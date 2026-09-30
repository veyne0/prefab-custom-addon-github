package com.prefab.addon.network;

import com.prefab.addon.config.BuildAnimationMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;

/**
 * 客户端发送给服务端的包：放置自定义建筑
 *
 * <p>关键: 携带 {@code houseFacing} (预览时的旋转方向) 一起发给服务端,
 * 否则服务端会按未旋转的 NBT 坐标放置, 而客户端预览的是旋转后的位置 → 实际建造位置跟预览对不上.</p>
 *
 * <p>{@code animationMode}: 玩家在设置里选定的建造动画模式:
 * <ul>
 *   <li>{@link BuildAnimationMode#OFF} - 不放动画, 跟原版 prefab 一样快 (可能卡顿)</li>
 *   <li>{@link BuildAnimationMode#FALL} - 1 块/tick, 客户端竖直下落</li>
 *   <li>{@link BuildAnimationMode#RAIN} - 1 块/tick, 客户端方块雨</li>
 *   <li>{@link BuildAnimationMode#THROW} - 1 块/tick, 客户端四周抛过来</li>
 * </ul>
 * 服务端 {@link com.prefab.addon.structure.AsyncBuildManager} 收到后:
 * <ol>
 *   <li>当 mode != OFF 时强制 batchSize=1 (避免 1 tick 全放完 → 没动画时间)</li>
 *   <li>每 tick 放完一批后, 通过 {@link BatchBlocksPlacedPayload} 把这一批方块 + mode
 *       告诉客户端, 客户端用 {@link com.prefab.addon.client.BuildAnimationRenderer} 按 mode
 *       渲染不同轨迹的动画.</li>
 * </ol>
 *
 * <p>1.20.1 Forge 移植: NeoForge StreamCodec 改为 SimpleChannel 的 static encode/decode.</p>
 */
public record BuildCustomStructurePayload(
        BlockPos pos,
        String packName,
        String constructionId,
        Direction houseFacing,
        BuildAnimationMode animationMode
) {

    public BuildCustomStructurePayload(BlockPos pos, String packName, String constructionId) {
        this(pos, packName, constructionId, Direction.SOUTH, BuildAnimationMode.OFF);
    }

    public BuildCustomStructurePayload(BlockPos pos, String packName, String constructionId, Direction houseFacing) {
        this(pos, packName, constructionId, houseFacing, BuildAnimationMode.OFF);
    }

    public static void encode(BuildCustomStructurePayload msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos());
        buf.writeUtf(msg.packName());
        buf.writeUtf(msg.constructionId());
        buf.writeEnum(msg.houseFacing());
        // 1 字节 ordinal 写入, 客户端按 ordinal 还原. 越界 fallback 到 OFF (防止版本不匹配).
        BuildAnimationMode mode = msg.animationMode() != null ? msg.animationMode() : BuildAnimationMode.OFF;
        buf.writeByte(mode.ordinal());
    }

    public static BuildCustomStructurePayload decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String packName = buf.readUtf();
        String constructionId = buf.readUtf();
        Direction facing = buf.readEnum(Direction.class);
        BuildAnimationMode mode = BuildAnimationMode.OFF;
        try {
            int ord = buf.readByte();
            BuildAnimationMode[] values = BuildAnimationMode.values();
            if (ord >= 0 && ord < values.length) mode = values[ord];
        } catch (Throwable ignored) {}
        return new BuildCustomStructurePayload(pos, packName, constructionId, facing, mode);
    }
}
