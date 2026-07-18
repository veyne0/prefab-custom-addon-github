package com.prefab.addon.client.gui;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollDisplay;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ProgressBar;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Scene;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.lowdragmc.lowdraglib2.utils.data.BlockInfo;
import com.lowdragmc.lowdraglib2.utils.virtuallevel.TrackedDummyWorld;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.ConstructionInfo;
import com.prefab.addon.extension.ExtensionPack;
import com.prefab.addon.extension.ExtensionPackManager;
import com.prefab.addon.structure.CustomStructureBuilder;
import com.prefab.addon.structure.CustomStructureBuilder.BlockData;
import com.prefab.addon.work.DependencyChecker;
import com.prefab.addon.work.DependencyChecker.CheckResult;

import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.FlexDirection;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 建筑详情界面 (LDLib2 实现).
 *
 * <h2>布局 (整屏, 100% width/height)</h2>
 * <pre>
 *   ┌────────────────────────────────────┐
 *   │ [Pack Selector 下拉选择扩展包]    (h=22)
 *   ├────────────────────────────────────┤
 *   │ [◀]  建筑名 [1/N]   [▶]         (h=22, 标题行, 切建筑用)
 *   │ ┌───── Body (ROW, flexGrow=1) ─┐  │
 *   │ │ ┌─ Info Scroller ─┐ ┌─ 3D ─┐ │  │
 *   │ │ │ 建筑名            │ │Scene │ │  │
 *   │ │ │ 作者              │ │      │ │  │
 *   │ │ │ 尺寸              │ │      │ │  │
 *   │ │ │ 依赖 mod (✓/✗)   │ │      │ │  │
 *   │ │ │ 描述              │ │      │ │  │
 *   │ │ │ ☐ 锁定绑定        │ │      │ │  │
 *   │ │ │ 渲染进度          │ │      │ │  │
 *   │ │ └──────────────────┘ └──────┘ │  │
 *   │ └────────────────────────────┘  │
 *   │ [Back]  [检测依赖]  [选择]      (h=22)
 *   └──────────────────────────────────┘
 * </pre>
 *
 * <h2>3D 预览异步加载 + 10% 增量</h2>
 * <ol>
 *   <li><b>UI 立即打开</b> (不阻塞主线程) - 进度显示 "解析 NBT 中..."</li>
 *   <li><b>后台线程</b> CompletableFuture 解析 NBT → List&lt;BlockData&gt;</li>
 *   <li>主线程轮询 parseFuture, 完成时:
 *       <ul>
 *         <li>创建 TrackedDummyWorld</li>
 *         <li>创建 Scene, 一次性 setRenderedCore(所有 positions) - 设置相机基于完整 bounding box, 渲染器注册所有位置</li>
 *       </ul>
 *   </li>
 *   <li>每个 client tick, 增量加入 10% blocks 到 world → {@code scene.needCompileCache()} 触发增量重编译</li>
 *   <li>玩家看到 3D 模型从 0% 渐进到 100% (跟 AsyncBuildManager 的 10%/tick 一样)</li>
 * </ol>
 *
 * <h2>性能特征</h2>
 * <ul>
 *   <li><b>无采样</b> - 之前为了避免卡死采样到 4000 块导致模型残缺, 现在 10w+ 块也全显示</li>
 *   <li><b>无主线程卡顿</b> - NBT 解析在 background, scene 编译用 cacheBuffer + syncCompile(false) 异步</li>
 *   <li><b>渐进渲染</b> - 每 tick 编译一次 (cost O(当前块数)), 10 ticks 内 1+2+...+10 = 55 单位, 可接受</li>
 * </ul>
 */
public final class GuiConstructionDetail {

    private GuiConstructionDetail() {}

    // === 异步 + 增量渲染常量 ===
    /** 每 tick 处理的方块百分比 (跟 AsyncBuildManager 一致: 10%/tick) */
    private static final int BATCH_PERCENT = 10;
    /** 单 tick 最多处理多少块 - 防止巨型建筑把单 tick 撑爆 */
    private static final int MAX_BATCH_SIZE = 1500;
    /** 进度文本 tick 间隔 (每 N tick 更新一次, 避免每 tick 都 setText) */
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
    /** 全部 blocks 都 add 完后, 是否已经触发过最终 needCompileCache (避免重复触发) */
    private static boolean finalCompileRequested;

    // === UI 引用 (tick handler 用) ===
    private static TextElement progressEl;
    private static int progressTickCounter;

    // === 状态消息 ===
    private static int statusTick = 0;
    private static String statusMsg = null;
    private static int statusColor = 0x55FF55;

    // === 导航 (上一/下一 建筑) ===
    /** 当前 pack 的所有建筑 (用于 < > 切换). null = 不支持切换. */
    private static List<ConstructionInfo> navList = null;
    /** 当前建筑在 navList 中的索引. -1 = 无效. */
    private static int navIndex = -1;
    /** Prev/Next 按钮引用 (在 createUI 时初始化) */
    private static Button btnPrev;
    private static Button btnNext;
    private static Label titleLabel;

    // === Pack 切换 (Selector) ===
    /** 所有可见的 pack 列表 (供 Selector 选择) */
    private static List<ExtensionPack> packList = java.util.Collections.emptyList();
    /** 当前选中的 pack */
    private static ExtensionPack currentPack = null;
    /** Pack Selector 控件引用 */
    private static Selector<ExtensionPack> packSelector;

    // === 依赖检测结果缓存 ===
    /** 最近的依赖检测结果 (用于在 mod 列表后加 ✗/✓) */
    private static Set<String> lastMissingMods = null;
    /** 检测结果对应的 construction 名字 (切换建筑时清空) */
    private static String lastCheckPackConstructionKey = null;
    /** 依赖列表容器引用 (用于检测后实时更新 ✗/✓) */
    private static UIElement depListContainer;
    /** 当前 info scroller 中显示的 construction (用于 rebuild dep list) */
    private static ConstructionInfo currentDisplayedConstruction;
    /** btnSelect 引用 - 锁定时禁用, 解锁时恢复 */
    private static Button btnSelectRef;
    /** "选择并锁定" 按钮引用 - 用于在 syncLockState 里刷新文字/颜色 */
    private static Button lockToggleBtnRef;

    /**
     * 找玩家主手或背包里的 Custom Blueprint.
     * 优先主手, 没有则扫背包找第一个. 找不到返回 ItemStack.EMPTY.
     */
    private static ItemStack findBlueprintInHandOrInv() {
        net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return ItemStack.EMPTY;
        ItemStack stack = player.getMainHandItem();
        if (stack.getItem() instanceof com.prefab.addon.items.CustomBlueprintItem) return stack;
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() instanceof com.prefab.addon.items.CustomBlueprintItem) return s;
        }
        return ItemStack.EMPTY;
    }

    /**
     * 当前玩家主手/背包里的蓝图是否已经:
     * <ol>
     *   <li>绑定了 packName/constructionId = 当前 construction</li>
     *   <li>locked = true</li>
     * </ol>
     * 是则返回 true - 这时应该禁用 prev/next/select, 防止玩家绕过锁定换建筑.
     */
    private static boolean isCurrentBlueprintLockedTo(ConstructionInfo info) {
        if (info == null || info.getPack() == null) return false;
        ItemStack stack = findBlueprintInHandOrInv();
        if (stack.isEmpty()) return false;
        if (!com.prefab.addon.items.CustomBlueprintItem.hasConstructionBound(stack)) return false;
        if (!com.prefab.addon.items.CustomBlueprintItem.isBoundTo(stack, info)) return false;
        return com.prefab.addon.items.CustomBlueprintItem.isLocked(stack);
    }

    /**
     * 同步 lockBtn + 按钮可用性到当前 construction 的 lock 状态.
     * 在 createUI 末尾和 tick handler 开头调用一次 (玩家可能切物品/解锁).
     */
    private static void syncLockState(ConstructionInfo info) {
        boolean locked = isCurrentBlueprintLockedTo(info);
        // 更新底部 [选择并锁定] / [解锁] 按钮的文字/颜色
        if (lockToggleBtnRef != null) {
            String currentText = lockToggleBtnRef.text.getText().getString();
            if (locked && !currentText.contains("已锁定")) {
                lockToggleBtnRef.setText("🔒 已锁定");
                lockToggleBtnRef.text.textStyle(t -> t.textColor(0xFFFFAA55));
            } else if (!locked && !currentText.contains("选择并锁定")) {
                lockToggleBtnRef.setText("🔐 选择并锁定");
                lockToggleBtnRef.text.textStyle(t -> t.textColor(0xFFFFFFFF));
            }
        }
        // 锁定 → 禁用 prev/next/select; 解锁 → 按 navList 决定
        if (btnPrev != null) {
            btnPrev.setActive(!locked && navList != null && navIndex > 0);
        }
        if (btnNext != null) {
            btnNext.setActive(!locked && navList != null
                && navIndex >= 0 && navIndex < navList.size() - 1);
        }
        if (btnSelectRef != null) {
            btnSelectRef.setActive(!locked);
        }
    }

    public static void open(ConstructionInfo construction) {
        openWithNav(construction, null, -1);
    }

    /**
     * 打开建筑详情, 支持 < > 切换 (同一 pack 的其他建筑).
     *
     * @param construction 当前建筑
     * @param allInPack 当前建筑所在 pack 的所有建筑列表 (用于切换), null = 不支持切换
     * @param index current 在 allInPack 中的索引
     */
    public static void openWithNav(ConstructionInfo construction, List<ConstructionInfo> allInPack, int index) {
        // 关键: 必须保留 packList (完整 pack 列表), 否则切换建筑后下拉框只剩当前 pack
        // 之前的 bug: 传 singlePack 只有 1 个, 用户切建筑后下拉框就只显示 1 个 pack
        // 如果之前 packList 是空的 (比如用户用 open() 直接进来), 用 getDiscoverablePacks
        List<ExtensionPack> allPacks;
        if (!packList.isEmpty()) {
            allPacks = packList;
        } else {
            allPacks = ExtensionPackManager.getInstance().getDiscoverablePacks();
        }
        int packIndex = -1;
        if (construction.getPack() != null) {
            for (int i = 0; i < allPacks.size(); i++) {
                if (allPacks.get(i) == construction.getPack()
                    || (allPacks.get(i).getPackageName() != null
                        && allPacks.get(i).getPackageName().equals(construction.getPack().getPackageName()))) {
                    packIndex = i;
                    break;
                }
            }
        }
        if (packIndex < 0) {
            // 当前 pack 不在 discoverable 列表里 (比如没 information/ 目录),
            // 至少把它自己加进去, 避免下拉框为空
            allPacks = new ArrayList<>(allPacks);
            allPacks.add(0, construction.getPack());
            packIndex = 0;
        }
        openWithPackSelector(construction, allInPack, index, allPacks, packIndex);
    }

    /**
     * 打开建筑详情 + Pack 选择器, 支持切换 pack 和同一 pack 内的建筑.
     *
     * @param construction 当前建筑
     * @param allInPack 当前建筑所在 pack 的所有建筑列表
     * @param index construction 在 allInPack 中的索引
     * @param allPacks 所有可见的 pack 列表 (供 Selector 切换)
     * @param packIndex current 所在 pack 在 allPacks 中的索引
     */
    public static void openWithPackSelector(ConstructionInfo construction, List<ConstructionInfo> allInPack,
                                             int index, List<ExtensionPack> allPacks, int packIndex) {
        resetState();
        currentConstruction = construction;
        navList = allInPack;
        navIndex = index;
        packList = allPacks != null ? allPacks : java.util.Collections.emptyList();
        currentPack = (packIndex >= 0 && packIndex < packList.size()) ? packList.get(packIndex) : construction.getPack();
        startAsyncParse(construction);
        ModularUI ui = createUI(construction);
        Minecraft.getInstance().setScreen(
            new ModularUIScreen(ui, Component.literal(construction.getName())));
    }

    /**
     * 右键蓝图时调用: 打开第一个 pack 的第一个建筑, 带 Pack 选择器.
     */
    public static void openFirstAvailable() {
        ExtensionPackManager mgr = ExtensionPackManager.getInstance();
        List<ExtensionPack> packs = mgr.getDiscoverablePacks();
        if (packs == null || packs.isEmpty()) {
            PrefabCustomAddon.LOGGER.warn("[DETAIL] openFirstAvailable: no discoverable packs");
            return;
        }
        ExtensionPack firstPack = packs.get(0);
        List<ConstructionInfo> constructions = firstPack.getConstructions();
        if (constructions == null || constructions.isEmpty()) {
            PrefabCustomAddon.LOGGER.warn("[DETAIL] openFirstAvailable: pack '{}' has no constructions", firstPack.getName());
            return;
        }
        openWithPackSelector(constructions.get(0), constructions, 0, packs, 0);
    }

    private static void resetState() {
        synchronized (parseLock) {
            currentConstruction = null;
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
            navList = null;
            navIndex = -1;
            btnPrev = null;
            btnNext = null;
            titleLabel = null;
            currentPack = null;
            packSelector = null;
            lockToggleBtnRef = null;
            btnSelectRef = null;
            // 清空依赖检测缓存 (新建筑需要重新检测)
            lastMissingMods = null;
            lastCheckPackConstructionKey = null;
        }
        statusTick = 0;
        statusMsg = null;
        progressTickCounter = 0;
    }

    /**
     * 启动后台 NBT 解析. UI 立即返回, parse 完成后由主线程 tick handler 消费.
     */
    private static void startAsyncParse(ConstructionInfo construction) {
        final String name = construction.getName();
        final String packId = construction.getPack().getPackageName();
        synchronized (parseLock) {
            parseFuture = CompletableFuture.supplyAsync(() -> {
                long t0 = System.currentTimeMillis();
                try {
                    byte[] nbtData = construction.getNbtData();
                    if (nbtData == null || nbtData.length == 0) {
                        PrefabCustomAddon.LOGGER.warn("[DETAIL] No NBT data for '{}' (pack={})", name, packId);
                        return null;
                    }
                    PrefabCustomAddon.LOGGER.info("[DETAIL] Async parse start: '{}' (pack={}, nbtBytes={})",
                        name, packId, nbtData.length);
                    CustomStructureBuilder.getInstance().loadStructureFromNbt(construction);
                    List<BlockData> blocks = CustomStructureBuilder.getInstance().parseStructureBlocks();
                    long ms = System.currentTimeMillis() - t0;
                    PrefabCustomAddon.LOGGER.info("[DETAIL] Async parse done: '{}' → {} blocks ({} ms)",
                        name, blocks != null ? blocks.size() : 0, ms);
                    return blocks;
                } catch (Throwable t) {
                    PrefabCustomAddon.LOGGER.error("[DETAIL] Async parse failed for '{}'", name, t);
                    return null;
                }
            });
        }
    }

    /**
     * 主线程 tick 调用 - 检查 parseFuture 是否完成, 是则把结果存入 parseResult.
     * 用 isDone() + get(0, MS) 非阻塞地取结果, 避免阻塞主线程.
     *
     * <p>关键: 区分 "parse 真正失败" (异常/无 NBT) vs "parse 成功但全是空气" (依赖 mod 缺失导致).
     * 之前 result.isEmpty() 被当作 parseFailed, 导致依赖 mod 缺失的建筑永远卡在 "3D 加载中...".</p>
     */
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
                // 只有 result == null (说明 NBT 数据为空或解析异常) 才算失败
                // 空列表 (result.isEmpty()) 是合法状态: 表示 NBT 解析成功但所有方块都是 air
                //   - 通常是依赖 mod 缺失, 那些方块在 BuiltInRegistries 找不到 → fallback 到 AIR
                //   - 这种情况应该让 UI 进入 "全部空气" 状态, 而不是卡在 "加载中"
                if (result == null) {
                    parseFailed = true;
                }
            } catch (Exception e) {
                PrefabCustomAddon.LOGGER.error("[DETAIL] Parse future get failed", e);
                parseResult = null;
                parseComplete = true;
                parseFailed = true;
            }
        }
    }

    /**
     * parse 完成后初始化渲染. 一次性 setRenderedCore 设置相机 (基于完整 bounding box)
     * 并把所有 positions 注册到 renderer; 之后每 tick 增量加 blocks 到 world 并重编译.
     *
     * <p>不采样, 全部方块都渲染. 318k 块的建筑也能完整显示, 只是首次编译慢一些 (syncCompile 模式每帧 200 块).</p>
     */
    private static void initRender() {
        if (parseResult == null || parseResult.isEmpty()) {
            renderDone = true;
            return;
        }
        try {
            // 关键: 不能传 realLevel 当 proxy!
            // TrackedDummyWorld.getBlockState 在 proxy 非空时直接返回 proxy.getBlockState(pos),
            //   而真实世界在 (0,0,0) 等位置都是 AIR → 渲染器拿到的全是 AIR, 3D 区域就全黑.
            // 必须用 new TrackedDummyWorld() (无 proxy), 这样 getBlockState 走 super → chunk system,
            //   而 setBlockAndUpdate 会把块写到本地 chunk, 渲染器才能读到正确的 BlockState.
            renderWorld = new TrackedDummyWorld();

            renderBlocks = parseResult;
            renderTotalBlocks = renderBlocks.size();
            // 软上限: 单 tick 最多 MAX_BATCH_SIZE 块, 防止 100k+ 巨型建筑单 tick 卡死
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
                PrefabCustomAddon.LOGGER.warn("[DETAIL] All {} blocks are air, nothing to render", renderTotalBlocks);
                renderDone = true;
                return;
            }

            // 不采样: 全部方块都渲染. 玩家可以看到完整的建筑, 即使是 318k 块的巨型建筑.
            // syncCompile 模式下, 编译是按帧限速的 (默认 2ms/帧, 约 200 块/帧),
            // 100k 块约 8 秒, 318k 块约 25 秒, 但不会卡死主线程.
            List<BlockData> renderList = nonAir;
            List<BlockPos> positions = new ArrayList<>(renderList.size());
            for (BlockData bd : renderList) {
                positions.add(bd.pos);
            }

            PrefabCustomAddon.LOGGER.info("[DETAIL] initRender: {} total / {} non-air / batch={}/tick (全量渲染, 无采样)",
                renderTotalBlocks, nonAir.size(), renderBatchSize);

            renderScene = new Scene();
            renderScene.useOrtho(true);
            renderScene.setDraggable(true);
            renderScene.setScalable(true);
            renderScene.setIntractable(true);
            renderScene.setRenderFacing(false);
            renderScene.setRenderSelect(false);
            renderScene.useCacheBuffer(true);
            // 关键: 用 syncCompile(true) - 在主线程每帧增量编译 (默认 2ms/帧, 200 块/帧),
            //   不用 background thread. 旧版 syncCompile(false) 在巨型建筑下会反复
            //   cancelCompile → thread.join(200) → 启新线程, 几次就把 CPU 跑满卡死.
            renderScene.syncCompile(true);
            // 关键: 禁用 tickWorld!
            //   Scene 默认每 tick 调 dummyWorld.tickWorld() → tickBlockEntities,
            //   但我们的虚拟世界里某些 block (create:chute 等) 状态在 addBlocks 过程中可能变成 AIR,
            //   blockEntity 还在, validateBlockState 抛 IllegalStateException → 崩溃.
            //   我们只需要纯渲染, 不需要 tick blockEntity.
            renderScene.setTickWorld(false);

            renderScene.createScene(renderWorld);
            // 一次性 setRenderedCore 设置相机 + 注册所有 positions.
            //  - camera 基于完整 bounding box (autoCamera=true) → 从一开始就对准整个建筑
            //  - 渲染器注册所有 positions → addBlocks 后能立即渲染
            //  - 此时 world 是空的, 首次 compile 0 块 → 0 ms
            renderScene.setRenderedCore(positions, null, true);

            renderActive = true;
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[DETAIL] initRender failed", t);
            renderDone = true;
        }
    }

    /**
     * 每 tick 调用: 把下一批 blocks 加入 world, 直到全部加完, 再触发一次最终重编译.
     *
     * <h2>关键设计: 单次最终重编译 (而非每 tick 重编译)</h2>
     * <ul>
     *   <li><b>addBlocks 阶段 (不调 needCompileCache)</b> - world 状态改变, 但不强制渲染器重新编译.
     *       渲染器 cacheState==NEED (从 initRender 的 setRenderedCore 触发) 会从 0 开始编译
     *       "注册的所有 positions", 但 world 此时还是空的, 它遍历 pos → getBlockState → AIR →
     *       渲染空气 → buffer 空 → 实际耗时几乎为 0.</li>
     *   <li><b>所有 blocks 都 addBlocks 完 (调一次 needCompileCache)</b> - 此时 world 有完整 blocks,
     *       渲染器从 0 开始编译完整 3D, syncCompile 模式每帧 200 块, 60k 块 5s 编译完.</li>
     *   <li><b>绝对不能在 addBlocks 中间调 needCompileCache</b> - cancelCompile 会清空
     *       syncCompileState, 渲染器每次都从 0 开始, 而"当前所有可见块"在每 tick 都在增长
     *       (10%→20%→...→100%), 永远编译不完 → CPU 100% → 卡死.</li>
     * </ul>
     */
    private static void tickRender() {
        if (!renderActive || renderScene == null || renderWorld == null) {
            renderActive = false;
            renderDone = true;
            return;
        }

        int endIdx = Math.min(renderTotalBlocks, renderNextIndex + renderBatchSize);
        if (endIdx <= renderNextIndex) {
            // 所有 blocks 都已加入 world - 只在此时触发一次最终重编译
            if (!finalCompileRequested) {
                finalCompileRequested = true;
                renderScene.needCompileCache();
                PrefabCustomAddon.LOGGER.info("[DETAIL] 全部 {} blocks 已 addBlocks 完成, 触发最终 needCompileCache",
                    renderTotalBlocks);
            }
            renderActive = false;
            renderDone = true;
            return;
        }

        // 增量 addBlocks (不调 needCompileCache, 避免 cancel 当前 syncCompile)
        Map<BlockPos, BlockInfo> blockMap = new HashMap<>();
        for (int i = renderNextIndex; i < endIdx; i++) {
            BlockData bd = renderBlocks.get(i);
            if (bd == null || bd.pos == null || bd.state == null) continue;
            if (bd.state.isAir()) continue;
            blockMap.put(bd.pos, BlockInfo.fromBlockState(bd.state));
        }
        if (!blockMap.isEmpty()) {
            renderWorld.addBlocks(blockMap);
            // 关键: 这里不调 needCompileCache!
            // cancelCompile 会清空 syncCompileState, 每次从头开始编译"当前所有可见块",
            // 而 addBlocks 不断把新块加进 world, 永远编译不完 → 卡死.
        }
        renderNextIndex = endIdx;

        if (renderNextIndex >= renderTotalBlocks) {
            // 本批处理完即所有 blocks 都加完, 立刻触发最终重编译
            if (!finalCompileRequested) {
                finalCompileRequested = true;
                renderScene.needCompileCache();
                PrefabCustomAddon.LOGGER.info("[DETAIL] 全部 {} blocks 已 addBlocks 完成, 触发最终 needCompileCache",
                    renderTotalBlocks);
            }
            renderActive = false;
            renderDone = true;
        }
    }

    private static int getRenderProgress() {
        if (renderTotalBlocks <= 0) return 0;
        return Math.min(100, renderNextIndex * 100 / renderTotalBlocks);
    }

    /** 构建标题文本: "建筑名 [index/total]" */
    private static String buildTitleText(ConstructionInfo construction) {
        if (navList == null || navIndex < 0) {
            return construction.getName();
        }
        return construction.getName() + "  [" + (navIndex + 1) + "/" + navList.size() + "]";
    }

    /**
     * 导航到 navList 中的另一个建筑.
     * 关闭当前屏幕, 重新打开目标建筑 (不重新加载 NBT 解析, 全新 state).
     */
    private static void navigateTo(int newIndex) {
        if (navList == null) return;
        if (newIndex < 0 || newIndex >= navList.size()) return;
        ConstructionInfo next = navList.get(newIndex);
        if (next == null) return;
        PrefabCustomAddon.LOGGER.info("[DETAIL] navigate: {} → {} (index {} → {})",
            currentConstruction != null ? currentConstruction.getName() : "?",
            next.getName(), navIndex, newIndex);
        // 重新打开, 触发 resetState + startAsyncParse
        openWithNav(next, navList, newIndex);
    }

    private static ModularUI createUI(ConstructionInfo construction) {
        PrefabCustomAddon.LOGGER.info("[DETAIL] createUI: 构造='{}' pack='{}'", construction.getName(), construction.getPack().getName());

        // 1) 根容器 - 填满屏幕 (widthPercent/heightPercent 100%)
        //   关键: 不能用固定 width/height 超过 guiScaledWidth (GUI scale 2 时 = 427),
        //   否则 modularUI.leftPos = (427-460)/2 = -16 → root 在屏幕外!
        //   改为 percent 100% 后 root 宽度 = 屏幕宽,leftPos = 0, 居中显示 ✓
        UIElement root = new UIElement();
        root.layout(l -> l
            .widthPercent(100).heightPercent(100)
            .flexDirection(FlexDirection.COLUMN)
            .paddingAll(2).gapAll(2)
        );
        root.style(s -> s.background(Sprites.BORDER));
        root.setOverflowVisible(false);

        // 2) Pack 选择栏: [Pack Selector] - 普通 column child, 固定高度 22
        //   让玩家用下拉框切换不同的拓展包, 切换后回到该 pack 的第一个建筑
        packSelector = new Selector<ExtensionPack>();
        packSelector.setCandidates(packList);
        PrefabCustomAddon.LOGGER.info("[DETAIL] createUI: packSelector created with {} candidates: {}",
            packList.size(), packList.stream().map(ExtensionPack::getName).toList());
        packSelector.setValue(currentPack);
        packSelector.setOnValueChanged(newPack -> {
            PrefabCustomAddon.LOGGER.info("[DETAIL] packSelector onValueChanged: '{}' → '{}', packList size={}",
                currentPack != null ? currentPack.getName() : "null",
                newPack != null ? newPack.getName() : "null",
                packList.size());
            if (newPack == null || newPack == currentPack) return;
            List<ConstructionInfo> allInNewPack = new ArrayList<>(newPack.getConstructions());
            if (allInNewPack.isEmpty()) return;
            openWithPackSelector(allInNewPack.get(0), allInNewPack, 0, packList, packList.indexOf(newPack));
        });
        packSelector.layout(l -> l.widthPercent(100).height(22));
        packSelector.selectorStyle(s -> s
            .scrollerViewHeight(120)
            .maxItemCount(8)
        );
        root.addChild(packSelector);

        // 3) 标题栏: [Prev] 标题 [Next] - 普通 column child, 固定高度 22
        UIElement titleRow = new UIElement();
        titleRow.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .widthPercent(100)
            .height(22)
            .gapAll(4)
            .paddingLeft(4).paddingRight(4)
        );
        titleRow.style(s -> s.background(Sprites.RECT_DARK));
        titleRow.setOverflowVisible(false);

        // Prev 按钮: 切到上一个建筑
        btnPrev = new Button().setText("◀");
        btnPrev.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        btnPrev.layout(l -> l.width(32).height(20));
        if (navList != null && navIndex > 0) {
            btnPrev.setOnClick(e -> navigateTo(navIndex - 1));
            btnPrev.setActive(true);
        } else {
            btnPrev.setActive(false);
        }
        titleRow.addChild(btnPrev);

        // 标题 (居中, flexGrow 占据中间)
        titleLabel = new Label();
        titleLabel.setText(buildTitleText(construction));
        titleLabel.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        titleLabel.layout(l -> l.flexGrow(1).height(20));
        titleRow.addChild(titleLabel);

        // Next 按钮: 切到下一个建筑
        btnNext = new Button().setText("▶");
        btnNext.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        btnNext.layout(l -> l.width(32).height(20));
        if (navList != null && navIndex >= 0 && navIndex < navList.size() - 1) {
            btnNext.setOnClick(e -> navigateTo(navIndex + 1));
            btnNext.setActive(true);
        } else {
            btnNext.setActive(false);
        }
        titleRow.addChild(btnNext);

        root.addChild(titleRow);
        PrefabCustomAddon.LOGGER.info("[DETAIL] titleRow added: navList={}, navIndex={}, prev={}, next={}",
            navList != null ? navList.size() : "null", navIndex,
            navList != null && navIndex > 0, navList != null && navIndex >= 0 && navIndex < navList.size() - 1);
        // 在 tick handler 里 log 实际尺寸
        final int[] counter = {0};
        titleRow.addEventListener(UIEvents.TICK, e -> {
            counter[0]++;
            if (counter[0] % 60 == 0) {
                UIElement trParent = titleRow.getParent();
                PrefabCustomAddon.LOGGER.info("[DETAIL] titleRow: pos=({},{}) size={}x{} rootPos=({},{}) rootSize={}x{} layoutX={} layoutY={} parent={}",
                    (int) titleRow.getPositionX(), (int) titleRow.getPositionY(),
                    (int) titleRow.getSizeWidth(), (int) titleRow.getSizeHeight(),
                    (int) root.getPositionX(), (int) root.getPositionY(),
                    (int) root.getSizeWidth(), (int) root.getSizeHeight(),
                    (int) titleRow.getLayoutX(), (int) titleRow.getLayoutY(),
                    trParent != null ? trParent.getClass().getSimpleName() + "(pos=" + (int)trParent.getPositionX() + "," + (int)trParent.getPositionY() + ")" : "null");
            }
        });

        // 3) Body 区: 左信息面板 + 右 3D 预览
        //   关键: 用 minHeight(0) 防止 body 被子元素的 heightPercent 撑爆
        //   body 用固定 height + flexShrink(1) 让 Taffy 约束最大高度
        UIElement bodyRow = new UIElement();
        bodyRow.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .widthPercent(100).flexGrow(1).flexShrink(1)
            .gapAll(4)
            .minHeight(0).minWidth(0)
        );
        bodyRow.setOverflowVisible(false);

        // 3a) 3D 预览容器 (右侧, flexGrow=1 占满)
        UIElement sceneContainer = new UIElement();
        sceneContainer.layout(l -> l.flexGrow(1).flexShrink(1).heightPercent(100).minHeight(0).minWidth(0));
        sceneContainer.style(s -> s.background(Sprites.RECT_DARK));
        sceneContainer.setOverflowVisible(false);

        // 初始 placeholder (renderScene 还没创建时显示)
        final TextElement scenePlaceholder = new TextElement();
        scenePlaceholder.setText("3D 加载中...\n(等待 NBT 解析)");
        scenePlaceholder.textStyle(t -> t
            .textAlignHorizontal(Horizontal.CENTER)
            .textColor(0xAAAAAA)
            .textWrap(TextWrap.WRAP));
        scenePlaceholder.layout(l -> l.widthPercent(100).heightPercent(100)
            .justifyContent(AlignContent.CENTER));
        sceneContainer.addChild(scenePlaceholder);

        // 3b) 进度文字 (保留 - 3D 渲染时显示进度)
        progressEl = new TextElement();
        progressEl.setText("解析 NBT 中...");
        progressEl.textStyle(t -> t.textColor(0xFFFF55).textAlignHorizontal(Horizontal.CENTER));
        progressEl.layout(l -> l.widthPercent(100).height(12));

        // 3c) 左侧信息面板 (锁定按钮已移到底部按钮行, 这里不再创建)
        ScrollerView infoScroller = createInfoScroller(construction, progressEl);
        infoScroller.layout(l -> l.width(140).heightPercent(100).minHeight(0));
        bodyRow.addChild(infoScroller);

        bodyRow.addChild(sceneContainer);
        root.addChild(bodyRow);
        PrefabCustomAddon.LOGGER.info("[DETAIL] Body row added: infoScroller(140px) + sceneContainer(flexGrow=1)");

        // 4) 按钮行 - 4 个按钮: Back, 检测依赖, 选择, 选择并锁定
        UIElement buttonRow = new UIElement();
        buttonRow.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .widthPercent(100).height(22)
            .gapAll(4)
            .justifyContent(AlignContent.CENTER)
        );

        Button btnBack = new Button().setText("Back");
        btnBack.setOnClick(e -> Minecraft.getInstance().setScreen(null));
        btnBack.layout(l -> l.flexGrow(1).heightPercent(100));
        buttonRow.addChild(btnBack);

        Button btnCheckDeps = new Button().setText("检测依赖");
        btnCheckDeps.setOnClick(e -> runDepCheck(construction));
        btnCheckDeps.layout(l -> l.flexGrow(1).heightPercent(100));
        buttonRow.addChild(btnCheckDeps);

        // "选择" 按钮 = 绑定当前 construction 到玩家主手 (或背包里) 的 自定义蓝图
        Button btnSelect = new Button().setText("选择");
        btnSelect.setOnClick(e -> bindCurrentConstruction(construction, false));
        btnSelect.layout(l -> l.flexGrow(1).heightPercent(100));
        btnSelectRef = btnSelect;
        buttonRow.addChild(btnSelect);

        // "选择并锁定" 按钮 = 绑定当前 construction + 立即锁定 (锁定后此蓝图无法再换建筑)
        //   - 状态显示: 未锁定时显示 "🔐 选择并锁定", 已锁定时显示 "🔒 已锁定" (颜色变橙)
        //   - 点击行为: 未锁定 → 绑定 + 锁定; 已锁定 → 解锁 (允许重新 [选择] 换建筑)
        final Button lockToggleBtn = new Button();
        final boolean initiallyLocked = isCurrentBlueprintLockedTo(construction);
        lockToggleBtn.setText(initiallyLocked ? "🔒 已锁定" : "🔐 选择并锁定");
        lockToggleBtn.text.textStyle(t -> t.textColor(initiallyLocked ? 0xFFFFAA55 : 0xFFFFFFFF));
        lockToggleBtn.layout(l -> l.flexGrow(1).heightPercent(100));
        lockToggleBtn.setOnClick(e -> {
            boolean currentlyLocked = isCurrentBlueprintLockedTo(construction);
            if (currentlyLocked) {
                // 已锁定 → 解锁
                ItemStack stack = findBlueprintInHandOrInv();
                if (stack.isEmpty()
                    || !(stack.getItem() instanceof com.prefab.addon.items.CustomBlueprintItem)) {
                    showStatus("✗ 找不到 自定义蓝图 (主手/背包)", 0xFF5555, 100);
                    return;
                }
                if (!com.prefab.addon.items.CustomBlueprintItem.isBoundTo(stack, construction)) {
                    showStatus("✗ 当前蓝图绑的是别的建筑, 不能解锁", 0xFF5555, 100);
                    return;
                }
                com.prefab.addon.items.CustomBlueprintItem.setLocked(stack,
                    construction.getPack().getName(), construction.getId(), false);
                showStatus("🔓 已解锁, 可以重新 [选择] 换建筑", 0x55FF55, 100);
            } else {
                // 未锁定 → 绑定当前建筑 + 锁定
                //   - 如果蓝图已绑别的建筑, bindCurrentConstruction 内会检查并提示
                //   - 成功后, 锁状态会通过 syncLockState 同步
                ItemStack stack = findBlueprintInHandOrInv();
                if (stack.isEmpty()
                    || !(stack.getItem() instanceof com.prefab.addon.items.CustomBlueprintItem)) {
                    showStatus("✗ 找不到 自定义蓝图 (主手/背包)", 0xFF5555, 100);
                    return;
                }
                if (com.prefab.addon.items.CustomBlueprintItem.isLocked(stack)) {
                    showStatus("✗ 当前蓝图已锁定到别的建筑, 请先解锁", 0xFF5555, 100);
                    return;
                }
                // 调 bindCurrentConstruction, 内部会 bind + 延迟关闭 GUI
                bindCurrentConstruction(construction, true);
            }
            // 刷新按钮状态
            syncLockState(construction);
        });
        lockToggleBtnRef = lockToggleBtn;
        buttonRow.addChild(lockToggleBtn);

        root.addChild(buttonRow);

        // 关键: createUI 末尾应用一次 lock 状态 - 锁定时禁用 prev/next/select
        syncLockState(construction);

        // 5) tick handler - 整个 3D 预览的生命周期
        final int[] debugTickCounter = {0};
        root.addEventListener(UIEvents.TICK, event -> {
            debugTickCounter[0]++;
            final int tickNum = debugTickCounter[0];

            // 5.0) 布局调试 - 前 5 个 tick 打印一次
            if (tickNum == 1 || tickNum == 5 || tickNum == 10) {
                float rootW = root.getSizeWidth(), rootH = root.getSizeHeight();
                float bodyW = bodyRow.getSizeWidth(), bodyH = bodyRow.getSizeHeight();
                float sceneW = sceneContainer.getSizeWidth(), sceneH = sceneContainer.getSizeHeight();
                float infoW = infoScroller.getSizeWidth(), infoH = infoScroller.getSizeHeight();
                float btnW = buttonRow.getSizeWidth(), btnH = buttonRow.getSizeHeight();
                PrefabCustomAddon.LOGGER.info("[DETAIL-LAYOUT] t#{} root={}x{} body={}x{} scene={}x{} info={}x{} btn={}x{}",
                    tickNum, (int)rootW, (int)rootH, (int)bodyW, (int)bodyH,
                    (int)sceneW, (int)sceneH, (int)infoW, (int)infoH, (int)btnW, (int)btnH);
            }

            // 5.01) 同步 lock 状态 - 每 10 tick 刷一次, 覆盖玩家外部切换物品/锁的情况
            if (tickNum % 10 == 0) {
                syncLockState(construction);
            }

            // 5a) 检查 parse 状态
            if (!parseComplete && !parseFailed) {
                checkParse();
                if (tickNum % 20 == 1) {
                    PrefabCustomAddon.LOGGER.info("[DETAIL] tick#{} waiting parse... sceneContainer.size={}x{} placeholder='{}'",
                        tickNum, (int) sceneContainer.getSizeWidth(), (int) sceneContainer.getSizeHeight(),
                        scenePlaceholder.getText());
                }
            }

            // 5b) parse 完成后, 初始化 render (一次)
            if (parseComplete && !parseFailed && renderScene == null && !renderActive && !renderDone) {
                PrefabCustomAddon.LOGGER.info("[DETAIL] tick#{} 调用 initRender", tickNum);
                initRender();
                if (renderScene != null) {
                    sceneContainer.clearAllChildren();
                    renderScene.layout(l -> l.widthPercent(100).heightPercent(100));
                    sceneContainer.addChild(renderScene);
                    progressEl.setText("渲染 0%");
                    PrefabCustomAddon.LOGGER.info("[DETAIL] tick#{} Scene 已添加到 sceneContainer, scene.size={}x{} sceneContainer.size={}x{}",
                        tickNum,
                        (int) renderScene.getSizeWidth(), (int) renderScene.getSizeHeight(),
                        (int) sceneContainer.getSizeWidth(), (int) sceneContainer.getSizeHeight());
                } else if (renderDone) {
                    scenePlaceholder.setText("3D 预览不可用\n(场景创建失败)");
                    progressEl.setText("✗ 无 3D");
                    progressEl.textStyle(t -> t.textColor(0xFF5555));
                    PrefabCustomAddon.LOGGER.warn("[DETAIL] tick#{} Scene 创建失败 (renderDone=true)", tickNum);
                }
            }

            // 5c) render 进行中 → tick + 限频更新进度文本
            if (renderActive) {
                tickRender();
                progressTickCounter++;
                if (progressTickCounter >= PROGRESS_UPDATE_TICKS) {
                    progressTickCounter = 0;
                    progressEl.setText("渲染 " + getRenderProgress() + "%");
                }
                if (tickNum % 20 == 0) {
                    PrefabCustomAddon.LOGGER.info("[DETAIL] tick#{} 渲染中 {}/{} ({}%) scene.size={}x{}",
                        tickNum, renderNextIndex, renderTotalBlocks, getRenderProgress(),
                        renderScene != null ? (int) renderScene.getSizeWidth() : 0,
                        renderScene != null ? (int) renderScene.getSizeHeight() : 0);
                }
            }

            // 5d) render 完成 / parse 失败 / 空结果 - 统一处理终态
            if (renderDone) {
                if (renderScene != null) {
                    progressEl.setText("✓ 完成 (" + renderTotalBlocks + " 块)");
                    progressEl.textStyle(t -> t.textColor(0x55FF55));
                    if (tickNum == debugTickCounter[0] || tickNum % 60 == 0) {
                        PrefabCustomAddon.LOGGER.info("[DETAIL] tick#{} 渲染完成 scene.size={}x{} eyePos={} lookAt={} zoom={} useOrtho={}",
                            tickNum,
                            renderScene != null ? (int) renderScene.getSizeWidth() : 0,
                            renderScene != null ? (int) renderScene.getSizeHeight() : 0,
                            renderScene.getRenderer() != null ? renderScene.getRenderer().getEyePos() : "null",
                            renderScene.getRenderer() != null ? renderScene.getRenderer().getLookAt() : "null",
                            renderScene.getZoom(), renderScene.isUseOrtho());
                    }
                } else {
                    if (parseFailed) {
                        progressEl.setText("✗ 解析失败");
                        progressEl.textStyle(t -> t.textColor(0xFF5555));
                    } else if (parseResult != null && parseResult.isEmpty()) {
                        progressEl.setText("⚠ 无可显示方块");
                        progressEl.textStyle(t -> t.textColor(0xFFAA55));
                        scenePlaceholder.setText("无 3D 内容\n(所有方块均为 air\n可能缺依赖 mod)");
                        scenePlaceholder.textStyle(t -> t.textColor(0xFFAA55)
                            .textAlignHorizontal(Horizontal.CENTER)
                            .textWrap(TextWrap.WRAP));
                    } else {
                        progressEl.setText("✗ 渲染失败");
                        progressEl.textStyle(t -> t.textColor(0xFF5555));
                    }
                }
            } else if (parseFailed) {
                progressEl.setText("✗ 解析失败");
                progressEl.textStyle(t -> t.textColor(0xFF5555));
                renderDone = true;
            } else if (parseComplete && parseResult != null && parseResult.isEmpty()) {
                progressEl.setText("⚠ 无可显示方块");
                progressEl.textStyle(t -> t.textColor(0xFFAA55));
                renderDone = true;
            }

            // 5e) 状态栏倒计时 (用 progressEl 显示简短消息)
            if (statusTick > 0 && statusMsg != null) {
                progressEl.setText(statusMsg);
                progressEl.textStyle(t -> t.textColor(statusColor));
                statusTick--;
                if (statusTick <= 0) {
                    statusMsg = null;
                    if (renderDone && renderScene != null) {
                        progressEl.setText("✓ 完成 (" + renderTotalBlocks + " 块)");
                        progressEl.textStyle(t -> t.textColor(0x55FF55));
                    }
                }
            }
        });

        return ModularUI.of(UI.of(root,
            StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)));
    }

    // === 信息面板 (ScrollerView + 锁 toggle + 进度) ===
    private static ScrollerView createInfoScroller(ConstructionInfo construction, TextElement progressEl) {
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

        // 进度 (渲染期间显示) - 放在最上面, 玩家一眼能看到
        content.addChild(progressEl);

        // 建筑名
        addField(content, "建筑名", construction.getName());
        // 作者
        String author = construction.getAuthor();
        addField(content, "作者", (author != null && !author.isEmpty()) ? author : "未知");
        // 尺寸 (用 getSize 字段, 解析前的 bounding box 计算)
        int[] computedSize = computeSize(parseResult); // 可能为 null (parse 还没完成)
        String sizeStr;
        if (computedSize != null) {
            sizeStr = computedSize[0] + "x" + computedSize[1] + "x" + computedSize[2];
        } else if (construction.getSize() != null) {
            sizeStr = construction.getSize();
        } else {
            sizeStr = "未知";
        }
        addField(content, "尺寸", sizeStr);

        // 蓝图格式: 用 construction.format (创建建筑时填的, 保存到 construction/<id>.txt).
        //   - 创建过的建筑 (有 "蓝图格式:" 行): 显示用户填的 (nbt / litematic / schem)
        //   - 旧建筑 (没填过格式): 显示"未知"
        String format = construction.getFormatDisplay();
        addField(content, "蓝图格式", format);

        // 依赖 mod - 使用独立容器, 检测后可动态 rebuild
        currentDisplayedConstruction = construction;
        List<String> deps = construction.getDependencies();
        addFieldLabel(content, "依赖 mod (" + (deps == null ? 0 : deps.size()) + ")");
        depListContainer = new UIElement();
        depListContainer.layout(l -> l
            .flexDirection(FlexDirection.COLUMN)
            .gapAll(3)
            .widthPercent(100)
        );
        content.addChild(depListContainer);
        rebuildDepList(construction);

        // 描述
        String desc = construction.getDescription();
        addFieldLabel(content, "描述");
        TextElement descEl = new TextElement();
        descEl.setText((desc == null || desc.isEmpty()) ? "无" : desc);
        descEl.textStyle(t -> t.textColor(0xCCCCCC).textWrap(TextWrap.WRAP));
        descEl.layout(l -> l.widthPercent(100).height(50));
        content.addChild(descEl);

        scroller.addScrollViewChild(content);
        return scroller;
    }

    /**
     * 重建依赖列表 (检测完成后实时更新 ✗/✓).
     * 关键: 必须先 clearAllChildren, 否则会重复堆叠.
     */
    private static void rebuildDepList(ConstructionInfo construction) {
        if (depListContainer == null) return;
        depListContainer.clearAllChildren();
        List<String> deps = construction.getDependencies();
        if (deps == null || deps.isEmpty()) {
            addValueLine(depListContainer, "无", 0xAAAAAA);
            return;
        }
        String currentKey = construction.getPack().getPackageName() + "/" + construction.getId();
        boolean hasCheckResult = lastCheckPackConstructionKey != null
            && lastCheckPackConstructionKey.equals(currentKey)
            && lastMissingMods != null;
        for (String d : deps) {
            String cleanId = d;
            int colon = cleanId.indexOf(':');
            if (colon > 0) cleanId = cleanId.substring(0, colon);
            if (hasCheckResult && lastMissingMods.contains(cleanId)) {
                // 缺失 - 红色 + 红叉
                addValueLine(depListContainer, "✗ " + d, 0xFF5555);
            } else if (hasCheckResult) {
                // 已安装 - 绿色 + 绿勾
                addValueLine(depListContainer, "✓ " + d, 0x55FF55);
            } else {
                // 未检测 - 白色
                addValueLine(depListContainer, "• " + d, 0xFFFFFF);
            }
        }
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

    // === 计算尺寸 ===
    private static int[] computeSize(List<BlockData> blocks) {
        if (blocks == null || blocks.isEmpty()) return null;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockData bd : blocks) {
            if (bd.pos.getX() < minX) minX = bd.pos.getX();
            if (bd.pos.getY() < minY) minY = bd.pos.getY();
            if (bd.pos.getZ() < minZ) minZ = bd.pos.getZ();
            if (bd.pos.getX() > maxX) maxX = bd.pos.getX();
            if (bd.pos.getY() > maxY) maxY = bd.pos.getY();
            if (bd.pos.getZ() > maxZ) maxZ = bd.pos.getZ();
        }
        return new int[]{ maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1 };
    }

    // === 依赖检测 ===
    private static void runDepCheck(ConstructionInfo construction) {
        List<String> deps = construction.getDependencies();
        PrefabCustomAddon.LOGGER.info("[DETAIL] Dep check: '{}' deps={}", construction.getName(), deps);
        CheckResult result = DependencyChecker.check(deps);

        // 缓存检测结果, 用于在 mod 列表后显示 ✗/✓
        // 注意: DependencyChecker.check 用的是 cleanDepList, 我们比对时也用 cleaned modid
        Set<String> missing = new HashSet<>();
        for (String m : result.missing) {
            int colon = m.indexOf(':');
            missing.add(colon > 0 ? m.substring(0, colon) : m);
        }
        lastMissingMods = missing;
        lastCheckPackConstructionKey = construction.getPack().getPackageName() + "/" + construction.getId();
        PrefabCustomAddon.LOGGER.info("[DETAIL] Dep check done: missing={}, present={}",
            result.missing, result.present);

        // 关键: 实时重建依赖列表 UI, 让 ✗/✓ 立即显示
        if (currentDisplayedConstruction != null
            && currentDisplayedConstruction.getPack().getPackageName().equals(construction.getPack().getPackageName())
            && currentDisplayedConstruction.getId().equals(construction.getId())) {
            rebuildDepList(construction);
        }

        // 简化状态消息: 顶部状态栏只显示简短结果 (依赖详情在左侧 mod 列表里用 ✗/✓ 显示)
        String shortMsg;
        if (result.missing.isEmpty()) {
            shortMsg = "✓ 依赖检测通过 (" + result.present.size() + "/" + (deps == null ? 0 : deps.size()) + ")";
        } else {
            shortMsg = "✗ 检测完成 - 见左侧 ✗ 标记";
        }
        statusMsg = shortMsg;
        statusColor = result.missing.isEmpty() ? 0x55FF55 : 0xFFAA55;
        statusTick = 200;
    }

    private static void showStatus(String msg, int color, int ticks) {
        statusMsg = msg;
        statusColor = color;
        statusTick = ticks;
    }

    /**
     * 把当前 construction 绑定到玩家背包里的 自定义蓝图.
     * 优先选主手蓝图, 主手没有时扫描背包找未绑定的蓝图.
     * 绑定后关闭当前界面 (玩家右键蓝图会直接进 CustomStructureGui, 看到 3D 预览 + 预览/建造按钮).
     *
     * @param lockAfter true = 绑定后立即锁住 (用于 [选择并锁定] 按钮)
     *                  false = 只绑定, 不锁 (用于 [选择] 按钮)
     */
    private static void bindCurrentConstruction(ConstructionInfo construction, boolean lockAfter) {
        if (construction == null || construction.getPack() == null) {
            showStatus("✗ 当前建筑无效", 0xFF5555, 100);
            return;
        }
        net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            showStatus("✗ 玩家不存在", 0xFF5555, 100);
            return;
        }

        // 1) 优先用主手的蓝图
        ItemStack stack = player.getMainHandItem();
        // 2) 主手不是蓝图 → 扫描背包找一个未锁定的蓝图
        if (stack.isEmpty() || !(stack.getItem() instanceof com.prefab.addon.items.CustomBlueprintItem)) {
            Inventory inv = player.getInventory();
            stack = ItemStack.EMPTY;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (s.getItem() instanceof com.prefab.addon.items.CustomBlueprintItem) {
                    stack = s;
                    break;
                }
            }
        }

        if (stack.isEmpty() || !(stack.getItem() instanceof com.prefab.addon.items.CustomBlueprintItem)) {
            showStatus("✗ 背包里没有 自定义蓝图 物品!", 0xFF5555, 100);
            return;
        }
        if (com.prefab.addon.items.CustomBlueprintItem.isLocked(stack)) {
            showStatus("✗ 蓝图已锁定, 无法重新绑定", 0xFF5555, 100);
            return;
        }

        // 关键: 必须用 getName() 而非 getPackageName()!
        // ExtensionPackManager.findConstruction 用 pack.getName() 做查找, 如果 bind 时存
        // 了 packageName, 玩家右键蓝图时 ClientHandler.openGuiForStack → findConstruction
        // 就会返回 null, "bound but info is null", 蓝图右键无反应.
        // CustomBlueprintItem.isBoundTo 也是用 getName() 做比较, 保持一致.
        String packName = construction.getPack().getName();
        String constructionId = construction.getId();
        PrefabCustomAddon.LOGGER.info("[DETAIL] bind: pack='{}' id='{}' → 蓝图 '{}' (lockAfter={})",
            packName, constructionId,
            com.prefab.addon.items.CustomBlueprintItem.hasConstructionBound(stack)
                ? "已绑定 " + com.prefab.addon.items.CustomBlueprintItem.getBoundPackName(stack)
                  + "/" + com.prefab.addon.items.CustomBlueprintItem.getBoundConstructionId(stack)
                : "未绑定",
            lockAfter);

        com.prefab.addon.items.CustomBlueprintItem.bindConstruction(stack, packName, constructionId);
        if (lockAfter) {
            // 立即锁定, 让这个蓝图绑死在这个建筑上, 玩家之后 [选择] 别的建筑会被拒绝
            com.prefab.addon.items.CustomBlueprintItem.setLocked(stack, packName, constructionId, true);
            showStatus("🔒 已绑定并锁定: " + construction.getName(), 0xFFAA55, 60);
        } else {
            showStatus("✓ 已绑定: " + construction.getName(), 0x55FF55, 60);
        }

        // 延迟 1.5 秒关闭, 让玩家看到成功提示
        new Thread(() -> {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            Minecraft.getInstance().execute(() -> {
                if (renderScene != null) renderScene.releaseRendererResource();
                Minecraft.getInstance().setScreen(null);
            });
        }, "DETAIL-Bind-Close").start();
    }
}
