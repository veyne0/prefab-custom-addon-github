package com.prefab.addon.work;

import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.nbt.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 解析 Minecraft .nbt 文件, 提取尺寸和用到的非 minecraft 模组。
 *
 * 支持的 size 格式 (按优先级尝试):
 *   1) size: [I; x, y, z]                    (标准 MC 结构方块, IntArray)
 *   2) size: {x, y, z} / {X, Y, Z}           (Compound)
 *   3) dimensions: {width, height, depth}    (某些模组)
 *   4) 从 blocks 列表的 pos 计算包围盒
 *
 * 模组 ID 从 palette[].Name (modid:block) 提取, minecraft 排除.
 */
public class NbtStructureParser {

    public static class NbtInfo {
        public final int sizeX, sizeY, sizeZ;
        public final List<String> modIds;        // 非 minecraft/prefab 的 mod id (排序去重)
        public final Map<String, Integer> blockUsage;  // 每个 mod 用到的方块数
        public final int totalBlocks;            // palette 总条目数
        public final String detectedFormat;      // 命中哪种格式
        // Litematica / Sponge 等带 metadata 的格式, 解析得到的元信息
        public final String metaName;            // null 表示没有
        public final String metaAuthor;
        public final String metaDescription;

        public NbtInfo(int x, int y, int z, List<String> modIds,
                       Map<String, Integer> usage, int totalBlocks, String fmt) {
            this(x, y, z, modIds, usage, totalBlocks, fmt, null, null, null);
        }

        public NbtInfo(int x, int y, int z, List<String> modIds,
                       Map<String, Integer> usage, int totalBlocks, String fmt,
                       String name, String author, String description) {
            this.sizeX = x; this.sizeY = y; this.sizeZ = z;
            this.modIds = modIds;
            this.blockUsage = usage;
            this.totalBlocks = totalBlocks;
            this.detectedFormat = fmt;
            this.metaName = name;
            this.metaAuthor = author;
            this.metaDescription = description;
        }

        public String sizeString() { return sizeX + "x" + sizeY + "x" + sizeZ; }
    }

    public static NbtInfo parse(Path nbtFile) throws IOException {
        return parse(Files.readAllBytes(nbtFile));
    }

    /**
     * 直接从 byte[] 解析 (供 CustomStructureGui 解析已缓存的 NBT 数据用)
     */
    public static NbtInfo parse(byte[] nbtData) throws IOException {
        if (nbtData == null || nbtData.length == 0) {
            throw new IOException("NBT 数据为空");
        }
        CompoundTag root = null;
        boolean compressed = false;
        // 1) 尝试压缩格式 (gzip 包裹)
        // 用 unlimitedHeap: vanilla 默认 2GB, 一些大 schematic (2M+ blocks) 解压后 67MB+ 没问题
        // 之前用 64MB 限制被 NbtAccounterException 抛出, 见 latest.log
        try (var bais = new java.io.ByteArrayInputStream(nbtData)) {
            root = NbtIo.readCompressed(bais, NbtAccounter.unlimitedHeap());
            compressed = true;
        } catch (IOException e) {
            // 2) 尝试未压缩
            try (var bais = new java.io.ByteArrayInputStream(nbtData)) {
                root = NbtIo.read(new java.io.DataInputStream(bais));
            }
        }
        if (root == null) {
            throw new IOException("无法解析 NBT (压缩和未压缩都失败)");
        }
        PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] 内存 NBT: size={} bytes, compressed={}", nbtData.length, compressed);

        // 检测并转换第三方格式 (litematica / sponge) 到 vanilla
        String fmt = NbtFormatConverter.detectFormat(root);
        PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] 检测到格式: {}", fmt);
        if (!"vanilla".equals(fmt) && !"unknown".equals(fmt)) {
            try {
                root = NbtFormatConverter.toVanilla(root);
                PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] 已转换 {} → vanilla", fmt);
            } catch (Throwable t) {
                PrefabCustomAddon.LOGGER.error("[NBT-DEBUG] 转换 {} 失败: {}", fmt, t.getMessage(), t);
                throw new IOException("转换 " + fmt + " 失败: " + t.getMessage(), t);
            }
        }
        return parseRoot(root);
    }

    private static NbtInfo parseRoot(CompoundTag root) {
        int sx = 0, sy = 0, sz = 0;
        String fmt = "未知";

        // 0) size: ListTag<3*IntTag> (1.21.1+ vanilla + NbtFormatConverter 转换后)
        if (sx == 0 && root.contains("size", Tag.TAG_LIST)) {
            ListTag sl = root.getList("size", Tag.TAG_INT);
            if (sl.size() >= 3 && (sl.getInt(0) != 0 || sl.getInt(1) != 0 || sl.getInt(2) != 0)) {
                sx = Math.abs(sl.getInt(0));
                sy = Math.abs(sl.getInt(1));
                sz = Math.abs(sl.getInt(2));
                fmt = "size[ListTag]";
            }
        }
        // 1) size: IntArray (老 vanilla 格式)
        if (sx == 0 && root.contains("size", Tag.TAG_INT_ARRAY)) {
            int[] size = root.getIntArray("size");
            if (size.length >= 3 && (size[0] != 0 || size[1] != 0 || size[2] != 0)) {
                sx = Math.abs(size[0]);
                sy = Math.abs(size[1]);
                sz = Math.abs(size[2]);
                fmt = "size[IntArray]";
            }
        }
        // 2) size: Compound {x/X, y/Y, z/Z}
        if (sx == 0 && root.contains("size", Tag.TAG_COMPOUND)) {
            CompoundTag s = root.getCompound("size");
            int tx = readAxis(s, "x", "X");
            int ty = readAxis(s, "y", "Y");
            int tz = readAxis(s, "z", "Z");
            if (tx != 0 || ty != 0 || tz != 0) {
                sx = Math.abs(tx); sy = Math.abs(ty); sz = Math.abs(tz);
                fmt = "size[Compound]";
            }
        }
        // 3) dimensions: Compound {width, height, depth}
        if (sx == 0 && root.contains("dimensions", Tag.TAG_COMPOUND)) {
            CompoundTag d = root.getCompound("dimensions");
            int w = d.contains("width") ? d.getInt("width") : 0;
            int h = d.contains("height") ? d.getInt("height") : 0;
            int dep = d.contains("depth") ? d.getInt("depth") : 0;
            if (w == 0 && h == 0 && dep == 0) {
                w = readAxis(d, "x", "X");
                h = readAxis(d, "y", "Y");
                dep = readAxis(d, "z", "Z");
            }
            if (w != 0 || h != 0 || dep != 0) {
                sx = Math.abs(w); sy = Math.abs(h); sz = Math.abs(dep);
                fmt = "dimensions[Compound]";
            }
        }
        // 3.5) bounds / 尺寸 / 大小  (中文模组)
        if (sx == 0) {
            for (String k : new String[]{"bounds", "boundingBox", "尺寸", "大小", "bounding_box"}) {
                if (root.contains(k, Tag.TAG_COMPOUND)) {
                    CompoundTag s = root.getCompound(k);
                    int tx = readAxis(s, "x", "X", "width", "w");
                    int ty = readAxis(s, "y", "Y", "height", "h");
                    int tz = readAxis(s, "z", "Z", "depth", "d");
                    if (tx != 0 || ty != 0 || tz != 0) {
                        sx = Math.abs(tx); sy = Math.abs(ty); sz = Math.abs(tz);
                        fmt = k + "[Compound]";
                        break;
                    }
                } else if (root.contains(k, Tag.TAG_INT_ARRAY)) {
                    int[] arr = root.getIntArray(k);
                    if (arr.length >= 3) {
                        sx = Math.abs(arr[0]); sy = Math.abs(arr[1]); sz = Math.abs(arr[2]);
                        fmt = k + "[IntArray]";
                        break;
                    }
                }
            }
        }
        // 4) 从 blocks[] 列表的 pos 算包围盒
        if (sx == 0) {
            int[] bbox = computeBoundingBox(root);
            if (bbox != null) {
                sx = bbox[0]; sy = bbox[1]; sz = bbox[2];
                fmt = "blocks[包围盒]";
            }
        }

        // 解析 palette
        Map<String, Integer> usage = new LinkedHashMap<>();
        Set<String> modIds = new TreeSet<>();
        int totalBlocks = 0;

        // 调试日志: 每个 size 检测方法的结果
        PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] 最终尺寸: " + sx + "x" + sy + "x" + sz + " (格式: " + fmt + ")");
        PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] palette 存在=" + root.contains("palette", Tag.TAG_LIST) + ", blocks 存在=" + root.contains("blocks", Tag.TAG_LIST));

        if (root.contains("palette", Tag.TAG_LIST)) {
            ListTag palette = root.getList("palette", Tag.TAG_COMPOUND);
            for (int i = 0; i < palette.size(); i++) {
                CompoundTag entry = palette.getCompound(i);
                String name = null;
                // 兼容: Name / Block / name / id
                for (String k : new String[]{"Name", "name", "Block", "block", "id"}) {
                    if (entry.contains(k, Tag.TAG_STRING)) {
                        name = entry.getString(k);
                        break;
                    }
                }
                if (name != null) {
                    String mod = modIdFromBlockId(name);
                    if (mod != null) {
                        usage.merge(mod, 1, Integer::sum);
                        if (!mod.equals("minecraft") && !mod.equals("prefab")) {
                            modIds.add(mod);
                        }
                    }
                }
            }
            totalBlocks = palette.size();
        } else if (root.contains("blocks", Tag.TAG_LIST)) {
            // 模组可能直接用 blocks 列表, 无 palette
            // mc 1.21.1+ 格式: { pos: [x,y,z], state: { Name: "minecraft:stone", Properties: {...} } }
            // 老格式:         { pos: [x,y,z], Name: "minecraft:stone", ... }
            // litematic:      { Name: "minecraft:stone" } (在 palette)
            ListTag blocks = root.getList("blocks", Tag.TAG_COMPOUND);
            totalBlocks = blocks.size();
            for (int i = 0; i < blocks.size(); i++) {
                CompoundTag b = blocks.getCompound(i);
                String name = null;
                // 优先级 1: mc 1.21.1+ 格式 (state 子 CompoundTag)
                if (b.contains("state", Tag.TAG_COMPOUND)) {
                    CompoundTag state = b.getCompound("state");
                    for (String k : new String[]{"Name", "name", "Block", "block", "id"}) {
                        if (state.contains(k, Tag.TAG_STRING)) {
                            name = state.getString(k);
                            break;
                        }
                    }
                }
                // 优先级 2: 老格式 (顶层 Name 字段)
                if (name == null) {
                    for (String k : new String[]{"Name", "name", "Block", "block", "id"}) {
                        if (b.contains(k, Tag.TAG_STRING)) {
                            name = b.getString(k);
                            break;
                        }
                    }
                }
                if (name != null) {
                    String mod = modIdFromBlockId(name);
                    if (mod != null) {
                        usage.merge(mod, 1, Integer::sum);
                        if (!mod.equals("minecraft") && !mod.equals("prefab")) {
                            modIds.add(mod);
                        }
                    }
                }
            }
        }
        // 提取 Litematica / Sponge 等格式带来的 metadata (NbtFormatConverter 会以 _meta_ 前缀挂到 root)
        String metaName = readMetaString(root, "_meta_name");
        String metaAuthor = readMetaString(root, "_meta_author");
        String metaDescription = readMetaString(root, "_meta_description");
        return new NbtInfo(sx, sy, sz, new ArrayList<>(modIds), usage, totalBlocks, fmt,
            metaName, metaAuthor, metaDescription);
    }

    /** 安全读取 _meta_* 字符串, 找不到返回 null, 空字符串也返回 null */
    private static String readMetaString(CompoundTag root, String key) {
        if (root == null || !root.contains(key, Tag.TAG_STRING)) return null;
        String v = root.getString(key);
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    /** 从 CompoundTag 读取轴向数值 (支持多种 key) */
    private static int readAxis(CompoundTag tag, String... keys) {
        for (String k : keys) {
            if (tag.contains(k)) {
                Tag t = tag.get(k);
                if (t.getId() == Tag.TAG_INT) return tag.getInt(k);
                if (t.getId() == Tag.TAG_BYTE) return tag.getByte(k);
                if (t.getId() == Tag.TAG_SHORT) return tag.getShort(k);
                if (t.getId() == Tag.TAG_LONG) return (int) tag.getLong(k);
            }
        }
        return 0;
    }

    /** 从 blocks[].pos 算包围盒 */
    private static int[] computeBoundingBox(CompoundTag root) {
        if (!root.contains("blocks", Tag.TAG_LIST)) {
            PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] computeBoundingBox: 根不含 blocks ListTag");
            return null;
        }
        // 用 elemType=10 (Compound) 试; 失败再用 -1 拿原始列表
        ListTag blocks;
        try {
            blocks = root.getList("blocks", Tag.TAG_COMPOUND);
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] computeBoundingBox: getList 失败: " + e.getMessage());
            return null;
        }
        if (blocks.isEmpty()) {
            PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] computeBoundingBox: blocks 为空");
            return null;
        }
        // 打印第一个 block 的所有 keys
        CompoundTag first = blocks.getCompound(0);
        PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] computeBoundingBox: blocks.size=" + blocks.size()
            + ", 第一个 block keys=" + first.getAllKeys());
        for (String fk : first.getAllKeys()) {
            Tag ft = first.get(fk);
            String fv = "";
            if (ft.getId() == Tag.TAG_INT_ARRAY) {
                fv = " = " + Arrays.toString(first.getIntArray(fk));
            } else if (ft.getId() == Tag.TAG_INT) {
                fv = " = " + first.getInt(fk);
            } else if (ft.getId() == Tag.TAG_COMPOUND) {
                fv = " (含 " + first.getCompound(fk).getAllKeys() + ")";
            } else if (ft.getId() == Tag.TAG_STRING) {
                fv = " = \"" + first.getString(fk) + "\"";
            }
            PrefabCustomAddon.LOGGER.info("[NBT-DEBUG]     first." + fk + " (type=" + ft.getId() + ")" + fv);
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int matched = 0, skipped = 0;
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag b = blocks.getCompound(i);
            int x = 0, y = 0, z = 0;
            if (b.contains("pos", Tag.TAG_INT_ARRAY)) {
                int[] pos = b.getIntArray("pos");
                if (pos.length >= 3) { x = pos[0]; y = pos[1]; z = pos[2]; matched++; }
                else { skipped++; continue; }
            } else if (b.contains("pos", Tag.TAG_LIST)) {
                // 1.21.1+ 格式: pos 是 ListTag 含 3 个 IntTag
                ListTag pos = b.getList("pos", Tag.TAG_INT);
                if (pos.size() >= 3) {
                    x = ((NumericTag) pos.get(0)).getAsInt();
                    y = ((NumericTag) pos.get(1)).getAsInt();
                    z = ((NumericTag) pos.get(2)).getAsInt();
                    matched++;
                } else { skipped++; continue; }
            } else if (b.contains("pos", Tag.TAG_COMPOUND)) {
                CompoundTag p = b.getCompound("pos");
                x = readAxis(p, "x", "X");
                y = readAxis(p, "y", "Y");
                z = readAxis(p, "z", "Z");
                matched++;
            } else if (b.contains("x")) {
                x = readAxis(b, "x", "X");
                y = readAxis(b, "y", "Y");
                z = readAxis(b, "z", "Z");
                matched++;
            } else { skipped++; continue; }
            if (x < minX) minX = x; if (x > maxX) maxX = x;
            if (y < minY) minY = y; if (y > maxY) maxY = y;
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
        }
        PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] computeBoundingBox: 匹配=" + matched + ", 跳过=" + skipped
            + ", bbox min=(" + minX + "," + minY + "," + minZ + ") max=(" + maxX + "," + maxY + "," + maxZ + ")");
        if (matched == 0) return null;
        return new int[]{maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1};
    }

    /** 从 "modid:block" 提取 mod id; 无冒号视为 minecraft */
    private static String modIdFromBlockId(String blockId) {
        if (blockId == null) return null;
        int colon = blockId.indexOf(':');
        if (colon < 0) return "minecraft";
        return blockId.substring(0, colon);
    }
}
