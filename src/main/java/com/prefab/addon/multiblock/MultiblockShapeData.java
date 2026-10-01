/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.BlockPos
 *  net.minecraft.core.registries.BuiltInRegistries
 *  net.minecraft.world.level.block.state.BlockState
 */
package com.prefab.addon.multiblock;

import com.prefab.addon.work.MaterialCalculator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

public final class MultiblockShapeData {
    public static final String SOURCE_GTM = "gtm";
    public static final String SOURCE_MEKANISM = "mekanism";
    public static final String SOURCE_MBD2 = "mbd2";
    public final String id;
    public final String langKey;
    public final Map<BlockPos, BlockState> localBlocks;
    public final BlockPos bboxMin;
    public final BlockPos bboxMax;
    public final String source;

    public MultiblockShapeData(String id, String langKey, Map<BlockPos, BlockState> localBlocks, BlockPos bboxMin, BlockPos bboxMax, String source) {
        this.id = id;
        this.langKey = langKey;
        this.localBlocks = localBlocks;
        this.bboxMin = bboxMin;
        this.bboxMax = bboxMax;
        this.source = source;
    }

    public int width() {
        return this.bboxMax.getX() - this.bboxMin.getX() + 1;
    }

    public int height() {
        return this.bboxMax.getY() - this.bboxMin.getY() + 1;
    }

    public int length() {
        return this.bboxMax.getZ() - this.bboxMin.getZ() + 1;
    }

    public String sessionKey() {
        return "multiblock:" + this.id;
    }

    public MaterialCalculator.MaterialList toMaterialList() {
        MaterialCalculator.MaterialList list = new MaterialCalculator.MaterialList();
        for (BlockState state : this.localBlocks.values()) {
            ++list.totalBlocks;
            String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (MaterialCalculator.isInfrastructureBlock(blockId)) {
                ++list.filteredInfrastructure;
                list.filteredTypes.merge(blockId, 1, Integer::sum);
                continue;
            }
            list.required.merge(blockId, 1, Integer::sum);
            ++list.nonAirBlocks;
        }
        list.uniqueBlockTypes = list.required.size();
        return list;
    }

    public Map<BlockPos, BlockState> copyBlocks() {
        return new LinkedHashMap<BlockPos, BlockState>(this.localBlocks);
    }
}

