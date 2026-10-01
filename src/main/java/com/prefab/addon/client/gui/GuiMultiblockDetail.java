/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen
 *  com.lowdragmc.lowdraglib2.gui.ui.ModularUI
 *  com.lowdragmc.lowdraglib2.gui.ui.UI
 *  com.lowdragmc.lowdraglib2.gui.ui.UIElement
 *  com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal
 *  com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode
 *  com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap
 *  com.lowdragmc.lowdraglib2.gui.ui.elements.Button
 *  com.lowdragmc.lowdraglib2.gui.ui.elements.Label
 *  com.lowdragmc.lowdraglib2.gui.ui.elements.Scene
 *  com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView
 *  com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement
 *  com.lowdragmc.lowdraglib2.gui.ui.style.Stylesheet
 *  com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager
 *  com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites
 *  com.lowdragmc.lowdraglib2.utils.data.BlockInfo
 *  com.lowdragmc.lowdraglib2.utils.virtuallevel.TrackedDummyWorld
 *  com.prefab.addon.client.gui.GuiMultiblockDetail$PreviewDummyWorld
 *  dev.vfyjxf.taffy.style.AlignContent
 *  dev.vfyjxf.taffy.style.FlexDirection
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.screens.Screen
 *  net.minecraft.client.player.LocalPlayer
 *  net.minecraft.client.resources.language.I18n
 *  net.minecraft.core.BlockPos
 *  net.minecraft.network.chat.Component
 *  net.minecraft.world.item.ItemStack
 *  net.minecraft.world.item.Items
 *  net.minecraft.world.level.Level
 *  net.minecraft.world.level.block.state.BlockState
 */
package com.prefab.addon.client.gui;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Scene;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.gui.ui.style.Stylesheet;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.lowdragmc.lowdraglib2.utils.data.BlockInfo;
import com.lowdragmc.lowdraglib2.utils.virtuallevel.TrackedDummyWorld;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.client.MultiblockPreview;
import com.prefab.addon.client.PackBrowserKeyHandler;
import com.prefab.addon.client.gui.GuiMultiblockDetail;
import com.prefab.addon.client.gui.MaterialSubmissionGui;
import com.prefab.addon.multiblock.MultiblockCatalog;
import com.prefab.addon.multiblock.MultiblockShapeData;
import com.prefab.addon.work.ChallengeSessionManager;
import com.prefab.addon.work.MaterialCalculator;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.FlexDirection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class GuiMultiblockDetail {
    private static String currentId;
    private static MultiblockShapeData currentShape;
    private static MaterialCalculator.MaterialList currentMatList;
    private static Scene renderScene;
    private static TrackedDummyWorld renderWorld;
    private static boolean sceneInited;
    private static boolean sceneFailed;
    private static UIElement sceneContainer;
    private static TextElement scenePlaceholder;
    private static TextElement gateEl;
    private static final Map<String, TextElement> matProgressEls;
    private static TextElement statusEl;
    private static String statusMsg;
    private static int statusColor;
    private static int statusTick;

    private GuiMultiblockDetail() {
    }

    public static String displayName(MultiblockShapeData shape) {
        if (shape == null) {
            return "?";
        }
        String s = null;
        try {
            s = I18n.get((String)shape.langKey, (Object[])new Object[0]);
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        if (s == null || s.isEmpty() || s.equals(shape.langKey)) {
            int colon = shape.id.indexOf(58);
            return colon >= 0 ? shape.id.substring(colon + 1) : shape.id;
        }
        return s;
    }

    public static void open(String id) {
        MultiblockShapeData shape = MultiblockCatalog.getShape(id);
        if (shape == null) {
            PrefabCustomAddon.LOGGER.warn("[MB-DETAIL] \u627e\u4e0d\u5230\u591a\u65b9\u5757 id={}", (Object)id);
            return;
        }
        GuiMultiblockDetail.releaseScene();
        currentId = id;
        currentShape = shape;
        currentMatList = shape.toMaterialList();
        sceneInited = false;
        sceneFailed = false;
        sceneContainer = null;
        scenePlaceholder = null;
        gateEl = null;
        statusEl = null;
        matProgressEls.clear();
        statusMsg = null;
        statusTick = 0;
        ModularUI ui = GuiMultiblockDetail.createUI(shape);
        Minecraft.getInstance().setScreen((Screen)new ModularUIScreen(ui, (Component)Component.literal((String)GuiMultiblockDetail.displayName(shape))));
        PrefabCustomAddon.LOGGER.info("[MB-DETAIL] \u6253\u5f00\u591a\u65b9\u5757\u8be6\u7ec6\u754c\u9762 id={} blocks={}", (Object)id, (Object)shape.localBlocks.size());
    }

    private static void releaseScene() {
        if (renderScene != null) {
            try {
                renderScene.releaseRendererResource();
            }
            catch (Throwable throwable) {
                // empty catch block
            }
        }
        renderScene = null;
        renderWorld = null;
        sceneInited = false;
    }

    private static ModularUI createUI(MultiblockShapeData shape) {
        UIElement root = new UIElement();
        root.layout(l -> l.widthPercent(100.0f).heightPercent(100.0f).flexDirection(FlexDirection.COLUMN).paddingAll(2.0f).gapAll(2.0f));
        root.style(s -> s.background(Sprites.BORDER));
        root.setOverflowVisible(false);
        UIElement titleRow = new UIElement();
        titleRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f).height(22.0f).gapAll(4.0f).paddingLeft(4.0f).paddingRight(4.0f));
        titleRow.style(s -> s.background(Sprites.RECT_DARK));
        titleRow.setOverflowVisible(false);
        Button btnBackTop = new Button().setText("\u2190 \u8fd4\u56de");
        btnBackTop.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        btnBackTop.layout(l -> l.width(56.0f).height(20.0f));
        btnBackTop.setOnClick(e -> GuiMultiblockDetail.backToBrowser());
        titleRow.addChild((UIElement)btnBackTop);
        Label titleLabel = new Label();
        titleLabel.setText(GuiMultiblockDetail.displayName(shape));
        titleLabel.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        titleLabel.layout(l -> l.flexGrow(1.0f).height(20.0f));
        titleRow.addChild((UIElement)titleLabel);
        gateEl = new TextElement();
        gateEl.setText("");
        gateEl.textStyle(t -> t.textAlignHorizontal(Horizontal.RIGHT));
        gateEl.layout(l -> l.width(150.0f).height(20.0f));
        titleRow.addChild((UIElement)gateEl);
        root.addChild(titleRow);
        UIElement bodyRow = new UIElement();
        bodyRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f).flexGrow(1.0f).flexShrink(1.0f).gapAll(4.0f).minHeight(0.0f).minWidth(0.0f));
        bodyRow.setOverflowVisible(false);
        bodyRow.addChild((UIElement)GuiMultiblockDetail.createMaterialScroller(shape));
        sceneContainer = new UIElement();
        sceneContainer.layout(l -> l.flexGrow(1.0f).flexShrink(1.0f).heightPercent(100.0f).minHeight(0.0f).minWidth(0.0f));
        sceneContainer.style(s -> s.background(Sprites.RECT_DARK));
        sceneContainer.setOverflowVisible(false);
        scenePlaceholder = new TextElement();
        scenePlaceholder.setText("3D \u52a0\u8f7d\u4e2d...");
        scenePlaceholder.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textColor(0xAAAAAA).textWrap(TextWrap.WRAP));
        scenePlaceholder.layout(l -> l.widthPercent(100.0f).heightPercent(100.0f).justifyContent(AlignContent.CENTER));
        sceneContainer.addChild((UIElement)scenePlaceholder);
        bodyRow.addChild(sceneContainer);
        root.addChild(bodyRow);
        statusEl = new TextElement();
        statusEl.setText("");
        statusEl.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        statusEl.layout(l -> l.widthPercent(100.0f).height(12.0f));
        root.addChild((UIElement)statusEl);
        UIElement buttonRow = new UIElement();
        buttonRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f).height(22.0f).gapAll(4.0f).justifyContent(AlignContent.CENTER));
        Button btnBack = new Button().setText("\u8fd4\u56de");
        btnBack.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        btnBack.layout(l -> l.flexGrow(1.0f).heightPercent(100.0f));
        btnBack.setOnClick(e -> GuiMultiblockDetail.backToBrowser());
        buttonRow.addChild((UIElement)btnBack);
        Button btnSubmit = new Button().setText("\u63d0\u4ea4\u6750\u6599");
        btnSubmit.textStyle(t -> t.textColor(-11141291).textAlignHorizontal(Horizontal.CENTER));
        btnSubmit.layout(l -> l.flexGrow(1.0f).heightPercent(100.0f));
        btnSubmit.setOnClick(e -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || currentMatList == null) {
                return;
            }
            MaterialSubmissionGui.open(shape.sessionKey(), GuiMultiblockDetail.displayName(shape), currentMatList, player);
        });
        buttonRow.addChild((UIElement)btnSubmit);
        Button btnPreview = new Button().setText("\u9884\u89c8/\u5efa\u9020");
        btnPreview.textStyle(t -> t.textColor(-11162881).textAlignHorizontal(Horizontal.CENTER));
        btnPreview.layout(l -> l.flexGrow(1.0f).heightPercent(100.0f));
        btnPreview.setOnClick(e -> {
            if (!MultiblockPreview.start(shape.id)) {
                GuiMultiblockDetail.showStatus("\u2717 \u9884\u89c8\u5931\u8d25: \u7ed3\u6784\u4e3a\u7a7a\u6216\u4e0d\u5b58\u5728", 0xFF5555, 80);
                return;
            }
            GuiMultiblockDetail.releaseScene();
        });
        buttonRow.addChild((UIElement)btnPreview);
        root.addChild(buttonRow);
        int[] tickCounter = new int[]{0};
        root.addEventListener("tick", event -> {
            tickCounter[0] = tickCounter[0] + 1;
            int t = tickCounter[0];
            if (!sceneInited && !sceneFailed) {
                GuiMultiblockDetail.initScene(shape);
                if (renderScene != null && sceneContainer != null) {
                    sceneContainer.clearAllChildren();
                    renderScene.layout(l -> l.widthPercent(100.0f).heightPercent(100.0f));
                    sceneContainer.addChild((UIElement)renderScene);
                } else if (sceneFailed && scenePlaceholder != null) {
                    scenePlaceholder.setText("3D \u9884\u89c8\u4e0d\u53ef\u7528");
                    scenePlaceholder.textStyle(tt -> tt.textColor(0xFFAA55));
                }
            }
            if (t % 20 == 0) {
                GuiMultiblockDetail.refreshProgress(shape);
            }
            if (statusTick > 0 && statusMsg != null && statusEl != null) {
                statusEl.setText(statusMsg);
                statusEl.textStyle(tt -> tt.textColor(statusColor));
                if (--statusTick <= 0) {
                    statusMsg = null;
                    statusEl.setText("");
                }
            }
        });
        // Scene tooltip 依赖 ModularUI.player (getCloneItemStack 需要玩家), 客户端界面必须显式传入
        return ModularUI.of((UI)UI.of((UIElement)root, (Stylesheet[])new Stylesheet[]{StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)}),
                Minecraft.getInstance().player);
    }

    private static ScrollerView createMaterialScroller(MultiblockShapeData shape) {
        ScrollerView scroller = new ScrollerView();
        scroller.scrollerStyle(s -> s.mode(ScrollerMode.VERTICAL));
        scroller.verticalScroller(v -> v.setScrollBarSize(8.0f));
        UIElement content = new UIElement();
        content.layout(l -> l.flexDirection(FlexDirection.COLUMN).gapAll(3.0f).paddingLeft(10.0f).paddingRight(6.0f).paddingTop(4.0f).paddingBottom(4.0f));
        content.style(s -> s.background(Sprites.RECT_DARK));
        GuiMultiblockDetail.addField(content, "\u540d\u79f0", GuiMultiblockDetail.displayName(shape));
        GuiMultiblockDetail.addField(content, "ID", shape.id);
        GuiMultiblockDetail.addField(content, "\u5c3a\u5bf8", shape.width() + "x" + shape.height() + "x" + shape.length());
        GuiMultiblockDetail.addField(content, "\u65b9\u5757\u6570", String.valueOf(shape.localBlocks.size()));
        GuiMultiblockDetail.addLabel(content, "\u6750\u6599\u6e05\u5355 (" + GuiMultiblockDetail.currentMatList.required.size() + " \u79cd):");
        for (Map.Entry<String, Integer> e : GuiMultiblockDetail.currentMatList.required.entrySet()) {
            String blockId = e.getKey();
            int required = e.getValue();
            GuiMultiblockDetail.addLabel(content, GuiMultiblockDetail.materialName(blockId) + " x" + required);
            TextElement prog = new TextElement();
            prog.setText("\u5df2\u4ea4 0 / " + required);
            prog.textStyle(t -> t.textColor(-21931));
            prog.layout(l -> l.widthPercent(100.0f).height(12.0f));
            content.addChild((UIElement)prog);
            matProgressEls.put(blockId, prog);
        }
        if (GuiMultiblockDetail.currentMatList.filteredInfrastructure > 0) {
            GuiMultiblockDetail.addValueLine(content, "(\u5df2\u8fc7\u6ee4\u57fa\u7840\u8bbe\u65bd\u65b9\u5757 " + GuiMultiblockDetail.currentMatList.filteredInfrastructure + " \u4e2a)", -7829368);
        }
        if (GuiMultiblockDetail.currentMatList.required.isEmpty()) {
            GuiMultiblockDetail.addValueLine(content, "\u65e0\u9700\u63d0\u4ea4\u6750\u6599", -7829368);
        }
        scroller.addScrollViewChild(content);
        scroller.layout(l -> l.width(150.0f).heightPercent(100.0f).minHeight(0.0f));
        return scroller;
    }

    private static String materialName(String blockId) {
        try {
            String s;
            ItemStack stack = MaterialCalculator.getItemStack(blockId);
            if (stack != null && !stack.isEmpty() && stack.getItem() != Items.BARRIER && stack.getItem() != Items.GRAY_STAINED_GLASS_PANE && (s = stack.getHoverName().getString()) != null && !s.isEmpty()) {
                return s;
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        return blockId;
    }

    private static void addField(UIElement parent, String label, String value) {
        GuiMultiblockDetail.addLabel(parent, label + ":");
        GuiMultiblockDetail.addValueLine(parent, value, 0xFFFFFF);
    }

    private static void addLabel(UIElement parent, String text) {
        TextElement lbl = new TextElement();
        lbl.setText(text);
        lbl.textStyle(t -> t.textColor(0xFFFF55));
        lbl.layout(l -> l.widthPercent(100.0f).height(12.0f));
        parent.addChild((UIElement)lbl);
    }

    private static void addValueLine(UIElement parent, String text, int color) {
        TextElement val = new TextElement();
        val.setText(text);
        val.textStyle(t -> t.textColor(color));
        val.layout(l -> l.widthPercent(100.0f).height(12.0f));
        parent.addChild((UIElement)val);
    }

    private static void initScene(MultiblockShapeData shape) {
        try {
            ArrayList<BlockPos> positions = new ArrayList<BlockPos>(shape.localBlocks.size());
            HashMap<BlockPos, BlockInfo> blockMap = new HashMap<BlockPos, BlockInfo>();
            for (Map.Entry<BlockPos, BlockState> e : shape.localBlocks.entrySet()) {
                BlockState st = e.getValue();
                if (st == null || st.isAir()) continue;
                positions.add(e.getKey());
                blockMap.put(e.getKey(), BlockInfo.fromBlockState((BlockState)st));
            }
            if (blockMap.isEmpty()) {
                sceneFailed = true;
                return;
            }
            renderWorld = new PreviewDummyWorld();
            renderScene = new Scene();
            renderScene.useOrtho(true);
            renderScene.setDraggable(true);
            renderScene.setScalable(true);
            renderScene.setIntractable(true);
            renderScene.setRenderFacing(false);
            renderScene.setRenderSelect(false);
            renderScene.useCacheBuffer(true);
            renderScene.syncCompile(true);
            renderScene.setTickWorld(false);
            renderScene.createScene((Level)renderWorld);
            renderScene.setRenderedCore(positions, null, true);
            // 悬停方块显示原生物品 tooltip (Scene.showHoverBlockTips, 不依赖 JEI);
            // xeiLookup 另提供 JEI/REI/EMI 查询集成
            renderScene.setShowHoverBlockTips(true);
            renderScene.xeiLookup();
            renderWorld.addBlocks(blockMap);
            renderScene.needCompileCache();
            sceneInited = true;
            PrefabCustomAddon.LOGGER.info("[MB-DETAIL] 3D \u521d\u59cb\u5316\u5b8c\u6210: {} blocks", (Object)blockMap.size());
        }
        catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[MB-DETAIL] initScene failed", t);
            sceneFailed = true;
        }
    }

    private static void refreshProgress(MultiblockShapeData shape) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || currentMatList == null) {
            return;
        }
        UUID uuid = mc.player.getUUID();
        // 创造模式免材料: 进度区只显示"创造免费", 不再要求提交
        if (mc.player.isCreative()) {
            for (Map.Entry<String, TextElement> e : matProgressEls.entrySet()) {
                int req = GuiMultiblockDetail.currentMatList.required.getOrDefault(e.getKey(), 0);
                e.getValue().setText("\u521b\u9020\u514d\u8d39 (" + req + " \u5757)");
                e.getValue().textStyle(t -> t.textColor(-11141291));
            }
            if (gateEl != null) {
                gateEl.setText("\u2713 \u521b\u9020\u6a21\u5f0f\u514d\u6750\u6599 \u00b7 " + PackBrowserKeyHandler.buildKeyName() + " \u53ef\u5efa\u9020");
                gateEl.textStyle(t -> t.textColor(-11141291));
            }
            return;
        }
        Map<String, Integer> submitted = ChallengeSessionManager.getSubmitted(uuid, shape.sessionKey());
        for (Map.Entry<String, TextElement> e : matProgressEls.entrySet()) {
            int req = GuiMultiblockDetail.currentMatList.required.getOrDefault(e.getKey(), 0);
            int sub = Math.min(req, submitted.getOrDefault(e.getKey(), 0));
            e.getValue().setText("\u5df2\u4ea4 " + sub + " / " + req);
            e.getValue().textStyle(t -> t.textColor(sub >= req ? -11141291 : -21931));
        }
        boolean ready = ChallengeSessionManager.isReady(uuid, shape.sessionKey(), GuiMultiblockDetail.currentMatList.required);
        if (gateEl != null) {
            gateEl.setText(ready ? "\u2713 \u6750\u6599\u5df2\u4ea4\u9f50 \u00b7 " + PackBrowserKeyHandler.buildKeyName() + " \u53ef\u5efa\u9020" : "\u6750\u6599\u672a\u4ea4\u9f50 \u00b7 \u4ec5\u53ef\u9884\u89c8");
            gateEl.textStyle(t -> t.textColor(ready ? -11141291 : -21931));
        }
    }

    private static void backToBrowser() {
        GuiMultiblockDetail.releaseScene();
        GuiMultiblockBrowser.open();
    }

    private static void showStatus(String msg, int color, int ticks) {
        statusMsg = msg;
        statusColor = color;
        statusTick = ticks;
        if (statusEl != null) {
            statusEl.setText(msg);
            statusEl.textStyle(t -> t.textColor(color));
        }
    }

    static {
        matProgressEls = new LinkedHashMap<String, TextElement>();
        statusMsg = null;
        statusColor = 0x55FF55;
        statusTick = 0;
    }

    /**
     * 多方块预览专用 dummy world: 覆写 {@link #getBlockEntity} 恒返回 null, 关闭所有 TESR
     * (方块实体特殊渲染器).
     *
     * <p><b>为什么:</b> LDLib2 的 {@code WorldSceneRenderer} 会自动收集带渲染器的方块实体并
     * 调用其 TESR. 多方块预览放的是<b>未成型</b>的孤立方块, 例如 Mekanism 工业涡轮机的
     * {@code RenderIndustrialTurbine} 直接访问 {@code multiblock.complex.getX()} 而不判空,
     * 未成型时 {@code complex == null} → NPE, 导致整个界面渲染崩溃 (见 crash 报告).</p>
     *
     * <p>结构预览只需要方块的静态模型 (casing / structural_glass / valve / rotor 等都是模型方块),
     * 不需要任何动态特殊渲染 (转子旋转等), 故统一屏蔽 getBlockEntity, 从源头杜绝此类崩溃,
     * 对 GTM / Mekanism 及未来任何非空安全的 TESR 通用.</p>
     *
     * <p><b>作用域限制:</b> 仅用于多方块预览。自定义建筑预览 (CustomStructureGui /
     * Construction3DView) 仍需 TESR 渲染箱子等纯特殊渲染方块, 切勿套用本类, 否则会漏渲染.</p>
     */
    private static final class PreviewDummyWorld extends TrackedDummyWorld {
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }
    }
}

