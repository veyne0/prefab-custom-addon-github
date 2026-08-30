package com.prefab.addon.network;

import com.prefab.addon.config.BuildAnimationMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：放置外包建筑 (8 个硬编码 OutsourceBlueprintItem 之一).
 *
 * <p>走的是和 {@link BuildCustomStructurePayload} 完全不同的服务端路径, 原因:
 * 外包建筑 <strong>不在</strong> {@code ExtensionPackManager} 里, 它由
 * {@link com.prefab.addon.outsource.OutsourceBuildingLoader} 从
 * {@code <gameDir>/prefab-outsource/*.zip} 扫描, 跟普通的 extension pack 是
 * 两套完全独立的数据.</p>
 *
 * <p>服务端处理流程:
 * <ol>
 *   <li>{@link com.prefab.addon.structure.OutsourceBuildManager#placeStructure} 强制重扫
 *       OutsourceBuildingLoader, 拿到对应 building + style 的 NBT 字节</li>
 *   <li>解析 NBT → BlockData 列表, 复用 {@code CustomStructureBuilder.parseStructureBlocksFromNbt}</li>
 *   <li>复用 {@code AsyncBuildManager.startTask} 启动异步分批放置
 *       (用 {@code packName="outsource"} 作为标记, 让 consume 走外包路径)</li>
 *   <li>任务完成时 {@code AsyncBuildManager.consumeBlueprint} 检测到 outsource
 *       packName, 调 {@code OutsourceBuildManager.consumeOutsourceBlueprint}
 *       消耗对应的 {@code OutsourceBlueprintItem}</li>
 * </ol>
 */
public record BuildOutsourceStructurePayload(
        String buildingId,
        int styleIndex,
        BlockPos pos,
        Direction houseFacing,
        BuildAnimationMode animationMode
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BuildOutsourceStructurePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("prefab_custom_addon", "build_outsource_structure"));

    public static final StreamCodec<FriendlyByteBuf, BuildOutsourceStructurePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, BuildOutsourceStructurePayload::buildingId,
                    ByteBufCodecs.VAR_INT, BuildOutsourceStructurePayload::styleIndex,
                    BlockPos.STREAM_CODEC, BuildOutsourceStructurePayload::pos,
                    Direction.STREAM_CODEC, BuildOutsourceStructurePayload::houseFacing,
                    BuildCustomStructurePayload.MODE_STREAM_CODEC, BuildOutsourceStructurePayload::animationMode,
                    BuildOutsourceStructurePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
