package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 客户端 → 服务端：客户端声明自己需要哪些 zip。
 * 通常只发本地缓存没有（或 SHA-1 不匹配）的包名。
 */
public record RequestServerPacksPayload(
        List<String> packNames
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RequestServerPacksPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("prefab_custom_addon", "request_server_packs"));

    public static final StreamCodec<FriendlyByteBuf, RequestServerPacksPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), RequestServerPacksPayload::packNames,
                    RequestServerPacksPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
