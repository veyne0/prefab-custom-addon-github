package com.prefab.addon.client.gui;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.work.ModrinthClient;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 依赖搜索弹窗 (Modrinth): 玩家在建筑详情页点缺失依赖旁的 [🔍] 进入,
 * 展示该 modid 在 Modrinth 上的候选模组 (图标/名称/简介/下载量), 可打开下载页.
 *
 * <p>网络失败 (如国内连不上 api.modrinth.com) 时状态栏提示, 不影响其他功能.</p>
 */
public final class GuiModrinthSearch {

    /** 图标纹理缓存: slug -> 已注册的 DynamicTexture location */
    private static final Map<String, ResourceLocation> ICONS = new ConcurrentHashMap<>();
    private static final Set<String> ICON_INFLIGHT = ConcurrentHashMap.newKeySet();

    private static TextField searchBox;
    private static Label statusL1;
    private static Label statusL2;
    private static UIElement listContent;
    /** 仅在 Modrinth 无结果时显示, 用 Bing 搜索模组名 */
    private static Button webSearchBtn;
    private static String currentQuery = "";
    private static List<ModrinthClient.Project> results = List.of();
    /** 打开搜索弹窗前的界面, 关闭/ESC 时返回它 (建筑详情页) */
    private static net.minecraft.client.gui.screens.Screen parentScreen;

    private GuiModrinthSearch() {}

    /** 打开弹窗并自动搜索 depId (建筑详情页 [🔍] 入口) */
    public static void open(String depId) {
        currentQuery = depId == null ? "" : depId.trim();
        parentScreen = Minecraft.getInstance().screen;
        ModularUI ui = ModularUI.of(UI.of(buildRoot(),
                StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)));
        // onClose (关闭按钮/ESC) 都回到上一级界面
        ModularUIScreen screen = new ModularUIScreen(ui, Component.literal("Modrinth 依赖搜索")) {
            @Override
            public void onClose() {
                returnToParent();
            }
        };
        Minecraft.getInstance().setScreen(screen);
        if (!currentQuery.isEmpty()) {
            doSearch(currentQuery);
        } else {
            status("输入 modid 或关键词, 点 [搜索]");
        }
    }

    /** 返回上一级界面 (通常是建筑详情页) */
    private static void returnToParent() {
        net.minecraft.client.gui.screens.Screen parent = parentScreen;
        parentScreen = null;
        if (parent != null) {
            Minecraft.getInstance().setScreen(parent);
        } else {
            Minecraft.getInstance().setScreen(null);
        }
    }

    private static UIElement buildRoot() {
        UIElement root = new UIElement();
        root.layout(l -> l.width(320).height(250)
                .flexDirection(FlexDirection.COLUMN).paddingAll(6).gapAll(4));
        root.style(s -> s.background(Sprites.BORDER));
        root.setOverflowVisible(false);

        Label title = new Label();
        title.setText("§l依赖搜索 (Modrinth)", false);
        title.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textColor(0xFFFF55));
        title.layout(l -> l.widthPercent(100).height(12));
        root.addChild(title);

        // 搜索行: [输入框] [搜索]
        UIElement row = new UIElement();
        row.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(16).gapAll(3));
        root.addChild(row);
        searchBox = new TextField();
        searchBox.setAnyString();
        searchBox.setText(currentQuery);
        searchBox.textFieldStyle(s -> s.textColor(0xFFFFFF).textShadow(false));
        searchBox.layout(l -> l.flexGrow(1).height(16).minHeight(16));
        row.addChild(searchBox);
        Button go = new Button().setText("搜索", false);
        go.textStyle(s -> s.textColor(0x88FF88));
        go.layout(l -> l.width(48).height(16));
        go.setOnClick(e -> doSearch(searchBox.getText()));
        row.addChild(go);
        Button copy = new Button().setText("复制名字", false);
        copy.layout(l -> l.width(70).height(16));
        copy.setOnClick(e -> {
            String name = searchBox.getText() == null ? "" : searchBox.getText().trim();
            if (name.isEmpty()) {
                status("输入框是空的, 没什么可复制");
                return;
            }
            Minecraft.getInstance().keyboardHandler.setClipboard(name);
            status("✓ 已复制: " + name);
        });
        row.addChild(copy);

        // 结果列表 (滚动) - 固定高度, 防止结果多时把窗口撑高、把底部按钮挤出去
        ScrollerView scroller = new ScrollerView();
        scroller.scrollerStyle(s -> s.mode(ScrollerMode.VERTICAL));
        scroller.verticalScroller(v -> v.setScrollBarSize(6f));
        scroller.layout(l -> l.widthPercent(100).height(150));
        root.addChild(scroller);
        listContent = new UIElement();
        listContent.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100).paddingAll(2).gapAll(3));
        listContent.style(s -> s.background(Sprites.RECT_DARK));
        listContent.setOverflowVisible(false);
        scroller.addScrollViewChild(listContent);

        rebuildResults(listContent);

        // 状态提示 (列表下方, 最多两行; 长提示如 PCL 建议放第二行)
        UIElement statusBox = new UIElement();
        statusBox.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100).height(21).gapAll(1));
        root.addChild(statusBox);
        statusL1 = new Label();
        statusL1.setText("", false);
        statusL1.textStyle(t -> t.textColor(0xAAAAAA));
        statusL1.layout(l -> l.widthPercent(100).height(10));
        statusBox.addChild(statusL1);
        statusL2 = new Label();
        statusL2.setText("", false);
        statusL2.textStyle(t -> t.textColor(0xAAAAAA));
        statusL2.layout(l -> l.widthPercent(100).height(10));
        statusBox.addChild(statusL2);

        // 底部: [打开Modrinth界面] [浏览器搜索(仅无结果时显示)] [返回]
        UIElement bottom = new UIElement();
        bottom.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(16).gapAll(3));
        root.addChild(bottom);
        Button web = new Button().setText("打开Modrinth界面", false);
        web.layout(l -> l.widthPercent(40).height(16));
        web.setOnClick(e -> openWebSearch(searchBox.getText()));
        bottom.addChild(web);
        webSearchBtn = new Button().setText("浏览器搜索", false);
        webSearchBtn.layout(l -> l.widthPercent(30).height(16));
        webSearchBtn.setOnClick(e -> openBrowserSearch(searchBox.getText()));
        webSearchBtn.setVisible(false);
        bottom.addChild(webSearchBtn);
        Button close = new Button().setText("返回", false);
        close.layout(l -> l.widthPercent(30).height(16));
        close.setOnClick(e -> returnToParent());
        bottom.addChild(close);

        return root;
    }

    /** 发起搜索 (异步, 3 秒超时; 回来后必须还在本界面且查询词没变才刷新) */
    private static void doSearch(String query) {
        String q = query == null ? "" : query.trim();
        currentQuery = q;
        results = List.of();
        showBrowserSearch(false);
        if (listContent != null) {
            listContent.clearAllChildren();
        }
        if (q.isEmpty()) {
            status("输入 modid 或关键词");
            return;
        }
        if (ModrinthClient.isBusy(q)) {
            status("查询中... (Modrinth)");
            return;
        }
        List<ModrinthClient.Project> cached = ModrinthClient.cached(q);
        if (!cached.isEmpty()) {
            results = cached;
            status("✓ 找到 " + cached.size() + " 个结果 (缓存)");
            if (listContent != null) rebuildResults(listContent);
            return;
        }
        status("查询中... (Modrinth, 约 3 秒超时)");
        ModrinthClient.search(q).thenAccept(list -> Minecraft.getInstance().execute(() -> {
            // 界面已关 / 换了搜索词: 丢弃
            if (Minecraft.getInstance().screen == null || !q.equals(currentQuery)) return;
            if (listContent == null) return;
            results = list == null ? List.of() : list;
            if (results.isEmpty()) {
                status("✗ Modrinth 未收录, 可能只在 CurseForge\n可在 PCL 或浏览器里搜索: " + q);
                showBrowserSearch(true);
            } else {
                status("✓ 找到 " + results.size() + " 个结果");
                showBrowserSearch(false);
            }
            rebuildResults(listContent);
        }));
    }

    /** 「浏览器搜索」按钮显隐 (仅 Modrinth 无结果时出现) */
    private static void showBrowserSearch(boolean show) {
        if (webSearchBtn != null) {
            webSearchBtn.setVisible(show);
        }
    }

    private static void rebuildResults(UIElement col) {
        col.clearAllChildren();
        if (results.isEmpty()) {
            Label empty = new Label();
            empty.setText("无结果", false);
            empty.textStyle(t -> t.textColor(0x888888));
            empty.layout(l -> l.widthPercent(100).height(12));
            col.addChild(empty);
            return;
        }
        for (ModrinthClient.Project p : results) {
            col.addChild(resultRow(p));
        }
        // 触发图标异步加载
        for (ModrinthClient.Project p : results) {
            loadIcon(p);
        }
    }

    /** 结果行: [图标 20x20] [名称 + 简介] [下载量 + 打开页面] */
    private static UIElement resultRow(ModrinthClient.Project p) {
        UIElement card = new UIElement();
        card.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(24)
                .alignItems(AlignItems.CENTER).gapAll(4));
        card.style(s -> s.background(Sprites.RECT_DARK));
        card.setOverflowVisible(false);

        UIElement icon = new UIElement();
        icon.layout(l -> l.width(20).height(20));
        ResourceLocation tex = ICONS.get(p.slug);
        icon.style(s -> s.backgroundTexture(tex != null
                ? SpriteTexture.of(tex)
                : Sprites.RECT_DARK));
        card.addChild(icon);

        UIElement info = new UIElement();
        info.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).minWidth(0).gapAll(1));
        card.addChild(info);
        Label name = new Label();
        name.setText(truncateW(p.title, 26), false);
        name.textStyle(t -> t.textColor(0xFFFFFF));
        name.layout(l -> l.widthPercent(100).height(10));
        info.addChild(name);
        Label desc = new Label();
        desc.setText(p.description.isEmpty() ? "(无简介)" : truncateW(p.description, 44), false);
        desc.textStyle(t -> t.textColor(0x888888));
        desc.layout(l -> l.widthPercent(100).height(10));
        info.addChild(desc);

        Label dl = new Label();
        dl.setText(humanCount(p.downloads), false);
        dl.textStyle(t -> t.textColor(0x88BBFF));
        dl.layout(l -> l.width(44).height(12));
        card.addChild(dl);

        Button page = new Button().setText("页面", false);
        page.textStyle(s -> s.textColor(0x88FF88));
        page.layout(l -> l.width(34).height(16));
        page.setOnClick(e -> openUrl(p.pageUrl()));
        card.addChild(page);
        return card;
    }

    /** 图标异步加载: 字节 → BufferedImage → DynamicTexture (主线程注册, 同终端缩略图方案) */
    private static void loadIcon(ModrinthClient.Project p) {
        if (p.iconUrl == null || p.iconUrl.isEmpty()
                || ICONS.containsKey(p.slug) || !ICON_INFLIGHT.add(p.slug)) {
            return;
        }
        ModrinthClient.fetchIcon(p.iconUrl).thenAccept(data -> Minecraft.getInstance().execute(() -> {
            ICON_INFLIGHT.remove(p.slug);
            if (data == null || data.length == 0 || Minecraft.getInstance().screen == null) return;
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
                if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) return;
                net.minecraft.client.renderer.texture.DynamicTexture tex =
                        new net.minecraft.client.renderer.texture.DynamicTexture(
                                img.getWidth(), img.getHeight(), false);
                tex.setFilter(false, false);
                com.mojang.blaze3d.platform.NativeImage pixels = tex.getPixels();
                for (int y = 0; y < img.getHeight(); y++) {
                    for (int x = 0; x < img.getWidth(); x++) {
                        int argb = img.getRGB(x, y);
                        pixels.setPixelRGBA(x, y,
                                argb & 0xFF00FF00 | (argb & 0xFF0000) >> 16 | (argb & 0xFF) << 16);
                    }
                }
                tex.upload();
                String safe = p.slug.toLowerCase().replaceAll("[^a-z0-9_.-]", "_");
                ICONS.put(p.slug, Minecraft.getInstance().getTextureManager()
                        .register("prefab_modrinth_" + safe, tex));
                if (listContent != null) rebuildResults(listContent);
            } catch (Exception ex) {
                PrefabCustomAddon.LOGGER.warn("[MODRINTH] icon upload failed for {}: {}", p.slug, ex.getMessage());
            }
        }));
    }

    private static void openWebSearch(String query) {
        String q = query == null ? "" : query.trim();
        String url = "https://modrinth.com/mods" + (q.isEmpty() ? "" : "?q=" + q);
        openUrl(url);
    }

    /** 浏览器搜索 (Bing, 国内可直连): "xxx minecraft mod" */
    private static void openBrowserSearch(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            status("输入框是空的");
            return;
        }
        try {
            openUrl("https://www.bing.com/search?q="
                    + java.net.URLEncoder.encode(q + " minecraft mod", java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("[MODRINTH] bing url encode failed: {}", q);
        }
    }

    /** 系统浏览器打开 (openUri + Desktop 兜底, 与其他界面同款) */
    private static void openUrl(String url) {
        try {
            net.minecraft.Util.getPlatform().openUri(java.net.URI.create(url));
        } catch (Exception e1) {
            try {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            } catch (Exception e2) {
                PrefabCustomAddon.LOGGER.warn("[MODRINTH] openUri failed: {}", url);
                status("✗ 无法打开浏览器");
            }
        }
    }

    private static void status(String text) {
        if (text == null) text = "";
        // 按换行拆两行; 颜色由首字符决定 (✓ 绿 / ✗ 红 / 其他灰)
        int color = text.startsWith("✓") ? 0x55FF55
                : text.startsWith("✗") ? 0xFF5555 : 0xAAAAAA;
        String l1 = text;
        String l2 = "";
        int nl = text.indexOf('\n');
        if (nl >= 0) {
            l1 = text.substring(0, nl);
            l2 = text.substring(nl + 1);
        }
        if (statusL1 != null) {
            statusL1.setText(l1, false);
            statusL1.textStyle(t -> t.textColor(color));
        }
        if (statusL2 != null) {
            statusL2.setText(l2, false);
            statusL2.textStyle(t -> t.textColor(color));
        }
    }

    private static String truncateW(String s, int chars) {
        if (s == null) return "";
        return s.length() <= chars ? s : s.substring(0, Math.max(1, chars - 1)) + "…";
    }

    private static String humanCount(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format("%.1fk", n / 1_000.0);
        return String.valueOf(n);
    }

    private static String tr(String key) {
        return PrefabCustomAddon.tr(key);
    }
}
