package com.prefab.addon.client;

import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import org.lwjgl.glfw.GLFW;

/**
 * 自定义推土机 — 3D 预览期间键盘控制.
 *
 * <ul>
 *   <li>↑/↓ : 沿玩家朝向前后移动 (Shift = 5 格大步)</li>
 *   <li>←/→ : 沿玩家朝向左右移动 (Shift = 5 格大步)</li>
 *   <li>+/- : 整体上下移动</li>
 *   <li>§6§lCTRL + ←/→§r (或 §6§lCTRL + Q/E§r) : 旋转 facing (90°/次)</li>
 *   <li>§6§lALT§r : 确认, 执行清除</li>
 *   <li>右键 : 取消预览</li>
 * </ul>
 */
@EventBusSubscriber(modid = PrefabCustomAddon.MOD_ID, value = Dist.CLIENT)
public class CustomBulldozerKeyHandler {

    private static final long MOVE_INTERVAL_MS = 150L;
    private static final long ROTATE_INTERVAL_MS = 180L;  // 旋转比移动慢一点, 防止快速连续转
    private static long lastMoveTimeMs = 0L;
    private static boolean lastRightDown = false;

    /** 旋转后 action bar 提示当前朝向. */
    private static void showFacing(Minecraft mc) {
        var s = CustomBulldozerPreviewRenderer.getState();
        if (s == null) return;
        mc.player.displayClientMessage(Component.literal(
            "§e朝向: §f" + facingCN(s.facing) + " §7(CTRL+←/→ 旋转)"), true);
    }

    private static String facingCN(Direction d) {
        return switch (d) {
            case NORTH -> "北 (-Z)";
            case SOUTH -> "南 (+Z)";
            case WEST  -> "西 (-X)";
            case EAST  -> "东 (+X)";
            default    -> d.getName();
        };
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!CustomBulldozerPreviewRenderer.isActive()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // === 右键取消 (早于 screen 检查) ===
        long window = mc.getWindow().getWindow();
        boolean rightDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        if (rightDown && !lastRightDown) {
            mc.player.sendSystemMessage(Component.literal("§c✗ 已取消自定义推土机预览"));
            CustomBulldozerPreviewRenderer.cancel();
            lastRightDown = true;
            return;
        }
        lastRightDown = rightDown;

        // === ALT 确认清除 ===
        boolean altDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                       || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
        if (altDown) {
            mc.player.sendSystemMessage(Component.literal("§a✓ 确认清除"));
            CustomBulldozerPreviewRenderer.execute();
            return;
        }

        // === CTRL + 方向键 左右 / CTRL + Q / E 旋转 facing (90°/次) ===
        boolean ctrlDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                        || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
        boolean qDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_Q) == GLFW.GLFW_PRESS;
        boolean eDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_E) == GLFW.GLFW_PRESS;
        if (ctrlDown) {
            // 节流: 旋转比移动慢一点
            long nowRot = System.currentTimeMillis();
            if (nowRot - lastMoveTimeMs >= ROTATE_INTERVAL_MS) {
                boolean left  = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT)  == GLFW.GLFW_PRESS;
                boolean right = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT) == GLFW.GLFW_PRESS;
                if (left || qDown) {
                    CustomBulldozerPreviewRenderer.rotateY();
                    lastMoveTimeMs = nowRot;
                    showFacing(mc);
                    return;
                }
                if (right || eDown) {
                    CustomBulldozerPreviewRenderer.rotateYReverse();
                    lastMoveTimeMs = nowRot;
                    showFacing(mc);
                    return;
                }
            }
        }

        // === 移动 (节流 150ms) ===
        long now = System.currentTimeMillis();
        if (now - lastMoveTimeMs < MOVE_INTERVAL_MS) return;
        boolean moved = false;

        boolean up    = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_UP)    == GLFW.GLFW_PRESS;
        boolean down  = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_DOWN)  == GLFW.GLFW_PRESS;
        boolean left  = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT)  == GLFW.GLFW_PRESS;
        boolean right = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT) == GLFW.GLFW_PRESS;
        boolean plus  = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_EQUAL)        == GLFW.GLFW_PRESS   // = / +
                       || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_KP_ADD)     == GLFW.GLFW_PRESS;
        boolean minus = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_MINUS)        == GLFW.GLFW_PRESS
                       || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_KP_SUBTRACT) == GLFW.GLFW_PRESS;
        boolean shift = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                       || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        int step = shift ? 5 : 1;

        Direction playerFacing = mc.player.getDirection();
        if (up)    { CustomBulldozerPreviewRenderer.move(playerFacing); moved = true; }
        if (down)  { CustomBulldozerPreviewRenderer.move(playerFacing.getOpposite()); moved = true; }
        if (left)  { CustomBulldozerPreviewRenderer.move(playerFacing.getCounterClockWise()); moved = true; }
        if (right) { CustomBulldozerPreviewRenderer.move(playerFacing.getClockWise()); moved = true; }
        if (plus)  { CustomBulldozerPreviewRenderer.moveVertical(step); moved = true; }
        if (minus) { CustomBulldozerPreviewRenderer.moveVertical(-step); moved = true; }

        if (moved) {
            lastMoveTimeMs = now;
            // 移动时给个简短提示
            var s = CustomBulldozerPreviewRenderer.getState();
            if (s != null) {
                mc.player.displayClientMessage(Component.literal(
                    "§e起点: §f" + s.pos.toShortString() + " §7(Shift = 5格大步)"), true);
            }
        }
    }

    /** 屏幕顶部操作提示. */
    @SubscribeEvent
    public static void onRenderGui(RenderGuiLayerEvent.Post event) {
        if (!CustomBulldozerPreviewRenderer.isActive()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;

        var pose = event.getGuiGraphics().pose();
        pose.pushPose();
        pose.scale(1.0f, 1.0f, 1.0f);
        int cx = mc.getWindow().getGuiScaledWidth() / 2;

        String[] lines = {
            "§6§l自定义推土机 - 预览模式",
            "§7↑↓←→ 移动 §7(Shift=大步) | §7+/- 上下 | §6CTRL+←/→§7 旋转",
            "§6§l[ALT]§r§e 确认清除  §7|  §c右键 取消"
        };
        int y = 8;
        for (String l : lines) {
            int w = mc.font.width(l);
            event.getGuiGraphics().fill(cx - w/2 - 4, y - 2, cx + w/2 + 4, y + 10, 0xC0101010);
            event.getGuiGraphics().drawString(mc.font, l, cx - w/2, y, 0xFFFFFF, false);
            y += 12;
        }
        pose.popPose();
    }
}
