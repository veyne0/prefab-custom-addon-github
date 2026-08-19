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
    /** 当前 pack 的所有建筑 (用于 < > 切换). null = 不支持切换. (新版 UI 已弃用, 保留仅为向后兼容) */
    private static List<ConstructionInfo> navList = null;
    /** 当前建筑在 navList 中的索引. -1 = 无效. (新版 UI 已弃用) */
    private static int navIndex = -1;
    /** Prev/Next 按钮引用 (新版 UI 已移除, 保留字段仅为避免编译错误) */
    private static Button btnPrev;
    private static Button btnNext;

    /**
     * "← 返回"按钮的自定义回调. null = 默认 (打开 GuiExtensionPackBrowser).
     * 编辑建筑 → 查看 场景下, GuiExtensionPackEditor.openConstructionDetail() 会传
     * 一个 reopenEditor() 回调, 让"返回"直接回到编辑建筑 tab, 不绕到云端浏览器.
     */
    private static Runnable onBackCallback = null;
    private static Label titleLabel;

    // === Pack 切换 (Selector) - 新版 UI 已移除 ===
    /** 所有可见的 pack 列表 (供 Selector 选择) - 新版 UI 不再用 */
    private static List<ExtensionPack> packList = java.util.Collections.emptyList();
    /** 当前选中的 pack - 新版 UI 不再用 */
    private static ExtensionPack currentPack = null;
    /** Pack Selector 控件引用 - 新版 UI 不再创建 */
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
    /** "收藏" 按钮引用 - 右上角 ☆ / ★ 切换 */
    private static Button btnFavoriteRef;

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
            String lockedText = com.prefab.addon.PrefabCustomAddon.tr("gui.detail.locked");
            String unlockText = com.prefab.addon.PrefabCustomAddon.tr("gui.detail.unlock");
            if (locked && !currentText.contains("已锁定") && !currentText.contains("Locked")) {
                lockToggleBtnRef.setText(lockedText);
                lockToggleBtnRef.text.textStyle(t -> t.textColor(0xFFFFAA55));
            } else if (!locked && !currentText.contains("选择并锁定") && !currentText.contains("Pick and lock")) {
                lockToggleBtnRef.setText(unlockText);
                lockToggleBtnRef.text.textStyle(t -> t.textColor(0xFFFFFFFF));
            }
        }
        // 新版 UI 已移除 prev/next 按钮, 只剩 select 需要根据 lock 状态启用
        if (btnSelectRef != null) {
            btnSelectRef.setActive(!locked);
        }
    }

    public static void open(ConstructionInfo construction) {
        // 默认: 返回按钮走 GuiExtensionPackBrowser (旧路径, 兼容)
        open(construction, null);
    }

    /**
     * 带"返回回调"地打开建筑详情. 提供给"编辑建筑 → 查看"场景,
     * 返回时直接回到 GuiExtensionPackEditor 的编辑建筑 tab,
     * 而不是默认的 GuiExtensionPackBrowser (那个界面是给云端/下载/收藏用的, 编辑建筑场景用不到).
     *
     * @param construction 当前建筑
     * @param onBack 返回按钮回调. null = 用默认行为 (打开 GuiExtensionPackBrowser).
     */
    public static void open(ConstructionInfo construction, Runnable onBack) {
        // 保存回调. 静态字段, 简单可靠, 跟 currentConstruction/navList 一样的"会话级单例"模式.
        GuiConstructionDetail.onBackCallback = onBack;
        resetState();
        currentConstruction = construction;
        // 新版 UI 不再用 navList (无翻页按钮), 但保留字段防止 null 引用
        navList = null;
        navIndex = -1;
        packList = ExtensionPackManager.getInstance().getDiscoverablePacks();
        currentPack = construction.getPack();
        startAsyncParse(construction);
        ModularUI ui = createUI(construction);
        Minecraft.getInstance().setScreen(
            new ModularUIScreen(ui, Component.literal(construction.getName())));
    }

    /** 安全取 packName, pack 为 null 时用 "local" 兜底 (单文件建筑). */
    private static String safePkg(ConstructionInfo c) {
        return (c == null || c.getPack() == null) ? "local" : c.getPack().getPackageName();
    }

    /**
     * 打开建筑详情, 支持 < > 切换 (同一 pack 的其他建筑).
     *
     * <p><b>新版 UI 已弃用:</b> 移除下拉框 + 翻页按钮后, openWithNav/openWithPackSelector
     * 等价于 {@link #open(ConstructionInfo)}.</p>
     *
     * @param construction 当前建筑
     * @param allInPack 当前建筑所在 pack 的所有建筑列表 (已忽略)
     * @param index current 在 allInPack 中的索引 (已忽略)
     */
    public static void openWithNav(ConstructionInfo construction, List<ConstructionInfo> allInPack, int index) {
        open(construction);
    }

    /**
     * 打开建筑详情 + Pack 选择器, 支持切换 pack 和同一 pack 内的建筑.
     *
     * <p><b>新版 UI 已弃用:</b> 不再有 pack 下拉框, 所以 packList/packIndex 都被忽略,
     * 内部直接走 {@link #open(ConstructionInfo)}.</p>
     *
     * @param construction 当前建筑
     * @param allInPack 当前建筑所在 pack 的所有建筑列表 (已忽略)
     * @param index construction 在 allInPack 中的索引 (已忽略)
     * @param allPacks 所有可见的 pack 列表 (已忽略)
     * @param packIndex current 所在 pack 在 allPacks 中的索引 (已忽略)
     */
    public static void openWithPackSelector(ConstructionInfo construction, List<ConstructionInfo> allInPack,
                                             int index, List<ExtensionPack> allPacks, int packIndex) {
        open(construction);
    }

    /**
     * 右键蓝图时调用: 打开第一个 pack 的第一个建筑.
     * <p>新版 UI 不再使用, 但保留供 CustomStructureGui 兼容.</p>
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
        open(constructions.get(0));
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
            btnFavoriteRef = null;
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
        // 防御: 单文件建筑 (LocalBuilding 转的 ConstructionInfo) 没有 pack, 用 id 兜底
        String packId = (construction.getPack() != null)
            ? construction.getPack().getPackageName()
            : ("local:" + construction.getId());
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
     *
     * <p><b>新版 UI 已弃用:</b> 无 prev/next 按钮, 这个方法不会被调用. 保留仅为向后兼容.</p>
     */
    private static void navigateTo(int newIndex) {
        if (navList == null) return;
        if (newIndex < 0 || newIndex >= navList.size()) return;
        ConstructionInfo next = navList.get(newIndex);
        if (next == null) return;
        PrefabCustomAddon.LOGGER.info("[DETAIL] navigate: {} → {} (index {} → {}) [DEPRECATED, no prev/next button in new UI]",
            currentConstruction != null ? currentConstruction.getName() : "?",
            next.getName(), navIndex, newIndex);
        open(next);
    }

    private static ModularUI createUI(ConstructionInfo construction) {
        // 单文件建筑没有 pack, 用 id 兜底, 避免 LOGGER NPE
        String packLabel = (construction.getPack() != null)
            ? construction.getPack().getName()
            : "本地单文件";
        PrefabCustomAddon.LOGGER.info("[DETAIL] createUI: 构造='{}' pack='{}'", construction.getName(), packLabel);

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

        // 新版 UI: 移除 packSelector 下拉框 + prev/next 翻页按钮
        //   (新版从 GuiExtensionPackBrowser 直接进 construction 详情, 切换建筑在 browser 层做)
        //   标题栏现在只放: [← 返回] 标题 [☆ 收藏]
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

        // 返回浏览器按钮 (左侧)
        Button btnBackToBrowser = new Button().setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.back"));
        btnBackToBrowser.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        btnBackToBrowser.layout(l -> l.width(56).height(20));
        btnBackToBrowser.setOnClick(e -> {
            // 优先用 caller 提供的回调 (编辑建筑 → 查看 场景下回到编辑器),
            // 没传回调才走默认的 GuiExtensionPackBrowser.
            if (onBackCallback != null) {
                onBackCallback.run();
                return;
            }
            // 关闭当前详情, 重新打开新版的 tabbed browser
            GuiExtensionPackBrowser.open();
        });
        titleRow.addChild(btnBackToBrowser);

        // 标题 (居中, flexGrow 占据中间)
        titleLabel = new Label();
        titleLabel.setText(construction.getName());
        titleLabel.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        titleLabel.layout(l -> l.flexGrow(1).height(20));
        titleRow.addChild(titleLabel);

        // 收藏按钮 (右侧, 右上角)
        //   - 已收藏: 显示 "★ 已收藏" (黄色)
        //   - 未收藏: 显示 "☆ 收藏" (白色)
        //   - 点击: 切换 + 持久化到 PlayerPreferences
        String pkg = construction.getPack() != null ? construction.getPack().getPackageName() : null;
        boolean fav = com.prefab.addon.config.PlayerPreferences.get().isFavorite(pkg, construction.getId());
        final Button btnFavorite = new Button();
        String favText = com.prefab.addon.PrefabCustomAddon.tr(fav ? "gui.detail.favorited" : "gui.detail.favorite");
        btnFavorite.setText(favText);
        btnFavorite.text.textStyle(t -> t.textColor(fav ? 0xFFFFDD66 : 0xFFFFFFFF));
        btnFavorite.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        btnFavorite.layout(l -> l.width(72).height(20));
        btnFavorite.setOnClick(e -> {
            boolean nowFav = com.prefab.addon.config.PlayerPreferences.get()
                .toggleFavorite(pkg, construction.getId());
            String newText = com.prefab.addon.PrefabCustomAddon.tr(nowFav ? "gui.detail.favorited" : "gui.detail.favorite");
            btnFavorite.setText(newText);
            btnFavorite.text.textStyle(t -> t.textColor(nowFav ? 0xFFFFDD66 : 0xFFFFFFFF));
            showStatus(com.prefab.addon.PrefabCustomAddon.tr(nowFav ? "gui.detail.add_favorite" : "gui.detail.remove_favorite"),
                nowFav ? 0x55FF55 : 0xFFAA55, 60);
        });
        btnFavoriteRef = btnFavorite;
        titleRow.addChild(btnFavorite);

        // "生成缩略图" 按钮 (3D 预览渲染完成后, 可以截屏存到 ThumbnailCache)
        //  - 已有原图 (hasPreviewImage): 显示为禁用 + "已有原图"
        //  - 已有缓存: 显示 "更新缩略图"
        //  - 无缓存: 显示 "生成缩略图"
        final Button btnCaptureThumb = new Button();
        boolean hasOrigImg = construction.hasPreviewImage();
        boolean hasCachedThumb = !hasOrigImg && com.prefab.addon.client.ThumbnailCache.hasCached(construction);
        if (hasOrigImg) {
            btnCaptureThumb.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.has_thumb"));
            btnCaptureThumb.setActive(false);
        } else if (hasCachedThumb) {
            btnCaptureThumb.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.update_thumb"));
        } else {
            btnCaptureThumb.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.generate_thumb"));
        }
        btnCaptureThumb.text.textStyle(t -> t.textColor(0xFFAAFFAA));
        btnCaptureThumb.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        btnCaptureThumb.layout(l -> l.width(80).height(20));
        btnCaptureThumb.setOnClick(e -> {
            // 把 3D 预览截屏到 ThumbnailCache
            captureCurrentSceneToThumbnail();
        });
        titleRow.addChild(btnCaptureThumb);

        root.addChild(titleRow);
        PrefabCustomAddon.LOGGER.info("[DETAIL] titleRow added (new version: Back + title + Favorite, no selector/prev/next)");
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
        scenePlaceholder.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.load_3d"));
        scenePlaceholder.textStyle(t -> t
            .textAlignHorizontal(Horizontal.CENTER)
            .textColor(0xAAAAAA)
            .textWrap(TextWrap.WRAP));
        scenePlaceholder.layout(l -> l.widthPercent(100).heightPercent(100)
            .justifyContent(AlignContent.CENTER));
        sceneContainer.addChild(scenePlaceholder);

        // 3b) 进度文字 (保留 - 3D 渲染时显示进度)
        progressEl = new TextElement();
        progressEl.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.parsing"));
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

        // 「删除建筑」按钮: 点一下走二次确认 (LdLib 弹窗), 确认后删 .nbt/.txt/.png + 回到浏览器
        Button btnDeleteBuilding = new Button().setText("§c🗑 删除建筑");
        btnDeleteBuilding.setOnClick(e -> confirmDeleteBuilding(construction));
        btnDeleteBuilding.layout(l -> l.flexGrow(1).heightPercent(100));
        buttonRow.addChild(btnDeleteBuilding);

        Button btnCheckDeps = new Button().setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.check_deps_btn"));
        btnCheckDeps.setOnClick(e -> runDepCheck(construction));
        btnCheckDeps.layout(l -> l.flexGrow(1).heightPercent(100));
        buttonRow.addChild(btnCheckDeps);

        // "选择" 按钮 = 绑定当前 construction 到玩家主手 (或背包里) 的 自定义蓝图
        Button btnSelect = new Button().setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.select"));
        btnSelect.setOnClick(e -> bindCurrentConstruction(construction, false));
        btnSelect.layout(l -> l.flexGrow(1).heightPercent(100));
        btnSelectRef = btnSelect;
        buttonRow.addChild(btnSelect);

        // "选择并锁定" 按钮 = 绑定当前 construction + 立即锁定 (锁定后此蓝图无法再换建筑)
        //   - 状态显示: 未锁定时显示 "🔐 选择并锁定", 已锁定时显示 "🔒 已锁定" (颜色变橙)
        //   - 点击行为: 未锁定 → 绑定 + 锁定; 已锁定 → 解锁 (允许重新 [选择] 换建筑)
        final Button lockToggleBtn = new Button();
        final boolean initiallyLocked = isCurrentBlueprintLockedTo(construction);
        lockToggleBtn.setText(initiallyLocked
            ? com.prefab.addon.PrefabCustomAddon.tr("gui.detail.locked")
            : com.prefab.addon.PrefabCustomAddon.tr("gui.detail.unlock"));
        lockToggleBtn.text.textStyle(t -> t.textColor(initiallyLocked ? 0xFFFFAA55 : 0xFFFFFFFF));
        lockToggleBtn.layout(l -> l.flexGrow(1).heightPercent(100));
        lockToggleBtn.setOnClick(e -> {
            boolean currentlyLocked = isCurrentBlueprintLockedTo(construction);
            if (currentlyLocked) {
                // 已锁定 → 解锁
                ItemStack stack = findBlueprintInHandOrInv();
                if (stack.isEmpty()
                    || !(stack.getItem() instanceof com.prefab.addon.items.CustomBlueprintItem)) {
                    showStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.no_blueprint_in_hand"), 0xFF5555, 100);
                    return;
                }
                if (!com.prefab.addon.items.CustomBlueprintItem.isBoundTo(stack, construction)) {
                    showStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.locked_other_unlock"), 0xFF5555, 100);
                    return;
                }
                com.prefab.addon.items.CustomBlueprintItem.setLocked(stack,
                    construction.getPack().getName(), construction.getId(), false);
                showStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.unlocked"), 0x55FF55, 100);
            } else {
                // 未锁定 → 绑定当前建筑 + 锁定
                //   - 如果蓝图已绑别的建筑, bindCurrentConstruction 内会检查并提示
                //   - 成功后, 锁状态会通过 syncLockState 同步
                ItemStack stack = findBlueprintInHandOrInv();
                if (stack.isEmpty()
                    || !(stack.getItem() instanceof com.prefab.addon.items.CustomBlueprintItem)) {
                    showStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.no_blueprint_in_hand"), 0xFF5555, 100);
                    return;
                }
                if (com.prefab.addon.items.CustomBlueprintItem.isLocked(stack)) {
                    showStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.locked_other_bind"), 0xFF5555, 100);
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
                    progressEl.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.render_0"));
                    PrefabCustomAddon.LOGGER.info("[DETAIL] tick#{} Scene 已添加到 sceneContainer, scene.size={}x{} sceneContainer.size={}x{}",
                        tickNum,
                        (int) renderScene.getSizeWidth(), (int) renderScene.getSizeHeight(),
                        (int) sceneContainer.getSizeWidth(), (int) sceneContainer.getSizeHeight());
                } else if (renderDone) {
                    scenePlaceholder.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.3d_unavailable"));
                    progressEl.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.render_fail"));
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
                    progressEl.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.render_pct", getRenderProgress()));
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
                    progressEl.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.done_label", renderTotalBlocks));
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
                        progressEl.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.parse_fail_label"));
                        progressEl.textStyle(t -> t.textColor(0xFF5555));
                    } else if (parseResult != null && parseResult.isEmpty()) {
                        progressEl.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.no_blocks_label"));
                        progressEl.textStyle(t -> t.textColor(0xFFAA55));
                        scenePlaceholder.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.no_3d_content"));
                        scenePlaceholder.textStyle(t -> t.textColor(0xFFAA55)
                            .textAlignHorizontal(Horizontal.CENTER)
                            .textWrap(TextWrap.WRAP));
                    } else {
                        progressEl.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.render_fail_label"));
                        progressEl.textStyle(t -> t.textColor(0xFF5555));
                    }
                }
            } else if (parseFailed) {
                progressEl.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.parse_fail_label"));
                progressEl.textStyle(t -> t.textColor(0xFF5555));
                renderDone = true;
            } else if (parseComplete && parseResult != null && parseResult.isEmpty()) {
                progressEl.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.no_blocks_label"));
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
                        progressEl.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.done_label", renderTotalBlocks));
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
        addField(content, com.prefab.addon.PrefabCustomAddon.tr("gui.detail.name"), construction.getName());
        // 作者
        String author = construction.getAuthor();
        addField(content, com.prefab.addon.PrefabCustomAddon.tr("gui.detail.author"),
            (author != null && !author.isEmpty()) ? author : com.prefab.addon.PrefabCustomAddon.tr("gui.detail.unknown"));
        // 尺寸 (用 getSize 字段, 解析前的 bounding box 计算)
        int[] computedSize = computeSize(parseResult); // 可能为 null (parse 还没完成)
        String sizeStr;
        if (computedSize != null) {
            sizeStr = computedSize[0] + "x" + computedSize[1] + "x" + computedSize[2];
        } else if (construction.getSize() != null) {
            sizeStr = construction.getSize();
        } else {
            sizeStr = com.prefab.addon.PrefabCustomAddon.tr("gui.detail.unknown");
        }
        addField(content, com.prefab.addon.PrefabCustomAddon.tr("gui.detail.size"), sizeStr);

        // 蓝图格式: 用 construction.format (创建建筑时填的, 保存到 construction/<id>.txt).
        //   - 创建过的建筑 (有 "蓝图格式:" 行): 显示用户填的 (nbt / litematic / schem)
        //   - 旧建筑 (没填过格式): 显示"未知"
        String format = construction.getFormatDisplay();
        addField(content, com.prefab.addon.PrefabCustomAddon.tr("gui.detail.format"), format);

        // 依赖 mod - 使用独立容器, 检测后可动态 rebuild
        currentDisplayedConstruction = construction;
        List<String> deps = construction.getDependencies();
        addFieldLabel(content, com.prefab.addon.PrefabCustomAddon.tr("gui.detail.dep_label", deps == null ? 0 : deps.size()));
        depListContainer = new UIElement();
        depListContainer.layout(l -> l
            .flexDirection(FlexDirection.COLUMN)
            .gapAll(3)
            .widthPercent(100)
        );
        content.addChild(depListContainer);
        rebuildDepList(construction);

        // 分类 - 来自 .txt 的 "分类:" 字段. 没有就显示"未分类"
        addField(content,
            com.prefab.addon.PrefabCustomAddon.tr("gui.detail.category"),
            construction.getCategoryOrDefault());

        // 描述
        String desc = construction.getDescription();
        addFieldLabel(content, com.prefab.addon.PrefabCustomAddon.tr("gui.detail.desc"));
        TextElement descEl = new TextElement();
        descEl.setText((desc == null || desc.isEmpty())
            ? com.prefab.addon.PrefabCustomAddon.tr("gui.detail.desc_empty") : desc);
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
            addValueLine(depListContainer, com.prefab.addon.PrefabCustomAddon.tr("gui.detail.deps_none"), 0xAAAAAA);
            return;
        }
        String currentKey = (construction.getPack() != null
            ? construction.getPack().getPackageName()
            : "local") + "/" + construction.getId();
        boolean hasCheckResult = lastCheckPackConstructionKey != null
            && lastCheckPackConstructionKey.equals(currentKey)
            && lastMissingMods != null;
        for (String d : deps) {
            String cleanId = d;
            int colon = cleanId.indexOf(':');
            if (colon > 0) cleanId = cleanId.substring(0, colon);
            if (hasCheckResult && lastMissingMods.contains(cleanId)) {
                // 缺失 - 红色 + 红叉
                addValueLine(depListContainer, com.prefab.addon.PrefabCustomAddon.tr("gui.detail.dep_missing", d), 0xFF5555);
            } else if (hasCheckResult) {
                // 已安装 - 绿色 + 绿勾
                addValueLine(depListContainer, com.prefab.addon.PrefabCustomAddon.tr("gui.detail.dep_present", d), 0x55FF55);
            } else {
                // 未检测 - 白色
                addValueLine(depListContainer, com.prefab.addon.PrefabCustomAddon.tr("gui.detail.dep_unknown", d), 0xFFFFFF);
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
        lastCheckPackConstructionKey = (construction.getPack() != null
            ? construction.getPack().getPackageName()
            : "local") + "/" + construction.getId();
        PrefabCustomAddon.LOGGER.info("[DETAIL] Dep check done: missing={}, present={}",
            result.missing, result.present);

        // 关键: 实时重建依赖列表 UI, 让 ✗/✓ 立即显示
        if (currentDisplayedConstruction != null
            && safePkg(currentDisplayedConstruction).equals(safePkg(construction))
            && currentDisplayedConstruction.getId().equals(construction.getId())) {
            rebuildDepList(construction);
        }

        // 简化状态消息: 顶部状态栏只显示简短结果 (依赖详情在左侧 mod 列表里用 ✗/✓ 显示)
        String shortMsg;
        if (result.missing.isEmpty()) {
            shortMsg = com.prefab.addon.PrefabCustomAddon.tr("gui.detail.dep_check_pass",
                result.present.size(), (deps == null ? 0 : deps.size()));
        } else {
            shortMsg = com.prefab.addon.PrefabCustomAddon.tr("gui.detail.dep_check_done");
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
        // 单文件建筑 (下载/独立 .nbt) 没有 pack, 用 localNbtPath 作为有效校验
        boolean isValid = construction != null
            && (construction.getPack() != null
                || (construction.getLocalNbtPath() != null
                    && java.nio.file.Files.exists(construction.getLocalNbtPath())));
        if (!isValid) {
            showStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.invalid_construction"), 0xFF5555, 100);
            return;
        }
        net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            showStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.no_player"), 0xFF5555, 100);
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
            showStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.no_blueprint_inv"), 0xFF5555, 100);
            return;
        }
        if (com.prefab.addon.items.CustomBlueprintItem.isLocked(stack)) {
            showStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.blueprint_locked_rebind"), 0xFF5555, 100);
            return;
        }

        // 关键: 必须用 getName() 而非 getPackageName()!
        // ExtensionPackManager.findConstruction 用 pack.getName() 做查找, 如果 bind 时存
        // 了 packageName, 玩家右键蓝图时 ClientHandler.openGuiForStack → findConstruction
        // 就会返回 null, "bound but info is null", 蓝图右键无反应.
        // CustomBlueprintItem.isBoundTo 也是用 getName() 做比较, 保持一致.
        String packName = (construction.getPack() != null)
            ? construction.getPack().getName()
            : "local";
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
            showStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.bound_and_locked", construction.getName()), 0xFFAA55, 60);
        } else {
            showStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.bound", construction.getName()), 0x55FF55, 60);
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

    /**
     * 截屏当前 3D 预览到 ThumbnailCache.
     *
     * <p>流程:
     * <ol>
     *   <li>拿到 renderScene 在屏幕上的位置和大小</li>
     *   <li>建一个 96x96 FBO</li>
     *   <li>把 Scene 渲染到 FBO</li>
     *   <li>readPixels 出来, 调 ThumbnailCache.captureCurrentFrame 保存</li>
     * </ol>
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
        try {
            // 1. 拿到 Scene 在屏幕上的位置
            int sceneScreenX = (int) renderScene.getPositionX();
            int sceneScreenY = (int) renderScene.getPositionY();
            int sceneScreenW = (int) renderScene.getSizeWidth();
            int sceneScreenH = (int) renderScene.getSizeHeight();
            if (sceneScreenW <= 0 || sceneScreenH <= 0) {
                showStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.thumb_not_drawn"), 0xFF5555, 60);
                return;
            }
            PrefabCustomAddon.LOGGER.info("[DETAIL] 截屏: scenePos=({},{}) size={}x{}",
                sceneScreenX, sceneScreenY, sceneScreenW, sceneScreenH);

            // 2. 在屏幕上画一个 96x96 的隐藏区域, 让 Scene 渲染到那
            //    简单做法: 改 Scene 大小到 96x96, 触发一次重绘, 然后从 framebuffer 读
            //    但 LDLib2 的 Scene 是通过 layout 定位, 改 size 不一定立即生效
            //
            //    更稳妥: 直接从 framebuffer 读 Scene 当前位置的内容
            //    Minecraft 默认 framebuffer 是屏幕的 (guiScale 缩放后), 我们的屏幕坐标跟
            //    framebuffer 坐标在 guiScale 下不同. 但 Scene 渲染时, PoseStack 的 translate
            //    已经把屏幕坐标转换成 MC GUI 坐标了. 我们读 framebuffer 时用 (sceneScreenX,
            //    sceneScreenY) 即可, 因为 glReadPixels 接受窗口坐标.
            //
            // 3. 实际上 glReadPixels 是从 framebuffer 当前 framebuffer 读, 坐标是相对于
            //    framebuffer 的左下角. Minecraft 的 main framebuffer 大小 = 实际像素 (不是 GUI 单位).
            //    我们需要 guiScale 缩放系数.
            //
            //    简单方案: 用 1.21.1 的 Minecraft.getInstance().getMainRenderTarget() 拿到 framebuffer,
            //    然后用它的尺寸 + 我们的 GUI 坐标算出像素位置.

            int mcWinW = Minecraft.getInstance().getWindow().getWidth();
            int mcWinH = Minecraft.getInstance().getWindow().getHeight();
            int guiScale = Minecraft.getInstance().options.guiScale().get();
            if (guiScale == 0) guiScale = 1;
            int fbW = mcWinW;
            int fbH = mcWinH;
            // framebuffer 坐标 = 屏幕坐标 * (实际像素 / 屏幕 GUI 像素)
            // GUI 单位下 width=Minecraft.getInstance().getWindow().getGuiScaledWidth()
            int guiW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
            int guiH = Minecraft.getInstance().getWindow().getGuiScaledHeight();
            double scaleX = (double) fbW / guiW;
            double scaleY = (double) fbH / guiH;
            int px = (int) (sceneScreenX * scaleX);
            int py_fb = (int) ((guiH - sceneScreenY - sceneScreenH) * scaleY);  // GL 是从下往上
            int pw = (int) (sceneScreenW * scaleX);
            int ph = (int) (sceneScreenH * scaleY);

            // 截屏 scene 区域
            java.nio.ByteBuffer buf = org.lwjgl.BufferUtils.createByteBuffer(pw * ph * 4);
            org.lwjgl.opengl.GL11.glReadPixels(px, py_fb, pw, ph,
                org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, buf);

            // 缩放到 96x96 (直接用 Java 缩放, 简单)
            com.mojang.blaze3d.platform.NativeImage fullImg =
                new com.mojang.blaze3d.platform.NativeImage(pw, ph, false);
            byte[] row = new byte[pw * 4];
            for (int y = 0; y < ph; y++) {
                buf.position((ph - 1 - y) * pw * 4);
                buf.get(row);
                for (int x = 0; x < pw; x++) {
                    int r = row[x * 4] & 0xFF;
                    int g = row[x * 4 + 1] & 0xFF;
                    int b = row[x * 4 + 2] & 0xFF;
                    int a = row[x * 4 + 3] & 0xFF;
                    int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                    fullImg.setPixelRGBA(x, y, abgr);
                }
            }
            // Resize 到 96x96
            int targetSize = 96;
            com.mojang.blaze3d.platform.NativeImage thumb =
                new com.mojang.blaze3d.platform.NativeImage(targetSize, targetSize, false);
            for (int ty = 0; ty < targetSize; ty++) {
                int sy = (int) ((double) ty / targetSize * ph);
                for (int tx = 0; tx < targetSize; tx++) {
                    int sx = (int) ((double) tx / targetSize * pw);
                    thumb.setPixelRGBA(tx, ty, fullImg.getPixelRGBA(sx, sy));
                }
            }
            fullImg.close();

            // 写盘
            java.nio.file.Path dir = com.prefab.addon.client.ThumbnailCache.getCacheDir();
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path file = dir.resolve(
                com.prefab.addon.client.ThumbnailCache.fingerprint(currentConstruction) + ".png");
            thumb.writeToFile(file);
            thumb.close();
            com.prefab.addon.client.ThumbnailCache.notifyCompleted(
                com.prefab.addon.client.ThumbnailCache.fingerprint(currentConstruction));
            showStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.thumb_saved", file.getFileName().toString()), 0x55FF55, 60);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[DETAIL] captureThumbnail failed: {}", t.getMessage(), t);
            showStatus(com.prefab.addon.PrefabCustomAddon.tr("gui.detail.screenshot_fail", t.getMessage()), 0xFF5555, 60);
        }
    }

    // ====================================================================
    // 删除建筑 (从详情页底部按钮)
    //   - 仅支持单文件建筑 (.nbt + .txt + .png 三件套平铺在 prefab-extension/ 或 prefab-download/),
    //     zip 拓展包内的建筑不支持 (要回 Editor 那边改 zip)
    //   - 走二次确认: 先点按钮 → 弹 LdLib 确认窗口 → 真的点了「确认删除」才删
    // ====================================================================

    /**
     * 二次确认弹窗: 玩家点底部「删除建筑」后, 弹出这个小窗.
     * 确认后调 {@link #doDeleteBuilding(ConstructionInfo)} 实际删.
     * 静态方法: 内部 lambda 从 createUI() 里调, createUI 是 static, 这边也必须 static.
     */
    private static void confirmDeleteBuilding(ConstructionInfo construction) {
        String title = com.prefab.addon.PrefabCustomAddon.tr("gui.detail.delete_confirm_title",
            construction.getName());

        com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen screen =
            new com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen(
                com.lowdragmc.lowdraglib2.gui.ui.ModularUI.of(
                    com.lowdragmc.lowdraglib2.gui.ui.UI.of(buildDeleteConfirmRoot(construction),
                        com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager.INSTANCE
                            .getStylesheetSafe(com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager.MC))),
                net.minecraft.network.chat.Component.literal(title));

        Minecraft.getInstance().setScreen(screen);
    }

    /** 构造二次确认弹窗的 root 节点. */
    private static com.lowdragmc.lowdraglib2.gui.ui.UIElement buildDeleteConfirmRoot(ConstructionInfo construction) {
        com.lowdragmc.lowdraglib2.gui.ui.UIElement root = new com.lowdragmc.lowdraglib2.gui.ui.UIElement();
        root.layout(l -> l.width(280).height(140)
            .flexDirection(dev.vfyjxf.taffy.style.FlexDirection.COLUMN));
        root.style(s -> s.background(com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites.BORDER));

        // 标题
        com.lowdragmc.lowdraglib2.gui.ui.UIElement titleBar = new com.lowdragmc.lowdraglib2.gui.ui.UIElement();
        titleBar.layout(l -> l.widthPercent(100).height(24)
            .paddingHorizontal(8)
            .justifyContent(dev.vfyjxf.taffy.style.AlignContent.CENTER));
        titleBar.style(s -> s.background(com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites.RECT_DARK));
        com.lowdragmc.lowdraglib2.gui.ui.elements.Label title = new com.lowdragmc.lowdraglib2.gui.ui.elements.Label();
        title.setText(net.minecraft.network.chat.Component.literal(
            "§c§l" + com.prefab.addon.PrefabCustomAddon.tr("gui.detail.delete_confirm_title",
                construction.getName())));
        title.textStyle(t -> t.textAlignHorizontal(com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal.CENTER));
        titleBar.addChild(title);
        root.addChild(titleBar);

        // 提示文字
        com.lowdragmc.lowdraglib2.gui.ui.UIElement body = new com.lowdragmc.lowdraglib2.gui.ui.UIElement();
        body.layout(l -> l.widthPercent(100).flex(1)
            .paddingAll(10)
            .flexDirection(dev.vfyjxf.taffy.style.FlexDirection.COLUMN)
            .gapAll(4));
        com.lowdragmc.lowdraglib2.gui.ui.elements.Label warn = new com.lowdragmc.lowdraglib2.gui.ui.elements.Label();
        warn.setText(net.minecraft.network.chat.Component.literal(
            "§7" + com.prefab.addon.PrefabCustomAddon.tr("gui.detail.delete_confirm_body",
                construction.getName())));
        warn.textStyle(t -> t.textWrap(com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap.WRAP)
            .adaptiveHeight(true));
        warn.layout(l -> l.widthPercent(100));
        body.addChild(warn);
        root.addChild(body);

        // 按钮行
        com.lowdragmc.lowdraglib2.gui.ui.UIElement btnRow = new com.lowdragmc.lowdraglib2.gui.ui.UIElement();
        btnRow.layout(l -> l.widthPercent(100).height(28)
            .paddingHorizontal(20).paddingVertical(4)
            .flexDirection(dev.vfyjxf.taffy.style.FlexDirection.ROW)
            .justifyContent(dev.vfyjxf.taffy.style.AlignContent.SPACE_BETWEEN));

        com.lowdragmc.lowdraglib2.gui.ui.elements.Button btnCancel = new com.lowdragmc.lowdraglib2.gui.ui.elements.Button();
        btnCancel.setText(net.minecraft.network.chat.Component.literal(
            "§7" + com.prefab.addon.PrefabCustomAddon.tr("gui.detail.delete_cancel")));
        btnCancel.layout(l -> l.width(80).height(20));
        btnCancel.setOnClick(e -> Minecraft.getInstance().setScreen(null));
        btnRow.addChild(btnCancel);

        com.lowdragmc.lowdraglib2.gui.ui.elements.Button btnOk = new com.lowdragmc.lowdraglib2.gui.ui.elements.Button();
        btnOk.setText(net.minecraft.network.chat.Component.literal(
            "§c§l" + com.prefab.addon.PrefabCustomAddon.tr("gui.detail.delete_ok")));
        btnOk.layout(l -> l.width(100).height(20));
        btnOk.setOnClick(e -> {
            doDeleteBuilding(construction);
        });
        btnRow.addChild(btnOk);

        root.addChild(btnRow);
        return root;
    }

    /** 实际执行删除: 单文件建筑 (本地有 .nbt/.txt/.png 的情况) 一律放行; 真没本地文件且是 zip 包内才拒绝. */
    private static void doDeleteBuilding(ConstructionInfo construction) {
        // 关闭确认弹窗
        Minecraft.getInstance().setScreen(null);
        Minecraft mc = Minecraft.getInstance();

        String cid = construction.getId();
        ExtensionPack packRef = construction.getPack();
        String packInfo = packRef == null ? "null" : packRef.getPackageName();
        java.nio.file.Path nbt = construction.getLocalNbtPath();
        String nbtInfo = nbt == null ? "null" : nbt.toString();
        PrefabCustomAddon.LOGGER.info("[DELETE] 开始删除 id='{}' pack={} localNbtPath={}", cid, packInfo, nbtInfo);

        // === 关键修复: 优先看本地文件, 不管 pack ===
        // 之前逻辑: construction.getPack() != null 就直接 return, 不删.
        // 但 GuiExtensionPackBrowser.getMergedConstructionsForBuildingsTab() 会把 LocalBuilding
        // 的字段合并到 pack 的 ConstructionInfo 上, 留下 pack 引用. 玩家看到的是"本地建筑",
        // 但代码把它当成"zip 内的", 拒绝删, 玩家还看不到错误 (setScreen(null) 在第一行就关了弹窗,
        // showStatus 写静态字段也没人渲染) → "按钮啥也没干".
        // 现在改成: 本地文件存在就以本地为准删 (不管 pack).
        if (nbt != null && java.nio.file.Files.exists(nbt)) {
            // === 本地文件存在, 删三件套 ===
            try {
                java.nio.file.Path dir = nbt.getParent();
                String name = nbt.getFileName().toString();
                int dot = name.lastIndexOf('.');
                if (dot <= 0) {
                    String msg = "§c[删除失败] 文件名无后缀: " + name;
                    PrefabCustomAddon.LOGGER.warn("[DELETE] {}", msg);
                    if (mc.player != null) mc.player.sendSystemMessage(Component.literal(msg));
                    return;
                }
                String base = name.substring(0, dot);
                int deleted = 0;
                for (String ext : java.util.Arrays.asList(".nbt", ".schem", ".schematic", ".litematic",
                                                           ".txt",
                                                           ".png", ".jpg", ".jpeg", ".gif", ".webp")) {
                    java.nio.file.Path p = dir.resolve(base + ext);
                    try {
                        if (java.nio.file.Files.deleteIfExists(p)) {
                            deleted++;
                            PrefabCustomAddon.LOGGER.info("[DELETE] 删除文件: {}", p);
                        }
                    } catch (Exception e) {
                        PrefabCustomAddon.LOGGER.warn("[DELETE] 删 {} 失败: {}", p, e.getMessage());
                    }
                }
                PrefabCustomAddon.LOGGER.info("[DELETE] 共删除 {} 个文件 (base={}, dir={})", deleted, base, dir);

                // 关掉详情页
                mc.setScreen(null);
                // 重扫 (zip pack 缓存, 不影响单文件但保险)
                try {
                    ExtensionPackManager.getInstance().forceReload();
                } catch (Throwable ignored) {}
                // 打开浏览器
                GuiExtensionPackBrowser.open();

                if (mc.player != null) {
                    mc.player.sendSystemMessage(Component.literal(
                        "§a✓ 已删除建筑 §f'" + cid + "'§a (§e" + deleted + "§a 个文件)"));
                }
            } catch (Exception ex) {
                PrefabCustomAddon.LOGGER.warn("[DELETE] 删除失败: {}", ex.getMessage(), ex);
                if (mc.player != null) {
                    mc.player.sendSystemMessage(Component.literal(
                        "§c[删除失败] " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage())));
                }
            }
            return;
        }

        // === 没本地文件, 看看 pack ===
        if (packRef != null) {
            String msg = "§c[删除失败] 该建筑在 zip 拓展包内, 没法从这里删 (包: " + packInfo + ")";
            PrefabCustomAddon.LOGGER.warn("[DELETE] {}", msg);
            if (mc.player != null) mc.player.sendSystemMessage(Component.literal(msg));
            return;
        }

        // 既没本地也没 pack, 异常状态
        String msg = "§c[删除失败] 建筑文件不存在, 也找不到包引用: " + cid;
        PrefabCustomAddon.LOGGER.warn("[DELETE] {}", msg);
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal(msg));
    }
}
