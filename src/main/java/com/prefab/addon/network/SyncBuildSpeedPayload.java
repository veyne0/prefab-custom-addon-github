package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端: 广播当前的全局建造放置速度 (buildBatchPercent).
 *
 * <p>触发场景:</p>
 * <ul>
 *   <li>OP 修改了速度后 → 服务端广播给所有在线玩家</li>
 *   <li>玩家进服时 → 服务端单独发给该玩家 (进服时给一个快照)</li>
 * </ul>
 *
 * <p>客户端收到后, 写进自己的 PlayerPreferences.buildBatchPercent, 但**仅供 SettingsGui 显示用**
 * (真正的速度由服务端 PlayerPreferences 控制). 这样做的好处是:</p>
 * <ul>
 *   <li>非 OP 玩家打开 SettingsGui 也能看到当前生效的速度, 但改不了</li>
 *   <li>OP 改完, 所有玩家的滑条立即同步到新值, 不会出现"我改了但你看不到"的割裂</li>
 * </ul>
 */
public record SyncBuildSpeedPayload(int percent) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SyncBuildSpeedPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("prefab_custom_addon", "sync_build_speed"));

    public static final StreamCodec<FriendlyByteBuf, SyncBuildSpeedPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, SyncBuildSpeedPayload::percent,
                    SyncBuildSpeedPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
