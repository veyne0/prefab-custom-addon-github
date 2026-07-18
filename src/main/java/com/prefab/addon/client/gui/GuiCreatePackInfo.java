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
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.work.PackCreator;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;

/**
 * 创建 / 编辑 拓展包信息表单 (LDLib2 实现).
 *
 * <p>字段顺序参考 d:\MC-Prefab-Main\预制建筑拓展包示例\information\test1.txt:
 * <ul>
 *   <li>标识符 / 拓展包名 / 作者 / 版本 / 相关链接 / 描述 / 封面 PNG</li>
 * </ul>
 *
 * <p>编辑模式: 传入 editing != null, 标识符字段不可改.
 * <br>创建模式: editing == null, 作者默认为玩家名, 版本默认 1.0.0.
 *
 * <p>依赖模组字段 (原 {@code fieldDeps}) 在 UI 上不显示, 永远传空字符串
 * (子建筑的依赖在导出时自动合并).
 */
public final class GuiCreatePackInfo {

    private GuiCreatePackInfo() {}

    // === 内部状态: 跨异步回调和 doSave 共享 ===
    private static final class FormState {
        PackCreator.PackWorkInfo editing;
        GuiExtensionPackCreator parent;
        boolean createMode;
        byte[] coverPng = null;
        String coverPngPath = "(未选择)";
    }

    /**
     * 打开创建 / 编辑 拓展包信息表单.
     *
     * @param editing  null = 创建模式; 非 null = 编辑模式 (标识符字段不可改)
     * @param parent   父窗口 (保存后回调 onChildClosed 触发刷新); 可为 null
     */
    public static void open(PackCreator.PackWorkInfo editing, GuiExtensionPackCreator parent) {
        FormState state = new FormState();
        state.editing = editing;
        state.parent = parent;
        state.createMode = (editing == null);

        ModularUI modularUI = createUI(state);
        String title = state.createMode
            ? "创建拓展包"
            : "编辑拓展包 - " + (editing == null ? "" : editing.id);
        Minecraft.getInstance().setScreen(new ModularUIScreen(modularUI, Component.literal(title)));
    }

    private static ModularUI createUI(FormState state) {
        // === 输入框 ===
        // 标识符
        TextField fieldId = new TextField();
        fieldId.setAnyString();
        fieldId.textFieldStyle(s -> s.placeholder(Component.literal("标识符 (字母/数字/_/-)")));

        // 拓展包名
        TextField fieldName = new TextField();
        fieldName.setAnyString();
        fieldName.textFieldStyle(s -> s.placeholder(Component.literal("中文名称")));

        // 作者
        TextField fieldAuthor = new TextField();
        fieldAuthor.setAnyString();

        // 版本
        TextField fieldVersion = new TextField();
        fieldVersion.setAnyString();

        // 相关链接
        TextField fieldLink = new TextField();
        fieldLink.setAnyString();

        // 描述
        TextField fieldDesc = new TextField();
        fieldDesc.setAnyString();

        // 预填编辑值
        if (!state.createMode) {
            fieldId.setText(safeStr(state.editing.id));
            fieldId.setActive(false);
            fieldName.setText(safeStr(state.editing.name));
            fieldAuthor.setText(safeStr(state.editing.author));
            fieldVersion.setText(safeStr(state.editing.version));
            fieldLink.setText(safeStr(state.editing.link));
            fieldDesc.setText(safeStr(state.editing.description));
        } else {
            // 新建: 作者用玩家名, 版本默认 1.0.0
            fieldId.setText("");
            fieldAuthor.setText(playerName());
            fieldVersion.setText("1.0.0");
        }

        // === 标签 ===
        // 封面路径 (默认灰色 "未选择")
        Label pathLabel = new Label();
        pathLabel.setText(Component.literal("(未选择)").withStyle(ChatFormatting.GRAY));
        pathLabel.textStyle(t -> t.textAlignHorizontal(Horizontal.LEFT));

        // 状态信息
        Label statusLabel = new Label();
        statusLabel.setText(Component.literal(""));
        statusLabel.textStyle(t -> t.textAlignHorizontal(Horizontal.LEFT));

        // === 滚动主区内容 (440 宽, 12 padding, 8 gap) ===
        UIElement content = new UIElement();
        content.layout(l -> l
            .width(440)
            .paddingAll(12)
            .gapAll(8)
            .flexDirection(FlexDirection.COLUMN)
        );
        content.style(s -> s.background(Sprites.BORDER));

        // 表单行 (label + input)
        content.addChild(makeRow("标识符:", fieldId));
        content.addChild(makeRow("拓展包名:", fieldName));
        content.addChild(makeRow("作者:", fieldAuthor));
        content.addChild(makeRow("版本:", fieldVersion));
        content.addChild(makeRow("相关链接:", fieldLink));
        content.addChild(makeRow("描述:", fieldDesc));

        // 封面 PNG 行: [label 60] [选择图片... 90] [清除 50]
        UIElement pngRow = new UIElement();
        pngRow.layout(l -> l
            .widthPercent(100)
            .height(22)
            .flexDirection(FlexDirection.ROW)
            .gapAll(8)
            .alignItems(AlignItems.CENTER)
        );
        Label pngLabel = new Label();
        pngLabel.setText(Component.literal("封面PNG:").withStyle(ChatFormatting.GRAY));
        pngLabel.textStyle(t -> t.textAlignHorizontal(Horizontal.LEFT));
        pngLabel.layout(l -> l.width(60).height(18));
        pngRow.addChild(pngLabel);

        Button btnChoosePng = new Button();
        btnChoosePng.setText(Component.literal("选择图片..."));
        btnChoosePng.layout(l -> l.width(90).height(18));
        btnChoosePng.setOnClick(e -> openPngChooser(state, pathLabel, statusLabel));
        pngRow.addChild(btnChoosePng);

        Button btnClearPng = new Button();
        btnClearPng.setText(Component.literal("清除"));
        btnClearPng.layout(l -> l.width(50).height(18));
        btnClearPng.setOnClick(e -> {
            state.coverPng = null;
            state.coverPngPath = "(未选择)";
            pathLabel.setText(Component.literal(state.coverPngPath).withStyle(ChatFormatting.GRAY));
        });
        pngRow.addChild(btnClearPng);
        content.addChild(pngRow);

        // 封面路径
        pathLabel.layout(l -> l.widthPercent(100).height(12));
        content.addChild(pathLabel);

        // 状态行
        statusLabel.layout(l -> l.widthPercent(100).height(12));
        content.addChild(statusLabel);

        // === ScrollerView 包裹 (456 宽, 200 高) - 留出顶部 24+28 + 底部 40 ===
        ScrollerView scrollerView = new ScrollerView();
        scrollerView.layout(l -> l
            .width(456)
            .height(200)
        );
        scrollerView.scrollerStyle(style -> style.mode(ScrollerMode.VERTICAL));
        scrollerView.addScrollViewChild(content);

        // === 根容器 (456 x 24+28+200+40=292) ===
        UIElement root = new UIElement();
        root.layout(l -> l
            .width(456)
            .height(292)
            .flexDirection(FlexDirection.COLUMN)
            .paddingAll(0)
            .gapAll(0)
        );
        root.style(s -> s.background(Sprites.BORDER));

        // 标题栏 (固定 24px) - 居中显示标题, 不放按钮 (避免被游戏顶部菜单遮挡)
        UIElement titleBar = new UIElement();
        titleBar.layout(l -> l
            .widthPercent(100)
            .height(24)
            .paddingHorizontal(8)
            .justifyContent(AlignContent.CENTER)
        );
        titleBar.style(s -> s.background(Sprites.RECT_DARK));
        Label title = new Label();
        title.setText(Component.literal(state.createMode
            ? "创建拓展包"
            : "编辑拓展包 - " + (state.editing == null ? "" : state.editing.id)));
        title.textStyle(t -> t
            .textAlignHorizontal(Horizontal.CENTER)
            .textColor(0xFFFFFFFF)
        );
        titleBar.addChild(title);
        root.addChild(titleBar);

        // 工具栏 (固定 28px) - 放 [← 返回] 和 [保存] 按钮, 不会被遮挡
        UIElement toolbar = new UIElement();
        toolbar.layout(l -> l
            .widthPercent(100)
            .height(28)
            .paddingHorizontal(8)
            .paddingVertical(4)
            .flexDirection(FlexDirection.ROW)
            .alignItems(AlignItems.CENTER)
            .justifyContent(AlignContent.SPACE_BETWEEN)
        );
        toolbar.style(s -> s.background(Sprites.RECT_DARK));
        toolbar.setOverflowVisible(false);

        Button btnBack = new Button();
        btnBack.setText(Component.literal("← 返回"));
        btnBack.layout(l -> l.width(60).height(18));
        btnBack.setOnClick(e -> {
            if (state.parent != null) {
                state.parent.onChildClosed();
            } else {
                Minecraft.getInstance().setScreen(null);
            }
        });
        toolbar.addChild(btnBack);

        Button btnSave = new Button();
        btnSave.setText(Component.literal(state.createMode ? "保存并创建" : "保存"));
        btnSave.layout(l -> l.width(90).height(18));
        btnSave.setOnClick(e -> doSave(state, fieldId, fieldName, fieldAuthor,
            fieldVersion, fieldLink, fieldDesc, statusLabel));
        toolbar.addChild(btnSave);

        root.addChild(toolbar);

        // 滚动主区
        root.addChild(scrollerView);

        // 底部按钮栏 (固定 40px, 右对齐) - 保留作为备选入口
        UIElement buttonBar = new UIElement();
        buttonBar.layout(l -> l
            .widthPercent(100)
            .height(40)
            .paddingAll(8)
            .gapAll(8)
            .flexDirection(FlexDirection.ROW)
            .justifyContent(AlignContent.FLEX_END)
        );

        Button btnCancel = new Button();
        btnCancel.setText(Component.literal("取消"));
        btnCancel.layout(l -> l.width(80).height(24));
        btnCancel.setOnClick(e -> {
            if (state.parent != null) {
                state.parent.onChildClosed();
            } else {
                Minecraft.getInstance().setScreen(null);
            }
        });
        buttonBar.addChild(btnCancel);
        root.addChild(buttonBar);

        PrefabCustomAddon.LOGGER.info("[CREATOR] GuiCreatePackInfo open: createMode={} editingId={}",
            state.createMode,
            state.editing == null ? "(new)" : state.editing.id);

        return ModularUI.of(UI.of(root,
            StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)));
    }

    // === 辅助 ===

    /** 构造一行: 左 60px 标签 + 右 flex 输入框 */
    private static UIElement makeRow(String labelText, TextField input) {
        UIElement row = new UIElement();
        row.layout(l -> l
            .widthPercent(100)
            .height(22)
            .flexDirection(FlexDirection.ROW)
            .gapAll(8)
            .alignItems(AlignItems.CENTER)
        );
        Label label = new Label();
        label.setText(Component.literal(labelText).withStyle(ChatFormatting.GRAY));
        label.textStyle(t -> t.textAlignHorizontal(Horizontal.LEFT));
        label.layout(l -> l.width(60).height(18));
        row.addChild(label);

        input.layout(l -> l.flex(1).height(18));
        row.addChild(input);
        return row;
    }

    /** 用 PowerShell 调 Windows 原生 OpenFileDialog 选 PNG */
    private static void openPngChooser(FormState state, Label pathLabel, Label statusLabel) {
        setStatus(statusLabel, "正在打开文件选择器...", ChatFormatting.AQUA);
        SystemFilePicker.openAsync("选择封面 PNG", "png", r -> {
            if (r.isOk()) {
                handlePngSelected(state, r.file, pathLabel, statusLabel);
            } else if (r.isCancelled()) {
                setStatus(statusLabel, "\u2717 已取消", ChatFormatting.GRAY);
            } else {
                setStatus(statusLabel, "\u2717 选择器错误: " + r.message, ChatFormatting.RED);
            }
        });
    }

    private static void handlePngSelected(FormState state, File f, Label pathLabel, Label statusLabel) {
        try {
            byte[] data = Files.readAllBytes(f.toPath());
            try (ByteArrayInputStream bis = new ByteArrayInputStream(data)) {
                BufferedImage img = ImageIO.read(bis);
                if (img == null) {
                    setStatus(statusLabel, "\u2717 无效的 PNG 文件", ChatFormatting.RED);
                    return;
                }
            }
            state.coverPng = data;
            state.coverPngPath = f.getAbsolutePath();
            String shortPath = state.coverPngPath;
            if (shortPath.length() > 50) {
                shortPath = "..." + shortPath.substring(shortPath.length() - 47);
            }
            pathLabel.setText(Component.literal(shortPath).withStyle(ChatFormatting.GREEN));
            setStatus(statusLabel, "\u2713 已选择封面: " + f.getName(), ChatFormatting.GREEN);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[CREATOR] read PNG failed", t);
            setStatus(statusLabel, "\u2717 读取失败: " + t.getMessage(), ChatFormatting.RED);
        }
    }

    private static void setStatus(Label statusLabel, String msg, ChatFormatting color) {
        statusLabel.setText(Component.literal(msg).withStyle(color));
    }

    private static void doSave(FormState state,
                               TextField fieldId, TextField fieldName, TextField fieldAuthor,
                               TextField fieldVersion, TextField fieldLink, TextField fieldDesc,
                               Label statusLabel) {
        String id = fieldId.getValue().trim();
        String name = fieldName.getValue().trim();
        if (id.isEmpty()) {
            setStatus(statusLabel, "\u2717 标识符不能为空", ChatFormatting.RED);
            return;
        }
        if (state.createMode && !id.matches("[A-Za-z0-9_\\-]+")) {
            setStatus(statusLabel, "\u2717 标识符只能含字母数字下划线连字符", ChatFormatting.RED);
            return;
        }
        if (name.isEmpty()) {
            setStatus(statusLabel, "\u2717 包名不能为空", ChatFormatting.RED);
            return;
        }

        String author = fieldAuthor.getValue().trim();
        String version = fieldVersion.getValue().trim();
        String link = fieldLink.getValue().trim();
        // deps 字段在 UI 上不显示, 永远传空 (子建筑依赖在导出时自动合并)
        String deps = "";
        String desc = fieldDesc.getValue().trim();

        try {
            if (state.createMode) {
                PackCreator.getInstance().createPack(id, name, author, version, deps, link, desc, state.coverPng);
                setStatus(statusLabel, "\u2713 已创建拓展包: " + id, ChatFormatting.GREEN);
            } else {
                PackCreator.getInstance().updatePack(id, name, author, version, deps, link, desc, state.coverPng);
                setStatus(statusLabel, "\u2713 已保存: " + id, ChatFormatting.GREEN);
            }
            if (state.parent != null) {
                state.parent.onChildClosed();
                return;
            }
            // 没有 parent, 直接关闭 (X 键直接打开的情况下)
            // 800ms 后关闭, 让用户看到 "✓ 已创建" 提示
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(800);
                } catch (InterruptedException ignored) {}
                Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(null));
            });
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.error("[CREATOR] save pack failed", e);
            setStatus(statusLabel, "\u2717 保存失败: " + e.getMessage(), ChatFormatting.RED);
        }
    }

    private static String playerName() {
        if (Minecraft.getInstance().player != null) {
            return Minecraft.getInstance().player.getName().getString();
        }
        return "anonymous";
    }

    private static String safeStr(String s) {
        return s == null ? "" : s;
    }
}
