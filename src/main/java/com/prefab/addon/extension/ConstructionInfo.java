package com.prefab.addon.extension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;

public class ConstructionInfo {
    private final String id;
    private String name;
    private String author;
    private String size;
    /** 蓝图格式: nbt / litematic / schem / 未知. 空 = 未知 (旧建筑). */
    private String format;
    private String description;
    private List<String> dependencies;
    /** 蓝图显示图标 - 物品 id (如 "minecraft:stone"). 空 = 使用默认图标. */
    private String icon;
    private ZipEntry nbtEntry;
    private ZipEntry pngEntry;
    private ExtensionPack pack;
    private byte[] pngData; // 缓存的PNG图片数据
    private byte[] nbtData; // 缓存的NBT数据
    /**
     * 本地图片路径 (单文件建筑用).
     * 非空时 {@link #hasPreviewImage()} 返回 true, {@link #getPngData()} 优先从 path 懒加载.
     * 与 pack.zip 模式互斥: zip 包内走 pngData 缓存, 单文件走 localImagePath 实时读.
     */
    private Path localImagePath;
    /**
     * 本地 NBT/Schematic 路径 (单文件建筑用).
     * 非空时 {@link #getNbtData()} 懒加载读这个文件. zip 模式下保持 null, 走 nbtData 缓存.
     */
    private Path localNbtPath;

    public ConstructionInfo(String id) {
        this.id = id;
        this.name = id;
    }

    public String getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getSize() { return size; }
    public void setSize(String size) { this.size = size; }

    /**
     * 蓝图格式 (nbt / litematic / schem). 创建建筑时用户填的, 保存到 construction/<id>.txt 里.
     * <p>空字符串 = "未知" (旧建筑没填过格式字段, 或加载失败).</p>
     */
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }

    /** 显示用的格式字符串: 永远不返回 null, 空白也按"未知"算. */
    public String getFormatDisplay() {
        if (format == null || format.isBlank()) return "未知";
        return format;
    }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getDependencies() { return dependencies; }
    public void setDependencies(List<String> dependencies) { this.dependencies = dependencies; }

    /** 蓝图显示图标: 物品 id, 如 "minecraft:stone". 空表示用默认. */
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }

    public ZipEntry getNbtEntry() { return nbtEntry; }
    public void setNbtEntry(ZipEntry nbtEntry) { this.nbtEntry = nbtEntry; }

    public ZipEntry getPngEntry() { return pngEntry; }
    public void setPngEntry(ZipEntry pngEntry) { this.pngEntry = pngEntry; }

    public byte[] getPngData() { return pngData; }
    public void setPngData(byte[] pngData) { this.pngData = pngData; }

    public byte[] getNbtData() {
        if (nbtData != null && nbtData.length > 0) return nbtData;
        // 单文件建筑: 懒加载
        if (localNbtPath != null && Files.exists(localNbtPath)) {
            try {
                return Files.readAllBytes(localNbtPath);
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }
    public void setNbtData(byte[] nbtData) { this.nbtData = nbtData; }

    /** 单文件建筑: 设置 NBT/Schematic 路径 (用于懒加载). */
    public Path getLocalNbtPath() { return localNbtPath; }
    public void setLocalNbtPath(Path p) { this.localNbtPath = p; }

    public ExtensionPack getPack() { return pack; }
    public void setPack(ExtensionPack pack) { this.pack = pack; }

    /**
     * 是否有预览图. 优先看缓存的 pngData, 再看 localImagePath (单文件建筑).
     */
    public boolean hasPreviewImage() {
        if (pngData != null && pngData.length > 0) return true;
        return localImagePath != null && Files.exists(localImagePath);
    }

    /**
     * 单文件建筑用: 读取本地图片路径.
     */
    public Path getLocalImagePath() { return localImagePath; }
    public void setLocalImagePath(Path p) { this.localImagePath = p; }

    /**
     * 获取预览图字节. 优先返回缓存 pngData; 单文件建筑从 localImagePath 懒加载.
     */
    public byte[] getPreviewBytes() {
        if (pngData != null && pngData.length > 0) return pngData;
        if (localImagePath != null && Files.exists(localImagePath)) {
            try {
                return Files.readAllBytes(localImagePath);
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }
}

