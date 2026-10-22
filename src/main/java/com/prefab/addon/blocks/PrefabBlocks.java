package com.prefab.addon.blocks;

import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 方块注册表 (1.20.1).
 *
 * <p>从 1.21.1 的 PrefabBlockEntities 移植; 1.20.1 暂无方块实体需求, 只注册 Block.</p>
 */
public class PrefabBlocks {

    public static final DeferredRegister<Block> BLOCKS =
        DeferredRegister.create(ForgeRegistries.BLOCKS, PrefabCustomAddon.MOD_ID);

    /**
     * 缺失方块 ("?" 方块): 建筑 NBT 里出现本服未注册的 mod 方块 (缺依赖) 时,
     * 预览与建造都用它占位, 让玩家一眼看出哪里缺了东西. 可正常挖掘清除.
     * 物品形态 (missing_block) 在主类 ITEMS 里注册, 创造栏可拿.
     */
    public static final RegistryObject<Block> MISSING_BLOCK =
        BLOCKS.register("missing_block", () -> new Block(
            Block.Properties.of().strength(0.3f).mapColor(MapColor.COLOR_MAGENTA)));
}
