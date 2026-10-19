package com.prefab.addon.mixin;

import com.lowdragmc.lowdraglib2.client.scene.WorldSceneRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 场景 TESR 兜底: LDLib2 的 3D 预览场景会渲染复制世界里的所有方块实体 (TESR),
 * 个别第三方 TESR 在假世界 (DummyWorld) 里拿不到运行时数据直接 NPE
 * (例如 通用机械:扩展 的管道渲染器 RenderExtraMechanicalPipe, network 为 null),
 * 会把整个预览界面带崩 → 这里把单个方块实体的渲染调用包上 try-catch,
 * 某个 TESR 崩了就跳过并记日志, 不再炸掉整个界面。
 *
 * 注: WorldSceneRenderer 是 LDLib2 自己的类, 不参与 MC 混淆重映射 (remap = false);
 *     但 @At 里的 BlockEntityRenderer.render 是 MC 方法, 需要参与重映射 (remap = true)。
 */
@Mixin(value = WorldSceneRenderer.class, remap = false)
public abstract class WorldSceneRendererMixin {

    @Redirect(method = "renderTESR",
        at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V"))
    private void prefabCustomAddon$safeRenderTESR(BlockEntityRenderer instance, BlockEntity tileEntity,
                                                   float partialTicks, PoseStack poseStack,
                                                   MultiBufferSource buffer, int light, int overlay) {
        try {
            instance.render(tileEntity, partialTicks, poseStack, buffer, light, overlay);
        } catch (Throwable t) {
            Logger log = LoggerFactory.getLogger("prefab-custom-addon");
            log.warn("[Scene] 跳过崩溃的方块实体渲染: {} @ {}",
                tileEntity.getBlockState().getBlock(), tileEntity.getBlockPos(), t);
        }
    }
}
