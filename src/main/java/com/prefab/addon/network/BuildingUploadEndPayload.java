package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：分片全部发完, 请求服务端收尾。
 *
 * 服务端流程: 校验字节数与 SHA-1 → 写入 server-cache/&lt;fileName&gt; → forceReload 重扫
 * → 给所有在线玩家重发 manifest → 回 BuildingUploadAckPayload(finished=true)。
 */
public record BuildingUploadEndPayload(
        String fileName
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BuildingUploadEndPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("prefab_custom_addon", "building_upload_end"));

    public static final StreamCodec<FriendlyByteBuf, BuildingUploadEndPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, BuildingUploadEndPayload::fileName,
                    BuildingUploadEndPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
