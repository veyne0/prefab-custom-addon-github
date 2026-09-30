package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 客户端 -> 服务端: 请求提交材料 (生存/冒险模式建造需先交齐材料).
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
) {

    public static void encode(SubmitMaterialsPayload msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.sessionId());
        writeMap(buf, msg.required());
        writeMap(buf, msg.fullRequired());
    }

    public static SubmitMaterialsPayload decode(FriendlyByteBuf buf) {
        String sessionId = buf.readUtf();
        Map<String, Integer> required = readMap(buf);
        Map<String, Integer> fullRequired = readMap(buf);
        return new SubmitMaterialsPayload(sessionId, required, fullRequired);
    }

    private static void writeMap(FriendlyByteBuf buf, Map<String, Integer> map) {
        int n = map == null ? 0 : map.size();
        buf.writeVarInt(n);
        if (map != null) {
            for (Map.Entry<String, Integer> e : map.entrySet()) {
                buf.writeUtf(e.getKey());
                buf.writeVarInt(e.getValue());
            }
        }
    }

    private static Map<String, Integer> readMap(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            map.put(buf.readUtf(), buf.readVarInt());
        }
        return map;
    }

    /** 服务端: 在服务端背包上真实扣除, 回传完整累计快照给客户端 */
    public static void handle(SubmitMaterialsPayload payload, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sp = ctx.getSender();
            if (sp == null) return;
            com.prefab.addon.work.ServerMaterialLedger.Result r =
                com.prefab.addon.work.ServerMaterialLedger.submit(
                    sp, payload.sessionId(), payload.required(), payload.fullRequired());
            NetworkHandler.sendToPlayer(sp,
                new MaterialSubmitResultPayload(payload.sessionId(), r.submittedAfter(), r.allDone()));
        });
        ctx.setPacketHandled(true);
    }
}
