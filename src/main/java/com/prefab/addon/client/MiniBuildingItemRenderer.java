package com.prefab.addon.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.blocks.MiniBuildingBlockEntity.CachedBlockEntry;
import com.prefab.addon.blocks.MiniBuildingStorage;
import com.prefab.addon.client.MiniBuildingMeshCache.BakedBlock;
import com.prefab.addon.client.MiniBuildingMeshCache.BakedMesh;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/**
 * 手持/掉落物/物品栏 渲染器: 从 ItemStack 读 NBT (而不是 BE).
 * 通过 RegisterClientExtensionsEvent 注入到 MiniBuildingBlockItem
 * (1.21.1 中 Item.initializeClient 已 deprecated, 必须用 event 注册).
 *
 * <p><b>性能 (v2)</b>: 跟放置后的 BER 一样, 解析后一次性烘焙成
 * {@link BakedMesh} (内部裁剪 + 面级剔除, 见 {@link MiniBuildingMeshCache}),
 * 之后每帧只做 translate/scale + putBulkData. 之前每帧对每个外壳方块调
 * {@code renderSingleBlock} 完整模型管线, 大建筑放物品栏直接掉帧.
 * 缓存按 ItemStack 弱引用, 数据异步到达时 {@link #invalidateAll} 清空重烘.</p>
 */
public class MiniBuildingItemRenderer extends BlockEntityWithoutLevelRenderer {

    public static final MiniBuildingItemRenderer INSTANCE = new MiniBuildingItemRenderer();

    /** 按 ItemStack 缓存烘焙结果. ItemStack 是弱引用, GC 会自动清理. */
    private final WeakHashMap<ItemStack, BakedMesh> cache = new WeakHashMap<>();

    public MiniBuildingItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),
              Minecraft.getInstance().getEntityModels());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack,
                             MultiBufferSource buffer, int packedLight, int packedOverlay) {
        BakedMesh mesh = cache.get(stack);
        if (mesh == null) {
            mesh = parseAndBake(stack);
            cache.put(stack, mesh);
        }
        if (mesh.blocks().isEmpty()) return;

        VertexConsumer buf = buffer.getBuffer(
            RenderType.entityCutout(TextureAtlas.LOCATION_BLOCKS));
        float bs = mesh.blockSize();
        for (BakedBlock bb : mesh.blocks()) {
            poseStack.pushPose();
            poseStack.translate(bb.px(), bb.py(), bb.pz());
            poseStack.scale(bs, bs, bs);
            for (BakedQuad quad : bb.quads()) {
                buf.putBulkData(poseStack.last(), quad, bb.r(), bb.g(), bb.b(), 1.0f,
                    packedLight, packedOverlay, false);
            }
            poseStack.popPose();
        }
    }

    private BakedMesh parseAndBake(ItemStack stack) {
        var data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (data == null) return BakedMesh.EMPTY;
        CompoundTag beTag = data.copyTag();
        CompoundTag mb = beTag.contains("MiniBuilding") ? beTag.getCompound("MiniBuilding") : beTag;
        if (mb.isEmpty()) return BakedMesh.EMPTY;

        // [FIX] 物品只存引用 NBT (完整数据在服务端外部文件, 避开 2MB 限制).
        //   之前直接读 width/blocks 字段 → 全是 0/空 → 物品栏什么都不画.
        //   现在: 从客户端缓存拿完整数据; 未命中时触发异步请求, 本帧暂不渲染.
        CompoundTag full;
        if (MiniBuildingStorage.isReference(mb)) {
            String refId = mb.getString(MiniBuildingStorage.KEY_REF_ID);
            if (refId.isEmpty()) return BakedMesh.EMPTY;
            full = MiniBuildingItemDataCache.get(refId);
            if (full == null) {
                MiniBuildingItemDataCache.requestIfNeeded(refId);
                return BakedMesh.EMPTY;
            }
        } else if (mb.contains("blocks")) {
            full = mb;  // 老格式: 完整数据内嵌在物品里 (1.9 之前捕获的)
        } else {
            return BakedMesh.EMPTY;
        }

        Minecraft mc = Minecraft.getInstance();
        List<CachedBlockEntry> list;
        try {
            list = parseBlocks(full);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.debug("[MINI_BUILDING] item-parse failed: {}", t.getMessage());
            return BakedMesh.EMPTY;
        }
        // tint 用玩家脚下的位置取色; 没有世界 (主菜单/掉落物渲染兜底) 就保持白色.
        // [PERF] 传几何预算: 大建筑超预算时体素降采样 (图标尺寸下几万块纯属浪费,
        //   两个大建筑在快捷栏就能把帧数打到三分之一). 放置后 BER 走无预算版, 全精度.
        BlockPos tintPos = mc.player != null ? mc.player.blockPosition() : BlockPos.ZERO;
        return MiniBuildingMeshCache.bake(mc.getBlockRenderer(), mc.level, tintPos,
            full.getInt("width"), full.getInt("height"), full.getInt("depth"),
            list, MiniBuildingMeshCache.ITEM_BLOCK_BUDGET);
    }

    /**
     * 解析 blocks 列表: 新格式是 ListTag&lt;CompoundTag&gt;, 老格式 (&lt; 1.9) 是
     * CompoundTag + String key. 两种都兼容.
     */
    private static List<CachedBlockEntry> parseBlocks(CompoundTag full) {
        List<CachedBlockEntry> list = new ArrayList<>();
        if (full.contains("blocks", Tag.TAG_LIST)) {
            ListTag blocksList = full.getList("blocks", Tag.TAG_COMPOUND);
            for (int i = 0; i < blocksList.size(); i++) {
                addEntry(list, blocksList.getCompound(i));
            }
        } else {
            CompoundTag blocksTag = full.getCompound("blocks");
            for (String key : blocksTag.getAllKeys()) {
                addEntry(list, blocksTag.getCompound(key));
            }
        }
        return list;
    }

    private static void addEntry(List<CachedBlockEntry> list, CompoundTag entry) {
        try {
            int bx = entry.getInt("x");
            int by = entry.getInt("y");
            int bz = entry.getInt("z");
            CompoundTag stateTag = entry.getCompound("state");
            var state = com.prefab.addon.cloud.CloudBuilding.readBlockState(stateTag);
            if (state == null || state.isAir()) return;
            list.add(new CachedBlockEntry(bx, by, bz, state));
        } catch (Throwable ignored) {
            // 单个方块解析失败不影响整体
        }
    }

    /**
     * 完整数据刚到达客户端时调用: 清空物品级缓存, 让下一帧重新解析+烘焙.
     * 不清的话, 之前缓存的"空结果" (数据未加载) 会一直命中, 永远不画.
     */
    public static void invalidateAll() {
        INSTANCE.cache.clear();
    }
}
