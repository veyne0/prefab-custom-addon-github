package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Map;

/**
 * 客户端 -> 服务端: 重置某 session 的材料提交进度 (提交界面的"重置进度"按钮).
 *
 * 服务端清掉 ServerMaterialLedger 里对应账本后, 回一个空的
 * {@link MaterialSubmitResultPayload} (deducted 为空, allDone=false) 作为确认,
 * 客户端收到后再清自己的镜像 session — 保证两侧进度一致.
 */
public record ResetMaterialLedgerPayload(
        String sessionId
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ResetMaterialLedgerPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("prefab_custom_addon", "reset_material_ledger"));

    public static final StreamCodec<FriendlyByteBuf, ResetMaterialLedgerPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ResetMaterialLedgerPayload::sessionId,
                    ResetMaterialLedgerPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 服务端: 清掉账本, 回一个空快照让客户端同步清镜像 */
    public static void handle(final ResetMaterialLedgerPayload payload, final IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        ctx.enqueueWork(() -> {
            com.prefab.addon.work.ServerMaterialLedger.reset(sp, payload.sessionId());
            PacketDistributor.sendToPlayer(sp,
                new MaterialSubmitResultPayload(payload.sessionId(), Map.of(), false));
        });
    }
}
