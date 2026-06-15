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
    
    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
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
}
