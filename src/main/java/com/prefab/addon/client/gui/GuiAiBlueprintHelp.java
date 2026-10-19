package com.prefab.addon.client.gui;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.gui.GuiBase;
import com.prefab.gui.controls.ExtendedButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * "AI 创建蓝图" 帮助子屏: 教玩家怎么让 AI agent 代做蓝图.
 *
 * <p>使用与 {@link GuiExtensionPackEditor} 同一套 GuiBase 界面框架
 * (之前用原生 vanilla Screen 实现时整体渲染发糊, 玩家看不清文字, 故重写).</p>
 *
 * <p>指南文档打包在 jar 内 ({@code /assets/prefab_custom_addon/docs/ai_blueprint_guide.md}),
 * 打开本界面时导出到 {@code <游戏目录>/prefab-document/AI蓝图创建指南.md} (与 mods 同级),
 * "打开文档文件夹" 按钮用系统文件管理器打开 prefab-document 目录, 玩家把里面的
 * 指南文档直接发给 AI 即可.</p>
 *
 * <p>导出策略: 目标文件不存在或内容与 jar 内版本不一致时才写入,
 * 玩家手改过的内容不会被无故覆盖 (mod 更新带了新指南时会覆盖为新版).</p>
 */
public class GuiAiBlueprintHelp extends GuiBase {

    /** jar 内指南资源路径 (ASCII 文件名, 避免部分环境 jar 内中文条目读取问题). */
    private static final String DOC_RESOURCE = "/assets/prefab_custom_addon/docs/ai_blueprint_guide.md";
    /** 导出到游戏目录后的文件名 (与仓库根目录的指南文档同名, 方便玩家识别). */
    private static final String DOC_FILE_NAME = "AI蓝图创建指南.md";

    /** 面板尺寸 (gui 坐标), 与 Editor 同风格的深色主题. */
    private static final int PANEL_W = 380;
    private static final int PANEL_H = 160;

    private static final int PANEL_BG = 0xFF1A1A1A;
    private static final int PANEL_BORDER = 0xFF555555;

    /** 父 Editor 引用 — 返回时还原 tab 状态. */
    private final Screen parent;

    private int panelX, panelY;

    /** 本屏状态消息 (打开文件夹成功/失败等). */
    private String status = null;
    private int statusColor = 0x55FF55;

    public GuiAiBlueprintHelp(Screen parent) {
        super("AI Blueprint Help");
        this.parent = parent;
        this.modifiedInitialXAxis = PANEL_W / 2;
        this.modifiedInitialYAxis = PANEL_H / 2;
    }

    /** 静态入口: Editor 调这个打开帮助屏 (顺带确保指南已导出). */
    public static void open(Screen parent) {
        ensureDocExtracted();
        Minecraft.getInstance().setScreen(new GuiAiBlueprintHelp(parent));
    }

    /** 指南文档导出目录: {@code <游戏目录>/prefab-document} (与 mods 文件夹同级). */
    public static Path docDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("prefab-document");
    }

    /** 指南文档导出后的完整路径. */
    public static Path docFile() {
        return docDir().resolve(DOC_FILE_NAME);
    }

    /**
     * 把 jar 内指南导出到 prefab-document/.
     * 文件已存在且内容一致时跳过; 内容不一致 (mod 更新带了新指南) 时覆盖.
     *
     * @return 导出成功 (或已存在同内容文件) 返回 true
     */
    public static boolean ensureDocExtracted() {
        try {
            byte[] data;
            try (var in = GuiAiBlueprintHelp.class.getResourceAsStream(DOC_RESOURCE)) {
                if (in == null) {
                    PrefabCustomAddon.LOGGER.error("[AI-HELP] jar 内找不到指南资源 {}", DOC_RESOURCE);
                    return false;
                }
                data = in.readAllBytes();
            }
            Path target = docFile();
            Files.createDirectories(target.getParent());
            if (Files.exists(target) && Arrays.equals(data, Files.readAllBytes(target))) {
                return true;
            }
            Files.write(target, data);
            PrefabCustomAddon.LOGGER.info("[AI-HELP] 已导出 AI 蓝图指南: {}", target);
            return true;
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[AI-HELP] 导出 AI 蓝图指南失败", t);
            return false;
        }
    }

    @Override
    protected void Initialize() {
        super.Initialize();
        int[] pos = computePanelPos();
        this.panelX = pos[0];
        this.panelY = pos[1];

        // 底部按钮: 打开文档文件夹 + 返回 (ExtendedButton, 与 Editor 同款控件)
        int btnY = this.panelY + PANEL_H - 24;
        int btnH = 18;
        makeButton(this.panelX + 8, btnY, 110, btnH,
                PrefabCustomAddon.tr("gui.ai_help.btn.open_doc"), this::openDocFolder);
        makeButton(this.panelX + 8 + 110 + 8, btnY, 60, btnH,
                PrefabCustomAddon.tr("gui.ai_help.btn.back"), this::backToParent);
    }

    /** 与 Editor.makeButton 同款: ExtendedButton + lambda 处理点击. */
    private ExtendedButton makeButton(int x, int y, int w, int h, String label, Runnable onClick) {
        ExtendedButton btn = new ExtendedButton(x, y, w, h, Component.literal(label), b -> onClick.run(), label);
        this.addRenderableWidget(btn);
        return btn;
    }

    /** 返回按钮: 回到父 Editor, tab 状态不丢. */
    private void backToParent() {
        if (this.parent != null) {
            Minecraft.getInstance().setScreen(this.parent);
        } else {
            onClose();
        }
    }

    /** 与 Editor 同款面板定位: 居中 + clamp 到 4px 边距. */
    private int[] computePanelPos() {
        int x = (this.width / 2) - this.modifiedInitialXAxis;
        int y = (this.height / 2) - this.modifiedInitialYAxis;
        if (x < 4) x = 4;
        if (y < 4) y = 4;
        if (x + PANEL_W > this.width - 4) x = Math.max(4, this.width - PANEL_W - 4);
        if (y + PANEL_H > this.height - 4) y = Math.max(4, this.height - PANEL_H - 4);
        return new int[]{x, y};
    }

    @Override
    protected void preButtonRender(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, float partialTicks) {
        // 面板底色 + 边框 (与 Editor 深色主题一致)
        int rx = this.panelX, ry = this.panelY;
        guiGraphics.fill(rx, ry, rx + PANEL_W, ry + PANEL_H, PANEL_BG);
        guiGraphics.fill(rx, ry, rx + PANEL_W, ry + 1, PANEL_BORDER);
        guiGraphics.fill(rx, ry + PANEL_H - 1, rx + PANEL_W, ry + PANEL_H, PANEL_BORDER);
        guiGraphics.fill(rx, ry, rx + 1, ry + PANEL_H, PANEL_BORDER);
        guiGraphics.fill(rx + PANEL_W - 1, ry, rx + PANEL_W, ry + PANEL_H, PANEL_BORDER);
    }

    @Override
    protected void postButtonRender(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, float partialTicks) {
        int rx = this.panelX, ry = this.panelY, rw = PANEL_W, rh = PANEL_H;

        // 标题
        guiGraphics.drawString(this.font, PrefabCustomAddon.tr("gui.ai_help.title"), rx + 8, ry + 8, 0xFFFFAA00, true);

        // 步骤说明
        guiGraphics.drawString(this.font, PrefabCustomAddon.tr("gui.ai_help.step1"), rx + 8, ry + 26, 0xFFFFFFFF, true);
        guiGraphics.drawString(this.font, PrefabCustomAddon.tr("gui.ai_help.step2"), rx + 8, ry + 38, 0xFFFFFFFF, true);

        // 文档位置 (自动换行, 最多 3 行)
        guiGraphics.drawString(this.font, PrefabCustomAddon.tr("gui.ai_help.doc_label"), rx + 8, ry + 54, 0xFF55FF55, true);
        List<FormattedCharSequence> lines = this.font.split(
                Component.literal("§7" + docFile()), rw - 16);
        int ly = ry + 64;
        for (int i = 0; i < lines.size() && i < 3; i++) {
            guiGraphics.drawString(this.font, lines.get(i), rx + 8, ly, 0xFFAAAAAA, true);
            ly += 11;
        }

        // 状态消息 (按钮行上方)
        if (status != null) {
            guiGraphics.drawString(this.font, status, rx + 8, ry + rh - 40, statusColor, true);
        }
    }

    @Override
    public void buttonClicked(AbstractButton button) {
        // 本 GUI 的按钮都用 makeButton 包装, lambda 已经处理点击;
        // 这里留空即可 (与 Editor 一致).
    }

    /**
     * 用系统文件管理器打开文件夹 (两个 GUI 共用).
     *
     * <p>部分启动器环境下 AWT Desktop 不可用 ({@code isDesktopSupported()} 返回 false,
     * 玩家实测会走到"系统不支持"分支), 所以 Desktop 之外再做平台命令降级:
     * Windows=explorer.exe / macOS=open / Linux=xdg-open.</p>
     *
     * @return null 表示已发起打开, 否则返回失败原因 (供状态栏展示)
     */
    public static String openFolder(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (Throwable t) {
            return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        }
        // 1) AWT Desktop (标准途径)
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir.toFile());
                return null;
            }
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[FOLDER] Desktop.open 失败, 改用命令行降级: {}", t.toString());
        }
        // 2) 平台命令降级 (explorer.exe 即使成功也可能返回非 0 退出码, 不 waitFor)
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String[] cmd;
            if (os.contains("win")) {
                cmd = new String[]{"explorer.exe", dir.toString()};
            } else if (os.contains("mac") || os.contains("darwin")) {
                cmd = new String[]{"open", dir.toString()};
            } else {
                cmd = new String[]{"xdg-open", dir.toString()};
            }
            new ProcessBuilder(cmd).start();
            return null;
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[FOLDER] 命令行打开文件夹失败: {}", dir, t);
            return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        }
    }

    /** 打开 prefab-document 文件夹 (先确保指南已导出). */
    private void openDocFolder() {
        if (!ensureDocExtracted()) {
            status = PrefabCustomAddon.tr("gui.ai_help.status.extract_fail");
            statusColor = 0xFF5555;
            return;
        }
        Path dir = docDir();
        String err = openFolder(dir);
        if (err == null) {
            status = PrefabCustomAddon.tr("gui.ai_help.status.opened");
            statusColor = 0x55FF55;
        } else {
            status = PrefabCustomAddon.tr("gui.ai_help.status.fail", err);
            statusColor = 0xFF5555;
            PrefabCustomAddon.LOGGER.error("[AI-HELP] 打开文档文件夹失败: {}", err);
        }
    }

    /** Esc: 回到父 Editor (GuiBase 的关闭路径走 Screen.onClose). */
    @Override
    public void onClose() {
        if (parent != null) {
            Minecraft.getInstance().setScreen(parent);
        } else {
            super.onClose();
        }
    }
}
