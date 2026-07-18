package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 -> 服务端：把当前 Construction 绑定到玩家背包中的 Custom Blueprint.
 *
 * locked = true 时表示把蓝图"封死"——此后再点 Select 不会重新绑.
 * 这让玩家可以做出能交易的蓝图(蓝图一旦锁定就只能用它绑的那个建筑, 无法再换).
 *
 * 关键：之前的实现只在客户端调用 stack.set(DataComponents.CUSTOM_DATA, ...)，
 * 服务端 ItemStack 不会被修改，导致：
 * 1) 玩家退出存档时，服务端的（无绑定）ItemStack 覆盖了客户端的（有绑定）ItemStack → 重进存档后绑定丢失
 * 2) 服务端的 placeStructure 找不到绑定的蓝图 → 不消耗
 *
 * 本包强制服务端也调用 bindConstruction，让两侧 ItemStack 保持一致.
 */
public record BindConstructionPayload(
        String packName,
        String constructionId,
        boolean locked
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BindConstructionPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("prefab_custom_addon", "bind_construction"));

    public static final StreamCodec<FriendlyByteBuf, BindConstructionPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, BindConstructionPayload::packName,
                    ByteBufCodecs.STRING_UTF8, BindConstructionPayload::constructionId,
                    ByteBufCodecs.BOOL, BindConstructionPayload::locked,
                    BindConstructionPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
