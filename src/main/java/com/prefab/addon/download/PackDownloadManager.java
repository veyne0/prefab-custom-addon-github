package com.prefab.addon.download;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.config.AddonConfig;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 拓展包下载管理器
 * - 从配置的服务器拉取列表
 * - 下载 zip 到 .minecraft/prefab-extension/&lt;packId&gt;/
 * - 校验 zip 必须含 information/ 和 construction/ 子目录
 */
public class PackDownloadManager {

    private static final PackDownloadManager INSTANCE = new PackDownloadManager();
    public static PackDownloadManager getInstance() { return INSTANCE; }

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    /** 拓展包元信息 (在 mod 端使用) */
    public static class PackInfo {
        public final String id;
        public final String name;
        public final String author;
        public final String version;
        public final String description;
        public final String dependencies;
        public final String link;
        public final int buildings;
        public final int downloads;
        public final String uploaded;
        public final String cover;
        public final List<BuildingInfo> buildingList = new ArrayList<>();

        public PackInfo(String id, String name, String author, String version, String description,
                        String dependencies, String link,
                        int buildings, int downloads, String uploaded, String cover) {
            this.id = id; this.name = name; this.author = author; this.version = version;
            this.description = description; this.dependencies = dependencies; this.link = link;
            this.buildings = buildings; this.downloads = downloads;
            this.uploaded = uploaded; this.cover = cover;
        }
    }

    public static class BuildingInfo {
        public final String id;
        public final String name;
        public final String author;
        public final String size;

        public BuildingInfo(String id, String name, String author, String size) {
            this.id = id; this.name = name; this.author = author; this.size = size;
        }
    }

    /** 下载进度回调 */
    public interface ProgressCallback {
        void onProgress(long downloaded, long total, double percent);
        void onComplete(Path savedTo);
        void onError(String error);
        void onStart(String packId);
    }

    /** 拓展包根目录: .minecraft/prefab-extension/ */
    public static Path getExtensionRoot() {
        Path mc = Paths.get(System.getProperty("user.dir"));
        // 通常 .minecraft/ 就是 user.dir, 但 dev 环境下可能要往下找
        Path config = mc.resolve("config");
        if (!Files.exists(config)) {
            // 尝试 mod-dev 环境: 找到 .minecraft
            Path cur = mc;
            for (int i = 0; i < 5; i++) {
                cur = cur.getParent();
                if (cur == null) break;
                if (Files.exists(cur.resolve("config"))) {
                    mc = cur;
                    break;
                }
            }
        }
        return mc.resolve("prefab-extension");
    }

    /** 拉取服务器列表 */
    public CompletableFuture<List<PackInfo>> fetchPackListAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = AddonConfig.getServerUrl();
                PrefabCustomAddon.LOGGER.info("[DOWNLOAD] Fetching from: {}", url);
                if (url == null || url.isEmpty()) {
                    throw new IOException("下载服务器地址未配置 (config/prefab_custom_addon-common.toml)");
                }
                // 去掉末尾 /
                if (url.endsWith("/")) url = url.substring(0, url.length() - 1);

                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/api/packs"))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "PrefabCustomAddon/1.0")
                    .GET()
                    .build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                PrefabCustomAddon.LOGGER.info("[DOWNLOAD] Response: HTTP {} ({} bytes)", resp.statusCode(), resp.body().length());
                if (resp.statusCode() / 100 != 2) {
                    throw new IOException("HTTP " + resp.statusCode() + ": " + resp.body());
                }

                List<PackInfo> list = new ArrayList<>();
                JsonArray arr = JsonParser.parseString(resp.body()).getAsJsonArray();
                for (JsonElement el : arr) {
                    JsonObject o = el.getAsJsonObject();
                    list.add(new PackInfo(
                        str(o, "id"),
                        str(o, "name"),
                        str(o, "author"),
                        str(o, "version"),
                        str(o, "description"),
                        str(o, "dependencies"),
                        str(o, "link"),
                        o.has("buildings") ? o.get("buildings").getAsInt() : 0,
                        o.has("downloads") ? o.get("downloads").getAsInt() : 0,
                        str(o, "uploaded"),
                        str(o, "cover")
                    ));
                }
                PrefabCustomAddon.LOGGER.info("[DOWNLOAD] Fetched {} packs from {}", list.size(), url);
                return list;
            } catch (Exception e) {
                PrefabCustomAddon.LOGGER.error("[DOWNLOAD] Fetch pack list failed: {}", e.getMessage(), e);
                throw new RuntimeException(e);
            }
        });
    }

    /** 拉取单个包详情 (含建筑列表) */
    public CompletableFuture<PackInfo> fetchPackDetailAsync(String packId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = normalizeUrl() + "/api/packs/" + enc(packId);
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "PrefabCustomAddon/1.0")
                    .GET()
                    .build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 404) {
                    throw new IOException("拓展包不存在: " + packId);
                }
                if (resp.statusCode() / 100 != 2) {
                    throw new IOException("HTTP " + resp.statusCode());
                }
                JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
                PackInfo info = new PackInfo(
                    str(o, "id"),
                    str(o, "name"),
                    str(o, "author"),
                    str(o, "version"),
                    str(o, "description"),
                    str(o, "dependencies"),
                    str(o, "link"),
                    o.has("buildings") ? o.get("buildings").getAsInt() : 0,
                    o.has("downloads") ? o.get("downloads").getAsInt() : 0,
                    str(o, "uploaded"),
                    str(o, "cover")
                );
                if (o.has("buildings") && o.get("buildings").isJsonArray()) {
                    for (JsonElement b : o.get("buildings").getAsJsonArray()) {
                        JsonObject bo = b.getAsJsonObject();
                        info.buildingList.add(new BuildingInfo(
                            str(bo, "id"),
                            str(bo, "name"),
                            str(bo, "author"),
                            str(bo, "size")
                        ));
                    }
                }
                return info;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** 用流式方式下载 zip, 支持进度 (异步线程) */
    public void downloadPackStreaming(String packId, ProgressCallback callback) {
        new Thread(() -> {
            try {
                if (callback != null) callback.onStart(packId);
                String url = normalizeUrl() + "/api/packs/" + enc(packId) + "/download";
                PrefabCustomAddon.LOGGER.info("[DOWNLOAD] Streaming from: {}", url);

                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .header("User-Agent", "PrefabCustomAddon/1.0")
                    .GET()
                    .build();

                Path targetDir = getExtensionRoot().resolve(safeName(packId));
                Files.createDirectories(targetDir);
                Path zipPath = targetDir.resolve(packId + ".zip");

                HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() / 100 != 2) {
                    String err = "HTTP " + resp.statusCode();
                    if (callback != null) callback.onError(err);
                    return;
                }
                long total = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
                try (InputStream in = resp.body();
                     var out = Files.newOutputStream(zipPath, java.nio.file.StandardOpenOption.CREATE,
                         java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buf = new byte[8192];
                    long downloaded = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        downloaded += n;
                        if (callback != null) {
                            double pct = total > 0 ? (downloaded * 100.0 / total) : 0;
                            callback.onProgress(downloaded, total, pct);
                        }
                    }
                }
                PrefabCustomAddon.LOGGER.info("[DOWNLOAD] Downloaded {} ({} bytes)", packId, Files.size(zipPath));

                // 校验
                validateZipStructure(zipPath);

                if (callback != null) callback.onComplete(zipPath);
            } catch (Exception e) {
                PrefabCustomAddon.LOGGER.error("[DOWNLOAD] Failed", e);
                if (callback != null) callback.onError(e.getMessage());
            }
        }, "PackDownload-" + packId).start();
    }

    private void validateZipStructure(Path zipPath) throws IOException {
        boolean hasInfo = false, hasConst = false;
        try (var zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) { zis.closeEntry(); continue; }
                String n = e.getName();
                if (n.startsWith("information/")) hasInfo = true;
                if (n.startsWith("construction/")) hasConst = true;
                zis.closeEntry();
            }
        }
        if (!(hasInfo && hasConst)) {
            // 删除无效 zip
            Files.deleteIfExists(zipPath);
            throw new IOException("标准格式校验失败: zip 内必须包含 information/ 和 construction/ 子目录");
        }
    }

    private String normalizeUrl() {
        String url = AddonConfig.getServerUrl();
        if (url == null || url.isEmpty()) {
            throw new RuntimeException("下载服务器地址未配置");
        }
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String safeName(String n) {
        if (n == null) return "_";
        return n.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    // ========================================================================
    // 独立建筑下载 (新版: /api/buildings, 保存到 prefab-download/)
    // ========================================================================

    /**
     * 独立建筑元信息 (从服务器 /api/buildings 拉取).
     * 文件存到 .minecraft/prefab-download/&lt;id&gt;&lt;fileExt&gt;
     */
    public static class BuildingInfo2 {
        public final String id;
        public final String name;
        public final String author;
        public final String description;
        public final String fileExt;
        public final long fileSize;
        public final String imageExt;
        public final int downloads;
        public final String uploaded;

        public BuildingInfo2(String id, String name, String author, String description,
                             String fileExt, long fileSize, String imageExt,
                             int downloads, String uploaded) {
            this.id = id; this.name = name; this.author = author;
            this.description = description;
            this.fileExt = fileExt == null ? "" : fileExt;
            this.fileSize = fileSize;
            this.imageExt = imageExt == null ? "" : imageExt;
            this.downloads = downloads;
            this.uploaded = uploaded == null ? "" : uploaded;
        }
    }

    /**
     * 独立建筑下载根目录: .minecraft/prefab-download/
     */
    public static Path getDownloadRoot() {
        return getExtensionRoot().getParent().resolve("prefab-download");
    }

    /**
     * 拉取服务器独立建筑列表.
     */
    public CompletableFuture<List<BuildingInfo2>> fetchBuildingListAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = normalizeUrl() + "/api/buildings";
                PrefabCustomAddon.LOGGER.info("[DOWNLOAD-B] Fetching buildings from: {}", url);

                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "PrefabCustomAddon/1.0")
                    .GET()
                    .build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) {
                    throw new IOException("HTTP " + resp.statusCode() + ": " + resp.body());
                }

                List<BuildingInfo2> list = new ArrayList<>();
                JsonArray arr = JsonParser.parseString(resp.body()).getAsJsonArray();
                for (JsonElement el : arr) {
                    JsonObject o = el.getAsJsonObject();
                    list.add(new BuildingInfo2(
                        str(o, "id"),
                        str(o, "name"),
                        str(o, "author"),
                        str(o, "description"),
                        str(o, "fileExt"),
                        o.has("fileSize") ? o.get("fileSize").getAsLong() : 0L,
                        str(o, "imageExt"),
                        o.has("downloads") ? o.get("downloads").getAsInt() : 0,
                        str(o, "uploaded")
                    ));
                }
                PrefabCustomAddon.LOGGER.info("[DOWNLOAD-B] Fetched {} buildings", list.size());
                return list;
            } catch (Exception e) {
                PrefabCustomAddon.LOGGER.error("[DOWNLOAD-B] Fetch building list failed: {}", e.getMessage(), e);
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 下载独立建筑文件 (流式) -> 保存到 .minecraft/prefab-download/&lt;name&gt;&lt;fileExt&gt;
     * <p>同时下载同名 .png (预览图) 和 .txt (元信息) 放到同目录, 让本地扫描器能识别.</p>
     */
    public void downloadBuildingStreaming(BuildingInfo2 info, ProgressCallback callback) {
        new Thread(() -> {
            try {
                if (callback != null) callback.onStart(info.id);
                String urlBase = normalizeUrl();
                String baseName = safeName(info.name == null || info.name.isEmpty() ? info.id : info.name);
                String ext = info.fileExt == null || info.fileExt.isEmpty() ? ".nbt" : info.fileExt;
                if (!ext.startsWith(".")) ext = "." + ext;

                Path targetDir = getDownloadRoot();
                Files.createDirectories(targetDir);

                // 同名文件: 加 _2 _3 后缀
                String suffix = "";
                if (Files.exists(targetDir.resolve(baseName + ext))) {
                    int i = 2;
                    while (Files.exists(targetDir.resolve(baseName + "_" + i + ext))) i++;
                    suffix = "_" + i;
                }
                Path filePath = targetDir.resolve(baseName + suffix + ext);
                PrefabCustomAddon.LOGGER.info("[DOWNLOAD-B] Streaming from: {}/api/buildings/{}/download -> {}",
                    urlBase, info.id, filePath);

                // ---- 1) 主建筑文件 ----
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(urlBase + "/api/buildings/" + enc(info.id) + "/download"))
                    .timeout(Duration.ofSeconds(120))
                    .header("User-Agent", "PrefabCustomAddon/1.0")
                    .GET()
                    .build();
                HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() / 100 != 2) {
                    String err = "HTTP " + resp.statusCode();
                    if (callback != null) callback.onError(err);
                    return;
                }
                long total = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
                try (InputStream in = resp.body();
                     var out = Files.newOutputStream(filePath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buf = new byte[8192];
                    long downloaded = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        downloaded += n;
                        if (callback != null) {
                            double pct = total > 0 ? (downloaded * 100.0 / total) : 0;
                            callback.onProgress(downloaded, total, pct);
                        }
                    }
                }
                PrefabCustomAddon.LOGGER.info("[DOWNLOAD-B] Downloaded {} -> {} ({} bytes)",
                    info.id, filePath, Files.size(filePath));

                // ---- 2) 预览图 (可选, 404 时静默忽略) ----
                try {
                    HttpRequest imgReq = HttpRequest.newBuilder()
                        .uri(URI.create(urlBase + "/api/buildings/" + enc(info.id) + "/image"))
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", "PrefabCustomAddon/1.0")
                        .GET()
                        .build();
                    HttpResponse<byte[]> imgResp = httpClient.send(imgReq, HttpResponse.BodyHandlers.ofByteArray());
                    if (imgResp.statusCode() / 100 == 2 && imgResp.body().length > 0) {
                        String imgExt = (info.imageExt != null && !info.imageExt.isEmpty()) ? info.imageExt : ".png";
                        if (!imgExt.startsWith(".")) imgExt = "." + imgExt;
                        Path imgPath = targetDir.resolve(baseName + suffix + imgExt);
                        Files.write(imgPath, imgResp.body(),
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        PrefabCustomAddon.LOGGER.info("[DOWNLOAD-B] Saved image -> {} ({} bytes)",
                            imgPath, imgResp.body().length);
                    } else {
                        PrefabCustomAddon.LOGGER.info("[DOWNLOAD-B] No image for {} (HTTP {})", info.id, imgResp.statusCode());
                    }
                } catch (Exception imgEx) {
                    PrefabCustomAddon.LOGGER.warn("[DOWNLOAD-B] Image download failed for {}: {}", info.id, imgEx.getMessage());
                }

                // ---- 3) 元信息 (可选, 404 时静默忽略) ----
                try {
                    HttpRequest infoReq = HttpRequest.newBuilder()
                        .uri(URI.create(urlBase + "/api/buildings/" + enc(info.id) + "/info"))
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", "PrefabCustomAddon/1.0")
                        .GET()
                        .build();
                    HttpResponse<byte[]> infoResp = httpClient.send(infoReq, HttpResponse.BodyHandlers.ofByteArray());
                    if (infoResp.statusCode() / 100 == 2 && infoResp.body().length > 0) {
                        Path infoPath = targetDir.resolve(baseName + suffix + ".txt");
                        Files.write(infoPath, infoResp.body(),
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        PrefabCustomAddon.LOGGER.info("[DOWNLOAD-B] Saved info -> {} ({} bytes)",
                            infoPath, infoResp.body().length);
                    } else {
                        PrefabCustomAddon.LOGGER.info("[DOWNLOAD-B] No info for {} (HTTP {})", info.id, infoResp.statusCode());
                    }
                } catch (Exception infoEx) {
                    PrefabCustomAddon.LOGGER.warn("[DOWNLOAD-B] Info download failed for {}: {}", info.id, infoEx.getMessage());
                }

                if (callback != null) callback.onComplete(filePath);
            } catch (Exception e) {
                PrefabCustomAddon.LOGGER.error("[DOWNLOAD-B] Failed", e);
                if (callback != null) callback.onError(e.getMessage());
            }
        }, "BuildingDownload-" + info.id).start();
    }

    /**
     * 检查建筑文件是否已经下载过 (按 id + fileExt 匹配).
     */
    public boolean isBuildingDownloaded(BuildingInfo2 info) {
        if (info == null) return false;
        try {
            Path dir = getDownloadRoot();
            if (!Files.exists(dir)) return false;
            String baseName = safeName(info.name == null || info.name.isEmpty() ? info.id : info.name);
            String ext = info.fileExt == null || info.fileExt.isEmpty() ? ".nbt" : info.fileExt;
            // 1) 主名
            if (Files.exists(dir.resolve(baseName + ext))) return true;
            // 2) _2 _3 ... 后缀
            int i = 2;
            while (i < 100) {
                if (Files.exists(dir.resolve(baseName + "_" + i + ext))) return true;
                i++;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 下载并缓存建筑预览图到本地 temp, 返回字节数组 (失败返回 null).
     * 缓存到 .minecraft/prefab-download/.cache/&lt;id&gt;&lt;imageExt&gt;
     * 如果服务器给的 imageExt 为空, 会按 .png / .jpg / .jpeg / .webp 顺序逐个试.
     */
    public CompletableFuture<byte[]> fetchBuildingImageAsync(BuildingInfo2 info) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (info == null || info.id == null || info.id.isEmpty()) return null;
                Path cacheDir = getDownloadRoot().resolve(".cache");
                Files.createDirectories(cacheDir);

                String[] extsToTry;
                if (info.imageExt != null && !info.imageExt.isEmpty()) {
                    extsToTry = new String[]{info.imageExt};
                } else {
                    extsToTry = new String[]{".png", ".jpg", ".jpeg", ".webp", ".gif"};
                }

                for (String ext : extsToTry) {
                    if (ext == null || ext.isEmpty()) continue;
                    if (!ext.startsWith(".")) ext = "." + ext;
                    Path imgPath = cacheDir.resolve(info.id + ext);
                    if (Files.exists(imgPath)) {
                        return Files.readAllBytes(imgPath);
                    }
                    String url = normalizeUrl() + "/api/buildings/" + enc(info.id) + "/image";
                    HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", "PrefabCustomAddon/1.0")
                        .GET()
                        .build();
                    HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
                    if (resp.statusCode() / 100 != 2) {
                        // 404 时继续尝试下一个扩展名
                        continue;
                    }
                    byte[] data;
                    try (InputStream in = resp.body()) {
                        data = in.readAllBytes();
                    }
                    if (data.length > 0) {
                        Files.write(imgPath, data);
                        return data;
                    }
                }
                PrefabCustomAddon.LOGGER.warn("[DOWNLOAD-B] no image found for {} (tried {} exts)", info.id, extsToTry.length);
                return null;
            } catch (Exception e) {
                PrefabCustomAddon.LOGGER.warn("[DOWNLOAD-B] fetch image failed for {}: {}", info.id, e.getMessage());
                return null;
            }
        });
    }
}
