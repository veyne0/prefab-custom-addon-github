package com.prefab.addon.cloud;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 云端建筑 (玩家私有存储).
 *
 * <p>每建造完一个建筑, 自动保存一份到这里. 玩家可以"收回" (从世界清掉方块, 标记 placed=false)
 * 或"放出" (在玩家当前位置重建, 标记 placed=true). 跨存档/跨世界保留.</p>
 *
 * <h2>存储</h2>
 * <pre>
 *   联机:  &lt;worldDir&gt;/data/prefab-cloudbuilds/&lt;uuid&gt;.nbt (服务端权威, 按玩家 UUID 归档)
 *   单机:  &lt;worldDir&gt;/data/prefab-cloudbuilds/localplayer.nbt
 * </pre>
 *
 * <h2>状态机</h2>
 * <pre>
 *   ┌────────┐  建造完成  ┌────────┐
 *   │ (新建) │ ────────→ │ 已收回 │ ←───────────┐
 *   └────────┘           └───┬────┘             │
 *                            │ 放出              │ 收回
 *                            ▼                   │
 *                        ┌────────┐             │
 *                        │ 已放出 │ ────────────┘
 *                        └────────┘
 * </pre>
 *
 * <h2>为什么方块数据要存在这里</h2>
 * 收回后 pack 可能被玩家删除/原版 mod 卸载, 重建时需要原始 NBT.
 * 每份云端建筑自带完整 blocks 列表, 跟当前 pack 解耦.
 */
public final class CloudBuilding {

    /** 快照格式版本. 0=老格式 (lx/ly/lz 已按 buildFacing 旋转, state 是原始), 1=新格式 (原始 pos+state, 旋转在使用时应用). */
    public static final int FORMAT_VERSION = 1;

    public final String id;            // UUID
    public String name;                // 建筑名 (玩家可改, 默认 = construction.displayName)
    public String packName;            // 来源 pack, "__standalone__" 表示本地单文件
    public String constructionId;      // 建筑 ID (ConstructionInfo.id)
    public long timestamp;             // 保存时间 (ms)
    public boolean placed;             // true = 在世界中
    public BlockPos placedAt;          // 放出位置 (placed=true 时有效, 单机/联机都用世界坐标)
    public Direction facing;           // 建筑最初建造时的朝向
    public int sizeX, sizeY, sizeZ;    // 包围盒 (基于原始坐标, 用作校验 + 收回时定位)
    /** 内部版本号 (0=老格式, 1=新格式). 老存档加载时会自动迁移并 in-memory 升到 1. */
    public int version;
    public final List<BlockSnapshot> blocks = new ArrayList<>();  // 原始 (未旋转) 方块快照
    public final List<TileEntitySnapshot> tileEntities = new ArrayList<>();  // 备份时容器/告示牌/漏斗里的内容
    public byte[] thumbnailPng;        // 可选, 64x64 缩略图, 空 = 用占位符

    public CloudBuilding(String id) {
        this.id = id;
        this.placed = false;
        this.placedAt = BlockPos.ZERO;
        this.facing = Direction.SOUTH;
        this.version = FORMAT_VERSION;
    }

    public String getStatusLabel() {
        return placed ? "已放出" : "已收回";
    }

    /** NBT 序列化. */
    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putString("name", name == null ? "" : name);
        tag.putString("packName", packName == null ? "" : packName);
        tag.putString("constructionId", constructionId == null ? "" : constructionId);
        tag.putLong("timestamp", timestamp);
        tag.putBoolean("placed", placed);
        writeBlockPos(tag, "placedAt", placedAt == null ? BlockPos.ZERO : placedAt);
        tag.putString("facing", facing == null ? "south" : facing.getName());
        tag.putInt("sizeX", sizeX);
        tag.putInt("sizeY", sizeY);
        tag.putInt("sizeZ", sizeZ);
        tag.putInt("version", version);

        ListTag blocksList = new ListTag();
        for (BlockSnapshot bs : blocks) {
            blocksList.add(bs.toNbt());
        }
        tag.put("blocks", blocksList);

        ListTag teList = new ListTag();
        for (TileEntitySnapshot te : tileEntities) {
            teList.add(te.toNbt());
        }
        tag.put("tileEntities", teList);

        if (thumbnailPng != null && thumbnailPng.length > 0) {
            tag.putByteArray("thumbnail", thumbnailPng);
        }
        return tag;
    }

    /** NBT 反序列化. */
    public static CloudBuilding fromNbt(CompoundTag tag) {
        String id = tag.getString("id");
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
        }
        CloudBuilding b = new CloudBuilding(id);
        b.name = tag.getString("name");
        b.packName = tag.getString("packName");
        b.constructionId = tag.getString("constructionId");
        b.timestamp = tag.getLong("timestamp");
        b.placed = tag.getBoolean("placed");
        b.placedAt = readBlockPos(tag.getCompound("placedAt"));
        b.facing = parseFacing(tag.getString("facing"));
        b.sizeX = tag.getInt("sizeX");
        b.sizeY = tag.getInt("sizeY");
        b.sizeZ = tag.getInt("sizeZ");
        // version: 缺省 = 0 (老格式, 已旋转的 lx/ly/lz + 原始 state)
        b.version = tag.contains("version") ? tag.getInt("version") : 0;

        ListTag blocksList = tag.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocksList.size(); i++) {
            b.blocks.add(BlockSnapshot.fromNbt(blocksList.getCompound(i)));
        }

        ListTag teList = tag.getList("tileEntities", Tag.TAG_COMPOUND);
        for (int i = 0; i < teList.size(); i++) {
            b.tileEntities.add(TileEntitySnapshot.fromNbt(teList.getCompound(i)));
        }

        // === 老格式迁移: 把已旋转的 lx/ly/lz 反旋转回原始 pos ===
        // state 已经是原始的, 不需要动. 升到 1, 内存里以后就是新格式.
        // blocks 和 tileEntities 都加载后再迁移, 否则 tileEntities 还没数据.
        if (b.version < FORMAT_VERSION) {
            int steps = facingToRotationSteps(b.facing);
            int inverse = (4 - steps) % 4;
            for (BlockSnapshot bs : b.blocks) {
                int lx = bs.lx, lz = bs.lz;
                for (int s = 0; s < inverse; s++) {
                    int nlx =  lz;
                    int nlz = -lx;
                    lx = nlx;
                    lz = nlz;
                }
                bs.lx = lx;
                bs.lz = lz;
            }
            // tileEntities 也要一起迁移, 否则 summon 时 TE 会被放到错误位置
            for (TileEntitySnapshot te : b.tileEntities) {
                int lx = te.lx, lz = te.lz;
                for (int s = 0; s < inverse; s++) {
                    int nlx =  lz;
                    int nlz = -lx;
                    lx = nlx;
                    lz = nlz;
                }
                te.lx = lx;
                te.lz = lz;
            }
            b.version = FORMAT_VERSION;
        }

        if (tag.contains("thumbnail")) {
            b.thumbnailPng = tag.getByteArray("thumbnail");
        }
        return b;
    }

    private static Direction parseFacing(String name) {
        if (name == null || name.isEmpty()) return Direction.SOUTH;
        try {
            Direction d = Direction.byName(name);
            return d == null ? Direction.SOUTH : d;
        } catch (Exception e) {
            return Direction.SOUTH;
        }
    }

    /**
     * Direction → 90° 旋转步数 (绕 Y 轴 CCW 俯视, 跟 AsyncBuildManager / CustomStructureBuilder 保持一致).
     * SOUTH=0, EAST=1, NORTH=2, WEST=3.
     */
    public static int facingToRotationSteps(Direction facing) {
        if (facing == null) return 0;
        return switch (facing) {
            case SOUTH -> 0;
            case EAST  -> 1;
            case NORTH -> 2;
            case WEST  -> 3;
            default    -> 0;
        };
    }

    /**
     * 绕 Y 轴 (steps 次) 旋转 (x, z) → (z, -x) 公式, 返回新的 (lx, lz).
     * y 不动. 跟 AsyncBuildManager.processTick 和 offsetStructureBlocks 完全一致.
     */
    public static int rotateLX(int lx, int lz, int steps) {
        steps = ((steps % 4) + 4) % 4;
        for (int s = 0; s < steps; s++) {
            int nlx =  lz;
            int nlz = -lx;
            lx = nlx;
            lz = nlz;
        }
        return lx;
    }

    /** 配合 rotateLX, 拿到旋转后的 lz. */
    public static int rotateLZ(int lx, int lz, int steps) {
        steps = ((steps % 4) + 4) % 4;
        for (int s = 0; s < steps; s++) {
            int nlx =  lz;
            int nlz = -lx;
            lx = nlx;
            lz = nlz;
        }
        return lz;
    }

    // ============================================================
    // BlockPos NBT 工具 (自实现, 避开 1.21.1 NbtUtils.readBlockPos 签名变化)
    // ============================================================

    /** 写 BlockPos, 格式 {x,y,z}. */
    public static void writeBlockPos(CompoundTag parent, String key, BlockPos pos) {
        CompoundTag t = new CompoundTag();
        t.putInt("X", pos.getX());
        t.putInt("Y", pos.getY());
        t.putInt("Z", pos.getZ());
        parent.put(key, t);
    }

    /** 读 BlockPos. 失败返回 ZERO. */
    public static BlockPos readBlockPos(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) return BlockPos.ZERO;
        try {
            int x = tag.getInt("X");
            int y = tag.getInt("Y");
            int z = tag.getInt("Z");
            // 兼容: 某些工具写小写 x/y/z
            if (!tag.contains("X") && tag.contains("x")) x = tag.getInt("x");
            if (!tag.contains("Y") && tag.contains("y")) y = tag.getInt("y");
            if (!tag.contains("Z") && tag.contains("z")) z = tag.getInt("z");
            return new BlockPos(x, y, z);
        } catch (Exception e) {
            return BlockPos.ZERO;
        }
    }

    // ============================================================
    // BlockState NBT 工具 (自实现, 避开 1.21.1 NbtUtils.readBlockState/writeBlockState 签名变化)
    // 用 vanilla structure 格式: {Name, Properties?}
    // ============================================================

    /** 写 BlockState. 失败返回空 tag. */
    public static CompoundTag writeBlockState(BlockState state) {
        CompoundTag tag = new CompoundTag();
        if (state == null) return tag;
        try {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            tag.putString("Name", id == null ? "minecraft:air" : id.toString());
            Map<Property<?>, Comparable<?>> props = state.getValues();
            if (!props.isEmpty()) {
                CompoundTag p = new CompoundTag();
                for (Map.Entry<Property<?>, Comparable<?>> e : props.entrySet()) {
                    p.putString(e.getKey().getName(), e.getValue().toString());
                }
                tag.put("Properties", p);
            }
        } catch (Exception ignored) {}
        return tag;
    }

    /** 读 BlockState. 失败返回 null. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static BlockState readBlockState(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) return null;
        try {
            if (!tag.contains("Name", Tag.TAG_STRING)) return null;
            String name = tag.getString("Name");
            Block block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(name))
                .orElse(Blocks.AIR);
            BlockState state = block.defaultBlockState();
            if (tag.contains("Properties", Tag.TAG_COMPOUND) && block != Blocks.AIR) {
                CompoundTag props = tag.getCompound("Properties");
                for (String key : props.getAllKeys()) {
                    Property<?> property = block.getStateDefinition().getProperty(key);
                    if (property == null) continue;
                    String value = props.getString(key);
                    Optional<?> opt = property.getValue(value);
                    if (opt.isEmpty()) continue;
                    try {
                        state = state.setValue((Property) property, (Comparable) opt.get());
                    } catch (Throwable ignored) {}
                }
            }
            return state;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * TileEntity 快照: 相对坐标 + NBT.
     * 用来在放出时恢复容器/告示牌/漏斗的内容.
     */
    public static final class TileEntitySnapshot {
        public int lx, ly, lz;
        public CompoundTag nbt;

        public TileEntitySnapshot() {}

        public TileEntitySnapshot(int lx, int ly, int lz, CompoundTag nbt) {
            this.lx = lx;
            this.ly = ly;
            this.lz = lz;
            this.nbt = nbt;
        }

        public CompoundTag toNbt() {
            CompoundTag t = new CompoundTag();
            t.putInt("x", lx);
            t.putInt("y", ly);
            t.putInt("z", lz);
            if (nbt != null) t.put("data", nbt);
            return t;
        }

        public static TileEntitySnapshot fromNbt(CompoundTag t) {
            TileEntitySnapshot s = new TileEntitySnapshot();
            s.lx = t.getInt("x");
            s.ly = t.getInt("y");
            s.lz = t.getInt("z");
            if (t.contains("data")) s.nbt = t.getCompound("data");
            return s;
        }
    }

    /**
     * 单个方块快照: 相对坐标 + BlockState NBT.
     * 用 vanilla structure 格式 (Name + Properties) 序列化, 跟 vanilla 1.21+ 兼容.
     */
    public static final class BlockSnapshot {
        public int lx, ly, lz;              // 相对坐标 (相对 placedAt 或 origin)
        public CompoundTag stateNbt;        // 自实现的 BlockState NBT

        public BlockSnapshot() {}

        public BlockSnapshot(int lx, int ly, int lz, BlockState state) {
            this.lx = lx;
            this.ly = ly;
            this.lz = lz;
            this.stateNbt = writeBlockState(state);
        }

        public BlockState getState() {
            return readBlockState(stateNbt);
        }

        public CompoundTag toNbt() {
            CompoundTag t = new CompoundTag();
            t.putInt("x", lx);
            t.putInt("y", ly);
            t.putInt("z", lz);
            if (stateNbt != null) {
                t.put("state", stateNbt);
            }
            return t;
        }

        public static BlockSnapshot fromNbt(CompoundTag t) {
            BlockSnapshot s = new BlockSnapshot();
            s.lx = t.getInt("x");
            s.ly = t.getInt("y");
            s.lz = t.getInt("z");
            if (t.contains("state")) {
                s.stateNbt = t.getCompound("state");
            }
            return s;
        }
    }
}
