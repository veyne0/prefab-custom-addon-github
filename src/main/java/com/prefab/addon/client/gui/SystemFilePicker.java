package com.prefab.addon.client.gui;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.prefab.addon.PrefabCustomAddon;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 系统文件选择器 (LDLib2 in-game UI 实现).
 *
 * <p>替代原 PowerShell + OpenFileDialog 方案: 改为游戏内文件浏览器 UI,
 * 用 LDLib2 组件渲染, 不需要外部进程 (跨平台, 不再依赖 Windows + PowerShell).</p>
 *
 * <p>用法 (与原版完全兼容):</p>
 * <pre>{@code
 *   SystemFilePicker.openAsync("选择PNG", "png", result -> {
 *       if (result.isOk()) { File f = result.file; ... }
 *   });
 * }</pre>
 *
 * <p>UI 结构 (风格指南 §3 布局):</p>
 * <ul>
 *   <li>标题栏 (24px, RECT_DARK): 居中显示 pickerTitle</li>
 *   <li>路径栏 (24px, RECT_DARK): 灰色显示当前目录绝对路径</li>
 *   <li>ScrollerView (456x280, 风格指南 §2):
 *       <ul>
 *         <li>第一项 "⬆ ..  返回上级" (有父目录时显示)</li>
 *         <li>之后是子目录 (📁 前缀)</li>
 *         <li>最后是匹配扩展名的文件 (🖼 前缀)
 *             — 目录优先于文件, 同类按名称升序</li>
 *       </ul>
 *   </li>
 *   <li>按钮栏 (40px, RECT_DARK): 右下角"取消"按钮</li>
 * </ul>
 *
 * <p>关闭语义:</p>
 * <ul>
 *   <li>点文件 → {@link Minecraft#setScreen(Screen)} null + {@link Result#ok(File)}</li>
 *   <li>点取消 / 按 ESC → 匿名 ModularUIScreen.onClose() 检测未交付, 交付
 *       {@link Result#cancelled()}</li>
 * </ul>
 */
public final class SystemFilePicker {

    /** 选择结果 — 字段/方法签名与原版完全一致 (其他文件直接引用). */
    public static final class Result {
        /** 选中的文件 (仅 {@link #isOk()} 为 true 时有效). */
        public final File file;
        /** 错误或取消信息 ({@link #isOk()}=false 时), 调试用. */
        public final String message;
        /** true = 用户选好文件, false = 取消/失败. */
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
     * 异步打开文件选择器, 单扩展名版本 (兼容旧 API).
     */
    public static void openAsync(String title, String extension, Consumer<Result> onResult) {
        openAsync(title, Arrays.asList(extension), onResult);
    }

    /**
     * 异步打开文件选择器, 多扩展名版本.
     * 优先使用系统原生 FileDialog (AWT), 失败回退到 in-game UI.
     *
     * @param title      标题 (同时作为窗口标题)
     * @param extensions 扩展名列表 (不带点, 例 ["nbt", "litematic", "schem"]).
     *                   空 / null 视为不过滤, 显示所有文件
     * @param onResult   回调 (在 Minecraft 主线程触发)
     */
    public static void openAsync(String title, List<String> extensions, Consumer<Result> onResult) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            if (onResult != null) onResult.accept(Result.error("no-minecraft"));
            return;
        }
        // UI 必须在主线程上打开; 无论从哪个线程调用都安全
        mc.execute(() -> {
            List<String> exts = normalizeExtensions(extensions);
            // 优先尝试 PowerShell 启动系统原生对话框 (PCL/HMCL 等启动器默认 headless, AWT 不可用)
            try {
                if (openWindowsPowerShellDialog(title, exts, onResult)) {
                    return; // PowerShell 已接管
                }
            } catch (Throwable t) {
                PrefabCustomAddon.LOGGER.warn("[SYSPICKER] PowerShell 对话框失败, 回退 AWT/in-game", t);
            }
            // 尝试 AWT 原生对话框 (fullscreen 模式, 不带 -Djava.awt.headless=true)
            try {
                if (openNativeDialog(title, exts, onResult)) {
                    return; // AWT 对话框已接管
                }
            } catch (Throwable t) {
                PrefabCustomAddon.LOGGER.warn("[SYSPICKER] AWT 对话框失败, 回退 in-game UI", t);
            }
            // 最后回退到 in-game UI
            try {
                openInGame(mc, title, exts, onResult);
            } catch (Throwable t) {
                PrefabCustomAddon.LOGGER.error("[SYSPICKER] 打开文件选择器失败", t);
                if (onResult != null) onResult.accept(Result.error("exception: " + t.getMessage()));
            }
        });
    }

    /**
     * 用 PowerShell 启动 Windows 原生 OpenFileDialog (System.Windows.Forms).
     * 适用于 PCL/HMCL 等启动器默认 headless 模式的场景.
     * <p>
     * 做法: 写入临时 .ps1 脚本 (使用 [System.Windows.Forms.OpenFileDialog] + Add-Type) ,
     * 通过 powershell.exe 启动, 同步等待退出, 解析 stdout 获取用户选择的文件路径.
     * </p>
     * <p>
     * 仅 Windows 平台生效; Linux/macOS 直接返回 false 走 AWT/in-game 流程.
     * </p>
     *
     * @return true = PowerShell 已成功启动并接管 (无论用户取消还是选中了文件, 都会回调 onResult)
     *         false = 平台不匹配 (非 Windows), 走其他回退路径
     */
    private static boolean openWindowsPowerShellDialog(String title, List<String> extensions, Consumer<Result> onResult) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            return false; // 非 Windows, 跳过
        }
        PrefabCustomAddon.LOGGER.info("[SYSPICKER] 启动 PowerShell 原生文件选择器, title={}, exts={}", title, extensions);

        // 构造 PowerShell 脚本
        // 使用 [System.Windows.Forms.OpenFileDialog] (Windows 自带, 弹出标准资源管理器对话框)
        // Add-Type -AssemblyName System.Windows.Forms 加载 WinForms 程序集
        StringBuilder ps = new StringBuilder();
        ps.append("Add-Type -AssemblyName System.Windows.Forms | Out-Null;");
        ps.append("$d = New-Object System.Windows.Forms.OpenFileDialog;");
        ps.append("$d.Title = '").append(escapePsString(title == null ? "选择文件" : title)).append("';");
        if (extensions != null && !extensions.isEmpty()) {
            ps.append("$d.Filter = '").append(buildPsFilter(extensions)).append("';");
        } else {
            ps.append("$d.Filter = '所有文件 (*.*)|*.*';");
        }
        // 多选 = false (单选, 返回单个文件路径)
        ps.append("$d.Multiselect = $false;");
        // 起始目录: 用户的"文档"文件夹
        ps.append("$d.InitialDirectory = [Environment]::GetFolderPath('MyDocuments');");
        // 显示并等待用户选择
        ps.append("$r = $d.ShowDialog();");
        // 取消时 Write-Output "CANCEL"; 选中时 Write-Output 文件路径
        ps.append("if ($r -eq [System.Windows.Forms.DialogResult]::OK) {");
        ps.append("Write-Output $d.FileName;");
        ps.append("} else {");
        ps.append("Write-Output 'CANCEL';");
        ps.append("}");

        // 写入临时文件
        java.io.File scriptFile = null;
        java.io.BufferedWriter writer = null;
        try {
            scriptFile = java.io.File.createTempFile("prefab_filepicker_", ".ps1");
            scriptFile.deleteOnExit();
            writer = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(scriptFile), java.nio.charset.StandardCharsets.UTF_8));
            // BOM 让 Windows PowerShell 正确识别 UTF-8
            writer.write('\ufeff');
            writer.write(ps.toString());
            writer.flush();
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[SYSPICKER] 写入 PowerShell 脚本失败", t);
            return false;
        } finally {
            if (writer != null) try { writer.close(); } catch (Throwable ignored) {}
        }

        // 启动 PowerShell
        final java.io.File psFile = scriptFile;
        Process process;
        try {
            // -NoProfile: 不加载用户 profile (快)
            // -ExecutionPolicy Bypass: 允许运行临时脚本
            // -File <script>: 执行脚本
            // -STA: 单线程单元 (OpenFileDialog 必须 STA)
            ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-STA",
                "-File", psFile.getAbsolutePath());
            pb.redirectErrorStream(true);
            process = pb.start();
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[SYSPICKER] 启动 powershell.exe 失败 ({}), 跳过", t.getMessage());
            if (psFile != null) psFile.delete();
            return false;
        }

        // 异步读取 stdout, 等进程结束
        final Process proc = process;
        new Thread(() -> {
            StringBuilder out = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (out.length() > 0) out.append("\n");
                    out.append(line);
                }
            } catch (Throwable ignored) {}
            try {
                int code = proc.waitFor();
                PrefabCustomAddon.LOGGER.info("[SYSPICKER] PowerShell 退出 code={}, output={}", code, out);
            } catch (Throwable ignored) {}

            String path = out.toString().trim();
            if (pathFileClean(psFile)) {} // no-op (just for clarity)

            // 切回主线程回调
            final String finalPath = path;
            Minecraft.getInstance().execute(() -> {
                try {
                    if (finalPath.isEmpty() || finalPath.equalsIgnoreCase("CANCEL")) {
                        if (onResult != null) onResult.accept(Result.cancelled());
                        return;
                    }
                    File selected = new File(finalPath);
                    if (!selected.exists()) {
                        if (onResult != null) onResult.accept(Result.error("not-found: " + finalPath));
                        return;
                    }
                    if (onResult != null) onResult.accept(Result.ok(selected));
                } finally {
                    if (psFile != null) psFile.delete();
                }
            });
        }, "PrefabAddon-SysPicker-PS").start();

        return true;
    }

    /** 构造 PowerShell Filter 字符串: 'NBT 文件 (*.nbt)|*.nbt|所有文件 (*.*)|*.*' */
    private static String buildPsFilter(List<String> extensions) {
        StringBuilder sb = new StringBuilder();
        // 主过滤器: 列出所有扩展名
        StringBuilder extList = new StringBuilder();
        StringBuilder filterNames = new StringBuilder();
        for (int i = 0; i < extensions.size(); i++) {
            String ext = extensions.get(i);
            if (i > 0) {
                extList.append(";");
                filterNames.append(";");
            }
            extList.append("*.").append(ext);
            filterNames.append(ext.toUpperCase()).append(" 文件 (*.").append(ext).append(")");
        }
        sb.append(filterNames).append("|").append(extList);
        sb.append("|所有文件 (*.*)|*.*");
        return escapePsString(sb.toString());
    }

    /** 转义 PowerShell 字符串中的单引号 */
    private static String escapePsString(String s) {
        if (s == null) return "''";
        return s.replace("'", "''");
    }

    /** 删除临时文件 (用于 finally) */
    private static boolean pathFileClean(java.io.File f) {
        try { return f != null && f.delete(); } catch (Throwable ignored) { return false; }
    }

    /**
     * 用 AWT 原生文件对话框打开选择器.
     * 在 Windows 上是系统 Explorer 弹窗, macOS 是原生 NSOpenPanel, Linux 通常是 GTK/Qt.
     * FileDialog 必须在 AWT 事件分派线程 (EDT) 中调用, 否则可能死锁.
     * 用 SwingUtilities.invokeAndWait 同步等待结果, 然后切回 MC 主线程回调.
     *
     * @return true = 原生对话框已成功打开并接管; false = 不可用 (回退 in-game)
     */
    private static boolean openNativeDialog(String title, List<String> extensions, Consumer<Result> onResult) throws Exception {
        // AWT 在 headless 环境下不可用
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            PrefabCustomAddon.LOGGER.info("[SYSPICKER] headless 模式, 跳过原生对话框");
            return false;
        }
        // AWT FileDialog 在 macOS 不支持扩展名过滤, 直接用 LOAD 模式让它显示所有文件
        // 我们的 extensions 仅用于选择后过滤, 用户选错文件会被我们自己 reject
        final java.util.concurrent.atomic.AtomicReference<File> pickedFile = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicBoolean confirmed = new java.util.concurrent.atomic.AtomicBoolean(false);

        javax.swing.SwingUtilities.invokeAndWait(() -> {
            java.awt.FileDialog fd = new java.awt.FileDialog(
                (java.awt.Frame) null,  // 用 null 父窗口 → 独立窗口
                title != null ? title : "选择文件",
                java.awt.FileDialog.LOAD);
            // 设置起始目录为用户文档
            File startDir = new File(System.getProperty("user.home", "."));
            try {
                File docs = new File(System.getProperty("user.home"), "Documents");
                if (docs.isDirectory() && docs.canRead()) startDir = docs;
            } catch (Exception ignore) {}
            fd.setDirectory(startDir.getAbsolutePath());
            // macOS 不支持 setFile 过滤器 (用系统菜单做类型过滤), 跳过 filename 过滤
            boolean isMac = System.getProperty("os.name", "").toLowerCase().contains("mac");
            if (!isMac) {
                if (extensions != null && !extensions.isEmpty()) {
                    String extPattern = extensions.stream()
                        .map(e -> "*." + e)
                        .reduce((a, b) -> a + ";" + b)
                        .orElse("*.*");
                    fd.setFile(extPattern);
                }
            }
            fd.setVisible(true);   // 模态阻塞
            String dir = fd.getDirectory();
            String name = fd.getFile();
            if (dir == null || name == null) {
                confirmed.set(false);
            } else {
                File selected = new File(dir, name);
                // 校验扩展名
                if (extensions != null && !extensions.isEmpty()) {
                    String fn = selected.getName().toLowerCase(Locale.ROOT);
                    boolean ok = extensions.stream().anyMatch(e -> fn.endsWith("." + e.toLowerCase(Locale.ROOT)));
                    if (!ok) {
                        PrefabCustomAddon.LOGGER.warn("[SYSPICKER] 原生对话框选中的文件扩展名不匹配: {} (需要: {})",
                            fn, extensions);
                        // 仍然接受, 让用户自己选
                    }
                }
                pickedFile.set(selected);
                confirmed.set(true);
            }
        });

        if (!confirmed.get()) {
            // 用户取消了原生对话框
            Minecraft.getInstance().execute(() -> {
                if (onResult != null) onResult.accept(Result.cancelled());
            });
            return true;
        }
        File selected = pickedFile.get();
        if (selected == null) {
            Minecraft.getInstance().execute(() -> {
                if (onResult != null) onResult.accept(Result.error("no-file-selected"));
            });
            return true;
        }
        Minecraft.getInstance().execute(() -> {
            if (onResult != null) onResult.accept(Result.ok(selected));
        });
        return true;
    }

    // ====================================================================
    // 内部: 状态 + UI 构建
    // ====================================================================

    /** 把扩展名归一化为小写, 过滤 null/空串. */
    private static List<String> normalizeExtensions(List<String> exts) {
        if (exts == null) return List.of();
        return exts.stream()
            .filter(e -> e != null && !e.isEmpty())
            .map(e -> e.toLowerCase(Locale.ROOT))
            .toList();
    }

    private static void openInGame(Minecraft mc, String title, List<String> extensions, Consumer<Result> onResult) {
        // 默认从用户主目录开始; 优先尝试 Documents (Windows 习惯)
        File startDir = new File(System.getProperty("user.home", "."));
        try {
            File docs = new File(System.getProperty("user.home"), "Documents");
            if (docs.isDirectory() && docs.canRead()) startDir = docs;
        } catch (Exception ignore) {}

        FilePickerState state = new FilePickerState(startDir, extensions, onResult);
        String safeTitle = (title != null && !title.isEmpty()) ? title : "选择文件";
        ModularUI ui = buildUI(safeTitle, state);

        // 包装一层 ModularUIScreen 以拦截 onClose(): 按 ESC / 切屏时若仍未交付结果,
        // 主动交付 Result.cancelled(), 避免调用方永远等不到回调
        Screen wrapped = new ModularUIScreen(ui, Component.literal(safeTitle)) {
            @Override
            public void onClose() {
                super.onClose();
                state.deliverCancelledIfPending();
            }
        };
        mc.setScreen(wrapped);
    }

    /** 文件选择器跨控件共享的可变状态. */
    private static final class FilePickerState {
        File currentDir;
        final List<String> extensions;
        final Consumer<Result> callback;
        boolean delivered = false;

        FilePickerState(File start, List<String> exts, Consumer<Result> cb) {
            this.currentDir = start;
            this.extensions = exts;
            this.callback = cb;
        }

        void deliverCancelledIfPending() {
            if (delivered) return;
            delivered = true;
            if (callback != null) callback.accept(Result.cancelled());
        }

        void deliverOk(File f) {
            if (delivered) return;
            delivered = true;
            if (callback != null) callback.accept(Result.ok(f));
        }
    }

    private static ModularUI buildUI(String pickerTitle, FilePickerState state) {
        // === 标题栏 (24px, 不滚动, 居中标题) ===
        UIElement titleBar = new UIElement();
        titleBar.layout(l -> l
            .widthPercent(100).height(24)
            .paddingHorizontal(8)
            .justifyContent(AlignContent.CENTER)
        );
        titleBar.style(s -> s.background(Sprites.RECT_DARK));
        Label titleLabel = new Label();
        titleLabel.setText(Component.literal(pickerTitle));
        titleLabel.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textColor(0xFFFFFF));
        titleBar.addChild(titleLabel);

        // === 路径栏 (24px, 不滚动, 灰色当前路径) ===
        UIElement pathBar = new UIElement();
        pathBar.layout(l -> l
            .widthPercent(100).height(24)
            .paddingHorizontal(8)
            .justifyContent(AlignContent.CENTER)
        );
        pathBar.style(s -> s.background(Sprites.RECT_DARK));
        Label pathLabel = new Label();
        pathLabel.setText(Component.literal("路径: " + state.currentDir.getAbsolutePath())
            .withStyle(ChatFormatting.GRAY));
        pathLabel.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        pathBar.addChild(pathLabel);

        // === 主滚动区: 内部 content + 外层 ScrollerView (风格指南 §2) ===
        UIElement content = new UIElement();
        content.layout(l -> l
            .width(440)
            .paddingAll(4)
            .gapAll(2)
            .flexDirection(FlexDirection.COLUMN)
        );
        content.style(s -> s.background(Sprites.BORDER));
        populateFileList(content, state, pathLabel);

        ScrollerView scrollerView = new ScrollerView();
        scrollerView.layout(l -> l.width(456).height(280));
        scrollerView.addScrollViewChild(content);
        scrollerView.scrollerStyle(style -> style.mode(ScrollerMode.VERTICAL));
        scrollerView.verticalScroller(scroller -> scroller.setScrollBarSize(15f));

        // === 底部按钮栏 (40px, 不滚动, 取消按钮靠右) ===
        UIElement buttonBar = new UIElement();
        buttonBar.layout(l -> l
            .widthPercent(100).height(40)
            .paddingAll(8).gapAll(8)
            .flexDirection(FlexDirection.ROW)
            .justifyContent(AlignContent.FLEX_END)
        );
        buttonBar.style(s -> s.background(Sprites.RECT_DARK));
        Button cancelButton = new Button().setText(Component.literal("✗ 取消"));
        cancelButton.setOnClick(e -> Minecraft.getInstance().setScreen(null));
        cancelButton.layout(l -> l.width(80).height(24));
        buttonBar.addChild(cancelButton);

        // === 根容器 ===
        UIElement root = new UIElement();
        root.layout(l -> l
            .width(456).height(368)
            .flexDirection(FlexDirection.COLUMN)
            .paddingAll(0)
        );
        root.addChild(titleBar);
        root.addChild(pathBar);
        root.addChild(scrollerView);
        root.addChild(buttonBar);

        return ModularUI.of(UI.of(root,
            StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)));
    }

    /**
     * 填充文件列表. 切换目录时重新调用, 重建 content 内的按钮 (保留外层 ScrollerView 滚动容器).
     */
    private static void populateFileList(UIElement content, FilePickerState state, Label pathLabel) {
        content.clearAllChildren();
        pathLabel.setText(Component.literal("路径: " + state.currentDir.getAbsolutePath())
            .withStyle(ChatFormatting.GRAY));

        // "返回上级" 入口 — 仅当存在父目录时显示 (如 C:\ 根目录无父)
        File parent = state.currentDir.getParentFile();
        if (parent != null) {
            Button upButton = new Button().setText(Component.literal("⬆ ..  (返回上级)"));
            upButton.setOnClick(e -> {
                state.currentDir = parent;
                populateFileList(content, state, pathLabel);
            });
            upButton.layout(l -> l.widthPercent(100).height(20));
            content.addChild(upButton);
        }

        File[] files = state.currentDir.listFiles();
        if (files == null) {
            Label err = new Label();
            err.setText(Component.literal("(无法读取此目录)").withStyle(ChatFormatting.RED));
            content.addChild(err);
            return;
        }

        // 排序: 目录在前, 文件在后; 同类按名称升序
        Arrays.sort(files, (a, b) -> {
            boolean aDir = a.isDirectory();
            boolean bDir = b.isDirectory();
            if (aDir != bDir) return aDir ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        boolean anyFile = false;
        for (File f : files) {
            if (f.isHidden()) continue;
            if (f.isDirectory()) {
                Button row = new Button().setText(Component.literal("📁 " + f.getName()));
                row.setOnClick(e -> {
                    state.currentDir = f;
                    populateFileList(content, state, pathLabel);
                });
                row.layout(l -> l.widthPercent(100).height(20));
                content.addChild(row);
            } else {
                if (!matchesExtension(f, state.extensions)) continue;
                anyFile = true;
                Button row = new Button().setText(Component.literal("🖼 " + f.getName()));
                row.setOnClick(e -> {
                    state.deliverOk(f);
                    Minecraft.getInstance().setScreen(null);
                });
                row.layout(l -> l.widthPercent(100).height(20));
                content.addChild(row);
            }
        }

        if (!anyFile) {
            Label empty = new Label();
            empty.setText(Component.literal("(此目录没有匹配的文件)")
                .withStyle(ChatFormatting.GRAY));
            content.addChild(empty);
        }
    }

    /** 文件扩展名匹配 (大小写不敏感). */
    private static boolean matchesExtension(File f, List<String> exts) {
        if (exts == null || exts.isEmpty()) return true;
        String name = f.getName().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = name.substring(dot + 1);
        for (String e : exts) {
            if (ext.equals(e)) return true;
        }
        return false;
    }
}
