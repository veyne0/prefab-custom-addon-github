package com.prefab.addon.network;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.ExtensionPackManager;
import com.prefab.addon.items.CustomBlueprintItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class NetworkHandler {

    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(
                BuildCustomStructurePayload.TYPE,
                BuildCustomStructurePayload.STREAM_CODEC,
                NetworkHandler::handleBuild
        );
        registrar.playToServer(
                BindConstructionPayload.TYPE,
                BindConstructionPayload.STREAM_CODEC,
                NetworkHandler::handleBind
        );

        // ===== 客户端→服务端: 全局建造速度 (OP 校验) =====
        registrar.playToServer(
                UpdateBuildSpeedPayload.TYPE,
                UpdateBuildSpeedPayload.STREAM_CODEC,
                NetworkHandler::handleUpdateBuildSpeed
        );
        // ===== 服务端→客户端: 广播当前全服建造速度 (供 SettingsGui 同步显示) =====
        registrar.playToClient(
                SyncBuildSpeedPayload.TYPE,
                SyncBuildSpeedPayload.STREAM_CODEC,
                (payload, ctx) -> com.prefab.addon.config.PlayerPreferences.get()
                        .setBuildBatchPercentFromServer(payload.percent())
        );

        // ===== 服务端→客户端: 拓展包同步 (清单 / 分片) =====
        registrar.playToClient(
                ServerPackManifestPayload.TYPE,
                ServerPackManifestPayload.STREAM_CODEC,
                (payload, ctx) -> ServerPackSyncClient.getInstance().handleManifest(payload)
        );
        registrar.playToClient(
                ServerPackChunkPayload.TYPE,
                ServerPackChunkPayload.STREAM_CODEC,
                (payload, ctx) -> ServerPackSyncClient.getInstance().handleChunk(payload)
        );

        // ===== 客户端→服务端: 拓展包同步 (请求 / ACK / 重发清单) =====
        registrar.playToServer(
                RequestServerPacksPayload.TYPE,
                RequestServerPacksPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    ServerPlayer sp = (ServerPlayer) ctx.player();
                    ServerPackSyncServer.getInstance().handleRequest(sp, payload);
                }
        );
        registrar.playToServer(
                ServerPackChunkAckPayload.TYPE,
                ServerPackChunkAckPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    ServerPlayer sp = (ServerPlayer) ctx.player();
                    ServerPackSyncServer.getInstance().handleAck(sp, payload);
                }
        );
        registrar.playToServer(
                RequestServerPackManifestPayload.TYPE,
                RequestServerPackManifestPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    ServerPlayer sp = (ServerPlayer) ctx.player();
                    PrefabCustomAddon.LOGGER.info("[PACK-SYNC] {} requested manifest resend", sp.getName().getString());
                    // 玩家主动点 "同步" 按钮时, 顺带重扫一次服务器目录,
                    // 这样服主中途加 zip 不需要重启服也能让客户端拿到
                    int n = ExtensionPackManager.getInstance().reload();
                    PrefabCustomAddon.LOGGER.info("[PACK-SYNC] After reload: {} packs on server", n);
                    ServerPackSyncServer.getInstance().onPlayerJoin(sp);
                }
        );
    }

    /**
     * 客户端发送 BuildCustomStructurePayload 到服务端
     */
    public static void sendToServer(BuildCustomStructurePayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    /**
     * 客户端发送 BindConstructionPayload 到服务端：把当前 Construction 绑定到玩家背包里的 Custom Blueprint
     */
    public static void sendToServer(BindConstructionPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    /** 客户端→服务端: 通用 */
    public static void sendToServer(RequestServerPacksPayload payload) {
        PacketDistributor.sendToServer(payload);
    }
    public static void sendToServer(ServerPackChunkAckPayload payload) {
        PacketDistributor.sendToServer(payload);
    }
    public static void sendToServer(RequestServerPackManifestPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    /** 客户端→服务端: 申请修改全服建造速度 */
    public static void sendToServer(UpdateBuildSpeedPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    /**
     * 服务端→所有在线玩家: 广播当前全服建造速度.
     * 给每个在线玩家单独发一份 (不依赖所有玩家在同一连接上, 兼容性更好).
     */
    public static void broadcastBuildSpeed(net.minecraft.server.MinecraftServer server, int percent) {
        SyncBuildSpeedPayload payload = new SyncBuildSpeedPayload(percent);
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(sp, payload);
        }
    }

    /**
     * 服务端处理: 修改全服建造速度 (OP 校验).
     * 非 OP 直接拒绝 + 警告. 通过后写入服务端 PlayerPreferences, 并广播给所有在线玩家.
     */
    private static void handleUpdateBuildSpeed(UpdateBuildSpeedPayload payload, IPayloadContext context) {
        context.player().getServer().execute(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            int requestedPercent = Math.max(1, Math.min(100, payload.percent()));

            // OP 校验 (permission level >= 2)
            if (!player.hasPermissions(2)) {
                PrefabCustomAddon.LOGGER.warn("[BUILD-SPEED] Non-OP player {} tried to set build speed to {}%, refused",
                    player.getName().getString(), requestedPercent);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§c[建造速度] 需要 OP 权限才能修改! (permission level >= 2)")
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
    }

    private static void handleBuild(BuildCustomStructurePayload payload, IPayloadContext context) {
        context.player().getServer().execute(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) player.level();
            PrefabCustomAddon.LOGGER.info("Building custom structure '{}' from pack '{}' at {}",
                    payload.constructionId(), payload.packName(), payload.pos());
            try {
                // 关键: 服务端 build 路径必须强制从磁盘重扫, 防止管理员中途删除 zip 后
                //   内存里 packs 列表还是旧的 → 看似还能 build, 实际 build 用了"已删除"的数据.
                //   这是真正的"防误用"机制: 客户端有任何 zip 都没用, 服务端磁盘上有才能建.
                com.prefab.addon.extension.ExtensionPackManager.getInstance().forceReload();

                // 关键: 异步任务. placeStructure 立即返回 true (任务已注册到 AsyncBuildManager),
                // 蓝图消耗在异步任务 onCompleted() 里完成 (失败/取消时**不消耗**).
                boolean ok = com.prefab.addon.structure.CustomStructureBuilder.getInstance()
                        .placeStructure(player, level, payload.pos(), payload.packName(),
                                payload.constructionId(), payload.houseFacing());
                if (!ok) {
                    PrefabCustomAddon.LOGGER.warn("[BUILD-DEBUG] 启动异步建造任务失败, 蓝图不消耗: pack={}/{}",
                        payload.packName(), payload.constructionId());
                    if (player != null) {
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "§e提示: 建造启动失败, 蓝图未消耗").withStyle(net.minecraft.ChatFormatting.YELLOW));
                    }
                }
            } catch (Exception e) {
                PrefabCustomAddon.LOGGER.error("Build failed for {}/{}", payload.packName(), payload.constructionId(), e);
                if (player != null) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "✗ 建造失败: " + e.getMessage()).withStyle(net.minecraft.ChatFormatting.RED));
                }
            }
            // 兜底: 服务端没找到这个 construction (玩家本地有, 但服务端没有) → 明确告诉玩家
            if (com.prefab.addon.extension.ExtensionPackManager.getInstance()
                    .findConstruction(payload.packName(), payload.constructionId()) == null) {
                PrefabCustomAddon.LOGGER.error("[BUILD-REFUSE] Server has no construction '{}' in pack '{}'. " +
                        "Player {} tried to build it (pack only on client?)",
                        payload.constructionId(), payload.packName(), player.getName().getString());
                if (player != null) {
                    // 多行提示, 同 CustomStructureBuilder.placeStructure 里的找不到建筑提示保持一致
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "✗ 服务器没有这个建筑 (服务端 prefab-extension/ 缺少该拓展包)")
                            .withStyle(net.minecraft.ChatFormatting.RED));
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "§e提示: 建筑拓展包需放在 服务端 prefab-extension/ 文件夹里 (服务器端生效)。")
                            .withStyle(net.minecraft.ChatFormatting.YELLOW));
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "§e提示: 放好后按 §fO §e打开设置界面, 点击 [§f🔄 同步服务器拓展包§e] 即可拉取。")
                            .withStyle(net.minecraft.ChatFormatting.YELLOW));
                }
            }
        });
    }

    /**
     * 服务端处理：找到玩家背包里的 Custom Blueprint，写入 packName/constructionId，
     * 并把变化广播到客户端。
     *
     * 关键：如果只改客户端的 ItemStack，服务端 ItemStack 不会被修改，
     * 玩家退出存档后绑定就丢了。
     */
    private static void handleBind(BindConstructionPayload payload, IPayloadContext context) {
        context.player().getServer().execute(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
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
    }
}
