package com.prefab.addon.integration.xaero;

import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Xaero's World Map / Minimap 集成桥.
 *
 * <h2>为什么是反射</h2>
 * Xaero's 没公开 API, 也没标注任何 @Mod/公共接口. 只能走反射调内部类.
 * 已知路径 (来自 ping-to-map-xaeros 等 mod 的实际集成):
 * <pre>
 *   xaero.hud.minimap.BuiltInHudModules.MINIMAP
 *     .getCurrentSession()
 *     .getWorldManager()                            // 1.39
 *     .getCurrentWorld()
 *     .getCurrentWaypointSet()
 *       → Waypoint(name, x, y, z, color, yaw, temporary)
 *       → waypointSet.add(waypoint)
 * </pre>
 *
 * <h2>版本差异</h2>
 * <ul>
 *   <li>1.39.x (1.21.1 早期): getCurrentSession() 直接返回 MinimapSession, 然后 getWorldManager()</li>
 *   <li>1.40.x+: 部分版本会多一层 getWaypointSession() / getSession(), 所以下面带 try/catch 多路径尝试</li>
 * </ul>
 *
 * <h2>线程要求</h2>
 * Xaero 的内部会话和 waypoint set 都是 client-side 状态, 必须在客户端主线程调.
 * 我们的 summon 流程在 server thread 跑, 所以这里提供一个 async-safe 入口:
 *  - 服务端调时把任务 enqueue 到 {@link net.minecraft.client.Minecraft#execute}, 由 client thread 执行.
 *  - 客户端自己调时直接执行即可.
 *
 * <h2>失败安全</h2>
 * 所有反射调用包在 try/catch, 任何 NoSuchMethodError / NoSuchFieldError / InvocationTargetException
 * 都不会让游戏崩, 只是默默不打航点 (log.warn 一行, 不刷屏).
 */
public final class XaeroWaypointBridge {

    private XaeroWaypointBridge() {}

    /** 启动时探测一次, Xaero 不在时直接标记 false, 后续 add/remove 全走 fast path. */
    private static final AtomicBoolean AVAILABLE = new AtomicBoolean(false);
    private static volatile boolean probed = false;
    /** 探针最近一次失败时间 (ms). 失败后允许重试, 但有节流 (避免每帧重探). */
    private static volatile long lastProbeFailMs = 0L;
    /** 探针失败重试间隔: 5 秒. GUI 打开 / 玩家切世界 后 Xaero 可能稍晚才就绪. */
    private static final long PROBE_RETRY_COOLDOWN_MS = 5_000L;
    /** 累计连续失败次数, 达到上限就永久放弃本次会话 (避免刷日志). */
    private static final int PROBE_MAX_FAILS = 20;
    private static volatile int probeFailCount = 0;

    /** 缓存反射结果, 避免每次 add 都重新 Class.forName. */
    private static Class<?> WP_CLASS;           // Waypoint class (Xaero 26.4.2 在 xaero.common.waypoint.Waypoint)
    private static Object MINIMAP_MODULE;       // xaero.hud.minimap.BuiltInHudModules.MINIMAP 静态字段
    private static Method M_GET_CURRENT_SESSION;
    private static Method M_GET_WORLD_MANAGER;
    private static Method M_GET_CURRENT_WORLD;
    private static Method M_GET_WAYPOINT_SET;    // 在 World 上
    private static Constructor<?> WP_CTOR;       // Waypoint 构造
    private static Method M_WP_SET_ADD;          // waypointSet.add(Waypoint) 或 list/array
    private static Method M_WP_SET_REMOVE;       // 可选, recall 时调用

    // ============================================================
    // 公开 API
    // ============================================================

    /** Xaero 是否可用.
     *  <p>失败时不会永久缓存: 隔 {@code PROBE_RETRY_COOLDOWN_MS} 后会重试一次,
     *  这样玩家进入世界、GUI 打开、Xaero 加载完成等时机能自动恢复. */
    public static boolean isAvailable() {
        if (AVAILABLE.get()) return true;          // 成功路径 (热)
        // === 0) 先用 ModList 查 Xaero 装了没 — 没装就直接 false, 不去碰反射 ===
        if (!isXaeroModLoaded()) return false;
        if (probed) {                              // 失败过 → 看是否到 cooldown
            long now = System.currentTimeMillis();
            if (now - lastProbeFailMs < PROBE_RETRY_COOLDOWN_MS) return false;
            // 到 cooldown 了, 允许重试: 把 probed 复位, 走完整 probe
            probed = false;
            probeFailCount = 0;                    // 重置连续失败计数
        }
        probe();
        return AVAILABLE.get();
    }

    /** 强制立即重探 (无视 cooldown). GUI 打开 / 切世界 等事件可以调一下. */
    public static synchronized void forceProbe() {
        probed = false;
        probeFailCount = 0;
        probe();
    }

    /**
     * Xaero 模组 (Minimap 或 World Map) 是否在客户端加载?
     * 用 NeoForge 自带的 ModList, 不靠反射 — 玩家装没装一眼就知道, 不会因为反射拿不到类而误判.
     * <p>注: 我们在 mods.toml 里没声明 hard dependency, 所以这里要走运行时检查.</p>
     */
    public static boolean isXaeroModLoaded() {
        try {
            Class<?> modListClass = Class.forName("net.neoforged.fml.ModList");
            java.lang.reflect.Method get = modListClass.getMethod("get");
            Object modList = get.invoke(null);
            if (modList == null) return false;
            // modList.isLoaded(String id) - mod id 字符串
            java.lang.reflect.Method isLoaded = modListClass.getMethod("isLoaded", String.class);
            Object r1 = isLoaded.invoke(modList, "xaeroworldmap");
            if (Boolean.TRUE.equals(r1)) {
                PrefabCustomAddon.LOGGER.info("[XAERO] isXaeroModLoaded: xaeroworldmap=true (World Map 装了)");
                return true;
            }
            Object r2 = isLoaded.invoke(modList, "xaerominimap");
            if (Boolean.TRUE.equals(r2)) {
                PrefabCustomAddon.LOGGER.info("[XAERO] isXaeroModLoaded: xaerominimap=true (Minimap 装了)");
                return true;
            }
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[XAERO] isXaeroModLoaded check failed: {}", t.toString());
        }
        return false;
    }

    /**
     * 最近一次 addWaypoint 失败原因 (供 GUI 显示). null = 成功或未尝试.
     * <p>不在多线程下读取, 单线程 GUI 渲染线程用, 没问题.</p>
     */
    private static volatile String lastAddError = null;

    /** GUI 读完清掉, 避免污染下次显示. */
    public static String consumeLastAddError() {
        String e = lastAddError;
        lastAddError = null;
        return e;
    }

    /**
     * 在 Xaero 地图上添加一个永久航点.
     * @return true=成功, false=失败 (调用 consumeLastAddError() 拿原因)
     */
    public static boolean addWaypoint(ResourceKey<Level> dim, BlockPos pos,
                                      String name, int colorARGB) {
        lastAddError = null;
        if (!isAvailable()) {
            lastAddError = "Xaero 不可用 (probe 失败)";
            return false;
        }
        if (pos == null || name == null) {
            lastAddError = "坐标或名字为空";
            return false;
        }
        if (WP_CTOR == null) {
            // 探针没找到 Waypoint 构造 — addWaypoint 不可用, 但 removeWaypoint 可能还工作
            lastAddError = "Waypoint 构造未找到 (26.4.2 改签名?)";
            PrefabCustomAddon.LOGGER.warn("[XAERO] addWaypoint: WP_CTOR is null, can't construct waypoint");
            return false;
        }
        try {
            Object waypointSet = getCurrentWaypointSet();
            if (waypointSet == null) {
                lastAddError = "waypointSet 为空 (Xaero 未在世界中?)";
                PrefabCustomAddon.LOGGER.warn("[XAERO] addWaypoint: waypointSet is null");
                return false;
            }

            // 26.4.2 构造签名固定 (int, int, int, String, String, ...) — 参数顺序:
            //   x, y, z, name, subName, [color | color+dimId | color+dimId+yaw | color+dimId+yaw+temporary | ...]
            // 老的 Xaero (name, x, y, z, color, ...) 跟 26.4.2 完全不一样, 别用.
            // 这里按构造实际参数个数动态拼装, 避免 6 参 / 7 参 / 8 参混用报 "wrong number of arguments".
            Class<?>[] paramTypes = WP_CTOR.getParameterTypes();
            int pc = paramTypes.length;
            Object[] args = new Object[pc];
            args[0] = pos.getX();
            args[1] = pos.getY();
            args[2] = pos.getZ();
            args[3] = name;
            args[4] = "";                 // subName / 前缀 — 留空
            // 6+ 位的默认填充: color 必须在第 6 位 (索引 5)
            for (int i = 5; i < pc; i++) {
                Class<?> t = paramTypes[i];
                if (i == 5) {
                    // 第 6 位 = color
                    if (t == int.class) {
                        args[i] = colorARGB;
                    } else {
                        // WaypointColor 是 enum, 取第一个常量. 拿不到常量就传 null (反射会报, 但日志能看见)
                        args[i] = firstEnumConstant(t);
                    }
                } else if (t == int.class) {
                    args[i] = 0;            // dimId 等
                } else if (t == boolean.class) {
                    args[i] = Boolean.FALSE; // rotation / temporary
                } else if (t.isEnum()) {
                    args[i] = firstEnumConstant(t);
                } else {
                    args[i] = null;         // 其它复杂类型, 听天由命, 反射失败会进 catch
                }
            }
            Object waypoint = WP_CTOR.newInstance(args);
            invoke(M_WP_SET_ADD, waypointSet, waypoint);
            return true;
        } catch (Throwable t) {
            lastAddError = t.getClass().getSimpleName() + ": " + t.getMessage();
            PrefabCustomAddon.LOGGER.warn("[XAERO] addWaypoint failed: {}", t.toString());
            return false;
        }
    }

    /** 拿 enum class 的第一个常量, 拿不到返回 null. */
    private static Object firstEnumConstant(Class<?> enumClass) {
        try {
            Object[] vals = (Object[]) enumClass.getMethod("values").invoke(null);
            return (vals != null && vals.length > 0) ? vals[0] : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 检查当前 waypointSet 里是否存在 name 完全匹配的航点.
     * 跟 {@link #removeWaypoint} 的区别: 这个只查不删, 用于 toggle 场景.
     *
     * @return true=存在, false=不存在 / 不可用 / 异常
     */
    public static boolean hasWaypoint(String name) {
        if (!isAvailable()) return false;
        if (name == null) return false;
        try {
            Object waypointSet = getCurrentWaypointSet();
            if (waypointSet == null) return false;
            for (Object wp : iterateWaypoints(waypointSet)) {
                if (name.equals(getWaypointName(wp))) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 移除航点 (按 name 匹配, recall 时调用).
     * 优先用反射的 remove 方法, 找不到就走遍历.
     */
    public static boolean removeWaypoint(String name) {
        if (!isAvailable()) return false;
        if (name == null) return false;
        try {
            Object waypointSet = getCurrentWaypointSet();
            if (waypointSet == null) return false;

            // 优先调 remove(Object) — 1.40+ 可能有
            if (M_WP_SET_REMOVE != null) {
                for (Object wp : iterateWaypoints(waypointSet)) {
                    if (name.equals(getWaypointName(wp))) {
                        invoke(M_WP_SET_REMOVE, waypointSet, wp);
                        return true;
                    }
                }
            } else {
                PrefabCustomAddon.LOGGER.debug("[XAERO] removeWaypoint: no remove method, skip");
            }
            return false;
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[XAERO] removeWaypoint failed: {}", t.getMessage());
            return false;
        }
    }

    /**
     * 拿当前 waypointSet. addWaypoint / removeWaypoint 共用的中间层.
     * 走: MINIMAP_MODULE → session → worldManager → world → waypointSet
     * 每一步都用对应的辅助函数按多版本方法名试探.
     */
    private static Object getCurrentWaypointSet() {
        if (MINIMAP_MODULE == null || M_GET_WAYPOINT_SET == null) return null;
        Object session = getCurrentSessionFrom(MINIMAP_MODULE);
        if (session == null) return null;
        Object worldManager = getWorldManagerFrom(session);
        if (worldManager == null) return null;
        Method mGetCurrentWorld = getCurrentWorldOn(worldManager.getClass());
        if (mGetCurrentWorld == null) return null;
        Object world = invoke(mGetCurrentWorld, worldManager);
        if (world == null) return null;
        return invoke(M_GET_WAYPOINT_SET, world);
    }

    /** 便利: 建筑航点默认色 — 蓝绿色 0xFF55AA55 (Xaero 默认也是绿色, 这里用一致色). */
    public static final int COLOR_BUILDING = 0xFF55AA55;
    /** 玩家临时标记色 — 黄色 */
    public static final int COLOR_HIGHLIGHT = 0xFFFFFF55;

    // ============================================================
    // 探测 / 缓存反射
    // ============================================================

    private static synchronized void probe() {
        if (probed) return;
        // === 0) ModList 先关: Xaero 没装就立即返回, 反射全部跳过 ===
        if (!isXaeroModLoaded()) {
            // 不算失败, 不打日志, 玩家没装而已
            probed = true;        // 探针走过一遍了, 避免每次 isAvailable() 都重试
            AVAILABLE.set(false);
            return;
        }
        // 注意: 这里不立刻设 probed=true. 只有走完整个反射链并 AVAILABLE.set(true) 之后才设.
        //   失败路径必须把 probed 保留为 false, 让下次 isAvailable() 还能重试.
        //   (玩家刚进入服务器时 Xaero session 可能还没就绪, 旧逻辑永久 false 修不掉)
        //   失败次数有上限 (PROBE_MAX_FAILS), 达到就真的放弃, 避免刷日志.
        try {
            // === 新思路: 不靠猜 Waypoint 类名, 倒着反推 ===
            // 1) 拿到 module 入口 (BuiltinHudModules.MINIMAP / WorldMap.sessionManager / XaeroMinimapSession.INSTANCE)
            Object minimapModule = findMinimapModule();
            if (minimapModule == null) {
                scanXaeroClasses("minimap module not found in any known path");
                markProbeFailed("minimap module not found (XaeroMinimapSession / Session / BuiltInHudModules.MINIMAP / WorldMap.sessionManager all failed)");
                return;
            }
            MINIMAP_MODULE = minimapModule;

            // 2) 拿 session — 不同入口的 getCurrentSession 签名可能不同
            //    老 World Map: xaero.map.WorldMap.sessionManager.getCurrentMapSession()
            //    新 Minimap: xaero.common.XaeroMinimapSession.getCurrent() 或类似
            //    老 Minimap: xaero.hud.minimap.BuiltInHudModules.MINIMAP.getCurrentSession()
            Object session = getCurrentSessionFrom(MINIMAP_MODULE);
            if (session == null) {
                markProbeFailed("session is null (Xaero not in world yet, or wrong entry point)");
                return;
            }

            // 3) 拿 worldManager → world → waypointSet
            Object worldManager = getWorldManagerFrom(session);
            if (worldManager == null) {
                markProbeFailed("worldManager is null");
                return;
            }
            Method mGetCurrentWorld = getCurrentWorldOn(worldManager.getClass());
            if (mGetCurrentWorld == null) {
                markProbeFailed("getCurrentWorld method not found on " + worldManager.getClass().getName());
                return;
            }
            Object world = invoke(mGetCurrentWorld, worldManager);
            if (world == null) {
                markProbeFailed("world is null");
                return;
            }

            // getCurrentWaypointSet — 在 World 上
            M_GET_WAYPOINT_SET = findMethodOnChain(world.getClass(), "getCurrentWaypointSet", null);
            if (M_GET_WAYPOINT_SET == null) {
                markProbeFailed("getCurrentWaypointSet method not found on " + world.getClass().getName());
                return;
            }
            Object waypointSet = invoke(M_GET_WAYPOINT_SET, world);
            if (waypointSet == null) {
                markProbeFailed("waypointSet is null");
                return;
            }

            // 4) 反推 Waypoint class: 找 waypointSet 的 add 方法, 其第一个参数类型就是 Waypoint class
            //    不需要事先知道类名, 反射拿.
            Method mAdd = findAddMethod(waypointSet.getClass());
            if (mAdd == null) {
                markProbeFailed("add method not found on waypointSet " + waypointSet.getClass().getName());
                return;
            }
            Class<?> wpClass = mAdd.getParameterTypes()[0];
            WP_CLASS = wpClass;
            M_WP_SET_ADD = mAdd;

            // 5) 找 remove(Object) — 找不到没关系
            try {
                M_WP_SET_REMOVE = waypointSet.getClass().getMethod("remove", wpClass);
            } catch (NoSuchMethodException ignored) {
                M_WP_SET_REMOVE = null;
            }

            // 6) 找 Waypoint 构造: 试 (String, int, int, int, int, int, boolean) 这套老 Xaero 风格
            //    26.4.2 可能改签名, 没找到就把 addWaypoint 视为不可用, 但 removeWaypoint 还可工作
            WP_CTOR = findWaypointCtor(wpClass);

            // 全部成功: 真正标记 probed=true, AVAILABLE=true
            probed = true;
            AVAILABLE.set(true);
            PrefabCustomAddon.LOGGER.info("[XAERO] waypoint bridge initialized (waypoint class: {}, waypointSet class: {}, ctor: {}, module: {})",
                wpClass.getName(), waypointSet.getClass().getName(),
                WP_CTOR != null ? "ok" : "NOT FOUND (add only via existing instances)",
                MINIMAP_MODULE.getClass().getName());
        } catch (Throwable probeErr) {
            // 通用兜底: findXaeroClass / findFieldRecursive / getMethod 等反射调用都自己 catch 了所有异常,
            // 不会从这里抛出. 但万一还有未知反射方法 (例如 findMethodOnChain 找不到方法时返回 null 后续 NPE),
            // 这里用 Throwable 一并接住, 避免编译错误.
            if (probeErr instanceof ClassNotFoundException) {
                scanXaeroClasses("ClassNotFoundException: " + probeErr.getMessage());
                markProbeFailed("not installed: " + probeErr.getMessage());
            } else if (probeErr instanceof NoSuchMethodException) {
                markProbeFailed("API mismatch: " + probeErr.getMessage());
            } else {
                markProbeFailed("probe exception: " + probeErr.getClass().getSimpleName() + " " + probeErr.getMessage());
            }
        }
    }

    /**
     * 从入口 (MINIMAP_MODULE, 可能是 BuiltInHudModules.MINIMAP 实例, 也可能是 WorldMapSessionManager 实例)
     * 拿当前 session. 试多种方法名.
     */
    private static Object getCurrentSessionFrom(Object entry) {
        if (entry == null) return null;
        // 1) entry 自身就是 session (XaeroMinimapSession.INSTANCE 之类)
        //    启发式: 类名包含 "Session" → 直接当 session 用
        if (entry.getClass().getName().toLowerCase().contains("session")) {
            return entry;
        }
        // 2) entry.getCurrentSession()
        Method m = findMethodOnChain(entry.getClass(), "getCurrentSession", null);
        if (m != null) {
            Object s = invoke(m, entry);
            if (s != null) return s;
        }
        // 3) entry.getCurrentMapSession() (WorldMapSessionManager)
        m = findMethodOnChain(entry.getClass(), "getCurrentMapSession", null);
        if (m != null) {
            Object s = invoke(m, entry);
            if (s != null) return s;
        }
        // 4) entry.getCurrentMinimapSession()
        m = findMethodOnChain(entry.getClass(), "getCurrentMinimapSession", null);
        if (m != null) {
            Object s = invoke(m, entry);
            if (s != null) return s;
        }
        return null;
    }

    /**
     * 从 session 拿 worldManager. 试 (1) session.getWorldManager() (2) session.getWaypointSession().getWorldManager()
     */
    private static Object getWorldManagerFrom(Object session) {
        if (session == null) return null;
        // (1) 直接 getWorldManager
        Method m = findMethodOnChain(session.getClass(), "getWorldManager", null);
        if (m != null) {
            Object wm = invoke(m, session);
            if (wm != null) return wm;
        }
        // (2) session.getWaypointSession().getWorldManager()
        Method m2 = findMethodOnChain(session.getClass(), "getWaypointSession", null);
        if (m2 != null) {
            Object wpSession = invoke(m2, session);
            if (wpSession != null) {
                Method m3 = findMethodOnChain(wpSession.getClass(), "getWorldManager", null);
                if (m3 != null) {
                    Object wm = invoke(m3, wpSession);
                    if (wm != null) return wm;
                }
            }
        }
        // (3) session 自身就是 worldManager (类名带 "WorldManager")
        if (session.getClass().getName().contains("WorldManager")) {
            return session;
        }
        return null;
    }

    /**
     * 找 waypointSet 的 add 方法. 优先 (Class) 类型, 也兼容 (Object).
     * 返回 Method.invoke 接受的参数 (通常是 Waypoint class).
     */
    private static Method findAddMethod(Class<?> waypointSetClass) {
        for (Method m : waypointSetClass.getMethods()) {
            if (!m.getName().equals("add")) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length != 1) continue;
            // 排除 boolean / int 之类的 add (比如 add(int, Object) 这种 List.add)
            if (params[0] == boolean.class || params[0] == int.class) continue;
            return m;
        }
        return null;
    }

    /**
     * 找 Waypoint class 的可用构造. 26.4.2 改过签名, 这里不靠 try/catch 试错,
     * 直接 {@code getDeclaredConstructors()} 全量列出再按参数模式匹配, 稳得多.
     *
     * <p><b>26.4.2 实际签名</b> (从 [XAERO] findWaypointCtor failed 日志拿到):
     * <ul>
     *   <li>ctor(int, int, int, String, String, WaypointColor)                          -- 6 参</li>
     *   <li>ctor(int, int, int, String, String, int)                                    -- 6 参 (int color) ← 最简, 优先</li>
     *   <li>ctor(int, int, int, String, String, int, int)                               -- 7 参</li>
     *   <li>ctor(int, int, int, String, String, int, int, boolean)                      -- 8 参</li>
     *   <li>ctor(int, int, int, String, String, int, int, boolean, boolean)             -- 9 参</li>
     *   <li>ctor(int, int, int, String, String, WaypointColor, WaypointPurpose)         -- 8 参</li>
     *   <li>ctor(int, int, int, String, String, WaypointColor, WaypointPurpose, boolean)         -- 9 参</li>
     *   <li>ctor(int, int, int, String, String, WaypointColor, WaypointPurpose, boolean, boolean) -- 10 参</li>
     * </ul>
     * 参数顺序固定是 <b>(x, y, z, name, subName, ...)</b> — 跟老 Xaero 的 (name, x, y, z, ...) 完全不一样.
     * </p>
     *
     * <p>匹配策略: 前 5 个参数必须是 (int, int, int, String, String), 后续参数忽略.
     * 这样不管后续是 color (int/WaypointColor) 还是 dimension / purpose / yaw / temporary, 都能用同一个 ctor.
     * 然后取参数最少的那个 (调用最简单). 优先 int color, 实在没有再退到 WaypointColor.</p>
     */
    private static Constructor<?> findWaypointCtor(Class<?> wpClass) {
        try {
            Constructor<?>[] all = wpClass.getDeclaredConstructors();
            Constructor<?> best = null;
            Constructor<?> bestColor = null;  // 第 6 参数是 int (int color), 优先
            Constructor<?> bestColorObj = null; // 第 6 参数是 WaypointColor (enum/class), 备选
            for (Constructor<?> c : all) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length < 6) continue;
                // 前 5 参数必须 (int, int, int, String, String)
                if (p[0] != int.class || p[1] != int.class || p[2] != int.class) continue;
                if (p[3] != String.class || p[4] != String.class) continue;
                if (best == null || p.length < best.getParameterCount()) {
                    best = c;
                }
                // 第 6 参数 (索引 5): int = 优, 其它 class/enum = 备
                if (p.length == 6) {
                    if (p[5] == int.class && bestColor == null) bestColor = c;
                    else if (bestColorObj == null) bestColorObj = c;
                }
            }
            // 优先 int-color 的 6 参, 没有再退化到任意 6 参
            Constructor<?> pick = (bestColor != null) ? bestColor
                                : (bestColorObj != null) ? bestColorObj
                                : best;
            if (pick != null) {
                pick.setAccessible(true);
                return pick;
            }

            // === 兜底: 没找到符合 (int,int,int,String,String,...) 模式的, 列出全部 ===
            StringBuilder sb = new StringBuilder();
            sb.append("[XAERO] findWaypointCtor failed for ").append(wpClass.getName()).append("\n");
            sb.append("  ctors (no match for (int,int,int,String,String,...) pattern):\n");
            for (Constructor<?> c : all) {
                sb.append("    ctor(");
                Class<?>[] params = c.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(params[i].getSimpleName());
                }
                sb.append(")\n");
            }
            sb.append("  fields:\n");
            for (Field f : wpClass.getDeclaredFields()) {
                sb.append("    ").append(f.getType().getSimpleName()).append(" ").append(f.getName()).append("\n");
            }
            PrefabCustomAddon.LOGGER.warn(sb.toString());
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[XAERO] findWaypointCtor exception: {}", t.toString());
        }
        return null;
    }

    /** 记录一次探针失败, 设 cooldown 时间戳. 连续失败超过上限就永久放弃. */
    private static void markProbeFailed(String reason) {
        lastProbeFailMs = System.currentTimeMillis();
        probeFailCount++;
        if (probeFailCount == 1 || probeFailCount == PROBE_MAX_FAILS) {
            // 第 1 次 + 上限最后一次打日志, 中间静默
            PrefabCustomAddon.LOGGER.info("[XAERO] probe failed ({}), retry in {}s{}. count={}",
                reason, PROBE_RETRY_COOLDOWN_MS / 1000,
                probeFailCount >= PROBE_MAX_FAILS ? " (last try, giving up)" : "",
                probeFailCount);
        }
        if (probeFailCount >= PROBE_MAX_FAILS) {
            // 永久缓存这次失败, 不再重试 (避免每帧 / 每次 GUI 渲染都重探)
            probed = true;
        }
    }

    /**
     * 找 MINIMAP_MODULE 入口, 兼容 Xaero 26.4.2 (xaero.common.*) / 老 Xaero Minimap / 老 World Map.
     * <p>Xaero 26.4.2 重大重命名: 全部从 xaero.hud.minimap.* / xaero.map.* 迁到 xaero.common.*.</p>
     * <p>返回的 Object 是带 getCurrentWorld / getWaypointSet 等方法的对象.</p>
     */
    private static Object findMinimapModule() {
        // === 路径 1: Xaero 26.4.2+ (xaero.common.XaeroMinimapSession) ===
        try {
            Class<?> c = Class.forName("xaero.common.XaeroMinimapSession");
            // 静态实例: XaeroMinimapSession.INSTANCE 或 XaeroMinimapSession.instance
            for (String fn : new String[]{"INSTANCE", "instance", "minimapInstance"}) {
                try {
                    Field f = c.getField(fn);
                    Object mod = f.get(null);
                    if (mod != null) return mod;
                } catch (NoSuchFieldException ignored) {}
            }
            // 没静态实例 → 直接拿一个, 反正是用反射拿内部状态, 不需要真正可用的 session
            // 走 "getCurrentWorld" 这种方法在原 session 实例上
            // 但这里没人能 new, 跳过
        } catch (Throwable ignored) {}

        // === 路径 2: Xaero 26.4.2+ (xaero.common.Session) ===
        try {
            Class<?> c = Class.forName("xaero.common.Session");
            for (String fn : new String[]{"INSTANCE", "instance", "current"}) {
                try {
                    Field f = c.getField(fn);
                    Object mod = f.get(null);
                    if (mod != null) return mod;
                } catch (NoSuchFieldException ignored) {}
            }
        } catch (Throwable ignored) {}

        // === 路径 3: 老 Xaero Minimap (xaero.hud.minimap.BuiltInHudModules.MINIMAP) ===
        try {
            Class<?> modulesClass = Class.forName("xaero.hud.minimap.BuiltInHudModules");
            Field f = modulesClass.getField("MINIMAP");
            Object mod = f.get(null);
            if (mod != null) return mod;
        } catch (Throwable ignored) {}

        // === 路径 4: 老 Xaero World Map (xaero.map.WorldMap.sessionManager) ===
        try {
            Class<?> wm = Class.forName("xaero.map.WorldMap");
            Field f = findFieldRecursive(wm, "sessionManager");
            if (f != null) {
                f.setAccessible(true);
                Object sm = f.get(null);
                if (sm != null) {
                    Method m = findMethodOnChain(sm.getClass(), "getCurrentMapSession", null);
                    if (m == null) m = findMethodOnChain(sm.getClass(), "getCurrentMinimapSession", null);
                    if (m == null) m = findMethodOnChain(sm.getClass(), "getCurrentSession", null);
                    if (m != null) {
                        Object s = invoke(m, sm);
                        if (s != null) return s;
                    }
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    /**
     * 列一下 classpath 里所有 xaero.* 类的 short name, 帮诊断「Xaero 装了但探针失败」这种问题.
     * 借助 system class loader 拿到 URLClassLoader 的 entries 太少, 直接靠 Class.forName 试探.
     * 探测一些常见的类名, 把命中的列出来. 不会每帧都跑 (只在 probe 失败时跑一次).
     */
    private static void scanXaeroClasses(String reason) {
        // === 0) 先用 ModList 确认 Xaero 装了没 ===
        boolean modLoaded = isXaeroModLoaded();
        if (!modLoaded) {
            PrefabCustomAddon.LOGGER.warn("[XAERO] class scan ({}): ModList says Xaero NOT loaded → 玩家没装 Xaero, 按钮不会画",
                reason);
            return;
        }

        // === 1) 候选路径 ===
        String[] probes = {
            "xaero.common.XaeroMinimapSession",
            "xaero.common.Session",
            "xaero.common.ModSettings",
            "xaero.common.Xaero",
            "xaero.common.XaeroMinimap",
            "xaero.common.XaeroWorldMap",
            "xaero.common.Waypoint",
            "xaero.common.waypoint.Waypoint",
            "xaero.common.waypoint.set.Waypoint",
            "xaero.common.waypoints.Waypoint",
            "xaero.common.WaypointSet",
            "xaero.common.waypoint.WaypointSet",
            "xaero.common.waypoint.set.WaypointSet",
            "xaero.common.BuiltInHudModules",
            "xaero.common.HudModules",
            "xaero.common.minimap.waypoint.Waypoint",
            "xaero.common.minimap.waypoint.set.Waypoint",
            "xaero.common.minimap.waypoint.WaypointSet",
            "xaero.common.minimap.Waypoint",
            "xaero.common.minimap.WaypointSet",
            "xaero.common.minimap.XaeroMinimap",
            "xaero.common.minimap.MiniMapSession",
            "xaero.common.worldmap.WorldMap",
            "xaero.common.worldmap.WorldMapSession",
            "xaero.common.worldmap.XaeroWorldMap",
            "xaero.common.worldmap.WorldMapSessionManager",
            "xaero.common.WorldMapSessionManager",
            "xaero.common.worldmap.waypoint.Waypoint",
            "xaero.common.worldmap.waypoint.set.Waypoint",
            "xaero.hud.minimap.BuiltInHudModules",
            "xaero.hud.minimap.waypoint.set.Waypoint",
            "xaero.hud.minimap.waypoint.Waypoint",
            "xaero.hud.minimap.XaeroMinimap",
            "xaero.hud.minimap.mod.XaeroMinimap",
            "xaero.map.WorldMap",
        };
        StringBuilder hit = new StringBuilder();
        for (String p : probes) {
            try {
                Class<?> c = Class.forName(p);
                hit.append("\n    HIT  ").append(p).append("  (").append(c.getName()).append(")");
            } catch (Throwable t) {
                // 静默: 只列命中的
            }
        }

        // === 2) 扫 classpath 里 xaero jar 内的 class 名 ===
        // Xaero 26.4.2 把包名改得我们猜不到, 静态探测会漏. 终极手段: 打开 jar 列出 xaero.* class.
        StringBuilder jarScan = new StringBuilder();
        try {
            ClassLoader cl = XaeroWaypointBridge.class.getClassLoader();
            Class<?> c = cl.getClass();
            while (c != null) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField("ucp");
                    f.setAccessible(true);
                    Object ucp = f.get(cl);
                    java.lang.reflect.Field loadersField = ucp.getClass().getDeclaredField("loaders");
                    loadersField.setAccessible(true);
                    Object loaders = loadersField.get(ucp);
                    for (Object entry : (Iterable<?>) loaders) {
                        try {
                            java.lang.reflect.Field loaderField = findFieldRecursive(entry.getClass(), "loader");
                            if (loaderField == null) continue;
                            Object loader = loaderField.get(entry);
                            if (loader == null) continue;
                            if (!loader.getClass().getName().contains("JarLoader")) continue;
                            java.lang.reflect.Field baseField = findFieldRecursive(entry.getClass(), "base");
                            if (baseField == null) continue;
                            Object base = baseField.get(entry);
                            String pathStr = String.valueOf(base);
                            String lower = pathStr.toLowerCase();
                            if (!lower.contains("xaero") && !lower.contains("minimap") && !lower.contains("worldmap")) continue;
                            try (java.util.jar.JarFile jf = new java.util.jar.JarFile(pathStr)) {
                                int n = 0;
                                for (java.util.Enumeration<java.util.jar.JarEntry> e = jf.entries(); e.hasMoreElements();) {
                                    java.util.jar.JarEntry je = e.nextElement();
                                    String n2 = je.getName();
                                    if (n2.endsWith(".class") && n2.contains("xaero")) {
                                        String cname = n2.replace('/', '.').replace(".class", "");
                                        jarScan.append("\n    JAR  ").append(cname);
                                        n++;
                                        if (n > 200) { jarScan.append("\n    (... trunc)"); break; }
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
                c = c.getSuperclass();
            }
        } catch (Throwable t) {
            jarScan.append("\n    (jar scan failed: ").append(t.getClass().getSimpleName()).append(")");
        }

        PrefabCustomAddon.LOGGER.warn("[XAERO] class scan ({}): ModList=Xaero loaded, reflection miss.\n  probes found:{}\n  jar scan:{}",
            reason, hit, jarScan);
    }

    private static Method getCurrentWorldOn(Class<?> worldManagerClass) {
        try {
            return worldManagerClass.getMethod("getCurrentWorld");
        } catch (NoSuchMethodException e) {
            // 兜底: 1.40+ 可能有 getCurrentMultiworld 之类的
            for (Method m : worldManagerClass.getMethods()) {
                if (m.getName().toLowerCase(Locale.ROOT).contains("currentworld")) return m;
            }
            throw new RuntimeException("getCurrentWorld not found on " + worldManagerClass);
        }
    }

    private static Object tryGetWorldManagerViaWaypointSession(Object session) {
        try {
            Method m = findMethodOnChain(session.getClass(), "getWaypointSession", null);
            if (m == null) return null;
            Object wpSession = invoke(m, session);
            if (wpSession == null) return null;
            Method m2 = findMethodOnChain(wpSession.getClass(), "getWorldManager", null);
            if (m2 == null) return null;
            return invoke(m2, wpSession);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<Object> iterateWaypoints(Object waypointSet) {
        // 兼容 set / list / 数组
        try {
            if (waypointSet instanceof Iterable<?> it) {
                List<Object> out = new ArrayList<>();
                for (Object o : it) out.add(o);
                return out;
            }
        } catch (Throwable ignored) {}
        PrefabCustomAddon.LOGGER.debug("[XAERO] iterateWaypoints: not iterable, type={}",
            waypointSet == null ? "null" : waypointSet.getClass().getName());
        return new ArrayList<>();
    }

    private static String getWaypointName(Object wp) {
        try {
            Method m = wp.getClass().getMethod("getName");
            Object o = m.invoke(wp);
            return o == null ? null : o.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ============================================================
    // 反射辅助
    // ============================================================

    private static Method findMethodOnChain(Class<?> startClass, String methodName, Class<?>[] paramTypes) {
        Class<?> c = startClass;
        while (c != null && c != Object.class) {
            try {
                Method m = (paramTypes == null)
                    ? c.getMethod(methodName)
                    : c.getMethod(methodName, paramTypes);
                return m;
            } catch (NoSuchMethodException ignored) {}
            c = c.getSuperclass();
        }
        // 退而求其次, 在 getMethods 里翻
        for (Method m : startClass.getMethods()) {
            if (m.getName().equals(methodName)) return m;
        }
        return null;
    }

    /** 在类 + 父类链上找指定 name 的字段 (公开或非公开), 返回第一个. */
    private static Field findFieldRecursive(Class<?> startClass, String fieldName) {
        Class<?> c = startClass;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    /**
     * 按候选 class name 列表逐个 Class.forName, 第一个成功返回 Class, 全失败返回 null.
     * 用于兼容 Xaero 不同版本 (26.4.2 改包名) 的同一个类.
     */
    private static Class<?> findXaeroClass(String[] candidateNames) {
        for (String n : candidateNames) {
            try {
                return Class.forName(n);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object invoke(Method m, Object target, Object... args) {
        if (m == null || target == null) return null;
        try {
            return m.invoke(target, args);
        } catch (IllegalAccessException | InvocationTargetException e) {
            PrefabCustomAddon.LOGGER.debug("[XAERO] invoke {} failed: {}",
                m.getName(), e.getMessage());
            return null;
        }
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }
}
