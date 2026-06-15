package com.prefab.addon.work;

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

        public NbtInfo(int x, int y, int z, List<String> modIds,
                       Map<String, Integer> usage, int totalBlocks, String fmt) {
            this.sizeX = x; this.sizeY = y; this.sizeZ = z;
            this.modIds = modIds;
            this.blockUsage = usage;
            this.totalBlocks = totalBlocks;
            this.detectedFormat = fmt;
        }

        public String sizeString() { return sizeX + "x" + sizeY + "x" + sizeZ; }
    }

    public static NbtInfo parse(Path nbtFile) throws IOException {
        if (!Files.exists(nbtFile)) {
            throw new IOException("文件不存在: " + nbtFile);
        }
        // 1) 尝试压缩格式 (gzip 包裹)
        try (var in = Files.newInputStream(nbtFile)) {
            CompoundTag root = NbtIo.readCompressed(in, NbtAccounter.create(64L * 1024 * 1024));
            return parseRoot(root);
        } catch (IOException e) {
            // 2) 尝试未压缩
            try (var in = Files.newInputStream(nbtFile)) {
                CompoundTag root = NbtIo.read(new java.io.DataInputStream(in));
                return parseRoot(root);
            }
        }
    }

    private static NbtInfo parseRoot(CompoundTag root) {
        int sx = 0, sy = 0, sz = 0;
        String fmt = "未知";

        // 1) size: IntArray
        if (root.contains("size", Tag.TAG_INT_ARRAY)) {
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
            ListTag blocks = root.getList("blocks", Tag.TAG_COMPOUND);
            totalBlocks = blocks.size();
            for (int i = 0; i < blocks.size(); i++) {
                CompoundTag b = blocks.getCompound(i);
                String name = null;
                for (String k : new String[]{"Name", "name", "Block", "block", "id"}) {
                    if (b.contains(k, Tag.TAG_STRING)) {
                        name = b.getString(k);
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
        }
        return new NbtInfo(sx, sy, sz, new ArrayList<>(modIds), usage, totalBlocks, fmt);
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
        if (!root.contains("blocks", Tag.TAG_LIST)) return null;
        ListTag blocks = root.getList("blocks", Tag.TAG_COMPOUND);
        if (blocks.isEmpty()) return null;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        boolean any = false;
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag b = blocks.getCompound(i);
            int x = 0, y = 0, z = 0;
            if (b.contains("pos", Tag.TAG_INT_ARRAY)) {
                int[] pos = b.getIntArray("pos");
                if (pos.length >= 3) { x = pos[0]; y = pos[1]; z = pos[2]; }
            } else if (b.contains("pos", Tag.TAG_COMPOUND)) {
                CompoundTag p = b.getCompound("pos");
                x = readAxis(p, "x", "X");
                y = readAxis(p, "y", "Y");
                z = readAxis(p, "z", "Z");
            } else if (b.contains("x")) {
                // 直接平铺
                x = readAxis(b, "x", "X");
                y = readAxis(b, "y", "Y");
                z = readAxis(b, "z", "Z");
            } else continue;
            if (x < minX) minX = x; if (x > maxX) maxX = x;
            if (y < minY) minY = y; if (y > maxY) maxY = y;
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
            any = true;
        }
        if (!any) return null;
        // 包围盒尺寸 = (max - min + 1)
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
