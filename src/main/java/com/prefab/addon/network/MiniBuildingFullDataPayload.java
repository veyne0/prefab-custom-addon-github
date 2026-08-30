package com.prefab.addon.network;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.blocks.MiniBuildingBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

/**
 * 服务端 → 客户端: 推送迷你建筑完整 NBT (绕过 2MB NbtAccounter).
 *
 * <p>正常 BE 同步 (ClientboundBlockEntityDataPacket) 受客户端 {@code NbtAccounter}
 * 限制 2MB. 中大型迷你建筑 (15777 块 ≈ 1MB+, 40x96x59 已经接近 2MB) 走那条路
 * 会直接断连. 改用自定义包 + byte[] 编码, 完全绕过 NbtAccounter.</p>
 *
 * <p>nbtData 是 {@link NbtIo#write} 出来的二进制 (无压缩, 解压逻辑在客户端),
 * 跟 {@link OperationWandScanResultPayload} 一样的处理方式.</p>
 */
public record MiniBuildingFullDataPayload(BlockPos pos, byte[] nbtData)
    implements CustomPacketPayload {

    public static final Type<MiniBuildingFullDataPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(PrefabCustomAddon.MOD_ID, "mini_building_full_data"));

    public static final StreamCodec<ByteBuf, MiniBuildingFullDataPayload> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, MiniBuildingFullDataPayload::pos,
            ByteBufCodecs.BYTE_ARRAY, MiniBuildingFullDataPayload::nbtData,
            MiniBuildingFullDataPayload::new
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(final MiniBuildingFullDataPayload payload, final IPayloadContext ctx) {
        // [DEBUG] 详细日志: 确认包到达客户端
        PrefabCustomAddon.LOGGER.info(
            "[MINI_BUILDING] [DEBUG] MiniBuildingFullDataPayload received: pos={}, nbtBytes={}",
            payload.pos(), payload.nbtData().length);
        ctx.enqueueWork(() -> {
            try (ByteArrayInputStream bais = new ByteArrayInputStream(payload.nbtData());
                 DataInputStream dis = new DataInputStream(bais)) {
                CompoundTag full = NbtIo.read(dis);
                if (full == null) {
                    PrefabCustomAddon.LOGGER.warn(
                        "[MINI_BUILDING] Client: got empty full data for {}", payload.pos());
                    return;
                }
                if (ctx.player().level().getBlockEntity(payload.pos())
                    instanceof MiniBuildingBlockEntity be) {
                    PrefabCustomAddon.LOGGER.info(
                        "[MINI_BUILDING] [DEBUG] MiniBuildingFullDataPayload: found BE @ {}, calling onFullDataReceived, full tag keys={}",
                        payload.pos(), full.getAllKeys());
                    be.onFullDataReceived(full);
                } else {
                    PrefabCustomAddon.LOGGER.warn(
                        "[MINI_BUILDING] Client: got full data for {} but no BE there (chunk not loaded?)",
                        payload.pos());
                }
            } catch (IOException e) {
                PrefabCustomAddon.LOGGER.error(
                    "[MINI_BUILDING] Client: failed to parse full data for {}", payload.pos(), e);
            }
        });
    }
}
