package com.prefab.addon.items;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 操作手杖 per-player 状态. 客户端 + 服务端各持一份 (通过 payload 同步).
 *
 * <p>生命周期:
 * <ol>
 *   <li>玩家手持手杖右键 → {@code RegionSelector.startWand(player)} 启动选区</li>
 *   <li>选完两个角点 + ALT 确认 → 服务端扫描方块 → {@link #cachedBlocks} 填充</li>
 *   <li>玩家进入"预览模式", {@link #previewPos} 跟随玩家视线更新</li>
 *   <li>右键建造 (或按 ALT 确认) → 清空状态</li>
 *   <li>CTRL → 取消, 清空状态</li>
 * </ol>
 */
public class OperationWandState {

    /**
     * 手杖模式.
     *
     * <ul>
     *   <li>MOVE: 移动模式. 右键建造 = 删除原区域 + 在目标位置放方块</li>
     *   <li>COPY: 复制模式. 右键建造 = 仅在目标位置放方块, 原区域保留.
     *       切换时如果不在创造模式, 拒绝切换 (HUD 提示). 默认仅创造可用.</li>
     * </ul>
     */
    public enum Mode {
        MOVE, COPY
    }

    /**
     * 缓存的方块条目: 局部坐标 (相对 origin) + BlockState.
     * 跟 MiniBuildingBlockEntity.CachedBlockEntry 字段一致, 但独立存放, 避免耦合.
     */
    public static class CachedBlock {
        public final int x, y, z;  // 相对 origin 的局部坐标 (0..size-1)
        public final BlockState state;

        public CachedBlock(int x, int y, int z, BlockState state) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.state = state;
        }
    }

    public final UUID playerId;
    public Mode mode = Mode.MOVE;

    /** 选区起点 (绝对坐标), 玩家右键选点时记录. */
    public BlockPos origin = null;
    /** 选区尺寸 (绝对, 包含两端, 所以 size = max - min + 1). */
    public int sizeX = 0, sizeY = 0, sizeZ = 0;

    /** 扫描缓存的方块列表 (服务端的 ground truth, 客户端通过 payload 收到副本). */
    public final List<CachedBlock> cachedBlocks = new ArrayList<>();

    /** 玩家当前预览位置 (绝对坐标 = 新建筑 origin). 跟随玩家视线更新. */
    public BlockPos previewPos = null;
    /** 上次更新的世界 tick, 用于节流 (避免每 tick 改 previewPos 触发大量重新计算). */
    public long lastPreviewUpdateTick = 0;

    public OperationWandState(UUID playerId) {
        this.playerId = playerId;
    }

    public boolean isReady() {
        return origin != null && sizeX > 0 && sizeY > 0 && sizeZ > 0 && !cachedBlocks.isEmpty();
    }

    /** 整体尺寸 (按 X/Y/Z 三轴). 用于 HUD 提示 "32x10x8 (2560 方块)". */
    public int blockCount() {
        return cachedBlocks.size();
    }

    public String sizeString() {
        return sizeX + "x" + sizeY + "x" + sizeZ + " (" + blockCount() + " 方块)";
    }

    /** 重置全部状态 (CTRL 取消 或 建造完成后调用). */
    public void clear() {
        mode = Mode.MOVE;
        origin = null;
        sizeX = sizeY = sizeZ = 0;
        cachedBlocks.clear();
        previewPos = null;
        lastPreviewUpdateTick = 0;
    }
}
