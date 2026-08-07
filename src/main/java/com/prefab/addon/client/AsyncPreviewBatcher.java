package com.prefab.addon.client;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.config.PlayerPreferences;
import com.prefab.structures.base.BuildBlock;
import com.prefab.structures.base.Structure;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 异步预览批处理: 用后台线程持续烘焙方块的 BakedQuads, 主线程渲染已就绪的.
 *
 * <h3>为什么后台线程?</h3>
 * 4w 块结构一次性在主线程调 {@code BakedModel.getQuads} 会卡 1+ 秒.
 * 拆到后台线程后, 主线程每帧只渲染已就绪的方块, 玩家感觉是"逐渐出现".
 *
 * <h3>线程安全策略</h3>
 * <ul>
 *   <li>{@link #pending} / {@link #ready}: {@link CopyOnWriteArrayList} (写少读多, 适合)</li>
 *   <li>每个 Structure 一个独立 session, 结构切换时 cancel 旧 session</li>
 *   <li>BakedModel.getQuads 大多数 mod 实现是只读, 安全;
 *       万一抛异常, 用 try-catch 兜底成空 quad 列表 (渲染成不可见, 不崩游戏)</li>
 * </ul>
 *
 * <h3>速度控制</h3>
 * 每秒最多处理 {@code (total * batchPercent/100)} 个方块.
 * 默认 10% → 40k 块结构约 10s 完成, 跟用户期望一致.
 * 玩家可调到 1% (慢但稳) ~ 100% (一次性).
 *
 * <h3>取消/重启</h3>
 * 玩家切换 structure / 移动预览 / 旋转时调 {@link #requestRestart(Structure)}.
 * 后台线程检测到 cancel 标志就退出当前任务, 等待新任务.
 */
public final class AsyncPreviewBatcher {

    /** 已烘焙好的方块 (主线程只读) */
    public static class ReadyBlock {
        public final BuildBlock buildBlock;
        public final List<net.minecraft.client.renderer.block.model.BakedQuad> quads;

        public ReadyBlock(BuildBlock bb, List<net.minecraft.client.renderer.block.model.BakedQuad> q) {
            this.buildBlock = bb;
            this.quads = q;
        }
    }

    /** 单个 structure 的批处理会话状态 */
    private static class Session {
        final Structure structure;
        final List<BuildBlock> pending;       // 待处理 (按索引顺序)
        volatile List<ReadyBlock> ready;      // 已就绪 (主线程读) — 改 rotationSteps 时替换
        final AtomicInteger processedCount;   // 已处理总数 (主线程读)
        volatile long nextBatchDeadlineMs;    // 下次批处理最早时间 (ms)
        volatile boolean cancelled;            // 取消标志
        volatile boolean completed;            // 是否全部完成
        volatile int rotationSteps;           // 90° 旋转步数 (0/1/2/3), 跟实际建造 (AsyncBuildManager) 一致

        Session(Structure s) {
            this.structure = s;
            this.pending = new CopyOnWriteArrayList<>(s.getBlocks() != null ? s.getBlocks() : Collections.emptyList());
            this.ready = new CopyOnWriteArrayList<>();
            this.processedCount = new AtomicInteger(0);
            this.nextBatchDeadlineMs = 0L;
            this.cancelled = false;
            this.completed = false;
            this.rotationSteps = 0;
        }
    }

    // 当前会话 (按 structure 引用索引)
    private static final Map<Structure, Session> SESSIONS = new IdentityHashMap<>();
    private static volatile Session currentSession = null;

    // 单例后台线程 (懒启动, 永生, 不 shutdown)
    private static volatile Thread workerThread = null;
    private static final Object START_LOCK = new Object();

    private AsyncPreviewBatcher() {}

    /**
     * 主线程调用: 切换到新结构时调用, 或确保当前结构的 session 已存在.
     * <p>
     * <b>关键: 只有"全新结构"才创建新 session. 同一个 structure 多次调用不会重建.</b><br>
     * 原因: 玩家移动 / 旋转预览时, KeyHandler 已经调 {@code offsetStructureBlocks}
     * 把每个 {@code BuildBlock.blockPos} 更新到新位置. 我们 renderer 读
     * {@code buildBlock.blockPos} 当前值, 跟着走. 烘焙的 quads 是基于 BlockState 的
     * 跟位置无关, 永久保留即可, 重新烘焙会导致大结构 (4w+ 块) 移动时**闪一下**
     * (整个建筑瞬间消失再重画) — 这是用户反馈的问题.
     * </p>
     * <p>
     * 旧实现: 每次移动/旋转都 requestRestart 重建 session, 重新读 structure.getBlocks()
     * (blockPos 已更新) 重新烘焙所有 quads → 大模型猫 (84w 顶点) 一次移动闪 1-2 秒.
     * </p>
     */
    public static void requestRestart(Structure newStructure) {
        if (newStructure == null) return;
        synchronized (SESSIONS) {
            // 1) 已存在这个 structure 的 session → 什么都不做.
            //    移动/旋转时 KeyHandler 改了 bb.blockPos, 渲染时直接读最新值.
            Session existing = SESSIONS.get(newStructure);
            if (existing != null) {
                currentSession = existing;
                return;
            }

            // 2) 全新结构 → 创建新 session, 一次性烘焙所有方块 (worker 异步).
            Session s = new Session(newStructure);
            SESSIONS.put(newStructure, s);
            currentSession = s;
            s.nextBatchDeadlineMs = System.currentTimeMillis();
            PrefabCustomAddon.LOGGER.info("[PREVIEW-ASYNC] 新会话启动 (新结构): totalBlocks={}", s.pending.size());
        }
    }

    /**
     * 主线程调用: 通知 session 当前的旋转步数. 如果变化, 重置 ready 列表, 让 worker 重新烘焙.
     *
     * <p>旋转步数变化 (玩家按 CTRL 旋转预览) 必须重新 bake, 因为 state 旋转了,
     *    quads 方向跟着变, 否则预览里装饰方块 (栅栏/楼梯/门/告示牌) 方向不对.</p>
     */
    public static void setRotationSteps(Structure structure, int steps) {
        if (structure == null) return;
        synchronized (SESSIONS) {
            Session s = SESSIONS.get(structure);
            if (s == null) return;
            int normalized = ((steps % 4) + 4) % 4;
            if (s.rotationSteps == normalized) return;
            PrefabCustomAddon.LOGGER.info("[PREVIEW-ASYNC] rotationSteps 变化: {} -> {}, 重置 ready",
                s.rotationSteps, normalized);
            s.rotationSteps = normalized;
            // 重置烘焙: 清空 ready 列表 + 重置 processedCount + 取消 completed 标记
            s.ready = new CopyOnWriteArrayList<>();
            s.processedCount.set(0);
            s.completed = false;
            s.nextBatchDeadlineMs = System.currentTimeMillis();
        }
    }

    /**
     * 主线程调用: 确保后台线程在跑.
     *
     * <p>禁用: 现在预览由 prefab 自带的 StructureRenderHandler 渲染, 不再需要后台
     * 线程做 BakedModel 烘焙. 保留方法签名以避免破坏其它调用方 (CustomStructureGui 之类),
     * 实际不做任何事.</p>
     */
    public static void ensureRunning(Structure structure) {
        // no-op: prefab 自己渲染
    }

    /**
     * 主线程调用: 取已就绪方块列表.
     *
     * <p><b>修复: 只有 completed=true 才返回数据, 否则返回空列表.</b><br>
     * 之前总是返回 ready list, 异步 worker 一边填, 主线程一边画 → 每帧画面里
     * 都多出几个新方块, 玩家视觉上感觉"一帧帧冒出来" (尤其大结构 4w+ 块
     * worker 跑几秒, 期间整张图在"渐入"), 这就是用户反馈的"预览方块一直闪".
     * 现在: 全部 ready 一齐放出来, 视觉上跟原版 Prefab 一致 (无闪烁).</p>
     */
    public static List<ReadyBlock> getReadyBlocks(Structure structure) {
        Session s;
        synchronized (SESSIONS) {
            s = SESSIONS.get(structure);
        }
        if (s == null || !s.completed) return Collections.emptyList();
        return s.ready;
    }

    /**
     * 主线程调用: 已处理方块数 (用于进度显示).
     */
    public static int getProcessedCount(Structure structure) {
        Session s;
        synchronized (SESSIONS) {
            s = SESSIONS.get(structure);
        }
        if (s == null) return 0;
        return s.processedCount.get();
    }

    /**
     * 后台线程主循环.
     */
    private static void runLoop() {
        PrefabCustomAddon.LOGGER.info("[PREVIEW-ASYNC] worker thread running");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Session s = currentSession;
                if (s == null) {
                    Thread.sleep(50);
                    continue;
                }
                if (s.cancelled || s.completed) {
                    Thread.sleep(50);
                    continue;
                }
                long now = System.currentTimeMillis();
                if (now < s.nextBatchDeadlineMs) {
                    // 还没到下个批次时间
                    long sleep = Math.min(50, s.nextBatchDeadlineMs - now);
                    Thread.sleep(Math.max(1, sleep));
                    continue;
                }

                // === 处理一批方块 ===
                int totalBlocks = s.pending.size();
                // **改动**: 永远一次性处理 100%, 不再受 PlayerPreferences.batchPercent 限制.
                // 之前 10%/tick 看起来"渐入", 但玩家在还没生成完时按方向键移动, 会出现
                //   1) 已经烘焙的方块跟着新 cfg.pos 走 (offsetStructureBlocks 改了 blockPos)
                //   2) 还在 pending 队列的方块要等下一个 tick 才会被处理, 这一帧不显示
                // → 视觉上"一部分动了, 一部分没动", 错位.
                // 现在一次性处理全部, 玩家就算在加载过程中移动, 整个建筑也是同步在 1 帧内
                // 重新渲染. 配合 CustomStructurePreviewRenderer 在 cfg.pos 变化时 requestRestart
                // (见 onRenderLevel 第 78 行检测), 移动/旋转 → 整个会话重启 → 全量重烘焙,
                // 1-2 tick 内完成 (异步线程, 不卡主线程), 视觉上无缝.
                int batchSize = totalBlocks;

                // 计算本批要处理的索引范围
                int startIdx = s.processedCount.get();
                int endIdx = Math.min(totalBlocks, startIdx + batchSize);

                BlockRenderDispatcher brd = Minecraft.getInstance().getBlockRenderer();
                Level level = Minecraft.getInstance().level;
                int baked = 0;
                int failed = 0;
                for (int i = startIdx; i < endIdx; i++) {
                    if (s.cancelled) break;
                    BuildBlock bb = s.pending.get(i);
                    if (bb == null) continue;
                    BlockState state = bb.getBlockState();
                    if (state == null || state.isAir()) continue;
                    // 关键: 跟原版 Prefab 预览 (StructureRenderHandler.drawStructure) 走同一套 SetBlockState.
                    //   BuildBlock.SetBlockState 内部对所有 property 类型按当前 houseFacing 重算:
                    //     - facing (HORIZONTAL_FACING)
                    //     - rotation (告示牌/头颅, 0/4/8/12 → S/W/N/E)
                    //     - axis (原木/骨头)
                    //     - 4 向连接 (墙/铁栅栏/玻璃板 CrossCollisionBlock)
                    //     - WallShape (墙的内/外/高)
                    //     - VineBlock 4 向布尔
                    //     - Lever 6 向 (FaceAttachedHorizontalDirectionalBlock)
                    //   之前用 BlockStateRotator.rotateY 只处理 HORIZONTAL_FACING, 栅栏/告示牌/楼梯方向
                    //   全部错乱 (装饰方块方向变了的根因).
                    if (s.structure.configuration != null && level != null) {
                        try {
                            BuildBlock rotatedBb = com.prefab.structures.base.BuildBlock.SetBlockState(
                                s.structure.configuration,
                                level,
                                bb.blockPos,
                                bb,
                                state.getBlock(),
                                state,
                                s.structure);
                            if (rotatedBb != null) {
                                state = rotatedBb.getBlockState();
                            }
                        } catch (Throwable t) {
                            // SetBlockState 内部对某些方块可能 NPE (例如方块没 default state), fallback 到原 state
                            PrefabCustomAddon.LOGGER.debug("[PREVIEW-ASYNC] SetBlockState 失败 ({}), 用原 state",
                                t.getMessage());
                        }
                    }
                    if (state == null || state.isAir()) continue;
                    try {
                        var model = brd.getBlockModel(state);
                        // 6 方向 quads 合并到一个 list
                        List<net.minecraft.client.renderer.block.model.BakedQuad> allQuads = new ArrayList<>();
                        for (Direction dir : Direction.values()) {
                            try {
                                var quads = model.getQuads(state, dir, RandomSource.create(42L));
                                if (quads != null) allQuads.addAll(quads);
                            } catch (Throwable t) {
                                // 单方向失败不影响其它方向
                            }
                        }
                        s.ready.add(new ReadyBlock(bb, allQuads));
                        baked++;
                    } catch (Throwable t) {
                        // 这个方块烘焙失败, 推一个空 quad 列表 (渲染时不可见)
                        s.ready.add(new ReadyBlock(bb, Collections.emptyList()));
                        failed++;
                    }
                }
                s.processedCount.set(endIdx);
                if (endIdx >= totalBlocks) {
                    s.completed = true;
                    PrefabCustomAddon.LOGGER.info("[PREVIEW-ASYNC] 完成: baked={} failed={} total={}",
                        baked, failed, totalBlocks);
                } else {
                    // 下批时间: 100ms 后 (10 批/秒), 总耗时 ≈ 10s (默认 10%)
                    s.nextBatchDeadlineMs = System.currentTimeMillis() + 100L;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                // 任何意外错误, 记日志后继续循环 (不退出线程)
                PrefabCustomAddon.LOGGER.error("[PREVIEW-ASYNC] worker exception", t);
                try { Thread.sleep(100); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        PrefabCustomAddon.LOGGER.info("[PREVIEW-ASYNC] worker thread exiting");
    }
}
