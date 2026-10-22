package com.prefab.addon.client.gui;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.ConstructionInfo;
import com.prefab.addon.structure.CustomStructureBuilder;
import com.prefab.addon.work.ChallengeSessionManager;
import com.prefab.addon.work.MaterialCalculator;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "提交材料"列表的共享数据源 (纯客户端):
 * 枚举玩家"交了一点但没交完"的建筑并计算进度.
 *
 * <p>GuiExtensionPackBrowser 的"提交材料" tab 与 TerminalGui 的"材料"应用共用.
 * 数据源是 {@link ChallengeSessionManager} 的客户端镜像 (服务端权威快照的落盘镜像),
 * 通过 {@link ChallengeSessionManager#snapshotSubmissions(UUID)} 只读枚举,
 * 避开 getSubmitted() 的 computeIfAbsent 副作用.</p>
 *
 * <p>需求表算法与 {@code CustomStructureGui.startAsyncParse} 同源:
 * {@code MaterialCalculator.calculate(getNbtData())}, required 为空时用
 * 解析出的 BlockData 列表走 {@code fromBlocks} 兜底 (litematic/schem 格式).
 * 需求表按建筑 id 缓存 —— 会话内建筑 NBT 不变, 重解析代价高.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class SubmitMaterialIndex {

    /** 一条提交进度: 建筑 + 已交快照 + 需求表 + 汇总进度. */
    public static final class Entry {
        public final ConstructionInfo construction;
        /** 已交材料快照 (blockId → 已交数), 只读副本. */
        public final Map<String, Integer> submitted;
        /** 需求表; null = 需求表不可得 (空 NBT / 解析失败), 无法算进度. */
        public final MaterialCalculator.MaterialList required;
        /** Σ min(已交, 需求) —— 超交不计入进度. */
        public final int haveTotal;
        /** Σ 需求. */
        public final int needTotal;
        /** 全部需求已交齐 (与 CustomStructureGui.checkMaterialsReady 同语义). */
        public final boolean ready;

        Entry(ConstructionInfo construction, Map<String, Integer> submitted,
              MaterialCalculator.MaterialList required,
              int haveTotal, int needTotal, boolean ready) {
            this.construction = construction;
            this.submitted = submitted;
            this.required = required;
            this.haveTotal = haveTotal;
            this.needTotal = needTotal;
            this.ready = ready;
        }

        /** 进度 0~1; 需求表不可得时返回 -1. */
        public float progress() {
            return needTotal <= 0 ? -1f : Math.min(1f, (float) haveTotal / needTotal);
        }
    }

    /** 需求表缓存 (建筑 id → 需求表, null 值表示"算过了但不可得", 同样防重复算). */
    private static final Map<String, MaterialCalculator.MaterialList> REQUIRED_CACHE = new HashMap<>();

    /** 清需求表缓存 (建筑文件被编辑/重载扩展包时调用; 目前列表页每次打开重算成本可接受, 预留). */
    public static void clearRequiredCache() {
        REQUIRED_CACHE.clear();
    }

    /**
     * 枚举玩家有提交记录、且能在本地建筑列表里按 id 匹配上的建筑.
     * 匹配不上的 key (multiblock 占位 / 已删建筑) 直接跳过.
     * 排序: 交齐的在前, 之后按进度降序, 再按名称.
     */
    public static List<Entry> collect(List<ConstructionInfo> buildings, UUID playerId) {
        List<Entry> out = new ArrayList<>();
        Map<String, Map<String, Integer>> snapshot = ChallengeSessionManager.snapshotSubmissions(playerId);
        if (snapshot.isEmpty()) return out;
        Map<String, ConstructionInfo> byId = new HashMap<>();
        for (ConstructionInfo c : buildings) {
            byId.putIfAbsent(c.getId(), c);
        }
        for (Map.Entry<String, Map<String, Integer>> e : snapshot.entrySet()) {
            ConstructionInfo c = byId.get(e.getKey());
            if (c == null) continue;
            out.add(buildEntry(c, e.getValue(), playerId));
        }
        out.sort((a, b) -> {
            if (a.ready != b.ready) return a.ready ? -1 : 1;
            int byProgress = Float.compare(b.progress(), a.progress());
            if (byProgress != 0) return byProgress;
            return a.construction.getName().compareToIgnoreCase(b.construction.getName());
        });
        return out;
    }

    /** 单建筑条目 (需求表走缓存; 交齐判定复用 isReady, 与详情页门控一致). */
    public static Entry buildEntry(ConstructionInfo c, Map<String, Integer> submitted, UUID playerId) {
        MaterialCalculator.MaterialList req = requiredOf(c);
        int have = 0;
        int need = 0;
        boolean ready;
        if (req == null) {
            // 需求表不可得: 只汇总已交数量, 进度未知
            for (Integer v : submitted.values()) {
                if (v != null && v > 0) have += v;
            }
            ready = false;
        } else {
            for (Map.Entry<String, Integer> r : req.required.entrySet()) {
                need += r.getValue();
                have += Math.min(submitted.getOrDefault(r.getKey(), 0), r.getValue());
            }
            ready = ChallengeSessionManager.isReady(playerId, c.getId(), req.required);
        }
        return new Entry(c, submitted, req, have, need, ready);
    }

    /** 需求表 (带缓存): 与 CustomStructureGui.startAsyncParse 同算法, 同步计算. */
    public static MaterialCalculator.MaterialList requiredOf(ConstructionInfo c) {
        // computeIfAbsent 对 null 结果不缓存, 用显式 containsKey 让"不可得"也只算一次
        if (REQUIRED_CACHE.containsKey(c.getId())) {
            return REQUIRED_CACHE.get(c.getId());
        }
        MaterialCalculator.MaterialList ml = computeRequired(c);
        REQUIRED_CACHE.put(c.getId(), ml);
        return ml;
    }

    private static MaterialCalculator.MaterialList computeRequired(ConstructionInfo c) {
        try {
            byte[] nbtData = c.getNbtData();
            MaterialCalculator.MaterialList ml =
                    (nbtData == null || nbtData.length == 0) ? null : MaterialCalculator.calculate(nbtData);
            if (ml == null || ml.required.isEmpty()) {
                // calculate 只认 vanilla palette+blocks 格式; litematic/schem 重解析方块兜底
                CustomStructureBuilder.getInstance().loadStructureFromNbt(c);
                List<CustomStructureBuilder.BlockData> blocks =
                        CustomStructureBuilder.getInstance().parseStructureBlocks();
                if (blocks != null && !blocks.isEmpty()) {
                    ml = MaterialCalculator.fromBlocks(blocks);
                }
            }
            return (ml == null || ml.required.isEmpty()) ? null : ml;
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[SUBMIT-LIST] 需求表计算失败: {} ({})", c.getId(), c.getName(), t);
            return null;
        }
    }

    private SubmitMaterialIndex() {
    }
}
