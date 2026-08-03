package com.prefab.addon.extension;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.download.PackDownloadManager;
import com.prefab.addon.work.PackCreator;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.stream.Stream;

/**
 * 拓展包状态服务 - 检测"本地工作区 prefab-work/"里的包 vs "安装目录 prefab-extension/"里的包.
 *
 * <p>用场景: X 键打开 {@code GuiExtensionPackCreator} (本地工作区) 时, 玩家点选一个本地包,
 * 自动扫描 prefab-extension/ 看是不是已经安装过, 以及建筑是否一致.
 *   - prefab-extension/ 没有同名包 → "可添加" + 添加按钮
 *   - prefab-extension/ 有同名包, 建筑一致 → "已添加"
 *   - prefab-extension/ 有同名包, 建筑不一致 → "已添加 (本地有改动)" + 重新添加按钮
 *
 * <p>实现细节:
 *   - 匹配规则: 以 zip **文件名** (id.zip) 匹配, 而非读包内 id 字段
 *     (因为玩家可能改了 txt 但没重新打包, 这种边缘情况不重要)
 *   - 建筑比对: 取两边包内 construction/ 目录的所有 .nbt 文件名集合, 比集合是否相同
 *     (忽略 .png / .txt 元信息, 也不比较 NBT 内容细节 - 那种粒度太严, 重新保存一次 NBT
 *     字节差异就误判了)
 */
public final class PackStatusService {

    /** 包在 prefab-extension/ 的状态 */
    public enum State {
        /** prefab-extension/ 还没有这个 id 的 zip → 显示"添加"按钮 */
        NOT_ADDED,
        /** 已添加, 建筑列表完全一致 → "已添加" */
        ADDED_SAME,
        /** 已添加, 但本地建筑列表和 prefab-extension/ 不一样 → "已添加 (本地有改动)" + 重新添加 */
        ADDED_DIFFERENT
    }

    /** 单个包的状态快照 (UI 用) */
    public static class Status {
        public final State state;
        public final Path installedZipPath;   // prefab-extension/<id>.zip (NOT_ADDED 时为 null)
        public final Set<String> installedBuildings;  // prefab-extension/ 包内的建筑 id 集合
        public final Set<String> localBuildings;      // 本地工作区 包内的建筑 id 集合

        public Status(State s, Path zip, Set<String> installed, Set<String> local) {
            this.state = s;
            this.installedZipPath = zip;
            this.installedBuildings = installed;
            this.localBuildings = local;
        }

        public String displayText() {
            switch (state) {
                case NOT_ADDED: return com.prefab.addon.PrefabCustomAddon.tr("status.not_added");
                case ADDED_SAME: return com.prefab.addon.PrefabCustomAddon.tr("status.added_same");
                case ADDED_DIFFERENT:
                    return com.prefab.addon.PrefabCustomAddon.tr("status.added_diff",
                        onlyInLocal().size(), onlyInInstalled().size());
            }
            return "?";
        }

        /** 建筑只在本地有 (相对 installed) */
        public Set<String> onlyInLocal() {
            Set<String> r = new TreeSet<>(localBuildings);
            r.removeAll(installedBuildings);
            return r;
        }

        /** 建筑只在 installed 有 (相对本地) */
        public Set<String> onlyInInstalled() {
            Set<String> r = new TreeSet<>(installedBuildings);
            r.removeAll(localBuildings);
            return r;
        }
    }

    private PackStatusService() {}

    /** 取 prefab-extension 目录 */
    public static Path getExtensionDir() {
        return PackDownloadManager.getExtensionRoot();
    }

    /**
     * 取一个本地工作区包的状态. 主入口, UI 调这个.
     */
    public static Status checkStatus(String localPackId) {
        Set<String> localBuildings = readLocalBuildings(localPackId);

        Path extDir = getExtensionDir();
        if (extDir == null || !Files.exists(extDir)) {
            return new Status(State.NOT_ADDED, null, Collections.emptySet(), localBuildings);
        }

        // prefab-extension/ 里可能有同名 zip (一个或多个 - server-cache 会有副本, 但只看主目录)
        Path[] candidates = new Path[] {
            extDir.resolve(localPackId + ".zip"),
            extDir.resolve("server-cache").resolve(localPackId + ".zip")
        };

        for (Path zip : candidates) {
            if (Files.exists(zip)) {
                Set<String> inst = readBuildingsFromZip(zip);
                State s = inst.equals(localBuildings) ? State.ADDED_SAME : State.ADDED_DIFFERENT;
                return new Status(s, zip, inst, localBuildings);
            }
        }

        return new Status(State.NOT_ADDED, null, Collections.emptySet(), localBuildings);
    }

    /**
     * 把本地工作区的包添加到 prefab-extension/ (生成 zip 拷贝过去).
     * @return 添加成功后的 zip 路径, 失败返回 null
     */
    public static Path addToExtension(String localPackId) {
        Path workRoot = PackCreator.getWorkRoot();
        Path packDir = workRoot.resolve(localPackId);
        if (!Files.exists(packDir)) {
            PrefabCustomAddon.LOGGER.error("[PACK-STATUS] 本地包不存在: {}", localPackId);
            return null;
        }

        Path extDir = getExtensionDir();
        if (extDir == null) {
            PrefabCustomAddon.LOGGER.error("[PACK-STATUS] 无法解析 prefab-extension 目录");
            return null;
        }
        try {
            Files.createDirectories(extDir);
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("[PACK-STATUS] 创建 prefab-extension 失败", e);
            return null;
        }

        Path targetZip = extDir.resolve(localPackId + ".zip");

        // 用 PackCreator.createZipFor 重新打包到工作区根, 再拷过去
        // (不直接打包到 prefab-extension 因为路径在不同盘可能跨设备)
        Path tempZip;
        try {
            tempZip = PackCreator.getInstance().createZipFor(packDir, localPackId);
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("[PACK-STATUS] 打包 zip 失败", e);
            return null;
        }

        try {
            // 覆盖: 删除旧的再拷
            if (Files.exists(targetZip)) Files.delete(targetZip);
            Files.copy(tempZip, targetZip, StandardCopyOption.REPLACE_EXISTING);
            PrefabCustomAddon.LOGGER.info("[PACK-STATUS] 已添加 {} → {}", localPackId, targetZip);
            return targetZip;
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("[PACK-STATUS] 复制到 prefab-extension 失败", e);
            return null;
        }
    }

    // ===== 私有方法 =====

    /** 读本地工作区指定包内的建筑 id 集合 (从 construction/ 目录的 .nbt 文件名) */
    private static Set<String> readLocalBuildings(String packId) {
        Set<String> result = new TreeSet<>();
        Path dir = PackCreator.getWorkRoot().resolve(packId).resolve("construction");
        if (!Files.exists(dir)) return result;
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".nbt"))
                  .forEach(p -> {
                      String n = p.getFileName().toString();
                      result.add(n.substring(0, n.length() - 4));  // 去 .nbt 后缀
                  });
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.warn("[PACK-STATUS] 读本地建筑失败: {}", e.getMessage());
        }
        return result;
    }

    /** 读 zip 内 construction/ 下的 .nbt 文件名集合 */
    private static Set<String> readBuildingsFromZip(Path zipPath) {
        Set<String> result = new TreeSet<>();
        if (!Files.exists(zipPath)) return result;
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> es = zf.entries();
            while (es.hasMoreElements()) {
                ZipEntry e = es.nextElement();
                if (e.isDirectory()) continue;
                String n = e.getName().toLowerCase();
                // 支持两种内部布局: <id>/construction/xxx.nbt (旧) 或直接 construction/xxx.nbt
                if (!n.endsWith(".nbt")) continue;
                if (!n.contains("construction/")) continue;
                int idx = n.lastIndexOf('/');
                String base = idx >= 0 ? n.substring(idx + 1) : n;
                result.add(base.substring(0, base.length() - 4));  // 去 .nbt
            }
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.warn("[PACK-STATUS] 读 zip 失败 {}: {}", zipPath, e.getMessage());
        }
        return result;
    }
}
