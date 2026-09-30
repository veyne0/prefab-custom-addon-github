package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端 → 服务端：客户端声明自己需要哪些 zip。
 * 通常只发本地缓存没有（或 SHA-1 不匹配）的包名。
 */
public record RequestServerPacksPayload(
        List<String> packNames
) {

    public static void encode(RequestServerPacksPayload msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.packNames().size());
        for (String s : msg.packNames()) buf.writeUtf(s);
    }

    public static RequestServerPacksPayload decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(buf.readUtf());
        return new RequestServerPacksPayload(list);
    }
}