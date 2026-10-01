package com.prefab.addon.terminal;

import com.lowdragmc.lowdraglib2.gui.factory.HeldItemUIMenuType;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIContainerMenu;
import com.prefab.addon.terminal.device.TerminalDeviceHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
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
        TerminalDeviceHandler.unregisterServer(serverPlayer.getUUID());
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
