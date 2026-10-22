package com.prefab.addon.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.ExtensionPackManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 服务端建筑管理 (仅 OP): 列出/删除 server-cache 里的建筑 zip, 管理玩家上传权限。
 *
 * <p>权限存储: config/prefab_custom_addon/upload_perms.json,
 * Map&lt;UUID, Boolean&gt;, <b>缺席的玩家默认有上传权限</b> (需求: 玩家默认可上传)。</p>
 */
public class BuildingManageServer {

    private static final BuildingManageServer INSTANCE = new BuildingManageServer();
    public static BuildingManageServer getInstance() { return INSTANCE; }

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._\\-\\u4e00-\\u9fa5()（）]+");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type PERM_TYPE = new TypeToken<Map<String, Boolean>>() {}.getType();

    /** 玩家上传权限 (UUID -> 是否允许); 缺席 = 默认允许 */
    private final Map<UUID, Boolean> uploadPerms = new ConcurrentHashMap<>();

    private BuildingManageServer() {}

    // ------------------------------------------------------------------ 权限

    /** 是否有上传权限 (缺席默认允许) */
    public boolean hasUploadPerm(UUID uuid) {
        return uploadPerms.getOrDefault(uuid, true);
    }

    /** 设置玩家上传权限并持久化 */
    public void setUploadPerm(UUID uuid, boolean allowed) {
        uploadPerms.put(uuid, allowed);
        savePerms();
    }

    private Path permsFile() {
        return FMLPaths.CONFIGDIR.get().resolve("prefab_custom_addon").resolve("upload_perms.json");
    }

    /** 启动时加载权限表 (文件缺失/损坏都不致命) */
    public void loadPerms() {
        uploadPerms.clear();
        Path f = permsFile();
        if (!Files.exists(f)) return;
        try {
            Map<String, Boolean> raw = GSON.fromJson(Files.readString(f), PERM_TYPE);
            if (raw != null) {
                for (Map.Entry<String, Boolean> e : raw.entrySet()) {
                    try {
                        uploadPerms.put(UUID.fromString(e.getKey()), e.getValue());
                    } catch (IllegalArgumentException ignore) {}
                }
            }
            PrefabCustomAddon.LOGGER.info("[BUILD-MANAGE] loaded {} upload perm entries", uploadPerms.size());
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("[BUILD-MANAGE] failed to load upload perms", e);
        }
    }

    private void savePerms() {
        try {
            Path f = permsFile();
            Files.createDirectories(f.getParent());
            Map<String, Boolean> raw = new ConcurrentHashMap<>();
            for (Map.Entry<UUID, Boolean> e : uploadPerms.entrySet()) {
                raw.put(e.getKey().toString(), e.getValue());
            }
            Files.writeString(f, GSON.toJson(raw));
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.warn("[BUILD-MANAGE] failed to save upload perms", e);
        }
    }

    // ------------------------------------------------------------------ 管理操作

    /** 收到管理操作 (客户端 → 服务端)。非 OP 直接拒绝。 */
    public void handleAction(ServerPlayer player, BuildingManageActionPayload payload) {
        player.getServer().execute(() -> {
            if (!player.hasPermissions(2)) {
                PrefabCustomAddon.LOGGER.warn("[BUILD-MANAGE] non-OP {} tried manage action '{}', refused",
                        player.getName().getString(), payload.action());
                player.sendSystemMessage(Component
                        .translatable("manage.err.op").withStyle(ChatFormatting.RED));
                return;
            }
            switch (payload.action()) {
                case "list" -> sendManageData(player);
                case "delete" -> handleDelete(player, payload.arg1());
                case "perm" -> handlePerm(player, payload.arg1(), payload.arg2());
                default -> PrefabCustomAddon.LOGGER.warn("[BUILD-MANAGE] unknown action '{}'", payload.action());
            }
        });
    }

    /** 删除 server-cache 里的一个建筑 zip */
    private void handleDelete(ServerPlayer player, String fileName) {
        if (fileName == null || !SAFE_NAME.matcher(fileName).matches() || !fileName.endsWith(".zip")) {
            player.sendSystemMessage(Component
                    .translatable("manage.err.delete_fail").withStyle(ChatFormatting.RED));
            return;
        }
        Path dir = ExtensionPackManager.getInstance().getServerCacheDir();
        Path target = dir == null ? null : dir.resolve(fileName).normalize();
        if (target == null || !target.getParent().equals(dir.normalize()) || !Files.exists(target)) {
            player.sendSystemMessage(Component
                    .translatable("manage.err.delete_fail").withStyle(ChatFormatting.RED));
            return;
        }
        try {
            Files.delete(target);
            PrefabCustomAddon.LOGGER.info("[BUILD-MANAGE] {} deleted server building '{}'",
                    player.getName().getString(), fileName);
            player.sendSystemMessage(Component
                    .translatable("manage.err.deleted", fileName).withStyle(ChatFormatting.GREEN));
            // 重扫服务端建筑库 + 给所有在线玩家重发 manifest (被删建筑从所有人列表消失)
            ExtensionPackManager.getInstance().forceReload();
            for (ServerPlayer sp : player.getServer().getPlayerList().getPlayers()) {
                ServerPackSyncServer.getInstance().onPlayerJoin(sp);
            }
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("[BUILD-MANAGE] failed to delete '{}'", fileName, e);
            player.sendSystemMessage(Component
                    .translatable("manage.err.delete_fail").withStyle(ChatFormatting.RED));
        }
        // 删完 (无论成败) 回一份最新数据, 界面刷新
        sendManageData(player);
    }

    /** 设置玩家上传权限 */
    private void handlePerm(ServerPlayer player, String uuidStr, String allowedStr) {
        try {
            UUID uuid = UUID.fromString(uuidStr);
            boolean allowed = "true".equals(allowedStr);
            setUploadPerm(uuid, allowed);
            PrefabCustomAddon.LOGGER.info("[BUILD-MANAGE] {} set upload perm of {} -> {}",
                    player.getName().getString(), uuid, allowed);
        } catch (IllegalArgumentException e) {
            PrefabCustomAddon.LOGGER.warn("[BUILD-MANAGE] bad perm arg: {} / {}", uuidStr, allowedStr);
        }
        sendManageData(player);
    }

    /** 下发管理数据 (建筑列表 + 玩家权限列表) 给管理员 */
    public void sendManageData(ServerPlayer player) {
        List<BuildingManageDataPayload.BuildingFile> buildings = new ArrayList<>();
        Path dir = ExtensionPackManager.getInstance().getServerCacheDir();
        if (dir != null && Files.exists(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".zip"))
                        .forEach(p -> {
                            try {
                                long size = Files.size(p);
                                buildings.add(new BuildingManageDataPayload.BuildingFile(
                                        p.getFileName().toString(), size));
                            } catch (IOException ignore) {}
                        });
            } catch (IOException e) {
                PrefabCustomAddon.LOGGER.warn("[BUILD-MANAGE] failed to list server-cache", e);
            }
        }
        buildings.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));

        // 玩家列表 = 在线玩家 ∪ 权限表里记录过的玩家 (覆盖离线玩家)
        List<BuildingManageDataPayload.PlayerPerm> players = new ArrayList<>();
        var seen = new java.util.HashSet<UUID>();
        for (ServerPlayer sp : player.getServer().getPlayerList().getPlayers()) {
            players.add(new BuildingManageDataPayload.PlayerPerm(
                    sp.getUUID(), sp.getName().getString(), hasUploadPerm(sp.getUUID())));
            seen.add(sp.getUUID());
        }
        for (UUID id : uploadPerms.keySet()) {
            if (seen.add(id)) {
                players.add(new BuildingManageDataPayload.PlayerPerm(id, null, hasUploadPerm(id)));
            }
        }
        players.sort((a, b) -> {
            String an = a.name() == null ? a.id().toString() : a.name();
            String bn = b.name() == null ? b.id().toString() : b.name();
            return an.compareToIgnoreCase(bn);
        });

        NetworkHandler.sendToPlayer(player, new BuildingManageDataPayload(buildings, players));
    }
}
