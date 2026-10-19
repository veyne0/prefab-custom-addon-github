package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：上传的一个分片 (最大 32 KiB, 见 BuildingUploadClient.CHUNK_SIZE)。
 *
 * 字段：
 *   - fileName: 目标文件名 (会话 key)
 *   - offset:   本分片在 zip 里的起始字节偏移
 *   - data:     本分片字节数据
 *
 * 服务端校验 offset == 已收字节数, 不匹配时回 ACK 让客户端按服务端游标重发。
 */
public record BuildingUploadChunkPayload(
        String fileName,
        long offset,
        byte[] data
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BuildingUploadChunkPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("prefab_custom_addon", "building_upload_chunk"));

    public static final StreamCodec<FriendlyByteBuf, BuildingUploadChunkPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, BuildingUploadChunkPayload::fileName,
                    ByteBufCodecs.VAR_LONG,    BuildingUploadChunkPayload::offset,
                    ByteBufCodecs.byteArray(32 * 1024), BuildingUploadChunkPayload::data,
                    BuildingUploadChunkPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
