package com.prefab.addon.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.config.PlayerPreferences;
import com.prefab.structures.base.BuildBlock;
import com.prefab.structures.base.Structure;
import com.prefab.structures.config.StructureConfiguration;
import com.prefab.structures.render.StructureRenderHandler;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 自定义建筑预览的 3D 渲染 - 真实半透明"幽灵方块", 异步按批生成.
 *
 * <h3>异步策略</h3>
 * 大型 litematic (4w+ 方块) 在主线程一次性做 {@code BakedModel.getQuads} 会卡死 1+ 秒.
 * 改成:
 * <ol>
 *   <li>{@link AsyncPreviewBatcher}: 启动一个常驻后台线程, 持续从待处理列表取方块,
 *       做 BakedModel 烘焙 (BakedModel 在多数 mod 实现下读安全), 推到已就绪列表.</li>
 *   <li>主线程的 {@code onRenderLevel} 只渲染**已就绪**方块, 不阻塞.</li>
 *   <li>每批处理的方块百分比由 {@link PlayerPreferences#getPreviewBatchPercent()} 控制
 *       (默认 10%, 玩家可在挑战模式设置里调).</li>
 * </ol>
 *
 * <h3>进度显示</h3>
 * 通过 {@link StructurePreviewHud} 在屏幕右上角显示 "生成预览: 30% (12000/40000)".
 *
 * <h3>线程安全</h3>
 * - 待处理列表 / 已就绪列表: {@link CopyOnWriteArrayList} (写入极少, 读取频繁, 适合)
 * - 进度: {@link AtomicInteger}
 * - 取消标志: {@link AtomicReference}{@code <Boolean>}
 * - 启动新批次前会取消上一次任务, 避免老结构的方块渲染到新结构
 */
@EventBusSubscriber(modid = PrefabCustomAddon.MOD_ID, value = Dist.CLIENT)
public class CustomStructurePreviewRenderer {

    // 调试节流: 每 60 帧输出一次 PREVIEW-RENDER 摘要, 避免日志刷屏
    private static int debugFrameCount = 0;
    private static long lastDebugLogMs = 0L;
    private static final int LOG_THROTTLE_MS = 1500;

    // 上次处理的 structure 引用 + cfg.pos, 用于检测结构切换 / 移动 / 旋转
    private static volatile Structure lastRenderedStructure = null;
    private static volatile Structure lastRenderedConfigurationStructure = null;
    private static volatile net.minecraft.core.BlockPos lastRenderedPos = null;
    private static volatile net.minecraft.core.Direction lastRenderedFacing = null;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // AFTER_BLOCK_ENTITIES 阶段: 方块实体已画完, 线框不会被方块实体遮挡
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;

        // 关键: 读我们 own 的 ADDON_PREVIEW_STRUCTURE / ADDON_PREVIEW_CONFIG,
        // 不是 prefab 的 StructureRenderHandler.currentStructure / currentConfiguration.
        //
        // 原因: prefab 自己的 renderStructurePreview (RenderIndicatorMixin 注入) 会
        //   **无条件**画 prefab 的 currentStructure, 没有"是不是 addon 启动的"判断.
        //   如果我们读 prefab 的字段, 就需要额外判断 isCurrentPreviewStartedByAddon()
        //   才能避免 prefab 自己画我们也在画 → "两个预览".
        //   现在的方案 (CustomStructureGui.handlePreviewButtonClick):
        //     setStructure(structure, cfg) 立即 setStructure(null, null) 把 prefab 的
        //     currentStructure 清成 null, prefab 的 renderer 看到 null 就 return, 永远不画
        //     我们的预览. 我们读 ADDON_PREVIEW_* 字段, 跟 prefab 隔离, 永远只画 1 份.
        Structure currentStructure =
            com.prefab.addon.client.gui.CustomStructureGui.getAddonPreviewStructure();
        StructureConfiguration currentConfiguration =
            com.prefab.addon.client.gui.CustomStructureGui.getAddonPreviewConfig();

        if (currentStructure == null || currentConfiguration == null) return;
        if (currentConfiguration.pos == null) return;

        // === 检测结构切换 / 移动 / 旋转: 重置批处理状态 ===
        // 之前只检测 structure 引用变化, 没检测 cfg.pos 变化, 玩家在异步生成过程中移动
        // 会出现: 已烘焙的方块跟着新 cfg.pos 走, 还在 pending 队列的方块要等下一 tick
        // 才被处理 (并且用新 blockPos), 中间这一帧不显示 → 错位.
        // 现在: 任何变化 (切换 / 移动 / 旋转) 都 requestRestart, 整个会话作废, 重新
        // 把所有 BuildBlock 入队 (带新 blockPos), 异步线程一次性全烘焙 (AsyncPreviewBatcher
        // 已经改成 batchSize=totalBlocks). 视觉上 1-2 帧内完成, 无缝.
        boolean needsRestart = false;
        if (currentStructure != lastRenderedStructure) {
            needsRestart = true;
        } else if (currentConfiguration.pos != lastRenderedPos
                || currentConfiguration.houseFacing != lastRenderedFacing) {
            needsRestart = true;
        }
        if (needsRestart) {
            AsyncPreviewBatcher.requestRestart(currentStructure);
            lastRenderedStructure = currentStructure;
            lastRenderedPos = currentConfiguration.pos;
            lastRenderedFacing = currentConfiguration.houseFacing;
        }

        // === 触发后台批处理 (如果没在跑) ===
        AsyncPreviewBatcher.ensureRunning(currentStructure);

        // === 从 event 取 camera, 跟原版 Prefab 一模一样 ===
        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();

        // === 关键: 直接用 event.getPoseStack(), 不做任何额外 translate ===
        PoseStack poseStack = event.getPoseStack();

        // === 关键: 用 MultiBufferSource 而不是 Tesselator ===
        MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();

        int blocksDrawn = 0;
        int blocksSkipped = 0;
        int blocksReady = 0;
        BlockPos firstBadPos = null;
        String firstBadReason = null;

        try {
            List<BuildBlock> blocks = currentStructure.getBlocks();
            if (blocks == null) return;

            // === 取已就绪方块 (background 线程烘焙完成的) ===
            List<AsyncPreviewBatcher.ReadyBlock> readyList =
                AsyncPreviewBatcher.getReadyBlocks(currentStructure);

            BlockRenderDispatcher brd = Minecraft.getInstance().getBlockRenderer();

            for (AsyncPreviewBatcher.ReadyBlock ready : readyList) {
                blocksReady++;
                try {
                    BuildBlock buildBlock = ready.buildBlock;
                    BlockPos buildBlockPos = buildBlock.blockPos;
                    if (buildBlockPos == null) { blocksSkipped++; continue; }

                    // 距离检查: 跳过距离太远的方块 (> 128 格)
                    double dx = buildBlockPos.getX() + 0.5 - cameraPos.x;
                    double dy = buildBlockPos.getY() + 0.5 - cameraPos.y;
                    double dz = buildBlockPos.getZ() + 0.5 - cameraPos.z;
                    if (dx * dx + dy * dy + dz * dz > 128 * 128) { blocksSkipped++; continue; }

                    BlockState state = buildBlock.getBlockState();
                    if (state == null) { blocksSkipped++; continue; }
                    if (state.getBlock() == net.minecraft.world.level.block.Blocks.AIR) { blocksSkipped++; continue; }
                    if (state.getRenderShape() == RenderShape.INVISIBLE) {
                        // INVISIBLE 用白玻璃替代
                        state = net.minecraft.world.level.block.Blocks.WHITE_STAINED_GLASS.defaultBlockState();
                    }

                    poseStack.pushPose();
                    poseStack.translate(
                        buildBlockPos.getX() - cameraPos.x,
                        buildBlockPos.getY() - cameraPos.y,
                        buildBlockPos.getZ() - cameraPos.z
                    );

                    VertexConsumer ghostBuf = bufferSource.getBuffer(RenderType.entityTranslucent(
                        net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS));

                    int color = Minecraft.getInstance().getBlockColors().getColor(state,
                        Minecraft.getInstance().level, buildBlockPos, 0);
                    float r = (float)(color >> 16 & 255) / 255.0F;
                    float g = (float)(color >> 8 & 255) / 255.0F;
                    float b = (float)(color & 255) / 255.0F;

                    // 1.21.1: ghostBuf (MultiBufferSource 返回的 BufferBuilder) 实现的是
                    //   IVertexConsumerExtension, 其 putBulkData(Pose, BakedQuad,
                    //   r, g, b, alpha, light, overlay, readAlpha) 接收 9 个参数.
                    //   VertexConsumer 基类的 putBulkData 没有 readAlpha 参数 (8 个).
                    //   这里 cast 到 IVertexConsumerExtension 才能调到 9 参数重载.
                    net.neoforged.neoforge.client.extensions.IVertexConsumerExtension vertexExt =
                        (net.neoforged.neoforge.client.extensions.IVertexConsumerExtension) ghostBuf;
                    for (var quad : ready.quads) {
                        vertexExt.putBulkData(
                            poseStack.last(), quad,
                            r, g, b, 0.45F,
                            0xF000F0,
                            OverlayTexture.NO_OVERLAY,
                            false
                        );
                    }

                    poseStack.popPose();
                    blocksDrawn++;
                } catch (Throwable t) {
                    blocksSkipped++;
                    if (firstBadPos == null) firstBadPos = ready.buildBlock != null ? ready.buildBlock.blockPos : null;
                    if (firstBadReason == null) firstBadReason = "exception: " + t.getClass().getSimpleName() + ": " + t.getMessage();
                }
            }

            // 更新 HUD 进度
            int total = blocks.size();
            int processed = AsyncPreviewBatcher.getProcessedCount(currentStructure);
            StructurePreviewHud.updateProgress(currentStructure, processed, total);

        } finally {
            bufferSource.endBatch();
        }

        // 调试日志节流: 每 1.5s 最多记一次
        debugFrameCount++;
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastDebugLogMs > LOG_THROTTLE_MS) {
            lastDebugLogMs = nowMs;
            int structBlockCount = currentStructure.getBlocks() != null ? currentStructure.getBlocks().size() : 0;
            PrefabCustomAddon.LOGGER.info(
                "[PREVIEW-RENDER] drawn={} skipped={} ready={} total={} pos={} facing={} structBlocks={} cameraPos=({},{},{}) {}",
                blocksDrawn, blocksSkipped, blocksReady, structBlockCount,
                currentConfiguration.pos, currentConfiguration.houseFacing,
                structBlockCount,
                (int) cameraPos.x, (int) cameraPos.y, (int) cameraPos.z,
                firstBadReason != null ? "firstReason=" + firstBadReason : ""
            );
        }
    }
}
