package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 -> 服务端：把当前 Construction 绑定到玩家背包中的 Custom Blueprint。
 *
 * 关键：之前的实现只在客户端调用 stack.set(DataComponents.CUSTOM_DATA, ...)，
 * 服务端 ItemStack 不会被修改，导致：
 * 1) 玩家退出存档时，服务端的（无绑定）ItemStack 覆盖了客户端的（有绑定）ItemStack → 重进存档后绑定丢失
 * 2) 服务端的 placeStructure 找不到绑定的蓝图 → 不消耗
 *
 * 本包强制服务端也调用 bindConstruction，让两侧 ItemStack 保持一致。
 */
public record BindConstructionPayload(
        String packName,
        String constructionId
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BindConstructionPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("prefab_custom_addon", "bind_construction"));

    public static final StreamCodec<FriendlyByteBuf, BindConstructionPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, BindConstructionPayload::packName,
                    ByteBufCodecs.STRING_UTF8, BindConstructionPayload::constructionId,
                    BindConstructionPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
