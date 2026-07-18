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
        // 直接读 AddonConfig: 该值从 .minecraft/config/prefab_custom_addon-common.toml 读
        // 加载顺序: CommonSetup → RecipeManager.onDataPackLoad → 读 config
        // 因为 ICondition.test 在每次 reload/load recipe 时调用, 总会拿到最新值
        return AddonConfig.isCustomBlueprintRecipeEnabled();
    }

    @Override
    public MapCodec<? extends ICondition> codec() {
        return CODEC;
    }
}
