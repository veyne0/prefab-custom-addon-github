package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 服务端 → 客户端：服务器侧所有拓展包的清单。
 * 客户端拿到清单后，比对自己缓存，决定要请求哪些 zip。
 *
 * 字段：
 *   - name:   拓展包 packageName（作为客户端缓存的文件名 + 服务器读取 zip 的 key）
 *   - sha1:   zip 内容 SHA-1（40 字符小写十六进制），用于判断客户端是否已缓存最新版
 *   - size:   zip 文件大小（字节），用于进度条
 */
public record ServerPackManifestPayload(
        List<Entry> packs
) implements CustomPacketPayload {

    public record Entry(String name, String sha1, long size) {}

    public static final CustomPacketPayload.Type<ServerPackManifestPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("prefab_custom_addon", "server_pack_manifest"));

    public static final StreamCodec<FriendlyByteBuf, Entry> ENTRY_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, Entry::name,
            ByteBufCodecs.STRING_UTF8, Entry::sha1,
            ByteBufCodecs.VAR_LONG,  Entry::size,
            Entry::new
    );

    public static final StreamCodec<FriendlyByteBuf, ServerPackManifestPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ENTRY_CODEC.apply(ByteBufCodecs.list()), ServerPackManifestPayload::packs,
                    ServerPackManifestPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
