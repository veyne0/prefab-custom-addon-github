package com.prefab.addon.items;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.ExtensionPackManager;
import com.prefab.addon.network.BuildCustomStructurePayload;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
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
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 自定义蓝图物品 (common 部分)。
 *
 * **绝对不要在本类里 import 任何 net.minecraft.client.* 或 client.gui.* 类**。
 * RegisterEvent 阶段会强制加载本类, 一旦静态结构 (字段/方法签名/方法体里的类引用)
 * 触碰到客户端类, 在 dedicated server 加载时会被 RuntimeDistCleaner 直接抛
 * "Attempted to load class net/minecraft/client/... for invalid dist DEDICATED_SERVER",
 * 整个 mod 加载回滚, 服务端崩溃。
 *
 * 所有客户端 GUI 触发 (打开 CustomStructureGui / GuiCustomStructureSelection) 都在
 * {@code com.prefab.addon.client.CustomBlueprintClientHandler} 里通过
 * {@code PlayerInteractEvent.RightClickItem/RightClickBlock} 拦截, 该类
 * 标注了 {@code @Mod.EventBusSubscriber(Dist.CLIENT)}, 永远不会在服务端加载。
 */
public class CustomBlueprintItem extends Item {
    public CustomBlueprintItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        // 客户端实际打开 GUI 的逻辑在 CustomBlueprintClientHandler.onRightClickItem 里
        // 这里服务端不需要任何行为, 直接返回 success 消费掉右键事件
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        // 客户端实际打开 GUI 的逻辑在 CustomBlueprintClientHandler.onRightClickBlock 里
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
        // 单文件建筑 (LocalBuilding 兜底) 没有 ExtensionPack, getPack() 可能是 null.
        // 旧蓝图可能存的是 "local" packName, 用 STANDALONE_PACKAGE 比较, 跟 isBuildable 路径保持一致.
        String infoPack = (info.getPack() != null)
            ? info.getPack().getName()
            : com.prefab.addon.extension.ExtensionPackManager.STANDALONE_PACKAGE;
        return getBoundPackName(stack).equals(infoPack)
            && getBoundConstructionId(stack).equals(info.getId());
    }

    /**
     * 蓝图是否被"锁定绑定" — 一旦锁定, 就不能再用 Select 重新绑其他建筑.
     * 适合做能交易的蓝图: 一旦绑好就改不了, 可以放心地放在箱子里交易.
     */
    public static boolean isLocked(ItemStack stack) {
        if (stack.getItem() != com.prefab.addon.PrefabCustomAddon.CUSTOM_BLUEPRINT.get()) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        return data.copyTag().getBoolean("locked");
    }

    public static void bindConstruction(ItemStack stack, String packName, String constructionId) {
        bindConstruction(stack, packName, constructionId, false);
    }

    /**
     * 设置蓝图的 locked 状态, 不改 packName/constructionId.
     * <p>专门给"锁定绑定"toggle 用: 玩家点 toggle 时切 locked 字段, 同时更新显示名 (加/去 🔒).</p>
     *
     * <p>不允许给不同建筑切 locked - 必须 packName/constructionId 跟当前绑定一致.</p>
     *
     * <p>为什么不用 {@link #bindConstruction}: 那个方法会拒绝已锁定的蓝图重新绑定,
     * 包括"想解锁"的合法场景. 这个方法绕过那个 guard, 只动 locked 字段.</p>
     *
     * @return true = 设置成功, false = 蓝图未绑到 packName/constructionId (拒绝)
     */
    public static boolean setLocked(ItemStack stack, String packName, String constructionId, boolean locked) {
        if (stack.getItem() != com.prefab.addon.PrefabCustomAddon.CUSTOM_BLUEPRINT.get()) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        CompoundTag tag = data.copyTag();
        if (!packName.equals(tag.getString("packName"))) return false;
        if (!constructionId.equals(tag.getString("constructionId"))) return false;
        tag.putBoolean("locked", locked);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        // 更新显示名 (加/去 🔒)
        try {
            String displayName = constructionId;
            com.prefab.addon.extension.ConstructionInfo info =
                ExtensionPackManager.getInstance().findConstruction(packName, constructionId);
            if (info != null) {
                displayName = info.getName();
            }
            String label = "Custom Blueprint - " + displayName + (locked ? " 🔒" : "");
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(label));
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("[BIND-DEBUG] Failed to update custom name after lock toggle", e);
        }
        return true;
    }

    /**
     * 在玩家背包里找一份绑定了 packName/constructionId 的 Custom Blueprint, 消耗 (shrink) 1 个.
     * <p>一份蓝图 = 一次建造. 玩家绑好蓝图后, 每次点建造都会消耗 1 个, 直到用完.
     * 这样可以防止"一份蓝图无限造"的复制 bug.
     *
     * @param player 玩家 (会扫描主背包 + 副手)
     * @param packName 拓展包名
     * @param constructionId 建筑 ID
     * @return true = 成功消耗了 1 个, false = 没找到匹配的蓝图
     */
    public static boolean consumeOne(net.minecraft.world.entity.player.Player player,
                                     String packName, String constructionId) {
        if (player == null) return false;
        net.minecraft.world.entity.player.Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() != com.prefab.addon.PrefabCustomAddon.CUSTOM_BLUEPRINT.get()) continue;
            if (!hasConstructionBound(stack)) continue;
            if (!getBoundPackName(stack).equals(packName)) continue;
            if (!getBoundConstructionId(stack).equals(constructionId)) continue;
            // 找到了匹配的蓝图, 消耗 1 个
            stack.shrink(1);
            if (stack.getCount() <= 0) {
                inv.setItem(i, ItemStack.EMPTY);
            }
            // 推送到客户端, 避免玩家屏幕上的蓝图数没及时更新
            if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                sp.containerMenu.broadcastChanges();
                inv.setChanged();
            }
            return true;
        }
        return false;
    }

    public static void bindConstruction(ItemStack stack, String packName, String constructionId, boolean locked) {
        // 已锁定的蓝图禁止重新绑
        if (isLocked(stack)) {
            PrefabCustomAddon.LOGGER.warn(
                "[BIND-DEBUG] 蓝图已锁定, 拒绝重新绑定: current={}/{} new={}/{}",
                getBoundPackName(stack), getBoundConstructionId(stack), packName, constructionId);
            return;
        }
        CompoundTag baseTag = new CompoundTag();
        baseTag.putString("packName", packName);
        baseTag.putString("constructionId", constructionId);
        baseTag.putBoolean("locked", locked);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(baseTag));

        // 视觉区分已绑定的蓝图 (玩家背包里有多张蓝图时分不清哪个绑了哪个没绑)
        // - 改显示名 → "Custom Blueprint - 橡树"
        // - 加附魔光效
        try {
            String displayName = constructionId;
            com.prefab.addon.extension.ConstructionInfo info =
                ExtensionPackManager.getInstance().findConstruction(packName, constructionId);
            if (info != null) {
                displayName = info.getName();
            }
            String label = "Custom Blueprint - " + displayName
                + (locked ? " 🔒" : "");
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(label));
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("[BIND-DEBUG] Failed to set custom name / glint", e);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        if (hasConstructionBound(stack)) {
            // 显示绑定的建筑的中文名(优先), 找不到名字时回退到 id
            String packName = getBoundPackName(stack);
            String constructionId = getBoundConstructionId(stack);
            String displayName = constructionId;
            com.prefab.addon.extension.ConstructionInfo info =
                ExtensionPackManager.getInstance().findConstruction(packName, constructionId);
            if (info != null) {
                displayName = info.getName();
            }
            tooltip.add(Component.literal("Bound: " + displayName)
                .withStyle(ChatFormatting.GOLD));
            if (isLocked(stack)) {
                tooltip.add(Component.literal(com.prefab.addon.PrefabCustomAddon.tr("item.custom_blueprint.locked"))
                    .withStyle(ChatFormatting.RED));
            }
        } else {
            tooltip.add(Component.translatable("item.prefab_custom_addon.custom_blueprint.desc").withStyle(ChatFormatting.GRAY));
        }
        super.appendHoverText(stack, context, tooltip, flag);
    }
}