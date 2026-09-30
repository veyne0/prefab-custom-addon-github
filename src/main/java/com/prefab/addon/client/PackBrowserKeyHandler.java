package com.prefab.addon.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.client.gui.GuiExtensionPackBrowser;
import com.prefab.addon.client.gui.GuiExtensionPackEditor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;
import net.minecraftforge.eventbus.api.EventPriority;

/**
 * 键盘绑定:
 *   Z 键 → 打开拓展包管理界面 (浏览/下载)
 *   X 键 → 打开拓展包制作界面 (创建/编辑本地工作区)
 *
 * 用了 Minecraft 标准的 KeyMapping, 所以这些键会出现在
 * Options → Controls → Prefab Custom Addon 分组里, 玩家可以改键.
 *
 * 注意: RegisterKeyMappingsEvent 是 MOD 总线事件 (bus = MOD),
 * 按键的 tick 轮询在 {@link PackBrowserKeyTickHandler} (FORGE 总线).
 */
@Mod.EventBusSubscriber(modid = PrefabCustomAddon.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
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
}
