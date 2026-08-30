package com.prefab.addon.structure;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.config.BuildAnimationMode;
import com.prefab.addon.items.OutsourceBlueprintItem;
import com.prefab.addon.outsource.OutsourceBuilding;
import com.prefab.addon.outsource.OutsourceBuildingLoader;
import com.prefab.addon.outsource.OutsourceStyle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * 外包建筑的服务器端建造入口.
 *
 * <p>跟普通自定义建筑完全独立的路径, 原因:
 * <ol>
 *   <li>外包建筑 ({@link OutsourceBuilding}) 来自 {@code <gameDir>/prefab-outsource/*.zip},
 *       不在 {@code ExtensionPackManager} 里 (那是 mod 玩家自己配置的拓展包).</li>
 *   <li>每个 Item 实例硬编码一个 {@code buildingId} (对应 ZIP 里的子文件夹), 蓝图消耗必须按
 *       buildingId 精确匹配, 跟普通 custom blueprint 的 packName+constructionId 匹配逻辑不同.</li>
 *   <li>服务端磁盘重扫时, 强制 reload {@link OutsourceBuildingLoader} 拿到最新 ZIP 内容,
 *       防止管理员中途换 ZIP 后玩家还拿旧数据建.</li>
 * </ol>
 *
 * <h3>调用流程</h3>
 * <pre>
 *   客户端点 "预览" → ALT 按下
 *     → StructurePreviewKeyHandler.triggerOutsourceBuildAtPreview
 *     → 发送 BuildOutsourceStructurePayload(buildingId, styleIndex, pos, facing, mode)
 *   服务端 NetworkHandler.handleBuildOutsource
 *     → OutsourceBuildManager.placeStructure(player, level, ...)
 *     → 重扫 OutsourceBuildingLoader
 *     → OutsourceBuilding.getStyles().get(styleIndex).getNbtData()
 *     → CustomStructureBuilder.parseStructureBlocksFromNbt(nbt)
 *     → AsyncBuildManager.startTask(packName="outsource", constructionId="&lt;id&gt;_style_&lt;idx&gt;")
 *   完成后 AsyncBuildManager.consumeBlueprint
 *     → 检测 packName == "outsource" → OutsourceBuildManager.consumeOutsourceBlueprint
 *     → 按 buildingId 匹配背包里的 OutsourceBlueprintItem → setCount-1 / setItem(EMPTY)
 * </pre>
 */
public final class OutsourceBuildManager {

    /**
     * 标记外包建筑 task 的 packName. 跟普通 "extension_pack_name" 完全区别开, 避免
     * AsyncBuildManager.consumeBlueprint 走 ExtensionPackManager 路径, 走专用的
     * OutsourceBlueprintItem 消耗逻辑.
     */
    public static final String OUTSOURCE_PACK_NAME = "outsource";

    private OutsourceBuildManager() {}

    /**
     * 启动一个外包建筑的异步建造任务.
     *
     * @return true = 任务已成功入队; false = 建筑没找到 / NBT 解析失败 / 风格越界.
     */
    public static boolean placeStructure(ServerPlayer player, ServerLevel level, String buildingId,
                                         int styleIndex, BlockPos origin, Direction houseFacing,
                                         BuildAnimationMode animationMode) {
        if (player == null || level == null) {
            PrefabCustomAddon.LOGGER.warn("[OUTSOURCE-BUILD] placeStructure: player/level 为空, 拒绝");
            return false;
        }
        if (buildingId == null || buildingId.isEmpty()) {
            PrefabCustomAddon.LOGGER.warn("[OUTSOURCE-BUILD] placeStructure: buildingId 为空, 拒绝");
            return false;
        }

        // 1) 强制重扫 ZIP 目录, 防止管理员换 ZIP 后玩家还拿旧数据
        try {
            OutsourceBuildingLoader.getInstance().scan(
                player.getServer().getServerDirectory());
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[OUTSOURCE-BUILD] 强制重扫失败 (非致命, 用上次缓存)", t);
        }

        // 2) 找建筑
        OutsourceBuilding building = OutsourceBuildingLoader.getInstance().getBuildingById(buildingId);
        if (building == null) {
            PrefabCustomAddon.LOGGER.warn("[OUTSOURCE-BUILD] 找不到 building: '{}' (玩家 {} 尝试建造)",
                buildingId, player.getName().getString());
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§c✗ 找不到外包建筑: " + buildingId + " (ZIP 可能已删除, 重进或 /prefabaddon reload)")
                .withStyle(net.minecraft.ChatFormatting.RED));
            return false;
        }
        if (styleIndex < 0 || styleIndex >= building.getStyleCount()) {
            PrefabCustomAddon.LOGGER.warn("[OUTSOURCE-BUILD] 风格索引越界: {} (count={})",
                styleIndex, building.getStyleCount());
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§c✗ 风格索引越界: " + styleIndex)
                .withStyle(net.minecraft.ChatFormatting.RED));
            return false;
        }

        OutsourceStyle style = building.getStyles().get(styleIndex);
        byte[] nbtData = style.getNbtData();
        if (nbtData == null || nbtData.length == 0) {
            PrefabCustomAddon.LOGGER.warn("[OUTSOURCE-BUILD] 风格 {} NBT 数据为空: {}",
                styleIndex, style.getFileName());
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§c✗ 建筑 NBT 数据为空: " + style.getFileName())
                .withStyle(net.minecraft.ChatFormatting.RED));
            return false;
        }

        // 3) 解析 NBT → BlockData 列表
        //    直接用静态方法, 不用走 CustomStructureBuilder 实例的 currentStructureNbt 字段,
        //    跟其他自定义建筑隔离, 不会污染 instance 状态.
        CompoundTag nbt;
        try {
            nbt = NbtIo.readCompressed(new ByteArrayInputStream(nbtData), NbtAccounter.unlimitedHeap());
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.error("[OUTSOURCE-BUILD] NBT 解析失败: {}/{}",
                buildingId, style.getFileName(), e);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§c✗ NBT 解析失败: " + e.getMessage())
                .withStyle(net.minecraft.ChatFormatting.RED));
            return false;
        }

        List<CustomStructureBuilder.BlockData> blocks;
        try {
            blocks = CustomStructureBuilder.parseStructureBlocksFromNbt(nbt);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[OUTSOURCE-BUILD] 解析 BlockData 失败: {}/{}",
                buildingId, style.getFileName(), t);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§c✗ 方块解析失败: " + t.getMessage())
                .withStyle(net.minecraft.ChatFormatting.RED));
            return false;
        }
        if (blocks == null || blocks.isEmpty()) {
            PrefabCustomAddon.LOGGER.warn("[OUTSOURCE-BUILD] 建筑无方块: {}/{}",
                buildingId, style.getFileName());
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§c✗ 建筑无方块: " + style.getFileName())
                .withStyle(net.minecraft.ChatFormatting.RED));
            return false;
        }

        // 4) 启动异步任务
        //    constructionId 用 buildingId + "_style_" + styleIndex 形式, 既唯一又能从 task 还原 buildingId.
        String constructionId = buildingId + "_style_" + styleIndex;
        AsyncBuildManager.startTask(player, level, origin,
            OUTSOURCE_PACK_NAME, constructionId,
            blocks, houseFacing,
            animationMode != null ? animationMode : BuildAnimationMode.OFF);

        PrefabCustomAddon.LOGGER.info("[OUTSOURCE-BUILD] placeStructure 启动: player={} building={} style={}/{} pos={} facing={} mode={} blocks={}",
            player.getName().getString(), buildingId, styleIndex, building.getStyleCount(),
            origin, houseFacing, animationMode, blocks.size());
        return true;
    }

    /**
     * 从 constructionId 解析回 buildingId.
     * <p>格式约定: {@code <buildingId>_style_<idx>}, 跟 {@link #placeStructure} 里
     * 写 constructionId 的方式一致.</p>
     */
    public static String parseBuildingIdFromConstructionId(String constructionId) {
        if (constructionId == null) return "";
        int idx = constructionId.lastIndexOf("_style_");
        if (idx < 0) return constructionId;
        return constructionId.substring(0, idx);
    }

    /**
     * 异步任务完成时, 消耗一个对应的 {@link OutsourceBlueprintItem}.
     *
     * <p>由 {@code AsyncBuildManager.consumeBlueprint} 在检测到
     * {@code task.packName.equals(OUTSOURCE_PACK_NAME)} 时调用.</p>
     *
     * <p>匹配规则: 遍历背包, 找到 Item 类型是 OutsourceBlueprintItem 且
     * {@link OutsourceBlueprintItem#getEffectiveBuildingId} 等于传入 buildingId 的
     * 第一张, 减 1 (count==1 时清空).</p>
     */
    public static void consumeOutsourceBlueprint(ServerPlayer player, String buildingId) {
        if (player == null) return;
        if (buildingId == null || buildingId.isEmpty()) return;

        Inventory inv = player.getInventory();
        // 优先主手 (跟 AsyncBuildManager.consumeBlueprintByIdOnly 同样的策略)
        int selected = inv.selected;
        ItemStack hotbarStack = inv.getItem(selected);
        int foundSlot = -1;
        ItemStack foundStack = ItemStack.EMPTY;
        if (isMatchingOutsourceBlueprint(hotbarStack, buildingId)) {
            foundSlot = selected;
            foundStack = hotbarStack;
        } else {
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (isMatchingOutsourceBlueprint(stack, buildingId)) {
                    foundSlot = i;
                    foundStack = stack;
                    break;
                }
            }
        }
        if (foundStack.isEmpty()) {
            PrefabCustomAddon.LOGGER.warn(
                "[OUTSOURCE-BUILD] 兜底: 找不到 buildingId={} 的 OutsourceBlueprintItem (玩家 {} 背包里没有, 任务完成但不消耗)",
                buildingId, player.getName().getString());
            return;
        }

        int prevCount = foundStack.getCount();
        if (foundStack.getCount() == 1) {
            inv.setItem(foundSlot, ItemStack.EMPTY);
        } else {
            foundStack.setCount(foundStack.getCount() - 1);
        }
        if (player.containerMenu != null) {
            player.containerMenu.broadcastChanges();
        }
        if (player.connection != null) {
            int stateId = player.containerMenu != null ? player.containerMenu.getStateId() : 0;
            net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket packet =
                new net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket(
                    net.minecraft.world.inventory.InventoryMenu.CONTAINER_ID, stateId,
                    player.inventoryMenu.getItems(), net.minecraft.world.item.ItemStack.EMPTY);
            player.connection.send(packet);
        }
        inv.setChanged();
        PrefabCustomAddon.LOGGER.info("[OUTSOURCE-BUILD] 已消耗 OutsourceBlueprintItem (slot {} was {}, buildingId={}, player={})",
            foundSlot, prevCount, buildingId, player.getName().getString());
    }

    private static boolean isMatchingOutsourceBlueprint(ItemStack stack, String buildingId) {
        if (stack.isEmpty()) return false;
        if (!(stack.getItem() instanceof OutsourceBlueprintItem)) return false;
        return buildingId.equals(OutsourceBlueprintItem.getEffectiveBuildingId(stack));
    }
}
