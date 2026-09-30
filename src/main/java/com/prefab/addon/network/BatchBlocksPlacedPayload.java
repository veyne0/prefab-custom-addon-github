package com.prefab.addon.network;

import com.prefab.addon.config.BuildAnimationMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * <p><b>序列化方案</b>: BlockState 用 NBT (项目自实现的 writeBlockState / readBlockState).
 * 每个方块 NBT 大约几十字节, 1000 块一批 = 几十 KB, 完全在限额内.</p>
 */
public record BatchBlocksPlacedPayload(
        List<BlockPos> positions,
        List<BlockState> states,
        BuildAnimationMode mode
) {

    public static void encode(BatchBlocksPlacedPayload p, FriendlyByteBuf buf) {
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
            buf.writeBlockPos(p.positions.get(i));
            // 用项目自实现的 writeBlockState, 避开版本间 NbtUtils API 变化.
            CompoundTag tag = writeBlockState(p.states.get(i));
            buf.writeNbt(tag);
        }
    }

    public static BatchBlocksPlacedPayload decode(FriendlyByteBuf buf) {
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
            positions.add(buf.readBlockPos());
            CompoundTag tag = buf.readNbt();
            // 失败时 fallback 到 AIR (不会崩, 只是这一个方块不显示动画, 真方块照常可见).
            BlockState state = readBlockState(tag);
            if (state == null) state = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            states.add(state);
        }
        return new BatchBlocksPlacedPayload(positions, states, mode);
    }

    // ============================================================
    // BlockState NBT 工具 (自实现, 避开 1.21.1 NbtUtils.readBlockState/writeBlockState 签名变化)
    // 用 vanilla structure 格式: {Name, Properties?}
    // ============================================================

    /** 写 BlockState. 失败返回空 tag. */
    private static CompoundTag writeBlockState(BlockState state) {
        CompoundTag tag = new CompoundTag();
        if (state == null) return tag;
        try {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            tag.putString("Name", id == null ? "minecraft:air" : id.toString());
            Map<Property<?>, Comparable<?>> props = state.getValues();
            if (!props.isEmpty()) {
                CompoundTag p = new CompoundTag();
                for (Map.Entry<Property<?>, Comparable<?>> e : props.entrySet()) {
                    p.putString(e.getKey().getName(), e.getValue().toString());
                }
                tag.put("Properties", p);
            }
        } catch (Exception ignored) {}
        return tag;
    }

    /** 读 BlockState. 失败返回 null. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState readBlockState(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) return null;
        try {
            if (!tag.contains("Name", Tag.TAG_STRING)) return null;
            String name = tag.getString("Name");
            Block block = BuiltInRegistries.BLOCK.getOptional(new ResourceLocation(name))
                .orElse(Blocks.AIR);
            BlockState state = block.defaultBlockState();
            if (tag.contains("Properties", Tag.TAG_COMPOUND) && block != Blocks.AIR) {
                CompoundTag props = tag.getCompound("Properties");
                for (String key : props.getAllKeys()) {
                    Property<?> property = block.getStateDefinition().getProperty(key);
                    if (property == null) continue;
                    String value = props.getString(key);
                    Optional<?> opt = property.getValue(value);
                    if (opt.isEmpty()) continue;
                    try {
                        state = state.setValue((Property) property, (Comparable) opt.get());
                    } catch (Throwable ignored) {}
                }
            }
            return state;
        } catch (Exception e) {
            return null;
        }
    }
}
