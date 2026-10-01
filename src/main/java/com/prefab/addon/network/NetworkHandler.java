/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.ChatFormatting
 *  net.minecraft.core.component.DataComponents
 *  net.minecraft.nbt.CompoundTag
 *  net.minecraft.network.chat.Component
 *  net.minecraft.network.protocol.common.custom.CustomPacketPayload
 *  net.minecraft.server.MinecraftServer
 *  net.minecraft.server.level.ServerLevel
 *  net.minecraft.server.level.ServerPlayer
 *  net.minecraft.world.entity.player.Inventory
 *  net.minecraft.world.item.ItemStack
 *  net.minecraft.world.item.component.CustomData
 *  net.neoforged.neoforge.network.PacketDistributor
 *  net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
 *  net.neoforged.neoforge.network.handling.IPayloadContext
 *  net.neoforged.neoforge.network.registration.PayloadRegistrar
 */
package com.prefab.addon.network;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.client.BuildAnimationRenderer;
import com.prefab.addon.cloud.CloudBuilding;
import com.prefab.addon.cloud.CloudBuildingClientCache;
import com.prefab.addon.cloud.CloudBuildingDeletePayload;
import com.prefab.addon.cloud.CloudBuildingManager;
import com.prefab.addon.cloud.CloudBuildingRecallPayload;
import com.prefab.addon.cloud.CloudBuildingSummonPayload;
import com.prefab.addon.cloud.CloudBuildingSyncPayload;
import com.prefab.addon.config.PlayerPreferences;
import com.prefab.addon.extension.ExtensionPackManager;
import com.prefab.addon.items.CustomBlueprintItem;
import com.prefab.addon.multiblock.MultiblockPlacer;
import com.prefab.addon.network.BatchBlocksPlacedPayload;
import com.prefab.addon.network.BindConstructionPayload;
import com.prefab.addon.network.BuildCustomStructurePayload;
import com.prefab.addon.network.BuildOutsourceStructurePayload;
import com.prefab.addon.network.ClientBuildingSyncHelper;
import com.prefab.addon.network.ExecuteCustomBulldozerPayload;
import com.prefab.addon.network.MiniBuildingCapturePayload;
import com.prefab.addon.network.MiniBuildingFullDataPayload;
import com.prefab.addon.network.MiniBuildingItemDataRequestPayload;
import com.prefab.addon.network.MiniBuildingItemDataResponsePayload;
import com.prefab.addon.network.MultiblockSummonPayload;
import com.prefab.addon.network.OperationWandBuildPayload;
import com.prefab.addon.network.OperationWandScanPayload;
import com.prefab.addon.network.OperationWandScanResultPayload;
import com.prefab.addon.network.RequestMiniBuildingDataPayload;
import com.prefab.addon.network.RequestServerPackManifestPayload;
import com.prefab.addon.network.RequestServerPacksPayload;
import com.prefab.addon.network.ServerPackChunkAckPayload;
import com.prefab.addon.network.ServerPackChunkPayload;
import com.prefab.addon.network.ServerPackManifestPayload;
import com.prefab.addon.network.ServerPackSyncClient;
import com.prefab.addon.network.ServerPackSyncServer;
import com.prefab.addon.network.SyncBuildSpeedPayload;
import com.prefab.addon.network.UpdateBuildSpeedPayload;
import com.prefab.addon.structure.AsyncBuildManager;
import com.prefab.addon.structure.CustomStructureBuilder;
import com.prefab.addon.structure.OutsourceBuildManager;
import java.util.ArrayList;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class NetworkHandler {
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(BuildCustomStructurePayload.TYPE, BuildCustomStructurePayload.STREAM_CODEC, NetworkHandler::handleBuild);
        registrar.playToServer(BindConstructionPayload.TYPE, BindConstructionPayload.STREAM_CODEC, NetworkHandler::handleBind);
        registrar.playToServer(BuildOutsourceStructurePayload.TYPE, BuildOutsourceStructurePayload.STREAM_CODEC, NetworkHandler::handleBuildOutsource);
        registrar.playToServer(ExecuteCustomBulldozerPayload.TYPE, ExecuteCustomBulldozerPayload.STREAM_CODEC, ExecuteCustomBulldozerPayload::handle);
        registrar.playToServer(MiniBuildingCapturePayload.TYPE, MiniBuildingCapturePayload.STREAM_CODEC, MiniBuildingCapturePayload::handle);
        registrar.playToServer(RequestMiniBuildingDataPayload.TYPE, RequestMiniBuildingDataPayload.STREAM_CODEC, RequestMiniBuildingDataPayload::handle);
        registrar.playToClient(MiniBuildingFullDataPayload.TYPE, MiniBuildingFullDataPayload.STREAM_CODEC, MiniBuildingFullDataPayload::handle);
        registrar.playToServer(MiniBuildingItemDataRequestPayload.TYPE, MiniBuildingItemDataRequestPayload.STREAM_CODEC, MiniBuildingItemDataRequestPayload::handle);
        registrar.playToClient(MiniBuildingItemDataResponsePayload.TYPE, MiniBuildingItemDataResponsePayload.STREAM_CODEC, MiniBuildingItemDataResponsePayload::handle);
        registrar.playToServer(UpdateBuildSpeedPayload.TYPE, UpdateBuildSpeedPayload.STREAM_CODEC, NetworkHandler::handleUpdateBuildSpeed);
        registrar.playToClient(SyncBuildSpeedPayload.TYPE, SyncBuildSpeedPayload.STREAM_CODEC, (payload, ctx) -> PlayerPreferences.get().setBuildBatchPercentFromServer(payload.percent()));
        registrar.playToClient(ServerPackManifestPayload.TYPE, ServerPackManifestPayload.STREAM_CODEC, (payload, ctx) -> ServerPackSyncClient.getInstance().handleManifest((ServerPackManifestPayload)payload));
        registrar.playToClient(ServerPackChunkPayload.TYPE, ServerPackChunkPayload.STREAM_CODEC, (payload, ctx) -> ServerPackSyncClient.getInstance().handleChunk((ServerPackChunkPayload)payload));
        registrar.playToServer(RequestServerPacksPayload.TYPE, RequestServerPacksPayload.STREAM_CODEC, (payload, ctx) -> {
            ServerPlayer sp = (ServerPlayer)ctx.player();
            ServerPackSyncServer.getInstance().handleRequest(sp, (RequestServerPacksPayload)payload);
        });
        registrar.playToServer(ServerPackChunkAckPayload.TYPE, ServerPackChunkAckPayload.STREAM_CODEC, (payload, ctx) -> {
            ServerPlayer sp = (ServerPlayer)ctx.player();
            ServerPackSyncServer.getInstance().handleAck(sp, (ServerPackChunkAckPayload)payload);
        });
        registrar.playToServer(RequestServerPackManifestPayload.TYPE, RequestServerPackManifestPayload.STREAM_CODEC, (payload, ctx) -> {
            ServerPlayer sp = (ServerPlayer)ctx.player();
            PrefabCustomAddon.LOGGER.info("[PACK-SYNC] {} requested manifest resend", (Object)sp.getName().getString());
            int n = ExtensionPackManager.getInstance().reload();
            PrefabCustomAddon.LOGGER.info("[PACK-SYNC] After reload: {} packs on server", (Object)n);
            ServerPackSyncServer.getInstance().onPlayerJoin(sp);
        });
        registrar.playToServer(CloudBuildingRecallPayload.TYPE, CloudBuildingRecallPayload.STREAM_CODEC, (payload, ctx) -> {
            ServerPlayer sp = (ServerPlayer)ctx.player();
            CloudBuildingManager.getInstance().recall(sp, payload.buildingId());
        });
        registrar.playToServer(CloudBuildingSummonPayload.TYPE, CloudBuildingSummonPayload.STREAM_CODEC, (payload, ctx) -> {
            ServerPlayer sp = (ServerPlayer)ctx.player();
            CloudBuildingManager.getInstance().summon(sp, payload.buildingId(), payload.pos(), payload.facing());
        });
        registrar.playToServer(CloudBuildingDeletePayload.TYPE, CloudBuildingDeletePayload.STREAM_CODEC, (payload, ctx) -> {
            ServerPlayer sp = (ServerPlayer)ctx.player();
            CloudBuildingManager.getInstance().delete(sp, payload.buildingId());
        });
        registrar.playToServer(MultiblockSummonPayload.TYPE, MultiblockSummonPayload.STREAM_CODEC, (payload, ctx) -> {
            ServerPlayer sp = (ServerPlayer)ctx.player();
            MultiblockPlacer.place(sp, payload.id(), payload.pos(), payload.facing());
        });
        registrar.playToClient(CloudBuildingSyncPayload.TYPE, CloudBuildingSyncPayload.STREAM_CODEC, (payload, ctx) -> {
            ArrayList<CloudBuilding> list = new ArrayList<CloudBuilding>();
            for (CompoundTag tag : payload.buildings()) {
                try {
                    list.add(CloudBuilding.fromNbt(tag));
                }
                catch (Exception e) {
                    PrefabCustomAddon.LOGGER.warn("[CLOUD-CACHE] \u8df3\u8fc7\u635f\u574f\u7684\u4e91\u7aef\u5efa\u7b51: {}", (Object)e.getMessage());
                }
            }
            CloudBuildingClientCache.getInstance().replaceAll(list);
            ctx.enqueueWork(() -> ClientBuildingSyncHelper.onSyncReceived(list));
        });
        registrar.playToClient(BatchBlocksPlacedPayload.TYPE, BatchBlocksPlacedPayload.STREAM_CODEC, (payload, ctx) -> BuildAnimationRenderer.onBatchBlocksPlaced(payload));
        registrar.playToServer(OperationWandScanPayload.TYPE, OperationWandScanPayload.STREAM_CODEC, OperationWandScanPayload::handle);
        registrar.playToClient(OperationWandScanResultPayload.TYPE, OperationWandScanResultPayload.STREAM_CODEC, OperationWandScanResultPayload::handle);
        registrar.playToServer(OperationWandBuildPayload.TYPE, OperationWandBuildPayload.STREAM_CODEC, OperationWandBuildPayload::handle);
        // 挑战模式材料提交 (服务端权威扣除, 修复刷物品 bug)
        registrar.playToServer(SubmitMaterialsPayload.TYPE, SubmitMaterialsPayload.STREAM_CODEC, SubmitMaterialsPayload::handle);
        registrar.playToClient(MaterialSubmitResultPayload.TYPE, MaterialSubmitResultPayload.STREAM_CODEC, MaterialSubmitResultPayload::handle);
        registrar.playToServer(ResetMaterialLedgerPayload.TYPE, ResetMaterialLedgerPayload.STREAM_CODEC, ResetMaterialLedgerPayload::handle);
    }

    public static void sendToServer(BuildCustomStructurePayload payload) {
        PacketDistributor.sendToServer((CustomPacketPayload)payload, (CustomPacketPayload[])new CustomPacketPayload[0]);
    }

    public static void sendToServer(BindConstructionPayload payload) {
        PacketDistributor.sendToServer((CustomPacketPayload)payload, (CustomPacketPayload[])new CustomPacketPayload[0]);
    }

    public static void sendToServer(RequestServerPacksPayload payload) {
        PacketDistributor.sendToServer((CustomPacketPayload)payload, (CustomPacketPayload[])new CustomPacketPayload[0]);
    }

    public static void sendToServer(ServerPackChunkAckPayload payload) {
        PacketDistributor.sendToServer((CustomPacketPayload)payload, (CustomPacketPayload[])new CustomPacketPayload[0]);
    }

    public static void sendToServer(RequestServerPackManifestPayload payload) {
        PacketDistributor.sendToServer((CustomPacketPayload)payload, (CustomPacketPayload[])new CustomPacketPayload[0]);
    }

    public static void sendToServer(UpdateBuildSpeedPayload payload) {
        PacketDistributor.sendToServer((CustomPacketPayload)payload, (CustomPacketPayload[])new CustomPacketPayload[0]);
    }

    public static void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer((CustomPacketPayload)payload, (CustomPacketPayload[])new CustomPacketPayload[0]);
    }

    public static void broadcastBuildSpeed(MinecraftServer server, int percent) {
        SyncBuildSpeedPayload payload = new SyncBuildSpeedPayload(percent);
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer((ServerPlayer)sp, (CustomPacketPayload)payload, (CustomPacketPayload[])new CustomPacketPayload[0]);
        }
    }

    private static void handleUpdateBuildSpeed(UpdateBuildSpeedPayload payload, IPayloadContext context) {
        context.player().getServer().execute(() -> {
            ServerPlayer player = (ServerPlayer)context.player();
            int requestedPercent = Math.max(1, Math.min(100, payload.percent()));
            if (!player.hasPermissions(2)) {
                PrefabCustomAddon.LOGGER.warn("[BUILD-SPEED] Non-OP player {} tried to set build speed to {}%, refused", (Object)player.getName().getString(), (Object)requestedPercent);
                player.sendSystemMessage((Component)Component.literal((String)PrefabCustomAddon.tr("err.build_speed_op", new Object[0])).withStyle(ChatFormatting.RED));
                return;
            }
            int oldPercent = PlayerPreferences.get().getBuildBatchPercent();
            if (oldPercent == requestedPercent) {
                PrefabCustomAddon.LOGGER.info("[BUILD-SPEED] OP {} set build speed to {}% (no change)", (Object)player.getName().getString(), (Object)requestedPercent);
                return;
            }
            PlayerPreferences.get().setBuildBatchPercent(requestedPercent);
            PrefabCustomAddon.LOGGER.info("[BUILD-SPEED] OP {} set build speed: {}% \u2192 {}% (\u5168\u670d\u751f\u6548, \u5e7f\u64ad\u7ed9\u6240\u6709\u5728\u7ebf\u73a9\u5bb6)", new Object[]{player.getName().getString(), oldPercent, requestedPercent});
            NetworkHandler.broadcastBuildSpeed(player.getServer(), requestedPercent);
            player.sendSystemMessage((Component)Component.literal((String)String.format("\u00a7a[\u5efa\u9020\u901f\u5ea6] \u5df2\u8bbe\u4e3a %d%% (\u5168\u670d\u751f\u6548)", requestedPercent)).withStyle(ChatFormatting.GREEN));
        });
    }

    private static void handleBuild(BuildCustomStructurePayload payload, IPayloadContext context) {
        context.player().getServer().execute(() -> {
            ServerPlayer player;
            block6: {
                player = (ServerPlayer)context.player();
                ServerLevel level = (ServerLevel)player.level();
                PrefabCustomAddon.LOGGER.info("Building custom structure '{}' from pack '{}' at {} (silent={})", new Object[]{payload.constructionId(), payload.packName(), payload.pos(), payload.silent()});
                try {
                    ExtensionPackManager.getInstance().forceReload();
                    boolean ok = CustomStructureBuilder.getInstance().placeStructure(player, level, payload.pos(), payload.packName(), payload.constructionId(), payload.houseFacing(), payload.animationMode(), payload.silent());
                    if (!ok) {
                        PrefabCustomAddon.LOGGER.warn("[BUILD-DEBUG] \u542f\u52a8\u5f02\u6b65\u5efa\u9020\u4efb\u52a1\u5931\u8d25, \u84dd\u56fe\u4e0d\u6d88\u8017: pack={}/{}", (Object)payload.packName(), (Object)payload.constructionId());
                        if (player != null) {
                            player.sendSystemMessage((Component)Component.literal((String)"\u00a7e\u63d0\u793a: \u5efa\u9020\u542f\u52a8\u5931\u8d25, \u84dd\u56fe\u672a\u6d88\u8017").withStyle(ChatFormatting.YELLOW));
                        }
                    } else {
                        // 建造任务已启动: 清服务端材料账本 (与客户端 StructurePreviewKeyHandler 的 reset 对齐)
                        com.prefab.addon.work.ServerMaterialLedger.reset(player, payload.constructionId());
                    }
                }
                catch (Exception e) {
                    PrefabCustomAddon.LOGGER.error("Build failed for {}/{}", new Object[]{payload.packName(), payload.constructionId(), e});
                    if (player == null) break block6;
                    player.sendSystemMessage((Component)Component.literal((String)("\u2717 \u5efa\u9020\u5931\u8d25: " + e.getMessage())).withStyle(ChatFormatting.RED));
                }
            }
            if (ExtensionPackManager.getInstance().findConstruction(payload.packName(), payload.constructionId()) == null) {
                PrefabCustomAddon.LOGGER.error("[BUILD-REFUSE] Server has no construction '{}' in pack '{}'. Player {} tried to build it (pack only on client?)", new Object[]{payload.constructionId(), payload.packName(), player.getName().getString()});
                if (player != null) {
                    player.sendSystemMessage((Component)Component.literal((String)PrefabCustomAddon.tr("err.not_on_server", new Object[0])).withStyle(ChatFormatting.RED));
                    player.sendSystemMessage((Component)Component.literal((String)PrefabCustomAddon.tr("err.hint.pack_on_server", new Object[0])).withStyle(ChatFormatting.YELLOW));
                    player.sendSystemMessage((Component)Component.literal((String)PrefabCustomAddon.tr("err.hint.sync_server", new Object[0])).withStyle(ChatFormatting.YELLOW));
                }
            }
        });
    }

    private static void handleBuildOutsource(BuildOutsourceStructurePayload payload, IPayloadContext context) {
        context.player().getServer().execute(() -> {
            block3: {
                ServerPlayer player = (ServerPlayer)context.player();
                ServerLevel level = (ServerLevel)player.level();
                PrefabCustomAddon.LOGGER.info("[OUTSOURCE-BUILD] Server received build request: buildingId='{}' style={} pos={} facing={} mode={}", new Object[]{payload.buildingId(), payload.styleIndex(), payload.pos(), payload.houseFacing(), payload.animationMode()});
                try {
                    boolean ok = OutsourceBuildManager.placeStructure(player, level, payload.buildingId(), payload.styleIndex(), payload.pos(), payload.houseFacing(), payload.animationMode());
                    if (!ok) {
                        PrefabCustomAddon.LOGGER.warn("[OUTSOURCE-BUILD] \u542f\u52a8\u5931\u8d25: buildingId={} style={}, \u84dd\u56fe\u4e0d\u6d88\u8017", (Object)payload.buildingId(), (Object)payload.styleIndex());
                    }
                }
                catch (Exception e) {
                    PrefabCustomAddon.LOGGER.error("[OUTSOURCE-BUILD] Build failed for {}", (Object)payload.buildingId(), (Object)e);
                    if (player == null) break block3;
                    player.sendSystemMessage((Component)Component.literal((String)("\u2717 \u5916\u5305\u5efa\u7b51\u5efa\u9020\u5931\u8d25: " + e.getMessage())).withStyle(ChatFormatting.RED));
                }
            }
        });
    }

    private static void handleBind(BindConstructionPayload payload, IPayloadContext context) {
        context.player().getServer().execute(() -> {
            ItemStack stack;
            int i;
            ServerPlayer player = (ServerPlayer)context.player();
            PrefabCustomAddon.LOGGER.info("[BIND-DEBUG] Server received bind request: {}/{} locked={}", new Object[]{payload.packName(), payload.constructionId(), payload.locked()});
            Inventory inv = player.getInventory();
            int boundSlot = -1;
            int scanned = 0;
            for (i = 0; i < inv.getContainerSize(); ++i) {
                stack = inv.getItem(i);
                if (stack.isEmpty()) continue;
                ++scanned;
                if (!AsyncBuildManager.isPlayerBlueprint(stack) || NetworkHandler.isLockedForBind(stack)) continue;
                String curPack = AsyncBuildManager.readBoundPackName(stack);
                String curCid = AsyncBuildManager.readBoundConstructionId(stack);
                if (!payload.packName().equals(curPack) || !payload.constructionId().equals(curCid)) continue;
                boundSlot = i;
                PrefabCustomAddon.LOGGER.info("[BIND-DEBUG] Server: blueprint in slot {} already bound to {}/{}, skip write (no-op)", new Object[]{i, curPack, curCid});
                break;
            }
            if (boundSlot == -1) {
                for (i = 0; i < inv.getContainerSize(); ++i) {
                    stack = inv.getItem(i);
                    if (stack.isEmpty()) continue;
                    ++scanned;
                    if (!AsyncBuildManager.isPlayerBlueprint(stack)) continue;
                    if (NetworkHandler.isLockedForBind(stack)) {
                        PrefabCustomAddon.LOGGER.warn("[BIND-DEBUG] Server: blueprint in slot {} is locked, refuse re-bind", (Object)i);
                        break;
                    }
                    if (stack.getItem() instanceof CustomBlueprintItem) {
                        CustomBlueprintItem.bindConstruction(stack, payload.packName(), payload.constructionId(), payload.locked());
                    } else {
                        CompoundTag tag = new CompoundTag();
                        tag.putString("packName", payload.packName());
                        tag.putString("constructionId", payload.constructionId());
                        tag.putBoolean("locked", payload.locked());
                        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                    }
                    boundSlot = i;
                    PrefabCustomAddon.LOGGER.info("[BIND-DEBUG] Server-side bound blueprint in slot {} (item={}, count={}, locked={})", new Object[]{i, stack.getItem(), stack.getCount(), payload.locked()});
                    break;
                }
            }
            if (boundSlot == -1) {
                PrefabCustomAddon.LOGGER.warn("[BIND-DEBUG] No Custom Blueprint found in player inventory! Scanned {} non-empty slots", (Object)scanned);
            } else {
                inv.setChanged();
                if (player.containerMenu != null) {
                    player.containerMenu.broadcastChanges();
                }
                PrefabCustomAddon.LOGGER.info("[BIND-DEBUG] Inventory dirty flag set and broadcastChanges called");
            }
        });
    }

    private static boolean isLockedForBind(ItemStack stack) {
        if (stack.getItem() instanceof CustomBlueprintItem) {
            return CustomBlueprintItem.isLocked(stack);
        }
        CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return false;
        }
        return data.copyTag().getBoolean("locked");
    }
}

