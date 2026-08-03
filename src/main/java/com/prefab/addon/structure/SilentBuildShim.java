package com.prefab.addon.structure;

import com.prefab.addon.PrefabCustomAddon;

import java.lang.reflect.Method;

/**
 * 反射调用 MyMod 的 SilentBuild.runSilent, 抑制建造期间的 ItemEntity spawn.
 * <p>
 * <b>为什么需要 shim</b>: PrefabCustomAddon 不硬依赖 MyMod, 运行时通过反射
 * 查找 {@code com.example.mymod.warehouse.cloud.SilentBuild.runSilent(Runnable)}.
 * 找到 → 调用 (进入 silent 模式, mixin 拦截 ItemEntity). 找不到 → 直接跑
 * (没有 silent 模式, 容易产生掉落物, 但不影响核心功能).
 * <p>
 * <b>为什么放在 build 期间</b>: 100% 完成瞬间产生掉落物的根因, 是 modded 容器
 * (SophisticatedStorage / Mekanism / 告示牌/梯子) 的 onRemove 重写里
 * 调 {@code level.addFreshEntity(itemEntity)} 走通了 spawn 路径, 完全绕开
 * flags=98 的 SUPPRESS 抑制. 终极兜底: 在 build 全程把 {@code Level.addFreshEntity}
 * 用 mixin 拦住, 凡是 ItemEntity 一律拒绝. 这里 shim 负责打开/关闭 mixin 开关.
 * <p>
 * <b>软依赖</b>: {@link com.prefab.addon.structure.AsyncBuildManager} 把每个 batch
 * 的 setBlock 循环用 {@link #runSilent(Runnable)} 包起来. MyMod 没装时
 * (例如独立使用本附属) 也照常建造, 只不过没有 silent 兜底.
 */
public final class SilentBuildShim {
    private static final Method RUN_SILENT = resolveRunSilent();
    private static boolean warned = false;

    private SilentBuildShim() {}

    private static Method resolveRunSilent() {
        try {
            Class<?> cls = Class.forName("com.example.mymod.warehouse.cloud.SilentBuild");
            Method m = cls.getMethod("runSilent", Runnable.class);
            PrefabCustomAddon.LOGGER.info("[SilentBuildShim] resolved MyMod SilentBuild.runSilent");
            return m;
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[SilentBuildShim] MyMod SilentBuild not available: {} (silent build disabled, may produce drops)",
                t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    /**
     * 反射调用 MyMod SilentBuild.runSilent; 找不到则直接执行 (no silent).
     */
    public static void runSilent(Runnable body) {
        if (RUN_SILENT != null) {
            try {
                RUN_SILENT.invoke(null, (Object) body);
                return;
            } catch (Throwable t) {
                if (!warned) {
                    PrefabCustomAddon.LOGGER.warn("[SilentBuildShim] invoke failed (will fall back to direct run): {}",
                        t.getClass().getSimpleName() + ": " + t.getMessage());
                    warned = true;
                }
            }
        }
        body.run();
    }

    /**
     * 探测 silent build 模式是否可用 (主要用于诊断 / 调试).
     */
    public static boolean isAvailable() {
        return RUN_SILENT != null;
    }
}
