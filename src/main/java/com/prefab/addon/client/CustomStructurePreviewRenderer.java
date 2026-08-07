package com.prefab.addon.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.prefab.PrefabBase;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.structures.base.BuildBlock;
import com.prefab.structures.base.Structure;
import com.prefab.structures.config.StructureConfiguration;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自定义建筑预览 (addon 启动的预览, 不是 prefab 原版的).
 *
 * <h2>渲染模式</h2>
 * 走 prefab 一样的路径: 调 {@link BuildBlock#SetBlockState} 处理所有 property 类型
 * (facing/rotation/axis/vine/wall/cross-collision/lever/trapdoor/stairs/glass),
 * 装饰方块方向 100% 正确.
 *
 * <h2>为啥不直接用 prefab 的 StructureRenderHandler</h2>
 * prefab 内部的 {@code bakeBlockAndSubBlock} 对每个方块位置做
 * {@code worldState.isAir()} 检查, 不是空气就跳过 (设计意图: 避免预览方块穿透实心方块).
 * 我们的自定义建筑 24x20x26, 玩家站在地面打开 GUI 时, 建筑大部分方块跟地面/树/墙重叠,
 * prefab 全部跳过 → 什么都不显示.
 *
 * <h2>为啥不缓存 VertexBuffer</h2>
 * 每帧重新烘焙所有 BuildBlock quads 写入 MultiBufferSource.
 * 2877 块 × ~20 quads = 57540 quads/帧, CPU 成本 5-10ms, 接受 (20 TPS 渲染可承受).
 * 不缓存避免: 1) VertexBuffer API 复杂, 跨版本易错; 2) 状态管理 (pos/facing 变化时清空); 3) Shader/RenderSystem 状态污染.
 *
 * <h2>闪烁问题</h2>
 * 用 {@code RenderType.entityCutout(LOCATION_BLOCKS)} 走 alpha-test 路径, 严格 depth-test
 * 不闪烁. Trade-off: 玻璃/灯笼等 alpha<0.5 半透明方块 alpha-test 失败 → 透明消失.
 * 这是 prefab 原版预览的同一个 trade-off (prefab 也用 entityCutout 系).
 *
 * <h2>缓存 hit 检测</h2>
 * {@code lastRenderedStructure / Pos / Facing} 变化时记录日志 (调试用), 但不需清缓存 (无缓存).
 */
@EventBusSubscriber(modid = "prefab_custom_addon", value = Dist.CLIENT)
public class CustomStructurePreviewRenderer {

    private static final AtomicBoolean firstErrorLogged = new AtomicBoolean(false);

    /** 上次成功渲染时的引用/位置/朝向 — 用于日志, 调试 "为啥不动" 的问题 */
    private static volatile Structure lastRenderedStructure = null;
    private static volatile BlockPos lastRenderedPos = null;
    private static volatile Direction lastRenderedFacing = null;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;

        Structure currentStructure =
            com.prefab.addon.client.gui.CustomStructureGui.getAddonPreviewStructure();
        StructureConfiguration currentConfiguration =
            com.prefab.addon.client.gui.CustomStructureGui.getAddonPreviewConfig();

        if (currentStructure == null || currentConfiguration == null) return;
        if (currentConfiguration.pos == null) return;

        // pos/facing 变化时记日志, 调试 "为啥预览不跟手" 用
        if (currentStructure != lastRenderedStructure
                || currentConfiguration.pos != lastRenderedPos
                || currentConfiguration.houseFacing != lastRenderedFacing) {
            PrefabCustomAddon.LOGGER.info("[PREVIEW-RENDER] state changed: pos {} facing {} blocks {}",
                currentConfiguration.pos, currentConfiguration.houseFacing,
                currentStructure.getBlocks() != null ? currentStructure.getBlocks().size() : 0);
            lastRenderedStructure = currentStructure;
            lastRenderedPos = currentConfiguration.pos;
            lastRenderedFacing = currentConfiguration.houseFacing;
        }

        try {
            renderPreview(currentStructure, currentConfiguration, event);
        } catch (Throwable t) {
            if (firstErrorLogged.compareAndSet(false, true)) {
                PrefabCustomAddon.LOGGER.error("[PREVIEW-RENDER] fatal render exception", t);
            }
        }
    }

    private static void renderPreview(
            Structure currentStructure,
            StructureConfiguration currentConfiguration,
            RenderLevelStageEvent event) {

        Minecraft mc = Minecraft.getInstance();
        BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        PoseStack poseStack = event.getPoseStack();
        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();

        // entityCutout 走 alpha-test, depth-test 严格, 不闪烁.
        // 玻璃/灯笼等 alpha<0.5 半透明方块会透明消失 (跟 prefab 原版预览的 trade-off 一致).
        VertexConsumer buf = buffers.getBuffer(RenderType.entityCutout(
            net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS));

        List<BuildBlock> blocks = currentStructure.getBlocks();
        if (blocks == null) return;

        int drawn = 0;
        int skipped = 0;
        BlockPos firstBadPos = null;
        String firstBadReason = null;

        for (BuildBlock blockInfo : blocks) {
            try {
                // 用 prefab 的 BuildBlock.SetBlockState 处理所有 property 类型 → 装饰方块方向 100% 正确
                BlockState state = blockInfo.getBlockState();
                if (state == null) { skipped++; continue; }
                if (state.getBlock() == net.minecraft.world.level.block.Blocks.AIR) { skipped++; continue; }
                if (state.getRenderShape() == RenderShape.INVISIBLE) { skipped++; continue; }

                // 计算 rotated pos: prefab 内部用 getRelativePosition(basePos, clearSpace.direction, houseFacing)
                BlockPos pos = blockInfo.getStartingPosition().getRelativePosition(
                        currentConfiguration.pos,
                        currentStructure.getClearSpace().getShape().getDirection(),
                        currentConfiguration.houseFacing);

                // prefab 的 SetBlockState 内部会读 level.getBlockState(pos) 来判断 subBlock 等,
                //   也会修改 BuildBlock.state. 必须每次新建一个 "工作副本" 避免破坏当前 buildBlock.
                BuildBlock working = BuildBlock.SetBlockState(
                        currentConfiguration,
                        mc.level,
                        currentConfiguration.pos,
                        blockInfo,
                        state.getBlock(),
                        state,
                        currentStructure);
                // SetBlockState 内部会调 block.setStartingPosition(...) 重新计算 offset, 但 blockPos
                //   留空. 强制用我们算的 rotatedPos.
                working.blockPos = pos;

                // 取 baked model — BuildBlock 没有 getShape(), 自己从 BlockRenderDispatcher 取
                // 用 var 不显式 import BakedModel (跟 AsyncPreviewBatcher.java:276 同样套路)
                var model = blockRenderer.getBlockModel(state);
                List<BakedQuad> quadList = new ArrayList<>();
                RandomSource rng = RandomSource.create(42L);
                // null = 未指定方向 (跨方向 quads, 比如草、藤蔓、kelp)
                quadList.addAll(model.getQuads(state, null, rng));
                for (Direction dir : Direction.values()) {
                    quadList.addAll(model.getQuads(state, dir, rng));
                }
                if (quadList.isEmpty()) { skipped++; continue; }

                poseStack.pushPose();
                poseStack.translate(
                    pos.getX() - camPos.x,
                    pos.getY() - camPos.y,
                    pos.getZ() - camPos.z);

                int color = mc.getBlockColors().getColor(state, mc.level, pos, 0);
                float r = (float) (color >> 16 & 255) / 255.0F;
                float g = (float) (color >> 8 & 255) / 255.0F;
                float b = (float) (color & 255) / 255.0F;

                for (BakedQuad quad : quadList) {
                    buf.putBulkData(poseStack.last(), quad, r, g, b, 1.0F,
                        0xF000F0, OverlayTexture.NO_OVERLAY, false);
                }

                poseStack.popPose();
                drawn++;
            } catch (Throwable t) {
                skipped++;
                if (firstBadPos == null) firstBadPos = blockInfo != null ? blockInfo.blockPos : null;
                if (firstBadReason == null) firstBadReason = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
        }

        buffers.endBatch();

        // 调试: 每 60 帧 (~3 秒) 记一次
        if ((mc.player.tickCount % 60) == 0) {
            PrefabCustomAddon.LOGGER.info(
                "[PREVIEW-RENDER] drawn={} skipped={} total={} pos={} facing={} {}",
                drawn, skipped, blocks.size(),
                currentConfiguration.pos, currentConfiguration.houseFacing,
                firstBadReason != null ? "first=" + firstBadReason : "");
        }
    }
}
