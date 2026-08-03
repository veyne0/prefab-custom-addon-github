package com.prefab.addon.cloud;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端: 请求在指定位置放出指定云端建筑.
 *
 * <p>玩家在世界里用方向键 / CTRL 旋转预览后, 按 ALT 触发此 packet.
 * 服务端在 {@code pos} 处按当前玩家朝向放置, 不再用 player.blockPosition().offset(0, 1, 0)
 * 强制头顶 1 格 (那是无预览的旧实现).</p>
 */
public record CloudBuildingSummonPayload(String buildingId, BlockPos pos, Direction facing)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CloudBuildingSummonPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("prefab_custom_addon", "cloud_summon"));

    public static final StreamCodec<FriendlyByteBuf, CloudBuildingSummonPayload> STREAM_CODEC =
            StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, CloudBuildingSummonPayload::buildingId,
                BlockPos.STREAM_CODEC, CloudBuildingSummonPayload::pos,
                Direction.STREAM_CODEC, CloudBuildingSummonPayload::facing,
                CloudBuildingSummonPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
