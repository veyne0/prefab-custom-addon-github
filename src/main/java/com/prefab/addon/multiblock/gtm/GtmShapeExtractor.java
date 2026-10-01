/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.BlockPos
 *  net.minecraft.resources.ResourceLocation
 *  net.minecraft.world.level.block.Block
 *  net.minecraft.world.level.block.state.BlockState
 */
package com.prefab.addon.multiblock.gtm;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.multiblock.MultiblockShapeData;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class GtmShapeExtractor {
    private static final String CLS_GT_REGISTRIES = "com.gregtechceu.gtceu.api.registry.GTRegistries";
    private static final String CLS_IMACHINE_BLOCK = "com.gregtechceu.gtceu.api.block.IMachineBlock";

    private GtmShapeExtractor() {
    }

    public static Map<String, MultiblockShapeData> extractAll() {
        LinkedHashMap<String, MultiblockShapeData> out = new LinkedHashMap<String, MultiblockShapeData>();
        try {
            Class<?> registries = Class.forName(CLS_GT_REGISTRIES);
            Field machinesField = registries.getField("MACHINES");
            Object machines = machinesField.get(null);
            Map<Object, Object> defs = GtmShapeExtractor.asIdMap(machines);
            PrefabCustomAddon.LOGGER.info("[GT-MB] \u5f00\u59cb\u63d0\u53d6 GTM \u591a\u65b9\u5757\u76ee\u5f55, \u673a\u5668\u603b\u6570={}", (Object)defs.size());
            for (Map.Entry<Object, Object> e : defs.entrySet()) {
                String id = e.getKey().toString();
                try {
                    MultiblockShapeData data = GtmShapeExtractor.extractOne(id, e.getValue());
                    if (data == null) continue;
                    out.put(id, data);
                }
                catch (Throwable t) {
                    PrefabCustomAddon.LOGGER.warn("[GT-MB] \u63d0\u53d6\u5931\u8d25, \u8df3\u8fc7: {} ({})", (Object)id, (Object)t.toString());
                }
            }
            PrefabCustomAddon.LOGGER.info("[GT-MB] \u63d0\u53d6\u5b8c\u6210: \u591a\u65b9\u5757 {} \u4e2a (\u673a\u5668\u603b\u6570 {})", (Object)out.size(), (Object)defs.size());
        }
        catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[GT-MB] GTM \u6ce8\u518c\u8868\u53cd\u5c04\u5931\u8d25, \u76ee\u5f55\u4e3a\u7a7a", t);
        }
        return out;
    }

    private static Map<Object, Object> asIdMap(Object machines) throws Exception {
        LinkedHashMap<Object, Object> map = new LinkedHashMap<Object, Object>();
        try {
            Method registry = machines.getClass().getMethod("registry", new Class[0]);
            map.putAll((Map)registry.invoke(machines, new Object[0]));
            return map;
        }
        catch (NoSuchMethodException registry) {
            if (machines instanceof Iterable) {
                Iterable it = (Iterable)machines;
                for (Object def : it) {
                    Method getId = def.getClass().getMethod("getId", new Class[0]);
                    map.put(getId.invoke(def, new Object[0]), def);
                }
            }
            return map;
        }
    }

    private static MultiblockShapeData extractOne(String id, Object def) throws Exception {
        Method getMatchingShapes;
        try {
            getMatchingShapes = def.getClass().getMethod("getMatchingShapes", new Class[0]);
        }
        catch (NoSuchMethodException notMultiblock) {
            return null;
        }
        List shapes = (List)getMatchingShapes.invoke(def, new Object[0]);
        if (shapes == null || shapes.isEmpty()) {
            return null;
        }
        Object shapeInfo = shapes.get(0);
        Object blocks3d = shapeInfo.getClass().getMethod("getBlocks", new Class[0]).invoke(shapeInfo, new Object[0]);
        if (!(blocks3d instanceof Object[][][])) {
            return null;
        }
        Object[][][] blocks = (Object[][][])blocks3d;
        Class<?> imachineBlock = Class.forName(CLS_IMACHINE_BLOCK);
        Method getDefinition = imachineBlock.getMethod("getDefinition", new Class[0]);
        Method getBlockState = null;
        BlockPos controllerPos = null;
        for (int x = 0; x < blocks.length && controllerPos == null; ++x) {
            if (blocks[x] == null) continue;
            block3: for (int y = 0; y < blocks[x].length && controllerPos == null; ++y) {
                if (blocks[x][y] == null) continue;
                for (int z = 0; z < blocks[x][y].length; ++z) {
                    Block block;
                    BlockState state;
                    Object cell = blocks[x][y][z];
                    if (cell == null) continue;
                    if (getBlockState == null) {
                        getBlockState = cell.getClass().getMethod("getBlockState", new Class[0]);
                    }
                    if ((state = (BlockState)getBlockState.invoke(cell, new Object[0])) == null || !imachineBlock.isInstance(block = state.getBlock()) || getDefinition.invoke(block, new Object[0]) != def) continue;
                    controllerPos = new BlockPos(x, y, z);
                    continue block3;
                }
            }
        }
        if (controllerPos == null) {
            PrefabCustomAddon.LOGGER.warn("[GT-MB] {} \u5f62\u72b6\u91cc\u627e\u4e0d\u5230\u63a7\u5236\u5668\u683c, \u8df3\u8fc7", (Object)id);
            return null;
        }
        if (getBlockState == null) {
            return null;
        }
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
                    Object cell = blocks[x][y][z];
                    if (cell == null) continue;
                    BlockPos local = new BlockPos(x, y, z).subtract(controllerPos);
                    minX = Math.min(minX, local.getX());
                    maxX = Math.max(maxX, local.getX());
                    minY = Math.min(minY, local.getY());
                    maxY = Math.max(maxY, local.getY());
                    minZ = Math.min(minZ, local.getZ());
                    maxZ = Math.max(maxZ, local.getZ());
                    BlockState state = (BlockState)getBlockState.invoke(cell, new Object[0]);
                    if (state == null || state.isAir()) continue;
                    localBlocks.put(local, state);
                }
            }
        }
        if (localBlocks.isEmpty() || minX == Integer.MAX_VALUE) {
            return null;
        }
        ResourceLocation rl = ResourceLocation.tryParse((String)id);
        String langKey = "block." + (rl != null ? rl.getNamespace() : "gtceu") + "." + (rl != null ? rl.getPath() : id);
        return new MultiblockShapeData(id, langKey, localBlocks, new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ), "gtm");
    }
}

