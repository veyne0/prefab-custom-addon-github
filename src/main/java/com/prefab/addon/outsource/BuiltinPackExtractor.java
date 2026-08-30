package com.prefab.addon.outsource;

import com.prefab.addon.PrefabCustomAddon;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 内置 ZIP 包写入器 —— 把 mod jar 里的 投影.zip 在启动时解到
 * {@code <gameDir>/prefab-outsource/投影.zip},然后由 {@link OutsourceBuildingLoader} 扫到。
 *
 * <p>为什么这么做: 8 个 Item 已经硬编码了 {@code 投影_<子文件夹名>} 这种 buildingId,
 * 只有 ZIP 文件名是 "投影.zip" 时,扫描器才会生成匹配的 ID。所以这个工具负责:</p>
 * <ol>
 *   <li>从 mod jar 里读内置资源 (用 {@link Class#getResourceAsStream},dev / 生产环境通用)</li>
 *   <li>写到 prefab-outsource/投影.zip (永远覆盖 —— 模组更新会带上新的建筑)</li>
 *   <li>返回是否成功写入,让 {@link com.prefab.addon.PrefabCustomAddon#commonSetup} 决定是否触发 scan</li>
 * </ol>
 *
 * <p>玩家在 prefab-outsource/ 放自己的其他 ZIP (例如 {@code 我的村庄.zip}) 不会受影响,
 * 扫描器只看后缀 .zip,内置包只是其中之一。</p>
 */
public final class BuiltinPackExtractor {

    /**
     * 资源路径(jar 内 / dev 环境 build/resources/main 下都用这个读)。
     * 注意: 文件名用英文以避免不同编码/打包器对中文资源名的差异处理。
     */
    public static final String BUILTIN_PACK_RESOURCE = "/data/prefab_custom_addon/builtin/outsource_pack.zip";

    /**
     * 写到 prefab-outsource/ 后改回用户认得出的中文名,
     * 扫描器会按这个 zipBaseName 生成 "投影_&lt;子文件夹&gt;" 的 buildingId,跟 8 个 Item 对应。
     */
    public static final String TARGET_FILE_NAME = "投影.zip";

    public static final String OUTSOURCE_DIR_NAME = "prefab-outsource";

    private BuiltinPackExtractor() {}

    /**
     * 把内置 ZIP 写入 {@code <gameDir>/prefab-outsource/投影.zip}。
     * 永远覆盖 —— 模组更新会替换旧包,玩家想保留自己的版本可以改名 (不要叫 投影.zip)。
     *
     * @param gameDir 游戏根目录(整合服 / 专用服 / 客户端都一样,就是 .minecraft 那一级)
     * @return true = 写入成功, false = 资源缺失 / IO 失败
     */
    public static boolean extract(Path gameDir) {
        if (gameDir == null) {
            PrefabCustomAddon.LOGGER.warn("[BUILTIN] gameDir 为空, 跳过内置包写入");
            return false;
        }
        Path target;
        try {
            Path dir = gameDir.resolve(OUTSOURCE_DIR_NAME);
            Files.createDirectories(dir);
            target = dir.resolve(TARGET_FILE_NAME);
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("[BUILTIN] 创建 prefab-outsource/ 目录失败: {}", e.getMessage(), e);
            return false;
        }

        try (InputStream is = BuiltinPackExtractor.class.getResourceAsStream(BUILTIN_PACK_RESOURCE)) {
            if (is == null) {
                PrefabCustomAddon.LOGGER.warn("[BUILTIN] 内置资源不存在: {} (打包时漏放了?)", BUILTIN_PACK_RESOURCE);
                return false;
            }
            long bytes = Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
            PrefabCustomAddon.LOGGER.info("[BUILTIN] 已写入 {} ({} bytes,REPLACE_EXISTING)", target, bytes);
            return true;
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("[BUILTIN] 写入失败: target={}, err={}", target, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 把内置 ZIP 当作首次安装只写一次 —— 如果目标已存在就不动。
     * (备用,目前默认用 {@link #extract(Path)} 的"永远覆盖"模式)
     */
    public static boolean extractIfMissing(Path gameDir) {
        if (gameDir == null) return false;
        Path target = gameDir.resolve(OUTSOURCE_DIR_NAME).resolve(TARGET_FILE_NAME);
        if (Files.exists(target)) {
            PrefabCustomAddon.LOGGER.info("[BUILTIN] 已存在, 跳过首次写入: {}", target);
            return false;
        }
        return extract(gameDir);
    }
}
