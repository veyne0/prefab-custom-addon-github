package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 客户端 -> 服务端: 重置某 session 的材料提交进度 (提交界面的"重置进度"按钮).
 *
 * 服务端清掉 ServerMaterialLedger 里对应账本后, 回一个空的
 * {@link MaterialSubmitResultPayload} (快照为空, allDone=false) 作为确认,
 * 客户端收到后再清自己的镜像 session — 保证两侧进度一致.
 */
public record ResetMaterialLedgerPayload(
        String sessionId
) {

    public static void encode(ResetMaterialLedgerPayload msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.sessionId());
    }

    public static ResetMaterialLedgerPayload decode(FriendlyByteBuf buf) {
        return new ResetMaterialLedgerPayload(buf.readUtf());
    }

    /** 服务端: 清掉账本, 回一个空快照让客户端同步清镜像 */
    public static void handle(ResetMaterialLedgerPayload payload, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sp = ctx.getSender();
            if (sp == null) return;
            com.prefab.addon.work.ServerMaterialLedger.reset(sp, payload.sessionId());
            NetworkHandler.sendToPlayer(sp,
                new MaterialSubmitResultPayload(payload.sessionId(), Map.of(), false));
        });
        ctx.setPacketHandled(true);
    }
}
