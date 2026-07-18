package com.prefab.addon.structure;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.config.PlayerPreferences;
import com.prefab.addon.items.CustomBlueprintItem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 异步建造管理器 - 大建筑分批放置, 每 tick 放 N% 块, 避免单 tick 卡死.
 *
 * <h3>为什么分批?</h3>
 * 4w 块结构一次性 setBlock 1w+ 次 → 单 tick 卡 1+ 秒, 玩家感觉"卡死了".
 * 拆成 10 批 (默认 10%/批, 100ms/tick) → 每 tick 只放 4k 块 ≈ 几十 ms,
 * 总时间 ≈ 1-10s, 期间玩家可以正常玩.
 *
 * <h3>流程</h3>
 * <pre>
 *   placeStructureAsync(player, level, origin, pack, id)
 *     ↓
 *   把 (pack, id, origin, blocks 列表) 打包成 BuildTask
 *   注册到 ACTIVE_TASKS (按 player UUID 索引)
 *   立即给玩家发 "开始建造, 共 N 块" 消息
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
 * 同一个玩家不能同时跑 2 个 task. 启动新 task 时若已有 task, 旧 task 直接完成丢弃 (避免方块错位).
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
        public int placedCount;          // 已放置数
        public int nextIndex;            // 下一个要放的方块索引
        public boolean completed;        // 是否全部完成
        public boolean cancelled;        // 玩家退出/重置
        public boolean blueprintConsumed; // 蓝图是否已消耗 (完成时消耗)

        public BuildTask(ServerPlayer p, Level l, BlockPos o,
                         String pack, String id,
                         List<CustomStructureBuilder.BlockData> blocks) {
            this(p, l, o, pack, id, blocks, 0);
        }

        public BuildTask(ServerPlayer p, Level l, BlockPos o,
                         String pack, String id,
                         List<CustomStructureBuilder.BlockData> blocks,
                         int rotationSteps) {
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
        }

        public int getPercent() {
            return totalBlocks > 0 ? (placedCount * 100 / totalBlocks) : 100;
        }
    }

    // 当前所有玩家的活跃 task (按 UUID 索引)
    private static final Map<UUID, BuildTask> ACTIVE_TASKS = new ConcurrentHashMap<>();

    private AsyncBuildManager() {}

    /**
     * 启动一个异步建造任务.
     * 如果该玩家已有 task, 旧的会标记 cancelled (新 task 覆盖).
     */
    public static void startTask(ServerPlayer player, Level level, BlockPos origin,
                                 String packName, String constructionId,
                                 List<CustomStructureBuilder.BlockData> blocks) {
        startTask(player, level, origin, packName, constructionId, blocks, net.minecraft.core.Direction.SOUTH);
    }

    /**
     * 带旋转的 startTask 重载. houseFacing 是预览时的旋转方向,
     * 服务端必须用同样的旋转放置方块, 否则实际建筑位置会跟预览错开.
     */
    public static void startTask(ServerPlayer player, Level level, BlockPos origin,
                                 String packName, String constructionId,
                                 List<CustomStructureBuilder.BlockData> blocks,
                                 net.minecraft.core.Direction houseFacing) {
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

        BuildTask task = new BuildTask(player, level, origin, packName, constructionId, blocks, steps);
        ACTIVE_TASKS.put(player.getUUID(), task);
        PrefabCustomAddon.LOGGER.info("[BUILD-ASYNC] 启动: player={} pack={} construction={} origin={} totalBlocks={} batchPercent={}% houseFacing={}({} steps)",
            player.getName().getString(), packName, constructionId, origin,
            task.totalBlocks, PlayerPreferences.get().getBuildBatchPercent(), houseFacing, steps);
        if (player != null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§e开始建造 " + constructionId + ": " + task.totalBlocks + " 块 (异步, 每批 "
                + PlayerPreferences.get().getBuildBatchPercent() + "%, 预计 <10s)"));
        }
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
     * Server tick 事件 - 每 tick 处理所有活跃 task 的一批.
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
            // 玩家离线了?
            if (t.player == null || !t.player.isAlive() || t.player.isRemoved()) {
                PrefabCustomAddon.LOGGER.info("[BUILD-ASYNC] 玩家离线, 取消 task");
                return true;
            }
            return false;
        });
    }

    private static void processTick(BuildTask task) {
        if (task.completed || task.cancelled) return;

        // 检查玩家是否还在 (可能切换存档等)
        if (task.player == null || task.player.isRemoved()) {
            task.cancelled = true;
            return;
        }
        ServerPlayer player = task.player;

        int batchPercent = PlayerPreferences.get().getBuildBatchPercent();
        // 关键: 玩家反馈"1% 还是太快了" → 公式从 totalBlocks * percent / 100 改成 / 1000,
        //   1% 实际相当于 0.1%/tick, 1000 块建筑 1% 也要 1000 tick (50s @ 20tps).
        //   10% 仍然是 5s, 100% 仍然 0.5s, 总耗时由百分比决定 (跟建筑大小无关).
        //   - 1% → ~50s (无论建筑大小, 因为 totalBlocks * 1 / 1000 块/tick * totalBlocks 块 = 1000 tick)
        //   - 10% → ~5s
        //   - 50% → ~1s
        //   - 100% → ~0.5s
        int batchSize = Math.max(1, task.totalBlocks * batchPercent / 1000);

        int endIdx = Math.min(task.totalBlocks, task.nextIndex + batchSize);

        // 关键: 异步建造必须用 UPDATE_CLIENTS | UPDATE_SUPPRESS_DROPS
        //   = 2 | 32 = 34
        // - UPDATE_CLIENTS: 通知客户端更新方块
        // - UPDATE_SUPPRESS_DROPS: 防止 onRemove 时把方块变成掉落物
        //   (红石/拉杆/红石粉等被放置时 onPlace 会检查周围方块,
        //    分批阶段周围方块还是 air, 红石组件会先被 setBlock 设为 "无效状态" 再被
        //    removeBlock, 没有 SUPPRESS_DROPS 就会变成掉落物. 1.20.1 也是同样问题.)
        // 不要用 UPDATE_NEIGHBORS (=1), 否则会立即通知 6 个邻居触发 neighborChanged,
        //   红石组件在依赖的方块尚未放置时会被判定为 "失去支撑" 而立即 break.
        // 全部放完后再在 onCompleted() 里对整个 box 做一次全量红石重算.
        int flags = net.minecraft.world.level.block.Block.UPDATE_CLIENTS
                  | net.minecraft.world.level.block.Block.UPDATE_SUPPRESS_DROPS;
        for (int i = task.nextIndex; i < endIdx; i++) {
            CustomStructureBuilder.BlockData data = task.blocks.get(i);
            if (data == null) continue;
            try {
                // 关键: 旋转 local pos (绕 (0,0,0) 中心, 跟客户端 offsetStructureBlocks 一致)
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
                task.level.setBlock(target, data.state, flags);
                task.placedCount++;
            } catch (Throwable t) {
                PrefabCustomAddon.LOGGER.warn("[BUILD-ASYNC] setBlock 失败 at {}: {}", data.pos, t.getMessage());
            }
        }
        task.nextIndex = endIdx;

        // 进度反馈 (每 10% 给玩家发一次消息)
        int currentPct = task.getPercent();
        int lastReportedPct = (task.placedCount - batchSize <= 0) ? 0 :
                              ((task.placedCount - batchSize) * 100 / task.totalBlocks);
        if (currentPct / 10 > lastReportedPct / 10 && currentPct < 100) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§7建造中: " + currentPct + "% (" + task.placedCount + "/" + task.totalBlocks + ")"));
        }

        if (task.nextIndex >= task.totalBlocks) {
            task.completed = true;
        }
    }

    private static void onCompleted(BuildTask task) {
        long elapsedMs = System.currentTimeMillis() - task.startTickMs;
        PrefabCustomAddon.LOGGER.info("[BUILD-ASYNC] 完成: player={} pack={} construction={} placed={}/{} elapsed={}ms",
            task.player.getName().getString(), task.packName, task.constructionId,
            task.placedCount, task.totalBlocks, elapsedMs);

        // 全部放完后, 对整栋建筑的包围盒做一次全量邻居更新,
        // 让红石 / 拉杆 / 红石粉等组件能正确感知到它们周围的最终状态.
        try {
            triggerRedstoneUpdate(task);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[BUILD-ASYNC] 红石重算失败 (非致命)", t);
        }

        // 消耗蓝图 (成功后才消耗, 跟旧逻辑一致)
        if (!task.blueprintConsumed) {
            consumeBlueprint(task);
            task.blueprintConsumed = true;
        }

        if (task.player != null && !task.player.isRemoved()) {
            task.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§a✓ 建造完成 " + task.constructionId + ": " + task.placedCount + " 块 (耗时 "
                + (elapsedMs / 1000.0) + "s)").withStyle(net.minecraft.ChatFormatting.GREEN));
        }
    }

    /**
     * 对整栋建筑的包围盒做一次全量邻居更新, 让红石 / 拉杆 / 红石粉等组件
     * 能正确感知到它们周围的最终状态 (避免异步分批放置导致邻居更新时
     * 依赖的方块还没放, 从而被破坏变成掉落物).
     */
    private static void triggerRedstoneUpdate(BuildTask task) {
        if (task.blocks.isEmpty()) return;
        // 计算包围盒 (用 min/max 整数, 避免依赖 BlockPos.MutableBox 内部类).
        // 关键: 必须按 task.rotationSteps 旋转每个 local pos 后再求 min/max,
        // 否则旋转后的建筑 box 跟实际的旋转后位置不匹配, 触发红石重算的范围也会错.
        BlockPos origin = task.origin;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        boolean init = false;
        for (CustomStructureBuilder.BlockData data : task.blocks) {
            if (data == null) continue;
            int lx = data.pos.getX();
            int ly = data.pos.getY();
            int lz = data.pos.getZ();
            for (int s = 0; s < task.rotationSteps; s++) {
                int nlx =  lz;
                int nlz = -lx;
                lx = nlx;
                lz = nlz;
            }
            int wx = origin.getX() + lx;
            int wy = origin.getY() + ly;
            int wz = origin.getZ() + lz;
            if (!init) {
                minX = maxX = wx;
                minY = maxY = wy;
                minZ = maxZ = wz;
                init = true;
            } else {
                if (wx < minX) minX = wx;
                if (wy < minY) minY = wy;
                if (wz < minZ) minZ = wz;
                if (wx > maxX) maxX = wx;
                if (wy > maxY) maxY = wy;
                if (wz > maxZ) maxZ = wz;
            }
        }
        if (!init) return;
        PrefabCustomAddon.LOGGER.info("[BUILD-ASYNC] 触发红石重算: box=[{}..{},{}..{},{}..{}] ({} 块)",
            minX, maxX, minY, maxY, minZ, maxZ, task.placedCount);
        // 遍历包围盒内每个方块, 通知其邻居
        for (BlockPos p : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            if (task.level.getBlockState(p).isAir()) continue;
            net.minecraft.world.level.block.state.BlockState bs = task.level.getBlockState(p);
            task.level.blockUpdated(p, bs.getBlock());
            task.level.updateNeighborsAt(p, bs.getBlock());
        }
    }

    /**
     * 消耗玩家背包里绑定的 Custom Blueprint (复用 placeStructure 的逻辑).
     *
     * <p>双层查找策略, 兼容老版本蓝图 (旧版本 bind 时用了 getPackageName(), 跟新版本 findConstruction
     * 用的 getName() 不一致, 导致严格 isBoundTo 失败):
     * <ol>
     *   <li>严格查找: 用 info (getName() + getId()) 匹配 - 正常情况</li>
     *   <li>退化查找: 只按 constructionId 匹配 - 旧版本蓝图兼容</li>
     * </ol>
     */
    private static void consumeBlueprint(BuildTask task) {
        ServerPlayer player = task.player;
        if (player == null) return;
        Inventory inv = player.getInventory();
        // 用 ConstructionInfo 找蓝图 (但 task 没有 info, 用 packName+constructionId 反查)
        com.prefab.addon.extension.ConstructionInfo info =
            com.prefab.addon.extension.ExtensionPackManager.getInstance()
                .findConstruction(task.packName, task.constructionId);
        if (info == null) {
            // 旧版本蓝图可能用 getPackageName() 绑定, 直接 lookup 失败.
            // 退化: 按 constructionId 找, 然后按 id 匹配 inventory 里的蓝图.
            PrefabCustomAddon.LOGGER.warn("[BUILD-ASYNC] consumeBlueprint: findConstruction({}/{}) 失败, 尝试按 id 退化",
                task.packName, task.constructionId);
            consumeBlueprintByIdOnly(player, inv, task.constructionId);
            return;
        }

        // 优先消耗主手选中槽, 其次其他 (用严格的 isBoundTo)
        int selected = inv.selected;
        ItemStack hotbarStack = inv.getItem(selected);
        int foundSlot = -1;
        ItemStack foundStack = ItemStack.EMPTY;
        if (CustomBlueprintItem.isBoundTo(hotbarStack, info)) {
            foundSlot = selected;
            foundStack = hotbarStack;
        } else {
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (CustomBlueprintItem.isBoundTo(stack, info)) {
                    foundSlot = i;
                    foundStack = stack;
                    break;
                }
            }
        }
        if (foundStack.isEmpty()) {
            // 严格匹配失败 - 退化按 constructionId 查找 (兼容旧蓝图)
            // 详细打印每个槽位的实际绑定值, 便于诊断 isBoundTo 为何失败
            StringBuilder dump = new StringBuilder();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (s.isEmpty() || !(s.getItem() instanceof CustomBlueprintItem)) continue;
                String bPack = CustomBlueprintItem.getBoundPackName(s);
                String bCid  = CustomBlueprintItem.getBoundConstructionId(s);
                dump.append(String.format("  slot=%d bound=[%s/%s] count=%d; ", i, bPack, bCid, s.getCount()));
            }
            PrefabCustomAddon.LOGGER.warn(
                "[BUILD-ASYNC] consumeBlueprint: isBoundTo 失败 for {}/{}, 实际蓝图绑定: [{}], 尝试按 id 退化",
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
     * 退化方案: 不用 ConstructionInfo, 直接按 constructionId 匹配背包里的蓝图.
     * 用于旧版本蓝图 (包名字段不一致导致 info 反查失败) 的兼容.
     */
    private static void consumeBlueprintByIdOnly(ServerPlayer player, Inventory inv, String constructionId) {
        int selected = inv.selected;
        ItemStack hotbarStack = inv.getItem(selected);
        int foundSlot = -1;
        ItemStack foundStack = ItemStack.EMPTY;
        // 优先主手
        if (hotbarStack.getItem() instanceof com.prefab.addon.items.CustomBlueprintItem
            && com.prefab.addon.items.CustomBlueprintItem.getBoundConstructionId(hotbarStack).equals(constructionId)) {
            foundSlot = selected;
            foundStack = hotbarStack;
        } else {
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.getItem() instanceof com.prefab.addon.items.CustomBlueprintItem
                    && com.prefab.addon.items.CustomBlueprintItem.getBoundConstructionId(stack).equals(constructionId)) {
                    foundSlot = i;
                    foundStack = stack;
                    break;
                }
            }
        }
        if (foundStack.isEmpty()) {
            PrefabCustomAddon.LOGGER.warn("[BUILD-ASYNC] 退化: 没找到 id={} 的蓝图", constructionId);
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
}
