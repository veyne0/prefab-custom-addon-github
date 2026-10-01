package com.prefab.addon.terminal.client.gui;

import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Tab;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TabView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.prefab.addon.terminal.TerminalRegistry;
import com.prefab.addon.terminal.client.WallpaperManager;
import com.prefab.addon.terminal.client.camera.TerminalCameraManager;
import com.prefab.addon.terminal.client.camera.TerminalPhotoStore;
import com.prefab.addon.terminal.client.gamecenter.GameCenter;
import com.prefab.addon.terminal.device.TerminalDeviceHandler;
import com.prefab.addon.terminal.network.TerminalPayloads;
import com.prefab.addon.terminal.ui.TerminalUI;
import com.prefab.addon.config.CategoryManager;
import com.prefab.addon.client.gui.CustomStructureGui;
import com.prefab.addon.client.gui.GuiConstructionDetail;
import com.prefab.addon.client.gui.GuiCreateBuildingInfo;
import com.prefab.addon.client.gui.GuiExtensionPackEditor;
import com.prefab.addon.config.CategoryManager;
import com.prefab.addon.config.BuildAnimationMode;
import com.prefab.addon.config.PlayerPreferences;
import com.prefab.addon.extension.ConstructionInfo;
import com.prefab.addon.extension.ExtensionPackManager;
import com.prefab.addon.extension.LocalBuilding;
import com.prefab.addon.extension.LocalBuildingScanner;
import com.prefab.addon.work.RegionSelector;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.Util;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * 终端客户端应用层 — 只负责"纯客户端"的应用 tab 内容与 tab 生命周期.
 *
 * Menu UI 架构下的分工:
 *   - common 骨架 (TabView/主界面) 在 {@link TerminalUI} 双侧构建;
 *   - 本类经 {@link TerminalUI#clientSetup} 钩子在客户端 create 收尾时接到
 *     TabView 引用, 负责按需追加应用 tab (相册/浏览器/设置/用户);
 *   - 应用 tab 内容全部是客户端数据源 (bindDataSource/自绘/MCEF), 不注册任何
 *     SyncValue, 不影响双侧同步索引.
 *
 * 关闭 GUI (ScreenEvent.Closing): 保存已开 tab 偏好 (C2S 让服务端写终端物品) +
 * 释放嵌入式浏览器 + 注销客户端设备槽镜像.
 */
@EventBusSubscriber(modid = TerminalRegistry.HOST_MOD_ID, value = Dist.CLIENT)
public final class TerminalGui {
    private static final String DEFAULT_BROWSER_URL = "https://www.mcmod.cn/";
    /** 应用 id → 已开的 tab (偏好保存/去重). */
    private static final Map<String, Tab> appTabs = new HashMap<>();
    /** 全部存活的嵌入式浏览器面板 (弹窗可开多 tab), 关 tab / 关界面时逐个释放. */
    private static final List<BrowserElement> openBrowsers = new ArrayList<>();
    /** createBrowserContent 创建实例后暂存于此, 供建 tab 时接关闭钩子 (_handoff_). */
    @Nullable
    private static BrowserElement lastCreatedBrowser = null;
    /** 设置面板最近一次操作结果提示 (应用壁纸成功/失败). */
    private static String settingsMsg = "";
    /** 设置状态提示颜色 (绿成功 / 红失败). */
    private static int settingsColor = 0x55FF55;
    /** 设置应用当前分类 (0=建造, 1=桌面). */
    private static int settingsTab = 0;

    // 当前 GUI 的骨架引用 (uiOpen=false 时全部置 null, 钩子内先查 uiOpen)
    private static boolean uiOpen = false;
    @Nullable
    private static TabView tabView = null;
    @Nullable
    private static Tab homeTab = null;

    /** 相册浏览状态快照: 上次正在查看的照片文件名 (null = 网格页), 重开终端后回到查看器. */
    @Nullable
    private static String lastAlbumPhoto = null;

    /** 浏览器浏览状态快照: 最近访问的地址, 重开浏览器 tab 时恢复 (弹窗 tab 的跳转同样计入). */
    private static String lastBrowserUrl = DEFAULT_BROWSER_URL;

    /** 建筑选择状态: 当前选中分类 (null = 全部), 跨关开终端保留 (同 lastBrowserUrl, 分类被删时自动回退全部). */
    @Nullable
    private static String buildingsCategory = null;
    /** 建筑选择页: 当前页码 (0 起, 每页 8 张; 切分类归零) */
    private static int buildingsPage = 0;

    // "创建建筑" 应用状态 (跨关开终端保留: 游戏内选区需要关终端再重开)
    /** 当前侧栏标签 (0 = 外部建筑文件, 1 = 游戏内选择建筑, 2 = 分类管理). */
    private static int createTab = 0;
    /** 游戏内选区完成重开终端后, 自动选中"创建建筑" tab (一次性标志). */
    private static boolean createSelectOnReopen = false;
    /** 重开终端后自动进入 创建建筑-编辑建筑 tab (3D 详情页返回用). */
    private static boolean createEditTabOnReopen = false;
    /** 重开终端后自动选中"建筑选择"应用 tab (CustomStructureGui 返回用, 一次性). */
    private static boolean buildingsSelectOnReopen = false;
    /** 最近一次操作的状态提示 (null = 不显示). */
    @Nullable
    private static String createStatusMsg = null;
    private static int createStatusColor = 0x55FF55;
    /** 分类管理: 正在重命名的分类名 (null = 非重命名模式). */
    @Nullable
    private static String renameTargetCategory = null;
    /** 编辑建筑 tab: 搜索关键字 (空 = 全部显示). */
    private static String editSearchText = "";
    /** 编辑建筑 tab: 当前页码 (0 起, 每页 6 张; 搜索变化时归零) */
    private static int editCreatePage = 0;
    /** 编辑建筑 tab: 正在编辑信息的建筑 (null = 卡片网格视图). */
    @Nullable
    private static LocalBuilding editInfoTarget = null;

    /** 已卸载应用 id 集合 (客户端状态, 随终端物品 NBT "UninstalledApps" 持久化). */
    private static final Set<String> uninstalledApps = new HashSet<>();
    /** 主界面图标网格 (本次 GUI 会话, 安装/卸载后整体重建子元素刷新桌面). */
    @Nullable
    private static UIElement homeGrid = null;
    /** 浏览器 tab → 其 BrowserElement (卸载浏览器应用时统一 removeTab + 释放 Chromium 资源). */
    private static final Map<Tab, BrowserElement> browserTabs = new HashMap<>();

    private TerminalGui() {
    }

    /** @return 终端 GUI 当前是否打开 (游戏中心等外部 tick 驱动方据此暂停/清理). */
    public static boolean isUiOpen() {
        return uiOpen;
    }

    /** 重开终端后选中"建筑选择"应用 tab (CustomStructureGui 从终端打开、返回时调用, 一次性). */
    public static void selectBuildingsOnReopen() {
        buildingsSelectOnReopen = true;
    }

    /**
     * CustomStructureGui 详细页返回后要回到的应用 tab id (默认"建筑选择").
     * 从哪个应用打开详细页, 就在 openFromTerminal 前把它改成对应 app id,
     * 返回时才能回到原界面 (下载建筑应用反馈: 看完详细返回跑到了建筑选择).
     */
    private static String detailReturnAppId = "buildings";

    // ==================== 生命周期 (TerminalUI 钩子 / Screen 事件) ====================

    /** common 骨架建好后的客户端收尾 (TerminalUI.clientSetup 钩子). */
    public static void setupApps(TerminalUI.ClientContext ctx) {
        tabView = ctx.tabView();
        homeTab = ctx.homeTab();
        homeGrid = ctx.homeGrid();
        appTabs.clear();
        browserTabs.clear();
        lastCreatedBrowser = null;
        uiOpen = true;

        // 已卸载应用集合: 从终端物品 NBT 恢复, 并按此过滤主界面图标网格
        uninstalledApps.clear();
        uninstalledApps.addAll(readStringListPref(ctx.terminal(), "UninstalledApps"));
        // 应用中心是唯一恢复入口, 强制视为已安装 (防 NBT 异常数据把桌面清空变砖)
        uninstalledApps.remove("appstore");
        refreshHomeGrid();

        // 回收站是 common 常驻 tab (服务端绑定槽位), 未卸载时直接登记; 主界面图标点击走"已存在则选中"逻辑.
        // 回收站 tab 挂 × 关闭按钮: 仅客户端移除 tab (menu 槽位保留, 关终端时照常还物);
        // 关闭后点桌面图标显示提示 tab, 重新打开终端恢复. 已卸载则同 × 行为移除 tab.
        Tab recycleTab = ctx.recycleTab();
        if (recycleTab != null) {
            if (uninstalledApps.contains("recycle")) {
                tabView.removeTab(recycleTab);
            } else {
                appTabs.put("recycle", recycleTab);
                recycleTab.addChild(createTabCloseButton(recycleTab, null, "recycle"));
            }
        }

        // JEI 排除区: LDLib 的 JEI 桥会递归收集 UI 树的 appendExtraAreas 上报为 JEI 排除区,
        // 这里挂一个全屏矩形, JEI 物品面板在终端打开期间无处安放即整体隐藏.
        UIElement uiRoot = ctx.tabView().getParent();
        if (uiRoot != null) {
            uiRoot.addChild(new UIElement() {
                @Override
                public void appendExtraAreas(List<Rect2i> areas) {
                    var window = Minecraft.getInstance().getWindow();
                    areas.add(new Rect2i(0, 0, window.getGuiScaledWidth(), window.getGuiScaledHeight()));
                }
            });
        }

        // 网页 target=_blank 弹窗被 McefBridge 拦截后投递到这里: 开新终端 tab (真浏览器的新标签页语义)
        McefBridge.setPopupListener(url -> {
            if (uiOpen && tabView != null) {
                openBrowserTab(url);
            }
        });

        // 恢复上次打开的应用 tab (不抢选中, 主界面保持当前)
        for (String id : readOpenTabs(ctx.terminal())) {
            openAppById(id, false);
        }
        // 详细页返回: 回到打开它的那个应用 tab (建筑选择/下载建筑), 没开过就现场打开
        if (buildingsSelectOnReopen) {
            buildingsSelectOnReopen = false;
            String appId = detailReturnAppId == null ? "buildings" : detailReturnAppId;
            detailReturnAppId = "buildings"; // 一次性, 用完恢复默认
            Tab b = appTabs.get(appId);
            if (b == null || tabView == null || !tabView.getTabContents().containsKey(b)) {
                openAppById(appId, true);
            } else {
                tabView.selectTab(b);
            }
        }
        // 游戏内选区结束重开终端: 自动选中"创建建筑" tab, 让玩家直接看到回填的表单
        if (createSelectOnReopen || createEditTabOnReopen) {
            createSelectOnReopen = false;
            boolean editTab = createEditTabOnReopen;
            createEditTabOnReopen = false;
            Tab created = appTabs.get("create");
            if (created != null && tabView != null && tabView.getTabContents().containsKey(created)) {
                tabView.selectTab(created);
                if (editTab) {
                    createTab = 3; // 编辑建筑
                    editInfoTarget = null; // 防止停留在半编辑状态
                    GuiCreateBuildingInfo.restoreForTerminal(); // 半编辑中断: 恢复创建表单
                }
            }
        }
    }

    /** 主界面图标点击入口 (TerminalUI.appOpener 钩子). */
    public static void onAppIconClick(String appId) {
        if (!uiOpen || uninstalledApps.contains(appId)) {
            return;
        }
        // 相机: 不开 tab, 点图标直接进入相机模式
        if ("camera".equals(appId)) {
            enterCameraMode();
            return;
        }
        for (TerminalUI.AppDef app : TerminalUI.APPS) {
            if (app.id().equals(appId)) {
                openApp(app);
                return;
            }
        }
    }

    /** 关闭终端 GUI 并进入相机模式 (下一帧执行, 避免在 UI 事件分派中重入 setScreen). */
    private static void enterCameraMode() {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> {
            mc.setScreen(null);
            TerminalCameraManager.enter();
        });
    }

    /** ScreenEvent.Closing (客户端): 关终端 GUI 时保存 tab 偏好 + 释放资源. */
    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        // 客户端设备镜像只在终端 GUI 打开期间登记, 借此判定关的是不是终端
        if (!uiOpen || TerminalDeviceHandler.client() == null) {
            return;
        }
        uiOpen = false;

        // tab 偏好持久化: 服务端写回终端物品 CUSTOM_DATA, 下次打开随菜单快照恢复
        CompoundTag data = new CompoundTag();
        ListTag tabs = new ListTag();
        for (String id : appTabs.keySet()) {
            tabs.add(StringTag.valueOf(id));
        }
        data.put("OpenTabs", tabs);
        ListTag uninstalled = new ListTag();
        for (String id : uninstalledApps) {
            uninstalled.add(StringTag.valueOf(id));
        }
        data.put("UninstalledApps", uninstalled);
        TerminalPayloads.sendSavePrefs(data);

        appTabs.clear();
        browserTabs.clear();
        tabView = null;
        homeTab = null;
        homeGrid = null;
        lastCreatedBrowser = null;
        // 释放嵌入式浏览器的 Chromium 离屏资源 + 注销客户端设备槽镜像
        disposeAllBrowsers();
        TerminalBuildingThumbs.clearListeners();
        TerminalDeviceHandler.registerClient(null);
    }

    /** 从终端物品 CUSTOM_DATA 读字符串列表偏好 (onSavePrefs 服务端写入). */
    private static List<String> readStringListPref(@Nullable ItemStack terminal, String key) {
        List<String> out = new ArrayList<>();
        if (terminal == null || terminal.isEmpty()) {
            return out;
        }
        CustomData cd = terminal.get(DataComponents.CUSTOM_DATA);
        if (cd == null) {
            return out;
        }
        CompoundTag tag = cd.copyTag();
        if (tag.contains(key, Tag.TAG_LIST)) {
            ListTag tabs = tag.getList(key, Tag.TAG_STRING);
            for (int i = 0; i < tabs.size(); i++) {
                String id = tabs.getString(i);
                if (!id.isEmpty()) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    /** 从终端物品 CUSTOM_DATA 读已开 tab 偏好 (onSavePrefs 服务端写入). */
    private static List<String> readOpenTabs(@Nullable ItemStack terminal) {
        return readStringListPref(terminal, "OpenTabs");
    }

    // ==================== tab 管理 ====================

    private static void openAppById(String id, boolean select) {
        for (TerminalUI.AppDef app : TerminalUI.APPS) {
            if (app.id().equals(id)) {
                openApp(app, select);
                return;
            }
        }
    }

    /** 打开应用 tab: 已开则切换, 否则新建可关闭 tab 并选中. */
    private static void openApp(TerminalUI.AppDef app) {
        openApp(app, true);
    }

    /** 打开应用 tab; select=false 用于重开 GUI 时恢复 tab (不抢选中, 不切换视图). */
    private static void openApp(TerminalUI.AppDef app, boolean select) {
        // 相机无 tab (点图标直接进模式), 兼容旧版本保存的偏好里可能残留的 id
        if ("camera".equals(app.id())) {
            return;
        }
        // 已卸载的应用不开 tab (重开终端恢复 OpenTabs 时同样被拦)
        if (uninstalledApps.contains(app.id())) {
            return;
        }
        Tab existing = appTabs.get(app.id());
        if (existing != null && tabView != null && tabView.getTabContents().containsKey(existing)) {
            if (select) {
                tabView.selectTab(existing);
            }
            return;
        }
        UIElement content = createAppContent(app);
        BrowserElement b = lastCreatedBrowser;
        lastCreatedBrowser = null;
        Tab tab = addClosableTab(I18n.get(app.nameKey()), content, b == null ? null : () -> disposeBrowser(b), select, app.id());
        appTabs.put(app.id(), tab);
        if (b != null) {
            browserTabs.put(tab, b);
        }
    }

    /** 新建可关闭 tab (× 作为 Tab 子元素; 点击同时会选中该 tab, removeTab 后自动回落). */
    private static Tab addClosableTab(String title, UIElement content, @Nullable Runnable onClose,
                                      boolean select, @Nullable String appId) {
        Tab tab = new Tab().setText(title, false);
        tab.addChild(createTabCloseButton(tab, onClose, appId));
        tabView.addTab(tab, content);
        if (select && tabView != null) {
            tabView.selectTab(tab);
        }
        return tab;
    }

    /** 为 Tab 生成 × 关闭按钮: removeTab + 清 appTabs 登记 + 下一帧跳回主界面. */
    private static Button createTabCloseButton(Tab tab, @Nullable Runnable onClose, @Nullable String appId) {
        Button close = new Button().setText("×");
        close.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        close.layout(l -> l.width(10.0f).height(10.0f).marginLeft(2.0f));
        close.setOnClick(e -> {
            if (tabView == null) {
                return;
            }
            tabView.removeTab(tab);
            if (onClose != null) {
                onClose.run();
            }
            if (appId != null) {
                appTabs.remove(appId);
            }
            // 关闭任意应用 tab 后显式跳回主界面.
            // 必须在下一帧 (Minecraft.tell) 执行, 因为 TabView 自身的选中状态在
            // removeTab 触发的同帧内不会立即稳定, 同步 selectTab 会被后续逻辑覆盖.
            if (homeTab != null && tab != homeTab) {
                Minecraft.getInstance().tell(() -> {
                    if (uiOpen && tabView != null && homeTab != null
                            && tabView.getTabContents().containsKey(homeTab)) {
                        tabView.selectTab(homeTab);
                    }
                });
            }
        });
        return close;
    }

    /** 弹窗 (target=_blank) 拦截入口: 为目标 URL 开独立浏览器 tab, 不进 appTabs 去重表. */
    private static void openBrowserTab(String url) {
        UIElement content = createBrowserContent(url);
        BrowserElement b = lastCreatedBrowser;
        lastCreatedBrowser = null;
        Tab tab = addClosableTab(I18n.get("gui.modern_terminal.app.browser"), content, b == null ? null : () -> disposeBrowser(b), true, null);
        if (b != null) {
            browserTabs.put(tab, b);
        }
    }

    // ==================== 应用中心 (安装/卸载所有应用) ====================

    /** 应用中心: 所有应用的卡片列表, 每张卡片可安装/卸载 (应用中心自身不可卸载). */
    /** 应用中心: 当前页码 (0 起, 每页 6 个) */
    private static int appStorePage = 0;

    private static UIElement createAppStoreContent() {
        UIElement content = new UIElement();
        content.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100.0f).heightPercent(100.0f)
                .gapAll(4.0f).paddingAll(4.0f));

        Runnable[] rebuildRoot = {null};
        rebuildRoot[0] = () -> {
            content.clearAllChildren();

            // 分页: 每页 6 个 (应用变多后一页放不下, 会把 tab 栏挤出去)
            final int pageSize = 6;
            int totalPages = (TerminalUI.APPS.size() + pageSize - 1) / pageSize;
            if (appStorePage >= totalPages) appStorePage = totalPages - 1;
            if (appStorePage < 0) appStorePage = 0;
            int from = appStorePage * pageSize;
            int to = Math.min(from + pageSize, TerminalUI.APPS.size());

            ScrollerView scroll = new ScrollerView();
            scroll.style(s -> s.backgroundTexture(Sprites.RECT_DARK));
            scroll.layout(l -> l.flexGrow(1.0f).widthPercent(100.0f));
            content.addChild(scroll);
            scroll.viewContainer.layout(l -> l.flexDirection(FlexDirection.COLUMN)
                    .gapAll(4.0f).paddingAll(4.0f));

            for (int i = from; i < to; i++) {
                scroll.addScrollViewChild(appStoreCard(TerminalUI.APPS.get(i), rebuildRoot[0]));
            }

            // 翻页条 (只有一页时不显示)
            if (totalPages > 1) {
                UIElement pageBar = new UIElement();
                pageBar.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f)
                        .height(14.0f).gapAll(4.0f));
                Button prev = new Button().setText(I18n.get("gui.modern_terminal.page.prev"), false);
                prev.layout(l -> l.width(40.0f).heightPercent(100.0f));
                prev.setOnClick(e -> {
                    if (appStorePage > 0) {
                        appStorePage--;
                        rebuildRoot[0].run();
                    }
                });
                pageBar.addChild(prev);
                Label pageLabel = new Label();
                pageLabel.setText(I18n.get("gui.modern_terminal.page.info",
                        appStorePage + 1, totalPages), false);
                pageLabel.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textColor(0xAAAAAA));
                pageLabel.layout(l -> l.flexGrow(1.0f).heightPercent(100.0f));
                pageBar.addChild(pageLabel);
                Button next = new Button().setText(I18n.get("gui.modern_terminal.page.next"), false);
                next.layout(l -> l.width(40.0f).heightPercent(100.0f));
                next.setOnClick(e -> {
                    if (appStorePage < totalPages - 1) {
                        appStorePage++;
                        rebuildRoot[0].run();
                    }
                });
                pageBar.addChild(next);
                content.addChild(pageBar);
            }
        };
        rebuildRoot[0].run();
        return content;
    }

    /** 单张应用卡片: 图标 + 名字 + 安装状态 + 安装/卸载按钮. */
    private static UIElement appStoreCard(TerminalUI.AppDef app, Runnable refresh) {
        boolean builtin = "appstore".equals(app.id());
        boolean installed = !uninstalledApps.contains(app.id());

        UIElement card = new UIElement();
        card.style(s -> s.backgroundTexture(Sprites.RECT_DARK));
        card.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER)
                .widthPercent(100.0f).height(34.0f).gapAll(6.0f).paddingAll(4.0f));

        UIElement icon = new UIElement();
        icon.layout(l -> l.width(24.0f).height(24.0f));
        icon.style(s -> s.backgroundTexture(new ItemStackTexture(app.icon().get())));
        card.addChild(icon);

        UIElement mid = new UIElement();
        mid.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1.0f).gapAll(1.0f));
        card.addChild(mid);

        Label name = new Label();
        name.setText(I18n.get(app.nameKey()), false);
        mid.addChild(name);

        Label status = new Label();
        status.setText(I18n.get(builtin ? "gui.modern_terminal.appstore.builtin"
                : installed ? "gui.modern_terminal.appstore.installed"
                : "gui.modern_terminal.appstore.not_installed"), false);
        status.textStyle(t -> t.fontSize(6.5f).textColor(builtin || installed ? 0x55FF55 : 0xFF5555));
        mid.addChild(status);

        if (!builtin) {
            Button btn = new Button().setText(I18n.get(installed
                    ? "gui.modern_terminal.appstore.uninstall"
                    : "gui.modern_terminal.appstore.install"), false);
            btn.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
            btn.layout(l -> l.width(48.0f).height(14.0f));
            btn.setOnClick(e -> {
                setAppInstalled(app.id(), !installed);
                refresh.run();
            });
            card.addChild(btn);
        }
        return card;
    }

    /** 设置应用安装状态并立即持久化 (服务端写终端物品 CUSTOM_DATA). */
    private static void setAppInstalled(String appId, boolean installed) {
        if ("appstore".equals(appId)) {
            return; // 应用中心不可卸载
        }
        if (installed) {
            uninstalledApps.remove(appId);
        } else {
            uninstalledApps.add(appId);
            closeAppTab(appId);
        }
        // 立即写 NBT (不等关 GUI): 防止卸载后游戏崩溃丢失状态
        CompoundTag data = new CompoundTag();
        ListTag list = new ListTag();
        for (String id : uninstalledApps) {
            list.add(StringTag.valueOf(id));
        }
        data.put("UninstalledApps", list);
        TerminalPayloads.sendSavePrefs(data);
        refreshHomeGrid();
    }

    /** 关闭某应用的 tab (同 × 点击: removeTab + 清登记; 浏览器需额外释放嵌入式实例). */
    private static void closeAppTab(String appId) {
        Tab tab = appTabs.remove(appId);
        if (tab == null || tabView == null || !tabView.getTabContents().containsKey(tab)) {
            return;
        }
        tabView.removeTab(tab);
        if ("browser".equals(appId)) {
            // 浏览器应用卸载: 该 tab 与弹窗 tab 的嵌入式浏览器全部释放
            // (正常 × 关闭走 onClose 钩子释放, 这里手动补齐)
            for (Map.Entry<Tab, BrowserElement> en : List.copyOf(browserTabs.entrySet())) {
                Tab bt = en.getKey();
                if (tabView.getTabContents().containsKey(bt)) {
                    tabView.removeTab(bt);
                }
                disposeBrowser(en.getValue());
                browserTabs.remove(bt);
            }
        }
    }

    /** 按安装状态重建主界面图标网格 (桌面刷新). */
    private static void refreshHomeGrid() {
        if (homeGrid == null) {
            return;
        }
        homeGrid.clearAllChildren();
        for (TerminalUI.AppDef app : TerminalUI.APPS) {
            if (!uninstalledApps.contains(app.id())) {
                homeGrid.addChild(TerminalUI.appCell(app));
            }
        }
    }

    // ==================== 应用内容 ====================

    /** 应用 tab 内容面板分发. */
    private static UIElement createAppContent(TerminalUI.AppDef app) {
        if (app.id().equals("buildings")) {
            return createBuildingsContent();
        }
        if (app.id().equals("create")) {
            return createCreateBuildingContent();
        }
        if (app.id().equals("user")) {
            return createUserContent();
        }
        if (app.id().equals("browser")) {
            return createBrowserContent(lastBrowserUrl); // 浏览状态保留: 回到上次访问的地址
        }
        if (app.id().equals("webget")) {
            return createWebGetContent();
        }
        if (app.id().equals("settings")) {
            return createSettingsContent();
        }
        if (app.id().equals("album")) {
            return createAlbumContent();
        }
        if (app.id().equals("game")) {
            return GameCenter.createContent();
        }
        if (app.id().equals("recycle")) {
            // 回收站 common tab 在本次会话中被 × 关闭: 槽位注册只在开终端时双侧执行, 无法就地重建
            return createRecycleClosedContent();
        }
        if (app.id().equals("appstore")) {
            return createAppStoreContent();
        }
        UIElement content = new UIElement();
        content.layout(l -> l.flexDirection(FlexDirection.COLUMN).alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER)
                .widthPercent(100.0f).heightPercent(100.0f).gapAll(4.0f));
        Label title = new Label();
        title.setText(I18n.get(app.nameKey()), false);
        title.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        content.addChild(title);
        Label note = new Label();
        note.setText(I18n.get("gui.modern_terminal.app.placeholder"), false);
        note.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        content.addChild(note);
        return content;
    }

    /** 回收站 common tab 被关闭后的占位内容: 提示重开终端恢复. */
    private static UIElement createRecycleClosedContent() {
        UIElement content = new UIElement();
        content.layout(l -> l.flexDirection(FlexDirection.COLUMN).alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER)
                .widthPercent(100.0f).heightPercent(100.0f).gapAll(4.0f));
        Label title = new Label();
        title.setText(I18n.get("gui.modern_terminal.app.recycle"), false);
        title.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        content.addChild(title);
        Label note = new Label();
        note.setText(I18n.get("gui.modern_terminal.recycle.closed"), false);
        note.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        content.addChild(note);
        return content;
    }

    // ==================== "下载建筑" app ====================
    // 原 GuiExtensionPackBrowser 下载 tab 的终端移植版: 网站建筑列表 + 下载按钮.
    // 数据层直接复用 PackDownloadManager (拉列表/拉图/流式下载/已下载判定), 服务器地址用 AddonConfig.

    private static List<com.prefab.addon.download.PackDownloadManager.BuildingInfo2> webGetList = null;
    private static boolean webGetLoading = false;
    private static String webGetError = null;
    /** 网站建筑缩略图缓存 (id -> 已注册的 DynamicTexture location). */
    private static final Map<String, net.minecraft.resources.ResourceLocation> WEBGET_IMAGES = new HashMap<>();
    private static final Set<String> WEBGET_IMG_LOADING = new HashSet<>();
    private static final Set<String> WEBGET_DOWNLOADING = new HashSet<>();
    private static final Map<String, double[]> WEBGET_PROGRESS = new HashMap<>();

    /** "下载建筑" 应用: 顶部状态行 + 刷新/打开网站, 下面是网站建筑卡片列表. */
    private static UIElement createWebGetContent() {
        UIElement root = new UIElement();
        root.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100.0f).heightPercent(100.0f)
                .paddingAll(4.0f).gapAll(4.0f));

        Label status = new Label();
        status.setText(I18n.get("gui.modern_terminal.webget.loading"), false);
        status.textStyle(t -> t.textColor(0xAAAAAA));
        status.layout(l -> l.flexGrow(1.0f).height(12.0f));

        Button btnOpen = new Button().setText(I18n.get("gui.modern_terminal.webget.open"), false);
        btnOpen.layout(l -> l.width(56.0f).height(14.0f));
        btnOpen.setOnClick(e -> {
            String url = com.prefab.addon.config.AddonConfig.getServerUrl();
            if (url == null || url.isEmpty()) {
                status.setText(I18n.get("gui.modern_terminal.webget.nourl"), false);
                status.textStyle(t -> t.textColor(0xFF5555));
                return;
            }
            try {
                Util.getPlatform().openUri(java.net.URI.create(url));
            } catch (Exception ignored) {
            }
        });

        Button btnRefresh = new Button().setText(I18n.get("gui.modern_terminal.webget.refresh"), false);
        btnRefresh.layout(l -> l.width(32.0f).height(14.0f));

        UIElement header = new UIElement();
        header.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f).gapAll(4.0f));
        header.addChild(status);
        header.addChild(btnRefresh);
        header.addChild(btnOpen);

        UIElement listHost = new UIElement();
        listHost.layout(l -> l.flexGrow(1.0f).widthPercent(100.0f));

        root.addChild(header);
        root.addChild(listHost);

        Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            listHost.clearAllChildren();
            // 状态行
            if (webGetError != null) {
                status.setText(I18n.get("gui.modern_terminal.webget.failed", webGetError), false);
                status.textStyle(t -> t.textColor(0xFF5555));
            } else if (webGetLoading) {
                status.setText(I18n.get("gui.modern_terminal.webget.loading"), false);
                status.textStyle(t -> t.textColor(0xAAAAAA));
            } else {
                status.setText(I18n.get("gui.modern_terminal.webget.count",
                        webGetList == null ? 0 : webGetList.size()), false);
                status.textStyle(t -> t.textColor(0xAAAAAA));
            }
            if (webGetList == null || webGetList.isEmpty()) {
                Label empty = new Label();
                empty.setText(I18n.get(webGetError == null && !webGetLoading
                        ? "gui.modern_terminal.webget.empty" : "gui.modern_terminal.webget.loading"), false);
                empty.textStyle(t -> t.textColor(0x888888).textAlignHorizontal(Horizontal.CENTER));
                empty.layout(l -> l.flexGrow(1.0f).widthPercent(100.0f));
                listHost.addChild(empty);
                return;
            }
            ScrollerView scroll = new ScrollerView();
            scroll.style(s -> s.backgroundTexture(Sprites.RECT_DARK));
            scroll.layout(l -> l.flexGrow(1.0f).widthPercent(100.0f));
            listHost.addChild(scroll);
            // 手动分行 (每行 2 张, flex wrap 在不定宽容器上不触发 — 同建筑选择页)
            scroll.viewContainer.layout(l -> l.flexDirection(FlexDirection.COLUMN).gapAll(10.0f));
            final int cols = 2;
            var list = webGetList;
            for (int i = 0; i < list.size(); i += cols) {
                UIElement row = new UIElement();
                row.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(10.0f));
                for (int j = i; j < Math.min(i + cols, list.size()); j++) {
                    row.addChild(webGetCard(list.get(j), rebuild[0]));
                }
                scroll.addScrollViewChild(row);
            }
        };

        btnRefresh.setOnClick(e -> fetchWebGetList(rebuild[0]));
        if (webGetList == null && !webGetLoading) {
            fetchWebGetList(rebuild[0]);
        }
        rebuild[0].run();
        return root;
    }

    /** 拉网站建筑列表 (异步), 完成后触发 rebuild 回主线程刷 UI. */
    private static void fetchWebGetList(Runnable rebuild) {
        if (webGetLoading) {
            return;
        }
        webGetLoading = true;
        webGetError = null;
        rebuild.run();
        com.prefab.addon.download.PackDownloadManager.getInstance().fetchBuildingListAsync()
                .thenAccept(list -> Minecraft.getInstance().execute(() -> {
                    webGetList = list;
                    webGetLoading = false;
                    webGetError = null;
                    if (list != null) {
                        for (var b : list) {
                            if (b != null && b.id != null && !WEBGET_IMAGES.containsKey(b.id)) {
                                loadWebGetImage(b, rebuild);
                            }
                        }
                    }
                    rebuild.run();
                }))
                .exceptionally(ex -> {
                    Throwable t = ex.getCause() != null ? ex.getCause() : ex;
                    Minecraft.getInstance().execute(() -> {
                        webGetLoading = false;
                        webGetError = t.getMessage();
                        rebuild.run();
                    });
                    return null;
                });
    }

    /** 异步拉建筑预览图并注册成 DynamicTexture (同浏览器下载 tab, 前缀不同避免互踢). */
    private static void loadWebGetImage(com.prefab.addon.download.PackDownloadManager.BuildingInfo2 b, Runnable rebuild) {
        if (b.id == null || !WEBGET_IMG_LOADING.add(b.id)) {
            return;
        }
        com.prefab.addon.download.PackDownloadManager.getInstance().fetchBuildingImageAsync(b)
                .thenAccept(data -> Minecraft.getInstance().execute(() -> {
                    WEBGET_IMG_LOADING.remove(b.id);
                    if (data == null || data.length == 0) {
                        return;
                    }
                    try {
                        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(
                                new java.io.ByteArrayInputStream(data));
                        if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) {
                            return;
                        }
                        int w = img.getWidth(), h = img.getHeight();
                        net.minecraft.client.renderer.texture.DynamicTexture tex =
                                new net.minecraft.client.renderer.texture.DynamicTexture(w, h, false);
                        tex.setFilter(false, false);
                        com.mojang.blaze3d.platform.NativeImage pixels = tex.getPixels();
                        for (int y = 0; y < h; y++) {
                            for (int x = 0; x < w; x++) {
                                int argb = img.getRGB(x, y);
                                pixels.setPixelRGBA(x, y,
                                        argb & 0xFF00FF00 | (argb & 0xFF0000) >> 16 | (argb & 0xFF) << 16);
                            }
                        }
                        tex.upload();
                        String safeId = b.id.toLowerCase().replaceAll("[^a-z0-9_.-]", "_");
                        WEBGET_IMAGES.put(b.id, Minecraft.getInstance().getTextureManager()
                                .register("prefab_webget_" + safeId, tex));
                        rebuild.run();
                    } catch (Exception ex) {
                        com.prefab.addon.PrefabCustomAddon.LOGGER.warn("[WEBGET] image upload failed for {}", b.id, ex);
                    }
                }))
                .exceptionally(ex -> {
                    Minecraft.getInstance().execute(() -> WEBGET_IMG_LOADING.remove(b.id));
                    return null;
                });
    }

    /** 流式下载到 prefab-download (带进度), 完成/失败后刷新卡片状态. */
    private static void startWebGetDownload(com.prefab.addon.download.PackDownloadManager.BuildingInfo2 b, Runnable rebuild) {
        if (b == null || b.id == null || WEBGET_DOWNLOADING.contains(b.id)) {
            return;
        }
        WEBGET_DOWNLOADING.add(b.id);
        WEBGET_PROGRESS.put(b.id, new double[]{0, 0, 0});
        rebuild.run();
        com.prefab.addon.download.PackDownloadManager.getInstance().downloadBuildingStreaming(b,
                new com.prefab.addon.download.PackDownloadManager.ProgressCallback() {
                    @Override
                    public void onStart(String packId) {
                    }

                    @Override
                    public void onProgress(long downloaded, long total, double percent) {
                        Minecraft.getInstance().execute(() ->
                                WEBGET_PROGRESS.put(b.id, new double[]{downloaded, total, percent}));
                    }

                    @Override
                    public void onComplete(java.nio.file.Path savedTo) {
                        Minecraft.getInstance().execute(() -> {
                            WEBGET_DOWNLOADING.remove(b.id);
                            WEBGET_PROGRESS.remove(b.id);
                            rebuild.run();
                        });
                    }

                    @Override
                    public void onError(String error) {
                        Minecraft.getInstance().execute(() -> {
                            WEBGET_DOWNLOADING.remove(b.id);
                            WEBGET_PROGRESS.remove(b.id);
                            webGetError = I18n.get("gui.modern_terminal.webget.err", b.name, error);
                            rebuild.run();
                        });
                    }
                });
    }

    /** 网站建筑卡片: 缩略图 + 名称/作者/大小/下载量 + 下载状态行. 已下载时点卡片开详细界面. */
    private static UIElement webGetCard(com.prefab.addon.download.PackDownloadManager.BuildingInfo2 b, Runnable rebuild) {
        // 卡片本体用 Button (UIElement 没有 setOnClick), hover 变亮给点击反馈
        Button card = new Button().noText();
        card.buttonStyle(s -> s.baseTexture(Sprites.RECT_DARK)
                .hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
        card.layout(l -> l.flexDirection(FlexDirection.ROW).width(158.0f).height(50.0f)
                .paddingAll(3.0f).gapAll(4.0f));

        // 缩略图 (加载中/失败 = 深色占位)
        UIElement thumb = new UIElement();
        thumb.layout(l -> l.width(42.0f).height(42.0f));
        net.minecraft.resources.ResourceLocation img = WEBGET_IMAGES.get(b.id);
        if (img != null) {
            thumb.style(s -> s.backgroundTexture(SpriteTexture.of(img)));
        } else {
            thumb.style(s -> s.backgroundTexture(Sprites.RECT_DARK));
        }
        card.addChild(thumb);

        UIElement info = new UIElement();
        info.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1.0f).gapAll(1.0f));

        Label name = new Label();
        name.setText(b.name == null || b.name.isEmpty() ? b.id : b.name, false);
        name.textStyle(t -> t.textColor(0xFFFFFF));
        name.layout(l -> l.widthPercent(100.0f).height(10.0f));
        info.addChild(name);

        Label meta = new Label();
        meta.setText("by " + (b.author == null ? "?" : b.author)
                + " · " + (b.fileExt == null ? ".nbt" : b.fileExt)
                + " · " + humanSize(b.fileSize)
                + (b.downloads > 0 ? " · ↓" + b.downloads : ""), false);
        meta.textStyle(t -> t.textColor(0x888888));
        meta.layout(l -> l.widthPercent(100.0f).height(9.0f));
        info.addChild(meta);

        // 状态行: 下载中 xx% / ✓ 已下载 / 下载按钮
        boolean downloaded = com.prefab.addon.download.PackDownloadManager.getInstance().isBuildingDownloaded(b);
        if (WEBGET_DOWNLOADING.contains(b.id)) {
            double[] p = WEBGET_PROGRESS.get(b.id);
            int pct = p == null ? 0 : (int) Math.round(p[2]);
            Label dl = new Label();
            dl.setText(I18n.get("gui.modern_terminal.webget.downloading", pct), false);
            dl.textStyle(t -> t.textColor(0xFFAA55));
            dl.layout(l -> l.widthPercent(100.0f).height(12.0f));
            info.addChild(dl);
        } else if (downloaded) {
            Label done = new Label();
            done.setText(I18n.get("gui.modern_terminal.webget.downloaded"), false);
            done.textStyle(t -> t.textColor(0x55FF55));
            done.layout(l -> l.widthPercent(100.0f).height(12.0f));
            info.addChild(done);
        } else {
            Button dl = new Button().setText(I18n.get("gui.modern_terminal.webget.download"), false);
            dl.layout(l -> l.width(48.0f).height(12.0f));
            dl.setOnClick(e -> startWebGetDownload(b, rebuild));
            info.addChild(dl);
        }
        // 已下载的卡片点击 → 打开建筑详细界面 (同建筑选择页: 3D 预览 + 建造)
        if (downloaded) {
            card.setOnClick(e -> {
                var player = Minecraft.getInstance().player;
                if (player == null) return;
                String baseName = (b.name == null || b.name.isEmpty() ? b.id : b.name)
                        .replaceAll("[\\\\/:*?\"<>|]", "_");
                String ext = b.fileExt == null || b.fileExt.isEmpty() ? ".nbt" : b.fileExt;
                ConstructionInfo target = null;
                for (ConstructionInfo c : collectBuildings()) {
                    if (c.getLocalNbtPath() != null) {
                        String fn = c.getLocalNbtPath().getFileName().toString();
                        // 命中主名或 _2/_3 去重后缀 (isBuildingDownloaded 同款规则)
                        if (fn.equals(baseName + ext) || (fn.startsWith(baseName + "_") && fn.endsWith(ext))) {
                            target = c;
                            break;
                        }
                    }
                    if (target == null && c.getId().equals(baseName)) {
                        target = c;
                    }
                }
                if (target != null) {
                    detailReturnAppId = "webget"; // 详细页返回时回到下载建筑 tab
                    CustomStructureGui.openFromTerminal(target, player.blockPosition().below());
                }
            });
        }
        card.addChild(info);
        return card;
    }

    /** 字节数转可读大小 (B/KB/MB). */
    private static String humanSize(long bytes) {
        if (bytes <= 0) {
            return "?";
        }
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1fKB", bytes / 1024.0);
        }
        return String.format("%.1fMB", bytes / 1024.0 / 1024.0);
    }

    /** "相册" 应用: 照片缩略图网格 + 点开大图预览/删除. 照片存 {gameDir}/modern_terminal/photos. */
    private static UIElement createAlbumContent() {
        UIElement content = new UIElement();
        content.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100.0f).heightPercent(100.0f).gapAll(4.0f).paddingAll(4.0f));

        UIElement[] pane = {null}; // 当前面板 (网格 / 查看器), 换页时整体重建

        // 刷新 = 整体重建 (数组引用, lambda 内自引用)
        Runnable[] rebuildRoot = {null};
        rebuildRoot[0] = () -> {
            content.clearAllChildren();
            List<String> list = TerminalPhotoStore.listPhotos();

            // 工具条: 数量 + 刷新
            UIElement toolbar = new UIElement();
            toolbar.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f).height(14.0f).gapAll(4.0f));
            Label count = new Label();
            count.setText(I18n.get("gui.modern_terminal.album.count", list.size()), false);
            count.layout(l -> l.flexGrow(1.0f).heightPercent(100.0f));
            toolbar.addChild(count);
            Button refresh = new Button().setText(I18n.get("gui.modern_terminal.album.refresh"), false);
            refresh.layout(l -> l.width(44.0f).height(14.0f));
            refresh.setOnClick(e -> rebuildRoot[0].run());
            toolbar.addChild(refresh);
            content.addChild(toolbar);

            UIElement body = new UIElement();
            body.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f).flexGrow(1.0f));
            content.addChild(body);
            pane[0] = body;

            if (list.isEmpty()) {
                Label empty = new Label();
                empty.setText(I18n.get("gui.modern_terminal.album.empty"), false);
                empty.layout(l -> l.alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER)
                        .widthPercent(100.0f).heightPercent(100.0f));
                body.addChild(empty);
                return;
            }

            // 缩略图网格 (wrap 换行)
            ScrollerView scroll = new ScrollerView();
            scroll.style(s -> s.backgroundTexture(Sprites.RECT_DARK));
            scroll.layout(l -> l.flexGrow(1.0f).heightPercent(100.0f));
            body.addChild(scroll);
            // viewContainer 宽度不定 (ScrollerView 按内容算), flexWrap 在这里永远不触发;
            // 改为手动按每行 3 张分行走 ROW 布局, 确定性换行 (96*3+gap6*2=300, 最窄面板也放得下)
            scroll.viewContainer.layout(l -> l.flexDirection(FlexDirection.COLUMN).gapAll(6.0f));

            final int cols = 3;
            for (int i = 0; i < list.size(); i += cols) {
                UIElement row = new UIElement();
                row.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(6.0f));
                for (int j = i; j < Math.min(i + cols, list.size()); j++) {
                    String name = list.get(j);
                    Button cell = new Button();
                    // Button 的贴图走 buttonStyle (base/hover/pressed), 通用 style().backgroundTexture 会被忽略
                    var photo = SpriteTexture.of(TerminalPhotoStore.texture(name));
                    cell.buttonStyle(s -> s.baseTexture(photo).hoverTexture(photo).pressedTexture(photo));
                    cell.noText();
                    cell.layout(l -> l.width(96.0f).height(54.0f));
                    cell.setOnClick(e -> openPhotoViewer(pane[0], rebuildRoot[0], name));
                    row.addChild(cell);
                }
                scroll.addScrollViewChild(row);
            }

            // 浏览状态保留: 上次正在查看的照片仍存在 → 直接回到查看器
            if (lastAlbumPhoto != null && list.contains(lastAlbumPhoto)) {
                openPhotoViewer(pane[0], rebuildRoot[0], lastAlbumPhoto);
            } else {
                lastAlbumPhoto = null; // 照片已被删除/清理, 快照失效
            }
        };
        rebuildRoot[0].run();
        return content;
    }

    /** 单张照片查看: 大图 + 日期/文件名 + 返回 / 删除. */
    private static void openPhotoViewer(UIElement pane, Runnable backToList, String name) {
        lastAlbumPhoto = name; // 记录浏览快照
        pane.clearAllChildren();

        UIElement viewer = new UIElement();
        viewer.style(s -> s.backgroundTexture(Sprites.RECT_DARK));
        viewer.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f).heightPercent(100.0f).gapAll(6.0f).paddingAll(4.0f));
        pane.addChild(viewer);

        UIElement photo = new UIElement();
        photo.style(s -> s.backgroundTexture(SpriteTexture.of(TerminalPhotoStore.texture(name))));
        photo.layout(l -> l.flexGrow(1.0f).heightPercent(100.0f));
        viewer.addChild(photo);

        UIElement side = new UIElement();
        side.layout(l -> l.flexDirection(FlexDirection.COLUMN).width(120.0f).heightPercent(100.0f).gapAll(4.0f));
        viewer.addChild(side);

        Label date = new Label();
        date.setText(TerminalPhotoStore.displayDate(name), false);
        side.addChild(date);

        Label fileName = new Label();
        fileName.setText(name, false);
        fileName.textStyle(t -> t.fontSize(6.3f));
        side.addChild(fileName);

        Button back = new Button().setText(I18n.get("gui.modern_terminal.album.back"), false);
        back.layout(l -> l.widthPercent(100.0f).height(16.0f));
        back.setOnClick(e -> {
            lastAlbumPhoto = null; // 返回网格 → 快照清空
            backToList.run();
        });
        side.addChild(back);

        Button delete = new Button().setText(I18n.get("gui.modern_terminal.album.delete"), false);
        delete.layout(l -> l.widthPercent(100.0f).height(16.0f));
        delete.setOnClick(e -> {
            if (TerminalPhotoStore.delete(name)) {
                lastAlbumPhoto = null;
                backToList.run();
            }
        });
        side.addChild(delete);
    }

    /** "建筑选择" 应用: 左侧分类侧栏 + 右侧建筑缩略图网格, 数据与主模组建筑 tab 同源. */
    private static UIElement createBuildingsContent() {
        UIElement content = new UIElement();
        content.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100.0f).heightPercent(100.0f)
                .gapAll(4.0f).paddingAll(4.0f));

        // 缩略图就绪后整体刷新 (重建时已加载纹理走缓存, 不再触发加载 → 收敛)
        Runnable[] rebuildRoot = {null};
        rebuildRoot[0] = () -> {
            content.clearAllChildren();
            List<ConstructionInfo> all = collectBuildings();

            // 工具条: 建筑数量
            UIElement toolbar = new UIElement();
            toolbar.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f).height(14.0f));
            Label count = new Label();
            count.setText(I18n.get("gui.modern_terminal.app.buildings.count", all.size()), false);
            toolbar.addChild(count);
            content.addChild(toolbar);

            UIElement body = new UIElement();
            body.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f).flexGrow(1.0f).gapAll(4.0f));
            content.addChild(body);

            // 左侧分类侧栏: 全部 / 未分类 / 自定义分类
            // CategoryManager.getCategories() 首位固定是"未分类", 与上面手写项重复, 从 index 1 起 (同主模组)
            UIElement sidebar = new UIElement();
            sidebar.layout(l -> l.flexDirection(FlexDirection.COLUMN).width(64.0f).gapAll(2.0f));
            body.addChild(sidebar);
            addCategoryButton(sidebar, I18n.get("gui.modern_terminal.app.buildings.cat.all"), null, rebuildRoot[0]);
            addCategoryButton(sidebar, I18n.get("gui.modern_terminal.app.buildings.cat.uncategorized"),
                    CategoryManager.UNCATEGORIZED, rebuildRoot[0]);
            List<String> cats = CategoryManager.get().getCategories();
            for (int i = 1; i < cats.size(); i++) {
                addCategoryButton(sidebar, cats.get(i), cats.get(i), rebuildRoot[0]);
            }

            // 分类过滤 (null = 全部), 同主模组 filterByCategory
            // 分类可能已被删除 (状态跨开关联保留): 不存在时回退"全部"
            if (buildingsCategory != null && !CategoryManager.UNCATEGORIZED.equals(buildingsCategory)
                    && !cats.contains(buildingsCategory)) {
                buildingsCategory = null;
            }
            List<ConstructionInfo> shown = new ArrayList<>();
            for (ConstructionInfo c : all) {
                if (buildingsCategory == null || buildingsCategory.equals(c.getCategoryOrDefault())) {
                    shown.add(c);
                }
            }

            if (shown.isEmpty()) {
                Label empty = new Label();
                empty.setText(I18n.get("gui.modern_terminal.app.buildings.empty"), false);
                empty.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textAlignVertical(Vertical.CENTER));
                empty.layout(l -> l.flexGrow(1.0f).heightPercent(100.0f));
                body.addChild(empty);
                return;
            }

            // 右侧: 网格 + 翻页条 (纵向排)
            UIElement right = new UIElement();
            right.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1.0f).heightPercent(100.0f).gapAll(4.0f));
            body.addChild(right);
            // 分页: 每页 8 张 (2 行 x 4 列)
            final int pageSize = 8;
            int totalPages = (shown.size() + pageSize - 1) / pageSize;
            if (buildingsPage >= totalPages) buildingsPage = totalPages - 1;
            if (buildingsPage < 0) buildingsPage = 0;
            int from = buildingsPage * pageSize;
            int to = Math.min(from + pageSize, shown.size());

            ScrollerView scroll = new ScrollerView();
            scroll.style(s -> s.backgroundTexture(Sprites.RECT_DARK));
            // 只用 flexGrow 占剩余高度, 不能再叠 heightPercent(100) —
            // 那会占满整列把下面的翻页条挤出屏幕 (只露半截)
            scroll.layout(l -> l.flexGrow(1.0f));
            right.addChild(scroll);
            // viewContainer 宽度不定 (ScrollerView 按内容算), flexWrap 永远不触发 (同相册页);
            // 手动按每行 4 张分行走 ROW 布局, 确定性换行 (72*4+gap6*3=306, 扣掉 64 侧栏也放得下)
            scroll.viewContainer.layout(l -> l.flexDirection(FlexDirection.COLUMN).gapAll(6.0f));
            final int cols = 4;
            for (int i = from; i < to; i += cols) {
                UIElement row = new UIElement();
                row.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(6.0f));
                for (int j = i; j < Math.min(i + cols, to); j++) {
                    row.addChild(buildingCard(shown.get(j)));
                }
                scroll.addScrollViewChild(row);
            }

            // 翻页条 (只有一页时不显示)
            if (totalPages > 1) {
                UIElement pageBar = new UIElement();
                pageBar.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f)
                        .height(14.0f).gapAll(4.0f));
                Button prev = new Button().setText(I18n.get("gui.modern_terminal.page.prev"), false);
                prev.layout(l -> l.width(40.0f).heightPercent(100.0f));
                prev.setOnClick(e -> {
                    if (buildingsPage > 0) {
                        buildingsPage--;
                        rebuildRoot[0].run();
                    }
                });
                pageBar.addChild(prev);
                Label pageLabel = new Label();
                pageLabel.setText(I18n.get("gui.modern_terminal.page.info",
                        buildingsPage + 1, totalPages), false);
                pageLabel.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textColor(0xAAAAAA));
                pageLabel.layout(l -> l.flexGrow(1.0f).heightPercent(100.0f));
                pageBar.addChild(pageLabel);
                Button next = new Button().setText(I18n.get("gui.modern_terminal.page.next"), false);
                next.layout(l -> l.width(40.0f).heightPercent(100.0f));
                next.setOnClick(e -> {
                    if (buildingsPage < totalPages - 1) {
                        buildingsPage++;
                        rebuildRoot[0].run();
                    }
                });
                pageBar.addChild(next);
                right.addChild(pageBar);
            }
        };
        rebuildRoot[0].run();
        TerminalBuildingThumbs.addListener(rebuildRoot[0]);
        return content;
    }

    /** 分类侧栏按钮: 选中态高亮 (RECT_SOLID), 点击切换分类并重建; 名称超 6 字截断 (64 宽侧栏装不下). */
    private static void addCategoryButton(UIElement sidebar, String name, @Nullable String value, Runnable rebuild) {
        boolean selected = (buildingsCategory == null) ? (value == null) : buildingsCategory.equals(value);
        Button b = new Button().setText(truncate(name, 6), false);
        b.buttonStyle(s -> {
            if (selected) {
                s.baseTexture(Sprites.RECT_SOLID).hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_SOLID);
            } else {
                s.baseTexture(Sprites.RECT_DARK).hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK);
            }
        });
        b.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textAlignVertical(Vertical.CENTER));
        b.layout(l -> l.widthPercent(100.0f).height(14.0f));
        b.setOnClick(e -> {
            buildingsCategory = value;
            buildingsPage = 0; // 切分类回第一页
            rebuild.run();
        });
        sidebar.addChild(b);
    }

    /** 建筑卡片: 正方形缩略图按钮 + 下方名字; 缩略图异步加载, 就绪前显示默认底 (listener 触发重建). */
    private static UIElement buildingCard(ConstructionInfo c) {
        UIElement card = new UIElement();
        card.layout(l -> l.flexDirection(FlexDirection.COLUMN).width(72.0f).gapAll(1.0f));

        Button img = new Button();
        img.noText();
        img.layout(l -> l.widthPercent(100.0f).height(72.0f));
        var rl = TerminalBuildingThumbs.get(c);
        if (rl != null) {
            // Button 的贴图走 buttonStyle (base/hover/pressed), 通用 style().backgroundTexture 会被忽略
            var tex = SpriteTexture.of(rl);
            img.buttonStyle(s -> s.baseTexture(tex).hoverTexture(tex).pressedTexture(tex));
        }
        // 点击卡片 → 打开主模组原版建筑界面 (3D 预览 + 提交材料/预览/建造),
        // 不需要自定义蓝图; 挑战模式 (提交材料) 跟随设置里的提交材料开关.
        img.setOnClick(e -> {
            var player = Minecraft.getInstance().player;
            if (player == null) return;
            detailReturnAppId = "buildings"; // 详细页返回时回到建筑选择 tab
            CustomStructureGui.openFromTerminal(c, player.blockPosition().below());
        });
        card.addChild(img);

        Label name = new Label();
        name.setText(c.getName(), false);
        name.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).fontSize(6.3f));
        name.layout(l -> l.widthPercent(100.0f).height(10.0f));
        card.addChild(name);
        return card;
    }

    /**
     * 建筑 tab 数据源: 与主模组 GuiExtensionPackBrowser 建筑页同源 ——
     * 扩展包建筑 (过滤 server-backed 副本) ∪ 本地扫描建筑 (id 相同则本地元数据覆盖).
     */
    private static List<ConstructionInfo> collectBuildings() {
        List<ConstructionInfo> all = new ArrayList<>();
        for (ConstructionInfo c : ExtensionPackManager.getInstance().getAllConstructionsForGui()) {
            if (c.getPack() != null && c.getPack().isServerBacked()) {
                continue;
            }
            all.add(c);
        }
        for (LocalBuilding lb : LocalBuildingScanner.scanAll()) {
            ConstructionInfo c = null;
            for (ConstructionInfo e : all) {
                if (e.getId().equals(lb.id)) {
                    c = e;
                    break;
                }
            }
            if (c == null) {
                c = new ConstructionInfo(lb.id);
                all.add(c);
            }
            c.setName(lb.name);
            c.setAuthor(lb.author);
            c.setDescription(lb.description);
            c.setDependencies(lb.dependencies);
            c.setCategory(lb.category);
            if (lb.fileExt != null && !lb.fileExt.isEmpty()) {
                c.setFormat(lb.fileExt.startsWith(".") ? lb.fileExt.substring(1) : lb.fileExt);
            }
            if (lb.imagePath != null && Files.exists(lb.imagePath)) {
                c.setLocalImagePath(lb.imagePath);
            }
            if (lb.filePath != null) {
                c.setLocalNbtPath(lb.filePath);
            }
        }
        return all;
    }

    /** 超长截断显示 (终端侧栏/卡片文字宽度有限). */
    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    // ==================== "创建建筑" 应用 ====================
    // 移植自主模组 GuiExtensionPackEditor 的"创建建筑" tab:
    //   - 表单字段/选文件/保存全部复用 GuiCreateBuildingInfo 的静态业务逻辑 (桥接方法);
    //   - 游戏内选区走 RegionSelector, 需要临时关终端, 完成后 sendReopenTerminal 重开.

    /** "创建建筑" 应用: 左侧侧栏 (外部建筑文件 / 游戏内选择建筑) + 右侧面板. */
    private static UIElement createCreateBuildingContent() {
        UIElement content = new UIElement();
        content.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100.0f).heightPercent(100.0f)
                .gapAll(4.0f).paddingAll(4.0f));

        Runnable[] rebuildRoot = {null};
        rebuildRoot[0] = () -> {
            content.clearAllChildren();

            UIElement body = new UIElement();
            body.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f).flexGrow(1.0f).gapAll(4.0f));
            content.addChild(body);

            // 左侧侧栏: 两个标签页
            UIElement sidebar = new UIElement();
            sidebar.layout(l -> l.flexDirection(FlexDirection.COLUMN).width(72.0f).gapAll(2.0f));
            body.addChild(sidebar);
            addCreateTabButton(sidebar, I18n.get("gui.modern_terminal.create.tab.ext"), 0, rebuildRoot[0]);
            addCreateTabButton(sidebar, I18n.get("gui.modern_terminal.create.tab.ingame"), 1, rebuildRoot[0]);
            addCreateTabButton(sidebar, I18n.get("gui.modern_terminal.create.tab.cat"), 2, rebuildRoot[0]);
            addCreateTabButton(sidebar, I18n.get("gui.modern_terminal.create.tab.edit"), 3, rebuildRoot[0]);

            // 右侧面板
            UIElement panel = new UIElement();
            panel.style(s -> s.backgroundTexture(Sprites.RECT_DARK));
            panel.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1.0f).heightPercent(100.0f)
                    .gapAll(2.0f).paddingAll(4.0f));
            body.addChild(panel);
            if (createTab == 1) {
                buildCreateInGamePanel(panel, rebuildRoot[0]);
            } else if (createTab == 2) {
                buildCreateCategoryPanel(panel, rebuildRoot[0]);
            } else if (createTab == 3) {
                buildCreateEditPanel(panel, rebuildRoot[0]);
            } else {
                buildCreateFilePanel(panel, rebuildRoot[0]);
            }

            // 状态提示 (最近一次选文件/保存/导入结果)
            if (createStatusMsg != null) {
                Label st = new Label();
                st.setText(createStatusMsg, false);
                st.textStyle(t -> t.textColor(createStatusColor).textWrap(TextWrap.WRAP));
                st.layout(l -> l.widthPercent(100.0f).height(20.0f));
                content.addChild(st);
            }
        };
        rebuildRoot[0].run();
        // 编辑建筑卡片的缩略图异步加载, 就绪后刷新 (含其他 tab 重建, 无副作用)
        TerminalBuildingThumbs.addListener(rebuildRoot[0]);
        return content;
    }

    /** 创建建筑侧栏标签按钮 (选中态高亮, 同建筑选择侧栏样式). */
    private static void addCreateTabButton(UIElement sidebar, String name, int index, Runnable rebuild) {
        boolean selected = createTab == index;
        Button b = new Button().setText(truncate(name, 8), false);
        b.buttonStyle(s -> {
            if (selected) {
                s.baseTexture(Sprites.RECT_SOLID).hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_SOLID);
            } else {
                s.baseTexture(Sprites.RECT_DARK).hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK);
            }
        });
        b.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).fontSize(6.5f));
        b.layout(l -> l.widthPercent(100.0f).height(16.0f));
        b.setOnClick(e -> {
            createTab = index;
            rebuild.run();
        });
        sidebar.addChild(b);
    }

    /** 创建建筑两页共用的可滚动表单根容器. */
    private static UIElement newCreateFormRoot(UIElement panel) {
        ScrollerView scroll = new ScrollerView();
        scroll.layout(l -> l.widthPercent(100.0f).flexGrow(1.0f));
        panel.addChild(scroll);
        UIElement form = new UIElement();
        form.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100.0f).gapAll(2.0f));
        scroll.addScrollViewChild(form);
        return form;
    }

    /** 创建建筑来源行: 灰色提示小字 (两个标签页区别仅在选建筑的方式). */
    private static void addCreateHint(UIElement parent, String text) {
        Label hint = new Label();
        hint.setText(text, false);
        hint.textStyle(t -> t.textColor(0xAAAAAA));
        hint.layout(l -> l.flexGrow(1.0f).height(16.0f));
        parent.addChild(hint);
    }

    /** 创建建筑 - 外部建筑文件页: 来源行(选 litematic/schem/nbt 文件) + 公共表单 + 保存/重置. */
    private static void buildCreateFilePanel(UIElement panel, Runnable rebuild) {
        UIElement form = newCreateFormRoot(panel);

        addCreateFormFields(form, rebuild);
        addCreateReadonlyRows(form);
        addCreateSrcRowFile(form, rebuild);
        addCreateSaveResetRow(form, rebuild);
    }

    /** 外部建筑文件页来源行: [选择文件...] + 提示 (放表单底部, 靠近保存按钮). */
    private static void addCreateSrcRowFile(UIElement form, Runnable rebuild) {
        UIElement srcRow = new UIElement();
        srcRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f).height(16.0f)
                .gapAll(4.0f).alignItems(AlignItems.CENTER));
        form.addChild(srcRow);
        Button pick = new Button().setText(I18n.get("gui.modern_terminal.create.pick_file"), false);
        pick.layout(l -> l.width(70.0f).height(16.0f));
        pick.setOnClick(e -> GuiCreateBuildingInfo.pickNbtForBlueprint((res, err) ->
                Minecraft.getInstance().execute(() -> {
                    if (err != null) {
                        setCreateStatus(I18n.get("gui.modern_terminal.create.pick_fail",
                                err.getMessage() == null ? "unknown" : err.getMessage()), 0xFF5555);
                        rebuild.run();
                        return;
                    }
                    if (res == null) {
                        return; // 玩家取消
                    }
                    GuiCreateBuildingInfo.importNbtFile(res.file);
                    setCreateStatus(GuiCreateBuildingInfo.getStatusMessage(), GuiCreateBuildingInfo.getStatusColor());
                    rebuild.run();
                })));
        srcRow.addChild(pick);
        addCreateHint(srcRow, I18n.get("gui.modern_terminal.create.src.file_hint"));
    }

    /** 创建建筑 - 游戏内选择建筑页: 来源行(开始选区) + 同一套公共表单 (选完区自动回填). */
    private static void buildCreateInGamePanel(UIElement panel, Runnable rebuild) {
        UIElement form = newCreateFormRoot(panel);

        addCreateFormFields(form, rebuild);
        addCreateReadonlyRows(form);
        // 来源行 (开始选区) 放表单底部, 靠近保存按钮
        UIElement srcRow = new UIElement();
        srcRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f).height(16.0f)
                .gapAll(4.0f).alignItems(AlignItems.CENTER));
        form.addChild(srcRow);
        Button start = new Button().setText(I18n.get("gui.modern_terminal.create.ingame.start"), false);
        start.layout(l -> l.width(70.0f).height(16.0f));
        start.setOnClick(e -> startInGameSelectionForCreate());
        srcRow.addChild(start);
        addCreateHint(srcRow, I18n.get("gui.modern_terminal.create.src.ingame_hint"));

        addCreateSaveResetRow(form, rebuild);
    }

    /**
     * 创建建筑公共表单字段: 显示名 + 分类 + 图标.
     * 两个标签页共用; 数据存在 GuiCreateBuildingInfo 静态字段, 切换标签页时天然同步.
     * 保存时显示名直接作为建筑文件名 (支持中文).
     */
    private static void addCreateFormFields(UIElement form, Runnable rebuild) {
        // 可编辑字段: TextField 值实时写回 GuiCreateBuildingInfo 桥接字段 (doSave 直接读静态值)
        addCreateTextField(form, I18n.get("gui.modern_terminal.create.field.name"),
                GuiCreateBuildingInfo.getNameValue(), GuiCreateBuildingInfo::setNameValue);
        addCreateCategoryRow(form);
        addCreateIconRow(form, rebuild);
    }

    /** 创建建筑表单图标行: 标签 + 已选状态 + [选择图片...] [✕] (保存时写到 &lt;id&gt;.png/jpg). */
    private static void addCreateIconRow(UIElement parent, Runnable rebuild) {
        UIElement row = new UIElement();
        row.layout(l -> l.widthPercent(100.0f).height(16.0f).flexDirection(FlexDirection.ROW)
                .gapAll(4.0f).alignItems(AlignItems.CENTER));
        parent.addChild(row);
        Label lbl = new Label();
        lbl.setText(I18n.get("gui.modern_terminal.create.field.icon"), false);
        lbl.layout(l -> l.width(52.0f).height(16.0f));
        row.addChild(lbl);

        boolean has = GuiCreateBuildingInfo.hasIconData();
        Label val = new Label();
        val.setText(has ? truncate(GuiCreateBuildingInfo.getIconPathValue(), 20)
                : I18n.get("gui.modern_terminal.create.icon_none"), false);
        val.textStyle(t -> t.textColor(has ? 0x55FF55 : 0xFF5555));
        val.layout(l -> l.flexGrow(1.0f).height(16.0f));
        row.addChild(val);

        Button pickIcon = new Button().setText(I18n.get("gui.modern_terminal.create.pick_icon"), false);
        pickIcon.layout(l -> l.width(58.0f).height(16.0f));
        pickIcon.setOnClick(e -> GuiCreateBuildingInfo.openIconPickerForTerminal(() ->
                Minecraft.getInstance().execute(rebuild)));
        row.addChild(pickIcon);

        Button clearIcon = new Button().setText("X", false);
        clearIcon.layout(l -> l.width(16.0f).height(16.0f));
        clearIcon.setOnClick(e -> {
            GuiCreateBuildingInfo.clearIconForTerminal();
            rebuild.run();
        });
        row.addChild(clearIcon);
    }

    /** 创建建筑只读信息行: 建筑文件/尺寸/依赖 (选文件或选区后自动填). */
    private static void addCreateReadonlyRows(UIElement form) {
        addCreateReadonlyRow(form, I18n.get("gui.modern_terminal.create.field.file"),
                truncate(GuiCreateBuildingInfo.getNbtPathValue(), 46));
        addCreateReadonlyRow(form, I18n.get("gui.modern_terminal.create.field.size"),
                GuiCreateBuildingInfo.getSizeValue());
        addCreateReadonlyRow(form, I18n.get("gui.modern_terminal.create.field.deps"),
                GuiCreateBuildingInfo.getDepsValue());
    }

    /** 创建建筑按钮行: [保存并创建] [重置] (两个标签页行为一致). */
    private static void addCreateSaveResetRow(UIElement form, Runnable rebuild) {
        UIElement btnRow = new UIElement();
        btnRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f).height(16.0f).gapAll(4.0f));
        form.addChild(btnRow);

        Button save = new Button().setText(I18n.get("gui.modern_terminal.create.save"), false);
        save.layout(l -> l.width(80.0f).height(16.0f));
        save.setOnClick(e -> {
            boolean ok = GuiCreateBuildingInfo.saveBuildingForTerminal();
            setCreateStatus(GuiCreateBuildingInfo.getStatusMessage(), GuiCreateBuildingInfo.getStatusColor());
            if (ok) {
                GuiCreateBuildingInfo.resetForTerminal(); // 同原编辑器: 保存成功后清空表单
            }
            rebuild.run();
        });
        btnRow.addChild(save);

        Button reset = new Button().setText(I18n.get("gui.modern_terminal.create.reset"), false);
        reset.layout(l -> l.width(44.0f).height(16.0f));
        reset.setOnClick(e -> {
            GuiCreateBuildingInfo.resetForTerminal();
            setCreateStatus(null, 0);
            rebuild.run();
        });
        btnRow.addChild(reset);
    }

    /** 创建建筑 - 游戏内选区: 下一帧关终端 → RegionSelector 选区 → 结果写回表单 → 重开终端. */
    private static void startInGameSelectionForCreate() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        // 下一帧关闭, 避免在 UI 事件分派中重入 setScreen (同 enterCameraMode)
        mc.tell(() -> {
            mc.setScreen(null);
            RegionSelector.start(mc.player, new RegionSelector.OnCompleted() {
                @Override
                public void onCompleted(byte[] data, Path nbtFile, String sizeString, List<String> modIds) {
                    Minecraft.getInstance().execute(() -> {
                        GuiCreateBuildingInfo.applyInGamePickForTerminal(data, nbtFile, sizeString, modIds);
                        setCreateStatus(GuiCreateBuildingInfo.getStatusMessage(), GuiCreateBuildingInfo.getStatusColor());
                        createSelectOnReopen = true; // 重开终端后自动选中创建建筑 tab
                        TerminalPayloads.sendReopenTerminal();
                    });
                }

                @Override
                public void onCancelled() {
                    Minecraft.getInstance().execute(TerminalPayloads::sendReopenTerminal);
                }
            });
        });
    }

    /** 创建建筑表单可编辑行: 标签 + TextField (onChange 写回 GuiCreateBuildingInfo). */
    private static void addCreateTextField(UIElement parent, String label, String initial,
                                           java.util.function.Consumer<String> onWrite) {
        UIElement row = new UIElement();
        row.layout(l -> l.widthPercent(100.0f).height(16.0f).flexDirection(FlexDirection.ROW)
                .gapAll(4.0f).alignItems(AlignItems.CENTER));
        parent.addChild(row);
        Label lbl = new Label();
        lbl.setText(label, false);
        lbl.layout(l -> l.width(52.0f).height(16.0f));
        row.addChild(lbl);
        row.addChild(newCreateTextField(initial, onWrite));
    }

    /** 创建建筑输入框 (白字, 任意字符, 最长 1024, 弹性宽度). */
    private static TextField newCreateTextField(String initial, java.util.function.Consumer<String> onWrite) {
        TextField tf = new TextField();
        tf.setText(initial == null ? "" : initial);
        tf.setTextResponder(onWrite::accept);
        tf.setAnyString();
        tf.setTextValidator(s -> s == null || s.length() <= 1024);
        tf.textFieldStyle(s -> s.textColor(0xFFFFFF).textShadow(false));
        tf.layout(l -> l.flexGrow(1.0f).height(16.0f).minHeight(16.0f));
        return tf;
    }

    /** 创建建筑 - 分类管理页: 分类列表 (改名/删除) + 底部新建/重命名输入区 (移植自 GuiCategoryManager). */
    private static void buildCreateCategoryPanel(UIElement panel, Runnable rebuild) {
        UIElement root = new UIElement();
        root.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100.0f).heightPercent(100.0f).gapAll(2.0f));
        panel.addChild(root);

        // 数量提示: x/10
        Label count = new Label();
        count.setText(I18n.get("gui.modern_terminal.create.cat.count",
                CategoryManager.get().categories.size(), CategoryManager.MAX_CATEGORIES), false);
        count.textStyle(t -> t.textColor(0xAAAAAA));
        count.layout(l -> l.widthPercent(100.0f).height(12.0f));
        root.addChild(count);

        // 分类列表 (可滚动)
        ScrollerView scroll = new ScrollerView();
        scroll.layout(l -> l.widthPercent(100.0f).flexGrow(1.0f));
        root.addChild(scroll);
        UIElement list = new UIElement();
        list.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100.0f).gapAll(2.0f));
        scroll.addScrollViewChild(list);

        // 未分类行: 隐式默认分类, 不可改不可删
        UIElement uncatRow = new UIElement();
        uncatRow.layout(l -> l.widthPercent(100.0f).height(16.0f).flexDirection(FlexDirection.ROW)
                .gapAll(4.0f).alignItems(AlignItems.CENTER));
        list.addChild(uncatRow);
        Label uncatName = new Label();
        uncatName.setText(truncate(CategoryManager.UNCATEGORIZED, 12), false);
        uncatName.textStyle(t -> t.textColor(0x888888));
        uncatName.layout(l -> l.flexGrow(1.0f).height(16.0f));
        uncatRow.addChild(uncatName);
        Label uncatHint = new Label();
        uncatHint.setText(I18n.get("gui.modern_terminal.create.cat.uncat_hint"), false);
        uncatHint.textStyle(t -> t.textColor(0x666666));
        uncatHint.layout(l -> l.width(34.0f).height(16.0f));
        uncatRow.addChild(uncatHint);

        for (String c : CategoryManager.get().getCategories()) {
            if (CategoryManager.UNCATEGORIZED.equals(c)) {
                continue;
            }
            addCreateCategoryListRow(list, c, rebuild);
        }

        // 底部: 新建模式 / 重命名模式
        UIElement bottom = new UIElement();
        bottom.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100.0f).gapAll(2.0f));
        root.addChild(bottom);
        if (renameTargetCategory != null) {
            Label hint = new Label();
            hint.setText(I18n.get("gui.modern_terminal.create.cat.rename_hint", renameTargetCategory), false);
            hint.textStyle(t -> t.textColor(0xFFAA00));
            hint.layout(l -> l.widthPercent(100.0f).height(12.0f));
            bottom.addChild(hint);

            UIElement row = new UIElement();
            row.layout(l -> l.widthPercent(100.0f).height(16.0f).flexDirection(FlexDirection.ROW).gapAll(4.0f));
            bottom.addChild(row);
            final String[] input = {renameTargetCategory};
            row.addChild(newCreateTextField(renameTargetCategory, s -> input[0] = s));
            addCreateCategorySmallButton(row, I18n.get("gui.modern_terminal.create.cat.confirm"), 30, () -> {
                String old = renameTargetCategory;
                String newName = input[0] == null ? "" : input[0].trim();
                if (CategoryManager.get().renameCategory(old, newName)) {
                    setCreateStatus(I18n.get("gui.modern_terminal.create.cat.rename_ok", old, newName), 0x55FF55);
                } else {
                    setCreateStatus(I18n.get("gui.modern_terminal.create.cat.rename_fail"), 0xFF5555);
                }
                renameTargetCategory = null;
                rebuild.run();
            });
            addCreateCategorySmallButton(row, I18n.get("gui.modern_terminal.create.cat.cancel"), 30, () -> {
                renameTargetCategory = null;
                rebuild.run();
            });
        } else {
            UIElement row = new UIElement();
            row.layout(l -> l.widthPercent(100.0f).height(16.0f).flexDirection(FlexDirection.ROW).gapAll(4.0f));
            bottom.addChild(row);
            final String[] input = {""};
            row.addChild(newCreateTextField("", s -> input[0] = s));
            addCreateCategorySmallButton(row, I18n.get("gui.modern_terminal.create.cat.add"), 30, () -> {
                String n = input[0] == null ? "" : input[0].trim();
                if (CategoryManager.get().addCategory(n)) {
                    // addCategory 会截断到 16 字符, 显示实际存进去的名字
                    String stored = CategoryManager.get().categories
                            .get(CategoryManager.get().categories.size() - 1);
                    setCreateStatus(I18n.get("gui.modern_terminal.create.cat.add_ok", stored), 0x55FF55);
                } else {
                    setCreateStatus(I18n.get("gui.modern_terminal.create.cat.add_fail"), 0xFF5555);
                }
                rebuild.run();
            });
        }
    }

    /** 创建建筑分类列表行: 分类名 + [改名] [删除]. */
    private static void addCreateCategoryListRow(UIElement list, String name, Runnable rebuild) {
        UIElement row = new UIElement();
        row.layout(l -> l.widthPercent(100.0f).height(16.0f).flexDirection(FlexDirection.ROW)
                .gapAll(4.0f).alignItems(AlignItems.CENTER));
        list.addChild(row);
        Label lbl = new Label();
        lbl.setText(truncate(name, 12), false);
        lbl.layout(l -> l.flexGrow(1.0f).height(16.0f));
        row.addChild(lbl);
        addCreateCategorySmallButton(row, I18n.get("gui.modern_terminal.create.cat.rename"), 30, () -> {
            renameTargetCategory = name;
            rebuild.run();
        });
        addCreateCategorySmallButton(row, I18n.get("gui.modern_terminal.create.cat.delete"), 30, () -> {
            if (CategoryManager.get().removeCategory(name)) {
                setCreateStatus(I18n.get("gui.modern_terminal.create.cat.del_ok", name), 0x55FF55);
            }
            rebuild.run();
        });
    }

    /** 创建建筑分类页小按钮 (改名/删除/添加/确认等). */
    private static void addCreateCategorySmallButton(UIElement parent, String text, float width, Runnable onClick) {
        Button b = new Button().setText(text, false);
        b.buttonStyle(s -> s.baseTexture(Sprites.RECT_DARK).hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
        b.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        b.layout(l -> l.width(width).height(16.0f));
        b.setOnClick(e -> onClick.run());
        parent.addChild(b);
    }

    // -------------------- 创建建筑 - 编辑建筑 tab --------------------
    // 移植自主模组 GuiExtensionPackEditor 的"编辑建筑" tab:
    //   搜索 + 卡片网格 (查看/编辑原理图/编辑信息).
    //   [编辑] 复用 GuiExtensionPackEditor.startSchematicEdit (3D 原理图编辑);
    //   [编辑信息] 走 GuiCreateBuildingInfo 的 loadForEditInfo/saveEditInfoForTerminal 桥接.

    /** 编辑建筑页: 搜索框 + 建筑卡片网格; 或 (点了编辑信息) 字段编辑表单. */
    private static void buildCreateEditPanel(UIElement panel, Runnable rebuild) {
        if (editInfoTarget != null) {
            buildEditInfoForm(panel, rebuild);
            return;
        }
        UIElement root = new UIElement();
        root.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100.0f).heightPercent(100.0f).gapAll(2.0f));
        panel.addChild(root);

        // 搜索框 (过滤 id/建筑名/作者/描述, 同原编辑器 matchesSearch)
        // 注意: 输入时只重建下方列表区, 不整页重建, 避免丢输入焦点
        TextField search = new TextField();
        search.setText(editSearchText);
        search.setAnyString();
        search.setTextValidator(s -> s == null || s.length() <= 64);
        search.textFieldStyle(s -> s.textColor(0xFFFFFF).textShadow(false));
        search.layout(l -> l.widthPercent(100.0f).height(16.0f));
        UIElement countArea = new UIElement();
        countArea.layout(l -> l.widthPercent(100.0f).height(12.0f));
        UIElement listArea = new UIElement();
        // ROW + flexGrow 横向撑满 (同建筑选择页 body 结构); 用 COLUMN 会让滚动区交叉轴
        // 失去约束, 卡片最小宽度把面板撑破 (右侧溢出)
        listArea.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f)
                .flexGrow(1.0f).gapAll(2.0f));
        UIElement pageArea = new UIElement();
        pageArea.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f).gapAll(4.0f));
        Runnable[] rebuildList = {null};
        rebuildList[0] = () -> {
            listArea.clearAllChildren();
            countArea.clearAllChildren();
            pageArea.clearAllChildren();
            List<LocalBuilding> shown = new ArrayList<>();
            for (LocalBuilding lb : LocalBuildingScanner.scanAll()) {
                if (matchesEditSearch(lb)) {
                    shown.add(lb);
                }
            }
            Label count = new Label();
            count.setText(I18n.get("gui.modern_terminal.create.edit.count", shown.size()), false);
            count.textStyle(t -> t.textColor(0xAAAAAA));
            count.layout(l -> l.widthPercent(100.0f).height(12.0f));
            countArea.addChild(count);
            if (shown.isEmpty()) {
                Label empty = new Label();
                empty.setText(I18n.get("gui.modern_terminal.create.edit.empty"), false);
                empty.textStyle(t -> t.textColor(0x888888).textAlignHorizontal(Horizontal.CENTER));
                empty.layout(l -> l.flexGrow(1.0f).widthPercent(100.0f));
                listArea.addChild(empty);
                return;
            }
            // 分页: 每页 6 张 (3 行 x 2 列), 一页放不下的走底部翻页条
            final int pageSize = 6;
            int totalPages = (shown.size() + pageSize - 1) / pageSize;
            if (editCreatePage >= totalPages) editCreatePage = totalPages - 1;
            if (editCreatePage < 0) editCreatePage = 0;
            int from = editCreatePage * pageSize;
            int to = Math.min(from + pageSize, shown.size());

            // 翻页条 (只有一页时不显示)
            if (totalPages > 1) {
                Button prev = new Button().setText(I18n.get("gui.modern_terminal.page.prev"), false);
                prev.layout(l -> l.width(40.0f).height(14.0f));
                prev.setOnClick(e -> {
                    if (editCreatePage > 0) {
                        editCreatePage--;
                        rebuildList[0].run();
                    }
                });
                pageArea.addChild(prev);
                Label pageLabel = new Label();
                pageLabel.setText(I18n.get("gui.modern_terminal.page.info",
                        editCreatePage + 1, totalPages), false);
                pageLabel.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textColor(0xAAAAAA));
                pageLabel.layout(l -> l.flexGrow(1.0f).height(14.0f)
                        .alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER));
                pageArea.addChild(pageLabel);
                Button next = new Button().setText(I18n.get("gui.modern_terminal.page.next"), false);
                next.layout(l -> l.width(40.0f).height(14.0f));
                next.setOnClick(e -> {
                    if (editCreatePage < totalPages - 1) {
                        editCreatePage++;
                        rebuildList[0].run();
                    }
                });
                pageArea.addChild(next);
            }

            // 缩略图数据源与建筑选择页一致 (collectBuildings: 扩展包建筑带 pngData,
            // 本地建筑带 localImagePath) — 只用裸 LocalBuilding 包装会丢扩展包缩略图
            var thumbMap = new java.util.HashMap<String, ConstructionInfo>();
            for (ConstructionInfo c : collectBuildings()) {
                thumbMap.putIfAbsent(c.getId(), c);
            }
            ScrollerView scroll = new ScrollerView();
            // 完全同建筑选择页: ROW 里 flexGrow 撑满 + heightPercent 纵向铺满
            scroll.layout(l -> l.flexGrow(1.0f).heightPercent(100.0f));
            listArea.addChild(scroll);
            // 滚动内容纵向排列, 每行固定 2 张卡片 (原编辑器同款两列布局);
            // 不依赖 flex wrap — taffy 对不定宽容器不触发换行, 会溢出右侧
            scroll.viewContainer.layout(l -> l.flexDirection(FlexDirection.COLUMN).gapAll(6.0f));
            for (int i = from; i < to; i += 2) {
                UIElement row = new UIElement();
                row.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(6.0f));
                for (int j = i; j < Math.min(i + 2, to); j++) {
                    row.addChild(buildEditBuildingCard(shown.get(j), thumbMap.get(shown.get(j).id), rebuild));
                }
                scroll.addScrollViewChild(row);
            }
        };
        search.setTextResponder(s -> {
            editSearchText = s == null ? "" : s.trim();
            editCreatePage = 0; // 搜索条件变了, 回到第一页
            rebuildList[0].run();
        });
        root.addChild(search);
        root.addChild(countArea);
        root.addChild(listArea);
        root.addChild(pageArea);
        rebuildList[0].run();
    }

    /** 编辑建筑搜索匹配: id/建筑名/作者/描述 包含关键字 (大小写不敏感), 空 = 全部. */
    private static boolean matchesEditSearch(LocalBuilding lb) {
        if (editSearchText.isEmpty()) {
            return true;
        }
        String q = editSearchText.toLowerCase(java.util.Locale.ROOT);
        return (lb.id != null && lb.id.toLowerCase(java.util.Locale.ROOT).contains(q))
            || (lb.name != null && lb.name.toLowerCase(java.util.Locale.ROOT).contains(q))
            || (lb.author != null && lb.author.toLowerCase(java.util.Locale.ROOT).contains(q))
            || (lb.description != null && lb.description.toLowerCase(java.util.Locale.ROOT).contains(q));
    }

    /** 编辑建筑卡片: 缩略图 + 名称/格式大小 + [查看] [编辑] [编辑信息]. */
    private static UIElement buildEditBuildingCard(LocalBuilding lb, @Nullable ConstructionInfo thumbInfo,
                                                   Runnable rebuild) {
        UIElement card = new UIElement();
        card.style(s -> s.backgroundTexture(Sprites.RECT_DARK));
        card.layout(l -> l.flexDirection(FlexDirection.COLUMN).width(170.0f).paddingAll(4.0f).gapAll(2.0f));

        // 上半: 40x40 缩略图 + 右侧名称/格式
        UIElement top = new UIElement();
        top.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f).gapAll(4.0f));
        card.addChild(top);

        Button img = new Button();
        img.noText();
        img.layout(l -> l.width(40.0f).height(40.0f));
        var rl = TerminalBuildingThumbs.get(thumbInfo != null ? thumbInfo : wrapForThumb(lb));
        if (rl != null) {
            var tex = SpriteTexture.of(rl);
            img.buttonStyle(s -> s.baseTexture(tex).hoverTexture(tex).pressedTexture(tex));
        }
        // 点缩略图 = 查看 (同原编辑器卡片行为)
        img.setOnClick(e -> openEditBuildingDetail(lb));
        top.addChild(img);

        UIElement infoCol = new UIElement();
        infoCol.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1.0f).gapAll(1.0f));
        top.addChild(infoCol);
        Label name = new Label();
        name.setText(truncate(lb.getDisplayName(), 12), false);
        name.textStyle(t -> t.fontSize(6.5f));
        name.layout(l -> l.widthPercent(100.0f).height(12.0f));
        infoCol.addChild(name);
        Label meta = new Label();
        meta.setText(I18n.get("gui.modern_terminal.create.edit.meta",
                lb.fileExt == null ? ".nbt" : lb.fileExt,
                formatBuildingSize(lb.fileSize)), false);
        meta.textStyle(t -> t.textColor(0xAAAAAA).fontSize(6.0f));
        meta.layout(l -> l.widthPercent(100.0f).height(10.0f));
        infoCol.addChild(meta);

        // 下半: [查看] [编辑] [编辑信息]
        UIElement btnRow = new UIElement();
        btnRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f).height(16.0f)
                .gapAll(2.0f));
        card.addChild(btnRow);

        Button view = new Button().setText(I18n.get("gui.modern_terminal.create.edit.view"), false);
        view.buttonStyle(s -> s.baseTexture(Sprites.RECT_DARK).hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
        view.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        view.layout(l -> l.width(32.0f).height(16.0f));
        view.setOnClick(e -> openEditBuildingDetail(lb));
        btnRow.addChild(view);

        Button edit = new Button().setText(I18n.get("gui.modern_terminal.create.edit.edit"), false);
        edit.buttonStyle(s -> s.baseTexture(Sprites.RECT_DARK).hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
        edit.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        edit.layout(l -> l.width(32.0f).height(16.0f));
        edit.setOnClick(e -> {
            // 成功时 startSchematicEdit 已自行关终端进入 3D 编辑; 失败时把错误挂到状态栏
            String err = GuiExtensionPackEditor.startSchematicEdit(lb);
            if (err != null) {
                setCreateStatus(err, 0xFF5555);
                rebuild.run();
            }
        });
        btnRow.addChild(edit);

        Button info = new Button().setText(I18n.get("gui.modern_terminal.create.edit.info"), false);
        info.buttonStyle(s -> s.baseTexture(Sprites.RECT_DARK).hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
        info.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        info.layout(l -> l.width(48.0f).height(16.0f));
        info.setOnClick(e -> {
            // 编辑信息与创建表单共用静态字段: 先快照创建表单, 结束时恢复, 避免污染创建表单
            GuiCreateBuildingInfo.snapshotForTerminal();
            GuiCreateBuildingInfo.loadForEditInfo(lb);
            editInfoTarget = lb;
            rebuild.run();
        });
        btnRow.addChild(info);

        return card;
    }

    /** 编辑建筑卡片 [查看]: 打开主模组 3D 详情页, 返回后直达 创建建筑-编辑建筑 tab. */
    private static void openEditBuildingDetail(LocalBuilding lb) {
        GuiConstructionDetail.open(GuiExtensionPackEditor.buildDetailInfo(lb), () -> {
            createSelectOnReopen = true;
            createEditTabOnReopen = true;
            TerminalPayloads.sendReopenTerminal();
        });
    }

    /** LocalBuilding → 缩略图用 ConstructionInfo (TerminalBuildingThumbs 按 id 取缓存). */
    private static ConstructionInfo wrapForThumb(LocalBuilding lb) {
        ConstructionInfo c = new ConstructionInfo(lb.id);
        c.setName(lb.getDisplayName());
        if (lb.imagePath != null && Files.exists(lb.imagePath)) {
            c.setLocalImagePath(lb.imagePath);
        }
        return c;
    }

    /** 文件大小人性化: B / KB / MB. */
    private static String formatBuildingSize(long bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
        }
        if (bytes >= 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    /** 编辑信息表单: 回填的元信息字段 + [保存] [返回] (保存就地写回 .txt, 改名时重命名文件). */
    private static void buildEditInfoForm(UIElement panel, Runnable rebuild) {
        UIElement form = newCreateFormRoot(panel);

        // 标题 + 返回
        UIElement titleRow = new UIElement();
        titleRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f).height(16.0f)
                .gapAll(4.0f).alignItems(AlignItems.CENTER));
        form.addChild(titleRow);
        Label title = new Label();
        title.setText(I18n.get("gui.modern_terminal.create.edit.info_title",
                truncate(editInfoTarget.getDisplayName(), 16)), false);
        title.layout(l -> l.flexGrow(1.0f).height(16.0f));
        titleRow.addChild(title);
        Button back = new Button().setText(I18n.get("gui.modern_terminal.create.edit.back"), false);
        back.buttonStyle(s -> s.baseTexture(Sprites.RECT_DARK).hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
        back.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        back.layout(l -> l.width(36.0f).height(16.0f));
        back.setOnClick(e -> {
            editInfoTarget = null;
            rebuild.run();
        });
        titleRow.addChild(back);

        // 可编辑字段 (简化字段集, 同创建表单: 显示名/分类/图标; 作者和描述保留原值不显示)
        addCreateTextField(form, I18n.get("gui.modern_terminal.create.field.name"),
                GuiCreateBuildingInfo.getNameValue(), GuiCreateBuildingInfo::setNameValue);
        addCreateCategoryRow(form);
        addEditInfoIconRow(form, rebuild);
        // 只读: 建筑文件 (编辑信息不更换建筑本体); 之后 尺寸/依赖 可编辑
        addCreateReadonlyRow(form, I18n.get("gui.modern_terminal.create.field.file"),
                truncate(GuiCreateBuildingInfo.getNbtPathValue(), 46));
        addCreateTextField(form, I18n.get("gui.modern_terminal.create.field.size"),
                GuiCreateBuildingInfo.getSizeValue(), GuiCreateBuildingInfo::setSizeValue);
        addCreateTextField(form, I18n.get("gui.modern_terminal.create.field.deps"),
                GuiCreateBuildingInfo.getDepsValue(), GuiCreateBuildingInfo::setDepsValue);

        // [保存] [返回]
        UIElement btnRow = new UIElement();
        btnRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f).height(16.0f).gapAll(4.0f));
        form.addChild(btnRow);
        final LocalBuilding target = editInfoTarget;
        Button save = new Button().setText(I18n.get("gui.modern_terminal.create.edit.save_info"), false);
        save.layout(l -> l.width(80.0f).height(16.0f));
        save.setOnClick(e -> {
            boolean ok = GuiCreateBuildingInfo.saveEditInfoForTerminal(target);
            setCreateStatus(GuiCreateBuildingInfo.getStatusMessage(), GuiCreateBuildingInfo.getStatusColor());
            if (ok) {
                editInfoTarget = null; // 保存成功回卡片列表 (改名后旧对象已失效)
                GuiCreateBuildingInfo.restoreForTerminal(); // 恢复进入编辑信息前的创建表单
            }
            rebuild.run();
        });
        btnRow.addChild(save);

        Button backBtn = new Button().setText(I18n.get("gui.modern_terminal.create.edit.back"), false);
        backBtn.buttonStyle(s -> s.baseTexture(Sprites.RECT_DARK).hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
        backBtn.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        backBtn.layout(l -> l.width(36.0f).height(16.0f));
        backBtn.setOnClick(e -> {
            editInfoTarget = null;
            GuiCreateBuildingInfo.restoreForTerminal();
            rebuild.run();
        });
        btnRow.addChild(backBtn);
    }

    /** 编辑信息图标行: 标签 + 当前图标状态 + [选择图片...] (不选图保存时保留原图标). */
    private static void addEditInfoIconRow(UIElement parent, Runnable rebuild) {
        UIElement row = new UIElement();
        row.layout(l -> l.widthPercent(100.0f).height(16.0f).flexDirection(FlexDirection.ROW)
                .gapAll(4.0f).alignItems(AlignItems.CENTER));
        parent.addChild(row);
        Label lbl = new Label();
        lbl.setText(I18n.get("gui.modern_terminal.create.field.icon"), false);
        lbl.layout(l -> l.width(52.0f).height(16.0f));
        row.addChild(lbl);

        boolean has = GuiCreateBuildingInfo.hasIconData();
        Label val = new Label();
        val.setText(has ? truncate(GuiCreateBuildingInfo.getIconPathValue(), 20)
                : I18n.get("gui.modern_terminal.create.edit.icon_keep"), false);
        val.textStyle(t -> t.textColor(has ? 0x55FF55 : 0xAAAAAA));
        val.layout(l -> l.flexGrow(1.0f).height(16.0f));
        row.addChild(val);

        Button pickIcon = new Button().setText(I18n.get("gui.modern_terminal.create.pick_icon"), false);
        pickIcon.layout(l -> l.width(58.0f).height(16.0f));
        pickIcon.setOnClick(e -> GuiCreateBuildingInfo.openIconPickerForTerminal(() ->
                Minecraft.getInstance().execute(rebuild)));
        row.addChild(pickIcon);
    }


    /** 创建建筑表单分类行: ‹ 分类值 › 循环切换 CategoryManager 里的分类. */
    private static void addCreateCategoryRow(UIElement parent) {
        UIElement row = new UIElement();
        row.layout(l -> l.widthPercent(100.0f).height(16.0f).flexDirection(FlexDirection.ROW)
                .gapAll(4.0f).alignItems(AlignItems.CENTER));
        parent.addChild(row);
        Label lbl = new Label();
        lbl.setText(I18n.get("gui.modern_terminal.create.field.category"), false);
        lbl.layout(l -> l.width(52.0f).height(16.0f));
        row.addChild(lbl);

        Button prev = new Button().setText("<", false);
        prev.layout(l -> l.width(14.0f).height(16.0f));
        Button next = new Button().setText(">", false);
        next.layout(l -> l.width(14.0f).height(16.0f));
        Label val = new Label();
        val.setText(GuiCreateBuildingInfo.getCategoryValue(), false);
        val.layout(l -> l.flexGrow(1.0f).height(16.0f));

        prev.setOnClick(e -> cycleCreateCategory(val, -1));
        next.setOnClick(e -> cycleCreateCategory(val, 1));
        row.addChild(prev);
        row.addChild(val);
        row.addChild(next);
    }

    /** 分类循环步进 (step = ±1), 更新桥接字段与显示. */
    private static void cycleCreateCategory(Label val, int step) {
        List<String> cats = CategoryManager.get().getCategories();
        if (cats.isEmpty()) {
            return;
        }
        int idx = cats.indexOf(GuiCreateBuildingInfo.getCategoryValue());
        int nextIdx = ((idx < 0 ? 0 : idx) + step + cats.size()) % cats.size();
        GuiCreateBuildingInfo.setCategoryValue(cats.get(nextIdx));
        val.setText(cats.get(nextIdx), false);
    }

    /** 创建建筑表单只读行: 标签 + 灰色值 (选文件/选区后自动填). */
    private static void addCreateReadonlyRow(UIElement parent, String label, String value) {
        UIElement row = new UIElement();
        row.layout(l -> l.widthPercent(100.0f).height(16.0f).flexDirection(FlexDirection.ROW)
                .gapAll(4.0f).alignItems(AlignItems.CENTER));
        parent.addChild(row);
        Label lbl = new Label();
        lbl.setText(label, false);
        lbl.layout(l -> l.width(52.0f).height(16.0f));
        row.addChild(lbl);
        Label val = new Label();
        val.setText(value == null || value.isEmpty() ? "-" : value, false);
        val.textStyle(t -> t.textColor(0xAAAAAA));
        val.layout(l -> l.flexGrow(1.0f).height(16.0f));
        row.addChild(val);
    }

    /** 更新创建建筑应用的状态提示 (随 rebuild 显示在面板底部). */
    private static void setCreateStatus(@Nullable String msg, int color) {
        createStatusMsg = (msg == null || msg.isEmpty()) ? null : msg;
        createStatusColor = color;
    }

    /** 设置应用: 左侧分类侧栏 (建造/桌面) + 右侧内容. */
    private static UIElement createSettingsContent() {
        UIElement content = new UIElement();
        content.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100.0f).heightPercent(100.0f)
                .gapAll(4.0f).paddingAll(4.0f));

        Runnable[] rebuildRoot = {null};
        rebuildRoot[0] = () -> {
            content.clearAllChildren();
            UIElement body = new UIElement();
            body.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f)
                    .flexGrow(1.0f).gapAll(4.0f));
            content.addChild(body);

            // 左侧分类侧栏
            UIElement sidebar = new UIElement();
            sidebar.layout(l -> l.flexDirection(FlexDirection.COLUMN).width(64.0f).heightPercent(100.0f)
                    .gapAll(2.0f));
            body.addChild(sidebar);
            addSettingsTabButton(sidebar, I18n.get("gui.modern_terminal.settings.tab.building"), 0, rebuildRoot[0]);
            addSettingsTabButton(sidebar, I18n.get("gui.modern_terminal.settings.tab.desktop"), 1, rebuildRoot[0]);

            // 右侧内容面板
            UIElement panel = new UIElement();
            panel.style(s -> s.backgroundTexture(Sprites.RECT_DARK));
            panel.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1.0f).heightPercent(100.0f)
                    .gapAll(2.0f).paddingAll(6.0f));
            body.addChild(panel);

            if (settingsTab == 0) {
                buildSettingsBuildingPanel(panel, rebuildRoot[0]);
            } else {
                buildSettingsDesktopPanel(panel);
            }

            // 状态提示
            if (settingsMsg != null && !settingsMsg.isEmpty()) {
                Label st = new Label();
                st.setText(settingsMsg, false);
                st.textStyle(t -> t.textColor(settingsColor).textWrap(TextWrap.WRAP));
                st.layout(l -> l.widthPercent(100.0f).height(20.0f));
                content.addChild(st);
            }
        };
        rebuildRoot[0].run();
        return content;
    }

    /** 设置侧栏分类按钮 (同创建建筑侧栏样式). */
    private static void addSettingsTabButton(UIElement sidebar, String name, int index, Runnable rebuild) {
        boolean selected = settingsTab == index;
        Button b = new Button().setText(truncate(name, 8), false);
        b.buttonStyle(s -> {
            if (selected) {
                s.baseTexture(Sprites.RECT_SOLID).hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_SOLID);
            } else {
                s.baseTexture(Sprites.RECT_DARK).hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK);
            }
        });
        b.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).fontSize(6.5f));
        b.layout(l -> l.widthPercent(100.0f).height(16.0f));
        b.setOnClick(e -> {
            settingsTab = index;
            rebuild.run();
        });
        sidebar.addChild(b);
    }

    /** 设置 - 建造分类: 移自主模组编辑器设置页 (依赖检测 + 性能/动画 + 重置). */
    private static void buildSettingsBuildingPanel(UIElement panel, Runnable rebuild) {
        PlayerPreferences p = PlayerPreferences.get();

        // 材料规则提示: 挑战模式开关已移除, 生存/冒险固定消耗材料, 创造免费
        addSettingsHint(panel, I18n.get("gui.modern_terminal.settings.material_rule_hint"), false);

        // 打开建筑时自动检测依赖
        Button deps = new Button().setText(I18n.get("gui.modern_terminal.settings.building.deps")
                + ": " + (p.autoDepCheckOnOpen ? "ON" : "OFF"), false);
        deps.buttonStyle(s -> s.baseTexture(Sprites.RECT_DARK)
                .hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
        deps.layout(l -> l.widthPercent(100.0f).height(16.0f));
        deps.setOnClick(e -> {
            p.autoDepCheckOnOpen = !p.autoDepCheckOnOpen;
            p.save();
            settingsMsg = I18n.get(p.autoDepCheckOnOpen
                    ? "gui.modern_terminal.settings.deps_on" : "gui.modern_terminal.settings.deps_off");
            settingsColor = 0x55FF55;
            rebuild.run();
        });
        panel.addChild(deps);
        addSettingsHint(panel, I18n.get("gui.modern_terminal.settings.building.deps.hint"), false);

        // 建造动画模式 (4 状态循环: OFF → FALL → RAIN → THROW)
        BuildAnimationMode mode = p.getBuildAnimationMode();
        Button anim = new Button().setText(I18n.get("gui.modern_terminal.settings.building.anim")
                + ": " + mode, false);
        anim.buttonStyle(s -> s.baseTexture(Sprites.RECT_DARK)
                .hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
        anim.layout(l -> l.widthPercent(100.0f).height(16.0f));
        anim.setOnClick(e -> {
            p.setBuildAnimationMode(p.getBuildAnimationMode().next());
            settingsMsg = I18n.get("gui.modern_terminal.settings.building.anim")
                    + ": " + p.getBuildAnimationMode();
            settingsColor = 0x55FF55;
            rebuild.run();
        });
        panel.addChild(anim);
        addSettingsHint(panel, buildAnimDesc(mode), false);

        // 重置偏好
        Button reset = new Button().setText(I18n.get("gui.modern_terminal.settings.building.reset"), false);
        reset.buttonStyle(s -> s.baseTexture(Sprites.RECT_DARK)
                .hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
        reset.layout(l -> l.widthPercent(100.0f).height(16.0f));
        reset.setOnClick(e -> {
            try {
                p.favoriteKeys = new java.util.ArrayList<>();
                p.autoDepCheckOnOpen = false;
                p.buildAnimationMode = BuildAnimationMode.OFF;
                p.save();
                settingsMsg = I18n.get("gui.modern_terminal.settings.reset_ok");
                settingsColor = 0x55FF55;
            } catch (Throwable t) {
                settingsMsg = I18n.get("gui.modern_terminal.settings.reset_fail", t.getMessage());
                settingsColor = 0xFF5555;
            }
            rebuild.run();
        });
        panel.addChild(reset);
    }

    /** 建造动画模式副标题 (同主模组编辑器文案). */
    private static String buildAnimDesc(BuildAnimationMode mode) {
        return switch (mode) {
            case OFF   -> I18n.get("gui.modern_terminal.settings.anim.off");
            case FALL  -> I18n.get("gui.modern_terminal.settings.anim.fall");
            case RAIN  -> I18n.get("gui.modern_terminal.settings.anim.rain");
            case THROW -> I18n.get("gui.modern_terminal.settings.anim.throw");
        };
    }

    /** 设置行下方的小字说明 (disabled=true 时置灰). */
    private static void addSettingsHint(UIElement panel, String text, boolean disabled) {
        Label hint = new Label();
        hint.setText(text, false);
        hint.textStyle(t -> t.textColor(disabled ? 0x888888 : 0xAAAAAA).textWrap(TextWrap.WRAP));
        hint.layout(l -> l.widthPercent(100.0f).height(18.0f));
        panel.addChild(hint);
    }

    /** 设置 - 桌面分类: 主界面背景切换 (原有壁纸设置). */
    private static void buildSettingsDesktopPanel(UIElement panel) {
        Label title = new Label();
        title.setText(I18n.get("gui.modern_terminal.app.settings.wallpaper"), false);
        title.textStyle(t -> t.textAlignHorizontal(Horizontal.LEFT));
        title.layout(l -> l.widthPercent(100.0f).height(12.0f));
        panel.addChild(title);

        Label hint = new Label();
        hint.setText(I18n.get("gui.modern_terminal.app.settings.wallpaper.hint"), false);
        hint.textStyle(t -> t.textAlignHorizontal(Horizontal.LEFT));
        hint.layout(l -> l.widthPercent(100.0f).height(12.0f));
        panel.addChild(hint);

        UIElement row = new UIElement();
        row.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f).height(20.0f).gapAll(4.0f));
        TextField pathField = new TextField();
        pathField.setText(WallpaperManager.currentPath());
        pathField.layout(l -> l.flexGrow(1.0f).heightPercent(100.0f));
        row.addChild(pathField);

        Button btnBrowse = new Button().setText(I18n.get("gui.modern_terminal.app.settings.wallpaper.browse"), false);
        btnBrowse.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        btnBrowse.layout(l -> l.width(64.0f).heightPercent(100.0f));
        btnBrowse.setOnClick(e -> openWallpaperPicker(pathField));
        row.addChild(btnBrowse);

        Button btnApply = new Button().setText(I18n.get("gui.modern_terminal.app.settings.wallpaper.apply"), false);
        btnApply.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        btnApply.layout(l -> l.width(48.0f).heightPercent(100.0f));
        btnApply.setOnClick(e -> applyWallpaper(pathField.getText()));
        row.addChild(btnApply);

        Button btnClear = new Button().setText(I18n.get("gui.modern_terminal.app.settings.wallpaper.clear"), false);
        btnClear.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        btnClear.layout(l -> l.width(48.0f).heightPercent(100.0f));
        btnClear.setOnClick(e -> {
            WallpaperManager.clear(true);
            pathField.setText("");
            settingsMsg = "";
        });
        row.addChild(btnClear);
        panel.addChild(row);

        Label result = new Label();
        result.bindDataSource(com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.SupplierDataSource
                .of(() -> Component.literal(settingsMsg)));
        result.textStyle(t -> t.textAlignHorizontal(Horizontal.LEFT).textAlignVertical(Vertical.CENTER));
        result.layout(l -> l.widthPercent(100.0f).height(12.0f));
        panel.addChild(result);
    }

    /**
     * 打开系统文件选择器.
     *
     * 用 LWJGL 自带的 TinyFD 而不是 Swing/AWT: Minecraft 启动时会把 {@code java.awt.headless} 置为 true,
     * JFileChooser / FileDialog 一律抛 HeadlessException (日志里就是 "headless 环境, 选择器不可用"),
     * 而且这个属性被 GraphicsEnvironment 缓存后再改也来不及. TinyFD 走 Win32 原生对话框,
     * 并把当前进程的顶层可见窗口 (GLFW 游戏窗) 当 owner, 天然浮在游戏之上.
     *
     * 开在独立线程: 对话框会阻塞到用户关闭, 占渲染线程会卡死游戏.
     * 任何异常都只记日志 + 在设置面板提示, 绝不让游戏崩溃 (玩家仍可手输路径点"应用").
     */
    private static void openWallpaperPicker(TextField field) {
        Thread t = new Thread(() -> {
            String picked;
            try {
                TerminalRegistry.LOGGER.info("[WALLPAPER] 正在打开系统文件选择器 (tinyfd)...");
                picked = pickWithTinyFd();
            } catch (Throwable e) {
                TerminalRegistry.LOGGER.warn("[WALLPAPER] 文件选择器打开失败", e);
                Minecraft.getInstance().tell(() -> settingsMsg = String.format(
                        I18n.get("gui.modern_terminal.app.settings.wallpaper.picker.failed"), e));
                return;
            }
            if (picked == null) {
                TerminalRegistry.LOGGER.info("[WALLPAPER] 用户取消了文件选择");
                return;
            }
            TerminalRegistry.LOGGER.info("[WALLPAPER] 已选择: {}", picked);
            Minecraft.getInstance().tell(() -> {
                field.setText(picked);
                applyWallpaper(picked);
            });
        }, "terminal-wallpaper-picker");
        t.setDaemon(true);
        t.start();
    }

    /** 图片扩展名过滤 (TinyFD 要求 "*.ext" 形式). */
    private static final String[] IMAGE_FILTERS = {"*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif", "*.tga"};

    /** 原生文件对话框; 用户取消时返回 null. */
    @Nullable
    private static String pickWithTinyFd() {
        // MemoryStack 是线程局部的, 必须包住整个调用 (filters 里的 UTF8 缓冲区在调用期间要有效)
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(IMAGE_FILTERS.length);
            for (String pattern : IMAGE_FILTERS) {
                filters.put(stack.UTF8(pattern));
            }
            filters.flip();
            return TinyFileDialogs.tinyfd_openFileDialog(
                    I18n.get("gui.modern_terminal.app.settings.wallpaper.picker.title"),
                    pickerStartDir(),
                    filters,
                    "Image (png/jpg/bmp/gif/tga)",
                    false);
        }
    }

    /** 对话框起始目录: 当前壁纸所在目录 > 图片库 > 用户主目录. */
    @Nullable
    private static String pickerStartDir() {
        String current = WallpaperManager.currentPath();
        if (!current.isBlank()) {
            try {
                Path parent = Path.of(current).getParent();
                if (parent != null && Files.isDirectory(parent)) {
                    return parent.toString();
                }
            } catch (InvalidPathException ignored) {
                // 配置里的路径坏了就回落到默认目录
            }
        }
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return null;
        }
        Path pictures = Path.of(home, "Pictures");
        return Files.isDirectory(pictures) ? pictures.toString() : home;
    }

    private static void applyWallpaper(String path) {
        boolean ok = WallpaperManager.apply(path, true);
        settingsMsg = ok ? "" : String.format(
                I18n.get("gui.modern_terminal.app.settings.wallpaper.failed"), WallpaperManager.lastError());
    }

    /** "用户" 应用: 纯 label 显示用户名与设备名. */
    private static UIElement createUserContent() {
        String playerName = Minecraft.getInstance().player == null ? "" : Minecraft.getInstance().player.getGameProfile().getName();
        UIElement content = new UIElement();
        content.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100.0f).heightPercent(100.0f).gapAll(6.0f).paddingAll(8.0f));
        Label nameLabel = new Label();
        nameLabel.setText(I18n.get("gui.modern_terminal.app.user.name") + ": " + playerName, false);
        nameLabel.layout(l -> l.widthPercent(100.0f).height(12.0f));
        content.addChild(nameLabel);
        Label deviceLabel = new Label();
        deviceLabel.setText(I18n.get("gui.modern_terminal.app.user.device") + ": "
                + String.format(I18n.get("gui.modern_terminal.app.user.device.value"), playerName), false);
        deviceLabel.layout(l -> l.widthPercent(100.0f).height(12.0f));
        content.addChild(deviceLabel);
        return content;
    }

    /** "浏览器" 应用: 地址栏 + 跳转/系统浏览器按钮 + MCEF 嵌入式面板 (无 MCEF 时降级). */
    private static UIElement createBrowserContent(String startUrl) {
        UIElement content = new UIElement();
        content.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100.0f).heightPercent(100.0f).gapAll(4.0f).paddingAll(4.0f));

        UIElement toolbar = new UIElement();
        toolbar.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f).height(20.0f).gapAll(4.0f));
        TextField urlField = new TextField();
        urlField.setText(startUrl);
        urlField.layout(l -> l.flexGrow(1.0f).heightPercent(100.0f));
        toolbar.addChild(urlField);
        Button btnGo = new Button().setText(I18n.get("gui.modern_terminal.app.browser.go"), false);
        btnGo.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        btnGo.layout(l -> l.width(40.0f).heightPercent(100.0f));
        toolbar.addChild(btnGo);
        Button btnSystem = new Button().setText(I18n.get("gui.modern_terminal.app.browser.system"), false);
        btnSystem.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        btnSystem.layout(l -> l.width(64.0f).heightPercent(100.0f));
        btnSystem.setOnClick(e -> openSystemBrowser(urlField.getText()));
        toolbar.addChild(btnSystem);
        content.addChild(toolbar);

        BrowserElement view = new BrowserElement(startUrl);
        // 页内跳转/重定向时 CEF 地址变化回写地址栏 (同真浏览器), 同时记入浏览快照
        view.setOnAddressChange(u -> {
            lastBrowserUrl = u;
            if (!u.equals(urlField.getText())) {
                urlField.setText(u);
            }
        });
        view.layout(l -> l.widthPercent(100.0f).flexGrow(1.0f));
        btnGo.setOnClick(e -> {
            lastBrowserUrl = urlField.getText();
            view.navigate(urlField.getText());
        });
        openBrowsers.add(view);
        lastCreatedBrowser = view;
        content.addChild(view);

        Label hint = new Label();
        // 实时诊断行: 有 MCEF 时显示嵌入链路各环状态 (init/browser/tex/size/err), 黑屏可直接截图定位
        hint.bindDataSource(com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.SupplierDataSource
                .of(() -> Component.literal(browserDebugLine(view))));
        hint.textStyle(t -> t.textAlignHorizontal(Horizontal.LEFT).textAlignVertical(Vertical.CENTER));
        hint.layout(l -> l.widthPercent(100.0f).height(12.0f));
        content.addChild(hint);
        return content;
    }

    private static String browserDebugLine(BrowserElement view) {
        if (!McefBridge.isAvailable()) {
            return I18n.get("gui.modern_terminal.app.browser.no_mcef");
        }
        return view.debugState();
    }

    /** 降级/补充通道: 用操作系统默认浏览器打开真实网页. */
    private static void openSystemBrowser(String raw) {
        try {
            Util.getPlatform().openUri(BrowserElement.normalize(raw));
        } catch (Throwable ignored) {
            // 系统浏览器打不开时静默, 不炸界面
        }
    }

    private static void disposeBrowser(BrowserElement b) {
        b.dispose();
        openBrowsers.remove(b);
    }

    private static void disposeAllBrowsers() {
        for (BrowserElement b : List.copyOf(openBrowsers)) {
            b.dispose();
        }
        openBrowsers.clear();
    }
}
