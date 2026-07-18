package com.prefab.addon.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * 模组配置：目前只放"拓展包下载服务器地址"。
 * 编辑 .minecraft/config/prefab_custom_addon-common.toml 修改。
 */
public class AddonConfig {
    public static final ModConfigSpec COMMON_SPEC;
    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> DOWNLOAD_SERVER_URL;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> TRUSTED_SERVERS;
    /** 是否启用 自定义蓝图 配方 (默认 true). 设 false 后配方被 NeoForge 条件系统过滤掉, 玩家无法合成. */
    public static final ModConfigSpec.BooleanValue ENABLE_CUSTOM_BLUEPRINT_RECIPE;

    static {
        BUILDER.comment("Prefab Custom Addon common config")
                .push("download");

        DOWNLOAD_SERVER_URL = BUILDER
                .comment("拓展包下载服务器地址 (例如 http://your.server:9999)")
                .define("serverUrl", "http://121.89.89.67:9999");

        // 暂保留 defineList 的旧版 API, 等 NeoForge 出新 API 再换
        @SuppressWarnings("deprecation")
        ModConfigSpec.ConfigValue<List<? extends String>> tmp = BUILDER
                .defineList("trustedServers",
                        List.of("http://121.89.89.67:9999"),
                        o -> o instanceof String);
        TRUSTED_SERVERS = tmp;

        BUILDER.pop();

        // 单独分组: 玩法
        BUILDER.comment("Gameplay settings")
                .push("gameplay");

        ENABLE_CUSTOM_BLUEPRINT_RECIPE = BUILDER
                .comment("是否启用 自定义蓝图 配方 (true=可以合成, false=无法合成, 蓝图只能通过指令/创造栏获得)")
                .define("enableCustomBlueprintRecipe", true);

        BUILDER.pop();

        COMMON_SPEC = BUILDER.build();
    }

    /** 当前下载服务器地址 */
    public static String getServerUrl() {
        return DOWNLOAD_SERVER_URL.get();
    }

    public static java.util.List<String> getTrustedServers() {
        java.util.List<String> list = new java.util.ArrayList<>();
        for (Object o : TRUSTED_SERVERS.get()) {
            if (o != null) list.add(o.toString());
        }
        return list;
    }

    /** 是否启用 自定义蓝图 配方. 配方 JSON 用 neoforge:conditions + 自定义 condition 检查这个值. */
    public static boolean isCustomBlueprintRecipeEnabled() {
        return ENABLE_CUSTOM_BLUEPRINT_RECIPE.get();
    }
}
