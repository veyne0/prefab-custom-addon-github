package com.prefab.addon.client.gui;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollDisplay;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.prefab.addon.PrefabCustomAddon;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * "制作蓝图" tab 配方格用的小型物品搜索弹窗 (LDLib2 自管版).
 *
 * <p>不用 SearchComponent 的浮动 dropdown (定位不稳, 经常渲染不出来),
 * 改用 TextField + ScrollerView 拼一个简单的"搜索框 + 滚动列表"自管 UI.
 * 候选物品按 id 前缀/子串过滤, 上限 200 条, 选中后回调 item id 字符串并
 * 关闭弹窗回到 Editor.</p>
 *
 * <p>关闭语义: 不向 GuiCreateBuildingInfo 写任何状态. 选完后用
 * {@code Minecraft.execute(...)} 延后一帧 {@code setScreen(parent)} 直接回到
 * {@link GuiExtensionPackEditor}, 不弹任何创建建筑窗口, 避免和 LDLib2 Screen
 * 事件循环冲突导致 GL 状态残留 / 双屏叠加.</p>
 */
public final class GuiItemSearchPopup {

    /** 弹窗根容器像素尺寸. */
    private static final int W = 220;
    private static final int H = 280;

    /** 候选显示上限. */
    private static final int MAX_RESULTS = 200;
    /** 单行高. */
    private static final int ROW_H = 18;

    private static GuiExtensionPackEditor pendingParent = null;
    private static int pendingCellIdx = -1;
    private static Consumer<String> pendingCallback = null;

    private GuiItemSearchPopup() {}

    /**
     * 静态入口: 父 Editor 调这个, 选完物品后回调 item id 字符串, 然后回到 Editor.
     */
    public static void open(GuiExtensionPackEditor parent, int cellIdx, Consumer<String> cb) {
        pendingParent = parent;
        pendingCellIdx = cellIdx;
        pendingCallback = cb;

        // 缓存所有有效物品, 启动时构建一次
        List<Item> allItems = new ArrayList<>();
        for (Item it : BuiltInRegistries.ITEM) {
            if (isValid(it)) allItems.add(it);
        }

        // 弹窗 root - 用具体像素尺寸, 避免 percent 在无父容器时算成 0
        UIElement root = new UIElement();
        root.layout(l -> l
            .width(W).height(H)
            .flexDirection(FlexDirection.COLUMN)
            .paddingAll(4).gapAll(4)
        );
        root.style(s -> s.background(Sprites.BORDER));
        root.setOverflowVisible(false);

        // === 标题 ===
        Label title = new Label();
        title.setText("§l" + PrefabCustomAddon.tr("gui.make_blueprint.search.title", pendingCellIdx + 1));
        title.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        title.layout(l -> l.widthPercent(100).height(16));
        root.addChild(title);

        // === 搜索框 ===
        final UIElement listContainer = new UIElement();
        listContainer.layout(l -> l
            .widthPercent(100).heightAuto()
            .flexDirection(FlexDirection.COLUMN).gapAll(1).paddingAll(2).minHeight(0)
        );

        TextField search = new TextField();
        search.setAnyString();  // 允许任意字符输入
        search.setTextResponder(word -> rebuildList(allItems, listContainer, word));
        search.layout(l -> l.widthPercent(100).height(16));
        root.addChild(search);

        // === 滚动列表 (候选物品) ===
        ScrollerView scroller = new ScrollerView();
        scroller.layout(l -> l
            .widthPercent(100).flexGrow(1).flexShrink(1)
            .flexBasis(0).minHeight(0).minWidth(0)
        );
        scroller.scrollerStyle(s -> s
            .mode(ScrollerMode.VERTICAL)
            .verticalScrollDisplay(ScrollDisplay.ALWAYS)
            .horizontalScrollDisplay(ScrollDisplay.NEVER)
            .minScrollPixel(8)
            .maxScrollPixel(80));
        scroller.verticalScroller(s -> s.setScrollBarSize(8));
        scroller.addScrollViewChild(listContainer);
        root.addChild(scroller);

        // === 底部按钮行 + 提示 ===
        UIElement buttonRow = new UIElement();
        buttonRow.layout(l -> l
            .widthPercent(100).height(20)
            .flexDirection(FlexDirection.ROW)
            .gapAll(4).alignItems(AlignItems.CENTER)
        );
        Button btnClear = new Button();
        btnClear.setText(PrefabCustomAddon.tr("gui.make_blueprint.search.clear"));
        btnClear.layout(l -> l.width(50).heightPercent(100));
        btnClear.setOnClick(e -> commitWith(null));
        buttonRow.addChild(btnClear);

        Button btnBack = new Button();
        btnBack.setText(PrefabCustomAddon.tr("gui.make_blueprint.search.back"));
        btnBack.layout(l -> l.width(50).heightPercent(100));
        btnBack.setOnClick(e -> returnToParent());
        buttonRow.addChild(btnBack);

        UIElement filler = new UIElement();
        filler.layout(l -> l.flexGrow(1).heightPercent(100));
        buttonRow.addChild(filler);

        TextElement hint = new TextElement();
        hint.setText(PrefabCustomAddon.tr("gui.make_blueprint.search.hint"));
        hint.textStyle(t -> t.textColor(0xFFAAAAAA));
        hint.layout(l -> l.height(14));
        buttonRow.addChild(hint);

        root.addChild(buttonRow);

        ModularUI ui = ModularUI.of(UI.of(root));
        ModularUIScreen screen = new ModularUIScreen(ui, Component.literal(
            PrefabCustomAddon.tr("gui.make_blueprint.search.window_title")));

        // 初始填充 (空关键字 = 前 200 个)
        rebuildList(allItems, listContainer, "");
        Minecraft.getInstance().setScreen(screen);
    }

    private static boolean isValid(Item it) {
        if (it == null) return false;
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(it);
        if (key == null) return false;
        String id = key.toString();
        return !id.equals("minecraft:air") && !id.equals("minecraft:barrier") && !id.endsWith(":air");
    }

    /**
     * 按 word 重新填充候选列表.
     */
    private static void rebuildList(List<Item> allItems, UIElement listContainer, String word) {
        listContainer.clearAllChildren();
        String q = word == null ? "" : word.trim().toLowerCase(Locale.ROOT);
        List<Item> prefix = new ArrayList<>();
        List<Item> contains = new ArrayList<>();
        if (q.isEmpty()) {
            int n = Math.min(MAX_RESULTS, allItems.size());
            for (int i = 0; i < n; i++) prefix.add(allItems.get(i));
        } else {
            for (Item it : allItems) {
                String id = BuiltInRegistries.ITEM.getKey(it).toString().toLowerCase(Locale.ROOT);
                if (id.startsWith(q)) {
                    prefix.add(it);
                } else if (id.contains(q)) {
                    contains.add(it);
                }
                if (prefix.size() + contains.size() >= MAX_RESULTS) break;
            }
        }
        List<Item> ordered = new ArrayList<>(prefix.size() + contains.size());
        ordered.addAll(prefix);
        ordered.addAll(contains);

        for (Item it : ordered) {
            listContainer.addChild(buildRow(it));
        }
        // 提示: 没匹配时显示一行说明
        if (ordered.isEmpty()) {
            TextElement none = new TextElement();
            none.setText("§7" + PrefabCustomAddon.tr("gui.make_blueprint.search.no_match"));
            none.layout(l -> l.widthPercent(100).height(16));
            listContainer.addChild(none);
        }
    }

    private static UIElement buildRow(Item it) {
        UIElement row = new UIElement();
        row.layout(l -> l
            .widthPercent(100).height(ROW_H)
            .flexDirection(FlexDirection.ROW)
            .gapAll(4).alignItems(AlignItems.CENTER)
        );

        ItemSlot icon = new ItemSlot();
        icon.layout(l -> l.width(16).height(16).flexShrink(0));
        try {
            icon.setValue(new ItemStack(it));
        } catch (Throwable ignore) {}
        row.addChild(icon);

        // 整行用 Button 包, 点击即选中 (覆盖 icon/label 的点击区域)
        TextElement label = new TextElement();
        label.setText(BuiltInRegistries.ITEM.getKey(it).toString());
        label.textStyle(t -> t.textColor(0xFFFFFFFF));
        label.layout(l -> l.flexGrow(1).height(14));
        row.addChild(label);

        // 在 row 上挂点击事件 (LDLib2 元素支持, 不需要绝对定位 overlay)
        row.addEventListener(UIEvents.MOUSE_DOWN, e -> {
            commitWith(it);
        });
        return row;
    }

    private static void commitWith(Item item) {
        String itemId = null;
        if (item != null) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
            if (key != null) itemId = key.toString();
        }
        Consumer<String> cb = pendingCallback;
        returnToParent();
        if (cb != null) {
            try {
                cb.accept(itemId);
            } catch (Throwable t) {
                PrefabCustomAddon.LOGGER.error("[ITEM-SEARCH] 回调失败", t);
            }
        }
    }

    private static void returnToParent() {
        // 拍快照, 然后清空静态引用, 避免重复 commit
        GuiExtensionPackEditor parent = pendingParent;
        pendingCallback = null;
        pendingParent = null;
        pendingCellIdx = -1;
        // 同步切回 parent. 之前延后一帧 (Minecraft.execute) 会让 popup 那帧渲染
        // 残留到 buffer, 下帧切屏时叠加 editor 看到的就是"两屏残影". 同步切时
        // Minecraft 会在同一次 tick 内完成 screen 替换, 不会跨帧.
        if (parent != null) {
            Minecraft.getInstance().setScreen(parent);
        } else {
            Minecraft.getInstance().setScreen(null);
        }
    }
}
