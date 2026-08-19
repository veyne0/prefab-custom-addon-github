package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 服务端 → 客户端：服务器侧所有可同步建筑的清单（建筑级，非包级）。
 * <p>
 * <strong>已废弃"拓展包"概念：每个建筑都是独立的清单条目</strong>。一个老式 .zip 拓展包
 * 里有几个建筑，就发几个 entry；新的"三件套"格式（.nbt + .txt + .png 平铺在
 * prefab-extension/）一个文件就发一个 entry。
 * </p>
 *
 * <h2>字段说明</h2>
 * <ul>
 *   <li><b>buildingId</b>  建筑 id（裸名，无扩展名）。例：huochaihe</li>
 *   <li><b>displayName</b> 友好显示名（从 .txt 的"建筑名:"行读，空则用 buildingId）</li>
 *   <li><b>format</b>     建筑蓝图格式：".nbt" / ".litematic" / ".schem"</li>
 *   <li><b>packName</b>   源文件名（无扩展名）。例：test.zip 里有 huochaihe 和 dixiagongcheng 两个建筑，
 *                          它们的 packName 都是 "test"。新格式下 packName == buildingId</li>
 *   <li><b>sha1</b>       源文件（zip 或 nbt）内容 SHA-1，用于比对本地缓存</li>
 *   <li><b>size</b>       源文件字节数，用于进度条</li>
 *   <li><b>author</b>     作者（从 .txt 解析）</li>
 *   <li><b>description</b> 描述（从 .txt 解析）</li>
 *   <li><b>pngData</b>    缩略图 PNG 字节（从 zip 内 .png 或同前缀 .png 读）。空字节数组表示无图</li>
 * </ul>
 *
 * <h2>同步协议</h2>
 * 客户端根据 <code>packName + format</code> 决定要请求哪个源文件。
 * 老式 zip：请求一次就把整包下到 server-cache/，包里所有建筑同时变"已同步"。
 * 新格式：每个 packName 都唯一对应一个 .nbt 文件。
 */
public record ServerPackManifestPayload(
        List<Entry> buildings
) implements CustomPacketPayload {

    /**
     * 单个建筑条目。name/sha1 字段保留以兼容旧调用方，新代码用 buildingId/packName。
     */
    public record Entry(
            String name,            // = buildingId (旧字段，保留兼容)
            String sha1,            // = 源文件 SHA-1
            long size,              // = 源文件字节数
            String buildingId,      // 建筑 id
            String displayName,     // 友好名
            String format,          // .nbt / .litematic / .schem
            String packName,        // 源文件 basename
            String sourceFileName,  // 源文件完整名 (e.g. "test.zip" / "huochaihe.nbt")
            String author,          // 作者
            String description,     // 描述
            byte[] pngData          // 缩略图 PNG（可空）
    ) {
        /** 旧构造器：只用 (name, sha1, size)，其他字段填空。 */
        public Entry(String name, String sha1, long size) {
            this(name, sha1, size, name, name, ".nbt", name, name + ".zip", "", "", new byte[0]);
        }
    }

    public static final CustomPacketPayload.Type<ServerPackManifestPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("prefab_custom_addon", "server_pack_manifest"));

    /**
     * Entry 编解码。
     * <p>注意: {@link StreamCodec#composite} 最多支持 6 个字段, 我们有 11 个,
     * 所以用自定义 codec 手动 encode/decode, 字段顺序跟 record 一致.</p>
     * <p>字段顺序: name, sha1, size, buildingId, displayName, format,
     * packName, sourceFileName, author, description, pngData.</p>
     */
    public static final StreamCodec<FriendlyByteBuf, Entry> ENTRY_CODEC = new StreamCodec<>() {
        @Override
        public void encode(FriendlyByteBuf buf, Entry e) {
            buf.writeUtf(e.name);
            buf.writeUtf(e.sha1);
            buf.writeVarLong(e.size);
            buf.writeUtf(e.buildingId);
            buf.writeUtf(e.displayName);
            buf.writeUtf(e.format);
            buf.writeUtf(e.packName);
            buf.writeUtf(e.sourceFileName);
            buf.writeUtf(e.author);
            buf.writeUtf(e.description);
            buf.writeByteArray(e.pngData);
        }

        @Override
        public Entry decode(FriendlyByteBuf buf) {
            return new Entry(
                buf.readUtf(),
                buf.readUtf(),
                buf.readVarLong(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readByteArray()
            );
        }
    };

    public static final StreamCodec<FriendlyByteBuf, ServerPackManifestPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ENTRY_CODEC.apply(ByteBufCodecs.list()), ServerPackManifestPayload::buildings,
                    ServerPackManifestPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
