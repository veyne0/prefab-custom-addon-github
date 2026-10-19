package com.prefab.addon.client.gui;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.ConstructionInfo;
import com.prefab.addon.extension.LocalBuilding;
import com.prefab.addon.mcs.McsApiClient;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 蓝图广场 (mcschematic.top) — I 键打开, LDLib2 ModularUI 实现.
 *
 * <p>布局 (整屏, 100% width/height):</p>
 * <pre>
 *   ┌──────────────────────────────────────────┐
 *   │ [在线|已下载]  共N个蓝图  [最新][最热][刷新][网页] │ (h=18 工具栏)
 *   │ [搜索框........................] [搜索]      │ (h=16)
 *   │ ┌─ 卡片滚动区 (3 列) ──────────────────┐    │
 *   │ │ [图] 名称        [图] 名称  ...     │    │
 *   │ │      by xx · ↓n       下载/已下载   │    │
 *   │ └──────────────────────────────────┘    │
 *   │ [加载下一页]  (还有下一页时)                  │
 *   │ 状态栏: 下载 xx% / 已保存 / 错误信息          │ (h=12)
 *   └──────────────────────────────────────────┘
 * </pre>
 *
 * <p>交互与现有「下载建筑」一致: 点 [下载] 落盘到 prefab-mcs/ (建筑 + .txt 元信息 + 预览图),
 * 已下载的卡片点击 → {@link GuiConstructionDetail} 建筑详细界面 (3D 预览 + 建造).
 * 「已下载」视图列出 prefab-mcs/ 里的所有建筑, 点击同样进详细界面.</p>
 */
public final class GuiBlueprintMarket {

    // 注册 WebP 解码器 (mcschematic.top 预览图是 webp, ImageIO 默认不支持).
    // 解码器类已由 jar task 合并进 mod jar; 显式 registerProvider 不依赖 ServiceLoader 类加载器行为.
    static {
        try {
            javax.imageio.spi.IIORegistry.getDefaultInstance()
                .registerServiceProvider(new com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi());
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[MCS] WebP 解码器注册失败, 预览图可能无法显示: {}", t.getMessage());
        }
    }

    private static final int CARD_H = 74;
    private static final int THUMB = 62;
    private static final int ONLINE_PAGE_SIZE = 8;   // 在线视图每页显示 8 张卡片 (翻页式)
    /** 分类按钮尺寸 (固定宽, 名字最长 5 个汉字也放得下). */
    private static final int CAT_BTN_W = 58;
    private static final int CAT_BTN_H = 13;

    // 动态列布局: 按当前窗口宽度决定列数/卡片宽, 保证撑满不空一半
    private static int cols() {
        int avail = Minecraft.getInstance().getWindow().getGuiScaledWidth() - 32; // 根padding + 滚动条 + 列表padding
        return Math.max(3, Math.min(avail / 190, 6));
    }

    private static int cardW() {
        int avail = Minecraft.getInstance().getWindow().getGuiScaledWidth() - 32;
        return Math.max(110, (avail - (cols() - 1) * 4) / cols());
    }

    private static int rowW() {
        return cardW() * cols() + 4 * (cols() - 1);
    }

    private static int onlinePageSize() {
        return ONLINE_PAGE_SIZE;
    }

    // === 全局状态 (界面单实例, 重开保留浏览位置) ===
    private static String query = "";
    private static String sort = "latest";
    private static boolean viewOnline = true;           // true=在线列表 false=已下载列表
    private static final List<McsApiClient.Blueprint> items = new ArrayList<>();
    private static String nextCursor = null;
    private static int totalCount = -1;
    private static boolean loading = false;
    private static String errorMessage = null;
    private static int page = 0;                        // 在线视图当前页 (0-based, 翻页式)
    private static int localPage = 0;                   // 已下载视图当前页 (0-based, 翻页式)

    // 分类筛选 (仅在线视图): categories=null 表示还没拉到; categoryIdx 0 = 全部分类, 1..n = categories[idx-1]
    private static List<McsApiClient.Category> categories = null;
    private static int categoryIdx = 0;

    // 下载状态 (id → 状态)
    private static final Set<String> DOWNLOADING = new HashSet<>();
    private static final Set<String> DOWNLOADED = new HashSet<>();

    // 预览图纹理缓存 (在线: key=blueprint id, 已下载: key=图片路径)
    private static final Map<String, ResourceLocation> PREVIEW_IMAGES = new HashMap<>();
    private static final Set<String> PREVIEW_LOADING = new HashSet<>();
    private static final Set<String> PREVIEW_EMPTY = new HashSet<>();

    private static final AtomicInteger TEX_GEN = new AtomicInteger();

    // 已下载扫描缓存 (进入已下载视图 / 下载完成时刷新)
    private static List<LocalBuilding> localCache = new ArrayList<>();

    // 状态栏 (rebuild 时重建, 持引用以便直接更新)
    private static Label statusLabel;
    // 根容器 / 列表区引用 (重建用, 单实例界面)
    private static UIElement rootRef;
    private static UIElement listAreaRef;

    private GuiBlueprintMarket() {}

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        // 关键顺序: 先刷新数据/重置页码, 再 buildRoot 渲染.
        // 之前是先渲染后 refreshLocal() → 详情页返回时 UI 用旧页码画了分页条,
        // 但随后 localPage 被归零 → 分页按钮 get 到的是 0, 点了像没反应.
        refreshLocal();
        ensureCategories();
        UIElement root = buildRoot();
        ModularUI ui = ModularUI.of(UI.of(root,
            StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)), mc.player);
        mc.setScreen(new ModularUIScreen(ui, Component.literal(
            PrefabCustomAddon.tr("mcs.title"))));
        if (viewOnline && items.isEmpty() && !loading) {
            fetchFirstPage();
        }
    }

    // ==================== UI 构建 ====================

    private static UIElement buildRoot() {
        UIElement root = new UIElement();
        root.layout(l -> l
            .widthPercent(100).heightPercent(100)
            .flexDirection(FlexDirection.COLUMN)
            .paddingAll(4).gapAll(3)
        );
        root.style(s -> s.background(Sprites.BORDER));
        root.setOverflowVisible(false);
        rebuildAll(root);
        return root;
    }

    /** 整体重建 (视图切换/排序切换时调用, 会重建搜索框). */
    private static void rebuildAll(UIElement root) {
        rootRef = root;
        root.clearAllChildren();

        // 工具栏: [在线|已下载] 统计 [最新][最热][刷新][网页]
        UIElement toolbar = new UIElement();
        toolbar.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(18).gapAll(3));
        toolbar.style(s -> s.background(Sprites.RECT_DARK));
        toolbar.setOverflowVisible(false);
        root.addChild(toolbar);

        toolbar.addChild(viewBtn(tr("mcs.view.online"), viewOnline, () -> { viewOnline = true; refreshLocal(); rebuild(); }));
        toolbar.addChild(viewBtn(tr("mcs.view.downloaded"), !viewOnline, () -> { viewOnline = false; refreshLocal(); rebuild(); }));

        Label count = new Label();
        count.setText(countText(), false);
        count.textStyle(t -> t.textColor(0xAAAAAA));
        count.layout(l -> l.flexGrow(1).heightPercent(100).paddingLeft(4));
        toolbar.addChild(count);

        toolbar.addChild(toolbarBtn(tr("mcs.sort.latest"), () -> { if (!sort.equals("latest")) { sort = "latest"; fetchFirstPage(); } }));
        toolbar.addChild(toolbarBtn(tr("mcs.sort.popular"), () -> { if (!sort.equals("popular")) { sort = "popular"; fetchFirstPage(); } }));
        toolbar.addChild(toolbarBtn(tr("mcs.refresh"), () -> { if (viewOnline) fetchFirstPage(); else refreshLocal(); }));
        toolbar.addChild(toolbarBtn(tr("mcs.open_web"), () -> openWeb(McsApiClient.SITE)));

        // 搜索行 (仅在线视图)
        if (viewOnline) {
            UIElement searchRow = new UIElement();
            searchRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(16).gapAll(3));
            root.addChild(searchRow);
            TextField search = new TextField();
            search.setText(query);
            search.setAnyString();
            search.setTextValidator(s -> s == null || s.length() <= 64);
            search.textFieldStyle(s -> s.textColor(0xFFFFFF).textShadow(false));
            search.layout(l -> l.flexGrow(1).height(16).minHeight(16));
            search.setTextResponder(s -> query = s);
            searchRow.addChild(search);
            Button go = new Button().setText(tr("mcs.search"), false);
            go.layout(l -> l.width(44).height(16));
            go.setOnClick(e -> fetchFirstPage());
            searchRow.addChild(go);

            // 分类按钮区: [全部][红石装置][仓储与物流]... 按窗口宽度手动分行
            // (LDLib2 flex 没有自动 wrap, 手动按 perRow 切块; 分类列表没拉到前不显示)
            if (categories != null && !categories.isEmpty()) {
                int perRow = Math.max(4,
                    (Minecraft.getInstance().getWindow().getGuiScaledWidth() - 8) / (CAT_BTN_W + 3));
                int total = categories.size() + 1;   // +1 是 "全部分类" (idx 0)
                for (int r = 0; r < total; r += perRow) {
                    UIElement btnRow = new UIElement();
                    btnRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(CAT_BTN_H).gapAll(3));
                    btnRow.setOverflowVisible(false);
                    for (int i = r; i < Math.min(r + perRow, total); i++) {
                        final int idx = i;
                        boolean sel = categoryIdx == idx;
                        String label = i == 0 ? tr("mcs.category.all") : categories.get(i - 1).name;
                        Button cb = new Button().setText(truncate(label, 7), false);
                        cb.textStyle(t -> t.textColor(sel ? 0xFFDD55 : 0xFFFFFF));
                        cb.layout(l -> l.width(CAT_BTN_W).height(CAT_BTN_H).flexShrink(0));
                        cb.setOnClick(e -> {
                            if (categoryIdx != idx) {
                                categoryIdx = idx;
                                fetchFirstPage();
                            }
                        });
                        btnRow.addChild(cb);
                    }
                    root.addChild(btnRow);
                }
            }
        }

        // 卡片滚动区
        UIElement listArea = new UIElement();
        listArea.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).flexGrow(1).minHeight(0));
        listAreaRef = listArea;
        root.addChild(listArea);

        // 状态栏
        UIElement statusRow = new UIElement();
        statusRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(12));
        statusRow.style(s -> s.background(Sprites.RECT_DARK));
        root.addChild(statusRow);
        statusLabel = new Label();
        statusLabel.setText(currentStatus(), false);
        statusLabel.textStyle(t -> t.textColor(0xBBBBBB));
        statusLabel.layout(l -> l.widthPercent(100).height(12).paddingLeft(4));
        statusRow.addChild(statusLabel);

        rebuildList(listArea);
    }

    private static void rebuild() {
        // 单实例界面: 直接对记录的根容器整体重建
        if (rootRef != null) {
            rebuildAll(rootRef);
        }
    }

    /** 仅重建卡片列表 (搜索/下载完成时调用, 不打断搜索框输入). */
    private static void rebuildList() {
        if (listAreaRef != null) {
            rebuildList(listAreaRef);
        }
    }

    private static void rebuildList(UIElement listArea) {
        listArea.clearAllChildren();
        if (viewOnline) {
            rebuildOnlineList(listArea);
        } else {
            rebuildLocalList(listArea);
        }
    }

    /** 在线列表: 动态多列卡片 + 底部翻页条 (‹ x/y ›, 翻到未拉取的页自动追加请求). */
    private static void rebuildOnlineList(UIElement listArea) {
        ScrollerView scroll = new ScrollerView();
        scroll.scrollerStyle(s -> s.mode(ScrollerMode.VERTICAL));
        scroll.verticalScroller(v -> v.setScrollBarSize(6f));
        scroll.layout(l -> l.flexGrow(1).heightPercent(100));
        listArea.addChild(scroll);

        UIElement list = new UIElement();
        list.layout(l -> l.flexDirection(FlexDirection.COLUMN).width(rowW()).gapAll(4).paddingAll(2));
        scroll.addScrollViewChild(list);

        int cw = cardW();
        int pageSize = onlinePageSize();
        int totalPages = onlineTotalPages();
        if (page >= totalPages) page = totalPages - 1;

        if (items.isEmpty()) {
            Label empty = new Label();
            if (loading) empty.setText(tr("mcs.empty.loading"), false);
            else if (errorMessage != null) empty.setText(tr("mcs.empty.failed"), false);
            else empty.setText(tr("mcs.empty.none"), false);
            empty.textStyle(t -> t.textColor(0x888888));
            empty.layout(l -> l.width(rowW() - 4).height(60).alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER));
            list.addChild(empty);
            if (errorMessage != null) {
                Label err = new Label();
                err.setText(truncate(errorMessage, 50), false);
                err.textStyle(t -> t.textColor(0xFF6666));
                err.layout(l -> l.width(rowW() - 4).height(12).alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER));
                list.addChild(err);
            }
            return;
        }

        // 当前页切片 (翻到还没拉取的页时先显示加载中, 数据到了自动刷新)
        int start = page * pageSize;
        if (start >= items.size()) {
            Label empty = new Label();
            empty.setText(tr("mcs.empty.loading"), false);
            empty.textStyle(t -> t.textColor(0x888888));
            empty.layout(l -> l.width(rowW() - 4).height(60).alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER));
            list.addChild(empty);
        } else {
            int end = Math.min(start + pageSize, items.size());
            for (int i = start; i < end; i += cols()) {
                UIElement row = new UIElement();
                row.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4));
                for (int j = i; j < Math.min(i + cols(), end); j++) {
                    row.addChild(onlineCard(items.get(j), cw));
                }
                list.addChild(row);
            }
        }

        // 翻页条: ‹ x/y › (居中)
        UIElement pager = new UIElement();
        pager.layout(l -> l.flexDirection(FlexDirection.ROW).width(rowW() - 4).height(14).gapAll(4)
            .alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER));
        Button prev = new Button().setText("‹", false);
        prev.layout(l -> l.width(14).height(14));
        prev.setActive(page > 0 && !loading);
        prev.setOnClick(e -> gotoPage(page - 1));
        pager.addChild(prev);
        Label pl = new Label();
        pl.setText((page + 1) + " / " + totalPages, false);
        pl.textStyle(t -> t.textColor(0xCCCCCC));
        pl.layout(l -> l.width(60).height(14).alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER));
        pager.addChild(pl);
        Button next = new Button().setText("›", false);
        next.layout(l -> l.width(14).height(14));
        next.setActive(page < totalPages - 1 && !loading);
        next.setOnClick(e -> gotoPage(page + 1));
        pager.addChild(next);
        list.addChild(pager);
    }

    /** 在线列表总页数 (有下一页时多留一页, 翻过去会触发拉取). */
    private static int onlineTotalPages() {
        int local = (items.size() + onlinePageSize() - 1) / onlinePageSize();
        if (local == 0) local = 1;
        boolean hasMore = nextCursor != null && !nextCursor.isEmpty();
        return hasMore ? local + 1 : local;
    }

    /** 翻页: 超出已拉取范围时用游标追加请求. */
    private static void gotoPage(int newPage) {
        newPage = Math.max(0, Math.min(newPage, onlineTotalPages() - 1));
        if (newPage == page) return;
        page = newPage;
        if (page * onlinePageSize() >= items.size() && nextCursor != null && !nextCursor.isEmpty()) {
            fetchPage(nextCursor);
        } else {
            rebuild();
        }
    }

    /** 已下载列表: prefab-mcs/ 里的建筑卡片, 点击进详细界面; 分页浏览 (‹ x/y ›, 与在线列表同款). */
    private static void rebuildLocalList(UIElement listArea) {
        ScrollerView scroll = new ScrollerView();
        scroll.scrollerStyle(s -> s.mode(ScrollerMode.VERTICAL));
        scroll.verticalScroller(v -> v.setScrollBarSize(6f));
        scroll.layout(l -> l.flexGrow(1).heightPercent(100));
        listArea.addChild(scroll);

        UIElement list = new UIElement();
        list.layout(l -> l.flexDirection(FlexDirection.COLUMN).width(rowW()).gapAll(4).paddingAll(2));
        scroll.addScrollViewChild(list);

        if (localCache.isEmpty()) {
            Label empty = new Label();
            empty.setText(tr("mcs.empty.local"), false);
            empty.textStyle(t -> t.textColor(0x888888));
            empty.layout(l -> l.width(rowW() - 4).height(60).alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER));
            list.addChild(empty);
            return;
        }

        int c = cols();
        int perPage = Math.max(1, c * localRowsPerPage());
        int totalPages = (localCache.size() + perPage - 1) / perPage;
        if (localPage >= totalPages) localPage = totalPages - 1;
        if (localPage < 0) localPage = 0;
        int start = localPage * perPage;
        int end = Math.min(start + perPage, localCache.size());
        int cw = cardW();
        for (int i = start; i < end; i += c) {
            UIElement row = new UIElement();
            row.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4));
            for (int j = i; j < Math.min(i + c, end); j++) {
                row.addChild(localCard(localCache.get(j)));
            }
            list.addChild(row);
        }

        // 翻页条: ‹ x/y › (居中, 只有一页时不显示)
        if (totalPages > 1) {
            UIElement pager = new UIElement();
            pager.layout(l -> l.flexDirection(FlexDirection.ROW).width(rowW() - 4).height(14).gapAll(4)
                .alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER));
            Button prev = new Button().setText("‹", false);
            prev.layout(l -> l.width(14).height(14));
            prev.setActive(localPage > 0);
            prev.setOnClick(e -> {
                if (localPage > 0) {
                    localPage--;
                    rebuildList();
                }
            });
            pager.addChild(prev);
            Label pl = new Label();
            pl.setText((localPage + 1) + " / " + totalPages, false);
            pl.textStyle(t -> t.textColor(0xCCCCCC));
            pl.layout(l -> l.width(60).height(14).alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER));
            pager.addChild(pl);
            Button next = new Button().setText("›", false);
            next.layout(l -> l.width(14).height(14));
            next.setActive(localPage < totalPages - 1);
            next.setOnClick(e -> {
                if (localPage < totalPages - 1) {
                    localPage++;
                    rebuildList();
                }
            });
            pager.addChild(next);
            list.addChild(pager);
        }
    }

    /** 已下载列表每页行数 (按窗口高度算, 卡片 74 高 + 行距 4). */
    private static int localRowsPerPage() {
        int availH = Minecraft.getInstance().getWindow().getGuiScaledHeight() - 85;
        return Math.max(1, availH / (CARD_H + 4));
    }

    // ==================== 卡片 ====================

    /** 在线蓝图卡片: 预览图 + 名称/作者/元信息 + 下载按钮; 已下载时点卡片开详细界面.
     *  注意: 未下载的卡片不设 onClick — 下载按钮的点击事件会冒泡到卡片, 否则点下载会跳网页. */
    private static UIElement onlineCard(McsApiClient.Blueprint b, int cw) {
        boolean downloaded = isDownloadedNow(b);
        Button card = new Button().noText();
        card.buttonStyle(s -> s.baseTexture(Sprites.RECT_DARK)
            .hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
        card.layout(l -> l.flexDirection(FlexDirection.ROW).width(cw).height(CARD_H)
            .paddingAll(3).gapAll(4));

        // 预览图 48×48
        UIElement thumb = new UIElement();
        thumb.layout(l -> l.width(THUMB).height(THUMB));
        ResourceLocation img = PREVIEW_IMAGES.get(b.id);
        if (img != null) {
            thumb.style(s -> s.backgroundTexture(SpriteTexture.of(img)));
        } else {
            thumb.style(s -> s.backgroundTexture(Sprites.RECT_DARK));
            ensureOnlinePreview(b);
        }
        card.addChild(thumb);

        // 右侧信息列 (宽度 = 卡片宽 - 内边距 - 缩略图 - 间距)
        UIElement info = new UIElement();
        info.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).gapAll(1).minWidth(0));
        card.addChild(info);
        int textW = cw - THUMB - 12;

        Label name = new Label();
        name.setText(truncateW(b.title == null ? "?" : b.title, textW), false);
        name.textStyle(t -> t.textColor(0xFFFFFF));
        name.layout(l -> l.widthPercent(100).height(10));
        info.addChild(name);

        Label author = new Label();
        author.setText(truncateW("上传者 " + (b.author == null || b.author.isEmpty() ? "?" : b.author), textW), false);
        author.textStyle(t -> t.textColor(0x888888));
        author.layout(l -> l.widthPercent(100).height(9));
        info.addChild(author);

        Label meta = new Label();
        meta.setText(truncateW((b.sourceFormat == null ? "litematic" : b.sourceFormat) + " · ↓" + b.downloads, textW), false);
        meta.textStyle(t -> t.textColor(0x777777));
        meta.layout(l -> l.widthPercent(100).height(9));
        info.addChild(meta);

        // 状态行: 下载中 / 已下载 / 下载按钮
        if (DOWNLOADING.contains(b.id)) {
            Label dl = new Label();
            dl.setText(tr("mcs.download.waiting"), false);
            dl.textStyle(t -> t.textColor(0xFFAA55));
            dl.layout(l -> l.widthPercent(100).height(12));
            info.addChild(dl);
        } else if (downloaded) {
            Label done = new Label();
            done.setText(tr("mcs.download.downloaded"), false);
            done.textStyle(t -> t.textColor(0x55FF55));
            done.layout(l -> l.widthPercent(100).height(12));
            info.addChild(done);
        } else {
            Button dl = new Button().setText(tr("mcs.download"), false);
            dl.layout(l -> l.width(52).height(12));
            dl.setOnClick(e -> startDownload(b));
            info.addChild(dl);
        }

        // 已下载 → 点卡片进详细界面 (同下载建筑: 3D 预览 + 建造)
        // 未下载时不设卡片点击: 一是避免与下载按钮点击冒泡冲突, 二是没必要的网页跳转
        if (downloaded) {
            card.setOnClick(e -> openDetailFor(b));
        }
        return card;
    }

    /** 已下载建筑卡片: 本地预览图 + 名称/作者/大小, 点击进详细界面. */
    private static UIElement localCard(LocalBuilding lb) {
        Button card = new Button().noText();
        card.buttonStyle(s -> s.baseTexture(Sprites.RECT_DARK)
            .hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
        card.layout(l -> l.flexDirection(FlexDirection.ROW).width(cardW()).height(CARD_H)
            .paddingAll(3).gapAll(4));

        UIElement thumb = new UIElement();
        thumb.layout(l -> l.width(THUMB).height(THUMB));
        String key = lb.imagePath == null ? "none:" + lb.id : lb.imagePath.toString();
        ResourceLocation img = PREVIEW_IMAGES.get(key);
        if (img != null) {
            thumb.style(s -> s.backgroundTexture(SpriteTexture.of(img)));
        } else {
            thumb.style(s -> s.backgroundTexture(Sprites.RECT_DARK));
            ensureLocalPreview(lb, key);
        }
        card.addChild(thumb);

        UIElement info = new UIElement();
        info.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).gapAll(1).minWidth(0));
        card.addChild(info);
        int textW = cardW() - THUMB - 12;

        Label name = new Label();
        name.setText(truncateW(lb.getDisplayName(), textW), false);
        name.textStyle(t -> t.textColor(0xFFFFFF));
        name.layout(l -> l.widthPercent(100).height(10));
        info.addChild(name);

        Label author = new Label();
        author.setText(truncateW("上传者 " + (lb.author == null || lb.author.isEmpty() ? "?" : lb.author), textW), false);
        author.textStyle(t -> t.textColor(0x888888));
        author.layout(l -> l.widthPercent(100).height(9));
        info.addChild(author);

        Label hint = new Label();
        hint.setText(tr("mcs.detail_hint"), false);
        hint.textStyle(t -> t.textColor(0x5588CC));
        hint.layout(l -> l.widthPercent(100).height(9));
        info.addChild(hint);

        card.setOnClick(e -> openDetailGui(lb));
        return card;
    }

    // ==================== 数据动作 ====================

    private static void fetchFirstPage() {
        items.clear();
        nextCursor = null;
        totalCount = -1;
        errorMessage = null;
        page = 0;
        fetchPage(null);
    }

    private static void fetchPage(String cursor) {
        if (loading) return;
        loading = true;
        errorMessage = null;
        rebuild();
        McsApiClient.fetchAsync(query, sort, currentCategorySlug(), cursor).whenComplete((pg, err) ->
            Minecraft.getInstance().execute(() -> {
                loading = false;
                if (err != null) {
                    Throwable t = err.getCause() != null ? err.getCause() : err;
                    errorMessage = t.getMessage() == null ? "网络错误" : t.getMessage();
                    setStatus(tr("mcs.status.failed", truncate(errorMessage, 30)));
                    rebuild();
                    return;
                }
                items.addAll(pg.items);
                nextCursor = pg.nextCursor;
                if (pg.total > 0) totalCount = pg.total;
                rebuild();
            }));
    }

    private static void refreshLocal() {
        localCache = McsApiClient.scanDownloaded();
        // 不重置 localPage: 详情页返回/重开界面时保留浏览位置 (越界由渲染时 clamp 收敛)
    }

    // ==================== 分类筛选 ====================

    /** 当前选中分类的 slug (全部分类/未加载 → null = 不限). */
    private static String currentCategorySlug() {
        if (categories == null || categoryIdx <= 0 || categoryIdx > categories.size()) return null;
        return categories.get(categoryIdx - 1).slug;
    }

    /** 懒加载分类列表 (首次 open 时), 成功后重建界面显示分类按钮. */
    private static void ensureCategories() {
        if (categories != null) return;
        McsApiClient.fetchCategoriesAsync().whenComplete((list, err) ->
            Minecraft.getInstance().execute(() -> {
                if (err != null) {
                    // 失败置空列表: 不再反复重试, 分类行只显示 "全部分类"
                    PrefabCustomAddon.LOGGER.warn("[MCS] 分类列表拉取失败: {}", err.getMessage());
                    categories = new ArrayList<>();
                    return;
                }
                categories = list;
                if (viewOnline) rebuild();
            }));
    }

    private static void startDownload(McsApiClient.Blueprint b) {
        if (DOWNLOADING.contains(b.id)) return;
        DOWNLOADING.add(b.id);
        setStatus(tr("mcs.download.start", truncate(b.title, 14)));
        Runnable reload = GuiBlueprintMarket::rebuildList;
        reload.run();
        McsApiClient.downloadAsync(b, new McsApiClient.DownloadCallback() {
            @Override public void onProgress(long done, long total) {
                // 简化: 与现有下载建筑一致, 下载中只显示状态文案, 完成后刷新
            }
            @Override public void onSuccess(java.nio.file.Path savedTo) {
                Minecraft.getInstance().execute(() -> {
                    DOWNLOADING.remove(b.id);
                    DOWNLOADED.add(b.id);
                    setStatus(tr("mcs.download.done", savedTo.getFileName().toString()));
                    refreshLocal();
                    if (reload != null) reload.run();
                    PrefabCustomAddon.LOGGER.info("[MCS] 蓝图已下载: {}", savedTo);
                });
            }
            @Override public void onError(String message) {
                Minecraft.getInstance().execute(() -> {
                    DOWNLOADING.remove(b.id);
                    setStatus(tr("mcs.download.fail", message));
                    if (reload != null) reload.run();
                });
            }
        });
    }

    /** 已下载卡片 → 建筑详细界面 (CustomStructureGui: 3D 预览 + 建造). */
    private static void openDetailFor(McsApiClient.Blueprint b) {
        String base = b.fileBase();
        LocalBuilding hit = findLocalByBase(base);
        if (hit != null) {
            openDetailGui(hit);
        } else {
            setStatus(tr("mcs.status.not_found"));
        }
    }

    /**
     * 打开建筑详细界面 — 用 CustomStructureGui (蓝图右键那个完整详情页):
     * 底部按钮 关闭/预览(或提交材料)/更换建筑/收藏/删除建筑/更新缩略图, 左侧真实方块尺寸.
     * 走 openFromMarket (无蓝图建造入口): ALT 预览建造不要求背包有自定义蓝图,
     * openPos 用玩家当前位置 (预览基座落在脚下).
     */
    private static void openDetailGui(LocalBuilding lb) {
        ConstructionInfo ci = GuiExtensionPackEditor.buildDetailInfo(lb);
        var p = Minecraft.getInstance().player;
        BlockPos pos = p != null ? p.blockPosition() : BlockPos.ZERO;
        CustomStructureGui.openFromMarket(ci, pos);
    }

    /** 按 fileBase (标题-id前8位) 在扫描缓存里找对应 LocalBuilding, 未命中现场再扫一次. */
    private static LocalBuilding findLocalByBase(String base) {
        for (LocalBuilding lb : localCache) {
            if (lb.id.equals(base) || lb.id.startsWith(base + "_")) return lb;
        }
        refreshLocal();
        for (LocalBuilding lb : localCache) {
            if (lb.id.equals(base) || lb.id.startsWith(base + "_")) return lb;
        }
        return null;
    }

    private static boolean isDownloadedNow(McsApiClient.Blueprint b) {
        if (DOWNLOADED.contains(b.id)) return true;
        String base = b.fileBase();
        for (LocalBuilding lb : localCache) {
            if (lb.id.equals(base) || lb.id.startsWith(base + "_")) return true;
        }
        return false;
    }

    private static void openWeb(String url) {
        try {
            net.minecraft.Util.getPlatform().openUri(java.net.URI.create(url));
            setStatus(tr("mcs.status.opened", url));
        } catch (Throwable t) {
            try {
                Minecraft.getInstance().keyboardHandler.setClipboard(url);
                setStatus(tr("mcs.status.clipboard"));
            } catch (Throwable ignored) {}
        }
    }

    // ==================== 预览图加载 ====================

    /** 在线卡片预览图: 磁盘缓存 → 下载 → DynamicTexture → 刷新列表. */
    private static void ensureOnlinePreview(McsApiClient.Blueprint b) {
        if (PREVIEW_LOADING.contains(b.id) || PREVIEW_EMPTY.contains(b.id) || PREVIEW_IMAGES.containsKey(b.id)) return;
        loadPreview(b.id, McsApiClient.fetchPreviewAsync(b), GuiBlueprintMarket::scheduleListRebuild);
    }

    /** 本地卡片预览图: 直接读图片文件 → DynamicTexture → 刷新列表 (否则首次打开只显示占位图). */
    private static void ensureLocalPreview(LocalBuilding lb, String key) {
        if (lb.imagePath == null || PREVIEW_LOADING.contains(key) || PREVIEW_EMPTY.contains(key)) return;
        loadPreview(key, CompletableFuture.supplyAsync(() -> {
            try {
                return Files.readAllBytes(lb.imagePath);
            } catch (Exception e) {
                return null;
            }
        }), GuiBlueprintMarket::scheduleListRebuild);
    }

    /** 一帧内多张图加载完成只重建一次列表 (17 张图各回调一次 rebuildList 会白烧 DOM 构建). */
    private static boolean rebuildPending = false;

    private static void scheduleListRebuild() {
        if (rebuildPending) return;
        rebuildPending = true;
        Minecraft.getInstance().execute(() -> {
            rebuildPending = false;
            rebuildList();
        });
    }

    private static void loadPreview(String key, CompletableFuture<byte[]> future, Runnable reload) {
        PREVIEW_LOADING.add(key);
        future.thenAccept(bytes -> Minecraft.getInstance().execute(() -> {
            PREVIEW_LOADING.remove(key);
            if (bytes == null || bytes.length == 0) {
                PREVIEW_EMPTY.add(key);
                return;
            }
            DynamicTexture tex = uploadTexture(bytes);
            if (tex != null) {
                ResourceLocation loc = Minecraft.getInstance().getTextureManager()
                    .register("mcs_prev_" + TEX_GEN.incrementAndGet(), tex);
                PREVIEW_IMAGES.put(key, loc);
                if (reload != null) reload.run();
            } else {
                PREVIEW_EMPTY.add(key);
            }
        })).exceptionally(ex -> {
            Minecraft.getInstance().execute(() -> {
                PREVIEW_LOADING.remove(key);
                PREVIEW_EMPTY.add(key);
            });
            return null;
        });
    }

    /** 图片字节 → 居中裁方 → 缩到 ≤256 → DynamicTexture (失败返回 null). */
    private static DynamicTexture uploadTexture(byte[] bytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) return null;
            int w = img.getWidth();
            int h = img.getHeight();
            if (w <= 0 || h <= 0) return null;
            int side = Math.min(w, h);
            if (side < w || side < h) {
                BufferedImage copy = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
                Graphics2D gc = copy.createGraphics();
                gc.drawImage(img.getSubimage((w - side) / 2, (h - side) / 2, side, side), 0, 0, null);
                gc.dispose();
                img = copy;
            }
            if (side > 256) {
                BufferedImage scaled = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
                Graphics2D gs = scaled.createGraphics();
                gs.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                gs.drawImage(img, 0, 0, 256, 256, null);
                gs.dispose();
                img = scaled;
            }
            int size = img.getWidth();
            DynamicTexture tex = new DynamicTexture(size, size, false);
            tex.setFilter(true, true);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int argb = img.getRGB(x, y);
                    int abgr = (argb & 0xFF00FF00) | ((argb & 0xFF0000) >> 16) | ((argb & 0xFF) << 16);
                    tex.getPixels().setPixelRGBA(x, y, abgr);
                }
            }
            tex.upload();
            return tex;
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("[MCS] 预览图纹理上传失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 小工具 ====================

    private static UIElement viewBtn(String text, boolean active, Runnable onClick) {
        Button btn = new Button().setText(text, false);
        btn.text.textStyle(t -> t.textColor(active ? 0xFFFFDD66 : 0xCCCCCC));
        btn.layout(l -> l.width(52).height(14));
        btn.setOnClick(e -> onClick.run());
        return btn;
    }

    private static UIElement toolbarBtn(String text, Runnable onClick) {
        Button btn = new Button().setText(text, false);
        btn.textStyle(t -> t.textAlignHorizontal(com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal.CENTER));
        btn.layout(l -> l.width(38).height(14));
        btn.setOnClick(e -> onClick.run());
        return btn;
    }

    private static String countText() {
        if (viewOnline) {
            return tr("mcs.header", totalCount > 0 ? String.valueOf(totalCount) : (items.isEmpty() ? "?" : String.valueOf(items.size())));
        }
        return tr("mcs.local.count", String.valueOf(localCache.size()));
    }

    private static String currentStatus() {
        if (loading) return tr("mcs.status.loading");
        if (errorMessage != null) return tr("mcs.status.failed", truncate(errorMessage, 40));
        return viewOnline ? tr("mcs.status.hint") : tr("mcs.status.hint_local");
    }

    private static void setStatus(String msg) {
        if (statusLabel != null) {
            statusLabel.setText(msg, false);
        }
    }

    private static String tr(String key, Object... args) {
        return PrefabCustomAddon.tr(key, args);
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + ".." : s;
    }

    /**
     * 按像素宽截断 (CJK 字符按 9px, 其余按 6px 估算), 超出补 "..".
     * 卡片加大后让标题尽量显示完整又不溢出 info 列.
     */
    private static String truncateW(String s, int maxPx) {
        if (s == null) return "";
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += s.charAt(i) > 0x2E7F ? 9 : 6;   // CJK 及全角区
            if (w > maxPx) return s.substring(0, i) + "..";
        }
        return s;
    }
}
