package com.prefab.addon.work;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.download.PackDownloadManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 本地工作区管理器 (.minecraft/prefab-work/)
 *
 * 目录结构:
 *   prefab-work/
 *     <packId>/
 *       information/
 *         <packId>.txt        - 元信息 (从 test1.txt 模板来)
 *         拓展包图标.png       - 封面
 *       construction/
 *         <buildingId>.nbt
 *         <buildingId>.png
 *         <buildingId>.txt
 */
public class PackCreator {

    private static final PackCreator INSTANCE = new PackCreator();
    public static PackCreator getInstance() { return INSTANCE; }

    public static Path getWorkRoot() {
        return PackDownloadManager.getExtensionRoot().getParent().resolve("prefab-work");
    }

    /** 拓展包信息 (本地工作区) */
    public static class PackWorkInfo {
        public final String id;
        public final Path dir;
        public final Path infoTxt;
        public final Path coverImage;
        public final String name;
        public final String author;
        public final String version;
        public final String dependencies;
        public final String link;
        public final String description;
        public final int buildingCount;

        public PackWorkInfo(String id, Path dir, String name, String author, String version,
                            String dependencies, String link, String description, int buildingCount) {
            this.id = id;
            this.dir = dir;
            this.infoTxt = dir.resolve("information").resolve(id + ".txt");
            this.coverImage = findCoverImage(dir.resolve("information"));
            this.name = name;
            this.author = author;
            this.version = version;
            this.dependencies = dependencies;
            this.link = link;
            this.description = description;
            this.buildingCount = buildingCount;
        }
    }

    /** 建筑信息 (本地工作区) */
    public static class BuildingWorkInfo {
        public final String id;
        public final Path nbt;
        public final Path png;
        public final Path txt;
        public final String name;
        public final String author;
        public final String size;
        public final String dependencies;
        public final String description;

        public BuildingWorkInfo(String id, Path nbt, Path png, Path txt,
                                String name, String author, String size,
                                String dependencies, String description) {
            this.id = id;
            this.nbt = nbt;
            this.png = png;
            this.txt = txt;
            this.name = name;
            this.author = author;
            this.size = size;
            this.dependencies = dependencies;
            this.description = description;
        }
    }

    /** 扫描工作区, 列出所有拓展包 */
    public List<PackWorkInfo> scanPacks() {
        List<PackWorkInfo> result = new ArrayList<>();
        Path root = getWorkRoot();
        if (!Files.exists(root)) {
            try {
                Files.createDirectories(root);
            } catch (IOException e) {
                PrefabCustomAddon.LOGGER.error("Failed to create prefab-work: {}", e.getMessage());
                return result;
            }
        }
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                  .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                  .forEach(packDir -> {
                      PackWorkInfo info = readPackInfo(packDir);
                      if (info != null) result.add(info);
                  });
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("Scan prefab-work failed: {}", e.getMessage());
        }
        return result;
    }

    /** 读取单个拓展包信息 */
    public PackWorkInfo readPackInfo(Path packDir) {
        String id = packDir.getFileName().toString();
        Path infoDir = packDir.resolve("information");
        Path infoTxt = infoDir.resolve(id + ".txt");
        if (!Files.exists(infoTxt)) return null;

        String content;
        try {
            content = Files.readString(infoTxt, StandardCharsets.UTF_8);
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.warn("Read {} failed: {}", infoTxt, e.getMessage());
            return null;
        }
        Map<String, String> kv = parseInfoText(content);

        int buildingCount = 0;
        Path constructionDir = packDir.resolve("construction");
        if (Files.exists(constructionDir)) {
            try (Stream<Path> s = Files.list(constructionDir)) {
                buildingCount = (int) s.filter(p -> p.toString().endsWith(".txt")).count();
            } catch (IOException ignored) {}
        }

        return new PackWorkInfo(
            id, packDir,
            kv.getOrDefault("name", id),
            kv.getOrDefault("author", ""),
            kv.getOrDefault("version", ""),
            kv.getOrDefault("dependencies", ""),
            kv.getOrDefault("link", ""),
            kv.getOrDefault("description", ""),
            buildingCount
        );
    }

    /** 读取某个拓展包下的所有建筑 */
    public List<BuildingWorkInfo> readBuildings(String packId) {
        List<BuildingWorkInfo> result = new ArrayList<>();
        Path constructionDir = getWorkRoot().resolve(packId).resolve("construction");
        if (!Files.exists(constructionDir)) return result;

        try (Stream<Path> stream = Files.list(constructionDir)) {
            stream.filter(p -> p.toString().endsWith(".txt"))
                  .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                  .forEach(txtPath -> {
                      String id = txtPath.getFileName().toString().replaceAll("\\.txt$", "");
                      BuildingWorkInfo info = readBuildingInfo(packId, id);
                      if (info != null) result.add(info);
                  });
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("Scan construction failed: {}", e.getMessage());
        }
        return result;
    }

    public BuildingWorkInfo readBuildingInfo(String packId, String buildingId) {
        Path dir = getWorkRoot().resolve(packId).resolve("construction");
        Path nbt = dir.resolve(buildingId + ".nbt");
        Path png = dir.resolve(buildingId + ".png");
        Path txt = dir.resolve(buildingId + ".txt");
        if (!Files.exists(txt)) return null;
        String content;
        try {
            content = Files.readString(txt, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
        Map<String, String> kv = parseBuildingInfoText(content);
        return new BuildingWorkInfo(
            buildingId, nbt, png, txt,
            kv.getOrDefault("name", buildingId),
            kv.getOrDefault("author", ""),
            kv.getOrDefault("size", ""),
            kv.getOrDefault("dependencies", ""),
            kv.getOrDefault("description", "")
        );
    }

    /** 创建新拓展包 (含 information/ 子目录和 txt) */
    public PackWorkInfo createPack(String id, String name, String author, String version,
                                   String dependencies, String link, String description,
                                   byte[] coverPng) throws IOException {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("ID 不能为空");
        }
        // ID 合法性检查
        if (!id.matches("[A-Za-z0-9_\\-]+")) {
            throw new IllegalArgumentException("ID 只能包含字母、数字、下划线、连字符");
        }
        Path packDir = getWorkRoot().resolve(id);
        if (Files.exists(packDir)) {
            throw new IllegalArgumentException("拓展包已存在: " + id);
        }
        Files.createDirectories(packDir.resolve("information"));
        Files.createDirectories(packDir.resolve("construction"));

        // 写 txt
        Path infoTxt = packDir.resolve("information").resolve(id + ".txt");
        String content = buildInfoText(id, name, author, version, dependencies, link, description);
        Files.writeString(infoTxt, content, StandardCharsets.UTF_8);

        // 写封面图
        if (coverPng != null && coverPng.length > 0) {
            Path cover = packDir.resolve("information").resolve("cover.png");
            Files.write(cover, coverPng);
        }

        // 同时生成 zip 包 (在 prefab-work 根目录下, 同级于 packDir)
        try {
            createZipFor(packDir, id);
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.warn("[WORK] 创建 zip 失败: {}", e.getMessage());
        }

        return readPackInfo(packDir);
    }

    /**
     * 为指定拓展包目录生成 zip 文件 (放于 workRoot/<id>.zip)
     */
    public Path createZipFor(Path packDir, String id) throws IOException {
        Path zipPath = getWorkRoot().resolve(id + ".zip");
        // 删除旧 zip
        if (Files.exists(zipPath)) Files.delete(zipPath);
        // 用 java.nio 创建 zip
        try (var zos = new java.util.zip.ZipOutputStream(
                new java.io.BufferedOutputStream(Files.newOutputStream(zipPath)))) {
            if (Files.exists(packDir)) {
                try (var stream = Files.walk(packDir)) {
                    stream.filter(Files::isRegularFile).forEach(p -> {
                        String entryName = packDir.getParent().relativize(p).toString()
                            .replace('\\', '/');
                        try {
                            var entry = new java.util.zip.ZipEntry(entryName);
                            entry.setSize(Files.size(p));
                            zos.putNextEntry(entry);
                            Files.copy(p, zos);
                            zos.closeEntry();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
            }
        }
        PrefabCustomAddon.LOGGER.info("[WORK] 已生成 zip: {}", zipPath);
        return zipPath;
    }

    /** 重新打包当前 zip (用于建筑变更后) */
    public Path repackZip(String packId) throws IOException {
        Path packDir = getWorkRoot().resolve(packId);
        if (!Files.exists(packDir)) {
            throw new IOException("拓展包不存在: " + packId);
        }
        return createZipFor(packDir, packId);
    }

    /** 更新拓展包信息 (txt + 可选封面) */
    public PackWorkInfo updatePack(String id, String name, String author, String version,
                                   String dependencies, String link, String description,
                                   byte[] coverPng) throws IOException {
        Path packDir = getWorkRoot().resolve(id);
        if (!Files.exists(packDir)) {
            throw new IllegalArgumentException("拓展包不存在: " + id);
        }
        Path infoTxt = packDir.resolve("information").resolve(id + ".txt");
        String content = buildInfoText(id, name, author, version, dependencies, link, description);
        Files.writeString(infoTxt, content, StandardCharsets.UTF_8);

        if (coverPng != null && coverPng.length > 0) {
            Path cover = packDir.resolve("information").resolve("cover.png");
            Files.write(cover, coverPng);
        }
        // 重新打包 zip
        try { repackZip(id); } catch (IOException e) { PrefabCustomAddon.LOGGER.warn("[WORK] repack zip 失败: {}", e.getMessage()); }
        return readPackInfo(packDir);
    }

    /** 保存建筑 (nbt + png + txt) */
    public BuildingWorkInfo saveBuilding(String packId, String buildingId,
                                         String name, String author, String size,
                                         String dependencies, String description,
                                         byte[] nbtData, byte[] pngData) throws IOException {
        if (buildingId == null || buildingId.isEmpty()) {
            throw new IllegalArgumentException("建筑 ID 不能为空");
        }
        if (!buildingId.matches("[A-Za-z0-9_\\-]+")) {
            throw new IllegalArgumentException("建筑 ID 只能包含字母、数字、下划线、连字符");
        }
        Path dir = getWorkRoot().resolve(packId).resolve("construction");
        Files.createDirectories(dir);
        if (nbtData == null || nbtData.length == 0) {
            throw new IllegalArgumentException("NBT 文件必须先选择");
        }
        Path nbtPath = dir.resolve(buildingId + ".nbt");
        Files.write(nbtPath, nbtData);
        if (pngData != null && pngData.length > 0) {
            Path pngPath = dir.resolve(buildingId + ".png");
            Files.write(pngPath, pngData);
        }
        Path txtPath = dir.resolve(buildingId + ".txt");
        String content = buildBuildingInfoText(buildingId, name, author, size, dependencies, description);
        Files.writeString(txtPath, content, StandardCharsets.UTF_8);

        // 自动更新父拓展包的依赖 (合并所有建筑的依赖)
        updatePackDependenciesFromBuildings(packId);

        // 重新打包 zip
        try { repackZip(packId); } catch (IOException e) { PrefabCustomAddon.LOGGER.warn("[WORK] repack zip 失败: {}", e.getMessage()); }

        return readBuildingInfo(packId, buildingId);
    }

    /** 删除建筑 (3 个文件) + 自动重新汇总拓展包依赖 */
    public void deleteBuilding(String packId, String buildingId) throws IOException {
        Path dir = getWorkRoot().resolve(packId).resolve("construction");
        boolean any = false;
        for (String ext : new String[]{".nbt", ".png", ".txt"}) {
            Path p = dir.resolve(buildingId + ext);
            if (Files.exists(p)) {
                Files.delete(p);
                any = true;
            }
        }
        if (!any) {
            throw new IOException("建筑不存在: " + buildingId);
        }
        // 重新汇总拓展包依赖
        updatePackDependenciesFromBuildings(packId);
        // 重新打包 zip
        try { repackZip(packId); } catch (IOException e) { PrefabCustomAddon.LOGGER.warn("[WORK] repack zip 失败: {}", e.getMessage()); }
        PrefabCustomAddon.LOGGER.info("[WORK] Deleted building: {}/{}", packId, buildingId);
    }

    /** 删除整个拓展包目录 */
    public void deletePack(String packId) throws IOException {
        Path packDir = getWorkRoot().resolve(packId);
        if (!Files.exists(packDir)) {
            throw new IOException("拓展包不存在: " + packId);
        }
        try (Stream<Path> walk = Files.walk(packDir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) {}
                });
        }
        // 删 zip
        Path zipPath = getWorkRoot().resolve(packId + ".zip");
        if (Files.exists(zipPath)) Files.delete(zipPath);
        PrefabCustomAddon.LOGGER.info("[WORK] Deleted pack: {}", packId);
    }

    /**
     * 扫描指定包下所有建筑的依赖, 合并到拓展包 txt 的 依赖模组 字段
     */
    public void updatePackDependenciesFromBuildings(String packId) {
        List<BuildingWorkInfo> buildings = readBuildings(packId);
        Set<String> allDeps = new TreeSet<>();
        for (BuildingWorkInfo b : buildings) {
            String d = b.dependencies == null ? "" : b.dependencies.trim();
            if (!d.isEmpty()) {
                for (String dep : d.split("[,，;；\\s]+")) {
                    if (!dep.isEmpty()) allDeps.add(dep);
                }
            }
        }

        PackWorkInfo pack = readPackInfo(getWorkRoot().resolve(packId));
        if (pack == null) return;

        String newDeps = String.join(",", allDeps);
        if (newDeps.equals(pack.dependencies)) return;  // 没变化

        try {
            updatePack(packId, pack.name, pack.author, pack.version,
                newDeps, pack.link, pack.description, null);
            PrefabCustomAddon.LOGGER.info("[WORK] Auto-updated {} dependencies: {}", packId, newDeps);
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("Failed to update pack deps: {}", e.getMessage());
        }
    }

    /** 合并所有建筑依赖 + 用户原值 */
    private String buildInfoText(String id, String name, String author, String version,
                                 String dependencies, String link, String description) {
        StringBuilder sb = new StringBuilder();
        sb.append("作者:").append(nullSafe(author)).append('\n');
        sb.append("版本:").append(nullSafe(version)).append('\n');
        sb.append("标识符:").append(id).append('\n');
        sb.append("拓展包名:").append(nullSafe(name)).append('\n');
        sb.append("相关链接:").append(nullSafe(link)).append('\n');
        sb.append("依赖模组:").append(nullSafe(dependencies)).append('\n');
        sb.append("描述:").append(nullSafe(description)).append('\n');
        return sb.toString();
    }

    private String buildBuildingInfoText(String id, String name, String author, String size,
                                         String dependencies, String description) {
        StringBuilder sb = new StringBuilder();
        sb.append("作者:").append(nullSafe(author)).append('\n');
        sb.append("建筑名:").append(nullSafe(name)).append('\n');
        sb.append("尺寸:").append(nullSafe(size)).append('\n');
        sb.append("描述:").append(nullSafe(description)).append('\n');
        sb.append("建筑标识符:").append(id).append('\n');
        sb.append("依赖模组:").append(nullSafe(dependencies)).append('\n');
        return sb.toString();
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }

    /** 解析 information/<id>.txt 格式 */
    public static Map<String, String> parseInfoText(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : (text == null ? "" : text).split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String sep = line.contains(":") ? ":" : (line.contains("：") ? "：" : null);
            if (sep == null) continue;
            int idx = line.indexOf(sep);
            String k = line.substring(0, idx).trim().toLowerCase();
            String v = line.substring(idx + 1).trim();
            if (k.equals("作者") || k.equals("author")) result.put("author", v);
            else if (k.equals("版本") || k.equals("version")) result.put("version", v);
            else if (k.equals("标识符") || k.equals("id")) result.put("id", v);
            else if (k.equals("拓展包名") || k.equals("name")) result.put("name", v);
            else if (k.equals("相关链接") || k.equals("link") || k.equals("url")) result.put("link", v);
            else if (k.equals("依赖模组") || k.equals("dependencies") || k.equals("deps")) result.put("dependencies", v);
            else if (k.equals("描述") || k.equals("description") || k.equals("desc") || k.equals("说明")) result.put("description", v);
        }
        return result;
    }

    /** 解析 construction/<id>.txt 格式 */
    public static Map<String, String> parseBuildingInfoText(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : (text == null ? "" : text).split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String sep = line.contains(":") ? ":" : (line.contains("：") ? "：" : null);
            if (sep == null) continue;
            int idx = line.indexOf(sep);
            String k = line.substring(0, idx).trim().toLowerCase();
            String v = line.substring(idx + 1).trim();
            if (k.equals("作者") || k.equals("author")) result.put("author", v);
            else if (k.equals("建筑名") || k.equals("name")) result.put("name", v);
            else if (k.equals("尺寸") || k.equals("size")) result.put("size", v);
            else if (k.equals("描述") || k.equals("description") || k.equals("desc") || k.equals("说明")) result.put("description", v);
            else if (k.equals("建筑标识符") || k.equals("id")) result.put("id", v);
            else if (k.equals("依赖模组") || k.equals("dependencies") || k.equals("deps")) result.put("dependencies", v);
        }
        return result;
    }

    private static Path findCoverImage(Path infoDir) {
        if (!Files.exists(infoDir)) return null;
        String[] names = {"cover.png", "图标.png", "建筑包图像.png"};
        for (String n : names) {
            Path p = infoDir.resolve(n);
            if (Files.exists(p)) return p;
        }
        try (Stream<Path> s = Files.list(infoDir)) {
            return s.filter(p -> p.toString().toLowerCase().endsWith(".png"))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
