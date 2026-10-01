package com.prefab.addon.terminal.client.camera;

import com.prefab.addon.terminal.TerminalRegistry;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;

/**
 * 相机渲染事件 (mod bus, 仅客户端): 帧末截图 / HUD 层取消 + 取景框 / 隐藏第一人称手.
 * 实际逻辑都在 {@link TerminalCameraManager}.
 */
@EventBusSubscriber(modid = TerminalRegistry.HOST_MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class CameraRenderEvents {

    private CameraRenderEvents() {
    }

    @SubscribeEvent
    public static void onRenderFramePost(RenderFrameEvent.Post event) {
        TerminalCameraManager.onRenderFramePost(event);
    }

    @SubscribeEvent
    public static void onRenderGuiLayer(RenderGuiLayerEvent.Pre event) {
        TerminalCameraManager.onRenderGuiLayer(event);
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        TerminalCameraManager.onRenderHand(event);
    }
}
