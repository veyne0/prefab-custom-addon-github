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
}
