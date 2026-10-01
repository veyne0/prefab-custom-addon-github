/*
 * Decompiled with CFR 0.152.
 */
package com.prefab.addon.multiblock;

import com.prefab.addon.multiblock.MultiblockShapeData;
import com.prefab.addon.multiblock.gtm.GtmMultiblockCatalog;
import com.prefab.addon.multiblock.mbd2.Mbd2MultiblockCatalog;
import com.prefab.addon.multiblock.mekanism.MekanismShapeTemplates;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MultiblockCatalog {
    public static final String SOURCE_GTM = "gtm";
    public static final String SOURCE_MEKANISM = "mekanism";
    public static final String SOURCE_MBD2 = "mbd2";
    private static volatile Map<String, MultiblockShapeData> cache = null;

    private MultiblockCatalog() {
    }

    public static boolean isGtmLoaded() {
        return GtmMultiblockCatalog.isGtmLoaded();
    }

    public static boolean isMekanismLoaded() {
        return MekanismShapeTemplates.isMekanismLoaded();
    }

    public static boolean isMbd2Loaded() {
        return Mbd2MultiblockCatalog.isMbd2Loaded();
    }

    public static boolean isAnyLoaded() {
        return MultiblockCatalog.isGtmLoaded() || MultiblockCatalog.isMekanismLoaded() || MultiblockCatalog.isMbd2Loaded();
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     * Enabled force condition propagation
     * Lifted jumps to return sites
     */
    public static Map<String, MultiblockShapeData> getAll() {
        Map<String, MultiblockShapeData> local = cache;
        if (local != null) return local;
        Class<MultiblockCatalog> clazz = MultiblockCatalog.class;
        synchronized (MultiblockCatalog.class) {
            local = cache;
            if (local != null) return local;
            LinkedHashMap<String, MultiblockShapeData> merged = new LinkedHashMap<String, MultiblockShapeData>();
            merged.putAll(GtmMultiblockCatalog.getAll());
            merged.putAll(MekanismShapeTemplates.getAll());
            merged.putAll(Mbd2MultiblockCatalog.getAll());
            cache = local = merged.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(merged);
            // ** MonitorExit[var1_1] (shouldn't be in output)
            return local;
        }
    }

    public static MultiblockShapeData getShape(String id) {
        return id == null ? null : MultiblockCatalog.getAll().get(id);
    }

    public static List<String> getIds(String sourceFilter) {
        if (sourceFilter == null) {
            return List.copyOf(MultiblockCatalog.getAll().keySet());
        }
        ArrayList<String> out = new ArrayList<String>();
        for (Map.Entry<String, MultiblockShapeData> e : MultiblockCatalog.getAll().entrySet()) {
            if (!sourceFilter.equals(e.getValue().source)) continue;
            out.add(e.getKey());
        }
        return out;
    }

    public static List<String> getIds() {
        return MultiblockCatalog.getIds(null);
    }

    public static List<String> getAvailableSources() {
        boolean gtm = false;
        boolean mek = false;
        boolean mbd2 = false;
        for (MultiblockShapeData s : MultiblockCatalog.getAll().values()) {
            if (SOURCE_GTM.equals(s.source)) {
                gtm = true;
                continue;
            }
            if (SOURCE_MEKANISM.equals(s.source)) {
                mek = true;
                continue;
            }
            if (!SOURCE_MBD2.equals(s.source)) continue;
            mbd2 = true;
        }
        ArrayList<String> out = new ArrayList<String>(3);
        if (gtm) {
            out.add(SOURCE_GTM);
        }
        if (mek) {
            out.add(SOURCE_MEKANISM);
        }
        if (mbd2) {
            out.add(SOURCE_MBD2);
        }
        return out;
    }

    public static String sourceDisplayName(String source) {
        if (SOURCE_GTM.equals(source)) {
            return "\u683c\u96f7\u79d1\u6280";
        }
        if (SOURCE_MEKANISM.equals(source)) {
            return "\u901a\u7528\u673a\u68b0";
        }
        if (SOURCE_MBD2.equals(source)) {
            return "MBD2 \u81ea\u5b9a\u4e49";
        }
        return source == null ? "\u672a\u77e5" : source;
    }

    public static void init() {
        GtmMultiblockCatalog.init();
        MekanismShapeTemplates.init();
        Mbd2MultiblockCatalog.init();
        MultiblockCatalog.getAll();
    }
}

