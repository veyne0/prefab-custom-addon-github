package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 客户端 → 服务端：分片全部发完, 请求服务端收尾。
 *
 * 服务端流程: 校验字节数与 SHA-1 → 写入 server-cache/&lt;fileName&gt; → forceReload 重扫
 * → 给所有在线玩家重发 manifest → 回 BuildingUploadAckPayload(finished=true)。
 */
public record BuildingUploadEndPayload(String fileName) {

    public static void encode(BuildingUploadEndPayload msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.fileName());
    }

    public static BuildingUploadEndPayload decode(FriendlyByteBuf buf) {
        return new BuildingUploadEndPayload(buf.readUtf());
    }
}
