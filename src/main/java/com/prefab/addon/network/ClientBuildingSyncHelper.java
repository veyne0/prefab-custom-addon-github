package com.prefab.addon.network;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.cloud.CloudBuilding;
import com.prefab.addon.integration.BuildingDatabase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

/**
 * 客户端处理 CloudBuildingSyncPayload 的联动副作用.
 *
 * <h2>职责</h2>
 * <ol>
 *   <li>Jade 用的 {@link BuildingDatabase} 全量替换 (placed=true 的)</li>
 * </ol>
 *
 * <h2>Xaero 航点</h2>
 * <b>不再自动标航点</b> — 玩家自己点 GUI 上的"导航"按钮才会标.
 * 收回建筑时也不会自动删航点 (玩家手动标的, 让他自己管).
 * 这样避免: 玩家每放出/收回一次建筑都被加/删航点, 地图上航点爆炸.
 *
 * <h2>线程</h2>
 * 由 NetworkHandler 在 {@code ctx.enqueueWork} 里调用, 已经在 client thread,
 * 可以直接读 {@code Minecraft.getInstance().level}.
 */
public final class ClientBuildingSyncHelper {

    private ClientBuildingSyncHelper() {}

    /**
     * 客户端收到全量 sync 后调用. 必须 client thread.
     */
    public static void onSyncReceived(List<CloudBuilding> all) {
        try {
            // 1) BuildingDatabase: 全量替换为当前 placed 建筑
            BuildingDatabase.clear();
            ClientLevel clientLevel = Minecraft.getInstance().level;
            String playerName = null;
            UUID playerUuid = null;
            if (Minecraft.getInstance().player != null) {
                playerName = Minecraft.getInstance().player.getName().getString();
                playerUuid = Minecraft.getInstance().player.getUUID();
            }

            // 2) 只更新 Jade database. Xaero 航点完全交给玩家手动管理.
            for (CloudBuilding b : all) {
                if (!b.placed) continue;
                try {
                    ResourceKey<Level> dim = resolveDimensionId(b.dimensionId, clientLevel);
                    int steps = CloudBuilding.facingToRotationSteps(b.facing);
                    BuildingDatabase.register(new BuildingDatabase.Record(
                        b.id, b.name, playerName, playerUuid,
                        dim, b.placedAt, b.sizeX, b.sizeY, b.sizeZ, steps));
                } catch (Throwable t) {
                    PrefabCustomAddon.LOGGER.warn("[CLIENT-SYNC] register {} failed: {}",
                        b.id, t.getMessage());
                }
            }
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.warn("[CLIENT-SYNC] onSyncReceived failed: {}", t.getMessage());
        }
    }

    /** 关服 / 切世界时调用, 清缓存. */
    public static void clearCache() {
        BuildingDatabase.clear();
    }

    /** 字符串 dimId → ResourceKey, 失败 fallback 到当前客户端所在维度. */
    private static ResourceKey<Level> resolveDimensionId(String id, ClientLevel currentLevel) {
        if (id != null && !id.isEmpty()) {
            try {
                ResourceLocation loc = ResourceLocation.parse(id);
                return ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, loc);
            } catch (Throwable ignored) {}
        }
        if (currentLevel != null) return currentLevel.dimension();
        return ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
            ResourceLocation.withDefaultNamespace("overworld"));
    }
}
