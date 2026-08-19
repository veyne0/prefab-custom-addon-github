package com.prefab.addon.config;

/**
 * 建造动画模式 - 玩家在设置里选 1 种, 控制建造时客户端的视觉表现.
 *
 * <p>设计目标: 4 个状态全循环, 不需要单独"开/关"按钮. 默认 {@link #OFF} (不动画, 跟旧版兼容).</p>
 *
 * <h2>3 种动画对比</h2>
 * <table border="1">
 *   <tr><th>Mode</th><th>轨迹</th><th>总时长</th><th>视觉感</th></tr>
 *   <tr><td>FALL</td><td>竖直下落 (realX, realY+N, realZ) → (realX, realY, realZ)</td><td>~8 tick (0.4s)</td><td>经典"天上掉方块"</td></tr>
 *   <tr><td>RAIN</td><td>散点下落 (realX+dx, realY+12, realZ+dz) → target, dx/dz 随机</td><td>~12 tick (0.6s)</td><td>方块像雨一样下</td></tr>
 *   <tr><td>THROW</td><td>四周抛物线 (realX±10, realY+6, realZ±10) → target, 抛物线插值</td><td>~14 tick (0.7s)</td><td>方块被抛到目标位</td></tr>
 * </table>
 *
 * <p>服务端在 {@link com.prefab.addon.structure.AsyncBuildManager} 启动 task 时读取玩家偏好,
 * 通过 {@link com.prefab.addon.network.BatchBlocksPlacedPayload} 传给客户端, 客户端
 * {@link com.prefab.addon.client.BuildAnimationRenderer} 按 mode 计算每个方块的渲染位置.</p>
 *
 * <p>注意: 老的 {@code PlayerPreferences.enableBuildAnimation} 字段已删除, 改用本枚举.
 * 偏好文件兼容: 老存档里 {@code enableBuildAnimation=true} 会在 load 时一次性迁移到
 * {@code buildAnimationMode=FALL}, 保持玩家"开了下落动画"的体验不丢.</p>
 */
public enum BuildAnimationMode {
    /** 不放动画, 直接全 setBlock (老行为, 跟原版 prefab 一样快, 可能卡顿). */
    OFF,
    /** 竖直下落, 经典"方块从天上掉下来". */
    FALL,
    /** 方块雨, 起点随机偏移 + 更高的下落高度. */
    RAIN,
    /** 四周抛过来, 抛物线轨迹 (起点在建筑外圈, 终点 = 目标位置). */
    THROW;

    /** Cycle 到下一个 mode. 用在 UI 循环按钮上. */
    public BuildAnimationMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /** 从字符串解析, 失败 fallback. 主要给 preferences.json 兼容用. */
    public static BuildAnimationMode parseOrDefault(String s, BuildAnimationMode def) {
        if (s == null || s.isEmpty()) return def;
        try {
            return BuildAnimationMode.valueOf(s.toUpperCase());
        } catch (Throwable t) {
            return def;
        }
    }
}
