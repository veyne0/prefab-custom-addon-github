package com.prefab.addon.client.gui;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.config.BuildAnimationMode;
import com.prefab.addon.config.CategoryManager;
import com.prefab.addon.config.PlayerPreferences;
import com.prefab.addon.extension.ConstructionInfo;
import com.prefab.addon.extension.LocalBuilding;
import com.prefab.addon.extension.LocalBuildingScanner;
import com.prefab.addon.work.FolderOpener;
import com.prefab.addon.work.PackCreator;
import com.prefab.gui.GuiBase;
import com.prefab.gui.controls.ExtendedButton;
import com.prefab.addon.integration.KubeJSIntegration;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.awt.Desktop;
import java.awt.FileDialog;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * X 键打开的独立 GUI (浏览器样式).
 *
 * 不动现有的"建筑选择界面" GuiExtensionPackBrowser (6 tab: 建筑/云端/服务器/收藏/下载/原版).
 * 本 GUI 是 X 键专用, 只有 2 个 tab: 创建建筑 / 编辑建筑.
 *   - "创建建筑" tab: 在白色背景上用 GuiGraphics 绘制 EditBox + 按钮.
 *   - "编辑建筑" tab: 从 prefab-extension/ 扫描三件套建筑, 以卡片网格显示, 每张卡片有"查看"和"编辑"按钮.
 *
 * 复用 GuiCreateBuildingInfo 的业务逻辑 (doSave / doDelete / openNbtPicker / 等),
 * 表单字段值在 GuiCreateBuildingInfo static 字段上传递 (package-private 访问).
 */
public class GuiExtensionPackEditor extends GuiBase {

    // === 布局常量 (与 Browser 一致) ===
    private static final int PANEL_W = 400;
    private static final int PANEL_H = 240;
    private static final int TABS_W = 72;        // 左侧 tab 宽度
    private static final int SEARCH_H = 18;      // 顶部标题栏高
    /** Tab 名称的最大宽度 - 中文 2 字节字符, 1 字符约 6px. */
    private static final int TAB_H = 24;

    // === 颜色 (与 Browser 一致) ===
    private static final int PANEL_BG = 0xFF1A1A1A;       // 主面板深灰
    private static final int PANEL_BORDER = 0xFF555555;   // 边框
    private static final int TAB_BG_HOVER = 0xFF2A4A6A;   // tab hover
    private static final int TAB_BG_ACTIVE = 0xFF3A6AAA;  // tab 选中 (蓝)
    private static final int TAB_FG = 0xFFFFFFFF;         // tab 文字
    private static final int CONTENT_BG = 0xFFFFFFFF;     // 右内容区纯白
    private static final int LABEL_COLOR = 0xFF202020;     // 字段标签 (深色, 配白底)
    private static final int TITLE_COLOR = 0xFF333333;     // 标题
    private static final int HINT_COLOR = 0xFF707070;      // 提示
    private static final int BTN_BG = 0xFFE8E8E8;          // 按钮底色
    private static final int BTN_BG_HOVER = 0xFFC8D8E8;    // 按钮 hover
    private static final int BTN_BG_PRIMARY = 0xFF6A8FB5;  // 主按钮
    private static final int BTN_BG_DANGER = 0xFFB56A6A;   // 危险按钮
    private static final int CARD_BG = 0xFFE0E0E0;        // 卡片底色
    private static final int CARD_BORDER = 0xFFAAAAAA;    // 卡片边框
    private static final int CARD_HOVER = 0xFFC8D8E8;      // 卡片 hover

    // === 4 个 Tab ===
    public enum Tab {
        CREATE("创建建筑"),
        EDIT("编辑建筑"),
        SETTINGS("设置"),
        MAKE_BLUEPRINT("制作蓝图");
        final String label;
        Tab(String label) { this.label = label; }
    }
    private Tab currentTab = Tab.CREATE;

    // === 面板位置 (与 Browser 一致) ===
    private int grayBoxX, grayBoxY;

    // === "创建建筑" tab 的 EditBox 和按钮 ===
    private EditBox edId, edName, edAuthor, edDeps, edDesc, edSize;
    /** 只读 EditBox: 显示当前已选的 NBT 建筑文件路径 / 选区信息. */
    private EditBox edNbtFile;
    /** 只读 EditBox: 显示当前已选的图标文件名 (玩家按"选图标"按钮选完后回填). */
    private EditBox edIconFile;
    /** 只读 EditBox: 显示当前选中的分类. 旁边 3 个按钮: ‹ 上一个 / › 下一个 / + 管理分类. */
    private EditBox edCategory;
    private ExtendedButton btnSelectNbt, btnInGame, btnIcon, btnSave, btnReset, btnDelete;
    private ExtendedButton btnSelectNbtPrimary;
    private ExtendedButton btnCatPrev, btnCatNext, btnCatAdd;
    /** EditBox 状态: 用来在切到创建 tab 时从 GuiCreateBuildingInfo 同步. */
    private boolean createFormInited = false;
    /** 创建 tab 第一行按钮的 Y 坐标, 状态消息绘制在该行之上 (避免与按钮重叠). */
    private int createBtn1Y = 0;
    /** 创建 tab 状态消息占位行的 Y 坐标. 0 = 未初始化. */
    private int createStatusY = 0;

    // === "编辑建筑" tab 的数据 ===
    private List<LocalBuilding> editBuildings = new ArrayList<>();
    /** 搜索过滤后的建筑 (派生自 editBuildings, 跟随搜索框刷新). */
    private List<LocalBuilding> editFiltered = new ArrayList<>();
    /** 搜索关键字 (小写). 空 = 不过滤. */
    private String editSearch = "";
    /** 当前页 (1-based). */
    private int editPage = 1;
    /** 每页卡片数 (2 列 × 实际行数, 算出来). */
    private int editPageSize = 8;
    /** 搜索框 EditBox. */
    private EditBox edSearch;
    /** 搜索框矩形: 用于点击聚焦 (渲染时也算) */
    private int[] edSearchRect = new int[]{0, 0, 0, 0};
    /** 卡片矩形 (grayBoxX, grayBoxY, cardX, cardY, cardW, cardH) 列表, 用于点击检测. */
    private List<int[]> editCardRects = new ArrayList<>();
    /** "查看" 按钮矩形列表 (每张卡一个) -> 与 editCardRects 索引对应. */
    private List<int[]> editViewBtnRects = new ArrayList<>();
    /** "编辑" 按钮矩形列表. */
    private List<int[]> editEditBtnRects = new ArrayList<>();
    /** 翻页按钮矩形: [prevX, prevY, prevW, prevH, nextX, nextY, nextW, nextH]. */
    private int[] editPagingRects = new int[]{0, 0, 0, 0, 0, 0, 0, 0};

    // === "设置" tab 状态 ===
    /** 设置页控件矩形缓存. */
    // settingsToggleRect[i] = 第 i 个 toggle 行的 [x, y, w, h].
    // 索引: 0=挑战模式, 1=自动检测依赖, 2=强制启用 KubeJS 联动.
    private int[][] settingsToggleRect = new int[][]{
        {0,0,0,0}, // 0: 挑战模式
        {0,0,0,0}, // 1: 打开建筑时自动检测依赖
        {0,0,0,0}, // 2: 强制启用 KubeJS 联动 (调试用)
    };
    /** 建造动画 mode 切换按钮的 [x, y, w, h]. 点击循环切换 OFF→FALL→RAIN→THROW→OFF. */
    private int[] settingsModeCycleRect = new int[]{0, 0, 0, 0};
    private int[] settingsOpenFolderRect = new int[]{0, 0, 0, 0};
    private int[] settingsSyncServerRect = new int[]{0, 0, 0, 0};
    private int[] settingsResetRect = new int[]{0, 0, 0, 0};
    private int[] settingsDoneRect = new int[]{0, 0, 0, 0};
    /** 设置 tab 当前页 (1=挑战+性能/动画, 2=快捷操作). */
    private int settingsPage = 1;
    /** 设置 tab 翻页按钮矩形: [prevX, prevY, prevW, prevH, nextX, nextY, nextW, nextH]. */
    private int[] settingsPagingRects = new int[]{0, 0, 0, 0, 0, 0, 0, 0};
    private int settingsStatusTick = 0;
    private String settingsStatus = null;
    private int settingsStatusColor = 0x55FF55;

    // === "制作蓝图" tab 的字段 ===
    private EditBox edBlueprintName;       // 显示名 (玩家填的)
    // 命名空间 / 物品 id 都不再让玩家填 — 简化:
    //   - 命名空间固定 player_pack
    //   - 物品 id 固定 player_blueprint, 已存在则自动加序号 (_2, _3, ...)
    //   之前 3 个输入框太繁琐, 玩家根本不在意 ns / id, 只关心显示名.
    // private EditBox edBlueprintNamespace;  // 隐藏
    // private EditBox edBlueprintItemId;     // 隐藏
    /** 选中的建筑 (NBT 来源, 单文件建筑或 zip 包内的). null = 没选. */
    private com.prefab.addon.extension.LocalBuilding selectedBuilding;
    /** 选中的贴图源文件 (玩家电脑上的 .png). null = 没选. */
    private java.nio.file.Path selectedTextureSource;
    /** 9 槽配方: 9 个 null-or-itemId. */
    private final java.util.List<String> recipeItems = new java.util.ArrayList<>(java.util.Arrays.asList(null, null, null, null, null, null, null, null, null));
    /** 锁定 (默认 true, 蓝图一旦生成不可 rebind). */
    private boolean blueprintLocked = true;
    /** 控件矩形缓存. */
    private int[] mbSelectBuildingFileRect = new int[]{0,0,0,0};  // 选外部 NBT 文件
    private int[] mbSelectInGameRect = new int[]{0,0,0,0};        // 游戏内选区
    private int[] mbSelectTextureRect = new int[]{0,0,0,0};
    private int[] mbLockedRect = new int[]{0,0,0,0};
    private int[] mbRecipeGridRect = new int[]{0,0,0,0};
    private int[] mbGenerateRect = new int[]{0,0,0,0};
    private int[] mbOpenFolderRect = new int[]{0,0,0,0};
    /** 滚动条槽 [x,y,w,h] + 拇指 [x,y,w,h] (绘制在 drawMakeBlueprintTab 里). */
    private int[] mbScrollBarRect = new int[]{0,0,0,0};
    private int[] mbScrollThumbRect = new int[]{0,0,0,0};
    /** 拖动滚动条的状态. */
    private boolean mbScrollDragging = false;
    private int mbScrollDragOffset = 0;
    /** 当前滚动偏移 (像素). */
    private int mbScroll = 0;
    /** 本帧计算的内容总高度, 用于算 maxScroll. */
    private int mbContentHeight = 0;
    /** tick() 轮询 GuiCreateBuildingInfo.nbtData 时的来源 (外部 NBT 还是游戏内). */
    private int mbPollSource = 0; // 0=none 1=external 2=ingame
    /** 当前正在编辑的配方格 idx (-1 = 没编辑), 用于在点开小搜索框后回填. */
    private int mbEditingCell = -1;
    /** 状态消息 (this tab 自己的, 区别于全局 statusMessage). */
    private String mbStatus = null;
    private int mbStatusTick = 0;
    private int mbStatusColor = 0x55FF55;

    /** 状态消息 (底部状态栏). */
    private String statusMessage = null;
    private int statusColor = 0x55FF55;
    private int statusTick = 0;

    public GuiExtensionPackEditor() {
        super("Extension Editor");
        this.modifiedInitialXAxis = PANEL_W / 2;
        this.modifiedInitialYAxis = PANEL_H / 2;
    }

    /** 用指定初始 tab 打开 (供 O 键直接跳到设置 tab 用). */
    public static void open(Tab initialTab) {
        GuiExtensionPackEditor gui = new GuiExtensionPackEditor();
        gui.currentTab = initialTab;
        Minecraft.getInstance().setScreen(gui);
    }

    @Override
    protected void Initialize() {
        super.Initialize();
        int[] pos = computePanelPos();
        this.grayBoxX = pos[0];
        this.grayBoxY = pos[1];

        // === KubeJS 联动: GUI 打开时同步 forceEnabled + 刷新一次检测 ===
        // 万一之前的缓存错了, 这里纠正.
        try {
            com.prefab.addon.integration.KubeJSIntegration.setForceEnabled(
                PlayerPreferences.get().forceKubeJSTab);
            com.prefab.addon.integration.KubeJSIntegration.recheck();
            PrefabCustomAddon.LOGGER.info(
                "[MAKE-BLUEPRINT] GUI 初始化: KubeJS={}, forceEnabled={}, 当前 tab={}",
                com.prefab.addon.integration.KubeJSIntegration.isLoaded(),
                com.prefab.addon.integration.KubeJSIntegration.isForceEnabled(),
                this.currentTab);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[MAKE-BLUEPRINT] KubeJS 状态同步失败: {}", t.getMessage());
        }

        // 创建 tab 的控件 (始终在 widget 列表里, 切 tab 时切可见性)
        initCreateTabWidgets();

        // 编辑 tab 的搜索框 (始终在 widget 列表里, 切 tab 时切可见性)
        initEditTabWidgets();

        // 制作蓝图 tab 的 EditBox
        initMakeBlueprintWidgets();

        // 加载编辑 tab 数据
        refreshEditTab();

        // 初始化时按当前 tab 一次性刷一次 widget 可见性.
        // 否则 default currentTab=CREATE 时, EDIT/MAKE_BLUEPRINT 的 widget 仍 visible
        // (只有 MAKE_BLUEPRINT 的 EditBox 在 init 时显式 setVisible(false), 其他都没设),
        // 切到 MAKE_BLUEPRINT tab 后会跟 CREATE 的按钮 / EditBox 叠加渲染.
        applyTabVisibility();
    }

    /** 根据当前 currentTab 刷新所有 tab 的 widget 可见性. switchTab 和 Initialize 末尾共用. */
    private void applyTabVisibility() {
        setCreateTabVisible(this.currentTab == Tab.CREATE);
        setEditTabVisible(this.currentTab == Tab.EDIT);
        setMakeBlueprintTabVisible(this.currentTab == Tab.MAKE_BLUEPRINT);
    }

    /** 制作蓝图 tab 的 EditBox (始终在 widget 列表, 通过 setVisible 切). */
    private void initMakeBlueprintWidgets() {
        // 内容区在 computePanelPos / contentRect 里, 但 tab 内容具体位置在 drawMakeBlueprintTab 里.
        // 这里只创建 EditBox, 设默认可见性 false (切到 tab 时再显示).
        edBlueprintName = new EditBox(this.font, 0, 0, 120, 14, Component.literal(""));
        edBlueprintName.setMaxLength(64);
        edBlueprintName.setValue("我的蓝图");
        addRenderableWidget(edBlueprintName);
        edBlueprintName.setVisible(false);
        // 命名空间 / 物品 id 不再让玩家填 (固定 player_pack + player_blueprint + 序号)
        // 它们的引用也都从代码里移除, 避免误用
    }

    /** 编辑 tab 的搜索框 (在右内容区顶部). 始终 addRenderableWidget, 通过 setVisible 切换. */
    private void initEditTabWidgets() {
        int[] r = contentRect();
        int rx = r[0], ry = r[1], rw = r[2];
        int searchX = rx + 6;
        int searchY = ry + 32;  // 标题 + hint 之下
        int searchW = rw - 12;
        int searchH = 14;
        edSearchRect = new int[]{searchX, searchY, searchW, searchH};
        edSearch = new EditBox(this.font, searchX, searchY, searchW, searchH, Component.literal(""));
        edSearch.setMaxLength(64);
        edSearch.setValue(this.editSearch);
        edSearch.setResponder(s -> {
            this.editSearch = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
            // 搜索变化时重置到第 1 页, 重新过滤
            this.editPage = 1;
            rebuildEditFiltered();
        });
        edSearch.setVisible(false);  // 默认不在 create tab 显示
        this.addRenderableWidget(edSearch);
    }

    /** 根据 editSearch 过滤 editBuildings → editFiltered. */
    private void rebuildEditFiltered() {
        this.editFiltered.clear();
        if (this.editSearch.isEmpty()) {
            this.editFiltered.addAll(this.editBuildings);
        } else {
            String q = this.editSearch;
            for (LocalBuilding lb : this.editBuildings) {
                if (matchesSearch(lb, q)) this.editFiltered.add(lb);
            }
        }
        if (this.editPage < 1) this.editPage = 1;
        int totalPages = Math.max(1, (this.editFiltered.size() + this.editPageSize - 1) / this.editPageSize);
        if (this.editPage > totalPages) this.editPage = totalPages;
    }

    private static boolean matchesSearch(LocalBuilding lb, String q) {
        if (lb == null || q == null || q.isEmpty()) return true;
        if (lb.id != null && lb.id.toLowerCase(Locale.ROOT).contains(q)) return true;
        if (lb.name != null && lb.name.toLowerCase(Locale.ROOT).contains(q)) return true;
        if (lb.author != null && lb.author.toLowerCase(Locale.ROOT).contains(q)) return true;
        if (lb.description != null && lb.description.toLowerCase(Locale.ROOT).contains(q)) return true;
        return false;
    }

    private int[] computePanelPos() {
        int x = (this.width / 2) - this.modifiedInitialXAxis;
        int y = (this.height / 2) - this.modifiedInitialYAxis;
        if (x < 4) x = 4;
        if (y < 4) y = 4;
        if (x + PANEL_W > this.width - 4) x = Math.max(4, this.width - PANEL_W - 4);
        if (y + PANEL_H > this.height - 4) y = Math.max(4, this.height - PANEL_H - 4);
        return new int[]{x, y};
    }

    /** 右内容区. */
    private int[] contentRect() {
        int x = grayBoxX + TABS_W + 4 + 1;
        int y = grayBoxY + 1;
        int w = PANEL_W - TABS_W - 8 - 2;
        int h = PANEL_H - 2;
        return new int[]{x, y, w, h};
    }

    // ============================================================
    // 创建 tab 控件初始化
    // ============================================================
    private void initCreateTabWidgets() {
        int[] r = contentRect();
        int rx = r[0], ry = r[1], rw = r[2], rh = r[3];

        int labelW = 40;
        int fieldX = rx + labelW + 2;
        int fieldW = rw - labelW - 8;
        int fieldH = 16;
        int rowH = 20;
        int y = ry + 18;  // 跳过标题

        edId     = makeField(fieldX, y, fieldW, fieldH, () -> com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldIdValue,     s -> com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldIdValue = s); y += rowH;
        edName   = makeField(fieldX, y, fieldW, fieldH, () -> com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldNameValue,   s -> com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldNameValue = s); y += rowH;
        edAuthor = makeField(fieldX, y, fieldW, fieldH, () -> com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldAuthorValue, s -> com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldAuthorValue = s); y += rowH;
        edDeps   = makeField(fieldX, y, fieldW, fieldH, () -> com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldDepsValue,   s -> com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldDepsValue = s); y += rowH;
        // 尺寸 (只读, 选完 NBT/选区后自动回填)
        edSize   = new EditBox(this.font, fieldX, y, fieldW, fieldH, Component.literal(""));
        edSize.setMaxLength(64);
        edSize.setValue(safe(com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldSizeValue));
        edSize.setEditable(false);
        this.addRenderableWidget(edSize);
        y += rowH;
        // 描述 (高度 24, 给底部状态条 / 按钮让出空间)
        edDesc   = makeField(fieldX, y, fieldW, 24,       () -> com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldDescValue,   s -> com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldDescValue = s);
        edDesc.setMaxLength(256);
        y += 28;

        // === 分类行: 只读显示 + ‹ › + 按钮 ===
        // 跟 NBT / 图标行 同样的高度 (12px), 总宽 = fieldW (跟其它 EditBox 对齐)
        int catH = 12;
        int catBtnW = 14;             // ‹ / › 按钮
        int catAddBtnW = 28;          // "管理" 按钮 (实际 28 让它能装下"管理"两个字)
        int catDisplayW = fieldW - catBtnW * 2 - catAddBtnW - 2;  // 减 2 留缝
        int catX = fieldX;
        int catY = y;

        edCategory = new EditBox(this.font, catX, catY, catDisplayW, catH, Component.literal(""));
        edCategory.setMaxLength(64);
        edCategory.setEditable(false);
        edCategory.setValue(CategoryManager.UNCATEGORIZED);
        this.addRenderableWidget(edCategory);

        btnCatPrev = makeButton(catX + catDisplayW, catY, catBtnW, catH, "‹", () -> {
            java.util.List<String> all = CategoryManager.get().getCategories();
            int idx = indexOfCategory(all, GuiCreateBuildingInfo.fieldCategoryValue);
            int next = (idx <= 0) ? all.size() - 1 : idx - 1;
            GuiCreateBuildingInfo.fieldCategoryValue = all.get(next);
            refreshCategoryDisplay();
        });
        btnCatNext = makeButton(catX + catDisplayW + catBtnW, catY, catBtnW, catH, "›", () -> {
            java.util.List<String> all = CategoryManager.get().getCategories();
            int idx = indexOfCategory(all, GuiCreateBuildingInfo.fieldCategoryValue);
            int next = (idx < 0 || idx >= all.size() - 1) ? 0 : idx + 1;
            GuiCreateBuildingInfo.fieldCategoryValue = all.get(next);
            refreshCategoryDisplay();
        });
        btnCatAdd = makeButton(catX + catDisplayW + catBtnW * 2, catY, catAddBtnW, catH, "管理", () -> {
            // 弹独立分类管理界面, 关掉时刷新当前显示
            GuiCategoryManager.openStandalone();
            refreshCategoryDisplay();
        });
        y += catH + 2;

        // 已选建筑文件 (只读)
        int nbtFieldH = 12;
        edNbtFile = new EditBox(this.font, fieldX, y, fieldW, nbtFieldH, Component.literal(""));
        edNbtFile.setMaxLength(512);
        edNbtFile.setValue("");  // tick() 里从 GuiCreateBuildingInfo.nbtPath 同步
        edNbtFile.setEditable(false);
        this.addRenderableWidget(edNbtFile);
        y += nbtFieldH + 2;

        // 已选图标 (只读) - 显示选完图标后回填的文件名
        int iconFieldH = 12;
        edIconFile = new EditBox(this.font, fieldX, y, fieldW, iconFieldH, Component.literal(""));
        edIconFile.setMaxLength(256);
        edIconFile.setValue("");  // tick() 里从 GuiCreateBuildingInfo.fieldIconPath 同步
        edIconFile.setEditable(false);
        this.addRenderableWidget(edIconFile);
        y += iconFieldH + 4;

        // 状态消息占位行 (文字高度 8px, 上下各留 2px)
        this.createStatusY = y;
        y += 12;

        // 按钮行 1: [选 NBT] [游戏中选区] [选图标]
        int btn1Y = y;
        this.createBtn1Y = btn1Y;
        int btnH = 14;
        int gap = 4;
        int btn1W = (rw - 16 - gap * 2) / 3;
        int btnX = rx + 8;
        btnSelectNbt = makeButton(btnX, btn1Y, btn1W, btnH, "选 NBT", () -> {
            com.prefab.addon.client.gui.GuiCreateBuildingInfo.openNbtPickerFromExtension();
            refreshNbtFileDisplay();
        });
        btnInGame    = makeButton(btnX + btn1W + gap, btn1Y, btn1W, btnH, "游戏中选区", () -> startInGameSelectionForEditor());
        btnIcon      = makeButton(btnX + (btn1W + gap) * 2, btn1Y, btn1W, btnH, "选图标", () -> {
            com.prefab.addon.client.gui.GuiCreateBuildingInfo.openIconPickerFromExtension();
        });
        y += btnH + 4;

        // 按钮行 2: [保存并创建] [重置] [删除] - 居中
        int btn2W = 70;
        int totalW = btn2W * 3 + gap * 2;
        int btn2X = rx + (rw - totalW) / 2;
        btnSave   = makeButton(btn2X, y, btn2W, btnH, "保存并创建", this::onSaveAndCreate);
        btnReset  = makeButton(btn2X + btn2W + gap, y, btn2W, btnH, "重置", () -> {
            com.prefab.addon.client.gui.GuiCreateBuildingInfo.resetForReuse();
            syncFromCb();
            refreshNbtFileDisplay();
        });
        btnDelete = makeButton(btn2X + (btn2W + gap) * 2, y, btn2W, btnH, "删除", () -> {
            String id = com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldIdValue.trim();
            if (!id.isEmpty()) com.prefab.addon.client.gui.GuiCreateBuildingInfo.doDelete(null, id);
            copyStatusFromCb();
        });

        // 默认显示创建 tab
        setCreateTabVisible(true);
        setEditTabVisible(false);
        // 同步一次 nbt 路径显示
        refreshNbtFileDisplay();
    }

    /**
     * "保存并创建" 按钮处理:
     * 1) 调 GuiCreateBuildingInfo.doSave() (用 GuiCreateBuildingInfo static 字段)
     * 2) 检查保存结果: 如果 statusMessage 包含"成功", 在聊天栏发送"创建成功"提示
     * 3) 在 Editor 底部状态栏显示同样的提示
     */
    private void onSaveAndCreate() {
        com.prefab.addon.client.gui.GuiCreateBuildingInfo.doSave();
        copyStatusFromCb();
        // 成功时额外发聊天消息 + 重置表单
        if (this.statusMessage != null
            && (this.statusMessage.contains("成功") || this.statusMessage.contains("已保存"))) {
            String id = com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldIdValue.trim();
            String name = com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldNameValue.trim();
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.sendSystemMessage(
                    net.minecraft.network.chat.Component.literal(
                        "§a[创建成功] §7建筑 §f" + (name.isEmpty() ? id : name) + " §7已保存到 prefab-extension/"));
            }
            // 重置表单, 方便用户继续创建下一个
            com.prefab.addon.client.gui.GuiCreateBuildingInfo.resetForReuse();
            syncFromCb();
            refreshNbtFileDisplay();
        }
    }

    /**
     * 游戏中选区 (重写 RegionSelector 流程, 选区完成后 setScreen 回 Editor 而不是老 LDLib2 界面).
     * 不调 GuiCreateBuildingInfo.startInWorldPicking().
     */
    private void startInGameSelectionForEditor() {
        // 1) 缓存现场
        final String savedId = com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldIdValue;
        final String savedName = com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldNameValue;
        final String savedAuthor = com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldAuthorValue;
        final String savedDeps = com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldDepsValue;
        final String savedDesc = com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldDescValue;

        // 2) 关闭当前 Screen
        Minecraft.getInstance().setScreen(null);

        // 3) 启动选区
        com.prefab.addon.work.RegionSelector.start(
            Minecraft.getInstance().player,
            new com.prefab.addon.work.RegionSelector.OnCompleted() {
                @Override
                public void onCompleted(byte[] newNbtData, java.nio.file.Path nbtFile,
                                        String sizeString, java.util.List<String> modIds) {
                    Minecraft.getInstance().execute(() -> {
                        // 写 NBT 到 static 字段
                        com.prefab.addon.client.gui.GuiCreateBuildingInfo.nbtData = newNbtData;
                        com.prefab.addon.client.gui.GuiCreateBuildingInfo.nbtPath = nbtFile == null
                            ? "游戏中选区 (临时)"
                            : nbtFile.toAbsolutePath().toString();
                        // 解析 NBT 信息, 写 size / deps
                        try {
                            com.prefab.addon.work.NbtStructureParser.NbtInfo info =
                                com.prefab.addon.work.NbtStructureParser.parse(newNbtData);
                            com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldSizeValue = info.sizeString();
                            com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldDepsValue =
                                modIds == null || modIds.isEmpty() ? "prefab" : String.join(",", modIds);
                        } catch (Exception ex) {
                            com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldSizeValue = "";
                        }
                        // 恢复用户已填字段
                        com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldIdValue = savedId;
                        com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldNameValue = savedName;
                        com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldAuthorValue = savedAuthor;
                        com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldDepsValue =
                            savedDeps.isEmpty() ? com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldDepsValue : savedDeps;
                        com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldDescValue = savedDesc;
                        // 重开 Editor (不是老 LDLib2 窗!)
                        Minecraft.getInstance().setScreen(new GuiExtensionPackEditor());
                    });
                }
                @Override
                public void onCancelled() {
                    Minecraft.getInstance().execute(() -> {
                        Minecraft.getInstance().setScreen(new GuiExtensionPackEditor());
                    });
                }
            });
    }

    /** tick() 时调: 同步 nbtPath → edNbtFile, 选完 NBT 后会自动显示文件路径. */
    private void refreshNbtFileDisplay() {
        if (edNbtFile != null) {
            String path = com.prefab.addon.client.gui.GuiCreateBuildingInfo.nbtPath;
            if (path == null || path.isEmpty()) {
                edNbtFile.setValue("未选建筑文件");
            } else {
                int lastSep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
                String display = lastSep >= 0 ? path.substring(lastSep + 1) : path;
                edNbtFile.setValue("已选 NBT: " + display);
            }
        }
        // 同步尺寸 (选完 NBT/选区后由 GuiCreateBuildingInfo 写入 fieldSizeValue)
        if (edSize != null) {
            edSize.setValue(safe(com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldSizeValue));
        }
        if (edIconFile != null) {
            String iconPath = com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldIconPath;
            String iconData = "";
            try {
                java.lang.reflect.Field f = com.prefab.addon.client.gui.GuiCreateBuildingInfo.class.getDeclaredField("fieldIconData");
                f.setAccessible(true);
                byte[] data = (byte[]) f.get(null);
                if (data != null && data.length > 0) iconData = " (有数据 " + data.length + " 字节)";
            } catch (Throwable ignored) {}
            if (iconPath == null || iconPath.isEmpty()
                || iconPath.equals(com.prefab.addon.PrefabCustomAddon.tr("gui.create_building.empty_value"))) {
                edIconFile.setValue("未选图标" + iconData);
            } else {
                edIconFile.setValue("已选图标: " + iconPath + iconData);
            }
        }
    }

    private EditBox makeField(int x, int y, int w, int h, java.util.function.Supplier<String> getter, java.util.function.Consumer<String> setter) {
        EditBox box = new EditBox(this.font, x, y, w, h, Component.literal(""));
        box.setMaxLength(128);
        box.setValue(getter.get() == null ? "" : getter.get());
        box.setResponder(setter::accept);
        this.addRenderableWidget(box);
        return box;
    }

    private ExtendedButton makeButton(int x, int y, int w, int h, String label, Runnable onClick) {
        ExtendedButton btn = new ExtendedButton(x, y, w, h, Component.literal(label), b -> onClick.run(), label);
        this.addRenderableWidget(btn);
        return btn;
    }

    /** 从 GuiCreateBuildingInfo 同步字段 → EditBox. */
    private void syncFromCb() {
        if (edId != null)     edId.setValue(safe(com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldIdValue));
        if (edName != null)   edName.setValue(safe(com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldNameValue));
        if (edAuthor != null) edAuthor.setValue(safe(com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldAuthorValue));
        if (edDeps != null)   edDeps.setValue(safe(com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldDepsValue));
        if (edSize != null)   edSize.setValue(safe(com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldSizeValue));
        if (edDesc != null)   edDesc.setValue(safe(com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldDescValue));
        refreshCategoryDisplay();
    }

    /** 刷新分类显示 (从 fieldCategoryValue 读, 写到 edCategory). 分类列表变化时也调. */
    private void refreshCategoryDisplay() {
        if (edCategory == null) return;
        String v = com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldCategoryValue;
        if (v == null || v.isEmpty()) v = CategoryManager.UNCATEGORIZED;
        edCategory.setValue(v);
    }

    /** 找 value 在 all 里的 index, 找不到返回 -1. */
    private static int indexOfCategory(java.util.List<String> all, String value) {
        if (all == null || value == null) return -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).equalsIgnoreCase(value)) return i;
        }
        return -1;
    }

    private void copyStatusFromCb() {
        try {
            java.lang.reflect.Field f = com.prefab.addon.client.gui.GuiCreateBuildingInfo.class.getDeclaredField("statusMessage");
            f.setAccessible(true);
            this.statusMessage = (String) f.get(null);
            java.lang.reflect.Field cf = com.prefab.addon.client.gui.GuiCreateBuildingInfo.class.getDeclaredField("statusColor");
            cf.setAccessible(true);
            this.statusColor = cf.getInt(null);
            this.statusTick = 120;
        } catch (Throwable ignored) {}
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private void setCreateTabVisible(boolean v) {
        if (edId != null)     edId.setVisible(v);
        if (edName != null)   edName.setVisible(v);
        if (edAuthor != null) edAuthor.setVisible(v);
        if (edDeps != null)   edDeps.setVisible(v);
        if (edSize != null)   edSize.setVisible(v);
        if (edDesc != null)   edDesc.setVisible(v);
        if (edCategory != null) edCategory.setVisible(v);
        if (btnCatPrev != null) btnCatPrev.visible = v;
        if (btnCatNext != null) btnCatNext.visible = v;
        if (btnCatAdd != null)  btnCatAdd.visible = v;
        if (edNbtFile != null) edNbtFile.setVisible(v);
        if (edIconFile != null) edIconFile.setVisible(v);
        if (btnSelectNbt != null) btnSelectNbt.visible = v;
        if (btnInGame != null)    btnInGame.visible = v;
        if (btnIcon != null)      btnIcon.visible = v;
        if (btnSave != null)      btnSave.visible = v;
        if (btnReset != null)     btnReset.visible = v;
        if (btnDelete != null)    btnDelete.visible = v;
    }

    // ============================================================
    // 编辑 tab
    // ============================================================
    private void refreshEditTab() {
        this.editBuildings.clear();
        try {
            // 扫 prefab-extension/ + prefab-download/ + 老 prefab-work/<packId>/construction/
            // (兼容老拓展包; 同 id 时 prefab-extension 优先)
            List<LocalBuilding> all = LocalBuildingScanner.scanAll();
            this.editBuildings.addAll(all);
        } catch (Exception ex) {
            PrefabCustomAddon.LOGGER.error("[EDITOR] 扫描 LocalBuilding 失败: {}", ex.getMessage());
        }
        this.editPage = 1;
        rebuildEditFiltered();
    }

    private void setEditTabVisible(boolean v) {
        // 编辑 tab 用 GuiGraphics 自绘, 不依赖 widget; 搜索框的可见性单独切
        if (this.edSearch != null) this.edSearch.setVisible(v);
    }

    private void switchTab(Tab tab) {
        if (tab == this.currentTab) return;
        this.currentTab = tab;
        // 切到编辑 tab 时重新扫描 + 重新过滤
        if (tab == Tab.EDIT) {
            refreshEditTab();
        }
        applyTabVisibility();
    }

    /** 设置"制作蓝图" tab 的 EditBox 可见性. */
    private void setMakeBlueprintTabVisible(boolean visible) {
        if (edBlueprintName != null) edBlueprintName.setVisible(visible);
        // edBlueprintNamespace / edBlueprintItemId 已移除 (玩家只填显示名, ns+id 后台自动生成)
    }

    // ============================================================
    // 渲染
    // ============================================================
    @Override
    public void tick() {
        super.tick();
        if (this.statusTick > 0) this.statusTick--;
        if (this.settingsStatusTick > 0) this.settingsStatusTick--;
        if (this.mbStatusTick > 0) this.mbStatusTick--;
        // 同步 nbt 路径 / 尺寸 / 状态消息 (选 NBT / 选区完成后会自动显示)
        if (this.currentTab == Tab.CREATE) {
            refreshNbtFileDisplay();
            // 选 NBT / 选区 / 选图标 完成后, GuiCreateBuildingInfo.setStatus() 会更新其 static 字段.
            // 这里每 tick 轮询一次, 同步到本 GUI 的状态栏, 用户才能看到识别失败的提示.
            pollStatusFromCb();
        }
        // 制作蓝图 tab: 旧的反射轮询路径已删除. 现在用回调 (handleNbtPickedForBlueprint /
        // handleInGamePickedForBlueprint) 直接更新 selectedBuilding, 不再读写 GuiCreateBuildingInfo
        // 的 static 字段, 因此不会再"开完创建建筑界面" (那是 startInWorldPicking 的副作用).
    }

    /**
     * 选完 NBT 文件的回调 (pickNbtForBlueprint 走这里). res=null 时要么是取消要么是失败:
     *   - 取消: 不报错, 也不动 selectedBuilding, 让玩家继续编辑
     *   - 失败: 报错, 状态栏提示
     */
    private void handleNbtPickedForBlueprint(
            com.prefab.addon.client.gui.GuiCreateBuildingInfo.NbtPickResult res,
            Throwable err) {
        if (err != null) {
            this.mbStatus = "§cNBT 选择失败: §7" + err.getMessage();
            this.mbStatusColor = 0xFF5555;
            this.mbStatusTick = 150;
            return;
        }
        if (res == null) {
            // 取消
            this.mbStatus = "§7已取消 NBT 选择";
            this.mbStatusColor = 0xFFAA55;
            this.mbStatusTick = 80;
            return;
        }
        // 选完了: 构造 LocalBuilding, source="external"
        String id = res.fileName.toLowerCase(Locale.ROOT);
        if (id.endsWith(".nbt")) id = id.substring(0, id.length() - 4);
        else if (id.endsWith(".litematic")) id = id.substring(0, id.length() - 10);
        else if (id.endsWith(".schem")) id = id.substring(0, id.length() - 6);
        else if (id.endsWith(".schematic")) id = id.substring(0, id.length() - 9);
        LocalBuilding lb = new LocalBuilding(
            id, res.fileName, "", "", java.util.Collections.emptyList(), "",
            ".nbt", 0L, null, "external",
            res.file.toPath(), null, null);
        this.selectedBuilding = lb;
        this.mbStatus = "§a已选建筑文件: §f" + res.fileName
            + (res.sizeString != null && !res.sizeString.isEmpty() ? "  §7(" + res.sizeString + ")" : "")
            + "  §7[外源]";
        this.mbStatusColor = 0x55FF55;
        this.mbStatusTick = 120;
        PrefabCustomAddon.LOGGER.info("[MAKE-BLUEPRINT] 选完建筑文件: id={} size={} path={}",
            id, res.sizeString, res.file.toPath());
    }

    /**
     * 检查 {@code <ns>_<id>_blueprint.js} 是否已存在于 {@code kubejs/startup_scripts/}.
     * 存在就把 {@code id} 改成 {@code <id>_2}, 还存在再 {@code _3}...
     * <p>修 "我建了 3 次, KubeJS 只看得到 1 个" — 之前每次都用同一个默认 id, 后面写的覆盖前面写的.</p>
     */
    private String autoIncrementItemIdIfTaken(String ns, String id) {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null) return id;
            java.nio.file.Path kubejsDir = mc.gameDirectory.toPath().resolve("kubejs");
            java.nio.file.Path startupScripts = kubejsDir.resolve("startup_scripts");
            java.nio.file.Path serverScripts = kubejsDir.resolve("server_scripts");
            if (!java.nio.file.Files.isDirectory(startupScripts) || !java.nio.file.Files.isDirectory(serverScripts)) {
                return id;  // 第一次跑, 不会冲突
            }
            String jsName = ns + "_" + id + "_blueprint.js";
            java.nio.file.Path jsPath = startupScripts.resolve(jsName);
            java.nio.file.Path recipePath = serverScripts.resolve(ns + "_" + id + "_blueprint_recipes.js");
            if (!java.nio.file.Files.exists(jsPath) && !java.nio.file.Files.exists(recipePath)) {
                return id;  // 不冲突
            }
            // 冲突: 找下一个可用后缀
            int n = 2;
            while (n < 1000) {
                String candidate = id + "_" + n;
                String jsName2 = ns + "_" + candidate + "_blueprint.js";
                String recipeName2 = ns + "_" + candidate + "_blueprint_recipes.js";
                if (!java.nio.file.Files.exists(startupScripts.resolve(jsName2))
                    && !java.nio.file.Files.exists(serverScripts.resolve(recipeName2))) {
                    PrefabCustomAddon.LOGGER.info(
                        "[MAKE-BLUEPRINT] itemId '{}' 已存在, 自动改为 '{}'", id, candidate);
                    return candidate;
                }
                n++;
            }
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[MAKE-BLUEPRINT] autoIncrementItemIdIfTaken 失败: {}", t.getMessage());
        }
        return id;
    }

    /**
     * 选完游戏内区域的回调 (pickInGameForBlueprint 走这里).
     * <p>关键: 必须把 NBT 真实写入 {@code prefab-extension/<id>.nbt},
     * 不能再用临时文件 + hash 短码, 否则右键蓝图时 prefab 系统按
     * constructionId 找建筑根本找不到 (图里 "找不到建筑:?/in_game_xxx").</p>
     */
    private void handleInGamePickedForBlueprint(
            com.prefab.addon.client.gui.GuiCreateBuildingInfo.InGamePickResult res,
            Throwable err) {
        if (err != null) {
            this.mbStatus = "§c游戏内选区失败: §7" + err.getMessage();
            this.mbStatusColor = 0xFF5555;
            this.mbStatusTick = 150;
            return;
        }
        if (res == null) {
            this.mbStatus = "§7已取消游戏内选区";
            this.mbStatusColor = 0xFFAA55;
            this.mbStatusTick = 80;
            return;
        }
        if (res.nbtData == null || res.nbtData.length == 0) {
            this.mbStatus = "§c游戏内选区 NBT 为空";
            this.mbStatusColor = 0xFF5555;
            this.mbStatusTick = 150;
            return;
        }

        // 生成建筑 id: 时间戳 + hash 短码, 避免和现有文件撞名
        int h = java.util.Arrays.hashCode(res.nbtData);
        String shortHash = String.format("%06x", h & 0xFFFFFF);
        long ts = System.currentTimeMillis() / 1000L;
        String id = "ingame_" + ts + "_" + shortHash;
        String displayName = res.sizeString != null && !res.sizeString.isEmpty()
            ? "内嵌建筑 " + res.sizeString
            : ("内嵌建筑 " + id);

        // 把 NBT 持久化到 prefab-extension/ — 这是真实建筑位置, prefab 系统会扫到
        java.nio.file.Path extRoot;
        java.nio.file.Path nbtPath = null;
        try {
            extRoot = com.prefab.addon.extension.LocalBuildingScanner.getExtensionRoot();
            java.nio.file.Files.createDirectories(extRoot);
            nbtPath = extRoot.resolve(id + ".nbt");
            java.nio.file.Files.write(nbtPath, res.nbtData);
            PrefabCustomAddon.LOGGER.info("[MAKE-BLUEPRINT] 游戏内选区持久化: {} ({} bytes)",
                nbtPath, res.nbtData.length);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[MAKE-BLUEPRINT] 持久化 NBT 失败", t);
            this.mbStatus = "§c写入 prefab-extension/ 失败: §7" + t.getMessage();
            this.mbStatusColor = 0xFF5555;
            this.mbStatusTick = 150;
            return;
        }

        // 构造 LocalBuilding, 指向真实持久化文件 (让右键能通过 constructionId 找到)
        LocalBuilding lb = new LocalBuilding(
            id, displayName, "", "", res.modIds == null ? java.util.Collections.emptyList() : res.modIds, "",
            ".nbt", res.nbtData.length, null, "extension",
            nbtPath, null, null);
        this.selectedBuilding = lb;
        this.mbStatus = "§a已选游戏内建筑: §f" + displayName
            + (res.sizeString != null && !res.sizeString.isEmpty() ? "  §7(" + res.sizeString + ")" : "")
            + "  §7[已存到 prefab-extension/]";
        this.mbStatusColor = 0x55FF55;
        this.mbStatusTick = 120;
        PrefabCustomAddon.LOGGER.info("[MAKE-BLUEPRINT] 选完游戏内建筑: id={} size={} bytes={}",
            id, res.sizeString, res.nbtData.length);
    }

    /**
     * tick 轮询: GuiCreateBuildingInfo 完成选 NBT / 选区 后, 会写 {@code nbtData} 和 {@code nbtPath}.
     * 检测到任一为非空时, 构造 {@link LocalBuilding} 写回 {@code selectedBuilding} 并结束轮询.
     */
    private void pollNbtFromCb() {
        try {
            Class<?> cb = com.prefab.addon.client.gui.GuiCreateBuildingInfo.class;
            java.lang.reflect.Field df = cb.getDeclaredField("nbtData");
            df.setAccessible(true);
            byte[] nbt = (byte[]) df.get(null);
            java.lang.reflect.Field pf = cb.getDeclaredField("nbtPath");
            pf.setAccessible(true);
            String path = (String) pf.get(null);
            if (nbt == null || nbt.length == 0) return;
            // 选完: 构造 LocalBuilding
            String fileName = path == null ? "in_game_" + System.currentTimeMillis()
                : new java.io.File(path).getName();
            String id = fileName.toLowerCase(Locale.ROOT);
            if (id.endsWith(".nbt")) id = id.substring(0, id.length() - 4);
            // 路径可能在 GuiCreateBuildingInfo 选中区时被写为临时文件, 那就当 in_game 来源
            String source = (this.mbPollSource == 2) ? "in_game" : "external";
            String displayName = id;
            // 同步 fieldIdValue / fieldNameValue, 后续如果玩家要"生成"也能用
            com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldIdValue = id;
            com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldNameValue = displayName;
            // 构造 LocalBuilding
            java.nio.file.Path fp = path == null ? null : new java.io.File(path).toPath();
            com.prefab.addon.extension.LocalBuilding lb =
                new com.prefab.addon.extension.LocalBuilding(
                    id, displayName, "", "", java.util.Collections.emptyList(), "",
                    ".nbt", 0L, null, source,
                    fp, null, null);
            this.selectedBuilding = lb;
            this.mbStatus = PrefabCustomAddon.tr("gui.make_blueprint.status.building_picked", displayName + "  (" + source + ")");
            this.mbStatusColor = 0x55FF55;
            this.mbStatusTick = 120;
            // 清掉 cb 状态, 避免下次再触发
            df.set(null, null);
            this.mbPollSource = 0;
            PrefabCustomAddon.LOGGER.info("[MAKE-BLUEPRINT] 选完建筑: id={} source={} path={}", id, source, path);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[MAKE-BLUEPRINT] pollNbtFromCb 失败", t);
            this.mbPollSource = 0;
        }
    }

    /**
     * 轮询 GuiCreateBuildingInfo 的 static 状态消息 → 本 GUI 状态栏.
     * 触发条件: 1) cbTick 上升沿 (从 0 跳到 100+) — 全新消息
     *           2) 消息文本与上次不同 — 同样是新消息, 但 cbTick 仍是 100 (复用)
     * 只用任一条件触发一次, 避免状态栏永不消失.
     */
    private int lastCbStatusTick = -1;
    private String lastCbStatusText = null;
    private void pollStatusFromCb() {
        try {
            java.lang.reflect.Field tf = com.prefab.addon.client.gui.GuiCreateBuildingInfo.class.getDeclaredField("statusTick");
            tf.setAccessible(true);
            int cbTick = tf.getInt(null);
            java.lang.reflect.Field mf = com.prefab.addon.client.gui.GuiCreateBuildingInfo.class.getDeclaredField("statusMessage");
            mf.setAccessible(true);
            String cbMsg = (String) mf.get(null);
            if (cbTick <= 0 || cbMsg == null) {
                lastCbStatusTick = 0;
                lastCbStatusText = null;
                return;
            }
            boolean risingEdge = cbTick > lastCbStatusTick;
            boolean textChanged = !cbMsg.equals(lastCbStatusText);
            if (risingEdge || textChanged) {
                lastCbStatusTick = cbTick;
                lastCbStatusText = cbMsg;
                copyStatusFromCb();
            }
        } catch (Throwable ignored) {}
    }

    @Override
    protected void preButtonRender(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, float partialTicks) {
        // 1) 主面板底色
        guiGraphics.fill(this.grayBoxX, this.grayBoxY, this.grayBoxX + PANEL_W, this.grayBoxY + PANEL_H, PANEL_BG);
        // 边框
        guiGraphics.fill(this.grayBoxX, this.grayBoxY, this.grayBoxX + PANEL_W, this.grayBoxY + 1, PANEL_BORDER);
        guiGraphics.fill(this.grayBoxX, this.grayBoxY + PANEL_H - 1, this.grayBoxX + PANEL_W, this.grayBoxY + PANEL_H, PANEL_BORDER);
        guiGraphics.fill(this.grayBoxX, this.grayBoxY, this.grayBoxX + 1, this.grayBoxY + PANEL_H, PANEL_BORDER);
        guiGraphics.fill(this.grayBoxX + PANEL_W - 1, this.grayBoxY, this.grayBoxX + PANEL_W, this.grayBoxY + PANEL_H, PANEL_BORDER);

        // 2) 左侧 tab 列 (背景)
        int tx = this.grayBoxX + 2;
        int ty = this.grayBoxY + 2;
        int tw = TABS_W;
        int th = TAB_H;
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            int by = ty + i * th;
            boolean active = tabs[i] == this.currentTab;
            boolean hover = mouseX >= tx && mouseX < tx + tw && mouseY >= by && mouseY < by + th;
            int bg = active ? TAB_BG_ACTIVE : (hover ? TAB_BG_HOVER : 0xFF2A2A2A);
            guiGraphics.fill(tx, by, tx + tw, by + th, bg);
        }

        // 3) 右内容区 (纯白)
        int[] cr = contentRect();
        guiGraphics.fill(cr[0], cr[1], cr[0] + cr[2], cr[1] + cr[3], CONTENT_BG);
    }

    @Override
    protected void postButtonRender(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, float partialTicks) {
        // 1) 左 tab 文字 (在 tab 背景上画)
        int tx = this.grayBoxX + 2;
        int ty = this.grayBoxY + 2;
        int tw = TABS_W;
        int th = TAB_H;
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            // 联动: KubeJS 没装就不渲染"制作蓝图" tab
            if (tabs[i] == Tab.MAKE_BLUEPRINT && !KubeJSIntegration.isLoaded()) continue;
            String label = tabs[i].label;
            int textW = this.font.width(label);
            int textX = tx + (tw - textW) / 2;
            int textY = ty + i * th + (th - 8) / 2;
            guiGraphics.drawString(this.font, label, textX, textY, TAB_FG, true);
        }

        // 2) 当前 tab 的内容
        int[] cr = contentRect();
        if (currentTab == Tab.CREATE) {
            drawCreateTab(guiGraphics, cr, mouseX, mouseY);
        } else if (currentTab == Tab.EDIT) {
            drawEditTab(guiGraphics, cr, mouseX, mouseY);
        } else if (currentTab == Tab.MAKE_BLUEPRINT) {
            drawMakeBlueprintTab(guiGraphics, cr, mouseX, mouseY);
        } else {
            drawSettingsTab(guiGraphics, cr, mouseX, mouseY);
        }
    }

    @Override
    public void buttonClicked(AbstractButton button) {
        // 本 GUI 的 EditBox/Button 都是用 makeButton 包装, lambda 已经处理点击;
        // 这里留空即可 (GuiBase 把按钮的点击转给 lambda, buttonClicked 仅用于其他 AbstractButton 监听).
    }

    private void drawCreateTab(GuiGraphics guiGraphics, int[] cr, int mouseX, int mouseY) {
        int rx = cr[0], ry = cr[1], rw = cr[2];

        // 标题
        guiGraphics.drawString(this.font, "§l创建建筑", rx + 4, ry + 4, TITLE_COLOR, false);

        // 字段标签 (在 EditBox 左侧, EditBox 已经由 super.render 画, 这里只画标签)
        int labelW = 40;
        int rowH = 20;
        int y = ry + 22;  // 跳过标题 (与字段 y=ry+18 对齐)
        String[] labels = {"标识符:", "建筑名:", "作者:", "依赖:", "尺寸:", "描述:"};
        for (int i = 0; i < labels.length; i++) {
            int ly = y + i * rowH + 4;
            guiGraphics.drawString(this.font, labels[i], rx + 4, ly, LABEL_COLOR, false);
        }

        // 状态消息 (绘制在专用的占位行, 不会与按钮重叠)
        if (this.statusMessage != null && this.statusTick > 0 && this.createStatusY > 0) {
            int sy = this.createStatusY + 2;  // 占位行上 +2px (文字基线)
            if (sy < ry + 14) sy = ry + 14;
            guiGraphics.drawString(this.font, this.statusMessage, rx + 4, sy, this.statusColor, false);
        }
    }

    private void drawEditTab(GuiGraphics guiGraphics, int[] cr, int mouseX, int mouseY) {
        int rx = cr[0], ry = cr[1], rw = cr[2], rh = cr[3];

        // 标题
        guiGraphics.drawString(this.font, "§l编辑建筑", rx + 4, ry + 4, TITLE_COLOR, false);
        // 搜索框 hint: 当为空时在 EditBox 右侧画一个 "🔍 搜索" 小字
        if (this.edSearch != null && this.edSearch.getValue().isEmpty()) {
            // 把 hint 画在 EditBox 上方一点点 (避免被 EditBox 黑底遮)
            guiGraphics.drawString(this.font, "§7🔍 搜索 建筑名 / 作者 / 描述",
                this.edSearchRect[0], this.edSearchRect[1] - 10, HINT_COLOR, false);
        }

        if (this.editFiltered.isEmpty()) {
            String hint = this.editBuildings.isEmpty()
                ? "暂无本地建筑 (prefab-extension/ 为空)"
                : "没有匹配 \"" + this.editSearch + "\" 的建筑";
            guiGraphics.drawString(this.font, hint, rx + 4, ry + 40, HINT_COLOR, false);
            this.editCardRects.clear();
            this.editViewBtnRects.clear();
            this.editEditBtnRects.clear();
            // 仍然画分页按钮 (即使没数据, 也显示页码)
            drawEditPagination(guiGraphics, cr, mouseX, mouseY);
            return;
        }

        // 2 列卡片网格
        int cardW = (rw - 16) / 2;
        int cardH = 78;
        int gap = 4;
        int startX = rx + 6;
        int startY = ry + 50;  // 标题 (4) + hint (~10) + 搜索框 (32~46) + 间距
        int cols = 2;
        // 给底部翻页按钮留 16px: rh - 16 是可用卡片区高度
        int maxRows = (rh - 50 - 16) / (cardH + gap);
        if (maxRows < 1) maxRows = 1;
        this.editPageSize = maxRows * cols;

        this.editCardRects.clear();
        this.editViewBtnRects.clear();
        this.editEditBtnRects.clear();

        int totalPages = Math.max(1, (this.editFiltered.size() + this.editPageSize - 1) / this.editPageSize);
        if (this.editPage < 1) this.editPage = 1;
        if (this.editPage > totalPages) this.editPage = totalPages;
        int startIdx = (this.editPage - 1) * this.editPageSize;
        int endIdx = Math.min(startIdx + this.editPageSize, this.editFiltered.size());

        int idx = startIdx;
        int drawn = 0;
        for (int row = 0; row < maxRows && idx < endIdx; row++) {
            for (int col = 0; col < cols && idx < endIdx; col++) {
                LocalBuilding lb = this.editFiltered.get(idx);
                int cx = startX + col * (cardW + gap);
                int cy = startY + row * (cardH + gap);
                boolean hover = mouseX >= cx && mouseX < cx + cardW && mouseY >= cy && mouseY < cy + cardH;
                int bg = hover ? CARD_HOVER : CARD_BG;
                guiGraphics.fill(cx, cy, cx + cardW, cy + cardH, bg);
                // 边框
                guiGraphics.fill(cx, cy, cx + cardW, cy + 1, CARD_BORDER);
                guiGraphics.fill(cx, cy + cardH - 1, cx + cardW, cy + cardH, CARD_BORDER);
                guiGraphics.fill(cx, cy, cx + 1, cy + cardH, CARD_BORDER);
                guiGraphics.fill(cx + cardW - 1, cy, cx + cardW, cy + cardH, CARD_BORDER);
                // 缩略图占位
                int imgX = cx + 4, imgY = cy + 4, imgS = 36;
                guiGraphics.fill(imgX, imgY, imgX + imgS, imgY + imgS, 0xFF333333);
                // 优先用 .png 缩略图 (用真实尺寸 blit, 避免 texW/texH 写死 64 报错)
                CachedImage img = imageLocationFor(lb);
                if (img != null) {
                    try {
                        guiGraphics.blit(img.loc, imgX, imgY, imgS, imgS,
                            0, 0, img.width, img.height, img.width, img.height);
                    } catch (Throwable t) {
                        PrefabCustomAddon.LOGGER.warn("[EDITOR] blit 缩略图失败 ({}): {}", lb.id, t.getMessage());
                        guiGraphics.drawString(this.font, "图", imgX + 12, imgY + 12, HINT_COLOR, false);
                    }
                } else {
                    guiGraphics.drawString(this.font, "图", imgX + 12, imgY + 12, HINT_COLOR, false);
                }
                // 建筑名
                String name = lb.getDisplayName();
                int maxNameW = cardW - imgS - 12;
                if (this.font.width(name) > maxNameW) {
                    while (name.length() > 1 && this.font.width(name + "...") > maxNameW) name = name.substring(0, name.length() - 1);
                    name = name + "...";
                }
                guiGraphics.drawString(this.font, name, cx + imgS + 8, cy + 8, LABEL_COLOR, false);
                // 作者
                if (lb.author != null && !lb.author.isEmpty()) {
                    guiGraphics.drawString(this.font, "作者: " + lb.author, cx + imgS + 8, cy + 18, HINT_COLOR, false);
                }
                // 文件大小
                guiGraphics.drawString(this.font, "格式: " + (lb.fileExt == null ? "?" : lb.fileExt) + "  " + formatSize(lb.fileSize),
                    cx + imgS + 8, cy + 28, HINT_COLOR, false);
                // 按钮 [查看] [编辑]
                int btnW = 38, btnH = 14, btnGap = 4;
                int btnY = cy + cardH - btnH - 4;
                int btnX1 = cx + cardW - btnW * 2 - btnGap - 4;
                int btnX2 = btnX1 + btnW + btnGap;
                boolean hoverView = mouseX >= btnX1 && mouseX < btnX1 + btnW && mouseY >= btnY && mouseY < btnY + btnH;
                boolean hoverEdit = mouseX >= btnX2 && mouseX < btnX2 + btnW && mouseY >= btnY && mouseY < btnY + btnH;
                guiGraphics.fill(btnX1, btnY, btnX1 + btnW, btnY + btnH, hoverView ? BTN_BG_HOVER : BTN_BG);
                guiGraphics.fill(btnX2, btnY, btnX2 + btnW, btnY + btnH, hoverEdit ? BTN_BG_HOVER : BTN_BG);
                guiGraphics.drawString(this.font, "查看", btnX1 + (btnW - this.font.width("查看")) / 2, btnY + 3, LABEL_COLOR, false);
                guiGraphics.drawString(this.font, "编辑", btnX2 + (btnW - this.font.width("编辑")) / 2, btnY + 3, LABEL_COLOR, false);

                this.editCardRects.add(new int[]{cx, cy, cardW, cardH});
                this.editViewBtnRects.add(new int[]{btnX1, btnY, btnW, btnH});
                this.editEditBtnRects.add(new int[]{btnX2, btnY, btnW, btnH});
                idx++;
                drawn++;
            }
        }

        // 翻页按钮 + 计数
        drawEditPagination(guiGraphics, cr, mouseX, mouseY);
    }

    /** 画编辑 tab 底部的翻页按钮: [< 上一页]  X/Y  [下一页 >]. */
    private void drawEditPagination(GuiGraphics guiGraphics, int[] cr, int mouseX, int mouseY) {
        int rx = cr[0], ry = cr[1], rw = cr[2], rh = cr[3];
        int total = this.editFiltered.size();
        int totalPages = Math.max(1, (total + this.editPageSize - 1) / this.editPageSize);
        int btnY = ry + rh - 14;
        int btnH = 12;
        int btnW = 50;
        int gap = 4;
        int prevX = rx + 6;
        int nextX = rx + rw - 6 - btnW;
        boolean canPrev = this.editPage > 1;
        boolean canNext = this.editPage < totalPages;
        boolean hoverPrev = canPrev && mouseX >= prevX && mouseX < prevX + btnW && mouseY >= btnY && mouseY < btnY + btnH;
        boolean hoverNext = canNext && mouseX >= nextX && mouseX < nextX + btnW && mouseY >= btnY && mouseY < btnY + btnH;
        int prevBg = canPrev ? (hoverPrev ? BTN_BG_HOVER : BTN_BG) : 0xFFCCCCCC;
        int nextBg = canNext ? (hoverNext ? BTN_BG_HOVER : BTN_BG) : 0xFFCCCCCC;
        int prevTxt = canPrev ? LABEL_COLOR : 0xFF888888;
        int nextTxt = canNext ? LABEL_COLOR : 0xFF888888;
        // 上一页按钮
        guiGraphics.fill(prevX, btnY, prevX + btnW, btnY + btnH, prevBg);
        guiGraphics.fill(prevX, btnY, prevX + btnW, btnY + 1, CARD_BORDER);
        guiGraphics.fill(prevX, btnY + btnH - 1, prevX + btnW, btnY + btnH, CARD_BORDER);
        guiGraphics.fill(prevX, btnY, prevX + 1, btnY + btnH, CARD_BORDER);
        guiGraphics.fill(prevX + btnW - 1, btnY, prevX + btnW, btnY + btnH, CARD_BORDER);
        guiGraphics.drawString(this.font, "< 上一页", prevX + 6, btnY + 2, prevTxt, false);
        // 下一页按钮
        guiGraphics.fill(nextX, btnY, nextX + btnW, btnY + btnH, nextBg);
        guiGraphics.fill(nextX, btnY, nextX + btnW, btnY + 1, CARD_BORDER);
        guiGraphics.fill(nextX, btnY + btnH - 1, nextX + btnW, btnY + btnH, CARD_BORDER);
        guiGraphics.fill(nextX, btnY, nextX + 1, btnY + btnH, CARD_BORDER);
        guiGraphics.fill(nextX + btnW - 1, btnY, nextX + btnW, btnY + btnH, CARD_BORDER);
        guiGraphics.drawString(this.font, "下一页 >", nextX + 6, btnY + 2, nextTxt, false);
        // 中间页码 X/Y
        String pageText = this.editPage + " / " + totalPages;
        int ptW = this.font.width(pageText);
        int ptX = prevX + btnW + (nextX - prevX - btnW - ptW) / 2;
        guiGraphics.drawString(this.font, pageText, ptX, btnY + 2, HINT_COLOR, false);

        this.editPagingRects = new int[]{
            prevX, btnY, btnW, btnH,
            nextX, btnY, btnW, btnH
        };
    }

    // ============================================================
    // 设置 tab - 自绘 (与 Browser 风格一致, 替代 LDLib2 的 SettingsGui)
    //
    // 内容较多 (挑战玩法 + 性能/动画 + 快捷操作), 分 2 页:
    //   页 1: 挑战玩法 (挑战模式 / 自动检测依赖) + 性能 / 动画 (下落动画 / 预览 / 建造)
    //   页 2: 快捷操作 (打开文件夹 / 同步 / 重置)
    // 顶部有"上一页 / 页码 / 下一页"小翻页条, 完成按钮固定在右下角任意页都有.
    // ============================================================
    private static final int SETTINGS_TOTAL_PAGES = 2;

    // ============================================================
    // 制作蓝图 tab
    // ============================================================
    private void drawMakeBlueprintTab(GuiGraphics guiGraphics, int[] cr, int mouseX, int mouseY) {
        int rx = cr[0], ry = cr[1], rw = cr[2], rh = cr[3];
        Minecraft mc = Minecraft.getInstance();

        // ===== 1) 底部固定区 (生成/打开文件夹按钮 + 状态) — 不参与滚动 =====
        int bottomY = ry + rh - 32;     // 生成 + 打开 kubejs 按钮 Y
        int statusY = bottomY - 14;     // 状态消息 Y
        int scrollBottom = statusY - 4; // 滚动区下边界

        // 状态消息
        if (mbStatus != null && mbStatusTick > 0) {
            int color = mbStatusColor | 0xFF000000;
            guiGraphics.drawString(this.font, mbStatus, rx + 8, statusY, color, false);
        }

        // 生成 + 打开文件夹按钮
        int btnBottomW = 100, btnBottomGap = 8, btnBottomH = 22;
        mbGenerateRect = new int[]{rx + 8, bottomY, btnBottomW, btnBottomH};
        drawButton(guiGraphics, mbGenerateRect, PrefabCustomAddon.tr("gui.make_blueprint.btn.generate"),
            BTN_BG_PRIMARY, mouseX, mouseY);
        mbOpenFolderRect = new int[]{rx + 8 + btnBottomW + btnBottomGap, bottomY, 110, btnBottomH};
        drawButton(guiGraphics, mbOpenFolderRect, PrefabCustomAddon.tr("gui.make_blueprint.btn.open_folder"),
            0xFFCCCCCC, mouseX, mouseY);

        // ===== 2) 滚动区: 从 ry 到 scrollBottom =====
        int scrollTop = ry;
        int scrollH = scrollBottom - scrollTop;

        // 启用 scissor, 防止越界绘制
        int scX1 = rx, scY1 = scrollTop, scX2 = rx + rw - 8, scY2 = scrollBottom;
        guiGraphics.enableScissor(scX1, scY1, scX2, scY2);

        // 逻辑 Y: 用来堆元素, 不受 mbScroll 影响
        int ly = ry + 6;  // logical Y (滚动前)

        // 标题
        guiGraphics.drawString(this.font, PrefabCustomAddon.tr("gui.make_blueprint.title"), rx + 8, ly - mbScroll, 0xFF333333, false);
        ly += 14;
        guiGraphics.drawString(this.font,
            PrefabCustomAddon.tr("gui.make_blueprint.subtitle"),
            rx + 8, ly - mbScroll, 0xFF666666, false);
        ly += 12;
        guiGraphics.drawString(this.font,
            PrefabCustomAddon.tr("gui.make_blueprint.restart_hint"),
            rx + 8, ly - mbScroll, 0xFFAA5500, false);
        ly += 16;

        // ---- EditBox 区域 ----
        int formX = rx + 8;
        int labelW = 60;
        int rowH = 22;

        // 名字 (显示名) — 唯一让玩家填的输入框
        guiGraphics.drawString(this.font, PrefabCustomAddon.tr("gui.make_blueprint.label.name"), formX, ly - mbScroll + 4, 0xFF333333, false);
        edBlueprintName.setX(formX + labelW);
        edBlueprintName.setY(ly - mbScroll);
        edBlueprintName.setWidth(rw - labelW - 24);
        edBlueprintName.setVisible(true);
        ly += rowH;
        // 命名空间 / 物品 id 简化为自动生成: player_pack:player_blueprint[_2][_3]...
        // 不用再让玩家填, 省两个 input

        // ---- 建筑选择: 拆 2 按钮, 纵向排列 ----
        int selBtnH = 16;
        int selBtnW = rw - 24;
        // 选建筑文件
        mbSelectBuildingFileRect = new int[]{formX, ly - mbScroll, selBtnW, selBtnH};
        String fileBtnLabel = (selectedBuilding != null && !"in_game".equals(selectedBuilding.source))
            ? PrefabCustomAddon.tr("gui.make_blueprint.btn.pick_building_file_set", selectedBuilding.getDisplayName())
            : PrefabCustomAddon.tr("gui.make_blueprint.btn.pick_building_file_empty");
        drawButton(guiGraphics, mbSelectBuildingFileRect, fileBtnLabel,
            selectedBuilding != null ? BTN_BG_HOVER : 0xFFEEEEEE,
            mouseX, mouseY);
        ly += selBtnH + 2;
        // 游戏内选建筑
        mbSelectInGameRect = new int[]{formX, ly - mbScroll, selBtnW, selBtnH};
        String inGameBtnLabel = (selectedBuilding != null && "in_game".equals(selectedBuilding.source))
            ? PrefabCustomAddon.tr("gui.make_blueprint.btn.pick_building_ingame_set", selectedBuilding.getDisplayName())
            : PrefabCustomAddon.tr("gui.make_blueprint.btn.pick_building_ingame_empty");
        drawButton(guiGraphics, mbSelectInGameRect, inGameBtnLabel,
            selectedBuilding != null && "in_game".equals(selectedBuilding.source)
                ? BTN_BG_HOVER : 0xFFEEEEEE,
            mouseX, mouseY);
        ly += selBtnH + 2;

        // 选贴图
        mbSelectTextureRect = new int[]{formX, ly - mbScroll, selBtnW, selBtnH};
        String texBtnLabel = (selectedTextureSource != null)
            ? PrefabCustomAddon.tr("gui.make_blueprint.btn.pick_texture_set", selectedTextureSource.getFileName().toString())
            : PrefabCustomAddon.tr("gui.make_blueprint.btn.pick_texture_empty");
        drawButton(guiGraphics, mbSelectTextureRect, texBtnLabel,
            selectedTextureSource != null ? BTN_BG_HOVER : 0xFFEEEEEE,
            mouseX, mouseY);
        ly += selBtnH + 6;

        // ---- 9 宫格配方 ----
        guiGraphics.drawString(this.font, PrefabCustomAddon.tr("gui.make_blueprint.recipe_label"), formX, ly - mbScroll, 0xFF333333, false);
        ly += 14;
        int cellSize = 22;
        int cellGap = 2;
        int gridW = cellSize * 3 + cellGap * 2;
        int gridX = formX;
        int gridY = ly - mbScroll;
        mbRecipeGridRect = new int[]{gridX, gridY, gridW, cellSize * 3 + cellGap * 2};
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int idx = row * 3 + col;
                int cx = gridX + col * (cellSize + cellGap);
                int cy = gridY + row * (cellSize + cellGap);
                String item = recipeItems.get(idx);
                guiGraphics.fill(cx, cy, cx + cellSize, cy + cellSize, 0xFFE8E8E8);
                guiGraphics.fill(cx, cy, cx + cellSize, cy + 1, 0xFFAAAAAA);
                guiGraphics.fill(cx, cy + cellSize - 1, cx + cellSize, cy + cellSize, 0xFFAAAAAA);
                guiGraphics.fill(cx, cy, cx + 1, cy + cellSize, 0xFFAAAAAA);
                guiGraphics.fill(cx + cellSize - 1, cy, cx + cellSize, cy + cellSize, 0xFFAAAAAA);
                if (item != null && !item.isBlank()) {
                    // 1) 优先画物品图标 (玩家在搜索弹窗选了物品后, 这里显示该物品的 16x16 icon)
                    ItemStack stack = parseItemStack(item);
                    if (stack != null) {
                        guiGraphics.renderItem(stack, cx + 3, cy + 3);
                    } else {
                        String shortName = item;
                        if (shortName.contains(":")) shortName = shortName.substring(shortName.indexOf(':') + 1);
                        if (this.font.width(shortName) > cellSize - 4) {
                            int len = shortName.length();
                            while (len > 2 && this.font.width(shortName.substring(0, len)) > cellSize - 4) len--;
                            shortName = shortName.substring(0, len) + "..";
                        }
                        guiGraphics.drawString(this.font, "§7" + shortName, cx + 2, cy + 8, 0xFF333333, false);
                    }
                } else {
                    guiGraphics.drawString(this.font, "§8-", cx + 8, cy + 8, 0xFF888888, false);
                }
            }
        }
        ly += cellSize * 3 + cellGap * 2 + 6;

        // ---- 锁定 toggle ----
        int lkBox = 12;
        int lkY = ly - mbScroll;
        mbLockedRect = new int[]{formX, lkY, rw - 24, 18};
        guiGraphics.fill(formX, lkY, formX + lkBox, lkY + lkBox, 0xFFFFFFFF);
        guiGraphics.fill(formX, lkY, formX + lkBox, lkY + 1, 0xFF666666);
        guiGraphics.fill(formX, lkY + lkBox - 1, formX + lkBox, lkY + lkBox, 0xFF666666);
        guiGraphics.fill(formX, lkY, formX + 1, lkY + lkBox, 0xFF666666);
        guiGraphics.fill(formX + lkBox - 1, lkY, formX + lkBox, lkY + lkBox, 0xFF666666);
        if (blueprintLocked) {
            guiGraphics.fill(formX + 2, lkY + 2, formX + lkBox - 2, lkY + lkBox - 2, 0xFF3366AA);
        }
        guiGraphics.drawString(this.font, PrefabCustomAddon.tr("gui.make_blueprint.locked_label"),
            formX + lkBox + 6, lkY + 2, 0xFF333333, false);
        ly += 24;

        // 关闭 scissor (但 super.render 在外层, EditBox 渲染会绕过 scissor, 见 render() 重写)
        guiGraphics.disableScissor();

        // 总内容高度 (ly 是滚动前逻辑底部, ry 是滚动前逻辑顶部)
        int contentH = ly - ry;
        mbContentHeight = contentH;
        int maxScroll = Math.max(0, contentH - scrollH);
        if (mbScroll > maxScroll) mbScroll = maxScroll;
        if (mbScroll < 0) mbScroll = 0;

        // ===== 3) 滚动条 (右侧, 不参与滚动, 一直可见) =====
        int barX = rx + rw - 6;
        int barY = scrollTop + 2;
        int barH = scrollH - 4;
        mbScrollBarRect = new int[]{barX, barY, 4, barH};
        // 背景
        guiGraphics.fill(barX, barY, barX + 4, barY + barH, 0xFFCCCCCC);
        if (contentH > scrollH) {
            int thumbH = Math.max(20, (int) (barH * ((double) scrollH / contentH)));
            int thumbY = barY + (int) ((barH - thumbH) * ((double) mbScroll / maxScroll));
            mbScrollThumbRect = new int[]{barX, thumbY, 4, thumbH};
            guiGraphics.fill(barX, thumbY, barX + 4, thumbY + thumbH,
                mbScrollDragging ? 0xFF6666AA : 0xFF6688AA);
            // 缩略图
            int thumbH2 = Math.max(12, thumbH);
            guiGraphics.fill(barX, thumbY, barX + 4, thumbY + thumbH2, 0xFF336688);
        } else {
            mbScrollThumbRect = new int[]{0, 0, 0, 0};
        }

        // ===== 4) 等待 NBT 时显示加载状态 =====
        if (mbPollSource != 0) {
            String waitMsg = (mbPollSource == 1) ? "§e等待选 NBT 文件..." : "§e等待游戏内选区...";
            guiGraphics.drawString(this.font, waitMsg, rx + 8, statusY, 0xFFAA5500, false);
        }
    }

    /** 简化按钮绘制 (不依赖 makeButton, 不进 widget 列表). */
    private void drawButton(GuiGraphics g, int[] r, String label, int bg, int mx, int my) {
        int x = r[0], y = r[1], w = r[2], h = r[3];
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        int color = hover ? BTN_BG_HOVER : bg;
        g.fill(x, y, x + w, y + h, color);
        g.fill(x, y, x + w, y + 1, 0xFF555555);
        g.fill(x, y + h - 1, x + w, y + h, 0xFF555555);
        g.fill(x, y, x + 1, y + h, 0xFF555555);
        g.fill(x + w - 1, y, x + w, y + h, 0xFF555555);
        int textW = this.font.width(label);
        g.drawString(this.font, label, x + (w - textW) / 2, y + (h - 8) / 2, 0xFFFFFFFF, true);
    }

    // ============================================================
    // 设置 tab
    // ============================================================
    private void drawSettingsTab(GuiGraphics guiGraphics, int[] cr, int mouseX, int mouseY) {
        int rx = cr[0], ry = cr[1], rw = cr[2], rh = cr[3];
        PlayerPreferences prefs = PlayerPreferences.get();
        Minecraft mc = Minecraft.getInstance();
        boolean isOp = mc.player != null && mc.player.hasPermissions(2);

        // 标题
        guiGraphics.drawString(this.font, "§l⚙ Prefab Custom Addon 设置", rx + 4, ry + 4, TITLE_COLOR, false);

        // 顶部翻页条 (左侧: 页名, 右侧: < / N/2 / >)
        int headerY = ry + 16;
        String pageName = this.settingsPage == 1 ? "§7基础设置" : "§7快捷操作";
        guiGraphics.drawString(this.font, pageName, rx + 6, headerY, LABEL_COLOR, false);
        // 翻页按钮 (右上角, 小尺寸)
        int pgBtnW = 22, pgBtnH = 12;
        int pgNextX = rx + rw - pgBtnW - 6;
        int pgPrevX = pgNextX - pgBtnW - 4;
        int pgY = headerY - 2;
        boolean canPrev = this.settingsPage > 1;
        boolean canNext = this.settingsPage < SETTINGS_TOTAL_PAGES;
        boolean hoverPrev = canPrev && mouseX >= pgPrevX && mouseX < pgPrevX + pgBtnW
            && mouseY >= pgY && mouseY < pgY + pgBtnH;
        boolean hoverNext = canNext && mouseX >= pgNextX && mouseX < pgNextX + pgBtnW
            && mouseY >= pgY && mouseY < pgY + pgBtnH;
        // 上一页
        int prevBg = canPrev ? (hoverPrev ? BTN_BG_HOVER : BTN_BG) : 0xFFCCCCCC;
        guiGraphics.fill(pgPrevX, pgY, pgPrevX + pgBtnW, pgY + pgBtnH, prevBg);
        guiGraphics.fill(pgPrevX, pgY, pgPrevX + pgBtnW, pgY + 1, CARD_BORDER);
        guiGraphics.fill(pgPrevX, pgY + pgBtnH - 1, pgPrevX + pgBtnW, pgY + pgBtnH, CARD_BORDER);
        guiGraphics.fill(pgPrevX, pgY, pgPrevX + 1, pgY + pgBtnH, CARD_BORDER);
        guiGraphics.fill(pgPrevX + pgBtnW - 1, pgY, pgPrevX + pgBtnW, pgY + pgBtnH, CARD_BORDER);
        guiGraphics.drawString(this.font, "<", pgPrevX + 8, pgY + 2, canPrev ? LABEL_COLOR : 0xFF888888, false);
        // 页码 (两个按钮之间)
        String pageText = this.settingsPage + "/" + SETTINGS_TOTAL_PAGES;
        int ptW = this.font.width(pageText);
        int ptX = pgPrevX + pgBtnW + 2;
        guiGraphics.drawString(this.font, pageText, ptX, pgY + 2, HINT_COLOR, false);
        // 下一页
        int nextBg = canNext ? (hoverNext ? BTN_BG_HOVER : BTN_BG) : 0xFFCCCCCC;
        guiGraphics.fill(pgNextX, pgY, pgNextX + pgBtnW, pgY + pgBtnH, nextBg);
        guiGraphics.fill(pgNextX, pgY, pgNextX + pgBtnW, pgY + 1, CARD_BORDER);
        guiGraphics.fill(pgNextX, pgY + pgBtnH - 1, pgNextX + pgBtnW, pgY + pgBtnH, CARD_BORDER);
        guiGraphics.fill(pgNextX, pgY, pgNextX + 1, pgY + pgBtnH, CARD_BORDER);
        guiGraphics.fill(pgNextX + pgBtnW - 1, pgY, pgNextX + pgBtnW, pgY + pgBtnH, CARD_BORDER);
        guiGraphics.drawString(this.font, ">", pgNextX + 8, pgY + 2, canNext ? LABEL_COLOR : 0xFF888888, false);
        this.settingsPagingRects = new int[]{
            pgPrevX, pgY, pgBtnW, pgBtnH,
            pgNextX, pgY, pgBtnW, pgBtnH
        };

        int y = ry + 32;
        int padX = 6;

        if (this.settingsPage == 1) {
            // === 页 1: 挑战玩法 + 性能/动画 ===
            // === 分组 1: 挑战玩法 ===
            guiGraphics.drawString(this.font, "§e▍ 挑战玩法", rx + padX, y, LABEL_COLOR, false);
            y += 12;
            drawToggleRow(guiGraphics, rx, rw, y, mouseX, mouseY,
                "挑战模式", "§7(消耗材料, 全服生效, 须OP权限)", isOp,
                prefs.consumeMaterials,
                settingsToggleRect, 0);
            y += 28;  // 24px 行高 + 4px 间距 (跟新的 drawToggleRow 行高匹配)
            drawToggleRow(guiGraphics, rx, rw, y, mouseX, mouseY,
                "打开建筑时自动检测依赖", "§7(检查该建筑所需的 mod 是否安装)", true,
                prefs.autoDepCheckOnOpen,
                settingsToggleRect, 1);
            y += 32;  // 24px 行高 + 8px 分组间距

            // === 分组 2: 性能 / 动画 (cycle 按钮, 4 选 1) ===
            guiGraphics.drawString(this.font, "§e▍ 性能 / 动画", rx + padX, y, LABEL_COLOR, false);
            y += 12;
            // 建造动画 mode cycle 按钮. 4 状态循环: OFF → FALL → RAIN → THROW → OFF.
            // OFF = 不放动画, 瞬建. FALL/RAIN/THROW = 慢速建造 + 3 种视觉轨迹.
            drawModeCycleRow(guiGraphics, rx, rw, y, mouseX, mouseY);
        } else {
            // === 页 2: 快捷操作 ===
            guiGraphics.drawString(this.font, "§e▍ 快捷操作", rx + padX, y, LABEL_COLOR, false);
            y += 12;
            int btnH = 20;
            int btnW = rw - 12;
            int btnX = rx + 6;
            drawButtonRow(guiGraphics, btnX, y, btnW, btnH, mouseX, mouseY,
                "📁 打开拓展包文件夹", settingsOpenFolderRect);
            y += btnH + 4;
            drawButtonRow(guiGraphics, btnX, y, btnW, btnH, mouseX, mouseY,
                "🔄 同步服务器拓展包", settingsSyncServerRect);
            y += btnH + 4;
            drawButtonRow(guiGraphics, btnX, y, btnW, btnH, mouseX, mouseY,
                "♻ 重置偏好 (清除收藏/重置挑战模式)", settingsResetRect);
            y += btnH + 8;

            // === 联动: 强制启用 KubeJS "制作蓝图" tab (调试用) ===
            // 自动检测不到 KubeJS 时玩家可手动勾, 绕过检测.
            drawToggleRow(guiGraphics, rx, rw, y, mouseX, mouseY,
                PrefabCustomAddon.tr("gui.settings.force_kubejs_label"),
                PrefabCustomAddon.tr("gui.settings.force_kubejs_hint"),
                true,
                PlayerPreferences.get().forceKubeJSTab,
                settingsToggleRect, 2);
        }

        // 完成 button (右下角, 固定, 任意页都有)
        int doneW = 80;
        int doneH = 18;
        int doneX = rx + rw - doneW - 6;
        int doneY = ry + rh - doneH - 4;
        boolean hoverDone = mouseX >= doneX && mouseX < doneX + doneW
            && mouseY >= doneY && mouseY < doneY + doneH;
        guiGraphics.fill(doneX, doneY, doneX + doneW, doneY + doneH, hoverDone ? BTN_BG_PRIMARY : BTN_BG);
        guiGraphics.fill(doneX, doneY, doneX + doneW, doneY + 1, CARD_BORDER);
        guiGraphics.fill(doneX, doneY + doneH - 1, doneX + doneW, doneY + doneH, CARD_BORDER);
        guiGraphics.fill(doneX, doneY, doneX + 1, doneY + doneH, CARD_BORDER);
        guiGraphics.fill(doneX + doneW - 1, doneY, doneX + doneW, doneY + doneH, CARD_BORDER);
        String doneLabel = "✓ 完成";
        guiGraphics.drawString(this.font, doneLabel,
            doneX + (doneW - this.font.width(doneLabel)) / 2, doneY + 5,
            hoverDone ? 0xFFFFFFFF : LABEL_COLOR, false);
        this.settingsDoneRect = new int[]{doneX, doneY, doneW, doneH};

        // 状态消息 (底部左侧)
        if (this.settingsStatus != null && this.settingsStatusTick > 0) {
            guiGraphics.drawString(this.font, this.settingsStatus,
                rx + 4, doneY + 5, this.settingsStatusColor, false);
        }
    }

    /**
     * 画一个 cycle 行 - 4 状态循环按钮, 显示当前 mode + 副标题解释.
     * 跟 drawToggleRow 不同: 没有复选框, 整行点击循环切换.
     * 视觉:
     *   [第 1 行] 标题  ............ [大写当前 mode 标签]
     *   [第 2 行] 副标题 (简短描述, 浅灰)
     *   [第 3 行] (OFF 隐藏, 其他) 4 个小图标 (OFF / FALL / RAIN / THROW) 标识当前选中的
     */
    private void drawModeCycleRow(GuiGraphics g, int rx, int rw, int y, int mx, int my) {
        PlayerPreferences p = PlayerPreferences.get();
        BuildAnimationMode mode = p.getBuildAnimationMode();
        int w = rw - 12;
        int x = rx + 6;
        int h = 32;  // 比 toggle 高, 容纳 3 行 (标题+副标题+4个小标识)
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        int bg = hover ? BTN_BG_HOVER : BTN_BG;
        g.fill(x, y, x + w, y + h, bg);
        g.fill(x, y, x + w, y + 1, CARD_BORDER);
        g.fill(x, y + h - 1, x + w, y + h, CARD_BORDER);
        g.fill(x, y, x + 1, y + h, CARD_BORDER);
        g.fill(x + w - 1, y, x + w, y + h, CARD_BORDER);

        // 第 1 行: 标题
        g.drawString(this.font, "建造动画模式", x + 6, y + 3, LABEL_COLOR, false);
        // 当前 mode 标签 (右对齐, 大字+颜色, 让玩家一眼看清选了哪个)
        String modeLabel;
        int modeColor;
        switch (mode) {
            case OFF   -> { modeLabel = "OFF";   modeColor = 0xFF888888; }
            case FALL  -> { modeLabel = "FALL";  modeColor = 0xFF55AA55; }
            case RAIN  -> { modeLabel = "RAIN";  modeColor = 0xFF5599FF; }
            case THROW -> { modeLabel = "THROW"; modeColor = 0xFFAA55FF; }
            default    -> { modeLabel = "?";     modeColor = 0xFFFFFFFF; }
        }
        g.drawString(this.font, "§l" + modeLabel, x + w - 8 - this.font.width("§l" + modeLabel), y + 2, modeColor, false);

        // 第 2 行: 副标题 (按 mode 选不同描述)
        String subtitle = switch (mode) {
            case OFF   -> "§7(瞬建, 不放动画, 跟原版 prefab 一样快, 可能卡顿)";
            case FALL  -> "§a(每个方块从空中 8 格高处竖直掉下, 1 块/tick, ~50s/1000块)";
            case RAIN  -> "§b(每个方块从更高的空中散点掉下, 起点横向 ±2 格随机, 1 块/tick)";
            case THROW -> "§d(每个方块从建筑外圈 ±10 格抛过来, 抛物线轨迹, 1 块/tick)";
            default    -> "§7(?)";
        };
        g.drawString(this.font, subtitle, x + 6, y + 13, HINT_COLOR, false);

        // 第 3 行: 4 个 mode 标识 + 选中高亮
        int dotY = y + 23;
        int dotR = 3;
        int dotsStartX = x + 6;
        int dotSpacing = (w - 12) / 4;
        String[] modeNames = {"OFF", "FALL", "RAIN", "THROW"};
        int[] modeColors = {0xFF888888, 0xFF55AA55, 0xFF5599FF, 0xFFAA55FF};
        for (int i = 0; i < 4; i++) {
            int dotX = dotsStartX + i * dotSpacing + dotR;
            boolean isSelected = (i == mode.ordinal());
            int color = isSelected ? modeColors[i] : 0xFFCCCCCC;
            // 实心圆
            g.fill(dotX - dotR, dotY - dotR, dotX + dotR + 1, dotY + dotR + 1, color);
            if (isSelected) {
                // 选中加白边
                g.fill(dotX - dotR, dotY - dotR, dotX + dotR + 1, dotY - dotR + 1, 0xFFFFFFFF);
                g.fill(dotX - dotR, dotY + dotR, dotX + dotR + 1, dotY + dotR + 1, 0xFFFFFFFF);
                g.fill(dotX - dotR, dotY - dotR, dotX - dotR + 1, dotY + dotR + 1, 0xFFFFFFFF);
                g.fill(dotX + dotR, dotY - dotR, dotX + dotR + 1, dotY + dotR + 1, 0xFFFFFFFF);
            }
            // mode 名称 (圆点右侧)
            int textColor = isSelected ? modeColors[i] : 0xFFAAAAAA;
            g.drawString(this.font, modeNames[i], dotX + dotR + 3, dotY - 3, textColor, false);
        }

        // 缓存 rect 给 mouseClicked
        this.settingsModeCycleRect = new int[]{x, y, w, h};
    }

    /**
     * 画一个 toggle 行 (复选框 + 标题 + 副标题 + 状态).
     * @param rectArr 共享的 int[][] 缓存, 索引 idx 处放本行的 [x, y, w, h]
     */
    private void drawToggleRow(GuiGraphics g, int rx, int rw, int y, int mx, int my,
                               String title, String subtitle, boolean enabled, boolean checked,
                               int[][] rectArr, int idx) {
        int w = rw - 12;
        int x = rx + 6;
        // 行高 24: title @ y+3, subtitle @ y+13 (10px 间隔), 行底留 4px padding.
        // 之前 h=16 + 副标题 y+11 紧贴下边, 跟下一行 title 重叠. 现在 24px 留足空间.
        int h = 24;
        boolean hover = enabled && mx >= x && mx < x + w && my >= y && my < y + h;
        int bg = !enabled ? 0xFFEEEEEE : (hover ? BTN_BG_HOVER : BTN_BG);
        g.fill(x, y, x + w, y + h, bg);
        g.fill(x, y, x + w, y + 1, CARD_BORDER);
        g.fill(x, y + h - 1, x + w, y + h, CARD_BORDER);
        g.fill(x, y, x + 1, y + h, CARD_BORDER);
        g.fill(x + w - 1, y, x + w, y + h, CARD_BORDER);
        // 复选框 (居中靠上, 跟 title 同行)
        int cb = 10;
        int cbX = x + 4, cbY = y + 3;
        g.fill(cbX, cbY, cbX + cb, cbY + cb, 0xFFFFFFFF);
        g.fill(cbX, cbY, cbX + cb, cbY + 1, 0xFF333333);
        g.fill(cbX, cbY + cb - 1, cbX + cb, cbY + cb, 0xFF333333);
        g.fill(cbX, cbY, cbX + 1, cbY + cb, 0xFF333333);
        g.fill(cbX + cb - 1, cbY, cbX + cb, cbY + cb, 0xFF333333);
        if (checked) {
            g.fill(cbX + 2, cbY + 5, cbX + 4, cbY + 6, 0xFF55AA55);
            g.fill(cbX + 3, cbY + 6, cbX + 4, cbY + 8, 0xFF55AA55);
        }
        int titleColor = enabled ? LABEL_COLOR : 0xFF888888;
        g.drawString(this.font, title, cbX + cb + 6, y + 3, titleColor, false);
        int statusColor = checked ? 0x55AA55 : 0x888888;
        String statusText = checked ? "§a✓ ON" : "§8✗ OFF";
        g.drawString(this.font, statusText, x + w - 30, y + 3, statusColor, false);
        if (subtitle != null && !subtitle.isEmpty()) {
            // 副标题放第二行, 跟 title 隔 10px, 行底还有 1-2px padding, 不会跟下一行重叠.
            g.drawString(this.font, subtitle, cbX + cb + 6, y + 13, HINT_COLOR, false);
        }
        if (rectArr != null) rectArr[idx] = new int[]{x, y, w, h};
    }

    /**
     * 画一个滑条行 (标签 + 滑条, 标签在第一行, 滑条在第二行).
     * @param sliderId 1 = 预览, 2 = 建造
     * @return 滑条之后的下一个 y
     */
    private int drawSliderRow(GuiGraphics g, int rx, int rw, int y, int mx, int my,
                              String title, boolean disabled, int currentPct,
                              boolean showOpHint,
                              int[] knobOut, int[] trackOut, int sliderId) {
        int x = rx + 6;
        int w = rw - 12;
        int titleColor = disabled ? 0xFF888888 : LABEL_COLOR;
        g.drawString(this.font, title, x, y, titleColor, false);
        String valueStr = (disabled ? "§8" : "§f") + currentPct + "%";
        if (showOpHint) valueStr += "  §c(OP only)";
        int valW = this.font.width(valueStr);
        g.drawString(this.font, valueStr, x + w - valW, y, 0xFFFFFFFF, false);
        y += 11;
        int trackX = x;
        int trackY = y + 2;
        int trackW = w;
        int trackH = 6;
        int fillW = (int) (trackW * (currentPct / 100.0));
        g.fill(trackX, trackY, trackX + trackW, trackY + trackH, 0xFFB0B0B0);
        g.fill(trackX, trackY, trackX + trackW, trackY + 1, 0xFF555555);
        g.fill(trackX, trackY + trackH - 1, trackX + trackW, trackY + trackH, 0xFF555555);
        g.fill(trackX, trackY, trackX + fillW, trackY + trackH, disabled ? 0xFF999999 : 0xFF6A8FB5);
        int knobSize = 12;
        int knobX = trackX + fillW - knobSize / 2;
        if (knobX < trackX) knobX = trackX;
        if (knobX + knobSize > trackX + trackW) knobX = trackX + trackW - knobSize;
        int knobY = trackY - (knobSize - trackH) / 2;
        boolean hoverKnob = mx >= knobX && mx < knobX + knobSize
            && my >= knobY && my < knobY + knobSize;
        int knobColor = disabled ? 0xFFCCCCCC : (hoverKnob ? 0xFFFFFFFF : 0xFFE0E0E0);
        g.fill(knobX, knobY, knobX + knobSize, knobY + knobSize, knobColor);
        g.fill(knobX, knobY, knobX + knobSize, knobY + 1, 0xFF555555);
        g.fill(knobX, knobY + knobSize - 1, knobX + knobSize, knobY + knobSize, 0xFF555555);
        g.fill(knobX, knobY, knobX + 1, knobY + knobSize, 0xFF555555);
        g.fill(knobX + knobSize - 1, knobY, knobX + knobSize, knobY + knobSize, 0xFF555555);
        if (knobOut != null) {
            knobOut[0] = knobX; knobOut[1] = knobY; knobOut[2] = knobSize; knobOut[3] = knobSize;
        }
        if (trackOut != null) {
            trackOut[0] = trackX; trackOut[1] = trackY; trackOut[2] = trackW; trackOut[3] = trackH;
        }
        // (原 this.settingsSliderId = sliderId; 死代码, 字段从未被读取, 已删除)
        return y + trackH + 4;
    }

    /** 画一个按钮行 (居中标签). */
    private void drawButtonRow(GuiGraphics g, int x, int y, int w, int h, int mx, int my,
                               String label, int[] rectOut) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        g.fill(x, y, x + w, y + h, hover ? BTN_BG_HOVER : BTN_BG);
        g.fill(x, y, x + w, y + 1, CARD_BORDER);
        g.fill(x, y + h - 1, x + w, y + h, CARD_BORDER);
        g.fill(x, y, x + 1, y + h, CARD_BORDER);
        g.fill(x + w - 1, y, x + w, y + h, CARD_BORDER);
        g.drawString(this.font, label,
            x + (w - this.font.width(label)) / 2, y + 6, LABEL_COLOR, false);
        if (rectOut != null) {
            rectOut[0] = x; rectOut[1] = y; rectOut[2] = w; rectOut[3] = h;
        }
    }

    /** 推进滑条拖拽: 鼠标 x 位置 → 1..100 百分比. */
    private int sliderXToPercent(int mouseX, int trackX, int trackW) {
        if (trackW <= 0) return 1;
        double ratio = (double) (mouseX - trackX) / trackW;
        if (ratio < 0) ratio = 0;
        if (ratio > 1) ratio = 1;
        return Math.max(1, Math.min(100, (int) Math.round(ratio * 100)));
    }

    private void setSettingsStatus(String msg, int color) {
        this.settingsStatus = msg;
        this.settingsStatusColor = color;
        this.settingsStatusTick = 120;
    }

    // ============================================================
    // 输入
    // ============================================================
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int mx = (int) mouseX, my = (int) mouseY;
        // 1) 左侧 tab 点击
        int tx = this.grayBoxX + 2;
        int ty = this.grayBoxY + 2;
        int tw = TABS_W;
        int th = TAB_H;
        if (mx >= tx && mx < tx + tw) {
            int idx = (my - ty) / th;
            if (idx >= 0 && idx < Tab.values().length) {
                Tab t = Tab.values()[idx];
                // 联动: KubeJS 没装不能切到"制作蓝图"
                if (t == Tab.MAKE_BLUEPRINT && !KubeJSIntegration.isLoaded()) {
                    return true;
                }
                switchTab(t);
                return true;
            }
        }
        // 2) 编辑 tab 卡片按钮点击
        if (this.currentTab == Tab.EDIT) {
            // 2a) 翻页按钮
            int[] pr = this.editPagingRects;
            if (pr[2] > 0 && pr[6] > 0) {
                // 上一页
                if (mx >= pr[0] && mx < pr[0] + pr[2] && my >= pr[1] && my < pr[1] + pr[3]) {
                    if (this.editPage > 1) {
                        this.editPage--;
                        return true;
                    }
                }
                // 下一页
                if (mx >= pr[4] && mx < pr[4] + pr[6] && my >= pr[5] && my < pr[5] + pr[7]) {
                    int totalPages = Math.max(1, (this.editFiltered.size() + this.editPageSize - 1) / this.editPageSize);
                    if (this.editPage < totalPages) {
                        this.editPage++;
                        return true;
                    }
                }
            }
            // 2b) 卡片 "查看" 按钮
            for (int i = 0; i < this.editViewBtnRects.size(); i++) {
                int[] r = this.editViewBtnRects.get(i);
                if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                    // 索引 i 对应的是 editFiltered 当前页的第 i 个, 需要换算到原始列表索引
                    int startIdx = (this.editPage - 1) * this.editPageSize;
                    int realIdx = startIdx + i;
                    if (realIdx >= 0 && realIdx < this.editFiltered.size()) {
                        openConstructionDetail(this.editFiltered.get(realIdx));
                    }
                    return true;
                }
            }
            // 2c) 卡片 "编辑" 按钮
            for (int i = 0; i < this.editEditBtnRects.size(); i++) {
                int[] r = this.editEditBtnRects.get(i);
                if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                    int startIdx = (this.editPage - 1) * this.editPageSize;
                    int realIdx = startIdx + i;
                    if (realIdx >= 0 && realIdx < this.editFiltered.size()) {
                        openEditBuilding(this.editFiltered.get(realIdx));
                    }
                    return true;
                }
            }
        }
        // 3) 设置 tab 交互
        if (this.currentTab == Tab.SETTINGS) {
            // 翻页按钮 (顶部右上角, 任意页都响应)
            int[] spr = this.settingsPagingRects;
            // 上一页
            if (spr[2] > 0 && mx >= spr[0] && mx < spr[0] + spr[2]
                && my >= spr[1] && my < spr[1] + spr[3] && this.settingsPage > 1) {
                this.settingsPage--;
                return true;
            }
            // 下一页
            if (spr[6] > 0 && mx >= spr[4] && mx < spr[4] + spr[6]
                && my >= spr[5] && my < spr[5] + spr[7] && this.settingsPage < SETTINGS_TOTAL_PAGES) {
                this.settingsPage++;
                return true;
            }
            // 挑战模式 toggle (idx=0)
            int[] tr0 = this.settingsToggleRect[0];
            if (tr0[2] > 0 && mx >= tr0[0] && mx < tr0[0] + tr0[2] && my >= tr0[1] && my < tr0[1] + tr0[3]) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null && !mc.player.hasPermissions(2)) {
                    if (mc.player != null) {
                        mc.player.sendSystemMessage(Component.literal(
                            com.prefab.addon.PrefabCustomAddon.tr("gui.settings.challenge_op_required"))
                            .withStyle(ChatFormatting.RED));
                    }
                    setSettingsStatus("§c需要 OP 权限", 0xFF5555);
                } else {
                    PlayerPreferences p = PlayerPreferences.get();
                    p.setConsumeMaterials(!p.consumeMaterials);
                    setSettingsStatus(p.consumeMaterials ? "§a挑战模式已开启" : "§7挑战模式已关闭", 0x55FF55);
                }
                return true;
            }
            // 自动检测依赖 toggle (idx=1) - 客户端个人设置, 不需 OP
            int[] tr1 = this.settingsToggleRect[1];
            if (tr1[2] > 0 && mx >= tr1[0] && mx < tr1[0] + tr1[2] && my >= tr1[1] && my < tr1[1] + tr1[3]) {
                PlayerPreferences p = PlayerPreferences.get();
                boolean newVal = !p.autoDepCheckOnOpen;
                p.autoDepCheckOnOpen = newVal;
                p.save();
                setSettingsStatus(newVal ? "§a打开建筑时自动检测依赖 已开启" : "§7自动检测依赖 已关闭", 0x55FF55);
                return true;
            }
            // 强制启用 KubeJS 联动 toggle (idx=2) - 客户端个人设置
            // 勾上后 KubeJSIntegration.isLoaded() 始终返回 true, "制作蓝图" tab 立即显示
            int[] tr2 = this.settingsToggleRect[2];
            if (tr2[2] > 0 && mx >= tr2[0] && mx < tr2[0] + tr2[2] && my >= tr2[1] && my < tr2[1] + tr2[3]) {
                PlayerPreferences p = PlayerPreferences.get();
                boolean newVal = !p.forceKubeJSTab;
                p.forceKubeJSTab = newVal;
                p.save();
                com.prefab.addon.integration.KubeJSIntegration.setForceEnabled(newVal);
                com.prefab.addon.integration.KubeJSIntegration.invalidateCache();
                setSettingsStatus(PrefabCustomAddon.tr(newVal
                    ? "gui.settings.force_kubejs_on"
                    : "gui.settings.force_kubejs_off"), 0x55FF55);
                return true;
            }
            // 建造动画 mode cycle 按钮 - 客户端个人设置, 不需 OP
            // 点击循环: OFF → FALL → RAIN → THROW → OFF.
            int[] cr2 = this.settingsModeCycleRect;
            if (cr2[2] > 0 && mx >= cr2[0] && mx < cr2[0] + cr2[2] && my >= cr2[1] && my < cr2[1] + cr2[3]) {
                PlayerPreferences p = PlayerPreferences.get();
                BuildAnimationMode next = p.getBuildAnimationMode().next();
                p.setBuildAnimationMode(next);
                String label = switch (next) {
                    case OFF   -> "§7OFF (瞬建, 不放动画)";
                    case FALL  -> "§aFALL (竖直下落, 1 块/tick)";
                    case RAIN  -> "§bRAIN (方块雨, 起点随机偏移)";
                    case THROW -> "§dTHROW (四周抛过来, 抛物线)";
                };
                setSettingsStatus("§f建造动画: " + label, 0x55FF55);
                return true;
            }
            // (删除: 建造 / 预览速度滑条, 默认 1 tick 放完所有方块, 开启下落动画时强制 1%/tick)
            // 打开拓展包文件夹
            int[] fr = this.settingsOpenFolderRect;
            if (fr[2] > 0 && mx >= fr[0] && mx < fr[0] + fr[2] && my >= fr[1] && my < fr[1] + fr[3]) {
                try {
                    FolderOpener.openExtensionFolder();
                    setSettingsStatus("§a已打开拓展包文件夹", 0x55FF55);
                } catch (Exception ex) {
                    PrefabCustomAddon.LOGGER.error("[SETTINGS] 打开拓展包文件夹失败", ex);
                    setSettingsStatus("§c打开失败: " + ex.getMessage(), 0xFF5555);
                }
                return true;
            }
            // 同步服务器拓展包
            int[] sr = this.settingsSyncServerRect;
            if (sr[2] > 0 && mx >= sr[0] && mx < sr[0] + sr[2] && my >= sr[1] && my < sr[1] + sr[3]) {
                Minecraft mc = Minecraft.getInstance();
                try {
                    com.prefab.addon.network.ServerPackSyncClient.getInstance().requestResync();
                    if (mc.player != null) {
                        mc.player.sendSystemMessage(Component.literal(
                            com.prefab.addon.PrefabCustomAddon.tr("gui.settings.sync_requested"))
                            .withStyle(ChatFormatting.AQUA));
                    }
                    setSettingsStatus("§a同步请求已发送", 0x55FF55);
                } catch (Throwable t) {
                    PrefabCustomAddon.LOGGER.error("[SETTINGS] 同步服务器建筑失败", t);
                    setSettingsStatus("§c同步失败: " + t.getMessage(), 0xFF5555);
                }
                return true;
            }
            // 重置偏好 (清空收藏 + 重置挑战模式 + 重置动画)
            int[] rr = this.settingsResetRect;
            if (rr[2] > 0 && mx >= rr[0] && mx < rr[0] + rr[2] && my >= rr[1] && my < rr[1] + rr[3]) {
                try {
                    PlayerPreferences p = PlayerPreferences.get();
                    int removedFavs = p.favoriteKeys != null ? p.favoriteKeys.size() : 0;
                    p.favoriteKeys = new java.util.ArrayList<>();
                    p.consumeMaterials = false;
                    p.autoDepCheckOnOpen = false;
                    p.buildAnimationMode = BuildAnimationMode.OFF;
                    p.save();
                    setSettingsStatus("§a已重置偏好 (清空 " + removedFavs + " 个收藏)", 0x55FF55);
                } catch (Throwable t) {
                    setSettingsStatus("§c重置失败: " + t.getMessage(), 0xFF5555);
                }
                return true;
            }
            // 完成
            int[] dr = this.settingsDoneRect;
            if (dr[2] > 0 && mx >= dr[0] && mx < dr[0] + dr[2] && my >= dr[1] && my < dr[1] + dr[3]) {
                Minecraft.getInstance().setScreen(null);
                return true;
            }
        }
        // 4) 制作蓝图 tab 交互
        if (this.currentTab == Tab.MAKE_BLUEPRINT) {
            // 4a) 选建筑文件 (.nbt) - 用 pickNbtForBlueprint 回调直接更新, 不打开创建建筑界面
            int[] bf = this.mbSelectBuildingFileRect;
            if (bf[2] > 0 && mx >= bf[0] && mx < bf[0] + bf[2]
                && my >= bf[1] && my < bf[1] + bf[3]) {
                try {
                    // 先关当前 GUI (选 NBT 是异步模态对话框, 关掉主屏避免双层渲染)
                    final GuiExtensionPackEditor self = this;
                    self.mbStatus = PrefabCustomAddon.tr("gui.make_blueprint.poll.wait_nbt");
                    self.mbStatusTick = 200;
                    com.prefab.addon.client.gui.GuiCreateBuildingInfo.pickNbtForBlueprint(
                        (res, err) -> Minecraft.getInstance().execute(() -> {
                            // 回到自己 (主屏), 不弹创建建筑界面
                            if (Minecraft.getInstance().screen != self) {
                                Minecraft.getInstance().setScreen(self);
                            }
                            self.handleNbtPickedForBlueprint(res, err);
                        }));
                } catch (Throwable t) {
                    this.mbStatus = PrefabCustomAddon.tr("gui.make_blueprint.status.picker_err", t.getMessage());
                    this.mbStatusColor = 0xFF5555;
                    this.mbStatusTick = 150;
                }
                return true;
            }
            // 4a2) 游戏内选建筑 - 用 pickInGameForBlueprint 回调直接更新, 不打开创建建筑界面.
            // 注意: 必须先 setScreen(null) 关掉 editor 界面, 否则玩家看不见游戏世界,
            // 看不见选区预览 (问题: "点后没自动关闭这个界面, 要手动关闭后才能看到选择预览界面").
            int[] bi = this.mbSelectInGameRect;
            if (bi[2] > 0 && mx >= bi[0] && mx < bi[0] + bi[2]
                && my >= bi[1] && my < bi[1] + bi[3]) {
                try {
                    final GuiExtensionPackEditor self = this;
                    self.mbStatus = PrefabCustomAddon.tr("gui.make_blueprint.poll.wait_ingame");
                    self.mbStatusTick = 200;
                    // 先关掉当前屏, 玩家在游戏里选区 (左键角点1, 右键角点2, ALT 确认)
                    Minecraft.getInstance().setScreen(null);
                    com.prefab.addon.client.gui.GuiCreateBuildingInfo.pickInGameForBlueprint(
                        (res, err) -> Minecraft.getInstance().execute(() -> {
                            if (Minecraft.getInstance().screen != self) {
                                Minecraft.getInstance().setScreen(self);
                            }
                            self.handleInGamePickedForBlueprint(res, err);
                        }));
                } catch (Throwable t) {
                    this.mbStatus = PrefabCustomAddon.tr("gui.make_blueprint.status.picker_err", t.getMessage());
                    this.mbStatusColor = 0xFF5555;
                    this.mbStatusTick = 150;
                }
                return true;
            }
            // 4b) 选贴图按钮
            int[] tr = this.mbSelectTextureRect;
            if (tr[2] > 0 && mx >= tr[0] && mx < tr[0] + tr[2]
                && my >= tr[1] && my < tr[1] + tr[3]) {
                openTextureFileDialog();
                return true;
            }
            // 4c) 锁定 toggle
            int[] lr = this.mbLockedRect;
            if (lr[2] > 0 && mx >= lr[0] && mx < lr[0] + lr[2]
                && my >= lr[1] && my < lr[1] + lr[3]) {
                this.blueprintLocked = !this.blueprintLocked;
                this.mbStatus = this.blueprintLocked
                    ? PrefabCustomAddon.tr("gui.make_blueprint.status.locked_on")
                    : PrefabCustomAddon.tr("gui.make_blueprint.status.locked_off");
                this.mbStatusColor = 0x55FF55;
                this.mbStatusTick = 80;
                return true;
            }
            // 4d) 9 宫格点击: 左键 = 弹小搜索框选物品, 右键 = 清空
            int[] gr = this.mbRecipeGridRect;
            if (gr[2] > 0 && gr[3] > 0
                && mx >= gr[0] && mx < gr[0] + gr[2]
                && my >= gr[1] && my < gr[1] + gr[3]) {
                int cellSize = 22;
                int cellGap = 2;
                int relX = mx - gr[0];
                int relY = my - gr[1];
                int col = relX / (cellSize + cellGap);
                int row = relY / (cellSize + cellGap);
                if (col >= 0 && col < 3 && row >= 0 && row < 3) {
                    int idx = row * 3 + col;
                    if (button == 1) {
                        // 右键清空
                        this.recipeItems.set(idx, null);
                        this.mbStatus = PrefabCustomAddon.tr("gui.make_blueprint.status.cell_cleared", idx + 1);
                        this.mbStatusColor = 0xAAAAAA;
                        this.mbStatusTick = 60;
                    } else {
                        // 左键: 弹小搜索框
                        this.mbEditingCell = idx;
                        GuiItemSearchPopup.open(this, idx, currentItemId -> {
                            this.recipeItems.set(idx, currentItemId);
                            this.mbStatus = PrefabCustomAddon.tr("gui.make_blueprint.status.cell_set", idx + 1, currentItemId);
                            this.mbStatusColor = 0x55FF55;
                            this.mbStatusTick = 80;
                            this.mbEditingCell = -1;
                        });
                    }
                }
                return true;
            }
            // 4e) 滚动条点击: 进入拖动
            int[] sb = this.mbScrollBarRect;
            int[] thb = this.mbScrollThumbRect;
            if (sb[2] > 0 && mx >= sb[0] && mx < sb[0] + sb[2]
                && my >= sb[1] && my < sb[1] + sb[3]) {
                if (thb[2] > 0 && mx >= thb[0] && mx < thb[0] + thb[2]
                    && my >= thb[1] && my < thb[1] + thb[3]) {
                    // 点中拇指: 进入拖动
                    this.mbScrollDragging = true;
                    this.mbScrollDragOffset = my - thb[1];
                } else {
                    // 点空槽: 跳到该位置
                    int[] cr = contentRect();
                    int scrollH = (cr[1] + cr[3] - 32 - 14 - 4) - cr[1];
                    int maxScroll = Math.max(1, mbContentHeight - scrollH);
                    int barH = sb[3];
                    int thumbH = thb[2] > 0 ? thb[3] : 20;
                    int clickRatio = (my - sb[1] - thumbH / 2) / (barH - thumbH);
                    clickRatio = Math.max(0, Math.min(1, clickRatio));
                    this.mbScroll = (int) (clickRatio * maxScroll);
                }
                return true;
            }
            // 4f) 生成并注册
            int[] gr2 = this.mbGenerateRect;
            if (gr2[2] > 0 && mx >= gr2[0] && mx < gr2[0] + gr2[2]
                && my >= gr2[1] && my < gr2[1] + gr2[3]) {
                doGenerateBlueprint();
                return true;
            }
            // 4g) 打开 kubejs 文件夹
            int[] or = this.mbOpenFolderRect;
            if (or[2] > 0 && mx >= or[0] && mx < or[0] + or[2]
                && my >= or[1] && my < or[1] + or[3]) {
                openKubejsFolder();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 滚轮: 在 MAKE_BLUEPRINT tab 滚轮调 mbScroll. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.currentTab == Tab.MAKE_BLUEPRINT) {
            int maxScroll = Math.max(0, mbContentHeight
                - Math.max(0, (contentRect()[1] + contentRect()[3] - 32 - 14 - 4) - contentRect()[1]));
            int step = 18;
            if (scrollY > 0) this.mbScroll = Math.max(0, this.mbScroll - step);
            else if (scrollY < 0) this.mbScroll = Math.min(maxScroll, this.mbScroll + step);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** 鼠标拖动: 拖滚动条. */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.mbScrollDragging && this.currentTab == Tab.MAKE_BLUEPRINT) {
            int my = (int) mouseY;
            int[] cr = contentRect();
            int scrollH = (cr[1] + cr[3] - 32 - 14 - 4) - cr[1];
            int maxScroll = Math.max(1, mbContentHeight - scrollH);
            int[] sb = this.mbScrollBarRect;
            int[] th = this.mbScrollThumbRect;
            int barH = sb[3];
            int thumbH = th[2] > 0 ? th[3] : 20;
            int newThumbY = my - this.mbScrollDragOffset - sb[1];
            newThumbY = Math.max(0, Math.min(barH - thumbH, newThumbY));
            int newRatio = (barH - thumbH) <= 0 ? 0
                : (int) ((newThumbY / (double) (barH - thumbH)) * maxScroll);
            this.mbScroll = Math.max(0, Math.min(maxScroll, newRatio));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    /** 鼠标松开: 结束滚动条拖动. */
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.mbScrollDragging) {
            this.mbScrollDragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    // (删除: 滑条拖拽 mouseReleased / mouseDragged, 不再需要)
    // 现在默认: 1 tick 全放完; 开启建造动画 (FALL/RAIN/THROW) 时, 1 块/tick 建造.
    // 强制 1 块/tick 的逻辑在 AsyncBuildManager.processTick 里通过 task.isAnimationEnabled() 实现,
    //   客户端 BuildAnimationRenderer 按 buildAnimationMode 渲染不同轨迹.

    private void openConstructionDetail(LocalBuilding lb) {
        // 查看: 打开 GuiConstructionDetail (成熟的详细界面, 已带 3D 预览 Scene 渲染)
        // 不再走自写的 GuiExtensionConstructionDetail (那张只显示 2D PNG 缩略图, 没有 3D 旋转预览).
        if (lb.filePath == null || !Files.exists(lb.filePath)) {
            this.statusMessage = "建筑文件不存在: " + lb.id;
            this.statusColor = 0xFFAA55;
            this.statusTick = 120;
            return;
        }
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
            ci.setDependencies(java.util.Arrays.asList(deps.split("\\s*,\\s*")));
        }
        // 设置本地路径, GuiConstructionDetail 的 startAsyncParse 会懒加载 NBT
        ci.setLocalNbtPath(lb.filePath);
        if (lb.imagePath != null && Files.exists(lb.imagePath)) {
            ci.setLocalImagePath(lb.imagePath);
        }
        // "← 返回" 按钮: 编辑建筑 → 查看 → 返回, 应该回到编辑建筑 tab,
        // 而不是默认的 GuiExtensionPackBrowser (云端/下载那个界面).
        GuiConstructionDetail.open(ci, () -> GuiExtensionPackEditor.open(Tab.EDIT));
    }

    private void openEditBuilding(LocalBuilding lb) {
        // 编辑: 构造 BuildingWorkInfo, 调 GuiCreateBuildingInfo.open(packId, info, null) 走编辑模式
        // (不能调 openFullEditor(), 那会进入创建模式 = 玩家看到第 3 个图的空表单)
        if (lb.filePath == null || !Files.exists(lb.filePath)) {
            this.statusMessage = "建筑文件不存在: " + lb.id;
            this.statusColor = 0xFFAA55;
            this.statusTick = 120;
            return;
        }
        String deps = parseInfoTxt(lb.infoPath, "依赖模组");
        if (deps == null) deps = parseInfoTxt(lb.infoPath, "dependencies");
        if (deps == null) deps = "";
        // 分类: 从 .txt 读 "分类: xxx", 找不到默认 "未分类" (走空字符串, ConstructionInfo 解析时归到 "未分类")
        String category = parseInfoTxt(lb.infoPath, "分类");
        if (category == null) category = parseInfoTxt(lb.infoPath, "category");
        if (category == null) category = "";
        PackCreator.BuildingWorkInfo info = new PackCreator.BuildingWorkInfo(
            lb.id,
            lb.filePath,
            lb.imagePath,
            lb.infoPath,
            lb.getDisplayName(),
            lb.author == null ? "" : lb.author,
            formatSize(lb.fileSize),
            deps,
            lb.description == null ? "" : lb.description,
            "",  // icon 物品 id: 单文件模式用 PNG 不用, 留空
            category  // 分类: 让玩家能在编辑界面改
        );
        // packId 传空串: GuiCreateBuildingInfo 走 editor/edit 模式
        com.prefab.addon.client.gui.GuiCreateBuildingInfo.open("", info, null);
    }

    // ============================================================
    // 制作蓝图 tab — 工具方法
    // ============================================================

    /**
     * 弹出系统文件选择对话框, 玩家选完 .png 后设到 {@link #selectedTextureSource}.
     * <p>复用项目里的 {@link SystemFilePicker}: 优先 Windows 原生 OpenFileDialog (PowerShell 启动),
     * 回退 AWT FileDialog, 最后回退游戏内文件浏览器. 这样 PCL/HMCL headless 启动器也能用.</p>
     */
    private void openTextureFileDialog() {
        try {
            SystemFilePicker.openAsync(
                com.prefab.addon.PrefabCustomAddon.tr("gui.make_blueprint.pick_texture", "选蓝图贴图 (.png)"),
                java.util.Arrays.asList("png"),
                result -> {
                    if (result.isOk() && result.file != null && result.file.exists()) {
                        selectedTextureSource = result.file.toPath();
                        mbStatus = "§a已选贴图: §f" + selectedTextureSource.getFileName();
                        mbStatusColor = 0x55FF55;
                        mbStatusTick = 120;
                        PrefabCustomAddon.LOGGER.info("[MAKE-BLUEPRINT] texture: {}", selectedTextureSource);
                    } else if (result.isCancelled()) {
                        mbStatus = "§7取消选贴图";
                        mbStatusColor = 0xFFAA55;
                        mbStatusTick = 60;
                    } else {
                        mbStatus = "§c选贴图失败: §7" + (result.message == null ? "未知错误" : result.message);
                        mbStatusColor = 0xFF5555;
                        mbStatusTick = 100;
                    }
                });
        } catch (Throwable th) {
            PrefabCustomAddon.LOGGER.error("[MAKE-BLUEPRINT] SystemFilePicker 打开失败", th);
            mbStatus = "§c打开文件选择器失败: §7" + th.getMessage();
            mbStatusColor = 0xFF5555;
            mbStatusTick = 100;
        }
    }

    /**
     * 收集表单, 构造 {@link CustomBlueprintCodeGenerator.Spec}, 调
     * {@link CustomBlueprintCodeGenerator#generate}. 成功 / 失败都更新状态 + 聊天栏.
     */
    private void doGenerateBlueprint() {
        try {
            // 1) 收集表单 — 简化: 只让玩家填显示名, ns/id 自动生成
            String displayName = edBlueprintName == null ? "" : edBlueprintName.getValue().trim();
            // 命名空间固定 player_pack (避免和其他 mod 撞名)
            String subNamespace = "player_pack";
            // 物品 id 固定 player_blueprint, 已存在自动加 _2, _3, ...
            String itemId = "player_blueprint";
            itemId = autoIncrementItemIdIfTaken(subNamespace, itemId);

            // 2) 校验关键字段
            if (selectedBuilding == null) {
                mbStatus = PrefabCustomAddon.tr("gui.make_blueprint.status.need_building");
                mbStatusColor = 0xFF5555;
                mbStatusTick = 100;
                return;
            }
            if (selectedTextureSource == null || !Files.exists(selectedTextureSource)) {
                mbStatus = PrefabCustomAddon.tr("gui.make_blueprint.status.need_texture");
                mbStatusColor = 0xFF5555;
                mbStatusTick = 100;
                return;
            }
            if (recipeItems.stream().allMatch(s -> s == null || s.isBlank())) {
                mbStatus = PrefabCustomAddon.tr("gui.make_blueprint.status.need_recipe");
                mbStatusColor = 0xFF5555;
                mbStatusTick = 100;
                return;
            }

            // 3) 构造 Spec (validate() 会自动加 player_ 前缀)
            CustomBlueprintCodeGenerator.Spec spec = new CustomBlueprintCodeGenerator.Spec();
            spec.displayName = displayName.isEmpty() ? itemId : displayName;
            spec.namespace = subNamespace;
            spec.itemId = itemId;
            spec.packName = selectedBuilding.source == null ? "local" : selectedBuilding.source;
            spec.constructionId = selectedBuilding.id;
            spec.locked = this.blueprintLocked;
            spec.sourceTexture = selectedTextureSource;
            // 只塞非空格子, 9 个上限
            java.util.List<String> ri = new java.util.ArrayList<>();
            for (String s : recipeItems) {
                if (s != null && !s.isBlank()) ri.add(s.trim());
            }
            spec.recipeItems = ri;

            // 3.5) 自动加序号: 检查 itemId 对应的 startup script / recipe js / texture 是否已存在.
            // 存在就把 itemId 改成 "<原id>_2", 已存在再 "_3"... 避免 "我建了 3 次, 全是 blueprint_1, KubeJS 只看得到 1 个"
            spec.itemId = autoIncrementItemIdIfTaken(spec.namespace, spec.itemId);

            // 4) 调用生成器
            CustomBlueprintCodeGenerator.Result result = CustomBlueprintCodeGenerator.generate(spec);

            // 5) 状态 + 聊天栏反馈
            String nsItem = spec.namespace + ":" + spec.itemId;
            mbStatus = PrefabCustomAddon.tr("gui.make_blueprint.status.generated", nsItem);
            mbStatusColor = 0x55FF55;
            mbStatusTick = 200;
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

            // 不再回填输入框 — ns/id 已自动生成, 玩家不需要知道
        } catch (IllegalArgumentException iae) {
            mbStatus = PrefabCustomAddon.tr("gui.make_blueprint.status.param_err", iae.getMessage());
            mbStatusColor = 0xFF5555;
            mbStatusTick = 150;
            PrefabCustomAddon.LOGGER.error("[MAKE-BLUEPRINT] 参数校验失败", iae);
        } catch (Throwable t) {
            mbStatus = PrefabCustomAddon.tr("gui.make_blueprint.status.gen_fail", t.getMessage());
            mbStatusColor = 0xFF5555;
            mbStatusTick = 200;
            PrefabCustomAddon.LOGGER.error("[MAKE-BLUEPRINT] 生成失败", t);
            Minecraft mc2 = Minecraft.getInstance();
            if (mc2.player != null) {
                mc2.player.sendSystemMessage(Component.literal(
                    "§c[制作蓝图] 生成失败: " + t.getMessage()).withStyle(ChatFormatting.RED));
            }
        }
    }

    /** 打开 minecraft/kubejs 文件夹 (Desktop.open). 失败给个状态提示. */
    private void openKubejsFolder() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                mbStatus = "§cMC 实例未就绪";
                mbStatusColor = 0xFF5555;
                return;
            }
            Path kubejsDir = mc.gameDirectory.toPath().resolve("kubejs");
            if (!Files.exists(kubejsDir)) {
                Files.createDirectories(kubejsDir);
            }
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(kubejsDir.toFile());
                mbStatus = PrefabCustomAddon.tr("gui.make_blueprint.status.folder_opened");
                mbStatusColor = 0x55FF55;
                mbStatusTick = 100;
            } else {
                mbStatus = PrefabCustomAddon.tr("gui.make_blueprint.status.folder_no_desktop", kubejsDir.toString());
                mbStatusColor = 0xFFAA55;
                mbStatusTick = 200;
            }
        } catch (Throwable t) {
            mbStatus = PrefabCustomAddon.tr("gui.make_blueprint.status.folder_fail", t.getMessage());
            mbStatusColor = 0xFF5555;
            mbStatusTick = 150;
            PrefabCustomAddon.LOGGER.error("[MAKE-BLUEPRINT] 打开 kubejs 文件夹失败", t);
        }
    }

    /** 把 LocalBuilding 的字段填到 GuiCreateBuildingInfo static 字段, 包括 NBT 字节. */
    private void syncLocalBuildingToCb(LocalBuilding lb) {
        com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldIdValue = lb.id;
        com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldNameValue = lb.name == null ? "" : lb.name;
        com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldAuthorValue = lb.author == null ? "" : lb.author;
        // 依赖从 .txt 的 依赖: 字段解析 (如果没有就空)
        String deps = parseInfoTxt(lb.infoPath, "依赖");
        if (deps == null) deps = parseInfoTxt(lb.infoPath, "依赖模组");
        if (deps == null) deps = parseInfoTxt(lb.infoPath, "dependencies");
        if (deps == null) deps = "";
        com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldDepsValue = deps;
        com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldDescValue = lb.description == null ? "" : lb.description;
        com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldSizeValue = formatSize(lb.fileSize);
        com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldFormatValue = lb.fileExt == null ? "nbt" : lb.fileExt.replace(".", "");
        // 加载图标字节
        if (lb.imagePath != null && Files.exists(lb.imagePath)) {
            try {
                com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldIconData = Files.readAllBytes(lb.imagePath);
            } catch (Exception ex) {
                com.prefab.addon.client.gui.GuiCreateBuildingInfo.fieldIconData = null;
            }
        }
        // 加载 NBT 字节
        try {
            com.prefab.addon.client.gui.GuiCreateBuildingInfo.nbtData = Files.readAllBytes(lb.filePath);
            com.prefab.addon.client.gui.GuiCreateBuildingInfo.nbtPath = lb.filePath.toString();
        } catch (Exception ex) {
            this.statusMessage = "读取 NBT 失败: " + ex.getMessage();
            this.statusColor = 0xFF5555;
            this.statusTick = 120;
        }
    }

    /** 解析 .txt 元信息中的 "key: value", 找不到返回 null. */
    private static String parseInfoTxt(Path infoPath, String key) {
        if (infoPath == null) { com.prefab.addon.PrefabCustomAddon.LOGGER.info("[DIAG-PT] parseInfoTxt: infoPath=null key={}", key); return null; }
        if (!Files.exists(infoPath)) { com.prefab.addon.PrefabCustomAddon.LOGGER.info("[DIAG-PT] parseInfoTxt: file not exist: {} key={}", infoPath, key); return null; }
        try {
            String content = Files.readString(infoPath);
            // 剥 UTF-8 BOM (Windows 记事本常带, 不剥会让第一行 key 带 \uFEFF 匹配不上)
            if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
                content = content.substring(1);
            }
            com.prefab.addon.PrefabCustomAddon.LOGGER.info("[DIAG-PT] parseInfoTxt: file={} key={} content={}",
                infoPath.getFileName(), key, content.replace("\n", "\\n"));
            for (String line : content.split("\\r?\\n")) {
                String[] kv = line.split("[:：]", 2);
                if (kv.length != 2) continue;
                if (kv[0].trim().equalsIgnoreCase(key)) return kv[1].trim();
            }
        } catch (Exception e) {
            com.prefab.addon.PrefabCustomAddon.LOGGER.warn("[DIAG-PT] parseInfoTxt exception: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 缓存的建筑预览图: ResourceLocation + 真实尺寸 (避免 blit 用错 texW/texH 失败).
     */
    private static final class CachedImage {
        final ResourceLocation loc;
        final int width;
        final int height;
        CachedImage(ResourceLocation l, int w, int h) { loc = l; width = w; height = h; }
    }

    /**
     * 把配方格 item id 字符串 (例如 {@code minecraft:diamond} 或
     * {@code {id:"minecraft:diamond",Count:3b}}) 解析为 {@link ItemStack}.
     * 解析失败返回 null (走文字 fallback).
     */
    private static ItemStack parseItemStack(String id) {
        if (id == null || id.isBlank()) return null;
        String s = id.trim();
        // 1) 简写形式: namespace:path
        try {
            if (s.startsWith("{") && s.endsWith("}")) {
                // 跳过 SNBT 复合 tag, 提取 id 字段
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
                    var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(key);
                    if (item != null) return new ItemStack(item);
                }
            }
        } catch (Throwable ignore) {}
        return null;
    }

    /**
     * 加载 LocalBuilding 的缩略图为 ResourceLocation, 并记下真实尺寸.
     * 失败返回 null (用占位符). 用缓存避免重复加载.
     */
    private final java.util.Map<String, CachedImage> imageCache = new java.util.HashMap<>();
    private CachedImage imageLocationFor(LocalBuilding lb) {
        if (lb.imagePath == null || !Files.exists(lb.imagePath)) return null;
        String key = lb.imagePath.toString();
        if (this.imageCache.containsKey(key)) {
            CachedImage cached = this.imageCache.get(key);
            // null 也缓存, 避免每次都尝试加载
            return cached;
        }
        try {
            byte[] bytes = Files.readAllBytes(lb.imagePath);
            // 1.21.1: NativeImage.read(InputStream) + DynamicTexture(NativeImage)
            com.mojang.blaze3d.platform.NativeImage img =
                com.mojang.blaze3d.platform.NativeImage.read(new java.io.ByteArrayInputStream(bytes));
            int w = img.getWidth();
            int h = img.getHeight();
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(
                "prefab_extension_editor", "buildings/" + lb.id + (lb.imageExt == null ? ".png" : lb.imageExt));
            net.minecraft.client.renderer.texture.DynamicTexture tex =
                new net.minecraft.client.renderer.texture.DynamicTexture(img);
            Minecraft.getInstance().getTextureManager().register(loc, tex);
            CachedImage ci = new CachedImage(loc, w, h);
            this.imageCache.put(key, ci);
            return ci;
        } catch (Exception ex) {
            PrefabCustomAddon.LOGGER.warn("[EDITOR] 加载建筑预览图失败 ({}): {}", lb.imagePath, ex.getMessage());
            // 缓存 null 避免反复尝试
            this.imageCache.put(key, null);
            return null;
        }
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return (bytes / 1024 / 1024) + " MB";
    }
}
