package com.prefab.addon.client.gui;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.work.PackCreator;
import com.prefab.gui.GuiBase;
import com.prefab.gui.controls.ExtendedButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import javax.imageio.ImageIO;
import java.util.concurrent.CompletableFuture;

/**
 * 创建 / 编辑 拓展包信息表单。
 *
 * 字段顺序参考 d:\MC-Prefab-Main\预制建筑拓展包示例\information\test1.txt:
 *   作者 / 版本 / 标识符 / 拓展包名 / 相关链接 / 依赖模组 / 描述
 *
 * 编辑模式: 传入 editing != null
 * 创建模式: editing == null
 */
public class GuiCreatePackInfo extends GuiBase {

    private final GuiExtensionPackCreator parent;
    private final PackCreator.PackWorkInfo editing;  // null = 创建模式
    private final boolean createMode;

    // 输入框
    private EditBox fieldId;
    private EditBox fieldName;
    private EditBox fieldAuthor;
    private EditBox fieldVersion;
    private EditBox fieldLink;
    private EditBox fieldDeps;
    private EditBox fieldDesc;

    // 按钮
    private ExtendedButton btnSave;
    private ExtendedButton btnCancel;
    private ExtendedButton btnChoosePng;
    private ExtendedButton btnClearPng;

    // 选中的 PNG 数据
    private byte[] coverPng = null;
    private String coverPngPath = "(未选择)";

    // 状态信息
    private String statusMessage = null;
    private int statusColor = 0x55FF55;
    private int statusTick = 0;

    // 表单布局起点
    private int formX, formY;
    private int grayBoxX, grayBoxY;
    private int panelW, panelH;
    private static final int FIELD_W = 280;
    private static final int FIELD_H = 16;
    private static final int ROW_H = 20;        // 紧凑行高 (原 24, 缩小)
    private static final int GAP_BEFORE_SAVE = 6; // PNG 行到保存按钮的间隔

    public GuiCreatePackInfo(PackCreator.PackWorkInfo editing, GuiExtensionPackCreator parent) {
        super(editing == null ? "创建拓展包" : "编辑拓展包");
        this.editing = editing;
        this.parent = parent;
        this.createMode = (editing == null);
    }

    public static void open(PackCreator.PackWorkInfo editing, GuiExtensionPackCreator parent) {
        Minecraft.getInstance().setScreen(new GuiCreatePackInfo(editing, parent));
    }

    @Override
    protected void Initialize() {
        super.Initialize();
        // 紧凑布局: 8 行 (7 字段 + 1 PNG) + 顶部标题 + 底部保存按钮
        // 总高度 = 标题(20) + 8*20 + 间距(6) + 保存按钮(20) + 余量(10) = 216
        this.panelW = 380;
        this.panelH = 230;
        this.modifiedInitialXAxis = panelW / 2;
        this.modifiedInitialYAxis = panelH / 2;
        this.imagePanelWidth = panelW;
        this.imagePanelHeight = panelH;
        this.shownImageHeight = 1;
        this.shownImageWidth = 1;

        this.grayBoxX = (this.width / 2) - this.modifiedInitialXAxis;
        this.grayBoxY = (this.height / 2) - this.modifiedInitialYAxis;

        // 表单起点 (左侧标签 + 右侧输入框)
        this.formX = grayBoxX + 14;
        this.formY = grayBoxY + 24;  // 标题占 20px

        int inputX = formX + 70;
        int y = formY;

        // 标识符
        this.fieldId = addField(inputX, y, FIELD_W - 70);
        if (createMode) {
            this.fieldId.setHint(Component.literal("标识符 (字母/数字/_/-)"));
            this.fieldId.setValue("");
        } else {
            this.fieldId.setValue(editing.id);
            this.fieldId.setEditable(false);
            this.fieldId.setBordered(false);
        }
        y += ROW_H;

        // 拓展包名
        this.fieldName = addField(inputX, y, FIELD_W - 70);
        this.fieldName.setHint(Component.literal("中文名称"));
        y += ROW_H;

        // 作者
        this.fieldAuthor = addField(inputX, y, FIELD_W - 70);
        this.fieldAuthor.setHint(Component.literal("你的名字"));
        y += ROW_H;

        // 版本
        this.fieldVersion = addField(inputX, y, FIELD_W - 70);
        this.fieldVersion.setHint(Component.literal("例如 1.0.0"));
        y += ROW_H;

        // 相关链接
        this.fieldLink = addField(inputX, y, FIELD_W - 70);
        this.fieldLink.setHint(Component.literal("https://..."));
        y += ROW_H;

        // 依赖模组
        this.fieldDeps = addField(inputX, y, FIELD_W - 70);
        this.fieldDeps.setHint(Component.literal("建筑用到了哪些模组里的物品就填哪些模组"));
        y += ROW_H;

        // 描述
        this.fieldDesc = addField(inputX, y, FIELD_W - 70);
        this.fieldDesc.setHint(Component.literal("这个拓展包是做什么的"));
        y += ROW_H;

        // 封面 PNG 行
        this.btnChoosePng = this.createAndAddButton(inputX, y - 1, 90, 18, "选择图片...");
        this.btnClearPng = this.createAndAddButton(inputX + 95, y - 1, 45, 18, "清除");
        y += ROW_H - 2;

        // 预填编辑值
        if (!createMode) {
            this.fieldName.setValue(safeStr(editing.name));
            this.fieldAuthor.setValue(safeStr(editing.author));
            this.fieldVersion.setValue(safeStr(editing.version));
            this.fieldLink.setValue(safeStr(editing.link));
            this.fieldDeps.setValue(safeStr(editing.dependencies));
            this.fieldDesc.setValue(safeStr(editing.description));
        }

        // 保存/创建按钮 (放在 PNG 行下方, 一定可见)
        int btnY = formY + 8 * ROW_H + GAP_BEFORE_SAVE - 1;
        this.btnSave = this.createAndAddButton(grayBoxX + 175, btnY, 90, 20,
            createMode ? "保存并创建" : "保存");
        this.btnCancel = this.createAndAddButton(grayBoxX + 270, btnY, 90, 20, "取消");
    }

    private EditBox addField(int x, int y, int w) {
        EditBox box = new EditBox(this.font, x, y, w, FIELD_H, Component.literal(""));
        box.setMaxLength(512);
        this.addRenderableWidget(box);
        return box;
    }

    private static String safeStr(String s) { return s == null ? "" : s; }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {  // GLFW_KEY_ESCAPE
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void tick() {
        super.tick();
        if (statusTick > 0) statusTick--;
    }

    private void setStatus(String msg, int color) {
        this.statusMessage = msg;
        this.statusColor = color;
        this.statusTick = 100;
    }

    @Override
    protected void preButtonRender(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, float partialTicks) {
        this.drawControlBackground(guiGraphics, x, y, this.imagePanelWidth, this.imagePanelHeight);
    }

    @Override
    protected void postButtonRender(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, float partialTicks) {
        // 标题
        guiGraphics.drawCenteredString(this.font,
            createMode ? "创建拓展包" : "编辑拓展包 - " + (editing == null ? "" : editing.id),
            this.getCenteredXAxis(), y + 6, this.textColor);

        // 字段标签
        guiGraphics.drawString(this.font, "标识符:", formX, formY + 4, 0xAAAAAA);
        guiGraphics.drawString(this.font, "拓展包名:",   formX, formY + ROW_H * 1 + 4, 0xAAAAAA);
        guiGraphics.drawString(this.font, "作者:",       formX, formY + ROW_H * 2 + 4, 0xAAAAAA);
        guiGraphics.drawString(this.font, "版本:",       formX, formY + ROW_H * 3 + 4, 0xAAAAAA);
        guiGraphics.drawString(this.font, "相关链接:",    formX, formY + ROW_H * 4 + 4, 0xAAAAAA);
        guiGraphics.drawString(this.font, "依赖模组:",    formX, formY + ROW_H * 5 + 4, 0xAAAAAA);
        guiGraphics.drawString(this.font, "描述:",       formX, formY + ROW_H * 6 + 4, 0xAAAAAA);
        guiGraphics.drawString(this.font, "封面PNG:",    formX, formY + ROW_H * 7 + 4, 0xAAAAAA);

        // PNG 路径
        int pngY = formY + ROW_H * 7;
        int pngPathX = formX + 145;
        String pathShort = coverPngPath;
        if (pathShort.length() > 50) pathShort = "..." + pathShort.substring(pathShort.length() - 47);
        int col = coverPng == null ? 0x888888 : 0x55FF55;
        guiGraphics.drawString(this.font, pathShort, pngPathX, pngY + 4, col);

        // 状态
        if (statusMessage != null && statusTick > 0) {
            int sy = grayBoxY + panelH - 14;
            guiGraphics.drawString(this.font, statusMessage, grayBoxX + 10, sy, statusColor);
        }
    }

    @Override
    public void buttonClicked(AbstractButton button) {
        if (button == this.btnCancel) {
            this.onClose();
            return;
        }
        if (button == this.btnSave) {
            doSave();
            return;
        }
        if (button == this.btnChoosePng) {
            openPngChooser();
            return;
        }
        if (button == this.btnClearPng) {
            this.coverPng = null;
            this.coverPngPath = "(未选择)";
            return;
        }
    }

    /**
     * 用 PowerShell 调 Windows 原生 OpenFileDialog 选 PNG。
     */
    private void openPngChooser() {
        setStatus("正在打开文件选择器...", 0x55AAFF);
        SystemFilePicker.openAsync("选择封面 PNG", "png", r -> {
            if (r.isOk()) {
                handlePngSelected(r.file);
            } else if (r.isCancelled()) {
                setStatus("✗ 已取消", 0x888888);
            } else {
                setStatus("✗ 选择器错误: " + r.message, 0xFF5555);
            }
        });
    }

    private void handlePngSelected(File f) {
        try {
            byte[] data = Files.readAllBytes(f.toPath());
            try (ByteArrayInputStream bis = new ByteArrayInputStream(data)) {
                BufferedImage img = ImageIO.read(bis);
                if (img == null) {
                    setStatus("✗ 无效的 PNG 文件", 0xFF5555);
                    return;
                }
            }
            this.coverPng = data;
            this.coverPngPath = f.getAbsolutePath();
            setStatus("✓ 已选择封面: " + f.getName(), 0x55FF55);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[CREATOR] read PNG failed", t);
            setStatus("✗ 读取失败: " + t.getMessage(), 0xFF5555);
        }
    }

    private void doSave() {
        String id = fieldId.getValue().trim();
        String name = fieldName.getValue().trim();
        if (id.isEmpty()) {
            setStatus("✗ 标识符不能为空", 0xFF5555);
            return;
        }
        if (createMode && !id.matches("[A-Za-z0-9_\\-]+")) {
            setStatus("✗ 标识符只能含字母数字下划线连字符", 0xFF5555);
            return;
        }
        if (name.isEmpty()) {
            setStatus("✗ 包名不能为空", 0xFF5555);
            return;
        }

        String author = fieldAuthor.getValue().trim();
        String version = fieldVersion.getValue().trim();
        String link = fieldLink.getValue().trim();
        String deps = fieldDeps.getValue().trim();
        String desc = fieldDesc.getValue().trim();

        try {
            if (createMode) {
                PackCreator.getInstance().createPack(id, name, author, version, deps, link, desc, coverPng);
                setStatus("✓ 已创建拓展包: " + id, 0x55FF55);
            } else {
                PackCreator.getInstance().updatePack(id, name, author, version, deps, link, desc, coverPng);
                setStatus("✓ 已保存: " + id, 0x55FF55);
            }
            if (parent != null) parent.onChildClosed();
            CompletableFuture.runAsync(() -> {
                try { Thread.sleep(800); } catch (InterruptedException ignored) {}
                Minecraft.getInstance().execute(this::onClose);
            });
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.error("[CREATOR] save pack failed", e);
            setStatus("✗ 保存失败: " + e.getMessage(), 0xFF5555);
        }
    }
}
