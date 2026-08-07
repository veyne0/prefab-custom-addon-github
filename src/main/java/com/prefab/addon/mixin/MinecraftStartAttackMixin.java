package com.prefab.addon.mixin;

import com.prefab.addon.work.RegionSelector;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 拦截 Minecraft.startAttack() 和 continueAttack() 阻止选区时破坏方块.
 * PlayerInteractEvent.LeftClickBlock 在 1.21.1 客户端不触发, 必须用 mixin 拦截.
 */
@Mixin(Minecraft.class)
public class MinecraftStartAttackMixin {

    /**
     * 取消 startAttack() - 防止点击破坏方块/攻击实体.
     * startAttack() 返回 boolean, 用 CallbackInfoReturnable<Boolean>.
     *
     * 修复: SHIFT 临时解锁 (按住 SHIFT) 时不取消, 让玩家正常挖方块.
     *   之前没有 isTempUnlocked 检查, 导致选区模式按住 SHIFT 也挖不动.
     */
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void prefabAddon$onStartAttack(CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = (Minecraft) (Object) this;
        if (mc.player == null) return;
        if (RegionSelector.isActive(mc.player)
            && !RegionSelector.isTempUnlocked(mc.player)) {
            cir.setReturnValue(false);
            cir.cancel();
        }
    }

    /**
     * 取消 continueAttack() - 防止按住左键持续破坏方块.
     * 即便 startAttack 被取消, continueAttack 每 tick 仍可能调 gameMode.continueDestroyBlock
     * 最终走回 startDestroyBlock (create destroy 状态), 实际破坏方块.
     *
     * 修复: SHIFT 临时解锁时不取消.
     */
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void prefabAddon$onContinueAttack(boolean leftClick, CallbackInfo ci) {
        if (!leftClick) return;  // 只阻止左键, 不阻止右键 (右键走 startUseItem)
        Minecraft mc = (Minecraft) (Object) this;
        if (mc.player == null) return;
        if (RegionSelector.isActive(mc.player)
            && !RegionSelector.isTempUnlocked(mc.player)) {
            ci.cancel();
        }
    }
}
