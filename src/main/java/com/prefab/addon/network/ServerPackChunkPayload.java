package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 服务端 → 客户端：一个 zip 的一个分片。
 * 字段: packName / offset(分片起始字节偏移) / totalSize(zip 完整大小) / data(本分片字节, 最大约 60 KiB).
 * 客户端按 packName 维护 ByteArrayOutputStream，按 offset 写入。
 */
public record ServerPackChunkPayload(
        String packName,
        long offset,
        long totalSize,
        byte[] data
) {

    public static void encode(ServerPackChunkPayload msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.packName());
        buf.writeVarLong(msg.offset());
        buf.writeVarLong(msg.totalSize());
        buf.writeByteArray(msg.data());
    }

    public static ServerPackChunkPayload decode(FriendlyByteBuf buf) {
        return new ServerPackChunkPayload(buf.readUtf(), buf.readVarLong(), buf.readVarLong(), buf.readByteArray());
    }
}