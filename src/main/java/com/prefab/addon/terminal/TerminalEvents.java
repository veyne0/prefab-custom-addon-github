package com.prefab.addon.terminal;

import com.lowdragmc.lowdraglib2.gui.factory.HeldItemUIMenuType;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIContainerMenu;
import com.prefab.addon.terminal.device.TerminalDeviceHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 终端 menu 生命周期事件 (common, 游戏总线).
 *
 * createUI 在服务端会把设备槽 handler 登记到 SERVER_INSTANCES (供耗电 C2S 定位),
 * menu 关闭时在这里注销, 防止 UUID → handler 泄漏.
 * 客户端镜像的注销在 TerminalGui 的 ScreenEvent.Closing 里 (顺带保存 tab 偏好).
 */
@EventBusSubscriber(modid = TerminalRegistry.HOST_MOD_ID)
public final class TerminalEvents {

    private TerminalEvents() {
    }

    /** 死亡重生: NeoForge 重生是新实体, getPersistentData() 顶层键会丢.
     *  PlayerEvent.Clone(死亡) 时旧实体数据还完整, 把终端存储格/设备槽手动拷到新实体. */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (event.isWasDeath() && !event.getOriginal().level().isClientSide) {
            TerminalDeviceHandler.copyPersistedOnClone(event.getOriginal(), event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (!(event.getContainer() instanceof ModularUIContainerMenu menu)) {
            return;
        }
        if (!(menu.uiHolder instanceof HeldItemUIMenuType.HeldItemUIHolder holder)) {
            return;
        }
        if (holder.itemStack.getItem() != TerminalRegistry.TERMINAL.get()) {
            return;
        }
        // 必须第一行: 关闭 menu 的"门", 屏蔽 vanilla removed() 清空 slot 触发的 onContentsChanged
        // (该链会用空 handler 覆盖目标 stack 上还有的设备数据 — 存档验尸实锤).
        TerminalDeviceHandler handler = TerminalDeviceHandler.server(serverPlayer.getUUID());
        if (handler != null) {
            handler.markClosed();
            // 关 GUI 时最后一次持久化 (closing=true): 此刻 closed 门已开, 写一次"关闭前快照"
            // 到目标 stack, 保险覆盖 GUI 期间任何对象替换.
            handler.persistToTerminal(true);
        }
        // 诊断: menu 关闭时确认手上 stack (identity) 上 DeviceSlots 是否仍在
        var cd = holder.itemStack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        TerminalRegistry.LOGGER.info("[DEVICE] menu关闭: 手上终端@{} DeviceSlots存在={} keys={}",
                System.identityHashCode(holder.itemStack),
                cd != null && cd.copyTag().contains("DeviceSlots", net.minecraft.nbt.Tag.TAG_COMPOUND),
                cd == null ? "null" : cd.copyTag().getAllKeys());
        // 诊断: 关闭瞬间 dump 存储格/合成格/回收站非空内容 (排查 shift+点击物品去向, 定位后删)
        com.prefab.addon.terminal.ui.TerminalUI.diagDumpContainers(serverPlayer, "close");
        // 回收站: 关 GUI 时把还留在槽位里的物品还给玩家 (放不下掉脚边), 再注销容器防泄漏
        net.minecraft.world.SimpleContainer bin =
                com.prefab.addon.terminal.ui.TerminalUI.removeRecycleBin(serverPlayer.getUUID());
        if (bin != null) {
            for (int i = 0; i < bin.getContainerSize(); i++) {
                ItemStack left = bin.getItem(i);
                if (!left.isEmpty()) {
                    bin.setItem(i, ItemStack.EMPTY);
                    if (!serverPlayer.getInventory().add(left)) {
                        serverPlayer.drop(left, false);
                    }
                }
            }
        }
        // 存储软件: 关 GUI 兜底持久化存储格 + 合成格剩余还玩家 + 注销会话
        com.prefab.addon.terminal.ui.TerminalUI.closeStorageSession(serverPlayer);
        TerminalDeviceHandler.unregisterServer(serverPlayer.getUUID());
    }

    /**
     * 拾取分流 (Pre): 背包里带着建筑终端且背包 1 个都装不下时, 拾取物直接尝试进终端存储格.
     * deny 后原版 playerTouch 直接 return 不再碰实体, 由本方法自行收尾 (动画+统计+移除/写回剩余).
     */
    @SubscribeEvent
    public static void onItemPickupPre(ItemEntityPickupEvent.Pre event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return; // 事件仅逻辑服务端触发, 此处双保险过滤
        }
        ItemEntity entity = event.getItemEntity();
        ItemStack original = entity.getItem();
        if (original.isEmpty() || inventoryCanFit(player, original)) {
            return; // 背包装得下 → 原版拾取, 部分拾取的剩余由 Post 兜底分流
        }
        if (!hasTerminalInInventory(player)) {
            return; // 装不下且没带终端 → 原版行为 (留地上)
        }
        // 背包 1 个都装不下 → 直接尝试终端存储格 (stash 不修改传入 stack)
        int moved = com.prefab.addon.terminal.ui.TerminalUI.stashPickupIntoStorage(player, original);
        if (moved <= 0) {
            return; // 存储也满 → 原版行为 (留地上)
        }
        player.displayClientMessage(Component.translatable(
                "gui.modern_terminal.pickup_stashed", original.getHoverName()), false);
        event.setCanPickup(TriState.FALSE); // 接管: 屏蔽原版无效 add (会整组返回 false 什么都不做)
        player.awardStat(Stats.ITEM_PICKED_UP.get(original.getItem()), moved);
        player.onItemPickup(entity); // 客户端拾取动画 (take 包)
        if (moved < original.getCount()) {
            // 存储没装完全部: 剩余写回实体 (deny 路径下原版不再引用该 stack, setItem 安全且同步客户端)
            entity.setItem(original.copyWithCount(original.getCount() - moved));
            entity.setPickUpDelay(10); // 留地上, 稍后可再触发 (届时存储若有空位可续收)
        } else {
            entity.discard();
        }
    }

    /**
     * 拾取分流 (Post): 原版部分拾取 (背包塞满但装进了至少 1 个) 后, 剩余 stack 是实体的 live 引用.
     * 直接分流进终端存储格并 shrink; 剩余清空时原版 playerTouch 收尾逻辑会自动 discard + 发拾取动画.
     */
    @SubscribeEvent
    public static void onItemPickupPost(ItemEntityPickupEvent.Post event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack remaining = event.getCurrentStack();
        if (remaining.isEmpty() || !hasTerminalInInventory(player)) {
            return;
        }
        int moved = com.prefab.addon.terminal.ui.TerminalUI.stashPickupIntoStorage(player, remaining);
        if (moved > 0) {
            // hoverName 必须在 shrink 前取 (剩余清空后 live stack 失效)
            player.displayClientMessage(Component.translatable(
                    "gui.modern_terminal.pickup_stashed", remaining.getHoverName()), false);
            remaining.shrink(moved); // shrink 后为空 → 原版收尾自动 discard + onItemPickup (数量含存储部分)
        }
    }

    /** 只读模拟: 背包 36 格能否至少装下该物品 1 个 (合堆或空位), 用于 Pre 阶段判定是否需要分流. */
    private static boolean inventoryCanFit(ServerPlayer player, ItemStack stack) {
        net.minecraft.world.entity.player.Inventory inv = player.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack slot = inv.items.get(i);
            if (slot.isEmpty()) {
                return true;
            }
            if (ItemStack.isSameItemSameComponents(slot, stack)
                    && slot.getCount() < slot.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    /** 背包 (含副手) 里是否带着建筑终端. */
    private static boolean hasTerminalInInventory(ServerPlayer player) {
        for (ItemStack s : player.getInventory().items) {
            if (!s.isEmpty() && s.getItem() == TerminalRegistry.TERMINAL.get()) {
                return true;
            }
        }
        for (ItemStack s : player.getInventory().offhand) {
            if (!s.isEmpty() && s.getItem() == TerminalRegistry.TERMINAL.get()) {
                return true;
            }
        }
        return false;
    }

    /** 诊断: 服务器关闭 (存档前最后一刻) dump 每个玩家背包里终端的 CUSTOM_DATA 状态. */
    @SubscribeEvent
    public static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) {
            p.getInventory().items.forEach(stack -> {
                if (stack.getItem() == TerminalRegistry.TERMINAL.get()) {
                    var cd = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
                    int n = cd == null || !cd.copyTag().contains("DeviceSlots", net.minecraft.nbt.Tag.TAG_COMPOUND)
                            ? -1
                            : cd.copyTag().getCompound("DeviceSlots")
                                    .getList("Items", net.minecraft.nbt.Tag.TAG_COMPOUND).size();
                    TerminalRegistry.LOGGER.info("[DEVICE] 关服dump: 玩家={} 终端@{} DeviceSlots槽位数={} keys={}",
                            p.getGameProfile().getName(), System.identityHashCode(stack), n,
                            cd == null ? "null" : cd.copyTag().getAllKeys());
                }
            });
        }
    }
}
