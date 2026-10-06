package com.prefab.addon.work;

/**
 * 全服免材料模式 (由 OP 指令 /prefabaddon freemode on|off|status 控制).
 *
 * <p>开启后, 所有玩家在生成/建造模式下不再需要提交材料, 权限要求 OP (等级 2).</p>
 *
 * <ul>
 *   <li>{@link #serverEnabled}: 服务端权威值, 指令切换, 玩家登录时随 FreeBuildSyncPayload 同步.
 *       不持久化 — 服务器重启后默认关闭 (保守行为, 避免 OP 忘记关导致材料体系失效).</li>
 *   <li>{@link #clientEnabled}: 客户端镜像, 收到 FreeBuildSyncPayload 后更新.
 *       客户端各材料门控点把"开启"视同"创造模式" (与 isCreative() 豁免并联).</li>
 * </ul>
 */
public class FreeBuildMode {
    private static volatile boolean serverEnabled;
    private static volatile boolean clientEnabled;

    private FreeBuildMode() {}

    /** 服务端权威开关 (仅指令写入) */
    public static boolean isServerEnabled() { return serverEnabled; }
    public static void setServerEnabled(boolean v) { serverEnabled = v; }

    /** 客户端判定: 免材料是否生效 (来自服务端同步) */
    public static boolean isEnabled() { return clientEnabled; }
    public static void setClientEnabled(boolean v) { clientEnabled = v; }
}
