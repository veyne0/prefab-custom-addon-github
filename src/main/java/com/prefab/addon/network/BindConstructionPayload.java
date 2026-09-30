package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 客户端 -> 服务端：把当前 Construction 绑定到玩家背包中的 Custom Blueprint.
 *
 * locked = true 时表示把蓝图"封死"——此后再点 Select 不会重新绑.
 * 这让玩家可以做出能交易的蓝图(蓝图一旦锁定就只能用它绑的那个建筑, 无法再换).
 *
 * 本包强制服务端也调用 bindConstruction，让两侧 ItemStack 保持一致.
 */
public record BindConstructionPayload(
        String packName,
        String constructionId,
        boolean locked
) {

    public static void encode(BindConstructionPayload msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.packName());
        buf.writeUtf(msg.constructionId());
        buf.writeBoolean(msg.locked());
    }

    public static BindConstructionPayload decode(FriendlyByteBuf buf) {
        return new BindConstructionPayload(buf.readUtf(), buf.readUtf(), buf.readBoolean());
    }
}
