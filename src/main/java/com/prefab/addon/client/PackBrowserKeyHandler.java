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
 *   Z 键 → 打开建筑浏览器 (浏览/下载)
 *   X 键 → 打开建筑制作界面 (创建/编辑本地工作区)
 *
 * 用了 Minecraft 标准的 KeyMapping, 所以这些键会出现在
 * Options → Controls → Prefab Custom Addon 分组里, 玩家可以改键.
 */
@EventBusSubscriber(modid = PrefabCustomAddon.MOD_ID, value = Dist.CLIENT)
public class PackBrowserKeyHandler {

    public static final String KEY_CATEGORY = "key.categories.prefab_custom_addon";

    public static KeyMapping OPEN_BROWSER;  // Z 默认
    public static KeyMapping OPEN_CREATOR;  // X 默认
    /** 预览模式下建造键 (默认左 ALT). 注册成 KeyMapping 后玩家可在
     *  选项→控制→按键绑定 里改键 (玩家反馈: ALT 被硬占用且改不了). */
    public static KeyMapping BUILD_AT_PREVIEW;
    // === 预览操作键 (默认值 = 原来 GLFW 硬轮询的键, 行为不变, 但玩家可改键) ===
    public static KeyMapping PREVIEW_FORWARD;    // ↑
    public static KeyMapping PREVIEW_BACK;       // ↓
    public static KeyMapping PREVIEW_LEFT;       // ←
    public static KeyMapping PREVIEW_RIGHT;      // →
    public static KeyMapping PREVIEW_RAISE;      // 小键盘 + (主排 =/+ 键为内置次要绑定)
    public static KeyMapping PREVIEW_LOWER;      // - (或小键盘 -)
    public static KeyMapping PREVIEW_FAST_MOVE;  // 左 Shift (5 格大步)
    public static KeyMapping PREVIEW_ROTATE;     // 左 CTRL (旋转 90°)
    public static KeyMapping CANCEL_PREVIEW;     // 鼠标右键 (取消预览)
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
        BUILD_AT_PREVIEW = new KeyMapping(
            "key.prefab_custom_addon.build_at_preview",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_ALT,
            KEY_CATEGORY);
        PREVIEW_FORWARD = new KeyMapping(
            "key.prefab_custom_addon.preview_forward",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UP,
            KEY_CATEGORY);
        PREVIEW_BACK = new KeyMapping(
            "key.prefab_custom_addon.preview_back",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_DOWN,
            KEY_CATEGORY);
        PREVIEW_LEFT = new KeyMapping(
            "key.prefab_custom_addon.preview_left",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT,
            KEY_CATEGORY);
        PREVIEW_RIGHT = new KeyMapping(
            "key.prefab_custom_addon.preview_right",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_RIGHT,
            KEY_CATEGORY);
        PREVIEW_RAISE = new KeyMapping(
            "key.prefab_custom_addon.preview_raise",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_KP_ADD,
            KEY_CATEGORY);
        PREVIEW_LOWER = new KeyMapping(
            "key.prefab_custom_addon.preview_lower",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_MINUS,
            KEY_CATEGORY);
        PREVIEW_FAST_MOVE = new KeyMapping(
            "key.prefab_custom_addon.preview_fast_move",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_SHIFT,
            KEY_CATEGORY);
        PREVIEW_ROTATE = new KeyMapping(
            "key.prefab_custom_addon.preview_rotate",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_CONTROL,
            KEY_CATEGORY);
        CANCEL_PREVIEW = new KeyMapping(
            "key.prefab_custom_addon.cancel_preview",
            InputConstants.Type.MOUSE,
            GLFW.GLFW_MOUSE_BUTTON_RIGHT,
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

    /** 建造键当前绑定的显示名 (提示文本用, 改键后自动跟着变). 未注册时兜底 "ALT". */
    public static String buildKeyName() {
        return keyName(BUILD_AT_PREVIEW, "ALT");
    }

    /** 任意 KeyMapping 当前绑定的显示名 (提示文本用, 改键后自动跟着变). */
    public static String keyName(KeyMapping km, String fallback) {
        return km == null ? fallback : km.getTranslatedKeyMessage().getString();
    }

    /**
     * 检测 KeyMapping 当前绑定是否被物理按住 (支持键盘键 / 鼠标键).
     *
     * <p>不用 {@code km.isDown()}: 它与原版键位 (如 WASD 移动、右键使用) 绑同一物理键时,
     * 原版会吞掉 click 计数导致 isDown() 恒为 false. 预览操作需要"按住"语义
     * (持续移动 / 旋转), 所以直接查绑定键的物理状态 — 玩家改键后自动跟随新绑定.</p>
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

    /** NeoForge 会在合适时机调用这个把 key mapping 注册到 Controls 菜单 */
    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        if (OPEN_BROWSER == null) register();
        event.register(OPEN_BROWSER);
        event.register(OPEN_CREATOR);
        event.register(BUILD_AT_PREVIEW);
        event.register(PREVIEW_FORWARD);
        event.register(PREVIEW_BACK);
        event.register(PREVIEW_LEFT);
        event.register(PREVIEW_RIGHT);
        event.register(PREVIEW_RAISE);
        event.register(PREVIEW_LOWER);
        event.register(PREVIEW_FAST_MOVE);
        event.register(PREVIEW_ROTATE);
        event.register(CANCEL_PREVIEW);
        event.register(EDIT_DELETE);
        event.register(EDIT_PLACE);
        event.register(EDIT_UNDO);
        event.register(EDIT_SAVE);
        event.register(EDIT_EXIT);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.level == null) return;
        if (mc.screen != null) return;  // 已有 GUI 打开时不响应

        if (OPEN_BROWSER == null) return;  // 还没注册

        // Z 键 → 建筑浏览器 (浏览/下载) - 现有 6 tab 浏览器
        while (OPEN_BROWSER.consumeClick()) {
            PrefabCustomAddon.LOGGER.info("[Z-KEY] Opening building browser");
            Minecraft.getInstance().setScreen(new GuiExtensionPackBrowser());
        }

        // X 键 → 独立编辑器 GUI (3 tab: 创建建筑 / 编辑建筑 / 设置)
        while (OPEN_CREATOR.consumeClick()) {
            PrefabCustomAddon.LOGGER.info("[X-KEY] Opening building creator (3-tab)");
            Minecraft.getInstance().setScreen(new GuiExtensionPackEditor());
        }
    }
}
