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
 *   I 键 → 蓝图广场 (mcschematic.top 在线蓝图下载)
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
    public static KeyMapping OPEN_MCS_MARKET;  // I 默认 → 蓝图广场 (mcschematic.top)
    // === 编辑原理图模式键 (默认值 = 原硬轮询键, 行为不变) ===
    public static KeyMapping EDIT_DELETE;        // 鼠标左键 (删除方块)
    public static KeyMapping EDIT_PLACE;         // 鼠标右键 (放置方块)
    public static KeyMapping EDIT_UNDO;          // 左 CTRL (撤销)
    public static KeyMapping EDIT_SAVE;          // 左 ALT (保存并退出)
    public static KeyMapping EDIT_EXIT;          // ESC (丢弃退出)

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
        OPEN_MCS_MARKET = new KeyMapping(
            "key.prefab_custom_addon.open_mcs_market",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_I,
            KEY_CATEGORY);
        EDIT_DELETE = new KeyMapping(
            "key.prefab_custom_addon.edit_delete",
            InputConstants.Type.MOUSE,
            GLFW.GLFW_MOUSE_BUTTON_LEFT,
            KEY_CATEGORY);
        EDIT_PLACE = new KeyMapping(
            "key.prefab_custom_addon.edit_place",
            InputConstants.Type.MOUSE,
            GLFW.GLFW_MOUSE_BUTTON_RIGHT,
            KEY_CATEGORY);
        EDIT_UNDO = new KeyMapping(
            "key.prefab_custom_addon.edit_undo",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_CONTROL,
            KEY_CATEGORY);
        EDIT_SAVE = new KeyMapping(
            "key.prefab_custom_addon.edit_save",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_ALT,
            KEY_CATEGORY);
        EDIT_EXIT = new KeyMapping(
            "key.prefab_custom_addon.edit_exit",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_ESCAPE,
            KEY_CATEGORY);
    }

    /** NeoForge 会在合适时机调用这个把 key mapping 注册到 Controls 菜单 */
    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        if (OPEN_BROWSER == null) register();
        event.register(OPEN_BROWSER);
        event.register(OPEN_CREATOR);
        event.register(OPEN_MCS_MARKET);
        event.register(EDIT_DELETE);
        event.register(EDIT_PLACE);
        event.register(EDIT_UNDO);
        event.register(EDIT_SAVE);
        event.register(EDIT_EXIT);
    }

    /** 任意 KeyMapping 当前绑定的显示名 (提示文本用, 改键后自动跟着变). */
    public static String keyName(KeyMapping km, String fallback) {
        return km == null ? fallback : km.getTranslatedKeyMessage().getString();
    }

    /**
     * 检测 KeyMapping 当前绑定是否被物理按住 (支持键盘键 / 鼠标键).
     *
     * <p>不用 {@code km.isDown()}: 它与原版键位 (如 WASD 移动、右键使用) 绑同一物理键时,
     * 原版会吞掉 click 计数导致 isDown() 恒为 false. 编辑模式需要"按住"语义
     * (边沿检测), 所以直接查绑定键的物理状态 — 玩家改键后自动跟随新绑定.</p>
     */
    public static boolean isKeyDown(KeyMapping km) {
        if (km == null) return false;
        InputConstants.Key key = km.getKey();
        long window = Minecraft.getInstance().getWindow().getWindow();
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
        }
        return GLFW.glfwGetKey(window, key.getValue()) == GLFW.GLFW_PRESS;
    }
}
