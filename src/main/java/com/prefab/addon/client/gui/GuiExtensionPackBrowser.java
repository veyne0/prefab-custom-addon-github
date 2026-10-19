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
import com.mojang.blaze3d.platform.NativeImage;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.client.PackBrowserKeyHandler;
import com.prefab.addon.client.ThumbnailCache;
import com.prefab.addon.work.DependencyChecker;
import com.prefab.addon.work.NbtFormatConverter;
import com.prefab.addon.cloud.CloudBuilding;
import com.prefab.addon.cloud.CloudBuildingClientCache;
import com.prefab.addon.cloud.CloudPreview;
import com.prefab.addon.config.AddonConfig;
import com.prefab.addon.config.CategoryManager;
import com.prefab.addon.config.PlayerPreferences;
import com.prefab.addon.download.PackDownloadManager;
import com.prefab.addon.extension.ConstructionInfo;
import com.prefab.addon.extension.ExtensionPack;
import com.prefab.addon.extension.ExtensionPackManager;
import com.prefab.addon.extension.LocalBuilding;
import com.prefab.addon.extension.LocalBuildingScanner;
import com.prefab.addon.integration.xaero.XaeroWaypointBridge;
import com.prefab.addon.network.BuildingUploadClient;
import com.prefab.addon.network.ServerPackManifestPayload;
import com.prefab.addon.network.ServerPackSyncClient;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * 建筑浏览器 (X/Z 键 / 各详情页"关闭"返回) — LDLib2 ModularUI 整体重写版.
 *
 * <p>布局 (整屏 100%):</p>
 * <pre>
 *   ┌────────┬────────────────────────────────────┐
 *   │ 建筑    │ [搜索框.........................]   │ ← 建筑/收藏 tab 才有
 *   │ 云端建筑│ [状态摘要........] [筛选▾][同步][网页]│ ← 各 tab 顶部行不同
 *   │ 服务器  │ ┌─ 卡片滚动区 (动态多列) ──────────┐ │
 *   │ 收藏    │ │ [图]名称 [图]名称 ...           │ │
 *   │ 下载    │ │      作者/元信息/操作按钮        │ │
 *   │ 提交材料│ └──────────────────────────────┘ │
 *   │        │                     [分类面板(建筑)]│
 *   └────────┴────────────────────────────────────┘
 *   状态栏: 最近一次操作结果 (h=12)
 * </pre>
 *
 * <p>与旧版 (GuiBase 手绘) 的差异:</p>
 * <ul>
 *   <li>整体重写为 LDLib2 ModularUI (照抄 {@link GuiBlueprintMarket} 的静态单实例模式);</li>
 *   <li>「更新日志」tab 按需求去掉, 剩 6 个 tab;</li>
 *   <li>建筑/收藏 tab 恢复旧版翻页浏览 (◀ 页码 ▶, 每页列数×行数按窗口大小自适应);</li>
 *   <li>云端/服务器/下载/提交材料 tab 用滚动列表, 卡片网格按窗口宽度动态定列数;</li>
 *   <li>旧版 pack 钻取 (drilldown) 是死代码 (无任何调用点), 已一并移除.</li>
 * </ul>
 *
 * <p>数据逻辑 (扫描/分类/收藏/云端列表/服务器同步/网站下载/提交材料) 与旧版等价,
 * 静态入口 {@link #open()} / {@link #openForBlueprint(ItemStack, BlockPos)} 签名不变.</p>
 */
public final class GuiExtensionPackBrowser {

    // === 6 个 Tab (更新日志已按需求移除) ===
    public enum Tab {
        BUILDINGS("建筑"),
        CLOUD("云端建筑"),
        SERVERS("服务器"),
        FAVORITES("收藏"),
        DOWNLOAD("下载"),
        SUBMIT("提交材料");
        final String label;
        Tab(String label) { this.label = label; }
    }

    // === 布局常量 ===
    private static final int TAB_W = 62;       // 左侧 tab 列宽
    private static final int THUMB = 48;       // 卡片缩略图边长
    private static final int CAT_W = 104;      // 分类面板宽

    // === 全局状态 (界面单实例, 重开保留) ===
    private static Tab currentTab = Tab.BUILDINGS;
    private static String searchText = "";
    /** 蓝图模式: 右键未绑定自定义蓝图时记录的蓝图物品 (null = 普通模式). */
    private static ItemStack blueprintStack = null;
    /** 蓝图模式: 右键未绑定自定义蓝图时记录的建造位置. */
    private static BlockPos blueprintPos = null;
    /** 每 tab 独立的 分类/搜索/面板收起 状态 (切 tab 互不干扰). */
    private static final Map<Tab, TabState> TAB_STATES = new EnumMap<>(Tab.class);
    private static String currentCategory = null;
    private static boolean categoryPanelHidden = false;
    /** 云端删除确认 (null = 无). */
    private static String pendingDeleteBuildingId = null;
    private static String serverFilter = "unsynced";
    /** 服务器 tab: 每个过滤模式 (all/unsynced/synced) 各自记住的分页页码 */
    private static final java.util.Map<String, Integer> SERVER_PAGE_BY_FILTER = new java.util.HashMap<>();
    /** 本地建筑扫描缓存 (每轮 rebuildList 开头清空, 同一轮内所有建筑共用一次扫描结果) */
    private static List<LocalBuilding> localScanCache = null;

    private static List<LocalBuilding> scanAllCached() {
        if (localScanCache == null) {
            localScanCache = LocalBuildingScanner.scanAll();
        }
        return localScanCache;
    }
    private static String websiteFilter = "all";
    /** 建筑/收藏 tab 的分页页码 (0-based; 切 tab/分类/搜索时归零, 关闭界面保留). */
    private static int constructionPage = 0;
    /** 云端/服务器/下载/提交材料 tab 的分页页码 (切 tab/筛选时归零). */
    private static int cloudPage = 0;
    private static int serverPage = 0;
    private static int websitePage = 0;
    private static int submitPage = 0;

    private static final class TabState {
        final String category;
        final boolean panelHidden;
        final String search;
        final int page;
        /** 服务器 tab 专属: 分页页码 + 过滤模式 (切 tab/重开界面保留) */
        int serverPage;
        String serverFilter;
        TabState(String category, boolean panelHidden, String search, int page) {
            this.category = category;
            this.panelHidden = panelHidden;
            this.search = search;
            this.page = page;
            this.serverPage = 0;
            this.serverFilter = "unsynced";
        }
    }

    // === 预览图/缩略图纹理缓存 (key 见各 ensure 方法) ===
    private static final Map<String, ResourceLocation> PREVIEW_TEX = new HashMap<>();
    private static final Set<String> PREVIEW_LOADING = new HashSet<>();
    private static final Map<String, ResourceLocation> CACHED_THUMB_TEX = new HashMap<>();
    private static final Map<String, ResourceLocation> LOCAL_IMG = new HashMap<>();
    private static final Set<String> LOCAL_IMG_LOADING = new HashSet<>();
    private static final Map<String, ResourceLocation> WEBSITE_IMG = new HashMap<>();
    private static final Set<String> WEBSITE_IMG_LOADING = new HashSet<>();
    private static final Map<String, ResourceLocation> CLOUD_THUMB = new HashMap<>();
    private static final Set<String> CLOUD_THUMB_LOADING = new HashSet<>();
    private static final AtomicInteger TEX_GEN = new AtomicInteger();

    // === 各 tab 数据 ===
    private static List<LocalBuilding> downloadedBuildings = new ArrayList<>();
    private static List<PackDownloadManager.BuildingInfo2> websiteBuildings = new ArrayList<>();
    private static boolean websiteBuildingsLoading = false;
    private static String websiteBuildingsError = null;
    private static final Set<String> WEBSITE_DOWNLOADING = new HashSet<>();
    private static final Map<String, double[]> WEBSITE_PROGRESS = new HashMap<>();
    /** 进度刷新节流: 只在整数百分比变化时重建列表. */
    private static final Map<String, Integer> WEBSITE_LAST_PCT = new HashMap<>();

    // === UI 引用 (重建用, 单实例界面) ===
    private static UIElement rootRef;
    private static UIElement listAreaRef;
    private static Label statusLabel;
    /** 最近一次状态消息 (rebuild 重建 statusLabel 后用它恢复文本). */
    private static String lastStatus;

    private GuiExtensionPackBrowser() {}

    // ==================== 静态入口 (签名与旧版一致) ====================

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        // 普通模式: 清掉可能残留的蓝图状态
        blueprintStack = null;
        blueprintPos = null;
        // 打开时强制重扫拓展包 (旧版构造函数行为)
        ExtensionPackManager.getInstance().reloadIfChanged();
        ExtensionPackManager.getInstance().forceReload();
        // 静态单实例: 所在 tab / 分类 / 搜索 / 页码 全部跨开屏保留
        // (从详情页"关闭"返回时回到原 tab, 不再强制回建筑 tab)
        pendingDeleteBuildingId = null;
        refreshDownloadedBuildings();

        UIElement root = buildRoot();
        ModularUI ui = ModularUI.of(UI.of(root,
            StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)), mc.player);
        mc.setScreen(new ModularUIScreen(ui, Component.literal(
            PrefabCustomAddon.tr("gui.prefab_custom_addon.title"))));
    }

    /**
     * 蓝图模式打开 (右键未绑定的自定义蓝图): 记录蓝图 stack + 建造位置,
     * 之后点建筑卡片直接 {@link CustomStructureGui#openFromBrowser}, 跳过浏览器详情页.
     * 蓝图模式保留上次所在 tab (卡片点击处理是通用的, 任何 tab 都能选建筑).
     */
    public static void openForBlueprint(ItemStack stack, BlockPos pos) {
        open();
        blueprintStack = stack;
        blueprintPos = pos;
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

    /** 整体重建 (切 tab / 分类切换 / 面板收起时调用, 会重建搜索框). */
    private static void rebuild() {
        if (rootRef != null) {
            rebuildAll(rootRef);
        }
    }

    private static void rebuildAll(UIElement root) {
        rootRef = root;
        root.clearAllChildren();

        // 主行: 左 tab 列 + 右内容列
        UIElement main = new UIElement();
        main.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).flexGrow(1).minHeight(0).gapAll(4));
        main.setOverflowVisible(false);
        root.addChild(main);

        // 左 tab 列
        UIElement tabCol = new UIElement();
        tabCol.layout(l -> l.flexDirection(FlexDirection.COLUMN).width(TAB_W).gapAll(3));
        tabCol.setOverflowVisible(false);
        for (Tab t : Tab.values()) {
            Tab ft = t;
            Button tb = new Button().setText(t.label, false);
            tb.textStyle(s -> s.textColor(t == currentTab ? 0xFFDD66 : 0xCCCCCC));
            tb.layout(l -> l.widthPercent(100).height(16));
            tb.setOnClick(e -> switchTab(ft));
            tabCol.addChild(tb);
        }
        main.addChild(tabCol);

        // 右内容列
        UIElement content = new UIElement();
        content.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).minWidth(0).gapAll(3));
        content.setOverflowVisible(false);
        main.addChild(content);

        buildTabHeader(content);

        // 卡片列表区
        UIElement listArea = new UIElement();
        listArea.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).flexGrow(1).minHeight(0).gapAll(4));
        listArea.setOverflowVisible(false);
        listAreaRef = listArea;
        content.addChild(listArea);

        rebuildList(listArea);

        // 状态栏 (h=12)
        UIElement statusRow = new UIElement();
        statusRow.layout(l -> l.widthPercent(100).height(12));
        statusRow.style(s -> s.background(Sprites.RECT_DARK));
        statusRow.setOverflowVisible(false);
        root.addChild(statusRow);
        statusLabel = new Label();
        statusLabel.setText(lastStatus == null ? "" : lastStatus, false);
        statusLabel.textStyle(t -> t.textColor(0xAAAAAA));
        statusLabel.layout(l -> l.heightPercent(100).paddingLeft(3));
        statusRow.addChild(statusLabel);
    }

    /** 各 tab 顶部行 (搜索框 / 状态摘要 / 操作按钮), 最后建好 listAreaRef 供 rebuildList 填充. */
    private static void buildTabHeader(UIElement content) {
        switch (currentTab) {
            case BUILDINGS -> {
                content.addChild(searchRow(tr("browser.search.placeholder_buildings")));
            }
            case FAVORITES -> {
                content.addChild(searchRow(tr("browser.search.placeholder_favorites")));
            }
            case CLOUD -> {
                List<CloudBuilding> list = CloudBuildingClientCache.getInstance().getAll();
                Label summary = new Label();
                summary.setText(cloudSummary(list), false);
                summary.textStyle(t -> t.textColor(0x88BBFF));
                summary.layout(l -> l.widthPercent(100).height(12));
                content.addChild(summary);
            }
            case SERVERS -> {
                // 与建筑 tab 同一份列表 → 给同样的搜索框
                content.addChild(searchRow(tr("browser.search.placeholder_buildings")));
                // 只显示上传状态 (服务器清单在进服时自动收, 不再要手动"同步服务器"按钮)
                UIElement row = new UIElement();
                row.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(14).gapAll(3));
                content.addChild(row);
                Label st = new Label();
                st.setText(serverStatusText(), false);
                st.textStyle(t -> t.textColor(serverStatusColor()));
                st.layout(l -> l.flexGrow(1).heightPercent(100));
                row.addChild(st);
            }
            case DOWNLOAD -> {
                refreshDownloadedBuildings();
                if (websiteBuildings.isEmpty() && !websiteBuildingsLoading) {
                    refreshWebsiteBuildings();
                }
                UIElement row = new UIElement();
                row.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(14).gapAll(3));
                content.addChild(row);
                Label st = new Label();
                st.setText(websiteStatusText(), false);
                st.textStyle(t -> t.textColor(websiteStatusColor()));
                st.layout(l -> l.flexGrow(1).heightPercent(100));
                row.addChild(st);
                // 筛选循环按钮: 全部 → 已下载 → 未下载
                String filtLabel = "all".equals(websiteFilter) ? tr("website.filter.all")
                    : ("downloaded".equals(websiteFilter) ? tr("website.filter.downloaded")
                    : tr("website.filter.not_downloaded"));
                Button filt = new Button().setText("▼ " + filtLabel, false);
                filt.layout(l -> l.width(70).height(14));
                filt.setOnClick(e -> {
                    websiteFilter = "all".equals(websiteFilter) ? "downloaded"
                        : ("downloaded".equals(websiteFilter) ? "not_downloaded" : "all");
                    websitePage = 0;
                    rebuild();
                });
                row.addChild(filt);
                Button web = new Button().setText(tr("website.button.open"), false);
                web.layout(l -> l.width(48).height(14));
                web.setOnClick(e -> openWebsiteHome());
                row.addChild(web);
                // MCS 蓝图站入口 (I 键同款界面)
                Button mcs = new Button().setText("MCS蓝图站", false);
                mcs.layout(l -> l.width(64).height(14));
                mcs.setOnClick(e -> GuiBlueprintMarket.open());
                row.addChild(mcs);
            }
            case SUBMIT -> {
                // 无顶部行 (与旧版一致: 搜索框只在建筑/收藏 tab 显示, 但仍按共享搜索词过滤)
            }
        }
    }

    private static UIElement searchRow(String placeholder) {
        UIElement row = new UIElement();
        row.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(16).gapAll(3));
        row.setOverflowVisible(false);
        TextField search = new TextField();
        search.setText(searchText);
        search.setAnyString();
        search.setTextValidator(s -> s == null || s.length() <= 64);
        search.textFieldStyle(s -> s.textColor(0xFFFFFF).textShadow(false));
        search.layout(l -> l.flexGrow(1).height(16).minHeight(16));
        search.setTextResponder(s -> {
            if (!Objects.equals(searchText, s)) {
                searchText = s == null ? "" : s;
                constructionPage = 0;
                serverPage = 0;
                submitPage = 0;
                rebuildList();
            }
        });
        row.addChild(search);
        Label hint = new Label();
        hint.setText(placeholder, false);
        hint.textStyle(t -> t.textColor(0x666666));
        hint.layout(l -> l.width(70).height(16).alignItems(AlignItems.CENTER));
        row.addChild(hint);
        return row;
    }

    /** 仅重建卡片列表 (搜索/纹理加载/下载完成时调用, 不打断搜索框输入). */
    private static void rebuildList() {
        if (listAreaRef != null) {
            rebuildList(listAreaRef);
        }
    }

    private static void rebuildList(UIElement listArea) {
        listArea.clearAllChildren();
        // 翻页/切过滤会整页重建卡片: 清掉本地扫描缓存, 本轮 rebuild 内只扫一次磁盘
        // (之前 resolveUploadSource 对每个建筑各扫一遍, N+1 全盘扫描是翻页卡顿的根源)
        localScanCache = null;
        // 缩略图缓存批次更新时清掉派生纹理 (与旧版 tick 轮询等价, 挪到重建时机)
        ThumbnailCache.pollCompleted();

        if (currentTab == Tab.BUILDINGS) {
            if (categoryPanelHidden) {
                buildCategoryCollapsed(listArea);
            } else {
                buildCategoryPanel(listArea);
            }
            buildBuildingsGrid(listArea);
        } else if (currentTab == Tab.CLOUD) {
            if (pendingDeleteBuildingId != null) {
                buildDeleteConfirm(listArea);
            } else {
                buildCloudGrid(listArea);
            }
        } else if (currentTab == Tab.SERVERS) {
            buildServersGrid(listArea);
        } else if (currentTab == Tab.FAVORITES) {
            List<ConstructionInfo> favs = ExtensionPackManager.getInstance().getFavoriteConstructions();
            buildConstructionGrid(listArea, filterBySearch(favs, searchText), favs.isEmpty());
        } else if (currentTab == Tab.DOWNLOAD) {
            buildDownloadGrid(listArea);
        } else if (currentTab == Tab.SUBMIT) {
            List<SubmitMaterialIndex.Entry> entries = getSubmitEntries();
            buildSubmitGrid(listArea, entries);
        }
    }

    // ==================== Tab: 建筑 ====================

    /** 分类面板 (右侧, 可收起) + 建筑卡片网格; 两者都塞进 listArea 行里. */
    private static void buildBuildingsGrid(UIElement listArea) {
        List<ConstructionInfo> all = getMergedConstructionsForBuildingsTab();
        List<ConstructionInfo> byCat = filterByCategory(all, currentCategory);
        List<ConstructionInfo> filtered = filterBySearch(byCat, searchText);
        UIElement grid = new UIElement();
        grid.layout(l -> l.flexDirection(FlexDirection.ROW).flexGrow(1).minWidth(0));
        grid.setOverflowVisible(false);
        listArea.addChild(grid);
        fillConstructionGrid(grid, filtered, emptyTitleFor(all.isEmpty()), emptyHintFor(all.isEmpty()));
    }

    private static String emptyTitleFor(boolean noBuildings) {
        if (currentCategory != null) return tr("browser.category.empty_in_category", currentCategory);
        if (searchText != null && !searchText.isEmpty()) return tr("browser.empty.no_match");
        return noBuildings ? tr("browser.empty.no_buildings") : tr("browser.empty.no_match");
    }

    private static String emptyHintFor(boolean noBuildings) {
        if (currentCategory != null) return tr("browser.category.empty_in_category_hint");
        if (searchText != null && !searchText.isEmpty()) return tr("browser.empty.try_clear_search");
        return noBuildings ? tr("browser.empty.no_buildings_hint") : tr("browser.empty.try_clear_search");
    }

    /** 右侧分类列表: 全部 / 未分类 / 自定义分类(跳过 CategoryManager 首项, 与旧版一致) + 管理按钮. */
    private static void buildCategoryPanel(UIElement listArea) {
        UIElement panel = new UIElement();
        panel.layout(l -> l.flexDirection(FlexDirection.COLUMN).width(CAT_W).gapAll(2));
        panel.style(s -> s.background(Sprites.RECT_DARK));
        panel.setOverflowVisible(false);
        listArea.addChild(panel);

        // 标题 + 收起按钮
        UIElement head = new UIElement();
        head.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(14).gapAll(2));
        head.setOverflowVisible(false);
        panel.addChild(head);
        Label title = new Label();
        title.setText(tr("browser.category.title"), false);
        title.textStyle(t -> t.textColor(0xFFFFFF));
        title.layout(l -> l.flexGrow(1).heightPercent(100).paddingLeft(2));
        head.addChild(title);
        Button tg = new Button().setText("◀", false);
        tg.textStyle(s -> s.textColor(0xCCCCCC));
        tg.layout(l -> l.width(16).height(14));
        tg.setOnClick(e -> {
            categoryPanelHidden = true;
            rebuild();
        });
        head.addChild(tg);

        // 分类项
        ScrollerView scroll = new ScrollerView();
        scroll.scrollerStyle(s -> s.mode(ScrollerMode.VERTICAL));
        scroll.verticalScroller(v -> v.setScrollBarSize(5f));
        scroll.layout(l -> l.flexGrow(1).widthPercent(100).minHeight(0));
        panel.addChild(scroll);
        UIElement list = new UIElement();
        list.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100).paddingAll(2).gapAll(2));
        scroll.addScrollViewChild(list);

        list.addChild(categoryItem(tr("browser.category.all"), currentCategory == null, () -> toggleCategory(null)));
        list.addChild(categoryItem("未分类", "未分类".equals(currentCategory), () -> toggleCategory("未分类")));
        List<String> all = CategoryManager.get().getCategories();
        for (int i = 1; i < all.size(); i++) {
            String name = all.get(i);
            list.addChild(categoryItem(name, name.equals(currentCategory), () -> toggleCategory(name)));
        }

        // 管理分类
        Button manage = new Button().setText("+ " + tr("browser.category.manage"), false);
        manage.textStyle(s -> s.textColor(0xCCCCCC));
        manage.layout(l -> l.widthPercent(100).height(14));
        manage.setOnClick(e -> GuiCategoryManager.openStandalone());
        panel.addChild(manage);
    }

    /** 分类收起时的窄条 + 展开按钮 (建筑 tab). */
    private static void buildCategoryCollapsed(UIElement listArea) {
        UIElement panel = new UIElement();
        panel.layout(l -> l.flexDirection(FlexDirection.COLUMN).width(20).gapAll(2));
        panel.style(s -> s.background(Sprites.RECT_DARK));
        panel.setOverflowVisible(false);
        listArea.addChild(panel);
        Button tg = new Button().setText("▶", false);
        tg.textStyle(s -> s.textColor(0xCCCCCC));
        tg.layout(l -> l.widthPercent(100).height(14));
        tg.setOnClick(e -> {
            categoryPanelHidden = false;
            rebuild();
        });
        panel.addChild(tg);
    }

    private static UIElement categoryItem(String label, boolean selected, Runnable onClick) {
        Button item = new Button().setText(truncateW(label, CAT_W - 14), false);
        item.textStyle(s -> s.textColor(selected ? 0xFFFF88 : 0xBBBBBB));
        item.buttonStyle(s -> s.baseTexture(selected ? Sprites.RECT_LIGHT : Sprites.RECT_DARK)
            .hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
        item.layout(l -> l.widthPercent(100).height(14));
        item.setOnClick(e -> onClick.run());
        return item;
    }

    private static void toggleCategory(String cat) {
        currentCategory = Objects.equals(cat, currentCategory) ? null : cat;
        constructionPage = 0;
        rebuild();
    }

    // ==================== Tab: 云端建筑 ====================

    private static String cloudSummary(List<CloudBuilding> list) {
        if (list.isEmpty()) return tr("cloud.status.empty");
        int placed = 0;
        for (CloudBuilding b : list) {
            if (b.placed) placed++;
        }
        return tr("cloud.status.summary", String.valueOf(list.size()),
            String.valueOf(placed), String.valueOf(list.size() - placed));
    }

    private static void buildCloudGrid(UIElement listArea) {
        List<CloudBuilding> list = CloudBuildingClientCache.getInstance().getAll();
        UIElement grid = new UIElement();
        grid.layout(l -> l.flexDirection(FlexDirection.ROW).flexGrow(1).minWidth(0));
        grid.setOverflowVisible(false);
        listArea.addChild(grid);
        if (list.isEmpty()) {
            fillEmpty(grid, tr("cloud.empty.title"), tr("cloud.empty.hint"));
            return;
        }
        ScrollerView scroller = scroller(grid);
        UIElement col = new UIElement();
        col.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100).paddingAll(2).gapAll(4));
        scroller.addScrollViewChild(col);
        int cw = wideCardW();
        int c = wideCols();
        int perPage = Math.max(1, c * rowsPerPageFor(92));
        int totalPages = (list.size() + perPage - 1) / perPage;
        cloudPage = clampPage(cloudPage, totalPages);
        int from = cloudPage * perPage;
        int to = Math.min(from + perPage, list.size());
        for (int i = from; i < to; i += c) {
            UIElement row = new UIElement();
            row.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4));
            for (int j = i; j < Math.min(i + c, to); j++) {
                row.addChild(cloudCard(list.get(j), cw));
            }
            col.addChild(row);
        }
        if (totalPages > 1) {
            col.addChild(pager(totalPages, () -> cloudPage, p -> cloudPage = p));
        }
    }

    /** 云端建筑卡片: 缩略图 + 名称/状态/元信息 + 收回/放出/导航/删除 (卡片本体不可点, 与旧版一致). */
    private static UIElement cloudCard(CloudBuilding b, int cw) {
        UIElement card = new UIElement();
        card.layout(l -> l.flexDirection(FlexDirection.COLUMN).width(cw).height(92)
            .paddingAll(3).gapAll(2));
        card.style(s -> s.background(Sprites.RECT_DARK));
        card.setOverflowVisible(false);

        // 上部: 缩略图 + 信息列 + 右上角导航/删除
        UIElement top = new UIElement();
        top.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).gapAll(4));
        top.setOverflowVisible(false);
        card.addChild(top);

        UIElement thumb = new UIElement();
        thumb.layout(l -> l.width(THUMB).height(THUMB));
        ResourceLocation tex = CLOUD_THUMB.get(b.id);
        if (tex == null && b.thumbnailPng != null && b.thumbnailPng.length > 0) {
            tex = ensureCloudThumbLoaded(b);
        }
        ResourceLocation ftex = tex;
        if (ftex != null) {
            thumb.style(s -> s.backgroundTexture(SpriteTexture.of(ftex)));
        } else {
            thumb.style(s -> s.backgroundTexture(Sprites.RECT_DARK));
            if (b.name != null && !b.name.isEmpty() && !CLOUD_THUMB_LOADING.contains(b.id)) {
                CLOUD_THUMB_LOADING.add(b.id);
                triggerLoadCloudThumb(b);
            }
        }
        top.addChild(thumb);

        UIElement info = new UIElement();
        info.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).gapAll(1).minWidth(0));
        top.addChild(info);
        int textW = cw - THUMB - 16 - 14;
        info.addChild(cardLabel(truncateW(displayName(b.name, tr("cloud.card.unnamed")), textW), 0xFFFFFF, 10));
        info.addChild(cardLabel(b.placed ? tr("cloud.card.placed") : tr("cloud.card.recalled"),
            b.placed ? 0x55FF77 : 0x888888, 9));
        info.addChild(cardLabel(truncateW(cloudMeta(b), textW), 0x999999, 9));
        info.addChild(cardLabel(truncateW(b.placed ? "点收回清理" : "点放出预览", textW), 0x666666, 9));

        UIElement topBtns = new UIElement();
        topBtns.layout(l -> l.flexDirection(FlexDirection.COLUMN).gapAll(2));
        topBtns.setOverflowVisible(false);
        top.addChild(topBtns);
        // 导航按钮 (Xaero 可用且有真实位置才显示, 与旧版一致)
        if (xaeroNavigable(b)) {
            Button nav = new Button().setText("⚑", false);
            nav.textStyle(s -> s.textColor(0x66CCFF));
            nav.layout(l -> l.width(14).height(11));
            nav.setOnClick(e -> cloudNavigate(b));
            topBtns.addChild(nav);
        }
        Button del = new Button().setText("✕", false);
        del.textStyle(s -> s.textColor(0xFF7777));
        del.layout(l -> l.width(14).height(11));
        del.setOnClick(e -> cloudRequestDelete(b));
        topBtns.addChild(del);

        // 底部按钮行: 收回 / 放出
        UIElement btns = new UIElement();
        btns.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).flexGrow(1).gapAll(4));
        btns.setOverflowVisible(false);
        card.addChild(btns);
        Button recall = new Button().setText(tr("cloud.button.recall"), false);
        recall.textStyle(s -> s.textColor(b.placed ? 0xFFFFFF : 0x666666));
        recall.layout(l -> l.widthPercent(50).heightPercent(100));
        recall.setActive(b.placed);
        recall.setOnClick(e -> cloudRecall(b));
        btns.addChild(recall);
        Button summon = new Button().setText(tr("cloud.button.summon"), false);
        summon.textStyle(s -> s.textColor(!b.placed ? 0xFFFFFF : 0x666666));
        summon.layout(l -> l.widthPercent(50).heightPercent(100));
        summon.setActive(!b.placed);
        summon.setOnClick(e -> cloudSummon(b));
        btns.addChild(summon);
        return card;
    }

    private static String displayName(String name, String fallback) {
        return name == null || name.isEmpty() ? fallback : name;
    }

    private static String cloudMeta(CloudBuilding b) {
        String facing;
        if (b.facing == null) {
            facing = "?";
        } else {
            switch (b.facing.getName()) {
                case "north" -> facing = "北";
                case "south" -> facing = "南";
                case "east" -> facing = "东";
                case "west" -> facing = "西";
                default -> facing = b.facing.getName();
            }
        }
        String relTime = formatRelativeTime(b.timestamp)
            .replace("分钟", "分").replace("小时", "时");
        String meta = b.placed && b.placedAt != null
            ? b.blocks.size() + " · " + b.placedAt.toShortString() + " · " + relTime
            : b.blocks.size() + " · " + facing + " · " + relTime;
        return meta + "  " + b.sizeX + "×" + b.sizeY + "×" + b.sizeZ;
    }

    private static boolean xaeroNavigable(CloudBuilding b) {
        boolean hasRealPlacedAt = b.placedAt != null
            && (b.placedAt.getX() != 0 || b.placedAt.getY() != 0 || b.placedAt.getZ() != 0);
        return XaeroWaypointBridge.isAvailable() && hasRealPlacedAt;
    }

    /** 导航: 有航点则移除, 没有则添加 (与旧版逻辑一致). */
    private static void cloudNavigate(CloudBuilding b) {
        ResourceKey<Level> dim;
        try {
            if (b.dimensionId != null && !b.dimensionId.isEmpty()) {
                dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(b.dimensionId));
            } else {
                dim = Minecraft.getInstance().level != null
                    ? Minecraft.getInstance().level.dimension()
                    : ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));
            }
        } catch (Throwable t) {
            dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));
        }
        String wpName = b.placed ? "→ " + b.name : "→ " + b.name + " (已收回)";
        String otherWpName = b.placed ? "→ " + b.name + " (已收回)" : "→ " + b.name;
        boolean exists = XaeroWaypointBridge.hasWaypoint(wpName) || XaeroWaypointBridge.hasWaypoint(otherWpName);
        if (exists) {
            boolean removed = XaeroWaypointBridge.removeWaypoint(wpName);
            if (!removed) removed = XaeroWaypointBridge.removeWaypoint(otherWpName);
            if (removed) {
                setStatus("✗ 已取消航点: " + b.name);
            } else {
                setStatus("✗ 找到航点但无法删除 (Xaero remove API 不兼容?)");
            }
        } else {
            boolean ok = XaeroWaypointBridge.addWaypoint(dim, b.placedAt, wpName, 0xFF5533DD);
            if (ok) {
                setStatus("📍 已标航点: " + b.name + " @ " + b.placedAt.toShortString()
                    + (b.placed ? "" : " (已收回, 历史位置)"));
            } else {
                String reason = XaeroWaypointBridge.consumeLastAddError();
                setStatus("✗ 标航点失败: " + (reason == null || reason.isEmpty() ? "Xaero 不可用" : reason));
            }
        }
    }

    private static void cloudRequestDelete(CloudBuilding b) {
        if (b == null) {
            setStatus("✗ 找不到该云端建筑");
            return;
        }
        if (b.placed) {
            setStatus("建筑已放出, 请先收回再删除");
            return;
        }
        pendingDeleteBuildingId = b.id;
        rebuildList();
    }

    private static void cloudRecall(CloudBuilding b) {
        if (!b.placed) {
            setStatus("该建筑已经是收回状态");
            return;
        }
        CloudBuildingClientCache.getInstance().requestRecall(b.id);
        setStatus("↩ 收回中: " + b.name);
    }

    private static void cloudSummon(CloudBuilding b) {
        if (b.placed) {
            setStatus("已放出 @ " + (b.placedAt == null ? "?" : b.placedAt.toShortString()) + ", 先收回再放出");
            return;
        }
        boolean ok = CloudPreview.start(b.id);
        if (ok) {
            setStatus("☁ 云端预览: " + b.name + " ("
                + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_FORWARD, "↑")
                + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_BACK, "↓")
                + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_LEFT, "←")
                + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_RIGHT, "→")
                + " 移动, " + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_ROTATE, "CTRL")
                + " 旋转, " + PackBrowserKeyHandler.buildKeyName() + " 放出, "
                + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.CANCEL_PREVIEW, "右键") + " 取消)");
        } else {
            setStatus("✗ 开启云端预览失败");
        }
    }

    /** 云端删除确认面板 (替换卡片列表). */
    private static void buildDeleteConfirm(UIElement listArea) {
        CloudBuilding b = CloudBuildingClientCache.getInstance().getById(pendingDeleteBuildingId);
        String name = b != null ? b.name : "?";
        UIElement grid = new UIElement();
        grid.layout(l -> l.flexDirection(FlexDirection.ROW).flexGrow(1).minWidth(0));
        grid.setOverflowVisible(false);
        listArea.addChild(grid);

        UIElement box = new UIElement();
        box.layout(l -> l.flexDirection(FlexDirection.COLUMN).width(200).paddingAll(8).gapAll(6)
            .alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER));
        box.style(s -> s.background(Sprites.RECT_LIGHT));
        box.setOverflowVisible(false);
        grid.addChild(box);

        Label title = new Label();
        title.setText(tr("delete.title"), false);
        title.textStyle(t -> t.textColor(0xFF5555));
        title.layout(l -> l.widthPercent(100).height(10).alignItems(AlignItems.CENTER));
        box.addChild(title);
        Label nameL = new Label();
        nameL.setText(truncate(name, 28), false);
        nameL.textStyle(t -> t.textColor(0xFFFFFF));
        nameL.layout(l -> l.widthPercent(100).height(10).alignItems(AlignItems.CENTER));
        box.addChild(nameL);
        Label body = new Label();
        body.setText(tr("delete.body"), false);
        body.textStyle(t -> t.textColor(0xAAAAAA));
        body.layout(l -> l.widthPercent(100).height(10).alignItems(AlignItems.CENTER));
        box.addChild(body);

        UIElement btns = new UIElement();
        btns.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(16).gapAll(8)
            .alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER));
        btns.setOverflowVisible(false);
        box.addChild(btns);
        Button cancel = new Button().setText(tr("delete.cancel"), false);
        cancel.layout(l -> l.width(72).height(16));
        cancel.setOnClick(e -> {
            pendingDeleteBuildingId = null;
            setStatus("已取消删除");
            rebuildList();
        });
        btns.addChild(cancel);
        Button ok = new Button().setText(tr("delete.confirm"), false);
        ok.textStyle(s -> s.textColor(0xFF8888));
        ok.layout(l -> l.width(72).height(16));
        ok.setOnClick(e -> {
            String id = pendingDeleteBuildingId;
            pendingDeleteBuildingId = null;
            CloudBuildingClientCache.getInstance().requestDelete(id);
            setStatus("✗ 删除中: " + name);
            rebuildList();
        });
        btns.addChild(ok);
    }

    // ==================== Tab: 服务器 ====================

    private static String serverStatusText() {
        // 只反映上传状态 (服务器清单同步是进服自动的后台行为, 不再显示)
        BuildingUploadClient up = BuildingUploadClient.getInstance();
        return switch (up.getState()) {
            case UPLOADING, DONE, ERROR -> up.getStatusMessage();
            default -> tr("server.status.upload_hint");
        };
    }

    private static int serverStatusColor() {
        BuildingUploadClient up = BuildingUploadClient.getInstance();
        return switch (up.getState()) {
            case UPLOADING -> 0x55AAFF;
            case DONE -> 0x55FF55;
            case ERROR -> 0xFF5555;
            default -> 0xAAAAAA;
        };
    }

    // ==================== Tab: 服务器 (本地建筑上传) ====================

    /** 上传判定结果 */
    private static final int UP_MISSING = 0;  // 服务器还没有
    private static final int UP_STALE = 1;    // 服务器有但内容旧 (zip 包可比 SHA-1)
    private static final int UP_SYNCED = 2;   // 已上传 (可建造)
    private static final int UP_NA = 3;       // 找不到可上传的源文件

    /** 本地 zip SHA-1 缓存: path|mtime|size → sha1 (避免每次重建列表都全量读盘算哈希) */
    private static final Map<String, String> ZIP_SHA1_CACHE = new HashMap<>();

    /** 本次游戏内已成功上传的 packName 集合 (manifest 还没推到/没匹配上时的兜底判定) */
    private static final Set<String> LOCALLY_UPLOADED = new HashSet<>();

    /** 上传成功回调 (BuildingUploadClient 在收到服务器最终 ACK 后调用) */
    public static void markUploaded(String fileName) {
        if (fileName != null && fileName.toLowerCase().endsWith(".zip")) {
            LOCALLY_UPLOADED.add(fileName.substring(0, fileName.length() - 4));
        }
    }

    /** 可上传源: packZip != null → 整个 zip 原样上传; 否则 standalone != null → 现场打包 建筑文件+.txt */
    private record UploadSource(Path packZip, LocalBuilding standalone) {}

    /** 服务器 tab 一行数据: 建筑 + 上传源 + 上传状态 */
    private record ServerUploadEntry(ConstructionInfo c, UploadSource src, int state) {}

    /** 解析一个建筑的可上传源; null = 不可上传 (zip 文件丢失/单文件建筑缺 nbt) */
    private static UploadSource resolveUploadSource(ConstructionInfo c) {
        if (c.getPack() != null) {
            ExtensionPack p = c.getPack();
            // __standalone__ 虚拟包: 建筑本体是磁盘上的单文件 (.nbt/.schem/...),
            // 不是 zip, 不能在 zip 分支判定 —— 落到下面的 scanAll 按文件查找
            if (!ExtensionPackManager.STANDALONE_PACKAGE.equals(p.getPackageName())) {
                String fp = p.getFilePath();
                String fn = p.getFileName();
                if (fp != null && fn != null && fn.toLowerCase().endsWith(".zip")) {
                    Path zip = Paths.get(fp);
                    return Files.exists(zip) ? new UploadSource(zip, null) : null;
                }
                // 非 zip 的单文件包 (.litematic/.schem 独立建筑): 同样落到下面 scanAll 按文件查找
            }
        }
        if (c.getLocalNbtPath() != null) {
            for (LocalBuilding lb : scanAllCached()) {
                if (c.getId().equals(lb.id) && lb.filePath != null) {
                    return new UploadSource(null, lb);
                }
            }
        }
        return null;
    }

    /**
     * 从建筑文件的 NBT 调色板提取依赖 modid (上传前校验的兜底):
     * 信息 txt 缺失或漏写依赖时, 方块 id 的 namespace 就是要装的模组.
     * 独立建筑读单文件; zip 包建筑扫描包内全部结构文件取并集.
     */
    private static java.util.List<String> collectPaletteDeps(UploadSource src) {
        java.util.TreeSet<String> mods = new java.util.TreeSet<>();
        try {
            if (src.standalone() != null && src.standalone().filePath != null) {
                mods.addAll(NbtFormatConverter.extractModIds(Files.readAllBytes(src.standalone().filePath)));
            } else if (src.packZip() != null) {
                try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(src.packZip().toFile())) {
                    var entries = zf.entries();
                    while (entries.hasMoreElements()) {
                        java.util.zip.ZipEntry ze = entries.nextElement();
                        if (ze.isDirectory()) continue;
                        String n = ze.getName().toLowerCase();
                        if (n.endsWith(".nbt") || n.endsWith(".litematic")
                            || n.endsWith(".schem") || n.endsWith(".schematic")) {
                            mods.addAll(NbtFormatConverter.extractModIds(zf.getInputStream(ze).readAllBytes()));
                        }
                    }
                }
            }
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("[BUILD-UPLOAD] 解析建筑依赖 (palette) 失败 (非致命): {}", e.getMessage());
        }
        return new java.util.ArrayList<>(mods);
    }

    /** 本地 zip 的 SHA-1 (带 mtime+size 缓存) */
    private static String cachedZipSha1(Path zip) {
        try {
            String key = zip + "|" + Files.getLastModifiedTime(zip).toMillis() + "|" + Files.size(zip);
            return ZIP_SHA1_CACHE.computeIfAbsent(key, k -> ExtensionPackManager.computeSha1Hex(zip));
        } catch (Exception e) {
            return null;
        }
    }

    /** 判定一个建筑的同步状态: 与服务端 manifest (packName+buildingId) 匹配, zip 包再比 SHA-1 */
    private static int uploadState(ConstructionInfo c, UploadSource src) {
        if (src == null) return UP_NA;
        String buildingId = c.getId();
        String packName;
        String localSha1 = null;
        if (src.packZip() != null) {
            String fn = src.packZip().getFileName().toString();
            packName = fn.substring(0, fn.length() - 4);
            localSha1 = cachedZipSha1(src.packZip());
        } else {
            // 独立建筑: 上传后 zip 名 = <id>.zip, packName = id
            packName = buildingId;
        }
        for (ServerPackManifestPayload.Entry e :
                ServerPackSyncClient.getInstance().getServerManifestSnapshot()) {
            if (!buildingId.equals(e.buildingId())) continue;
            if (packName == null || !packName.equals(e.packName())) continue;
            if (localSha1 != null && e.sha1() != null && !e.sha1().isEmpty()) {
                return e.sha1().equalsIgnoreCase(localSha1) ? UP_SYNCED : UP_STALE;
            }
            // 独立建筑: zip 是现场打包的, 只按 manifest 存在性判定
            return UP_SYNCED;
        }
        // manifest 兜底: 刚上传成功、服务器清单还没刷新到时, 按本地成功记录判定
        if (LOCALLY_UPLOADED.contains(packName)) return UP_SYNCED;
        return UP_MISSING;
    }

    /** 服务器 tab 全量数据: 与「建筑」tab 同一份列表 (含搜索过滤), 逐个算上传源和状态 */
    private static List<ServerUploadEntry> collectServerUploadEntries() {
        List<ConstructionInfo> all = filterBySearch(getMergedConstructionsForBuildingsTab(), searchText);
        List<ServerUploadEntry> out = new ArrayList<>(all.size());
        for (ConstructionInfo c : all) {
            UploadSource src = resolveUploadSource(c);
            out.add(new ServerUploadEntry(c, src, uploadState(c, src)));
        }
        return out;
    }

    private static List<ServerUploadEntry> filterServerUploads(List<ServerUploadEntry> all) {
        List<ServerUploadEntry> filtered = new ArrayList<>();
        for (ServerUploadEntry e : all) {
            if ("unsynced".equals(serverFilter) && e.state() != UP_MISSING && e.state() != UP_STALE) continue;
            if ("synced".equals(serverFilter) && e.state() != UP_SYNCED) continue;
            filtered.add(e);
        }
        return filtered;
    }

    private static void buildServersGrid(UIElement listArea) {
        UIElement grid = new UIElement();
        grid.layout(l -> l.flexDirection(FlexDirection.ROW).flexGrow(1).minWidth(0).gapAll(4));
        grid.setOverflowVisible(false);
        listArea.addChild(grid);

        if (Minecraft.getInstance().getCurrentServer() == null) {
            fillEmpty(grid, tr("server.only_multiplayer.title"), tr("server.only_multiplayer.hint"));
            return;
        }
        List<ServerUploadEntry> all = collectServerUploadEntries();

        // 中列: 过滤 tab + 卡片列表 (和「建筑」tab 同一份内容, 卡片多一个上传按钮)
        UIElement mid = new UIElement();
        mid.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).minWidth(0).gapAll(3));
        mid.setOverflowVisible(false);
        grid.addChild(mid);

        int syncedCount = 0;
        int missingCount = 0;
        for (ServerUploadEntry e : all) {
            if (e.state() == UP_SYNCED) syncedCount++;
            else if (e.state() != UP_NA) missingCount++;
        }
        UIElement filters = new UIElement();
        filters.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(14).gapAll(4));
        filters.setOverflowVisible(false);
        mid.addChild(filters);
        String[] modes = {"all", "unsynced", "synced"};
        String[] labels = {
            tr("server.filter.all", String.valueOf(all.size())),
            tr("server.filter.unsynced", String.valueOf(missingCount)),
            tr("server.filter.synced", String.valueOf(syncedCount))};
        // 管理按钮仅 OP 可见 (有权限打开管理界面: 删除建筑 / 玩家上传权限)
        boolean isOp = Minecraft.getInstance().player != null
                && Minecraft.getInstance().player.hasPermissions(2);
        for (int i = 0; i < 3; i++) {
            final String mode = modes[i];
            Button f = new Button().setText(labels[i], false);
            f.textStyle(s -> s.textColor(mode.equals(serverFilter) ? 0xFFFF88 : 0xBBBBBB));
            f.buttonStyle(s -> s.baseTexture(mode.equals(serverFilter) ? Sprites.RECT_LIGHT : Sprites.RECT_DARK)
                .hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
            if (isOp) {
                f.layout(l -> l.flexGrow(1).height(14).minWidth(0));
            } else {
                f.layout(l -> l.widthPercent(33).height(14));
            }
            f.setOnClick(e -> {
                // 切过滤: 记住当前过滤的页码, 恢复目标过滤的页码 (来回切不丢分页)
                SERVER_PAGE_BY_FILTER.put(serverFilter, serverPage);
                serverFilter = mode;
                serverPage = SERVER_PAGE_BY_FILTER.getOrDefault(mode, 0);
                rebuild();
            });
            filters.addChild(f);
        }
        if (isOp) {
            Button manage = new Button().setText("管理", false);
            manage.textStyle(s -> s.textColor(0xFFCC66));
            manage.buttonStyle(s -> s.baseTexture(Sprites.RECT_DARK)
                .hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
            manage.layout(l -> l.width(36).height(14));
            manage.setOnClick(e -> GuiServerManage.open());
            filters.addChild(manage);
        }

        if (all.isEmpty()) {
            fillEmpty(mid, tr("server.empty.title"), tr("server.empty.hint"));
            return;
        }

        List<ServerUploadEntry> filtered = filterServerUploads(all);
        if (filtered.isEmpty()) {
            String emptyTitle = "unsynced".equals(serverFilter) ? tr("server.filter.empty_synced")
                : ("synced".equals(serverFilter) ? tr("server.filter.empty_unsynced") : tr("msg.no_match"));
            String emptyHint = "unsynced".equals(serverFilter) ? tr("server.filter.empty_synced_hint")
                : ("synced".equals(serverFilter) ? tr("server.filter.empty_unsynced_hint") : tr("msg.try_filter"));
            fillEmpty(mid, emptyTitle, emptyHint);
            return;
        }
        ScrollerView scroller = scroller(mid);
        UIElement col = new UIElement();
        col.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100).paddingAll(2).gapAll(4));
        scroller.addScrollViewChild(col);
        int cw = wideCardW();
        int c = wideCols();
        int perPage = Math.max(1, c * rowsPerPageFor(84));
        int totalPages = (filtered.size() + perPage - 1) / perPage;
        serverPage = clampPage(serverPage, totalPages);
        int from = serverPage * perPage;
        int to = Math.min(from + perPage, filtered.size());
        for (int i = from; i < to; i += c) {
            UIElement row = new UIElement();
            row.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4));
            for (int j = i; j < Math.min(i + c, to); j++) {
                row.addChild(serverUploadCard(filtered.get(j), cw));
            }
            col.addChild(row);
        }
        if (totalPages > 1) {
            col.addChild(pager(totalPages, () -> serverPage, p -> serverPage = p));
        }
    }

    /** 服务器 tab 卡片: 缩略图+名称+状态; 底部 = 上传按钮 / 已同步标签; 已同步点卡片直接进详情. */
    private static UIElement serverUploadCard(ServerUploadEntry e, int cw) {
        ConstructionInfo c = e.c();
        Button card = new Button().noText();
        card.buttonStyle(s -> s.baseTexture(Sprites.RECT_DARK)
            .hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
        card.layout(l -> l.flexDirection(FlexDirection.COLUMN).width(cw).height(84)
            .paddingAll(3).gapAll(2));
        card.setOverflowVisible(false);

        // 上部: 缩略图 (60px) + 名称/大小
        UIElement top = new UIElement();
        top.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(60).gapAll(4));
        top.setOverflowVisible(false);
        card.addChild(top);

        UIElement thumb = new UIElement();
        thumb.layout(l -> l.width(60).height(60));
        ResourceLocation tex = null;
        if (c.hasPreviewImage()) {
            tex = ensurePreviewTextureLoaded(c);
        }
        if (tex == null && ThumbnailCache.hasCached(c)) {
            byte[] pngData = ThumbnailCache.read(c);
            if (pngData != null) {
                tex = ensureCachedThumbnailTextureLoaded(c, pngData);
            }
        }
        ResourceLocation ftex = tex;
        thumb.style(s -> s.backgroundTexture(ftex != null ? SpriteTexture.of(ftex) : Sprites.RECT_DARK));
        top.addChild(thumb);

        UIElement info = new UIElement();
        info.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).gapAll(1).minWidth(0));
        top.addChild(info);
        int textW = cw - 60 - 14;
        info.addChild(cardLabel(truncateW(c.getName(), textW), 0xFFFFFF, 10));
        long srcSize = e.src() != null && e.src().packZip() != null
            ? cachedZipSize(e.src().packZip()) : 0;
        String sizeText = srcSize > 0 ? formatFileSize(srcSize)
            : (e.src() != null && e.src().standalone() != null
                ? formatFileSize(e.src().standalone().fileSize) : "-");
        info.addChild(cardLabel(truncateW(c.getFormat() + " · " + sizeText, textW), 0x777777, 9));

        // 底部: 状态按钮
        BuildingUploadClient up = BuildingUploadClient.getInstance();
        if (e.state() == UP_SYNCED) {
            UIElement tag = new UIElement();
            tag.layout(l -> l.widthPercent(100).height(16).alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER));
            tag.style(s -> s.backgroundTexture(Sprites.RECT_LIGHT));
            tag.setOverflowVisible(false);
            card.addChild(tag);
            Label lbl = new Label();
            lbl.setText(tr("server.badge.synced"), false);
            lbl.textStyle(t -> t.textColor(0x55FF77));
            lbl.layout(l -> l.widthPercent(100).heightPercent(100).alignItems(AlignItems.CENTER)
                .justifyContent(AlignContent.CENTER));
            tag.addChild(lbl);
            // 已上传: 点卡片 → 打开服务器副本详情 (建造走服务端 packName)
            card.setOnClick(ev -> openServerBuildingDetail(e));
        } else if (e.state() == UP_NA) {
            info.addChild(cardLabel(tr("server.badge.na"), 0x666666, 10));
        } else {
            boolean stale = e.state() == UP_STALE;
            Button act = new Button().setText(tr(stale ? "server.button.reupload" : "server.button.upload"), false);
            act.layout(l -> l.widthPercent(100).height(16));
            act.setActive(!up.isUploading());
            act.setOnClick(ev -> {
                BuildingUploadClient client = BuildingUploadClient.getInstance();
                if (client.isUploading()) {
                    setStatus(tr("upload.err.busy"));
                    return;
                }
                // 依赖缺失的建筑禁止上传: 传上去别人也建不了 (服务端同样缺依赖时解析会失败)
                // txt 里的依赖 + 直接解析建筑文件 NBT palette 的依赖 (txt 缺失/漏记时兜底)
                java.util.LinkedHashSet<String> depSet = new java.util.LinkedHashSet<>();
                if (e.c().getDependencies() != null) {
                    depSet.addAll(e.c().getDependencies());
                }
                depSet.addAll(collectPaletteDeps(e.src()));
                if (!depSet.isEmpty()) {
                    DependencyChecker.CheckResult cr = DependencyChecker.check(new java.util.ArrayList<>(depSet));
                    if (!cr.missing.isEmpty()) {
                        setStatus(tr("server.upload.err.deps", String.join(", ", cr.missing)));
                        return;
                    }
                }
                UploadSource src = e.src();
                if (src.packZip() != null) {
                    client.startPack(src.packZip());
                } else {
                    client.startStandalone(src.standalone());
                }
            });
            card.addChild(act);
        }
        return card;
    }

    /** zip 文件大小 (失败返回 -1) */
    private static long cachedZipSize(Path zip) {
        try {
            return Files.size(zip);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 已上传建筑 → 点开详情. 直接用本地建筑数据 (预览/详情读本地文件, 不需要任何副本):
     *  <ul>
     *    <li>zip 包建筑: 本地包名与服务端一致 (同一个 zip), 建造 payload 直接命中服务端</li>
     *    <li>独立建筑: payload 带 STANDALONE_PACKAGE, 服务端 findConstruction 按建筑 id
     *        去 server-cache 的上传 zip 里兜底查找</li>
     *  </ul> */
    private static void openServerBuildingDetail(ServerUploadEntry e) {
        openConstructionDetail(e.c());
    }

    /** BuildingUploadClient 状态回调 (主线程): 刷新状态栏; structural=true 时重建列表 (按钮状态变化) */
    public static void onUploadProgress(boolean structural) {
        BuildingUploadClient up = BuildingUploadClient.getInstance();
        setStatus(up.getStatusMessage());
        if (structural && currentTab == Tab.SERVERS) {
            rebuildList();
        }
    }

    // ==================== Tab: 收藏 / 建筑 共用卡片网格 ====================

    /** 通用建筑卡片网格 (建筑 tab 的分类过滤已经做完; favorites 传收藏列表). */
    private static void buildConstructionGrid(UIElement listArea, List<ConstructionInfo> filtered, boolean sourceEmpty) {
        UIElement grid = new UIElement();
        grid.layout(l -> l.flexDirection(FlexDirection.ROW).flexGrow(1).minWidth(0));
        grid.setOverflowVisible(false);
        listArea.addChild(grid);
        String emptyTitle = sourceEmpty && filtered.isEmpty() && (searchText == null || searchText.isEmpty())
            ? tr("favorites.empty.title") : emptyTitleFor(false);
        String emptyHint = sourceEmpty && filtered.isEmpty() && (searchText == null || searchText.isEmpty())
            ? tr("favorites.empty.hint") : emptyHintFor(false);
        fillConstructionGrid(grid, filtered, emptyTitle, emptyHint);
    }

    /** 建筑/收藏共用分页网格: 每页 cols×rowsPerPage 张卡片, 底部 ◀ 页码 ▶. */
    private static void fillConstructionGrid(UIElement area, List<ConstructionInfo> list, String emptyTitle, String emptyHint) {
        if (list.isEmpty()) {
            fillEmpty(area, emptyTitle, emptyHint);
            return;
        }
        boolean withCat = currentTab == Tab.BUILDINGS && !categoryPanelHidden;
        int c = cols(withCat);
        int cw = cardW(withCat);
        int perPage = Math.max(1, c * rowsPerPage());
        int totalPages = (list.size() + perPage - 1) / perPage;
        constructionPage = clampPage(constructionPage, totalPages);
        int from = constructionPage * perPage;
        int to = Math.min(from + perPage, list.size());

        UIElement pageCol = new UIElement();
        pageCol.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).minWidth(0).gapAll(3));
        pageCol.setOverflowVisible(false);
        area.addChild(pageCol);

        UIElement rowsWrap = new UIElement();
        rowsWrap.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100)
            .flexGrow(1).minHeight(0).gapAll(4));
        rowsWrap.setOverflowVisible(false);
        pageCol.addChild(rowsWrap);
        for (int i = from; i < to; i += c) {
            UIElement row = new UIElement();
            row.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4));
            for (int j = i; j < Math.min(i + c, to); j++) {
                row.addChild(constructionCard(list.get(j), cw));
            }
            rowsWrap.addChild(row);
        }

        // 分页条: ◀ 页码/总页数 ▶ (只有一页时不显示)
        if (totalPages > 1) {
            pageCol.addChild(pager(totalPages, () -> constructionPage, p -> constructionPage = p));
        }
    }

    /** 建筑卡片: 缩略图占满上部 + 收藏星 + 名称; 点卡片进 CustomStructureGui 详情. */
    private static UIElement constructionCard(ConstructionInfo c, int cw) {
        Button card = new Button().noText();
        card.buttonStyle(s -> s.baseTexture(Sprites.RECT_DARK)
            .hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
        card.layout(l -> l.flexDirection(FlexDirection.COLUMN).width(cw).height(84));
        card.setOverflowVisible(false);

        UIElement thumb = new UIElement();
        thumb.layout(l -> l.widthPercent(100).height(64));
        ResourceLocation tex = null;
        if (c.hasPreviewImage()) {
            tex = ensurePreviewTextureLoaded(c);
        }
        if (tex == null && ThumbnailCache.hasCached(c)) {
            byte[] pngData = ThumbnailCache.read(c);
            if (pngData != null) {
                tex = ensureCachedThumbnailTextureLoaded(c, pngData);
            }
        }
        ResourceLocation ftex = tex;
        if (ftex != null) {
            thumb.style(s -> s.backgroundTexture(SpriteTexture.of(ftex)));
        } else {
            thumb.style(s -> s.backgroundTexture(Sprites.RECT_DARK));
        }
        card.addChild(thumb);

        String packKey = c.getPack() == null ? "__standalone__" : c.getPack().getPackageName();
        boolean isFav = PlayerPreferences.get().isFavorite(packKey, c.getId());
        Label name = new Label();
        name.setText((isFav ? "★ " : "") + truncate(c.getName(), 10), false);
        name.textStyle(t -> t.textColor(isFav ? 0xFFFF44 : 0xFFFFFF));
        name.layout(l -> l.widthPercent(100).height(12).alignItems(AlignItems.CENTER)
            .justifyContent(AlignContent.CENTER));
        card.addChild(name);

        card.setOnClick(e -> openConstructionDetail(c));
        return card;
    }

    // ==================== Tab: 下载 ====================

    private static String websiteStatusText() {
        if (websiteBuildingsLoading) return tr("website.status.loading");
        if (websiteBuildingsError != null) return tr("website.status.failed", truncate(websiteBuildingsError, 16));
        if (websiteBuildings.isEmpty()) return tr("website.status.empty");
        return tr("website.status.summary", String.valueOf(websiteBuildings.size()));
    }

    private static int websiteStatusColor() {
        if (websiteBuildingsLoading) return 0x55AAFF;
        if (websiteBuildingsError != null) return 0xFF5555;
        return 0xAAAAAA;
    }

    private static List<PackDownloadManager.BuildingInfo2> filteredWebsiteBuildings() {
        List<PackDownloadManager.BuildingInfo2> result = new ArrayList<>();
        for (PackDownloadManager.BuildingInfo2 b : websiteBuildings) {
            if (b == null || b.id == null) continue;
            boolean downloaded = PackDownloadManager.getInstance().isBuildingDownloaded(b);
            if ("all".equals(websiteFilter)
                || ("downloaded".equals(websiteFilter) && downloaded)
                || ("not_downloaded".equals(websiteFilter) && !downloaded)) {
                result.add(b);
            }
        }
        result.sort((a, b2) -> {
            boolean da = PackDownloadManager.getInstance().isBuildingDownloaded(a);
            boolean db = PackDownloadManager.getInstance().isBuildingDownloaded(b2);
            if (da != db) return da ? -1 : 1;
            return 0;
        });
        return result;
    }

    private static void buildDownloadGrid(UIElement listArea) {
        UIElement grid = new UIElement();
        grid.layout(l -> l.flexDirection(FlexDirection.ROW).flexGrow(1).minWidth(0));
        grid.setOverflowVisible(false);
        listArea.addChild(grid);
        if (websiteBuildings.isEmpty()) {
            String emptyTitle;
            String emptyHint;
            if (websiteBuildingsError != null) {
                emptyTitle = tr("website.empty.failed");
                emptyHint = tr("website.empty.failed_hint");
            } else if (websiteBuildingsLoading) {
                emptyTitle = tr("website.empty.loading");
                emptyHint = tr("website.empty.loading_hint");
            } else {
                emptyTitle = tr("website.empty.empty");
                emptyHint = tr("website.empty.empty_hint");
            }
            fillEmpty(grid, emptyTitle, emptyHint);
            return;
        }
        List<PackDownloadManager.BuildingInfo2> filtered = filteredWebsiteBuildings();
        if (filtered.isEmpty()) {
            String emptyTitle;
            String emptyHint;
            if ("not_downloaded".equals(websiteFilter)) {
                emptyTitle = tr("website.empty.all_downloaded");
                emptyHint = tr("website.empty.all_downloaded_hint");
            } else if ("downloaded".equals(websiteFilter)) {
                emptyTitle = tr("website.empty.no_downloaded");
                emptyHint = tr("website.empty.no_downloaded_hint");
            } else {
                emptyTitle = tr("website.empty.empty");
                emptyHint = tr("website.empty.empty_hint");
            }
            fillEmpty(grid, emptyTitle, emptyHint);
            return;
        }
        ScrollerView scroller = scroller(grid);
        UIElement col = new UIElement();
        col.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100).paddingAll(2).gapAll(4));
        scroller.addScrollViewChild(col);
        int cw = wideCardW();
        int c = wideCols();
        int perPage = Math.max(1, c * rowsPerPageFor(72));
        int totalPages = (filtered.size() + perPage - 1) / perPage;
        websitePage = clampPage(websitePage, totalPages);
        int from = websitePage * perPage;
        int to = Math.min(from + perPage, filtered.size());
        for (int i = from; i < to; i += c) {
            UIElement row = new UIElement();
            row.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4));
            for (int j = i; j < Math.min(i + c, to); j++) {
                row.addChild(websiteCard(filtered.get(j), cw));
            }
            col.addChild(row);
        }
        if (totalPages > 1) {
            col.addChild(pager(totalPages, () -> websitePage, p -> websitePage = p));
        }
    }

    /** 网站建筑卡片: 已下载 → 点卡片进详情 (底部"已下载"标签); 未下载 → 底部"下载"按钮. */
    private static UIElement websiteCard(PackDownloadManager.BuildingInfo2 b, int cw) {
        boolean downloaded = isWebsiteBuildingDownloaded(b);
        boolean downloading = WEBSITE_DOWNLOADING.contains(b.id);
        // 卡片本体用 Button (纯 UIElement 没有 setOnClick); 未下载分支有子按钮, 不给卡片设 onClick
        Button card = new Button().noText();
        card.buttonStyle(s -> s.baseTexture(Sprites.RECT_DARK)
            .hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
        card.layout(l -> l.flexDirection(FlexDirection.COLUMN).width(cw).height(72)
            .paddingAll(3).gapAll(2));
        card.setOverflowVisible(false);

        UIElement top = new UIElement();
        top.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).gapAll(4));
        top.setOverflowVisible(false);
        card.addChild(top);

        UIElement thumb = new UIElement();
        thumb.layout(l -> l.width(36).height(36));
        ResourceLocation tex = WEBSITE_IMG.get(b.id);
        if (tex != null) {
            thumb.style(s -> s.backgroundTexture(SpriteTexture.of(tex)));
        } else {
            thumb.style(s -> s.backgroundTexture(Sprites.RECT_DARK));
        }
        top.addChild(thumb);

        UIElement info = new UIElement();
        info.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).gapAll(1).minWidth(0));
        top.addChild(info);
        int textW = cw - 36 - 14;
        info.addChild(cardLabel(truncateW(displayName(b.name, b.id), textW), 0xFFFFFF, 10));
        info.addChild(cardLabel(truncateW("by " + (b.author == null || b.author.isEmpty()
            ? tr("website.author.unknown") : b.author), textW), 0x888888, 9));
        String meta = (b.fileExt == null || b.fileExt.isEmpty() ? ".nbt" : b.fileExt)
            + " · " + formatFileSize(b.fileSize) + (b.downloads > 0 ? " · ↓" + b.downloads : "");
        info.addChild(cardLabel(truncateW(meta, textW), 0x777777, 9));

        if (downloading) {
            double[] prog = WEBSITE_PROGRESS.get(b.id);
            String label = prog != null ? tr("website.badge.downloading", String.valueOf((int) prog[2]))
                : tr("website.status.loading");
            UIElement tag = new UIElement();
            tag.layout(l -> l.widthPercent(100).height(16).alignItems(AlignItems.CENTER)
                .justifyContent(AlignContent.CENTER));
            tag.style(s -> s.backgroundTexture(Sprites.RECT_LIGHT));
            tag.setOverflowVisible(false);
            card.addChild(tag);
            Label lbl = new Label();
            lbl.setText(label, false);
            lbl.textStyle(t -> t.textColor(0xAAAAAA));
            lbl.layout(l -> l.widthPercent(100).heightPercent(100).alignItems(AlignItems.CENTER)
                .justifyContent(AlignContent.CENTER));
            tag.addChild(lbl);
        } else if (downloaded) {
            UIElement tag = new UIElement();
            tag.layout(l -> l.widthPercent(100).height(16).alignItems(AlignItems.CENTER)
                .justifyContent(AlignContent.CENTER));
            tag.style(s -> s.backgroundTexture(Sprites.RECT_LIGHT));
            tag.setOverflowVisible(false);
            card.addChild(tag);
            Label lbl = new Label();
            lbl.setText(tr("website.badge.downloaded"), false);
            lbl.textStyle(t -> t.textColor(0x55FF55));
            lbl.layout(l -> l.widthPercent(100).heightPercent(100).alignItems(AlignItems.CENTER)
                .justifyContent(AlignContent.CENTER));
            tag.addChild(lbl);
            card.setOnClick(e -> openDownloadedBuildingDetail(b));
        } else {
            Button dl = new Button().setText(tr("website.button.download"), false);
            dl.layout(l -> l.widthPercent(100).height(16));
            dl.setOnClick(e -> startWebsiteDownload(b));
            card.addChild(dl);
        }
        return card;
    }

    // ==================== Tab: 提交材料 ====================

    /** 提交材料条目列表 (含搜索词过滤): "交了一点但没交完"的建筑. */
    private static List<SubmitMaterialIndex.Entry> getSubmitEntries() {
        var pl = Minecraft.getInstance().player;
        List<SubmitMaterialIndex.Entry> entries = pl == null ? List.of()
            : SubmitMaterialIndex.collect(getMergedConstructionsForBuildingsTab(), pl.getUUID());
        if (!searchText.isEmpty()) {
            String q = searchText.toLowerCase();
            List<SubmitMaterialIndex.Entry> filtered = new ArrayList<>(entries.size());
            for (SubmitMaterialIndex.Entry en : entries) {
                if (en.construction.getName().toLowerCase().contains(q)) {
                    filtered.add(en);
                }
            }
            entries = filtered;
        }
        return entries;
    }

    private static void buildSubmitGrid(UIElement listArea, List<SubmitMaterialIndex.Entry> entries) {
        UIElement grid = new UIElement();
        grid.layout(l -> l.flexDirection(FlexDirection.ROW).flexGrow(1).minWidth(0));
        grid.setOverflowVisible(false);
        listArea.addChild(grid);
        if (entries.isEmpty()) {
            fillEmpty(grid, tr("submit.empty.title"), tr("submit.empty.hint"));
            return;
        }
        ScrollerView scroller = scroller(grid);
        UIElement col = new UIElement();
        col.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100).paddingAll(2).gapAll(4));
        scroller.addScrollViewChild(col);
        int cw = cardW(false);
        int c = cols(false);
        int perPage = Math.max(1, c * rowsPerPageFor(84));
        int totalPages = (entries.size() + perPage - 1) / perPage;
        submitPage = clampPage(submitPage, totalPages);
        int from = submitPage * perPage;
        int to = Math.min(from + perPage, entries.size());
        for (int i = from; i < to; i += c) {
            UIElement row = new UIElement();
            row.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4));
            for (int j = i; j < Math.min(i + c, to); j++) {
                row.addChild(submitCard(entries.get(j), cw));
            }
            col.addChild(row);
        }
        if (totalPages > 1) {
            col.addChild(pager(totalPages, () -> submitPage, p -> submitPage = p));
        }
    }

    /** 提交进度卡片: 缩略图 + 名称 + 进度 + 状态; 交齐绿框提示可建造. */
    private static UIElement submitCard(SubmitMaterialIndex.Entry en, int cw) {
        Button card = new Button().noText();
        card.buttonStyle(s -> s.baseTexture(Sprites.RECT_DARK)
            .hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
        card.layout(l -> l.flexDirection(FlexDirection.COLUMN).width(cw).height(84).paddingAll(3).gapAll(2));
        card.setOverflowVisible(false);

        UIElement top = new UIElement();
        top.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).gapAll(4));
        top.setOverflowVisible(false);
        card.addChild(top);

        // 缩略图 (预览图 → 缩略图缓存, 与建筑卡片同源)
        UIElement thumb = new UIElement();
        thumb.layout(l -> l.width(40).height(40));
        ResourceLocation tex = null;
        if (en.construction.hasPreviewImage()) {
            tex = ensurePreviewTextureLoaded(en.construction);
        }
        if (tex == null && ThumbnailCache.hasCached(en.construction)) {
            byte[] pngData = ThumbnailCache.read(en.construction);
            if (pngData != null) {
                tex = ensureCachedThumbnailTextureLoaded(en.construction, pngData);
            }
        }
        ResourceLocation ftex = tex;
        if (ftex != null) {
            thumb.style(s -> s.backgroundTexture(SpriteTexture.of(ftex)));
        } else {
            thumb.style(s -> s.backgroundTexture(Sprites.RECT_DARK));
        }
        top.addChild(thumb);

        UIElement info = new UIElement();
        info.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).gapAll(1).minWidth(0));
        top.addChild(info);
        info.addChild(cardLabel(truncate(en.construction.getName(), 8), 0xFFFFFF, 10));
        if (en.needTotal > 0) {
            info.addChild(cardLabel(en.haveTotal + "/" + en.needTotal, 0xAAAAAA, 9));
        } else {
            info.addChild(cardLabel("已提 " + en.haveTotal, 0x888888, 9));
        }

        // 进度条 (底部横条): fill 作为 bar 的子元素, 按百分比填充
        if (en.needTotal > 0) {
            UIElement bar = new UIElement();
            bar.layout(l -> l.widthPercent(100).height(6));
            bar.style(s -> s.backgroundTexture(Sprites.RECT_DARK));
            bar.setOverflowVisible(false);
            card.addChild(bar);
            UIElement fill = new UIElement();
            int pct = Math.max(2, Math.round(en.progress() * 100.0f));
            fill.layout(l -> l.widthPercent(pct).heightPercent(100));
            fill.style(s -> s.backgroundTexture(en.ready ? Sprites.RECT_LIGHT : Sprites.RECT_LIGHT));
            bar.addChild(fill);
        }

        String status;
        int statusColor;
        if (en.ready) {
            status = "✓ " + tr("submit.ready");
            statusColor = 0x55FF55;
        } else if (en.needTotal > 0) {
            status = tr("submit.remaining", String.valueOf(en.needTotal - en.haveTotal));
            statusColor = 0xFFAA55;
        } else {
            status = tr("submit.unknown", String.valueOf(en.submitted.size()));
            statusColor = 0x888888;
        }
        card.addChild(cardLabel(status, statusColor, 9));

        card.setOnClick(e -> openConstructionDetail(en.construction));
        return card;
    }

    // ==================== 通用小部件 ====================

    private static ScrollerView scroller(UIElement parent) {
        ScrollerView scroll = new ScrollerView();
        scroll.scrollerStyle(s -> s.mode(ScrollerMode.VERTICAL));
        scroll.verticalScroller(v -> v.setScrollBarSize(6f));
        scroll.layout(l -> l.flexGrow(1).widthPercent(100).minHeight(0));
        parent.addChild(scroll);
        return scroll;
    }

    private static void fillEmpty(UIElement area, String title, String hint) {
        UIElement box = new UIElement();
        box.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).widthPercent(100)
            .alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER).gapAll(3));
        area.addChild(box);
        Label t = new Label();
        t.setText(title, false);
        t.textStyle(s -> s.textColor(0xAAAAAA));
        t.layout(l -> l.widthPercent(100).height(12).alignItems(AlignItems.CENTER)
            .justifyContent(AlignContent.CENTER));
        box.addChild(t);
        Label h = new Label();
        h.setText(hint, false);
        h.textStyle(s -> s.textColor(0x777777));
        h.layout(l -> l.widthPercent(100).height(12).alignItems(AlignItems.CENTER)
            .justifyContent(AlignContent.CENTER));
        box.addChild(h);
    }

    private static UIElement cardLabel(String text, int color, int height) {
        Label l = new Label();
        l.setText(text, false);
        l.textStyle(t -> t.textColor(color));
        l.layout(el -> el.widthPercent(100).height(height));
        return l;
    }

    /** 小卡片网格可用宽度 (建筑 tab 有分类面板时要扣掉). */
    private static int gridAvailW(boolean withCategory) {
        return Minecraft.getInstance().getWindow().getGuiScaledWidth() - TAB_W - 30
            - (withCategory ? CAT_W + 4 : 0);
    }

    /** 动态列数; 建筑 tab 有分类面板时要扣掉面板宽 (否则最后一列被切掉). */
    private static int cols(boolean withCategory) {
        return Math.max(2, Math.min(gridAvailW(withCategory) / 96, 6));
    }

    private static int cardW(boolean withCategory) {
        int avail = gridAvailW(withCategory);
        int c = cols(withCategory);
        return Math.max(92, (avail - (c - 1) * 4) / c);
    }

    /** 每页行数 (按窗口高度算, 卡片 84 高 + 行距 4). */
    private static int rowsPerPage() {
        int availH = Minecraft.getInstance().getWindow().getGuiScaledHeight() - 80;
        return Math.max(1, availH / 88);
    }

    /** 每页行数 (按窗口高度与卡片高度算, 行距 4). */
    private static int rowsPerPageFor(int cardH) {
        int availH = Minecraft.getInstance().getWindow().getGuiScaledHeight() - 70;
        return Math.max(1, availH / (cardH + 4));
    }

    /** 页码收敛到 [0, totalPages-1]. */
    private static int clampPage(int page, int totalPages) {
        if (page >= totalPages) return totalPages - 1;
        return Math.max(0, page);
    }

    /** 通用翻页条: ◀ 页码/总页数 ▶ (点击后重建列表区, 不打断搜索框输入). */
    private static UIElement pager(int totalPages, IntSupplier get, IntConsumer set) {
        UIElement nav = new UIElement();
        nav.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(14).gapAll(4)
            .alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER));
        nav.setOverflowVisible(false);
        Button prev = new Button().setText("◀", false);
        prev.textStyle(s -> s.textColor(0xCCCCCC));
        prev.layout(l -> l.width(24).height(14));
        prev.setOnClick(e -> {
            int p = get.getAsInt();
            if (p > 0) {
                set.accept(p - 1);
                rebuildList();
            }
        });
        nav.addChild(prev);
        Label lbl = new Label();
        lbl.setText((get.getAsInt() + 1) + " / " + totalPages, false);
        lbl.textStyle(t -> t.textColor(0xAAAAAA));
        lbl.layout(l -> l.width(44).height(14).alignItems(AlignItems.CENTER)
            .justifyContent(AlignContent.CENTER));
        nav.addChild(lbl);
        Button next = new Button().setText("▶", false);
        next.textStyle(s -> s.textColor(0xCCCCCC));
        next.layout(l -> l.width(24).height(14));
        next.setOnClick(e -> {
            int p = get.getAsInt();
            if (p < totalPages - 1) {
                set.accept(p + 1);
                rebuildList();
            }
        });
        nav.addChild(next);
        return nav;
    }

    /** 动态列布局: 宽卡片 (云端/服务器/下载). */
    private static int wideCols() {
        int avail = Minecraft.getInstance().getWindow().getGuiScaledWidth() - TAB_W - 30;
        return Math.max(1, Math.min(avail / 240, 3));
    }

    private static int wideCardW() {
        int avail = Minecraft.getInstance().getWindow().getGuiScaledWidth() - TAB_W - 30;
        return Math.max(180, (avail - (wideCols() - 1) * 4) / wideCols());
    }

    // ==================== Tab 切换 ====================

    private static void switchTab(Tab tab) {
        if (tab == currentTab) return;
        // 保存当前 tab 状态 (含页码; 服务器 tab 额外存分页+过滤)
        SERVER_PAGE_BY_FILTER.put(serverFilter, serverPage);
        TabState savedCur = new TabState(currentCategory, categoryPanelHidden, searchText, constructionPage);
        savedCur.serverPage = serverPage;
        savedCur.serverFilter = serverFilter;
        TAB_STATES.put(currentTab, savedCur);
        currentTab = tab;
        // 恢复目标 tab 状态
        TabState saved = TAB_STATES.get(tab);
        if (saved != null) {
            currentCategory = saved.category;
            categoryPanelHidden = saved.panelHidden;
            searchText = saved.search;
            constructionPage = saved.page;
            serverPage = saved.serverPage;
            serverFilter = saved.serverFilter != null ? saved.serverFilter : "unsynced";
        } else {
            currentCategory = null;
            categoryPanelHidden = false;
            searchText = "";
            constructionPage = 0;
            serverPage = 0;
            serverFilter = "unsynced";
        }
        pendingDeleteBuildingId = null;
        cloudPage = 0;
        websitePage = 0;
        submitPage = 0;
        // 进入云端 tab 时探针 Xaero 可用性 (旧版行为, 每次进入探一次即可, 不放 buildCloudGrid)
        if (tab == Tab.CLOUD) {
            XaeroWaypointBridge.forceProbe();
        }
        rebuild();
    }

    // ==================== 详情跳转 ====================

    private static void openConstructionDetail(ConstructionInfo c) {
        // 蓝图模式: 点建筑卡片直接开 CustomStructureGui (生存=提交材料 / 创造=预览)
        if (blueprintStack != null) {
            ItemStack stack = blueprintStack;
            BlockPos pos = blueprintPos;
            blueprintStack = null;
            blueprintPos = null;
            PrefabCustomAddon.LOGGER.info("[BLUEPRINT-MODE] 卡片点击直跳 CustomStructureGui: id={}, pos={}",
                c.getId(), pos);
            CustomStructureGui.openFromBrowser(c, stack, pos);
            return;
        }
        var pl = Minecraft.getInstance().player;
        CustomStructureGui.openFromBrowser(c, null, pl == null ? null : pl.blockPosition().below());
    }

    /** 下载 tab 已下载卡片 → 按文件名找本地三件套 → 详情. */
    private static void openDownloadedBuildingDetail(PackDownloadManager.BuildingInfo2 b) {
        if (b == null) return;
        String baseName = b.name == null || b.name.isEmpty() ? b.id : b.name;
        baseName = baseName.replaceAll("[\\\\/:*?\"<>|]", "_");
        String ext = b.fileExt == null || b.fileExt.isEmpty() ? ".nbt" : b.fileExt;
        if (!ext.startsWith(".")) ext = "." + ext;
        String extNoDot = ext.substring(1);

        for (Path root : new Path[]{LocalBuildingScanner.getDownloadRoot(), LocalBuildingScanner.getExtensionRoot()}) {
            String scanTag = root.equals(LocalBuildingScanner.getDownloadRoot()) ? "download" : "extension";
            for (LocalBuilding lb : LocalBuildingScanner.scanDir(root, scanTag)) {
                String lbExt = lb.fileExt == null ? "" : lb.fileExt;
                boolean extMatch = lbExt.isEmpty() || lbExt.equalsIgnoreCase(ext)
                    || lbExt.equalsIgnoreCase(extNoDot)
                    || (lbExt.startsWith(".") && lbExt.substring(1).equalsIgnoreCase(extNoDot));
                if (!lb.id.equals(baseName) || !extMatch) continue;
                ConstructionInfo c = new ConstructionInfo(lb.id);
                c.setName(lb.name == null || lb.name.isEmpty() ? b.name : lb.name);
                c.setAuthor(lb.author == null || lb.author.isEmpty() ? b.author : lb.author);
                c.setDescription(lb.description);
                c.setDependencies(lb.dependencies);
                c.setCategory(lb.category);
                c.setFormat(lb.fileExt == null ? "nbt" : lb.fileExt.replaceFirst("^\\.", ""));
                c.setLocalImagePath(lb.imagePath);
                c.setLocalNbtPath(lb.filePath);
                openConstructionDetail(c);
                return;
            }
        }
        setStatus("✗ 找不到已下载文件, 试重新下载: " + b.name);
    }

    // ==================== 下载 tab 数据 ====================

    private static void refreshDownloadedBuildings() {
        downloadedBuildings = LocalBuildingScanner.scanDir(LocalBuildingScanner.getDownloadRoot(), "download");
        for (LocalBuilding lb : downloadedBuildings) {
            if (!lb.hasPreviewImage() || LOCAL_IMG.containsKey(lb.id) || LOCAL_IMG_LOADING.contains(lb.id)) continue;
            LOCAL_IMG_LOADING.add(lb.id);
            triggerLoadLocalImage(lb);
        }
    }

    private static void triggerLoadLocalImage(LocalBuilding lb) {
        Path imgPath = lb.imagePath;
        if (imgPath == null || !Files.exists(imgPath)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try (InputStream is = Files.newInputStream(imgPath)) {
                BufferedImage img = ImageIO.read(is);
                if (img == null) {
                    return;
                }
                Minecraft.getInstance().execute(() -> {
                    DynamicTexture tex = uploadIconTexture(img);
                    if (tex == null) {
                        return;
                    }
                    ResourceLocation loc = Minecraft.getInstance().getTextureManager()
                        .register("prefab_dl_" + TEX_GEN.incrementAndGet(), tex);
                    LOCAL_IMG.put(lb.id, loc);
                });
            } catch (Exception e) {
                PrefabCustomAddon.LOGGER.warn("[DOWNLOAD-TAB] local image read failed for {}", lb.id, e);
            }
        });
    }

    private static void refreshWebsiteBuildings() {
        if (websiteBuildingsLoading) return;
        websiteBuildingsLoading = true;
        websiteBuildingsError = null;
        PackDownloadManager.getInstance().fetchBuildingListAsync()
            .thenAccept(list -> Minecraft.getInstance().execute(() -> {
                websiteBuildings = list;
                websiteBuildingsLoading = false;
                websiteBuildingsError = null;
                for (PackDownloadManager.BuildingInfo2 b : list) {
                    if (b == null || b.id == null || WEBSITE_IMG.containsKey(b.id)
                        || WEBSITE_IMG_LOADING.contains(b.id)) continue;
                    triggerLoadWebsiteImage(b);
                }
                rebuildList();
            }))
            .exceptionally(ex -> {
                Throwable t = ex.getCause() != null ? ex.getCause() : ex;
                Minecraft.getInstance().execute(() -> {
                    websiteBuildingsLoading = false;
                    websiteBuildingsError = t.getMessage();
                    PrefabCustomAddon.LOGGER.warn("[DOWNLOAD-TAB] fetch building list failed: {}", websiteBuildingsError);
                    rebuildList();
                });
                return null;
            });
    }

    private static void triggerLoadWebsiteImage(PackDownloadManager.BuildingInfo2 b) {
        if (b == null || b.id == null) return;
        WEBSITE_IMG_LOADING.add(b.id);
        PackDownloadManager.getInstance().fetchBuildingImageAsync(b)
            .thenAccept(data -> Minecraft.getInstance().execute(() -> {
                WEBSITE_IMG_LOADING.remove(b.id);
                if (data == null || data.length == 0) return;
                BufferedImage img;
                try {
                    img = ImageIO.read(new ByteArrayInputStream(data));
                } catch (Exception e) {
                    return;
                }
                if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) return;
                DynamicTexture tex = new DynamicTexture(img.getWidth(), img.getHeight(), false);
                tex.setFilter(false, false);
                copyPixels(img, tex);
                tex.upload();
                ResourceLocation loc = Minecraft.getInstance().getTextureManager()
                    .register("prefab_web_" + TEX_GEN.incrementAndGet(), tex);
                WEBSITE_IMG.put(b.id, loc);
                rebuildList();
            }))
            .exceptionally(ex -> {
                Minecraft.getInstance().execute(() -> WEBSITE_IMG_LOADING.remove(b.id));
                return null;
            });
    }

    private static void startWebsiteDownload(PackDownloadManager.BuildingInfo2 b) {
        if (b == null || b.id == null || WEBSITE_DOWNLOADING.contains(b.id)) return;
        WEBSITE_DOWNLOADING.add(b.id);
        WEBSITE_PROGRESS.put(b.id, new double[]{0, 0, 0});
        setStatus(tr("website.status.downloading", truncate(b.name, 14)));
        rebuildList();
        PackDownloadManager.getInstance().downloadBuildingStreaming(b, new PackDownloadManager.ProgressCallback() {
            @Override
            public void onStart(String packId) {
            }

            @Override
            public void onProgress(long downloaded, long total, double percent) {
                // 节流: 只在整数百分比变化时重建 (避免高频回调刷爆 UI)
                int pct = (int) percent;
                Integer last = WEBSITE_LAST_PCT.get(b.id);
                if (last == null || last.intValue() != pct) {
                    WEBSITE_LAST_PCT.put(b.id, pct);
                    Minecraft.getInstance().execute(() -> {
                        WEBSITE_PROGRESS.put(b.id, new double[]{downloaded, total, percent});
                        rebuildList();
                    });
                }
            }

            @Override
            public void onComplete(Path savedTo) {
                Minecraft.getInstance().execute(() -> {
                    WEBSITE_DOWNLOADING.remove(b.id);
                    WEBSITE_PROGRESS.remove(b.id);
                    WEBSITE_LAST_PCT.remove(b.id);
                    refreshDownloadedBuildings();
                    setStatus(tr("browser.status.downloaded", b.name));
                    rebuildList();
                });
            }

            @Override
            public void onError(String error) {
                Minecraft.getInstance().execute(() -> {
                    WEBSITE_DOWNLOADING.remove(b.id);
                    WEBSITE_PROGRESS.remove(b.id);
                    WEBSITE_LAST_PCT.remove(b.id);
                    setStatus(tr("browser.status.download_failed", error));
                    rebuildList();
                });
            }
        });
    }

    private static void openWebsiteHome() {
        String url = AddonConfig.getServerUrl();
        if (url == null || url.isEmpty()) {
            setStatus(tr("browser.status.no_server_url"));
            return;
        }
        try {
            Util.getPlatform().openUri(URI.create(url));
            setStatus(tr("browser.status.opened_browser"));
        } catch (Exception e) {
            setStatus(tr("browser.status.open_failed", e.getMessage()));
        }
    }

    private static boolean isWebsiteBuildingDownloaded(PackDownloadManager.BuildingInfo2 b) {
        if (b == null) return false;
        String baseName = b.name == null || b.name.isEmpty() ? b.id : b.name;
        baseName = baseName.replaceAll("[\\\\/:*?\"<>|]", "_");
        String ext = b.fileExt == null || b.fileExt.isEmpty() ? ".nbt" : b.fileExt;
        Path dir = PackDownloadManager.getDownloadRoot();
        if (!Files.exists(dir)) return false;
        if (Files.exists(dir.resolve(baseName + ext))) return true;
        for (int i = 2; i < 100; i++) {
            if (Files.exists(dir.resolve(baseName + "_" + i + ext))) return true;
        }
        return false;
    }

    // ==================== 云端缩略图 ====================

    private static void triggerLoadCloudThumb(CloudBuilding b) {
        if (b == null || b.id == null) return;
        if (b.thumbnailPng != null && b.thumbnailPng.length > 0) {
            byte[] data = b.thumbnailPng;
            CompletableFuture.runAsync(() -> {
                try {
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
                    if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) {
                        loadCloudThumbFromLocalBuilding(b);
                        return;
                    }
                    int w = img.getWidth();
                    int h = img.getHeight();
                    Minecraft.getInstance().execute(() -> uploadCloudThumb(b, img, w, h));
                } catch (Exception e) {
                    PrefabCustomAddon.LOGGER.warn("[CLOUD-THUMB] 嵌入图读取失败, 尝试 LocalBuilding 兜底: {}", e.toString());
                    loadCloudThumbFromLocalBuilding(b);
                }
            });
            return;
        }
        loadCloudThumbFromLocalBuilding(b);
    }

    /** 云端缩略图兜底: 按名字找本地同名建筑的预览图. */
    private static void loadCloudThumbFromLocalBuilding(CloudBuilding b) {
        CompletableFuture.runAsync(() -> {
            try {
                for (LocalBuilding lb : LocalBuildingScanner.scanAll()) {
                    if (lb.name == null || !lb.name.equals(b.name)) continue;
                    if (lb.imagePath == null || !Files.exists(lb.imagePath)) return;
                    try (InputStream is = Files.newInputStream(lb.imagePath)) {
                        BufferedImage img = ImageIO.read(is);
                        if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) return;
                        int w = img.getWidth();
                        int h = img.getHeight();
                        Minecraft.getInstance().execute(() -> uploadCloudThumb(b, img, w, h));
                    }
                    return;
                }
            } catch (Exception e) {
                PrefabCustomAddon.LOGGER.warn("[CLOUD-THUMB-FALLBACK] 读图失败 {}", b.id);
            }
        });
    }

    private static void uploadCloudThumb(CloudBuilding b, BufferedImage img, int ww, int hh) {
        try {
            DynamicTexture tex = new DynamicTexture(ww, hh, false);
            tex.setFilter(false, false);
            copyPixels(img, tex);
            tex.upload();
            ResourceLocation loc = Minecraft.getInstance().getTextureManager()
                .register("prefab_cloud_" + TEX_GEN.incrementAndGet(), tex);
            CLOUD_THUMB.put(b.id, loc);
            rebuildList();
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("[CLOUD-THUMB] upload failed for {}", b.id);
        }
    }

    private static ResourceLocation ensureCloudThumbLoaded(CloudBuilding b) {
        if (b == null || b.thumbnailPng == null || b.thumbnailPng.length == 0) return null;
        ResourceLocation existing = CLOUD_THUMB.get(b.id);
        if (existing != null) return existing;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(b.thumbnailPng));
            if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) return null;
            int w = img.getWidth();
            int h = img.getHeight();
            DynamicTexture tex = new DynamicTexture(w, h, false);
            tex.setFilter(false, false);
            copyPixels(img, tex);
            tex.upload();
            ResourceLocation loc = Minecraft.getInstance().getTextureManager()
                .register("prefab_cloud_" + TEX_GEN.incrementAndGet(), tex);
            CLOUD_THUMB.put(b.id, loc);
            return loc;
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("[CLOUD-THUMB] 同步解码失败: id={} err={}", b.id, e.toString());
            return null;
        }
    }

    // ==================== 建筑预览图 / 缓存缩略图 ====================

    /** 建筑预览图: pngData → localImagePath 文件 → 居中裁方纹理. key = 建筑 id. */
    private static ResourceLocation ensurePreviewTextureLoaded(ConstructionInfo c) {
        String key = c.getId();
        if (PREVIEW_TEX.containsKey(key)) return PREVIEW_TEX.get(key);
        if (!c.hasPreviewImage()) {
            PREVIEW_TEX.put(key, null);
            return null;
        }
        byte[] data = c.getPngData();
        if ((data == null || data.length == 0) && c.getLocalImagePath() != null) {
            try {
                data = Files.readAllBytes(c.getLocalImagePath());
            } catch (Exception e) {
                PrefabCustomAddon.LOGGER.warn("[PREVIEW] {} 读 {} 失败: {}", key, c.getLocalImagePath(), e.getMessage());
            }
        }
        if (data == null || data.length == 0) {
            PREVIEW_TEX.put(key, null);
            return null;
        }
        DynamicTexture tex = uploadTexture(data);
        ResourceLocation loc = null;
        if (tex != null) {
            loc = Minecraft.getInstance().getTextureManager()
                .register("prefab_preview_" + TEX_GEN.incrementAndGet(), tex);
        }
        PREVIEW_TEX.put(key, loc);
        return loc;
    }

    /** 缩略图缓存 (mcs 下载时的缩略图) → 纹理. key = ThumbnailCache 指纹. */
    private static ResourceLocation ensureCachedThumbnailTextureLoaded(ConstructionInfo c, byte[] pngData) {
        String key = ThumbnailCache.fingerprint(c);
        if (CACHED_THUMB_TEX.containsKey(key)) return CACHED_THUMB_TEX.get(key);
        DynamicTexture tex = uploadTexture(pngData);
        ResourceLocation loc = null;
        if (tex != null) {
            loc = Minecraft.getInstance().getTextureManager()
                .register("prefab_thumb_" + TEX_GEN.incrementAndGet(), tex);
        }
        CACHED_THUMB_TEX.put(key, loc);
        return loc;
    }

    // ==================== 纹理工具 ====================

    /** 把 BufferedImage 像素 (ARGB→ABGR) 写进 DynamicTexture. */
    private static void copyPixels(BufferedImage img, DynamicTexture tex) {
        NativeImage pixels = tex.getPixels();
        int w = img.getWidth();
        int h = img.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int abgr = (argb & 0xFF00FF00) | ((argb & 0xFF0000) >> 16) | ((argb & 0xFF) << 16);
                pixels.setPixelRGBA(x, y, abgr);
            }
        }
    }

    /** 图片字节 → 居中裁方 → 缩到 ≤256 → DynamicTexture (失败返回 null). */
    private static DynamicTexture uploadTexture(byte[] bytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            return uploadIconTexture(img);
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("[BROWSER] 预览图纹理上传失败: {}", e.getMessage());
            return null;
        }
    }

    /** 居中裁方 + 限宽 256 → DynamicTexture (双线性过滤, 与旧版 uploadIconTexture 一致). */
    private static DynamicTexture uploadIconTexture(BufferedImage img) {
        if (img == null) return null;
        try {
            int w = img.getWidth();
            int h = img.getHeight();
            if (w <= 0 || h <= 0) return null;
            int cropSide = Math.min(w, h);
            if (cropSide < w || cropSide < h) {
                BufferedImage cropped = img.getSubimage((w - cropSide) / 2, (h - cropSide) / 2, cropSide, cropSide);
                BufferedImage croppedCopy = new BufferedImage(cropSide, cropSide, BufferedImage.TYPE_INT_ARGB);
                Graphics2D cg = croppedCopy.createGraphics();
                cg.drawImage(cropped, 0, 0, null);
                cg.dispose();
                img = croppedCopy;
                w = cropSide;
                h = cropSide;
            }
            if (w > 256) {
                BufferedImage scaled = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.drawImage(img, 0, 0, 256, 256, null);
                g.dispose();
                img = scaled;
                w = 256;
                h = 256;
            }
            DynamicTexture tex = new DynamicTexture(w, h, false);
            tex.setFilter(true, true);
            copyPixels(img, tex);
            tex.upload();
            return tex;
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("[BROWSER] uploadIconTexture 失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 数据过滤 ====================

    /** 建筑 tab 数据源: ExtensionPack 全部建筑 (去掉 server-cache 副本) ∪ LocalBuildingScanner 本地扫描, 收藏优先排序. */
    private static List<ConstructionInfo> getMergedConstructionsForBuildingsTab() {
        List<ConstructionInfo> raw = ExtensionPackManager.getInstance().getAllConstructionsForGui();
        List<ConstructionInfo> all = new ArrayList<>(raw.size());
        for (ConstructionInfo c : raw) {
            if (c.getPack() != null && c.getPack().isServerBacked()) continue;
            all.add(c);
        }
        for (LocalBuilding lb : scanAllCached()) {
            ConstructionInfo existing = null;
            for (ConstructionInfo c : all) {
                if (c.getId().equals(lb.id)) {
                    existing = c;
                    break;
                }
            }
            ConstructionInfo c = existing;
            if (c == null) {
                c = new ConstructionInfo(lb.id);
                all.add(c);
            }
            c.setName(lb.name);
            c.setAuthor(lb.author);
            c.setDescription(lb.description);
            c.setDependencies(lb.dependencies);
            c.setCategory(lb.category);
            if (lb.fileExt != null && !lb.fileExt.isEmpty()) {
                c.setFormat(lb.fileExt.startsWith(".") ? lb.fileExt.substring(1) : lb.fileExt);
            }
            if (lb.imagePath != null && Files.exists(lb.imagePath)) {
                c.setLocalImagePath(lb.imagePath);
            }
            if (lb.filePath != null) {
                c.setLocalNbtPath(lb.filePath);
            }
        }
        PlayerPreferences prefs = PlayerPreferences.get();
        List<ConstructionInfo> favorited = new ArrayList<>();
        List<ConstructionInfo> rest = new ArrayList<>();
        for (ConstructionInfo c : all) {
            String pkg = c.getPack() == null ? null : c.getPack().getPackageName();
            if (prefs.isFavorite(pkg, c.getId())) {
                favorited.add(c);
            } else {
                rest.add(c);
            }
        }
        List<ConstructionInfo> merged = new ArrayList<>(all.size());
        merged.addAll(favorited);
        merged.addAll(rest);
        return merged;
    }

    private static List<ConstructionInfo> filterByCategory(List<ConstructionInfo> src, String cat) {
        if (cat == null) return src;
        List<ConstructionInfo> out = new ArrayList<>();
        for (ConstructionInfo c : src) {
            if (cat.equals(c.getCategoryOrDefault())) out.add(c);
        }
        return out;
    }

    private static List<ConstructionInfo> filterBySearch(List<ConstructionInfo> src, String q) {
        if (q == null || q.trim().isEmpty()) return src;
        String needle = q.trim().toLowerCase();
        List<ConstructionInfo> out = new ArrayList<>();
        for (ConstructionInfo c : src) {
            if (matches(c.getName(), needle) || matches(c.getAuthor(), needle) || matches(c.getId(), needle)) {
                out.add(c);
            }
        }
        return out;
    }

    private static boolean matches(String s, String needle) {
        return s != null && s.toLowerCase().contains(needle);
    }

    // ==================== 小工具 ====================

    private static void setStatus(String msg) {
        lastStatus = msg;
        if (statusLabel != null) {
            statusLabel.setText(msg, false);
        }
    }

    private static String tr(String key, Object... args) {
        return PrefabCustomAddon.tr(key, args);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + ".." : s;
    }

    /** 按像素宽截断 (CJK 按 9px, 其余按 6px 估算), 超出补 "..". */
    private static String truncateW(String s, int maxPx) {
        if (s == null) return "";
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += s.charAt(i) > 0x2E7F ? 9 : 6;
            if (w > maxPx) return s.substring(0, i) + "..";
        }
        return s;
    }

    private static String formatRelativeTime(long ts) {
        if (ts <= 0L) return tr("time.unknown");
        long diff = System.currentTimeMillis() - ts;
        if (diff < 0L) diff = 0L;
        long sec = diff / 1000L;
        if (sec < 60L) return tr("time.just_now");
        long min = sec / 60L;
        if (min < 60L) return tr("time.minutes_ago", String.valueOf(min));
        long hr = min / 60L;
        if (hr < 24L) return tr("time.hours_ago", String.valueOf(hr));
        return tr("time.days_ago", String.valueOf(hr / 24L));
    }

    private static String formatFileSize(long bytes) {
        if (bytes <= 0L) return "-";
        if (bytes < 1024L) return bytes + "B";
        if (bytes < 0x100000L) return bytes / 1024L + "KB";
        return String.format("%.1fMB", bytes / 1024.0 / 1024.0);
    }
}
