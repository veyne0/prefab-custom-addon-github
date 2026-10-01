package com.prefab.addon.terminal.client.camera;

import com.prefab.addon.terminal.TerminalRegistry;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * 相机输入事件 (game bus, 仅客户端): 左键快门 / ESC 退出 / 断线复位.
 * 实际逻辑都在 {@link TerminalCameraManager}.
 */
@EventBusSubscriber(modid = TerminalRegistry.HOST_MOD_ID, value = Dist.CLIENT)
public final class CameraInputEvents {

    private CameraInputEvents() {
    }

    @SubscribeEvent
    public static void onAttackKey(InputEvent.InteractionKeyMappingTriggered event) {
        TerminalCameraManager.onAttackKey(event);
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        TerminalCameraManager.onScreenOpening(event);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        TerminalCameraManager.onLogout(event);
    }
}
