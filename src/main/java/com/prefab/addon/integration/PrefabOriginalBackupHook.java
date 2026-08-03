package com.prefab.addon.integration;

import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 历史保留类: 原计划用于"玩家按 O 键主动触发 prefab 原版建筑完整备份",
 * 已被自定义蓝图 (Custom Blueprint) 的全自动云端备份流程替代 — 见
 * {@link com.prefab.addon.cloud.CloudBuildingManager} + AsyncBuildManager.onCompleted 钩子.
 * 玩家不再需要手动按 O 键, 也不再需要看任何进游戏提示.
 * <p>
 * 类本身保留是为了避免破坏引用了 markBackupSuccess / consumeLastBackupSuccess
 * 的旧代码路径, 内部方法现在都退化为 no-op.
 * <p>
 * 注意: 移除了原本的 {@code @EventBusSubscriber} 注解 — 因为类里已经没有
 * {@code @SubscribeEvent} 方法, 留着会触发
 * "has no @SubscribeEvent methods, but register was called anyway" 启动错误.
 */
public class PrefabOriginalBackupHook {

    private static final AtomicBoolean lastBackupSuccess = new AtomicBoolean(false);

    private PrefabOriginalBackupHook() {}

    @Deprecated  // 退化为 no-op
    public static void markBackupSuccess() {
        lastBackupSuccess.set(true);
    }

    @Deprecated  // 退化为 no-op
    public static boolean consumeLastBackupSuccess() {
        return lastBackupSuccess.getAndSet(false);
    }
}
