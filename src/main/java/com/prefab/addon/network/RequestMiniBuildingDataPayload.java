package com.prefab.addon.network;

import com.prefab.addon.PrefabCustomAddon;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端: 请求某个 BlockPos 的迷你建筑完整 NBT.
 *
 * <p><b>为什么需要这个</b>:
 * 迷你建筑 NBT 太大 (40x96x59 = 15k 块 ≈ 1MB+), 走 vanilla 的
 * {@code ClientboundBlockEntityDataPacket} 会触发客户端 {@code NbtAccounter} 的 2MB
 * 上限. 所以 BE 的 {@code getUpdateTag} 只发"引用 NBT" (&lt; 1KB).
 * 客户端 BE 拿到引用后, 没法自己读文件 (ClientLevel 没有 server), 所以渲染不出
 * 任何东西 (BER 看到空 cachedBlocks).
 *
 * <p>本包让客户端在 BER 第一次发现 data 是引用时, 主动向服务端请求完整 NBT.
 * 服务端从世界目录的外部文件读出来, 通过 {@link MiniBuildingFullDataPayload} 推回
 * (用 byte[] 走 ByteBuf, 绕过 NbtAccounter 限制).
 */
public record RequestMiniBuildingDataPayload(BlockPos pos)
    implements CustomPacketPayload {

    public static final Type<RequestMiniBuildingDataPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(PrefabCustomAddon.MOD_ID, "mini_building_data_req"));

    public static final StreamCodec<ByteBuf, RequestMiniBuildingDataPayload> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, RequestMiniBuildingDataPayload::pos,
            RequestMiniBuildingDataPayload::new
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(final RequestMiniBuildingDataPayload payload, final IPayloadContext ctx) {
        // [DEBUG] 详细日志: 确认包到达服务端
        PrefabCustomAddon.LOGGER.info(
            "[MINI_BUILDING] [DEBUG] RequestMiniBuildingDataPayload received at pos={}, player={}",
            payload.pos(), ctx.player().getName().getString());
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        ctx.enqueueWork(() -> {
            // 1) 找到 BE
            if (sp.serverLevel().getBlockEntity(payload.pos())
                instanceof com.prefab.addon.blocks.MiniBuildingBlockEntity be) {
                PrefabCustomAddon.LOGGER.info(
                    "[MINI_BUILDING] [DEBUG] RequestMiniBuildingDataPayload: found BE @ {}, calling handleClientDataRequest",
                    payload.pos());
                // 2) 触发 BE 自己加载完整数据 (从文件读) + 推回客户端
                be.handleClientDataRequest(sp);
            } else {
                PrefabCustomAddon.LOGGER.warn(
                    "[MINI_BUILDING] Server: {} requested data for {} but no BE there",
                    sp.getName().getString(), payload.pos());
            }
        });
    }
}
