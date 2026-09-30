package com.prefab.addon.work;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.download.PackDownloadManager;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 在系统文件管理器中打开 .minecraft/prefab-extension/ 目录。
 *
 * - Windows: explorer.exe
 * - macOS:   open
 * - Linux:   xdg-open
 *
 * 跨平台兜底: java.awt.Desktop.browseFileDirectory() (若可用)
 */
public class FolderOpener {

    /**
     * 打开拓展包根目录 (.minecraft/prefab-extension/).
     * 如果目录不存在则尝试创建, 失败时弹聊天消息.
     */
    public static boolean openExtensionFolder() {
        Path dir = PackDownloadManager.getExtensionRoot();
        PrefabCustomAddon.LOGGER.info("[FOLDER] open extension folder: {}", dir);
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
                PrefabCustomAddon.LOGGER.info("[FOLDER] created dir: {}", dir);
            } catch (IOException e) {
                PrefabCustomAddon.LOGGER.warn("[FOLDER] failed to create dir {}: {}", dir, e.getMessage());
                return false;
            }
        }
        return openInOS(dir);
    }

    /**
     * 在系统文件管理器中打开指定目录.
     */
    public static boolean openInOS(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            PrefabCustomAddon.LOGGER.warn("[FOLDER] dir not found: {}", dir);
            return false;
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                // Windows: explorer.exe 会自动选中文档
                Process p = new ProcessBuilder("explorer.exe", dir.toAbsolutePath().toString()).start();
                PrefabCustomAddon.LOGGER.info("[FOLDER] spawned explorer.exe pid={}", p.pid());
                return true;
            } else if (os.contains("mac")) {
                Process p = new ProcessBuilder("open", dir.toAbsolutePath().toString()).start();
                PrefabCustomAddon.LOGGER.info("[FOLDER] spawned open pid={}", p.pid());
                return true;
            } else {
                // Linux / 其他
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(dir.toFile());
                    return true;
                }
                Process p = new ProcessBuilder("xdg-open", dir.toAbsolutePath().toString()).start();
                PrefabCustomAddon.LOGGER.info("[FOLDER] spawned xdg-open pid={}", p.pid());
                return true;
            }
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.warn("[FOLDER] failed to open {}: {}", dir, e.getMessage());
            return false;
        }
    }
}
