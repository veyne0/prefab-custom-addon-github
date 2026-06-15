package com.prefab.addon.client;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.structures.base.Structure;
import com.prefab.structures.config.StructureConfiguration;
import com.prefab.structures.render.StructureRenderHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;

/**
 * 自定义建筑 Preview 全局键盘控制。
 *
 * 玩家点 "预览" 后 GUI 会关闭（Prefab 默认行为），但玩家还需要在世界中用键盘
 * 微调预览位置。本类用 ClientTickEvent 全局监听键盘：
 *
 * - ←/→  : 相对**玩家朝向**左右平移（Shift = 5 格大步）
 * - ↑/↓  : 相对**玩家朝向**前后平移（Shift = 5 格大步）
 * - +/-  : 整体垂直上下 1 格（Shift = 5 格大步）
 * - CTRL : 整体逆时针旋转 90°（按一次 90°，按住时按节流间隔持续旋转）
 * - ALT  : 在当前 Preview 位置直接建造
 *
 * 节流：所有移动 / 旋转受 minIntervalMs 控制（默认 150ms），
 *       防止按 1 帧 1 格过快（实测 60fps 一次按下会飞 60 格），也避免
 *       setStructure→rebuildPreviewMeshes 在大建筑上卡顿。
 *
 * 渲染提示在 StructurePreviewHud（屏幕顶部居中显示）。
 */
@EventBusSubscriber(modid = PrefabCustomAddon.MOD_ID, value = Dist.CLIENT)
public class StructurePreviewKeyHandler {

    // 防止按住 ALT 时一帧内建造多次
    private static int lastAction = -1;

    // 节流：距离上次 move/rotate 至少经过这么久（毫秒）才允许下一次
    private static final long MOVE_INTERVAL_MS = 150L;
    private static long lastMoveTimeMs = 0L;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // 当前没有打开任何 GUI（点完 Preview 后 GUI 已关闭）
        if (mc.screen != null) {
            return;
        }

        // 没有正在预览的结构
        StructureConfiguration cfg = StructureRenderHandler.currentConfiguration;
        Structure currentStructure = StructureRenderHandler.currentStructure;
        if (cfg == null || cfg.pos == null || currentStructure == null) {
            return;
        }

        long window = mc.getWindow().getWindow();
        boolean shiftDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        boolean ctrlDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
        int step = shiftDown ? 5 : 1;

        // === 方向键：相对玩家视角移动 ===
        // 玩家水平朝向（不含上下）
        Direction playerFacing = mc.player.getNearestViewDirection();
        if (playerFacing == Direction.UP || playerFacing == Direction.DOWN) {
            playerFacing = Direction.NORTH;
        }

        Direction forward = playerFacing;
        Direction back = playerFacing.getOpposite();
        Direction left = playerFacing.getCounterClockWise();
        Direction right = playerFacing.getClockWise();

        int dx = 0, dz = 0, dy = 0;
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT) == GLFW.GLFW_PRESS) {
            dx += left.getStepX();
            dz += left.getStepZ();
        }
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT) == GLFW.GLFW_PRESS) {
            dx += right.getStepX();
            dz += right.getStepZ();
        }
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_UP) == GLFW.GLFW_PRESS) {
            dx += forward.getStepX();
            dz += forward.getStepZ();
        }
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_DOWN) == GLFW.GLFW_PRESS) {
            dx += back.getStepX();
            dz += back.getStepZ();
        }
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_EQUAL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_KP_ADD) == GLFW.GLFW_PRESS) {
            dy += 1;
        }
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_MINUS) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_KP_SUBTRACT) == GLFW.GLFW_PRESS) {
            dy -= 1;
        }

        // 节流：如果上次 move/rotate 还没超过 MOVE_INTERVAL_MS，就不响应
        long now = System.currentTimeMillis();
        boolean canMove = (now - lastMoveTimeMs) >= MOVE_INTERVAL_MS;

        if (canMove && (dx != 0 || dz != 0 || dy != 0)) {
            BlockPos oldPos = cfg.pos;
            BlockPos newPos = oldPos.offset(dx * step, dy * step, dz * step);
            cfg.pos = newPos;
            // 关键：Prefab 把结构烘焙到 previewChunks 网格，必须 setStructure 触发 needsRebuild=true
            StructureRenderHandler.setStructure(currentStructure, cfg);
            lastMoveTimeMs = now;
            PrefabCustomAddon.LOGGER.info("[PREVIEW-MOVE] player={} dx={} dz={} dy={} step={}  {} -> {}",
                playerFacing, dx, dz, dy, step, oldPos, newPos);
            return;  // 一帧内 move 和 rotate 不能同时发生（避免冲突）
        }

        // === CTRL 键 → 旋转 90°（逆时针） ===
        if (canMove && ctrlDown) {
            Direction newFacing = rotateCounterClockwise(cfg.houseFacing);
            Direction oldFacing = cfg.houseFacing;
            cfg.houseFacing = newFacing;
            StructureRenderHandler.setStructure(currentStructure, cfg);
            lastMoveTimeMs = now;
            PrefabCustomAddon.LOGGER.info("[PREVIEW-ROTATE] houseFacing {} -> {}", oldFacing, newFacing);
        }

        // === ALT 键 → 直接建造（节流：1 秒最多 1 次） ===
        boolean altDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
        if (altDown) {
            if (lastAction != GLFW.GLFW_KEY_LEFT_ALT && lastAction != GLFW.GLFW_KEY_RIGHT_ALT) {
                triggerBuildAtPreview(cfg, currentStructure);
            }
        }
        if (!altDown) {
            lastAction = -1;
        }
    }

    /**
     * 逆时针旋转 90°：SOUTH → EAST → NORTH → WEST → SOUTH
     * 也就是逆时针（看向俯视图）。
     */
    private static Direction rotateCounterClockwise(Direction current) {
        return switch (current) {
            case SOUTH -> Direction.EAST;
            case EAST -> Direction.NORTH;
            case NORTH -> Direction.WEST;
            case WEST -> Direction.SOUTH;
            default -> current;
        };
    }

    /**
     * ALT 按下时直接建造。
     */
    private static void triggerBuildAtPreview(StructureConfiguration cfg, Structure structure) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        lastAction = GLFW.GLFW_KEY_LEFT_ALT;

        net.minecraft.world.item.ItemStack blueprint = net.minecraft.world.item.ItemStack.EMPTY;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack s = player.getInventory().getItem(i);
            if (s.getItem() instanceof com.prefab.addon.items.CustomBlueprintItem) {
                blueprint = s;
                break;
            }
        }
        if (blueprint.isEmpty()) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("No Custom Blueprint in inventory!")
                    .withStyle(net.minecraft.ChatFormatting.RED));
            return;
        }
        String packName = com.prefab.addon.items.CustomBlueprintItem.getBoundPackName(blueprint);
        String constructionId = com.prefab.addon.items.CustomBlueprintItem.getBoundConstructionId(blueprint);
        if (packName.isEmpty() || constructionId.isEmpty()) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Blueprint is not bound!")
                    .withStyle(net.minecraft.ChatFormatting.RED));
            return;
        }

        PrefabCustomAddon.LOGGER.info("[PREVIEW-BUILD] ALT pressed, sending BuildCustomStructurePayload for {}/{} at {}",
            packName, constructionId, cfg.pos);

        com.prefab.addon.network.NetworkHandler.sendToServer(
            new com.prefab.addon.network.BuildCustomStructurePayload(
                cfg.pos, packName, constructionId));
        StructureRenderHandler.setStructure(null, null);
    }
}
