package com.prefab.addon.multiblock.mbd2;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.multiblock.MultiblockShapeData;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.neoforged.fml.ModList;

/**
 * MBD2 (Multiblocked2) 多方块目录: 缓存 {@link Mbd2ShapeExtractor} 的提取结果.
 *
 * <p>与 GTM / Mekanism 目录同构, 由 {@code MultiblockCatalog} 聚合. MBD2 没装时
 * 目录为空, 不影响其他 source.</p>
 */
public final class Mbd2MultiblockCatalog {
    private static final String MBD2_MOD_ID = "mbd2";
    private static volatile Map<String, MultiblockShapeData> cache = null;

    private Mbd2MultiblockCatalog() {
    }

    public static boolean isMbd2Loaded() {
        return ModList.get().isLoaded(MBD2_MOD_ID);
    }

    public static Map<String, MultiblockShapeData> getAll() {
        Map<String, MultiblockShapeData> local = cache;
        if (local != null) return local;
        synchronized (Mbd2MultiblockCatalog.class) {
            local = cache;
            if (local != null) return local;
            cache = local = Mbd2MultiblockCatalog.isMbd2Loaded() ? Collections.unmodifiableMap(Mbd2ShapeExtractor.extractAll()) : Collections.emptyMap();
            return local;
        }
    }

    public static MultiblockShapeData getShape(String id) {
        return id == null ? null : Mbd2MultiblockCatalog.getAll().get(id);
    }

    public static List<String> getIds() {
        return List.copyOf(Mbd2MultiblockCatalog.getAll().keySet());
    }

    public static void init() {
        try {
            PrefabCustomAddon.LOGGER.info("[MBD2-MB] 目录预热: mbd2Loaded={}", (Object)Mbd2MultiblockCatalog.isMbd2Loaded());
            Mbd2MultiblockCatalog.getAll();
        }
        catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[MBD2-MB] 目录预热失败", t);
        }
    }
}
