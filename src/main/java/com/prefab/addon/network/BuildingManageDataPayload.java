package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务端 → 客户端: 管理界面数据 (建筑文件列表 + 玩家上传权限)。
 * 仅在管理员打开管理界面 / 执行管理操作后下发。
 */
public record BuildingManageDataPayload(
        List<BuildingFile> buildings,
        List<PlayerPerm> players
) {

    /** server-cache 里的一个建筑文件 */
    public record BuildingFile(String name, long size) {
        public static void encode(BuildingFile msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.name());
            buf.writeVarLong(msg.size());
        }

        public static BuildingFile decode(FriendlyByteBuf buf) {
            return new BuildingFile(buf.readUtf(), buf.readVarLong());
        }
    }

    /** 一个玩家的上传权限状态 */
    public record PlayerPerm(UUID id, String name, boolean allowed) {
        public static void encode(PlayerPerm msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.id.toString());
            buf.writeUtf(msg.name() == null ? "" : msg.name());
            buf.writeBoolean(msg.allowed());
        }

        public static PlayerPerm decode(FriendlyByteBuf buf) {
            String id = buf.readUtf();
            String name = buf.readUtf();
            boolean allowed = buf.readBoolean();
            UUID uuid;
            try {
                uuid = UUID.fromString(id);
            } catch (IllegalArgumentException e) {
                uuid = new UUID(0L, 0L);
            }
            return new PlayerPerm(uuid, name.isEmpty() ? null : name, allowed);
        }
    }

    public static void encode(BuildingManageDataPayload msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.buildings().size());
        for (BuildingFile f : msg.buildings()) {
            BuildingFile.encode(f, buf);
        }
        buf.writeVarInt(msg.players().size());
        for (PlayerPerm p : msg.players()) {
            PlayerPerm.encode(p, buf);
        }
    }

    public static BuildingManageDataPayload decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<BuildingFile> buildings = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            buildings.add(BuildingFile.decode(buf));
        }
        int m = buf.readVarInt();
        List<PlayerPerm> players = new ArrayList<>(m);
        for (int i = 0; i < m; i++) {
            players.add(PlayerPerm.decode(buf));
        }
        return new BuildingManageDataPayload(buildings, players);
    }
}
