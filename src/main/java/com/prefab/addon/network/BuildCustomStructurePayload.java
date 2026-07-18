package com.prefab.addon.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 客户端发送给服务端的包：放置自定义建筑
 *
 * <p>关键: 携带 {@code houseFacing} (预览时的旋转方向) 一起发给服务端,
 * 否则服务端会按未旋转的 NBT 坐标放置, 而客户端预览的是旋转后的位置 → 实际建造位置跟预览对不上.</p>
 */
public record BuildCustomStructurePayload(
        BlockPos pos,
        String packName,
        String constructionId,
        Direction houseFacing
) implements CustomPacketPayload {

    public BuildCustomStructurePayload(BlockPos pos, String packName, String constructionId) {
        this(pos, packName, constructionId, Direction.SOUTH);
    }

    public static final CustomPacketPayload.Type<BuildCustomStructurePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("prefab_custom_addon", "build_custom_structure"));

    public static final StreamCodec<FriendlyByteBuf, BuildCustomStructurePayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, BuildCustomStructurePayload::pos,
                    ByteBufCodecs.STRING_UTF8, BuildCustomStructurePayload::packName,
                    ByteBufCodecs.STRING_UTF8, BuildCustomStructurePayload::constructionId,
                    Direction.STREAM_CODEC, BuildCustomStructurePayload::houseFacing,
                    BuildCustomStructurePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
