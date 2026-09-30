package com.prefab.addon.config;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.crafting.conditions.ICondition;
import net.minecraftforge.common.crafting.conditions.IConditionSerializer;

/**
 * 自定义 Forge 数据加载条件: 检查 AddonConfig 中的 enableCustomBlueprintRecipe.
 *
 * <p>用法: 在 recipe JSON 里加上 {@code "conditions": [{"type": "prefab_custom_addon:custom_blueprint_recipe"}]}.
 * 当配置为 false 时, 整个 recipe 文件被 Forge 跳过, 玩家无法合成自定义蓝图.</p>
 *
 * <p>Forge 1.20.1: Serializer 是顶层接口 {@link IConditionSerializer},
 * 通过 {@code CraftingHelper.register(SERIALIZER)} 注册 (见 PrefabCustomAddon 构造器).</p>
 */
public record CustomBlueprintRecipeCondition() implements ICondition {

    /** 该 condition 的序列化器. 注册后配方 JSON 里
     *  "conditions": [{"type": "prefab_custom_addon:custom_blueprint_recipe"}] 就会走这里解析. */
    public static final IConditionSerializer<CustomBlueprintRecipeCondition> SERIALIZER =
        new IConditionSerializer<>() {
            @Override
            public ResourceLocation getID() {
                return new ResourceLocation("prefab_custom_addon", "custom_blueprint_recipe");
            }

            @Override
            public CustomBlueprintRecipeCondition read(JsonObject json) {
                return new CustomBlueprintRecipeCondition();
            }

            @Override
            public void write(JsonObject json, CustomBlueprintRecipeCondition value) {
                // 无额外字段
            }
        };

    @Override
    public ResourceLocation getID() {
        return SERIALIZER.getID();
    }

    @Override
    public boolean test(ICondition.IContext context) {
        // 直接读 AddonConfig: 该值从 .minecraft/config/prefab_custom_addon-common.toml 读
        // 加载顺序: CommonSetup → RecipeManager.onDataPackLoad → 读 config
        // 因为 ICondition.test 在每次 reload/load recipe 时调用, 总会拿到最新值
        return AddonConfig.isCustomBlueprintRecipeEnabled();
    }
}
