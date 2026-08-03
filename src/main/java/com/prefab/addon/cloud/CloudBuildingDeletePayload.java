package com.prefab.addon.cloud;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端: 请求删除指定云端建筑 (从云端抹掉, 不影响世界已放出的方块).
 *
 * <p>调用方在发包前应该已经确认: 该建筑 !b.placed (已经收回, 不会在世界里留下孤儿方块).
 * 如果 placed=true, 服务端会拒绝删除并提示玩家先收回.</p>
 */
public record CloudBuildingDeletePayload(String buildingId)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CloudBuildingDeletePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("prefab_custom_addon", "cloud_delete"));

    public static final StreamCodec<FriendlyByteBuf, CloudBuildingDeletePayload> STREAM_CODEC =
            StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, CloudBuildingDeletePayload::buildingId,
                CloudBuildingDeletePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
