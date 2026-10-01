/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.BlockPos
 *  net.minecraft.core.Direction
 *  net.minecraft.network.FriendlyByteBuf
 *  net.minecraft.network.codec.ByteBufCodecs
 *  net.minecraft.network.codec.StreamCodec
 *  net.minecraft.network.protocol.common.custom.CustomPacketPayload
 *  net.minecraft.network.protocol.common.custom.CustomPacketPayload$Type
 *  net.minecraft.resources.ResourceLocation
 */
package com.prefab.addon.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record MultiblockSummonPayload(String id, BlockPos pos, Direction facing) implements CustomPacketPayload
{
    public static final CustomPacketPayload.Type<MultiblockSummonPayload> TYPE = new CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath((String)"prefab_custom_addon", (String)"multiblock_summon"));
    public static final StreamCodec<FriendlyByteBuf, MultiblockSummonPayload> STREAM_CODEC = StreamCodec.composite((StreamCodec)ByteBufCodecs.STRING_UTF8, MultiblockSummonPayload::id, (StreamCodec)BlockPos.STREAM_CODEC, MultiblockSummonPayload::pos, (StreamCodec)Direction.STREAM_CODEC, MultiblockSummonPayload::facing, MultiblockSummonPayload::new);

    public MultiblockSummonPayload(String id, BlockPos pos, Direction facing) {
        this.id = id;
        this.pos = pos;
        this.facing = facing;
    }

    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public String id() {
        return this.id;
    }

    public BlockPos pos() {
        return this.pos;
    }

    public Direction facing() {
        return this.facing;
    }
}

