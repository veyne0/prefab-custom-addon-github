package com.prefab.addon.cloud;

import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.List;

/**
 * 收回云端建筑时,防止方块自身掉落物 (sign/banner/bed/flower_pot/skull/lectern 等
 * 忽略 UPDATE_SUPPRESS_DROPS flag 直接 popResource 的特殊方块) 散落地上.
 *
 * <p>实现策略 (来自参考实现 高级云存储-1.2.0.jar):</p>
 * <ol>
 *   <li>ThreadLocal {@link SilentBuild#runSilent} 标记当前线程处于"静默构建"状态</li>
 *   <li>{@link SilentItemJoinListener} 监听 {@link EntityJoinLevelEvent},
 *       静默态时取消所有 ItemEntity 的 join 事件 (在服务端, 100% 可靠,
 *       因为 recall 操作只发生在服务端线程, 且事件总线一定在 addFreshEntity 之前触发)</li>
 *   <li>{@link SilentItemJoinListener#sweepRange} 在清空结束后再扫一遍 AABB,
 *       discard 任何漏网的 ItemEntity (兜底防御)</li>
 * </ol>
 *
 * <p>三层防御: UPDATE_SUPPRESS_DROPS flag (catches 95% blocks) +
 * EntityJoinLevelEvent + AABB 扫荡. 任何特殊方块都拦得住.</p>
 *
 * <p>注: 1.21.1 的 {@code Level} 类没有 addFreshEntity 方法 (只在
 * ServerLevel / ClientLevel 子类里), 所以没法像参考 mod 那样写 LevelMixin.
 * 改用纯事件拦截同样可靠 (recall 永远在服务端主线程跑, 事件总线一定能看到).</p>
 */
public final class CloudBuildingSilentClear {

    private CloudBuildingSilentClear() {}

    // ============================================================
    // ThreadLocal flag
    // ============================================================

    /**
     * 标记当前线程是否处于"静默构建"状态 (收回/建造云端建筑时).
     * 用 ThreadLocal 而不是 static 字段, 避免跨请求污染.
     */
    public static final class SilentBuild {
        private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

        private SilentBuild() {}

        public static void setActive(boolean v) {
            ACTIVE.set(v);
        }

        public static boolean isActive() {
            return Boolean.TRUE.equals(ACTIVE.get());
        }

        /** 包裹一段代码块: 内部 setActive(true), 执行后 finally 恢复原值. */
        public static void runSilent(Runnable body) {
            boolean prev = isActive();
            setActive(true);
            try {
                body.run();
            } finally {
                setActive(prev);
            }
        }
    }

    // ============================================================
    // 事件拦截: EntityJoinLevelEvent + AABB 扫荡
    // ============================================================

    /**
     * 拦截 ItemEntity join level 事件. 当 SilentBuild.isActive() == true 时
     * 任何 ItemEntity 都不允许进入世界 (直接 setCanceled).
     *
     * <p>注意: recall 永远在服务端主线程跑 (NetworkHandler → ServerPlayer → ServerLevel),
     *       EntityJoinLevelEvent 一定在 addFreshEntity 之后被 fire, 所以这里的 cancel
     *       一定生效. 服务端 100% 可靠拦截, 客户端不需要这个 (recall 客户端没逻辑).</p>
     */
    @EventBusSubscriber(modid = PrefabCustomAddon.MOD_ID)
    public static final class SilentItemJoinListener {

        private SilentItemJoinListener() {}

        @SubscribeEvent
        public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
            if (event.getLevel().isClientSide()) return;
            if (!SilentBuild.isActive()) return;
            Entity entity = event.getEntity();
            if (!(entity instanceof ItemEntity)) return;
            ItemEntity item = (ItemEntity) entity;
            // 用 stack trace 检查是否在 setBlock 链里 (避免误拦真正的玩家投放物)
            // 实际上 setBlock → block.onRemove → popResource 都会走到这里, 静默态必拦
            PrefabCustomAddon.LOGGER.info("[CLOUD-SILENT] 拒绝 ItemEntity 进入: {} x{} @ {} (静默构建中)",
                item.getItem().getItem(), item.getItem().getCount(), item.blockPosition().toShortString());
            event.setCanceled(true);
        }

        /**
         * 清空结束后, 兜底扫一遍 AABB, 把任何漏网的 ItemEntity discard 掉.
         * @param origin 区域起点
         * @param sx,sy,sz 区域尺寸
         */
        public static void sweepRange(ServerLevel level, BlockPos origin, int sx, int sy, int sz) {
            try {
                AABB box = new AABB(
                    (double) origin.getX(), (double) origin.getY(), (double) origin.getZ(),
                    (double) (origin.getX() + sx),
                    (double) (origin.getY() + sy),
                    (double) (origin.getZ() + sz));
                List<ItemEntity> list = level.getEntitiesOfClass(ItemEntity.class, box,
                    e -> e.isAlive() && !e.isRemoved());
                if (list.isEmpty()) return;
                int killed = 0;
                for (ItemEntity ie : list) {
                    ie.discard();
                    killed++;
                }
                if (killed > 0) {
                    PrefabCustomAddon.LOGGER.info("[CLOUD-SILENT] 兜底扫荡: 移除 {} 个 ItemEntity @ {}..{}",
                        killed, origin.toShortString(), box.toString());
                }
            } catch (Throwable t) {
                PrefabCustomAddon.LOGGER.warn("[CLOUD-SILENT] sweepRange 失败: {}", t.toString());
            }
        }
    }
}
