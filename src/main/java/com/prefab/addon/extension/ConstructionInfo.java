package com.prefab.addon.extension;

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
    private ZipEntry nbtEntry;
    private ZipEntry pngEntry;
    private ExtensionPack pack;
    private byte[] pngData; // 缓存的PNG图片数据
    private byte[] nbtData; // 缓存的NBT数据

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

    public ZipEntry getNbtEntry() { return nbtEntry; }
    public void setNbtEntry(ZipEntry nbtEntry) { this.nbtEntry = nbtEntry; }

    public ZipEntry getPngEntry() { return pngEntry; }
    public void setPngEntry(ZipEntry pngEntry) { this.pngEntry = pngEntry; }

    public byte[] getPngData() { return pngData; }
    public void setPngData(byte[] pngData) { this.pngData = pngData; }

    public byte[] getNbtData() { return nbtData; }
    public void setNbtData(byte[] nbtData) { this.nbtData = nbtData; }

    public ExtensionPack getPack() { return pack; }
    public void setPack(ExtensionPack pack) { this.pack = pack; }

    public boolean hasPreviewImage() { return pngData != null && pngData.length > 0; }
}

