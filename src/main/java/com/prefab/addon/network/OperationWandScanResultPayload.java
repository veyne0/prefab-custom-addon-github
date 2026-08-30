package com.prefab.addon.network;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.client.OperationWandClientHandler;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

/**
 * 服务端 → 客户端: 扫描结果 (含 NBT 字节).
 *
 * <p>客户端解析 NBT 写入 OperationWandState.cachedBlocks, 用于 3D 预览渲染.</p>
 *
 * <p>7 个字段超过 {@code StreamCodec.composite} 的 6 字段上限, 使用自定义 codec
 * (跟 MiniBuildingInteractPayload 同样的处理方式).</p>
 */
public record OperationWandScanResultPayload(
    BlockPos origin,
    int sizeX, int sizeY, int sizeZ,
    int blockCount,
    int airSkipped,
    byte[] nbtData
) implements CustomPacketPayload {

    public static final Type<OperationWandScanResultPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(PrefabCustomAddon.MOD_ID, "operation_wand_scan_result"));

    /**
     * 自定义 StreamCodec, 7 字段 (复合 6 上限).
     * 编码顺序: origin, sizeX, sizeY, sizeZ, blockCount, airSkipped, nbtData.
     */
    public static final StreamCodec<ByteBuf, OperationWandScanResultPayload> STREAM_CODEC =
        new StreamCodec<>() {
            @Override
            public void encode(ByteBuf buf, OperationWandScanResultPayload v) {
                BlockPos.STREAM_CODEC.encode(buf, v.origin());
                ByteBufCodecs.VAR_INT.encode(buf, v.sizeX());
                ByteBufCodecs.VAR_INT.encode(buf, v.sizeY());
                ByteBufCodecs.VAR_INT.encode(buf, v.sizeZ());
                ByteBufCodecs.VAR_INT.encode(buf, v.blockCount());
                ByteBufCodecs.VAR_INT.encode(buf, v.airSkipped());
                ByteBufCodecs.BYTE_ARRAY.encode(buf, v.nbtData());
            }

            @Override
            public OperationWandScanResultPayload decode(ByteBuf buf) {
                BlockPos origin = BlockPos.STREAM_CODEC.decode(buf);
                int sx = ByteBufCodecs.VAR_INT.decode(buf);
                int sy = ByteBufCodecs.VAR_INT.decode(buf);
                int sz = ByteBufCodecs.VAR_INT.decode(buf);
                int bc = ByteBufCodecs.VAR_INT.decode(buf);
                int as = ByteBufCodecs.VAR_INT.decode(buf);
                byte[] nbt = ByteBufCodecs.BYTE_ARRAY.decode(buf);
                return new OperationWandScanResultPayload(origin, sx, sy, sz, bc, as, nbt);
            }
        };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(final OperationWandScanResultPayload payload, final IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            // 客户端: 解析 NBT → 写入 OperationWandState
            try (ByteArrayInputStream bais = new ByteArrayInputStream(payload.nbtData());
                 DataInputStream dis = new DataInputStream(bais)) {
                CompoundTag root = NbtIo.read(dis);
                if (root == null) {
                    PrefabCustomAddon.LOGGER.error("[OP-WAND] NBT parse failed: empty root");
                    return;
                }
                ListTag list = root.getList("blocks", Tag.TAG_COMPOUND);
                OperationWandClientHandler.onScanResult(
                    payload.origin(), payload.sizeX(), payload.sizeY(), payload.sizeZ(),
                    payload.blockCount(), payload.airSkipped(), list);
            } catch (IOException e) {
                PrefabCustomAddon.LOGGER.error("[OP-WAND] NBT parse failed", e);
            }
        });
    }
}
