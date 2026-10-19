package com.prefab.addon.network;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.ExtensionPackManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端建筑上传状态机 (客户端 → 服务器 server-cache/)。
 *
 * <p>每个玩家一个会话:
 * <ol>
 *   <li>收 BuildingUploadStartPayload → 校验文件名/大小, 开会话</li>
 *   <li>收 BuildingUploadChunkPayload → 校验 offset, 拼接, 回 ACK (流控: 客户端等 ACK 再发下一片)</li>
 *   <li>收 BuildingUploadEndPayload → 校验字节数 + SHA-1 → 写 server-cache/&lt;文件名&gt;
 *       → forceReload 重扫 → 给所有在线玩家重发 manifest → 回最终 ACK</li>
 * </ol>
 *
 * <p>防滥用: 单文件上限 {@link #MAX_FILE_SIZE} (8 MB), 只收 .zip, 文件名安全化,
 * 会话 2 分钟无活动自动丢弃, 每人同时只有一个上传会话。
 */
public class BuildingUploadServer {

    private static final BuildingUploadServer INSTANCE = new BuildingUploadServer();
    public static BuildingUploadServer getInstance() { return INSTANCE; }

    /** 单个上传文件上限: 8 MB (一个独立建筑 zip 实际都在 2 MB 以内) */
    public static final long MAX_FILE_SIZE = 8L * 1024 * 1024;

    /** 两次成功上传之间的最小间隔 (毫秒): 防止恶意刷文件占满磁盘 */
    private static final long UPLOAD_COOLDOWN_MS = 60_000L;

    /** server-cache 总占用上限: 超过后拒绝新上传 (服主可手动清理旧文件释放空间) */
    private static final long MAX_CACHE_TOTAL = 512L * 1024 * 1024;

    /** 每人最近一次成功上传时间 (冷却用) */
    private final Map<UUID, Long> lastSuccessMs = new ConcurrentHashMap<>();
    /** 会话无活动超时 (ms), 超时后下次分片到达时丢弃重开 */
    private static final long SESSION_TIMEOUT_MS = 120_000;

    private static class UploadSession {
        final String fileName;
        final long totalSize;
        final String sha1;
        final long startMs = System.currentTimeMillis();
        long lastMs = startMs;
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        UploadSession(String fileName, long totalSize, String sha1) {
            this.fileName = fileName;
            this.totalSize = totalSize;
            this.sha1 = sha1;
        }
    }

    private final Map<UUID, UploadSession> sessions = new HashMap<>();

    /** 玩家退出: 清掉他的上传会话 */
    public void onPlayerLeave(ServerPlayer player) {
        sessions.remove(player.getUUID());
    }

    /** 收到开始上传 */
    public void handleStart(ServerPlayer player, BuildingUploadStartPayload payload) {
        player.getServer().execute(() -> {
            // 上传权限: 管理员可按玩家关闭 (玩家默认有权限)
            if (!BuildingManageServer.getInstance().hasUploadPerm(player.getUUID())) {
                sendAck(player, payload.fileName(), 0, true, false,
                        PrefabCustomAddon.tr("upload.err.no_perm"));
                return;
            }
            String fileName = sanitizeFileName(payload.fileName());
            if (fileName == null) {
                sendAck(player, payload.fileName(), 0, true, false, PrefabCustomAddon.tr("upload.err.bad_name"));
                return;
            }
            long total = payload.totalSize();
            if (total <= 0 || total > MAX_FILE_SIZE) {
                sendAck(player, fileName, 0, true, false,
                        PrefabCustomAddon.tr("upload.err.too_large", String.valueOf(MAX_FILE_SIZE / 1024 / 1024)));
                return;
            }
            // 上传冷却: 刚成功传过一个, 60 秒内不许再开新会话 (防刷盘)
            Long last = lastSuccessMs.get(player.getUUID());
            if (last != null) {
                long wait = UPLOAD_COOLDOWN_MS - (System.currentTimeMillis() - last);
                if (wait > 0) {
                    sendAck(player, fileName, 0, true, false,
                            PrefabCustomAddon.tr("upload.err.cooldown", (long) Math.ceil(wait / 1000.0)));
                    return;
                }
            }
            // 一人一会话: 新 Start 直接覆盖旧会话 (客户端取消/重传场景)
            UploadSession old = sessions.put(player.getUUID(), new UploadSession(fileName, total, payload.sha1()));
            if (old != null) {
                PrefabCustomAddon.LOGGER.info("[BUILD-UPLOAD] {} restarted upload (old '{}' aborted)",
                        player.getName().getString(), old.fileName);
            }
            PrefabCustomAddon.LOGGER.info("[BUILD-UPLOAD] {} starts uploading '{}' ({} bytes, sha1={})",
                    player.getName().getString(), fileName, total, payload.sha1());
        });
    }

    /** 收到一个分片 */
    public void handleChunk(ServerPlayer player, BuildingUploadChunkPayload payload) {
        player.getServer().execute(() -> {
            UploadSession s = sessions.get(player.getUUID());
            if (s == null || !s.fileName.equals(payload.fileName())) {
                // 没有会话 (服务端重启/超时) → 让客户端中止
                sendAck(player, payload.fileName(), 0, true, false, PrefabCustomAddon.tr("upload.err.no_session"));
                return;
            }
            s.lastMs = System.currentTimeMillis();
            long received = s.buf.size();
            if (payload.offset() != received) {
                // 游标不齐: 回 ACK 带上服务端真实游标, 客户端从那里续传
                sendAck(player, s.fileName, received, false, false, "");
                return;
            }
            byte[] data = payload.data();
            if (data == null || data.length == 0
                    || received + data.length > s.totalSize) {
                sendAck(player, s.fileName, received, true, false, PrefabCustomAddon.tr("upload.err.bad_chunk"));
                sessions.remove(player.getUUID());
                return;
            }
            s.buf.write(data, 0, data.length);
            sendAck(player, s.fileName, s.buf.size(), false, false, "");
        });
    }

    /** 收完, 收尾落盘 */
    public void handleEnd(ServerPlayer player, BuildingUploadEndPayload payload) {
        player.getServer().execute(() -> {
            UploadSession s = sessions.remove(player.getUUID());
            if (s == null || !s.fileName.equals(payload.fileName())) {
                sendAck(player, payload.fileName(), 0, true, false, PrefabCustomAddon.tr("upload.err.no_session"));
                return;
            }
            if (s.buf.size() != s.totalSize) {
                sendAck(player, s.fileName, s.buf.size(), true, false,
                        PrefabCustomAddon.tr("upload.err.size_mismatch"));
                return;
            }
            byte[] bytes = s.buf.toByteArray();
            if (s.sha1 != null && !s.sha1.isEmpty() && !s.sha1.equalsIgnoreCase(sha1Hex(bytes))) {
                sendAck(player, s.fileName, s.buf.size(), true, false, PrefabCustomAddon.tr("upload.err.sha1_mismatch"));
                return;
            }
            try {
                Path dir = ExtensionPackManager.getInstance().getServerCacheDir();
                if (dir == null) {
                    sendAck(player, s.fileName, s.buf.size(), true, false, PrefabCustomAddon.tr("upload.err.no_cache_dir"));
                    return;
                }
                // server-cache 总量上限: 已占用 + 本次 > 上限 → 拒绝, 保护服务器磁盘
                long used = cacheTotalSize(dir);
                if (used + bytes.length > MAX_CACHE_TOTAL) {
                    PrefabCustomAddon.LOGGER.warn("[BUILD-UPLOAD] rejected '{}' from {}: cache total {}MB + {} > limit {}MB",
                            s.fileName, player.getName().getString(),
                            used / 1024 / 1024, bytes.length / 1024, MAX_CACHE_TOTAL / 1024 / 1024);
                    sendAck(player, s.fileName, s.buf.size(), true, false,
                            PrefabCustomAddon.tr("upload.err.cache_full"));
                    return;
                }
                Files.createDirectories(dir);
                Path target = dir.resolve(s.fileName);
                Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                PrefabCustomAddon.LOGGER.info("[BUILD-UPLOAD] {} uploaded '{}' ({} bytes) -> {}",
                        player.getName().getString(), s.fileName, bytes.length, target);
                lastSuccessMs.put(player.getUUID(), System.currentTimeMillis());

                // 重扫服务端建筑库 + 给所有在线玩家重发 manifest (其他玩家也能同步到)
                ExtensionPackManager.getInstance().forceReload();
                PrefabCustomAddon.LOGGER.info("[BUILD-UPLOAD] forceReload after upload done");
                for (ServerPlayer sp : player.getServer().getPlayerList().getPlayers()) {
                    ServerPackSyncServer.getInstance().onPlayerJoin(sp);
                }
                player.sendSystemMessage(PrefabCustomAddon.trc("upload.done_chat", s.fileName)
                        .withStyle(ChatFormatting.GREEN));
                sendAck(player, s.fileName, s.buf.size(), true, true,
                        PrefabCustomAddon.tr("upload.done_status"));
            } catch (IOException e) {
                PrefabCustomAddon.LOGGER.error("[BUILD-UPLOAD] Failed to write uploaded zip", e);
                sendAck(player, s.fileName, s.buf.size(), true, false,
                        PrefabCustomAddon.tr("upload.err.io", e.getMessage()));
            }
        });
    }

    private static void sendAck(ServerPlayer player, String fileName, long received,
                                boolean finished, boolean ok, String message) {
        PacketDistributor.sendToPlayer(player,
                new BuildingUploadAckPayload(fileName, received, finished, ok,
                        message == null ? "" : message));
    }

    /** 统计 server-cache 目录总字节数 (目录不存在或不可读返回 0) */
    private static long cacheTotalSize(Path dir) {
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile).mapToLong(p -> {
                try { return Files.size(p); } catch (IOException e) { return 0L; }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** 文件名安全化: 只允许 .zip 结尾, 过掉路径分隔符 / 上级目录 / 空白; 不合法返回 null */
    private static String sanitizeFileName(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replace('\\', '_').replace('/', '_');
        if (s.contains("..")) return null;
        if (s.contains(":") || s.contains("*") || s.contains("?") || s.contains("\"")
                || s.contains("<") || s.contains(">") || s.contains("|")) return null;
        if (s.isEmpty() || s.length() > 96) return null;
        if (!s.toLowerCase().endsWith(".zip")) return null;
        return s;
    }

    private static String sha1Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
