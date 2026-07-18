package com.prefab.addon.client;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.config.PlayerPreferences;
import com.prefab.structures.base.BuildBlock;
import com.prefab.structures.base.Structure;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
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
        final List<ReadyBlock> ready;         // 已就绪 (主线程读)
        final AtomicInteger processedCount;   // 已处理总数 (主线程读)
        volatile long nextBatchDeadlineMs;    // 下次批处理最早时间 (ms)
        volatile boolean cancelled;            // 取消标志
        volatile boolean completed;            // 是否全部完成

        Session(Structure s) {
            this.structure = s;
            this.pending = new CopyOnWriteArrayList<>(s.getBlocks() != null ? s.getBlocks() : Collections.emptyList());
            this.ready = new CopyOnWriteArrayList<>();
            this.processedCount = new AtomicInteger(0);
            this.nextBatchDeadlineMs = 0L;
            this.cancelled = false;
            this.completed = false;
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
     * 主线程调用: 切换到新结构时取消旧会话, 启动新会话.
     */
    public static void requestRestart(Structure newStructure) {
        if (newStructure == null) return;
        synchronized (SESSIONS) {
            if (currentSession != null && currentSession.structure != newStructure) {
                currentSession.cancelled = true;
                PrefabCustomAddon.LOGGER.info("[PREVIEW-ASYNC] 取消旧会话 (切换 structure)");
            }
            Session existing = SESSIONS.get(newStructure);
            if (existing == null) {
                Session s = new Session(newStructure);
                SESSIONS.put(newStructure, s);
                currentSession = s;
                s.nextBatchDeadlineMs = System.currentTimeMillis();
                PrefabCustomAddon.LOGGER.info("[PREVIEW-ASYNC] 新会话启动: totalBlocks={}",
                    s.pending.size());
            } else {
                currentSession = existing;
            }
        }
    }

    /**
     * 主线程调用: 确保后台线程在跑.
     */
    public static void ensureRunning(Structure structure) {
        if (workerThread != null && workerThread.isAlive()) return;
        synchronized (START_LOCK) {
            if (workerThread != null && workerThread.isAlive()) return;
            workerThread = new Thread(AsyncPreviewBatcher::runLoop, "PrefabAsyncPreview");
            workerThread.setDaemon(true);
            workerThread.start();
            PrefabCustomAddon.LOGGER.info("[PREVIEW-ASYNC] 后台线程启动");
        }
    }

    /**
     * 主线程调用: 取已就绪方块列表.
     */
    public static List<ReadyBlock> getReadyBlocks(Structure structure) {
        Session s;
        synchronized (SESSIONS) {
            s = SESSIONS.get(structure);
        }
        if (s == null) return Collections.emptyList();
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
                int baked = 0;
                int failed = 0;
                for (int i = startIdx; i < endIdx; i++) {
                    if (s.cancelled) break;
                    BuildBlock bb = s.pending.get(i);
                    if (bb == null) continue;
                    BlockState state = bb.getBlockState();
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
