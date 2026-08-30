package com.prefab.addon.blocks;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * 迷你建筑方块: 放置后承载建筑数据.
 *
 * <p>渲染策略: ENTITYBLOCK_ANIMATED (走 BlockEntityRenderer).</p>
 *
 * <p>数据流 (1.21.1 标准的 BLOCK_ENTITY_DATA 体系):
 * <ul>
 *   <li>放置: BlockItem 读 {@code DataComponents.BLOCK_ENTITY_DATA}, 通过
 *       {@code BlockItem.updateCustomBlockEntityTag} 解析到 BE (自动).</li>
 *   <li>破坏: 重写 {@link #playerDestroy}, 主动用 BE.saveWithoutMetadata 把
 *       数据写进 ItemStack, 然后 popResource. 同时 {@link #getDrops} 返回空
 *       避免默认掉落 (空 ItemStack) 重复弹出.</li>
 * </ul>
 */
public class MiniBuildingBlock extends BaseEntityBlock {

    public static final MapCodec<MiniBuildingBlock> CODEC = simpleCodec(MiniBuildingBlock::new);

    public MiniBuildingBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MiniBuildingBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;  // 走 BlockEntityRenderer
    }

    /**
     * 玩家破坏方块时: 主动用 BE 数据生成一个带 BLOCK_ENTITY_DATA 的 ItemStack
     * 并 popResource, 避免 super 默认生成无数据的 ItemStack.
     *
     * <p>不调 super.playerDestroy, 因为它会默认掉落一个无 NBT 的 ItemStack
     * (走 Block.getDrops / state.getDrops, 走默认 loot table).</p>
     */
    @Override
    public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state,
                              @Nullable BlockEntity blockEntity, ItemStack tool) {
        if (!level.isClientSide() && blockEntity instanceof MiniBuildingBlockEntity mbbe) {
            ItemStack stack = new ItemStack(asItem());
            // [FIX] 只写引用, 绝不写完整数据: 完整数据可能几十 MB, 掉落物实体的
            //   ItemStack 会随 set_entity_data 同步给客户端, 超 2MB 触发 NbtAccounter
            //   限制直接断线 (disconnect report: Failed to decode set_entity_data).
            //   之前这里用 saveWithoutMetadata 把完整 NBT 写进掉落物.
            CompoundTag refTag = mbbe.toReferenceTag(level.registryAccess());
            if (refTag != null) {
                BlockItem.setBlockEntityData(stack, mbbe.getType(), refTag);
            } else {
                com.prefab.addon.PrefabCustomAddon.LOGGER.warn(
                    "[MINI_BUILDING] playerDestroy @ {}: 无法导出引用, 掉落无数据物品 (总比断线强)", pos);
            }
            Block.popResource(level, pos, stack);
            com.prefab.addon.PrefabCustomAddon.LOGGER.info(
                "[MINI_BUILDING] playerDestroy: popped ref-only item at {}", pos);
        }
        // 不调 super.playerDestroy, 避免默认掉落无 NBT 的 ItemStack
    }

    /**
     * 不让默认 loot table 处理 (我们已经自己处理 playerDestroy 掉落).
     * 返回空 list 避免双重掉落.
     */
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return Collections.emptyList();
    }
}
