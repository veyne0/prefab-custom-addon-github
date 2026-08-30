package com.prefab.addon.client;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.client.gui.GuiOutsourceBuildingDetail;
import com.prefab.addon.items.OutsourceBlueprintItem;
import com.prefab.addon.outsource.OutsourceBuilding;
import com.prefab.addon.outsource.OutsourceBuildingLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 外包建筑蓝图 —— 客户端 GUI 拦截器。
 *
 * <p>只对 {@link OutsourceBlueprintItem} 生效。原 {@code CustomBlueprintItem} 走自己的
 * {@link CustomBlueprintClientHandler},互不干扰。</p>
 */
@EventBusSubscriber(modid = PrefabCustomAddon.MOD_ID, value = Dist.CLIENT)
public class OutsourceBuildingClientHandler {

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        Player player = event.getEntity();
        if (!player.level().isClientSide) return;
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof OutsourceBlueprintItem)) return;
        event.setCanceled(true);
        BlockPos pos = player.blockPosition().above();
        openGuiForStack(stack, player, pos, event.getHand());
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (!player.level().isClientSide) return;
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof OutsourceBlueprintItem)) return;
        event.setCanceled(true);
        BlockPos pos = event.getPos().above();
        openGuiForStack(stack, player, pos, event.getHand());
    }

    private static void openGuiForStack(ItemStack stack, Player player, BlockPos pos, InteractionHand hand) {
        // 优先用 Item 实例硬编码的 buildingId (8 个外包蓝图都是这样)
        // 找不到 (旧 NBT 形式 / 第三方写 NBT) 时再 fallback 到 NBT
        String buildingId = OutsourceBlueprintItem.getEffectiveBuildingId(stack);
        if (buildingId.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c✗ 该蓝图没有绑定建筑数据"));
            return;
        }
        OutsourceBuilding b = OutsourceBuildingLoader.getInstance().getBuildingById(buildingId);
        if (b == null) {
            PrefabCustomAddon.LOGGER.warn("[OUTSOURCE] ClientHandler: buildingId '{}' not found (zip removed/renamed?)",
                buildingId);
            player.sendSystemMessage(Component.literal(
                "§c✗ 找不到建筑 '" + buildingId + "' 对应的 ZIP,可能已被删除/重命名\n" +
                "§7请执行 /prefabaddon reload 或重新放置 ZIP 后重进游戏"));
            return;
        }
        // 默认打开第 0 个风格,玩家点"切换"按钮可在 in-place 切下一个
        int styleIndex = 0;
        // 兼容 NBT 中存的 styleIndex (老存档 / 第三方生成的 ItemStack)
        if (stack.getItem() instanceof OutsourceBlueprintItem) {
            int nbtStyle = OutsourceBlueprintItem.getStyleIndex(stack);
            if (nbtStyle >= 0 && nbtStyle < b.getStyleCount()) {
                styleIndex = nbtStyle;
            }
        }
        PrefabCustomAddon.LOGGER.info("[OUTSOURCE] ClientHandler open: '{}' (style {}/{}) at {}",
            b.getFolderName(), styleIndex + 1, b.getStyleCount(), pos);
        GuiOutsourceBuildingDetail.open(b, styleIndex);
    }
}
