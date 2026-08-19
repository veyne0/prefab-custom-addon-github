package com.prefab.addon.cloud;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.ExtensionPackManager;
import com.prefab.addon.integration.BuildingDatabase;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 服务端权威的云端建筑管理器.
 *
 * <h2>存储</h2>
 * <pre>
 *   联机:  &lt;worldDir&gt;/data/prefab-cloudbuilds/&lt;uuid&gt;.nbt
 *   单机:  &lt;worldDir&gt;/data/prefab-cloudbuilds/localplayer.nbt
 * </pre>
 *
 * <h2>线程模型</h2>
 * 所有方法必须在 server thread (或 server.execute() 里) 调用. 内部用 per-instance lock
 * 防止并发的磁盘 IO (单机的云端 tab 异步加载图等场景, 避免读和写重叠).
 *
 * <h2>同步策略</h2>
 * 玩家进服 → {@link #onPlayerJoin} 读盘 + 内存 + 全量推给客户端 (用 S2C sync packet).
 * 之后所有改动 (auto-save / recall / summon) 都用全量 broadcast, 简单可靠.
 *
 * <h2>单机模式</h2>
 * 客户端 = 服务端, 直接走 getInstance() 读写, 不发包.
 */
public class CloudBuildingManager {

    private static final CloudBuildingManager INSTANCE = new CloudBuildingManager();
    public static CloudBuildingManager getInstance() { return INSTANCE; }

    private final Map<UUID, List<CloudBuilding>> store = new ConcurrentHashMap<>();
    private final ReentrantLock ioLock = new ReentrantLock();
    private MinecraftServer server;

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    // ============================================================
    // 路径
    // ============================================================

    private Path getRootDir() {
        if (server != null) {
            // 1.21.1: LevelResource.PLAYER_DATA_DIR 直接对应 <worldDir>/data/ 目录,
            // 云端建筑存到 <worldDir>/data/prefab-cloudbuilds/ 与 vanilla datapack data 同级, 不污染存档.
            return server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve("prefab-cloudbuilds");
        }
        // 兜底: 客户端直调 (不推荐, 应该走 ClientCache)
        Path mc = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath();
        return mc.resolve("prefab-cloudbuilds");
    }

    private Path playerFile(UUID playerUuid) {
        return getRootDir().resolve(playerUuid + ".nbt");
    }

    // ============================================================
    // 加载 / 保存
    // ============================================================

    public void onPlayerJoin(ServerPlayer player) {
        UUID uuid = player.getUUID();
        List<CloudBuilding> list = loadFromDisk(uuid);
        store.put(uuid, list);
        PrefabCustomAddon.LOGGER.info("[CLOUD] 玩家 {} 加载 {} 个云端建筑", player.getName().getString(), list.size());

        // === 联动: rebuild BuildingDatabase (Jade 源) ===
        // 服务端拿不到当前 ServerLevel 的引用 (player 才刚 join, level 可能还在切),
        // 所以维度用 CloudBuilding.dimensionId 字符串反解, 拿不到就 fallback 到 overworld.
        // 这里清一次再 register, 避免热重载场景下同 id 重复 register.
        for (CloudBuilding b : list) {
            try {
                if (!b.placed) continue;
                BuildingDatabase.unregister(b.id);
                ResourceLocation dimLoc = parseDimensionId(b.dimensionId);
                ResourceKey<Level> dimKey = ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION, dimLoc);
                int steps = CloudBuilding.facingToRotationSteps(b.facing);
                BuildingDatabase.register(new BuildingDatabase.Record(
                    b.id, b.name, player.getName().getString(), player.getUUID(),
                    dimKey, b.placedAt, b.sizeX, b.sizeY, b.sizeZ, steps));
            } catch (Throwable t) {
                PrefabCustomAddon.LOGGER.warn("[CLOUD] rebuild BuildingDatabase for {} failed: {}",
                    b.id, t.getMessage());
            }
        }

        syncToClient(player);
    }

    /** CloudBuilding.dimensionId (字符串) → ResourceLocation, 失败 fallback 到 overworld. */
    private static ResourceLocation parseDimensionId(String s) {
        if (s != null && !s.isEmpty()) {
            try {
                return ResourceLocation.parse(s);
            } catch (Throwable ignored) {}
        }
        return ResourceLocation.withDefaultNamespace("overworld");
    }

    public void onPlayerLeave(UUID playerUuid) {
        // 暂保留内存, 玩家可能很快回来
    }

    /** 关服时全量落盘 (兜底). */
    public void onServerStopping() {
        for (UUID uuid : store.keySet()) {
            saveToDisk(uuid);
        }
    }

    private List<CloudBuilding> loadFromDisk(UUID playerUuid) {
        ioLock.lock();
        try {
            Path file = playerFile(playerUuid);
            if (!Files.exists(file)) return new ArrayList<>();
            try {
                CompoundTag root = NbtIo.read(file);
                if (root == null) return new ArrayList<>();
                List<CloudBuilding> list = new ArrayList<>();
                ListTag arr = root.getList("buildings", CompoundTag.TAG_COMPOUND);
                for (int i = 0; i < arr.size(); i++) {
                    try {
                        list.add(CloudBuilding.fromNbt(arr.getCompound(i)));
                    } catch (Exception e) {
                        PrefabCustomAddon.LOGGER.warn("[CLOUD] 跳过损坏的云端建筑 #{}: {}", i, e.getMessage());
                    }
                }
                return list;
            } catch (IOException e) {
                PrefabCustomAddon.LOGGER.error("[CLOUD] 读 {} 失败: {}", file, e.getMessage());
                return new ArrayList<>();
            }
        } finally {
            ioLock.unlock();
        }
    }

    private void saveToDisk(UUID playerUuid) {
        ioLock.lock();
        try {
            List<CloudBuilding> list = store.getOrDefault(playerUuid, Collections.emptyList());
            Path file = playerFile(playerUuid);
            Files.createDirectories(file.getParent());
            CompoundTag root = new CompoundTag();
            ListTag arr = new ListTag();
            for (CloudBuilding b : list) arr.add(b.toNbt());
            root.put("buildings", arr);
            NbtIo.write(root, file);
            PrefabCustomAddon.LOGGER.info("[CLOUD] 保存 {} 个云端建筑到 {}", list.size(), file);
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("[CLOUD] 写盘失败: {}", e.getMessage());
        } finally {
            ioLock.unlock();
        }
    }

    // ============================================================
    // CRUD
    // ============================================================

    /** 新增 (auto-save 用). 同步给客户端. */
    public void add(ServerPlayer player, CloudBuilding b) {
        List<CloudBuilding> list = store.computeIfAbsent(player.getUUID(), k -> new ArrayList<>());
        list.removeIf(x -> x.id.equals(b.id));
        list.add(b);
        saveToDisk(player.getUUID());
        syncToClient(player);
        PrefabCustomAddon.LOGGER.info("[CLOUD] 玩家 {} 新增云端建筑 {} ({} 块)",
            player.getName().getString(), b.name, b.blocks.size());
    }

    /** 删除. */
    public void remove(ServerPlayer player, String buildingId) {
        List<CloudBuilding> list = store.get(player.getUUID());
        if (list == null) return;
        if (list.removeIf(x -> x.id.equals(buildingId))) {
            saveToDisk(player.getUUID());
            syncToClient(player);
            PrefabCustomAddon.LOGGER.info("[CLOUD] 玩家 {} 删除云端建筑 {}", player.getName().getString(), buildingId);
        }
    }

    /** 重命名. */
    public void rename(ServerPlayer player, String buildingId, String newName) {
        CloudBuilding b = getById(player.getUUID(), buildingId);
        if (b == null) return;
        b.name = newName;
        saveToDisk(player.getUUID());
        syncToClient(player);
    }

    public List<CloudBuilding> getAll(UUID playerUuid) {
        return store.getOrDefault(playerUuid, Collections.emptyList());
    }

    public CloudBuilding getById(UUID playerUuid, String buildingId) {
        for (CloudBuilding b : getAll(playerUuid)) {
            if (b.id.equals(buildingId)) return b;
        }
        return null;
    }

    // ============================================================
    // Recall / Summon
    // ============================================================

    /** 收回: 清世界方块 (包含把容器里的物品 drop 给玩家), placed=false. */
    public boolean recall(ServerPlayer player, String buildingId) {
        CloudBuilding b = getById(player.getUUID(), buildingId);
        if (b == null) {
            warn(player, "云端建筑不存在: " + buildingId);
            return false;
        }
        if (!b.placed) {
            info(player, "该建筑已经是收回状态");
            return false;
        }
        ServerLevel level = player.serverLevel();
        int restoredItems = clearBlocksAndCollectItems(level, b, player);
        b.placed = false;

        // === 联动: 从 BuildingDatabase 注销 (Jade 不再提示) ===
        // 客户端在收到下面的 syncToClient 后会自己 diff Xaero 航点
        try {
            BuildingDatabase.unregister(b.id);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[CLOUD] unregister BuildingDatabase failed: {}", t.getMessage());
        }

        saveToDisk(player.getUUID());
        syncToClient(player);
        success(player, "已收回: " + b.name + (restoredItems > 0
            ? " (回收 " + restoredItems + " 件物品)" : ""));
        PrefabCustomAddon.LOGGER.info("[CLOUD] 玩家 {} 收回建筑 {} ({} 块, 回收 {} 件物品, 原位置 {})",
            player.getName().getString(), b.name, b.blocks.size(), restoredItems, b.placedAt);
        return true;
    }

    /**
     * 删除: 把云端建筑彻底抹掉 (从磁盘 + 内存都删除). 仅当 !placed 时允许,
     * 否则拒绝并提示先收回 —— 否则会留下孤儿方块.
     *
     * @return true=删除成功, false=被拒绝 (placed=true 或不存在)
     */
    public boolean delete(ServerPlayer player, String buildingId) {
        CloudBuilding b = getById(player.getUUID(), buildingId);
        if (b == null) {
            warn(player, "云端建筑不存在: " + buildingId);
            return false;
        }
        if (b.placed) {
            warn(player, "建筑 \"" + b.name + "\" 当前已放出, 删除会留下孤儿方块, 请先收回再删除");
            return false;
        }
        String name = b.name;
        remove(player, buildingId);
        // === 联动: 删除时也清 BuildingDatabase (理论上 delete 要求 placed=false, 这里兜底) ===
        try {
            BuildingDatabase.unregister(buildingId);
        } catch (Throwable ignored) {}
        success(player, "已从云端删除: " + name);
        return true;
    }

    /**
     * 放出: 在 {@code overrideOrigin} (玩家预览选的位置, 通常 player.head 上方 1 格) 重建.
     * 如果 {@code overrideOrigin} 为 null, 退化到旧的 player.head 上方 1 格 (无预览路径).
     * @param overrideFacing 玩家预览时按 CTRL 旋转的方向, 服务端用此方向写 b.facing.
     *                       null 时退化到 b.facing 当前值.
     */
    public boolean summon(ServerPlayer player, String buildingId, BlockPos overrideOrigin, Direction overrideFacing) {
        CloudBuilding b = getById(player.getUUID(), buildingId);
        if (b == null) {
            warn(player, "云端建筑不存在: " + buildingId);
            return false;
        }
        if (b.placed) {
            warn(player, "该建筑已放出, 位置: " + b.placedAt.toShortString()
                + ", 请先收回再重新放出");
            return false;
        }
        ServerLevel level = player.serverLevel();
        BlockPos origin = overrideOrigin != null
            ? overrideOrigin
            : player.blockPosition().offset(0, 1, 0);
        // 实际放置朝向: 玩家预览时按 CTRL 旋转的 overrideFacing 优先, 没有就用建筑存档的 facing.
        Direction placeFacing = overrideFacing != null ? overrideFacing : b.facing;
        if (overrideFacing != null) {
            // 玩家在预览时旋转过 → 把建筑的"建造时朝向"也更新成玩家选的, 后续 recall/再 summon 用这个
            b.facing = overrideFacing;
        }
        // 旋转步数: 用 placeFacing (覆盖值优先) 算, 跟 CloudPreview 预览的 offsetStructureBlocks 一致.
        int steps = CloudBuilding.facingToRotationSteps(placeFacing);

        // 冲突检查: 用旋转后的 (lx, lz) 算 target
        for (CloudBuilding.BlockSnapshot bs : b.blocks) {
            int plx = CloudBuilding.rotateLX(bs.lx, bs.lz, steps);
            int plz = CloudBuilding.rotateLZ(bs.lx, bs.lz, steps);
            BlockPos target = origin.offset(plx, bs.ly, plz);
            BlockState cur = level.getBlockState(target);
            if (!cur.isAir() && !cur.canBeReplaced()) {
                warn(player, "位置冲突 @ " + target.toShortString()
                    + " (有 " + cur.getBlock().getDescriptionId() + "), 请换个位置");
                return false;
            }
        }
        placeBlocks(level, origin, b, steps);
        restoreTileEntities(level, origin, b, steps);
        b.placed = true;
        b.placedAt = origin;
        b.dimensionId = level.dimension().location().toString();

        // === 联动: 注册到 BuildingDatabase (Jade 查询源) ===
        // 服务端权威, 写一次即可, 客户端收到 CloudBuildingSyncPayload 时会再写自己的那份
        // Xaero 航点必须客户端调, 通过下面 saveToDisk + syncToClient 触发客户端处理
        try {
            BuildingDatabase.Record rec = new BuildingDatabase.Record(
                b.id, b.name, player.getName().getString(), player.getUUID(),
                level.dimension(), origin, b.sizeX, b.sizeY, b.sizeZ, steps);
            BuildingDatabase.register(rec);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[CLOUD] register BuildingDatabase failed: {}", t.getMessage());
        }

        saveToDisk(player.getUUID());
        syncToClient(player);
        success(player, "已放出: " + b.name + " @ " + origin.toShortString());
        PrefabCustomAddon.LOGGER.info("[CLOUD] 玩家 {} 放出建筑 {} @ {} facing={} (steps={}, {} 块, {} 个 TE)",
            player.getName().getString(), b.name, origin, placeFacing, steps, b.blocks.size(), b.tileEntities.size());
        return true;
    }

    /** 兼容旧调用: 无预览路径, 用 player.head 上方 1 格 + 原有 facing. */
    public boolean summon(ServerPlayer player, String buildingId) {
        return summon(player, buildingId, null, null);
    }

    // ============================================================
    // 实际操作方块
    // ============================================================

    /**
     * 清空区域内的方块, 并把 TileEntity 里的物品 drop 到玩家背包.
     * @return 实际收回的物品件数 (不包含空气方块)
     */
    private int clearBlocksAndCollectItems(ServerLevel level, CloudBuilding b, ServerPlayer player) {
        if (b.placedAt == null) return 0;
        // 收回时也要按放置时的朝向旋转, 找到正确的世界方块位置 (快照是原始 pos+state)
        int steps = CloudBuilding.facingToRotationSteps(b.facing);
        // flags 含义: 2 (UPDATE_CLIENTS) + 32 (UPDATE_SUPPRESS_DROPS) + 64 (UPDATE_MOVE_BY_PISTON)
        //   UPDATE_SUPPRESS_DROPS 让 95% 方块 (dirt/stone/planks/红石粉/...) 自身不生成 ItemEntity
        //   但部分方块 (sign / banner / bed / flower_pot / skull / lectern / conduit / hanging_sign)
        //   在 onRemove 里直接 popResource,完全无视这个 flag —— 这类走 SilentBuild 兜底拦截.
        int flags = Block.UPDATE_MOVE_BY_PISTON | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_CLIENTS;
        int itemCount = 0;
        int cleared = 0, skipped = 0, teCollected = 0;

        // 整段清空逻辑包在 SilentBuild.runSilent() 里.
        // 期间任何 ItemEntity 试图 addFreshEntity 都会被:
        //   1) UPDATE_SUPPRESS_DROPS (95% 方块)
        //   2) EntityJoinLevelEvent 拦截 (静默态取消 ItemEntity join)
        //   3) sweepRange 兜底扫荡 (清空结束后的 AABB)
        // 这三层防御后, 任何特殊方块都拦得住, 不会再把 dirt/stone 加进背包 (上次 bug).
        AtomicReference<Integer> itemCountRef = new AtomicReference<>(0);
        AtomicInteger clearedRef = new AtomicInteger(0);
        AtomicInteger teCollectedRef = new AtomicInteger(0);
        AtomicInteger skippedRef = new AtomicInteger(0);

        // 用于 sweepRange AABB: 旋转后的 (lx, lz) 范围
        // 用 int[] 包装, lambda 才能修改 (基本类型变量在 lambda 里必须是 final)
        final int[] rangeR = new int[] { Integer.MAX_VALUE, Integer.MIN_VALUE }; // [minRlx, maxRlx]
        final int[] rangeC = new int[] { Integer.MAX_VALUE, Integer.MIN_VALUE }; // [minRlz, maxRlz]

        CloudBuildingSilentClear.SilentBuild.runSilent(() -> {
            for (CloudBuilding.BlockSnapshot bs : b.blocks) {
                int plx = CloudBuilding.rotateLX(bs.lx, bs.lz, steps);
                int plz = CloudBuilding.rotateLZ(bs.lx, bs.lz, steps);
                if (plx < rangeR[0]) rangeR[0] = plx;
                if (plx > rangeR[1]) rangeR[1] = plx;
                if (plz < rangeC[0]) rangeC[0] = plz;
                if (plz > rangeC[1]) rangeC[1] = plz;
                BlockPos target = b.placedAt.offset(plx, bs.ly, plz);
                if (!level.isLoaded(target)) {
                    skippedRef.incrementAndGet();
                    continue;
                }
                // 先取 TE 里的物品 (不放成 ItemEntity, 直接塞玩家背包)
                BlockEntity te = level.getBlockEntity(target);
                if (te instanceof Container container) {
                    try {
                        for (int slot = 0; slot < container.getContainerSize(); slot++) {
                            ItemStack stack = container.getItem(slot);
                            if (stack.isEmpty()) continue;
                            itemCountRef.updateAndGet(v -> v + stack.getCount());
                            // 1.21.1: Inventory.placeItemBackInInventory(stack) — 背包满自动掉地上.
                            player.getInventory().placeItemBackInInventory(stack.copy());
                            container.setItem(slot, ItemStack.EMPTY);
                        }
                        container.setChanged();
                        teCollectedRef.incrementAndGet();
                    } catch (Throwable t) {
                        // 容错: TE 取物品失败就降级成 setBlock 时的原生掉落 (SilentBuild 会兜底拦)
                    }
                }
                // 移除 TE 后清方块
                if (te != null) {
                    level.removeBlockEntity(target);
                }
                if (level.setBlock(target, Blocks.AIR.defaultBlockState(), flags)) {
                    clearedRef.incrementAndGet();
                }
            }
        });

        cleared = clearedRef.get();
        skipped = skippedRef.get();
        teCollected = teCollectedRef.get();
        itemCount = itemCountRef.get();

        // 兜底扫荡: 任何漏网的 ItemEntity 全部 discard (用旋转后的范围)
        if (rangeR[0] != Integer.MAX_VALUE && rangeR[1] != Integer.MIN_VALUE) {
            int sx = rangeR[1] - rangeR[0] + 1;
            int sz = rangeC[1] - rangeC[0] + 1;
            int minOffX = Math.min(0, rangeR[0]);
            int minOffZ = Math.min(0, rangeC[0]);
            if (sx > 0 && sz > 0) {
                CloudBuildingSilentClear.SilentItemJoinListener.sweepRange(
                    level, b.placedAt.offset(minOffX, 0, minOffZ), sx, 256, sz);
            }
        }

        PrefabCustomAddon.LOGGER.info("[CLOUD-RECALL] 收回建筑 {} placedAt={} facing={}({} steps) 列表={} 块 实际清空={} 跳过={} TE收集={} 物品{}件",
            b.name, b.placedAt, b.facing, steps, b.blocks.size(), cleared, skipped, teCollected, itemCount);
        return itemCount;
    }

    /** 放置方块 + 恢复 TE. */
    private void placeBlocks(ServerLevel level, BlockPos origin, CloudBuilding b, int steps) {
        int flags = Block.UPDATE_MOVE_BY_PISTON | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_CLIENTS;
        for (CloudBuilding.BlockSnapshot bs : b.blocks) {
            int plx = CloudBuilding.rotateLX(bs.lx, bs.lz, steps);
            int plz = CloudBuilding.rotateLZ(bs.lx, bs.lz, steps);
            BlockPos target = origin.offset(plx, bs.ly, plz);
            BlockState state = bs.getState();
            if (state == null) continue;
            // state 也按 steps 旋转, 楼梯/栅栏/门/告示牌/漏斗/活塞/熔炉等 facing/axis 跟玩家预览一致
            if (steps != 0) {
                state = com.prefab.addon.structure.BlockStateRotator.rotateY(state, steps);
            }
            if (level.getBlockEntity(target) != null) {
                level.removeBlockEntity(target);
            }
            level.setBlock(target, state, flags);
        }
    }

    /** 恢复 TileEntity 内容 (1.21.1 用 BlockEntity.loadStatic(BlockPos, BlockState, CompoundTag, HolderLookup.Provider) 反序列化). */
    private void restoreTileEntities(ServerLevel level, BlockPos origin, CloudBuilding b, int steps) {
        if (b.tileEntities.isEmpty()) return;
        for (CloudBuilding.TileEntitySnapshot te : b.tileEntities) {
            int plx = CloudBuilding.rotateLX(te.lx, te.lz, steps);
            int plz = CloudBuilding.rotateLZ(te.lx, te.lz, steps);
            BlockPos target = origin.offset(plx, te.ly, plz);
            if (!level.isLoaded(target)) continue;
            if (te.nbt == null) continue;
            try {
                BlockState state = level.getBlockState(target);
                // 关键: TE 的 nbt 里 x/y/z 是绝对世界坐标, load() 会用它覆盖构造时的 pos.
                // 老存档里这值是建造时 (旋转后) 的世界坐标, 跟新放置位置不一致会导致
                // TE "瞬移" 到旧坐标或丢内容. 强制覆盖成新的 target.
                te.nbt.putInt("x", target.getX());
                te.nbt.putInt("y", target.getY());
                te.nbt.putInt("z", target.getZ());
                // 1.21.1: loadStatic 需要 HolderLookup.Provider
                BlockEntity loaded = BlockEntity.loadStatic(target, state, te.nbt, level.registryAccess());
                if (loaded != null) {
                    level.setBlockEntity(loaded);
                }
            } catch (Throwable t) {
                // 单个 TE 恢复失败不影响其他方块
                PrefabCustomAddon.LOGGER.warn("[CLOUD] 恢复 TE 失败 @ {}: {}",
                    target.toShortString(), t.getMessage());
            }
        }
    }

    // ============================================================
    // 网络同步
    // ============================================================

    /** 全量推给指定玩家. */
    public void syncToClient(ServerPlayer player) {
        if (player.level().isClientSide()) return;
        List<CloudBuilding> list = getAll(player.getUUID());
        List<CompoundTag> nbtList = new ArrayList<>(list.size());
        for (CloudBuilding b : list) nbtList.add(b.toNbt());
        PacketDistributor.sendToPlayer(player, new CloudBuildingSyncPayload(nbtList));
    }

    public void resyncClient(ServerPlayer player) {
        syncToClient(player);
    }

    // ============================================================
    // 工具: 给玩家发消息
    // ============================================================

    private static void info(ServerPlayer p, String msg) {
        p.sendSystemMessage(net.minecraft.network.chat.Component.literal("§e" + msg)
            .withStyle(net.minecraft.ChatFormatting.YELLOW));
    }
    private static void warn(ServerPlayer p, String msg) {
        p.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c" + msg)
            .withStyle(net.minecraft.ChatFormatting.RED));
    }
    private static void success(ServerPlayer p, String msg) {
        p.sendSystemMessage(net.minecraft.network.chat.Component.literal("§a✓ " + msg)
            .withStyle(net.minecraft.ChatFormatting.GREEN));
    }

    /** 单机/调试用: 直接拿服务端 (本类) 的所有 list, 不发包. */
    public List<CloudBuilding> getAllLocal(UUID playerUuid) {
        return getAll(playerUuid);
    }
}
