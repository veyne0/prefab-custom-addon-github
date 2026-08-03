package com.prefab.addon.extension;

import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.server.MinecraftServer;
import net.minecraft.client.Minecraft;

import java.io.*;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ExtensionPackManager {
    private static ExtensionPackManager instance;
    private final List<ExtensionPack> packs = new ArrayList<>();
    private Path extensionDir;

    // ============ 热重载 (Hot Reload) ============
    /** 最近一次扫描的目录文件夹 mtime (毫秒), 用于 GUI 重开时检测外部改动 */
    private final AtomicLong lastDirectoryMTime = new AtomicLong(0L);
    /** 最近一次 zip 文件名→mtime 映射, 用于精确发现哪个 zip 变化了 */
    private final Map<String, Long> lastZipMTime = new ConcurrentHashMap<>();
    /** WatchService 守护线程 (客户端 zip 监听) */
    private Thread watchThread;
    private volatile boolean watcherRunning = false;
    /** 上次 reload 时间, 避免短时间内重复扫描 */
    private final AtomicLong lastReloadMs = new AtomicLong(0L);
    /** reload 节流最小间隔 (ms) */
    private static final long RELOAD_DEBOUNCE_MS = 500L;

    /**
     * 是否在服务端运行.
     * - true: 只扫描 server-cache/ (拓展包由服务端的"实际可建造包"清单决定)
     * - false: 同时扫描主目录 + server-cache/ (本地玩家可以预览/创作本地包)
     *
     * <p>关键: 服务端不扫主目录, 服务端主目录里的本地 zip 不会出现在同步清单里,
     * 客户端也就拿不到这些 zip 的 NBT 数据, 自然不会"误以为能在服务器里建造本地包".
     * 防止玩家误用本地包造成"服务器里建造不了"的问题.</p>
     */
    private volatile boolean isServerSide = false;

    private ExtensionPackManager() {}
    
    public static ExtensionPackManager getInstance() {
        if (instance == null) {
            instance = new ExtensionPackManager();
        }
        return instance;
    }
    
    public void initialize(MinecraftServer server) {
        // 服务端: 直接用 MinecraftServer.getServerDirectory() 取服务端运行目录
        // (server.properties / mods / world 同级, 即玩家认知的"服务端根")
        // 不要靠 worldDir.getParent().getParent() 推, 专用服务器上会拿到 null 然后 NPE
        Path gameDir = server.getServerDirectory();
        Path versionsDir = gameDir.resolve("versions");

        Path versionExtensionDir = findVersionExtensionDir(versionsDir);

        if (versionExtensionDir != null) {
            this.extensionDir = versionExtensionDir;
        } else {
            this.extensionDir = gameDir.resolve("prefab-extension");
        }

        // 关键: 服务端只扫描 server-cache/ 子目录, 忽略主目录的本地 zip
        // 原因: 服务端主目录里的 zip 是"管理员手动放的本地包", 客户端同步后虽然能看到,
        //       但因为客户端拿不到对应的 NBT, 实际上无法在服务器上建造.
        //       为避免玩家误用本地包造成"服务器里建造不了", 服务端只把 server-cache/ 里的
        //       包 (这些是真正会在服务器上建造的) 推给客户端.
        this.isServerSide = true;
        PrefabCustomAddon.LOGGER.info("Server using extension directory: {} (server-side: only server-cache/ will be scanned)",
            extensionDir);
        scanExtensionPacks();
    }

    public void initializeClient() {
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        Path versionsDir = gameDir.resolve("versions");
        Path rootExtensionDir = gameDir.resolve("prefab-extension");

        PrefabCustomAddon.LOGGER.info("[DIAG-INIT] gameDir = {}", gameDir);
        PrefabCustomAddon.LOGGER.info("[DIAG-INIT] gameDir/prefab-extension 存在? {}", Files.exists(rootExtensionDir));
        PrefabCustomAddon.LOGGER.info("[DIAG-INIT] gameDir/versions 存在? {}", Files.exists(versionsDir));

        Path versionExtensionDir = findVersionExtensionDir(versionsDir);

        PrefabCustomAddon.LOGGER.info("[DIAG-INIT] findVersionExtensionDir 返回: {}", versionExtensionDir);

        if (versionExtensionDir != null) {
            this.extensionDir = versionExtensionDir;
        } else if (Files.exists(rootExtensionDir)) {
            this.extensionDir = rootExtensionDir;
        } else {
            this.extensionDir = rootExtensionDir;
        }
        this.isServerSide = false;
        PrefabCustomAddon.LOGGER.info("[DIAG-INIT] 最终 extensionDir = {}", extensionDir);
        PrefabCustomAddon.LOGGER.info("[DIAG-INIT] extensionDir 存在? {}", Files.exists(extensionDir));
        if (Files.exists(extensionDir)) {
            try (var stream = Files.list(extensionDir)) {
                StringBuilder sb = new StringBuilder("[DIAG-INIT] extensionDir 内容: ");
                stream.forEach(p -> sb.append(p.getFileName()).append(", "));
                PrefabCustomAddon.LOGGER.info(sb.toString());
            } catch (IOException e) {
                PrefabCustomAddon.LOGGER.warn("[DIAG-INIT] 列 extensionDir 失败: {}", e.toString());
            }
        }
        scanExtensionPacks();
    }

    /** 返回是否在服务端运行. */
    public boolean isServerSide() {
        return isServerSide;
    }
    
    private Path findVersionExtensionDir(Path versionsDir) {
        if (!Files.exists(versionsDir) || !Files.isDirectory(versionsDir)) {
            return null;
        }

        try {
            // 先尝试: 跟当前游戏目录同名的版本子目录 (启动器通常把版本名放在 gameDir 路径里)
            // gameDir = .minecraft/versions/1.21.1-NeoForge_21.1.233/ → 当前版本名
            String currentVersionName = null;
            try {
                Path currentGameDir = Minecraft.getInstance().gameDirectory.toPath();
                String gd = currentGameDir.getFileName().toString();
                // 仅当 gameDir 看着像版本名 (含数字或 forge/neoforge/fabric 等) 才认
                if (gd.matches(".*\\d.*") || gd.toLowerCase().contains("forge")
                        || gd.toLowerCase().contains("fabric") || gd.toLowerCase().contains("neoforge")) {
                    currentVersionName = gd;
                }
            } catch (Throwable ignored) {}

            List<Path> candidates = Files.list(versionsDir)
                .filter(Files::isDirectory)
                .map(dir -> dir.resolve("prefab-extension"))
                .filter(Files::exists)
                .filter(Files::isDirectory)
                .collect(Collectors.toList());

            if (candidates.isEmpty()) return null;

            // 1) 优先: 跟当前游戏版本名匹配的那个
            if (currentVersionName != null) {
                for (Path c : candidates) {
                    if (c.getParent().getFileName().toString().equals(currentVersionName)) {
                        PrefabCustomAddon.LOGGER.info("[findVersionExtensionDir] 优先选当前游戏版本同名: {}", c);
                        return c;
                    }
                }
            }

            // 2) 兜底: 按 prefab-extension/ 目录 mtime 倒序, 玩家最近在玩的版本通常 mtime 最新
            Path picked = candidates.stream()
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
            PrefabCustomAddon.LOGGER.info("[findVersionExtensionDir] mtime 最新: {} (候选数 {})", picked, candidates.size());
            return picked;
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

        // 记录目录 mtime (用于后续变更检测)
        long dirMTime = safeMTime(extensionDir);
        lastDirectoryMTime.set(dirMTime);
        Map<String, Long> newZipMTime = new HashMap<>();

        // 1) 扫描主目录 (用户手动放的 zip)
        //    服务端和客户端都扫 - 玩家能看见这些 (客户端多人模式会过滤掉非 serverBacked 的),
        //    但服务端扫描是为了让管理员能把 zip 放在主目录里也能用.
        scanDirInto(extensionDir, newZipMTime, false);

        // 2) 扫描 server-cache/ 子目录 (服务器同步下来的 zip)
        //    服务端的 server-cache/ 也用同一个目录 - 管理员也可以把共享包放这.
        Path serverCache = extensionDir.resolve("server-cache");
        scanDirInto(serverCache, null, true);

        // 3) 按 packageName 去重: 同一包有 server-backed 版本时, 优先用 server-cache 那个 (可建)
        //    这样 GUI 不会重复显示同一个包两次
        dedupeByPackageName();

        // 更新 zip mtime 缓存 (只跟踪主目录, server-cache 由网络协议自己管)
        synchronized (lastZipMTime) {
            lastZipMTime.clear();
            lastZipMTime.putAll(newZipMTime);
        }

        PrefabCustomAddon.LOGGER.info("Loaded {} extension packs (after dedup)", packs.size());
    }

    /**
     * 强制重扫 (绕过 mtime 检查). 服务端 build 路径用:
     *   管理员可能中途删除/添加 zip, 但 in-memory packs 列表没及时刷新.
     *   如果 build 时用旧数据, 就会"看似还能建" (因为 findConstruction 还在内存里找到了).
     *   这里强制从磁盘重扫, 保证数据新鲜. 性能: scanExtensionPacks() 本身 < 100ms, 可以接受.
     */
    public synchronized void forceReload() {
        PrefabCustomAddon.LOGGER.info("[BUILD-GUARD] 强制重扫服务端拓展包 (确保数据新鲜)");
        int before = packs.size();
        scanExtensionPacks();
        int after = packs.size();
        if (before != after) {
            PrefabCustomAddon.LOGGER.info("[BUILD-GUARD] 重扫后包数变化: {} → {} (管理员可能添加/删除了 zip)", before, after);
        }
    }

    /**
     * 按 packageName 去重当前 packs 列表.
     * 规则:
     *   - 没有 packageName 的 (异常包) 全部保留
     *   - 同名包: 比较两个 zip 的 mtime (修改时间) 和 sha1 (内容哈希)
     *     - 内容不同的 → **较新** (mtime 大的) 优先, 让玩家更新了拓展包后无需手动清缓存
     *     - 内容相同 → serverBacked=true 优先 (可建)
     */
    private void dedupeByPackageName() {
        // 先按 packageName 分组
        Map<String, List<ExtensionPack>> byPkg = new LinkedHashMap<>();
        List<ExtensionPack> noPkg = new ArrayList<>();
        for (ExtensionPack p : packs) {
            String pkg = p.getPackageName();
            if (pkg == null || pkg.isEmpty()) {
                noPkg.add(p);
                continue;
            }
            byPkg.computeIfAbsent(pkg, k -> new ArrayList<>()).add(p);
        }
        PrefabCustomAddon.LOGGER.info("[DIAG-DEDUPE] dedupeByPackageName: byPkg 大小 {}, noPkg 大小 {}",
            byPkg.size(), noPkg.size());
        byPkg.forEach((k, v) -> PrefabCustomAddon.LOGGER.info("[DIAG-DEDUPE]   byPkg['{}']: {} 个 pack, 名称: {}",
            k, v.size(), v.stream().map(ExtensionPack::getName).collect(Collectors.toList())));

        // 重建 packs: 每组只保留一个
        packs.clear();
        for (Map.Entry<String, List<ExtensionPack>> e : byPkg.entrySet()) {
            List<ExtensionPack> group = e.getValue();
            // 独立 .nbt 建筑: 每个文件就是一栋"独立建筑", 不能按 packageName 合并
            // (因为 loadStandaloneNbtPack 给所有独立 .nbt 都用了同一个 packageName="__standalone__"
            //   作为收藏键前缀, 实际是不同建筑, 不该去重)
            if (STANDALONE_PACKAGE.equals(e.getKey())) {
                packs.addAll(group);
                continue;
            }
            if (group.size() == 1) {
                packs.add(group.get(0));
                continue;
            }
            // 多副本 → 选 winner
            ExtensionPack picked = pickNewestOrServerBacked(group);
            long dropped = group.stream().filter(p -> p != picked).count();
            if (dropped > 0) {
                PrefabCustomAddon.LOGGER.info("[DEDUP] Pack '{}' 有 {} 个副本, 保留 '{}' (serverBacked={}, mtime={}, size={})",
                        e.getKey(), group.size(), picked.getName(),
                        picked.isServerBacked(),
                        picked.getFilePath() != null ? safeMTimeString(picked) : "?",
                        picked.getFileSize());
            }
            packs.add(picked);
        }

        // 没有 packageName 的全部保留 (无法去重)
        packs.addAll(noPkg);
    }

    /**
     * 多副本时: 内容不同 → 较新 (mtime 大的) 赢; 内容相同 → serverBacked=true 赢.
     * 读取每个 zip 的 mtime; 失败时 fallback 到 fileSize, 再 fallback 到 serverBacked flag.
     */
    private ExtensionPack pickNewestOrServerBacked(List<ExtensionPack> group) {
        // 收集每个 pack 的 (mtime, contentSha1)
        java.util.Map<ExtensionPack, Long> mtimes = new HashMap<>();
        java.util.Map<ExtensionPack, String> sha1s = new HashMap<>();
        for (ExtensionPack p : group) {
            try {
                Path path = java.nio.file.Paths.get(p.getFilePath());
                if (java.nio.file.Files.exists(path)) {
                    mtimes.put(p, java.nio.file.Files.getLastModifiedTime(path).toMillis());
                }
            } catch (Exception ignored) {}
            sha1s.put(p, p.getContentSha1() != null ? p.getContentSha1() : "");
        }
        // 1) 内容不同 → 较新的赢
        java.util.Set<String> distinctSha1s = new java.util.HashSet<>(sha1s.values());
        if (distinctSha1s.size() > 1) {
            return group.stream()
                    .max((a, b) -> Long.compare(
                        mtimes.getOrDefault(a, 0L),
                        mtimes.getOrDefault(b, 0L)))
                    .orElse(group.get(0));
        }
        // 2) 内容相同 → serverBacked=true 优先
        return group.stream()
                .filter(ExtensionPack::isServerBacked)
                .findFirst()
                .orElse(group.get(0));
    }

    private static String safeMTimeString(ExtensionPack p) {
        try {
            return java.nio.file.Files.getLastModifiedTime(java.nio.file.Paths.get(p.getFilePath())).toString();
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * 扫描单个目录里的所有 .zip / .litematic / .schem / .schematic / .nbt，加载为 ExtensionPack。
     * @param dir         要扫描的目录（不存在则跳过）
     * @param mtimeMap    若非 null，把 (文件名 → mtime) 写入此 map（主目录用）
     * @param isServerCache 是否是服务器缓存目录：是则日志加 [SERVER-CACHE] 前缀，
     *                      同时把加载出来的包标记为 serverBacked=true
     */
    private void scanDirInto(Path dir, Map<String, Long> mtimeMap, boolean isServerCache) {
        if (dir == null || !Files.exists(dir) || !Files.isDirectory(dir)) return;

        String tag = isServerCache ? "[SERVER-CACHE] " : "";
        try {
            // 同时支持 .zip / .litematic / .schem / .schematic / .nbt
            List<Path> zipFiles = Files.list(dir)
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String n = p.getFileName().toString().toLowerCase();
                    return n.endsWith(".zip") || n.endsWith(".litematic")
                        || n.endsWith(".schem") || n.endsWith(".schematic")
                        || n.endsWith(".nbt");
                })
                .collect(java.util.stream.Collectors.toList());

            PrefabCustomAddon.LOGGER.info("{}Found {} extension files in {}", tag, zipFiles.size(), dir);
            for (Path file : zipFiles) {
                if (mtimeMap != null) {
                    mtimeMap.put(file.getFileName().toString(), safeMTime(file));
                }
                PrefabCustomAddon.LOGGER.info("{}Found extension file: {}", tag, file);
            }

            // 诊断: 列出整个目录里的所有文件 (包括 .txt/.png 等被过滤的), 确认有没有 zip 被漏掉
            try (var allStream = Files.list(dir)) {
                StringBuilder all = new StringBuilder("[DIAG-SCAN] ");
                allStream.forEach(p -> all.append(p.getFileName()).append("(").append(Files.isRegularFile(p) ? "file" : "dir").append("), "));
                PrefabCustomAddon.LOGGER.info(all.toString());
            } catch (IOException e) { /* ignore */ }

            // 主目录才做热重载 diff 日志
            if (!isServerCache && mtimeMap != null) {
                Set<String> oldNames = new HashSet<>(lastZipMTime.keySet());
                Set<String> newNames = mtimeMap.keySet();
                Set<String> added = new HashSet<>(newNames);
                added.removeAll(oldNames);
                Set<String> removed = new HashSet<>(oldNames);
                removed.removeAll(newNames);
                Set<String> modified = new HashSet<>();
                for (Map.Entry<String, Long> e : mtimeMap.entrySet()) {
                    Long old = lastZipMTime.get(e.getKey());
                    if (old != null && !old.equals(e.getValue())) {
                        modified.add(e.getKey());
                    }
                }
                if (!added.isEmpty()) PrefabCustomAddon.LOGGER.info("[HOT-RELOAD] 新增 file: {}", added);
                if (!removed.isEmpty()) PrefabCustomAddon.LOGGER.info("[HOT-RELOAD] 删除 file: {}", removed);
                if (!modified.isEmpty()) PrefabCustomAddon.LOGGER.info("[HOT-RELOAD] 修改 file: {}", modified);
            }

            for (Path file : zipFiles) {
                String n = file.getFileName().toString().toLowerCase();
                if (n.endsWith(".zip")) {
                    loadExtensionPack(file, isServerCache);
                } else if (n.endsWith(".litematic")) {
                    loadLitematicPack(file, isServerCache);
                } else if (n.endsWith(".schem") || n.endsWith(".schematic")) {
                    // Sponge Schematic (.schem / .schematic) - 走 NbtFormatConverter 转 vanilla
                    SpongeSchematicParser.loadAsPackIntoList(packs, file, isServerCache, mtimeMap, computeSha1Hex(file));
                } else if (n.endsWith(".nbt")) {
                    // 独立 .nbt (vanilla StructureTemplate) - 单文件当一个 1-建筑的"独立包"
                    loadStandaloneNbtPack(file, isServerCache);
                }
            }
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("{}Failed to scan directory {}", tag, dir, e);
        }
    }

    /**
     * 加载一个独立的 .nbt 文件为 ExtensionPack (1 个建筑的"独立包").
     * <p>vanilla StructureTemplate NBT 直接就是建筑数据, 不需要转换.</p>
     * <p>把 .nbt 文件名 (去后缀) 当作包名 + 建筑 id.
     *    收藏键用 {@code __standalone__/<filename>} 这样能跟拓展包内建筑区分开.</p>
     */
    private void loadStandaloneNbtPack(Path nbtPath, boolean isServerCache) {
        try {
            byte[] nbtData = Files.readAllBytes(nbtPath);
            if (nbtData.length == 0) {
                PrefabCustomAddon.LOGGER.warn("[STANDALONE-NBT] 空文件: {}", nbtPath);
                return;
            }

            ExtensionPack pack = new ExtensionPack();
            String fileName = nbtPath.getFileName().toString();
            pack.setFilePath(nbtPath.toString());
            pack.setFileName(fileName);
            pack.setFileSize(nbtPath.toFile().length());
            pack.setContentSha1(computeSha1Hex(nbtPath));
            pack.setServerBacked(isServerCache);
            // 关键: standalone 包没有 information/ 目录, getDisplayedPacks() 会过滤掉.
            // 但 getAllConstructions() 不过滤 → "建筑" 标签页能看到.
            pack.setHasInfoFolder(false);

            String constructionId = fileName.replaceAll("(?i)\\.nbt$", "");
            ConstructionInfo info = new ConstructionInfo(constructionId);
            info.setName(constructionId);
            info.setAuthor("独立建筑");
            info.setNbtData(nbtData);
            info.setPack(pack);
            pack.getConstructions().add(info);
            pack.setName(constructionId);  // pack 名 = 文件名去后缀
            // 标记 packageName 固定为 __standalone__, 用于收藏键
            pack.setPackageName("__standalone__");

            this.packs.add(pack);
            PrefabCustomAddon.LOGGER.info("[STANDALONE-NBT] 加载独立建筑 '{}' ({} 字节)", constructionId, nbtData.length);
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.error("[STANDALONE-NBT] 加载 {} 失败: {}", nbtPath, e.toString(), e);
        }
    }

    /**
     * 加载一个 .litematic 单文件为 ExtensionPack
     * 转换流程：litematica NBT → 标准结构 NBT → 作为唯一 construction 的 nbtData
     */
    private void loadLitematicPack(Path litematicPath, boolean isServerCache) {
        try {
            byte[] standardStructureBytes = LitematicaParser.parseToStandardStructureBytes(litematicPath);
            if (standardStructureBytes == null || standardStructureBytes.length == 0) {
                PrefabCustomAddon.LOGGER.warn("[LITEMATICA] Empty after conversion: {}", litematicPath);
                return;
            }

            ExtensionPack pack = new ExtensionPack();
            String fileName = litematicPath.getFileName().toString();
            pack.setFilePath(litematicPath.toString());
            pack.setFileName(fileName);
            pack.setFileSize(litematicPath.toFile().length());
            pack.setContentSha1(computeSha1Hex(litematicPath));
            pack.setServerBacked(isServerCache);

            // construction 名: 去后缀
            String constructionId = fileName.replaceAll("(?i)\\.litematic$", "");
            ConstructionInfo info = new ConstructionInfo(constructionId);
            info.setName(constructionId);
            info.setAuthor("Litematica Importer");
            info.setNbtData(standardStructureBytes);
            info.setPack(pack);
            pack.getConstructions().add(info);
            pack.setName(fileName);  // pack 名 = 文件名

            this.packs.add(pack);
            PrefabCustomAddon.LOGGER.info("[LITEMATICA] Loaded '{}' as pack '{}' (1 region, {} bytes nbtData)",
                fileName, pack.getName(), standardStructureBytes.length);
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.error("[LITEMATICA] Failed to load {}: {}", litematicPath, e.toString(), e);
        }
    }

    /**
     * 返回 server-cache/ 子目录的绝对路径。客户端同步下来的 zip 都放这里。
     * 不会自动创建 — 调用方按需创建。
     */
    public Path getServerCacheDir() {
        return extensionDir == null ? null : extensionDir.resolve("server-cache");
    }

    private static long safeMTime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * 计算文件内容的 SHA-1，返回 40 位小写十六进制字符串。
     * 用于服务器→客户端同步时做内容指纹比对，客户端可据此判断是否已缓存。
     */
    public static String computeSha1Hex(Path p) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            try (InputStream is = Files.newInputStream(p)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) md.update(buf, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("Failed to compute SHA-1 for {}: {}", p, e.getMessage());
            return null;
        }
    }

    /**
     * 客户端热重载: 节流后重新扫描, 立即生效 (无需重进存档).
     * WatchService 线程 / GUI 重开检测 / 快捷键 都可调用此方法.
     */
    public synchronized void reloadClient() {
        long now = System.currentTimeMillis();
        long last = lastReloadMs.get();
        if (now - last < RELOAD_DEBOUNCE_MS) {
            PrefabCustomAddon.LOGGER.debug("[HOT-RELOAD] 节流中, 跳过 (距上次 {}ms)", now - last);
            return;
        }
        lastReloadMs.set(now);
        PrefabCustomAddon.LOGGER.info("[HOT-RELOAD] 开始重新扫描拓展包...");
        int before = packs.size();
        scanExtensionPacks();
        int after = packs.size();
        PrefabCustomAddon.LOGGER.info("[HOT-RELOAD] 扫描完成: {} → {} 个拓展包", before, after);
    }

    /**
     * GUI 重开时调用: 检测目录或 zip 是否有外部改动, 有则自动 reload.
     * 静默失败 (只记录 debug 日志), 不会刷屏.
     */
    public void reloadIfChanged() {
        if (extensionDir == null || !Files.exists(extensionDir)) return;
        long currentDirMTime = safeMTime(extensionDir);
        if (currentDirMTime != lastDirectoryMTime.get()) {
            PrefabCustomAddon.LOGGER.info("[HOT-RELOAD] 检测到拓展包目录变化, 自动重新扫描");
            reloadClient();
            return;
        }
        // 逐个 zip 检查
        try (var stream = Files.list(extensionDir)) {
            stream.filter(p -> {
                    String n = p.getFileName().toString().toLowerCase();
                    return n.endsWith(".zip") || n.endsWith(".nbt");
                })
                  .forEach(p -> {
                      String name = p.getFileName().toString();
                      long mt = safeMTime(p);
                      Long old = lastZipMTime.get(name);
                      if (old == null || old != mt) {
                          PrefabCustomAddon.LOGGER.info("[HOT-RELOAD] 检测到 {} 变化, 自动重新扫描", name);
                          reloadClient();
                      }
                  });
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.debug("[HOT-RELOAD] 扫描目录失败: {}", e.getMessage());
        }
    }

    /**
     * 启动 WatchService 守护线程, 监听 extensionDir 内的 zip 文件变化.
     * 事件触发 → 节流后切主线程 reloadClient().
     */
    public void startHotReloadWatcher(Path dir) {
        if (watcherRunning) {
            PrefabCustomAddon.LOGGER.debug("[HOT-RELOAD] Watcher 已在运行, 跳过启动");
            return;
        }
        if (dir == null) return;
        try {
            if (!Files.exists(dir)) Files.createDirectories(dir);
            final WatchService ws = dir.getFileSystem().newWatchService();
            dir.register(ws,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY);

            watcherRunning = true;
            watchThread = new Thread(() -> {
                PrefabCustomAddon.LOGGER.info("[HOT-RELOAD] Watcher 启动, 监听: {}", dir);
                while (watcherRunning) {
                    WatchKey key;
                    try {
                        key = ws.take();
                    } catch (InterruptedException | ClosedWatchServiceException e) {
                        PrefabCustomAddon.LOGGER.info("[HOT-RELOAD] Watcher 退出");
                        return;
                    }
                    boolean changed = false;
                    for (WatchEvent<?> evt : key.pollEvents()) {
                        if (evt.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                        Object ctx = evt.context();
                        if (ctx instanceof Path p) {
                            String name = p.getFileName().toString();
                            if (name.toLowerCase().endsWith(".zip")) {
                                PrefabCustomAddon.LOGGER.info("[HOT-RELOAD] 文件系统事件: {} ({})", evt.kind(), name);
                                changed = true;
                            }
                        }
                    }
                    if (!key.reset()) {
                        break;
                    }
                    if (changed) {
                        // 切回客户端主线程执行 reload, 避免并发改 packs 列表
                        try {
                            Minecraft mc = Minecraft.getInstance();
                            mc.execute(() -> reloadClient());
                        } catch (Exception e) {
                            PrefabCustomAddon.LOGGER.warn("[HOT-RELOAD] 调度 reload 失败, 同步执行: {}", e.getMessage());
                            reloadClient();
                        }
                    }
                }
                try { ws.close(); } catch (IOException ignored) {}
            }, "PrefabAddon-HotReloadWatcher");
            watchThread.setDaemon(true);
            watchThread.start();
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.warn("[HOT-RELOAD] 启动 Watcher 失败 (不影响游戏, 可手动用快捷键刷新): {}", e.getMessage());
        }
    }

    public void stopHotReloadWatcher() {
        watcherRunning = false;
        if (watchThread != null) {
            watchThread.interrupt();
            watchThread = null;
        }
    }
    
    private void loadExtensionPack(Path zipPath, boolean isServerCache) {
        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            ExtensionPack pack = new ExtensionPack();
            pack.setFilePath(zipPath.toString());
            pack.setFileName(zipPath.getFileName().toString());
            pack.setZipFile(zipFile);
            pack.setFileSize(zipPath.toFile().length());
            pack.setContentSha1(computeSha1Hex(zipPath));
            // 标记来源: server-cache/ 目录下的是服务器同步下来的, 服务端会持有同样的 zip
            pack.setServerBacked(isServerCache);

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
                            // 解析前先拆 key/value, 支持中英文 key 和全角冒号
                            int colonIdx = -1;
                            for (String sep : new String[]{":", "："}) {
                                int idx = line.indexOf(sep);
                                if (idx >= 0) { colonIdx = idx; break; }
                            }
                            if (colonIdx < 0) continue;
                            String key = line.substring(0, colonIdx).trim().toLowerCase();
                            String val = line.substring(colonIdx + 1).trim();
                            if (val.isEmpty()) continue;
                            if (key.equals("package") || key.equals("package_name") || key.equals("标识符")) {
                                pack.setPackageName(val);
                            } else if (key.equals("name") || key.equals("拓展包名") || key.equals("名称")) {
                                pack.setName(val);
                            } else if (key.equals("author") || key.equals("作者")) {
                                pack.setAuthor(val);
                            } else if (key.equals("url") || key.equals("link") || key.equals("相关链接") || key.equals("链接")) {
                                pack.setUrl(val);
                            } else if (key.equals("dependence") || key.equals("dependencies") || key.equals("deps")
                                    || key.equals("依赖模组") || key.equals("依赖")) {
                                // 拆分: 逗号/分号/空白 → 数组, 然后用 DependencyChecker.cleanDepList
                                // 二次清理 (去重 + 去作者注释) 后存进 pack.
                                String[] rawDeps = val.split("[,，;；\\s]+");
                                List<String> cleaned = com.prefab.addon.work.DependencyChecker.cleanDepList(java.util.Arrays.asList(rawDeps));
                                pack.setDependencies(cleaned);
                            } else if (key.equals("description") || key.equals("desc") || key.equals("说明")
                                    || key.equals("描述")) {
                                pack.setDescription(val);
                            } else if (key.equals("version") || key.equals("版本")) {
                                pack.setVersion(val);
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

            // 尝试多种常见文件名 (用户可能用 cover.png / 封面.png / 建筑包图像.png 等)
            String[] coverCandidates = {
                baseFolder + "information/建筑包图像.png",
                baseFolder + "information/建筑包图像.jpg",
                baseFolder + "information/cover.png",
                baseFolder + "information/cover.jpg",
                baseFolder + "information/cover.jpeg",
                baseFolder + "information/封面.png",
                baseFolder + "information/封面.jpg",
                baseFolder + "information/icon.png",
                baseFolder + "information/image.png",
                baseFolder + "images/cover.png",
                baseFolder + "cover.png",
            };
            ZipEntry coverEntry = null;
            String foundCoverPath = null;
            for (String candidate : coverCandidates) {
                ZipEntry e = zipFile.getEntry(candidate);
                if (e != null) {
                    coverEntry = e;
                    foundCoverPath = candidate;
                    break;
                }
            }
            // 大小写不敏感兜底: 扫一遍 zip entries
            if (coverEntry == null) {
                String lowerPrefix = (baseFolder + "information/").toLowerCase();
                java.util.Enumeration<? extends ZipEntry> coverScanEntries = zipFile.entries();
                while (coverScanEntries.hasMoreElements()) {
                    ZipEntry e = coverScanEntries.nextElement();
                    String n = e.getName().toLowerCase();
                    if (n.startsWith(lowerPrefix)) {
                        String name = n.substring(lowerPrefix.length());
                        if (name.startsWith("cover") || name.startsWith("封面") || name.startsWith("建筑包图像") || name.startsWith("icon")) {
                            if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) {
                                coverEntry = e;
                                foundCoverPath = e.getName();
                                break;
                            }
                        }
                    }
                }
            }
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
                    PrefabCustomAddon.LOGGER.info("Loaded cover: {} ({} bytes)", foundCoverPath, baos.size());
                } catch (IOException e) {
                    PrefabCustomAddon.LOGGER.warn("Failed to cache cover image for {}", zipPath);
                }
            } else {
                PrefabCustomAddon.LOGGER.info("No cover image found in {}", zipPath);
            }

            loadConstructions(zipFile, pack, baseFolder);

            PrefabCustomAddon.LOGGER.info("[DIAG-LOAD-PACK] {} 加载后 constructions={}, baseFolder='{}', isServerCache={}",
                zipPath.getFileName(), pack.getConstructions().size(), baseFolder, isServerCache);
            if (!pack.getConstructions().isEmpty()) {
                packs.add(pack);
                PrefabCustomAddon.LOGGER.info("Loaded extension pack: {} (info={}, hasCover={}, constructions={})",
                        pack.getName(), pack.hasInfoFolder(), pack.hasCoverImage(), pack.getConstructions().size());
            } else {
                PrefabCustomAddon.LOGGER.warn("[DIAG-LOAD-PACK] {} constructions 为空, 不加入 packs 列表!", zipPath.getFileName());
            }
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("Failed to load extension pack: {}", zipPath, e);
        }
    }

    private String detectBaseFolder(ZipFile zipFile) {
        // 收集所有 entry 和所有顶层子目录
        java.util.List<String> allNames = new ArrayList<>();
        java.util.Set<String> topFolders = new java.util.LinkedHashSet<>();
        Enumeration<? extends ZipEntry> firstScan = zipFile.entries();
        while (firstScan.hasMoreElements()) {
            ZipEntry e = firstScan.nextElement();
            String n = e.getName();
            allNames.add(n);
            int slash = n.indexOf('/');
            if (slash > 0) {
                topFolders.add(n.substring(0, slash + 1));
            }
        }

        // 1) 优先：直接存在 construction/ 或 information/ 文件夹 → 根目录格式
        for (String n : allNames) {
            if (n.startsWith("construction/") || n.startsWith("information/")) {
                PrefabCustomAddon.LOGGER.info("[detectBaseFolder] step1 命中根目录 construction/information, base=''");
                return "";
            }
        }

        // 2) 新格式：所有文件直接在 zip 根目录（.txt / .nbt / .png 平铺）
        boolean hasRootNbt = false;
        boolean hasRootTxt = false;
        boolean hasRootPng = false;
        for (String n : allNames) {
            if (n.contains("/")) continue;          // 跳过子目录里的
            if (n.endsWith(".nbt")) hasRootNbt = true;
            else if (n.endsWith(".txt")) hasRootTxt = true;
            else if (n.endsWith(".png")) hasRootPng = true;
        }
        if (hasRootNbt && (hasRootTxt || hasRootPng)) {
            PrefabCustomAddon.LOGGER.info("[detectBaseFolder] step2 命中扁平根目录, base=''");
            return "";
        }

        // 3) 旧格式：所有东西放在一个共享的子文件夹下（如 test/construction/... + test/information/...）
        //    要求: 该子文件夹同时存在 construction/ 和 information/ 两个子目录
        for (String folder : topFolders) {
            String testConstruction = folder + "construction/";
            String testInformation = folder + "information/";
            boolean hasConstruction = allNames.stream().anyMatch(n -> n.startsWith(testConstruction));
            boolean hasInformation = allNames.stream().anyMatch(n -> n.startsWith(testInformation));
            if (hasConstruction && hasInformation) {
                PrefabCustomAddon.LOGGER.info("[detectBaseFolder] step3 命中旧格式子文件夹 (有 construction+information): {}", folder);
                return folder;
            }
        }

        // 4) 扩展旧格式: 子文件夹下有 construction/ 但没有 information/ (部分 zip 没 information/ 目录)
        //    例: test/construction/castle.nbt + test/construction/castle.txt (没有 information)
        for (String folder : topFolders) {
            String testConstruction = folder + "construction/";
            long nbts = allNames.stream().filter(n -> n.startsWith(testConstruction) && n.endsWith(".nbt")).count();
            if (nbts > 0) {
                PrefabCustomAddon.LOGGER.info("[detectBaseFolder] step4 命中子文件夹 + construction (无 information): {}, {} 个 nbt", folder, nbts);
                return folder;
            }
        }

        // 5) 兜底: 单一子文件夹 + 扁平文件 (子文件夹里有 .nbt/.txt/.png 但没有 construction/ 子目录)
        //    例: test/castle.nbt + test/castle.txt + test/castle.png
        //    只在"只有一个顶层子文件夹"且该子文件夹里有建筑文件时启用, 避免误判多包合集
        if (topFolders.size() == 1) {
            String folder = topFolders.iterator().next();
            long folderNbt = allNames.stream()
                .filter(n -> n.startsWith(folder) && !n.substring(folder.length()).contains("/"))
                .filter(n -> n.endsWith(".nbt"))
                .count();
            if (folderNbt > 0) {
                PrefabCustomAddon.LOGGER.info("[detectBaseFolder] step5 命中子文件夹 + 扁平: {}, {} 个 nbt", folder, folderNbt);
                return folder;
            }
        }

        // 6) 兜底: 多个子文件夹, 每个子文件夹是一个"独立建筑" (新模式合集)
        //    走 loadConstructions 的多子目录扁平加载逻辑
        PrefabCustomAddon.LOGGER.info("[detectBaseFolder] 没有命中已知格式, 返回空, 让 loadConstructions 兜底. 子文件夹: {}", topFolders);
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

        // 兜底: 主流程没找到任何建筑, 试"多子文件夹合集"格式
        // 例: zip 里多个顶层子文件夹, 每个子文件夹里有 castle.nbt / castle.txt / castle.png
        //     (玩家可能直接把多个独立建筑打包成一个 zip 当合集)
        if (constructions.isEmpty() || constructions.values().stream().noneMatch(c -> c.getNbtEntry() != null)) {
            int fallbackLoaded = loadConstructionsMultiFolderFallback(zipFile, pack, constructions);
            if (fallbackLoaded > 0) {
                PrefabCustomAddon.LOGGER.info("[loadConstructions] 主流程没找到建筑, 多子文件夹兜底加载了 {} 个", fallbackLoaded);
            }
        }

        constructions.values().stream()
            .filter(c -> c.getNbtEntry() != null)
            .forEach(pack.getConstructions()::add);
    }

    /**
     * 兜底: 遍历所有顶层子文件夹, 每个子文件夹看作一个"独立建筑包",
     * 把子文件夹里的 .nbt/.txt/.png 合并为一个 ConstructionInfo.
     * <p>用于兼容"玩家把多个独立建筑打成一个 zip 当合集"的情况.</p>
     */
    private int loadConstructionsMultiFolderFallback(ZipFile zipFile, ExtensionPack pack,
                                                      Map<String, ConstructionInfo> constructions) {
        java.util.Set<String> topFolders = new java.util.LinkedHashSet<>();
        Enumeration<? extends ZipEntry> scan = zipFile.entries();
        while (scan.hasMoreElements()) {
            String n = scan.nextElement().getName();
            int slash = n.indexOf('/');
            if (slash > 0) topFolders.add(n.substring(0, slash + 1));
        }

        int loaded = 0;
        for (String folder : topFolders) {
            // 收集这个 folder 里的所有文件
            java.util.Map<String, String> filesByBase = new java.util.LinkedHashMap<>();
            Enumeration<? extends ZipEntry> scan2 = zipFile.entries();
            ZipEntry nbtEntry = null;
            ZipEntry txtEntry = null;
            ZipEntry pngEntry = null;
            String firstBaseName = null;
            while (scan2.hasMoreElements()) {
                ZipEntry e = scan2.nextElement();
                String n = e.getName();
                if (!n.startsWith(folder)) continue;
                String rest = n.substring(folder.length());
                if (rest.isEmpty() || rest.contains("/")) continue; // 子子目录跳过
                int dot = rest.lastIndexOf('.');
                if (dot <= 0) continue;
                String base = rest.substring(0, dot);
                String ext = rest.substring(dot + 1).toLowerCase();
                if (firstBaseName == null) firstBaseName = base;
                if (ext.equals("nbt")) nbtEntry = e;
                else if (ext.equals("txt")) txtEntry = e;
                else if (ext.equals("png")) pngEntry = e;
            }
            if (nbtEntry == null) continue;  // 没有 .nbt 就不算建筑

            // folder 名作为建筑 id (去掉尾部 '/')
            String constructionId = folder.endsWith("/") ? folder.substring(0, folder.length() - 1) : folder;
            ConstructionInfo info = new ConstructionInfo(constructionId);
            info.setPack(pack);
            info.setName(firstBaseName != null ? firstBaseName : constructionId);
            info.setNbtEntry(nbtEntry);
            try (InputStream is = zipFile.getInputStream(nbtEntry)) {
                info.setNbtData(is.readAllBytes());
            } catch (IOException ex) {
                PrefabCustomAddon.LOGGER.warn("[多子文件夹兜底] 读 {} 失败: {}", nbtEntry.getName(), ex.toString());
                continue;
            }
            if (txtEntry != null) {
                try (InputStream is = zipFile.getInputStream(txtEntry);
                     BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        parseConstructionLine(line, info);
                    }
                } catch (Exception ex) {
                    try (InputStream is = zipFile.getInputStream(txtEntry);
                         BufferedReader reader = new BufferedReader(new InputStreamReader(is, "GBK"))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            parseConstructionLine(line, info);
                        }
                    } catch (Exception ex2) {
                        // txt 解码失败不算致命错误
                    }
                }
            }
            if (pngEntry != null) {
                try (InputStream is = zipFile.getInputStream(pngEntry);
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = is.read(buffer)) != -1) baos.write(buffer, 0, len);
                    info.setPngData(baos.toByteArray());
                } catch (IOException ex) {
                    // png 加载失败不算致命错误
                }
            }
            constructions.put(constructionId, info);
            loaded++;
        }
        return loaded;
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
     * 运行时重扫拓展包目录。供 /prefabaddon reload 指令和玩家手动"同步"按钮调用。
     * 清空内存中的 packs 列表, 然后重跑 scanExtensionPacks() 把磁盘上的 zip 全部重新加载。
     * 保留 extensionDir 路径不变 (服务器工作目录不会动)。
     */
    public synchronized int reload() {
        int before = this.packs.size();
        // 关掉旧的 zipFile 句柄, 避免文件锁导致扫描失败
        for (ExtensionPack p : this.packs) {
            try {
                if (p.getZipFile() != null) p.getZipFile().close();
            } catch (Exception ignored) {}
        }
        this.packs.clear();
        if (this.extensionDir != null && Files.exists(this.extensionDir)) {
            scanExtensionPacks();
        } else {
            PrefabCustomAddon.LOGGER.warn("[RELOAD] extensionDir 未初始化或不存在, 不扫");
        }
        int after = this.packs.size();
        PrefabCustomAddon.LOGGER.info("[RELOAD] Extension pack manager reloaded: {} -> {} packs (extensionDir={})",
            before, after, this.extensionDir);
        return after;
    }

    /**
     * 服务器→客户端同步用：返回所有已加载拓展包的 (name, sha1, size) 列表。
     * name = packageName（缓存文件名用），sha1 = zip 内容指纹，size = zip 字节数。
     */
    public List<com.prefab.addon.network.ServerPackManifestPayload.Entry> getSyncManifest() {
        List<com.prefab.addon.network.ServerPackManifestPayload.Entry> list = new ArrayList<>();
        for (ExtensionPack p : packs) {
            String name = p.getPackageName();
            String sha1 = p.getContentSha1();
            long size = p.getFileSize();
            if (name == null || name.isEmpty() || sha1 == null) continue; // 跳过无 package 的异常包
            list.add(new com.prefab.addon.network.ServerPackManifestPayload.Entry(name, sha1, size));
        }
        return list;
    }

    /**
     * 服务端按 packageName 找 zip 文件路径（用于读取并分片发到客户端）。
     * 返回 null 表示该包不存在 / 已被管理员删除。
     */
    public java.nio.file.Path findPackZipPath(String packageName) {
        for (ExtensionPack p : packs) {
            if (packageName.equals(p.getPackageName())) {
                String fp = p.getFilePath();
                if (fp == null) return null;
                return java.nio.file.Paths.get(fp);
            }
        }
        return null;
    }

    /**
     * 返回所有**有 information/ 子目录**的拓展包（即"标准格式"）。
     * Z 键的拓展包管理界面只显示这类（扁平格式如 拓展包示例2 不显示）。
     *
     * <p>多人模式下额外过滤：只显示 serverBacked=true 的包（来自 server-cache/），
     * 隐藏本地手动放的 zip。原因：本地的 zip 在服务器上找不到对应 NBT，
     * 玩家点这些包后会"看似可以建造但实际失败"，体验很差。</p>
     */
    public List<ExtensionPack> getDiscoverablePacks() {
        return getDisplayedPacks();
    }

    /**
     * 给 GUI 用的"显示"列表:
     *   - 过滤掉没有 information/ 子目录的扁平包
     *   - 多人模式下再过滤掉非 serverBacked 的本地包
     *
     * 与 getDiscoverablePacks() 等价, 但语义更清楚. GuiCustomStructureSelection.PACKS
     * 视图也用这个, 跟 Z 键拓展包管理界面行为保持一致.
     */
    public List<ExtensionPack> getDisplayedPacks() {
        boolean filterServerBacked = shouldFilterToServerBackedOnly();
        return packs.stream()
            .filter(ExtensionPack::hasInfoFolder)
            .filter(p -> !filterServerBacked || p.isServerBacked())
            .collect(Collectors.toList());
    }

    /**
     * 是否应该把"非 server-backed"的拓展包从 GUI 里隐藏.
     * 规则:
     *   - 单人游戏: 不隐藏 (集成服能扫到本地包, 本地包也能建造)
     *   - 多人游戏 (连接服务器时): 隐藏 (本地包在服务器上不可建造, 显示出来只会误导)
     */
    private static boolean shouldFilterToServerBackedOnly() {
        try {
            // 单人游戏 (Minecraft.getInstance().getSingleplayerServer() != null) 保留所有
            if (Minecraft.getInstance().getSingleplayerServer() != null) return false;
        } catch (Exception ignored) {}
        // 其他情况 (多人客户端) → 隐藏本地包, 只显示 server-cache/ 里的
        return true;
    }

    /**
     * 按 (packName, constructionId) 找建筑. 服务端 build 路径用.
     * - packName 是 STANDALONE_PACKAGE 时, 走单文件建筑 (LocalBuilding) 兜底
     * - 其他 packName, 按 packName 找 pack, 然后按 id 找 construction
     */
    public ConstructionInfo findConstruction(String packName, String constructionId) {
        for (ExtensionPack pack : packs) {
            if (!pack.getName().equals(packName)) continue;
            for (ConstructionInfo info : pack.getConstructions()) {
                if (info.getId().equals(constructionId)) {
                    return info;
                }
            }
        }
        // 兜底: STANDALONE_PACKAGE 来自单文件建筑 (LocalBuilding) 右键,
        // 单文件 NBT 不在任何 ExtensionPack 里, 必须走 LocalBuildingScanner 兜底.
        if (STANDALONE_PACKAGE.equals(packName)) {
            return findLocalBuildingFallback(constructionId);
        }
        return null;
    }

    /**
     * 退化查找: 只按 constructionId 找, 忽略 packName.
     * 用于兼容旧版本蓝图 (旧版本 bind 时存了 getPackageName(), 跟 findConstruction 用
     * 的 getName() 不一致导致直接 lookup 失败), 找到第一个匹配返回.
     * 注意: 多个 pack 有同名 construction 时只会返回第一个, 用户应尽快重新绑定以修好.
     */
    public ConstructionInfo findByConstructionIdOnly(String constructionId) {
        for (ExtensionPack pack : packs) {
            for (ConstructionInfo info : pack.getConstructions()) {
                if (info.getId().equals(constructionId)) {
                    return info;
                }
            }
        }
        return null;
    }

    /**
     * 单文件建筑 (LocalBuilding) 查找: 走 LocalBuildingScanner.scanAll(), 找到匹配的 id 后
     * 包装成 ConstructionInfo (pack=null, name/author/desc 从 .txt 读取).
     * 找不到返回 null. 用在右键本地建筑蓝图时: 该 construction 不在任何 ExtensionPack 里,
     * 但单文件 NBT 还在 prefab-extension/ 或 prefab-download/ 里.
     */
    public ConstructionInfo findLocalBuildingFallback(String constructionId) {
        try {
            for (LocalBuilding lb : LocalBuildingScanner.scanAll()) {
                if (!constructionId.equals(lb.id)) continue;
                ConstructionInfo c = new ConstructionInfo(lb.id);
                c.setName(lb.name);
                c.setAuthor(lb.author);
                c.setDescription(lb.description);
                if (lb.fileExt != null && !lb.fileExt.isEmpty()) {
                    c.setFormat(lb.fileExt.startsWith(".") ? lb.fileExt.substring(1) : lb.fileExt);
                }
                c.setLocalImagePath(lb.imagePath);
                c.setLocalNbtPath(lb.filePath);
                return c;
            }
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[EPM] findLocalBuildingFallback failed", t);
        }
        return null;
    }

    /**
     * 判断一个建筑当前**实际可建造**:
     *   - 单人游戏 (集成服): 主目录里的本地包也是可建造的 (服务端=本地)
     *   - 多人游戏: 只有 server-cache/ 里的包 (serverBacked=true) 可建造
     *
     * 必须在客户端线程调用, 因为内部要访问 Minecraft.getInstance().
     */
    public static boolean isBuildable(ConstructionInfo c) {
        if (c == null || c.getPack() == null) return false;
        ExtensionPack pack = c.getPack();
        // 单人模式: 任何本地加载的包都可用 (集成服会扫同一份本地目录)
        if (Minecraft.getInstance().getSingleplayerServer() != null) return true;
        // 多人模式: 只有服务器同步下来的才能在服务器找到 NBT
        return pack.isServerBacked();
    }

    /**
     * 按包粒度的可建造判断 (用于 PACKS 视图的列表显示).
     */
    public static boolean isBuildable(ExtensionPack pack) {
        if (pack == null) return false;
        if (Minecraft.getInstance().getSingleplayerServer() != null) return true;
        return pack.isServerBacked();
    }

    public List<ConstructionInfo> getAllConstructions() {
        List<ConstructionInfo> all = new ArrayList<>();
        for (ExtensionPack pack : packs) {
            all.addAll(pack.getConstructions());
        }
        return all;
    }

    // ============================================================
    // 新版 GUI (扩展包/建筑/服务器/收藏/下载 标签页) 用的辅助查询
    // ============================================================

    /**
     * 固定标识: standalone (独立 .nbt) 包的 packageName.
     * 用于收藏键 ("__standalone__/<file>") 和 GUI 分组.
     */
    public static final String STANDALONE_PACKAGE = "__standalone__";

    /**
     * "服务器" 标签页用的列表: 显示已同步到 server-cache/ 的所有建筑.
     * <p>包含标准 zip 拓展包 (hasInfoFolder=true) 和单文件建筑 (nbt/litematic/schem, hasInfoFolder=false).
     * 玩家同步下来的 (serverBacked=true) 都会显示.</p>
     * <p>之所以不再过滤 hasInfoFolder: 单文件建筑通过 pack sync 同步到 server-cache/ 后, 也是
     * 可建造资源, 应该出现在服务器 tab. 如果过滤掉, 玩家就只能通过建筑 tab 看到, 体验不直观.</p>
     */
    public List<ExtensionPack> getServerCachePacks() {
        return packs.stream()
            .filter(ExtensionPack::isServerBacked)
            .collect(Collectors.toList());
    }

    /**
     * "服务器" 标签页用的扩展列表: 返回所有服务端可同步的建筑 (含已同步 + 未同步).
     * <p>从 {@link com.prefab.addon.network.ServerPackSyncClient} 缓存的 manifest 读服务端建筑,
     * 再扫本地 server-cache/ 算每个的 synced 状态.</p>
     * <p>未同步的只显示基础信息 (name, sha1, size, 状态), 没有缩略图/描述等,
     * 因为这些信息要从文件读, 未同步就拿不到.</p>
     */
    public List<ServerBuildingInfo> getServerBuildings() {
        java.util.List<com.prefab.addon.network.ServerPackManifestPayload.Entry> manifest =
            com.prefab.addon.network.ServerPackSyncClient.getInstance().getServerManifestSnapshot();
        if (manifest == null || manifest.isEmpty()) return java.util.Collections.emptyList();

        Path cacheDir = getServerCacheDir();
        // 收集本地 server-cache/ 里所有建筑文件, 按 name (去后缀) 索引
        java.util.Map<String, Path> localByName = new java.util.HashMap<>();
        if (cacheDir != null && Files.exists(cacheDir)) {
            try (java.util.stream.Stream<Path> s = Files.list(cacheDir)) {
                s.filter(Files::isRegularFile).forEach(p -> {
                    String fn = p.getFileName().toString();
                    int dot = fn.lastIndexOf('.');
                    if (dot <= 0) return;
                    String base = fn.substring(0, dot);
                    String ext = fn.substring(dot).toLowerCase();
                    // 排除 .png / .txt (单文件建筑的辅助文件)
                    if (ext.equals(".png") || ext.equals(".txt") || ext.equals(".jpg")
                        || ext.equals(".jpeg") || ext.equals(".gif") || ext.equals(".webp")) return;
                    // 同名文件: 优先 .zip (标准拓展包), 否则任意
                    Path existing = localByName.get(base);
                    if (existing == null || ext.equals(".zip")) {
                        localByName.put(base, p);
                    }
                });
            } catch (java.io.IOException e) {
                PrefabCustomAddon.LOGGER.warn("[SERVER-BUILDINGS] Failed to list server-cache: {}", e.getMessage());
            }
        }

        java.util.List<ServerBuildingInfo> result = new java.util.ArrayList<>();
        for (var e : manifest) {
            if (e.name() == null || e.name().isEmpty()) continue;
            Path localPath = localByName.get(e.name());
            boolean synced = false;
            String ext = "";
            if (localPath != null) {
                String fn = localPath.getFileName().toString();
                int dot = fn.lastIndexOf('.');
                if (dot > 0) ext = fn.substring(dot);
                try {
                    String localSha1 = computeSha1Hex(localPath);
                    synced = e.sha1().equalsIgnoreCase(localSha1);
                } catch (Exception ex) {
                    synced = false;
                }
            }
            result.add(new ServerBuildingInfo(e.name(), e.sha1(), e.size(), synced, ext));
        }
        return result;
    }

    /**
     * "扩展包" 标签页用的列表: 标准格式 + 信息文件 + 多人模式过滤本地包 (跟 getDiscoverablePacks 等价).
     */
    public List<ExtensionPack> getPacksForGui() {
        return getDisplayedPacks();
    }

    /**
     * "建筑" 标签页用的列表: 所有 construction (包括扩展包内 + 独立 .nbt + 独立 .litematic + 独立 .schem).
     *
     * <p><b>不要</b>再按 serverBacked 过滤: 当拓展包同时存在于主目录和 server-cache/ 时,
     * dedupeByPackageName 会保留 server-cache 版本 (serverBacked=true, 玩家能在服务器上造).
     * 如果这里再过滤, 整个"建筑" tab 会变 0, 玩家看不到任何建筑.</p>
     *
     * <p>"服务器" tab 独立使用 {@link #getServerBuildings()}, 跟这里没有重复问题.</p>
     */
    public List<ConstructionInfo> getAllConstructionsForGui() {
        java.util.List<ConstructionInfo> all = getAllConstructions();
        PrefabCustomAddon.LOGGER.info("[DIAG-ALL-CONST] getAllConstructions 总数: {}", all.size());
        // 按 pack 名字分组输出
        java.util.Map<String, Integer> byPack = new java.util.LinkedHashMap<>();
        for (ConstructionInfo c : all) {
            String pname = c.getPack() == null ? "<no-pack>" : c.getPack().getName();
            byPack.merge(pname, 1, Integer::sum);
        }
        byPack.forEach((k, v) -> PrefabCustomAddon.LOGGER.info("[DIAG-ALL-CONST]   pack '{}': {} 个建筑", k, v));
        // 不再按 serverBacked 过滤: 建筑 tab 显示全部 dedup 后留下的建筑.
        // "服务器" tab 用 getServerBuildings(), 跟这里用不同数据源, 不会重复.
        PrefabCustomAddon.LOGGER.info("[DIAG-ALL-CONST] 建筑 tab 实际返回: {} (含 serverBacked, 因为 dedup 后每个包只剩一份)", all.size());
        return all;
    }

    /**
     * 独立建筑 (不在任何 zip 拓展包内, 文件直接放 prefab-extension 根目录的 .nbt / .litematic / .schem).
     * packageName == STANDALONE_PACKAGE 即可识别.
     */
    public List<ConstructionInfo> getStandaloneConstructions() {
        List<ConstructionInfo> list = new ArrayList<>();
        for (ExtensionPack p : packs) {
            if (STANDALONE_PACKAGE.equals(p.getPackageName())) {
                list.addAll(p.getConstructions());
            }
        }
        return list;
    }

    /**
     * 收藏标签页: 根据 favoriteKeys 列表, 在所有已加载建筑里查找匹配项.
     * 找不到的键 (比如该建筑已被玩家从磁盘删除) 会被静默跳过.
     *
     * <p>匹配规则:
     * <ul>
     *   <li>键格式 "packageName/constructionId" → 找 packageName + id 匹配的建筑
     *   <li>packageName == STANDALONE_PACKAGE 时 → 找 constructionId 匹配的独立建筑
     * </ul>
     */
    public List<ConstructionInfo> getFavoriteConstructions() {
        List<ConstructionInfo> result = new ArrayList<>();
        List<String> keys = com.prefab.addon.config.PlayerPreferences.get().getFavoriteKeys();
        for (String key : keys) {
            String[] parts = com.prefab.addon.config.PlayerPreferences.parseKey(key);
            String pkg = parts[0];
            String cid = parts[1];
            ConstructionInfo hit = findConstructionByPackageAndId(pkg, cid);
            if (hit == null) {
                // 退化: 普通包没找到 (例如 LocalBuilding 转的建筑, 它们没在 packs 列表里).
                // 直接去 LocalBuildingScanner 拿, 还原成 ConstructionInfo 即可.
                hit = findLocalBuildingFallback(cid);
            }
            if (hit != null) result.add(hit);
        }
        return result;
    }

    /**
     * 按 (packageName, constructionId) 找建筑.
     * - pkg = STANDALONE_PACKAGE 时, 在所有 standalone 包里按 id 匹配
     * - 其他 pkg, 按 packageName 找 pack, 然后按 id 找 construction
     *   (兼容老版本: packageName 不匹配时, 退化到按 id 全局查找)
     */
    public ConstructionInfo findConstructionByPackageAndId(String packageName, String constructionId) {
        if (constructionId == null || constructionId.isEmpty()) return null;
        if (STANDALONE_PACKAGE.equals(packageName)) {
            for (ExtensionPack p : packs) {
                if (!STANDALONE_PACKAGE.equals(p.getPackageName())) continue;
                for (ConstructionInfo c : p.getConstructions()) {
                    if (constructionId.equals(c.getId())) return c;
                }
            }
            return null;
        }
        // 普通包: 严格按 packageName + id
        for (ExtensionPack p : packs) {
            String pkg = p.getPackageName();
            if (pkg == null || !pkg.equals(packageName)) continue;
            for (ConstructionInfo c : p.getConstructions()) {
                if (constructionId.equals(c.getId())) return c;
            }
        }
        // 退化: packageName 不匹配 (老建筑), 按 id 找第一个
        for (ExtensionPack p : packs) {
            for (ConstructionInfo c : p.getConstructions()) {
                if (constructionId.equals(c.getId())) return c;
            }
        }
        return null;
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
                String[] rawDeps = deps.split("\\s*,\\s*");
                // 关键: 用 cleanDepList 去重 + 清理 (去掉作者注释等)
                // 避免 "航空学" 这类同名 mod 被显示两次
                List<String> cleaned =
                    com.prefab.addon.work.DependencyChecker.cleanDepList(java.util.Arrays.asList(rawDeps));
                info.setDependencies(cleaned);
            }
        } else if (line.startsWith("建筑标识符:")) {
            return line.substring("建筑标识符:".length()).trim();
        } else if (line.startsWith("蓝图格式:")) {
            // 蓝图格式 (nbt / litematic / schem). 创建建筑时填的. 空表示"未知"
            String fmt = line.substring("蓝图格式:".length()).trim();
            if (!fmt.isEmpty()) {
                info.setFormat(fmt);
            }
        } else if (line.startsWith("图标:")) {
            // 蓝图显示图标 (物品 id, 如 minecraft:stone). 空 = 默认
            String icon = line.substring("图标:".length()).trim();
            if (!icon.isEmpty()) {
                info.setIcon(icon);
            }
        }
        return null;
    }
}
