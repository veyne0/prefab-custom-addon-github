package com.prefab.addon.items;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.network.OperationWandScanPayload;
import com.prefab.addon.network.NetworkHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * 操作手杖 - 选区确认后的执行入口.
 *
 * <p>由 {@link com.prefab.addon.work.RegionSelector#confirm} 在 mode ==
 * {@code OPERATION_WAND} 时调用. 客户端只发两个角点到服务端, 由服务端扫描
 * 区域并存入 {@link OperationWandState}. 客户端不直接扫描是因为 ClientLevel
 * 的 chunk 缓存可能尚未同步 (参考 MiniBuildingSelector 的同样考虑).</p>
 */
public final class OperationWandSelectionHandler {

    /** 单边最长允许 32 格 (最大 32x32x32 = 32768, 避免服务端 NBT 爆炸). */
    public static final int MAX_SIZE = 32;
    /** 总方块数上限 16384 (避开单 tag 2MB 限制 + 性能). */
    public static final int MAX_BLOCKS = 16384;

    private OperationWandSelectionHandler() {}

    /**
     * 客户端: ALT 确认后, 发角点到服务端触发扫描.
     */
    public static void onConfirm(Player player,
                                 com.prefab.addon.work.RegionSelector.SelectionState st) {
        // 服务端不直接调用 confirm (走 RegionSelector), 这里客户端发包给服务端
        if (!player.level().isClientSide()) return;
        BlockPos c1 = st.pos1;
        BlockPos c2 = st.pos2;
        if (c1 == null || c2 == null) return;

        int sx = Math.abs(c2.getX() - c1.getX()) + 1;
        int sy = Math.abs(c2.getY() - c1.getY()) + 1;
        int sz = Math.abs(c2.getZ() - c1.getZ()) + 1;
        if (sx > MAX_SIZE || sy > MAX_SIZE || sz > MAX_SIZE) {
            player.sendSystemMessage(Component.literal(
                "§c[操作手杖] §7选区超过 " + MAX_SIZE + " 格 (实际 " + sx + "x" + sy + "x" + sz + "), 捕获取消"
            ));
            return;
        }
        long estBlocks = (long) sx * sy * sz;
        if (estBlocks > MAX_BLOCKS) {
            player.sendSystemMessage(Component.literal(
                "§c[操作手杖] §7总方块超过 " + MAX_BLOCKS + " (实际 " + estBlocks + "), 捕获取消"
            ));
            return;
        }

        PrefabCustomAddon.LOGGER.info(
            "[OP-WAND] Client: sending corners ({}, {}, {}) - ({}, {}, {}), size {}x{}x{}",
            c1.getX(), c1.getY(), c1.getZ(), c2.getX(), c2.getY(), c2.getZ(), sx, sy, sz);

        NetworkHandler.sendToServer(new OperationWandScanPayload(c1.immutable(), c2.immutable()));
    }
}
