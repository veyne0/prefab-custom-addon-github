package com.prefab.addon.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.ConstructionInfo;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 建筑缩略图自动生成 + 磁盘缓存.
 *
 * <h2>工作流</h2>
 * <ol>
 *   <li>玩家打开建筑详情, 3D 预览完成渲染 → 主线程调用 {@link #captureMainThread},
 *       把当前 3D 视图截图存到 {@code .minecraft/prefab-custom-addon/thumbnails/&lt;sha1&gt;.png}</li>
 *   <li>玩家在浏览器看到没有 PNG 的建筑卡片:
 *       <ul>
 *         <li>有缓存 → 直接加载显示 (毫秒级)</li>
 *         <li>无缓存 → 显示占位, 等待玩家打开 detail 后再生成</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <h2>缓存 key</h2>
 * SHA1( packName | constructionId | nbtData hash )
 * <p>建筑文件改了之后, 缓存自动失效 (不同 key).</p>
 *
 * <h2>为什么不用后台线程渲染</h2>
 * Minecraft 的 GL context 是 per-thread 的, 离屏 3D 渲染需要绑 framebuffer 切换状态,
 * 跟主线程渲染冲突. 简单方案: 直接截图已经渲染好的 3D 预览 (详情页打开时),
 * 既复用现有的渲染流程, 又不增加额外 CPU/GPU 负担.
 */
public final class ThumbnailCache {

    private static final int THUMB_W = 96;   // 略大于 60, 给高 DPI 留余地
    private static final int THUMB_H = 96;

    /** 上次新增/更新的 fingerprint, GUI tick 时检测, 有则重置自己的 texture cache */
    private static final ConcurrentHashMap<String, Boolean> COMPLETED = new ConcurrentHashMap<>();

    private ThumbnailCache() {}

    /** 缓存根目录: .minecraft/prefab-custom-addon/thumbnails/ */
    public static Path getCacheDir() {
        Path mc = Paths.get(System.getProperty("user.dir"));
        Path config = mc.resolve("config");
        if (!Files.exists(config)) {
            Path cur = mc;
            for (int i = 0; i < 5; i++) {
                cur = cur.getParent();
                if (cur == null) break;
                if (Files.exists(cur.resolve("config"))) {
                    mc = cur;
                    break;
                }
            }
        }
        return mc.resolve("prefab-custom-addon").resolve("thumbnails");
    }

    /**
     * 计算 construction 的缓存指纹:
     * packName | constructionId | size | nbtData hash
     * <p>建筑文件改了之后, 指纹会变, 自动失效旧缓存.</p>
     */
    public static String fingerprint(ConstructionInfo c) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            String pkg = c.getPack() == null ? "" : c.getPack().getPackageName();
            md.update(pkg.getBytes());
            md.update((byte) '|');
            md.update(c.getId().getBytes());
            md.update((byte) '|');
            md.update((c.getSize() == null ? "" : c.getSize()).getBytes());
            md.update((byte) '|');
            if (c.getNbtData() != null) {
                md.update(c.getNbtData());
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return Integer.toHexString((c.getId() + (c.getSize() == null ? "" : c.getSize())).hashCode());
        }
    }

    public static Path getCacheFile(ConstructionInfo c) {
        return getCacheDir().resolve(fingerprint(c) + ".png");
    }

    /** 缓存是否存在 */
    public static boolean hasCached(ConstructionInfo c) {
        if (c == null) return false;
        return Files.isRegularFile(getCacheFile(c));
    }

    /** 读取缓存 (字节数组). 失败返回 null. */
    public static byte[] read(ConstructionInfo c) {
        if (c == null) return null;
        Path p = getCacheFile(c);
        if (!Files.isRegularFile(p)) return null;
        try {
            return Files.readAllBytes(p);
        } catch (Exception e) {
            return null;
        }
    }

    /** tick 里调用: 有没有新完成的缓存. 有就重置 GUI texture cache 重新加载. */
    public static boolean pollCompleted() {
        if (COMPLETED.isEmpty()) return false;
        COMPLETED.clear();
        return true;
    }

    /** 强制通知: 用于外部直接写完 PNG 后通知 GUI 重读 */
    public static void notifyCompleted(String fingerprint) {
        COMPLETED.put(fingerprint, Boolean.TRUE);
    }

    /**
     * 主线程: 截图当前 framebuffer 的 thumbRect 区域, 保存为 PNG 缓存.
     *
     * <p>调用前应确保 OpenGL 状态机被切到 thumbRect 大小, 并已渲染好 3D 视图.
     * 这里只负责 readPixels + 写盘.</p>
     *
     * @param c 要保存的建筑
     * @return true 成功保存
     */
    public static boolean captureCurrentFrame(ConstructionInfo c) {
        if (c == null) return false;
        if (c.hasPreviewImage()) return false;  // 已有原图, 不重复
        if (hasCached(c)) return false;  // 已有缓存
        try {
            int width = THUMB_W, height = THUMB_H;
            ByteBuffer buf = BufferUtils.createByteBuffer(width * height * 4);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
            // OpenGL 像素 Y 是从下往上, flip
            NativeImage image = new NativeImage(width, height, false);
            byte[] row = new byte[width * 4];
            for (int y = 0; y < height; y++) {
                buf.position((height - 1 - y) * width * 4);
                buf.get(row);
                for (int x = 0; x < width; x++) {
                    int r = row[x * 4] & 0xFF;
                    int g = row[x * 4 + 1] & 0xFF;
                    int b = row[x * 4 + 2] & 0xFF;
                    int a = row[x * 4 + 3] & 0xFF;
                    int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                    image.setPixelRGBA(x, y, abgr);
                }
            }
            Path dir = getCacheDir();
            Files.createDirectories(dir);
            Path file = dir.resolve(fingerprint(c) + ".png");
            image.writeToFile(file);
            image.close();
            COMPLETED.put(fingerprint(c), Boolean.TRUE);
            PrefabCustomAddon.LOGGER.info("[THUMB] Saved thumbnail: {} ({} bytes)",
                file.getFileName(), Files.size(file));
            return true;
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[THUMB] capture failed: {}", t.getMessage());
            return false;
        }
    }
}
