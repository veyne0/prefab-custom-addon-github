package com.prefab.addon.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.items.OperationWandManager;
import com.prefab.addon.items.OperationWandState;
import com.prefab.addon.network.NetworkHandler;
import com.prefab.addon.network.OperationWandBuildPayload;
import com.prefab.addon.work.RegionSelector;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.lwjgl.glfw.GLFW;

/**
 * 操作手杖 - 客户端事件处理.
 *
 * <p>职责:
 * <ol>
 *   <li>鼠标右键 (手持手杖, 未 ready) → 启动选区</li>
 *   <li>shift + 鼠标滚轮 → 切模式 (MOVE ↔ COPY, 仅创造模式允许 COPY)</li>
 *   <li>鼠标右键 (已 ready) → 触发 build (右键方块或右键空气都触发, 不再要求必须空地)</li>
 *   <li>CTRL 键 → 取消 / 清除状态</li>
 *   <li>接收 {@code OperationWandScanResultPayload} → 写本地 cachedBlocks (用于预览)</li>
 *   <li>玩家视线位置更新 → 同步 previewPos</li>
 * </ol>
 */
@EventBusSubscriber(modid = PrefabCustomAddon.MOD_ID, value = Dist.CLIENT)
public class OperationWandClientHandler {

    /** 上次模式切换 tick, 防止连续滚轮事件触发多次. */
    private static long lastModeSwitchTick = 0;

    // ============== 1) shift + 鼠标滚轮 → 切模式 ==============

    /**
     * 监听鼠标滚轮事件, 检测 shift + 滚轮 (上/下) 切模式.
     *
     * <p>为什么用 {@link InputEvent.MouseScrollingEvent} 而不是每 tick 轮询:
     *   鼠标滚轮的 deltaY 是一次性事件 (玩家实际滚一格才触发一次),
     *   比"按住 shift+方向键"更符合 WorldEdit 的操作习惯.</p>
     */
    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.options.hideGui) return;
        // 必须手持手杖才响应
        ItemStack mainHand = mc.player.getMainHandItem();
        if (mainHand.getItem() != PrefabCustomAddon.OPERATION_WAND.get()) return;
        // shift 必须按下
        long window = mc.getWindow().getWindow();
        boolean shiftDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                         || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        if (!shiftDown) return;
        // 屏幕打开时不切 (避免 GUI 误触发)
        if (mc.screen != null) return;

        double deltaY = event.getScrollDeltaY();
        if (deltaY == 0) return;
        // 节流: 防止单次滚动事件重复触发 (deltaY 可能 > 1.0)
        long nowTick = mc.player.level().getGameTime();
        if (nowTick - lastModeSwitchTick < 4) {
            event.setCanceled(true);
            return;
        }
        // deltaY > 0: 上滚 → 切到下一个模式
        // deltaY < 0: 下滚 → 切到上一个模式
        cycleMode(mc.player, deltaY > 0 ? +1 : -1);
        lastModeSwitchTick = nowTick;
        event.setCanceled(true);  // 吃掉事件, 防止滚到物品栏
    }

    // ============== 2) 玩家右键方块/空气 → 启动选区 或 触发 build ==============

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() == false) return;
        Player player = event.getEntity();
        ItemStack mainHand = player.getMainHandItem();
        if (mainHand.getItem() != PrefabCustomAddon.OPERATION_WAND.get()) return;

        // 已 ready 状态: 玩家右键方块 = 触发 build
        OperationWandState state = OperationWandManager.get(player);
        if (state.isReady()) {
            BlockPos target = event.getPos().offset(event.getFace().getNormal());
            sendBuild(player, target);
            event.setCanceled(true);
            return;
        }

        // 选区模式进行中: 交给 RegionSelectorEventHandler 处理
        if (RegionSelector.isActive(player)
            && RegionSelector.getState(player).mode == RegionSelector.Mode.OPERATION_WAND) {
            return;
        }

        // 否则: 启动选区
        RegionSelector.startWand(player);
        event.setCanceled(true);
    }

    /**
     * 右键空气 (没击中方块) - 也触发 build (用户要求: 任何右键都触发, 不限空地).
     * 如果不拦截这个事件, 服务端 BuildPayload 永远收不到, 因为 Minecraft 的右键空气
     * 不会自动发包.
     */
    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide() == false) return;
        Player player = event.getEntity();
        ItemStack mainHand = player.getMainHandItem();
        if (mainHand.getItem() != PrefabCustomAddon.OPERATION_WAND.get()) return;

        // 已 ready 状态: 右键空气 = 触发 build
        OperationWandState state = OperationWandManager.get(player);
        if (state.isReady()) {
            // 用视线命中的方块位置 (按方向偏移 1 格, 跟右键方块时的逻辑一致)
            HitResult hit = Minecraft.getInstance().hitResult;
            BlockPos target;
            if (hit instanceof BlockHitResult bhr) {
                target = bhr.getBlockPos().offset(bhr.getDirection().getNormal());
            } else {
                // 完全空击 (玩家对着天空): 用玩家脚下上方 1 格作为目标
                target = player.blockPosition().above();
            }
            sendBuild(player, target);
            event.setCanceled(true);
        }
    }

    private static void sendBuild(Player player, BlockPos target) {
        OperationWandState state = OperationWandManager.get(player);
        NetworkHandler.sendToServer(new OperationWandBuildPayload(
            target, state.mode.ordinal()));
        player.sendSystemMessage(Component.literal(
            "§a[操作手杖] §7正在 §f" + (state.mode == OperationWandState.Mode.MOVE ? "移动" : "复制")
                + "§7 → (" + target.getX() + "," + target.getY() + "," + target.getZ() + ")"
        ));
        // 客户端先清空状态, 防止快速连点重复发包. 服务端 build 完成后也会再清一次.
        OperationWandManager.clear(player);
    }

    // ============== 3) 玩家视线位置更新 / CTRL 取消 ==============

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        Player player = mc.player;

        long window = mc.getWindow().getWindow();

        // === CTRL 取消 ===
        boolean ctrlDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                        || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
        if (ctrlDown) {
            OperationWandState state = OperationWandManager.get(player);
            if (state.isReady()) {
                OperationWandManager.clear(player);
                player.sendSystemMessage(Component.literal("§c[操作手杖] §7已取消 (CTRL)"));
            }
        }

        // === 玩家视线位置更新 previewPos (每 2 tick 一次) ===
        OperationWandState st = OperationWandManager.get(player);
        long nowTick = player.level().getGameTime();
        if (st.isReady() && nowTick - st.lastPreviewUpdateTick >= 2) {
            HitResult hit = mc.hitResult;
            if (hit instanceof BlockHitResult bhr) {
                BlockPos target = bhr.getBlockPos().offset(bhr.getDirection().getNormal());
                if (st.previewPos == null || !st.previewPos.equals(target)) {
                    st.previewPos = target.immutable();
                    st.lastPreviewUpdateTick = nowTick;
                }
            } else {
                // 视线对空: 用玩家脚下作为预览位置 (不偏移, 直接对到脚下)
                BlockPos target = player.blockPosition();
                if (st.previewPos == null || !st.previewPos.equals(target)) {
                    st.previewPos = target.immutable();
                    st.lastPreviewUpdateTick = nowTick;
                }
            }
        }
    }

    private static void cycleMode(Player player, int delta) {
        OperationWandState state = OperationWandManager.get(player);
        OperationWandState.Mode[] modes = OperationWandState.Mode.values();
        int newIdx = (state.mode.ordinal() + delta + modes.length) % modes.length;
        OperationWandState.Mode newMode = modes[newIdx];

        // COPY 模式仅创造模式可用
        if (newMode == OperationWandState.Mode.COPY && !player.isCreative()) {
            player.sendSystemMessage(Component.literal(
                "§c[操作手杖] §7复制模式仅创造模式可用, 保持 §f" + modeName(state.mode) + " §7模式"));
            return;
        }

        state.mode = newMode;
        player.sendSystemMessage(Component.literal(
            "§a[操作手杖] §7模式 → §f" + modeName(newMode) + " §7(shift+鼠标滚轮 切换)"));
    }

    private static String modeName(OperationWandState.Mode mode) {
        return mode == OperationWandState.Mode.MOVE ? "移动" : "复制";
    }

    // ============== 4) 接收服务端扫描结果 → 写本地 cachedBlocks ==============

    /**
     * 由 {@link com.prefab.addon.network.OperationWandScanResultPayload} 调用.
     */
    public static void onScanResult(BlockPos origin, int sizeX, int sizeY, int sizeZ,
                                     int blockCount, int airSkipped, ListTag list) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        OperationWandState state = OperationWandManager.get(player);
        state.clear();
        state.origin = origin;
        state.sizeX = sizeX;
        state.sizeY = sizeY;
        state.sizeZ = sizeZ;
        state.previewPos = origin;  // 初始预览位置
        for (int i = 0; i < list.size(); i++) {
            var entry = list.getCompound(i);
            int lx = entry.getInt("x");
            int ly = entry.getInt("y");
            int lz = entry.getInt("z");
            BlockState blockState = BlockState.CODEC.parse(
                net.minecraft.nbt.NbtOps.INSTANCE, entry.get("state")).getOrThrow();
            state.cachedBlocks.add(new OperationWandState.CachedBlock(lx, ly, lz, blockState));
        }
        PrefabCustomAddon.LOGGER.info("[OP-WAND] Client state updated: origin={}, size={}x{}x{}, blocks={} ({} air skipped)",
            origin, sizeX, sizeY, sizeZ, state.cachedBlocks.size(), airSkipped);
    }
}
