package com.prefab.addon.client.gui;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.ConstructionInfo;
import com.prefab.gui.GuiBase;
import com.prefab.gui.controls.ExtendedButton;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import javax.imageio.ImageIO;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;

/**
 * 从拓展包管理界面打开的建筑详细界面（仅查看）。
 *
 * 与 GuiConstructionDetail 的区别：
 *   - 没有 "Select" 按钮（不在这里绑定蓝图）
 *   - 没有 "Back" 按钮（"关闭" 直接返回拓展包管理界面）
 *
 * 用户在 GuiExtensionPackBrowser 单击某项建筑 → 打开本 GUI → "关闭" 回到拓展包管理界面。
 */
public class GuiExtensionConstructionDetail extends GuiBase {

    private final ConstructionInfo construction;
    private ResourceLocation previewTextureLocation;
    private int textureWidth = 0;
    private int textureHeight = 0;

    private ExtendedButton btnClose;

    public GuiExtensionConstructionDetail(ConstructionInfo construction) {
        super(construction.getName());
        this.construction = construction;
    }

    public static void open(ConstructionInfo construction) {
        Minecraft.getInstance().setScreen(new GuiExtensionConstructionDetail(construction));
    }

    @Override
    protected void Initialize() {
        super.Initialize();
        loadPreviewImage();

        // 面板尺寸与 GuiConstructionDetail 一致
        this.modifiedInitialXAxis = 200;
        this.modifiedInitialYAxis = 130;
        this.imagePanelWidth = 400;
        this.imagePanelHeight = 260;
        this.shownImageHeight = 180;
        this.shownImageWidth = 340;

        int grayBoxX = (this.width / 2) - this.modifiedInitialXAxis;
        int grayBoxY = (this.height / 2) - this.modifiedInitialYAxis;

        // 只有一个"关闭"按钮，放在底部居中
        this.btnClose = this.createAndAddCustomButton(grayBoxX + 155, grayBoxY + 225, 90, 20, "关闭");
    }

    @Override
    protected void preButtonRender(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, float partialTicks) {
        this.drawControlBackground(guiGraphics, x, y, this.imagePanelWidth, this.imagePanelHeight);
        this.drawControlLeftPanel(guiGraphics, x + 5, y + 5, 200, this.imagePanelHeight - 20);
        this.drawControlRightPanel(guiGraphics, x + 215, y + 5, 175, this.imagePanelHeight - 20);

        if (previewTextureLocation != null) {
            int imgX = x + 15;
            int imgY = y + 12;
            int imgW = 180;
            int imgH = 180;
            drawTexturedQuadNeatest(previewTextureLocation,
                imgX, imgY, imgW, imgH,
                0, 0, textureWidth, textureHeight, textureWidth, textureHeight);
        } else {
            guiGraphics.fill(x + 15, y + 12, x + 195, y + 192, 0xFF222222);
            guiGraphics.drawCenteredString(this.font, "无预览",
                    x + 105, y + 95, 0x888888);
        }
    }

    @Override
    protected void postButtonRender(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, float partialTicks) {
        guiGraphics.drawCenteredString(this.font, this.construction.getName(),
                this.getCenteredXAxis(), y + 8, this.textColor);

        int infoX = x + 220;
        int infoY = y + 12;

        guiGraphics.drawString(this.font, "建筑名:", infoX, infoY, this.textColor);
        guiGraphics.drawString(this.font, truncate(this.construction.getName(), 18),
                infoX, infoY + 12, 0xFFFFFF);

        String author = this.construction.getAuthor();
        guiGraphics.drawString(this.font, "作者:", infoX, infoY + 32, this.textColor);
        guiGraphics.drawString(this.font, truncate(author != null && !author.isEmpty() ? author : "未知", 18),
                infoX, infoY + 44, 0xFFFFFF);

        guiGraphics.drawString(this.font, "尺寸:", infoX, infoY + 64, this.textColor);
        String size = this.construction.getSize() != null ? this.construction.getSize() : "?";
        guiGraphics.drawString(this.font, size, infoX, infoY + 76, 0xFFFFFF);

        guiGraphics.drawString(this.font, "描述:", infoX, infoY + 100, this.textColor);
        String desc = this.construction.getDescription();
        if (desc == null || desc.isEmpty()) {
            desc = "无";
        }
        drawMultilineString(guiGraphics, desc, infoX, infoY + 112, 165, 0xCCCCCC);
    }

    private String truncate(String s, int n) {
        return s.length() > n ? s.substring(0, n - 2) + ".." : s;
    }

    private void drawMultilineString(GuiGraphics guiGraphics, String text, int x, int y, int maxWidth, int color) {
        List<FormattedCharSequence> lines = font.split(FormattedText.of(text), maxWidth);
        int lineY = y;
        for (FormattedCharSequence line : lines) {
            guiGraphics.drawString(font, line, x, lineY, color);
            lineY += 11;
            if (lineY > y + 80) break;
        }
    }

    @Override
    public void buttonClicked(AbstractButton button) {
        if (button == this.btnClose) {
            // 返回拓展包管理界面
            GuiExtensionPackBrowser.open();
        }
    }

    /**
     * 立即绘制纹理：绕过 ImmediatelyFast 的 LINEAR 强制过滤。
     */
    private void drawTexturedQuadNeatest(ResourceLocation texture, int x, int y, int w, int h,
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

    private void loadPreviewImage() {
        if (!this.construction.hasPreviewImage() || previewTextureLocation != null) return;
        try (InputStream is = new ByteArrayInputStream(this.construction.getPngData())) {
            BufferedImage bufferedImage = ImageIO.read(is);
            if (bufferedImage == null) return;
            int width = bufferedImage.getWidth();
            int height = bufferedImage.getHeight();

            DynamicTexture previewTexture = new DynamicTexture(width, height, false);
            previewTexture.setFilter(false, false);
            for (int yy = 0; yy < height; yy++) {
                for (int xx = 0; xx < width; xx++) {
                    int argb = bufferedImage.getRGB(xx * bufferedImage.getWidth() / width, yy * bufferedImage.getHeight() / height);
                    int abgr = ((argb & 0xFF00FF00) | ((argb & 0x00FF0000) >> 16) | ((argb & 0x000000FF) << 16));
                    previewTexture.getPixels().setPixelRGBA(xx, yy, abgr);
                }
            }
            previewTexture.upload();
            previewTexture.setFilter(false, false);
            previewTextureLocation = Minecraft.getInstance().getTextureManager()
                    .register("prefab_ext_detail_preview", previewTexture);
            this.textureWidth = width;
            this.textureHeight = height;
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.warn("Failed to load preview image for: {}", construction.getName());
        }
    }

    @Override
    public void onClose() {
        if (previewTextureLocation != null) {
            Minecraft.getInstance().getTextureManager().release(previewTextureLocation);
        }
        super.onClose();
    }
}
