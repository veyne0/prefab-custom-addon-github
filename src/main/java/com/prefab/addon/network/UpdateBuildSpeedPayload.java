package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 客户端 → 服务端: 申请修改全局建造放置速度 (buildBatchPercent).
 * 服务端校验 OP 权限, 持久化后通过 {@link SyncBuildSpeedPayload} 广播给所有在线玩家.
 */
public record UpdateBuildSpeedPayload(int percent) {

    public static void encode(UpdateBuildSpeedPayload msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.percent());
    }

    public static UpdateBuildSpeedPayload decode(FriendlyByteBuf buf) {
        return new UpdateBuildSpeedPayload(buf.readVarInt());
    }
}
