package com.prefab.addon.network;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.items.OperationWandManager;
import com.prefab.addon.items.OperationWandState;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端: 在目标位置放置 (MOVE / COPY).
 *
 * <p>携带: 目标 origin (新建筑 origin) + mode (0=MOVE, 1=COPY).</p>
 *
 * <p>服务端从 {@link OperationWandManager#get(ServerPlayer)} 读取缓存的方块列表
 * (在 scan 时已存), 然后:
 * <ul>
 *   <li>COPY: 在 target 放方块, 原区域保留</li>
 *   <li>MOVE: 在 target 放方块 + 清空原区域</li>
 * </ul>
 * 玩家可处于任何模式: 生存 + 创造都允许 MOVE; COPY 仅创造模式允许.</p>
 */
public record OperationWandBuildPayload(
    BlockPos target,
    int mode  // 0=MOVE, 1=COPY (跟 OperationWandState.Mode 顺序一致)
) implements CustomPacketPayload {

    public static final Type<OperationWandBuildPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(PrefabCustomAddon.MOD_ID, "operation_wand_build"));

    public static final StreamCodec<ByteBuf, OperationWandBuildPayload> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, OperationWandBuildPayload::target,
            ByteBufCodecs.VAR_INT, OperationWandBuildPayload::mode,
            OperationWandBuildPayload::new
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(final OperationWandBuildPayload payload, final IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        Level level = sp.level();

        // 校验: 玩家必须手持 OPERATION_WAND
        ItemStack mainHand = sp.getMainHandItem();
        ItemStack offHand  = sp.getOffhandItem();
        boolean isMain = mainHand.getItem() == PrefabCustomAddon.OPERATION_WAND.get();
        boolean isOff  = offHand.getItem()  == PrefabCustomAddon.OPERATION_WAND.get();
        if (!isMain && !isOff) {
            sp.sendSystemMessage(Component.literal("§c[操作手杖] §7未持有手杖, 操作取消"));
            return;
        }

        OperationWandState state = OperationWandManager.get(sp);
        if (!state.isReady()) {
            sp.sendSystemMessage(Component.literal("§c[操作手杖] §7没有可用的选区, 操作取消"));
            return;
        }

        OperationWandState.Mode modeEnum;
        try {
            modeEnum = OperationWandState.Mode.values()[payload.mode()];
        } catch (ArrayIndexOutOfBoundsException ex) {
            sp.sendSystemMessage(Component.literal("§c[操作手杖] §7未知模式, 操作取消"));
            return;
        }

        // COPY 仅创造模式可用
        if (modeEnum == OperationWandState.Mode.COPY && !sp.isCreative()) {
            sp.sendSystemMessage(Component.literal(
                "§c[操作手杖] §7复制模式仅创造模式可用, 操作取消"));
            return;
        }

        BlockPos target = payload.target();
        BlockPos origin = state.origin;

        // === 1) 在 target 放方块 ===
        int placed = 0;
        for (OperationWandState.CachedBlock cb : state.cachedBlocks) {
            BlockPos dest = target.offset(cb.x, cb.y, cb.z);
            level.setBlock(dest, cb.state, 2 | 16);  // 2=UPDATE_CLIENTS, 16=UPDATE_NEIGHBORS
            placed++;
        }

        // === 2) MOVE 模式: 清空原区域 ===
        int cleared = 0;
        if (modeEnum == OperationWandState.Mode.MOVE) {
            for (OperationWandState.CachedBlock cb : state.cachedBlocks) {
                BlockPos src = origin.offset(cb.x, cb.y, cb.z);
                // 只在原位置确实是我们放的方块时清空, 避免误删 (玩家可能在期间改了方块)
                BlockState current = level.getBlockState(src);
                if (current.getBlock() == cb.state.getBlock()) {
                    level.setBlock(src, Blocks.AIR.defaultBlockState(), 2);
                    cleared++;
                }
            }
        }

        // === 3) 反馈 ===
        String modeName = modeEnum == OperationWandState.Mode.MOVE ? "移动" : "复制";
        sp.sendSystemMessage(Component.literal(
            "§a[操作手杖] §7" + modeName + "完成: 放置 §f" + placed + " §7个方块"
                + (modeEnum == OperationWandState.Mode.MOVE ? ", 清除 §f" + cleared + " §7个方块" : "")
                + " (目标 " + target.getX() + "," + target.getY() + "," + target.getZ() + ")"
        ));

        // === 4) 清空状态 (允许玩家重新选区) ===
        OperationWandManager.clear(sp);
    }
}
