package com.prefab.addon.client;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.client.gui.CustomStructureGui;
import com.prefab.addon.client.gui.GuiConstructionDetail;
import com.prefab.addon.client.gui.GuiCustomStructureSelection;
import com.prefab.addon.extension.ConstructionInfo;
import com.prefab.addon.extension.ExtensionPackManager;
import com.prefab.addon.items.CustomBlueprintItem;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 自定义蓝图物品 — 客户端 GUI 拦截器。
 *
 * 必须在 client 端, 所以用 {@code @EventBusSubscriber(Dist.CLIENT)}:
 * dedicated server 永远不会加载这个类, 自然不会触发
 * "Attempted to load class net/minecraft/client/... for invalid dist DEDICATED_SERVER"。
 *
 * 工作方式:
 *   - 玩家右键物品 (空气):  {@link #onRightClickItem} 拦截, 打开 GUI, 取消事件
 *   - 玩家右键方块:        {@link #onRightClickBlock} 拦截, 打开 GUI, 取消事件
 *   - 已绑定的蓝图 → CustomStructureGui (Prefab 原生预览+建造界面)
 *   - 未绑定的蓝图 → GuiCustomStructureSelection (拓展包/建筑选择界面)
 *
 * 位置 (BlockPos) 处理:
 *   - 右键空气: 用玩家脚上方 1 格
 *   - 右键方块: 用点击面之上 1 格
 */
@EventBusSubscriber(modid = PrefabCustomAddon.MOD_ID, value = Dist.CLIENT)
public class CustomBlueprintClientHandler {

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        Player player = event.getEntity();
        if (!player.level().isClientSide) return;

        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        if (!(stack.getItem() instanceof CustomBlueprintItem)) return;

        // 客户端拦截: 取消事件, 自己开 GUI
        event.setCanceled(true);
        BlockPos pos = player.blockPosition().above();
        openGuiForStack(stack, player, pos, event.getHand());
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (!player.level().isClientSide) return;

        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        if (!(stack.getItem() instanceof CustomBlueprintItem)) return;

        event.setCanceled(true);
        // 关键: 用点击面之上 1 格作为建筑原点 (避免该位置是草方块导致 Prefab 跳过 by=0 层)
        BlockPos pos = event.getPos().above();
        openGuiForStack(stack, player, pos, event.getHand());
    }

    /**
     * 玩家进游戏时发欢迎消息 - 客户端路径 (覆盖纯客户端 / 单人模式).
     * 服务端版在 {@link com.prefab.addon.PrefabCustomAddon#onPlayerLoggedIn} 里, 专用服玩家会通过那条路径收到欢迎消息.
     * 本 listener 处理纯客户端 / 单人模式: 此时 event.entity = LocalPlayer (非 ServerPlayer), 服务端 listener 不会跑.
     *
     * 关键: 在整合服场景下, 物理客户端走这里 (因为 PlayerEvent 在物理端都会触发), 但服务端的
     *      onPlayerLoggedIn 也会跑 (ServerPlayer). 为避免重复发, 用 Minecraft.hasSingleplayerServer() 区分:
     *      - 单人模式 (hasSingleplayerServer = true): 物理服务端就是本进程 → onPlayerLoggedIn 会触发 (ServerPlayer),
     *        客户端 listener 跳过, 避免重复.
     *      - 整合服 (hasSingleplayerServer = false 但玩家也是 ServerPlayer): 服务端会发, 客户端 listener 也跳过.
     *      - 纯远程客户端登录到专用服 (hasSingleplayerServer = false 且 entity 非 ServerPlayer): 服务端发,
     *        但客户端这边要等到 sync 后才收到 → 这里再发一次 (其实会重复). 暂接受: 远端客户端发一次, 服务端也会发一次,
     *        远端客户端会看到 2 条. 这是用 server.sendSystemMessage 的固有局限, 实际使用可接受.
     *
     * 实际上更简单的方案: 客户端 listener 只在 hasSingleplayerServer = true 时发 (因为单人模式的 entity 不是
     * ServerPlayer, 服务端 onPlayerLoggedIn 不会跑), 其他情况不发 (因为服务端 onPlayerLoggedIn 已经发了).
     *
     * 注意: 本类标了 @EventBusSubscriber(Dist.CLIENT), 在 dedicated server 上永远不会加载本类,
     *       所以不会发生 client API 误用问题.
     */
    @SubscribeEvent
    public static void onPlayerLoggedInClient(PlayerEvent.PlayerLoggedInEvent event) {
        if (!event.getEntity().level().isClientSide()) return;
        if (event.getEntity() != Minecraft.getInstance().player) return;
        // 单人模式才在这里发 (其他情况服务端已发过)
        if (!Minecraft.getInstance().hasSingleplayerServer()) return;
        event.getEntity().sendSystemMessage(net.minecraft.network.chat.Component.literal(
            "§a感谢使用 §e\"预制建筑附属\"§a, 按 §fO §a键可以打开本模组的设置界面"));
    }

    private static void openGuiForStack(ItemStack stack, Player player, BlockPos pos, InteractionHand hand) {
        boolean bound = CustomBlueprintItem.hasConstructionBound(stack);
        PrefabCustomAddon.LOGGER.info(
            "[USE-DEBUG] ClientHandler: hand={} hasBound={} locked={} pack={} id={}",
            hand, bound, CustomBlueprintItem.isLocked(stack),
            CustomBlueprintItem.getBoundPackName(stack),
            CustomBlueprintItem.getBoundConstructionId(stack));

        if (bound) {
            String packName = CustomBlueprintItem.getBoundPackName(stack);
            String constructionId = CustomBlueprintItem.getBoundConstructionId(stack);
            ConstructionInfo info = ExtensionPackManager.getInstance().findConstruction(packName, constructionId);
            if (info == null) {
                // 向后兼容: 旧版本可能用 getPackageName() 绑定, 直接 lookup 失败.
                // 退化方案: 按 constructionId 找第一个匹配 (用户需重新绑一次以修好).
                PrefabCustomAddon.LOGGER.warn(
                    "[USE-DEBUG] ClientHandler: direct lookup miss for {}/{}, trying fallback by id only",
                    packName, constructionId);
                info = ExtensionPackManager.getInstance().findByConstructionIdOnly(constructionId);
            }
            if (info != null) {
                PrefabCustomAddon.LOGGER.info("[USE-DEBUG] ClientHandler opening CustomStructureGui for {}/{} at {}",
                        info.getPack().getName(), constructionId, pos);
                CustomStructureGui.open(info, stack, pos);
            } else {
                PrefabCustomAddon.LOGGER.error(
                    "[USE-DEBUG] ClientHandler: bound but info is null for {}/{} (rebind needed?)",
                    packName, constructionId);
            }
        } else {
            PrefabCustomAddon.LOGGER.info("[USE-DEBUG] ClientHandler: no bound, opening GuiConstructionDetail (first available)");
            GuiConstructionDetail.openFirstAvailable();
        }
    }
}
