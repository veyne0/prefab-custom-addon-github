package com.prefab.addon.terminal.device;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.HashMap;
import java.util.Map;

/**
 * 电池电量统一读写 (common, 双侧安全).
 *
 * "模拟电池" (vanilla 常见物品查表): 电量写进物品 CUSTOM_DATA "MtCharge" int.
 * (自 modernterminal 移植时移除了 GTM energy_content 组件的读写分支 — 本模组无 GTM 依赖.)
 *
 * Menu UI 下电池 stack 经 slot 同步到客户端, 所以客户端读 handler 里的电池
 * 就能拿到最新电量 — 不再需要独立的 currentEnergy 标量与同步包.
 */
public final class BatteryHelper {

    // ==== 物品→容量映射 (单位: EU) ====
    // 用 vanilla 常见物品做"模拟电池", 让原版玩家也能给终端供电.
    private static final Map<String, Integer> ENERGY_MAP = new HashMap<>();
    static {
        ENERGY_MAP.put(itemId(Items.REDSTONE),       600);
        ENERGY_MAP.put(itemId(Items.GUNPOWDER),      800);
        ENERGY_MAP.put(itemId(Items.COAL),           1600);
        ENERGY_MAP.put(itemId(Items.CHARCOAL),       1600);
        ENERGY_MAP.put(itemId(Items.GLOWSTONE_DUST), 4000);
        ENERGY_MAP.put(itemId(Items.BLAZE_POWDER),   2400);
        ENERGY_MAP.put(itemId(Items.BLAZE_ROD),      4800);
        ENERGY_MAP.put(itemId(Items.ENDER_PEARL),    2000);
        ENERGY_MAP.put(itemId(Items.EMERALD),        8000);
        ENERGY_MAP.put(itemId(Items.DIAMOND),        10000);
        ENERGY_MAP.put(itemId(Items.NETHER_STAR),    100000);
        ENERGY_MAP.put(itemId(Items.DRAGON_EGG),     50000);
    }

    // ==== 物品→探矿半径映射 (单位: chunk) ====
    private static final Map<String, Integer> RADIUS_MAP = new HashMap<>();
    static {
        RADIUS_MAP.put(itemId(Items.STICK),        1);
        RADIUS_MAP.put(itemId(Items.COMPASS),      2);
        RADIUS_MAP.put(itemId(Items.IRON_INGOT),   2);
        RADIUS_MAP.put(itemId(Items.GOLD_INGOT),   3);
        RADIUS_MAP.put(itemId(Items.EMERALD),      3);
        RADIUS_MAP.put(itemId(Items.DIAMOND),      4);
        RADIUS_MAP.put(itemId(Items.ENDER_PEARL),  3);
        RADIUS_MAP.put(itemId(Items.NETHER_STAR),  5);
    }

    private BatteryHelper() {
    }

    private static String itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static String stackId(ItemStack stack) {
        return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** 物品是否可作电池 (查表命中). */
    public static boolean isBattery(ItemStack stack) {
        return maxCharge(stack) > 0;
    }

    /** 物品是否可作探矿仪 (查表半径 > 0). */
    public static boolean isProspector(ItemStack stack) {
        return prospectRadius(stack) > 0;
    }

    /** 探矿半径 (0 = 不是探矿仪). */
    public static int prospectRadius(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        return RADIUS_MAP.getOrDefault(stackId(stack), 0);
    }

    /** 物品最大容量 (查表; 0 = 不认识). */
    public static long maxCharge(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        return ENERGY_MAP.getOrDefault(stackId(stack), 0);
    }

    /** 当前电量 (CUSTOM_DATA "MtCharge"). */
    public static long getCharge(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null) {
            return 0;
        }
        return Math.max(0, cd.copyTag().getInt("MtCharge"));
    }

    /** 写电量 (写 CUSTOM_DATA "MtCharge"). 非模拟电池返回 false. */
    public static boolean setCharge(ItemStack stack, long charge) {
        if (stack.isEmpty()) {
            return false;
        }
        if (ENERGY_MAP.getOrDefault(stackId(stack), 0) <= 0) {
            return false;
        }
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putInt("MtCharge", (int) Math.max(0, Math.min(Integer.MAX_VALUE, charge)));
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return true;
    }

    /** 首次放入电池槽时初始化模拟电池的 charge 组件. */
    public static void initChargeIfNeeded(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd != null && cd.copyTag().contains("MtCharge")) {
            return;
        }
        int max = ENERGY_MAP.getOrDefault(stackId(stack), 0);
        if (max > 0) {
            setCharge(stack, max);
        }
    }
}
