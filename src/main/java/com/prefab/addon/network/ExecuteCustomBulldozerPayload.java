package com.prefab.addon.network;

import com.prefab.addon.PrefabCustomAddon;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端: 执行自定义推土机清除.
 *
 * <p>携带: 起始点 (pos) + 三个尺寸 (length/width/height) + 朝向 (facing).</p>
 *
 * <p>服务端: 校验玩家手持 + 走原版 {@code BlockShouldBeClearedDuringConstruction} 同款逻辑
 * (按 diamond pickaxe/shovel/axe 决定是否掉物品) → 逐格 setBlock AIR → 扣耐久.</p>
 */
public record ExecuteCustomBulldozerPayload(
    BlockPos pos,
    int length,
    int width,
    int height,
    Direction facing,
    boolean noDrops
) implements CustomPacketPayload {

    public static final Type<ExecuteCustomBulldozerPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(PrefabCustomAddon.MOD_ID, "execute_custom_bulldozer"));

    public static final StreamCodec<ByteBuf, ExecuteCustomBulldozerPayload> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC,    ExecuteCustomBulldozerPayload::pos,
            ByteBufCodecs.VAR_INT,     ExecuteCustomBulldozerPayload::length,
            ByteBufCodecs.VAR_INT,     ExecuteCustomBulldozerPayload::width,
            ByteBufCodecs.VAR_INT,     ExecuteCustomBulldozerPayload::height,
            Direction.STREAM_CODEC,    ExecuteCustomBulldozerPayload::facing,
            ByteBufCodecs.BOOL,        ExecuteCustomBulldozerPayload::noDrops,
            ExecuteCustomBulldozerPayload::new
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(final ExecuteCustomBulldozerPayload payload, final IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        Level level = sp.level();

        // 校验: 玩家必须手持 CUSTOM_BULLDOZER
        ItemStack mainHand = sp.getMainHandItem();
        ItemStack offHand  = sp.getOffhandItem();
        boolean isMain = mainHand.getItem() == PrefabCustomAddon.CUSTOM_BULLDOZER.get();
        boolean isOff  = offHand.getItem()  == PrefabCustomAddon.CUSTOM_BULLDOZER.get();
        if (!isMain && !isOff) return;

        // 1) 计算清空区域 (跟原版 StructureBulldozer 同款: 起点 + 朝向 + 尺寸)
        AABB box = computeBox(payload.pos(), payload.facing(),
            payload.length(), payload.width(), payload.height());
        BlockPos start = BlockPos.containing(box.minX, box.minY, box.minZ);
        BlockPos end   = BlockPos.containing(box.maxX, box.maxY, box.maxZ);

        // 2) 逐格清除 (原版 BlockShouldBeClearedDuringConstruction 等价物)
        int cleared = 0;
        boolean creative = sp.isCreative();
        // "破坏不生成掉落物" 模式下 (含客户端 noDrops 勾选): 完全跳过 dropResources, 性能大幅提升.
        boolean skipDrops = payload.noDrops();
        for (BlockPos p : BlockPos.betweenClosed(start, end)) {
            BlockState state = level.getBlockState(p);
            if (state.isAir()) continue;
            // 流体: 创造模式先替换为石头 (才能 setAir)
            if (creative && state.getBlock() instanceof LiquidBlock) {
                level.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
                state = level.getBlockState(p);
            }
            // 掉物品: 跟原版一样, 不需要特定工具或钻石工具能采; noDrops=true 时完全跳过.
            if (!skipDrops && !creative && state.getDestroySpeed(level, p) >= 0f) {
                boolean needsTool = state.requiresCorrectToolForDrops();
                if (!needsTool || isEffectiveTool(state)) {
                    BlockEntity be = level.getBlockEntity(p);
                    Block.dropResources(state, level, p, be, sp, sp.getMainHandItem());
                }
            }
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
            cleared++;
        }

        // 3) 扣耐久 (生存模式才扣)
        if (!creative) {
            ItemStack stack = isMain ? mainHand : offHand;
            stack.hurtAndBreak(1, sp,
                isMain ? net.minecraft.world.entity.EquipmentSlot.MAINHAND
                       : net.minecraft.world.entity.EquipmentSlot.OFFHAND);
        }

        sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            "§a✓ 推土机已清除 " + cleared + " 个方块 ("
            + payload.length() + "x" + payload.width() + "x" + payload.height() + ")"
            + (skipDrops ? " §7[无掉落物]" : "")));
    }

    /**
     * 按 facing 算出盒子. facing 是玩家朝向 (south/north/east/west):
     *   - length 沿 facing 方向
     *   - width  垂直于 facing (水平)
     *   - height 竖直
     * 起点 pos 视为"前方左下角" (X: facing 方向左偏 1 格).
     */
    private static AABB computeBox(BlockPos origin, Direction facing, int length, int width, int height) {
        Direction sideways = facing.getCounterClockWise();  // 玩家左
        // X 方向: 起点 = origin + (sideways) * ((width - 1) / 2)
        int xOffset = -((width - 1) / 2) * sideways.getStepX();
        int zOffset = -((width - 1) / 2) * sideways.getStepZ();
        BlockPos start = origin.offset(xOffset, 0, zOffset);
        // 终 X/Z: start + facing * (length - 1) + sideways * (width - 1)
        int fx = facing.getStepX(), fz = facing.getStepZ();
        int sx = sideways.getStepX(), sz = sideways.getStepZ();
        int endX = start.getX() + fx * (length - 1) + sx * (width - 1);
        int endZ = start.getZ() + fz * (length - 1) + sz * (width - 1);
        int endY = origin.getY() + height - 1;
        return new AABB(
            Math.min(start.getX(), endX), origin.getY(),         Math.min(start.getZ(), endZ),
            Math.max(start.getX(), endX), endY,                  Math.max(start.getZ(), endZ)
        );
    }

    private static boolean isEffectiveTool(BlockState state) {
        // 跟原版 StructureBulldozer 一致: 钻石稿/锹/斧 任一有效即视作可掉物品
        return net.minecraft.world.item.Items.DIAMOND_PICKAXE.isCorrectToolForDrops(
                   new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE), state)
            || net.minecraft.world.item.Items.DIAMOND_SHOVEL.isCorrectToolForDrops(
                   new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SHOVEL), state)
            || net.minecraft.world.item.Items.DIAMOND_AXE.isCorrectToolForDrops(
                   new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_AXE), state);
    }
}
