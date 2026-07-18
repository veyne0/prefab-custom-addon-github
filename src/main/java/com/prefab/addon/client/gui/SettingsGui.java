package com.prefab.addon.client.gui;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Scroller;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.config.PlayerPreferences;
import com.prefab.addon.network.NetworkHandler;
import com.prefab.addon.network.UpdateBuildSpeedPayload;
import com.prefab.addon.work.FolderOpener;
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
 *   <li><b>挑战模式 (consumeMaterials)</b>: 需要 OP (permission level >= 2). 非 OP 点击 toggle 会被还原并提示.</li>
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
        title.setText(Component.literal("⚙ Prefab Custom Addon 设置"));
        title.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        root.addChild(title);

        // 副标题
        Label subtitle = new Label();
        subtitle.setText(
            Component.literal("挑战模式开启后, 建造需提交材料").withStyle(ChatFormatting.GRAY));
        subtitle.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        root.addChild(subtitle);

        // === 挑战模式 toggle ===
        Toggle challengeToggle = new Toggle();
        challengeToggle.setText(challengeButtonText(prefs.consumeMaterials, isOp));
        challengeToggle.setOn(prefs.consumeMaterials);
        challengeToggle.setOnToggleChanged(isOn -> {
            if (!isOp) {
                if (mc.player != null) {
                    mc.player.sendSystemMessage(Component.literal(
                        "[挑战模式] 需要 OP 权限才能切换! (permission level >= 2)")
                        .withStyle(ChatFormatting.RED));
                }
                PrefabCustomAddon.LOGGER.warn("[SETTINGS] Non-OP player tried to toggle challenge mode");
                challengeToggle.setOn(!isOn);
                return;
            }
            prefs.setConsumeMaterials(isOn);
            challengeToggle.setText(challengeButtonText(isOn, true));
            PrefabCustomAddon.LOGGER.info("[SETTINGS] Challenge mode toggled to {}", isOn);
        });
        root.addChild(challengeToggle);

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
                        "[建造速度] 需要 OP 权限才能修改! (permission level >= 2)")
                        .withStyle(ChatFormatting.RED));
                }
                PrefabCustomAddon.LOGGER.warn("[SETTINGS] Non-OP player tried to set build speed to {}%, refused", pct);
                return;
            }
            PrefabCustomAddon.LOGGER.info("[SETTINGS] OP player requesting build speed = {}%", pct);
            NetworkHandler.sendToServer(new UpdateBuildSpeedPayload(pct));
        });
        root.addChild(buildScroller);

        // === 打开拓展包文件夹 (无权限要求) ===
        Button folderButton = new Button().setText(Component.literal("📁 打开拓展包文件夹"));
        folderButton.setOnClick(e -> {
            try {
                FolderOpener.openExtensionFolder();
            } catch (Exception ex) {
                PrefabCustomAddon.LOGGER.error("[SETTINGS] 打开拓展包文件夹失败", ex);
            }
        });
        root.addChild(folderButton);

        // === 同步服务器拓展包 (无权限要求, 玩家自己触发) ===
        Button syncServerButton = new Button().setText(Component.literal("🔄 同步服务器拓展包"));
        syncServerButton.setOnClick(e -> {
            // 触发 ServerPackSyncClient 的手动同步流程 (重新发 manifest 到服务端)
            try {
                com.prefab.addon.network.ServerPackSyncClient.getInstance().requestResync();
                if (mc.player != null) {
                    mc.player.sendSystemMessage(Component.literal(
                        "已发送同步请求, 服务器将在数秒内推送最新拓展包...")
                        .withStyle(ChatFormatting.AQUA));
                }
            } catch (Throwable t) {
                PrefabCustomAddon.LOGGER.error("[SETTINGS] 同步服务器拓展包失败", t);
                if (mc.player != null) {
                    mc.player.sendSystemMessage(Component.literal(
                        "✗ 同步失败: " + t.getMessage()).withStyle(ChatFormatting.RED));
                }
            }
        });
        root.addChild(syncServerButton);

        // === 完成 ===
        Button doneButton = new Button().setText(Component.literal("✓ 完成"));
        doneButton.setOnClick(e -> mc.setScreen(null));
        root.addChild(doneButton);

        PrefabCustomAddon.LOGGER.info("[SETTINGS] Init: challengeMode={} isOp={} previewBatch={}% buildBatch={}%",
            prefs.consumeMaterials, isOp, prefs.getPreviewBatchPercent(), prefs.getBuildBatchPercent());

        return ModularUI.of(UI.of(root,
            StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)));
    }

    private static Component challengeButtonText(boolean consume, boolean isOp) {
        String status = consume ? "✓ 开启" : "✗ 关闭";
        String opHint = isOp ? "" : "  §c(需OP)";
        // 全服生效 + OP 权限 这两条是关键提示, 直接钉在按钮文字后面, 玩家一眼能看到.
        return Component.literal("挑战模式 (消耗材料, 全服生效, 须OP权限): " + status + opHint);
    }

    private static Component buildLabelText(int pct, boolean isOp) {
        String opHint = isOp ? "" : "  §c(需OP)";
        return Component.literal("建造放置速度 §7(全服共享, 需OP) §e" + pct + "% §8(1%≈50s, 10%≈5s)" + opHint);
    }
}
