package com.prefab.addon.work;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 服务端材料提交账本 - 挑战模式"已提交材料"的服务端权威记录.
 *
 * <p><b>为什么需要这个类 (严重刷物品 bug 修复)</b>:
 * 旧实现里 {@link ChallengeSessionManager#submit} / MaterialSubmissionGui 只在**客户端**
 * LocalPlayer 的背包上扣材料, 服务端背包从头到尾没动过. 玩家之后右键打开任何容器 GUI
 * (箱子/多方块结构界面) 时, 服务端把自己那份"没扣过"的背包同步回客户端 →
 * 已提交的材料全部复活, 可以无限刷.</p>
 *
 * <p>现在: 客户端点"提交" → 发 SubmitMaterialsPayload → 本类在**服务端背包**上真实扣除
 * (同时累计服务端账本) → 回包 MaterialSubmitResultPayload 告诉客户端实际扣了多少 →
 * 客户端只把服务端确认的数量记进自己的镜像 session (用于 UI 显示 / isReady 门控).</p>
 *
 * <p>持久化: <存档目录>/prefab_custom_addon/material_ledger/<UUID>.json
 * (单机=存档文件夹, 专用服=世界文件夹; 重启服务端进度不丢).</p>
 */
public class ServerMaterialLedger {

    // 玩家 UUID → (sessionKey → (blockId → 累计已提交))
    private static final Map<UUID, Map<String, Map<String, Integer>>> LEDGER = new HashMap<>();
    private static final Set<UUID> LOADED = new HashSet<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LEDGER_TYPE = new TypeToken<Map<String, Map<String, Integer>>>(){}.getType();

    /**
     * 服务端提交结果 (发给客户端回包用).
     * submittedAfter = 该 session 处理后的**完整累计快照** (客户端直接拿它替换本地镜像, 不做增量合并).
     */
    public record Result(Map<String, Integer> submittedAfter, boolean allDone) {}

    /**
     * 在服务端背包上执行材料提交: 每种材料扣 min(背包实有, 还差的数量).
     *
     * @param player       服务端玩家
     * @param sessionKey   会话 key (建筑 id 或 "multiblock:xxx")
     * @param required     该次提交针对的需求表 (blockId → 总需求; 单卡片提交时只含一项), 扣除只按它执行
     * @param fullRequired 该 session 的**完整**需求表, 仅供判定 allDone (单卡片提交时 required 只有一项,
     *                     若只看它, 交齐这一种就会误报"全部已交齐"而其它材料还缺)
     */
    public static Result submit(ServerPlayer player, String sessionKey,
                                Map<String, Integer> required, Map<String, Integer> fullRequired) {
        UUID playerId = player.getUUID();
        if (!LOADED.contains(playerId)) loadFromDisk(player);
        Map<String, Integer> submitted = LEDGER
            .computeIfAbsent(playerId, k -> new HashMap<>())
            .computeIfAbsent(sessionKey, k -> new LinkedHashMap<>());

        Inventory inv = player.getInventory();
        Map<String, Integer> deducted = new LinkedHashMap<>();

        for (Map.Entry<String, Integer> e : required.entrySet()) {
            String blockId = e.getKey();
            int need = e.getValue();
            int remaining = need - submitted.getOrDefault(blockId, 0);
            if (remaining <= 0) continue;

            int available = MaterialCalculator.countInInventory(inv, blockId);
            int take = Math.min(available, remaining);
            if (take <= 0) continue;

            int removed = removeFromInventory(inv, blockId, take);
            if (removed > 0) {
                submitted.merge(blockId, removed, Integer::sum);
                deducted.merge(blockId, removed, Integer::sum);
            }
        }

        if (!deducted.isEmpty()) {
            inv.setChanged();
            if (player.containerMenu != null) player.containerMenu.broadcastChanges();
            saveToDisk(player);
        }

        // allDone 必须按**完整需求表**判定: required 可能只是单卡片提交的一项,
        // 只看它会在其它材料还缺时误报"全部材料已交齐"
        Map<String, Integer> checkAgainst =
            (fullRequired == null || fullRequired.isEmpty()) ? required : fullRequired;
        boolean allDone = true;
        for (Map.Entry<String, Integer> e : checkAgainst.entrySet()) {
            if (submitted.getOrDefault(e.getKey(), 0) < e.getValue()) { allDone = false; break; }
        }

        int total = deducted.values().stream().mapToInt(Integer::intValue).sum();
        PrefabCustomAddon.LOGGER.info("[MATERIAL-LEDGER] 服务端提交: 玩家={} session={} 本次扣={} 全齐={}",
            player.getName().getString(), sessionKey, total, allDone);
        // 回传完整累计快照 (副本, 防止外部改动内部 map)
        return new Result(new LinkedHashMap<>(submitted), allDone);
    }

    /**
     * 清空某玩家某 session 的服务端账本.
     * 调用时机: ① 建造成功后 (handleBuild / MultiblockPlacer.place, 与客户端 reset 对齐);
     *          ② 客户端点"重置进度"按钮 (ResetMaterialLedgerPayload).
     */
    public static void reset(ServerPlayer player, String sessionKey) {
        UUID playerId = player.getUUID();
        if (!LOADED.contains(playerId)) loadFromDisk(player);
        Map<String, Map<String, Integer>> playerMap = LEDGER.get(playerId);
        if (playerMap != null && playerMap.remove(sessionKey) != null) {
            saveToDisk(player);
            PrefabCustomAddon.LOGGER.info("[MATERIAL-LEDGER] 服务端账本已清空: 玩家={} session={}",
                player.getName().getString(), sessionKey);
        }
    }

    // ============================================================
    // 背包扣除 (服务端权威)
    // ============================================================

    /** 按 blockId (兼容 block→item 映射) 从背包扣除 count 个, 返回实际扣除数量 */
    private static int removeFromInventory(Inventory inv, String blockId, int count) {
        ResourceLocation rl = ResourceLocation.tryParse(blockId);
        if (rl == null) return 0;
        Item target = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
        if (target == null) {
            net.minecraft.world.level.block.Block b =
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
            if (b != null) target = b.asItem();
        }
        if (target == null) return 0;

        int left = count;
        for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || s.getItem() != target) continue;
            int n = Math.min(left, s.getCount());
            s.shrink(n);
            if (s.getCount() <= 0) inv.setItem(i, ItemStack.EMPTY);
            left -= n;
        }
        return count - left;
    }

    // ============================================================
    // 持久化
    // ============================================================

    private static Path getLedgerFile(ServerPlayer player) {
        try {
            MinecraftServer server = player.getServer();
            if (server == null) return null;
            Path dir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("prefab_custom_addon").resolve("material_ledger");
            Files.createDirectories(dir);
            return dir.resolve(player.getUUID() + ".json");
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("[MATERIAL-LEDGER] 创建账本目录失败", e);
            return null;
        }
    }

    private static void saveToDisk(ServerPlayer player) {
        Path file = getLedgerFile(player);
        if (file == null) return;
        Map<String, Map<String, Integer>> playerMap =
            LEDGER.getOrDefault(player.getUUID(), new HashMap<>());
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file.toFile()), StandardCharsets.UTF_8)) {
            GSON.toJson(playerMap, LEDGER_TYPE, w);
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("[MATERIAL-LEDGER] 保存失败: {}", file, e);
        }
    }

    private static void loadFromDisk(ServerPlayer player) {
        LOADED.add(player.getUUID());
        Path file = getLedgerFile(player);
        if (file == null || !Files.exists(file)) return;
        try (Reader r = new InputStreamReader(new FileInputStream(file.toFile()), StandardCharsets.UTF_8)) {
            Map<String, Map<String, Integer>> data = GSON.fromJson(r, LEDGER_TYPE);
            if (data != null) {
                LEDGER.put(player.getUUID(), data);
                PrefabCustomAddon.LOGGER.info("[MATERIAL-LEDGER] 从磁盘恢复: 玩家={} ({} session)",
                    player.getName().getString(), data.size());
            }
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.error("[MATERIAL-LEDGER] 读取/解析失败: {}", file, e);
        }
    }
}
