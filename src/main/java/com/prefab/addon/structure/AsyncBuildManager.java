package com.prefab.addon.structure;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.config.BuildAnimationMode;
import com.prefab.addon.config.PlayerPreferences;
import com.prefab.addon.items.CustomBlueprintItem;
import com.prefab.addon.integration.BlueprintSpecStore;
import com.prefab.addon.network.BatchBlocksPlacedPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 异步建筑管理器 - 大建筑分批放置, 每 tick 放 N% 块, 避免单 tick 卡死.
 *
 * <h3>为什么要分批?</h3>
 * 4w 块结构一次 setBlock 1w+ 次 → 单 tick 卡 1+ 秒, 玩家感觉"卡死了".
 * 拆成 10 批 (默认 10%/批, 100ms/tick) → 每 tick 只放 4k 块 ≈ 几十 ms,
 * 总时间 ≈ 1-10s, 期间玩家可以正常玩
 *
 * <h3>流程</h3>
 * <pre>
 *   placeStructureAsync(player, level, origin, pack, id)
 *     ↓
 *   把 (pack, id, origin, blocks 列表) 打包成 BuildTask
 *   注册到 ACTIVE_TASKS (按 player UUID 索引)
 *   立即给玩家发 "开始建造: 共 N 块" 消息
 *
 *   每个 server tick:
 *     从 ACTIVE_TASKS 拿所有 task
 *     每个 task 处理 buildBatchPercent% 个方块
 *     全部完成后:
 *       - 发 "完成, 放置 N 块" 消息
 *       - 从 ACTIVE_TASKS 移除
 * </pre>
 *
 * <h3>并发安全</h3>
 * 同一个玩家不能同时跑 2 个 task. 启动新 task 时若已有 task, 旧 task 直接标记废弃 (避免方块错位).
 * 不同玩家的 task 完全独立.
 */
@Mod.EventBusSubscriber(modid = PrefabCustomAddon.MOD_ID)
public final class AsyncBuildManager {

    /** 单个玩家的建造任务 */
    public static class BuildTask {
        public final UUID playerUuid;
        public final ServerPlayer player;
        public final Level level;
        public final BlockPos origin;
        public final String packName;
        public final String constructionId;
        public final List<CustomStructureBuilder.BlockData> blocks;
        public final int totalBlocks;
        public final long startTickMs;
        public final int rotationSteps;     // 90° 旋转步数 (0/1/2/3), 预览时 houseFacing 决定
        /**
         * 建造动画模式 (枚举, 2026-08 改: 之前是 boolean enableAnimation).
         *   - OFF  → 瞬建模式, 每 tick 全放完 (可能卡顿, 跟原版 prefab 一样)
         *   - FALL → 每 tick 1 块, 客户端用"竖直下落"轨迹渲染
         *   - RAIN → 每 tick 1 块, 客户端用"方块雨"轨迹渲染
         *   - THROW→ 每 tick 1 块, 客户端用"四周抛过来"轨迹渲染
         * 非 OFF 时强制 batchSize=1 (整除会丢精度, 直接写死 1 块/tick 最稳).
         */
        public final BuildAnimationMode animationMode;
        public int placedCount;          // 已放置数
        public int nextIndex;            // 下一个要放的方块索引
        public boolean completed;        // 是否全部完成
        public boolean cancelled;        // 玩家退出/重置

        public BuildTask(ServerPlayer p, Level l, BlockPos o,
                         String pack, String id,
                         List<CustomStructureBuilder.BlockData> blocks) {
            this(p, l, o, pack, id, blocks, 0, BuildAnimationMode.OFF);
        }

        public BuildTask(ServerPlayer p, Level l, BlockPos o,
                         String pack, String id,
                         List<CustomStructureBuilder.BlockData> blocks,
                         int rotationSteps) {
            this(p, l, o, pack, id, blocks, rotationSteps, BuildAnimationMode.OFF);
        }

        public BuildTask(ServerPlayer p, Level l, BlockPos o,
                         String pack, String id,
                         List<CustomStructureBuilder.BlockData> blocks,
                         int rotationSteps,
                         BuildAnimationMode animationMode) {
            this.playerUuid = p.getUUID();
            this.player = p;
            this.level = l;
            this.origin = o;
            this.packName = pack;
            this.constructionId = id;
            this.blocks = blocks;
            this.totalBlocks = blocks.size();
            this.startTickMs = System.currentTimeMillis();
            this.placedCount = 0;
            this.nextIndex = 0;
            this.completed = false;
            this.cancelled = false;
            this.rotationSteps = rotationSteps;
            this.animationMode = animationMode != null ? animationMode : BuildAnimationMode.OFF;
        }

        /** 旧 API 兼容: enableAnimation=true 等价于 FALL 模式. */
        public BuildTask(ServerPlayer p, Level l, BlockPos o,
                         String pack, String id,
                         List<CustomStructureBuilder.BlockData> blocks,
                         int rotationSteps,
                         boolean enableAnimation) {
            this(p, l, o, pack, id, blocks, rotationSteps,
                 enableAnimation ? BuildAnimationMode.FALL : BuildAnimationMode.OFF);
        }

        public int getPercent() {
            return totalBlocks > 0 ? (placedCount * 100 / totalBlocks) : 100;
        }

        public boolean isAnimationEnabled() {
            return animationMode != BuildAnimationMode.OFF;
        }
    }

    // 当前所有玩家的活跃 task (按 UUID 索引)
    private static final Map<UUID, BuildTask> ACTIVE_TASKS = new ConcurrentHashMap<>();

    private AsyncBuildManager() {}

    /**
     * 启动一个异步建造任务
     * 如果该玩家已有 task, 旧的会标记 cancelled (新 task 覆盖).
     */
    public static void startTask(ServerPlayer player, Level level, BlockPos origin,
                                 String packName, String constructionId,
                                 List<CustomStructureBuilder.BlockData> blocks) {
        startTask(player, level, origin, packName, constructionId, blocks, net.minecraft.core.Direction.SOUTH, BuildAnimationMode.OFF);
    }

    /**
     * 带旋转的 startTask 重载. houseFacing 是预览时的旋转方向
     * 服务端必须用同样的旋转放置方块, 否则实际建筑位置会跟预览错开.
     */
    public static void startTask(ServerPlayer player, Level level, BlockPos origin,
                                 String packName, String constructionId,
                                 List<CustomStructureBuilder.BlockData> blocks,
                                 net.minecraft.core.Direction houseFacing) {
        startTask(player, level, origin, packName, constructionId, blocks, houseFacing, BuildAnimationMode.OFF);
    }

    /**
     * 完整重载: 带 houseFacing + animationMode.
     * <p>animationMode != OFF 时: 强制 batchSize=1, 每 tick 放完一批后通过
     * {@link com.prefab.addon.network.BatchBlocksPlacedPayload} 把这一批方块 + mode
     * 发给该玩家, 客户端用 BuildAnimationRenderer 按 mode 渲染动画轨迹.</p>
     */
    public static void startTask(ServerPlayer player, Level level, BlockPos origin,
                                 String packName, String constructionId,
                                 List<CustomStructureBuilder.BlockData> blocks,
                                 net.minecraft.core.Direction houseFacing,
                                 BuildAnimationMode animationMode) {
        if (player == null || level == null || blocks == null || blocks.isEmpty()) {
            PrefabCustomAddon.LOGGER.warn("[BUILD-ASYNC] 启动失败: 参数无效 (player={} blocks={})",
                player != null, blocks != null ? blocks.size() : -1);
            return;
        }
        int steps = facingToRotationSteps(houseFacing);
        BuildTask existing = ACTIVE_TASKS.get(player.getUUID());
        if (existing != null && !existing.completed) {
            existing.cancelled = true;
            PrefabCustomAddon.LOGGER.info("[BUILD-ASYNC] 玩家 {} 有未完成任务, 标记为 cancelled", player.getName().getString());
        }

        BuildAnimationMode mode = animationMode != null ? animationMode : BuildAnimationMode.OFF;
        BuildTask task = new BuildTask(player, level, origin, packName, constructionId, blocks, steps, mode);
        ACTIVE_TASKS.put(player.getUUID(), task);

        // 动画模式下强制每 tick 1 块, 玩家设的 buildBatchPercent 被覆盖. 不修改 PlayerPreferences
        // (关掉动画开关后恢复玩家之前的设置, 不会"污染"玩家偏好).
        // effectivePercentPerTick 仅用于日志输出, 实际逻辑在 processTick 里硬编码 1 块/tick.
        String modeStr = switch (mode) {
            case OFF   -> "OFF (瞬建, 100%/tick)";
            case FALL  -> "FALL (竖直下落, 1 块/tick, ~50s/1000块)";
            case RAIN  -> "RAIN (方块雨, 1 块/tick, 起点随机偏移)";
            case THROW -> "THROW (四周抛过来, 1 块/tick, 抛物线轨迹)";
        };

        PrefabCustomAddon.LOGGER.info("[BUILD-ASYNC] 启动: player={} pack={} construction={} origin={} totalBlocks={} mode={} houseFacing={}({} steps)",
            player.getName().getString(), packName, constructionId, origin,
            task.totalBlocks, mode, houseFacing, steps);
    }

    /**
     * 旧 API 兼容重载: boolean enableAnimation → FALL/OFF 模式.
     * 保留给老调用方 (BuildCustomStructurePayload 等) 不用改代码.
     */
    public static void startTask(ServerPlayer player, Level level, BlockPos origin,
                                 String packName, String constructionId,
                                 List<CustomStructureBuilder.BlockData> blocks,
                                 net.minecraft.core.Direction houseFacing,
                                 boolean enableAnimation) {
        startTask(player, level, origin, packName, constructionId, blocks, houseFacing,
                  enableAnimation ? BuildAnimationMode.FALL : BuildAnimationMode.OFF);
    }

    /**
     * houseFacing -> 90° 旋转步数 (绕 Y 轴, CCW 俯视)
     * SOUTH=0, EAST=1, NORTH=2, WEST=3.
     * 必须跟客户端的 offsetStructureBlocks 保持一致, 否则预览和实际位置不匹配.
     */
    private static int facingToRotationSteps(net.minecraft.core.Direction facing) {
        if (facing == null) return 0;
        return switch (facing) {
            case SOUTH -> 0;
            case EAST  -> 1;
            case NORTH -> 2;
            case WEST  -> 3;
            default    -> 0;
        };
    }

    /**
     * Server tick 事件 - 每 tick 处理所有活跃 task 的一批
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (ACTIVE_TASKS.isEmpty()) return;
        for (BuildTask task : ACTIVE_TASKS.values()) {
            try {
                processTick(task);
            } catch (Throwable t) {
                PrefabCustomAddon.LOGGER.error("[BUILD-ASYNC] task 异常, 取消", t);
                task.cancelled = true;
            }
        }
        // 清理已完成/已取消的
        ACTIVE_TASKS.entrySet().removeIf(e -> {
            BuildTask t = e.getValue();
            if (t.completed) {
                onCompleted(t);
                return true;
            }
            if (t.cancelled) {
                PrefabCustomAddon.LOGGER.info("[BUILD-ASYNC] task cancelled, player={} placed={}/{}",
                    t.player.getName().getString(), t.placedCount, t.totalBlocks);
                return true;
            }
            // 玩家离线了
            if (t.player == null || !t.player.isAlive() || t.player.isRemoved()) {
                PrefabCustomAddon.LOGGER.info("[BUILD-ASYNC] 玩家离线, 取消 task");
                return true;
            }
            return false;
        });
    }

    private static void processTick(BuildTask task) {
        if (task.completed || task.cancelled) return;

        // 检查玩家是否还在 (可能切换世界等)
        if (task.player == null || task.player.isRemoved()) {
            task.cancelled = true;
            return;
        }
        ServerPlayer player = task.player;

        // 建造速度策略 (2026-08 更新: 移除了 UI 滑条, 改成简单的二选一):
        //   - 玩家开启 "建造下落动画" → 每 tick 固定放 1 块 (20 块/秒, 1000 块 ≈ 50s)
        //     方块从 y_target+8 慢慢落到 y_target, 玩家能清楚看到每个方块从空中落下的过程
        //   - 玩家关闭下落动画       → 100%/tick (单 tick 全放, 跟原版 prefab 一样快, 可能卡顿)
        // 之前 1%/tick 太快 (10 块/tick = 200 块/秒, 5s/1000 块, 玩家看不清单个方块下落),
        //   改成"每 tick 固定 1 块" (慢 10 倍) 后动画效果最明显.
        // 公式: 动画模式 batchSize=1, 瞬建模式 batchSize=totalBlocks (单 tick 全放).
        int batchSize;
        if (task.isAnimationEnabled()) {
            // 动画模式: 固定每 tick 放 1 块, 不再按 percentPerTick 算 (整除会丢精度)
            batchSize = 1;
        } else {
            // 瞬建模式: 100%/tick = 一次 setBlock 全部方块
            int percentPerTick = 1000;
            batchSize = Math.max(1, task.totalBlocks * percentPerTick / 100);
        }

        int endIdx = Math.min(task.totalBlocks, task.nextIndex + batchSize);

        // 关键: 异步建造必须用 UPDATE_MOVE_BY_PISTON | UPDATE_SUPPRESS_DROPS | UPDATE_CLIENTS
        //   = 64 | 32 | 2 = 98
        // - UPDATE_MOVE_BY_PISTON (64): 让 Block.onRemove 看到 isMoving=true,
        //   跳过容器 (桶子/陷阱/告示牌 等) 的 dropResources 的整段路径. 这是
        //   Sable / Create contraption / dungeon-train-mc 公认的"开源方案".
        //   之前只用 UPDATE_SUPPRESS_DROPS (flags=34) 对 modded 容器仍然会掉
        //   掉落物 (桶子/告示牌/植物等), 升到 98 才能彻底避免.
        // - UPDATE_SUPPRESS_DROPS (32): 同名保险.
        // - UPDATE_CLIENTS (2): 通知客户端更新方块.
        //   红石/活塞/红石粉等被放置时 onPlace 会检查周围方块, 分批阶段周围
        //   方块还是 air, 没 SUPPRESS 就会变成掉落物.
        // 不要用 UPDATE_NEIGHBORS (=1), 否则会立刻通知 6 个邻居触发 neighborChanged,
        //   红石组件在依赖的方块尚未放置时会被判断为"失去支撑"而立刻 break.
        // 全部放完后再在 onCompleted() 那里对整个 box 做一次全量红石重算.
        int flags = net.minecraft.world.level.block.Block.UPDATE_MOVE_BY_PISTON
                  | net.minecraft.world.level.block.Block.UPDATE_SUPPRESS_DROPS
                  | net.minecraft.world.level.block.Block.UPDATE_CLIENTS;

        // 动画模式下收集这一批放置的方块 (pos + state), 放完发 BatchBlocksPlacedPayload 给客户端做下落动画.
        // 预分配容量避免 ArrayList 扩容, 实际 1%/tick 时大部分 task 一批就几个, list 很小.
        List<BlockPos> animPositions = task.isAnimationEnabled() ? new ArrayList<>(batchSize) : null;
        List<BlockState> animStates = task.isAnimationEnabled() ? new ArrayList<>(batchSize) : null;

        // Critical: wrap the whole setBlock loop in SilentBuild.runSilent so that the
        // LevelMixin addFreshEntity interceptor rejects any ItemEntity spawned by
        // modded container onRemove / popResource fallback paths (signs/ladders/plants).
        // runSilentViaReflection uses Java reflection to call MyMod's SilentBuild.runSilent;
        // it falls back to direct execution (no silent mode) if MyMod is missing.
        com.prefab.addon.structure.SilentBuildShim.runSilent(() -> {
            for (int i = task.nextIndex; i < endIdx; i++) {
                CustomStructureBuilder.BlockData data = task.blocks.get(i);
                if (data == null) continue;
                try {
                    int lx = data.pos.getX();
                    int ly = data.pos.getY();
                    int lz = data.pos.getZ();
                    for (int s = 0; s < task.rotationSteps; s++) {
                        int nlx =  lz;
                        int nlz = -lx;
                        lx = nlx;
                        lz = nlz;
                    }
                    BlockPos target = task.origin.offset(lx, ly, lz);
                    net.minecraft.world.level.block.state.BlockState old = task.level.getBlockState(target);
                    if (old.hasBlockEntity() && old.getBlock() != data.state.getBlock()) {
                        task.level.removeBlockEntity(target);
                    }
                    // 关键修复: 旋转建筑时 (houseFacing != SOUTH) 方块状态也要跟着转
                    // 例如: 橡木楼梯的 facing 跟着 NORTH→WEST→SOUTH→EAST 转,
                    // 栅栏的 east/west/north/south 4 个连接属性跟着转,
                    // 门/按钮/漏斗/活塞/告示牌的 facing/rotation 也跟着转.
                    // 不转的话玩家旋转建筑后, 楼梯还是原来的方向, 栅栏还是连原来的方向.
                    BlockState rotatedState = BlockStateRotator.rotateY(data.state, task.rotationSteps);
                    task.level.setBlock(target, rotatedState, flags);
                    task.placedCount++;
                    if (animPositions != null) {
                        animPositions.add(target);
                        animStates.add(rotatedState);
                    }
                } catch (Throwable t) {
                    PrefabCustomAddon.LOGGER.warn("[BUILD-ASYNC] setBlock failed at {}: {}", data.pos, t.getMessage());
                }
            }
        });
        task.nextIndex = endIdx;

        // 动画模式: 放完这一批后, 把刚放的方块 + mode 发给客户端, 客户端用 BuildAnimationRenderer
        // 渲染它们从起点到目标位置的动画 (按 mode 走不同轨迹, maxTicks 8~14).
        if (task.isAnimationEnabled() && !animPositions.isEmpty()) {
            try {
                com.prefab.addon.network.NetworkHandler.sendToPlayer(
                    player,
                    new BatchBlocksPlacedPayload(animPositions, animStates, task.animationMode)
                );
                PrefabCustomAddon.LOGGER.debug("[BUILD-ANIM] sent BatchBlocksPlacedPayload: {} blocks, mode={}",
                    animPositions.size(), task.animationMode);
            } catch (Throwable t) {
                PrefabCustomAddon.LOGGER.warn("[BUILD-ANIM] sendToPlayer failed: {}", t.getMessage());
            }
        }

        // (建造进度不再刷聊天栏, 只保留日志)

        if (task.nextIndex >= task.totalBlocks) {
            task.completed = true;
        }
    }


    /**
     * 软依赖 MyMod 的 SilentBuild.runSilent(Runnable). 反射调用, 失败降级到直接跑.
     * <p>
     * 详细原理: 在 build + 红石重算期间, ItemEntity spawn 全部被 mixin 拒绝.
     * 这是根治"建造时/收回时变成掉落物"问题的最后兜底——即使前面
     * flags=98 + removeBlockEntity + barriers 替代法都没挡住所有路径, 只要最终
     * ItemEntity 想 spawn 出来, mixin 一定拦截.
     */
    private static final java.lang.reflect.Method SILENT_BUILD_RUN_SILENT = resolveSilentBuild();
    private static boolean silentBuildWarned = false;

    private static java.lang.reflect.Method resolveSilentBuild() {
        try {
            Class<?> cls = Class.forName("com.example.mymod.warehouse.cloud.SilentBuild");
            PrefabCustomAddon.LOGGER.info("[BUILD-ASYNC] resolveSilentBuild: class found = {}", cls.getName());
            java.lang.reflect.Method m = cls.getMethod("runSilent", Runnable.class);
            PrefabCustomAddon.LOGGER.info("[BUILD-ASYNC] resolveSilentBuild: method = {}", m);
            return m;
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[BUILD-ASYNC] resolveSilentBuild: FAILED to find SilentBuild class: {}",
                t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    private static void runSilentViaReflection(Runnable body) {
        if (SILENT_BUILD_RUN_SILENT != null) {
            try {
                PrefabCustomAddon.LOGGER.info("[BUILD-ASYNC] runSilent via reflection: invoking");
                SILENT_BUILD_RUN_SILENT.invoke(null, (Object) body);
                return;
            } catch (Throwable t) {
                if (!silentBuildWarned) {
                    PrefabCustomAddon.LOGGER.warn("[BUILD-ASYNC] SilentBuild reflection invoke failed: {}",
                        t.getClass().getSimpleName() + ": " + t.getMessage());
                    silentBuildWarned = true;
                }
            }
        }
        PrefabCustomAddon.LOGGER.warn("[BUILD-ASYNC] runSilent fallback (NO SILENT MODE!)");
        body.run();
    }

    private static void onCompleted(BuildTask task) {
        long elapsedMs = System.currentTimeMillis() - task.startTickMs;
        PrefabCustomAddon.LOGGER.info("[BUILD-ASYNC] 完成: player={} pack={} construction={} placed={}/{} elapsed={}ms",
            task.player.getName().getString(), task.packName, task.constructionId,
            task.placedCount, task.totalBlocks, elapsedMs);

        // 全部放完了, 对整栋建筑的包围盒做一次全量邻居更新 (Sable barriers 替代法
        // 已经包含了关键的"barrier 替 → 还原"清理, 这里只做最终通知.
        // 整个过程包在 MyMod SilentBuild.runSilent 里, mixin 拦截 ItemEntity spawn.
        // 软依赖 MyMod: 通过反射调 SilentBuild.runSilent, 失败降级到直接跑.
        PrefabCustomAddon.LOGGER.info("[BUILD-ASYNC] build done, no redstone-recompute needed");
    }

    /**
     * 对整栋建筑的包围盒做一次全量邻居更新, 让红石/活塞/红石粉等组件
     * 能正确感知到它们周围的最终状态 (避免异步分批放置导致邻居更新时
     * 依赖的方块还没放, 从而被破坏变成掉落物).
     *
     * <p><b>关键修复 (T5.5 掉落物根除)</b>: 原版这里直接调
     * {@code level.blockUpdated(p, ...)} + {@code level.updateNeighborsAt(p, ...)},
     * 1.21.1 的 {@code Level.updateNeighborsAt} 内部会让 6 个邻居调
     * {@code Block.onNeighborChange}, 进一步触发 modded 容器的
     * {@code onRemove → dropResources} 或作物/桶子/告示牌 等
     * 失去支撑走 {@code Level.destroyBlock(pos, dropBlock=true)}——
     * <b>这条路径完全不受我们之前 setBlock 的 flags=98 控制</b>,
     * 强制产生掉落物.
     *
     * <p>新方案: <b>先把建筑内所有方块替换为 barriers</b> (用 flags=98 +
     * removeBlockEntity), 触发级联; <b>再还原为原方块</b> (同样 flags=98 +
     * removeBlockEntity). 跟 Sable PR#365 "Replace old blocks with barriers
     * before removal" 思路一致, 但我们是把整个建筑先 "barrier 罩 → 再拆掉"
     * (此时所有方块都是 barrier, 不会触发 modded 容器掉落), 然后还原.
     * 红石/活塞/红石粉等最终姿态正确, 没有任何方块变成掉落物.
     *
     * <p>实现参考:
     * <ul>
     *   <li>Sable PR#365 (MIT): 用 barriers 替代旧方块再清除, 防止
     *       易碎方块 (火把/按钮) 在支撑方块被移除时 break 产生 duplication</li>
     *   <li>Sable PR#659 (MIT, final): "removeBlockEntity() + setBlock(AIR, 98)"
     *       —— 这两步对单个 setBlock 调用足够, 但对邻居级联产生的 destroyBlock
     *       仍然会掉. <b>必须</b>再加 barriers 替代把整个级联路径清空才能根治</li>
     * </ul>
     */
    /**
     * No-op. Replaced barriers-redo (it dropped items via updateNeighborsAt).
     * Now setBlock(flags=98) + removeBlockEntity already prevents onRemove drops,
     * redstone components auto-connect on onPlace. No global recompute needed.
     */
    private static void triggerRedstoneUpdate(BuildTask task) {
        if (task.blocks.isEmpty()) return;
        PrefabCustomAddon.LOGGER.info("[BUILD-ASYNC] triggerRedstoneUpdate (no-op)");
    }

    /**
     * 取消指定玩家的任务 (e.g. 玩家退出, 切世界).
     */
    public static void cancelPlayerTasks(UUID playerUuid) {
        BuildTask t = ACTIVE_TASKS.get(playerUuid);
        if (t != null) t.cancelled = true;
    }

    /**
     * 当前活跃 task 数 (调试用).
     */
    public static int activeCount() {
        return ACTIVE_TASKS.size();
    }

    // ============== KubeJS 蓝图兼容 ==============

    /**
     * 玩家蓝图 tag: KubeJS 在 startup_scripts 里 {@code add('kubejs:my_blueprint')} 加进这个 tag.
     * 服务端 + 客户端共用同一个 tag definition ({@code data/.../tags/items/player_blueprint.json}).
     */
    private static final TagKey<Item> PLAYER_BLUEPRINT_TAG = TagKey.create(
        Registries.ITEM,
        new ResourceLocation(PrefabCustomAddon.MOD_ID, "player_blueprint"));

    /**
     * 判断 ItemStack 是否是"KubeJS 联动蓝图" (排除 mod 原生 CustomBlueprintItem).
     * <p>判断逻辑: 带 {@code prefab_custom_addon:player_blueprint} tag <b>且</b> 不是
     * {@link CustomBlueprintItem} 实例. KubeJS 在 startup_scripts 里
     * {@code add('kubejs:my_blueprint')} 加进这个 tag, 这些蓝图建造时应免材料
     * (KubeJS 蓝图通常没有服务端材料账本记录).</p>
     */
    public static boolean isKubeJSPlayerBlueprint(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.getItem() instanceof CustomBlueprintItem) return false;
        return stack.is(PLAYER_BLUEPRINT_TAG);
    }

    /**
     * 解析 KubeJS 联动蓝图的绑定 (packName / constructionId / locked).
     *
     * <p>以前生成的 startup script 用 KubeJS 1.21 的 {@code setCustomData} 烤默认 NBT,
     * 1.20.1 KubeJS (2001.x) 没这个 API, 新脚本不再写 NBT, 默认绑定数据在规格文件里.
     * 解析优先级: 物品栈 NBT (玩家绑定过的) 优先 → NBT 为空时按物品 id 查
     * {@code prefab-extension/.blueprint-specs/<itemId>.json} → 都没有返回 null (未绑定).</p>
     *
     * <p>common 代码: 服务端建造链路和客户端 handler / tooltip 都走这里.</p>
     */
    public static BlueprintSpecStore.Binding resolveKubeJSBlueprintBinding(ItemStack stack) {
        return BlueprintSpecStore.resolveBinding(stack);
    }
}