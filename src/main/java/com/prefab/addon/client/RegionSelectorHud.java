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
 * 3-4 行就刷下去了, 玩家切回来又忘了按什么键, 而且高频消息会导致聊天栏重排版掉帧.
 * 顶部 HUD 一直挂着, 任何时候都能看到. 操作提示/角点坐标全部由本 HUD 显示,
 * 聊天栏只保留结果反馈 (导出成功/失败/取消).</p>
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

        // 标题/ALT 提示按选区用途区分 (进入时的聊天提示已删, 由 HUD 告知当前模式)
        RegionSelector.Mode mode = (st != null) ? st.mode : RegionSelector.Mode.BLUEPRINT_EXPORT;
        String title;
        String altAction;
        switch (mode) {
            case MINI_BUILDING_CAPTURE -> { title = "§a[迷你建筑转换器]"; altAction = "转换"; }
            case OPERATION_WAND -> { title = "§a[操作手杖]"; altAction = "捕获区域"; }
            default -> { title = "§a[选区模式]"; altAction = "确认"; }
        }

        // 文字内容 (按选区进度动态切换; 已选角点显示坐标, 替代原聊天栏的角点消息)
        String mainText;
        if (cornerCount == 0) {
            mainText = title + " §7左键选角点1 §8| §7右键选角点2 §8| §aALT " + altAction
                + " §8| §cCTRL 取消 §8| §e按住 SHIFT 临时挖方块";
        } else if (cornerCount == 1) {
            mainText = title + " §7角点1: §f(" + st.pos1.toShortString() + ")"
                + " §e→ §7右键选角点2 §8| §aALT " + altAction + " §8| §cCTRL 取消";
        } else {
            mainText = title + " §7角点1: §f(" + st.pos1.toShortString() + ")"
                + " §8| §7角点2: §f(" + st.pos2.toShortString() + ")"
                + " §a→ 按 ALT " + altAction + " §8| §cCTRL 取消";
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
