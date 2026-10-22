package com.prefab.addon.terminal;

import com.lowdragmc.lowdraglib2.gui.factory.HeldItemUIMenuType;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIContainerMenu;
import com.prefab.addon.terminal.device.TerminalDeviceHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 终端 menu 生命周期事件 (common, 游戏总线).
 *
 * createUI 在服务端会把设备槽 handler 登记到 SERVER_INSTANCES (供耗电 C2S 定位),
 * menu 关闭时在这里注销, 防止 UUID → handler 泄漏.
 * 客户端镜像的注销在 TerminalGui 的 ScreenEvent.Closing 里 (顺带保存 tab 偏好).
 *
 * 1.20.1 Forge 拾取差异: 只有 EntityItemPickupEvent (Pre 语义, 可取消), 没有 NeoForge
 * 的 Pre/Post 对 — "部分拾取后剩余分流" 的 Post 路径裁掉, 仅保留 Pre 全量接管.
 */
@Mod.EventBusSubscriber(modid = TerminalRegistry.HOST_MOD_ID)
public final class TerminalEvents {

    private TerminalEvents() {
    }

    /** 死亡重生: 重生后是新实体, getPersistentData() 顶层键会丢.
     *  PlayerEvent.Clone(死亡) 时旧实体数据还完整, 把终端存储格/设备槽手动拷到新实体. */
    @SubscribeEvent
    public static void onPlayerClone(net.minecraftforge.event.entity.player.PlayerEvent.Clone event) {
        if (event.isWasDeath() && !event.getOriginal().level().isClientSide) {
            TerminalDeviceHandler.copyPersistedOnClone(event.getOriginal(), event.getEntity());
        }
    }

    /** 诊断: menu 打开完成时 dump 服务端槽序 (与客户端 Closing 的槽序对比, 定位 index 错位; 定位后删). */
    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer serverPlayer)
                || !(event.getContainer() instanceof ModularUIContainerMenu menu)) {
            return;
        }
        com.prefab.addon.terminal.ui.TerminalUI.diagDumpMenuSlots(
                "SERVER-open " + serverPlayer.getName().getString(), menu);
        // 诊断: 挂 menu 槽变化 listener, 观测背包槽实时变化 (shift 后物品去向; 定位后删)
        com.prefab.addon.terminal.ui.TerminalUI.diagWatchInventoryViaMenu(serverPlayer, menu);
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
        CompoundTag tag = holder.itemStack.getTag();
        TerminalRegistry.LOGGER.info("[DEVICE] menu关闭: 手上终端@{} DeviceSlots存在={} keys={}",
                System.identityHashCode(holder.itemStack),
                tag != null && tag.contains("DeviceSlots", Tag.TAG_COMPOUND),
                tag == null ? "null" : tag.getAllKeys());
        // 诊断: 关闭瞬间 dump 存储格/合成格/回收站非空内容 (排查 shift+点击物品去向, 定位后删)
        com.prefab.addon.terminal.ui.TerminalUI.diagDumpContainers(serverPlayer, "close");
        // 诊断: dump 服务端 menu 全槽序 (与客户端 Closing 对比; 定位后删)
        com.prefab.addon.terminal.ui.TerminalUI.diagDumpMenuSlots(
                "SERVER-close " + serverPlayer.getName().getString(), menu);
        // 诊断: 移除 menu 背包槽变化 listener (防累积; 定位后删)
        com.prefab.addon.terminal.ui.TerminalUI.removeInvDiagListener(serverPlayer, menu);
        // 回收站: 关 GUI 时把还留在槽位里的物品还给玩家 (放不下掉脚边), 再注销容器防泄漏
        net.minecraft.world.SimpleContainer bin =
                com.prefab.addon.terminal.ui.TerminalUI.removeRecycleBin(serverPlayer.getUUID());
        if (bin != null) {
            for (int i = 0; i < bin.getContainerSize(); i++) {
                ItemStack left = bin.getItem(i);
                if (!left.isEmpty()) {
                    bin.setItem(i, ItemStack.EMPTY);
                    boolean added = serverPlayer.getInventory().add(left);
                    // 诊断: 还物逐条去向 (定位后删)
                    TerminalRegistry.LOGGER.info("[AUI-DBG] 回收站还物 #{} {} -> {}",
                            i, left.getItem(), added ? "进背包" : "掉地上");
                    if (!added) {
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
     * 拾取分流 (Pre, 全量接管): 背包里带着建筑终端且背包 1 个都装不下时, 拾取物直接尝试进终端存储格.
     * cancel 后 Forge 补丁的 playerTouch 直接 return 不再碰实体, 由本方法自行收尾 (动画+统计+移除/写回剩余).
     * 1.21.1 的 Post 路径 (部分拾取剩余分流) 在 Forge 1.20.1 无对应事件, 已裁剪.
     */
    @SubscribeEvent
    public static void onItemPickup(EntityItemPickupEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return; // 事件仅逻辑服务端触发, 此处双保险过滤
        }
        ItemEntity entity = event.getItem();
        ItemStack original = entity.getItem();
        if (original.isEmpty() || inventoryCanFit(player, original)) {
            return; // 背包装得下 → 原版拾取
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
        event.setCanceled(true); // 接管: 屏蔽原版无效 add (会整组返回 false 什么都不做)
        player.awardStat(Stats.ITEM_PICKED_UP.get(original.getItem()), moved);
        player.take(entity, moved); // 客户端拾取动画 (ClientboundTakeItemEntityPacket, 1.20.1 方法名是 take)
        if (moved < original.getCount()) {
            // 存储没装完全部: 剩余写回实体 (cancel 路径下原版不再引用该 stack, setItem 安全且同步客户端)
            entity.setItem(original.copyWithCount(original.getCount() - moved));
            entity.setPickUpDelay(10); // 留地上, 稍后可再触发 (届时存储若有空位可续收)
        } else {
            entity.discard();
        }
    }

    /** 只读模拟: 背包 36 格能否至少装下该物品 1 个 (合堆或空位), 用于判定是否需要分流. */
    private static boolean inventoryCanFit(ServerPlayer player, ItemStack stack) {
        net.minecraft.world.entity.player.Inventory inv = player.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack slot = inv.items.get(i);
            if (slot.isEmpty()) {
                return true;
            }
            if (ItemStack.isSameItemSameTags(slot, stack)
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

    /** 诊断: 服务器关闭 (存档前最后一刻) dump 每个玩家背包里终端的 NBT 状态. */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) {
            p.getInventory().items.forEach(stack -> {
                if (stack.getItem() == TerminalRegistry.TERMINAL.get()) {
                    CompoundTag tag = stack.getTag();
                    int n = tag == null || !tag.contains("DeviceSlots", Tag.TAG_COMPOUND)
                            ? -1
                            : tag.getCompound("DeviceSlots")
                                    .getList("Items", Tag.TAG_COMPOUND).size();
                    TerminalRegistry.LOGGER.info("[DEVICE] 关服dump: 玩家={} 终端@{} DeviceSlots槽位数={} keys={}",
                            p.getGameProfile().getName(), System.identityHashCode(stack), n,
                            tag == null ? "null" : tag.getAllKeys());
                }
            });
        }
    }
}
