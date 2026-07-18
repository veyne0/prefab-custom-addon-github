package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：确认收到一个分片，请求发送下一个。
 * 也用于"接收完成"信号（done = true）。
 *
 * 服务端按 packName 维护每个玩家的发送游标，收到 ACK 后推进游标并发下一片；
 * ACK done=true 后切到下一个 pack。
 */
public record ServerPackChunkAckPayload(
        String packName,
        long nextOffset,  // 期望的下一个 offset（= 已收到的字节数）
        boolean done      // 客户端已收到完整 zip
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ServerPackChunkAckPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("prefab_custom_addon", "server_pack_chunk_ack"));

    public static final StreamCodec<FriendlyByteBuf, ServerPackChunkAckPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ServerPackChunkAckPayload::packName,
                    ByteBufCodecs.VAR_LONG,    ServerPackChunkAckPayload::nextOffset,
                    ByteBufCodecs.BOOL,        ServerPackChunkAckPayload::done,
                    ServerPackChunkAckPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
