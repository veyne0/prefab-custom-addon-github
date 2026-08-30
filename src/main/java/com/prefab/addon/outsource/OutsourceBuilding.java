package com.prefab.addon.outsource;

import com.prefab.addon.extension.ConstructionInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 单个外包建筑 = ZIP 里 1 个子文件夹的所有"风格"。
 *
 * ID 规则: {@code <zipBaseName>_<folderName>}
 *   - zipBaseName: 去掉 .zip 后缀的 ZIP 文件名(防止不同 ZIP 里出现重名子文件夹)
 *   - folderName: 子文件夹名(中文,作为 GUI 显示名)
 *
 * 稳定 ID 用于 ItemStack NBT 标识,RIGHT-CLICK 后 ClientHandler 用 ID 找回来。
 */
public final class OutsourceBuilding {

    /**
     * 需要在 litematic 解析时强制 Y 翻转的子文件夹名（中文 folderName）。
     *
     * <p>目前只有 {@code 冒险者酒馆} —— 这份 litematic 用了 "Y=0 在顶部" 的反向约定
     * （size 是正数，但实际 Y 坐标是"屋顶→地基"方向），不翻的话整栋楼会上下颠倒。</p>
     *
     * <p>以后遇到其他同样问题的建筑，追加 folderName 到这里即可。匹配按
     * {@link String#equals(Object)} 精确匹配 folderName（中文名）。</p>
     */
    private static final Set<String> FLIP_Y_FOLDER_NAMES = Set.of("冒险者酒馆");

    /**
     * 判定某个子文件夹对应的 litematic 是否需要在解析时强制 Y 翻转。
     *
     * @param folderName ZIP 内的子文件夹名（中文明文）
     * @return true = 需要在 {@code LitematicaParser} 解析时传 {@code forceFlipY=true}
     */
    public static boolean shouldFlipY(String folderName) {
        return folderName != null && FLIP_Y_FOLDER_NAMES.contains(folderName);
    }

    private final String id;
    private final String folderName;
    private final String author;
    private final String zipSource;
    private final List<OutsourceStyle> styles;
    /** 缓存:每个 styleIndex 对应的 ConstructionInfo(按需构造) */
    private final List<ConstructionInfo> constructionCache;

    public OutsourceBuilding(String id, String folderName, String author, String zipSource, List<OutsourceStyle> styles) {
        this.id = id;
        this.folderName = folderName;
        this.author = author;
        this.zipSource = zipSource;
        this.styles = Collections.unmodifiableList(new ArrayList<>(styles));
        this.constructionCache = new ArrayList<>(Collections.nCopies(styles.size(), null));
    }

    public String getId() { return id; }
    public String getFolderName() { return folderName; }
    public String getAuthor() { return author; }
    public String getZipSource() { return zipSource; }
    public List<OutsourceStyle> getStyles() { return styles; }
    public int getStyleCount() { return styles.size(); }
    public boolean hasMultipleStyles() { return styles.size() >= 2; }

    /**
     * 取出第 styleIdx 个风格对应的 ConstructionInfo(懒构造,带 NBT 字节)。
     * 返回的 ConstructionInfo 没有 pack 引用(单文件建筑,走 localNbtPath 路径)。
     */
    public ConstructionInfo getConstruction(int styleIdx) {
        if (styleIdx < 0 || styleIdx >= styles.size()) {
            return null;
        }
        ConstructionInfo cached = constructionCache.get(styleIdx);
        if (cached != null) return cached;
        OutsourceStyle s = styles.get(styleIdx);
        // 用 "<id>_style_<idx>" 作 constructionId,确保唯一
        String cid = id + "_style_" + styleIdx;
        ConstructionInfo info = new ConstructionInfo(cid);
        info.setName(s.getDisplayName());
        info.setAuthor(author);
        // 把字节存到 nbtData(单文件建筑走 nbtData 分支,无需 localNbtPath)
        info.setNbtData(s.getNbtData());
        // format 字段:用户能看到 "litematic / nbt"
        String ext = s.getFileExt();
        if (ext != null && ext.startsWith(".")) ext = ext.substring(1);
        info.setFormat(ext);
        constructionCache.set(styleIdx, info);
        return info;
    }

    /**
     * 找下一个风格索引(到达末尾循环回 0)。
     */
    public int nextStyleIndex(int current) {
        if (styles.isEmpty()) return 0;
        int n = styles.size();
        return ((current + 1) % n + n) % n;
    }
}
