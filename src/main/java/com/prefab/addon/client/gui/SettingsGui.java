package com.prefab.addon.client.gui;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Scroller;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.config.PlayerPreferences;
import com.prefab.addon.network.NetworkHandler;
import com.prefab.addon.network.UpdateBuildSpeedPayload;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 玩家偏好设置 GUI (LDLib2 实现).
 * 打开方式: GuiCustomStructureSelection 上的"设置"按钮.
 *
 * <h2>权限</h2>
 * <ul>
 *   <li><b>预览速度 (previewBatchPercent)</b>: <strong>个人</strong>设置 (客户端本地, 不走网络), 无权限要求.</li>
 *   <li><b>建造速度 (buildBatchPercent)</b>: <strong>全服共享</strong>, 需要 OP (服务端校验, 非 OP 直接拒绝).
 *       GUI 改值时发 {@link UpdateBuildSpeedPayload} 给服务端, 服务端处理完用 {@link com.prefab.addon.network.SyncBuildSpeedPayload}
 *       广播给所有玩家.</li>
 * </ul>
 *
 * <h2>为什么用 LDLib2</h2>
 * 替代手算坐标 + AbstractSliderButton + ExtendedButton 的拼凑式 UI.
 * 用 Flex 布局 + 现成 Toggle / Scroller / Button, 体积从 ~230 行降到 ~140 行.
 */
public final class SettingsGui {
    private SettingsGui() {}

    public static void open() {
        ModularUI modularUI = createUI();
        Minecraft.getInstance().setScreen(
            new ModularUIScreen(modularUI, Component.literal("Prefab Custom Addon - 设置")));
    }

    private static ModularUI createUI() {
        Minecraft mc = Minecraft.getInstance();
        boolean isOp = mc.player != null && mc.player.hasPermissions(2);
        PlayerPreferences prefs = PlayerPreferences.get();

        // === 根容器 ===
        UIElement root = new UIElement();
        root.layout(l -> l
            .width(300)
            .height(220)
            .paddingAll(10)
            .gapAll(6)
            .flexDirection(FlexDirection.COLUMN)
        );
        root.style(s -> s.background(Sprites.BORDER));

        // 标题
        Label title = new Label();
        title.setText(Component.literal(PrefabCustomAddon.tr("gui.settings.title")));
        title.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        root.addChild(title);

        // === 预览生成速度 (个人设置) ===
        // 之前: 滑条控制 AsyncPreviewBatcher 每 tick 处理多少 % 块 (默认 10%/tick).
        // 现在: AsyncPreviewBatcher 已经固定成 "全量异步" (batchSize = totalBlocks).
        // 原因是: 节流会让玩家在生成过程中移动预览时, 已烘焙方块跟着新 cfg.pos 走,
        // 还在 pending 队列的方块下一 tick 才被处理 (新 blockPos), 中间这一帧不显示 →
        // 视觉上"一部分动了, 一部分没动" → 错位. 一次性全量处理 + 移动/旋转时
        // 自动 requestRestart, 1-2 帧内完成, 无错位. 所以这个 slider 没意义了, 去掉.
        // 字段 PlayerPreferences.previewBatchPercent 保留 (读旧 config 兼容), 但不再有 UI.
        Label previewInfo = new Label();
        previewInfo.setText(Component.literal("预览生成: §e全量异步 §7(已固定, 移动/旋转自动整批重烘焙)"));
        root.addChild(previewInfo);

        // === 建造放置速度 (全服共享, 需 OP) ===
        Label buildLabel = new Label();
        buildLabel.setText(buildLabelText(prefs.getBuildBatchPercent(), isOp));
        root.addChild(buildLabel);
        Scroller.Horizontal buildScroller = new Scroller.Horizontal();
        buildScroller.setRange(1, 100);
        buildScroller.setValue((float) prefs.getBuildBatchPercent());
        buildScroller.setOnValueChanged(v -> {
            int pct = Math.round(v);
            if (!isOp) {
                if (mc.player != null) {
                    mc.player.sendSystemMessage(Component.literal(
                        PrefabCustomAddon.tr("err.build_speed_op"))
                        .withStyle(ChatFormatting.RED));
                }
                PrefabCustomAddon.LOGGER.warn("[SETTINGS] Non-OP player tried to set build speed to {}%, refused", pct);
                return;
            }
            PrefabCustomAddon.LOGGER.info("[SETTINGS] OP player requesting build speed = {}%", pct);
            NetworkHandler.sendToServer(new UpdateBuildSpeedPayload(pct));
        });
        root.addChild(buildScroller);

        // === 完成 ===
        Button doneButton = new Button().setText(Component.literal(PrefabCustomAddon.tr("gui.settings.done")));
        doneButton.setOnClick(e -> mc.setScreen(null));
        root.addChild(doneButton);

        PrefabCustomAddon.LOGGER.info("[SETTINGS] Init: isOp={} previewBatch={}% buildBatch={}%",
            isOp, prefs.getPreviewBatchPercent(), prefs.getBuildBatchPercent());

        return ModularUI.of(UI.of(root,
            StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)));
    }

    private static Component buildLabelText(int pct, boolean isOp) {
        String opHint = isOp ? "" : "  §c(OP only)";
        return Component.literal(PrefabCustomAddon.tr("gui.settings.speed_label", pct, opHint));
    }
}
