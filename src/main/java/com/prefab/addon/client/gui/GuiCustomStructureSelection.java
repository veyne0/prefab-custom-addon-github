package com.prefab.addon.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.ConstructionInfo;
import com.prefab.addon.extension.ExtensionPack;
import com.prefab.addon.extension.ExtensionPackManager;
import com.prefab.gui.GuiBase;
import com.prefab.gui.controls.ExtendedButton;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;

/**
 * 自定义建筑选择 GUI（列表） - 第一级界面。
 *
 * 用户右键 Custom Blueprint：
 *   - 未绑定 → 打开本 GUI
 *   - 已绑定 → 跳过本 GUI，直接打开 CustomStructureGui（Prefab 原生 Prefab 风格）
 *
 * 在本 GUI 中：
 *   - 列表里展示所有 Construction
 *   - 点击某个 Construction → 打开 GuiConstructionDetail（详细界面）
 *   - 在详细界面里点 "Select" 才把 Construction 绑定到蓝图
 *   - 绑定完成后，玩家关闭 GUI，右键蓝图（对着方块 useOn）即可打开 CustomStructureGui
 *     进行预览 / 建造（这是用户给的"第 2 张图"——Prefab 原生风格）
 *
 * 视觉风格：复用 Prefab 的 GuiBase 背景、左面板装饰与按钮风格。
 */
public class GuiCustomStructureSelection extends GuiBase {

    private final List<ConstructionInfo> constructions;
    private int scrollOffset = 0;
    private int visibleItems = 6;

    // 列表渲染区域（在面板内左侧）
    private int listX, listY, listWidth, listHeight;

    // 按钮
    private ExtendedButton btnCancel;
    private ExtendedButton btnUp;
    private ExtendedButton btnDown;

    public GuiCustomStructureSelection() {
        super("Custom Structures");
        this.constructions = getAllConstructions();
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new GuiCustomStructureSelection());
    }

    private List<ConstructionInfo> getAllConstructions() {
        List<ConstructionInfo> all = new ArrayList<>();
        for (ExtensionPack pack : ExtensionPackManager.getInstance().getPacks()) {
            all.addAll(pack.getConstructions());
        }
        PrefabCustomAddon.LOGGER.info("Found {} constructions total", all.size());
        return all;
    }

    @Override
    protected void Initialize() {
        super.Initialize();

        // 调整面板尺寸：略高一些，留出底部按钮 + 顶部标题
        this.modifiedInitialXAxis = 200;
        this.modifiedInitialYAxis = 130;
        this.imagePanelWidth = 400;
        this.imagePanelHeight = 260;
        this.shownImageHeight = 150;
        this.shownImageWidth = 268;

        int grayBoxX = (this.width / 2) - this.modifiedInitialXAxis;
        int grayBoxY = (this.height / 2) - this.modifiedInitialYAxis;

        // 列表占据面板的中间区域
        this.listWidth = 340;
        this.listHeight = 180;
        this.listX = grayBoxX + 30;
        this.listY = grayBoxY + 30;

        // 上下滚动按钮（放在列表右侧）
        this.btnUp = this.createAndAddCustomButton(listX + listWidth + 4, listY, 20, 20, "^");
        this.btnDown = this.createAndAddCustomButton(listX + listWidth + 4, listY + listHeight - 20, 20, 20, "v");

        // 取消按钮（Prefab 标准位置：底部中间）
        this.btnCancel = this.createAndAddButton(grayBoxX + 155, grayBoxY + 225, 90, 20, "Cancel");
    }

    @Override
    protected void preButtonRender(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, float partialTicks) {
        // 复用 Prefab 原生背景（default_background.png）
        this.drawControlBackground(guiGraphics, x, y, this.imagePanelWidth, this.imagePanelHeight);
        // 左面板装饰
        this.drawControlLeftPanel(guiGraphics, listX - 10, listY - 5, listWidth + 60, listHeight + 10);
    }

    @Override
    protected void postButtonRender(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, float partialTicks) {
        // 标题
        guiGraphics.drawCenteredString(this.font, "Select a Structure",
                this.getCenteredXAxis(), y + 8, this.textColor);

        // 提示语（让用户知道要双击 / 单击进入详细）
        if (!this.constructions.isEmpty()) {
            guiGraphics.drawCenteredString(this.font, "Click a structure to view details",
                    this.getCenteredXAxis(), y + 22, 0xAAAAAA);
        }

        // 列表
        drawStructureList(guiGraphics, mouseX, mouseY);
    }

    private void drawStructureList(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // 列表背景（深色）
        guiGraphics.fill(listX, listY, listX + listWidth, listY + listHeight, 0xFF1A1A1A);

        if (this.constructions.isEmpty()) {
            guiGraphics.drawCenteredString(this.font, "No constructions", listX + listWidth / 2, listY + 80, 0xFF5555);
            return;
        }

        int itemHeight = this.listHeight / this.visibleItems;
        for (int i = 0; i < this.visibleItems && (i + this.scrollOffset) < this.constructions.size(); i++) {
            int itemY = this.listY + i * itemHeight;
            ConstructionInfo c = this.constructions.get(i + this.scrollOffset);

            int bg;
            // 高亮悬停项（无默认选中！）
            if (mouseX >= this.listX && mouseX <= this.listX + this.listWidth
                    && mouseY >= itemY && mouseY < itemY + itemHeight) {
                bg = 0xFF4A6FA5; // 悬停蓝
            } else {
                bg = 0xFF333333; // 普通
            }
            guiGraphics.fill(this.listX, itemY, this.listX + this.listWidth, itemY + itemHeight - 2, bg);

            // 名称（截断）
            String name = c.getName();
            int maxChars = 48;
            if (name.length() > maxChars) name = name.substring(0, maxChars - 2) + "..";
            guiGraphics.drawString(this.font, name, this.listX + 8, itemY + (itemHeight - 8) / 2, 0xFFFFFF);
        }

        // 滚动条信息
        if (this.constructions.size() > this.visibleItems) {
            String info = (this.scrollOffset + 1) + "-" +
                    Math.min(this.scrollOffset + this.visibleItems, this.constructions.size())
                    + " of " + this.constructions.size();
            guiGraphics.drawString(this.font, info, this.listX, this.listY - 12, this.textColor);
        }
    }

    @Override
    public void buttonClicked(AbstractButton button) {
        if (button == this.btnCancel) {
            this.closeScreen();
            return;
        }
        if (button == this.btnUp) {
            if (this.scrollOffset > 0) this.scrollOffset--;
            return;
        }
        if (button == this.btnDown) {
            if (this.scrollOffset < this.constructions.size() - this.visibleItems) this.scrollOffset++;
            return;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0
                && mouseX >= this.listX && mouseX <= this.listX + this.listWidth
                && mouseY >= this.listY && mouseY <= this.listY + this.listHeight) {
            int itemHeight = this.listHeight / this.visibleItems;
            int idx = (int) ((mouseY - this.listY) / itemHeight);
            if (idx >= 0 && idx < this.visibleItems && (idx + this.scrollOffset) < this.constructions.size()) {
                // 单击：打开详细界面（GuiConstructionDetail）
                ConstructionInfo selected = this.constructions.get(idx + this.scrollOffset);
                GuiConstructionDetail.open(selected);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY > 0 && this.scrollOffset > 0) {
            this.scrollOffset--;
            return true;
        }
        if (scrollY < 0 && this.scrollOffset < this.constructions.size() - this.visibleItems) {
            this.scrollOffset++;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
