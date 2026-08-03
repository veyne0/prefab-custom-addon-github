package com.prefab.addon.extension;

import java.nio.file.Path;

/**
 * 本地单文件建筑 (.nbt/.schem/.litematic + .txt + .png 三件套).
 * <p>统一表示 prefab-extension/ 和 prefab-download/ 里以单文件方式存放的建筑.
 * 一个 LocalBuilding = 同前缀的 .nbt (或 .schem/.litematic) + .txt (元信息) + .png (预览图, 可选).</p>
 *
 * <p>与 {@link ConstructionInfo} 的区别:
 * <ul>
 *   <li>ConstructionInfo: zip 拓展包内的建筑, 数据来自 ZipEntry, 通过 pack 反向查找</li>
 *   <li>LocalBuilding: 磁盘上独立的三件套文件, 数据来自 .txt + .nbt + .png</li>
 * </ul>
 * </p>
 */
public class LocalBuilding {
    /** 建筑 id (= 不带扩展名的文件名, 同一前缀的 .nbt/.txt/.png 共用) */
    public final String id;
    /** 建筑名 (从 .txt 的 "建筑名:" 解析, 缺省回退到 id) */
    public final String name;
    /** 作者 (从 .txt 的 "作者:" 解析) */
    public final String author;
    /** 描述 (从 .txt 的 "描述:" 解析) */
    public final String description;
    /** 建筑文件扩展名, 含前导点, 如 ".nbt"/".schem"/".litematic" */
    public final String fileExt;
    /** 建筑文件大小, 字节 */
    public final long fileSize;
    /** 预览图扩展名, 含前导点, 如 ".png"/".jpg"; 无预览图时为 null */
    public final String imageExt;
    /** 来源: "extension" (prefab-extension/) 或 "download" (prefab-download/) */
    public final String source;

    /** .nbt/.schem/.litematic 完整路径 */
    public final Path filePath;
    /** .txt 元信息完整路径 (可能不存在, 缺省时仅用文件名兜底) */
    public final Path infoPath;
    /** .png/.jpg 完整路径 (可空) */
    public final Path imagePath;

    public LocalBuilding(String id, String name, String author, String description,
                         String fileExt, long fileSize, String imageExt, String source,
                         Path filePath, Path infoPath, Path imagePath) {
        this.id = id;
        this.name = name;
        this.author = author;
        this.description = description;
        this.fileExt = fileExt;
        this.fileSize = fileSize;
        this.imageExt = imageExt;
        this.source = source;
        this.filePath = filePath;
        this.infoPath = infoPath;
        this.imagePath = imagePath;
    }

    /** 是否有预览图文件. */
    public boolean hasPreviewImage() {
        return imagePath != null;
    }

    /** 显示名: 优先 name, 空则用 id. */
    public String getDisplayName() {
        if (name != null && !name.isBlank()) return name;
        return id;
    }
}
