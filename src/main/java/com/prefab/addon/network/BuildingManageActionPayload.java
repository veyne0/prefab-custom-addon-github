package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 客户端 → 服务端: 管理操作 (仅 OP 可用, 服务端二次校验)。
 *
 * action:
 *   - "list"   → 请求管理数据 (建筑列表 + 玩家权限), arg1/arg2 忽略
 *   - "delete" → 删除 server-cache 里的一个建筑 zip, arg1 = 文件名
 *   - "perm"   → 设置玩家上传权限, arg1 = 玩家 UUID, arg2 = "true"/"false"
 */
public record BuildingManageActionPayload(
        String action,
        String arg1,
        String arg2
) {

    public static void encode(BuildingManageActionPayload msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.action());
        buf.writeUtf(msg.arg1());
        buf.writeUtf(msg.arg2());
    }

    public static BuildingManageActionPayload decode(FriendlyByteBuf buf) {
        return new BuildingManageActionPayload(buf.readUtf(), buf.readUtf(), buf.readUtf());
    }
}
