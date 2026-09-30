package com.prefab.addon.network;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.ExtensionPackManager;
import com.prefab.addon.items.CustomBlueprintItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Forge 1.20.1 网络层: 用 SimpleChannel 注册全部 11 个包.
 *
 * <p>客户端处理的包统一走 {@link ClientPacketHandlers} (只在客户端线程调用),
 * 避免 dedicated server 加载客户端类.</p>
 */
public class NetworkHandler {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(PrefabCustomAddon.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int nextId = 0;

    /** 注册全部包. 在 mod 构造时调用一次. */
    public static void register() {
        // ===== 客户端→服务端 =====
        registerMessage(BuildCustomStructurePayload.class,
                BuildCustomStructurePayload::encode, BuildCustomStructurePayload::decode,
                NetworkHandler::handleBuild);
        registerMessage(BindConstructionPayload.class,
                BindConstructionPayload::encode, BindConstructionPayload::decode,
                NetworkHandler::handleBind);
        // 自定义推土机: 客户端 → 服务端执行清除
        registerMessage(ExecuteCustomBulldozerPayload.class,
                ExecuteCustomBulldozerPayload::encode, ExecuteCustomBulldozerPayload::decode,
                (payload, ctxSup) -> {
                    NetworkEvent.Context ctx = ctxSup.get();
                    ctx.enqueueWork(() -> {
                        ServerPlayer sp = ctx.getSender();
                        if (sp != null) ExecuteCustomBulldozerPayload.handle(payload, sp);
                    });
                    ctx.setPacketHandled(true);
                });
        // 全局建造速度 (OP 校验)
        registerMessage(UpdateBuildSpeedPayload.class,
                UpdateBuildSpeedPayload::encode, UpdateBuildSpeedPayload::decode,
                NetworkHandler::handleUpdateBuildSpeed);
        // 拓展包同步 (请求 / ACK / 重发清单)
        registerMessage(RequestServerPacksPayload.class,
                RequestServerPacksPayload::encode, RequestServerPacksPayload::decode,
                (payload, ctxSup) -> {
                    NetworkEvent.Context ctx = ctxSup.get();
                    ctx.enqueueWork(() -> {
                        ServerPlayer sp = ctx.getSender();
                        if (sp != null) ServerPackSyncServer.getInstance().handleRequest(sp, payload);
                    });
                    ctx.setPacketHandled(true);
                });
        registerMessage(ServerPackChunkAckPayload.class,
                ServerPackChunkAckPayload::encode, ServerPackChunkAckPayload::decode,
                (payload, ctxSup) -> {
                    NetworkEvent.Context ctx = ctxSup.get();
                    ctx.enqueueWork(() -> {
                        ServerPlayer sp = ctx.getSender();
                        if (sp != null) ServerPackSyncServer.getInstance().handleAck(sp, payload);
                    });
                    ctx.setPacketHandled(true);
                });
        registerMessage(RequestServerPackManifestPayload.class,
                RequestServerPackManifestPayload::encode, RequestServerPackManifestPayload::decode,
                (payload, ctxSup) -> {
                    NetworkEvent.Context ctx = ctxSup.get();
                    ctx.enqueueWork(() -> {
                        ServerPlayer sp = ctx.getSender();
                        if (sp == null) return;
                        PrefabCustomAddon.LOGGER.info("[PACK-SYNC] {} requested manifest resend", sp.getName().getString());
                        // 玩家主动点 "同步" 按钮时, 顺带重扫一次服务器目录,
                        // 这样服主中途加 zip 不需要重启服也能让客户端拿到
                        int n = ExtensionPackManager.getInstance().reload();
                        PrefabCustomAddon.LOGGER.info("[PACK-SYNC] After reload: {} packs on server", n);
                        ServerPackSyncServer.getInstance().onPlayerJoin(sp);
                    });
                    ctx.setPacketHandled(true);
                });

        // ===== 服务端→客户端 =====
        // 广播当前全服建造速度 (供 SettingsGui 同步显示)
        registerMessage(SyncBuildSpeedPayload.class,
                SyncBuildSpeedPayload::encode, SyncBuildSpeedPayload::decode,
                (payload, ctxSup) -> {
                    NetworkEvent.Context ctx = ctxSup.get();
                    ctx.enqueueWork(() -> {
                        if (FMLEnvironment.dist == Dist.CLIENT) {
                            com.prefab.addon.config.PlayerPreferences.get()
                                    .setBuildBatchPercentFromServer(payload.percent());
                        }
                    });
                    ctx.setPacketHandled(true);
                });
        // 拓展包同步 (清单 / 分片)
        registerMessage(ServerPackManifestPayload.class,
                ServerPackManifestPayload::encode, ServerPackManifestPayload::decode,
                (payload, ctxSup) -> {
                    NetworkEvent.Context ctx = ctxSup.get();
                    ctx.enqueueWork(() -> {
                        if (FMLEnvironment.dist == Dist.CLIENT) {
                            ClientPacketHandlers.handlePackManifest(payload);
                        }
                    });
                    ctx.setPacketHandled(true);
                });
        registerMessage(ServerPackChunkPayload.class,
                ServerPackChunkPayload::encode, ServerPackChunkPayload::decode,
                (payload, ctxSup) -> {
                    NetworkEvent.Context ctx = ctxSup.get();
                    ctx.enqueueWork(() -> {
                        if (FMLEnvironment.dist == Dist.CLIENT) {
                            ClientPacketHandlers.handlePackChunk(payload);
                        }
                    });
                    ctx.setPacketHandled(true);
                });
        // 一批方块刚被放置 (用于"建造下落动画")
        registerMessage(BatchBlocksPlacedPayload.class,
                BatchBlocksPlacedPayload::encode, BatchBlocksPlacedPayload::decode,
                (payload, ctxSup) -> {
                    NetworkEvent.Context ctx = ctxSup.get();
                    ctx.enqueueWork(() -> {
                        if (FMLEnvironment.dist == Dist.CLIENT) {
                            ClientPacketHandlers.handleBatchBlocksPlaced(payload);
                        }
                    });
                    ctx.setPacketHandled(true);
                });
        // 材料提交 (客户端 → 服务端扣材料, 服务端权威)
        registerMessage(SubmitMaterialsPayload.class,
                SubmitMaterialsPayload::encode, SubmitMaterialsPayload::decode,
                SubmitMaterialsPayload::handle);
        // 材料提交结果 (服务端 → 客户端快照同步)
        registerMessage(MaterialSubmitResultPayload.class,
                MaterialSubmitResultPayload::encode, MaterialSubmitResultPayload::decode,
                MaterialSubmitResultPayload::handle);
        // 重置材料提交进度 (提交界面的"重置进度"按钮)
        registerMessage(ResetMaterialLedgerPayload.class,
                ResetMaterialLedgerPayload::encode, ResetMaterialLedgerPayload::decode,
                ResetMaterialLedgerPayload::handle);
    }

    private static <T> void registerMessage(Class<T> type,
                                            BiConsumer<T, FriendlyByteBuf> encoder,
                                            Function<FriendlyByteBuf, T> decoder,
                                            BiConsumer<T, Supplier<NetworkEvent.Context>> handler) {
        INSTANCE.registerMessage(nextId++, type, encoder, decoder, handler);
    }

    // ============================================================
    // 发送封装
    // ============================================================

    /** 客户端→服务端: 通用 (所有 C2S 包都走这里). */
    public static void sendToServer(Object payload) {
        INSTANCE.sendToServer(payload);
    }

    /** 服务端→指定玩家. */
    public static void sendToPlayer(ServerPlayer player, Object payload) {
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), payload);
    }

    /**
     * 服务端→所有在线玩家: 广播当前全服建造速度.
     * 给每个在线玩家单独发一份 (不依赖所有玩家在同一连接上, 兼容性更好).
     */
    public static void broadcastBuildSpeed(net.minecraft.server.MinecraftServer server, int percent) {
        SyncBuildSpeedPayload payload = new SyncBuildSpeedPayload(percent);
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            sendToPlayer(sp, payload);
        }
    }

    // ============================================================
    // 服务端处理器
    // ============================================================

    /**
     * 服务端处理: 修改全服建造速度 (OP 校验).
     * 非 OP 直接拒绝 + 警告. 通过后写入服务端 PlayerPreferences, 并广播给所有在线玩家.
     */
    private static void handleUpdateBuildSpeed(UpdateBuildSpeedPayload payload, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;
            int requestedPercent = Math.max(1, Math.min(100, payload.percent()));

            // OP 校验 (permission level >= 2)
            if (!player.hasPermissions(2)) {
                PrefabCustomAddon.LOGGER.warn("[BUILD-SPEED] Non-OP player {} tried to set build speed to {}%, refused",
                    player.getName().getString(), requestedPercent);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    PrefabCustomAddon.tr("err.build_speed_op"))
                    .withStyle(net.minecraft.ChatFormatting.RED));
                return;
            }

            int oldPercent = com.prefab.addon.config.PlayerPreferences.get().getBuildBatchPercent();
            if (oldPercent == requestedPercent) {
                PrefabCustomAddon.LOGGER.info("[BUILD-SPEED] OP {} set build speed to {}% (no change)",
                    player.getName().getString(), requestedPercent);
                return;
            }

            // 写服务端 PlayerPreferences
            com.prefab.addon.config.PlayerPreferences.get().setBuildBatchPercent(requestedPercent);
            PrefabCustomAddon.LOGGER.info("[BUILD-SPEED] OP {} set build speed: {}% → {}% (全服生效, 广播给所有在线玩家)",
                player.getName().getString(), oldPercent, requestedPercent);

            // 广播给所有在线玩家 (让他们的 SettingsGui 滑条显示同步到新值)
            broadcastBuildSpeed(player.getServer(), requestedPercent);

            // 给发起者一个确认消息
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                String.format("§a[建造速度] 已设为 %d%% (全服生效)", requestedPercent))
                .withStyle(net.minecraft.ChatFormatting.GREEN));
        });
        ctx.setPacketHandled(true);
    }

    private static void handleBuild(BuildCustomStructurePayload payload, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;
            net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) player.level();
            PrefabCustomAddon.LOGGER.info("Building custom structure '{}' from pack '{}' at {}",
                    payload.constructionId(), payload.packName(), payload.pos());
            try {
                // 关键: 服务端 build 路径必须强制从磁盘重扫, 防止管理员中途删除 zip 后
                //   内存里 packs 列表还是旧的 → 看似还能 build, 实际 build 用了"已删除"的数据.
                //   这是真正的"防误用"机制: 客户端有什么 zip 都没用, 服务端磁盘上有才能建.
                com.prefab.addon.extension.ExtensionPackManager.getInstance().forceReload();

                // 关键: 异步任务. placeStructure 立即返回 true (任务已注册到 AsyncBuildManager).
                // 蓝图是便携终端入口, 建造不消耗蓝图; 生存材料在提交时已由 ServerMaterialLedger 扣除.
                boolean ok = com.prefab.addon.structure.CustomStructureBuilder.getInstance()
                        .placeStructure(player, level, payload.pos(), payload.packName(),
                                payload.constructionId(), payload.houseFacing(), payload.animationMode());
                if (!ok) {
                    PrefabCustomAddon.LOGGER.warn("[BUILD-DEBUG] 启动异步建造任务失败: pack={}/{}",
                        payload.packName(), payload.constructionId());
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "§e提示: 建造启动失败").withStyle(net.minecraft.ChatFormatting.YELLOW));
                } else {
                    // 建造任务已启动: 清服务端材料账本 (与客户端 StructurePreviewKeyHandler 的 reset 对齐)
                    com.prefab.addon.work.ServerMaterialLedger.reset(player, payload.constructionId());
                }
            } catch (Exception e) {
                PrefabCustomAddon.LOGGER.error("Build failed for {}/{}", payload.packName(), payload.constructionId(), e);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "✗ 建造失败: " + e.getMessage()).withStyle(net.minecraft.ChatFormatting.RED));
            }
            // 兜底: 服务端没找到这个 construction (玩家本地有, 但服务端没有) → 明确告诉玩家
            if (com.prefab.addon.extension.ExtensionPackManager.getInstance()
                    .findConstruction(payload.packName(), payload.constructionId()) == null) {
                PrefabCustomAddon.LOGGER.error("[BUILD-REFUSE] Server has no construction '{}' in pack '{}'. " +
                        "Player {} tried to build it (pack only on client?)",
                        payload.constructionId(), payload.packName(), player.getName().getString());
                // 多行提示, 同 CustomStructureBuilder.placeStructure 里的找不到建筑提示保持一致
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        PrefabCustomAddon.tr("err.not_on_server"))
                        .withStyle(net.minecraft.ChatFormatting.RED));
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        PrefabCustomAddon.tr("err.hint.pack_on_server"))
                        .withStyle(net.minecraft.ChatFormatting.YELLOW));
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        PrefabCustomAddon.tr("err.hint.sync_server"))
                        .withStyle(net.minecraft.ChatFormatting.YELLOW));
            }
        });
        ctx.setPacketHandled(true);
    }

    /**
     * 服务端处理：找到玩家背包里的 Custom Blueprint，写入 packName/constructionId，
     * 并把变化广播到客户端。
     *
     * 关键：如果只改客户端的 ItemStack，服务端 ItemStack 不会被修改，
     * 玩家退出存档后绑定就丢了。
     */
    private static void handleBind(BindConstructionPayload payload, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;
            PrefabCustomAddon.LOGGER.info("[BIND-DEBUG] Server received bind request: {}/{} locked={}",
                    payload.packName(), payload.constructionId(), payload.locked());

            Inventory inv = player.getInventory();
            int boundSlot = -1;
            int scanned = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty()) continue;
                scanned++;
                if (stack.getItem() instanceof CustomBlueprintItem) {
                    // 服务端也校验: 已锁定的蓝图不允许重新绑
                    if (CustomBlueprintItem.isLocked(stack)) {
                        PrefabCustomAddon.LOGGER.warn(
                            "[BIND-DEBUG] Server: blueprint in slot {} is locked, refuse re-bind", i);
                        break;
                    }
                    CustomBlueprintItem.bindConstruction(stack,
                        payload.packName(), payload.constructionId(), payload.locked());
                    boundSlot = i;
                    PrefabCustomAddon.LOGGER.info("[BIND-DEBUG] Server-side bound blueprint in slot {} (count={}, locked={})",
                            i, stack.getCount(), payload.locked());
                    break;  // 只绑第一个
                }
            }

            if (boundSlot == -1) {
                PrefabCustomAddon.LOGGER.warn("[BIND-DEBUG] No Custom Blueprint found in player inventory! " +
                        "Scanned {} non-empty slots", scanned);
            } else {
                // 关键：把服务端的变化推到客户端
                inv.setChanged();
                if (player.containerMenu != null) {
                    player.containerMenu.broadcastChanges();
                }
                PrefabCustomAddon.LOGGER.info("[BIND-DEBUG] Inventory dirty flag set and broadcastChanges called");
            }
        });
        ctx.setPacketHandled(true);
    }
}
