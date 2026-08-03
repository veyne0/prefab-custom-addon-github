package com.prefab.addon.network;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.ExtensionPackManager;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 客户端拓展包同步状态机。
 *
 * 流程：
 *   1. 收到 ServerPackManifestPayload → 跟本地 server-cache 比对 SHA-1
 *   2. 把缺失/过期的包发 RequestServerPacksPayload 给服务端
 *   3. 收 ServerPackChunkPayload → 按 packName 累计到 ByteArrayOutputStream
 *   4. 收完（offset+len == totalSize）→ 写 server-cache/&lt;name&gt;.zip
 *   5. 所有缺包收完 → 触发 ExtensionPackManager 重扫
 *
 * 状态通过 GUI 顶部的 statusMessage 字段反馈给玩家。
 */
public class ServerPackSyncClient {

    private static final ServerPackSyncClient INSTANCE = new ServerPackSyncClient();
    public static ServerPackSyncClient getInstance() { return INSTANCE; }

    /** 同步状态 (供 GUI 显示) */
    public enum State {
        IDLE,             // 空闲
        CHECKING,         // 收到清单, 正在比对本地缓存
        REQUESTING,       // 已发出 RequestServerPacksPayload
        DOWNLOADING,      // 正在收分片
        FINALIZING,       // 全部收完, 写盘 + 重扫中
        DONE,             // 同步完成
        ERROR             // 出错
    }

    private volatile State state = State.IDLE;
    private volatile String statusMessage = "";
    private volatile int totalToSync = 0;
    private volatile int doneCount = 0;
    private volatile long totalBytesToSync = 0;
    private volatile long bytesReceived = 0;

    /** 当前正在收的包：name → (expectedTotalSize, accumulator) */
    private final Map<String, Inflight> inflight = new HashMap<>();
    /** 本轮要收的包 (用于完成度统计) */
    private final Set<String> wanted = new HashSet<>();

    /**
     * 服务端 manifest 缓存. 每次收到 ServerPackManifestPayload 都会更新.
     * 用于 GUI "服务器" 标签页显示所有可同步的建筑 (含未同步的).
     * 不阻塞 sync 流程, 即使没用也无所谓.
     */
    private final List<ServerPackManifestPayload.Entry> serverManifestCache = new ArrayList<>();
    private final Object manifestLock = new Object();

    private static class Inflight {
        long totalSize;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        long received;
    }

    public State getState() { return state; }
    public String getStatusMessage() { return statusMessage; }
    public int getDoneCount() { return doneCount; }
    public int getTotalToSync() { return totalToSync; }
    public boolean isSyncing() {
        return state == State.REQUESTING || state == State.DOWNLOADING || state == State.FINALIZING;
    }

    /**
     * 返回服务端 manifest 的快照 (含 name, sha1, size). 用于 GUI "服务器" tab 显示未同步建筑.
     * 返回的是新 list, 修改不影响内部缓存.
     */
    public List<ServerPackManifestPayload.Entry> getServerManifestSnapshot() {
        synchronized (manifestLock) {
            return new ArrayList<>(serverManifestCache);
        }
    }

    /** 收到服务端 manifest */
    public void handleManifest(ServerPackManifestPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return;
        state = State.CHECKING;
        statusMessage = "正在比对服务器拓展包...";
        doneCount = 0;
        bytesReceived = 0;

        // 缓存 manifest (给 GUI "服务器" tab 显示未同步建筑用)
        synchronized (manifestLock) {
            serverManifestCache.clear();
            if (payload.packs() != null) {
                serverManifestCache.addAll(payload.packs());
            }
        }

        // 确保 server-cache 目录存在
        Path cacheDir = ExtensionPackManager.getInstance().getServerCacheDir();
        if (cacheDir == null) {
            state = State.ERROR;
            statusMessage = "✗ prefab-extension 目录未初始化";
            return;
        }
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            state = State.ERROR;
            statusMessage = "✗ 创建 server-cache 失败: " + e.getMessage();
            return;
        }

        // 比对 SHA-1：找本地缺 / 哈希不一致的包
        List<String> need = new ArrayList<>();
        long totalBytes = 0;
        for (ServerPackManifestPayload.Entry e : payload.packs()) {
            if (e.name() == null || e.name().isEmpty()) continue;
            // 安全校验: 文件名里不能有 .. 或 /
            String safe = sanitizeFileName(e.name());
            Path zipPath = cacheDir.resolve(safe + ".zip");
            String localSha1 = null;
            if (Files.exists(zipPath)) {
                localSha1 = ExtensionPackManager.computeSha1Hex(zipPath);
            }
            if (!e.sha1().equalsIgnoreCase(localSha1)) {
                need.add(e.name());
                totalBytes += e.size();
                PrefabCustomAddon.LOGGER.info("[PACK-SYNC] Need pack '{}' (server sha1={}, local sha1={})",
                        e.name(), e.sha1(), localSha1);
            } else {
                PrefabCustomAddon.LOGGER.info("[PACK-SYNC] Have pack '{}' (sha1 match), skip", e.name());
            }
        }

        totalToSync = need.size();
        totalBytesToSync = totalBytes;
        wanted.clear();
        wanted.addAll(need);

        if (need.isEmpty()) {
            state = State.DONE;
            statusMessage = "✓ 服务器拓展包已是最新 (" + payload.packs().size() + " 个)";
            doneCount = totalToSync;
            // 还是触发一次重扫，确保 server-cache 里的包出现在 GUI 中
            ExtensionPackManager.getInstance().reloadClient();
            return;
        }

        state = State.REQUESTING;
        statusMessage = "请求 " + need.size() + " 个拓展包 (" + humanBytes(totalBytes) + ")...";
        NetworkHandler.sendToServer(new RequestServerPacksPayload(need));
    }

    /** 收到服务端发来的一个分片 */
    public void handleChunk(ServerPackChunkPayload payload) {
        if (state != State.REQUESTING && state != State.DOWNLOADING) {
            // 收完了还来一片，忽略
            return;
        }
        state = State.DOWNLOADING;
        Inflight inf = inflight.computeIfAbsent(payload.packName(), k -> {
            Inflight x = new Inflight();
            x.totalSize = payload.totalSize();
            return x;
        });
        // 防呆: 客户端期望的 offset 和服务端发的不一致，说明有 ACK 漏了
        if (inf.received != payload.offset()) {
            PrefabCustomAddon.LOGGER.warn("[PACK-SYNC] Chunk offset mismatch for '{}': expected={} got={}, skip",
                    payload.packName(), inf.received, payload.offset());
            // 还是 ACK 一下让服务端继续发后面的
            NetworkHandler.sendToServer(new ServerPackChunkAckPayload(payload.packName(), inf.received, false));
            return;
        }
        inf.baos.write(payload.data(), 0, payload.data().length);
        inf.received += payload.data().length;
        bytesReceived += payload.data().length;

        // 反馈进度
        long recv = bytesReceived;
        long total = totalBytesToSync;
        int pct = total > 0 ? (int) (recv * 100 / total) : 0;
        int idx = doneCount + 1;
        statusMessage = "下载中 [" + idx + "/" + totalToSync + "] " + payload.packName() + "  " + pct + "%";

        if (inf.received >= inf.totalSize) {
            // 收完一个包: 写盘 + 校验
            finalizeOne(payload.packName(), inf);
            // 通知服务端这一包已完成
            NetworkHandler.sendToServer(new ServerPackChunkAckPayload(payload.packName(), inf.received, true));
        } else {
            // 通知服务端发下一片
            NetworkHandler.sendToServer(new ServerPackChunkAckPayload(payload.packName(), inf.received, false));
        }
    }

    /** 收完一个包, 写 server-cache, 从 inflight 移除 */
    private void finalizeOne(String name, Inflight inf) {
        try {
            Path cacheDir = ExtensionPackManager.getInstance().getServerCacheDir();
            String safe = sanitizeFileName(name);
            Path target = cacheDir.resolve(safe + ".zip");
            byte[] bytes = inf.baos.toByteArray();
            Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            // 校验写入的文件
            String actualSha1 = ExtensionPackManager.computeSha1Hex(target);
            PrefabCustomAddon.LOGGER.info("[PACK-SYNC] Wrote pack '{}' ({} bytes, sha1={}) to {}",
                    name, bytes.length, actualSha1, target);

            doneCount++;
            wanted.remove(name);
            inflight.remove(name);

            if (wanted.isEmpty()) {
                state = State.FINALIZING;
                statusMessage = "所有包已下载, 重新扫描...";
                // 触发重扫: 此时 server-cache/ 里的新 zip 才会出现在 GUI 列表中
                ExtensionPackManager.getInstance().reloadClient();
                state = State.DONE;
                statusMessage = "✓ 服务器拓展包同步完成 (" + totalToSync + " 个)";
            }
        } catch (IOException e) {
            state = State.ERROR;
            statusMessage = "✗ 写入 " + name + " 失败: " + e.getMessage();
            PrefabCustomAddon.LOGGER.error("[PACK-SYNC] Failed to finalize pack {}", name, e);
        }
    }

    /** 玩家手动点「同步服务器拓展包」按钮: 让服务端重发 manifest（支持管理员中途加包） */
    public void requestResync() {
        if (isSyncing()) {
            statusMessage = "已在同步中...";
            return;
        }
        state = State.IDLE;
        statusMessage = "⟳ 正在请求服务器清单...";
        PrefabCustomAddon.LOGGER.info("[PACK-SYNC] User clicked resync button");
        NetworkHandler.sendToServer(new RequestServerPackManifestPayload());
    }

    /**
     * 玩家在「服务器」tab 点击单个未同步卡片: 拉这一个建筑.
     * <p>走 RequestServerPacksPayload, 但只放一个 name. 服务端会从 prefab-extension 找到对应
     * zip 然后发过来; 客户端用同一个 handleChunk 通道写入 server-cache/.</p>
     *
     * <p>约束: 必须保证服务端的 ServerPackSyncServer 已经把对应 zip 的 metadata 记过 (即 manifest 已经收到过一次).
     * 这里直接根据缓存的 manifest 找, 没找到就报错让玩家走"同步服务器"按钮重发 manifest.</p>
     */
    public void requestSyncSingle(String packName) {
        if (packName == null || packName.isEmpty()) return;
        if (isSyncing()) {
            statusMessage = "已在同步中, 请稍候";
            return;
        }
        // 在缓存的 manifest 里找
        ServerPackManifestPayload.Entry entry = null;
        synchronized (manifestLock) {
            for (ServerPackManifestPayload.Entry e : serverManifestCache) {
                if (packName.equals(e.name())) {
                    entry = e;
                    break;
                }
            }
        }
        if (entry == null) {
            state = State.ERROR;
            statusMessage = "✗ 没找到建筑 '" + packName + "' 的清单, 请先点「同步服务器」";
            PrefabCustomAddon.LOGGER.warn("[PACK-SYNC] requestSyncSingle: '{}' not in cached manifest, abort", packName);
            return;
        }
        // 准备下载
        totalToSync = 1;
        totalBytesToSync = entry.size();
        bytesReceived = 0;
        doneCount = 0;
        wanted.clear();
        wanted.add(packName);
        inflight.remove(packName);

        state = State.REQUESTING;
        statusMessage = "请求同步建筑 '" + packName + "' (" + humanBytes(entry.size()) + ")...";
        PrefabCustomAddon.LOGGER.info("[PACK-SYNC] User requested single sync: {} ({} bytes)", packName, entry.size());

        // 确保 server-cache 目录存在
        Path cacheDir = ExtensionPackManager.getInstance().getServerCacheDir();
        if (cacheDir != null) {
            try { Files.createDirectories(cacheDir); } catch (IOException ignored) {}
        }
        NetworkHandler.sendToServer(new RequestServerPacksPayload(java.util.Collections.singletonList(packName)));
    }

    /** 安全化包名用作文件名: 替换 / \ : * ? " < > | 和 .. */
    private static String sanitizeFileName(String name) {
        if (name == null) return "_";
        String s = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (s.contains("..")) s = s.replace("..", "_");
        if (s.isEmpty()) s = "_";
        return s;
    }

    private static String humanBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        return String.format("%.2f MB", b / 1024.0 / 1024.0);
    }
}
