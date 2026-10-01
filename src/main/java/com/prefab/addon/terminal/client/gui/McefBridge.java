package com.prefab.addon.terminal.client.gui;

import com.prefab.addon.terminal.TerminalRegistry;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * MCEF (Minecraft Chromium Embedded Framework) 反射桥.
 *
 * 零编译依赖: 全部反射调用, MCEF 未安装时 isAvailable() = false, 优雅降级为"系统浏览器"模式.
 * API 对照 MCEF 1.21.1 分支源码 (com.cinemamod.mcef.MCEF / MCEFBrowser / MCEFRenderer):
 *   MCEF.isInitialized() / MCEF.createBrowser(url, transparent, w, h)
 *   MCEFBrowser.getRenderer().getTextureID() / resize / loadURL / close
 *   MCEFBrowser.sendMouseMove|MousePress|MouseRelease|MouseWheel|KeyPress|KeyRelease|KeyTyped
 */
public final class McefBridge {
    private static Boolean available = null;
    private static String lastError = "";
    private static Method mIsInitialized, mCreateBrowser;
    private static Method mGetRenderer, mGetTextureID, mResize, mLoadURL, mClose;
    private static Method mMouseMove, mMousePress, mMouseRelease, mMouseWheel;
    private static Method mKeyPress, mKeyRelease, mKeyType;
    private static Method mSetFocus;
    private static Method mSetCursorChangeListener;
    private static boolean handlersInstalled = false;
    /** browser 实例 → 地址变化回调 (主线程投递). */
    private static final Map<Object, Consumer<String>> addressListeners = new ConcurrentHashMap<>();
    /** 被拦截的弹窗 (target=_blank) URL 回调 (主线程投递), 由 TerminalGui 注册为"开新 tab". */
    private static volatile Consumer<String> popupListener;

    private McefBridge() {
    }

    /** MCEF 类是否存在 (不代表已初始化, 初始化状态见 isInitialized). */
    public static boolean isAvailable() {
        if (available == null) {
            available = load();
        }
        return available;
    }

    private static boolean load() {
        try {
            Class<?> mcef = Class.forName("com.cinemamod.mcef.MCEF");
            mIsInitialized = mcef.getMethod("isInitialized");
            mCreateBrowser = mcef.getMethod("createBrowser", String.class, boolean.class, int.class, int.class);
            Class<?> browser = Class.forName("com.cinemamod.mcef.MCEFBrowser");
            mGetRenderer = browser.getMethod("getRenderer");
            mResize = browser.getMethod("resize", int.class, int.class);
            mLoadURL = browser.getMethod("loadURL", String.class);
            mClose = browser.getMethod("close");
            mMouseMove = browser.getMethod("sendMouseMove", int.class, int.class);
            mMousePress = browser.getMethod("sendMousePress", int.class, int.class, int.class);
            mMouseRelease = browser.getMethod("sendMouseRelease", int.class, int.class, int.class);
            mMouseWheel = browser.getMethod("sendMouseWheel", int.class, int.class, double.class, int.class);
            mKeyPress = browser.getMethod("sendKeyPress", int.class, long.class, int.class);
            mKeyRelease = browser.getMethod("sendKeyRelease", int.class, long.class, int.class);
            mKeyType = browser.getMethod("sendKeyTyped", char.class, int.class);
            mSetFocus = browser.getMethod("setFocus", boolean.class);
            Class<?> cursorListener = Class.forName("com.cinemamod.mcef.listeners.MCEFCursorChangeListener");
            mSetCursorChangeListener = browser.getMethod("setCursorChangeListener", cursorListener);
            Class<?> renderer = Class.forName("com.cinemamod.mcef.MCEFRenderer");
            mGetTextureID = renderer.getMethod("getTextureID");
            return true;
        } catch (Throwable t) {
            lastError = "load: " + t;
            TerminalRegistry.LOGGER.warn("[McefBridge] 反射装载失败", t);
            return false;
        }
    }

    /** 最近一次反射失败原因 (诊断用, 空串 = 无失败). */
    public static String lastError() {
        return lastError;
    }

    /** CEF 是否初始化完成 (MCEF 在游戏启动后异步初始化, 可能稍后就绪). */
    public static boolean isInitialized() {
        try {
            return (Boolean) mIsInitialized.invoke(null);
        } catch (Throwable t) {
            return false;
        }
    }

    public static Object createBrowser(String url, int width, int height) {
        installHandlers();
        try {
            return mCreateBrowser.invoke(null, url, false, width, height);
        } catch (Throwable t) {
            lastError = "createBrowser: " + t;
            TerminalRegistry.LOGGER.warn("[McefBridge] createBrowser 失败", t);
            return null;
        }
    }

    public static int textureId(Object browser) {
        try {
            return (Integer) mGetTextureID.invoke(mGetRenderer.invoke(browser));
        } catch (Throwable t) {
            lastError = "textureId: " + t;
            return 0;
        }
    }

    public static void resize(Object browser, int width, int height) {
        invoke(mResize, browser, width, height);
    }

    public static void loadURL(Object browser, String url) {
        invoke(mLoadURL, browser, url);
    }

    public static void close(Object browser) {
        invoke(mClose, browser);
    }

    public static void mouseMove(Object browser, int x, int y) {
        invoke(mMouseMove, browser, x, y);
    }

    public static void mousePress(Object browser, int x, int y, int button) {
        invoke(mMousePress, browser, x, y, button);
    }

    public static void mouseRelease(Object browser, int x, int y, int button) {
        invoke(mMouseRelease, browser, x, y, button);
    }

    public static void mouseWheel(Object browser, int x, int y, double amount, int modifiers) {
        invoke(mMouseWheel, browser, x, y, amount, modifiers);
    }

    public static void keyPress(Object browser, int keyCode, long scanCode, int modifiers) {
        invoke(mKeyPress, browser, keyCode, scanCode, modifiers);
    }

    public static void keyRelease(Object browser, int keyCode, long scanCode, int modifiers) {
        invoke(mKeyRelease, browser, keyCode, scanCode, modifiers);
    }

    public static void keyType(Object browser, char c, int modifiers) {
        invoke(mKeyType, browser, c, modifiers);
    }

    /** CEF 焦点 (官方示例在每次鼠标/键盘交互后调 setFocus(true)). */
    public static void setFocus(Object browser, boolean focused) {
        invoke(mSetFocus, browser, focused);
    }

    /**
     * 挂光标变化回调 (诊断 hover 是否送达渲染器): 鼠标移到链接上时 CEF 会把光标切成手型,
     * 若 move 事件到不了渲染器则光标永远不变. 会替换 MCEF 默认的 GLFW 光标联动 (诊断期可接受).
     */
    public static void watchCursor(Object browser, IntConsumer sink) {
        if (mSetCursorChangeListener == null) {
            return;
        }
        try {
            Class<?> iface = mSetCursorChangeListener.getParameterTypes()[0];
            Object proxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, (p, method, args) -> {
                if ("onCursorChange".equals(method.getName()) && args != null && args.length >= 1 && args[0] instanceof Integer id) {
                    sink.accept(id);
                }
                return defaultValue(method.getReturnType());
            });
            mSetCursorChangeListener.invoke(browser, proxy);
        } catch (Throwable t) {
            lastError = "watchCursor: " + t;
            TerminalRegistry.LOGGER.warn("[McefBridge] watchCursor 失败", t);
        }
    }

    /** 注册某浏览器实例的地址变化回调 (onAddressChange, 主线程). */
    public static void watchAddress(Object browser, Consumer<String> listener) {
        addressListeners.put(browser, listener);
    }

    public static void unwatchAddress(Object browser) {
        addressListeners.remove(browser);
    }

    /** 注册弹窗拦截回调 (onBeforePopup 被取消时投递目标 URL, 主线程). */
    public static void setPopupListener(Consumer<String> listener) {
        popupListener = listener;
    }

    /**
     * 挂两个 JCEF 处理器 (反射 + 动态代理, 零编译依赖, 接口签名跨 JCEF 版本按方法名分发):
     * - CefDisplayHandler.onAddressChange → 地址栏同步;
     * - CefLifeSpanHandler.onBeforePopup → 取消 CEF 自带弹窗 (无渲染器不可见), 改投终端新 tab.
     */
    private static void installHandlers() {
        if (handlersInstalled) {
            return;
        }
        try {
            Class<?> mcef = Class.forName("com.cinemamod.mcef.MCEF");
            ClassLoader loader = mcef.getClassLoader();
            Object client = mcef.getMethod("getClient").invoke(null);
            Class<?> displayIface = Class.forName("org.cef.handler.CefDisplayHandler", false, loader);
            Object displayProxy = Proxy.newProxyInstance(loader, new Class<?>[]{displayIface}, (proxy, method, args) -> {
                if ("onAddressChange".equals(method.getName()) && args != null && args.length >= 3 && args[2] instanceof String url) {
                    TerminalRegistry.LOGGER.info("[McefBridge] onAddressChange url={}", url);
                    Object browser = args[0];
                    Minecraft.getInstance().tell(() -> {
                        Consumer<String> listener = addressListeners.get(browser);
                        if (listener != null) {
                            listener.accept(url);
                        }
                    });
                }
                return defaultValue(method.getReturnType());
            });
            client.getClass().getMethod("addDisplayHandler", displayIface).invoke(client, displayProxy);
            Object cefClient = client.getClass().getMethod("getHandle").invoke(client);
            Class<?> lifeIface = Class.forName("org.cef.handler.CefLifeSpanHandler", false, loader);
            Object lifeProxy = Proxy.newProxyInstance(loader, new Class<?>[]{lifeIface}, (proxy, method, args) -> {
                if ("onBeforePopup".equals(method.getName())) {
                    String url = extractPopupUrl(args);
                    TerminalRegistry.LOGGER.info("[McefBridge] onBeforePopup url={}", url);
                    if (url != null) {
                        Minecraft.getInstance().tell(() -> {
                            Consumer<String> listener = popupListener;
                            if (listener != null) {
                                listener.accept(url);
                            }
                        });
                        return Boolean.TRUE;
                    }
                }
                return defaultValue(method.getReturnType());
            });
            cefClient.getClass().getMethod("addLifeSpanHandler", lifeIface).invoke(cefClient, lifeProxy);
            // 探针: 同 tab 导航尝试 (链接点击若送达渲染器, 必触发 onBeforeBrowse)
            Class<?> reqIface = Class.forName("org.cef.handler.CefRequestHandler", false, loader);
            Object reqProxy = Proxy.newProxyInstance(loader, new Class<?>[]{reqIface}, (proxy, method, args) -> {
                if ("onBeforeBrowse".equals(method.getName()) && args != null) {
                    String url = extractRequestUrl(args);
                    if (url == null) {
                        StringBuilder sb = new StringBuilder();
                        for (Object a : args) {
                            sb.append(a == null ? "null" : a.getClass().getName()).append(", ");
                        }
                        TerminalRegistry.LOGGER.info("[McefBridge] onBeforeBrowse url=null args=[{}]", sb);
                    } else {
                        TerminalRegistry.LOGGER.info("[McefBridge] onBeforeBrowse url={}", url);
                    }
                }
                return defaultValue(method.getReturnType());
            });
            cefClient.getClass().getMethod("addRequestHandler", reqIface).invoke(cefClient, reqProxy);
            // 探针: 导航生命周期 (onLoadStart/End/Error), 定位"导航发起后死在哪一环"
            Class<?> loadIface = Class.forName("org.cef.handler.CefLoadHandler", false, loader);
            Object loadProxy = Proxy.newProxyInstance(loader, new Class<?>[]{loadIface}, (proxy, method, args) -> {
                String n = method.getName();
                if (args != null && args.length >= 2) {
                    if (n.equals("onLoadStart")) {
                        TerminalRegistry.LOGGER.info("[McefBridge] onLoadStart frame={}", frameUrl(args[1]));
                    } else if (n.equals("onLoadEnd") && args.length >= 3) {
                        TerminalRegistry.LOGGER.info("[McefBridge] onLoadEnd frame={} status={}", frameUrl(args[1]), args[2]);
                    } else if (n.equals("onLoadError") && args.length >= 5) {
                        TerminalRegistry.LOGGER.info("[McefBridge] onLoadError code={} text={} failedUrl={}", args[2], args[3], args[4]);
                    }
                }
                return defaultValue(method.getReturnType());
            });
            client.getClass().getMethod("addLoadHandler", loadIface).invoke(client, loadProxy);
            handlersInstalled = true;
        } catch (Throwable t) {
            lastError = "installHandlers: " + t;
            TerminalRegistry.LOGGER.warn("[McefBridge] 挂载 CEF 处理器失败", t);
        }
    }

    /** onBeforePopup 签名跨 JCEF 版本有变体, 从第 3 个参数起找第一个像 URL 的 String (target_url). */
    private static String extractPopupUrl(Object[] args) {
        if (args == null) {
            return null;
        }
        for (int i = 2; i < args.length; i++) {
            if (args[i] instanceof String s && s.contains("://")) {
                return s;
            }
        }
        return null;
    }

    /** 反射取 CefFrame.getURL() (探针日志用). */
    private static String frameUrl(Object frame) {
        if (frame == null) {
            return "null";
        }
        try {
            return (String) frame.getClass().getMethod("getURL").invoke(frame);
        } catch (Throwable t) {
            TerminalRegistry.LOGGER.info("[McefBridge] frame.getURL 失败: {}", t.toString());
            return "?";
        }
    }

    /** 从 onBeforeBrowse 参数里找 CefRequest 并反射取 getURL() (CefBrowser 也有 getURL, 必须按类型名区分). */
    private static String extractRequestUrl(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object a : args) {
            if (a == null || !a.getClass().getName().contains("CefRequest")) {
                continue;
            }
            try {
                return (String) a.getClass().getMethod("getURL").invoke(a);
            } catch (Throwable t) {
                TerminalRegistry.LOGGER.info("[McefBridge] request.getURL 失败: {}", t.toString());
            }
        }
        return null;
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return Boolean.FALSE;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        return null;
    }

    private static void invoke(Method method, Object target, Object... args) {
        if (method == null) return;
        try {
            method.invoke(target, args);
        } catch (Throwable t) {
            lastError = method.getName() + ": " + t;
            TerminalRegistry.LOGGER.warn("[McefBridge] invoke {} 失败", method.getName(), t);
        }
    }
}
