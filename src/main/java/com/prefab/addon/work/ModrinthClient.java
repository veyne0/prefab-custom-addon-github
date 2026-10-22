package com.prefab.addon.work;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.SharedConstants;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Modrinth API 客户端 (免 key, 3 秒超时, 失败优雅降级):
 * <ol>
 *   <li>slug 精确查询:  GET /v2/projects?ids=["create-connected"] (依赖 id 的连字符变体一并尝试)</li>
 *   <li>文本兜底搜索:  GET /v2/search?query=..&facets=[project_type:mod, 版本, loader]</li>
 * </ol>
 * 结果按查询词缓存 (进程内); 网络失败返回空列表, 不抛异常 (国内网络连不上 Modrinth 时功能静默失效).
 */
public final class ModrinthClient {

    /** Modrinth 项目 (搜索结果行) */
    public static class Project {
        public final String slug;
        public final String title;
        public final String description;
        public final String iconUrl;
        public final long downloads;

        public Project(String slug, String title, String description, String iconUrl, long downloads) {
            this.slug = slug == null ? "" : slug;
            this.title = title == null ? slug : title;
            this.description = description == null ? "" : description;
            this.iconUrl = iconUrl == null ? "" : iconUrl;
            this.downloads = downloads;
        }

        /** 项目页面 (modrinth.com/mod/&lt;slug&gt;) */
        public String pageUrl() {
            return "https://modrinth.com/mod/" + slug;
        }
    }

    private static final String API = "https://api.modrinth.com/v2";
    private static final String USER_AGENT = "PrefabCustomAddon/1.0 (Minecraft mod)";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Map<String, List<Project>> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> INFLIGHT = ConcurrentHashMap.newKeySet();

    /**
     * 已知 modid → Modrinth slug 别名表。
     * 覆盖缩写 (cfm) / 连写 (createoreexcavation) 等无法由算法推导的情况, 命中后直接精确查询。
     * 注意: 只收录确认在 Modrinth 上的; 纯 CurseForge 模组 (如 cfm/MrCrayfish 家具) 不收, 让它走 PCL 提示。
     */
    private static final Map<String, String> SLUG_ALIASES = Map.ofEntries(
            Map.entry("createoreexcavation", "create-ore-excavation")
    );

    private ModrinthClient() {}

    /** 该查询词是否正在请求中 (调用方据此显示"查询中...") */
    public static boolean isBusy(String depId) {
        return INFLIGHT.contains(normalizeKey(depId));
    }

    /** 已缓存的结果 (无缓存返回空列表) */
    public static List<Project> cached(String depId) {
        List<Project> hit = CACHE.get(normalizeKey(depId));
        return hit == null ? List.of() : hit;
    }

    /**
     * 搜索依赖: slug 精确 → 空则文本兜底. 永不抛异常, 网络失败返回空列表.
     */
    public static CompletableFuture<List<Project>> search(String depId) {
        final String key = normalizeKey(depId);
        if (key.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        List<Project> hit = CACHE.get(key);
        if (hit != null) {
            return CompletableFuture.completedFuture(hit);
        }
        if (!INFLIGHT.add(key)) {
            return CompletableFuture.completedFuture(List.of());
        }
        return slugLookup(key)
                .thenCompose(list -> list.isEmpty() ? textSearch(key) : CompletableFuture.completedFuture(list))
                .whenComplete((r, e) -> {
                    INFLIGHT.remove(key);
                    if (r != null && !r.isEmpty()) {
                        CACHE.put(key, r);
                    }
                });
    }

    /** 拉取项目图标字节 (3 秒超时, 失败返回 null) */
    public static CompletableFuture<byte[]> fetchIcon(String url) {
        if (url == null || url.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(3))
                        .header("User-Agent", USER_AGENT)
                        .GET().build();
                HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
                return resp.statusCode() == 200 ? resp.body() : null;
            } catch (Exception e) {
                return null;
            }
        });
    }

    // === 内部 ===

    private static String normalizeKey(String depId) {
        if (depId == null) return "";
        int colon = depId.indexOf(':');
        if (colon >= 0) depId = depId.substring(0, colon);
        return depId.trim().toLowerCase(Locale.ROOT);
    }

    private static CompletableFuture<List<Project>> slugLookup(String key) {
        return CompletableFuture.supplyAsync(() -> {
            // slug 候选: 别名表 → 原样 / 下划线→连字符 / 连字符→下划线
            // 逐个单独查询 (批量请求在某些网络环境下不稳定, 单请求最可靠)
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            String alias = SLUG_ALIASES.get(key);
            if (alias != null) {
                ids.add(alias);
            }
            ids.add(key);
            ids.add(key.replace('_', '-'));
            ids.add(key.replace('-', '_'));
            for (String id : ids) {
                String body = getJson(API + "/project/" + URLEncoder.encode(id, StandardCharsets.UTF_8));
                if (body == null) continue;
                try {
                    JsonObject o = JsonParser.parseString(body).getAsJsonObject();
                    List<Project> parsed = parseProjects(singleToArray(o));
                    if (!parsed.isEmpty()) return parsed;
                } catch (Exception e) {
                    PrefabCustomAddon.LOGGER.warn("[MODRINTH] slug parse failed for {}: {}", id, e.getMessage());
                }
            }
            return List.<Project>of();
        });
    }

    private static JsonArray singleToArray(JsonObject o) {
        JsonArray arr = new JsonArray();
        arr.add(o);
        return arr;
    }

    private static CompletableFuture<List<Project>> textSearch(String key) {
        return CompletableFuture.supplyAsync(() -> {
            // 查询词变体: create_connected → "create connected" / "createconnected"
            // (Modrinth 文本索引对下划线 token 匹配差, 转空格才能命中 "Create: Connected")
            LinkedHashSet<String> queries = new LinkedHashSet<>();
            queries.add(key);
            if (key.contains("_")) {
                queries.add(key.replace('_', ' '));
                queries.add(key.replace("_", ""));
            }
            for (String q : queries) {
                List<Project> hit = searchOnce(q, true);
                if (!hit.isEmpty()) return hit;
            }
            // 最后一搏: 去掉版本/loader 过滤 (有些项目没标注当前 MC 版本)
            for (String q : queries) {
                List<Project> hit = searchOnce(q, false);
                if (!hit.isEmpty()) return hit;
            }
            return List.<Project>of();
        });
    }

    private static List<Project> searchOnce(String query, boolean withFilters) {
        String facets;
        if (withFilters) {
            String ver = SharedConstants.getCurrentVersion().getName();
            facets = "[[\"project_type:mod\"],[\"versions:" + ver + "\"],"
                    + "[\"loaders:neoforge\",\"loaders:forge\"]]";
        } else {
            facets = "[[\"project_type:mod\"]]";
        }
        String url = API + "/search?limit=10&index=relevance"
                + "&query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&facets=" + URLEncoder.encode(facets, StandardCharsets.UTF_8);
        String body = getJson(url);
        if (body == null) return List.of();
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (root.has("hits") && root.get("hits").isJsonArray()) {
                return parseProjects(root.getAsJsonArray("hits"));
            }
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("[MODRINTH] search parse failed: {}", e.getMessage());
        }
        return List.of();
    }

    private static List<Project> parseProjects(JsonArray arr) {
        List<Project> out = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            // search 接口的 hits 含其他类型, 只收 mod
            if (o.has("project_type") && !o.get("project_type").isJsonNull()
                    && !"mod".equals(o.get("project_type").getAsString())) {
                continue;
            }
            String slug = o.has("slug") && !o.get("slug").isJsonNull() ? o.get("slug").getAsString() : "";
            if (slug.isEmpty()) continue;
            String title = o.has("title") && !o.get("title").isJsonNull() ? o.get("title").getAsString() : slug;
            String desc = o.has("description") && !o.get("description").isJsonNull()
                    ? o.get("description").getAsString() : "";
            String icon = o.has("icon_url") && !o.get("icon_url").isJsonNull() ? o.get("icon_url").getAsString() : "";
            long dl = o.has("downloads") && !o.get("downloads").isJsonNull() ? o.get("downloads").getAsLong() : 0;
            out.add(new Project(slug, title, desc, icon, dl));
        }
        return out;
    }

    /** GET, 3 秒超时; 非 200 或异常 → null */
    private static String getJson(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .header("User-Agent", USER_AGENT)
                    .GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                PrefabCustomAddon.LOGGER.warn("[MODRINTH] {} -> HTTP {}", url, resp.statusCode());
                return null;
            }
            return resp.body();
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("[MODRINTH] request failed: {} ({})", url, e.getMessage());
            return null;
        }
    }
}
