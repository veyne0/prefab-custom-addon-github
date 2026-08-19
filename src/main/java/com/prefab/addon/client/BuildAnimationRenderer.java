package com.prefab.addon.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.config.BuildAnimationMode;
import com.prefab.addon.config.PlayerPreferences;
import com.prefab.addon.network.BatchBlocksPlacedPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * 客户端"建造动画"渲染器 - 3 种 mode 选 1.
 *
 * <h2>工作流程</h2>
 * <ol>
 *   <li>服务端 {@link com.prefab.addon.structure.AsyncBuildManager} 每 tick 放完一批方块后,
 *       通过 {@link BatchBlocksPlacedPayload} 把 (pos, state, mode) 列表发给该玩家.</li>
 *   <li>本类 onBatchBlocksPlaced() 收到包后, 对每个方块创建一个 {@link FallingEntry},
 *       起始位置 / 轨迹时长 按 mode 决定.</li>
 *   <li>每 client tick: 推进 currentTick, 到 maxTicks 时移除 (真实方块已 visible).</li>
 *   <li>每帧 RenderLevelStageEvent.AFTER_BLOCK_ENTITIES: 渲染所有还在动画的 FallingEntry 的 ghost 方块,
 *       位置 = mode 决定的插值 (realX, currentRenderY, realZ), 用 entityCutout (不闪烁).</li>
 * </ol>
 *
 * <h2>3 种 mode 轨迹</h2>
 * <table>
 *   <tr><th>Mode</th><th>起点</th><th>终点</th><th>maxTicks</th><th>轨迹</th></tr>
 *   <tr><td>FALL</td><td>(realX, realY+8, realZ)</td><td>(realX, realY, realZ)</td><td>8</td><td>线性下降</td></tr>
 *   <tr><td>RAIN</td><td>(realX+dx, realY+yOff, realZ+dz) yOff∈[6,18], dx/dz∈[-2,2]</td><td>(realX, realY, realZ)</td><td>yOff</td><td>线性下降 + 横向偏移, 每个方块独立起始高度</td></tr>
 *   <tr><td>THROW</td><td>(realX+dirX*10, realY+8, realZ+dirZ*10) dirX/dirZ∈{-1,0,1}</td><td>(realX, realY, realZ)</td><td>14</td><td>抛物线 (x/z 线性, y = lerp + sin(πt)*6)</td></tr>
 * </table>
 *
 * <h2>为什么不用 mixin 拦截方块渲染</h2>
 * 服务端 setBlock 仍然带 UPDATE_CLIENTS, 客户端 chunk 已经知道方块存在, 但我们在
 * "动画阶段" 在 startPos→target 路径上渲染一个 ghost (遮住/叠加真方块), 玩家视觉上看到动画.
 * 动画结束后从列表移除, ghost 不再渲染, 真方块直接显示 — 玩家视觉上"落定".
 */
@EventBusSubscriber(modid = PrefabCustomAddon.MOD_ID, value = Dist.CLIENT)
public class BuildAnimationRenderer {

    // 3 种模式各自的动画总时长 (tick). 跟 startPos 的高度匹配 (8/12/8) — 1 tick 走 1 格 y, 看起来自然.
    private static final int MAX_TICKS_FALL  = 8;   // 8 格高, 8 tick = 0.4s
    private static final int MAX_TICKS_RAIN  = 12;  // RAIN 默认值; 实际每个方块的 maxTicks 在构造时按起始高度覆盖
    private static final int MAX_TICKS_THROW = 14;  // 14 tick = 0.7s (抛物线起步慢, 看起来更"有重量")
    // RAIN 横向随机偏移范围
    private static final float RAIN_SCATTER = 2.0f;
    // RAIN 起始 Y 高度范围 (相对 realY). 6~18, 每个方块按位置 hash 抽一个, 看起来像"高低不同的雨滴"
    private static final int RAIN_Y_MIN = 6;
    private static final int RAIN_Y_MAX = 18;
    // THROW 起点在建筑外圈的距离 + 抛物线弧高
    private static final float THROW_DISTANCE = 10.0f;
    private static final float THROW_ARC_HEIGHT = 6.0f;

    /**
     * 一个正在"动"的方块条目.
     * realX/Y/Z: 真实方块的位置 (不变, 跟服务端 setBlock 后的方块位置一致).
     * startX/Y/Z: 动画起点 (mode 决定, FALL = realY+8, RAIN = realY+随机高度+横向, THROW = 外圈+realY+8).
     * currentTick: 当前进度 (0..maxTicks-1), 0 时渲染起点, maxTicks-1 时渲染接近终点, 到 maxTicks 移除.
     * maxTicks: 该方块总动画时长 (FALL=8, THROW=14, RAIN=按起始高度[6..18]).
     * mode: 用哪种轨迹计算 currentRenderX/Y/Z.
     */
    private static class FallingEntry {
        final BlockPos realPos;
        final BlockState state;
        final BuildAnimationMode mode;
        final double startX, startY, startZ;
        int maxTicks;  // RAIN 模式会在 case 块里覆盖成"按起始高度"
        int currentTick;

        FallingEntry(BlockPos realPos, BlockState state, BuildAnimationMode mode) {
            this.realPos = realPos;
            this.state = state;
            this.mode = mode;
            this.currentTick = 0;
            this.maxTicks = maxTicksFor(mode);
            // 起点根据 mode 计算. 终点的 X/Z 在 render 时按 progress 插值, 这里只存"从哪里开始".
            double rx = realPos.getX();
            double ry = realPos.getY();
            double rz = realPos.getZ();
            switch (mode) {
                case FALL -> {
                    this.startX = rx;
                    this.startY = ry + PlayerPreferences.get().getBuildAnimHeight();
                    this.startZ = rz;
                }
                case RAIN -> {
                    // 横向散开: dx/dz ∈ [-RAIN_SCATTER, RAIN_SCATTER]
                    // 起始 Y 高度也按位置 hash 散开: ry + [RAIN_Y_MIN, RAIN_Y_MAX]
                    // 每个方块自己的 maxTicks = 起始高度 (保持 1 tick/格 的统一下落速度),
                    // 看起来就是"从不同高度同时开始落下的雨", 而不是一整块一起下降.
                    // 用 realPos 的 hash 当种子, 同一位置每次重新加入动画时偏移一致 (不会随机抖).
                    long seed = (long) realPos.asLong() * 2654435761L;
                    RandomSource r = RandomSource.create(seed);
                    this.startX = rx + (r.nextDouble() * 2.0 - 1.0) * RAIN_SCATTER;
                    int heightRange = RAIN_Y_MAX - RAIN_Y_MIN + 1;
                    int yOffset = RAIN_Y_MIN + r.nextInt(heightRange);
                    this.startY = ry + yOffset;
                    this.maxTicks = yOffset;  // 高度 = tick 数, 速度保持 1 格/tick
                    this.startZ = rz + (r.nextDouble() * 2.0 - 1.0) * RAIN_SCATTER;
                }
                case THROW -> {
                    // 起点在"外圈": 选一个随机方向 (-1/0/+1 in x, -1/0/+1 in z), 离 realPos 距离 THROW_DISTANCE
                    // 4 个主方向 (东南西北) 加 4 个对角线共 8 个, 看起来"从四周抛过来"
                    long seed = (long) realPos.asLong() * 40503L;  // 跟 RAIN 不同的种子, 不会重合
                    RandomSource r = RandomSource.create(seed);
                    int idx = r.nextInt(8);  // 0..7 = 8 个方向
                    double dirX = switch (idx % 3) { case 0 -> -1; case 1 -> 0; default -> 1; };
                    double dirZ = switch (idx / 3) { case 0 -> -1; case 1 -> 0; default -> 1; };
                    this.startX = rx + dirX * THROW_DISTANCE;
                    this.startY = ry + 8.0;
                    this.startZ = rz + dirZ * THROW_DISTANCE;
                }
                default -> {
                    // OFF 不应该到这里, 兜底走 FALL 行为
                    this.startX = rx;
                    this.startY = ry + 8.0;
                    this.startZ = rz;
                }
            }
        }

        private static int maxTicksFor(BuildAnimationMode mode) {
            return switch (mode) {
                case FALL  -> MAX_TICKS_FALL;
                case RAIN  -> MAX_TICKS_RAIN;
                case THROW -> MAX_TICKS_THROW;
                default    -> MAX_TICKS_FALL;
            };
        }

        /**
         * 计算当前 tick 时的渲染 X/Y/Z.
         * <ul>
         *   <li>FALL: 起点垂直 → 终点. progress = currentTick / (maxTicks-1).</li>
         *   <li>RAIN: 跟 FALL 一样线性, 但 startX/startZ 已经在构造时随机偏移了.</li>
         *   <li>THROW: x/z 线性插值, y = lerp(startY, endY, progress) + sin(π·progress)·THROW_ARC_HEIGHT
         *       (抛物线在 t=0.5 时达到顶峰, 看起来"被抛到空中再落下").</li>
         * </ul>
         */
        void computeCurrentPos(double[] out) {
            double endX = realPos.getX();
            double endY = realPos.getY();
            double endZ = realPos.getZ();
            double t;
            if (maxTicks <= 1) t = 1.0;
            else t = Math.min(1.0, (double) currentTick / (double) (maxTicks - 1));
            // 线性插值 x/z (FALL 时 startX==endX, 等价于"只动 y")
            double cx = startX + (endX - startX) * t;
            double cz = startZ + (endZ - startZ) * t;
            double cy;
            if (mode == BuildAnimationMode.THROW) {
                // 抛物线: y 在 t=0.5 时达到顶峰, 比 lerp 多一个 sin 弧
                double linearY = startY + (endY - startY) * t;
                double arc = Math.sin(t * Math.PI) * THROW_ARC_HEIGHT;
                cy = linearY + arc;
            } else {
                // FALL / RAIN: 纯线性
                cy = startY + (endY - startY) * t;
            }
            out[0] = cx;
            out[1] = cy;
            out[2] = cz;
        }
    }

    // 全部正在动的方块. 单线程访问 (RenderLevel + ClientTick 都在主线程).
    private static final List<FallingEntry> FALLING = new ArrayList<>();

    // 随机数, 给 model.getQuads 用
    private static final RandomSource RNG = RandomSource.create(42L);

    // 防止服务端发了 1 个包客户端瞬间处理导致 FALLING 短时间塞 1w 个方块
    private static final int MAX_FALLING = 4096;
    private static final Random STATS_RAND = new Random();

    private BuildAnimationRenderer() {}

    /**
     * 服务端 → 客户端 包处理入口 (在 NetworkHandler 里注册).
     * 对每个方块创建一个 FallingEntry, 起点 / 时长按 mode 决定.
     */
    public static void onBatchBlocksPlaced(BatchBlocksPlacedPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            PrefabCustomAddon.LOGGER.debug("[BUILD-ANIM] onBatchBlocksPlaced called but no world/player, skip");
            return;
        }

        synchronized (FALLING) {
            // 防御: 超过上限就丢弃最老的 (避免内存爆). 实际上服务端每批只发少量方块, 不会到这里.
            int incoming = payload.positions().size();
            if (FALLING.size() + incoming > MAX_FALLING) {
                int overflow = FALLING.size() + incoming - MAX_FALLING;
                PrefabCustomAddon.LOGGER.warn("[BUILD-ANIM] FALLING 超过上限 ({}), 丢弃最老的 {} 个",
                    MAX_FALLING, overflow);
                FALLING.subList(0, Math.min(overflow, FALLING.size())).clear();
            }
            int n = Math.min(payload.positions().size(), payload.states().size());
            BuildAnimationMode payloadMode = payload.mode() != null ? payload.mode() : BuildAnimationMode.OFF;
            // 客户端兜底: 如果 payload 是 OFF (版本不匹配 / 极端 case), 用玩家当前偏好代替
            if (payloadMode == BuildAnimationMode.OFF) payloadMode = PlayerPreferences.get().getBuildAnimationMode();
            for (int i = 0; i < n; i++) {
                BlockPos pos = payload.positions().get(i);
                BlockState state = payload.states().get(i);
                if (pos == null || state == null || state.isAir()) continue;
                FALLING.add(new FallingEntry(pos.immutable(), state, payloadMode));
            }
        }
        if (STATS_RAND.nextInt(20) == 0) {
            PrefabCustomAddon.LOGGER.info("[BUILD-ANIM] onBatchBlocksPlaced: +{} falling, total={}, mode={}",
                payload.positions().size(), FALLING.size(), payload.mode());
        }
    }

    /**
     * 主线程 client tick: 推进动画进度. 1 tick +1, 满了移除.
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (FALLING.isEmpty()) return;
        synchronized (FALLING) {
            Iterator<FallingEntry> it = FALLING.iterator();
            while (it.hasNext()) {
                FallingEntry e = it.next();
                e.currentTick++;
                if (e.currentTick >= e.maxTicks) {
                    it.remove();
                }
            }
        }
    }

    /**
     * 主线程 level render: 渲染所有还在动画的 ghost 方块.
     */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
        if (FALLING.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        // 玩家在 GUI 里时不渲染 (不常见, 但防止 leak)
        if (mc.screen != null) return;

        // 复制快照避免 ConcurrentModificationException (虽然 onClientTick 也加锁, 防御性)
        List<FallingEntry> snapshot;
        synchronized (FALLING) {
            if (FALLING.isEmpty()) return;
            snapshot = new ArrayList<>(FALLING);
        }

        try {
            renderFalling(mc, event, snapshot);
        } catch (Throwable t) {
            // 渲染失败不崩游戏, 静默 + 日志
            PrefabCustomAddon.LOGGER.warn("[BUILD-ANIM] renderFalling failed: {}", t.getMessage());
        }
    }

    private static void renderFalling(Minecraft mc, RenderLevelStageEvent event, List<FallingEntry> snapshot) {
        BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        PoseStack poseStack = event.getPoseStack();
        Vec3 camPos = event.getCamera().getPosition();

        // 跟 CustomStructurePreviewRenderer 一致: entityCutout + alpha-test, 不闪烁.
        VertexConsumer buf = buffers.getBuffer(RenderType.entityCutout(
            net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS));

        double[] pos = new double[3];
        int drawn = 0;
        for (FallingEntry e : snapshot) {
            BlockState state = e.state;
            if (state == null || state.isAir()) continue;
            var model = blockRenderer.getBlockModel(state);
            // 6 方向 quads
            List<BakedQuad> allQuads = new ArrayList<>();
            try {
                allQuads.addAll(model.getQuads(state, null, RNG));
            } catch (Throwable ignored) {}
            for (Direction dir : Direction.values()) {
                try {
                    allQuads.addAll(model.getQuads(state, dir, RNG));
                } catch (Throwable ignored) {}
            }
            if (allQuads.isEmpty()) continue;

            // 算当前 tick 时的渲染位置 (按 mode 不同轨迹)
            e.computeCurrentPos(pos);

            poseStack.pushPose();
            // 渲染位置: (posX, posY, posZ) - cameraPos
            poseStack.translate(
                pos[0] - camPos.x,
                pos[1] - camPos.y,
                pos[2] - camPos.z);

            int color = mc.getBlockColors().getColor(state, mc.level, e.realPos, 0);
            float r = (float) (color >> 16 & 255) / 255.0F;
            float g = (float) (color >> 8 & 255) / 255.0F;
            float b = (float) (color & 255) / 255.0F;

            for (BakedQuad quad : allQuads) {
                buf.putBulkData(poseStack.last(), quad, r, g, b, 1.0F,
                    0xF000F0, OverlayTexture.NO_OVERLAY, false);
            }
            poseStack.popPose();
            drawn++;
        }
        buffers.endBatch();

        if ((mc.player.tickCount % 60) == 0 && drawn > 0) {
            PrefabCustomAddon.LOGGER.debug("[BUILD-ANIM] rendered {} animating blocks ({} total tracked)",
                drawn, snapshot.size());
        }
    }

    /** 调试用: 当前正在动的方块数. */
    public static int fallingCount() {
        return FALLING.size();
    }

    /** 强制清空所有下落方块 (玩家切世界/重置时调用). */
    public static void clearAll() {
        synchronized (FALLING) {
            FALLING.clear();
        }
    }
}
