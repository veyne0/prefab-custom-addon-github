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

        Path versionExtensionDir = findVersionExtensionDir(versionsDir);

        if (versionExtensionDir != null) {
            this.extensionDir = versionExtensionDir;
        } else if (Files.exists(rootExtensionDir)) {
            this.extensionDir = rootExtensionDir;
        } else {
            this.extensionDir = rootExtensionDir;
        }
        this.isServerSide = false;
        PrefabCustomAddon.LOGGER.info("Using extension directory: {}", extensionDir);
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

        // 重建 packs: 每组只保留一个
        packs.clear();
        for (Map.Entry<String, List<ExtensionPack>> e : byPkg.entrySet()) {
            List<ExtensionPack> group = e.getValue();
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
     * 扫描单个目录里的所有 .zip，加载为 ExtensionPack。
     * @param dir         要扫描的目录（不存在则跳过）
     * @param mtimeMap    若非 null，把 (文件名 → mtime) 写入此 map（主目录用）
     * @param isServerCache 是否是服务器缓存目录：是则日志加 [SERVER-CACHE] 前缀，
     *                      同时把加载出来的包标记为 serverBacked=true
     */
    private void scanDirInto(Path dir, Map<String, Long> mtimeMap, boolean isServerCache) {
        if (dir == null || !Files.exists(dir) || !Files.isDirectory(dir)) return;

        String tag = isServerCache ? "[SERVER-CACHE] " : "";
        try {
            // 同时支持 .zip / .litematic / .schem / .schematic
            List<Path> zipFiles = Files.list(dir)
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String n = p.getFileName().toString().toLowerCase();
                    return n.endsWith(".zip") || n.endsWith(".litematic")
                        || n.endsWith(".schem") || n.endsWith(".schematic");
                })
                .collect(java.util.stream.Collectors.toList());

            PrefabCustomAddon.LOGGER.info("{}Found {} extension files in {}", tag, zipFiles.size(), dir);
            for (Path file : zipFiles) {
                if (mtimeMap != null) {
                    mtimeMap.put(file.getFileName().toString(), safeMTime(file));
                }
                PrefabCustomAddon.LOGGER.info("{}Found extension file: {}", tag, file);
            }

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
                }
            }
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("{}Failed to scan directory {}", tag, dir, e);
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
            stream.filter(p -> p.toString().endsWith(".zip"))
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

            if (!pack.getConstructions().isEmpty()) {
                packs.add(pack);
                PrefabCustomAddon.LOGGER.info("Loaded extension pack: {} (info={}, hasCover={}, constructions={})",
                        pack.getName(), pack.hasInfoFolder(), pack.hasCoverImage(), pack.getConstructions().size());
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
        }
        return null;
    }
}
