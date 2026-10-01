package com.prefab.addon.terminal.client;

import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.prefab.addon.terminal.TerminalRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.language.I18n;
import net.neoforged.fml.loading.FMLPaths;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 主界面壁纸管理 (仅 client).
 *
 * 玩家选一张本地图片 → NativeImage 读入 → DynamicTexture 上传 → 主界面内容面板以整幅拉伸的四边形绘制.
 * 选中的路径持久化到 config/modern_terminal/wallpaper.txt, 下次启动自动恢复.
 *
 * 绘制沿用 BrowserElement 已验证的序列: 先 flush (1.21.1 GuiGraphics 批量提交) → 关深度 →
 * POSITION_TEX_COLOR 逐顶点色四边形 (uv 0..1 拉伸) → 恢复.
 */
public final class WallpaperManager {
    /** NativeImage 走 stb_image, 这些扩展名是它能解的; 其余的快速失败并给出明确提示. */
    private static final String[] SUPPORTED_EXT = {".png", ".jpg", ".jpeg", ".bmp", ".gif", ".tga"};
    /** 单边像素上限: 超过多半是选错文件, 且上传 GL 纹理开销巨大. */
    private static final int MAX_DIMENSION = 8192;

    @Nullable
    private static DynamicTexture texture;
    private static int glId = 0;
    private static String currentPath = "";
    /** 最近一次 apply 失败的原因 (已本地化); 成功时为空. */
    private static String lastError = "";

    private WallpaperManager() {
    }

    public static boolean has() {
        return texture != null && glId > 0;
    }

    public static String currentPath() {
        return currentPath;
    }

    public static String lastError() {
        return lastError;
    }

    /** 启动时调用: 从配置文件恢复上次选择的壁纸. */
    public static void load() {
        String saved = readConfig();
        if (!saved.isBlank()) {
            apply(saved, false);
        }
    }

    /**
     * 应用一张图片为壁纸; persist=true 时写回配置文件. 返回是否成功.
     *
     * 绝不抛异常: 路径非法/文件不存在/格式不支持/解码失败 都只记 lastError + 日志, 由 UI 提示.
     * 必须在渲染线程调用 (要建 GL 纹理); 其他线程会自动转到渲染线程重放并返回 false.
     */
    public static boolean apply(String path, boolean persist) {
        lastError = "";
        if (path == null || path.isBlank()) {
            clear(persist);
            return false;
        }
        if (!RenderSystem.isOnRenderThread()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                lastError = I18n.get("gui.modern_terminal.app.settings.wallpaper.err.wrong_thread");
                TerminalRegistry.LOGGER.warn("[WALLPAPER] 客户端尚未就绪, 无法在非渲染线程加载壁纸: {}", path);
                return false;
            }
            // DynamicTexture 会调 RenderSystem, 非渲染线程直接抛 "Rendersystem called from wrong thread"
            mc.tell(() -> apply(path, persist));
            return false;
        }
        String cleaned = sanitizePath(path);
        Path p;
        try {
            p = Path.of(cleaned);
        } catch (InvalidPathException e) {
            // Windows "复制文件地址" 的引号已剥掉, 到这里说明路径里仍有非法字符
            lastError = I18n.get("gui.modern_terminal.app.settings.wallpaper.err.bad_path");
            TerminalRegistry.LOGGER.warn("[WALLPAPER] 路径非法 \"{}\": {}", cleaned, e.getReason());
            return false;
        }
        if (!hasSupportedExtension(cleaned)) {
            lastError = I18n.get("gui.modern_terminal.app.settings.wallpaper.err.unsupported");
            TerminalRegistry.LOGGER.warn("[WALLPAPER] 格式不支持: {}", cleaned);
            return false;
        }
        if (!Files.isRegularFile(p)) {
            lastError = I18n.get("gui.modern_terminal.app.settings.wallpaper.err.not_found");
            TerminalRegistry.LOGGER.warn("[WALLPAPER] 文件不存在: {}", p);
            return false;
        }
        NativeImage image = null;
        try {
            image = decode(p);
            int w = image.getWidth();
            int h = image.getHeight();
            if (w > MAX_DIMENSION || h > MAX_DIMENSION) {
                lastError = String.format(I18n.get("gui.modern_terminal.app.settings.wallpaper.err.too_big"), w, h);
                image.close();
                image = null;
                return false;
            }
            releaseTexture();
            texture = new DynamicTexture(image);
            image = null; // 所有权已交给 DynamicTexture
            glId = texture.getId();
            currentPath = p.toAbsolutePath().toString();
            if (persist) {
                writeConfig(currentPath);
            }
            TerminalRegistry.LOGGER.info("[WALLPAPER] 已加载壁纸 {}x{}: {}", w, h, currentPath);
            return true;
        } catch (Throwable e) {
            // 解码失败 / IO 失败 / GL 上传失败, 一律不崩游戏
            lastError = I18n.get("gui.modern_terminal.app.settings.wallpaper.err.decode");
            TerminalRegistry.LOGGER.warn("[WALLPAPER] 壁纸加载失败 {}: {}", p, e.toString());
            return false;
        } finally {
            if (image != null) {
                try {
                    image.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 解码图片.
     *
     * 先走 Minecraft 原生 stb 通道 (PNG 最快, 零拷贝); 它只认 PNG, 碰到 jpg/bmp/gif 会抛
     * "Bad PNG Signature", 这时再用 ImageIO 逐像素转成 NativeImage (headless 下也可用).
     */
    private static NativeImage decode(Path p) throws IOException {
        Throwable nativeFailure;
        try (InputStream in = Files.newInputStream(p)) {
            return NativeImage.read(in);
        } catch (Throwable e) {
            nativeFailure = e;
        }
        NativeImage converted = decodeViaImageIo(p);
        if (converted == null) {
            throw new IOException("图片解码失败 (原生通道: " + nativeFailure + ")", nativeFailure);
        }
        TerminalRegistry.LOGGER.info("[WALLPAPER] 原生解码失败, 已改用 ImageIO 转换: {}", p.getFileName());
        return converted;
    }

    /**
     * ImageIO 通道: BufferedImage → NativeImage.
     *
     * NativeImage 像素存的是 ABGR 小端 int (0xAABBGGRR), 而 BufferedImage.getRGB 给的是 ARGB (0xAARRGGBB),
     * 必须换通道顺序; useStb=false 与原版 MapRenderer 手建 NativeImage 的用法一致 (不会上下颠倒).
     */
    @Nullable
    private static NativeImage decodeViaImageIo(Path p) {
        BufferedImage src;
        try {
            src = ImageIO.read(p.toFile());
        } catch (Throwable e) {
            TerminalRegistry.LOGGER.warn("[WALLPAPER] ImageIO 读取失败 {}: {}", p, e.toString());
            return null;
        }
        if (src == null) {
            return null;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        NativeImage out = new NativeImage(NativeImage.Format.RGBA, w, h, false);
        try {
            int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
            for (int y = 0, i = 0; y < h; y++) {
                for (int x = 0; x < w; x++, i++) {
                    int c = argb[i];
                    // ARGB(0xAARRGGBB) -> ABGR(0xAABBGGRR): A/G 位置不变, B 上移 16, R 下移 16
                    int abgr = (c & 0xFF00FF00) | ((c & 0x000000FF) << 16) | ((c & 0x00FF0000) >>> 16);
                    out.setPixelRGBA(x, y, abgr);
                }
            }
            return out;
        } catch (Throwable e) {
            out.close();
            TerminalRegistry.LOGGER.warn("[WALLPAPER] ImageIO 像素转换失败 {}: {}", p, e.toString());
            return null;
        }
    }

    /**
     * 清理玩家输入的路径: 去首尾空白 + 剥掉成对引号.
     *
     * Windows 资源管理器 "复制文件地址" 得到的是 {@code "C:\a b\c.png"} (带引号),
     * 直接喂给 Path.of 会抛 InvalidPathException; 全角引号一并处理.
     */
    static String sanitizePath(String raw) {
        String s = raw.trim();
        int start = 0;
        int end = s.length();
        while (start < end && isQuote(s.charAt(start))) {
            start++;
        }
        while (end > start && isQuote(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end).trim();
    }

    private static boolean isQuote(char c) {
        return c == '"' || c == '\'' || c == '\u201c' || c == '\u201d' || c == '\u2018' || c == '\u2019';
    }

    private static boolean hasSupportedExtension(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        for (String ext : SUPPORTED_EXT) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /** 清除壁纸 (释放 GL 纹理); persist=true 时清空配置文件. */
    public static void clear(boolean persist) {
        releaseTexture();
        currentPath = "";
        if (persist) {
            writeConfig("");
        }
    }

    private static void releaseTexture() {
        if (texture != null) {
            try {
                texture.close();
            } catch (Throwable ignored) {
            }
            texture = null;
            glId = 0;
        }
    }

    /** 在主界面内容面板上整幅拉伸绘制壁纸 (无壁纸时 no-op). */
    public static void draw(GUIContext ctx, float x, float y, float w, float h) {
        if (!has() || w <= 0 || h <= 0) {
            return;
        }
        ctx.graphics.flush();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, glId);
        Matrix4f pose = ctx.graphics.pose().last().pose();
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bb.addVertex(pose, x, y + h, 0.0f).setUv(0.0f, 1.0f).setColor(255, 255, 255, 255);
        bb.addVertex(pose, x + w, y + h, 0.0f).setUv(1.0f, 1.0f).setColor(255, 255, 255, 255);
        bb.addVertex(pose, x + w, y, 0.0f).setUv(1.0f, 0.0f).setColor(255, 255, 255, 255);
        bb.addVertex(pose, x, y, 0.0f).setUv(0.0f, 0.0f).setColor(255, 255, 255, 255);
        BufferUploader.drawWithShader(bb.buildOrThrow());
        RenderSystem.setShaderTexture(0, 0);
        RenderSystem.enableDepthTest();
    }

    // ==== 配置持久化 ====
    private static Path configFile() {
        Path dir = FMLPaths.CONFIGDIR.get().resolve("modern_terminal");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        return dir.resolve("wallpaper.txt");
    }

    private static String readConfig() {
        try {
            Path f = configFile();
            if (!Files.isRegularFile(f)) {
                return "";
            }
            return Files.readString(f).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeConfig(String value) {
        try {
            Files.writeString(configFile(), value);
        } catch (IOException e) {
            TerminalRegistry.LOGGER.warn("[WALLPAPER] 配置写入失败: {}", e.toString());
        }
    }
}
