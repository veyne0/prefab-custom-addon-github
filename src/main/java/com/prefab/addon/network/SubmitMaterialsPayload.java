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
 * 客户端 -> 服务端: 请求提交材料 (挑战模式).
 *
 * required = 本次提交针对的需求表 (blockId -> 总需求量):
 *   - "全部提交" = 完整材料清单
 *   - 单卡片"提交" = 只含该卡片一项
 * fullRequired = 该 session 的**完整**材料需求表, 仅供服务端判定 allDone:
 *   单卡片提交时 required 只有一项, 若只看它, 交齐这一种就会误报"全部材料已交齐"
 *   (实际其它材料还缺). 扣除逻辑仍只按 required 执行.
 *
 * 服务端 (ServerMaterialLedger) 按 min(背包实有, 还差的数量) 在**服务端背包**上真实扣除,
 * 再回 {@link MaterialSubmitResultPayload} 告知处理后的完整累计快照.
 * 修复: 旧实现只在客户端扣, 打开任何容器 GUI 后服务端背包同步回来材料复活 (刷物品).
 */
public record SubmitMaterialsPayload(
        String sessionId,
        Map<String, Integer> required,
        Map<String, Integer> fullRequired
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SubmitMaterialsPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("prefab_custom_addon", "submit_materials"));

    public static final StreamCodec<FriendlyByteBuf, SubmitMaterialsPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, SubmitMaterialsPayload::sessionId,
                    ByteBufCodecs.map(java.util.LinkedHashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.INT),
                    SubmitMaterialsPayload::required,
                    ByteBufCodecs.map(java.util.LinkedHashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.INT),
                    SubmitMaterialsPayload::fullRequired,
                    SubmitMaterialsPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 服务端: 在服务端背包上真实扣除, 回传完整累计快照给客户端 */
    public static void handle(final SubmitMaterialsPayload payload, final IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        ctx.enqueueWork(() -> {
            com.prefab.addon.work.ServerMaterialLedger.Result r =
                com.prefab.addon.work.ServerMaterialLedger.submit(
                    sp, payload.sessionId(), payload.required(), payload.fullRequired());
            PacketDistributor.sendToPlayer(sp,
                new MaterialSubmitResultPayload(payload.sessionId(), r.submittedAfter(), r.allDone()));
        });
    }
}
