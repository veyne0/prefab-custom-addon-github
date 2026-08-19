package com.prefab.addon.network;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.ExtensionPackManager;
import com.prefab.addon.extension.ServerBuildingInfo;
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
 * 客户端建筑同步状态机 (建筑级, 不再是包级).
 *
 * <p>流程:
 * <ol>
 *   <li>收 ServerPackManifestPayload → 跟本地 server-cache 比对 SHA-1, 生成 ServerBuildingInfo 列表 (每个建筑一条, 含 synced 状态)</li>
 *   <li>把缺失/过期的源文件 (按 packName 去重) 发 RequestServerPacksPayload 给服务端</li>
 *   <li>收 ServerPackChunkPayload → 按 packName 累计到 ByteArrayOutputStream</li>
 *   <li>收完 → 写 server-cache/&lt;packName&gt;.&lt;sourceExt&gt;</li>
 *   <li>所有缺包收完 → 触发 ExtensionPackManager 重扫, 重建 ServerBuildingInfo 列表</li>
 * </ol>
 *
 * <p><strong>已废弃"拓展包"概念</strong>:
 * <ul>
 *   <li>每个建筑 = 1 个 ServerBuildingInfo 卡片</li>
 *   <li>老式 .zip 包: 1 个 zip 里有 N 个建筑 → N 个 ServerBuildingInfo, 共享 packName (zip basename) 和 sha1/size (zip 指纹)</li>
 *   <li>新格式独立 .nbt: 1 个文件 = 1 个 ServerBuildingInfo, packName == buildingId</li>
 * </ul>
 * 同步一个建筑 = 下载源文件 (zip 或 nbt). 老 zip 同步一次, 包里所有建筑同时变 synced.
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

    /** 当前正在收的包: packName → (expectedTotalSize, accumulator, sourceFileName) */
    private final Map<String, Inflight> inflight = new HashMap<>();
    /** 本轮要收的包 (用于完成度统计, 按 packName 去重) */
    private final Set<String> wanted = new HashSet<>();

    /**
     * 服务端 manifest 缓存. 每次收到 ServerPackManifestPayload 都会更新.
     * 用于 GUI "服务器" 标签页显示所有可同步的建筑 (含未同步的).
     * 不阻塞 sync 流程, 即使没用也无所谓.
     */
    private final List<ServerBuildingInfo> serverBuildingsCache = new ArrayList<>();
    private final Object cacheLock = new Object();

    private static class Inflight {
        long totalSize;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        long received;
        /** 源文件名 (e.g. "test.zip" / "huochaihe.nbt"), 收完写盘时用 */
        String sourceFileName = "unknown.zip";
    }

    public State getState() { return state; }
    public String getStatusMessage() { return statusMessage; }
    public int getDoneCount() { return doneCount; }
    public int getTotalToSync() { return totalToSync; }
    public boolean isSyncing() {
        return state == State.REQUESTING || state == State.DOWNLOADING || state == State.FINALIZING;
    }

    /**
     * 返回当前所有可同步建筑的快照 (含 name, sha1, size, synced 等).
     * 返回的是新 list, 修改不影响内部缓存.
     */
    public List<ServerBuildingInfo> getServerBuildingSnapshot() {
        synchronized (cacheLock) {
            return new ArrayList<>(serverBuildingsCache);
        }
    }

    /**
     * 返回最近一次收到的 manifest 原始 entries (兼容老 API).
     * <p>用于 {@link ExtensionPackManager#getServerBuildings()} 在
     * {@link #getServerBuildingSnapshot()} 还没填好 (例如启动早期) 时兜底.</p>
     */
    public List<ServerPackManifestPayload.Entry> getServerManifestSnapshot() {
        List<ServerPackManifestPayload.Entry> out = new ArrayList<>();
        synchronized (cacheLock) {
            for (ServerBuildingInfo b : serverBuildingsCache) {
                out.add(new ServerPackManifestPayload.Entry(
                        b.buildingId,
                        b.sha1,
                        b.size,
                        b.buildingId,
                        b.displayName,
                        ".nbt",
                        b.packName,
                        b.sourceFileName,
                        b.author,
                        b.description,
                        b.pngData
                ));
            }
        }
        return out;
    }

    /** 收到服务端 manifest */
    public void handleManifest(ServerPackManifestPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return;
        state = State.CHECKING;
        statusMessage = "正在比对服务器建筑...";
        doneCount = 0;
        bytesReceived = 0;

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

        // 解析 manifest → 生成 ServerBuildingInfo 列表 + 按 packName 找要下载的源文件
        List<ServerBuildingInfo> buildings = new ArrayList<>();
        Set<String> needPackNames = new HashSet<>();  // 去重: 一个 zip 多个建筑只下载一次
        long totalBytes = 0;
        for (ServerPackManifestPayload.Entry e : payload.buildings()) {
            if (e.buildingId() == null || e.buildingId().isEmpty()) continue;
            String srcFile = e.sourceFileName() == null || e.sourceFileName().isEmpty()
                    ? (e.packName() == null ? e.buildingId() + ".zip" : e.packName() + ".zip")
                    : e.sourceFileName();
            String safeName = sanitizeFileName(srcFile);
            Path cacheFile = cacheDir.resolve(safeName);
            // 检查本地缓存 SHA-1
            String localSha1 = null;
            if (Files.exists(cacheFile)) {
                localSha1 = ExtensionPackManager.computeSha1Hex(cacheFile);
            }
            boolean synced = e.sha1() != null && e.sha1().equalsIgnoreCase(localSha1);
            if (!synced) {
                // 按 packName 去重: 老 zip 多个建筑只下一个 zip
                if (e.packName() != null && !e.packName().isEmpty()) {
                    needPackNames.add(e.packName());
                }
                PrefabCustomAddon.LOGGER.info("[BUILD-SYNC] Need building '{}' (src={}, server sha1={}, local sha1={})",
                        e.buildingId(), srcFile, e.sha1(), localSha1);
            } else {
                PrefabCustomAddon.LOGGER.info("[BUILD-SYNC] Have building '{}' (sha1 match), skip", e.buildingId());
            }
            buildings.add(new ServerBuildingInfo(
                    e.buildingId(),
                    e.displayName(),
                    e.author(),
                    e.description(),
                    srcFile,
                    e.packName(),
                    e.sha1(),
                    e.size(),
                    e.pngData(),
                    synced
            ));
            // 进度统计按 packName (而不是 building), 跟实际下载对齐
            if (!synced) {
                // 注意: 同一 zip 多个建筑会重复加 size, 但 totalToSync 用了 needPackNames 长度所以是去重的
                // 这里 totalBytes 先算所有建筑总和, 然后按比例估算
                totalBytes += e.size();
            }
        }

        // 缓存给 GUI 用
        synchronized (cacheLock) {
            serverBuildingsCache.clear();
            serverBuildingsCache.addAll(buildings);
        }

        // 实际下载数 = packName 去重后的数量
        int actualDownloads = needPackNames.size();
        // totalBytes 改成按 packName 去重后的总和 (避免大 zip 多建筑时数字虚高)
        long actualBytes = 0;
        for (ServerBuildingInfo b : buildings) {
            if (b.synced) continue;
            if (b.packName != null && needPackNames.contains(b.packName)) {
                // 第一次遇到这个 packName, 加上 size
                // 用 contains 检查过的跳过
            }
        }
        // 简化: 直接用第一个匹配的 unsynced building 的 size 作为该 packName 的下载字节
        Set<String> counted = new HashSet<>();
        for (ServerBuildingInfo b : buildings) {
            if (b.synced) continue;
            if (b.packName != null && !counted.contains(b.packName) && needPackNames.contains(b.packName)) {
                actualBytes += b.size;
                counted.add(b.packName);
            }
        }

        totalToSync = actualDownloads;
        totalBytesToSync = actualBytes;
        wanted.clear();
        wanted.addAll(needPackNames);
        // 缓存每个 packName 的 sourceFileName, 收完时写盘用
        packNameToSourceFile.clear();
        for (ServerBuildingInfo b : buildings) {
            if (b.packName != null && !b.packName.isEmpty() && b.sourceFileName != null) {
                packNameToSourceFile.put(b.packName, b.sourceFileName);
            }
        }

        if (needPackNames.isEmpty()) {
            state = State.DONE;
            statusMessage = "✓ 服务器建筑已是最新 (" + buildings.size() + " 个)";
            doneCount = totalToSync;
            // 触发重扫, 确保 server-cache 里的新文件出现在 GUI 中
            ExtensionPackManager.getInstance().reloadClient();
            return;
        }

        state = State.REQUESTING;
        statusMessage = "请求 " + actualDownloads + " 个源文件 (" + humanBytes(actualBytes) + ")...";
        // 把 packName 列表发给服务端 (服务端按 packName 找源文件)
        NetworkHandler.sendToServer(new RequestServerPacksPayload(new ArrayList<>(needPackNames)));
    }

    /** packName → 源文件完整名 (e.g. "test" → "test.zip"), 用于收完写盘 */
    private final Map<String, String> packNameToSourceFile = new HashMap<>();

    /** 收到服务端发来的一个分片 */
    public void handleChunk(ServerPackChunkPayload payload) {
        if (state != State.REQUESTING && state != State.DOWNLOADING) {
            // 收完了还来一片, 忽略
            return;
        }
        state = State.DOWNLOADING;
        Inflight inf = inflight.computeIfAbsent(payload.packName(), k -> {
            Inflight x = new Inflight();
            x.totalSize = payload.totalSize();
            x.sourceFileName = packNameToSourceFile.getOrDefault(payload.packName(), payload.packName() + ".zip");
            return x;
        });
        // 防呆: 客户端期望的 offset 和服务端发的不一致, 说明有 ACK 漏了
        if (inf.received != payload.offset()) {
            PrefabCustomAddon.LOGGER.warn("[BUILD-SYNC] Chunk offset mismatch for '{}': expected={} got={}, skip",
                    payload.packName(), inf.received, payload.offset());
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
            finalizeOne(payload.packName(), inf);
            NetworkHandler.sendToServer(new ServerPackChunkAckPayload(payload.packName(), inf.received, true));
        } else {
            NetworkHandler.sendToServer(new ServerPackChunkAckPayload(payload.packName(), inf.received, false));
        }
    }

    /** 收完一个源文件, 写 server-cache, 从 inflight 移除 */
    private void finalizeOne(String packName, Inflight inf) {
        try {
            Path cacheDir = ExtensionPackManager.getInstance().getServerCacheDir();
            String safeName = sanitizeFileName(inf.sourceFileName);
            Path target = cacheDir.resolve(safeName);
            byte[] bytes = inf.baos.toByteArray();
            Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            // 校验写入的文件
            String actualSha1 = ExtensionPackManager.computeSha1Hex(target);
            PrefabCustomAddon.LOGGER.info("[BUILD-SYNC] Wrote source '{}' ({} bytes, sha1={}) to {}",
                    packName, bytes.length, actualSha1, target);

            doneCount++;
            wanted.remove(packName);
            inflight.remove(packName);

            if (wanted.isEmpty()) {
                state = State.FINALIZING;
                statusMessage = "所有源文件已下载, 重新扫描...";
                ExtensionPackManager.getInstance().reloadClient();
                state = State.DONE;
                statusMessage = "✓ 服务器建筑同步完成 (" + totalToSync + " 个源文件)";
            }
        } catch (IOException e) {
            state = State.ERROR;
            statusMessage = "✗ 写入 " + packName + " 失败: " + e.getMessage();
            PrefabCustomAddon.LOGGER.error("[BUILD-SYNC] Failed to finalize source {}", packName, e);
        }
    }

    /** 玩家手动点「同步服务器建筑」按钮: 让服务端重发 manifest (支持管理员中途加包) */
    public void requestResync() {
        if (isSyncing()) {
            statusMessage = "已在同步中...";
            return;
        }
        state = State.IDLE;
        statusMessage = "⟳ 正在请求服务器清单...";
        PrefabCustomAddon.LOGGER.info("[BUILD-SYNC] User clicked resync button");
        NetworkHandler.sendToServer(new RequestServerPackManifestPayload());
    }

    /**
     * 玩家在「服务器」tab 点击单个未同步卡片: 拉这一个建筑对应的源文件.
     * <p>对于老式 .zip 里的建筑: 拉的是整个 zip, 同步后包内所有建筑都变 synced.
     * 对于独立 .nbt 建筑: 拉的就是那个 .nbt.</p>
     */
    public void requestSyncSingle(String buildingId) {
        if (buildingId == null || buildingId.isEmpty()) return;
        if (isSyncing()) {
            statusMessage = "已在同步中, 请稍候";
            return;
        }
        // 在缓存里找该建筑
        ServerBuildingInfo target = null;
        synchronized (cacheLock) {
            for (ServerBuildingInfo b : serverBuildingsCache) {
                if (buildingId.equals(b.buildingId)) {
                    target = b;
                    break;
                }
            }
        }
        if (target == null) {
            state = State.ERROR;
            statusMessage = "✗ 没找到建筑 '" + buildingId + "' 的清单, 请先点「同步服务器」";
            PrefabCustomAddon.LOGGER.warn("[BUILD-SYNC] requestSyncSingle: '{}' not in cached manifest, abort", buildingId);
            return;
        }
        if (target.synced) {
            // 已同步, 玩家应该是误点了 - 不需要下载
            return;
        }
        // 准备下载源文件
        String packName = target.packName == null ? buildingId : target.packName;
        totalToSync = 1;
        totalBytesToSync = target.size;
        bytesReceived = 0;
        doneCount = 0;
        wanted.clear();
        wanted.add(packName);
        inflight.remove(packName);
        packNameToSourceFile.put(packName, target.sourceFileName);

        state = State.REQUESTING;
        statusMessage = "请求同步建筑 '" + target.getDisplayName() + "' (" + humanBytes(target.size) + ")...";
        PrefabCustomAddon.LOGGER.info("[BUILD-SYNC] User requested single sync: {} (src={}, {} bytes)",
                buildingId, target.sourceFileName, target.size);

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
