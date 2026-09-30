package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 服务端 → 客户端: 广播当前的全局建造放置速度 (buildBatchPercent).
 * OP 修改后广播给所有在线玩家; 玩家进服时单独发一份快照.
 * 客户端收到后写入 PlayerPreferences.buildBatchPercent, 仅供 SettingsGui 显示.
 */
public record SyncBuildSpeedPayload(int percent) {

    public static void encode(SyncBuildSpeedPayload msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.percent());
    }

    public static SyncBuildSpeedPayload decode(FriendlyByteBuf buf) {
        return new SyncBuildSpeedPayload(buf.readVarInt());
    }
}
