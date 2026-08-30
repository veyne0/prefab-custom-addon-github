package com.prefab.addon.items;

import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 操作手杖状态管理 (静态, 跨 GUI 持久).
 *
 * <p>客户端 + 服务端都用同一份 (per-player 状态). 服务端是 ground truth
 * (扫描方块), 客户端缓存用于显示预览.</p>
 */
public final class OperationWandManager {

    private static final Map<UUID, OperationWandState> STATES = new HashMap<>();

    private OperationWandManager() {}

    /** 获取玩家状态. 首次访问自动创建. */
    public static OperationWandState get(Player player) {
        return get(player.getUUID());
    }

    public static OperationWandState get(UUID playerId) {
        return STATES.computeIfAbsent(playerId, OperationWandState::new);
    }

    /** 玩家是否在操作手杖模式 (持有手杖 + 状态 ready). */
    public static boolean isReady(Player player) {
        if (player == null) return false;
        return get(player).isReady();
    }

    /** 清空玩家状态 (CTRL 取消 / 建造完成 / 玩家退出). */
    public static void clear(Player player) {
        if (player == null) return;
        STATES.remove(player.getUUID());
    }

    public static void clear(UUID playerId) {
        STATES.remove(playerId);
    }

    /** 玩家退出时调用, 防止状态泄露. */
    public static void onPlayerLogout(UUID playerId) {
        STATES.remove(playerId);
    }
}
