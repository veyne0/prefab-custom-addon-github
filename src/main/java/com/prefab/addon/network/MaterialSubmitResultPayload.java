package com.prefab.addon.network;

import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Map;

/**
 * 服务端 -> 客户端: 材料提交/重置结果.
 *
 * submittedAfter = 服务端账本处理后的**完整累计快照** (blockId -> 已提交数);
 * 重置时为空 map. 客户端收到后直接用它替换 ChallengeSessionManager 里的镜像 session
 * (UI 显示 / isReady 门控用), 自己不再动背包 —— 背包以服务端 broadcastChanges 为准.
 */
public record MaterialSubmitResultPayload(
        String sessionId,
        Map<String, Integer> submittedAfter,
        boolean allDone
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MaterialSubmitResultPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("prefab_custom_addon", "material_submit_result"));

    public static final StreamCodec<FriendlyByteBuf, MaterialSubmitResultPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, MaterialSubmitResultPayload::sessionId,
                    ByteBufCodecs.map(java.util.LinkedHashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.INT),
                    MaterialSubmitResultPayload::submittedAfter,
                    ByteBufCodecs.BOOL, MaterialSubmitResultPayload::allDone,
                    MaterialSubmitResultPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 客户端: 用服务端快照替换本地镜像 session, 再刷新可能开着的提交界面 */
    public static void handle(final MaterialSubmitResultPayload payload, final IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            net.minecraft.client.player.LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
            if (player == null) return;
            com.prefab.addon.work.ChallengeSessionManager.applyServerState(
                player.getUUID(), payload.sessionId(), payload.submittedAfter());
            com.prefab.addon.client.gui.MaterialSubmissionGui.onServerState(
                payload.sessionId(), payload.submittedAfter(), payload.allDone());
            PrefabCustomAddon.LOGGER.info("[MATERIAL-LEDGER] 客户端同步服务端账本: session={} 项数={} 全齐={}",
                payload.sessionId(), payload.submittedAfter().size(), payload.allDone());
        });
    }
}
