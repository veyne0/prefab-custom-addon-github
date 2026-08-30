package com.prefab.addon.structure;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.StairsShape;

/**
 * 绕 Y 轴 90° 旋转 BlockState 的工具.
 * 配合 AsyncBuildManager 使用, 让玩家旋转建筑 (houseFacing) 时
 * 方块的 facing / axis / shape 等属性跟着转, 不只是位置转.
 *
 * <p>旋转方向: 90° CCW 俯视 (跟 MC 标准的 N→W 一致).
 * 跟位置旋转公式 (x,z)→(z,-x) 保持一致, 预览跟实际放置匹配.</p>
 *
 * <p>覆盖的属性 (大部分 1.21+ 方块已够用):</p>
 * <ul>
 *   <li>HORIZONTAL_FACING — 栅栏、门、按钮、压力板、告示牌、桶等</li>
 *   <li>FACING — 楼梯、漏斗、活塞、活塞臂、熔炉、箱子、唱片机、酿造台、发射器、投掷器、刷怪笼等</li>
 *   <li>AXIS — 圆木、去皮木、方块茎、活塞、铁轨等</li>
 *   <li>NORTH/EAST/SOUTH/WEST — 栅栏/墙的 4 个连接属性</li>
 *   <li>STAIRS_SHAPE — 楼梯的 inner/outer left/right</li>
 *   <li>ROTATION — 告示牌 (0-15)</li>
 * </ul>
 *
 * <p><b>未处理 (TODO):</b> 床 (head/foot 联动), 双箱子 (BELL_ATTACHMENT 简化),
 * 头颅/旗帜 (16 个 rotation 跟告示牌算法不同), 信标、附魔台 (不需要转).</p>
 */
public final class BlockStateRotator {

    private BlockStateRotator() {}

    /**
     * 绕 Y 轴旋转 BlockState steps 次 (0..3).
     * steps=0 不转, 1=90°, 2=180°, 3=270°.
     * 负数自动规范化到 0..3.
     */
    public static BlockState rotateY(BlockState state, int steps) {
        if (state == null || steps == 0) return state;
        steps = ((steps % 4) + 4) % 4;
        if (steps == 0) return state;
        BlockState result = state;
        for (int i = 0; i < steps; i++) {
            result = rotateOnce(result);
        }
        return result;
    }

    private static BlockState rotateOnce(BlockState state) {
        // 1. HORIZONTAL_FACING (栅栏、门、按钮、压力板、告示牌、桶等)
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction d = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, rotateHorizontal(d));
        }

        // 2. FACING (楼梯、漏斗、活塞、活塞臂、熔炉、箱子、唱片机、酿造台、发射器、投掷器、刷怪笼等)
        if (state.hasProperty(BlockStateProperties.FACING)) {
            Direction d = state.getValue(BlockStateProperties.FACING);
            state = state.setValue(BlockStateProperties.FACING, rotateFacing(d));
        }

        // 3. AXIS (圆木、去皮木、方块茎、活塞、铁轨等)
        if (state.hasProperty(BlockStateProperties.AXIS)) {
            Direction.Axis a = state.getValue(BlockStateProperties.AXIS);
            state = state.setValue(BlockStateProperties.AXIS, rotateAxis(a));
        }

        // 4. 栅栏/墙/玻璃板 的 4 个连接属性 (NORTH/EAST/SOUTH/WEST)
        //    90° CCW 位置公式 (x,z)→(z,-x) 下, 一个原本连 N 方向的栅栏, 旋转后
        //    它的"连接臂"应该指向 W 方向. 也就是:
        //      N→W, E→N, S→E, W→S (跟 HORIZONTAL_FACING 同方向)
        //    之前 v2.0.0 的版本这里写反了 (新 N = 老 W), 导致 90°/270° 旋转后
        //    玻璃板/石墙/栅栏的连接方向错乱 (用户报的问题). 修成下面 4 行:
        if (state.hasProperty(BlockStateProperties.NORTH)) {
            boolean n = state.getValue(BlockStateProperties.NORTH);
            boolean e = state.getValue(BlockStateProperties.EAST);
            boolean s = state.getValue(BlockStateProperties.SOUTH);
            boolean w = state.getValue(BlockStateProperties.WEST);
            state = state.setValue(BlockStateProperties.NORTH, e);  // 新 N = 老 E
            state = state.setValue(BlockStateProperties.EAST,  s);  // 新 E = 老 S
            state = state.setValue(BlockStateProperties.SOUTH, w);  // 新 S = 老 W
            state = state.setValue(BlockStateProperties.WEST,  n);  // 新 W = 老 N
        }

        // 5. 楼梯 STAIRS_SHAPE
        if (state.hasProperty(BlockStateProperties.STAIRS_SHAPE)) {
            StairsShape s = state.getValue(BlockStateProperties.STAIRS_SHAPE);
            state = state.setValue(BlockStateProperties.STAIRS_SHAPE, rotateStairsShape(s));
        }

        // 6. 告示牌 ROTATION (0-15)
        //    注意: BlockStateProperties 在 1.21.1 没有公开 ROTATION 字段,
        //    告示牌 / 头颅 / 旗帜 的 rotation 是 IntegerProperty, 通过名字查找.
        net.minecraft.world.level.block.state.properties.Property<?> rotProp =
            state.getBlock().getStateDefinition().getProperty("rotation");
        if (rotProp instanceof net.minecraft.world.level.block.state.properties.IntegerProperty ip
            && ip.getPossibleValues().contains(0)) {
            try {
                int r = state.getValue(ip);
                state = state.setValue(ip, (r + 4) % 16);
            } catch (Throwable ignored) {}
        }

        return state;
    }

    /**
     * 旋转水平方向 (NORTH/EAST/SOUTH/WEST).
     * 90° CCW: N→W, E→N, S→E, W→S.
     */
    private static Direction rotateHorizontal(Direction d) {
        return switch (d) {
            case NORTH -> Direction.WEST;
            case EAST  -> Direction.NORTH;
            case SOUTH -> Direction.EAST;
            case WEST  -> Direction.SOUTH;
            default    -> d;  // UP/DOWN 不变
        };
    }

    /**
     * 旋转 FACING (含 UP/DOWN 的 6 个方向).
     * 水平方向同 rotateHorizontal, UP/DOWN 保持.
     */
    private static Direction rotateFacing(Direction d) {
        return switch (d) {
            case NORTH -> Direction.WEST;
            case EAST  -> Direction.NORTH;
            case SOUTH -> Direction.EAST;
            case WEST  -> Direction.SOUTH;
            default    -> d;  // UP/DOWN 不变
        };
    }

    /**
     * 旋转 AXIS (X/Y/Z).
     * X↔Z, Y 不变.
     */
    private static Direction.Axis rotateAxis(Direction.Axis a) {
        return switch (a) {
            case X -> Direction.Axis.Z;
            case Z -> Direction.Axis.X;
            default -> a;  // Y 不变
        };
    }

    /**
     * 旋转楼梯 shape.
     * straight 不变, inner/outer 的 left/right 跟着 facing 互换.
     * 90° CCW 旋转: inner_left↔inner_right, outer_left↔outer_right.
     */
    private static StairsShape rotateStairsShape(StairsShape s) {
        return switch (s) {
            case STRAIGHT    -> StairsShape.STRAIGHT;
            case INNER_LEFT  -> StairsShape.INNER_RIGHT;
            case INNER_RIGHT -> StairsShape.INNER_LEFT;
            case OUTER_LEFT  -> StairsShape.OUTER_RIGHT;
            case OUTER_RIGHT -> StairsShape.OUTER_LEFT;
        };
    }
}
