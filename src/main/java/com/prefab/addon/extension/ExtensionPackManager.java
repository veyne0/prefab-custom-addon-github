package com.prefab.addon.extension;

import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.client.Minecraft;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ExtensionPackManager {
    private static ExtensionPackManager instance;
    private final List<ExtensionPack> packs = new ArrayList<>();
    private Path extensionDir;
    
    private ExtensionPackManager() {}
    
    public static ExtensionPackManager getInstance() {
        if (instance == null) {
            instance = new ExtensionPackManager();
        }
        return instance;
    }
    
    public void initialize(MinecraftServer server) {
        Path worldDir = server.getWorldPath(LevelResource.ROOT).getParent();
        Path gameDir = worldDir.getParent().getParent(); // 返回 .minecraft
        Path versionsDir = gameDir.resolve("versions");
        
        Path versionExtensionDir = findVersionExtensionDir(versionsDir);
        
        if (versionExtensionDir != null) {
            this.extensionDir = versionExtensionDir;
        } else {
            this.extensionDir = gameDir.resolve("prefab-extension");
        }
        
        PrefabCustomAddon.LOGGER.info("Server using extension directory: {}", extensionDir);
        scanExtensionPacks();
    }
    
    public void initializeClient() {
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        Path versionsDir = gameDir.resolve("versions");
        Path rootExtensionDir = gameDir.resolve("prefab-extension");
        
        Path versionExtensionDir = findVersionExtensionDir(versionsDir);
        
        if (versionExtensionDir != null) {
            this.extensionDir = versionExtensionDir;
        } else if (Files.exists(rootExtensionDir)) {
            this.extensionDir = rootExtensionDir;
        } else {
            this.extensionDir = rootExtensionDir;
        }
        PrefabCustomAddon.LOGGER.info("Using extension directory: {}", extensionDir);
        scanExtensionPacks();
    }
    
    private Path findVersionExtensionDir(Path versionsDir) {
        if (!Files.exists(versionsDir) || !Files.isDirectory(versionsDir)) {
            return null;
        }
        
        try {
            return Files.list(versionsDir)
                .filter(Files::isDirectory)
                .map(dir -> dir.resolve("prefab-extension"))
                .filter(Files::exists)
                .filter(Files::isDirectory)
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.warn("Failed to scan versions directory", e);
            return null;
        }
    }
    
    public void scanExtensionPacks() {
        packs.clear();
        
        PrefabCustomAddon.LOGGER.info("Scanning extension directory: {}", extensionDir);
        
        if (!Files.exists(extensionDir)) {
            try {
                Files.createDirectories(extensionDir);
                PrefabCustomAddon.LOGGER.info("Created prefab-extension directory: {}", extensionDir);
            } catch (IOException e) {
                PrefabCustomAddon.LOGGER.error("Failed to create prefab-extension directory", e);
                return;
            }
        }
        
        try {
            List<Path> zipFiles = Files.walk(extensionDir)
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".zip"))
                .collect(java.util.stream.Collectors.toList());
            
            PrefabCustomAddon.LOGGER.info("Found {} zip files", zipFiles.size());
            for (Path zipFile : zipFiles) {
                PrefabCustomAddon.LOGGER.info("Found zip file: {}", zipFile);
            }
            
            zipFiles.forEach(this::loadExtensionPack);
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("Failed to scan prefab-extension directory", e);
        }
        
        PrefabCustomAddon.LOGGER.info("Loaded {} extension packs", packs.size());
    }
    
    private void loadExtensionPack(Path zipPath) {
        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            ExtensionPack pack = new ExtensionPack();
            pack.setFilePath(zipPath.toString());
            pack.setFileName(zipPath.getFileName().toString());
            pack.setZipFile(zipFile);

            String baseFolder = detectBaseFolder(zipFile);

            // 查找information文件夹中的txt文件（拓展包信息）
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            boolean foundInfoFile = false;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                String infoPath = baseFolder + "information/";
                if (name.startsWith(infoPath) && name.endsWith(".txt") && !name.contains("建筑包图像")) {
                    foundInfoFile = true;
                    try (InputStream is = zipFile.getInputStream(entry);
                         BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.startsWith("package:")) {
                                String packageName = line.substring("package:".length()).trim();
                                pack.setPackageName(packageName);
                            } else if (line.startsWith("name:")) {
                                pack.setName(line.substring("name:".length()).trim());
                            } else if (line.startsWith("author:")) {
                                pack.setAuthor(line.substring("author:".length()).trim());
                            } else if (line.startsWith("url:")) {
                                pack.setUrl(line.substring("url:".length()).trim());
                            } else if (line.startsWith("dependence:")) {
                                String deps = line.substring("dependence:".length()).trim();
                                if (!deps.isEmpty()) {
                                    pack.setDependencies(Arrays.asList(deps.split(",")));
                                }
                            } else if (line.startsWith("description:")) {
                                pack.setDescription(line.substring("description:".length()).trim());
                            } else if (line.startsWith("version:")) {
                                pack.setVersion(line.substring("version:".length()).trim());
                            }
                        }
                    } catch (IOException e) {
                        PrefabCustomAddon.LOGGER.warn("Failed to read info TXT for {}", zipPath);
                    }
                    break; // 只读取第一个txt文件
                }
            }
            pack.setHasInfoFolder(foundInfoFile);

            // 如果没有找到信息文件，使用默认值
            if (pack.getName() == null || pack.getName().isEmpty()) {
                pack.setName(pack.getFileName().replace(".zip", ""));
            }

            String coverPath = baseFolder + "information/建筑包图像.png";
            ZipEntry coverEntry = zipFile.getEntry(coverPath);
            if (coverEntry != null) {
                pack.setHasCoverImage(true);
                pack.setCoverImageEntry(coverEntry);
                // 立即读取并缓存封面图
                try (InputStream is = zipFile.getInputStream(coverEntry);
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = is.read(buffer)) != -1) baos.write(buffer, 0, len);
                    pack.setCoverImageData(baos.toByteArray());
                } catch (IOException e) {
                    PrefabCustomAddon.LOGGER.warn("Failed to cache cover image for {}", zipPath);
                }
            }

            loadConstructions(zipFile, pack, baseFolder);

            if (!pack.getConstructions().isEmpty()) {
                packs.add(pack);
                PrefabCustomAddon.LOGGER.info("Loaded extension pack: {} (info={}, hasCover={}, constructions={})",
                        pack.getName(), foundInfoFile, pack.hasCoverImage(), pack.getConstructions().size());
            }
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("Failed to load extension pack: {}", zipPath, e);
        }
    }
    
    private String detectBaseFolder(ZipFile zipFile) {
        Enumeration<? extends ZipEntry> entries = zipFile.entries();

        // 1) 优先：直接存在 construction/ 或 information/ 文件夹 → 根目录格式
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.startsWith("construction/") || name.startsWith("information/")) {
                PrefabCustomAddon.LOGGER.info("Found direct construction/information, returning empty base folder");
                return "";
            }
        }

        // 2) 新格式：所有文件直接在 zip 根目录（.txt / .nbt / .png 平铺）
        //    例: 拓展包示例2/   包含 new.txt, new.nbt, new.png
        //    条件：根目录有 .nbt 文件 + 至少一个匹配的 .txt（同名）
        boolean hasRootNbt = false;
        boolean hasRootTxt = false;
        boolean hasRootPng = false;
        Enumeration<? extends ZipEntry> entries2 = zipFile.entries();
        while (entries2.hasMoreElements()) {
            ZipEntry e = entries2.nextElement();
            String n = e.getName();
            if (n.contains("/")) continue;          // 跳过子目录里的
            if (n.endsWith(".nbt")) hasRootNbt = true;
            else if (n.endsWith(".txt")) hasRootTxt = true;
            else if (n.endsWith(".png")) hasRootPng = true;
        }
        if (hasRootNbt && (hasRootTxt || hasRootPng)) {
            PrefabCustomAddon.LOGGER.info("Detected FLAT root pack (no subfolders), base folder=''");
            return "";
        }

        // 3) 旧格式：所有东西放在一个共享的子文件夹下（如 prefab_extension/construction/...）
        Enumeration<? extends ZipEntry> entries3 = zipFile.entries();
        while (entries3.hasMoreElements()) {
            ZipEntry entry = entries3.nextElement();
            String name = entry.getName();

            int firstSlash = name.indexOf('/');
            if (firstSlash > 0) {
                String folder = name.substring(0, firstSlash + 1);
                String testConstruction = folder + "construction/";
                String testInformation = folder + "information/";

                Enumeration<? extends ZipEntry> entries4 = zipFile.entries();
                boolean hasConstruction = false;
                boolean hasInformation = false;

                while (entries4.hasMoreElements()) {
                    ZipEntry e = entries4.nextElement();
                    if (e.getName().startsWith(testConstruction)) {
                        hasConstruction = true;
                    }
                    if (e.getName().startsWith(testInformation)) {
                        hasInformation = true;
                    }
                    if (hasConstruction && hasInformation) {
                        PrefabCustomAddon.LOGGER.info("Found base folder: {}", folder);
                        return folder;
                    }
                }
            }
        }

        PrefabCustomAddon.LOGGER.info("No base folder detected, returning empty");
        return "";
    }
    
    private void loadConstructions(ZipFile zipFile, ExtensionPack pack, String baseFolder) {
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        Map<String, ConstructionInfo> constructions = new HashMap<>();

        // 决定建筑文件来源路径：扁平格式（文件在根） vs 标准格式（construction/ 子目录）
        String constructionPath = baseFolder + "construction/";
        boolean isFlat = constructionPath.isEmpty() && !hasConstructionSubfolder(zipFile, baseFolder);

        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();

            // 跳过信息文件夹里的 txt（不是建筑文件）
            if (name.contains("/information/") || name.startsWith("information/")) continue;

            // 决定这个 entry 是否要处理
            boolean accept;
            if (isFlat) {
                // 扁平：仅根目录文件（不进入任何子目录）
                accept = !name.contains("/") && name.contains(".");
            } else {
                // 标准：必须以 construction/ 开头
                accept = name.startsWith(constructionPath) && name.contains(".");
            }
            if (!accept) continue;

            // 计算相对路径
            String baseName;
            if (isFlat) {
                baseName = name;
            } else {
                baseName = name.substring(constructionPath.length());
            }
            String extension = baseName.substring(baseName.lastIndexOf('.') + 1);
            String constructionName = baseName.substring(0, baseName.lastIndexOf('.'));

            if (!constructions.containsKey(constructionName)) {
                constructions.put(constructionName, new ConstructionInfo(constructionName));
            }
            constructions.get(constructionName).setPack(pack);

            switch (extension.toLowerCase()) {
                case "txt":
                    try {
                        ConstructionInfo info = constructions.get(constructionName);
                        String identifier = constructionName;

                        // 尝试UTF-8读取，如果失败则尝试GBK
                        try (InputStream is = zipFile.getInputStream(entry);
                             BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                String newId = parseConstructionLine(line, info);
                                if (newId != null) identifier = newId;
                            }
                        } catch (Exception e) {
                            // UTF-8失败，尝试GBK
                            try (InputStream is = zipFile.getInputStream(entry);
                                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, "GBK"))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    String newId = parseConstructionLine(line, info);
                                    if (newId != null) identifier = newId;
                                }
                            }
                        }

                        if (!identifier.equals(constructionName) && !identifier.isEmpty()) {
                            constructions.put(identifier, info);
                        }
                        if (info.getName().isEmpty()) {
                            info.setName(constructionName);
                        }
                    } catch (IOException e) {
                        PrefabCustomAddon.LOGGER.warn("Failed to read TXT for {}", constructionName);
                    }
                    break;
                case "nbt":
                    ConstructionInfo nbtInfo = constructions.get(constructionName);
                    nbtInfo.setNbtEntry(entry);
                    // 立即读取并缓存NBT数据，避免ZipFile关闭后无法访问
                    try (InputStream is = zipFile.getInputStream(entry)) {
                        byte[] nbtData = is.readAllBytes();
                        nbtInfo.setNbtData(nbtData);
                    } catch (IOException e) {
                        PrefabCustomAddon.LOGGER.warn("Failed to cache NBT for {}", constructionName);
                    }
                    break;
                case "png":
                    ConstructionInfo info = constructions.get(constructionName);
                    info.setPngEntry(entry);
                    // 立即读取并缓存PNG数据，避免ZipFile关闭后无法访问
                    try (InputStream is = zipFile.getInputStream(entry);
                         ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = is.read(buffer)) != -1) {
                            baos.write(buffer, 0, len);
                        }
                        info.setPngData(baos.toByteArray());
                    } catch (IOException e) {
                        PrefabCustomAddon.LOGGER.warn("Failed to cache PNG for {}", constructionName);
                    }
                    break;
            }
        }

        constructions.values().stream()
            .filter(c -> c.getNbtEntry() != null)
            .forEach(pack.getConstructions()::add);
    }

    /**
     * 检查 zip 里是否存在 construction/ 子目录（标准格式的特征）。
     */
    private boolean hasConstructionSubfolder(ZipFile zipFile, String baseFolder) {
        String prefix = baseFolder + "construction/";
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            if (e.getName().startsWith(prefix)) return true;
        }
        return false;
    }
    
    public List<ExtensionPack> getPacks() { return packs; }

    /**
     * 返回所有**有 information/ 子目录**的拓展包（即"标准格式"）。
     * Z 键的拓展包管理界面只显示这类（扁平格式如 拓展包示例2 不显示）。
     */
    public List<ExtensionPack> getDiscoverablePacks() {
        return packs.stream()
            .filter(ExtensionPack::hasInfoFolder)
            .collect(Collectors.toList());
    }

    public ConstructionInfo findConstruction(String packName, String constructionId) {
        for (ExtensionPack pack : packs) {
            if (!pack.getName().equals(packName)) continue;
            for (ConstructionInfo info : pack.getConstructions()) {
                if (info.getId().equals(constructionId)) {
                    return info;
                }
            }
        }
        return null;
    }

    public List<ConstructionInfo> getAllConstructions() {
        List<ConstructionInfo> all = new ArrayList<>();
        for (ExtensionPack pack : packs) {
            all.addAll(pack.getConstructions());
        }
        return all;
    }
    
    private String parseConstructionLine(String line, ConstructionInfo info) {
        if (line.startsWith("作者:")) {
            info.setAuthor(line.substring("作者:".length()).trim());
        } else if (line.startsWith("建筑名:")) {
            info.setName(line.substring("建筑名:".length()).trim());
        } else if (line.startsWith("尺寸:")) {
            info.setSize(line.substring("尺寸:".length()).trim());
        } else if (line.startsWith("描述:")) {
            info.setDescription(line.substring("描述:".length()).trim());
        } else if (line.startsWith("依赖模组:")) {
            // 多个模组用英文逗号分隔
            String deps = line.substring("依赖模组:".length()).trim();
            if (!deps.isEmpty()) {
                info.setDependencies(java.util.Arrays.asList(deps.split("\\s*,\\s*")));
            }
        } else if (line.startsWith("建筑标识符:")) {
            return line.substring("建筑标识符:".length()).trim();
        }
        return null;
    }
}
