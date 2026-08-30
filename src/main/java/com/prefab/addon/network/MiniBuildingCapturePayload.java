package com.prefab.addon.network;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.blocks.MiniBuildingStorage;
import com.prefab.addon.items.MiniBuildingCaptureHelper;
import com.prefab.addon.items.MiniBuildingConverterItem;
import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端: 把两个角点发给服务端, 由服务端扫描区域 + 生成迷你方块.
 *
 * <p><b>为什么不让客户端 capture?</b> 之前客户端用 ClientLevel.getBlockState 扫描
 * 区域, 在玩家选完 ALT 确认时, ClientLevel 的 chunk 缓存可能尚未同步 / 区域未加载,
 * 导致 level.getBlockState() 全返回 AIR → 捕获 0 方块, 但 NBT 里的 width/height/depth
 * 是按 AABB 算的, 最终给玩家一个"0 方块"迷你方块 (从截图消息
 * "成功捕获 22x26x26 区域 (0 方块)" 可以看到). 改为服务端 scan 后, 服务端 level 跟
 * 世界状态永远一致, 不存在缓存问题.</p>
 *
 * <p>服务端流程:
 * <ol>
 *   <li>校验玩家还拿着迷你建筑转换器</li>
 *   <li>校验尺寸 (单边 ≤ 64)</li>
 *   <li>在 ServerLevel 上扫描, 生成迷你建筑 NBT</li>
 *   <li>创建 ItemStack (MINI_BUILDING_BLOCK_ITEM), 写入 NBT</li>
 *   <li>放进背包 (满则掉落)</li>
 *   <li>扣耐久 (创造模式不扣)</li>
 *   <li>反馈 (含方块数)</li>
 * </ol>
 */
public record MiniBuildingCapturePayload(BlockPos corner1, BlockPos corner2)
    implements CustomPacketPayload {

    public static final Type<MiniBuildingCapturePayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(PrefabCustomAddon.MOD_ID, "mini_building_capture"));

    public static final StreamCodec<ByteBuf, MiniBuildingCapturePayload> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC,    MiniBuildingCapturePayload::corner1,
            BlockPos.STREAM_CODEC,    MiniBuildingCapturePayload::corner2,
            MiniBuildingCapturePayload::new
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(final MiniBuildingCapturePayload payload, final IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;

        ctx.enqueueWork(() -> {
            // 1) 校验玩家还拿着迷你建筑转换器
            ItemStack mainHand = sp.getMainHandItem();
            ItemStack offHand  = sp.getOffhandItem();
            boolean isMain = mainHand.getItem() instanceof MiniBuildingConverterItem;
            boolean isOff  = offHand.getItem()  instanceof MiniBuildingConverterItem;
            if (!isMain && !isOff) {
                PrefabCustomAddon.LOGGER.warn("[MINI_BUILDING] Server: player {} not holding converter, refuse",
                    sp.getName().getString());
                sp.sendSystemMessage(Component.literal(
                    "§c[迷你建筑转换器] §7手持物品必须是迷你建筑转换器, 操作取消"
                ).withStyle(ChatFormatting.RED));
                return;
            }

            BlockPos c1 = payload.corner1();
            BlockPos c2 = payload.corner2();
            if (c1 == null || c2 == null) {
                PrefabCustomAddon.LOGGER.warn("[MINI_BUILDING] Server: corners null, refuse");
                return;
            }

            // 2) 校验尺寸
            int sx = Math.abs(c2.getX() - c1.getX()) + 1;
            int sy = Math.abs(c2.getY() - c1.getY()) + 1;
            int sz = Math.abs(c2.getZ() - c1.getZ()) + 1;
            if (sx > MiniBuildingConverterItem.MAX_SIZE
                || sy > MiniBuildingConverterItem.MAX_SIZE
                || sz > MiniBuildingConverterItem.MAX_SIZE) {
                sp.sendSystemMessage(Component.literal(
                    "§c[迷你建筑转换器] §7选区超过 " + MiniBuildingConverterItem.MAX_SIZE
                        + " 格 (实际 " + sx + "x" + sy + "x" + sz + "), 转换取消"
                ).withStyle(ChatFormatting.RED));
                return;
            }

            // 体积预检: 提前在服务端拦下过大的区域, 避免 NBT 估算/扫描浪费时间
            // 注意: 这里算的是 AABB 体积, 实际有效方块数会少 (空气/危险方块被跳过)
            long volume = (long) sx * sy * sz;
            if (volume > MiniBuildingCaptureHelper.MAX_BLOCKS) {
                sp.sendSystemMessage(Component.literal(
                    "§c[迷你建筑转换器] §7区域过大 (体积 " + volume
                        + " > " + MiniBuildingCaptureHelper.MAX_BLOCKS + " 块上限), 试着选更小的区域"
                ).withStyle(ChatFormatting.RED));
                return;
            }

            // 3) 服务端 level 扫描
            ServerLevel level = sp.serverLevel();
            BlockPos mn = new BlockPos(
                Math.min(c1.getX(), c2.getX()),
                Math.min(c1.getY(), c2.getY()),
                Math.min(c1.getZ(), c2.getZ()));
            BlockPos mx = new BlockPos(
                Math.max(c1.getX(), c2.getX()),
                Math.max(c1.getY(), c2.getY()),
                Math.max(c1.getZ(), c2.getZ()));
            AABB box = new AABB(
                mn.getX(), mn.getY(), mn.getZ(),
                mx.getX() + 1, mx.getY() + 1, mx.getZ() + 1);
            ItemStack miniBlock = MiniBuildingCaptureHelper.capture(level, box, sp);
            if (miniBlock == null || miniBlock.isEmpty()) {
                sp.sendSystemMessage(Component.literal(
                    "§c[迷你建筑转换器] §7捕获失败, 选区可能全是空气或被跳过的方块"
                ).withStyle(ChatFormatting.RED));
                return;
            }

            // [FIX] 先读 blockCount, 后面 Inventory.add 会把 miniBlock.setCount(0) 把它清空!
            // Inventory.add() 在 vanilla 1.21.1 的实现是:
            //   items.set(i, stack.copy());
            //   stack.setCount(0);  // 原 stack 引用被清空
            // 所以必须在 add 之前就读出 ref_block_count, 不然 chat 永远显示 0 方块.
            int blockCount = 0;
            {
                var preData = miniBlock.get(DataComponents.BLOCK_ENTITY_DATA);
                if (preData != null) {
                    CompoundTag beTag = preData.copyTag();
                    CompoundTag mb = beTag.contains("MiniBuilding")
                        ? beTag.getCompound("MiniBuilding") : beTag;
                    if (mb.contains(MiniBuildingStorage.KEY_REF_BLOCK_COUNT)) {
                        blockCount = mb.getInt(MiniBuildingStorage.KEY_REF_BLOCK_COUNT);
                    } else if (mb.contains("block_count")) {
                        blockCount = mb.getInt("block_count");
                    }
                }
                PrefabCustomAddon.LOGGER.info(
                    "[MINI_BUILDING] [DEBUG] payload: pre-add blockCount read, blockCount={}, present={}",
                    blockCount, preData != null);
            }

            // 4) 给玩家
            if (!sp.getInventory().add(miniBlock)) {
                sp.drop(miniBlock, false);
            }

            // 5) 扣耐久
            ItemStack held = isMain ? mainHand : offHand;
            EquipmentSlot slot = isMain ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
            if (!sp.isCreative()) {
                held.hurtAndBreak(1, sp, slot);
            }

            // 6) 反馈 (blockCount 已经在 add 之前读到, 直接用)
            sp.sendSystemMessage(Component.literal(
                "§a[迷你建筑转换器] §7成功捕获 " + sx + "x" + sy + "x" + sz + " 区域 ("
                    + blockCount + " 方块), 迷你建筑方块已放入背包"
            ).withStyle(ChatFormatting.GREEN));

            PrefabCustomAddon.LOGGER.info(
                "[MINI_BUILDING] Server: {} captured {}x{}x{} region ({} blocks)",
                sp.getName().getString(), sx, sy, sz, blockCount);
        });
    }
}
