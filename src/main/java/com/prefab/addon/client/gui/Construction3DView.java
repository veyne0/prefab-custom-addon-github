package com.prefab.addon.client.gui;

import com.lowdragmc.lowdraglib2.gui.ui.elements.Scene;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.utils.data.BlockInfo;
import com.lowdragmc.lowdraglib2.utils.virtuallevel.TrackedDummyWorld;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.ConstructionInfo;
import com.prefab.addon.structure.CustomStructureBuilder;
import com.prefab.addon.structure.CustomStructureBuilder.BlockData;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 建筑 3D 预览的渲染状态机 —— 抽离自 {@link GuiConstructionDetail},
 * 让 {@link GuiOutsourceBuildingDetail} 能复用异步解析 + 增量 addBlocks + Scene 编译,
 * <b>不直接访问</b> {@code GuiConstructionDetail} 的私有字段。
 *
 * <p>职责:从 {@code ConstructionInfo.getNbtData()} 解析 → 拿到 {@code List&lt;BlockData&gt;}
 * → 塞进 {@code TrackedDummyWorld} → 渲染到 {@code Scene}。所有状态是 static 的(单实例)。</p>
 *
 * <p>调用方负责持有 {@code TextElement progressEl} 和 {@code UIElement sceneContainer} 引用,
 * 在每个 client tick 调一次 {@link #runTick(TextElement, com.lowdragmc.lowdraglib2.gui.ui.UIElement, TextElement)},
 * helper 会自动:检查 parse → 初始化 Scene → 增量 addBlocks → 更新进度文本。</p>
 */
public final class Construction3DView {

    // 防止实例化
    private Construction3DView() {}

    // === 异步 + 增量渲染常量 (从 GuiConstructionDetail 抄过来,保持一致) ===
    private static final int BATCH_PERCENT = 10;
    private static final int MAX_BATCH_SIZE = 1500;
    private static final int PROGRESS_UPDATE_TICKS = 5;

    // === 异步解析状态 ===
    private static ConstructionInfo currentConstruction;
    private static CompletableFuture<List<BlockData>> parseFuture;
    private static volatile List<BlockData> parseResult;
    private static volatile boolean parseComplete;
    private static volatile boolean parseFailed;
    private static final Object parseLock = new Object();

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

    // 注: 之前的 flipY 字段 (用于在 3D 渲染层翻转 Y) 已删除. Y 翻转现在在数据层做
    //   (OutsourceBuilding.shouldFlipY → LitematicaParser.convertToStandardStructure(root, forceFlipY)),
    //   预览 + 实际建造走同一份已翻转的 NBT, 永远一致, 渲染层不需要额外处理.

    // === tick 节流 ===
    private static int progressTickCounter;

    // === 状态消息 ===
    private static int statusTick = 0;
    private static String statusMsg = null;
    private static int statusColor = 0x55FF55;

    // ============================================================
    // 公共 API
    // ============================================================

    /**
     * 重置 3D 状态,准备解析新建筑。
     * 释放旧 Scene 资源,清空缓存,设新 currentConstruction。
     */
    public static void reset(ConstructionInfo newConstruction) {
        if (renderScene != null) {
            try { renderScene.releaseRendererResource(); } catch (Throwable ignored) {}
        }
        synchronized (parseLock) {
            currentConstruction = newConstruction;
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
        }
        statusTick = 0;
        statusMsg = null;
        progressTickCounter = 0;
    }

    /**
     * 启动后台 NBT 解析(parse 完后由 {@link #runTick} 消费)。
     */
    public static void startAsyncParse(ConstructionInfo construction) {
        if (construction == null) return;
        final String name = construction.getName();
        synchronized (parseLock) {
            parseFuture = CompletableFuture.supplyAsync(() -> {
                long t0 = System.currentTimeMillis();
                try {
                    byte[] nbtData = construction.getNbtData();
                    if (nbtData == null || nbtData.length == 0) {
                        PrefabCustomAddon.LOGGER.warn("[3DVIEW] No NBT data for '{}'", name);
                        return null;
                    }
                    CustomStructureBuilder.getInstance().loadStructureFromNbt(construction);
                    List<BlockData> blocks = CustomStructureBuilder.getInstance().parseStructureBlocks();
                    long ms = System.currentTimeMillis() - t0;
                    PrefabCustomAddon.LOGGER.info("[3DVIEW] parse '{}' → {} blocks ({} ms)",
                        name, blocks != null ? blocks.size() : 0, ms);
                    return blocks;
                } catch (Throwable t) {
                    PrefabCustomAddon.LOGGER.error("[3DVIEW] parse failed for '{}'", name, t);
                    return null;
                }
            });
        }
    }

    /**
     * 当前构造(只读)。
     */
    public static ConstructionInfo getCurrentConstruction() { return currentConstruction; }

    /**
     * 临时显示一条状态文字(覆盖进度条 N 个 tick,后恢复)。给 [选择] 按钮反馈用。
     */
    public static void showStatus(String msg, int color, int ticks) {
        statusMsg = msg;
        statusColor = color;
        statusTick = ticks;
    }

    /**
     * 主线程每 tick 调一次。内部完成:检查 parse → 初始化 Scene → 增量 addBlocks → 终态更新。
     *
     * @param progressEl       显示"解析 NBT 中... 50%"等
     * @param sceneContainer   持有 Scene 的容器
     * @param scenePlaceholder Scene 还没建好时显示的占位文字
     */
    public static void runTick(TextElement progressEl,
                               com.lowdragmc.lowdraglib2.gui.ui.UIElement sceneContainer,
                               TextElement scenePlaceholder) {
        if (progressEl == null || sceneContainer == null) return;

        // 5a) parse 没完成 → 检查一次
        if (!parseComplete && !parseFailed) {
            checkParse();
        }

        // 5b) parse 完成 + render 未初始化 → init
        if (parseComplete && !parseFailed && renderScene == null && !renderActive && !renderDone) {
            initRender();
            if (renderScene != null) {
                sceneContainer.clearAllChildren();
                renderScene.layout(l -> l.widthPercent(100).heightPercent(100));
                sceneContainer.addChild(renderScene);
                progressEl.setText(PrefabCustomAddon.tr("gui.outsource.3d_loading"));
            } else if (renderDone) {
                if (scenePlaceholder != null) {
                    scenePlaceholder.setText(PrefabCustomAddon.tr("gui.outsource.3d_unavailable"));
                }
            }
        }

        // 5c) render 进行中
        if (renderActive) {
            tickRender();
            progressTickCounter++;
            if (progressTickCounter >= PROGRESS_UPDATE_TICKS) {
                progressTickCounter = 0;
                progressEl.setText(PrefabCustomAddon.tr("gui.outsource.3d_render_pct", getRenderProgress()));
            }
        }

        // 5d) 终态
        if (renderDone) {
            if (renderScene != null) {
                progressEl.setText(PrefabCustomAddon.tr("gui.outsource.3d_done", renderTotalBlocks));
            } else {
                if (parseFailed) {
                    progressEl.setText(PrefabCustomAddon.tr("gui.outsource.3d_parse_fail"));
                } else if (parseResult != null && parseResult.isEmpty()) {
                    progressEl.setText(PrefabCustomAddon.tr("gui.outsource.3d_no_blocks"));
                } else {
                    progressEl.setText(PrefabCustomAddon.tr("gui.outsource.3d_render_fail"));
                }
            }
        } else if (parseFailed) {
            progressEl.setText(PrefabCustomAddon.tr("gui.outsource.3d_parse_fail"));
            renderDone = true;
        } else if (parseComplete && parseResult != null && parseResult.isEmpty()) {
            progressEl.setText(PrefabCustomAddon.tr("gui.outsource.3d_no_blocks"));
            renderDone = true;
        }

        // 5e) 状态栏倒计时
        if (statusTick > 0 && statusMsg != null) {
            progressEl.setText(statusMsg);
            progressEl.textStyle(t -> t.textColor(statusColor));
            statusTick--;
            if (statusTick <= 0) {
                statusMsg = null;
                if (renderDone && renderScene != null) {
                    progressEl.setText(PrefabCustomAddon.tr("gui.outsource.3d_done", renderTotalBlocks));
                }
            }
        }
    }

    /**
     * 释放资源(GUI 关闭时调,防止泄漏)。
     */
    public static void release() {
        if (renderScene != null) {
            try { renderScene.releaseRendererResource(); } catch (Throwable ignored) {}
            renderScene = null;
        }
    }

    // ============================================================
    // 内部实现
    // ============================================================

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
                PrefabCustomAddon.LOGGER.error("[3DVIEW] parse future get failed", e);
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
            renderWorld = new TrackedDummyWorld();
            renderBlocks = parseResult;
            renderTotalBlocks = renderBlocks.size();
            renderBatchSize = Math.min(MAX_BATCH_SIZE,
                Math.max(1, renderTotalBlocks * BATCH_PERCENT / 100));
            renderNextIndex = 0;

            // 过滤 air + null
            List<BlockData> nonAir = new ArrayList<>();
            for (BlockData bd : renderBlocks) {
                if (bd == null || bd.pos == null || bd.state == null) continue;
                if (bd.state.isAir()) continue;
                nonAir.add(bd);
            }
            if (nonAir.isEmpty()) {
                PrefabCustomAddon.LOGGER.warn("[3DVIEW] all {} blocks are air, skip render", renderTotalBlocks);
                renderDone = true;
                return;
            }

            // 构建 setRenderedCore 用的 positions 列表 (用原始 BlockPos, Y 翻转在数据层完成)
            List<BlockData> renderList = nonAir;
            List<BlockPos> positions = new ArrayList<>(renderList.size());
            for (BlockData bd : renderList) {
                positions.add(bd.pos);
            }

            PrefabCustomAddon.LOGGER.info("[3DVIEW] initRender: {} total / {} non-air",
                renderTotalBlocks, nonAir.size());

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
            PrefabCustomAddon.LOGGER.error("[3DVIEW] initRender failed", t);
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
            // Y 翻转在数据层完成 (LitematicaParser forceFlipY), 这里直接用原始 BlockPos.
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
}
