/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.lowdragmc.lowdraglib2.gui.ColorPattern
 *  com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen
 *  com.lowdragmc.lowdraglib2.gui.texture.ColorBorderTexture
 *  com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture
 *  com.lowdragmc.lowdraglib2.gui.texture.GuiTextureGroup
 *  com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture
 *  com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture
 *  com.lowdragmc.lowdraglib2.gui.ui.ModularUI
 *  com.lowdragmc.lowdraglib2.gui.ui.UI
 *  com.lowdragmc.lowdraglib2.gui.ui.UIElement
 *  com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal
 *  com.lowdragmc.lowdraglib2.gui.ui.data.ScrollDisplay
 *  com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode
 *  com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap
 *  com.lowdragmc.lowdraglib2.gui.ui.elements.Button
 *  com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView
 *  com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement
 *  com.lowdragmc.lowdraglib2.gui.ui.style.Stylesheet
 *  com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager
 *  com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites
 *  dev.vfyjxf.taffy.style.AlignContent
 *  dev.vfyjxf.taffy.style.AlignItems
 *  dev.vfyjxf.taffy.style.FlexDirection
 *  dev.vfyjxf.taffy.style.FlexWrap
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.screens.Screen
 *  net.minecraft.client.player.LocalPlayer
 *  net.minecraft.network.chat.Component
 *  net.minecraft.world.entity.player.Inventory
 *  net.minecraft.world.item.Item
 *  net.minecraft.world.item.ItemStack
 *  net.minecraft.world.item.Items
 *  net.minecraft.world.level.ItemLike
 */
package com.prefab.addon.client.gui;

import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollDisplay;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.gui.ui.style.Stylesheet;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.ConstructionInfo;
import com.prefab.addon.network.NetworkHandler;
import com.prefab.addon.network.ResetMaterialLedgerPayload;
import com.prefab.addon.network.SubmitMaterialsPayload;
import com.prefab.addon.work.ChallengeSessionManager;
import com.prefab.addon.work.MaterialCalculator;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;

public final class MaterialSubmissionGui {
    private static final int CARD_W = 96;
    private static final int CARD_H = 124;
    private static final int WINDOW_W = 480;
    private static final int WINDOW_H = 240;
    private static final int CARDS_PER_PAGE = 4;
    /** 当前打开的提交界面 (供服务端账本回包 onServerState 刷新用); 关闭时置 null */
    private static State currentState = null;

    private MaterialSubmissionGui() {
    }

    public static void open(ConstructionInfo construction, MaterialCalculator.MaterialList materialList, boolean challengeMode, LocalPlayer player) {
        State state = new State();
        state.sessionId = construction.getId();
        state.displayName = construction.getName();
        state.materialList = materialList;
        state.challengeMode = challengeMode;
        state.player = player;
        state.playerId = player.getUUID();
        state.rows = MaterialSubmissionGui.computeRows(state);
        MaterialSubmissionGui.currentState = state;
        ModularUI ui = MaterialSubmissionGui.buildUI(state);
        String title = PrefabCustomAddon.tr("gui.material.title", construction.getName());
        Minecraft.getInstance().setScreen((Screen)new ModularUIScreen(ui, (Component)Component.literal((String)title)));
    }

    public static void open(String sessionId, String displayName, MaterialCalculator.MaterialList materialList, LocalPlayer player) {
        State state = new State();
        state.sessionId = sessionId;
        state.displayName = displayName;
        state.materialList = materialList;
        state.challengeMode = true;
        state.player = player;
        state.playerId = player.getUUID();
        state.rows = MaterialSubmissionGui.computeRows(state);
        state.previousScreen = Minecraft.getInstance().screen;
        MaterialSubmissionGui.currentState = state;
        ModularUI ui = MaterialSubmissionGui.buildUI(state);
        String title = PrefabCustomAddon.tr("gui.material.title", displayName);
        Minecraft.getInstance().setScreen((Screen)new ModularUIScreen(ui, (Component)Component.literal((String)title)));
    }

    private static List<MaterialRow> computeRows(State state) {
        ArrayList<MaterialRow> out = new ArrayList<MaterialRow>();
        if (state.materialList == null || state.materialList.required.isEmpty()) {
            return out;
        }
        Inventory inv = state.player.getInventory();
        Map<String, Integer> submitted = ChallengeSessionManager.getSubmitted(state.playerId, state.sessionId);
        int filteredOut = 0;
        for (Map.Entry<String, Integer> e : state.materialList.required.entrySet()) {
            String blockId = e.getKey();
            int required = e.getValue();
            ItemStack icon = MaterialCalculator.getItemStack(blockId);
            if (MaterialSubmissionGui.isPlaceholderIcon(icon)) {
                ++filteredOut;
                PrefabCustomAddon.LOGGER.debug("[SUBMIT-GUI] \u8fc7\u6ee4\u6389\u65e0 Item \u5f62\u5f0f\u7684\u6750\u6599: {} (icon={})", (Object)blockId, (Object)icon.getItem());
                continue;
            }
            int inInv = MaterialCalculator.countInInventory(inv, blockId);
            int alreadySubmitted = submitted.getOrDefault(blockId, 0);
            int remaining = Math.max(0, required - alreadySubmitted);
            out.add(new MaterialRow(blockId, required, alreadySubmitted, inInv, remaining, icon));
        }
        if (filteredOut > 0) {
            PrefabCustomAddon.LOGGER.info("[SUBMIT-GUI] {} \u5171\u8fc7\u6ee4\u6389 {} \u79cd\u65e0 Item \u5f62\u5f0f\u7684\u6750\u6599", (Object)state.sessionId, (Object)filteredOut);
        }
        out.sort(Comparator.comparingInt((MaterialRow r) -> r.remaining == 0 ? 1 : 0).thenComparingInt((MaterialRow r) -> -r.required));
        return out;
    }

    private static boolean isPlaceholderIcon(ItemStack icon) {
        if (icon == null || icon.isEmpty()) {
            return true;
        }
        Item item = icon.getItem();
        return item == Items.BARRIER || item == Items.GRAY_STAINED_GLASS_PANE;
    }

    private static List<MaterialRow> getPageRows(State state) {
        if (state.rows == null) {
            return List.of();
        }
        int total = state.rows.size();
        if (total == 0) {
            return List.of();
        }
        int from = state.currentPage * 4;
        if (from >= total) {
            from = 0;
        }
        int to = Math.min(from + 4, total);
        return state.rows.subList(from, to);
    }

    private static int getTotalPages(State state) {
        int total;
        int n = total = state.rows == null ? 0 : state.rows.size();
        if (total == 0) {
            return 1;
        }
        return (total + 4 - 1) / 4;
    }

    private static void refreshAll(State state) {
        state.rows = MaterialSubmissionGui.computeRows(state);
        int totalPages = MaterialSubmissionGui.getTotalPages(state);
        if (state.currentPage >= totalPages) {
            state.currentPage = totalPages - 1;
        }
        if (state.currentPage < 0) {
            state.currentPage = 0;
        }
        MaterialSubmissionGui.refreshCardsOnly(state);
        MaterialSubmissionGui.updateProgressEl(state);
        MaterialSubmissionGui.updatePageInfoEl(state);
    }

    private static void refreshCardsOnly(State state) {
        if (state.cardContainer == null) {
            return;
        }
        state.cardContainer.clearAllChildren();
        List<MaterialRow> pageRows = MaterialSubmissionGui.getPageRows(state);
        if (pageRows.isEmpty()) {
            TextElement empty = new TextElement();
            empty.setText(PrefabCustomAddon.tr("gui.material.empty", new Object[0]));
            empty.textStyle(t -> t.textColor(-7829368).textAlignHorizontal(Horizontal.CENTER));
            empty.layout(l -> l.widthPercent(100.0f).height(60.0f).justifyContent(AlignContent.CENTER));
            state.cardContainer.addChild((UIElement)empty);
        } else {
            for (MaterialRow r : pageRows) {
                state.cardContainer.addChild(MaterialSubmissionGui.createCard(state, r));
            }
        }
    }

    private static void updatePageInfoEl(State state) {
        if (state.pageInfoEl == null) {
            return;
        }
        int totalPages = MaterialSubmissionGui.getTotalPages(state);
        int totalCards = state.rows == null ? 0 : state.rows.size();
        int from = state.currentPage * 4 + 1;
        int to = Math.min((state.currentPage + 1) * 4, totalCards);
        String text = totalCards == 0 ? PrefabCustomAddon.tr("gui.material.page_info_empty", new Object[0]) : String.format(Locale.ROOT, PrefabCustomAddon.tr("gui.material.page_info", new Object[0]), state.currentPage + 1, totalPages, from, to, totalCards);
        state.pageInfoEl.setText((Component)Component.literal((String)text));
    }

    private static void updateProgressEl(State state) {
        if (state.progressEl == null) {
            return;
        }
        int totalRequired = state.rows.stream().mapToInt(r -> r.required).sum();
        int totalSubmitted = state.rows.stream().mapToInt(r -> r.alreadySubmitted).sum();
        int totalRemaining = state.rows.stream().mapToInt(r -> r.remaining).sum();
        int totalTypes = state.rows.size();
        int typesComplete = (int)state.rows.stream().filter(r -> r.remaining == 0).count();
        String suffix = totalRemaining == 0 ? PrefabCustomAddon.tr("gui.material.progress_done", new Object[0]) : String.format(Locale.ROOT, PrefabCustomAddon.tr("gui.material.progress_remain", new Object[0]), totalRemaining);
        String line = String.format(Locale.ROOT, PrefabCustomAddon.tr("gui.material.progress_line", new Object[0]), totalSubmitted, totalRequired, typesComplete, totalTypes, suffix);
        int color = totalRemaining == 0 ? -11141291 : -21931;
        state.progressEl.setText((Component)Component.literal((String)line));
        state.progressEl.textStyle(t -> t.textColor(color));
    }

    private static UIElement createCard(State state, MaterialRow row) {
        UIElement card = new UIElement();
        card.layout(l -> l.flexDirection(FlexDirection.COLUMN).width(96.0f).height(124.0f).paddingAll(2.0f).gapAll(0.0f).alignItems(AlignItems.CENTER));
        int bgColor = row.remaining == 0 ? -1073728768 : -1071241690;
        card.style(s -> s.background((IGuiTexture)new GuiTextureGroup(new IGuiTexture[]{new ColorRectTexture(bgColor), new ColorBorderTexture(1, -11184811)})));
        UIElement iconArea = new UIElement();
        // 图标区必须是正方形: ItemStackTexture 按 scale(width/16, height/16) 独立拉伸 xy,
        // 旧的 92x36 扁矩形会把 16x16 物品渲染拉成扁板. 64x64 = 整数 4 倍缩放, 不变形且像素清晰
        iconArea.layout(l -> l.width(64.0f).height(64.0f).alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER));
        ItemStack displayIcon = MaterialSubmissionGui.isFallbackIcon(row.icon) ? new ItemStack((ItemLike)Items.GRAY_STAINED_GLASS_PANE) : row.icon;
        iconArea.style(s -> s.background((IGuiTexture)new ItemStackTexture(new ItemStack[]{displayIcon})));
        card.addChild(iconArea);
        String displayName = MaterialSubmissionGui.resolveDisplayName(row);
        TextElement nameEl = new TextElement();
        nameEl.setText((Component)Component.literal((String)displayName));
        int nameColor = MaterialSubmissionGui.isFallbackIcon(row.icon) ? -21931 : (row.remaining == 0 ? -5570646 : -1);
        nameEl.textStyle(t -> t.textColor(nameColor).textAlignHorizontal(Horizontal.CENTER).textWrap(TextWrap.WRAP));
        nameEl.layout(l -> l.width(92.0f).height(20.0f));
        card.addChild((UIElement)nameEl);
        String progText = row.alreadySubmitted + " / " + row.required;
        TextElement progEl = new TextElement();
        progEl.setText((Component)Component.literal((String)progText));
        int progColor = row.remaining == 0 ? -11141291 : (row.inInv > 0 ? -21931 : -43691);
        progEl.textStyle(t -> t.textColor(progColor).textAlignHorizontal(Horizontal.CENTER));
        progEl.layout(l -> l.width(92.0f).height(11.0f));
        card.addChild((UIElement)progEl);
        UIElement bottom = new UIElement();
        bottom.layout(l -> l.flexDirection(FlexDirection.ROW).width(92.0f).height(18.0f).alignItems(AlignItems.CENTER).gapAll(2.0f));
        Button submitBtn = new Button();
        submitBtn.setText(PrefabCustomAddon.tr("gui.material.submit_btn", new Object[0]));
        submitBtn.textStyle(t -> t.textColor(-11141291).textAlignHorizontal(Horizontal.CENTER));
        submitBtn.layout(l -> l.width(28.0f).height(16.0f));
        submitBtn.setOnClick(e -> MaterialSubmissionGui.onSubmitSingle(state, row));
        bottom.addChild((UIElement)submitBtn);
        String remainText = row.remaining == 0 ? PrefabCustomAddon.tr("gui.material.remain_done_short", new Object[0]) : String.format(Locale.ROOT, PrefabCustomAddon.tr("gui.material.remain_short", new Object[0]), row.remaining);
        TextElement remainEl = new TextElement();
        remainEl.setText((Component)Component.literal((String)remainText));
        remainEl.textStyle(t -> t.textColor(row.remaining == 0 ? -11141291 : -21931).textAlignHorizontal(Horizontal.RIGHT));
        remainEl.layout(l -> l.flexGrow(1.0f).height(16.0f));
        bottom.addChild((UIElement)remainEl);
        card.addChild(bottom);
        return card;
    }

    private static void onSubmitSingle(State state, MaterialRow row) {
        Map<String, Integer> invCounts = MaterialSubmissionGui.countSingleInInventory(state.player, row.blockId);
        int toSubmit = Math.min(invCounts.getOrDefault(row.blockId, 0), row.remaining);
        if (toSubmit <= 0) {
            MaterialSubmissionGui.setStatus(state, String.format(Locale.ROOT, PrefabCustomAddon.tr("gui.material.no_inventory_single", new Object[0]), MaterialSubmissionGui.resolveDisplayName(row)), 0xFF5555, 60);
            return;
        }
        // 发服务端扣除 (服务端权威, 客户端不再改背包/session): required 传该材料的**总需求**, 服务端按自己账本算还差多少;
        // fullRequired 传完整需求表, 服务端判 allDone 时不会因只交了一种就误报"全部已交齐"
        HashMap<String, Integer> required = new HashMap<String, Integer>();
        required.put(row.blockId, row.required);
        Map<String, Integer> fullRequired = state.materialList == null ? required : state.materialList.required;
        NetworkHandler.sendToServer(new SubmitMaterialsPayload(state.sessionId, required, fullRequired));
        MaterialSubmissionGui.setStatus(state, String.format(Locale.ROOT, PrefabCustomAddon.tr("gui.material.submitted_single", new Object[0]), toSubmit, MaterialSubmissionGui.resolveDisplayName(row)), -11141291, 60);
        PrefabCustomAddon.LOGGER.info("[SUBMIT-GUI] \u73a9\u5bb6 {} \u8bf7\u6c42\u63d0\u4ea4\u5efa\u7b51 {} \u7684\u6750\u6599 {} (\u670d\u52a1\u7aef\u6263\u9664)", new Object[]{state.playerId, state.sessionId, row.blockId});
    }

    private static void onSubmitAll(State state) {
        if (state.materialList == null || state.materialList.required.isEmpty()) {
            MaterialSubmissionGui.setStatus(state, PrefabCustomAddon.tr("gui.material.nothing_to_submit", new Object[0]), 0xFF5555, 80);
            return;
        }
        // 全部提交: 完整需求表发服务端, 服务端逐项按 min(背包实有, 还差) 扣除后回快照 (fullRequired 与 required 同表)
        Map<String, Integer> full = state.materialList.required;
        NetworkHandler.sendToServer(new SubmitMaterialsPayload(state.sessionId, full, full));
        MaterialSubmissionGui.setStatus(state, "\u00a7e\u63d0\u4ea4\u4e2d...", 0xFFAA00, 40);
    }

    private static void onReset(State state) {
        // 请求服务端清账本 (服务端回空快照 → onServerState 清客户端镜像并刷新)
        NetworkHandler.sendToServer(new ResetMaterialLedgerPayload(state.sessionId));
        MaterialSubmissionGui.setStatus(state, PrefabCustomAddon.tr("gui.material.reset_done", new Object[0]), -21931, 60);
        PrefabCustomAddon.LOGGER.info("[SUBMIT-GUI] \u73a9\u5bb6 {} \u8bf7\u6c42\u91cd\u7f6e\u5efa\u7b51 {} \u7684\u63d0\u4ea4\u8fdb\u5ea6 (\u670d\u52a1\u7aef)", (Object)state.playerId, (Object)state.sessionId);
    }

    /**
     * 服务端账本快照回来时调用 (MaterialSubmitResultPayload.handle → 这里).
     * ChallengeSessionManager.applyServerState 已把镜像 session 替换成服务端快照,
     * 这里只需刷新 UI (若当前正开着这个 session 的提交界面).
     */
    public static void onServerState(String sessionId, Map<String, Integer> submittedAfter, boolean allDone) {
        State state = MaterialSubmissionGui.currentState;
        if (state == null || state.sessionId == null || !state.sessionId.equals(sessionId)) {
            return;
        }
        MaterialSubmissionGui.refreshAll(state);
        if (allDone) {
            MaterialSubmissionGui.setStatus(state, PrefabCustomAddon.tr("gui.material.all_done", new Object[0]), 0x55FF55, 100);
        }
    }

    private static void setStatus(State state, String msg, int color, int ticks) {
        state.statusMessage = msg;
        state.statusColor = color;
        state.statusExpireTick = Minecraft.getInstance().level.getGameTime() + (long)ticks;
        if (state.statusEl != null) {
            state.statusEl.setText((Component)Component.literal((String)msg));
            state.statusEl.textStyle(t -> t.textColor(color));
        }
    }

    private static ModularUI buildUI(State state) {
        UIElement root = new UIElement();
        root.layout(l -> l.width(480.0f).height(240.0f).flexDirection(FlexDirection.COLUMN).paddingAll(4.0f).gapAll(3.0f));
        root.style(s -> s.background(Sprites.BORDER));
        root.setOverflowVisible(false);
        TextElement titleEl = new TextElement();
        titleEl.setText(PrefabCustomAddon.tr("gui.material.title", state.displayName));
        titleEl.textStyle(t -> t.textColor(-11141121).textAlignHorizontal(Horizontal.CENTER));
        titleEl.layout(l -> l.widthPercent(100.0f).height(14.0f));
        root.addChild((UIElement)titleEl);
        state.progressEl = new TextElement();
        MaterialSubmissionGui.updateProgressEl(state);
        state.progressEl.layout(l -> l.widthPercent(100.0f).height(11.0f));
        root.addChild((UIElement)state.progressEl);
        ScrollerView scroller = new ScrollerView();
        scroller.scrollerStyle(s -> s.mode(ScrollerMode.VERTICAL).horizontalScrollDisplay(ScrollDisplay.NEVER).verticalScrollDisplay(ScrollDisplay.ALWAYS).minScrollPixel(4.0f).maxScrollPixel(40.0f));
        scroller.verticalScroller(v -> v.setScrollBarSize(3.0f));
        scroller.viewPort(vp -> vp.style(s -> s.backgroundTexture((IGuiTexture)ColorPattern.SEAL_BLACK.rectTexture()).overlay((IGuiTexture)IGuiTexture.EMPTY)).layout(l -> l.paddingAll(2.0f)));
        scroller.layout(l -> l.widthPercent(100.0f).flexGrow(1.0f));
        root.addChild((UIElement)scroller);
        state.cardContainer = new UIElement();
        state.cardContainer.layout(l -> l.flexDirection(FlexDirection.ROW).flexWrap(FlexWrap.WRAP).alignContent(AlignContent.FLEX_START).gapAll(4.0f).paddingLeft(10.0f).paddingRight(6.0f).paddingTop(2.0f).paddingBottom(2.0f).widthPercent(100.0f).flexShrink(0.0f).minHeight(0.0f));
        state.cardContainer.setId("__cards__");
        scroller.addScrollViewChild(state.cardContainer);
        MaterialSubmissionGui.refreshCardsOnly(state);
        UIElement pageRow = new UIElement();
        pageRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f).height(18.0f).gapAll(4.0f).alignItems(AlignItems.CENTER));
        Button btnPrev = new Button();
        btnPrev.setText(PrefabCustomAddon.tr("gui.material.prev_short", new Object[0]));
        btnPrev.textStyle(t -> t.textColor(-1));
        btnPrev.layout(l -> l.width(76.0f).heightPercent(100.0f));
        btnPrev.setOnClick(e -> {
            if (state.currentPage > 0) {
                --state.currentPage;
                MaterialSubmissionGui.refreshCardsOnly(state);
                MaterialSubmissionGui.updatePageInfoEl(state);
            }
        });
        pageRow.addChild((UIElement)btnPrev);
        UIElement pageSpacer = new UIElement();
        pageSpacer.layout(l -> l.flexGrow(1.0f).heightPercent(100.0f));
        pageRow.addChild(pageSpacer);
        state.pageInfoEl = new TextElement();
        MaterialSubmissionGui.updatePageInfoEl(state);
        state.pageInfoEl.textStyle(t -> t.textColor(-5592406).textAlignHorizontal(Horizontal.CENTER));
        state.pageInfoEl.layout(l -> l.heightPercent(100.0f));
        pageRow.addChild((UIElement)state.pageInfoEl);
        UIElement pageSpacer2 = new UIElement();
        pageSpacer2.layout(l -> l.flexGrow(1.0f).heightPercent(100.0f));
        pageRow.addChild(pageSpacer2);
        Button btnNext = new Button();
        btnNext.setText(PrefabCustomAddon.tr("gui.material.next", new Object[0]));
        btnNext.textStyle(t -> t.textColor(-1));
        btnNext.layout(l -> l.width(76.0f).heightPercent(100.0f));
        btnNext.setOnClick(e -> {
            int totalPages = MaterialSubmissionGui.getTotalPages(state);
            if (state.currentPage < totalPages - 1) {
                ++state.currentPage;
                MaterialSubmissionGui.refreshCardsOnly(state);
                MaterialSubmissionGui.updatePageInfoEl(state);
            }
        });
        pageRow.addChild((UIElement)btnNext);
        root.addChild(pageRow);
        state.statusEl = new TextElement();
        state.statusEl.setText("");
        state.statusEl.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        state.statusEl.layout(l -> l.widthPercent(100.0f).height(11.0f));
        root.addChild((UIElement)state.statusEl);
        UIElement btnRow = new UIElement();
        btnRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f).height(20.0f).gapAll(4.0f).alignItems(AlignItems.CENTER));
        Button btnSubmitAll = new Button();
        btnSubmitAll.setText(PrefabCustomAddon.tr("gui.material.submit_all", new Object[0]));
        btnSubmitAll.textStyle(t -> t.textColor(-11141291));
        btnSubmitAll.layout(l -> l.width(110.0f).heightPercent(100.0f));
        btnSubmitAll.setOnClick(e -> MaterialSubmissionGui.onSubmitAll(state));
        btnRow.addChild((UIElement)btnSubmitAll);
        Button btnReset = new Button();
        btnReset.setText(PrefabCustomAddon.tr("gui.material.reset_short", new Object[0]));
        btnReset.textStyle(t -> t.textColor(-21931));
        btnReset.layout(l -> l.width(80.0f).heightPercent(100.0f));
        btnReset.setOnClick(e -> MaterialSubmissionGui.onReset(state));
        btnRow.addChild((UIElement)btnReset);
        UIElement spacer = new UIElement();
        spacer.layout(l -> l.flexGrow(1.0f).heightPercent(100.0f));
        btnRow.addChild(spacer);
        Button btnClose = new Button();
        btnClose.setText(PrefabCustomAddon.tr("gui.material.close_short", new Object[0]));
        btnClose.layout(l -> l.width(80.0f).heightPercent(100.0f));
        btnClose.setOnClick(e -> {
            MaterialSubmissionGui.currentState = null;
            Screen prev = state.previousScreen;
            if (prev != null) {
                Minecraft.getInstance().setScreen(prev);
            } else {
                Minecraft.getInstance().setScreen(null);
            }
        });
        btnRow.addChild((UIElement)btnClose);
        root.addChild(btnRow);
        return ModularUI.of((UI)UI.of((UIElement)root, (Stylesheet[])new Stylesheet[]{StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)}));
    }

    private static String resolveDisplayName(MaterialRow row) {
        String modHint;
        if (MaterialSubmissionGui.isFallbackIcon(row.icon)) {
            return row.blockId;
        }
        String localized = null;
        try {
            localized = row.icon.getHoverName().getString();
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        if (localized == null || localized.isEmpty() || localized.startsWith("item.") || localized.startsWith("block.")) {
            int colonIdx = row.blockId.indexOf(58);
            if (colonIdx > 0) {
                String ns = row.blockId.substring(0, colonIdx);
                String path = row.blockId.substring(colonIdx + 1);
                String abbrNs = ns.length() > 1 ? ns.substring(0, 1) + ":" : ns + ":";
                return abbrNs + path;
            }
            return row.blockId;
        }
        if (!MaterialSubmissionGui.hasChineseCharacters(localized) && (modHint = MaterialSubmissionGui.getModIdFromBlockId(row.blockId)) != null) {
            return localized + " [" + modHint + "]";
        }
        return localized;
    }

    private static boolean isFallbackIcon(ItemStack icon) {
        if (icon.isEmpty()) {
            return true;
        }
        if (icon.getItem() == Items.GRAY_STAINED_GLASS_PANE) {
            return true;
        }
        return icon.getItem() == Items.BARRIER;
    }

    private static boolean hasChineseCharacters(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); ++i) {
            char c = s.charAt(i);
            if (c < '\u4e00' || c > '\u9fff') continue;
            return true;
        }
        return false;
    }

    private static String getModIdFromBlockId(String blockId) {
        if (blockId == null) {
            return null;
        }
        int colon = blockId.indexOf(58);
        if (colon < 0) {
            return null;
        }
        String ns = blockId.substring(0, colon);
        String key = "gui.material.mod." + ns;
        String translated = PrefabCustomAddon.tr(key, new Object[0]);
        if (translated == null || translated.isEmpty() || translated.equals(key)) {
            return ns;
        }
        return translated;
    }

    private static Map<String, Integer> countSingleInInventory(LocalPlayer player, String blockId) {
        HashMap<String, Integer> out = new HashMap<String, Integer>();
        int count = MaterialCalculator.countInInventory(player.getInventory(), blockId);
        out.put(blockId, count);
        return out;
    }

    // [已移除] deductFromInventory: 旧的客户端扣背包逻辑 (刷物品 bug 根源).
    // 现在材料扣除全部走服务端 ServerMaterialLedger, 客户端只发 SubmitMaterialsPayload.

    private static final class State {
        String sessionId;
        String displayName;
        MaterialCalculator.MaterialList materialList;
        boolean challengeMode;
        LocalPlayer player;
        UUID playerId;
        List<MaterialRow> rows;
        Screen previousScreen;
        int currentPage = 0;
        String statusMessage = null;
        int statusColor = 0xFFFFFF;
        long statusExpireTick = 0L;
        TextElement statusEl;
        TextElement progressEl;
        TextElement pageInfoEl;
        UIElement cardContainer;

        private State() {
        }
    }

    private static final class MaterialRow {
        final String blockId;
        final int required;
        int alreadySubmitted;
        int inInv;
        int remaining;
        final ItemStack icon;

        MaterialRow(String blockId, int required, int alreadySubmitted, int inInv, int remaining, ItemStack icon) {
            this.blockId = blockId;
            this.required = required;
            this.alreadySubmitted = alreadySubmitted;
            this.inInv = inInv;
            this.remaining = remaining;
            this.icon = icon;
        }
    }
}

