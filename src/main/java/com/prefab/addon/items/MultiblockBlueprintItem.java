package com.prefab.addon.items;

import java.util.List;
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

/**
 * 多方块结构蓝图物品 (common 部分).
 *
 * **绝对不要在本类里 import 任何 net.minecraft.client.* 或 client.gui.* 类**.
 * RegisterEvent 阶段会强制加载本类, 一旦静态结构触碰到客户端类, 在 dedicated server
 * 加载时会被 RuntimeDistCleaner 直接抛 "Attempted to load class ... for invalid dist
 * DEDICATED_SERVER", 整个 mod 加载回滚, 服务端崩溃.
 *
 * 打开多方块浏览器 GUI 的逻辑在
 * {@code com.prefab.addon.client.MultiblockBlueprintClientHandler} 里通过
 * {@code PlayerInteractEvent.RightClickItem/RightClickBlock} 拦截, 该类标注了
 * {@code @Mod.EventBusSubscriber(Dist.CLIENT)}, 永远不会在服务端加载.
 * (与 {@link CustomBlueprintItem} + CustomBlueprintClientHandler 同一套模式.)
 */
public class MultiblockBlueprintItem extends Item {
    public MultiblockBlueprintItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        // 客户端实际打开 GUI 的逻辑在 MultiblockBlueprintClientHandler.onRightClickItem 里
        // 这里服务端不需要任何行为, 直接返回 success 消费掉右键事件
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        // 客户端实际打开 GUI 的逻辑在 MultiblockBlueprintClientHandler.onRightClickBlock 里
        return InteractionResult.PASS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.prefab_custom_addon.multiblock_blueprint.desc")
            .withStyle(net.minecraft.ChatFormatting.GRAY));
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
