package com.prefab.addon.client;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.client.gui.GuiExtensionPackBrowser;
import com.prefab.addon.client.gui.GuiExtensionPackEditor;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 按键的 tick 轮询 (FORGE 总线事件).
 * KeyMapping 本体的注册在 {@link PackBrowserKeyHandler} (MOD 总线).
 */
@Mod.EventBusSubscriber(modid = PrefabCustomAddon.MOD_ID, value = Dist.CLIENT)
public class PackBrowserKeyTickHandler {

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.level == null) return;
        if (mc.screen != null) return;  // 已有 GUI 打开时不响应

        if (PackBrowserKeyHandler.OPEN_BROWSER == null) return;  // 还没注册

        // Z 键 → 拓展包管理 (浏览/下载) - 现有 6 tab 浏览器
        while (PackBrowserKeyHandler.OPEN_BROWSER.consumeClick()) {
            PrefabCustomAddon.LOGGER.info("[Z-KEY] Opening extension pack browser");
            Minecraft.getInstance().setScreen(new GuiExtensionPackBrowser());
        }

        // X 键 → 独立编辑器 GUI (3 tab: 创建建筑 / 编辑建筑 / 设置)
        while (PackBrowserKeyHandler.OPEN_CREATOR.consumeClick()) {
            PrefabCustomAddon.LOGGER.info("[X-KEY] Opening extension pack editor (3-tab)");
            Minecraft.getInstance().setScreen(new GuiExtensionPackEditor());
        }
    }
}
