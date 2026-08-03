package com.prefab.addon.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 原版 GUI 缩略图渲染器.
 * <p>从 <code>prefab:textures/gui/&lt;name&gt;.png</code> 异步加载原版提供的 2D 缩略图,
 * 缓存到 DynamicTexture, 供卡片和详情界面直接 blit.</p>
 *
 * <h3>设计</h3>
 * <ul>
 *   <li>cache: key = 源资源 toString ("prefab:textures/gui/x.png"), value = 生成的 ResourceLocation</li>
 *   <li>sizes: key = 生成的 ResourceLocation, value = [w, h] (DynamicTexture 没有通用 getWidth)</li>
 *   <li>失败: cache = MISSING sentinel, sizes = [0, 0]</li>
 *   <li>从 DynamicTexture 拿尺寸: 用 tex.getPixels().getWidth() / getHeight() (1.21.1 NativeImage 接口)</li>
 * </ul>
 */
public final class GuiThumbRenderer {

    private static final Map<String, ResourceLocation> cache = new HashMap<>();
    private static final Map<ResourceLocation, int[]> sizes = new HashMap<>();
    private static final Set<String> loading = new HashSet<>();
    /** 缺图 sentinel. 任何"加载失败"或"资源不存在"都用这个. */
    private static final ResourceLocation MISSING = ResourceLocation.fromNamespaceAndPath("prefab_addon", "thumb_missing");

    private GuiThumbRenderer() {}

    /**
     * 异步加载缩略图 (启动一次后, 多次调用拿同一个 ResourceLocation).
     */
    public static void preload(ResourceLocation loc) {
        if (loc == null) return;
        String key = loc.toString();
        if (cache.containsKey(key) || loading.contains(key)) return;
        loading.add(key);

        CompletableFuture.runAsync(() -> {
            BufferedImage img = null;
            try (InputStream is = Minecraft.getInstance()
                    .getResourceManager()
                    .getResource(loc)
                    .orElseThrow(() -> new java.io.IOException("no resource " + loc))
                    .open()) {
                img = ImageIO.read(is);
            } catch (Exception e) {
                // 资源不存在/IO 失败 -> MISSING
                img = null;
            }
            final BufferedImage finalImg = img;
            Minecraft.getInstance().execute(() -> {
                if (finalImg == null || finalImg.getWidth() <= 0 || finalImg.getHeight() <= 0) {
                    cache.put(key, MISSING);
                    sizes.put(MISSING, new int[]{0, 0});
                    loading.remove(key);
                    return;
                }
                try {
                    int w = finalImg.getWidth(), h = finalImg.getHeight();
                    DynamicTexture tex = new DynamicTexture(w, h, false);
                    tex.setFilter(false, false);
                    var pixels = tex.getPixels();
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            int argb = finalImg.getRGB(x, y);
                            int abgr = ((argb & 0xFF00FF00)
                                | ((argb & 0x00FF0000) >> 16)
                                | ((argb & 0x000000FF) << 16));
                            pixels.setPixelRGBA(x, y, abgr);
                        }
                    }
                    tex.upload();
                    String texKey = "prefab_addon_thumb_"
                        + key.replace(':', '_').replace('/', '_').replace('.', '_');
                    ResourceLocation out = Minecraft.getInstance().getTextureManager()
                        .register(texKey, tex);
                    cache.put(key, out);
                    sizes.put(out, new int[]{w, h});
                } catch (Exception e) {
                    cache.put(key, MISSING);
                    sizes.put(MISSING, new int[]{0, 0});
                } finally {
                    loading.remove(key);
                }
            });
        });
    }

    /** 获取已缓存的缩略图. 没加载完成返回 null. */
    public static ResourceLocation get(ResourceLocation loc) {
        if (loc == null) return null;
        return cache.get(loc.toString());
    }

    /**
     * 绘制缩略图到 GUI, 自动 fit 到 (x,y,w,h), 保持原图比例.
     */
    public static void drawThumb(GuiGraphics g, int x, int y, int w, int h, ResourceLocation loc) {
        if (loc == null) {
            drawPlaceholder(g, x, y, w, h, "无缩略图");
            return;
        }
        ResourceLocation tex = get(loc);
        if (tex == null) {
            preload(loc);
            drawPlaceholder(g, x, y, w, h, "加载中...");
            return;
        }
        if (tex == MISSING) {
            drawPlaceholder(g, x, y, w, h, "无图");
            return;
        }
        int[] wh = sizes.get(tex);
        if (wh == null) {
            drawPlaceholder(g, x, y, w, h, "?");
            return;
        }
        int imgW = wh[0], imgH = wh[1];
        if (imgW <= 0 || imgH <= 0) {
            drawPlaceholder(g, x, y, w, h, "?");
            return;
        }
        // 保持比例居中
        float scale = Math.min((float) w / imgW, (float) h / imgH);
        int drawW = (int) (imgW * scale);
        int drawH = (int) (imgH * scale);
        int drawX = x + (w - drawW) / 2;
        int drawY = y + (h - drawH) / 2;

        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, tex);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        // 1.21.1 blit 11 参数: tex, blitX, blitY, blitW, blitH, uOff, vOff, uW, vH, texW, texH
        g.blit(tex, drawX, drawY, drawW, drawH, 0, 0, imgW, imgH, imgW, imgH);
        RenderSystem.disableBlend();
    }

    private static void drawPlaceholder(GuiGraphics g, int x, int y, int w, int h, String label) {
        int bg = 0xFF333344;
        g.fill(x, y, x + w, y + h, bg);
        g.fill(x, y, x + w, y + 1, 0xFF555566);
        g.fill(x, y + h - 1, x + w, y + h, 0xFF222233);
        g.fill(x, y, x + 1, y + h, 0xFF555566);
        g.fill(x + w - 1, y, x + w, y + h, 0xFF555566);
        var font = Minecraft.getInstance().font;
        g.drawCenteredString(font, label, x + w / 2, y + h / 2 - 4, 0xFFCCCCCC);
    }
}
