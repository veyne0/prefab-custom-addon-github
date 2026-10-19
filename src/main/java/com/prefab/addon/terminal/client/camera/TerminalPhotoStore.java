package com.prefab.addon.terminal.client.camera;

import com.mojang.blaze3d.platform.NativeImage;
import com.prefab.addon.terminal.TerminalRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 终端相册存储 (client-only, 参考 MIT 协议的 Camerapture 拍照存储思路简化而来).
 *
 * 照片 = PNG 文件, 存 {gameDir}/modern_terminal/photos/photo_<时间戳>.png.
 * 文件名即元数据 (时间戳自然有序), 无需索引文件; 相册 = 目录列表.
 *
 * GL 纹理缓存: 相册 UI 显示照片时把 PNG 解码为 DynamicTexture 注册到 TextureManager,
 * LRU 最多 {@link #TEX_CACHE_MAX} 张, 逐出即释放, 防止多图常驻显存.
 */
public final class TerminalPhotoStore {
    /** 全尺寸纹理缓存上限 (只有查看器用, 同屏最多 1 张 + 余量). */
    private static final int TEX_CACHE_MAX = 8;
    /** 缩略图缓存上限 (一页网格 15~18 张 + 余量; 192x108 RGBA 约 83KB/张, 64 张 ≈ 5MB 显存). */
    private static final int THUMB_CACHE_MAX = 64;
    /** 缩略图最大尺寸 (网格格子 96x54, 2x 留清晰度余量). */
    private static final int THUMB_MAX_W = 192;
    private static final int THUMB_MAX_H = 108;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmssSSS");

    /** name(file name) → full-size texture RL, accessOrder 实现真正的 LRU. */
    private static final LinkedHashMap<String, ResourceLocation> texCache = new LinkedHashMap<>(16, 0.75f, true);
    /** name(file name) → 缩略图 RL, 同样 LRU. 网格用缩略图, 避免全尺寸解码/显存双爆炸. */
    private static final LinkedHashMap<String, ResourceLocation> thumbCache = new LinkedHashMap<>(16, 0.75f, true);

    private TerminalPhotoStore() {
    }

    public static Path photosDir() {
        return Paths.get(Minecraft.getInstance().gameDirectory.getAbsolutePath(),
                "modern_terminal", "photos");
    }

    /** 保存照片, 返回文件名 (photo_yyyyMMdd_HHmmssSSS.png). 毫秒级时间戳 + 冲突后缀防覆盖. */
    public static String save(NativeImage image) throws IOException {
        Path dir = photosDir();
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(FMT);
        Path file = null;
        for (int n = 0; ; n++) {
            Path candidate = dir.resolve("photo_" + stamp + (n > 0 ? "_" + n : "") + ".png");
            if (Files.notExists(candidate)) {
                file = candidate;
                break;
            }
        }
        image.writeToFile(file);
        TerminalRegistry.LOGGER.info("[CAMERA] 照片已保存: {}", file);
        return file.getFileName().toString();
    }

    /** 全部照片 (按文件名倒序 = 新的在前). */
    public static List<String> listPhotos() {
        try (Stream<Path> stream = Files.list(photosDir())) {
            List<String> names = new ArrayList<>();
            stream.filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".png");
                    })
                    .map(p -> p.getFileName().toString())
                    .sorted(Comparator.reverseOrder())
                    .forEach(names::add);
            return names;
        } catch (IOException dirMissing) {
            return List.of();
        }
    }

    public static boolean delete(String name) {
        releaseTexture(name);
        releaseThumbnail(name);
        try {
            return Files.deleteIfExists(photosDir().resolve(name));
        } catch (IOException e) {
            TerminalRegistry.LOGGER.warn("[CAMERA] 删除照片失败: {}", name, e);
            return false;
        }
    }

    /** 文件名 → 展示用日期字符串 (photo_20260910_142530123.png → 2026-09-10 14:25:30). */
    public static String displayDate(String name) {
        String body = name;
        int dot = body.lastIndexOf('.');
        if (dot > 0) {
            body = body.substring(0, dot);
        }
        if (body.startsWith("photo_")) {
            body = body.substring(6);
        }
        if (body.length() >= 15) {
            String date = body.substring(0, 8);
            String time = body.substring(9, 15);
            String ms = body.length() >= 18 ? body.substring(16, 18) : "";
            try {
                return date.substring(0, 4) + "-" + date.substring(4, 6) + "-" + date.substring(6, 8)
                        + " " + time + (ms.isEmpty() ? "" : "." + ms);
            } catch (Exception ignored) {
            }
        }
        return name;
    }

    /** 懒加载 GL 纹理 (LRU 最多 8 张, 逐出释放). 必须在渲染线程调用 (UI 构建即在渲染线程). */
    public static ResourceLocation texture(String name) {
        ResourceLocation cached = texCache.get(name);
        if (cached != null) {
            return cached;
        }
        try {
            Path file = photosDir().resolve(name);
            if (Files.notExists(file)) {
                return missing();
            }
            // Mojmap 的 NativeImage.read 只有流/字节重载; DynamicTexture 会接管 image 的生命周期
            ResourceLocation rl;
            try (InputStream in = Files.newInputStream(file)) {
                NativeImage image = NativeImage.read(in);
                rl = ResourceLocation.fromNamespaceAndPath(
                        TerminalRegistry.MOD_ID, "photo/" + name.toLowerCase(Locale.ROOT));
                Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(image));
            }
            texCache.put(name, rl);
            while (texCache.size() > TEX_CACHE_MAX) {
                String eldest = texCache.entrySet().iterator().next().getKey();
                releaseTexture(eldest);
            }
            return rl;
        } catch (Exception e) {
            TerminalRegistry.LOGGER.warn("[CAMERA] 照片纹理加载失败: {}", name, e);
            return missing();
        }
    }

    public static void releaseTexture(String name) {
        ResourceLocation rl = texCache.remove(name);
        if (rl != null) {
            Minecraft.getInstance().getTextureManager().release(rl);
        }
    }

    public static void releaseThumbnail(String name) {
        ResourceLocation rl = thumbCache.remove(name);
        if (rl != null) {
            Minecraft.getInstance().getTextureManager().release(rl);
        }
    }

    /**
     * 缩略图纹理 (相册网格专用): PNG 解码后按比例缩小到 ≤192x108 再注册 GL 纹理.
     * 解码一次后常驻 LRU 缓存, 翻页/重建 UI 不再重复解码全尺寸 PNG — 相册卡顿的根因.
     * 必须在渲染线程调用 (UI 构建即在渲染线程).
     */
    public static ResourceLocation thumbnail(String name) {
        ResourceLocation cached = thumbCache.get(name);
        if (cached != null) {
            return cached;
        }
        try {
            Path file = photosDir().resolve(name);
            if (Files.notExists(file)) {
                return missing();
            }
            NativeImage src;
            try (InputStream in = Files.newInputStream(file)) {
                src = NativeImage.read(in);
            }
            NativeImage thumb = downscale(src, THUMB_MAX_W, THUMB_MAX_H);
            if (thumb != src) {
                src.close();
            }
            ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(
                    TerminalRegistry.MOD_ID, "photo_thumb/" + name.toLowerCase(Locale.ROOT));
            Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(thumb));
            thumbCache.put(name, rl);
            while (thumbCache.size() > THUMB_CACHE_MAX) {
                String eldest = thumbCache.entrySet().iterator().next().getKey();
                releaseThumbnail(eldest);
            }
            return rl;
        } catch (Exception e) {
            TerminalRegistry.LOGGER.warn("[CAMERA] 照片缩略图加载失败: {}", name, e);
            return missing();
        }
    }

    /** 最近邻采样缩放; 原图不超过上限时原样返回 (调用方据此跳过 close). */
    private static NativeImage downscale(NativeImage src, int maxW, int maxH) {
        int sw = src.getWidth(), sh = src.getHeight();
        if (sw <= maxW && sh <= maxH) {
            return src;
        }
        double scale = Math.min((double) maxW / sw, (double) maxH / sh);
        int dw = Math.max(1, (int) Math.round(sw * scale));
        int dh = Math.max(1, (int) Math.round(sh * scale));
        NativeImage dst = new NativeImage(src.format(), dw, dh, false);
        for (int y = 0; y < dh; y++) {
            int sy = Math.min(sh - 1, (int) (y / scale));
            for (int x = 0; x < dw; x++) {
                int sx = Math.min(sw - 1, (int) (x / scale));
                dst.setPixelRGBA(x, y, src.getPixelRGBA(sx, sy));
            }
        }
        return dst;
    }

    private static ResourceLocation missing() {
        return ResourceLocation.withDefaultNamespace("textures/misc/missingno.png");
    }
}
