package com.prefab.addon.extension;

/**
 * "服务器" 标签页里展示的一个建筑条目.
 * <p>从 {@link com.prefab.addon.network.ServerPackManifestPayload.Entry} 解析而来,
 * 加上"是否已同步到本地 server-cache/"的状态字段.</p>
 *
 * <p><strong>已废弃"拓展包"概念</strong>：每个条目就是一个建筑, 不是文件/包.
 * 对于老式 .zip 拓展包, 一个包里的 N 个建筑会展开为 N 个 ServerBuildingInfo,
 * 它们共享 packName (源 zip 的 basename) 和 sha1/size (源 zip 的指纹和大小).
 * 对于新格式独立 .nbt/.litematic/.schem, packName = buildingId, 每个独立.</p>
 */
public class ServerBuildingInfo {
    /** 建筑 id (= 文件名去后缀, 跟 manifest entry 的 buildingId/name 字段一致) */
    public final String buildingId;
    /** 友好显示名 (中文等, 来自 .txt 的"建筑名:"行, 缺省回退到 buildingId) */
    public final String displayName;
    /** 作者 (来自 .txt 的"作者:"行, 可能空) */
    public final String author;
    /** 描述 (来自 .txt 的"描述:"行, 可能空) */
    public final String description;
    /** 源文件名 (e.g. "test.zip" / "huochaihe.nbt") - 用于同步时定位缓存 */
    public final String sourceFileName;
    /** 源文件 basename (无扩展名, 跟 packName 一致) - 老 zip 多建筑共享 */
    public final String packName;
    /** 源文件 SHA-1 (40 位小写 hex) */
    public final String sha1;
    /** 源文件大小 (字节) */
    public final long size;
    /** 缩略图 PNG 字节 (来自 zip 内 .png 或同前缀 .png, 可能空) */
    public final byte[] pngData;
    /** 本地 server-cache/ 是否有匹配的缓存 (即 sourceFileName 在缓存目录里) */
    public final boolean synced;

    public ServerBuildingInfo(String buildingId, String displayName, String author, String description,
                              String sourceFileName, String packName,
                              String sha1, long size, byte[] pngData, boolean synced) {
        this.buildingId = buildingId;
        this.displayName = displayName;
        this.author = author == null ? "" : author;
        this.description = description == null ? "" : description;
        this.sourceFileName = sourceFileName;
        this.packName = packName == null ? buildingId : packName;
        this.sha1 = sha1;
        this.size = size;
        this.pngData = pngData == null ? new byte[0] : pngData;
        this.synced = synced;
    }

    /** 便利构造: 旧 5 字段形式 (兼容旧调用方) */
    public ServerBuildingInfo(String name, String sha1, long size, boolean synced, String extension) {
        this(name, name, "", "",
                name + (extension == null ? ".zip" : extension),
                name, sha1, size, new byte[0], synced);
    }

    public String getDisplayName() {
        if (displayName != null && !displayName.isEmpty()) return displayName;
        return buildingId == null || buildingId.isEmpty() ? "未命名" : buildingId;
    }

    public String getShortSha1() {
        if (sha1 == null || sha1.length() < 8) return sha1 == null ? "" : sha1;
        return sha1.substring(0, 8);
    }

    /** 是否有缩略图. */
    public boolean hasThumbnail() {
        return pngData != null && pngData.length > 0;
    }

    /** 取得文件后缀 (含前导点, 如 ".zip" / ".nbt"). */
    public String getSourceExt() {
        if (sourceFileName == null) return ".zip";
        int dot = sourceFileName.lastIndexOf('.');
        return dot < 0 ? ".zip" : sourceFileName.substring(dot);
    }
}
