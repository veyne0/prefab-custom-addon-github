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
    private static final int TEX_CACHE_MAX = 8;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmssSSS");

    /** name(file name) → registered texture RL, accessOrder 实现真正的 LRU. */
    private static final LinkedHashMap<String, ResourceLocation> texCache = new LinkedHashMap<>(16, 0.75f, true);

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

    private static ResourceLocation missing() {
        return ResourceLocation.withDefaultNamespace("textures/misc/missingno.png");
    }
}
