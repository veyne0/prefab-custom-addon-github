package com.prefab.addon.extension;

import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Litematica .litematic 文件解析器。
 *
 * 参考: fi.dy.masa.litematica.schematic.LitematicaSchematic
 *
 * Litematica 格式 (top level NBT):
 *   - Version: int
 *   - MinecraftDataVersion: int
 *   - Metadata: compound
 *   - Regions: compound {regionName -> {BlockStatePalette, BlockStates (long[]), Position, Size, ...}}
 *
 * BlockStates 是 packed long array，每个 block 的 palette index 按位打包
 * bit 宽度 = max(2, 32 - numberOfLeadingZeros(paletteSize - 1))
 *
 * 解析后转换为 vanilla structure 格式 (size, palette, blocks=[{pos, state}])：
 *   这样 CustomStructureBuilder.parseStandardStructure 不用改
 */
public class LitematicaParser {

    /**
     * 解析 .litematic 文件 → 标准 vanilla structure 格式 NBT (压缩后)
     */
    public static byte[] parseToStandardStructureBytes(Path litematicFile) throws IOException {
        byte[] raw = Files.readAllBytes(litematicFile);
        // 蓝图站 (mcschematic.top) 部分建筑下载下来其实是 JSON 格式
        // ({"name":"","statePosArrayList":"{blockstatemap:[...],...}","requiredItems":{...}}),
        // 只是扩展名挂了 .litematic. 按首字符 '{' 识别, 走 JSON 解析.
        int first = 0;
        while (first < raw.length && Character.isWhitespace(raw[first])) first++;
        if (first < raw.length && raw[first] == '{') {
            return convertJsonToStandardStructure(raw, litematicFile.toString());
        }
        CompoundTag root = readRootTag(raw, litematicFile.toString());
        return convertToStandardStructure(root);
    }

    /**
     * 蓝图站 JSON 格式 → 标准 vanilla structure NBT (压缩后).
     *
     * <pre>
     * {
     *   "name": "",
     *   "statePosArrayList": "{blockstatemap:[{Name:\"minecraft:air\"},{Name:\"xxx\",Properties:{...}},...],
     *                          endpos:{X:0,Y:0,Z:0},startpos:{X:-8,Y:-8,Z:-8},
     *                          statelist:[I;0,0,1,...]}",
     *   "requiredItems": { ... }
     * }
     * </pre>
     * statelist 每格一个 palette 索引, 排列顺序按 vanilla/litematica 惯例:
     * x 最快, z 其次, y 最慢 (index = (y*sizeZ + z)*sizeX + x), 相对 startpos.
     */
    private static byte[] convertJsonToStandardStructure(byte[] raw, String fileName) throws IOException {
        com.google.gson.JsonObject obj;
        try {
            obj = com.google.gson.JsonParser.parseString(new String(raw, java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonObject();
        } catch (Exception e) {
            throw new IOException("JSON 蓝图解析失败: " + e.getMessage(), e);
        }
        if (!obj.has("statePosArrayList") || !obj.get("statePosArrayList").isJsonPrimitive()) {
            throw new IOException("JSON 蓝图缺少 statePosArrayList 字段");
        }
        // 内嵌 SNBT (无外层大括号), 包一层后用 TagParser 解析
        CompoundTag tag;
        try {
            tag = net.minecraft.nbt.TagParser.parseTag("{" + obj.get("statePosArrayList").getAsString() + "}");
        } catch (Exception e) {
            throw new IOException("JSON 蓝图 statePosArrayList 解析失败: " + e.getMessage(), e);
        }

        ListTag paletteSrc = tag.getList("blockstatemap", 10);
        int[] states = tag.getIntArray("statelist");
        CompoundTag start = tag.getCompound("startpos");
        CompoundTag end = tag.getCompound("endpos");
        if (paletteSrc.isEmpty() || states.length == 0 || start.isEmpty() || end.isEmpty()) {
            throw new IOException("JSON 蓝图字段不完整: palette=" + paletteSrc.size()
                + " states=" + states.length);
        }
        int startX = start.getInt("X"), startY = start.getInt("Y"), startZ = start.getInt("Z");
        int endX = end.getInt("X"), endY = end.getInt("Y"), endZ = end.getInt("Z");
        int sizeX = Math.abs(endX - startX) + 1;
        int sizeY = Math.abs(endY - startY) + 1;
        int sizeZ = Math.abs(endZ - startZ) + 1;
        if ((long) sizeX * sizeY * sizeZ != states.length) {
            throw new IOException("JSON 蓝图 statelist 数量不匹配: 期望 " + sizeX + "*" + sizeY + "*" + sizeZ
                + "=" + ((long) sizeX * sizeY * sizeZ) + ", 实际 " + states.length
                + " (排列顺序与预期不符?)");
        }

        CompoundTag output = new CompoundTag();

        ListTag paletteOut = new ListTag();
        // blockstatemap 条目本身就是 vanilla palette 格式 {Name, Properties?}, 原样拷贝
        for (int i = 0; i < paletteSrc.size(); i++) {
            paletteOut.add(paletteSrc.getCompound(i).copy());
        }
        output.put("palette", paletteOut);

        ListTag blocksList = new ListTag();
        int added = 0, skippedAir = 0;
        for (int i = 0; i < states.length; i++) {
            int stateIdx = states[i];
            if (stateIdx < 0 || stateIdx >= paletteSrc.size()) {
                continue; // 越界索引, 跳过
            }
            if (isAirBlock(paletteSrc.getCompound(stateIdx))) {
                skippedAir++;
                continue;
            }
            // x 最快, z 其次, y 最慢, 相对 startpos (min corner)
            int x = i % sizeX;
            int rem = i / sizeX;
            int z = rem % sizeZ;
            int y = rem / sizeZ;
            CompoundTag blockTag = new CompoundTag();
            blockTag.put("pos", new IntArrayTag(new int[]{x, y, z}));
            blockTag.put("state", IntTag.valueOf(stateIdx));
            blocksList.add(blockTag);
            added++;
        }
        output.put("blocks", blocksList);
        ListTag sizeListTag = new ListTag();
        sizeListTag.add(IntTag.valueOf(sizeX));
        sizeListTag.add(IntTag.valueOf(sizeY));
        sizeListTag.add(IntTag.valueOf(sizeZ));
        output.put("sizeList", sizeListTag);
        output.putInt("DataVersion", 0);

        PrefabCustomAddon.LOGGER.info("[LITEMATICA] JSON 蓝图转换完成: {} size={}x{}x{} palette={} added={} skippedAir={}",
            fileName, sizeX, sizeY, sizeZ, paletteSrc.size(), added, skippedAir);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        NbtIo.writeCompressed(output, bos);
        return bos.toByteArray();
    }

    /**
     * 读顶层 NBT, 自动识别 gzip 压缩 / 未压缩两种存储.
     *
     * <p>Litematica mod 自己写文件永远是 gzip, 但**蓝图站 (mcschematic.top) 下载的
     * .litematic 有相当一部分是未压缩 NBT** — 直接走 NbtIo.readCompressed 会抛
     * "ZipException: Not in GZIP format" → 转换失败 → 结构为空. 按文件头魔数
     * (0x1F 0x8B) 分流: gzip → readCompressed; 否则 gzip 包一层再解.</p>
     */
    private static CompoundTag readRootTag(byte[] raw, String fileName) throws IOException {
        boolean gzip = raw.length >= 2
            && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B;
        if (gzip) {
            return NbtIo.readCompressed(new ByteArrayInputStream(raw));
        }
        // 未压缩 NBT: 先 gzip 包一层, 再走 readCompressed
        PrefabCustomAddon.LOGGER.info("[LITEMATICA] {} 是未压缩 NBT (非 gzip), 自动包装后解析", fileName);
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gos = new java.util.zip.GZIPOutputStream(gz)) {
            gos.write(raw);
        }
        try {
            return NbtIo.readCompressed(new ByteArrayInputStream(gz.toByteArray()));
        } catch (IOException e) {
            // gzip 包装后仍解析失败: 打印前 16 字节帮助判断文件真实格式 (HTML? 纯文本?)
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < Math.min(16, raw.length); i++) {
                hex.append(String.format("%02X ", raw[i]));
            }
            PrefabCustomAddon.LOGGER.error("[LITEMATICA] {} NBT 解析失败 (文件头: {}): {}",
                fileName, hex.toString().trim(), e.getMessage());
            throw e;
        }
    }

    /**
     * 把 litematica NBT root 转换为标准结构 NBT（返回压缩后字节）
     */
    public static byte[] convertToStandardStructure(CompoundTag root) throws IOException {
        if (!root.contains("Regions", 10)) {
            throw new IOException("Not a litematic file: missing Regions tag");
        }

        CompoundTag regions = root.getCompound("Regions");
        if (regions.isEmpty()) {
            throw new IOException("Litematic has no regions");
        }

        // 策略：取第一个 region（最常见的情况）
        // 多 region 文件目前不支持合并 - 加载多个独立的 ConstructionInfo
        // 这里我们仅处理单 region 文件
        java.util.Set<String> regionKeys = regions.getAllKeys();
        if (regionKeys.isEmpty()) {
            throw new IOException("Litematic has no regions");
        }
        String firstRegionName = regionKeys.iterator().next();
        CompoundTag region = regions.getCompound(firstRegionName);

        PrefabCustomAddon.LOGGER.info("[LITEMATICA] Converting region '{}' from .litematic", firstRegionName);

        // 读取 palette
        if (!region.contains("BlockStatePalette", 9)) {
            throw new IOException("Region missing BlockStatePalette");
        }
        ListTag palette = region.getList("BlockStatePalette", 10);
        int paletteSize = palette.size();

        // 读取 BlockStates (long array)
        if (!region.contains("BlockStates", 12)) {
            throw new IOException("Region missing BlockStates");
        }
        long[] blockStateArr = ((LongArrayTag) region.get("BlockStates")).getAsLongArray();

        // 读取 Position 和 Size
        int posX = 0, posY = 0, posZ = 0;
        if (region.contains("Position", 10)) {
            CompoundTag pos = region.getCompound("Position");
            posX = pos.getInt("x");
            posY = pos.getInt("y");
            posZ = pos.getInt("z");
        }
        int sizeX, sizeY, sizeZ;
        if (region.contains("Size", 10)) {
            CompoundTag size = region.getCompound("Size");
            // litematica 规范: Size 带符号只表示 "Position 是该轴的 max corner" (从 Position 反向延伸),
            // BlockStates 的 packed 顺序永远是正向 0..size-1, **与 size 符号无关**.
            // 不取绝对值的话, 蓝图站上 Size.y<0 的文件 totalBlocks<=0 → 一个方块都不进 → 预览全 air.
            sizeX = Math.abs(size.getInt("x"));
            sizeY = Math.abs(size.getInt("y"));
            sizeZ = Math.abs(size.getInt("z"));
        } else {
            throw new IOException("Region missing Size");
        }
        PrefabCustomAddon.LOGGER.info("[LITEMATICA] Region size: {}x{}x{} pos=({},{},{}) palette={} longArrayLen={}",
            sizeX, sizeY, sizeZ, posX, posY, posZ, paletteSize, blockStateArr.length);

        // bit 宽度
        int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(paletteSize - 1));
        long mask = (1L << bits) - 1;
        long totalBlocks = (long) sizeX * sizeY * sizeZ;

        // 构造标准结构 NBT
        CompoundTag output = new CompoundTag();
        // 关键: vanilla 1.21.1 StructureTemplate.load() 读 "size" 用 BlockPos.CODEC.
        // BlockPos.CODEC 是 list codec (encodeStart(JsonOps) -> [1,2,3]), 通过 NbtOps 解码 NBT 时
        // 期待 **ListTag (3 个 IntTag)**. 写 IntArrayTag / CompoundTag 都会被 BlockPos.CODEC 静默拒收
        // → size=BlockPos.ZERO → placeInWorld 啥都放不了.
        ListTag sizeObj = new ListTag();
        sizeObj.add(IntTag.valueOf(sizeX));
        sizeObj.add(IntTag.valueOf(sizeY));
        sizeObj.add(IntTag.valueOf(sizeZ));
        output.put("size", sizeObj);
        // 旧版兼容: 保留 IntArrayTag 写到 sizeList key, 方便旧代码看. 实际 load() 不会读这个.
        output.put("sizeList", new IntArrayTag(new int[]{sizeX, sizeY, sizeZ}));
        output.put("palette", palette.copy());

        // 构造 blocks 列表
        ListTag blocksList = new ListTag();
        int added = 0, skippedAir = 0, skippedOOB = 0;
        for (long blockIndex = 0; blockIndex < totalBlocks; blockIndex++) {
            // 解包 long array
            int longIndex = (int) ((blockIndex * bits) / 64);
            int bitOffset = (int) ((blockIndex * bits) % 64);
            if (longIndex >= blockStateArr.length) {
                skippedOOB++;
                continue;
            }
            long value = (blockStateArr[longIndex] >>> bitOffset) & mask;
            // 如果跨 long, 还要从下一个 long 取高位
            if (bitOffset + bits > 64 && longIndex + 1 < blockStateArr.length) {
                long highBits = blockStateArr[longIndex + 1];
                value |= (highBits << (64 - bitOffset)) & mask;
            }
            int stateIndex = (int) value;
            if (stateIndex < 0 || stateIndex >= paletteSize) {
                skippedOOB++;
                continue;
            }

            // 计算 (x, y, z) - litematica 内部坐标: y 变化最慢
            int y = (int) (blockIndex / ((long) sizeX * sizeZ));
            long rem = blockIndex % ((long) sizeX * sizeZ);
            int z = (int) (rem / sizeX);
            int x = (int) (rem % sizeX);

            // 跳过 air (litematica 通常把 air 放在 palette[0])
            CompoundTag paletteEntry = palette.getCompound(stateIndex);
            if (isAirBlock(paletteEntry)) {
                skippedAir++;
                continue;
            }

            // 加 pos + state index
            // **关键 1**: 标准 structure 格式中 pos 必须是**相对 (0,0,0) 的偏移**, 不是世界绝对坐标!
            // litematica 的 Position 字段是 region 在世界的**绝对位置** (通常为负, 表示 region 中心对齐到世界原点).
            // 如果加了 posX/Y/Z, 块坐标会跑到 (例如) [-17, 0, 2] 范围, 导致 Prefab 的 chunk 计算错乱、预览一片空.
            // 所以**不要**加 posX, posY, posZ; 直接用 (x, y, z) 即可.
            // **关键 2**: vanilla StructureTemplate.load() 读 "pos" 用 TAG_INT_ARRAY (11), 不是 ListTag.
            // 之前一版用 ListTag, 1.21.1 的 StructureTemplate 拒收 → placeInWorld 返回 false (size/blocks/palette 一切看起来都对,
            // 但 pos 字段格式不对, 整批 block 都被丢了, placeInWorld 静默返回 0).
            // 修法: 用 IntArrayTag (3 个 int), 跟 vanilla 自己写出来的 structure NBT 一致.
            CompoundTag blockTag = new CompoundTag();
            blockTag.put("pos", new IntArrayTag(new int[]{x, y, z}));
            blockTag.put("state", IntTag.valueOf(stateIndex));
            blocksList.add(blockTag);
            added++;
        }
        output.put("blocks", blocksList);
        // 兼容读 path: parseToPrefabStructure (CustomStructureBuilder) 用 getList("size", 3) 读 sizeList,
        // vanilla StructureTemplate.load() 用 BlockPos.CODEC 读 "size".
        // 关键: 不要再用同一 key 写 ListTag, 会覆盖 IntArrayTag 让 vanilla 读不到.
        // 这里用另一个 key "sizeList" 存 ListTag, 给 CustomStructureBuilder 内部用.
        ListTag sizeListTag = new ListTag();
        sizeListTag.add(IntTag.valueOf(sizeX));
        sizeListTag.add(IntTag.valueOf(sizeY));
        sizeListTag.add(IntTag.valueOf(sizeZ));
        output.put("sizeList", sizeListTag);
        output.putInt("DataVersion", root.contains("MinecraftDataVersion", 3) ? root.getInt("MinecraftDataVersion") : 0);

        // === 提取 Litematica 的 Metadata (Name / Author / Description) ===
        // Litematica 文件顶层的 Metadata CompoundTag 包含作者等信息:
        //   { Name: "My Build", Author: "Steve", Description: "...", TotalBlocks, TimeCreated, ... }
        // 挂在 output._meta_* 字段, NbtStructureParser 读出来后填到 NbtInfo.metaName / metaAuthor / metaDescription
        if (root.contains("Metadata", 10)) {
            CompoundTag meta = root.getCompound("Metadata");
            attachMetaString(output, meta, "Name", "_meta_name");
            attachMetaString(output, meta, "Author", "_meta_author");
            attachMetaString(output, meta, "Description", "_meta_description");
            PrefabCustomAddon.LOGGER.info("[LITEMATICA] Metadata: name={} author={} desc={}",
                output.contains("_meta_name") ? output.getString("_meta_name") : "(none)",
                output.contains("_meta_author") ? output.getString("_meta_author") : "(none)",
                output.contains("_meta_description") ? output.getString("_meta_description") : "(none)");
        } else {
            PrefabCustomAddon.LOGGER.info("[LITEMATICA] 无 Metadata 字段");
        }

        PrefabCustomAddon.LOGGER.info("[LITEMATICA] Converted: added={} skippedAir={} skippedOOB={}", added, skippedAir, skippedOOB);

        // 调试: 打印前 3 个 block 的 pos + state, 方便验证 NBT 格式是否被 vanilla 接受.
        if (PrefabCustomAddon.LOGGER.isDebugEnabled() && !blocksList.isEmpty()) {
            for (int i = 0; i < Math.min(3, blocksList.size()); i++) {
                CompoundTag bt = blocksList.getCompound(i);
                PrefabCustomAddon.LOGGER.debug("[LITEMATICA] sample block[{}]: pos={} state={}",
                    i, bt.get("pos"), bt.get("state"));
            }
        }

        // 压缩为字节
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        NbtIo.writeCompressed(output, bos);
        return bos.toByteArray();
    }

    private static boolean isAirBlock(CompoundTag paletteEntry) {
        if (paletteEntry.contains("Name", 8)) {
            String name = paletteEntry.getString("Name");
            return "minecraft:air".equals(name) || "minecraft:cave_air".equals(name) || "minecraft:void_air".equals(name);
        }
        return false;
    }

    /**
     * 把 litematica 元信息字符串复制到 dest 上, 用 _meta_* 命名空间.
     * 缺失/非 String/空内容 → 跳过, 不覆盖已有值.
     * 供 NbtStructureParser 后续读取.
     */
    private static void attachMetaString(CompoundTag dest, CompoundTag src, String srcKey, String destKey) {
        if (src == null || dest == null) return;
        if (!src.contains(srcKey, 8)) return;
        String v = src.getString(srcKey);
        if (v == null) return;
        v = v.trim();
        if (v.isEmpty()) return;
        dest.putString(destKey, v);
    }
}
