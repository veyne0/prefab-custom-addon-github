package com.prefab.addon.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.ConstructionInfo;
import com.prefab.addon.extension.ExtensionPack;
import com.prefab.addon.extension.ExtensionPackManager;
import com.prefab.gui.GuiBase;
import com.prefab.gui.controls.ExtendedButton;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.resources.ResourceLocation;

/**
 * Z 键打开的拓展包管理界面。
 *
 * 设计灵感来自图片里的"扩展包商店"网页（信息详尽、分标签展示），
 * 但本 GUI 在 Minecraft 界面里只展示**最核心**的内容：
 *  - 左：拓展包列表（**仅标准格式**——有 information/ 子目录的）
 *  - 右：选中包的详情
 *      顶部：封面图 + 包名 + 作者 + 版本 + 描述
 *      底部：建筑列表
 *
 * 扁平格式的拓展包（文件直接放根目录的，如 拓展包示例2）**不会**在这里显示——
 * 那种拓展包没有 information/ 元信息。
 */
public class GuiExtensionPackBrowser extends GuiBase {

    private final List<ExtensionPack> discoverablePacks;
    private int selectedPackIndex = -1;  // -1 表示未选中
    private int scrollOffsetConstructions = 0;
    private int visibleConstructions = 4;  // 减少可见行数，让 GUI 更紧凑

    // 常量：绘制与点击都使用这些值（保持一致）
    private static final int PACK_ITEM_H = 30;     // 包列表每行高度
    private static final int CONST_ITEM_H = 24;    // 建筑列表每行高度

    // 左面板
    private int listX, listY, listWidth, listHeight;
    private int listContentY;  // 第一个列表项的 Y（列表头下方）

    // 右面板（详情区）
    private int detailX, detailY, detailWidth, detailHeight;

    // 建筑列表的起始 Y（draw 和 click 共用，避免偏移）
    private int constListY0;

    // 按钮
    private ExtendedButton btnClose;
    private ExtendedButton btnDownload;  // 打开下载界面

    // 缓存封面图纹理
    private ResourceLocation coverTextureLocation;

    /** 静态记忆：上次打开时选中的拓展包索引。关闭 detail 回到这里时恢复。 */
    private static int rememberedPackIndex = -1;
    private static String rememberedPackName = null;

    public GuiExtensionPackBrowser() {
        super("Extension Pack Browser");
        this.discoverablePacks = ExtensionPackManager.getInstance().getDiscoverablePacks();
        PrefabCustomAddon.LOGGER.info("[PACK-BROWSER] Opened with {} discoverable packs", discoverablePacks.size());

        // 恢复上次选中的包（从详情返回时使用）
        if (rememberedPackIndex >= 0 && rememberedPackIndex < this.discoverablePacks.size()
            && this.discoverablePacks.get(rememberedPackIndex).getName().equals(rememberedPackName)) {
            this.selectedPackIndex = rememberedPackIndex;
        }
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new GuiExtensionPackBrowser());
    }

    /** 当要打开详情时调用，记住当前选中的包索引和名称。 */
    public static void rememberSelection(int index, String name) {
        rememberedPackIndex = index;
        rememberedPackName = name;
    }

    /** 打开详情时调用，避免关闭后回到时 selectedPackIndex 还是 0。 */
    public static void clearRememberedSelection() {
        rememberedPackIndex = -1;
        rememberedPackName = null;
    }

    @Override
    protected void Initialize() {
        super.Initialize();
        // 紧凑布局：460x260 面板，留出顶部标题和底部按钮空间
        this.modifiedInitialXAxis = 230;
        this.modifiedInitialYAxis = 130;
        this.imagePanelWidth = 460;
        this.imagePanelHeight = 260;
        this.shownImageHeight = 1;
        this.shownImageWidth = 1;

        int grayBoxX = (this.width / 2) - this.modifiedInitialXAxis;
        int grayBoxY = (this.height / 2) - this.modifiedInitialYAxis;

        // 左面板：拓展包列表
        this.listWidth = 130;
        this.listHeight = 200;
        this.listX = grayBoxX + 8;
        this.listY = grayBoxY + 26;  // 紧贴标题下方
        this.listContentY = this.listY + 18;  // 列表头占 18px

        // 右面板：详情
        this.detailX = grayBoxX + 148;
        this.detailY = grayBoxY + 26;
        this.detailWidth = 304;
        this.detailHeight = 200;

        // 建筑列表起始 Y（必须与 draw 一致，click 检测用）
        this.constListY0 = this.detailY + 100;

        // 关闭按钮（放在面板底部中央）
        this.btnClose = this.createAndAddButton(
            grayBoxX + 280, grayBoxY + 232, 75, 20, "关闭");
        // 下载拓展包按钮
        this.btnDownload = this.createAndAddButton(
            grayBoxX + 100, grayBoxY + 232, 120, 20, "下载拓展包");
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // 检测左面板点击 → 切换选中包
            for (int i = 0; i < this.discoverablePacks.size(); i++) {
                int itemY = this.listContentY + i * PACK_ITEM_H;
                if (mouseX >= this.listX && mouseX <= this.listX + this.listWidth
                    && mouseY >= itemY && mouseY < itemY + PACK_ITEM_H) {
                    this.selectedPackIndex = i;
                    this.scrollOffsetConstructions = 0;
                    this.coverTextureLocation = null;  // 重新加载封面
                    return true;
                }
            }
            // 检测建筑列表点击 → 打开建筑详细界面（仅查看）
            if (this.selectedPackIndex >= 0) {
                for (int j = 0; j < this.visibleConstructions; j++) {
                    int constIdx = j + this.scrollOffsetConstructions;
                    if (constIdx >= currentConstructions().size()) break;
                    int iy = this.constListY0 + j * CONST_ITEM_H;
                    if (mouseX >= this.detailX && mouseX <= this.detailX + this.detailWidth
                        && mouseY >= iy && mouseY < iy + CONST_ITEM_H) {
                        // 记住当前选中的包，关闭详情后能恢复
                        GuiExtensionPackBrowser.rememberSelection(
                            this.selectedPackIndex,
                            this.discoverablePacks.get(this.selectedPackIndex).getName()
                        );
                        GuiExtensionConstructionDetail.open(currentConstructions().get(constIdx));
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.selectedPackIndex >= 0 && mouseX >= this.detailX && mouseX <= this.detailX + this.detailWidth) {
            int max = Math.max(0, currentConstructions().size() - this.visibleConstructions);
            this.scrollOffsetConstructions = Math.max(0, Math.min(max, this.scrollOffsetConstructions - (int) scrollY));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private List<ConstructionInfo> currentConstructions() {
        if (this.selectedPackIndex < 0) return new ArrayList<>();
        return this.discoverablePacks.get(this.selectedPackIndex).getConstructions();
    }

    @Override
    protected void preButtonRender(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, float partialTicks) {
        // Prefab 背景
        this.drawControlBackground(guiGraphics, x, y, this.imagePanelWidth, this.imagePanelHeight);
        // 左面板装饰
        this.drawControlLeftPanel(guiGraphics, this.listX - 6, this.listY - 6, this.listWidth + 12, this.listHeight + 12);
        // 右面板装饰
        this.drawControlLeftPanel(guiGraphics, this.detailX - 6, this.detailY - 6, this.detailWidth + 12, this.detailHeight + 12);
    }

    @Override
    protected void postButtonRender(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, float partialTicks) {
        // 标题（顶部，紧凑）
        guiGraphics.drawCenteredString(this.font, "拓展包管理", this.getCenteredXAxis(), y + 4, this.textColor);
        guiGraphics.drawCenteredString(this.font, "手持蓝图按 Z 打开此界面",
                this.getCenteredXAxis(), y + 16, 0xAAAAAA);

        // 左面板：包列表
        drawPackList(guiGraphics, mouseX, mouseY);

        // 右面板：包详情
        if (this.selectedPackIndex >= 0) {
            drawPackDetails(guiGraphics, mouseX, mouseY);
        } else {
            // 提示选择包
            guiGraphics.drawCenteredString(this.font, "← 请选择左侧的拓展包",
                    this.detailX + this.detailWidth / 2, this.detailY + this.detailHeight / 2 - 6, 0xAAAAAA);
            guiGraphics.drawCenteredString(this.font, "(只显示带 information/ 子目录的标准格式包)",
                    this.detailX + this.detailWidth / 2, this.detailY + this.detailHeight / 2 + 8, 0x888888);
        }
    }

    private void drawPackList(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // 列表头（放在面板内部顶部偏右，加粗避开主面板左边的灰色边框）
        guiGraphics.drawString(this.font, "§l拓展包",
                this.listX + 8, this.listY + 4, this.textColor);

        // 列表头底部分隔线
        int headerBottomY = this.listY + 16;
        guiGraphics.fill(this.listX, headerBottomY, this.listX + this.listWidth, headerBottomY + 1, 0xFF555555);

        // 列表背景
        guiGraphics.fill(this.listX, this.listY + 18, this.listX + this.listWidth,
            this.listY + this.listHeight, 0xFF1A1A1A);

        if (this.discoverablePacks.isEmpty()) {
            guiGraphics.drawCenteredString(this.font, "未发现",
                    this.listX + this.listWidth / 2, this.listY + 80, 0xFF5555);
            guiGraphics.drawCenteredString(this.font, "标准格式拓展包",
                    this.listX + this.listWidth / 2, this.listY + 95, 0xFF5555);
            return;
        }

        for (int i = 0; i < this.discoverablePacks.size(); i++) {
            int itemY = this.listContentY + i * PACK_ITEM_H;
            if (itemY + PACK_ITEM_H > this.listY + this.listHeight) break;
            ExtensionPack p = this.discoverablePacks.get(i);
            int bg;
            if (i == this.selectedPackIndex) {
                bg = 0xFF4A6FA5;  // 选中蓝
            } else if (mouseX >= this.listX && mouseX <= this.listX + this.listWidth
                && mouseY >= itemY && mouseY < itemY + PACK_ITEM_H) {
                bg = 0xFF3A3A3A;  // 悬停灰
            } else {
                bg = 0xFF2A2A2A;  // 普通
            }
            guiGraphics.fill(this.listX, itemY, this.listX + this.listWidth, itemY + PACK_ITEM_H - 2, bg);

            String name = p.getName();
            if (name.length() > 12) name = name.substring(0, 10) + "..";
            guiGraphics.drawString(this.font, name, this.listX + 8, itemY + 4, 0xFFFFFF);

            int count = p.getConstructions().size();
            String countText = count + " 建筑";
            guiGraphics.drawString(this.font, countText, this.listX + 8, itemY + 16, 0xAAAAAA);

            // 选中标记
            if (i == this.selectedPackIndex) {
                guiGraphics.fill(this.listX, itemY, this.listX + 3, itemY + PACK_ITEM_H - 2, 0xFF55AAFF);
            }
        }
    }

    private void drawPackDetails(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        ExtensionPack p = this.discoverablePacks.get(this.selectedPackIndex);

        // 顶部：包名（标题）
        String title = p.getName();
        if (title.length() > 24) title = title.substring(0, 22) + "..";
        guiGraphics.drawString(this.font, "§l" + title, this.detailX + 4, this.detailY + 4, 0x55AAFF);

        // 封面图（左上角，48x48）
        if (p.hasCoverImage() && p.getCoverImageData() != null) {
            ensureCoverTextureLoaded(p);
            if (coverTextureLocation != null) {
                drawTexturedQuadNearest(guiGraphics, coverTextureLocation,
                    this.detailX + 4, this.detailY + 18, 48, 48,
                    0, 0, 48, 48, 48, 48);
            }
        } else {
            guiGraphics.fill(this.detailX + 4, this.detailY + 18, this.detailX + 52, this.detailY + 66, 0xFF1A1A1A);
            guiGraphics.drawCenteredString(this.font, "无封面", this.detailX + 28, this.detailY + 38, 0x555555);
        }

        // 信息字段（封面图右侧）
        int tx = this.detailX + 60;
        int ty = this.detailY + 18;
        int lineH = 11;

        String author = (p.getAuthor() == null || p.getAuthor().isEmpty()) ? "未知" : p.getAuthor();
        guiGraphics.drawString(this.font, "作者: " + author, tx, ty, 0xFFFFFF);

        String version = (p.getVersion() == null || p.getVersion().isEmpty()) ? "未指定" : p.getVersion();
        guiGraphics.drawString(this.font, "版本: " + version, tx, ty + lineH, 0xFFFFFF);

        String packageName = (p.getPackageName() == null || p.getPackageName().isEmpty())
            ? p.getFileName() : p.getPackageName();
        guiGraphics.drawString(this.font, "标识: " + packageName, tx, ty + lineH * 2, 0xFFFFFF);

        // 依赖
        String deps = (p.getDependencies() == null || p.getDependencies().isEmpty())
            ? "无" : String.join(", ", p.getDependencies());
        if (deps.length() > 28) deps = deps.substring(0, 26) + "..";
        guiGraphics.drawString(this.font, "依赖: " + deps, tx, ty + lineH * 3, 0xFFFFFF);

        // 描述
        String desc = (p.getDescription() == null || p.getDescription().isEmpty()) ? "无" : p.getDescription();
        if (desc.length() > 42) desc = desc.substring(0, 40) + "..";
        guiGraphics.drawString(this.font, "描述: " + desc, this.detailX + 4, this.detailY + 70, 0xFFFFAA00);

        // 建筑列表标题
        guiGraphics.drawString(this.font, "建筑列表 (" + currentConstructions().size() + "):",
                this.detailX + 4, this.detailY + 88, this.textColor);

        // 建筑列表
        drawConstructionList(guiGraphics, mouseX, mouseY);
    }

    private void drawConstructionList(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int constH = CONST_ITEM_H * this.visibleConstructions;
        // 列表背景
        guiGraphics.fill(this.detailX, this.constListY0, this.detailX + this.detailWidth, this.constListY0 + constH, 0xFF1A1A1A);

        List<ConstructionInfo> list = currentConstructions();
        for (int j = 0; j < this.visibleConstructions; j++) {
            int constIdx = j + this.scrollOffsetConstructions;
            if (constIdx >= list.size()) break;
            int iy = this.constListY0 + j * CONST_ITEM_H;
            ConstructionInfo c = list.get(constIdx);
            int bg;
            if (mouseX >= this.detailX && mouseX <= this.detailX + this.detailWidth
                && mouseY >= iy && mouseY < iy + CONST_ITEM_H) {
                bg = 0xFF3A3A3A;  // 悬停灰
            } else {
                bg = 0xFF2A2A2A;  // 普通
            }
            guiGraphics.fill(this.detailX, iy, this.detailX + this.detailWidth, iy + CONST_ITEM_H - 1, bg);
            String n = c.getName();
            if (n.length() > 30) n = n.substring(0, 28) + "..";
            guiGraphics.drawString(this.font, n, this.detailX + 6, iy + 3, 0xFFFFFF);
            // 第二行：作者 + 尺寸
            StringBuilder sub = new StringBuilder();
            if (c.getAuthor() != null && !c.getAuthor().isEmpty()) sub.append("by ").append(c.getAuthor());
            if (c.getSize() != null && !c.getSize().isEmpty()) {
                if (sub.length() > 0) sub.append("  ");
                sub.append(c.getSize());
            }
            if (sub.length() == 0) sub.append("点击查看");
            String subStr = sub.toString();
            if (subStr.length() > 38) subStr = subStr.substring(0, 36) + "..";
            guiGraphics.drawString(this.font, subStr, this.detailX + 6, iy + 13, 0xAAAAAA);
        }

        // 滚动提示
        if (list.size() > this.visibleConstructions) {
            int max = list.size() - this.visibleConstructions;
            String info = (this.scrollOffsetConstructions + 1) + "-" +
                Math.min(this.scrollOffsetConstructions + this.visibleConstructions, list.size()) +
                " / " + list.size() + " (滚轮翻页 · 点击查看详情)";
            guiGraphics.drawString(this.font, info, this.detailX + 4, this.constListY0 + constH + 4, 0x888888);
        }
    }

    private void ensureCoverTextureLoaded(ExtensionPack p) {
        if (coverTextureLocation != null) return;
        try (java.io.InputStream is = new java.io.ByteArrayInputStream(p.getCoverImageData())) {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(is);
            if (img == null) return;
            int w = img.getWidth(), h = img.getHeight();
            net.minecraft.client.renderer.texture.DynamicTexture tex =
                new net.minecraft.client.renderer.texture.DynamicTexture(w, h, false);
            tex.setFilter(false, false); // NEAREST
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = img.getRGB(x, y);
                    int abgr = ((argb & 0xFF00FF00) | ((argb & 0x00FF0000) >> 16) | ((argb & 0x000000FF) << 16));
                    tex.getPixels().setPixelRGBA(x, y, abgr);
                }
            }
            tex.upload();
            this.coverTextureLocation = Minecraft.getInstance().getTextureManager()
                    .register("prefab_cover_" + p.getPackageName(), tex);
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("Failed to load cover image for {}", p.getName(), e);
        }
    }

    private void drawTexturedQuadNearest(GuiGraphics guiGraphics, ResourceLocation texture,
                                          int x, int y, int w, int h,
                                          int u, int v, int uW, int vH, int sheetW, int sheetH) {
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.texParameter(com.mojang.blaze3d.platform.GlConst.GL_TEXTURE_2D,
            com.mojang.blaze3d.platform.GlConst.GL_TEXTURE_MIN_FILTER,
            com.mojang.blaze3d.platform.GlConst.GL_NEAREST);
        RenderSystem.texParameter(com.mojang.blaze3d.platform.GlConst.GL_TEXTURE_2D,
            com.mojang.blaze3d.platform.GlConst.GL_TEXTURE_MAG_FILTER,
            com.mojang.blaze3d.platform.GlConst.GL_NEAREST);
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
    public void buttonClicked(AbstractButton button) {
        if (button == this.btnClose) {
            this.onClose();
            return;
        }
        if (button == this.btnDownload) {
            GuiExtensionPackDownloader.open();
        }
    }

    @Override
    public void onClose() {
        // 释放封面纹理
        this.coverTextureLocation = null;
        super.onClose();
    }
}
