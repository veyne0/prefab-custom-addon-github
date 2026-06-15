package com.prefab.addon.client.gui;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.gui.GuiBase;
import com.prefab.gui.controls.ExtendedButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Minecraft 原生文件选择器 (替代 AWT FileDialog, 在 MC 1.21+ 不依赖 AWT)。
 *
 * 用法:
 *   GuiFilePicker.open(
 *       System.getProperty("user.home"),  // 起始目录
 *       new String[]{"nbt"},             // 扩展名过滤 (小写, 不带点也兼容)
 *       file -> { ... }                   // 用户选好文件后的回调
 *   );
 *
 * 布局:
 *   - 顶部: 路径输入框 (可直接粘贴路径) + "转到"按钮
 *   - 中部: 文件/文件夹列表 (文件夹优先, 文件按名排序)
 *   - 底部: "选择"/"取消"按钮
 */
public class GuiFilePicker extends GuiBase {

    public static class Result {
        public final File file;       // null = 用户取消
        public final boolean ok;
        public Result(File f, boolean o) { this.file = f; this.ok = o; }
    }

    private final Consumer<File> callback;
    private final String[] extensions;          // 允许的扩展名 (e.g. {"nbt", "png"}), null = 不过滤
    private final List<FileEntry> entries = new ArrayList<>();
    private int scrollOffset = 0;
    private int visibleRows = 12;
    private static final int ROW_H = 14;

    private EditBox pathField;
    private ExtendedButton btnGoto;
    private ExtendedButton btnUp;
    private ExtendedButton btnSelect;
    private ExtendedButton btnCancel;
    private ExtendedButton btnHome;
    private ExtendedButton btnDesktop;
    private ExtendedButton btnDownloads;
    private ExtendedButton btnPictures;

    private File currentDir;
    private File selectedFile = null;
    private int listX, listY, listW, listH;
    private int formX, formY;
    private int grayBoxX, grayBoxY;
    private int panelW, panelH;

    private String statusMessage = null;
    private int statusColor = 0x55FF55;
    private int statusTick = 0;

    public GuiFilePicker(String initialDir, String[] extensions, Consumer<File> callback) {
        super("Select File");
        this.callback = callback;
        this.extensions = extensions;
        File start = initialDir == null ? new File(System.getProperty("user.home")) : new File(initialDir);
        if (!start.isDirectory()) {
            start = start.getParentFile();
            if (start == null || !start.isDirectory()) {
                start = new File(System.getProperty("user.home"));
            }
        }
        this.currentDir = start;
    }

    public static void open(String initialDir, String[] extensions, Consumer<File> callback) {
        Minecraft.getInstance().setScreen(new GuiFilePicker(initialDir, extensions, callback));
    }

    @Override
    protected void Initialize() {
        super.Initialize();
        this.panelW = 460;
        this.panelH = 340;
        this.modifiedInitialXAxis = panelW / 2;
        this.modifiedInitialYAxis = panelH / 2;
        this.imagePanelWidth = panelW;
        this.imagePanelHeight = panelH;
        this.shownImageHeight = 1;
        this.shownImageWidth = 1;

        this.grayBoxX = (this.width / 2) - this.modifiedInitialXAxis;
        this.grayBoxY = (this.height / 2) - this.modifiedInitialYAxis;

        this.formX = grayBoxX + 12;
        this.formY = grayBoxY + 24;
        this.listW = panelW - 24;
        this.listH = 220;
        this.listX = formX;
        this.listY = formY + 50;
        this.visibleRows = listH / ROW_H;

        // 路径输入框 + 转到
        this.pathField = new EditBox(this.font, formX, formY, listW - 60, 16, Component.literal(""));
        this.pathField.setMaxLength(512);
        this.pathField.setValue(currentDir.getAbsolutePath());
        this.addRenderableWidget(pathField);
        this.btnGoto = this.createAndAddButton(formX + listW - 55, formY, 55, 18, "转到");

        // 快捷目录按钮
        int sbY = formY + 22;
        this.btnHome = this.createAndAddButton(formX, sbY, 60, 18, "主目录");
        this.btnDesktop = this.createAndAddButton(formX + 65, sbY, 60, 18, "桌面");
        this.btnDownloads = this.createAndAddButton(formX + 130, sbY, 70, 18, "下载");
        this.btnPictures = this.createAndAddButton(formX + 205, sbY, 60, 18, "图片");
        this.btnUp = this.createAndAddButton(formX + listW - 55, sbY, 55, 18, "↑ 上级");

        // 底部按钮
        int btnY = grayBoxY + panelH - 26;
        this.btnSelect = this.createAndAddButton(grayBoxX + panelW - 200, btnY, 90, 20, "选择");
        this.btnCancel = this.createAndAddButton(grayBoxX + panelW - 105, btnY, 90, 20, "取消");
        this.btnSelect.active = false;

        refreshEntries();
    }

    private void refreshEntries() {
        entries.clear();
        if (currentDir == null || !currentDir.isDirectory()) {
            setStatus("目录不存在: " + currentDir, 0xFF5555);
            return;
        }
        // 当前路径 (用于显示)
        this.pathField.setValue(currentDir.getAbsolutePath());

        File[] children = currentDir.listFiles();
        if (children == null) {
            setStatus("无法读取目录: " + currentDir, 0xFF5555);
            return;
        }
        // 排序: 文件夹优先, 然后按名字
        java.util.Arrays.sort(children, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        for (File f : children) {
            // 隐藏文件 (.xxx) 跳过
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) {
                entries.add(new FileEntry(f, true));
            } else if (matchesExtension(f)) {
                entries.add(new FileEntry(f, false));
            }
        }
        scrollOffset = 0;
        selectedFile = null;
        updateSelectButton();
        setStatus("已加载: " + entries.size() + " 个条目", 0xAAAAAA);
    }

    private boolean matchesExtension(File f) {
        if (extensions == null || extensions.length == 0) return true;
        String name = f.getName().toLowerCase();
        for (String ext : extensions) {
            if (name.endsWith("." + ext.toLowerCase())) return true;
        }
        return false;
    }

    private void setStatus(String msg, int color) {
        this.statusMessage = msg;
        this.statusColor = color;
        this.statusTick = 80;
    }

    @Override
    public void tick() {
        super.tick();
        if (statusTick > 0) statusTick--;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {  // ESC
            finish(null);
            return true;
        }
        if (keyCode == 257 && pathField.isFocused()) {  // Enter on path field
            gotoPath(pathField.getValue().trim());
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // 列表点击
            for (int i = 0; i < Math.min(visibleRows, entries.size()); i++) {
                int idx = i + scrollOffset;
                if (idx >= entries.size()) break;
                int iy = listY + i * ROW_H;
                if (mouseX >= listX && mouseX <= listX + listW
                    && mouseY >= iy && mouseY < iy + ROW_H) {
                    FileEntry e = entries.get(idx);
                    if (e.isDir) {
                        // 双击效果 (单点击进入)
                        currentDir = e.file;
                        refreshEntries();
                    } else {
                        selectedFile = e.file;
                        updateSelectButton();
                        setStatus("已选: " + e.file.getName(), 0x55FF55);
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= listX && mouseX <= listX + listW
            && mouseY >= listY && mouseY <= listY + listH) {
            int max = Math.max(0, entries.size() - visibleRows);
            scrollOffset = Math.max(0, Math.min(max, scrollOffset - (int) scrollY));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void updateSelectButton() {
        this.btnSelect.active = (selectedFile != null && selectedFile.isFile());
    }

    private void gotoPath(String p) {
        if (p == null || p.isEmpty()) return;
        // 去掉可能的引号
        p = p.replace("\"", "").trim();
        // 尝试作为目录
        File f = new File(p);
        if (f.isDirectory()) {
            currentDir = f;
            refreshEntries();
            return;
        }
        // 可能是文件 (粘贴完整文件路径的情况)
        if (f.isFile()) {
            File parent = f.getParentFile();
            if (parent != null && parent.isDirectory()) {
                currentDir = parent;
                refreshEntries();
                // 选中该文件
                for (FileEntry e : entries) {
                    if (e.file.equals(f)) {
                        selectedFile = f;
                        // 滚到可见
                        int idx = entries.indexOf(e);
                        scrollOffset = Math.max(0, Math.min(entries.size() - visibleRows, idx - visibleRows / 2));
                        updateSelectButton();
                        setStatus("已选: " + f.getName(), 0x55FF55);
                        return;
                    }
                }
            }
        }
        setStatus("✗ 路径无效: " + p, 0xFF5555);
    }

    private void goUp() {
        File parent = currentDir == null ? null : currentDir.getParentFile();
        if (parent != null && parent.isDirectory()) {
            currentDir = parent;
            refreshEntries();
        }
    }

    private void goHome(String subdir) {
        File f = new File(System.getProperty("user.home"), subdir);
        if (!f.isDirectory()) f = new File(System.getProperty("user.home"));
        currentDir = f;
        refreshEntries();
    }

    @Override
    protected void preButtonRender(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, float partialTicks) {
        this.drawControlBackground(guiGraphics, x, y, this.imagePanelWidth, this.imagePanelHeight);
    }

    @Override
    protected void postButtonRender(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, float partialTicks) {
        // 标题
        String extLabel = (extensions == null || extensions.length == 0) ? "所有文件"
            : "*." + String.join(", *.", extensions);
        guiGraphics.drawCenteredString(this.font,
            "选择文件 (" + extLabel + ")",
            this.getCenteredXAxis(), y + 6, this.textColor);

        // 列表背景
        guiGraphics.fill(listX - 2, listY - 2, listX + listW + 2, listY + listH + 2, 0xFF333333);
        guiGraphics.fill(listX, listY, listX + listW, listY + listH, 0xFF1A1A1A);

        if (entries.isEmpty()) {
            guiGraphics.drawCenteredString(this.font, "(空目录)", listX + listW / 2, listY + 20, 0xAAAAAA);
        } else {
            for (int i = 0; i < Math.min(visibleRows, entries.size()); i++) {
                int idx = i + scrollOffset;
                if (idx >= entries.size()) break;
                FileEntry e = entries.get(idx);
                int iy = listY + i * ROW_H;
                int bg;
                if (e.file.equals(selectedFile)) {
                    bg = 0xFF4A6FA5;
                } else if (mouseX_() >= listX && mouseX_() <= listX + listW
                    && mouseY_() >= iy && mouseY_() < iy + ROW_H) {
                    bg = 0xFF2A2A2A;
                } else {
                    bg = (i % 2 == 0) ? 0xFF1F1F1F : 0xFF1A1A1A;
                }
                guiGraphics.fill(listX, iy, listX + listW, iy + ROW_H, bg);
                String icon = e.isDir ? "📁" : "📄";
                String name = e.file.getName();
                int maxNameLen = (listW - 30) / 7;
                if (name.length() > maxNameLen) name = name.substring(0, maxNameLen - 2) + "..";
                int nameColor = e.isDir ? 0xFFDD55FF : 0xFFFFFFFF;
                // 简单图标
                guiGraphics.drawString(this.font, e.isDir ? "[D]" : "[F]",
                    listX + 4, iy + 2, nameColor);
                guiGraphics.drawString(this.font, name, listX + 24, iy + 2, nameColor);
            }
        }

        // 滚动条 (简单指示)
        if (entries.size() > visibleRows) {
            int barH = listH;
            int knobH = Math.max(10, barH * visibleRows / entries.size());
            int maxScroll = Math.max(1, entries.size() - visibleRows);
            int knobY = listY + (barH - knobH) * scrollOffset / maxScroll;
            guiGraphics.fill(listX + listW - 3, knobY, listX + listW, knobY + knobH, 0xFFAAAAAA);
        }

        // 状态
        if (statusMessage != null && statusTick > 0) {
            int sy = grayBoxY + panelH - 14;
            guiGraphics.drawString(this.font, statusMessage, grayBoxX + 12, sy, statusColor);
        }
    }

    // 把 mouseX/mouseY 缓存到字段, 避免修改所有 signature
    private double _mx, _my;
    private double mouseX_() { return _mx; }
    private double mouseY_() { return _my; }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        this._mx = mouseX;
        this._my = mouseY;
        super.render(guiGraphics, mouseX, mouseY, partialTicks);
    }

    @Override
    public void buttonClicked(AbstractButton button) {
        if (button == this.btnCancel || button == null && false) {
            finish(null);
            return;
        }
        if (button == this.btnSelect) {
            if (selectedFile != null && selectedFile.isFile()) {
                finish(selectedFile);
            } else {
                setStatus("请先选择一个文件", 0xFF5555);
            }
            return;
        }
        if (button == this.btnGoto) {
            gotoPath(pathField.getValue().trim());
            return;
        }
        if (button == this.btnUp) {
            goUp();
            return;
        }
        if (button == this.btnHome) {
            goHome("");
            return;
        }
        if (button == this.btnDesktop) {
            goHome("Desktop");
            return;
        }
        if (button == this.btnDownloads) {
            goHome("Downloads");
            return;
        }
        if (button == this.btnPictures) {
            goHome("Pictures");
            return;
        }
    }

    private void finish(File f) {
        Consumer<File> cb = this.callback;
        this.onClose();
        if (cb != null && f != null) {
            try { cb.accept(f); } catch (Throwable t) {
                PrefabCustomAddon.LOGGER.error("[FILEPICKER] callback failed", t);
            }
        }
    }

    /** 列表条目: 文件或文件夹 */
    private static class FileEntry {
        final File file;
        final boolean isDir;
        FileEntry(File f, boolean d) { this.file = f; this.isDir = d; }
    }
}
