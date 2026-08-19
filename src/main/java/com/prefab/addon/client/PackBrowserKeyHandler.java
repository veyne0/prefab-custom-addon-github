package com.prefab.addon.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.client.gui.GuiExtensionPackBrowser;
import com.prefab.addon.client.gui.GuiExtensionPackEditor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;
import net.neoforged.bus.api.EventPriority;

/**
 * 键盘绑定:
 *   Z 键 → 打开拓展包管理界面 (浏览/下载)
 *   X 键 → 打开拓展包制作界面 (创建/编辑本地工作区)
 *
 * 用了 Minecraft 标准的 KeyMapping, 所以这些键会出现在
 * Options → Controls → Prefab Custom Addon 分组里, 玩家可以改键.
 */
@EventBusSubscriber(modid = PrefabCustomAddon.MOD_ID, value = Dist.CLIENT)
public class PackBrowserKeyHandler {

    public static final String KEY_CATEGORY = "key.categories.prefab_custom_addon";

    public static KeyMapping OPEN_BROWSER;  // Z 默认
    public static KeyMapping OPEN_CREATOR;  // X 默认

    /** 构造, 在 mod 启动时调用 */
    public static void register() {
        OPEN_BROWSER = new KeyMapping(
            "key.prefab_custom_addon.open_browser",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Z,
            KEY_CATEGORY);
        OPEN_CREATOR = new KeyMapping(
            "key.prefab_custom_addon.open_creator",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_X,
            KEY_CATEGORY);
    }

    /** NeoForge 会在合适时机调用这个把 key mapping 注册到 Controls 菜单 */
    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        if (OPEN_BROWSER == null) register();
        event.register(OPEN_BROWSER);
        event.register(OPEN_CREATOR);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.level == null) return;
        if (mc.screen != null) return;  // 已有 GUI 打开时不响应

        if (OPEN_BROWSER == null) return;  // 还没注册

        // Z 键 → 拓展包管理 (浏览/下载) - 现有 6 tab 浏览器
        while (OPEN_BROWSER.consumeClick()) {
            PrefabCustomAddon.LOGGER.info("[Z-KEY] Opening extension pack browser");
            Minecraft.getInstance().setScreen(new GuiExtensionPackBrowser());
        }

        // X 键 → 独立编辑器 GUI (3 tab: 创建建筑 / 编辑建筑 / 设置)
        while (OPEN_CREATOR.consumeClick()) {
            PrefabCustomAddon.LOGGER.info("[X-KEY] Opening extension pack editor (3-tab)");
            Minecraft.getInstance().setScreen(new GuiExtensionPackEditor());
        }
    }
}
