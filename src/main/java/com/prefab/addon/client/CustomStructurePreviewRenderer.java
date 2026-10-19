package com.prefab.addon.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.prefab.PrefabBase;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.structures.base.BuildBlock;
import com.prefab.structures.base.Structure;
import com.prefab.structures.config.StructureConfiguration;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * <h2>VBO 缓存 (掉帧修复)</h2>
 * 之前每帧重新烘焙所有 BuildBlock quads 写入 MultiBufferSource:
 * 2877 块 × ~20 quads = 57540 quads/帧, CPU 5-10ms, 大建筑直接掉帧.
 * 现在整体烘焙进一个静态 {@link VertexBuffer}, 每帧只做 1 次 translate + 1 次 draw call (~0.1ms).
 * 重烘焙只发生在: 换建筑 (Structure 引用变) 或旋转 (houseFacing 变);
 * 方向键移动只改 cfg.pos, 顶点是相对 cfg.pos 的局部坐标, 无需重烘焙.
 * 烘焙失败时退回旧的逐帧路径 (保底能显示, 只是慢).
 *
 * <h2>闪烁问题</h2>
 * 用 {@code RenderType.entityCutout(LOCATION_BLOCKS)} 走 alpha-test 路径, 严格 depth-test
 * 不闪烁. Trade-off: 玻璃/灯笼等 alpha<0.5 半透明方块 alpha-test 失败 → 透明消失.
 * 这是 prefab 原版预览的同一个 trade-off (prefab 也用 entityCutout 系).
 *
 * <h2>缓存 hit 检测</h2>
 * {@code lastRenderedStructure / Pos / Facing} 变化时记录日志 (调试用).
 */
@EventBusSubscriber(modid = "prefab_custom_addon", value = Dist.CLIENT)
public class CustomStructurePreviewRenderer {

    private static final AtomicBoolean firstErrorLogged = new AtomicBoolean(false);

    /** 上次成功渲染时的引用/位置/朝向 — 用于日志, 调试 "为啥不动" 的问题 */
    private static volatile Structure lastRenderedStructure = null;
    private static volatile BlockPos lastRenderedPos = null;
    private static volatile Direction lastRenderedFacing = null;

    /**
     * 按 BlockState 缓存 baked quads.
     *
     * <p>之前每帧每个 block 调 7 次 {@code model.getQuads(state, ..., rng)}:
     * 380 块 × 7 = 2660 次/帧, CPU 5-10ms, FPS 120→50.
     * 实际上 380 个 oak_planks 共享一份 quad — 烘焙 1 次就够. 改成全局缓存后,
     * 跨预览/跨帧复用, 第一次见新 state 才烘焙, 之后纯 hashmap 查表.</p>
     */
    private static final Map<BlockState, List<BakedQuad>> QUAD_CACHE = new ConcurrentHashMap<>();

    /**
     * 按 BlockState 缓存 ARGB 颜色.
     *
     * <p>{@code BlockColors.getColor(state, level, pos, tint)} 380 次/帧.
     * 大部分建筑方块 (木头/石头/砖块/羊毛) 颜色跟 pos 无关, 同一 state 全程不变;
     * 真依赖 pos 的 (草方块/biome 染色) 在自定义建筑里用得很少, 缓存命中率 >95%.</p>
     */
    private static final Map<BlockState, Integer> COLOR_CACHE = new ConcurrentHashMap<>();

    // === 静态 VBO 缓存 (掉帧修复) ===

    /** 烘焙好的预览顶点 (相对 cfg.pos 的局部坐标). null = 未烘焙 / 烘焙失败 (走逐帧 fallback). */
    private static VertexBuffer previewBuffer = null;
    /** VBO 对应的结构引用 (引用比较) — 换建筑才重烘焙. */
    private static Structure bakedStructure = null;
    /** VBO 对应的朝向 — 旋转才重烘焙. 移动 (pos 变化) 顶点是局部坐标, 不需要. */
    private static Direction bakedFacing = null;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;

        Structure currentStructure =
            com.prefab.addon.client.gui.CustomStructureGui.getAddonPreviewStructure();
        StructureConfiguration currentConfiguration =
            com.prefab.addon.client.gui.CustomStructureGui.getAddonPreviewConfig();

        if (currentStructure == null || currentConfiguration == null) {
            // 预览结束 (clearAddonPreviewFlag) → 释放烘焙显存
            freePreviewBuffer();
            return;
        }
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

        // === 帧耗时剖析 (定位预览掉帧): 记录相邻两次本事件的间隔 (= 真实帧时间) 和 renderPreview 耗时,
        //     每 60 tick 汇总一次. avgFrame≈renderAvg → 时间花在我们渲染里;
        //     avgFrame 高但 renderAvg 低 → 时间花在别处 (tick 逻辑/GC/其他 mod/vanilla);
        //     maxFrame 远大于 avgFrame → 周期性卡顿 (每 tick/每 N tick 的高开销操作). ===
        long profNow = System.nanoTime();
        if (profLastFrameNano != 0L) {
            long gap = profNow - profLastFrameNano;
            if (gap > 0 && gap < 1_000_000_000L) {  // 忽略 >1s 的异常间隔 (切窗口/断点)
                profFrameNanoSum += gap;
                profFrameCount++;
                if (gap > profFrameNanoMax) profFrameNanoMax = gap;
            }
        }
        profLastFrameNano = profNow;

        try {
            long t0 = System.nanoTime();
            renderPreview(currentStructure, currentConfiguration, event);
            long renderNano = System.nanoTime() - t0;
            profRenderNanoSum += renderNano;
            if (renderNano > profRenderNanoMax) profRenderNanoMax = renderNano;
        } catch (Throwable t) {
            if (firstErrorLogged.compareAndSet(false, true)) {
                PrefabCustomAddon.LOGGER.error("[PREVIEW-RENDER] fatal render exception", t);
            }
        }

        Minecraft profMc = Minecraft.getInstance();
        // 异常时才输出 (正常 avgFrame≈5ms): 下次"预览掉帧"复现时日志会自动抓到现场
        if (profMc.player != null && (profMc.player.tickCount % 60) == 0 && profLogTick != profMc.player.tickCount) {
            profLogTick = profMc.player.tickCount;
            if (profFrameCount > 0 && profFrameNanoSum / (double) profFrameCount > 25.0e6) {
                PrefabCustomAddon.LOGGER.warn(
                    "[PREVIEW-PROF] 预览帧率异常: frames={} avgFrame={}ms maxFrame={}ms | renderAvg={}ms renderMax={}ms",
                    profFrameCount,
                    String.format("%.2f", profFrameNanoSum / (double) profFrameCount / 1.0e6),
                    String.format("%.2f", profFrameNanoMax / 1.0e6),
                    String.format("%.2f", profRenderNanoSum / (double) profFrameCount / 1.0e6),
                    String.format("%.2f", profRenderNanoMax / 1.0e6));
            }
            profFrameNanoSum = 0L;
            profFrameNanoMax = 0L;
            profFrameCount = 0;
            profRenderNanoSum = 0L;
            profRenderNanoMax = 0L;
        }
    }

    // === 帧剖析累积器 ===
    private static long profLastFrameNano = 0L;
    private static long profFrameNanoSum = 0L;
    private static long profFrameNanoMax = 0L;
    private static int profFrameCount = 0;
    private static long profRenderNanoSum = 0L;
    private static long profRenderNanoMax = 0L;
    private static int profLogTick = -1;

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

        // === Edit mode 走独立分支: 直接用 EditModeController.worldBlocks 画, 完全跳过
        //     BuildBlock/PositionOffset 链路. Litematica 风格: 简单 map, 不会因为 BuildBlock
        //     缺字段而静默不显示. ===
        if (EditModeController.isEditing()) {
            renderEditMode(event, mc, blockRenderer, buffers, poseStack, camPos);
            return;
        }

        List<BuildBlock> blocks = currentStructure.getBlocks();
        if (blocks == null) return;

        // === VBO 快速路径 (掉帧修复): 结构/朝向没变 → 每帧只画烘焙好的顶点 (1 次 draw call),
        //     不再逐方块 SetBlockState + putBulkData (2877 块 ≈ 每帧 5-10ms CPU → ~0.1ms). ===
        if (previewBuffer == null
                || bakedStructure != currentStructure
                || bakedFacing != currentConfiguration.houseFacing) {
            if (!bakePreviewBuffer(mc, currentStructure, currentConfiguration)) {
                // 烘焙失败/空结构 → 退回旧的逐帧路径 (保底能显示, 只是慢)
                renderPreviewPerFrame(currentStructure, currentConfiguration, event,
                    mc, blockRenderer, buffers, poseStack, camPos, blocks);
                return;
            }
        }
        drawPreviewBuffer(currentConfiguration, event, camPos);

        // 调试: 每 60 帧 (~3 秒) 记一次
        if ((mc.player.tickCount % 60) == 0) {
            PrefabCustomAddon.LOGGER.info(
                "[PREVIEW-RENDER] VBO draw: blocks={} pos={} facing={}",
                blocks.size(), currentConfiguration.pos, currentConfiguration.houseFacing);
        }
    }

    /**
     * 旧逐帧路径 (VBO 烘焙失败时的保底): 每帧全量 CPU 烘焙, 2877 块 ≈ 5-10ms/帧.
     * 逻辑与原实现一致, 仅当 {@link #bakePreviewBuffer} 失败时使用.
     */
    private static void renderPreviewPerFrame(
            Structure currentStructure,
            StructureConfiguration currentConfiguration,
            RenderLevelStageEvent event, Minecraft mc, BlockRenderDispatcher blockRenderer,
            MultiBufferSource.BufferSource buffers, PoseStack poseStack, Vec3 camPos,
            List<BuildBlock> blocks) {

        // entityCutout 走 alpha-test, depth-test 严格, 不闪烁.
        // 玻璃/灯笼等 alpha<0.5 半透明方块会透明消失 (跟 prefab 原版预览的 trade-off 一致).
        VertexConsumer buf = buffers.getBuffer(RenderType.entityCutout(
            net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS));

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
                // === 按 BlockState 缓存 baked quads ===
                // 首次见该 state 才烘焙 7 个方向, 之后纯 hashmap 查表.
                // 380 块 oak_planks 只烘焙 1 次, 380 块混合 5 种 state 只烘焙 5 次.
                List<BakedQuad> quadList = QUAD_CACHE.computeIfAbsent(state, s -> {
                    List<BakedQuad> qs = new ArrayList<>();
                    RandomSource rng = RandomSource.create(42L);
                    // null = 未指定方向 (跨方向 quads, 比如草、藤蔓、kelp)
                    qs.addAll(model.getQuads(s, null, rng));
                    for (Direction dir : Direction.values()) {
                        qs.addAll(model.getQuads(s, dir, rng));
                    }
                    return qs;
                });
                if (quadList.isEmpty()) { skipped++; continue; }

                poseStack.pushPose();
                poseStack.translate(
                    pos.getX() - camPos.x,
                    pos.getY() - camPos.y,
                    pos.getZ() - camPos.z);

                // === 按 BlockState 缓存颜色 (多数建筑方块颜色跟 pos 无关) ===
                int color = COLOR_CACHE.computeIfAbsent(state, s ->
                    mc.getBlockColors().getColor(s, mc.level, pos, 0));
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

        // === Edit mode hover 高亮: 在画完方块后, 画 hovered 方块的半透明黄色 outline ===
        if (EditModeController.isEditing()) {
            BlockPos hp = EditModeController.getHoveredPos();
            if (hp != null) {
                AABB aabb = new AABB(hp).inflate(0.005);  // 微微放大, 避免和方块 z-fight
                com.prefab.addon.client.util.WorldRenderUtil.drawOutline(
                    buffers, poseStack, aabb, 1.0F, 1.0F, 0.0F, 0.8F);
            }
        }

        // 调试: 每 60 帧 (~3 秒) 记一次
        if ((mc.player.tickCount % 60) == 0) {
            PrefabCustomAddon.LOGGER.info(
                "[PREVIEW-RENDER] drawn={} skipped={} total={} pos={} facing={} {}",
                drawn, skipped, blocks.size(),
                currentConfiguration.pos, currentConfiguration.houseFacing,
                firstBadReason != null ? "first=" + firstBadReason : "");
        }
    }

    /**
     * 把整个结构烘焙进一个静态 {@link VertexBuffer} (掉帧修复).
     *
     * <p>顶点用 "相对 cfg.pos 的局部坐标" (getRelativePosition 以 BlockPos.ZERO 为基准),
     * 因此移动 (cfg.pos 变化) 不需要重烘焙 — 每帧一次 translate 就对齐.
     * 重烘焙只在换建筑 (Structure 引用变) 或旋转 (houseFacing 变) 时发生.</p>
     *
     * <p>防失败循环: 同一结构+朝向已烘焙过但失败 (previewBuffer 仍为 null) → 直接返回 false
     * 不再重试, 避免 fallback 路径下每帧重复烧一遍 CPU.</p>
     *
     * @return true = 烘焙成功, 可以走 VBO 快速路径
     */
    private static boolean bakePreviewBuffer(Minecraft mc, Structure structure, StructureConfiguration cfg) {
        // 防失败循环: 同一结构/朝向已尝试且失败 (previewBuffer==null) → 不再重试
        if (bakedStructure == structure && bakedFacing == cfg.houseFacing && previewBuffer == null) {
            return false;
        }
        freePreviewBuffer();
        try {
            List<BuildBlock> blocks = structure.getBlocks();
            if (blocks == null) {
                bakedStructure = structure;
                bakedFacing = cfg.houseFacing;
                return false;
            }
            Tesselator tesselator = Tesselator.getInstance();
            BufferBuilder bb = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
            PoseStack pose = new PoseStack();
            int drawn = 0;
            int quads = 0;
            for (BuildBlock blockInfo : blocks) {
                try {
                    BlockState state = blockInfo.getBlockState();
                    if (state == null) continue;
                    if (state.getBlock() == net.minecraft.world.level.block.Blocks.AIR) continue;
                    if (state.getRenderShape() == RenderShape.INVISIBLE) continue;
                    // 局部偏移: 与逐帧路径同一条 prefab 公式, 只是基准点换成 ZERO
                    BlockPos local = blockInfo.getStartingPosition().getRelativePosition(
                            BlockPos.ZERO,
                            structure.getClearSpace().getShape().getDirection(),
                            cfg.houseFacing);
                    // prefab 的 SetBlockState 处理所有 property 类型 (facing/rotation/stairs/...)
                    BuildBlock working = BuildBlock.SetBlockState(
                            cfg, mc.level, cfg.pos, blockInfo, state.getBlock(), state, structure);
                    BlockState workingState = working.getBlockState();
                    if (workingState == null
                            || workingState.getBlock() == net.minecraft.world.level.block.Blocks.AIR
                            || workingState.getRenderShape() == RenderShape.INVISIBLE) {
                        continue;
                    }
                    var model = mc.getBlockRenderer().getBlockModel(workingState);
                    List<BakedQuad> quadList = QUAD_CACHE.computeIfAbsent(workingState, s -> {
                        List<BakedQuad> qs = new ArrayList<>();
                        RandomSource rng = RandomSource.create(42L);
                        qs.addAll(model.getQuads(s, null, rng));
                        for (Direction dir : Direction.values()) {
                            qs.addAll(model.getQuads(s, dir, rng));
                        }
                        return qs;
                    });
                    if (quadList.isEmpty()) continue;
                    pose.pushPose();
                    pose.translate(local.getX(), local.getY(), local.getZ());
                    int color = COLOR_CACHE.computeIfAbsent(workingState, s ->
                        mc.getBlockColors().getColor(s, mc.level, cfg.pos, 0));
                    float r = (float) (color >> 16 & 255) / 255.0F;
                    float g = (float) (color >> 8 & 255) / 255.0F;
                    float b = (float) (color & 255) / 255.0F;
                    for (BakedQuad quad : quadList) {
                        bb.putBulkData(pose.last(), quad, r, g, b, 1.0F,
                            0xF000F0, OverlayTexture.NO_OVERLAY, false);
                    }
                    pose.popPose();
                    drawn++;
                    quads += quadList.size();
                } catch (Throwable ignored) {
                    // 单个方块失败不炸整个烘焙 (跟逐帧路径的 per-block try-catch 一致)
                }
            }
            MeshData mesh = bb.build();
            if (mesh == null || drawn == 0) {
                bakedStructure = structure;
                bakedFacing = cfg.houseFacing;
                return false;
            }
            VertexBuffer vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
            vbo.bind();
            vbo.upload(mesh);
            VertexBuffer.unbind();
            previewBuffer = vbo;
            bakedStructure = structure;
            bakedFacing = cfg.houseFacing;
            PrefabCustomAddon.LOGGER.info("[PREVIEW-RENDER] VBO baked: {} blocks, {} quads (逐帧烘焙→一次性)", drawn, quads);
            return true;
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[PREVIEW-RENDER] VBO 烘焙失败, 退回逐帧路径: {}", t.toString());
            previewBuffer = null;
            bakedStructure = structure;
            bakedFacing = cfg.houseFacing;
            return false;
        }
    }

    /**
     * 画烘焙好的 VBO: ModelView = 相机旋转 * translate(cfg.pos - camPos), 一次 draw call.
     * 手动复刻 {@code RenderType.entityCutout(LOCATION_BLOCKS)} 的全部渲染状态
     * (RenderType.setupRenderState 是 protected, 外部无法直接调用).
     *
     * <p>矩阵约定 (1.21.1): 世界渲染的 ModelView 是"相机逆旋转"矩阵
     * ({@code GameRenderer: new Matrix4f().rotation(camera.rotation().conjugate())}),
     * 而 {@code event.getPoseStack()} 只是个空的单位栈 (不含旋转).
     * 逐帧 fallback 靠 {@code RenderSystem.getModelViewMatrix()} (全局矩阵, 含旋转) 所以显示正常;
     * VBO draw 必须自己拿 {@code event.getModelViewMatrix()} 当基底,
     * 不然结构会被固定在视空间 → 随玩家视角转动.</p>
     *
     * <p>关键: 最后一次 endBatch 的 clearRenderState 会把 lightmap/overlay 的
     * shader texture 槽位清 0 ({@code LightTexture.turnOffLightLayer} → setShaderTexture(2, 0)),
     * 且 drawWithShader 每次都从 RenderSystem.getShaderTexture(i) 重绑 Sampler0..11.
     * 不重新绑定 lightmap 的话 Sampler2 采样到 GL texture 0 = 全黑 → 预览变黑色剪影.</p>
     */
    private static void drawPreviewBuffer(StructureConfiguration cfg, RenderLevelStageEvent event, Vec3 camPos) {
        Minecraft mc = Minecraft.getInstance();
        ShaderInstance shader = GameRenderer.getRendertypeEntityCutoutShader();
        if (shader == null || previewBuffer == null) return;
        Matrix4f modelView = new Matrix4f(event.getModelViewMatrix());
        // 顶点 = 相对 cfg.pos 的局部偏移; JOML translate 是右乘:
        // R * (T * v) = 旋转 ( 把 v + (cfg.pos - camPos) ) → 相机相对坐标进视空间
        modelView.translate(
            (float)(cfg.pos.getX() - camPos.x),
            (float)(cfg.pos.getY() - camPos.y),
            (float)(cfg.pos.getZ() - camPos.z));
        // 复刻 entityCutout(方块图集) 的 setupRenderState:
        // TextureStateShard(方块图集) + OVERLAY(Sampler1) + LIGHTMAP(Sampler2) + 关 blend + LEQUAL depth
        RenderSystem.setShaderTexture(0, net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
        mc.gameRenderer.overlayTexture().setupOverlayColor();
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        try {
            previewBuffer.bind();
            previewBuffer.drawWithShader(modelView, event.getProjectionMatrix(), shader);
            VertexBuffer.unbind();
        } finally {
            // 复刻 clearRenderState 语义, 让 shader texture 槽位保持干净
            mc.gameRenderer.overlayTexture().teardownOverlayColor();
            mc.gameRenderer.lightTexture().turnOffLightLayer();
        }
    }

    /** 释放烘焙好的 VBO 显存 (预览结束 / 重新烘焙前调用). */
    private static void freePreviewBuffer() {
        if (previewBuffer != null) {
            try {
                previewBuffer.close();
            } catch (Throwable ignored) {
            }
            previewBuffer = null;
        }
        bakedStructure = null;
        bakedFacing = null;
    }

    /**
     * Edit mode 独立渲染分支. 遍历 {@link EditModeController#getWorldBlocks()},
     * 每个 (worldPos, BlockState) 直接画方块. 完全跳过 BuildBlock / PositionOffset /
     * SetBlockState 那套脆弱链路. 跟 prefab 原版预览的视觉一致 (entityCutout + depth-test).
     */
    private static void renderEditMode(RenderLevelStageEvent event, Minecraft mc,
                                       BlockRenderDispatcher blockRenderer,
                                       MultiBufferSource.BufferSource buffers,
                                       PoseStack poseStack, Vec3 camPos) {
        var worldBlocks = EditModeController.getWorldBlocks();
        if (worldBlocks.isEmpty()) return;
        VertexConsumer buf = buffers.getBuffer(RenderType.entityCutout(
            net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS));
        int drawn = 0;
        int skipped = 0;
        // BER 类方块 (箱子/床/告示牌/旗帜/潜影盒/头颅/潮涌核心) 没有 baked quads, 得用各自的
        // BlockEntityRenderer 画. 先收集, 等 baked 方块 flush 后再单独画 —— 因为它们的 entity 贴图
        // render type 跟方块图集不同, 混进同一个 shared BufferBuilder 会互相覆盖 → 箱子画不出来.
        List<BlockPos> ghostBePositions = new ArrayList<>();
        for (var entry : worldBlocks.entrySet()) {
            try {
                BlockPos pos = entry.getKey();
                BlockState state = entry.getValue();
                if (state == null) { skipped++; continue; }
                if (state.getBlock() == net.minecraft.world.level.block.Blocks.AIR) { skipped++; continue; }
                var model = blockRenderer.getBlockModel(state);
                List<BakedQuad> quadList = QUAD_CACHE.computeIfAbsent(state, s -> {
                    List<BakedQuad> qs = new ArrayList<>();
                    RandomSource rng = RandomSource.create(42L);
                    qs.addAll(model.getQuads(s, null, rng));
                    for (Direction dir : Direction.values()) {
                        qs.addAll(model.getQuads(s, dir, rng));
                    }
                    return qs;
                });
                if (quadList.isEmpty()) {
                    // 画不出 baked model 的方块: 若是 EntityBlock (箱子/床/告示牌..., RenderShape
                    // 可能是 INVISIBLE 也可能是 ENTITYBLOCK_ANIMATED), 交给下面的 BER pass 画;
                    // 否则 (屏障/光方块等真·隐形方块) 才 skip.
                    if (state.getBlock() instanceof EntityBlock) {
                        ghostBePositions.add(pos.immutable());
                    } else {
                        skipped++;
                    }
                    continue;
                }
                poseStack.pushPose();
                poseStack.translate(
                    pos.getX() - camPos.x,
                    pos.getY() - camPos.y,
                    pos.getZ() - camPos.z);
                int color = COLOR_CACHE.computeIfAbsent(state, s ->
                    mc.getBlockColors().getColor(s, mc.level, pos, 0));
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
            }
        }
        // 先把 baked 幽灵方块 flush 出去 (单一 entityCutout(方块图集) render type), 深度正常.
        buffers.endBatch();

        // === BER pass: 箱子/床/告示牌等. bufferSource 在 render type 切换时会自动 flush
        //     上一个 batch, 所以这里不需要每个 BE 单独 endBatch; 循环结束后统一 flush 一次. ===
        for (BlockPos gpos : ghostBePositions) {
            BlockState gstate = worldBlocks.get(gpos);
            if (gstate == null) { skipped++; continue; }
            if (renderBlockEntityGhost(gstate, gpos, mc, buffers, poseStack, camPos)) {
                drawn++;
            } else {
                skipped++;
            }
        }
        // 统一 flush BER pass (替代原来逐 BE endBatch, 避免 draw call 风暴)
        buffers.endBatch();

        // === 双 highlight: hoveredBlock (黄色=删除) 跟 hoveredAir (橙色=放置) 同时画 ===
        // 关键修复 1: AABB 必须转成 "相机相对" 坐标 —— RenderLevelStageEvent 的 poseStack 原点在相机,
        //   上面画方块时每个都 translate(pos - camPos). 之前指示框直接用世界坐标 new AABB(hb),
        //   等于把框画到 (camPos + hb) 的位置 → 跑到视野外, 所以 "指示框显示不出来".
        // 关键修复 2: 画完必须 buffers.endBatch() flush, 否则 lines 顶点留在 buffer 里永远不出图.
        BlockPos hb = EditModeController.getHoveredBlockForRender();
        BlockPos ha = EditModeController.getHoveredAirForRender();
        if (hb != null || ha != null) {
            VertexConsumer lineVc = buffers.getBuffer(RenderType.lines());
            if (hb != null) {
                AABB aabb = new AABB(hb).inflate(0.02).move(-camPos.x, -camPos.y, -camPos.z);
                net.minecraft.client.renderer.LevelRenderer.renderLineBox(
                    poseStack, lineVc, aabb, 1.0F, 1.0F, 0.0F, 1.0F);  // 黄色 = delete
            }
            if (ha != null && !ha.equals(hb)) {
                AABB aabb = new AABB(ha).inflate(0.02).move(-camPos.x, -camPos.y, -camPos.z);
                net.minecraft.client.renderer.LevelRenderer.renderLineBox(
                    poseStack, lineVc, aabb, 1.0F, 0.5F, 0.0F, 1.0F);  // 橙色 = place
            }
            buffers.endBatch();
        }
        if ((mc.player.tickCount % 60) == 0) {
            PrefabCustomAddon.LOGGER.info("[EDIT-RENDER] drawn={} skipped={} total={} hoveredBlock={} hoveredAir={}",
                drawn, skipped, worldBlocks.size(), hb, ha);
        }
    }

    /**
     * 渲染 BlockEntity 类方块 (箱子/床/告示牌/旗帜/潜影盒/头颅/潮涌核心) 的幽灵预览.
     *
     * <p>这些方块 {@link RenderShape} 是 {@code INVISIBLE} — 没有 baked model, 只能靠各自的
     * {@link BlockEntityRenderer} 画. 之前 edit 预览直接 skip, 导致 "放置的箱子保存后才看得见".
     * 这里临时 new 一个 BlockEntity (pos + state), 塞进真实 level (箱子/床的 BER 内部会查 level),
     * 再用客户端已注册的 BER 渲染到当前 buffer, 视觉跟保存后一致.</p>
     *
     * <p>无 BER / newBlockEntity 返回 null / BER 抛异常 时返回 false, 调用方计入 skipped,
     * 绝不 crash (纯预览, 失败大不了这个方块不显示).</p>
     *
     * @return 成功画出返回 true
     */
    private static boolean renderBlockEntityGhost(BlockState state, BlockPos pos, Minecraft mc,
                                                  MultiBufferSource.BufferSource buffers,
                                                  PoseStack poseStack, Vec3 camPos) {
        try {
            if (!(state.getBlock() instanceof EntityBlock entityBlock)) return false;
            BlockEntity be = entityBlock.newBlockEntity(pos, state);
            if (be == null) { logGhostBeOnce("newBlockEntity 返回 null: " + state); return false; }
            be.setLevel(mc.level);  // 箱子/床等 BER 内部会查 level, 必须给 (否则 NPE)
            BlockEntityRenderer<BlockEntity> ber = mc.getBlockEntityRenderDispatcher().getRenderer(be);
            if (ber == null) { logGhostBeOnce("没有已注册的 BER: " + state); return false; }
            poseStack.pushPose();
            poseStack.translate(pos.getX() - camPos.x, pos.getY() - camPos.y, pos.getZ() - camPos.z);
            // partialTick 固定 1.0: 幽灵箱子每帧新建 BE、无 tick, 盖子不会动, 数值不影响外观.
            // 光照全亮 0xF000F0: 跟上面 baked 方块一致, 保证预览里始终看得清.
            ber.render(be, 1.0F, poseStack, buffers, 0xF000F0, OverlayTexture.NO_OVERLAY);
            poseStack.popPose();
            // 不在这里 endBatch — bufferSource 在 render type 切换时自动 flush 上一个 batch,
            // 循环结束后统一 flush 一次, 避免 N 个 BE = N 次 draw call 的风暴.
            return true;
        } catch (Throwable t) {
            logGhostBeOnce("BER 渲染抛异常: " + state + " → " + t);
            return false;
        }
    }

    /** 幽灵 BlockEntity 渲染失败只打一次日志 (避免每帧刷屏), 方便定位 "箱子还是没显示". */
    private static final AtomicBoolean ghostBeLogged = new AtomicBoolean(false);
    private static void logGhostBeOnce(String reason) {
        if (ghostBeLogged.compareAndSet(false, true)) {
            PrefabCustomAddon.LOGGER.warn("[EDIT-RENDER] 幽灵 BlockEntity 渲染失败: {}", reason);
        }
    }
}
