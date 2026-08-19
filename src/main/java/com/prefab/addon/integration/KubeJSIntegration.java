package com.prefab.addon.integration;

import com.prefab.addon.PrefabCustomAddon;
import net.neoforged.fml.ModList;

import java.util.Arrays;
import java.util.List;

/**
 * KubeJS 联动检测.
 *
 * <p>"制作蓝图" tab 完全依赖 KubeJS (写 startup_scripts / 配方 JSON / 贴图).
 * 玩家没装 KubeJS 时这个 tab 整个不显示, 避免点了没反应.</p>
 *
 * <p>不强制依赖 KubeJS: {@code build.gradle} 用了 {@code compileOnly},
 * 编译时拿到 class 引用, 运行时通过 {@link ModList} 查加载列表, 没装就
 * {@link #isLoaded()} 返回 false, 调用方自行隐藏 UI.</p>
 *
 * <h2>检测策略 (按顺序, 任一命中即视为已装)</h2>
 * <ol>
 *   <li>{@link #forceEnabled} = true (玩家手动开, 调试用)</li>
 *   <li>尝试多个 mod id 候选 (应对 fork / 改包的情况):
 *       <ul>
 *         <li>{@code kubejs} — KubeJS 主 mod (官方 NeoForge / Forge / Fabric 1.21.1 通用)</li>
 *         <li>{@code kubejs_} — 早期版本</li>
 *       </ul>
 *   </li>
 *   <li>类路径兜底: {@code Class.forName("dev.latvian.mods.kubejs.KubeJS")}
 *       (KubeJS 主类, 任意版本都有, mod id 改名也能识别)</li>
 * </ol>
 *
 * <p>所有路径都失败时返回 false, 同时记一行 warn, 方便排查 "我装了 KubeJS 但 tab 没显示".</p>
 */
public final class KubeJSIntegration {

    /** KubeJS 的标准 mod id (官方版本都用这个). */
    public static final String KUBEJS_MOD_ID = "kubejs";

    /**
     * 手动强制启用 "制作蓝图" tab. 默认 false (自动检测).
     * <p>设置方法: 在游戏内按 O 打开设置, 找到 "启用 KubeJS 联动 (强制)" 勾上即可.
     * 也可调用 {@link #setForceEnabled(boolean)} 程序化设置.</p>
     */
    private static volatile boolean forceEnabled = false;

    /** 缓存: 自动检测结果, 手动设置 ({@link #forceEnabled}) 不受缓存影响. */
    private static volatile Boolean cachedDetected = null;

    private KubeJSIntegration() {}

    /**
     * 多个候选 mod id 列表. KubeJS 官方用 "kubejs", 但 fork (KubeJS+ / KubeJS-Create) 偶尔改名.
     * 按顺序匹配, 命中即返回.
     */
    private static final List<String> MOD_ID_CANDIDATES = Arrays.asList(
        "kubejs",
        "kubejs_",
        "kubejs_official"
    );

    /**
     * 当前运行实例里 KubeJS 是否已加载.
     * <p>注意: 调用时机要在 mod 加载完成之后 (FML 加载阶段后),
     * 否则 {@link ModList} 还没建好. 在 {@code Screen#init} / tick 里调 OK.</p>
     *
     * <p>线程安全: 用 {@code volatile} 包装缓存, 多线程 (Minecraft 主线程 + 其他)
     * 调不会看到半初始化的状态.</p>
     */
    public static boolean isLoaded() {
        if (forceEnabled) return true;
        Boolean cached = cachedDetected;
        if (cached != null) return cached;

        // 1) ModList 多候选匹配
        try {
            for (String modId : MOD_ID_CANDIDATES) {
                if (ModList.get().isLoaded(modId)) {
                    cachedDetected = Boolean.TRUE;
                    PrefabCustomAddon.LOGGER.info("[KubeJS] ModList 检测到 KubeJS 已加载 (mod id={}), 启用 '制作蓝图' tab", modId);
                    return true;
                }
            }
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[KubeJS] ModList 检测失败: {}", t.getMessage());
        }

        // 2) 类路径兜底
        try {
            Class.forName("dev.latvian.mods.kubejs.KubeJS");
            cachedDetected = Boolean.TRUE;
            PrefabCustomAddon.LOGGER.info("[KubeJS] 类路径兜底检测到 KubeJS 已加载, 启用 '制作蓝图' tab");
            return true;
        } catch (ClassNotFoundException cnfe) {
            // 真的没装
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[KubeJS] 类路径检测异常: {}", t.getMessage());
        }

        // 3) 都没找到
        cachedDetected = Boolean.FALSE;
        PrefabCustomAddon.LOGGER.warn(
            "[KubeJS] 未检测到 KubeJS, '制作蓝图' tab 已隐藏. "
            + "如果你装了 KubeJS 但 tab 不显示, 请在游戏内按 O → 勾上 '启用 KubeJS 联动 (强制)'.");
        return false;
    }

    /** 玩家手动 /kubejs reload 时, 缓存可能失效, 提供一个刷新钩子. */
    public static void invalidateCache() {
        cachedDetected = null;
    }

    /**
     * 手动设置强制启用标志. 设为 true 后 {@link #isLoaded()} 始终返回 true,
     * 不走自动检测. 调试用, 适合 "我确定 KubeJS 装好了但自动检测死活过不了" 的情况.
     */
    public static void setForceEnabled(boolean v) {
        forceEnabled = v;
        PrefabCustomAddon.LOGGER.info("[KubeJS] 强制启用标志设为 {}", v);
    }

    /** 当前强制启用标志. */
    public static boolean isForceEnabled() {
        return forceEnabled;
    }

    /**
     * 重新跑自动检测 + 刷新缓存. 用于 GUI 打开时保险刷一下:
     * 万一上一次是 mod 加载早期调的, 缓存了 false, 现在能纠正过来.
     */
    public static void recheck() {
        invalidateCache();
        boolean result = isLoaded();
        PrefabCustomAddon.LOGGER.info("[KubeJS] recheck → {}", result ? "loaded" : "not loaded");
    }
}
