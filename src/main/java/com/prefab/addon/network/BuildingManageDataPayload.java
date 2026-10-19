package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

/**
 * 服务端 → 客户端: 管理界面数据 (建筑文件列表 + 玩家上传权限)。
 * 仅在管理员打开管理界面 / 执行管理操作后下发。
 */
public record BuildingManageDataPayload(
        List<BuildingFile> buildings,
        List<PlayerPerm> players
) implements CustomPacketPayload {

    /** server-cache 里的一个建筑文件 */
    public record BuildingFile(String name, long size) {
        public static final StreamCodec<FriendlyByteBuf, BuildingFile> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, BuildingFile::name,
                        ByteBufCodecs.VAR_LONG, BuildingFile::size,
                        BuildingFile::new
                );
    }

    /** 一个玩家的上传权限状态 */
    public record PlayerPerm(UUID id, String name, boolean allowed) {
        public static final StreamCodec<FriendlyByteBuf, PlayerPerm> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, p -> p.id.toString(),
                        ByteBufCodecs.STRING_UTF8, PlayerPerm::name,
                        ByteBufCodecs.BOOL, PlayerPerm::allowed,
                        (s, n, a) -> new PlayerPerm(UUID.fromString(s), n, a)
                );
    }

    public static final CustomPacketPayload.Type<BuildingManageDataPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("prefab_custom_addon", "building_manage_data"));

    public static final StreamCodec<FriendlyByteBuf, BuildingManageDataPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BuildingFile.STREAM_CODEC.apply(ByteBufCodecs.list()), BuildingManageDataPayload::buildings,
                    PlayerPerm.STREAM_CODEC.apply(ByteBufCodecs.list()), BuildingManageDataPayload::players,
                    BuildingManageDataPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
