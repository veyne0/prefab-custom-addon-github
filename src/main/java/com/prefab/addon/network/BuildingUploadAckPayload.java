package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端：上传进度 ACK / 最终结果。
 *
 * 字段：
 *   - fileName: 会话 key (目标文件名)
 *   - received: finished=false 时 = 服务端已收字节数 (客户端用它做流控, 只发这个偏移之后的分片)
 *   - finished: true = 传输结束 (此时看 ok / message)
 *   - ok:       最终是否成功
 *   - message:  给玩家看的结果描述 (已 tr 本地化, 服务端 tr 发过来)
 */
public record BuildingUploadAckPayload(
        String fileName,
        long received,
        boolean finished,
        boolean ok,
        String message
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BuildingUploadAckPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("prefab_custom_addon", "building_upload_ack"));

    public static final StreamCodec<FriendlyByteBuf, BuildingUploadAckPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, BuildingUploadAckPayload::fileName,
                    ByteBufCodecs.VAR_LONG,    BuildingUploadAckPayload::received,
                    ByteBufCodecs.BOOL,        BuildingUploadAckPayload::finished,
                    ByteBufCodecs.BOOL,        BuildingUploadAckPayload::ok,
                    ByteBufCodecs.STRING_UTF8, BuildingUploadAckPayload::message,
                    BuildingUploadAckPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
