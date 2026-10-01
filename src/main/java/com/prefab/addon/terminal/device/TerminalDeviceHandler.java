package com.prefab.addon.terminal.device;

import com.prefab.addon.terminal.TerminalRegistry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 终端设备槽 5 格 — Menu UI 的服务端真源 (ItemStackHandler).
 *
 * 槽位布局: [0] 电池 [1] 探矿仪 [2..4] 通用扩展.
 *
 * 持久化: 每次槽位变化 (onContentsChanged, 服务端 menu 点击触发) 都把整个
 * handler 序列化回终端物品 CUSTOM_DATA "DeviceSlots" — 物品即存档, 无需任何同步包.
 * 客户端 handler 是 menu slot 同步的镜像, 客户端的 onContentsChanged 只写本地副本.
 *
 * 电池初始化: 槽位变化时对电池槽调 BatteryHelper.initChargeIfNeeded,
 * 让模拟电池 (红石等) 首次放入时获得 MtCharge 组件 — 电量从此跟物品走.
 */
public class TerminalDeviceHandler extends ItemStackHandler {

    public static final int SLOT_COUNT = 5;
    public static final int SLOT_BATTERY = 0;
    public static final int SLOT_PROSPECTOR = 1;

    private final ItemStack terminal;
    private final boolean clientSide;
    private final net.minecraft.core.HolderLookup.Provider provider;
    /** 服务端记录打开时的玩家/手: persist 时同时写"当前手上的 stack", 防止 holder 持有的
     * stack 引用在 GUI 期间被外部机制替换导致数据写到孤儿对象上 (存档丢失的防御). */
    private final @Nullable net.minecraft.world.entity.player.Player player;
    private final @Nullable net.minecraft.world.InteractionHand hand;
    /** menu 关闭门: vanilla AbstractContainerMenu.removed() 会在 Close 事件之前清空 slot,
     * 触发 onContentsChanged → persistToTerminal(false), 用空数据覆盖了"菜单关闭瞬间"还
     * 在的设备数据. 设为 true 后 onContentsChanged/persist 全部静默退出. */
    private volatile boolean closed = false;

    /** 服务端: playerId → 打开中的 handler (服务端读写设备槽时定位 menu 正在用的实例). */
    private static final Map<UUID, TerminalDeviceHandler> SERVER_INSTANCES = new ConcurrentHashMap<>();
    /** 客户端: 当前打开 GUI 的 handler 镜像 (电量显示等客户端应用读它). */
    private static volatile @Nullable TerminalDeviceHandler clientInstance = null;

    public TerminalDeviceHandler(ItemStack terminal, boolean clientSide,
                                 net.minecraft.core.HolderLookup.Provider provider) {
        this(terminal, clientSide, provider, null, null);
    }

    public TerminalDeviceHandler(ItemStack terminal, boolean clientSide,
                                 net.minecraft.core.HolderLookup.Provider provider,
                                 @Nullable net.minecraft.world.entity.player.Player player,
                                 @Nullable net.minecraft.world.InteractionHand hand) {
        super(SLOT_COUNT);
        this.terminal = terminal;
        this.clientSide = clientSide;
        this.provider = provider;
        this.player = player;
        this.hand = hand;
        // 恢复槽位: 优先从 Player.getPersistentData() (NeoForge PERSISTED_NBT_TAG_KEY,
        // 走玩家 .dat 文件独立字段, 完全不受 ItemStack 同步/死亡重生影响),
        // fallback 到 ItemStack CUSTOM_DATA.
        // 存档验尸: 多次 SavePrefs 写入后到退服之间整个 CUSTOM_DATA 组件被外力剥离
        // (keys=null, 不是 Items=[]), 改用 PlayerPersistentData 绕开 ItemStack 层.
        if (!terminal.isEmpty() || player != null) {
            CompoundTag saved = null;
            String source = "none";
            boolean fromPlayerData = false;
            if (player != null) {
                CompoundTag pd = player.getPersistentData();
                if (pd.contains("MtDeviceSlots", Tag.TAG_COMPOUND)) {
                    saved = pd.getCompound("MtDeviceSlots");
                    source = "PlayerPersistentData";
                    fromPlayerData = true;
                }
            }
            if (saved == null && !terminal.isEmpty()) {
                CustomData cd = terminal.get(DataComponents.CUSTOM_DATA);
                if (cd != null && cd.copyTag().contains("DeviceSlots", Tag.TAG_COMPOUND)) {
                    saved = cd.copyTag().getCompound("DeviceSlots");
                    source = "ItemStack.CUSTOM_DATA";
                }
            }
            if (saved != null) {
                TerminalRegistry.LOGGER.info("[DEVICE] 构造: 恢复设备槽 terminal@{} (clientSide={}) source={} items={}",
                        System.identityHashCode(terminal), clientSide, source,
                        saved.getList("Items", Tag.TAG_COMPOUND));
                if (!saved.isEmpty()) {
                    this.deserializeNBT(provider, saved);
                }
                // 自愈: 真源在 PlayerPersistentData 时无条件把恢复出的槽位回写终端组件
                // (persistToTerminal 双写 CUSTOM_DATA + PP; 此刻 GUI 已打开, 手持不渲染,
                // 组件同步不会触发拿起动画). 覆盖两种情况:
                // ① CUSTOM_DATA 的 DeviceSlots 被外力剥离 (历史验尸: keys=null) — 否则
                //    离线读链路 (物品 tooltip 电量 / 相机 enter 读电量) 读到空, 表现为
                //    "重进游戏后要先把电池拿出来再放回去才能识别";
                // ② 相机模式扣电只写 PP (避免每秒组件同步导致终端反复拿起), 打开 GUI
                //    时把 PP 领先的电量追平进组件.
                if (!clientSide && fromPlayerData && !saved.isEmpty()) {
                    TerminalRegistry.LOGGER.info(
                            "[DEVICE] 自愈: 真源在 PP, 回写终端组件追平 terminal@{}",
                            System.identityHashCode(terminal));
                    persistToTerminal();
                }
            } else {
                CustomData cd = terminal.isEmpty() ? null : terminal.get(DataComponents.CUSTOM_DATA);
                TerminalRegistry.LOGGER.info("[DEVICE] 构造: 终端无 DeviceSlots 数据 terminal@{} (clientSide={}) keys={}",
                        System.identityHashCode(terminal), clientSide,
                        cd == null ? "null" : cd.copyTag().getAllKeys());
            }
        } else {
            TerminalRegistry.LOGGER.warn("[DEVICE] 构造: terminal stack 为空! clientSide={}", clientSide);
        }
    }

    @Override
    protected void onContentsChanged(int slot) {
        super.onContentsChanged(slot);
        // closed 门: removed() 清空 slot 触发的 onContentsChanged 全部静默 (avoids空覆盖)
        if (closed) {
            TerminalRegistry.LOGGER.info("[DEVICE] onContentsChanged IGNORED (closed=true) slot={} stack={}",
                    slot, getStackInSlot(slot));
            return;
        }
        // 电池槽首次放入: 初始化模拟电池 charge 组件 (电量跟物品走)
        if (slot == SLOT_BATTERY) {
            BatteryHelper.initChargeIfNeeded(getStackInSlot(SLOT_BATTERY));
        }
        TerminalRegistry.LOGGER.info("[DEVICE] onContentsChanged slot={} clientSide={} stack={}",
                slot, clientSide, getStackInSlot(slot));
        if (!clientSide && !terminal.isEmpty()) {
            persistToTerminal();
        }
    }

    /** 槽位白名单: 电池槽只收电池, 探矿仪槽只收探矿仪 (menu 槽 mayPlace 走这里). */
    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        boolean ok = switch (slot) {
            case SLOT_BATTERY -> BatteryHelper.isBattery(stack);
            case SLOT_PROSPECTOR -> BatteryHelper.isProspector(stack);
            default -> true;
        };
        if (!ok) {
            TerminalRegistry.LOGGER.info("[DEVICE] 拒绝放入 slot={} item={} (isBattery={} isProspector={})",
                    slot, BuiltInRegistries.ITEM.getKey(stack.getItem()), BatteryHelper.isBattery(stack),
                    BatteryHelper.isProspector(stack));
        }
        return ok;
    }

    /** 序列化整个 handler 回终端物品 CUSTOM_DATA (服务端真源持久化).
     *
     * 服务端永远写 player.getItemInHand(hand) 的实时引用, 不信任 createUI 时缓存的
     * holder.itemStack — GUI 期间背包 stack 引用可能被外部机制替换, 写旧引用等于写
     * 孤儿对象 (存档验证: OpenTabs 关 GUI 现拿对象能进存档, holder 缓存引用进不了). */
    public void persistToTerminal() {
        persistToTerminal(false);
    }

    /** 关闭时清空保护: 存档验尸实锤 menu 关闭流程会在 persist 前清空 handler 槽位,
     * 若用空数据覆盖目标 stack 上非空的 DeviceSlots = 吞设备. closing=true 时遇到
     * "槽位全空但目标还有设备数据" 直接跳过写入, 保留背包上的最后好状态. */
    public void persistToTerminal(boolean closing) {
        if (terminal.isEmpty()) {
            return;
        }
        if (closed) {
            TerminalRegistry.LOGGER.info("[DEVICE] persist IGNORED (closed=true) target@{}",
                    System.identityHashCode(terminal));
            return;
        }
        ItemStack target = terminal;
        if (!clientSide && player != null && hand != null) {
            ItemStack inHand = player.getItemInHand(hand);
            if (!inHand.isEmpty() && inHand.getItem() == terminal.getItem()) {
                target = inHand;
            }
        }
        if (target != terminal) {
            TerminalRegistry.LOGGER.info("[DEVICE] 引用已替换: holder@{} != 实时@{}, 改写实时 stack",
                    System.identityHashCode(terminal), System.identityHashCode(target));
        }
        CompoundTag fresh = serializeNBT(provider);
        int freshSize = fresh.getList("Items", Tag.TAG_COMPOUND).size();
        CustomData prev = target.get(DataComponents.CUSTOM_DATA);
        CompoundTag prevDevice = prev == null ? null : prev.copyTag().getCompound("DeviceSlots");
        int prevSize = prevDevice == null ? 0
                : prevDevice.getList("Items", Tag.TAG_COMPOUND).size();
        // 空覆盖保护 (无条件): 若 handler 全空但目标 stack 上还有设备数据, 跳过写入.
        // 兜底对抗 vanilla removed() 在 Close 事件前清空 slot 的场景.
        if (freshSize == 0 && prevSize > 0) {
            TerminalRegistry.LOGGER.warn(
                    "[DEVICE] 空覆盖保护: handler空但目标@{}还有 {} 件设备, 跳过 (closing={} removed前清空?)",
                    System.identityHashCode(target), prevSize, closing);
            return;
        }
        CompoundTag merged = target.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        merged.put("DeviceSlots", fresh);
        target.set(DataComponents.CUSTOM_DATA, CustomData.of(merged));
        // 真源: 同步写到 Player.getPersistentData(), 走玩家 .dat 独立字段.
        // ItemStack CUSTOM_DATA 可能被 NeoForge 同步层剥离 (验尸 keys=null),
        // 玩家 PersistentData 走 PERSISTED_NBT_TAG_KEY 不受 ItemStack 同步影响.
        if (player != null) {
            player.getPersistentData().put("MtDeviceSlots", fresh.copy());
        }
        // 诊断: 写后立即读回 + 打印 Items.size (键存在但 Items=[] 的情况之前漏判)
        CustomData verify = target.get(DataComponents.CUSTOM_DATA);
        CompoundTag vt = verify == null ? null : verify.copyTag();
        int verifySize = vt == null ? 0
                : vt.getCompound("DeviceSlots").getList("Items", Tag.TAG_COMPOUND).size();
        TerminalRegistry.LOGGER.info(
                "[DEVICE] persist -> target@{} 写后读回: DeviceSlots.数量={}->{} keys={}",
                System.identityHashCode(target), freshSize, verifySize,
                vt == null ? "null" : vt.getAllKeys());
    }

    /** 关 menu 入口: 必须在 Close 事件第一行调用, 屏蔽 vanilla removed() 清空触发的
     * onContentsChanged/persist 链, 防止"用空数据覆盖设备"再次发生. */
    public void markClosed() {
        this.closed = true;
    }

    // ==== 双侧实例登记 ====

    public static void registerServer(UUID playerId, TerminalDeviceHandler handler) {
        SERVER_INSTANCES.put(playerId, handler);
    }

    public static void unregisterServer(UUID playerId) {
        SERVER_INSTANCES.remove(playerId);
    }

    public static @Nullable TerminalDeviceHandler server(UUID playerId) {
        return SERVER_INSTANCES.get(playerId);
    }

    public static @Nullable TerminalDeviceHandler client() {
        return clientInstance;
    }

    public static void registerClient(@Nullable TerminalDeviceHandler handler) {
        clientInstance = handler;
    }

    /** 电池槽 stack (null = 未打开). */
    public static @Nullable ItemStack clientBattery() {
        TerminalDeviceHandler h = clientInstance;
        return h == null ? null : h.getStackInSlot(SLOT_BATTERY);
    }

    /** 探矿仪槽 stack (null = 未打开). */
    public static @Nullable ItemStack clientProspector() {
        TerminalDeviceHandler h = clientInstance;
        return h == null ? null : h.getStackInSlot(SLOT_PROSPECTOR);
    }

    /** 静态: 从离线的终端物品 CUSTOM_DATA "DeviceSlots" 里读某一格.
     *  相机模式在 GUI 关闭后运行, client()/server() 实例都已注销,
     *  只能走手上终端物品的持久化数据 (组件变化会自动同步客户端). */
    public static ItemStack readSlotFrom(ItemStack terminal, int slot,
                                         net.minecraft.core.HolderLookup.Provider provider) {
        if (terminal == null || terminal.isEmpty()) {
            return ItemStack.EMPTY;
        }
        CustomData cd = terminal.get(DataComponents.CUSTOM_DATA);
        if (cd == null) {
            return ItemStack.EMPTY;
        }
        var items = cd.copyTag().getCompound("DeviceSlots").getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            CompoundTag entry = items.getCompound(i);
            if (entry.getInt("Slot") == slot) {
                return ItemStack.parseOptional(provider, entry);
            }
        }
        return ItemStack.EMPTY;
    }
}
