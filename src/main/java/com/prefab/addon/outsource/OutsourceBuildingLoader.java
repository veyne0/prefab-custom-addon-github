package com.prefab.addon.outsource;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.LitematicaParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 外包建筑扫描器(单例)。
 *
 * 工作流程:
 *   1. scan(gameDir) → 扫 &lt;gameDir&gt;/prefab-outsource/*.zip
 *   2. 每个 ZIP:
 *      - 解析 entries
 *      - 按"子文件夹"分组:子文件夹名 = 蓝图名(folderName)
 *      - 子文件夹下的 .nbt / .litematic / .schem = 该建筑的风格
 *   3. 把每组包装成 OutsourceBuilding(一个 ZIP 一组,跨 ZIP 互不影响)
 *   4. ZIP 解析失败 / 空 ZIP 跳过,日志警告
 *
 * ZIP 内结构示例:
 *   投影.zip/
 *     中式茶楼/
 *       茶楼_主楼.nbt
 *       茶楼_配楼.nbt
 *     樱花神社/
 *       神社_主殿.litematic
 *
 * → 扫出 2 个 OutsourceBuilding:
 *   1) id="投影_中式茶楼", folderName="中式茶楼", styles=2 个
 *   2) id="投影_樱花神社", folderName="樱花神社", styles=1 个
 */
public final class OutsourceBuildingLoader {

    /** 游戏目录下放 ZIP 建筑包的文件夹名 */
    public static final String OUTSOURCE_DIR_NAME = "prefab-outsource";
    /** 所有建筑统一作者(从 UP 主授权信息硬编码) */
    public static final String DEFAULT_AUTHOR = "b站UP主:Uniek-夜辉";

    private static final OutsourceBuildingLoader INSTANCE = new OutsourceBuildingLoader();

    public static OutsourceBuildingLoader getInstance() { return INSTANCE; }

    private final List<OutsourceBuilding> buildings = new ArrayList<>();
    private volatile Path lastScannedDir;

    private OutsourceBuildingLoader() {}

    public List<OutsourceBuilding> getBuildings() {
        return Collections.unmodifiableList(buildings);
    }

    public OutsourceBuilding getBuildingById(String id) {
        if (id == null) return null;
        for (OutsourceBuilding b : buildings) {
            if (id.equals(b.getId())) return b;
        }
        return null;
    }

    public Path getLastScannedDir() { return lastScannedDir; }

    /**
     * 扫描 &lt;gameDir&gt;/prefab-outsource/ 下所有 .zip。
     * 目录不存在则创建空目录 + 写 README.txt 提示玩家放 ZIP。
     *
     * @return 扫到的建筑总数
     */
    public synchronized int scan(Path gameDir) {
        buildings.clear();
        if (gameDir == null) {
            PrefabCustomAddon.LOGGER.warn("[OUTSOURCE] scan: gameDir is null, skip");
            return 0;
        }
        Path dir = gameDir.resolve(OUTSOURCE_DIR_NAME);
        lastScannedDir = dir;
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
                // 写 README 提示
                Path readme = dir.resolve("README.txt");
                String content =
                    "外包建筑 ZIP 建筑包\n" +
                    "==================\n\n" +
                    "把 UP 主提供的 .zip 建筑包放进这个文件夹即可被 mod 自动识别。\n\n" +
                    "ZIP 内结构要求:\n" +
                    "  <子文件夹名>/<投影文件>.nbt 或 .litematic\n" +
                    "  ↑ 子文件夹名 = 蓝图显示名(可中文)\n" +
                    "  ↑ 投影文件 = 该建筑的一种风格(可多个)\n\n" +
                    "示例:\n" +
                    "  投影.zip/\n" +
                    "    中式茶楼/\n" +
                    "      茶楼_主楼.nbt\n" +
                    "      茶楼_配楼.nbt\n" +
                    "    樱花神社/\n" +
                    "      神社.litematic\n\n" +
                    "扫描后,游戏内创造栏会显示这些建筑的蓝图物品。\n" +
                    "右键蓝图 → 看到 3D 预览 → 点 [选择] 建造。\n\n" +
                    "修改 ZIP 后,游戏内执行 /prefabaddon reload 可热重载。\n";
                Files.writeString(readme, content);
                PrefabCustomAddon.LOGGER.info("[OUTSOURCE] 创建扫描目录: {} (含 README.txt)", dir);
            } catch (IOException e) {
                PrefabCustomAddon.LOGGER.error("[OUTSOURCE] 创建目录失败: {}", dir, e);
                return 0;
            }
            return 0;
        }
        if (!Files.isDirectory(dir)) {
            PrefabCustomAddon.LOGGER.error("[OUTSOURCE] 路径不是目录: {}", dir);
            return 0;
        }

        int totalZips = 0;
        int totalBuildings = 0;
        try (var stream = Files.list(dir)) {
            List<Path> zips = new ArrayList<>();
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                  .forEach(zips::add);
            // 按文件名排序,保证扫描顺序稳定(便于 debug)
            zips.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
            for (Path zip : zips) {
                totalZips++;
                int added = parseSingleZip(zip);
                totalBuildings += added;
            }
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("[OUTSOURCE] 扫描目录失败: {}", dir, e);
        }
        PrefabCustomAddon.LOGGER.info("[OUTSOURCE] 扫描完成: {} 个 ZIP, {} 个建筑 (共 {} 种风格)",
            totalZips, buildings.size(), buildings.stream().mapToInt(OutsourceBuilding::getStyleCount).sum());
        return totalBuildings;
    }

    /**
     * 解析单个 ZIP,返回本次新增的建筑数。
     */
    private int parseSingleZip(Path zipPath) {
        String zipBaseName = stripZipExt(zipPath.getFileName().toString());
        // 暂存: folderName -> List<OutsourceStyle>
        Map<String, List<OutsourceStyle>> grouped = new LinkedHashMap<>();
        int entryCount = 0;
        int errorCount = 0;
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                // 跳过 macOS 资源文件 / Windows Thumbs.db
                if (name.contains("__MACOSX/") || name.contains(".DS_Store") || name.endsWith("/Thumbs.db")) {
                    continue;
                }
                // ZIP 路径用 '/',Linux/Windows 通用
                int slash = name.lastIndexOf('/');
                if (slash < 0) continue; // 根目录的散文件,不是子文件夹结构,跳过
                String folderName = name.substring(0, slash);
                String fileName = name.substring(slash + 1);
                String ext = extOf(fileName);
                if (!isBlueprintExt(ext)) continue; // 跳过非蓝图文件
                // 读字节
                byte[] data = readZipEntry(zip, entry);
                if (data == null || data.length == 0) {
                    errorCount++;
                    continue;
                }
                // 如果是 litematic,预先转换成 standard structure 字节,
                // 这样 CustomStructureBuilder.loadStructureFromNbt 不用关心格式.
                // forceFlipY: 某些 litematic 文件用 "Y=0 在顶部" 的反向约定
                //   (典型: 冒险者酒馆), 不翻就整栋楼上下颠倒.
                //   翻转在 NBT 层做, 预览 + 实际建造走同一份数据, 永远一致.
                if (".litematic".equals(ext)) {
                    try {
                        CompoundTag root = NbtIo.readCompressed(
                            new ByteArrayInputStream(data), NbtAccounter.unlimitedHeap());
                        boolean forceFlipY = OutsourceBuilding.shouldFlipY(folderName);
                        data = LitematicaParser.convertToStandardStructure(root, forceFlipY);
                    } catch (Throwable t) {
                        PrefabCustomAddon.LOGGER.warn("[OUTSOURCE] litematic 解析失败: {}/{} - {}",
                            zipPath.getFileName(), name, t.getMessage());
                        errorCount++;
                        continue;
                    }
                    ext = ".nbt";
                }
                // 如果是 .schem (Sponge Schematic),目前用 CustomStructureBuilder 兼容性兜底(它能解 NBT)
                // 如有需要再补 SpongeSchematicParser 调用
                grouped.computeIfAbsent(folderName, k -> new ArrayList<>())
                       .add(new OutsourceStyle(fileName, data, ext));
                entryCount++;
            }
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("[OUTSOURCE] 打开 ZIP 失败: {}", zipPath, e);
            return 0;
        }

        int added = 0;
        for (Map.Entry<String, List<OutsourceStyle>> e : grouped.entrySet()) {
            String folderName = e.getKey();
            List<OutsourceStyle> styles = e.getValue();
            if (styles.isEmpty()) continue;
            String id = zipBaseName + "_" + folderName;
            OutsourceBuilding b = new OutsourceBuilding(id, folderName, DEFAULT_AUTHOR,
                zipPath.getFileName().toString(), styles);
            buildings.add(b);
            added++;
            PrefabCustomAddon.LOGGER.info("[OUTSOURCE]  + {} 风格数={} (来自 {})",
                folderName, styles.size(), zipPath.getFileName());
        }
        if (entryCount == 0 && errorCount == 0) {
            PrefabCustomAddon.LOGGER.warn("[OUTSOURCE] ZIP 无有效内容: {} (请检查子文件夹结构)", zipPath.getFileName());
        }
        return added;
    }

    private static byte[] readZipEntry(ZipFile zip, ZipEntry entry) {
        // 限 64MB,超大 ZIP 条目直接跳过(防止 OOM)
        long size = entry.getSize();
        if (size > 64L * 1024L * 1024L) {
            PrefabCustomAddon.LOGGER.warn("[OUTSOURCE] 跳过过大条目: {} ({} bytes)", entry.getName(), size);
            return null;
        }
        try (InputStream in = zip.getInputStream(entry);
             ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, (int) size))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("[OUTSOURCE] 读 ZIP entry 失败: {}", entry.getName(), e);
            return null;
        }
    }

    private static String stripZipExt(String filename) {
        if (filename == null) return "?";
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static String extOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static boolean isBlueprintExt(String ext) {
        return ".nbt".equals(ext) || ".litematic".equals(ext) || ".schem".equals(ext);
    }
}
