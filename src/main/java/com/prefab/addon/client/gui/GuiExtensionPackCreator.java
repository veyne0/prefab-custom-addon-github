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
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.PackStatusService;
import com.prefab.addon.work.PackCreator;

import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 拓展包制作界面 (X 键打开) - LDLib2 实现.
 *
 * <h2>布局 (整屏 100%)</h2>
 * <pre>
 *   ┌────────────────────────────────────────────────┐
 *   │ 拓展包制作 - 本地工作区                          │ (title h=20)
 *   │ 工作目录: D:\...\prefab-work                     │ (subtitle h=14)
 *   ├──────────┬────────────────────┬────────────────┤
 *   │ 我的拓展包│ 选中包: 标题        │ [封面图 48x48] │
 *   │ ┌──────┐ │ 标识符/作者/版本    │ [更换封面]     │
 *   │ │pack1 │ │ 依赖/链接/描述     │ 状态: ...      │
 *   │ │pack2 │ │ (scroller)        │ 本地: 11 预装: 0│
 *   │ │...   │ │                    │ [➕ 添加]       │
 *   │ └──────┘ │                    │ ┌─建筑列表─┐    │
 *   │ (scroll) │                    │ │b1 b2 ... │    │
 *   │          │                    │ └──────────┘    │
 *   ├──────────┴────────────────────┴────────────────┤
 *   │ [创建拓展][编辑][创建建筑][删建筑][删包][目录][刷新][关闭]│ (h=22)
 *   │ 状态: ...                                       │ (h=12)
 *   └────────────────────────────────────────────────┘
 * </pre>
 */
public final class GuiExtensionPackCreator {

    private GuiExtensionPackCreator() {}

    // === 静态状态 (供子界面回调) ===
    private static final List<PackCreator.PackWorkInfo> packs = new ArrayList<>();
    private static List<PackCreator.BuildingWorkInfo> buildings = new ArrayList<>();
    private static int selectedIndex = -1;
    private static int selectedBuildingIndex = -1;
    private static PackStatusService.Status currentPackStatus = null;

    // 封面图缓存: packId -> ResourceLocation
    private static final Map<String, ResourceLocation> coverTextures = new HashMap<>();
    private static String loadedCoverFor = null;

    // 状态消息
    private static String statusMessage = null;
    private static int statusColor = 0x55FF55;
    private static int statusTick = 0;

    // === UI 引用 (tick handler 用) ===
    private static TextElement statusEl;
    private static UIElement packListContent;
    private static UIElement buildingListContent;
    private static UIElement infoContent;
    private static UIElement coverEl;
    private static UIElement bScroller;
    private static UIElement infoScroller;
    private static Selector<String> packSelector;
    private static Button btnCreatePack;
    private static Button btnEditPack;
    private static Button btnCreateBuilding;
    private static Button btnDeleteBuilding;
    private static Button btnDeletePack;
    private static Button btnEditCover;
    private static Button btnOpenFolder;
    private static Button btnRefresh;
    private static Button btnClose;
    private static Button btnAddToExtension;

    // === 静态单例 (供子界面拿 parent ref) ===
    private static GuiExtensionPackCreator currentInstance = null;

    /** 子界面 / 外部调用: 拿到当前实例 (用于 parent ref) */
    public static GuiExtensionPackCreator getCurrent() {
        return currentInstance;
    }

    public static void open() {
        // 释放旧封面纹理
        for (ResourceLocation loc : coverTextures.values()) {
            Minecraft.getInstance().getTextureManager().release(loc);
        }
        coverTextures.clear();
        loadedCoverFor = null;

        selectedIndex = -1;
        selectedBuildingIndex = -1;
        currentPackStatus = null;
        statusMessage = null;
        statusTick = 0;
        buildings = new ArrayList<>();
        currentInstance = new GuiExtensionPackCreator();
        refreshPacks();
        // 关键修复: 默认选中第一个包, 避免用户必须点 刷新 才能看到建筑
        if (selectedIndex == -1 && !packs.isEmpty()) {
            selectedIndex = 0;
            loadBuildingsForSelected();
        }

        ModularUI ui = createUI();
        Minecraft.getInstance().setScreen(
            new ModularUIScreen(ui, Component.literal("拓展包制作 - 本地工作区")));
    }

    /**
     * 子界面关闭后回调: 重新打开 (保留选中状态).
     * 同时关闭其他子界面, 防止堆叠多层 GuiCreatePackInfo.
     */
    public void onChildClosed() {
        int savedSelectedIndex = selectedIndex;
        int savedBuildingIndex = selectedBuildingIndex;
        // 先 close 当前的 (避免叠加)
        Minecraft.getInstance().execute(() -> {
            Minecraft.getInstance().setScreen(null);
            // 然后重新打开本 GUI
            Minecraft.getInstance().execute(() -> {
                open();
                // 恢复选中
                if (savedSelectedIndex >= 0 && savedSelectedIndex < packs.size()) {
                    selectedIndex = savedSelectedIndex;
                    loadBuildingsForSelected();
                    if (savedBuildingIndex >= 0 && savedBuildingIndex < buildings.size()) {
                        selectedBuildingIndex = savedBuildingIndex;
                    }
                }
                rebuildInfoPanel();
                rebuildPackSelector();
                rebuildBuildingList();
                rebuildAddButton();
            });
        });
    }

    // === 数据加载 ===

    private static void refreshPacks() {
        try {
            packs.clear();
            packs.addAll(PackCreator.getInstance().scanPacks());
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[CREATOR] scan packs failed", t);
        }
        if (selectedIndex >= packs.size()) {
            selectedIndex = -1;
            buildings = new ArrayList<>();
        }
        if (selectedIndex >= 0) {
            loadBuildingsForSelected();
        }
        updateButtonStates();
    }

    private static void loadBuildingsForSelected() {
        if (selectedIndex < 0 || selectedIndex >= packs.size()) {
            buildings = new ArrayList<>();
            currentPackStatus = null;
            return;
        }
        PackCreator.PackWorkInfo p = packs.get(selectedIndex);
        try {
            buildings = PackCreator.getInstance().readBuildings(p.id);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[CREATOR] read buildings failed", t);
            buildings = new ArrayList<>();
        }
        if (selectedBuildingIndex >= buildings.size()) selectedBuildingIndex = -1;
        loadCoverFor(p);
        detectPackStatus();
    }

    private static void detectPackStatus() {
        if (selectedIndex < 0 || selectedIndex >= packs.size()) {
            currentPackStatus = null;
            return;
        }
        try {
            PackCreator.PackWorkInfo p = packs.get(selectedIndex);
            currentPackStatus = PackStatusService.checkStatus(p.id);
            PrefabCustomAddon.LOGGER.info("[CREATOR] pack '{}' status: {} (local={} inst={})",
                p.id, currentPackStatus.state,
                currentPackStatus.localBuildings.size(),
                currentPackStatus.installedBuildings.size());
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[CREATOR] detectPackStatus failed", t);
            currentPackStatus = null;
        }
    }

    private static void loadCoverFor(PackCreator.PackWorkInfo p) {
        if (loadedCoverFor != null && loadedCoverFor.equals(p.id)) return;
        if (loadedCoverFor != null) {
            ResourceLocation old = coverTextures.remove(loadedCoverFor);
            if (old != null) Minecraft.getInstance().getTextureManager().release(old);
        }
        loadedCoverFor = p.id;
        if (p.coverImage == null || !Files.exists(p.coverImage)) return;
        try {
            NativeImage ni;
            try (var in = Files.newInputStream(p.coverImage)) {
                ni = NativeImage.read(in);
            }
            if (ni == null) return;
            DynamicTexture tex = new DynamicTexture(ni);
            ResourceLocation loc = Minecraft.getInstance().getTextureManager()
                .register("prefab_addon/cover_" + p.id, tex);
            coverTextures.put(p.id, loc);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[CREATOR] load cover failed for " + p.id, t);
        }
    }

    private static void updateButtonStates() {
        boolean hasPack = (selectedIndex >= 0 && selectedIndex < packs.size());
        boolean hasBuilding = (selectedBuildingIndex >= 0 && selectedBuildingIndex < buildings.size());
        if (btnCreateBuilding != null) btnCreateBuilding.setActive(hasPack);
        if (btnEditPack != null) btnEditPack.setActive(hasPack);
        if (btnDeleteBuilding != null) btnDeleteBuilding.setActive(hasBuilding);
        if (btnDeletePack != null) btnDeletePack.setActive(hasPack);
        if (btnEditCover != null) btnEditCover.setActive(hasPack);
    }

    private static void setStatus(String msg, int color) {
        statusMessage = msg;
        statusColor = color;
        statusTick = 80;
    }

    // === UI 重建 (选中/状态变化时) ===

    /** 构建下拉框候选列表.
     * 关键: Selector 用 setCandidates + onValueChanged 的 value 做 .equals 比较,
     * 如果两个包的 display 一样 (e.g. 两个都叫 "测试文件") + id 不一样,
     * 会因为 Selector 把它们去重 → 只显示一项.
     * 解决: 直接用 pack id 作为 selector 的 value (唯一), display 写在 setCandidateUIProvider 自渲染.
     */
    private static List<String> buildPackCandidates() {
        List<String> ids = new ArrayList<>();
        for (PackCreator.PackWorkInfo p : packs) {
            ids.add(p.id);
        }
        return ids;
    }

    /**
     * 把 pack.id 渲染成下拉框里的可读行 (id + 可选 name).
     * 让同一名字的两个包也能区分开来 (后缀 (id)).
     */
    private static String packDisplay(PackCreator.PackWorkInfo p) {
        if (p.name == null || p.name.isEmpty() || p.name.equals(p.id)) {
            return "§f" + p.id;
        }
        return "§f" + p.name + " §7(" + p.id + ")";
    }

    /** 下拉框选择变更回调 - 现在 value 直接就是 pack.id */
    private static void onPackSelectorChanged(String newValue) {
        if (newValue == null) return;
        int newIdx = -1;
        for (int i = 0; i < packs.size(); i++) {
            if (packs.get(i).id.equals(newValue)) {
                newIdx = i;
                break;
            }
        }
        if (newIdx != selectedIndex) {
            selectedIndex = newIdx;
            selectedBuildingIndex = -1;
            loadBuildingsForSelected();
            updateButtonStates();
            rebuildInfoPanel();
            rebuildBuildingList();
            rebuildAddButton();
            rebuildCover();
        }
    }

    private static void rebuildPackSelector() {
        if (packSelector == null) return;
        String prev = packSelector.getValue();
        List<String> newCandidates = buildPackCandidates();
        packSelector.setCandidates(newCandidates);
        PrefabCustomAddon.LOGGER.info("[CREATOR] rebuildPackSelector: packs.size={} candidates={}",
            packs.size(), newCandidates);
        if (selectedIndex >= 0 && selectedIndex < packs.size()) {
            packSelector.setValue(packs.get(selectedIndex).id, false);
        } else if (prev != null && newCandidates.contains(prev)) {
            packSelector.setValue(prev, false);
        } else if (!newCandidates.isEmpty()) {
            packSelector.setValue(newCandidates.get(0), false);
            selectedIndex = 0;
        }
    }

    private static void rebuildPackList() {
        // 兼容旧调用, 实际更新下拉框
        rebuildPackSelector();
    }

    private static UIElement createPackListItem(int idx, PackCreator.PackWorkInfo p) {
        // 旧方法, 不再使用 (拓展包选择已改为下拉框). 保留以防编译错误.
        return new UIElement();
    }

    private static void rebuildInfoPanel() {
        if (infoContent == null) return;
        infoContent.clearAllChildren();
        if (selectedIndex < 0 || selectedIndex >= packs.size()) {
            // 占位提示
            TextElement empty = new TextElement();
            empty.setText("← 选择左侧的拓展包\n或点击「创建拓展包」开始");
            empty.textStyle(t -> t.textColor(0xAAAAAA)
                .textAlignHorizontal(Horizontal.CENTER)
                .textWrap(TextWrap.WRAP));
            empty.layout(l -> l.widthPercent(100).heightPercent(100)
                .justifyContent(AlignContent.CENTER));
            infoContent.addChild(empty);
            return;
        }
        PackCreator.PackWorkInfo p = packs.get(selectedIndex);
        // 标题
        String title = p.name == null || p.name.isEmpty() ? p.id : p.name;
        TextElement titleEl = new TextElement();
        titleEl.setText("§l" + truncate(title, 20));
        titleEl.textStyle(t -> t.textColor(0x55AAFF));
        titleEl.layout(l -> l.widthPercent(100).height(18).flexShrink(0).flexGrow(0));
        infoContent.addChild(titleEl);

        addField(infoContent, "标识符:", p.id);
        addField(infoContent, "作者:", p.author);
        addField(infoContent, "版本:", p.version);
        // 依赖: 把逗号分隔的依赖列表换成换行分隔, 每个 mod 一行, 方便分辨
        String deps = p.dependencies;
        int depLineCount = 0;
        if (deps != null && !deps.isEmpty()) {
            deps = deps.replaceAll("[\\r\\n\\s,]+", ",").trim();
            if (deps.endsWith(",")) deps = deps.substring(0, deps.length() - 1);
            String[] depArr = deps.split(",");
            depLineCount = depArr.length;
            deps = String.join("\n", depArr);
        }
        // 高度: 每行约 12px (字号 9 + 间距), 限制在 32~120 之间
        int depHeight = Math.max(32, Math.min(120, depLineCount * 12 + 4));
        addField(infoContent, "依赖:", deps, true, depHeight);
        addField(infoContent, "链接:", p.link);
        // 描述: 如果太长就截断, 避免一整段话占满屏
        String desc = p.description;
        if (desc != null && desc.length() > 60) desc = desc.substring(0, 58) + "..";
        addField(infoContent, "描述:", desc, true);
    }

    private static void addField(UIElement parent, String label, String value) {
        addField(parent, label, value, false, 0);
    }

    private static void addField(UIElement parent, String label, String value, boolean fullWidth) {
        addField(parent, label, value, fullWidth, 0);
    }

    /**
     * 添加信息字段 (标签 + 值, 单行 / 多行)
     * @param customHeight 当 >0 时覆盖 fullWidth 默认的 40px 高度, 用于依赖 (多 mod) 等
     */
    private static void addField(UIElement parent, String label, String value, boolean fullWidth, int customHeight) {
        // 单行: 标签 (固定宽 50) + 值 (flex 1, 固定高度避免滚动时高度变化导致闪烁)
        UIElement row = new UIElement();
        row.layout(l -> l.widthPercent(100).flexDirection(FlexDirection.ROW)
            .gapAll(4).minHeight(0).heightAuto());
        row.setOverflowVisible(false);

        // 标签 - 固定高度, flexShrink(0) 防止被压缩
        TextElement labelEl = new TextElement();
        labelEl.setText(label);
        labelEl.textStyle(t -> t.textColor(0xAAAAAA));
        labelEl.layout(l -> l.width(36).height(14).flexShrink(0).flexGrow(0));
        row.addChild(labelEl);

        // 值 - 固定高度, flexShrink(0) 防止被压缩
        // fullWidth=true (描述) 40px, 可显示 2-3 行
        // fullWidth=false 默认 14px 单行
        String val = value == null || value.isEmpty() ? "-" : value;
        TextElement valEl = new TextElement();
        valEl.setText(val);
        // 关键: WRAP 会触发 recompute() 在 layout 变化时 → 滚动时闪烁
        // 单行字段直接用 NONE, 描述等长字段单独处理
        valEl.textStyle(t -> t.textColor(fullWidth ? 0xFFDDCC55 : 0xFFFFFF)
            .textWrap(TextWrap.NONE).adaptiveHeight(false)
            .fontSize(customHeight > 0 ? 7f : 9f));
        int valHeight = customHeight > 0 ? customHeight : (fullWidth ? 40 : 14);
        // 当 customHeight > 0, value 高度应让字垂直居中 (用 alignItems CENTER)
        if (customHeight > 0) {
            valEl.layout(l -> l.height(valHeight).flexShrink(0).flexGrow(1)
                .alignItems(dev.vfyjxf.taffy.style.AlignItems.FLEX_START));
        } else if (fullWidth) {
            valEl.layout(l -> l.height(valHeight).flexShrink(0).flexGrow(1));
        } else {
            valEl.layout(l -> l.height(14).flexShrink(0).flexGrow(1));
        }
        row.addChild(valEl);

        parent.addChild(row);

        // 间距 - 固定高度避免压缩
        UIElement spacer = new UIElement();
        spacer.layout(l -> l.widthPercent(100).height(2).flexShrink(0).flexGrow(0));
        parent.addChild(spacer);
    }

    private static void rebuildBuildingList() {
        if (buildingListContent == null) return;
        buildingListContent.clearAllChildren();
        if (buildings.isEmpty()) {
            TextElement empty = new TextElement();
            empty.setText("(无)");
            empty.textStyle(t -> t.textColor(0x888888)
                .textAlignHorizontal(Horizontal.CENTER));
            empty.layout(l -> l.widthPercent(100).height(20));
            buildingListContent.addChild(empty);
            return;
        }
        for (int i = 0; i < buildings.size(); i++) {
            final int idx = i;
            PackCreator.BuildingWorkInfo b = buildings.get(i);
            UIElement item = new UIElement();
            item.layout(l -> l.widthPercent(100).height(20).marginBottom(1
                ).flexDirection(FlexDirection.COLUMN).paddingAll(1));
            item.setOverflowVisible(false);
            if (idx == selectedBuildingIndex) {
                // 选中项: 浅灰色 (纯色无边框, 不会闪)
                item.style(s -> s.backgroundTexture(ColorPattern.GRAY.rectTexture()));
            } else {
                // 未选中: 深灰色 (纯色无边框, 不会闪)
                item.style(s -> s.backgroundTexture(ColorPattern.DARK_GRAY.rectTexture()));
            }
            String bname = b.name == null || b.name.isEmpty() ? b.id : b.name;
            TextElement nameEl = new TextElement();
            nameEl.setText(truncate(bname, 8));
            nameEl.textStyle(t -> t.textColor(0xFFFFFF));
            nameEl.layout(l -> l.widthPercent(100).height(11));
            item.addChild(nameEl);
            TextElement sizeEl = new TextElement();
            sizeEl.setText(b.size == null ? "" : b.size);
            sizeEl.textStyle(t -> t.textColor(0x55FF55));
            sizeEl.layout(l -> l.widthPercent(100).height(9));
            item.addChild(sizeEl);
            item.addEventListener(UIEvents.MOUSE_DOWN, e -> {
                selectedBuildingIndex = idx;
                // 关键: 不重建列表, 只更新选中 item 的 style. 重建会重置 scrollOffset
                // 导致 viewContainer 整体跳变 ~70 像素 (肉眼可见闪烁).
                updateAllBuildingItemStyles();
                updateButtonStates();
                openEditBuilding();
            });
            buildingListContent.addChild(item);
            // 分隔线: 在每个 item 之后 (最后一个不加) 加 1px 灰线, 让 item 边界清晰
            if (i < buildings.size() - 1) {
                UIElement separator = new UIElement();
                separator.layout(l -> l.widthPercent(100).height(1).flexShrink(0).flexGrow(0));
                separator.style(s -> s.backgroundTexture(ColorPattern.GRAY.rectTexture()));
                buildingListContent.addChild(separator);
            }
        }
    }

    /** 更新所有 building item 的选中样式 (不重建, 避免 scrollOffset 重置) */
    private static void updateAllBuildingItemStyles() {
        if (buildingListContent == null) return;
        for (int i = 0; i < buildingListContent.getChildren().size(); i++) {
            UIElement child = buildingListContent.getChildren().get(i);
            updateBuildingItemStyle(child, i);
        }
    }

    private static void updateBuildingItemStyle(UIElement item, int idx) {
        if (item == null) return;
        if (idx == selectedBuildingIndex) {
            item.style(s -> s.backgroundTexture(ColorPattern.GRAY.rectTexture()));
        } else {
            item.style(s -> s.backgroundTexture(ColorPattern.DARK_GRAY.rectTexture()));
        }
    }

    private static void rebuildAddButton() {
        if (btnAddToExtension == null) return;
        if (currentPackStatus == null) {
            btnAddToExtension.setVisible(false);
            return;
        }
        btnAddToExtension.setVisible(true);
        switch (currentPackStatus.state) {
            case NOT_ADDED:
                btnAddToExtension.setActive(true);
                btnAddToExtension.setText("➕ 添加");
                break;
            case ADDED_SAME:
                btnAddToExtension.setActive(false);
                btnAddToExtension.setText("✓ 已添加");
                break;
            case ADDED_DIFFERENT:
                btnAddToExtension.setActive(true);
                btnAddToExtension.setText("⟳ 重新添加");
                break;
        }
    }

    private static void rebuildCover() {
        if (coverEl == null) return;
        // 触发 coverEl 重新计算 (在 tick 里基于 selectedIndex 选图)
        coverEl.setActive(true);
    }

    // === 工具方法 ===

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 2) + "..";
    }

    private static String stripColor(String s) {
        if (s == null) return "";
        return s.replaceAll("§.", "");
    }

    // ===== FLICKER DEBUG =====
    private static long lastDebugLogMs = 0L;
    private static float lastBuildingListTop = Float.NaN;
    private static float lastInfoContentTop = Float.NaN;
    private static int frameCounter = 0;
    private static long lastFlickerCheckMs = 0L;
    private static int flickerFrameCount = 0;
    // 微抖动检测: 累积 deltaY 抖动
    private static float accumulatedJitterB = 0f;
    private static float accumulatedJitterI = 0f;
    // text 元素 ref 字 hash
    private static int lastInfoContentHash = 0;
    private static int infoContentSetTextCount = 0;

    /** 每帧调用, 检测闪烁根因 */
    public static void onFrameTick() {
        if (currentInstance == null) return;
        frameCounter++;
        long now = System.currentTimeMillis();
        if (now - lastDebugLogMs < 500) return; // 0.5s 节流
        lastDebugLogMs = now;
        try {
            float bTop = buildingListContent != null ? buildingListContent.getPositionY() : Float.NaN;
            float iTop = infoContent != null ? infoContent.getPositionY() : Float.NaN;
            float bVPTop = bScroller != null ? bScroller.getPositionY() : Float.NaN;
            float iVPTop = infoScroller != null ? infoScroller.getPositionY() : Float.NaN;
            // 检测 top 偏移是否在变
            float bDelta = 0f, iDelta = 0f;
            if (!Float.isNaN(lastBuildingListTop)) {
                bDelta = bTop - lastBuildingListTop;
                if (Math.abs(bDelta) > 0.001f) accumulatedJitterB += Math.abs(bDelta);
            }
            if (!Float.isNaN(lastInfoContentTop)) {
                iDelta = iTop - lastInfoContentTop;
                if (Math.abs(iDelta) > 0.001f) accumulatedJitterI += Math.abs(iDelta);
            }
            // 检测子元素数量和 hash
            int infoChildCount = -1;
            int bChildCount = -1;
            try { infoChildCount = infoContent != null ? infoContent.getChildren().size() : -1; } catch (Throwable ignored) {}
            try { bChildCount = buildingListContent != null ? buildingListContent.getChildren().size() : -1; } catch (Throwable ignored) {}
            StringBuilder bj = new StringBuilder();
            if (bDelta != 0) bj.append(String.format("⚠B_DRIFT(%.4f)", bDelta));
            if (iDelta != 0) bj.append(String.format("⚠I_DRIFT(%.4f)", iDelta));
            PrefabCustomAddon.LOGGER.info("[FLICKER-DBG] f#{} bList={} info={} bVP={} iVP={} dt={}ms jB={} jI={} bN={} iN={} iSet={} {}",
                frameCounter,
                String.format("%.4f", bTop),
                String.format("%.4f", iTop),
                String.format("%.1f", bVPTop),
                String.format("%.1f", iVPTop),
                now - lastFlickerCheckMs,
                String.format("%.4f", accumulatedJitterB),
                String.format("%.4f", accumulatedJitterI),
                bChildCount,
                infoChildCount,
                infoContentSetTextCount,
                bj);
            lastBuildingListTop = bTop;
            lastInfoContentTop = iTop;
            lastFlickerCheckMs = now;
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[FLICKER-DBG] tick err: {}", t.toString());
        }
    }

    // === UI 创建 ===

    private static ModularUI createUI() {
        PrefabCustomAddon.LOGGER.info("[CREATOR] createUI");

        // 根
        UIElement root = new UIElement();
        root.layout(l -> l
            .widthPercent(100).heightPercent(100)
            .flexDirection(FlexDirection.COLUMN)
            .paddingAll(2).gapAll(2)
        );
        root.style(s -> s.backgroundTexture(ColorPattern.SEAL_BLACK.rectTexture()));
        root.setOverflowVisible(false);

        // === 标题行 ===
        Label titleEl = new Label();
        titleEl.setText("§l拓展包制作 - 本地工作区");
        titleEl.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        titleEl.layout(l -> l.widthPercent(100).height(20));
        root.addChild(titleEl);

        // === 副标题行 ===
        Path workRoot = PackCreator.getWorkRoot();
        TextElement subtitleEl = new TextElement();
        subtitleEl.setText("工作目录: " + workRoot.toString());
        subtitleEl.textStyle(t -> t.textColor(0xAAAAAA)
            .textAlignHorizontal(Horizontal.CENTER));
        subtitleEl.layout(l -> l.widthPercent(100).height(14));
        root.addChild(subtitleEl);

        // === 拓展包选择行 (下拉框) ===
        UIElement packSelectRow = new UIElement();
        packSelectRow.layout(l -> l
            .widthPercent(100).height(22)
            .flexDirection(FlexDirection.ROW).gapAll(4)
            .alignItems(AlignItems.CENTER)
        );
        packSelectRow.setOverflowVisible(false);
        Label packLabel = new Label();
        packLabel.setText("§l选择拓展包:");
        packLabel.textStyle(t -> t.textAlignHorizontal(Horizontal.LEFT));
        packLabel.layout(l -> l.height(18).flexShrink(0));
        packSelectRow.addChild(packLabel);

        packSelector = new Selector<String>();
        packSelector.setCandidates(buildPackCandidates());
        // 自定义下拉框候选的渲染 (直接显示 pack.id 即可, 友好显示名)
        packSelector.setCandidateUIProvider(value -> {
            // value 可能是 null (初始化)
            String show = value == null ? "(无)" : value;
            // 找到对应 pack, 显示友好名 (name + id)
            for (PackCreator.PackWorkInfo p : packs) {
                if (p.id.equals(value)) {
                    show = packDisplay(p);
                    break;
                }
            }
            Label lbl = new Label();
            lbl.setText(show);
            lbl.layout(l -> l.widthPercent(100).height(14));
            return lbl;
        });
        packSelector.setOnValueChanged(GuiExtensionPackCreator::onPackSelectorChanged);
        packSelector.layout(l -> l.flexGrow(1).height(18));
        packSelector.selectorStyle(s -> s.maxItemCount(8).scrollerViewHeight(120));
        if (selectedIndex >= 0 && selectedIndex < packs.size()) {
            packSelector.setValue(packs.get(selectedIndex).id, false);
        }
        packSelectRow.addChild(packSelector);
        root.addChild(packSelectRow);

        // === 主体 (3 列: 建筑列表 / 信息 / 封面+操作) ===
        // 关键: 用一个外层 ScrollerView 包裹整个 bodyRow, 取消 3 个内层 ScrollerView.
        // 内层多个 ScrollerView 的 scrollOffset 会在每帧 clamp 造成 viewContainer 跳变闪烁.
        ScrollerView bodyScroller = new ScrollerView();
        bodyScroller.layout(l -> l.widthPercent(100).flexGrow(1).flexShrink(1).minHeight(0));
        bodyScroller.scrollerStyle(s -> s.mode(ScrollerMode.VERTICAL)
            .verticalScrollDisplay(ScrollDisplay.AUTO)
            .horizontalScrollDisplay(ScrollDisplay.NEVER)
            .minScrollPixel(8)
            .maxScrollPixel(120));
        bodyScroller.verticalScroller(s -> s.setScrollBarSize(4));
        bodyScroller.viewPort(vp -> vp.style(s -> s.backgroundTexture(ColorPattern.SEAL_BLACK.rectTexture()).overlay(IGuiTexture.EMPTY)));

        UIElement bodyContent = new UIElement();
        // 关键: 不用 heightAuto(), 避免浮点漂移
        bodyContent.layout(l -> l.widthPercent(100)
            .flexDirection(FlexDirection.COLUMN).gapAll(2).minHeight(0).flexShrink(0));
        bodyScroller.addScrollViewChild(bodyContent);
        root.addChild(bodyScroller);

        UIElement bodyRow = new UIElement();
        bodyRow.layout(l -> l
            .widthPercent(100).flexShrink(0)
            .flexDirection(FlexDirection.ROW)
            .gapAll(2).minHeight(0).minWidth(0)
            .alignItems(dev.vfyjxf.taffy.style.AlignItems.STRETCH)
        );
        bodyRow.setOverflowVisible(false);
        bodyContent.addChild(bodyRow);

        // -- 左面板: 建筑列表 --
        // 关键: 显式设 flex(1) 让 leftPanel 在 row-direction 父容器中**拉伸到与父同等高度**,
        // 否则 heightPercent(100) 在 taffy row flex 容器中可能塌缩成 0, 内部 ScrollerView
        // 拿不到高度 → 滚动条无法响应鼠标滚轮.
        UIElement leftPanel = new UIElement();
        leftPanel.layout(l -> l.width(130).flex(1).flexShrink(0)
            .flexDirection(FlexDirection.COLUMN).gapAll(2).minHeight(0));
        leftPanel.setOverflowVisible(false);
        Label leftTitle = new Label();
        leftTitle.setText("§l建筑");
        leftTitle.textStyle(t -> t.textAlignHorizontal(Horizontal.LEFT));
        leftTitle.layout(l -> l.widthPercent(100).height(16));
        leftPanel.addChild(leftTitle);

        bScroller = new UIElement();
        bScroller.layout(l -> l.widthPercent(100).flexGrow(1).flexShrink(1).minHeight(0));
        bScroller.setOverflowVisible(false);
        bScroller.style(s -> s.backgroundTexture(ColorPattern.DARK_GRAY.rectTexture()));
        buildingListContent = new UIElement();
        // 关键: 不用 heightAuto(), 避免浮点漂移
        buildingListContent.layout(l -> l.widthPercent(100)
            .flexDirection(FlexDirection.COLUMN).minHeight(0).flexShrink(0));
        bScroller.addChild(buildingListContent);
        leftPanel.addChild(bScroller);
        bodyRow.addChild(leftPanel);

        // -- 中面板: 选中包信息 --
        UIElement midPanel = new UIElement();
        midPanel.layout(l -> l.flexGrow(1).flexShrink(1).flex(1)
            .minHeight(0).minWidth(0)
            .flexDirection(FlexDirection.COLUMN).paddingAll(2));
        midPanel.style(s -> s.backgroundTexture(ColorPattern.DARK_GRAY.rectTexture()));
        midPanel.setOverflowVisible(false);

        infoScroller = new UIElement();
        infoScroller.layout(l -> l.widthPercent(100).flexGrow(1).flexShrink(1).minHeight(0));
        infoScroller.setOverflowVisible(false);
        infoScroller.style(s -> s.backgroundTexture(ColorPattern.DARK_GRAY.rectTexture()));
        infoContent = new UIElement();
        infoContent.layout(l -> l.widthPercent(100)
            .flexDirection(FlexDirection.COLUMN).paddingAll(2).minHeight(0).flexShrink(0));
        infoScroller.addChild(infoContent);
        midPanel.addChild(infoScroller);
        bodyRow.addChild(midPanel);

        // -- 右面板: 封面 + 状态 (用 UIElement clip 即可) --
        UIElement rightScroller = new UIElement();
        rightScroller.layout(l -> l.width(130).flex(1).flexShrink(0).flexGrow(0)
            .minHeight(0));
        rightScroller.setOverflowVisible(false);

        UIElement rightPanel = new UIElement();
        rightPanel.layout(l -> l.widthPercent(100)
            .flexDirection(FlexDirection.COLUMN).gapAll(4).minHeight(0).flexShrink(0));
        rightPanel.setOverflowVisible(false);

        // 封面图 (40x40 + 边框)
        UIElement coverBox = new UIElement();
        coverBox.layout(l -> l.widthPercent(100).height(46)
            .justifyContent(AlignContent.CENTER).alignItems(AlignItems.CENTER));
        coverBox.style(s -> s.backgroundTexture(ColorPattern.DARK_GRAY.rectTexture()));
        coverBox.setOverflowVisible(false);
        // 用 CoverImageElement 显示封面
        coverEl = new CoverImageElement(40, 40);
        coverEl.layout(l -> l.width(40).height(40));
        coverBox.addChild(coverEl);
        rightPanel.addChild(coverBox);

        // 更换封面按钮
        btnEditCover = new Button().setText("更换封面");
        btnEditCover.setOnClick(e -> openCoverChooser());
        btnEditCover.layout(l -> l.widthPercent(100).height(18));
        rightPanel.addChild(btnEditCover);

        // 状态信息 (本地/预装建筑数) - 固定区域, 不参与布局压缩
        UIElement statusBox = new UIElement();
        statusBox.layout(l -> l.widthPercent(100).height(46).minHeight(46)
            .flexDirection(FlexDirection.COLUMN).gapAll(2).paddingAll(2));
        statusBox.style(s -> s.backgroundTexture(ColorPattern.DARK_GRAY.rectTexture()));
        statusBox.setOverflowVisible(false);
        TextElement statusInfoEl = new TextElement();
        statusInfoEl.setId("creator_status_info");
        statusInfoEl.setText("");
        statusInfoEl.textStyle(t -> t.textColor(0xAAAAAA).textWrap(TextWrap.WRAP).adaptiveHeight(true));
        statusInfoEl.layout(l -> l.widthPercent(100).heightAuto().minHeight(0));
        statusBox.addChild(statusInfoEl);
        rightPanel.addChild(statusBox);

        // 添加按钮
        btnAddToExtension = new Button().setText("添加");
        btnAddToExtension.setOnClick(e -> addSelectedPackToExtension());
        btnAddToExtension.layout(l -> l.widthPercent(100).height(20).marginTop(4));
        rightPanel.addChild(btnAddToExtension);

        rightScroller.addChild(rightPanel);
        bodyRow.addChild(rightScroller);
        // bodyRow 已经添加到 bodyContent, 不需要 root.addChild(bodyRow)

        // === 底部按钮行 ===
        UIElement buttonRow = new UIElement();
        buttonRow.layout(l -> l
            .widthPercent(100).height(22)
            .flexDirection(FlexDirection.ROW).gapAll(2)
            .justifyContent(AlignContent.CENTER)
        );
        buttonRow.setOverflowVisible(false);

        btnCreatePack = new Button().setText("创建拓展包");
        btnCreatePack.setOnClick(e -> GuiCreatePackInfo.open(null, currentInstance));
        btnCreatePack.layout(l -> l.flexGrow(1).heightPercent(100));
        buttonRow.addChild(btnCreatePack);

        btnEditPack = new Button().setText("编辑拓展包");
        btnEditPack.setOnClick(e -> {
            if (selectedIndex >= 0 && selectedIndex < packs.size()) {
                GuiCreatePackInfo.open(packs.get(selectedIndex), currentInstance);
            } else {
                setStatus("请先选中一个拓展包", 0xFF5555);
            }
        });
        btnEditPack.layout(l -> l.flexGrow(1).heightPercent(100));
        buttonRow.addChild(btnEditPack);

        btnCreateBuilding = new Button().setText("创建建筑");
        btnCreateBuilding.setOnClick(e -> {
            if (selectedIndex < 0) return;
            PackCreator.PackWorkInfo p = packs.get(selectedIndex);
            GuiCreateBuildingInfo.open(p.id, null, currentInstance);
        });
        btnCreateBuilding.layout(l -> l.flexGrow(1).heightPercent(100));
        buttonRow.addChild(btnCreateBuilding);

        btnDeleteBuilding = new Button().setText("删建筑");
        btnDeleteBuilding.setOnClick(e -> deleteSelectedBuilding());
        btnDeleteBuilding.layout(l -> l.flexGrow(1).heightPercent(100));
        buttonRow.addChild(btnDeleteBuilding);

        btnDeletePack = new Button().setText("删拓展包");
        btnDeletePack.setOnClick(e -> deleteSelectedPack());
        btnDeletePack.layout(l -> l.flexGrow(1).heightPercent(100));
        buttonRow.addChild(btnDeletePack);

        btnOpenFolder = new Button().setText("打开目录");
        btnOpenFolder.setOnClick(e -> openWorkFolder());
        btnOpenFolder.layout(l -> l.flexGrow(1).heightPercent(100));
        buttonRow.addChild(btnOpenFolder);

        btnRefresh = new Button().setText("刷新");
        btnRefresh.setOnClick(e -> {
            refreshPacks();
            setStatus("已刷新 (" + packs.size() + " 个拓展包, " + buildings.size() + " 个建筑)", 0x55FF55);
            rebuildPackList();
            rebuildInfoPanel();
            rebuildBuildingList();
        });
        btnRefresh.layout(l -> l.flexGrow(1).heightPercent(100));
        buttonRow.addChild(btnRefresh);

        btnClose = new Button().setText("关闭");
        btnClose.setOnClick(e -> {
            // 释放封面纹理
            for (ResourceLocation loc : coverTextures.values()) {
                Minecraft.getInstance().getTextureManager().release(loc);
            }
            coverTextures.clear();
            currentInstance = null;
            Minecraft.getInstance().setScreen(null);
        });
        btnClose.layout(l -> l.flexGrow(1).heightPercent(100));
        buttonRow.addChild(btnClose);

        root.addChild(buttonRow);

        // === 状态行 ===
        statusEl = new TextElement();
        statusEl.setText("");
        statusEl.textStyle(t -> t.textColor(0x55FF55)
            .textAlignHorizontal(Horizontal.CENTER));
        statusEl.layout(l -> l.widthPercent(100).height(12));
        root.addChild(statusEl);

        // === tick handler: 状态消息 + 状态信息更新 ===
        final int[] tickCounter = {0};
        root.addEventListener(UIEvents.TICK, event -> {
            tickCounter[0]++;
            // 状态消息倒计时
            if (statusTick > 0 && statusMessage != null) {
                statusEl.setText(statusMessage);
                statusEl.textStyle(t -> t.textColor(statusColor));
                statusTick--;
                if (statusTick <= 0) statusMessage = null;
            } else if (statusEl != null) {
                statusEl.setText("");
            }
            // 状态信息 (本地/预装) - 每次 tick 刷新 (可能选了不同包)
            updateStatusInfoText(statusInfoEl);
        });

        // 初始数据填充
        rebuildPackList();
        rebuildInfoPanel();
        rebuildBuildingList();
        rebuildAddButton();
        updateButtonStates();

        return ModularUI.of(UI.of(root,
            StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)));
    }

    private static void updateStatusInfoText(TextElement statusInfoEl) {
        if (statusInfoEl == null) return;
        if (currentPackStatus == null) {
            statusInfoEl.setText("");
            return;
        }
        int stateColor;
        switch (currentPackStatus.state) {
            case NOT_ADDED: stateColor = 0xFFDD66; break;
            case ADDED_SAME: stateColor = 0x55FF55; break;
            case ADDED_DIFFERENT: stateColor = 0xFF8855; break;
            default: stateColor = 0xAAAAAA;
        }
        String stateText = currentPackStatus.displayText();
        int localN = currentPackStatus.localBuildings.size();
        int instN = currentPackStatus.installedBuildings.size();
        String text = "§7状态: " + stripColor(stateText) + "\n"
            + "§7本地:" + localN + " 预装:" + instN;
        statusInfoEl.setText(text);
        statusInfoEl.textStyle(t -> t.textColor(stateColor).textWrap(TextWrap.WRAP));
    }

    // === 业务逻辑 (从原类保留) ===

    private static void addSelectedPackToExtension() {
        if (selectedIndex < 0 || selectedIndex >= packs.size()) {
            setStatus("请先选中一个拓展包", 0xFF5555);
            return;
        }
        PackCreator.PackWorkInfo p = packs.get(selectedIndex);
        if (p == null) return;

        try {
            Path result = PackStatusService.addToExtension(p.id);
            if (result != null) {
                setStatus("✓ 已添加 " + p.id + " → " + result, 0x55FF55);
                detectPackStatus();
                rebuildAddButton();
                try {
                    com.prefab.addon.extension.ExtensionPackManager.getInstance().scanExtensionPacks();
                } catch (Throwable t) {
                    PrefabCustomAddon.LOGGER.warn("[CREATOR] ExtensionPackManager 重扫失败", t);
                }
            } else {
                setStatus("✗ 添加失败, 看日志", 0xFF5555);
            }
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[CREATOR] addSelectedPackToExtension 失败", t);
            setStatus("✗ 添加失败: " + t.getMessage(), 0xFF5555);
        }
    }

    private static void openCoverChooser() {
        if (selectedIndex < 0 || selectedIndex >= packs.size()) return;
        PackCreator.PackWorkInfo p = packs.get(selectedIndex);
        setStatus("正在打开文件选择器...", 0x55AAFF);
        SystemFilePicker.openAsync("选择拓展包封面 PNG", "png", r -> {
            if (r.isOk()) {
                handleCoverSelected(p, r.file);
            } else if (r.isCancelled()) {
                setStatus("✗ 已取消", 0x888888);
            } else {
                setStatus("✗ 选择器错误: " + r.message, 0xFF5555);
            }
        });
    }

    private static void handleCoverSelected(PackCreator.PackWorkInfo p, File f) {
        try {
            byte[] data = Files.readAllBytes(f.toPath());
            try (var in = Files.newInputStream(f.toPath())) {
                NativeImage ni = NativeImage.read(in);
                if (ni == null) {
                    setStatus("✗ 无效的 PNG 文件", 0xFF5555);
                    return;
                }
            }
            Path cover = PackCreator.getWorkRoot()
                .resolve(p.id).resolve("information").resolve("cover.png");
            Files.createDirectories(cover.getParent());
            Files.write(cover, data);
            ResourceLocation old = coverTextures.remove(p.id);
            if (old != null) Minecraft.getInstance().getTextureManager().release(old);
            loadedCoverFor = null;
            refreshPacks();
            setStatus("✓ 已保存封面: " + f.getName(), 0x55FF55);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[CREATOR] save cover failed", t);
            setStatus("✗ 保存失败: " + t.getMessage(), 0xFF5555);
        }
    }

    private static void deleteSelectedPack() {
        if (selectedIndex < 0 || selectedIndex >= packs.size()) return;
        PackCreator.PackWorkInfo p = packs.get(selectedIndex);
        try {
            PackCreator.getInstance().deletePack(p.id);
            setStatus("已删除拓展包: " + p.id, 0x55FF55);
            ResourceLocation old = coverTextures.remove(p.id);
            if (old != null) Minecraft.getInstance().getTextureManager().release(old);
            selectedIndex = -1;
            selectedBuildingIndex = -1;
            buildings = new ArrayList<>();
            refreshPacks();
            rebuildPackList();
            rebuildInfoPanel();
            rebuildBuildingList();
            rebuildAddButton();
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.error("[CREATOR] delete pack failed", e);
            setStatus("✗ 删除失败: " + e.getMessage(), 0xFF5555);
        }
    }

    private static void openEditBuilding() {
        if (selectedBuildingIndex < 0 || selectedBuildingIndex >= buildings.size()) return;
        PackCreator.PackWorkInfo p = packs.get(selectedIndex);
        PackCreator.BuildingWorkInfo b = buildings.get(selectedBuildingIndex);
        GuiCreateBuildingInfo.open(p.id, b, currentInstance);
    }

    private static void deleteSelectedBuilding() {
        if (selectedBuildingIndex < 0 || selectedBuildingIndex >= buildings.size()) return;
        PackCreator.PackWorkInfo p = packs.get(selectedIndex);
        PackCreator.BuildingWorkInfo b = buildings.get(selectedBuildingIndex);
        try {
            PackCreator.getInstance().deleteBuilding(p.id, b.id);
            setStatus("已删除建筑: " + b.id, 0x55FF55);
            selectedBuildingIndex = -1;
            loadBuildingsForSelected();
            refreshPacks();
            updateButtonStates();
            rebuildBuildingList();
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.error("[CREATOR] delete building failed", e);
            setStatus("✗ 删除失败: " + e.getMessage(), 0xFF5555);
        }
    }

    private static void openWorkFolder() {
        Path root = PackCreator.getWorkRoot();
        try {
            if (!Files.exists(root)) Files.createDirectories(root);
        } catch (java.io.IOException e) {
            setStatus("创建目录失败: " + e.getMessage(), 0xFF5555);
            return;
        }
        try {
            net.minecraft.Util.getPlatform().openUri(java.net.URI.create(root.toUri().toString()));
            setStatus("已打开: " + root, 0x55FF55);
        } catch (Throwable t1) {
            try {
                java.awt.Desktop.getDesktop().open(root.toFile());
                setStatus("已打开: " + root, 0x55FF55);
            } catch (Throwable t2) {
                setStatus("无法打开目录: " + t2.getMessage(), 0xFF5555);
            }
        }
    }

    // === 自定义 UIElement: 封面图 ===

    /**
     * 在指定尺寸内绘制当前选中拓展包的封面图 (从 coverTextures 中取).
     * tick 时会基于 selectedIndex 刷新 texture.
     */
    private static class CoverImageElement extends UIElement {
        private int tw, th;
        CoverImageElement(int w, int h) {
            this.tw = w;
            this.th = h;
        }
        @Override
        public void drawBackgroundAdditional(GUIContext guiContext) {
            super.drawBackgroundAdditional(guiContext);
            // 用 tick 替代 - 此方法每个 frame 调一次
            ResourceLocation loc = null;
            if (selectedIndex >= 0 && selectedIndex < packs.size()) {
                PackCreator.PackWorkInfo p = packs.get(selectedIndex);
                loc = coverTextures.get(p.id);
            }
            if (loc == null) {
                // 画占位 (灰色)
                int px = (int) getPositionX() + 2, py = (int) getPositionY() + 2;
                int pw = (int) getSizeWidth() - 4, ph = (int) getSizeHeight() - 4;
                guiContext.graphics.fill(px, py, px + pw, py + ph, 0xFF222222);
            } else {
                try {
                    GuiGraphics g = guiContext.graphics;
                    RenderSystem.enableBlend();
                    RenderSystem.defaultBlendFunc();
                    RenderSystem.setShaderColor(1, 1, 1, 1);
                    g.blit(loc, (int) getPositionX(), (int) getPositionY(), 0, 0,
                        (int) getSizeWidth(), (int) getSizeHeight(),
                        (int) getSizeWidth(), (int) getSizeHeight());
                    RenderSystem.setShaderColor(1, 1, 1, 1);
                    RenderSystem.disableBlend();
                } catch (Throwable t) {
                    // 忽略
                }
            }
        }
    }
}
