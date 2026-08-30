package com.prefab.addon.items;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;

/**
 * 迷你建筑方块的物品形态.
 *
 * <p>在 1.21.1 体系下:
 * <ul>
 *   <li>放方块时: BlockItem 走 BLOCK_ENTITY_DATA → BlockItem.updateCustomBlockEntityTag
 *       → MiniBuildingBlockEntity.loadWithComponents → 数据从 ItemStack 进 BE (无需
 *       在 MiniBuildingBlock.setPlacedBy 手动复制).</li>
 *   <li>破坏方块时: 重写 MiniBuildingBlock.playerDestroy, 主动用
 *       {@link BlockItem#setBlockEntityData} 把 BE 的 saveWithoutMetadata 写进
 *       ItemStack 的 BLOCK_ENTITY_DATA, 然后 popResource, 保证掉落物带数据.</li>
 *   <li>手持/掉落物渲染: 通过 {@link net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent}
 *       在客户端注入 IClientItemExtensions (返回 {@code MiniBuildingItemRenderer}).
 *       1.21.1 中 Item.initializeClient 已被 deprecated, 必须用 RegisterClientExtensionsEvent.</li>
 * </ul>
 */
public class MiniBuildingBlockItem extends BlockItem {

    public MiniBuildingBlockItem(Block block, Properties properties) {
        super(block, properties);
    }
}
