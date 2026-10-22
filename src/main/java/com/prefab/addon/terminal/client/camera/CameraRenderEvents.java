package com.prefab.addon.terminal.client.camera;

import com.prefab.addon.terminal.TerminalRegistry;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 相机渲染事件 (forge bus, 仅客户端): 帧末截图 / HUD 层取消 + 取景框 / 隐藏第一人称手.
 * 实际逻辑都在 {@link TerminalCameraManager}.
 *
 * 1.20.1 注: 1.21.1 的 RenderFrameEvent.Post (mod bus) 在 1.20.1 没有直接对应物,
 * 用 RenderTickEvent END 阶段 (帧末) 替代; RenderGuiLayerEvent 对应 RenderGuiOverlayEvent.
 */
@Mod.EventBusSubscriber(modid = TerminalRegistry.HOST_MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CameraRenderEvents {

    private CameraRenderEvents() {
    }

    /** RenderTickEvent END (forge bus): 帧末截图保存 (对应 1.21.1 RenderFrameEvent.Post). */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            TerminalCameraManager.onRenderFrameEnd();
        }
    }

    @SubscribeEvent
    public static void onRenderGuiOverlay(RenderGuiOverlayEvent.Pre event) {
        TerminalCameraManager.onRenderGuiOverlay(event);
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        TerminalCameraManager.onRenderHand(event);
    }
}
