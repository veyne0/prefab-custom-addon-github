package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

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
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BuildingManageActionPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("prefab_custom_addon", "building_manage_action"));

    public static final StreamCodec<FriendlyByteBuf, BuildingManageActionPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, BuildingManageActionPayload::action,
                    ByteBufCodecs.STRING_UTF8, BuildingManageActionPayload::arg1,
                    ByteBufCodecs.STRING_UTF8, BuildingManageActionPayload::arg2,
                    BuildingManageActionPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
