package com.prefab.addon.client;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.network.MiniBuildingItemDataRequestPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端: 按 ref_id 缓存迷你建筑完整 NBT.
 *
 * <p><b>为什么需要</b>: 迷你建筑物品 (ItemStack) 只存引用 NBT (&lt; 1KB, 绕开
 * 客户端 2MB NbtAccounter), 物品栏渲染 ({@link MiniBuildingItemRenderer}) 需要完整
 * blocks 列表才能画出微缩模型. 物品没有 BlockPos, 不能走放置方块的
 * {@code RequestMiniBuildingDataPayload} (按位置找 BE), 所以按 ref_id 单独请求.</p>
 *
 * <p><b>流程</b>:
 * <ol>
 *   <li>渲染器发现物品是引用且缓存没数据 → {@link #requestIfNeeded} (10s 节流, 派发到主线程发包)</li>
 *   <li>服务端 {@code MiniBuildingItemDataRequestPayload.handle} 从外部文件读出完整 NBT</li>
 *   <li>服务端走 {@code MiniBuildingItemDataResponsePayload} (byte[] 编码) 推回</li>
 *   <li>客户端 {@link #put} 存缓存 + 让物品渲染器缓存失效, 下一帧渲染出来</li>
 * </ol>
 */
public final class MiniBuildingItemDataCache {

    /** ref_id → 完整建筑 NBT. 渲染线程读, 网络线程 (enqueueWork→主线程) 写. */
    private static final Map<String, CompoundTag> DATA = new ConcurrentHashMap<>();
    /** ref_id → 上次发请求的时间戳 (节流, 防止文件丢失时每帧刷请求). */
    private static final Map<String, Long> LAST_REQUEST_MS = new ConcurrentHashMap<>();
    /** 同一个 id 两次请求的最小间隔. 文件丢失时 10 秒重试一次. */
    private static final long REQUEST_INTERVAL_MS = 10_000L;
    /** 缓存上限, 超过时整个清空 (建筑物品数据体积大, 不做精细 LRU). */
    private static final int MAX_ENTRIES = 256;

    private MiniBuildingItemDataCache() {}

    public static CompoundTag get(String id) {
        return id == null ? null : DATA.get(id);
    }

    public static void put(String id, CompoundTag data) {
        if (id == null || id.isEmpty() || data == null) return;
        if (DATA.size() >= MAX_ENTRIES) {
            DATA.clear();
            LAST_REQUEST_MS.clear();
        }
        DATA.put(id, data);
    }

    /** 切世界/退出时清理 (避免跨世界残留别人的建筑数据). */
    public static void clear() {
        DATA.clear();
        LAST_REQUEST_MS.clear();
    }

    /**
     * 缓存未命中时向服务端请求完整数据.
     *
     * <p>渲染线程调用安全: 发包动作派发到主线程 (跟
     * {@code MiniBuildingBlockEntity.rebuildCache} 的既有模式一致).
     * 节流保证同一个 id 不会每帧都发包.</p>
     */
    public static void requestIfNeeded(String id) {
        if (id == null || id.isEmpty() || DATA.containsKey(id)) return;
        long now = System.currentTimeMillis();
        Long last = LAST_REQUEST_MS.get(id);
        if (last != null && now - last < REQUEST_INTERVAL_MS) return;
        LAST_REQUEST_MS.put(id, now);
        try {
            Minecraft.getInstance().execute(() -> {
                try {
                    PacketDistributor.sendToServer(new MiniBuildingItemDataRequestPayload(id));
                    PrefabCustomAddon.LOGGER.info(
                        "[MINI_BUILDING] Item render: requested full data for ref_id={}", id);
                } catch (Throwable t) {
                    PrefabCustomAddon.LOGGER.error(
                        "[MINI_BUILDING] Item render: sendToServer failed for ref_id={}", id, t);
                }
            });
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error(
                "[MINI_BUILDING] Item render: dispatch failed for ref_id={}", id, t);
        }
    }
}
