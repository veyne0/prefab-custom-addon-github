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
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollDisplay;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.ConstructionInfo;
import com.prefab.addon.work.ChallengeSessionManager;
import com.prefab.addon.work.MaterialCalculator;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 材料提交 GUI (LDLib2 实现, 卡片式布局) - 挑战模式专用.
 *
 * <p>设计: 每种材料一张卡片, 横排展示, 卡片内容:
 * <pre>
 * ┌─────────────────┐
 * │      图标        │   顶部: ItemStackTexture (16x16 居中放大到 32x32)
 * │    (草方块)      │   中部: 翻译后的中文名 (fallback: ns:path + mod 缩写)
 * │ ─────────────── │
 * │ [提交] 还差 629  │   底部: 左 - 提交该材料按钮; 右 - 还差 X 个
 * └─────────────────┘
 * </pre>
 *
 * <p>底部全局操作: [✓ 全部提交]  [↺ 重置]  [✗ 关闭]
 *
 * <p>UI 风格: LDLib2 + ModularUI, 跟 Z 键拓展包管理界面一致.
 */
public final class MaterialSubmissionGui {

    private static final int CARD_W = 96;
    private static final int CARD_H = 124;
    private static final int WINDOW_W = 480;
    // 关键: 窗口高度必须能在默认 GUI scale (scale 3, 240px 屏幕高度) 下完整显示
    private static final int WINDOW_H = 240;
    // 每页卡片数: 1 行 × 4 列 (受限于 WINDOW_H=240 只能放 1 行)
    private static final int CARDS_PER_PAGE = 4;

    private MaterialSubmissionGui() {}

    public static void open(ConstructionInfo construction,
                            MaterialCalculator.MaterialList materialList,
                            boolean challengeMode,
                            net.minecraft.client.player.LocalPlayer player) {
        // 用 static holder 跨回调共享状态 (LDLib2 UI 是 function-style, 没 this)
        State state = new State();
        state.construction = construction;
        state.materialList = materialList;
        state.challengeMode = challengeMode;
        state.player = player;
        state.playerId = player.getUUID();
        state.rows = computeRows(state);

        ModularUI ui = buildUI(state);
        String title = com.prefab.addon.PrefabCustomAddon.tr("gui.material.title", construction.getName());
        Minecraft.getInstance().setScreen(new ModularUIScreen(ui, Component.literal(title)));
    }

    // === 跨回调共享状态 ===
    private static final class State {
        ConstructionInfo construction;
        MaterialCalculator.MaterialList materialList;
        boolean challengeMode;
        net.minecraft.client.player.LocalPlayer player;
        UUID playerId;
        List<MaterialRow> rows;

        // 打开这个界面之前的屏幕 (一般是 CustomStructureGui), 关闭按钮用来返回
        net.minecraft.client.gui.screens.Screen previousScreen;

        // 分页 (每页 CARDS_PER_PAGE 张卡片, ◀ ▶ 切换)
        int currentPage = 0;

        // 状态消息 (提交/重置后短暂显示)
        String statusMessage = null;
        int statusColor = 0xFFFFFF;
        long statusExpireTick = 0;

        // 引用
        TextElement statusEl;
        TextElement progressEl;
        TextElement pageInfoEl;
        UIElement cardContainer;  // ScrollerView 的 viewContainer, 用来 refreshAll
    }

    private static final class MaterialRow {
        final String blockId;
        final int required;
        int alreadySubmitted;
        int inInv;
        int remaining;
        final ItemStack icon;
        MaterialRow(String blockId, int required, int alreadySubmitted, int inInv,
                    int remaining, ItemStack icon) {
            this.blockId = blockId;
            this.required = required;
            this.alreadySubmitted = alreadySubmitted;
            this.inInv = inInv;
            this.remaining = remaining;
            this.icon = icon;
        }
    }

    // === 行计算 (跟 prefab GuiBase 版逻辑一致) ===
    // 过滤掉: 该 blockId 在游戏中根本不存在 (没有 Item 形式) 的材料
    // - getItemStack 返回 BARRIER: Block 注册表都查不到 (NBT 损坏/格式不对)
    // - getItemStack 返回 GRAY_STAINED_GLASS_PANE: Block 存在但没 Item 形式 (玩家没法放进背包提交)
    // 这两种都是"无法提交"的, 不应该出现在挑战模式界面里
    private static List<MaterialRow> computeRows(State state) {
        List<MaterialRow> out = new ArrayList<>();
        if (state.materialList == null || state.materialList.required.isEmpty()) return out;
        Inventory inv = state.player.getInventory();
        Map<String, Integer> submitted = ChallengeSessionManager.getSubmitted(
            state.playerId, state.construction.getId());
        int filteredOut = 0;
        for (Map.Entry<String, Integer> e : state.materialList.required.entrySet()) {
            String blockId = e.getKey();
            int required = e.getValue();
            ItemStack icon = MaterialCalculator.getItemStack(blockId);
            // 跳过"无法提交"的材料: BARRIER 或 GRAY_STAINED_GLASS_PANE 占位
            if (isPlaceholderIcon(icon)) {
                filteredOut++;
                PrefabCustomAddon.LOGGER.debug("[SUBMIT-GUI] 过滤掉无 Item 形式的材料: {} (icon={})",
                    blockId, icon.getItem());
                continue;
            }
            int inInv = MaterialCalculator.countInInventory(inv, blockId);
            int alreadySubmitted = submitted.getOrDefault(blockId, 0);
            int remaining = Math.max(0, required - alreadySubmitted);
            out.add(new MaterialRow(blockId, required, alreadySubmitted, inInv, remaining, icon));
        }
        if (filteredOut > 0) {
            PrefabCustomAddon.LOGGER.info("[SUBMIT-GUI] {} 共过滤掉 {} 种无 Item 形式的材料",
                state.construction.getId(), filteredOut);
        }
        out.sort(Comparator.comparingInt((MaterialRow r) -> r.remaining == 0 ? 1 : 0)
            .thenComparingInt(r -> -r.required));
        return out;
    }

    /** 是否是"无 Item 形式"的占位符图标 (BARRIER/GRAY_STAINED_GLASS_PANE). */
    private static boolean isPlaceholderIcon(ItemStack icon) {
        if (icon == null || icon.isEmpty()) return true;
        var item = icon.getItem();
        return item == Items.BARRIER || item == Items.GRAY_STAINED_GLASS_PANE;
    }

    // === 当前页的卡片 (按 currentPage 切片) ===
    private static List<MaterialRow> getPageRows(State state) {
        if (state.rows == null) return List.of();
        int total = state.rows.size();
        if (total == 0) return List.of();
        int from = state.currentPage * CARDS_PER_PAGE;
        if (from >= total) from = 0;
        int to = Math.min(from + CARDS_PER_PAGE, total);
        return state.rows.subList(from, to);
    }

    private static int getTotalPages(State state) {
        int total = state.rows == null ? 0 : state.rows.size();
        if (total == 0) return 1;
        return (total + CARDS_PER_PAGE - 1) / CARDS_PER_PAGE;
    }

    // === 重建 (提交/重置/翻页后调用) ===
    private static void refreshAll(State state) {
        state.rows = computeRows(state);
        // 防止 currentPage 越界 (e.g. 提交完一组后 rows 数量变化)
        int totalPages = getTotalPages(state);
        if (state.currentPage >= totalPages) state.currentPage = totalPages - 1;
        if (state.currentPage < 0) state.currentPage = 0;
        refreshCardsOnly(state);
        updateProgressEl(state);
        updatePageInfoEl(state);
    }

    // === 只刷新卡片 (翻页时用, rows 不用重算) ===
    private static void refreshCardsOnly(State state) {
        if (state.cardContainer == null) return;
        state.cardContainer.clearAllChildren();
        List<MaterialRow> pageRows = getPageRows(state);
        if (pageRows.isEmpty()) {
            TextElement empty = new TextElement();
            empty.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.material.empty"));
            empty.textStyle(t -> t.textColor(0xFF888888).textAlignHorizontal(Horizontal.CENTER));
            empty.layout(l -> l.widthPercent(100).height(60).justifyContent(AlignContent.CENTER));
            state.cardContainer.addChild(empty);
        } else {
            for (MaterialRow r : pageRows) {
                state.cardContainer.addChild(createCard(state, r));
            }
        }
    }

    private static void updatePageInfoEl(State state) {
        if (state.pageInfoEl == null) return;
        int totalPages = getTotalPages(state);
        int totalCards = state.rows == null ? 0 : state.rows.size();
        int from = state.currentPage * CARDS_PER_PAGE + 1;
        int to = Math.min((state.currentPage + 1) * CARDS_PER_PAGE, totalCards);
        String text;
        if (totalCards == 0) {
            text = com.prefab.addon.PrefabCustomAddon.tr("gui.material.page_info_empty");
        } else {
            text = String.format(java.util.Locale.ROOT,
                com.prefab.addon.PrefabCustomAddon.tr("gui.material.page_info"),
                state.currentPage + 1, totalPages, from, to, totalCards);
        }
        state.pageInfoEl.setText(Component.literal(text));
    }

    private static void updateProgressEl(State state) {
        if (state.progressEl == null) return;
        int totalRequired = state.rows.stream().mapToInt(r -> r.required).sum();
        int totalSubmitted = state.rows.stream().mapToInt(r -> r.alreadySubmitted).sum();
        int totalRemaining = state.rows.stream().mapToInt(r -> r.remaining).sum();
        int totalTypes = state.rows.size();
        int typesComplete = (int) state.rows.stream().filter(r -> r.remaining == 0).count();
        String suffix = totalRemaining == 0
            ? com.prefab.addon.PrefabCustomAddon.tr("gui.material.progress_done")
            : String.format(java.util.Locale.ROOT,
                com.prefab.addon.PrefabCustomAddon.tr("gui.material.progress_remain"),
                totalRemaining);
        String line = String.format(java.util.Locale.ROOT,
            com.prefab.addon.PrefabCustomAddon.tr("gui.material.progress_line"),
            totalSubmitted, totalRequired, typesComplete, totalTypes, suffix);
        int color = totalRemaining == 0 ? 0xFF55FF55 : 0xFFFFAA55;
        state.progressEl.setText(Component.literal(line));
        state.progressEl.textStyle(t -> t.textColor(color));
    }

    // === 卡片创建 ===
    private static UIElement createCard(State state, MaterialRow row) {
        UIElement card = new UIElement();
        card.layout(l -> l
            .flexDirection(FlexDirection.COLUMN)
            .width(CARD_W).height(CARD_H)
            .paddingAll(2).gapAll(0)
            .alignItems(AlignItems.CENTER)
        );
        // 卡片底色: 已交齐=绿底, 未交齐=深灰底 (用 GuiTextureGroup 叠加底色 + 边框)
        int bgColor = row.remaining == 0 ? 0xC0003300 : 0xC0262626;
        card.style(s -> s.background(new GuiTextureGroup(
            new ColorRectTexture(bgColor),
            new ColorBorderTexture(1, 0xFF555555)
        )));

        // === 1. 图标区 ===
        UIElement iconArea = new UIElement();
        iconArea.layout(l -> l.width(CARD_W - 4).height(36).alignItems(AlignItems.CENTER)
            .justifyContent(AlignContent.CENTER));
        ItemStack displayIcon = isFallbackIcon(row.icon)
            ? new ItemStack(Items.GRAY_STAINED_GLASS_PANE) : row.icon;
        iconArea.style(s -> s.background(new ItemStackTexture(displayIcon)));
        card.addChild(iconArea);

        // === 2. 中文名 (优先用 ItemStack.getHoverName, fallback 到 ns:path) ===
        String displayName = resolveDisplayName(row);
        TextElement nameEl = new TextElement();
        nameEl.setText(Component.literal(displayName));
        int nameColor = isFallbackIcon(row.icon) ? 0xFFFFAA55
            : (row.remaining == 0 ? 0xFFAAFFAA : 0xFFFFFFFF);
        nameEl.textStyle(t -> t
            .textColor(nameColor)
            .textAlignHorizontal(Horizontal.CENTER)
            .textWrap(TextWrap.WRAP));
        nameEl.layout(l -> l.width(CARD_W - 4).height(20));
        card.addChild(nameEl);

        // === 3. 进度小字 (X / Y) ===
        String progText = row.alreadySubmitted + " / " + row.required;
        TextElement progEl = new TextElement();
        progEl.setText(Component.literal(progText));
        int progColor = row.remaining == 0 ? 0xFF55FF55 : (row.inInv > 0 ? 0xFFFFAA55 : 0xFFFF5555);
        progEl.textStyle(t -> t.textColor(progColor).textAlignHorizontal(Horizontal.CENTER));
        progEl.layout(l -> l.width(CARD_W - 4).height(11));
        card.addChild(progEl);

        // === 4. 底部: 提交按钮 (左) + 还差X (右) ===
        UIElement bottom = new UIElement();
        bottom.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .width(CARD_W - 4).height(18)
            .alignItems(AlignItems.CENTER)
            .gapAll(2)
        );
        // 提交按钮 (左) - 永远可点, 没材料时 onSubmitSingle 显示提示
        Button submitBtn = new Button();
        submitBtn.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.material.submit_btn"));
        submitBtn.textStyle(t -> t.textColor(0xFF55FF55).textAlignHorizontal(Horizontal.CENTER));
        submitBtn.layout(l -> l.width(28).height(16));
        submitBtn.setOnClick(e -> onSubmitSingle(state, row));
        bottom.addChild(submitBtn);

        // 还差X (右, 弹性占满)
        String remainText = row.remaining == 0
            ? com.prefab.addon.PrefabCustomAddon.tr("gui.material.remain_done_short")
            : String.format(java.util.Locale.ROOT,
                com.prefab.addon.PrefabCustomAddon.tr("gui.material.remain_short"),
                row.remaining);
        TextElement remainEl = new TextElement();
        remainEl.setText(Component.literal(remainText));
        remainEl.textStyle(t -> t
            .textColor(row.remaining == 0 ? 0xFF55FF55 : 0xFFFFAA55)
            .textAlignHorizontal(Horizontal.RIGHT));
        remainEl.layout(l -> l.flexGrow(1).height(16));
        bottom.addChild(remainEl);
        card.addChild(bottom);

        return card;
    }

    // === 单卡片提交 (用单种材料映射) ===
    private static void onSubmitSingle(State state, MaterialRow row) {
        // 单种材料提交: 用 required 临时构造 MaterialList, 但只对 row.blockId 提交
        // ChallengeSessionManager.submit() 会按 "背包里有" 的全量扣, 我们需要限到 row.blockId
        // 最简单: 在背包里只对这种材料计数后扣, 然后写回 session
        Map<String, Integer> required = new java.util.HashMap<>();
        required.put(row.blockId, row.required);
        Map<String, Integer> invCounts = countSingleInInventory(state.player, row.blockId);
        int toSubmit = Math.min(invCounts.getOrDefault(row.blockId, 0), row.remaining);
        if (toSubmit <= 0) {
            setStatus(state, String.format(java.util.Locale.ROOT,
                com.prefab.addon.PrefabCustomAddon.tr("gui.material.no_inventory_single"),
                resolveDisplayName(row)), 0xFF5555, 60);
            return;
        }
        // 真正扣除: 直接调 ChallengeSessionManager 的内部逻辑 — 走 submit() 但 single-key
        // 我们自己实现: 修改 session 然后从背包扣
        var session = ChallengeSessionManager.getSubmittedAsMutable(state.playerId, state.construction.getId());
        int oldSub = session.getOrDefault(row.blockId, 0);
        session.put(row.blockId, oldSub + toSubmit);
        // 物理扣背包
        deductFromInventory(state.player, row.blockId, toSubmit);
        // 持久化
        ChallengeSessionManager.saveToDisk(state.playerId);
        setStatus(state, String.format(java.util.Locale.ROOT,
            com.prefab.addon.PrefabCustomAddon.tr("gui.material.submitted_single"),
            toSubmit, resolveDisplayName(row)), 0xFF55FF55, 60);
        PrefabCustomAddon.LOGGER.info("[SUBMIT-GUI] 玩家 {} 提交建筑 {} 的材料 {} x{}",
            state.playerId, state.construction.getId(), row.blockId, toSubmit);
        refreshAll(state);
    }

    // === 全部提交 ===
    private static void onSubmitAll(State state) {
        var r = ChallengeSessionManager.submit(state.player.getInventory(),
            state.construction.getId(), state.materialList.required);
        if (r.thisRoundDeducted == 0) {
            setStatus(state, com.prefab.addon.PrefabCustomAddon.tr("gui.material.nothing_to_submit"), 0xFF5555, 80);
            return;
        }
        if (r.allDone) {
            setStatus(state, com.prefab.addon.PrefabCustomAddon.tr("gui.material.all_done"), 0x55FF55, 100);
        } else {
            setStatus(state, String.format(java.util.Locale.ROOT,
                com.prefab.addon.PrefabCustomAddon.tr("gui.material.partial_hint"),
                r.getSummary()), 0xFFFFAA55, 100);
        }
        refreshAll(state);
    }

    // === 重置 ===
    private static void onReset(State state) {
        ChallengeSessionManager.reset(state.playerId, state.construction.getId());
        setStatus(state, com.prefab.addon.PrefabCustomAddon.tr("gui.material.reset_done"), 0xFFFFAA55, 60);
        PrefabCustomAddon.LOGGER.info("[SUBMIT-GUI] 玩家 {} 重置建筑 {} 的提交进度",
            state.playerId, state.construction.getId());
        refreshAll(state);
    }

    // === 状态消息 (倒计时 1 tick = 50ms) ===
    private static void setStatus(State state, String msg, int color, int ticks) {
        state.statusMessage = msg;
        state.statusColor = color;
        state.statusExpireTick = Minecraft.getInstance().level.getGameTime() + ticks;
        if (state.statusEl != null) {
            state.statusEl.setText(Component.literal(msg));
            state.statusEl.textStyle(t -> t.textColor(color));
        }
    }

    // === 主 UI 构建 ===
    private static ModularUI buildUI(State state) {
        UIElement root = new UIElement();
        root.layout(l -> l
            .width(WINDOW_W).height(WINDOW_H)
            .flexDirection(FlexDirection.COLUMN)
            .paddingAll(4).gapAll(3)
        );
        root.style(s -> s.background(Sprites.BORDER));
        root.setOverflowVisible(false);

        // === 标题 ===
        TextElement titleEl = new TextElement();
        titleEl.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.material.title", state.construction.getName()));
        titleEl.textStyle(t -> t.textColor(0xFF55FFFF).textAlignHorizontal(Horizontal.CENTER));
        titleEl.layout(l -> l.widthPercent(100).height(14));
        root.addChild(titleEl);

        // === 副标题 + 进度 ===
        state.progressEl = new TextElement();
        updateProgressEl(state);  // 初始化
        state.progressEl.layout(l -> l.widthPercent(100).height(11));
        root.addChild(state.progressEl);

        // === 卡片 ScrollerView (竖向, viewPort 用纯色避免 BORDER 边框遮挡) ===
        ScrollerView scroller = new ScrollerView();
        scroller.scrollerStyle(s -> s
            .mode(ScrollerMode.VERTICAL)
            .horizontalScrollDisplay(ScrollDisplay.NEVER)
            .verticalScrollDisplay(ScrollDisplay.ALWAYS)
            .minScrollPixel(4).maxScrollPixel(40)
        );
        scroller.verticalScroller(v -> v.setScrollBarSize(3f));
        // 关键: viewPort 用 backgroundTexture() 覆盖默认 BORDER sprite, 避免 5px 边框遮挡内容
        // 同时把 viewPort 内边距从默认 5 改成 2, 配合 cardContainer 的 paddingAll(2) 让卡片不靠边
        scroller.viewPort(vp -> vp.style(s -> s
            .backgroundTexture(ColorPattern.SEAL_BLACK.rectTexture())
            .overlay(IGuiTexture.EMPTY))
            .layout(l -> l.paddingAll(2)));
        scroller.layout(l -> l.widthPercent(100).flexGrow(1));
        root.addChild(scroller);

        // 卡片容器 - flex-wrap 多行 (每页 4 张, 1 行 4 列)
        state.cardContainer = new UIElement();
        state.cardContainer.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .flexWrap(FlexWrap.WRAP)
            .alignContent(AlignContent.FLEX_START)
            .gapAll(4)
            .paddingLeft(10)   // 关键: 左边距加大, 防止卡片最左边的图标/文字被 ScrollerView 左边缘裁剪
            .paddingRight(6)
            .paddingTop(2).paddingBottom(2)
            .widthPercent(100).flexShrink(0).minHeight(0)
        );
        state.cardContainer.setId("__cards__");
        scroller.addScrollViewChild(state.cardContainer);

        // 卡片 (由 refreshCardsOnly 填充当前页)
        refreshCardsOnly(state);

        // === 翻页按钮行 (◀ 第 X/Y 页 ▶) ===
        UIElement pageRow = new UIElement();
        pageRow.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .widthPercent(100).height(18)
            .gapAll(4)
            .alignItems(AlignItems.CENTER)
        );
        Button btnPrev = new Button();
        btnPrev.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.material.prev_short"));
        btnPrev.textStyle(t -> t.textColor(0xFFFFFFFF));
        btnPrev.layout(l -> l.width(76).heightPercent(100));
        btnPrev.setOnClick(e -> {
            if (state.currentPage > 0) {
                state.currentPage--;
                refreshCardsOnly(state);
                updatePageInfoEl(state);
            }
        });
        pageRow.addChild(btnPrev);

        // 弹性占位
        UIElement pageSpacer = new UIElement();
        pageSpacer.layout(l -> l.flexGrow(1).heightPercent(100));
        pageRow.addChild(pageSpacer);

        state.pageInfoEl = new TextElement();
        updatePageInfoEl(state);
        state.pageInfoEl.textStyle(t -> t.textColor(0xFFAAAAAA)
            .textAlignHorizontal(Horizontal.CENTER));
        state.pageInfoEl.layout(l -> l.heightPercent(100));
        pageRow.addChild(state.pageInfoEl);

        UIElement pageSpacer2 = new UIElement();
        pageSpacer2.layout(l -> l.flexGrow(1).heightPercent(100));
        pageRow.addChild(pageSpacer2);

        Button btnNext = new Button();
        btnNext.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.material.next"));
        btnNext.textStyle(t -> t.textColor(0xFFFFFFFF));
        btnNext.layout(l -> l.width(76).heightPercent(100));
        btnNext.setOnClick(e -> {
            int totalPages = getTotalPages(state);
            if (state.currentPage < totalPages - 1) {
                state.currentPage++;
                refreshCardsOnly(state);
                updatePageInfoEl(state);
            }
        });
        pageRow.addChild(btnNext);

        root.addChild(pageRow);

        // === 状态消息行 ===
        state.statusEl = new TextElement();
        state.statusEl.setText("");
        state.statusEl.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        state.statusEl.layout(l -> l.widthPercent(100).height(11));
        root.addChild(state.statusEl);

        // === 底部按钮行 ===
        UIElement btnRow = new UIElement();
        btnRow.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .widthPercent(100).height(20)
            .gapAll(4)
            .alignItems(AlignItems.CENTER)
        );

        Button btnSubmitAll = new Button();
        btnSubmitAll.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.material.submit_all"));
        btnSubmitAll.textStyle(t -> t.textColor(0xFF55FF55));
        btnSubmitAll.layout(l -> l.width(110).heightPercent(100));
        btnSubmitAll.setOnClick(e -> onSubmitAll(state));
        btnRow.addChild(btnSubmitAll);

        Button btnReset = new Button();
        btnReset.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.material.reset_short"));
        btnReset.textStyle(t -> t.textColor(0xFFFFAA55));
        btnReset.layout(l -> l.width(80).heightPercent(100));
        btnReset.setOnClick(e -> onReset(state));
        btnRow.addChild(btnReset);

        // 弹性占位
        UIElement spacer = new UIElement();
        spacer.layout(l -> l.flexGrow(1).heightPercent(100));
        btnRow.addChild(spacer);

        Button btnClose = new Button();
        btnClose.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.material.close_short"));
        btnClose.layout(l -> l.width(80).heightPercent(100));
        btnClose.setOnClick(e -> {
            // 关键: 关闭时放回上一级界面 (一般是 CustomStructureGui),
            //   而不是 Minecraft.getInstance().setScreen(null) (直接退到游戏世界)
            var prev = state.previousScreen;
            if (prev != null) {
                Minecraft.getInstance().setScreen(prev);
            } else {
                Minecraft.getInstance().setScreen(null);
            }
        });
        btnRow.addChild(btnClose);

        root.addChild(btnRow);

        return ModularUI.of(UI.of(root,
            StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)));
    }

    // === 显示名解析 (翻译 + 英文名 mod 缩写) ===
    private static String resolveDisplayName(MaterialRow row) {
        if (isFallbackIcon(row.icon)) {
            return row.blockId;
        }
        String localized = null;
        try { localized = row.icon.getHoverName().getString(); } catch (Throwable ignored) {}
        if (localized == null || localized.isEmpty()
            || localized.startsWith("item.") || localized.startsWith("block.")) {
            int colonIdx = row.blockId.indexOf(':');
            if (colonIdx > 0) {
                String ns = row.blockId.substring(0, colonIdx);
                String path = row.blockId.substring(colonIdx + 1);
                String abbrNs = ns.length() > 1 ? ns.substring(0, 1) + ":" : ns + ":";
                return abbrNs + path;
            }
            return row.blockId;
        }
        if (!hasChineseCharacters(localized)) {
            String modHint = getModIdFromBlockId(row.blockId);
            if (modHint != null) return localized + " [" + modHint + "]";
        }
        return localized;
    }

    private static boolean isFallbackIcon(ItemStack icon) {
        if (icon.isEmpty()) return true;
        if (icon.getItem() == Items.GRAY_STAINED_GLASS_PANE) return true;
        if (icon.getItem() == Items.BARRIER) return true;
        return false;
    }

    private static boolean hasChineseCharacters(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }

    private static String getModIdFromBlockId(String blockId) {
        if (blockId == null) return null;
        int colon = blockId.indexOf(':');
        if (colon < 0) return null;
        String ns = blockId.substring(0, colon);
        // 用翻译键查 mod 显示名: 已知 mod 用本地化名, 未知 mod 直接返回 modId
        String key = "gui.material.mod." + ns;
        String translated = com.prefab.addon.PrefabCustomAddon.tr(key);
        // tr() 在 key 缺失时返回 key 本身, 此时用 ns 作为 fallback
        if (translated == null || translated.isEmpty() || translated.equals(key)) {
            return ns;
        }
        return translated;
    }

    // === 工具: 背包里数某 blockId 的数量 ===
    private static Map<String, Integer> countSingleInInventory(
        net.minecraft.client.player.LocalPlayer player, String blockId) {
        Map<String, Integer> out = new java.util.HashMap<>();
        int count = MaterialCalculator.countInInventory(player.getInventory(), blockId);
        out.put(blockId, count);
        return out;
    }

    // === 工具: 从背包里扣某 blockId 的 count 个 ===
    private static void deductFromInventory(
        net.minecraft.client.player.LocalPlayer player, String blockId, int count) {
        if (count <= 0) return;
        // 跟 ChallengeSessionManager.submit 一致: 找 (blockId 派生的) ItemStack 然后 setCount(0)
        ItemStack target = MaterialCalculator.getItemStack(blockId);
        if (target.isEmpty()) return;
        Inventory inv = player.getInventory();
        int left = count;
        for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            if (!s.is(target.getItem())) continue;
            int n = Math.min(left, s.getCount());
            s.shrink(n);
            if (s.getCount() <= 0) inv.setItem(i, ItemStack.EMPTY);
            else inv.setItem(i, s);
            left -= n;
        }
    }
}
