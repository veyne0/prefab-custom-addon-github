package com.prefab.addon.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.work.NbtStructureParser;
import com.prefab.addon.work.PackCreator;
import com.prefab.gui.GuiBase;
import com.prefab.gui.controls.ExtendedButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;

/**
 * 创建 / 编辑 建筑信息表单。
 *
 * 字段顺序参考 d:\MC-Prefab-Main\预制建筑拓展包示例\construction\example.txt:
 *   作者 / 建筑名 / 尺寸 / 描述 / 建筑标识符 / 依赖模组
 *
 * 创建流程:
 *   1) 必须先选 NBT 文件 → 解析尺寸 (sizeX x sizeY x sizeZ) 和非 minecraft 模组列表
 *   2) 自动填到 尺寸 和 依赖模组 字段 (用户可改)
 *   3) 填剩余信息 + 选 PNG
 *   4) 保存: nbt + png + txt 三个文件, 自动更新父拓展包依赖
 */
public class GuiCreateBuildingInfo extends GuiBase {

    private final GuiExtensionPackCreator parent;
    private final String packId;
    private final PackCreator.BuildingWorkInfo editing;  // null = 创建模式

    // 输入框
    private EditBox fieldId;
    private EditBox fieldName;
    private EditBox fieldAuthor;
    private EditBox fieldSize;
    private EditBox fieldDeps;
    private EditBox fieldDesc;

    // 按钮
    private ExtendedButton btnSave;
    private ExtendedButton btnCancel;
    private ExtendedButton btnChooseNbt;
    private ExtendedButton btnChoosePng;
    private ExtendedButton btnClearPng;

    // 已选文件
    private byte[] nbtData = null;
    private String nbtPath = "(未选择)";
    private byte[] pngData = null;
    private String pngPath = "(未选择)";

    // NBT 解析结果 (用于展示给用户)
    private NbtStructureParser.NbtInfo nbtInfo = null;
    private boolean nbtParsed = false;

    // PNG 预览纹理
    private ResourceLocation pngTexture = null;
    private byte[] lastPngData = null;

    // 状态
    private String statusMessage = null;
    private int statusColor = 0x55FF55;
    private int statusTick = 0;

    // 布局
    private int formX, formY;
    private int grayBoxX, grayBoxY;
    private int panelW, panelH;
    private static final int FIELD_W = 200;
    private static final int FIELD_H = 16;
    private static final int ROW_H = 19;
    private static final int GAP_BEFORE_SAVE = 6;

    public GuiCreateBuildingInfo(String packId, PackCreator.BuildingWorkInfo editing,
                                  GuiExtensionPackCreator parent) {
        super("Building Info");
        this.packId = packId;
        this.editing = editing;
        this.parent = parent;
    }

    public static void open(String packId, PackCreator.BuildingWorkInfo editing,
                            GuiExtensionPackCreator parent) {
        Minecraft.getInstance().setScreen(new GuiCreateBuildingInfo(packId, editing, parent));
    }

    @Override
    protected void Initialize() {
        super.Initialize();
        this.panelW = 440;
        this.panelH = 280;
        this.modifiedInitialXAxis = panelW / 2;
        this.modifiedInitialYAxis = panelH / 2;
        this.imagePanelWidth = panelW;
        this.imagePanelHeight = panelH;
        this.shownImageHeight = 1;
        this.shownImageWidth = 1;

        this.grayBoxX = (this.width / 2) - this.modifiedInitialXAxis;
        this.grayBoxY = (this.height / 2) - this.modifiedInitialYAxis;

        this.formX = grayBoxX + 10;
        this.formY = grayBoxY + 22;

        int labelW = 56;
        int inputX = formX + labelW;
        int y = formY;

        // 建筑标识符
        this.fieldId = addField(inputX, y, FIELD_W);
        this.fieldId.setHint(Component.literal("建筑标识符 (字母/数字/_/-)"));
        y += ROW_H;

        // 建筑名
        this.fieldName = addField(inputX, y, FIELD_W);
        this.fieldName.setHint(Component.literal("中文名称"));
        y += ROW_H;

        // 作者
        this.fieldAuthor = addField(inputX, y, FIELD_W);
        this.fieldAuthor.setHint(Component.literal("你的名字"));
        y += ROW_H;

        // 尺寸 (auto-filled by NBT)
        this.fieldSize = addField(inputX, y, FIELD_W);
        this.fieldSize.setHint(Component.literal("XxYxZ (选 NBT 后自动填)"));
        y += ROW_H;

        // 依赖模组 (auto-filled by NBT, minecraft 排除)
        this.fieldDeps = addField(inputX, y, FIELD_W);
        this.fieldDeps.setHint(Component.literal("建筑用到了哪些模组里的物品就填哪些模组"));
        y += ROW_H;

        // 描述
        this.fieldDesc = addField(inputX, y, FIELD_W);
        this.fieldDesc.setHint(Component.literal("建筑的描述"));
        y += ROW_H;

        // NBT 文件选择
        this.btnChooseNbt = this.createAndAddButton(inputX, y - 1, 90, 18, "选择NBT...");
        y += ROW_H - 2;

        // PNG 文件选择
        this.btnChoosePng = this.createAndAddButton(inputX, y - 1, 90, 18, "选择PNG...");
        this.btnClearPng = this.createAndAddButton(inputX + 95, y - 1, 45, 18, "清除");
        y += ROW_H - 2;

        // 预填编辑值
        if (editing != null) {
            this.fieldId.setValue(editing.id);
            this.fieldId.setEditable(false);
            this.fieldId.setBordered(false);
            this.fieldName.setValue(safeStr(editing.name));
            this.fieldAuthor.setValue(safeStr(editing.author));
            this.fieldSize.setValue(safeStr(editing.size));
            this.fieldDeps.setValue(safeStr(editing.dependencies));
            this.fieldDesc.setValue(safeStr(editing.description));
            if (Files.exists(editing.nbt)) {
                try {
                    this.nbtData = Files.readAllBytes(editing.nbt);
                    this.nbtPath = editing.nbt.toString();
                    this.nbtParsed = true;
                    try { this.nbtInfo = NbtStructureParser.parse(editing.nbt); }
                    catch (Exception ignored) {}
                } catch (Exception ignored) {}
            }
            if (Files.exists(editing.png)) {
                try {
                    this.pngData = Files.readAllBytes(editing.png);
                    this.pngPath = editing.png.toString();
                    loadPngTexture(this.pngData);
                } catch (Exception ignored) {}
            }
        } else {
            this.fieldDeps.setValue("prefab");
        }

        // 保存/创建按钮
        int btnY = formY + 8 * ROW_H + GAP_BEFORE_SAVE;
        this.btnSave = this.createAndAddButton(grayBoxX + 200, btnY, 90, 20,
            editing == null ? "保存并创建" : "保存");
        this.btnCancel = this.createAndAddButton(grayBoxX + 295, btnY, 90, 20, "取消");
    }

    /** 加载 PNG 预览纹理 */
    private void loadPngTexture(byte[] data) {
        if (data == null || data.length == 0) {
            pngTexture = null;
            lastPngData = null;
            return;
        }
        if (lastPngData == data) return;  // 同一对象, 不重新加载
        // 释放旧纹理
        if (pngTexture != null) {
            Minecraft.getInstance().getTextureManager().release(pngTexture);
            pngTexture = null;
        }
        try {
            com.mojang.blaze3d.platform.NativeImage ni;
            try (var in = new java.io.ByteArrayInputStream(data)) {
                ni = com.mojang.blaze3d.platform.NativeImage.read(in);
            }
            if (ni == null) return;
            DynamicTexture tex = new DynamicTexture(ni);
            String texId = (editing != null ? editing.id : "new") + "_" + System.nanoTime();
            pngTexture = Minecraft.getInstance().getTextureManager()
                .register("prefab_addon/bpng_" + texId, tex);
            lastPngData = data;
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[CREATOR] load building PNG failed", t);
        }
    }

    private EditBox addField(int x, int y, int w) {
        EditBox box = new EditBox(this.font, x, y, w, FIELD_H, Component.literal(""));
        box.setMaxLength(512);
        this.addRenderableWidget(box);
        return box;
    }

    private static String safeStr(String s) { return s == null ? "" : s; }

    private void setStatus(String msg, int color) {
        this.statusMessage = msg;
        this.statusColor = color;
        this.statusTick = 100;
    }

    @Override
    public void tick() {
        super.tick();
        if (statusTick > 0) statusTick--;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected void preButtonRender(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, float partialTicks) {
        this.drawControlBackground(guiGraphics, x, y, this.imagePanelWidth, this.imagePanelHeight);
    }

    @Override
    protected void postButtonRender(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, float partialTicks) {
        String title = (editing == null ? "创建建筑" : "编辑建筑 - " + editing.id) + " (拓展包: " + packId + ")";
        guiGraphics.drawCenteredString(this.font, title, this.getCenteredXAxis(), y + 6, this.textColor);

        guiGraphics.drawString(this.font, "建筑标识符:", formX, formY + 4, 0xAAAAAA);
        guiGraphics.drawString(this.font, "建筑名:",     formX, formY + ROW_H * 1 + 4, 0xAAAAAA);
        guiGraphics.drawString(this.font, "作者:",       formX, formY + ROW_H * 2 + 4, 0xAAAAAA);
        guiGraphics.drawString(this.font, "尺寸:",       formX, formY + ROW_H * 3 + 4, 0xAAAAAA);
        guiGraphics.drawString(this.font, "依赖模组:",    formX, formY + ROW_H * 4 + 4, 0xAAAAAA);
        guiGraphics.drawString(this.font, "描述:",       formX, formY + ROW_H * 5 + 4, 0xAAAAAA);

        // NBT 路径 (选择 NBT 按钮右侧)
        int nbtPathY = formY + ROW_H * 6;
        int nbtPathX = formX + 56 + 90 + 6;  // 按钮右边 6px
        String nbtShort = nbtPath;
        if (nbtShort.length() > 22) nbtShort = "..." + nbtShort.substring(nbtShort.length() - 19);
        int nbtCol = nbtData == null ? 0xFF5555 : 0x55FF55;
        guiGraphics.drawString(this.font, nbtShort, nbtPathX, nbtPathY + 4, nbtCol);

        // PNG 路径 (选择 PNG + 清除 按钮右侧)
        int pngPathY = formY + ROW_H * 7;
        int pngPathX = formX + 56 + 95 + 45 + 6;  // PNG按钮+清除按钮右边 6px
        String pngShort = pngPath;
        if (pngShort.length() > 18) pngShort = "..." + pngShort.substring(pngShort.length() - 15);
        int pngCol = pngData == null ? 0x888888 : 0x55FF55;
        guiGraphics.drawString(this.font, pngShort, pngPathX, pngPathY + 4, pngCol);

        // NBT 解析结果
        if (nbtParsed && nbtInfo != null) {
            int infoY = formY + 8 * ROW_H + 2;
            String modInfo;
            if (nbtInfo.modIds.isEmpty()) {
                modInfo = "已解析: " + nbtInfo.sizeString() + " | 仅 minecraft/prefab 方块";
            } else {
                modInfo = "已解析: " + nbtInfo.sizeString() + " | 模组: " + String.join(", ", nbtInfo.modIds);
            }
            if (modInfo.length() > 65) modInfo = modInfo.substring(0, 62) + "...";
            guiGraphics.drawString(this.font, modInfo, formX, infoY, 0x55FF55);
        }

        // PNG 预览 (右上角 96x96)
        int pcx = grayBoxX + panelW - 110;
        int pcy = grayBoxY + 26;
        int pcs = 96;
        guiGraphics.fill(pcx - 2, pcy - 2, pcx + pcs + 2, pcy + pcs + 2, 0xFF555555);
        if (pngTexture != null) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            try {
                guiGraphics.blit(pngTexture, pcx, pcy, 0, 0, pcs, pcs, pcs, pcs);
            } catch (Throwable t) { /* ignore */ }
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.disableBlend();
        } else {
            guiGraphics.fill(pcx, pcy, pcx + pcs, pcy + pcs, 0xFF222222);
            guiGraphics.drawCenteredString(this.font, "(无预览图)", pcx + pcs / 2, pcy + pcs / 2 - 4, 0x888888);
        }
        guiGraphics.drawCenteredString(this.font, "预览", pcx + pcs / 2, pcy + pcs + 4, 0x888888);

        if (statusMessage != null && statusTick > 0) {
            int sy = grayBoxY + panelH - 14;
            guiGraphics.drawString(this.font, statusMessage, grayBoxX + 10, sy, statusColor);
        }
    }

    @Override
    public void onClose() {
        if (pngTexture != null) {
            Minecraft.getInstance().getTextureManager().release(pngTexture);
            pngTexture = null;
        }
        super.onClose();
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
        if (button == this.btnChooseNbt) {
            openNbtChooser();
            return;
        }
        if (button == this.btnChoosePng) {
            openPngChooser();
            return;
        }
        if (button == this.btnClearPng) {
            this.pngData = null;
            this.pngPath = "(未选择)";
            if (pngTexture != null) {
                Minecraft.getInstance().getTextureManager().release(pngTexture);
                pngTexture = null;
            }
            lastPngData = null;
            return;
        }
    }

    /**
     * 用 PowerShell 调 Windows 原生 OpenFileDialog 选 NBT。
     * PowerShell 用 STA 线程模式 (OpenFileDialog 是 STA COM).
     */
    private void openNbtChooser() {
        setStatus("正在打开文件选择器...", 0x55AAFF);
        SystemFilePicker.openAsync("选择 Minecraft 结构 NBT", "nbt", r -> {
            if (r.isOk()) {
                handleNbtSelected(r.file);
            } else if (r.isCancelled()) {
                setStatus("✗ 已取消", 0x888888);
            } else {
                setStatus("✗ 选择器错误: " + r.message, 0xFF5555);
            }
        });
    }

    private void handleNbtSelected(File f) {
        try {
            byte[] data = Files.readAllBytes(f.toPath());
            NbtStructureParser.NbtInfo info;
            try {
                info = NbtStructureParser.parse(f.toPath());
            } catch (Exception e) {
                setStatus("✗ NBT 解析失败: " + e.getMessage(), 0xFF5555);
                return;
            }
            this.nbtData = data;
            this.nbtPath = f.getAbsolutePath();
            this.nbtInfo = info;
            this.nbtParsed = true;
            if (info.sizeX > 0) {
                this.fieldSize.setValue(info.sizeString());
            }
            java.util.LinkedHashSet<String> deps = new java.util.LinkedHashSet<>();
            String existing = fieldDeps.getValue().trim();
            if (!existing.isEmpty()) {
                for (String d : existing.split("[,，;；\\s]+")) {
                    if (!d.isEmpty()) deps.add(d.trim());
                }
            }
            for (String m : info.modIds) deps.add(m);
            deps.add("prefab");
            deps.remove("minecraft");
            this.fieldDeps.setValue(String.join(", ", deps));

            String modInfo = info.modIds.isEmpty()
                ? "仅 minecraft/prefab 方块"
                : "识别模组: " + String.join(", ", info.modIds);
            setStatus("✓ " + f.getName() + " (" + info.sizeString() + ") | " + modInfo, 0x55FF55);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[CREATOR] read NBT failed", t);
            setStatus("✗ 读取失败: " + t.getMessage(), 0xFF5555);
        }
    }

    /**
     * 用 PowerShell 调 Windows 原生 OpenFileDialog 选 PNG。
     * PowerShell 用 STA 线程模式 (OpenFileDialog 是 STA COM).
     */
    private void openPngChooser() {
        setStatus("正在打开文件选择器...", 0x55AAFF);
        SystemFilePicker.openAsync("选择建筑预览 PNG", "png", r -> {
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
            try (java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(data)) {
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(bis);
                if (img == null) {
                    setStatus("✗ 无效的 PNG 文件", 0xFF5555);
                    return;
                }
            }
            this.pngData = data;
            this.pngPath = f.getAbsolutePath();
            loadPngTexture(this.pngData);   // ← 立即更新预览
            setStatus("✓ 已选择预览: " + f.getName(), 0x55FF55);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[CREATOR] read PNG failed", t);
            setStatus("✗ 读取失败: " + t.getMessage(), 0xFF5555);
        }
    }

    private static File findStartDir(String... names) {
        String home = System.getProperty("user.home");
        for (String n : names) {
            File f = new File(home, n);
            if (f.isDirectory()) return f;
        }
        return new File(home);
    }

    private void doSave() {
        String id = fieldId.getValue().trim();
        String name = fieldName.getValue().trim();
        String size = fieldSize.getValue().trim();
        if (id.isEmpty()) { setStatus("✗ 建筑标识符不能为空", 0xFF5555); return; }
        if (!id.matches("[A-Za-z0-9_\\-]+")) {
            setStatus("✗ 标识符只能含字母数字下划线连字符", 0xFF5555); return;
        }
        if (name.isEmpty()) { setStatus("✗ 建筑名不能为空", 0xFF5555); return; }
        if (size.isEmpty()) { setStatus("✗ 尺寸不能为空 (请先选 NBT)", 0xFF5555); return; }
        if (nbtData == null) { setStatus("✗ 必须先选择 NBT 文件", 0xFF5555); return; }

        String author = fieldAuthor.getValue().trim();
        String deps = fieldDeps.getValue().trim();
        String desc = fieldDesc.getValue().trim();

        try {
            PackCreator.getInstance().saveBuilding(packId, id, name, author, size, deps, desc, nbtData, pngData);
            setStatus("✓ 已保存建筑: " + id + " (依赖已合并到拓展包)", 0x55FF55);
            if (parent != null) parent.onChildClosed();
            CompletableFuture.runAsync(() -> {
                try { Thread.sleep(800); } catch (InterruptedException ignored) {}
                Minecraft.getInstance().execute(this::onClose);
            });
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.error("[CREATOR] save building failed", e);
            setStatus("✗ 保存失败: " + e.getMessage(), 0xFF5555);
        }
    }
}
