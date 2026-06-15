package com.prefab.addon.items;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.client.gui.GuiCustomStructureSelection;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import com.prefab.addon.extension.ExtensionPackManager;
import com.prefab.addon.network.BuildCustomStructurePayload;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

public class CustomBlueprintItem extends Item {
    public CustomBlueprintItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            if (hasConstructionBound(stack)) {
                // 已绑定时右键空气：也打开 Prefab 原生 GUI，使用玩家头上 1 格位置作为 pos
                String packName = getBoundPackName(stack);
                String constructionId = getBoundConstructionId(stack);
                com.prefab.addon.extension.ConstructionInfo info =
                        ExtensionPackManager.getInstance().findConstruction(packName, constructionId);
                if (info != null) {
                    // 玩家脚上方 1 格
                    BlockPos footPos = player.blockPosition().above();
                    Minecraft.getInstance().setScreen(new com.prefab.addon.client.gui.CustomStructureGui(info, stack, footPos));
                    return InteractionResultHolder.success(stack);
                }
            } else {
                GuiCustomStructureSelection.open();
            }
        }
        return InteractionResultHolder.success(stack);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        ItemStack stack = context.getItemInHand();
        if (level.isClientSide) {
            PrefabCustomAddon.LOGGER.info("[USE-DEBUG] CustomBlueprintItem.useOn called, hasBound={}, item={}, itemInHand={}",
                hasConstructionBound(stack),
                stack.getItem().getClass().getSimpleName(),
                context.getItemInHand().getItem().getClass().getSimpleName());

            if (hasConstructionBound(stack)) {
                // 打开 Prefab 原生 GuiStructure（自定义建筑版）
                String packName = getBoundPackName(stack);
                String constructionId = getBoundConstructionId(stack);
                com.prefab.addon.extension.ConstructionInfo info =
                        ExtensionPackManager.getInstance().findConstruction(packName, constructionId);
                if (info != null) {
                    // 关键：用 rightClickedPos.above() 作为建筑原点（避免该位置已是草方块
                    // 导致 Prefab 的 bakeBlockAndSubBlock 跳过 by=0 层的方块渲染）
                    BlockPos structurePos = context.getClickedPos().above();
                    PrefabCustomAddon.LOGGER.info("[USE-DEBUG] Opening CustomStructureGui (Prefab native) for {}/{} at pos={}", packName, constructionId, structurePos);
                    // 用 Prefab 原生 GuiStructure 处理预览和建造
                    Minecraft.getInstance().setScreen(new com.prefab.addon.client.gui.CustomStructureGui(info, stack, structurePos));
                    return InteractionResult.SUCCESS;
                } else {
                    PrefabCustomAddon.LOGGER.error("Cannot find bound construction: {}/{}", packName, constructionId);
                    return InteractionResult.PASS;
                }
            } else {
                // 未绑定，打开选择界面
                PrefabCustomAddon.LOGGER.info("[USE-DEBUG] No bound, opening GuiCustomStructureSelection");
                GuiCustomStructureSelection.open();
                return InteractionResult.SUCCESS;
            }
        }
        return InteractionResult.PASS;
    }

    public static boolean hasConstructionBound(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        CompoundTag tag = data.copyTag();
        return tag.contains("packName") && tag.contains("constructionId");
    }

    public static String getBoundPackName(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return "";
        return data.copyTag().getString("packName");
    }

    public static String getBoundConstructionId(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return "";
        return data.copyTag().getString("constructionId");
    }

    public static boolean isBoundTo(ItemStack stack, com.prefab.addon.extension.ConstructionInfo info) {
        if (stack.getItem() != com.prefab.addon.PrefabCustomAddon.CUSTOM_BLUEPRINT.get()) return false;
        return getBoundPackName(stack).equals(info.getPack().getName())
            && getBoundConstructionId(stack).equals(info.getId());
    }

    public static void bindConstruction(ItemStack stack, String packName, String constructionId) {
        CompoundTag baseTag = new CompoundTag();
        baseTag.putString("packName", packName);
        baseTag.putString("constructionId", constructionId);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(baseTag));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        if (hasConstructionBound(stack)) {
            tooltip.add(Component.literal("Bound: " + getBoundPackName(stack) + " / " + getBoundConstructionId(stack)).withStyle(ChatFormatting.GOLD));
        } else {
            tooltip.add(Component.translatable("item.prefab_custom_addon.custom_blueprint.desc").withStyle(ChatFormatting.GRAY));
        }
        super.appendHoverText(stack, context, tooltip, flag);
    }
}