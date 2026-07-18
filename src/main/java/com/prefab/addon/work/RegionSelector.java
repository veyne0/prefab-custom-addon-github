package com.prefab.addon.work;

import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

/**
 * 跨 GUI 的"区域选点"状态.
 *
 * 工作流:
 *   1) 玩家在创建建筑界面点"选择建筑" → 关闭 GUI, 进入选择模式
 *   2) 左键 = 选角点 1, 右键 = 选角点 2 (左键右键各一次)
 *   3) 两个角点都选好后, 客户端调用 StructureBlockExporter 导出 NBT
 *   4) NBT 写好后, 重新打开创建建筑界面, 字段自动填充
 *
 * 用法:
 *   - RegionSelector.start(player, callback) - 启动选择模式
 *   - RegionSelector.onLeftClick(pos) / onRightClick(pos) - 处理点击
 *   - RegionSelector.isActive(player) - 玩家当前是否在选择模式
 *   - RegionSelector.cancel() - 取消选择
 */
public class RegionSelector {
    private static final java.util.Map<UUID, SelectionState> ACTIVE = new java.util.HashMap<>();

    public static class SelectionState {
        public final UUID playerId;
        public BlockPos pos1 = null;  // 左键
        public BlockPos pos2 = null;  // 右键
        public OnCompleted callback;
        public long startTick;

        public SelectionState(UUID id, OnCompleted cb) {
            this.playerId = id;
            this.callback = cb;
            this.startTick = System.currentTimeMillis();
        }

        public boolean bothSelected() {
            return pos1 != null && pos2 != null;
        }

        public BlockPos getMin() {
            if (pos1 == null || pos2 == null) return null;
            return new BlockPos(
                Math.min(pos1.getX(), pos2.getX()),
                Math.min(pos1.getY(), pos2.getY()),
                Math.min(pos1.getZ(), pos2.getZ()));
        }

        public BlockPos getMax() {
            if (pos1 == null || pos2 == null) return null;
            return new BlockPos(
                Math.max(pos1.getX(), pos2.getX()),
                Math.max(pos1.getY(), pos2.getY()),
                Math.max(pos1.getZ(), pos2.getZ()));
        }
    }

    /** 完成时的回调: nbtData + nbtFile + sizeString + modIds */
    public interface OnCompleted {
        void onCompleted(byte[] nbtData, java.nio.file.Path nbtFile, String sizeString, java.util.List<String> modIds);
        void onCancelled();
    }

    public static void start(Player player, OnCompleted callback) {
        ACTIVE.put(player.getUUID(), new SelectionState(player.getUUID(), callback));
        if (player instanceof net.minecraft.client.player.LocalPlayer) {
            player.sendSystemMessage(Component.literal(
                "§a[选择模式] §7左键选角点 1, 右键选角点 2 §a(选完后按 §eALT §a确认, §eCTRL §a取消)"));
        }
        PrefabCustomAddon.LOGGER.info("[REGION-SELECT] Started selection for {}", player.getName().getString());
    }

    public static boolean isActive(Player player) {
        return ACTIVE.containsKey(player.getUUID());
    }

    public static SelectionState getState(Player player) {
        return ACTIVE.get(player.getUUID());
    }

    public static void cancel(Player player) {
        SelectionState st = ACTIVE.remove(player.getUUID());
        if (st != null && st.callback != null) {
            st.callback.onCancelled();
            player.sendSystemMessage(Component.literal("§c[选择模式] §7已取消"));
        }
    }

    public static void onLeftClick(Player player, BlockPos pos) {
        SelectionState st = ACTIVE.get(player.getUUID());
        if (st == null) return;
        st.pos1 = pos.immutable();
        if (st.bothSelected()) {
            player.sendSystemMessage(Component.literal(
                "§a[选择模式] §7角点 1: §f" + formatPos(pos)
                    + " §7| 角点 2: §f" + formatPos(st.pos2)
                    + " §a(按 ALT 确认导出)"));
        } else {
            player.sendSystemMessage(Component.literal(
                "§a[选择模式] §7角点 1: §f" + formatPos(pos) + " §7(右键选角点 2)"));
        }
    }

    public static void onRightClick(Player player, BlockPos pos) {
        SelectionState st = ACTIVE.get(player.getUUID());
        if (st == null) return;
        st.pos2 = pos.immutable();
        if (st.bothSelected()) {
            player.sendSystemMessage(Component.literal(
                "§a[选择模式] §7角点 2: §f" + formatPos(pos)
                    + " §7| 角点 1: §f" + formatPos(st.pos1)
                    + " §a(按 ALT 确认导出)"));
        } else {
            player.sendSystemMessage(Component.literal(
                "§a[选择模式] §7角点 2: §f" + formatPos(pos) + " §7(左键选角点 1)"));
        }
    }

    /**
     * ALT 键确认: 两个角点都选好后由 RegionSelectorEventHandler 触发, 开始导出 NBT.
     */
    public static void confirm(Player player) {
        SelectionState st = ACTIVE.get(player.getUUID());
        if (st == null) return;
        if (!st.bothSelected()) {
            player.sendSystemMessage(Component.literal(
                "§e[选择模式] §7请先选好两个角点 (左键 + 右键) 再按 ALT 确认"));
            return;
        }
        player.sendSystemMessage(Component.literal(
            "§a[选择模式] §7两个角点都选好了, 开始导出 NBT..."));
        doExport(player, st);
    }

    private static String formatPos(BlockPos p) {
        return "(" + p.getX() + ", " + p.getY() + ", " + p.getZ() + ")";
    }

    private static void doExport(Player player, SelectionState st) {
        ACTIVE.remove(player.getUUID());
        try {
            java.nio.file.Path nbtFile = StructureBlockExporter.exportToTempNbt(
                player.level(), st.getMin(), st.getMax());
            byte[] nbtData = java.nio.file.Files.readAllBytes(nbtFile);
            NbtStructureParser.NbtInfo info = NbtStructureParser.parse(nbtData);
            if (st.callback != null) {
                st.callback.onCompleted(nbtData, nbtFile, info.sizeString(), info.modIds);
            }
            player.sendSystemMessage(Component.literal(
                "§a[选择模式] §7NBT 导出成功: §f" + nbtFile.getFileName()
                    + " §7(" + info.sizeString() + ", " + nbtData.length + " bytes)"));
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[REGION-SELECT] Export failed", t);
            player.sendSystemMessage(Component.literal("§c[选择模式] §4导出失败: §f" + t.getMessage()));
            if (st.callback != null) st.callback.onCancelled();
        }
    }

    /** 每帧调用, 渲染选择框和提示 - 实际渲染由 RegionSelectorEventHandler.onRenderLevel 处理 */
    public static void onRender(net.minecraft.client.gui.GuiGraphics g, net.minecraft.client.Camera camera,
                                float partialTicks) {
        // 实际 3D 渲染: 见 RegionSelectorEventHandler.onRenderLevel
        // 这里只画 2D HUD 提示文字 (右上角)
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        SelectionState st = ACTIVE.get(mc.player.getUUID());
        if (st == null) return;

        // 简单画 2D 文字提示
        if (g == null) return;
        int screenW = mc.getWindow().getGuiScaledWidth();
        String tip;
        if (st.pos1 == null) {
            tip = "§a[选择模式] §7左键选角点 1";
        } else if (st.pos2 == null) {
            tip = "§a[选择模式] §7角点 1=" + formatPos(st.pos1) + " §7| 右键选角点 2";
        } else {
            tip = "§a[选择模式] §7角点 1=" + formatPos(st.pos1) + " §7| 角点 2=" + formatPos(st.pos2);
        }
        g.drawString(mc.font, tip, screenW - mc.font.width(tip) - 4, 4, 0xFFFFFF);
    }
}
