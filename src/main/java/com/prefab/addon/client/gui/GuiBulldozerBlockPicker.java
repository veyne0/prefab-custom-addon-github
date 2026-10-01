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
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 自定义推土机 — 「填充方块」选择弹窗 (仿 {@link GuiItemSearchPopup} / 图2 样式).
 *
 * <p>结构完全对齐 GuiItemSearchPopup: TextField 搜索框 + ScrollerView 滚动列表 +
 * 底部「清空 / 返回」按钮. 区别:</p>
 * <ul>
 *   <li>只列出可放置的方块 (BlockItem, 排除 air / barrier / bedrock)</li>
 *   <li>选中后回调 block 注册 id 字符串 (e.g. "minecraft:stone"), 「清空」回调 null</li>
 *   <li>关闭时回到 {@link GuiCustomBulldozer} (而不是 Editor)</li>
 * </ul>
 */
public final class GuiBulldozerBlockPicker {

    /** 弹窗根容器像素尺寸 (跟 GuiItemSearchPopup 一致). */
    private static final int W = 220;
    private static final int H = 280;

    /** 候选显示上限. */
    private static final int MAX_RESULTS = 400;
    /** 单行高. */
    private static final int ROW_H = 18;

    private static GuiCustomBulldozer pendingParent = null;
    private static Consumer<String> pendingCallback = null;
    /** 跨 open() 调用的搜索关键字缓存 (跟 GuiItemSearchPopup 同套路). */
    private static String SAVED_SEARCH_QUERY = "";

    private GuiBulldozerBlockPicker() {}

    /**
     * 静态入口: 主推土机 GUI 点「填充方块」时调用, 选完后回调 block id 字符串 (清空 = null),
     * 然后回到 parent.
     */
    public static void open(GuiCustomBulldozer parent, Consumer<String> cb) {
        pendingParent = parent;
        pendingCallback = cb;

        // 缓存所有可填充方块, 启动时构建一次
        List<Item> allItems = new ArrayList<>();
        for (Item it : BuiltInRegistries.ITEM) {
            if (isFillableBlock(it)) allItems.add(it);
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
        title.setText("§l§d选填充方块");
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
        search.setAnyString();
        search.setTextResponder(word -> {
            SAVED_SEARCH_QUERY = word == null ? "" : word;
            rebuildList(allItems, listContainer, word);
        });
        search.layout(l -> l.widthPercent(100).height(16));
        root.addChild(search);

        // === 滚动列表 (候选方块) ===
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
        btnClear.setText("§c清空");
        btnClear.layout(l -> l.width(50).heightPercent(100));
        btnClear.setOnClick(e -> commitWith(null));
        buttonRow.addChild(btnClear);

        Button btnBack = new Button();
        btnBack.setText("§7返回");
        btnBack.layout(l -> l.width(50).heightPercent(100));
        btnBack.setOnClick(e -> returnToParent());
        buttonRow.addChild(btnBack);

        UIElement filler = new UIElement();
        filler.layout(l -> l.flexGrow(1).heightPercent(100));
        buttonRow.addChild(filler);

        TextElement hint = new TextElement();
        hint.setText("§7打字过滤方块");
        hint.textStyle(t -> t.textColor(0xFFAAAAAA));
        hint.layout(l -> l.height(14));
        buttonRow.addChild(hint);

        root.addChild(buttonRow);

        ModularUI ui = ModularUI.of(UI.of(root));
        ModularUIScreen screen = new ModularUIScreen(ui, Component.literal("选填充方块"));

        if (!SAVED_SEARCH_QUERY.isEmpty()) {
            search.setText(SAVED_SEARCH_QUERY);
        }
        rebuildList(allItems, listContainer, SAVED_SEARCH_QUERY);
        Minecraft.getInstance().setScreen(screen);
    }

    /** 只允许真正的可放置方块 (BlockItem), 排除空气 / 屏障 / 基岩等. */
    private static boolean isFillableBlock(Item it) {
        if (!(it instanceof BlockItem bi)) return false;
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(it);
        if (key == null) return false;
        var block = bi.getBlock();
        if (block == null || block == Blocks.AIR) return false;
        String id = key.toString();
        return !id.endsWith(":air")
            && !id.equals("minecraft:barrier")
            && !id.equals("minecraft:bedrock");
    }

    /**
     * 按 word 重新填充候选列表 (前缀匹配优先, 其次子串匹配).
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
                // 搜索同时匹配原始 id (英文) 和翻译后的显示名 (中文), 任一语言都能搜到.
                String id = BuiltInRegistries.ITEM.getKey(it).toString();
                String idLower = id.toLowerCase(Locale.ROOT);
                String nameLower = new ItemStack(it).getHoverName().getString()
                    .toLowerCase(Locale.ROOT);
                if (idLower.startsWith(q) || nameLower.startsWith(q)) {
                    prefix.add(it);
                } else if (idLower.contains(q) || nameLower.contains(q)) {
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
        if (ordered.isEmpty()) {
            TextElement none = new TextElement();
            none.setText("§7未找到匹配的方块");
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

        // 用 ItemStack.getHoverName() 走 vanilla 翻译系统, 显示本地化方块名.
        TextElement label = new TextElement();
        label.setText(new ItemStack(it).getHoverName().getString());
        label.textStyle(t -> t.textColor(0xFFFFFFFF));
        label.layout(l -> l.flexGrow(1).height(14));
        row.addChild(label);

        row.addEventListener(UIEvents.MOUSE_DOWN, e -> commitWith(it));
        return row;
    }

    private static void commitWith(Item item) {
        String blockId = null;
        if (item instanceof BlockItem bi) {
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(bi.getBlock());
            if (key != null) blockId = key.toString();
        }
        Consumer<String> cb = pendingCallback;
        returnToParent();
        if (cb != null) {
            try {
                cb.accept(blockId);
            } catch (Throwable t) {
                PrefabCustomAddon.LOGGER.error("[BULLDOZER-BLOCK-PICKER] 回调失败", t);
            }
        }
    }

    private static void returnToParent() {
        GuiCustomBulldozer parent = pendingParent;
        pendingCallback = null;
        pendingParent = null;
        // 同步切回 parent (跟 GuiItemSearchPopup 一致, 避免跨帧残影).
        if (parent != null) {
            Minecraft.getInstance().setScreen(parent);
        } else {
            Minecraft.getInstance().setScreen(null);
        }
    }
}
