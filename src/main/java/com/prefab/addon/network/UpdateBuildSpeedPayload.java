package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端: 申请修改全局建造放置速度 (buildBatchPercent).
 *
 * <p>建造速度是<strong>全服共享</strong>的设置 (AsyncBuildManager.processTick 在服务端读
 * PlayerPreferences.buildBatchPercent), 不像预览速度是个人客户端本地设置.
 * 因此:</p>
 * <ul>
 *   <li>服务端校验: 玩家必须 OP (permission level >= 2). 非 OP 直接拒绝并发警告.</li>
 *   <li>服务端持久化: 写入服务端 config/prefab_addon/preferences.json.</li>
 *   <li>服务端广播: 通过 {@link SyncBuildSpeedPayload} 推给<strong>所有在线玩家</strong>,
 *       让每个客户端的 SettingsGui 滑条值保持同步.</li>
 * </ul>
 */
public record UpdateBuildSpeedPayload(int percent) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<UpdateBuildSpeedPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("prefab_custom_addon", "update_build_speed"));

    public static final StreamCodec<FriendlyByteBuf, UpdateBuildSpeedPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, UpdateBuildSpeedPayload::percent,
                    UpdateBuildSpeedPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
