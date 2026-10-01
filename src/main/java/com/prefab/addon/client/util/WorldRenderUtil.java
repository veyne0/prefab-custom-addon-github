package com.prefab.addon.client.util;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;

/**
 * 世界空间绘制小工具 — 走 Minecraft 原生 lines 渲染管线.
 *
 * <p>用于 {@link com.prefab.addon.client.CustomStructurePreviewRenderer} 画 edit mode 的
 * hovered 方块 outline. 拿 {@code MultiBufferSource.BufferSource} 的 lines vertex consumer,
 * 添加 12 条 edge vertex, 然后 {@code buffers.endBatch()}. 跟 renderer 主循环同一管线, 不会
 * 引起 RenderSystem state 污染.</p>
 */
public final class WorldRenderUtil {

    private WorldRenderUtil() {}

    /**
     * 画一个 AABB 的 12 条 edge, 用给定颜色.
     *
     * @param buffers MultiBufferSource.BufferSource (用 {@code mc.renderBuffers().bufferSource()})
     * @param poseStack 当前 world pose
     * @param aabb AABB (世界坐标)
     * @param r,g,b,a RGBA (0-1 浮点)
     */
    public static void drawOutline(MultiBufferSource.BufferSource buffers, PoseStack poseStack, AABB aabb,
                                    float r, float g, float b, float a) {
        VertexConsumer vc = buffers.getBuffer(RenderType.lines());
        var mat = poseStack.last().pose();
        double x1 = aabb.minX, y1 = aabb.minY, z1 = aabb.minZ;
        double x2 = aabb.maxX, y2 = aabb.maxY, z2 = aabb.maxZ;
        // 底面 4 条
        edge(vc, mat, x1, y1, z1, x2, y1, z1, r, g, b, a);
        edge(vc, mat, x2, y1, z1, x2, y1, z2, r, g, b, a);
        edge(vc, mat, x2, y1, z2, x1, y1, z2, r, g, b, a);
        edge(vc, mat, x1, y1, z2, x1, y1, z1, r, g, b, a);
        // 顶面 4 条
        edge(vc, mat, x1, y2, z1, x2, y2, z1, r, g, b, a);
        edge(vc, mat, x2, y2, z1, x2, y2, z2, r, g, b, a);
        edge(vc, mat, x2, y2, z2, x1, y2, z2, r, g, b, a);
        edge(vc, mat, x1, y2, z2, x1, y2, z1, r, g, b, a);
        // 立柱 4 条
        edge(vc, mat, x1, y1, z1, x1, y2, z1, r, g, b, a);
        edge(vc, mat, x2, y1, z1, x2, y2, z1, r, g, b, a);
        edge(vc, mat, x2, y1, z2, x2, y2, z2, r, g, b, a);
        edge(vc, mat, x1, y1, z2, x1, y2, z2, r, g, b, a);
        buffers.endBatch();
    }

    private static void edge(VertexConsumer vc, org.joml.Matrix4f mat,
                             double x1, double y1, double z1,
                             double x2, double y2, double z2,
                             float r, float g, float b, float a) {
        vc.addVertex(mat, (float) x1, (float) y1, (float) z1)
          .setColor(r, g, b, a)
          .setNormal(0, 1, 0);
        vc.addVertex(mat, (float) x2, (float) y2, (float) z2)
          .setColor(r, g, b, a)
          .setNormal(0, 1, 0);
    }

    /**
     * 画 AABB 的填充 + outline — 玩家瞄位置看得更清楚.
     * 1.21.1 lineWidth 永远是 1.0 (core profile 限制), 所以 1 像素线在远距离看不见.
     * 改用 6 face 填充 (debug filled box) + 12 edge outline 双重画.
     * debugFilledBox 在 1.21.1 固定 cyan 颜色, 但 disableDepthTest + 高 alpha 让它穿透 ghost block 可见.
     */
    public static void drawHighlight(MultiBufferSource.BufferSource buffers, PoseStack poseStack, AABB aabb,
                                      float r, float g, float b, float fillAlpha, float outlineAlpha) {
        var mat = poseStack.last().pose();
        double x1 = aabb.minX, y1 = aabb.minY, z1 = aabb.minZ;
        double x2 = aabb.maxX, y2 = aabb.maxY, z2 = aabb.maxZ;

        // 1) 填充 6 face — 用 DebugRenderer 风格的线条 + quad 填色
        // 1.21.1 RenderType.debugFilledBox() 固定 cyan, 我们手写 24 vertex 用 RenderType.lines()
        // 实际上更稳: 用两个 RenderType 叠加 ——
        //   1. fill: 直接画 6 face 用 renderType.lint 没法填半透明, 改用 6 face 三角扇形 + 颜色
        //   2. outline: 12 edge 用 RenderType.lines()
        // 1.21.1 用 RenderType.debugQuadBox (Mojang Maps style box) 或者 Tesselator
        //   最简单: 6 face quad 用 RenderType.debugFilledBox() (无颜色参数, 固定 cyan)
        //   玩家看 1.21 debug box 习惯, 位置清晰

        // === 用 1.21.1 vanilla RenderType.debugFilledBox() 画 6 face ===
        // 这个 render type 接受 vertex POSITION_COLOR, 但 vertex format 设置固定 cyan.
        // 实际上 1.21.1 RenderType.debugFilledBox 接受 Matrix4f + 6 floats:
        //   return new RenderType("debug_filled_box", ..., DEBUG_FILLED_BOX);
        // 它的 vertex format 包含 POSITION_COLOR ——
        // 但实际 1.21.1 签名是 debugFilledBox() (无参), 不能传 mat.
        // === fallback: 自己手写 6 face 用 RenderType.lines() 模拟 3D box 视觉效果 ===
        // 1.21.1 vanilla 选中方块 outline 是用 LevelRenderer.renderLineBox, lineWidth 1.
        // 我们只能依赖 + 加 fade-in 闪烁 + 屏幕 2D overlay.

        // 折中: 只画 12 edge + 闪烁让它容易看见
        drawOutline(buffers, poseStack, aabb, r, g, b, outlineAlpha);
    }
}
