package com.prefab.addon.network;

import com.prefab.addon.config.BuildAnimationMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;

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
 *       (用 {@code packName="outsource"} 作为标记, 让完成回调走外包蓝图消耗路径)</li>
 *   <li>任务完成时 {@code AsyncBuildManager.onCompleted} 检测到 outsource
 *       packName, 调 {@code OutsourceBuildManager.consumeOutsourceBlueprint}
 *       消耗对应的 {@code OutsourceBlueprintItem}</li>
 * </ol>
 *
 * <p>1.20.1 Forge 移植: NeoForge StreamCodec 改为 SimpleChannel 的 static encode/decode,
 * 编解码风格与 {@link BuildCustomStructurePayload} 一致.</p>
 */
public record BuildOutsourceStructurePayload(
        String buildingId,
        int styleIndex,
        BlockPos pos,
        Direction houseFacing,
        BuildAnimationMode animationMode
) {

    public static void encode(BuildOutsourceStructurePayload msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.buildingId());
        buf.writeVarInt(msg.styleIndex());
        buf.writeBlockPos(msg.pos());
        buf.writeEnum(msg.houseFacing());
        // 1 字节 ordinal 写入, 客户端按 ordinal 还原. 越界 fallback 到 OFF (防止版本不匹配).
        BuildAnimationMode mode = msg.animationMode() != null ? msg.animationMode() : BuildAnimationMode.OFF;
        buf.writeByte(mode.ordinal());
    }

    public static BuildOutsourceStructurePayload decode(FriendlyByteBuf buf) {
        String buildingId = buf.readUtf();
        int styleIndex = buf.readVarInt();
        BlockPos pos = buf.readBlockPos();
        Direction facing = buf.readEnum(Direction.class);
        BuildAnimationMode mode = BuildAnimationMode.OFF;
        try {
            int ord = buf.readByte();
            BuildAnimationMode[] values = BuildAnimationMode.values();
            if (ord >= 0 && ord < values.length) mode = values[ord];
        } catch (Throwable ignored) {}
        return new BuildOutsourceStructurePayload(buildingId, styleIndex, pos, facing, mode);
    }
}
