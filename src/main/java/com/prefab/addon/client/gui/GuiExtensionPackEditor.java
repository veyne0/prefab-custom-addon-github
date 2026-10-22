package com.prefab.addon.client.gui;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.mojang.blaze3d.platform.NativeImage;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.client.EditModeController;
import com.prefab.addon.config.BuildAnimationMode;
import com.prefab.addon.config.CategoryManager;
import com.prefab.addon.config.PlayerPreferences;
import com.prefab.addon.extension.ConstructionInfo;
import com.prefab.addon.extension.LocalBuilding;
import com.prefab.addon.extension.LocalBuildingScanner;
import com.prefab.addon.integration.KubeJSIntegration;
import com.prefab.addon.items.CustomBlueprintItem;
import com.prefab.addon.structure.CustomStructureBuilder;
import com.prefab.addon.work.RegionSelector;
import com.prefab.addon.client.gui.GuiCreateBuildingInfo.InGamePickResult;
import com.prefab.addon.client.gui.GuiCreateBuildingInfo.NbtPickResult;
import com.wuest.prefab.structures.base.BuildBlock;
import com.wuest.prefab.structures.base.Structure;
import com.wuest.prefab.structures.config.StructureConfiguration;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.imageio.ImageIO;
import java.awt.Desktop;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * X 键打开的建筑制作编辑器 — LDLib2 ModularUI 整体重写版 (从 1.21.1 同名实现移植).
 *
 * <p>布局 (整屏 100%):</p>
 * <pre>
 *   ┌────────┬────────────────────────────────────┐
 *   │ 创建建筑│ [各 tab 顶部行: 搜索框 / 标题 / 操作] │
 *   │ 编辑建筑│ ┌─ 表单 / 卡片滚动区 ─────────────┐ │
 *   │ 设置    │ │ ...                            │ │
 *   │ 制作蓝图│ └──────────────────────────────┘ │
 *   │ 蓝图管理│                     [底部操作按钮] │
 *   └────────┴────────────────────────────────────┘
 *   状态栏: 最近一次操作结果 (h=12)
 * </pre>
 *
 * <p>与旧版 (GuiBase 手绘) 的差异:</p>
 * <ul>
 *   <li>整体重写为 LDLib2 ModularUI 静态单实例模式 (照抄 1.21.1 版结构);</li>
 *   <li>分页 / 搜索 / tab / 制作蓝图表单状态全部静态持久化, 关掉重开不丢;</li>
 *   <li>设置 tab 改为单页滚动 (去掉旧版 2 页翻页), 保留 "自动检测依赖" toggle;</li>
 *   <li>外部签名 {@link #buildDetailInfo(LocalBuilding)} /
 *       {@link #startSchematicEdit(LocalBuilding)} / {@link #open(Tab)} 保持不变.</li>
 * </ul>
 *
 * <p>与 1.21.1 蓝本的差异 (1.20.1 功能保留):</p>
 * <ul>
 *   <li>创建建筑 tab 保留 "删除" 按钮 (GuiCreateBuildingInfo.doDelete);</li>
 *   <li>设置 tab 保留 "自动检测依赖" toggle (PlayerPreferences.autoDepCheckOnOpen);</li>
 *   <li>制作蓝图保留 配方非空校验 (need_recipe) +
 *       外部建筑复制到 blueprint-buildings/ (source="blueprint") +
 *       migrateLegacyBlueprintBuildings 迁移; locked toggle 已移除 (spec.locked 强制 true);</li>
 *   <li>蓝图管理删除联动 BlueprintSpecStore 规格文件 + deleteBlueprintBuilding;</li>
 *   <li>舍弃 1.21.1 的 AI 帮助按钮 (GuiAiBlueprintHelp 1.20.1 不存在) 与
 *       "▍ 建造材料" 装饰段 (无对应控件).</li>
 * </ul>
 */
public final class GuiExtensionPackEditor {

    // === 5 个 Tab ===
    public enum Tab {
        CREATE("创建建筑"),
        EDIT("编辑建筑"),
        SETTINGS("设置"),
        MAKE_BLUEPRINT("制作蓝图"),
        MANAGE_BLUEPRINT("蓝图管理");
        final String label;
        Tab(String label) { this.label = label; }
    }

    // === 布局常量 ===
    private static final int TAB_W = 62;       // 左侧 tab 列宽
    private static final int THUMB = 40;       // 编辑建筑卡片缩略图边长

    // === 全局状态 (界面单实例, 重开保留) ===
    private static Tab currentTab = Tab.CREATE;
    /** 编辑建筑 tab: 搜索关键字 + 页码 (0-based, 跨开屏保留). */
    private static String editSearch = "";
    private static int editPage = 0;
    /** 蓝图管理 tab: 页码 (0-based). */
    private static int mgmtPage = 0;

    // === "制作蓝图" tab 状态 (静态: 关掉 GUI 重开不丢) ===
    private static String SAVED_BLUEPRINT_NAME = "";
    /** 选中的建筑 (NBT 来源, 单文件建筑或 zip 包内的). null = 没选. */
    private static LocalBuilding selectedBuilding;
    /** 选中的贴图源文件 (玩家电脑上的 .png). null = 没选. */
    private static Path selectedTextureSource;
    /** 9 槽配方: 9 个 null-or-itemId (row-major 0..8). */
    private static final List<String> recipeItems = new ArrayList<>(Arrays.asList(
        null, null, null, null, null, null, null, null, null));

    // === "蓝图管理" tab 状态 ===
    /** 玩家本局点过 "删除" 的蓝图 (ns:itemId). 重启游戏自然清空, 与 KubeJS 注册状态对齐. */
    private static final Set<String> DELETED_THIS_SESSION = ConcurrentHashMap.newKeySet();
    /** 缓存: 当前列出的蓝图条目. 进入 tab / 刷新时重建. */
    private static final List<ManagedBlueprint> managedBlueprints = new ArrayList<>();

    // === "编辑建筑" tab 数据 ===
    private static final List<LocalBuilding> editBuildings = new ArrayList<>();
    /** 非 null = 创建建筑 tab 当前处于 "编辑信息" 模式, 目标是这个本地建筑. */
    private static LocalBuilding editInfoTarget;

    /** 一条 "KubeJS 联动蓝图" 的渲染数据: 物品 + 它对应的 KubeJS 文件. */
    private static final class ManagedBlueprint {
        final Item item;
        final String displayName;
        final String namespace;
        final String itemId;
        final Path jsFile;
        final Path recipeFile;
        final Path textureFile;
        ManagedBlueprint(Item item, String displayName, String namespace, String itemId,
                         Path jsFile, Path recipeFile, Path textureFile) {
            this.item = item;
            this.displayName = displayName;
            this.namespace = namespace;
            this.itemId = itemId;
            this.jsFile = jsFile;
            this.recipeFile = recipeFile;
            this.textureFile = textureFile;
        }
        String nsItem() { return namespace + ":" + itemId; }
    }

    // === 预览图纹理缓存 (编辑建筑 tab, key = 图片路径) ===
    private static final Map<String, ResourceLocation> EDIT_IMG = new HashMap<>();
    private static final Set<String> EDIT_IMG_LOADING = new HashSet<>();
    private static final AtomicInteger TEX_GEN = new AtomicInteger();

    // === UI 引用 (重建用, 单实例界面) ===
    private static UIElement rootRef;
    private static UIElement listAreaRef;
    private static Label statusLabel;
    /** 最近一次状态消息 (rebuild 重建 statusLabel 后用它恢复文本). */
    private static String lastStatus = "";
    private static int lastStatusColor = 0xAAAAAA;

    private GuiExtensionPackEditor() {}

    // ==================== 静态入口 (签名与旧版一致) ====================

    public static void open() {
        open(currentTab);
    }

    /** 用指定初始 tab 打开. */
    public static void open(Tab initialTab) {
        Minecraft mc = Minecraft.getInstance();
        currentTab = initialTab;
        // KubeJS 联动: GUI 打开时同步 forceEnabled + 刷新一次检测 (旧版 Initialize 行为)
        try {
            KubeJSIntegration.setForceEnabled(PlayerPreferences.get().forceKubeJSTab);
            KubeJSIntegration.recheck();
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[EDITOR] KubeJS 状态同步失败: {}", t.getMessage());
        }
        refreshEditBuildings();
        if (initialTab == Tab.MANAGE_BLUEPRINT) {
            rebuildManagedBlueprints();
        }
        UIElement root = buildRoot();
        ModularUI ui = ModularUI.of(UI.of(root,
            StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)), mc.player);
        mc.setScreen(new ModularUIScreen(ui, Component.literal(
            PrefabCustomAddon.tr("gui.prefab_custom_addon.title"))));
    }

    // ==================== UI 构建 ====================

    private static UIElement buildRoot() {
        UIElement root = new UIElement();
        root.layout(l -> l
            .widthPercent(100).heightPercent(100)
            .flexDirection(FlexDirection.COLUMN)
            .paddingAll(4).gapAll(3)
        );
        root.style(s -> s.background(Sprites.BORDER));
        root.setOverflowVisible(false);
        rebuildAll(root);
        return root;
    }

    /** 整体重建 (切 tab / 设置项切换时调用). */
    private static void rebuild() {
        if (rootRef != null) {
            rebuildAll(rootRef);
        }
    }

    private static void rebuildAll(UIElement root) {
        rootRef = root;
        root.clearAllChildren();

        // 主行: 左 tab 列 + 右内容列
        UIElement main = new UIElement();
        main.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).flexGrow(1).minHeight(0).gapAll(4));
        main.setOverflowVisible(false);
        root.addChild(main);

        // 左 tab 列
        UIElement tabCol = new UIElement();
        tabCol.layout(l -> l.flexDirection(FlexDirection.COLUMN).width(TAB_W).gapAll(3));
        tabCol.setOverflowVisible(false);
        for (Tab t : Tab.values()) {
            Tab ft = t;
            boolean kubejsDep = (t == Tab.MAKE_BLUEPRINT || t == Tab.MANAGE_BLUEPRINT)
                && !KubeJSIntegration.isLoaded();
            Button tb = new Button().setText(t.label, false);
            int fg = t == currentTab ? 0xFFDD66 : (kubejsDep ? 0x777777 : 0xCCCCCC);
            tb.textStyle(s -> s.textColor(fg));
            tb.layout(l -> l.widthPercent(100).height(16));
            tb.setOnClick(e -> switchTab(ft));
            tabCol.addChild(tb);
        }
        main.addChild(tabCol);

        // 右内容列
        UIElement content = new UIElement();
        content.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).minWidth(0).gapAll(3));
        content.setOverflowVisible(false);
        main.addChild(content);

        buildTabHeader(content);

        // 列表区
        UIElement listArea = new UIElement();
        listArea.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).flexGrow(1).minHeight(0).gapAll(4));
        listArea.setOverflowVisible(false);
        listAreaRef = listArea;
        content.addChild(listArea);

        rebuildList(listArea);

        // 状态栏 (h=12)
        UIElement statusRow = new UIElement();
        statusRow.layout(l -> l.widthPercent(100).height(12));
        statusRow.style(s -> s.background(Sprites.RECT_DARK));
        statusRow.setOverflowVisible(false);
        root.addChild(statusRow);
        statusLabel = new Label();
        statusLabel.setText(stripCodes(lastStatus), false);
        statusLabel.textStyle(t -> t.textColor(lastStatusColor));
        statusLabel.layout(l -> l.heightPercent(100).paddingLeft(3));
        statusRow.addChild(statusLabel);
    }

    /** 仅重建列表区 (搜索 / 图片加载完成时调用, 不打断搜索框输入). */
    private static void rebuildList() {
        if (listAreaRef != null) {
            rebuildList(listAreaRef);
        }
    }

    /** 各 tab 顶部行. */
    private static void buildTabHeader(UIElement content) {
        switch (currentTab) {
            case EDIT -> {
                UIElement row = new UIElement();
                row.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(16).gapAll(3));
                row.setOverflowVisible(false);
                content.addChild(row);
                TextField search = new TextField();
                search.setText(editSearch);
                search.setAnyString();
                search.setTextValidator(s -> s == null || s.length() <= 64);
                search.textFieldStyle(s -> s.textColor(0xFFFFFF).textShadow(false));
                search.layout(l -> l.flexGrow(1).height(16).minHeight(16));
                search.setTextResponder(s -> {
                    String q = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
                    if (!q.equals(editSearch)) {
                        editSearch = q;
                        editPage = 0;
                        rebuildList();
                    }
                });
                row.addChild(search);
                // 1.20.1 lang 没有 mcs.refresh, 硬编码中文
                Button refresh = new Button().setText("刷新", false);
                refresh.layout(l -> l.width(44).height(16));
                refresh.setOnClick(e -> {
                    refreshEditBuildings();
                    rebuildList();
                });
                row.addChild(refresh);
            }
            case MAKE_BLUEPRINT -> {
                UIElement row = new UIElement();
                row.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(16).gapAll(3));
                row.setOverflowVisible(false);
                content.addChild(row);
                Label title = new Label();
                title.setText(tr("gui.make_blueprint.title"), false);
                title.textStyle(t -> t.textColor(0xFFDD66));
                title.layout(l -> l.flexGrow(1).heightPercent(100));
                row.addChild(title);
                // 1.21.1 蓝本这里还有 AI 帮助按钮, 1.20.1 没有 GuiAiBlueprintHelp, 舍弃
            }
            case MANAGE_BLUEPRINT -> {
                UIElement row = new UIElement();
                row.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(16).gapAll(3));
                row.setOverflowVisible(false);
                content.addChild(row);
                Label title = new Label();
                // 1.20.1 lang 没有 editor.manage.title, 硬编码中文
                title.setText("§l§n蓝图管理 §7(共 " + managedBlueprints.size() + " 个)", false);
                title.textStyle(t -> t.textColor(0xFFDD66));
                title.layout(l -> l.flexGrow(1).heightPercent(100));
                row.addChild(title);
                Button refresh = new Button().setText("刷新", false);
                refresh.layout(l -> l.width(44).height(16));
                refresh.setOnClick(e -> {
                    rebuildManagedBlueprints();
                    setStatus("已刷新, 共 " + managedBlueprints.size() + " 个自定义蓝图", 0x55FF55);
                    rebuildList();
                });
                row.addChild(refresh);
            }
            case SETTINGS -> {
                Label title = new Label();
                title.setText(tr("gui.settings.title"), false);
                title.textStyle(t -> t.textColor(0xFFDD66));
                title.layout(l -> l.widthPercent(100).height(14));
                content.addChild(title);
            }
            default -> {
                // CREATE tab: 标题在列表区里画
            }
        }
    }

    private static void rebuildList(UIElement listArea) {
        listArea.clearAllChildren();
        switch (currentTab) {
            case CREATE -> buildCreateTab(listArea);
            case EDIT -> buildEditTab(listArea);
            case SETTINGS -> buildSettingsTab(listArea);
            case MAKE_BLUEPRINT -> buildMakeBlueprintTab(listArea);
            case MANAGE_BLUEPRINT -> buildManageBlueprintTab(listArea);
        }
    }

    // ==================== Tab: 创建建筑 ====================
    // 数据逻辑照抄 TerminalGui 的移植: 全部走 GuiCreateBuildingInfo 的桥接方法.

    private static void buildCreateTab(UIElement listArea) {
        UIElement panel = new UIElement();
        panel.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).widthPercent(100)
            .gapAll(3).paddingAll(2));
        panel.style(s -> s.background(Sprites.RECT_DARK));
        panel.setOverflowVisible(false);
        listArea.addChild(panel);

        Label hint = new Label();
        if (editInfoTarget != null) {
            // 编辑信息模式: 复用创建表单改字段, 建筑文件本体不动
            hint.setText("正在编辑建筑信息: "
                + (editInfoTarget.getDisplayName() == null ? editInfoTarget.id : editInfoTarget.getDisplayName())
                + " — 改完点「保存信息」, 点「取消」放弃", false);
            hint.textStyle(t -> t.textColor(0x88BBFF));
        } else {
            // 1.20.1 lang 没有 hint_terminal, 硬编码中文
            hint.setText("建议使用建筑终端来创建建筑", false);
            hint.textStyle(t -> t.textColor(0xFFAA55));
        }
        hint.layout(l -> l.widthPercent(100).height(12).marginBottom(4));
        panel.addChild(hint);

        // 建筑名 (唯一可编辑字段; 标识符自动用建筑名生成)
        UIElement nameRow = new UIElement();
        nameRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(16).gapAll(3)
            .alignItems(AlignItems.CENTER).marginBottom(4));
        panel.addChild(nameRow);
        nameRow.addChild(rowLabel(tr("gui.create_building.label_name"), 46));
        TextField name = new TextField();
        name.setText(GuiCreateBuildingInfo.getNameValue());
        name.setAnyString();
        name.setTextValidator(s -> s == null || s.length() <= 128);
        name.textFieldStyle(s -> s.textColor(0xFFFFFF).textShadow(false));
        name.layout(l -> l.flexGrow(1).height(16).minHeight(16));
        name.setTextResponder(GuiCreateBuildingInfo::setNameValue);
        nameRow.addChild(name);

        // 分类行: [‹] [当前分类] [›] [管理]
        UIElement catRow = new UIElement();
        catRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(16).gapAll(3)
            .alignItems(AlignItems.CENTER).marginBottom(4));
        panel.addChild(catRow);
        catRow.addChild(rowLabel(tr("gui.create_building.label_category"), 46));
        String cur = GuiCreateBuildingInfo.getCategoryValue();
        if (cur == null || cur.isEmpty()) cur = CategoryManager.UNCATEGORIZED;
        String curF = cur;
        Button prev = new Button().setText("‹", false);
        prev.textStyle(s -> s.textColor(0xCCCCCC));
        prev.layout(l -> l.width(16).height(16));
        prev.setOnClick(e -> cycleCategory(-1));
        catRow.addChild(prev);
        Label catLbl = new Label();
        catLbl.setText(truncateW(curF, 90), false);
        catLbl.textStyle(t -> t.textColor(0x88BBFF));
        catLbl.layout(l -> l.flexGrow(1).heightPercent(100).paddingLeft(2));
        catRow.addChild(catLbl);
        Button next = new Button().setText("›", false);
        next.textStyle(s -> s.textColor(0xCCCCCC));
        next.layout(l -> l.width(16).height(16));
        next.setOnClick(e -> cycleCategory(1));
        catRow.addChild(next);
        Button manage = new Button().setText(tr("gui.create_building.add_category"), false);
        manage.layout(l -> l.width(48).height(16));
        manage.setOnClick(e -> GuiCategoryManager.openStandalone());
        catRow.addChild(manage);

        // 只读信息行: 建筑 / 尺寸 / 依赖
        String nbtPathVal = GuiCreateBuildingInfo.getNbtPathValue();
        addReadonlyRow(panel, tr("gui.create_building.file_short",
            nbtPathVal == null || nbtPathVal.isEmpty() ? tr("gui.create_building.empty_brackets") : nbtPathVal));
        addReadonlyRow(panel, tr("gui.create_building.label_size")
            + " " + (GuiCreateBuildingInfo.getSizeValue().isEmpty() ? tr("gui.create_building.parse_hint")
            : GuiCreateBuildingInfo.getSizeValue()));
        // 依赖行: 依赖多时按 ~44 字符/行拆成多行, 保证完整显示不截断
        String depsVal = GuiCreateBuildingInfo.getDepsValue();
        if (depsVal.isEmpty()) {
            addReadonlyRow(panel, tr("gui.create_building.label_deps")
                + " " + tr("gui.create_building.empty_brackets"));
        } else {
            String prefix = tr("gui.create_building.label_deps") + " ";
            StringBuilder line = new StringBuilder();
            for (String dep : depsVal.split("\\s*,\\s*")) {
                if (dep.isBlank()) continue;
                String cand = line.length() == 0 ? dep : line + ", " + dep;
                if (cand.length() > 44 && line.length() > 0) {
                    addReadonlyRow(panel, prefix + line);
                    prefix = "      ";
                    line = new StringBuilder(dep);
                } else {
                    line = new StringBuilder(cand);
                }
            }
            if (line.length() > 0) {
                addReadonlyRow(panel, prefix + line);
            }
        }

        // 图标行: 状态 + [选图标] [✕]
        UIElement iconRow = new UIElement();
        iconRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(16).gapAll(3)
            .alignItems(AlignItems.CENTER).marginBottom(4));
        panel.addChild(iconRow);
        boolean hasIcon = GuiCreateBuildingInfo.hasIconData();
        Label iconVal = new Label();
        iconVal.setText((hasIcon ? "✓ " : tr("gui.create_building.icon") + " ")
            + truncateW(GuiCreateBuildingInfo.getIconPathValue(), 60), false);
        iconVal.textStyle(t -> t.textColor(hasIcon ? 0x55FF55 : 0x888888));
        iconVal.layout(l -> l.flexGrow(1).heightPercent(100));
        iconRow.addChild(iconVal);
        Button pickIcon = new Button().setText(tr("gui.create_building.choose_image"), false);
        pickIcon.layout(l -> l.width(64).height(16));
        pickIcon.setOnClick(e -> GuiCreateBuildingInfo.openIconPickerForTerminal(() ->
            Minecraft.getInstance().execute(() -> {
                setStatus(GuiCreateBuildingInfo.getStatusMessage(), GuiCreateBuildingInfo.getStatusColor());
                rebuild();
            })));
        iconRow.addChild(pickIcon);

        // 按钮行 1: [选建筑文件] [游戏中选区] (已选建筑时改为"重新选择"; 编辑信息模式下不显示, 建筑文件只读)
        if (editInfoTarget == null) {
        boolean hasBuilding = GuiCreateBuildingInfo.hasNbtData();
        UIElement pickRow = new UIElement();
        pickRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(16).gapAll(3).marginBottom(4));
        panel.addChild(pickRow);
        Button pickNbt = new Button().setText(hasBuilding
            ? "重新选择建筑文件" : tr("gui.create_building.choose_nbt"), false);
        pickNbt.layout(l -> l.widthPercent(50).height(16));
        pickNbt.setOnClick(e -> GuiCreateBuildingInfo.pickNbtForBlueprint((res, err) ->
            Minecraft.getInstance().execute(() -> {
                if (err != null) {
                    setStatus("✗ " + err.getMessage(), 0xFF5555);
                    return;
                }
                if (res != null) {
                    GuiCreateBuildingInfo.importNbtFile(res.file);
                    // 外部建筑文件: 显示名还空着就自动填文件名 (去扩展名)
                    if (GuiCreateBuildingInfo.getNameValue().isBlank()
                        && res.fileName != null && !res.fileName.isBlank()) {
                        String auto = res.fileName;
                        int dot = auto.lastIndexOf('.');
                        if (dot > 0) auto = auto.substring(0, dot);
                        GuiCreateBuildingInfo.setNameValue(auto);
                    }
                    setStatus(GuiCreateBuildingInfo.getStatusMessage(), GuiCreateBuildingInfo.getStatusColor());
                    rebuildList();
                }
            })));
        pickRow.addChild(pickNbt);
        Button pickInGame = new Button().setText(hasBuilding
            ? "🎯 重新选择建筑" : tr("gui.create_building.pick_in_world"), false);
        pickInGame.layout(l -> l.widthPercent(50).height(16));
        pickInGame.setOnClick(e -> startInGameSelectionForCreate());
        pickRow.addChild(pickInGame);
        }

        // 按钮行 2: [保存并创建 / 保存信息] [重置 / 取消] ([删除] 仅创建模式, 1.20.1 保留)
        boolean infoMode = editInfoTarget != null;
        UIElement actRow = new UIElement();
        actRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(16).gapAll(3).marginBottom(4));
        panel.addChild(actRow);
        Button save = new Button().setText(infoMode
            ? "保存信息" : tr("gui.create_building.save_and_create"), false);
        save.textStyle(s -> s.textColor(infoMode ? 0x88BBFF : 0x88FF88));
        save.layout(l -> l.widthPercent(infoMode ? 50 : 40).height(16));
        save.setOnClick(e -> {
            if (editInfoTarget != null) {
                onSaveEditInfo();
            } else {
                onSaveAndCreate();
            }
        });
        actRow.addChild(save);
        Button reset = new Button().setText(infoMode
            ? "取消" : tr("gui.create_building.cancel"), false);
        reset.layout(l -> l.widthPercent(infoMode ? 50 : 30).height(16));
        reset.setOnClick(e -> {
            boolean wasInfoMode = editInfoTarget != null;
            editInfoTarget = null;
            GuiCreateBuildingInfo.resetForTerminal();
            setStatus("", 0xAAAAAA);
            if (wasInfoMode) {
                open(Tab.EDIT);
            } else {
                rebuild();
            }
        });
        actRow.addChild(reset);
        // 1.20.1 特有: 删除按钮 (按当前表单 id 删建筑文件), 编辑信息模式下隐藏避免误删
        if (!infoMode) {
            Button delete = new Button().setText(tr("gui.create_building.delete"), false);
            delete.textStyle(s -> s.textColor(0xFF8888));
            delete.layout(l -> l.widthPercent(30).height(16));
            delete.setOnClick(e -> onDeleteCurrentBuilding());
            actRow.addChild(delete);
        }
    }

    /** "删除" 按钮 (创建建筑 tab, 1.20.1 保留): 按表单当前 id 调 GuiCreateBuildingInfo.doDelete. */
    private static void onDeleteCurrentBuilding() {
        String id = GuiCreateBuildingInfo.getIdValue();
        if (id != null && !id.isEmpty()) {
            GuiCreateBuildingInfo.doDelete(null, id);
            setStatus(GuiCreateBuildingInfo.getStatusMessage(), GuiCreateBuildingInfo.getStatusColor());
            refreshEditBuildings();
        }
    }

    /** "保存信息": 把表单字段写回目标建筑的 .txt (含改名换图标), 成功后回编辑建筑 tab. */
    private static void onSaveEditInfo() {
        LocalBuilding target = editInfoTarget;
        if (target == null) return;
        String name = GuiCreateBuildingInfo.getNameValue();
        boolean ok = GuiCreateBuildingInfo.saveEditInfoForTerminal(target);
        setStatus(GuiCreateBuildingInfo.getStatusMessage(), GuiCreateBuildingInfo.getStatusColor());
        if (ok) {
            editInfoTarget = null;
            GuiCreateBuildingInfo.resetForTerminal();
            refreshEditBuildings();
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                    "§a[信息已更新] §7建筑 §f" + (name == null || name.isEmpty() ? target.id : name)
                        + " §7的信息已保存"));
            }
            open(Tab.EDIT);
        }
    }

    /** 创建建筑 - 游戏内选区: 关屏 → RegionSelector → applyInGamePickForTerminal → 重开. */
    private static void startInGameSelectionForCreate() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.tell(() -> {
            mc.setScreen(null);
            RegionSelector.start(mc.player, new RegionSelector.OnCompleted() {
                @Override
                public void onCompleted(byte[] data, Path nbtFile, String sizeString, List<String> modIds) {
                    Minecraft.getInstance().execute(() -> {
                        GuiCreateBuildingInfo.applyInGamePickForTerminal(data, nbtFile, sizeString, modIds);
                        open(Tab.CREATE);
                    });
                }

                @Override
                public void onCancelled() {
                    Minecraft.getInstance().execute(() -> open(Tab.CREATE));
                }
            });
        });
    }

    /** "保存并创建": saveBuildingForTerminal + 成功时聊天提示 + 重置表单. */
    private static void onSaveAndCreate() {
        String id = GuiCreateBuildingInfo.getIdValue();
        String name = GuiCreateBuildingInfo.getNameValue();
        boolean ok = GuiCreateBuildingInfo.saveBuildingForTerminal();
        setStatus(GuiCreateBuildingInfo.getStatusMessage(), GuiCreateBuildingInfo.getStatusColor());
        if (ok) {
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                    "§a[创建成功] §7建筑 §f" + (name == null || name.isEmpty() ? id : name)
                        + " §7已保存到 prefab-extension/"));
            }
            GuiCreateBuildingInfo.resetForTerminal();
            rebuild();
        }
    }

    /** 分类 ‹ › 循环切换 (CategoryManager 列表, 忽略大小写匹配当前值). */
    private static void cycleCategory(int dir) {
        List<String> all = CategoryManager.get().getCategories();
        if (all.isEmpty()) return;
        String cur = GuiCreateBuildingInfo.getCategoryValue();
        int idx = -1;
        if (cur != null) {
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).equalsIgnoreCase(cur)) { idx = i; break; }
            }
        }
        int next;
        if (idx < 0) {
            next = dir > 0 ? 0 : all.size() - 1;
        } else {
            next = (idx + dir + all.size()) % all.size();
        }
        GuiCreateBuildingInfo.setCategoryValue(all.get(next));
        rebuild();
    }

    // ==================== Tab: 编辑建筑 ====================

    /** 扫 prefab-extension/ + prefab-download/ + 老 prefab-work/<packId>/construction/. */
    private static void refreshEditBuildings() {
        editBuildings.clear();
        try {
            editBuildings.addAll(LocalBuildingScanner.scanAll());
        } catch (Exception ex) {
            PrefabCustomAddon.LOGGER.error("[EDITOR] 扫描 LocalBuilding 失败: {}", ex.getMessage());
        }
    }

    private static List<LocalBuilding> filteredEditBuildings() {
        if (editSearch == null || editSearch.isEmpty()) return editBuildings;
        List<LocalBuilding> out = new ArrayList<>();
        for (LocalBuilding lb : editBuildings) {
            if (matchesSearch(lb, editSearch)) out.add(lb);
        }
        return out;
    }

    private static boolean matchesSearch(LocalBuilding lb, String q) {
        if (lb == null || q == null || q.isEmpty()) return true;
        if (lb.id != null && lb.id.toLowerCase(Locale.ROOT).contains(q)) return true;
        if (lb.name != null && lb.name.toLowerCase(Locale.ROOT).contains(q)) return true;
        if (lb.author != null && lb.author.toLowerCase(Locale.ROOT).contains(q)) return true;
        return lb.description != null && lb.description.toLowerCase(Locale.ROOT).contains(q);
    }

    private static void buildEditTab(UIElement listArea) {
        List<LocalBuilding> filtered = filteredEditBuildings();
        if (filtered.isEmpty()) {
            UIElement grid = new UIElement();
            grid.layout(l -> l.flexDirection(FlexDirection.ROW).flexGrow(1).minWidth(0));
            grid.setOverflowVisible(false);
            listArea.addChild(grid);
            fillEmpty(grid,
                editBuildings.isEmpty() ? tr("gui.prefab_custom_addon.no_packs")
                    : tr("gui.make_blueprint.search.no_match"),
                editBuildings.isEmpty() ? tr("gui.prefab_custom_addon.place_packs")
                    : tr("msg.try_filter"));
            return;
        }
        UIElement grid = new UIElement();
        grid.layout(l -> l.flexDirection(FlexDirection.ROW).flexGrow(1).minWidth(0));
        grid.setOverflowVisible(false);
        listArea.addChild(grid);

        ScrollerView scroller = scroller(grid);
        UIElement col = new UIElement();
        col.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100).paddingAll(2).gapAll(4));
        scroller.addScrollViewChild(col);

        int cw = wideCardW();
        int c = wideCols();
        int perPage = Math.max(1, c * rowsPerPageFor(58));
        int totalPages = Math.max(1, (filtered.size() + perPage - 1) / perPage);
        editPage = clampPage(editPage, totalPages);
        int from = editPage * perPage;
        int to = Math.min(from + perPage, filtered.size());
        for (int i = from; i < to; i += c) {
            UIElement row = new UIElement();
            row.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4));
            for (int j = i; j < Math.min(i + c, to); j++) {
                row.addChild(editBuildingCard(filtered.get(j), cw));
            }
            col.addChild(row);
        }
        if (totalPages > 1) {
            col.addChild(pager(totalPages, () -> editPage, p -> editPage = p));
        }
    }

    /** 编辑建筑卡片: 缩略图 + 名称/作者/格式 + [详情] [编辑建筑] [编辑信息]; 卡片本体不可点 (有子按钮). */
    private static UIElement editBuildingCard(LocalBuilding lb, int cw) {
        UIElement card = new UIElement();
        card.layout(l -> l.flexDirection(FlexDirection.COLUMN).width(cw).height(58)
            .paddingAll(3).gapAll(2));
        card.style(s -> s.background(Sprites.RECT_DARK));
        card.setOverflowVisible(false);

        UIElement top = new UIElement();
        top.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).gapAll(4));
        top.setOverflowVisible(false);
        card.addChild(top);

        UIElement thumb = new UIElement();
        thumb.layout(l -> l.width(THUMB).height(THUMB));
        ResourceLocation tex = ensureEditImageLoaded(lb);
        if (tex != null) {
            thumb.style(s -> s.backgroundTexture(SpriteTexture.of(tex)));
        } else {
            thumb.style(s -> s.backgroundTexture(Sprites.RECT_SOLID));
        }
        top.addChild(thumb);

        UIElement info = new UIElement();
        info.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).gapAll(1).minWidth(0));
        top.addChild(info);
        int textW = cw - THUMB - 16;
        info.addChild(cardLabel(truncateW(lb.getDisplayName(), textW), 0xFFFFFF, 11));
        info.addChild(cardLabel(truncateW(lb.author == null || lb.author.isEmpty()
            ? tr("website.author.unknown") : lb.author, textW), 0x888888, 9));
        info.addChild(cardLabel(truncateW((lb.fileExt == null ? "?" : lb.fileExt)
            + " · " + formatSize(lb.fileSize), textW), 0x777777, 9));

        UIElement btns = new UIElement();
        btns.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(16).gapAll(4));
        btns.setOverflowVisible(false);
        card.addChild(btns);
        Button view = new Button().setText(tr("gui.prefab_custom_addon.detail"), false);
        view.layout(l -> l.widthPercent(33).height(16));
        view.setOnClick(e -> openConstructionDetail(lb));
        btns.addChild(view);
        Button edit = new Button().setText("编辑建筑", false);   // 1.20.1 lang 无 editor.card.edit_building
        edit.textStyle(s -> s.textColor(0xFFDD66));
        edit.layout(l -> l.widthPercent(34).height(16));
        edit.setOnClick(e -> openEditBuilding(lb));
        btns.addChild(edit);
        Button editInfo = new Button().setText("编辑信息", false); // 1.20.1 lang 无 editor.card.edit_info
        editInfo.textStyle(s -> s.textColor(0x88BBFF));
        editInfo.layout(l -> l.widthPercent(33).height(16));
        editInfo.setOnClick(e -> openEditInfo(lb));
        btns.addChild(editInfo);
        return card;
    }

    /** 编辑信息: 回填表单字段到创建建筑 tab (编辑信息模式), 建筑文件本体不动. */
    private static void openEditInfo(LocalBuilding lb) {
        editInfoTarget = lb;
        GuiCreateBuildingInfo.loadForEditInfo(lb);
        open(Tab.CREATE);
    }

    /** 查看: 打开 GuiConstructionDetail (3D 预览), 返回时回编辑建筑 tab. */
    private static void openConstructionDetail(LocalBuilding lb) {
        if (lb.filePath == null || !Files.exists(lb.filePath)) {
            setStatus("✗ 建筑文件不存在: " + lb.id, 0xFFAA55);
            return;
        }
        GuiConstructionDetail.open(buildDetailInfo(lb), () -> GuiExtensionPackEditor.open(Tab.EDIT));
    }

    /** 编辑: 启动 3D 原理图编辑 (世界内幽灵预览), 失败给状态提示. */
    private static void openEditBuilding(LocalBuilding lb) {
        String err = startSchematicEdit(lb);
        if (err != null) {
            setStatus(err, 0xFF5555);
        }
    }

    /** 编辑建筑缩略图: 异步解码 + 注册 DynamicTexture, 完成后刷新列表. */
    private static ResourceLocation ensureEditImageLoaded(LocalBuilding lb) {
        if (lb.imagePath == null || !Files.exists(lb.imagePath)) return null;
        String key = lb.imagePath.toString();
        if (EDIT_IMG.containsKey(key)) return EDIT_IMG.get(key);
        if (EDIT_IMG_LOADING.contains(key)) return null;
        EDIT_IMG_LOADING.add(key);
        Path imgPath = lb.imagePath;
        CompletableFuture.runAsync(() -> {
            try (InputStream is = Files.newInputStream(imgPath)) {
                BufferedImage img = ImageIO.read(is);
                // 纹理注册必须回主线程 (GL 调用), 后台线程只做图片解码
                Minecraft.getInstance().execute(() -> {
                    if (img == null) {
                        EDIT_IMG.put(key, null);
                        EDIT_IMG_LOADING.remove(key);
                        return;
                    }
                    DynamicTexture tex = uploadIconTexture(img);
                    // 1.20.1: TextureManager.register(ResourceLocation, DynamicTexture) 返回 void,
                    // ResourceLocation 自己造 (不返回自动生成的)
                    ResourceLocation loc = null;
                    if (tex != null) {
                        loc = new ResourceLocation("prefab_extension_editor",
                            "edit_" + TEX_GEN.incrementAndGet());
                        Minecraft.getInstance().getTextureManager().register(loc, tex);
                    }
                    EDIT_IMG.put(key, loc);
                    EDIT_IMG_LOADING.remove(key);
                    if (loc != null) {
                        rebuildList();
                    }
                });
            } catch (Exception e) {
                PrefabCustomAddon.LOGGER.warn("[EDITOR] 加载建筑预览图失败 ({}): {}", imgPath, e.getMessage());
                Minecraft.getInstance().execute(() -> {
                    EDIT_IMG.put(key, null);
                    EDIT_IMG_LOADING.remove(key);
                });
            }
        });
        return null;
    }

    // ==================== Tab: 设置 ====================

    private static void buildSettingsTab(UIElement listArea) {
        UIElement panel = new UIElement();
        panel.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).widthPercent(100)
            .gapAll(3).paddingAll(2));
        panel.style(s -> s.background(Sprites.RECT_DARK));
        panel.setOverflowVisible(false);
        listArea.addChild(panel);

        ScrollerView scroller = scroller(panel);
        UIElement col = new UIElement();
        col.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100).paddingAll(2).gapAll(3));
        scroller.addScrollViewChild(col);

        // === 性能 / 动画 ===
        // (1.21.1 蓝本这里还有 "▍ 建造材料" 装饰段, 1.20.1 没有对应控件, 舍弃)
        col.addChild(sectionLabel("▍ 性能 / 动画"));
        PlayerPreferences prefs = PlayerPreferences.get();
        BuildAnimationMode mode = prefs.getBuildAnimationMode();
        UIElement modeRow = new UIElement();
        modeRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(16).gapAll(3));
        col.addChild(modeRow);
        BuildAnimationMode[] modes = BuildAnimationMode.values();
        for (BuildAnimationMode m : modes) {
            BuildAnimationMode fm = m;
            Button b = new Button().setText(m.name(), false);
            boolean sel = m == mode;
            b.textStyle(s -> s.textColor(sel ? 0xFFFF88 : 0xBBBBBB));
            b.buttonStyle(s -> s.baseTexture(sel ? Sprites.RECT_LIGHT : Sprites.RECT_DARK)
                .hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
            b.layout(l -> l.widthPercent(100.0f / modes.length).height(16));
            b.setOnClick(e -> {
                PlayerPreferences.get().setBuildAnimationMode(fm);
                setStatus("§f建造动画: " + modeStatusText(fm), 0x55FF55);
                rebuildList();
            });
            modeRow.addChild(b);
        }
        col.addChild(cardLabel(modeSubtitle(mode), 0x888888, 11));

        // === 快捷操作 ===
        col.addChild(sectionLabel("▍ 快捷操作"));
        Button reset = new Button().setText("♻ 重置个人偏好 (动画模式/收藏等)", false);
        reset.layout(l -> l.widthPercent(100).height(16));
        reset.setOnClick(e -> {
            try {
                PlayerPreferences p = PlayerPreferences.get();
                int removedFavs = p.favoriteKeys != null ? p.favoriteKeys.size() : 0;
                p.favoriteKeys = new ArrayList<>();
                p.autoDepCheckOnOpen = false;
                p.buildAnimationMode = BuildAnimationMode.OFF;
                p.save();
                setStatus("已重置偏好 (清空 " + removedFavs + " 个收藏)", 0x55FF55);
                rebuildList();
            } catch (Throwable t) {
                setStatus("✗ 重置失败: " + t.getMessage(), 0xFF5555);
            }
        });
        col.addChild(reset);

        // 1.20.1 特有: 自动检测依赖 toggle (打开建筑时, 客户端个人设置)
        boolean autoDep = PlayerPreferences.get().autoDepCheckOnOpen;
        Button autoDepBtn = new Button().setText(
            "打开建筑时自动检测依赖  " + (autoDep ? "§a✓ ON" : "§7✗ OFF"), false);
        autoDepBtn.textStyle(s -> s.textColor(autoDep ? 0xFFFF88 : 0xBBBBBB));
        autoDepBtn.buttonStyle(s -> s.baseTexture(autoDep ? Sprites.RECT_LIGHT : Sprites.RECT_DARK)
            .hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
        autoDepBtn.layout(l -> l.widthPercent(100).height(16));
        autoDepBtn.setOnClick(e -> {
            PlayerPreferences p = PlayerPreferences.get();
            boolean newVal = !p.autoDepCheckOnOpen;
            p.autoDepCheckOnOpen = newVal;
            p.save();
            setStatus(newVal ? "打开建筑时自动检测依赖 已开启" : "自动检测依赖 已关闭", 0x55FF55);
            rebuildList();
        });
        col.addChild(autoDepBtn);

        // 强制启用 KubeJS 联动 (调试用)
        boolean force = PlayerPreferences.get().forceKubeJSTab;
        Button kube = new Button().setText(tr("gui.settings.force_kubejs_label")
            + "  " + (force ? "§✓ ON" : "✗ OFF"), false);
        kube.textStyle(s -> s.textColor(force ? 0xFFFF88 : 0xBBBBBB));
        kube.buttonStyle(s -> s.baseTexture(force ? Sprites.RECT_LIGHT : Sprites.RECT_DARK)
            .hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
        kube.layout(l -> l.widthPercent(100).height(16));
        kube.setOnClick(e -> {
            PlayerPreferences p = PlayerPreferences.get();
            boolean newVal = !p.forceKubeJSTab;
            p.forceKubeJSTab = newVal;
            p.save();
            KubeJSIntegration.setForceEnabled(newVal);
            KubeJSIntegration.invalidateCache();
            setStatus(tr(newVal ? "gui.settings.force_kubejs_on" : "gui.settings.force_kubejs_off"), 0x55FF55);
            rebuild();
        });
        col.addChild(kube);
        col.addChild(cardLabel(tr("gui.settings.force_kubejs_hint"), 0x888888, 11));
    }

    private static String modeStatusText(BuildAnimationMode m) {
        return switch (m) {
            case OFF -> "OFF (瞬建, 不放动画)";
            case FALL -> "FALL (竖直下落, 1 块/tick)";
            case RAIN -> "RAIN (方块雨, 起点随机偏移)";
            case THROW -> "THROW (四周抛过来, 抛物线)";
        };
    }

    private static String modeSubtitle(BuildAnimationMode m) {
        return switch (m) {
            case OFF -> "(瞬建, 不放动画, 跟原版 prefab 一样快, 可能卡顿)";
            case FALL -> "(每个方块从空中 8 格高处竖直掉下, 1 块/tick)";
            case RAIN -> "(每个方块从更高的空中散点掉下, 起点横向 ±2 格随机, 1 块/tick)";
            case THROW -> "(每个方块从建筑外圈 ±10 格抛过来, 抛物线轨迹, 1 块/tick)";
        };
    }

    // ==================== Tab: 制作蓝图 ====================

    private static void buildMakeBlueprintTab(UIElement listArea) {
        // KubeJS 没装时显示居中提示, 不画表单
        if (!KubeJSIntegration.isLoaded()) {
            buildKubeJSRequiredPanel(listArea);
            return;
        }

        UIElement panel = new UIElement();
        panel.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).widthPercent(100).gapAll(3));
        panel.setOverflowVisible(false);
        listArea.addChild(panel);

        // 滚动表单区
        ScrollerView scroller = scroller(panel);
        UIElement form = new UIElement();
        form.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100).paddingAll(2).gapAll(3));
        scroller.addScrollViewChild(form);

        form.addChild(cardLabel(tr("gui.make_blueprint.subtitle"), 0xAAAAAA, 11));
        form.addChild(cardLabel(tr("gui.make_blueprint.restart_hint"), 0xFFAA55, 11));

        // 显示名 (唯一可编辑输入框; ns/id 自动生成)
        UIElement nameRow = new UIElement();
        nameRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(16).gapAll(3)
            .alignItems(AlignItems.CENTER));
        form.addChild(nameRow);
        nameRow.addChild(rowLabel(tr("gui.make_blueprint.label.name"), 46));
        TextField name = new TextField();
        name.setText(SAVED_BLUEPRINT_NAME);
        name.setAnyString();
        name.setTextValidator(s -> s == null || s.length() <= 64);
        name.textFieldStyle(s -> s.textColor(0xFFFFFF).textShadow(false));
        name.layout(l -> l.flexGrow(1).height(16).minHeight(16));
        name.setTextResponder(s -> SAVED_BLUEPRINT_NAME = s == null ? "" : s);
        nameRow.addChild(name);

        // 建筑来源按钮: 选建筑文件 / 游戏内选建筑 / 选贴图
        form.addChild(sourceButton(
            selectedBuilding != null && !"in_game".equals(selectedBuilding.source)
                ? tr("gui.make_blueprint.btn.pick_building_file_set", selectedBuilding.getDisplayName())
                : tr("gui.make_blueprint.btn.pick_building_file_empty"),
            e -> pickNbtForBlueprint()));
        form.addChild(sourceButton(
            selectedBuilding != null && "in_game".equals(selectedBuilding.source)
                ? tr("gui.make_blueprint.btn.pick_building_ingame_set", selectedBuilding.getDisplayName())
                : tr("gui.make_blueprint.btn.pick_building_ingame_empty"),
            e -> pickInGameForBlueprint()));
        form.addChild(sourceButton(
            selectedTextureSource != null
                ? tr("gui.make_blueprint.btn.pick_texture_set", selectedTextureSource.getFileName().toString())
                : tr("gui.make_blueprint.btn.pick_texture_empty"),
            e -> openTextureFileDialog()));

        // 锁定 toggle 已移除: 蓝图强制锁定 (spec.locked 恒 true), 玩家不能 rebind

        // 9 宫格配方
        form.addChild(cardLabel(tr("gui.make_blueprint.recipe_label"), 0xAAAAAA, 11));
        for (int row = 0; row < 3; row++) {
            UIElement gridRow = new UIElement();
            gridRow.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(2));
            form.addChild(gridRow);
            for (int colI = 0; colI < 3; colI++) {
                gridRow.addChild(recipeCell(row * 3 + colI, 22));
            }
        }
        // 1.20.1 lang 没有 recipe_hint, 硬编码中文
        form.addChild(cardLabel("鼠标左键选择配方物品，右键删除", 0x777777, 11));

        // 底部固定操作行: [生成并注册] [打开 kubejs 文件夹]
        UIElement bottom = new UIElement();
        bottom.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(18).gapAll(3));
        panel.addChild(bottom);
        Button generate = new Button().setText(tr("gui.make_blueprint.btn.generate"), false);
        generate.textStyle(s -> s.textColor(0x88FF88));
        generate.layout(l -> l.widthPercent(50).height(18));
        generate.setOnClick(e -> doGenerateBlueprint());
        bottom.addChild(generate);
        Button folder = new Button().setText(tr("gui.make_blueprint.btn.open_folder"), false);
        folder.layout(l -> l.widthPercent(50).height(18));
        folder.setOnClick(e -> openKubejsFolder());
        bottom.addChild(folder);
    }

    /** 全宽来源选择按钮 (选建筑文件 / 游戏内 / 选贴图). */
    private static UIElement sourceButton(String label, Consumer<Button> onClick) {
        Button b = new Button().setText(truncateW(stripCodes(label), 120), false);
        b.layout(l -> l.widthPercent(100).height(16));
        b.setOnClick(e -> onClick.accept(b));
        return b;
    }

    /** 配方格: ItemSlot 显示物品, 点击弹 GuiItemSearchPopup 选材. */
    private static UIElement recipeCell(int idx, int size) {
        final int fidx = idx;
        Button cell = new Button().noText();
        cell.buttonStyle(s -> s.baseTexture(Sprites.RECT_DARK)
            .hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
        cell.layout(l -> l.width(size).height(size));
        cell.setOverflowVisible(false);
        String item = recipeItems.get(idx);
        ItemStack stack = parseItemStack(item);
        if (stack != null) {
            ItemSlot slot = new ItemSlot();
            try {
                slot.setValue(stack);
            } catch (Throwable ignore) {}
            slot.layout(l -> l.width(size - 4).height(size - 4).alignItems(AlignItems.CENTER)
                .justifyContent(AlignContent.CENTER));
            cell.addChild(slot);
        } else if (item != null && !item.isBlank()) {
            Label lbl = new Label();
            String shortName = item.contains(":") ? item.substring(item.indexOf(':') + 1) : item;
            lbl.setText(truncateW(shortName, size - 4), false);
            lbl.textStyle(t -> t.textColor(0xCCCCCC));
            lbl.layout(l -> l.widthPercent(100).heightPercent(100).alignItems(AlignItems.CENTER)
                .justifyContent(AlignContent.CENTER));
            cell.addChild(lbl);
        } else {
            Label lbl = new Label();
            lbl.setText("-", false);
            lbl.textStyle(t -> t.textColor(0x666666));
            lbl.layout(l -> l.widthPercent(100).heightPercent(100).alignItems(AlignItems.CENTER)
                .justifyContent(AlignContent.CENTER));
            cell.addChild(lbl);
        }
        cell.setOnClick(e -> GuiItemSearchPopup.open(fidx, id -> {
            recipeItems.set(fidx, id);
            setStatus(tr("gui.make_blueprint.status.cell_set", String.valueOf(fidx + 1),
                id == null ? "-" : id), 0x55FF55);
            rebuildList();
        }));
        // 右键清空该格: 必须 capture 阶段 (第三参 true) — ItemSlot 子元素在 bubble 阶段
        // 会 stopPropagation(), capture 自顶向下先于它触发, 不受影响
        cell.addEventListener(UIEvents.MOUSE_DOWN, e -> {
            if (e.button == 1) {
                recipeItems.set(fidx, null);
                setStatus(tr("gui.make_blueprint.status.cell_cleared", String.valueOf(fidx + 1)), 0xAAAAAA);
                rebuildList();
            }
        }, true);
        return cell;
    }

    /** 制作蓝图 - 选建筑文件 (回调直接更新 selectedBuilding, 不再轮询). */
    private static void pickNbtForBlueprint() {
        setStatus(tr("gui.make_blueprint.poll.wait_nbt"), 0xFFAA55);
        GuiCreateBuildingInfo.pickNbtForBlueprint((res, err) -> Minecraft.getInstance().execute(() -> {
            handleNbtPickedForBlueprint(res, err);
            rebuildList();
        }));
    }

    /**
     * 制作蓝图 - 游戏内选建筑: 关屏 → pickInGameForBlueprint → 重开编辑器.
     * 必须先关屏, 否则玩家看不见选区预览.
     */
    private static void pickInGameForBlueprint() {
        Minecraft mc = Minecraft.getInstance();
        setStatus(tr("gui.make_blueprint.poll.wait_ingame"), 0xFFAA55);
        mc.tell(() -> {
            mc.setScreen(null);
            GuiCreateBuildingInfo.pickInGameForBlueprint((res, err) -> Minecraft.getInstance().execute(() -> {
                handleInGamePickedForBlueprint(res, err);
                open(Tab.MAKE_BLUEPRINT);
            }));
        });
    }

    /**
     * 选完 NBT 文件的回调 (制作蓝图 tab). res=null 时取消不动状态.
     */
    private static void handleNbtPickedForBlueprint(NbtPickResult res, Throwable err) {
        if (err != null) {
            setStatus("NBT 选择失败: " + err.getMessage(), 0xFF5555);
            return;
        }
        if (res == null) {
            setStatus("已取消 NBT 选择", 0xFFAA55);
            return;
        }
        String id = res.fileName.toLowerCase(Locale.ROOT);
        if (id.endsWith(".nbt")) id = id.substring(0, id.length() - 4);
        else if (id.endsWith(".litematic")) id = id.substring(0, id.length() - 10);
        else if (id.endsWith(".schem")) id = id.substring(0, id.length() - 6);
        else if (id.endsWith(".schematic")) id = id.substring(0, id.length() - 9);
        String lowerName = res.fileName.toLowerCase(Locale.ROOT);
        String pickedExt = ".nbt";
        for (String e : new String[]{".litematic", ".schematic", ".schem", ".nbt"}) {
            if (lowerName.endsWith(e)) { pickedExt = e; break; }
        }
        // 根据所选文件名自动填显示名 (去后缀, 保留原大小写; 每次选择都覆盖)
        String autoName = res.fileName;
        int dotIdx = autoName.lastIndexOf('.');
        if (dotIdx > 0) autoName = autoName.substring(0, dotIdx);
        SAVED_BLUEPRINT_NAME = autoName;
        LocalBuilding lb = new LocalBuilding(
            id, res.fileName, "", "", java.util.Collections.emptyList(), "",
            pickedExt, 0L, null, "external",
            res.file.toPath(), null, null);
        selectedBuilding = lb;
        setStatus("已选建筑文件: " + res.fileName
            + (res.sizeString != null && !res.sizeString.isEmpty() ? " (" + res.sizeString + ")" : "")
            + " [外源]", 0x55FF55);
        PrefabCustomAddon.LOGGER.info("[MAKE-BLUEPRINT] 选完建筑文件: id={} size={} path={}",
            id, res.sizeString, res.file.toPath());
    }

    /**
     * 选完游戏内区域的回调 (制作蓝图 tab, 1.20.1 逻辑).
     * NBT 持久化到 prefab-extension/blueprint-buildings/ — 蓝图专属建筑目录, 不进
     * 建筑选择列表 (浏览器/收藏/蓝图选择都不扫这里), 蓝图链路按 constructionId 直读.
     */
    private static void handleInGamePickedForBlueprint(InGamePickResult res, Throwable err) {
        if (err != null) {
            setStatus("游戏内选区失败: " + err.getMessage(), 0xFF5555);
            return;
        }
        if (res == null) {
            setStatus("已取消游戏内选区", 0xFFAA55);
            return;
        }
        if (res.nbtData == null || res.nbtData.length == 0) {
            setStatus("游戏内选区 NBT 为空", 0xFF5555);
            return;
        }
        int h = Arrays.hashCode(res.nbtData);
        String shortHash = String.format("%06x", h & 0xFFFFFF);
        long ts = System.currentTimeMillis() / 1000L;
        String id = "ingame_" + ts + "_" + shortHash;
        String displayName = res.sizeString != null && !res.sizeString.isEmpty()
            ? "内嵌建筑 " + res.sizeString
            : ("内嵌建筑 " + id);
        Path extRoot;
        Path nbtPath;
        try {
            // 老 in_game_* 临时文件迁到 blueprint-buildings/ (一次性)
            LocalBuildingScanner.migrateLegacyBlueprintBuildings();
            extRoot = LocalBuildingScanner.getBlueprintBuildingsRoot();
            Files.createDirectories(extRoot);
            nbtPath = extRoot.resolve(id + ".nbt");
            Files.write(nbtPath, res.nbtData);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[MAKE-BLUEPRINT] 持久化 NBT 失败", t);
            setStatus("写入 blueprint-buildings/ 失败: " + t.getMessage(), 0xFF5555);
            return;
        }
        LocalBuilding lb = new LocalBuilding(
            id, displayName, "", "", res.modIds == null ? java.util.Collections.emptyList() : res.modIds, "",
            ".nbt", (long) res.nbtData.length, null, "blueprint",
            nbtPath, null, null);
        selectedBuilding = lb;
        setStatus("已选游戏内建筑: " + displayName
            + (res.sizeString != null && !res.sizeString.isEmpty() ? " (" + res.sizeString + ")" : "")
            + " [已存到 prefab-extension/blueprint-buildings/]", 0x55FF55);
    }

    /** 选贴图: 系统文件选择对话框 (SystemFilePicker, 异步回调). */
    private static void openTextureFileDialog() {
        try {
            SystemFilePicker.openAsync(
                tr("gui.make_blueprint.pick_texture"),
                Arrays.asList("png"),
                result -> Minecraft.getInstance().execute(() -> {
                    if (result.isOk() && result.file != null && result.file.exists()) {
                        selectedTextureSource = result.file.toPath();
                        setStatus("已选贴图: " + selectedTextureSource.getFileName(), 0x55FF55);
                        rebuildList();
                    } else if (result.isCancelled()) {
                        setStatus("取消选贴图", 0xFFAA55);
                    } else {
                        setStatus("选贴图失败: " + (result.message == null ? "未知错误" : result.message), 0xFF5555);
                    }
                }));
        } catch (Throwable th) {
            PrefabCustomAddon.LOGGER.error("[MAKE-BLUEPRINT] SystemFilePicker 打开失败", th);
            setStatus("打开文件选择器失败: " + th.getMessage(), 0xFF5555);
        }
    }

    /**
     * 收集表单, 构造 {@link CustomBlueprintCodeGenerator.Spec}, 调 generate.
     * 成功 / 失败都更新状态 + 聊天栏.
     * <p>1.20.1 保留: 配方非空校验 (need_recipe) + 外部建筑复制进
     * blueprint-buildings/ (source="blueprint"), 不进建筑选择列表.</p>
     */
    private static void doGenerateBlueprint() {
        try {
            String displayName = SAVED_BLUEPRINT_NAME == null ? "" : SAVED_BLUEPRINT_NAME.trim();
            String subNamespace = "player_pack";
            String itemId = "player_blueprint";

            // 校验关键字段
            if (selectedBuilding == null) {
                setStatus(tr("gui.make_blueprint.status.need_building"), 0xFF5555);
                return;
            }
            if (selectedTextureSource == null || !Files.exists(selectedTextureSource)) {
                setStatus(tr("gui.make_blueprint.status.need_texture"), 0xFF5555);
                return;
            }
            // 1.20.1 保留: 配方非空校验
            boolean hasRecipe = false;
            for (String s : recipeItems) {
                if (s != null && !s.isBlank()) { hasRecipe = true; break; }
            }
            if (!hasRecipe) {
                setStatus(tr("gui.make_blueprint.status.need_recipe"), 0xFF5555);
                return;
            }

            // 1.20.1 保留: 外源建筑复制进 blueprint-buildings/ (蓝图专属目录, 不进建筑选择列表),
            // 绑定复制后的 id, 右键才能按 constructionId 找到. 已在扫描目录里的
            // (extension / download / legacy-pack:*) 和已持久化的 (blueprint) 不重复复制.
            LocalBuildingScanner.migrateLegacyBlueprintBuildings();
            if (selectedBuilding.filePath != null && Files.exists(selectedBuilding.filePath)
                    && !"extension".equals(selectedBuilding.source)
                    && !"download".equals(selectedBuilding.source)
                    && !"blueprint".equals(selectedBuilding.source)
                    && (selectedBuilding.source == null || !selectedBuilding.source.startsWith("legacy-pack"))) {
                try {
                    byte[] bytes = Files.readAllBytes(selectedBuilding.filePath);
                    String origName = selectedBuilding.filePath.getFileName().toString();
                    String ext = origName.contains(".") ? origName.substring(origName.lastIndexOf('.')) : ".nbt";
                    int fh = Arrays.hashCode(bytes);
                    String newId = String.format("mb_%d_%06x", System.currentTimeMillis() / 1000L, fh & 0xFFFFFF);
                    Path extRoot = LocalBuildingScanner.getBlueprintBuildingsRoot();
                    Files.createDirectories(extRoot);
                    Path newPath = extRoot.resolve(newId + ext);
                    Files.write(newPath, bytes);
                    String copyName = (selectedBuilding.name == null || selectedBuilding.name.isBlank())
                        ? newId : selectedBuilding.name;
                    Path origPath = selectedBuilding.filePath;
                    selectedBuilding = new LocalBuilding(
                        newId, copyName, selectedBuilding.author, selectedBuilding.description,
                        selectedBuilding.dependencies, selectedBuilding.category,
                        ext, (long) bytes.length, null, "blueprint",
                        newPath, null, null);
                    PrefabCustomAddon.LOGGER.info("[MAKE-BLUEPRINT] 外部建筑已复制进 blueprint-buildings: {} -> {}",
                        origPath, newPath);
                } catch (Throwable t) {
                    PrefabCustomAddon.LOGGER.error("[MAKE-BLUEPRINT] 复制外部建筑失败", t);
                    setStatus("复制建筑文件到 blueprint-buildings/ 失败: " + t.getMessage(), 0xFF5555);
                    return;
                }
            }

            // 构造 Spec (validate() 会自动加 player_ 前缀)
            CustomBlueprintCodeGenerator.Spec spec = new CustomBlueprintCodeGenerator.Spec();
            String displayNameFallback = (selectedBuilding.getDisplayName() != null
                && !selectedBuilding.getDisplayName().isBlank())
                    ? selectedBuilding.getDisplayName() : itemId;
            spec.displayName = displayName.isEmpty() ? displayNameFallback : displayName;
            spec.namespace = subNamespace;
            spec.itemId = itemId;
            spec.packName = selectedBuilding.source == null ? "local" : selectedBuilding.source;
            spec.constructionId = selectedBuilding.id;
            spec.locked = true;  // 强制锁定, 玩家不能 rebind
            spec.sourceTexture = selectedTextureSource;
            // 直接传 9 槽位 (row-major 0..8), null/空表示无物品; 压缩会打乱配方位置
            spec.recipeItems = new ArrayList<>(recipeItems);
            // 自动加序号: 同名脚本已存在时改 <id>_2, _3...
            spec.itemId = autoIncrementItemIdIfTaken(spec.namespace, spec.itemId);

            CustomBlueprintCodeGenerator.Result result = CustomBlueprintCodeGenerator.generate(spec);

            String nsItem = spec.namespace + ":" + spec.itemId;
            setStatus(tr("gui.make_blueprint.status.generated", nsItem), 0x55FF55);
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal(
                    "§a[制作蓝图] 已生成 KubeJS 文件: §f" + nsItem
                    + "§a. 脚本=" + result.jsFile.getFileName()
                    + ", 配方=" + result.jsonFile.getFileName()
                    + ", 贴图=" + result.textureFile.getFileName()
                    + ".\n§e请重启游戏 (startup_scripts 只在启动时跑)").withStyle(ChatFormatting.AQUA));
            }
            PrefabCustomAddon.LOGGER.info("[MAKE-BLUEPRINT] generated {}: js={} json={} tex={}",
                nsItem, result.jsFile, result.jsonFile, result.textureFile);

            // 生成成功后清空表单
            selectedBuilding = null;
            selectedTextureSource = null;
            for (int i = 0; i < recipeItems.size(); i++) {
                recipeItems.set(i, null);
            }
            SAVED_BLUEPRINT_NAME = "";
            rebuildList();
        } catch (IllegalArgumentException iae) {
            setStatus(tr("gui.make_blueprint.status.param_err", iae.getMessage()), 0xFF5555);
            PrefabCustomAddon.LOGGER.error("[MAKE-BLUEPRINT] 参数校验失败", iae);
        } catch (Throwable t) {
            setStatus(tr("gui.make_blueprint.status.gen_fail", t.getMessage()), 0xFF5555);
            PrefabCustomAddon.LOGGER.error("[MAKE-BLUEPRINT] 生成失败", t);
            Minecraft mc2 = Minecraft.getInstance();
            if (mc2.player != null) {
                mc2.player.sendSystemMessage(Component.literal(
                    "§c[制作蓝图] 生成失败: " + t.getMessage()).withStyle(ChatFormatting.RED));
            }
        }
    }

    /**
     * 检查 {@code <ns>_<id>_blueprint.js} / recipes.js 是否已存在.
     * 存在就把 id 改成 {@code <id>_2}, 还存在再 _3...
     */
    private static String autoIncrementItemIdIfTaken(String ns, String id) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return id;
            Path kubejsDir = mc.gameDirectory.toPath().resolve("kubejs");
            Path startupScripts = kubejsDir.resolve("startup_scripts");
            Path serverScripts = kubejsDir.resolve("server_scripts");
            if (!Files.isDirectory(startupScripts) || !Files.isDirectory(serverScripts)) {
                return id;
            }
            String jsName = ns + "_" + id + "_blueprint.js";
            Path jsPath = startupScripts.resolve(jsName);
            Path recipePath = serverScripts.resolve(ns + "_" + id + "_blueprint_recipes.js");
            if (!Files.exists(jsPath) && !Files.exists(recipePath)) {
                return id;
            }
            int n = 2;
            while (n < 1000) {
                String candidate = id + "_" + n;
                String jsName2 = ns + "_" + candidate + "_blueprint.js";
                String recipeName2 = ns + "_" + candidate + "_blueprint_recipes.js";
                if (!Files.exists(startupScripts.resolve(jsName2))
                    && !Files.exists(serverScripts.resolve(recipeName2))) {
                    PrefabCustomAddon.LOGGER.info("[MAKE-BLUEPRINT] itemId '{}' 已存在, 自动改为 '{}'", id, candidate);
                    return candidate;
                }
                n++;
            }
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[MAKE-BLUEPRINT] autoIncrementItemIdIfTaken 失败: {}", t.getMessage());
        }
        return id;
    }

    /** 打开 minecraft/kubejs 文件夹 (Desktop.open). 失败给个状态提示. */
    private static void openKubejsFolder() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                setStatus("MC 实例未就绪", 0xFF5555);
                return;
            }
            Path kubejsDir = mc.gameDirectory.toPath().resolve("kubejs");
            if (!Files.exists(kubejsDir)) {
                Files.createDirectories(kubejsDir);
            }
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(kubejsDir.toFile());
                setStatus(tr("gui.make_blueprint.status.folder_opened"), 0x55FF55);
            } else {
                setStatus(tr("gui.make_blueprint.status.folder_no_desktop", kubejsDir.toString()), 0xFFAA55);
            }
        } catch (Throwable t) {
            setStatus(tr("gui.make_blueprint.status.folder_fail", t.getMessage()), 0xFF5555);
            PrefabCustomAddon.LOGGER.error("[MAKE-BLUEPRINT] 打开 kubejs 文件夹失败", t);
        }
    }

    /** KubeJS 未安装时的居中提示面板 ("制作蓝图" / "蓝图管理" 共用). */
    private static void buildKubeJSRequiredPanel(UIElement listArea) {
        UIElement box = new UIElement();
        box.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).widthPercent(100)
            .alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER).gapAll(4));
        listArea.addChild(box);
        String[] lines = {
            tr("gui.kubejs_required.title"),
            tr("gui.kubejs_required.desc"),
            "",
            tr("gui.kubejs_required.install_hint"),
            tr("gui.kubejs_required.force_hint")
        };
        for (String line : lines) {
            Label l = new Label();
            l.setText(line, false);
            l.textStyle(t -> t.textColor(0xCCCCCC));
            l.layout(l2 -> l2.widthPercent(100).height(12).alignItems(AlignItems.CENTER)
                .justifyContent(AlignContent.CENTER));
            box.addChild(l);
        }
    }

    // ==================== Tab: 蓝图管理 ====================

    /**
     * 枚举所有 KubeJS 联动蓝图 (= prefab_custom_addon:player_blueprint tag 里的 Item).
     * 每个 Item 拼出对应的 KubeJS 文件路径, 文件不在本地时渲染 "文件已丢失" 标记.
     */
    private static void rebuildManagedBlueprints() {
        managedBlueprints.clear();
        try {
            // 1.20.1: ResourceLocation 用 new 构造 (无 fromNamespaceAndPath)
            ResourceLocation tagLoc = new ResourceLocation(
                PrefabCustomAddon.MOD_ID, "player_blueprint");
            TagKey<Item> tagKey = TagKey.create(Registries.ITEM, tagLoc);
            var holders = BuiltInRegistries.ITEM.getTagOrEmpty(tagKey);
            Path mcDir = minecraftDir();
            Path kubejsDir = mcDir.resolve("kubejs");
            Path startupScripts = kubejsDir.resolve("startup_scripts");
            Path serverScripts = kubejsDir.resolve("server_scripts");
            for (var holder : holders) {
                Item item = holder.value();
                if (item == null) continue;
                // 排除 mod 原生 CustomBlueprintItem (没有对应的 KubeJS 文件可删)
                if (item instanceof CustomBlueprintItem) continue;
                // 1.20.1: Holder 上没有便捷 key(), 直接用注册表拿 ResourceLocation
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
                if (key == null) continue;
                String namespace = key.getNamespace();
                String itemId = key.getPath();
                // 本局已删的蓝图不再列出 (物品注册还在, 但文件已删)
                if (DELETED_THIS_SESSION.contains(namespace + ":" + itemId)) continue;
                String jsName = namespace + "_" + itemId + "_blueprint.js";
                String recipeName = namespace + "_" + itemId + "_blueprint_recipes.js";
                Path jsFile = startupScripts.resolve(jsName);
                Path recipeFile = serverScripts.resolve(recipeName);
                Path textureFile = kubejsDir.resolve("assets")
                    .resolve(namespace).resolve("textures").resolve("item").resolve(itemId + ".png");
                String displayName = new ItemStack(item).getHoverName().getString();
                managedBlueprints.add(new ManagedBlueprint(item, displayName, namespace, itemId,
                    jsFile, recipeFile, textureFile));
            }
            managedBlueprints.sort((a, b) -> a.displayName.compareToIgnoreCase(b.displayName));
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[MANAGE-BLUEPRINT] 枚举失败: {}", t.toString());
        }
    }

    /**
     * 删除指定蓝图对应的 KubeJS 文件 (startup script + recipe + texture + 规格文件),
     * 并联动删 blueprint-buildings/ 里的建筑文件 (1.20.1 保留), 提示 /reload.
     */
    private static void deleteManagedBlueprint(ManagedBlueprint bp) {
        // 先读规格 (删除规格文件前): 拿 constructionId 用于联动删 blueprint-buildings 里的建筑文件
        String bpConstructionId = null;
        try {
            com.prefab.addon.integration.BlueprintSpecStore.SpecEntry specEntry =
                com.prefab.addon.integration.BlueprintSpecStore.readSpec(bp.itemId);
            if (specEntry != null && specEntry.constructionId() != null
                    && !specEntry.constructionId().isEmpty()) {
                bpConstructionId = specEntry.constructionId();
            }
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[MANAGE-BLUEPRINT] 读规格失败 ({}): {}", bp.itemId, t.toString());
        }
        // 先把 ns:itemId 加黑名单, 下面的 rebuildManagedBlueprints() 就不会再列出来
        DELETED_THIS_SESSION.add(bp.nsItem());
        int deleted = 0;
        int missing = 0;
        StringBuilder log = new StringBuilder();
        Path[] targets = new Path[]{
            bp.jsFile, bp.recipeFile, bp.textureFile,
            // 默认绑定规格文件: 不删的话重装后蓝图会凭规格文件"复活"默认绑定
            com.prefab.addon.integration.BlueprintSpecStore.specPath(bp.itemId)};
        for (Path p : targets) {
            try {
                if (Files.deleteIfExists(p)) {
                    deleted++;
                    log.append("✓").append(p.getFileName()).append(" ");
                } else {
                    missing++;
                    log.append("✗").append(p.getFileName()).append("(已无) ");
                }
            } catch (Exception e) {
                missing++;
                log.append("✗").append(p.getFileName()).append("(失败) ");
                PrefabCustomAddon.LOGGER.warn("[MANAGE-BLUEPRINT] 删除失败: {} ({})", p, e.getMessage());
            }
        }
        // 删除联动: 该蓝图生成的建筑在 blueprint-buildings/ 里 → 一并删掉
        // (只动蓝图专属目录, prefab-extension 根目录/下载目录的普通建筑不受影响)
        int bpFilesDeleted = 0;
        if (bpConstructionId != null) {
            bpFilesDeleted = LocalBuildingScanner.deleteBlueprintBuilding(bpConstructionId);
            if (bpFilesDeleted > 0) {
                log.append("✓blueprint-buildings/").append(bpConstructionId).append(".*(")
                    .append(bpFilesDeleted).append("个) ");
            }
        }
        setStatus("已删 " + bp.nsItem() + ": " + log + " (请 /reload 生效)", 0x55FF55);
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                "§7[蓝图管理] 已删 " + bp.nsItem() + " 的 KubeJS 文件 (删 " + deleted + " 个, 缺 " + missing + " 个)"
                + (bpFilesDeleted > 0 ? ", 联动删除建筑文件 " + bpFilesDeleted + " 个" : "")
                + ".\n§e请 /reload 让配方 / 物品注册生效 (startup script 必须重启游戏)."));
        }
        rebuildManagedBlueprints();
        rebuildList();
    }

    private static void buildManageBlueprintTab(UIElement listArea) {
        if (!KubeJSIntegration.isLoaded()) {
            buildKubeJSRequiredPanel(listArea);
            return;
        }
        UIElement panel = new UIElement();
        panel.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).widthPercent(100).gapAll(3));
        panel.setOverflowVisible(false);
        listArea.addChild(panel);

        // 1.20.1 lang 没有 editor.manage.hint, 硬编码中文
        panel.addChild(cardLabel("§7管理由本 mod 注册的自定义蓝图 (KubeJS / 自定义纹理)", 0x888888, 11));

        if (managedBlueprints.isEmpty()) {
            UIElement grid = new UIElement();
            grid.layout(l -> l.flexDirection(FlexDirection.ROW).flexGrow(1).minWidth(0));
            grid.setOverflowVisible(false);
            panel.addChild(grid);
            // 1.20.1 lang 没有 editor.manage.empty*, 硬编码中文
            fillEmpty(grid, "没有可管理的自定义蓝图", "在 '制作蓝图' tab 生成蓝图后会显示在这里");
            return;
        }

        ScrollerView scroller = scroller(panel);
        UIElement col = new UIElement();
        col.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100).paddingAll(2).gapAll(4));
        scroller.addScrollViewChild(col);

        int cw = wideCardW();
        int c = wideCols();
        int perPage = Math.max(1, c * rowsPerPageFor(44));
        int totalPages = Math.max(1, (managedBlueprints.size() + perPage - 1) / perPage);
        mgmtPage = clampPage(mgmtPage, totalPages);
        int from = mgmtPage * perPage;
        int to = Math.min(from + perPage, managedBlueprints.size());
        for (int i = from; i < to; i += c) {
            UIElement row = new UIElement();
            row.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4));
            for (int j = i; j < Math.min(i + c, to); j++) {
                row.addChild(managedBlueprintCard(managedBlueprints.get(j), cw));
            }
            col.addChild(row);
        }
        if (totalPages > 1) {
            col.addChild(pager(totalPages, () -> mgmtPage, p -> mgmtPage = p));
        }
    }

    /** 蓝图管理卡片: 物品图标 + 名称 / ns:id / 文件状态 + [✗ 删除]; 卡片本体不可点. */
    private static UIElement managedBlueprintCard(ManagedBlueprint bp, int cw) {
        UIElement card = new UIElement();
        card.layout(l -> l.flexDirection(FlexDirection.ROW).width(cw).height(44)
            .paddingAll(3).gapAll(4).alignItems(AlignItems.CENTER));
        card.style(s -> s.background(Sprites.RECT_DARK));
        card.setOverflowVisible(false);

        ItemSlot icon = new ItemSlot();
        try {
            icon.setValue(new ItemStack(bp.item));
        } catch (Throwable ignore) {}
        icon.layout(l -> l.width(18).height(18));
        card.addChild(icon);

        UIElement info = new UIElement();
        info.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).gapAll(1).minWidth(0));
        card.addChild(info);
        info.addChild(cardLabel(truncateW(bp.displayName, cw - 140), 0xFFFFFF, 10));
        info.addChild(cardLabel(truncateW(bp.nsItem(), cw - 140), 0x888888, 9));
        List<String> missing = new ArrayList<>();
        if (!Files.exists(bp.jsFile)) missing.add("脚本");     // 1.20.1 lang 无 editor.manage.file.script
        if (!Files.exists(bp.textureFile)) missing.add("纹理"); // 1.20.1 lang 无 editor.manage.file.texture
        boolean recipeMissing = !Files.exists(bp.recipeFile);
        String statusLine;
        int statusColor;
        if (!missing.isEmpty()) {
            // 脚本/纹理缺了蓝图就没法注册, 属于真缺失
            statusLine = truncateW("✗ 缺失文件: " + String.join("、", missing), cw - 140);
            statusColor = 0xFFAA55;
        } else if (recipeMissing) {
            // 配方只是合成途径, 缺了物品照样能用 → 视为可用
            statusLine = "✓ 可使用 (无配方)";
            statusColor = 0x55FF55;
        } else {
            statusLine = "✓ 关联文件完整";
            statusColor = 0x55FF55;
        }
        info.addChild(cardLabel(statusLine, statusColor, 9));

        Button del = new Button().setText(tr("gui.category.delete"), false);
        del.textStyle(s -> s.textColor(0xFF8888));
        del.layout(l -> l.width(48).height(16));
        del.setOnClick(e -> deleteManagedBlueprint(bp));
        card.addChild(del);
        return card;
    }

    // ==================== 3D 原理图编辑 (签名保持, TerminalGui 复用) ====================

    /**
     * 启动 3D 原理图编辑 (世界内幽灵预览, 左键删除 / 右键放置 / ALT 保存).
     * 成功返回 null (内部会关 GUI 进 edit mode), 失败返回错误消息.
     */
    public static String startSchematicEdit(LocalBuilding lb) {
        if (lb.filePath == null || !Files.exists(lb.filePath)) {
            return "建筑文件不存在: " + lb.id;
        }
        // 1) 包成 ConstructionInfo 喂给 CustomStructureBuilder (复用现有 NBT 解析链路)
        ConstructionInfo ci = new ConstructionInfo(lb.id);
        ci.setName(lb.getDisplayName());
        ci.setAuthor(lb.author == null ? "" : lb.author);
        ci.setDescription(lb.description == null ? "" : lb.description);
        ci.setLocalNbtPath(lb.filePath);

        // 2) NBT → Prefab Structure
        Structure structure = CustomStructureBuilder.parseToPrefabStructure(ci);
        if (structure == null || structure.getBlocks() == null || structure.getBlocks().isEmpty()) {
            return "建筑 NBT 解析失败或为空: " + lb.id;
        }

        // 3) 玩家朝向 (初始化: 反向, prefab 内部约定)
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.player.LocalPlayer player = mc.player;
        Direction houseFacing = (player != null) ? player.getDirection().getOpposite() : Direction.SOUTH;
        BlockPos playerPos = (player != null) ? player.blockPosition() : BlockPos.ZERO;
        BlockPos basePos = playerPos.above();

        // 4) 把每个 BuildBlock.blockPos 从 localPos 转为 worldPos (renderer 直接读 worldPos)
        StructureConfiguration cfg = new StructureConfiguration();
        cfg.Initialize();
        cfg.pos = basePos;
        cfg.houseFacing = houseFacing;
        CustomStructureBuilder.offsetStructureBlocks(structure, cfg.pos, cfg.houseFacing);

        // 5) 拿原始 NBT byte[]
        byte[] nbtBytes = ci.getNbtData();
        if (nbtBytes == null) {
            return "无法读取 NBT 字节: " + lb.id;
        }

        // 6) 提取 world / local 两个 map (EditModeController 新架构用)
        LinkedHashMap<BlockPos, BlockState> worldMap = new LinkedHashMap<>();
        for (BuildBlock bb : structure.getBlocks()) {
            if (bb == null || bb.getBlockState() == null) continue;
            worldMap.put(bb.blockPos, bb.getBlockState());
        }
        LinkedHashMap<BlockPos, BlockState> localMap = new LinkedHashMap<>();
        int parseFailCount = 0;
        try {
            // 1.20.1: NbtIo.read(DataInputStream) 无 NbtAccounter 参数
            CompoundTag tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(nbtBytes)));
            ListTag blocks = tag.getList("blocks", 10);
            for (int i = 0; i < blocks.size(); i++) {
                CompoundTag entry = blocks.getCompound(i);
                int[] p = entry.getIntArray("pos");
                if (p.length != 3) continue;
                BlockPos lp = new BlockPos(p[0], p[1], p[2]);
                BlockState s = parseStateFromNbtEntry(entry);
                if (s != null) localMap.put(lp, s);
                else parseFailCount++;
            }
        } catch (Exception ex1) {
            // 原 NBT 是 GZIP 压缩的, 重试
            try {
                // 1.20.1: NbtIo.readCompressed(InputStream) 无 NbtAccounter 参数
                CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(nbtBytes));
                ListTag blocks = tag.getList("blocks", 10);
                for (int i = 0; i < blocks.size(); i++) {
                    CompoundTag entry = blocks.getCompound(i);
                    int[] p = entry.getIntArray("pos");
                    if (p.length != 3) continue;
                    BlockPos lp = new BlockPos(p[0], p[1], p[2]);
                    BlockState s = parseStateFromNbtEntry(entry);
                    if (s != null) localMap.put(lp, s);
                    else parseFailCount++;
                }
            } catch (Exception ex2) {
                PrefabCustomAddon.LOGGER.warn("[EDIT-MODE] failed to parse NBT for local map, falling back to worldToLocal", ex2);
            }
        }
        // 补齐缺失: NBT 的 state 可能是 palette index (int) 格式, 解析失败时用 worldToLocal 反推
        if (localMap.size() < worldMap.size() / 2) {
            int added = 0;
            for (var e : worldMap.entrySet()) {
                BlockPos wp = e.getKey();
                BlockPos lp = worldToLocalHelper(wp, basePos, houseFacing);
                if (!localMap.containsKey(lp)) {
                    localMap.put(lp, e.getValue());
                    added++;
                }
            }
            PrefabCustomAddon.LOGGER.info("[EDIT-MODE] localMap补齐: 解析成功={} 失败={} 兜底补={} (total {})",
                localMap.size() - added, parseFailCount, added, localMap.size());
        }

        // 7) 设置 addon 预览状态
        CustomStructureGui.setAddonPreviewStructure(structure);
        CustomStructureGui.setAddonPreviewConfig(cfg);
        CustomStructureGui.markAddonPreviewActive();
        // 8) 关闭 GUI
        mc.setScreen(null);
        // 9) 进入 edit mode. 保存成功后清掉所有引用该文件的 ConstructionInfo 的 nbtData 缓存.
        EditModeController.setOnSavedCallback(() -> {
            for (com.prefab.addon.extension.ExtensionPack p : com.prefab.addon.extension.ExtensionPackManager.getInstance().getPacks()) {
                for (ConstructionInfo c : p.getConstructions()) {
                    if (c.getLocalNbtPath() != null && c.getLocalNbtPath().equals(lb.filePath)) {
                        c.setNbtData(null);
                    }
                }
            }
        });
        EditModeController.enterEditMode(worldMap, localMap, cfg, nbtBytes, lb.filePath);
        return null; // 成功
    }

    private static int facingToSteps(Direction facing) {
        if (facing == null) return 0;
        return switch (facing) {
            case SOUTH -> 0;
            case EAST  -> 1;
            case NORTH -> 2;
            case WEST  -> 3;
            default    -> 0;
        };
    }

    /** world pos → local pos helper. 跟 EditModeController.worldToLocal 一致. */
    private static BlockPos worldToLocalHelper(BlockPos worldPos, BlockPos basePos, Direction facing) {
        int steps = facingToSteps(facing);
        int rx = worldPos.getX() - basePos.getX();
        int ry = worldPos.getY() - basePos.getY();
        int rz = worldPos.getZ() - basePos.getZ();
        for (int s = 0; s < steps; s++) {
            int newRx = -rz;
            int newRz =  rx;
            rx = newRx;
            rz = newRz;
        }
        return new BlockPos(rx, ry, rz);
    }

    private static BlockState parseStateFromNbtEntry(CompoundTag entry) {
        if (entry.contains("state", 10)) {
            CompoundTag st = entry.getCompound("state");
            if (st.contains("Name", 8)) {
                String name = st.getString("Name");
                // 1.20.1: ResourceLocation 用 new 构造 (无 parse), 非法名抛异常 → catch 掉返回 null
                Block block;
                try {
                    block = BuiltInRegistries.BLOCK.get(new ResourceLocation(name));
                } catch (Exception e) {
                    return null;
                }
                if (block == null) return null;
                BlockState state = block.defaultBlockState();
                if (st.contains("Properties", 10)) {
                    CompoundTag props = st.getCompound("Properties");
                    for (var prop : state.getProperties()) {
                        if (props.contains(prop.getName(), 8)) {
                            String val = props.getString(prop.getName());
                            try {
                                var optVal = prop.getValue(val);
                                if (optVal.isPresent()) {
                                    Object raw = optVal.get();
                                    @SuppressWarnings({"unchecked", "rawtypes"})
                                    Comparable cval = (Comparable) raw;
                                    @SuppressWarnings("unchecked")
                                    BlockState newState = state.setValue(
                                        (net.minecraft.world.level.block.state.properties.Property) prop, cval);
                                    state = newState;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
                return state;
            }
        }
        return null;
    }

    // ==================== 详情信息 (外部签名, TerminalGui 复用) ====================

    /**
     * 把 LocalBuilding 包成 {@link GuiConstructionDetail} 用的 ConstructionInfo
     * (元信息 + 本地三件套路径一并带上).
     */
    public static ConstructionInfo buildDetailInfo(LocalBuilding lb) {
        ConstructionInfo ci = new ConstructionInfo(lb.id);
        ci.setName(lb.getDisplayName());
        ci.setAuthor(lb.author == null ? "" : lb.author);
        ci.setSize(formatSize(lb.fileSize));
        ci.setFormat(lb.fileExt == null ? "nbt" : lb.fileExt.replace(".", ""));
        ci.setDescription(lb.description == null ? "" : lb.description);
        String deps = parseInfoTxt(lb.infoPath, "依赖");
        if (deps == null) deps = parseInfoTxt(lb.infoPath, "依赖模组");
        if (deps == null) deps = parseInfoTxt(lb.infoPath, "dependencies");
        if (deps != null && !deps.isEmpty()) {
            ci.setDependencies(Arrays.asList(deps.split("\\s*,\\s*")));
        }
        // 设置本地路径, GuiConstructionDetail 的 startAsyncParse 会懒加载 NBT
        ci.setLocalNbtPath(lb.filePath);
        if (lb.imagePath != null && Files.exists(lb.imagePath)) {
            ci.setLocalImagePath(lb.imagePath);
        }
        return ci;
    }

    /** 解析 .txt 元信息中的 "key: value", 找不到返回 null. */
    private static String parseInfoTxt(Path infoPath, String key) {
        if (infoPath == null || !Files.exists(infoPath)) return null;
        try {
            String content = Files.readString(infoPath);
            // 剥 UTF-8 BOM (Windows 记事本常带)
            if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
                content = content.substring(1);
            }
            for (String line : content.split("\\r?\\n")) {
                String[] kv = line.split("[:：]", 2);
                if (kv.length != 2) continue;
                if (kv[0].trim().equalsIgnoreCase(key)) return kv[1].trim();
            }
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("[EDITOR] parseInfoTxt exception: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 把配方格 item id 字符串解析为 {@link ItemStack} (支持 SNBT {id:"..."} 复合 tag).
     * 解析失败返回 null.
     */
    private static ItemStack parseItemStack(String id) {
        if (id == null || id.isBlank()) return null;
        String s = id.trim();
        try {
            if (s.startsWith("{") && s.endsWith("}")) {
                int p = s.indexOf("id:");
                if (p < 0) p = s.indexOf("\"id\"");
                if (p >= 0) {
                    int q1 = s.indexOf('"', p + 3);
                    if (q1 > 0) {
                        int q2 = s.indexOf('"', q1 + 1);
                        if (q2 > q1) s = s.substring(q1 + 1, q2);
                    }
                }
            }
            if (s.contains(":")) {
                ResourceLocation key = ResourceLocation.tryParse(s);
                if (key != null) {
                    var item = BuiltInRegistries.ITEM.get(key);
                    if (item != null) return new ItemStack(item);
                }
            }
        } catch (Throwable ignore) {}
        return null;
    }

    /** 取当前 Minecraft 客户端 .minecraft/ 根目录. */
    private static Path minecraftDir() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            throw new IllegalStateException("GuiExtensionPackEditor 必须在 client 端调用");
        }
        return mc.gameDirectory.toPath();
    }

    /**
     * 清洗文件名: 替换 Windows 非法字符为下划线, 去首尾空白与末尾的点, 限长 50 字符.
     */
    private static String sanitizeFileName(String s) {
        if (s == null) return "";
        String cleaned = s.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
        while (cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.length() > 50) {
            cleaned = cleaned.substring(0, 50).trim();
            while (cleaned.endsWith(".")) {
                cleaned = cleaned.substring(0, cleaned.length() - 1);
            }
        }
        return cleaned;
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return (bytes / 1024 / 1024) + " MB";
    }

    // ==================== Tab 切换 ====================

    private static void switchTab(Tab tab) {
        if (tab == currentTab) return;
        currentTab = tab;
        // 进入编辑 tab 时重新扫描 (玩家可能在外面删/加了建筑)
        if (tab == Tab.EDIT) {
            refreshEditBuildings();
        }
        // 进入蓝图管理 tab 时重新枚举 (玩家可能 /reload)
        if (tab == Tab.MANAGE_BLUEPRINT) {
            rebuildManagedBlueprints();
        }
        rebuild();
    }

    // ==================== 通用小部件 ====================

    private static ScrollerView scroller(UIElement parent) {
        ScrollerView scroll = new ScrollerView();
        scroll.scrollerStyle(s -> s.mode(ScrollerMode.VERTICAL));
        scroll.verticalScroller(v -> v.setScrollBarSize(6f));
        scroll.layout(l -> l.flexGrow(1).widthPercent(100).minHeight(0));
        parent.addChild(scroll);
        return scroll;
    }

    private static void fillEmpty(UIElement area, String title, String hint) {
        UIElement box = new UIElement();
        box.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).widthPercent(100)
            .alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER).gapAll(3));
        area.addChild(box);
        Label t = new Label();
        t.setText(title, false);
        t.textStyle(s -> s.textColor(0xAAAAAA));
        t.layout(l -> l.widthPercent(100).height(12).alignItems(AlignItems.CENTER)
            .justifyContent(AlignContent.CENTER));
        box.addChild(t);
        Label h = new Label();
        h.setText(hint, false);
        h.textStyle(s -> s.textColor(0x777777));
        h.layout(l -> l.widthPercent(100).height(12).alignItems(AlignItems.CENTER)
            .justifyContent(AlignContent.CENTER));
        box.addChild(h);
    }

    private static UIElement cardLabel(String text, int color, int height) {
        Label l = new Label();
        l.setText(text, false);
        l.textStyle(t -> t.textColor(color));
        l.layout(el -> el.widthPercent(100).height(height));
        return l;
    }

    private static UIElement rowLabel(String text, int width) {
        Label l = new Label();
        l.setText(text, false);
        l.textStyle(t -> t.textColor(0xAAAAAA));
        l.layout(el -> el.width(width).height(16).alignItems(AlignItems.CENTER));
        return l;
    }

    private static UIElement sectionLabel(String text) {
        Label l = new Label();
        l.setText(text, false);
        l.textStyle(t -> t.textColor(0xFFDD66));
        l.layout(el -> el.widthPercent(100).height(12));
        return l;
    }

    private static void addReadonlyRow(UIElement panel, String text) {
        Label l = new Label();
        l.setText(truncateW(stripCodes(text), 300), false);
        l.textStyle(t -> t.textColor(0x999999));
        l.layout(el -> el.widthPercent(100).height(12).marginBottom(4));
        panel.addChild(l);
    }

    /** 页码收敛到 [0, totalPages-1]. */
    private static int clampPage(int page, int totalPages) {
        if (page >= totalPages) return totalPages - 1;
        return Math.max(0, page);
    }

    /** 通用翻页条: ◀ 页码/总页数 ▶ (点击后重建列表区, 不打断搜索框输入). */
    private static UIElement pager(int totalPages, IntSupplier get, IntConsumer set) {
        UIElement nav = new UIElement();
        nav.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(14).gapAll(4)
            .alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER));
        nav.setOverflowVisible(false);
        Button prev = new Button().setText("◀", false);
        prev.textStyle(s -> s.textColor(0xCCCCCC));
        prev.layout(l -> l.width(24).height(14));
        prev.setOnClick(e -> {
            int p = get.getAsInt();
            if (p > 0) {
                set.accept(p - 1);
                rebuildList();
            }
        });
        nav.addChild(prev);
        Label lbl = new Label();
        lbl.setText((get.getAsInt() + 1) + " / " + totalPages, false);
        lbl.textStyle(t -> t.textColor(0xAAAAAA));
        lbl.layout(l -> l.width(44).height(14).alignItems(AlignItems.CENTER)
            .justifyContent(AlignContent.CENTER));
        nav.addChild(lbl);
        Button next = new Button().setText("▶", false);
        next.textStyle(s -> s.textColor(0xCCCCCC));
        next.layout(l -> l.width(24).height(14));
        next.setOnClick(e -> {
            int p = get.getAsInt();
            if (p < totalPages - 1) {
                set.accept(p + 1);
                rebuildList();
            }
        });
        nav.addChild(next);
        return nav;
    }

    // ==================== 布局尺寸 ====================

    /** 宽卡片 (编辑建筑/蓝图管理) 动态列数. */
    private static int wideCols() {
        int avail = Minecraft.getInstance().getWindow().getGuiScaledWidth() - TAB_W - 30;
        return Math.max(1, Math.min(avail / 240, 3));
    }

    private static int wideCardW() {
        int avail = Minecraft.getInstance().getWindow().getGuiScaledWidth() - TAB_W - 30;
        return Math.max(180, (avail - (wideCols() - 1) * 4) / wideCols());
    }

    /** 每页行数 (按窗口高度与卡片高度算, 行距 4). */
    private static int rowsPerPageFor(int cardH) {
        int availH = Minecraft.getInstance().getWindow().getGuiScaledHeight() - 70;
        return Math.max(1, availH / (cardH + 4));
    }

    // ==================== 纹理工具 ====================

    /** 把 BufferedImage 像素 (ARGB→ABGR) 写进 DynamicTexture. */
    private static void copyPixels(BufferedImage img, DynamicTexture tex) {
        NativeImage pixels = tex.getPixels();
        int w = img.getWidth();
        int h = img.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int abgr = (argb & 0xFF00FF00) | ((argb & 0xFF0000) >> 16) | ((argb & 0xFF) << 16);
                pixels.setPixelRGBA(x, y, abgr);
            }
        }
    }

    /** 居中裁方 + 限宽 256 → DynamicTexture (双线性过滤, 失败返回 null). */
    private static DynamicTexture uploadIconTexture(BufferedImage img) {
        if (img == null) return null;
        try {
            int w = img.getWidth();
            int h = img.getHeight();
            if (w <= 0 || h <= 0) return null;
            int cropSide = Math.min(w, h);
            if (cropSide < w || cropSide < h) {
                BufferedImage cropped = img.getSubimage((w - cropSide) / 2, (h - cropSide) / 2, cropSide, cropSide);
                BufferedImage croppedCopy = new BufferedImage(cropSide, cropSide, BufferedImage.TYPE_INT_ARGB);
                Graphics2D cg = croppedCopy.createGraphics();
                cg.drawImage(cropped, 0, 0, null);
                cg.dispose();
                img = croppedCopy;
            }
            if (img.getWidth() > 256) {
                BufferedImage scaled = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.drawImage(img, 0, 0, 256, 256, null);
                g.dispose();
                img = scaled;
            }
            // 本工程 1.20.1 映射下 DynamicTexture 是 (int,int,boolean useCalloc) 三参构造
            DynamicTexture tex = new DynamicTexture(img.getWidth(), img.getHeight(), false);
            tex.setFilter(true, true);
            copyPixels(img, tex);
            tex.upload();
            return tex;
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("[EDITOR] uploadIconTexture 失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 状态栏 / 小工具 ====================

    private static void setStatus(String msg) {
        setStatus(msg, 0xAAAAAA);
    }

    private static void setStatus(String msg, int color) {
        lastStatus = msg == null ? "" : msg;
        lastStatusColor = color;
        if (statusLabel != null) {
            statusLabel.setText(stripCodes(lastStatus), false);
            statusLabel.textStyle(t -> t.textColor(color));
        }
    }

    /** 去掉旧版手绘状态消息里的 § 颜色码 (LDLib2 Label 不解析 § 代码). */
    private static String stripCodes(String s) {
        if (s == null) return "";
        return s.replaceAll("§.", "");
    }

    private static String tr(String key, Object... args) {
        return PrefabCustomAddon.tr(key, args);
    }

    /** 按像素宽截断 (CJK 按 9px, 其余按 6px 估算), 超出补 "..". */
    private static String truncateW(String s, int maxPx) {
        if (s == null) return "";
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += s.charAt(i) > 0x2E7F ? 9 : 6;
            if (w > maxPx) return s.substring(0, i) + "..";
        }
        return s;
    }
}
