package com.prefab.addon.client.gui;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollDisplay;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.config.CategoryManager;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 分类管理独立界面 (LDLib2 实现).
 *
 * <p>玩家在 {@link GuiCreateBuildingInfo} 点 "添加分类" 时弹出来, 支持:
 * <ul>
 *   <li>新建分类 (上限 10 个, 由 {@link CategoryManager#MAX_CATEGORIES} 限制)</li>
 *   <li>重命名已存在的分类 (限 16 字符)</li>
 *   <li>删除分类 (不影响 .txt 已写入的分类字段, 仍显示原值)</li>
 *   <li>返回: 关闭弹窗, 自动重开上一个创建建筑界面, 让玩家接着填</li>
 * </ul>
 *
 * <p>设计参考 {@link GuiCreateBuildingInfo} 的 LDLib2 风格 (Column flex + Scroller).</p>
 */
public final class GuiCategoryManager {

    /** 关闭后回调: 通知上一级重新打开 (例如创建建筑界面), 让玩家接着填分类字段. */
    public interface ReturnCallback {
        void onReturn();
    }

    /** 上次打开此 GUI 时登记的回调. static, 跨 Minecraft.setScreen 切换屏后仍能拿到. */
    private static ReturnCallback returnCallback = null;

    /** 当前状态消息 (供 status 区域显示). */
    private static String statusMessage = null;
    private static int statusColor = 0x55FF55;

    private GuiCategoryManager() {}

    /**
     * 弹一个独立的 ModularUI 屏.
     * @param onReturn 关闭时回调, 通常是 reopen 创建建筑界面
     */
    public static void open(ReturnCallback onReturn) {
        returnCallback = onReturn;
        statusMessage = null;
        statusColor = 0x55FF55;
        ModularUI ui = ModularUI.of(UI.of(buildRoot(),
            StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)));
        String title = com.prefab.addon.PrefabCustomAddon.tr("gui.category.window_title");
        Minecraft.getInstance().setScreen(new ModularUIScreen(ui, Component.literal(title)));
    }

    /**
     * 不带返回回调打开: 关闭后直接退出到上一个屏 (即主屏 setScreen(null)).
     * 供主屏直接调用 (例如建筑 tab 的 "管理分类" 按钮, 没上下文可回).
     */
    public static void openStandalone() {
        returnCallback = null;
        statusMessage = null;
        statusColor = 0x55FF55;
        ModularUI ui = ModularUI.of(UI.of(buildRoot(),
            StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)));
        String title = com.prefab.addon.PrefabCustomAddon.tr("gui.category.window_title");
        Minecraft.getInstance().setScreen(new ModularUIScreen(ui, Component.literal(title)));
    }

    private static UIElement buildRoot() {
        UIElement root = new UIElement();
        root.layout(l -> l
            .width(280).height(260)
            .flexDirection(FlexDirection.COLUMN)
            .paddingAll(6).gapAll(4)
        );
        root.style(s -> s.background(Sprites.BORDER));
        root.setOverflowVisible(false);

        // === 标题 ===
        Label titleEl = new Label();
        titleEl.setText("§l" + com.prefab.addon.PrefabCustomAddon.tr("gui.category.title"));
        titleEl.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        titleEl.layout(l -> l.widthPercent(100).height(18));
        root.addChild(titleEl);

        // === 顶部说明 ===
        TextElement hintEl = new TextElement();
        hintEl.setText(com.prefab.addon.PrefabCustomAddon.tr(
            "gui.category.hint", CategoryManager.MAX_CATEGORIES));
        hintEl.textStyle(t -> t.textColor(0xAAAAAA).textWrap(TextWrap.WRAP));
        hintEl.layout(l -> l.widthPercent(100).heightAuto().minHeight(14));
        root.addChild(hintEl);

        // === 新建分类输入行 ===
        UIElement inputRow = new UIElement();
        inputRow.layout(l -> l.widthPercent(100).height(20)
            .flexDirection(FlexDirection.ROW).gapAll(4).alignItems(AlignItems.CENTER));
        inputRow.setOverflowVisible(false);

        TextField inputTf = new TextField();
        inputTf.setText("");
        inputTf.setTextResponder(s -> { /* 实时不用, 保存时读 */ });
        inputTf.setAnyString();
        // 限长 16 (跟 CategoryManager.addCategory 一致, 截断在 manager 里做)
        inputTf.setTextValidator(s -> s == null || s.length() <= 16);
        inputTf.textFieldStyle(s -> s.textColor(0xFFFFFF).textShadow(false));
        inputTf.layout(l -> l.flexGrow(1).height(16).minHeight(16));
        inputRow.addChild(inputTf);

        Button btnAdd = new Button().setText(com.prefab.addon.PrefabCustomAddon.tr("gui.category.add"));
        btnAdd.setOnClick(e -> {
            String name = inputTf.getText();
            CategoryManager mgr = CategoryManager.get();
            if (mgr.getCategories().size() > mgr.categories.size() + 1) {
                // 防御 (理论上不会发生, getCategories() 永远 >= categories + 1)
            }
            if (!mgr.canAdd()) {
                setStatus(com.prefab.addon.PrefabCustomAddon.tr(
                    "gui.category.full", mgr.MAX_CATEGORIES), 0xFF5555);
                return;
            }
            boolean ok = mgr.addCategory(name);
            if (ok) {
                inputTf.setText("");
                setStatus(com.prefab.addon.PrefabCustomAddon.tr(
                    "gui.category.added", mgr.dumpForLog()), 0x55FF55);
                // 刷新列表
                refreshScreen();
            } else {
                setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.category.add_fail"), 0xFF5555);
            }
        });
        btnAdd.layout(l -> l.heightPercent(100).width(60).flexShrink(0));
        inputRow.addChild(btnAdd);

        root.addChild(inputRow);

        // === 分类列表 (Scroller) ===
        ScrollerView listScroller = new ScrollerView();
        listScroller.layout(l -> l.widthPercent(100).flexGrow(1).flexShrink(1)
            .flexBasis(0).minHeight(0).minWidth(0));
        listScroller.scrollerStyle(s -> s.mode(ScrollerMode.VERTICAL)
            .verticalScrollDisplay(ScrollDisplay.ALWAYS)
            .horizontalScrollDisplay(ScrollDisplay.NEVER)
            .minScrollPixel(8)
            .maxScrollPixel(80));
        listScroller.verticalScroller(s -> s.setScrollBarSize(8));
        UIElement listContent = new UIElement();
        listContent.layout(l -> l.widthPercent(100).heightAuto()
            .flexDirection(FlexDirection.COLUMN).gapAll(2).paddingAll(2).minHeight(0));
        listScroller.addScrollViewChild(listContent);

        // 渲染分类列表
        renderCategoryList(listContent);

        root.addChild(listScroller);

        // === 状态行 ===
        TextElement statusEl = new TextElement();
        statusEl.setText(statusMessage == null ? "" : statusMessage);
        statusEl.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textColor(statusColor));
        statusEl.layout(l -> l.widthPercent(100).height(14));
        root.addChild(statusEl);

        // === 底部按钮行 ===
        UIElement buttonRow = new UIElement();
        buttonRow.layout(l -> l.widthPercent(100).height(22)
            .flexDirection(FlexDirection.ROW).gapAll(2));
        buttonRow.setOverflowVisible(false);

        Button btnDone = new Button().setText(com.prefab.addon.PrefabCustomAddon.tr("gui.category.done"));
        btnDone.setOnClick(e -> {
            ReturnCallback cb = returnCallback;
            returnCallback = null;
            if (cb != null) {
                cb.onReturn();
            } else {
                Minecraft.getInstance().setScreen(null);
            }
        });
        btnDone.layout(l -> l.flexGrow(1).heightPercent(100));
        buttonRow.addChild(btnDone);

        root.addChild(buttonRow);

        return root;
    }

    /**
     * 渲染分类列表 (每行 = 分类名 + [重命名] [删除] 两个按钮).
     * "未分类" 是隐藏默认分类, 不出现在列表里 (玩家不能编辑它).
     */
    private static void renderCategoryList(UIElement listContent) {
        listContent.clearAllChildren();
        CategoryManager mgr = CategoryManager.get();
        List<String> customs = mgr.categories;  // 不含 "未分类"
        if (customs.isEmpty()) {
            TextElement emptyEl = new TextElement();
            emptyEl.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.category.empty"));
            emptyEl.textStyle(t -> t.textColor(0x888888).textWrap(TextWrap.WRAP));
            emptyEl.layout(l -> l.widthPercent(100).heightAuto().minHeight(20));
            listContent.addChild(emptyEl);
            return;
        }
        for (String name : customs) {
            UIElement row = buildCategoryRow(name);
            listContent.addChild(row);
        }
    }

    /** 构造单行: [分类名] [重命名] [删除]. */
    private static UIElement buildCategoryRow(String name) {
        UIElement row = new UIElement();
        row.layout(l -> l.widthPercent(100).height(20)
            .flexDirection(FlexDirection.ROW).gapAll(2).alignItems(AlignItems.CENTER));
        row.setOverflowVisible(false);

        TextElement nameEl = new TextElement();
        nameEl.setText("§f" + name);
        nameEl.textStyle(t -> t.textColor(0xFFFFFF).textWrap(TextWrap.WRAP).adaptiveHeight(true));
        nameEl.layout(l -> l.flexGrow(1).heightAuto().minHeight(14));
        row.addChild(nameEl);

        Button btnRename = new Button().setText(com.prefab.addon.PrefabCustomAddon.tr("gui.category.rename"));
        btnRename.setOnClick(e -> showRenameDialog(name));
        btnRename.layout(l -> l.heightPercent(100).width(40).flexShrink(0));
        row.addChild(btnRename);

        Button btnDelete = new Button().setText("§c" + com.prefab.addon.PrefabCustomAddon.tr("gui.category.delete"));
        btnDelete.setOnClick(e -> {
            CategoryManager mgr = CategoryManager.get();
            boolean ok = mgr.removeCategory(name);
            if (ok) {
                setStatus(com.prefab.addon.PrefabCustomAddon.tr(
                    "gui.category.deleted", name, mgr.dumpForLog()), 0x55FF55);
                refreshScreen();
            } else {
                setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.category.delete_fail"), 0xFF5555);
            }
        });
        btnDelete.layout(l -> l.heightPercent(100).width(30).flexShrink(0));
        row.addChild(btnDelete);

        return row;
    }

    /**
     * 弹一个小弹窗: 重命名分类.
     * 用一个新的 ModularUI 屏 (220x120), 简单点 OK / Cancel.
     */
    private static void showRenameDialog(String oldName) {
        UIElement root = new UIElement();
        root.layout(l -> l
            .width(220).height(120)
            .flexDirection(FlexDirection.COLUMN)
            .paddingAll(8).gapAll(6)
        );
        root.style(s -> s.background(Sprites.BORDER));
        root.setOverflowVisible(false);

        Label title = new Label();
        title.setText("§l" + com.prefab.addon.PrefabCustomAddon.tr(
            "gui.category.rename_title", oldName));
        title.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        title.layout(l -> l.widthPercent(100).height(18));
        root.addChild(title);

        TextField inputTf = new TextField();
        inputTf.setText(oldName);
        inputTf.setAnyString();
        inputTf.setTextValidator(s -> s == null || s.length() <= 16);
        inputTf.textFieldStyle(s -> s.textColor(0xFFFFFF).textShadow(false));
        inputTf.layout(l -> l.widthPercent(100).height(18).minHeight(18));
        root.addChild(inputTf);

        UIElement buttonRow = new UIElement();
        buttonRow.layout(l -> l.widthPercent(100).height(20)
            .flexDirection(FlexDirection.ROW).gapAll(4));
        Button btnCancel = new Button().setText(com.prefab.addon.PrefabCustomAddon.tr("gui.category.cancel"));
        btnCancel.setOnClick(e -> Minecraft.getInstance().setScreen(null));
        btnCancel.layout(l -> l.flexGrow(1).heightPercent(100));
        buttonRow.addChild(btnCancel);

        Button btnOk = new Button().setText(com.prefab.addon.PrefabCustomAddon.tr("gui.category.ok"));
        btnOk.setOnClick(e -> {
            String newName = inputTf.getText();
            CategoryManager mgr = CategoryManager.get();
            if (newName == null || newName.trim().isEmpty()) {
                setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.category.name_empty"), 0xFF5555);
                Minecraft.getInstance().setScreen(null);
                return;
            }
            if (newName.trim().equals(oldName)) {
                // 没改 → 直接关掉
                Minecraft.getInstance().setScreen(null);
                return;
            }
            boolean ok = mgr.renameCategory(oldName, newName);
            if (ok) {
                setStatus(com.prefab.addon.PrefabCustomAddon.tr(
                    "gui.category.renamed", oldName, newName.trim()), 0x55FF55);
                refreshScreen();
            } else {
                setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.category.rename_fail"), 0xFF5555);
            }
        });
        btnOk.layout(l -> l.flexGrow(1).heightPercent(100));
        buttonRow.addChild(btnOk);

        root.addChild(buttonRow);

        ModularUI ui = ModularUI.of(UI.of(root,
            StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)));
        String titleStr = com.prefab.addon.PrefabCustomAddon.tr("gui.category.rename_title", oldName);
        Minecraft.getInstance().setScreen(new ModularUIScreen(ui, Component.literal(titleStr)));
    }

    /** 刷新当前屏 (重新走 buildRoot). 增删分类后用. */
    private static void refreshScreen() {
        // 直接重建 ModularUI 屏
        openStandaloneInternal();
    }

    private static void openStandaloneInternal() {
        ModularUI ui = ModularUI.of(UI.of(buildRoot(),
            StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)));
        String title = com.prefab.addon.PrefabCustomAddon.tr("gui.category.window_title");
        Minecraft.getInstance().setScreen(new ModularUIScreen(ui, Component.literal(title)));
    }

    private static void setStatus(String msg, int color) {
        statusMessage = msg;
        statusColor = color;
    }
}
