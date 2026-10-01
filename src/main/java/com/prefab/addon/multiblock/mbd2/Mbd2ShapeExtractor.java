package com.prefab.addon.multiblock.mbd2;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.multiblock.MultiblockShapeData;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * MBD2 (Multiblocked2) 多方块形状提取器: 纯反射, 不编译期依赖 MBD2.
 *
 * <p>MBD2 的自定义多方块由游戏内编辑器记录成 {@code MultiblockShapeInfo}
 * (LDLib2 的 {@code BlockInfo[][][]} 显式三维布局), 挂在
 * {@code MultiblockMachineDefinition.shapeInfoFactory} 上. 这里反射枚举
 * {@code MBDRegistries.MACHINE_DEFINITIONS}, 过滤出多方块定义, 取第一个 shapeInfo
 * 转成 {@link MultiblockShapeData}, 复用现有预览/建造/材料链路.</p>
 *
 * <p>MBD2 没装时 {@link Mbd2MultiblockCatalog#isMbd2Loaded()} 为 false, 不会走到这里;
 * 即使反射链任何一环失败也只跳过该结构, 不影响其他 source.</p>
 */
public final class Mbd2ShapeExtractor {
    private static final String CLS_REGISTRIES = "com.lowdragmc.mbd2.api.registry.MBDRegistries";
    private static final String CLS_MULTIBLOCK_DEF = "com.lowdragmc.mbd2.common.machine.definition.MultiblockMachineDefinition";
    private static final String CLS_CONTROLLER_BLOCK_INFO = "com.lowdragmc.mbd2.utils.ControllerBlockInfo";

    private Mbd2ShapeExtractor() {
    }

    public static Map<String, MultiblockShapeData> extractAll() {
        LinkedHashMap<String, MultiblockShapeData> out = new LinkedHashMap<String, MultiblockShapeData>();
        try {
            Class<?> registries = Class.forName(CLS_REGISTRIES);
            Field field = registries.getField("MACHINE_DEFINITIONS");
            Object machineDefs = field.get(null);
            Class<?> mbDefClass = Class.forName(CLS_MULTIBLOCK_DEF);
            Object valuesObj = machineDefs.getClass().getMethod("values").invoke(machineDefs);
            if (!(valuesObj instanceof Iterable)) {
                return out;
            }
            int total = 0;
            for (Object def : (Iterable<?>) valuesObj) {
                if (def == null || !mbDefClass.isInstance(def)) continue;
                ++total;
                String id = definitionId(def);
                if (id == null) continue;
                try {
                    MultiblockShapeData data = Mbd2ShapeExtractor.extractOne(id, def, mbDefClass);
                    if (data == null) continue;
                    out.put(id, data);
                }
                catch (Throwable t) {
                    PrefabCustomAddon.LOGGER.warn("[MBD2-MB] 提取失败, 跳过: {} ({})", (Object)id, (Object)t.toString());
                }
            }
            PrefabCustomAddon.LOGGER.info("[MBD2-MB] 提取完成: 多方块 {} 个 (多方块定义总数 {})", (Object)out.size(), (Object)total);
        }
        catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[MBD2-MB] MBD2 注册表反射失败, 目录为空", t);
        }
        return out;
    }

    private static String definitionId(Object def) {
        try {
            Object rl = def.getClass().getMethod("id").invoke(def);
            return rl == null ? null : rl.toString();
        }
        catch (Throwable t) {
            return null;
        }
    }

    private static MultiblockShapeData extractOne(String id, Object def, Class<?> mbDefClass) throws Exception {
        // shapeInfoFactory() 是 Lombok fluent getter, 返回 Function<Definition, MultiblockShapeInfo[]>
        Object factory = mbDefClass.getMethod("shapeInfoFactory").invoke(def);
        if (factory == null) {
            return null;
        }
        Method applyMethod = Function.class.getMethod("apply", Object.class);
        Object shapeInfosObj = applyMethod.invoke(factory, def);
        if (!(shapeInfosObj instanceof Object[]) || ((Object[])shapeInfosObj).length == 0) {
            return null;
        }
        Object shapeInfo = ((Object[])shapeInfosObj)[0];
        if (shapeInfo == null) {
            return null;
        }
        Object blocks3d = shapeInfo.getClass().getMethod("getBlocks").invoke(shapeInfo);
        if (!(blocks3d instanceof Object[][][])) {
            return null;
        }
        Object[][][] blocks = (Object[][][])blocks3d;

        // 找 controller 格 (ControllerBlockInfo) 作原点/建造锚点; 找不到则退回 (0,0,0)
        Class<?> controllerInfoClass = Class.forName(CLS_CONTROLLER_BLOCK_INFO);
        BlockPos controllerPos = null;
        outer:
        for (int x = 0; x < blocks.length; ++x) {
            if (blocks[x] == null) continue;
            for (int y = 0; y < blocks[x].length; ++y) {
                if (blocks[x][y] == null) continue;
                for (int z = 0; z < blocks[x][y].length; ++z) {
                    Object cell = blocks[x][y][z];
                    if (cell == null || !controllerInfoClass.isInstance(cell)) continue;
                    controllerPos = new BlockPos(x, y, z);
                    break outer;
                }
            }
        }
        if (controllerPos == null) {
            controllerPos = BlockPos.ZERO;
        }

        Method getBlockState = null;
        LinkedHashMap<BlockPos, BlockState> localBlocks = new LinkedHashMap<BlockPos, BlockState>();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int x = 0; x < blocks.length; ++x) {
            if (blocks[x] == null) continue;
            for (int y = 0; y < blocks[x].length; ++y) {
                if (blocks[x][y] == null) continue;
                for (int z = 0; z < blocks[x][y].length; ++z) {
                    BlockState state;
                    Object cell = blocks[x][y][z];
                    if (cell == null) continue;
                    if (getBlockState == null) {
                        getBlockState = cell.getClass().getMethod("getBlockState");
                    }
                    BlockPos local = new BlockPos(x, y, z).subtract(controllerPos);
                    minX = Math.min(minX, local.getX());
                    maxX = Math.max(maxX, local.getX());
                    minY = Math.min(minY, local.getY());
                    maxY = Math.max(maxY, local.getY());
                    minZ = Math.min(minZ, local.getZ());
                    maxZ = Math.max(maxZ, local.getZ());
                    if ((state = (BlockState)getBlockState.invoke(cell)) == null || state.isAir()) continue;
                    localBlocks.put(local, state);
                }
            }
        }
        if (localBlocks.isEmpty() || minX == Integer.MAX_VALUE) {
            return null;
        }
        ResourceLocation rl = ResourceLocation.tryParse((String)id);
        String langKey = "block." + (rl != null ? rl.getNamespace() : "mbd2") + "." + (rl != null ? rl.getPath() : id);
        return new MultiblockShapeData(id, langKey, localBlocks, new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ), MultiblockShapeData.SOURCE_MBD2);
    }
}
