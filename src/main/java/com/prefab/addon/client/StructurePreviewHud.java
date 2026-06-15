package com.prefab.addon.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.prefab.structures.render.StructureRenderHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;

/**
 * 自定义建筑预览期间的屏幕顶部 HUD 操作提示。
 *
 * 只在 StructureRenderHandler.currentStructure 不为 null 时显示，避免干扰其他场景。
 * 文字带半透明背景条，方便在任何天空/地形背景下都看得清。
 */
@EventBusSubscriber(modid = "prefab_custom_addon", value = Dist.CLIENT)
public class StructurePreviewHud {

    @SubscribeEvent
    public static void onRenderGuiLayer(RenderGuiLayerEvent.Post event) {
        // 只在预览自定义建筑时显示
        if (StructureRenderHandler.currentStructure == null
                || StructureRenderHandler.currentConfiguration == null) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (mc.options.hideGui) return;

        GuiGraphics guiGraphics = event.getGuiGraphics();
        int screenWidth = mc.getWindow().getGuiScaledWidth();

        String[] lines = {
            "预览模式: 方向键 移动  |  +/- 上下  |  Shift 加速  |  CTRL 旋转  |  ALT 建造  |  右键方块 取消"
        };

        int lineHeight = 12;
        int padding = 4;
        int boxHeight = lineHeight * lines.length + padding * 2 + 1;  // +1 for top line
        int y = 6;

        int maxWidth = 0;
        for (String s : lines) {
            int w = mc.font.width(s);
            if (w > maxWidth) maxWidth = w;
        }
        int boxWidth = maxWidth + padding * 2;

        int x = (screenWidth - boxWidth) / 2;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        guiGraphics.fill(x, y, x + boxWidth, y + boxHeight, 0xC0000000);
        guiGraphics.fill(x, y, x + boxWidth, y + 1, 0xFF55AAFF);

        int textY = y + padding + 1;
        for (String s : lines) {
            int textX = x + (boxWidth - mc.font.width(s)) / 2;
            guiGraphics.drawString(mc.font, s, textX, textY, 0xFFFFFF);
            textY += lineHeight;
        }
        RenderSystem.disableBlend();
    }
}

