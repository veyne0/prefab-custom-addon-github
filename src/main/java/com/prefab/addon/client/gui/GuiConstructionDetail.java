package com.prefab.addon.client.gui;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.ConstructionInfo;
import com.prefab.gui.GuiBase;
import com.prefab.gui.controls.ExtendedButton;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import javax.imageio.ImageIO;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;

/**
 * 自定义建筑详细界面 - 第二级界面。
 *
 * 用户在 GuiCustomStructureSelection 单击某项建筑 → 打开本 GUI。
 *
 * 本 GUI：
 *   - 显示建筑大图（NEAREST 过滤保持像素风格）
 *   - 显示建筑名称、来源包、尺寸、描述
 *   - "Select"（选择）按钮：把当前 Construction 绑定到背包中的 Custom Blueprint 上
 *   - "Back"（返回）按钮：返回 GuiCustomStructureSelection
 *
 * 绑定完成后，玩家关闭 GUI，右键 Custom Blueprint（对着方块 useOn）
 * 即可打开 CustomStructureGui（Prefab 原生风格 取消/预览/建造），那才是真正的预览/建造界面。
 *
 * 视觉风格：复用 Prefab GuiBase 背景、左/右面板装饰、Prefab 按钮风格——和 Prefab 内置建筑
 * 详细界面保持一致。
 */
public class GuiConstructionDetail extends GuiBase {

    private final ConstructionInfo construction;
    private ResourceLocation previewTextureLocation;
    private int textureWidth = 0;
    private int textureHeight = 0;

    // 按钮
    private ExtendedButton btnSelect;
    private ExtendedButton btnBack;

    public GuiConstructionDetail(ConstructionInfo construction) {
        super(construction.getName());
        this.construction = construction;
    }

    public static void open(ConstructionInfo construction) {
        Minecraft.getInstance().setScreen(new GuiConstructionDetail(construction));
    }

    @Override
    protected void Initialize() {
        super.Initialize();
        loadPreviewImage();

        // 调整面板尺寸：和 Prefab 内置结构 GUI 风格一致
        this.modifiedInitialXAxis = 200;
        this.modifiedInitialYAxis = 130;
        this.imagePanelWidth = 400;
        this.imagePanelHeight = 260;
        this.shownImageHeight = 180;
        this.shownImageWidth = 340;

        int grayBoxX = (this.width / 2) - this.modifiedInitialXAxis;
        int grayBoxY = (this.height / 2) - this.modifiedInitialYAxis;

        // 三个按钮（Prefab 标准位置：底部）
        this.btnBack = this.createAndAddCustomButton(grayBoxX + 25, grayBoxY + 225, 90, 20, "Back");
        this.btnSelect = this.createAndAddCustomButton(grayBoxX + 155, grayBoxY + 225, 90, 20, "Select");
        // 占位：第三个按钮是 Build，但这里不需要——预览/建造走 CustomStructureGui
        // 我们留一个 Cancel 或 noop，这里不放以免误导用户
    }

    @Override
    protected void preButtonRender(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, float partialTicks) {
        // 复用 Prefab 原生背景
        this.drawControlBackground(guiGraphics, x, y, this.imagePanelWidth, this.imagePanelHeight);
        // 左面板装饰（图片区）
        this.drawControlLeftPanel(guiGraphics, x + 5, y + 5, 200, this.imagePanelHeight - 20);
        // 右面板装饰（信息区）
        this.drawControlRightPanel(guiGraphics, x + 215, y + 5, 175, this.imagePanelHeight - 20);

        // 绘制建筑图片
        if (previewTextureLocation != null) {
            int imgX = x + 15;
            int imgY = y + 12;
            int imgW = 180;
            int imgH = 180;
            drawTexturedQuadNeatest(previewTextureLocation,
                imgX, imgY, imgW, imgH,
                0, 0, textureWidth, textureHeight, textureWidth, textureHeight);
        } else {
            // 无图片占位
            guiGraphics.fill(x + 15, y + 12, x + 195, y + 192, 0xFF222222);
            guiGraphics.drawCenteredString(this.font, "No Preview",
                    x + 105, y + 95, 0x888888);
        }
    }

    @Override
    protected void postButtonRender(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, float partialTicks) {
        // 标题（建筑名称）
        guiGraphics.drawCenteredString(this.font, this.construction.getName(),
                this.getCenteredXAxis(), y + 8, this.textColor);

        // 右面板信息（中文标签）
        int infoX = x + 220;
        int infoY = y + 12;

        // 建筑名
        guiGraphics.drawString(this.font, "建筑名:", infoX, infoY, this.textColor);
        guiGraphics.drawString(this.font, truncate(this.construction.getName(), 18),
                infoX, infoY + 12, 0xFFFFFF);

        // 作者（新增字段，txt 文件里的"作者:"）
        String author = this.construction.getAuthor();
        guiGraphics.drawString(this.font, "作者:", infoX, infoY + 32, this.textColor);
        guiGraphics.drawString(this.font, truncate(author != null && !author.isEmpty() ? author : "未知", 18),
                infoX, infoY + 44, 0xFFFFFF);

        // 尺寸
        guiGraphics.drawString(this.font, "尺寸:", infoX, infoY + 64, this.textColor);
        String size = this.construction.getSize() != null ? this.construction.getSize() : "?";
        guiGraphics.drawString(this.font, size, infoX, infoY + 76, 0xFFFFFF);

        // 描述（多行）
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
            if (lineY > y + 80) break; // 限制行数
        }
    }

    @Override
    public void buttonClicked(AbstractButton button) {
        if (button == this.btnBack) {
            GuiCustomStructureSelection.open();
            return;
        }
        if (button == this.btnSelect) {
            bindToBlueprint();
            return;
        }
    }

    /**
     * 关键：把当前 Construction 绑定到背包中的 Custom Blueprint。
     * 绑定后玩家右键蓝图（对着方块 useOn）会直接打开 CustomStructureGui 进行预览/建造。
     *
     * 实现分两步（两步必须都执行）：
     * 1) 客户端立即绑定 → 玩家关 GUI 后右键蓝图就能直接打开 CustomStructureGui
     * 2) 发包给服务端也执行绑定 → 持久化（重进存档不丢）+ 让 placeStructure 能找到绑定的蓝图消耗
     *
     * 之前只做第 1 步，服务端 ItemStack 没绑定，导致重进存档后绑定丢失且蓝图不消耗。
     */
    private void bindToBlueprint() {
        try {
            net.minecraft.world.entity.player.Player player = Minecraft.getInstance().player;
            if (player == null) return;

            net.minecraft.world.item.ItemStack blueprint = net.minecraft.world.item.ItemStack.EMPTY;
            int foundSlot = -1;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                net.minecraft.world.item.ItemStack stack = player.getInventory().getItem(i);
                if (stack.getItem() instanceof com.prefab.addon.items.CustomBlueprintItem) {
                    blueprint = stack;
                    foundSlot = i;
                    break;
                }
            }

            if (blueprint.isEmpty()) {
                player.sendSystemMessage(Component.literal("No Custom Blueprint found in inventory!")
                        .withStyle(net.minecraft.ChatFormatting.RED));
                PrefabCustomAddon.LOGGER.warn("[BIND-DEBUG] No Custom Blueprint in inventory, abort");
                return;
            }

            String packName = construction.getPack().getName();
            String constructionId = construction.getId();
            PrefabCustomAddon.LOGGER.info("[BIND-DEBUG] Client binding blueprint at slot {} to {}/{}",
                    foundSlot, packName, constructionId);

            // 1) 客户端立即绑定（用于本会话内立即生效）
            com.prefab.addon.items.CustomBlueprintItem.bindConstruction(
                    blueprint,
                    packName,
                    constructionId
            );

            // 2) 关键：发包给服务端也执行绑定（持久化 + 消耗逻辑依赖）
            com.prefab.addon.network.NetworkHandler.sendToServer(
                    new com.prefab.addon.network.BindConstructionPayload(packName, constructionId)
            );

            player.sendSystemMessage(Component.literal("Bound to: " + construction.getName()
                    + " (slot " + foundSlot + ")").withStyle(net.minecraft.ChatFormatting.GREEN));
            PrefabCustomAddon.LOGGER.info("Bound blueprint to: {} / {}",
                    packName, constructionId);

            // 关闭本 GUI——玩家接下来需要右键蓝图（useOn）打开 CustomStructureGui
            this.closeScreen();
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("Failed to bind blueprint", e);
        }
    }

    @Override
    public void init() {
        super.init();
        if (previewTextureLocation == null) {
            loadPreviewImage();
        }
    }

    /**
     * 关键：ImmediatelyFast 会重写 GuiGraphics.blit 并强制 LINEAR 过滤。
     * 用 Tesselator 手动绘制，绕过它，保留 NEAREST 过滤。
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
        try (InputStream is = new java.io.ByteArrayInputStream(this.construction.getPngData())) {
            BufferedImage bufferedImage = ImageIO.read(is);
            if (bufferedImage == null) return;
            int width = bufferedImage.getWidth();
            int height = bufferedImage.getHeight();

            DynamicTexture previewTexture = new DynamicTexture(width, height, false);
            previewTexture.setFilter(false, false); // NEAREST
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
                    .register("prefab_detail_preview", previewTexture);
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
