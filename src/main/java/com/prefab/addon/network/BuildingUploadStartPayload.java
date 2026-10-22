package com.prefab.addon.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 客户端 → 服务端：开始上传一个建筑 zip。
 *
 * 字段：
 *   - fileName: 目标文件名 (必须以 .zip 结尾, 服务端会再校验/安全化)
 *   - totalSize: zip 完整大小 (服务端按它做上限校验)
 *   - sha1: 客户端算好的 zip SHA-1 (服务端收完校验完整性)
 *
 * 与下载方向 (ServerPackSyncServer) 镜像: 分片 + ACK 流控, 每 32 KiB 一片。
 */
public record BuildingUploadStartPayload(
        String fileName,
        long totalSize,
        String sha1
) {

    public static void encode(BuildingUploadStartPayload msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.fileName());
        buf.writeVarLong(msg.totalSize());
        buf.writeUtf(msg.sha1());
    }

    public static BuildingUploadStartPayload decode(FriendlyByteBuf buf) {
        return new BuildingUploadStartPayload(buf.readUtf(), buf.readVarLong(), buf.readUtf());
    }
}
