package com.prefab.addon.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.ConstructionInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 建筑缩略图自动生成 + 磁盘缓存.
 *
 * <h2>工作流</h2>
 * <ol>
 *   <li>玩家打开建筑详情, 3D 预览完成渲染 → 主线程调用 {@link #captureRegion},
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

    /** 上次新增/更新的 fingerprint, GUI tick 时检测, 有则重置自己的 texture cache */
    private static final ConcurrentHashMap<String, Boolean> COMPLETED = new ConcurrentHashMap<>();

    /** GUI 用纹理缓存: fingerprint → 条目 (rl=null 表示已确认无图) */
    private static final ConcurrentHashMap<String, ThumbEntry> TEXTURES = new ConcurrentHashMap<>();

    /** 注册名生成计数: (重)生成缩略图后注册全新纹理, 避免同名注册命中 TextureManager 里的旧纹理 */
    private static final AtomicInteger TEX_GEN = new AtomicInteger();

    /** 缓存条目: 纹理 + 加载时缩略图文件的 mtime (变化 = 生成/重新生成过, 触发重载) */
    private record ThumbEntry(ResourceLocation rl, long mtime) {}

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

    /**
     * 缩略图缓存文件的 lastModified (无文件返回 0).
     * <p>GUI 侧用它做无损失效检测: 生成/重新生成缩略图 → mtime 变化 → 重载纹理.
     * 不依赖 COMPLETED 完成标记 (那个会被浏览器 tick 全量消费, 跨 GUI 会互相抢).</p>
     */
    public static long lastModified(ConstructionInfo c) {
        if (c == null) return 0L;
        try {
            return Files.getLastModifiedTime(getCacheFile(c)).toMillis();
        } catch (Exception e) {
            return 0L;
        }
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
     * 主线程: 获取用于 GUI 显示的缩略图纹理 (载入后缓存).
     * <p>优先级: 磁盘缓存缩略图 → pack 自带预览图. 无图返回 null.</p>
     * <p>失效检测: 对比缩略图文件 mtime, 生成/重新生成后自动重载, 无需跨 GUI 的完成标记.</p>
     */
    public static ResourceLocation getOrLoadTexture(ConstructionInfo c) {
        if (c == null) return null;
        String key = fingerprint(c);
        long mtime = lastModified(c);
        ThumbEntry e = TEXTURES.get(key);
        if (e != null && e.mtime() == mtime) {
            return e.rl();
        }

        ResourceLocation loc = null;
        if (mtime > 0) {
            byte[] png = read(c);
            if (png != null) {
                loc = uploadPng(png, "prefab_thumb_" + key + "_" + TEX_GEN.incrementAndGet());
            }
        }
        if (loc == null) {
            byte[] packImg = c.getPreviewBytes();
            if (packImg != null) {
                loc = uploadPng(packImg, "prefab_packimg_" + key + "_" + TEX_GEN.incrementAndGet());
            }
        }
        TEXTURES.put(key, new ThumbEntry(loc, mtime));
        return loc;
    }

    /**
     * PNG 字节 → 注册为动态纹理 (失败返回 null).
     * <p>非正方形图中心裁正方形, 超过 256px 缩到 256, 处理方式与浏览器卡片一致.</p>
     */
    private static ResourceLocation uploadPng(byte[] png, String regName) {
        try (var is = new ByteArrayInputStream(png)) {
            BufferedImage img = ImageIO.read(is);
            if (img == null) return null;
            int w = img.getWidth();
            int h = img.getHeight();
            if (w <= 0 || h <= 0) return null;
            int side = Math.min(w, h);
            if (side < w || side < h) {
                BufferedImage cropped = img.getSubimage((w - side) / 2, (h - side) / 2, side, side);
                BufferedImage copy = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g0 = copy.createGraphics();
                g0.drawImage(cropped, 0, 0, null);
                g0.dispose();
                img = copy;
                w = h = side;
            }
            if (w > 256) {
                BufferedImage scaled = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(img, 0, 0, 256, 256, null);
                g.dispose();
                img = scaled;
                w = h = 256;
            }
            var tex = new DynamicTexture(w, h, false);
            tex.setFilter(true, true);
            var pixels = tex.getPixels();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = img.getRGB(x, y);
                    int abgr = (argb & 0xFF00FF00) | ((argb & 0xFF0000) >> 16) | ((argb & 0xFF) << 16);
                    pixels.setPixelRGBA(x, y, abgr);
                }
            }
            tex.upload();
            return Minecraft.getInstance().getTextureManager().register(regName, tex);
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("[THUMB] texture upload failed ({}): {}", regName, e.getMessage());
            return null;
        }
    }

    /**
     * 主线程: 从 mainRenderTarget 截取指定 GUI 区域, box-filter 面积平均缩放到 targetSize 方形,
     * 写入 PNG 缓存并通知 GUI 重载.
     *
     * <p>必须显式绑定 {@code GL_READ_FRAMEBUFFER} 到 mainRenderTarget 再读: vanilla GUI
     * (含 3D 预览合成结果) 画在 mainRenderTarget 里, 帧间内容持久保留 (帧首才 clear);
     * 若直接读"当前绑定"的 framebuffer, 点击回调发生在两帧之间, 绑定的是窗口后缓冲,
     * swap 后内容未定义 (驱动上常为纯黑) —— 这就是缩略图全黑的根因.</p>
     *
     * <p>坐标换算: GUI 单位 × (实际像素 / GUI 缩放后像素); GL 是底部原点, Y 需翻转.</p>
     *
     * @param guiX       区域左上角 X (GUI 单位, 顶部为 0)
     * @param guiY       区域左上角 Y (GUI 单位)
     * @param guiW       区域宽 (GUI 单位)
     * @param guiH       区域高 (GUI 单位)
     * @param c          要保存的建筑
     * @param targetSize 目标边长 (正方形, 如 256)
     * @return true 成功保存
     */
    public static boolean captureRegion(int guiX, int guiY, int guiW, int guiH,
                                        ConstructionInfo c, int targetSize) {
        if (c == null || guiW <= 0 || guiH <= 0 || targetSize <= 0) return false;
        try {
            var window = Minecraft.getInstance().getWindow();
            var main = Minecraft.getInstance().getMainRenderTarget();
            int guiScaledW = window.getGuiScaledWidth();
            int guiScaledH = window.getGuiScaledHeight();
            if (guiScaledW <= 0 || guiScaledH <= 0 || main.frameBufferId <= 0) return false;
            double scaleX = (double) main.viewWidth / guiScaledW;
            double scaleY = (double) main.viewHeight / guiScaledH;
            int px = (int) (guiX * scaleX);
            int pyFb = (int) ((guiScaledH - guiY - guiH) * scaleY);  // GL 底部原点, 翻转 Y
            int pw = (int) (guiW * scaleX);
            int ph = (int) (guiH * scaleY);
            if (pw <= 0 || ph <= 0 || px < 0 || pyFb < 0
                    || px + pw > main.viewWidth || pyFb + ph > main.viewHeight) return false;

            ByteBuffer buf = BufferUtils.createByteBuffer(pw * ph * 4);
            // 显式从 mainRenderTarget 读 (vanilla GUI + 3D 预览画在这里, 内容帧间持久保留);
            // 点击回调发生在两帧之间, 当前绑定的可能是窗口后缓冲 (swap 后内容未定义, 常为纯黑)
            int prevReadFb = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
            GL11.glReadPixels(px, pyFb, pw, ph, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFb);
            return scaleAndSave(buf, pw, ph, c, targetSize);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[THUMB] capture failed: {}", t.getMessage());
            return false;
        }
    }

    /**
     * RGBA 像素缓冲 → alpha 不透明化 (FBO 的 alpha 通道不可靠, alpha=0 的 PNG
     * 在图片查看器里显示成全黑) → box-filter 面积平均缩放 → 写 PNG 缓存 + 通知 GUI 重载.
     * GL 像素 Y 从下往上, 翻转到顶部为 0, 顺便 RGBA → NativeImage 的 ABGR 打包.
     */
    private static boolean scaleAndSave(ByteBuffer buf, int pw, int ph,
                                        ConstructionInfo c, int targetSize) {
        try {
            NativeImage full = new NativeImage(pw, ph, false);
            byte[] row = new byte[pw * 4];
            for (int y = 0; y < ph; y++) {
                buf.position((ph - 1 - y) * pw * 4);
                buf.get(row);
                for (int x = 0; x < pw; x++) {
                    int r = row[x * 4] & 0xFF;
                    int g = row[x * 4 + 1] & 0xFF;
                    int b = row[x * 4 + 2] & 0xFF;
                    full.setPixelRGBA(x, y, (0xFF << 24) | (b << 16) | (g << 8) | r);  // 强制不透明
                }
            }

            // box-filter 面积平均缩放 (每目标像素取源区域平均色, 比最近邻点抽样清晰得多)
            NativeImage thumb = new NativeImage(targetSize, targetSize, false);
            for (int ty = 0; ty < targetSize; ty++) {
                int sy0 = (int) ((long) ty * ph / targetSize);
                int sy1 = Math.max(sy0 + 1, (int) ((long) (ty + 1) * ph / targetSize));
                for (int tx = 0; tx < targetSize; tx++) {
                    int sx0 = (int) ((long) tx * pw / targetSize);
                    int sx1 = Math.max(sx0 + 1, (int) ((long) (tx + 1) * pw / targetSize));
                    long rSum = 0, gSum = 0, bSum = 0;
                    for (int sy = sy0; sy < sy1; sy++) {
                        for (int sx = sx0; sx < sx1; sx++) {
                            int p = full.getPixelRGBA(sx, sy);
                            rSum += p & 0xFF;
                            gSum += (p >>> 8) & 0xFF;
                            bSum += (p >>> 16) & 0xFF;
                        }
                    }
                    int n = (sx1 - sx0) * (sy1 - sy0);
                    int rAvg = (int) (rSum / n);
                    int gAvg = (int) (gSum / n);
                    int bAvg = (int) (bSum / n);
                    thumb.setPixelRGBA(tx, ty, (0xFF << 24) | (bAvg << 16) | (gAvg << 8) | rAvg);
                }
            }
            full.close();

            Path dir = getCacheDir();
            Files.createDirectories(dir);
            Path file = dir.resolve(fingerprint(c) + ".png");
            thumb.writeToFile(file);
            thumb.close();
            COMPLETED.put(fingerprint(c), Boolean.TRUE);
            PrefabCustomAddon.LOGGER.info("[THUMB] Saved thumbnail: {} ({}x{} -> {}px, {} bytes)",
                file.getFileName(), pw, ph, targetSize, Files.size(file));
            return true;
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[THUMB] save failed: {}", t.getMessage());
            return false;
        }
    }
}
