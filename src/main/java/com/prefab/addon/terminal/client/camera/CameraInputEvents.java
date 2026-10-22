package com.prefab.addon.terminal.client.camera;

import com.prefab.addon.terminal.TerminalRegistry;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 相机输入事件 (forge bus, 仅客户端): 左键快门 / ESC 退出 / 断线复位.
 * 实际逻辑都在 {@link TerminalCameraManager}.
 */
@Mod.EventBusSubscriber(modid = TerminalRegistry.HOST_MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
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
