package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

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
) {

    public static void encode(MaterialSubmitResultPayload msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.sessionId());
        int n = msg.submittedAfter() == null ? 0 : msg.submittedAfter().size();
        buf.writeVarInt(n);
        if (msg.submittedAfter() != null) {
            for (Map.Entry<String, Integer> e : msg.submittedAfter().entrySet()) {
                buf.writeUtf(e.getKey());
                buf.writeVarInt(e.getValue());
            }
        }
        buf.writeBoolean(msg.allDone());
    }

    public static MaterialSubmitResultPayload decode(FriendlyByteBuf buf) {
        String sessionId = buf.readUtf();
        int n = buf.readVarInt();
        Map<String, Integer> submittedAfter = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            submittedAfter.put(buf.readUtf(), buf.readVarInt());
        }
        boolean allDone = buf.readBoolean();
        return new MaterialSubmitResultPayload(sessionId, submittedAfter, allDone);
    }

    /** 客户端: 用服务端快照替换本地镜像 session, 再刷新可能开着的提交界面 */
    public static void handle(MaterialSubmitResultPayload payload, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                ClientPacketHandlers.handleMaterialSubmitResult(payload);
            }
        });
        ctx.setPacketHandled(true);
    }
}
