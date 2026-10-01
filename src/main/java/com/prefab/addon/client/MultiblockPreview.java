/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.prefab.structures.base.BuildBlock
 *  com.prefab.structures.base.BuildClear
 *  com.prefab.structures.base.BuildProperty
 *  com.prefab.structures.base.BuildShape
 *  com.prefab.structures.base.PositionOffset
 *  com.prefab.structures.base.Structure
 *  com.prefab.structures.config.StructureConfiguration
 *  net.minecraft.client.Minecraft
 *  net.minecraft.core.BlockPos
 *  net.minecraft.core.Direction
 *  net.minecraft.core.registries.BuiltInRegistries
 *  net.minecraft.resources.ResourceLocation
 *  net.minecraft.world.level.block.state.BlockState
 *  net.minecraft.world.level.block.state.properties.Property
 */
package com.prefab.addon.client;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.client.gui.CustomStructureGui;
import com.prefab.addon.multiblock.MultiblockCatalog;
import com.prefab.addon.multiblock.MultiblockShapeData;
import com.prefab.addon.structure.CustomStructureBuilder;
import com.prefab.structures.base.BuildBlock;
import com.prefab.structures.base.BuildClear;
import com.prefab.structures.base.BuildProperty;
import com.prefab.structures.base.BuildShape;
import com.prefab.structures.base.PositionOffset;
import com.prefab.structures.base.Structure;
import com.prefab.structures.config.StructureConfiguration;
import com.prefab.structures.render.StructureRenderHandler;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

public final class MultiblockPreview {
    private static volatile String CURRENT_MULTIBLOCK_ID = null;

    private MultiblockPreview() {
    }

    public static String getCurrentId() {
        return CURRENT_MULTIBLOCK_ID;
    }

    public static boolean isActive() {
        return CURRENT_MULTIBLOCK_ID != null;
    }

    public static boolean start(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        MultiblockShapeData shape = MultiblockCatalog.getShape(id);
        if (shape == null) {
            PrefabCustomAddon.LOGGER.warn("[MB-PREVIEW] \u627e\u4e0d\u5230\u591a\u65b9\u5757 id={}", (Object)id);
            return false;
        }
        Structure structure = MultiblockPreview.buildStructureFromShape(shape);
        if (structure == null || structure.getBlocks() == null || structure.getBlocks().isEmpty()) {
            PrefabCustomAddon.LOGGER.warn("[MB-PREVIEW] \u8f6c\u6362\u5931\u8d25, blocks \u4e3a\u7a7a id={}", (Object)id);
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        BlockPos origin = mc.player != null ? mc.player.blockPosition().offset(0, 1, 0) : BlockPos.ZERO;
        Direction houseFacing = mc.player != null ? mc.player.getDirection() : Direction.SOUTH;
        StructureConfiguration cfg = new StructureConfiguration();
        cfg.Initialize();
        cfg.pos = origin;
        cfg.houseFacing = houseFacing;
        CustomStructureBuilder.offsetStructureBlocks(structure, cfg.pos, cfg.houseFacing);
        CustomStructureGui.setAddonPreviewStructure(structure);
        CustomStructureGui.setAddonPreviewConfig(cfg);
        CustomStructureGui.markAddonPreviewActive();
        StructureRenderHandler.setStructure(structure, cfg);
        StructureRenderHandler.setStructure(null, null);
        CURRENT_MULTIBLOCK_ID = id;
        PrefabCustomAddon.LOGGER.info("[MB-PREVIEW] \u5f00\u542f\u591a\u65b9\u5757\u9884\u89c8 id={} pos={} facing={} blocks={}", new Object[]{id, origin, houseFacing, structure.getBlocks().size()});
        mc.setScreen(null);
        return true;
    }

    public static void cancel() {
        if (CURRENT_MULTIBLOCK_ID == null && CustomStructureGui.getAddonPreviewStructure() == null) {
            return;
        }
        PrefabCustomAddon.LOGGER.info("[MB-PREVIEW] \u53d6\u6d88\u9884\u89c8 id={}", (Object)CURRENT_MULTIBLOCK_ID);
        CURRENT_MULTIBLOCK_ID = null;
        CustomStructureGui.clearAddonPreviewFlag();
        StructureRenderHandler.setStructure(null, null);
    }

    public static Structure buildStructureFromShape(MultiblockShapeData shape) {
        Structure structure = new Structure();
        structure.setName("multiblock_" + shape.id.replace(':', '_'));
        ArrayList<BuildBlock> buildBlocks = new ArrayList<BuildBlock>(shape.localBlocks.size());
        IdentityHashMap<BuildBlock, BlockPos> localPosMap = new IdentityHashMap<BuildBlock, BlockPos>();
        int added = 0;
        for (Map.Entry<BlockPos, BlockState> e : shape.localBlocks.entrySet()) {
            ResourceLocation id;
            BlockState state = e.getValue();
            if (state == null || state.isAir() || (id = BuiltInRegistries.BLOCK.getKey(state.getBlock())) == null) continue;
            BuildBlock bb = new BuildBlock();
            bb.Initialize();
            bb.setBlockDomain(id.getNamespace());
            bb.setBlockName(id.getPath());
            ArrayList<BuildProperty> propList = new ArrayList<BuildProperty>();
            for (Map.Entry pe : state.getValues().entrySet()) {
                BuildProperty bp = new BuildProperty();
                bp.setName(((Property)pe.getKey()).getName());
                bp.setValue(((Comparable)pe.getValue()).toString());
                propList.add(bp);
            }
            bb.setProperties(propList);
            BlockPos local = e.getKey();
            PositionOffset offset = Structure.getStartingPositionFromOriginalAndCurrentPosition((BlockPos)local, (BlockPos)BlockPos.ZERO);
            bb.setStartingPosition(offset);
            bb.setBlockState(state);
            bb.setBlockStateData("");
            bb.blockPos = local;
            localPosMap.put(bb, local);
            buildBlocks.add(bb);
            ++added;
        }
        structure.setBlocks(buildBlocks);
        CustomStructureBuilder.putLocalPosMap(structure, localPosMap);
        BuildClear clearSpace = new BuildClear();
        BuildShape shapeBox = clearSpace.getShape();
        if (shapeBox != null) {
            shapeBox.setWidth(shape.width());
            shapeBox.setHeight(shape.height());
            shapeBox.setLength(shape.length());
            shapeBox.setDirection(Direction.NORTH);
        }
        structure.setClearSpace(clearSpace);
        PrefabCustomAddon.LOGGER.info("[MB-PREVIEW] \u8f6c\u6362 {} \u2192 Structure: blocks={}", (Object)shape.id, (Object)added);
        return structure;
    }
}

