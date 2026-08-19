package com.prefab.addon.client;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.client.gui.CustomStructureGui;
import com.prefab.addon.client.gui.GuiConstructionDetail;
import com.prefab.addon.client.gui.GuiCustomStructureSelection;
import com.prefab.addon.client.gui.GuiExtensionPackBrowser;
import com.prefab.addon.extension.ConstructionInfo;
import com.prefab.addon.extension.ExtensionPackManager;
import com.prefab.addon.items.CustomBlueprintItem;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
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

    /**
     * Tag: 玩家在 "制作蓝图" tab 通过 KubeJS 注册的蓝图物品.
     * <p>认两种来源:
     * <ul>
     *   <li>{@link CustomBlueprintItem} (mod 原生, NBT 存 packName/constructionId/locked)</li>
     *   <li>任意 Item 加上这个 tag (KubeJS 注册的, NBT 一样存 packName/constructionId/locked)</li>
     * </ul>
     * tag 文件: {@code data/prefab_custom_addon/tags/items/player_blueprint.json}
     */
    private static final TagKey<Item> PLAYER_BLUEPRINT_TAG = TagKey.create(
        Registries.ITEM,
        ResourceLocation.fromNamespaceAndPath(PrefabCustomAddon.MOD_ID, "player_blueprint"));

    /** 玩家蓝图物品的 NBT key 集合 (跟 CustomBlueprintItem 完全一致). */
    private static final String NBT_PACK_NAME   = "packName";
    private static final String NBT_CONSTRUCTION = "constructionId";
    private static final String NBT_LOCKED      = "locked";

    /**
     * 判断物品是否需要被本 handler 拦截: 原生 CustomBlueprintItem 或带 player_blueprint tag 的.
     * <p>public: StructurePreviewKeyHandler 也要复用这个判断 (查找背包蓝图时, 不只认 CustomBlueprintItem,
     * 也认 KubeJS 注册的带 tag 物品).</p>
     */
    public static boolean isHandledBlueprint(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.getItem() instanceof CustomBlueprintItem) return true;
        if (stack.is(PLAYER_BLUEPRINT_TAG)) return true;
        return false;
    }

    /** 玩家蓝图 NBT 读取 (兼容 CustomBlueprintItem 和 tag 物品). */
    private static boolean hasBound(ItemStack stack) {
        net.minecraft.world.item.component.CustomData data =
            stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        net.minecraft.nbt.CompoundTag tag = data.copyTag();
        return tag.contains(NBT_PACK_NAME) && tag.contains(NBT_CONSTRUCTION);
    }

    private static String getBoundPackName(ItemStack stack) {
        net.minecraft.world.item.component.CustomData data =
            stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (data == null) return "";
        return data.copyTag().getString(NBT_PACK_NAME);
    }

    private static String getBoundConstructionId(ItemStack stack) {
        net.minecraft.world.item.component.CustomData data =
            stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (data == null) return "";
        return data.copyTag().getString(NBT_CONSTRUCTION);
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        Player player = event.getEntity();
        if (!player.level().isClientSide) return;

        ItemStack stack = event.getItemStack();
        if (!isHandledBlueprint(stack)) return;

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
        if (!isHandledBlueprint(stack)) return;

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
            com.prefab.addon.PrefabCustomAddon.tr("welcome.thanks")));
    }

    private static void openGuiForStack(ItemStack stack, Player player, BlockPos pos, InteractionHand hand) {
        boolean bound = hasBound(stack);
        PrefabCustomAddon.LOGGER.info(
            "[USE-DEBUG] ClientHandler: hand={} hasBound={} item={} pack={} id={}",
            hand, bound, stack.getItem(),
            getBoundPackName(stack), getBoundConstructionId(stack));

        if (bound) {
            String packName = getBoundPackName(stack);
            String constructionId = getBoundConstructionId(stack);
            ConstructionInfo info = ExtensionPackManager.getInstance().findConstruction(packName, constructionId);
            if (info == null) {
                // 向后兼容: 旧版本可能用 getPackageName() 绑定, 直接 lookup 失败.
                // 退化方案: 按 constructionId 找第一个匹配 (用户需重新绑一次以修好).
                PrefabCustomAddon.LOGGER.warn(
                    "[USE-DEBUG] ClientHandler: direct lookup miss for {}/{}, trying fallback by id only",
                    packName, constructionId);
                info = ExtensionPackManager.getInstance().findByConstructionIdOnly(constructionId);
            }
            if (info == null && "local".equals(packName)) {
                // 单文件建筑 (LocalBuilding 转的) 没有 pack, 但 "local" 应该是约定 packName.
                // 既然 findByConstructionIdOnly 也找不到, 多半是构造文件夹被删/移走了, 用全局扫一次 LocalBuilding 兜底.
                PrefabCustomAddon.LOGGER.warn(
                    "[USE-DEBUG] ClientHandler: local building lookup miss for '{}', trying LocalBuildingScanner fallback",
                    constructionId);
                info = scanLocalBuildingFallback(constructionId);
            }
            if (info != null) {
                String packLabel = info.getPack() != null ? info.getPack().getName() : "local";
                PrefabCustomAddon.LOGGER.info("[USE-DEBUG] ClientHandler opening CustomStructureGui for {}/{} at {}",
                        packLabel, constructionId, pos);
                CustomStructureGui.open(info, stack, pos);
            } else {
                PrefabCustomAddon.LOGGER.error(
                    "[USE-DEBUG] ClientHandler: bound but info is null for {}/{} (rebind needed?)",
                    packName, constructionId);
                // 给玩家一个明确的错误提示, 避免"右键无反应"看起来像 bug
                net.minecraft.network.chat.Component msg = net.minecraft.network.chat.Component.literal(
                    com.prefab.addon.PrefabCustomAddon.tr("err.not_found", "?", constructionId)
                    + "\n§7可能文件被删除/移走, 请重新 [选择] 建筑");
                player.sendSystemMessage(msg);
            }
        } else {
            // 未绑定 → 打开新版"拓展包/建筑管理"浏览器 (左侧 5 个 tab, 右侧卡片列表)
            // 不再直接进 detail 界面, 让玩家先选要建造的建筑
            PrefabCustomAddon.LOGGER.info("[USE-DEBUG] ClientHandler: no bound, opening GuiExtensionPackBrowser");
            GuiExtensionPackBrowser.open();
        }
    }

    /**
     * 单文件建筑最后兜底: 直接走 LocalBuildingScanner 扫所有可能的目录 (下载/拓展包),
     * 找 id 匹配的. 找不到返回 null.
     */
    private static ConstructionInfo scanLocalBuildingFallback(String constructionId) {
        try {
            java.util.List<com.prefab.addon.extension.LocalBuilding> all =
                com.prefab.addon.extension.LocalBuildingScanner.scanAll();
            for (com.prefab.addon.extension.LocalBuilding lb : all) {
                if (constructionId.equals(lb.id)) {
                    ConstructionInfo c = new ConstructionInfo(lb.id);
                    c.setName(lb.name);
                    c.setAuthor(lb.author);
                    c.setDescription(lb.description);
                    if (lb.fileExt != null && !lb.fileExt.isEmpty()) {
                        c.setFormat(lb.fileExt.startsWith(".") ? lb.fileExt.substring(1) : lb.fileExt);
                    }
                    c.setLocalImagePath(lb.imagePath);
                    c.setLocalNbtPath(lb.filePath);
                    return c;
                }
            }
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[USE-DEBUG] scanLocalBuildingFallback failed", t);
        }
        return null;
    }
}
