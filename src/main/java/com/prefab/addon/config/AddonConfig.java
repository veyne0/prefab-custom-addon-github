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
}
