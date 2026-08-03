package com.prefab.addon.extension;

/**
 * "服务器" 标签页里展示的一个建筑条目.
 * <p>从 {@link com.prefab.addon.network.ServerPackManifestPayload.Entry} 解析而来,
 * 加上"是否已同步到本地 server-cache/"的状态字段.</p>
 *
 * <p>注意: 未同步的建筑只有元数据 (name, sha1, size), 没有 name/author/description/png
 * 等详细元信息 (这些要从文件里读). 因此未同步卡片只显示基础信息 + 状态, 不显示缩略图.
 * 同步后玩家才能在"建筑" tab 看到完整信息.</p>
 */
public class ServerBuildingInfo {
    /** 包名 (= 文件名去后缀, 跟 manifest entry 的 name 字段一致) */
    public final String name;
    /** 文件 SHA-1 (40 位小写 hex) */
    public final String sha1;
    /** 文件大小 (字节) */
    public final long size;
    /** 本地 server-cache/ 是否有匹配的缓存 */
    public final boolean synced;
    /** 推断的文件扩展名 (.nbt / .litematic / .schem / .zip), 用于显示 */
    public final String extension;

    public ServerBuildingInfo(String name, String sha1, long size, boolean synced, String extension) {
        this.name = name;
        this.sha1 = sha1;
        this.size = size;
        this.synced = synced;
        this.extension = extension;
    }

    public String getDisplayName() {
        return name == null || name.isEmpty() ? "未命名" : name;
    }

    public String getShortSha1() {
        if (sha1 == null || sha1.length() < 8) return sha1 == null ? "" : sha1;
        return sha1.substring(0, 8);
    }
}
