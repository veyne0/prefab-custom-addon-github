package com.prefab.addon.cloud;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端: 请求收回指定云端建筑 (清世界方块, placed=false).
 */
public record CloudBuildingRecallPayload(String buildingId)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CloudBuildingRecallPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("prefab_custom_addon", "cloud_recall"));

    public static final StreamCodec<FriendlyByteBuf, CloudBuildingRecallPayload> STREAM_CODEC =
            StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, CloudBuildingRecallPayload::buildingId,
                CloudBuildingRecallPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
