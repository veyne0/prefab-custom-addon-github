package com.prefab.addon.terminal.network;

import com.prefab.addon.terminal.TerminalRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 终端网络通道 (Forge SimpleChannel).
 *
 * 仅保留终端本体所需的通道: 偏好保存 (已打开的应用 tab 列表) 与重开终端 GUI.
 * 矿脉勘探 / 多方块相机 / 多方块建造相关的通道随 GTM 功能一并裁剪.
 */
public final class TerminalPayloads {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(TerminalRegistry.MOD_ID, "terminal"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int nextId = 0;

    /** 客户端 → 服务端: 终端偏好 (已打开的应用 tab 列表), 关闭 GUI 时发一次. */
    public record SavePrefsC2S(CompoundTag data) {
        public static void encode(SavePrefsC2S p, FriendlyByteBuf buf) {
            buf.writeNbt(p.data());
        }

        public static SavePrefsC2S decode(FriendlyByteBuf buf) {
            Tag tag = buf.readNbt();
            return new SavePrefsC2S(tag instanceof CompoundTag ct ? ct : new CompoundTag());
        }
    }

    /** 客户端 → 服务端: 请求重开终端 GUI (相机模式按 ESC 退出后回到终端主界面). */
    public record ReopenC2S() {
        public static void encode(ReopenC2S p, FriendlyByteBuf buf) {
        }

        public static ReopenC2S decode(FriendlyByteBuf buf) {
            return new ReopenC2S();
        }
    }

    /** 客户端 → 服务端: 清空回收站 (回收站应用两段确认后的最终点击). */
    public record ClearRecycleC2S() {
        public static void encode(ClearRecycleC2S p, FriendlyByteBuf buf) {
        }

        public static ClearRecycleC2S decode(FriendlyByteBuf buf) {
            return new ClearRecycleC2S();
        }
    }

    /** 客户端 → 服务端: 一键整理存储格 (存储软件 "一键整理" 按钮点击). */
    public record SortStorageC2S() {
        public static void encode(SortStorageC2S p, FriendlyByteBuf buf) {
        }

        public static SortStorageC2S decode(FriendlyByteBuf buf) {
            return new SortStorageC2S();
        }
    }

    /** 客户端 → 服务端: 多配方切换 (存储软件结果槽旁 < > 按钮点击, delta = ±1). */
    public record CycleCraftRecipeC2S(int delta) {
        public static void encode(CycleCraftRecipeC2S p, FriendlyByteBuf buf) {
            buf.writeVarInt(p.delta());
        }

        public static CycleCraftRecipeC2S decode(FriendlyByteBuf buf) {
            return new CycleCraftRecipeC2S(buf.readVarInt());
        }
    }

    /** 客户端 → 服务端: 回收站 tab 内 shift+点击背包物品, 请求直接移入回收站 (绕过 vanilla quickMove). */
    public record MoveToRecycleC2S(int containerSlot) {
        public static void encode(MoveToRecycleC2S p, FriendlyByteBuf buf) {
            buf.writeVarInt(p.containerSlot());
        }

        public static MoveToRecycleC2S decode(FriendlyByteBuf buf) {
            return new MoveToRecycleC2S(buf.readVarInt());
        }
    }

    /** mod 构造时调用: 注册全部终端 C2S 通道. */
    public static void register() {
        registerMessage(SavePrefsC2S.class, SavePrefsC2S::encode, SavePrefsC2S::decode, TerminalPayloads::applySavePrefs);
        registerMessage(ReopenC2S.class, ReopenC2S::encode, ReopenC2S::decode, TerminalPayloads::applyReopen);
        registerMessage(ClearRecycleC2S.class, ClearRecycleC2S::encode, ClearRecycleC2S::decode, TerminalPayloads::applyClearRecycle);
        registerMessage(SortStorageC2S.class, SortStorageC2S::encode, SortStorageC2S::decode, TerminalPayloads::applySortStorage);
        registerMessage(CycleCraftRecipeC2S.class, CycleCraftRecipeC2S::encode, CycleCraftRecipeC2S::decode, TerminalPayloads::applyCycleCraftRecipe);
        registerMessage(MoveToRecycleC2S.class, MoveToRecycleC2S::encode, MoveToRecycleC2S::decode, TerminalPayloads::applyMoveToRecycle);
    }

    private static <MSG> void registerMessage(Class<MSG> type,
                                              BiConsumer<MSG, FriendlyByteBuf> encoder,
                                              Function<FriendlyByteBuf, MSG> decoder,
                                              BiConsumer<MSG, ServerPlayer> handler) {
        INSTANCE.registerMessage(nextId++, type, encoder, decoder, (msg, ctxSup) -> {
            NetworkEvent.Context ctx = ctxSup.get();
            ctx.enqueueWork(() -> handler.accept(msg, ctx.getSender()));
            ctx.setPacketHandled(true);
        });
    }

    /**
     * 服务端处理偏好保存: OpenTabs 写回手上终端物品普通 NBT (1.21.1 的 CUSTOM_DATA 在
     * 1.20.1 没有对应物, 全部走 stack tag).
     */
    public static void applySavePrefs(SavePrefsC2S payload, ServerPlayer player) {
        if (player == null || player.level().isClientSide) {
            return;
        }
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.isEmpty() || stack.getItem() != TerminalRegistry.TERMINAL.get()) {
                continue;
            }
            TerminalRegistry.LOGGER.info("[DEVICE] SavePrefs: 写入手上终端@{} keys={}",
                    System.identityHashCode(stack),
                    stack.hasTag() ? stack.getTag().getAllKeys() : java.util.List.of());
            CompoundTag merged = stack.getOrCreateTag();
            merged.merge(payload.data().copy());
            break;
        }
    }

    /**
     * 服务端处理重开终端: 校验玩家手上确实拿着终端物品, 再走 HeldItemUIMenuType.openUI
     * (与 TerminalItem 右键同路径). 防止没有终端的玩家随意拉起菜单.
     */
    public static void applyReopen(ReopenC2S payload, ServerPlayer player) {
        if (player == null || player.level().isClientSide || !player.isAlive()) {
            return;
        }
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (!stack.isEmpty() && stack.getItem() == TerminalRegistry.TERMINAL.get()) {
                com.lowdragmc.lowdraglib2.gui.factory.HeldItemUIMenuType.openUI(player, hand);
                return;
            }
        }
    }

    /**
     * 服务端处理清空回收站: 找到该玩家当前 menu 的回收站容器, 清空全部槽位
     * (物品直接销毁). 关 GUI 时 TerminalEvents 会把剩余物品还给玩家, 所以这里
     * 只处理玩家主动确认的清除.
     */
    public static void applyClearRecycle(ClearRecycleC2S payload, ServerPlayer player) {
        if (player == null || player.level().isClientSide) {
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

    /** 服务端处理一键整理: 合并同物品 + 排序写回存储格 (持久化 listener 自动触发). */
    public static void applySortStorage(SortStorageC2S payload, ServerPlayer player) {
        if (player == null || player.level().isClientSide) {
            return;
        }
        com.prefab.addon.terminal.ui.TerminalUI.sortStorage(player);
        if (player.containerMenu != null) {
            player.containerMenu.broadcastChanges();
        }
    }

    /** 服务端处理多配方切换: 推进索引 + 重算结果预览 (result 槽随 broadcastChanges 同步客户端). */
    public static void applyCycleCraftRecipe(CycleCraftRecipeC2S payload, ServerPlayer player) {
        if (player == null || player.level().isClientSide) {
            return;
        }
        com.prefab.addon.terminal.ui.TerminalUI.cycleCraftRecipe(player, payload.delta());
        if (player.containerMenu != null) {
            player.containerMenu.broadcastChanges();
        }
    }

    /**
     * 服务端处理"移入回收站": 从玩家背包 (items 0-35) 取物品塞进该玩家的回收站 bin.
     * 回收站 tab 内 vanilla quickMove 按 menu 槽注册序把物品路由进存储格, 客户端
     * 拦截 shift+点击后改走这里. 拒收终端本体 (与 bin mayPlace 一致); bin 满则剩余留背包.
     */
    public static void applyMoveToRecycle(MoveToRecycleC2S payload, ServerPlayer player) {
        if (player == null || player.level().isClientSide) {
            return;
        }
        // 仅在终端 GUI (ModularUI menu) 打开时受理
        if (!(player.containerMenu instanceof com.lowdragmc.lowdraglib2.gui.holder.ModularUIContainerMenu)) {
            return;
        }
        net.minecraft.world.SimpleContainer bin = com.prefab.addon.terminal.ui.TerminalUI.recycleBin(player.getUUID());
        if (bin == null) {
            return;
        }
        int cs = payload.containerSlot();
        if (cs < 0 || cs >= player.getInventory().items.size()) {
            return;
        }
        ItemStack source = player.getInventory().getItem(cs);
        if (source.isEmpty() || source.getItem() == TerminalRegistry.TERMINAL.get()) {
            return;
        }
        ItemStack leftover = source.copy();
        // 先与 bin 内同物品合并 (1.20.1 没有 Container.addItem, 手写)
        for (int i = 0; i < bin.getContainerSize() && !leftover.isEmpty(); i++) {
            ItemStack existing = bin.getItem(i);
            if (!existing.isEmpty() && ItemStack.isSameItemSameTags(existing, leftover)) {
                int moved = Math.min(leftover.getCount(),
                        Math.min(bin.getMaxStackSize(), existing.getMaxStackSize()) - existing.getCount());
                if (moved > 0) {
                    existing.grow(moved);
                    leftover.shrink(moved);
                    bin.setItem(i, existing);
                }
            }
        }
        // 再填空槽
        for (int i = 0; i < bin.getContainerSize() && !leftover.isEmpty(); i++) {
            if (bin.getItem(i).isEmpty()) {
                int put = Math.min(leftover.getCount(), Math.min(bin.getMaxStackSize(), leftover.getMaxStackSize()));
                bin.setItem(i, leftover.split(put));
            }
        }
        player.getInventory().setItem(cs, leftover.isEmpty() ? ItemStack.EMPTY : leftover);
        bin.setChanged();
        if (player.containerMenu != null) {
            player.containerMenu.broadcastChanges();
        }
    }

    /** 客户端入口: 关闭 GUI 时保存偏好 (gui 层不直接碰 SimpleChannel). */
    public static void sendSavePrefs(CompoundTag data) {
        INSTANCE.sendToServer(new SavePrefsC2S(data));
    }

    /** 客户端入口: 请求重开终端 GUI (相机模式 ESC 退出后回到终端). */
    public static void sendReopenTerminal() {
        INSTANCE.sendToServer(new ReopenC2S());
    }

    /** 客户端入口: 清空回收站 (回收站应用两段确认后的最终点击). */
    public static void sendClearRecycle() {
        INSTANCE.sendToServer(new ClearRecycleC2S());
    }

    /** 客户端入口: 一键整理存储格. */
    public static void sendSortStorage() {
        INSTANCE.sendToServer(new SortStorageC2S());
    }

    /** 客户端入口: 多配方切换 (本地预测由 GUI 层自行处理, 这里通知服务端真源). */
    public static void sendCycleCraftRecipe(int delta) {
        INSTANCE.sendToServer(new CycleCraftRecipeC2S(delta));
    }

    /** 客户端入口: 回收站 tab 内 shift+点击背包物品 → 请求直接移入回收站. */
    public static void sendMoveToRecycle(int containerSlot) {
        INSTANCE.sendToServer(new MoveToRecycleC2S(containerSlot));
    }

    private TerminalPayloads() {
    }
}
