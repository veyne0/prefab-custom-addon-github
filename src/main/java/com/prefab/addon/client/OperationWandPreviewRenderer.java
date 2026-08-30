package com.prefab.addon.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.items.OperationWandManager;
import com.prefab.addon.items.OperationWandState;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * 操作手杖 - 3D 预览.
 *
 * <p>玩家扫描选区后, 紫色线框 + 半透明面显示在目标位置 (跟随玩家视线). 模式:
 * <ul>
 *   <li>MOVE: 紫色 (#AA55AA) + 显示"原区域位置"灰色虚线框作为参考</li>
 *   <li>COPY: 粉色 (#FF55FF) + 不显示原区域 (因为不会被改)</li>
 * </ul>
 * 渲染阶段: AFTER_TRANSLUCENT_BLOCKS (不被方块遮挡, 玩家能透过墙看预览位置).
 */
@EventBusSubscriber(modid = PrefabCustomAddon.MOD_ID, value = Dist.CLIENT)
public class OperationWandPreviewRenderer {

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (Minecraft.getInstance().player == null) return;

        OperationWandState state = OperationWandManager.get(Minecraft.getInstance().player);
        if (!state.isReady()) return;
        if (state.previewPos == null) return;

        Camera cam = event.getCamera();
        Vec3 camPos = cam.getPosition();
        PoseStack poseStack = event.getPoseStack();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        BlockPos preview = state.previewPos;
        AABB previewBox = new AABB(
            preview.getX(), preview.getY(), preview.getZ(),
            preview.getX() + state.sizeX, preview.getY() + state.sizeY, preview.getZ() + state.sizeZ);

        boolean isMove = state.mode == OperationWandState.Mode.MOVE;
        float r, g, b;
        if (isMove) {
            r = 0.67f; g = 0.33f; b = 0.67f;  // 紫色
        } else {
            r = 1.0f; g = 0.33f; b = 0.85f;  // 粉色
        }

        // 1) 半透明面 (玻璃箱)
        drawFaceOverlay(poseStack, previewBox, r, g, b, 0.10f);
        // 2) 主线框
        drawWireframe(poseStack, previewBox, r, g, b, 1.0f);
        // 3) MOVE 模式: 还画原区域位置 (灰色虚线效果: 半透明 0.4)
        if (isMove && state.origin != null) {
            AABB originBox = new AABB(
                state.origin.getX(), state.origin.getY(), state.origin.getZ(),
                state.origin.getX() + state.sizeX, state.origin.getY() + state.sizeY,
                state.origin.getZ() + state.sizeZ);
            drawWireframe(poseStack, originBox, 0.5f, 0.5f, 0.5f, 0.5f);
        }

        poseStack.popPose();
    }

    // ============== 渲染工具方法 (从 CustomBulldozerPreviewRenderer 复制) ==============

    private static void drawFaceOverlay(PoseStack poseStack, AABB box,
                                        float r, float g, float b, float a) {
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f m = poseStack.last().pose();
        float x1 = (float) box.minX, y1 = (float) box.minY, z1 = (float) box.minZ;
        float x2 = (float) box.maxX, y2 = (float) box.maxY, z2 = (float) box.maxZ;

        quad(buf, m, x2,y1,z1, x2,y1,z2, x2,y2,z2, x2,y2,z1, r,g,b,a); // +X
        quad(buf, m, x1,y1,z2, x1,y1,z1, x1,y2,z1, x1,y2,z2, r,g,b,a); // -X
        quad(buf, m, x1,y2,z1, x2,y2,z1, x2,y2,z2, x1,y2,z2, r,g,b,a); // +Y
        quad(buf, m, x1,y1,z2, x2,y1,z2, x2,y1,z1, x1,y1,z1, r,g,b,a); // -Y
        quad(buf, m, x2,y1,z2, x1,y1,z2, x1,y2,z2, x2,y2,z2, r,g,b,a); // +Z
        quad(buf, m, x1,y1,z1, x2,y1,z1, x2,y2,z1, x1,y2,z1, r,g,b,a); // -Z

        flushQuads(buf);
    }

    private static void drawWireframe(PoseStack poseStack, AABB box,
                                      float r, float g, float b, float a) {
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f m = poseStack.last().pose();
        float x1 = (float) box.minX, y1 = (float) box.minY, z1 = (float) box.minZ;
        float x2 = (float) box.maxX, y2 = (float) box.maxY, z2 = (float) box.maxZ;

        // 底面
        line(buf, m, x1,y1,z1, x2,y1,z1, r,g,b,a);
        line(buf, m, x2,y1,z1, x2,y1,z2, r,g,b,a);
        line(buf, m, x2,y1,z2, x1,y1,z2, r,g,b,a);
        line(buf, m, x1,y1,z2, x1,y1,z1, r,g,b,a);
        // 顶面
        line(buf, m, x1,y2,z1, x2,y2,z1, r,g,b,a);
        line(buf, m, x2,y2,z1, x2,y2,z2, r,g,b,a);
        line(buf, m, x2,y2,z2, x1,y2,z2, r,g,b,a);
        line(buf, m, x1,y2,z2, x1,y2,z1, r,g,b,a);
        // 立柱
        line(buf, m, x1,y1,z1, x1,y2,z1, r,g,b,a);
        line(buf, m, x2,y1,z1, x2,y2,z1, r,g,b,a);
        line(buf, m, x2,y1,z2, x2,y2,z2, r,g,b,a);
        line(buf, m, x1,y1,z2, x1,y2,z2, r,g,b,a);

        flushLines(buf);
    }

    private static void flushLines(BufferBuilder buf) {
        MeshData data = buf.build();
        if (data == null) return;
        try {
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(false);
            BufferUploader.drawWithShader(data);
        } finally {
            data.close();
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }
    }

    private static void flushQuads(BufferBuilder buf) {
        MeshData data = buf.build();
        if (data == null) return;
        try {
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(false);
            BufferUploader.drawWithShader(data);
        } finally {
            data.close();
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }
    }

    private static void line(BufferBuilder buf, Matrix4f m,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float r, float g, float b, float a) {
        buf.addVertex(m, x1, y1, z1).setColor(r, g, b, a);
        buf.addVertex(m, x2, y2, z2).setColor(r, g, b, a);
    }

    private static void quad(BufferBuilder buf, Matrix4f m,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3,
                             float x4, float y4, float z4,
                             float r, float g, float b, float a) {
        buf.addVertex(m, x1, y1, z1).setColor(r, g, b, a);
        buf.addVertex(m, x2, y2, z2).setColor(r, g, b, a);
        buf.addVertex(m, x3, y3, z3).setColor(r, g, b, a);
        buf.addVertex(m, x4, y4, z4).setColor(r, g, b, a);
    }
}
