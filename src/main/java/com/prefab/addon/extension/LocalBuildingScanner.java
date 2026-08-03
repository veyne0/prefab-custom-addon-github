package com.prefab.addon.extension;

import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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

    /** 获取 prefab-download/ 根目录 (位于 .minecraft/prefab-download/). */
    public static Path getDownloadRoot() {
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        return gameDir.resolve("prefab-download");
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
                String name = id, author = "", description = "";
                if (infoPath != null) {
                    try {
                        String content = Files.readString(infoPath, StandardCharsets.UTF_8);
                        for (String line : content.split("\\r?\\n")) {
                            String[] kv = splitKeyValue(line);
                            if (kv == null) continue;
                            String k = kv[0], v = kv[1];
                            switch (k) {
                                case "建筑名", "name" -> { if (!v.isEmpty()) name = v; }
                                case "作者", "author" -> author = v;
                                case "描述", "description", "desc", "说明" -> description = v;
                            }
                        }
                    } catch (IOException ex) {
                        PrefabCustomAddon.LOGGER.warn("读 {} 失败: {}", infoPath, ex.getMessage());
                    }
                }

                result.add(new LocalBuilding(
                    id, name, author, description,
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
     */
    public static List<LocalBuilding> scanAll() {
        Map<String, LocalBuilding> byId = new HashMap<>();
        // 先扫 prefab-extension/ (本地的优先, 后扫会被覆盖, 所以先扫)
        Path extRoot = getExtensionRoot();
        Path dlRoot = getDownloadRoot();
        PrefabCustomAddon.LOGGER.info("[DIAG-LBS] 扫描 extension root = {}, download root = {}",
            extRoot, dlRoot);
        PrefabCustomAddon.LOGGER.info("[DIAG-LBS]   extRoot 存在? {}, dlRoot 存在? {}",
            Files.exists(extRoot), Files.exists(dlRoot));
        for (LocalBuilding lb : scanDir(extRoot, "extension")) {
            byId.put(lb.id, lb);
        }
        for (LocalBuilding lb : scanDir(dlRoot, "download")) {
            // prefab-extension 里已有同名建筑, 跳过
            if (!byId.containsKey(lb.id)) {
                byId.put(lb.id, lb);
            }
        }
        PrefabCustomAddon.LOGGER.info("[DIAG-LBS] scanAll 总共 {} 个 LocalBuilding", byId.size());
        return new ArrayList<>(byId.values());
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
