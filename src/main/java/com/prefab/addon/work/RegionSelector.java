package com.prefab.addon.work;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.items.mini.MiniBuildingSelector;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * 跨 GUI 的"区域选点"状态.
 *
 * 工作流:
 *   1) 玩家在创建建筑界面点"选择建筑" → 关闭 GUI, 进入选择模式
 *   2) 左键 = 选角点 1, 右键 = 选角点 2 (左键右键各一次)
 *   3) 两个角点都选好后, 按 ALT 确认, 由 mode 决定后续动作
 *   4) BLUEPRINT_EXPORT 模式: 导出 NBT, 重新打开创建建筑界面
 *      MINI_BUILDING_CAPTURE 模式: 扫描区域生成迷你建筑方块
 *
 * 用法:
 *   - RegionSelector.start(player, callback) - 启动 BLUEPRINT 模式 (旧接口)
 *   - RegionSelector.start(player, mode, callback) - 启动指定模式
 *   - RegionSelector.startMiniBuilding(player) - 启动 MINI_BUILDING 模式
 *   - RegionSelector.onLeftClick(pos) / onRightClick(pos) - 处理点击
 *   - RegionSelector.isActive(player) - 玩家当前是否在选择模式
 *   - RegionSelector.cancel() - 取消选择
 */
public class RegionSelector {
    private static final java.util.Map<UUID, SelectionState> ACTIVE = new java.util.HashMap<>();

    /**
     * 选区用途: 决定 confirm() 后做什么.
     *
     *   - BLUEPRINT_EXPORT: 导出 NBT (创建建筑/编辑拓展包时使用)
     *   - MINI_BUILDING_CAPTURE: 捕获区域为迷你建筑方块 (迷你建筑转换器使用)
     *   - OPERATION_WAND: 操作手杖选区 (移动/复制模式). confirm 后扫描方块进
     *       {@link com.prefab.addon.items.OperationWandState.cachedBlocks}
     */
    public enum Mode {
        BLUEPRINT_EXPORT,
        MINI_BUILDING_CAPTURE,
        OPERATION_WAND
    }

    public static class SelectionState {
        public final UUID playerId;
        public BlockPos pos1 = null;  // 左键
        public BlockPos pos2 = null;  // 右键
        public OnCompleted callback;
        /** 选区用途. 决定 confirm() 后 dispatch 到哪. */
        public Mode mode = Mode.BLUEPRINT_EXPORT;
        public long startTick;
        /** 按住 SHIFT 时为 true, 临时解锁挖方块/用物品. */
        public boolean tempUnlocked = false;

        public SelectionState(UUID id, OnCompleted cb) {
            this.playerId = id;
            this.callback = cb;
            this.startTick = System.currentTimeMillis();
        }

        public SelectionState(UUID id, Mode mode, OnCompleted cb) {
            this(id, cb);
            this.mode = mode;
        }

        public boolean bothSelected() {
            return pos1 != null && pos2 != null;
        }

        /**
         * 已选角点数: 0=没选, 1=只选了 pos1, 2=两个都选了.
         * 供 HUD 显示用, 玩家能一眼看到当前选区进度.
         */
        public int getCornerCount() {
            int n = 0;
            if (pos1 != null) n++;
            if (pos2 != null) n++;
            return n;
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

    /**
     * 旧接口, 默认走 BLUEPRINT_EXPORT 模式. 保留以兼容现有 GUI 流程
     * (GuiCreateBuildingInfo / GuiExtensionPackEditor).
     */
    public static void start(Player player, OnCompleted callback) {
        start(player, Mode.BLUEPRINT_EXPORT, callback);
    }

    public static void start(Player player, Mode mode, OnCompleted callback) {
        ACTIVE.put(player.getUUID(), new SelectionState(player.getUUID(), mode, callback));
        // 操作提示由 RegionSelectorHud 顶部 HUD 显示, 不再发聊天栏消息
        PrefabCustomAddon.LOGGER.info("[REGION-SELECT] Started selection for {} (mode={})",
            player.getName().getString(), mode);
    }

    /**
     * 启动迷你建筑捕获模式. 由 RegionSelectorEventHandler 在玩家手持
     * 迷你建筑转换器时自动调用.
     */
    public static void startMiniBuilding(Player player) {
        SelectionState st = ACTIVE.get(player.getUUID());
        if (st != null && st.mode == Mode.MINI_BUILDING_CAPTURE) {
            return;  // 已经在迷你模式, 啥也不做
        }
        // 占用同一个 slot, 覆盖现有选区 (理论上不会发生, 互斥)
        ACTIVE.put(player.getUUID(), new SelectionState(player.getUUID(),
            Mode.MINI_BUILDING_CAPTURE, null));
        // 操作提示由 RegionSelectorHud 顶部 HUD 显示, 不再发聊天栏消息
        PrefabCustomAddon.LOGGER.info("[REGION-SELECT] Started mini building selection for {}",
            player.getName().getString());
    }

    /**
     * 启动操作手杖选区模式. 由 OperationWandClientHandler 在玩家手持
     * 手杖右键方块时调用. confirm 后服务端扫描方块进 OperationWandState.
     */
    public static void startWand(Player player) {
        SelectionState st = ACTIVE.get(player.getUUID());
        if (st != null && st.mode == Mode.OPERATION_WAND) {
            return;  // 已经在操作手杖模式, 啥也不做
        }
        ACTIVE.put(player.getUUID(), new SelectionState(player.getUUID(),
            Mode.OPERATION_WAND, null));
        // 操作提示由 RegionSelectorHud 顶部 HUD 显示, 不再发聊天栏消息
        PrefabCustomAddon.LOGGER.info("[REGION-SELECT] Started operation wand selection for {}",
            player.getName().getString());
    }

    public static boolean isActive(Player player) {
        return ACTIVE.containsKey(player.getUUID());
    }

    public static SelectionState getState(Player player) {
        return ACTIVE.get(player.getUUID());
    }

    /**
     * 玩家当前是否处于"临时解锁"状态 (按住 SHIFT).
     * 未在选区模式时也返回 false.
     */
    public static boolean isTempUnlocked(Player player) {
        SelectionState st = ACTIVE.get(player.getUUID());
        return st != null && st.tempUnlocked;
    }

    public static void cancel(Player player) {
        SelectionState st = ACTIVE.remove(player.getUUID());
        if (st != null && st.callback != null) {
            st.callback.onCancelled();
            player.sendSystemMessage(Component.literal(PrefabCustomAddon.tr("sel.cancelled")));
        }
    }

    /** 设置角点 1 (左键). 坐标反馈由 RegionSelectorHud 显示. */
    public static void onLeftClick(Player player, BlockPos pos) {
        SelectionState st = ACTIVE.get(player.getUUID());
        if (st == null) return;
        st.pos1 = pos.immutable();
    }

    /** 设置角点 2 (右键). 坐标反馈由 RegionSelectorHud 显示. */
    public static void onRightClick(Player player, BlockPos pos) {
        SelectionState st = ACTIVE.get(player.getUUID());
        if (st == null) return;
        st.pos2 = pos.immutable();
    }

    /**
     * ALT 键确认: 两个角点都选好后由 RegionSelectorEventHandler 触发, 按 mode 分派.
     */
    public static void confirm(Player player) {
        SelectionState st = ACTIVE.get(player.getUUID());
        if (st == null) return;
        if (!st.bothSelected()) {
            // action bar 提示 (不进聊天栏, 避免按住 ALT 时刷屏)
            player.displayClientMessage(Component.literal(PrefabCustomAddon.tr("sel.need_both")), true);
            return;
        }

        // 先从 ACTIVE 移除, 避免递归 / 重复触发
        ACTIVE.remove(player.getUUID());

        switch (st.mode) {
            case BLUEPRINT_EXPORT:
                doExport(player, st);
                break;
            case MINI_BUILDING_CAPTURE:
                MiniBuildingSelector.onConfirm(player, st);
                break;
            case OPERATION_WAND:
                com.prefab.addon.items.OperationWandSelectionHandler.onConfirm(player, st);
                break;
        }
    }

    private static void doExport(Player player, SelectionState st) {
        // 注意: confirm() 已经把 st 从 ACTIVE 移除, 这里直接用 st
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
}
