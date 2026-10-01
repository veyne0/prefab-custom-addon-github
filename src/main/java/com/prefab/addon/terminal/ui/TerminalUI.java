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
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.jetbrains.annotations.Nullable;

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
            new AppDef("create", () -> new ItemStack(Blocks.CRAFTING_TABLE)),
            new AppDef("recycle", () -> new ItemStack(Items.HOPPER)),
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
    public record ClientContext(TabView tabView, Tab homeTab, Tab recycleTab,
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

        UIElement[] homeGridOut = new UIElement[1];
        Tab homeTab = new Tab().setText(Component.translatable("gui.modern_terminal.tab.home"));
        tabView.addTab(homeTab, createHomeContent(homeGridOut));

        // 回收站 tab 常驻 (服务端绑定槽位在此, 与设备 handler 同模式: 双侧同序注册 menu 槽)
        Tab recycleTab = new Tab().setText(Component.translatable("gui.modern_terminal.app.recycle"));
        tabView.addTab(recycleTab, createRecycleContent(recycleBin, player, clientSide));

        root.addChild(tabView);

        ModularUI ui = ModularUI.of(
                UI.of(root, new Stylesheet[]{StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)}),
                player);

        if (clientSide) {
            Consumer<ClientContext> setup = clientSetup;
            if (setup != null) {
                setup.accept(new ClientContext(tabView, homeTab, recycleTab, homeGridOut[0], holder.itemStack));
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
            ItemSlot slot = new ItemSlot().bind(new Slot(bin, i, 0, 0));
            slot.layout(l -> l.width(18.0f).height(18.0f));
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
}
