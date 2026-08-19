package com.prefab.addon.client.gui;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.LocalBuilding;
import com.prefab.addon.extension.LocalBuildingScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * "制作蓝图" tab 用的建筑选择子屏: 列出所有本地建筑, 支持搜索,
 * 点一个就回调回父界面 (Editor 的 selectedBuilding).
 *
 * <p>最小化实现: 没有 tab / 翻页 / 3D 预览, 只是给玩家点一个 LocalBuilding.</p>
 *
 * <p>回调模式: 父界面 {@code GuiExtensionPackEditor} 调用
 * {@link #open(GuiExtensionPackEditor, Consumer)}, 子屏点完建筑调 {@code callback.accept(lb)} 然后
 * {@code mc.setScreen(parentGui)}, 父界面从 {@code selectedBuilding} 读结果.</p>
 */
public class GuiBlueprintBuildingPicker extends Screen {

    private static Consumer<LocalBuilding> pendingCallback = null;
    /** 父 Editor 引用 — 点完建筑后回到父, 不会丢失 tab 状态. */
    private static GuiExtensionPackEditor pendingParent = null;

    private final List<LocalBuilding> all = new ArrayList<>();
    private List<LocalBuilding> filtered = new ArrayList<>();
    private EditBox search;
    private int scrollOffset = 0;
    private final int rowH = 22;
    private final int visibleRows = 12;

    /** 面板尺寸和位置 (相对整张 GUI). */
    private static final int PANEL_W = 320;
    private static final int PANEL_H = 320;
    private int panelX, panelY;

    /**
     * 静态入口: 父界面调用这个, 进入子屏; 点完建筑后回调, 然后回到父界面.
     * 父 Editor 引用会记下, 选完直接 setScreen(parent) 还原, tab 状态不丢.
     */
    public static void open(GuiExtensionPackEditor parent, Consumer<LocalBuilding> cb) {
        pendingCallback = cb;
        pendingParent = parent;
        Minecraft.getInstance().setScreen(new GuiBlueprintBuildingPicker());
    }

    public GuiBlueprintBuildingPicker() {
        super(Component.literal("Pick Building"));
    }

    @Override
    protected void init() {
        super.init();
        this.panelX = (this.width - PANEL_W) / 2;
        this.panelY = (this.height - PANEL_H) / 2;

        // 扫描所有本地建筑
        try {
            List<LocalBuilding> scanned = LocalBuildingScanner.scanAll();
            if (scanned != null) {
                this.all.clear();
                this.all.addAll(scanned);
            }
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[MB-PICKER] 扫描 LocalBuilding 失败", t);
        }
        this.filtered = new ArrayList<>(this.all);

        int searchX = this.panelX + 8;
        int searchY = this.panelY + 28;
        int searchW = PANEL_W - 16;
        this.search = new EditBox(this.font, searchX, searchY, searchW, 14, Component.literal(""));
        this.search.setMaxLength(64);
        this.search.setValue("");
        this.search.setResponder(s -> {
            this.scrollOffset = 0;
            rebuildFiltered();
        });
        this.addRenderableWidget(this.search);
    }

    private void rebuildFiltered() {
        String q = this.search == null ? "" : this.search.getValue().trim().toLowerCase(Locale.ROOT);
        this.filtered.clear();
        if (q.isEmpty()) {
            this.filtered.addAll(this.all);
        } else {
            for (LocalBuilding lb : this.all) {
                String name = lb.getDisplayName() == null ? "" : lb.getDisplayName().toLowerCase(Locale.ROOT);
                String id = lb.id == null ? "" : lb.id.toLowerCase(Locale.ROOT);
                if (name.contains(q) || id.contains(q)) this.filtered.add(lb);
            }
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 背景遮罩
        g.fill(0, 0, this.width, this.height, 0xCC000000);

        int rx = this.panelX, ry = this.panelY, rw = PANEL_W, rh = PANEL_H;

        // 面板底色
        g.fill(rx, ry, rx + rw, ry + rh, 0xFFFFFFFF);
        g.fill(rx, ry, rx + rw, ry + 1, 0xFF555555);
        g.fill(rx, ry + rh - 1, rx + rw, ry + rh, 0xFF555555);
        g.fill(rx, ry, rx + 1, ry + rh, 0xFF555555);
        g.fill(rx + rw - 1, ry, rx + rw, ry + rh, 0xFF555555);

        // 标题
        g.drawString(this.font, "§l§n选建筑 (本地)", rx + 8, ry + 8, 0xFF333333, false);

        // 列表区
        int listY = ry + 50;
        int listX = rx + 8;
        int listW = rw - 16;
        int listH = this.rowH * this.visibleRows;

        // 背景
        g.fill(listX, listY, listX + listW, listY + listH, 0xFFF5F5F5);
        g.fill(listX, listY, listX + listW, listY + 1, 0xFFAAAAAA);
        g.fill(listX, listY + listH - 1, listX + listW, listY + listH, 0xFFAAAAAA);
        g.fill(listX, listY, listX + 1, listY + listH, 0xFFAAAAAA);
        g.fill(listX + listW - 1, listY, listX + listW, listY + listH, 0xFFAAAAAA);

        // 渲染可见行
        for (int i = 0; i < this.visibleRows; i++) {
            int idx = this.scrollOffset + i;
            if (idx >= this.filtered.size()) break;
            LocalBuilding lb = this.filtered.get(idx);
            int ry2 = listY + i * this.rowH;
            int rowX1 = listX;
            int rowX2 = listX + listW;
            int rowY1 = ry2;
            int rowY2 = ry2 + this.rowH;
            boolean hover = mouseX >= rowX1 && mouseX < rowX2 && mouseY >= rowY1 && mouseY < rowY2;
            g.fill(rowX1, rowY1, rowX2, rowY2, hover ? 0xFFDDEEFF : 0xFFEEEEEE);
            // 文件存在性标识
            boolean exists = lb.filePath != null && Files.exists(lb.filePath);
            String dot = exists ? "§a●" : "§c●";
            String name = lb.getDisplayName() == null ? lb.id : lb.getDisplayName();
            String sub = lb.id + (lb.source != null && !"local".equals(lb.source) ? "  §7[" + lb.source + "]" : "");
            g.drawString(this.font, dot + " §f" + name, rowX1 + 6, rowY1 + 2, 0xFF333333, false);
            g.drawString(this.font, "§7" + sub, rowX1 + 6, rowY1 + 12, 0xFF666666, false);
        }

        // 滚动指示
        if (this.filtered.size() > this.visibleRows) {
            int totalPages = (this.filtered.size() + this.visibleRows - 1) / this.visibleRows;
            int curPage = this.scrollOffset / this.visibleRows + 1;
            g.drawString(this.font,
                "§7" + curPage + " / " + totalPages + "  (滚轮翻页)",
                listX, listY + listH + 4, 0xFF666666, false);
        } else {
            g.drawString(this.font, "§7共 " + this.filtered.size() + " 个建筑",
                listX, listY + listH + 4, 0xFF666666, false);
        }

        // 底部按钮: 返回
        int btnY = ry + rh - 28;
        int backX = rx + 8;
        int backW = 80;
        int backH = 20;
        boolean backHover = mouseX >= backX && mouseX < backX + backW && mouseY >= btnY && mouseY < btnY + backH;
        g.fill(backX, btnY, backX + backW, btnY + backH, backHover ? 0xFFCCCCCC : 0xFFEEEEEE);
        g.fill(backX, btnY, backX + backW, btnY + 1, 0xFF555555);
        g.fill(backX, btnY + backH - 1, backX + backW, btnY + backH, 0xFF555555);
        g.fill(backX, btnY, backX + 1, btnY + backH, 0xFF555555);
        g.fill(backX + backW - 1, btnY, backX + backW, btnY + backH, 0xFF555555);
        String backLabel = "← 返回";
        int backTextW = this.font.width(backLabel);
        g.drawString(this.font, backLabel, backX + (backW - backTextW) / 2, btnY + 6, 0xFFFFFFFF, true);

        // super.render 最后画: 渲染 EditBox 等 widget
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // 1.21.1: 新签名 (mouseX, mouseY, scrollX, scrollY), 旧 delta 没了
        // 上滚 (scrollY > 0) = 往上翻页, 下滚 (scrollY < 0) = 往下翻页
        double delta = scrollY;
        int maxOffset = Math.max(0, ((this.filtered.size() - 1) / this.visibleRows) * this.visibleRows);
        if (delta > 0) {
            this.scrollOffset = Math.max(0, this.scrollOffset - this.visibleRows);
        } else {
            this.scrollOffset = Math.min(maxOffset, this.scrollOffset + this.visibleRows);
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int mx = (int) mouseX, my = (int) mouseY;
        int rx = this.panelX, ry = this.panelY, rw = PANEL_W, rh = PANEL_H;

        // 返回按钮
        int btnY = ry + rh - 28;
        int backX = rx + 8;
        int backW = 80;
        int backH = 20;
        if (mx >= backX && mx < backX + backW && my >= btnY && my < btnY + backH) {
            returnToParent(null);
            return true;
        }

        // 列表点击
        int listY = ry + 50;
        int listX = rx + 8;
        int listW = rw - 16;
        if (mx >= listX && mx < listX + listW && my >= listY && my < listY + this.rowH * this.visibleRows) {
            int rel = (my - listY) / this.rowH;
            int idx = this.scrollOffset + rel;
            if (idx >= 0 && idx < this.filtered.size()) {
                LocalBuilding lb = this.filtered.get(idx);
                returnToParent(lb);
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void returnToParent(LocalBuilding picked) {
        if (picked != null && pendingCallback != null) {
            try {
                pendingCallback.accept(picked);
            } catch (Throwable t) {
                PrefabCustomAddon.LOGGER.error("[MB-PICKER] 回调失败", t);
            }
        }
        pendingCallback = null;
        // 回到父 Editor (保留原 tab 状态), 没有父就新开一个
        GuiExtensionPackEditor parent = pendingParent;
        pendingParent = null;
        if (parent != null) {
            Minecraft.getInstance().setScreen(parent);
        } else {
            Minecraft.getInstance().setScreen(new GuiExtensionPackEditor());
        }
    }
}
