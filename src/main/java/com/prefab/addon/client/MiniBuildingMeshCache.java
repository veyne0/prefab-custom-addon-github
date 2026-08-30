package com.prefab.addon.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.blocks.MiniBuildingBlockEntity;
import com.prefab.addon.blocks.MiniBuildingBlockEntity.CachedBlockEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.LongFunction;

/**
 * 迷你建筑烘焙中心: 放置后的 BER ({@link MiniBuildingBlockRenderer}) 和
 * 物品栏/手持渲染器 ({@link MiniBuildingItemRenderer}) 共用.
 *
 * <p><b>为什么烘焙</b>: 之前每帧对每个方块调 {@code renderSingleBlock}
 * (完整模型管线: 模型查找 → 7 方向 getQuads → tint → AO). 上万块的大建筑
 * 无论放在物品栏还是放置后, 每帧几千次完整渲染 → 严重掉帧.</p>
 *
 * <p>迷你建筑是静态的, 只烘焙一次:
 * <ul>
 *   <li>内部方块裁剪 (6 邻居全在建筑内 → 看不见, 跳过), 只留外壳</li>
 *   <li><b>面级剔除</b>: 外壳方块之间贴合的面也不画 — quad 方向上相邻方块
 *       存在且双方该面都是完整硬面 (isFaceSturdy) 时, 该 quad 被夹住看不见.
 *       实心大建筑外壳几何量能再降 2-4 倍</li>
 *   <li>按 BlockState 缓存 quad 列表 (同状态多个方块共享, 不重复解析模型)</li>
 *   <li>烘焙结果 (位置偏移 + tint + 过滤后 quads) 按 BE/ItemStack 缓存</li>
 * </ul>
 * 每帧渲染只剩 translate/scale + putBulkData, 没有任何每帧模型解析.</p>
 *
 * <p><b>为什么不缓存 VertexBuffer</b>: 跟 {@link CustomStructurePreviewRenderer}
 * 的取舍一致 — VertexBuffer API 复杂, 跨版本易错, Shader/RenderSystem 状态容易
 * 污染. quad 烘焙 + putBulkData 走的是本项目已验证的快速路径 (跟建造动画渲染器
 * 同套路), 已经省掉了每帧最贵的模型解析 + 每帧剔除部分.</p>
 *
 * <p><b>渲染管线取舍</b>: 统一走 {@code entityCutout} alpha-test 路径 (不闪烁),
 * 玻璃/半透明方块不做混合 — 跟项目其他预览渲染器同一个 trade-off.</p>
 */
@EventBusSubscriber(modid = PrefabCustomAddon.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class MiniBuildingMeshCache {

    private static final float PADDING = 0.05f;
    private static final float RENDER_MIN = PADDING;
    private static final float RENDER_MAX = 1.0f - PADDING;
    /** 0.2% 缝隙, 刚好避免相邻方块 z-fighting, 又不让人眼看到. */
    private static final float SHRINK = 0.998f;
    /** < 8 格: 详细渲染 (超大建筑动态缩小, 见 detailDistance). */
    private static final double LOD_DETAILED = 8.0;
    /** > 32 格: 完全跳过. */
    private static final double LOD_OUTLINE = 32.0;

    /**
     * 物品渲染几何预算: 超过则体素降采样 (每轮 2×2×2 合 1, 几何量除 8).
     * 16×16 快捷栏图标/背包 ~32px 展示下, 几千块已远超像素级需求;
     * 几万块的完整外壳每帧提交会把帧数打到三分之一.
     */
    public static final int ITEM_BLOCK_BUDGET = 2500;

    /** 烘焙后的单个方块: 相对原点偏移 (已含等比缩放布局) + tint + 过滤后的 quads. */
    public record BakedBlock(float px, float py, float pz,
                             float r, float g, float b, List<BakedQuad> quads) {}

    /** 烘焙结果: 等比缩放布局的方块尺寸 + 外壳方块列表. */
    public record BakedMesh(float blockSize, List<BakedBlock> blocks) {
        public static final BakedMesh EMPTY = new BakedMesh(0f, List.of());
    }

    private static final class Entry {
        long version = -1;
        BakedMesh mesh = BakedMesh.EMPTY;
    }

    /** BE → 烘焙结果. WeakHashMap: BE 随方块破坏被回收, 缓存自动清理. */
    private static final Map<MiniBuildingBlockEntity, Entry> CACHE =
        Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * BlockState → quads. BlockState 实例是注册表常驻单例, 该缓存长期有效;
     * 资源重载 (换材质包/模型重烘焙) 时通过 reload listener 清空.
     */
    private static final Map<BlockState, List<BakedQuad>> QUAD_CACHE = new HashMap<>();

    private static final RandomSource RNG = RandomSource.create(42L);

    private MiniBuildingMeshCache() {}

    /** 资源重载时清缓存 (模型/贴图变了, 旧 quads 失效). */
    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        // registerReloadListener 参数是 PreparableReloadListener (非函数式接口),
        // 必须显式转成其函数式子接口 ResourceManagerReloadListener 才能用 lambda.
        event.registerReloadListener((ResourceManagerReloadListener) manager -> clearAll());
    }

    public static void clearAll() {
        CACHE.clear();
        QUAD_CACHE.clear();
    }

    // ==================== 放置后 BER 入口 ====================

    /**
     * BER 入口: 渲染一个迷你建筑方块 (含 LOD + 烘焙缓存).
     */
    public static void render(MiniBuildingBlockEntity be, float partialTicks, PoseStack poseStack,
                              MultiBufferSource bufferSource, int packedLight) {
        int w = be.getWidth();
        int h = be.getHeight();
        int d = be.getDepth();
        if (w <= 0 || h <= 0 || d <= 0) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Vec3 cam = mc.player.getEyePosition(partialTicks);
        BlockPos pos = be.getBlockPos();
        double distSq = cam.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

        // === 激进 LOD: 远距离直接跳过 ===
        if (distSq > LOD_OUTLINE * LOD_OUTLINE) return;

        List<CachedBlockEntry> blocks = be.getCachedBlocks();
        if (blocks.isEmpty()) return;

        Entry entry = getOrBuild(be, mc, blocks, w, h, d);
        if (entry == null || entry.mesh.blocks().isEmpty()) return;

        // 中距离跳过; 超大建筑 (外壳方块多) 动态缩小详细距离, 压住每帧提交量
        double detail = detailDistance(entry.mesh.blocks().size());
        if (distSq > detail * detail) return;

        VertexConsumer buf = bufferSource.getBuffer(
            RenderType.entityCutout(TextureAtlas.LOCATION_BLOCKS));
        float bs = entry.mesh.blockSize();
        for (BakedBlock bb : entry.mesh.blocks()) {
            poseStack.pushPose();
            poseStack.translate(bb.px(), bb.py(), bb.pz());
            poseStack.scale(bs, bs, bs);
            for (BakedQuad quad : bb.quads()) {
                buf.putBulkData(poseStack.last(), quad, bb.r(), bb.g(), bb.b(), 1.0f,
                    packedLight, OverlayTexture.NO_OVERLAY, false);
            }
            poseStack.popPose();
        }
    }

    /** 外壳方块越多, 详细渲染距离越小 (每帧几何量 ≈ 外壳方块数 × 平均 quad 数). */
    private static double detailDistance(int visibleBlocks) {
        if (visibleBlocks <= 8000) return LOD_DETAILED;
        if (visibleBlocks <= 25000) return 6.0;
        return 4.0;
    }

    /**
     * 取烘焙结果; dataVersion 变化 (新数据到达) 时重新烘焙.
     */
    private static Entry getOrBuild(MiniBuildingBlockEntity be, Minecraft mc,
                                    List<CachedBlockEntry> blocks, int w, int h, int d) {
        long version = be.getDataVersion();
        Entry entry = CACHE.get(be);
        if (entry != null && entry.version == version) return entry;

        entry = new Entry();
        entry.version = version;
        entry.mesh = bake(mc.getBlockRenderer(), mc.level, be.getBlockPos(), w, h, d, blocks);
        CACHE.put(be, entry);
        PrefabCustomAddon.LOGGER.debug(
            "[MINI_BUILDING] mesh baked @ {}: {} visible blocks (of {}), version={}",
            be.getBlockPos(), entry.mesh.blocks().size(), blocks.size(), version);
        return entry;
    }

    // ==================== 通用烘焙 (BER + 物品栏共用) ====================

    /** 无预算版 (放置后 BER 用, 保持全精度). */
    public static BakedMesh bake(BlockRenderDispatcher blockRenderer, Level tintLevel, BlockPos tintPos,
                                 int w, int h, int d, List<CachedBlockEntry> blocks) {
        return bake(blockRenderer, tintLevel, tintPos, w, h, d, blocks, 0);
    }

    /**
     * 把方块列表烘焙成网格. 幂等且无副作用, BER 和物品栏渲染器共用.
     *
     * @param tintLevel tint 取色用的世界, 可为 null (主菜单/数据未到达时不染草色)
     * @param tintPos   tint 取色位置 (用放置位置的环境色)
     * @param blocks    原始方块列表 (允许含内部方块, 烘焙时裁剪)
     * @param maxBlocks 几何预算 (&lt;=0 不限制). 超过时先体素降采样再烘焙,
     *                  用于物品栏: 图标尺寸下轮廓完整比细节重要.
     */
    public static BakedMesh bake(BlockRenderDispatcher blockRenderer, Level tintLevel, BlockPos tintPos,
                                 int w, int h, int d, List<CachedBlockEntry> blocks, int maxBlocks) {
        if (w <= 0 || h <= 0 || d <= 0 || blocks.isEmpty()) return BakedMesh.EMPTY;

        // 超预算: 体素降采样 (2×2×2 合 1, 保留轮廓, 不留洞). 降完再走正常烘焙,
        // 内部/面级裁剪在粗网格上再做一遍, 几何量进一步下降.
        if (maxBlocks > 0 && blocks.size() > maxBlocks) {
            Decimated dec = decimate(blocks, w, h, d, maxBlocks);
            blocks = dec.blocks();
            w = dec.w(); h = dec.h(); d = dec.d();
            PrefabCustomAddon.LOGGER.debug(
                "[MINI_BUILDING] item mesh decimated to {} blocks ({}x{}x{})",
                blocks.size(), w, h, d);
        }

        // === 等比例缩放布局 ===
        float maxDim = Math.max(w, Math.max(h, d));
        float bs = (RENDER_MAX - RENDER_MIN) / maxDim * SHRINK;
        float startX = 0.5f - w * bs * 0.5f;
        float startY = 0.5f - h * bs * 0.5f;
        float startZ = 0.5f - d * bs * 0.5f;

        // 位置集合: 内部裁剪 (6 邻居) 用
        Set<Long> posSet = new java.util.HashSet<>(blocks.size() * 2);
        // 位置 → state: 面级剔除时查邻居状态用 (烘焙期临时表, 用完即弃)
        Map<Long, BlockState> stateMap = new HashMap<>(blocks.size() * 2);
        for (CachedBlockEntry e : blocks) {
            long key = packPos(e.x(), e.y(), e.z());
            posSet.add(key);
            stateMap.put(key, e.state());
        }

        List<BakedBlock> list = new ArrayList<>();
        for (CachedBlockEntry e : blocks) {
            try {
                int bx = e.x(), by = e.y(), bz = e.z();
                BlockState state = e.state();
                if (state == null || state.isAir()) continue;

                // 流体: 强制 defaultBlockState (满流体), 且不做内部/面级裁剪
                boolean isFluid = state.getBlock() instanceof LiquidBlock;
                BlockState renderState = isFluid ? state.getBlock().defaultBlockState() : state;
                if (!isFluid && isInternal(bx, by, bz, w, h, d, posSet::contains)) continue;

                List<BakedQuad> quads = getQuadsCached(blockRenderer, renderState);
                if (quads.isEmpty()) continue;

                // 面级剔除: 被邻居硬面完全夹住的 quad 不画 (流体除外)
                List<BakedQuad> kept = filterQuads(quads, renderState, isFluid,
                    bx, by, bz, stateMap::get);

                float r = 1.0f, g = 1.0f, b = 1.0f;
                if (!isFluid && tintLevel != null) {
                    // tint (草/树叶等). 用放置位置取色, 跟实际环境光一致
                    int color = Minecraft.getInstance().getBlockColors()
                        .getColor(renderState, tintLevel, tintPos, 0);
                    r = (float) (color >> 16 & 255) / 255.0f;
                    g = (float) (color >> 8 & 255) / 255.0f;
                    b = (float) (color & 255) / 255.0f;
                }
                list.add(new BakedBlock(
                    startX + bx * bs, startY + by * bs, startZ + bz * bs,
                    r, g, b, kept));
            } catch (Throwable t) {
                // 单个方块烘焙失败不影响整体
            }
        }
        return new BakedMesh(bs, list);
    }

    /** 降采样结果: 新尺寸 + 合并后的方块列表. */
    private record Decimated(int w, int h, int d, List<CachedBlockEntry> blocks) {}

    /**
     * 体素降采样: 反复把坐标除 2 (2×2×2 区域合 1 格, 取首个非空气状态),
     * 直到方块数 ≤ budget. 轮廓保形: 实心区域合并后仍实心, 不会像随机抽样那样留洞.
     */
    private static Decimated decimate(List<CachedBlockEntry> blocks, int w, int h, int d, int budget) {
        List<CachedBlockEntry> cur = blocks;
        int cw = w, ch = h, cd = d;
        while (cur.size() > budget && cw > 1 && ch > 1 && cd > 1) {
            Map<Long, CachedBlockEntry> merged = new HashMap<>(cur.size() / 4 + 16);
            for (CachedBlockEntry e : cur) {
                int nx = e.x() >> 1, ny = e.y() >> 1, nz = e.z() >> 1;
                merged.putIfAbsent(packPos(nx, ny, nz), new CachedBlockEntry(nx, ny, nz, e.state()));
            }
            cur = new ArrayList<>(merged.values());
            cw = (cw + 1) >> 1; ch = (ch + 1) >> 1; cd = (cd + 1) >> 1;
        }
        return new Decimated(cw, ch, cd, cur);
    }

    /**
     * 面级剔除: quad 朝某方向且该方向邻居存在时, 只有当 "本方块该面是完整硬面
     * 且邻居对面也是完整硬面" 才剔除 — 跟 vanilla chunk mesher 的安全条件一致,
     * 不会误删半砖/楼梯等露出来的几何.
     *
     * @return 过滤后的列表; 如果一个都没剔除, 直接返回原共享列表 (省内存)
     */
    private static List<BakedQuad> filterQuads(List<BakedQuad> quads, BlockState state, boolean isFluid,
                                               int bx, int by, int bz, LongFunction<BlockState> neighbor) {
        if (isFluid) return quads;
        List<BakedQuad> kept = null;
        for (int i = 0; i < quads.size(); i++) {
            BakedQuad q = quads.get(i);
            if (shouldCullFace(q, state, bx, by, bz, neighbor)) {
                if (kept == null) {
                    kept = new ArrayList<>(quads.size());
                    for (int j = 0; j < i; j++) kept.add(quads.get(j));
                }
            } else if (kept != null) {
                kept.add(q);
            }
        }
        return kept == null ? quads : kept;
    }

    private static boolean shouldCullFace(BakedQuad quad, BlockState state,
                                          int bx, int by, int bz, LongFunction<BlockState> neighbor) {
        Direction dir = quad.getDirection();
        if (dir == null) return false;  // 跨方向几何 (交叉草/藤蔓), 永远保留
        BlockState nb = neighbor.apply(packPos(bx + dir.getStepX(), by + dir.getStepY(), bz + dir.getStepZ()));
        if (nb == null) return false;
        // EmptyBlockGetter: 不需要真实世界, 纯形状判断
        return state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, dir)
            && nb.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, dir.getOpposite());
    }

    /**
     * BlockState → quads 缓存. 模型查找 + 7 方向 getQuads 只在第一次做,
     * 之后同状态的所有方块共享这份列表. 物品栏渲染器也复用.
     */
    public static List<BakedQuad> getQuadsCached(BlockRenderDispatcher blockRenderer, BlockState state) {
        List<BakedQuad> cached = QUAD_CACHE.get(state);
        if (cached != null) return cached;
        List<BakedQuad> all = new ArrayList<>();
        try {
            var model = blockRenderer.getBlockModel(state);
            RNG.setSeed(42L);
            // null = 非朝向相关 quads (草/藤蔓等跨方向几何)
            all.addAll(model.getQuads(state, null, RNG));
            for (Direction dir : Direction.values()) {
                all.addAll(model.getQuads(state, dir, RNG));
            }
        } catch (Throwable t) {
            // 模型解析失败: 记空列表, 该状态不渲染
        }
        List<BakedQuad> result = all.isEmpty() ? List.of() : all;
        QUAD_CACHE.put(state, result);
        return result;
    }

    /** 6 邻居全在建筑内 → 完全被包裹, 玩家看不见, 跳过. */
    private static boolean isInternal(int x, int y, int z, int w, int h, int d,
                                      java.util.function.LongPredicate contains) {
        if (x - 1 < 0 || x + 1 >= w) return false;
        if (y - 1 < 0 || y + 1 >= h) return false;
        if (z - 1 < 0 || z + 1 >= d) return false;
        if (!contains.test(packPos(x - 1, y, z))) return false;
        if (!contains.test(packPos(x + 1, y, z))) return false;
        if (!contains.test(packPos(x, y - 1, z))) return false;
        if (!contains.test(packPos(x, y + 1, z))) return false;
        if (!contains.test(packPos(x, y, z - 1))) return false;
        if (!contains.test(packPos(x, y, z + 1))) return false;
        return true;
    }

    /** 跟 {@link MiniBuildingBlockEntity#packPosExternal} 同一编码. */
    private static long packPos(int x, int y, int z) {
        return ((long) (x & 0xFFFF) << 32) | ((long) (y & 0xFFFF) << 16) | (long) (z & 0xFFFF);
    }
}
