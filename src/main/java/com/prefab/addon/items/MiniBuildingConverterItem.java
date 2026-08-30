package com.prefab.addon.items;

import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

import java.util.List;

/**
 * 迷你建筑转换器.
 *
 * <p>使用方法 (跟"创建建筑"游戏内选区一致):
 * <ol>
 *   <li>左键方块 1 → 选角点 1 (创造模式左键不会破坏方块)</li>
 *   <li>右键方块 2 / 空气 → 选角点 2 (支持空气方块)</li>
 *   <li>按 ALT 确认 → 服务端扫描区域, 玩家获得迷你建筑方块</li>
 *   <li>CTRL 取消, SHIFT 临时解锁挖方块/用物品</li>
 * </ol>
 *
 * <p>特性:
 * <ul>
 *   <li>生存模式耐久 2, 创造模式无限</li>
 *   <li>选中区域有黄色指示框 (pos1=绿, pos2=红, 整体=黄)</li>
 *   <li>选区超限自动取消 (单边 &le; 64)</li>
 * </ul>
 *
 * <p>所有交互都通过 {@link com.prefab.addon.work.RegionSelector} (模式
 * {@code MINI_BUILDING_CAPTURE}) 处理, useOn 只是兜底 (实际不会触发).</p>
 */
public class MiniBuildingConverterItem extends Item {

    public static final int MAX_SIZE = 128;

    public MiniBuildingConverterItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        // 选区模式: 全权交给 RegionSelectorEventHandler 处理左/右键选点
        // (mixin 已拦左键破坏方块 + onMouseButtonPre 拦右键放物品)
        // useOn 不会在选区模式时被调用, 这里只是兜底
        if (com.prefab.addon.work.RegionSelector.isActive(context.getPlayer())) {
            return InteractionResult.SUCCESS;
        }
        // 选区模式没启动: 通常是玩家刚拿到转换器但 onClientTick 还没跑
        // (仅 1 tick 延迟, 等下次 tick 即可). 走兜底: 启动选区模式, 不做事
        if (context.getLevel().isClientSide && context.getPlayer() != null) {
            com.prefab.addon.work.RegionSelector.startMiniBuilding(context.getPlayer());
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(
            "item.prefab_custom_addon.mini_building_converter.tooltip.1"
        ).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable(
            "item.prefab_custom_addon.mini_building_converter.tooltip.2"
        ).withStyle(ChatFormatting.GRAY));
    }
}

