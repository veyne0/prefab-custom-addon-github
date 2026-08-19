package com.prefab.addon.integration;

import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 客户端/服务端共用的"已放出建筑"位置索引.
 *
 * <p>Jade 集成需要根据玩家看的位置反查"这里属于哪个建筑":
 *  1. CloudBuildingManager.summon 成功 → register()
 *  2. CloudBuildingManager.recall 成功 → unregister()
 *  3. 玩家跨世界/进服重新加载 → rebuild from disk
 *  4. JadeBuildingProvider.appendBody → find(pos, dim) → 命中就在 tooltip 加 "建筑: XXX"
 *
 * <p>为什么用 List 不分维度?
 *  1. 云端建筑只属于单个玩家, 不会很多, 一般 < 100 个/玩家.
 *  2. 跨维度时直接比对 dim 引用即可 O(1) 过滤; 实际比对位置是 O(N), N 很小.
 *  3. 写多读少 + 玩家一般只看自己附近的方块, 直接 CopyOnWrite 简单安全.
 *
 * <p>线程安全:
 *  - register/unregister: 服务端 (or client cache 写) 调用.
 *  - find: Jade tick 频繁调, CopyOnWriteArrayList 迭代无锁.
 */
public final class BuildingDatabase {

    /** 单条记录. */
    public static final class Record {
        public final String id;             // CloudBuilding.id (UUID 字符串)
        public final String name;           // 建筑名
        public final String ownerName;      // 玩家名 (纯展示用, 可能 null)
        public final UUID owner;            // 玩家 UUID
        public final ResourceKey<Level> dim;  // 所在维度
        public final BlockPos origin;       // 放出的原点 (placedAt, 已旋转后的世界坐标)
        public final int sizeX, sizeY, sizeZ; // 包围盒 (相对坐标, 旋转前)
        public final int facingSteps;       // 旋转步数 (用于 1.21.1 反查时把 (lx, lz) 还原)

        public Record(String id, String name, String ownerName, UUID owner,
                      ResourceKey<Level> dim, BlockPos origin,
                      int sizeX, int sizeY, int sizeZ, int facingSteps) {
            this.id = id;
            this.name = name == null || name.isEmpty() ? "(未命名)" : name;
            this.ownerName = ownerName;
            this.owner = owner;
            this.dim = dim;
            this.origin = origin;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.facingSteps = facingSteps;
        }

        /**
         * 判断 {@code pos} 是否在本建筑的包围盒内.
         * 需要先按反向旋转把 (pos - origin) 还原到"未旋转"坐标系, 再与 (0,0,0)~(sizeX-1, sizeY-1, sizeZ-1) 比对.
         */
        public boolean contains(BlockPos pos) {
            if (pos == null) return false;
            int dx = pos.getX() - origin.getX();
            int dy = pos.getY() - origin.getY();
            int dz = pos.getZ() - origin.getZ();
            if (dy < 0 || dy >= sizeY) return false;
            // 反向旋转: facingSteps = 玩家预览时顺时针绕 Y 走了多少步,
            // 还原 (dx, dz) 需要的"反向步数" = (4 - facingSteps) % 4
            int inverse = (4 - facingSteps) % 4;
            for (int s = 0; s < inverse; s++) {
                int ndx =  dz;
                int ndz = -dx;
                dx = ndx;
                dz = ndz;
            }
            // 现在 (dx, dy, dz) 是未旋转坐标, 直接比对
            return dx >= 0 && dx < sizeX && dz >= 0 && dz < sizeZ;
        }
    }

    /** 所有已放出建筑. CopyOnWrite: 写少读多, 读不需锁. */
    private static final List<Record> RECORDS = new CopyOnWriteArrayList<>();

    private BuildingDatabase() {}

    // ============================================================
    // CRUD (服务端 + 客户端 cache 都用)
    // ============================================================

    /**
     * 注册一条已放出建筑. 同 id 已有则替换 (玩家可能在不同位置重放出).
     */
    public static void register(Record r) {
        if (r == null) return;
        // 先删同 id (容错: 重复 summon 不会双加)
        RECORDS.removeIf(x -> x.id.equals(r.id));
        RECORDS.add(r);
        PrefabCustomAddon.LOGGER.info("[BUILDING-DB] register: {} @ {} dim={} ({}x{}x{})",
            r.name, r.origin, r.dim.location(), r.sizeX, r.sizeY, r.sizeZ);
    }

    /**
     * 删除一条 (recall / delete 时调用).
     */
    public static void unregister(String id) {
        if (id == null) return;
        boolean removed = RECORDS.removeIf(x -> x.id.equals(id));
        if (removed) {
            PrefabCustomAddon.LOGGER.info("[BUILDING-DB] unregister: {}", id);
        }
    }

    /** 清空全部 (跨世界/重载时). */
    public static void clear() {
        RECORDS.clear();
    }

    /**
     * 查询: pos+dim 命中哪条建筑 (按注册顺序, 即后注册优先 — 玩家最近放出的更可能想看到).
     * @return Record 或 null
     */
    public static Record find(Level level, BlockPos pos) {
        if (level == null || pos == null) return null;
        ResourceKey<Level> key = level.dimension();
        // 倒序遍历, 后注册的优先
        for (int i = RECORDS.size() - 1; i >= 0; i--) {
            Record r = RECORDS.get(i);
            if (r.dim == key && r.contains(pos)) return r;
        }
        return null;
    }

    /** 当前注册的全部记录 (调试/统计用). */
    public static List<Record> all() {
        return new ArrayList<>(RECORDS);
    }

    public static int size() {
        return RECORDS.size();
    }
}
