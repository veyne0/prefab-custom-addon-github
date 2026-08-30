package com.prefab.addon.items.mini;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.items.MiniBuildingConverterItem;
import com.prefab.addon.network.MiniBuildingCapturePayload;
import com.prefab.addon.network.NetworkHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * 迷你建筑转换器 - 选区确认后的执行入口.
 *
 * <p>由 {@link com.prefab.addon.work.RegionSelector#confirm} 在 mode ==
 * {@code MINI_BUILDING_CAPTURE} 时调用. 客户端只发两个角点到服务端, 由服务端扫描
 * 区域并生成迷你方块 + 扣耐久. 客户端不直接 capture 是因为 ClientLevel 的 chunk
 * 缓存可能尚未同步, 之前导致捕获 0 方块.</p>
 *
 * <p>AABB 约定: corner1 / corner2 都是包含的方块坐标.</p>
 */
public final class MiniBuildingSelector {

    /** 单边最长允许 128 格 (体积上限 200000 块, 详见 MiniBuildingCaptureHelper.MAX_BLOCKS). */
    public static final int MAX_SIZE = MiniBuildingConverterItem.MAX_SIZE;

    private MiniBuildingSelector() {}

    /**
     * 客户端: ALT 确认后的执行.
     *
     * <ol>
     *   <li>校验尺寸 (单边 ≤ 64)</li>
     *   <li>发两个角点到服务端, 由服务端 capture</li>
     * </ol>
     */
    public static void onConfirm(Player player, com.prefab.addon.work.RegionSelector.SelectionState st) {
        if (player.level().isClientSide() == false) {
            return;  // 只在客户端发包
        }
        BlockPos c1 = st.pos1;
        BlockPos c2 = st.pos2;
        if (c1 == null || c2 == null) return;

        int sx = Math.abs(c2.getX() - c1.getX()) + 1;
        int sy = Math.abs(c2.getY() - c1.getY()) + 1;
        int sz = Math.abs(c2.getZ() - c1.getZ()) + 1;
        if (sx > MAX_SIZE || sy > MAX_SIZE || sz > MAX_SIZE) {
            player.sendSystemMessage(Component.literal(
                "§c[迷你建筑转换器] §7选区超过 " + MAX_SIZE + " 格 (实际 " + sx + "x" + sy + "x" + sz + "), 转换取消"
            ));
            return;
        }

        PrefabCustomAddon.LOGGER.info(
            "[MINI_BUILDING] Client: sending corners ({}, {}, {}) - ({}, {}, {}), size {}x{}x{}",
            c1.getX(), c1.getY(), c1.getZ(), c2.getX(), c2.getY(), c2.getZ(), sx, sy, sz);

        NetworkHandler.sendToServer(new MiniBuildingCapturePayload(c1.immutable(), c2.immutable()));
    }
}
