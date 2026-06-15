package com.prefab.addon.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.work.PackCreator;
import com.prefab.gui.GuiBase;
import com.prefab.gui.controls.ExtendedButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * X 键打开的拓展包制作界面（本地工作区 prefab-work/）。
 *
 * 布局:
 *  - 左面板: 拓展包列表
 *  - 右面板: 选中包的详情 + 封面图 + 建筑列表
 *  - 底部按钮: 创建拓展包 / 创建建筑 / 删除 / 打开工作目录 / 刷新 / 关闭
 *
 * 工作目录: .minecraft/prefab-work/&lt;packId&gt;/
 *   information/&lt;packId&gt;.txt + cover.png
 *   construction/&lt;buildingId&gt;.{nbt,png,txt}
 */
public class GuiExtensionPackCreator extends GuiBase {

    private static final int PACK_ITEM_H = 30;
    private static final int BUILDING_ITEM_H = 22;
    private static final int SCROLLBAR_W = 4;        // 滚动条宽度
    private static final int SCROLLBAR_PAD = 1;      // 滚动条与列表的内边距

    // 滚动条拖动状态: -1=无, 0=拓展包列表, 1=建筑列表
    private int scrollDragging = -1;
    private double dragStartY = 0;
    private int dragStartOffset = 0;

    // 左面板
    private int listX, listY, listWidth, listHeight;
    private int listContentY;
    private int visibleRows;
    private int detailX, detailY, detailWidth, detailHeight;
    /** 信息字段区 (左) */
    private int infoX, infoY, infoWidth, infoHeight;
    /** 右侧封面 + 建筑列表区 */
    private int rightX, rightY, rightWidth, rightHeight;

    // 数据
    private List<PackCreator.PackWorkInfo> packs = new ArrayList<>();
    private List<PackCreator.BuildingWorkInfo> buildings = new ArrayList<>();
    private int selectedIndex = -1;
    private int selectedBuildingIndex = -1;
    private int scrollOffset = 0;
    private int buildingScrollOffset = 0;
    private int visibleBuildings = 4;

    // 状态信息
    private String statusMessage = null;
    private int statusColor = 0x55FF55;
    private int statusTick = 0;

    // 按钮
    private ExtendedButton btnCreatePack;
    private ExtendedButton btnCreateBuilding;
    private ExtendedButton btnDeleteBuilding;
    private ExtendedButton btnDeletePack;
    private ExtendedButton btnEditCover;
    private ExtendedButton btnOpenFolder;
    private ExtendedButton btnRefresh;
    private ExtendedButton btnClose;

    // 封面图缓存: packId -> ResourceLocation
    private final Map<String, ResourceLocation> coverTextures = new HashMap<>();
    private String loadedCoverFor = null;

    public GuiExtensionPackCreator() {
        super("Extension Pack Creator");
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new GuiExtensionPackCreator());
    }

    @Override
    protected void Initialize() {
        super.Initialize();
        // 根据 GUI 缩放动态计算面板尺寸 (避免 2x/3x 缩放时溢出屏幕)
        int guiScale = (int) Minecraft.getInstance().getWindow().getGuiScale();
        // 基准: 1x 缩放下面板 480x320, 2x 缩放缩到 400x260, 3x 缩放缩到 320x220
        if (guiScale == 1) {
            this.imagePanelWidth = 480;
            this.imagePanelHeight = 320;
        } else if (guiScale == 2) {
            this.imagePanelWidth = 400;
            this.imagePanelHeight = 270;
        } else {
            this.imagePanelWidth = 320;
            this.imagePanelHeight = 220;
        }
        this.modifiedInitialXAxis = this.imagePanelWidth / 2;
        this.modifiedInitialYAxis = this.imagePanelHeight / 2;
        this.shownImageHeight = 1;
        this.shownImageWidth = 1;

        int grayBoxX = (this.width / 2) - this.modifiedInitialXAxis;
        int grayBoxY = (this.height / 2) - this.modifiedInitialYAxis;

        // 缩放因子 (用于字号/间距/按钮尺寸)
        float scale = guiScale == 1 ? 1.0f : (guiScale == 2 ? 0.85f : 0.7f);

        // 左面板
        this.listWidth = Math.round(130 * scale);
        this.listHeight = Math.round(this.imagePanelHeight - 60 * scale);
        this.listX = grayBoxX + Math.round(8 * scale);
        this.listY = grayBoxY + Math.round(26 * scale);
        this.listContentY = this.listY + Math.round(18 * scale);
        this.visibleRows = Math.max(3, this.listHeight / PACK_ITEM_H);

        // 总 detail 区 (左+右两列)
        this.detailX = grayBoxX + this.listWidth + Math.round(18 * scale);
        this.detailY = grayBoxY + Math.round(26 * scale);
        this.detailWidth = grayBoxX + this.imagePanelWidth - this.detailX - Math.round(8 * scale);
        this.detailHeight = this.listHeight;

        // 右侧栏: 封面 + 建筑列表
        // 1x: 110, 2x: 100, 3x: 88, 至少能容纳 48px 封面图 + 滚动条
        if (guiScale == 1)      this.rightWidth = 110;
        else if (guiScale == 2) this.rightWidth = 100;
        else                    this.rightWidth = 88;
        this.rightX = this.detailX + this.detailWidth - this.rightWidth;
        this.rightY = this.detailY;
        this.rightHeight = this.detailHeight;

        // 信息字段区 (左侧)
        this.infoX = this.detailX;
        this.infoY = this.detailY;
        this.infoWidth = this.rightX - this.detailX - Math.round(6 * scale);
        this.infoHeight = this.detailHeight;

        // 封面图下方的"更换封面"按钮 (在右栏, 封面图正下方)
        // 封面图在 rightY+6..rightY+54, 按钮放在 rightY+58
        this.btnEditCover = this.createAndAddButton(
            this.rightX + (this.rightWidth - Math.round(76 * scale)) / 2,
            this.rightY + 58,
            Math.round(76 * scale), Math.round(16 * scale), "更换封面");

        // 底部按钮
        int btnY = grayBoxY + this.imagePanelHeight - Math.round(30 * scale);
        int btnH = Math.round(20 * scale);
        int btnW1 = Math.round(70 * scale);
        int btnW2 = Math.round(50 * scale);
        int btnW3 = Math.round(64 * scale);
        int sp = Math.round(4 * scale);
        int bx = grayBoxX + Math.round(6 * scale);
        this.btnCreatePack = this.createAndAddButton(bx, btnY, btnW1, btnH, "创建拓展包"); bx += btnW1 + sp;
        this.btnCreateBuilding = this.createAndAddButton(bx, btnY, btnW1, btnH, "创建建筑"); bx += btnW1 + sp;
        this.btnDeleteBuilding = this.createAndAddButton(bx, btnY, btnW2, btnH, "删建筑"); bx += btnW2 + sp;
        this.btnDeletePack = this.createAndAddButton(bx, btnY, btnW1, btnH, "删拓展包"); bx += btnW1 + sp;
        this.btnOpenFolder = this.createAndAddButton(bx, btnY, btnW1, btnH, "打开目录"); bx += btnW1 + sp;
        this.btnRefresh = this.createAndAddButton(bx, btnY, btnW2, btnH, "刷新"); bx += btnW2 + sp;
        this.btnClose = this.createAndAddButton(bx, btnY, btnW3, btnH, "关闭");
        this.btnCreateBuilding.active = false;
        this.btnDeleteBuilding.active = false;
        this.btnDeletePack.active = false;
        this.btnEditCover.active = false;

        refreshPacks();
    }

    @Override
    public void onClose() {
        // 清理封面图纹理
        for (ResourceLocation loc : coverTextures.values()) {
            Minecraft.getInstance().getTextureManager().release(loc);
        }
        coverTextures.clear();
        super.onClose();
    }

    private void refreshPacks() {
        try {
            this.packs = PackCreator.getInstance().scanPacks();
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[CREATOR] scan packs failed", t);
            this.packs = new ArrayList<>();
        }
        if (selectedIndex >= packs.size()) {
            selectedIndex = -1;
            buildings = new ArrayList<>();
        }
        if (selectedIndex >= 0) {
            loadBuildingsForSelected();
        }
        updateButtonStates();
    }

    private void loadBuildingsForSelected() {
        if (selectedIndex < 0 || selectedIndex >= packs.size()) {
            buildings = new ArrayList<>();
            return;
        }
        PackCreator.PackWorkInfo p = packs.get(selectedIndex);
        try {
            buildings = PackCreator.getInstance().readBuildings(p.id);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[CREATOR] read buildings failed", t);
            buildings = new ArrayList<>();
        }
        if (selectedBuildingIndex >= buildings.size()) selectedBuildingIndex = -1;
        // 加载封面图
        loadCoverFor(p);
    }

    /** 加载选中拓展包的封面图, 缓存 */
    private void loadCoverFor(PackCreator.PackWorkInfo p) {
        if (loadedCoverFor != null && loadedCoverFor.equals(p.id)) return;
        // 释放旧纹理
        if (loadedCoverFor != null) {
            ResourceLocation old = coverTextures.remove(loadedCoverFor);
            if (old != null) Minecraft.getInstance().getTextureManager().release(old);
        }
        loadedCoverFor = p.id;
        if (p.coverImage == null || !Files.exists(p.coverImage)) return;
        try {
            // NativeImage 自带从 InputStream 读 PNG 的方法
            com.mojang.blaze3d.platform.NativeImage ni;
            try (var in = Files.newInputStream(p.coverImage)) {
                ni = com.mojang.blaze3d.platform.NativeImage.read(in);
            }
            if (ni == null) return;
            // 注册到 TextureManager
            DynamicTexture tex = new DynamicTexture(ni);
            ResourceLocation loc = Minecraft.getInstance().getTextureManager()
                .register("prefab_addon/cover_" + p.id, tex);
            coverTextures.put(p.id, loc);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[CREATOR] load cover failed for " + p.id, t);
        }
    }

    private void updateButtonStates() {
        this.btnCreateBuilding.active = (selectedIndex >= 0);
        this.btnDeleteBuilding.active = (selectedBuildingIndex >= 0 && selectedBuildingIndex < buildings.size());
        this.btnDeletePack.active = (selectedIndex >= 0);
        this.btnEditCover.active = (selectedIndex >= 0);
    }

    private void setStatus(String msg, int color) {
        this.statusMessage = msg;
        this.statusColor = color;
        this.statusTick = 80;
    }

    @Override
    public void tick() {
        super.tick();
        if (statusTick > 0) statusTick--;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // 拓展包列表滚动条点击
            if (handlePackScrollbarClick(mouseX, mouseY)) return true;
            // 建筑列表滚动条点击
            if (handleBuildingScrollbarClick(mouseX, mouseY)) return true;

            // 左面板 - 拓展包点击
            for (int i = 0; i < Math.min(visibleRows, packs.size()); i++) {
                int idx = i + scrollOffset;
                if (idx >= packs.size()) break;
                int iy = listContentY + i * PACK_ITEM_H;
                if (mouseX >= listX && mouseX <= listX + listWidth
                    && mouseY >= iy && mouseY < iy + PACK_ITEM_H) {
                    if (selectedIndex != idx) {
                        selectedIndex = idx;
                        selectedBuildingIndex = -1;
                        buildingScrollOffset = 0;
                        loadBuildingsForSelected();
                        updateButtonStates();
                    }
                    return true;
                }
            }
            // 右面板 - 建筑点击 (单击直接打开编辑)
            if (selectedIndex >= 0) {
                int bListX = getBuildingListX();
                int bListY = getBuildingListY();
                int bListW = getBuildingListW();
                int visB = getVisibleBuildings();
                for (int i = 0; i < Math.min(visB, buildings.size()); i++) {
                    int idx = i + buildingScrollOffset;
                    if (idx >= buildings.size()) break;
                    int iy = bListY + i * BUILDING_ITEM_H;
                    if (mouseX >= bListX && mouseX <= bListX + bListW
                        && mouseY >= iy && mouseY < iy + BUILDING_ITEM_H) {
                        // 单击建筑: 选中 + 立即打开编辑界面
                        selectedBuildingIndex = idx;
                        updateButtonStates();
                        openEditBuilding();
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 拓展包列表滚动条点击处理 (返回 true 表示事件已处理) */
    private boolean handlePackScrollbarClick(double mouseX, double mouseY) {
        if (packs.size() <= visibleRows) return false;
        int sbX = this.listX + this.listWidth - SCROLLBAR_W - SCROLLBAR_PAD;
        int sbY = this.listY + 18;
        int sbH = this.listHeight - 18;
        if (mouseX < sbX || mouseX > sbX + SCROLLBAR_W) return false;
        if (mouseY < sbY || mouseY > sbY + sbH) return false;
        int thumbH = Math.max(12, sbH * visibleRows / packs.size());
        int max = packs.size() - visibleRows;
        int thumbY = sbY + (scrollOffset * (sbH - thumbH) / Math.max(1, max));
        if (mouseY < thumbY) {
            // 点击在拇指上方
            scrollOffset = Math.max(0, scrollOffset - visibleRows);
        } else if (mouseY > thumbY + thumbH) {
            // 点击在拇指下方
            scrollOffset = Math.min(max, scrollOffset + visibleRows);
        } else {
            // 点击在拇指上: 开始拖动
            scrollDragging = 0;
            dragStartY = mouseY;
            dragStartOffset = scrollOffset;
        }
        return true;
    }

    /** 计算建筑列表的当前位置和高度 (与 drawPackDetails 中保持一致) */
    private int getBuildingListY() {
        // 封面图在 rightY+6..rightY+54, 按钮在 rightY+58..rightY+74, 列表起点 = rightY + 86
        return this.rightY + 86;
    }
    private int getBuildingListH() {
        int bListY = getBuildingListY();
        int bottomBtnTop = (this.height / 2) - this.modifiedInitialYAxis + this.imagePanelHeight - 30;
        return Math.max(40, Math.min(bottomBtnTop - bListY - 6, this.rightY + this.rightHeight - bListY - 6));
    }
    private int getBuildingListW() { return this.rightWidth - 8; }
    private int getBuildingListX() { return this.rightX + 4; }
    private int getVisibleBuildings() { return Math.max(1, getBuildingListH() / BUILDING_ITEM_H); }

    /** 建筑列表滚动条点击处理 (返回 true 表示事件已处理) */
    private boolean handleBuildingScrollbarClick(double mouseX, double mouseY) {
        if (buildings.size() <= visibleBuildings) return false;
        int bListX = getBuildingListX();
        int bListY = getBuildingListY();
        int bListW = getBuildingListW();
        int bListH = getBuildingListH();
        int sbX = bListX + bListW - SCROLLBAR_W - SCROLLBAR_PAD;
        int sbY = bListY;
        if (mouseX < sbX || mouseX > sbX + SCROLLBAR_W) return false;
        if (mouseY < sbY || mouseY > sbY + bListH) return false;
        int thumbH = Math.max(12, bListH * visibleBuildings / buildings.size());
        int max = buildings.size() - visibleBuildings;
        int thumbY = bListY + (buildingScrollOffset * (bListH - thumbH) / Math.max(1, max));
        if (mouseY < thumbY) {
            buildingScrollOffset = Math.max(0, buildingScrollOffset - visibleBuildings);
        } else if (mouseY > thumbY + thumbH) {
            buildingScrollOffset = Math.min(max, buildingScrollOffset + visibleBuildings);
        } else {
            scrollDragging = 1;
            dragStartY = mouseY;
            dragStartOffset = buildingScrollOffset;
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && scrollDragging >= 0) {
            scrollDragging = -1;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (scrollDragging == 0) {
            // 拓展包列表滚动
            int sbY = this.listY + 18;
            int sbH = this.listHeight - 18;
            int thumbH = Math.max(12, sbH * visibleRows / Math.max(1, packs.size()));
            int max = Math.max(0, packs.size() - visibleRows);
            int rangeH = sbH - thumbH;
            if (rangeH > 0) {
                double deltaY = mouseY - dragStartY;
                int delta = (int)(deltaY * max / rangeH);
                scrollOffset = Math.max(0, Math.min(max, dragStartOffset + delta));
            }
            return true;
        } else if (scrollDragging == 1) {
            // 建筑列表滚动
            int bListY = getBuildingListY();
            int bListH = getBuildingListH();
            int visB = getVisibleBuildings();
            int thumbH = Math.max(12, bListH * visB / Math.max(1, buildings.size()));
            int max = Math.max(0, buildings.size() - visB);
            int rangeH = bListH - thumbH;
            if (rangeH > 0) {
                double deltaY = mouseY - dragStartY;
                int delta = (int)(deltaY * max / rangeH);
                buildingScrollOffset = Math.max(0, Math.min(max, dragStartOffset + delta));
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // 拓展包列表
        if (mouseX >= listX && mouseX <= listX + listWidth
            && mouseY >= listY && mouseY <= listY + listHeight) {
            int max = Math.max(0, packs.size() - visibleRows);
            scrollOffset = Math.max(0, Math.min(max, scrollOffset - (int) scrollY));
            return true;
        }
        // 建筑列表 (Y 修正: 与 drawPackDetails 一致, 都用 detailY + 140)
        int bListX = getBuildingListX();
        int bListY = getBuildingListY();
        int bListW = getBuildingListW();
        int bListH = getBuildingListH();
        int visB = getVisibleBuildings();
        if (mouseX >= bListX && mouseX <= bListX + bListW
            && mouseY >= bListY && mouseY <= bListY + bListH) {
            int max = Math.max(0, buildings.size() - visB);
            buildingScrollOffset = Math.max(0, Math.min(max, buildingScrollOffset - (int) scrollY));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    protected void preButtonRender(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, float partialTicks) {
        this.drawControlBackground(guiGraphics, x, y, this.imagePanelWidth, this.imagePanelHeight);
        this.drawControlLeftPanel(guiGraphics, this.listX - 6, this.listY - 6, this.listWidth + 12, this.listHeight + 12);
        this.drawControlLeftPanel(guiGraphics, this.detailX - 6, this.detailY - 6, this.detailWidth + 12, this.detailHeight + 12);
    }

    @Override
    protected void postButtonRender(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, float partialTicks) {
        // 标题
        guiGraphics.drawCenteredString(this.font, "拓展包制作 - 本地工作区",
            this.getCenteredXAxis(), y + 4, this.textColor);
        Path workRoot = PackCreator.getWorkRoot();
        guiGraphics.drawCenteredString(this.font, "工作目录: " + workRoot.toString(),
            this.getCenteredXAxis(), y + 16, 0xAAAAAA);

        // 左面板
        drawPackList(guiGraphics, mouseX, mouseY);

        // 右面板
        if (selectedIndex >= 0 && selectedIndex < packs.size()) {
            drawPackDetails(guiGraphics, mouseX, mouseY);
        } else {
            guiGraphics.drawCenteredString(this.font, "← 选择左侧的拓展包",
                this.detailX + this.detailWidth / 2, this.detailY + this.detailHeight / 2 - 6, 0xAAAAAA);
            guiGraphics.drawCenteredString(this.font, "或点击「创建拓展包」开始",
                this.detailX + this.detailWidth / 2, this.detailY + this.detailHeight / 2 + 8, 0x888888);
        }

        // 状态栏 (位置自适应 panel 大小, 防止高 GUI 缩放下被裁掉)
        if (statusMessage != null && statusTick > 0) {
            int sy = y + this.imagePanelHeight - 12;
            guiGraphics.drawString(this.font, statusMessage, x + 8, sy, statusColor);
        }
    }

    private void drawPackList(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(this.font, "§l我的拓展包",
            this.listX + 8, this.listY + 4, this.textColor);
        guiGraphics.fill(this.listX, this.listY + 16, this.listX + this.listWidth, this.listY + 17, 0xFF555555);
        guiGraphics.fill(this.listX, this.listY + 18, this.listX + this.listWidth,
            this.listY + this.listHeight, 0xFF1A1A1A);

        if (packs.isEmpty()) {
            guiGraphics.drawCenteredString(this.font, "还没有拓展包",
                this.listX + this.listWidth / 2, this.listY + 80, 0xAAAAAA);
            guiGraphics.drawCenteredString(this.font, "点下方「创建拓展包」",
                this.listX + this.listWidth / 2, this.listY + 95, 0x888888);
            return;
        }

        for (int i = 0; i < Math.min(visibleRows, packs.size()); i++) {
            int idx = i + scrollOffset;
            if (idx >= packs.size()) break;
            int iy = listContentY + i * PACK_ITEM_H;
            PackCreator.PackWorkInfo p = packs.get(idx);
            int bg;
            if (idx == selectedIndex) {
                bg = 0xFF4A6FA5;
            } else if (mouseX >= listX && mouseX <= listX + listWidth
                && mouseY >= iy && mouseY < iy + PACK_ITEM_H) {
                bg = 0xFF3A3A3A;
            } else {
                bg = 0xFF2A2A2A;
            }
            guiGraphics.fill(this.listX, iy, this.listX + this.listWidth, iy + PACK_ITEM_H - 2, bg);
            String name = p.name;
            if (name == null || name.isEmpty()) name = p.id;
            if (name.length() > 12) name = name.substring(0, 10) + "..";
            guiGraphics.drawString(this.font, name, this.listX + 8, iy + 4, 0xFFFFFF);
            guiGraphics.drawString(this.font, p.buildingCount + " 建筑", this.listX + 8, iy + 16, 0xAAAAAA);
            if (idx == selectedIndex) {
                guiGraphics.fill(this.listX, iy, this.listX + 3, iy + PACK_ITEM_H - 2, 0xFF55AAFF);
            }
        }

        // 滚动条 (在列表右侧)
        drawVerticalScrollbar(guiGraphics,
            this.listX + this.listWidth - SCROLLBAR_W - SCROLLBAR_PAD,
            this.listY + 18,
            this.listHeight - 18,
            packs.size(), visibleRows, scrollOffset,
            scrollDragging == 0, mouseX, mouseY);
    }

    private void drawPackDetails(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        PackCreator.PackWorkInfo p = packs.get(selectedIndex);

        // ==================== 左: 信息字段区 ====================
        // 标题
        String title = (p.name == null || p.name.isEmpty()) ? p.id : p.name;
        if (title.length() > 20) title = title.substring(0, 18) + "..";
        guiGraphics.drawString(this.font, "§l" + title, this.infoX + 4, this.infoY + 4, 0x55AAFF);

        // 字段 (在 info 区)
        int tx = this.infoX + 6;
        int ty = this.infoY + 22;
        int lineH = 10;
        int labelW = 42;
        int valueW = this.infoWidth - 12;  // 减去左右 padding

        String[][] rows = {
            {"标识符:", p.id == null ? "" : p.id},
            {"作者:", p.author == null ? "" : p.author},
            {"版本:", p.version == null ? "" : p.version},
            {"依赖:", p.dependencies == null ? "" : p.dependencies},
            {"链接:", p.link == null ? "" : p.link},
            {"建筑:", String.valueOf(p.buildingCount)},
        };
        for (String[] row : rows) {
            guiGraphics.drawString(this.font, row[0], tx, ty, 0xAAAAAA);
            String val = row[1];
            if (val == null || val.isEmpty()) val = "-";
            val = truncateForWidth(val, this.font, valueW - labelW);
            guiGraphics.drawString(this.font, val, tx + labelW, ty, 0xFFFFFF);
            ty += lineH;
        }

        // 描述 (限宽, 多行)
        ty += 2;
        guiGraphics.drawString(this.font, "描述:", tx, ty, 0xAAAAAA);
        ty += lineH;
        String desc = p.description == null ? "" : p.description;
        if (desc.isEmpty()) desc = "(无)";
        List<FormattedCharSequence> lines = font.split(FormattedText.of(desc), valueW);
        int descEndY = this.infoY + this.infoHeight - 12;
        for (FormattedCharSequence l : lines) {
            if (ty > descEndY) break;
            guiGraphics.drawString(this.font, l, tx, ty, 0xFFDDCC55);
            ty += 10;
        }

        // ==================== 右: 封面 + 建筑列表 ====================
        // 封面图: 顶部居中 48x48
        ResourceLocation cover = coverTextures.get(p.id);
        int cs = 48;
        int cx = this.rightX + (this.rightWidth - cs) / 2;
        int cy = this.rightY + 6;
        if (cover != null) {
            guiGraphics.fill(cx - 2, cy - 2, cx + cs + 2, cy + cs + 2, 0xFF555555);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            try {
                guiGraphics.blit(cover, cx, cy, 0, 0, cs, cs, cs, cs);
            } catch (Throwable t) {
                // 忽略渲染错误
            }
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.disableBlend();
        } else {
            guiGraphics.fill(cx - 2, cy - 2, cx + cs + 2, cy + cs + 2, 0xFF555555);
            guiGraphics.fill(cx, cy, cx + cs, cy + cs, 0xFF222222);
            guiGraphics.drawCenteredString(this.font, "(无)", cx + cs / 2, cy + cs / 2 - 3, 0x888888);
        }

        // 建筑列表: 封面图 + 按钮下方
        int bListX = this.rightX + 4;
        int bListY = cy + cs + 32;  // 封面图 + 按钮 + 间距
        int bListW = this.rightWidth - 8;
        // 高度 = 面板剩余空间, 保证不超出
        int bottomBtnTop = (this.height / 2) - this.modifiedInitialYAxis + this.imagePanelHeight - 30;
        int bListH = Math.max(40, Math.min(bottomBtnTop - bListY - 6, this.rightY + this.rightHeight - bListY - 6));
        int visB = Math.max(1, bListH / BUILDING_ITEM_H);

        // 标题
        guiGraphics.drawString(this.font, "§l建筑:",
            bListX, bListY - 12, this.textColor);
        // 背景
        guiGraphics.fill(bListX, bListY, bListX + bListW, bListY + bListH, 0xFF1A1A1A);

        if (buildings.isEmpty()) {
            guiGraphics.drawCenteredString(this.font, "(无)",
                bListX + bListW / 2, bListY + 8, 0x888888);
        } else {
            for (int i = 0; i < Math.min(visB, buildings.size()); i++) {
                int idx = i + buildingScrollOffset;
                if (idx >= buildings.size()) break;
                int iy = bListY + i * BUILDING_ITEM_H;
                PackCreator.BuildingWorkInfo b = buildings.get(idx);
                int bg;
                if (idx == selectedBuildingIndex) {
                    bg = 0xFF4A6FA5;
                } else if (mouseX >= bListX && mouseX <= bListX + bListW
                    && mouseY >= iy && mouseY < iy + BUILDING_ITEM_H) {
                    bg = 0xFF3A3A3A;
                } else {
                    bg = 0xFF2A2A2A;
                }
                guiGraphics.fill(bListX, iy, bListX + bListW, iy + BUILDING_ITEM_H - 1, bg);
                String bname = (b.name == null || b.name.isEmpty()) ? b.id : b.name;
                if (bname.length() > 8) bname = bname.substring(0, 7) + "..";
                guiGraphics.drawString(this.font, bname, bListX + 4, iy + 3, 0xFFFFFF);
                String size = b.size == null ? "" : b.size;
                guiGraphics.drawString(this.font, size, bListX + 4, iy + 13, 0x55FF55);
                if (idx == selectedBuildingIndex) {
                    guiGraphics.fill(bListX, iy, bListX + 3, iy + BUILDING_ITEM_H - 1, 0xFF55AAFF);
                }
            }
        }

        // 建筑列表滚动条 (右侧)
        drawVerticalScrollbar(guiGraphics,
            bListX + bListW - SCROLLBAR_W - SCROLLBAR_PAD,
            bListY, bListH,
            buildings.size(), visB, buildingScrollOffset,
            scrollDragging == 1, mouseX, mouseY);
    }

    /**
     * 绘制纵向滚动条 (轨道 + 拇指)。仅在 total > visible 时显示。
     *
     * @param x         轨道左 X
     * @param y         轨道顶 Y
     * @param h         轨道高度
     * @param total     总条目数
     * @param visible   可见条目数
     * @param offset    当前滚动偏移
     * @param dragging  当前是否正在拖动该滚动条
     * @param mouseX    当前鼠标 X
     * @param mouseY    当前鼠标 Y
     */
    private void drawVerticalScrollbar(GuiGraphics guiGraphics,
                                       int x, int y, int h,
                                       int total, int visible, int offset,
                                       boolean dragging, int mouseX, int mouseY) {
        if (total <= visible) return;  // 不需要滚动条
        // 轨道背景
        guiGraphics.fill(x, y, x + SCROLLBAR_W, y + h, 0xFF0E0E0E);
        // 拇指
        int thumbH = Math.max(12, h * visible / total);
        int max = Math.max(1, total - visible);
        int thumbY = y + (offset * (h - thumbH) / max);
        boolean hover = mouseX >= x - 1 && mouseX <= x + SCROLLBAR_W + 1
            && mouseY >= thumbY && mouseY <= thumbY + thumbH;
        int thumbColor;
        if (dragging) thumbColor = 0xFFCCCCCC;
        else if (hover) thumbColor = 0xFFAAAAAA;
        else thumbColor = 0xFF666666;
        guiGraphics.fill(x, thumbY, x + SCROLLBAR_W, thumbY + thumbH, thumbColor);
        // 拇指边框
        guiGraphics.fill(x, thumbY, x + 1, thumbY + thumbH, 0xFF888888);
        guiGraphics.fill(x + SCROLLBAR_W - 1, thumbY, x + SCROLLBAR_W, thumbY + thumbH, 0xFF888888);
    }

    private String truncateForWidth(String s, net.minecraft.client.gui.Font font, int maxPx) {
        if (s == null) return "";
        if (font.width(s) <= maxPx) return s;
        for (int len = s.length() - 1; len > 0; len--) {
            String candidate = s.substring(0, len) + "..";
            if (font.width(candidate) <= maxPx) return candidate;
        }
        return "..";
    }

    @Override
    public void buttonClicked(AbstractButton button) {
        PrefabCustomAddon.LOGGER.info("[CREATOR] buttonClicked: " + (button == null ? "null" : button.getMessage().getString()));
        if (button == this.btnClose) {
            this.onClose();
            return;
        }
        if (button == this.btnRefresh) {
            refreshPacks();
            setStatus("已刷新 (" + packs.size() + " 个拓展包, " + buildings.size() + " 个建筑)", 0x55FF55);
            return;
        }
        if (button == this.btnCreatePack) {
            GuiCreatePackInfo.open(null, this);
            return;
        }
        if (button == this.btnCreateBuilding) {
            if (selectedIndex < 0) return;
            PackCreator.PackWorkInfo p = packs.get(selectedIndex);
            GuiCreateBuildingInfo.open(p.id, null, this);
            return;
        }
        if (button == this.btnDeleteBuilding) {
            deleteSelectedBuilding();
            return;
        }
        if (button == this.btnOpenFolder) {
            openWorkFolder();
            return;
        }
        if (button == this.btnEditCover) {
            PrefabCustomAddon.LOGGER.info("[CREATOR] btnEditCover clicked, selected=" + selectedIndex);
            openCoverChooser();
            return;
        }
        if (button == this.btnDeletePack) {
            deleteSelectedPack();
            return;
        }
    }

    private void openCoverChooser() {
        if (selectedIndex < 0 || selectedIndex >= packs.size()) return;
        PackCreator.PackWorkInfo p = packs.get(selectedIndex);
        setStatus("正在打开文件选择器...", 0x55AAFF);
        SystemFilePicker.openAsync("选择拓展包封面 PNG", "png", r -> {
            if (r.isOk()) {
                handleCoverSelected(p, r.file);
            } else if (r.isCancelled()) {
                setStatus("✗ 已取消", 0x888888);
            } else {
                setStatus("✗ 选择器错误: " + r.message, 0xFF5555);
            }
        });
    }

    private void handleCoverSelected(PackCreator.PackWorkInfo p, File f) {
        try {
            byte[] data = Files.readAllBytes(f.toPath());
            // 验证
            try (var in = Files.newInputStream(f.toPath())) {
                com.mojang.blaze3d.platform.NativeImage ni =
                    com.mojang.blaze3d.platform.NativeImage.read(in);
                if (ni == null) {
                    setStatus("✗ 无效的 PNG 文件", 0xFF5555);
                    return;
                }
            }
            // 写为 information/cover.png
            Path cover = PackCreator.getWorkRoot()
                .resolve(p.id).resolve("information").resolve("cover.png");
            Files.createDirectories(cover.getParent());
            Files.write(cover, data);
            // 释放旧纹理
            ResourceLocation old = coverTextures.remove(p.id);
            if (old != null) Minecraft.getInstance().getTextureManager().release(old);
            loadedCoverFor = null;  // 强制重新加载
            refreshPacks();
            setStatus("✓ 已保存封面: " + f.getName(), 0x55FF55);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[CREATOR] save cover failed", t);
            setStatus("✗ 保存失败: " + t.getMessage(), 0xFF5555);
        }
    }

    private void deleteSelectedPack() {
        if (selectedIndex < 0 || selectedIndex >= packs.size()) return;
        PackCreator.PackWorkInfo p = packs.get(selectedIndex);
        try {
            PackCreator.getInstance().deletePack(p.id);
            setStatus("已删除拓展包: " + p.id, 0x55FF55);
            // 释放封面纹理
            ResourceLocation old = coverTextures.remove(p.id);
            if (old != null) Minecraft.getInstance().getTextureManager().release(old);
            selectedIndex = -1;
            selectedBuildingIndex = -1;
            buildings = new ArrayList<>();
            refreshPacks();
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.error("[CREATOR] delete pack failed", e);
            setStatus("✗ 删除失败: " + e.getMessage(), 0xFF5555);
        }
    }

    private void openEditBuilding() {
        if (selectedBuildingIndex < 0 || selectedBuildingIndex >= buildings.size()) return;
        PackCreator.PackWorkInfo p = packs.get(selectedIndex);
        PackCreator.BuildingWorkInfo b = buildings.get(selectedBuildingIndex);
        GuiCreateBuildingInfo.open(p.id, b, this);
    }

    private void deleteSelectedBuilding() {
        if (selectedBuildingIndex < 0 || selectedBuildingIndex >= buildings.size()) return;
        PackCreator.PackWorkInfo p = packs.get(selectedIndex);
        PackCreator.BuildingWorkInfo b = buildings.get(selectedBuildingIndex);
        try {
            PackCreator.getInstance().deleteBuilding(p.id, b.id);
            setStatus("已删除建筑: " + b.id, 0x55FF55);
            selectedBuildingIndex = -1;
            loadBuildingsForSelected();
            // 同时刷新 pack 列表的 buildingCount
            refreshPacks();
            updateButtonStates();
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.error("[CREATOR] delete building failed", e);
            setStatus("✗ 删除失败: " + e.getMessage(), 0xFF5555);
        }
    }

    private void openWorkFolder() {
        Path root = PackCreator.getWorkRoot();
        try {
            if (!Files.exists(root)) Files.createDirectories(root);
        } catch (IOException e) {
            setStatus("创建目录失败: " + e.getMessage(), 0xFF5555);
            return;
        }
        try {
            net.minecraft.Util.getPlatform().openUri(java.net.URI.create(root.toUri().toString()));
            setStatus("已打开: " + root, 0x55FF55);
        } catch (Throwable t1) {
            try {
                java.awt.Desktop.getDesktop().open(root.toFile());
                setStatus("已打开: " + root, 0x55FF55);
            } catch (Throwable t2) {
                setStatus("无法打开目录: " + t2.getMessage(), 0xFF5555);
            }
        }
    }

    /** 子界面回调: 重新扫描 */
    public void onChildClosed() {
        refreshPacks();
    }
}
