package com.prefab.addon.client;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.client.gui.GuiCustomBulldozer;
import com.prefab.addon.items.ItemCustomBulldozer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 自定义推土机 — 客户端右键拦截器.
 *
 * <p>右键点方块顶面 → 打开自定义推土机 GUI ({@link GuiCustomBulldozer}).</p>
 * <p>右键空气 → 也开 GUI, 起点用玩家脚上方 1 格.</p>
 */
@EventBusSubscriber(modid = PrefabCustomAddon.MOD_ID, value = Dist.CLIENT)
public class CustomBulldozerClientHandler {

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        Player player = event.getEntity();
        if (!player.level().isClientSide) return;
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        if (!(stack.getItem() instanceof ItemCustomBulldozer)) return;

        event.setCanceled(true);
        BlockPos pos = player.blockPosition().above();
        openGui(stack, player, pos, event.getHand());
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (!player.level().isClientSide) return;
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        if (!(stack.getItem() instanceof ItemCustomBulldozer)) return;

        event.setCanceled(true);
        // 必须点方块顶面, 否则不开 GUI
        if (event.getFace() != net.minecraft.core.Direction.UP) return;
        BlockPos pos = event.getPos().above();
        openGui(stack, player, pos, event.getHand());
    }

    private static void openGui(ItemStack stack, Player player, BlockPos pos, InteractionHand hand) {
        GuiCustomBulldozer.PENDING_STACK = stack;
        GuiCustomBulldozer.PENDING_POS = pos.immutable();
        Minecraft.getInstance().setScreen(new GuiCustomBulldozer(stack, pos));
    }
}
