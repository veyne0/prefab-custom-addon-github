package com.prefab.addon.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 玩家/全局偏好设置 (挑战模式、消耗材料、建造速度等).
 *
 * <h2>作用范围</h2>
 * <ul>
 *   <li><b>预览速度 previewBatchPercent</b>: 客户端 AsyncPreviewBatcher 读取, <strong>个人</strong>生效 (每个客户端各自一份).
 *       SettingsGui 滑条直接写本地, 不走网络包.</li>
 *   <li><b>建造速度 buildBatchPercent</b>: 服务端 AsyncBuildManager.processTick 读取, <strong>全服共享</strong>.
 *       SettingsGui 滑条改变时发 {@link com.prefab.addon.network.UpdateBuildSpeedPayload} 给服务端,
 *       服务端 OP 校验通过后写入自己的 PlayerPreferences, 再用 {@link com.prefab.addon.network.SyncBuildSpeedPayload}
 *       广播给所有客户端, 让每个客户端的 GUI 滑条显示最新值.</li>
 * </ul>
 *
 * <h2>路径解析</h2>
 * 单人/整合服: 客户端和服务端共享同一个 JVM, 同一个 PlayerPreferences 实例, 都用 client 目录.
 * 专用服务器: 服务端用 {@link MinecraftServer#getServerDirectory()}, 客户端用 {@link Minecraft#getInstance()}.gameDirectory,
 * 两者目录不同, 各自的设置互不干扰 (这正是"建造速度全服, 预览速度个人"的关键).
 *
 * <h2>持久化</h2>
 * 写入 {@code <gameDir>/config/prefab_addon/preferences.json}.
 */
public class PlayerPreferences {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static PlayerPreferences instance;
    private static final Object LOCK = new Object();

    // 字段 - 都会序列化到 JSON
    // 默认开启挑战模式: 适应服务器环境, 防止 OP 忘开.
    // 老用户如果之前 JSON 里有 consumeMaterials:false, load() 会一次性升级到 true.
    public boolean consumeMaterials = false;       // 挑战模式: 建造是否消耗材料 (默认 false, 关闭)
    public boolean autoDepCheckOnOpen = false;     // 打开 GUI 时自动检测依赖 (默认 false)
    public int lastSelectedPackIndex = 0;          // 上次选中的拓展包索引

    // === 异步批处理配置 (大建筑性能优化) ===
    // 预览生成: 每次 tick 处理的方块百分比, 范围 1..100, **默认 1%** (最慢最稳, 不卡)
    //   - 客户端个人设置, 不走网络
    //   - 1% × 100 ticks ≈ 5s 完成 1000 块预览, 1% × 1000 ticks ≈ 50s 完成 40k 块
    public int previewBatchPercent = 1;
    // 建造速度: 每次 tick 放置的方块百分比, 范围 1..100, **默认 1%** (最慢最稳, 玩家要求)
    //   - 全服共享, SettingsGui 改完发 UpdateBuildSpeedPayload 给服务端, OP 校验
    //   - 服务端写自己的 PlayerPreferences, 然后用 SyncBuildSpeedPayload 广播给所有客户端
    public int buildBatchPercent = 1;
    // === 建造动画模式 (纯客户端视觉效果, 2026-08 改: 改用枚举支持 4 种) ===
    // 默认 OFF, 玩家在设置里切到 FALL (竖直下落) / RAIN (方块雨) / THROW (四周抛过来) 三种之一.
    // 服务端在 AsyncBuildManager 启动 task 时读取玩家偏好, 通过 BatchBlocksPlacedPayload 传给客户端,
    // 客户端用 BuildAnimationRenderer 按 mode 计算每个方块的渲染轨迹.
    // 老的 enableBuildAnimation 字段已删除, 字段重命名为 buildAnimationMode (枚举).
    // 兼容迁移: load() 时检查老 JSON 有 enableBuildAnimation=true, 自动迁移到 buildAnimationMode=FALL.
    public BuildAnimationMode buildAnimationMode = BuildAnimationMode.OFF;
    /** 动画起始高度 (FALL/RAIN 用). 固定 8, 留字段以后扩展成 slider. */
    public int buildAnimHeight = 8;

    // === KubeJS 联动: 强制启用 "制作蓝图" tab (调试用) ===
    // 默认 false (自动检测). 当自动检测死活过不了 (mod id 不一致 / 类加载器怪) 时,
    // 玩家在设置里勾上这个, 直接绕过检测. 设置保存到 preferences.json, 重启后还在.
    public boolean forceKubeJSTab = false;

    // === 收藏的建筑 (GUI "收藏" 标签页显示) ===
    // 存储键: "packageName/constructionId"
    //   - 拓展包内建筑: "my_pack/castle"
    //   - 独立 .litematic/.schem/.nbt: "__standalone__/<filename>"  (包名段固定为 __standalone__, 建筑名段 = 文件名去后缀)
    public List<String> favoriteKeys = new ArrayList<>();

    public PlayerPreferences() {}

    public static PlayerPreferences get() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = load();
                }
            }
        }
        return instance;
    }

    /**
     * 解析 preferences.json 的路径. 跨平台:
     * <ul>
     *   <li>客户端 (包括整合服): 优先用 {@link Minecraft#getInstance()}.gameDirectory</li>
     *   <li>专用服务端: 用 {@link FMLPaths#GAMEDIR} (fallback, 因为 Minecraft.getInstance() 是 null)</li>
     * </ul>
     */
    private static Path getPrefsFile() {
        Path gameDir = resolveGameDir();
        return gameDir.resolve("config").resolve("prefab_addon").resolve("preferences.json");
    }

    /**
     * 解析当前进程使用的 game directory. 客户端拿不到 Minecraft 实例时 (专用服务端或很早的 FML 阶段) 用 FMLPaths.
     */
    private static Path resolveGameDir() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.gameDirectory != null) {
                return mc.gameDirectory.toPath();
            }
        } catch (Throwable ignored) {
            // 专用服务端 Minecraft.getInstance() 可能抛 NoClassDefFoundError 或返回 null, 都兜底
        }
        return FMLPaths.GAMEDIR.get();
    }

    private static PlayerPreferences load() {
        Path file = getPrefsFile();
        // 一次性迁移: 老 prefab-extension/preferences.json 还在, 拷到新位置
        if (!Files.exists(file)) {
            Path gameDir = resolveGameDir();
            java.util.List<Path> legacyFiles = legacyLocations(gameDir);
            for (Path legacy : legacyFiles) {
                if (Files.exists(legacy)) {
                    try {
                        Files.createDirectories(file.getParent());
                        Files.copy(legacy, file);
                        PrefabCustomAddon.LOGGER.warn("[PREFS] 已从老位置迁移偏好: {} → {}", legacy, file);
                        break;
                    } catch (IOException e) {
                        PrefabCustomAddon.LOGGER.warn("[PREFS] 从 {} 迁移失败: {}", legacy, e.getMessage());
                    }
                }
            }
        }
        if (!Files.exists(file)) {
            PrefabCustomAddon.LOGGER.info("[PREFS] 偏好文件不存在, 使用默认值 (consumeMaterials=true, previewBatchPercent=1, buildBatchPercent=1): {}", file);
            return new PlayerPreferences();
        }
        // 先读原始文本 - 检查是否显式存了 "consumeMaterials": false (老用户), 用于一次性升级
        String rawJson = null;
        try {
            rawJson = Files.readString(file);
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("[PREFS] 读取原始 JSON 失败: {}", e.getMessage());
        }
        try (Reader r = Files.newBufferedReader(file)) {
            PlayerPreferences p = GSON.fromJson(r, PlayerPreferences.class);
            if (p == null) p = new PlayerPreferences();

            // === 2026-08 老字段迁移: enableBuildAnimation (boolean) → buildAnimationMode (enum) ===
            // Gson 默认会忽略未知字段, 老存档里 "enableBuildAnimation": true 会被忽略, 导致
            //   玩家之前开启的下落动画突然没了. 改用 rawJson 扫一遍, 一次性迁移.
            if (p.buildAnimationMode == BuildAnimationMode.OFF && rawJson != null
                    && rawJson.matches("(?is).*[\"']enableBuildAnimation[\"']\\s*:\\s*true.*")) {
                PrefabCustomAddon.LOGGER.warn("[PREFS] 1.6.0 升级: 旧版 enableBuildAnimation=true 迁移到 buildAnimationMode=FALL");
                p.buildAnimationMode = BuildAnimationMode.FALL;
                p.save();
            }

            // 老用户迁移: 1.2.0 之前默认是 consumeMaterials=true (挑战模式开启)
            // 1.2.0 改成默认 false (关闭), 但已存了 "consumeMaterials":true 的用户继续保留 true
            if (p.consumeMaterials && rawJson != null
                    && !rawJson.matches("(?is).*[\"']consumeMaterials[\"']\\s*:\\s*true.*")) {
                PrefabCustomAddon.LOGGER.warn("[PREFS] 1.2.0 升级: 旧版无明确配置, 默认改为 consumeMaterials=false");
                p.consumeMaterials = false;
                p.save();
            }
            PrefabCustomAddon.LOGGER.info("[PREFS] 加载偏好: consumeMaterials={} autoDepCheckOnOpen={} lastPackIdx={} previewBatchPercent={} buildBatchPercent={}",
                p.consumeMaterials, p.autoDepCheckOnOpen, p.lastSelectedPackIndex,
                p.previewBatchPercent, p.buildBatchPercent);
            return p;
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("[PREFS] 加载失败, 使用默认值: {}", e.getMessage());
            return new PlayerPreferences();
        }
    }

    /** 老版本存放偏好文件的所有可能位置 (按优先级倒序) */
    private static java.util.List<Path> legacyLocations(Path gameDir) {
        java.util.List<Path> list = new java.util.ArrayList<>();
        // 1) 根目录 .minecraft/prefab-extension/preferences.json
        list.add(gameDir.resolve("prefab-extension").resolve("preferences.json"));
        // 2) 版本专属目录 versions/<ver>/prefab-extension/preferences.json
        Path versionsDir = gameDir.resolve("versions");
        if (Files.exists(versionsDir) && Files.isDirectory(versionsDir)) {
            try (var stream = Files.list(versionsDir)) {
                stream.filter(Files::isDirectory).forEach(d ->
                    list.add(d.resolve("prefab-extension").resolve("preferences.json")));
            } catch (IOException ignored) {}
        }
        return list;
    }

    public void save() {
        synchronized (LOCK) {
            Path file = getPrefsFile();
            try {
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                try (Writer w = Files.newBufferedWriter(file)) {
                    GSON.toJson(this, w);
                }
                PrefabCustomAddon.LOGGER.info("[PREFS] 保存偏好: consumeMaterials={} previewBatchPercent={} buildBatchPercent={}",
                    this.consumeMaterials, this.previewBatchPercent, this.buildBatchPercent);
            } catch (IOException e) {
                PrefabCustomAddon.LOGGER.error("[PREFS] 保存失败: {}", e.getMessage());
            }
        }
    }

    public void toggleConsumeMaterials() {
        this.consumeMaterials = !this.consumeMaterials;
        save();
        PrefabCustomAddon.LOGGER.info("[PREFS] 切换 consumeMaterials = {}", this.consumeMaterials);
    }

    /**
     * 显式设置 consumeMaterials (供 GUI 调用, 而不是 toggle).
     * 新版 SettingsGui (LDLib2) 的 Toggle 直接给布尔值, 不再 toggle.
     */
    public void setConsumeMaterials(boolean value) {
        if (this.consumeMaterials == value) return;
        this.consumeMaterials = value;
        save();
        PrefabCustomAddon.LOGGER.info("[PREFS] 设置 consumeMaterials = {}", this.consumeMaterials);
    }

    /**
     * 预览批处理百分比 (1..100). 限制范围, 避免异常值.
     */
    public int getPreviewBatchPercent() {
        return clampPercent(this.previewBatchPercent, 1);
    }

    /**
     * 建造批处理百分比 (1..100). 限制范围, 避免异常值.
     * <p>注意: 这个值在<strong>服务端</strong>被 AsyncBuildManager 读取, 真正的"建造速度"是服务端的.
     * 客户端读到的是 {@link SyncBuildSpeedPayload} 同步过来的值, 仅供 GUI 显示.</p>
     */
    public int getBuildBatchPercent() {
        return clampPercent(this.buildBatchPercent, 1);
    }

    /**
     * 客户端 GUI 直接调, 写本地 previewBatchPercent (个人设置, 不走网络).
     */
    public void setPreviewBatchPercent(int percent) {
        this.previewBatchPercent = clampPercent(percent, 1);
        save();
        PrefabCustomAddon.LOGGER.info("[PREFS] 预览批次百分比 = {}", this.previewBatchPercent);
    }

    /**
     * <strong>仅供服务端调用</strong>: 写 buildBatchPercent 到服务端 PlayerPreferences (全服共享).
     * 客户端 GUI 改值时应发 {@link com.prefab.addon.network.UpdateBuildSpeedPayload}, 由服务端调这个方法.
     */
    public void setBuildBatchPercent(int percent) {
        this.buildBatchPercent = clampPercent(percent, 1);
        save();
        PrefabCustomAddon.LOGGER.info("[PREFS] 建造批次百分比 = {} (服务端, 全服生效)", this.buildBatchPercent);
    }

    /**
     * <strong>仅供客户端调用</strong>: 把服务端广播的 buildBatchPercent 写到本地 PlayerPreferences (仅供显示).
     * 不走 save() (避免覆盖服务端文件, 但仍然会调一次, 让 JSON 字段和当前值一致).
     */
    public void setBuildBatchPercentFromServer(int percent) {
        this.buildBatchPercent = clampPercent(percent, 1);
        PrefabCustomAddon.LOGGER.info("[PREFS] 建造批次百分比 = {} (从服务端同步, 仅供本地显示)", this.buildBatchPercent);
        // 不 save(): 客户端 JSON 只是缓存, 真正的权威在服务端
    }

    /**
     * 建造下落动画开关 (wrapper, 兼容老调用方).
     * 实际数据存的是 {@link #buildAnimationMode} 枚举, 这里只判断 != OFF.
     * 老代码可以直接用, 新代码推荐直接调 {@link #getBuildAnimationMode()}.
     */
    public boolean isBuildAnimationEnabled() {
        return this.buildAnimationMode != BuildAnimationMode.OFF;
    }

    /** @deprecated 1.6.0 起改用 {@link #setBuildAnimationMode(BuildAnimationMode)}. */
    @Deprecated
    public void setBuildAnimationEnabled(boolean value) {
        setBuildAnimationMode(value ? BuildAnimationMode.FALL : BuildAnimationMode.OFF);
    }

    /**
     * 读取当前建造动画模式. 默认 {@link BuildAnimationMode#OFF}.
     */
    public BuildAnimationMode getBuildAnimationMode() {
        return this.buildAnimationMode != null ? this.buildAnimationMode : BuildAnimationMode.OFF;
    }

    /**
     * 设置建造动画模式并持久化. 设为 {@code null} 等同于 OFF.
     */
    public void setBuildAnimationMode(BuildAnimationMode mode) {
        BuildAnimationMode v = mode != null ? mode : BuildAnimationMode.OFF;
        if (this.buildAnimationMode == v) return;
        this.buildAnimationMode = v;
        save();
        PrefabCustomAddon.LOGGER.info("[PREFS] 建造动画模式 = {}", this.buildAnimationMode);
    }

    /** 动画起始高度, 默认 8. */
    public int getBuildAnimHeight() {
        return this.buildAnimHeight < 1 ? 1 : (this.buildAnimHeight > 32 ? 32 : this.buildAnimHeight);
    }

    private static int clampPercent(int v, int def) {
        if (v < 1) return 1;
        if (v > 100) return 100;
        return v;
    }

    // ============================================================
    // 收藏 (Favorites) 管理
    // ============================================================

    /** 标准化收藏键. 非法分隔符会被替换为 '_' 避免和 "packageName/constructionId" 冲突. */
    private static String normalizeKey(String packageName, String constructionId) {
        String pkg = packageName == null || packageName.isEmpty() ? "__standalone__" : packageName;
        String cid = constructionId == null ? "" : constructionId;
        pkg = pkg.replace('/', '_').replace('\\', '_');
        cid = cid.replace('/', '_').replace('\\', '_');
        return pkg + "/" + cid;
    }

    /** 反向: 从键里拆出 (packageName, constructionId). */
    public static String[] parseKey(String key) {
        if (key == null) return new String[]{"", ""};
        int idx = key.indexOf('/');
        if (idx < 0) return new String[]{"", key};
        return new String[]{key.substring(0, idx), key.substring(idx + 1)};
    }

    public boolean isFavorite(String packageName, String constructionId) {
        if (favoriteKeys == null || favoriteKeys.isEmpty()) return false;
        String k = normalizeKey(packageName, constructionId);
        return favoriteKeys.contains(k);
    }

    /**
     * 切换收藏状态. 返回切换后的新状态 (true=已收藏).
     * 修改后会自动 save() 持久化.
     */
    public boolean toggleFavorite(String packageName, String constructionId) {
        if (favoriteKeys == null) favoriteKeys = new ArrayList<>();
        String k = normalizeKey(packageName, constructionId);
        boolean now;
        if (favoriteKeys.contains(k)) {
            favoriteKeys.remove(k);
            now = false;
        } else {
            favoriteKeys.add(k);
            now = true;
        }
        save();
        PrefabCustomAddon.LOGGER.info("[FAV] 切换收藏 {} -> {} ({})", k, now, now ? "已添加" : "已移除");
        return now;
    }

    /** 显式添加 (供批量导入用). */
    public void addFavorite(String packageName, String constructionId) {
        if (favoriteKeys == null) favoriteKeys = new ArrayList<>();
        String k = normalizeKey(packageName, constructionId);
        if (!favoriteKeys.contains(k)) {
            favoriteKeys.add(k);
            save();
        }
    }

    /** 显式移除. */
    public void removeFavorite(String packageName, String constructionId) {
        if (favoriteKeys == null) return;
        String k = normalizeKey(packageName, constructionId);
        if (favoriteKeys.remove(k)) {
            save();
        }
    }

    /** 返回当前所有收藏键 (拷贝). */
    public List<String> getFavoriteKeys() {
        if (favoriteKeys == null) return new ArrayList<>();
        return new ArrayList<>(favoriteKeys);
    }
}
