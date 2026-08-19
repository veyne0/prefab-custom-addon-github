package com.prefab.addon.client.gui;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollDisplay;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.ConstructionInfo;
import com.prefab.addon.extension.LocalBuilding;
import com.prefab.addon.extension.LocalBuildingScanner;
import com.prefab.addon.work.PackCreator;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 拓展包 / 建筑 管理主界面 (X 键打开) - LDLib2 实现, 侧边栏 + 标签页布局.
 *
 * <h2>布局</h2>
 * <pre>
 *   ┌──────────────────────────────────────────────┐
 *   │ 建筑管理 - 本地工作区                          │ (title h=20)
 *   │ 工作目录: .../prefab-extension                 │ (subtitle h=12)
 *   ├──────────┬───────────────────────────────────┤
 *   │ [添加建筑]│  添加建筑: 嵌入 createFormElement │
 *   │ [编辑建筑]│  编辑建筑: LocalBuilding 卡片网格 │
 *   │ (sidebar)│  (content)                        │
 *   ├──────────┴───────────────────────────────────┤
 *   │ 状态: ...                          [关闭]    │ (h=20)
 *   └──────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>两个 tab</h2>
 * <ul>
 *   <li><b>添加建筑</b> — 嵌入 {@link GuiCreateBuildingInfo#createFormElement()}, 玩家在
 *       表单里选 NBT/游戏中选区/选图标 → 保存到 prefab-extension/{id}.{nbt,txt,png} 三件套.
 *       OBJ 转换 / 游戏中选区 / 图标选择 / 保存 / 删除 全部走 GuiCreateBuildingInfo 原方法, 此处不动.</li>
 *   <li><b>编辑建筑</b> — 用 {@link LocalBuildingScanner} 扫 prefab-extension/,
 *       以 3 列卡片网格展示. 每张卡片含图片/名称/查看/编辑按钮.
 *       查看 = 转 ConstructionInfo 调 {@link GuiConstructionDetail#open(ConstructionInfo)};
 *       编辑 = 转 BuildingWorkInfo 调 {@link GuiCreateBuildingInfo#open} (独立屏).</li>
 * </ul>
 *
 * <h2>parent 回调</h2>
 * 嵌入表单后, GuiCreateBuildingInfo 的 doSave / doDelete / 取消按钮仍会调
 * parent.onChildClosed / onBuildingSaved / onBuildingDeleted. 这里区分两种模式:
 * <ul>
 *   <li><b>嵌入式</b> (主界面可见) — 原地刷新表单 / 列表, 不关屏.</li>
 *   <li><b>独立式</b> (从 "编辑" 进入的独立编辑屏) — 重新打开主界面.</li>
 * </ul>
 * 通过 {@code Minecraft.getInstance().screen == currentModularScreen} 判断.
 */
public final class GuiExtensionPackCreator {

    private GuiExtensionPackCreator() {}

    // === 静态状态 ===
    /** 当前可见的 ModularUIScreen 引用, 用于 parent 回调判断"主界面是否可见". */
    private static ModularUIScreen currentModularScreen = null;
    /** 当前 tab: "add" / "edit". */
    private static String currentTab = "add";
    /** 编辑建筑 tab 扫描的 LocalBuilding 列表. */
    private static final List<LocalBuilding> buildingList = new ArrayList<>();
    /** LocalBuilding.id -> 加载的预览图 ResourceLocation. 关闭主界面时释放. */
    private static final Map<String, ResourceLocation> buildingImages = new HashMap<>();

    // === UI 引用 ===
    private static UIElement addTabContent;
    private static UIElement editTabContent;
    private static TextElement statusEl;
    private static TextElement subtitleEl;
    private static Button tabAddBtn;
    private static Button tabEditBtn;
    private static UIElement editCardGrid;
    private static ScrollerView editScroller;
    private static TextElement editEmptyEl;

    // === 状态消息 ===
    private static String statusMessage = null;
    private static int statusColor = 0x55FF55;
    private static int statusTick = 0;

    // === 静态单例 (供子界面拿 parent ref) ===
    private static GuiExtensionPackCreator currentInstance = null;

    /** 子界面 / 外部调用: 拿到当前实例 (用于 parent ref) */
    public static GuiExtensionPackCreator getCurrent() {
        return currentInstance;
    }

    /**
     * 打开主界面 (X 键入口).
     */
    public static void open() {
        // 释放旧封面/卡片图片纹理
        releaseAllImages();

        currentTab = "add";
        statusMessage = null;
        statusTick = 0;
        // 打开主界面时, 先把 GuiCreateBuildingInfo 的 static 状态重置
        // (避免上次编辑建筑/独立打开的残留值污染新打开的添加建筑表单)
        GuiCreateBuildingInfo.resetForReuse();
        currentInstance = new GuiExtensionPackCreator();
        // 预扫一次, 切到编辑 tab 时不至于空
        refreshBuildingList();

        ModularUI ui = createUI();
        currentModularScreen = new ModularUIScreen(ui,
            Component.literal(PrefabCustomAddon.tr("gui.extension_creator.window_title")));
        Minecraft.getInstance().setScreen(currentModularScreen);
    }

    /** 释放所有加载的预览图纹理. */
    private static void releaseAllImages() {
        for (ResourceLocation loc : buildingImages.values()) {
            try {
                Minecraft.getInstance().getTextureManager().release(loc);
            } catch (Throwable ignored) {}
        }
        buildingImages.clear();
    }

    // === parent 回调 ===

    /**
     * 子界面 (form 取消按钮) 回调:
     * <ul>
     *   <li>嵌入式 — 原地重置表单, 不关屏.</li>
     *   <li>独立式 — 重新打开主界面.</li>
     * </ul>
     */
    public void onChildClosed() {
        if (isMainScreenVisible()) {
            // 嵌入式: 重置表单, 留在添加建筑 tab
            GuiCreateBuildingInfo.resetForReuse();
            GuiCreateBuildingInfo.parent = currentInstance;
            rebuildAddTab();
            switchTab("add");
            setStatus(PrefabCustomAddon.tr("gui.extension_creator.cancelled"), 0x888888);
        } else {
            // 独立式: 重新打开主界面
            reopenMainScreen("add");
        }
    }

    /**
     * 子界面 (form 保存成功) 回调.
     */
    public void onBuildingSaved(String id) {
        if (isMainScreenVisible()) {
            // 嵌入式: 重置表单, 留在添加建筑 tab; 同时刷新编辑 tab 列表
            GuiCreateBuildingInfo.resetForReuse();
            GuiCreateBuildingInfo.parent = currentInstance;
            rebuildAddTab();
            switchTab("add");
            setStatus(PrefabCustomAddon.tr("gui.extension_creator.saved", id), 0x55FF55);
            refreshBuildingList();
        } else {
            // 独立式 (从 "编辑" 进入的编辑屏): 重新打开, 切到编辑 tab 让玩家看到更新
            reopenMainScreen("edit");
        }
    }

    /**
     * 子界面 (form 删除成功) 回调.
     */
    public void onBuildingDeleted(String buildingId) {
        if (isMainScreenVisible()) {
            // 嵌入式: 不太可能触发 (嵌入式是 create 模式, 不显示删除按钮), 但兜底
            GuiCreateBuildingInfo.resetForReuse();
            GuiCreateBuildingInfo.parent = currentInstance;
            rebuildAddTab();
            switchTab("add");
            setStatus(PrefabCustomAddon.tr("gui.extension_creator.deleted", buildingId), 0x55FF55);
            refreshBuildingList();
        } else {
            // 独立式 (从 "编辑" 进入的编辑屏): 重新打开, 切到编辑 tab
            reopenMainScreen("edit");
        }
    }

    /** 主界面是否当前可见 (用 ModularUIScreen 引用比较). */
    private static boolean isMainScreenVisible() {
        return currentModularScreen != null
            && Minecraft.getInstance().screen == currentModularScreen;
    }

    /** 重新打开主界面 (用于独立式子界面关闭后). */
    private static void reopenMainScreen(String tab) {
        final String targetTab = tab == null ? "add" : tab;
        Minecraft.getInstance().execute(() -> {
            Minecraft.getInstance().setScreen(null);
            Minecraft.getInstance().execute(() -> {
                open();
                switchTab(targetTab);
            });
        });
    }

    // === 数据加载 ===

    /**
     * 扫描 prefab-extension/ 下的所有 LocalBuilding, 用于编辑建筑 tab.
     * 加载的图片存入 buildingImages 缓存.
     */
    private static void refreshBuildingList() {
        try {
            buildingList.clear();
            buildingList.addAll(LocalBuildingScanner.scanAll());
            // 按 id 排序保证稳定显示
            buildingList.sort((a, b) -> a.id.compareToIgnoreCase(b.id));
            PrefabCustomAddon.LOGGER.info("[CREATOR] 扫描 prefab-extension 找到 {} 个建筑",
                buildingList.size());
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[CREATOR] 扫描 LocalBuilding 失败", t);
        }
        rebuildEditGrid();
    }

    /**
     * 加载 LocalBuilding 的预览图, 返回 ResourceLocation (缓存).
     */
    private static ResourceLocation getOrLoadImage(LocalBuilding lb) {
        if (lb == null || !lb.hasPreviewImage() || !Files.exists(lb.imagePath)) return null;
        String key = lb.id;
        ResourceLocation loc = buildingImages.get(key);
        if (loc != null) return loc;
        try {
            NativeImage ni;
            try (var in = Files.newInputStream(lb.imagePath)) {
                ni = NativeImage.read(in);
            }
            if (ni == null) return null;
            DynamicTexture tex = new DynamicTexture(ni);
            String texKey = "prefab_addon_bldimg_" + key + "_"
                + Long.toHexString(System.currentTimeMillis());
            loc = Minecraft.getInstance().getTextureManager().register(texKey, tex);
            buildingImages.put(key, loc);
            return loc;
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[CREATOR] 加载建筑预览图失败: {}", lb.imagePath, t);
            return null;
        }
    }

    private static void setStatus(String msg, int color) {
        statusMessage = msg;
        statusColor = color;
        statusTick = 100;
    }

    // === tab 切换 ===

    private static void switchTab(String tab) {
        String newTab = (tab == null) ? "add" : tab;
        // 切回 "添加建筑" tab 时, 先把表单清空 (避免之前编辑建筑留下的字段值还占着表单)
        if ("add".equals(newTab) && !"add".equals(currentTab)) {
            GuiCreateBuildingInfo.resetForReuse();
            GuiCreateBuildingInfo.parent = currentInstance;
            rebuildAddTab();
        }
        currentTab = newTab;
        boolean showAdd = "add".equals(currentTab);
        if (addTabContent != null) addTabContent.setVisible(showAdd);
        if (editTabContent != null) editTabContent.setVisible(!showAdd);
        // 标签按钮高亮
        if (tabAddBtn != null) {
            tabAddBtn.textStyle(t -> t.textColor(showAdd ? 0xFFFF55 : 0xFFFFFF));
        }
        if (tabEditBtn != null) {
            tabEditBtn.textStyle(t -> t.textColor(showAdd ? 0xFFFFFF : 0xFFFF55));
        }
    }

    /**
     * 重建 "添加建筑" tab 的内容 = 嵌入的表单.
     * 每次重建都会调 GuiCreateBuildingInfo.createFormElement() 拿一个新的 UIElement.
     * 嵌入前先确保 GuiCreateBuildingInfo 的 static 状态 (parent, fields) 已就绪.
     */
    private static void rebuildAddTab() {
        if (addTabContent == null) return;
        addTabContent.clearAllChildren();
        // 把 parent 设上, 让嵌入的表单的保存/删除/取消按钮能找到主界面
        if (GuiCreateBuildingInfo.parent != currentInstance) {
            GuiCreateBuildingInfo.parent = currentInstance;
        }
        UIElement form = GuiCreateBuildingInfo.createFormElement();
        // 表单本身要占满整个 tab
        form.layout(l -> l.widthPercent(100).heightPercent(100));
        addTabContent.addChild(form);
    }

    /**
     * 重建 "编辑建筑" tab 的内容 = 3 列 LocalBuilding 卡片网格.
     */
    private static void rebuildEditGrid() {
        if (editCardGrid == null) return;
        editCardGrid.clearAllChildren();
        if (buildingList.isEmpty()) {
            if (editEmptyEl != null) {
                editEmptyEl.setVisible(true);
            }
            return;
        }
        if (editEmptyEl != null) editEmptyEl.setVisible(false);

        // 3 列网格: 用 ROW 容器嵌套 COLUMN 容器
        // 简化实现: 用一个宽度自适应的 ROW 列表, 每行放 3 个 card
        final int COLS = 3;
        final int CARD_W = 80;
        final int CARD_H = 105;
        final int GAP = 3;

        for (int i = 0; i < buildingList.size(); i += COLS) {
            UIElement row = new UIElement();
            row.layout(l -> l.widthPercent(100).height(CARD_H)
                .flexDirection(FlexDirection.ROW).gapAll(GAP).marginBottom(GAP));
            row.setOverflowVisible(false);
            for (int j = 0; j < COLS && i + j < buildingList.size(); j++) {
                LocalBuilding lb = buildingList.get(i + j);
                row.addChild(buildBuildingCard(lb, CARD_W, CARD_H));
            }
            editCardGrid.addChild(row);
        }
    }

    /**
     * 构造一张 LocalBuilding 卡片: 预览图 + 名称 + [查看][编辑] 按钮.
     */
    private static UIElement buildBuildingCard(LocalBuilding lb, int cardW, int cardH) {
        UIElement card = new UIElement();
        card.layout(l -> l.width(cardW).height(cardH)
            .flexDirection(FlexDirection.COLUMN).paddingAll(2).gapAll(1));
        card.style(s -> s.backgroundTexture(ColorPattern.DARK_GRAY.rectTexture()));
        card.setOverflowVisible(false);

        // === 预览图 (60x60) ===
        BuildingImageElement img = new BuildingImageElement(60, 60, lb);
        UIElement imgBox = new UIElement();
        imgBox.layout(l -> l.widthPercent(100).height(62)
            .justifyContent(AlignContent.CENTER).alignItems(AlignItems.CENTER));
        imgBox.style(s -> s.backgroundTexture(ColorPattern.SEAL_BLACK.rectTexture()));
        imgBox.setOverflowVisible(false);
        img.layout(l -> l.width(60).height(60));
        imgBox.addChild(img);
        card.addChild(imgBox);

        // === 名称 (1 行, 截断) ===
        String displayName = lb.getDisplayName();
        if (displayName.length() > 8) displayName = displayName.substring(0, 7) + "..";
        TextElement nameEl = new TextElement();
        nameEl.setText(displayName);
        nameEl.textStyle(t -> t.textColor(0xFFFFFF)
            .textAlignHorizontal(Horizontal.CENTER)
            .textWrap(TextWrap.NONE));
        nameEl.layout(l -> l.widthPercent(100).height(12));
        card.addChild(nameEl);

        // === 按钮行 [查看][编辑] ===
        UIElement btnRow = new UIElement();
        btnRow.layout(l -> l.widthPercent(100).height(20)
            .flexDirection(FlexDirection.ROW).gapAll(2));
        btnRow.setOverflowVisible(false);

        Button btnView = new Button()
            .setText(PrefabCustomAddon.tr("gui.extension_creator.view"));
        btnView.setOnClick(e -> onViewBuilding(lb));
        btnView.layout(l -> l.flexGrow(1).heightPercent(100));
        btnView.textStyle(t -> t.textColor(0x55FF55));
        btnRow.addChild(btnView);

        Button btnEdit = new Button()
            .setText(PrefabCustomAddon.tr("gui.extension_creator.edit"));
        btnEdit.setOnClick(e -> onEditBuilding(lb));
        btnEdit.layout(l -> l.flexGrow(1).heightPercent(100));
        btnEdit.textStyle(t -> t.textColor(0x55AAFF));
        btnRow.addChild(btnEdit);

        card.addChild(btnRow);
        return card;
    }

    // === 卡片按钮回调 ===

    /**
     * "查看" — 把 LocalBuilding 转成 ConstructionInfo, 打开 GuiConstructionDetail 的 3D 预览.
     */
    private static void onViewBuilding(LocalBuilding lb) {
        if (lb == null) return;
        try {
            ConstructionInfo c = localBuildingToConstructionInfo(lb);
            // 直接调用 GuiConstructionDetail.open 会替换主界面屏; 用户看完后通过 X 键返回.
            GuiConstructionDetail.open(c);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[CREATOR] 打开建筑预览失败: {}", lb.id, t);
            setStatus(PrefabCustomAddon.tr("gui.extension_creator.view_fail", lb.id, t.getMessage()),
                0xFF5555);
        }
    }

    /**
     * "编辑" — 把 LocalBuilding 转成 BuildingWorkInfo, 调用 GuiCreateBuildingInfo.open
     * 打开独立编辑屏. 编辑后通过 parent 回调重新打开主界面.
     */
    private static void onEditBuilding(LocalBuilding lb) {
        if (lb == null) return;
        try {
            PackCreator.BuildingWorkInfo bw = localBuildingToBuildingWorkInfo(lb);
            // packId 用 lb.id (兼容原 form 的标题显示, 新流程没有 pack 概念)
            GuiCreateBuildingInfo.open(lb.id, bw, currentInstance);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[CREATOR] 打开建筑编辑失败: {}", lb.id, t);
            setStatus(PrefabCustomAddon.tr("gui.extension_creator.edit_fail", lb.id, t.getMessage()),
                0xFF5555);
        }
    }

    /**
     * LocalBuilding → ConstructionInfo (供 GuiConstructionDetail 预览).
     */
    private static ConstructionInfo localBuildingToConstructionInfo(LocalBuilding lb) {
        ConstructionInfo c = new ConstructionInfo(lb.id);
        c.setName(lb.name == null || lb.name.isEmpty() ? lb.id : lb.name);
        c.setAuthor(lb.author == null ? "" : lb.author);
        c.setDescription(lb.description == null ? "" : lb.description);
        c.setFormat(lb.fileExt == null ? "nbt" : lb.fileExt.replaceFirst("^\\.", ""));
        c.setLocalImagePath(lb.imagePath);
        c.setLocalNbtPath(lb.filePath);
        return c;
    }

    /**
     * LocalBuilding → PackCreator.BuildingWorkInfo (供 GuiCreateBuildingInfo.open 编辑模式).
     * <p>从 {@code <id>.txt} 解析 size / dependencies, 缺省空.</p>
     */
    private static PackCreator.BuildingWorkInfo localBuildingToBuildingWorkInfo(LocalBuilding lb) {
        String size = "";
        String deps = "";
        if (lb.infoPath != null && Files.exists(lb.infoPath)) {
            try {
                String content = Files.readString(lb.infoPath,
                    java.nio.charset.StandardCharsets.UTF_8);
                for (String line : content.split("\\r?\\n")) {
                    String[] kv = splitKeyValue(line);
                    if (kv == null) continue;
                    String k = kv[0], v = kv[1];
                    switch (k) {
                        case "尺寸", "size" -> size = v;
                        case "依赖", "dependencies" -> deps = v;
                        default -> {} // 其它字段 GuiCreateBuildingInfo 不直接用
                    }
                }
            } catch (Throwable ignored) {}
        }
        return new PackCreator.BuildingWorkInfo(
            lb.id, lb.filePath, lb.imagePath, lb.infoPath,
            lb.name == null ? lb.id : lb.name,
            lb.author == null ? "" : lb.author,
            size, deps,
            lb.description == null ? "" : lb.description,
            ""  // icon 物品 id, 缺省空
        );
    }

    /** "key: value" 解析 (同 LocalBuildingScanner.splitKeyValue, 复制一份避免 public 暴露). */
    private static String[] splitKeyValue(String line) {
        if (line == null) return null;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return null;
        int idx = trimmed.indexOf(':');
        if (idx < 0) idx = trimmed.indexOf('：');
        if (idx <= 0) return null;
        String k = trimmed.substring(0, idx).trim();
        String v = trimmed.substring(idx + 1).trim();
        if (k.isEmpty()) return null;
        return new String[]{k, v};
    }

    // === UI 创建 ===

    private static ModularUI createUI() {
        // 根: 整屏 column
        UIElement root = new UIElement();
        root.layout(l -> l
            .widthPercent(100).heightPercent(100)
            .flexDirection(FlexDirection.COLUMN)
            .paddingAll(2).gapAll(2));
        root.style(s -> s.backgroundTexture(ColorPattern.SEAL_BLACK.rectTexture()));
        root.setOverflowVisible(false);

        // === 标题 ===
        Label titleEl = new Label();
        titleEl.setText("§l" + PrefabCustomAddon.tr("gui.extension_creator.window_title"));
        titleEl.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        titleEl.layout(l -> l.widthPercent(100).height(20));
        root.addChild(titleEl);

        // === 副标题 (工作目录) ===
        Path workRoot = LocalBuildingScanner.getExtensionRoot();
        subtitleEl = new TextElement();
        subtitleEl.setText(PrefabCustomAddon.tr("gui.extension_creator.work_dir", workRoot.toString()));
        subtitleEl.textStyle(t -> t.textColor(0xAAAAAA)
            .textAlignHorizontal(Horizontal.CENTER));
        subtitleEl.layout(l -> l.widthPercent(100).height(12));
        root.addChild(subtitleEl);

        // === 主体: 侧边栏 + 内容 ===
        UIElement mainRow = new UIElement();
        mainRow.layout(l -> l.widthPercent(100).flexGrow(1).flexShrink(1).minHeight(0)
            .flexDirection(FlexDirection.ROW).gapAll(2));
        mainRow.setOverflowVisible(false);
        root.addChild(mainRow);

        // -- 侧边栏 --
        UIElement sidebar = new UIElement();
        sidebar.layout(l -> l.width(72).flexShrink(0).flexGrow(0)
            .minHeight(0)
            .flexDirection(FlexDirection.COLUMN).gapAll(2).paddingAll(2));
        sidebar.style(s -> s.backgroundTexture(ColorPattern.DARK_GRAY.rectTexture()));
        sidebar.setOverflowVisible(false);

        // 侧边栏标题
        Label sbTitle = new Label();
        sbTitle.setText("§l" + PrefabCustomAddon.tr("gui.extension_creator.sidebar_title"));
        sbTitle.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        sbTitle.layout(l -> l.widthPercent(100).height(14));
        sidebar.addChild(sbTitle);

        // "添加建筑" 按钮
        tabAddBtn = new Button();
        tabAddBtn.setText(PrefabCustomAddon.tr("gui.extension_creator.tab_add"));
        tabAddBtn.setOnClick(e -> switchTab("add"));
        tabAddBtn.layout(l -> l.widthPercent(100).height(28));
        tabAddBtn.textStyle(t -> t.textColor(0xFFFF55));  // 默认选中, 高亮
        sidebar.addChild(tabAddBtn);

        // "编辑建筑" 按钮
        tabEditBtn = new Button();
        tabEditBtn.setText(PrefabCustomAddon.tr("gui.extension_creator.tab_edit"));
        tabEditBtn.setOnClick(e -> {
            // 切到编辑 tab 前重新扫一次, 让用户看到最新列表
            refreshBuildingList();
            switchTab("edit");
        });
        tabEditBtn.layout(l -> l.widthPercent(100).height(28));
        tabEditBtn.textStyle(t -> t.textColor(0xFFFFFF));
        sidebar.addChild(tabEditBtn);

        mainRow.addChild(sidebar);

        // -- 内容区 (容纳两个 tab, 同时只有一个可见) --
        UIElement content = new UIElement();
        content.layout(l -> l.flexGrow(1).flexShrink(1).flex(1).minWidth(0).minHeight(0)
            .flexDirection(FlexDirection.COLUMN).gapAll(2));
        content.setOverflowVisible(false);
        mainRow.addChild(content);

        // -- 添加建筑 tab --
        addTabContent = new UIElement();
        addTabContent.layout(l -> l.widthPercent(100).heightPercent(100)
            .flexDirection(FlexDirection.COLUMN).gapAll(2));
        addTabContent.setOverflowVisible(false);
        content.addChild(addTabContent);

        // 嵌入表单 (此时 GuiCreateBuildingInfo 的 static 状态: 上次 open()/resetForReuse() 留下的)
        // 兜底: 第一次打开时 currentInstance 已建, 把 parent 补上
        if (GuiCreateBuildingInfo.parent == null) {
            GuiCreateBuildingInfo.parent = currentInstance;
        }
        rebuildAddTab();

        // -- 编辑建筑 tab --
        editTabContent = new UIElement();
        editTabContent.layout(l -> l.widthPercent(100).heightPercent(100)
            .flexDirection(FlexDirection.COLUMN).gapAll(2));
        editTabContent.setOverflowVisible(false);
        editTabContent.setVisible(false);  // 默认隐藏
        content.addChild(editTabContent);

        // 编辑建筑 tab 顶部按钮行: [刷新]
        UIElement editTopBar = new UIElement();
        editTopBar.layout(l -> l.widthPercent(100).height(20)
            .flexDirection(FlexDirection.ROW).gapAll(2).alignItems(AlignItems.CENTER));
        editTopBar.setOverflowVisible(false);
        Button btnRefresh = new Button()
            .setText(PrefabCustomAddon.tr("gui.extension_creator.refresh"));
        btnRefresh.setOnClick(e -> {
            refreshBuildingList();
            setStatus(PrefabCustomAddon.tr("gui.extension_creator.refreshed",
                buildingList.size()), 0x55FF55);
        });
        btnRefresh.layout(l -> l.width(50).heightPercent(100));
        editTopBar.addChild(btnRefresh);

        TextElement editCountEl = new TextElement();
        editCountEl.setText(PrefabCustomAddon.tr("gui.extension_creator.count_label",
            buildingList.size()));
        editCountEl.textStyle(t -> t.textColor(0xAAAAAA));
        editCountEl.layout(l -> l.flexGrow(1).heightPercent(100));
        editTopBar.addChild(editCountEl);

        editTabContent.addChild(editTopBar);

        // 编辑建筑 tab 主体: ScrollerView 装卡片网格
        editScroller = new ScrollerView();
        editScroller.layout(l -> l.widthPercent(100).flexGrow(1).flexShrink(1).minHeight(0));
        editScroller.scrollerStyle(s -> s.mode(ScrollerMode.VERTICAL)
            .verticalScrollDisplay(ScrollDisplay.AUTO)
            .horizontalScrollDisplay(ScrollDisplay.NEVER)
            .minScrollPixel(8).maxScrollPixel(80));
        editScroller.verticalScroller(s -> s.setScrollBarSize(4));
        editScroller.viewPort(vp -> vp.style(s -> s.backgroundTexture(ColorPattern.SEAL_BLACK.rectTexture())
            .overlay(IGuiTexture.EMPTY)));

        editCardGrid = new UIElement();
        editCardGrid.layout(l -> l.widthPercent(100)
            .flexDirection(FlexDirection.COLUMN).paddingAll(2).gapAll(2)
            .minHeight(0).flexShrink(0));
        editScroller.addScrollViewChild(editCardGrid);
        editTabContent.addChild(editScroller);

        // 空列表提示
        editEmptyEl = new TextElement();
        editEmptyEl.setText(PrefabCustomAddon.tr("gui.extension_creator.empty_hint",
            LocalBuildingScanner.getExtensionRoot().toString()));
        editEmptyEl.textStyle(t -> t.textColor(0xAAAAAA)
            .textAlignHorizontal(Horizontal.CENTER)
            .textWrap(TextWrap.WRAP));
        editEmptyEl.layout(l -> l.widthPercent(100).height(40)
            .justifyContent(AlignContent.CENTER));
        editEmptyEl.setVisible(false);
        editTabContent.addChild(editEmptyEl);

        // 初始填一次卡片 (open() 时已扫过)
        rebuildEditGrid();

        // === 底部: 状态行 + 关闭按钮 ===
        UIElement bottomBar = new UIElement();
        bottomBar.layout(l -> l.widthPercent(100).height(22)
            .flexDirection(FlexDirection.ROW).gapAll(4).alignItems(AlignItems.CENTER));
        bottomBar.setOverflowVisible(false);

        statusEl = new TextElement();
        statusEl.setText("");
        statusEl.textStyle(t -> t.textAlignHorizontal(Horizontal.LEFT));
        statusEl.layout(l -> l.flexGrow(1).heightPercent(100));
        bottomBar.addChild(statusEl);

        Button btnClose = new Button();
        btnClose.setText(PrefabCustomAddon.tr("gui.extension_creator.close"));
        btnClose.setOnClick(e -> {
            // 释放所有预览图纹理
            releaseAllImages();
            currentModularScreen = null;
            currentInstance = null;
            // 切掉 parent 引用避免泄漏
            GuiCreateBuildingInfo.parent = null;
            Minecraft.getInstance().setScreen(null);
        });
        btnClose.layout(l -> l.width(60).heightPercent(100));
        bottomBar.addChild(btnClose);

        root.addChild(bottomBar);

        // === tick: 状态消息淡出 ===
        root.addEventListener(UIEvents.TICK, event -> {
            if (statusTick > 0 && statusMessage != null) {
                statusEl.setText(statusMessage);
                statusEl.textStyle(t -> t.textColor(statusColor));
                statusTick--;
                if (statusTick <= 0) {
                    statusMessage = null;
                    statusEl.setText("");
                }
            } else if (statusEl != null) {
                statusEl.setText("");
            }
        });

        // 初始 tab 状态
        switchTab(currentTab);

        return ModularUI.of(UI.of(root,
            StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)));
    }

    // === 自定义 UIElement: 建筑预览图 ===

    /**
     * 在指定尺寸内绘制 LocalBuilding 的预览图. tick 时按需懒加载.
     */
    private static class BuildingImageElement extends UIElement {
        private final int tw, th;
        private final LocalBuilding lb;
        BuildingImageElement(int w, int h, LocalBuilding lb) {
            this.tw = w;
            this.th = h;
            this.lb = lb;
        }
        @Override
        public void drawBackgroundAdditional(GUIContext guiContext) {
            super.drawBackgroundAdditional(guiContext);
            if (lb == null) return;
            ResourceLocation loc = getOrLoadImage(lb);
            int px = (int) getPositionX();
            int py = (int) getPositionY();
            if (loc == null) {
                // 占位: 深灰底 + 文字
                guiContext.graphics.fill(px, py, px + tw, py + th, 0xFF222233);
                guiContext.graphics.drawCenteredString(Minecraft.getInstance().font,
                    "无图", px + tw / 2, py + th / 2 - 4, 0xFF888888);
            } else {
                try {
                    GuiGraphics g = guiContext.graphics;
                    RenderSystem.enableBlend();
                    RenderSystem.defaultBlendFunc();
                    RenderSystem.setShaderColor(1, 1, 1, 1);
                    g.blit(loc, px, py, 0, 0, tw, th, tw, th);
                    RenderSystem.setShaderColor(1, 1, 1, 1);
                    RenderSystem.disableBlend();
                } catch (Throwable ignored) {}
            }
        }
    }
}
