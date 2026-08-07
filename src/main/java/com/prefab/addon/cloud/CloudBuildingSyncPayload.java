package com.prefab.addon.cloud;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端: 全量同步云端建筑列表.
 *
 * <p>每个 CloudBuilding 转成 CompoundTag 传输, 客户端反序列化为
 * {@link CloudBuilding} 实例存到 {@link CloudBuildingClientCache}.</p>
 *
 * <p>数据量: 单个建筑可能 4w 块, 但 NBT 压缩后通常 &lt;1MB;
 * 一次性全量推, 玩家打开云端 tab 时已经到位, 不用增量同步.</p>
 */
public record CloudBuildingSyncPayload(List<CompoundTag> buildings)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CloudBuildingSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("prefab_custom_addon", "cloud_sync"));

    public static final StreamCodec<FriendlyByteBuf, CloudBuildingSyncPayload> STREAM_CODEC =
            StreamCodec.of(
                (buf, payload) -> {
                    buf.writeInt(payload.buildings.size());
                    for (CompoundTag tag : payload.buildings) {
                        // writeNbt 会写 TAG_COMPOUND 类型头
                        buf.writeNbt(tag);
                    }
                },
                buf -> {
                    int n = buf.readInt();
                    List<CompoundTag> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        // readNbt(NbtAccounter) 返回 Tag, 需要 cast 成 CompoundTag
                        // 用 unlimitedHeap 解码, 避免默认 2MB 限制导致大建筑 (NBT > 2MB) 整客户端掉线
                        Tag raw = buf.readNbt(NbtAccounter.unlimitedHeap());
                        if (raw instanceof CompoundTag t) list.add(t);
                    }
                    return new CloudBuildingSyncPayload(list);
                }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
