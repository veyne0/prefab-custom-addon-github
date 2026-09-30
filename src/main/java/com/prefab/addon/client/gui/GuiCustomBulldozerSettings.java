package com.prefab.addon.client.gui;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.items.ItemCustomBulldozer;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 自定义推土机 — 设置 GUI (LdLib 实现).
 *
 * <p>提供: 长/宽/高输入 + 预设按钮 + 破坏不生成掉落物 (noDrops) 切换.</p>
 *
 * <p>无模态: 直接 ModularUIScreen 打开. 关闭时回到 parent (主推土机 GUI).</p>
 */
public final class GuiCustomBulldozerSettings {

    private GuiCustomBulldozerSettings() {}

    /**
     * 打开设置 GUI.
     *
     * @param parent 主推土机 GUI, 按 "返回" / "确定" 时回到这里
     * @param stack  玩家手持的推土机 ItemStack
     */
    public static void open(Screen parent, ItemStack stack) {
        ModularUI modularUI = createUI(parent, stack);
        Minecraft.getInstance().setScreen(
            new ModularUIScreen(modularUI, Component.literal("自定义推土机 - 设置"))
        );
    }

    private static ModularUI createUI(Screen parent, ItemStack stack) {
        // === 输入框 ===
        TextField fieldLength = new TextField();
        fieldLength.setText(String.valueOf(ItemCustomBulldozer.getLength(stack)));
        fieldLength.textFieldStyle(s -> s.placeholder(Component.literal("长 X (1-256)")));

        TextField fieldWidth = new TextField();
        fieldWidth.setText(String.valueOf(ItemCustomBulldozer.getWidth(stack)));
        fieldWidth.textFieldStyle(s -> s.placeholder(Component.literal("宽 Z (1-256)")));

        TextField fieldHeight = new TextField();
        fieldHeight.setText(String.valueOf(ItemCustomBulldozer.getHeight(stack)));
        fieldHeight.textFieldStyle(s -> s.placeholder(Component.literal("高 Y (1-256)")));

        // === 状态标签 ===
        Label statusLabel = new Label();
        statusLabel.setText(Component.literal(""));
        statusLabel.textStyle(t -> t.textAlignHorizontal(Horizontal.LEFT));

        // === 滚动主区内容 ===
        UIElement content = new UIElement();
        content.layout(l -> l
            .width(440)
            .paddingAll(12)
            .gapAll(8)
            .flexDirection(FlexDirection.COLUMN)
        );
        content.style(s -> s.background(Sprites.BORDER));

        // 标题小字
        content.addChild(makeInfoLabel(
            "§7清除区域 (长 X 宽 X 高, 范围 1..256)",
            12
        ));

        // 三输入框行
        content.addChild(makeRow("长 X:", fieldLength));
        content.addChild(makeRow("宽 Z:", fieldWidth));
        content.addChild(makeRow("高 Y:", fieldHeight));

        // 预设按钮行
        UIElement presetRow = new UIElement();
        presetRow.layout(l -> l
            .widthPercent(100)
            .height(24)
            .flexDirection(FlexDirection.ROW)
            .gapAll(8)
            .alignItems(AlignItems.CENTER)
        );
        presetRow.addChild(makeInfoLabel("§7预设:", 12));
        content.addChild(presetRow);

        UIElement presetBtns = new UIElement();
        presetBtns.layout(l -> l
            .widthPercent(100)
            .height(24)
            .flexDirection(FlexDirection.ROW)
            .gapAll(6)
            .alignItems(AlignItems.CENTER)
        );
        // 16x16x16
        presetBtns.addChild(makePresetBtn("§716x16x16", 16, 16, 16, fieldLength, fieldWidth, fieldHeight));
        // 32x32x32
        presetBtns.addChild(makePresetBtn("§732x32x32", 32, 32, 32, fieldLength, fieldWidth, fieldHeight));
        // 64x64x32
        presetBtns.addChild(makePresetBtn("§764x64x32", 64, 64, 32, fieldLength, fieldWidth, fieldHeight));
        content.addChild(presetBtns);

        // === 固定说明: 任何模式都不生成掉落物 (玩家不再能切换) ===
        Label fixedInfo = new Label();
        fixedInfo.setText(Component.literal(
            "§7ⓘ 清除时§c不生成掉落物§7 (固定, 不可改, 大区域性能最佳)"));
        fixedInfo.textStyle(t -> t.textColor(0xFFFFCC55).textAlignHorizontal(Horizontal.LEFT));
        fixedInfo.layout(l -> l.widthPercent(100).height(18));
        content.addChild(fixedInfo);

        // 状态行
        statusLabel.layout(l -> l.widthPercent(100).height(14));
        content.addChild(statusLabel);

        // === ScrollerView 包裹 ===
        ScrollerView scrollerView = new ScrollerView();
        scrollerView.layout(l -> l.width(456).height(220));
        scrollerView.scrollerStyle(style -> style.mode(ScrollerMode.VERTICAL));
        scrollerView.addScrollViewChild(content);

        // === 根容器 ===
        UIElement root = new UIElement();
        root.layout(l -> l
            .width(456)
            .height(320)
            .flexDirection(FlexDirection.COLUMN)
        );
        root.style(s -> s.background(Sprites.BORDER));

        // 标题栏
        UIElement titleBar = new UIElement();
        titleBar.layout(l -> l
            .widthPercent(100).height(24)
            .paddingHorizontal(8)
            .justifyContent(AlignContent.CENTER)
        );
        titleBar.style(s -> s.background(Sprites.RECT_DARK));
        Label title = new Label();
        title.setText(Component.literal("自定义推土机 - 设置"));
        title.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textColor(0xFFFFFFFF));
        titleBar.addChild(title);
        root.addChild(titleBar);

        // 工具栏 (返回 / 保存)
        UIElement toolbar = new UIElement();
        toolbar.layout(l -> l
            .widthPercent(100).height(28)
            .paddingHorizontal(8).paddingVertical(4)
            .flexDirection(FlexDirection.ROW)
            .alignItems(AlignItems.CENTER)
            .justifyContent(AlignContent.SPACE_BETWEEN)
        );
        toolbar.style(s -> s.background(Sprites.RECT_DARK));

        Button btnBack = new Button();
        btnBack.setText(Component.literal("← 返回"));
        btnBack.layout(l -> l.width(60).height(18));
        btnBack.setOnClick(e -> Minecraft.getInstance().setScreen(parent));
        toolbar.addChild(btnBack);

        Button btnSave = new Button();
        btnSave.setText(Component.literal("§a✓ 保存"));
        btnSave.layout(l -> l.width(80).height(18));
        btnSave.setOnClick(e -> doSave(parent, stack, fieldLength, fieldWidth, fieldHeight, statusLabel));
        toolbar.addChild(btnSave);

        root.addChild(toolbar);

        // 滚动主区
        root.addChild(scrollerView);

        // 底部状态条 (固定 36px, 显示当前区域)
        UIElement bottomBar = new UIElement();
        bottomBar.layout(l -> l
            .widthPercent(100).height(36)
            .paddingAll(8)
            .gapAll(8)
            .flexDirection(FlexDirection.ROW)
            .alignItems(AlignItems.CENTER)
        );
        bottomBar.style(s -> s.background(Sprites.RECT_DARK));
        Label currentLabel = new Label();
        currentLabel.setText(Component.literal(String.format(
            "§7当前: §f%dx%dx%d §7|  §c不生成掉落物",
            ItemCustomBulldozer.getLength(stack),
            ItemCustomBulldozer.getWidth(stack),
            ItemCustomBulldozer.getHeight(stack)
        )).withStyle(ChatFormatting.WHITE));
        currentLabel.textStyle(t -> t.textAlignHorizontal(Horizontal.LEFT));
        currentLabel.layout(l -> l.widthPercent(100).height(18));
        bottomBar.addChild(currentLabel);
        root.addChild(bottomBar);

        return ModularUI.of(UI.of(root,
            StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)));
    }

    // === 辅助 ===

    /** 构造一行: 60px 标签 + flex 输入框 */
    private static UIElement makeRow(String labelText, TextField input) {
        UIElement row = new UIElement();
        row.layout(l -> l
            .widthPercent(100).height(22)
            .flexDirection(FlexDirection.ROW)
            .gapAll(8)
            .alignItems(AlignItems.CENTER)
        );
        Label label = new Label();
        label.setText(Component.literal(labelText).withStyle(ChatFormatting.GRAY));
        label.textStyle(t -> t.textAlignHorizontal(Horizontal.LEFT));
        label.layout(l -> l.width(60).height(18));
        row.addChild(label);

        input.layout(l -> l.flex(1).height(18));
        row.addChild(input);
        return row;
    }

    /** 单行灰色提示文字 */
    private static Label makeInfoLabel(String text, int heightPx) {
        Label l = new Label();
        l.setText(Component.literal(text));
        l.textStyle(t -> t.textAlignHorizontal(Horizontal.LEFT));
        l.layout(layout -> layout.widthPercent(100).height(heightPx));
        return l;
    }

    /** 预设按钮: 点击填入 L/W/H */
    private static Button makePresetBtn(String label, int L, int W, int H,
                                        TextField fL, TextField fW, TextField fH) {
        Button b = new Button();
        b.setText(Component.literal(label));
        b.layout(l -> l.width(60).height(18));
        b.setOnClick(e -> {
            fL.setText(String.valueOf(L));
            fW.setText(String.valueOf(W));
            fH.setText(String.valueOf(H));
        });
        return b;
    }

    /** 更新 noDrops 按钮的文字/颜色 */
    private static void updateNoDropsBtn(Button btn, boolean on) {
        if (on) {
            btn.setText(Component.literal("§c✗ 破坏不生成掉落物: §l[已开启]"));
            btn.text.textStyle(t -> t.textColor(0xFFFF5555));
        } else {
            btn.setText(Component.literal("§a✓ 破坏不生成掉落物: §l[已关闭]"));
            btn.text.textStyle(t -> t.textColor(0xFF55FF55));
        }
    }

    /** 校验 + 保存 + 回到 parent */
    private static void doSave(Screen parent, ItemStack stack,
                               TextField fL, TextField fW, TextField fH, Label status) {
        int L, W, H;
        try { L = Integer.parseInt(fL.getValue().trim()); }
        catch (Exception e) {
            setStatus(status, "§c✗ 长 必须为整数", 0xFFFF5555);
            return;
        }
        try { W = Integer.parseInt(fW.getValue().trim()); }
        catch (Exception e) {
            setStatus(status, "§c✗ 宽 必须为整数", 0xFFFF5555);
            return;
        }
        try { H = Integer.parseInt(fH.getValue().trim()); }
        catch (Exception e) {
            setStatus(status, "§c✗ 高 必须为整数", 0xFFFF5555);
            return;
        }
        if (L < 1 || L > 256 || W < 1 || W > 256 || H < 1 || H > 256) {
            setStatus(status, "§c✗ 数值必须在 1..256 之间 (当前: "
                + L + "x" + W + "x" + H + ")", 0xFFFF5555);
            return;
        }
        ItemCustomBulldozer.setDimensions(stack, L, W, H);
        // 固定 noDrops = true (玩家不能改, 性能最优)
        ItemCustomBulldozer.setNoDrops(stack, true);
        PrefabCustomAddon.LOGGER.info("[Bulldozer] settings saved: {}x{}x{} (noDrops 固定开启)",
            L, W, H);
        // 关闭 LdLib UI, 回到主推土机 GUI
        Minecraft.getInstance().setScreen(parent);
    }

    private static void setStatus(Label l, String text, int color) {
        l.setText(Component.literal(text));
        l.textStyle(t -> t.textColor(color));
    }
}
