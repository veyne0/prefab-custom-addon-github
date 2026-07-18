package com.prefab.addon.work;

import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把世界里的一个区域导出成 Minecraft .nbt 结构文件.
 *
 * 输出格式跟游戏里用结构方块保存出来的一致:
 *   {
 *     size: [sizeX, sizeY, sizeZ],
 *     palette: [ {Name: "minecraft:stone", Properties: {}} ],
 *     blocks: [ {pos: [x,y,z], state: 0} ],
 *     entities: []
 *   }
 *
 * 这样导出后可以直接用本 mod 的 NbtStructureParser 重新解析回 size / modIds,
 * 也能直接被 Prefab 的"粘贴"功能读取.
 */
public class StructureBlockExporter {

    /**
     * 把 [min..max] 区域导出到 .nbt 文件 (写到 mod 工作目录的 temp 子目录).
     * @return 写出的文件路径
     */
    public static Path exportToTempNbt(Level level, BlockPos min, BlockPos max) throws Exception {
        int sizeX = Math.abs(max.getX() - min.getX()) + 1;
        int sizeY = Math.abs(max.getY() - min.getY()) + 1;
        int sizeZ = Math.abs(max.getZ() - min.getZ()) + 1;
        PrefabCustomAddon.LOGGER.info("[STRUCT-EXPORT] size: {}x{}x{} ({} blocks)",
            sizeX, sizeY, sizeZ, (long)sizeX * sizeY * sizeZ);

        // 防爆内存
        long total = (long) sizeX * sizeY * sizeZ;
        if (total > 1_000_000) {
            throw new RuntimeException("区域太大 (" + total + " 方块), 上限 1,000,000");
        }

        // 1) 收集 palette (按 (blockId, properties) 唯一化)
        List<PaletteEntry> palette = new ArrayList<>();
        Map<String, Integer> paletteIndex = new LinkedHashMap<>();
        List<int[]> blockPositions = new ArrayList<>((int) total);
        List<Integer> blockStates = new ArrayList<>((int) total);

        int airCount = 0;
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    BlockPos worldPos = new BlockPos(
                        min.getX() + x,
                        min.getY() + y,
                        min.getZ() + z);
                    BlockState bs = level.getBlockState(worldPos);
                    if (bs.isAir()) { airCount++; continue; }

                    String key = stateKey(bs);
                    Integer idx = paletteIndex.get(key);
                    if (idx == null) {
                        idx = palette.size();
                        palette.add(new PaletteEntry(bs));
                        paletteIndex.put(key, idx);
                    }
                    blockPositions.add(new int[]{x, y, z});
                    blockStates.add(idx);
                }
            }
        }
        PrefabCustomAddon.LOGGER.info("[STRUCT-EXPORT] palette={} nonAirBlocks={} air={}",
            palette.size(), blockPositions.size(), airCount);

        // 2) 写 NBT
        CompoundTag root = new CompoundTag();
        root.putIntArray("size", new int[]{sizeX, sizeY, sizeZ});
        root.put("entities", new ListTag());
        root.putInt("DataVersion", 3953);  // 1.21.1
        root.putInt("version", 1);

        ListTag paletteTag = new ListTag();
        for (PaletteEntry pe : palette) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Name", pe.id.toString());
            if (pe.props != null && !pe.props.isEmpty()) {
                CompoundTag props = new CompoundTag();
                for (Map.Entry<String, String> e : pe.props.entrySet()) {
                    props.putString(e.getKey(), e.getValue());
                }
                entry.put("Properties", props);
            }
            paletteTag.add(entry);
        }
        root.put("palette", paletteTag);

        ListTag blocksTag = new ListTag();
        for (int i = 0; i < blockPositions.size(); i++) {
            int[] p = blockPositions.get(i);
            int s = blockStates.get(i);
            CompoundTag bt = new CompoundTag();
            // 1.21.1 格式: pos 是 ListTag [x,y,z]
            ListTag posList = new ListTag();
            posList.add(net.minecraft.nbt.IntTag.valueOf(p[0]));
            posList.add(net.minecraft.nbt.IntTag.valueOf(p[1]));
            posList.add(net.minecraft.nbt.IntTag.valueOf(p[2]));
            bt.put("pos", posList);
            bt.putInt("state", s);
            blocksTag.add(bt);
        }
        root.put("blocks", blocksTag);

        // 3) 写到 temp 文件
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "prefab_custom_addon_export");
        Files.createDirectories(tempDir);
        Path out = tempDir.resolve("export_" + System.currentTimeMillis() + ".nbt");
        try (OutputStream os = Files.newOutputStream(out)) {
            NbtIo.writeCompressed(root, os);
        }
        PrefabCustomAddon.LOGGER.info("[STRUCT-EXPORT] wrote {} ({} bytes)", out, Files.size(out));
        return out;
    }

    private static String stateKey(BlockState bs) {
        StringBuilder sb = new StringBuilder();
        sb.append(BuiltInRegistries.BLOCK.getKey(bs.getBlock()).toString());
        var props = bs.getValues();
        if (!props.isEmpty()) {
            sb.append('|');
            props.forEach((p, v) -> sb.append(p.getName()).append('=').append(v).append(';'));
        }
        return sb.toString();
    }

    private static class PaletteEntry {
        final ResourceLocation id;
        final Map<String, String> props;
        PaletteEntry(BlockState bs) {
            this.id = BuiltInRegistries.BLOCK.getKey(bs.getBlock());
            this.props = new java.util.LinkedHashMap<>();
            bs.getValues().forEach((p, v) -> this.props.put(p.getName(), v.toString()));
        }
    }
}
