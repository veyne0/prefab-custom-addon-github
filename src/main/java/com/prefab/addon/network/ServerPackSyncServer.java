package com.prefab.addon.network;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.ExtensionPackManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端拓展包同步状态机。
 *
 * 每个玩家一个 PlayerSession：记录该玩家"接下来要发哪几个包"和"当前包已发到哪个 offset"。
 *   - 玩家进服 → onPlayerJoin() 发清单
 *   - 客户端发 RequestServerPacksPayload → 把它要的 pack 排入队列，sendNextChunk()
 *   - 客户端发 ServerPackChunkAckPayload → 推进游标，sendNextChunk()
 *   - 当前包发完 → 切下一个包
 *   - 队列空 → 清除该玩家 session
 *
 * 一次只发一个分片（最大约 60 KiB），等 ACK 再发下一片，天然限速、不会撑爆带宽。
 */
public class ServerPackSyncServer {

    private static final ServerPackSyncServer INSTANCE = new ServerPackSyncServer();
    public static ServerPackSyncServer getInstance() { return INSTANCE; }

    /** 单个分片大小。60 KiB 留余量避开 MC 默认 2 MiB 包上限。 */
    public static final int CHUNK_SIZE = 60 * 1024;

    /** 一个玩家的同步会话 */
    private static class PlayerSession {
        /** 还没开始发的包名（按客户端请求顺序） */
        final List<String> pendingQueue = new ArrayList<>();
        /** 当前正在发的包：name, totalSize, offset */
        String currentName = null;
        long currentTotal = 0;
        long currentOffset = 0;
        /** 当前包已发过一片，等 ACK 期间不再发 */
        boolean awaitingAck = false;
    }

    private final Map<UUID, PlayerSession> sessions = new HashMap<>();

    /** 玩家进服：推 manifest 给他 */
    public void onPlayerJoin(ServerPlayer player) {
        List<ServerPackManifestPayload.Entry> manifest = ExtensionPackManager.getInstance().getSyncManifest();
        if (manifest.isEmpty()) {
            PrefabCustomAddon.LOGGER.info("[PACK-SYNC] No extension packs on server, nothing to sync to {}", player.getName().getString());
            return;
        }
        PrefabCustomAddon.LOGGER.info("[PACK-SYNC] Sending manifest ({} packs) to {}", manifest.size(), player.getName().getString());
        PacketDistributor.sendToPlayer(player, new ServerPackManifestPayload(manifest));
    }

    /** 玩家退出：清掉他的 session */
    public void onPlayerLeave(ServerPlayer player) {
        sessions.remove(player.getUUID());
    }

    /** 客户端发来 RequestServerPacksPayload：把要的包排入队列，开始发 */
    public void handleRequest(ServerPlayer player, RequestServerPacksPayload payload) {
        if (payload.packNames() == null || payload.packNames().isEmpty()) return;

        PlayerSession s = sessions.computeIfAbsent(player.getUUID(), k -> new PlayerSession());
        synchronized (s) {
            for (String name : payload.packNames()) {
                if (!s.pendingQueue.contains(name)) s.pendingQueue.add(name);
            }
            PrefabCustomAddon.LOGGER.info("[PACK-SYNC] {} requested {} pack(s), queue={}",
                    player.getName().getString(), payload.packNames().size(), s.pendingQueue);
            // 如果当前没在发，立刻开始
            if (s.currentName == null && !s.awaitingAck) {
                advanceToNext(player, s);
            }
        }
    }

    /** 客户端 ACK 了一个分片：推进游标，发下一片 / 切下一个包 */
    public void handleAck(ServerPlayer player, ServerPackChunkAckPayload payload) {
        PlayerSession s = sessions.get(player.getUUID());
        if (s == null) return;
        synchronized (s) {
            if (s.currentName == null || !s.currentName.equals(payload.packName())) {
                // 客户端 ACK 了一个我们不在发的包（已切到下一个），忽略
                return;
            }
            s.awaitingAck = false;
            if (payload.done()) {
                PrefabCustomAddon.LOGGER.info("[PACK-SYNC] {} done receiving pack {}", player.getName().getString(), s.currentName);
                s.currentName = null;
                s.currentOffset = 0;
                s.currentTotal = 0;
                advanceToNext(player, s);
            } else {
                s.currentOffset = payload.nextOffset();
                sendNextChunk(player, s);
            }
        }
    }

    /** 从 pendingQueue 取下一个包开始发；如果队列空则清除 session */
    private void advanceToNext(ServerPlayer player, PlayerSession s) {
        Iterator<String> it = s.pendingQueue.iterator();
        while (it.hasNext()) {
            String name = it.next();
            it.remove();
            Path zipPath = ExtensionPackManager.getInstance().findPackZipPath(name);
            if (zipPath == null || !Files.exists(zipPath)) {
                PrefabCustomAddon.LOGGER.warn("[PACK-SYNC] Server pack '{}' missing on disk, skip", name);
                continue;
            }
            long size;
            try {
                size = Files.size(zipPath);
            } catch (IOException e) {
                PrefabCustomAddon.LOGGER.warn("[PACK-SYNC] Cannot stat '{}': {}", name, e.getMessage());
                continue;
            }
            s.currentName = name;
            s.currentTotal = size;
            s.currentOffset = 0;
            PrefabCustomAddon.LOGGER.info("[PACK-SYNC] Start sending pack '{}' ({} bytes) to {}",
                    name, size, player.getName().getString());
            sendNextChunk(player, s);
            return;  // 等 ACK 再继续
        }
        // 队列空
        s.currentName = null;
        if (s.pendingQueue.isEmpty()) {
            // 完全没东西要发了，保留 session 一会儿也没意义，删掉
        }
    }

    /** 发当前包的一片给玩家。读完一片就发，让客户端 ACK 后再读下一片。 */
    private void sendNextChunk(ServerPlayer player, PlayerSession s) {
        if (s.currentName == null || s.awaitingAck) return;
        Path zipPath = ExtensionPackManager.getInstance().findPackZipPath(s.currentName);
        if (zipPath == null) {
            PrefabCustomAddon.LOGGER.warn("[PACK-SYNC] Pack '{}' disappeared mid-transfer, abort", s.currentName);
            s.currentName = null;
            advanceToNext(player, s);
            return;
        }
        long remaining = s.currentTotal - s.currentOffset;
        if (remaining <= 0) {
            // 已发完，等客户端 ACK done=true；不再发片
            return;
        }
        int len = (int) Math.min(CHUNK_SIZE, remaining);
        byte[] buf = new byte[len];
        try (RandomAccessFile raf = new RandomAccessFile(zipPath.toFile(), "r")) {
            raf.seek(s.currentOffset);
            raf.readFully(buf);
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("[PACK-SYNC] Failed to read chunk of '{}' at offset {}: {}",
                    s.currentName, s.currentOffset, e.getMessage());
            s.currentName = null;
            advanceToNext(player, s);
            return;
        }
        ServerPackChunkPayload chunk = new ServerPackChunkPayload(
                s.currentName, s.currentOffset, s.currentTotal, buf);
        PacketDistributor.sendToPlayer(player, chunk);
        s.awaitingAck = true;
    }

    /** 调试 / 状态查询 */
    public int activeSessionCount() {
        return sessions.size();
    }
}
