package com.prefab.addon.client.gui;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.ConstructionInfo;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 从拓展包管理界面打开的建筑详细界面（仅查看）.
 *
 * 与 GuiConstructionDetail 的区别:
 *   - 没有 "Select" 按钮（不在这里绑定蓝图）
 *   - 没有 "Back" 按钮（"关闭" 直接返回拓展包管理界面）
 *
 * LDLib2 重写: 用 ModularUI + ModularUIScreen 替代 Screen, ScrollerView 包裹主内容,
 * 用 IGuiTexture (DynamicTexture + 自定义 draw) 渲染预加载的建筑预览图.
 * 行为保持与原版一致: 加载预览图 → 显示建筑名/作者/尺寸/描述 + 预览图 → 关闭回到拓展包管理界面.
 */
public final class GuiExtensionConstructionDetail {

    private final ConstructionInfo construction;
    private ResourceLocation previewTextureLocation;
    private int textureWidth = 0;
    private int textureHeight = 0;

    private GuiExtensionConstructionDetail(ConstructionInfo construction) {
        this.construction = construction;
    }

    public static void open(ConstructionInfo construction) {
        GuiExtensionConstructionDetail gui = new GuiExtensionConstructionDetail(construction);
        gui.loadPreviewImage();
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new ModularUIScreen(gui.createUI(),
            Component.literal(construction.getName())));
    }

    private ModularUI createUI() {
        // === 根容器 ===
        UIElement root = new UIElement();
        root.layout(l -> l
            .width(456)
            .height(344)
            .paddingAll(0)
            .gapAll(0)
            .flexDirection(FlexDirection.COLUMN)
        );
        root.style(s -> s.background(Sprites.BORDER));

        // === 标题栏 (24px, 固定不滚动) ===
        UIElement titleBar = new UIElement();
        titleBar.layout(l -> l
            .widthPercent(100)
            .height(24)
            .paddingHorizontal(8)
            .justifyContent(AlignContent.CENTER)
        );
        titleBar.style(s -> s.background(Sprites.RECT_DARK));
        Label title = new Label();
        title.setText(Component.literal(this.construction.getName()));
        title.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textColor(0xFFFFFF));
        titleBar.addChild(title);
        root.addChild(titleBar);

        // === 主内容: ScrollerView 包裹 row 布局 (图片 + 信息) ===
        UIElement content = new UIElement();
        content.layout(l -> l
            .width(440)
            .paddingAll(8)
            .gapAll(8)
            .flexDirection(FlexDirection.ROW)
        );
        content.style(s -> s.background(Sprites.BORDER));

        content.addChild(buildImageBox());
        content.addChild(buildInfoBox());

        ScrollerView scrollerView = new ScrollerView();
        scrollerView.layout(l -> l.width(456).height(280));
        scrollerView.scrollerStyle(s -> s.mode(ScrollerMode.VERTICAL));
        scrollerView.verticalScroller(s -> s.setScrollBarSize(15f));
        scrollerView.addScrollViewChild(content);
        root.addChild(scrollerView);

        // === 底部按钮栏 (40px, 固定不滚动) ===
        UIElement buttonBar = new UIElement();
        buttonBar.layout(l -> l
            .widthPercent(100)
            .height(40)
            .paddingAll(8)
            .gapAll(8)
            .flexDirection(FlexDirection.ROW)
            .justifyContent(AlignContent.CENTER)
        );
        Button btnClose = new Button().setText(Component.literal(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.close")));
        btnClose.setOnClick(e -> GuiExtensionPackBrowser.open());
        btnClose.layout(l -> l.width(90).height(24));
        buttonBar.addChild(btnClose);
        root.addChild(buttonBar);

        // === 资源清理: 关闭时释放预览图纹理 ===
        root.addEventListener(UIEvents.REMOVED, e -> releaseTexture());

        return ModularUI.of(UI.of(root,
            StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)));
    }

    /**
     * 左侧图片框 (200x260).
     * 有预览图: 用自定义 IGuiTexture 渲染 (保持长宽比, NEAREST 过滤, 居中).
     * 无预览图: 灰色背景 + "无预览" 文字.
     */
    private UIElement buildImageBox() {
        UIElement imageBox = new UIElement();
        imageBox.layout(l -> l.width(200).height(260).paddingAll(2));

        if (previewTextureLocation != null) {
            imageBox.style(s -> s.background(Sprites.RECT_DARK));
            UIElement img = new UIElement();
            img.layout(l -> l.widthPercent(100).heightPercent(100));
            img.style(s -> s.background(createImageTexture()));
            imageBox.addChild(img);
        } else {
            imageBox.style(s -> s.background(Sprites.RECT_DARK));
            imageBox.layout(l -> l.width(200).height(260)
                .paddingAll(2)
                .justifyContent(AlignContent.CENTER)
                .alignItems(AlignItems.CENTER));
            Label noPreview = new Label();
            noPreview.setText(Component.literal("无预览").withStyle(ChatFormatting.DARK_GRAY));
            noPreview.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
            imageBox.addChild(noPreview);
        }
        return imageBox;
    }

    /**
     * 右侧信息列 (220 宽, 多条标签).
     * 与原版一致: 建筑名 / 作者 / 尺寸 / 描述(多行).
     */
    private UIElement buildInfoBox() {
        UIElement infoBox = new UIElement();
        infoBox.layout(l -> l
            .width(220)
            .height(260)
            .paddingAll(4)
            .gapAll(6)
            .flexDirection(FlexDirection.COLUMN)
        );
        infoBox.style(s -> s.background(Sprites.RECT_DARK));

        // 建筑名
        infoBox.addChild(makeFieldLabel("建筑名:"));
        infoBox.addChild(makeFieldValue(truncate(this.construction.getName(), 18)));

        // 作者
        infoBox.addChild(makeFieldLabel("作者:"));
        String author = this.construction.getAuthor();
        infoBox.addChild(makeFieldValue(
            truncate(author != null && !author.isEmpty() ? author : "未知", 18)));

        // 尺寸
        infoBox.addChild(makeFieldLabel("尺寸:"));
        String size = this.construction.getSize() != null ? this.construction.getSize() : "?";
        infoBox.addChild(makeFieldValue(size));

        // 描述 (多行, 自动换行, 限制最大高度)
        infoBox.addChild(makeFieldLabel("描述:"));
        String desc = this.construction.getDescription();
        if (desc == null || desc.isEmpty()) desc = "无";
        Label descValue = new Label();
        descValue.setText(Component.literal(desc).withStyle(ChatFormatting.GRAY));
        descValue.textStyle(t -> t
            .textAlignHorizontal(Horizontal.LEFT)
            .textWrap(TextWrap.WRAP)
        );
        descValue.layout(l -> l.widthPercent(100).height(80));
        infoBox.addChild(descValue);

        return infoBox;
    }

    private static Label makeFieldLabel(String text) {
        Label label = new Label();
        label.setText(Component.literal(text));
        label.textStyle(t -> t.textAlignHorizontal(Horizontal.LEFT));
        return label;
    }

    private static Label makeFieldValue(String text) {
        Label label = new Label();
        label.setText(Component.literal(text).withStyle(ChatFormatting.WHITE));
        label.textStyle(t -> t.textAlignHorizontal(Horizontal.LEFT));
        return label;
    }

    private static String truncate(String s, int n) {
        return s.length() > n ? s.substring(0, n - 2) + ".." : s;
    }

    /**
     * 创建一个自定义 IGuiTexture, 在指定区域按"保持长宽比 + 居中"渲染已注册的预览图.
     * 强制 NEAREST 过滤 (绕过 ImmediatelyFast 的 LINEAR 强制过滤).
     */
    private IGuiTexture createImageTexture() {
        final ResourceLocation texLoc = this.previewTextureLocation;
        final int texW = this.textureWidth;
        final int texH = this.textureHeight;
        return new IGuiTexture() {
            @Override
            public IGuiTexture copy() { return this; }

            @Override
            public void draw(GuiGraphics graphics, float mouseX, float mouseY,
                             float x, float y, float width, float height, float partialTicks) {
                int drawW, drawH, drawX, drawY;
                if (texW > 0 && texH > 0) {
                    float aspect = (float) texW / (float) texH;
                    if (aspect >= 1.0f) {
                        drawW = (int) width;
                        drawH = Math.max(1, (int) (width / aspect));
                    } else {
                        drawH = (int) height;
                        drawW = Math.max(1, (int) (height * aspect));
                    }
                    drawX = (int) (x + (width - drawW) / 2f);
                    drawY = (int) (y + (height - drawH) / 2f);
                } else {
                    drawW = (int) width; drawH = (int) height; drawX = (int) x; drawY = (int) y;
                }
                drawTexturedQuadNeatest(texLoc, drawX, drawY, drawW, drawH, texW, texH);
            }
        };
    }

    /**
     * 立即绘制纹理: 绕过 ImmediatelyFast 的 LINEAR 强制过滤.
     */
    private static void drawTexturedQuadNeatest(ResourceLocation texture, int x, int y, int w, int h,
                                                int sheetW, int sheetH) {
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.texParameter(GlConst.GL_TEXTURE_2D,
            GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_NEAREST);
        RenderSystem.texParameter(GlConst.GL_TEXTURE_2D,
            GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_NEAREST);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        float f = 1.0F / sheetW;
        float f1 = 1.0F / sheetH;
        float u0 = 0;
        float v0 = 0;
        float u1 = (float) sheetW * f;
        float v1 = (float) sheetH * f1;

        Tesselator tesselator = Tesselator.getInstance();
        var buffer = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        buffer.addVertex(x, y + h, 0).setUv(u0, v1).setColor(1.0F, 1.0F, 1.0F, 1.0F);
        buffer.addVertex(x + w, y + h, 0).setUv(u1, v1).setColor(1.0F, 1.0F, 1.0F, 1.0F);
        buffer.addVertex(x + w, y, 0).setUv(u1, v0).setColor(1.0F, 1.0F, 1.0F, 1.0F);
        buffer.addVertex(x, y, 0).setUv(u0, v0).setColor(1.0F, 1.0F, 1.0F, 1.0F);
        BufferUploader.drawWithShader(buffer.build());
    }

    /**
     * 加载建筑预览图到 DynamicTexture, 注册到 TextureManager.
     * 复制自原版 GuiExtensionConstructionDetail (PNG → ABGR NativeImage → 强制 NEAREST).
     */
    private void loadPreviewImage() {
        if (!this.construction.hasPreviewImage() || previewTextureLocation != null) return;
        try (InputStream is = new ByteArrayInputStream(this.construction.getPngData())) {
            BufferedImage bufferedImage = ImageIO.read(is);
            if (bufferedImage == null) {
                PrefabCustomAddon.LOGGER.warn("[EXT-DETAIL] ImageIO.read returned null for {}",
                    construction.getId());
                return;
            }
            int width = bufferedImage.getWidth();
            int height = bufferedImage.getHeight();
            PrefabCustomAddon.LOGGER.info("[EXT-DETAIL] Loading preview {}x{} for {}",
                width, height, construction.getId());

            NativeImage nativeImage = new NativeImage(width, height, true /*useSrgb*/);
            for (int yy = 0; yy < height; yy++) {
                for (int xx = 0; xx < width; xx++) {
                    int argb = bufferedImage.getRGB(xx, yy);
                    int abgr = ((argb & 0xFF00FF00)
                        | ((argb & 0x00FF0000) >> 16)
                        | ((argb & 0x000000FF) << 16));
                    nativeImage.setPixelRGBA(xx, yy, abgr);
                }
            }
            DynamicTexture previewTexture = new DynamicTexture(nativeImage);
            previewTexture.setFilter(false, false); // NEAREST
            // 关键: 纹理名要带 construction id, 否则所有 detail GUI 共用同一个纹理
            previewTextureLocation = Minecraft.getInstance().getTextureManager()
                .register("prefab_ext_detail_preview_" + construction.getId(), previewTexture);
            this.textureWidth = width;
            this.textureHeight = height;
            PrefabCustomAddon.LOGGER.info("[EXT-DETAIL] Texture registered: {} size={}x{}",
                previewTextureLocation, width, height);
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.warn("Failed to load preview image for: {}",
                construction.getName(), e);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("Preview load error", t);
        }
    }

    /**
     * 释放预览图纹理 (在 UI 树移除时调用, 即关闭/返回时).
     */
    private void releaseTexture() {
        if (previewTextureLocation != null) {
            Minecraft.getInstance().getTextureManager().release(previewTextureLocation);
            previewTextureLocation = null;
        }
    }
}
