package com.prefab.addon.client.gui;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import javax.imageio.ImageIO;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.client.GuiThumbRenderer;
import com.prefab.addon.client.ThumbnailCache;
import com.prefab.addon.cloud.CloudBuilding;
import com.prefab.addon.cloud.CloudBuildingClientCache;
import com.prefab.addon.config.CategoryManager;
import com.prefab.addon.config.PlayerPreferences;
import com.prefab.addon.download.PackDownloadManager;
import com.prefab.addon.download.PackDownloadManager.BuildingInfo2;
import com.prefab.addon.extension.ConstructionInfo;
import com.prefab.addon.extension.ExtensionPack;
import com.prefab.addon.extension.ExtensionPackManager;
import com.prefab.addon.extension.LocalBuilding;
import com.prefab.addon.extension.LocalBuildingScanner;
import com.prefab.addon.extension.ServerBuildingInfo;
import com.prefab.addon.integration.xaero.XaeroWaypointBridge;
import com.prefab.addon.work.FolderOpener;
import com.prefab.gui.GuiBase;
import com.prefab.gui.controls.ExtendedButton;


import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * 拓展包 / 建筑 管理界面 (新版: 左侧标签栏 + 右侧卡片列表).
 *
 * <h2>布局 (400 x 240 全屏化)</h2>
 * <pre>
 *   ┌─ 面板顶部不再有突出部分, 也不画标题文字 ─┐
 *   ├─ Tabs (w=72) ─┬─ Search bar (h=18, 仅建筑/收藏 tab) ─┤
 *   │  扩展包        │  ┌─ Card grid (3 cols x 2 rows) ─┐  │
 *   │  建筑          │  │  卡片 卡片 卡片               │  │
 *   │  云端建筑      │  │  卡片 卡片 卡片               │  │
 *   │  服务器        │  └─────────────────────────────┘  │
 *   │  收藏          │                                   │
 *   │  下载          │                                   │
 *   └──────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>6 个标签</h2>
 * <ul>
 *   <li><b>建筑</b>: 扫描 <b>全部</b> 建筑 (zip 拓展包内 + 独立 .nbt + 独立 .litematic + 独立 .schem).
 *       卡片样式: 预览图 + 建筑名 + 作者. 顶部带搜索框. 点击 → 打开 GuiConstructionDetail.</li>
 *   <li><b>云端建筑</b>: 显示当前玩家已经存到云端的建筑 (由自定义蓝图建造后自动备份).
 *       卡片样式: 缩略图 + 建筑名 + 状态(已放出/已收回) + 收回/放出按钮. 跨存档/跨世界保留.</li>
 *   <li><b>服务器</b>: 显示服务器 manifest 里的建筑 (单建筑粒度同步), 跟下载 tab 类似卡片.
 *       卡片样式: 图片 + 建筑名 + 同步/查看按钮. 已同步点卡片进 detail, 未同步点按钮单建筑拉取.</li>
 *   <li><b>收藏</b>: 显示 PlayerPreferences.favoriteKeys 对应的建筑. 没有就是空状态.</li>
 *   <li><b>下载</b>: 快捷打开 GuiExtensionPackDownloader, 留出未来扩展空间 (服务器列表等).</li>
 *   <li><b>下载</b>: 从 /api/buildings 拉取在线建筑列表, 玩家可一键下载到 prefab-download/.</li>
 * </ul>
 *
 * <h3>已移除</h3>
 * <ul>
 *   <li><b>原版</b> tab: 内置 Prefab 原版建筑目录会触发 Modrinth 版权审核拒绝, 整 tab 移除.</li>
 * </ul>
 *
 * <h2>导航栈</h2>
 *  - 顶层: 5 个 tab 之一
 *  - 次层: "扩展包" / "服务器" tab 内点击 pack 卡片 → 进入"包内建筑"视图
 *  - 第三层: 点击任何建筑 → 打开 GuiConstructionDetail (新版的, 含收藏按钮, 无下拉框/翻页)
 */
public class GuiExtensionPackBrowser extends GuiBase {

    // === 5 个标签 ===
    private enum Tab {
        BUILDINGS("建筑"),
        CLOUD("云端建筑"),
        SERVERS("服务器"),
        FAVORITES("收藏"),
        DOWNLOAD("下载");
        final String label;
        Tab(String label) { this.label = label; }
    }

    private Tab currentTab = Tab.BUILDINGS;

    /**
     * 次级视图: 在 PACKS / SERVERS tab 里, 点击 pack 卡片后进入的"包内建筑"视图.
     * 其他 tab 永远是 null (直接显示该 tab 的卡片网格, 点击建筑直接进 detail).
     */
    private ExtensionPack currentDrilldownPack = null;

    // === 布局常量 ===
    private static final int PANEL_W = 400;
    private static final int PANEL_H = 240;
    private static final int TABS_W = 72;        // 左侧 tab 宽度 (3 汉字 24px*3=72)
    private static final int SEARCH_H = 18;      // 搜索框高度 (右面板顶部, 在 PACKS / BUILDINGS tab 显示)

    // 卡片
    private static final int CARD_W = 92;
    private static final int CARD_H = 80;
    private static final int CARD_GAP = 6;
    private static final int CARD_COLS = 3;
    private static final int CARD_ROWS = 2;
    // 云端 tab 单独用 2 列布局, 让卡片更宽, 缩略图更大, 文字更完整
    private static final int CLOUD_CARD_COLS = 2;
    private static final int CLOUD_CARD_GAP = 6;
    private static final int CLOUD_CARD_H = 96;
    // 服务器 tab 跟下载 tab 同样 2 列: 左侧缩略图 + 右侧文字 + 底部按钮
    // 高度压到 56 让一屏放 3 行 (≈ 188 / 62 = 3), 共 6 个/页, 8 个建筑 2 页搞定
    private static final int SERVER_CARD_COLS = 2;
    private static final int SERVER_CARD_GAP = 6;
    private static final int SERVER_CARD_H = 56;
    // 建筑 tab 用 2 列 (右侧 110px 给分类列表), 4 个/页
    private static final int BUILDINGS_CARD_COLS = 2;
    // 分类列表宽度 (右栏, 跟搜索框宽度一样)
    private static final int CAT_LIST_W = 110;
    // 搜索框宽度 (建筑 tab 缩短, 其它 tab 仍用 SEARCH_FULL_W)
    private static final int SEARCH_BOX_W = 110;
    private static final int SEARCH_FULL_W = PANEL_W - TABS_W - 12;  // 约 316, 其它 tab 用

    // === 状态 ===
    private String searchText = "";
    private int scrollOffsetCards = 0;  // 卡片网格滚动偏移 (按 "页" 翻, 每页 CARD_COLS * CARD_ROWS)
    /** 建筑 tab 当前选中的分类 (null = 全部). 切换 tab 时重置为 null. */
    private String currentCategory = null;

    // === 跨 GUI 实例记忆: 玩家关闭 GUI 再打开后, 恢复上次的分类/页码/面板状态 ===
    private static String rememberedCategory = null;
    private static int rememberedPage = 0;
    private static boolean rememberedPanelHidden = false;

    // === 跨 tab 记忆: 玩家在「建筑」tab 选「原版」翻到第 2 页 → 切到「服务器」tab → 再切回「建筑」,
    //   应该还是「原版」第 2 页. 下面的 Map 存每个 tab 自己的状态. ===
    private static final java.util.Map<Tab, TabState> TAB_STATES = new java.util.EnumMap<>(Tab.class);

    /** 每个 tab 的状态快照. */
    private static final class TabState {
        String currentCategory;
        int scrollOffsetCards;
        boolean categoryPanelHidden;
        String searchText;
        TabState(String cat, int page, boolean hidden, String search) {
            this.currentCategory = cat;
            this.scrollOffsetCards = page;
            this.categoryPanelHidden = hidden;
            this.searchText = search;
        }
    }

    // === 分页按钮 hit rect (各 tab 共用, 每次重绘前重置) ===
    private int[] paginationBarRect = null;     // 整个分页条
    private int[] paginationPrevRect = null;     // ‹ 上一页
    private int[] paginationNextRect = null;     // › 下一页
    private int[][] paginationPageRects = null;  // [N] 数字按钮, 每项 [x,y,w,h,pageIndex]

    // === 分类列表 (建筑 tab 右侧) ===
    /** 单个分类项 hit rect, 用于点击检测. 每项 [x, y, w, h]. */
    private final List<int[]> categoryItemRects = new ArrayList<>();
    /** 分类列表 "添加" 按钮 (弹 GuiCategoryManager) hit rect. */
    private int[] categoryAddBtnRect = null;
    /** 分类面板隐藏按钮 hit rect (右上角 ◀ 收起 / 隐藏状态下左边缘 ▶ 展开). */
    private int[] categoryToggleBtnRect = null;
    /** 分类面板是否被玩家收起. 收起后只显示一个 ▶ 小按钮 + 卡片占满全宽. */
    private boolean categoryPanelHidden = false;

    {
        // 默认值用静态记忆的"上次状态" (玩家在 Initialize() 之前 new GUI 也会先走这里)
        this.categoryPanelHidden = GuiExtensionPackBrowser.rememberedPanelHidden;
        this.currentCategory = GuiExtensionPackBrowser.rememberedCategory;
        this.scrollOffsetCards = GuiExtensionPackBrowser.rememberedPage;
    }

    // === 缓存的依赖检测结果 ===
    private final java.util.Map<String, java.util.List<String>> depCheckMissing = new java.util.HashMap<>();
    private int statusTick = 0;
    private String statusMessage = null;
    private int statusColor = 0x55FF55;

    // === 缓存封面图 (按 pack.getPackageName() || pack.getFileName() 索引) ===
    private final java.util.Map<String, ResourceLocation> coverTextureCache = new java.util.HashMap<>();

    // === 缓存建筑预览图 (按 constructionId 索引) ===
    private final java.util.Map<String, ResourceLocation> previewTextureCache = new java.util.HashMap<>();

    // === 按钮 (顶部 title bar) ===
    private ExtendedButton btnBack;          // 次级视图返回 (左上角)
    private ExtendedButton btnSync;          // 同步服务器包
    private ExtendedButton btnCheckDeps;     // 检测依赖
    private ExtendedButton btnOpenFolder;    // 打开拓展包文件夹
    private ExtendedButton btnClose;         // 关闭 (右上角)

    // === 输入框 (搜索用) ===
    private net.minecraft.client.gui.components.EditBox searchBox;

    // === 下载 Tab: 扫描本地 prefab-download/ 目录里的单文件建筑 ===
    // 新模式: 网站下载的建筑 = .nbt + .txt + .png 三件套, 直接放进 .minecraft/prefab-download/
    // 这里每次切到下载 tab 时都会重新扫描, 不依赖 web 服务器拉列表.
    private java.util.List<LocalBuilding> downloadedBuildings = new ArrayList<>();
    /** 预览图缓存: buildingId -> ResourceLocation. 优先读本地 .png, 没有则用占位符. */
    private final java.util.Map<String, ResourceLocation> localImageCache = new java.util.HashMap<>();
    /** 本地图片加载去重, 防止同一张图被并发读. */
    private final java.util.Set<String> localImageLoading = new java.util.HashSet<>();

    // === 服务器 Tab: 同步建筑缩略图缓存 ===
    /** server-cache/ 已同步建筑缩略图: name -> ResourceLocation. */
    private final java.util.Map<String, ResourceLocation> serverImageCache = new java.util.HashMap<>();
    /** 同步按钮 hit rect: name -> [x, y, w, h]. */
    private final java.util.Map<String, int[]> serverCardSyncBtnRects = new java.util.HashMap<>();
    /** 缩略图加载去重: name -> "loading" 标志 (用 Set). */
    private final java.util.Set<String> serverImageLoading = new java.util.HashSet<>();

    // === 下载 Tab: 网站建筑列表 (从 /api/buildings 拉) ===
    /** 网站上所有用户上传的建筑. 进游戏首次进入下载 tab 时拉取, 之后手动刷新. */
    private java.util.List<BuildingInfo2> websiteBuildings = new ArrayList<>();
    /** 网站建筑是否正在拉取中. */
    private boolean websiteBuildingsLoading = false;
    /** 网站拉取失败时的错误信息. null = 没出错或还没拉过. */
    private String websiteBuildingsError = null;
    /** 网站建筑预览图缓存: id -> ResourceLocation. */
    private final java.util.Map<String, ResourceLocation> websiteImageCache = new java.util.HashMap<>();
    /** 网站建筑图片正在加载中的 id 集合. */
    private final java.util.Set<String> websiteImageLoading = new java.util.HashSet<>();
    /** 正在下载的建筑 id (用于显示进度 + 禁用按钮). */
    private final java.util.Set<String> websiteDownloading = new java.util.HashSet<>();
    /** 下载进度: id -> (downloaded, total, percent). */
    private final java.util.Map<String, double[]> websiteDownloadProgress = new java.util.HashMap<>();
    /** "打开网站" 按钮位置. */
    private int[] lastOpenWebsiteButtonRect = null;
    /** 过滤下拉: "all" / "downloaded" / "not_downloaded". 默认 "all". */
    private String websiteFilter = "all";
    /** 过滤下拉按钮位置. */
    private int[] websiteFilterButtonRect = null;
    /** 卡片 hit rect: buildingId -> [x, y, w, h]. */
    private final java.util.Map<String, int[]> websiteCardHitRects = new java.util.LinkedHashMap<>();
    /** 卡片下载按钮 hit rect: buildingId -> [x, y, w, h]. */
    private final java.util.Map<String, int[]> websiteCardDownloadBtnRects = new java.util.LinkedHashMap<>();

    // === 云端建筑 Tab: 玩家自存建筑 (服务器/单机都走 CloudBuildingClientCache) ===
    /** 云端建筑卡片 hit rect: buildingId -> [x, y, w, h]. */
    private final java.util.Map<String, int[]> cloudCardHitRects = new java.util.LinkedHashMap<>();
    /** 云端建筑「收回」按钮 hit rect: buildingId -> [x, y, w, h]. */
    private final java.util.Map<String, int[]> cloudCardRecallBtnRects = new java.util.LinkedHashMap<>();
    /** 云端建筑「放出」按钮 hit rect: buildingId -> [x, y, w, h]. */
    private final java.util.Map<String, int[]> cloudCardSummonBtnRects = new java.util.LinkedHashMap<>();
    /** 云端建筑「删除」按钮 hit rect: buildingId -> [x, y, w, h]. */
    private final java.util.Map<String, int[]> cloudCardDeleteBtnRects = new java.util.LinkedHashMap<>();
    /** 云端建筑「导航」按钮 hit rect: buildingId -> [x, y, w, h]. 仅在装了 Xaero 且建筑已放出时启用. */
    private final java.util.Map<String, int[]> cloudCardNavigateBtnRects = new java.util.LinkedHashMap<>();
    /** 「导航」按钮调试日志已打过的 buildingId 集合, 每个建筑只打一次避免刷屏. */
    private final java.util.Set<String> navigateDebugLogged = new java.util.HashSet<>();
    /** 待删除的云端建筑 (非空时显示确认弹窗). */
    private String pendingDeleteBuildingId = null;
    /** 确认弹窗「取消」按钮 hit rect. */
    private int[] deleteConfirmCancelRect = null;
    /** 确认弹窗「确定」按钮 hit rect. */
    private int[] deleteConfirmOkRect = null;
    /** 上次进入云端 tab 的 tick, 用于节流刷新 (进 tab 后 1s 内最多打 1 次服务端重发). */
    private long cloudTabLastEnterTickMs = 0;
    /** 云端建筑缩略图缓存: buildingId -> ResourceLocation. 按 cb.name 在 LocalBuildingScanner 里找同名建筑,
     *  读 .png 加载. 找不到/加载失败时该 entry 不存在, 卡片显示首字符占位符. */
    private final java.util.Map<String, ResourceLocation> cloudThumbCache = new java.util.HashMap<>();
    /** 云端建筑缩略图正在加载中的 buildingId 集合 (去重). */
    private final java.util.Set<String> cloudThumbLoading = new java.util.HashSet<>();

    /** 切到下载 tab 时被调用, 扫描 prefab-download/ 并加载预览图. */
    private void refreshDownloadedBuildings() {
        this.downloadedBuildings = LocalBuildingScanner.scanDir(
            LocalBuildingScanner.getDownloadRoot(), "download");
        // 触发缺图建筑的本地图片加载
        for (LocalBuilding lb : this.downloadedBuildings) {
            if (lb.hasPreviewImage() && !this.localImageCache.containsKey(lb.id)
                && !this.localImageLoading.contains(lb.id)) {
                this.localImageLoading.add(lb.id);
                triggerLoadLocalImage(lb);
            }
        }
    }

    /**
     * 读取本地 .png 加载为 texture.
     * 失败/无图时不缓存 (UI 会用占位符).
     */
    private void triggerLoadLocalImage(LocalBuilding lb) {
        Path imgPath = lb.imagePath;
        if (imgPath == null || !Files.exists(imgPath)) return;
        CompletableFuture.runAsync(() -> {
            try (InputStream is = Files.newInputStream(imgPath)) {
                BufferedImage img = ImageIO.read(is);
                if (img == null) return;
                Minecraft.getInstance().execute(() -> {
                    try {
                        DynamicTexture tex = uploadIconTexture(img);
                        if (tex == null) return;
                        ResourceLocation loc = Minecraft.getInstance().getTextureManager()
                            .register("prefab_dl_" + lb.id, tex);
                        this.localImageCache.put(lb.id, loc);
                    } catch (Exception e) {
                        PrefabCustomAddon.LOGGER.warn("[DOWNLOAD-TAB] local image upload failed for {}", lb.id, e);
                    }
                });
            } catch (Exception e) {
                PrefabCustomAddon.LOGGER.warn("[DOWNLOAD-TAB] local image read failed for {}", lb.id, e);
            }
        });
    }

    // === 缩略图最大边长 ===
    // 之前 128 太小, 源图(1920x1080 截图)经 bilinear 下采样到 128 后会丢很多细节,
    // 再用 nearest 像素投到 60x60 卡片上会糊成"被 JPEG 压缩"的样子.
    // 256 是平衡: 60x60 卡片 (含 2x DPR 余量 120) 4x over-sample, 显存 256x256x4 = 256KB/张可接受,
    // bicubic 下采样比 bilinear 锐利得多, 最终 bilinear 投到屏幕平滑.
    private static final int ICON_MAX_DIM = 256;

    /**
     * 把 BufferedImage 缩到 ICON_MAX_DIM 以内, 上传为 Minecraft DynamicTexture.
     * 失败返回 null.
     *
     * <p>之前两处都是用原图尺寸上传: 玩家手贱加一张 1920x1080 截图 (1.7 MB) 当建筑图,
     * 会导致: ① getRGB 单像素 JNI 调用跑 200 万次, 渲染线程卡几秒;
     * ② GPU 申请几十 MB 纹理, 部分驱动会静默失败 → UI 显示占位符, 跟"图片没生效" 表现一样.
     * 这里用 Graphics2D 一次性 bilinear 缩到 ≤128, 既不卡顿, 又一定能在驱动允许的范围内上传.</p>
     */
    private static DynamicTexture uploadIconTexture(BufferedImage img) {
        if (img == null) return null;
        int w = img.getWidth(), h = img.getHeight();
        if (w <= 0 || h <= 0) return null;

        // === Step 1: 中心裁剪成正方形 ===
        // 卡片是 iconSize×iconSize 正方形显示, 但原图往往是 16:9 截图 / 4:3 缩略图 / 竖屏.
        // 之前用 Math.min(scale) 等比缩, 16:9 → 256×144 → 投到 100×100 上下留黑条, 看起来"四周发黑".
        // 缩略图通用做法: 中心 crop 成正方形, 再缩到 ICON_MAX_DIM, 永远铺满不黑边.
        // 中心 crop (而不是 top/bottom crop) 保证主体 (一般是建筑中段) 不被切掉.
        int cropSide = Math.min(w, h);
        int cropX = (w - cropSide) / 2;
        int cropY = (h - cropSide) / 2;
        if (cropSide < w || cropSide < h) {
            BufferedImage cropped = img.getSubimage(cropX, cropY, cropSide, cropSide);
            // getSubimage 共享底层 raster, 不能直接 dispose 原图, 这里 copy 一份脱离
            BufferedImage croppedCopy = new BufferedImage(cropSide, cropSide, BufferedImage.TYPE_INT_ARGB);
            croppedCopy.createGraphics().drawImage(cropped, 0, 0, null);
            img = croppedCopy;
            w = cropSide;
            h = cropSide;
        }

        // === Step 2: 下采样到 ICON_MAX_DIM (已经是正方形, 缩出来就是正方形) ===
        if (w > ICON_MAX_DIM) {
            double scale = (double) ICON_MAX_DIM / w;
            int nw = ICON_MAX_DIM;
            int nh = ICON_MAX_DIM;  // 因为已 crop 成正方形, 缩出来仍是正方形
            BufferedImage scaled = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = scaled.createGraphics();
            try {
                // BICUBIC 比 BILINEAR 锐利得多, 边角细节 (MC 截图里的方块/树叶) 不会糊.
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                    java.awt.RenderingHints.VALUE_RENDER_QUALITY);
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.drawImage(img, 0, 0, nw, nh, null);
            } finally {
                g.dispose();
            }
            img = scaled;
            w = nw;
            h = nh;
        }
        DynamicTexture tex = new DynamicTexture(w, h, false);
        // bilinear: 源已 256 (4x over-sample), 投到 60x60 时平滑; nearest 会有马赛克.
        // (drawIconNearest 名字虽然叫 Nearest, 但实际走 GuiGraphics.blit, 用的是纹理自己的 filter)
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

    // === 云端建筑 Tab: 缩略图懒加载 ===

    /**
     * 加载云端建筑缩略图.
     * 优先级:
     *   1) {@code b.thumbnailPng} — 建造时从 ConstructionInfo 嵌入的图 (跟建筑强绑定, 不依赖磁盘文件)
     *   2) 按 cb.name 找同名 LocalBuilding, 读它的 .png
     *   3) 都没有 → 不写 cache, 卡片显示首字符占位符
     */
    private void triggerLoadCloudThumb(CloudBuilding b) {
        if (b == null || b.id == null) return;
        PrefabCustomAddon.LOGGER.info("[CLOUD-THUMB] 尝试为云端建筑 {} (id={}) 加载缩略图", b.name, b.id);

        // 1) 优先用嵌入的图
        if (b.thumbnailPng != null && b.thumbnailPng.length > 0) {
            byte[] data = b.thumbnailPng;
            CompletableFuture.runAsync(() -> {
                try {
                    BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(data));
                    if (img == null) {
                        PrefabCustomAddon.LOGGER.warn("[CLOUD-THUMB] 嵌入图解码失败, 尝试 LocalBuilding 兜底");
                        loadCloudThumbFromLocalBuilding(b);
                        return;
                    }
                    int w = img.getWidth(), h = img.getHeight();
                    if (w <= 0 || h <= 0) {
                        loadCloudThumbFromLocalBuilding(b);
                        return;
                    }
                    final int ww = w, hh = h;
                    Minecraft.getInstance().execute(() -> uploadCloudThumb(b, img, ww, hh, "embedded"));
                } catch (Exception e) {
                    PrefabCustomAddon.LOGGER.warn("[CLOUD-THUMB] 嵌入图读取失败, 尝试 LocalBuilding 兜底: {}", e.toString());
                    loadCloudThumbFromLocalBuilding(b);
                }
            });
            return;
        }

        // 2) 嵌入图为空, 走 LocalBuilding 兜底
        loadCloudThumbFromLocalBuilding(b);
    }

    /** 兜底路径: 按 cb.name 找同名 LocalBuilding, 读 .png. */
    private void loadCloudThumbFromLocalBuilding(CloudBuilding b) {
        CompletableFuture.runAsync(() -> {
            try {
                java.util.List<LocalBuilding> all = LocalBuildingScanner.scanAll();
                PrefabCustomAddon.LOGGER.info("[CLOUD-THUMB-FALLBACK] 扫描到 {} 个 LocalBuilding, 目标 name='{}'",
                    all.size(), b.name);
                LocalBuilding matched = null;
                for (LocalBuilding lb : all) {
                    if (lb.name != null && lb.name.equals(b.name)) {
                        matched = lb;
                        break;
                    }
                }
                if (matched == null) {
                    PrefabCustomAddon.LOGGER.warn("[CLOUD-THUMB-FALLBACK] 没找到同名 LocalBuilding (name='{}'), 走首字符占位符", b.name);
                    return;
                }
                if (matched.imagePath == null || !Files.exists(matched.imagePath)) {
                    PrefabCustomAddon.LOGGER.warn("[CLOUD-THUMB-FALLBACK] 匹配建筑没图片, 走首字符占位符");
                    return;
                }
                try (InputStream is = Files.newInputStream(matched.imagePath)) {
                    BufferedImage img = ImageIO.read(is);
                    if (img == null) return;
                    int w = img.getWidth(), h = img.getHeight();
                    if (w <= 0 || h <= 0) return;
                    final int ww = w, hh = h;
                    Minecraft.getInstance().execute(() -> uploadCloudThumb(b, img, ww, hh, "fallback"));
                }
            } catch (Exception e) {
                PrefabCustomAddon.LOGGER.warn("[CLOUD-THUMB-FALLBACK] 读图失败 {}", b.id, e);
            }
        });
    }

    /** 上传 BufferedImage 到 GPU, 写进 cloudThumbCache. */
    private void uploadCloudThumb(CloudBuilding b, BufferedImage img, int ww, int hh, String source) {
        try {
            DynamicTexture tex = new DynamicTexture(ww, hh, false);
            tex.setFilter(false, false);
            NativeImage pixels = tex.getPixels();
            for (int y = 0; y < hh; y++) {
                for (int x = 0; x < ww; x++) {
                    int argb = img.getRGB(x, y);
                    int abgr = ((argb & 0xFF00FF00) | ((argb & 0x00FF0000) >> 16) | ((argb & 0x000000FF) << 16));
                    pixels.setPixelRGBA(x, y, abgr);
                }
            }
            tex.upload();
            ResourceLocation loc = Minecraft.getInstance().getTextureManager()
                .register("prefab_cloud_" + b.id, tex);
            this.cloudThumbCache.put(b.id, loc);
            PrefabCustomAddon.LOGGER.info("[CLOUD-THUMB] 加载成功 (来源={}, {}x{}) -> {}", source, ww, hh, loc);
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("[CLOUD-THUMB] upload failed for {}", b.id, e);
        }
    }

    // === 下载 Tab: 网站建筑列表 ===

    /** 触发从网站拉建筑列表 (异步). 拉完后会自动触发缺图建筑的图片下载. */
    private void refreshWebsiteBuildings() {
        if (this.websiteBuildingsLoading) return;
        this.websiteBuildingsLoading = true;
        this.websiteBuildingsError = null;
        PackDownloadManager.getInstance().fetchBuildingListAsync()
            .thenAccept(list -> {
                Minecraft.getInstance().execute(() -> {
                    this.websiteBuildings = list;
                    this.websiteBuildingsLoading = false;
                    this.websiteBuildingsError = null;
                    // 触发缺图建筑的预览图下载
                    for (BuildingInfo2 b : list) {
                        if (b == null || b.id == null) continue;
                        if (this.websiteImageCache.containsKey(b.id)
                            || this.websiteImageLoading.contains(b.id)) continue;
                        triggerLoadWebsiteImage(b);
                    }
                });
            })
            .exceptionally(ex -> {
                Minecraft.getInstance().execute(() -> {
                    this.websiteBuildingsLoading = false;
                    this.websiteBuildingsError = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                    PrefabCustomAddon.LOGGER.warn("[DOWNLOAD-TAB] fetch building list failed: {}", this.websiteBuildingsError);
                });
                return null;
            });
    }

    /** 触发单个网站建筑的预览图下载 (异步, 结果会进 websiteImageCache). */
    private void triggerLoadWebsiteImage(BuildingInfo2 b) {
        if (b == null || b.id == null) return;
        this.websiteImageLoading.add(b.id);
        PackDownloadManager.getInstance().fetchBuildingImageAsync(b)
            .thenAccept(data -> {
                Minecraft.getInstance().execute(() -> {
                    if (data == null || data.length == 0) {
                        this.websiteImageLoading.remove(b.id);
                        return;
                    }
                    try {
                        BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(data));
                        if (img == null) {
                            this.websiteImageLoading.remove(b.id);
                            return;
                        }
                        int w = img.getWidth(), h = img.getHeight();
                        if (w <= 0 || h <= 0) {
                            this.websiteImageLoading.remove(b.id);
                            return;
                        }
                        DynamicTexture tex = new DynamicTexture(w, h, false);
                        tex.setFilter(false, false);
                        NativeImage pixels = tex.getPixels();
                        for (int y = 0; y < h; y++) {
                            for (int x = 0; x < w; x++) {
                                int argb = img.getRGB(x, y);
                                int abgr = ((argb & 0xFF00FF00) | ((argb & 0x00FF0000) >> 16) | ((argb & 0x000000FF) << 16));
                                pixels.setPixelRGBA(x, y, abgr);
                            }
                        }
                        tex.upload();
                        ResourceLocation loc = Minecraft.getInstance().getTextureManager()
                            .register("prefab_web_" + b.id, tex);
                        this.websiteImageCache.put(b.id, loc);
                        this.websiteImageLoading.remove(b.id);
                    } catch (Exception e) {
                        PrefabCustomAddon.LOGGER.warn("[DOWNLOAD-TAB] website image upload failed for {}", b.id, e);
                        this.websiteImageLoading.remove(b.id);
                    }
                });
            })
            .exceptionally(ex -> {
                this.websiteImageLoading.remove(b.id);
                return null;
            });
    }

    /** 触发单个网站建筑下载. 完成后自动刷新本地列表 + 网站状态. */
    private void startWebsiteDownload(BuildingInfo2 b) {
        if (b == null || b.id == null) return;
        if (this.websiteDownloading.contains(b.id)) return;
        this.websiteDownloading.add(b.id);
        this.websiteDownloadProgress.put(b.id, new double[]{0, 0, 0});
        PackDownloadManager.getInstance().downloadBuildingStreaming(b,
            new PackDownloadManager.ProgressCallback() {
                @Override public void onStart(String packId) {}
                @Override public void onProgress(long downloaded, long total, double percent) {
                    Minecraft.getInstance().execute(() -> {
                        websiteDownloadProgress.put(b.id, new double[]{downloaded, total, percent});
                    });
                }
                @Override public void onComplete(Path savedTo) {
                    Minecraft.getInstance().execute(() -> {
                        websiteDownloading.remove(b.id);
                        websiteDownloadProgress.remove(b.id);
                        // 重新扫描本地, 已下载的会自动出现在建筑 tab
                        refreshDownloadedBuildings();
                        setStatus(tr("browser.status.downloaded", b.name), 0x55FF55);
                    });
                }
                @Override public void onError(String error) {
                    Minecraft.getInstance().execute(() -> {
                        websiteDownloading.remove(b.id);
                        websiteDownloadProgress.remove(b.id);
                        setStatus(tr("browser.status.download_failed", error), 0xFF5555);
                    });
                }
            });
    }

    /** 打开网站主页 (浏览器). */
    private void openWebsiteHome() {
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

    /** 判断一个网站建筑是否已经下载到本地. */
    private boolean isWebsiteBuildingDownloaded(BuildingInfo2 b) {
        if (b == null) return false;
        // 用文件名匹配: 建筑名+后缀 或 建筑名+_2+后缀 等
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

    public GuiExtensionPackBrowser() {
        super("Extension Pack Browser");
        // 触发热重载检测, 避免管理员加了新包还得重启游戏
        ExtensionPackManager.getInstance().reloadIfChanged();
        // 诊断: 进 GUI 时直接强制重扫一次, 避免热重载判定漏掉 (Windows dir mtime 偶尔不更新)
        ExtensionPackManager.getInstance().forceReload();
        PrefabCustomAddon.LOGGER.info("[DIAG-OPEN-GUI] 进建筑浏览器 GUI, 当前 packs 数量: {}",
            ExtensionPackManager.getInstance().getPacks().size());
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new GuiExtensionPackBrowser());
    }

    // ============================================================
    // 初始化
    // ============================================================

    @Override
    protected void Initialize() {
        // 显式从 static 字段恢复状态 (instance initializer 在 super() 之前跑, 可能被 super 覆盖)
        this.categoryPanelHidden = GuiExtensionPackBrowser.rememberedPanelHidden;
        this.currentCategory = GuiExtensionPackBrowser.rememberedCategory;
        this.scrollOffsetCards = GuiExtensionPackBrowser.rememberedPage;
        super.Initialize();
        this.modifiedInitialXAxis = PANEL_W / 2;
        this.modifiedInitialYAxis = PANEL_H / 2;
        this.imagePanelWidth = PANEL_W;
        this.imagePanelHeight = PANEL_H;
        this.shownImageHeight = 1;
        this.shownImageWidth = 1;

        int[] pos = computePanelPos();
        int grayBoxX = pos[0];
        int grayBoxY = pos[1];
        int tbY = grayBoxY + 1;
        int tbH = 18;

        // 按用户要求: 顶部只保留一个返回按钮 (drilldown 时显示), 其它功能移到底部或内嵌
        // 左上角: 返回 (drilldown 时显示)
        this.btnBack = this.createAndAddButton(
            grayBoxX + 2, tbY, 28, tbH, "←");
        this.btnBack.visible = false;  // 默认隐藏

        // 右上的 4 个按钮 (X/F/S/?) 改放到每个 tab 的页脚位置, 由 tab 渲染时按需创建
        // 这里不再创建

        // === 搜索框 (在右面板顶部) ===
        // 宽度: 建筑 tab 用短款 (SEARCH_BOX_W=110, 右侧让出位置给分类列表), 其它 tab 用全宽.
        // 简单做法: 初始建为全宽, switchTab 时按需重新建. 详见 switchTab() 里的 rebuildSearchBox().
        int sbX = grayBoxX + TABS_W + 6;
        int sbY = grayBoxY + 4;
        int initialW = isBuildingsTab() ? SEARCH_BOX_W : SEARCH_FULL_W;
        this.searchBox = new net.minecraft.client.gui.components.EditBox(
            this.font, sbX, sbY, initialW, SEARCH_H - 2,
            net.minecraft.network.chat.Component.literal(tr("browser.search.placeholder")));
        this.searchBox.setMaxLength(64);
        this.searchBox.setBordered(true);
        this.searchBox.setVisible(false);  // 默认隐藏, tab 切换时再显示
        // 文本变化回调: 同步到 searchText, 触发过滤
        this.searchBox.setResponder(text -> {
            this.searchText = text;
            this.scrollOffsetCards = 0;  // 搜索词变了, 重置翻页
        });
        this.addRenderableWidget(this.searchBox);

        // 预扫描本地 prefab-download/, 切到下载 tab 时不用再扫
        refreshDownloadedBuildings();

        PrefabCustomAddon.LOGGER.info("[BROWSER-NEW] Initialize: panel {}x{} at ({},{}), screen={}x{}, tabs={}",
            PANEL_W, PANEL_H, grayBoxX, grayBoxY, this.width, this.height, TABS_W);
    }


    // ============================================================
    // 渲染
    // ============================================================

    @Override
    public void tick() {
        super.tick();
        if (this.statusTick > 0) this.statusTick--;
        // 缩略图缓存新增 → 清空 thumb texture cache 强制重读
        if (com.prefab.addon.client.ThumbnailCache.pollCompleted()) {
            this.cachedThumbTextureCache.clear();
        }
    }

    /**
     * 计算 panel 实际位置, 强制 clamp 到屏幕范围内, 避免高 GUI 缩放下被裁切.
     * 返回 [grayBoxX, grayBoxY].
     */
    private int[] computePanelPos() {
        int x = (this.width / 2) - this.modifiedInitialXAxis;
        int y = (this.height / 2) - this.modifiedInitialYAxis;
        if (x < 4) x = 4;
        if (y < 4) y = 4;
        if (x + PANEL_W > this.width - 4) x = Math.max(4, this.width - PANEL_W - 4);
        if (y + PANEL_H > this.height - 4) y = Math.max(4, this.height - PANEL_H - 4);
        return new int[]{x, y};
    }

    @Override
    protected void preButtonRender(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, float partialTicks) {
        // 自己画一个简洁的统一背景 (不再用 drawControlBackground, 避免其默认的"突出来"白条)
        int[] pos = computePanelPos();
        int grayBoxX = pos[0];
        int grayBoxY = pos[1];
        // 主面板: 深灰底 + 1px 浅灰边框
        guiGraphics.fill(grayBoxX, grayBoxY, grayBoxX + PANEL_W, grayBoxY + PANEL_H, 0xFF1A1A1A);
        guiGraphics.fill(grayBoxX, grayBoxY, grayBoxX + PANEL_W, grayBoxY + 1, 0xFF555555);
        guiGraphics.fill(grayBoxX, grayBoxY + PANEL_H - 1, grayBoxX + PANEL_W, grayBoxY + PANEL_H, 0xFF555555);
        guiGraphics.fill(grayBoxX, grayBoxY, grayBoxX + 1, grayBoxY + PANEL_H, 0xFF555555);
        guiGraphics.fill(grayBoxX + PANEL_W - 1, grayBoxY, grayBoxX + PANEL_W, grayBoxY + PANEL_H, 0xFF555555);

        // 左侧 tab 区域 (从顶部开始, 不再留 title bar 空间)
        this.drawControlLeftPanel(guiGraphics,
            grayBoxX + 4, grayBoxY + 2,
            TABS_W, PANEL_H - 4);

        // 右侧内容区域
        this.drawControlLeftPanel(guiGraphics,
            grayBoxX + TABS_W + 4, grayBoxY + 2,
            PANEL_W - TABS_W - 8, PANEL_H - 4);
    }

    @Override
    protected void postButtonRender(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, float partialTicks) {
        int[] pos = computePanelPos();
        int grayBoxX = pos[0];
        int grayBoxY = pos[1];

        // 顶部不再画标题文字 (按用户要求去掉 "拓展包 / 建筑管理" 和 "按 Z 打开此界面")

        // 左侧 tabs
        drawTabs(guiGraphics, grayBoxX, grayBoxY, mouseX, mouseY);

        // 右侧内容
        drawContent(guiGraphics, grayBoxX, grayBoxY, mouseX, mouseY);

        // 底部状态栏
        if (this.statusMessage != null && this.statusTick > 0) {
            int statusY = grayBoxY + PANEL_H - 14;
            int sw = this.font.width(this.statusMessage);
            int statusX = grayBoxX + (PANEL_W - sw) / 2;
            guiGraphics.fill(statusX - 6, statusY - 2, statusX + sw + 6, statusY + 12, 0xC0000000);
            guiGraphics.drawString(this.font, this.statusMessage, statusX, statusY, this.statusColor);
        }
    }

    // ============================================================
    // 左侧 Tab 栏
    // ============================================================

    /** Tab 项 y 坐标 (顶部到下, 紧贴面板顶部) */
    private int getTabY(int index) {
        int[] pos = computePanelPos();
        return pos[1] + 4 + index * 22;
    }

    private int getTabX() {
        int[] pos = computePanelPos();
        return pos[0] + 4;
    }

    private void drawTabs(GuiGraphics guiGraphics, int grayBoxX, int grayBoxY, int mouseX, int mouseY) {
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            Tab t = tabs[i];
            int ty = getTabY(i);
            int tx = getTabX();
            int tw = TABS_W - 4;
            int th = 22;

            boolean active = (i == this.currentTab.ordinal()) && this.currentDrilldownPack == null;
            boolean hovered = mouseX >= tx && mouseX <= tx + tw
                && mouseY >= ty && mouseY <= ty + th;

            int bg;
            int textColor;
            if (active) {
                bg = 0xFF4A6FA5;
                textColor = 0xFFFFFF;
            } else if (hovered) {
                bg = 0xFF3A3A3A;
                textColor = 0xFFFFFF;
            } else {
                bg = 0xFF2A2A2A;
                textColor = 0xCCCCCC;
            }
            guiGraphics.fill(tx, ty, tx + tw, ty + th, bg);
            if (active) {
                // 选中标记条
                guiGraphics.fill(tx, ty, tx + 3, ty + th, 0xFF55AAFF);
            }
            guiGraphics.drawString(this.font, t.label, tx + 8, ty + 7, textColor);
        }
    }

    private Tab tabHitTest(int mouseX, int mouseY) {
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            int ty = getTabY(i);
            int tx = getTabX();
            int tw = TABS_W - 4;
            int th = 22;
            if (mouseX >= tx && mouseX <= tx + tw
                && mouseY >= ty && mouseY <= ty + th) {
                return tabs[i];
            }
        }
        return null;
    }

    // ============================================================
    // 右侧内容: 5 个 tab 各自的渲染
    // ============================================================

    /**
     * 右内容区: 减去 tabs 和 search 的可用区域.
     * return [x, y, w, h] 用于放卡片网格.
     * 注意: BUILDINGS tab 右栏还有分类列表 (CAT_LIST_W 宽), 卡片可用宽度要再减掉.
     * 调用方按需决定要不要减 (buildings tab 走自己的 getBuildingsCardRect, 其它 tab 用本返回值).
     */
    private int[] getContentRect(int grayBoxX, int grayBoxY) {
        int cx = grayBoxX + TABS_W + 6;
        int cy = grayBoxY + 4;
        int cw = PANEL_W - TABS_W - 12;
        boolean needSearch = isBuildingsTab() || this.currentTab == Tab.FAVORITES;
        int ch = PANEL_H - 8;
        if (needSearch) {
            cy += SEARCH_H;
            ch -= SEARCH_H;
        }
        return new int[]{cx, cy, cw, ch};
    }

    /**
     * 建筑 tab 的卡片区 rect: 从 getContentRect() 减掉右侧分类列表.
     * 高度也再减 2 留 padding.
     * 分类面板被收起时, 卡片用全宽 (只让出隐藏状态下的 ▶ 按钮宽度, ≈14px).
     */
    private int[] getBuildingsCardRect(int grayBoxX, int grayBoxY) {
        int[] base = getContentRect(grayBoxX, grayBoxY);
        if (this.categoryPanelHidden) {
            return new int[]{base[0], base[1] + 2, base[2] - 18, base[3] - 2};
        }
        return new int[]{base[0], base[1] + 2, base[2] - CAT_LIST_W - 4, base[3] - 2};
    }

    private void drawContent(GuiGraphics guiGraphics, int grayBoxX, int grayBoxY, int mouseX, int mouseY) {
        // 搜索框可见性
        boolean needSearch = isBuildingsTab() || this.currentTab == Tab.FAVORITES;
        this.searchBox.setVisible(needSearch);
        if (needSearch) {
            String hint = this.currentTab == Tab.FAVORITES
                ? tr("browser.search.placeholder_favorites")
                : tr("browser.search.placeholder_buildings");
            // 绘制占位提示 (EditBox 自身没法画 hint, 简单做: 文本框为空时画灰色 hint)
            if (this.searchBox.getValue().isEmpty()) {
                int sbX = this.searchBox.getX();
                int sbY = this.searchBox.getY();
                guiGraphics.drawString(this.font, hint, sbX + 4, sbY + 4, 0xFF888888);
            }
        }

        // 派发到具体 tab 渲染
        if (this.currentDrilldownPack != null) {
            drawPackDrilldown(guiGraphics, grayBoxX, grayBoxY, mouseX, mouseY);
            return;
        }
        switch (this.currentTab) {
            case BUILDINGS: drawTabBuildings(guiGraphics, grayBoxX, grayBoxY, mouseX, mouseY); break;
            case CLOUD:     drawTabCloud(guiGraphics, grayBoxX, grayBoxY, mouseX, mouseY); break;
            case SERVERS:   drawTabServers(guiGraphics, grayBoxX, grayBoxY, mouseX, mouseY); break;
            case FAVORITES: drawTabFavorites(guiGraphics, grayBoxX, grayBoxY, mouseX, mouseY); break;
            case DOWNLOAD:  drawTabDownload(guiGraphics, grayBoxX, grayBoxY, mouseX, mouseY); break;
        }
        // 顶层覆盖: 云端删除确认弹窗 (盖在所有 tab 上, 强制玩家先确认/取消)
        if (this.currentTab == Tab.CLOUD && this.pendingDeleteBuildingId != null) {
            drawDeleteConfirmOverlay(guiGraphics, grayBoxX, grayBoxY, mouseX, mouseY);
        }
    }

    /** 云端建筑删除确认弹窗: 居中模态. */
    private void drawDeleteConfirmOverlay(GuiGraphics guiGraphics, int grayBoxX, int grayBoxY, int mouseX, int mouseY) {
        CloudBuilding b = CloudBuildingClientCache.getInstance().getById(this.pendingDeleteBuildingId);
        String name = b != null ? b.name : "?";

        // 1) 半透明黑色蒙版
        guiGraphics.fill(0, 0, this.width, this.height, 0xC0000000);

        // 2) 弹窗尺寸: 居中, 宽 220, 高 90
        int overlayW = 220;
        int overlayH = 90;
        int overlayX = (this.width - overlayW) / 2;
        int overlayY = (this.height - overlayH) / 2;
        guiGraphics.fill(overlayX, overlayY, overlayX + overlayW, overlayY + overlayH, 0xEE1A1A1A);
        guiGraphics.fill(overlayX, overlayY, overlayX + overlayW, overlayY + 1, 0xFFAA3333);
        guiGraphics.fill(overlayX, overlayY + overlayH - 1, overlayX + overlayW, overlayY + overlayH, 0xFF555555);
        guiGraphics.fill(overlayX, overlayY, overlayX + 1, overlayY + overlayH, 0xFF555555);
        guiGraphics.fill(overlayX + overlayW - 1, overlayY, overlayX + overlayW, overlayY + overlayH, 0xFF555555);

        // 3) 标题
        guiGraphics.drawCenteredString(this.font, tr("delete.title"),
            overlayX + overlayW / 2, overlayY + 8, 0xFF5555);
        // 4) 建筑名 (截断)
        String nameLine = "§f" + truncate(name, 28);
        guiGraphics.drawCenteredString(this.font, nameLine,
            overlayX + overlayW / 2, overlayY + 24, 0xFFFFFF);
        // 5) 警告
        guiGraphics.drawCenteredString(this.font, tr("delete.body"),
            overlayX + overlayW / 2, overlayY + 40, 0xAAAAAA);

        // 6) [取消] [确定] 两个按钮
        int btnW = 80, btnH = 18, btnGap = 16;
        int btnY = overlayY + overlayH - btnH - 10;
        int cancelX = overlayX + overlayW / 2 - btnGap / 2 - btnW;
        int okX = overlayX + overlayW / 2 + btnGap / 2;

        // 取消
        boolean cancelHover = mouseX >= cancelX && mouseX <= cancelX + btnW
            && mouseY >= btnY && mouseY <= btnY + btnH;
        guiGraphics.fill(cancelX, btnY, cancelX + btnW, btnY + btnH, cancelHover ? 0xFF6677AA : 0xFF445577);
        guiGraphics.drawCenteredString(this.font, tr("delete.cancel"), cancelX + btnW / 2, btnY + 5, 0xFFFFFF);
        this.deleteConfirmCancelRect = new int[]{cancelX, btnY, btnW, btnH};

        // 确定 (红色警告色)
        boolean okHover = mouseX >= okX && mouseX <= okX + btnW
            && mouseY >= btnY && mouseY <= btnY + btnH;
        guiGraphics.fill(okX, btnY, okX + btnW, btnY + btnH, okHover ? 0xFFCC3333 : 0xFFAA2222);
        guiGraphics.drawCenteredString(this.font, tr("delete.confirm"), okX + btnW / 2, btnY + 5, 0xFFFFFF);
        this.deleteConfirmOkRect = new int[]{okX, btnY, btnW, btnH};
    }

    // ----- Tab 2: 建筑 -----
    // 合并显示 prefab-extension/ 里的所有建筑:
    //   - zip 拓展包内的 construction
    //   - prefab-extension/ 根目录下的单文件建筑 (.nbt + .txt + .png)
    //   - prefab-download/ 根目录下的单文件建筑 (兼容, 因为 prefab-download/ 也算"我的")
    // 同 id 时优先用 zip 内的 (玩家本地副本).
    //
    // 跟其它 tab 不一样的地方: 右侧有一个分类列表 (CAT_LIST_W 宽), 玩家点分类后只显示该分类的建筑.
    // 搜索框也跟着缩短 (见 rebuildSearchBox). 卡片 2 列布局, 4 个/页.
    private void drawTabBuildings(GuiGraphics guiGraphics, int grayBoxX, int grayBoxY, int mouseX, int mouseY) {
        // 画分类列表 (在 drawContent 之前画, 反正后面覆盖也没关系 — 卡片区不算分类列表)
        drawCategoryList(guiGraphics, grayBoxX, grayBoxY, mouseX, mouseY);

        java.util.List<ConstructionInfo> all = getMergedConstructionsForBuildingsTab();
        java.util.List<ConstructionInfo> byCat = filterByCategory(all, this.currentCategory);
        java.util.List<ConstructionInfo> filtered = filterBySearch(byCat, this.searchText);
        drawConstructionCardsForBuildings(guiGraphics, grayBoxX, grayBoxY, mouseX, mouseY, filtered);
    }

    /**
     * 按当前选中分类过滤. currentCategory == null 表示"全部" (不过滤);
     * 非 null 时, 只保留 c.getCategoryOrDefault() 等于 currentCategory 的建筑.
     * 跟 CategoryManager.UNCATEGORIZED 配合: 玩家 .txt 留空 = 自动归到 "未分类".
     */
    private java.util.List<ConstructionInfo> filterByCategory(java.util.List<ConstructionInfo> src, String cat) {
        if (cat == null) return src;
        java.util.List<ConstructionInfo> out = new ArrayList<>();
        for (ConstructionInfo c : src) {
            if (cat.equals(c.getCategoryOrDefault())) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * 画建筑 tab 右侧的分类列表.
     * 第一项是 "全部" (点 = 清掉分类过滤), 然后是 "未分类", 再下面是用户自定义的.
     * 列表底部一个 [+] 按钮 → 弹 GuiCategoryManager.
     * 面板右侧 (右边缘外) 一个 [◀/▶] 细窄按钮 → 收起/展开分类面板. 收起时按钮上方显示当前分类名.
     */
    private void drawCategoryList(GuiGraphics guiGraphics, int grayBoxX, int grayBoxY,
                                  int mouseX, int mouseY) {
        // 重置 hit rect
        this.categoryItemRects.clear();
        this.categoryAddBtnRect = null;
        this.categoryToggleBtnRect = null;

        int[] pos = computePanelPos();
        int panelX = pos[0];
        int panelY = pos[1];

        int listX = panelX + PANEL_W - CAT_LIST_W - 4;
        int listY = panelY + 4;
        int listW = CAT_LIST_W;
        int listH = PANEL_H - 8;

        // === 收起状态: 画一个 [▶ 展开] 横向按钮 (在原分类列表的位置), 上方显示当前分类名 ===
        if (this.categoryPanelHidden) {
            int btnW = 28;
            int btnH = 12;
            // 贴在原 list 区域 (panelX + PANEL_W - CAT_LIST_W - 4) 内的顶部
            int btnX = panelX + PANEL_W - btnW - 6;
            int btnY = listY + 18;
            boolean hover = mouseX >= btnX && mouseX <= btnX + btnW
                && mouseY >= btnY && mouseY <= btnY + btnH;
            guiGraphics.fill(btnX, btnY, btnX + btnW, btnY + btnH,
                hover ? 0xFF6677AA : 0xFF445577);
            guiGraphics.drawCenteredString(this.font, "▶ 展开",
                btnX + btnW / 2, btnY + (btnH - 8) / 2, 0xFFFFFFFF);
            this.categoryToggleBtnRect = new int[]{btnX, btnY, btnW, btnH};

            // 在按钮上方显示当前分类名 (单行, 超长截断)
            String catDisplay;
            if (this.currentCategory == null) {
                catDisplay = tr("browser.category.all");
            } else {
                catDisplay = this.currentCategory;
            }
            int maxTextW = CAT_LIST_W + 4;  // 横排多给点宽度
            if (this.font.width(catDisplay) > maxTextW) {
                while (catDisplay.length() > 1 && this.font.width(catDisplay + "..") > maxTextW) {
                    catDisplay = catDisplay.substring(0, catDisplay.length() - 1);
                }
                catDisplay = catDisplay + "..";
            }
            // 右对齐到 list 区域右边缘
            int textX = listX + listW - this.font.width(catDisplay);
            if (textX < listX) textX = listX;
            int textY = listY + 4;
            guiGraphics.drawString(this.font, "§7" + catDisplay, textX, textY, 0xFFCCCCCC);
            return;
        }

        // 背景
        guiGraphics.fill(listX, listY, listX + listW, listY + listH, 0xFF1F1F1F);
        guiGraphics.fill(listX, listY, listX + listW, listY + 1, 0xFF555555);
        guiGraphics.fill(listX, listY + listH - 1, listX + listW, listY + listH, 0xFF555555);
        guiGraphics.fill(listX, listY, listX + 1, listY + listH, 0xFF555555);
        guiGraphics.fill(listX + listW - 1, listY, listX + listW, listY + listH, 0xFF555555);

        // 标题 "分类" (左对齐) + 右上角横向 [◀ 收起] 按钮
        guiGraphics.drawString(this.font, "§l" + tr("browser.category.title"),
            listX + 4, listY + 4, 0xFFFFFFFF);

        int tgW = 20;
        int tgH = 10;
        int tgX = listX + listW - tgW - 2;
        int tgY = listY + 3;
        boolean tgHover = mouseX >= tgX && mouseX <= tgX + tgW
            && mouseY >= tgY && mouseY <= tgY + tgH;
        guiGraphics.fill(tgX, tgY, tgX + tgW, tgY + tgH,
            tgHover ? 0xFF6677AA : 0xFF445577);
        guiGraphics.drawCenteredString(this.font, "◀ 收起",
            tgX + tgW / 2, tgY + (tgH - 8) / 2 + 1, 0xFFFFFFFF);
        this.categoryToggleBtnRect = new int[]{tgX, tgY, tgW, tgH};

        // 选项列表
        int itemY = listY + 16;
        int itemH = 14;
        int padX = 4;

        // 第 0 项: "全部" (currentCategory == null 时选中)
        drawCategoryItem(guiGraphics, listX, itemY, listW, itemH,
            tr("browser.category.all"), this.currentCategory == null,
            this.categoryItemRects.size());
        this.categoryItemRects.add(new int[]{listX + padX, itemY, listW - padX * 2, itemH});
        itemY += itemH;

        // 第 1 项: "未分类" (currentCategory == UNCATEGORIZED 时选中)
        drawCategoryItem(guiGraphics, listX, itemY, listW, itemH,
            CategoryManager.UNCATEGORIZED,
            CategoryManager.UNCATEGORIZED.equals(this.currentCategory),
            this.categoryItemRects.size());
        this.categoryItemRects.add(new int[]{listX + padX, itemY, listW - padX * 2, itemH});
        itemY += itemH;

        // 后面: 用户自定义分类 (顺序: getCategories() 已排好, 第 0 项是 UNCATEGORIZED, 跳过)
        java.util.List<String> all = CategoryManager.get().getCategories();
        for (int i = 1; i < all.size(); i++) {
            String name = all.get(i);
            drawCategoryItem(guiGraphics, listX, itemY, listW, itemH,
                name, name.equals(this.currentCategory), this.categoryItemRects.size());
            this.categoryItemRects.add(new int[]{listX + padX, itemY, listW - padX * 2, itemH});
            itemY += itemH;
            // 超过区域就停 (列表最多 1+1+10=12 项, 每项 14px = 168px, 区域 232px 够)
            if (itemY + itemH > listY + listH - 20) break;
        }

        // 底部 [+] 添加分类 按钮
        int btnY = listY + listH - 18;
        int btnH = 14;
        int btnW = listW - 8;
        int btnX = listX + 4;
        boolean btnHover = mouseX >= btnX && mouseX <= btnX + btnW
            && mouseY >= btnY && mouseY <= btnY + btnH;
        guiGraphics.fill(btnX, btnY, btnX + btnW, btnY + btnH,
            btnHover ? 0xFF6677AA : 0xFF445577);
        guiGraphics.drawCenteredString(this.font, "+ " + tr("browser.category.manage"),
            btnX + btnW / 2, btnY + 3, 0xFFFFFFFF);
        this.categoryAddBtnRect = new int[]{btnX, btnY, btnW, btnH};
    }

    /** 画一个分类项. idx 用于 hover 检测 (mouseX/Y 在 mouseClicked 那边比对). */
    private void drawCategoryItem(GuiGraphics guiGraphics, int x, int y, int w, int h,
                                  String label, boolean selected, int idx) {
        int bg = selected ? 0xFF3A6AAA : 0xFF1A1A1A;
        int padX = 4;
        guiGraphics.fill(x + padX, y, x + w - padX, y + h, bg);
        if (selected) {
            // 选中标记
            guiGraphics.fill(x + padX, y, x + padX + 2, y + h, 0xFF55AAFF);
        }
        // 文字: 截断, 留 2px padding
        int maxW = w - padX * 2 - 4;
        String display = label;
        if (this.font.width(display) > maxW) {
            // 简单截断: 按字符截到一定长度
            while (display.length() > 1 && this.font.width(display + "..") > maxW) {
                display = display.substring(0, display.length() - 1);
            }
            display = display + "..";
        }
        int textColor = selected ? 0xFFFFFFFF : 0xFFCCCCCC;
        guiGraphics.drawString(this.font, display, x + padX + 4, y + 3, textColor);
    }

    /**
     * 建筑 tab 专用卡片渲染: 2 列布局, 用更窄的卡片区 (右边给分类列表让位).
     */
    private void drawConstructionCardsForBuildings(GuiGraphics guiGraphics, int grayBoxX, int grayBoxY,
                                                   int mouseX, int mouseY,
                                                   java.util.List<ConstructionInfo> list) {
        int[] rect = getBuildingsCardRect(grayBoxX, grayBoxY);
        int rx = rect[0], ry = rect[1], rw = rect[2], rh = rect[3];

        if (list.isEmpty()) {
            String title, hint;
            if (this.currentCategory != null) {
                title = tr("browser.category.empty_in_category", this.currentCategory);
                hint = tr("browser.category.empty_in_category_hint");
            } else if (this.searchText != null && !this.searchText.isEmpty()) {
                title = tr("browser.empty.no_match");
                hint = tr("browser.empty.try_clear_search");
            } else {
                title = tr("browser.empty.no_buildings");
                hint = tr("browser.empty.no_buildings_hint");
            }
            drawEmpty(guiGraphics, rect, title, hint);
            return;
        }

        int cols = BUILDINGS_CARD_COLS;
        int rows = CARD_ROWS;  // 仍然 2 行
        int pageSize = cols * rows;
        int pageCount = Math.max(1, (list.size() + pageSize - 1) / pageSize);
        int page = Math.max(0, Math.min(this.scrollOffsetCards, pageCount - 1));
        int start = page * pageSize;
        int end = Math.min(start + pageSize, list.size());

        int gridW = cols * CARD_W + (cols - 1) * CARD_GAP;
        int gridX = rx + (rw - gridW) / 2;
        int gridY = ry + 2;

        for (int i = 0; i < (end - start); i++) {
            int row = i / cols;
            int col = i % cols;
            int cx = gridX + col * (CARD_W + CARD_GAP);
            int cy = gridY + row * (CARD_H + CARD_GAP);
            ConstructionInfo c = list.get(start + i);
            drawConstructionCard(guiGraphics, cx, cy, c, mouseX, mouseY);
        }

        if (pageCount > 1) {
            drawPaginationBar(guiGraphics, rx, ry + rh - 12, rw, page, pageCount, mouseX, mouseY);
        }
    }

    /**
     * 合并 zip 包 construction + 单文件 LocalBuilding 列表, 转成统一的 ConstructionInfo.
     * LocalBuilding 没有 pack 关联, 所以渲染走 localImagePath 分支 (ensurePreviewTextureLoaded 已支持).
     *
     * <p><b>注意</b>: 建筑 tab 显示全部建筑, 包括已收藏的 (收藏 tab 单独显示).
     * 之前的"过滤掉已收藏"逻辑会导致: 用户一旦点过收藏星星, 整个建筑 tab 就空了,
     * 这是个反馈死锁. 现在直接返回全部.</p>
     */
    private java.util.List<ConstructionInfo> getMergedConstructionsForBuildingsTab() {
        java.util.List<ConstructionInfo> all = new java.util.ArrayList<>(
            ExtensionPackManager.getInstance().getAllConstructionsForGui());
        java.util.Set<String> existingIds = new java.util.HashSet<>();
        for (ConstructionInfo c : all) existingIds.add(c.getId());

        java.util.List<LocalBuilding> lbs = LocalBuildingScanner.scanAll();
        PrefabCustomAddon.LOGGER.info("[BUILDINGS-TAB] ExtensionPack 建筑 {} 个, LocalBuildingScanner 扫到 {} 个, 现有 id 集合 {}",
            all.size(), lbs.size(), existingIds);
        for (LocalBuilding lb : lbs) {
            PrefabCustomAddon.LOGGER.info("[BUILDINGS-TAB]   LocalBuilding: id='{}' name='{}' file={}",
                lb.id, lb.name, lb.filePath);
        }

        for (LocalBuilding lb : lbs) {
            // 关键: 不要 skip 重复 id 的 LocalBuilding. ExtensionPack 里可能没有 .png 文件路径,
            // LocalBuilding 里的 c.setLocalImagePath() 才是图片能渲染出来的关键.
            // 用一个 id→ConstructionInfo 索引, 找到就回填字段, 找不到才新建.
            ConstructionInfo existing = null;
            for (ConstructionInfo c : all) {
                if (c.getId().equals(lb.id)) { existing = c; break; }
            }
            ConstructionInfo c = existing;
            if (c == null) {
                c = new ConstructionInfo(lb.id);
                all.add(c);
            }
            // 用 LocalBuilding 的字段回填: name (从 .txt 解析, 优先级高于 ExtensionPack 默认 id),
            // author/desc 同理. localImagePath 补上后 hasPreviewImage() 才会返回 true.
            c.setName(lb.name);
            c.setAuthor(lb.author);
            c.setDescription(lb.description);
            // 依赖: LocalBuilding 从 .txt "依赖" 解析出来的, 转给 ConstructionInfo 让详情页能显示出来.
            // 不能空着不传, 否则 GuiConstructionDetail.getDependencies() 返回 null, UI 显示"无".
            c.setDependencies(lb.dependencies);
            // 分类: 同样从 LocalBuilding 透传, 详情页 / 列表分类都靠这个字段.
            c.setCategory(lb.category);
            if (lb.fileExt != null && !lb.fileExt.isEmpty()) {
                c.setFormat(lb.fileExt.startsWith(".") ? lb.fileExt.substring(1) : lb.fileExt);
            }
            // localImagePath: 只在 LocalBuilding 里有的 .png 路径. 如果 ExtensionPack 也有 pngData,
            // hasPreviewImage() 仍会优先用 pngData; 这里设了 localImagePath 是为了 pngData 缺失时能 fallback.
            if (lb.imagePath != null && Files.exists(lb.imagePath)) {
                c.setLocalImagePath(lb.imagePath);
            }
            if (lb.filePath != null) {
                c.setLocalNbtPath(lb.filePath);
            }
            PrefabCustomAddon.LOGGER.info("[BUILDINGS-TAB]   合并 id='{}' (lb.dependencies={} → c.dependencies={})",
                lb.id, lb.dependencies, c.getDependencies());
        }

        // === 收藏的建筑排在最前面 ===
        // 用 (packageName, constructionId) 跟 PlayerPreferences.favoriteKeys 比对.
        // 收藏的优先排第一组, 未收藏的跟在后面; 各自内部保持原顺序.
        PlayerPreferences prefs = PlayerPreferences.get();
        java.util.List<ConstructionInfo> favorited = new java.util.ArrayList<>();
        java.util.List<ConstructionInfo> rest = new java.util.ArrayList<>();
        for (ConstructionInfo c : all) {
            String pkg = c.getPack() == null ? null : c.getPack().getPackageName();
            if (prefs.isFavorite(pkg, c.getId())) {
                favorited.add(c);
            } else {
                rest.add(c);
            }
        }
        java.util.List<ConstructionInfo> merged = new java.util.ArrayList<>(all.size());
        merged.addAll(favorited);
        merged.addAll(rest);
        PrefabCustomAddon.LOGGER.info("[BUILDINGS-TAB] 收藏优先排序: 收藏 {} 个, 非收藏 {} 个, 合并后 {} 个",
            favorited.size(), rest.size(), merged.size());
        return merged;
    }

    // ----- Tab 3: 服务器 (复用 ServerPackSyncClient, 显示 synced + unsynced) -----
    // 默认就只显示「未同步」建筑, 玩家按需点单卡片同步; 顶部"同步全部"按钮用于一次性拉完.
    // 同步流程: 点未同步卡片 → ServerPackSyncClient.requestSyncSingle(name) 拉这一个 zip.
    private int[] lastSyncServerBtn = null;  // 「同步服务器」按钮位置
    /** 当前页面: "all" / "unsynced" / "synced". 默认 "unsynced" - 一打开就只看到本地没的. */
    private String serverFilter = "unsynced";

    private void drawTabServers(GuiGraphics guiGraphics, int grayBoxX, int grayBoxY, int mouseX, int mouseY) {
        int[] r = getContentRect(grayBoxX, grayBoxY);
        int rx = r[0], ry = r[1], rw = r[2], rh = r[3];

        // 重置筛选 tab hit rect, 避免空状态分支时命中陈旧坐标
        this.serverFilterTabs = new int[3][4];

        // 0) 单人游戏守卫: 服务器 tab 仅在多人游戏时可用
        if (Minecraft.getInstance().getCurrentServer() == null) {
            // 不画状态条 / 按钮, 整块区域都给提示
            drawEmpty(guiGraphics, new int[]{rx, ry, rw, rh},
                tr("server.only_multiplayer.title"),
                tr("server.only_multiplayer.hint"));
            this.lastSyncServerBtn = null;
            return;
        }

        // 1) 顶部状态条
        com.prefab.addon.network.ServerPackSyncClient sync =
            com.prefab.addon.network.ServerPackSyncClient.getInstance();
        com.prefab.addon.network.ServerPackSyncClient.State sState = sync.getState();
        String sMsg = sync.getStatusMessage();
        String statusText;
        int statusColor;
        if (sState == com.prefab.addon.network.ServerPackSyncClient.State.DOWNLOADING
            || sState == com.prefab.addon.network.ServerPackSyncClient.State.REQUESTING
            || sState == com.prefab.addon.network.ServerPackSyncClient.State.CHECKING
            || sState == com.prefab.addon.network.ServerPackSyncClient.State.FINALIZING) {
            statusText = sMsg == null || sMsg.isEmpty() ? tr("server.status.syncing") : sMsg;
            statusColor = 0x55AAFF;
        } else if (sState == com.prefab.addon.network.ServerPackSyncClient.State.DONE) {
            statusText = sMsg == null || sMsg.isEmpty() ? tr("server.status.done") : sMsg;
            statusColor = 0x55FF55;
        } else if (sState == com.prefab.addon.network.ServerPackSyncClient.State.ERROR) {
            statusText = sMsg == null || sMsg.isEmpty() ? tr("server.status.error") : sMsg;
            statusColor = 0xFF5555;
        } else {
            statusText = tr("server.status.idle");
            statusColor = 0xAAAAAA;
        }
        guiGraphics.drawString(this.font, statusText, rx + 4, ry + 4, statusColor);

        // 2) 「同步服务器」按钮 (右上)
        int sbW = 60, sbH = 14;
        int sbX = rx + rw - sbW - 4;
        int sbY = ry + 2;
        boolean isSyncing = sync.isSyncing();
        if (isSyncing) {
            guiGraphics.fill(sbX, sbY, sbX + sbW, sbY + sbH, 0xFF555555);
            guiGraphics.drawCenteredString(this.font, tr("server.button.syncing"), sbX + sbW / 2, sbY + 3, 0xFFCCCCCC);
        } else {
            drawTextButton(guiGraphics, sbX, sbY, sbW, sbH, tr("server.button.sync"), mouseX, mouseY);
        }
        this.lastSyncServerBtn = new int[]{sbX, sbY, sbW, sbH};

        // 3) 子筛选: 全部 / 未同步 / 已同步
        int filterY = ry + 22;
        int filterH = rh - 22;
        java.util.List<ServerBuildingInfo> all = ExtensionPackManager.getInstance().getServerBuildings();
        if (all.isEmpty()) {
            drawEmpty(guiGraphics, new int[]{rx, filterY, rw, filterH},
                tr("server.empty.title"),
                tr("server.empty.hint"));
            return;
        }
        int allCount = all.size();
        int unsyncedCount = 0;
        for (ServerBuildingInfo b : all) if (!b.synced) unsyncedCount++;
        int syncedCount = allCount - unsyncedCount;

        // 三个小标签: 全部 N / 未同步 N / 已同步 N
        int tabW = (rw - 8) / 3;
        int tabH = 12;
        int tabY = filterY;
        for (int i = 0; i < 3; i++) {
            int tx = rx + 4 + i * (tabW + 4);
            String[] labels = {
                tr("server.filter.all", String.valueOf(allCount)),
                tr("server.filter.unsynced", String.valueOf(unsyncedCount)),
                tr("server.filter.synced", String.valueOf(syncedCount))
            };
            String[] modes = {"all", "unsynced", "synced"};
            boolean active = modes[i].equals(this.serverFilter);
            int bg = active ? 0xFF6677AA : 0xFF333344;
            guiGraphics.fill(tx, tabY, tx + tabW, tabY + tabH, bg);
            guiGraphics.fill(tx, tabY, tx + tabW, tabY + 1, active ? 0xFF8899CC : 0xFF555566);
            guiGraphics.drawCenteredString(this.font, labels[i], tx + tabW / 2, tabY + 2,
                active ? 0xFFFFFFFF : 0xFFAAAAAA);
            this.serverFilterTabs[i] = new int[]{tx, tabY, tabW, tabH};
        }

        // 4) 列表: 按筛选过滤
        java.util.List<ServerBuildingInfo> filtered = new java.util.ArrayList<>();
        for (ServerBuildingInfo b : all) {
            if ("unsynced".equals(this.serverFilter) && b.synced) continue;
            if ("synced".equals(this.serverFilter) && !b.synced) continue;
            filtered.add(b);
        }
        if (filtered.isEmpty()) {
            // 针对每个筛选给出更具体的提示
            String emptyTitle;
            String emptyHint;
            if ("unsynced".equals(this.serverFilter)) {
                emptyTitle = tr("server.filter.empty_synced");
                emptyHint = tr("server.filter.empty_synced_hint");
            } else if ("synced".equals(this.serverFilter)) {
                emptyTitle = tr("server.filter.empty_unsynced");
                emptyHint = tr("server.filter.empty_unsynced_hint");
            } else {
                emptyTitle = tr("msg.no_match");
                emptyHint = tr("msg.try_filter");
            }
            drawEmpty(guiGraphics, new int[]{rx, filterY + tabH + 4, rw, filterH - tabH - 4},
                emptyTitle, emptyHint);
            return;
        }
        drawServerBuildingCards(guiGraphics, rx, filterY + tabH + 4, rw, filterH - tabH - 4,
            mouseX, mouseY, filtered);
    }

    private int[][] serverFilterTabs = new int[3][4];

    /**
     * 渲染 ServerBuildingInfo 卡片列表 (1 列).
     * 每张卡片的 (x, y, w, h, idx) 存到 {@link #serverCardHitRects}, 用于点击检测.
     */
    private int[][] serverCardHitRects = new int[40][5];
    private int serverCardHitCount = 0;

    private void drawServerBuildingCards(GuiGraphics guiGraphics, int rx, int ry, int rw, int rh,
                                          int mouseX, int mouseY,
                                          java.util.List<ServerBuildingInfo> list) {
        // 重置 hit rect
        this.serverCardHitCount = 0;
        this.serverCardSyncBtnRects.clear();
        if (list.isEmpty()) {
            drawEmpty(guiGraphics, new int[]{rx, ry, rw, rh}, tr("msg.no_match"), tr("msg.try_filter"));
            return;
        }
        // 卡片: 2 列网格, 风格跟下载/网站 tab 一致: 左缩略图 + 右文字 + 底按钮
        int cardW = (rw - 8 - (SERVER_CARD_COLS - 1) * SERVER_CARD_GAP) / SERVER_CARD_COLS;
        int cardH = SERVER_CARD_H;
        int cardGap = SERVER_CARD_GAP;
        int listH = rh - 14;
        int total = list.size();
        // pageSize = (行数 * 列数) = 每页总卡片数. 必须跟 computePageSizeForCurrentTab 的 SERVERS 分支一致,
        // 否则 maxPage 算错, 点 [›] 翻不了页.
        int rows = Math.max(1, (listH + cardGap) / (cardH + cardGap));
        int pageSize = rows * SERVER_CARD_COLS;
        int pageCount = Math.max(1, (total + pageSize - 1) / pageSize);
        int page = Math.max(0, Math.min(this.scrollOffsetCards, pageCount - 1));
        int start = page * pageSize;
        int end = Math.min(start + pageSize, total);
        for (int i = start; i < end; i++) {
            int idx = i - start;
            int col = idx % SERVER_CARD_COLS;
            int row = idx / SERVER_CARD_COLS;
            int cx = rx + 4 + col * (cardW + cardGap);
            int cy = ry + 2 + row * (cardH + cardGap);
            drawServerBuildingCard(guiGraphics, cx, cy, cardW, cardH, list.get(i), mouseX, mouseY);
            // 存 hit rect
            if (this.serverCardHitCount < this.serverCardHitRects.length) {
                this.serverCardHitRects[this.serverCardHitCount++] =
                    new int[]{cx, cy, cardW, cardH, i};
            }
        }
        if (pageCount > 1) {
            drawPaginationBar(guiGraphics, rx, ry + rh - 12, rw, page, pageCount, mouseX, mouseY);
        }
    }

    /**
     * 服务器建筑卡片 - 跟下载/网站 tab 同样的 "左缩略图 + 右文字 + 底按钮" 布局.
     * 左: 36x36 缩略图 (已同步显示 PNG, 未同步显示 ↓ 占位)
     * 右: 建筑名 (大) + 状态(已同步/未同步) + meta(扩展名 · 大小)
     * 底: 整宽同步/查看按钮
     */
    private void drawServerBuildingCard(GuiGraphics guiGraphics, int cx, int cy, int cw, int ch,
                                          ServerBuildingInfo b, int mouseX, int mouseY) {
        boolean hovered = mouseX >= cx && mouseX <= cx + cw && mouseY >= cy && mouseY <= cy + ch;
        // 卡片底色
        int bg = hovered ? 0xFF2D2D2D : 0xFF1F1F1F;
        guiGraphics.fill(cx, cy, cx + cw, cy + ch, bg);
        // 边框: 已同步绿, 未同步橙
        int borderColor = b.synced ? 0xFF2E7D32 : 0xFFFF8C00;
        guiGraphics.fill(cx, cy, cx + cw, cy + 1, borderColor);
        guiGraphics.fill(cx, cy + ch - 1, cx + cw, cy + ch, 0xFF555555);
        guiGraphics.fill(cx, cy, cx + 1, cy + ch, 0xFF555555);
        guiGraphics.fill(cx + cw - 1, cy, cx + cw, cy + ch, 0xFF555555);

        // === 上半部分: 左侧缩略图 + 右侧名称/状态/meta ===
        // 缩略图: 已同步 或 manifest 自带 pngData 都能显示. 完全无图才显示占位符.
        // 自适应缩略图大小: 卡片矮一点就用小一点, 避免按钮被挤掉.
        int iconSize = (ch >= 64) ? 36 : 28;
        int iconX = cx + 4;
        int iconY = cy + 4;
        boolean canShowThumb = b.synced || (b.pngData != null && b.pngData.length > 0);
        if (canShowThumb) {
            ensureServerBuildingImageLoaded(b);
            ResourceLocation tex = this.serverImageCache.get(b.buildingId);
            if (tex != null) {
                // uploadIconTexture 输出 ≤256x256 正方形, sheetW/H = 256 才对得上 UV.
                drawIconNearest(guiGraphics, tex, iconX, iconY, iconSize, iconSize, 0, 0, 256, 256, 256, 256);
            } else {
                guiGraphics.fill(iconX, iconY, iconX + iconSize, iconY + iconSize, 0xFF1A1A1A);
                String initial = b.getDisplayName();
                if (initial.isEmpty()) initial = "?";
                else initial = initial.substring(0, 1);
                guiGraphics.drawCenteredString(this.font, initial,
                    iconX + iconSize / 2, iconY + iconSize / 2 - 4, 0xFF88CC88);
            }
        } else {
            // 完全没图 (manifest 也没带) → 显示下载占位
            guiGraphics.fill(iconX, iconY, iconX + iconSize, iconY + iconSize, 0xFF1A1A1A);
            guiGraphics.drawCenteredString(this.font, "↓",
                iconX + iconSize / 2, iconY + iconSize / 2 - 8, 0xFFFF8C00);
            String hint = tr("server.card.unsynced_hint");
            guiGraphics.drawCenteredString(this.font, hint,
                iconX + iconSize / 2, iconY + iconSize / 2 + 4, 0xFFAA8866);
        }

        // 右侧文字区
        int textX = iconX + iconSize + 5;
        int textW = cw - iconSize - 10;
        if (textW < 30) textW = 30;

        // 文字行 Y 坐标: 卡片矮 (ch=56) 时用紧凑布局, 正常时用宽松布局
        int nameY = (ch >= 64) ? cy + 5 : cy + 2;
        int statusY = (ch >= 64) ? cy + 17 : cy + 12;
        int metaY = (ch >= 64) ? cy + 29 : cy + 22;

        // 1) 名称
        String name = b.getDisplayName();
        if (this.font.width(name) > textW) {
            while (this.font.width(name + "..") > textW && name.length() > 1) {
                name = name.substring(0, name.length() - 1);
            }
            name = name + "..";
        }
        guiGraphics.drawString(this.font, name, textX, nameY, 0xFFFFFFFF);

        // 2) 状态行
        String statusLine = tr(b.synced ? "server.badge.synced" : "server.badge.unsynced");
        int statusColor = b.synced ? 0xFF55FF55 : 0xFFFFAA55;
        guiGraphics.drawString(this.font, statusLine, textX, statusY, statusColor);

        // 3) meta 行: 扩展名 · 大小
        String ext = b.getSourceExt();
        String meta = ext + " · " + formatFileSize(b.size);
        guiGraphics.drawString(this.font, meta, textX, metaY, 0xFF888888);

        // === 分隔线 (卡片下半) ===
        int sepY = (ch >= 64) ? (cy + 44) : (cy + 36);
        guiGraphics.fill(cx + 2, sepY, cx + cw - 2, sepY + 1, 0xFF444444);

        // === 底部按钮: 整宽 ===
        int btnH = (ch >= 64) ? 16 : 14;
        int btnX = cx + 5;
        int btnY = cy + ch - btnH - 3;
        int btnW = cw - 10;
        boolean btnHovered = mouseX >= btnX && mouseX <= btnX + btnW
            && mouseY >= btnY && mouseY <= btnY + btnH;
        if (b.synced) {
            int btnBg = btnHovered ? 0xFF3A6A3A : 0xCC2E7D32;
            guiGraphics.fill(btnX, btnY, btnX + btnW, btnY + btnH, btnBg);
            guiGraphics.drawCenteredString(this.font, tr("server.button.view"),
                btnX + btnW / 2, btnY + 4, 0xFFFFFFFF);
        } else {
            int btnBg = btnHovered ? 0xFFFFAA55 : 0xFFFF8C00;
            guiGraphics.fill(btnX, btnY, btnX + btnW, btnY + btnH, btnBg);
            guiGraphics.drawCenteredString(this.font, tr("server.button.sync_one"),
                btnX + btnW / 2, btnY + 4, 0xFFFFFFFF);
        }
        this.serverCardSyncBtnRects.put(b.buildingId, new int[]{btnX, btnY, btnW, btnH});
    }

    /**
     * 加载服务器建筑缩略图. 优先用 manifest 自带的 pngData (新协议),
     * 没图或未同步时才去 server-cache/ 找同前缀 .png 或 zip 内 .png.
     * <p><strong>已废弃"拓展包"概念</strong>: 同步/未同步建筑都应该能显示缩略图,
     * 区别仅是数据源 (manifest pngData vs 本地文件). 完全没图才显示占位符.</p>
     */
    private void ensureServerBuildingImageLoaded(ServerBuildingInfo b) {
        if (b == null || b.buildingId == null) return;
        final String key = b.buildingId;
        if (this.serverImageCache.containsKey(key)) return;
        if (this.serverImageLoading.contains(key)) return;
        // 1) manifest 自带 pngData → 直接走异步加载, 同步/未同步都生效
        // 2) 已同步 → 可走 server-cache/ 兜底路径
        boolean hasPngData = b.pngData != null && b.pngData.length > 0;
        if (!hasPngData && !b.synced) return;  // 啥都没有, 跳过
        this.serverImageLoading.add(key);

        CompletableFuture.runAsync(() -> {
            try {
                byte[] data = null;
                // 1) 优先: manifest 自带 pngData (新协议, 服务端发清单时一起塞过来)
                if (b.pngData != null && b.pngData.length > 0) {
                    data = b.pngData;
                } else {
                    // 2) 兜底: 从 server-cache/ 找
                    Path cacheDir = com.prefab.addon.extension.ExtensionPackManager.getInstance().getServerCacheDir();
                    if (cacheDir != null && Files.exists(cacheDir)) {
                        String srcFile = b.sourceFileName == null ? "" : b.sourceFileName.toLowerCase();
                        if (srcFile.endsWith(".zip")) {
                            // 拓展包: 读 zip 内第一个 .png
                            Path zipPath = findServerFile(cacheDir, b.packName == null ? b.buildingId : b.packName, ".zip");
                            if (zipPath == null) zipPath = findServerFile(cacheDir, b.buildingId, ".zip");
                            if (zipPath != null) {
                                try (java.util.zip.ZipInputStream zis =
                                    new java.util.zip.ZipInputStream(Files.newInputStream(zipPath))) {
                                    java.util.zip.ZipEntry ze;
                                    while ((ze = zis.getNextEntry()) != null) {
                                        if (ze.getName().toLowerCase().endsWith(".png") && !ze.isDirectory()) {
                                            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                                            byte[] buf = new byte[4096];
                                            int n;
                                            while ((n = zis.read(buf)) > 0) bos.write(buf, 0, n);
                                            data = bos.toByteArray();
                                            break;
                                        }
                                    }
                                }
                            }
                        } else {
                            // 单文件建筑: 找同名 .png
                            for (String imgExt : new String[]{".png", ".jpg", ".jpeg", ".webp"}) {
                                Path imgPath = findServerFile(cacheDir, b.buildingId, imgExt);
                                if (imgPath != null) {
                                    data = Files.readAllBytes(imgPath);
                                    break;
                                }
                            }
                        }
                    }
                }
                if (data == null || data.length == 0) {
                    Minecraft.getInstance().execute(() -> {
                        this.serverImageCache.put(key, null);
                        this.serverImageLoading.remove(key);
                    });
                    return;
                }
                final byte[] finalData = data;
                Minecraft.getInstance().execute(() -> {
                    try (java.io.InputStream is = new java.io.ByteArrayInputStream(finalData)) {
                        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(is);
                        if (img == null) {
                            this.serverImageCache.put(key, null);
                        } else {
                            DynamicTexture tex = uploadIconTexture(img);
                            if (tex == null) {
                                this.serverImageCache.put(key, null);
                            } else {
                                ResourceLocation loc = Minecraft.getInstance().getTextureManager()
                                    .register("prefab_server_" + key, tex);
                                this.serverImageCache.put(key, loc);
                            }
                        }
                    } catch (Exception e) {
                        PrefabCustomAddon.LOGGER.warn("[SERVER-IMG] {} load failed: {}", key, e.toString());
                        this.serverImageCache.put(key, null);
                    } finally {
                        this.serverImageLoading.remove(key);
                    }
                });
            } catch (Exception e) {
                PrefabCustomAddon.LOGGER.warn("[SERVER-IMG] {} read failed: {}", key, e.toString());
                Minecraft.getInstance().execute(() -> {
                    this.serverImageCache.put(key, null);
                    this.serverImageLoading.remove(key);
                });
            }
        });
    }

    private static Path findServerFile(Path dir, String baseName, String ext) {
        Path p = dir.resolve(baseName + ext);
        return Files.exists(p) ? p : null;
    }

    /**
     * 找出当前鼠标命中的 server card 索引, 没命中返回 -1.
     */
    private int serverCardHitTest(int mouseX, int mouseY) {
        for (int i = 0; i < this.serverCardHitCount; i++) {
            int[] r = this.serverCardHitRects[i];
            if (mouseX >= r[0] && mouseX <= r[0] + r[2] && mouseY >= r[1] && mouseY <= r[1] + r[3]) {
                return r[4];
            }
        }
        return -1;
    }

    // ----- Tab 4: 收藏 -----
    private void drawTabFavorites(GuiGraphics guiGraphics, int grayBoxX, int grayBoxY, int mouseX, int mouseY) {
        java.util.List<ConstructionInfo> favs = ExtensionPackManager.getInstance().getFavoriteConstructions();
        java.util.List<ConstructionInfo> filtered = filterBySearch(favs, this.searchText);
        if (favs.isEmpty()) {
            int[] r = getContentRect(grayBoxX, grayBoxY);
            drawEmpty(guiGraphics, r, tr("favorites.empty.title"),
                tr("favorites.empty.hint"));
            return;
        }
        drawConstructionCards(guiGraphics, grayBoxX, grayBoxY, mouseX, mouseY, filtered);
    }

    // ----- Tab 5: 下载 (网站建筑市场) -----
    // 数据源: /api/buildings 网站列表
    // 卡片样式: 缩略图 + 名称 + 作者 + 格式/大小 + (下载按钮 或 已下载标识)
    // 排序: 已下载优先
    // 顶部: 状态/错误/拉取中提示 + 「打开网站」按钮 + 「筛选」下拉
    // 点击下载按钮 → 流式下载到 prefab-download/

    private void drawTabDownload(GuiGraphics guiGraphics, int grayBoxX, int grayBoxY, int mouseX, int mouseY) {
        int[] r = getContentRect(grayBoxX, grayBoxY);
        int rx = r[0], ry = r[1], rw = r[2], rh = r[3];

        // 清空卡片 hit rect
        this.websiteCardHitRects.clear();

        // ============== 顶部一行: 状态文字 + 打开网站按钮 + 筛选下拉 ==============
        // 状态文字 (左) - 截断避免跟右上按钮重叠
        String statusText;
        int statusColor;
        if (this.websiteBuildingsLoading) {
            statusText = tr("website.status.loading");
            statusColor = 0x55AAFF;
        } else if (this.websiteBuildingsError != null) {
            statusText = tr("website.status.failed", truncate(this.websiteBuildingsError, 16));
            statusColor = 0xFF5555;
        } else if (this.websiteBuildings.isEmpty()) {
            statusText = tr("website.status.empty");
            statusColor = 0xAAAAAA;
        } else {
            statusText = tr("website.status.summary", String.valueOf(this.websiteBuildings.size()));
            statusColor = 0xAAAAAA;
        }
        guiGraphics.drawString(this.font, statusText, rx + 4, ry + 4, statusColor);

        // 右上: 打开网站 + 筛选下拉
        int btnH = 12;
        int webBtnW = 60;
        int webBtnX = rx + rw - webBtnW - 4;
        int webBtnY = ry + 2;
        drawTextButton(guiGraphics, webBtnX, webBtnY, webBtnW, btnH, tr("website.button.open"), mouseX, mouseY);
        this.lastOpenWebsiteButtonRect = new int[]{webBtnX, webBtnY, webBtnW, btnH};

        int filtW = 64;
        int filtX = webBtnX - filtW - 4;
        int filtY = ry + 2;
        String filtLabel = "all".equals(this.websiteFilter) ? tr("website.filter.all")
            : "downloaded".equals(this.websiteFilter) ? tr("website.filter.downloaded")
            : tr("website.filter.not_downloaded");
        drawTextButton(guiGraphics, filtX, filtY, filtW, btnH, "▼ " + filtLabel, mouseX, mouseY);
        this.websiteFilterButtonRect = new int[]{filtX, filtY, filtW, btnH};

        // ============== 过滤 + 排序 ==============
        // 1) 按筛选过滤
        java.util.List<BuildingInfo2> filtered = new java.util.ArrayList<>();
        for (BuildingInfo2 b : this.websiteBuildings) {
            if (b == null || b.id == null) continue;
            boolean downloaded = isWebsiteBuildingDownloaded(b);
            if ("downloaded".equals(this.websiteFilter) && !downloaded) continue;
            if ("not_downloaded".equals(this.websiteFilter) && downloaded) continue;
            filtered.add(b);
        }
        // 2) 已下载优先排序
        filtered.sort((a, b) -> {
            boolean da = isWebsiteBuildingDownloaded(a);
            boolean db = isWebsiteBuildingDownloaded(b);
            if (da != db) return da ? -1 : 1;
            return 0;  // 保持服务器原顺序
        });

        // 空状态
        if (filtered.isEmpty()) {
            String emptyTitle;
            String emptyHint;
            if (this.websiteBuildingsError != null) {
                emptyTitle = tr("website.empty.failed");
                emptyHint = tr("website.empty.failed_hint");
            } else if ("not_downloaded".equals(this.websiteFilter)) {
                emptyTitle = tr("website.empty.all_downloaded");
                emptyHint = tr("website.empty.all_downloaded_hint");
            } else if ("downloaded".equals(this.websiteFilter)) {
                emptyTitle = tr("website.empty.no_downloaded");
                emptyHint = tr("website.empty.no_downloaded_hint");
            } else if (this.websiteBuildingsLoading) {
                emptyTitle = tr("website.empty.loading");
                emptyHint = tr("website.empty.loading_hint");
            } else {
                emptyTitle = tr("website.empty.empty");
                emptyHint = tr("website.empty.empty_hint");
            }
            drawEmpty(guiGraphics, new int[]{rx, ry + 18, rw, rh - 18}, emptyTitle, emptyHint);
            return;
        }

        // ============== 卡片网格 ==============
        int listY = ry + 18;
        int listH = rh - 22;
        int cardW = (rw - 8 - (CARD_COLS - 1) * CARD_GAP) / CARD_COLS;
        int cardH = 70;  // 放下"缩略图+文字+底部下载按钮"三段
        int total = filtered.size();
        int pageSize = Math.max(1, (listH + CARD_GAP) / (cardH + CARD_GAP));
        int pageCount = Math.max(1, (total + pageSize - 1) / pageSize);
        int page = Math.max(0, Math.min(this.scrollOffsetCards, pageCount - 1));
        int start = page * pageSize;
        int end = Math.min(start + pageSize, total);
        int col = 0, row = 0;
        for (int i = start; i < end; i++) {
            BuildingInfo2 b = filtered.get(i);
            int cx = rx + 4 + col * (cardW + CARD_GAP);
            int cy = listY + row * (cardH + CARD_GAP);
            drawWebsiteBuildingCard(guiGraphics, cx, cy, cardW, cardH, b, mouseX, mouseY);
            // 存 hit rect (for download button detection)
            int[] rect = new int[]{cx, cy, cardW, cardH};
            this.websiteCardHitRects.put(b.id, rect);
            col++;
            if (col >= CARD_COLS) { col = 0; row++; }
        }
        // 翻页提示
        if (pageCount > 1) {
            drawPaginationBar(guiGraphics, rx, ry + rh - 12, rw, page, pageCount, mouseX, mouseY);
        }
    }

    private void drawWebsiteBuildingCard(GuiGraphics guiGraphics, int cx, int cy, int cw, int ch,
                                          BuildingInfo2 b, int mouseX, int mouseY) {
        boolean downloaded = isWebsiteBuildingDownloaded(b);
        boolean downloading = this.websiteDownloading.contains(b.id);
        boolean hovered = mouseX >= cx && mouseX <= cx + cw && mouseY >= cy && mouseY <= cy + ch;

        // 卡片底色 (已下载: 绿边; 未下载: 橙边; 下载中: 蓝边)
        int bg = hovered ? 0xFF2D2D2D : 0xFF1F1F1F;
        guiGraphics.fill(cx, cy, cx + cw, cy + ch, bg);
        int borderColor;
        if (downloading) borderColor = 0xFF55AAFF;
        else if (downloaded) borderColor = 0xFF2E7D32;
        else borderColor = 0xFFFF8C00;
        guiGraphics.fill(cx, cy, cx + cw, cy + 1, borderColor);
        guiGraphics.fill(cx, cy + ch - 1, cx + cw, cy + ch, 0xFF555555);
        guiGraphics.fill(cx, cy, cx + 1, cy + ch, 0xFF555555);
        guiGraphics.fill(cx + cw - 1, cy, cx + cw, cy + ch, 0xFF555555);

        // 上半部分: 缩略图 + 名称 + 作者 + meta
        int iconSize = 36;
        int iconX = cx + 4;
        int iconY = cy + 4;
        ResourceLocation tex = this.websiteImageCache.get(b.id);
        if (tex != null) {
            drawIconNearest(guiGraphics, tex, iconX, iconY, iconSize, iconSize, 0, 0, 48, 48, 48, 48);
        } else {
            guiGraphics.fill(iconX, iconY, iconX + iconSize, iconY + iconSize, 0xFF1A1A1A);
            String initial = b.name.isEmpty() ? "?" : b.name.substring(0, 1);
            guiGraphics.drawCenteredString(this.font, initial, iconX + iconSize / 2, iconY + iconSize / 2 - 4, 0xFF888888);
        }

        // 中间: 名称 + 作者
        int textX = iconX + iconSize + 5;
        int textW = cw - iconSize - 10;
        if (textW < 30) textW = 30;
        String name = b.name == null || b.name.isEmpty() ? b.id : b.name;
        if (this.font.width(name) > textW) {
            while (this.font.width(name + "..") > textW && name.length() > 1) {
                name = name.substring(0, name.length() - 1);
            }
            name = name + "..";
        }
        guiGraphics.drawString(this.font, name, textX, cy + 5, 0xFFFFFF);
        // 作者
        String author = "§7by " + (b.author == null || b.author.isEmpty() ? tr("website.author.unknown") : b.author);
        if (this.font.width(author) > textW) {
            while (this.font.width(author + "..") > textW && author.length() > 1) {
                author = author.substring(0, author.length() - 1);
            }
            author = author + "..";
        }
        guiGraphics.drawString(this.font, author, textX, cy + 17, 0xFFAAAAAA);
        // 格式 + 大小 + 下载次数
        String meta = "§7" + (b.fileExt == null || b.fileExt.isEmpty() ? ".nbt" : b.fileExt)
            + " · " + formatFileSize(b.fileSize)
            + (b.downloads > 0 ? " · ↓" + b.downloads : "");
        guiGraphics.drawString(this.font, meta, textX, cy + 29, 0xFF888888);

        // 分隔线 (卡片下半)
        int sepY = cy + 44;
        guiGraphics.fill(cx + 2, sepY, cx + cw - 2, sepY + 1, 0xFF444444);

        // 下半部分: 下载/已下载/下载中 按钮
        int dBtnW = cw - 10;
        int dBtnH = 16;
        int dBtnX = cx + 5;
        int dBtnY = cy + ch - dBtnH - 4;
        if (downloading) {
            guiGraphics.fill(dBtnX, dBtnY, dBtnX + dBtnW, dBtnY + dBtnH, 0xFF555577);
            double[] prog = this.websiteDownloadProgress.get(b.id);
            String label = tr("website.status.loading");
            if (prog != null) {
                int pct = (int) prog[2];
                label = tr("website.badge.downloading", String.valueOf(pct));
            }
            guiGraphics.drawCenteredString(this.font, label, dBtnX + dBtnW / 2, dBtnY + 4, 0xFFCCCCCC);
        } else if (downloaded) {
            guiGraphics.fill(dBtnX, dBtnY, dBtnX + dBtnW, dBtnY + dBtnH, 0xCC2E7D32);
            guiGraphics.drawCenteredString(this.font, tr("website.badge.downloaded"), dBtnX + dBtnW / 2, dBtnY + 4, 0xFFFFFFFF);
        } else {
            drawTextButton(guiGraphics, dBtnX, dBtnY, dBtnW, dBtnH, tr("website.button.download"), mouseX, mouseY);
        }
        // 存按钮 hit rect
        this.websiteCardDownloadBtnRects.put(b.id, new int[]{dBtnX, dBtnY, dBtnW, dBtnH});
    }

    // ----- Tab: 云端建筑 -----
    // 数据源: CloudBuildingClientCache (客户端缓存, 由服务端 onPlayerJoin 推送过来)
    //   - 卡片: 缩略图占位 (用 first char) + 建筑名 + 状态(已放出/已收回) + 收回/放出按钮
    //   - 状态: 已放出 = 绿边, 已收回 = 灰边
    //   - 收回: C2S recall → 服务端清方块 + placed=false + sync
    //   - 放出: C2S summon → 服务端在玩家头顶 1 格重建 + placed=true + sync
    //   - 单机: 服务端 = 客户端, 但网络层一样走 (玩家模型 -> 内部发包 -> 同进程回环)
    private void drawTabCloud(GuiGraphics guiGraphics, int grayBoxX, int grayBoxY, int mouseX, int mouseY) {
        int[] r = getContentRect(grayBoxX, grayBoxY);
        int rx = r[0], ry = r[1], rw = r[2], rh = r[3];

        // 清空 hit rect
        this.cloudCardHitRects.clear();
        this.cloudCardRecallBtnRects.clear();
        this.cloudCardSummonBtnRects.clear();
        this.cloudCardDeleteBtnRects.clear();
        this.cloudCardNavigateBtnRects.clear();
        // 切页时清掉调试日志集合, 避免历史 buildingId 永远不再打日志
        // (但只在第一页清一次, 用一个 dirty 标记控制)
        if (this.navigateDebugLogged.size() > 200) {
            this.navigateDebugLogged.clear();
        }

        // 1) 顶部状态行: 数量 + 同步提示
        java.util.List<CloudBuilding> list = CloudBuildingClientCache.getInstance().getAll();
        String statusText;
        int statusColor;
        if (list.isEmpty()) {
            statusText = tr("cloud.status.empty");
            statusColor = 0xAAAAAA;
        } else {
            int placed = 0, recalled = 0;
            for (CloudBuilding b : list) {
                if (b.placed) placed++; else recalled++;
            }
            statusText = tr("cloud.status.summary", String.valueOf(list.size()), String.valueOf(placed), String.valueOf(recalled));
            statusColor = 0x55AAFF;
        }
        guiGraphics.drawString(this.font, statusText, rx + 4, ry + 4, statusColor);

        if (list.isEmpty()) {
            drawEmpty(guiGraphics, new int[]{rx, ry + 18, rw, rh - 18},
                tr("cloud.empty.title"),
                tr("cloud.empty.hint"));
            return;
        }

        // 同步预解码所有有 thumbnailPng 的建筑 (避免异步 triggerLoadCloudThumb 第一次 draw 还没回来 → 显示"中"占位符)
        for (CloudBuilding b : list) {
            if (b.thumbnailPng != null && b.thumbnailPng.length > 0
                && !this.cloudThumbCache.containsKey(b.id)) {
                ensureCloudThumbLoaded(b);
            }
        }

        // 2) 卡片网格: 2 列布局 (云端 tab 单独用, 比 BUILDINGS/EXTENSION 卡片更宽更高)
        int listY = ry + 18;
        int listH = rh - 22;
        int cardW = (rw - 8 - (CLOUD_CARD_COLS - 1) * CLOUD_CARD_GAP) / CLOUD_CARD_COLS;
        int cardH = CLOUD_CARD_H;
        int total = list.size();
        // pageSize = 行数 × 列数 = 一页总卡片数. 必须跟 computePageSizeForCurrentTab 的 CLOUD 分支一致,
        // 否则 maxPage 算错, 翻页按钮按一行一翻, 一行就 2 个, 一页只能放 2 个.
        int rows = Math.max(1, (listH + CLOUD_CARD_GAP) / (cardH + CLOUD_CARD_GAP));
        int pageSize = rows * CLOUD_CARD_COLS;
        int pageCount = Math.max(1, (total + pageSize - 1) / pageSize);
        int page = Math.max(0, Math.min(this.scrollOffsetCards, pageCount - 1));
        int start = page * pageSize;
        int end = Math.min(start + pageSize, total);
        int col = 0, row = 0;
        for (int i = start; i < end; i++) {
            int cx = rx + 4 + col * (cardW + CLOUD_CARD_GAP);
            int cy = listY + row * (cardH + CLOUD_CARD_GAP);
            drawCloudBuildingCard(guiGraphics, cx, cy, cardW, cardH, list.get(i), mouseX, mouseY);
            this.cloudCardHitRects.put(list.get(i).id, new int[]{cx, cy, cardW, cardH});
            col++;
            if (col >= CLOUD_CARD_COLS) { col = 0; row++; }
        }
        if (pageCount > 1) {
            drawPaginationBar(guiGraphics, rx, ry + rh - 12, rw, page, pageCount, mouseX, mouseY);
        }
    }

    /**
     * 绘制单个云端建筑卡片 (云端 tab 专用, 2 列布局, 卡片比 BUILDINGS tab 大).
     * 布局:
     *   ┌─ 缩略图 (60x60) ─┬─ 建筑名 (1行)         ┬─ ×┐
     *   │                  │ 状态 (1行)            │  │
     *   │                  │ 元信息 (1行)          │  │
     *   │                  │ 操作提示 (1行)        │  │
     *   ├──────────────────┴──────────────────────┴──┤
     *   │ [收回]                       [放出]       │
     *   └────────────────────────────────────────────┘
     */
    private void drawCloudBuildingCard(GuiGraphics guiGraphics, int cx, int cy, int cw, int ch,
                                         CloudBuilding b, int mouseX, int mouseY) {
        boolean hovered = mouseX >= cx && mouseX <= cx + cw && mouseY >= cy && mouseY <= cy + ch;

        // 卡片底色 + 边框
        int bg = hovered ? 0xFF2D2D2D : 0xFF1F1F1F;
        guiGraphics.fill(cx, cy, cx + cw, cy + ch, bg);
        int topColor = b.placed ? 0xFF2E7D32 : 0xFF777777;
        guiGraphics.fill(cx, cy, cx + cw, cy + 1, topColor);
        guiGraphics.fill(cx, cy + ch - 1, cx + cw, cy + ch, 0xFF555555);
        guiGraphics.fill(cx, cy, cx + 1, cy + ch, 0xFF555555);
        guiGraphics.fill(cx + cw - 1, cy, cx + cw, cy + ch, 0xFF555555);

        // 缩略图 48x48 在左 (缩小一点, 给文字区留出 12px 更多空间, 避免 "2877 块 · @south" 截断成 "@..")
        int iconSize = 48;
        int iconX = cx + 4;
        int iconY = cy + 4;
        int iconBg = b.placed ? 0xFF1F3A1F : 0xFF2A2A2A;
        guiGraphics.fill(iconX, iconY, iconX + iconSize, iconY + iconSize, iconBg);
        ResourceLocation thumb = this.cloudThumbCache.get(b.id);
        if (thumb == null && b.thumbnailPng != null && b.thumbnailPng.length > 0) {
            // 嵌入图存在但还没解码, 同步解码 (避免异步丢失, 第一次切 tab 就能看到图)
            thumb = ensureCloudThumbLoaded(b);
        }
        if (thumb != null) {
            // blit: 源尺寸用原图 (64x64 之类), 目标尺寸用 iconSize
            int srcW = b.thumbWidth > 0 ? b.thumbWidth : iconSize;
            int srcH = b.thumbHeight > 0 ? b.thumbHeight : iconSize;
            guiGraphics.blit(thumb, iconX, iconY, 0, 0, iconSize, iconSize, srcW, srcH);
        } else {
            // 兜底: 异步走 LocalBuilding 找同名图
            if (b.name != null && !b.name.isEmpty()
                && !this.cloudThumbLoading.contains(b.id)) {
                this.cloudThumbLoading.add(b.id);
                triggerLoadCloudThumb(b);
            }
            // 占位符: 显示首字符
            String initial = (b.name == null || b.name.isEmpty()) ? "?" : b.name.substring(0, 1);
            guiGraphics.drawCenteredString(this.font, initial, iconX + iconSize / 2, iconY + iconSize / 2 - 4,
                b.placed ? 0xFF88CC88 : 0xFF888888);
        }

        // 文字区域 (缩略图右侧)
        // 16 = 右上角删除按钮 (10) + 内边距 (3) + 间距 (3)
        int textX = iconX + iconSize + 6;
        int textW = cw - iconSize - 12 - 14;
        if (textW < 30) textW = 30;

        // 1) 建筑名 (1 行, 不带状态)
        String baseName = b.name == null || b.name.isEmpty() ? tr("cloud.card.unnamed") : b.name;
        String nameTrunc = truncateToWidth(baseName, textW);
        guiGraphics.drawString(this.font, nameTrunc, textX, cy + 4, 0xFFFFFFFF);

        // 2) 状态 (1 行, 独立显示, 不和名字挤)
        String statusSuffix = b.placed ? tr("cloud.card.placed") : tr("cloud.card.recalled");
        int statusColor = b.placed ? 0xFF55FF55 : 0xFFAAAAAA;
        guiGraphics.drawString(this.font, statusSuffix, textX, cy + 16, statusColor);

        // 3) 元信息 (1 行, 块数/朝向/时间) — 紧凑写法, 避免被 truncateToWidth 截成 "@.."
        //   之前 "2877 块 · @south · 2分钟前" 在 71px 文字区里超出, 被截成 "2877 块 · @.."
        //   现在: 块数 + 朝向缩写(南北东西) + 相对时间缩写, 单字符分隔
        String facingShort;
        if (b.facing == null) {
            facingShort = "?";
        } else switch (b.facing.getName()) {
            case "north" -> facingShort = "北";
            case "south" -> facingShort = "南";
            case "east"  -> facingShort = "东";
            case "west"  -> facingShort = "西";
            default      -> facingShort = b.facing.getName();
        };
        String relTime = formatRelativeTime(b.timestamp);
        // "刚刚" / "2 分前" / "3 时前" / "8 天前"
        relTime = relTime.replace("分钟", "分").replace("小时", "时").replace("天", "天");
        String meta;
        if (b.placed && b.placedAt != null) {
            meta = b.blocks.size() + " · " + b.placedAt.toShortString() + " · " + relTime;
        } else {
            meta = b.blocks.size() + " · " + facingShort + " · " + relTime;
        }
        meta = truncateToWidth(meta, textW);
        guiGraphics.drawString(this.font, meta, textX, cy + 28, 0xFFAAAAAA);

        // 4) 操作提示 (1 行) — 去掉花引号, 减少字符数
        String hint = b.placed ? "点收回清理" : "点放出预览";
        hint = truncateToWidth(hint, textW);
        guiGraphics.drawString(this.font, hint, textX, cy + 40, 0xFF888888);

        // 预留 5/6 行: 占地信息 (sizeX x sizeY x sizeZ)
        String sizeInfo = b.sizeX + "×" + b.sizeY + "×" + b.sizeZ;
        sizeInfo = truncateToWidth(sizeInfo, textW);
        guiGraphics.drawString(this.font, sizeInfo, textX, cy + 52, 0xFF777777);

        // 分隔线 (缩略图 + 文字 下方, 按钮 上方)
        int sepY = cy + 70;
        guiGraphics.fill(cx + 2, sepY, cx + cw - 2, sepY + 1, 0xFF444444);

        // 底部: 收回 + 放出 两个按钮 (各占一半)
        int btnY = sepY + 4;
        int btnH = ch - (btnY - cy) - 3;
        if (btnH < 14) btnH = 14;
        int gap = 4;
        int recallW = (cw - 10 - gap) / 2;
        int recallX = cx + 5;
        int summonX = recallX + recallW + gap;
        int summonW = cw - 10 - recallW - gap;

        if (b.placed) {
            drawTextButton(guiGraphics, recallX, btnY, recallW, btnH, tr("cloud.button.recall"), mouseX, mouseY);
        } else {
            int bg2 = 0xFF333333;
            guiGraphics.fill(recallX, btnY, recallX + recallW, btnY + btnH, bg2);
            guiGraphics.drawCenteredString(this.font, tr("cloud.button.recall"), recallX + recallW / 2,
                btnY + (btnH - 8) / 2, 0xFF666666);
        }
        if (!b.placed) {
            drawTextButton(guiGraphics, summonX, btnY, summonW, btnH, tr("cloud.button.summon"), mouseX, mouseY);
        } else {
            int bg2 = 0xFF333333;
            guiGraphics.fill(summonX, btnY, summonX + summonW, btnY + btnH, bg2);
            guiGraphics.drawCenteredString(this.font, tr("cloud.button.summon"), summonX + summonW / 2,
                btnY + (btnH - 8) / 2, 0xFF666666);
        }

        this.cloudCardRecallBtnRects.put(b.id, new int[]{recallX, btnY, recallW, btnH});
        this.cloudCardSummonBtnRects.put(b.id, new int[]{summonX, btnY, summonW, btnH});

        // 右上角「导航」按钮: 把该建筑最近一次放出的位置 (placedAt) 标到 Xaero 地图上.
        // 仅在玩家装了 Xaero's World Map/Minimap 且建筑有过实际放出位置时显示.
        // 没装 Xaero: 整个按钮不画, hit-rect 也不写 → 鼠标点不到.
        // placed=true 或 false 都行: 已收回的建筑 placedAt 仍保留着, 玩家想回去也能标.
        //   只有从未放出的建筑 (placedAt = BlockPos.ZERO) 才不显示.
        boolean hasRealPlacedAt = b.placedAt != null
            && (b.placedAt.getX() != 0 || b.placedAt.getY() != 0 || b.placedAt.getZ() != 0);
        boolean xaeroAvail = XaeroWaypointBridge.isAvailable();
        // 调试日志: 进云端 tab 后第一次画这个 building 时, 打印一次状态
        if (!navigateDebugLogged.contains(b.id)) {
            navigateDebugLogged.add(b.id);
            PrefabCustomAddon.LOGGER.info("[CLOUD-NAV] building='{}' placed={} placedAt={} xaeroAvail={} → {}",
                b.name, b.placed, b.placedAt, xaeroAvail,
                (xaeroAvail && hasRealPlacedAt) ? "BUTTON_SHOWN" : "BUTTON_HIDDEN");
        }
        if (xaeroAvail && hasRealPlacedAt) {
            int navSize = 10;
            // 紧贴 × 按钮左侧 (gap=2)
            int delXForNav = cx + cw - 3 - 10;         // × 按钮左沿, delSize=10
            int navX = delXForNav - 2 - navSize;       // 导航按钮左沿: × 按钮左侧再减 2px 间隔
            int navY = cy + 3;
            boolean navHovered = mouseX >= navX && mouseX <= navX + navSize
                && mouseY >= navY && mouseY <= navY + navSize;
            // 颜色: 蓝绿 (跟 XaeroWaypointBridge.COLOR_BUILDING 一致), hover 时变亮
            int navColor = navHovered ? 0xFF88FF88 : 0xFF55AA55;
            // 画一个迷你"地图标记"图标: 圆点 + 下尖 (5 像素圆 + 1 像素尾巴)
            int cx2 = navX + navSize / 2;
            int cy2 = navY + navSize / 2;
            // 圆点 (3x3)
            guiGraphics.fill(cx2 - 1, cy2 - 2, cx2 + 2, cy2 - 1, navColor);
            guiGraphics.fill(cx2 - 2, cy2 - 1, cx2 + 3, cy2,     navColor);
            guiGraphics.fill(cx2 - 1, cy2,     cx2 + 2, cy2 + 1, navColor);
            // 尾巴 (1 像素)
            guiGraphics.fill(cx2,     cy2 + 1, cx2 + 1, cy2 + 2, navColor);
            // hover 时多画一圈高亮
            if (navHovered) {
                guiGraphics.fill(cx2 - 2, cy2 - 3, cx2 + 3, cy2 - 2, 0x55FFFFFF);
                guiGraphics.fill(cx2 - 3, cy2 - 2, cx2 + 4, cy2 - 1, 0x55FFFFFF);
                guiGraphics.fill(cx2 - 3, cy2 + 1, cx2 + 4, cy2 + 2, 0x55FFFFFF);
            }
            this.cloudCardNavigateBtnRects.put(b.id, new int[]{navX, navY, navSize, navSize});
        }

        // 右上角删除按钮: 10x10 的小 ×, hover 时变红
        int delSize = 10;
        int delX = cx + cw - delSize - 3;
        int delY = cy + 3;
        boolean delHovered = mouseX >= delX && mouseX <= delX + delSize && mouseY >= delY && mouseY <= delY + delSize;
        int delColor = delHovered ? 0xFFFF5555 : 0xFF888888;
        // 画 × (两条对角线)
        guiGraphics.fill(delX + 2, delY + 2, delX + delSize - 1, delY + 3, delColor);
        guiGraphics.fill(delX + 3, delY + 3, delX + delSize - 2, delY + 4, delColor);
        guiGraphics.fill(delX + delSize - 2, delY + 2, delX + delSize - 1, delY + 3, delColor);
        guiGraphics.fill(delX + 3, delY + delSize - 3, delX + delSize - 2, delY + delSize - 2, delColor);
        for (int i = 0; i < delSize - 4; i++) {
            guiGraphics.fill(delX + 2 + i, delY + 2 + i, delX + 3 + i, delY + 3 + i, delColor);
            guiGraphics.fill(delX + delSize - 3 - i, delY + 2 + i, delX + delSize - 2 - i, delY + 3 + i, delColor);
        }
        this.cloudCardDeleteBtnRects.put(b.id, new int[]{delX, delY, delSize, delSize});
    }

    /**
     * 同步解码 cb.thumbnailPng 并上传到 GPU 纹理, 写进 cloudThumbCache.
     * 替代异步 triggerLoadCloudThumb: 同步版本保证渲染线程能立即拿到图, 避免"中"占位符残留.
     * 失败时返回 null (调用方继续走异步兜底).
     */
    private ResourceLocation ensureCloudThumbLoaded(CloudBuilding b) {
        if (b == null || b.thumbnailPng == null || b.thumbnailPng.length == 0) return null;
        ResourceLocation existing = this.cloudThumbCache.get(b.id);
        if (existing != null) return existing;
        try {
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(b.thumbnailPng));
            if (img == null) {
                PrefabCustomAddon.LOGGER.warn("[CLOUD-THUMB] 同步解码嵌入图为 null: id={}", b.id);
                return null;
            }
            int w = img.getWidth(), h = img.getHeight();
            if (w <= 0 || h <= 0) return null;
            // 记下原图尺寸, 给 blit 用 (避免用 iconSize 60 当源尺寸导致 UV 错位)
            b.thumbWidth = w;
            b.thumbHeight = h;
            final int ww = w, hh = h;
            DynamicTexture tex = new DynamicTexture(ww, hh, false);
            tex.setFilter(false, false);
            NativeImage pixels = tex.getPixels();
            for (int y = 0; y < hh; y++) {
                for (int x = 0; x < ww; x++) {
                    int argb = img.getRGB(x, y);
                    int abgr = ((argb & 0xFF00FF00) | ((argb & 0x00FF0000) >> 16) | ((argb & 0x000000FF) << 16));
                    pixels.setPixelRGBA(x, y, abgr);
                }
            }
            tex.upload();
            ResourceLocation loc = Minecraft.getInstance().getTextureManager()
                .register("prefab_cloud_" + b.id, tex);
            this.cloudThumbCache.put(b.id, loc);
            PrefabCustomAddon.LOGGER.info("[CLOUD-THUMB] 同步解码嵌入图: id={} {}x{}", b.id, ww, hh);
            return loc;
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("[CLOUD-THUMB] 同步解码失败: id={} err={}", b.id, e.toString());
            return null;
        }
    }

    /**
     * 按像素宽度截断字符串 (用于卡片文字过长). 末尾加 "..".
     */
    private String truncateToWidth(String s, int maxWidth) {
        if (s == null) return "";
        if (this.font.width(s) <= maxWidth) return s;
        String trimmed = s;
        while (trimmed.length() > 1 && this.font.width(trimmed + "..") > maxWidth) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "..";
    }

    /** 时间戳 → 相对时间 (i18n). */
    private String formatRelativeTime(long ts) {
        if (ts <= 0) return tr("time.unknown");
        long diff = System.currentTimeMillis() - ts;
        if (diff < 0) diff = 0;
        long sec = diff / 1000;
        if (sec < 60) return tr("time.just_now");
        long min = sec / 60;
        if (min < 60) return tr("time.minutes_ago", String.valueOf(min));
        long hr = min / 60;
        if (hr < 24) return tr("time.hours_ago", String.valueOf(hr));
        long day = hr / 24;
        return tr("time.days_ago", String.valueOf(day));
    }

    // === i18n helpers ===
    // 简化的翻译调用: 把 lang key + 可变参数 → 渲染好的 String (含 § 颜色码)
    // lang 文件里直接放 §7xxx 这种带颜色码的字符串即可.
    private static String tr(String key, Object... args) {
        try {
            return Component.translatable(key, args).getString();
        } catch (Throwable t) {
            return key;  // key 缺失时降级, 至少不崩
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }

    /** 命中测试: int[]{x, y, w, h} vs (mx, my). */
    private static boolean hitTestRect(int[] r, int mx, int my) {
        if (r == null || r.length < 4) return false;
        return mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3];
    }

    private void drawTextButton(GuiGraphics guiGraphics, int x, int y, int w, int h,
                                 String text, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        int bg = hovered ? 0xFF6677AA : 0xFF445577;
        guiGraphics.fill(x, y, x + w, y + h, bg);
        guiGraphics.fill(x, y, x + w, y + 1, 0xFF8899CC);
        guiGraphics.fill(x, y + h - 1, x + w, y + h, 0xFF223344);
        guiGraphics.drawCenteredString(this.font, text, x + w / 2, y + (h - 8) / 2, 0xFFFFFF);
    }

    /**
     * 在底部画分页按钮条: [‹] [1] [2] [3] [4] [5] [›]
     * 写入 paginationPrevRect / paginationPageRects / paginationNextRect 供点击检测.
     * 调用前确保已用 drawContent 计算好 scrollOffsetCards (页索引) 和对应 maxPage.
     */
    /**
     * 简化版分页条: [‹] [_] [›] - 中间只显示当前页数 (1-based), 切页时 ±1.
     * 写入 paginationPrevRect / paginationPageRects / paginationNextRect 供点击检测.
     */
    private void drawPaginationBar(GuiGraphics guiGraphics, int rx, int bottomY, int rw,
                                    int currentPage, int totalPages, int mouseX, int mouseY) {
        // 重置
        this.paginationBarRect = null;
        this.paginationPrevRect = null;
        this.paginationNextRect = null;
        this.paginationPageRects = null;
        if (totalPages <= 1) return;

        int barH = 12;
        int y = bottomY;
        this.paginationBarRect = new int[]{rx, y, rw, barH};

        int prevW = 16;
        int nextW = 16;
        int gap = 4;
        int numW = 28;  // 中间页数框稍宽一点, 让"1"或"1/5"读起来清楚
        int totalUsed = prevW + gap + numW + gap + nextW;
        int startX = rx + (rw - totalUsed) / 2;

        // [‹] 按钮
        int prevX = startX;
        int cy = y;
        this.paginationPrevRect = new int[]{prevX, cy, prevW, barH};
        boolean prevActive = currentPage > 0;
        drawPageBtn(guiGraphics, this.paginationPrevRect, "‹", prevActive, prevActive && isHovered(this.paginationPrevRect, mouseX, mouseY));

        // [_] 中间页数按钮 (只显示当前页码, e.g. "1"; 玩家点 [‹]/[›] 时翻页)
        int numX = prevX + prevW + gap;
        int[] numR = new int[]{numX, cy, numW, barH};
        // 中心按钮只是显示用, 不作为分页跳转目标 (玩家用 [‹]/[›] 翻页)
        // 不放进 paginationPageRects, 避免 handlePaginationClick 误把 numR[4] 当页码访问越界
        this.paginationPageRects = null;
        int bg = 0xFF445577;
        boolean hovered = isHovered(numR, mouseX, mouseY);
        if (hovered) bg = 0xFF556699;
        guiGraphics.fill(numR[0], numR[1], numR[0] + numR[2], numR[1] + numR[3], bg);
        // 显示 "当前页 / 总页数", 避免玩家在多页时不知道总页数
        String label = (currentPage + 1) + "/" + totalPages;
        guiGraphics.drawCenteredString(this.font, label,
            numR[0] + numR[2] / 2, numR[1] + (numR[3] - 8) / 2, 0xFFFFFFFF);

        // [›] 按钮
        int nextX = numX + numW + gap;
        this.paginationNextRect = new int[]{nextX, cy, nextW, barH};
        boolean nextActive = currentPage < totalPages - 1;
        drawPageBtn(guiGraphics, this.paginationNextRect, "›", nextActive, nextActive && isHovered(this.paginationNextRect, mouseX, mouseY));
    }

    private void drawPageBtn(GuiGraphics guiGraphics, int[] r, String text, boolean active, boolean hovered) {
        int bg;
        if (!active) bg = 0xFF2A2A2A;
        else if (hovered) bg = 0xFF6677AA;
        else bg = 0xFF445577;
        guiGraphics.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], bg);
        int textColor = active ? 0xFFFFFF : 0xFF666666;
        guiGraphics.drawCenteredString(this.font, text, r[0] + r[2] / 2, r[1] + (r[3] - 8) / 2, textColor);
    }

    private static boolean isHovered(int[] r, int mx, int my) {
        return r != null && mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3];
    }

    /**
     * 鼠标点击分页条, 如果命中就设置 scrollOffsetCards 并返回 true.
     */
    private boolean handlePaginationClick(int mouseX, int mouseY) {
        if (isHovered(this.paginationPrevRect, mouseX, mouseY)) {
            if (this.scrollOffsetCards > 0) {
                this.scrollOffsetCards--;
                return true;
            }
        }
        if (isHovered(this.paginationNextRect, mouseX, mouseY)) {
            int maxPage = computeMaxPageForCurrentTab();
            if (this.scrollOffsetCards < maxPage) {
                this.scrollOffsetCards++;
                return true;
            }
        }
        if (this.paginationPageRects != null) {
            for (int[] r : this.paginationPageRects) {
                if (isHovered(r, mouseX, mouseY)) {
                    this.scrollOffsetCards = r[4];
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 分类列表点击处理. 仅在 BUILDINGS tab 调用. 命中:
     *   - [◀] / [▶] 收起/展开按钮 → 切换 categoryPanelHidden
     *   - 单个分类项 → 设 currentCategory (再次点同一项 = 取消, 走回 "全部")
     *   - [+] 管理按钮 → 弹 GuiCategoryManager
     * 没命中返回 false.
     */
    private boolean handleCategoryListClick(int mouseX, int mouseY) {
        // 1) 收起/展开按钮 (最优先, 任何状态下都有)
        if (isHovered(this.categoryToggleBtnRect, mouseX, mouseY)) {
            this.categoryPanelHidden = !this.categoryPanelHidden;
            this.scrollOffsetCards = 0;  // 收起后宽了, 翻页重置
            return true;
        }
        // 收起状态: 面板其它部分不接收点击
        if (this.categoryPanelHidden) {
            return false;
        }
        // 2) 单个分类项
        for (int idx = 0; idx < this.categoryItemRects.size(); idx++) {
            int[] r = this.categoryItemRects.get(idx);
            if (isHovered(r, mouseX, mouseY)) {
                String newCat;
                if (idx == 0) {
                    newCat = null;  // "全部"
                } else if (idx == 1) {
                    newCat = CategoryManager.UNCATEGORIZED;
                } else {
                    // idx 2..N: 对应 CategoryManager.getCategories() 第 1..(N-1) 项
                    java.util.List<String> all = CategoryManager.get().getCategories();
                    int realIdx = idx - 1;  // 跳过 UNCATEGORIZED
                    if (realIdx >= 0 && realIdx < all.size()) {
                        newCat = all.get(realIdx);
                    } else {
                        newCat = null;
                    }
                }
                // 再次点同一项 = 取消, 走 "全部"
                if (java.util.Objects.equals(newCat, this.currentCategory)) {
                    this.currentCategory = null;
                } else {
                    this.currentCategory = newCat;
                }
                this.scrollOffsetCards = 0;  // 切分类时重置翻页
                return true;
            }
        }
        // 3) [+] 管理按钮
        if (isHovered(this.categoryAddBtnRect, mouseX, mouseY)) {
            // 弹独立 LDLib2 屏, 关闭后回到本屏 (玩家分类列表会自动刷新)
            GuiCategoryManager.openStandalone();
            return true;
        }
        return false;
    }

    /** 打开 .minecraft/prefab-download/ 文件夹. 不存在时自动创建. */
    private void openDownloadFolder() {
        Path dir = LocalBuildingScanner.getDownloadRoot();
        try {
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            FolderOpener.openInOS(dir);
            setStatus("已打开: " + dir, 0x55FF55);
        } catch (Exception e) {
            setStatus("打开失败: " + e.getMessage(), 0xFF5555);
        }
    }

    /** 绘制一张已下载建筑卡片. LocalBuilding 版本: 不再有"下载"按钮, 改为"已下载"标识. */
    private void drawDownloadedCard(GuiGraphics guiGraphics, int cx, int cy, int cw, int ch,
                                     LocalBuilding lb, int mouseX, int mouseY) {
        // 卡片底色
        boolean hovered = mouseX >= cx && mouseX <= cx + cw && mouseY >= cy && mouseY <= cy + ch;
        int bg = hovered ? 0xFF2D2D2D : 0xFF1F1F1F;
        guiGraphics.fill(cx, cy, cx + cw, cy + ch, bg);
        guiGraphics.fill(cx, cy, cx + cw, cy + 1, 0xFF555555);
        guiGraphics.fill(cx, cy + ch - 1, cx + cw, cy + ch, 0xFF555555);
        guiGraphics.fill(cx, cy, cx + 1, cy + ch, 0xFF555555);
        guiGraphics.fill(cx + cw - 1, cy, cx + cw, cy + ch, 0xFF555555);

        // 左侧: 缩略图 (40x40)
        int iconSize = 40;
        int iconX = cx + 4;
        int iconY = cy + (ch - iconSize) / 2;
        ResourceLocation tex = this.localImageCache.get(lb.id);
        if (tex != null) {
            drawIconNearest(guiGraphics, tex, iconX, iconY, iconSize, iconSize, 0, 0, 256, 256, 256, 256);
        } else {
            guiGraphics.fill(iconX, iconY, iconX + iconSize, iconY + iconSize, 0xFF1A1A1A);
            String initial = lb.name.isEmpty() ? "?" : lb.name.substring(0, 1);
            guiGraphics.drawCenteredString(this.font, initial, iconX + iconSize / 2, iconY + iconSize / 2 - 4, 0xFF888888);
        }

        // 中间: 名称 (居中偏上)
        int textX = iconX + iconSize + 6;
        int textW = cw - iconSize - 12;
        String name = lb.getDisplayName();
        if (this.font.width(name) > textW) {
            while (this.font.width(name + "..") > textW && name.length() > 1) {
                name = name.substring(0, name.length() - 1);
            }
            name = name + "..";
        }
        guiGraphics.drawString(this.font, name, textX, cy + 8, 0xFFFFFF);
        // 作者 + 格式 + 大小
        String author = "§7by " + (lb.author.isEmpty() ? "未知" : lb.author);
        guiGraphics.drawString(this.font, author, textX, cy + 20, 0xFFAAAAAA);
        // 文件大小
        String meta = "§7" + (lb.fileExt.isEmpty() ? ".nbt" : lb.fileExt) + " · " + formatFileSize(lb.fileSize);
        guiGraphics.drawString(this.font, meta, textX, cy + 32, 0xFF888888);

        // 右下角小角标: 来源
        String tag = "extension".equals(lb.source) ? "本地" : "下载";
        int tagW = this.font.width(tag);
        guiGraphics.drawString(this.font, "§7" + tag, cx + cw - tagW - 4, cy + ch - 10, 0xFF888888);
    }

    private static String formatFileSize(long bytes) {
        if (bytes <= 0) return "-";
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return String.format("%.1fMB", bytes / 1024.0 / 1024.0);
    }

    // ----- 包内建筑 (drilldown) -----
    private void drawPackDrilldown(GuiGraphics guiGraphics, int grayBoxX, int grayBoxY, int mouseX, int mouseY) {
        // 顶部加一行包信息
        ExtensionPack p = this.currentDrilldownPack;
        int[] r = getContentRect(grayBoxX, grayBoxY);
        int infoY = r[1] - 4;
        String info = String.format("§7作者: §f%s §7| §7建筑: §f%d §7| §7版本: §f%s",
            p.getAuthor() == null || p.getAuthor().isEmpty() ? "未知" : p.getAuthor(),
            p.getConstructions().size(),
            p.getVersion() == null || p.getVersion().isEmpty() ? "未指定" : p.getVersion());
        guiGraphics.drawString(this.font, info, r[0] + 4, infoY, 0xFFFFFF);

        java.util.List<ConstructionInfo> filtered = filterBySearch(p.getConstructions(), this.searchText);
        drawConstructionCards(guiGraphics, grayBoxX, grayBoxY, mouseX, mouseY, filtered);
    }

    // ============================================================
    // 卡片渲染: 拓展包
    // ============================================================

    private void drawPackCards(GuiGraphics guiGraphics, int grayBoxX, int grayBoxY, int mouseX, int mouseY,
                                java.util.List<ExtensionPack> packs, boolean isServerTab) {
        int[] rect = getContentRect(grayBoxX, grayBoxY);
        int rx = rect[0], ry = rect[1], rw = rect[2], rh = rect[3];

        if (packs.isEmpty()) {
            String hint = isServerTab
                ? "(服务器还没有同步任何包)"
                : "(prefab-extension 文件夹里没找到标准格式拓展包)";
            drawEmpty(guiGraphics, rect, "未发现", hint);
            return;
        }

        int pageSize = CARD_COLS * CARD_ROWS;
        int pageCount = Math.max(1, (packs.size() + pageSize - 1) / pageSize);
        int page = Math.max(0, Math.min(this.scrollOffsetCards, pageCount - 1));
        int start = page * pageSize;
        int end = Math.min(start + pageSize, packs.size());

        // 居中放卡片网格
        int gridW = CARD_COLS * CARD_W + (CARD_COLS - 1) * CARD_GAP;
        int gridX = rx + (rw - gridW) / 2;
        int gridY = ry + 4;

        for (int i = 0; i < (end - start); i++) {
            int row = i / CARD_COLS;
            int col = i % CARD_COLS;
            int cx = gridX + col * (CARD_W + CARD_GAP);
            int cy = gridY + row * (CARD_H + CARD_GAP);
            ExtensionPack p = packs.get(start + i);
            drawPackCard(guiGraphics, cx, cy, p, mouseX, mouseY);
        }

        // 翻页提示
        if (pageCount > 1) {
            drawPaginationBar(guiGraphics, rx, ry + rh - 12, rw, page, pageCount, mouseX, mouseY);
        }
    }

    private void drawPackCard(GuiGraphics guiGraphics, int cx, int cy, ExtensionPack p, int mouseX, int mouseY) {
        boolean hovered = mouseX >= cx && mouseX <= cx + CARD_W
            && mouseY >= cy && mouseY <= cy + CARD_H;
        int bg = hovered ? 0xFF3A3A3A : 0xFF1F1F1F;
        int border = hovered ? 0xFF55AAFF : 0xFF555555;
        // 卡片底色
        guiGraphics.fill(cx, cy, cx + CARD_W, cy + CARD_H, bg);
        // 边框
        guiGraphics.fill(cx, cy, cx + CARD_W, cy + 1, border);
        guiGraphics.fill(cx, cy + CARD_H - 1, cx + CARD_W, cy + CARD_H, border);
        guiGraphics.fill(cx, cy, cx + 1, cy + CARD_H, border);
        guiGraphics.fill(cx + CARD_W - 1, cy, cx + CARD_W, cy + CARD_H, border);

        // 封面 (60x60, 居中)
        int iconSize = 60;
        int iconX = cx + (CARD_W - iconSize) / 2;
        int iconY = cy + 4;
        if (p.hasCoverImage() && p.getCoverImageData() != null) {
            ResourceLocation tex = ensureCoverTextureLoaded(p);
            if (tex != null) {
                drawIconNearest(guiGraphics, tex, iconX, iconY, iconSize, iconSize, 0, 0, 48, 48, 48, 48);
            } else {
                guiGraphics.fill(iconX, iconY, iconX + iconSize, iconY + iconSize, 0xFF1A1A1A);
            }
        } else {
            // 占位: 用包名第一个字符
            guiGraphics.fill(iconX, iconY, iconX + iconSize, iconY + iconSize, 0xFF1A1A1A);
            String ch = p.getName().isEmpty() ? "?" : p.getName().substring(0, 1);
            guiGraphics.drawCenteredString(this.font, ch, iconX + iconSize / 2, iconY + iconSize / 2 - 4, 0xFF888888);
        }

        // 包名 (最多 2 行, 12 字截断)
        String name = p.getName();
        if (name.length() > 12) name = name.substring(0, 10) + "..";
        guiGraphics.drawCenteredString(this.font, name, cx + CARD_W / 2, cy + iconSize + 6, 0xFFFFFF);

        // 副标题: 建筑数 + 来源标签
        String sub = p.getConstructions().size() + " 建筑";
        if (p.isServerBacked()) sub += " §b[服]";
        guiGraphics.drawCenteredString(this.font, sub, cx + CARD_W / 2, cy + CARD_H - 10, 0xFFAAAAAA);
    }

    // ============================================================
    // 卡片渲染: 建筑
    // ============================================================

    private void drawConstructionCards(GuiGraphics guiGraphics, int grayBoxX, int grayBoxY, int mouseX, int mouseY,
                                       java.util.List<ConstructionInfo> list) {
        int[] rect = getContentRect(grayBoxX, grayBoxY);
        int rx = rect[0], ry = rect[1], rw = rect[2], rh = rect[3];

        if (list.isEmpty()) {
            drawEmpty(guiGraphics, rect, "无匹配建筑", "(试试清空搜索词)");
            return;
        }

        int pageSize = CARD_COLS * CARD_ROWS;
        int pageCount = Math.max(1, (list.size() + pageSize - 1) / pageSize);
        int page = Math.max(0, Math.min(this.scrollOffsetCards, pageCount - 1));
        int start = page * pageSize;
        int end = Math.min(start + pageSize, list.size());

        int gridW = CARD_COLS * CARD_W + (CARD_COLS - 1) * CARD_GAP;
        int gridX = rx + (rw - gridW) / 2;
        int gridY = ry + 4;

        for (int i = 0; i < (end - start); i++) {
            int row = i / CARD_COLS;
            int col = i % CARD_COLS;
            int cx = gridX + col * (CARD_W + CARD_GAP);
            int cy = gridY + row * (CARD_H + CARD_GAP);
            ConstructionInfo c = list.get(start + i);
            drawConstructionCard(guiGraphics, cx, cy, c, mouseX, mouseY);
        }

        if (pageCount > 1) {
            drawPaginationBar(guiGraphics, rx, ry + rh - 12, rw, page, pageCount, mouseX, mouseY);
        }
    }

    private void drawConstructionCard(GuiGraphics guiGraphics, int cx, int cy, ConstructionInfo c, int mouseX, int mouseY) {
        boolean hovered = mouseX >= cx && mouseX <= cx + CARD_W
            && mouseY >= cy && mouseY <= cy + CARD_H;
        int bg = hovered ? 0xFF3A3A3A : 0xFF1F1F1F;
        int border = hovered ? 0xFF55AAFF : 0xFF555555;
        guiGraphics.fill(cx, cy, cx + CARD_W, cy + CARD_H, bg);
        guiGraphics.fill(cx, cy, cx + CARD_W, cy + 1, border);
        guiGraphics.fill(cx, cy + CARD_H - 1, cx + CARD_W, cy + CARD_H, border);
        guiGraphics.fill(cx, cy, cx + 1, cy + CARD_H, border);
        guiGraphics.fill(cx + CARD_W - 1, cy, cx + CARD_W, cy + CARD_H, border);

        // 预览图: 铺满卡片宽度 (iconW = CARD_W - 2), 高度铺到 iconH = 64,
        //   留 12px 底部空间给建筑名. 纹理是 256x256 正方形 (uploadIconTexture 已中心裁剪 + 缩放),
        //   这里直接拉伸到卡片宽, 避免之前 60x60 居中露出的 16px 左右 + 4px 上下 黑边.
        int iconW = CARD_W - 2;
        int iconH = CARD_H - 16;  // 64, 留 14 给建筑名
        int iconX = cx + 1;
        int iconY = cy + 1;
        boolean iconDrawn = false;
        if (c.hasPreviewImage()) {
            // 1) 建筑自带 PNG 图标 (拓展包/独立 .nbt 旁边的 .png) - 优先用
            ResourceLocation tex = ensurePreviewTextureLoaded(c);
            if (tex != null) {
                drawIconNearest(guiGraphics, tex, iconX, iconY, iconW, iconH, 0, 0, 256, 256, 256, 256);
                iconDrawn = true;
            } else {
                guiGraphics.fill(iconX, iconY, iconX + iconW, iconY + iconH, 0xFF1A1A1A);
            }
        }
        if (!iconDrawn && com.prefab.addon.client.ThumbnailCache.hasCached(c)) {
            // 2) 缩略图缓存 (从 3D 详情预览自动截屏生成的 PNG) - 没有图标时用
            byte[] pngData = com.prefab.addon.client.ThumbnailCache.read(c);
            if (pngData != null) {
                ResourceLocation tex = ensureCachedThumbnailTextureLoaded(c, pngData);
                if (tex != null) {
                    drawIconNearest(guiGraphics, tex, iconX, iconY, iconW, iconH, 0, 0, 256, 256, 256, 256);
                    iconDrawn = true;
                } else {
                    guiGraphics.fill(iconX, iconY, iconX + iconW, iconY + iconH, 0xFF1A1A1A);
                }
            } else {
                guiGraphics.fill(iconX, iconY, iconX + iconW, iconY + iconH, 0xFF1A1A1A);
            }
        }
        if (!iconDrawn) {
            // 3) 无图标无缓存 - 显示占位 (打开 detail 后会自动生成缓存)
            guiGraphics.fill(iconX, iconY, iconX + iconW, iconY + iconH, 0xFF1A1A1A);
            String ch = c.getName().isEmpty() ? "?" : c.getName().substring(0, 1);
            guiGraphics.drawCenteredString(this.font, ch, iconX + iconW / 2, iconY + iconH / 2 - 4, 0xFF888888);
        }

        // 建筑名 (底部 12px). 已收藏时在前面加一个星星图标
        String packKey = c.getPack() == null
            ? ExtensionPackManager.STANDALONE_PACKAGE
            : c.getPack().getPackageName();
        boolean isFav = PlayerPreferences.get().isFavorite(packKey, c.getId());
        String star = isFav ? "★ " : "";
        int nameMaxLen = isFav ? 8 : 10;  // 留位置给星星
        String name = c.getName();
        if (name.length() > nameMaxLen) name = name.substring(0, nameMaxLen - 2) + "..";
        String displayName = star + name;
        int nameColor = isFav ? 0xFFFFDD66 : 0xFFFFFF;
        guiGraphics.drawCenteredString(this.font, displayName, cx + CARD_W / 2, iconY + iconH + 2, nameColor);
    }

    // ============================================================
    // 命中测试: 卡片点击
    // ============================================================

    /** 返回 (page 起点) 在卡片列表中的索引; 找不到返回 -1. */
    private int cardHitTest(int mouseX, int mouseY, int totalCount) {
        return cardHitTest(mouseX, mouseY, totalCount, CARD_COLS, getContentRect(
            computePanelPos()[0], computePanelPos()[1]));
    }

    /** 建筑 tab 用: 2 列 + 减掉分类列表宽度的卡片区. */
    private int cardHitTestBuildings(int mouseX, int mouseY, int totalCount) {
        int[] pos = computePanelPos();
        return cardHitTest(mouseX, mouseY, totalCount, BUILDINGS_CARD_COLS,
            getBuildingsCardRect(pos[0], pos[1]));
    }

    private int cardHitTest(int mouseX, int mouseY, int totalCount, int cols, int[] rect) {
        int rx = rect[0], ry = rect[1], rw = rect[2], rh = rect[3];

        if (mouseX < rx || mouseX > rx + rw || mouseY < ry || mouseY > ry + rh) return -1;

        int pageSize = cols * CARD_ROWS;
        int pageCount = Math.max(1, (totalCount + pageSize - 1) / pageSize);
        int page = Math.max(0, Math.min(this.scrollOffsetCards, pageCount - 1));

        int gridW = cols * CARD_W + (cols - 1) * CARD_GAP;
        int gridX = rx + (rw - gridW) / 2;
        int gridY = ry + 4;

        for (int i = 0; i < pageSize; i++) {
            int row = i / cols;
            int col = i % cols;
            int cx = gridX + col * (CARD_W + CARD_GAP);
            int cy = gridY + row * (CARD_H + CARD_GAP);
            if (mouseX >= cx && mouseX <= cx + CARD_W && mouseY >= cy && mouseY <= cy + CARD_H) {
                int idx = page * pageSize + i;
                return idx < totalCount ? idx : -1;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int mx = (int) mouseX, my = (int) mouseY;

        // 0) 分页条点击 (各 tab 通用)
        if (handlePaginationClick(mx, my)) {
            return true;
        }

        // 1) Tab 点击
        Tab hit = tabHitTest(mx, my);
        if (hit != null) {
            switchTab(hit);
            return true;
        }

        // 2) 卡片点击
        if (this.currentDrilldownPack != null) {
            java.util.List<ConstructionInfo> list = filterBySearch(
                this.currentDrilldownPack.getConstructions(), this.searchText);
            int idx = cardHitTest(mx, my, list.size());
            if (idx >= 0) {
                openConstructionDetail(list.get(idx));
                return true;
            }
        } else {
            switch (this.currentTab) {
                case BUILDINGS: {
                    // 2a) 分类列表点击 (在卡片前判断 — 如果点中了分类, 就别再走卡片)
                    if (handleCategoryListClick(mx, my)) {
                        return true;
                    }
                    // 2b) 卡片: 应用分类 + 搜索过滤, 2 列布局
                    java.util.List<ConstructionInfo> all = getMergedConstructionsForBuildingsTab();
                    java.util.List<ConstructionInfo> byCat = filterByCategory(all, this.currentCategory);
                    java.util.List<ConstructionInfo> list = filterBySearch(byCat, this.searchText);
                    int idx = cardHitTestBuildings(mx, my, list.size());
                    if (idx >= 0) {
                        openConstructionDetail(list.get(idx));
                        return true;
                    }
                    break;
                }
                case CLOUD: {
                    // 1) 「收回」按钮: 命中 → C2S recall
                    for (java.util.Map.Entry<String, int[]> e : this.cloudCardRecallBtnRects.entrySet()) {
                        int[] r = e.getValue();
                        if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3]) {
                            String id = e.getKey();
                            CloudBuilding b = CloudBuildingClientCache.getInstance().getById(id);
                            if (b == null) {
                                setStatus("✗ 找不到该云端建筑", 0xFF5555);
                                return true;
                            }
                            if (!b.placed) {
                                setStatus("该建筑已经是收回状态", 0xAAAAAA);
                                return true;
                            }
                            CloudBuildingClientCache.getInstance().requestRecall(id);
                            setStatus("↩ 收回中: " + b.name, 0x55AAFF);
                            return true;
                        }
                    }
                    // 1.5) 「删除」按钮: 命中 → 弹出确认弹窗
                    for (java.util.Map.Entry<String, int[]> e : this.cloudCardDeleteBtnRects.entrySet()) {
                        int[] r = e.getValue();
                        if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3]) {
                            String id = e.getKey();
                            CloudBuilding b = CloudBuildingClientCache.getInstance().getById(id);
                            if (b == null) {
                                setStatus("✗ 找不到该云端建筑", 0xFF5555);
                                return true;
                            }
                            if (b.placed) {
                                setStatus("建筑已放出, 请先收回再删除", 0xFFAA55);
                                return true;
                            }
                            // 弹出确认弹窗
                            this.pendingDeleteBuildingId = id;
                            return true;
                        }
                    }
                    // 1.6) 确认弹窗的 [取消] / [确定] 按钮
                    if (this.pendingDeleteBuildingId != null) {
                        if (this.deleteConfirmCancelRect != null
                            && hitTestRect(this.deleteConfirmCancelRect, mx, my)) {
                            this.pendingDeleteBuildingId = null;
                            this.deleteConfirmCancelRect = null;
                            this.deleteConfirmOkRect = null;
                            setStatus("已取消删除", 0xAAAAAA);
                            return true;
                        }
                        if (this.deleteConfirmOkRect != null
                            && hitTestRect(this.deleteConfirmOkRect, mx, my)) {
                            String id = this.pendingDeleteBuildingId;
                            CloudBuilding b = CloudBuildingClientCache.getInstance().getById(id);
                            String name = b != null ? b.name : "?";
                            this.pendingDeleteBuildingId = null;
                            this.deleteConfirmCancelRect = null;
                            this.deleteConfirmOkRect = null;
                            CloudBuildingClientCache.getInstance().requestDelete(id);
                            setStatus("✗ 删除中: " + name, 0xFFAA55);
                            return true;
                        }
                        // 弹窗期间吞掉其他点击, 防止误操作其他卡片
                        return true;
                    }
                    // 1.7) 「导航」按钮: 命中 → 在 Xaero 地图上标一个航点
                    //     守门条件 (绘制端已经过): Xaero 已装 + placedAt 非 ZERO.
                    //     已收回但放过出的建筑也能用, 标的是最近一次位置.
                    for (java.util.Map.Entry<String, int[]> e : this.cloudCardNavigateBtnRects.entrySet()) {
                        int[] r = e.getValue();
                        if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3]) {
                            String id = e.getKey();
                            CloudBuilding b = CloudBuildingClientCache.getInstance().getById(id);
                            if (b == null || b.placedAt == null
                                || (b.placedAt.getX() == 0 && b.placedAt.getY() == 0 && b.placedAt.getZ() == 0)) {
                                // 理论上不会到这里, 防御一下
                                setStatus("✗ 该建筑暂无世界位置", 0xFF5555);
                                return true;
                            }
                            // 1) 解析维度 (从 b.dimensionId 字符串 → ResourceKey; 失败 fallback 到当前客户端所在维度)
                            ResourceKey<Level> dim;
                            try {
                                if (b.dimensionId != null && !b.dimensionId.isEmpty()) {
                                    ResourceLocation dimLoc = ResourceLocation.parse(b.dimensionId);
                                    dim = ResourceKey.create(Registries.DIMENSION, dimLoc);
                                } else {
                                    dim = Minecraft.getInstance().level != null
                                        ? Minecraft.getInstance().level.dimension()
                                        : ResourceKey.create(Registries.DIMENSION,
                                            ResourceLocation.withDefaultNamespace("overworld"));
                                }
                            } catch (Throwable t) {
                                dim = ResourceKey.create(Registries.DIMENSION,
                                    ResourceLocation.withDefaultNamespace("overworld"));
                            }
                            // 2) Xaero 航点 (走反射, 没装 Xaero 时 isAvailable()=false, 静默失败)
                            //    命名: "→ <建筑名> [已收回]" 让玩家一眼看出是历史位置
                            //    切换逻辑: 已有 → 取消, 没有 → 添加. 跨状态名也要兜底
                            //    (建筑被召回/放出后, 旧航点还在, 同名 remove 不到, 试另一种).
                            //    用 hasWaypoint() 区分"没找到"和"找到了但 remove 失败", 避免误添加重复航点.
                            String wpName = b.placed
                                ? "→ " + b.name
                                : "→ " + b.name + " (已收回)";
                            String otherWpName = b.placed
                                ? "→ " + b.name + " (已收回)"
                                : "→ " + b.name;
                            boolean exists = XaeroWaypointBridge.hasWaypoint(wpName);
                            if (!exists) {
                                exists = XaeroWaypointBridge.hasWaypoint(otherWpName);
                            }
                            if (exists) {
                                // 找到了 → 删. 优先删当前状态同名, 找不到再删跨状态那个
                                boolean removed = XaeroWaypointBridge.removeWaypoint(wpName);
                                if (!removed) removed = XaeroWaypointBridge.removeWaypoint(otherWpName);
                                if (removed) {
                                    setStatus("✗ 已取消航点: " + b.name, 0xFFFF55);
                                } else {
                                    // 罕见: 找到了但 remove API 不可用. 不能继续 add, 否则变成两条.
                                    setStatus("✗ 找到航点但无法删除 (Xaero 26.4.2 remove API 不兼容?)", 0xFF5555);
                                }
                            } else {
                                boolean ok = XaeroWaypointBridge.addWaypoint(
                                    dim, b.placedAt, wpName, XaeroWaypointBridge.COLOR_BUILDING);
                                if (ok) {
                                    setStatus("📍 已标航点: " + b.name + " @ "
                                        + b.placedAt.toShortString()
                                        + (b.placed ? "" : " §7(已收回, 历史位置)"), 0x55FF55);
                                } else {
                                    String reason = com.prefab.addon.integration.xaero.XaeroWaypointBridge.consumeLastAddError();
                                    if (reason == null || reason.isEmpty()) reason = "Xaero 不可用";
                                    setStatus("✗ 标航点失败: " + reason, 0xFF5555);
                                }
                            }
                            return true;
                        }
                    }
                    // 2) 「放出」按钮: 命中 → 开启世界预览 (走 CloudPreview.start, 不直接发包)
                    //    旧实现: CloudBuildingClientCache.requestSummon(id) → 服务端立即在玩家头顶 1 格
                    //    重建方块, 玩家无法选位置. 现在: 走世界内预览, 玩家用方向键/CTRL 选位置,
                    //    按 ALT 真正建造, 按右键取消. 跟 prefab 原版建筑预览体验一致.
                    for (java.util.Map.Entry<String, int[]> e : this.cloudCardSummonBtnRects.entrySet()) {
                        int[] r = e.getValue();
                        if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3]) {
                            String id = e.getKey();
                            CloudBuilding b = CloudBuildingClientCache.getInstance().getById(id);
                            if (b == null) {
                                setStatus("✗ 找不到该云端建筑", 0xFF5555);
                                return true;
                            }
                            if (b.placed) {
                                setStatus("已放出 @ " + (b.placedAt == null ? "?" : b.placedAt.toShortString())
                                    + ", 先收回再放出", 0xAAAAAA);
                                return true;
                            }
                            // 开启云端建筑世界预览 (玩家用方向键/CTRL 选位置, ALT 建造, 右键取消)
                            boolean ok = com.prefab.addon.cloud.CloudPreview.start(id);
                            if (ok) {
                                setStatus("☁ 云端预览: " + b.name + " (方向键移动, CTRL 旋转, ALT 放出, 右键取消)", 0x55FF55);
                            } else {
                                setStatus("✗ 开启云端预览失败", 0xFF5555);
                            }
                            return true;
                        }
                    }
                    return true;  // consume click
                }
                case SERVERS: {
                    // 0) 单人游戏守卫: 服务器 tab 在单人游戏下不响应任何点击
                    if (Minecraft.getInstance().getCurrentServer() == null) {
                        return true;
                    }
                    com.prefab.addon.network.ServerPackSyncClient sync =
                        com.prefab.addon.network.ServerPackSyncClient.getInstance();
                    // 1) 「同步服务器」按钮 (右上): 同步所有缺失
                    if (this.lastSyncServerBtn != null && !sync.isSyncing()) {
                        int[] r = this.lastSyncServerBtn;
                        if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3]) {
                            sync.requestResync();
                            setStatus("已请求同步服务器", 0x55AAFF);
                            return true;
                        }
                    }
                    // 2) 筛选 tab (全部 / 未同步 / 已同步)
                    String[] modes = {"all", "unsynced", "synced"};
                    for (int i = 0; i < 3; i++) {
                        int[] r = this.serverFilterTabs[i];
                        if (r == null || r.length < 4) continue;
                        if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3]) {
                            this.serverFilter = modes[i];
                            this.scrollOffsetCards = 0;
                            setStatus("筛选: " + modes[i], 0xAAAAAA);
                            return true;
                        }
                    }
                    // 3) 卡片点击: 先看同步按钮 rect
                    for (java.util.Map.Entry<String, int[]> e : this.serverCardSyncBtnRects.entrySet()) {
                        int[] r = e.getValue();
                        if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3]) {
                            // 找对应 ServerBuildingInfo
                            java.util.List<ServerBuildingInfo> all =
                                ExtensionPackManager.getInstance().getServerBuildings();
                            ServerBuildingInfo target = null;
                            for (ServerBuildingInfo b : all) {
                                if (e.getKey().equals(b.buildingId)) { target = b; break; }
                            }
                            if (target == null) return true;
                            if (target.synced) {
                                // 已同步 → 进入 detail
                                openSyncedServerBuildingDetail(target);
                            } else {
                                if (sync.isSyncing()) {
                                    setStatus("同步中, 请稍候...", 0xFFAA55);
                                } else {
                                    sync.requestSyncSingle(target.buildingId);
                                    setStatus("开始同步: " + target.getDisplayName(), 0x55AAFF);
                                }
                            }
                            return true;
                        }
                    }
                    // 4) 卡片主体点击: 已同步 → detail; 未同步 → 同步
                    int cardIdx = serverCardHitTest(mx, my);
                    if (cardIdx >= 0) {
                        java.util.List<ServerBuildingInfo> all =
                            ExtensionPackManager.getInstance().getServerBuildings();
                        java.util.List<ServerBuildingInfo> filtered = new java.util.ArrayList<>();
                        for (ServerBuildingInfo b : all) {
                            if ("unsynced".equals(this.serverFilter) && b.synced) continue;
                            if ("synced".equals(this.serverFilter) && !b.synced) continue;
                            filtered.add(b);
                        }
                        if (cardIdx < filtered.size()) {
                            ServerBuildingInfo target = filtered.get(cardIdx);
                            if (target.synced) {
                                openSyncedServerBuildingDetail(target);
                            } else {
                                if (sync.isSyncing()) {
                                    setStatus("同步中, 请稍候...", 0xFFAA55);
                                } else {
                                    sync.requestSyncSingle(target.buildingId);
                                    setStatus("开始同步: " + target.getDisplayName(), 0x55AAFF);
                                }
                            }
                            return true;
                        }
                    }
                    break;
                }
                case FAVORITES: {
                    java.util.List<ConstructionInfo> list = filterBySearch(
                        ExtensionPackManager.getInstance().getFavoriteConstructions(), this.searchText);
                    int idx = cardHitTest(mx, my, list.size());
                    if (idx >= 0) {
                        openConstructionDetail(list.get(idx));
                        return true;
                    }
                    break;
                }
                case DOWNLOAD: {
                    // 1) 「打开网站」按钮 (右上)
                    if (this.lastOpenWebsiteButtonRect != null) {
                        int[] r = this.lastOpenWebsiteButtonRect;
                        if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3]) {
                            openWebsiteHome();
                            return true;
                        }
                    }
                    // 2) 「筛选」下拉按钮: 循环切换 all / downloaded / not_downloaded
                    if (this.websiteFilterButtonRect != null) {
                        int[] r = this.websiteFilterButtonRect;
                        if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3]) {
                            this.websiteFilter =
                                "all".equals(this.websiteFilter) ? "downloaded"
                                : "downloaded".equals(this.websiteFilter) ? "not_downloaded"
                                : "all";
                            this.scrollOffsetCards = 0;
                            return true;
                        }
                    }
                    // 3) 卡片下载按钮: 命中就调 startWebsiteDownload
                    for (java.util.Map.Entry<String, int[]> e : this.websiteCardDownloadBtnRects.entrySet()) {
                        int[] r = e.getValue();
                        if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3]) {
                            String id = e.getKey();
                            // 找对应的 BuildingInfo2
                            for (BuildingInfo2 b : this.websiteBuildings) {
                                if (id.equals(b.id)) {
                                    if (isWebsiteBuildingDownloaded(b)) {
                                        // 已下载: 直接打开详情 (跟卡片本体点击行为一致)
                                        openDownloadedBuildingDetail(b);
                                    } else if (this.websiteDownloading.contains(id)) {
                                        setStatus("下载中, 请稍候", 0xFFAA55);
                                    } else {
                                        startWebsiteDownload(b);
                                    }
                                    return true;
                                }
                            }
                        }
                    }
                    // 4) 卡片本体: 已下载的建筑 → 点击直接进建筑详情 (没下载的不响应, 避免误操作)
                    for (java.util.Map.Entry<String, int[]> e : this.websiteCardHitRects.entrySet()) {
                        int[] r = e.getValue();
                        if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3]) {
                            String id = e.getKey();
                            for (BuildingInfo2 b : this.websiteBuildings) {
                                if (id.equals(b.id)) {
                                    if (isWebsiteBuildingDownloaded(b)) {
                                        openDownloadedBuildingDetail(b);
                                    } else {
                                        setStatus("✗ 该建筑还未下载, 先点底部「下载」按钮", 0xAAAAAA);
                                    }
                                    return true;
                                }
                            }
                        }
                    }
                    return true;  // consume click
                }
                // 注: 原版 tab 已删除 (Modrinth 版权审核), VANILLA 枚举值不存在, 不会进任何 case.
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // 翻页改用底部分页按钮, 滚轮不再用于翻页 (避免和搜索框抢事件)
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /**
     * 当前 tab 的卡片"每页能放几个" (跟各 tab 的 drawXxx 里用的 pageSize 必须一致,
     * 否则分页按钮会算错页数 → 点 [›] 没反应).
     *
     * <p>历史 bug: 之前这里统一用 {@code CARD_COLS * CARD_ROWS = 6} 作 pageSize,
     * 但 CLOUD / DOWNLOAD tab 的 draw 函数用 {@code (listH + CARD_GAP) / (cardH + CARD_GAP)}
     * (cardH=70, 只算行数不算列数). 结果: 视觉显示 "1/2" 但 maxPage 算成 0, 点 [›] 完全无效.</p>
     */
    private int computePageSizeForCurrentTab() {
        if (this.currentTab == Tab.CLOUD) {
            // 跟 drawTabCloud 完全一致
            int rh = PANEL_H - 8;                  // getContentRect 的 rh (无 search)
            int listH = rh - 22;                   // draw 里 listH = rh - 22
            int cardH = CLOUD_CARD_H;              // draw 里 cardH = CLOUD_CARD_H (96, 不要再写 70)
            int cardGap = CLOUD_CARD_GAP;
            return Math.max(1, (listH + cardGap) / (cardH + cardGap)) * CLOUD_CARD_COLS;
        }
        if (this.currentTab == Tab.DOWNLOAD) {
            // 跟 drawTabDownload 完全一致
            int rh = PANEL_H - 8;
            int listH = rh - 22;
            int cardH = 70;
            int cardGap = 6;
            return Math.max(1, (listH + cardGap) / (cardH + cardGap)) * 2;
        }
        if (this.currentTab == Tab.SERVERS) {
            // 跟 drawServerTab / drawServerBuildingCards 完全一致
            int rh = PANEL_H - 8;                  // getContentRect 的 rh (无 search)
            int filterH = rh - 8;                  // drawServerTab 里 filterH = rh - 8
            int tabH = 18;                         // drawServerTab 里 tabH 写死
            int listH = filterH - tabH - 4 - 14;   // drawServerBuildingCards 里 listH = rh - 14
            int cardH = SERVER_CARD_H;
            int cardGap = SERVER_CARD_GAP;
            return Math.max(1, (listH + cardGap) / (cardH + cardGap)) * SERVER_CARD_COLS;
        }
        if (this.currentTab == Tab.BUILDINGS) {
            // 跟 drawConstructionCardsForBuildings 完全一致: 2 列 × 2 行 = 4/页
            // (不能用默认的 CARD_COLS=3, 那是 3 列的拓展包 tab 用的, 跟建筑 tab 不一致)
            return BUILDINGS_CARD_COLS * CARD_ROWS;
        }
        return CARD_COLS * CARD_ROWS;
    }

    /** 当前 tab 的总页数 (用于滚轮翻页 clamp). */
    private int computeMaxPageForCurrentTab() {
        int pageSize = computePageSizeForCurrentTab();
        int total;
        if (this.currentTab == Tab.DOWNLOAD) {
            total = getFilteredWebsiteBuildings().size();
        } else if (this.currentTab == Tab.FAVORITES) {
            total = filterBySearch(
                ExtensionPackManager.getInstance().getFavoriteConstructions(), this.searchText).size();
        } else if (this.currentTab == Tab.CLOUD) {
            // 云端 tab 不分搜索, 直接拿 cache 数量
            total = CloudBuildingClientCache.getInstance().size();
        } else if (this.currentTab == Tab.SERVERS) {
            // 服务器 tab: 应用 serverFilter 后取总数
            java.util.List<ServerBuildingInfo> all =
                ExtensionPackManager.getInstance().getServerBuildings();
            int n = 0;
            for (ServerBuildingInfo b : all) {
                if (b == null) continue;
                if ("unsynced".equals(this.serverFilter) && b.synced) continue;
                if ("synced".equals(this.serverFilter) && !b.synced) continue;
                n++;
            }
            total = n;
        } else {
            total = filterBySearch(getMergedConstructionsForBuildingsTab(), this.searchText).size();
        }
        return Math.max(0, (total + pageSize - 1) / pageSize - 1);
    }

    /** 当前 DOWNLOAD tab 过滤后的网站建筑列表 (供 computeMaxPageForCurrentTab 复用). */
    private java.util.List<BuildingInfo2> getFilteredWebsiteBuildings() {
        java.util.List<BuildingInfo2> result = new java.util.ArrayList<>();
        for (BuildingInfo2 b : this.websiteBuildings) {
            if (b == null || b.id == null) continue;
            boolean downloaded = PackDownloadManager.getInstance().isBuildingDownloaded(b);
            if ("all".equals(this.websiteFilter)) {
                result.add(b);
            } else if ("downloaded".equals(this.websiteFilter) && downloaded) {
                result.add(b);
            } else if ("not_downloaded".equals(this.websiteFilter) && !downloaded) {
                result.add(b);
            }
        }
        return result;
    }

    // ============================================================
    // 按钮事件
    // ============================================================

    @Override
    public void buttonClicked(AbstractButton button) {
        if (button == this.btnClose) {
            this.onClose();
            return;
        }
        if (button == this.btnBack) {
            this.currentDrilldownPack = null;
            this.btnBack.visible = false;
            this.scrollOffsetCards = 0;
            this.searchText = "";
            if (this.searchBox != null) this.searchBox.setValue("");
            return;
        }
        if (button == this.btnSync) {
            // 触发热重载 + 触发 sync
            ExtensionPackManager.getInstance().reloadClient();
            // 让用户感知: 触发服务端 pack manifest 请求
            try {
                com.prefab.addon.network.ServerPackSyncClient.getInstance().requestResync();
            } catch (Exception e) {
                PrefabCustomAddon.LOGGER.warn("[BROWSER] sync 失败: {}", e.getMessage());
            }
            setStatus("已请求同步服务器建筑", 0x55AAFF);
            return;
        }
        if (button == this.btnCheckDeps) {
            runDependencyCheck();
            return;
        }
        if (button == this.btnOpenFolder) {
            boolean ok = FolderOpener.openExtensionFolder();
            if (ok) {
                setStatus("已打开拓展包文件夹", 0x55FF55);
            } else {
                setStatus("打开失败, 请手动访问: " + com.prefab.addon.download.PackDownloadManager.getExtensionRoot(),
                    0xFFAA55);
            }
            return;
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC = 在 drilldown 视图返回顶层, 否则关闭界面
        if (keyCode == 256 /* GLFW_KEY_ESCAPE */) {
            // 优先处理删除确认弹窗: ESC 取消删除
            if (this.pendingDeleteBuildingId != null) {
                this.pendingDeleteBuildingId = null;
                this.deleteConfirmCancelRect = null;
                this.deleteConfirmOkRect = null;
                setStatus("已取消删除", 0xAAAAAA);
                return true;
            }
            if (this.currentDrilldownPack != null) {
                this.currentDrilldownPack = null;
                this.btnBack.visible = false;
                this.scrollOffsetCards = 0;
                this.searchText = "";
                if (this.searchBox != null) this.searchBox.setValue("");
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        // 搜索框获得焦点时, 让 EditBox 消费字符
        if (this.searchBox != null && this.searchBox.isVisible() && this.searchBox.isFocused()) {
            // 让 EditBox 自身处理
        }
        return super.charTyped(codePoint, modifiers);
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private void switchTab(Tab tab) {
        if (tab == this.currentTab && this.currentDrilldownPack == null) return;

        // === 1. 切走前, 把当前 tab 的状态存到 TAB_STATES ===
        //   (用 EnumMap, 每个 tab 各自一份, 切回时能恢复 "原版第2页" 那种状态)
        if (this.currentTab != null) {
            TAB_STATES.put(this.currentTab, new TabState(
                this.currentCategory,
                this.scrollOffsetCards,
                this.categoryPanelHidden,
                this.searchText
            ));
        }

        // === 2. 切换 tab ===
        this.currentTab = tab;
        this.currentDrilldownPack = null;
        this.btnBack.visible = false;

        // === 3. 恢复目标 tab 的状态 (没存过 → 用默认: 全部/第0页/未收起/空搜索) ===
        TabState saved = TAB_STATES.get(tab);
        final int savedScroll;
        final boolean savedPanelHidden;
        final String savedSearch;
        if (saved != null) {
            this.currentCategory      = saved.currentCategory;
            savedScroll               = saved.scrollOffsetCards;
            savedPanelHidden          = saved.categoryPanelHidden;
            savedSearch               = saved.searchText;
        } else {
            this.currentCategory      = null;
            savedScroll               = 0;
            savedPanelHidden          = false;
            savedSearch               = "";
        }
        // 先把面板状态和搜索文本写回 instance 字段 (setValue 会触发 responder,
        // responder 会把 scrollOffsetCards 清零, 所以页数留到最后再写)
        this.categoryPanelHidden = savedPanelHidden;
        this.searchText          = savedSearch;
        if (this.searchBox != null) {
            this.searchBox.setValue(this.searchText);
            // 切 tab 时按需重设搜索框宽度 (建筑 tab 短, 其它 tab 全宽)
            rebuildSearchBox();
            // 重建/恢复搜索框后再写回页数, 避免被搜索框 responder 清零
            this.scrollOffsetCards = savedScroll;
        } else {
            this.scrollOffsetCards = savedScroll;
        }
        // 切到云端 tab 时, 顺手强制重探一下 Xaero (玩家进入世界后 Xaero session
        // 可能比 sync 收包晚就绪 → 之前的 probe 失败被永久缓存 → 导航按钮不出).
        if (tab == Tab.CLOUD) {
            XaeroWaypointBridge.forceProbe();
        }
        // 切 tab 时清掉云端删除确认弹窗
        this.pendingDeleteBuildingId = null;
        this.deleteConfirmCancelRect = null;
        this.deleteConfirmOkRect = null;
        // 切到下载 tab 时重新扫描本地 prefab-download/ + 拉取网站列表
        if (tab == Tab.DOWNLOAD) {
            refreshDownloadedBuildings();
            // 网站列表只在没拉过 / 拉取失败时拉, 避免每次切 tab 都打服务器
            if (this.websiteBuildings.isEmpty() && !this.websiteBuildingsLoading) {
                refreshWebsiteBuildings();
            }
        }
    }

    /** 是否当前在 BUILDINGS tab (含 drilldown 到单包建筑的情况). */
    private boolean isBuildingsTab() {
        return this.currentTab == Tab.BUILDINGS || this.currentDrilldownPack != null;
    }

    /**
     * 重建搜索框 widget, 让宽度匹配当前 tab.
     * Minecraft EditBox 宽度在构造时定, 改不了, 只能 remove + 重新 add.
     */
    private void rebuildSearchBox() {
        if (this.searchBox == null) return;
        int[] pos = computePanelPos();
        int grayBoxX = pos[0];
        int grayBoxY = pos[1];
        int sbX = grayBoxX + TABS_W + 6;
        int sbY = grayBoxY + 4;
        int targetW = isBuildingsTab() ? SEARCH_BOX_W : SEARCH_FULL_W;
        if (this.searchBox.getWidth() == targetW) {
            // 已经是目标宽度, 不动
            return;
        }
        // 暂存当前文本, 删旧 widget, 建新 widget
        String current = this.searchBox.getValue();
        this.removeWidget(this.searchBox);
        this.searchBox = new net.minecraft.client.gui.components.EditBox(
            this.font, sbX, sbY, targetW, SEARCH_H - 2,
            net.minecraft.network.chat.Component.literal(tr("browser.search.placeholder")));
        this.searchBox.setMaxLength(64);
        this.searchBox.setBordered(true);
        this.searchBox.setValue(current);
        this.searchBox.setResponder(text -> {
            this.searchText = text;
            this.scrollOffsetCards = 0;
        });
        this.addRenderableWidget(this.searchBox);
    }

    private void drilldownPack(ExtensionPack p) {
        this.currentDrilldownPack = p;
        this.btnBack.visible = true;
        this.scrollOffsetCards = 0;
        this.searchText = "";
        this.currentCategory = null;  // drilldown 也不带分类
        if (this.searchBox != null) {
            this.searchBox.setValue("");
            // drilldown 仍属于 "建筑" 类 tab, 用短搜索框
            rebuildSearchBox();
        }
    }

    private void openConstructionDetail(ConstructionInfo c) {
        // 在打开详情前先保存状态到 static 字段 (onClose 也会保存, 这里双保险, 防止 onClose 顺序问题)
        GuiExtensionPackBrowser.rememberedCategory = this.currentCategory;
        GuiExtensionPackBrowser.rememberedPage = this.scrollOffsetCards;
        GuiExtensionPackBrowser.rememberedPanelHidden = this.categoryPanelHidden;
        // 直接进详情 (无下拉框, 无翻页按钮, 右上角有收藏按钮)
        GuiConstructionDetail.open(c);
    }

    /**
     * 下载 tab 里, 已下载的网站建筑: 找到本地的 LocalBuilding, 转成 ConstructionInfo 打开详情.
     *
     * <p>下载到 prefab-download/ 的文件命名规则 (跟 {@link #isWebsiteBuildingDownloaded} 一致):
     * 文件名 = {@code sanitize(b.name) + b.fileExt} (或 _2 / _3 ... 等后缀, 用于重名). </p>
     *
     * <p>对应到 {@link LocalBuilding} 的 id (即不带扩展名的文件名), 所以直接按 baseName 匹配.</p>
     */
    private void openDownloadedBuildingDetail(BuildingInfo2 b) {
        if (b == null) return;
        String baseName = b.name == null || b.name.isEmpty() ? b.id : b.name;
        baseName = baseName.replaceAll("[\\\\/:*?\"<>|]", "_");
        // 规范化 ext: 跟下载时一致, 都带点 (".nbt" 而不是 "nbt")
        String ext = b.fileExt == null || b.fileExt.isEmpty() ? ".nbt" : b.fileExt;
        if (!ext.startsWith(".")) ext = "." + ext;
        // 去掉点的 ext, 用于跟 LocalBuilding.fileExt (保留点) 比较
        String extNoDot = ext.substring(1);

        // 1) 优先: 用 prefab-download/ 里 baseName 匹配 LocalBuilding
        Path dlRoot = LocalBuildingScanner.getDownloadRoot();
        java.util.List<LocalBuilding> dlList = LocalBuildingScanner.scanDir(dlRoot, "download");
        for (LocalBuilding lb : dlList) {
            // 修复: 之前用 ext.replaceFirst("^\\.", "") 去点, 跟 lb.fileExt (保留点) 比较永远 false
            // → 已下载的建筑都找不到. 这里改成两个都规范化, 任意一个匹配即可
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
                openConstructionDetail(c);
                return;
            }
        }

        // 2) 兜底: 也扫 prefab-extension/, 玩家可能把下载的文件搬过去了
        Path extRoot = LocalBuildingScanner.getExtensionRoot();
        java.util.List<LocalBuilding> extList = LocalBuildingScanner.scanDir(extRoot, "extension");
        for (LocalBuilding lb : extList) {
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
                openConstructionDetail(c);
                return;
            }
        }

        // 3) 都找不到 (本地文件被删了但状态还在): 给提示, 不强行开空详情
        setStatus("✗ 找不到已下载文件, 试重新下载: " + b.name, 0xFFAA55);
    }

    private void setStatus(String msg, int color) {
        this.statusMessage = msg;
        this.statusColor = color;
        this.statusTick = 200;
    }

    private void runDependencyCheck() {
        // 简化: 扫描所有 pack, 把缺失的 mod 列在状态栏
        java.util.Map<String, java.util.List<String>> missing = new java.util.LinkedHashMap<>();
        ExtensionPackManager mgr = ExtensionPackManager.getInstance();
        for (ExtensionPack p : mgr.getPacksForGui()) {
            java.util.List<String> deps = p.getDependencies();
            if (deps == null || deps.isEmpty()) continue;
            com.prefab.addon.work.DependencyChecker.CheckResult r = com.prefab.addon.work.DependencyChecker.check(deps);
            if (!r.missing.isEmpty()) {
                missing.put(p.getName(), r.missing);
            }
        }
        if (missing.isEmpty()) {
            setStatus("✓ 所有拓展包依赖都已满足", 0x55FF55);
        } else {
            int totalMissing = missing.values().stream().mapToInt(java.util.List::size).sum();
            setStatus("✗ 共 " + totalMissing + " 个依赖缺失 (点击下方 [依赖详情] 查看)",
                0xFFAA55);
        }
    }

    /**
     * 简单搜索: 不区分大小写, 匹配 name / author / id.
     */
    private java.util.List<ConstructionInfo> filterBySearch(java.util.List<ConstructionInfo> src, String q) {
        if (q == null || q.trim().isEmpty()) return src;
        String needle = q.trim().toLowerCase();
        java.util.List<ConstructionInfo> out = new ArrayList<>();
        for (ConstructionInfo c : src) {
            if (matches(c.getName(), needle) || matches(c.getAuthor(), needle)
                || matches(c.getId(), needle)) {
                out.add(c);
            }
        }
        return out;
    }

    private static boolean matches(String s, String needle) {
        return s != null && s.toLowerCase().contains(needle);
    }

    private void drawEmpty(GuiGraphics guiGraphics, int[] rect, String title, String hint) {
        int cx = rect[0] + rect[2] / 2;
        int cy = rect[1] + rect[3] / 2 - 10;
        guiGraphics.drawCenteredString(this.font, "§7" + title, cx, cy, 0xAAAAAA);
        guiGraphics.drawCenteredString(this.font, hint, cx, cy + 14, 0x888888);
    }

    // ----- 封面/预览图加载 (NEAREST 缩放) -----

    private ResourceLocation ensureCoverTextureLoaded(ExtensionPack p) {
        String key = p.getPackageName() != null ? p.getPackageName() : p.getFileName();
        if (this.coverTextureCache.containsKey(key)) return this.coverTextureCache.get(key);
        try (java.io.InputStream is = new java.io.ByteArrayInputStream(p.getCoverImageData())) {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(is);
            if (img == null) return null;
            int w = img.getWidth(), h = img.getHeight();
            net.minecraft.client.renderer.texture.DynamicTexture tex =
                new net.minecraft.client.renderer.texture.DynamicTexture(w, h, false);
            tex.setFilter(false, false);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = img.getRGB(x, y);
                    int abgr = ((argb & 0xFF00FF00) | ((argb & 0x00FF0000) >> 16) | ((argb & 0x000000FF) << 16));
                    tex.getPixels().setPixelRGBA(x, y, abgr);
                }
            }
            tex.upload();
            ResourceLocation loc = Minecraft.getInstance().getTextureManager()
                .register("prefab_cover_" + key, tex);
            this.coverTextureCache.put(key, loc);
            return loc;
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("Failed to load cover image for {}", p.getName(), e);
            this.coverTextureCache.put(key, null);
            return null;
        }
    }

    private ResourceLocation ensurePreviewTextureLoaded(ConstructionInfo c) {
        String key = c.getId();
        if (this.previewTextureCache.containsKey(key)) return this.previewTextureCache.get(key);
        if (!c.hasPreviewImage()) {
            // 诊断: 为什么 hasPreviewImage 返回 false. 大概率是 c.getLocalImagePath() 为 null 或者文件不存在
            PrefabCustomAddon.LOGGER.info("[PREVIEW] {} 无图: pngData={} localImagePath={} exists={}",
                key, c.getPngData() != null ? c.getPngData().length : "null",
                c.getLocalImagePath(),
                c.getLocalImagePath() != null ? Files.exists(c.getLocalImagePath()) : "n/a");
            this.previewTextureCache.put(key, null);
            return null;
        }
        // 优先用 zip 包内缓存的 pngData; 没有时从单文件建筑本地 .png 路径读
        byte[] data = c.getPngData();
        if ((data == null || data.length == 0) && c.getLocalImagePath() != null) {
            try {
                data = Files.readAllBytes(c.getLocalImagePath());
                PrefabCustomAddon.LOGGER.info("[PREVIEW] {} 从 {} 读到 {} bytes", key, c.getLocalImagePath(), data.length);
            } catch (Exception e) {
                PrefabCustomAddon.LOGGER.warn("[PREVIEW] {} 读 {} 失败: {}", key, c.getLocalImagePath(), e.getMessage());
            }
        }
        if (data == null || data.length == 0) {
            PrefabCustomAddon.LOGGER.info("[PREVIEW] {} data 为空, 跳过", key);
            this.previewTextureCache.put(key, null);
            return null;
        }
        try (java.io.InputStream is = new java.io.ByteArrayInputStream(data)) {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(is);
            if (img == null) {
                PrefabCustomAddon.LOGGER.warn("[PREVIEW] {} ImageIO 解析失败 ({} bytes), 文件可能损坏或格式不支持",
                    key, data.length);
                this.previewTextureCache.put(key, null);
                return null;
            }
            net.minecraft.client.renderer.texture.DynamicTexture tex = uploadIconTexture(img);
            if (tex == null) {
                PrefabCustomAddon.LOGGER.warn("[PREVIEW] {} uploadIconTexture 返回 null (源 {}x{})", key, img.getWidth(), img.getHeight());
                this.previewTextureCache.put(key, null);
                return null;
            }
            ResourceLocation loc = Minecraft.getInstance().getTextureManager()
                .register("prefab_preview_" + key, tex);
            PrefabCustomAddon.LOGGER.info("[PREVIEW] {} 上传成功 {}x{} → {}", key,
                img.getWidth(), img.getHeight(), loc);
            this.previewTextureCache.put(key, loc);
            return loc;
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("Failed to load preview for {}", c.getName(), e);
            this.previewTextureCache.put(key, null);
            return null;
        }
    }

    /** 加载缩略图缓存 PNG (跟 ensurePreviewTextureLoaded 一样的逻辑, 但用 cachedThumbTextureCache) */
    private final java.util.Map<String, ResourceLocation> cachedThumbTextureCache = new java.util.HashMap<>();
    private ResourceLocation ensureCachedThumbnailTextureLoaded(ConstructionInfo c, byte[] pngData) {
        String key = com.prefab.addon.client.ThumbnailCache.fingerprint(c);
        if (this.cachedThumbTextureCache.containsKey(key)) return this.cachedThumbTextureCache.get(key);
        try (java.io.InputStream is = new java.io.ByteArrayInputStream(pngData)) {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(is);
            if (img == null) {
                this.cachedThumbTextureCache.put(key, null);
                return null;
            }
            net.minecraft.client.renderer.texture.DynamicTexture tex = uploadIconTexture(img);
            if (tex == null) {
                this.cachedThumbTextureCache.put(key, null);
                return null;
            }
            ResourceLocation loc = Minecraft.getInstance().getTextureManager()
                .register("prefab_thumb_" + key, tex);
            this.cachedThumbTextureCache.put(key, loc);
            return loc;
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("Failed to load cached thumbnail for {}", c.getName(), e);
            this.cachedThumbTextureCache.put(key, null);
            return null;
        }
    }

    private void drawIconNearest(GuiGraphics guiGraphics, ResourceLocation texture,
                                  int x, int y, int w, int h,
                                  int u, int v, int uW, int vH, int sheetW, int sheetH) {
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);
        // 改 GL_LINEAR: uploadIconTexture 里 tex.setFilter(true, true) 设的是 bilinear,
        // 但这里强制 GL_NEAREST 会覆盖它, 缩到 60x60 时有马赛克. 改 LINEAR 保持平滑.
        RenderSystem.texParameter(com.mojang.blaze3d.platform.GlConst.GL_TEXTURE_2D,
            com.mojang.blaze3d.platform.GlConst.GL_TEXTURE_MIN_FILTER,
            com.mojang.blaze3d.platform.GlConst.GL_LINEAR);
        RenderSystem.texParameter(com.mojang.blaze3d.platform.GlConst.GL_TEXTURE_2D,
            com.mojang.blaze3d.platform.GlConst.GL_TEXTURE_MAG_FILTER,
            com.mojang.blaze3d.platform.GlConst.GL_LINEAR);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        float f = 1.0F / sheetW;
        float f1 = 1.0F / sheetH;
        float u0 = (float) u * f;
        float v0 = (float) v * f1;
        float u1 = (float) (u + uW) * f;
        float v1 = (float) (v + vH) * f1;

        com.mojang.blaze3d.vertex.Tesselator tesselator = com.mojang.blaze3d.vertex.Tesselator.getInstance();
        com.mojang.blaze3d.vertex.BufferBuilder buffer = tesselator.begin(
            com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
            com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR);
        buffer.addVertex(x, y + h, 0).setUv(u0, v1).setColor(1.0F, 1.0F, 1.0F, 1.0F);
        buffer.addVertex(x + w, y + h, 0).setUv(u1, v1).setColor(1.0F, 1.0F, 1.0F, 1.0F);
        buffer.addVertex(x + w, y, 0).setUv(u1, v0).setColor(1.0F, 1.0F, 1.0F, 1.0F);
        buffer.addVertex(x, y, 0).setUv(u0, v0).setColor(1.0F, 1.0F, 1.0F, 1.0F);
        BufferUploader.drawWithShader(buffer.build());
    }

    @Override
    public void onClose() {
        // 关闭前记录 GUI 状态, 下次 open() 时恢复
        GuiExtensionPackBrowser.rememberedCategory = this.currentCategory;
        GuiExtensionPackBrowser.rememberedPage = this.scrollOffsetCards;
        GuiExtensionPackBrowser.rememberedPanelHidden = this.categoryPanelHidden;

        // 释放纹理
        this.coverTextureCache.clear();
        this.previewTextureCache.clear();
        this.cachedThumbTextureCache.clear();
        this.localImageCache.clear();
        this.websiteImageCache.clear();
        this.serverImageCache.clear();
        super.onClose();
    }

    /**
     * 打开已同步服务器建筑的 detail 界面.
     * 单文件 (.nbt/.schem/.litematic): 直接读 server-cache/&lt;name&gt;.&lt;ext&gt; 走 ConstructionInfo 路径.
     * 拓展包 (.zip): 手动解析 zip 找 construction/&lt;id&gt;.nbt + &lt;id&gt;.png, 构造 ConstructionInfo.
     */
    private void openSyncedServerBuildingDetail(ServerBuildingInfo b) {
        if (b == null || !b.synced) return;
        Path cacheDir = com.prefab.addon.extension.ExtensionPackManager.getInstance().getServerCacheDir();
        if (cacheDir == null || !Files.exists(cacheDir)) {
            setStatus("✗ 找不到 server-cache/ 目录", 0xFF5555);
            return;
        }
        // 源文件 basename: 老 zip 是 packName (= 去掉 .zip 后的源文件名), 独立 .nbt 是 buildingId
        String fileBase = b.packName != null && !b.packName.isEmpty() ? b.packName : b.buildingId;
        String ext = b.getSourceExt().toLowerCase();
        if (ext.equals(".zip")) {
            // 拓展包: 手动解析 zip
            Path zipPath = findServerFile(cacheDir, fileBase, ".zip");
            if (zipPath == null) {
                setStatus("✗ 找不到 zip: " + fileBase + ".zip", 0xFF5555);
                return;
            }
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(zipPath.toFile())) {
                // 找 construction/ 下的 .nbt (取第一个)
                java.util.zip.ZipEntry nbtEntry = null;
                java.util.zip.ZipEntry pngEntry = null;
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    java.util.zip.ZipEntry e = en.nextElement();
                    if (e.isDirectory()) continue;
                    String ename = e.getName().toLowerCase();
                    if (nbtEntry == null && (ename.endsWith(".nbt") || ename.endsWith(".litematic")
                        || ename.endsWith(".schem") || ename.endsWith(".schematic"))) {
                        nbtEntry = e;
                    }
                    if (pngEntry == null && ename.endsWith(".png")) {
                        pngEntry = e;
                    }
                }
                if (nbtEntry == null) {
                    setStatus("✗ zip " + fileBase + " 里没找到建筑文件", 0xFF5555);
                    return;
                }
                // 大文件保护: 超过 20MB 提示玩家单独下载, 避免 OOM
                long nbtSize = nbtEntry.getSize();
                if (nbtSize > 20L * 1024 * 1024) {
                    setStatus("✗ 建筑太大 (" + (nbtSize / 1024 / 1024) + "MB), 不支持在线预览", 0xFFAA55);
                    return;
                }
                ConstructionInfo c = new ConstructionInfo(b.buildingId);
                c.setName(b.getDisplayName());
                if (pngEntry != null) {
                    try (java.io.InputStream is = zip.getInputStream(pngEntry)) {
                        c.setPngData(is.readAllBytes());
                    }
                }
                try (java.io.InputStream is = zip.getInputStream(nbtEntry)) {
                    c.setNbtData(is.readAllBytes());
                }
                GuiConstructionDetail.open(c);
            } catch (Exception e) {
                setStatus("✗ 解析 zip 失败: " + e.getMessage(), 0xFF5555);
                PrefabCustomAddon.LOGGER.warn("[SERVER-DETAIL] zip parse failed for {}", fileBase, e);
            }
        } else {
            // 单文件: 读 server-cache/&lt;fileBase&gt;.&lt;ext&gt;
            Path filePath = findServerFile(cacheDir, fileBase, ext);
            if (filePath == null) {
                setStatus("✗ 找不到文件: " + fileBase + ext, 0xFF5555);
                return;
            }
            Path imgPath = findServerFile(cacheDir, fileBase, ".png");
            if (imgPath == null) {
                for (String ie : new String[]{".jpg", ".jpeg", ".webp"}) {
                    imgPath = findServerFile(cacheDir, fileBase, ie);
                    if (imgPath != null) break;
                }
            }
            // 直接打开 detail
            ConstructionInfo c = new ConstructionInfo(b.buildingId);
            c.setName(b.getDisplayName());
            c.setLocalImagePath(imgPath);
            c.setLocalNbtPath(filePath);
            GuiConstructionDetail.open(c);
        }
    }
}
