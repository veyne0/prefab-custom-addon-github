package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;

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
) {

    public static void encode(BuildingUploadAckPayload msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.fileName());
        buf.writeVarLong(msg.received());
        buf.writeBoolean(msg.finished());
        buf.writeBoolean(msg.ok());
        buf.writeUtf(msg.message());
    }

    public static BuildingUploadAckPayload decode(FriendlyByteBuf buf) {
        return new BuildingUploadAckPayload(buf.readUtf(), buf.readVarLong(),
                buf.readBoolean(), buf.readBoolean(), buf.readUtf());
    }
}
