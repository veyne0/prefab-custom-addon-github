package com.prefab.addon.items.mini;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;

/**
 * 迷你建筑转换器的选区状态 (角点 1 + 角点 2).
 *
 * <p>序列化到物品 NBT, 玩家右键切换状态机:
 * <pre>
 *   IDLE → 点 1 → AWAIT_CORNER2 → 点 2 → 转换 → IDLE
 * </pre>
 */
public class MiniBuildingSelectionState {

    private static final String KEY_HAS_C1 = "has_corner1";
    private static final String KEY_C1 = "corner1";
    private static final String KEY_C2 = "corner2";

    private BlockPos corner1;
    private BlockPos corner2;

    public static MiniBuildingSelectionState fromNBT(CompoundTag tag) {
        MiniBuildingSelectionState s = new MiniBuildingSelectionState();
        if (tag.getBoolean(KEY_HAS_C1)) {
            CompoundTag c1 = tag.getCompound(KEY_C1);
            s.corner1 = new BlockPos(c1.getInt("x"), c1.getInt("y"), c1.getInt("z"));
        }
        if (tag.contains(KEY_C2)) {
            CompoundTag c2 = tag.getCompound(KEY_C2);
            s.corner2 = new BlockPos(c2.getInt("x"), c2.getInt("y"), c2.getInt("z"));
        }
        return s;
    }

    public void saveToNBT(CompoundTag tag) {
        tag.remove(KEY_HAS_C1);
        tag.remove(KEY_C1);
        tag.remove(KEY_C2);

        if (corner1 != null) {
            tag.putBoolean(KEY_HAS_C1, true);
            CompoundTag c1 = new CompoundTag();
            c1.putInt("x", corner1.getX());
            c1.putInt("y", corner1.getY());
            c1.putInt("z", corner1.getZ());
            tag.put(KEY_C1, c1);
        }
        if (corner2 != null) {
            CompoundTag c2 = new CompoundTag();
            c2.putInt("x", corner2.getX());
            c2.putInt("y", corner2.getY());
            c2.putInt("z", corner2.getZ());
            tag.put(KEY_C2, c2);
        }
    }

    public boolean hasCorner1() {
        return corner1 != null;
    }

    public boolean hasBothCorners() {
        return corner1 != null && corner2 != null;
    }

    public BlockPos getCorner1() {
        return corner1;
    }

    public BlockPos getCorner2() {
        return corner2;
    }

    public void setCorner1(BlockPos pos) {
        this.corner1 = pos.immutable();
        this.corner2 = null;
    }

    public void setCorner2(BlockPos pos) {
        this.corner2 = pos.immutable();
    }

    public AABB getBoundingBox() {
        if (corner1 == null || corner2 == null) return null;
        return new AABB(
            corner1.getX(), corner1.getY(), corner1.getZ(),
            corner2.getX() + 1, corner2.getY() + 1, corner2.getZ() + 1);
    }

    public void reset() {
        this.corner1 = null;
        this.corner2 = null;
    }
}
