package com.prefab.addon.client.gui;

import com.prefab.addon.multiblock.MultiblockCatalog;
import com.prefab.addon.multiblock.MultiblockShapeData;
import com.prefab.addon.work.ChallengeSessionManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 多方块结构浏览器 — 独立界面 (不再内嵌在 GuiExtensionPackBrowser 里).
 *
 * <p>入口: 多方块结构蓝图物品右键 ({@code MultiblockBlueprintClientHandler} 拦截).
 * 顶部标签页按来源划分: 全部 / 格雷科技 / 通用机械 / MBD2 自定义 (动态, 只列已加载的来源).
 * 卡片网格 3x2 + 底部分页, 点卡片进 {@link GuiMultiblockDetail} 预览/建造.</p>
 *
 * <p>窗口风格沿用主浏览器: 居中灰box + 边框 + 深色卡片, 颜色值与主浏览器保持一致.</p>
 */
public class GuiMultiblockBrowser extends Screen {
    private static final int PANEL_W = 320;
    private static final int PANEL_H = 240;
    private static final int CARD_W = 92;
    private static final int CARD_H = 80;
    private static final int CARD_GAP = 6;
    private static final int CARD_COLS = 3;
    private static final int PAGE_SIZE = CARD_COLS * 2;

    /** null = 全部来源; "links" = 百科链接标签页.
     *  static: 跨界面实例保留 — 从详情页返回 / 关闭重开都恢复上次的标签页和页数. */
    private static String sourceFilter = null;
    private static int page = 0;
    private static final String TAB_LINKS = "links";
    private final Map<String, int[]> sourceTabRects = new LinkedHashMap<String, int[]>();
    private final Map<String, int[]> cardRects = new LinkedHashMap<String, int[]>();
    private final Map<String, int[]> linkRects = new LinkedHashMap<String, int[]>();
    private int[] pagPrevRect = null;
    private int[] pagNextRect = null;

    public GuiMultiblockBrowser() {
        super(Component.literal("多方块结构浏览器"));
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new GuiMultiblockBrowser());
    }

    private int[] computePanelPos() {
        int x = this.width / 2 - PANEL_W / 2;
        int y = this.height / 2 - PANEL_H / 2;
        if (x < 4) {
            x = 4;
        }
        if (y < 4) {
            y = 4;
        }
        if (x + PANEL_W > this.width - 4) {
            x = Math.max(4, this.width - PANEL_W - 4);
        }
        if (y + PANEL_H > this.height - 4) {
            y = Math.max(4, this.height - PANEL_H - 4);
        }
        return new int[]{x, y};
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        super.render(guiGraphics, mouseX, mouseY, partialTicks);
        int[] pos = this.computePanelPos();
        int px = pos[0];
        int py = pos[1];
        // 灰box + 边框 (颜色与主浏览器 preButtonRender 一致)
        guiGraphics.fill(px, py, px + PANEL_W, py + PANEL_H, -15066598);
        guiGraphics.fill(px, py, px + PANEL_W, py + 1, -11184811);
        guiGraphics.fill(px, py + PANEL_H - 1, px + PANEL_W, py + PANEL_H, -11184811);
        guiGraphics.fill(px, py, px + 1, py + PANEL_H, -11184811);
        guiGraphics.fill(px + PANEL_W - 1, py, px + PANEL_W, py + PANEL_H, -11184811);
        guiGraphics.drawCenteredString(this.font, "多方块结构浏览器", px + PANEL_W / 2, py + 5, 0x55AAFF);
        this.drawSourceTabs(guiGraphics, px, py, mouseX, mouseY);
        this.drawCards(guiGraphics, px, py, mouseX, mouseY);
    }

    private void drawSourceTabs(GuiGraphics guiGraphics, int px, int py, int mouseX, int mouseY) {
        this.sourceTabRects.clear();
        // 固定标签页: 对应模组没装也显示 (内容区提示需安装), 让玩家知道哪些模组提供多方块;
        // 末尾"百科链接"标签页跳转 mcmod 百科.
        ArrayList<String> keys = new ArrayList<String>();
        ArrayList<String> labels = new ArrayList<String>();
        keys.add("all");
        labels.add("全部");
        keys.add(MultiblockCatalog.SOURCE_GTM);
        labels.add(MultiblockCatalog.sourceDisplayName(MultiblockCatalog.SOURCE_GTM));
        keys.add(MultiblockCatalog.SOURCE_MEKANISM);
        labels.add(MultiblockCatalog.sourceDisplayName(MultiblockCatalog.SOURCE_MEKANISM));
        keys.add(MultiblockCatalog.SOURCE_MBD2);
        labels.add(MultiblockCatalog.sourceDisplayName(MultiblockCatalog.SOURCE_MBD2));
        keys.add(TAB_LINKS);
        labels.add("百科链接");
        int btnH = 16;
        int x = px + 4;
        int y = py + 18;
        for (int i = 0; i < keys.size(); ++i) {
            String key = keys.get(i);
            String label = labels.get(i);
            int bw = this.font.width(label) + 12;
            boolean active = key.equals("all") && this.sourceFilter == null || key.equals(this.sourceFilter);
            boolean hovered = mouseX >= x && mouseX <= x + bw && mouseY >= y && mouseY <= y + btnH;
            int bg = active ? -13743509 : (hovered ? -12961222 : -14737633);
            int border = active ? -11162881 : (hovered ? -8947849 : -11184811);
            guiGraphics.fill(x, y, x + bw, y + btnH, bg);
            guiGraphics.fill(x, y, x + bw, y + 1, border);
            guiGraphics.fill(x, y + btnH - 1, x + bw, y + btnH, border);
            guiGraphics.fill(x, y, x + 1, y + btnH, border);
            guiGraphics.fill(x + bw - 1, y, x + bw, y + btnH, border);
            guiGraphics.drawCenteredString(this.font, label, x + bw / 2, y + 4, active ? 0xFFFFFF : -5592406);
            this.sourceTabRects.put(key, new int[]{x, y, bw, btnH});
            x += bw + 4;
        }
    }

    private void drawCards(GuiGraphics guiGraphics, int px, int py, int mouseX, int mouseY) {
        this.cardRects.clear();
        this.pagPrevRect = null;
        this.pagNextRect = null;
        int areaX = px + 4;
        int areaY = py + 38;
        int areaW = PANEL_W - 8;
        int areaH = PANEL_H - 38 - 14;
        if (TAB_LINKS.equals(sourceFilter)) {
            this.drawLinks(guiGraphics, px, py, areaX, areaY, areaW, mouseX, mouseY);
            return;
        }
        if (sourceFilter != null && !isSourceLoaded(sourceFilter)) {
            guiGraphics.drawCenteredString(this.font, "需安装「" + MultiblockCatalog.sourceDisplayName(sourceFilter) + "」才能显示多方块结构", px + PANEL_W / 2, py + PANEL_H / 2 - 6, -7829368);
            guiGraphics.drawCenteredString(this.font, "安装并重启游戏后, 该标签页会自动列出其多方块结构", px + PANEL_W / 2, py + PANEL_H / 2 + 6, -10066330);
            return;
        }
        if (!MultiblockCatalog.isAnyLoaded()) {
            guiGraphics.drawCenteredString(this.font, "未安装格雷科技 / 通用机械 / MBD2", px + PANEL_W / 2, py + PANEL_H / 2 - 6, -7829368);
            guiGraphics.drawCenteredString(this.font, "该界面展示 GTM / Mekanism / MBD2 多方块结构", px + PANEL_W / 2, py + PANEL_H / 2 + 6, -10066330);
            return;
        }
        List<String> ids = MultiblockCatalog.getIds(this.sourceFilter);
        if (ids.isEmpty()) {
            guiGraphics.drawCenteredString(this.font, "该分类下暂无多方块结构", px + PANEL_W / 2, areaY + 60, -7829368);
            return;
        }
        HashMap<String, Boolean> readyCache = new HashMap<String, Boolean>();
        Minecraft mc = Minecraft.getInstance();
        for (String id : ids) {
            MultiblockShapeData sh;
            boolean ready = false;
            if (mc.player != null && (sh = MultiblockCatalog.getShape(id)) != null) {
                // 创造模式免材料: 卡片直接显示可建造
                ready = mc.player.isCreative()
                    || ChallengeSessionManager.isReady(mc.player.getUUID(), sh.sessionKey(), sh.toMaterialList().required);
            }
            readyCache.put(id, ready);
        }
        int pageCount = Math.max(1, (ids.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, pageCount - 1));
        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, ids.size());
        int gridW = CARD_COLS * CARD_W + (CARD_COLS - 1) * CARD_GAP;
        int gridX = areaX + (areaW - gridW) / 2;
        int gridY = areaY + 2;
        for (int i = 0; i < end - start; ++i) {
            int row = i / CARD_COLS;
            int col = i % CARD_COLS;
            int cx = gridX + col * (CARD_W + CARD_GAP);
            int cy = gridY + row * (CARD_H + CARD_GAP);
            String id = ids.get(start + i);
            this.drawCard(guiGraphics, cx, cy, id, Boolean.TRUE.equals(readyCache.get(id)), mouseX, mouseY);
            this.cardRects.put(id, new int[]{cx, cy, CARD_W, CARD_H});
        }
        if (pageCount > 1) {
            this.drawPaginationBar(guiGraphics, px, py + PANEL_H - 14, PANEL_W, page, pageCount, mouseX, mouseY);
        }
    }

    private void drawCard(GuiGraphics guiGraphics, int cx, int cy, String id, boolean ready, int mouseX, int mouseY) {
        boolean hovered = mouseX >= cx && mouseX <= cx + CARD_W && mouseY >= cy && mouseY <= cy + CARD_H;
        int bg = hovered ? -12961222 : -14737633;
        int border = hovered ? -11162881 : -11184811;
        guiGraphics.fill(cx, cy, cx + CARD_W, cy + CARD_H, bg);
        guiGraphics.fill(cx, cy, cx + CARD_W, cy + 1, border);
        guiGraphics.fill(cx, cy + CARD_H - 1, cx + CARD_W, cy + CARD_H, border);
        guiGraphics.fill(cx, cy, cx + 1, cy + CARD_H, border);
        guiGraphics.fill(cx + CARD_W - 1, cy, cx + CARD_W, cy + CARD_H, border);
        MultiblockShapeData shape = MultiblockCatalog.getShape(id);
        if (shape == null) {
            return;
        }
        String name = (String) GuiMultiblockDetail.displayName(shape);
        if (this.font.width(name) > 84) {
            name = this.font.plainSubstrByWidth(name, 80) + "..";
        }
        guiGraphics.drawCenteredString(this.font, name, cx + CARD_W / 2, cy + 8, 0xFFFFFF);
        String path = id.substring(id.indexOf(':') + 1);
        if (this.font.width(path) > 84) {
            path = this.font.plainSubstrByWidth(path, 80) + "..";
        }
        guiGraphics.drawCenteredString(this.font, path, cx + CARD_W / 2, cy + 22, -7829368);
        guiGraphics.drawCenteredString(this.font, shape.width() + "x" + shape.height() + "x" + shape.length() + "  " + shape.localBlocks.size() + " 块", cx + CARD_W / 2, cy + 38, -5592406);
        guiGraphics.drawCenteredString(this.font, ready ? "✓ 材料已交" : "材料未交", cx + CARD_W / 2, cy + CARD_H - 12, ready ? -11141291 : -21931);
    }

    private void drawPaginationBar(GuiGraphics guiGraphics, int px, int y, int pw, int currentPage, int totalPages, int mouseX, int mouseY) {
        int barH = 12;
        int prevW = 16;
        int nextW = 16;
        int gap = 4;
        int numW = 28;
        int totalUsed = prevW + gap + numW + gap + nextW;
        int prevX = px + (pw - totalUsed) / 2;
        this.pagPrevRect = new int[]{prevX, y, prevW, barH};
        boolean prevActive = currentPage > 0;
        this.drawPageBtn(guiGraphics, this.pagPrevRect, "‹", prevActive, prevActive && isHovered(this.pagPrevRect, mouseX, mouseY));
        int numX = prevX + prevW + gap;
        int[] numR = new int[]{numX, y, numW, barH};
        int bg = isHovered(numR, mouseX, mouseY) ? -11180391 : -12298889;
        guiGraphics.fill(numR[0], numR[1], numR[0] + numR[2], numR[1] + numR[3], bg);
        guiGraphics.drawCenteredString(this.font, currentPage + 1 + "/" + totalPages, numR[0] + numR[2] / 2, numR[1] + (numR[3] - 8) / 2, -1);
        int nextX = numX + numW + gap;
        this.pagNextRect = new int[]{nextX, y, nextW, barH};
        boolean nextActive = currentPage < totalPages - 1;
        this.drawPageBtn(guiGraphics, this.pagNextRect, "›", nextActive, nextActive && isHovered(this.pagNextRect, mouseX, mouseY));
    }

    private void drawPageBtn(GuiGraphics guiGraphics, int[] r, String text, boolean active, boolean hovered) {
        int bg = !active ? -14013910 : (hovered ? -10061910 : -12298889);
        guiGraphics.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], bg);
        guiGraphics.drawCenteredString(this.font, text, r[0] + r[2] / 2, r[1] + (r[3] - 8) / 2, active ? 0xFFFFFF : -10066330);
    }

    private static boolean isHovered(int[] r, int mx, int my) {
        return r != null && mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3];
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int mx = (int) mouseX;
        int my = (int) mouseY;
        for (Map.Entry<String, int[]> e : this.sourceTabRects.entrySet()) {
            if (!isHovered(e.getValue(), mx, my)) continue;
            String key = e.getKey();
            sourceFilter = "all".equals(key) ? null : key;
            page = 0;
            return true;
        }
        if (TAB_LINKS.equals(sourceFilter)) {
            for (Map.Entry<String, int[]> e : this.linkRects.entrySet()) {
                if (!isHovered(e.getValue(), mx, my)) continue;
                openUrl(e.getKey());
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
        for (Map.Entry<String, int[]> e : this.cardRects.entrySet()) {
            if (!isHovered(e.getValue(), mx, my)) continue;
            GuiMultiblockDetail.open(e.getKey());
            return true;
        }
        int pageCount = this.computePageCount();
        if (isHovered(this.pagPrevRect, mx, my) && page > 0) {
            --page;
            return true;
        }
        if (isHovered(this.pagNextRect, mx, my) && page < pageCount - 1) {
            ++page;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private int computePageCount() {
        if (TAB_LINKS.equals(sourceFilter)) {
            return 1;
        }
        List<String> ids = MultiblockCatalog.getIds(sourceFilter);
        return Math.max(1, (ids.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    /** 百科链接标签页: 每行一个 mcmod 百科链接, 点击直接用系统浏览器打开. */
    private void drawLinks(GuiGraphics guiGraphics, int px, int py, int areaX, int areaY, int areaW, int mouseX, int mouseY) {
        this.linkRects.clear();
        guiGraphics.drawCenteredString(this.font, "点击链接跳转浏览器查看对应模组的 mcmod 百科", px + PANEL_W / 2, areaY + 4, -7829368);
        String[][] links = new String[][]{
            {"MBD2 (Multiblocked2)", "https://www.mcmod.cn/class/16540.html"},
            {"格雷科技现代版", "https://www.mcmod.cn/class/12850.html"},
            {"通用机械", "https://www.mcmod.cn/class/187.html"},
        };
        int rowW = 288;
        int rowH = 24;
        int x = areaX + (areaW - rowW) / 2;
        int y = areaY + 20;
        for (String[] link : links) {
            boolean hovered = mouseX >= x && mouseX <= x + rowW && mouseY >= y && mouseY <= y + rowH;
            int bg = hovered ? -12961222 : -14737633;
            int border = hovered ? -11162881 : -11184811;
            guiGraphics.fill(x, y, x + rowW, y + rowH, bg);
            guiGraphics.fill(x, y, x + rowW, y + 1, border);
            guiGraphics.fill(x, y + rowH - 1, x + rowW, y + rowH, border);
            guiGraphics.fill(x, y, x + 1, y + rowH, border);
            guiGraphics.fill(x + rowW - 1, y, x + rowW, y + rowH, border);
            guiGraphics.drawString(this.font, link[0], x + 6, y + 8, 0xFFFFFF);
            guiGraphics.drawString(this.font, link[1], x + rowW - 6 - this.font.width(link[1]), y + 8, hovered ? 0x77BBFF : 0x55AAFF);
            this.linkRects.put(link[1], new int[]{x, y, rowW, rowH});
            y += rowH + 6;
        }
    }

    private static boolean isSourceLoaded(String src) {
        if (MultiblockCatalog.SOURCE_GTM.equals(src)) {
            return MultiblockCatalog.isGtmLoaded();
        }
        if (MultiblockCatalog.SOURCE_MEKANISM.equals(src)) {
            return MultiblockCatalog.isMekanismLoaded();
        }
        if (MultiblockCatalog.SOURCE_MBD2.equals(src)) {
            return MultiblockCatalog.isMbd2Loaded();
        }
        return false;
    }

    /** 直接用系统浏览器打开链接 (与 GuiExtensionPackDownloader 同一套 openUri + Desktop 兜底). */
    private static void openUrl(String url) {
        try {
            net.minecraft.Util.getPlatform().openUri(java.net.URI.create(url));
        } catch (Throwable t) {
            try {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            } catch (Throwable t2) {
                com.prefab.addon.PrefabCustomAddon.LOGGER.warn("[MB-BROWSER] 打开百科链接失败: {}", url, t2);
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
