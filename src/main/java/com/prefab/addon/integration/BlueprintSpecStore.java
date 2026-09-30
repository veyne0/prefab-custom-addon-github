package com.prefab.addon.integration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.download.PackDownloadManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * KubeJS 联动蓝图的"默认绑定规格"存储.
 *
 * <p>背景: 生成器以前在 startup script 里用 KubeJS 1.21 (2100.x) 的 {@code item.setCustomData}
 * 给蓝图物品烤默认 NBT (packName/constructionId/locked), 但 1.20.1 的 KubeJS (2001.x) 没有
 * 这个 API, 启动直接 {@code TypeError: Cannot find function setCustomData}.
 * 修复方案 (版本无关): 脚本只负责注册物品 + tag + 配方, 默认绑定数据由模组自己解析 —
 * 生成时把规格写成 JSON 文件, 读绑定处按 "物品栈 NBT 优先 → 规格文件兜底" 解析.</p>
 *
 * <p>存储位置: {@code prefab-extension/.blueprint-specs/<itemId>.json}.
 * root 用 {@link PackDownloadManager#getExtensionRoot()} (基于 user.dir 推导,
 * 物理客户端 / dedicated server 都能解析, 不写死绝对路径).</p>
 */
public final class BlueprintSpecStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 一条蓝图规格 (生成器写入, 读取处兜底用). */
    public record SpecEntry(String namespace, String itemId, String displayName,
                            String packName, String constructionId, boolean locked) {}

    /** 解析出来的绑定 (等价于老 NBT 的 packName/constructionId/locked 三件套). */
    public record Binding(String packName, String constructionId, boolean locked) {}

    private BlueprintSpecStore() {}

    /** 规格目录: {@code <prefab-extension>/.blueprint-specs/}. */
    public static Path specsDir() {
        return PackDownloadManager.getExtensionRoot().resolve(".blueprint-specs");
    }

    /** 单条规格文件路径: {@code <specsDir>/<itemId>.json}. */
    public static Path specPath(String itemId) {
        return specsDir().resolve(itemId + ".json");
    }

    /**
     * 写规格文件. 目录不存在自动创建; 失败只 warn 不抛
     * (规格缺失只影响默认绑定, 不该阻断蓝图生成流程).
     */
    public static void writeSpec(SpecEntry entry) {
        try {
            Files.createDirectories(specsDir());
            JsonObject o = new JsonObject();
            o.addProperty("namespace", entry.namespace());
            o.addProperty("itemId", entry.itemId());
            o.addProperty("displayName", entry.displayName());
            o.addProperty("packName", entry.packName());
            o.addProperty("constructionId", entry.constructionId());
            o.addProperty("locked", entry.locked());
            Files.writeString(specPath(entry.itemId()), GSON.toJson(o), StandardCharsets.UTF_8);
            PrefabCustomAddon.LOGGER.info("[BLUEPRINT-SPEC] 规格已写入: {} (pack={}/{} locked={})",
                specPath(entry.itemId()), entry.packName(), entry.constructionId(), entry.locked());
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("[BLUEPRINT-SPEC] 写规格文件失败 ({}): {}",
                entry.itemId(), e.toString());
        }
    }

    /** 按 itemId 读规格. 文件不存在 / JSON 坏了返回 null. */
    public static SpecEntry readSpec(String itemId) {
        if (itemId == null || itemId.isEmpty()) return null;
        Path p = specPath(itemId);
        if (!Files.isRegularFile(p)) return null;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8))
                .getAsJsonObject();
            return new SpecEntry(
                o.has("namespace") ? o.get("namespace").getAsString() : "",
                itemId,
                o.has("displayName") ? o.get("displayName").getAsString() : itemId,
                o.has("packName") ? o.get("packName").getAsString() : "",
                o.has("constructionId") ? o.get("constructionId").getAsString() : "",
                o.has("locked") && o.get("locked").getAsBoolean());
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("[BLUEPRINT-SPEC] 读规格文件失败 ({}): {}", itemId, e.toString());
            return null;
        }
    }

    /** 删规格文件 (蓝图管理 tab 删除蓝图时一起清掉). 失败只 warn. */
    public static void deleteSpec(String itemId) {
        try {
            Files.deleteIfExists(specPath(itemId));
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("[BLUEPRINT-SPEC] 删规格文件失败 ({}): {}", itemId, e.toString());
        }
    }

    /** 物品栈 NBT 里是否直接存了绑定 (玩家绑定过的蓝图). */
    public static boolean hasStackBinding(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains("packName") && tag.contains("constructionId");
    }

    /**
     * 解析蓝图绑定. 优先级: 物品栈 NBT (玩家绑定过的) 优先 → NBT 为空时按物品 id 查
     * {@code prefab-extension/.blueprint-specs/<itemId>.json} → 都没有返回 null (未绑定).
     *
     * <p>common 代码: 服务端建造链路 ({@code AsyncBuildManager}) 和客户端
     * (handler / tooltip) 都可以调, 不依赖任何 client 类.</p>
     */
    public static Binding resolveBinding(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            String p = tag.contains("packName") ? tag.getString("packName") : "";
            String c = tag.contains("constructionId") ? tag.getString("constructionId") : "";
            if (!p.isEmpty() && !c.isEmpty()) {
                return new Binding(p, c, tag.getBoolean("locked"));
            }
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        SpecEntry e = readSpec(key == null ? "" : key.getPath());
        if (e != null && !e.packName().isEmpty() && !e.constructionId().isEmpty()) {
            return new Binding(e.packName(), e.constructionId(), e.locked());
        }
        return null;
    }

    /**
     * 解析 locked 状态: NBT 有 locked 字段用 NBT, 否则规格文件, 都没有 false.
     */
    public static boolean resolveLocked(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains("locked")) return tag.getBoolean("locked");
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        SpecEntry e = readSpec(key == null ? "" : key.getPath());
        return e != null && e.locked();
    }
}
