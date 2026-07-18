package com.prefab.addon.extension;

import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ExtensionPack {
    private String packageName;
    private String name;
    private String author;
    private String description;
    private String url;
    private String filePath;
    private String fileName;
    private boolean hasCoverImage;
    private ZipEntry coverImageEntry;
    private ZipFile zipFile;
    private final List<ConstructionInfo> constructions = new ArrayList<>();
    private List<String> dependencies;
    private boolean hasInfoFolder;  // 是否有 information/ 子目录（Z键界面只显示这类）
    private byte[] coverImageData;  // 缓存的封面图数据
    private String version;          // 版本号（可选）
    private String contentSha1;      // zip 文件内容的 SHA-1（十六进制），用于服务器→客户端同步
    private long fileSize;           // zip 文件大小（字节），用于同步时显示进度
    private boolean serverBacked;    // true = 来自 server-cache/（服务器同步下来的），false = 本地手动放的
    private long mtimeMs;            // 文件最后修改时间戳（毫秒），用于扫描时跳过未变文件
    private boolean spongeSchematic; // true = 来自 .schem / .schematic 单文件（不是 zip 包）
    private long totalBlocks;        // 建筑总方块数（用于 GUI 排序/统计）

    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    @Override
    public String toString() { return name; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    /**
     * 蓝图文件格式 (按 fileName 后缀判断).
     * <ul>
     *   <li>{@code .litematic} 单文件包 → "litematic"</li>
     *   <li>{@code .schem} / {@code .schematic} 单文件包 → "schem"</li>
     *   <li>{@code .zip} 拓展包 → "nbt" (内部存的蓝图为 NBT)</li>
     *   <li>{@code .nbt} → "nbt"</li>
     *   <li>其他 → "未知"</li>
     * </ul>
     *
     * <p>注意: zip 拓展包的"蓝图文件"是其内部包含的 .nbt 建筑文件, 所以显示为 nbt.
     * 用户看到的应该是"这份建筑本身是什么格式", 而不是"包是什么格式".</p>
     */
    public String getBlueprintFormat() {
        if (fileName == null) return "未知";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".litematic")) return "litematic";
        if (lower.endsWith(".schem") || lower.endsWith(".schematic")) return "schem";
        if (lower.endsWith(".zip")) return "nbt";
        if (lower.endsWith(".nbt")) return "nbt";
        return "未知";
    }
    
    public boolean hasCoverImage() { return hasCoverImage; }
    public void setHasCoverImage(boolean hasCoverImage) { this.hasCoverImage = hasCoverImage; }
    
    public ZipEntry getCoverImageEntry() { return coverImageEntry; }
    public void setCoverImageEntry(ZipEntry coverImageEntry) { this.coverImageEntry = coverImageEntry; }
    
    public ZipFile getZipFile() { return zipFile; }
    public void setZipFile(ZipFile zipFile) { this.zipFile = zipFile; }
    
    public List<ConstructionInfo> getConstructions() { return constructions; }
    
    public List<String> getDependencies() { return dependencies; }
    public void setDependencies(List<String> dependencies) { this.dependencies = dependencies; }

    public boolean hasInfoFolder() { return hasInfoFolder; }
    public void setHasInfoFolder(boolean hasInfoFolder) { this.hasInfoFolder = hasInfoFolder; }

    public byte[] getCoverImageData() { return coverImageData; }
    public void setCoverImageData(byte[] coverImageData) { this.coverImageData = coverImageData; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getContentSha1() { return contentSha1; }
    public void setContentSha1(String contentSha1) { this.contentSha1 = contentSha1; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public boolean isServerBacked() { return serverBacked; }
    public void setServerBacked(boolean serverBacked) { this.serverBacked = serverBacked; }

    public long getMtimeMs() { return mtimeMs; }
    public void setMtimeMs(long mtimeMs) { this.mtimeMs = mtimeMs; }

    public boolean isSpongeSchematic() { return spongeSchematic; }
    public void setSpongeSchematic(boolean spongeSchematic) { this.spongeSchematic = spongeSchematic; }

    public long getTotalBlocks() { return totalBlocks; }
    public void setTotalBlocks(long totalBlocks) { this.totalBlocks = totalBlocks; }

    /**
     * 替换 constructions 列表 (单文件 schem 导入时用). 内部 list 是 final,
     * 外部要整体替换只能清空再 add, 所以提供这个便捷方法.
     */
    public void setConstructions(List<ConstructionInfo> list) {
        this.constructions.clear();
        if (list != null) this.constructions.addAll(list);
    }
}
