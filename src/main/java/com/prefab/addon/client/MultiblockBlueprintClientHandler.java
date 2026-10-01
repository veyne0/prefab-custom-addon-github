package com.prefab.addon.client;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.client.gui.GuiMultiblockBrowser;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 多方块结构蓝图物品 — 客户端 GUI 拦截器.
 *
 * 必须在 client 端, 所以用 {@code @EventBusSubscriber(Dist.CLIENT)}:
 * dedicated server 永远不会加载这个类, 自然不会触发
 * "Attempted to load class net/minecraft/client/... for invalid dist DEDICATED_SERVER".
 *
 * 工作方式 (与 {@link CustomBlueprintClientHandler} 同模式):
 *   - 玩家右键物品 (空气):  {@link #onRightClickItem} 拦截, 打开多方块浏览器, 取消事件
 *   - 玩家右键方块:        {@link #onRightClickBlock} 拦截, 打开多方块浏览器, 取消事件
 */
@EventBusSubscriber(modid = PrefabCustomAddon.MOD_ID, value = Dist.CLIENT)
public class MultiblockBlueprintClientHandler {

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        Player player = event.getEntity();
        if (!player.level().isClientSide) return;
        if (!isMultiblockBlueprint(event.getItemStack())) return;
        event.setCanceled(true);
        GuiMultiblockBrowser.open();
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (!player.level().isClientSide) return;
        if (!isMultiblockBlueprint(event.getItemStack())) return;
        event.setCanceled(true);
        GuiMultiblockBrowser.open();
    }

    private static boolean isMultiblockBlueprint(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() == PrefabCustomAddon.MULTIBLOCK_BLUEPRINT.get();
    }
}
