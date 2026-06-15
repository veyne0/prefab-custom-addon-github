package com.prefab.addon.client.gui;

import com.prefab.addon.PrefabCustomAddon;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 系统原生文件选择器。
 *
 * 原理: Minecraft 1.21+ 用 LWJGL3, AWT 无法工作 (Toolkit 未初始化).
 *       转用 PowerShell 调 System.Windows.Forms.OpenFileDialog —
 *       这是 Windows 真正的"打开"对话框, 进程外运行不会阻塞游戏.
 *
 * 用法:
 *   SystemFilePicker.openAsync("选择PNG", "png", result -> {
 *       if (result.isOk()) { File f = result.file; ... }
 *   });
 */
public final class SystemFilePicker {

    /** 选择结果 */
    public static final class Result {
        /** 选中的文件 (仅 isOk() 为 true 时有效) */
        public final File file;
        /** 错误或取消信息 (isOk=false 时), 调试用 */
        public final String message;
        /** true = 用户选好文件, false = 取消/失败 */
        public final boolean ok;
        private Result(File f, boolean o, String m) { this.file = f; this.ok = o; this.message = m; }
        public boolean isOk() { return ok && file != null; }
        public boolean isCancelled() { return !ok && "cancelled".equals(message); }
        public boolean isError() { return !ok && !"cancelled".equals(message); }
        public static Result cancelled() { return new Result(null, false, "cancelled"); }
        public static Result error(String m) { return new Result(null, false, m); }
        public static Result ok(File f) { return new Result(f, true, null); }
    }

    private SystemFilePicker() {}

    /**
     * 异步打开系统原生文件选择器, 选中后通过回调返回 Result。
     * Result 区分 "用户选好文件" / "用户取消" / "错误", 让调用方决定是否回退。
     */
    public static void openAsync(String title, String extension, Consumer<Result> onResult) {
        if (!isWindows()) {
            if (onResult != null) onResult.accept(Result.error("not-windows"));
            return;
        }
        // 异步启动 PowerShell, 避免阻塞游戏主线程
        CompletableFuture.runAsync(() -> {
            Result result;
            try {
                result = openWindows(title, extension);
            } catch (Throwable t) {
                PrefabCustomAddon.LOGGER.error("[SYSPICKER] PowerShell 调用失败", t);
                result = Result.error("exception: " + t.getMessage());
            }
            final Result finalResult = result;
            if (onResult != null) {
                net.minecraft.client.Minecraft.getInstance().execute(() -> onResult.accept(finalResult));
            }
        });
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }

    private static Result openWindows(String title, String ext) throws Exception {
        // 转义 PowerShell 字符串中的单引号 (用 '' 转义)
        String safeTitle = title.replace("'", "''");
        String safeExt = ext.replace("'", "''");

        // 关键: 用文件作为输入输出通道, 避免被隐藏的 PowerShell stdout 缓冲/卡死问题
        // 把脚本写到临时 .ps1 文件, 用 cmd.exe /c start 启动一个**新的可见控制台**
        // 来执行它, 这样 OpenFileDialog 有可见的父窗口
        java.nio.file.Path scriptPath = java.nio.file.Files.createTempFile("prefab_pick_", ".ps1");
        java.nio.file.Path outputPath = java.nio.file.Files.createTempFile("prefab_pick_out_", ".txt");
        try {
            String script =
                "Add-Type -AssemblyName System.Windows.Forms; " +
                "$ErrorActionPreference = 'Stop'; " +
                "$f = New-Object System.Windows.Forms.OpenFileDialog; " +
                "$f.Title = '" + safeTitle + "'; " +
                "$f.Filter = '" + safeExt.toUpperCase() + " 文件 (*." + safeExt + ")|*." + safeExt + "|所有文件 (*.*)|*.*'; " +
                "$f.CheckFileExists = $true; " +
                "$f.Multiselect = $false; " +
                "$f.InitialDirectory = [Environment]::GetFolderPath('MyDocuments'); " +
                "try { " +
                "  $r = $f.ShowDialog(); " +
                "  if ($r -eq [System.Windows.Forms.DialogResult]::OK) { " +
                // 用 utf8NoBOM 避免 Java 读到 BOM 字符
                "    [System.IO.File]::WriteAllText('" + outputPath.toString().replace("'", "''") + "', ('OK:' + $f.FileName), (New-Object System.Text.UTF8Encoding $false)) " +
                "  } else { " +
                "    [System.IO.File]::WriteAllText('" + outputPath.toString().replace("'", "''") + "', 'CANCEL', (New-Object System.Text.UTF8Encoding $false)) " +
                "  } " +
                "} catch { " +
                "  [System.IO.File]::WriteAllText('" + outputPath.toString().replace("'", "''") + "', ('ERROR:' + $_.Exception.Message), (New-Object System.Text.UTF8Encoding $false)) " +
                "}";
            java.nio.file.Files.writeString(scriptPath, script,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);

            // 用 cmd /c start 启动新可见控制台运行 PowerShell
            // 关键: "start" 会创建新进程并允许它显示窗口
            String psScript = scriptPath.toAbsolutePath().toString();
            ProcessBuilder pb = new ProcessBuilder(
                "cmd.exe", "/c", "start", "\"\"", "/WAIT",
                "powershell.exe", "-STA", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", psScript
            );
            // 不要重定向 IO — 让 PowerShell 在自己的可见控制台里运行
            pb.inheritIO();
            Process proc = pb.start();

            // 轮询输出文件, 最多等 5 分钟
            String result = null;
            long deadline = System.currentTimeMillis() + 300_000L;
            while (System.currentTimeMillis() < deadline) {
                if (java.nio.file.Files.exists(outputPath)) {
                    try {
                        String content = java.nio.file.Files.readString(outputPath,
                            java.nio.charset.StandardCharsets.UTF_8).trim();
                        if (!content.isEmpty()) {
                            result = content;
                            break;
                        }
                    } catch (java.nio.file.NoSuchFileException ignore) {}
                }
                if (!proc.isAlive()) {
                    // 进程已退出, 最后再读一次
                    Thread.sleep(200);
                    if (java.nio.file.Files.exists(outputPath)) {
                        result = java.nio.file.Files.readString(outputPath,
                            java.nio.charset.StandardCharsets.UTF_8).trim();
                    }
                    break;
                }
                Thread.sleep(150);
            }
            if (!proc.isAlive()) proc.destroyForcibly();

            if (result == null) {
                PrefabCustomAddon.LOGGER.warn("[SYSPICKER] 超时未收到选择结果");
                return Result.error("timeout");
            }
            if ("CANCEL".equals(result)) {
                PrefabCustomAddon.LOGGER.info("[SYSPICKER] 用户取消");
                return Result.cancelled();
            }
            if (result.startsWith("ERROR:")) {
                PrefabCustomAddon.LOGGER.warn("[SYSPICKER] OpenFileDialog 错误: {}", result);
                return Result.error(result);
            }
            if (result.startsWith("OK:")) {
                String path = result.substring(3);
                File f = new File(path);
                if (!f.isFile()) {
                    PrefabCustomAddon.LOGGER.warn("[SYSPICKER] 选中的路径不是文件: {}", path);
                    return Result.error("not-a-file: " + path);
                }
                PrefabCustomAddon.LOGGER.info("[SYSPICKER] 已选择: {}", path);
                return Result.ok(f);
            }
            PrefabCustomAddon.LOGGER.warn("[SYSPICKER] 未知结果: {}", result);
            return Result.error("unknown-result: " + result);
        } finally {
            // 清理临时文件
            try { java.nio.file.Files.deleteIfExists(scriptPath); } catch (Exception ignore) {}
            try { java.nio.file.Files.deleteIfExists(outputPath); } catch (Exception ignore) {}
        }
    }
}
