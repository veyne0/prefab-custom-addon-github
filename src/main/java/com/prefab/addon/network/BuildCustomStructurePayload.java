package com.prefab.addon.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 客户端发送给服务端的包：放置自定义建筑
 */
public record BuildCustomStructurePayload(
        BlockPos pos,
        String packName,
        String constructionId
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BuildCustomStructurePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("prefab_custom_addon", "build_custom_structure"));

    public static final StreamCodec<FriendlyByteBuf, BuildCustomStructurePayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, BuildCustomStructurePayload::pos,
                    ByteBufCodecs.STRING_UTF8, BuildCustomStructurePayload::packName,
                    ByteBufCodecs.STRING_UTF8, BuildCustomStructurePayload::constructionId,
                    BuildCustomStructurePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
