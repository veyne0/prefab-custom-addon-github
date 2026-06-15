package com.prefab.addon.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.client.gui.GuiExtensionPackBrowser;
import com.prefab.addon.client.gui.GuiExtensionPackCreator;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;

/**
 * 键盘监听器：
 *   Z 键 → 打开拓展包管理界面 (浏览/下载)
 *   X 键 → 打开拓展包制作界面 (创建/编辑本地工作区)
 * 任何时候都能按 (不要求手持蓝图)。可去 Options→Controls 改键。
 */
@EventBusSubscriber(modid = PrefabCustomAddon.MOD_ID, value = Dist.CLIENT)
public class PackBrowserKeyHandler {
    /** 主键：Z。打开拓展包管理界面 (浏览/下载) */
    public static final int BROWSE_KEY = GLFW.GLFW_KEY_Z;
    /** 制作键：X。打开拓展包制作界面 */
    public static final int CREATE_KEY = GLFW.GLFW_KEY_X;

    private static boolean zWasDown = false;
    private static boolean xWasDown = false;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.level == null) return;
        if (mc.screen != null) return;  // 已有 GUI 打开时不响应

        long window = mc.getWindow().getWindow();

        // Z 键 → 拓展包管理 (浏览/下载)
        boolean zDown = InputConstants.isKeyDown(window, BROWSE_KEY);
        if (zDown && !zWasDown) {
            PrefabCustomAddon.LOGGER.info("[Z-KEY] Opening extension pack browser");
            Minecraft.getInstance().setScreen(new GuiExtensionPackBrowser());
        }
        zWasDown = zDown;

        // X 键 → 拓展包制作
        boolean xDown = InputConstants.isKeyDown(window, CREATE_KEY);
        if (xDown && !xWasDown) {
            PrefabCustomAddon.LOGGER.info("[X-KEY] Opening extension pack creator");
            Minecraft.getInstance().setScreen(new GuiExtensionPackCreator());
        }
        xWasDown = xDown;
    }
}
