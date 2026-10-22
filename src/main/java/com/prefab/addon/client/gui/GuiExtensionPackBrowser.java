package com.prefab.addon.client.gui;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.config.CategoryManager;
import com.prefab.addon.download.PackDownloadManager;
import com.prefab.addon.extension.ConstructionInfo;
import com.prefab.addon.extension.ExtensionPackManager;
import com.prefab.addon.extension.LocalBuilding;
import com.prefab.addon.extension.LocalBuildingScanner;
import com.prefab.addon.network.ServerPackManifestPayload;
import com.prefab.addon.network.BuildingUploadClient;
import com.prefab.addon.network.ServerPackSyncClient;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 拓展包浏览器 (LDLib2 UI 重写版, 2026-10).
 *
 * <p>旧版是基于 vanilla Screen 手绘 hit-rect 的 ~3000 行实现; 本版整体改用 LDLib2
 * UIElement 树 (照 {@code TerminalGui} 的模式): 左侧 tab 栏 + 右侧内容面板,
 * 数据变化时重建面板, 缩略图异步加载完毕后通过 listener 触发刷新.</p>
 *
 * <h2>四个 tab</h2>
 * <ul>
 *   <li><b>建筑</b>: 扩展包建筑 (过滤 server-backed 副本) ∪ 本地扫描建筑 (id 合并回填),
 *       搜索 + 分类侧栏 (管理分类弹 {@link GuiCategoryManager}) + 分页</li>
 *   <li><b>服务器</b>: 服务器同步清单 (仅多人游戏), 同步全部 / 单卡同步 / 查看</li>
 *   <li><b>收藏</b>: {@link ExtensionPackManager#getFavoriteConstructions()}</li>
 *   <li><b>下载</b>: 网站建筑市场 (拉列表 / 下载 / 筛选 / 已下载优先)</li>
 * </ul>
 *
 * <p>蓝图模式: 右键未绑定蓝图进入 ({@link #openForBlueprint}), 点建筑卡片直跳
 * {@link CustomStructureGui#open(ConstructionInfo, ItemStack, BlockPos)}, 跳过详情页.</p>
 *
 * <p>公开 API 保持不变: {@link #open()} / {@link #openForBlueprint(ItemStack, BlockPos)} /
 * {@link #safeTextureId(String)}.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class GuiExtensionPackBrowser {

    // ==================== tab 定义 ====================
    private static final int TAB_BUILDINGS = 0;
    private static final int TAB_SERVERS = 1;
    private static final int TAB_FAVORITES = 2;
    private static final int TAB_DOWNLOAD = 3;
    private static final int TAB_MANAGE = 4;
    private static final int TAB_SUBMIT = 5;
    private static final String[] TAB_LABELS = {"建筑", "服务器", "收藏", "下载", "分类管理", "提交材料"};

    // ==================== 静态状态 (跨开关联保留, 同旧版语义) ====================
    private static int currentTab = TAB_BUILDINGS;

    /** 各 tab 分页 (0 起). int[1] 包装方便闭包内修改. */
    private static final int[] buildingsPage = {0};
    private static final int[] favsPage = {0};
    private static final int[] serverPage = {0};
    private static final int[] downloadPage = {0};
    private static final int[] submitPage = {0};

    /** 各 tab 搜索文本 (建筑/收藏有搜索框, 其它 tab 无). */
    private static String buildingsSearch = "";
    private static String favsSearch = "";

    /** 建筑 tab 当前分类 (null = 全部). */
    @Nullable
    private static String currentCategory = null;

    /** 服务器 tab 子筛选: "all" / "unsynced" / "synced". 默认只看未同步. */
    private static String serverFilter = "unsynced";
    /** 下载 tab 筛选: "all" / "downloaded" / "not_downloaded". */
    private static String websiteFilter = "all";

    // === 蓝图模式 (CustomBlueprintClientHandler 右键未绑定蓝图时进入) ===
    @Nullable
    private static ItemStack blueprintStack = null;
    @Nullable
    private static BlockPos blueprintPos = null;

    // === 数据缓存 ===
    /** 建筑 tab 合并列表: 打开界面时构建一次 (旧版每帧重建, LDLib2 版改开屏一次). */
    private static List<ConstructionInfo> mergedBuildings = new ArrayList<>();

    // === 网站建筑 (下载 tab) ===
    private static List<PackDownloadManager.BuildingInfo2> websiteBuildings = new ArrayList<>();
    private static boolean websiteLoading = false;
    @Nullable
    private static String websiteError = null;
    /** 已拉取过一次列表 (进界面首次拉, 之后手动点刷新按钮/重开界面). */
    private static boolean websiteFetched = false;
    private static final Map<String, ResourceLocation> websiteImages = new HashMap<>();
    private static final Set<String> websiteImgLoading = new HashSet<>();
    private static final Set<String> websiteDownloading = new HashSet<>();

    // === 状态栏 ===
    @Nullable
    private static String statusMessage = null;
    private static int statusColor = 0x55FF55;
    private static int statusTick = 0;

    // === UI 引用 (界面关闭时置 null) ===
    @Nullable
    private static Runnable tabRebuild = null;
    /** 建筑/收藏 tab 内部列表刷新 (搜索框不重建, 保住输入焦点). */
    @Nullable
    private static Runnable listRefresh = null;
    @Nullable
    private static Label statusLabel = null;
    private static int tickCounter = 0;

    /** 纹理注册名计数 (重载后注册新名, 避免命中旧纹理). */
    private static final AtomicInteger TEX_GEN = new AtomicInteger();

    private GuiExtensionPackBrowser() {
    }

    // ==================== 打开入口 ====================

    /** 普通模式打开. */
    public static void open() {
        openInternal(null, null);
    }

    /** 蓝图模式打开: 点建筑卡片直跳 CustomStructureGui. */
    public static void openForBlueprint(ItemStack stack, BlockPos pos) {
        openInternal(stack, pos);
    }

    private static void openInternal(@Nullable ItemStack stack, @Nullable BlockPos pos) {
        dbg("openInternal blueprint={} pos={}", stack != null, pos);
        blueprintStack = stack;
        blueprintPos = pos;
        // 触发热重载, 避免管理员加了新包还得重启游戏
        ExtensionPackManager.getInstance().reloadIfChanged();
        ExtensionPackManager.getInstance().forceReload();
        // 建筑 tab 合并列表: 开屏构建一次 (分类管理/详情页返回都会重开本界面 → 自然刷新)
        mergedBuildings = collectMergedBuildings();
        // 缩略图就绪 listener (重开时重挂, 关屏时清)
        rebindThumbListeners();
        UIElement root = buildRoot();
        ModularUI ui = ModularUI.of(UI.of(root,
                StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)));
        Minecraft.getInstance().setScreen(new ModularUIScreen(ui, Component.literal("Extension Pack Browser")));
    }

    /** 重挂缩略图 listener (旧监听清空, 防引用上一届已销毁的面板). */
    private static void rebindThumbListeners() {
        com.prefab.addon.terminal.client.gui.TerminalBuildingThumbs.clearListeners();
        com.prefab.addon.terminal.client.gui.TerminalBuildingThumbs.addListener(() -> {
            // 建筑/收藏 tab 刷新列表区即可 (不重建搜索框); 其它 tab 没有这类缩略图
            Runnable r = listRefresh != null ? listRefresh : tabRebuild;
            // tell 任务内主线程执行, 理论安全; 记日志确认触发时机
            dbg("thumb-listener tab={} hasRefresh={}", TAB_LABELS[currentTab], r != null);
            if (r != null) r.run();
        });
    }

    /**
     * 贴图注册名安全化: ResourceLocation 只允许 [a-z0-9/._-],
     * 其余字符 (如中文建筑名) 转成 _uXXXX 转义, 否则 register 会抛 ResourceLocationException.
     */
    public static String safeTextureId(String id) {
        if (id == null || id.isEmpty()) return "null";
        StringBuilder sb = new StringBuilder(id.length());
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '/' || c == '.' || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append(String.format("_u%04x", (int) c));
            }
        }
        return sb.toString();
    }

    // ==================== UI 骨架 ====================

    private static UIElement buildRoot() {
        UIElement root = new UIElement();
        root.layout(l -> l.width(404).height(244)
                .flexDirection(FlexDirection.COLUMN).paddingAll(4).gapAll(2));
        root.style(s -> s.background(Sprites.BORDER));
        root.setOverflowVisible(false);

        UIElement mainRow = new UIElement();
        mainRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).flexGrow(1).gapAll(2));

        // 左侧 tab 栏 (5 个 tab, "分类管理" 4 字需要 48 宽)
        UIElement tabCol = new UIElement();
        tabCol.layout(l -> l.flexDirection(FlexDirection.COLUMN).width(48).gapAll(2));
        for (int i = 0; i < TAB_LABELS.length; i++) {
            addTabChild(tabCol, i);
        }
        mainRow.addChild(tabCol);
        root.addChild(mainRow);

        // 右侧内容面板 (按 tab 重建)
        UIElement tabArea = new UIElement();
        tabArea.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).gapAll(2));
        mainRow.addChild(tabArea);

        // 底部状态栏
        statusLabel = new Label();
        statusLabel.setText("", false);
        statusLabel.textStyle(t -> t.textColor(0x55FF55).textShadow(false)
                .textAlignVertical(Vertical.CENTER));
        statusLabel.layout(l -> l.widthPercent(100).height(12));
        root.addChild(statusLabel);

        tabRebuild = () -> {
            listRefresh = null;
            tabArea.clearAllChildren();
            switch (currentTab) {
                case TAB_BUILDINGS -> buildTabBuildings(tabArea);
                case TAB_SERVERS -> buildTabServers(tabArea);
                case TAB_FAVORITES -> buildTabFavorites(tabArea);
                case TAB_DOWNLOAD -> buildTabDownload(tabArea);
                case TAB_MANAGE -> buildTabManage(tabArea);
                case TAB_SUBMIT -> buildTabSubmit(tabArea);
            }
        };

        // 帧驱动: 状态栏倒计时 + 同步/下载中的周期刷新
        tickCounter = 0;
        root.addEventListener(UIEvents.TICK, e -> {
            tickCounter++;
            if (statusTick > 0 && --statusTick == 0) {
                statusMessage = null;
                updateStatusLabel();
            }
            boolean dirty = false;
            if (currentTab == TAB_SERVERS && ServerPackSyncClient.getInstance().isSyncing()
                    && tickCounter % 10 == 0) {
                dirty = true;
            }
            if (currentTab == TAB_DOWNLOAD && !websiteDownloading.isEmpty()
                    && tickCounter % 10 == 0) {
                dirty = true;
            }
            if (dirty && tabRebuild != null) {
                // 嫌疑点: UIEvent(TICK) 分发窗口内同步结构性重建 → 可能让下一次点击失灵
                dbg("tick-refresh tab={} tick={} (TICK 分发窗口内同步重建)", TAB_LABELS[currentTab], tickCounter);
                tabRebuild.run();
            }
        });
        final int gen = SCREEN_GEN.incrementAndGet();
        root.addEventListener(UIEvents.REMOVED, e -> {
            if (SCREEN_GEN.get() != gen) {
                // 旧屏幕迟到的移除事件 (重复 open 时): 新屏幕正在用这些静态字段, 绝不能清
                dbg("screen-removed IGNORED gen={} (旧屏幕迟到事件, 保留新屏幕状态)", gen);
                return;
            }
            dbg("screen-removed (浏览器关闭, 清 tabRebuild/listener)");
            tabRebuild = null;
            listRefresh = null;
            statusLabel = null;
            com.prefab.addon.terminal.client.gui.TerminalBuildingThumbs.clearListeners();
        });

        tabRebuild.run();
        return root;
    }

    /** 左侧 tab 按钮 (选中高亮, 点击切换并重建内容面板). */
    private static void addTabChild(UIElement tabCol, int index) {
        Button b = new Button().setText(TAB_LABELS[index], false);
        boolean selected = currentTab == index;
        b.buttonStyle(s -> {
            if (selected) {
                s.baseTexture(Sprites.RECT_SOLID).hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_SOLID);
            } else {
                s.baseTexture(Sprites.RECT_DARK).hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK);
            }
        });
        b.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textAlignVertical(Vertical.CENTER));
        b.layout(l -> l.widthPercent(100).height(16));
        b.setOnClick(e -> {
            if (currentTab != index) {
                currentTab = index;
                post("tab:" + TAB_LABELS[index], tabRebuild);
            }
        });
        tabCol.addChild(b);
    }

    // ==================== Tab: 分类管理 ====================

    /** 分类管理 tab: 嵌入 {@link GuiCategoryManager} 的内容, 增删改后 post 重建本 tab. */
    private static void buildTabManage(UIElement area) {
        area.addChild(GuiCategoryManager.buildTabContent(() -> post("manage:refresh", tabRebuild)));
    }

    // ==================== Tab: 建筑 ====================

    private static void buildTabBuildings(UIElement area) {
        // 搜索行 (重建列表时不重建, 保输入焦点)
        UIElement searchRow = new UIElement();
        searchRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(16).gapAll(2));
        TextField search = new TextField();
        search.setText(buildingsSearch);
        search.setAnyString();
        search.textFieldStyle(s -> s.textColor(0xFFFFFF).textShadow(false));
        search.layout(l -> l.flexGrow(1).height(16).minHeight(16));
        UIElement listArea = new UIElement();
        listArea.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100)
                .flexGrow(1).flexBasis(0).minHeight(0).minWidth(0).gapAll(2));
        Runnable[] rebuildList = {null};
        rebuildList[0] = () -> {
            listRefresh = rebuildList[0];
            listArea.clearAllChildren();
            buildBuildingsBody(listArea, rebuildList[0]);
        };
        search.setTextResponder(s -> {
            buildingsSearch = s == null ? "" : s.trim();
            buildingsPage[0] = 0;
            post("b-search", rebuildList[0]);
        });
        searchRow.addChild(search);
        area.addChild(searchRow);
        area.addChild(listArea);
        rebuildList[0].run();
    }

    /** 建筑列表体: 左分类侧栏 + 右卡片网格 + 分页. */
    private static void buildBuildingsBody(UIElement listArea, Runnable rebuildList) {
        // 分类有效性检查 (分类可能已被删)
        if (currentCategory != null && !CategoryManager.UNCATEGORIZED.equals(currentCategory)) {
            if (!CategoryManager.get().getCategories().contains(currentCategory)) {
                currentCategory = null;
            }
        }
        List<ConstructionInfo> byCat = filterByCategory(mergedBuildings, currentCategory);
        List<ConstructionInfo> shown = filterBySearch(byCat, buildingsSearch);

        UIElement body = new UIElement();
        body.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100)
                .flexGrow(1).flexBasis(0).minHeight(0).gapAll(4));
        listArea.addChild(body);

        // 分类侧栏: 全部 / 未分类 / 自定义分类 (管理分类已提升为顶级 tab)
        UIElement sidebar = new UIElement();
        sidebar.layout(l -> l.flexDirection(FlexDirection.COLUMN).width(56).gapAll(2));
        body.addChild(sidebar);
        addCategoryButton(sidebar, tr("browser.category.all"), null, rebuildList);
        addCategoryButton(sidebar, CategoryManager.UNCATEGORIZED, CategoryManager.UNCATEGORIZED, rebuildList);
        List<String> cats = CategoryManager.get().getCategories();
        // getCategories() 首位固定是"未分类", 跳过 (同 TerminalGui/主模组)
        for (int i = 1; i < cats.size(); i++) {
            addCategoryButton(sidebar, cats.get(i), cats.get(i), rebuildList);
        }

        int pageSize = 6;
        int total = shown.size();
        int totalPages = Math.max(1, (total + pageSize - 1) / pageSize);
        if (buildingsPage[0] >= totalPages) buildingsPage[0] = totalPages - 1;
        if (buildingsPage[0] < 0) buildingsPage[0] = 0;

        if (shown.isEmpty()) {
            body.addChild(emptyLabel(buildingsSearch.isEmpty() && currentCategory == null
                    ? "gui.modern_terminal.app.buildings.empty" : "msg.no_match",
                    "msg.try_filter"));
        } else {
            // 卡片网格: 2 列 x 3 行 = 6/页 (tabCol 48 宽后网格区 286, 卡片 140)
            UIElement gridCol = new UIElement();
            gridCol.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).gapAll(4));
            body.addChild(gridCol);
            int from = buildingsPage[0] * pageSize;
            int to = Math.min(from + pageSize, total);
            final int cols = 2;
            for (int i = from; i < to; i += cols) {
                UIElement row = new UIElement();
                row.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(6));
                for (int j = i; j < Math.min(i + cols, to); j++) {
                    row.addChild(constructionCard(shown.get(j), 140));
                }
                gridCol.addChild(row);
            }
        }
        // 分页条固定在列表底部 (单页也显示, 便于用户感知总数)
        addPageBar(listArea, buildingsPage, total, pageSize, rebuildList, true);
    }

    /** 分类侧栏按钮: 选中高亮, 再点同一项取消 (回"全部"). */
    private static void addCategoryButton(UIElement sidebar, String name, @Nullable String value, Runnable rebuild) {
        boolean selected = (currentCategory == null) ? (value == null) : currentCategory.equals(value);
        Button b = new Button().setText(truncate(name, 6), false);
        b.buttonStyle(s -> {
            if (selected) {
                s.baseTexture(Sprites.RECT_SOLID).hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_SOLID);
            } else {
                s.baseTexture(Sprites.RECT_DARK).hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK);
            }
        });
        b.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textAlignVertical(Vertical.CENTER));
        b.layout(l -> l.widthPercent(100).height(14));
        b.setOnClick(e -> {
            if (java.util.Objects.equals(currentCategory, value)) {
                currentCategory = null;
            } else {
                currentCategory = value;
            }
            buildingsPage[0] = 0;
            post("cat:" + value, rebuild);
        });
        sidebar.addChild(b);
    }

    /** 缩略图最大边长: 256 平衡清晰度与显存/驱动上限. */
    private static final int ICON_MAX_DIM = 256;

    /**
     * BufferedImage → DynamicTexture: 中心正方裁剪 + 超过 {@value #ICON_MAX_DIM} 缩至该尺寸
     * (BICUBIC) + ARGB→ABGR 逐像素写入, bilinear 过滤. 失败返回 null.
     */
    @Nullable
    private static DynamicTexture uploadIconTexture(BufferedImage img) {
        if (img == null) return null;
        int w = img.getWidth(), h = img.getHeight();
        if (w <= 0 || h <= 0) return null;

        // 中心裁剪成正方形 (卡片是正方形显示, 避免黑边)
        int cropSide = Math.min(w, h);
        int cropX = (w - cropSide) / 2;
        int cropY = (h - cropSide) / 2;
        if (cropSide < w || cropSide < h) {
            BufferedImage croppedCopy = new BufferedImage(cropSide, cropSide, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = croppedCopy.createGraphics();
            g.drawImage(img.getSubimage(cropX, cropY, cropSide, cropSide), 0, 0, null);
            g.dispose();
            img = croppedCopy;
            w = cropSide;
            h = cropSide;
        }
        // 过大下采样
        if (w > ICON_MAX_DIM) {
            BufferedImage scaled = new BufferedImage(ICON_MAX_DIM, ICON_MAX_DIM, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(img, 0, 0, ICON_MAX_DIM, ICON_MAX_DIM, null);
            g.dispose();
            img = scaled;
            w = ICON_MAX_DIM;
            h = ICON_MAX_DIM;
        }
        DynamicTexture tex = new DynamicTexture(w, h, false);
        tex.setFilter(true, true);
        NativeImage pixels = tex.getPixels();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int abgr = ((argb & 0xFF00FF00) | ((argb & 0x00FF0000) >> 16) | ((argb & 0x000000FF) << 16));
                pixels.setPixelRGBA(x, y, abgr);
            }
        }
        tex.upload();
        return tex;
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

    /** 各过滤模式记忆的页码 (来回切换过滤不丢分页) */
    private static final Map<String, Integer> SERVER_PAGE_BY_FILTER = new HashMap<>();

    /** 上传成功回调 (BuildingUploadClient 在收到服务器最终 ACK 后调用) */
    public static void markUploaded(String fileName) {
        if (fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            LOCALLY_UPLOADED.add(fileName.substring(0, fileName.length() - 4));
        }
    }

    /** BuildingUploadClient 状态回调: 刷新状态栏; structural=true 时重建服务器 tab (按钮状态变化) */
    public static void onUploadProgress(boolean structural) {
        com.prefab.addon.network.BuildingUploadClient up =
                com.prefab.addon.network.BuildingUploadClient.getInstance();
        var st = up.getState();
        int color = st == com.prefab.addon.network.BuildingUploadClient.State.UPLOADING ? 0x55AAFF
                : st == com.prefab.addon.network.BuildingUploadClient.State.DONE ? 0x55FF55
                : st == com.prefab.addon.network.BuildingUploadClient.State.ERROR ? 0xFF5555
                : 0xAAAAAA;
        String msg = up.getStatusMessage();
        if (msg != null && !msg.isEmpty()) {
            setStatus(msg, color);
        }
        if (structural && currentTab == TAB_SERVERS && tabRebuild != null) {
            post("upload-progress", tabRebuild);
        }
    }

    /** 顶部状态行文本: 只反映上传状态 (服务器清单同步是进服自动的后台行为, 不再显示) */
    private static String serverStatusText() {
        com.prefab.addon.network.BuildingUploadClient up =
                com.prefab.addon.network.BuildingUploadClient.getInstance();
        var st = up.getState();
        if (st == com.prefab.addon.network.BuildingUploadClient.State.UPLOADING
                || st == com.prefab.addon.network.BuildingUploadClient.State.DONE
                || st == com.prefab.addon.network.BuildingUploadClient.State.ERROR) {
            String m = up.getStatusMessage();
            return m == null || m.isEmpty() ? tr("server.status.upload_hint") : m;
        }
        return tr("server.status.upload_hint");
    }

    private static int serverStatusColor() {
        com.prefab.addon.network.BuildingUploadClient up =
                com.prefab.addon.network.BuildingUploadClient.getInstance();
        var st = up.getState();
        if (st == com.prefab.addon.network.BuildingUploadClient.State.UPLOADING) return 0x55AAFF;
        if (st == com.prefab.addon.network.BuildingUploadClient.State.DONE) return 0x55FF55;
        if (st == com.prefab.addon.network.BuildingUploadClient.State.ERROR) return 0xFF5555;
        return 0xAAAAAA;
    }

    /** 可上传源: packZip != null → 整个 zip 原样上传; 否则 standalone != null → 现场打包 建筑文件+.txt */
    private record UploadSource(Path packZip, LocalBuilding standalone) {}

    /** 服务器 tab 一行数据: 建筑 + 上传源 + 上传状态 */
    private record ServerUploadEntry(ConstructionInfo c, UploadSource src, int state) {}

    /** 解析一个建筑的可上传源; null = 不可上传 (zip 文件丢失/单文件建筑缺 nbt) */
    private static UploadSource resolveUploadSource(ConstructionInfo c) {
        if (c.getPack() != null) {
            com.prefab.addon.extension.ExtensionPack p = c.getPack();
            // __standalone__ 虚拟包: 建筑本体是磁盘上的单文件 (.nbt/.schem/...),
            // 不是 zip, 不能在 zip 分支判定 —— 落到下面的 scanAll 按文件查找
            if (!ExtensionPackManager.STANDALONE_PACKAGE.equals(p.getPackageName())) {
                String fp = p.getFilePath();
                String fn = p.getFileName();
                if (fp != null && fn != null && fn.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    Path zip = Paths.get(fp);
                    return Files.exists(zip) ? new UploadSource(zip, null) : null;
                }
                // 非 zip 的单文件包 (.litematic/.schem 独立建筑): 同样落到下面 scanAll 按文件查找
            }
        }
        if (c.getLocalNbtPath() != null) {
            for (LocalBuilding lb : LocalBuildingScanner.scanAll()) {
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
    private static List<String> collectPaletteDeps(UploadSource src) {
        java.util.TreeSet<String> mods = new java.util.TreeSet<>();
        try {
            if (src.standalone() != null && src.standalone().filePath != null) {
                mods.addAll(com.prefab.addon.work.NbtFormatConverter.extractModIds(
                        Files.readAllBytes(src.standalone().filePath)));
            } else if (src.packZip() != null) {
                try (ZipFile zf = new ZipFile(src.packZip().toFile())) {
                    var entries = zf.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry ze = entries.nextElement();
                        if (ze.isDirectory()) continue;
                        String n = ze.getName().toLowerCase(Locale.ROOT);
                        if (n.endsWith(".nbt") || n.endsWith(".litematic")
                                || n.endsWith(".schem") || n.endsWith(".schematic")) {
                            mods.addAll(com.prefab.addon.work.NbtFormatConverter.extractModIds(
                                    zf.getInputStream(ze).readAllBytes()));
                        }
                    }
                }
            }
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("[BUILD-UPLOAD] 解析建筑依赖 (palette) 失败 (非致命): {}", e.getMessage());
        }
        return new ArrayList<>(mods);
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
        List<ConstructionInfo> all = filterBySearch(mergedBuildings, buildingsSearch);
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

    private static void buildTabServers(UIElement area) {
        // 单人游戏守卫
        if (Minecraft.getInstance().getCurrentServer() == null) {
            area.addChild(emptyLabel("server.only_multiplayer.title", "server.only_multiplayer.hint"));
            return;
        }

        // 顶部: 上传状态行 (同步由进服自动完成, 不再显示同步按钮)
        UIElement statusRow = new UIElement();
        statusRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(14).gapAll(4));
        area.addChild(statusRow);
        Label upStatus = new Label();
        upStatus.setText(truncate(serverStatusText(), 56), false);
        upStatus.textStyle(t -> t.textColor(serverStatusColor()).textShadow(false)
                .textAlignVertical(Vertical.CENTER));
        upStatus.layout(l -> l.flexGrow(1).height(14));
        statusRow.addChild(upStatus);

        List<ServerUploadEntry> all = collectServerUploadEntries();
        int syncedCount = 0;
        int missingCount = 0;
        for (ServerUploadEntry e : all) {
            if (e.state() == UP_SYNCED) syncedCount++;
            else if (e.state() != UP_NA) missingCount++;
        }

        // 过滤行: 全部 / 未上传 / 已上传 (带计数) + OP 可见「管理」按钮
        UIElement filterRow = new UIElement();
        filterRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(14).gapAll(4));
        area.addChild(filterRow);
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
            Button b = new Button().setText(labels[i], false);
            boolean active = mode.equals(serverFilter);
            b.buttonStyle(s -> {
                if (active) {
                    s.baseTexture(Sprites.RECT_SOLID).hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_SOLID);
                } else {
                    s.baseTexture(Sprites.RECT_DARK).hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK);
                }
            });
            b.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textAlignVertical(Vertical.CENTER));
            if (isOp) {
                b.layout(l -> l.flexGrow(1).height(14));
            } else {
                b.layout(l -> l.widthPercent(33).height(14));
            }
            b.setOnClick(e -> {
                // 切过滤: 记住当前过滤的页码, 恢复目标过滤的页码 (来回切不丢分页)
                SERVER_PAGE_BY_FILTER.put(serverFilter, serverPage[0]);
                serverFilter = mode;
                serverPage[0] = SERVER_PAGE_BY_FILTER.getOrDefault(mode, 0);
                post("srv-filter:" + mode, tabRebuild);
            });
            filterRow.addChild(b);
        }
        if (isOp) {
            Button manage = new Button().setText(tr("server.button.manage"), false);
            manage.textStyle(t -> t.textColor(0xFFCC66).textAlignHorizontal(Horizontal.CENTER)
                    .textAlignVertical(Vertical.CENTER));
            manage.buttonStyle(s -> s.baseTexture(Sprites.RECT_DARK)
                    .hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
            manage.layout(l -> l.width(36).height(14));
            manage.setOnClick(e -> GuiServerManage.open());
            filterRow.addChild(manage);
        }

        if (all.isEmpty()) {
            area.addChild(emptyLabel("server.empty.title", "server.empty.hint"));
            return;
        }

        List<ServerUploadEntry> filtered = filterServerUploads(all);
        if (filtered.isEmpty()) {
            // 注意键名语义: "unsynced" 过滤下空 → "没有未上传的建筑" (empty_synced)
            if ("unsynced".equals(serverFilter)) {
                area.addChild(emptyLabel("server.filter.empty_synced", "server.filter.empty_synced_hint"));
            } else if ("synced".equals(serverFilter)) {
                area.addChild(emptyLabel("server.filter.empty_unsynced", "server.filter.empty_unsynced_hint"));
            } else {
                area.addChild(emptyLabel("msg.no_match", "msg.try_filter"));
            }
            return;
        }

        // 卡片网格: 2 列 x 2 行 = 4/页
        UIElement gridCol = new UIElement();
        gridCol.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).gapAll(4));
        area.addChild(gridCol);
        int pageSize = 4;
        int total = filtered.size();
        int totalPages = Math.max(1, (total + pageSize - 1) / pageSize);
        if (serverPage[0] >= totalPages) serverPage[0] = totalPages - 1;
        if (serverPage[0] < 0) serverPage[0] = 0;
        int from = serverPage[0] * pageSize;
        int to = Math.min(from + pageSize, total);
        final int cols = 2;
        for (int i = from; i < to; i += cols) {
            UIElement row = new UIElement();
            row.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(6));
            for (int j = i; j < Math.min(i + cols, to); j++) {
                row.addChild(serverUploadCard(filtered.get(j)));
            }
            gridCol.addChild(row);
        }
        addPageBar(gridCol, serverPage, total, pageSize, () -> post("srv-page", tabRebuild));
    }

    /** 服务器 tab 卡片: 缩略图 + 名称/格式·大小; 底部 = 上传/重新上传按钮 或 已上传标签. */
    private static UIElement serverUploadCard(ServerUploadEntry e) {
        ConstructionInfo c = e.c();
        UIElement card = new UIElement();
        card.style(s -> s.backgroundTexture(Sprites.RECT_DARK));
        card.layout(l -> l.flexDirection(FlexDirection.COLUMN).width(168).paddingAll(3).gapAll(2));

        // 上排: 缩略图 + 文字
        UIElement top = new UIElement();
        top.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).gapAll(4));
        card.addChild(top);

        Button img = new Button();
        img.noText();
        img.layout(l -> l.width(36).height(36));
        var rl = com.prefab.addon.terminal.client.gui.TerminalBuildingThumbs.get(c);
        if (rl != null) {
            var tex = SpriteTexture.of(rl);
            img.buttonStyle(s -> s.baseTexture(tex).hoverTexture(tex).pressedTexture(tex));
        }
        if (e.state() == UP_SYNCED) {
            // 已上传: 点缩略图直接进详情 (建造走服务端 packName)
            img.setOnClick(ev -> openServerBuildingDetail(e));
        }
        top.addChild(img);

        UIElement textCol = new UIElement();
        textCol.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).gapAll(1));
        top.addChild(textCol);
        Label name = new Label();
        name.setText(truncate(c.getName(), 12), false);
        name.textStyle(t -> t.textColor(0xFFFFFF).textShadow(false).fontSize(7f));
        name.layout(l -> l.widthPercent(100).height(9));
        textCol.addChild(name);
        long srcSize = e.src() != null && e.src().packZip() != null
                ? cachedZipSize(e.src().packZip()) : -1;
        String sizeText = srcSize >= 0 ? formatFileSize(srcSize)
                : (e.src() != null && e.src().standalone() != null
                        ? formatFileSize(e.src().standalone().fileSize) : "-");
        Label meta = new Label();
        meta.setText((c.getFormat() == null || c.getFormat().isEmpty() ? "nbt" : c.getFormat())
                + " · " + sizeText, false);
        meta.textStyle(t -> t.textColor(0x888888).textShadow(false).fontSize(6f));
        meta.layout(l -> l.widthPercent(100).height(8));
        textCol.addChild(meta);

        // 底部按钮: 已上传标签 (点击进详情) / 不可上传 / 上传·重新上传
        var up = com.prefab.addon.network.BuildingUploadClient.getInstance();
        if (e.state() == UP_SYNCED) {
            Button tag = new Button().setText(tr("server.badge.synced"), false);
            tag.textStyle(t -> t.textColor(0x55FF77).textAlignHorizontal(Horizontal.CENTER)
                    .textAlignVertical(Vertical.CENTER));
            tag.buttonStyle(s -> s.baseTexture(Sprites.RECT_LIGHT)
                    .hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_LIGHT));
            tag.layout(l -> l.widthPercent(100).height(14));
            tag.setOnClick(ev -> openServerBuildingDetail(e));
            card.addChild(tag);
        } else if (e.state() == UP_NA) {
            Label na = new Label();
            na.setText(tr("server.badge.na"), false);
            na.textStyle(t -> t.textColor(0x666666).textShadow(false).fontSize(6f)
                    .textAlignHorizontal(Horizontal.CENTER).textAlignVertical(Vertical.CENTER));
            na.layout(l -> l.widthPercent(100).height(14));
            card.addChild(na);
        } else {
            boolean stale = e.state() == UP_STALE;
            Button act = new Button().setText(tr(stale ? "server.button.reupload" : "server.button.upload"), false);
            act.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textAlignVertical(Vertical.CENTER)
                    .textColor(stale ? 0xFFAA55 : 0x88BBFF));
            act.buttonStyle(s -> s.baseTexture(Sprites.RECT_DARK)
                    .hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
            act.layout(l -> l.widthPercent(100).height(14));
            act.setOnClick(ev -> {
                if (up.isUploading()) {
                    setStatus(tr("upload.err.busy"), 0xFFAA55);
                    return;
                }
                // 依赖缺失的建筑禁止上传: 传上去别人也建不了 (服务端同样缺依赖时解析会失败)
                // txt 里的依赖 + 直接解析建筑文件 NBT palette 的依赖 (txt 缺失/漏记时兜底)
                java.util.LinkedHashSet<String> depSet = new java.util.LinkedHashSet<>();
                if (c.getDependencies() != null) {
                    depSet.addAll(c.getDependencies());
                }
                depSet.addAll(collectPaletteDeps(e.src()));
                if (!depSet.isEmpty()) {
                    com.prefab.addon.work.DependencyChecker.CheckResult cr =
                            com.prefab.addon.work.DependencyChecker.check(new ArrayList<>(depSet));
                    if (!cr.missing.isEmpty()) {
                        setStatus(tr("server.upload.err.deps", String.join(", ", cr.missing)), 0xFF5555);
                        return;
                    }
                }
                UploadSource src = e.src();
                if (src.packZip() != null) {
                    up.startPack(src.packZip());
                } else {
                    up.startStandalone(src.standalone());
                }
            });
            card.addChild(act);
        }
        return card;
    }

    /** 已上传建筑 → 点开详情. 直接用本地建筑数据 (预览/详情读本地文件, 不需要任何副本):
     *  zip 包建筑本地包名与服务端一致 (同一个 zip), 建造 payload 直接命中服务端;
     *  独立建筑 payload 带 STANDALONE_PACKAGE, 服务端按建筑 id 去 server-cache 兜底查找. */
    private static void openServerBuildingDetail(ServerUploadEntry e) {
        openCard(e.c());
    }

    /** zip 文件大小 (失败返回 -1) */
    private static long cachedZipSize(Path zip) {
        try {
            return Files.size(zip);
        } catch (Exception e) {
            return -1;
        }
    }



    // ==================== Tab: 收藏 ====================

    private static void buildTabFavorites(UIElement area) {
        UIElement searchRow = new UIElement();
        searchRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(16).gapAll(2));
        TextField search = new TextField();
        search.setText(favsSearch);
        search.setAnyString();
        search.textFieldStyle(s -> s.textColor(0xFFFFFF).textShadow(false));
        search.layout(l -> l.flexGrow(1).height(16).minHeight(16));
        UIElement listArea = new UIElement();
        listArea.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100)
                .flexGrow(1).flexBasis(0).minHeight(0).minWidth(0).gapAll(2));
        Runnable[] rebuildList = {null};
        rebuildList[0] = () -> {
            listRefresh = rebuildList[0];
            listArea.clearAllChildren();
            List<ConstructionInfo> favs = ExtensionPackManager.getInstance().getFavoriteConstructions();
            List<ConstructionInfo> shown = filterBySearch(favs, favsSearch);
            if (shown.isEmpty()) {
                listArea.addChild(emptyLabel("favorites.empty.title", "favorites.empty.hint"));
                return;
            }
            UIElement gridCol = new UIElement();
            gridCol.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).gapAll(4));
            listArea.addChild(gridCol);
            int pageSize = 6;
            int total = shown.size();
            int totalPages = Math.max(1, (total + pageSize - 1) / pageSize);
            if (favsPage[0] >= totalPages) favsPage[0] = totalPages - 1;
            if (favsPage[0] < 0) favsPage[0] = 0;
            int from = favsPage[0] * pageSize;
            int to = Math.min(from + pageSize, total);
            final int cols = 2;
            for (int i = from; i < to; i += cols) {
                UIElement row = new UIElement();
                row.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(6));
                for (int j = i; j < Math.min(i + cols, to); j++) {
                    row.addChild(constructionCard(shown.get(j), 168));
                }
                gridCol.addChild(row);
            }
            addPageBar(gridCol, favsPage, total, pageSize, rebuildList[0]);
        };
        search.setTextResponder(s -> {
            favsSearch = s == null ? "" : s.trim();
            favsPage[0] = 0;
            post("f-search", rebuildList[0]);
        });
        searchRow.addChild(search);
        area.addChild(searchRow);
        area.addChild(listArea);
        rebuildList[0].run();
    }

    // ==================== Tab: 提交材料 ====================

    /**
     * 提交材料 tab: 列出"交了一点但没交完"的建筑卡片 (缩略图 + 名称 + 进度条 + 已交/需求),
     * 交齐的置顶并显示绿色"可以建造"提示. 数据源: ChallengeSessionManager 客户端镜像
     * (SubmitMaterialIndex 只读枚举, 不产生空条目副作用).
     */
    private static void buildTabSubmit(UIElement area) {
        UIElement listArea = new UIElement();
        listArea.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100)
                .flexGrow(1).flexBasis(0).minHeight(0).minWidth(0).gapAll(2));
        area.addChild(listArea);
        Runnable[] rebuildList = {null};
        rebuildList[0] = () -> {
            listRefresh = rebuildList[0];
            listArea.clearAllChildren();
            LocalPlayer player = Minecraft.getInstance().player;
            List<SubmitMaterialIndex.Entry> entries = player == null ? List.of()
                    : SubmitMaterialIndex.collect(mergedBuildings, player.getUUID());
            if (entries.isEmpty()) {
                listArea.addChild(emptyLabel("submit.empty.title", "submit.empty.hint"));
                return;
            }
            UIElement col = new UIElement();
            col.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).gapAll(4));
            listArea.addChild(col);
            int pageSize = 4;
            int total = entries.size();
            int totalPages = Math.max(1, (total + pageSize - 1) / pageSize);
            if (submitPage[0] >= totalPages) submitPage[0] = totalPages - 1;
            if (submitPage[0] < 0) submitPage[0] = 0;
            int from = submitPage[0] * pageSize;
            int to = Math.min(from + pageSize, total);
            for (int i = from; i < to; i++) {
                col.addChild(submitCard(entries.get(i)));
            }
            addPageBar(col, submitPage, total, pageSize, rebuildList[0]);
        };
        rebuildList[0].run();
    }

    /**
     * 提交材料卡片 (整行): 缩略图按钮 + 名称 + 进度条 + 状态行.
     * 交齐 → 绿条 + "可以建造!"; 需求表不可得 → 只显示已交材料种类数.
     */
    private static UIElement submitCard(SubmitMaterialIndex.Entry en) {
        UIElement card = new UIElement();
        card.style(s -> s.backgroundTexture(Sprites.RECT_DARK));
        card.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(52)
                .paddingAll(3).gapAll(4));

        Button img = new Button();
        img.noText();
        img.layout(l -> l.width(40).height(40));
        var rl = com.prefab.addon.terminal.client.gui.TerminalBuildingThumbs.get(en.construction);
        if (rl != null) {
            var tex = SpriteTexture.of(rl);
            img.buttonStyle(s -> s.baseTexture(tex).hoverTexture(tex).pressedTexture(tex));
        }
        img.setOnClick(e -> openCard(en.construction));
        card.addChild(img);

        UIElement textCol = new UIElement();
        textCol.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).gapAll(3));
        card.addChild(textCol);

        Label name = new Label();
        name.setText(truncate(en.construction.getName(), 28), false);
        name.textStyle(t -> t.textColor(0xFFFFFF).textShadow(false).fontSize(7f));
        name.layout(l -> l.widthPercent(100).height(9));
        textCol.addChild(name);

        // 进度条 (外底 + 内填充, ColorRectTexture 着色); 需求表不可得时不画填充
        UIElement barOuter = new UIElement();
        barOuter.style(s -> s.backgroundTexture(Sprites.RECT_DARK));
        barOuter.layout(l -> l.widthPercent(100).height(6));
        textCol.addChild(barOuter);
        if (en.needTotal > 0) {
            UIElement barFill = new UIElement();
            int pct = Math.max(2, (int) Math.round(en.progress() * 100));
            barFill.layout(l -> l.widthPercent(pct).heightPercent(100));
            int fillColor = en.ready ? 0x55FF55 : 0xFFAA55;
            barFill.style(s -> s.background(new ColorRectTexture(fillColor)));
            barOuter.addChild(barFill);
        }

        Label status = new Label();
        String statusText;
        int statusColor;
        if (en.ready) {
            statusText = tr("submit.progress", en.haveTotal, en.needTotal) + " · " + tr("submit.ready");
            statusColor = 0x55FF55;
        } else if (en.needTotal > 0) {
            statusText = tr("submit.progress", en.haveTotal, en.needTotal);
            statusColor = 0xAAAAAA;
        } else {
            statusText = tr("submit.unknown", en.submitted.size());
            statusColor = 0x888888;
        }
        status.setText(statusText, false);
        status.textStyle(t -> t.textColor(statusColor).textShadow(false).fontSize(6f));
        status.layout(l -> l.widthPercent(100).height(9));
        textCol.addChild(status);
        return card;
    }

    // ==================== Tab: 下载 ====================

    private static void buildTabDownload(UIElement area) {
        // 首次进入拉网站列表
        if (!websiteFetched && !websiteLoading) {
            refreshWebsiteBuildings();
        }

        // 顶部: 状态 + [筛选] + [打开网站]
        UIElement topRow = new UIElement();
        topRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(16).gapAll(4));
        area.addChild(topRow);
        String statusText;
        int statusCol;
        if (websiteLoading) {
            statusText = tr("website.status.loading");
            statusCol = 0x55AAFF;
        } else if (websiteError != null) {
            statusText = tr("website.status.failed", truncate(websiteError, 16));
            statusCol = 0xFF5555;
        } else if (websiteBuildings.isEmpty()) {
            statusText = tr("website.status.empty");
            statusCol = 0xAAAAAA;
        } else {
            statusText = tr("website.status.summary", String.valueOf(websiteBuildings.size()));
            statusCol = 0xAAAAAA;
        }
        Label dlStatus = new Label();
        dlStatus.setText(statusText, false);
        dlStatus.textStyle(t -> t.textColor(statusCol).textShadow(false).textAlignVertical(Vertical.CENTER));
        dlStatus.layout(l -> l.flexGrow(1).height(16));
        topRow.addChild(dlStatus);

        String filtLabel = "all".equals(websiteFilter) ? tr("website.filter.all")
                : "downloaded".equals(websiteFilter) ? tr("website.filter.downloaded")
                : tr("website.filter.not_downloaded");
        Button filt = new Button().setText("▼ " + filtLabel, false);
        filt.buttonStyle(s -> s.baseTexture(Sprites.RECT_DARK)
                .hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
        filt.layout(l -> l.width(72).height(16));
        filt.setOnClick(e -> {
            websiteFilter = "all".equals(websiteFilter) ? "downloaded"
                    : "downloaded".equals(websiteFilter) ? "not_downloaded" : "all";
            downloadPage[0] = 0;
            post("dl-filter:" + websiteFilter, tabRebuild);
        });
        topRow.addChild(filt);

        Button openWeb = new Button().setText(tr("website.button.open"), false);
        openWeb.buttonStyle(s -> s.baseTexture(Sprites.RECT_DARK)
                .hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
        openWeb.layout(l -> l.width(60).height(16));
        openWeb.setOnClick(e -> openWebsiteHome());
        topRow.addChild(openWeb);
        // MCS 蓝图站入口 (I 键同款界面, mcschematic.top 在线蓝图浏览/搜索/下载)
        Button mcs = new Button().setText("MCS蓝图站", false);
        mcs.buttonStyle(s -> s.baseTexture(Sprites.RECT_DARK)
                .hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
        mcs.layout(l -> l.width(64).height(16));
        mcs.setOnClick(e -> GuiBlueprintMarket.open());
        topRow.addChild(mcs);

        // 过滤 + 已下载优先排序
        List<PackDownloadManager.BuildingInfo2> filtered = new ArrayList<>();
        for (PackDownloadManager.BuildingInfo2 b : websiteBuildings) {
            if (b == null || b.id == null) continue;
            boolean downloaded = isWebsiteBuildingDownloaded(b);
            if ("downloaded".equals(websiteFilter) && !downloaded) continue;
            if ("not_downloaded".equals(websiteFilter) && downloaded) continue;
            filtered.add(b);
        }
        filtered.sort((a, b2) -> {
            boolean da = isWebsiteBuildingDownloaded(a);
            boolean db = isWebsiteBuildingDownloaded(b2);
            if (da != db) return da ? -1 : 1;
            return 0;
        });

        if (filtered.isEmpty()) {
            String emptyTitle;
            String emptyHint;
            if (websiteError != null) {
                emptyTitle = tr("website.empty.failed");
                emptyHint = tr("website.empty.failed_hint");
            } else if ("not_downloaded".equals(websiteFilter)) {
                emptyTitle = tr("website.empty.all_downloaded");
                emptyHint = tr("website.empty.all_downloaded_hint");
            } else if ("downloaded".equals(websiteFilter)) {
                emptyTitle = tr("website.empty.no_downloaded");
                emptyHint = tr("website.empty.no_downloaded_hint");
            } else if (websiteLoading) {
                emptyTitle = tr("website.empty.loading");
                emptyHint = tr("website.empty.loading_hint");
            } else {
                emptyTitle = tr("website.empty.empty");
                emptyHint = tr("website.empty.empty_hint");
            }
            area.addChild(emptyLabel(emptyTitle, emptyHint));
            return;
        }

        // 卡片网格: 2 列 x 2 行 = 4/页
        UIElement gridCol = new UIElement();
        gridCol.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).gapAll(4));
        area.addChild(gridCol);
        int pageSize = 4;
        int total = filtered.size();
        int totalPages = Math.max(1, (total + pageSize - 1) / pageSize);
        if (downloadPage[0] >= totalPages) downloadPage[0] = totalPages - 1;
        if (downloadPage[0] < 0) downloadPage[0] = 0;
        int from = downloadPage[0] * pageSize;
        int to = Math.min(from + pageSize, total);
        final int cols = 2;
        for (int i = from; i < to; i += cols) {
            UIElement row = new UIElement();
            row.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(6));
            for (int j = i; j < Math.min(i + cols, to); j++) {
                row.addChild(websiteCard(filtered.get(j)));
            }
            gridCol.addChild(row);
        }
        addPageBar(gridCol, downloadPage, total, pageSize, () -> {
            if (tabRebuild != null) tabRebuild.run();
        });
    }

    /** 网站建筑卡片: 缩略图 + 名称/作者/meta + [下载/查看] 按钮. */
    private static UIElement websiteCard(PackDownloadManager.BuildingInfo2 b) {
        boolean downloaded = isWebsiteBuildingDownloaded(b);
        boolean downloading = websiteDownloading.contains(b.id);

        UIElement card = new UIElement();
        card.style(s -> s.backgroundTexture(Sprites.RECT_DARK));
        card.layout(l -> l.flexDirection(FlexDirection.COLUMN).width(168).paddingAll(3).gapAll(2));

        UIElement top = new UIElement();
        top.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).gapAll(4));
        card.addChild(top);

        Button img = new Button();
        img.noText();
        img.layout(l -> l.width(36).height(36));
        ResourceLocation rl = websiteImages.get(b.id);
        if (rl == null && !websiteImgLoading.contains(b.id)) {
            triggerLoadWebsiteImage(b);
        }
        if (rl != null) {
            var tex = SpriteTexture.of(rl);
            img.buttonStyle(s -> s.baseTexture(tex).hoverTexture(tex).pressedTexture(tex));
            if (downloaded) img.setOnClick(e -> openDownloadedBuildingDetail(b));
        }
        top.addChild(img);

        UIElement textCol = new UIElement();
        textCol.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).gapAll(1));
        top.addChild(textCol);
        Label name = new Label();
        name.setText(truncate(b.name, 12), false);
        name.textStyle(t -> t.textColor(0xFFFFFF).textShadow(false).fontSize(7f));
        name.layout(l -> l.widthPercent(100).height(9));
        textCol.addChild(name);
        Label author = new Label();
        author.setText(truncate(b.author == null || b.author.isEmpty()
                ? tr("website.author.unknown") : b.author, 12), false);
        author.textStyle(t -> t.textColor(0xAAAAAA).textShadow(false).fontSize(6f));
        author.layout(l -> l.widthPercent(100).height(8));
        textCol.addChild(author);
        Label meta = new Label();
        meta.setText((b.fileExt == null || b.fileExt.isEmpty() ? ".nbt" : b.fileExt)
                + " · " + formatFileSize(b.fileSize), false);
        meta.textStyle(t -> t.textColor(0x888888).textShadow(false).fontSize(6f));
        meta.layout(l -> l.widthPercent(100).height(8));
        textCol.addChild(meta);

        // 底部按钮
        String btnText;
        if (downloading) {
            btnText = tr("website.badge.downloading", "…");
        } else if (downloaded) {
            btnText = tr("website.badge.downloaded");
        } else {
            btnText = tr("website.button.download");
        }
        Button action = new Button().setText(btnText, false);
        action.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textAlignVertical(Vertical.CENTER));
        action.layout(l -> l.widthPercent(100).height(14));
        action.setOnClick(e -> {
            if (isWebsiteBuildingDownloaded(b)) {
                openDownloadedBuildingDetail(b);
            } else if (websiteDownloading.contains(b.id)) {
                setStatus("下载中, 请稍候", 0xFFAA55);
            } else {
                startWebsiteDownload(b);
            }
        });
        card.addChild(action);
        return card;
    }

    /** 拉取网站建筑列表 (异步). */
    private static void refreshWebsiteBuildings() {
        websiteFetched = true;
        websiteLoading = true;
        websiteError = null;
        PackDownloadManager.getInstance().fetchBuildingListAsync()
                .thenAccept(list -> Minecraft.getInstance().execute(() -> {
                    websiteBuildings = list;
                    websiteLoading = false;
                    websiteError = null;
                    dbg("fetch-list-refresh count={}", list.size());
                    if (tabRebuild != null) tabRebuild.run();
                }))
                .exceptionally(ex -> {
                    Minecraft.getInstance().execute(() -> {
                        websiteLoading = false;
                        websiteError = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                        PrefabCustomAddon.LOGGER.warn("[DOWNLOAD-TAB] fetch building list failed: {}", websiteError);
                        dbg("fetch-list-fail-refresh");
                        if (tabRebuild != null) tabRebuild.run();
                    });
                    return null;
                });
    }

    /** 异步拉取网站建筑预览图 → websiteImages → 刷新. */
    private static void triggerLoadWebsiteImage(PackDownloadManager.BuildingInfo2 b) {
        if (b == null || b.id == null) return;
        if (!websiteImgLoading.add(b.id)) return;
        PackDownloadManager.getInstance().fetchBuildingImageAsync(b)
                .thenAccept(data -> Minecraft.getInstance().execute(() -> {
                    websiteImgLoading.remove(b.id);
                    if (data == null || data.length == 0) return;
                    try {
                        BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
                        if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) return;
                        DynamicTexture tex = uploadIconTexture(img);
                        if (tex == null) return;
                        websiteImages.put(b.id, Minecraft.getInstance().getTextureManager()
                                .register("prefab_web_" + safeTextureId(b.id) + "_" + TEX_GEN.incrementAndGet(), tex));
                        dbg("web-img-refresh id={}", b.id);
                        if (tabRebuild != null) tabRebuild.run();
                    } catch (Exception e) {
                        PrefabCustomAddon.LOGGER.warn("[DOWNLOAD-TAB] website image upload failed for {}", b.id, e);
                    }
                }))
                .exceptionally(ex -> {
                    websiteImgLoading.remove(b.id);
                    return null;
                });
    }

    /** 触发单个网站建筑下载 (流式, 回调只改数据, UI 由 TICK 周期刷新). */
    private static void startWebsiteDownload(PackDownloadManager.BuildingInfo2 b) {
        if (b == null || b.id == null) return;
        if (!websiteDownloading.add(b.id)) return;
        PackDownloadManager.getInstance().downloadBuildingStreaming(b,
                new PackDownloadManager.ProgressCallback() {
                    @Override public void onStart(String packId) {
                    }

                    @Override public void onProgress(long downloaded, long total, double percent) {
                    }

                    @Override public void onComplete(Path savedTo) {
                        Minecraft.getInstance().execute(() -> {
                            websiteDownloading.remove(b.id);
                            setStatus(tr("browser.status.downloaded", b.name), 0x55FF55);
                            dbg("dl-complete-refresh id={}", b.id);
                            if (tabRebuild != null) tabRebuild.run();
                        });
                    }

                    @Override public void onError(String error) {
                        Minecraft.getInstance().execute(() -> {
                            websiteDownloading.remove(b.id);
                            setStatus(tr("browser.status.download_failed", error), 0xFF5555);
                            dbg("dl-fail-refresh id={}", b.id);
                            if (tabRebuild != null) tabRebuild.run();
                        });
                    }
                });
    }

    /** 打开网站主页 (系统浏览器). */
    private static void openWebsiteHome() {
        dbg("open-website-home");
        String url = com.prefab.addon.config.AddonConfig.getServerUrl();
        if (url == null || url.isEmpty()) {
            setStatus(tr("browser.status.no_server_url"), 0xFF5555);
            return;
        }
        try {
            net.minecraft.Util.getPlatform().openUri(java.net.URI.create(url));
            setStatus(tr("browser.status.opened_browser"), 0x55AAFF);
        } catch (Exception e) {
            setStatus(tr("browser.status.open_failed", e.getMessage()), 0xFF5555);
        }
    }

    /** 判断一个网站建筑是否已经下载到本地 (文件名 = sanitize(name)+ext 或 _2/_3...). */
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

    // ==================== 卡片点击 → 详情 ====================

    /** 统一入口: 蓝图模式直跳 CustomStructureGui, 普通模式进详情页. */
    private static void openCard(ConstructionInfo c) {
        dbg("open-card id={} blueprintMode={}", c.getId(), blueprintStack != null);
        if (blueprintStack != null) {
            ItemStack stack = blueprintStack;
            BlockPos pos = blueprintPos;
            blueprintStack = null;
            blueprintPos = null;
            PrefabCustomAddon.LOGGER.info("[BLUEPRINT-MODE] 卡片点击直跳 CustomStructureGui: id={}, pos={}",
                    c.getId(), pos);
            // 传入返回回调: 详情页点关闭回到浏览器 (而非退出到游戏)
            CustomStructureGui.open(c, stack, pos, () -> GuiExtensionPackBrowser.open());
            return;
        }
        // 统一详情页样式: 非蓝图模式也开 CustomStructureGui (底部 关闭|预览|提交材料/更换建筑 那套),
        // 不再开 GuiConstructionDetail (删除建筑|选择|选择并锁定 那套) — 两种入口两种样式不统一.
        // 无蓝图: 基座落在玩家脚下方块下方一格 (跟终端入口 openFromTerminal 一致); 关闭回调重开浏览器.
        LocalPlayer pl = Minecraft.getInstance().player;
        CustomStructureGui.open(c, null, pl == null ? null : pl.blockPosition().below(),
                () -> GuiExtensionPackBrowser.open());
    }

    /**
     * 已下载的网站建筑: 按文件名匹配 LocalBuilding (先 prefab-download/, 后 prefab-extension/ 兜底),
     * 转 ConstructionInfo 打开详情.
     */
    private static void openDownloadedBuildingDetail(PackDownloadManager.BuildingInfo2 b) {
        if (b == null) return;
        dbg("open-downloaded-detail id={}", b.id);
        String baseName = b.name == null || b.name.isEmpty() ? b.id : b.name;
        baseName = baseName.replaceAll("[\\\\/:*?\"<>|]", "_");
        String ext = b.fileExt == null || b.fileExt.isEmpty() ? ".nbt" : b.fileExt;
        if (!ext.startsWith(".")) ext = "." + ext;
        String extNoDot = ext.substring(1);

        Path[] roots = {LocalBuildingScanner.getDownloadRoot(), LocalBuildingScanner.getExtensionRoot()};
        for (Path root : roots) {
            for (LocalBuilding lb : LocalBuildingScanner.scanDir(root, "browser")) {
                String lbExt = lb.fileExt == null ? "" : lb.fileExt;
                boolean idMatch = lb.id.equals(baseName);
                boolean extMatch = lbExt.isEmpty() || lbExt.equalsIgnoreCase(ext)
                        || lbExt.equalsIgnoreCase(extNoDot)
                        || (lbExt.startsWith(".") && lbExt.substring(1).equalsIgnoreCase(extNoDot));
                if (idMatch && extMatch) {
                    ConstructionInfo c = new ConstructionInfo(lb.id);
                    c.setName(lb.name == null || lb.name.isEmpty() ? b.name : lb.name);
                    c.setAuthor(lb.author == null || lb.author.isEmpty() ? b.author : lb.author);
                    c.setDescription(lb.description);
                    c.setDependencies(lb.dependencies);
                    c.setCategory(lb.category);
                    c.setFormat(lb.fileExt == null ? "nbt" : lb.fileExt.replaceFirst("^\\.", ""));
                    c.setLocalImagePath(lb.imagePath);
                    c.setLocalNbtPath(lb.filePath);
                    openCard(c);
                    return;
                }
            }
        }
        setStatus("✗ 找不到已下载文件, 试重新下载: " + b.name, 0xFFAA55);
    }

    // ==================== 建筑 tab 数据源 ====================

    /**
     * 建筑 tab 合并列表: 扩展包建筑 (过滤 server-backed 副本) ∪ 本地扫描建筑
     * (id 相同则 LocalBuilding 元数据覆盖 — localImagePath 是图片能渲染的关键).
     * 收藏的排最前. 旧版每帧重建, 本版开屏调用一次.
     */
    private static List<ConstructionInfo> collectMergedBuildings() {
        List<ConstructionInfo> all = new ArrayList<>();
        for (ConstructionInfo c : ExtensionPackManager.getInstance().getAllConstructionsForGui()) {
            if (c.getPack() != null && c.getPack().isServerBacked()) {
                continue; // server-cache 副本不进建筑 tab
            }
            all.add(c);
        }
        for (LocalBuilding lb : LocalBuildingScanner.scanAll()) {
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
        // 收藏优先排序
        var prefs = com.prefab.addon.config.PlayerPreferences.get();
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

    // ==================== 通用部件 ====================

    /**
     * 建筑/收藏卡片: 横排 (缩略图按钮 + 名称/作者), 点击缩略图进详情.
     * 缩略图走 TerminalBuildingThumbs 异步加载 (就绪前占位底, listener 触发列表刷新).
     */
    private static UIElement constructionCard(ConstructionInfo c, int width) {
        UIElement card = new UIElement();
        card.style(s -> s.backgroundTexture(Sprites.RECT_DARK));
        card.layout(l -> l.flexDirection(FlexDirection.ROW).width(width).height(48).paddingAll(3).gapAll(3));

        Button img = new Button();
        img.noText();
        img.layout(l -> l.width(40).height(40));
        var rl = com.prefab.addon.terminal.client.gui.TerminalBuildingThumbs.get(c);
        if (rl != null) {
            var tex = SpriteTexture.of(rl);
            img.buttonStyle(s -> s.baseTexture(tex).hoverTexture(tex).pressedTexture(tex));
        }
        img.setOnClick(e -> openCard(c));
        card.addChild(img);

        UIElement textCol = new UIElement();
        textCol.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).gapAll(1));
        card.addChild(textCol);
        Label name = new Label();
        name.setText(truncate(c.getName(), 11), false);
        name.textStyle(t -> t.textColor(0xFFFFFF).textShadow(false).fontSize(7f));
        name.layout(l -> l.widthPercent(100).height(9));
        textCol.addChild(name);
        Label author = new Label();
        author.setText(truncate(c.getAuthor() == null || c.getAuthor().isEmpty()
                ? tr("website.author.unknown") : c.getAuthor(), 11), false);
        author.textStyle(t -> t.textColor(0xAAAAAA).textShadow(false).fontSize(6f));
        author.layout(l -> l.widthPercent(100).height(8));
        textCol.addChild(author);
        Label fmt = new Label();
        fmt.setText((c.getFormat() == null || c.getFormat().isEmpty() ? "nbt" : c.getFormat())
                + (c.getCategoryOrDefault() == null || c.getCategoryOrDefault().isEmpty()
                || CategoryManager.UNCATEGORIZED.equals(c.getCategoryOrDefault())
                ? "" : " · " + truncate(c.getCategoryOrDefault(), 6)), false);
        fmt.textStyle(t -> t.textColor(0x888888).textShadow(false).fontSize(6f));
        fmt.layout(l -> l.widthPercent(100).height(8));
        textCol.addChild(fmt);
        return card;
    }

    /** 分页条 (只有一页时不显示): [‹] x/N [›]. */
    private static void addPageBar(UIElement parent, int[] page, int total, int pageSize, Runnable rebuild) {
        addPageBar(parent, page, total, pageSize, rebuild, false);
    }

    /** 分页条: [‹] x/N [›]; alwaysShow=true 时单页也显示 (建筑 tab 需求). */
    private static void addPageBar(UIElement parent, int[] page, int total, int pageSize,
                                   Runnable rebuild, boolean alwaysShow) {
        int totalPages = Math.max(1, (total + pageSize - 1) / pageSize);
        if (totalPages <= 1 && !alwaysShow) return;
        UIElement bar = new UIElement();
        bar.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(14).gapAll(4));
        Button prev = new Button().setText("‹", false);
        prev.buttonStyle(s -> s.baseTexture(Sprites.RECT_DARK)
                .hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
        prev.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textAlignVertical(Vertical.CENTER));
        prev.layout(l -> l.width(24).height(14));
        prev.setOnClick(e -> {
            if (page[0] > 0) {
                page[0]--;
                post("page-prev", rebuild);
            }
        });
        bar.addChild(prev);
        Label info = new Label();
        info.setText((page[0] + 1) + " / " + totalPages, false);
        info.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textAlignVertical(Vertical.CENTER)
                .textColor(0xAAAAAA).textShadow(false));
        info.layout(l -> l.flexGrow(1).height(14));
        bar.addChild(info);
        Button next = new Button().setText("›", false);
        next.buttonStyle(s -> s.baseTexture(Sprites.RECT_DARK)
                .hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
        next.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textAlignVertical(Vertical.CENTER));
        next.layout(l -> l.width(24).height(14));
        next.setOnClick(e -> {
            if (page[0] < totalPages - 1) {
                page[0]++;
                post("page-next", rebuild);
            }
        });
        bar.addChild(next);
        parent.addChild(bar);
    }

    /** 居中空状态提示 (标题 + 副提示). */
    private static UIElement emptyLabel(String titleKey, String hintKey) {
        UIElement col = new UIElement();
        col.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).widthPercent(100).gapAll(4));
        Label title = new Label();
        title.setText(tr(titleKey), false);
        title.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textAlignVertical(Vertical.CENTER)
                .textColor(0xAAAAAA));
        title.layout(l -> l.widthPercent(100).flexGrow(1));
        Label hint = new Label();
        hint.setText(tr(hintKey), false);
        hint.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textAlignVertical(Vertical.CENTER)
                .textColor(0x666666).fontSize(6.5f));
        hint.layout(l -> l.widthPercent(100).height(12));
        col.addChild(title);
        col.addChild(hint);
        return col;
    }

    // ==================== 工具 ====================

    /** 分类过滤 (null = 全部; 同 TerminalGui 用 getCategoryOrDefault 对齐). */
    private static List<ConstructionInfo> filterByCategory(List<ConstructionInfo> src, @Nullable String cat) {
        if (cat == null) return src;
        List<ConstructionInfo> out = new ArrayList<>();
        for (ConstructionInfo c : src) {
            if (cat.equals(c.getCategoryOrDefault())) out.add(c);
        }
        return out;
    }

    /** 简单搜索: 不区分大小写, 匹配 name / author / id. */
    private static List<ConstructionInfo> filterBySearch(List<ConstructionInfo> src, String q) {
        if (q == null || q.trim().isEmpty()) return src;
        String needle = q.trim().toLowerCase(Locale.ROOT);
        List<ConstructionInfo> out = new ArrayList<>();
        for (ConstructionInfo c : src) {
            if (matches(c.getName(), needle) || matches(c.getAuthor(), needle)
                    || matches(c.getId(), needle)) {
                out.add(c);
            }
        }
        return out;
    }

    private static boolean matches(@Nullable String s, String needle) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** server-cache/ 里找 baseName+ext 文件 (找不到试 _2/_3 后缀). */
    @Nullable
    private static Path findServerFile(Path dir, String baseName, String ext) {
        Path f = dir.resolve(baseName + ext);
        if (Files.exists(f)) return f;
        for (int i = 2; i < 100; i++) {
            f = dir.resolve(baseName + "_" + i + ext);
            if (Files.exists(f)) return f;
        }
        return null;
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private static String truncate(@Nullable String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    // ==================== 诊断日志 (排查"按钮点了没反应") ====================
    // 诊断矩阵 (复现时看 latest.log 的 [AUI-DBG] 段):
    //  A. 点击后连 post-queued 都没有 → UIEvent 没到达 handler (分发层死)
    //  B. post-queued 有但无 post-begin → tell 任务没跑 (主循环异常)
    //  C. post-end 后下一次点击死 → 上次重建产物的监听没绑上
    //  D. 死击之前紧邻 tick-refresh / 异步 refresh → 元凶就是那次分发窗口内的同步重建

    /** 诊断日志序号: queued→begin→end 三段对齐用. */
    private static final AtomicInteger POST_SEQ = new AtomicInteger();

    /** 屏幕代际计数: 每次 buildRoot 递增; 旧屏幕迟到的 REMOVED 不得清掉新屏幕的静态状态. */
    private static final AtomicInteger SCREEN_GEN = new AtomicInteger();

    /** 统一 [AUI-DBG] 前缀, 方便日志过滤. */
    private static void dbg(String format, Object... args) {
        PrefabCustomAddon.LOGGER.info("[AUI-DBG] " + format, args);
    }

    /**
     * 把 rebuild 排到主循环下一拍执行: 避开 UIEvent 分发窗口内同步改树的时序坑.
     * 带五段式诊断日志: queued(入队) → begin(出队, 含排队耗时) → end / THREW / skip.
     */
    private static void post(String tag, @Nullable Runnable r) {
        if (r == null) {
            dbg("post-skip {} (null runnable)", tag);
            return;
        }
        int seq = POST_SEQ.incrementAndGet();
        long enqueuedMs = System.currentTimeMillis();
        dbg("post-queued #{} {} tab={}", seq, tag, TAB_LABELS[currentTab]);
        Minecraft.getInstance().tell(() -> {
            long waitMs = System.currentTimeMillis() - enqueuedMs;
            dbg("post-begin  #{} {} wait={}ms tab={}", seq, tag, waitMs, TAB_LABELS[currentTab]);
            try {
                r.run();
                dbg("post-end    #{} {}", seq, tag);
            } catch (Throwable t) {
                dbg("post-THREW #{} {}", seq, tag, t);
                throw t;
            }
        });
    }

    /** i18n (key 缺失时降级为 key 本身). */
    private static String tr(String key, Object... args) {
        try {
            return Component.translatable(key, args).getString();
        } catch (Throwable t) {
            return key;
        }
    }

    private static void setStatus(String msg, int color) {
        statusMessage = msg;
        statusColor = color;
        statusTick = 200;
        updateStatusLabel();
    }

    private static void updateStatusLabel() {
        if (statusLabel != null) {
            statusLabel.setText(statusMessage == null ? "" : statusMessage, false);
            int c = statusColor;
            statusLabel.textStyle(t -> t.textColor(c).textShadow(false)
                    .textAlignVertical(Vertical.CENTER));
        }
    }
}
