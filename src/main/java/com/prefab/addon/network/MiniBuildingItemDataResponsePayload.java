package com.prefab.addon.network;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.client.MiniBuildingItemDataCache;
import com.prefab.addon.client.MiniBuildingItemRenderer;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

/**
 * 服务端 → 客户端: 按 ref_id 推送迷你建筑完整 NBT (物品栏渲染用).
 *
 * <p>{@code nbtData} 是 {@link NbtIo#write} 出来的二进制 (无压缩), 跟
 * {@link MiniBuildingFullDataPayload} 一样用 byte[] 走 {@link ByteBuf}, 完全绕过
 * 客户端 {@code NbtAccounter} 的 2MB 上限. 客户端存入
 * {@link MiniBuildingItemDataCache}, 并让 {@link MiniBuildingItemRenderer} 的
 * 物品级缓存失效, 下一帧即可画出微缩模型.</p>
 */
public record MiniBuildingItemDataResponsePayload(String refId, byte[] nbtData)
    implements CustomPacketPayload {

    public static final Type<MiniBuildingItemDataResponsePayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(PrefabCustomAddon.MOD_ID, "mini_building_item_data"));

    public static final StreamCodec<ByteBuf, MiniBuildingItemDataResponsePayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, MiniBuildingItemDataResponsePayload::refId,
            ByteBufCodecs.BYTE_ARRAY, MiniBuildingItemDataResponsePayload::nbtData,
            MiniBuildingItemDataResponsePayload::new
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(final MiniBuildingItemDataResponsePayload payload, final IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            String id = payload.refId();
            try (ByteArrayInputStream bais = new ByteArrayInputStream(payload.nbtData());
                 DataInputStream dis = new DataInputStream(bais)) {
                CompoundTag full = NbtIo.read(dis);
                if (full == null) {
                    PrefabCustomAddon.LOGGER.warn(
                        "[MINI_BUILDING] Client: got empty item data for ref_id={}", id);
                    return;
                }
                MiniBuildingItemDataCache.put(id, full);
                // 让物品渲染缓存失效, 下一帧重新解析 (否则命中"空结果"缓存永远不画)
                MiniBuildingItemRenderer.invalidateAll();
                PrefabCustomAddon.LOGGER.info(
                    "[MINI_BUILDING] Client: item data cached for ref_id={} ({} bytes)",
                    id, payload.nbtData().length);
            } catch (IOException e) {
                PrefabCustomAddon.LOGGER.error(
                    "[MINI_BUILDING] Client: failed to parse item data for ref_id={}", id, e);
            }
        });
    }
}
