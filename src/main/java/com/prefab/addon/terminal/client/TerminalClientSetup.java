package com.prefab.addon.terminal.client;

import com.prefab.addon.terminal.TerminalRegistry;
import com.prefab.addon.terminal.client.gui.TerminalGui;
import com.prefab.addon.terminal.ui.TerminalUI;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 终端客户端启动装配 (自 ProspectClientSetup 裁剪, 去掉勘探/多方块相机的数据链注入).
 *
 * 装配 {@link TerminalUI} 的 Menu UI 客户端钩子 (壁纸绘制 / 图标点击 / create 收尾):
 * common 工厂只留钩子位, 客户端类只在这里被引用 (dedicated server 安全).
 */
@EventBusSubscriber(modid = TerminalRegistry.HOST_MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class TerminalClientSetup {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // 恢复上次选择的主界面壁纸. 必须 enqueueWork: FMLClientSetupEvent 跑在 Worker-Main 线程,
        // 而 DynamicTexture 会调 RenderSystem 创建 GL 纹理, 直接加载会抛
        // "Rendersystem called from wrong thread" → 壁纸每次重启都丢.
        event.enqueueWork(WallpaperManager::load);

        // Menu UI 客户端钩子 (dedicated server 上这些字段保持 null, TerminalUI 走 common 路径)
        TerminalUI.wallpaperRenderer = (ctx, element) -> WallpaperManager.draw(ctx,
                element.getPositionX(), element.getPositionY(),
                element.getSizeWidth(), element.getSizeHeight());
        TerminalUI.appOpener = TerminalGui::onAppIconClick;
        TerminalUI.clientSetup = TerminalGui::setupApps;
        // 窗口尺寸自适应: common 侧按 GUI 可视区夹取窗口大小
        TerminalUI.guiScreenSize = () -> {
            var window = Minecraft.getInstance().getWindow();
            return new float[]{window.getGuiScaledWidth(), window.getGuiScaledHeight()};
        };

        TerminalRegistry.LOGGER.info("[MODERN-TERMINAL] 终端客户端装配完成 (Menu UI 钩子)");
    }
}
