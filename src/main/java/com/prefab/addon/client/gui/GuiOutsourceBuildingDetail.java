package com.prefab.addon.client.gui;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.ConstructionInfo;
import com.prefab.addon.outsource.OutsourceBuilding;
import com.prefab.structures.base.Structure;
import com.prefab.structures.config.StructureConfiguration;
import com.prefab.structures.render.StructureRenderHandler;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;

/**
 * 外包建筑蓝图 —— 简化版建筑详情 GUI。
 *
 * <p>布局 (整屏 100%):</p>
 * <pre>
 *   ┌──────────────────────────────────────┐
 *   │  左侧(140px)  │   右侧(flexGrow=1)   │
 *   │  进度文字      │                     │
 *   │  建筑名        │   3D 预览 Scene     │
 *   │  作者          │                     │
 *   ├──────────────────────────────────────┤
 *   │ [返回]  [切换 N/M]  [选择]            │
 *   └──────────────────────────────────────┘
 * </pre>
 *
 * <p>in-place 切风格:不销毁 GUI,只 reset 3D 状态 + 重新解析 + 更新 label/button 文字,
 * 3D 相机视角自动保留。</p>
 */
public final class GuiOutsourceBuildingDetail {

    private GuiOutsourceBuildingDetail() {}

    // === 当前会话状态 ===
    private static OutsourceBuilding currentBuilding;
    private static int currentStyleIndex;

    // === UI 元素引用 (tick handler 和 switchStyle 用) ===
    private static UIElement sceneContainer;
    private static TextElement scenePlaceholder;
    private static TextElement progressEl;
    private static Label buildingNameLabel;
    private static Label authorLabel;
    private static Button switchBtn;
    private static Button selectBtn;

    /**
     * 打开 GUI:第 styleIndex 个风格(0-based)。
     */
    public static void open(OutsourceBuilding building, int styleIndex) {
        if (building == null || building.getStyleCount() == 0) return;
        currentBuilding = building;
        currentStyleIndex = Math.max(0, Math.min(styleIndex, building.getStyleCount() - 1));
        ConstructionInfo info = building.getConstruction(currentStyleIndex);
        Construction3DView.reset(info);
        Construction3DView.startAsyncParse(info);
        ModularUI ui = createUI();
        Minecraft.getInstance().setScreen(
            new ModularUIScreen(ui, Component.literal(building.getFolderName())));
    }

    private static void switchStyle() {
        if (currentBuilding == null || !currentBuilding.hasMultipleStyles()) return;
        int next = currentBuilding.nextStyleIndex(currentStyleIndex);
        currentStyleIndex = next;
        ConstructionInfo info = currentBuilding.getConstruction(next);

        // 重置 3D 状态机 → 重新异步解析
        Construction3DView.reset(info);
        Construction3DView.startAsyncParse(info);

        // 把 Scene 容器清空,塞回占位符(下一个 tick Scene 创建好后会自动替换)
        if (sceneContainer != null && scenePlaceholder != null) {
            sceneContainer.clearAllChildren();
            sceneContainer.addChild(scenePlaceholder);
            scenePlaceholder.setText(PrefabCustomAddon.tr("gui.outsource.3d_loading"));
        }
        if (progressEl != null) {
            progressEl.setText(PrefabCustomAddon.tr("gui.outsource.3d_loading"));
        }
        // 更新左侧/按钮文字
        refreshLabels();
        PrefabCustomAddon.LOGGER.info("[OUTSOURCE] switchStyle → index {}/{} (folder='{}')",
            next + 1, currentBuilding.getStyleCount(), currentBuilding.getFolderName());
    }

    private static void onSelect() {
        if (currentBuilding == null) return;
        String label = currentBuilding.getFolderName()
            + (currentBuilding.hasMultipleStyles()
                ? "  §7[风格 " + (currentStyleIndex + 1) + "/" + currentBuilding.getStyleCount() + "]"
                : "");
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.sendSystemMessage(
                Component.literal("§a✓ 已选: " + label));
        }
        closeScreen();
    }

    /**
     * "预览" 按钮:关闭当前 GUI, 启动附属的世界内 3D 预览模式.
     * 玩家在世界里用方向键移动位置, CTRL 旋转, 右键取消, ALT 实际建造 (走的是 prefab 自己的 build path).
     */
    private static void onPreview() {
        if (currentBuilding == null) return;
        ConstructionInfo info = currentBuilding.getConstruction(currentStyleIndex);
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            closeScreen();
            return;
        }

        // 1) NBT → Prefab Structure (跟 CustomStructureGui.handlePreviewButtonClick 用的是同一个解析)
        Structure structure;
        try {
            structure = com.prefab.addon.structure.CustomStructureBuilder.getInstance().parseToPrefabStructure(info);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[OUTSOURCE] parseToPrefabStructure failed for '{}'", info.getName(), t);
            player.sendSystemMessage(Component.literal("§c✗ 解析建筑失败, 请查看日志"));
            closeScreen();
            return;
        }
        if (structure == null || structure.getBlocks() == null || structure.getBlocks().isEmpty()) {
            player.sendSystemMessage(Component.literal("§c✗ 建筑为空, 无法预览"));
            closeScreen();
            return;
        }

        // 2) 玩家朝向 (默认 SOUTH, 跟 prefab 原版行为一致)
        Direction houseFacing = player.getDirection().getOpposite();

        // 3) StructureConfiguration: 抬 1 格避免被 prefab renderer 跳空气 (CustomStructureGui 同款坑)
        StructureConfiguration cfg = new StructureConfiguration();
        cfg.Initialize();
        BlockPos basePos = player.blockPosition().above();
        cfg.pos = basePos;
        cfg.houseFacing = houseFacing;

        // 4) localPos → worldPos
        com.prefab.addon.structure.CustomStructureBuilder.offsetStructureBlocks(structure, cfg.pos, cfg.houseFacing);

        // 5) 设到 CustomStructureGui 静态字段, CustomStructurePreviewRenderer 读这些
        com.prefab.addon.client.gui.CustomStructureGui.setAddonPreviewStructure(structure);
        com.prefab.addon.client.gui.CustomStructureGui.setAddonPreviewConfig(cfg);
        com.prefab.addon.client.gui.CustomStructureGui.markAddonPreviewActive();

        // === 关键: 设置外包建筑上下文, 让 ALT 建造能识别 "这是 outsource 预览" ===
        // 之前这里没设, ALT 按下时 triggerBuildAtPreview 调 getPackNameForBuild() 返回 ""
        // (currentConstruction 是 null) → "找不到当前预览的建筑信息" → 永远建不了.
        // 修复: 用 setOutsourceContext 把 buildingId + styleIndex 存到 CustomStructureGui,
        //   ALT 时 StructurePreviewKeyHandler 用 isCurrentOutsource() 分流到 triggerOutsourceBuildAtPreview.
        com.prefab.addon.client.gui.CustomStructureGui.setOutsourceContext(
            currentBuilding.getId(), currentStyleIndex);

        // 6) 关键: 清掉 prefab 自己的 currentStructure, 防止 prefab 自己的 renderer 画第二份
        //    (CustomStructureGui.handlePreviewButtonClick 同款逻辑)
        StructureRenderHandler.setStructure(null, null);

        // 7) 关 GUI
        closeScreen();

        player.sendSystemMessage(Component.literal(
            "§a✓ 进入预览: " + info.getName() + "\n" +
            "§7方向键移动, CTRL 旋转, 右键取消, ALT 建造"));
        PrefabCustomAddon.LOGGER.info("[OUTSOURCE] Preview launched: {} at {} facing {} ({} blocks)",
            info.getName(), cfg.pos, cfg.houseFacing, structure.getBlocks().size());
    }

    private static void onBack() {
        closeScreen();
    }

    private static void closeScreen() {
        Construction3DView.release();
        if (Minecraft.getInstance().player != null) {
            // 清空当前会话引用(避免下次会话状态污染)
        }
        currentBuilding = null;
        currentStyleIndex = 0;
        Minecraft.getInstance().setScreen(null);
    }

    private static void refreshLabels() {
        if (currentBuilding == null) return;
        ConstructionInfo info = currentBuilding.getConstruction(currentStyleIndex);
        if (buildingNameLabel != null) {
            buildingNameLabel.setText(info.getName());
        }
        if (authorLabel != null) {
            String author = currentBuilding.getAuthor();
            authorLabel.setText(author != null && !author.isEmpty()
                ? author
                : PrefabCustomAddon.tr("gui.outsource.author_unknown"));
        }
        if (switchBtn != null) {
            if (currentBuilding.hasMultipleStyles()) {
                switchBtn.setText(PrefabCustomAddon.tr("gui.outsource.switch_btn",
                    currentStyleIndex + 1, currentBuilding.getStyleCount()));
                switchBtn.setActive(true);
            } else {
                switchBtn.setText(PrefabCustomAddon.tr("gui.outsource.switch_none"));
                switchBtn.setActive(false);
            }
        }
    }

    private static ModularUI createUI() {
        // 1) root
        UIElement root = new UIElement();
        root.layout(l -> l
            .widthPercent(100).heightPercent(100)
            .flexDirection(FlexDirection.COLUMN)
            .paddingAll(2).gapAll(2)
        );
        root.style(s -> s.background(Sprites.BORDER));
        root.setOverflowVisible(false);

        // 2) body: 左信息面板 + 右 3D
        UIElement bodyRow = new UIElement();
        bodyRow.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .widthPercent(100).flexGrow(1).flexShrink(1)
            .gapAll(4)
            .minHeight(0).minWidth(0)
        );
        bodyRow.setOverflowVisible(false);

        // 2a) 3D 容器(右侧, flexGrow=1)
        sceneContainer = new UIElement();
        sceneContainer.layout(l -> l.flexGrow(1).flexShrink(1).heightPercent(100).minHeight(0).minWidth(0));
        sceneContainer.style(s -> s.background(Sprites.RECT_DARK));
        sceneContainer.setOverflowVisible(false);

        scenePlaceholder = new TextElement();
        scenePlaceholder.setText(PrefabCustomAddon.tr("gui.outsource.3d_loading"));
        scenePlaceholder.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textColor(0xAAAAAA).textWrap(TextWrap.WRAP));
        scenePlaceholder.layout(l -> l.widthPercent(100).heightPercent(100).justifyContent(AlignContent.CENTER));
        sceneContainer.addChild(scenePlaceholder);

        // 2b) 进度文字(在左侧面板顶部,玩家一眼能看到)
        progressEl = new TextElement();
        progressEl.setText(PrefabCustomAddon.tr("gui.outsource.3d_loading"));
        progressEl.textStyle(t -> t.textColor(0xFFFF55).textAlignHorizontal(Horizontal.CENTER));
        progressEl.layout(l -> l.widthPercent(100).height(12));

        // 2c) 左侧信息面板(只显示: 建筑名 + 作者)
        //    关键: ScrollerView 自己区域默认用 BORDER 浅灰底, infoContent 下方空白会显出
        //    "上半暗, 下半浅" 的割裂感. 修法: 走 viewPort 覆盖默认 BORDER sprite → RECT_DARK.
        //    (注意: scrollerStyle.background 不存在, LDLib2 ScrollerView 是用 viewPort 改背景;
        //     之前 2026-08 那次想直接调 .background(IGuiTexture) 编译错, 改成 viewPort 模式.)
        ScrollerView infoScroller = new ScrollerView();
        infoScroller.scrollerStyle(s -> s.mode(ScrollerMode.VERTICAL));
        infoScroller.verticalScroller(v -> v.setScrollBarSize(8f));
        // 用 viewPort 改背景: 整块可视区都铺 RECT_DARK, 内容下面不会再露出浅灰
        infoScroller.viewPort(vp -> vp.style(s -> s
            .backgroundTexture(Sprites.RECT_DARK)
            .overlay(com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture.EMPTY))
            .layout(l -> l.paddingAll(0))  // 内容自己负责 padding, viewPort 别再加 5px
        );
        infoScroller.layout(l -> l.width(140).heightPercent(100).minHeight(0));

        UIElement infoContent = new UIElement();
        infoContent.layout(l -> l
            .flexDirection(FlexDirection.COLUMN)
            .gapAll(4)
            .paddingLeft(10).paddingRight(6)
            .paddingTop(4).paddingBottom(4)
            // 关键: heightPercent(100) 让 infoContent 撑满整个 ScrollerView 的可视区,
            // 否则暗色背景只覆盖 label 那几行, 下方空白会显出 ScrollerView 自己的底色 → "割裂感".
            .heightPercent(100).minHeight(0)
        );
        infoContent.style(s -> s.background(Sprites.RECT_DARK));

        // 进度
        infoContent.addChild(progressEl);

        // 建筑名 label(粗体大字号)
        addFieldLabel(infoContent, PrefabCustomAddon.tr("gui.outsource.building_name"));
        buildingNameLabel = new Label();
        buildingNameLabel.setText(currentBuilding.getConstruction(currentStyleIndex).getName());
        buildingNameLabel.textStyle(t -> t.textColor(0xFFFFFF));
        buildingNameLabel.layout(l -> l.widthPercent(100).height(14));
        infoContent.addChild(buildingNameLabel);

        // 作者 label
        addFieldLabel(infoContent, PrefabCustomAddon.tr("gui.outsource.author"));
        authorLabel = new Label();
        String author = currentBuilding.getAuthor();
        authorLabel.setText(author != null && !author.isEmpty()
            ? author
            : PrefabCustomAddon.tr("gui.outsource.author_unknown"));
        authorLabel.textStyle(t -> t.textColor(0xCCCCCC));
        authorLabel.layout(l -> l.widthPercent(100).height(12));
        infoContent.addChild(authorLabel);

        // 提示行(给 1 风格的多风格建筑显示当前是第几个)
        if (currentBuilding.hasMultipleStyles()) {
            Label hint = new Label();
            hint.setText(PrefabCustomAddon.tr("gui.outsource.style_hint",
                currentStyleIndex + 1, currentBuilding.getStyleCount()));
            hint.textStyle(t -> t.textColor(0xFFAA55));
            hint.layout(l -> l.widthPercent(100).height(12));
            infoContent.addChild(hint);
        }

        // 注: 之前这里有个 "Y 翻转" 按钮. 2026-08 改成数据层翻转
        //   (OutsourceBuilding.shouldFlipY → LitematicaParser.forceFlipY) 后,
        //   预览 NBT 已经是正的了, 按钮没用. 删掉. 实际建造也走同一份 NBT, 永远一致.

        infoScroller.addScrollViewChild(infoContent);
        bodyRow.addChild(infoScroller);
        bodyRow.addChild(sceneContainer);
        root.addChild(bodyRow);

        // 3) 底部按钮行: [返回] [切换] [选择] —— 3 个 flexGrow(1)
        UIElement buttonRow = new UIElement();
        buttonRow.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .widthPercent(100).height(24)
            .gapAll(4)
            .justifyContent(AlignContent.CENTER)
        );

        // 返回
        Button btnBack = new Button().setText(PrefabCustomAddon.tr("gui.outsource.back"));
        btnBack.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        btnBack.layout(l -> l.flexGrow(1).heightPercent(100));
        btnBack.setOnClick(e -> onBack());
        buttonRow.addChild(btnBack);

        // 切换(只有 ≥2 风格才启用)
        switchBtn = new Button();
        if (currentBuilding.hasMultipleStyles()) {
            switchBtn.setText(PrefabCustomAddon.tr("gui.outsource.switch_btn",
                currentStyleIndex + 1, currentBuilding.getStyleCount()));
            switchBtn.setActive(true);
        } else {
            switchBtn.setText(PrefabCustomAddon.tr("gui.outsource.switch_none"));
            switchBtn.setActive(false);
        }
        switchBtn.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        switchBtn.layout(l -> l.flexGrow(1).heightPercent(100));
        switchBtn.setOnClick(e -> switchStyle());
        buttonRow.addChild(switchBtn);

        // 预览 (点后进入附属的世界内 3D 预览模式: 方向键移动, CTRL 旋转, ALT 建造)
        selectBtn = new Button().setText(PrefabCustomAddon.tr("gui.outsource.preview"));
        selectBtn.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        selectBtn.layout(l -> l.flexGrow(1).heightPercent(100));
        selectBtn.setOnClick(e -> onPreview());
        buttonRow.addChild(selectBtn);

        root.addChild(buttonRow);

        // 4) tick handler —— 跑 3D 渲染状态机
        root.addEventListener(UIEvents.TICK, event -> {
            Construction3DView.runTick(progressEl, sceneContainer, scenePlaceholder);
        });

        return ModularUI.of(UI.of(root,
            StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)));
    }

    private static void addFieldLabel(UIElement parent, String text) {
        TextElement lbl = new TextElement();
        lbl.setText(text);
        lbl.textStyle(t -> t.textColor(0xFFFF55));
        lbl.layout(l -> l.widthPercent(100).height(12));
        parent.addChild(lbl);
    }
}
