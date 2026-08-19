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
import com.prefab.addon.network.ExecuteCustomBulldozerPayload;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;

/**
 * 自定义推土机 — 3D 黄线框预览.
 *
 * <p>玩家点「预览」后, 整个黄线框出现在世界里, 玩家用方向键 / +/- 整体移动,
 * ALT 确认清除, 右键取消 (右键取消逻辑在 {@link CustomBulldozerKeyHandler} 里).</p>
 *
 * <p>渲染走 vanilla 调试线框管线 (1.21.1 新 API):
 * {@code Tesselator.begin(Mode, DefaultVertexFormat) -> BufferBuilder
 *   -> .build() 拿 MeshData -> BufferUploader.drawWithShader 提交}.
 * 阶段: {@code AFTER_TRANSLUCENT_BLOCKS} — 不被方块遮挡.</p>
 */
@EventBusSubscriber(modid = PrefabCustomAddon.MOD_ID, value = Dist.CLIENT)
public class CustomBulldozerPreviewRenderer {

    /** 当前预览状态 (静态单例, 同一时刻只能预览一个). */
    private static PreviewState state = null;

    public static class PreviewState {
        public BlockPos pos;        // 可移动的起点 (玩家方向键改这个)
        public final int length;
        public final int width;
        public final int height;
        public Direction facing;    // 可旋转方向 (CTRL+Q/E 改这个, 预览时直观)
        public final ItemStack stack;
        public PreviewState(BlockPos pos, int L, int W, int H, Direction facing, ItemStack stack) {
            this.pos = pos;
            this.length = L; this.width = W; this.height = H;
            this.facing = facing; this.stack = stack;
        }
        public AABB box() {
            Direction sideways = facing.getCounterClockWise();
            int xOff = -((width - 1) / 2) * sideways.getStepX();
            int zOff = -((width - 1) / 2) * sideways.getStepZ();
            BlockPos start = pos.offset(xOff, 0, zOff);
            int fx = facing.getStepX(), fz = facing.getStepZ();
            int sx = sideways.getStepX(), sz = sideways.getStepZ();
            int endX = start.getX() + fx * (length - 1) + sx * (width - 1);
            int endZ = start.getZ() + fz * (length - 1) + sz * (width - 1);
            int endY = pos.getY() + height - 1;
            return new AABB(
                Math.min(start.getX(), endX), pos.getY(), Math.min(start.getZ(), endZ),
                Math.max(start.getX(), endX), endY,      Math.max(start.getZ(), endZ)
            );
        }
    }

    public static boolean isActive() { return state != null; }
    public static PreviewState getState() { return state; }

    public static void start(BlockPos pos, int L, int W, int H, Direction facing, ItemStack stack) {
        state = new PreviewState(pos, L, W, H, facing, stack);
    }

    public static void cancel() {
        state = null;
    }

    public static void move(Direction dir) {
        if (state == null) return;
        state.pos = state.pos.relative(dir);
    }

    public static void moveVertical(int dy) {
        if (state == null) return;
        state.pos = state.pos.offset(0, dy, 0);
    }

    /**
     * 旋转 facing (CCW 一次, 90°).
     * 只允许在水平面 4 个方向之间切 (N/E/S/W), 不动上下.
     */
    public static void rotateY() {
        if (state == null) return;
        state.facing = state.facing.getCounterClockWise();
    }

    /**
     * 旋转 facing (CW 一次, 90°).
     */
    public static void rotateYReverse() {
        if (state == null) return;
        state.facing = state.facing.getClockWise();
    }

    public static void execute() {
        if (state == null) return;
        // 强制 noDrops = true: 自定义推土机任何模式都不生成掉落物, 玩家不能改
        PacketDistributor.sendToServer(new ExecuteCustomBulldozerPayload(
            state.pos, state.length, state.width, state.height, state.facing, true));
        state = null;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (state == null) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (Minecraft.getInstance().player == null) return;

        AABB box = state.box();
        Camera cam = event.getCamera();
        Vec3 camPos = cam.getPosition();
        PoseStack poseStack = event.getPoseStack();

        // 平移到相机空间
        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        // 1) 6 个外法线面渲染淡黄色半透明 (玻璃箱效果, 让玩家从外面看进去觉得"这片会被清掉")
        drawFaceOverlay(poseStack, box, 1.0f, 0.95f, 0.2f, 0.12f);
        // 2) 黄色主线框 (框出边界)
        drawWireframe(poseStack, box, 1.0f, 1.0f, 0.2f, 1.0f);
        // 3) 8 个角的小立方体, 让玩家更容易看到顶点
        drawCorners(poseStack, box, 1.0f, 0.7f, 0.0f, 1.0f);

        poseStack.popPose();
    }

    /**
     * 在 6 个外法线面渲染半透明黄色 (玻璃箱效果).
     * 用 QUADS 走普通 alpha 混合, 让玩家从外面看进去觉得里面的方块"被淡化了".
     */
    private static void drawFaceOverlay(PoseStack poseStack, AABB box,
                                        float r, float g, float b, float a) {
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f m = poseStack.last().pose();
        float x1 = (float) box.minX, y1 = (float) box.minY, z1 = (float) box.minZ;
        float x2 = (float) box.maxX + 1, y2 = (float) box.maxY + 1, z2 = (float) box.maxZ + 1;

        // 6 个面 (顶点绕序: 法线朝外)
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
        float x2 = (float) box.maxX + 1, y2 = (float) box.maxY + 1, z2 = (float) box.maxZ + 1;

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

    private static void drawCorners(PoseStack poseStack, AABB box, float r, float g, float b, float a) {
        // 角点处画 8 个小方块 (0.15 单位), 用 QUADS
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        float[][] corners = {
            {(float)box.minX,        (float)box.minY,        (float)box.minZ},
            {(float)box.maxX+1,      (float)box.minY,        (float)box.minZ},
            {(float)box.maxX+1,      (float)box.minY,        (float)box.maxZ+1},
            {(float)box.minX,        (float)box.minY,        (float)box.maxZ+1},
            {(float)box.minX,        (float)box.maxY+1,      (float)box.minZ},
            {(float)box.maxX+1,      (float)box.maxY+1,      (float)box.minZ},
            {(float)box.maxX+1,      (float)box.maxY+1,      (float)box.maxZ+1},
            {(float)box.minX,        (float)box.maxY+1,      (float)box.maxZ+1},
        };
        float s = 0.08f;  // 角点方块半边长
        for (float[] c : corners) {
            float cx = c[0], cy = c[1], cz = c[2];
            // 6 个面
            quad(buf, poseStack.last().pose(),
                cx-s,cy-s,cz+s, cx+s,cy-s,cz+s, cx+s,cy+s,cz+s, cx-s,cy+s,cz+s, r,g,b,a); // +Z
            quad(buf, poseStack.last().pose(),
                cx+s,cy-s,cz-s, cx-s,cy-s,cz-s, cx-s,cy+s,cz-s, cx+s,cy+s,cz-s, r,g,b,a); // -Z
            quad(buf, poseStack.last().pose(),
                cx-s,cy-s,cz-s, cx-s,cy-s,cz+s, cx-s,cy+s,cz+s, cx-s,cy+s,cz-s, r,g,b,a); // -X
            quad(buf, poseStack.last().pose(),
                cx+s,cy-s,cz+s, cx+s,cy-s,cz-s, cx+s,cy+s,cz-s, cx+s,cy+s,cz+s, r,g,b,a); // +X
            quad(buf, poseStack.last().pose(),
                cx-s,cy+s,cz+s, cx+s,cy+s,cz+s, cx+s,cy+s,cz-s, cx-s,cy+s,cz-s, r,g,b,a); // +Y
            quad(buf, poseStack.last().pose(),
                cx-s,cy-s,cz-s, cx+s,cy-s,cz-s, cx+s,cy-s,cz+s, cx-s,cy-s,cz+s, r,g,b,a); // -Y
        }
        flushQuads(buf);
    }

    /**
     * 1.21.1 的 Tesselator 走新 API: build() 拿 MeshData, BufferUploader.drawWithShader 提交.
     * 旧版 tesselator.end() 已删除. 我们要分两条不同 path:
     * - DEBUG_LINES 用 {@code POSITION_COLOR} shader + 默认 blend
     * - QUADS 用 {@code POSITION_COLOR} shader + 默认 blend
     * 两者都关 depthMask / disableCull 即可.
     */
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
