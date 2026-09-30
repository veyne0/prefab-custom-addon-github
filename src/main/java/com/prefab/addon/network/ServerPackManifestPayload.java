package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：服务器侧所有可同步建筑的清单（建筑级，非包级）。
 *
 * <h2>字段说明</h2>
 * <ul>
 *   <li><b>buildingId</b>  建筑 id（裸名，无扩展名）。例：huochaihe</li>
 *   <li><b>displayName</b> 友好显示名（从 .txt 的"建筑名:"行读，空则用 buildingId）</li>
 *   <li><b>format</b>     建筑蓝图格式：".nbt" / ".litematic" / ".schem"</li>
 *   <li><b>packName</b>   源文件名（无扩展名）。新格式下 packName == buildingId</li>
 *   <li><b>sha1</b>       源文件（zip 或 nbt）内容 SHA-1，用于比对本地缓存</li>
 *   <li><b>size</b>       源文件字节数，用于进度条</li>
 *   <li><b>author</b>     作者（从 .txt 解析）</li>
 *   <li><b>description</b> 描述（从 .txt 解析）</li>
 *   <li><b>pngData</b>    缩略图 PNG 字节。空字节数组表示无图</li>
 * </ul>
 */
public record ServerPackManifestPayload(
        List<Entry> buildings
) {

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

    public static void encode(ServerPackManifestPayload msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.buildings().size());
        for (Entry e : msg.buildings()) {
            writeEntry(buf, e);
        }
    }

    public static ServerPackManifestPayload decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Entry> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(readEntry(buf));
        }
        return new ServerPackManifestPayload(list);
    }

    /** Entry 编码。字段顺序: name, sha1, size, buildingId, displayName, format,
     *  packName, sourceFileName, author, description, pngData. */
    private static void writeEntry(FriendlyByteBuf buf, Entry e) {
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

    private static Entry readEntry(FriendlyByteBuf buf) {
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
}
