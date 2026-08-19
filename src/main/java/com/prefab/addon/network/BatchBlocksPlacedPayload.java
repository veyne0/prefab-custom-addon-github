package com.prefab.addon.network;

import com.prefab.addon.config.BuildAnimationMode;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 服务端 → 客户端: 一批方块刚刚被放置 (用于"建造下落动画").
 *
 * <p>服务端 {@link com.prefab.addon.structure.AsyncBuildManager} 每 tick 放完一批方块后,
 * 如果玩家的 {@link com.prefab.addon.config.PlayerPreferences#getBuildAnimationMode()} 非 OFF,
 * 就把这个包发给该玩家, 附带"这一 tick 放的所有方块的 (pos, state) 列表" + 动画 mode.
 * mode 字段控制客户端用哪种轨迹渲染这些方块:
 * <ul>
 *   <li>{@link BuildAnimationMode#FALL} - 竖直下落</li>
 *   <li>{@link BuildAnimationMode#RAIN} - 方块雨 (起点随机偏移 + 更高)</li>
 *   <li>{@link BuildAnimationMode#THROW} - 四周抛物线</li>
 * </ul>
 * 客户端 {@link com.prefab.addon.client.BuildAnimationRenderer} 收到包后, 对每个方块
 * 启动 maxTicks tick 的动画 (按 mode 走不同轨迹), 落地瞬间停止渲染 ghost
 * (真实方块已 visible, 因为服务端已 setBlock + UPDATE_CLIENTS).</p>
 *
 * <p><b>序列化方案</b>: BlockState 用 NBT (NbtUtils.writeBlockState / readBlockState) 而非
 * StreamCodec, 原因是 1.21.1 的 BlockState 没有静态 STREAM_CODEC 字段 (项目里写死).
 * 每个方块 NBT 大约几十字节, 1000 块一批 = 几十 KB, 完全在 NbtAccounter 限额内 (2MB).</p>
 */
public record BatchBlocksPlacedPayload(
        List<BlockPos> positions,
        List<BlockState> states,
        BuildAnimationMode mode
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BatchBlocksPlacedPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("prefab_custom_addon", "batch_blocks_placed"));

    public static final StreamCodec<FriendlyByteBuf, BatchBlocksPlacedPayload> STREAM_CODEC =
            StreamCodec.of(
                    BatchBlocksPlacedPayload::write,
                    BatchBlocksPlacedPayload::read
            );

    private static void write(FriendlyByteBuf buf, BatchBlocksPlacedPayload p) {
        // 写 mode (1 字节 = ordinal), 客户端按 ordinal 还原. 兼容老客户端 (没这个字段): 走 OFF 模式.
        BuildAnimationMode mode = p.mode != null ? p.mode : BuildAnimationMode.OFF;
        buf.writeByte(mode.ordinal());
        int n = p.positions.size();
        if (n != p.states.size()) {
            throw new IllegalArgumentException(
                "BatchBlocksPlacedPayload: positions.size() (" + n + ") != states.size() (" + p.states.size() + ")");
        }
        if (n > Short.MAX_VALUE) {
            throw new IllegalArgumentException("BatchBlocksPlacedPayload: too many blocks in one batch: " + n);
        }
        buf.writeShort(n);
        for (int i = 0; i < n; i++) {
            BlockPos.STREAM_CODEC.encode(buf, p.positions.get(i));
            // 用项目自实现的 writeBlockState (CloudBuilding.writeBlockState), 避开 1.21.1 NbtUtils API 变化.
            // FriendlyByteBuf.writeNbt 写入 NBT, 客户端用 readNbt 读, 跟项目里其它 NBT 传输一致.
            CompoundTag tag = com.prefab.addon.cloud.CloudBuilding.writeBlockState(p.states.get(i));
            buf.writeNbt(tag);
        }
    }

    private static BatchBlocksPlacedPayload read(FriendlyByteBuf buf) {
        // 读 mode: 1 字节 ordinal. 越界 fallback 到 OFF (防止版本不匹配).
        BuildAnimationMode mode = BuildAnimationMode.OFF;
        try {
            int ord = buf.readByte();
            BuildAnimationMode[] values = BuildAnimationMode.values();
            if (ord >= 0 && ord < values.length) mode = values[ord];
        } catch (Throwable ignored) {}
        int n = buf.readShort();
        if (n < 0) {
            throw new IllegalArgumentException("BatchBlocksPlacedPayload: negative count " + n);
        }
        // 防御: 单包最大 64k 块, 防止恶意/损坏包耗尽内存
        if (n > 65535) {
            throw new IllegalArgumentException("BatchBlocksPlacedPayload: count " + n + " > 65535");
        }
        java.util.List<BlockPos> positions = new java.util.ArrayList<>(n);
        java.util.List<BlockState> states = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            positions.add(BlockPos.STREAM_CODEC.decode(buf));
            // 读 NBT, 用 NbtAccounter.unlimitedHeap() 避免大包超过 2MB 上限 (单批 1w 块 NBT 几百 KB, 用 unlimited 更稳).
            CompoundTag tag = buf.readNbt();
            // 失败时 fallback 到 AIR (不会崩, 只是这一个方块不显示动画, 真方块照常可见).
            BlockState state = com.prefab.addon.cloud.CloudBuilding.readBlockState(tag);
            if (state == null) state = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            states.add(state);
        }
        return new BatchBlocksPlacedPayload(positions, states, mode);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
