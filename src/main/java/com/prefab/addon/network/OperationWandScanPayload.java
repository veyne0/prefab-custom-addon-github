package com.prefab.addon.network;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.items.OperationWandManager;
import com.prefab.addon.items.OperationWandSelectionHandler;
import com.prefab.addon.items.OperationWandState;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 客户端 → 服务端: 操作手杖扫描选区.
 *
 * <p>携带: 两个角点 (corner1, corner2). 服务端扫描全部方块状态,
 * 序列化为 NBT byte[] 通过 {@link OperationWandScanResultPayload} 回传客户端.</p>
 *
 * <p>为什么走网络包而不是服务端静态状态: 客户端要画预览, 必须持有方块列表.
 * 服务端保持的 OperationWandState 是 ground truth, 客户端 state 仅供预览.</p>
 */
public record OperationWandScanPayload(
    BlockPos corner1,
    BlockPos corner2
) implements CustomPacketPayload {

    public static final Type<OperationWandScanPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(PrefabCustomAddon.MOD_ID, "operation_wand_scan"));

    public static final StreamCodec<ByteBuf, OperationWandScanPayload> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, OperationWandScanPayload::corner1,
            BlockPos.STREAM_CODEC, OperationWandScanPayload::corner2,
            OperationWandScanPayload::new
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(final OperationWandScanPayload payload, final IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        Level level = sp.level();

        BlockPos c1 = payload.corner1();
        BlockPos c2 = payload.corner2();
        int sx = Math.abs(c2.getX() - c1.getX()) + 1;
        int sy = Math.abs(c2.getY() - c1.getY()) + 1;
        int sz = Math.abs(c2.getZ() - c1.getZ()) + 1;
        if (sx > OperationWandSelectionHandler.MAX_SIZE
            || sy > OperationWandSelectionHandler.MAX_SIZE
            || sz > OperationWandSelectionHandler.MAX_SIZE) {
            sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§c[操作手杖] §7选区超过 " + OperationWandSelectionHandler.MAX_SIZE + " 格, 扫描取消"));
            return;
        }

        BlockPos min = new BlockPos(
            Math.min(c1.getX(), c2.getX()),
            Math.min(c1.getY(), c2.getY()),
            Math.min(c1.getZ(), c2.getZ()));
        BlockPos max = new BlockPos(
            Math.max(c1.getX(), c2.getX()),
            Math.max(c1.getY(), c2.getY()),
            Math.max(c1.getZ(), c2.getZ()));

        // === 1) 扫描所有方块, 写入 NBT (ListTag of CompoundTag) ===
        ListTag list = new ListTag();
        int airSkipped = 0;
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            BlockState state = level.getBlockState(p);
            if (state.isAir()) {
                airSkipped++;
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putInt("x", p.getX() - min.getX());  // 局部坐标
            entry.putInt("y", p.getY() - min.getY());
            entry.putInt("z", p.getZ() - min.getZ());
            entry.put("state", BlockState.CODEC.encodeStart(
                net.minecraft.nbt.NbtOps.INSTANCE, state).getOrThrow());
            list.add(entry);
        }
        CompoundTag root = new CompoundTag();
        root.put("blocks", list);
        root.putInt("sizeX", sx);
        root.putInt("sizeY", sy);
        root.putInt("sizeZ", sz);
        root.putInt("originX", min.getX());
        root.putInt("originY", min.getY());
        root.putInt("originZ", min.getZ());
        root.putInt("airSkipped", airSkipped);

        // === 2) 序列化为 byte[] (gzip 压缩) ===
        byte[] nbtBytes;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            NbtIo.write(root, dos);
            nbtBytes = baos.toByteArray();
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("[OP-WAND] NBT write failed", e);
            sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§c[操作手杖] §7扫描失败: " + e.getMessage()));
            return;
        }
        PrefabCustomAddon.LOGGER.info("[OP-WAND] Scanned {} blocks ({} air skipped), nbt={} bytes",
            list.size(), airSkipped, nbtBytes.length);

        // === 3) 服务端 state: 保存 origin + size + cached blocks (用于 build) ===
        OperationWandState srvState = OperationWandManager.get(sp);
        srvState.clear();
        srvState.origin = min.immutable();
        srvState.sizeX = sx;
        srvState.sizeY = sy;
        srvState.sizeZ = sz;
        srvState.previewPos = min.immutable();  // 初始预览位置 = 原位置
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            BlockState state = BlockState.CODEC.parse(
                net.minecraft.nbt.NbtOps.INSTANCE, entry.get("state")).getOrThrow();
            srvState.cachedBlocks.add(new OperationWandState.CachedBlock(
                entry.getInt("x"), entry.getInt("y"), entry.getInt("z"), state));
        }

        // === 4) 把 NBT 发回客户端 (供预览) ===
        PacketDistributor.sendToPlayer(sp,
            new OperationWandScanResultPayload(min.immutable(), sx, sy, sz,
                list.size(), airSkipped, nbtBytes));

        sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            "§a[操作手杖] §7已捕获 §f" + list.size() + " §7个方块 (§7" + sx + "x" + sy + "x" + sz
                + "§7). 手持手杖, §a右键空地§7 = 移动/复制, §cCTRL§7 = 取消"
        ));
    }
}
