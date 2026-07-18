package com.prefab.addon.work;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

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

    /**
     * 获取玩家在某建筑下的提交进度 (可写版本).
     * 与 getSubmitted() 不同的是, 这个方法返回的是 SESSIONS 里实际的 LinkedHashMap 引用,
     * 修改后必须调用 saveToDisk() 才会持久化.
     * 用于单卡片提交等需要直接修改 session 累计数量的场景.
     */
    public static Map<String, Integer> getSubmittedAsMutable(UUID playerId, String constructionId) {
        if (!LOADED.contains(playerId)) {
            loadFromDisk(playerId);
        }
        Map<String, Map<String, Integer>> playerMap =
            SESSIONS.computeIfAbsent(playerId, k -> new HashMap<>());
        return playerMap.computeIfAbsent(constructionId, k -> new LinkedHashMap<>());
    }

    /**
     * 从背包扣减材料, 累计到 session.
     * 每次调用只扣"背包里当前实际有"的, 不要求一次性交齐.
     *
     * @return SubmissionResult - 包含本次扣减了多少, 累计还差多少
     */
    public static SubmissionResult submit(Inventory inv, String constructionId,
                                           Map<String, Integer> required) {
        UUID playerId;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return new SubmissionResult(0, 0, 0, false);
        }
        playerId = player.getUUID();
        if (!LOADED.contains(playerId)) loadFromDisk(playerId);

        Map<String, Integer> submitted = getSubmitted(playerId, constructionId);
        int thisRoundDeducted = 0;
        int totalStillMissing = 0;

        for (Map.Entry<String, Integer> e : required.entrySet()) {
            String blockId = e.getKey();
            int need = e.getValue();
            int alreadySubmitted = submitted.getOrDefault(blockId, 0);
            int remaining = need - alreadySubmitted;
            if (remaining <= 0) continue;

            // 玩家背包里实际有多少 (按 block id 解析成 item)
            int available = MaterialCalculator.countInInventory(inv, blockId);
            int take = Math.min(available, remaining);
            if (take <= 0) {
                totalStillMissing += remaining;
                continue;
            }

            // 从背包扣减
            int toRemove = take;
            for (int i = 0; i < inv.getContainerSize() && toRemove > 0; i++) {
                ItemStack s = inv.getItem(i);
                if (s.isEmpty()) continue;
                ResourceLocation target = ResourceLocation.tryParse(blockId);
                if (target == null) continue;
                if (matchesItem(s, target)) {
                    int shrink = Math.min(s.getCount(), toRemove);
                    s.shrink(shrink);
                    toRemove -= shrink;
                }
            }
            int actuallyDeducted = take - toRemove;
            if (actuallyDeducted > 0) {
                submitted.merge(blockId, actuallyDeducted, Integer::sum);
                thisRoundDeducted += actuallyDeducted;
            }
            int afterTake = remaining - actuallyDeducted;
            if (afterTake > 0) {
                totalStillMissing += afterTake;
            }
        }
        inv.setChanged();
        if (player.containerMenu != null) player.containerMenu.broadcastChanges();

        // === 持久化: 立刻写回磁盘, 这样下次进游戏还能拿到 ===
        saveToDisk(playerId);

        boolean allDone = totalStillMissing == 0;
        PrefabCustomAddon.LOGGER.info("[CHALLENGE-SESSION] submit: 本次扣 {} 个, 还差 {} 个, 完成={}",
            thisRoundDeducted, totalStillMissing, allDone);
        return new SubmissionResult(thisRoundDeducted, totalStillMissing,
            submitted.values().stream().mapToInt(Integer::intValue).sum(), allDone);
    }

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

    public static class SubmissionResult {
        public final int thisRoundDeducted;  // 本次扣减的方块数
        public final int totalStillMissing;  // 全部还差多少 (累计)
        public final int totalSubmittedSoFar;  // 累计已提交
        public final boolean allDone;

        public SubmissionResult(int thisRoundDeducted, int totalStillMissing,
                                int totalSubmittedSoFar, boolean allDone) {
            this.thisRoundDeducted = thisRoundDeducted;
            this.totalStillMissing = totalStillMissing;
            this.totalSubmittedSoFar = totalSubmittedSoFar;
            this.allDone = allDone;
        }

        public String getSummary() {
            if (allDone) return "✓ 全部材料已提交!";
            if (thisRoundDeducted == 0) return "✗ 背包里没有可提交的材料";
            return "本次提交 " + thisRoundDeducted + " 个, 还差 " + totalStillMissing + " 个";
        }
    }

    private static boolean matchesItem(ItemStack s, ResourceLocation rl) {
        ResourceLocation itemLoc = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem());
        if (itemLoc.equals(rl)) return true;
        net.minecraft.world.level.block.Block b =
            net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
        if (b != null && net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(b.asItem()).equals(itemLoc)) {
            return true;
        }
        return false;
    }
}
