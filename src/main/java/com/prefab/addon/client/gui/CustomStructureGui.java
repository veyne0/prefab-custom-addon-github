package com.prefab.addon.client.gui;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Scene;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.lowdragmc.lowdraglib2.utils.data.BlockInfo;
import com.lowdragmc.lowdraglib2.utils.virtuallevel.TrackedDummyWorld;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.config.PlayerPreferences;
import com.prefab.addon.extension.ConstructionInfo;
import com.prefab.addon.structure.CustomStructureBuilder;
import com.prefab.addon.structure.CustomStructureBuilder.BlockData;
import com.prefab.addon.work.MaterialCalculator;
import com.prefab.addon.work.NbtStructureParser;
import com.prefab.structures.base.Structure;
import com.prefab.structures.config.StructureConfiguration;
import com.prefab.structures.render.StructureRenderHandler;

import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 自定义建筑选择界面 (右键自定义蓝图打开的界面) - LDLib2 实现.
 *
 * <h2>布局 (整屏, 100% width/height)</h2>
 * <pre>
 *   ┌────────────────────────────────────┐
 *   │                                    │
 *   │       LDLib2 Scene 3D 预览          │ (flexGrow=1, 占满中间)
 *   │       (异步 NBT 解析 + 增量渲染)    │
 *   │                                    │
 *   ├────────────────────────────────────┤
 *   │ [取消] [预览] [建造] [更换建筑]    │ 普通模式 (h=22)
 *   │ [取消] [提交材料] [更换建筑]       │ 挑战模式 (h=22)
 *   └────────────────────────────────────┘
 * </pre>
 *
 * <p>"预览" 按钮: 关闭当前 GUI, 调用 {@link StructureRenderHandler#setStructure}
 *  开启世界内预览 (Prefab 原生 GuiStructure 风格), 玩家可以用方向键移动、CTRL 旋转、
 *  ALT 直接在预览位置建造. 配合 {@link com.prefab.addon.client.StructurePreviewKeyHandler}.</p>
 *
 * <p>"建造" 按钮: 直接发送网络包建造, 跟之前的 performBuildOrPreview 一样.</p>
 *
 * <h2>3D 预览异步加载 + 10% 增量</h2>
 * <ol>
 *   <li>UI 立即打开 (不阻塞主线程) - 中间显示 "3D 加载中..."</li>
 *   <li>后台线程 CompletableFuture 解析 NBT → List&lt;BlockData&gt;</li>
 *   <li>主线程 tick: 每 tick 加 10% blocks 进 TrackedDummyWorld, 全部加完后触发一次最终重编译</li>
 * </ol>
 */
public class CustomStructureGui {

    // === 异步 + 增量渲染常量 ===
    private static final int BATCH_PERCENT = 10;
    private static final int MAX_BATCH_SIZE = 1500;

    // === 异步解析状态 (独立于 GuiConstructionDetail, 避免冲突) ===
    private static ConstructionInfo currentConstruction;
    private static ItemStack currentBlueprint;
    private static BlockPos currentOpenPos;
    private static CompletableFuture<List<BlockData>> parseFuture;
    private static volatile List<BlockData> parseResult;
    private static volatile boolean parseComplete;
    private static volatile boolean parseFailed;
    private static final Object parseLock = new Object();

    // === 预览来源追踪 ===
    // 区分"我们 GUI 启动的预览"和"prefab 原版 GUI 启动的预览":
    // 用 prefab 原版的 prefab.Structure 引用做 key, 我们自己的预览跟 prefab 的引用不会撞.
    // StructureRenderHandler.setStructure(structure, cfg) 后, 玩家可能:
    //   1) 切回 prefab GuiStructure → prefab 自己 setStructure 覆盖, reference 换 → 我们识别为原版
    //   2) 直接在世界里按 ALT 建造 → 我们 setStructure(null, null) → next tick 看不到这个 structure → 识别为 None
    private static final java.util.concurrent.atomic.AtomicReference<com.prefab.structures.base.Structure>
        LAST_ADDON_STRUCTURE_REF = new java.util.concurrent.atomic.AtomicReference<>(null);
    private static final java.util.concurrent.atomic.AtomicBoolean
        LAST_ADDON_PREVIEW = new java.util.concurrent.atomic.AtomicBoolean(false);

    // === 增量渲染状态 ===
    private static Scene renderScene;
    private static TrackedDummyWorld renderWorld;
    private static List<BlockData> renderBlocks;
    private static int renderNextIndex;
    private static int renderTotalBlocks;
    private static int renderBatchSize;
    private static volatile boolean renderActive;
    private static volatile boolean renderDone;
    private static boolean finalCompileRequested;

    // === NBT 解析后产生的派生数据 (供按钮逻辑用) ===
    private static MaterialCalculator.MaterialList materialList;
    private static NbtStructureParser.NbtInfo nbtInfo;
    private static boolean challengeMode;

    // === UI 引用 (tick handler 用) ===
    private static TextElement statusEl;
    private static Button btnMain;  // 预览/建造 或 提交材料(挑战模式)

    // === 状态消息 (例如 "提交材料后才能建造" 等提示) ===
    private static int statusTick = 0;
    private static String statusMsg = null;
    private static int statusColor = 0x55FF55;

    private CustomStructureGui() {}

    /**
     * 打开自定义建筑选择界面.
     *
     * @param construction 当前建筑
     * @param blueprint 主手蓝图 (用于锁定检测, 可为 null)
     * @param openPos 玩家右键蓝图时的位置 (用于构建位置)
     */
    public static void open(ConstructionInfo construction, ItemStack blueprint, BlockPos openPos) {
        resetState();
        currentConstruction = construction;
        currentBlueprint = blueprint;
        currentOpenPos = openPos == null ? BlockPos.ZERO : openPos;
        challengeMode = PlayerPreferences.get().consumeMaterials;

        PrefabCustomAddon.LOGGER.info("[CUSTOM-GUI-V2] open: construction='{}' id='{}' challengeMode={}",
            construction.getName(), construction.getId(), challengeMode);

        startAsyncParse(construction);
        ModularUI ui = createUI(construction);
        Minecraft.getInstance().setScreen(
            new ModularUIScreen(ui, Component.literal(construction.getName())));
    }

    private static void resetState() {
        synchronized (parseLock) {
            currentConstruction = null;
            currentBlueprint = null;
            currentOpenPos = null;
            parseFuture = null;
            parseResult = null;
            parseComplete = false;
            parseFailed = false;
            renderScene = null;
            renderWorld = null;
            renderBlocks = null;
            renderNextIndex = 0;
            renderTotalBlocks = 0;
            renderBatchSize = 0;
            renderActive = false;
            renderDone = false;
            finalCompileRequested = false;
            materialList = null;
            nbtInfo = null;
        }
        statusTick = 0;
        statusMsg = null;
        challengeMode = false;
    }

    private static void startAsyncParse(ConstructionInfo construction) {
        final String name = construction.getName();
        final String packId = construction.getPack().getPackageName();
        synchronized (parseLock) {
            parseFuture = CompletableFuture.supplyAsync(() -> {
                long t0 = System.currentTimeMillis();
                try {
                    byte[] nbtData = construction.getNbtData();
                    if (nbtData == null || nbtData.length == 0) {
                        PrefabCustomAddon.LOGGER.warn("[CUSTOM-GUI-V2] No NBT data for '{}' (pack={})", name, packId);
                        return null;
                    }
                    PrefabCustomAddon.LOGGER.info("[CUSTOM-GUI-V2] Async parse start: '{}' (pack={}, nbtBytes={})",
                        name, packId, nbtData.length);
                    // 解析 NBT → blocks + 派生 material list + nbt info
                    CustomStructureBuilder.getInstance().loadStructureFromNbt(construction);
                    List<BlockData> blocks = CustomStructureBuilder.getInstance().parseStructureBlocks();
                    try {
                        materialList = MaterialCalculator.calculate(nbtData);
                        nbtInfo = NbtStructureParser.parse(nbtData);
                    } catch (Throwable t) {
                        PrefabCustomAddon.LOGGER.warn("[CUSTOM-GUI-V2] Material/info parse failed (non-fatal)", t);
                    }
                    long ms = System.currentTimeMillis() - t0;
                    PrefabCustomAddon.LOGGER.info("[CUSTOM-GUI-V2] Async parse done: '{}' → {} blocks ({} ms)",
                        name, blocks != null ? blocks.size() : 0, ms);
                    return blocks;
                } catch (Throwable t) {
                    PrefabCustomAddon.LOGGER.error("[CUSTOM-GUI-V2] Async parse failed for '{}'", name, t);
                    return null;
                }
            });
        }
    }

    private static void checkParse() {
        if (parseComplete || parseFailed) return;
        if (parseFuture == null) return;
        if (!parseFuture.isDone()) return;

        synchronized (parseLock) {
            if (parseComplete || parseFailed) return;
            try {
                List<BlockData> result = parseFuture.get(0, TimeUnit.MILLISECONDS);
                parseResult = result;
                parseComplete = true;
                if (result == null) {
                    parseFailed = true;
                }
            } catch (Exception e) {
                PrefabCustomAddon.LOGGER.error("[CUSTOM-GUI-V2] Parse future get failed", e);
                parseResult = null;
                parseComplete = true;
                parseFailed = true;
            }
        }
    }

    private static void initRender() {
        if (parseResult == null || parseResult.isEmpty()) {
            renderDone = true;
            return;
        }
        try {
            // 不能传 realLevel 当 proxy - 必须用纯 TrackedDummyWorld
            renderWorld = new TrackedDummyWorld();

            renderBlocks = parseResult;
            renderTotalBlocks = renderBlocks.size();
            renderBatchSize = Math.min(MAX_BATCH_SIZE,
                Math.max(1, renderTotalBlocks * BATCH_PERCENT / 100));
            renderNextIndex = 0;

            // 跳过 air + null blocks
            List<BlockData> nonAir = new ArrayList<>();
            for (BlockData bd : renderBlocks) {
                if (bd == null || bd.pos == null || bd.state == null) continue;
                if (bd.state.isAir()) continue;
                nonAir.add(bd);
            }
            if (nonAir.isEmpty()) {
                PrefabCustomAddon.LOGGER.warn("[CUSTOM-GUI-V2] All {} blocks are air, nothing to render", renderTotalBlocks);
                renderDone = true;
                return;
            }

            // 不采样 - 全部方块都渲染
            List<BlockData> renderList = nonAir;
            List<BlockPos> positions = new ArrayList<>(renderList.size());
            for (BlockData bd : renderList) {
                positions.add(bd.pos);
            }

            PrefabCustomAddon.LOGGER.info("[CUSTOM-GUI-V2] initRender: {} total / {} non-air / batch={}/tick",
                renderTotalBlocks, nonAir.size(), renderBatchSize);

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

            renderScene.createScene(renderWorld);
            renderScene.setRenderedCore(positions, null, true);

            renderActive = true;
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[CUSTOM-GUI-V2] initRender failed", t);
            renderDone = true;
        }
    }

    private static void tickRender() {
        if (!renderActive || renderScene == null || renderWorld == null) {
            renderActive = false;
            renderDone = true;
            return;
        }

        int endIdx = Math.min(renderTotalBlocks, renderNextIndex + renderBatchSize);
        if (endIdx <= renderNextIndex) {
            if (!finalCompileRequested) {
                finalCompileRequested = true;
                renderScene.needCompileCache();
            }
            renderActive = false;
            renderDone = true;
            return;
        }

        Map<BlockPos, BlockInfo> blockMap = new HashMap<>();
        for (int i = renderNextIndex; i < endIdx; i++) {
            BlockData bd = renderBlocks.get(i);
            if (bd == null || bd.pos == null || bd.state == null) continue;
            if (bd.state.isAir()) continue;
            blockMap.put(bd.pos, BlockInfo.fromBlockState(bd.state));
        }
        if (!blockMap.isEmpty()) {
            renderWorld.addBlocks(blockMap);
        }
        renderNextIndex = endIdx;

        if (renderNextIndex >= renderTotalBlocks) {
            if (!finalCompileRequested) {
                finalCompileRequested = true;
                renderScene.needCompileCache();
            }
            renderActive = false;
            renderDone = true;
        }
    }

    private static int getRenderProgress() {
        if (renderTotalBlocks <= 0) return 0;
        return Math.min(100, renderNextIndex * 100 / renderTotalBlocks);
    }

    private static ModularUI createUI(ConstructionInfo construction) {
        PrefabCustomAddon.LOGGER.info("[CUSTOM-GUI-V2] createUI: construction='{}'", construction.getName());

        // 根容器 - 填满屏幕
        UIElement root = new UIElement();
        root.layout(l -> l
            .widthPercent(100).heightPercent(100)
            .flexDirection(FlexDirection.COLUMN)
            .paddingAll(2).gapAll(2)
        );
        root.style(s -> s.background(Sprites.BORDER));
        root.setOverflowVisible(false);

        // === 中间: 3D 预览容器 (flexGrow=1 占满中间) ===
        UIElement sceneContainer = new UIElement();
        sceneContainer.layout(l -> l
            .flexGrow(1).flexShrink(1)
            .widthPercent(100).heightPercent(100)
            .minHeight(0).minWidth(0)
        );
        sceneContainer.style(s -> s.background(Sprites.RECT_DARK));
        sceneContainer.setOverflowVisible(false);

        // 初始 placeholder
        final TextElement scenePlaceholder = new TextElement();
        scenePlaceholder.setText("3D 加载中...\n(等待 NBT 解析)");
        scenePlaceholder.textStyle(t -> t
            .textAlignHorizontal(Horizontal.CENTER)
            .textColor(0xAAAAAA)
            .textWrap(TextWrap.WRAP));
        scenePlaceholder.layout(l -> l.widthPercent(100).heightPercent(100)
            .justifyContent(AlignContent.CENTER));
        sceneContainer.addChild(scenePlaceholder);

        root.addChild(sceneContainer);

        // === 底部: 状态消息 (简短, 一行) ===
        statusEl = new TextElement();
        statusEl.setText("");
        statusEl.textStyle(t -> t
            .textAlignHorizontal(Horizontal.CENTER)
            .textColor(0xAAAAAA));
        statusEl.layout(l -> l.widthPercent(100).height(12));
        root.addChild(statusEl);

        // === 底部: 按钮行 ===
        // 普通模式: [取消] [预览] [更换建筑] (3 buttons, h=22)
        //   - 建造用预览界面里按 ALT 完成, 不需要单独按钮
        // 挑战模式: [取消] [提交材料] [更换建筑] (3 buttons, h=22)
        UIElement buttonRow = new UIElement();
        buttonRow.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .widthPercent(100).height(22)
            .gapAll(2)
            .justifyContent(AlignContent.CENTER)
        );
        buttonRow.setOverflowVisible(false);

        // 取消按钮
        Button btnCancel = new Button().setText("取消");
        btnCancel.setOnClick(e -> {
            if (renderScene != null) renderScene.releaseRendererResource();
            Minecraft.getInstance().setScreen(null);
        });
        btnCancel.layout(l -> l.flexGrow(1).heightPercent(100));
        buttonRow.addChild(btnCancel);

        if (challengeMode) {
            // 挑战模式: 单个 "提交材料" 按钮
            btnMain = new Button().setText("提交材料");
            btnMain.setOnClick(e -> handleMainButtonClick());
            btnMain.layout(l -> l.flexGrow(2).heightPercent(100));
            buttonRow.addChild(btnMain);
        } else {
            // 普通模式: 只剩 "预览" 按钮 (建造用预览界面里的 ALT 完成)
            Button btnPreview = new Button().setText("预览");
            btnPreview.setOnClick(e -> handlePreviewButtonClick());
            btnPreview.layout(l -> l.flexGrow(2).heightPercent(100));
            buttonRow.addChild(btnPreview);
        }

        // 更换建筑按钮
        // 需求: 跳到 GuiConstructionDetail (第5张图, 带 [选择] + [选择并锁定] 按钮的详情界面),
        //       而不是 GuiCustomStructureSelection (第3张图, 中间的建筑列表层).
        // 流程: 关闭当前 CustomStructureGui, 释放 3D 渲染资源, 打开建筑详情 (会显示当前 pack 的第一个建筑 + 切换按钮).
        Button btnChange = new Button().setText("更换建筑");
        btnChange.setOnClick(e -> {
            if (renderScene != null) renderScene.releaseRendererResource();
            // 关闭当前界面 (释放 ModularUI 资源), 打开建筑详情.
            // 之前: GuiCustomStructureSelection.open();  // 第3张图: 列表层 (有 PACKS/BUILDINGS 切换)
            // 现在: GuiConstructionDetail.openFirstAvailable();  // 第5张图: 详情层 (有 [选择] / [选择并锁定])
            GuiConstructionDetail.openFirstAvailable();
        });
        btnChange.layout(l -> l.flexGrow(1).heightPercent(100));
        // 蓝图锁定时变灰
        if (currentBlueprint != null && !currentBlueprint.isEmpty()
            && com.prefab.addon.items.CustomBlueprintItem.isLocked(currentBlueprint)) {
            btnChange.setActive(false);
            btnChange.setText("🔒 已锁定");
        }
        buttonRow.addChild(btnChange);

        root.addChild(buttonRow);

        // === tick handler - 整个 3D 预览 + 按钮状态的生命周期 ===
        final int[] debugTickCounter = {0};
        root.addEventListener(UIEvents.TICK, event -> {
            debugTickCounter[0]++;
            final int tickNum = debugTickCounter[0];

            // 解析状态检查
            if (!parseComplete && !parseFailed) {
                checkParse();
            }

            // parse 完成后初始化 render
            if (parseComplete && !parseFailed && renderScene == null && !renderActive && !renderDone) {
                initRender();
                if (renderScene != null) {
                    sceneContainer.clearAllChildren();
                    renderScene.layout(l -> l.widthPercent(100).heightPercent(100));
                    sceneContainer.addChild(renderScene);
                } else if (renderDone) {
                    scenePlaceholder.setText("3D 预览不可用\n(场景创建失败)");
                }
            }

            // 渲染中
            if (renderActive) {
                tickRender();
                if (tickNum % 5 == 0 && statusMsg == null) {
                    statusEl.setText("渲染 " + getRenderProgress() + "%");
                    statusEl.textStyle(t -> t.textColor(0xFFFF55));
                }
            }

            // 渲染完成
            if (renderDone) {
                if (renderScene != null) {
                    if (statusMsg == null) {
                        statusEl.setText("✓ " + construction.getName() + " (" + renderTotalBlocks + " 块)");
                        statusEl.textStyle(t -> t.textColor(0x55FF55));
                    }
                } else if (parseFailed) {
                    if (statusMsg == null) {
                        statusEl.setText("✗ NBT 解析失败");
                        statusEl.textStyle(t -> t.textColor(0xFF5555));
                    }
                } else if (parseResult != null && parseResult.isEmpty()) {
                    scenePlaceholder.setText("无 3D 内容\n(所有方块均为 air\n可能缺依赖 mod)");
                    scenePlaceholder.textStyle(t -> t.textColor(0xFFAA55)
                        .textAlignHorizontal(Horizontal.CENTER)
                        .textWrap(TextWrap.WRAP));
                    if (statusMsg == null) {
                        statusEl.setText("⚠ 无可显示方块");
                        statusEl.textStyle(t -> t.textColor(0xFFAA55));
                    }
                }
            }

            // 状态消息倒计时
            if (statusTick > 0 && statusMsg != null) {
                statusEl.setText(statusMsg);
                statusEl.textStyle(t -> t.textColor(statusColor));
                statusTick--;
                if (statusTick <= 0) {
                    statusMsg = null;
                }
            }

            // 按钮状态刷新
            updateMainButtonState();
        });

        return ModularUI.of(UI.of(root,
            StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)));
    }

    /** 建造按钮 (或挑战模式下的"提交材料"/"预览"按钮) 点击处理 */
    private static void handleMainButtonClick() {
        if (challengeMode) {
            // 挑战模式: 按钮在材料未交齐时显示 "提交材料", 交齐后变成 "预览".
            // 用 checkMaterialsReady() 决定当前按下去是哪种行为.
            if (checkMaterialsReady()) {
                // 材料已交齐 → 按钮现在是 "预览", 按下去走预览/建造流程
                handlePreviewButtonClick();
                return;
            }
            // 材料未交齐 → 按钮现在是 "提交材料", 打开提交界面
            if (materialList == null) {
                showStatus("⚠ NBT 还未解析, 稍等", 0xFFAA00, 80);
                return;
            }
            net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) return;
            MaterialSubmissionGui.open(currentConstruction, materialList, challengeMode, player);
            return;
        }
        // 实际建造 (异步网络包, 服务端完成后消耗蓝图)
        performBuildOrPreview();
    }

    /**
     * "预览" 按钮: 关闭当前 GUI, 调用 {@link StructureRenderHandler#setStructure}
     * 开启世界内预览 (Prefab 原生 GuiStructure 风格).
     *
     * <p>玩家在世界中用方向键微调位置, CTRL 旋转 90°, ALT 在当前位置直接建造.</p>
     */
    private static void handlePreviewButtonClick() {
        if (currentConstruction == null) {
            showStatus("⚠ 建筑信息还未加载, 稍等", 0xFFAA00, 80);
            return;
        }
        // 1) NBT → Prefab Structure (跟服务端 build 用的是同一个解析)
        Structure structure;
        try {
            structure = CustomStructureBuilder.parseToPrefabStructure(currentConstruction);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[CUSTOM-GUI-V2] parseToPrefabStructure failed for preview", t);
            showStatus("✗ NBT 解析失败, 无法预览", 0xFF5555, 100);
            return;
        }
        if (structure == null || structure.getBlocks() == null || structure.getBlocks().isEmpty()) {
            showStatus("⚠ 结构为空, 无法预览", 0xFFAA55, 100);
            return;
        }

        // 2) 玩家朝向 (初始化: SOUTH)
        net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
        Direction houseFacing = (player != null)
            ? player.getDirection().getOpposite()
            : Direction.SOUTH;

        // 3) 构造 StructureConfiguration
        StructureConfiguration cfg = new StructureConfiguration();
        cfg.Initialize();
        cfg.pos = currentOpenPos != null ? currentOpenPos : BlockPos.ZERO;
        cfg.houseFacing = houseFacing;

        // 4) 把每个 BuildBlock.blockPos 从 localPos 转换为 worldPos (basePos + rotated local)
        CustomStructureBuilder.offsetStructureBlocks(structure, cfg.pos, cfg.houseFacing);

        // 5) 释放 2D 预览的 Scene 资源
        if (renderScene != null) {
            try { renderScene.releaseRendererResource(); } catch (Throwable ignored) {}
            renderScene = null;
        }

        // 6) 调用 Prefab 原生 StructureRenderHandler - 进入世界内预览
        StructureRenderHandler.setStructure(structure, cfg);

        // 标记: 当前预览是由**我们的 GUI** 启动的. StructurePreviewKeyHandler
        // 拿这个标记判断是"我们的预览"还是"prefab 原版预览" — 仅看
        // currentConstruction 不行, 因为它是上次编辑的自定义建筑, 即使后来玩家
        // 打开了 prefab 的 GuiStructure, 这个字段还残留着, 误判为我们的预览.
        LAST_ADDON_STRUCTURE_REF.set(structure);
        LAST_ADDON_PREVIEW.set(true);

        PrefabCustomAddon.LOGGER.info("[CUSTOM-GUI-V2] Preview: pack={} id={} pos={} facing={} blocks={}",
            currentConstruction.getPack() != null ? currentConstruction.getPack().getName() : "?",
            currentConstruction.getId(), cfg.pos, cfg.houseFacing,
            structure.getBlocks().size());

        // 7) 关闭当前 GUI
        Minecraft.getInstance().setScreen(null);
    }

    /** 刷新中间按钮的 active 状态 + 文本 */
    private static void updateMainButtonState() {
        if (btnMain == null) return;
        if (parseFailed || !parseComplete) {
            btnMain.setActive(false);
            return;
        }
        if (challengeMode) {
            // 挑战模式: 材料未交齐时按钮显示 "提交材料", 交齐后变成 "预览".
            // 之前逻辑是 checkMaterialsReady() 才 enable, 永远点不了.
            // 现在: 永远可点, 文本根据材料是否交齐动态切换.
            if (checkMaterialsReady()) {
                btnMain.setText("预览");
            } else {
                btnMain.setText("提交材料");
            }
            btnMain.setActive(true);
        } else {
            btnMain.setText("预览");
            btnMain.setActive(true);
        }
    }

    /** 实际执行 预览/建造: 走网络包, 跟 CustomStructureGui.performBuildClick 一样 */
    private static void performBuildOrPreview() {
        if (currentConstruction == null) return;

        // 多人模式下, 服务器没有这个拓展包 → 阻止
        if (!com.prefab.addon.extension.ExtensionPackManager.isBuildable(currentConstruction)) {
            showStatus("✗ 服务器没有这个拓展包, 无法建造!", 0xFF5555, 200);
            return;
        }
        // 挑战模式 + 未提交材料 → 阻止
        if (challengeMode && !checkMaterialsReady()) {
            showStatus("⚠ 挑战模式: 请先提交全部材料", 0xFFAA00, 100);
            return;
        }
        // 背包里没蓝图 → 阻止
        if (!hasBlueprintInInventory()) {
            showStatus("⚠ 背包里没有 自定义蓝图 物品!", 0xFF5555, 100);
            return;
        }

        String packName = currentConstruction.getPack() != null
            ? currentConstruction.getPack().getName() : "";
        net.minecraft.core.BlockPos buildPos = currentOpenPos != null
            ? currentOpenPos : net.minecraft.core.BlockPos.ZERO;

        // **关键**: 在建造前, 自动把当前 Construction 绑定到玩家背包里第一张未锁定的蓝图.
        // 之前从来没自动绑过, 导致服务端 consumeBlueprint 时找不到匹配的 blueprint
        // (蓝图的 packName/constructionId 字段都是空的或者绑到了别的建筑).
        com.prefab.addon.network.NetworkHandler.sendToServer(
            new com.prefab.addon.network.BindConstructionPayload(
                packName, currentConstruction.getId(), false));

        PrefabCustomAddon.LOGGER.info("[CUSTOM-GUI-V2] Build: pack={} id={} pos={} challengeMode={}",
            packName, currentConstruction.getId(), buildPos, challengeMode);
        com.prefab.addon.network.NetworkHandler.sendToServer(
            new com.prefab.addon.network.BuildCustomStructurePayload(
                buildPos, packName, currentConstruction.getId()));
        if (renderScene != null) renderScene.releaseRendererResource();
        Minecraft.getInstance().setScreen(null);
    }

    /**
     * 暴露给 {@link com.prefab.addon.client.StructurePreviewKeyHandler} 用:
     * ALT 建造时需要拿到跟 GUI 建造按钮**完全一致**的 packName (currentConstruction.getPack().getName()),
     * 不要用蓝图里存的 packName (旧版本是用 getPackageName() 绑定的, 会跟服务端不一致).
     */
    public static String getPackNameForBuild() {
        return currentConstruction != null && currentConstruction.getPack() != null
            ? currentConstruction.getPack().getName() : "";
    }

    public static String getConstructionIdForBuild() {
        return currentConstruction != null ? currentConstruction.getId() : "";
    }

    /**
     * 当前世界中预览的 {@link com.prefab.structures.base.Structure} 是不是由**我们的 GUI** 启动的.
     * <p>实现: 比较 prefab 公共静态字段
     * {@link com.prefab.structures.render.StructureRenderHandler#currentStructure} 是不是
     * 我们上次 {@code setStructure} 时记下的引用. 是 → 我们的预览, 否 → prefab 自己启动的.</p>
     *
     * <p>为什么不用 {@link #getPackNameForBuild()}: 那个读 {@link #currentConstruction},
     * 它是上次编辑的自定义建筑缓存, 即使后来玩家打开了 prefab 的 {@code GuiStructure} 预览原版建筑,
     * 这个字段还残留, 会误判为我们的预览 → 挑战模式被错触发, 移动/旋转走的是错误的 rebuild 路径.</p>
     */
    public static boolean isCurrentPreviewStartedByAddon() {
        com.prefab.structures.base.Structure prefabCurrent =
            com.prefab.structures.render.StructureRenderHandler.currentStructure;
        if (prefabCurrent == null) return false;
        if (!LAST_ADDON_PREVIEW.get()) return false;
        com.prefab.structures.base.Structure ref = LAST_ADDON_STRUCTURE_REF.get();
        return ref == prefabCurrent;
    }

    /**
     * 预览结束时清掉标记. 供 {@link com.prefab.addon.client.StructurePreviewKeyHandler}
     * 在 tick 检测到 prefab.currentStructure == null 时调用.
     */
    public static void clearAddonPreviewFlag() {
        LAST_ADDON_PREVIEW.set(false);
        LAST_ADDON_STRUCTURE_REF.set(null);
    }

    private static boolean hasBlueprintInInventory() {
        net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return false;
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() instanceof com.prefab.addon.items.CustomBlueprintItem) {
                return true;
            }
        }
        return false;
    }

    private static boolean checkMaterialsReady() {
        if (materialList == null || materialList.required.isEmpty()) return true;
        net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return false;
        return com.prefab.addon.work.ChallengeSessionManager.isReady(
            player.getUUID(), currentConstruction.getId(), materialList.required);
    }

    private static void showStatus(String msg, int color, int ticks) {
        statusMsg = msg;
        statusColor = color;
        statusTick = ticks;
    }
}
