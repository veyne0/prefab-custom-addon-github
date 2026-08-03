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
import com.lowdragmc.lowdraglib2.gui.ui.elements.ProgressBar;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.ConstructionInfo;
import com.prefab.addon.extension.ExtensionPack;
import com.prefab.addon.extension.ExtensionPackManager;
import com.prefab.addon.network.ServerPackSyncClient;
import com.prefab.addon.work.DependencyChecker;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自定义建筑选择 GUI (LDLib2 版).
 *
 * - 列表里展示所有 Construction
 * - 点击某个 Construction → 打开 GuiConstructionDetail (详细界面)
 * - 在详细界面里点 "Select" 才把 Construction 绑定到蓝图
 *
 * <h2>布局 (460x320)</h2>
 * <pre>
 *   ┌─ 标题 (h=20) ────────────────────┐
 *   │ ┌─ ScrollerView (flexGrow) ─────┐ │
 *   │ │  · item 1                    │ │
 *   │ │  · item 2                    │ │
 *   │ │  · item 3                    │ │
 *   │ └──────────────────────────────┘ │
 *   │  [状态栏 (dep 提示)]              │
 *   │  [同步进度条]                      │
 *   │  [Cancel] [依赖] [Back] [Sync]    │
 *   └─────────────────────────────────┘
 * </pre>
 */
public class GuiCustomStructureSelection {

    private enum View { PACKS, BUILDINGS }

    private View view = View.PACKS;
    private String currentPackId = null;

    private final List<Object> currentItems = new ArrayList<>();

    // 依赖检测: key = construction.getId(), value = missing list
    private final Map<String, DependencyChecker.CheckResult> depResults = new HashMap<>();
    private boolean depCheckedAll = false;
    private int depStatusTick = 0;
    private int depStatusColor = 0x55FF55;

    // UI 引用 (在 createUI 中初始化, tick handler 用)
    private UIElement listContainer;       // ScrollerView 的 viewContainer
    private TextElement statusEl;
    private TextElement progressMsgEl;
    private ProgressBar progressBar;
    private Button btnBack;
    private Button btnCheckDeps;
    private Label titleEl;

    public GuiCustomStructureSelection() {
        ExtensionPackManager.getInstance().reloadIfChanged();
    }

    public static void open() {
        Minecraft.getInstance().setScreen(
            new ModularUIScreen(buildUI(), Component.literal("Custom Structures")));
    }

    /**
     * 从建筑详情返回: 直接进入 BUILDINGS 视图 (上一级), 而不是回到 PACKS 视图 (第一级).
     * 用户的层级是: PACKS → BUILDINGS → DETAIL, Back 应该 BUILDINGS → DETAIL → BUILDINGS (上一级).
     */
    public static void openFromBack(String packId) {
        Minecraft.getInstance().setScreen(
            new ModularUIScreen(buildUIFromBack(packId), Component.literal("Custom Structures")));
    }

    private static ModularUI buildUIFromBack(String packId) {
        GuiCustomStructureSelection self = new GuiCustomStructureSelection();
        // 直接进入 BUILDINGS 视图, 跳到上一级
        if (packId != null && !packId.isEmpty()) {
            self.enterBuildingsView(packId);
        } else {
            self.refreshPacksView();
        }
        return self.createUI();
    }

    private static ModularUI buildUI() {
        GuiCustomStructureSelection self = new GuiCustomStructureSelection();
        return self.createUI();
    }

    private ModularUI createUI() {
        // 根容器
        UIElement root = new UIElement();
        root.layout(l -> l
            .width(460).height(320)
            .flexDirection(FlexDirection.COLUMN)
            .paddingAll(6).gapAll(4)
        );
        root.style(s -> s.background(Sprites.BORDER));
        root.setOverflowVisible(false);

        // 标题
        titleEl = new Label();
        titleEl.setText("选择拓展包");
        titleEl.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        titleEl.layout(l -> l.widthPercent(100).height(20));
        root.addChild(titleEl);

        // 提示行 (左键: 进入拓展包 | 右键: ...)
        TextElement hintEl = new TextElement();
        hintEl.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.select.hint"));
        hintEl.textStyle(t -> t.textColor(0xAAAAAA).textAlignHorizontal(Horizontal.CENTER));
        hintEl.layout(l -> l.widthPercent(100).height(12));
        root.addChild(hintEl);

        // 列表 ScrollerView
        ScrollerView listScroller = new ScrollerView();
        listScroller.scrollerStyle(s -> s
            .mode(ScrollerMode.VERTICAL)
            .verticalScrollDisplay(ScrollDisplay.ALWAYS)
            .horizontalScrollDisplay(ScrollDisplay.NEVER)
        );
        listScroller.verticalScroller(v -> v.setScrollBarSize(8f));
        listScroller.layout(l -> l.widthPercent(100).flexGrow(1));
        root.addChild(listScroller);

        // 拿 listContainer 引用 (以后用 rebuildList 重置内容)
        listContainer = new UIElement();
        listContainer.layout(l -> l
            .flexDirection(FlexDirection.COLUMN)
            .widthPercent(100)
            .gapAll(2)
        );
        listContainer.setId("__list_container__");
        listScroller.addScrollViewChild(listContainer);

        // 状态栏 (dep 提示 / 同步消息)
        statusEl = new TextElement();
        statusEl.setText("");
        statusEl.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        statusEl.layout(l -> l.widthPercent(100).height(14));
        statusEl.setDisplay(false);
        root.addChild(statusEl);

        // 同步进度条 (默认隐藏)
        progressBar = new ProgressBar();
        progressBar.setProgress(0f);
        progressBar.layout(l -> l.widthPercent(100).height(8));
        progressBar.setDisplay(false);
        root.addChild(progressBar);

        progressMsgEl = new TextElement();
        progressMsgEl.setText("");
        progressMsgEl.textStyle(t -> t.textColor(0xFFFFFF));
        progressMsgEl.layout(l -> l.widthPercent(100).height(11));
        progressMsgEl.setDisplay(false);
        root.addChild(progressMsgEl);

        // 按钮行
        UIElement buttonRow = new UIElement();
        buttonRow.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .widthPercent(100).height(24)
            .gapAll(4)
            .justifyContent(AlignContent.CENTER)
        );
        root.addChild(buttonRow);

        Button btnCancel = new Button().setText("Cancel");
        btnCancel.setOnClick(e -> Minecraft.getInstance().setScreen(null));
        btnCancel.layout(l -> l.flexGrow(1).heightPercent(100));
        buttonRow.addChild(btnCancel);

        btnCheckDeps = new Button().setText("🔍 依赖");
        btnCheckDeps.setOnClick(e -> {
            if (this.view == View.BUILDINGS) runDepCheckAll();
        });
        btnCheckDeps.layout(l -> l.flexGrow(1).heightPercent(100));
        buttonRow.addChild(btnCheckDeps);

        btnBack = new Button().setText("← 返回");
        btnBack.setOnClick(e -> refreshPacksView());
        btnBack.layout(l -> l.flexGrow(1).heightPercent(100));
        btnBack.setDisplay(false);
        buttonRow.addChild(btnBack);

        Button btnSync = new Button().setText("⟳ 同步");
        btnSync.setOnClick(e -> ServerPackSyncClient.getInstance().requestResync());
        btnSync.layout(l -> l.flexGrow(1).heightPercent(100));
        buttonRow.addChild(btnSync);

        // tick handler
        root.addEventListener(UIEvents.TICK, event -> {
            updateViewHeader();
            updateProgressBar();
            updateDepStatus();
        });

        // 初始填充列表
        refreshPacksView();

        return ModularUI.of(UI.of(root,
            StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)));
    }

    /** PACKS 视图: 列出所有拓展包 */
    private void refreshPacksView() {
        this.view = View.PACKS;
        this.currentPackId = null;
        this.currentItems.clear();
        for (ExtensionPack p : ExtensionPackManager.getInstance().getDisplayedPacks()) {
            this.currentItems.add(p);
        }
        if (btnBack != null) btnBack.setDisplay(false);
        updateCheckDepsButton();
        rebuildList();
    }

    /** BUILDINGS 视图: 列出当前拓展包的所有建筑 */
    private void enterBuildingsView(String packId) {
        this.view = View.BUILDINGS;
        this.currentPackId = packId;
        this.currentItems.clear();
        ExtensionPack p = findPackById(packId);
        if (p != null) {
            for (ConstructionInfo c : p.getConstructions()) {
                this.currentItems.add(c);
            }
        }
        if (btnBack != null) btnBack.setDisplay(true);
        updateCheckDepsButton();
        rebuildList();
    }

    private ExtensionPack findPackById(String id) {
        if (id == null) return null;
        for (ExtensionPack p : ExtensionPackManager.getInstance().getDisplayedPacks()) {
            if (id.equals(p.getPackageName())) return p;
        }
        return null;
    }

    private void updateCheckDepsButton() {
        if (this.btnCheckDeps == null) return;
        // BUILDINGS 才可用
        // LDLib2 Button 用 isEnabled() 控制
    }

    private void updateViewHeader() {
        if (titleEl == null) return;
        titleEl.setText(this.view == View.PACKS
            ? "选择拓展包"
            : "建筑: " + currentPackName());
    }

    private String currentPackName() {
        ExtensionPack p = findPackById(this.currentPackId);
        if (p == null) return "";
        return p.getName() != null && !p.getName().isEmpty() ? p.getName() : p.getPackageName();
    }

    /** 重建 listContainer 的内容 */
    private void rebuildList() {
        if (listContainer == null) return;
        listContainer.clearAllChildren();
        if (this.currentItems.isEmpty()) {
            TextElement empty = new TextElement();
            empty.setText(this.view == View.PACKS ? "无拓展包" : "该拓展包无建筑");
            empty.textStyle(t -> t.textColor(0xFF5555).textAlignHorizontal(Horizontal.CENTER));
            empty.layout(l -> l.widthPercent(100).height(40)
                .justifyContent(AlignContent.CENTER));
            listContainer.addChild(empty);
            return;
        }
        for (Object item : this.currentItems) {
            listContainer.addChild(createItemRow(item));
        }
    }

    /** 创建一个 item 行 (Button + 文本 + dep marker) */
    private UIElement createItemRow(Object item) {
        UIElement row = new UIElement();
        row.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .widthPercent(100).height(32)
            .gapAll(4)
        );
        row.style(s -> s.background(Sprites.RECT_LIGHT));
        row.setOverflowVisible(false);

        String title;
        String subtitle = null;
        boolean buildable = true;
        if (this.view == View.PACKS && item instanceof ExtensionPack pack) {
            title = (pack.getName() == null || pack.getName().isEmpty()) ? pack.getPackageName() : pack.getName();
            int n = pack.getConstructions() == null ? 0 : pack.getConstructions().size();
            subtitle = "📦 " + pack.getPackageName() + " • " + n + " 建筑";
            buildable = ExtensionPackManager.isBuildable(pack);
        } else if (this.view == View.BUILDINGS && item instanceof ConstructionInfo c) {
            title = c.getName();
            subtitle = "📐 " + c.getAuthor() + " • " + c.getSize();
            buildable = ExtensionPackManager.isBuildable(c);
        } else {
            title = String.valueOf(item);
        }
        final boolean finalBuildable = buildable;
        final String finalTitle = title;
        final String finalSubtitle = subtitle;

        // 主标题 (按钮形式, 点击进入)
        Button mainBtn = new Button();
        mainBtn.setText(finalTitle);
        mainBtn.textStyle(t -> t.textColor(finalBuildable ? 0xFFFFFF : 0x888888)
            .textWrap(TextWrap.NONE));
        mainBtn.layout(l -> l.flexGrow(1).heightPercent(100));
        mainBtn.setOnClick(e -> onItemClicked(item, e.button));
        row.addChild(mainBtn);

        // 副标题 (灰色小字, 显示在主标题下方)
        if (finalSubtitle != null) {
            TextElement sub = new TextElement();
            sub.setText(finalSubtitle);
            sub.textStyle(t -> t.textColor(0xAAAAAA));
            sub.layout(l -> l.width(160).heightPercent(100));
            row.addChild(sub);
        }

        // 依赖状态标记 (仅 BUILDINGS 视图)
        if (this.view == View.BUILDINGS && this.depCheckedAll && item instanceof ConstructionInfo c) {
            DependencyChecker.CheckResult r = this.depResults.get(c.getId());
            String marker;
            int color;
            if (r == null) {
                marker = "·";
                color = 0x888888;
            } else if (r.missing.isEmpty()) {
                marker = "✓";
                color = 0x55FF55;
            } else {
                marker = "✗ " + r.missing.size();
                color = 0xFF5555;
            }
            TextElement markerEl = new TextElement();
            markerEl.setText(marker);
            markerEl.textStyle(t -> t.textColor(color).textAlignHorizontal(Horizontal.CENTER));
            markerEl.layout(l -> l.width(24).heightPercent(100));
            row.addChild(markerEl);
        }

        return row;
    }

    private void onItemClicked(Object item, int mouseButton) {
        if (this.view == View.PACKS) {
            if (item instanceof ExtensionPack pack) {
                // 直接打开第一个建筑的详情 (跳过中间 BUILDINGS 列表层)
                // 用户的层级: PACKS → DETAIL, 用 < > 按钮切换同一 pack 的其他建筑
                if (pack.getConstructions() == null || pack.getConstructions().isEmpty()) {
                    this.depStatusMessage = "✗ 该拓展包无建筑";
                    this.depStatusColor = 0xFF5555;
                    this.depStatusTick = 120;
                    return;
                }
                List<ConstructionInfo> all = pack.getConstructions();
                GuiConstructionDetail.openWithNav(all.get(0), all, 0);
            }
        } else {
            if (item instanceof ConstructionInfo selected) {
                if (mouseButton == 0) {
                    GuiConstructionDetail.openWithNav(selected, this.currentItems instanceof List<?> list
                        ? castToConstructions(list)
                        : null, this.currentItems.indexOf(selected));
                } else {
                    // 右键 = 直接进入建造/预览
                    var player = Minecraft.getInstance().player;
                    var openPos = player != null
                        ? player.blockPosition().above()
                        : net.minecraft.core.BlockPos.ZERO;
                    net.minecraft.world.item.ItemStack blueprint = net.minecraft.world.item.ItemStack.EMPTY;
                    if (player != null) {
                        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                            net.minecraft.world.item.ItemStack s = player.getInventory().getItem(i);
                            if (s.getItem() instanceof com.prefab.addon.items.CustomBlueprintItem) {
                                blueprint = s;
                                break;
                            }
                        }
                    }
                    com.prefab.addon.client.gui.CustomStructureGui.open(selected, blueprint, openPos);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ConstructionInfo> castToConstructions(List<?> list) {
        try {
            return (List<ConstructionInfo>) list;
        } catch (ClassCastException e) {
            return null;
        }
    }

    private void runDepCheckAll() {
        PrefabCustomAddon.LOGGER.info("[SELECT] check deps for all {} constructions (view=BUILDINGS, pack={})",
            this.currentItems.size(), this.currentPackId);
        this.depResults.clear();
        int totalMissing = 0;
        int totalMissingMods = 0;
        for (Object o : this.currentItems) {
            if (!(o instanceof ConstructionInfo c)) continue;
            List<String> deps = c.getDependencies();
            if (deps == null || deps.isEmpty()) continue;
            DependencyChecker.CheckResult r = DependencyChecker.check(deps);
            this.depResults.put(c.getId(), r);
            if (!r.missing.isEmpty()) {
                totalMissing++;
                totalMissingMods += r.missing.size();
            }
        }
        this.depCheckedAll = true;
        if (totalMissing == 0) {
            this.depStatusMessage = "✓ 该拓展包所有建筑依赖均已安装 (" + this.currentItems.size() + " 个)";
            this.depStatusColor = 0x55FF55;
        } else {
            this.depStatusMessage = "✗ " + totalMissing + " 个建筑缺 " + totalMissingMods + " 个 mod";
            this.depStatusColor = 0xFFAA55;
        }
        this.depStatusTick = 200;
        PrefabCustomAddon.LOGGER.info("[SELECT] dep check done: missing={} missingMods={}", totalMissing, totalMissingMods);
        // 重建列表 (显示 marker)
        rebuildList();
    }

    private String depStatusMessage = null;

    private void updateDepStatus() {
        if (statusEl == null) return;
        if (this.depStatusMessage != null && this.depStatusTick > 0) {
            statusEl.setDisplay(true);
            statusEl.setText(Component.literal(this.depStatusMessage)
                .withStyle(s -> s.withColor(this.depStatusColor)));
            this.depStatusTick--;
            if (this.depStatusTick == 0) {
                statusEl.setDisplay(false);
            }
        }
    }

    private void updateProgressBar() {
        if (progressBar == null || progressMsgEl == null) return;
        ServerPackSyncClient sync = ServerPackSyncClient.getInstance();
        ServerPackSyncClient.State sState = sync.getState();
        String syncMsg = sync.getStatusMessage();
        if (sState == ServerPackSyncClient.State.REQUESTING
            || sState == ServerPackSyncClient.State.DOWNLOADING
            || sState == ServerPackSyncClient.State.FINALIZING
            || sState == ServerPackSyncClient.State.CHECKING) {
            progressBar.setDisplay(true);
            progressMsgEl.setDisplay(true);
            long total = sync.getTotalToSync();
            int done = sync.getDoneCount();
            int pct;
            if (sState == ServerPackSyncClient.State.DOWNLOADING && total > 0) {
                pct = Math.min(100, (int) ((done + 1) * 100L / Math.max(1, total)));
            } else {
                pct = 0;
            }
            progressBar.setProgress(pct / 100f);
            progressMsgEl.setText(syncMsg != null ? syncMsg : "");
        } else if (sState == ServerPackSyncClient.State.DONE && syncMsg != null && !syncMsg.isEmpty()) {
            progressBar.setDisplay(false);
            progressMsgEl.setDisplay(true);
            progressMsgEl.setText(syncMsg);
            progressMsgEl.textStyle(t -> t.textColor(0x55FF55));
        } else if (sState == ServerPackSyncClient.State.ERROR && syncMsg != null && !syncMsg.isEmpty()) {
            progressBar.setDisplay(false);
            progressMsgEl.setDisplay(true);
            progressMsgEl.setText(syncMsg);
            progressMsgEl.textStyle(t -> t.textColor(0xFF5555));
        } else {
            progressBar.setDisplay(false);
            progressMsgEl.setDisplay(false);
        }
    }
}
