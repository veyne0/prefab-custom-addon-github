package com.prefab.addon.network;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.items.ItemCustomBulldozer;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
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
 *
 * <p>掉落物规则: 清除模式下长/宽/高 每个都 ≤16 才生成掉落物, 否则跳过 (V2.2.0).</p>
 */
public record ExecuteCustomBulldozerPayload(
    BlockPos pos,
    int length,
    int width,
    int height,
    Direction facing,
    boolean noDrops,
    boolean fillMode,
    String fillBlockId
) implements CustomPacketPayload {

    public static final Type<ExecuteCustomBulldozerPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(PrefabCustomAddon.MOD_ID, "execute_custom_bulldozer"));

    /**
     * 8 字段超过 {@code StreamCodec.composite} 的 6 字段上限, 用自定义 codec
     * (跟 OperationWandScanResultPayload 同样处理).
     * 编码顺序: pos, length, width, height, facing, noDrops, fillMode, fillBlockId.
     */
    public static final StreamCodec<ByteBuf, ExecuteCustomBulldozerPayload> STREAM_CODEC =
        new StreamCodec<>() {
            @Override
            public void encode(ByteBuf buf, ExecuteCustomBulldozerPayload v) {
                BlockPos.STREAM_CODEC.encode(buf, v.pos());
                ByteBufCodecs.VAR_INT.encode(buf, v.length());
                ByteBufCodecs.VAR_INT.encode(buf, v.width());
                ByteBufCodecs.VAR_INT.encode(buf, v.height());
                Direction.STREAM_CODEC.encode(buf, v.facing());
                ByteBufCodecs.BOOL.encode(buf, v.noDrops());
                ByteBufCodecs.BOOL.encode(buf, v.fillMode());
                ByteBufCodecs.STRING_UTF8.encode(buf, v.fillBlockId() == null ? "" : v.fillBlockId());
            }

            @Override
            public ExecuteCustomBulldozerPayload decode(ByteBuf buf) {
                BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
                int length = ByteBufCodecs.VAR_INT.decode(buf);
                int width = ByteBufCodecs.VAR_INT.decode(buf);
                int height = ByteBufCodecs.VAR_INT.decode(buf);
                Direction facing = Direction.STREAM_CODEC.decode(buf);
                boolean noDrops = ByteBufCodecs.BOOL.decode(buf);
                boolean fillMode = ByteBufCodecs.BOOL.decode(buf);
                String fillBlockId = ByteBufCodecs.STRING_UTF8.decode(buf);
                return new ExecuteCustomBulldozerPayload(
                    pos, length, width, height, facing, noDrops, fillMode, fillBlockId);
            }
        };

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

        // 把 payload 携带的设置回写服务端手持物品 NBT: 客户端 GUI 改尺寸/模式只改客户端 NBT, 不会同步到服务端,
        // 扣耐久触发 slot 同步后客户端会被服务端旧副本覆盖, 尺寸重置回默认 32x32x32. 这里回写保持两边一致.
        ItemStack syncStack = isMain ? mainHand : offHand;
        ItemCustomBulldozer.setDimensions(syncStack, payload.length(), payload.width(), payload.height());
        ItemCustomBulldozer.setFillMode(syncStack, payload.fillMode());
        ItemCustomBulldozer.setFillBlockId(syncStack, payload.fillBlockId());

        // 1) 计算清空区域 (跟原版 StructureBulldozer 同款: 起点 + 朝向 + 尺寸)
        AABB box = computeBox(payload.pos(), payload.facing(),
            payload.length(), payload.width(), payload.height());
        BlockPos start = BlockPos.containing(box.minX, box.minY, box.minZ);
        BlockPos end   = BlockPos.containing(box.maxX, box.maxY, box.maxZ);

        // === 填充模式: 把整个区域所有格子替换成选中方块 (WorldEdit //set 风格) ===
        if (payload.fillMode()) {
            String id = payload.fillBlockId();
            if (id == null || id.isEmpty()) {
                sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§c✗ 未选择填充方块, 请先点「填充方块」按钮选择"));
                return;
            }
            Block fillBlock;
            try {
                fillBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
            } catch (Throwable t) {
                fillBlock = Blocks.AIR;
            }
            if (fillBlock == null || fillBlock == Blocks.AIR) {
                sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§c✗ 填充方块无效: " + id));
                return;
            }
            BlockState fillState = fillBlock.defaultBlockState();
            int filled = 0;
            for (BlockPos p : BlockPos.betweenClosed(start, end)) {
                level.setBlock(p, fillState, 2);
                filled++;
            }
            // 扣耐久 (生存模式才扣)
            if (!sp.isCreative()) {
                ItemStack heldStack = isMain ? mainHand : offHand;
                heldStack.hurtAndBreak(1, sp,
                    isMain ? net.minecraft.world.entity.EquipmentSlot.MAINHAND
                           : net.minecraft.world.entity.EquipmentSlot.OFFHAND);
            }
            sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§a✓ 推土机已填充 " + filled + " 个方块 ("
                + payload.length() + "x" + payload.width() + "x" + payload.height() + ") → §f"
                + new ItemStack(fillBlock).getHoverName().getString()));
            return;
        }

        // 2) 逐格清除 (原版 BlockShouldBeClearedDuringConstruction 等价物)
        int cleared = 0;
        boolean creative = sp.isCreative();
        // 尺寸判定: 长/宽/高 每个都 ≤16 才生成掉落物, 否则大范围清除直接跳过 (防物品泛滥 + 性能)
        boolean smallArea = payload.length() <= 16 && payload.width() <= 16 && payload.height() <= 16;
        // "破坏不生成掉落物" 模式下 (含客户端 noDrops 勾选): 完全跳过 dropResources, 性能大幅提升.
        boolean skipDrops = payload.noDrops() || !smallArea;
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
            + (skipDrops ? (smallArea ? " §7[无掉落物]" : " §7[长宽高需每个都≤16才生成掉落物]") : "")));
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
