package com.prefab.addon.network;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.work.FreeBuildMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端: 同步全服免材料模式开关.
 *
 * <p>触发场景:</p>
 * <ul>
 *   <li>OP 执行 /prefabaddon freemode on|off 后 → 广播给所有在线玩家</li>
 *   <li>玩家进服时 → 服务端发给该玩家一个当前快照</li>
 * </ul>
 *
 * <p>客户端收到后更新 {@link FreeBuildMode} 的客户端镜像, 各材料门控点
 * (CustomStructureGui / StructurePreviewKeyHandler / 多方块浏览器与详情页)
 * 把它视同"创造模式"豁免.</p>
 */
public record FreeBuildSyncPayload(boolean enabled) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FreeBuildSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("prefab_custom_addon", "free_build_sync"));

    public static final StreamCodec<FriendlyByteBuf, FreeBuildSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, FreeBuildSyncPayload::enabled,
                    FreeBuildSyncPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 客户端: 更新本地免材料镜像 */
    public static void handle(final FreeBuildSyncPayload payload, final IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            FreeBuildMode.setClientEnabled(payload.enabled());
            PrefabCustomAddon.LOGGER.info("[FREE-BUILD] 客户端同步免材料模式: {}", payload.enabled());
        });
    }
}
