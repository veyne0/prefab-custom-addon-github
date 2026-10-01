/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.ChatFormatting
 *  net.minecraft.core.BlockPos
 *  net.minecraft.core.Direction
 *  net.minecraft.network.chat.Component
 *  net.minecraft.server.level.ServerLevel
 *  net.minecraft.server.level.ServerPlayer
 *  net.minecraft.world.level.block.Blocks
 *  net.minecraft.world.level.block.state.BlockState
 */
package com.prefab.addon.multiblock;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.multiblock.MultiblockCatalog;
import com.prefab.addon.multiblock.MultiblockShapeData;
import com.prefab.addon.structure.BlockStateRotator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class MultiblockPlacer {
    private MultiblockPlacer() {
    }

    public static void place(ServerPlayer player, String id, BlockPos origin, Direction facing) {
        if (player == null || origin == null) {
            return;
        }
        MultiblockShapeData shape = MultiblockCatalog.getShape(id);
        if (shape == null || shape.localBlocks.isEmpty()) {
            MultiblockPlacer.warn(player, "\u670d\u52a1\u7aef\u627e\u4e0d\u5230\u591a\u65b9\u5757\u7ed3\u6784: " + id + " (\u670d\u52a1\u7aef\u672a\u88c5 GTM \u6216\u63d0\u53d6\u5931\u8d25)");
            PrefabCustomAddon.LOGGER.warn("[MB-PLACE] shape missing on server, id={}", (Object)id);
            return;
        }
        ServerLevel level = player.serverLevel();
        int steps = MultiblockPlacer.facingToRotationSteps(facing);
        LinkedHashMap<BlockPos, BlockState> rotated = new LinkedHashMap<BlockPos, BlockState>();
        BlockState controllerState = null;
        for (Map.Entry<BlockPos, BlockState> e : shape.localBlocks.entrySet()) {
            BlockPos local = e.getKey();
            BlockState state = e.getValue();
            if (state == null || state.isAir()) continue;
            int rx = local.getX();
            int ry = local.getY();
            int rz = local.getZ();
            for (int s = 0; s < steps; ++s) {
                int newRx = rz;
                int newRz = -rx;
                rx = newRx;
                rz = newRz;
            }
            if (steps != 0) {
                state = BlockStateRotator.rotateY(state, steps);
            }
            BlockPos world = origin.offset(rx, ry, rz);
            if (local.equals((Object)BlockPos.ZERO)) {
                controllerState = state;
                continue;
            }
            rotated.put(world, state);
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int cx = 0; cx < 2; ++cx) {
            for (int cy = 0; cy < 2; ++cy) {
                for (int cz = 0; cz < 2; ++cz) {
                    BlockPos corner = new BlockPos(cx == 0 ? shape.bboxMin.getX() : shape.bboxMax.getX(), cy == 0 ? shape.bboxMin.getY() : shape.bboxMax.getY(), cz == 0 ? shape.bboxMin.getZ() : shape.bboxMax.getZ());
                    int rx = corner.getX();
                    int ry = corner.getY();
                    int rz = corner.getZ();
                    for (int s = 0; s < steps; ++s) {
                        int newRx = rz;
                        int newRz = -rx;
                        rx = newRx;
                        rz = newRz;
                    }
                    minX = Math.min(minX, origin.getX() + rx);
                    maxX = Math.max(maxX, origin.getX() + rx);
                    minY = Math.min(minY, origin.getY() + ry);
                    maxY = Math.max(maxY, origin.getY() + ry);
                    minZ = Math.min(minZ, origin.getZ() + rz);
                    maxZ = Math.max(maxZ, origin.getZ() + rz);
                }
            }
        }
        int cleared = 0;
        for (BlockPos p : BlockPos.betweenClosed((int)minX, (int)minY, (int)minZ, (int)maxX, (int)maxY, (int)maxZ)) {
            if (level.getBlockState(p).isAir()) continue;
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
            ++cleared;
        }
        int placed = 0;
        for (Map.Entry e : rotated.entrySet()) {
            level.setBlock((BlockPos)e.getKey(), (BlockState)e.getValue(), 3);
            ++placed;
        }
        if (controllerState != null) {
            level.setBlock(origin, controllerState, 3);
            ++placed;
        }
        PrefabCustomAddon.LOGGER.info("[MB-PLACE] {} \u653e\u7f6e\u591a\u65b9\u5757 id={} origin={} facing={} steps={} placed={} cleared={}", new Object[]{player.getName().getString(), id, origin, facing, steps, placed, cleared});
        MultiblockPlacer.success(player, "\u591a\u65b9\u5757\u7ed3\u6784\u5df2\u5efa\u9020: " + id + " (" + placed + " \u65b9\u5757, \u6e05\u7406 " + cleared + " \u683c)");
        // 建造成功: 清服务端材料账本 (key = sessionKey(), 与客户端 StructurePreviewKeyHandler 的 reset 对齐)
        com.prefab.addon.work.ServerMaterialLedger.reset(player, "multiblock:" + id);
    }

    private static int facingToRotationSteps(Direction facing) {
        if (facing == null) {
            return 0;
        }
        return switch (facing) {
            case Direction.SOUTH -> 0;
            case Direction.EAST -> 1;
            case Direction.NORTH -> 2;
            case Direction.WEST -> 3;
            default -> 0;
        };
    }

    private static void warn(ServerPlayer p, String msg) {
        p.sendSystemMessage((Component)Component.literal((String)("\u00a7c" + msg)).withStyle(ChatFormatting.RED));
    }

    private static void success(ServerPlayer p, String msg) {
        p.sendSystemMessage((Component)Component.literal((String)("\u00a7a\u2713 " + msg)).withStyle(ChatFormatting.GREEN));
    }
}

