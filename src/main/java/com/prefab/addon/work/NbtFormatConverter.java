package com.prefab.addon.work;

import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.nbt.*;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.*;

/**
 * 把第三方 NBT schematic 格式 (Litematica / Sponge) 转换为 Minecraft vanilla StructureTemplate 格式.
 * 转换后能直接用 NbtStructureParser 解析尺寸/依赖, 也能 Prefab 模组直接识别.
 *
 * 支持格式 (按 litematica-LTS-1.21 实际写入的格式):
 *   - vanilla        : {"size", "palette", "blocks", "entities", "DataVersion"} - 原样返回
 *   - litematica     : {"Version", "Metadata", "Regions": {"<name>": {Position, Size, BlockStatePalette, BlockStates}}}
 *                       Position/Size 是 Compound{x,y,z}, BlockStatePalette 是 ListTag<Compound{Name,Properties}>,
 *                       BlockStates 是 long[] (无 header, bitsPerBlock = max(2, 32 - numberOfLeadingZeros(paletteSize-1)))
 *   - sponge_v2      : {"Version", "Width", "Height", "Length", "Palette": Compound{<blockstate_string>: id},
 *                       "BlockData": byte[] (varint packed), "Metadata": Compound{Name, Author, ...}}
 *                       Sponge v2 (1.12) 的 Palette 是 Map<String, Integer>, BlockData 是 varint
 *   - sponge_v3      : {"Schematic": {"Version":3, "Width", "Height", "Length", "DataVersion",
 *                                       "Blocks": {"Palette": Compound, "Data": byte[] (varint)},
 *                                       "Metadata": Compound{Name, Author, ...}, "BlockEntities", "Entities"}}
 *                       Sponge v3 (1.18+) 把所有内容包在 "Schematic" 复合标签里
 */
public class NbtFormatConverter {

    /** 检测 NBT 根 CompoundTag 的格式. */
    public static String detectFormat(CompoundTag root) {
        if (root == null) return "unknown";
        // Litematica: 顶层有 "Regions" Compound (Metadata 可能有, 也可能老版本没有)
        if (root.contains("Regions", Tag.TAG_COMPOUND)) {
            return "litematica";
        }
        // Sponge v3 v3a (litematica 风格): Schematic/Blocks{Palette,Data}
        if (root.contains("Schematic", Tag.TAG_COMPOUND)) {
            CompoundTag schem = root.getCompound("Schematic");
            if (schem.contains("Width") && schem.contains("Height") && schem.contains("Length")
                    && schem.contains("Version", Tag.TAG_INT)
                    && schem.getInt("Version") >= 3
                    && schem.contains("Blocks", Tag.TAG_COMPOUND)) {
                return "sponge_v3";
            }
            // Sponge v3 v3b (WorldEdit 官方 1.18+): Schematic{Palette,BlockData}
            if (schem.contains("Width") && schem.contains("Height") && schem.contains("Length")
                    && schem.contains("Version", Tag.TAG_INT)
                    && schem.getInt("Version") >= 3
                    && schem.contains("Palette", Tag.TAG_COMPOUND)
                    && schem.contains("BlockData", Tag.TAG_LONG_ARRAY)) {
                return "sponge_v3we";
            }
        }
        // Sponge v2: 顶层有 Width/Height/Length (任意数字类型) + Version (Int, < 3) + Palette (Compound) + BlockData (ByteArray)
        if (isAnyNumeric(root, "Width") && isAnyNumeric(root, "Height") && isAnyNumeric(root, "Length")
                && root.contains("Version", Tag.TAG_INT)
                && root.getInt("Version") < 3
                && root.contains("Palette", Tag.TAG_COMPOUND)
                && root.contains("BlockData", Tag.TAG_BYTE_ARRAY)) {
            return "sponge_v2";
        }
        // Vanilla structure: 有 "palette" (ListTag) + "blocks" (ListTag)
        if (root.contains("palette", Tag.TAG_LIST) && root.contains("blocks", Tag.TAG_LIST)) {
            return "vanilla";
        }
        return "unknown";
    }

    /** 检查 CompoundTag 是否有指定 key 且为数字类型 (Int/Short/Long/Byte/Float/Double) */
    private static boolean isAnyNumeric(CompoundTag tag, String key) {
        if (tag == null || !tag.contains(key)) return false;
        int id = tag.get(key).getId();
        return id == Tag.TAG_INT || id == Tag.TAG_SHORT || id == Tag.TAG_LONG
                || id == Tag.TAG_BYTE || id == Tag.TAG_FLOAT || id == Tag.TAG_DOUBLE;
    }

    /** 安全读出数字 (优先 Int, 兼容 Short/Long/Byte) */
    private static int readAnyInt(CompoundTag tag, String key) {
        if (tag == null || !tag.contains(key)) return 0;
        Tag t = tag.get(key);
        int id = t.getId();
        if (id == Tag.TAG_INT) return tag.getInt(key);
        if (id == Tag.TAG_SHORT) return tag.getShort(key);
        if (id == Tag.TAG_LONG) return (int) tag.getLong(key);
        if (id == Tag.TAG_BYTE) return tag.getByte(key);
        if (id == Tag.TAG_FLOAT) return (int) tag.getFloat(key);
        if (id == Tag.TAG_DOUBLE) return (int) tag.getDouble(key);
        return 0;
    }

    /**
     * 自动转换第三方格式到 vanilla. 已经是 vanilla 直接返回原 root.
     * 转换失败时返回原 root 并 logger warn (上层按 vanilla 解析, 能解多少算多少).
     *
     * 输出 vanilla 格式 (匹配 CustomStructureBuilder.parseToPrefabStructure 期望):
     *   size: ListTag [Int, Int, Int]      (CustomStructureBuilder.java:470 用 getList("size", 3))
     *   palette: ListTag<Compound{Name, Properties}>
     *   blocks: ListTag<Compound{pos: ListTag[Int,Int,Int], state: Int}>
     */
    public static CompoundTag toVanilla(CompoundTag root) {
        if (root == null) return null;
        String fmt = detectFormat(root);
        PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] toVanilla() called, detected={}, rootKeys={}", fmt, root.getAllKeys());
        try {
            switch (fmt) {
                case "litematica":
                    PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] Converting litematica → vanilla");
                    return litematicaToVanilla(root);
                case "sponge_v3":
                    PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] Converting sponge_v3 (litematica style) → vanilla");
                    return spongeV3ToVanilla(root);
                case "sponge_v3we":
                    PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] Converting sponge_v3we (WorldEdit 1.18+ style) → vanilla");
                    return spongeV3WeToVanilla(root);
                case "sponge_v2":
                    PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] Converting sponge_v2 → vanilla");
                    return spongeV2ToVanilla(root);
                default:
                    PrefabCustomAddon.LOGGER.warn("[NBT-DEBUG] 格式为 {}, 不转换直接返回原 NBT", fmt);
                    return root;  // vanilla / unknown
            }
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[NBT-DEBUG] 转换 {} 到 vanilla 失败: {}", fmt, t.getMessage(), t);
            return root;
        }
    }

    // ===================================================================
    // Litematica
    // ===================================================================
    /**
     * Litematica 格式 (参考: fi.dy.masa.litematica.schematic.LitematicaSchematic.readFromNBT):
     *   顶层: {"Version", "Metadata", "Regions": {"<name>": CompoundTag}}
     *   region: {"Position":Compound{x,y,z}, "Size":Compound{x,y,z},
     *            "BlockStatePalette": ListTag<CompoundTag{Name,Properties}>,
     *            "BlockStates": long[]}
     *
     * BlockStates 解码: 标准 litematica **没有 header long**, 数据从 long[0] 开始
     *   bitsPerBlock = max(2, 32 - numberOfLeadingZeros(paletteSize-1))
     *   bit_offset = idx * bitsPerBlock
     *   long_index = bit_offset / 64
     *   bit_in_long = bit_offset % 64
     *   state = (data[long_index] >>> bit_in_long) & mask
     *   如果 bit_in_long + bitsPerBlock > 64: 从下一个 long 取高位
     *
     * block 顺序: index = y * sizeX * sizeZ + z * sizeX + x (Y-major, Z-then-X)
     */
    public static CompoundTag litematicaToVanilla(CompoundTag root) {
        if (!root.contains("Regions", Tag.TAG_COMPOUND)) {
            PrefabCustomAddon.LOGGER.warn("[NBT-DEBUG] Litematica 根不含 Regions");
            return root;
        }
        CompoundTag regions = root.getCompound("Regions");
        if (regions.isEmpty()) {
            PrefabCustomAddon.LOGGER.warn("[NBT-DEBUG] Litematica Regions 为空");
            return root;
        }
        String regionName = regions.getAllKeys().iterator().next();
        CompoundTag region = regions.getCompound(regionName);
        PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] Litematica region: {} (keys: {})", regionName, region.getAllKeys());

        // 把 Litematica 的 Metadata 也读出来, 转成 vanilla 后挂在 root._meta_*
        if (root.contains("Metadata", Tag.TAG_COMPOUND)) {
            CompoundTag meta = root.getCompound("Metadata");
            attachMetaString(root, meta, "Name", "_meta_name");
            attachMetaString(root, meta, "Author", "_meta_author");
            attachMetaString(root, meta, "Description", "_meta_description");
        }

        // 解析 Size / Position - litematica 实际写为 Compound{x,y,z} (用 NbtUtils.createBlockPosTag 创建)
        int[] posArr = readSizeOrPos(region, "Position", 3);
        int[] sizeArr = readSizeOrPos(region, "Size", 3);
        if (sizeArr.length < 3) {
            StringBuilder ks = new StringBuilder();
            for (String k : region.getAllKeys()) {
                Tag t = region.get(k);
                ks.append("  ").append(k).append(" (").append(t.getId()).append('/')
                        .append(t.getClass().getSimpleName()).append(")\n");
            }
            PrefabCustomAddon.LOGGER.error("[NBT-DEBUG] Litematica region '" + regionName
                    + "' Size 解析失败, region keys:\n" + ks);
            ListTag litemPaletteTmp = region.getList("BlockStatePalette", Tag.TAG_COMPOUND);
            sizeArr = new int[]{1, 1, Math.max(1, litemPaletteTmp.size())};
            PrefabCustomAddon.LOGGER.warn("[NBT-DEBUG] 使用兜底 size: {}", java.util.Arrays.toString(sizeArr));
        }
        int sizeX = Math.abs(sizeArr[0]);
        int sizeY = Math.abs(sizeArr[1]);
        int sizeZ = Math.abs(sizeArr[2]);
        PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] Litematica Size=({}x{}x{}), Pos={}",
                sizeX, sizeY, sizeZ, java.util.Arrays.toString(posArr));

        // palette: litematica 的 BlockStatePalette 和 vanilla palette 元素格式一致 (Name + Properties)
        ListTag vanillaPalette = new ListTag();
        ListTag litemPalette = region.getList("BlockStatePalette", Tag.TAG_COMPOUND);
        for (int i = 0; i < litemPalette.size(); i++) {
            CompoundTag old = litemPalette.getCompound(i);
            CompoundTag van = new CompoundTag();
            String name = old.getString("Name");
            van.putString("Name", name);
            if (old.contains("Properties", Tag.TAG_COMPOUND)) {
                van.put("Properties", old.getCompound("Properties"));
            }
            vanillaPalette.add(van);
        }
        PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] Litematica palette 大小: {}", vanillaPalette.size());

        // 解码 BlockStates
        long[] blockStates = region.getLongArray("BlockStates");
        if (blockStates.length == 0) {
            PrefabCustomAddon.LOGGER.warn("[NBT-DEBUG] Litematica BlockStates 为空");
        }
        // 标准 litematica: bitsPerBlock = max(2, 32 - numberOfLeadingZeros(paletteSize-1)), **无 header**
        int bitsPerBlock = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, vanillaPalette.size() - 1)));
        long mask = (1L << bitsPerBlock) - 1L;

        PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] Litematica 解码: size={}x{}x{}={}, bitsPerBlock={}, dataLongs={}",
                sizeX, sizeY, sizeZ, sizeX * sizeY * sizeZ, bitsPerBlock, blockStates.length);

        ListTag vanillaBlocks = new ListTag();
        int matched = 0, skipped = 0;
        // litematica 顺序: index = y * sizeX * sizeZ + z * sizeX + x (Y-major, Z-then-X)
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    int idx = y * sizeX * sizeZ + z * sizeX + x;
                    int bitOffset = idx * bitsPerBlock;
                    int longIdx = bitOffset / 64;
                    int bitInLong = bitOffset % 64;
                    if (longIdx >= blockStates.length) { skipped++; continue; }
                    long state = (blockStates[longIdx] >>> bitInLong) & mask;
                    if (bitInLong + bitsPerBlock > 64) {
                        if (longIdx + 1 < blockStates.length) {
                            long extra = (blockStates[longIdx + 1] << (64 - bitInLong));
                            state = (state | extra) & mask;
                        }
                    }
                    if (state < 0 || state >= vanillaPalette.size()) { skipped++; continue; }
                    CompoundTag block = new CompoundTag();
                    // vanilla pos: 1.21.1 StructureTemplate.load() 读 pos 用 TAG_INT_ARRAY (11),
                    // 用 ListTag 会让整批 block 被拒收, placeInWorld 静默返回 0.
                    // 之前用 ListTag 是给 parseToPrefabStructure (CustomStructureBuilder) 读用的,
                    // 它现在两种格式都支持, 所以统一用 IntArrayTag 跟 vanilla 写出来的 NBT 一致.
                    // 关键: pos 是**相对 (0,0,0) 的偏移**, 不加 Position (Position 是 region 在世界中的位置, 不是局部坐标)
                    block.put("pos", new IntArrayTag(new int[]{x, y, z}));
                    block.putInt("state", (int) state);
                    vanillaBlocks.add(block);
                    matched++;
                }
            }
        }
        PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] Litematica 解码: matched={}, skipped={}", matched, skipped);

        // size: ListTag [Int, Int, Int] (parseToPrefabStructure 用 getList("size", 3))
        ListTag sizeList = new ListTag();
        sizeList.add(IntTag.valueOf(sizeX));
        sizeList.add(IntTag.valueOf(sizeY));
        sizeList.add(IntTag.valueOf(sizeZ));

        CompoundTag vanilla = new CompoundTag();
        // 关键: vanilla StructureTemplate.load() 用 BlockPos.CODEC 读 "size",
        // 必须写成 IntArrayTag (3-int 数组). 之前用 ListTag[Int, Int, Int] 会让
        // 1.21.1 的 NbtOps 解码失败, 导致 StructureTemplate.size = BlockPos.ZERO,
        // 进而 placeInWorld 拒绝放置, "placeInWorld 返回 false".
        // 同时把 sizeList 存到另一个 key, 给 parseToPrefabStructure 读.
        vanilla.put("size", new IntArrayTag(new int[]{sizeX, sizeY, sizeZ}));
        vanilla.put("sizeList", sizeList);
        vanilla.put("palette", vanillaPalette);
        vanilla.put("blocks", vanillaBlocks);
        vanilla.putInt("DataVersion", 3953);  // 1.21.1
        // 关键: 把 _meta_* 从 root 复制到 vanilla (否则 NbtStructureParser 读不到 metadata)
        copyMetaFields(root, vanilla);
        return vanilla;
    }

    // ===================================================================
    // Sponge Schematic v3 (1.18+) - 嵌套结构
    // ===================================================================
    /**
     * 格式 (参考 litematica LitematicaSchematic.isValidSpongeSchematicv3 + readSpongeBlocksFromTag):
     *   顶层: {"Schematic": CompoundTag {
     *     "Version": 3,
     *     "DataVersion": int,
     *     "Width": int, "Height": int, "Length": int,
     *     "Blocks": CompoundTag {
     *       "Palette": CompoundTag { "<blockstate_string>": int_id, ... },
     *       "Data": byte[] (varint packed block ids)
     *     },
     *     "Metadata": CompoundTag { "Name": "...", "Author": "...", "Date": long }
     *   }}
     *
     * 实际 Palette 是 Map<String, Integer>, block state id 是 int (1.13+),
     * BlockData 是 varint 序列 (每个 varint = 一个 block 的 palette id).
     * 顺序: index = y * Width * Length + z * Width + x (Y-major, Z-then-X)
     */
    public static CompoundTag spongeV3ToVanilla(CompoundTag root) {
        CompoundTag schem = root.getCompound("Schematic");
        PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] Sponge v3 Schematic keys: {}", schem.getAllKeys());

        // 读 Width/Height/Length (可能是 Int 或 Short)
        int w = readAnyInt(schem, "Width");
        int h = readAnyInt(schem, "Height");
        int l = readAnyInt(schem, "Length");
        PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] Sponge v3 Size=({}x{}x{})", w, h, l);

        // 读 Blocks 子结构
        if (!schem.contains("Blocks", Tag.TAG_COMPOUND)) {
            PrefabCustomAddon.LOGGER.error("[NBT-DEBUG] Sponge v3 Schematic.Blocks 不存在或不是 Compound");
            return root;
        }
        CompoundTag blocksTag = schem.getCompound("Blocks");
        if (!blocksTag.contains("Palette", Tag.TAG_COMPOUND) || !blocksTag.contains("Data", Tag.TAG_BYTE_ARRAY)) {
            PrefabCustomAddon.LOGGER.error("[NBT-DEBUG] Sponge v3 Blocks 缺 Palette 或 Data (keys: {})", blocksTag.getAllKeys());
            return root;
        }
        CompoundTag paletteTag = blocksTag.getCompound("Palette");
        byte[] blockData = blocksTag.getByteArray("Data");
        PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] Sponge v3 palette 大小: {}, blockData bytes: {}",
                paletteTag.getAllKeys().size(), blockData.length);

        // 重建 vanilla palette
        ListTag vanillaPalette = buildVanillaPaletteFromMapStringId(paletteTag);
        PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] Sponge v3 vanilla palette size: {}", vanillaPalette.size());

        // 解码 varint BlockData
        int[] stateIds = decodeVarIntArray(blockData);
        int totalBlocks = w * h * l;
        PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] Sponge v3 解码: total={}, decoded varints={}", totalBlocks, stateIds.length);

        ListTag vanillaBlocks = new ListTag();
        int matched = 0, skipped = 0;
        // Sponge 顺序: index = y * W * L + z * W + x (Y-major, Z-then-X), 和 litematica 一致
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < l; z++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w * l + z * w + x;
                    if (i >= stateIds.length) { skipped++; continue; }
                    int stateId = stateIds[i];
                    if (stateId < 0 || stateId >= vanillaPalette.size()) { skipped++; continue; }
                    CompoundTag block = new CompoundTag();
                    ListTag posList = new ListTag();
                    posList.add(IntTag.valueOf(x));
                    posList.add(IntTag.valueOf(y));
                    posList.add(IntTag.valueOf(z));
                    block.put("pos", posList);
                    block.putInt("state", stateId);
                    vanillaBlocks.add(block);
                    matched++;
                }
            }
        }
        PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] Sponge v3 解码: matched={}, skipped={}", matched, skipped);

        // 元信息
        if (schem.contains("Metadata", Tag.TAG_COMPOUND)) {
            CompoundTag meta = schem.getCompound("Metadata");
            attachMetaString(root, meta, "Name", "_meta_name");
            attachMetaString(root, meta, "Author", "_meta_author");
            attachMetaString(root, meta, "Description", "_meta_description");
        }

        // size: ListTag [Int, Int, Int]
        ListTag sizeList = new ListTag();
        sizeList.add(IntTag.valueOf(w));
        sizeList.add(IntTag.valueOf(h));
        sizeList.add(IntTag.valueOf(l));

        CompoundTag vanilla = new CompoundTag();
        vanilla.put("size", new IntArrayTag(new int[]{w, h, l}));
        vanilla.put("sizeList", sizeList);
        vanilla.put("palette", vanillaPalette);
        vanilla.put("blocks", vanillaBlocks);
        vanilla.putInt("DataVersion", 3953);
        copyMetaFields(root, vanilla);
        return vanilla;
    }

    // ===================================================================
    // Sponge Schematic v3 WorldEdit 官方格式 (1.18+)
    // ===================================================================
    /**
     * 格式:
     *   顶层: {"Schematic": CompoundTag {
     *     "Version": 3,
     *     "DataVersion": int,
     *     "Width": int, "Height": int, "Length": int,
     *     "Palette": CompoundTag { "<blockstate_string>": int_id, ... },
     *     "BlockData": long[] (packed, 同 litematica 解码方式),
     *     "Metadata": CompoundTag { "Name": "...", "Author": "..." }
     *   }}
     *
     * BlockData 编码 (litematica 同款): bitsPerBlock = max(2, 32 - numberOfLeadingZeros(paletteSize-1))
     *   bit_offset = idx * bitsPerBlock
     *   long_index = bit_offset / 64, bit_in_long = bit_offset % 64
     *   state = (data[long_index] >>> bit_in_long) & mask
     *   如果 bit_in_long + bitsPerBlock > 64: 从下一个 long 取高位
     */
    public static CompoundTag spongeV3WeToVanilla(CompoundTag root) {
        CompoundTag schem = root.getCompound("Schematic");
        int w = readAnyInt(schem, "Width");
        int h = readAnyInt(schem, "Height");
        int l = readAnyInt(schem, "Length");
        PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] Sponge v3we Size=({}x{}x{})", w, h, l);

        if (!schem.contains("Palette", Tag.TAG_COMPOUND) || !schem.contains("BlockData", Tag.TAG_LONG_ARRAY)) {
            PrefabCustomAddon.LOGGER.error("[NBT-DEBUG] Sponge v3we 缺 Palette 或 BlockData (schem keys: {})", schem.getAllKeys());
            return root;
        }
        CompoundTag paletteTag = schem.getCompound("Palette");
        long[] blockData = schem.getLongArray("BlockData");
        PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] Sponge v3we palette keys: {}, blockData longs: {}",
                paletteTag.getAllKeys().size(), blockData.length);

        // 重建 vanilla palette
        ListTag vanillaPalette = buildVanillaPaletteFromMapStringId(paletteTag);
        PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] Sponge v3we vanilla palette size: {}", vanillaPalette.size());

        // bitsPerBlock + 解码 long[]
        int bitsPerBlock = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, vanillaPalette.size() - 1)));
        long mask = (1L << bitsPerBlock) - 1L;
        PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] Sponge v3we bitsPerBlock={}, mask={}", bitsPerBlock, mask);

        ListTag vanillaBlocks = new ListTag();
        int matched = 0, skipped = 0;
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < l; z++) {
                for (int x = 0; x < w; x++) {
                    int idx = y * w * l + z * w + x;
                    int bitOffset = idx * bitsPerBlock;
                    int longIdx = bitOffset / 64;
                    int bitInLong = bitOffset % 64;
                    if (longIdx >= blockData.length) { skipped++; continue; }
                    long state = (blockData[longIdx] >>> bitInLong) & mask;
                    if (bitInLong + bitsPerBlock > 64) {
                        if (longIdx + 1 < blockData.length) {
                            long extra = (blockData[longIdx + 1] << (64 - bitInLong));
                            state = (state | extra) & mask;
                        }
                    }
                    if (state < 0 || state >= vanillaPalette.size()) { skipped++; continue; }
                    CompoundTag block = new CompoundTag();
                    ListTag posList = new ListTag();
                    posList.add(IntTag.valueOf(x));
                    posList.add(IntTag.valueOf(y));
                    posList.add(IntTag.valueOf(z));
                    block.put("pos", posList);
                    block.putInt("state", (int) state);
                    vanillaBlocks.add(block);
                    matched++;
                }
            }
        }
        PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] Sponge v3we 解码: matched={}, skipped={}", matched, skipped);

        // 元信息
        if (schem.contains("Metadata", Tag.TAG_COMPOUND)) {
            CompoundTag meta = schem.getCompound("Metadata");
            attachMetaString(root, meta, "Name", "_meta_name");
            attachMetaString(root, meta, "Author", "_meta_author");
            attachMetaString(root, meta, "Description", "_meta_description");
        }

        ListTag sizeList = new ListTag();
        sizeList.add(IntTag.valueOf(w));
        sizeList.add(IntTag.valueOf(h));
        sizeList.add(IntTag.valueOf(l));

        CompoundTag vanilla = new CompoundTag();
        vanilla.put("size", new IntArrayTag(new int[]{w, h, l}));
        vanilla.put("sizeList", sizeList);
        vanilla.put("palette", vanillaPalette);
        vanilla.put("blocks", vanillaBlocks);
        vanilla.putInt("DataVersion", 3953);
        copyMetaFields(root, vanilla);
        return vanilla;
    }

    // ===================================================================
    // Sponge Schematic v2 (1.12 及更早, v1 / v2 格式相同)
    // ===================================================================
    /**
     * 格式 (参考 litematica LitematicaSchematic.isValidSpongeSchematic + readSpongeBlocksFromTag):
     *   顶层: {"Version":1或2, "Width":int, "Height":int, "Length":int,
     *         "Palette": CompoundTag { "<blockstate_string>": int_id, ... },
     *         "BlockData": byte[] (varint packed),
     *         "Metadata": CompoundTag}
     *
     * v1/v2 的 Palette 是 Map<String, Integer>, BlockData 是 varint 序列.
     */
    public static CompoundTag spongeV2ToVanilla(CompoundTag root) {
        int w = readAnyInt(root, "Width");
        int h = readAnyInt(root, "Height");
        int l = readAnyInt(root, "Length");
        PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] Sponge v2 Size=({}x{}x{})", w, h, l);

        if (!root.contains("Palette", Tag.TAG_COMPOUND) || !root.contains("BlockData", Tag.TAG_BYTE_ARRAY)) {
            PrefabCustomAddon.LOGGER.error("[NBT-DEBUG] Sponge v2 缺 Palette 或 BlockData (keys: {})", root.getAllKeys());
            return root;
        }
        CompoundTag paletteTag = root.getCompound("Palette");
        byte[] blockData = root.getByteArray("BlockData");
        PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] Sponge v2 palette keys: {}, blockData bytes: {}",
                paletteTag.getAllKeys().size(), blockData.length);

        // 重建 vanilla palette
        ListTag vanillaPalette = buildVanillaPaletteFromMapStringId(paletteTag);
        PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] Sponge v2 vanilla palette size: {}", vanillaPalette.size());

        // 解码 varint
        int[] stateIds = decodeVarIntArray(blockData);

        ListTag vanillaBlocks = new ListTag();
        int matched = 0, skipped = 0;
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < l; z++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w * l + z * w + x;
                    if (i >= stateIds.length) { skipped++; continue; }
                    int stateId = stateIds[i];
                    if (stateId < 0 || stateId >= vanillaPalette.size()) { skipped++; continue; }
                    CompoundTag block = new CompoundTag();
                    ListTag posList = new ListTag();
                    posList.add(IntTag.valueOf(x));
                    posList.add(IntTag.valueOf(y));
                    posList.add(IntTag.valueOf(z));
                    block.put("pos", posList);
                    block.putInt("state", stateId);
                    vanillaBlocks.add(block);
                    matched++;
                }
            }
        }
        PrefabCustomAddon.LOGGER.info("[NBT-DEBUG] Sponge v2 解码: matched={}, skipped={}", matched, skipped);

        // 元信息
        if (root.contains("Metadata", Tag.TAG_COMPOUND)) {
            CompoundTag meta = root.getCompound("Metadata");
            attachMetaString(root, meta, "Name", "_meta_name");
            attachMetaString(root, meta, "Author", "_meta_author");
            attachMetaString(root, meta, "Description", "_meta_description");
        }

        ListTag sizeList = new ListTag();
        sizeList.add(IntTag.valueOf(w));
        sizeList.add(IntTag.valueOf(h));
        sizeList.add(IntTag.valueOf(l));

        CompoundTag vanilla = new CompoundTag();
        vanilla.put("size", new IntArrayTag(new int[]{w, h, l}));
        vanilla.put("sizeList", sizeList);
        vanilla.put("palette", vanillaPalette);
        vanilla.put("blocks", vanillaBlocks);
        vanilla.putInt("DataVersion", 3953);
        copyMetaFields(root, vanilla);
        return vanilla;
    }

    /**
     * 把 Sponge 风格 "Map<String block_state_string, int id>" Palette 转换为 vanilla ListTag.
     * 兼容 id 稀疏 (0..N 但有洞) 的情况, 用 maxId 补齐, 缺位用 minecraft:air.
     * 解析 "minecraft:stone[facing=north]" 拆成 Name + Properties (Compound).
     */
    private static ListTag buildVanillaPaletteFromMapStringId(CompoundTag paletteTag) {
        ListTag vanillaPalette = new ListTag();
        int maxId = 0;
        Map<Integer, String> idToBlock = new TreeMap<>();
        for (String key : paletteTag.getAllKeys()) {
            int id = paletteTag.getInt(key);
            if (id < 0) continue;
            idToBlock.put(id, key);
            if (id > maxId) maxId = id;
        }
        for (int i = 0; i <= maxId; i++) {
            String blockStr = idToBlock.getOrDefault(i, "minecraft:air");
            int propStart = blockStr.indexOf('[');
            String name;
            CompoundTag properties = new CompoundTag();
            if (propStart >= 0 && blockStr.endsWith("]")) {
                name = blockStr.substring(0, propStart);
                String propStr = blockStr.substring(propStart + 1, blockStr.length() - 1);
                for (String prop : propStr.split(",")) {
                    String[] kv = prop.split("=", 2);
                    if (kv.length == 2) {
                        properties.putString(kv[0].trim(), kv[1].trim());
                    }
                }
            } else {
                name = blockStr;
            }
            CompoundTag van = new CompoundTag();
            van.putString("Name", name);
            if (!properties.isEmpty()) {
                van.put("Properties", properties);
            }
            vanillaPalette.add(van);
        }
        return vanillaPalette;
    }

    /** 解码 varint 数组 (Sponge v2/v3 / Protobuf 风格). */
    private static int[] decodeVarIntArray(byte[] bytes) {
        java.util.List<Integer> result = new java.util.ArrayList<>();
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes))) {
            while (dis.available() > 0) {
                int value = 0;
                int shift = 0;
                while (true) {
                    int b = dis.read();
                    if (b < 0) break;
                    value |= (b & 0x7F) << shift;
                    if ((b & 0x80) == 0) break;
                    shift += 7;
                    if (shift > 35) {
                        PrefabCustomAddon.LOGGER.warn("[NBT-DEBUG] varint 解码过长, 截断 (已读 {} 个)", result.size());
                        return result.stream().mapToInt(Integer::intValue).toArray();
                    }
                }
                result.add(value);
            }
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[NBT-DEBUG] varint 解码失败: {}", t.getMessage());
        }
        return result.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * 把第三方格式 (litematica / sponge) 的元信息字符串复制到 root 上, 用 _meta_* 命名空间,
     * NbtStructureParser 后续会读出来塞到 NbtInfo 里给 GUI 自动填表用。
     * srcKey 不存在 / 不是 StringTag / 内容为空 → 跳过, 不覆盖已有值。
     */
    private static void attachMetaString(CompoundTag dest, CompoundTag src, String srcKey, String destKey) {
        if (src == null || dest == null) return;
        if (!src.contains(srcKey, Tag.TAG_STRING)) return;
        String v = src.getString(srcKey);
        if (v == null) return;
        v = v.trim();
        if (v.isEmpty()) return;
        dest.putString(destKey, v);
    }

    /**
     * 把 _meta_name / _meta_author / _meta_description 三个字段从 src 复制到 dest.
     * 用于 NbtFormatConverter: 在 litematicaToVanilla / spongeToVanilla 中,
     * attachMetaString 暂存在原 root, 转换完 vanilla 后调用此方法复制过去.
     * 关键: 不复制到原 root 上, 否则 vanilla 字节写出去时字段会丢失!
     */
    private static void copyMetaFields(CompoundTag src, CompoundTag dest) {
        if (src == null || dest == null) return;
        for (String key : new String[]{"_meta_name", "_meta_author", "_meta_description"}) {
            if (src.contains(key, Tag.TAG_STRING)) {
                String v = src.getString(key);
                if (v != null && !v.isEmpty()) {
                    dest.putString(key, v);
                }
            }
        }
    }

    /**
     * 兼容多种 NBT 存储格式读 3-int 数组 (litematica 的 Position / Size).
     *  - CompoundTag{x,y,z} 或 {X,Y,Z} (litematica 实际格式)
     *  - IntArrayTag       (老版本 / 第三方工具)
     *  - ListTag<3*IntTag> (极端老版本)
     *  - 拆分键 SizeX/SizeY/SizeZ
     * 读不到时返回长度 0 的空数组.
     */
    private static int[] readSizeOrPos(CompoundTag tag, String key, int expected) {
        if (tag == null || !tag.contains(key)) {
            int[] split = readSplitKey(tag, key, expected);
            if (split.length >= expected) return split;
            return new int[0];
        }
        Tag t = tag.get(key);
        // 1) CompoundTag{x,y,z} 或 {X,Y,Z} (litematica 实际格式)
        if (t.getId() == Tag.TAG_COMPOUND) {
            CompoundTag c = (CompoundTag) t;
            int[] a = new int[expected];
            String[] xs = {"x", "X"};
            String[] ys = {"y", "Y"};
            String[] zs = {"z", "Z"};
            a[0] = readIntFlexible(c, xs);
            a[1] = readIntFlexible(c, ys);
            a[2] = readIntFlexible(c, zs);
            if (a[0] == 0 && a[1] == 0 && a[2] == 0 && !containsAnyNumeric(c, xs[0])
                    && !containsAnyNumeric(c, xs[1])) {
                return new int[0];
            }
            return a;
        }
        // 2) IntArray
        if (t.getId() == Tag.TAG_INT_ARRAY) {
            int[] a = ((IntArrayTag) t).getAsIntArray();
            if (a.length >= expected) return a;
            return a;
        }
        // 3) ByteArray (罕见, 仍然支持)
        if (t.getId() == Tag.TAG_BYTE_ARRAY) {
            byte[] b = ((ByteArrayTag) t).getAsByteArray();
            int[] a = new int[b.length];
            for (int i = 0; i < b.length; i++) a[i] = b[i];
            if (a.length >= expected) return a;
            return a;
        }
        // 4) ListTag
        if (t.getId() == Tag.TAG_LIST) {
            ListTag list = (ListTag) t;
            int[] a = new int[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Tag el = list.get(i);
                if (el.getId() == Tag.TAG_INT) a[i] = ((IntTag) el).getAsInt();
                else if (el.getId() == Tag.TAG_BYTE) a[i] = ((ByteTag) el).getAsByte();
                else if (el.getId() == Tag.TAG_SHORT) a[i] = ((ShortTag) el).getAsShort();
                else if (el.getId() == Tag.TAG_LONG) a[i] = (int) ((LongTag) el).getAsLong();
                else if (el.getId() == Tag.TAG_FLOAT) a[i] = (int) ((FloatTag) el).getAsFloat();
                else if (el.getId() == Tag.TAG_DOUBLE) a[i] = (int) ((DoubleTag) el).getAsDouble();
                else { return new int[0]; }
            }
            if (a.length >= expected) return a;
            return a;
        }
        // 5) 单个 IntTag → 长度 1
        if (t.getId() == Tag.TAG_INT) {
            return new int[]{((IntTag) t).getAsInt()};
        }
        // 6) 拆键回退
        int[] split = readSplitKey(tag, key, expected);
        if (split.length >= expected) return split;
        return new int[0];
    }

    private static int readIntFlexible(CompoundTag c, String[] keys) {
        for (String k : keys) {
            if (c.contains(k, Tag.TAG_INT)) return c.getInt(k);
            if (c.contains(k, Tag.TAG_BYTE)) return c.getByte(k);
            if (c.contains(k, Tag.TAG_SHORT)) return c.getShort(k);
            if (c.contains(k, Tag.TAG_LONG)) return (int) c.getLong(k);
        }
        return 0;
    }

    private static int[] readSplitKey(CompoundTag tag, String baseKey, int expected) {
        String x = baseKey + "X";
        String y = baseKey + "Y";
        String z = baseKey + "Z";
        if (tag != null && containsAnyNumeric(tag, x) && containsAnyNumeric(tag, y) && containsAnyNumeric(tag, z)) {
            return new int[]{tag.getInt(x), tag.getInt(y), tag.getInt(z)};
        }
        return new int[0];
    }

    private static boolean containsAnyNumeric(CompoundTag tag, String key) {
        if (tag == null || !tag.contains(key)) return false;
        int id = tag.get(key).getId();
        return id == Tag.TAG_INT || id == Tag.TAG_SHORT || id == Tag.TAG_LONG
                || id == Tag.TAG_BYTE || id == Tag.TAG_FLOAT || id == Tag.TAG_DOUBLE;
    }
}
