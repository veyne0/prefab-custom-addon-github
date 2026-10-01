package com.prefab.addon.work;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.client.Minecraft;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 挑战模式会话管理 - 跟踪每玩家每建筑的"已提交材料"累计进度.
 *
 * 设计: 玩家可以分多次提交材料, 每次提交扣减"背包里实际有"的, 累计到 session.
 * Session 在游戏内有效, 玩家关闭 GUI / 重新打开 / 重启 mod 都不丢失 (内存 + 玩家数据).
 *
 * 持久化: 把每个玩家的 SESSIONS 存到 <游戏目录>/prefab_custom_addon/challenge_sessions/<UUID>.json
 * 这种方式的好处:
 *   1) 单人 / 多人客户端都用同一份文件
 *   2) 玩家退出游戏再进来, 文件还在
 *   3) 玩家跨世界也保留
 *   4) 玩家在服务端无法控制本机的文件, 防作弊
 *
 * 字段: 玩家UUID + 建筑ID -> Map<blockId, 已提交数量>
 */
public class ChallengeSessionManager {
    // 玩家 UUID → (建筑 ID → 累计已提交的材料)
    private static final Map<UUID, Map<String, Map<String, Integer>>> SESSIONS = new HashMap<>();

    // Gson 用于序列化
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type SESSION_TYPE = new TypeToken<Map<String, Map<String, Integer>>>(){}.getType();

    // 每个玩家的"已加载"标记, 避免每次 submit 都重新 load
    private static final java.util.Set<UUID> LOADED = new java.util.HashSet<>();

    /** 清空指定玩家的全部 session (例如切世界) */
    public static void clearPlayer(UUID playerId) {
        SESSIONS.remove(playerId);
        LOADED.remove(playerId);
        PrefabCustomAddon.LOGGER.info("[CHALLENGE-SESSION] 清空玩家 session: {}", playerId);
    }

    /** 清空所有 session (测试用) */
    public static void clearAll() {
        SESSIONS.clear();
        LOADED.clear();
    }

    /** 获取玩家在某建筑下的提交进度 (不存在则返回空 map) */
    public static Map<String, Integer> getSubmitted(UUID playerId, String constructionId) {
        // 懒加载: 第一次访问某个玩家时, 从磁盘读
        if (!LOADED.contains(playerId)) {
            loadFromDisk(playerId);
        }
        return SESSIONS
            .computeIfAbsent(playerId, k -> new HashMap<>())
            .computeIfAbsent(constructionId, k -> new LinkedHashMap<>());
    }

    // [已移除] getSubmittedAsMutable(): 旧单卡片提交流程直接改客户端镜像用,
    // 现在提交进度全由服务端回传快照覆盖 (applyServerState), 不再需要可写引用.

    // [已移除] 旧的客户端 submit(): 只在**客户端**背包扣材料, 服务端不知情 →
    // 玩家打开任何容器 GUI 后服务端把"没扣过"的背包同步回来, 材料复活 (刷物品 bug).
    // 材料扣除已改为**服务端权威**: 见 ServerMaterialLedger.submit + SubmitMaterialsPayload.
    // 客户端本类现在只保存服务端回传的镜像快照 (applyServerState), 供 UI / isReady 门控用.

    /**
     * 检查某建筑是否已提交完毕 (即累计提交 == 需求)
     */
    public static boolean isReady(UUID playerId, String constructionId,
                                   Map<String, Integer> required) {
        if (!LOADED.contains(playerId)) loadFromDisk(playerId);
        Map<String, Integer> submitted = getSubmitted(playerId, constructionId);
        for (Map.Entry<String, Integer> e : required.entrySet()) {
            int need = e.getValue();
            int have = submitted.getOrDefault(e.getKey(), 0);
            if (have < need) return false;
        }
        return true;
    }

    /** 重置某玩家的某建筑 session (例如建完后清掉, 玩家可以重新挑战) */
    public static void reset(UUID playerId, String constructionId) {
        if (!LOADED.contains(playerId)) loadFromDisk(playerId);
        Map<String, Map<String, Integer>> playerMap = SESSIONS.get(playerId);
        if (playerMap != null) {
            playerMap.remove(constructionId);
            PrefabCustomAddon.LOGGER.info("[CHALLENGE-SESSION] 重置: 玩家={} 建筑={}", playerId, constructionId);
            saveToDisk(playerId);
        }
    }

    /**
     * 用**服务端权威快照**替换本地镜像 session (客户端收到 MaterialSubmitResultPayload 时调用).
     *
     * 修复刷物品 bug 后, 客户端不再自己扣背包/累计提交量 —— 材料扣除全在服务端
     * (ServerMaterialLedger) 完成, 客户端这个 session 只是给 UI 显示 / isReady 门控用的镜像.
     * 这里直接用服务端回传的完整快照覆盖, 保证两侧进度一致.
     *
     * @param serverState 服务端账本处理后的完整累计 (重置时为空 map)
     */
    public static void applyServerState(UUID playerId, String constructionId, Map<String, Integer> serverState) {
        if (!LOADED.contains(playerId)) loadFromDisk(playerId);
        Map<String, Map<String, Integer>> playerMap = SESSIONS.computeIfAbsent(playerId, k -> new HashMap<>());
        LinkedHashMap<String, Integer> mirror = new LinkedHashMap<>();
        if (serverState != null) mirror.putAll(serverState);
        playerMap.put(constructionId, mirror);
        saveToDisk(playerId);
    }

    /**
     * 拿每个玩家的 session 文件路径: <游戏目录>/prefab_custom_addon/challenge_sessions/<UUID>.json
     */
    private static Path getSessionFile(UUID playerId) {
        try {
            File gameDir = Minecraft.getInstance().gameDirectory;
            Path dir = gameDir.toPath().resolve("prefab_custom_addon").resolve("challenge_sessions");
            Files.createDirectories(dir);
            return dir.resolve(playerId.toString() + ".json");
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("[CHALLENGE-SESSION] 创建 session 目录失败", e);
            return null;
        }
    }

    public static void saveToDisk(UUID playerId) {
        Path file = getSessionFile(playerId);
        if (file == null) return;
        Map<String, Map<String, Integer>> playerMap = SESSIONS.getOrDefault(playerId, new HashMap<>());
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file.toFile()), StandardCharsets.UTF_8)) {
            GSON.toJson(playerMap, SESSION_TYPE, w);
            PrefabCustomAddon.LOGGER.info("[CHALLENGE-SESSION] 持久化: 玩家={} -> {} ({} 建筑)",
                playerId, file, playerMap.size());
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("[CHALLENGE-SESSION] 保存失败: {}", file, e);
        }
    }

    private static void loadFromDisk(UUID playerId) {
        LOADED.add(playerId);
        Path file = getSessionFile(playerId);
        if (file == null || !Files.exists(file)) return;
        try (Reader r = new InputStreamReader(new FileInputStream(file.toFile()), StandardCharsets.UTF_8)) {
            Map<String, Map<String, Integer>> data = GSON.fromJson(r, SESSION_TYPE);
            if (data == null) return;
            SESSIONS.put(playerId, data);
            int total = data.values().stream().mapToInt(m -> m.values().stream().mapToInt(Integer::intValue).sum()).sum();
            PrefabCustomAddon.LOGGER.info("[CHALLENGE-SESSION] 从磁盘恢复: 玩家={} {} 建筑, 共 {} 个材料",
                playerId, data.size(), total);
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("[CHALLENGE-SESSION] 读取失败: {}", file, e);
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.error("[CHALLENGE-SESSION] 解析失败 (文件已损坏? 删掉重来): {}", file, e);
        }
    }

    // [已移除] SubmissionResult / matchesItem: 仅服务于已删除的客户端 submit().
    // 服务端提交结果由 ServerMaterialLedger.Result 承担.
}
