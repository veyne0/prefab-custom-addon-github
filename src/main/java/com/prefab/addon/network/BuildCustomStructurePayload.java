package com.prefab.addon.network;

import com.prefab.addon.config.BuildAnimationMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端发送给服务端的包：放置自定义建筑
 *
 * <p>关键: 携带 {@code houseFacing} (预览时的旋转方向) 一起发给服务端,
 * 否则服务端会按未旋转的 NBT 坐标放置, 而客户端预览的是旋转后的位置 → 实际建造位置跟预览对不上.</p>
 *
 * <p>{@code animationMode}: 玩家在设置里选定的建造动画模式 (1.6.0 起支持 4 种):
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
 */
public record BuildCustomStructurePayload(
        BlockPos pos,
        String packName,
        String constructionId,
        Direction houseFacing,
        BuildAnimationMode animationMode,
        /**
         * 静默模式: KubeJS 联动蓝图 (带 player_blueprint tag, 但非 mod 原生 CustomBlueprintItem)
         * 建造时 = true. 服务端不会发任何聊天栏消息, 完成后不存云端. 普通 CustomBlueprintItem
         * 走的是 false (带消息 + 存云端的老路径).
         */
        boolean silent
) implements CustomPacketPayload {

    public BuildCustomStructurePayload(BlockPos pos, String packName, String constructionId) {
        this(pos, packName, constructionId, Direction.SOUTH, BuildAnimationMode.OFF, false);
    }

    public BuildCustomStructurePayload(BlockPos pos, String packName, String constructionId, Direction houseFacing) {
        this(pos, packName, constructionId, houseFacing, BuildAnimationMode.OFF, false);
    }

    public BuildCustomStructurePayload(BlockPos pos, String packName, String constructionId,
                                       Direction houseFacing, BuildAnimationMode animationMode) {
        this(pos, packName, constructionId, houseFacing, animationMode, false);
    }

    public static final CustomPacketPayload.Type<BuildCustomStructurePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("prefab_custom_addon", "build_custom_structure"));

    /** 1 字节 ordinal 写入, 客户端按 ordinal 还原. 越界 fallback 到 OFF (防止版本不匹配). */
    public static final StreamCodec<FriendlyByteBuf, BuildAnimationMode> MODE_STREAM_CODEC = StreamCodec.of(
            (buf, mode) -> buf.writeByte(mode != null ? mode.ordinal() : BuildAnimationMode.OFF.ordinal()),
            buf -> {
                try {
                    int ord = buf.readByte();
                    BuildAnimationMode[] values = BuildAnimationMode.values();
                    if (ord >= 0 && ord < values.length) return values[ord];
                } catch (Throwable ignored) {}
                return BuildAnimationMode.OFF;
            });

    /**
     * 第 6 个字段 silent 用 1 字节, 1=true, 0=false. StreamCodec.composite 公开重载到
     * 6 字段为止, 这里直接套 6-arg overload.
     */
    public static final StreamCodec<FriendlyByteBuf, BuildCustomStructurePayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, BuildCustomStructurePayload::pos,
                    net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8, BuildCustomStructurePayload::packName,
                    net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8, BuildCustomStructurePayload::constructionId,
                    Direction.STREAM_CODEC, BuildCustomStructurePayload::houseFacing,
                    MODE_STREAM_CODEC, BuildCustomStructurePayload::animationMode,
                    net.minecraft.network.codec.ByteBufCodecs.BOOL, BuildCustomStructurePayload::silent,
                    BuildCustomStructurePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
