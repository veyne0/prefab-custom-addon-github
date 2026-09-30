package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 客户端 → 服务端：玩家手动请求重新拉取 manifest（不重连服务器）。
 * 服务端收到后用 ServerPackManifestPayload 重新回应。
 */
public record RequestServerPackManifestPayload() {

    public static void encode(RequestServerPackManifestPayload msg, FriendlyByteBuf buf) {
        // 空包, 无字段
    }

    public static RequestServerPackManifestPayload decode(FriendlyByteBuf buf) {
        return new RequestServerPackManifestPayload();
    }
}