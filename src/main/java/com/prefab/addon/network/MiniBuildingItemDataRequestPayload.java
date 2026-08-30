package com.prefab.addon.network;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.blocks.MiniBuildingStorage;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 客户端 → 服务端: 按 ref_id 请求迷你建筑完整 NBT (物品栏渲染用).
 *
 * <p><b>跟 {@link RequestMiniBuildingDataPayload} 的区别</b>: 那个按 BlockPos 找
 * <b>已放置</b>的 BE; 物品栏里的迷你建筑物品没有位置, 只能按引用 UUID 请求.
 * 服务端从世界目录外部文件 ({@code <world>/data/prefab_custom_addon/mini_buildings/})
 * 读出完整 NBT, 走 {@link MiniBuildingItemDataResponsePayload} (byte[] 编码, 绕过
 * 客户端 2MB NbtAccounter) 推回.</p>
 */
public record MiniBuildingItemDataRequestPayload(String refId)
    implements CustomPacketPayload {

    public static final Type<MiniBuildingItemDataRequestPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(PrefabCustomAddon.MOD_ID, "mini_building_item_data_req"));

    public static final StreamCodec<ByteBuf, MiniBuildingItemDataRequestPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, MiniBuildingItemDataRequestPayload::refId,
            MiniBuildingItemDataRequestPayload::new
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(final MiniBuildingItemDataRequestPayload payload, final IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        ctx.enqueueWork(() -> {
            String id = payload.refId();
            if (id == null || id.isEmpty()) return;
            CompoundTag full = MiniBuildingStorage.load(sp.serverLevel(), id);
            if (full == null) {
                PrefabCustomAddon.LOGGER.warn(
                    "[MINI_BUILDING] Server: {} requested item data for {} but file missing (跨世界/文件被删?)",
                    sp.getName().getString(), id);
                return;
            }
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                 DataOutputStream dos = new DataOutputStream(baos)) {
                NbtIo.write(full, dos);
                byte[] bytes = baos.toByteArray();
                PrefabCustomAddon.LOGGER.info(
                    "[MINI_BUILDING] Server: sending item data to {} for ref_id={} ({} bytes)",
                    sp.getName().getString(), id, bytes.length);
                PacketDistributor.sendToPlayer(sp,
                    new MiniBuildingItemDataResponsePayload(id, bytes));
            } catch (IOException e) {
                PrefabCustomAddon.LOGGER.error(
                    "[MINI_BUILDING] Server: failed to serialize item data for ref_id={}", id, e);
            }
        });
    }
}
