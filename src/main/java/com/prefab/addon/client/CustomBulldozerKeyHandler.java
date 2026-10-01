package com.prefab.addon.client;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.items.ItemCustomBulldozer;
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
 *   <li>移动/旋转/确认/取消 全部读 KeyMapping (默认 ↑↓←→ / CTRL / ALT / 右键), 玩家可在按键绑定改键</li>
 *   <li>§6§lCTRL + ←/→§r (或 §6§lCTRL + Q/E§r) : 旋转 facing (90°/次)</li>
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
            "§e朝向: §f" + facingCN(s.facing) + " §7(" + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_ROTATE, "CTRL") + "+" + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_LEFT, "←") + "/" + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_RIGHT, "→") + " 旋转)"), true);
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

        // === 取消键 (早于 screen 检查): KeyMapping (默认鼠标右键), 玩家可改键 ===
        long window = mc.getWindow().getWindow();
        boolean rightDown = PackBrowserKeyHandler.isKeyDown(PackBrowserKeyHandler.CANCEL_PREVIEW);
        if (rightDown && !lastRightDown) {
            mc.player.sendSystemMessage(Component.literal("§c✗ 已取消自定义推土机预览"));
            CustomBulldozerPreviewRenderer.cancel();
            lastRightDown = true;
            return;
        }
        lastRightDown = rightDown;

        // === 确认键 (清除/填充): 注册成 KeyMapping (默认左 ALT), 玩家可在按键绑定改键 ===
        boolean altDown = PackBrowserKeyHandler.isKeyDown(PackBrowserKeyHandler.BUILD_AT_PREVIEW);
        if (altDown) {
            var sAlt = CustomBulldozerPreviewRenderer.getState();
            boolean fillAlt = sAlt != null && ItemCustomBulldozer.getFillMode(sAlt.stack);
            mc.player.sendSystemMessage(Component.literal(fillAlt ? "§a✓ 确认填充" : "§a✓ 确认清除"));
            CustomBulldozerPreviewRenderer.execute();
            return;
        }

        // === 旋转键 (默认 CTRL) + 方向键 左右 / CTRL + Q / E 旋转 facing (90°/次), 全部读 KeyMapping ===
        boolean ctrlDown = PackBrowserKeyHandler.isKeyDown(PackBrowserKeyHandler.PREVIEW_ROTATE);
        boolean qDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_Q) == GLFW.GLFW_PRESS;
        boolean eDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_E) == GLFW.GLFW_PRESS;
        if (ctrlDown) {
            // 节流: 旋转比移动慢一点
            long nowRot = System.currentTimeMillis();
            if (nowRot - lastMoveTimeMs >= ROTATE_INTERVAL_MS) {
                boolean left  = PackBrowserKeyHandler.isKeyDown(PackBrowserKeyHandler.PREVIEW_LEFT);
                boolean right = PackBrowserKeyHandler.isKeyDown(PackBrowserKeyHandler.PREVIEW_RIGHT);
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

        boolean up    = PackBrowserKeyHandler.isKeyDown(PackBrowserKeyHandler.PREVIEW_FORWARD);
        boolean down  = PackBrowserKeyHandler.isKeyDown(PackBrowserKeyHandler.PREVIEW_BACK);
        boolean left  = PackBrowserKeyHandler.isKeyDown(PackBrowserKeyHandler.PREVIEW_LEFT);
        boolean right = PackBrowserKeyHandler.isKeyDown(PackBrowserKeyHandler.PREVIEW_RIGHT);
        boolean plus  = PackBrowserKeyHandler.isKeyDown(PackBrowserKeyHandler.PREVIEW_RAISE)
                       || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_EQUAL)      == GLFW.GLFW_PRESS;  // 主排 =/+ 键 (次要绑定)
        boolean minus = PackBrowserKeyHandler.isKeyDown(PackBrowserKeyHandler.PREVIEW_LOWER)
                       || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_KP_SUBTRACT) == GLFW.GLFW_PRESS; // 小键盘 - (次要绑定)
        boolean shift = PackBrowserKeyHandler.isKeyDown(PackBrowserKeyHandler.PREVIEW_FAST_MOVE);
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
                    "§e起点: §f" + s.pos.toShortString() + " §7(" + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_FAST_MOVE, "Shift") + " = 5格大步)"), true);
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

        var s = CustomBulldozerPreviewRenderer.getState();
        boolean fill = s != null && ItemCustomBulldozer.getFillMode(s.stack);
        String[] lines = {
            fill ? "§6§l自定义推土机 - 填充预览" : "§6§l自定义推土机 - 清除预览",
            "§7" + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_FORWARD, "↑")
                + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_BACK, "↓")
                + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_LEFT, "←")
                + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_RIGHT, "→")
                + " 移动 §7(" + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_FAST_MOVE, "Shift") + "=大步) | §7"
                + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_RAISE, "+") + "/"
                + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_LOWER, "-") + " 上下 | §6"
                + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_ROTATE, "CTRL") + "+"
                + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_LEFT, "←") + "/"
                + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_RIGHT, "→") + "§7 旋转",
            fill ? "§6§l[" + PackBrowserKeyHandler.buildKeyName() + "]§r§e 确认填充  §7|  §c" + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.CANCEL_PREVIEW, "右键") + " 取消"
                 : "§6§l[" + PackBrowserKeyHandler.buildKeyName() + "]§r§e 确认清除  §7|  §c" + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.CANCEL_PREVIEW, "右键") + " 取消"
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
