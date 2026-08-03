package com.prefab.addon.cloud;

import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 客户端云端建筑缓存.
 *
 * <p>数据源: 服务端定时/进服/变动时全量推 {@link CloudBuildingSyncPayload},
 * 客户端解包后存这里. GUI tab 直接读这份 cache 渲染卡片, 不再发请求.</p>
 *
 * <p>单机模式: 客户端 = 服务端, 但是云端 tab 在客户端跑, 所以仍然走 cache.
 * 建造完成时直接 add() 到 cache, 避免绕一圈 (仍然要发包, 因为建造是服务端跑的).</p>
 */
public final class CloudBuildingClientCache {

    private static final CloudBuildingClientCache INSTANCE = new CloudBuildingClientCache();
    public static CloudBuildingClientCache getInstance() { return INSTANCE; }

    /** 当前玩家的所有云端建筑 (按 add 顺序, 最新在最后). */
    private final List<CloudBuilding> buildings = new CopyOnWriteArrayList<>();

    /** 上次更新的 server tick (用于在 tab 上显示 "同步于 N 秒前" 之类的状态, 可选). */
    private long lastUpdateMs = 0;

    private CloudBuildingClientCache() {}

    /** 全量替换. 由 {@link CloudBuildingSyncPayload} 处理器调用. */
    public void replaceAll(List<CloudBuilding> newBuildings) {
        buildings.clear();
        if (newBuildings != null) buildings.addAll(newBuildings);
        lastUpdateMs = System.currentTimeMillis();
        PrefabCustomAddon.LOGGER.info("[CLOUD-CACHE] 全量替换: {} 个建筑", buildings.size());
    }

    /** 追加/替换单个. 本地新增 (如自己刚造完) 时用. */
    public void upsert(CloudBuilding b) {
        if (b == null) return;
        buildings.removeIf(x -> x.id.equals(b.id));
        buildings.add(b);
        lastUpdateMs = System.currentTimeMillis();
    }

    public void remove(String id) {
        buildings.removeIf(x -> x.id.equals(id));
        lastUpdateMs = System.currentTimeMillis();
    }

    public List<CloudBuilding> getAll() {
        return Collections.unmodifiableList(buildings);
    }

    public CloudBuilding getById(String id) {
        for (CloudBuilding b : buildings) if (b.id.equals(id)) return b;
        return null;
    }

    public int size() { return buildings.size(); }

    public long getLastUpdateMs() { return lastUpdateMs; }

    public boolean isEmpty() { return buildings.isEmpty(); }

    /** 清空 (切世界/退出游戏时). */
    public void clear() {
        buildings.clear();
        lastUpdateMs = 0;
    }

    // ============================================================
    // 发送 C2S 请求 (收回 / 放出)
    // ============================================================

    public void requestRecall(String buildingId) {
        com.prefab.addon.network.NetworkHandler.sendToServer(
            new CloudBuildingRecallPayload(buildingId));
    }

    /** 请求删除一个云端建筑. 服务端会校验 placed 状态, placed=true 会被拒绝. */
    public void requestDelete(String buildingId) {
        com.prefab.addon.network.NetworkHandler.sendToServer(
            new CloudBuildingDeletePayload(buildingId));
    }

    // 旧的"直接放出"方法已废弃 — 现在的放出流程是:
    //   GuiExtensionPackBrowser 放出按钮 → CloudPreview.start(id) → 开启世界预览
    //   玩家 ALT → StructurePreviewKeyHandler.triggerCloudSummon() → 发带 pos+facing 的 packet
    //   玩家右键 → cancel()
    // 这里不再保留无 pos/facing 的版本, 避免误用.

    // ============================================================
    // 单机/客户端本地操作 (不绕过服务端, 仅用于调试/直接调用)
    // ============================================================

    /** 单机模式调试: 直接给 cache 加一个, 不发包. */
    public void addLocal(CloudBuilding b) {
        upsert(b);
    }
}
