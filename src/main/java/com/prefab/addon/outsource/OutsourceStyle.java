package com.prefab.addon.outsource;

/**
 * 单个外包建筑的"风格" —— 子文件夹里的一个投影文件。
 *
 * 字段:
 *   - fileName: 文件名(用作显示时的副标题)
 *   - nbtData: 文件原始字节(还没解析成 ConstructionInfo)
 *
 * 风格字节在 mod 启动时一次性全部读进内存(玩家右键后秒开,不等 IO)。
 */
public final class OutsourceStyle {
    private final String fileName;
    private final byte[] nbtData;
    /** 原始后缀名 (.nbt / .litematic / .schem),用于决定解析方式 */
    private final String fileExt;

    public OutsourceStyle(String fileName, byte[] nbtData, String fileExt) {
        this.fileName = fileName;
        this.nbtData = nbtData;
        this.fileExt = fileExt == null ? ".nbt" : fileExt.toLowerCase();
    }

    public String getFileName() { return fileName; }
    public byte[] getNbtData() { return nbtData; }
    public String getFileExt() { return fileExt; }

    /**
     * 给 GUI 显示用:把 "茶楼_主楼.litematic" 切成 "茶楼_主楼"
     */
    public String getDisplayName() {
        if (fileName == null) return "?";
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
