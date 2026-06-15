package com.prefab.addon.client.gui;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.config.AddonConfig;
import com.prefab.addon.download.PackDownloadManager;
import com.prefab.gui.GuiBase;
import com.prefab.gui.controls.ExtendedButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 拓展包下载界面
 * - 显示服务器上所有拓展包
 * - 点单个 → 下载
 * - 下载中显示进度条
 * - 下载完成后该包出现在游戏内的拓展包管理里
 */
public class GuiExtensionPackDownloader extends GuiBase {

    // 布局 (放大一点, 类似原版菜单)
    private static final int LIST_ITEM_H = 28;
    private int VISIBLE_ROWS = 5;  // 根据 listHeight 动态调整

    private int listX, listY, listWidth, listHeight;
    private int listContentY;

    // 数据
    private final List<PackDownloadManager.PackInfo> packs = new ArrayList<>();
    private int scrollOffset = 0;
    private int selectedIndex = -1;
    private boolean loading = false;
    private String errorMessage = null;
    private int refreshTick = 0;

    // 下载状态
    private boolean downloading = false;
    private String downloadingPackId = null;
    private double downloadProgress = 0;  // 0-100
    private long downloadDownloaded = 0;
    private long downloadTotal = 0;
    private String downloadStatus = null;  // null=空闲, "完成", "失败:..."

    // 按钮
    private ExtendedButton btnDownload;
    private ExtendedButton btnRefresh;
    private ExtendedButton btnBack;
    private ExtendedButton btnPublish;  // 打开网页版发布页

    public GuiExtensionPackDownloader() {
        super("Extension Pack Downloader");
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new GuiExtensionPackDownloader());
    }

    @Override
    protected void Initialize() {
        super.Initialize();
        // 适配小屏幕: 宽高都按屏幕尺寸自适应
        int targetHeight = Math.min(290, (int)(this.height * 0.85));
        if (targetHeight < 200) targetHeight = 200;
        // 宽度: 屏幕宽度 - 边距 (留 8px 安全区)
        int targetWidth = Math.min(460, this.width - 16);
        if (targetWidth < 320) targetWidth = 320;
        int halfHeight = targetHeight / 2;
        int halfWidth = targetWidth / 2;

        this.modifiedInitialXAxis = halfWidth;
        this.modifiedInitialYAxis = halfHeight;
        this.imagePanelWidth = targetWidth;
        this.imagePanelHeight = targetHeight;
        this.shownImageHeight = 1;
        this.shownImageWidth = 1;

        int grayBoxX = (this.width / 2) - this.modifiedInitialXAxis;
        int grayBoxY = (this.height / 2) - this.modifiedInitialYAxis;

        // 左面板: 列表 (占 60% 宽度)
        this.listWidth = (int)(targetWidth * 0.58f) - 12;
        this.listHeight = targetHeight - 70;
        this.listX = grayBoxX + 8;
        this.listY = grayBoxY + 30;
        this.listContentY = this.listY + 4;
        this.VISIBLE_ROWS = Math.max(3, this.listHeight / LIST_ITEM_H);

        // 按钮: 放在面板**内部**底部 (保证小屏也能点到)
        int btnY = grayBoxY + targetHeight - 26;
        this.btnDownload = this.createAndAddButton(grayBoxX + 8, btnY, 75, 20, "下载选中");
        this.btnRefresh = this.createAndAddButton(grayBoxX + 88, btnY, 60, 20, "刷新");
        this.btnPublish = this.createAndAddButton(grayBoxX + 153, btnY, 75, 20, "发布拓展包");
        this.btnBack = this.createAndAddButton(grayBoxX + targetWidth - 103, btnY, 95, 20, "返回");
        this.btnDownload.active = false;

        // 加载列表
        fetchList();
    }

    private void fetchList() {
        loading = true;
        errorMessage = null;
        packs.clear();
        PackDownloadManager.getInstance().fetchPackListAsync()
            .whenComplete((result, err) -> {
                Minecraft.getInstance().execute(() -> {
                    loading = false;
                    if (err != null) {
                        errorMessage = "无法连接服务器: " + err.getCause().getMessage();
                    } else {
                        packs.addAll(result);
                    }
                });
            });
    }

    @Override
    public void tick() {
        super.tick();
        refreshTick++;
        if (refreshTick % 40 == 0 && !loading && !downloading) {
            // 每 2 秒可手动刷新
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (packs.isEmpty()) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        if (scrollY > 0 && scrollOffset > 0) {
            scrollOffset--;
            return true;
        }
        if (scrollY < 0 && scrollOffset < packs.size() - VISIBLE_ROWS) {
            scrollOffset++;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // 检测列表点击
            for (int i = 0; i < Math.min(VISIBLE_ROWS, packs.size()); i++) {
                int idx = i + scrollOffset;
                if (idx >= packs.size()) break;
                int iy = listContentY + i * LIST_ITEM_H;
                if (mouseX >= listX && mouseX <= listX + listWidth
                    && mouseY >= iy && mouseY < iy + LIST_ITEM_H) {
                    selectedIndex = idx;
                    this.btnDownload.active = !downloading;
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void buttonClicked(AbstractButton button) {
        if (button == this.btnRefresh) {
            if (!loading && !downloading) fetchList();
            return;
        }
        if (button == this.btnBack) {
            this.onClose();
            return;
        }
        if (button == this.btnDownload) {
            if (selectedIndex >= 0 && selectedIndex < packs.size() && !downloading) {
                PackDownloadManager.PackInfo p = packs.get(selectedIndex);
                startDownload(p.id);
            }
        }
        if (button == this.btnPublish) {
            // 打开系统默认浏览器到 web 发布页
            String url = AddonConfig.getServerUrl();
            if (url == null || url.isEmpty()) {
                this.downloadStatus = "未配置服务器地址 (config/prefab_custom_addon-common.toml)";
                return;
            }
            try {
                // 优先用 Minecraft 自带 API (兼容性更好, 会在主线程外执行)
                net.minecraft.Util.getPlatform().openUri(java.net.URI.create(url));
                this.downloadStatus = "已打开发布页面: " + url;
                PrefabCustomAddon.LOGGER.info("[DOWNLOAD] Opened browser: {}", url);
            } catch (Throwable t1) {
                PrefabCustomAddon.LOGGER.warn("[DOWNLOAD] openUri failed, trying Desktop.browse", t1);
                try {
                    java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
                    this.downloadStatus = "已打开发布页面: " + url;
                } catch (Throwable t2) {
                    // 最后兜底: 把 URL 复制到剪贴板, 用户自己粘贴
                    try {
                        Minecraft.getInstance().keyboardHandler.setClipboard(url);
                    } catch (Throwable ignored) {}
                    this.downloadStatus = "无法打开浏览器, URL 已复制: " + url;
                    PrefabCustomAddon.LOGGER.error("[DOWNLOAD] All open methods failed", t2);
                }
            }
        }
    }

    private void startDownload(String packId) {
        downloading = true;
        downloadingPackId = packId;
        downloadProgress = 0;
        downloadDownloaded = 0;
        downloadTotal = 0;
        downloadStatus = "下载中...";
        this.btnDownload.active = false;
        this.btnRefresh.active = false;

        PackDownloadManager.getInstance().downloadPackStreaming(packId, new PackDownloadManager.ProgressCallback() {
            @Override public void onStart(String id) {
                Minecraft.getInstance().execute(() -> downloadStatus = "下载中...");
            }
            @Override public void onProgress(long downloaded, long total, double percent) {
                Minecraft.getInstance().execute(() -> {
                    downloadDownloaded = downloaded;
                    downloadTotal = total;
                    downloadProgress = percent;
                });
            }
            @Override public void onComplete(java.nio.file.Path savedTo) {
                Minecraft.getInstance().execute(() -> {
                    downloading = false;
                    downloadStatus = "完成: " + savedTo.getFileName();
                    this_refreshGui();
                });
            }
            @Override public void onError(String error) {
                Minecraft.getInstance().execute(() -> {
                    downloading = false;
                    downloadStatus = "失败: " + error;
                    this_refreshGui();
                });
            }
        });
    }

    private void this_refreshGui() {
        btnDownload.active = !downloading && selectedIndex >= 0;
        btnRefresh.active = !downloading;
    }

    @Override
    protected void preButtonRender(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, float partialTicks) {
        this.drawControlBackground(guiGraphics, x, y, this.imagePanelWidth, this.imagePanelHeight);
    }

    @Override
    protected void postButtonRender(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, float partialTicks) {
        // 标题
        guiGraphics.drawCenteredString(this.font, "拓展包下载 - " + AddonConfig.getServerUrl(),
            this.getCenteredXAxis(), (this.height / 2) - this.modifiedInitialYAxis + 4, this.textColor);
        guiGraphics.drawCenteredString(this.font, "(下载完成后请重新打开 Z 键管理界面即可看到)",
            this.getCenteredXAxis(), (this.height / 2) - this.modifiedInitialYAxis + 16, 0xAAAAAA);

        // 左面板: 列表
        guiGraphics.drawString(this.font, "§l服务器拓展包",
            this.listX + 4, this.listY - 14, this.textColor);
        guiGraphics.fill(this.listX, this.listY, this.listX + this.listWidth,
            this.listY + this.listHeight, 0xFF1A1A1A);

        if (loading) {
            guiGraphics.drawCenteredString(this.font, "加载中...",
                this.listX + this.listWidth / 2, this.listY + this.listHeight / 2, 0xAAAAAA);
        } else if (errorMessage != null) {
            guiGraphics.drawCenteredString(this.font, "✗",
                this.listX + this.listWidth / 2, this.listY + this.listHeight / 2 - 20, 0xFF5555);
            List<net.minecraft.util.FormattedCharSequence> lines = font.split(
                net.minecraft.network.chat.FormattedText.of(errorMessage), this.listWidth - 10);
            int ly = this.listY + this.listHeight / 2;
            for (net.minecraft.util.FormattedCharSequence l : lines) {
                guiGraphics.drawString(this.font, l, this.listX + 5, ly, 0xFF8888);
                ly += 11;
            }
        } else if (packs.isEmpty()) {
            guiGraphics.drawCenteredString(this.font, "服务器上还没有拓展包",
                this.listX + this.listWidth / 2, this.listY + this.listHeight / 2, 0x888888);
        } else {
            for (int i = 0; i < Math.min(VISIBLE_ROWS, packs.size()); i++) {
                int idx = i + scrollOffset;
                if (idx >= packs.size()) break;
                int iy = listContentY + i * LIST_ITEM_H;
                PackDownloadManager.PackInfo p = packs.get(idx);
                int bg;
                if (idx == selectedIndex) bg = 0xFF4A6FA5;
                else if (mouseX >= listX && mouseX <= listX + listWidth
                    && mouseY >= iy && mouseY < iy + LIST_ITEM_H) bg = 0xFF3A3A3A;
                else bg = 0xFF2A2A2A;
                guiGraphics.fill(this.listX, iy, this.listX + this.listWidth, iy + LIST_ITEM_H - 1, bg);
                String name = p.name;
                int maxName = this.listWidth / 6;
                if (name.length() > maxName) name = name.substring(0, maxName - 2) + "..";
                guiGraphics.drawString(this.font, name, this.listX + 6, iy + 4, 0xFFFFFF);
                String sub = "v" + p.version + " | " + p.buildings + "建筑 | 下载:" + p.downloads;
                int maxSub = this.listWidth / 6;
                if (sub.length() > maxSub) sub = sub.substring(0, maxSub - 2) + "..";
                guiGraphics.drawString(this.font, sub, this.listX + 6, iy + 14, 0xAAAAAA);
            }
        }

        // 右面板: 详情
        int detailX = listX + listWidth + 12;
        int detailY = listY;
        int grayBoxX = (this.width / 2) - this.modifiedInitialXAxis;
        int detailW = (grayBoxX + this.imagePanelWidth) - detailX - 8;
        int detailH = listHeight;
        guiGraphics.fill(detailX, detailY, detailX + detailW, detailY + detailH, 0xFF1A1A1A);
        guiGraphics.drawString(this.font, "§l详情",
            detailX + 6, detailY - 12, this.textColor);

        if (selectedIndex >= 0 && selectedIndex < packs.size()) {
            PackDownloadManager.PackInfo p = packs.get(selectedIndex);
            int dy = detailY + 6;
            int rowH = 11;
            int labelW = 28;  // "标识符:" 这种标签占 4 个中文字符
            // 名称 (加粗)
            String nameStr = p.name == null ? "" : p.name;
            guiGraphics.drawString(this.font, "§l" + truncateForWidth(nameStr, this.font, detailW - 14),
                detailX + 6, dy, this.textColor);
            dy += 13;
            // 其他字段
            String[][] rows = {
                {"标识符:", p.id == null ? "" : p.id},
                {"作者:", p.author == null ? "" : p.author},
                {"版本:", p.version == null ? "" : p.version},
                {"依赖:", p.dependencies == null ? "" : p.dependencies},
                {"链接:", p.link == null ? "" : p.link},
                {"建筑:", String.valueOf(p.buildings)},
            };
            for (String[] row : rows) {
                if (dy > detailY + detailH - 12) break;
                // 标签 (浅灰)
                guiGraphics.drawString(this.font, row[0],
                    detailX + 6, dy, 0xAAAAAA);
                // 值 (白)
                String val = row[1] == null ? "" : row[1];
                if (val.isEmpty()) val = "-";
                String valFitted = truncateForWidth(val, this.font, detailW - 14 - labelW);
                guiGraphics.drawString(this.font, valFitted,
                    detailX + 6 + labelW, dy, 0xFFFFFF);
                dy += rowH;
            }
            dy += 4;
            // 描述
            String desc = p.description == null ? "" : p.description;
            List<net.minecraft.util.FormattedCharSequence> dlines = font.split(
                net.minecraft.network.chat.FormattedText.of(desc), detailW - 12);
            for (net.minecraft.util.FormattedCharSequence l : dlines) {
                if (dy > detailY + detailH - 12) break;
                guiGraphics.drawString(this.font, l, detailX + 6, dy, 0xFFDDCC55);
                dy += 11;
            }
        } else {
            guiGraphics.drawCenteredString(this.font, "← 选择左侧",
                detailX + detailW / 2, detailY + detailH / 2, 0x888888);
        }

        // 下载状态条
        if (downloading || downloadStatus != null) {
            int grayBoxY = (this.height / 2) - this.modifiedInitialYAxis;
            int sbX = (this.width / 2) - this.modifiedInitialXAxis;
            int sbY = grayBoxY + this.imagePanelHeight - 26 - 28;  // 在按钮上方
            int sbW = this.imagePanelWidth - 16;
            // 进度条
            guiGraphics.fill(sbX + 4, sbY, sbX + sbW, sbY + 8, 0xFF333333);
            int pW = (int) (sbW * Math.min(100, downloadProgress) / 100.0);
            guiGraphics.fill(sbX + 4, sbY, sbX + 4 + pW, sbY + 8, 0xFF4A8FCC);
            String info = downloadingPackId == null ? "" : downloadingPackId;
            if (downloadTotal > 0) {
                info = info + "  " + formatBytes(downloadDownloaded) + " / " + formatBytes(downloadTotal)
                    + "  (" + String.format("%.1f", downloadProgress) + "%)";
            }
            guiGraphics.drawString(this.font, info, sbX + 4, sbY - 12, 0xFFFFFF);
        }
    }

    /** 按像素宽度截断字符串, 避免超界 */
    private String truncateForWidth(String s, net.minecraft.client.gui.Font font, int maxPx) {
        if (s == null) return "";
        if (font.width(s) <= maxPx) return s;
        // 逐步缩短
        String suffix = "..";
        for (int len = s.length() - 1; len > 0; len--) {
            String candidate = s.substring(0, len) + suffix;
            if (font.width(candidate) <= maxPx) return candidate;
        }
        return suffix;
    }

    private String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n - 2) + ".." : s;
    }

    private String formatBytes(long b) {
        if (b < 1024) return b + "B";
        if (b < 1024 * 1024) return String.format("%.1fKB", b / 1024.0);
        return String.format("%.2fMB", b / 1024.0 / 1024.0);
    }
}
