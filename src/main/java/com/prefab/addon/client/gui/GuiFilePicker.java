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
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.prefab.addon.PrefabCustomAddon;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Minecraft 原生文件选择器 (LDLib2 实现, 替代 AWT FileDialog, 在 MC 1.21+ 不依赖 AWT)。
 *
 * 用法:
 *   GuiFilePicker.open(
 *       System.getProperty("user.home"),  // 起始目录
 *       new String[]{"nbt"},             // 扩展名过滤 (小写, 不带点也兼容)
 *       file -> { ... }                   // 用户选好文件后的回调
 *   );
 *
 * 布局 (Flex 布局, MC 原版深色风格):
 *   - 标题栏 (固定 24px, 不可滚)
 *   - 路径输入框 + "转到" 按钮
 *   - 快捷目录按钮 (主目录 / 桌面 / 下载 / 图片 / ↑上级)
 *   - 文件/文件夹列表 (ScrollerView, 滚轮自动滚动)
 *   - 状态栏 (临时提示, 80 tick 自动清空)
 *   - 底部按钮栏 ("选择" / "取消")
 */
public class GuiFilePicker {

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
    private static final int ROW_H = 18;

    private TextField pathField;
    private ScrollerView fileListView;
    private Button btnGoto;
    private Button btnUp;
    private Button btnSelect;
    private Button btnCancel;
    private Button btnHome;
    private Button btnDesktop;
    private Button btnDownloads;
    private Button btnPictures;
    private Label statusLabel;

    private File currentDir;
    private File selectedFile = null;

    private String statusMessage = null;
    private int statusColor = 0x55FF55;
    private int statusTick = 0;

    public GuiFilePicker(String initialDir, String[] extensions, Consumer<File> callback) {
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
        GuiFilePicker picker = new GuiFilePicker(initialDir, extensions, callback);
        ModularUI ui = picker.createUI();
        Minecraft.getInstance().setScreen(
            new ModularUIScreen(ui, Component.literal(PrefabCustomAddon.tr("gui.picker.title",
                (extensions == null || extensions.length == 0) ? "*" : "*." + String.join(", *.", extensions)))));
    }

    private ModularUI createUI() {
        // === 根容器 (456 x 340) ===
        UIElement root = new UIElement();
        root.layout(l -> l
            .width(456)
            .height(340)
            .paddingAll(0)
            .flexDirection(FlexDirection.COLUMN)
        );
        root.style(s -> s.background(Sprites.BORDER));

        // === 标题栏 (固定 24px) ===
        String extLabel = (extensions == null || extensions.length == 0) ? "所有文件"
            : "*." + String.join(", *.", extensions);
        UIElement titleBar = new UIElement();
        titleBar.layout(l -> l
            .widthPercent(100)
            .height(24)
            .paddingHorizontal(8)
            .justifyContent(AlignContent.CENTER)
        );
        titleBar.style(s -> s.background(Sprites.RECT_DARK));
        Label title = new Label();
        title.setText(Component.literal("选择文件 (" + extLabel + ")"));
        title.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textColor(0xFFFFFFFF));
        titleBar.addChild(title);
        root.addChild(titleBar);

        // === 路径 + 转到 (固定 22px) ===
        UIElement pathRow = new UIElement();
        pathRow.layout(l -> l
            .widthPercent(100)
            .height(22)
            .paddingAll(2)
            .gapAll(4)
            .flexDirection(FlexDirection.ROW)
        );
        pathField = new TextField();
        pathField.setText(currentDir.getAbsolutePath());
        pathField.setAnyString();
        pathField.setTextValidator(s -> s.length() <= 512);
        pathField.layout(l -> l.flexGrow(1).height(18));
        pathRow.addChild(pathField);
        btnGoto = new Button();
        btnGoto.setText(Component.literal("转到"));
        btnGoto.setOnClick(e -> gotoPath(pathField.getText().trim()));
        btnGoto.layout(l -> l.width(50).height(18));
        pathRow.addChild(btnGoto);
        root.addChild(pathRow);

        // === 快捷目录按钮 (固定 22px) ===
        UIElement shortcutRow = new UIElement();
        shortcutRow.layout(l -> l
            .widthPercent(100)
            .height(22)
            .paddingHorizontal(4)
            .gapAll(4)
            .flexDirection(FlexDirection.ROW)
        );
        btnHome = new Button();
        btnHome.setText(Component.literal("主目录"));
        btnHome.setOnClick(e -> goHome(""));
        btnHome.layout(l -> l.width(60).height(18));
        shortcutRow.addChild(btnHome);

        btnDesktop = new Button();
        btnDesktop.setText(Component.literal(PrefabCustomAddon.tr("gui.picker.desktop")));
        btnDesktop.setOnClick(e -> goHome("Desktop"));
        btnDesktop.layout(l -> l.width(60).height(18));
        shortcutRow.addChild(btnDesktop);

        btnDownloads = new Button();
        btnDownloads.setText(Component.literal("下载"));
        btnDownloads.setOnClick(e -> goHome("Downloads"));
        btnDownloads.layout(l -> l.width(60).height(18));
        shortcutRow.addChild(btnDownloads);

        btnPictures = new Button();
        btnPictures.setText(Component.literal("图片"));
        btnPictures.setOnClick(e -> goHome("Pictures"));
        btnPictures.layout(l -> l.width(60).height(18));
        shortcutRow.addChild(btnPictures);

        // 占位 spacer 把 "上级" 推到右边
        UIElement spacer = new UIElement();
        spacer.layout(l -> l.flexGrow(1).height(18));
        shortcutRow.addChild(spacer);

        btnUp = new Button();
        btnUp.setText(Component.literal(PrefabCustomAddon.tr("gui.picker.up")));
        btnUp.setOnClick(e -> goUp());
        btnUp.layout(l -> l.width(60).height(18));
        shortcutRow.addChild(btnUp);
        root.addChild(shortcutRow);

        // === 文件列表 (ScrollerView, 220px) ===
        fileListView = new ScrollerView();
        fileListView.layout(l -> l
            .width(456)
            .height(220)
        );
        fileListView.scrollerStyle(style -> style.mode(ScrollerMode.VERTICAL));
        fileListView.verticalScroller(scroller -> scroller.setScrollBarSize(15f));
        root.addChild(fileListView);

        // === 状态栏 (固定 16px, 80 tick 自动清空) ===
        statusLabel = new Label();
        statusLabel.setText(Component.literal(""));
        statusLabel.textStyle(t -> t.textColor(0xAAAAAA));
        statusLabel.layout(l -> l.widthPercent(100).height(16).paddingHorizontal(8));
        statusLabel.addEventListener(UIEvents.TICK, e -> {
            if (statusTick > 0) {
                statusTick--;
                if (statusTick == 0) {
                    statusLabel.setText(Component.literal(""));
                }
            }
        });
        root.addChild(statusLabel);

        // === 底部按钮栏 (固定 36px) ===
        UIElement buttonBar = new UIElement();
        buttonBar.layout(l -> l
            .widthPercent(100)
            .height(36)
            .paddingAll(6)
            .gapAll(8)
            .flexDirection(FlexDirection.ROW)
            .justifyContent(AlignContent.FLEX_END)
        );
        btnSelect = new Button();
        btnSelect.setText(Component.literal("选择"));
        btnSelect.setOnClick(e -> {
            if (selectedFile != null && selectedFile.isFile()) {
                finish(selectedFile);
            } else {
                setStatus("请先选择一个文件", 0xFF5555);
            }
        });
        btnSelect.layout(l -> l.width(80).height(22));
        btnSelect.setActive(false);
        buttonBar.addChild(btnSelect);

        btnCancel = new Button();
        btnCancel.setText(Component.literal(PrefabCustomAddon.tr("gui.picker.cancel")));
        btnCancel.setOnClick(e -> finish(null));
        btnCancel.layout(l -> l.width(80).height(22));
        buttonBar.addChild(btnCancel);
        root.addChild(buttonBar);

        // === 初始化 ===
        refreshEntries();

        return ModularUI.of(UI.of(root,
            StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)));
    }

    private void refreshEntries() {
        entries.clear();
        if (currentDir == null || !currentDir.isDirectory()) {
            setStatus("目录不存在: " + currentDir, 0xFF5555);
            rebuildListView();
            return;
        }
        // 当前路径 (用于显示)
        if (pathField != null) {
            pathField.setText(currentDir.getAbsolutePath());
        }

        File[] children = currentDir.listFiles();
        if (children == null) {
            setStatus("无法读取目录: " + currentDir, 0xFF5555);
            rebuildListView();
            return;
        }
        // 排序: 文件夹优先, 然后按名字
        Arrays.sort(children, (a, b) -> {
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
        rebuildListView();
    }

    private void rebuildListView() {
        if (fileListView == null) return;
        fileListView.clearAllScrollViewChildren();
        if (entries.isEmpty()) {
            UIElement empty = new UIElement();
            empty.layout(l -> l
                .widthPercent(100)
                .height(24)
                .paddingVertical(6)
                .justifyContent(AlignContent.CENTER)
            );
            Label emptyLabel = new Label();
            emptyLabel.setText(Component.literal(PrefabCustomAddon.tr("gui.picker.empty")).withStyle(ChatFormatting.GRAY));
            emptyLabel.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
            empty.addChild(emptyLabel);
            fileListView.addScrollViewChild(empty);
            return;
        }
        for (FileEntry e : entries) {
            UIElement row = new UIElement();
            row.layout(l -> l
                .widthPercent(100)
                .height(ROW_H)
                .paddingHorizontal(6)
                .flexDirection(FlexDirection.ROW)
            );
            boolean isSelected = e.file.equals(selectedFile);
            row.style(s -> s.background(isSelected ? Sprites.RECT_LIGHT : Sprites.RECT_DARK));
            // 单击行: 文件夹则进入, 文件则选中
            row.addEventListener(UIEvents.CLICK, ev -> {
                if (e.isDir) {
                    currentDir = e.file;
                    refreshEntries();
                } else {
                    selectedFile = e.file;
                    updateSelectButton();
                    setStatus("已选: " + e.file.getName(), 0x55FF55);
                    rebuildListView();
                }
            });

            // 图标 + 文件名
            String icon = e.isDir ? "[D] " : "[F] ";
            Component nameComponent = e.isDir
                ? Component.literal(icon + e.file.getName()).withStyle(ChatFormatting.LIGHT_PURPLE)
                : Component.literal(icon + e.file.getName());
            Label rowLabel = new Label();
            rowLabel.setText(nameComponent);
            row.addChild(rowLabel);

            fileListView.addScrollViewChild(row);
        }
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
        if (statusLabel != null) {
            statusLabel.setText(Component.literal(msg));
            statusLabel.textStyle(t -> t.textColor(color));
        }
    }

    private void updateSelectButton() {
        if (btnSelect != null) {
            btnSelect.setActive(selectedFile != null && selectedFile.isFile());
        }
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
                        updateSelectButton();
                        setStatus("已选: " + f.getName(), 0x55FF55);
                        rebuildListView();
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

    private void finish(File f) {
        Consumer<File> cb = this.callback;
        Minecraft.getInstance().setScreen(null);
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
