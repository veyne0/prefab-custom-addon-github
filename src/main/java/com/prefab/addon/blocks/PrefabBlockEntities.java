package com.prefab.addon.blocks;

import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 方块 + 方块实体注册表.
 *
 * <p>注: 迷你建筑方块本身通过 {@link PrefabCustomAddon#MINI_BUILDING_BLOCK_ITEM}
 * 走 ITEMS 注册表 (作为 BlockItem). 这里只注册 Block + BlockEntityType.
 */
public class PrefabBlockEntities {

    public static final DeferredRegister.Blocks BLOCKS =
        DeferredRegister.createBlocks(PrefabCustomAddon.MOD_ID);

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, PrefabCustomAddon.MOD_ID);

    public static final DeferredBlock<MiniBuildingBlock> MINI_BUILDING_BLOCK =
        BLOCKS.register("mini_building", () -> new MiniBuildingBlock(
            Block.Properties.of().strength(0.5f).noOcclusion()));

    public static final net.neoforged.neoforge.registries.DeferredHolder<
        BlockEntityType<?>, BlockEntityType<MiniBuildingBlockEntity>> MINI_BUILDING_BE =
        BLOCK_ENTITIES.register("mini_building",
            () -> BlockEntityType.Builder.of(
                MiniBuildingBlockEntity::new, MINI_BUILDING_BLOCK.get())
                .build(null));
}
