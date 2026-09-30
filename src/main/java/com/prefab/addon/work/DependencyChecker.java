package com.prefab.addon.work;

import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.client.Minecraft;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 扫描 .minecraft/mods/ 下的所有 mod jar, 提取 modid, 用于检测依赖是否齐全。
 *
 * 兼容:
 *   - Forge / NeoForge: META-INF/mods.toml, META-INF/neoforge.mods.toml (TOML)
 *   - Fabric:           META-INF/fabric.mod.json
 *   - Quilt:           META-INF/quilt.mod.json
 *   - LiteLoader:      liteloader.mod.json
 *
 * 简单做法: 解析常见 manifest 文件; 失败时退化为 jar 文件名启发式匹配 (modid-version.jar)。
 */
public class DependencyChecker {

    /** 单个 mod 的解析结果 */
    public static class ModInfo {
        public final String modId;
        public final String fileName;
        public ModInfo(String modId, String fileName) {
            this.modId = modId;
            this.fileName = fileName;
        }
        @Override
        public String toString() { return modId + " (" + fileName + ")"; }
    }

    /** 检测结果 */
    public static class CheckResult {
        public final List<String> missing;   // 缺失的 modid
        public final List<String> present;   // 已安装的 modid
        public final Set<String> scannedIds; // 扫描到的所有 modid
        public final String summary;         // 一句话总结 (用于状态栏)

        public CheckResult(List<String> missing, List<String> present, Set<String> scannedIds, String summary) {
            this.missing = missing;
            this.present = present;
            this.scannedIds = scannedIds;
            this.summary = summary;
        }
    }

    private static final Pattern TOML_MOD_ID_LINE = Pattern.compile("^\\s*modId\\s*=\\s*\"([^\"]+)\"", Pattern.MULTILINE);
    private static final Pattern JSON_MOD_ID_FIELD = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    // 兜底: jar 文件名 modid-version.jar
    private static final Pattern FILE_NAME_MODID = Pattern.compile("^([a-z0-9_\\-\\.]+?)(?:-[0-9].*)?\\.jar$", Pattern.CASE_INSENSITIVE);

    /** 模组别名映射 (NBT 用的 modid 可能与玩家装的 jar modid 不同名)
     *  key = NBT 用的原 modid
     *  value = 玩家可能装的其他 modid (任一命中算依赖满足) */
    private static final Map<String, Set<String>> ALIASES = new HashMap<>();
    static {
        // Create: Aeronautics - 源码包叫 "simulated", 实际发布的 jar 用 aeronautics / create_aeronautics / createaeronautics
        ALIASES.put("simulated",           Set.of("aeronautics", "create_aeronautics", "createaeronautics"));
        ALIASES.put("aeronautics",         Set.of("simulated"));
        ALIASES.put("create_aeronautics",  Set.of("simulated", "aeronautics", "createaeronautics"));
        ALIASES.put("createaeronautics",   Set.of("simulated", "aeronautics", "create_aeronautics"));
        // Create 家族
        ALIASES.put("create",              Set.of("createplus", "create_centralized_kitchen", "createaddition"));
        ALIASES.put("createplus",          Set.of("create"));
        // JEI
        ALIASES.put("jei",                 Set.of("jei_1.21.1", "jei-1.21.1"));
        // Architectury
        ALIASES.put("architectury",        Set.of("architecture_api", "architectury_api"));
    }

    /** 模组显示名映射 (modid → 中文/英文友好名)
     *  注意: 不同的 modid 映射到不同的显示名, 否则 "simulated" 和 "aeronautics"
     *  都会显示成 "Create: Aeronautics", 玩家以为重复了.
     */
    private static final Map<String, String> MOD_DISPLAY_NAMES = new HashMap<>();
    static {
        // 关键: simulated (Create: Simulated) 和 aeronautics (Create: Aeronautics) 是两个独立 mod.
        // 之前都映射到 "Create: Aeronautics", 玩家看到两次以为是 bug.
        // 修正: 给它们不同的友好名, 玩家能区分.
        MOD_DISPLAY_NAMES.put("simulated", "Create: Simulated");
        MOD_DISPLAY_NAMES.put("aeronautics", "Create: Aeronautics");
        MOD_DISPLAY_NAMES.put("create_aeronautics", "Create: Aeronautics");
        MOD_DISPLAY_NAMES.put("createaeronautics", "Create: Aeronautics");
        MOD_DISPLAY_NAMES.put("create", "Create (机械动力)");
        MOD_DISPLAY_NAMES.put("createplus", "Create Plus");
        MOD_DISPLAY_NAMES.put("createframed", "Create Framed");
        MOD_DISPLAY_NAMES.put("create_aquatic_ambitions", "Create: Aquatic Ambitions");
        MOD_DISPLAY_NAMES.put("createa_aquatic_ambitions", "Create: Aquatic Ambitions");
        MOD_DISPLAY_NAMES.put("prefab", "Prefab (预制建筑)");
        MOD_DISPLAY_NAMES.put("jei", "JEI (物品管理器)");
        MOD_DISPLAY_NAMES.put("architectury", "Architecture API");
        MOD_DISPLAY_NAMES.put("quark", "Quark");
        MOD_DISPLAY_NAMES.put("yuushya", "Yuushya");
    }

    /**
     * 把 modid 清理成"纯 modid"用于匹配. 处理用户常见的几种脏数据:
     * <ul>
     *   <li>末尾的作者/标签/版本:  "simulated yuuShu8" → "simulated"</li>
     *   <li>中英混写的"(...)" 注释: "Create (机械动力)" → "create"</li>
     *   <li>命名空间前缀 modid:  "create:brass" → "create" (取冒号前)</li>
     * </ul>
     */
    public static String cleanModId(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";
        // 1) 去掉命名空间前缀 (modid 取冒号前)
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);
        // 2) 去掉 " (xxx)" 中文注释部分 - 例如 "Create (机械动力)" → "Create"
        int paren = s.indexOf(" (");
        if (paren > 0) s = s.substring(0, paren);
        // 3) 去掉空格后的附加内容 (作者名/标签)
        int space = s.indexOf(' ');
        if (space > 0) s = s.substring(0, space);
        return s.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 把 modid 列表去重, 同时清理每个 modid (去除作者注释等)
     */
    public static List<String> cleanDepList(List<String> deps) {
        if (deps == null) return java.util.Collections.emptyList();
        List<String> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String d : deps) {
            String cleaned = cleanModId(d);
            if (cleaned.isEmpty()) continue;
            if (seen.add(cleaned)) {
                out.add(cleaned);
            }
        }
        return out;
    }

    /** 把 modid 翻译成友好显示名 */
    public static String displayName(String modId) {
        if (modId == null) return "?";
        String key = cleanModId(modId);
        if (key.isEmpty()) return modId;
        return MOD_DISPLAY_NAMES.getOrDefault(key, modId);
    }

    /** 获取 mods 目录 (.minecraft/mods/) */
    public static Path getModsDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("mods");
    }

    /**
     * 扫描 mods 目录, 返回所有 jar 提取出的 modid 集合 (lowercase, 去重)。
     */
    public static Set<String> scanInstalledModIds() {
        Set<String> ids = new HashSet<>();
        Path modsDir = getModsDir();
        PrefabCustomAddon.LOGGER.info("[DEPS] scanning mods dir: {}", modsDir);
        if (!Files.exists(modsDir) || !Files.isDirectory(modsDir)) {
            PrefabCustomAddon.LOGGER.warn("[DEPS] mods dir missing or not a dir: {}", modsDir);
            return ids;
        }
        try (var stream = Files.list(modsDir)) {
            List<Path> jars = stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList();
            PrefabCustomAddon.LOGGER.info("[DEPS] found {} jar(s) in mods/", jars.size());
            for (Path jar : jars) {
                try {
                    List<String> modIds = extractModIdsFromJar(jar);
                    for (String id : modIds) {
                        if (id != null && !id.isEmpty()) {
                            ids.add(id.toLowerCase(Locale.ROOT));
                        }
                    }
                    PrefabCustomAddon.LOGGER.info("[DEPS]   {} -> {}", jar.getFileName(), modIds);
                } catch (Throwable t) {
                    PrefabCustomAddon.LOGGER.warn("[DEPS] failed to scan {}: {}", jar.getFileName(), t.getMessage());
                }
            }
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("[DEPS] failed to list mods dir", e);
        }
        PrefabCustomAddon.LOGGER.info("[DEPS] total scanned mod ids: {}", ids.size());
        return ids;
    }

    /**
     * 从单个 jar 中提取 modid 列表 (尝试多种 manifest)。
     */
    public static List<String> extractModIdsFromJar(Path jar) {
        List<String> ids = new ArrayList<>();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            // 1) neoforge.mods.toml
            readTomlModIds(zf, "META-INF/neoforge.mods.toml", ids);
            // 2) mods.toml (Forge)
            readTomlModIds(zf, "META-INF/mods.toml", ids);
            // 3) fabric.mod.json
            readJsonModIds(zf, "META-INF/fabric.mod.json", ids);
            // 4) quilt.mod.json
            readJsonModIds(zf, "META-INF/quilt.mod.json", ids);
            // 5) liteloader.mod.json
            readJsonModIds(zf, "liteloader.mod.json", ids);
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.warn("[DEPS] failed to open jar {}: {}", jar, e.getMessage());
        }
        // 兜底: 文件名启发式
        if (ids.isEmpty()) {
            String name = jar.getFileName().toString();
            Matcher m = FILE_NAME_MODID.matcher(name);
            if (m.find()) {
                ids.add(m.group(1).toLowerCase(Locale.ROOT));
            }
        }
        return ids;
    }

    private static void readTomlModIds(ZipFile zf, String entryName, List<String> out) {
        ZipEntry e = zf.getEntry(entryName);
        if (e == null) return;
        try (InputStream is = zf.getInputStream(e);
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            Matcher m = TOML_MOD_ID_LINE.matcher(sb);
            while (m.find()) {
                out.add(m.group(1));
            }
        } catch (IOException ex) {
            PrefabCustomAddon.LOGGER.warn("[DEPS] failed to read {} from jar: {}", entryName, ex.getMessage());
        }
    }

    private static void readJsonModIds(ZipFile zf, String entryName, List<String> out) {
        ZipEntry e = zf.getEntry(entryName);
        if (e == null) return;
        try (InputStream is = zf.getInputStream(e)) {
            byte[] data = is.readAllBytes();
            String s = new String(data, StandardCharsets.UTF_8);
            Matcher m = JSON_MOD_ID_FIELD.matcher(s);
            // fabric/quilt 一个文件可能含多个 mod (数组), 这里先取第一个 id 字段
            // 注: 数组里多个 mod, 会有多个 "id" 字段, 一并加入
            while (m.find()) {
                out.add(m.group(1));
            }
        } catch (IOException ex) {
            PrefabCustomAddon.LOGGER.warn("[DEPS] failed to read {} from jar: {}", entryName, ex.getMessage());
        }
    }

    /**
     * 检查依赖列表 (modid) 哪些缺失, 哪些已存在。
     */
    public static CheckResult check(List<String> dependencies) {
        Set<String> scanned = scanInstalledModIds();
        // 用 cleanDepList 去重 + 清理 (去掉作者注释等)
        List<String> deps = cleanDepList(dependencies);
        List<String> missing = new ArrayList<>();
        List<String> present = new ArrayList<>();
        for (String d : deps) {
            String key = d;  // cleanDepList 已经把 d 处理成纯 modid
            if (key == null || key.isBlank()) continue;
            // 兼容: "modid" 或 "modid:version" (curseforge 风格) - 只匹配 modid 部分
            int colon = key.indexOf(':');
            if (colon >= 0) key = key.substring(0, colon);
            // 1) 直接命中
            boolean hit = scanned.contains(key);
            // 2) 别名命中 (modid 重命名/分叉)
            if (!hit) {
                Set<String> aliases = ALIASES.get(key);
                if (aliases != null) {
                    for (String a : aliases) {
                        if (scanned.contains(a)) { hit = true; break; }
                    }
                }
            }
            // 3) 反向别名 (用户的 jar 是 alias, NBT 用了原名)
            if (!hit) {
                for (Map.Entry<String, Set<String>> e : ALIASES.entrySet()) {
                    if (e.getValue().contains(key) && scanned.contains(e.getKey())) {
                        hit = true; break;
                    }
                }
            }
            if (hit) {
                present.add(d);
            } else {
                missing.add(d);
            }
        }
        String summary;
        if (deps.isEmpty()) {
            summary = "无依赖需要检测";
        } else if (missing.isEmpty()) {
            summary = "✓ 所有 " + present.size() + " 个依赖均已安装";
        } else {
            summary = "✗ 缺少 " + missing.size() + "/" + deps.size() + " 个依赖: " + String.join(", ", missing);
        }
        PrefabCustomAddon.LOGGER.info("[DEPS] check: scanned={} present={} missing={}", scanned.size(), present.size(), missing.size());
        return new CheckResult(missing, present, scanned, summary);
    }
}
