package com.prefab.addon.network;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.client.gui.GuiExtensionPackBrowser;
import com.prefab.addon.extension.LocalBuilding;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 客户端建筑上传器 (把本地建筑 zip 分片推到服务器)。
 *
 * <p>两种来源:
 * <ul>
 *   <li>独立建筑 ({@link LocalBuilding}): 现场把 建筑文件 + 同名 .txt 信息文件 打包成
 *       &lt;id&gt;.zip (不含 png 图标 — 建造不需要, 且可能超限); zip 用固定时间戳,
 *       内容不变则 SHA-1 不变, 便于"已同步"判定</li>
 *   <li>本地 zip 拓展包: 直接把整个 zip 原样上传</li>
 * </ul>
 *
 * <p>流控与服务端镜像: 发一片 (32 KiB) → 等 BuildingUploadAckPayload → 再发下一片;
 * ACK.finished=true 表示最终结果。</p>
 */
public class BuildingUploadClient {

    private static final BuildingUploadClient INSTANCE = new BuildingUploadClient();
    public static BuildingUploadClient getInstance() { return INSTANCE; }

    /** 分片大小 (与服务端 codec 上限一致) */
    public static final int CHUNK_SIZE = 32 * 1024;
    /** 服务端上限 (提前拦截, 免得传一半被拒) */
    public static final long MAX_FILE_SIZE = BuildingUploadServer.MAX_FILE_SIZE;
    /** 固定 zip 时间戳: 内容相同 → zip 字节相同 → SHA-1 相同 (已同步判定用) */
    private static final long FIXED_ZIP_TIME = 946684800000L; // 2000-01-01

    public enum State { IDLE, UPLOADING, DONE, ERROR }

    private volatile State state = State.IDLE;
    private volatile String statusMessage = "";
    private volatile String fileName = null;
    private volatile byte[] data = null;
    private volatile long totalSize = 0;
    private volatile long sentBytes = 0;
    private volatile String sha1 = "";

    public State getState() { return state; }
    public String getStatusMessage() { return statusMessage; }
    public boolean isUploading() { return state == State.UPLOADING; }
    /** 当前进度百分比 (0-100, 非 UPLOADING 时返回 -1) */
    public int getProgressPercent() {
        if (state != State.UPLOADING || totalSize <= 0) return -1;
        return (int) (sentBytes * 100 / totalSize);
    }

    // ==================== 入口 ====================

    /** 上传一个独立建筑: 建筑文件 + .txt 信息 → 内存 zip → 分片发送 (不带 png 图标) */
    public void startStandalone(LocalBuilding lb) {
        if (lb == null || lb.filePath == null) return;
        byte[] zip;
        try {
            zip = buildStandaloneZip(lb);
        } catch (IOException e) {
            state = State.ERROR;
            statusMessage = PrefabCustomAddon.tr("upload.err.zip",
                    e.getMessage() == null ? "" : e.getMessage());
            notifyGui(true);
            return;
        }
        String display = lb.getDisplayName();
        start(lb.id + ".zip", zip, display);
    }

    /** 上传一个本地 zip 拓展包 (原样上传) */
    public void startPack(Path zipPath) {
        if (zipPath == null || !Files.exists(zipPath)) return;
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(zipPath);
        } catch (IOException e) {
            state = State.ERROR;
            statusMessage = PrefabCustomAddon.tr("upload.err.read",
                    e.getMessage() == null ? "" : e.getMessage());
            notifyGui(true);
            return;
        }
        start(zipPath.getFileName().toString(), bytes, zipPath.getFileName().toString());
    }

    private void start(String fname, byte[] bytes, String display) {
        if (isUploading()) {
            statusMessage = PrefabCustomAddon.tr("upload.err.busy");
            notifyGui(false);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getCurrentServer() == null) {
            statusMessage = PrefabCustomAddon.tr("upload.err.singleplayer");
            notifyGui(false);
            return;
        }
        if (bytes.length > MAX_FILE_SIZE) {
            state = State.ERROR;
            statusMessage = PrefabCustomAddon.tr("upload.err.too_large",
                    String.valueOf(MAX_FILE_SIZE / 1024 / 1024));
            notifyGui(true);
            return;
        }
        fileName = fname;
        data = bytes;
        totalSize = bytes.length;
        sentBytes = 0;
        sha1 = sha1Hex(bytes);
        state = State.UPLOADING;
        statusMessage = PrefabCustomAddon.tr("upload.status.start", display, humanBytes(totalSize));
        notifyGui(false);
        PrefabCustomAddon.LOGGER.info("[BUILD-UPLOAD] Start uploading '{}' ({} bytes, sha1={})",
                fname, totalSize, sha1);
        NetworkHandler.sendToServer(new BuildingUploadStartPayload(fname, totalSize, sha1));
        sendNextChunk(0);
    }

    // ==================== ACK 驱动的分片泵 ====================

    /** 收到服务端 ACK (网络线程) */
    public void handleAck(BuildingUploadAckPayload p) {
        if (p.finished()) {
            // 最终结果
            if (state != State.UPLOADING) return;
            if (data != null && !p.fileName().equals(fileName)) return;
            state = p.ok() ? State.DONE : State.ERROR;
            statusMessage = p.message() == null || p.message().isEmpty()
                    ? (p.ok() ? "✓" : "✗") : p.message();
            if (p.ok()) {
                // 记录成功, GUI 侧立刻把按钮刷成"已上传" (不等服务器 manifest 推送)
                GuiExtensionPackBrowser.markUploaded(p.fileName());
            }
            data = null;
            notifyGui(true);
            return;
        }
        // 进度 ACK
        if (state != State.UPLOADING || data == null) return;
        if (!p.fileName().equals(fileName)) return;
        long serverReceived = p.received();
        if (serverReceived < 0 || serverReceived > totalSize) return;
        sentBytes = serverReceived;
        int pct = getProgressPercent();
        statusMessage = PrefabCustomAddon.tr("upload.status.progress",
                String.valueOf(pct), humanBytes(sentBytes), humanBytes(totalSize));
        notifyGui(false);
        sendNextChunk(sentBytes);
    }

    /** 从指定偏移继续发 (发完自动补 End) */
    private void sendNextChunk(long offset) {
        byte[] d = data;
        if (d == null) return;
        if (offset >= totalSize) {
            statusMessage = PrefabCustomAddon.tr("upload.status.saving");
            notifyGui(false);
            NetworkHandler.sendToServer(new BuildingUploadEndPayload(fileName));
            return;
        }
        int len = (int) Math.min(CHUNK_SIZE, totalSize - offset);
        byte[] chunk = Arrays.copyOfRange(d, (int) offset, (int) (offset + len));
        NetworkHandler.sendToServer(new BuildingUploadChunkPayload(fileName, offset, chunk));
    }

    // ==================== zip 组包 ====================

    /** 独立建筑 → 内存 zip: &lt;id&gt;&lt;ext&gt; + &lt;id&gt;.txt (信息文件可选); 固定时间戳保证确定性 */
    private static byte[] buildStandaloneZip(LocalBuilding lb) throws IOException {
        String ext = lb.fileExt == null ? ".nbt" : lb.fileExt.toLowerCase();
        // litematic/schem 上传前统一转成标准 vanilla 结构 NBT (zip 内只写 <id>.nbt):
        // 服务端 zip 包只解析 .nbt 条目, 原样传 .litematic/.schem 服务端加载不出来
        byte[] converted = null;
        if (ext.equals(".litematic")) {
            converted = com.prefab.addon.extension.LitematicaParser.parseToStandardStructureBytes(lb.filePath);
            if (converted == null || converted.length == 0) {
                throw new IOException("litematica 转 vanilla NBT 失败: " + lb.filePath);
            }
            ext = ".nbt";
        } else if (ext.equals(".schem") || ext.equals(".schematic")) {
            converted = com.prefab.addon.extension.SpongeSchematicParser.parseToStandardStructureBytes(lb.filePath);
            if (converted == null || converted.length == 0) {
                throw new IOException("sponge schematic 转 vanilla NBT 失败: " + lb.filePath);
            }
            ext = ".nbt";
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.setLevel(Deflater.BEST_SPEED);
            if (converted != null) {
                putBytes(zos, converted, lb.id + ext);
            } else {
                putFile(zos, lb.filePath, lb.id + ext);
            }
            if (lb.infoPath != null && Files.exists(lb.infoPath)) {
                putFile(zos, lb.infoPath, lb.id + ".txt");
            }
        }
        return bos.toByteArray();
    }

    private static void putFile(ZipOutputStream zos, Path file, String entryName) throws IOException {
        ZipEntry ze = new ZipEntry(entryName);
        ze.setTime(FIXED_ZIP_TIME);
        zos.putNextEntry(ze);
        Files.copy(file, zos);
        zos.closeEntry();
    }

    private static void putBytes(ZipOutputStream zos, byte[] data, String entryName) throws IOException {
        ZipEntry ze = new ZipEntry(entryName);
        ze.setTime(FIXED_ZIP_TIME);
        zos.putNextEntry(ze);
        zos.write(data);
        zos.closeEntry();
    }

    // ==================== 杂项 ====================

    /** 状态变化通知 GUI (网络线程 → 主线程) */
    private void notifyGui(boolean structural) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        if (mc.isSameThread()) {
            GuiExtensionPackBrowser.onUploadProgress(structural);
        } else {
            mc.execute(() -> GuiExtensionPackBrowser.onUploadProgress(structural));
        }
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

    private static String humanBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        return String.format("%.2f MB", b / 1024.0 / 1024.0);
    }
}
