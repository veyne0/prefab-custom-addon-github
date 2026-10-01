package com.prefab.addon.terminal.client.camera;

import com.mojang.blaze3d.platform.NativeImage;
import com.prefab.addon.terminal.TerminalRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import com.prefab.addon.terminal.network.TerminalPayloads;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * 终端相机模式 (client-only, 拍照机制参考 MIT 协议的 Camerapture PictureTaker 简化而来).
 *
 * 流程: 相机软件点击"启动" → 关闭终端 GUI → cameraMode=true (无 Screen, 玩家可自由走动/视角):
 *   - RenderGuiLayerEvent.Pre 取消全部原版 HUD 层, 借准星层位置画取景框 (照抄 Camerapture);
 *   - RenderHandEvent 隐藏第一人称手;
 *   - 左键 (InteractionKeyMappingTriggered 取消攻击) = 快门: 藏 HUD 一帧 →
 *     RenderFrameEvent.Post 截帧 → PNG 存相册 (TerminalPhotoStore);
 *   - ESC: 原版会弹暂停菜单 → ScreenEvent.Opening 拦截 PauseScreen 并退出相机.
 * 拍照帧取景框因 hideGui=true 自动消失 (与 Camerapture 的 !hideGui 门控一致).
 */
public final class TerminalCameraManager {
    public static boolean cameraMode = false;

    private static boolean capturePending = false;
    private static boolean hudWasHidden = false;

    // 拍照结果提示 (取景框内自绘: 相机模式 HUD 全被取消, chat 消息要等退出才可见)
    private static long noticeUntil = 0;
    private static boolean noticeError = false;
    private static String noticeArg = "";
    private static long flashUntil = 0;
    private static final long NOTICE_MS = 2500;
    private static final long FLASH_MS = 250;

    private TerminalCameraManager() {
    }

    /** 进入相机模式 (终端 GUI 已由调用方通过 mc.tell 关闭). */
    public static void enter() {
        cameraMode = true;
        capturePending = false;
    }

    public static void exit() {
        cameraMode = false;
        capturePending = false;
        noticeUntil = 0;
        flashUntil = 0;
        // 保险: 无论如何恢复 HUD (requestCapture 里保存过原值, 这里兜底)
        Minecraft.getInstance().options.hideGui = false;
    }

    // ==================== 事件入口 (注册见 CameraRenderEvents / CameraInputEvents) ====================

    /** RenderFrameEvent.Post (mod bus): 帧末截图保存. */
    public static void onRenderFramePost(RenderFrameEvent.Post event) {
        if (!capturePending) {
            return;
        }
        capturePending = false;
        Minecraft mc = Minecraft.getInstance();
        mc.options.hideGui = hudWasHidden;
        try (NativeImage image = takeScreenshot(mc)) {
            String name = TerminalPhotoStore.save(image);
            // 提示画在取景框里 (HUD 已取消, chat 消息此刻不可见)
            noticeError = false;
            noticeArg = name;
            noticeUntil = System.currentTimeMillis() + NOTICE_MS;
            flashUntil = System.currentTimeMillis() + FLASH_MS;
        } catch (Exception e) {
            TerminalRegistry.LOGGER.error("[CAMERA] 照片保存失败", e);
            noticeError = true;
            noticeArg = "";
            noticeUntil = System.currentTimeMillis() + NOTICE_MS;
        }
    }

    private static NativeImage takeScreenshot(Minecraft mc) throws Exception {
        return net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget());
    }

    /** RenderGuiLayerEvent.Pre (mod bus): 相机模式取消全部原版 HUD 层, 准星层画取景框. */
    public static void onRenderGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (!cameraMode) {
            return;
        }
        event.setCanceled(true);
        if (event.getName() == VanillaGuiLayers.CROSSHAIR && !Minecraft.getInstance().options.hideGui) {
            drawViewfinder(event.getGuiGraphics());
        }
    }

    /** RenderHandEvent (mod bus): 相机模式隐藏第一人称手. */
    public static void onRenderHand(RenderHandEvent event) {
        if (cameraMode) {
            event.setCanceled(true);
        }
    }

    /** InteractionKeyMappingTriggered (game bus): 相机模式左键 = 快门, 取消攻击/挖掘/挥手. */
    public static void onAttackKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (cameraMode && event.isAttack()) {
            event.setCanceled(true);
            event.setSwingHand(false);
            requestCapture();
        }
    }

    /** ScreenEvent.Opening (game bus): 相机模式按 ESC 拦截暂停菜单 → 退出相机 → 回到终端主界面. */
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (cameraMode && event.getNewScreen() instanceof PauseScreen) {
            event.setCanceled(true);
            exit();
            // 下一帧请求服务端重开终端 GUI (菜单必须由服务端发起)
            Minecraft.getInstance().tell(TerminalPayloads::sendReopenTerminal);
        }
    }

    /** ClientPlayerNetworkEvent.LoggingOut (game bus): 断线/退世界退出相机. */
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        exit();
    }

    // ==================== 内部 ====================

    private static void requestCapture() {
        if (capturePending) {
            return;
        }
        capturePending = true;
        hudWasHidden = Minecraft.getInstance().options.hideGui;
        Minecraft.getInstance().options.hideGui = true;
    }

    /** 取景框: 四角括号 + 中心十字 + 顶部操作提示. */
    private static void drawViewfinder(GuiGraphics gui) {
        Minecraft mc = Minecraft.getInstance();
        int w = gui.guiWidth();
        int h = gui.guiHeight();
        int margin = 16;
        int len = 14;
        int color = 0xFFFFFFFF;
        int accent = 0xFF80FF80;

        // 四角括号 (横竖两笔)
        gui.fill(margin, margin, margin + len, margin + 1, color);
        gui.fill(margin, margin, margin + 1, margin + len, color);
        gui.fill(w - margin - len, margin, w - margin, margin + 1, color);
        gui.fill(w - margin - 1, margin, w - margin, margin + len, color);
        gui.fill(margin, h - margin - 1, margin + len, h - margin, color);
        gui.fill(margin, h - margin - len, margin + 1, h - margin, color);
        gui.fill(w - margin - len, h - margin - 1, w - margin, h - margin, color);
        gui.fill(w - margin - 1, h - margin - len, w - margin, h - margin, color);

        // 中心十字
        int cx = w / 2;
        int cy = h / 2;
        gui.fill(cx - 5, cy, cx - 2, cy + 1, color);
        gui.fill(cx + 3, cy, cx + 6, cy + 1, color);
        gui.fill(cx, cy - 5, cx + 1, cy - 2, color);
        gui.fill(cx, cy + 3, cx + 1, cy + 6, color);

        // 顶部提示
        gui.drawCenteredString(mc.font,
                Component.translatable("gui.modern_terminal.camera.hint"),
                w / 2, margin + 6, accent);

        // 拍照白闪 (渐隐)
        long now = System.currentTimeMillis();
        if (now < flashUntil) {
            int alpha = (int) (170 * (flashUntil - now) / FLASH_MS);
            gui.fill(0, 0, w, h, (alpha << 24) | 0xFFFFFF);
        }

        // 保存结果提示 (底部常驻至超时)
        if (now < noticeUntil) {
            Component msg = Component.translatable(
                    noticeError ? "gui.modern_terminal.camera.save_failed"
                            : "gui.modern_terminal.camera.saved",
                    noticeArg);
            gui.drawCenteredString(mc.font, msg, w / 2, h - margin - 10,
                    noticeError ? 0xFFFF6060 : accent);
        }
    }
}
