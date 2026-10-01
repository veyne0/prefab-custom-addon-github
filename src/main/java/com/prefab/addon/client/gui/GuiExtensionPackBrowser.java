/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.blaze3d.platform.NativeImage
 *  com.mojang.blaze3d.systems.RenderSystem
 *  com.mojang.blaze3d.vertex.BufferBuilder
 *  com.mojang.blaze3d.vertex.BufferUploader
 *  com.mojang.blaze3d.vertex.DefaultVertexFormat
 *  com.mojang.blaze3d.vertex.MeshData
 *  com.mojang.blaze3d.vertex.Tesselator
 *  com.mojang.blaze3d.vertex.VertexFormat$Mode
 *  com.prefab.gui.GuiBase
 *  com.prefab.gui.controls.ExtendedButton
 *  net.minecraft.Util
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.GuiGraphics
 *  net.minecraft.client.gui.components.AbstractButton
 *  net.minecraft.client.gui.components.EditBox
 *  net.minecraft.client.gui.components.events.GuiEventListener
 *  net.minecraft.client.gui.screens.Screen
 *  net.minecraft.client.renderer.GameRenderer
 *  net.minecraft.client.renderer.texture.DynamicTexture
 *  net.minecraft.core.registries.Registries
 *  net.minecraft.network.chat.Component
 *  net.minecraft.resources.ResourceKey
 *  net.minecraft.resources.ResourceLocation
 *  net.minecraft.world.level.Level
 */
package com.prefab.addon.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.client.PackBrowserKeyHandler;
import com.prefab.addon.client.ThumbnailCache;
import com.prefab.addon.client.gui.GuiCategoryManager;
import com.prefab.addon.client.gui.GuiConstructionDetail;
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
import com.prefab.addon.extension.ServerBuildingInfo;
import com.prefab.addon.integration.xaero.XaeroWaypointBridge;
import com.prefab.addon.network.ServerPackSyncClient;
import com.prefab.addon.work.ChallengeSessionManager;
import com.prefab.addon.work.DependencyChecker;
import com.prefab.addon.work.FolderOpener;
import com.prefab.gui.GuiBase;
import com.prefab.gui.controls.ExtendedButton;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

public class GuiExtensionPackBrowser
extends GuiBase {
    private Tab currentTab = Tab.BUILDINGS;
    private ExtensionPack currentDrilldownPack = null;
    private static final int PANEL_W = 400;
    private static final int PANEL_H = 240;
    private static final int TABS_W = 72;
    private static final int SEARCH_H = 18;
    private static final int CARD_W = 92;
    private static final int CARD_H = 80;
    private static final int CARD_GAP = 6;
    private static final int CARD_COLS = 3;
    private static final int CARD_ROWS = 2;
    private static final int CLOUD_CARD_COLS = 2;
    private static final int CLOUD_CARD_GAP = 6;
    private static final int CLOUD_CARD_H = 96;
    private static final int SERVER_CARD_COLS = 2;
    private static final int SERVER_CARD_GAP = 6;
    private static final int SERVER_CARD_H = 56;
    private static final int BUILDINGS_CARD_COLS = 2;
    private static final int CAT_LIST_W = 110;
    private static final int SEARCH_BOX_W = 110;
    private static final int SEARCH_FULL_W = 316;
    private String searchText = "";
    private int scrollOffsetCards = 0;
    private String currentCategory = null;
    private static String rememberedCategory = null;
    private static int rememberedPage = 0;
    private static boolean rememberedPanelHidden = false;
    private static final Map<Tab, TabState> TAB_STATES = new EnumMap<Tab, TabState>(Tab.class);
    private int[] paginationBarRect = null;
    private int[] paginationPrevRect = null;
    private int[] paginationNextRect = null;
    private int[][] paginationPageRects = null;
    private final List<int[]> categoryItemRects = new ArrayList<int[]>();
    private int[] categoryAddBtnRect = null;
    private int[] categoryToggleBtnRect = null;
    private boolean categoryPanelHidden = rememberedPanelHidden;
    private final Map<String, List<String>> depCheckMissing;
    private int statusTick = 0;
    private String statusMessage = null;
    private int statusColor = 0x55FF55;
    private final Map<String, ResourceLocation> coverTextureCache;
    private final Map<String, ResourceLocation> previewTextureCache;
    private ExtendedButton btnBack;
    private ExtendedButton btnSync;
    private ExtendedButton btnCheckDeps;
    private ExtendedButton btnOpenFolder;
    private ExtendedButton btnClose;
    private EditBox searchBox;
    private List<LocalBuilding> downloadedBuildings;
    private final Map<String, ResourceLocation> localImageCache;
    private final Set<String> localImageLoading;
    private final Map<String, ResourceLocation> serverImageCache;
    private final Map<String, int[]> serverCardSyncBtnRects;
    private final Set<String> serverImageLoading;
    private List<PackDownloadManager.BuildingInfo2> websiteBuildings;
    private boolean websiteBuildingsLoading = false;
    private String websiteBuildingsError = null;
    private final Map<String, ResourceLocation> websiteImageCache;
    private final Set<String> websiteImageLoading;
    private final Set<String> websiteDownloading;
    private final Map<String, double[]> websiteDownloadProgress;
    private int[] lastOpenWebsiteButtonRect = null;
    private String websiteFilter = "all";
    private int[] websiteFilterButtonRect = null;
    private final Map<String, int[]> websiteCardHitRects;
    private final Map<String, int[]> websiteCardDownloadBtnRects;
    private final Map<String, int[]> cloudCardHitRects;
    private final Map<String, int[]> cloudCardRecallBtnRects;
    private final Map<String, int[]> cloudCardSummonBtnRects;
    private final Map<String, int[]> cloudCardDeleteBtnRects;
    private final Map<String, int[]> cloudCardNavigateBtnRects;
    private final Set<String> navigateDebugLogged;
    private String pendingDeleteBuildingId = null;
    private int[] deleteConfirmCancelRect = null;
    private int[] deleteConfirmOkRect = null;
    private long cloudTabLastEnterTickMs = 0L;
    private final Map<String, ResourceLocation> cloudThumbCache;
    private final Set<String> cloudThumbLoading;
    private static final int ICON_MAX_DIM = 256;
    private int[] lastSyncServerBtn = null;
    private String serverFilter = "unsynced";
    private int[][] serverFilterTabs;
    private int[][] serverCardHitRects;
    private int serverCardHitCount = 0;
    private final Map<String, ResourceLocation> cachedThumbTextureCache;

    private void refreshDownloadedBuildings() {
        this.downloadedBuildings = LocalBuildingScanner.scanDir(LocalBuildingScanner.getDownloadRoot(), "download");
        for (LocalBuilding lb : this.downloadedBuildings) {
            if (!lb.hasPreviewImage() || this.localImageCache.containsKey(lb.id) || this.localImageLoading.contains(lb.id)) continue;
            this.localImageLoading.add(lb.id);
            this.triggerLoadLocalImage(lb);
        }
    }

    private void triggerLoadLocalImage(LocalBuilding lb) {
        Path imgPath = lb.imagePath;
        if (imgPath == null || !Files.exists(imgPath, new LinkOption[0])) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try (InputStream is = Files.newInputStream(imgPath, new OpenOption[0]);){
                BufferedImage img = ImageIO.read(is);
                if (img == null) {
                    return;
                }
                Minecraft.getInstance().execute(() -> {
                    try {
                        DynamicTexture tex = GuiExtensionPackBrowser.uploadIconTexture(img);
                        if (tex == null) {
                            return;
                        }
                        ResourceLocation loc = Minecraft.getInstance().getTextureManager().register("prefab_dl_" + lb.id, tex);
                        this.localImageCache.put(lb.id, loc);
                    }
                    catch (Exception e) {
                        PrefabCustomAddon.LOGGER.warn("[DOWNLOAD-TAB] local image upload failed for {}", (Object)lb.id, (Object)e);
                    }
                });
            }
            catch (Exception e) {
                PrefabCustomAddon.LOGGER.warn("[DOWNLOAD-TAB] local image read failed for {}", (Object)lb.id, (Object)e);
            }
        });
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static DynamicTexture uploadIconTexture(BufferedImage img) {
        if (img == null) {
            return null;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }
        int cropSide = Math.min(w, h);
        int cropX = (w - cropSide) / 2;
        int cropY = (h - cropSide) / 2;
        if (cropSide < w || cropSide < h) {
            BufferedImage cropped = img.getSubimage(cropX, cropY, cropSide, cropSide);
            BufferedImage croppedCopy = new BufferedImage(cropSide, cropSide, 2);
            croppedCopy.createGraphics().drawImage((Image)cropped, 0, 0, null);
            img = croppedCopy;
            w = cropSide;
            h = cropSide;
        }
        if (w > 256) {
            double scale = 256.0 / (double)w;
            int nw = 256;
            int nh = 256;
            BufferedImage scaled = new BufferedImage(nw, nh, 2);
            Graphics2D g = scaled.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.drawImage(img, 0, 0, nw, nh, null);
            }
            finally {
                g.dispose();
            }
            img = scaled;
            w = nw;
            h = nh;
        }
        DynamicTexture tex = new DynamicTexture(w, h, false);
        tex.setFilter(true, true);
        NativeImage pixels = tex.getPixels();
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                int argb = img.getRGB(x, y);
                int abgr = argb & 0xFF00FF00 | (argb & 0xFF0000) >> 16 | (argb & 0xFF) << 16;
                pixels.setPixelRGBA(x, y, abgr);
            }
        }
        tex.upload();
        return tex;
    }

    private void triggerLoadCloudThumb(CloudBuilding b) {
        if (b == null || b.id == null) {
            return;
        }
        PrefabCustomAddon.LOGGER.info("[CLOUD-THUMB] \u5c1d\u8bd5\u4e3a\u4e91\u7aef\u5efa\u7b51 {} (id={}) \u52a0\u8f7d\u7f29\u7565\u56fe", (Object)b.name, (Object)b.id);
        if (b.thumbnailPng != null && b.thumbnailPng.length > 0) {
            byte[] data = b.thumbnailPng;
            CompletableFuture.runAsync(() -> {
                try {
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
                    if (img == null) {
                        PrefabCustomAddon.LOGGER.warn("[CLOUD-THUMB] \u5d4c\u5165\u56fe\u89e3\u7801\u5931\u8d25, \u5c1d\u8bd5 LocalBuilding \u515c\u5e95");
                        this.loadCloudThumbFromLocalBuilding(b);
                        return;
                    }
                    int w = img.getWidth();
                    int h = img.getHeight();
                    if (w <= 0 || h <= 0) {
                        this.loadCloudThumbFromLocalBuilding(b);
                        return;
                    }
                    int ww = w;
                    int hh = h;
                    Minecraft.getInstance().execute(() -> this.uploadCloudThumb(b, img, ww, hh, "embedded"));
                }
                catch (Exception e) {
                    PrefabCustomAddon.LOGGER.warn("[CLOUD-THUMB] \u5d4c\u5165\u56fe\u8bfb\u53d6\u5931\u8d25, \u5c1d\u8bd5 LocalBuilding \u515c\u5e95: {}", (Object)e.toString());
                    this.loadCloudThumbFromLocalBuilding(b);
                }
            });
            return;
        }
        this.loadCloudThumbFromLocalBuilding(b);
    }

    private void loadCloudThumbFromLocalBuilding(CloudBuilding b) {
        CompletableFuture.runAsync(() -> {
            try {
                List<LocalBuilding> all = LocalBuildingScanner.scanAll();
                PrefabCustomAddon.LOGGER.info("[CLOUD-THUMB-FALLBACK] \u626b\u63cf\u5230 {} \u4e2a LocalBuilding, \u76ee\u6807 name='{}'", (Object)all.size(), (Object)b.name);
                LocalBuilding matched = null;
                for (LocalBuilding lb : all) {
                    if (lb.name == null || !lb.name.equals(b.name)) continue;
                    matched = lb;
                    break;
                }
                if (matched == null) {
                    PrefabCustomAddon.LOGGER.warn("[CLOUD-THUMB-FALLBACK] \u6ca1\u627e\u5230\u540c\u540d LocalBuilding (name='{}'), \u8d70\u9996\u5b57\u7b26\u5360\u4f4d\u7b26", (Object)b.name);
                    return;
                }
                if (matched.imagePath == null || !Files.exists(matched.imagePath, new LinkOption[0])) {
                    PrefabCustomAddon.LOGGER.warn("[CLOUD-THUMB-FALLBACK] \u5339\u914d\u5efa\u7b51\u6ca1\u56fe\u7247, \u8d70\u9996\u5b57\u7b26\u5360\u4f4d\u7b26");
                    return;
                }
                try (InputStream is = Files.newInputStream(matched.imagePath, new OpenOption[0]);){
                    BufferedImage img = ImageIO.read(is);
                    if (img == null) {
                        return;
                    }
                    int w = img.getWidth();
                    int h = img.getHeight();
                    if (w <= 0 || h <= 0) {
                        return;
                    }
                    int ww = w;
                    int hh = h;
                    Minecraft.getInstance().execute(() -> this.uploadCloudThumb(b, img, ww, hh, "fallback"));
                }
            }
            catch (Exception e) {
                PrefabCustomAddon.LOGGER.warn("[CLOUD-THUMB-FALLBACK] \u8bfb\u56fe\u5931\u8d25 {}", (Object)b.id, (Object)e);
            }
        });
    }

    private void uploadCloudThumb(CloudBuilding b, BufferedImage img, int ww, int hh, String source) {
        try {
            DynamicTexture tex = new DynamicTexture(ww, hh, false);
            tex.setFilter(false, false);
            NativeImage pixels = tex.getPixels();
            for (int y = 0; y < hh; ++y) {
                for (int x = 0; x < ww; ++x) {
                    int argb = img.getRGB(x, y);
                    int abgr = argb & 0xFF00FF00 | (argb & 0xFF0000) >> 16 | (argb & 0xFF) << 16;
                    pixels.setPixelRGBA(x, y, abgr);
                }
            }
            tex.upload();
            ResourceLocation loc = Minecraft.getInstance().getTextureManager().register("prefab_cloud_" + b.id, tex);
            this.cloudThumbCache.put(b.id, loc);
            PrefabCustomAddon.LOGGER.info("[CLOUD-THUMB] \u52a0\u8f7d\u6210\u529f (\u6765\u6e90={}, {}x{}) -> {}", new Object[]{source, ww, hh, loc});
        }
        catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("[CLOUD-THUMB] upload failed for {}", (Object)b.id, (Object)e);
        }
    }

    private void refreshWebsiteBuildings() {
        if (this.websiteBuildingsLoading) {
            return;
        }
        this.websiteBuildingsLoading = true;
        this.websiteBuildingsError = null;
        ((CompletableFuture)PackDownloadManager.getInstance().fetchBuildingListAsync().thenAccept(list -> Minecraft.getInstance().execute(() -> {
            this.websiteBuildings = list;
            this.websiteBuildingsLoading = false;
            this.websiteBuildingsError = null;
            for (PackDownloadManager.BuildingInfo2 b : list) {
                if (b == null || b.id == null || this.websiteImageCache.containsKey(b.id) || this.websiteImageLoading.contains(b.id)) continue;
                this.triggerLoadWebsiteImage(b);
            }
        }))).exceptionally(ex -> {
            Throwable t = (Throwable) ex;
            Minecraft.getInstance().execute(() -> {
                this.websiteBuildingsLoading = false;
                this.websiteBuildingsError = t.getCause() != null ? t.getCause().getMessage() : t.getMessage();
                PrefabCustomAddon.LOGGER.warn("[DOWNLOAD-TAB] fetch building list failed: {}", (Object)this.websiteBuildingsError);
            });
            return null;
        });
    }

    private void triggerLoadWebsiteImage(PackDownloadManager.BuildingInfo2 b) {
        if (b == null || b.id == null) {
            return;
        }
        this.websiteImageLoading.add(b.id);
        ((CompletableFuture)PackDownloadManager.getInstance().fetchBuildingImageAsync(b).thenAccept(data -> Minecraft.getInstance().execute(() -> {
            if (data == null || ((byte[])data).length == 0) {
                this.websiteImageLoading.remove(b.id);
                return;
            }
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream((byte[])data));
                if (img == null) {
                    this.websiteImageLoading.remove(b.id);
                    return;
                }
                int w = img.getWidth();
                int h = img.getHeight();
                if (w <= 0 || h <= 0) {
                    this.websiteImageLoading.remove(b.id);
                    return;
                }
                DynamicTexture tex = new DynamicTexture(w, h, false);
                tex.setFilter(false, false);
                NativeImage pixels = tex.getPixels();
                for (int y = 0; y < h; ++y) {
                    for (int x = 0; x < w; ++x) {
                        int argb = img.getRGB(x, y);
                        int abgr = argb & 0xFF00FF00 | (argb & 0xFF0000) >> 16 | (argb & 0xFF) << 16;
                        pixels.setPixelRGBA(x, y, abgr);
                    }
                }
                tex.upload();
                ResourceLocation loc = Minecraft.getInstance().getTextureManager().register("prefab_web_" + b.id, tex);
                this.websiteImageCache.put(b.id, loc);
                this.websiteImageLoading.remove(b.id);
            }
            catch (Exception e) {
                PrefabCustomAddon.LOGGER.warn("[DOWNLOAD-TAB] website image upload failed for {}", (Object)b.id, (Object)e);
                this.websiteImageLoading.remove(b.id);
            }
        }))).exceptionally(ex -> {
            this.websiteImageLoading.remove(b.id);
            return null;
        });
    }

    private void startWebsiteDownload(final PackDownloadManager.BuildingInfo2 b) {
        if (b == null || b.id == null) {
            return;
        }
        if (this.websiteDownloading.contains(b.id)) {
            return;
        }
        this.websiteDownloading.add(b.id);
        this.websiteDownloadProgress.put(b.id, new double[]{0.0, 0.0, 0.0});
        PackDownloadManager.getInstance().downloadBuildingStreaming(b, new PackDownloadManager.ProgressCallback(){

            @Override
            public void onStart(String packId) {
            }

            @Override
            public void onProgress(long downloaded, long total, double percent) {
                Minecraft.getInstance().execute(() -> GuiExtensionPackBrowser.this.websiteDownloadProgress.put(b.id, new double[]{downloaded, total, percent}));
            }

            @Override
            public void onComplete(Path savedTo) {
                Minecraft.getInstance().execute(() -> {
                    GuiExtensionPackBrowser.this.websiteDownloading.remove(b.id);
                    GuiExtensionPackBrowser.this.websiteDownloadProgress.remove(b.id);
                    GuiExtensionPackBrowser.this.refreshDownloadedBuildings();
                    GuiExtensionPackBrowser.this.setStatus(GuiExtensionPackBrowser.tr("browser.status.downloaded", b.name), 0x55FF55);
                });
            }

            @Override
            public void onError(String error) {
                Minecraft.getInstance().execute(() -> {
                    GuiExtensionPackBrowser.this.websiteDownloading.remove(b.id);
                    GuiExtensionPackBrowser.this.websiteDownloadProgress.remove(b.id);
                    GuiExtensionPackBrowser.this.setStatus(GuiExtensionPackBrowser.tr("browser.status.download_failed", error), 0xFF5555);
                });
            }
        });
    }

    private void openWebsiteHome() {
        String url = AddonConfig.getServerUrl();
        if (url == null || url.isEmpty()) {
            this.setStatus(GuiExtensionPackBrowser.tr("browser.status.no_server_url", new Object[0]), 0xFF5555);
            return;
        }
        try {
            Util.getPlatform().openUri(URI.create(url));
            this.setStatus(GuiExtensionPackBrowser.tr("browser.status.opened_browser", new Object[0]), 0x55AAFF);
        }
        catch (Exception e) {
            this.setStatus(GuiExtensionPackBrowser.tr("browser.status.open_failed", e.getMessage()), 0xFF5555);
        }
    }

    private boolean isWebsiteBuildingDownloaded(PackDownloadManager.BuildingInfo2 b) {
        if (b == null) {
            return false;
        }
        String baseName = b.name == null || b.name.isEmpty() ? b.id : b.name;
        baseName = baseName.replaceAll("[\\\\/:*?\"<>|]", "_");
        String ext = b.fileExt == null || b.fileExt.isEmpty() ? ".nbt" : b.fileExt;
        Path dir = PackDownloadManager.getDownloadRoot();
        if (!Files.exists(dir, new LinkOption[0])) {
            return false;
        }
        if (Files.exists(dir.resolve(baseName + ext), new LinkOption[0])) {
            return true;
        }
        for (int i = 2; i < 100; ++i) {
            if (!Files.exists(dir.resolve(baseName + "_" + i + ext), new LinkOption[0])) continue;
            return true;
        }
        return false;
    }

    public GuiExtensionPackBrowser() {
        super("Extension Pack Browser");
        this.currentCategory = rememberedCategory;
        this.scrollOffsetCards = rememberedPage;
        this.depCheckMissing = new HashMap<String, List<String>>();
        this.coverTextureCache = new HashMap<String, ResourceLocation>();
        this.previewTextureCache = new HashMap<String, ResourceLocation>();
        this.downloadedBuildings = new ArrayList<LocalBuilding>();
        this.localImageCache = new HashMap<String, ResourceLocation>();
        this.localImageLoading = new HashSet<String>();
        this.serverImageCache = new HashMap<String, ResourceLocation>();
        this.serverCardSyncBtnRects = new HashMap<String, int[]>();
        this.serverImageLoading = new HashSet<String>();
        this.websiteBuildings = new ArrayList<PackDownloadManager.BuildingInfo2>();
        this.websiteImageCache = new HashMap<String, ResourceLocation>();
        this.websiteImageLoading = new HashSet<String>();
        this.websiteDownloading = new HashSet<String>();
        this.websiteDownloadProgress = new HashMap<String, double[]>();
        this.websiteCardHitRects = new LinkedHashMap<String, int[]>();
        this.websiteCardDownloadBtnRects = new LinkedHashMap<String, int[]>();
        this.cloudCardHitRects = new LinkedHashMap<String, int[]>();
        this.cloudCardRecallBtnRects = new LinkedHashMap<String, int[]>();
        this.cloudCardSummonBtnRects = new LinkedHashMap<String, int[]>();
        this.cloudCardDeleteBtnRects = new LinkedHashMap<String, int[]>();
        this.cloudCardNavigateBtnRects = new LinkedHashMap<String, int[]>();
        this.navigateDebugLogged = new HashSet<String>();
        this.cloudThumbCache = new HashMap<String, ResourceLocation>();
        this.cloudThumbLoading = new HashSet<String>();
        this.serverFilterTabs = new int[3][4];
        this.serverCardHitRects = new int[40][5];
        this.cachedThumbTextureCache = new HashMap<String, ResourceLocation>();
        ExtensionPackManager.getInstance().reloadIfChanged();
        ExtensionPackManager.getInstance().forceReload();
        PrefabCustomAddon.LOGGER.info("[DIAG-OPEN-GUI] \u8fdb\u5efa\u7b51\u6d4f\u89c8\u5668 GUI, \u5f53\u524d packs \u6570\u91cf: {}", (Object)ExtensionPackManager.getInstance().getPacks().size());
    }

    public static void open() {
        Minecraft.getInstance().setScreen((Screen)new GuiExtensionPackBrowser());
    }

    protected void Initialize() {
        this.categoryPanelHidden = rememberedPanelHidden;
        this.currentCategory = rememberedCategory;
        this.scrollOffsetCards = rememberedPage;
        super.Initialize();
        this.modifiedInitialXAxis = 200;
        this.modifiedInitialYAxis = 120;
        this.imagePanelWidth = 400;
        this.imagePanelHeight = 240;
        this.shownImageHeight = 1;
        this.shownImageWidth = 1;
        int[] pos = this.computePanelPos();
        int grayBoxX = pos[0];
        int grayBoxY = pos[1];
        int tbY = grayBoxY + 1;
        int tbH = 18;
        this.btnBack = this.createAndAddButton(grayBoxX + 2, tbY, 28, tbH, "\u2190");
        this.btnBack.visible = false;
        int sbX = grayBoxX + 72 + 6;
        int sbY = grayBoxY + 4;
        int initialW = this.isBuildingsTab() ? 110 : 316;
        this.searchBox = new EditBox(this.font, sbX, sbY, initialW, 16, (Component)Component.literal((String)GuiExtensionPackBrowser.tr("browser.search.placeholder", new Object[0])));
        this.searchBox.setMaxLength(64);
        this.searchBox.setBordered(true);
        this.searchBox.setVisible(false);
        this.searchBox.setResponder(text -> {
            this.searchText = text;
            this.scrollOffsetCards = 0;
        });
        this.addRenderableWidget(this.searchBox);
        this.refreshDownloadedBuildings();
        PrefabCustomAddon.LOGGER.info("[BROWSER-NEW] Initialize: panel {}x{} at ({},{}), screen={}x{}, tabs={}", new Object[]{400, 240, grayBoxX, grayBoxY, this.width, this.height, 72});
    }

    public void tick() {
        super.tick();
        if (this.statusTick > 0) {
            --this.statusTick;
        }
        if (ThumbnailCache.pollCompleted()) {
            this.cachedThumbTextureCache.clear();
        }
    }

    private int[] computePanelPos() {
        int x = this.width / 2 - this.modifiedInitialXAxis;
        int y = this.height / 2 - this.modifiedInitialYAxis;
        if (x < 4) {
            x = 4;
        }
        if (y < 4) {
            y = 4;
        }
        if (x + 400 > this.width - 4) {
            x = Math.max(4, this.width - 400 - 4);
        }
        if (y + 240 > this.height - 4) {
            y = Math.max(4, this.height - 240 - 4);
        }
        return new int[]{x, y};
    }

    protected void preButtonRender(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, float partialTicks) {
        int[] pos = this.computePanelPos();
        int grayBoxX = pos[0];
        int grayBoxY = pos[1];
        guiGraphics.fill(grayBoxX, grayBoxY, grayBoxX + 400, grayBoxY + 240, -15066598);
        guiGraphics.fill(grayBoxX, grayBoxY, grayBoxX + 400, grayBoxY + 1, -11184811);
        guiGraphics.fill(grayBoxX, grayBoxY + 240 - 1, grayBoxX + 400, grayBoxY + 240, -11184811);
        guiGraphics.fill(grayBoxX, grayBoxY, grayBoxX + 1, grayBoxY + 240, -11184811);
        guiGraphics.fill(grayBoxX + 400 - 1, grayBoxY, grayBoxX + 400, grayBoxY + 240, -11184811);
        this.drawControlLeftPanel(guiGraphics, grayBoxX + 4, grayBoxY + 2, 72, 236);
        this.drawControlLeftPanel(guiGraphics, grayBoxX + 72 + 4, grayBoxY + 2, 320, 236);
    }

    protected void postButtonRender(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, float partialTicks) {
        int[] pos = this.computePanelPos();
        int grayBoxX = pos[0];
        int grayBoxY = pos[1];
        this.drawTabs(guiGraphics, grayBoxX, grayBoxY, mouseX, mouseY);
        this.drawContent(guiGraphics, grayBoxX, grayBoxY, mouseX, mouseY);
        if (this.statusMessage != null && this.statusTick > 0) {
            int statusY = grayBoxY + 240 - 14;
            int sw = this.font.width(this.statusMessage);
            int statusX = grayBoxX + (400 - sw) / 2;
            guiGraphics.fill(statusX - 6, statusY - 2, statusX + sw + 6, statusY + 12, -1073741824);
            guiGraphics.drawString(this.font, this.statusMessage, statusX, statusY, this.statusColor);
        }
    }

    private int getTabY(int index) {
        int[] pos = this.computePanelPos();
        return pos[1] + 4 + index * 22;
    }

    private int getTabX() {
        int[] pos = this.computePanelPos();
        return pos[0] + 4;
    }

    private void drawTabs(GuiGraphics guiGraphics, int grayBoxX, int grayBoxY, int mouseX, int mouseY) {
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; ++i) {
            int textColor;
            int bg;
            boolean hovered;
            Tab t = tabs[i];
            int ty = this.getTabY(i);
            int tx = this.getTabX();
            int tw = 68;
            int th = 22;
            boolean active = i == this.currentTab.ordinal() && this.currentDrilldownPack == null;
            boolean bl = hovered = mouseX >= tx && mouseX <= tx + tw && mouseY >= ty && mouseY <= ty + th;
            if (active) {
                bg = -11898971;
                textColor = 0xFFFFFF;
            } else if (hovered) {
                bg = -12961222;
                textColor = 0xFFFFFF;
            } else {
                bg = -14013910;
                textColor = 0xCCCCCC;
            }
            guiGraphics.fill(tx, ty, tx + tw, ty + th, bg);
            if (active) {
                guiGraphics.fill(tx, ty, tx + 3, ty + th, -11162881);
            }
            guiGraphics.drawString(this.font, t.label, tx + 8, ty + 7, textColor);
        }
    }

    private Tab tabHitTest(int mouseX, int mouseY) {
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; ++i) {
            int ty = this.getTabY(i);
            int tx = this.getTabX();
            int tw = 68;
            int th = 22;
            if (mouseX < tx || mouseX > tx + tw || mouseY < ty || mouseY > ty + th) continue;
            return tabs[i];
        }
        return null;
    }

    private int[] getContentRect(int grayBoxX, int grayBoxY) {
        int cx = grayBoxX + 72 + 6;
        int cy = grayBoxY + 4;
        int cw = 316;
        boolean needSearch = this.isBuildingsTab() || this.currentTab == Tab.FAVORITES;
        int ch = 232;
        if (needSearch) {
            cy += 18;
            ch -= 18;
        }
        return new int[]{cx, cy, cw, ch};
    }

    private int[] getBuildingsCardRect(int grayBoxX, int grayBoxY) {
        int[] base = this.getContentRect(grayBoxX, grayBoxY);
        if (this.categoryPanelHidden) {
            return new int[]{base[0], base[1] + 2, base[2] - 18, base[3] - 2};
        }
        return new int[]{base[0], base[1] + 2, base[2] - 110 - 4, base[3] - 2};
    }

    private void drawContent(GuiGraphics guiGraphics, int grayBoxX, int grayBoxY, int mouseX, int mouseY) {
        boolean needSearch = this.isBuildingsTab() || this.currentTab == Tab.FAVORITES;
        this.searchBox.setVisible(needSearch);
        if (needSearch) {
            String hint;
            String string = hint = this.currentTab == Tab.FAVORITES ? GuiExtensionPackBrowser.tr("browser.search.placeholder_favorites", new Object[0]) : GuiExtensionPackBrowser.tr("browser.search.placeholder_buildings", new Object[0]);
            if (this.searchBox.getValue().isEmpty()) {
                int sbX = this.searchBox.getX();
                int sbY = this.searchBox.getY();
                guiGraphics.drawString(this.font, hint, sbX + 4, sbY + 4, -7829368);
            }
        }
        if (this.currentDrilldownPack != null) {
            this.drawPackDrilldown(guiGraphics, grayBoxX, grayBoxY, mouseX, mouseY);
            return;
        }
        switch (this.currentTab.ordinal()) {
            case 0: {
                this.drawTabBuildings(guiGraphics, grayBoxX, grayBoxY, mouseX, mouseY);
                break;
            }
            case 1: {
                this.drawTabCloud(guiGraphics, grayBoxX, grayBoxY, mouseX, mouseY);
                break;
            }
            case 2: {
                this.drawTabServers(guiGraphics, grayBoxX, grayBoxY, mouseX, mouseY);
                break;
            }
            case 3: {
                this.drawTabFavorites(guiGraphics, grayBoxX, grayBoxY, mouseX, mouseY);
                break;
            }
            case 4: {
                this.drawTabDownload(guiGraphics, grayBoxX, grayBoxY, mouseX, mouseY);
                break;
            }
            case 5: {
                this.drawTabChangelog(guiGraphics, grayBoxX, grayBoxY, mouseX, mouseY);
            }
        }
        if (this.currentTab == Tab.CLOUD && this.pendingDeleteBuildingId != null) {
            this.drawDeleteConfirmOverlay(guiGraphics, grayBoxX, grayBoxY, mouseX, mouseY);
        }
    }

    private void drawTabChangelog(GuiGraphics guiGraphics, int grayBoxX, int grayBoxY, int mouseX, int mouseY) {
        int[] r = this.getContentRect(grayBoxX, grayBoxY);
        int rx = r[0];
        int ry = r[1];
        int rw = r[2];
        guiGraphics.drawString(this.font, "\u66f4\u65b0\u65e5\u5fd7", rx + 4, ry + 4, 0x55AAFF);
        guiGraphics.fill(rx + 4, ry + 17, rx + rw - 4, ry + 18, -12303292);
        String[] lines = new String[]{"V2.4.0更新日志", "修复已知bug", "新增下载建筑软件", "终端界面优化（分页/依赖检测）"};
        int lineY = ry + 24;
        for (int i = 0; i < lines.length; ++i) {
            // 内容区是浅灰底, 用深色字才看得清 (原来白/浅灰字在浅底上几乎不可见)
            int color = i == 0 ? 0x3F3F3F : 0x505050;
            guiGraphics.drawString(this.font, lines[i], rx + 6, lineY, color);
            lineY += 14;
        }
    }

    private void drawDeleteConfirmOverlay(GuiGraphics guiGraphics, int grayBoxX, int grayBoxY, int mouseX, int mouseY) {
        CloudBuilding b = CloudBuildingClientCache.getInstance().getById(this.pendingDeleteBuildingId);
        String name = b != null ? b.name : "?";
        guiGraphics.fill(0, 0, this.width, this.height, -1073741824);
        int overlayW = 220;
        int overlayH = 90;
        int overlayX = (this.width - overlayW) / 2;
        int overlayY = (this.height - overlayH) / 2;
        guiGraphics.fill(overlayX, overlayY, overlayX + overlayW, overlayY + overlayH, -300279270);
        guiGraphics.fill(overlayX, overlayY, overlayX + overlayW, overlayY + 1, -5622989);
        guiGraphics.fill(overlayX, overlayY + overlayH - 1, overlayX + overlayW, overlayY + overlayH, -11184811);
        guiGraphics.fill(overlayX, overlayY, overlayX + 1, overlayY + overlayH, -11184811);
        guiGraphics.fill(overlayX + overlayW - 1, overlayY, overlayX + overlayW, overlayY + overlayH, -11184811);
        guiGraphics.drawCenteredString(this.font, GuiExtensionPackBrowser.tr("delete.title", new Object[0]), overlayX + overlayW / 2, overlayY + 8, 0xFF5555);
        String nameLine = "\u00a7f" + GuiExtensionPackBrowser.truncate(name, 28);
        guiGraphics.drawCenteredString(this.font, nameLine, overlayX + overlayW / 2, overlayY + 24, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font, GuiExtensionPackBrowser.tr("delete.body", new Object[0]), overlayX + overlayW / 2, overlayY + 40, 0xAAAAAA);
        int btnW = 80;
        int btnH = 18;
        int btnGap = 16;
        int btnY = overlayY + overlayH - btnH - 10;
        int cancelX = overlayX + overlayW / 2 - btnGap / 2 - btnW;
        int okX = overlayX + overlayW / 2 + btnGap / 2;
        boolean cancelHover = mouseX >= cancelX && mouseX <= cancelX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
        guiGraphics.fill(cancelX, btnY, cancelX + btnW, btnY + btnH, cancelHover ? -10061910 : -12298889);
        guiGraphics.drawCenteredString(this.font, GuiExtensionPackBrowser.tr("delete.cancel", new Object[0]), cancelX + btnW / 2, btnY + 5, 0xFFFFFF);
        this.deleteConfirmCancelRect = new int[]{cancelX, btnY, btnW, btnH};
        boolean okHover = mouseX >= okX && mouseX <= okX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
        guiGraphics.fill(okX, btnY, okX + btnW, btnY + btnH, okHover ? -3394765 : -5627358);
        guiGraphics.drawCenteredString(this.font, GuiExtensionPackBrowser.tr("delete.confirm", new Object[0]), okX + btnW / 2, btnY + 5, 0xFFFFFF);
        this.deleteConfirmOkRect = new int[]{okX, btnY, btnW, btnH};
    }

    private void drawTabBuildings(GuiGraphics guiGraphics, int grayBoxX, int grayBoxY, int mouseX, int mouseY) {
        this.drawCategoryList(guiGraphics, grayBoxX, grayBoxY, mouseX, mouseY);
        List<ConstructionInfo> all = this.getMergedConstructionsForBuildingsTab();
        List<ConstructionInfo> byCat = this.filterByCategory(all, this.currentCategory);
        List<ConstructionInfo> filtered = this.filterBySearch(byCat, this.searchText);
        this.drawConstructionCardsForBuildings(guiGraphics, grayBoxX, grayBoxY, mouseX, mouseY, filtered);
    }

    private List<ConstructionInfo> filterByCategory(List<ConstructionInfo> src, String cat) {
        if (cat == null) {
            return src;
        }
        ArrayList<ConstructionInfo> out = new ArrayList<ConstructionInfo>();
        for (ConstructionInfo c : src) {
            if (!cat.equals(c.getCategoryOrDefault())) continue;
            out.add(c);
        }
        return out;
    }

    private void drawCategoryList(GuiGraphics guiGraphics, int grayBoxX, int grayBoxY, int mouseX, int mouseY) {
        this.categoryItemRects.clear();
        this.categoryAddBtnRect = null;
        this.categoryToggleBtnRect = null;
        int[] pos = this.computePanelPos();
        int panelX = pos[0];
        int panelY = pos[1];
        int listX = panelX + 400 - 110 - 4;
        int listY = panelY + 4;
        int listW = 110;
        int listH = 232;
        if (this.categoryPanelHidden) {
            int textX;
            int btnW = 28;
            int btnH = 12;
            int btnX = panelX + 400 - btnW - 6;
            int btnY = listY + 18;
            boolean hover = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
            guiGraphics.fill(btnX, btnY, btnX + btnW, btnY + btnH, hover ? -10061910 : -12298889);
            guiGraphics.drawCenteredString(this.font, "\u25b6 \u5c55\u5f00", btnX + btnW / 2, btnY + (btnH - 8) / 2, -1);
            this.categoryToggleBtnRect = new int[]{btnX, btnY, btnW, btnH};
            Object catDisplay = this.currentCategory == null ? GuiExtensionPackBrowser.tr("browser.category.all", new Object[0]) : this.currentCategory;
            int maxTextW = 114;
            if (this.font.width((String)catDisplay) > maxTextW) {
                while (((String)catDisplay).length() > 1 && this.font.width((String)catDisplay + "..") > maxTextW) {
                    catDisplay = ((String)catDisplay).substring(0, ((String)catDisplay).length() - 1);
                }
                catDisplay = (String)catDisplay + "..";
            }
            if ((textX = listX + listW - this.font.width((String)catDisplay)) < listX) {
                textX = listX;
            }
            int textY = listY + 4;
            guiGraphics.drawString(this.font, "\u00a77" + (String)catDisplay, textX, textY, -3355444);
            return;
        }
        guiGraphics.fill(listX, listY, listX + listW, listY + listH, -14737633);
        guiGraphics.fill(listX, listY, listX + listW, listY + 1, -11184811);
        guiGraphics.fill(listX, listY + listH - 1, listX + listW, listY + listH, -11184811);
        guiGraphics.fill(listX, listY, listX + 1, listY + listH, -11184811);
        guiGraphics.fill(listX + listW - 1, listY, listX + listW, listY + listH, -11184811);
        guiGraphics.drawString(this.font, "\u00a7l" + GuiExtensionPackBrowser.tr("browser.category.title", new Object[0]), listX + 4, listY + 4, -1);
        int tgW = 20;
        int tgH = 10;
        int tgX = listX + listW - tgW - 2;
        int tgY = listY + 3;
        boolean tgHover = mouseX >= tgX && mouseX <= tgX + tgW && mouseY >= tgY && mouseY <= tgY + tgH;
        guiGraphics.fill(tgX, tgY, tgX + tgW, tgY + tgH, tgHover ? -10061910 : -12298889);
        guiGraphics.drawCenteredString(this.font, "\u25c0 \u6536\u8d77", tgX + tgW / 2, tgY + (tgH - 8) / 2 + 1, -1);
        this.categoryToggleBtnRect = new int[]{tgX, tgY, tgW, tgH};
        int itemY = listY + 16;
        int itemH = 14;
        int padX = 4;
        this.drawCategoryItem(guiGraphics, listX, itemY, listW, itemH, GuiExtensionPackBrowser.tr("browser.category.all", new Object[0]), this.currentCategory == null, this.categoryItemRects.size());
        this.categoryItemRects.add(new int[]{listX + padX, itemY, listW - padX * 2, itemH});
        this.drawCategoryItem(guiGraphics, listX, itemY += itemH, listW, itemH, "\u672a\u5206\u7c7b", "\u672a\u5206\u7c7b".equals(this.currentCategory), this.categoryItemRects.size());
        this.categoryItemRects.add(new int[]{listX + padX, itemY, listW - padX * 2, itemH});
        itemY += itemH;
        List<String> all = CategoryManager.get().getCategories();
        for (int i = 1; i < all.size(); ++i) {
            String name = all.get(i);
            this.drawCategoryItem(guiGraphics, listX, itemY, listW, itemH, name, name.equals(this.currentCategory), this.categoryItemRects.size());
            this.categoryItemRects.add(new int[]{listX + padX, itemY, listW - padX * 2, itemH});
            if ((itemY += itemH) + itemH > listY + listH - 20) break;
        }
        int btnY = listY + listH - 18;
        int btnH = 14;
        int btnW = listW - 8;
        int btnX = listX + 4;
        boolean btnHover = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
        guiGraphics.fill(btnX, btnY, btnX + btnW, btnY + btnH, btnHover ? -10061910 : -12298889);
        guiGraphics.drawCenteredString(this.font, "+ " + GuiExtensionPackBrowser.tr("browser.category.manage", new Object[0]), btnX + btnW / 2, btnY + 3, -1);
        this.categoryAddBtnRect = new int[]{btnX, btnY, btnW, btnH};
    }

    private void drawCategoryItem(GuiGraphics guiGraphics, int x, int y, int w, int h, String label, boolean selected, int idx) {
        int bg = selected ? -12948822 : -15066598;
        int padX = 4;
        guiGraphics.fill(x + padX, y, x + w - padX, y + h, bg);
        if (selected) {
            guiGraphics.fill(x + padX, y, x + padX + 2, y + h, -11162881);
        }
        int maxW = w - padX * 2 - 4;
        Object display = label;
        if (this.font.width((String)display) > maxW) {
            while (((String)display).length() > 1 && this.font.width((String)display + "..") > maxW) {
                display = ((String)display).substring(0, ((String)display).length() - 1);
            }
            display = (String)display + "..";
        }
        int textColor = selected ? -1 : -3355444;
        guiGraphics.drawString(this.font, (String)display, x + padX + 4, y + 3, textColor);
    }

    private void drawConstructionCardsForBuildings(GuiGraphics guiGraphics, int grayBoxX, int grayBoxY, int mouseX, int mouseY, List<ConstructionInfo> list) {
        int[] rect = this.getBuildingsCardRect(grayBoxX, grayBoxY);
        int rx = rect[0];
        int ry = rect[1];
        int rw = rect[2];
        int rh = rect[3];
        if (list.isEmpty()) {
            String hint;
            String title;
            if (this.currentCategory != null) {
                title = GuiExtensionPackBrowser.tr("browser.category.empty_in_category", this.currentCategory);
                hint = GuiExtensionPackBrowser.tr("browser.category.empty_in_category_hint", new Object[0]);
            } else if (this.searchText != null && !this.searchText.isEmpty()) {
                title = GuiExtensionPackBrowser.tr("browser.empty.no_match", new Object[0]);
                hint = GuiExtensionPackBrowser.tr("browser.empty.try_clear_search", new Object[0]);
            } else {
                title = GuiExtensionPackBrowser.tr("browser.empty.no_buildings", new Object[0]);
                hint = GuiExtensionPackBrowser.tr("browser.empty.no_buildings_hint", new Object[0]);
            }
            this.drawEmpty(guiGraphics, rect, title, hint);
            return;
        }
        int cols = 2;
        int rows = 2;
        int pageSize = cols * rows;
        int pageCount = Math.max(1, (list.size() + pageSize - 1) / pageSize);
        int page = Math.max(0, Math.min(this.scrollOffsetCards, pageCount - 1));
        int start = page * pageSize;
        int end = Math.min(start + pageSize, list.size());
        int gridW = cols * 92 + (cols - 1) * 6;
        int gridX = rx + (rw - gridW) / 2;
        int gridY = ry + 2;
        for (int i = 0; i < end - start; ++i) {
            int row = i / cols;
            int col = i % cols;
            int cx = gridX + col * 98;
            int cy = gridY + row * 86;
            ConstructionInfo c = list.get(start + i);
            this.drawConstructionCard(guiGraphics, cx, cy, c, mouseX, mouseY);
        }
        if (pageCount > 1) {
            this.drawPaginationBar(guiGraphics, rx, ry + rh - 12, rw, page, pageCount, mouseX, mouseY);
        }
    }

    private List<ConstructionInfo> getMergedConstructionsForBuildingsTab() {
        ArrayList<ConstructionInfo> raw = new ArrayList<ConstructionInfo>(ExtensionPackManager.getInstance().getAllConstructionsForGui());
        int dropped = 0;
        ArrayList<ConstructionInfo> all = new ArrayList<ConstructionInfo>(raw.size());
        for (ConstructionInfo constructionInfo : raw) {
            if (constructionInfo.getPack() != null && constructionInfo.getPack().isServerBacked()) {
                ++dropped;
                continue;
            }
            all.add(constructionInfo);
        }
        if (dropped > 0) {
            PrefabCustomAddon.LOGGER.info("[BUILDINGS-TAB] \u8fc7\u6ee4\u6389 server-cache \u526f\u672c {} \u4e2a, \u5269\u4f59 {} \u4e2a", (Object)dropped, (Object)all.size());
        }
        HashSet<String> existingIds = new HashSet<String>();
        for (ConstructionInfo constructionInfo : all) {
            existingIds.add(constructionInfo.getId());
        }
        List<LocalBuilding> list = LocalBuildingScanner.scanAll();
        PrefabCustomAddon.LOGGER.info("[BUILDINGS-TAB] ExtensionPack \u5efa\u7b51 {} \u4e2a, LocalBuildingScanner \u626b\u5230 {} \u4e2a, \u73b0\u6709 id \u96c6\u5408 {}", new Object[]{all.size(), list.size(), existingIds});
        for (LocalBuilding lb : list) {
            PrefabCustomAddon.LOGGER.info("[BUILDINGS-TAB]   LocalBuilding: id='{}' name='{}' file={}", new Object[]{lb.id, lb.name, lb.filePath});
        }
        for (LocalBuilding lb : list) {
            Object c;
            ConstructionInfo existing = null;
            for (ConstructionInfo constructionInfo : all) {
                if (!constructionInfo.getId().equals(lb.id)) continue;
                existing = constructionInfo;
                break;
            }
            if ((c = existing) == null) {
                c = new ConstructionInfo(lb.id);
                all.add((ConstructionInfo)c);
            }
            ((ConstructionInfo)c).setName(lb.name);
            ((ConstructionInfo)c).setAuthor(lb.author);
            ((ConstructionInfo)c).setDescription(lb.description);
            ((ConstructionInfo)c).setDependencies(lb.dependencies);
            ((ConstructionInfo)c).setCategory(lb.category);
            if (lb.fileExt != null && !lb.fileExt.isEmpty()) {
                ((ConstructionInfo)c).setFormat(lb.fileExt.startsWith(".") ? lb.fileExt.substring(1) : lb.fileExt);
            }
            if (lb.imagePath != null && Files.exists(lb.imagePath, new LinkOption[0])) {
                ((ConstructionInfo)c).setLocalImagePath(lb.imagePath);
            }
            if (lb.filePath != null) {
                ((ConstructionInfo)c).setLocalNbtPath(lb.filePath);
            }
            PrefabCustomAddon.LOGGER.info("[BUILDINGS-TAB]   \u5408\u5e76 id='{}' (lb.dependencies={} \u2192 c.dependencies={})", new Object[]{lb.id, lb.dependencies, ((ConstructionInfo)c).getDependencies()});
        }
        PlayerPreferences playerPreferences = PlayerPreferences.get();
        ArrayList<ConstructionInfo> favorited = new ArrayList<ConstructionInfo>();
        ArrayList<ConstructionInfo> rest = new ArrayList<ConstructionInfo>();
        for (ConstructionInfo constructionInfo : all) {
            String pkg;
            String string = pkg = constructionInfo.getPack() == null ? null : constructionInfo.getPack().getPackageName();
            if (playerPreferences.isFavorite(pkg, constructionInfo.getId())) {
                favorited.add(constructionInfo);
                continue;
            }
            rest.add(constructionInfo);
        }
        ArrayList<ConstructionInfo> merged = new ArrayList<ConstructionInfo>(all.size());
        merged.addAll(favorited);
        merged.addAll(rest);
        PrefabCustomAddon.LOGGER.info("[BUILDINGS-TAB] \u6536\u85cf\u4f18\u5148\u6392\u5e8f: \u6536\u85cf {} \u4e2a, \u975e\u6536\u85cf {} \u4e2a, \u5408\u5e76\u540e {} \u4e2a", new Object[]{favorited.size(), rest.size(), merged.size()});
        return merged;
    }

    private void drawTabServers(GuiGraphics guiGraphics, int grayBoxX, int grayBoxY, int mouseX, int mouseY) {
        int statusColor;
        String statusText;
        int[] r = this.getContentRect(grayBoxX, grayBoxY);
        int rx = r[0];
        int ry = r[1];
        int rw = r[2];
        int rh = r[3];
        this.serverFilterTabs = new int[3][4];
        if (Minecraft.getInstance().getCurrentServer() == null) {
            this.drawEmpty(guiGraphics, new int[]{rx, ry, rw, rh}, GuiExtensionPackBrowser.tr("server.only_multiplayer.title", new Object[0]), GuiExtensionPackBrowser.tr("server.only_multiplayer.hint", new Object[0]));
            this.lastSyncServerBtn = null;
            return;
        }
        ServerPackSyncClient sync = ServerPackSyncClient.getInstance();
        ServerPackSyncClient.State sState = sync.getState();
        String sMsg = sync.getStatusMessage();
        if (sState == ServerPackSyncClient.State.DOWNLOADING || sState == ServerPackSyncClient.State.REQUESTING || sState == ServerPackSyncClient.State.CHECKING || sState == ServerPackSyncClient.State.FINALIZING) {
            statusText = sMsg == null || sMsg.isEmpty() ? GuiExtensionPackBrowser.tr("server.status.syncing", new Object[0]) : sMsg;
            statusColor = 0x55AAFF;
        } else if (sState == ServerPackSyncClient.State.DONE) {
            statusText = sMsg == null || sMsg.isEmpty() ? GuiExtensionPackBrowser.tr("server.status.done", new Object[0]) : sMsg;
            statusColor = 0x55FF55;
        } else if (sState == ServerPackSyncClient.State.ERROR) {
            statusText = sMsg == null || sMsg.isEmpty() ? GuiExtensionPackBrowser.tr("server.status.error", new Object[0]) : sMsg;
            statusColor = 0xFF5555;
        } else {
            statusText = GuiExtensionPackBrowser.tr("server.status.idle", new Object[0]);
            statusColor = 0xAAAAAA;
        }
        guiGraphics.drawString(this.font, statusText, rx + 4, ry + 4, statusColor);
        int sbW = 60;
        int sbH = 14;
        int sbX = rx + rw - sbW - 4;
        int sbY = ry + 2;
        boolean isSyncing = sync.isSyncing();
        if (isSyncing) {
            guiGraphics.fill(sbX, sbY, sbX + sbW, sbY + sbH, -11184811);
            guiGraphics.drawCenteredString(this.font, GuiExtensionPackBrowser.tr("server.button.syncing", new Object[0]), sbX + sbW / 2, sbY + 3, -3355444);
        } else {
            this.drawTextButton(guiGraphics, sbX, sbY, sbW, sbH, GuiExtensionPackBrowser.tr("server.button.sync", new Object[0]), mouseX, mouseY);
        }
        this.lastSyncServerBtn = new int[]{sbX, sbY, sbW, sbH};
        int filterY = ry + 22;
        int filterH = rh - 22;
        List<ServerBuildingInfo> all = ExtensionPackManager.getInstance().getServerBuildings();
        if (all.isEmpty()) {
            this.drawEmpty(guiGraphics, new int[]{rx, filterY, rw, filterH}, GuiExtensionPackBrowser.tr("server.empty.title", new Object[0]), GuiExtensionPackBrowser.tr("server.empty.hint", new Object[0]));
            return;
        }
        int allCount = all.size();
        int unsyncedCount = 0;
        for (ServerBuildingInfo b : all) {
            if (b.synced) continue;
            ++unsyncedCount;
        }
        int syncedCount = allCount - unsyncedCount;
        int tabW = (rw - 8) / 3;
        int tabH = 12;
        int tabY = filterY;
        for (int i = 0; i < 3; ++i) {
            int tx = rx + 4 + i * (tabW + 4);
            String[] labels = new String[]{GuiExtensionPackBrowser.tr("server.filter.all", String.valueOf(allCount)), GuiExtensionPackBrowser.tr("server.filter.unsynced", String.valueOf(unsyncedCount)), GuiExtensionPackBrowser.tr("server.filter.synced", String.valueOf(syncedCount))};
            String[] modes = new String[]{"all", "unsynced", "synced"};
            boolean active = modes[i].equals(this.serverFilter);
            int bg = active ? -10061910 : -13421756;
            guiGraphics.fill(tx, tabY, tx + tabW, tabY + tabH, bg);
            guiGraphics.fill(tx, tabY, tx + tabW, tabY + 1, active ? -7824948 : -11184794);
            guiGraphics.drawCenteredString(this.font, labels[i], tx + tabW / 2, tabY + 2, active ? -1 : -5592406);
            this.serverFilterTabs[i] = new int[]{tx, tabY, tabW, tabH};
        }
        ArrayList<ServerBuildingInfo> filtered = new ArrayList<ServerBuildingInfo>();
        for (ServerBuildingInfo b : all) {
            if ("unsynced".equals(this.serverFilter) && b.synced || "synced".equals(this.serverFilter) && !b.synced) continue;
            filtered.add(b);
        }
        if (filtered.isEmpty()) {
            String emptyHint;
            String emptyTitle;
            if ("unsynced".equals(this.serverFilter)) {
                emptyTitle = GuiExtensionPackBrowser.tr("server.filter.empty_synced", new Object[0]);
                emptyHint = GuiExtensionPackBrowser.tr("server.filter.empty_synced_hint", new Object[0]);
            } else if ("synced".equals(this.serverFilter)) {
                emptyTitle = GuiExtensionPackBrowser.tr("server.filter.empty_unsynced", new Object[0]);
                emptyHint = GuiExtensionPackBrowser.tr("server.filter.empty_unsynced_hint", new Object[0]);
            } else {
                emptyTitle = GuiExtensionPackBrowser.tr("msg.no_match", new Object[0]);
                emptyHint = GuiExtensionPackBrowser.tr("msg.try_filter", new Object[0]);
            }
            this.drawEmpty(guiGraphics, new int[]{rx, filterY + tabH + 4, rw, filterH - tabH - 4}, emptyTitle, emptyHint);
            return;
        }
        this.drawServerBuildingCards(guiGraphics, rx, filterY + tabH + 4, rw, filterH - tabH - 4, mouseX, mouseY, filtered);
    }

    private void drawServerBuildingCards(GuiGraphics guiGraphics, int rx, int ry, int rw, int rh, int mouseX, int mouseY, List<ServerBuildingInfo> list) {
        this.serverCardHitCount = 0;
        this.serverCardSyncBtnRects.clear();
        if (list.isEmpty()) {
            this.drawEmpty(guiGraphics, new int[]{rx, ry, rw, rh}, GuiExtensionPackBrowser.tr("msg.no_match", new Object[0]), GuiExtensionPackBrowser.tr("msg.try_filter", new Object[0]));
            return;
        }
        int cardW = (rw - 8 - 6) / 2;
        int cardH = 56;
        int cardGap = 6;
        int listH = rh - 14;
        int total = list.size();
        int rows = Math.max(1, (listH + cardGap) / (cardH + cardGap));
        int pageSize = rows * 2;
        int pageCount = Math.max(1, (total + pageSize - 1) / pageSize);
        int page = Math.max(0, Math.min(this.scrollOffsetCards, pageCount - 1));
        int start = page * pageSize;
        int end = Math.min(start + pageSize, total);
        for (int i = start; i < end; ++i) {
            int idx = i - start;
            int col = idx % 2;
            int row = idx / 2;
            int cx = rx + 4 + col * (cardW + cardGap);
            int cy = ry + 2 + row * (cardH + cardGap);
            this.drawServerBuildingCard(guiGraphics, cx, cy, cardW, cardH, list.get(i), mouseX, mouseY);
            if (this.serverCardHitCount >= this.serverCardHitRects.length) continue;
            this.serverCardHitRects[this.serverCardHitCount++] = new int[]{cx, cy, cardW, cardH, i};
        }
        if (pageCount > 1) {
            this.drawPaginationBar(guiGraphics, rx, ry + rh - 12, rw, page, pageCount, mouseX, mouseY);
        }
    }

    private void drawServerBuildingCard(GuiGraphics guiGraphics, int cx, int cy, int cw, int ch, ServerBuildingInfo b, int mouseX, int mouseY) {
        boolean btnHovered;
        boolean canShowThumb;
        boolean hovered = mouseX >= cx && mouseX <= cx + cw && mouseY >= cy && mouseY <= cy + ch;
        int bg = hovered ? -13816531 : -14737633;
        guiGraphics.fill(cx, cy, cx + cw, cy + ch, bg);
        int borderColor = b.synced ? -13730510 : -29696;
        guiGraphics.fill(cx, cy, cx + cw, cy + 1, borderColor);
        guiGraphics.fill(cx, cy + ch - 1, cx + cw, cy + ch, -11184811);
        guiGraphics.fill(cx, cy, cx + 1, cy + ch, -11184811);
        guiGraphics.fill(cx + cw - 1, cy, cx + cw, cy + ch, -11184811);
        int iconSize = ch >= 64 ? 36 : 28;
        int iconX = cx + 4;
        int iconY = cy + 4;
        boolean bl = canShowThumb = b.synced || b.pngData != null && b.pngData.length > 0;
        if (canShowThumb) {
            this.ensureServerBuildingImageLoaded(b);
            ResourceLocation tex = this.serverImageCache.get(b.buildingId);
            if (tex != null) {
                this.drawIconNearest(guiGraphics, tex, iconX, iconY, iconSize, iconSize, 0, 0, 256, 256, 256, 256);
            } else {
                guiGraphics.fill(iconX, iconY, iconX + iconSize, iconY + iconSize, -15066598);
                String initial = b.getDisplayName();
                initial = initial.isEmpty() ? "?" : initial.substring(0, 1);
                guiGraphics.drawCenteredString(this.font, initial, iconX + iconSize / 2, iconY + iconSize / 2 - 4, -7811960);
            }
        } else {
            guiGraphics.fill(iconX, iconY, iconX + iconSize, iconY + iconSize, -15066598);
            guiGraphics.drawCenteredString(this.font, "\u2193", iconX + iconSize / 2, iconY + iconSize / 2 - 8, -29696);
            String hint = GuiExtensionPackBrowser.tr("server.card.unsynced_hint", new Object[0]);
            guiGraphics.drawCenteredString(this.font, hint, iconX + iconSize / 2, iconY + iconSize / 2 + 4, -5601178);
        }
        int textX = iconX + iconSize + 5;
        int textW = cw - iconSize - 10;
        if (textW < 30) {
            textW = 30;
        }
        int nameY = ch >= 64 ? cy + 5 : cy + 2;
        int statusY = ch >= 64 ? cy + 17 : cy + 12;
        int metaY = ch >= 64 ? cy + 29 : cy + 22;
        Object name = b.getDisplayName();
        if (this.font.width((String)name) > textW) {
            while (this.font.width((String)name + "..") > textW && ((String)name).length() > 1) {
                name = ((String)name).substring(0, ((String)name).length() - 1);
            }
            name = (String)name + "..";
        }
        guiGraphics.drawString(this.font, (String)name, textX, nameY, -1);
        String statusLine = GuiExtensionPackBrowser.tr(b.synced ? "server.badge.synced" : "server.badge.unsynced", new Object[0]);
        int statusColor = b.synced ? -11141291 : -21931;
        guiGraphics.drawString(this.font, statusLine, textX, statusY, statusColor);
        String ext = b.getSourceExt();
        String meta = ext + " \u00b7 " + GuiExtensionPackBrowser.formatFileSize(b.size);
        guiGraphics.drawString(this.font, meta, textX, metaY, -7829368);
        int sepY = ch >= 64 ? cy + 44 : cy + 36;
        guiGraphics.fill(cx + 2, sepY, cx + cw - 2, sepY + 1, -12303292);
        int btnH = ch >= 64 ? 16 : 14;
        int btnX = cx + 5;
        int btnY = cy + ch - btnH - 3;
        int btnW = cw - 10;
        boolean bl2 = btnHovered = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
        if (b.synced) {
            int btnBg = btnHovered ? -12948934 : -869368526;
            guiGraphics.fill(btnX, btnY, btnX + btnW, btnY + btnH, btnBg);
            guiGraphics.drawCenteredString(this.font, GuiExtensionPackBrowser.tr("server.button.view", new Object[0]), btnX + btnW / 2, btnY + 4, -1);
        } else {
            int btnBg = btnHovered ? -21931 : -29696;
            guiGraphics.fill(btnX, btnY, btnX + btnW, btnY + btnH, btnBg);
            guiGraphics.drawCenteredString(this.font, GuiExtensionPackBrowser.tr("server.button.sync_one", new Object[0]), btnX + btnW / 2, btnY + 4, -1);
        }
        this.serverCardSyncBtnRects.put(b.buildingId, new int[]{btnX, btnY, btnW, btnH});
    }

    private void ensureServerBuildingImageLoaded(ServerBuildingInfo b) {
        boolean hasPngData;
        if (b == null || b.buildingId == null) {
            return;
        }
        String key = b.buildingId;
        if (this.serverImageCache.containsKey(key)) {
            return;
        }
        if (this.serverImageLoading.contains(key)) {
            return;
        }
        boolean bl = hasPngData = b.pngData != null && b.pngData.length > 0;
        if (!hasPngData && !b.synced) {
            return;
        }
        this.serverImageLoading.add(key);
        CompletableFuture.runAsync(() -> {
            try {
                byte[] data;
                block13: {
                    Path cacheDir;
                    block14: {
                        String srcFile;
                        block12: {
                            data = null;
                            if (b.pngData == null || b.pngData.length <= 0) break block12;
                            data = b.pngData;
                            break block13;
                        }
                        cacheDir = ExtensionPackManager.getInstance().getServerCacheDir();
                        if (cacheDir == null || !Files.exists(cacheDir, new LinkOption[0])) break block13;
                        String string = srcFile = b.sourceFileName == null ? "" : b.sourceFileName.toLowerCase();
                        if (!srcFile.endsWith(".zip")) break block14;
                        Path zipPath = GuiExtensionPackBrowser.findServerFile(cacheDir, b.packName == null ? b.buildingId : b.packName, ".zip");
                        if (zipPath == null) {
                            zipPath = GuiExtensionPackBrowser.findServerFile(cacheDir, b.buildingId, ".zip");
                        }
                        if (zipPath == null) break block13;
                        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath, new OpenOption[0]));){
                            ZipEntry ze;
                            while ((ze = zis.getNextEntry()) != null) {
                                int n;
                                if (!ze.getName().toLowerCase().endsWith(".png") || ze.isDirectory()) continue;
                                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                                byte[] buf = new byte[4096];
                                while ((n = zis.read(buf)) > 0) {
                                    bos.write(buf, 0, n);
                                }
                                data = bos.toByteArray();
                            }
                        }
                    }
                    for (String imgExt : new String[]{".png", ".jpg", ".jpeg", ".webp"}) {
                        Path imgPath = GuiExtensionPackBrowser.findServerFile(cacheDir, b.buildingId, imgExt);
                        if (imgPath == null) continue;
                        data = Files.readAllBytes(imgPath);
                        break;
                    }
                }
                if (data == null || data.length == 0) {
                    Minecraft.getInstance().execute(() -> {
                        this.serverImageCache.put(key, null);
                        this.serverImageLoading.remove(key);
                    });
                    return;
                }
                byte[] finalData = data;
                Minecraft.getInstance().execute(() -> {
                    try (ByteArrayInputStream is = new ByteArrayInputStream(finalData);){
                        BufferedImage img = ImageIO.read(is);
                        if (img == null) {
                            this.serverImageCache.put(key, null);
                        } else {
                            DynamicTexture tex = GuiExtensionPackBrowser.uploadIconTexture(img);
                            if (tex == null) {
                                this.serverImageCache.put(key, null);
                            } else {
                                ResourceLocation loc = Minecraft.getInstance().getTextureManager().register("prefab_server_" + key, tex);
                                this.serverImageCache.put(key, loc);
                            }
                        }
                    }
                    catch (Exception e) {
                        PrefabCustomAddon.LOGGER.warn("[SERVER-IMG] {} load failed: {}", (Object)key, (Object)e.toString());
                        this.serverImageCache.put(key, null);
                    }
                    finally {
                        this.serverImageLoading.remove(key);
                    }
                });
            }
            catch (Exception e) {
                PrefabCustomAddon.LOGGER.warn("[SERVER-IMG] {} read failed: {}", (Object)key, (Object)e.toString());
                Minecraft.getInstance().execute(() -> {
                    this.serverImageCache.put(key, null);
                    this.serverImageLoading.remove(key);
                });
            }
        });
    }

    private static Path findServerFile(Path dir, String baseName, String ext) {
        Path p = dir.resolve(baseName + ext);
        return Files.exists(p, new LinkOption[0]) ? p : null;
    }

    private int serverCardHitTest(int mouseX, int mouseY) {
        for (int i = 0; i < this.serverCardHitCount; ++i) {
            int[] r = this.serverCardHitRects[i];
            if (mouseX < r[0] || mouseX > r[0] + r[2] || mouseY < r[1] || mouseY > r[1] + r[3]) continue;
            return r[4];
        }
        return -1;
    }

    private void drawTabFavorites(GuiGraphics guiGraphics, int grayBoxX, int grayBoxY, int mouseX, int mouseY) {
        List<ConstructionInfo> favs = ExtensionPackManager.getInstance().getFavoriteConstructions();
        List<ConstructionInfo> filtered = this.filterBySearch(favs, this.searchText);
        if (favs.isEmpty()) {
            int[] r = this.getContentRect(grayBoxX, grayBoxY);
            this.drawEmpty(guiGraphics, r, GuiExtensionPackBrowser.tr("favorites.empty.title", new Object[0]), GuiExtensionPackBrowser.tr("favorites.empty.hint", new Object[0]));
            return;
        }
        this.drawConstructionCards(guiGraphics, grayBoxX, grayBoxY, mouseX, mouseY, filtered);
    }

    private void drawTabDownload(GuiGraphics guiGraphics, int grayBoxX, int grayBoxY, int mouseX, int mouseY) {
        int statusColor;
        String statusText;
        int[] r = this.getContentRect(grayBoxX, grayBoxY);
        int rx = r[0];
        int ry = r[1];
        int rw = r[2];
        int rh = r[3];
        this.websiteCardHitRects.clear();
        if (this.websiteBuildingsLoading) {
            statusText = GuiExtensionPackBrowser.tr("website.status.loading", new Object[0]);
            statusColor = 0x55AAFF;
        } else if (this.websiteBuildingsError != null) {
            statusText = GuiExtensionPackBrowser.tr("website.status.failed", GuiExtensionPackBrowser.truncate(this.websiteBuildingsError, 16));
            statusColor = 0xFF5555;
        } else if (this.websiteBuildings.isEmpty()) {
            statusText = GuiExtensionPackBrowser.tr("website.status.empty", new Object[0]);
            statusColor = 0xAAAAAA;
        } else {
            statusText = GuiExtensionPackBrowser.tr("website.status.summary", String.valueOf(this.websiteBuildings.size()));
            statusColor = 0xAAAAAA;
        }
        guiGraphics.drawString(this.font, statusText, rx + 4, ry + 4, statusColor);
        int btnH = 12;
        int webBtnW = 60;
        int webBtnX = rx + rw - webBtnW - 4;
        int webBtnY = ry + 2;
        this.drawTextButton(guiGraphics, webBtnX, webBtnY, webBtnW, btnH, GuiExtensionPackBrowser.tr("website.button.open", new Object[0]), mouseX, mouseY);
        this.lastOpenWebsiteButtonRect = new int[]{webBtnX, webBtnY, webBtnW, btnH};
        int filtW = 64;
        int filtX = webBtnX - filtW - 4;
        int filtY = ry + 2;
        String filtLabel = "all".equals(this.websiteFilter) ? GuiExtensionPackBrowser.tr("website.filter.all", new Object[0]) : ("downloaded".equals(this.websiteFilter) ? GuiExtensionPackBrowser.tr("website.filter.downloaded", new Object[0]) : GuiExtensionPackBrowser.tr("website.filter.not_downloaded", new Object[0]));
        this.drawTextButton(guiGraphics, filtX, filtY, filtW, btnH, "\u25bc " + filtLabel, mouseX, mouseY);
        this.websiteFilterButtonRect = new int[]{filtX, filtY, filtW, btnH};
        ArrayList<PackDownloadManager.BuildingInfo2> filtered = new ArrayList<PackDownloadManager.BuildingInfo2>();
        for (PackDownloadManager.BuildingInfo2 b2 : this.websiteBuildings) {
            if (b2 == null || b2.id == null) continue;
            boolean downloaded = this.isWebsiteBuildingDownloaded(b2);
            if ("downloaded".equals(this.websiteFilter) && !downloaded || "not_downloaded".equals(this.websiteFilter) && downloaded) continue;
            filtered.add(b2);
        }
        filtered.sort((a, b) -> {
            boolean db;
            boolean da = this.isWebsiteBuildingDownloaded((PackDownloadManager.BuildingInfo2)a);
            if (da != (db = this.isWebsiteBuildingDownloaded((PackDownloadManager.BuildingInfo2)b))) {
                return da ? -1 : 1;
            }
            return 0;
        });
        if (filtered.isEmpty()) {
            String emptyHint;
            String emptyTitle;
            if (this.websiteBuildingsError != null) {
                emptyTitle = GuiExtensionPackBrowser.tr("website.empty.failed", new Object[0]);
                emptyHint = GuiExtensionPackBrowser.tr("website.empty.failed_hint", new Object[0]);
            } else if ("not_downloaded".equals(this.websiteFilter)) {
                emptyTitle = GuiExtensionPackBrowser.tr("website.empty.all_downloaded", new Object[0]);
                emptyHint = GuiExtensionPackBrowser.tr("website.empty.all_downloaded_hint", new Object[0]);
            } else if ("downloaded".equals(this.websiteFilter)) {
                emptyTitle = GuiExtensionPackBrowser.tr("website.empty.no_downloaded", new Object[0]);
                emptyHint = GuiExtensionPackBrowser.tr("website.empty.no_downloaded_hint", new Object[0]);
            } else if (this.websiteBuildingsLoading) {
                emptyTitle = GuiExtensionPackBrowser.tr("website.empty.loading", new Object[0]);
                emptyHint = GuiExtensionPackBrowser.tr("website.empty.loading_hint", new Object[0]);
            } else {
                emptyTitle = GuiExtensionPackBrowser.tr("website.empty.empty", new Object[0]);
                emptyHint = GuiExtensionPackBrowser.tr("website.empty.empty_hint", new Object[0]);
            }
            this.drawEmpty(guiGraphics, new int[]{rx, ry + 18, rw, rh - 18}, emptyTitle, emptyHint);
            return;
        }
        int listY = ry + 18;
        int listH = rh - 22;
        int cardW = (rw - 8 - 12) / 3;
        int cardH = 70;
        int total = filtered.size();
        int pageSize = Math.max(1, (listH + 6) / (cardH + 6));
        int pageCount = Math.max(1, (total + pageSize - 1) / pageSize);
        int page = Math.max(0, Math.min(this.scrollOffsetCards, pageCount - 1));
        int start = page * pageSize;
        int end = Math.min(start + pageSize, total);
        int col = 0;
        int row = 0;
        for (int i = start; i < end; ++i) {
            PackDownloadManager.BuildingInfo2 b3 = (PackDownloadManager.BuildingInfo2)filtered.get(i);
            int cx = rx + 4 + col * (cardW + 6);
            int cy = listY + row * (cardH + 6);
            this.drawWebsiteBuildingCard(guiGraphics, cx, cy, cardW, cardH, b3, mouseX, mouseY);
            int[] rect = new int[]{cx, cy, cardW, cardH};
            this.websiteCardHitRects.put(b3.id, rect);
            if (++col < 3) continue;
            col = 0;
            ++row;
        }
        if (pageCount > 1) {
            this.drawPaginationBar(guiGraphics, rx, ry + rh - 12, rw, page, pageCount, mouseX, mouseY);
        }
    }

    private void drawWebsiteBuildingCard(GuiGraphics guiGraphics, int cx, int cy, int cw, int ch, PackDownloadManager.BuildingInfo2 b, int mouseX, int mouseY) {
        Object name;
        boolean downloaded = this.isWebsiteBuildingDownloaded(b);
        boolean downloading = this.websiteDownloading.contains(b.id);
        boolean hovered = mouseX >= cx && mouseX <= cx + cw && mouseY >= cy && mouseY <= cy + ch;
        int bg = hovered ? -13816531 : -14737633;
        guiGraphics.fill(cx, cy, cx + cw, cy + ch, bg);
        int borderColor = downloading ? -11162881 : (downloaded ? -13730510 : -29696);
        guiGraphics.fill(cx, cy, cx + cw, cy + 1, borderColor);
        guiGraphics.fill(cx, cy + ch - 1, cx + cw, cy + ch, -11184811);
        guiGraphics.fill(cx, cy, cx + 1, cy + ch, -11184811);
        guiGraphics.fill(cx + cw - 1, cy, cx + cw, cy + ch, -11184811);
        int iconSize = 36;
        int iconX = cx + 4;
        int iconY = cy + 4;
        ResourceLocation tex = this.websiteImageCache.get(b.id);
        if (tex != null) {
            this.drawIconNearest(guiGraphics, tex, iconX, iconY, iconSize, iconSize, 0, 0, 48, 48, 48, 48);
        } else {
            guiGraphics.fill(iconX, iconY, iconX + iconSize, iconY + iconSize, -15066598);
            String initial = b.name.isEmpty() ? "?" : b.name.substring(0, 1);
            guiGraphics.drawCenteredString(this.font, initial, iconX + iconSize / 2, iconY + iconSize / 2 - 4, -7829368);
        }
        int textX = iconX + iconSize + 5;
        int textW = cw - iconSize - 10;
        if (textW < 30) {
            textW = 30;
        }
        Object object = name = b.name == null || b.name.isEmpty() ? b.id : b.name;
        if (this.font.width((String)name) > textW) {
            while (this.font.width((String)name + "..") > textW && ((String)name).length() > 1) {
                name = ((String)name).substring(0, ((String)name).length() - 1);
            }
            name = (String)name + "..";
        }
        guiGraphics.drawString(this.font, (String)name, textX, cy + 5, 0xFFFFFF);
        Object author = "\u00a77by " + (b.author == null || b.author.isEmpty() ? GuiExtensionPackBrowser.tr("website.author.unknown", new Object[0]) : b.author);
        if (this.font.width((String)author) > textW) {
            while (this.font.width((String)author + "..") > textW && ((String)author).length() > 1) {
                author = ((String)author).substring(0, ((String)author).length() - 1);
            }
            author = (String)author + "..";
        }
        guiGraphics.drawString(this.font, (String)author, textX, cy + 17, -5592406);
        String meta = "\u00a77" + (b.fileExt == null || b.fileExt.isEmpty() ? ".nbt" : b.fileExt) + " \u00b7 " + GuiExtensionPackBrowser.formatFileSize(b.fileSize) + (String)(b.downloads > 0 ? " \u00b7 \u2193" + b.downloads : "");
        guiGraphics.drawString(this.font, meta, textX, cy + 29, -7829368);
        int sepY = cy + 44;
        guiGraphics.fill(cx + 2, sepY, cx + cw - 2, sepY + 1, -12303292);
        int dBtnW = cw - 10;
        int dBtnH = 16;
        int dBtnX = cx + 5;
        int dBtnY = cy + ch - dBtnH - 4;
        if (downloading) {
            guiGraphics.fill(dBtnX, dBtnY, dBtnX + dBtnW, dBtnY + dBtnH, -11184777);
            double[] prog = this.websiteDownloadProgress.get(b.id);
            String label = GuiExtensionPackBrowser.tr("website.status.loading", new Object[0]);
            if (prog != null) {
                int pct = (int)prog[2];
                label = GuiExtensionPackBrowser.tr("website.badge.downloading", String.valueOf(pct));
            }
            guiGraphics.drawCenteredString(this.font, label, dBtnX + dBtnW / 2, dBtnY + 4, -3355444);
        } else if (downloaded) {
            guiGraphics.fill(dBtnX, dBtnY, dBtnX + dBtnW, dBtnY + dBtnH, -869368526);
            guiGraphics.drawCenteredString(this.font, GuiExtensionPackBrowser.tr("website.badge.downloaded", new Object[0]), dBtnX + dBtnW / 2, dBtnY + 4, -1);
        } else {
            this.drawTextButton(guiGraphics, dBtnX, dBtnY, dBtnW, dBtnH, GuiExtensionPackBrowser.tr("website.button.download", new Object[0]), mouseX, mouseY);
        }
        this.websiteCardDownloadBtnRects.put(b.id, new int[]{dBtnX, dBtnY, dBtnW, dBtnH});
    }

    private void drawTabCloud(GuiGraphics guiGraphics, int grayBoxX, int grayBoxY, int mouseX, int mouseY) {
        int statusColor;
        String statusText;
        List<CloudBuilding> list;
        int[] r = this.getContentRect(grayBoxX, grayBoxY);
        int rx = r[0];
        int ry = r[1];
        int rw = r[2];
        int rh = r[3];
        this.cloudCardHitRects.clear();
        this.cloudCardRecallBtnRects.clear();
        this.cloudCardSummonBtnRects.clear();
        this.cloudCardDeleteBtnRects.clear();
        this.cloudCardNavigateBtnRects.clear();
        if (this.navigateDebugLogged.size() > 200) {
            this.navigateDebugLogged.clear();
        }
        if ((list = CloudBuildingClientCache.getInstance().getAll()).isEmpty()) {
            statusText = GuiExtensionPackBrowser.tr("cloud.status.empty", new Object[0]);
            statusColor = 0xAAAAAA;
        } else {
            int placed = 0;
            int recalled = 0;
            for (CloudBuilding b : list) {
                if (b.placed) {
                    ++placed;
                    continue;
                }
                ++recalled;
            }
            statusText = GuiExtensionPackBrowser.tr("cloud.status.summary", String.valueOf(list.size()), String.valueOf(placed), String.valueOf(recalled));
            statusColor = 0x55AAFF;
        }
        guiGraphics.drawString(this.font, statusText, rx + 4, ry + 4, statusColor);
        if (list.isEmpty()) {
            this.drawEmpty(guiGraphics, new int[]{rx, ry + 18, rw, rh - 18}, GuiExtensionPackBrowser.tr("cloud.empty.title", new Object[0]), GuiExtensionPackBrowser.tr("cloud.empty.hint", new Object[0]));
            return;
        }
        for (CloudBuilding b : list) {
            if (b.thumbnailPng == null || b.thumbnailPng.length <= 0 || this.cloudThumbCache.containsKey(b.id)) continue;
            this.ensureCloudThumbLoaded(b);
        }
        int listY = ry + 18;
        int listH = rh - 22;
        int cardW = (rw - 8 - 6) / 2;
        int cardH = 96;
        int total = list.size();
        int rows = Math.max(1, (listH + 6) / (cardH + 6));
        int pageSize = rows * 2;
        int pageCount = Math.max(1, (total + pageSize - 1) / pageSize);
        int page = Math.max(0, Math.min(this.scrollOffsetCards, pageCount - 1));
        int start = page * pageSize;
        int end = Math.min(start + pageSize, total);
        int col = 0;
        int row = 0;
        for (int i = start; i < end; ++i) {
            int cx = rx + 4 + col * (cardW + 6);
            int cy = listY + row * (cardH + 6);
            this.drawCloudBuildingCard(guiGraphics, cx, cy, cardW, cardH, list.get(i), mouseX, mouseY);
            this.cloudCardHitRects.put(list.get((int)i).id, new int[]{cx, cy, cardW, cardH});
            if (++col < 2) continue;
            col = 0;
            ++row;
        }
        if (pageCount > 1) {
            this.drawPaginationBar(guiGraphics, rx, ry + rh - 12, rw, page, pageCount, mouseX, mouseY);
        }
    }

    private void drawCloudBuildingCard(GuiGraphics guiGraphics, int cx, int cy, int cw, int ch, CloudBuilding b, int mouseX, int mouseY) {
        int bg2;
        String facingShort;
        boolean hovered = mouseX >= cx && mouseX <= cx + cw && mouseY >= cy && mouseY <= cy + ch;
        int bg = hovered ? -13816531 : -14737633;
        guiGraphics.fill(cx, cy, cx + cw, cy + ch, bg);
        int topColor = b.placed ? -13730510 : -8947849;
        guiGraphics.fill(cx, cy, cx + cw, cy + 1, topColor);
        guiGraphics.fill(cx, cy + ch - 1, cx + cw, cy + ch, -11184811);
        guiGraphics.fill(cx, cy, cx + 1, cy + ch, -11184811);
        guiGraphics.fill(cx + cw - 1, cy, cx + cw, cy + ch, -11184811);
        int iconSize = 48;
        int iconX = cx + 4;
        int iconY = cy + 4;
        int iconBg = b.placed ? -14730721 : -14013910;
        guiGraphics.fill(iconX, iconY, iconX + iconSize, iconY + iconSize, iconBg);
        ResourceLocation thumb = this.cloudThumbCache.get(b.id);
        if (thumb == null && b.thumbnailPng != null && b.thumbnailPng.length > 0) {
            thumb = this.ensureCloudThumbLoaded(b);
        }
        if (thumb != null) {
            int srcW = b.thumbWidth > 0 ? b.thumbWidth : iconSize;
            int srcH = b.thumbHeight > 0 ? b.thumbHeight : iconSize;
            guiGraphics.blit(thumb, iconX, iconY, 0.0f, 0.0f, iconSize, iconSize, srcW, srcH);
        } else {
            if (b.name != null && !b.name.isEmpty() && !this.cloudThumbLoading.contains(b.id)) {
                this.cloudThumbLoading.add(b.id);
                this.triggerLoadCloudThumb(b);
            }
            String initial = b.name == null || b.name.isEmpty() ? "?" : b.name.substring(0, 1);
            guiGraphics.drawCenteredString(this.font, initial, iconX + iconSize / 2, iconY + iconSize / 2 - 4, b.placed ? -7811960 : -7829368);
        }
        int textX = iconX + iconSize + 6;
        int textW = cw - iconSize - 12 - 14;
        if (textW < 30) {
            textW = 30;
        }
        String baseName = b.name == null || b.name.isEmpty() ? GuiExtensionPackBrowser.tr("cloud.card.unnamed", new Object[0]) : b.name;
        String nameTrunc = this.truncateToWidth(baseName, textW);
        guiGraphics.drawString(this.font, nameTrunc, textX, cy + 4, -1);
        String statusSuffix = b.placed ? GuiExtensionPackBrowser.tr("cloud.card.placed", new Object[0]) : GuiExtensionPackBrowser.tr("cloud.card.recalled", new Object[0]);
        int statusColor = b.placed ? -11141291 : -5592406;
        guiGraphics.drawString(this.font, statusSuffix, textX, cy + 16, statusColor);
        if (b.facing == null) {
            facingShort = "?";
        } else {
            switch (b.facing.getName()) {
                case "north": {
                    facingShort = "\u5317";
                    break;
                }
                case "south": {
                    facingShort = "\u5357";
                    break;
                }
                case "east": {
                    facingShort = "\u4e1c";
                    break;
                }
                case "west": {
                    facingShort = "\u897f";
                    break;
                }
                default: {
                    facingShort = b.facing.getName();
                }
            }
        }
        String relTime = this.formatRelativeTime(b.timestamp);
        relTime = relTime.replace("\u5206\u949f", "\u5206").replace("\u5c0f\u65f6", "\u65f6").replace("\u5929", "\u5929");
        Object meta = b.placed && b.placedAt != null ? b.blocks.size() + " \u00b7 " + b.placedAt.toShortString() + " \u00b7 " + relTime : b.blocks.size() + " \u00b7 " + facingShort + " \u00b7 " + relTime;
        meta = this.truncateToWidth((String)meta, textW);
        guiGraphics.drawString(this.font, (String)meta, textX, cy + 28, -5592406);
        String hint = b.placed ? "\u70b9\u6536\u56de\u6e05\u7406" : "\u70b9\u653e\u51fa\u9884\u89c8";
        hint = this.truncateToWidth(hint, textW);
        guiGraphics.drawString(this.font, hint, textX, cy + 40, -7829368);
        Object sizeInfo = b.sizeX + "\u00d7" + b.sizeY + "\u00d7" + b.sizeZ;
        sizeInfo = this.truncateToWidth((String)sizeInfo, textW);
        guiGraphics.drawString(this.font, (String)sizeInfo, textX, cy + 52, -8947849);
        int sepY = cy + 70;
        guiGraphics.fill(cx + 2, sepY, cx + cw - 2, sepY + 1, -12303292);
        int btnY = sepY + 4;
        int btnH = ch - (btnY - cy) - 3;
        if (btnH < 14) {
            btnH = 14;
        }
        int gap = 4;
        int recallW = (cw - 10 - gap) / 2;
        int recallX = cx + 5;
        int summonX = recallX + recallW + gap;
        int summonW = cw - 10 - recallW - gap;
        if (b.placed) {
            this.drawTextButton(guiGraphics, recallX, btnY, recallW, btnH, GuiExtensionPackBrowser.tr("cloud.button.recall", new Object[0]), mouseX, mouseY);
        } else {
            bg2 = -13421773;
            guiGraphics.fill(recallX, btnY, recallX + recallW, btnY + btnH, bg2);
            guiGraphics.drawCenteredString(this.font, GuiExtensionPackBrowser.tr("cloud.button.recall", new Object[0]), recallX + recallW / 2, btnY + (btnH - 8) / 2, -10066330);
        }
        if (!b.placed) {
            this.drawTextButton(guiGraphics, summonX, btnY, summonW, btnH, GuiExtensionPackBrowser.tr("cloud.button.summon", new Object[0]), mouseX, mouseY);
        } else {
            bg2 = -13421773;
            guiGraphics.fill(summonX, btnY, summonX + summonW, btnY + btnH, bg2);
            guiGraphics.drawCenteredString(this.font, GuiExtensionPackBrowser.tr("cloud.button.summon", new Object[0]), summonX + summonW / 2, btnY + (btnH - 8) / 2, -10066330);
        }
        this.cloudCardRecallBtnRects.put(b.id, new int[]{recallX, btnY, recallW, btnH});
        this.cloudCardSummonBtnRects.put(b.id, new int[]{summonX, btnY, summonW, btnH});
        boolean hasRealPlacedAt = b.placedAt != null && (b.placedAt.getX() != 0 || b.placedAt.getY() != 0 || b.placedAt.getZ() != 0);
        boolean xaeroAvail = XaeroWaypointBridge.isAvailable();
        if (!this.navigateDebugLogged.contains(b.id)) {
            this.navigateDebugLogged.add(b.id);
            PrefabCustomAddon.LOGGER.info("[CLOUD-NAV] building='{}' placed={} placedAt={} xaeroAvail={} \u2192 {}", new Object[]{b.name, b.placed, b.placedAt, xaeroAvail, xaeroAvail && hasRealPlacedAt ? "BUTTON_SHOWN" : "BUTTON_HIDDEN"});
        }
        if (xaeroAvail && hasRealPlacedAt) {
            int navSize = 10;
            int delXForNav = cx + cw - 3 - 10;
            int navX = delXForNav - 2 - navSize;
            int navY = cy + 3;
            boolean navHovered = mouseX >= navX && mouseX <= navX + navSize && mouseY >= navY && mouseY <= navY + navSize;
            int navColor = navHovered ? -7798904 : -11163051;
            int cx2 = navX + navSize / 2;
            int cy2 = navY + navSize / 2;
            guiGraphics.fill(cx2 - 1, cy2 - 2, cx2 + 2, cy2 - 1, navColor);
            guiGraphics.fill(cx2 - 2, cy2 - 1, cx2 + 3, cy2, navColor);
            guiGraphics.fill(cx2 - 1, cy2, cx2 + 2, cy2 + 1, navColor);
            guiGraphics.fill(cx2, cy2 + 1, cx2 + 1, cy2 + 2, navColor);
            if (navHovered) {
                guiGraphics.fill(cx2 - 2, cy2 - 3, cx2 + 3, cy2 - 2, 0x55FFFFFF);
                guiGraphics.fill(cx2 - 3, cy2 - 2, cx2 + 4, cy2 - 1, 0x55FFFFFF);
                guiGraphics.fill(cx2 - 3, cy2 + 1, cx2 + 4, cy2 + 2, 0x55FFFFFF);
            }
            this.cloudCardNavigateBtnRects.put(b.id, new int[]{navX, navY, navSize, navSize});
        }
        int delSize = 10;
        int delX = cx + cw - delSize - 3;
        int delY = cy + 3;
        boolean delHovered = mouseX >= delX && mouseX <= delX + delSize && mouseY >= delY && mouseY <= delY + delSize;
        int delColor = delHovered ? -43691 : -7829368;
        guiGraphics.fill(delX + 2, delY + 2, delX + delSize - 1, delY + 3, delColor);
        guiGraphics.fill(delX + 3, delY + 3, delX + delSize - 2, delY + 4, delColor);
        guiGraphics.fill(delX + delSize - 2, delY + 2, delX + delSize - 1, delY + 3, delColor);
        guiGraphics.fill(delX + 3, delY + delSize - 3, delX + delSize - 2, delY + delSize - 2, delColor);
        for (int i = 0; i < delSize - 4; ++i) {
            guiGraphics.fill(delX + 2 + i, delY + 2 + i, delX + 3 + i, delY + 3 + i, delColor);
            guiGraphics.fill(delX + delSize - 3 - i, delY + 2 + i, delX + delSize - 2 - i, delY + 3 + i, delColor);
        }
        this.cloudCardDeleteBtnRects.put(b.id, new int[]{delX, delY, delSize, delSize});
    }

    private ResourceLocation ensureCloudThumbLoaded(CloudBuilding b) {
        if (b == null || b.thumbnailPng == null || b.thumbnailPng.length == 0) {
            return null;
        }
        ResourceLocation existing = this.cloudThumbCache.get(b.id);
        if (existing != null) {
            return existing;
        }
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(b.thumbnailPng));
            if (img == null) {
                PrefabCustomAddon.LOGGER.warn("[CLOUD-THUMB] \u540c\u6b65\u89e3\u7801\u5d4c\u5165\u56fe\u4e3a null: id={}", (Object)b.id);
                return null;
            }
            int w = img.getWidth();
            int h = img.getHeight();
            if (w <= 0 || h <= 0) {
                return null;
            }
            b.thumbWidth = w;
            b.thumbHeight = h;
            int ww = w;
            int hh = h;
            DynamicTexture tex = new DynamicTexture(ww, hh, false);
            tex.setFilter(false, false);
            NativeImage pixels = tex.getPixels();
            for (int y = 0; y < hh; ++y) {
                for (int x = 0; x < ww; ++x) {
                    int argb = img.getRGB(x, y);
                    int abgr = argb & 0xFF00FF00 | (argb & 0xFF0000) >> 16 | (argb & 0xFF) << 16;
                    pixels.setPixelRGBA(x, y, abgr);
                }
            }
            tex.upload();
            ResourceLocation loc = Minecraft.getInstance().getTextureManager().register("prefab_cloud_" + b.id, tex);
            this.cloudThumbCache.put(b.id, loc);
            PrefabCustomAddon.LOGGER.info("[CLOUD-THUMB] \u540c\u6b65\u89e3\u7801\u5d4c\u5165\u56fe: id={} {}x{}", new Object[]{b.id, ww, hh});
            return loc;
        }
        catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("[CLOUD-THUMB] \u540c\u6b65\u89e3\u7801\u5931\u8d25: id={} err={}", (Object)b.id, (Object)e.toString());
            return null;
        }
    }

    private String truncateToWidth(String s, int maxWidth) {
        if (s == null) {
            return "";
        }
        if (this.font.width(s) <= maxWidth) {
            return s;
        }
        String trimmed = s;
        while (trimmed.length() > 1 && this.font.width(trimmed + "..") > maxWidth) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "..";
    }

    private String formatRelativeTime(long ts) {
        long sec;
        if (ts <= 0L) {
            return GuiExtensionPackBrowser.tr("time.unknown", new Object[0]);
        }
        long diff = System.currentTimeMillis() - ts;
        if (diff < 0L) {
            diff = 0L;
        }
        if ((sec = diff / 1000L) < 60L) {
            return GuiExtensionPackBrowser.tr("time.just_now", new Object[0]);
        }
        long min = sec / 60L;
        if (min < 60L) {
            return GuiExtensionPackBrowser.tr("time.minutes_ago", String.valueOf(min));
        }
        long hr = min / 60L;
        if (hr < 24L) {
            return GuiExtensionPackBrowser.tr("time.hours_ago", String.valueOf(hr));
        }
        long day = hr / 24L;
        return GuiExtensionPackBrowser.tr("time.days_ago", String.valueOf(day));
    }

    private static String tr(String key, Object ... args) {
        try {
            return Component.translatable((String)key, (Object[])args).getString();
        }
        catch (Throwable t) {
            return key;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...";
    }

    private static boolean hitTestRect(int[] r, int mx, int my) {
        if (r == null || r.length < 4) {
            return false;
        }
        return mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3];
    }

    private void drawTextButton(GuiGraphics guiGraphics, int x, int y, int w, int h, String text, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        int bg = hovered ? -10061910 : -12298889;
        guiGraphics.fill(x, y, x + w, y + h, bg);
        guiGraphics.fill(x, y, x + w, y + 1, -7824948);
        guiGraphics.fill(x, y + h - 1, x + w, y + h, -14535868);
        guiGraphics.drawCenteredString(this.font, text, x + w / 2, y + (h - 8) / 2, 0xFFFFFF);
    }

    private void drawPaginationBar(GuiGraphics guiGraphics, int rx, int bottomY, int rw, int currentPage, int totalPages, int mouseX, int mouseY) {
        int startX;
        this.paginationBarRect = null;
        this.paginationPrevRect = null;
        this.paginationNextRect = null;
        this.paginationPageRects = null;
        if (totalPages <= 1) {
            return;
        }
        int barH = 12;
        int y = bottomY;
        this.paginationBarRect = new int[]{rx, y, rw, barH};
        int prevW = 16;
        int nextW = 16;
        int gap = 4;
        int numW = 28;
        int totalUsed = prevW + gap + numW + gap + nextW;
        int prevX = startX = rx + (rw - totalUsed) / 2;
        int cy = y;
        this.paginationPrevRect = new int[]{prevX, cy, prevW, barH};
        boolean prevActive = currentPage > 0;
        this.drawPageBtn(guiGraphics, this.paginationPrevRect, "\u2039", prevActive, prevActive && GuiExtensionPackBrowser.isHovered(this.paginationPrevRect, mouseX, mouseY));
        int numX = prevX + prevW + gap;
        int[] numR = new int[]{numX, cy, numW, barH};
        this.paginationPageRects = null;
        int bg = -12298889;
        boolean hovered = GuiExtensionPackBrowser.isHovered(numR, mouseX, mouseY);
        if (hovered) {
            bg = -11180391;
        }
        guiGraphics.fill(numR[0], numR[1], numR[0] + numR[2], numR[1] + numR[3], bg);
        String label = currentPage + 1 + "/" + totalPages;
        guiGraphics.drawCenteredString(this.font, label, numR[0] + numR[2] / 2, numR[1] + (numR[3] - 8) / 2, -1);
        int nextX = numX + numW + gap;
        this.paginationNextRect = new int[]{nextX, cy, nextW, barH};
        boolean nextActive = currentPage < totalPages - 1;
        this.drawPageBtn(guiGraphics, this.paginationNextRect, "\u203a", nextActive, nextActive && GuiExtensionPackBrowser.isHovered(this.paginationNextRect, mouseX, mouseY));
    }

    private void drawPageBtn(GuiGraphics guiGraphics, int[] r, String text, boolean active, boolean hovered) {
        int bg = !active ? -14013910 : (hovered ? -10061910 : -12298889);
        guiGraphics.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], bg);
        int textColor = active ? 0xFFFFFF : -10066330;
        guiGraphics.drawCenteredString(this.font, text, r[0] + r[2] / 2, r[1] + (r[3] - 8) / 2, textColor);
    }

    private static boolean isHovered(int[] r, int mx, int my) {
        return r != null && mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3];
    }

    private boolean handlePaginationClick(int mouseX, int mouseY) {
        int maxPage;
        if (GuiExtensionPackBrowser.isHovered(this.paginationPrevRect, mouseX, mouseY) && this.scrollOffsetCards > 0) {
            --this.scrollOffsetCards;
            return true;
        }
        if (GuiExtensionPackBrowser.isHovered(this.paginationNextRect, mouseX, mouseY) && this.scrollOffsetCards < (maxPage = this.computeMaxPageForCurrentTab())) {
            ++this.scrollOffsetCards;
            return true;
        }
        if (this.paginationPageRects != null) {
            for (int[] r : this.paginationPageRects) {
                if (!GuiExtensionPackBrowser.isHovered(r, mouseX, mouseY)) continue;
                this.scrollOffsetCards = r[4];
                return true;
            }
        }
        return false;
    }

    private boolean handleCategoryListClick(int mouseX, int mouseY) {
        if (GuiExtensionPackBrowser.isHovered(this.categoryToggleBtnRect, mouseX, mouseY)) {
            this.categoryPanelHidden = !this.categoryPanelHidden;
            this.scrollOffsetCards = 0;
            return true;
        }
        if (this.categoryPanelHidden) {
            return false;
        }
        for (int idx = 0; idx < this.categoryItemRects.size(); ++idx) {
            String newCat;
            int[] r = this.categoryItemRects.get(idx);
            if (!GuiExtensionPackBrowser.isHovered(r, mouseX, mouseY)) continue;
            if (idx == 0) {
                newCat = null;
            } else if (idx == 1) {
                newCat = "\u672a\u5206\u7c7b";
            } else {
                List<String> all = CategoryManager.get().getCategories();
                int realIdx = idx - 1;
                newCat = realIdx >= 0 && realIdx < all.size() ? all.get(realIdx) : null;
            }
            this.currentCategory = Objects.equals(newCat, this.currentCategory) ? null : newCat;
            this.scrollOffsetCards = 0;
            return true;
        }
        if (GuiExtensionPackBrowser.isHovered(this.categoryAddBtnRect, mouseX, mouseY)) {
            GuiCategoryManager.openStandalone();
            return true;
        }
        return false;
    }

    private void openDownloadFolder() {
        Path dir = LocalBuildingScanner.getDownloadRoot();
        try {
            if (!Files.exists(dir, new LinkOption[0])) {
                Files.createDirectories(dir, new FileAttribute[0]);
            }
            FolderOpener.openInOS(dir);
            this.setStatus("\u5df2\u6253\u5f00: " + String.valueOf(dir), 0x55FF55);
        }
        catch (Exception e) {
            this.setStatus("\u6253\u5f00\u5931\u8d25: " + e.getMessage(), 0xFF5555);
        }
    }

    private void drawDownloadedCard(GuiGraphics guiGraphics, int cx, int cy, int cw, int ch, LocalBuilding lb, int mouseX, int mouseY) {
        boolean hovered = mouseX >= cx && mouseX <= cx + cw && mouseY >= cy && mouseY <= cy + ch;
        int bg = hovered ? -13816531 : -14737633;
        guiGraphics.fill(cx, cy, cx + cw, cy + ch, bg);
        guiGraphics.fill(cx, cy, cx + cw, cy + 1, -11184811);
        guiGraphics.fill(cx, cy + ch - 1, cx + cw, cy + ch, -11184811);
        guiGraphics.fill(cx, cy, cx + 1, cy + ch, -11184811);
        guiGraphics.fill(cx + cw - 1, cy, cx + cw, cy + ch, -11184811);
        int iconSize = 40;
        int iconX = cx + 4;
        int iconY = cy + (ch - iconSize) / 2;
        ResourceLocation tex = this.localImageCache.get(lb.id);
        if (tex != null) {
            this.drawIconNearest(guiGraphics, tex, iconX, iconY, iconSize, iconSize, 0, 0, 256, 256, 256, 256);
        } else {
            guiGraphics.fill(iconX, iconY, iconX + iconSize, iconY + iconSize, -15066598);
            String initial = lb.name.isEmpty() ? "?" : lb.name.substring(0, 1);
            guiGraphics.drawCenteredString(this.font, initial, iconX + iconSize / 2, iconY + iconSize / 2 - 4, -7829368);
        }
        int textX = iconX + iconSize + 6;
        int textW = cw - iconSize - 12;
        Object name = lb.getDisplayName();
        if (this.font.width((String)name) > textW) {
            while (this.font.width((String)name + "..") > textW && ((String)name).length() > 1) {
                name = ((String)name).substring(0, ((String)name).length() - 1);
            }
            name = (String)name + "..";
        }
        guiGraphics.drawString(this.font, (String)name, textX, cy + 8, 0xFFFFFF);
        String author = "\u00a77by " + (lb.author.isEmpty() ? "\u672a\u77e5" : lb.author);
        guiGraphics.drawString(this.font, author, textX, cy + 20, -5592406);
        String meta = "\u00a77" + (lb.fileExt.isEmpty() ? ".nbt" : lb.fileExt) + " \u00b7 " + GuiExtensionPackBrowser.formatFileSize(lb.fileSize);
        guiGraphics.drawString(this.font, meta, textX, cy + 32, -7829368);
        String tag = "extension".equals(lb.source) ? "\u672c\u5730" : "\u4e0b\u8f7d";
        int tagW = this.font.width(tag);
        guiGraphics.drawString(this.font, "\u00a77" + tag, cx + cw - tagW - 4, cy + ch - 10, -7829368);
    }

    private static String formatFileSize(long bytes) {
        if (bytes <= 0L) {
            return "-";
        }
        if (bytes < 1024L) {
            return bytes + "B";
        }
        if (bytes < 0x100000L) {
            return bytes / 1024L + "KB";
        }
        return String.format("%.1fMB", (double)bytes / 1024.0 / 1024.0);
    }

    private void drawPackDrilldown(GuiGraphics guiGraphics, int grayBoxX, int grayBoxY, int mouseX, int mouseY) {
        ExtensionPack p = this.currentDrilldownPack;
        int[] r = this.getContentRect(grayBoxX, grayBoxY);
        int infoY = r[1] - 4;
        String info = String.format("\u00a77\u4f5c\u8005: \u00a7f%s \u00a77| \u00a77\u5efa\u7b51: \u00a7f%d \u00a77| \u00a77\u7248\u672c: \u00a7f%s", p.getAuthor() == null || p.getAuthor().isEmpty() ? "\u672a\u77e5" : p.getAuthor(), p.getConstructions().size(), p.getVersion() == null || p.getVersion().isEmpty() ? "\u672a\u6307\u5b9a" : p.getVersion());
        guiGraphics.drawString(this.font, info, r[0] + 4, infoY, 0xFFFFFF);
        List<ConstructionInfo> filtered = this.filterBySearch(p.getConstructions(), this.searchText);
        this.drawConstructionCards(guiGraphics, grayBoxX, grayBoxY, mouseX, mouseY, filtered);
    }

    private void drawPackCards(GuiGraphics guiGraphics, int grayBoxX, int grayBoxY, int mouseX, int mouseY, List<ExtensionPack> packs, boolean isServerTab) {
        int[] rect = this.getContentRect(grayBoxX, grayBoxY);
        int rx = rect[0];
        int ry = rect[1];
        int rw = rect[2];
        int rh = rect[3];
        if (packs.isEmpty()) {
            String hint = isServerTab ? "(\u670d\u52a1\u5668\u8fd8\u6ca1\u6709\u540c\u6b65\u4efb\u4f55\u5305)" : "(prefab-extension \u6587\u4ef6\u5939\u91cc\u6ca1\u627e\u5230\u6807\u51c6\u683c\u5f0f\u62d3\u5c55\u5305)";
            this.drawEmpty(guiGraphics, rect, "\u672a\u53d1\u73b0", hint);
            return;
        }
        int pageSize = 6;
        int pageCount = Math.max(1, (packs.size() + pageSize - 1) / pageSize);
        int page = Math.max(0, Math.min(this.scrollOffsetCards, pageCount - 1));
        int start = page * pageSize;
        int end = Math.min(start + pageSize, packs.size());
        int gridW = 288;
        int gridX = rx + (rw - gridW) / 2;
        int gridY = ry + 4;
        for (int i = 0; i < end - start; ++i) {
            int row = i / 3;
            int col = i % 3;
            int cx = gridX + col * 98;
            int cy = gridY + row * 86;
            ExtensionPack p = packs.get(start + i);
            this.drawPackCard(guiGraphics, cx, cy, p, mouseX, mouseY);
        }
        if (pageCount > 1) {
            this.drawPaginationBar(guiGraphics, rx, ry + rh - 12, rw, page, pageCount, mouseX, mouseY);
        }
    }

    private void drawPackCard(GuiGraphics guiGraphics, int cx, int cy, ExtensionPack p, int mouseX, int mouseY) {
        boolean hovered = mouseX >= cx && mouseX <= cx + 92 && mouseY >= cy && mouseY <= cy + 80;
        int bg = hovered ? -12961222 : -14737633;
        int border = hovered ? -11162881 : -11184811;
        guiGraphics.fill(cx, cy, cx + 92, cy + 80, bg);
        guiGraphics.fill(cx, cy, cx + 92, cy + 1, border);
        guiGraphics.fill(cx, cy + 80 - 1, cx + 92, cy + 80, border);
        guiGraphics.fill(cx, cy, cx + 1, cy + 80, border);
        guiGraphics.fill(cx + 92 - 1, cy, cx + 92, cy + 80, border);
        int iconSize = 60;
        int iconX = cx + (92 - iconSize) / 2;
        int iconY = cy + 4;
        if (p.hasCoverImage() && p.getCoverImageData() != null) {
            ResourceLocation tex = this.ensureCoverTextureLoaded(p);
            if (tex != null) {
                this.drawIconNearest(guiGraphics, tex, iconX, iconY, iconSize, iconSize, 0, 0, 48, 48, 48, 48);
            } else {
                guiGraphics.fill(iconX, iconY, iconX + iconSize, iconY + iconSize, -15066598);
            }
        } else {
            guiGraphics.fill(iconX, iconY, iconX + iconSize, iconY + iconSize, -15066598);
            String ch = p.getName().isEmpty() ? "?" : p.getName().substring(0, 1);
            guiGraphics.drawCenteredString(this.font, ch, iconX + iconSize / 2, iconY + iconSize / 2 - 4, -7829368);
        }
        Object name = p.getName();
        if (((String)name).length() > 12) {
            name = ((String)name).substring(0, 10) + "..";
        }
        guiGraphics.drawCenteredString(this.font, (String)name, cx + 46, cy + iconSize + 6, 0xFFFFFF);
        String sub = p.getConstructions().size() + " \u5efa\u7b51";
        if (p.isServerBacked()) {
            sub = sub + " \u00a7b[\u670d]";
        }
        guiGraphics.drawCenteredString(this.font, sub, cx + 46, cy + 80 - 10, -5592406);
    }

    private void drawConstructionCards(GuiGraphics guiGraphics, int grayBoxX, int grayBoxY, int mouseX, int mouseY, List<ConstructionInfo> list) {
        int[] rect = this.getContentRect(grayBoxX, grayBoxY);
        int rx = rect[0];
        int ry = rect[1];
        int rw = rect[2];
        int rh = rect[3];
        if (list.isEmpty()) {
            this.drawEmpty(guiGraphics, rect, "\u65e0\u5339\u914d\u5efa\u7b51", "(\u8bd5\u8bd5\u6e05\u7a7a\u641c\u7d22\u8bcd)");
            return;
        }
        int pageSize = 6;
        int pageCount = Math.max(1, (list.size() + pageSize - 1) / pageSize);
        int page = Math.max(0, Math.min(this.scrollOffsetCards, pageCount - 1));
        int start = page * pageSize;
        int end = Math.min(start + pageSize, list.size());
        int gridW = 288;
        int gridX = rx + (rw - gridW) / 2;
        int gridY = ry + 4;
        for (int i = 0; i < end - start; ++i) {
            int row = i / 3;
            int col = i % 3;
            int cx = gridX + col * 98;
            int cy = gridY + row * 86;
            ConstructionInfo c = list.get(start + i);
            this.drawConstructionCard(guiGraphics, cx, cy, c, mouseX, mouseY);
        }
        if (pageCount > 1) {
            this.drawPaginationBar(guiGraphics, rx, ry + rh - 12, rw, page, pageCount, mouseX, mouseY);
        }
    }

    private void drawConstructionCard(GuiGraphics guiGraphics, int cx, int cy, ConstructionInfo c, int mouseX, int mouseY) {
        boolean hovered = mouseX >= cx && mouseX <= cx + 92 && mouseY >= cy && mouseY <= cy + 80;
        int bg = hovered ? -12961222 : -14737633;
        int border = hovered ? -11162881 : -11184811;
        guiGraphics.fill(cx, cy, cx + 92, cy + 80, bg);
        guiGraphics.fill(cx, cy, cx + 92, cy + 1, border);
        guiGraphics.fill(cx, cy + 80 - 1, cx + 92, cy + 80, border);
        guiGraphics.fill(cx, cy, cx + 1, cy + 80, border);
        guiGraphics.fill(cx + 92 - 1, cy, cx + 92, cy + 80, border);
        int iconW = 90;
        int iconH = 64;
        int iconX = cx + 1;
        int iconY = cy + 1;
        boolean iconDrawn = false;
        if (c.hasPreviewImage()) {
            ResourceLocation tex = this.ensurePreviewTextureLoaded(c);
            if (tex != null) {
                this.drawIconNearest(guiGraphics, tex, iconX, iconY, iconW, iconH, 0, 0, 256, 256, 256, 256);
                iconDrawn = true;
            } else {
                guiGraphics.fill(iconX, iconY, iconX + iconW, iconY + iconH, -15066598);
            }
        }
        if (!iconDrawn && ThumbnailCache.hasCached(c)) {
            byte[] pngData = ThumbnailCache.read(c);
            if (pngData != null) {
                ResourceLocation tex = this.ensureCachedThumbnailTextureLoaded(c, pngData);
                if (tex != null) {
                    this.drawIconNearest(guiGraphics, tex, iconX, iconY, iconW, iconH, 0, 0, 256, 256, 256, 256);
                    iconDrawn = true;
                } else {
                    guiGraphics.fill(iconX, iconY, iconX + iconW, iconY + iconH, -15066598);
                }
            } else {
                guiGraphics.fill(iconX, iconY, iconX + iconW, iconY + iconH, -15066598);
            }
        }
        if (!iconDrawn) {
            guiGraphics.fill(iconX, iconY, iconX + iconW, iconY + iconH, -15066598);
            String ch = c.getName().isEmpty() ? "?" : c.getName().substring(0, 1);
            guiGraphics.drawCenteredString(this.font, ch, iconX + iconW / 2, iconY + iconH / 2 - 4, -7829368);
        }
        String packKey = c.getPack() == null ? "__standalone__" : c.getPack().getPackageName();
        boolean isFav = PlayerPreferences.get().isFavorite(packKey, c.getId());
        String star = isFav ? "\u2605 " : "";
        int nameMaxLen = isFav ? 8 : 10;
        Object name = c.getName();
        if (((String)name).length() > nameMaxLen) {
            name = ((String)name).substring(0, nameMaxLen - 2) + "..";
        }
        String displayName = star + (String)name;
        int nameColor = isFav ? -8858 : 0xFFFFFF;
        guiGraphics.drawCenteredString(this.font, displayName, cx + 46, iconY + iconH + 2, nameColor);
    }

    private int cardHitTest(int mouseX, int mouseY, int totalCount) {
        return this.cardHitTest(mouseX, mouseY, totalCount, 3, this.getContentRect(this.computePanelPos()[0], this.computePanelPos()[1]));
    }

    private int cardHitTestBuildings(int mouseX, int mouseY, int totalCount) {
        int[] pos = this.computePanelPos();
        return this.cardHitTest(mouseX, mouseY, totalCount, 2, this.getBuildingsCardRect(pos[0], pos[1]));
    }

    private int cardHitTest(int mouseX, int mouseY, int totalCount, int cols, int[] rect) {
        int rx = rect[0];
        int ry = rect[1];
        int rw = rect[2];
        int rh = rect[3];
        if (mouseX < rx || mouseX > rx + rw || mouseY < ry || mouseY > ry + rh) {
            return -1;
        }
        int pageSize = cols * 2;
        int pageCount = Math.max(1, (totalCount + pageSize - 1) / pageSize);
        int page = Math.max(0, Math.min(this.scrollOffsetCards, pageCount - 1));
        int gridW = cols * 92 + (cols - 1) * 6;
        int gridX = rx + (rw - gridW) / 2;
        int gridY = ry + 4;
        for (int i = 0; i < pageSize; ++i) {
            int row = i / cols;
            int col = i % cols;
            int cx = gridX + col * 98;
            int cy = gridY + row * 86;
            if (mouseX < cx || mouseX > cx + 92 || mouseY < cy || mouseY > cy + 80) continue;
            int idx = page * pageSize + i;
            return idx < totalCount ? idx : -1;
        }
        return -1;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int mx = (int)mouseX;
        int my = (int)mouseY;
        if (this.handlePaginationClick(mx, my)) {
            return true;
        }
        Tab hit = this.tabHitTest(mx, my);
        if (hit != null) {
            this.switchTab(hit);
            return true;
        }
        if (this.currentDrilldownPack != null) {
            List<ConstructionInfo> list = this.filterBySearch(this.currentDrilldownPack.getConstructions(), this.searchText);
            int idx = this.cardHitTest(mx, my, list.size());
            if (idx >= 0) {
                this.openConstructionDetail(list.get(idx));
                return true;
            }
        } else {
            switch (this.currentTab.ordinal()) {
                case 0: {
                    if (this.handleCategoryListClick(mx, my)) {
                        return true;
                    }
                    List<ConstructionInfo> all = this.getMergedConstructionsForBuildingsTab();
                    List<ConstructionInfo> byCat = this.filterByCategory(all, this.currentCategory);
                    List<ConstructionInfo> list = this.filterBySearch(byCat, this.searchText);
                    int idx = this.cardHitTestBuildings(mx, my, list.size());
                    if (idx < 0) break;
                    this.openConstructionDetail(list.get(idx));
                    return true;
                }
                case 1: {
                    int[] r;
                    for (Map.Entry<String, int[]> e : this.cloudCardRecallBtnRects.entrySet()) {
                        r = e.getValue();
                        if (mx < r[0] || mx > r[0] + r[2] || my < r[1] || my > r[1] + r[3]) continue;
                        String id = (String)e.getKey();
                        CloudBuilding b = CloudBuildingClientCache.getInstance().getById(id);
                        if (b == null) {
                            this.setStatus("\u2717 \u627e\u4e0d\u5230\u8be5\u4e91\u7aef\u5efa\u7b51", 0xFF5555);
                            return true;
                        }
                        if (!b.placed) {
                            this.setStatus("\u8be5\u5efa\u7b51\u5df2\u7ecf\u662f\u6536\u56de\u72b6\u6001", 0xAAAAAA);
                            return true;
                        }
                        CloudBuildingClientCache.getInstance().requestRecall(id);
                        this.setStatus("\u21a9 \u6536\u56de\u4e2d: " + b.name, 0x55AAFF);
                        return true;
                    }
                    for (Map.Entry<String, int[]> e : this.cloudCardDeleteBtnRects.entrySet()) {
                        r = e.getValue();
                        if (mx < r[0] || mx > r[0] + r[2] || my < r[1] || my > r[1] + r[3]) continue;
                        String id = (String)e.getKey();
                        CloudBuilding b = CloudBuildingClientCache.getInstance().getById(id);
                        if (b == null) {
                            this.setStatus("\u2717 \u627e\u4e0d\u5230\u8be5\u4e91\u7aef\u5efa\u7b51", 0xFF5555);
                            return true;
                        }
                        if (b.placed) {
                            this.setStatus("\u5efa\u7b51\u5df2\u653e\u51fa, \u8bf7\u5148\u6536\u56de\u518d\u5220\u9664", 0xFFAA55);
                            return true;
                        }
                        this.pendingDeleteBuildingId = id;
                        return true;
                    }
                    if (this.pendingDeleteBuildingId != null) {
                        if (this.deleteConfirmCancelRect != null && GuiExtensionPackBrowser.hitTestRect(this.deleteConfirmCancelRect, mx, my)) {
                            this.pendingDeleteBuildingId = null;
                            this.deleteConfirmCancelRect = null;
                            this.deleteConfirmOkRect = null;
                            this.setStatus("\u5df2\u53d6\u6d88\u5220\u9664", 0xAAAAAA);
                            return true;
                        }
                        if (this.deleteConfirmOkRect != null && GuiExtensionPackBrowser.hitTestRect(this.deleteConfirmOkRect, mx, my)) {
                            String id = this.pendingDeleteBuildingId;
                            CloudBuilding b = CloudBuildingClientCache.getInstance().getById(id);
                            String name = b != null ? b.name : "?";
                            this.pendingDeleteBuildingId = null;
                            this.deleteConfirmCancelRect = null;
                            this.deleteConfirmOkRect = null;
                            CloudBuildingClientCache.getInstance().requestDelete(id);
                            this.setStatus("\u2717 \u5220\u9664\u4e2d: " + name, 0xFFAA55);
                            return true;
                        }
                        return true;
                    }
                    for (Map.Entry<String, int[]> e : this.cloudCardNavigateBtnRects.entrySet()) {
                        ResourceKey dim;
                        r = e.getValue();
                        if (mx < r[0] || mx > r[0] + r[2] || my < r[1] || my > r[1] + r[3]) continue;
                        String id = (String)e.getKey();
                        CloudBuilding b = CloudBuildingClientCache.getInstance().getById(id);
                        if (b == null || b.placedAt == null || b.placedAt.getX() == 0 && b.placedAt.getY() == 0 && b.placedAt.getZ() == 0) {
                            this.setStatus("\u2717 \u8be5\u5efa\u7b51\u6682\u65e0\u4e16\u754c\u4f4d\u7f6e", 0xFF5555);
                            return true;
                        }
                        try {
                            if (b.dimensionId != null && !b.dimensionId.isEmpty()) {
                                ResourceLocation dimLoc = ResourceLocation.parse((String)b.dimensionId);
                                dim = ResourceKey.create((ResourceKey)Registries.DIMENSION, (ResourceLocation)dimLoc);
                            } else {
                                dim = Minecraft.getInstance().level != null ? Minecraft.getInstance().level.dimension() : ResourceKey.create((ResourceKey)Registries.DIMENSION, (ResourceLocation)ResourceLocation.withDefaultNamespace((String)"overworld"));
                            }
                        }
                        catch (Throwable t) {
                            dim = ResourceKey.create((ResourceKey)Registries.DIMENSION, (ResourceLocation)ResourceLocation.withDefaultNamespace((String)"overworld"));
                        }
                        String wpName = b.placed ? "\u2192 " + b.name : "\u2192 " + b.name + " (\u5df2\u6536\u56de)";
                        String otherWpName = b.placed ? "\u2192 " + b.name + " (\u5df2\u6536\u56de)" : "\u2192 " + b.name;
                        boolean exists = XaeroWaypointBridge.hasWaypoint(wpName);
                        if (!exists) {
                            exists = XaeroWaypointBridge.hasWaypoint(otherWpName);
                        }
                        if (exists) {
                            boolean removed = XaeroWaypointBridge.removeWaypoint(wpName);
                            if (!removed) {
                                removed = XaeroWaypointBridge.removeWaypoint(otherWpName);
                            }
                            if (removed) {
                                this.setStatus("\u2717 \u5df2\u53d6\u6d88\u822a\u70b9: " + b.name, 0xFFFF55);
                            } else {
                                this.setStatus("\u2717 \u627e\u5230\u822a\u70b9\u4f46\u65e0\u6cd5\u5220\u9664 (Xaero 26.4.2 remove API \u4e0d\u517c\u5bb9?)", 0xFF5555);
                            }
                        } else {
                            boolean ok = XaeroWaypointBridge.addWaypoint((ResourceKey<Level>)dim, b.placedAt, wpName, -11163051);
                            if (ok) {
                                this.setStatus("\ud83d\udccd \u5df2\u6807\u822a\u70b9: " + b.name + " @ " + b.placedAt.toShortString() + (b.placed ? "" : " \u00a77(\u5df2\u6536\u56de, \u5386\u53f2\u4f4d\u7f6e)"), 0x55FF55);
                            } else {
                                String reason = XaeroWaypointBridge.consumeLastAddError();
                                if (reason == null || reason.isEmpty()) {
                                    reason = "Xaero \u4e0d\u53ef\u7528";
                                }
                                this.setStatus("\u2717 \u6807\u822a\u70b9\u5931\u8d25: " + reason, 0xFF5555);
                            }
                        }
                        return true;
                    }
                    for (Map.Entry<String, int[]> e : this.cloudCardSummonBtnRects.entrySet()) {
                        r = (int[])e.getValue();
                        if (mx < r[0] || mx > r[0] + r[2] || my < r[1] || my > r[1] + r[3]) continue;
                        String id = (String)e.getKey();
                        CloudBuilding b = CloudBuildingClientCache.getInstance().getById(id);
                        if (b == null) {
                            this.setStatus("\u2717 \u627e\u4e0d\u5230\u8be5\u4e91\u7aef\u5efa\u7b51", 0xFF5555);
                            return true;
                        }
                        if (b.placed) {
                            this.setStatus("\u5df2\u653e\u51fa @ " + (b.placedAt == null ? "?" : b.placedAt.toShortString()) + ", \u5148\u6536\u56de\u518d\u653e\u51fa", 0xAAAAAA);
                            return true;
                        }
                        boolean ok = CloudPreview.start(id);
                        if (ok) {
                            this.setStatus("\u2601 \u4e91\u7aef\u9884\u89c8: " + b.name + " (" + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_FORWARD, "\u2191") + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_BACK, "\u2193") + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_LEFT, "\u2190") + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_RIGHT, "\u2192") + " \u79fb\u52a8, " + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_ROTATE, "CTRL") + " \u65cb\u8f6c, " + PackBrowserKeyHandler.buildKeyName() + " \u653e\u51fa, " + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.CANCEL_PREVIEW, "\u53f3\u952e") + " \u53d6\u6d88)", 0x55FF55);
                        } else {
                            this.setStatus("\u2717 \u5f00\u542f\u4e91\u7aef\u9884\u89c8\u5931\u8d25", 0xFF5555);
                        }
                        return true;
                    }
                    return true;
                }
                case 2: {
                    int[] r;
                    if (Minecraft.getInstance().getCurrentServer() == null) {
                        return true;
                    }
                    ServerPackSyncClient sync = ServerPackSyncClient.getInstance();
                    if (this.lastSyncServerBtn != null && !sync.isSyncing() && mx >= (r = this.lastSyncServerBtn)[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3]) {
                        sync.requestResync();
                        this.setStatus("\u5df2\u8bf7\u6c42\u540c\u6b65\u670d\u52a1\u5668", 0x55AAFF);
                        return true;
                    }
                    String[] modes = new String[]{"all", "unsynced", "synced"};
                    for (int i = 0; i < 3; ++i) {
                        int[] r2 = this.serverFilterTabs[i];
                        if (r2 == null || r2.length < 4 || mx < r2[0] || mx > r2[0] + r2[2] || my < r2[1] || my > r2[1] + r2[3]) continue;
                        this.serverFilter = modes[i];
                        this.scrollOffsetCards = 0;
                        this.setStatus("\u7b5b\u9009: " + modes[i], 0xAAAAAA);
                        return true;
                    }
                    for (Map.Entry<String, int[]> e : this.serverCardSyncBtnRects.entrySet()) {
                        int[] r3 = e.getValue();
                        if (mx < r3[0] || mx > r3[0] + r3[2] || my < r3[1] || my > r3[1] + r3[3]) continue;
                        List<ServerBuildingInfo> all = ExtensionPackManager.getInstance().getServerBuildings();
                        ServerBuildingInfo target = null;
                        for (ServerBuildingInfo b : all) {
                            if (!e.getKey().equals(b.buildingId)) continue;
                            target = b;
                            break;
                        }
                        if (target == null) {
                            return true;
                        }
                        if (target.synced) {
                            this.openSyncedServerBuildingDetail(target);
                        } else if (sync.isSyncing()) {
                            this.setStatus("\u540c\u6b65\u4e2d, \u8bf7\u7a0d\u5019...", 0xFFAA55);
                        } else {
                            sync.requestSyncSingle(target.buildingId);
                            this.setStatus("\u5f00\u59cb\u540c\u6b65: " + target.getDisplayName(), 0x55AAFF);
                        }
                        return true;
                    }
                    int cardIdx = this.serverCardHitTest(mx, my);
                    if (cardIdx < 0) break;
                    List<ServerBuildingInfo> all = ExtensionPackManager.getInstance().getServerBuildings();
                    ArrayList<ServerBuildingInfo> filtered = new ArrayList<ServerBuildingInfo>();
                    for (ServerBuildingInfo b : all) {
                        if ("unsynced".equals(this.serverFilter) && b.synced || "synced".equals(this.serverFilter) && !b.synced) continue;
                        filtered.add(b);
                    }
                    if (cardIdx >= filtered.size()) break;
                    ServerBuildingInfo target = (ServerBuildingInfo)filtered.get(cardIdx);
                    if (target.synced) {
                        this.openSyncedServerBuildingDetail(target);
                    } else if (sync.isSyncing()) {
                        this.setStatus("\u540c\u6b65\u4e2d, \u8bf7\u7a0d\u5019...", 0xFFAA55);
                    } else {
                        sync.requestSyncSingle(target.buildingId);
                        this.setStatus("\u5f00\u59cb\u540c\u6b65: " + target.getDisplayName(), 0x55AAFF);
                    }
                    return true;
                }
                case 3: {
                    List<ConstructionInfo> list = this.filterBySearch(ExtensionPackManager.getInstance().getFavoriteConstructions(), this.searchText);
                    int idx = this.cardHitTest(mx, my, list.size());
                    if (idx < 0) break;
                    this.openConstructionDetail(list.get(idx));
                    return true;
                }
                case 4: {
                    String id;
                    int[] r;
                    int[] r4;
                    if (this.lastOpenWebsiteButtonRect != null && mx >= (r4 = this.lastOpenWebsiteButtonRect)[0] && mx <= r4[0] + r4[2] && my >= r4[1] && my <= r4[1] + r4[3]) {
                        this.openWebsiteHome();
                        return true;
                    }
                    if (this.websiteFilterButtonRect != null && mx >= (r4 = this.websiteFilterButtonRect)[0] && mx <= r4[0] + r4[2] && my >= r4[1] && my <= r4[1] + r4[3]) {
                        this.websiteFilter = "all".equals(this.websiteFilter) ? "downloaded" : ("downloaded".equals(this.websiteFilter) ? "not_downloaded" : "all");
                        this.scrollOffsetCards = 0;
                        return true;
                    }
                    for (Map.Entry<String, int[]> e : this.websiteCardDownloadBtnRects.entrySet()) {
                        r = e.getValue();
                        if (mx < r[0] || mx > r[0] + r[2] || my < r[1] || my > r[1] + r[3]) continue;
                        id = e.getKey();
                        for (PackDownloadManager.BuildingInfo2 b : this.websiteBuildings) {
                            if (!id.equals(b.id)) continue;
                            if (this.isWebsiteBuildingDownloaded(b)) {
                                this.openDownloadedBuildingDetail(b);
                            } else if (this.websiteDownloading.contains(id)) {
                                this.setStatus("\u4e0b\u8f7d\u4e2d, \u8bf7\u7a0d\u5019", 0xFFAA55);
                            } else {
                                this.startWebsiteDownload(b);
                            }
                            return true;
                        }
                    }
                    for (Map.Entry<String, int[]> e : this.websiteCardHitRects.entrySet()) {
                        r = e.getValue();
                        if (mx < r[0] || mx > r[0] + r[2] || my < r[1] || my > r[1] + r[3]) continue;
                        id = e.getKey();
                        for (PackDownloadManager.BuildingInfo2 b : this.websiteBuildings) {
                            if (!id.equals(b.id)) continue;
                            if (this.isWebsiteBuildingDownloaded(b)) {
                                this.openDownloadedBuildingDetail(b);
                            } else {
                                this.setStatus("\u2717 \u8be5\u5efa\u7b51\u8fd8\u672a\u4e0b\u8f7d, \u5148\u70b9\u5e95\u90e8\u300c\u4e0b\u8f7d\u300d\u6309\u94ae", 0xAAAAAA);
                            }
                            return true;
                        }
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private int computePageSizeForCurrentTab() {
        if (this.currentTab == Tab.CLOUD) {
            int rh = 232;
            int listH = rh - 22;
            int cardH = 96;
            int cardGap = 6;
            return Math.max(1, (listH + cardGap) / (cardH + cardGap)) * 2;
        }
        if (this.currentTab == Tab.DOWNLOAD) {
            int rh = 232;
            int listH = rh - 22;
            int cardH = 70;
            int cardGap = 6;
            return Math.max(1, (listH + cardGap) / (cardH + cardGap)) * 2;
        }
        if (this.currentTab == Tab.SERVERS) {
            int rh = 232;
            int filterH = rh - 8;
            int tabH = 18;
            int listH = filterH - tabH - 4 - 14;
            int cardH = 56;
            int cardGap = 6;
            return Math.max(1, (listH + cardGap) / (cardH + cardGap)) * 2;
        }
        if (this.currentTab == Tab.BUILDINGS) {
            return 4;
        }
        return 6;
    }

    private int computeMaxPageForCurrentTab() {
        int total;
        int pageSize = this.computePageSizeForCurrentTab();
        if (this.currentTab == Tab.DOWNLOAD) {
            total = this.getFilteredWebsiteBuildings().size();
        } else if (this.currentTab == Tab.FAVORITES) {
            total = this.filterBySearch(ExtensionPackManager.getInstance().getFavoriteConstructions(), this.searchText).size();
        } else if (this.currentTab == Tab.CLOUD) {
            total = CloudBuildingClientCache.getInstance().size();
        } else if (this.currentTab == Tab.SERVERS) {
            List<ServerBuildingInfo> all = ExtensionPackManager.getInstance().getServerBuildings();
            int n = 0;
            for (ServerBuildingInfo b : all) {
                if (b == null || "unsynced".equals(this.serverFilter) && b.synced || "synced".equals(this.serverFilter) && !b.synced) continue;
                ++n;
            }
            total = n;
        } else {
            total = this.filterBySearch(this.getMergedConstructionsForBuildingsTab(), this.searchText).size();
        }
        return Math.max(0, (total + pageSize - 1) / pageSize - 1);
    }

    private List<PackDownloadManager.BuildingInfo2> getFilteredWebsiteBuildings() {
        ArrayList<PackDownloadManager.BuildingInfo2> result = new ArrayList<PackDownloadManager.BuildingInfo2>();
        for (PackDownloadManager.BuildingInfo2 b : this.websiteBuildings) {
            if (b == null || b.id == null) continue;
            boolean downloaded = PackDownloadManager.getInstance().isBuildingDownloaded(b);
            if ("all".equals(this.websiteFilter)) {
                result.add(b);
                continue;
            }
            if ("downloaded".equals(this.websiteFilter) && downloaded) {
                result.add(b);
                continue;
            }
            if (!"not_downloaded".equals(this.websiteFilter) || downloaded) continue;
            result.add(b);
        }
        return result;
    }

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
            if (this.searchBox != null) {
                this.searchBox.setValue("");
            }
            return;
        }
        if (button == this.btnSync) {
            ExtensionPackManager.getInstance().reloadClient();
            try {
                ServerPackSyncClient.getInstance().requestResync();
            }
            catch (Exception e) {
                PrefabCustomAddon.LOGGER.warn("[BROWSER] sync \u5931\u8d25: {}", (Object)e.getMessage());
            }
            this.setStatus("\u5df2\u8bf7\u6c42\u540c\u6b65\u670d\u52a1\u5668\u5efa\u7b51", 0x55AAFF);
            return;
        }
        if (button == this.btnCheckDeps) {
            this.runDependencyCheck();
            return;
        }
        if (button == this.btnOpenFolder) {
            boolean ok = FolderOpener.openExtensionFolder();
            if (ok) {
                this.setStatus("\u5df2\u6253\u5f00\u62d3\u5c55\u5305\u6587\u4ef6\u5939", 0x55FF55);
            } else {
                this.setStatus("\u6253\u5f00\u5931\u8d25, \u8bf7\u624b\u52a8\u8bbf\u95ee: " + String.valueOf(PackDownloadManager.getExtensionRoot()), 0xFFAA55);
            }
            return;
        }
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            if (this.pendingDeleteBuildingId != null) {
                this.pendingDeleteBuildingId = null;
                this.deleteConfirmCancelRect = null;
                this.deleteConfirmOkRect = null;
                this.setStatus("\u5df2\u53d6\u6d88\u5220\u9664", 0xAAAAAA);
                return true;
            }
            if (this.currentDrilldownPack != null) {
                this.currentDrilldownPack = null;
                this.btnBack.visible = false;
                this.scrollOffsetCards = 0;
                this.searchText = "";
                if (this.searchBox != null) {
                    this.searchBox.setValue("");
                }
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    public boolean charTyped(char codePoint, int modifiers) {
        if (this.searchBox == null || !this.searchBox.isVisible() || this.searchBox.isFocused()) {
            // empty if block
        }
        return super.charTyped(codePoint, modifiers);
    }

    private void switchTab(Tab tab) {
        String savedSearch;
        boolean savedPanelHidden;
        int savedScroll;
        if (tab == this.currentTab && this.currentDrilldownPack == null) {
            return;
        }
        if (this.currentTab != null) {
            TAB_STATES.put(this.currentTab, new TabState(this.currentCategory, this.scrollOffsetCards, this.categoryPanelHidden, this.searchText));
        }
        this.currentTab = tab;
        this.currentDrilldownPack = null;
        this.btnBack.visible = false;
        TabState saved = TAB_STATES.get((Object)tab);
        if (saved != null) {
            this.currentCategory = saved.currentCategory;
            savedScroll = saved.scrollOffsetCards;
            savedPanelHidden = saved.categoryPanelHidden;
            savedSearch = saved.searchText;
        } else {
            this.currentCategory = null;
            savedScroll = 0;
            savedPanelHidden = false;
            savedSearch = "";
        }
        this.categoryPanelHidden = savedPanelHidden;
        this.searchText = savedSearch;
        if (this.searchBox != null) {
            this.searchBox.setValue(this.searchText);
            this.rebuildSearchBox();
            this.scrollOffsetCards = savedScroll;
        } else {
            this.scrollOffsetCards = savedScroll;
        }
        if (tab == Tab.CLOUD) {
            XaeroWaypointBridge.forceProbe();
        }
        this.pendingDeleteBuildingId = null;
        this.deleteConfirmCancelRect = null;
        this.deleteConfirmOkRect = null;
        if (tab == Tab.DOWNLOAD) {
            this.refreshDownloadedBuildings();
            if (this.websiteBuildings.isEmpty() && !this.websiteBuildingsLoading) {
                this.refreshWebsiteBuildings();
            }
        }
    }

    private boolean isBuildingsTab() {
        return this.currentTab == Tab.BUILDINGS || this.currentDrilldownPack != null;
    }

    private void rebuildSearchBox() {
        int targetW;
        if (this.searchBox == null) {
            return;
        }
        int[] pos = this.computePanelPos();
        int grayBoxX = pos[0];
        int grayBoxY = pos[1];
        int sbX = grayBoxX + 72 + 6;
        int sbY = grayBoxY + 4;
        int n = targetW = this.isBuildingsTab() ? 110 : 316;
        if (this.searchBox.getWidth() == targetW) {
            return;
        }
        String current = this.searchBox.getValue();
        this.removeWidget((GuiEventListener)this.searchBox);
        this.searchBox = new EditBox(this.font, sbX, sbY, targetW, 16, (Component)Component.literal((String)GuiExtensionPackBrowser.tr("browser.search.placeholder", new Object[0])));
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
        this.currentCategory = null;
        if (this.searchBox != null) {
            this.searchBox.setValue("");
            this.rebuildSearchBox();
        }
    }

    private void openConstructionDetail(ConstructionInfo c) {
        rememberedCategory = this.currentCategory;
        rememberedPage = this.scrollOffsetCards;
        rememberedPanelHidden = this.categoryPanelHidden;
        GuiConstructionDetail.open(c);
    }

    private void openDownloadedBuildingDetail(PackDownloadManager.BuildingInfo2 b) {
        Object ext;
        if (b == null) {
            return;
        }
        String baseName = b.name == null || b.name.isEmpty() ? b.id : b.name;
        baseName = baseName.replaceAll("[\\\\/:*?\"<>|]", "_");
        Object object = ext = b.fileExt == null || b.fileExt.isEmpty() ? ".nbt" : b.fileExt;
        if (!((String)ext).startsWith(".")) {
            ext = "." + (String)ext;
        }
        String extNoDot = ((String)ext).substring(1);
        Path dlRoot = LocalBuildingScanner.getDownloadRoot();
        List<LocalBuilding> dlList = LocalBuildingScanner.scanDir(dlRoot, "download");
        for (LocalBuilding lb : dlList) {
            boolean extMatch;
            String lbExt = lb.fileExt == null ? "" : lb.fileExt;
            boolean idMatch = lb.id.equals(baseName);
            boolean bl = extMatch = lbExt.isEmpty() || lbExt.equalsIgnoreCase((String)ext) || lbExt.equalsIgnoreCase(extNoDot) || lbExt.startsWith(".") && lbExt.substring(1).equalsIgnoreCase(extNoDot);
            if (!idMatch || !extMatch) continue;
            ConstructionInfo c = new ConstructionInfo(lb.id);
            c.setName(lb.name == null || lb.name.isEmpty() ? b.name : lb.name);
            c.setAuthor(lb.author == null || lb.author.isEmpty() ? b.author : lb.author);
            c.setDescription(lb.description);
            c.setDependencies(lb.dependencies);
            c.setCategory(lb.category);
            c.setFormat(lb.fileExt == null ? "nbt" : lb.fileExt.replaceFirst("^\\.", ""));
            c.setLocalImagePath(lb.imagePath);
            c.setLocalNbtPath(lb.filePath);
            this.openConstructionDetail(c);
            return;
        }
        Path extRoot = LocalBuildingScanner.getExtensionRoot();
        List<LocalBuilding> extList = LocalBuildingScanner.scanDir(extRoot, "extension");
        for (LocalBuilding lb : extList) {
            boolean extMatch;
            String lbExt = lb.fileExt == null ? "" : lb.fileExt;
            boolean idMatch = lb.id.equals(baseName);
            boolean bl = extMatch = lbExt.isEmpty() || lbExt.equalsIgnoreCase((String)ext) || lbExt.equalsIgnoreCase(extNoDot) || lbExt.startsWith(".") && lbExt.substring(1).equalsIgnoreCase(extNoDot);
            if (!idMatch || !extMatch) continue;
            ConstructionInfo c = new ConstructionInfo(lb.id);
            c.setName(lb.name == null || lb.name.isEmpty() ? b.name : lb.name);
            c.setAuthor(lb.author == null || lb.author.isEmpty() ? b.author : lb.author);
            c.setDescription(lb.description);
            c.setDependencies(lb.dependencies);
            c.setCategory(lb.category);
            c.setFormat(lb.fileExt == null ? "nbt" : lb.fileExt.replaceFirst("^\\.", ""));
            c.setLocalImagePath(lb.imagePath);
            c.setLocalNbtPath(lb.filePath);
            this.openConstructionDetail(c);
            return;
        }
        this.setStatus("\u2717 \u627e\u4e0d\u5230\u5df2\u4e0b\u8f7d\u6587\u4ef6, \u8bd5\u91cd\u65b0\u4e0b\u8f7d: " + b.name, 0xFFAA55);
    }

    private void setStatus(String msg, int color) {
        this.statusMessage = msg;
        this.statusColor = color;
        this.statusTick = 200;
    }

    private void runDependencyCheck() {
        LinkedHashMap<String, List<String>> missing = new LinkedHashMap<String, List<String>>();
        ExtensionPackManager mgr = ExtensionPackManager.getInstance();
        for (ExtensionPack p : mgr.getPacksForGui()) {
            List<String> deps = p.getDependencies();
            if (deps == null || deps.isEmpty()) continue;
            DependencyChecker.CheckResult r = DependencyChecker.check(deps);
            if (r.missing.isEmpty()) continue;
            missing.put(p.getName(), r.missing);
        }
        if (missing.isEmpty()) {
            this.setStatus("\u2713 \u6240\u6709\u62d3\u5c55\u5305\u4f9d\u8d56\u90fd\u5df2\u6ee1\u8db3", 0x55FF55);
        } else {
            int totalMissing = missing.values().stream().mapToInt(List::size).sum();
            this.setStatus("\u2717 \u5171 " + totalMissing + " \u4e2a\u4f9d\u8d56\u7f3a\u5931 (\u70b9\u51fb\u4e0b\u65b9 [\u4f9d\u8d56\u8be6\u60c5] \u67e5\u770b)", 0xFFAA55);
        }
    }

    private List<ConstructionInfo> filterBySearch(List<ConstructionInfo> src, String q) {
        if (q == null || q.trim().isEmpty()) {
            return src;
        }
        String needle = q.trim().toLowerCase();
        ArrayList<ConstructionInfo> out = new ArrayList<ConstructionInfo>();
        for (ConstructionInfo c : src) {
            if (!GuiExtensionPackBrowser.matches(c.getName(), needle) && !GuiExtensionPackBrowser.matches(c.getAuthor(), needle) && !GuiExtensionPackBrowser.matches(c.getId(), needle)) continue;
            out.add(c);
        }
        return out;
    }

    private static boolean matches(String s, String needle) {
        return s != null && s.toLowerCase().contains(needle);
    }

    private void drawEmpty(GuiGraphics guiGraphics, int[] rect, String title, String hint) {
        int cx = rect[0] + rect[2] / 2;
        int cy = rect[1] + rect[3] / 2 - 10;
        guiGraphics.drawCenteredString(this.font, "\u00a77" + title, cx, cy, 0xAAAAAA);
        guiGraphics.drawCenteredString(this.font, hint, cx, cy + 14, 0x888888);
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    private ResourceLocation ensureCoverTextureLoaded(ExtensionPack p) {
        String key;
        String string = key = p.getPackageName() != null ? p.getPackageName() : p.getFileName();
        if (this.coverTextureCache.containsKey(key)) {
            return this.coverTextureCache.get(key);
        }
        try (ByteArrayInputStream is = new ByteArrayInputStream(p.getCoverImageData());){
            BufferedImage img = ImageIO.read(is);
            if (img == null) {
                ResourceLocation resourceLocation = null;
                return resourceLocation;
            }
            int w = img.getWidth();
            int h = img.getHeight();
            DynamicTexture tex = new DynamicTexture(w, h, false);
            tex.setFilter(false, false);
            for (int y = 0; y < h; ++y) {
                for (int x = 0; x < w; ++x) {
                    int argb = img.getRGB(x, y);
                    int abgr = argb & 0xFF00FF00 | (argb & 0xFF0000) >> 16 | (argb & 0xFF) << 16;
                    tex.getPixels().setPixelRGBA(x, y, abgr);
                }
            }
            tex.upload();
            ResourceLocation loc = Minecraft.getInstance().getTextureManager().register("prefab_cover_" + key, tex);
            this.coverTextureCache.put(key, loc);
            ResourceLocation resourceLocation = loc;
            return resourceLocation;
        }
        catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("Failed to load cover image for {}", (Object)p.getName(), (Object)e);
            this.coverTextureCache.put(key, null);
            return null;
        }
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    private ResourceLocation ensurePreviewTextureLoaded(ConstructionInfo c) {
        String key = c.getId();
        if (this.previewTextureCache.containsKey(key)) {
            return this.previewTextureCache.get(key);
        }
        if (!c.hasPreviewImage()) {
            PrefabCustomAddon.LOGGER.info("[PREVIEW] {} \u65e0\u56fe: pngData={} localImagePath={} exists={}", new Object[]{key, c.getPngData() != null ? Integer.valueOf(c.getPngData().length) : "null", c.getLocalImagePath(), c.getLocalImagePath() != null ? Boolean.valueOf(Files.exists(c.getLocalImagePath(), new LinkOption[0])) : "n/a"});
            this.previewTextureCache.put(key, null);
            return null;
        }
        byte[] data = c.getPngData();
        if ((data == null || data.length == 0) && c.getLocalImagePath() != null) {
            try {
                data = Files.readAllBytes(c.getLocalImagePath());
                PrefabCustomAddon.LOGGER.info("[PREVIEW] {} \u4ece {} \u8bfb\u5230 {} bytes", new Object[]{key, c.getLocalImagePath(), data.length});
            }
            catch (Exception e) {
                PrefabCustomAddon.LOGGER.warn("[PREVIEW] {} \u8bfb {} \u5931\u8d25: {}", new Object[]{key, c.getLocalImagePath(), e.getMessage()});
            }
        }
        if (data == null || data.length == 0) {
            PrefabCustomAddon.LOGGER.info("[PREVIEW] {} data \u4e3a\u7a7a, \u8df3\u8fc7", (Object)key);
            this.previewTextureCache.put(key, null);
            return null;
        }
        try (ByteArrayInputStream is = new ByteArrayInputStream(data);){
            BufferedImage img = ImageIO.read(is);
            if (img == null) {
                PrefabCustomAddon.LOGGER.warn("[PREVIEW] {} ImageIO \u89e3\u6790\u5931\u8d25 ({} bytes), \u6587\u4ef6\u53ef\u80fd\u635f\u574f\u6216\u683c\u5f0f\u4e0d\u652f\u6301", (Object)key, (Object)data.length);
                this.previewTextureCache.put(key, null);
                ResourceLocation resourceLocation = null;
                return resourceLocation;
            }
            DynamicTexture tex = GuiExtensionPackBrowser.uploadIconTexture(img);
            if (tex == null) {
                PrefabCustomAddon.LOGGER.warn("[PREVIEW] {} uploadIconTexture \u8fd4\u56de null (\u6e90 {}x{})", new Object[]{key, img.getWidth(), img.getHeight()});
                this.previewTextureCache.put(key, null);
                ResourceLocation resourceLocation = null;
                return resourceLocation;
            }
            ResourceLocation loc = Minecraft.getInstance().getTextureManager().register("prefab_preview_" + key, tex);
            PrefabCustomAddon.LOGGER.info("[PREVIEW] {} \u4e0a\u4f20\u6210\u529f {}x{} \u2192 {}", new Object[]{key, img.getWidth(), img.getHeight(), loc});
            this.previewTextureCache.put(key, loc);
            ResourceLocation resourceLocation = loc;
            return resourceLocation;
        }
        catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("Failed to load preview for {}", (Object)c.getName(), (Object)e);
            this.previewTextureCache.put(key, null);
            return null;
        }
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    private ResourceLocation ensureCachedThumbnailTextureLoaded(ConstructionInfo c, byte[] pngData) {
        String key = ThumbnailCache.fingerprint(c);
        if (this.cachedThumbTextureCache.containsKey(key)) {
            return this.cachedThumbTextureCache.get(key);
        }
        try (ByteArrayInputStream is = new ByteArrayInputStream(pngData);){
            BufferedImage img = ImageIO.read(is);
            if (img == null) {
                this.cachedThumbTextureCache.put(key, null);
                ResourceLocation resourceLocation = null;
                return resourceLocation;
            }
            DynamicTexture tex = GuiExtensionPackBrowser.uploadIconTexture(img);
            if (tex == null) {
                this.cachedThumbTextureCache.put(key, null);
                ResourceLocation resourceLocation = null;
                return resourceLocation;
            }
            ResourceLocation loc = Minecraft.getInstance().getTextureManager().register("prefab_thumb_" + key, tex);
            this.cachedThumbTextureCache.put(key, loc);
            ResourceLocation resourceLocation = loc;
            return resourceLocation;
        }
        catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("Failed to load cached thumbnail for {}", (Object)c.getName(), (Object)e);
            this.cachedThumbTextureCache.put(key, null);
            return null;
        }
    }

    private void drawIconNearest(GuiGraphics guiGraphics, ResourceLocation texture, int x, int y, int w, int h, int u, int v, int uW, int vH, int sheetW, int sheetH) {
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture((int)0, (ResourceLocation)texture);
        RenderSystem.texParameter((int)3553, (int)10241, (int)9729);
        RenderSystem.texParameter((int)3553, (int)10240, (int)9729);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        float f = 1.0f / (float)sheetW;
        float f1 = 1.0f / (float)sheetH;
        float u0 = (float)u * f;
        float v0 = (float)v * f1;
        float u1 = (float)(u + uW) * f;
        float v1 = (float)(v + vH) * f1;
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        buffer.addVertex((float)x, (float)(y + h), 0.0f).setUv(u0, v1).setColor(1.0f, 1.0f, 1.0f, 1.0f);
        buffer.addVertex((float)(x + w), (float)(y + h), 0.0f).setUv(u1, v1).setColor(1.0f, 1.0f, 1.0f, 1.0f);
        buffer.addVertex((float)(x + w), (float)y, 0.0f).setUv(u1, v0).setColor(1.0f, 1.0f, 1.0f, 1.0f);
        buffer.addVertex((float)x, (float)y, 0.0f).setUv(u0, v0).setColor(1.0f, 1.0f, 1.0f, 1.0f);
        BufferUploader.drawWithShader((MeshData)buffer.build());
    }

    public void onClose() {
        rememberedCategory = this.currentCategory;
        rememberedPage = this.scrollOffsetCards;
        rememberedPanelHidden = this.categoryPanelHidden;
        this.coverTextureCache.clear();
        this.previewTextureCache.clear();
        this.cachedThumbTextureCache.clear();
        this.localImageCache.clear();
        this.websiteImageCache.clear();
        this.serverImageCache.clear();
        super.onClose();
    }

    private void openSyncedServerBuildingDetail(ServerBuildingInfo b) {
        if (b == null || !b.synced) {
            return;
        }
        Path cacheDir = ExtensionPackManager.getInstance().getServerCacheDir();
        if (cacheDir == null || !Files.exists(cacheDir, new LinkOption[0])) {
            this.setStatus("\u2717 \u627e\u4e0d\u5230 server-cache/ \u76ee\u5f55", 0xFF5555);
            return;
        }
        String fileBase = b.packName != null && !b.packName.isEmpty() ? b.packName : b.buildingId;
        String ext = b.getSourceExt().toLowerCase();
        if (ext.equals(".zip")) {
            Path zipPath = GuiExtensionPackBrowser.findServerFile(cacheDir, fileBase, ".zip");
            if (zipPath == null) {
                this.setStatus("\u2717 \u627e\u4e0d\u5230 zip: " + fileBase + ".zip", 0xFF5555);
                return;
            }
            try (ZipFile zip = new ZipFile(zipPath.toFile());){
                InputStream is;
                ZipEntry nbtEntry = null;
                ZipEntry pngEntry = null;
                Enumeration<? extends ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory()) continue;
                    String ename = e.getName().toLowerCase();
                    if (nbtEntry == null && (ename.endsWith(".nbt") || ename.endsWith(".litematic") || ename.endsWith(".schem") || ename.endsWith(".schematic"))) {
                        nbtEntry = e;
                    }
                    if (pngEntry != null || !ename.endsWith(".png")) continue;
                    pngEntry = e;
                }
                if (nbtEntry == null) {
                    this.setStatus("\u2717 zip " + fileBase + " \u91cc\u6ca1\u627e\u5230\u5efa\u7b51\u6587\u4ef6", 0xFF5555);
                    return;
                }
                long nbtSize = nbtEntry.getSize();
                if (nbtSize > 0x1400000L) {
                    this.setStatus("\u2717 \u5efa\u7b51\u592a\u5927 (" + nbtSize / 1024L / 1024L + "MB), \u4e0d\u652f\u6301\u5728\u7ebf\u9884\u89c8", 0xFFAA55);
                    return;
                }
                ConstructionInfo c = new ConstructionInfo(b.buildingId);
                c.setName(b.getDisplayName());
                if (pngEntry != null) {
                    is = zip.getInputStream(pngEntry);
                    try {
                        c.setPngData(is.readAllBytes());
                    }
                    finally {
                        if (is != null) {
                            is.close();
                        }
                    }
                }
                is = zip.getInputStream(nbtEntry);
                try {
                    c.setNbtData(is.readAllBytes());
                }
                finally {
                    if (is != null) {
                        is.close();
                    }
                }
                GuiConstructionDetail.open(c);
            }
            catch (Exception e) {
                this.setStatus("\u2717 \u89e3\u6790 zip \u5931\u8d25: " + e.getMessage(), 0xFF5555);
                PrefabCustomAddon.LOGGER.warn("[SERVER-DETAIL] zip parse failed for {}", (Object)fileBase, (Object)e);
            }
        } else {
            Path filePath = GuiExtensionPackBrowser.findServerFile(cacheDir, fileBase, ext);
            if (filePath == null) {
                this.setStatus("\u2717 \u627e\u4e0d\u5230\u6587\u4ef6: " + fileBase + ext, 0xFF5555);
                return;
            }
            Path imgPath = GuiExtensionPackBrowser.findServerFile(cacheDir, fileBase, ".png");
            if (imgPath == null) {
                String ie;
                String[] nbtEntry = new String[]{".jpg", ".jpeg", ".webp"};
                int n = nbtEntry.length;
                for (int i = 0; i < n && (imgPath = GuiExtensionPackBrowser.findServerFile(cacheDir, fileBase, ie = nbtEntry[i])) == null; ++i) {
                }
            }
            ConstructionInfo c = new ConstructionInfo(b.buildingId);
            c.setName(b.getDisplayName());
            c.setLocalImagePath(imgPath);
            c.setLocalNbtPath(filePath);
            GuiConstructionDetail.open(c);
        }
    }

    private static enum Tab {
        BUILDINGS("\u5efa\u7b51"),
        CLOUD("\u4e91\u7aef\u5efa\u7b51"),
        SERVERS("\u670d\u52a1\u5668"),
        FAVORITES("\u6536\u85cf"),
        DOWNLOAD("\u4e0b\u8f7d"),
        CHANGELOG("\u66f4\u65b0\u65e5\u5fd7");

        final String label;

        private Tab(String label) {
            this.label = label;
        }
    }

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
}

