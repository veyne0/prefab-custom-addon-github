package com.prefab.addon.client.gui;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Scene;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.lowdragmc.lowdraglib2.utils.data.BlockInfo;
import com.lowdragmc.lowdraglib2.utils.virtuallevel.TrackedDummyWorld;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.client.gui.GuiExtensionPackBrowser;
import com.prefab.addon.config.PlayerPreferences;
import com.prefab.addon.extension.ConstructionInfo;
import com.prefab.addon.extension.ExtensionPackManager;
import com.prefab.addon.structure.AsyncBuildManager;
import com.prefab.addon.structure.CustomStructureBuilder;
import com.prefab.addon.structure.CustomStructureBuilder.BlockData;
import com.prefab.addon.work.DependencyChecker;
import com.prefab.addon.work.DependencyChecker.CheckResult;
import com.prefab.addon.work.MaterialCalculator;
import com.prefab.addon.work.NbtStructureParser;
import com.wuest.prefab.structures.base.Structure;
import com.wuest.prefab.structures.config.StructureConfiguration;
import com.wuest.prefab.structures.render.StructureRenderHandler;

import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import com.lowdragmc.lowdraglib2.math.Size;
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
    /**
     * 玩家右键打开预览的蓝图 ItemStack (= 真正触发预览的 stack). 公开访问是为了让
     * {@link com.prefab.addon.client.StructurePreviewKeyHandler#triggerBuildAtPreview} 在
     * ALT 建造时能拿到"正确的" stack 来判断免材料豁免 (KubeJS 联动蓝图), 而不是从背包里
     * 扫描"第一个"蓝图 (那个是错的: 在 KubeJS 蓝图 slot 5 + 自定义蓝图 slot 3 共存时,
     * 第一个永远是 slot 3 的 CustomBlueprintItem, 会被错认成自定义蓝图路径, 误收材料).
     */
    public static ItemStack currentBlueprint;
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
    private static final java.util.concurrent.atomic.AtomicReference<com.wuest.prefab.structures.base.Structure>
        LAST_ADDON_STRUCTURE_REF = new java.util.concurrent.atomic.AtomicReference<>(null);
    private static final java.util.concurrent.atomic.AtomicBoolean
        LAST_ADDON_PREVIEW = new java.util.concurrent.atomic.AtomicBoolean(false);

    // === 当前正在预览的 structure + config (我们 own, 不依赖 prefab 的静态字段) ===
    // 关键: 之前 CustomStructurePreviewRenderer 和 StructurePreviewKeyHandler 都从
    //   StructureRenderHandler.currentStructure / currentConfiguration 读,
    //   但 prefab 自己的 RenderIndicatorMixin 注入的 renderStructurePreview **无条件**
    //   画 currentStructure (没有"是不是我们 addon 启动的"判断), setStructure 之后 prefab
    //   也会画一份 → 出现"两个预览".
    // 解决: 我们的 Renderer / KeyHandler 读 ADDON_PREVIEW_STRUCTURE / ADDON_PREVIEW_CONFIG,
    //   handlePreviewButtonClick 在 setStructure(structure, cfg) 之后立即 setStructure(null, null)
    //   把 prefab 的 currentStructure 清掉, prefab 的 renderer 看到 null 就 return, 永远不画 →
    //   只有我们的 renderer 在画, 1 份预览, 永远不会有"两个".
    //   ALT 建造时, 我们再 setStructure(null, null) 一次 (清掉我们的) + close GUI 完成.
    private static volatile com.wuest.prefab.structures.base.Structure ADDON_PREVIEW_STRUCTURE = null;
    private static volatile com.wuest.prefab.structures.config.StructureConfiguration ADDON_PREVIEW_CONFIG = null;

    // === 外包建筑预览上下文 (从 1.21.1 移植) ===
    // GuiOutsourceBuildingDetail.onPreview 启动预览时把 buildingId/styleIndex 存这里,
    // ALT 建造时 StructurePreviewKeyHandler 读 isCurrentOutsource() 决定走哪条 build 路径.
    // 跟 currentConstruction (普通自定义建筑) 互斥: 不会同时 setCurrentConstruction + setOutsourceContext.
    private static volatile boolean currentIsOutsource = false;
    private static volatile String currentOutsourceBuildingId = null;
    private static volatile int currentOutsourceStyleIndex = 0;

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
    /** 侧边栏"尺寸"值行引用: parse 完成后回填精确尺寸 (blueprint 建筑无 .txt 元信息), 用完置 null */
    private static TextElement sizeValueEl;
    private static Button btnMain;  // 预览/建造 或 提交材料(挑战模式)

    // === 状态消息 (例如 "提交材料后才能建造" 等提示) ===
    private static int statusTick = 0;
    private static String statusMsg = null;
    private static int statusColor = 0x55FF55;

    /** 从建筑终端打开 (openFromTerminal): 关闭 GUI 时自动重开终端而非返回浏览器 */
    private static boolean fromTerminal = false;

    // === 蓝图广场入口标记 (从 1.21.1 移植) ===
    // 从蓝图广场 (GuiBlueprintMarket) 点已下载建筑进来时 = true:
    //   跟终端入口一样是"无蓝图建造" — ALT 建造跳过背包自定义蓝图检查/绑定
    //   (下载的建筑和自定义蓝图物品无关, 不该要求玩家拿着蓝图).
    // 与 fromTerminal 分开: 关闭行为不同 — 终端要重开终端, 广场直接关屏回世界;
    // 走 closeScreen 关闭时重开蓝图广场 (浏览位置/分类/搜索都保留).
    private static boolean noBlueprintEntry = false;

    /** 关闭 GUI 时返回上一级的回调 (浏览器蓝图模式进入时传入); null = 默认关闭到游戏. */
    private static Runnable onBackCallback = null;

    // === 依赖检测结果 (open 时自动跑一次, 侧边栏依赖行按结果显示 ✓/✗) ===
    private static boolean depChecked = false;
    private static List<String> depMissingIds = null;

    private CustomStructureGui() {}

    /**
     * 是否消耗材料: 生存/冒险固定需要, 创造免费 (取代旧的"挑战模式"全局开关).
     */
    private static boolean shouldConsumeMaterials() {
        net.minecraft.client.player.LocalPlayer p = Minecraft.getInstance().player;
        return p == null || !p.isCreative();
    }

    /**
     * 打开自定义建筑选择界面.
     *
     * @param construction 当前建筑
     * @param blueprint 主手蓝图 (用于锁定检测, 可为 null)
     * @param openPos 玩家右键蓝图时的位置 (用于构建位置)
     */
    public static void open(ConstructionInfo construction, ItemStack blueprint, BlockPos openPos) {
        open(construction, blueprint, openPos, null);
    }

    /**
     * 打开自定义建筑选择界面 (带返回回调).
     *
     * @param onBack 关闭 GUI 时的返回回调 (例如浏览器蓝图模式传"重开浏览器"); null = 直接退出到游戏
     */
    public static void open(ConstructionInfo construction, ItemStack blueprint, BlockPos openPos,
                            Runnable onBack) {
        resetState();
        onBackCallback = onBack;
        currentConstruction = construction;
        currentBlueprint = blueprint;
        currentOpenPos = openPos == null ? BlockPos.ZERO : openPos;
        challengeMode = shouldConsumeMaterials();
        // KubeJS 联动蓝图豁免: 玩家合成的蓝图 (配方本身已有成本) 建造不再收材料,
        // 生存/冒险也直接建造 (材料规则只对建筑终端/原生自定义蓝图生效)
        if (challengeMode && blueprint != null && !blueprint.isEmpty()
                && AsyncBuildManager.isKubeJSPlayerBlueprint(blueprint)) {
            challengeMode = false;
        }

        PrefabCustomAddon.LOGGER.info("[CUSTOM-GUI-V2] open: construction='{}' id='{}' challengeMode={}",
            construction.getName(), construction.getId(), challengeMode);

        runDepCheckSilently(construction);
        startAsyncParse(construction);
        ModularUI ui = createUI(construction);
        Minecraft.getInstance().setScreen(
            new ModularUIScreen(ui, Component.literal(construction.getName())));
    }

    /**
     * 从建筑终端打开指定建筑 (1.21.1 对齐).
     * 与 {@link #open} 的区别: 不携带蓝图, 关闭时自动重开终端, 打开前自动跑依赖检测.
     */
    public static void openFromTerminal(ConstructionInfo construction, BlockPos openPos) {
        resetState();
        fromTerminal = true;
        currentConstruction = construction;
        currentBlueprint = null;
        currentOpenPos = openPos == null ? BlockPos.ZERO : openPos;
        challengeMode = shouldConsumeMaterials();

        PrefabCustomAddon.LOGGER.info("[CUSTOM-GUI-V2] openFromTerminal: construction='{}' id='{}' challengeMode={}",
            construction.getName(), construction.getId(), challengeMode);

        runDepCheckSilently(construction);
        startAsyncParse(construction);
        ModularUI ui = createUI(construction);
        runAutoDepCheck(construction);
        Minecraft.getInstance().setScreen(
            new ModularUIScreen(ui, Component.literal(construction.getName())));
    }

    /**
     * 从蓝图广场 (GuiBlueprintMarket) 点已下载建筑直跳打开 (1.21.1 对齐).
     * 无蓝图建造模式: ALT 建造不要求/不绑定自定义蓝图 (同终端入口),
     * 关闭时直接回游戏或重开蓝图广场.
     */
    public static void openFromMarket(ConstructionInfo construction, BlockPos openPos) {
        open(construction, null, openPos);
        // open() 内部 resetState() 会清标记, 这里在 setScreen 之前补上
        noBlueprintEntry = true;
    }

    /** 打开时自动跑一次依赖检测, 结果供侧边栏依赖行着色 (✗ 缺失红 / ✓ 已装绿), 不弹提示. */
    private static void runDepCheckSilently(ConstructionInfo c) {
        depChecked = false;
        depMissingIds = null;
        List<String> deps = c.getDependencies();
        if (deps == null || deps.isEmpty()) {
            depChecked = true;
            return;
        }
        try {
            CheckResult result = DependencyChecker.check(deps);
            if (result != null && result.missing != null) {
                depMissingIds = new ArrayList<>();
                for (String m : result.missing) {
                    // 防御: missing 元素理论上已是干净 modid, 这里仍截断冒号兜底
                    String clean = m;
                    int colon = clean.indexOf(':');
                    if (colon > 0) clean = clean.substring(0, colon);
                    depMissingIds.add(clean);
                }
            }
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[CUSTOM-GUI-V2] dep check failed (non-fatal)", t);
        }
        depChecked = true;
    }

    /** 打开时自动跑一次依赖检测, 结果显示在状态栏 (1.21.1 对齐). */
    private static void runAutoDepCheck(ConstructionInfo c) {
        List<String> deps = c.getDependencies();
        DependencyChecker.CheckResult result = GuiConstructionDetail.checkDepsAndUpdate(c);
        if (result.missing.isEmpty()) {
            showStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.dep_check_pass",
                result.present.size(), (deps == null ? 0 : deps.size())), 0x55FF55, 200);
        } else {
            showStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.dep_check_done"), 0xFFAA55, 200);
        }
    }

    /** 统一关闭入口: 优先走返回回调 (浏览器蓝图模式返回浏览器), fromTerminal 时自动重开建筑终端. */
    private static void closeScreen() {
        PrefabCustomAddon.LOGGER.info("[AUI-DBG] custom-gui closeScreen onBack={} fromTerminal={}",
            onBackCallback != null, fromTerminal);
        Minecraft.getInstance().setScreen(null);
        Runnable back = onBackCallback;
        onBackCallback = null;
        if (back != null) {
            back.run();
            return;
        }
        if (fromTerminal) {
            fromTerminal = false;
            com.prefab.addon.terminal.client.gui.TerminalGui.selectBuildingsOnReopen();
            com.prefab.addon.terminal.network.TerminalPayloads.sendReopenTerminal();
        } else if (noBlueprintEntry) {
            noBlueprintEntry = false;
            // 从蓝图广场打开时重开蓝图广场 (浏览位置/分类/搜索都保留)
            GuiBlueprintMarket.open();
        }
    }

    public static boolean isFromTerminal() {
        return fromTerminal;
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
            sizeValueEl = null;
        }
        statusTick = 0;
        statusMsg = null;
        challengeMode = false;
        fromTerminal = false;
        noBlueprintEntry = false;
        depChecked = false;
        depMissingIds = null;
    }

    private static void startAsyncParse(ConstructionInfo construction) {
        final String name = construction.getName();
        // 本地单文件建筑 (LocalBuilding) 的 getPack() 为 null, 用 STANDALONE_PACKAGE 兜底
        final String packId = construction.getPack() == null
            ? ExtensionPackManager.STANDALONE_PACKAGE
            : construction.getPack().getPackageName();
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
                        // 兜底: calculate 只认 vanilla palette+blocks 格式, litematic/schem
                        // 会得到空 required (生存模式免材料 bug). 用解析好的方块列表重算.
                        if ((materialList == null || materialList.required.isEmpty())
                                && blocks != null && !blocks.isEmpty()) {
                            materialList = MaterialCalculator.fromBlocks(blocks);
                        }
                        nbtInfo = NbtStructureParser.parse(nbtData);
                    } catch (Throwable t) {
                        // 计算异常也兜底 (materialList=null 同样会免材料)
                        if (blocks != null && !blocks.isEmpty()) {
                            materialList = MaterialCalculator.fromBlocks(blocks);
                        }
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

    private static void initRender(int panelW, int panelH) {
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

            // FBO 渲染器: Immediate 渲染器的 unProject/glReadPixels 用 GUI 顶部原点 y,
            // 而 GL 是底部原点 - trace 反投影会镜像错位 (tooltip/选中乱跳);
            // FBOWorldSceneRenderer.drawScene 内部做了 (1 - (mouseY-y)/height) 翻转修正.
            // fboSize 按面板尺寸 x GUI 缩放传入, 保证宽高比一致 (不拉伸) 且清晰.
            var mc = Minecraft.getInstance();
            int scale = (int) mc.getWindow().getGuiScale();
            int fboW = Math.min(2048, Math.max(16, panelW * Math.max(1, scale)));
            int fboH = Math.min(2048, Math.max(16, panelH * Math.max(1, scale)));
            renderScene.createScene(renderWorld, true, Size.of(fboW, fboH));
            renderScene.setRenderedCore(positions, null, true);
            // 悬停方块显示原生物品 tooltip (Scene.showHoverBlockTips, 不依赖 JEI);
            // xeiLookup 另提供 JEI/REI/EMI 查询集成
            renderScene.setShowHoverBlockTips(true);
            renderScene.xeiLookup();

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

        // === 中间: 左信息侧边栏 + 3D 预览容器 (排版对齐 GuiConstructionDetail) ===
        UIElement sceneContainer = new UIElement();
        sceneContainer.layout(l -> l
            .flexGrow(1).flexShrink(1)
            .heightPercent(100)
            .minHeight(0).minWidth(0)
        );
        sceneContainer.style(s -> s.background(Sprites.RECT_DARK));
        sceneContainer.setOverflowVisible(false);

        // 初始 placeholder
        final TextElement scenePlaceholder = new TextElement();
        scenePlaceholder.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.load_3d"));
        scenePlaceholder.textStyle(t -> t
            .textAlignHorizontal(Horizontal.CENTER)
            .textColor(0xAAAAAA)
            .textWrap(TextWrap.WRAP));
        scenePlaceholder.layout(l -> l.widthPercent(100).heightPercent(100)
            .justifyContent(AlignContent.CENTER));
        sceneContainer.addChild(scenePlaceholder);

        // === 状态/完成消息行: 从底部移入侧边栏顶部 (解析中 → 渲染 N% → ✓ 名称 (N 块) / 错误),
        // 和 GuiConstructionDetail 的 progressEl 同一模式, 底部不再重复显示 ===
        statusEl = new TextElement();
        statusEl.setText("");
        statusEl.textStyle(t -> t
            .textAlignHorizontal(Horizontal.CENTER)
            .textColor(0xAAAAAA));
        statusEl.layout(l -> l.widthPercent(100).height(12));

        // === Body 行: 左侧信息侧边栏 (140px, 同详情页) + 右侧 3D 预览 (flexGrow=1 收窄让位) ===
        UIElement bodyRow = new UIElement();
        bodyRow.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .widthPercent(100).flexGrow(1).flexShrink(1)
            .gapAll(2)
            .minHeight(0).minWidth(0)
        );
        bodyRow.setOverflowVisible(false);

        ScrollerView infoSidebar = createInfoSidebar(construction);
        infoSidebar.layout(l -> l.width(140).heightPercent(100).minHeight(0));
        bodyRow.addChild(infoSidebar);

        bodyRow.addChild(sceneContainer);
        root.addChild(bodyRow);

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

        // 取消按钮 — 先关屏再释放资源, 避免 releaseRendererResource 抛异常卡住 setScreen
        Button btnCancel = new Button().setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.close"));
        btnCancel.setOnClick(e -> {
            try {
                if (renderScene != null) {
                    var scene = renderScene;
                    renderScene = null;  // 先置 null 防止 closeGui 二次释放
                    try { scene.releaseRendererResource(); } catch (Throwable ignored) {}
                }
            } finally {
                closeScreen();
            }
        });
        btnCancel.layout(l -> l.flexGrow(1).heightPercent(100));
        buttonRow.addChild(btnCancel);

        if (challengeMode) {
            // 挑战模式: 单个 "提交材料" 按钮
            btnMain = new Button().setText(com.prefab.addon.PrefabCustomAddon.tr("gui.custom.submit_materials"));
            btnMain.setOnClick(e -> handleMainButtonClick());
            btnMain.layout(l -> l.flexGrow(2).heightPercent(100));
            buttonRow.addChild(btnMain);
        } else {
            // 普通模式: 只剩 "预览" 按钮 (建造用预览界面里的 ALT 完成)
            Button btnPreview = new Button().setText(com.prefab.addon.PrefabCustomAddon.tr("gui.custom.preview"));
            btnPreview.setOnClick(e -> handlePreviewButtonClick());
            btnPreview.layout(l -> l.flexGrow(2).heightPercent(100));
            buttonRow.addChild(btnPreview);
        }

        // 只对 KubeJS 蓝图隐藏缩略图/更换建筑按钮 (对齐 1.21.1 的 [关闭][预览] 布局);
        // 自定义蓝图右键、终端入口保持原有按钮不动.
        // 注意: isHandledBlueprint 对原生 CustomBlueprintItem 也返回 true (语义是"拦截右键"),
        // 这里必须再排除原生, 只认 KubeJS 注册的带 player_blueprint tag 的物品.
        boolean isKubeBlueprint = currentBlueprint != null && !currentBlueprint.isEmpty()
                && !(currentBlueprint.getItem() instanceof com.prefab.addon.items.CustomBlueprintItem)
                && com.prefab.addon.client.CustomBlueprintClientHandler.isHandledBlueprint(currentBlueprint);

        if (!isKubeBlueprint) {
        // 缩略图按钮: 把右侧 3D 预览截屏存成建筑缩略图 (浏览器卡片/终端用)
        // 无图="生成缩略图" / 已有缓存="更新缩略图" / 建筑自带原图="已带原图"(禁用)
        // 对齐 GuiConstructionDetail 的同名按钮, 自定义蓝图 + 终端两个入口都能用
        Button btnThumb = new Button();
        if (construction.hasPreviewImage()) {
            btnThumb.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.has_thumb"));
            btnThumb.setActive(false);
        } else {
            btnThumb.setText(com.prefab.addon.PrefabCustomAddon.tr(
                com.prefab.addon.client.ThumbnailCache.hasCached(construction)
                    ? "gui.detail.update_thumb" : "gui.detail.generate_thumb"));
            btnThumb.setOnClick(e -> {
                captureCurrentSceneToThumbnail();
                // 生成成功后把按钮文字切成 "更新缩略图"
                if (currentConstruction != null && !currentConstruction.hasPreviewImage()
                    && com.prefab.addon.client.ThumbnailCache.hasCached(currentConstruction)) {
                    btnThumb.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.update_thumb"));
                }
            });
        }
        btnThumb.layout(l -> l.flexGrow(1).heightPercent(100));
        buttonRow.addChild(btnThumb);
        } // end if (!isKubeBlueprint)

        // 更换建筑按钮
        // 需求: 跳到建筑浏览器 (第1张图, 左侧 tab 栏 + 建筑卡片网格),
        //       而不是直接进入某个建筑的详情 (之前: GuiConstructionDetail.openFirstAvailable()).
        // 流程: 关闭当前 CustomStructureGui, 释放 3D 渲染资源, 打开建筑浏览器.
        // 从建筑终端打开时不显示"更换建筑"按钮 (对齐 1.21.1: 关闭后自动重开终端)
        // KubeJS 蓝图也不显示 (对齐 1.21.1 的 KubeJS 蓝图右键布局)
        if (!fromTerminal && !isKubeBlueprint) {
        Button btnChange = new Button().setText(com.prefab.addon.PrefabCustomAddon.tr("gui.custom.change"));
        btnChange.setOnClick(e -> {
            if (renderScene != null) renderScene.releaseRendererResource();
            // 之前: GuiConstructionDetail.openFirstAvailable();  // 详情层 (地狱门那个 3D 预览)
            // 现在: GuiExtensionPackBrowser.open();               // 建筑浏览器 (第1张图, 卡片网格 + tab)
            GuiExtensionPackBrowser.open();
        });
        btnChange.layout(l -> l.flexGrow(1).heightPercent(100));
        // 蓝图锁定时变灰
        // 兼容两种来源:
        //   - mod 原生 CustomBlueprintItem: 读它的 isLocked(stack) (locked 字段)
        //   - KubeJS 注册的带 tag 物品: locked 取 NBT → .blueprint-specs 规格文件兜底
        //     (旧逻辑一律禁用; 现在生成器把 locked 写进规格文件, 按玩家填的值来)
        boolean isLocked = false;
        if (currentBlueprint != null && !currentBlueprint.isEmpty()) {
            if (currentBlueprint.getItem() instanceof com.prefab.addon.items.CustomBlueprintItem) {
                isLocked = com.prefab.addon.items.CustomBlueprintItem.isLocked(currentBlueprint);
            } else if (com.prefab.addon.client.CustomBlueprintClientHandler.isHandledBlueprint(currentBlueprint)) {
                isLocked = com.prefab.addon.integration.BlueprintSpecStore.resolveLocked(currentBlueprint);
            }
        }
        if (isLocked) {
            btnChange.setActive(false);
            btnChange.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.custom.locked"));
        }
        buttonRow.addChild(btnChange);
        } // end if (!fromTerminal && !isKubeBlueprint)

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

            // parse 完成后回填侧边栏"尺寸" (blueprint 建筑无 .txt 元信息, 建界面时只能显示"未知")
            if (parseComplete && parseResult != null && sizeValueEl != null) {
                int[] sz = computeSize(parseResult);
                if (sz != null) {
                    sizeValueEl.setText(sz[0] + "x" + sz[1] + "x" + sz[2]);
                }
                sizeValueEl = null;
            }

            // parse 完成后初始化 render
            if (parseComplete && !parseFailed && renderScene == null && !renderActive && !renderDone) {
                initRender((int) sceneContainer.getSizeWidth(), (int) sceneContainer.getSizeHeight());
                if (renderScene != null) {
                    sceneContainer.clearAllChildren();
                    renderScene.layout(l -> l.widthPercent(100).heightPercent(100));
                    sceneContainer.addChild(renderScene);
                } else if (renderDone) {
                    scenePlaceholder.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.3d_unavailable"));
                }
            }

            // 渲染中
            if (renderActive) {
                tickRender();
                if (tickNum % 5 == 0 && statusMsg == null) {
                    statusEl.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.render_pct", getRenderProgress()));
                    statusEl.textStyle(t -> t.textColor(0xFFFF55));
                }
            }

            // 渲染完成
            if (renderDone) {
                if (renderScene != null) {
                    if (statusMsg == null) {
                        statusEl.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.custom.build_done", construction.getName(), renderTotalBlocks));
                        statusEl.textStyle(t -> t.textColor(0x55FF55));
                    }
                } else if (parseFailed) {
                    if (statusMsg == null) {
                        statusEl.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.parse_fail"));
                        statusEl.textStyle(t -> t.textColor(0xFF5555));
                    }
                } else if (parseResult != null && parseResult.isEmpty()) {
                    scenePlaceholder.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.no_3d_content"));
                    scenePlaceholder.textStyle(t -> t.textColor(0xFFAA55)
                        .textAlignHorizontal(Horizontal.CENTER)
                        .textWrap(TextWrap.WRAP));
                    if (statusMsg == null) {
                        statusEl.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.no_blocks"));
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

        // Scene tooltip 依赖 ModularUI.player (getCloneItemStack 需要玩家), 客户端界面必须显式传入
        return ModularUI.of(UI.of(root,
            StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)),
            Minecraft.getInstance().player);
    }

    // === 左侧信息侧边栏 (排版/颜色对齐 GuiConstructionDetail.createInfoScroller, 只读无检测交互) ===
    private static ScrollerView createInfoSidebar(ConstructionInfo construction) {
        ScrollerView scroller = new ScrollerView();
        scroller.scrollerStyle(s -> s.mode(ScrollerMode.VERTICAL));
        scroller.verticalScroller(v -> v.setScrollBarSize(8f));

        UIElement content = new UIElement();
        content.layout(l -> l
            .flexDirection(FlexDirection.COLUMN)
            .gapAll(3)
            .paddingLeft(10)   // 给文字左侧更多空间, 防止 label 首字被裁
            .paddingRight(6)
            .paddingTop(4).paddingBottom(4)
        );
        content.style(s -> s.background(Sprites.RECT_DARK));

        // 完成状态/进度行 (原底部状态行, 已移入侧边栏顶部)
        content.addChild(statusEl);

        // 建筑名
        addField(content, com.prefab.addon.PrefabCustomAddon.tr("gui.detail.name"), construction.getName());
        // 作者
        String author = construction.getAuthor();
        addField(content, com.prefab.addon.PrefabCustomAddon.tr("gui.detail.author"),
            (author != null && !author.isEmpty()) ? author
                : com.prefab.addon.PrefabCustomAddon.tr("gui.detail.unknown"));
        // 尺寸: 优先解析结果 (通常 parse 还没完成 → 回退 .txt 的 "尺寸:" 字段 → "未知",
        // parse 完成后由 tick handler 经 sizeValueEl 回填精确值)
        int[] computedSize = computeSize(parseResult);
        String sizeStr;
        if (computedSize != null) {
            sizeStr = computedSize[0] + "x" + computedSize[1] + "x" + computedSize[2];
        } else if (construction.getSize() != null) {
            sizeStr = construction.getSize();
        } else {
            sizeStr = com.prefab.addon.PrefabCustomAddon.tr("gui.detail.unknown");
        }
        addFieldLabel(content, com.prefab.addon.PrefabCustomAddon.tr("gui.detail.size"));
        sizeValueEl = new TextElement();
        sizeValueEl.setText(sizeStr);
        sizeValueEl.textStyle(t -> t.textColor(0xFFFFFF));
        sizeValueEl.layout(l -> l.widthPercent(100).height(12));
        content.addChild(sizeValueEl);

        // 蓝图格式
        addField(content, com.prefab.addon.PrefabCustomAddon.tr("gui.detail.format"),
            construction.getFormatDisplay());

        // 依赖 mod (open 时已自动检测, 按结果显示 ✗ 缺失红 / ✓ 已装绿)
        List<String> deps = construction.getDependencies();
        addFieldLabel(content, com.prefab.addon.PrefabCustomAddon.tr("gui.detail.dep_label",
            deps == null ? 0 : deps.size()));
        if (deps == null || deps.isEmpty()) {
            addValueLine(content, com.prefab.addon.PrefabCustomAddon.tr("gui.detail.deps_none"), 0xAAAAAA);
        } else {
            for (String d : deps) {
                String cleanId = d;
                int colon = cleanId.indexOf(':');
                if (colon > 0) cleanId = cleanId.substring(0, colon);
                if (depChecked && depMissingIds != null && depMissingIds.contains(cleanId)) {
                    // 缺失 - 红字可点 + 绿色「[查询]」可点 (与浏览器详情页同款实现).
                    // 点击区用"无背景 Button" (LDLib2 只有 Button 有 setOnClick),
                    // base/pressed 全 EMPTY = 纯文字外观, 悬停亮起; flexGrow/固定宽度
                    // 保证按钮有布局边界, 否则 0 宽命中不了鼠标.
                    final String depId = cleanId;
                    UIElement row = new UIElement();
                    row.layout(l -> l.flexDirection(dev.vfyjxf.taffy.style.FlexDirection.ROW)
                            .widthPercent(100).height(12).gapAll(2));
                    content.addChild(row);
                    com.lowdragmc.lowdraglib2.gui.ui.elements.Button miss =
                            new com.lowdragmc.lowdraglib2.gui.ui.elements.Button()
                                    .setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.dep_missing", d), false);
                    miss.textStyle(t -> t.textColor(0xFF5555));
                    miss.buttonStyle(s -> s.baseTexture(com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture.EMPTY)
                            .hoverTexture(com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites.RECT_LIGHT)
                            .pressedTexture(com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture.EMPTY));
                    miss.layout(l -> l.height(12).paddingAll(0).flexGrow(1));
                    miss.setOnClick(e -> {
                        // [DEP-CLICK] 调试: 确认红字点击是否触发
                        com.prefab.addon.PrefabCustomAddon.LOGGER.info(
                                "[DEP-CLICK] 蓝图界面缺失依赖红字被点击: {}", depId);
                        GuiModrinthSearch.open(depId);
                    });
                    row.addChild(miss);
                    com.lowdragmc.lowdraglib2.gui.ui.elements.Button search =
                            new com.lowdragmc.lowdraglib2.gui.ui.elements.Button().setText("[查询]", false);
                    search.textStyle(t -> t.textColor(0x55FF55));
                    search.buttonStyle(s -> s.baseTexture(com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture.EMPTY)
                            .hoverTexture(com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites.RECT_LIGHT)
                            .pressedTexture(com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture.EMPTY));
                    search.layout(l -> l.height(12).paddingAll(0).width(36));
                    search.setOnClick(e -> {
                        // [DEP-CLICK] 调试: 确认 [查询] 按钮点击是否触发
                        com.prefab.addon.PrefabCustomAddon.LOGGER.info(
                                "[DEP-CLICK] 蓝图界面 [查询] 按钮被点击: {}", depId);
                        GuiModrinthSearch.open(depId);
                    });
                    row.addChild(search);
                } else if (depChecked) {
                    // 已安装 - 绿色
                    addValueLine(content, com.prefab.addon.PrefabCustomAddon.tr("gui.detail.dep_present", d), 0x55FF55);
                } else {
                    // 检测异常兜底 - 白色
                    addValueLine(content, com.prefab.addon.PrefabCustomAddon.tr("gui.detail.dep_unknown", d), 0xFFFFFF);
                }
            }
        }

        // 分类
        addField(content, com.prefab.addon.PrefabCustomAddon.tr("gui.detail.category"),
            construction.getCategoryOrDefault());

        // 描述
        addFieldLabel(content, com.prefab.addon.PrefabCustomAddon.tr("gui.detail.desc"));
        TextElement descEl = new TextElement();
        String desc = construction.getDescription();
        descEl.setText((desc == null || desc.isEmpty())
            ? com.prefab.addon.PrefabCustomAddon.tr("gui.detail.desc_empty") : desc);
        descEl.textStyle(t -> t.textColor(0xCCCCCC).textWrap(TextWrap.WRAP));
        descEl.layout(l -> l.widthPercent(100).height(50));
        content.addChild(descEl);

        scroller.addScrollViewChild(content);
        return scroller;
    }

    private static void addField(UIElement parent, String label, String value) {
        addFieldLabel(parent, label);
        addValueLine(parent, value != null ? value : "", 0xFFFFFF);
    }

    private static void addFieldLabel(UIElement parent, String label) {
        TextElement lbl = new TextElement();
        lbl.setText(label);
        lbl.textStyle(t -> t.textColor(0xFFFF55));
        lbl.layout(l -> l.widthPercent(100).height(12));
        parent.addChild(lbl);
    }

    private static void addValueLine(UIElement parent, String text, int color) {
        TextElement val = new TextElement();
        val.setText(text);
        val.textStyle(t -> t.textColor(color));
        val.layout(l -> l.widthPercent(100).height(12));
        parent.addChild(val);
    }

    // === 计算尺寸 (和 GuiConstructionDetail.computeSize 一致) ===
    private static int[] computeSize(List<BlockData> blocks) {
        if (blocks == null || blocks.isEmpty()) return null;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockData bd : blocks) {
            if (bd == null || bd.pos == null) continue;
            if (bd.pos.getX() < minX) minX = bd.pos.getX();
            if (bd.pos.getY() < minY) minY = bd.pos.getY();
            if (bd.pos.getZ() < minZ) minZ = bd.pos.getZ();
            if (bd.pos.getX() > maxX) maxX = bd.pos.getX();
            if (bd.pos.getY() > maxY) maxY = bd.pos.getY();
            if (bd.pos.getZ() > maxZ) maxZ = bd.pos.getZ();
        }
        if (minX == Integer.MAX_VALUE) return null;
        return new int[]{ maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1 };
    }

    /** 依赖缺失时禁止提交材料与建造 (runDepCheckSilently 已在打开时跑过检测). */
    private static boolean depMissing() {
        return depChecked && depMissingIds != null && !depMissingIds.isEmpty();
    }

    /** 建造按钮 (或挑战模式下的"提交材料"/"预览"按钮) 点击处理 */
    private static void handleMainButtonClick() {
        // 依赖缺失 → 提交材料/预览/建造全部阻止 (缺 mod 建出来也是残缺方块)
        if (depMissing()) {
            showStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.custom.dep_missing_block",
                String.join(", ", depMissingIds)), 0xFF5555, 200);
            return;
        }
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
                showStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.custom.parse_loading"), 0xFFAA00, 80);
                return;
            }
            net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) return;
            MaterialSubmissionGui.open(currentConstruction, materialList, challengeMode, player);
            return;
        }
        // 实际建造 (异步网络包, 服务端分批放置; 蓝图为便携终端入口, 不消耗)
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
            showStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.custom.preview_loading"), 0xFFAA00, 80);
            return;
        }
        // 1) NBT → Prefab Structure (跟服务端 build 用的是同一个解析)
        Structure structure;
        try {
            structure = CustomStructureBuilder.parseToPrefabStructure(currentConstruction);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[CUSTOM-GUI-V2] parseToPrefabStructure failed for preview", t);
            showStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.custom.parse_fail_preview"), 0xFF5555, 100);
            return;
        }
        if (structure == null || structure.getBlocks() == null || structure.getBlocks().isEmpty()) {
            showStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.custom.empty_structure"), 0xFFAA55, 100);
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
        // 关键: prefab 的 StructureRenderHandler.bakeBlockAndSubBlock 内部会调
        //   `player.level().getBlockState(pos)`, 如果不是空气 (且不是水) 就**跳过不画**.
        //   见 prefab Shared/.../StructureRenderHandler.java:500-506.
        //   currentOpenPos 是玩家右键点击的方块 (通常是地面/墙, 不是空气), 把 cfg.pos 设为
        //   currentOpenPos 会让建筑大部分方块在地下/墙里 → prefab 全部跳过 → 什么都不显示.
        // 修法: 抬到 currentOpenPos **上方 1 格** (建筑最底层从地面上一层开始, 大部分方块位置
        //   在空气中) → prefab 正常画.
        BlockPos basePos = currentOpenPos != null ? currentOpenPos : BlockPos.ZERO;
        cfg.pos = basePos.above();
        cfg.houseFacing = houseFacing;

        // 4) 把每个 BuildBlock.blockPos 从 localPos 转换为 worldPos (basePos + rotated local)
        CustomStructureBuilder.offsetStructureBlocks(structure, cfg.pos, cfg.houseFacing);

        // 5) 释放 2D 预览的 Scene 资源
        if (renderScene != null) {
            try { renderScene.releaseRendererResource(); } catch (Throwable ignored) {}
            renderScene = null;
        }

        // 6) 关键: 先把引用存到我们自己的静态字段, 然后 setStructure(structure, cfg) 之后
        //    **立即** setStructure(null, null) 把 prefab 自己的 currentStructure 清掉.
        //    prefab 的 renderStructurePreview (RenderIndicatorMixin 注入) 无条件画 currentStructure,
        //    看到 null 就 return → prefab 不画, 只有我们的 CustomStructurePreviewRenderer 画 → 单预览.
        //    之前: prefab 画一份 + 我们画一份 → "两个预览", 旋转/移动后尤其明显 (我们跟着 cfg.pos
        //    走, prefab 走自己的 getRelativePosition, 两者对不齐 → 两个独立预览).
        ADDON_PREVIEW_STRUCTURE = structure;
        ADDON_PREVIEW_CONFIG = cfg;
        LAST_ADDON_STRUCTURE_REF.set(structure);
        LAST_ADDON_PREVIEW.set(true);

        // 清掉 prefab 的 currentStructure, 防止 prefab 自己的 renderer (RenderIndicatorMixin 注入)
        //   画上一个 prefab 原版建筑 (双预览). prefab 看到 currentStructure=null 就 return.
        StructureRenderHandler.setStructure(null, null);
        // 我们自己画: CustomStructurePreviewRenderer 走 prefab 的渲染模式 (Tesselator + 按
        //   chunk 分组 + VertexBuffer 一次性提交) 但跳过 worldState.isAir() 检查, 让所有方块
        //   都画 (prefab 的 bakeBlockAndSubBlock 跳过非空气, 自定义建筑大部分方块跟地面/墙
        //   重叠会被跳过). 用 prefab 的 BuildBlock.SetBlockState 处理所有 property 类型.
        //   KeyHandler 移动/旋转时不需要 triggerPrefabRebuild (我们自己 renderer 检测 cfg.pos /
        //   houseFacing 变化时自动清 vertex buffer 重建).

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
                btnMain.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.custom.preview"));
            } else {
                btnMain.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.custom.submit_materials"));
            }
            btnMain.setActive(true);
        } else {
            btnMain.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.custom.preview"));
            btnMain.setActive(true);
        }
    }

    /** 实际执行 预览/建造: 走网络包, 跟 CustomStructureGui.performBuildClick 一样 */
    private static void performBuildOrPreview() {
        if (currentConstruction == null) return;

        // 依赖缺失 → 阻止建造 (兜底: 覆盖预览 ALT 建造等其它入口)
        if (depMissing()) {
            showStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.custom.dep_missing_block",
                String.join(", ", depMissingIds)), 0xFF5555, 200);
            return;
        }
        // 多人模式下, 服务器没有这个拓展包 → 阻止
        if (!com.prefab.addon.extension.ExtensionPackManager.isBuildable(currentConstruction)) {
            showStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.custom.no_server_pack"), 0xFF5555, 200);
            return;
        }
        // 挑战模式 + 未提交材料 → 阻止
        if (challengeMode && !checkMaterialsReady()) {
            showStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.custom.challenge_not_ready"), 0xFFAA00, 100);
            return;
        }

        String packName = currentConstruction.getPack() != null
            ? currentConstruction.getPack().getName() : "";
        net.minecraft.core.BlockPos buildPos = currentOpenPos != null
            ? currentOpenPos : net.minecraft.core.BlockPos.ZERO;

        // 建造不再要求持有/绑定自定义蓝图: 任何玩家从建筑列表选中建筑即可进入建造流程
        // (生存交材料建一次, 创造直接建). KubeJS 联动蓝图物品 (player_blueprint) 功能保留,
        // 但建筑列表的建造流程不再依赖/劫持玩家已有的蓝图.

        PrefabCustomAddon.LOGGER.info("[CUSTOM-GUI-V2] Build: pack={} id={} pos={} challengeMode={} animationMode={}",
            packName, currentConstruction.getId(), buildPos, challengeMode,
            com.prefab.addon.config.PlayerPreferences.get().getBuildAnimationMode());
        com.prefab.addon.network.NetworkHandler.sendToServer(
            new com.prefab.addon.network.BuildCustomStructurePayload(
                buildPos, packName, currentConstruction.getId(),
                net.minecraft.core.Direction.SOUTH,
                com.prefab.addon.config.PlayerPreferences.get().getBuildAnimationMode()));
        if (renderScene != null) renderScene.releaseRendererResource();
        Minecraft.getInstance().setScreen(null);
    }

    /**
     * 暴露给 {@link com.prefab.addon.client.StructurePreviewKeyHandler} 用:
     * ALT 建造时需要拿到跟 GUI 建造按钮**完全一致**的 packName (currentConstruction.getPack().getName()),
     * 不要用蓝图里存的 packName (旧版本是用 getPackageName() 绑定的, 会跟服务端不一致).
     */
    public static String getPackNameForBuild() {
        if (currentConstruction == null) return "";
        if (currentConstruction.getPack() != null) {
            return currentConstruction.getPack().getName();
        }
        // 单文件建筑 (LocalBuilding 兜底) 没有 ExtensionPack, 用 STANDALONE_PACKAGE 占位.
        // 服务端 build 路径会按 constructionId 找到对应的 LocalBuilding NBT, 跟 packName 无关.
        return com.prefab.addon.extension.ExtensionPackManager.STANDALONE_PACKAGE;
    }

    public static String getConstructionIdForBuild() {
        return currentConstruction != null ? currentConstruction.getId() : "";
    }

    /**
     * 当前世界中预览的 {@link com.wuest.prefab.structures.base.Structure} 是不是由**我们的 GUI** 启动的.
     *
     * <p>实现: 检查我们 own 的 {@link #ADDON_PREVIEW_STRUCTURE} 是不是非空. 是 → 我们的预览,
     * 否 → prefab 自己启动的 (玩家在 prefab 的 GuiStructure 里预览原版建筑).</p>
     *
     * <p>之前实现: 比较 prefab 的 currentStructure 跟我们记的 LAST_ADDON_STRUCTURE_REF.
     * 这要求我们 setStructure 之后 prefab 的 currentStructure 跟我们 setStructure 进去的对象
     * 引用相等. 现在 CustomStructureGui.handlePreviewButtonClick 改成:
     *   setStructure(structure, cfg) 立即 setStructure(null, null) → prefab.currentStructure 永远 null
     * 引用比对永远 false → 误判为"原版预览" → ALT 建造走 triggerPrefabOriginalBuild() 走 prefab
     * 的 build packet, 我们的 custom blueprint 不消耗, 服务端也找不到对应的 construction.</p>
     *
     * <p>现在: 读我们 own 的 ADDON_PREVIEW_STRUCTURE, 我们启动预览时它是 structure,
     * 我们 setStructure(null, null) 之后清空 (clearAddonPreviewFlag) 时它是 null.</p>
     */
    public static boolean isCurrentPreviewStartedByAddon() {
        return ADDON_PREVIEW_STRUCTURE != null;
    }

    /**
     * 当前正在预览的 structure (我们 own).
     * <p>CustomStructurePreviewRenderer 和 StructurePreviewKeyHandler 用这个引用,
     * 而不是 prefab 的 {@code StructureRenderHandler.currentStructure}.
     * 因为 prefab 自己的 renderer 也会画 currentStructure, 不读这个字段的话
     * prefab 也会画一份 → "两个预览".</p>
     */
    public static com.wuest.prefab.structures.base.Structure getAddonPreviewStructure() {
        return ADDON_PREVIEW_STRUCTURE;
    }

    /**
     * 当前正在预览的 config (我们 own). KeyHandler 移动/旋转时改的是这个引用,
     * 不要去碰 prefab 的 currentConfiguration.
     */
    public static com.wuest.prefab.structures.config.StructureConfiguration getAddonPreviewConfig() {
        return ADDON_PREVIEW_CONFIG;
    }

    /**
     * 预览结束时清掉标记. 供 {@link com.prefab.addon.client.StructurePreviewKeyHandler}
     * 在 tick 检测到 prefab.currentStructure == null 时调用.
     */
    public static void clearAddonPreviewFlag() {
        LAST_ADDON_PREVIEW.set(false);
        LAST_ADDON_STRUCTURE_REF.set(null);
        ADDON_PREVIEW_STRUCTURE = null;
        ADDON_PREVIEW_CONFIG = null;
        // 外包建筑上下文也清 (预览结束, 两条路径都不再活跃)
        currentIsOutsource = false;
        currentOutsourceBuildingId = null;
        currentOutsourceStyleIndex = 0;
    }

    /**
     * 供外部代码直接启动世界预览.
     * 等价于 handlePreviewButtonClick() 走到 setStructure 之前那一步的赋值, 但不关 GUI
     * (调用方自己关).
     */
    public static void setAddonPreviewStructure(com.wuest.prefab.structures.base.Structure structure) {
        ADDON_PREVIEW_STRUCTURE = structure;
    }

    public static void setAddonPreviewConfig(com.wuest.prefab.structures.config.StructureConfiguration cfg) {
        ADDON_PREVIEW_CONFIG = cfg;
    }

    public static void markAddonPreviewActive() {
        if (ADDON_PREVIEW_STRUCTURE != null) {
            LAST_ADDON_STRUCTURE_REF.set(ADDON_PREVIEW_STRUCTURE);
            LAST_ADDON_PREVIEW.set(true);
        }
    }

    // ============== 外包建筑预览上下文 (从 1.21.1 移植) ==============

    /**
     * 当前预览是不是来自 {@link com.prefab.addon.client.gui.GuiOutsourceBuildingDetail}.
     * ALT 建造时, {@link com.prefab.addon.client.StructurePreviewKeyHandler} 用这个
     * 决定走 {@code triggerOutsourceBuildAtPreview} 还是 {@code triggerBuildAtPreview}.
     */
    public static boolean isCurrentOutsource() {
        return currentIsOutsource;
    }

    /** 当前预览对应的 buildingId (例如 {@code 投影_中式茶楼}). */
    public static String getCurrentOutsourceBuildingId() {
        return currentOutsourceBuildingId;
    }

    /** 当前预览对应的风格索引 (0-based). */
    public static int getCurrentOutsourceStyleIndex() {
        return currentOutsourceStyleIndex;
    }

    /**
     * 由 {@link com.prefab.addon.client.gui.GuiOutsourceBuildingDetail#onPreview} 在启动
     * 预览时调用. 跟 {@link #setCurrentConstruction} 互斥 (不要同时调).
     */
    public static void setOutsourceContext(String buildingId, int styleIndex) {
        currentIsOutsource = true;
        currentOutsourceBuildingId = buildingId;
        currentOutsourceStyleIndex = styleIndex;
        // 同时把普通 custom 的 currentConstruction 显式置 null, 防止 ALT 分支误判
        // "上一个 custom 建筑还在", 走错 build 路径
        currentConstruction = null;
    }

    private static boolean checkMaterialsReady() {
        if (materialList == null || materialList.required.isEmpty()) return true;
        net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return false;
        return com.prefab.addon.work.ChallengeSessionManager.isReady(
            player.getUUID(), currentConstruction.getId(), materialList.required);
    }

    /**
     * 截屏当前 3D 预览到 ThumbnailCache (自定义蓝图/终端详情页的缩略图按钮).
     *
     * <p>流程与 {@link GuiConstructionDetail#captureCurrentSceneToThumbnail} 相同:
     * 拿 renderScene 的屏幕位置和大小, 调
     * {@link com.prefab.addon.client.ThumbnailCache#captureRegion}
     * (GUI 坐标 → framebuffer 像素坐标 + glReadPixels + box-filter 缩放到 256px + 写盘).</p>
     */
    private static void captureCurrentSceneToThumbnail() {
        if (renderScene == null || currentConstruction == null) {
            showStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.thumb_not_ready"), 0xFF5555, 60);
            return;
        }
        if (currentConstruction.hasPreviewImage()) {
            showStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.thumb_has_orig"), 0xFFAA55, 60);
            return;
        }
        if (com.prefab.addon.client.ThumbnailCache.hasCached(currentConstruction)) {
            // 已有缓存, 删除旧的再生成
            try {
                java.nio.file.Files.deleteIfExists(
                    com.prefab.addon.client.ThumbnailCache.getCacheFile(currentConstruction));
            } catch (Exception ignored) {}
        }
        int sceneScreenX = (int) renderScene.getPositionX();
        int sceneScreenY = (int) renderScene.getPositionY();
        int sceneScreenW = (int) renderScene.getSizeWidth();
        int sceneScreenH = (int) renderScene.getSizeHeight();
        if (sceneScreenW <= 0 || sceneScreenH <= 0) {
            showStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.thumb_not_drawn"), 0xFF5555, 60);
            return;
        }
        // 共用截图: 坐标换算 + glReadPixels + box-filter 缩放到 256px + 写盘 + notifyCompleted
        boolean ok = com.prefab.addon.client.ThumbnailCache.captureRegion(
            sceneScreenX, sceneScreenY, sceneScreenW, sceneScreenH, currentConstruction, 256);
        if (ok) {
            showStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.thumb_saved",
                com.prefab.addon.client.ThumbnailCache.getCacheFile(currentConstruction)
                    .getFileName().toString()), 0x55FF55, 60);
        } else {
            showStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.screenshot_fail", "capture"),
                0xFF5555, 60);
        }
    }

    private static void showStatus(String msg, int color, int ticks) {
        statusMsg = msg;
        statusColor = color;
        statusTick = ticks;
    }
}
