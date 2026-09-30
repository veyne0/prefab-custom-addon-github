package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 客户端 → 服务端：确认收到一个分片，请求发送下一个。
 * 也用于"接收完成"信号（done = true）。
 * 服务端按 packName 维护每个玩家的发送游标，收到 ACK 后推进游标并发下一片；
 * ACK done=true 后切到下一个 pack。
 */
public record ServerPackChunkAckPayload(
        String packName,
        long nextOffset,  // 期望的下一个 offset（= 已收到的字节数）
        boolean done      // 客户端已收到完整 zip
) {

    public static void encode(ServerPackChunkAckPayload msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.packName());
        buf.writeVarLong(msg.nextOffset());
        buf.writeBoolean(msg.done());
    }

    public static ServerPackChunkAckPayload decode(FriendlyByteBuf buf) {
        return new ServerPackChunkAckPayload(buf.readUtf(), buf.readVarLong(), buf.readBoolean());
    }
}