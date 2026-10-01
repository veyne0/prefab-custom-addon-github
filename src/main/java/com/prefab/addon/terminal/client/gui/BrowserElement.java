package com.prefab.addon.terminal.client.gui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.prefab.addon.terminal.TerminalRegistry;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.function.Consumer;

/**
 * 嵌入式浏览器面板: 把 MCEF 的 Chromium 离屏渲染贴图画进 LDLib2 元素,
 * 并把元素收到的鼠标 / 滚轮 / 键盘事件转发给 CEF 浏览器.
 *
 * MCEF 未安装或未初始化时本元素只是一块深色占位面板 (降级为系统浏览器模式).
 * 生命周期: 首次绘制时按元素实际像素尺寸创建浏览器; dispose() 释放 (关 tab / 关界面时必调).
 */
public class BrowserElement extends UIElement {
    private String url;
    @Nullable
    private Object browser;
    private int lastWidth = 0, lastHeight = 0;
    private int lastTex = 0;
    // 事件诊断计数: 区分"事件没到元素"与"到了但 CEF 不响应"
    private int evMove, evPress, evRelease;
    private int lastCx, lastCy;
    private int lastPixW, lastPixH;
    private int lastCursor = -1;
    @Nullable
    private Consumer<String> addressSink;

    public BrowserElement(String startUrl) {
        this.url = normalize(startUrl);
        style(s -> s.backgroundTexture(Sprites.RECT_DARK));
        setFocusable(true);
        addEventListener(UIEvents.MOUSE_MOVE, this::onMouseMove);
        addEventListener(UIEvents.MOUSE_DOWN, this::onMouseDown);
        addEventListener(UIEvents.MOUSE_UP, this::onMouseUp);
        addEventListener(UIEvents.MOUSE_WHEEL, this::onMouseWheel);
        addEventListener(UIEvents.KEY_DOWN, this::onKeyDown);
        addEventListener(UIEvents.KEY_UP, this::onKeyUp);
        addEventListener(UIEvents.CHAR_TYPED, this::onCharTyped);
    }

    /** 地址栏同步回调 (主线程): CEF 地址变化 (页内跳转/重定向) 时回写终端地址栏. */
    public void setOnAddressChange(Consumer<String> sink) {
        this.addressSink = sink;
    }

    /** 地址栏跳转: 浏览器已创建则直接 loadURL, 否则记下 URL 等创建时用. */
    public void navigate(String newUrl) {
        this.url = normalize(newUrl);
        if (browser != null) {
            McefBridge.loadURL(browser, url);
        }
    }

    /** 释放 Chromium 离屏资源; 重复调用安全. */
    public void dispose() {
        if (browser != null) {
            McefBridge.unwatchAddress(browser);
            McefBridge.close(browser);
            browser = null;
        }
    }

    @Override
    public void drawBackgroundAdditional(GUIContext guiContext) {
        if (!McefBridge.isAvailable() || !McefBridge.isInitialized()) {
            return;
        }
        int guiW = (int) getSizeWidth();
        int guiH = (int) getSizeHeight();
        if (guiW <= 0 || guiH <= 0) {
            return;
        }
        // CEF 视口按真实像素建 (GUI 单位 × guiScale), 与 MCEF 官方 ExampleScreen 一致, 保证 1:1 清晰度
        int w = toPixels(guiW);
        int h = toPixels(guiH);
        if (browser == null) {
            browser = McefBridge.createBrowser(url, w, h);
            if (browser != null) {
                McefBridge.watchAddress(browser, this::onAddressChanged);
                // 起始焦点: 不等首次交互, 创建后立刻聚焦, 避免 OSR 输入路由因未聚焦而丢弃事件
                McefBridge.setFocus(browser, true);
                // 光标探针: 移到链接上应切手型 (move 送达渲染器的铁证)
                McefBridge.watchCursor(browser, id -> {
                    if (id != lastCursor) {
                        lastCursor = id;
                        TerminalRegistry.LOGGER.info("[BrowserElement] cursor -> {}", id);
                    }
                });
            }
            lastWidth = guiW;
            lastHeight = guiH;
            lastPixW = w;
            lastPixH = h;
        } else if (guiW != lastWidth || guiH != lastHeight) {
            McefBridge.resize(browser, w, h);
            lastWidth = guiW;
            lastHeight = guiH;
            lastPixW = w;
            lastPixH = h;
        }
        if (browser == null) {
            return;
        }
        int textureId = McefBridge.textureId(browser);
        lastTex = textureId;
        if (textureId <= 0) {
            return;
        }
        // 绘制序列逐字对齐 MCEF 官方 ExampleScreen (1.21.1):
        // 1) 先 flush: 1.21.1 GuiGraphics 为批量提交, 不 flush 则本立即四边形会被队列里
        //    尚未落盘的批次 (如本元素背景) 事后盖住 —— 此前黑屏的真因;
        // 2) position_tex_color + 逐顶点色: 避开 position_tex 的 ColorModulator 调制;
        // 3) 关深度测试画完再恢复.
        guiContext.graphics.flush();
        float x = getPositionX();
        float y = getPositionY();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, textureId);
        Matrix4f pose = guiContext.graphics.pose().last().pose();
        BufferBuilder bufferbuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bufferbuilder.addVertex(pose, x, y + guiH, 0.0f).setUv(0.0f, 1.0f).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(pose, x + guiW, y + guiH, 0.0f).setUv(1.0f, 1.0f).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(pose, x + guiW, y, 0.0f).setUv(1.0f, 0.0f).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(pose, x, y, 0.0f).setUv(0.0f, 0.0f).setColor(255, 255, 255, 255);
        BufferUploader.drawWithShader(bufferbuilder.buildOrThrow());
        RenderSystem.setShaderTexture(0, 0);
        RenderSystem.enableDepthTest();
        super.drawBackgroundAdditional(guiContext);
    }

    /// 事件转发: 屏幕坐标 → CEF 视口像素坐标.
    /// 注意 getLocalMouse 返回的是父空间坐标 (不减元素原点, 见 UIElement.isMouseOver 拿它与 getPositionX/Y 比较),
    /// 必须再减自己的 position 才是元素本地坐标, 否则点击落点整体偏移 → 页内链接点不中.
    private Vector2f cefMouse(UIEvent event) {
        Vector2f local = getLocalMouse(event.x, event.y);
        return new Vector2f(toPixels(local.x - getPositionX()), toPixels(local.y - getPositionY()));
    }

    private void onAddressChanged(String newUrl) {
        if (addressSink != null) {
            addressSink.accept(newUrl);
        }
    }

    private void onMouseMove(UIEvent event) {
        if (browser == null) return;
        Vector2f p = cefMouse(event);
        evMove++;
        lastCx = (int) p.x;
        lastCy = (int) p.y;
        McefBridge.mouseMove(browser, (int) p.x, (int) p.y);
    }

    private void onMouseDown(UIEvent event) {
        if (browser == null) return;
        focus();
        Vector2f p = cefMouse(event);
        evPress++;
        lastCx = (int) p.x;
        lastCy = (int) p.y;
        McefBridge.mousePress(browser, (int) p.x, (int) p.y, event.button);
        TerminalRegistry.LOGGER.info("[BrowserElement] press cef={},{} button={}", (int) p.x, (int) p.y, event.button);
        McefBridge.setFocus(browser, true);
        event.stopPropagation();
    }

    private void onMouseUp(UIEvent event) {
        if (browser == null) return;
        Vector2f p = cefMouse(event);
        evRelease++;
        McefBridge.mouseRelease(browser, (int) p.x, (int) p.y, event.button);
        TerminalRegistry.LOGGER.info("[BrowserElement] release cef={},{} button={}", (int) p.x, (int) p.y, event.button);
        McefBridge.setFocus(browser, true);
        event.stopPropagation();
    }

    private void onMouseWheel(UIEvent event) {
        if (browser == null) return;
        Vector2f p = cefMouse(event);
        McefBridge.mouseWheel(browser, (int) p.x, (int) p.y, event.deltaY, event.modifiers);
        event.stopPropagation();
    }

    private void onKeyDown(UIEvent event) {
        // ESC 留给界面关闭, 不吞
        if (browser == null || !isFocused() || event.keyCode == GLFW.GLFW_KEY_ESCAPE) return;
        McefBridge.keyPress(browser, event.keyCode, event.scanCode, event.modifiers);
        McefBridge.setFocus(browser, true);
        event.stopPropagation();
    }

    private void onKeyUp(UIEvent event) {
        if (browser == null || !isFocused() || event.keyCode == GLFW.GLFW_KEY_ESCAPE) return;
        McefBridge.keyRelease(browser, event.keyCode, event.scanCode, event.modifiers);
        McefBridge.setFocus(browser, true);
        event.stopPropagation();
    }

    private void onCharTyped(UIEvent event) {
        if (browser == null || !isFocused() || event.codePoint == 0) return;
        McefBridge.keyType(browser, event.codePoint, event.modifiers);
        McefBridge.setFocus(browser, true);
        event.stopPropagation();
    }

    /** GUI 单位 → 真实像素 (CEF 视口/鼠标坐标空间). */
    private static int toPixels(float guiUnits) {
        return (int) (guiUnits * Minecraft.getInstance().getWindow().getGuiScale());
    }

    /** 诊断行: 嵌入链路各环状态 (状态栏实时显示, 黑屏定位用). */
    public String debugState() {
        return String.format("init=%s browser=%s tex=%d size=%dx%d mv=%d pr=%d rel=%d @%d,%d err=%s",
                McefBridge.isInitialized(), browser != null, lastTex, lastPixW, lastPixH,
                evMove, evPress, evRelease, lastCx, lastCy, McefBridge.lastError());
    }

    /** 补全协议头, 空串回 about:blank. */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "about:blank";
        }
        String trimmed = raw.trim();
        if (!trimmed.contains("://")) {
            return "https://" + trimmed;
        }
        return trimmed;
    }
}
