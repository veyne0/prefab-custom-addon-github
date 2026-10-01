package com.prefab.addon.terminal.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.prefab.addon.client.ThumbnailCache;
import com.prefab.addon.extension.ConstructionInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 建筑缩略图纹理加载 (纯客户端): PNG 字节 → 工作线程解码 → 主线程上传 DynamicTexture → 缓存 RL.
 *
 * 加载/上传模式照抄主模组 GuiExtensionPackBrowser (ensurePreviewTextureLoaded / uploadIconTexture),
 * 区别是这里异步执行 (主模组在渲染线程同步读文件, 大量建筑时进界面会卡顿),
 * 并按建筑 id 缓存空值避免对无图建筑反复重试.
 */
public final class TerminalBuildingThumbs {

    /** 建筑 id → 纹理 RL; value=null 表示确认无图/加载失败 (空值也缓存, 避免反复重试). */
    private static final Map<String, ResourceLocation> CACHE = new HashMap<>();
    /** 正在异步加载的 id 集合 (防重复触发). */
    private static final Set<String> PENDING = new HashSet<>();
    /** 纹理就绪监听 (建筑选择面板用它触发刷新); 终端 GUI 关闭时统一清空. */
    private static final List<Runnable> LISTENERS = new ArrayList<>();

    private TerminalBuildingThumbs() {
    }

    public static void addListener(Runnable r) {
        LISTENERS.add(r);
    }

    /** 终端 GUI 关闭时清空监听 (面板重建后会重新 addListener); 纹理缓存保留, 重开界面直接复用. */
    public static void clearListeners() {
        LISTENERS.clear();
    }

    /**
     * 取缩略图 RL. 未就绪时触发异步加载并返回 null (UI 先显示占位底),
     * 加载完成后在主线程通知 {@link #LISTENERS} 刷新.
     */
    public static @Nullable ResourceLocation get(ConstructionInfo c) {
        String key = c.getId();
        if (CACHE.containsKey(key)) {
            return CACHE.get(key);
        }
        if (PENDING.add(key)) {
            loadAsync(c);
        }
        return null;
    }

    /** 工作线程: 收集 PNG 字节 (pngData → 本地预览图 → ThumbnailCache 磁盘缓存) 并解码. */
    private static void loadAsync(ConstructionInfo c) {
        String key = c.getId();
        CompletableFuture.supplyAsync(() -> {
            byte[] data = c.getPngData();
            if ((data == null || data.length == 0) && c.getLocalImagePath() != null) {
                try {
                    data = Files.readAllBytes(c.getLocalImagePath());
                } catch (Exception ignored) {
                    // 读失败回落到磁盘缓存
                }
            }
            if ((data == null || data.length == 0) && ThumbnailCache.hasCached(c)) {
                try {
                    data = ThumbnailCache.read(c);
                } catch (Exception ignored) {
                }
            }
            if (data == null || data.length == 0) {
                return null;
            }
            try (ByteArrayInputStream is = new ByteArrayInputStream(data)) {
                return ImageIO.read(is);
            } catch (Exception e) {
                return null;
            }
        }).thenAccept(img -> Minecraft.getInstance().tell(() -> {
            ResourceLocation rl = null;
            if (img != null) {
                try {
                    DynamicTexture tex = uploadIconTexture(img);
                    if (tex != null) {
                        // 建筑 id 可能含 ResourceLocation 非法字符, 注册名清洗一次 (缓存键仍用原 id)
                        String safe = key.toLowerCase().replaceAll("[^a-z0-9_.\\-/]", "_");
                        rl = Minecraft.getInstance().getTextureManager()
                                .register("terminal_building_" + safe, tex);
                    }
                } catch (Exception ignored) {
                    // 上传失败按无图处理
                }
            }
            CACHE.put(key, rl);
            PENDING.remove(key);
            for (Runnable r : List.copyOf(LISTENERS)) {
                r.run();
            }
        }));
    }

    /**
     * BufferedImage → DynamicTexture: 中心正方裁剪 + 超过 256 缩至 256 + ARGB→ABGR 逐像素写入.
     * 与主模组 GuiExtensionPackBrowser.uploadIconTexture 同逻辑 (private 无法直接复用).
     */
    @Nullable
    private static DynamicTexture uploadIconTexture(BufferedImage img) {
        if (img == null) {
            return null;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }

        // 中心正方裁剪 (getSubimage 与源共享数据, 先画到独立图再交给后续缩放)
        int cropSide = Math.min(w, h);
        int cropX = (w - cropSide) / 2;
        int cropY = (h - cropSide) / 2;
        if (cropSide < w || cropSide < h) {
            BufferedImage cropped = new BufferedImage(cropSide, cropSide, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = cropped.createGraphics();
            g.drawImage(img.getSubimage(cropX, cropY, cropSide, cropSide), 0, 0, null);
            g.dispose();
            img = cropped;
            w = cropSide;
            h = cropSide;
        }
        // 过大缩到 256 (BICUBIC 高质量)
        if (w > 256) {
            BufferedImage scaled = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(img, 0, 0, 256, 256, null);
            g.dispose();
            img = scaled;
            w = 256;
            h = 256;
        }

        DynamicTexture tex = new DynamicTexture(w, h, false);
        tex.setFilter(true, true);
        NativeImage pixels = tex.getPixels();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int abgr = argb & 0xFF00FF00 | (argb & 0xFF0000) >> 16 | (argb & 0xFF) << 16;
                pixels.setPixelRGBA(x, y, abgr);
            }
        }
        tex.upload();
        return tex;
    }
}
