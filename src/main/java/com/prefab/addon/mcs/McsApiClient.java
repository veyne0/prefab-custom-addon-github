package com.prefab.addon.mcs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * mcschematic.top 蓝图广场 API 客户端 (只读) — 从 1.21.1 NeoForge 版移植.
 * 纯 java.net.http + Gson, 平台无关, 1.20.1 Forge 无需任何适配.
 *
 * <p>Base: https://www.mcschematic.top/api/v1 , 公开端点匿名可读, 无需 API 密钥.
 * 上传/发布只能在网页完成, mod 只做浏览 + 搜索 + 下载.</p>
 *
 * <p>所有网络请求都跑在后台线程池, 回调用 {@code CompletableFuture} 返回;
 * GUI 侧拿到结果后自行 {@code Minecraft.execute()} 切回主线程.</p>
 */
public final class McsApiClient {

    public static final String SITE = "https://www.mcschematic.top";
    private static final String BASE = SITE + "/api/v1";
    /** 每页条数 (网站 limit 上限未知, 12 稳妥). */
    public static final int PAGE_SIZE = 12;

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private static final ExecutorService IO = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "MCS-IO");
        t.setDaemon(true);
        return t;
    });

    private McsApiClient() {}

    // ==================== 数据模型 ====================

    /** 蓝图条目 (列表页返回的摘要信息). */
    public static class Blueprint {
        public String id;          // uuid
        public String slug;
        public String title;
        public String description;
        public String author;
        public String category;
        /** 预览图完整 URL (已是绝对地址). */
        public String previewUrl;
        /** 当前版本号 (下载 URL 用). */
        public int version;
        /** 来源格式: litematica / create / vanilla ... */
        public String sourceFormat;
        /** 网站声明的命名空间 (如 minecraft / create / mekanism), 可当依赖 modid 用 */
        public List<String> namespaces;
        public long downloads;
        public int views;
        public String createdAt;

        /** 文件名基础部分 (不含扩展名): 清洗后的标题-id前8位. */
        public String fileBase() {
            String t = sanitizeFileName(title);
            if (t.isEmpty()) t = "blueprint";
            return t + "-" + (id == null ? "00000000" : id.substring(0, Math.min(8, id.length())));
        }

        /** 蓝图网页地址 (点卡片跳转用). */
        public String webUrl() {
            if (slug != null && !slug.isEmpty()) return SITE + "/blueprint/" + slug;
            return SITE + "/blueprint/" + id;
        }
    }

    /** 一页列表结果. */
    public static class Page {
        public final List<Blueprint> items = new ArrayList<>();
        /** 翻页游标 (null = 没有下一页). */
        public String nextCursor;
        /** 网站总数 (未知 = -1). */
        public int total = -1;
    }

    /** 分类条目 (/categories 返回, 与网页侧边栏一致). */
    public static class Category {
        public String id;        // uuid
        public String slug;      // 过滤参数用: /blueprints?category=<slug>
        public String name;      // 显示名 (如 "红石装置")
        /** 父分类 id (null = 顶级分类; 网站侧边栏只显示顶级). */
        public String parentId;
        /** 该分类下蓝图数 (含子分类). */
        public int count;
    }

    // ==================== 分类 ====================

    /**
     * 拉取全部分类 (网站一次返回全部, 无需翻页).
     * 只保留顶级分类 (parentId 为空): 过滤顶级 slug 时网站会连带返回子分类的蓝图.
     */
    public static CompletableFuture<List<Category>> fetchCategoriesAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/categories"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET().build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() == 429) {
                    throw new RuntimeException("请求过于频繁, 请稍后再试");
                }
                if (resp.statusCode() != 200) {
                    throw new RuntimeException("HTTP " + resp.statusCode());
                }
                List<Category> out = new ArrayList<>();
                JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
                JsonArray items = root.has("items") && root.get("items").isJsonArray()
                    ? root.getAsJsonArray("items") : new JsonArray();
                for (JsonElement el : items) {
                    if (!el.isJsonObject()) continue;
                    JsonObject it = el.getAsJsonObject();
                    Category c = new Category();
                    c.id = str(it, "id");
                    c.slug = str(it, "slug");
                    c.name = str(it, "name");
                    c.parentId = str(it, "parentId");
                    if (it.has("blueprintCount") && it.get("blueprintCount").isJsonPrimitive()) {
                        c.count = it.get("blueprintCount").getAsInt();
                    }
                    // 只留顶级 + 有 slug/名字的 (子分类蓝图通过顶级 slug 过滤已覆盖)
                    if (c.slug != null && !c.slug.isEmpty() && c.name != null && !c.name.isEmpty()
                            && (c.parentId == null || c.parentId.isEmpty())) {
                        out.add(c);
                    }
                }
                return out;
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage() == null ? "网络错误" : e.getMessage(), e);
            }
        }, IO);
    }

    // ==================== 列表 ====================

    /**
     * 拉取蓝图列表.
     * @param query 搜索关键词 (空串 = 不过滤)
     * @param sort  排序: latest / oldest / popular
     * @param category 分类 slug (null/空 = 不限分类)
     * @param cursor 翻页游标 (null = 第一页)
     */
    public static CompletableFuture<Page> fetchAsync(String query, String sort, String category, String cursor) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                StringBuilder url = new StringBuilder(BASE)
                    .append("/blueprints?limit=").append(PAGE_SIZE)
                    .append("&sort=").append(encode(sort == null || sort.isEmpty() ? "latest" : sort));
                if (query != null && !query.isEmpty()) {
                    url.append("&q=").append(encode(query));
                }
                if (category != null && !category.isEmpty()) {
                    url.append("&category=").append(encode(category));
                }
                if (cursor != null && !cursor.isEmpty()) {
                    url.append("&cursor=").append(encode(cursor));
                }
                HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET().build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() == 429) {
                    throw new RuntimeException("请求过于频繁, 请稍后再试");
                }
                if (resp.statusCode() != 200) {
                    throw new RuntimeException("HTTP " + resp.statusCode());
                }
                return parsePage(resp.body());
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage() == null ? "网络错误" : e.getMessage(), e);
            }
        }, IO);
    }

    /** 解析列表 JSON: {items:[...], nextCursor, totalCount}. */
    private static Page parsePage(String body) {
        Page page = new Page();
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (root.has("totalCount") && root.get("totalCount").isJsonPrimitive()) {
            page.total = root.get("totalCount").getAsInt();
        }
        if (root.has("nextCursor") && !root.get("nextCursor").isJsonNull()) {
            page.nextCursor = root.get("nextCursor").getAsString();
        }
        JsonArray items = root.has("items") && root.get("items").isJsonArray()
            ? root.getAsJsonArray("items") : new JsonArray();
        for (JsonElement el : items) {
            if (!el.isJsonObject()) continue;
            JsonObject it = el.getAsJsonObject();
            Blueprint b = new Blueprint();
            b.id = str(it, "id");
            b.slug = str(it, "slug");
            b.title = str(it, "title");
            if (b.title == null || b.title.isEmpty()) b.title = b.slug != null ? b.slug : b.id;
            b.description = str(it, "description");
            JsonObject author = obj(it, "author");
            if (author != null) b.author = str(author, "displayName");
            JsonObject category = obj(it, "category");
            if (category != null) b.category = str(category, "name");
            b.previewUrl = str(it, "previewUrl");
            if (b.previewUrl != null && b.previewUrl.startsWith("/")) {
                b.previewUrl = SITE + b.previewUrl;   // 相对路径 → 绝对
            }
            JsonObject ver = obj(it, "currentVersion");
            if (ver != null) {
                if (ver.has("number") && ver.get("number").isJsonPrimitive()) {
                    b.version = ver.get("number").getAsInt();
                }
                b.sourceFormat = str(ver, "sourceFormat");
            }
            // namespaces: ["minecraft","create",...] → 依赖 modid (过滤 minecraft)
            if (it.has("namespaces") && it.get("namespaces").isJsonArray()) {
                b.namespaces = new ArrayList<>();
                for (JsonElement ns : it.getAsJsonArray("namespaces")) {
                    if (ns.isJsonPrimitive()) {
                        String n = ns.getAsString();
                        if (n != null && !n.isEmpty() && !"minecraft".equalsIgnoreCase(n)) {
                            b.namespaces.add(n);
                        }
                    }
                }
                if (b.namespaces.isEmpty()) b.namespaces = null;
            }
            JsonObject eng = obj(it, "engagement");
            if (eng != null && eng.has("downloadCount") && eng.get("downloadCount").isJsonPrimitive()) {
                b.downloads = eng.get("downloadCount").getAsLong();
            }
            b.createdAt = str(it, "createdAt");
            if (b.id != null) page.items.add(b);
        }
        return page;
    }

    // ==================== 预览图 (带磁盘缓存) ====================

    /** 预览图磁盘缓存目录: .minecraft/prefab-custom-addon/mcs-preview/ */
    public static Path previewCacheDir() {
        return Minecraft.getInstance().gameDirectory.toPath()
            .resolve("prefab-custom-addon").resolve("mcs-preview");
    }

    /**
     * 获取预览图 PNG 字节: 磁盘缓存命中直接读, 未命中下载后落盘.
     * 返回 null 表示无预览图.
     */
    public static CompletableFuture<byte[]> fetchPreviewAsync(Blueprint b) {
        if (b.previewUrl == null || b.previewUrl.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> {
            // 缓存扩展名用 .img: 实际格式按字节魔数判断 (网站预览图是 webp), 旧版误存 .png 的缓存自动失效
            Path cache = previewCacheDir().resolve(b.id + "-v" + b.version + ".img");
            try {
                if (Files.isRegularFile(cache)) {
                    byte[] cached = Files.readAllBytes(cache);
                    if (cached != null && cached.length > 0) return cached;
                }
            } catch (Exception ignored) {}
            try {
                byte[] data = httpGetBytes(b.previewUrl);
                if (data == null || data.length == 0) return null;
                try {
                    Files.createDirectories(cache.getParent());
                    Files.write(cache, data);
                } catch (Exception ex) {
                    PrefabCustomAddon.LOGGER.warn("[MCS] 预览图缓存写入失败: {}", ex.getMessage());
                }
                return data;
            } catch (Exception e) {
                PrefabCustomAddon.LOGGER.warn("[MCS] 预览图下载失败 {}: {}", b.id, e.getMessage());
                return null;
            }
        }, IO);
    }

    // ==================== 下载 ====================

    /** 下载进度回调 (全部回调已在主线程执行). */
    public interface DownloadCallback {
        void onProgress(long downloaded, long total);
        void onSuccess(Path savedTo);
        void onError(String message);
    }

    /** 下载落盘目录: 蓝图广场专用新文件夹 {gameDir}/prefab-mcs/ (与 prefab-download 分开) */
    public static Path downloadDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("prefab-mcs");
    }

    /** 扫描 prefab-mcs/ 里已下载的建筑 (供详情界面 / 已下载列表用). */
    public static List<com.prefab.addon.extension.LocalBuilding> scanDownloaded() {
        return com.prefab.addon.extension.LocalBuildingScanner.scanDir(downloadDir(), "mcs");
    }

    /** 按 sourceFormat 推导文件扩展名. */
    public static String fileExtFor(String sourceFormat) {
        if (sourceFormat == null) return ".litematic";
        return switch (sourceFormat.toLowerCase()) {
            case "litematica" -> ".litematic";
            case "create" -> ".schem";
            case "sponge", "schem" -> ".schem";
            case "schematic" -> ".schematic";
            case "vanilla", "structure", "nbt" -> ".nbt";
            default -> ".litematic";
        };
    }

    /** 该蓝图是否已下载到 prefab-mcs/ (按确定性文件名判断). */
    public static boolean isDownloaded(Blueprint b) {
        String ext = fileExtFor(b.sourceFormat);
        Path base = downloadDir().resolve(b.fileBase() + ext);
        if (Files.isRegularFile(base)) return true;
        for (int i = 2; i <= 9; i++) {
            if (Files.isRegularFile(downloadDir().resolve(b.fileBase() + "_" + i + ext))) return true;
        }
        return false;
    }

    /**
     * 异步下载蓝图到 prefab-mcs/, 同时写同名 .txt 元信息 + .png/.jpg/.webp 预览图.
     * 完成/失败会自动在主线程回调 cb.onSuccess / cb.onError.
     */
    public static CompletableFuture<Path> downloadAsync(Blueprint b, DownloadCallback cb) {
        CompletableFuture<Path> fut = CompletableFuture.supplyAsync(() -> {
            try {
                return doDownload(b, cb);
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage() == null ? "下载失败" : e.getMessage(), e);
            }
        }, IO);
        if (cb != null) {
            fut.whenComplete((saved, err) -> Minecraft.getInstance().execute(() -> {
                if (err != null) {
                    Throwable t = err.getCause() != null ? err.getCause() : err;
                    cb.onError(t.getMessage() == null ? "下载失败" : t.getMessage());
                } else {
                    cb.onSuccess(saved);
                }
            }));
        }
        return fut;
    }

    /** 下载主体 (IO 线程执行): 拉文件 → 落盘 → 写元信息 txt → 存预览图. */
    private static Path doDownload(Blueprint b, DownloadCallback cb) throws Exception {
        Path dir = downloadDir();
        Files.createDirectories(dir);
        String ext = fileExtFor(b.sourceFormat);
        String base = b.fileBase();
        Path target = dir.resolve(base + ext);
        // 重名避让: base_2, base_3 ...
        for (int i = 2; i <= 99 && Files.exists(target); i++) {
            target = dir.resolve(base + "_" + i + ext);
            base = target.getFileName().toString().substring(0, target.getFileName().toString().length() - ext.length());
        }
        String fileName = target.getFileName().toString();
        String finalBase = fileName.substring(0, fileName.length() - ext.length());

        String url = BASE + "/blueprints/" + b.id + "/versions/" + b.version + "/download";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .GET().build();
        HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() == 429) throw new RuntimeException("请求过于频繁, 请稍后再试");
        if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());
        long total = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);

        ByteArrayOutputStream buf = new ByteArrayOutputStream(1 << 16);
        byte[] chunk = new byte[8192];
        long done = 0;
        try (InputStream in = resp.body()) {
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                done += n;
                if (cb != null && total > 0) {
                    final long fDone = done;
                    Minecraft.getInstance().execute(() -> cb.onProgress(fDone, total));
                }
            }
        }
        byte[] data = buf.toByteArray();
        if (data.length == 0) throw new RuntimeException("下载内容为空");
        Files.write(target, data);
        Path saved = target;

        // 元信息 .txt (LocalBuildingScanner 解析这些 key)
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("建筑名: ").append(b.title == null ? finalBase : b.title).append('\n');
            if (b.author != null && !b.author.isEmpty()) sb.append("作者: ").append(b.author).append('\n');
            if (b.category != null && !b.category.isEmpty()) sb.append("分类: ").append(b.category).append('\n');
            if (b.description != null && !b.description.isEmpty()) sb.append("描述: ").append(b.description).append('\n');
            // 依赖: 网站 namespaces (已过滤 minecraft), DependencyChecker 会校验 modid 是否存在
            if (b.namespaces != null && !b.namespaces.isEmpty()) {
                sb.append("依赖: ").append(String.join(",", b.namespaces)).append('\n');
            }
            sb.append("来源: mcschematic.top ").append(b.webUrl()).append('\n');
            Files.writeString(dir.resolve(finalBase + ".txt"), sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            PrefabCustomAddon.LOGGER.warn("[MCS] 元信息写入失败: {}", ex.getMessage());
        }

        // 预览图 (详情/列表缩略图用). 网站预览图实测是 webp (RIFF....WEBP), 按魔数定扩展名
        if (b.previewUrl != null && !b.previewUrl.isEmpty()) {
            try {
                byte[] img = httpGetBytes(b.previewUrl);
                if (img != null && img.length > 12) {
                    String imgExt;
                    if ((img[0] & 0xFF) == 0xFF && (img[1] & 0xFF) == 0xD8) imgExt = ".jpg";
                    else if ((img[0] & 0xFF) == 0x89 && img[1] == 'P') imgExt = ".png";
                    else if (img[0] == 'R' && img[1] == 'I' && img[2] == 'F' && img[3] == 'F'
                        && img[8] == 'W' && img[9] == 'E' && img[10] == 'B' && img[11] == 'P') imgExt = ".webp";
                    else imgExt = ".webp";
                    Files.write(dir.resolve(finalBase + imgExt), img);
                }
            } catch (Exception ex) {
                PrefabCustomAddon.LOGGER.warn("[MCS] 预览图保存失败: {}", ex.getMessage());
            }
        }
        return saved;
    }

    // ==================== 工具 ====================

    private static byte[] httpGetBytes(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .GET().build();
        HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());
        return resp.body();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull() || !o.get(key).isJsonPrimitive()) return null;
        return o.get(key).getAsString();
    }

    private static JsonObject obj(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonObject()) return null;
        return o.getAsJsonObject(key);
    }

    /** 清洗文件名: 去掉 Windows/通用非法字符与控制符, 限长 40. */
    private static String sanitizeFileName(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if ("\\/:*?\"<>|".indexOf(c) >= 0 || c < 32) continue;
            sb.append(c);
            if (sb.length() >= 40) break;
        }
        return sb.toString().trim();
    }
}
