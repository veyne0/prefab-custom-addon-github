package com.prefab.addon.network;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 客户端侧包处理器. 只在客户端线程 (enqueueWork 之后) 调用,
 * 用 {@link OnlyIn} 隔离, 保证 dedicated server 不会加载这些客户端依赖类.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientPacketHandlers {

    private ClientPacketHandlers() {}

    /** 拓展包清单. */
    public static void handlePackManifest(ServerPackManifestPayload payload) {
        ServerPackSyncClient.getInstance().handleManifest(payload);
    }

    /** 拓展包分片. */
    public static void handlePackChunk(ServerPackChunkPayload payload) {
        ServerPackSyncClient.getInstance().handleChunk(payload);
    }

    /** 一批方块刚被放置 (建造下落动画). */
    public static void handleBatchBlocksPlaced(BatchBlocksPlacedPayload payload) {
        com.prefab.addon.client.BuildAnimationRenderer.onBatchBlocksPlaced(payload);
    }

    /** 服务端材料账本快照回来: 更新客户端镜像 session + 刷新提交界面. */
    public static void handleMaterialSubmitResult(MaterialSubmitResultPayload payload) {
        net.minecraft.client.player.LocalPlayer player =
            net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) return;
        com.prefab.addon.work.ChallengeSessionManager.applyServerState(
            player.getUUID(), payload.sessionId(), payload.submittedAfter());
        com.prefab.addon.client.gui.MaterialSubmissionGui.onServerState(
            payload.sessionId(), payload.submittedAfter(), payload.allDone());
        com.prefab.addon.PrefabCustomAddon.LOGGER.info(
            "[MATERIAL-LEDGER] 客户端同步服务端账本: session={} 项数={} 全齐={}",
            payload.sessionId(), payload.submittedAfter().size(), payload.allDone());
    }
}
