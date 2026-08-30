package com.prefab.addon.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.items.OperationWandManager;
import com.prefab.addon.items.OperationWandState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;

/**
 * 操作手杖 HUD — 顶部居中提示.
 *
 * <p>触发条件: 玩家手持手杖. 状态:
 * <ul>
 *   <li>未选区: "右键方块开始选区"</li>
 *   <li>选区中: 走 RegionSelectorHud (选角点提示)</li>
 *   <li>已 ready: "模式: 移动/复制, 尺寸, 右键空地=建造, CTRL=取消, shift+↑/↓=切模式"</li>
 * </ul>
 */
@EventBusSubscriber(modid = PrefabCustomAddon.MOD_ID, value = Dist.CLIENT)
public class OperationWandHud {

    @SubscribeEvent
    public static void onRenderGuiLayer(RenderGuiLayerEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (mc.options.hideGui) return;
        if (mc.player.getMainHandItem().getItem() != PrefabCustomAddon.OPERATION_WAND.get()
            && mc.player.getOffhandItem().getItem() != PrefabCustomAddon.OPERATION_WAND.get()) {
            return;
        }

        OperationWandState state = OperationWandManager.get(mc.player);
        if (!state.isReady()) {
            // 未 ready → 不画 (避免每次手持手杖顶部都有提示条)
            return;
        }

        int y = 6;
        GuiGraphics g = event.getGuiGraphics();
        int screenWidth = mc.getWindow().getGuiScaledWidth();

        String modeName = state.mode == OperationWandState.Mode.MOVE ? "移动" : "复制";
        String modeColor = state.mode == OperationWandState.Mode.MOVE ? "§a" : "§d";
        String mainText = String.format(
            "%s[操作手杖] §7模式: %s%s §8| §7尺寸: §f%s §8| §a右键空地 §7= 建造 §8| §cCTRL §7= 取消 §8| §eshift+↑/↓ §7= 切模式",
            "§b", modeColor, modeName, state.sizeString());

        int lineHeight = 12;
        int padding = 4;
        int boxHeight = lineHeight + padding * 2 + 1;

        int textW = mc.font.width(stripColor(mainText));
        int boxWidth = textW + padding * 2;
        int x = (screenWidth - boxWidth) / 2;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.fill(x, y, x + boxWidth, y + boxHeight, 0xC0000000);
        // 紫色顶边线 (跟迷你建筑 HUD 的蓝色, 推土机 HUD 的绿色区分)
        g.fill(x, y, x + boxWidth, y + 1, 0xFFAA55AA);

        int textY = y + padding + 1;
        int textX = x + padding;
        g.drawString(mc.font, mainText, textX, textY, 0xFFFFFF);

        RenderSystem.disableBlend();
    }

    private static String stripColor(String s) {
        return s.replaceAll("§.", "");
    }
}
