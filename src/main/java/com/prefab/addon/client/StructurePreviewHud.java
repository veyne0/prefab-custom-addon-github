package com.prefab.addon.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.prefab.structures.base.Structure;
import com.prefab.structures.render.StructureRenderHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;

/**
 * 自定义建筑预览期间的屏幕顶部 HUD 操作提示 + 异步生成进度.
 *
 * 只在 StructureRenderHandler.currentStructure 不为 null 时显示，避免干扰其他场景。
 * 文字带半透明背景条，方便在任何天空/地形背景下都看得清。
 */
@EventBusSubscriber(modid = "prefab_custom_addon", value = Dist.CLIENT)
public class StructurePreviewHud {

    // 异步生成进度 (由 CustomStructurePreviewRenderer 在 onRenderLevel 里更新)
    private static volatile int currentProcessed = 0;
    private static volatile int currentTotal = 0;
    private static volatile Structure currentProgressStructure = null;

    /**
     * 由 CustomStructurePreviewRenderer 在每帧更新进度.
     * 仅当变化时才更新字段, 减少 volatile 写.
     */
    public static void updateProgress(Structure s, int processed, int total) {
        if (s != currentProgressStructure || processed != currentProcessed || total != currentTotal) {
            currentProgressStructure = s;
            currentProcessed = processed;
            currentTotal = total;
        }
    }

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

        // 异步生成进度 (大结构时显示)
        int total = currentTotal;
        int processed = currentProcessed;
        int percent = total > 0 ? (processed * 100 / total) : 100;
        boolean isGenerating = total > 0 && processed < total;

        String[] lines;
        int visibleLines;
        if (isGenerating) {
            // 进度条: ████████░░░░░░░░ 50% (20000/40000)
            int barWidth = 20;
            int filled = Math.max(0, Math.min(barWidth, percent * barWidth / 100));
            StringBuilder bar = new StringBuilder("§7[");
            for (int i = 0; i < barWidth; i++) {
                if (i < filled) bar.append("§a█");
                else bar.append("§7░");
            }
            bar.append("§7] §e").append(percent).append("% §7(")
               .append(processed).append("/").append(total).append(")");
            lines = new String[] {
                "预览模式: 方向键 移动  |  +/- 上下  |  Shift 加速  |  CTRL 旋转  |  ALT 建造  |  右键方块 取消",
                "§b⏳ 异步生成预览 " + bar
            };
            visibleLines = 2;
        } else {
            lines = new String[] {
                "预览模式: 方向键 移动  |  +/- 上下  |  Shift 加速  |  CTRL 旋转  |  ALT 建造  |  右键方块 取消"
            };
            visibleLines = 1;
        }

        int lineHeight = 12;
        int padding = 4;
        int boxHeight = lineHeight * visibleLines + padding * 2 + 1;  // +1 for top line
        int y = 6;

        int maxWidth = 0;
        for (String s : lines) {
            // strip color codes for width
            String stripped = stripColor(s);
            int w = mc.font.width(stripped);
            if (w > maxWidth) maxWidth = w;
        }
        int boxWidth = maxWidth + padding * 2;

        int x = (screenWidth - boxWidth) / 2;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        guiGraphics.fill(x, y, x + boxWidth, y + boxHeight, 0xC0000000);
        guiGraphics.fill(x, y, x + boxWidth, y + 1, 0xFF55AAFF);

        int textY = y + padding + 1;
        for (int i = 0; i < visibleLines; i++) {
            String s = lines[i];
            String stripped = stripColor(s);
            int textX = x + (boxWidth - mc.font.width(stripped)) / 2;
            guiGraphics.drawString(mc.font, s, textX, textY, 0xFFFFFF);
            textY += lineHeight;
        }
        RenderSystem.disableBlend();
    }

    private static String stripColor(String s) {
        // 简单去除 § + 字符 (color codes), 用于宽度计算
        return s.replaceAll("§.", "");
    }
}

