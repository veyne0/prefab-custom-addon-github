package com.prefab.addon.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 建筑分类管理 (用户自定义的 10 个分类).
 *
 * <p>"未分类" 是隐式默认分类, 永远存在, 不存进 list.
 * 玩家在 {@code GuiCategoryManager} 里加的分类写到这里, 然后:</p>
 * <ul>
 *   <li>{@code GuiCreateBuildingInfo} / {@code GuiExtensionPackEditor} 里的分类下拉</li>
 *   <li>{@code GuiExtensionPackBrowser} 建筑 tab 右侧分类列表</li>
 * </ul>
 *
 * <p>每个本地建筑 (.txt 文件) 写一行 {@code 分类: <名称>}; 不写 = 未分类.
 * 读取时如果 {@code category} 在 {@link #getCategories()} 里找不到, 仍然显示原值
 * (允许玩家删了分类后老建筑仍带原分类名字, 不丢失信息).</p>
 *
 * <p>持久化: {@code <gameDir>/config/prefab_addon/categories.json}.</p>
 */
public class CategoryManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static CategoryManager instance;
    private static final Object LOCK = new Object();

    /** 玩家自定义的分类列表 (顺序敏感, GUI 按此顺序显示). 最多 10 个, 由 GUI 创建时限制. */
    public List<String> categories = new ArrayList<>();

    /** 隐藏的"未分类"分类, 永远不写进 JSON.  */
    public static final String UNCATEGORIZED = "未分类";

    /** 自定义分类最大数量 (10 个, 按用户要求). */
    public static final int MAX_CATEGORIES = 10;

    public CategoryManager() {}

    public static CategoryManager get() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = load();
                }
            }
        }
        return instance;
    }

    /** 返回所有分类 (含"未分类"在第一位), 永远不为空, 永远不重复. */
    public List<String> getCategories() {
        List<String> all = new ArrayList<>(categories.size() + 1);
        all.add(UNCATEGORIZED);
        for (String c : categories) {
            if (c != null && !c.isBlank() && !all.contains(c)) {
                all.add(c);
            }
        }
        return all;
    }

    /** 是否还能添加新分类 (上限 10). */
    public boolean canAdd() {
        return categories.size() < MAX_CATEGORIES;
    }

    /**
     * 添加分类. 自动去重 + 长度限制 (避免 UI 装不下).
     * @return true = 成功; false = 重复 / 已达上限 / 非法名称
     */
    public boolean addCategory(String name) {
        if (name == null) return false;
        String n = name.trim();
        if (n.isEmpty()) return false;
        if (UNCATEGORIZED.equals(n)) return false;  // 不能加 "未分类"
        if (n.length() > 16) n = n.substring(0, 16);  // 截断到 16 字符
        for (String c : categories) {
            if (c.equalsIgnoreCase(n)) return false;  // 重复
        }
        if (categories.size() >= MAX_CATEGORIES) return false;
        categories.add(n);
        save();
        return true;
    }

    /**
     * 删除分类. 不影响已经分类到该名的建筑 (.txt 里的 "分类: 老名字" 仍保留, 列表里查不到时显示原值).
     * @return true = 成功删除; false = 找不到
     */
    public boolean removeCategory(String name) {
        if (name == null) return false;
        for (int i = 0; i < categories.size(); i++) {
            if (categories.get(i).equals(name)) {
                categories.remove(i);
                save();
                return true;
            }
        }
        return false;
    }

    /** 重命名分类. 老名字不存在或新名字重复都返回 false. */
    public boolean renameCategory(String oldName, String newName) {
        if (oldName == null || newName == null) return false;
        String n = newName.trim();
        if (n.isEmpty() || UNCATEGORIZED.equals(n)) return false;
        if (n.length() > 16) n = n.substring(0, 16);
        int idx = -1;
        for (int i = 0; i < categories.size(); i++) {
            if (categories.get(i).equals(oldName)) { idx = i; break; }
        }
        if (idx < 0) return false;
        for (int i = 0; i < categories.size(); i++) {
            if (i != idx && categories.get(i).equalsIgnoreCase(n)) return false;
        }
        categories.set(idx, n);
        save();
        return true;
    }

    /** 用于调试日志: 当前所有分类. */
    public String dumpForLog() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < categories.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(categories.get(i));
        }
        sb.append("] (共 ").append(categories.size()).append("/").append(MAX_CATEGORIES).append(")");
        return sb.toString();
    }

    // === 持久化 ===

    private static Path getCategoryFile() {
        Path gameDir;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.gameDirectory != null) {
                gameDir = mc.gameDirectory.toPath();
            } else {
                gameDir = FMLPaths.GAMEDIR.get();
            }
        } catch (Throwable ignored) {
            gameDir = FMLPaths.GAMEDIR.get();
        }
        return gameDir.resolve("config").resolve("prefab_addon").resolve("categories.json");
    }

    private static CategoryManager load() {
        Path file = getCategoryFile();
        if (!Files.exists(file)) {
            PrefabCustomAddon.LOGGER.info("[CATEGORY] 分类文件不存在, 使用空列表: {}", file);
            return new CategoryManager();
        }
        try (Reader r = Files.newBufferedReader(file)) {
            CategoryManager m = GSON.fromJson(r, CategoryManager.class);
            if (m == null) m = new CategoryManager();
            // 防御: 加载后裁掉超过 10 个的 (理论上不会出现, 但被改 JSON 的话兜个底)
            if (m.categories.size() > MAX_CATEGORIES) {
                m.categories = new ArrayList<>(m.categories.subList(0, MAX_CATEGORIES));
            }
            PrefabCustomAddon.LOGGER.info("[CATEGORY] 加载分类: {}", m.dumpForLog());
            return m;
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("[CATEGORY] 加载分类失败, 用空列表: {}", e.getMessage());
            return new CategoryManager();
        }
    }

    public synchronized void save() {
        Path file = getCategoryFile();
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                GSON.toJson(this, w);
            }
            PrefabCustomAddon.LOGGER.info("[CATEGORY] 保存分类: {}", dumpForLog());
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.warn("[CATEGORY] 保存分类失败: {}", e.getMessage());
        }
    }
}
