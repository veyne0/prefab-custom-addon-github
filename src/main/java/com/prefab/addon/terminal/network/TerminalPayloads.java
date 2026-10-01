package com.prefab.addon.terminal.network;

import com.prefab.addon.terminal.TerminalRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 终端网络通道 (NeoForge payload API).
 *
 * 仅保留终端本体所需的通道: 偏好保存 (已打开的应用 tab 列表) 与重开终端 GUI.
 * 矿脉勘探 / 多方块相机 / 多方块建造相关的通道随 GTM 功能一并裁剪.
 */
public final class TerminalPayloads {

    /** 客户端 → 服务端: 终端偏好 (已打开的应用 tab 列表), 关闭 GUI 时发一次. */
    public record SavePrefsC2S(CompoundTag data) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SavePrefsC2S> TYPE = new CustomPacketPayload.Type<>(
                ResourceLocation.fromNamespaceAndPath(TerminalRegistry.MOD_ID, "save_prefs"));

        public static final StreamCodec<FriendlyByteBuf, SavePrefsC2S> CODEC =
                new StreamCodec<FriendlyByteBuf, SavePrefsC2S>() {
                    @Override
                    public SavePrefsC2S decode(FriendlyByteBuf buf) {
                        net.minecraft.nbt.Tag tag = buf.readNbt();
                        return new SavePrefsC2S(tag instanceof CompoundTag ct
                                ? ct : new CompoundTag());
                    }

                    @Override
                    public void encode(FriendlyByteBuf buf, SavePrefsC2S p) {
                        buf.writeNbt(p.data());
                    }
                };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端处理偏好保存: OpenTabs 写回手上终端物品 CUSTOM_DATA. */
    public static void onSavePrefs(SavePrefsC2S payload, IPayloadContext ctx) {
        if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer player) || player.level().isClientSide) {
            return;
        }
        for (net.minecraft.world.InteractionHand hand : net.minecraft.world.InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.isEmpty() || stack.getItem() != TerminalRegistry.TERMINAL.get()) {
                continue;
            }
            TerminalRegistry.LOGGER.info("[DEVICE] SavePrefs: 写入手上终端@{} keys={}",
                    System.identityHashCode(stack), stack.getOrDefault(
                            net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                            net.minecraft.world.item.component.CustomData.EMPTY).copyTag().getAllKeys());
            CompoundTag merged = stack.getOrDefault(
                    net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
            merged.merge(payload.data().copy());
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(merged));
            break;
        }
    }

    /** 客户端 → 服务端: 请求重开终端 GUI (相机模式按 ESC 退出后回到终端主界面). */
    public record ReopenC2S() implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ReopenC2S> TYPE = new CustomPacketPayload.Type<>(
                ResourceLocation.fromNamespaceAndPath(TerminalRegistry.MOD_ID, "reopen_terminal"));

        public static final StreamCodec<FriendlyByteBuf, ReopenC2S> CODEC =
                new StreamCodec<FriendlyByteBuf, ReopenC2S>() {
                    @Override
                    public ReopenC2S decode(FriendlyByteBuf buf) {
                        return new ReopenC2S();
                    }

                    @Override
                    public void encode(FriendlyByteBuf buf, ReopenC2S p) {
                    }
                };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 服务端处理重开终端: 校验玩家手上确实拿着终端物品, 再走 HeldItemUIMenuType.openUI
     * (与 TerminalItem 右键同路径). 防止没有终端的玩家随意拉起菜单.
     */
    public static void onReopen(ReopenC2S payload, IPayloadContext ctx) {
        if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer player)
                || player.level().isClientSide || !player.isAlive()) {
            return;
        }
        for (net.minecraft.world.InteractionHand hand : net.minecraft.world.InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (!stack.isEmpty() && stack.getItem() == TerminalRegistry.TERMINAL.get()) {
                com.lowdragmc.lowdraglib2.gui.factory.HeldItemUIMenuType.openUI(player, hand);
                return;
            }
        }
    }

    /** 客户端 → 服务端: 清空回收站 (回收站应用两段确认后的最终点击). */
    public record ClearRecycleC2S() implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ClearRecycleC2S> TYPE = new CustomPacketPayload.Type<>(
                ResourceLocation.fromNamespaceAndPath(TerminalRegistry.MOD_ID, "clear_recycle"));

        public static final StreamCodec<FriendlyByteBuf, ClearRecycleC2S> CODEC =
                new StreamCodec<FriendlyByteBuf, ClearRecycleC2S>() {
                    @Override
                    public ClearRecycleC2S decode(FriendlyByteBuf buf) {
                        return new ClearRecycleC2S();
                    }

                    @Override
                    public void encode(FriendlyByteBuf buf, ClearRecycleC2S p) {
                    }
                };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 服务端处理清空回收站: 找到该玩家当前 menu 的回收站容器, 清空全部槽位
     * (物品直接销毁). 关 GUI 时 TerminalEvents 会把剩余物品还给玩家, 所以这里
     * 只处理玩家主动确认的清除.
     */
    public static void onClearRecycle(ClearRecycleC2S payload, IPayloadContext ctx) {
        if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer player) || player.level().isClientSide) {
            return;
        }
        net.minecraft.world.SimpleContainer bin = com.prefab.addon.terminal.ui.TerminalUI.recycleBin(player.getUUID());
        if (bin == null) {
            return;
        }
        for (int i = 0; i < bin.getContainerSize(); i++) {
            bin.setItem(i, ItemStack.EMPTY);
        }
        bin.setChanged();
        if (player.containerMenu != null) {
            player.containerMenu.broadcastChanges();
        }
    }

    /** 客户端入口: 关闭 GUI 时保存偏好 (gui 层不直接碰 PacketDistributor). */
    public static void sendSavePrefs(CompoundTag data) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new SavePrefsC2S(data));
    }

    /** 客户端入口: 请求重开终端 GUI (相机模式 ESC 退出后回到终端). */
    public static void sendReopenTerminal() {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new ReopenC2S());
    }

    /** 客户端入口: 清空回收站 (回收站应用两段确认后的最终点击). */
    public static void sendClearRecycle() {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new ClearRecycleC2S());
    }

    /** mod 总线注册: 只注册终端本体的 C2S 通道. */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(SavePrefsC2S.TYPE, SavePrefsC2S.CODEC, TerminalPayloads::onSavePrefs);
        registrar.playToServer(ReopenC2S.TYPE, ReopenC2S.CODEC, TerminalPayloads::onReopen);
        registrar.playToServer(ClearRecycleC2S.TYPE, ClearRecycleC2S.CODEC, TerminalPayloads::onClearRecycle);
    }

    private TerminalPayloads() {
    }
}
