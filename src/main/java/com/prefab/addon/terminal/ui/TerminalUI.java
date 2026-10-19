package com.prefab.addon.terminal.ui;

import com.lowdragmc.lowdraglib2.gui.factory.HeldItemUIMenuType;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.SupplierDataSource;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Tab;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TabView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.inventory.InventorySlots;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.ui.style.Stylesheet;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.prefab.addon.terminal.TerminalRegistry;
import com.prefab.addon.terminal.device.TerminalDeviceHandler;
import com.prefab.addon.terminal.network.TerminalPayloads;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerListener;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 终端 UI 工厂 (common, 双侧执行).
 *
 * Menu UI 架构: 服务端 openMenu 与客户端 menu factory 各调一次 {@link #create},
 * 因此本类绝不允许 import 任何 net.minecraft.client.* 或本 mod client 包类.
 *
 * 双侧树结构约定 (UISyncManager 按 SyncValue 注册顺序索引同步):
 *   - common 部分 (主界面 tab) 双侧逐行同构建;
 *   - 客户端应用 tab (相册/游戏/设置/用户/浏览器) 经 {@link #clientSetup} 钩子在
 *     create 返回前追加, 内容全部是客户端数据源 (bindDataSource/自绘), 不注册任何
 *     SyncValue, 不影响同步索引.
 */
public final class TerminalUI {

    /** 应用注册表 (模板阶段图标用原版物品占位, 后续换自定义贴图). */
    public record AppDef(String id, Supplier<ItemStack> icon) {
        public String nameKey() {
            return "gui.modern_terminal.app." + id;
        }
    }

    public static final List<AppDef> APPS = List.of(
            new AppDef("buildings", () -> new ItemStack(Blocks.BRICKS)),
            new AppDef("material", () -> new ItemStack(Items.PAPER)),
            new AppDef("create", () -> new ItemStack(Blocks.CRAFTING_TABLE)),
            new AppDef("recycle", () -> new ItemStack(Items.HOPPER)),
            new AppDef("storage", () -> new ItemStack(Blocks.CHEST)),
            new AppDef("camera", () -> new ItemStack(Items.SPYGLASS)),
            new AppDef("album", () -> new ItemStack(Items.PAINTING)),
            new AppDef("game", () -> new ItemStack(Items.NOTE_BLOCK)),
            new AppDef("settings", () -> new ItemStack(Items.COMPASS)),
            new AppDef("user", () -> new ItemStack(Items.PLAYER_HEAD)),
            new AppDef("browser", () -> new ItemStack(Items.MAP)),
            new AppDef("webget", () -> new ItemStack(Items.BUNDLE)),
            new AppDef("appstore", () -> new ItemStack(Items.BOOKSHELF)));

    /** 客户端装配上下文: common 骨架建好后交给客户端补应用 tab / 恢复状态.
     *  homeGrid 是主界面图标网格 (无 SyncValue, 客户端可安全就地增删子元素做桌面刷新). */
    public record ClientContext(TabView tabView, Tab homeTab, Tab recycleTab, Tab storageTab,
                                UIElement homeGrid, ItemStack terminal) {
    }

    // ==== 回收站 (服务端真源; 关 GUI 时 TerminalEvents 负责还物 + 注销) ====

    /** 回收站槽位数. */
    public static final int RECYCLE_SLOT_COUNT = 10;
    /** 服务端回收站容器 (menu 打开期间有效, UUID → bin). */
    private static final Map<UUID, SimpleContainer> RECYCLE_BINS = new HashMap<>();

    /** 服务端: 取当前玩家 menu 的回收站容器 (null = 终端未打开 / 无). */
    public static @Nullable SimpleContainer recycleBin(UUID uuid) {
        return RECYCLE_BINS.get(uuid);
    }

    /** 服务端: menu 关闭时取走并注销回收站容器. */
    public static @Nullable SimpleContainer removeRecycleBin(UUID uuid) {
        return RECYCLE_BINS.remove(uuid);
    }

    // ==== 存储软件 (服务端真源; 关 GUI 时 TerminalEvents 负责持久化 + 合成格还物 + 注销) ====

    /** 存储格槽位数 (9x6). */
    public static final int STORAGE_SLOT_COUNT = 54;
    /** 合成网格格数 (3x3). */
    public static final int CRAFT_GRID_COUNT = 9;

    /** 一次存储软件会话的三个容器 (双侧树里各绑一份; 服务端为真源). */
    public record StorageSession(SimpleContainer storage, SimpleContainer craftGrid, SimpleContainer result) {
    }

    /** 服务端存储会话 (menu 打开期间有效, UUID → session). */
    private static final Map<UUID, StorageSession> STORAGE_SESSIONS = new HashMap<>();

    /** 多配方切换索引 (双侧各自维护: 客户端本地预测, 服务端真源; create 时归零, 关 GUI 服务端注销). */
    private static final Map<UUID, Integer> CRAFT_RECIPE_INDEX = new HashMap<>();

    /**
     * 诊断: dump 存储格/合成格/回收站三个容器的非空槽位 (排查 shift+点击物品去向).
     * 问题定位后可整体删除 (调用点: create 服务端分支 + TerminalEvents 关 GUI).
     */
    public static void diagDumpContainers(Player player, String phase) {
        StorageSession session = STORAGE_SESSIONS.get(player.getUUID());
        StringBuilder sb = new StringBuilder();
        appendContainerDiag(sb, "storage", session == null ? null : session.storage());
        appendContainerDiag(sb, "craftGrid", session == null ? null : session.craftGrid());
        appendContainerDiag(sb, "recycleBin", RECYCLE_BINS.get(player.getUUID()));
        TerminalRegistry.LOGGER.info("[STORAGE-DIAG] {} {}:{}", player.getName().getString(), phase, sb);
    }

    private static void appendContainerDiag(StringBuilder sb, String name, @Nullable SimpleContainer container) {
        sb.append(' ').append(name).append('=');
        if (container == null) {
            sb.append("<none>");
            return;
        }
        boolean any = false;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty()) {
                sb.append(" #").append(i).append('=')
                        .append(stack.getItem()).append('x').append(stack.getCount());
                any = true;
            }
        }
        if (!any) {
            sb.append("empty");
        }
    }

    /** 服务端: 关 GUI 兜底 — 最后持久化存储格 + 合成格剩余还给玩家 + 注销会话. */
    public static void closeStorageSession(ServerPlayer player) {
        StorageSession session = STORAGE_SESSIONS.remove(player.getUUID());
        CRAFT_RECIPE_INDEX.remove(player.getUUID());
        if (session == null) {
            return;
        }
        persistStorage(player, session.storage());
        for (int i = 0; i < session.craftGrid().getContainerSize(); i++) {
            ItemStack left = session.craftGrid().getItem(i);
            if (!left.isEmpty()) {
                session.craftGrid().setItem(i, ItemStack.EMPTY);
                if (!player.getInventory().add(left)) {
                    player.drop(left, false);
                }
            }
        }
    }

    // ==== 客户端钩子 (client setup 赋值, dedicated server 恒为 null, common 类不碰客户端类) ====

    /** 主界面壁纸绘制: GUIContext/UIElement 是 common 类型, 客户端实现里才碰具体渲染. */
    public static volatile @Nullable BiConsumer<GUIContext, UIElement> wallpaperRenderer;
    /** 主界面图标点击 → 打开/切换应用 tab (只有客户端输入事件会触发). */
    public static volatile @Nullable Consumer<String> appOpener;
    /** common 骨架建完后的客户端收尾 (恢复已开 tab / 挂弹窗监听等). */
    public static volatile @Nullable Consumer<ClientContext> clientSetup;
    /** 客户端 GUI 可视区尺寸 [guiScaledWidth, guiScaledHeight] (client setup 赋值; 服务端为 null 走默认). */
    public static volatile @Nullable Supplier<float[]> guiScreenSize;

    private TerminalUI() {
    }

    /** HeldItemUI.createUI 的共同实现 (双侧各跑一次). */
    public static ModularUI create(HeldItemUIMenuType.HeldItemUIHolder holder) {
        Player player = holder.player;
        boolean clientSide = player.level().isClientSide;

        // 双侧各建一份设备槽 handler: 服务端是持久化真源 (登记 SERVER_INSTANCES),
        // 客户端是 menu 槽同步的镜像 (登记 clientInstance 供应用读电量).
        TerminalDeviceHandler handler = new TerminalDeviceHandler(
                holder.itemStack, clientSide, player.registryAccess(),
                clientSide ? null : player, clientSide ? null : holder.hand);
        if (clientSide) {
            TerminalDeviceHandler.registerClient(handler);
        } else {
            TerminalDeviceHandler.registerServer(player.getUUID(), handler);
            // 诊断: createUI 发生在 openMenu 流程内 — 对比 holder 持有的 stack 与此刻背包里的 stack 是否同一实例
            ItemStack inHand = player.getItemInHand(holder.hand);
            TerminalRegistry.LOGGER.info("[DEVICE] createUI(server): holder.stack@{} 手上@{} 同一实例={}",
                    System.identityHashCode(holder.itemStack), System.identityHashCode(inHand),
                    holder.itemStack == inHand);
        }

        // 期望 480x360, 按客户端 GUI 可视区夹取 (留 8px 边距), 避免小屏下溢出;
        // 服务端无钩子走默认尺寸, 几何差异不影响按索引同步.
        Supplier<float[]> screenSize = guiScreenSize;
        float[] clamped = screenSize == null ? null : screenSize.get();
        final float width = clamped == null ? 480.0f : Math.min(480.0f, clamped[0] - 8.0f);
        final float height = clamped == null ? 360.0f : Math.min(360.0f, clamped[1] - 8.0f);
        UIElement root = new UIElement();
        root.layout(l -> l.width(width).height(height)
                .flexDirection(FlexDirection.COLUMN).paddingAll(2.0f));
        root.style(s -> s.background(Sprites.BORDER));
        root.setOverflowVisible(false);

        TabView tabView = new TabView();
        tabView.layout(l -> l.widthPercent(100.0f).heightPercent(100.0f).flexGrow(1.0f));

        // 回收站容器: 双侧各一份, 服务端为真源 (登记 RECYCLE_BINS, 清除 payload 与关 GUI 还物都用它),
        // 客户端这份是 menu 槽同步的镜像.
        SimpleContainer recycleBin = new SimpleContainer(RECYCLE_SLOT_COUNT);
        if (!clientSide) {
            RECYCLE_BINS.put(player.getUUID(), recycleBin);
        }

        // 存储软件容器: 双侧各一份 (服务端真源 + 客户端镜像, 与回收站同模式)
        SimpleContainer storage = new SimpleContainer(STORAGE_SLOT_COUNT);
        SimpleContainer craftGrid = new SimpleContainer(CRAFT_GRID_COUNT);
        SimpleContainer craftResult = new SimpleContainer(1);
        if (!clientSide) {
            loadStorage(player, storage);
            STORAGE_SESSIONS.put(player.getUUID(), new StorageSession(storage, craftGrid, craftResult));
            // 诊断: 打开时 dump 持久化读回内容
            diagDumpContainers(player, "open");
            // 实时持久化: 任何存储槽位变化立即写 PlayerPersistentData (关 GUI 兜底再写一次)
            storage.addListener(new ContainerListener() {
                @Override
                public void containerChanged(Container container) {
                    persistStorage(player, storage);
                }
            });
        }
        // 双侧: 本次会话多配方切换索引归零
        CRAFT_RECIPE_INDEX.put(player.getUUID(), 0);

        UIElement[] homeGridOut = new UIElement[1];
        Tab homeTab = new Tab().setText(Component.translatable("gui.modern_terminal.tab.home"));
        tabView.addTab(homeTab, createHomeContent(homeGridOut));

        // 存储 tab 必须先于回收站注册: LDLib2 的 calculateStyleAndLayout 只在客户端跑 (ModularUI.init
        // 是 @OnlyIn(CLIENT) + 渲染循环), 服务端 styleBag 永远没有 computed 值 → 服务端
        // quickMovePriority() 恒 0 / acceptQuickMove() 恒 true, slotStyle 设置在服务端 quickMove
        // 路径整体无效; 目标槽位排序退化为 menu 槽注册序, 存储格最先注册即成为 shift+点击第一目的地.
        Tab storageTab = new Tab().setText(Component.translatable("gui.modern_terminal.app.storage"));
        tabView.addTab(storageTab, createStorageContent(storage, craftGrid, craftResult, player, clientSide));

        // 回收站 tab 常驻 (双侧同序注册 menu 槽; 存储格满时 shift+点击的溢出物品会落进这里,
        // 关 GUI 时 TerminalEvents 统一还物, 物品不丢)
        Tab recycleTab = new Tab().setText(Component.translatable("gui.modern_terminal.app.recycle"));
        tabView.addTab(recycleTab, createRecycleContent(recycleBin, player, clientSide));

        root.addChild(tabView);

        ModularUI ui = ModularUI.of(
                UI.of(root, new Stylesheet[]{StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)}),
                player);

        if (clientSide) {
            Consumer<ClientContext> setup = clientSetup;
            if (setup != null) {
                setup.accept(new ClientContext(tabView, homeTab, recycleTab, storageTab,
                        homeGridOut[0], holder.itemStack));
            }
        }
        return ui;
    }

    // ==================== 主界面 tab (common 骨架, 客户端交互走钩子) ====================

    /** 主界面 tab 内容: 应用图标网格 (图标下写名字). gridOut[0] 回传网格元素 (客户端据此刷新桌面). */
    private static UIElement createHomeContent(UIElement[] gridOut) {
        UIElement home = new UIElement() {
            @Override
            public void drawBackgroundAdditional(GUIContext ctx) {
                // 壁纸铺满主界面内容面板, 画在图标/状态条之下 (客户端钩子, 服务端不渲染)
                BiConsumer<GUIContext, UIElement> hook = wallpaperRenderer;
                if (hook != null) {
                    hook.accept(ctx, this);
                }
                super.drawBackgroundAdditional(ctx);
            }
        };
        home.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100.0f).heightPercent(100.0f)
                .gapAll(6.0f).paddingAll(6.0f).minHeight(0.0f));

        UIElement grid = new UIElement();
        grid.layout(l -> l.flexDirection(FlexDirection.ROW).flexWrap(FlexWrap.WRAP)
                .alignContent(AlignContent.FLEX_START)
                .gapAll(10.0f).widthPercent(100.0f).flexGrow(1.0f).minHeight(0.0f));
        for (AppDef app : APPS) {
            grid.addChild(appCell(app));
        }
        home.addChild(grid);
        gridOut[0] = grid;
        return home;
    }

    /** 单个应用格子: 正方形图标 + 下方名字; 左键经 {@link #appOpener} 钩子打开应用 tab.
     *  public: 应用中心安装/卸载后客户端重建桌面网格复用. */
    public static UIElement appCell(AppDef app) {
        UIElement cell = new UIElement();
        cell.layout(l -> l.flexDirection(FlexDirection.COLUMN).alignItems(AlignItems.CENTER)
                .width(56.0f).height(50.0f).gapAll(2.0f));

        // 图标区必须正方形: ItemStackTexture 按 scale(w/16, h/16) 独立拉伸 xy
        UIElement icon = new UIElement();
        icon.layout(l -> l.width(32.0f).height(32.0f));
        icon.style(s -> s.backgroundTexture(new ItemStackTexture(app.icon().get())));
        cell.addChild(icon);

        Label name = new Label();
        name.setText(Component.translatable(app.nameKey()));
        name.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textAlignVertical(Vertical.CENTER));
        name.layout(l -> l.widthPercent(100.0f).height(12.0f));
        cell.addChild(name);

        cell.addEventListener(UIEvents.MOUSE_DOWN, event -> {
            if (event.button == 0) {
                Consumer<String> opener = appOpener;
                if (opener != null) {
                    opener.accept(app.id());
                }
            }
        });
        return cell;
    }

    // ==================== 回收站 tab (common, 服务端绑定槽位在此) ====================

    /**
     * 回收站内容: 上面 10 个 bin 槽位 (vanilla menu 语义: 左键拿放/右键半组/shift 快捷移动,
     * 光标物品由 ModularUIContainerScreen 原生渲染) + 待清除计数 + 清除按钮 (两段点击防误碰)
     * + 玩家背包 InventorySlots.
     */
    private static UIElement createRecycleContent(SimpleContainer bin, Player player, boolean clientSide) {
        UIElement content = new UIElement();
        content.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100.0f).heightPercent(100.0f)
                .gapAll(6.0f).paddingAll(6.0f).alignItems(AlignItems.CENTER));

        Label hint = new Label();
        hint.setText(Component.translatable("gui.modern_terminal.recycle.hint"));
        hint.textStyle(t -> t.textAlignHorizontal(Horizontal.LEFT));
        hint.layout(l -> l.widthPercent(100.0f).height(12.0f));
        content.addChild(hint);

        // 10 个回收槽位水平排开
        UIElement slotRow = new UIElement();
        slotRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f).height(20.0f)
                .gapAll(4.0f).alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER));
        for (int i = 0; i < RECYCLE_SLOT_COUNT; i++) {
            ItemSlot slot = new ItemSlot().bind(new Slot(bin, i, 0, 0) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    // 回收站也拒收建筑终端本体: 防 shift+点击绕进回收站后被一键清除销毁
                    return stack.getItem() != TerminalRegistry.TERMINAL.get();
                }
            });
            slot.layout(l -> l.width(18.0f).height(18.0f));
            // 不接受 shift 快捷移入: 回收站在别的 tab, 隐形吞物品.
            // 注: LDLib2 服务端不算样式, 此设置当前只在客户端生效; 服务端靠注册序兜底
            // (回收站注册在存储格之后, shift+点击到不了这里).
            slot.slotStyle(style -> style.acceptQuickMove(false));
            slotRow.addChild(slot);
        }
        content.addChild(slotRow);

        // 待清除计数 (客户端实时求值, 读 menu 槽同步的镜像)
        Label count = new Label();
        count.bindDataSource(SupplierDataSource.of(() -> Component.translatable(
                "gui.modern_terminal.recycle.count", countStacks(bin))));
        count.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textColor(0x55FF55));
        count.layout(l -> l.widthPercent(100.0f).height(12.0f));
        content.addChild(count);

        // 清除按钮: 两段点击防误碰 (第一次变红要求确认, 再点才发清除 payload)
        Button clear = new Button();
        clear.setText(Component.translatable("gui.modern_terminal.recycle.clear"));
        clear.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textColor(0xFFFFFF));
        clear.layout(l -> l.width(96.0f).height(16.0f));
        if (clientSide) {
            final boolean[] armed = {false};
            clear.setOnClick(e -> {
                if (!armed[0]) {
                    armed[0] = true;
                    clear.setText(Component.translatable("gui.modern_terminal.recycle.clear_confirm"));
                    clear.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textColor(0xFF5555));
                } else {
                    armed[0] = false;
                    clear.setText(Component.translatable("gui.modern_terminal.recycle.clear"));
                    clear.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textColor(0xFFFFFF));
                    TerminalPayloads.sendClearRecycle();
                }
            });
        }
        content.addChild(clear);

        // 背包区标题 + InventorySlots (玩家背包 36 格, 原生 shift 快捷移动, 占剩余高度)
        Label invTitle = new Label();
        invTitle.setText(Component.translatable("gui.modern_terminal.app.device.inv_title"));
        invTitle.textStyle(t -> t.textAlignHorizontal(Horizontal.LEFT));
        invTitle.layout(l -> l.widthPercent(100.0f).height(12.0f));
        content.addChild(invTitle);

        InventorySlots inv = new InventorySlots();
        inv.layout(l -> l.flexGrow(1.0f));
        content.addChild(inv);

        return content;
    }

    /** 回收站里非空槽位数. */
    private static int countStacks(SimpleContainer bin) {
        int n = 0;
        for (int i = 0; i < bin.getContainerSize(); i++) {
            if (!bin.getItem(i).isEmpty()) {
                n++;
            }
        }
        return n;
    }

    // ==================== 存储 tab (common, 服务端绑定槽位在此) ====================

    /**
     * 存储内容: 左列 = 54 格持久存储 + 玩家背包; 右列 = 3x3 工作台 + 结果槽.
     * 存储格/合成格是 vanilla menu 语义槽位 (服务端真源); 结果槽只读预览,
     * 取出时消耗材料 (onTake + setByPlayer 双路径守卫, 防重复消耗/白拿).
     */
    private static UIElement createStorageContent(SimpleContainer storage, SimpleContainer craftGrid,
                                                  SimpleContainer craftResult, Player player, boolean clientSide) {
        UIElement content = new UIElement();
        content.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f).heightPercent(100.0f)
                .gapAll(10.0f).paddingAll(6.0f));

        // 左列: 存储 9x6 + 背包 (宽 178 = 9*18 + 8*2)
        UIElement left = new UIElement();
        left.layout(l -> l.flexDirection(FlexDirection.COLUMN).width(178.0f).gapAll(4.0f));
        content.addChild(left);

        // 标题行: "存储空间" + 一键整理按钮
        UIElement titleRow = new UIElement();
        titleRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f).height(14.0f)
                .alignItems(AlignItems.CENTER).gapAll(4.0f));
        Label storageTitle = new Label();
        storageTitle.setText(Component.translatable("gui.modern_terminal.storage.title"));
        storageTitle.layout(l -> l.flexGrow(1.0f).height(12.0f));
        titleRow.addChild(storageTitle);
        Button sort = new Button();
        sort.setText(Component.translatable("gui.modern_terminal.storage.sort"));
        sort.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textColor(0xFFFFFF));
        sort.layout(l -> l.width(52.0f).height(14.0f));
        if (clientSide) {
            sort.setOnClick(e -> TerminalPayloads.sendSortStorage());
        }
        titleRow.addChild(sort);
        left.addChild(titleRow);

        UIElement grid = new UIElement();
        grid.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100.0f).gapAll(2.0f));
        for (int row = 0; row < STORAGE_SLOT_COUNT / 9; row++) {
            UIElement slotRow = new UIElement();
            slotRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f)
                    .height(18.0f).gapAll(2.0f));
            for (int col = 0; col < 9; col++) {
                int index = row * 9 + col;
                ItemSlot slot = new ItemSlot().bind(new Slot(storage, index, 0, 0) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        // 存储格拒收建筑终端本体 (手动放置与 shift+点击都过 menu 的 mayPlace)
                        return stack.getItem() != TerminalRegistry.TERMINAL.get();
                    }
                });
                slot.layout(l -> l.width(18.0f).height(18.0f));
                // shift 快捷移入的第一目标: LDLib2 服务端不算样式, priority 当前无效,
                // 实际靠"存储 tab 最先注册"的注册序兜底 (LDLib2 修复样式计算后自动生效)
                slot.slotStyle(style -> style.quickMovePriority(100));
                slotRow.addChild(slot);
            }
            grid.addChild(slotRow);
        }
        left.addChild(grid);

        Label invTitle = new Label();
        invTitle.setText(Component.translatable("gui.modern_terminal.app.device.inv_title"));
        invTitle.layout(l -> l.widthPercent(100.0f).height(12.0f));
        left.addChild(invTitle);

        InventorySlots inv = new InventorySlots();
        inv.layout(l -> l.flexGrow(1.0f));
        left.addChild(inv);

        // 右列: 3x3 工作台 + 结果槽
        UIElement right = new UIElement();
        right.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1.0f).gapAll(4.0f));
        content.addChild(right);

        Label craftTitle = new Label();
        craftTitle.setText(Component.translatable("gui.modern_terminal.storage.craft_title"));
        craftTitle.layout(l -> l.widthPercent(100.0f).height(12.0f));
        right.addChild(craftTitle);

        UIElement craftRow = new UIElement();
        craftRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f)
                .alignItems(AlignItems.CENTER).gapAll(8.0f));
        right.addChild(craftRow);

        UIElement craftGridEl = new UIElement();
        craftGridEl.layout(l -> l.flexDirection(FlexDirection.COLUMN).width(62.0f).gapAll(2.0f));
        for (int row = 0; row < 3; row++) {
            UIElement slotRow = new UIElement();
            slotRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f)
                    .height(18.0f).gapAll(2.0f));
            for (int col = 0; col < 3; col++) {
                int index = row * 3 + col;
                ItemSlot slot = new ItemSlot().bind(new Slot(craftGrid, index, 0, 0));
                slot.layout(l -> l.width(18.0f).height(18.0f));
                // 与原版工作台一致: 不接受 shift 快捷移入合成格 (手动点击不受影响).
                // 服务端此设置当前无效 (样式不计算), 但合成格注册在存储格之后, 注册序兜底
                slot.slotStyle(style -> style.acceptQuickMove(false));
                slotRow.addChild(slot);
            }
            craftGridEl.addChild(slotRow);
        }
        craftRow.addChild(craftGridEl);

        Label arrow = new Label();
        arrow.setText(Component.literal("->"));
        arrow.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textAlignVertical(Vertical.CENTER));
        arrow.layout(l -> l.width(18.0f).height(18.0f));
        craftRow.addChild(arrow);

        // 多配方计数 Label 先于 result 槽创建: CraftResultSlot 取走产物后要同步刷新它
        Label recipePos = new Label();
        recipePos.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textAlignVertical(Vertical.CENTER));
        recipePos.layout(l -> l.width(28.0f).height(18.0f));

        ItemSlot resultSlot = new ItemSlot().bind(new CraftResultSlot(player, craftGrid, craftResult, recipePos));
        resultSlot.layout(l -> l.width(18.0f).height(18.0f));
        craftRow.addChild(resultSlot);

        // 多配方切换: 同一合成布局匹配到多个配方时用 < > 切换合成哪个物品,
        // 中间动态显示 "当前/总数" (单配方时按钮无效果且计数隐藏)
        Button prevRecipe = new Button();
        prevRecipe.setText(Component.literal("<"));
        prevRecipe.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textColor(0xFFFFFF));
        prevRecipe.layout(l -> l.width(16.0f).height(18.0f));
        Button nextRecipe = new Button();
        nextRecipe.setText(Component.literal(">"));
        nextRecipe.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textColor(0xFFFFFF));
        nextRecipe.layout(l -> l.width(16.0f).height(18.0f));
        if (clientSide) {
            // 客户端本地先跑一次切换保预览一致 (预测), 再发 payload 让服务端真源同步
            prevRecipe.setOnClick(e -> {
                cycleCraftRecipe(player, -1, craftGrid, craftResult);
                TerminalPayloads.sendCycleCraftRecipe(-1);
            });
            nextRecipe.setOnClick(e -> {
                cycleCraftRecipe(player, 1, craftGrid, craftResult);
                TerminalPayloads.sendCycleCraftRecipe(1);
            });
        }
        craftRow.addChild(prevRecipe);

        craftRow.addChild(recipePos);
        craftRow.addChild(nextRecipe);

        // 双侧: 合成格变化 → 重算结果预览 + 刷新多配方计数 (listener 必须在 Label 构建后注册,
        // 避免数据源每帧全量匹配配方; result 容器不挂 listener, 防递归)
        craftGrid.addListener(new ContainerListener() {
            @Override
            public void containerChanged(Container container) {
                TerminalRegistry.LOGGER.info("[CRAFT-DIAG] {} gridChanged: {}", side(player), describeGrid(craftGrid));
                List<RecipeHolder<CraftingRecipe>> recipes = matchedRecipes(player, craftGrid);
                recipePos.setText(recipes.size() <= 1 ? Component.empty()
                        : Component.literal((recipeIndex(player, recipes) + 1) + "/" + recipes.size()));
                refreshCraftResult(craftGrid, craftResult, player, recipes);
            }
        });

        Label craftHint = new Label();
        craftHint.setText(Component.translatable("gui.modern_terminal.storage.craft_hint"));
        craftHint.layout(l -> l.widthPercent(100.0f).height(12.0f));
        right.addChild(craftHint);

        return content;
    }

    // ==================== 存储软件服务端动作 (payload 入口) ====================

    /**
     * 一键整理存储格: 同物品合堆 (组件级一致才合并, 防不同 NBT 混堆),
     * 按物品注册名 + 同物品数量降序排布, 从 0 号槽写回.
     * 每次 setItem 都触发持久化 listener 与 menu 槽同步.
     */
    public static void sortStorage(ServerPlayer player) {
        StorageSession session = STORAGE_SESSIONS.get(player.getUUID());
        if (session == null) {
            return;
        }
        SimpleContainer storage = session.storage();
        List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < storage.getContainerSize(); i++) {
            ItemStack s = storage.getItem(i);
            if (!s.isEmpty()) {
                stacks.add(s.copy());
            }
            storage.setItem(i, ItemStack.EMPTY);
        }
        // 合并: 优先填满已有堆, 余量自成新堆
        List<ItemStack> merged = new ArrayList<>();
        for (ItemStack s : stacks) {
            int rest = s.getCount();
            for (ItemStack t : merged) {
                if (rest <= 0) {
                    break;
                }
                if (ItemStack.isSameItemSameComponents(t, s) && t.getCount() < t.getMaxStackSize()) {
                    int move = Math.min(rest, t.getMaxStackSize() - t.getCount());
                    t.grow(move);
                    rest -= move;
                }
            }
            if (rest > 0) {
                ItemStack rem = s.copy();
                rem.setCount(rest);
                merged.add(rem);
            }
        }
        // 排序: 物品注册名升序, 同物品大堆在前
        merged.sort(Comparator
                .comparing((ItemStack s) -> BuiltInRegistries.ITEM.getKey(s.getItem()).toString())
                .thenComparing(s -> -s.getCount()));
        for (int i = 0; i < merged.size(); i++) {
            storage.setItem(i, merged.get(i));
        }
    }

    /** 服务端: 多配方切换入口 (CycleCraftRecipeC2S), 切换后 result 槽随 broadcastChanges 同步客户端. */
    public static void cycleCraftRecipe(ServerPlayer player, int delta) {
        StorageSession session = STORAGE_SESSIONS.get(player.getUUID());
        if (session == null) {
            return;
        }
        cycleCraftRecipe(player, delta, session.craftGrid(), session.result());
    }

    /**
     * 多配方切换核心 (双侧共用): 同一合成布局匹配到多个配方时推进/回退切换索引并重算结果预览.
     * 客户端按钮点击时本地先跑一次保预测一致, 服务端由 payload 驱动.
     * 单配方或空格时无操作; 索引按配方数取模 (wrap), 配方数变化时自动收敛.
     */
    private static void cycleCraftRecipe(Player player, int delta, SimpleContainer grid, SimpleContainer result) {
        Level level = player.level();
        List<RecipeHolder<CraftingRecipe>> recipes = matchedRecipes(player, grid);
        if (recipes.size() <= 1) {
            return;
        }
        int index = Math.floorMod(recipeIndex(player, recipes) + delta, recipes.size());
        CRAFT_RECIPE_INDEX.put(player.getUUID(), index);
        result.setItem(0, recipes.get(index).value().assemble(craftInput(grid), level.registryAccess()));
    }

    /**
     * 工作台结果槽: 只读预览 (mayPlace=false), 取出时消耗材料.
     * 防双扣: setByPlayer 补齐 LDLib2 quickMove 的 onTake 缺失并置抑制标记,
     * SWAP 的 setByPlayer+显式 onTake 双调用由标记去重 (mayPickup 清标记, vanilla 取出路径都先问 mayPickup).
     */
    private static class CraftResultSlot extends Slot {
        private final Player player;
        private final SimpleContainer craftGrid;
        /** 多配方计数 Label: 取走产物消耗材料后同步刷新 (材料变化可能改变可合成配方数). */
        private final Label recipePos;
        /** setByPlayer 刚消耗过: 随后的显式 onTake (SWAP 回声) 只应跳过 (防双扣). */
        private boolean suppressNextOnTake;

        CraftResultSlot(Player player, SimpleContainer craftGrid, SimpleContainer result, Label recipePos) {
            super(result, 0, 0, 0);
            this.player = player;
            this.craftGrid = craftGrid;
            this.recipePos = recipePos;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player p) {
            // vanilla 所有取出路径 (PICKUP/THROW/SWAP) 在 onTake 前都会先问 mayPickup — 顺带清抑制标记
            suppressNextOnTake = false;
            boolean ok = !getItem().isEmpty();
            TerminalRegistry.LOGGER.info("[CRAFT-DIAG] {} mayPickup: result={} -> {}", side(p), describe(getItem()), ok);
            return ok;
        }

        @Override
        public void onTake(Player p, ItemStack stack) {
            ItemStack preview = previewCraftResult(p, craftGrid);
            boolean matches = ItemStack.matches(stack, preview);
            TerminalRegistry.LOGGER.info("[CRAFT-DIAG] {} onTake: taken={} suppress={} matches={} preview={} grid={}",
                    side(p), describe(stack), suppressNextOnTake, matches, describe(preview), describeGrid(craftGrid));
            if (suppressNextOnTake) {
                suppressNextOnTake = false;
                return;
            }
            if (matches) {
                consumeCraftMaterials(p, craftGrid);
                refreshPreviewAfterTake();
            }
        }

        @Override
        public void setByPlayer(ItemStack newStack, ItemStack oldStack) {
            ItemStack before = oldStack;
            super.setByPlayer(newStack, oldStack);
            boolean taken = !before.isEmpty()
                    && (newStack.isEmpty() || newStack.getCount() < before.getCount());
            TerminalRegistry.LOGGER.info("[CRAFT-DIAG] {} setByPlayer: before={} after={} taken={}",
                    side(player), describe(before), describe(newStack), taken);
            // LDLib2 quickMoveStack 只 setByPlayer 不调 onTake → 这里补完整 take 语义;
            // SWAP 的显式 onTake 回声由标记去重. 客户端不消耗 (预测走 onTake 路径).
            if (taken && !player.level().isClientSide) {
                boolean matches = ItemStack.matches(before, previewCraftResult(player, craftGrid));
                TerminalRegistry.LOGGER.info("[CRAFT-DIAG] {} setByPlayer consume-check: matches={}", side(player), matches);
                if (matches) {
                    consumeCraftMaterials(player, craftGrid);
                    refreshPreviewAfterTake();
                }
                suppressNextOnTake = true;
            }
        }

        /**
         * 取走产物并消耗材料后立即重算预览: 材料仍够 → 产物格显示下一个 (仿 vanilla),
         * 不够 → 置空. shrink 不触发合成格 listener, 必须手动刷新; 多配方计数同步更新.
         */
        private void refreshPreviewAfterTake() {
            List<RecipeHolder<CraftingRecipe>> recipes = matchedRecipes(player, craftGrid);
            recipePos.setText(recipes.size() <= 1 ? Component.empty()
                    : Component.literal((recipeIndex(player, recipes) + 1) + "/" + recipes.size()));
            refreshCraftResult(craftGrid, (SimpleContainer) container, player, recipes);
        }
    }

    // ==================== 合成诊断日志 helper (修完 bug 可整体删除) ====================

    /** 诊断用物品简述: 数量 x 物品 + hash 尾缀 (区分 components 不同的实例). */
    private static String describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "EMPTY";
        }
        return stack.getCount() + "x " + stack.getItem() + "@" + Integer.toHexString(stack.hashCode());
    }

    /** 诊断用合成格 9 格简述 (逗号分隔, 空格显示 -). */
    private static String describeGrid(SimpleContainer grid) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < grid.getContainerSize(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            ItemStack s = grid.getItem(i);
            sb.append(s.isEmpty() ? "-" : s.getCount() + "x" + s.getItem());
        }
        return sb.append(']').toString();
    }

    /** 诊断用侧别标记. */
    private static String side(Player p) {
        return p.level().isClientSide ? "CLIENT" : "SERVER";
    }

    /** 合成格当前 9 格内容拷贝 (TransientCraftingContainer 只读构建用). */
    private static NonNullList<ItemStack> gridItems(SimpleContainer grid) {
        NonNullList<ItemStack> items = NonNullList.withSize(grid.getContainerSize(), ItemStack.EMPTY);
        for (int i = 0; i < grid.getContainerSize(); i++) {
            items.set(i, grid.getItem(i));
        }
        return items;
    }

    /** 合成格 9 格 → CraftingInput (配方匹配用). */
    private static CraftingInput craftInput(SimpleContainer grid) {
        TransientCraftingContainer craft = new TransientCraftingContainer(null, 3, 3, gridItems(grid));
        return craft.asCraftInput();
    }

    /**
     * 匹配当前合成格的全部配方 (按 recipe manager 顺序, 双侧一致).
     * 1.21.1 的 getAllRecipesFor 只有单参数版, 这里取全部合成配方后逐个 matches 过滤.
     */
    private static List<RecipeHolder<CraftingRecipe>> matchedRecipes(Player player, SimpleContainer grid) {
        Level level = player.level();
        List<RecipeHolder<CraftingRecipe>> all = level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING);
        if (all.isEmpty()) {
            return List.of();
        }
        CraftingInput input = craftInput(grid);
        List<RecipeHolder<CraftingRecipe>> matched = new ArrayList<>();
        for (RecipeHolder<CraftingRecipe> holder : all) {
            if (holder.value().matches(input, level)) {
                matched.add(holder);
            }
        }
        return matched;
    }

    /** 切换索引按配方数取模 (wrap, 配方数变化时自动收敛). */
    private static int recipeIndex(Player player, List<RecipeHolder<CraftingRecipe>> recipes) {
        return Math.floorMod(CRAFT_RECIPE_INDEX.getOrDefault(player.getUUID(), 0), recipes.size());
    }

    /**
     * 当前合成格匹配的配方产物预览 (双侧各自求值, 客户端 recipeManager 登录同步可用).
     * 多配方时取切换索引指向的配方 (取模 wrap); 无配方返回 EMPTY.
     */
    private static ItemStack previewCraftResult(Player player, SimpleContainer grid) {
        List<RecipeHolder<CraftingRecipe>> recipes = matchedRecipes(player, grid);
        if (recipes.isEmpty()) {
            return ItemStack.EMPTY;
        }
        RecipeHolder<CraftingRecipe> recipe = recipes.get(recipeIndex(player, recipes));
        return recipe.value().assemble(craftInput(grid), player.level().registryAccess());
    }

    /** 合成格变化 → 重算结果预览 (recipes 由调用方预匹配, 避免重复全量遍历; result 容器不挂 listener, 防递归). */
    private static void refreshCraftResult(SimpleContainer grid, SimpleContainer result, Player player,
                                           List<RecipeHolder<CraftingRecipe>> recipes) {
        if (recipes.isEmpty()) {
            TerminalRegistry.LOGGER.info("[CRAFT-DIAG] {} refresh: no recipe -> EMPTY", side(player));
            result.setItem(0, ItemStack.EMPTY);
            return;
        }
        RecipeHolder<CraftingRecipe> recipe = recipes.get(recipeIndex(player, recipes));
        ItemStack out = recipe.value().assemble(craftInput(grid), player.level().registryAccess());
        TerminalRegistry.LOGGER.info("[CRAFT-DIAG] {} refresh: idx={}/{} -> {} ({})",
                side(player), recipeIndex(player, recipes), recipes.size(), describe(out), recipe.id());
        result.setItem(0, out);
    }

    /** 仿 vanilla 消耗材料: 按当前切换索引的配方取余料, 每格 -1, 余料 (桶/碗) 同格回填或还给玩家. */
    private static void consumeCraftMaterials(Player player, SimpleContainer grid) {
        TerminalRegistry.LOGGER.info("[CRAFT-DIAG] {} consume: before={}", side(player), describeGrid(grid));
        List<RecipeHolder<CraftingRecipe>> recipes = matchedRecipes(player, grid);
        NonNullList<ItemStack> remaining = recipes.isEmpty()
                ? NonNullList.create()
                : recipes.get(recipeIndex(player, recipes)).value().getRemainingItems(craftInput(grid));
        for (int i = 0; i < grid.getContainerSize(); i++) {
            ItemStack inGrid = grid.getItem(i);
            if (inGrid.isEmpty()) {
                continue;
            }
            inGrid.shrink(1);
            ItemStack left = i < remaining.size() ? remaining.get(i) : ItemStack.EMPTY;
            if (left.isEmpty()) {
                continue;
            }
            if (inGrid.isEmpty()) {
                grid.setItem(i, left.copy());
            } else if (ItemStack.isSameItemSameComponents(inGrid, left)) {
                inGrid.grow(left.getCount());
            } else if (!player.getInventory().add(left.copy())) {
                player.drop(left.copy(), false);
            }
        }
        TerminalRegistry.LOGGER.info("[CRAFT-DIAG] {} consume: after={}", side(player), describeGrid(grid));
    }

    // ==================== 存储持久化 (PlayerPersistentData, 跟随设备槽 MtDeviceSlots 风格) ====================

    // ==================== 拾取分流 (背包满 + 随身终端 → 存入存储格) ====================

    /**
     * 拾取分流: 把物品尽量塞进终端存储格.
     * 开着存储 GUI 时直接写内存会话 (实时持久化 + 界面同步);
     * 未开 GUI 时从 PP 读出临时容器操作, 有收下再写回 PP.
     *
     * @return 实际收下的数量 (0 = 没有终端会话可写或存储格已满)
     */
    public static int stashPickupIntoStorage(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() == TerminalRegistry.TERMINAL.get()) {
            return 0; // 建筑终端本体不允许被拾取分流进存储格
        }
        StorageSession session = STORAGE_SESSIONS.get(player.getUUID());
        SimpleContainer storage;
        if (session != null) {
            storage = session.storage();
        } else {
            storage = new SimpleContainer(STORAGE_SLOT_COUNT);
            loadStorage(player, storage);
        }
        int left = stack.getCount();
        for (int i = 0; i < storage.getContainerSize() && left > 0; i++) {
            ItemStack slot = storage.getItem(i);
            if (slot.isEmpty()) {
                int move = Math.min(left, stack.getMaxStackSize());
                storage.setItem(i, stack.copyWithCount(move));
                left -= move;
            } else if (ItemStack.isSameItemSameComponents(slot, stack) && slot.getCount() < slot.getMaxStackSize()) {
                int move = Math.min(left, slot.getMaxStackSize() - slot.getCount());
                slot.grow(move);
                left -= move;
            }
        }
        int moved = stack.getCount() - left;
        if (moved > 0 && session == null) {
            persistStorage(player, storage);
        }
        return moved;
    }

    /** 存储格写入 PP (实时 listener + 关 GUI 兜底双保险); 空槽存空 CompoundTag 保持槽位对齐. */
    private static void persistStorage(Player player, SimpleContainer storage) {
        ListTag items = new ListTag();
        for (int i = 0; i < storage.getContainerSize(); i++) {
            ItemStack stack = storage.getItem(i);
            items.add(stack.isEmpty() ? new CompoundTag() : stack.save(player.registryAccess()));
        }
        CompoundTag root = new CompoundTag();
        root.put("Items", items);
        TerminalDeviceHandler.writePersisted(player, "MtStorageSlots", root);
    }

    /** 打开终端时从 PP 读回存储格 (parseOptional 对空 CompoundTag 返回 EMPTY); 兼容清理历史存入的终端. */
    private static void loadStorage(Player player, SimpleContainer storage) {
        CompoundTag root = TerminalDeviceHandler.readPersisted(player, "MtStorageSlots");
        ListTag items = root.getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < storage.getContainerSize() && i < items.size(); i++) {
            ItemStack stack = ItemStack.parseOptional(player.registryAccess(), items.getCompound(i));
            if (stack.getItem() == TerminalRegistry.TERMINAL.get()) {
                stack = ItemStack.EMPTY; // 旧版本可能已存入终端, 读回时清理
            }
            storage.setItem(i, stack);
        }
    }
}
