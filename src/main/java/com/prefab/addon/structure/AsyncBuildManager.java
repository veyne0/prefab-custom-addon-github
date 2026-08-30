package com.prefab.addon.structure;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.config.BuildAnimationMode;
import com.prefab.addon.config.PlayerPreferences;
import com.prefab.addon.items.CustomBlueprintItem;
import com.prefab.addon.network.BatchBlocksPlacedPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;

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
 *       - 消耗蓝图
 *       - 从 ACTIVE_TASKS 移除
 * </pre>
 *
 * <h3>并发安全</h3>
 * 同一个玩家不能同时跑 2 个 task. 启动新 task 时若已有 task, 旧 task 直接标记废弃 (避免方块错位).
 * 不同玩家的 task 完全独立.
 */
@EventBusSubscriber(modid = PrefabCustomAddon.MOD_ID)
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
        public boolean blueprintConsumed; // 蓝图是否已消耗 (完成时消耗)
        /**
         * 静默模式: KubeJS 联动蓝图建造时 = true.
         *   - 不发 "开始建造" / "建造完成" / "已存入云端" 等聊天栏消息
         *   - 完成后不存云端 (云端 tab 只放 CustomBlueprintItem 出的建筑)
         * 普通 mod 原生 CustomBlueprintItem 走的还是带消息 + 存云端的老路径.
         */
        public final boolean silent;

        public BuildTask(ServerPlayer p, Level l, BlockPos o,
                         String pack, String id,
                         List<CustomStructureBuilder.BlockData> blocks) {
            this(p, l, o, pack, id, blocks, 0, BuildAnimationMode.OFF, false);
        }

        public BuildTask(ServerPlayer p, Level l, BlockPos o,
                         String pack, String id,
                         List<CustomStructureBuilder.BlockData> blocks,
                         int rotationSteps) {
            this(p, l, o, pack, id, blocks, rotationSteps, BuildAnimationMode.OFF, false);
        }

        public BuildTask(ServerPlayer p, Level l, BlockPos o,
                         String pack, String id,
                         List<CustomStructureBuilder.BlockData> blocks,
                         int rotationSteps,
                         BuildAnimationMode animationMode) {
            this(p, l, o, pack, id, blocks, rotationSteps, animationMode, false);
        }

        public BuildTask(ServerPlayer p, Level l, BlockPos o,
                         String pack, String id,
                         List<CustomStructureBuilder.BlockData> blocks,
                         int rotationSteps,
                         BuildAnimationMode animationMode,
                         boolean silent) {
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
            this.blueprintConsumed = false;
            this.rotationSteps = rotationSteps;
            this.animationMode = animationMode != null ? animationMode : BuildAnimationMode.OFF;
            this.silent = silent;
        }

        /** 旧 API 兼容: enableAnimation=true 等价于 FALL 模式. */
        public BuildTask(ServerPlayer p, Level l, BlockPos o,
                         String pack, String id,
                         List<CustomStructureBuilder.BlockData> blocks,
                         int rotationSteps,
                         boolean enableAnimation) {
            this(p, l, o, pack, id, blocks, rotationSteps,
                 enableAnimation ? BuildAnimationMode.FALL : BuildAnimationMode.OFF, false);
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
        startTask(player, level, origin, packName, constructionId, blocks, houseFacing, BuildAnimationMode.OFF, false);
    }

    /**
     * 完整重载: 带 houseFacing + animationMode.
     * <p>animationMode != OFF 时: 强制 batchSize=1, 每 tick 放完一批后通过
     * {@link com.prefab.addon.network.BatchBlocksPlacedPayload} 把这一批方块 + mode
     * 发给该玩家, 客户端用 BuildAnimationRenderer 按 mode 渲染动画轨迹.</p>
     *
     * <p>{@code silent} = true 时: KubeJS 联动蓝图建造, 不发任何聊天栏消息,
     * 完成后不存云端. 默认为 false (普通 CustomBlueprintItem 走的还是带消息的老路径).</p>
     */
    public static void startTask(ServerPlayer player, Level level, BlockPos origin,
                                 String packName, String constructionId,
                                 List<CustomStructureBuilder.BlockData> blocks,
                                 net.minecraft.core.Direction houseFacing,
                                 BuildAnimationMode animationMode) {
        startTask(player, level, origin, packName, constructionId, blocks, houseFacing, animationMode, false);
    }

    /**
     * 完整重载: houseFacing + animationMode + silent.
     */
    public static void startTask(ServerPlayer player, Level level, BlockPos origin,
                                 String packName, String constructionId,
                                 List<CustomStructureBuilder.BlockData> blocks,
                                 net.minecraft.core.Direction houseFacing,
                                 BuildAnimationMode animationMode,
                                 boolean silent) {
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
        BuildTask task = new BuildTask(player, level, origin, packName, constructionId, blocks, steps, mode, silent);
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

        PrefabCustomAddon.LOGGER.info("[BUILD-ASYNC] 启动: player={} pack={} construction={} origin={} totalBlocks={} mode={} houseFacing={}({} steps) silent={}",
            player.getName().getString(), packName, constructionId, origin,
            task.totalBlocks, mode, houseFacing, steps, silent);
        // KubeJS 联动蓝图走 silent 模式, 不发 "开始建造" 聊天消息 (玩家视角是右键就放完了, 不需要被提醒)
        if (player != null && !silent) {
            String speedDesc = mode == BuildAnimationMode.OFF
                ? "100%/tick (单 tick 全放, 瞬建, 可能卡顿)"
                : "1 块/tick (§d" + mode.name() + " 动画§e, 配合" + switch (mode) {
                    case FALL  -> "竖直下落";
                    case RAIN  -> "方块雨";
                    case THROW -> "四周抛过来";
                    default    -> "?";
                } + "动画, ~50s/1000块)";
            String msg = "§e开始建造: " + constructionId + ": " + task.totalBlocks + " 块 (异步, " + speedDesc + ")";
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(msg));
        }
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
    public static void onServerTick(ServerTickEvent.Post event) {
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
                PacketDistributor.sendToPlayer(
                    player,
                    new BatchBlocksPlacedPayload(animPositions, animStates, task.animationMode)
                );
                PrefabCustomAddon.LOGGER.debug("[BUILD-ANIM] sent BatchBlocksPlacedPayload: {} blocks, mode={}",
                    animPositions.size(), task.animationMode);
            } catch (Throwable t) {
                PrefabCustomAddon.LOGGER.warn("[BUILD-ANIM] sendToPlayer failed: {}", t.getMessage());
            }
        }

        // 进度反馈 (每 10% 给玩家发一次消息). KubeJS 联动蓝图 silent 模式跳过.
        if (task.silent) {
            // do nothing
        } else {
            int currentPct = task.getPercent();
            int lastReportedPct = (task.placedCount - batchSize <= 0) ? 0 :
                                  ((task.placedCount - batchSize) * 100 / task.totalBlocks);
            if (currentPct / 10 > lastReportedPct / 10 && currentPct < 100) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§7建造中: " + currentPct + "% (" + task.placedCount + "/" + task.totalBlocks + ")"));
            }
        }

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
        PrefabCustomAddon.LOGGER.info("[BUILD-ASYNC] 完成: player={} pack={} construction={} placed={}/{} elapsed={}ms silent={}",
            task.player.getName().getString(), task.packName, task.constructionId,
            task.placedCount, task.totalBlocks, elapsedMs, task.silent);

        // 全部放完了, 对整栋建筑的包围盒做一次全量邻居更新 (Sable barriers 替代法
        // 已经包含了关键的"barrier 替 → 还原"清理, 这里只做最终通知.
        // 整个过程包在 MyMod SilentBuild.runSilent 里, mixin 拦截 ItemEntity spawn.
        // 软依赖 MyMod: 通过反射调 SilentBuild.runSilent, 失败降级到直接跑.
        PrefabCustomAddon.LOGGER.info("[BUILD-ASYNC] build done, no redstone-recompute needed");


        // 消耗蓝图 (成功后才消耗, 跟旧逻辑一致)
        if (!task.blueprintConsumed) {
            consumeBlueprint(task);
            task.blueprintConsumed = true;
        }

        // KubeJS 联动蓝图走 silent 模式, 不发 "建造完成" 聊天消息
        if (task.player != null && !task.player.isRemoved() && !task.silent) {
            task.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                PrefabCustomAddon.tr("build.done", task.constructionId, task.placedCount, (int) elapsedMs))
                .withStyle(net.minecraft.ChatFormatting.GREEN));
        }

        // === 云端自动备份 ===
        // KubeJS 联动蓝图走 silent 模式, 完成后不存云端 (云端 tab 只放原生 CustomBlueprintItem 出的建筑)
        if (task.silent) {
            PrefabCustomAddon.LOGGER.info("[BUILD-ASYNC] silent 模式, 跳过云端备份 (KubeJS 联动蓝图不入云端)");
            return;
        }
        // === 云端自动备份 (新版: 直接调 CloudBuildingManager, 不再走反射) ===
        // 每次自定义蓝图建造完成, 把整栋建筑存到玩家云端, 默认 placed=true.
        // 关键: 快照存的是**原始 (未旋转) pos + state**, 旋转在使用 (preview/summon) 时按 overrideFacing
        //   应用, 跟玩家预览里看到的朝向完全一致. 老格式 (旋转后的 pos + 原始 state) 会在加载时被
        //   CloudBuilding.fromNbt 自动反旋转迁移到新格式.
        try {
            int minX = 0, minY = 0, minZ = 0, maxX = 0, maxY = 0, maxZ = 0;
            boolean first = true;
            java.util.List<com.prefab.addon.cloud.CloudBuilding.BlockSnapshot> snapshots =
                new java.util.ArrayList<>(task.blocks.size());
            for (var b : task.blocks) {
                int lx = b.pos.getX();
                int ly = b.pos.getY();
                int lz = b.pos.getZ();
                // 存原始 pos, 不在保存时旋转
                if (first) {
                    minX = maxX = lx; minY = maxY = ly; minZ = maxZ = lz;
                    first = false;
                } else {
                    if (lx < minX) minX = lx; if (lx > maxX) maxX = lx;
                    if (ly < minY) minY = ly; if (ly > maxY) maxY = ly;
                    if (lz < minZ) minZ = lz; if (lz > maxZ) maxZ = lz;
                }
                // lx/ly/lz 是相对 placedAt (= task.origin) 的原始坐标, state 也是原始 state.
                // 配合 placedAt 就能精确定位 + 旋转.
                snapshots.add(new com.prefab.addon.cloud.CloudBuilding.BlockSnapshot(lx, ly, lz, b.state));
            }
            int sizeX = maxX - minX + 1;
            int sizeY = maxY - minY + 1;
            int sizeZ = maxZ - minZ + 1;
            // === 关键: placedAt 必须是"建筑某个参考点"对应的世界绝对坐标,
            //   这样 recall 时 target = placedAt + rotate(lx/ly/lz, buildFacing) 能直接对到方块实际世界位置.
            //   之前写成 realOrigin = task.origin + (minX, minY, minZ), 但放出 (summon) 时
            //   origin = playerHead, 两边原点不同, 召唤/收回位置错位, 出现"收回半边".
            //   修法: placedAt 直接用 task.origin, lx/ly/lz 是相对 task.origin 的原始坐标,
            //   这样 recall 算 placedAt + rotate(lx/ly/lz, buildFacing), summon 算 playerHead + rotate(lx/ly/lz, overrideFacing), 公式统一.
            BlockPos placedAt = task.origin;

            // 扫描每个方块位置, 存方块 + TE (含容器里的物品, 告示牌文字, 漏斗等)
            // 玩家在世界里可以给建筑加东西 (放进箱子里的物品等), 收回时这些东西会回到背包.
            // 注: 备份的是"建造完成时"的 TE 状态, 不包含玩家后续添加的东西 (那是收回时才扫描的).
            // TE 坐标也要存原始 (相对 task.origin 的 pre-rotation 坐标), 跟 snapshots 一致.
            List<com.prefab.addon.cloud.CloudBuilding.TileEntitySnapshot> teSnapshots =
                new java.util.ArrayList<>();
            for (var b : task.blocks) {
                int blx = b.pos.getX();
                int bly = b.pos.getY();
                int blz = b.pos.getZ();
                BlockPos worldPos = task.origin.offset(blx, bly, blz);
                if (!task.level.isLoaded(worldPos)) continue;
                net.minecraft.world.level.block.entity.BlockEntity te = task.level.getBlockEntity(worldPos);
                if (te == null) continue;
                try {
                    net.minecraft.nbt.CompoundTag teNbt = te.saveWithFullMetadata(task.level.registryAccess());
                    if (teNbt == null || teNbt.isEmpty()) continue;
                    teSnapshots.add(new com.prefab.addon.cloud.CloudBuilding.TileEntitySnapshot(
                        blx, bly, blz, teNbt));
                } catch (Throwable ignored) {
                    // 单个 TE 序列化失败不影响其他方块
                }
            }

            // 查 displayName (玩家期望的"自定义建筑本身的名字")
            String displayName = task.constructionId;
            byte[] thumbnailBytes = null;
            try {
                com.prefab.addon.extension.ConstructionInfo info =
                    com.prefab.addon.extension.ExtensionPackManager.getInstance()
                        .findConstruction(task.packName, task.constructionId);
                if (info != null && info.getName() != null && !info.getName().isEmpty()) {
                    displayName = info.getName();
                }
                // 把建筑自带的图片 (拓展包 pngData / 单文件 .png) 嵌入云端存档,
                // 玩家在云端 tab 就能直接看到对应缩略图, 不会因为 LocalBuilding 被删/换位置而丢图.
                if (info != null && info.hasPreviewImage()) {
                    thumbnailBytes = info.getPreviewBytes();
                    if (thumbnailBytes == null || thumbnailBytes.length == 0) thumbnailBytes = null;
                }
            } catch (Throwable dn) {
                PrefabCustomAddon.LOGGER.debug("[BUILD-ASYNC] get displayName failed, fallback to constructionId", dn);
            }

            // 跳过 placement (placed=true), 玩家随时可以收回/重新放出.
            com.prefab.addon.cloud.CloudBuilding cb = new com.prefab.addon.cloud.CloudBuilding(
                java.util.UUID.randomUUID().toString());
            cb.name = displayName;
            cb.packName = task.packName;
            cb.constructionId = task.constructionId;
            cb.timestamp = System.currentTimeMillis();
            cb.placed = true;
            cb.placedAt = placedAt;
            cb.facing = facingFromRotationSteps(task.rotationSteps);
            cb.sizeX = sizeX;
            cb.sizeY = sizeY;
            cb.sizeZ = sizeZ;
            cb.blocks.addAll(snapshots);
            cb.tileEntities.addAll(teSnapshots);
            cb.thumbnailPng = thumbnailBytes;
            PrefabCustomAddon.LOGGER.info("[BUILD-ASYNC][CLOUD-AUTO] 备份 {} 块 ({} TE), placedAt={}, size={}x{}x{}, placedCount={}/{}, thumbnail={}",
                snapshots.size(), teSnapshots.size(), placedAt, sizeX, sizeY, sizeZ,
                task.placedCount, task.totalBlocks,
                thumbnailBytes == null ? "无" : (thumbnailBytes.length + "B"));

            com.prefab.addon.cloud.CloudBuildingManager.getInstance().add(task.player, cb);

            if (task.player != null && !task.player.isRemoved()) {
                task.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    PrefabCustomAddon.tr("build.cloud_saved", displayName))
                    .withStyle(net.minecraft.ChatFormatting.AQUA));
            }
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[BUILD-ASYNC] 云端备份失败 (非致命, 建筑仍在世界中)", t);
        }
    }

    /** rotation steps → Direction. 跟 facingToRotationSteps 反向. */
    private static net.minecraft.core.Direction facingFromRotationSteps(int steps) {
        return switch (((steps % 4) + 4) % 4) {
            case 0 -> net.minecraft.core.Direction.SOUTH;
            case 1 -> net.minecraft.core.Direction.EAST;
            case 2 -> net.minecraft.core.Direction.NORTH;
            case 3 -> net.minecraft.core.Direction.WEST;
            default -> net.minecraft.core.Direction.SOUTH;
        };
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
     * 消耗玩家背包里绑定的 Custom Blueprint (复用 placeStructure 的逻辑).
     *
     * <p>双层查找策略, 兼容老版本蓝图 (老版本 bind 时用了 getPackageName(), 跟新版本 findConstruction
     * 用的 getName() 不一致, 导致严格 isBoundTo 失败):
     * <ol>
     *   <li>严格查找: 用 info (getName() + getId()) 匹配 - 正常情况</li>
     *   <li>兜底查找: 只按 constructionId 匹配 - 老版本蓝图兼容</li>
     * </ol>
     */
    private static void consumeBlueprint(BuildTask task) {
        ServerPlayer player = task.player;
        if (player == null) return;
        Inventory inv = player.getInventory();

        // === 外包建筑: 走专用路径, 用 buildingId 匹配 OutsourceBlueprintItem ===
        // packName 是 OutsourceBuildManager.OUTSOURCE_PACK_NAME 时跳过 ExtensionPackManager
        // 查找 (那个包里根本没有 outsource 的 construction, 找也找不到), 直接按
        // buildingId 匹配背包里硬编码的 OutsourceBlueprintItem.
        if (com.prefab.addon.structure.OutsourceBuildManager.OUTSOURCE_PACK_NAME.equals(task.packName)) {
            String buildingId = com.prefab.addon.structure.OutsourceBuildManager
                .parseBuildingIdFromConstructionId(task.constructionId);
            PrefabCustomAddon.LOGGER.info(
                "[BUILD-ASYNC] consumeBlueprint: outsource path, buildingId={}", buildingId);
            com.prefab.addon.structure.OutsourceBuildManager.consumeOutsourceBlueprint(player, buildingId);
            return;
        }

        // 用 ConstructionInfo 找蓝图 (但 task 没有 info, 用 packName+constructionId 反查)
        com.prefab.addon.extension.ConstructionInfo info =
            com.prefab.addon.extension.ExtensionPackManager.getInstance()
                .findConstruction(task.packName, task.constructionId);
        if (info == null) {
            // 老版本蓝图可能用 getPackageName() 绑定, 直接 lookup 失败.
            // 兜底: 按 constructionId 找, 然后按 id 匹配 inventory 里的蓝图.
            PrefabCustomAddon.LOGGER.warn("[BUILD-ASYNC] consumeBlueprint: findConstruction({}/{}) 失败, 尝试按id 兜底",
                task.packName, task.constructionId);
            consumeBlueprintByIdOnly(player, inv, task.constructionId);
            return;
        }

        // 优先消耗主手选中格, 其次其他 (用 isBoundToBlueprint, 兼容 mod 原生 + KubeJS 联动)
        // 之前用 CustomBlueprintItem.isBoundTo 只能识别原生 CustomBlueprintItem, 玩家主手拿 KubeJS
        // 蓝图时会被跳过, 然后退到 inventory 扫描找到第一个 CustomBlueprintItem (在 KubeJS 蓝图
        // 之前的 slot), 错误消耗自定义蓝图. 改用 isBoundToBlueprint (从 CUSTOM_DATA 读
        // packName+constructionId, 对原生和 KubeJS 都通用) 修这个 bug.
        int selected = inv.selected;
        ItemStack hotbarStack = inv.getItem(selected);
        int foundSlot = -1;
        ItemStack foundStack = ItemStack.EMPTY;
        if (isBoundToBlueprint(hotbarStack, info)) {
            foundSlot = selected;
            foundStack = hotbarStack;
        } else {
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (isBoundToBlueprint(stack, info)) {
                    foundSlot = i;
                    foundStack = stack;
                    break;
                }
            }
        }
        if (foundStack.isEmpty()) {
            // 严格匹配失败 - 兜底按 constructionId 查找 (兼容老蓝图)
            // 详细打印每个槽位的实际绑定值, 便于诊断 isBoundTo 为何失败.
            // 用 isPlayerBlueprint (兼容 mod 原生 + KubeJS 联动), 跟严格匹配路径保持一致.
            StringBuilder dump = new StringBuilder();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (!isPlayerBlueprint(s)) continue;
                String bPack = readBoundPackName(s);
                String bCid  = readBoundConstructionId(s);
                dump.append(String.format("  slot=%d item=%s bound=[%s/%s] count=%d; ",
                    i, s.getItem(), bPack, bCid, s.getCount()));
            }
            PrefabCustomAddon.LOGGER.warn(
                "[BUILD-ASYNC] consumeBlueprint: isBoundToBlueprint 失败 for {}/{}, 实际蓝图绑定: [{}], 尝试按id 兜底",
                task.packName, task.constructionId, dump);
            consumeBlueprintByIdOnly(player, inv, task.constructionId);
            return;
        }
        doConsume(player, inv, foundSlot, foundStack, "strict " + task.packName + "/" + task.constructionId);
    }

    /**
     * 实际执行消耗 (减少 count, 推送到客户端).
     */
    private static void doConsume(ServerPlayer player, Inventory inv, int slot,
                                  ItemStack stack, String context) {
        int prevCount = stack.getCount();
        if (stack.getCount() == 1) {
            inv.setItem(slot, ItemStack.EMPTY);
        } else {
            stack.setCount(stack.getCount() - 1);
        }
        // 强制推送到客户端: containerMenu 是 inventoryMenu 时 (玩家未开容器) 才有效
        if (player.containerMenu != null) {
            player.containerMenu.broadcastChanges();
        }
        // 兜底: 主动发 SetContainerContent 给客户端, 避免 inventoryMenu 不广播导致客户端看不见
        if (player.connection != null) {
            int stateId = player.containerMenu != null ? player.containerMenu.getStateId() : 0;
            net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket packet =
                new net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket(
                    net.minecraft.world.inventory.InventoryMenu.CONTAINER_ID, stateId,
                    player.inventoryMenu.getItems(), net.minecraft.world.item.ItemStack.EMPTY);
            player.connection.send(packet);
        }
        inv.setChanged();
        PrefabCustomAddon.LOGGER.info("[BUILD-ASYNC] 已消耗蓝图 (slot {} was {}, ctx={}), 已强制同步客户端",
            slot, prevCount, context);
    }

    /**
     * 兜底方案: 不用 ConstructionInfo, 直接按 constructionId 匹配背包里的蓝图.
     * 用于老版本蓝图 (包名字段不一致导致 info 反查失败) 的兼容
     */
    private static void consumeBlueprintByIdOnly(ServerPlayer player, Inventory inv, String constructionId) {
        int selected = inv.selected;
        ItemStack hotbarStack = inv.getItem(selected);
        int foundSlot = -1;
        ItemStack foundStack = ItemStack.EMPTY;
        // 优先主手. 兼容 mod 原生 + KubeJS 注册的带 tag 物品.
        if (isPlayerBlueprint(hotbarStack)
            && readBoundConstructionId(hotbarStack).equals(constructionId)) {
            foundSlot = selected;
            foundStack = hotbarStack;
        } else {
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (isPlayerBlueprint(stack)
                    && readBoundConstructionId(stack).equals(constructionId)) {
                    foundSlot = i;
                    foundStack = stack;
                    break;
                }
            }
        }
        if (foundStack.isEmpty()) {
            PrefabCustomAddon.LOGGER.warn("[BUILD-ASYNC] 兜底: 没找到id={} 的蓝图", constructionId);
            return;
        }
        doConsume(player, inv, foundSlot, foundStack, "id-only " + constructionId);
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
        ResourceLocation.fromNamespaceAndPath(PrefabCustomAddon.MOD_ID, "player_blueprint"));

    /** NBT 字段名 (跟 CustomBlueprintItem 完全一致, KubeJS 注册时也是这套字段). */
    private static final String NBT_PACK_NAME    = "packName";
    private static final String NBT_CONSTRUCTION = "constructionId";

    /**
     * 判断 ItemStack 是否是"玩家蓝图" (兼容 mod 原生 + KubeJS 注册).
     * mod 原生: 物品类型是 {@link CustomBlueprintItem}.
     * KubeJS: 任意物品, 但带 {@code prefab_custom_addon:player_blueprint} tag.
     */
    public static boolean isPlayerBlueprint(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.getItem() instanceof CustomBlueprintItem) return true;
        return stack.is(PLAYER_BLUEPRINT_TAG);
    }

    /**
     * 判断 ItemStack 是否是"KubeJS 联动蓝图" (排除 mod 原生 CustomBlueprintItem).
     * <p>判断逻辑: 带 {@code prefab_custom_addon:player_blueprint} tag <b>且</b> 不是
     * {@link CustomBlueprintItem} 实例. KubeJS 在 startup_scripts 里
     * {@code add('kubejs:my_blueprint')} 加进这个 tag, 这些蓝图建造时应该走 silent
     * 模式 (不刷聊天消息 / 不入云端 / 悬浮显示本 mod 名).</p>
     */
    public static boolean isKubeJSPlayerBlueprint(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.getItem() instanceof CustomBlueprintItem) return false;
        return stack.is(PLAYER_BLUEPRINT_TAG);
    }

    /** 读绑定的 packName (兼容 mod 原生 + KubeJS). */
    public static String readBoundPackName(ItemStack stack) {
        net.minecraft.world.item.component.CustomData data =
            stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (data == null) return "";
        return data.copyTag().getString(NBT_PACK_NAME);
    }

    /** 读绑定的 constructionId (兼容 mod 原生 + KubeJS). */
    public static String readBoundConstructionId(ItemStack stack) {
        net.minecraft.world.item.component.CustomData data =
            stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (data == null) return "";
        return data.copyTag().getString(NBT_CONSTRUCTION);
    }

    /** 是否绑了 packName+constructionId (兼容 mod 原生 + KubeJS). */
    public static boolean isBlueprintBound(ItemStack stack) {
        return !readBoundPackName(stack).isEmpty() && !readBoundConstructionId(stack).isEmpty();
    }

    /** 蓝图是否绑到 info (兼容 mod 原生 + KubeJS). */
    public static boolean isBoundToBlueprint(ItemStack stack, com.prefab.addon.extension.ConstructionInfo info) {
        if (info == null) return false;
        String infoPack = (info.getPack() != null)
            ? info.getPack().getName()
            : com.prefab.addon.extension.ExtensionPackManager.STANDALONE_PACKAGE;
        return readBoundPackName(stack).equals(infoPack)
            && readBoundConstructionId(stack).equals(info.getId());
    }
}