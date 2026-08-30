package com.prefab.addon.config;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.neoforged.neoforge.common.conditions.ICondition;

/**
 * 自定义 NeoForge 数据加载条件: 检查 AddonConfig 中的 enableCustomBlueprintRecipe.
 *
 * <p>用法: 在 recipe JSON 里加上 {@code "neoforge:conditions": [{"type": "prefab_custom_addon:custom_blueprint_recipe"}]}.
 * 当配置为 false 时, 整个 recipe 文件被 NeoForge 跳过, 玩家无法合成自定义蓝图.</p>
 *
 * <p>该 Codec 通过 {@link #CODEC} 静态注册到 {@code NeoForgeRegistries.CONDITION_SERIALIZERS},
 * 命名空间 "prefab_custom_addon", ID "custom_blueprint_recipe".</p>
 */
public record CustomBlueprintRecipeCondition() implements ICondition {

    /** 该 condition 的 MapCodec. 注册到 NeoForgeRegistries.CONDITION_SERIALIZERS 后,
     *  配方 JSON 里 "type": "prefab_custom_addon:custom_blueprint_recipe" 就会用这个 codec 解析. */
    public static final MapCodec<CustomBlueprintRecipeCondition> CODEC =
        MapCodec.unit(CustomBlueprintRecipeCondition::new);

    @Override
    public boolean test(ICondition.IContext context) {
        // 被 evaluate 时, test() 一次只打一行. 用一个简单 flag 防止多个 recipe 同时触发时刷屏.
        // 注意: 现在只剩 1 个 recipe (custom_blueprint.json) 挂这个 condition, 8 个外包建筑
        // 蓝图 (outsource_blueprint_*.json) 已经移除 condition, 不受开关影响.
        boolean enabled = AddonConfig.isCustomBlueprintRecipeEnabled();
        if (!enabled) {
            // 只在第一次发现 false 时打 WARN, 避免每个 recipe 文件都重复提示
            if (WARN_ONCE.compareAndSet(false, true)) {
                com.prefab.addon.PrefabCustomAddon.LOGGER.warn(
                    "[DEBUG-RECIPE] custom_blueprint_recipe 条件返回 false → "
                    + "custom_blueprint 配方已被 NeoForge 跳过加载. "
                    + "解决方法: 编辑 config/prefab_custom_addon-common.toml 把 enableCustomBlueprintRecipe 改为 true, "
                    + "或用 /prefabaddon recipes on 切换 (mod 会自动写回 toml).");
            }
        } else if (INFO_ONCE.compareAndSet(false, true)) {
            com.prefab.addon.PrefabCustomAddon.LOGGER.info(
                "[DEBUG-RECIPE] custom_blueprint_recipe 条件返回 true → custom_blueprint 配方应已加载");
        }
        return enabled;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean WARN_ONCE = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicBoolean INFO_ONCE = new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 重置一次性 log flag, 供 /prefabaddon recipes 命令在 reload 后再触发一次. */
    public static void resetLogFlag() {
        WARN_ONCE.set(false);
        INFO_ONCE.set(false);
    }

    @Override
    public MapCodec<? extends ICondition> codec() {
        return CODEC;
    }
}
