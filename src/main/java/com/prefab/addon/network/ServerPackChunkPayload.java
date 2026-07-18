package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端：一个 zip 的一个分片。
 *
 * 字段：
 *   - packName:    拓展包 packageName
 *   - offset:      本分片在 zip 里的起始字节偏移
 *   - totalSize:   zip 完整大小（最后一个分片用得上，用于客户端判断收完）
 *   - data:        本分片的字节数据（最大约 60 KiB）
 *
 * 客户端按 packName 维护 ByteArrayOutputStream，按 offset 写入。
 */
public record ServerPackChunkPayload(
        String packName,
        long offset,
        long totalSize,
        byte[] data
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ServerPackChunkPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("prefab_custom_addon", "server_pack_chunk"));

    public static final StreamCodec<FriendlyByteBuf, ServerPackChunkPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ServerPackChunkPayload::packName,
                    ByteBufCodecs.VAR_LONG,    ServerPackChunkPayload::offset,
                    ByteBufCodecs.VAR_LONG,    ServerPackChunkPayload::totalSize,
                    ByteBufCodecs.byteArray(60 * 1024), ServerPackChunkPayload::data,
                    ServerPackChunkPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
