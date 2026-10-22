package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;

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
) {

    public static void encode(BuildingUploadChunkPayload msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.fileName());
        buf.writeVarLong(msg.offset());
        buf.writeByteArray(msg.data());
    }

    public static BuildingUploadChunkPayload decode(FriendlyByteBuf buf) {
        return new BuildingUploadChunkPayload(buf.readUtf(), buf.readVarLong(), buf.readByteArray());
    }
}
