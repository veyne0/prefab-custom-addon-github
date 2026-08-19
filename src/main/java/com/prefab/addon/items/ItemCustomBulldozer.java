package com.prefab.addon.items;

import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;

import java.util.List;

/**
 * 自定义推土机物品.
 *
 * <p>与原版 prefab 推土机几乎一致, 但清除区域大小由 NBT 控制
 * ({@code length/width/height}), 默认 32x32x32 (原版 16x16x15 太小).</p>
 *
 * <p>无 GUI 类引用 (无 net.minecraft.client.*), 服务端可安全加载.</p>
 */
public class ItemCustomBulldozer extends Item {

    public static final int DEFAULT_LENGTH = 32;
    public static final int DEFAULT_WIDTH  = 32;
    public static final int DEFAULT_HEIGHT = 32;
    public static final int MAX_DIM = 256;
    public static final int MIN_DIM = 1;

    public ItemCustomBulldozer(Properties properties) {
        super(properties);
    }

    // === NBT 读写工具 ===

    public static CompoundTag getOrCreateTag(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = (data != null) ? data.copyTag() : new CompoundTag();
        return tag;
    }

    public static int getLength(ItemStack stack) {
        CompoundTag tag = getOrCreateTag(stack);
        return tag.contains("length") ? tag.getInt("length") : DEFAULT_LENGTH;
    }

    public static int getWidth(ItemStack stack) {
        CompoundTag tag = getOrCreateTag(stack);
        return tag.contains("width") ? tag.getInt("width") : DEFAULT_WIDTH;
    }

    public static int getHeight(ItemStack stack) {
        CompoundTag tag = getOrCreateTag(stack);
        return tag.contains("height") ? tag.getInt("height") : DEFAULT_HEIGHT;
    }

    public static void setDimensions(ItemStack stack, int length, int width, int height) {
        CompoundTag tag = getOrCreateTag(stack);
        tag.putInt("length", clamp(length));
        tag.putInt("width",  clamp(width));
        tag.putInt("height", clamp(height));
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    public static int clamp(int v) {
        return Math.max(MIN_DIM, Math.min(MAX_DIM, v));
    }

    // === noDrops: 破坏时不生成掉落物 (用于大区域清除, 避免卡顿) ===
    // 默认 false, 让玩家还能拿到掉落物; 玩家在 LdLib 设置 GUI 里可勾选.

    public static boolean getNoDrops(ItemStack stack) {
        CompoundTag tag = getOrCreateTag(stack);
        return tag.contains("noDrops") && tag.getBoolean("noDrops");
    }

    public static void setNoDrops(ItemStack stack, boolean noDrops) {
        CompoundTag tag = getOrCreateTag(stack);
        tag.putBoolean("noDrops", noDrops);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    // === Tooltip ===

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.prefab_custom_addon.custom_bulldozer.desc")
            .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal(String.format(
            "§7区域: §f%dx%dx%d §7(长x宽x高)",
            getLength(stack), getWidth(stack), getHeight(stack))
        ).withStyle(ChatFormatting.GRAY));
        // 自定义推土机固定不生成掉落物, 不论模式
        tooltip.add(Component.literal("§7掉落物: §c✗ 固定禁用 (任何模式都不生成)")
            .withStyle(ChatFormatting.GRAY));
        if (stack.getDamageValue() > 0) {
            int maxDmg = stack.getMaxDamage();
            tooltip.add(Component.literal("§7耐久: §e" + (maxDmg - stack.getDamageValue()) + "/" + maxDmg)
                .withStyle(ChatFormatting.GRAY));
        }
        super.appendHoverText(stack, context, tooltip, flag);
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        // 让耐久条可见 (跟原版推土机一致)
        return stack.getDamageValue() > 0;
    }
}
