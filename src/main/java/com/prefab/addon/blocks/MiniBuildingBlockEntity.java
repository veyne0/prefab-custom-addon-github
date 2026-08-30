package com.prefab.addon.blocks;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.network.MiniBuildingFullDataPayload;
import com.prefab.addon.network.RequestMiniBuildingDataPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * 迷你建筑方块的 TileEntity: 存储建筑数据 + 缓存已解析的方块列表.
 *
 * <p>性能: BER 渲染时不应该每帧都重新解析 NBT ({@code getAllKeys + getCompound +
 * NbtUtils.readBlockState} 每个方块都要做). 改为 setData() 时标记 dirty, 第一次
 * render() 时解析一次缓存到 {@code cachedBlocks}, 后续帧直接用缓存.</p>
 */
public class MiniBuildingBlockEntity extends BlockEntity {

    private CompoundTag data = new CompoundTag();

    /** 缓存: 已解析的方块列表. dirty=true 时下次访问触发重解析. */
    private List<CachedBlockEntry> cachedBlocks = List.of();
    /**
     * 缓存: 已解析方块位置集合 (用于 BER 内部方块裁剪, 6 邻居判断).
     * 之前每帧在 BER.render 里重建 15777 个 long + HashSet, 极费 CPU.
     * 移到 rebuildCache 一次性建好, BER 直接 O(1) 查询.
     */
    private java.util.Set<Long> cachedPosSet = java.util.Collections.emptySet();
    private boolean dirty = true;
    private int cachedWidth, cachedHeight, cachedDepth;

    /**
     * 文件是否已加载 (true 表示 data 字段是完整 NBT, 含 blocks 列表).
     * 跟 dirty 的区别: dirty 表示需要重解析 cachedBlocks; fileLoaded
     * 表示 data 字段本身是不是已经从文件读到了完整 NBT.
     */
    private boolean fileLoaded = false;

    /**
     * 客户端: 是否已经向服务端请求过完整 NBT.
     * 防止 BER 每一帧都重复发请求 (虽然服务端会去重, 但省点带宽).
     */
    private boolean requestedFullData = false;

    /**
     * 数据版本号: 每次 this.data 被替换时递增.
     * 客户端 mesh 烘焙缓存 (MiniBuildingMeshCache) 用它判断是否需要重新烘焙.
     */
    private long dataVersion = 0;

    public long getDataVersion() {
        return dataVersion;
    }

    public MiniBuildingBlockEntity(BlockPos pos, BlockState state) {
        super(PrefabBlockEntities.MINI_BUILDING_BE.get(), pos, state);
    }

    public CompoundTag getData() {
        return data;
    }

    public void setData(CompoundTag data) {
        this.data = data;
        this.dirty = true;
        this.fileLoaded = false;  // 新数据, 强制重新读文件
        this.dataVersion++;
        setChanged();
    }

    /**
     * 返回已缓存的方块列表. 第一次调用时如果 dirty=true 会重新解析 NBT.
     * 后续帧直接返回缓存, O(1).
     *
     * <p><b>客户端 ref 处理</b>:
     * 客户端的 BE 只有引用 NBT (没 blocks 列表), 又没法自己读文件 (ClientLevel 没
     * server). 第一次发现这种情况, 向服务端发请求, 等待 {@link #onFullDataReceived}
     * 推送完整 NBT 后重渲染. requestedFullData 防止每帧重复发请求.</p>
     */
    public List<CachedBlockEntry> getCachedBlocks() {
        if (dirty) {
            // 1) 如果 data 是引用 NBT, 先尝试加载完整 data
            if (!fileLoaded && MiniBuildingStorage.isReference(data) && this.level != null) {
                if (this.level.isClientSide()) {
                    // 客户端: 没文件, 主动向服务端请求
                    if (!requestedFullData) {
                        requestedFullData = true;
                        // [DEBUG] 详细日志: 确认是 ref, 确认 isClientSide
                        PrefabCustomAddon.LOGGER.info(
                            "[MINI_BUILDING] [DEBUG] getCachedBlocks: BE @ {} isClientSide={}, data is ref (id={}), dispatching request to main thread",
                            this.worldPosition, this.level.isClientSide(),
                            data.getString(MiniBuildingStorage.KEY_REF_ID));
                        // [FIX] 不能在 render 线程直接发包 (BER.render 调到这里)
                        // 派发到主线程. execute() 是异步, 不会阻塞渲染.
                        net.minecraft.client.Minecraft.getInstance().execute(() -> {
                            try {
                                PacketDistributor.sendToServer(
                                    new RequestMiniBuildingDataPayload(this.worldPosition));
                                PrefabCustomAddon.LOGGER.info(
                                    "[MINI_BUILDING] Client: requested full data for BE @ {} (dispatched on main thread)",
                                    this.worldPosition);
                            } catch (Throwable t) {
                                PrefabCustomAddon.LOGGER.error(
                                    "[MINI_BUILDING] Client: sendToServer failed for BE @ {}",
                                    this.worldPosition, t);
                            }
                        });
                    }
                } else {
                    // 服务端: 直接从文件读 (单方块玩家自己的世界, 没问题)
                    String id = data.getString(MiniBuildingStorage.KEY_REF_ID);
                    CompoundTag full = MiniBuildingStorage.load(this.level, id);
                    if (full != null) {
                        this.data = full;
                        this.dataVersion++;
                        PrefabCustomAddon.LOGGER.info(
                            "[MINI_BUILDING] [DEBUG] getCachedBlocks: BE @ {} server-side lazy-loaded from file: {}",
                            this.worldPosition, id);
                    } else {
                        PrefabCustomAddon.LOGGER.warn(
                            "[MINI_BUILDING] BE @ {} 引用文件丢失: {} (跨世界? 删档?)",
                            this.worldPosition, id);
                    }
                    this.fileLoaded = true;
                }
            } else {
                // [DEBUG] 不是 ref 或 fileLoaded, 看 data 是什么状态
                PrefabCustomAddon.LOGGER.info(
                    "[MINI_BUILDING] [DEBUG] getCachedBlocks: BE @ {} skip lazy load: fileLoaded={}, isRef={}, dataKeys={}",
                    this.worldPosition, fileLoaded,
                    MiniBuildingStorage.isReference(data),
                    data.getAllKeys());
            }
            rebuildCache();
            // [DEBUG] 重建后的状态
            PrefabCustomAddon.LOGGER.info(
                "[MINI_BUILDING] [DEBUG] getCachedBlocks: BE @ {} after rebuild: size={}x{}x{}, blocks={}",
                this.worldPosition, cachedWidth, cachedHeight, cachedDepth, cachedBlocks.size());
        }
        return cachedBlocks;
    }

    /**
     * 服务端: 处理客户端发来的完整 NBT 请求. 从文件读出来, 走自定义包推回
     * (用 byte[] 编码, 绕过 2MB NbtAccounter).
     */
    public void handleClientDataRequest(ServerPlayer sp) {
        if (this.level == null || this.level.isClientSide()) return;
        // 1) 确保服务端这边 data 是完整的 (如果是 ref, 临时加载)
        CompoundTag full = this.data;
        if (MiniBuildingStorage.isReference(full)) {
            String id = full.getString(MiniBuildingStorage.KEY_REF_ID);
            CompoundTag loaded = MiniBuildingStorage.load(this.level, id);
            if (loaded != null) {
                full = loaded;
                this.data = loaded;  // 缓存到 BE, 后续直接用, 不再读文件
            } else {
                PrefabCustomAddon.LOGGER.warn(
                    "[MINI_BUILDING] Server: {} requested full data for {} but file missing (id={})",
                    sp.getName().getString(), this.worldPosition, id);
                return;
            }
        }
        // 2) 序列化成 byte[] (NbtIo.write, 跟 OperationWandScanResultPayload 一样)
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            NbtIo.write(full, dos);
            byte[] bytes = baos.toByteArray();
            PrefabCustomAddon.LOGGER.info(
                "[MINI_BUILDING] Server: sending full data to {} for {} ({} bytes, blocks={})",
                sp.getName().getString(), this.worldPosition, bytes.length,
                full.getList("blocks", net.minecraft.nbt.Tag.TAG_COMPOUND).size());
            PacketDistributor.sendToPlayer(sp,
                new MiniBuildingFullDataPayload(this.worldPosition, bytes));
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error(
                "[MINI_BUILDING] Server: failed to serialize full data for {}",
                this.worldPosition, e);
        }
    }

    /**
     * 客户端: 收到服务端推来的完整 NBT. 替换 ref, 触发重解析.
     */
    public void onFullDataReceived(CompoundTag full) {
        this.data = full;
        this.fileLoaded = true;   // 直接是完整的, 不需要再读文件
        this.dirty = true;        // 强制 rebuildCache
        this.dataVersion++;       // 客户端 mesh 缓存失效, 重新烘焙
        setChanged();
        // 注意: 不需要 broadcastChanges, 因为这是 client side BE, setChanged 即可
        // 但 worldPosition / blockState 没变, 渲染下一帧自然看到新 cachedBlocks
        PrefabCustomAddon.LOGGER.info(
            "[MINI_BUILDING] Client: full data installed for BE @ {} (blocks={})",
            this.worldPosition, full.getList("blocks", net.minecraft.nbt.Tag.TAG_COMPOUND).size());
    }

    /**
     * 导出给 ItemStack 的数据 (破坏掉落/中键取方块都用它): 只返回引用 NBT,
     * 绝不返回完整数据. 完整数据可能几十 MB, 写进 ItemStack 后随掉落物实体/容器同步,
     * 客户端解码时触发 2MB NbtAccounter 限制直接断开连接.
     *
     * @return {id: BE类型, MiniBuilding: ref} 格式 (跟捕获助手一致), 失败返回 null
     */
    @Nullable
    public CompoundTag toReferenceTag(net.minecraft.core.RegistryAccess registries) {
        CompoundTag ref = exportRefTag();
        if (ref == null) return null;
        // 顶层包一层: id = BE 类型 (1.21.1 ItemStack 反序列化 BLOCK_ENTITY_DATA 要求顶层有 id)
        CompoundTag beTag = new CompoundTag();
        beTag.putString("id", net.minecraft.world.level.block.entity.BlockEntityType.getKey(getType()).toString());
        beTag.put(MiniBuildingStorage.KEY_FULL_DATA, ref);
        return beTag;
    }

    /**
     * 核心: 保证 this.data 有对应的外部文件, 返回引用 NBT.
     * data 已是引用直接返回副本; 是完整数据则先落盘 (服务端) 再转引用并替换 this.data.
     * 客户端没文件能力, 但客户端 BE 的 data 只会是引用或完整数据 (内存), 完整数据时返回 null.
     */
    @Nullable
    private CompoundTag exportRefTag() {
        if (MiniBuildingStorage.isReference(this.data)) {
            return this.data.copy();
        }
        // data 是完整 NBT (服务端懒加载后 / 老存档)
        CompoundTag full = this.data;
        String id = full.getString("id");
        if (this.level == null || this.level.isClientSide()) {
            // 客户端: 完整数据只可能来自服务端推送 (文件在服务端必然存在), 直接构造 ref.
            // 这里覆盖创造模式 pick-block (客户端 BE 内存里是完整数据) 的路径.
            return id.isEmpty() ? null : buildRef(full, id);
        }
        // 服务端: 确保文件存在 (已有则不重写, 省 IO)
        if (id.isEmpty()) id = MiniBuildingStorage.newId();
        java.nio.file.Path dir = MiniBuildingStorage.getStorageDir(this.level);
        boolean fileExists = dir != null && java.nio.file.Files.exists(dir.resolve(id + ".nbt"));
        if (!fileExists && !MiniBuildingStorage.save(this.level, id, full)) {
            PrefabCustomAddon.LOGGER.error(
                "[MINI_BUILDING] exportRefTag: failed to persist data for BE @ {}", this.worldPosition);
            return null;
        }
        CompoundTag ref = buildRef(full, id);
        // BE 自己换成引用, 后续存档/同步都不再携带完整数据.
        // 注意: 不调 setChanged() —— 本方法可能在存档流程 (saveAdditional) 中调用,
        // 那里标脏会递归触发存档. rebuildCache 后缓存不受影响 (ref 有尺寸字段).
        this.data = ref;
        this.fileLoaded = true;
        this.dirty = true;
        this.dataVersion++;
        return ref;
    }

    /** 从完整/老数据构造引用 NBT (尺寸等元数据兼容新老字段名). */
    private static CompoundTag buildRef(CompoundTag full, String id) {
        CompoundTag ref = new CompoundTag();
        ref.putString(MiniBuildingStorage.KEY_REF_ID, id);
        ref.putInt(MiniBuildingStorage.KEY_REF_WIDTH, firstNonZero(full.getInt("width"), full.getInt(MiniBuildingStorage.KEY_REF_WIDTH)));
        ref.putInt(MiniBuildingStorage.KEY_REF_HEIGHT, firstNonZero(full.getInt("height"), full.getInt(MiniBuildingStorage.KEY_REF_HEIGHT)));
        ref.putInt(MiniBuildingStorage.KEY_REF_DEPTH, firstNonZero(full.getInt("depth"), full.getInt(MiniBuildingStorage.KEY_REF_DEPTH)));
        ref.putInt(MiniBuildingStorage.KEY_REF_BLOCK_COUNT, firstNonZero(full.getInt("block_count"), full.getInt(MiniBuildingStorage.KEY_REF_BLOCK_COUNT)));
        String author = full.getString("author");
        if (author.isEmpty()) author = full.getString(MiniBuildingStorage.KEY_REF_AUTHOR);
        ref.putString(MiniBuildingStorage.KEY_REF_AUTHOR, author);
        long created = full.getLong("created");
        if (created == 0) created = full.getLong(MiniBuildingStorage.KEY_REF_CREATED);
        ref.putLong(MiniBuildingStorage.KEY_REF_CREATED, created);
        return ref;
    }

    private static int firstNonZero(int a, int b) {
        return a != 0 ? a : b;
    }

    public int getWidth() {
        if (dirty) rebuildCache();
        return cachedWidth;
    }

    public int getHeight() {
        if (dirty) rebuildCache();
        return cachedHeight;
    }

    public int getDepth() {
        if (dirty) rebuildCache();
        return cachedDepth;
    }

    // [DEBUG] 给 BER 用, 看 rebuildCache 后的原始值, 不再触发 rebuild
    public int getCachedWidth_DEBUG() { return cachedWidth; }
    public int getCachedHeight_DEBUG() { return cachedHeight; }
    public int getCachedDepth_DEBUG() { return cachedDepth; }

    private void rebuildCache() {
        // [FIX] 在 rebuildCache 顶部尝试 ref → full 加载.
        //   之前在 getCachedBlocks 里做这个, 但 getWidth/Height/Depth 先调了 rebuildCache
        //   (dirty=true → rebuild → dirty=false), 之后 getCachedBlocks 看到 dirty=false
        //   就直接 return cachedBlocks, ref 检测 + dispatch 请求逻辑被跳过了.
        //   移到 rebuildCache 后, getWidth/Height/Depth/getCachedBlocks 都会触发.
        if (!fileLoaded && MiniBuildingStorage.isReference(data) && this.level != null) {
            if (this.level.isClientSide()) {
                // 客户端: 发请求 (派发到主线程, 避免 render 线程发包被丢弃)
                if (!requestedFullData) {
                    requestedFullData = true;
                    final BlockPos pos = this.worldPosition;
                    PrefabCustomAddon.LOGGER.info(
                        "[MINI_BUILDING] [DEBUG] rebuildCache: client BE @ {} is ref, dispatching request",
                        pos);
                    try {
                        net.minecraft.client.Minecraft.getInstance().execute(() -> {
                            try {
                                PacketDistributor.sendToServer(
                                    new RequestMiniBuildingDataPayload(pos));
                                PrefabCustomAddon.LOGGER.info(
                                    "[MINI_BUILDING] Client: requested full data for BE @ {} (dispatched)",
                                    pos);
                            } catch (Throwable t) {
                                PrefabCustomAddon.LOGGER.error(
                                    "[MINI_BUILDING] Client: sendToServer failed for BE @ {}",
                                    pos, t);
                            }
                        });
                    } catch (Throwable t) {
                        PrefabCustomAddon.LOGGER.error(
                            "[MINI_BUILDING] Client: dispatch failed for BE @ {}",
                            pos, t);
                    }
                }
            } else {
                // 服务端: 直接从文件读
                String id = data.getString(MiniBuildingStorage.KEY_REF_ID);
                CompoundTag full = MiniBuildingStorage.load(this.level, id);
                if (full != null) {
                    this.data = full;
                    this.dataVersion++;
                    PrefabCustomAddon.LOGGER.info(
                        "[MINI_BUILDING] [DEBUG] rebuildCache: server BE @ {} lazy-loaded from file: {}",
                        this.worldPosition, id);
                } else {
                    PrefabCustomAddon.LOGGER.warn(
                        "[MINI_BUILDING] BE @ {} 引用文件丢失: {}",
                        this.worldPosition, id);
                }
                this.fileLoaded = true;
            }
        }

        cachedWidth = data.getInt("width");
        cachedHeight = data.getInt("height");
        cachedDepth = data.getInt("depth");
        // 引用 NBT fallback (客户端在收到 full NBT 之前能正确显示 size, 不然 BER 早退)
        if (cachedWidth == 0) cachedWidth = data.getInt(MiniBuildingStorage.KEY_REF_WIDTH);
        if (cachedHeight == 0) cachedHeight = data.getInt(MiniBuildingStorage.KEY_REF_HEIGHT);
        if (cachedDepth == 0) cachedDepth = data.getInt(MiniBuildingStorage.KEY_REF_DEPTH);
        // blocks 现在是 ListTag<CompoundTag>, 跟 vanilla NBT 格式保持一致
        // (v1.9 之前是 CompoundTag+String key, 改成 ListTag 节省 ~30% NBT 体积)
        net.minecraft.nbt.ListTag blocksList = data.getList("blocks", net.minecraft.nbt.Tag.TAG_COMPOUND);
        java.util.ArrayList<CachedBlockEntry> list = new java.util.ArrayList<>(blocksList.size());
        for (int i = 0; i < blocksList.size(); i++) {
            try {
                CompoundTag entry = blocksList.getCompound(i);
                int bx = entry.getInt("x");
                int by = entry.getInt("y");
                int bz = entry.getInt("z");
                CompoundTag stateTag = entry.getCompound("state");
                BlockState state = com.prefab.addon.cloud.CloudBuilding.readBlockState(stateTag);
                if (state == null || state.isAir()) continue;
                list.add(new CachedBlockEntry(bx, by, bz, state));
            } catch (Throwable t) {
                // 单个方块解析失败不影响整体
            }
        }
        cachedBlocks = list;
        // [PERF] 顺便建好位置集合, BER 不再每帧重建
        java.util.HashSet<Long> posSet = new java.util.HashSet<>(list.size() * 2);
        for (CachedBlockEntry e : list) {
            posSet.add(packPos(e.x(), e.y(), e.z()));
        }
        cachedPosSet = posSet;
        dirty = false;
        // [DEBUG] 每次 rebuild 后打日志, 方便追踪状态变化
        PrefabCustomAddon.LOGGER.info(
            "[MINI_BUILDING] [DEBUG] rebuildCache @ {} done: size={}x{}x{}, blocks={}, posSetSize={}, dataKeys={}, isRef={}, fileLoaded={}",
            this.worldPosition, cachedWidth, cachedHeight, cachedDepth, cachedBlocks.size(),
            cachedPosSet.size(),
            data.getAllKeys(), MiniBuildingStorage.isReference(data), fileLoaded);
    }

    /**
     * 暴露给 BER 的位置集合 (用于内部方块裁剪, O(1) 6 邻居查询).
     * BE rebuildCache 时一次性建好, BER 每帧直接读.
     */
    public java.util.Set<Long> getCachedPosSet() {
        if (dirty) {
            // 触发 rebuild
            getCachedBlocks();
        }
        return cachedPosSet;
    }

    /** 简单的 long 编码 (x, y, z). 建筑尺寸最大 128, 16bit 足够. */
    private static long packPos(int x, int y, int z) {
        return ((long) (x & 0xFFFF) << 32) | ((long) (y & 0xFFFF) << 16) | (long) (z & 0xFFFF);
    }

    /**
     * 公开给 BER 用的 pack 函数, 跟 BE 内部 cachedPosSet 用同一个编码.
     * BER 做 6 邻居裁剪时调这个, 跟 set 里的 key 匹配.
     */
    public static long packPosExternal(int x, int y, int z) {
        return ((long) (x & 0xFFFF) << 32) | ((long) (y & 0xFFFF) << 16) | (long) (z & 0xFFFF);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // [DEBUG] 详细日志: 看实际传给 BE 的 NBT 是什么
        PrefabCustomAddon.LOGGER.info(
            "[MINI_BUILDING] [DEBUG] loadAdditional @ {}: tagKeys={}, tag={}",
            this.worldPosition, tag.getAllKeys(), tag);
        if (tag.contains("MiniBuilding")) {
            this.data = tag.getCompound("MiniBuilding");
            PrefabCustomAddon.LOGGER.info(
                "[MINI_BUILDING] [DEBUG] loadAdditional @ {}: extracted MiniBuilding data, keys={}, "
                + "isRef={}, hasBlocks={}",
                this.worldPosition, this.data.getAllKeys(),
                MiniBuildingStorage.isReference(this.data),
                this.data.contains("blocks"));
            // 重要: this.data 现在可能只是"引用 NBT" (有 ref_id 没 blocks)
            // 真正加载完整数据在 getCachedBlocks() 时按需读文件
            this.dirty = true;
            this.fileLoaded = false;  // 强制首次重新读文件
            this.dataVersion++;
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        // 只写引用: 本方法同时服务于区块存档 和 创造模式 pick-block (Ctrl+中键).
        // pick-block 路径 (Minecraft.addCustomNbtData → saveCustomAndMetadata) 会把这里的输出直接写进玩家物品,
        // 完整数据 (可能几十 MB) 会随容器同步触发客户端 2MB NbtAccounter 断线.
        // 完整数据已在外部文件 (懒加载时读的), 存档里留引用即可.
        CompoundTag ref = exportRefTag();
        if (ref != null) {
            tag.put("MiniBuilding", ref);
        } else if (this.level == null || this.level.isClientSide()) {
            // 客户端导出失败: 绝不写完整数据 (会随物品同步, > 2MB 直接断线). 宁掉数据不断线.
            tag.put("MiniBuilding", new CompoundTag());
        } else {
            // 服务端落盘失败: 退回写完整数据进区块 (区块存档无大小限制, 不丢数据)
            tag.put("MiniBuilding", data.copy());
        }
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        // 关键: 这里返回的 NBT 是给客户端同步用的, 必须保持 < 2MB
        // 客户端的 NbtAccounter 默认 2MB, 超过就断开
        // 所以即使 data 包含完整数据, 也只发引用 (< 1KB)
        CompoundTag tag = super.getUpdateTag(registries);
        if (data != null && MiniBuildingStorage.isReference(data)) {
            tag.put("MiniBuilding", data.copy());  // ref only, ~200 bytes
        } else if (data != null) {
            // data 是完整 NBT (老存档兼容), 仍然只发引用大小 < 2MB
            // 这种情况只发生在 1.9 之前捕获的建筑, 新版本不会
            CompoundTag ref = new CompoundTag();
            String id = data.getString("id");
            if (!id.isEmpty()) {
                ref.putString(MiniBuildingStorage.KEY_REF_ID, id);
                ref.putInt(MiniBuildingStorage.KEY_REF_WIDTH, data.getInt("width"));
                ref.putInt(MiniBuildingStorage.KEY_REF_HEIGHT, data.getInt("height"));
                ref.putInt(MiniBuildingStorage.KEY_REF_DEPTH, data.getInt("depth"));
                ref.putInt(MiniBuildingStorage.KEY_REF_BLOCK_COUNT, data.getInt("block_count"));
                ref.putString(MiniBuildingStorage.KEY_REF_AUTHOR, data.getString("author"));
                ref.putLong(MiniBuildingStorage.KEY_REF_CREATED, data.getLong("created"));
                tag.put("MiniBuilding", ref);
            } else {
                // 没有 id, 老存档无法恢复, 发空引用
                tag.put("MiniBuilding", new CompoundTag());
            }
        }
        return tag;
    }

    /** 已解析的方块项 (pos + state), record 简洁. */
    public record CachedBlockEntry(int x, int y, int z, BlockState state) {}
}
