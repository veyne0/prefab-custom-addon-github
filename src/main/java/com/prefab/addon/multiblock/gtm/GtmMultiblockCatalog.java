/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.neoforged.fml.ModList
 */
package com.prefab.addon.multiblock.gtm;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.multiblock.MultiblockShapeData;
import com.prefab.addon.multiblock.gtm.GtmShapeExtractor;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.neoforged.fml.ModList;

public final class GtmMultiblockCatalog {
    private static final String GTM_MOD_ID = "gtceu";
    private static volatile Map<String, MultiblockShapeData> cache = null;

    private GtmMultiblockCatalog() {
    }

    public static boolean isGtmLoaded() {
        return ModList.get().isLoaded(GTM_MOD_ID);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     * Enabled force condition propagation
     * Lifted jumps to return sites
     */
    public static Map<String, MultiblockShapeData> getAll() {
        Map<String, MultiblockShapeData> local = cache;
        if (local != null) return local;
        Class<GtmMultiblockCatalog> clazz = GtmMultiblockCatalog.class;
        synchronized (GtmMultiblockCatalog.class) {
            local = cache;
            if (local != null) return local;
            cache = local = GtmMultiblockCatalog.isGtmLoaded() ? Collections.unmodifiableMap(GtmShapeExtractor.extractAll()) : Collections.emptyMap();
            // ** MonitorExit[var1_1] (shouldn't be in output)
            return local;
        }
    }

    public static MultiblockShapeData getShape(String id) {
        return id == null ? null : GtmMultiblockCatalog.getAll().get(id);
    }

    public static List<String> getIds() {
        return List.copyOf(GtmMultiblockCatalog.getAll().keySet());
    }

    public static void init() {
        try {
            PrefabCustomAddon.LOGGER.info("[GT-MB] \u76ee\u5f55\u9884\u70ed: gtmLoaded={}", (Object)GtmMultiblockCatalog.isGtmLoaded());
            GtmMultiblockCatalog.getAll();
        }
        catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[GT-MB] \u76ee\u5f55\u9884\u70ed\u5931\u8d25", t);
        }
    }
}

