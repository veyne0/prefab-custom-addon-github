package com.prefab.addon.blocks;

import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 迷你建筑外部存储: 把完整的建筑 NBT 写到世界目录的独立文件,
 * 避免 ItemStack 同步时被 Minecraft 1.21.1 客户端 2MB NbtAccounter 卡住.
 *
 * <p><b>为什么需要这个类</b>:
 * Minecraft 1.21.1 的 {@link NbtAccounter} 默认限制 2MB (2097152 bytes),
 * 服务端发的 NBT 超过这个值客户端就断开连接. 玩家捕获大建筑 (e.g. 48x96x59 = 271k 块
 * ≈ 2MB) 拿在手里, 服务端发 container_set_slot 同步 ItemStack 时会触发崩溃.
 *
 * <p><b>解决方案</b>:
 * <ul>
 *   <li>完整建筑 NBT 写到世界目录文件, 没有 2MB 限制 (实测 100MB 也能写)</li>
 *   <li>ItemStack 只存引用 (&lt; 1KB): UUID + 元数据 (尺寸/块数/作者/名字)</li>
 *   <li>BE 加载时按需读文件, 玩家放置/查看时才有完整数据</li>
 * </ul>
 *
 * <p><b>文件路径</b>: {@code <worldDir>/data/prefab_custom_addon/mini_buildings/<uuid>.nbt}.
 * 用 {@link NbtIo#writeCompressed} 二进制压缩写, 比 CompoundTag.toString() 紧凑 5-10x.
 *
 * <p><b>跨世界</b>: 文件绑定到 worldDir. 玩家把迷你建筑 ItemStack 带到别的世界,
 * 引用就找不到文件了. 这种情况在 BE 加载时检测, 给玩家清晰提示.
 */
public final class MiniBuildingStorage {

    /** ItemStack / BE 同步 NBT 中存储完整数据的 key. 不存这个 key 表示"用文件引用". */
    public static final String KEY_FULL_DATA = "MiniBuilding";
    /** 引用 NBT 中的 UUID key. */
    public static final String KEY_REF_ID = "ref_id";
    /** 引用 NBT 中的名字 key (玩家自取的, 可选). */
    public static final String KEY_REF_NAME = "ref_name";
    /** 引用 NBT 中的创建时间 key. */
    public static final String KEY_REF_CREATED = "ref_created";
    /** 引用 NBT 中的作者 key. */
    public static final String KEY_REF_AUTHOR = "ref_author";
    /** 引用 NBT 中的尺寸 key. */
    public static final String KEY_REF_WIDTH = "ref_width";
    public static final String KEY_REF_HEIGHT = "ref_height";
    public static final String KEY_REF_DEPTH = "ref_depth";
    public static final String KEY_REF_BLOCK_COUNT = "ref_block_count";

    private MiniBuildingStorage() {}

    /**
     * 获取迷你建筑数据目录, 不存在则创建.
     * 路径: {@code <worldDir>/data/prefab_custom_addon/mini_buildings/}
     */
    public static Path getStorageDir(Level level) {
        if (level == null) return null;
        Path worldDir = level.getServer() != null
            ? level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
            : null;
        if (worldDir == null) return null;
        Path dir = worldDir.resolve("data/prefab_custom_addon/mini_buildings");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("[MINI_BUILDING] 创建数据目录失败: {}", dir, e);
            return null;
        }
        return dir;
    }

    /**
     * 把完整建筑 NBT 写到文件. 文件名用 UUID.
     *
     * @return 写入成功返回 true, 失败返回 false (并 log)
     */
    public static boolean save(Level level, String id, CompoundTag fullData) {
        Path dir = getStorageDir(level);
        if (dir == null || id == null || id.isEmpty() || fullData == null) {
            PrefabCustomAddon.LOGGER.error("[MINI_BUILDING] 保存失败: dir/id/data 为空");
            return false;
        }
        Path file = dir.resolve(id + ".nbt");
        try {
            NbtIo.writeCompressed(fullData, file);
            PrefabCustomAddon.LOGGER.info(
                "[MINI_BUILDING] 已保存: {} ({} 字节, {} KB)",
                file.getFileName(), Files.size(file), Files.size(file) / 1024);
            return true;
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("[MINI_BUILDING] 写入失败: {}", file, e);
            return false;
        }
    }

    /**
     * 从文件读完整建筑 NBT.
     *
     * @return 成功返回 NBT, 失败 (文件不存在/IO 错误) 返回 null
     */
    public static CompoundTag load(Level level, String id) {
        if (level == null || id == null || id.isEmpty()) return null;
        Path dir = getStorageDir(level);
        if (dir == null) return null;
        Path file = dir.resolve(id + ".nbt");
        PrefabCustomAddon.LOGGER.info(
            "[MINI_BUILDING] [DEBUG] load: dir={}, file={}, exists={}",
            dir, file, Files.exists(file));
        if (!Files.exists(file)) {
            return null;
        }
        try {
            // [FIX] 文件由 writeCompressed() 写入 (GZIP 格式), 必须用 readCompressed() 读.
            //   之前用 NbtIo.read(DataInput) 读 raw 格式, 报 UTFDataFormatException (前几字节是 GZIP 头 1F 8B)
            //   或 EOFException (被当作 UTF 字符串长度读, 越界).
            //   readCompressed(Path) 内部会处理 GZIP 流.
            CompoundTag result = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            PrefabCustomAddon.LOGGER.info(
                "[MINI_BUILDING] [DEBUG] load: success, topKeys={}, fileLen={}",
                result.getAllKeys(), Files.size(file));
            return result;
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error(
                "[MINI_BUILDING] [DEBUG] 读取失败: file={}, exceptionClass={}, msg={}",
                file, e.getClass().getName(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * 删除文件 (玩家丢物品/拆方块时清理).
     */
    public static boolean delete(Level level, String id) {
        if (level == null || id == null || id.isEmpty()) return false;
        Path dir = getStorageDir(level);
        if (dir == null) return false;
        Path file = dir.resolve(id + ".nbt");
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.warn("[MINI_BUILDING] 删除失败: {}", file, e);
            return false;
        }
    }

    /**
     * 生成新 UUID (用于新捕获的迷你建筑).
     */
    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 判断一个 CompoundTag 是"引用 NBT" (只有 ref_* 字段) 还是"完整 NBT"
     * (有 MiniBuilding / blocks 字段).
     *
     * <p>用于兼容: 老的存档可能直接存完整 NBT, 新版本识别后自动迁移到文件.
     */
    public static boolean isReference(CompoundTag tag) {
        return tag != null && tag.contains(KEY_REF_ID);
    }

    /**
     * 迁移玩家背包里的老版本迷你建筑 ItemStack: 把完整 NBT 写到文件 + 替换 NBT 为引用.
     *
     * <p>用于 mod 升级场景: 1.9 之前的 ItemStack 内嵌完整 NBT (可能 2MB+),
     * 玩家进游戏时 NbtAccounter 限制 2MB 触发崩溃. 这个方法把老 ItemStack 改成
     * "文件 + 引用" 格式, 避免客户端同步时崩溃.
     *
     * <p>调用时机: 玩家登录 (PlayerLoggedInEvent) 时由 PrefabCustomAddon 触发.
     *
     * @param player 服务端玩家
     * @return 成功迁移的 ItemStack 数量
     */
    public static int migrateOldItemStacks(ServerPlayer player) {
        if (player == null) return 0;
        Inventory inv = player.getInventory();
        int migrated = 0;
        long totalBytes = 0;

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() != PrefabCustomAddon.MINI_BUILDING_BLOCK_ITEM.get()) continue;

            CustomData data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
            if (data == null) continue;
            CompoundTag beTag = data.copyTag();
            if (!beTag.contains("MiniBuilding")) continue;
            CompoundTag mb = beTag.getCompound("MiniBuilding");

            // 已经是引用, 跳过
            if (isReference(mb)) continue;
            // 没有 blocks 字段, 跳过 (无效数据)
            if (!mb.contains("blocks")) continue;

            // 是老版本完整 NBT, 迁移: 写文件 + 替换为引用
            String id = mb.getString("id");
            if (id.isEmpty()) {
                // 老存档可能没 id, 补一个新的
                id = newId();
                mb.putString("id", id);
            }

            // 写文件 (mini_buildings/<uuid>.nbt)
            boolean saved = save(player.level(), id, mb);
            if (!saved) {
                PrefabCustomAddon.LOGGER.warn(
                    "[MINI_BUILDING] 迁移 ItemStack @ slot {} 失败: 写文件 id={} 失败",
                    i, id);
                continue;
            }

            // 替换 NBT 为引用 (< 1KB)
            CompoundTag ref = new CompoundTag();
            ref.putString(KEY_REF_ID, id);
            ref.putString(KEY_REF_NAME, "");
            ref.putLong(KEY_REF_CREATED, mb.getLong("created"));
            ref.putString(KEY_REF_AUTHOR, mb.getString("author"));
            ref.putInt(KEY_REF_WIDTH, mb.getInt("width"));
            ref.putInt(KEY_REF_HEIGHT, mb.getInt("height"));
            ref.putInt(KEY_REF_DEPTH, mb.getInt("depth"));
            ref.putInt(KEY_REF_BLOCK_COUNT, mb.getInt("block_count"));

            beTag.put("MiniBuilding", ref);
            stack.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(beTag));

            int stackSize = stack.getCount();
            int bytes = Math.max(1, mb.toString().length() / 2);
            totalBytes += bytes * stackSize;
            migrated += stackSize;
        }

        if (migrated > 0) {
            PrefabCustomAddon.LOGGER.info(
                "[MINI_BUILDING] 迁移了 {} 个迷你建筑 ItemStack (~{} MB) 给玩家 {}",
                migrated, totalBytes / (1024 * 1024), player.getName().getString());
            if (player.level().getServer() != null) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§a[迷你建筑] §7已迁移 §e" + migrated + " §7个老版本迷你建筑到新格式 (~"
                        + (totalBytes / (1024 * 1024)) + " MB), 不再触发 2MB 客户端崩溃"
                ));
            }
        }
        return migrated;
    }

    /**
     * 修复玩家背包里所有迷你建筑 ItemStack 的 BLOCK_ENTITY_DATA 顶层 "id" 字段.
     *
     * <p><b>为什么需要这个</b>:
     * 1.21.1 vanilla {@code ItemStack.save()} 反序列化 BLOCK_ENTITY_DATA 时要求
     * 顶层有 {@code "id"} 字段 (ResourceLocation 格式, 标识 BE 类型), 找不到就抛
     * "Missing id for entity" 异常, 整个世界保存就崩.
     *
     * <p>之前版本的 capture helper 通过 {@code BlockItem.setBlockEntityData} 写入,
     * 该方法内部会加 {@code id} 字段. 但 1.21.1 某些场景下这个加 {@code id} 的过程
     * 不可靠 (或者 helper 走的是另一条不经过它的路径), 导致玩家背包里的迷你建筑
     * 物品 NBT 没有 {@code id} 字段, 一退出/自动保存就崩.</p>
     *
     * <p>此方法在玩家登录时扫描背包, 凡是迷你建筑物品 + 有 BLOCK_ENTITY_DATA + 没
     * {@code id} 字段, 就给它补上, 避免下次存档崩溃.</p>
     */
    public static int fixMissingIdField(ServerPlayer player) {
        if (player == null) return 0;
        Inventory inv = player.getInventory();
        int fixed = 0;
        String expectedId = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE
            .getKey(PrefabBlockEntities.MINI_BUILDING_BE.get()).toString();

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() != PrefabCustomAddon.MINI_BUILDING_BLOCK_ITEM.get()) continue;

            CustomData data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
            if (data == null) continue;
            CompoundTag beTag = data.copyTag();
            if (beTag.contains("id")) continue;  // 已有, 跳过
            if (!beTag.contains("MiniBuilding")) continue;  // 不是迷你建筑 NBT, 跳过

            // 补 id 字段
            beTag.putString("id", expectedId);
            stack.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(beTag));
            fixed++;
            PrefabCustomAddon.LOGGER.info(
                "[MINI_BUILDING] 修复 ItemStack @ slot {} 缺失的 id 字段 (玩家 {})",
                i, player.getName().getString());
        }
        if (fixed > 0) {
            PrefabCustomAddon.LOGGER.info(
                "[MINI_BUILDING] 共修复 {} 个迷你建筑 ItemStack 缺失的 id 字段 (玩家 {})",
                fixed, player.getName().getString());
            if (player.level().getServer() != null) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§a[迷你建筑] §7已修复 §e" + fixed + " §7个迷你建筑物品的 id 字段, 防止存档崩溃"
                ));
            }
        }
        return fixed;
    }
}
