package com.prefab.addon.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.prefab.addon.blocks.MiniBuildingBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/**
 * 迷你建筑方块 BER - 渲染逻辑委托给 {@link MiniBuildingMeshCache}.
 *
 * <p><b>性能演进</b>:
 * <ul>
 *   <li>v1: 每帧对每个方块 {@code renderSingleBlock} (完整模型管线) +
 *       每帧重复 6 邻居内部裁剪 → 大建筑放置后严重掉帧.</li>
 *   <li>v2: 外壳方块一次性烘焙成 quad 缓存 (按 BlockState 共享模型解析),
 *       每帧只做 translate/scale + putBulkData; 内部裁剪在烘焙时一次完成.</li>
 *   <li>v3 (当前): 烘焙时追加面级剔除 (外壳方块之间贴合的面不画,
 *       实心大建筑几何量再降 2-4 倍); 超大建筑动态缩小详细距离;
 *       {@link #getViewDistance} 压到 33 (匹配 LOD 上限), 避免 64 格内每个迷你方块
 *       每帧都进 render().</li>
 * </ul>
 *
 * <p>LOD 策略: 8 格内详细渲染 (超大建筑动态缩小到 4-6), 详细距离-32 格跳过,
 * &gt; 32 格完全跳过.</p>
 */
public class MiniBuildingBlockRenderer implements BlockEntityRenderer<MiniBuildingBlockEntity> {

    public MiniBuildingBlockRenderer(BlockEntityRendererProvider.Context ctx) {
    }

    /**
     * 默认 64 格内的 BE 每帧都会调 render(). 我们的 LOD 上限是 32 格,
     * 超出部分进 render() 也是早退, 直接不让它们进.
     */
    @Override
    public int getViewDistance() {
        return 33;
    }

    @Override
    public void render(MiniBuildingBlockEntity be, float partialTicks, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        MiniBuildingMeshCache.render(be, partialTicks, poseStack, bufferSource, packedLight);
    }
}
