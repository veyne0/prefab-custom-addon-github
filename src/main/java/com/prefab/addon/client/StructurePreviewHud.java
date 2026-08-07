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
 * <p>显示条件 (任一满足即画):</p>
 * <ul>
 *   <li>我们 addon 启动的自定义建筑预览 ({@code ADDON_PREVIEW_STRUCTURE != null}) — 我们的 renderer 在画</li>
 *   <li>玩家在 prefab 的 GuiStructure 里预览原版建筑 ({@code prefab.currentStructure != null}) — prefab 自己在画,
 *       但因为 KeyHandler 是我们的, 玩家也能用方向键 / CTRL / ALT, 所以 HUD 也要画出来给玩家看快捷键</li>
 * </ul>
 *
 * <p>文字带半透明背景条, 方便在任何天空/地形背景下都看得清.</p>
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
        // 显示条件 (改):
        //   1) 我们 addon 启动的自定义建筑预览 (ADDON_PREVIEW_STRUCTURE != null)  → 我们的 renderer 在画
        //   2) 玩家在 prefab 的 GuiStructure 里预览原版建筑 (prefab.currentStructure != null)  → prefab 自己画
        // 两种情况都显示顶部操作提示, 跟原版 prefab 保持一致 (原版就有一个简单的 "Preview mode: ..." 文字).
        // 之前逻辑只检查 ADDON_PREVIEW_STRUCTURE, 导致原版预览时 HUD 不显示, 玩家不知道快捷键.

        com.prefab.structures.base.Structure addonStructure =
            com.prefab.addon.client.gui.CustomStructureGui.getAddonPreviewStructure();
        com.prefab.structures.config.StructureConfiguration addonConfig =
            com.prefab.addon.client.gui.CustomStructureGui.getAddonPreviewConfig();
        com.prefab.structures.base.Structure vanillaStructure =
            com.prefab.structures.render.StructureRenderHandler.currentStructure;
        com.prefab.structures.config.StructureConfiguration vanillaConfig =
            com.prefab.structures.render.StructureRenderHandler.currentConfiguration;

        boolean isAddonPreview = addonStructure != null && addonConfig != null;
        boolean isVanillaPreview = !isAddonPreview
            && vanillaStructure != null && vanillaConfig != null
            && com.prefab.PrefabBase.serverConfiguration != null
            && com.prefab.PrefabBase.serverConfiguration.enableStructurePreview;

        if (!isAddonPreview && !isVanillaPreview) {
            return;  // 都没有在预览 → 不画 HUD
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
                com.prefab.addon.PrefabCustomAddon.tr("hud.preview_progress", bar.toString())
            };
            // 修复: 之前 visibleLines=2 但 lines 只有 1 元素, 第 132 行 lines[1] 越界崩溃
            visibleLines = 1;
        } else {
            lines = new String[] {
                com.prefab.addon.PrefabCustomAddon.tr("hud.preview_controls")
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

