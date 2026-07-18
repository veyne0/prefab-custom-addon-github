package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：玩家手动请求重新拉取 manifest（不重连服务器）。
 * 服务端收到后用 ServerPackManifestPayload 重新回应。
 */
public record RequestServerPackManifestPayload() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RequestServerPackManifestPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("prefab_custom_addon", "request_server_pack_manifest"));

    public static final StreamCodec<FriendlyByteBuf, RequestServerPackManifestPayload> STREAM_CODEC =
            StreamCodec.unit(new RequestServerPackManifestPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
