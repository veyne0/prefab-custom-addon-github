package com.prefab.addon.network;

import com.prefab.addon.PrefabCustomAddon;
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

    private static void handleBuild(BuildCustomStructurePayload payload, IPayloadContext context) {
        context.player().getServer().execute(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            Level level = player.level();
            PrefabCustomAddon.LOGGER.info("Building custom structure '{}' from pack '{}' at {}",
                    payload.constructionId(), payload.packName(), payload.pos());
            com.prefab.addon.structure.CustomStructureBuilder.getInstance()
                    .placeStructure(player, level, payload.pos(), payload.packName(), payload.constructionId());
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
            PrefabCustomAddon.LOGGER.info("[BIND-DEBUG] Server received bind request: {}/{}",
                    payload.packName(), payload.constructionId());

            Inventory inv = player.getInventory();
            int boundSlot = -1;
            int scanned = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty()) continue;
                scanned++;
                if (stack.getItem() instanceof CustomBlueprintItem) {
                    CustomBlueprintItem.bindConstruction(stack, payload.packName(), payload.constructionId());
                    boundSlot = i;
                    PrefabCustomAddon.LOGGER.info("[BIND-DEBUG] Server-side bound blueprint in slot {} (count={})",
                            i, stack.getCount());
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
