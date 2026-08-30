package com.prefab.addon.items;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.outsource.OutsourceBuilding;
import com.prefab.addon.outsource.OutsourceBuildingLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 外包建筑蓝图 Item —— 一类物品,一份代码,但用 8 个不同的 registry 名注册出 8 个实例,
 * 每个实例的 {@link #fixedBuildingId} 在注册时硬编码,直接对应投影/下的一个子文件夹。
 *
 * <p>为什么改成"硬编码 ID":玩家把投影.zip 放进 prefab-outsource/ 之后,扫描器会按
 * "{@code 投影_中世纪小屋}" 这种规则生成 ID,8 个 Item 各自绑死一个 ID,玩家拿到哪个 Item
 * 就只能开对应的建筑 GUI,不用 NBT 区分,也不会被 NBT 改写。</p>
 *
 * <p>为兼容老存档(用 NBT 存 buildingId 的 ItemStack)以及测试代码,NBT 读取工具仍然保留,
 * 优先级: {@link #getFixedBuildingIdRaw()} (Item 实例硬编码) > NBT > 空串。</p>
 */
public class OutsourceBlueprintItem extends Item {

    /** 该 Item 实例硬编码绑定的建筑 ID (与 OutsourceBuildingLoader 扫描结果一致) */
    private final String fixedBuildingId;

    public OutsourceBlueprintItem(String fixedBuildingId, Properties properties) {
        super(properties);
        this.fixedBuildingId = fixedBuildingId == null ? "" : fixedBuildingId;
    }

    /** Item 构造时硬编码的 buildingId,本类所有实例都有值 */
    public String getFixedBuildingIdRaw() {
        return fixedBuildingId;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        // 客户端 GUI 实际打开逻辑在 OutsourceBuildingClientHandler 里
        if (level.isClientSide()) {
            return InteractionResultHolder.success(player.getItemInHand(hand));
        }
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    @Override
    public Component getName(ItemStack stack) {
        // === 玩家反馈 (2026-08): 不显示 "外包蓝图 - xxx", 只显示建筑名 ===
        // 之前用的是 super.getName(stack), 走 language key → "外包蓝图 - 中世纪小屋".
        // 8 个 Item 的 language key 是 "item....outsource_blueprint_<folder>" = "外包蓝图 - <folder>",
        // 玩家想直接看到建筑名 (例如 "中世纪小屋"), 不要 "外包蓝图 -" 这个前缀.
        //
        // 逻辑:
        //   1. 优先用 OutsourceBuilding.getFolderName()  (例如 "中世纪小屋")
        //      → 如果建筑还在 (ZIP 没被删/改) → 直接返回 folder name.
        //   2. ZIP 失效 (返回 null) → fallback 到 super.getName (即原 "外包蓝图 - xxx" language key),
        //      至少给玩家一个能认的物品名, 不至于变成 "null" 那种 debug 字符串.
        //   3. fixedBuildingId 为空 (理论上 8 个 Item 都有 hardcode, 不会到这里) → super.getName.
        if (fixedBuildingId != null && !fixedBuildingId.isEmpty()) {
            OutsourceBuilding b = OutsourceBuildingLoader.getInstance().getBuildingById(fixedBuildingId);
            if (b != null && b.getFolderName() != null && !b.getFolderName().isEmpty()) {
                return Component.literal(b.getFolderName());
            }
        }
        return super.getName(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        String buildingId = getEffectiveBuildingId(stack);
        if (buildingId.isEmpty()) {
            tooltip.add(Component.translatable(
                "item." + PrefabCustomAddon.MOD_ID + ".outsource_blueprint.desc")
                .withStyle(ChatFormatting.GRAY));
            return;
        }
        OutsourceBuilding b = OutsourceBuildingLoader.getInstance().getBuildingById(buildingId);
        if (b == null) {
            tooltip.add(Component.literal(
                "§c✗ 该建筑已失效 (ZIP 已被删除/修改,重进游戏或 /prefabaddon reload)"));
            return;
        }
        // === 玩家反馈 (2026-08): hover 不再显示 "来源: 投影.zip" ===
        // 之前三行: 建筑名 / 作者 / 来源. 玩家觉得 "来源" 这行没意义 (玩家关心的是建筑本身,
        //   不是哪个 zip 文件, 而且游戏内已经能直接打开 ZIP 看了) → 删掉.
        // 剩下的: 建筑名 (跟 getName 重复, 玩家说是 "蓝图悬浮时名字不要显示'外包建筑', 就显示蓝图的名字就行" ——
        //   他主要想看的是 hover 第一行直接是建筑名, 而不是 "外包蓝图 - xxx" 那个 title 字段.
        //   现在 title 已经被 getName 改成 "中世纪小屋" 了, hover 这里再写一遍建筑名 + 多风格标签
        //   仍然有用: 多风格建筑需要告诉玩家当前是哪个风格.
        if (b.hasMultipleStyles()) {
            // 多风格时, 在 hover 写风格编号 (1-based), 跟 GUI 里的"风格 1/2"一致
            int styleIndex = getStyleIndex(stack);
            tooltip.add(Component.literal(
                "§7[风格 " + (styleIndex + 1) + "/" + b.getStyleCount() + "]")
                .withStyle(ChatFormatting.GRAY));
        }
        tooltip.add(Component.literal("§7作者: " + b.getAuthor()).withStyle(ChatFormatting.GRAY));
    }

    // ============ 静态 NBT 读取工具 (兼容老 ItemStack,以及外部写 NBT 的场景) ============

    public static final String NBT_BUILDING_ID = "buildingId";
    public static final String NBT_STYLE_INDEX = "styleIndex";

    /** 读 NBT 中的 buildingId,缺失返回空字符串 */
    public static String getBuildingId(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return "";
        CompoundTag tag = data.copyTag();
        return tag.getString(NBT_BUILDING_ID);
    }

    /** 读 NBT 中的 styleIndex,缺失返回 0 */
    public static int getStyleIndex(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return 0;
        CompoundTag tag = data.copyTag();
        return tag.getInt(NBT_STYLE_INDEX);
    }

    /**
     * 写 NBT(供外部扩展/未来 KubeJS 注册等场景使用,正常 8 个 Item 不需要写 NBT)。
     */
    public static void setBuilding(ItemStack stack, String buildingId, int styleIndex) {
        CompoundTag tag = new CompoundTag();
        tag.putString(NBT_BUILDING_ID, buildingId);
        tag.putInt(NBT_STYLE_INDEX, styleIndex);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /**
     * 实际生效的 buildingId: 优先用 Item 实例的硬编码值,fallback 到 NBT。
     * 用于右键打开 GUI、tooltip 等所有需要确定"这是哪个建筑"的场景。
     */
    public static String getEffectiveBuildingId(ItemStack stack) {
        if (stack.getItem() instanceof OutsourceBlueprintItem item) {
            String fixed = item.getFixedBuildingIdRaw();
            if (fixed != null && !fixed.isEmpty()) return fixed;
        }
        return getBuildingId(stack);
    }
}
