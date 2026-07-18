package com.prefab.addon.extension;

import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Sponge Schematic 单文件 (.schem / .schematic) 解析器 (1.21.1 NeoForge 版).
 *
 * <p>.schem 是 Sponge Schematic v3 (1.13+), .schematic 是 v2 (1.12 及更早, 兼容老 mod).
 * 通过 {@link com.prefab.addon.work.NbtFormatConverter} 转 vanilla structure NBT,
 * 然后保存为一个虚拟的 ExtensionPack.
 */
public class SpongeSchematicParser {

    /**
     * 从 .schem / .schematic 文件读 NBT.
     * Sponge Schematic 主流格式是 gzip 压缩的 NBT, 部分工具会输出未压缩版本, 加兜底.
     */
    public static CompoundTag readRoot(Path file) throws IOException {
        // 先尝试 gzip 压缩 (Sponge 主流格式)
        try (InputStream in = Files.newInputStream(file)) {
            try {
                return NbtIo.readCompressed(in, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            } catch (IOException compressed) {
                // 兜底: 尝试未压缩
                try (InputStream in2 = Files.newInputStream(file);
                     DataInputStream din = new DataInputStream(in2)) {
                    return NbtIo.read(din);
                }
            }
        }
    }

    /**
     * 加载 .schem / .schematic 文件, 转 vanilla NBT, 包装成 ExtensionPack 直接加到 packs 列表里.
     * (1.21.1 的 ExtensionPackManager.scanExtensionPacks 风格)
     */
    public static ExtensionPack loadAsPackIntoList(List<ExtensionPack> packs, Path schemFile,
                                                    boolean isServerCache,
                                                    Map<String, Long> mtimeMap, String contentSha1) {
        if (schemFile == null) return null;
        String fname = schemFile.getFileName().toString().toLowerCase();
        if (!fname.endsWith(".schem") && !fname.endsWith(".schematic")) {
            return null;
        }
        long start = System.currentTimeMillis();
        try {
            CompoundTag root = readRoot(schemFile);
            if (root == null) {
                PrefabCustomAddon.LOGGER.warn("[SCHEM] 无法读 NBT 根: {}", schemFile);
                return null;
            }

            String fmt = com.prefab.addon.work.NbtFormatConverter.detectFormat(root);
            PrefabCustomAddon.LOGGER.info("[SCHEM] 检测到格式: {} (文件 {})", fmt, schemFile.getFileName());
            if (!fmt.startsWith("sponge_")) {
                PrefabCustomAddon.LOGGER.warn("[SCHEM] 跳过, 不支持的格式: {}", fmt);
                return null;
            }

            // 转 vanilla NBT
            CompoundTag vanilla = com.prefab.addon.work.NbtFormatConverter.toVanilla(root);
            if (vanilla == null) {
                PrefabCustomAddon.LOGGER.warn("[SCHEM] 转换 vanilla NBT 失败: {}", schemFile);
                return null;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(vanilla, bos);
            byte[] nbtBytes = bos.toByteArray();

            // 解析 Size 字段
            int w = 0, h = 0, l = 0;
            if (root.contains("Width", 2)) w = root.getShort("Width");
            if (root.contains("Height", 2)) h = root.getShort("Height");
            if (root.contains("Length", 2)) l = root.getShort("Length");

            // 构造 ConstructionInfo
            String constructionId = stripExtension(schemFile.getFileName().toString());
            // 兼容 ConstructionInfo(id) 构造器 (id 是 final, 没有 setter)
            ConstructionInfo info = new ConstructionInfo(constructionId);
            info.setName(constructionId);
            info.setDescription("从 " + schemFile.getFileName() + " 导入 (" + fmt + ")");
            info.setNbtData(nbtBytes);
            info.setAuthor("");

            // 构造 ExtensionPack
            String packName = schemFile.getFileName().toString();
            ExtensionPack pack = new ExtensionPack();
            pack.setName(packName);
            pack.setPackageName(packName);
            pack.setFilePath(schemFile.toString());
            pack.setFileName(packName);
            pack.setFileSize(schemFile.toFile().length());
            pack.setContentSha1(contentSha1 != null ? contentSha1 : "");
            pack.setTotalBlocks((long) w * h * l);
            pack.setDescription("Sponge Schematic " + fmt);
            pack.setMtimeMs(safeMTime(schemFile));
            pack.setServerBacked(isServerCache);
            pack.setSpongeSchematic(true);

            java.util.List<ConstructionInfo> constructions = new java.util.ArrayList<>();
            constructions.add(info);
            pack.setConstructions(constructions);

            if (mtimeMap != null) {
                mtimeMap.put(packName, safeMTime(schemFile));
            }

            packs.add(pack);
            PrefabCustomAddon.LOGGER.info("[SCHEM] Loaded '{}' as pack '{}' (size={}x{}x{}, {} bytes, {} ms)",
                    packName, packName, w, h, l, nbtBytes.length, System.currentTimeMillis() - start);
            return pack;
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("[SCHEM] Failed to load: {}", schemFile, e);
            return null;
        }
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static long safeMTime(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (Exception e) { return 0; }
    }
}
