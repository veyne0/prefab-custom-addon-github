package com.prefab.addon.client.gui;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollDisplay;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ProgressBar;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.ObjToSchematicConverter;
import com.prefab.addon.work.NbtFormatConverter;
import com.prefab.addon.work.NbtStructureParser;
import com.prefab.addon.work.PackCreator;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 创建 / 编辑 建筑信息表单 (LDLib2 重写版, 中间无 3D 预览).
 *
 * 字段顺序参考 d:\MC-Prefab-Main\预制建筑拓展包示例\construction\example.txt:
 *   作者 / 建筑名 / 尺寸 / 描述 / 建筑标识符 / 依赖模组
 *
 * 创建流程:
 *   1) 选 NBT 文件 (或游戏中选区) → 解析尺寸, 非 minecraft 模组, meta 信息
 *   2) 填剩余信息
 *   3) 保存: nbt + txt 两个文件, 自动更新父拓展包依赖
 *
 * 注意: 此界面没有 3D 预览. 玩家可以在游戏内右键自定义蓝图查看 3D 预览.
 */
public final class GuiCreateBuildingInfo {

    // === 状态 ===
    private static String currentPackId;
    private static PackCreator.BuildingWorkInfo editing;
    private static GuiExtensionPackCreator parent;

    // === 表单字段值 (static, 用于 open() → createUI() 之间共享) ===
    private static String fieldIdValue = "";
    private static String fieldNameValue = "";
    private static String fieldAuthorValue = "";
    private static String fieldSizeValue = "";
    private static String fieldFormatValue = "";  // 蓝图格式: nbt / litematic / schem / 未知
    private static String fieldDepsValue = "";
    private static String fieldDescValue = "";
    /** 蓝图显示图标 - 物品 id, 如 "minecraft:stone". 空 = 使用默认图标. */
    private static String fieldIconValue = "";
    /** 待保存的图标图片 (PNG/JPG) 字节, 保存时写到 construction/<id>.png 覆盖原图. null = 未改. */
    private static byte[] fieldIconData = null;
    /** 当前图标文件路径 (UI 显示用) - 用于选择图片后提示玩家"已选: xxx.png". */
    private static String fieldIconPath = "";

    // === 已选 NBT ===
    private static byte[] nbtData = null;
    private static String nbtPath = "";
    private static NbtStructureParser.NbtInfo nbtInfo = null;

    // === 状态消息 ===
    private static String statusMessage = null;
    private static int statusColor = 0x55FF55;
    private static int statusTick = 0;

    // === UI 引用 ===
    private static TextElement statusEl;
    private static TextElement depEl;
    private static TextElement sizeEl;
    private static TextElement formatEl;  // 蓝图格式 TextElement 引用
    private static TextElement nbtPathEl;
    private static TextElement iconEl;     // 当前已选图标 TextElement 引用
    // 可编辑字段的 TextField 引用 - 用于 litematica 加载后自动填 name/author/desc 到 UI
    private static TextField idTf;
    private static TextField nameTf;
    private static TextField authorTf;
    private static TextField descTf;

    private GuiCreateBuildingInfo() {}

    /**
     * 打开创建/编辑建筑表单.
     * @param preserved 若非 null, 表示从游戏中选区返回, 不要 reset 用户已填的字段
     */
    public static void open(String packId, PackCreator.BuildingWorkInfo editingInfo,
                            GuiExtensionPackCreator parentGui) {
        open(packId, editingInfo, parentGui, null);
    }

    /**
     * 打开表单, 可选保留用户已填的字段值 (用于游戏中选区返回时恢复).
     */
    public static void open(String packId, PackCreator.BuildingWorkInfo editingInfo,
                            GuiExtensionPackCreator parentGui, PreservedFieldValues preserved) {
        if (preserved == null) {
            resetState();
        }
        currentPackId = packId;
        editing = editingInfo;
        parent = parentGui;

        // 预填字段 (编辑模式 或 preserved 恢复)
        if (preserved != null) {
            // 从游戏中选区返回: 恢复用户已填的字段 (id/name/author/desc)
            // size/deps 由 applyNbtFromRegion 处理 (新 NBT 数据) 或保持 cancel 时的原值
            // 绝对不要用 preserved.size/deps 覆盖 NBT 解析出的新尺寸和依赖
            fieldIdValue = preserved.id;
            fieldNameValue = preserved.name;
            fieldAuthorValue = preserved.author;
            fieldDescValue = preserved.desc;
            // nbtData/nbtPath/nbtInfo/fieldSizeValue/fieldDepsValue 已在 applyNbtFromRegion 中赋值
        } else if (editingInfo != null) {
            fieldIdValue = safeStr(editingInfo.id);
            fieldNameValue = safeStr(editingInfo.name);
            fieldAuthorValue = safeStr(editingInfo.author);
            fieldSizeValue = safeStr(editingInfo.size);
            fieldDepsValue = safeStr(editingInfo.dependencies);
            fieldDescValue = safeStr(editingInfo.description);
            // 编辑模式: 如果该建筑已有 PNG 图标, 显示文件名, 让玩家知道当前图标
            if (editingInfo.png != null && Files.exists(editingInfo.png)) {
                fieldIconPath = editingInfo.png.getFileName().toString();
            } else {
                fieldIconPath = com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.empty_value");
            }
            if (Files.exists(editingInfo.nbt)) {
                try {
                    nbtData = Files.readAllBytes(editingInfo.nbt);
                    nbtPath = editingInfo.nbt.toString();
                    nbtInfo = NbtStructureParser.parse(nbtData);
                    // 蓝图格式: 按 NBT 文件名后缀
                    fieldFormatValue = detectFormatFromFileName(editingInfo.nbt.getFileName().toString());
                } catch (Exception ignored) {}
            }
        } else {
            fieldDepsValue = "prefab";
        }

        ModularUI ui = createUI();
        String title = (editingInfo == null
            ? com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.window_title_create")
            : com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.window_title_edit", editingInfo.id))
            + " " + com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.window_title_pack", packId);
        Minecraft.getInstance().setScreen(new ModularUIScreen(ui, Component.literal(title)));
    }

    /**
     * 用当前 static 字段状态重建 UI 并显示.
     * 用于"在不动用 resetState 的前提下刷新屏幕"的场景 (例如 OBJ 转换后从选项框返回主界面).
     * 不重置任何字段, 直接基于当前 nbtData/nbtPath/fieldSizeValue/fieldFormatValue/... 重建.
     */
    private static void reopenCurrentUI() {
        String title = (editing == null
            ? com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.window_title_create")
            : com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.window_title_edit",
                editing.id != null ? editing.id : ""))
            + " " + com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.window_title_pack",
                currentPackId != null ? currentPackId : "?");
        ModularUI ui = createUI();
        Minecraft.getInstance().setScreen(new ModularUIScreen(ui, Component.literal(title)));
    }

    private static void resetState() {
        currentPackId = null;
        editing = null;
        parent = null;
        fieldIdValue = "";
        fieldNameValue = "";
        fieldAuthorValue = "";
        fieldSizeValue = "";
        fieldDepsValue = "";
        fieldDescValue = "";
        fieldIconValue = "";
        fieldIconData = null;
        fieldIconPath = com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.not_selected");
        nbtData = null;
        nbtPath = com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.not_selected");
        nbtInfo = null;
        statusMessage = null;
        statusTick = 0;
        statusColor = 0x55FF55;
    }

    private static String safeStr(String s) { return s == null ? "" : s; }

    /**
     * 根据文件名后缀识别蓝图格式 (nbt / litematic / schem / obj->schem / 未知).
     * 用于自动填 "蓝图格式" 字段 - 让玩家选完文件就能看到当前建筑是什么格式.
     */
    private static String detectFormatFromFileName(String fileName) {
        if (fileName == null) return com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.format_unknown");
        String lower = fileName.toLowerCase();
        // 转换后的 .converted.schem 文件 → 标识为 obj->schem
        if (lower.contains(".converted.schem") || lower.contains(".converted.schematic")) {
            return com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.obj_to_schem");
        }
        if (lower.endsWith(".litematic")) return "litematic";
        if (lower.endsWith(".schem") || lower.endsWith(".schematic")) return "schem";
        if (lower.endsWith(".nbt")) return "nbt";
        if (lower.endsWith(".obj")) return com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.obj_to_schem");
        return com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.format_unknown");
    }

    /**
     * 保留用户已填字段值的快照, 用于从游戏中选区返回时恢复表单.
     * 避免 resetState() 把用户已填的 id/name/author/desc 等清空.
     */
    public static final class PreservedFieldValues {
        public final String id, name, author, size, deps, desc;
        public PreservedFieldValues(String id, String name, String author,
                                    String size, String deps, String desc) {
            this.id = id;
            this.name = name;
            this.author = author;
            this.size = size;
            this.deps = deps;
            this.desc = desc;
        }
    }

    // === 主 UI 创建 ===
    private static ModularUI createUI() {
        UIElement root = new UIElement();
        root.layout(l -> l
            .widthPercent(100).heightPercent(100)
            .flexDirection(FlexDirection.COLUMN)
            .paddingAll(2).gapAll(2)
        );
        root.style(s -> s.background(Sprites.BORDER));
        root.setOverflowVisible(false);

        // === 标题 ===
        Label titleEl = new Label();
        titleEl.setText("§l" + (editing == null
            ? com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.create_title")
            : com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.edit_title", editing.id))
            + " §7(" + com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.pack_label", currentPackId) + ")");
        titleEl.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        titleEl.layout(l -> l.widthPercent(100).height(18));
        root.addChild(titleEl);

        // === 表单区域 (scroller, 占中间大部分) ===
        ScrollerView formScroller = new ScrollerView();
        formScroller.layout(l -> l.widthPercent(100).flexGrow(1).flexShrink(1)
            .flexBasis(0).minHeight(0).minWidth(0));
        formScroller.scrollerStyle(s -> s.mode(ScrollerMode.VERTICAL)
            .verticalScrollDisplay(ScrollDisplay.ALWAYS)
            .horizontalScrollDisplay(ScrollDisplay.NEVER)
            .minScrollPixel(8)
            .maxScrollPixel(80));
        formScroller.verticalScroller(s -> s.setScrollBarSize(8));
        UIElement formContent = new UIElement();
        formContent.layout(l -> l.widthPercent(100).heightAuto()
            .flexDirection(FlexDirection.COLUMN).gapAll(2).paddingAll(2).minHeight(0));
        formScroller.addScrollViewChild(formContent);

        // 字段: 标识符 / 名称 / 作者 / 尺寸 / 蓝图格式 / 依赖 / 描述
        // 保存 TextField 引用, 加载 litematica 后用 meta 自动填 name/author/desc 到 UI
        idTf = addInputField(formContent, com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.label_id"), fieldIdValue, v -> fieldIdValue = v, editing != null);
        nameTf = addInputField(formContent, com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.label_name"), fieldNameValue, v -> fieldNameValue = v, false);
        authorTf = addInputField(formContent, com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.label_author"), fieldAuthorValue, v -> fieldAuthorValue = v, false);
        // 尺寸 (只读, 由 NBT 自动填)
        addReadonlyField(formContent, com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.label_size"), fieldSizeValue, e -> { sizeEl = e; });
        // 蓝图格式 (只读, 根据所选文件后缀自动填)
        addReadonlyField(formContent, com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.label_format"), fieldFormatValue, e -> { formatEl = e; });
        // 依赖 (只读, 由 NBT 自动填)
        addReadonlyField(formContent, com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.label_deps"), fieldDepsValue, e -> { depEl = e; });
        descTf = addInputField(formContent, com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.label_desc"), fieldDescValue, v -> fieldDescValue = v, false);

        // === 图标选择行 ===
        // 让玩家从本地选一张 PNG/JPG 图作为蓝图图标. 图片会在保存时写到 construction/<id>.png,
        // 建筑标签页 drawConstructionCard 的 hasPreviewImage() 路径会自动用这张图.
        UIElement iconRow = new UIElement();
        iconRow.layout(l -> l.widthPercent(100).height(20)
            .flexDirection(FlexDirection.ROW).gapAll(4).marginTop(2)
            .alignItems(AlignItems.CENTER));
        iconRow.setOverflowVisible(false);
        Label iconLbl = new Label();
        iconLbl.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.icon"));
        iconLbl.textStyle(t -> t.textColor(0xAAAAAA));
        iconLbl.layout(l -> l.width(40).flexShrink(0));
        iconRow.addChild(iconLbl);

        // 当前图标显示
        iconEl = new TextElement();
        updateIconDisplay();
        iconEl.textStyle(t -> t.textColor(iconHasData() ? 0x55FF55 : 0xFF5555)
            .textWrap(TextWrap.WRAP).adaptiveHeight(true));
        iconEl.layout(l -> l.flexGrow(1).heightAuto().minHeight(14));
        iconRow.addChild(iconEl);

        Button btnPickIcon = new Button().setText(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.choose_image"));
        btnPickIcon.setOnClick(e -> openIconPicker());
        btnPickIcon.layout(l -> l.heightPercent(100).width(50).flexShrink(0));
        iconRow.addChild(btnPickIcon);

        Button btnClearIcon = new Button().setText("✕");
        btnClearIcon.setOnClick(e -> {
            fieldIconData = null;
            fieldIconPath = com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.not_selected");
            updateIconDisplay();
            setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.icon_cleared"), 0x55FF55);
        });
        btnClearIcon.layout(l -> l.heightPercent(100).width(20).flexShrink(0));
        iconRow.addChild(btnClearIcon);
        formContent.addChild(iconRow);

        // 选择按钮行
        UIElement selectRow = new UIElement();
        selectRow.layout(l -> l.widthPercent(100).height(20)
            .flexDirection(FlexDirection.ROW).gapAll(4).marginTop(2));
        Button btnChooseNbt = new Button().setText(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.choose_nbt"));
        btnChooseNbt.setOnClick(e -> openNbtChooser());
        btnChooseNbt.layout(l -> l.flexGrow(1).heightPercent(100));
        selectRow.addChild(btnChooseNbt);

        Button btnPickInWorld = new Button().setText(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.pick_in_world"));
        btnPickInWorld.setOnClick(e -> startInWorldPicking());
        btnPickInWorld.layout(l -> l.flexGrow(1).heightPercent(100));
        selectRow.addChild(btnPickInWorld);
        formContent.addChild(selectRow);

        // NBT 路径显示
        nbtPathEl = new TextElement();
        nbtPathEl.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.nbt_path", truncate(nbtPath, 50)));
        nbtPathEl.textStyle(t -> t.textColor(nbtData == null ? 0xFF5555 : 0x55FF55)
            .textWrap(TextWrap.WRAP));
        nbtPathEl.layout(l -> l.widthPercent(100).heightAuto().minHeight(12));
        formContent.addChild(nbtPathEl);

        root.addChild(formScroller);

        // === 底部状态行 ===
        statusEl = new TextElement();
        statusEl.setText("");
        statusEl.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        statusEl.layout(l -> l.widthPercent(100).height(14));
        root.addChild(statusEl);

        // === 底部按钮行 ===
        UIElement buttonRow = new UIElement();
        buttonRow.layout(l -> l.widthPercent(100).height(22)
            .flexDirection(FlexDirection.ROW).gapAll(2));
        buttonRow.setOverflowVisible(false);

        Button btnSave = new Button().setText(editing == null
            ? com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.save_and_create")
            : com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.save"));
        btnSave.setOnClick(e -> doSave());
        btnSave.layout(l -> l.flexGrow(1).heightPercent(100));
        buttonRow.addChild(btnSave);

        // 删除按钮 - 仅编辑模式显示, 创建模式没有要删的建筑
        if (editing != null) {
            Button btnDelete = new Button().setText(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.delete"));
            btnDelete.textStyle(t -> t.textColor(0xFFFF5555));
            btnDelete.setOnClick(e -> showDeleteConfirmDialog());
            btnDelete.layout(l -> l.flexGrow(1).heightPercent(100));
            buttonRow.addChild(btnDelete);
        }

        Button btnCancel = new Button().setText(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.cancel"));
        // 取消走 parent.onChildClosed() 返回主界面, 而不是直接 setScreen(null) 退出
        btnCancel.setOnClick(e -> {
            if (parent != null) {
                parent.onChildClosed();
            } else {
                Minecraft.getInstance().setScreen(null);
            }
        });
        btnCancel.layout(l -> l.flexGrow(1).heightPercent(100));
        buttonRow.addChild(btnCancel);
        root.addChild(buttonRow);

        // === tick: 状态消息淡出 ===
        root.addEventListener(UIEvents.TICK, e -> {
            if (statusTick > 0) {
                statusTick--;
                if (statusMessage != null) {
                    statusEl.setText(statusMessage);
                    statusEl.textStyle(t -> t.textColor(statusColor));
                }
                if (statusTick <= 0) {
                    statusEl.setText("");
                    statusMessage = null;
                }
            }
        });

        return ModularUI.of(UI.of(root,
            StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)));
    }

    /**
     * 选新建筑文件时, 把 "Unnamed" / "AUTH" 这类工具默认占位符当空, 不污染 UI.
     * (Litematica 默认 Name="Unnamed", 默认 Author="AUTH")
     */
    private static boolean isPlaceholderMeta(String s) {
        if (s == null) return true;
        String t = s.trim();
        if (t.isEmpty()) return true;
        String lower = t.toLowerCase();
        // 检查英文默认值 + 中文翻译后的值 (玩家语言是中文时, litematica 文件里的 meta 可能是"未命名"/"默认")
        return lower.equals("unnamed") || lower.equals("auth") || lower.equals("author")
            || lower.equals("default")
            || lower.equals(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.placeholder_unnamed").toLowerCase())
            || lower.equals(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.placeholder_default").toLowerCase());
    }

    // === 表单字段辅助 ===
    private static TextField addInputField(UIElement parent, String label, String initial,
                                      java.util.function.Consumer<String> onChange, boolean readOnly) {
        UIElement row = new UIElement();
        row.layout(l -> l.widthPercent(100).height(20)
            .flexDirection(FlexDirection.ROW).gapAll(4).alignItems(AlignItems.CENTER));
        row.setOverflowVisible(false);
        Label lbl = new Label();
        lbl.setText(label);
        lbl.textStyle(t -> t.textColor(0xAAAAAA));
        lbl.layout(l -> l.width(50).flexShrink(0));
        row.addChild(lbl);

        if (readOnly) {
            // 只读: 用 TextElement 显示
            TextElement val = new TextElement();
            val.setText(initial == null || initial.isEmpty()
                ? com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.empty_brackets")
                : initial);
            val.textStyle(t -> t.textColor(0xFFFFFF).textWrap(TextWrap.WRAP).adaptiveHeight(true));
            val.layout(l -> l.flexGrow(1).heightAuto().minHeight(14));
            row.addChild(val);
            parent.addChild(row);
            return null;
        }

        // 可编辑: 用 LDLib2 TextField (内置光标/焦点/输入处理, 不会因缩放变形)
        TextField tf = new TextField();
        tf.setText(initial == null ? "" : initial);
        tf.setTextResponder(onChange);
        tf.setAnyString();
        // 限制长度 <= 1024 字符 (TextField 没有 setMaxLength, 用 validator 限)
        tf.setTextValidator(s -> s == null || s.length() <= 1024);
        tf.textFieldStyle(s -> s.textColor(0xFFFFFF)
            .textShadow(false));
        tf.layout(l -> l.flexGrow(1).height(16).minHeight(16));
        row.addChild(tf);
        parent.addChild(row);
        return tf;
    }

    private static void addReadonlyField(UIElement parent, String label, String initial,
                                         java.util.function.Consumer<TextElement> onCreate) {
        UIElement row = new UIElement();
        row.layout(l -> l.widthPercent(100).heightAuto().minHeight(20)
            .flexDirection(FlexDirection.ROW).gapAll(4).alignItems(AlignItems.CENTER));
        row.setOverflowVisible(false);
        Label lbl = new Label();
        lbl.setText(label);
        lbl.textStyle(t -> t.textColor(0xAAAAAA));
        lbl.layout(l -> l.width(50).flexShrink(0));
        row.addChild(lbl);

        TextElement val = new TextElement();
        val.setText(initial == null || initial.isEmpty()
            ? com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.parse_hint")
            : initial);
        val.textStyle(t -> t.textColor(0xFFFFFF).textWrap(TextWrap.WRAP).adaptiveHeight(true));
        val.layout(l -> l.flexGrow(1).heightAuto().minHeight(14));
        row.addChild(val);
        parent.addChild(row);
        if (onCreate != null) onCreate.accept(val);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return "..." + s.substring(s.length() - (max - 3));
    }

    // === NBT 选择 ===
    private static void openNbtChooser() {
        setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.opening_nbt_picker"), 0x55AAFF);
        com.prefab.addon.client.gui.SystemFilePicker.openAsync(
            com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.choose_nbt_title"),
            java.util.Arrays.asList("nbt", "litematic", "schem", "schematic", "obj"),
            r -> {
                Minecraft.getInstance().execute(() -> {
                    if (r.isOk()) {
                        File f = r.file;
                        // OBJ 文件: 先转成 Schematic (gzip NBT) 再走原 NBT 处理流程
                        if (f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".obj")) {
                            handleObjSelected(f);
                            return;
                        }
                        handleNbtSelected(f);
                    } else if (r.isCancelled()) {
                        setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.cancelled"), 0x888888);
                    } else {
                        setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.picker_error", r.message), 0xFF5555);
                    }
                });
            });
    }

    /**
     * 处理 .obj 文件: 弹一个轻量级选项框让玩家选分辨率 + 实心/空心,
     * 然后用 ObjToSchematicConverter 转成 vanilla structure NBT (gzip 压缩).
     */
    private static void handleObjSelected(File objFile) {
        // 弹选项框 (4 档分辨率 + 实心/空心切换)
        showObjOptionsDialog(objFile);
    }

    /**
     * OBJ 转换选项弹窗 (LDLib2 ModularUI).
     * 布局 (220x180):
     *   - 标题 "OBJ 转换选项"
     *   - 4 个分辨率按钮: [8] [16] [32] [64] voxels/m (默认 32 高亮)
     *   - 实心/空心切换 (默认空心)
     *   - 表面强化复选框 (默认开)
     *   - 取消 / 转换 按钮
     */
    private static void showObjOptionsDialog(File objFile) {
        // 选项状态
        final int[] selectedVpm = {64};   // 默认 64 voxels/meter
        final boolean[] fillSolid = {false}; // 默认空心
        final boolean[] strengthen = {true}; // 默认开表面强化

        UIElement root = new UIElement();
        root.layout(l -> l
            .width(220).height(200)
            .flexDirection(FlexDirection.COLUMN)
            .paddingAll(8).gapAll(6)
        );
        root.style(s -> s.background(Sprites.BORDER));
        root.setOverflowVisible(false);

        // 标题
        Label title = new Label();
        title.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.obj_title_win"));
        title.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        title.layout(l -> l.widthPercent(100).height(20));
        root.addChild(title);

        // 提示
        TextElement hint = new TextElement();
        hint.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.file_label", objFile.getName()));
        hint.textStyle(t -> t.textColor(0xAAAAAA).textWrap(TextWrap.WRAP));
        hint.layout(l -> l.widthPercent(100).height(11));
        root.addChild(hint);

        // 分辨率标签
        TextElement vpmLabel = new TextElement();
        vpmLabel.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.vpm"));
        vpmLabel.textStyle(t -> t.textColor(0xFFFFFF));
        vpmLabel.layout(l -> l.widthPercent(100).height(12));
        root.addChild(vpmLabel);

        // 6 个分辨率按钮 (4→6: 加 128/256 高分辨率档, 适合写实/精细模型)
        UIElement vpmRow = new UIElement();
        vpmRow.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .widthPercent(100).height(20)
            .gapAll(3)
            .alignItems(AlignItems.CENTER)
        );
        root.addChild(vpmRow);

        Button[] vpmBtns = new Button[6];
        int[] vpmValues = {8, 16, 32, 64, 128, 256};
        for (int i = 0; i < vpmValues.length; i++) {
            final int vpm = vpmValues[i];
            final int idx = i;
            Button b = new Button();
            b.setText(String.valueOf(vpm));
            b.setOnClick(e -> {
                selectedVpm[0] = vpm;
                int selectedIdx = idx;
                for (int j = 0; j < vpmBtns.length; j++) {
                    final int jj = j;
                    vpmBtns[jj].textStyle(t -> t.textColor(jj == selectedIdx ? 0xFFFF00 : 0xFFFFFF));
                }
            });
            b.layout(l -> l.flexGrow(1).heightPercent(100));
            // 默认 64 高亮 — 适合大多数写实模型, sampleStep=1 → 6-10 万方块
            if (vpm == 64) b.textStyle(t -> t.textColor(0xFFFF00));
            vpmRow.addChild(b);
            vpmBtns[i] = b;
        }

        // 实心/空心切换
        UIElement solidRow = new UIElement();
        solidRow.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .widthPercent(100).height(18)
            .gapAll(4)
        );
        root.addChild(solidRow);
        // 用 Button 数组避免 lambda 循环引用编译错 (Java 不允许前向引用 local 变量)
        final Button[] solidHollowBtns = new Button[2];
        solidHollowBtns[0] = new Button(); // 实心
        solidHollowBtns[1] = new Button(); // 空心
        solidHollowBtns[0].setText(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.solid"));
        solidHollowBtns[1].setText(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.hollow"));
        solidHollowBtns[0].setOnClick(e -> {
            fillSolid[0] = true;
            solidHollowBtns[0].textStyle(t -> t.textColor(0xFFFF00));
            solidHollowBtns[1].textStyle(t -> t.textColor(0xFFFFFF));
        });
        solidHollowBtns[1].setOnClick(e -> {
            fillSolid[0] = false;
            solidHollowBtns[1].textStyle(t -> t.textColor(0xFFFF00));
            solidHollowBtns[0].textStyle(t -> t.textColor(0xFFFFFF));
        });
        solidHollowBtns[1].textStyle(t -> t.textColor(0xFFFF00)); // 默认空心高亮
        solidHollowBtns[0].layout(l -> l.flexGrow(1).heightPercent(100));
        solidHollowBtns[1].layout(l -> l.flexGrow(1).heightPercent(100));
        solidRow.addChild(solidHollowBtns[0]);
        solidRow.addChild(solidHollowBtns[1]);

        // 表面强化切换
        UIElement strengthRow = new UIElement();
        strengthRow.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .widthPercent(100).height(18)
            .gapAll(4)
        );
        root.addChild(strengthRow);
        Button btnStrengthOn = new Button();
        btnStrengthOn.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.surface_str"));
        btnStrengthOn.setOnClick(e -> {
            strengthen[0] = !strengthen[0];
            btnStrengthOn.textStyle(t -> t.textColor(strengthen[0] ? 0xFFFF00 : 0xFFFFFF));
            btnStrengthOn.setText(strengthen[0]
                ? com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.surface_str")
                : com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.surface_unstr"));
        });
        btnStrengthOn.textStyle(t -> t.textColor(0xFFFF00));
        btnStrengthOn.layout(l -> l.flexGrow(1).heightPercent(100));
        strengthRow.addChild(btnStrengthOn);

        // 按钮行
        UIElement buttonRow = new UIElement();
        buttonRow.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .widthPercent(100).height(22)
            .gapAll(4)
        );
        root.addChild(buttonRow);

        Button btnCancel = new Button();
        btnCancel.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.cancel"));
        btnCancel.setOnClick(e -> {
            Minecraft.getInstance().setScreen(null);
            setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.cancelled"), 0x888888);
        });
        btnCancel.layout(l -> l.flexGrow(1).heightPercent(100));
        buttonRow.addChild(btnCancel);

        Button btnConvert = new Button();
        btnConvert.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.start_convert"));
        btnConvert.setOnClick(e -> {
            ObjToSchematicConverter.Options opt = new ObjToSchematicConverter.Options();
            opt.voxelsPerMeter = selectedVpm[0];
            opt.fillInterior = fillSolid[0];
            opt.strengthenSurface = strengthen[0];

            // 异步: 显示进度条对话框 + worker thread 后台跑 + tick 读进度
            Minecraft.getInstance().setScreen(null);
            showObjProgressDialog(objFile, opt);
        });
        btnConvert.layout(l -> l.flexGrow(1).heightPercent(100));
        buttonRow.addChild(btnConvert);

        // 启动 UI
        Minecraft.getInstance().setScreen(
            new ModularUIScreen(ModularUI.of(UI.of(root,
                StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC))),
                Component.literal(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.obj_title_win")))
        );
    }

    /** 工具: 在 vpmValues 里找 vpm 的索引 */
    private static int findVpmIndex(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return 0;
    }

    /**
     * 异步执行 OBJ → NBT 转换, 显示进度条对话框.
     * <p>流程: 弹进度条 UI → 后台 worker thread 跑 convertToSchematic → tick 监听
     * 从 AtomicReference 读进度更新 UI → 完成后调用 handleConvertedSchematic + reopenCurrentUI().</p>
     * <p>关键: 整个转换在 worker thread 跑, 主线程只读 AtomicReference 进度 + 调 UI setText, 不卡.</p>
     */
    private static void showObjProgressDialog(File objFile, ObjToSchematicConverter.Options opt) {
        // UI 元素 (匿名 final, 让 tick lambda 可访问)
        ProgressBar progressBar = new ProgressBar();
        progressBar.setProgress(0f);
        TextElement statusText = new TextElement();
        statusText.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.preparing"));
        statusText.textStyle(t -> t.textColor(0xFFFFFF).textWrap(TextWrap.WRAP));
        TextElement percentText = new TextElement();
        percentText.setText("0%");
        percentText.textStyle(t -> t.textColor(0xFFFF00));

        // 取消标志
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicBoolean done = new AtomicBoolean(false);
        AtomicReference<Float> progressRef = new AtomicReference<>(0f);
        AtomicReference<String> messageRef = new AtomicReference<>(
            com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.preparing"));
        AtomicReference<ObjToSchematicConverter.Result> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        // 进度回调 (worker thread 调用, 只更新 AtomicReference, 不动 UI)
        opt.progressCallback = (frac, msg) -> {
            progressRef.set(frac);
            if (msg != null) messageRef.set(msg);
        };

        // worker thread
        Thread worker = new Thread(() -> {
            try {
                ObjToSchematicConverter.Result r =
                    ObjToSchematicConverter.convertToSchematic(objFile.toPath(), opt);
                resultRef.set(r);
            } catch (Throwable t) {
                errorRef.set(t);
                PrefabCustomAddon.LOGGER.error("[OBJ-CONVERT] 转换失败", t);
            } finally {
                done.set(true);
            }
        }, "PrefabAddon-ObjConvert");
        worker.setDaemon(true);
        worker.start();

        // UI 布局
        UIElement root = new UIElement();
        root.layout(l -> l
            .width(280).height(140)
            .flexDirection(FlexDirection.COLUMN)
            .paddingAll(10).gapAll(8)
        );
        root.style(s -> s.background(Sprites.BORDER));
        root.setOverflowVisible(false);

        Label title = new Label();
        title.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.obj_title"));
        title.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        title.layout(l -> l.widthPercent(100).height(18));
        root.addChild(title);

        // 文件名 (截断)
        String shortName = objFile.getName();
        if (shortName.length() > 35) shortName = shortName.substring(0, 32) + "...";
        TextElement fileLabel = new TextElement();
        fileLabel.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.file_short", shortName));
        fileLabel.textStyle(t -> t.textColor(0xAAAAAA).textWrap(TextWrap.WRAP));
        fileLabel.layout(l -> l.widthPercent(100).height(10));
        root.addChild(fileLabel);

        // 进度条
        progressBar.layout(l -> l.widthPercent(100).height(10));
        root.addChild(progressBar);

        // 百分比
        percentText.layout(l -> l.widthPercent(100).height(14));
        root.addChild(percentText);

        // 状态文字
        statusText.layout(l -> l.widthPercent(100).height(10));
        root.addChild(statusText);

        // 取消按钮
        Button btnCancel = new Button();
        btnCancel.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.cancel"));
        btnCancel.setOnClick(e -> {
            if (done.get()) {
                // 已完成, 关闭进度框回到主 UI
                Minecraft.getInstance().setScreen(null);
                reopenCurrentUI();
                return;
            }
            cancelled.set(true);
            // 不能强行中断 worker (convertToSchematic 内部没检查 cancel),
            // 但 worker 是 daemon, 主线程 setScreen(null) 不会卡.
            // 等 worker 自然结束, onClientTick 检测 cancelled && done 后回收.
            btnCancel.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.cancelling"));
            // 取消后禁用按钮 (LDLib2 的 Button 用 setActive)
            btnCancel.setActive(false);
        });
        btnCancel.layout(l -> l.widthPercent(100).height(22));
        root.addChild(btnCancel);

        // tick 监听: 主线程每帧读进度 + 更新 UI, 检测完成
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(new Object() {
            @net.neoforged.bus.api.SubscribeEvent
            public void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
                if (done.get()) {
                    net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(this);
                    if (cancelled.get()) {
                        setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.cancelled"), 0x888888);
                        Minecraft.getInstance().setScreen(null);
                        reopenCurrentUI();
                        return;
                    }
                    Throwable err = errorRef.get();
                    if (err != null) {
                        setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.obj_fail", err.getMessage()), 0xFF5555);
                        Minecraft.getInstance().setScreen(null);
                        reopenCurrentUI();
                        return;
                    }
                    ObjToSchematicConverter.Result r = resultRef.get();
                    if (r == null) {
                        setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.obj_no_result"), 0xFF5555);
                        Minecraft.getInstance().setScreen(null);
                        reopenCurrentUI();
                        return;
                    }
                    // 写 schem 临时文件 + 字段自动填
                    if (r.warnings != null && !r.warnings.isEmpty()) {
                        for (String w : r.warnings) {
                            PrefabCustomAddon.LOGGER.warn("[OBJ-CONVERT] {}", w);
                        }
                    }
                    File schemTmp = new File(objFile.getParentFile(),
                        stripExt(objFile.getName()) + ".converted.schem");
                    File processTarget;
                    try {
                        Files.write(schemTmp.toPath(), r.schematicBytes);
                        processTarget = schemTmp;
                    } catch (Exception writeEx) {
                        PrefabCustomAddon.LOGGER.warn("[OBJ-CONVERT] 写入临时文件失败, 使用内存数据", writeEx);
                        processTarget = new File(objFile.getParentFile(), stripExt(objFile.getName()) + ".schem");
                    }
                    PrefabCustomAddon.LOGGER.info("[OBJ-CONVERT] {} → {}x{}x{} ({} 方块, {}ms, vpm={}, solid={}, strengthen={})",
                        objFile.getName(), r.width, r.height, r.length, r.blockCount, r.elapsedMs,
                        opt.voxelsPerMeter, opt.fillInterior, opt.strengthenSurface);
                    handleConvertedSchematic(r, processTarget);
                    // 关闭进度条 + 回到主 UI
                    Minecraft.getInstance().setScreen(null);
                    reopenCurrentUI();
                    return;
                }
                // worker 还在跑: 更新 UI
                float frac = progressRef.get();
                String msg = messageRef.get();
                progressBar.setProgress(frac);
                int pct = Math.round(frac * 100f);
                percentText.setText(pct + "%");
                statusText.setText(msg != null ? msg : "");
            }
        });

        // 启动 UI
        Minecraft.getInstance().setScreen(
            new ModularUIScreen(ModularUI.of(UI.of(root,
                StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC))),
                Component.literal(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.obj_progress_window_title")))
        );
    }

    /**
     * 同步版本: 实际执行 OBJ → vanilla NBT 转换 + 字段自动填.
     * 旧版保留为兼容入口, 新流程用 showObjProgressDialog 异步 + 进度条.
     */
    @SuppressWarnings("unused")
    private static void runObjConversion(File objFile, ObjToSchematicConverter.Options opt) {
        try {
            setStatus(String.format(java.util.Locale.ROOT,
                com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.obj_status_format"),
                opt.voxelsPerMeter, opt.fillInterior
                    ? com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.solid")
                    : com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.hollow")),
                0x55AAFF);
            ObjToSchematicConverter.Result r =
                ObjToSchematicConverter.convertToSchematic(objFile.toPath(), opt);

            if (r.warnings != null && !r.warnings.isEmpty()) {
                for (String w : r.warnings) {
                    PrefabCustomAddon.LOGGER.warn("[OBJ-CONVERT] {}", w);
                }
            }

            // 写入临时 .schem 文件
            File schemTmp = new File(objFile.getParentFile(),
                stripExt(objFile.getName()) + ".converted.schem");
            try {
                Files.write(schemTmp.toPath(), r.schematicBytes);
            } catch (Exception writeEx) {
                PrefabCustomAddon.LOGGER.warn("[OBJ-CONVERT] 写入临时文件失败, 使用内存数据", writeEx);
                schemTmp = null;
            }

            PrefabCustomAddon.LOGGER.info("[OBJ-CONVERT] {} → {}x{}x{} ({} 方块, {}ms, vpm={}, solid={}, strengthen={})",
                objFile.getName(), r.width, r.height, r.length, r.blockCount, r.elapsedMs,
                opt.voxelsPerMeter, opt.fillInterior, opt.strengthenSurface);

            File processTarget = schemTmp != null ? schemTmp
                : new File(objFile.getParentFile(), stripExt(objFile.getName()) + ".schem");
            handleConvertedSchematic(r, processTarget);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[OBJ-CONVERT] 转换失败", t);
            setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.obj_fail", t.getMessage()), 0xFF5555);
        }
    }

    /**
     * 把 ObjToSchematicConverter 转出来的 vanilla NBT (gzip 压缩) 解压后走原 NBT 解析.
     * 不走 NbtFormatConverter.toVanilla (因为已经是 vanilla 了), 走 NbtStructureParser.parse
     * 提取尺寸/方块, 然后字段自动填.
     */
    private static void handleConvertedSchematic(ObjToSchematicConverter.Result r, File processTarget) {
        try {
            // NbtStructureParser.parse(byte[]) 内部会 detectFormat + 必要时 toVanilla
            // 这里我们传的 NBT 已经是 vanilla (palette+blocks+size), detectFormat 返回 "vanilla", 不做转换
            NbtStructureParser.NbtInfo info = NbtStructureParser.parse(r.schematicBytes);

            nbtData = r.schematicBytes;
            nbtPath = processTarget.getAbsolutePath();
            nbtInfo = info;

            // 字段自动填
            String fileName = processTarget.getName();
            String lower = fileName.toLowerCase(java.util.Locale.ROOT);
            // OBJ 来源 → 蓝图格式固定为 "obj->schem" (用户要求)
            String detectedFormat = com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.obj_to_schem");
            fieldFormatValue = detectedFormat;
            if (formatEl != null) formatEl.setText(detectedFormat);
            if (lower.endsWith(".schem") || lower.endsWith(".schematic") || lower.endsWith(".nbt")) {
                if (fieldIdValue.trim().isEmpty()) {
                    int extLen = lower.endsWith(".schematic") ? ".schematic".length()
                        : lower.endsWith(".schem") ? ".schem".length()
                        : ".nbt".length();
                    fieldIdValue = fileName.substring(0, fileName.length() - extLen);
                }
            }
            if (info.sizeX > 0 || info.sizeY > 0 || info.sizeZ > 0) {
                fieldSizeValue = info.sizeString();
                if (sizeEl != null) sizeEl.setText(fieldSizeValue);
            } else {
                // 兜底: 用转换结果自带的尺寸 (防止 NbtStructureParser 漏识别)
                fieldSizeValue = r.width + "x" + r.height + "x" + r.length;
                if (sizeEl != null) sizeEl.setText(fieldSizeValue);
            }
            // meta: OBJ 转换产物没有 metaName/Author/Description, 全清空
            fieldNameValue = "";
            if (nameTf != null) nameTf.setText("");
            fieldAuthorValue = "";
            if (authorTf != null) authorTf.setText("");
            fieldDescValue = "";
            if (descTf != null) descTf.setText("");
            // 依赖: 仅 prefab
            fieldDepsValue = "prefab";
            if (depEl != null) depEl.setText("prefab");
            if (nbtPathEl != null) {
                nbtPathEl.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.nbt_path", truncate(nbtPath, 50)));
                nbtPathEl.textStyle(t -> t.textColor(0x55FF55).textWrap(TextWrap.WRAP));
            }

            setStatus(String.format(java.util.Locale.ROOT,
                com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.obj_done_detail"),
                r.width, r.height, r.length, r.blockCount, r.elapsedMs), 0x55FF55);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[OBJ-CONVERT] 解析转换结果失败", t);
            setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.parse_fail", t.getMessage()), 0xFF5555);
        }
    }

    private static String stripExt(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static void handleNbtSelected(File f) {
        try {
            byte[] data = Files.readAllBytes(f.toPath());
            String detectedFmt;
            byte[] vanillaNbtData = data;
            try {
                CompoundTag root;
                try (var bais = new java.io.ByteArrayInputStream(data)) {
                    root = NbtIo.readCompressed(bais, NbtAccounter.unlimitedHeap());
                } catch (Exception ex1) {
                    try (var bais = new java.io.ByteArrayInputStream(data)) {
                        root = NbtIo.read(new java.io.DataInputStream(bais));
                    }
                }
                if (root == null) throw new java.io.IOException(
                    com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.nbt_cannot_read"));
                detectedFmt = NbtFormatConverter.detectFormat(root);
                PrefabCustomAddon.LOGGER.info("[CREATOR-LD2] {} 格式: {}", f.getName(), detectedFmt);
                if (!"vanilla".equals(detectedFmt) && !"unknown".equals(detectedFmt)) {
                    CompoundTag vanillaRoot = NbtFormatConverter.toVanilla(root);
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    NbtIo.writeCompressed(vanillaRoot, baos);
                    vanillaNbtData = baos.toByteArray();
                }
            } catch (Exception e) {
                setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.nbt_read_fail", e.getMessage()), 0xFF5555);
                return;
            }

            NbtStructureParser.NbtInfo info;
            try {
                info = NbtStructureParser.parse(vanillaNbtData);
            } catch (Exception e) {
                setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.nbt_parse_fail", e.getMessage()), 0xFF5555);
                return;
            }
            nbtData = vanillaNbtData;
            nbtPath = f.getAbsolutePath();
            nbtInfo = info;

            // 标识符自动填 (空时)
            String fileName = f.getName();
            String lower = fileName.toLowerCase();
            // 蓝图格式自动填 (按文件后缀)
            String detectedFormat = detectFormatFromFileName(fileName);
            fieldFormatValue = detectedFormat;
            if (formatEl != null) formatEl.setText(detectedFormat);
            for (String ext : new String[]{".nbt", ".litematic", ".schem", ".schematic"}) {
                if (lower.endsWith(ext)) {
                    if (fieldIdValue.trim().isEmpty()) {
                        fieldIdValue = fileName.substring(0, fileName.length() - ext.length());
                    }
                    break;
                }
            }
            // 尺寸
            if (info.sizeX > 0 || info.sizeY > 0 || info.sizeZ > 0) {
                fieldSizeValue = info.sizeString();
                if (sizeEl != null) sizeEl.setText(fieldSizeValue);
            }
            // meta 自动填 - 选新文件时**总是**用新文件 meta 覆盖
            // 1) 新文件有 metaName/Author/Description → 用新文件值 (即使旧字段已填, 也要替换)
            // 2) 新文件没对应字段 → 清空旧值 (避免显示上一个文件的信息)
            //    跳过 "Unnamed" / "AUTH" 这类占位符 (把"未填"当空)
            // 注意: meta 字段空字符串在 NbtFormatConverter 已被过滤, 所以 null 表示无字段
            if (info.metaName != null && !isPlaceholderMeta(info.metaName)) {
                fieldNameValue = info.metaName;
                if (nameTf != null) nameTf.setText(fieldNameValue);
            } else {
                fieldNameValue = "";
                if (nameTf != null) nameTf.setText("");
            }
            if (info.metaAuthor != null && !isPlaceholderMeta(info.metaAuthor)) {
                fieldAuthorValue = info.metaAuthor;
                if (authorTf != null) authorTf.setText(fieldAuthorValue);
            } else {
                fieldAuthorValue = "";
                if (authorTf != null) authorTf.setText("");
            }
            if (info.metaDescription != null && !info.metaDescription.trim().isEmpty()) {
                fieldDescValue = info.metaDescription;
                if (descTf != null) descTf.setText(fieldDescValue);
            } else {
                fieldDescValue = "";
                if (descTf != null) descTf.setText("");
            }
            // 依赖
            java.util.LinkedHashSet<String> depSet = new java.util.LinkedHashSet<>();
            if (fieldDepsValue != null && !fieldDepsValue.trim().isEmpty()) {
                for (String d : fieldDepsValue.split("[,，;；\\s]+")) {
                    if (!d.isEmpty()) depSet.add(d.trim());
                }
            }
            for (String m : info.modIds) depSet.add(m);
            depSet.add("prefab");
            depSet.remove("minecraft");
            fieldDepsValue = String.join(", ", depSet);
            if (depEl != null) {
                depEl.setText(info.modIds.isEmpty() ? "prefab" : fieldDepsValue);
            }
            if (nbtPathEl != null) {
                nbtPathEl.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.nbt_path", truncate(nbtPath, 50)));
                nbtPathEl.textStyle(t -> t.textColor(0x55FF55).textWrap(TextWrap.WRAP));
            }

            String modInfo = info.modIds.isEmpty()
                ? com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.mod_only_mc")
                : com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.mod_info",
                    String.join(", ", info.modIds));
            setStatus(String.format(java.util.Locale.ROOT,
                com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.loaded_with_size"),
                f.getName(), info.sizeString(), modInfo), 0x55FF55);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[CREATOR-LD2] read NBT failed", t);
            setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.read_fail", t.getMessage()), 0xFF5555);
        }
    }

    // === 游戏中选区 ===
    private static void startInWorldPicking() {
        // 缓存现场 (用 PreservedFieldValues 保留用户已填的字段)
        final PreservedFieldValues preserved = new PreservedFieldValues(
            fieldIdValue, fieldNameValue, fieldAuthorValue,
            fieldSizeValue, fieldDepsValue, fieldDescValue);
        final GuiExtensionPackCreator savedParent = parent;
        final PackCreator.BuildingWorkInfo savedEditing = editing;

        Minecraft.getInstance().setScreen(null);

        com.prefab.addon.work.RegionSelector.start(
            Minecraft.getInstance().player,
            new com.prefab.addon.work.RegionSelector.OnCompleted() {
                @Override
                public void onCompleted(byte[] newNbtData, java.nio.file.Path nbtFile,
                                        String sizeString, java.util.List<String> modIds) {
                    Minecraft.getInstance().execute(() -> {
                        // 1. 先用 preserved 打开 (创建新的 sizeEl/depEl 控件)
                        open(currentPackId, savedEditing, savedParent, preserved);
                        // 2. 再处理 NBT 数据 (更新新的 sizeEl/depEl 控件 + 设置 fieldSizeValue/fieldDepsValue)
                        applyNbtFromRegion(preserved, newNbtData, nbtFile, sizeString, modIds);
                    });
                }
                @Override
                public void onCancelled() {
                    Minecraft.getInstance().execute(() -> {
                        // 取消: 用 preserved 打开, 保留原 size/deps 值
                        // 注意: preserved.size/deps 已经是当前值 (startInWorldPicking 关闭前快照的)
                        fieldSizeValue = preserved.size;
                        fieldDepsValue = preserved.deps;
                        open(currentPackId, savedEditing, savedParent, preserved);
                        setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.region_cancelled"), 0x888888);
                    });
                }
            });
    }

    /**
     * 处理从游戏区域捕获的 NBT 数据, 更新 nbtData/nbtPath/nbtInfo 及尺寸/依赖字段.
     * 注意: 不要直接修改 fieldIdValue/fieldNameValue/fieldAuthorValue/fieldDescValue,
     * 这些用户已填的值由 PreservedFieldValues 在 open() 时恢复.
     */
    private static void applyNbtFromRegion(PreservedFieldValues preserved,
                                           byte[] newNbtData, java.nio.file.Path nbtFile,
                                           String sizeString, java.util.List<String> modIds) {
        if (newNbtData != null) {
            nbtData = newNbtData;
            nbtPath = nbtFile == null
                ? com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.region_picker")
                : nbtFile.toAbsolutePath().toString();
            try {
                nbtInfo = NbtStructureParser.parse(newNbtData);
            } catch (Exception ignored) {}

            // 游戏中选区: 永远是 nbt 格式
            fieldFormatValue = "nbt";
            if (formatEl != null) formatEl.setText("nbt");

            // 优先用 sizeString (RegionSelector 给的), 没有则从 nbtInfo 取
            if (sizeString != null && !sizeString.isEmpty()) {
                fieldSizeValue = sizeString;
            } else if (nbtInfo != null && nbtInfo.sizeX > 0) {
                fieldSizeValue = nbtInfo.sizeString();
            }
            if (sizeEl != null) sizeEl.setText(fieldSizeValue);

            // 合并依赖: 已有 deps + nbtInfo modIds + prefab
            java.util.LinkedHashSet<String> depSet = new java.util.LinkedHashSet<>();
            String preservedDeps = preserved == null ? "" : preserved.deps;
            if (preservedDeps != null && !preservedDeps.trim().isEmpty()) {
                for (String d : preservedDeps.split("[,，;；\\s]+")) {
                    if (!d.isEmpty()) depSet.add(d.trim());
                }
            }
            if (nbtInfo != null) for (String m : nbtInfo.modIds) depSet.add(m);
            if (modIds != null) for (String m : modIds) depSet.add(m);
            depSet.add("prefab");
            depSet.remove("minecraft");
            fieldDepsValue = String.join(", ", depSet);
            if (depEl != null) depEl.setText(fieldDepsValue);
            if (nbtPathEl != null) {
                nbtPathEl.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.nbt_path", truncate(nbtPath, 50)));
                nbtPathEl.textStyle(t -> t.textColor(0x55FF55).textWrap(TextWrap.WRAP));
            }
            // meta 自动填 (从游戏区域选区时也支持)
            if (nbtInfo != null) {
                if (nbtInfo.metaName != null && fieldNameValue.trim().isEmpty()) {
                    fieldNameValue = nbtInfo.metaName;
                    if (nameTf != null) nameTf.setText(fieldNameValue);
                }
                if (nbtInfo.metaAuthor != null && fieldAuthorValue.trim().isEmpty()) {
                    fieldAuthorValue = nbtInfo.metaAuthor;
                    if (authorTf != null) authorTf.setText(fieldAuthorValue);
                }
                if (nbtInfo.metaDescription != null && fieldDescValue.trim().isEmpty()) {
                    fieldDescValue = nbtInfo.metaDescription;
                    if (descTf != null) descTf.setText(fieldDescValue);
                }
            }
            setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.imported"), 0x55FF55);
        } else {
            // newNbtData == null: 取消情况, 保留原值
            if (preserved != null) {
                fieldSizeValue = preserved.size;
                fieldDepsValue = preserved.deps;
            }
        }
    }

    // === 保存 ===
    private static void doSave() {
        String id = fieldIdValue.trim();
        String name = fieldNameValue.trim();
        String size = fieldSizeValue.trim();
        if (id.isEmpty()) { setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.id_empty"), 0xFF5555); return; }
        if (!id.matches("[A-Za-z0-9_\\-]+")) {
            setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.id_invalid"), 0xFF5555); return;
        }
        if (name.isEmpty()) { setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.name_empty"), 0xFF5555); return; }
        if (size.isEmpty()) { setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.size_empty"), 0xFF5555); return; }
        if (nbtData == null) { setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.no_nbt"), 0xFF5555); return; }

        try {
            // fieldIconData 是玩家刚选的 PNG/JPG, 传 null 表示不覆盖原 png.
            // 选过图 → 写到 construction/<id>.png; 没选 → null 保持原图.
            PackCreator.getInstance().saveBuilding(currentPackId, id, name,
                fieldAuthorValue.trim(), size, fieldDepsValue.trim(),
                fieldDescValue.trim(), fieldFormatValue.trim(), fieldIconValue.trim(),
                nbtData, iconHasData() ? fieldIconData : null);
            setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.saved", id), 0x55FF55);
            if (parent != null) {
                // 已有 parent 直接调 onChildClosed, 让父 GUI 自己负责重开/关闭
                parent.onChildClosed();
            } else {
                // 没有 parent 直接关闭
                CompletableFuture.runAsync(() -> {
                    try { Thread.sleep(600); } catch (InterruptedException ignored) {}
                    Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(null));
                });
            }
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.error("[CREATOR-LD2] save building failed", e);
            setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.save_fail", e.getMessage()), 0xFF5555);
        }
    }

    private static void setStatus(String msg, int color) {
        statusMessage = msg;
        statusColor = color;
        statusTick = 100;
    }

    // === 图标选择 ===
    /**
     * 打开系统文件选择器, 让玩家选一张 PNG/JPG 图作为蓝图图标.
     * 选完后: fieldIconData = 图片字节, fieldIconPath = 文件路径, updateIconDisplay 刷新.
     * 保存时 (doSave) 会把 fieldIconData 写到 construction/<id>.png 覆盖原图.
     */
    private static void openIconPicker() {
        setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.opening_icon_picker"), 0x55AAFF);
        com.prefab.addon.client.gui.SystemFilePicker.openAsync(
            com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.choose_icon_title"),
            java.util.Arrays.asList("png", "jpg", "jpeg"),
            r -> {
                Minecraft.getInstance().execute(() -> {
                    if (r.isOk()) {
                        File f = r.file;
                        if (f == null || !f.isFile()) {
                            setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.file_not_exist"), 0xFF5555);
                            return;
                        }
                        try {
                            byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
                            if (data == null || data.length == 0) {
                                setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.image_empty"), 0xFF5555);
                                return;
                            }
                            // 简单大小限制, 防玩家不小心选了 50MB 的图
                            if (data.length > 2 * 1024 * 1024) {
                                setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.image_too_big"), 0xFF5555);
                                return;
                            }
                            fieldIconData = data;
                            fieldIconPath = f.getName();
                            updateIconDisplay();
                            setStatus(String.format(java.util.Locale.ROOT,
                                com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.icon_selected"),
                                f.getName(), data.length / 1024), 0x55FF55);
                        } catch (Throwable t) {
                            PrefabCustomAddon.LOGGER.error("[CREATOR-LD2] openIconPicker read failed", t);
                            setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.image_read_fail", t.getMessage()), 0xFF5555);
                        }
                    } else if (r.isCancelled()) {
                        setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.cancelled"), 0x888888);
                    } else {
                        setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.picker_error", r.message), 0xFF5555);
                    }
                });
            });
    }

    /** 当前是否已有可用的图标图片字节. */
    private static boolean iconHasData() {
        return fieldIconData != null && fieldIconData.length > 0;
    }

    /** 刷新当前图标显示文字. */
    private static void updateIconDisplay() {
        if (iconEl == null) return;
        if (iconHasData()) {
            iconEl.setText("§a" + truncate(fieldIconPath, 30));
        } else {
            iconEl.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.icon_default_display"));
        }
    }

    // === 删除确认 + 执行 ===
    /**
     * 弹出确认弹窗: 防止玩家误点删除.
     * 弹窗布局 (240x130):
     *   - 标题: "确认删除"
     *   - 提示: "确定要删除建筑 XXX 吗? 此操作不可撤销."
     *   - 按钮: [取消] [确认删除]
     */
    private static void showDeleteConfirmDialog() {
        if (editing == null || currentPackId == null) {
            setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.no_delete_target"), 0xFF5555);
            return;
        }
        final String buildingId = editing.id;
        final String packId = currentPackId;
        final String displayName = (fieldNameValue == null || fieldNameValue.isEmpty())
            ? buildingId : fieldNameValue;

        UIElement root = new UIElement();
        root.layout(l -> l
            .width(240).height(130)
            .flexDirection(FlexDirection.COLUMN)
            .paddingAll(10).gapAll(8)
        );
        root.style(s -> s.background(Sprites.BORDER));
        root.setOverflowVisible(false);

        Label title = new Label();
        title.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.delete_title_dialog"));
        title.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        title.layout(l -> l.widthPercent(100).height(20));
        root.addChild(title);

        TextElement hint = new TextElement();
        hint.setText(String.format(java.util.Locale.ROOT,
            com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.delete_body_dialog"),
            displayName, packId));
        hint.textStyle(t -> t.textColor(0xFFFFFF).textWrap(TextWrap.WRAP).adaptiveHeight(true));
        hint.layout(l -> l.widthPercent(100).heightAuto().minHeight(40));
        root.addChild(hint);

        // 按钮行
        UIElement buttonRow = new UIElement();
        buttonRow.layout(l -> l.widthPercent(100).height(22)
            .flexDirection(FlexDirection.ROW).gapAll(4));
        Button btnCancel = new Button().setText(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.cancel"));
        btnCancel.setOnClick(e -> Minecraft.getInstance().setScreen(null));
        btnCancel.layout(l -> l.flexGrow(1).heightPercent(100));
        buttonRow.addChild(btnCancel);

        Button btnConfirm = new Button().setText(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.delete_confirm_btn"));
        btnConfirm.textStyle(t -> t.textColor(0xFFFF5555));
        btnConfirm.setOnClick(e -> doDelete(packId, buildingId));
        btnConfirm.layout(l -> l.flexGrow(1).heightPercent(100));
        buttonRow.addChild(btnConfirm);
        root.addChild(buttonRow);

        Minecraft.getInstance().setScreen(
            new ModularUIScreen(ModularUI.of(UI.of(root,
                StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC))),
                Component.literal(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.delete_window_title")))
        );
    }

    /**
     * 实际执行删除 (在确认弹窗点 "确认删除" 后调用).
     * 删除后会调 parent.onChildClosed() 让父 GUI 重新刷新建筑列表.
     */
    private static void doDelete(String packId, String buildingId) {
        try {
            PackCreator.getInstance().deleteBuilding(packId, buildingId);
            PrefabCustomAddon.LOGGER.info("[CREATOR-LD2] deleted building {}/{}", packId, buildingId);
            // 关掉确认弹窗
            Minecraft.getInstance().setScreen(null);
            // 走 parent.onChildClosed() 回到主界面并刷新
            if (parent != null) {
                parent.onChildClosed();
            } else {
                Minecraft.getInstance().setScreen(null);
            }
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[CREATOR-LD2] deleteBuilding failed", t);
            setStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.delete_fail", t.getMessage()), 0xFF5555);
            // 弹窗还开着, 提示下让玩家看到
        }
    }
}
