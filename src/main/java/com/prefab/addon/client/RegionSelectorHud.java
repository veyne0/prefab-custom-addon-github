package com.prefab.addon.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.prefab.addon.work.RegionSelector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;

/**
 * 选区模式屏幕顶部居中 HUD 操作提示.
 *
 * <p>跟 {@link StructurePreviewHud} 风格一致 (黑色半透底 + 蓝色顶边线 + 居中文字),
 * 区别只在触发条件: 玩家处于 {@link RegionSelector} 选区模式时显示.</p>
 *
 * <p>为什么不再用聊天栏消息? 之前的实现是 {@code displayClientMessage}, 但聊天栏消息
 * 3-4 行就刷下去了, 玩家切回来又忘了按什么键. 顶部 HUD 一直挂着, 任何时候都能看到.
 * 同样的话会同时发给聊天栏 (留个 log) + HUD (可见提示), 玩家两全.</p>
 */
@EventBusSubscriber(modid = "prefab_custom_addon", value = Dist.CLIENT)
public class RegionSelectorHud {

    @SubscribeEvent
    public static void onRenderGuiLayer(RenderGuiLayerEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (mc.options.hideGui) return;

        // 没在选区模式 → 不画 (避免每次进游戏顶部都挂个提示条)
        if (!RegionSelector.isActive(mc.player)) return;

        // 选区进度: 0=还没选, 1=选了角点1, 2=角点1+角点2 都有
        RegionSelector.SelectionState st = RegionSelector.getState(mc.player);
        int cornerCount = (st != null) ? st.getCornerCount() : 0;

        // 顶部偏移, 跟 StructurePreviewHud 错开 (预览时上下排, 不重叠)
        int y = 6;

        GuiGraphics g = event.getGuiGraphics();
        int screenWidth = mc.getWindow().getGuiScaledWidth();

        // 文字内容 (按选区进度动态切换, 玩家更清楚当前该干嘛)
        String mainText;
        if (cornerCount == 0) {
            mainText = "§a[选区模式] §7左键选角点1 §8| §7右键选角点2 §8| §aALT 确认 §7| §cCTRL 取消 §7| §e按住 SHIFT 临时挖方块";
        } else if (cornerCount == 1) {
            mainText = "§a[选区模式] §7已选角点1 §e→ §7请选角点2 §8(§7右键§8) §7| §aALT 确认 §7| §cCTRL 取消";
        } else {
            mainText = "§a[选区模式] §7已选 §f" + cornerCount + " §7个角点 §a→ 按 §fALT §a确认 §7| §cCTRL 取消";
        }

        int lineHeight = 12;
        int padding = 4;
        int boxHeight = lineHeight + padding * 2 + 1;  // +1 for top accent line

        // 居中宽度: strip color codes for accurate width
        String stripped = stripColor(mainText);
        int textW = mc.font.width(stripped);
        int boxWidth = textW + padding * 2;

        int x = (screenWidth - boxWidth) / 2;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        // 半透明黑底 (跟 StructurePreviewHud 一致: 0xC0000000)
        g.fill(x, y, x + boxWidth, y + boxHeight, 0xC0000000);
        // 顶边线: 绿色 (跟 StructurePreviewHud 的蓝色区分, 让玩家一眼看出"这是选区"不是"预览")
        g.fill(x, y, x + boxWidth, y + 1, 0xFF55AA55);

        int textY = y + padding + 1;
        int textX = x + padding;
        g.drawString(mc.font, mainText, textX, textY, 0xFFFFFF);

        RenderSystem.disableBlend();
    }

    private static String stripColor(String s) {
        return s.replaceAll("§.", "");
    }
}
