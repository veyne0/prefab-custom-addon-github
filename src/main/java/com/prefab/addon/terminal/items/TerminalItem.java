package com.prefab.addon.terminal.items;

import com.lowdragmc.lowdraglib2.gui.factory.HeldItemUIMenuType;
import com.prefab.addon.terminal.TerminalRegistry;
import com.prefab.addon.terminal.ui.TerminalUI;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 终端物品 (common) — Menu UI 入口. 自 1.21.1 NeoForge 版移植.
 *
 * 实现 LDLib2 {@link HeldItemUIMenuType.HeldItemUI}: 右键时服务端
 * {@link HeldItemUIMenuType#openUI} 打开 {@code ModularUIContainerMenu},
 * 客户端经 LDLib2 注册好的 screen factory 自动弹出
 * {@code ModularUIContainerScreen} — UI 树由 {@link TerminalUI#create} 双侧各建一份.
 *
 * **绝对不要在本类里 import 任何 net.minecraft.client.* 或本 mod client 包类**:
 * 注册阶段会加载本类, dedicated server 上触碰客户端类直接崩.
 */
public class TerminalItem extends Item implements HeldItemUIMenuType.HeldItemUI {

    public TerminalItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            HeldItemUIMenuType.openUI(serverPlayer, hand);
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        // 右键方块同样打开终端 (客户端 SUCCESS 消费事件阻止方块交互, 服务端真正开菜单)
        Player player = context.getPlayer();
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            return HeldItemUIMenuType.openUI(serverPlayer, context.getHand())
                    ? InteractionResult.SUCCESS
                    : InteractionResult.PASS;
        }
        return context.getLevel().isClientSide ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    /**
     * 覆盖默认 stillValid (默认用 ItemStack.matches 整包对比): 探矿耗电/槽位变化
     * 都会把设备槽 NBT 写回手上终端物品, NBT 一变 matches 就 false,
     * 菜单会被误判失效直接关闭. 这里只要求手上还是同一个终端物品.
     */
    @Override
    public boolean stillValid(HeldItemUIMenuType.HeldItemUIHolder holder) {
        ItemStack current = holder.player.getItemInHand(holder.hand);
        return !current.isEmpty() && current.getItem() == holder.itemStack.getItem();
    }

    @Override
    public com.lowdragmc.lowdraglib2.gui.ui.ModularUI createUI(HeldItemUIMenuType.HeldItemUIHolder holder) {
        return TerminalUI.create(holder);
    }

    /**
     * 物品注册在 modern_terminal namespace 下 (UI 资源统一放这里), 但它不是 mod id,
     * tooltip 末尾会显示原始 namespace 字符串. 重写为返回主模组 id,
     * 让 tooltip 显示主模组名 "预制建筑附属".
     */
    @Override
    public String getCreatorModId(ItemStack itemStack) {
        return "prefab_custom_addon";
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item." + TerminalRegistry.MOD_ID + ".terminal.desc")
                .withStyle(ChatFormatting.GRAY));
        // 设备槽电池电量: 从终端物品 NBT "DeviceSlots" 里还原电池槽 stack,
        // 电量随 NBT 同步到客户端, tooltip 直接读即可 (没有电池就不显示这一行).
        ItemStack battery = com.prefab.addon.terminal.device.TerminalDeviceHandler
                .readSlotFrom(stack, com.prefab.addon.terminal.device.TerminalDeviceHandler.SLOT_BATTERY);
        long max = com.prefab.addon.terminal.device.BatteryHelper.maxCharge(battery);
        if (max > 0) {
            long cur = com.prefab.addon.terminal.device.BatteryHelper.getCharge(battery);
            tooltip.add(Component.translatable("gui.modern_terminal.terminal.battery_line",
                            cur, max, (int) (100L * cur / max))
                    .withStyle(ChatFormatting.GRAY));
        }
        super.appendHoverText(stack, level, tooltip, flag);
    }
}
