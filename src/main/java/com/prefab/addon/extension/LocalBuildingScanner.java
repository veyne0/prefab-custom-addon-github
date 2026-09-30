package com.prefab.addon.extension;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.work.DependencyChecker;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 扫描本地 prefab-extension/ 和 prefab-download/ 里的单文件建筑.
 *
 * <p>每个单文件建筑由同前缀 (不含扩展名) 的 3 个文件组成:
 * <ul>
 *   <li>建筑文件: .nbt / .schem / .litematic (必选, 否则不算有效建筑)</li>
 *   <li>元信息: .txt (可选, 缺省时用文件名作为 name)</li>
 *   <li>预览图: .png / .jpg / .jpeg / .gif / .webp (可选)</li>
 * </ul>
 * </p>
 *
 * <p>扫描结果按 id (即文件名) 去重, 同名建筑优先用 prefab-extension/ 里的 (玩家本地副本优先).</p>
 */
public class LocalBuildingScanner {

    private static final List<String> BUILDING_EXTS = Arrays.asList(".nbt", ".schem", ".litematic");
    private static final List<String> IMAGE_EXTS = Arrays.asList(".png", ".jpg", ".jpeg", ".gif", ".webp");

    /**
     * 蓝图专属建筑子目录 ({@code prefab-extension/blueprint-buildings/}).
     * "制作蓝图" 生成流程把建筑写到这里; 本目录<b>不进任何建筑列表</b>
     * (scanAll/浏览器/收藏/蓝图选择都不显示), 只供蓝图绑定链路按 constructionId 直读.
     */
    public static final String BLUEPRINT_BUILDINGS_DIR = "blueprint-buildings";

    /** 蓝图专属建筑目录: {@code <prefab-extension>/blueprint-buildings/} (不存在不自动创建, 写入方自建). */
    public static Path getBlueprintBuildingsRoot() {
        return getExtensionRoot().resolve(BLUEPRINT_BUILDINGS_DIR);
    }

    /** 旧根目录蓝图建筑迁移是否已跑过 (每次游戏会话一次). */
    private static boolean blueprintMigrationDone = false;

    /**
     * 旧文件迁移: "制作蓝图" 以前把生成的建筑 NBT 写到 prefab-extension/ 根目录,
     * 导致它们出现在建筑选择列表里. 生成流程现在改写 {@link #BLUEPRINT_BUILDINGS_DIR},
     * 本方法把 {@code .blueprint-specs/*.json} 里 constructionId 点名的根目录建筑文件
     * (建筑 + 同名 .txt/.png 等元信息) 移入 blueprint-buildings/.
     *
     * <p>只动规格文件里点名的 id — 玩家手放的普通建筑不在规格里, 不会被碰.
     * 每次会话只执行一次 (静态 boolean 防重复); 失败只 warn, 不阻断扫描/生成.</p>
     */
    public static synchronized void migrateLegacyBlueprintBuildings() {
        if (blueprintMigrationDone) return;
        blueprintMigrationDone = true;
        try {
            Path extRoot = getExtensionRoot();
            // 规格目录可能有两个解析根: BlueprintSpecStore 用 user.dir 推导 (common-safe),
            // 本类用 gameDirectory + versions 回退. 两处都扫, 合并 constructionId, 防漏.
            java.util.Set<String> ids = new java.util.HashSet<>();
            java.util.List<Path> specDirs = new ArrayList<>();
            specDirs.add(extRoot.resolve(".blueprint-specs"));
            try {
                Path alt = com.prefab.addon.download.PackDownloadManager.getExtensionRoot()
                    .resolve(".blueprint-specs");
                if (!alt.equals(specDirs.get(0))) specDirs.add(alt);
            } catch (Throwable ignored) {}
            for (Path specsDir : specDirs) {
                if (!Files.isDirectory(specsDir)) continue;
                try (Stream<Path> s = Files.list(specsDir)) {
                    for (Path p : s.filter(f -> f.getFileName().toString().toLowerCase().endsWith(".json"))
                            .collect(Collectors.toList())) {
                        try {
                            String json = Files.readString(p, StandardCharsets.UTF_8);
                            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                            if (o.has("constructionId")) {
                                String cid = o.get("constructionId").getAsString();
                                if (cid != null && !cid.isEmpty()) ids.add(cid);
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
            if (ids.isEmpty()) return;
            Path bpRoot = extRoot.resolve(BLUEPRINT_BUILDINGS_DIR);
            int moved = 0;
            for (String id : ids) {
                moved += moveBuildingFilesIfExists(extRoot, bpRoot, id);
            }
            if (moved > 0) {
                PrefabCustomAddon.LOGGER.info("[BP-MIGRATE] 已把 {} 个蓝图生成建筑迁入 {}", moved, bpRoot);
            }
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[BP-MIGRATE] 迁移失败: {}", t.toString());
        }
    }

    /**
     * 把 {@code <dir>/<id>.(nbt|schem|litematic|txt|png|...)} 移到 {@code <targetDir>/} 下
     * (目标已存在则跳过, 不覆盖). 源目录里该 id 一个文件都没有时不动. 返回实际移动的文件数.
     */
    private static int moveBuildingFilesIfExists(Path dir, Path targetDir, String id) {
        List<String> allExts = new ArrayList<>(BUILDING_EXTS);
        allExts.add(".txt");
        allExts.addAll(IMAGE_EXTS);
        try {
            boolean any = false;
            for (String ext : allExts) {
                if (Files.isRegularFile(dir.resolve(id + ext))) { any = true; break; }
            }
            if (!any) return 0;
            Files.createDirectories(targetDir);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[BP-MIGRATE] 准备迁移 '{}' 失败: {}", id, t.toString());
            return 0;
        }
        int moved = 0;
        for (String ext : allExts) {
            Path src = dir.resolve(id + ext);
            if (!Files.isRegularFile(src)) continue;
            Path dst = targetDir.resolve(id + ext);
            if (Files.exists(dst)) continue;
            try {
                Files.move(src, dst);
                moved++;
            } catch (IOException e) {
                PrefabCustomAddon.LOGGER.warn("[BP-MIGRATE] 移动 {} 失败: {}", src, e.getMessage());
            }
        }
        return moved;
    }

    /**
     * 蓝图专属建筑兜底查找: blueprint-buildings/ 不进任何列表, 按 constructionId 直读该目录
     * 构造 ConstructionInfo (元信息从同名 .txt 解析, 复用 scanDir 逻辑). 找不到返回 null.
     *
     * <p>调用方: 客户端右键蓝图解析链路 ({@code CustomBlueprintClientHandler}) 和
     * {@code ExtensionPackManager.findLocalBuildingFallback} (服务端 placeStructure 兜底).</p>
     */
    public static ConstructionInfo findBlueprintBuilding(String constructionId) {
        if (constructionId == null || constructionId.isEmpty()) return null;
        migrateLegacyBlueprintBuildings();
        try {
            for (LocalBuilding lb : scanDir(getBlueprintBuildingsRoot(), "blueprint")) {
                if (!constructionId.equals(lb.id)) continue;
                ConstructionInfo c = new ConstructionInfo(lb.id);
                c.setName(lb.name);
                c.setAuthor(lb.author);
                c.setDescription(lb.description);
                c.setDependencies(lb.dependencies);
                c.setCategory(lb.category);
                if (lb.fileExt != null && !lb.fileExt.isEmpty()) {
                    c.setFormat(lb.fileExt.startsWith(".") ? lb.fileExt.substring(1) : lb.fileExt);
                }
                c.setLocalImagePath(lb.imagePath);
                c.setLocalNbtPath(lb.filePath);
                return c;
            }
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[BP-FALLBACK] 查找蓝图建筑 '{}' 失败: {}", constructionId, t.toString());
        }
        return null;
    }

    /**
     * 删除 blueprint-buildings/ 里的指定建筑 (蓝图管理 tab 删除蓝图时联动).
     * 只删该目录里的文件 — prefab-extension 根目录的普通建筑不受影响. 返回删除的文件数.
     */
    public static int deleteBlueprintBuilding(String constructionId) {
        if (constructionId == null || constructionId.isEmpty()) return 0;
        List<String> allExts = new ArrayList<>(BUILDING_EXTS);
        allExts.add(".txt");
        allExts.addAll(IMAGE_EXTS);
        int deleted = 0;
        try {
            Path bpRoot = getBlueprintBuildingsRoot();
            for (String ext : allExts) {
                try {
                    if (Files.deleteIfExists(bpRoot.resolve(constructionId + ext))) deleted++;
                } catch (IOException e) {
                    PrefabCustomAddon.LOGGER.warn("[BP-DELETE] 删除 {} 失败: {}", constructionId + ext, e.getMessage());
                }
            }
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[BP-DELETE] 删除蓝图建筑 '{}' 失败: {}", constructionId, t.toString());
        }
        return deleted;
    }

    /** 获取 prefab-download/ 根目录 (位于 .minecraft/prefab-download/). */
    public static Path getDownloadRoot() {
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        return gameDir.resolve("prefab-download");
    }

    /** 获取老拓展包工作区根目录 (prefab-work/), 兼容旧版本. */
    public static Path getWorkRoot() {
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        return gameDir.resolve("prefab-work");
    }

    /** 获取 prefab-extension/ 根目录. 优先 .minecraft/prefab-extension/, 找不到时回退到 versions/<ver>/prefab-extension/. */
    public static Path getExtensionRoot() {
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        Path rootExt = gameDir.resolve("prefab-extension");
        if (Files.exists(rootExt)) return rootExt;
        Path versionsDir = gameDir.resolve("versions");
        if (Files.exists(versionsDir) && Files.isDirectory(versionsDir)) {
            try (Stream<Path> stream = Files.list(versionsDir)) {
                List<Path> candidates = stream
                    .filter(Files::isDirectory)
                    .map(d -> d.resolve("prefab-extension"))
                    .filter(Files::exists)
                    .filter(Files::isDirectory)
                    .collect(Collectors.toList());
                if (candidates.isEmpty()) return rootExt;
                // 1) 优先: 跟当前 gameDir 同名的版本子目录
                String currentVersionName = gameDir.getFileName().toString();
                if (currentVersionName.matches(".*\\d.*") || currentVersionName.toLowerCase().contains("forge")
                        || currentVersionName.toLowerCase().contains("fabric") || currentVersionName.toLowerCase().contains("neoforge")) {
                    for (Path c : candidates) {
                        if (c.getParent().getFileName().toString().equals(currentVersionName)) {
                            return c;
                        }
                    }
                }
                // 2) 兜底: mtime 最新 (玩家最近在玩的版本)
                return candidates.stream()
                    .max((a, b) -> {
                        try {
                            long ma = Files.getLastModifiedTime(a).toMillis();
                            long mb = Files.getLastModifiedTime(b).toMillis();
                            return Long.compare(ma, mb);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .orElse(candidates.get(0));
            } catch (IOException e) {
                PrefabCustomAddon.LOGGER.warn("扫描 versions 失败: {}", e.getMessage());
            }
        }
        return rootExt;
    }

    /**
     * 扫描指定目录下所有单文件建筑.
     * @param dir 目录 (prefab-extension/ 或 prefab-download/), 不会自动创建
     * @param source 标签: "extension" / "download" / "server-cache"
     */
    public static List<LocalBuilding> scanDir(Path dir, String source) {
        List<LocalBuilding> result = new ArrayList<>();
        if (dir == null || !Files.exists(dir) || !Files.isDirectory(dir)) {
            return result;
        }

        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = stream.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toList());

            // 按 id (文件名不带扩展名) 聚合
            Map<String, Path> fileById = new HashMap<>();
            Map<String, Path> infoById = new HashMap<>();
            Map<String, Path> imageById = new HashMap<>();
            Map<String, Long> fileSizeById = new HashMap<>();
            Map<String, String> fileExtById = new HashMap<>();
            Map<String, String> imageExtById = new HashMap<>();

            for (Path p : files) {
                String name = p.getFileName().toString();
                int dot = name.lastIndexOf('.');
                if (dot <= 0) continue;
                String id = name.substring(0, dot);
                String ext = name.substring(dot).toLowerCase();

                if (BUILDING_EXTS.contains(ext)) {
                    fileById.put(id, p);
                    fileExtById.put(id, ext);
                    try {
                        fileSizeById.put(id, Files.size(p));
                    } catch (IOException e) {
                        fileSizeById.put(id, 0L);
                    }
                } else if (ext.equals(".txt")) {
                    infoById.put(id, p);
                } else if (IMAGE_EXTS.contains(ext)) {
                    // 同 id 多张图: 优先 .png
                    Path existing = imageById.get(id);
                    if (existing == null || ext.equals(".png")) {
                        imageById.put(id, p);
                        imageExtById.put(id, ext);
                    }
                }
            }

            for (Map.Entry<String, Path> e : fileById.entrySet()) {
                String id = e.getKey();
                Path filePath = e.getValue();
                Path infoPath = infoById.get(id);
                Path imagePath = imageById.get(id);

                // 解析 .txt
                String name = id, author = "", description = "", category = "";
                List<String> dependencies = Collections.emptyList();
                if (infoPath != null) {
                    try {
                        String content = Files.readString(infoPath, StandardCharsets.UTF_8);
                        // 剥 UTF-8 BOM: Windows 记事本保存的 UTF-8 默认带 BOM, Files.readString 不自动剥,
                        // 会让第一行 key 变成 "\uFEFF建筑名", case 不匹配, 整个 name/author/description 解析全部回退到默认
                        if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
                            content = content.substring(1);
                        }
                        for (String line : content.split("\\r?\\n")) {
                            String[] kv = splitKeyValue(line);
                            if (kv == null) continue;
                            String k = kv[0], v = kv[1];
                            switch (k) {
                                case "建筑名", "name" -> { if (!v.isEmpty()) name = v; }
                                case "作者", "author" -> author = v;
                                case "描述", "description", "desc", "说明" -> description = v;
                                case "分类", "category", "类别" -> category = v;
                                case "依赖", "依赖模组", "dependencies", "dependence", "deps" -> {
                                    // 拆分: 逗号/分号/空白 → 数组
                                    if (v.isEmpty()) break;
                                    List<String> raw = new ArrayList<>();
                                    for (String part : v.split("[,;\\s]+")) {
                                        String t = part.trim();
                                        if (!t.isEmpty()) raw.add(t);
                                    }
                                    // 用 DependencyChecker.cleanDepList 去重 + 清理
                                    dependencies = DependencyChecker.cleanDepList(raw);
                                }
                            }
                        }
                    } catch (IOException ex) {
                        PrefabCustomAddon.LOGGER.warn("读 {} 失败: {}", infoPath, ex.getMessage());
                    }
                }

                result.add(new LocalBuilding(
                    id, name, author, description, dependencies, category,
                    fileExtById.get(id), fileSizeById.getOrDefault(id, 0L),
                    imageExtById.get(id), source,
                    filePath, infoPath, imagePath
                ));
            }
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("扫描 {} 失败: {}", dir, e.getMessage(), e);
        }

        return result;
    }

    /**
     * 扫描并合并 prefab-extension/ + prefab-download/ (用于"建筑" tab).
     * 同 id 时优先用 prefab-extension/ 里的 (玩家本地副本优先).
     *
     * <p>兼容老拓展包: 也扫 prefab-work/&lt;packId&gt;/construction/ 下的旧拓展包建筑.</p>
     *
     * <p><b>排除</b>: {@link #BLUEPRINT_BUILDINGS_DIR} 子目录不进任何列表 —
     * scanDir 只列规则文件 (不递归子目录), 蓝图生成建筑天然被跳过;
     * 蓝图链路按 constructionId 走 {@link #findBlueprintBuilding(String)} 直读.
     * 进本方法时顺带跑一次旧根目录蓝图建筑迁移 (会话内仅一次).</p>
     */
    public static List<LocalBuilding> scanAll() {
        migrateLegacyBlueprintBuildings();
        Map<String, LocalBuilding> byId = new HashMap<>();
        // 先扫 prefab-extension/ (本地的优先, 后扫会被覆盖, 所以先扫)
        Path extRoot = getExtensionRoot();
        Path dlRoot = getDownloadRoot();
        Path workRoot = getWorkRoot();
        for (LocalBuilding lb : scanDir(extRoot, "extension")) {
            byId.put(lb.id, lb);
        }
        for (LocalBuilding lb : scanDir(dlRoot, "download")) {
            // prefab-extension 里已有同名建筑, 跳过
            if (!byId.containsKey(lb.id)) {
                byId.put(lb.id, lb);
            }
        }
        // 兼容老拓展包 (prefab-work/<packId>/construction/)
        for (LocalBuilding lb : scanOldWorkPacks(workRoot)) {
            if (!byId.containsKey(lb.id)) {
                byId.put(lb.id, lb);
            }
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * 扫描老拓展包工作区: prefab-work/&lt;packId&gt;/construction/&lt;buildingId&gt;.{nbt,png,txt}
     * 返回的 LocalBuilding.source 标记为 "legacy-pack".
     */
    public static List<LocalBuilding> scanOldWorkPacks(Path workRoot) {
        List<LocalBuilding> result = new ArrayList<>();
        if (workRoot == null || !Files.exists(workRoot) || !Files.isDirectory(workRoot)) {
            return result;
        }
        try (Stream<Path> packs = Files.list(workRoot)) {
            for (Path packDir : packs.filter(Files::isDirectory).collect(Collectors.toList())) {
                Path construction = packDir.resolve("construction");
                if (!Files.exists(construction) || !Files.isDirectory(construction)) continue;
                String packId = packDir.getFileName().toString();
                // 复用 scanDir 解析 (它只扫单层文件, 跟 construction/ 完全一致)
                List<LocalBuilding> inPack = scanDir(construction, "legacy-pack:" + packId);
                result.addAll(inPack);
            }
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.warn("[DIAG-LBS] 扫描老拓展包失败: {}", e.getMessage());
        }
        return result;
    }

    /** 把一行 "key: value" / "key：value" 切成 [key, value], 失败返回 null. */
    private static String[] splitKeyValue(String line) {
        if (line == null) return null;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return null;
        int idx = trimmed.indexOf(':');
        if (idx < 0) idx = trimmed.indexOf('：');
        if (idx <= 0) return null;
        String k = trimmed.substring(0, idx).trim();
        String v = trimmed.substring(idx + 1).trim();
        if (k.isEmpty()) return null;
        return new String[]{k, v};
    }
}
