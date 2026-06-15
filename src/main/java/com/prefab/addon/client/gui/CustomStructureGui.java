package com.prefab.addon.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.ConstructionInfo;
import com.prefab.addon.extension.ExtensionPackManager;
import com.prefab.addon.structure.CustomStructureBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;

import com.prefab.structures.gui.GuiStructure;

/**
 * 复用 Prefab 主模组的 GuiStructure 来显示自定义建筑。
 * 我们只覆盖图片加载（自定义建筑的 PNG），其他 Preview/Build 按钮复用 Prefab 原生逻辑。
 *
 * 关键点：
 * - selectedStructure: 必须是 com.prefab.structures.base.Structure 实例
 *   Prefab 原生 performPreview() 会读它来调用 StructureRenderHandler.setStructure
 * - configuration: 复用 Prefab 的 StructureConfiguration（无需自己实现）
 * - btnVisualize / btnBuild: 复用 Prefab 的按钮处理
 */
public class CustomStructureGui extends GuiStructure {
    private final ConstructionInfo construction;
    private final ItemStack blueprint;
    private final BlockPos openPos;  // 玩家右击的方块位置
    private ResourceLocation customImageLocation;
    private int customImageWidth = 0;
    private int customImageHeight = 0;
    private boolean imageLoaded = false;

    // 新增：更换建筑按钮
    private com.prefab.gui.controls.ExtendedButton btnChange;

    public CustomStructureGui(ConstructionInfo construction, ItemStack blueprint, BlockPos openPos) {
        // 调父类构造（GuiStructure 接受 String 名字）
        super(construction.getName());
        this.construction = construction;
        this.blueprint = blueprint;
        this.openPos = openPos == null ? BlockPos.ZERO : openPos;

        // 关键：必须设置 structureConfiguration 为有效枚举值（不能是 null），
        // 否则 performCancelOrBuildOrHouseFacing 创建 StructureTagMessage 时 NPE。
        // 用 Basic 类型足够（我们的自定义建筑走 "Basic" 这条路）
        this.structureConfiguration = com.prefab.structures.messages.StructureTagMessage.EnumStructureConfiguration.Basic;
        // 复用 Basic.structureConfig（避免我们 new 一个孤立的 StructureConfiguration 不被识别）
        this.configuration = this.structureConfiguration.structureConfig;
        this.configuration.Initialize();
        // 关键：直接设置 pos 为玩家右击的位置（WriteToCompoundTag 内部会写入 NBT）
        this.configuration.pos = this.openPos;
        // 关键：强制设 houseFacing = SOUTH。
        // PositionOffset.getRelativePosition 内部 4-iteration 循环依赖 assumedNorth/configurationFacing
        // 的相对旋转，configurationFacing=SOUTH（即 opposite=NORTH）时与 assumedNorth=NORTH 同向，
        // 循环不会反转 east/south offsets，建筑渲染位置 = (bx, by, bz)（与 Build 路径一致）。
        // 默认 NORTH 会反转 X 轴，使预览相对于建造位置左右对称。
        this.configuration.houseFacing = net.minecraft.core.Direction.SOUTH;

        // 设置 selectedStructure：把 NBT 解析为 Prefab 的 Structure 实例
        try {
            this.selectedStructure = CustomStructureBuilder.parseToPrefabStructure(construction);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("Failed to create Structure for {}", construction.getId(), t);
        }
    }

    @Override
    public void init() {
        // 调用父类 init：会创建 btnCancel/btnBuild/btnVisualize 并设置 selectedStructure 相关
        super.init();
        // 加载自定义图片
        if (!imageLoaded) {
            loadCustomImage();
            imageLoaded = true;
        }
    }

    /**
     * 关键：处理方向键 / +/- / ALT 键，让玩家在 GUI 仍打开时也能微调 Preview 位置。
     *
     * 注意：默认 performPreview() 会关闭 GUI，关闭后这些键由
     * StructurePreviewKeyHandler（ClientTickEvent）接管。
     *
     * 方向键按**玩家视角**移动（不是世界 X/Z）。
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        com.prefab.structures.config.StructureConfiguration cfg =
            com.prefab.structures.render.StructureRenderHandler.currentConfiguration;
        if (cfg == null) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        int step = (modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT) != 0 ? 5 : 1;

        // 玩家朝向 → 前后左右
        net.minecraft.core.Direction facing = this.player != null
            ? this.player.getNearestViewDirection() : net.minecraft.core.Direction.NORTH;
        if (facing == net.minecraft.core.Direction.UP || facing == net.minecraft.core.Direction.DOWN) {
            facing = net.minecraft.core.Direction.NORTH;
        }
        net.minecraft.core.Direction forward = facing;
        net.minecraft.core.Direction back = facing.getOpposite();
        net.minecraft.core.Direction left = facing.getCounterClockWise();
        net.minecraft.core.Direction right = facing.getClockWise();

        int dx = 0, dz = 0, dy = 0;
        switch (keyCode) {
            case org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT:
                dx += left.getStepX(); dz += left.getStepZ(); break;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT:
                dx += right.getStepX(); dz += right.getStepZ(); break;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_UP:
                dx += forward.getStepX(); dz += forward.getStepZ(); break;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN:
                dx += back.getStepX(); dz += back.getStepZ(); break;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_EQUAL:
            case org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ADD:
                dy += 1; break;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_MINUS:
            case org.lwjgl.glfw.GLFW.GLFW_KEY_KP_SUBTRACT:
                dy -= 1; break;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT:
            case org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_ALT:
                if (this.player != null && this.btnBuild != null) {
                    this.buttonClicked(this.btnBuild);
                }
                return true;
            default:
                return super.keyPressed(keyCode, scanCode, modifiers);
        }
        net.minecraft.core.BlockPos pos = cfg.pos;
        net.minecraft.core.BlockPos newPos = pos.offset(dx * step, dy * step, dz * step);
        cfg.pos = newPos;
        if (this.configuration != null) {
            this.configuration.pos = newPos;
        }
        // 关键：Prefab 把结构烘焙到 previewChunks 网格，必须 setStructure 触发 needsRebuild=true
        com.prefab.structures.render.StructureRenderHandler.setStructure(this.selectedStructure, cfg);
        PrefabCustomAddon.LOGGER.info("[KEY-DEBUG] player={} dx={} dz={} step={}  {} -> {}",
            facing, dx, dz, step, pos, newPos);
        return true;
    }

    /**
     * 已废弃：之前的"直接重算 blockPos"方案对 Prefab 的烘焙网格无效。
     * 现在用 setStructure 触发 needsRebuild 来让 Prefab 自己用新 pos 重建网格。
     * 保留此方法以备后用（暂不删除以免影响代码结构）。
     */
    @SuppressWarnings("unused")
    private static void recomputeAllBlockPositions(com.prefab.structures.base.Structure structure,
                                                   net.minecraft.core.BlockPos newPos) {
        if (structure == null || newPos == null) return;
        com.prefab.structures.config.StructureConfiguration cfg =
            com.prefab.structures.render.StructureRenderHandler.currentConfiguration;
        if (cfg == null) return;
        net.minecraft.core.Direction structureDir = structure.getClearSpace().getShape().getDirection();
        net.minecraft.core.Direction houseFacing = cfg.houseFacing;
        for (com.prefab.structures.base.BuildBlock block : structure.getBlocks()) {
            if (block.getStartingPosition() != null) {
                block.blockPos = block.getStartingPosition().getRelativePosition(
                    newPos, structureDir, houseFacing);
            }
            block.centerOfBlock = null;
            com.prefab.structures.base.BuildBlock sub = block.getSubBlock();
            if (sub != null && sub.getStartingPosition() != null) {
                sub.blockPos = sub.getStartingPosition().getRelativePosition(
                    newPos, structureDir, houseFacing);
                sub.centerOfBlock = null;
            }
        }
    }

    @Override
    protected void Initialize() {
        // 调用父类 Initialize（设置 modifiedInitialXAxis 等）
        super.Initialize();
        // configuration 已在构造器中设置（EnumStructureConfiguration.Basic.structureConfig）
        // 这里仅保险检查
        if (this.configuration == null) {
            this.configuration = com.prefab.structures.messages.StructureTagMessage.EnumStructureConfiguration.Basic.structureConfig;
            this.configuration.Initialize();
        }
        // 关键：再次强制设 houseFacing = SOUTH（防止父类或后续逻辑把它重置为 NORTH）
        this.configuration.houseFacing = net.minecraft.core.Direction.SOUTH;
        // 关键：GuiStructure 的 Initialize 不会自动创建按钮，
        // Prefab 的具体子类（如 GuiBasicStructure）自己调用 InitializeStandardButtons()。
        // 我们也必须自己调用。
        this.InitializeStandardButtons();
        // 新增：在 3 个标准按钮下方加一个 [更换] 按钮 → 打开选择界面
        com.prefab.Tuple<Integer, Integer> adj = this.getAdjustedXYValue();
        int grayBoxX = adj.getFirst();
        int grayBoxY = adj.getSecond();
        this.btnChange = this.createAndAddCustomButton(
            grayBoxX + 215, grayBoxY + 192, 90, 20, "更换");
    }

    /**
     * 实现 GuiBase 抽象方法。Prefab 原生 GuiStructure 内部根据 EnumStructureConfiguration
     * 来路由按钮点击。我们复用 Prefab 的 performCancelOrBuildOrHouseFacing 来处理 Build/Cancel。
     */
    @Override
    public void buttonClicked(net.minecraft.client.gui.components.AbstractButton button) {
        // 在调 Prefab 原生按钮处理前，确保 configuration.pos 是玩家右击的位置
        if (this.configuration != null && this.configuration.pos == null && this.openPos != null) {
            this.configuration.pos = this.openPos;
        }
        // Build 按钮：用我们自己的 BuildCustomStructurePayload（不发 Prefab 的网络包，避免服务端走默认 AquaBase 路径）
        if (button == this.btnBuild) {
            String packName = this.construction.getPack() != null ? this.construction.getPack().getName() : "";
            // 关键：Build 必须与 Preview 在**同一位置**。
            // 优先级：
            //   1. currentConfiguration.pos（如果点过 Preview）
            //   2. this.openPos（useOn 时的玩家位置，**不是** build 时位置）
            //   3. player.above()（最后 fallback）
            net.minecraft.core.BlockPos buildPos;
            com.prefab.structures.config.StructureConfiguration previewConfig =
                com.prefab.structures.render.StructureRenderHandler.currentConfiguration;
            if (previewConfig != null && previewConfig.pos != null) {
                buildPos = previewConfig.pos;
            } else if (this.openPos != null) {
                buildPos = this.openPos;
            } else if (this.player != null) {
                buildPos = this.player.blockPosition().above();
            } else {
                buildPos = net.minecraft.core.BlockPos.ZERO;
            }
            // 同步 configuration.pos
            if (this.configuration != null) {
                this.configuration.pos = buildPos;
            }
            PrefabCustomAddon.LOGGER.info("[BUILD-DEBUG] Build button clicked, sending BuildCustomStructurePayload for {}/{} at {} (player={}, usePreviewPos={})",
                packName, this.construction.getId(), buildPos, this.player != null ? this.player.blockPosition() : "null",
                previewConfig != null && previewConfig.pos != null);
            com.prefab.addon.network.NetworkHandler.sendToServer(
                new com.prefab.addon.network.BuildCustomStructurePayload(
                    buildPos, packName, this.construction.getId()));
            // 关键：Build 后清除预览（避免幽灵方块继续显示）
            com.prefab.structures.render.StructureRenderHandler.setStructure(null, null);
            this.onClose();
            return;
        }
        // Cancel 按钮：仅关闭 GUI
        if (button == this.btnCancel) {
            // Cancel 时清除 Preview
            com.prefab.structures.render.StructureRenderHandler.setStructure(null, null);
            this.onClose();
            return;
        }
        // Visualize（Preview）按钮：用 Prefab 原生 performPreview（纯客户端渲染，不发网络包）
        if (button == this.btnVisualize) {
            // 关键：使用**与 Build 同一**位置（this.openPos，useOn 时的方块位置），
            // 保证 Preview 和 Build 在同一位置，避免"对称"问题
            net.minecraft.core.BlockPos previewPos = this.openPos != null ? this.openPos : BlockPos.ZERO;
            if (this.configuration != null) {
                this.configuration.pos = previewPos;
            }
            this.performPreview();
            return;
        }
        // 新增：更换建筑按钮 → 重新打开选择界面
        if (button == this.btnChange) {
            // 关键：清除当前 Preview 渲染，避免幽灵方块
            com.prefab.structures.render.StructureRenderHandler.setStructure(null, null);
            GuiCustomStructureSelection.open();
            return;
        }
    }

    /**
     * 从 ConstructionInfo 的 PNG 数据加载为 DynamicTexture。
     * 路径：construction.getPngData() 返回字节数组
     */
    private void loadCustomImage() {
        try {
            byte[] pngBytes = construction.getPngData();
            if (pngBytes == null || pngBytes.length == 0) {
                PrefabCustomAddon.LOGGER.warn("No PNG data for construction: {}", construction.getId());
                return;
            }
            BufferedImage img;
            try (InputStream is = new java.io.ByteArrayInputStream(pngBytes)) {
                img = ImageIO.read(is);
            }
            if (img == null) {
                PrefabCustomAddon.LOGGER.warn("ImageIO.read returned null for {}", construction.getId());
                return;
            }
            int width = img.getWidth();
            int height = img.getHeight();
            DynamicTexture tex = new DynamicTexture(width, height, false);
            tex.setFilter(false, false); // blur=false, mipmap=false → NEAREST
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int argb = img.getRGB(x * img.getWidth() / width, y * img.getHeight() / height);
                    int abgr = ((argb & 0xFF00FF00) | ((argb & 0x00FF0000) >> 16) | ((argb & 0x000000FF) << 16));
                    tex.getPixels().setPixelRGBA(x, y, abgr);
                }
            }
            tex.upload();
            tex.setFilter(false, false); // 再次设置确保 GPU 端
            this.customImageLocation = Minecraft.getInstance().getTextureManager().register("prefab_custom_preview", tex);
            this.customImageWidth = width;
            this.customImageHeight = height;
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.warn("Failed to load custom preview image for {}", construction.getId(), e);
        }
    }

    /**
     * 覆盖图片渲染：用 Tesselator 手动绘制自定义建筑的 PNG，绕过 ImmediatelyFast。
     * Prefab 的 GuiStructure 默认用 structureImageLocation 渲染图片，
     * 但我们用 customImageLocation 显示自定义 PNG。
     */
    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 先调父类渲染（绘制按钮、背景、Prefab 内置图片）
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // 在 Prefab 默认图片位置覆盖我们的自定义图片
        if (customImageLocation != null) {
            // Prefab GuiStructure 默认在右上角区域渲染图片（一般 100x100 或更大）
            // 父类已经画过，我们用半透明遮罩 + 自定义图覆盖
            // Prefab 的图片位置是 width-256/2 + 10, 30，尺寸 100x100
            int imgX = this.width / 2 + 10;
            int imgY = 30;
            int imgW = 100;
            int imgH = 100;

            // 半透明黑底（覆盖默认图）
            guiGraphics.fill(imgX - 2, imgY - 2, imgX + imgW + 2, imgY + imgH + 2, 0xFF000000);

            // 用 Tesselator 绘制 NEAREST 过滤的自定义图
            drawTexturedQuadNeatest(customImageLocation,
                imgX, imgY, imgW, imgH,
                0, 0, customImageWidth, customImageHeight, customImageWidth, customImageHeight);
        }

        // 显示当前 Preview 位置 + 操作提示（GUI 内部，按钮上方）
        com.prefab.structures.config.StructureConfiguration cfg =
            com.prefab.structures.render.StructureRenderHandler.currentConfiguration;
        if (cfg != null && cfg.pos != null) {
            // 复用 Prefab 给的 grayBoxY 把文字画在按钮上方
            // 按钮位于 grayBoxY + 167；我们把文字放在 grayBoxY + 143
            com.prefab.Tuple<Integer, Integer> adj = this.getAdjustedXYValue();
            int boxX = adj.getFirst();
            int boxY = adj.getSecond();
            int boxW = 310;  // GUI box 宽
            int helpY = boxY + 143;  // 按钮在 +167，留 24 像素空间

            // 第一行：操作提示
            String help = "方向键 移动  |  +/- 上下  |  Shift 加速  |  CTRL 旋转  |  ALT 建造  |  右键取消";
            int helpW = this.font.width(help);
            int helpX = boxX + (boxW - helpW) / 2;
            // 半透明背景条
            guiGraphics.fill(boxX + 8, helpY - 4, boxX + boxW - 8, helpY + 12, 0xC0000000);
            // 蓝色顶饰线
            guiGraphics.fill(boxX + 8, helpY - 4, boxX + boxW - 8, helpY - 3, 0xFF55AAFF);
            guiGraphics.drawString(this.font, help, helpX, helpY, 0xFFFFFF);

            // 第二行：Preview 位置
            String posText = "预览位置: " + cfg.pos.toShortString();
            int posY = helpY + 14;
            int posW = this.font.width(posText);
            int posX = boxX + (boxW - posW) / 2;
            guiGraphics.fill(boxX + 8, posY - 4, boxX + boxW - 8, posY + 12, 0xC0000000);
            guiGraphics.drawString(this.font, posText, posX, posY, 0xFFFFAA00);
        }
    }

    /**
     * 关键：ImmediatelyFast 会重写 GuiGraphics.blit 强制 LINEAR 过滤。
     * 用 Tesselator 直接绘制绕过它，保留 NEAREST 过滤（像素风格）。
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
}
