/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.BlockPos
 *  net.minecraft.core.registries.BuiltInRegistries
 *  net.minecraft.resources.ResourceLocation
 *  net.minecraft.world.level.block.Block
 *  net.minecraft.world.level.block.Blocks
 *  net.minecraft.world.level.block.state.BlockState
 *  net.neoforged.fml.ModList
 */
package com.prefab.addon.multiblock.mekanism;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.multiblock.MultiblockShapeData;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

public final class MekanismShapeTemplates {
    private static final String MEKANISM_MOD_ID = "mekanism";
    private static final String MEKANISM_GENERATORS_MOD_ID = "mekanismgenerators";
    private static final String NS_MEK = "mekanism";
    private static final String NS_GEN = "mekanismgenerators";
    private static volatile Map<String, MultiblockShapeData> cache = null;

    private MekanismShapeTemplates() {
    }

    public static boolean isMekanismLoaded() {
        return ModList.get().isLoaded("mekanism");
    }

    public static boolean isMekanismGeneratorsLoaded() {
        return ModList.get().isLoaded("mekanismgenerators");
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     * Enabled force condition propagation
     * Lifted jumps to return sites
     */
    public static Map<String, MultiblockShapeData> getAll() {
        Map<String, MultiblockShapeData> local = cache;
        if (local != null) return local;
        Class<MekanismShapeTemplates> clazz = MekanismShapeTemplates.class;
        synchronized (MekanismShapeTemplates.class) {
            local = cache;
            if (local != null) return local;
            LinkedHashMap<String, MultiblockShapeData> out = new LinkedHashMap<String, MultiblockShapeData>();
            try {
                if (MekanismShapeTemplates.isMekanismLoaded()) {
                    MekanismShapeTemplates.addIfPresent(out, MekanismShapeTemplates.buildDynamicTank());
                    MekanismShapeTemplates.addIfPresent(out, MekanismShapeTemplates.buildInductionMatrix());
                    MekanismShapeTemplates.addIfPresent(out, MekanismShapeTemplates.buildThermoelectricBoiler());
                    MekanismShapeTemplates.addIfPresent(out, MekanismShapeTemplates.buildThermalEvaporationTower());
                    MekanismShapeTemplates.addIfPresent(out, MekanismShapeTemplates.buildSps());
                }
                if (MekanismShapeTemplates.isMekanismGeneratorsLoaded()) {
                    MekanismShapeTemplates.addIfPresent(out, MekanismShapeTemplates.buildFissionReactor());
                    MekanismShapeTemplates.addIfPresent(out, MekanismShapeTemplates.buildFusionReactor());
                    MekanismShapeTemplates.addIfPresent(out, MekanismShapeTemplates.buildIndustrialTurbine());
                }
            }
            catch (Throwable t) {
                PrefabCustomAddon.LOGGER.error("[MEK-MB] \u6a21\u677f\u6784\u5efa\u5f02\u5e38", t);
            }
            PrefabCustomAddon.LOGGER.info("[MEK-MB] \u6a21\u677f\u6784\u5efa\u5b8c\u6210: {} \u4e2a (mek={}, generators={})", new Object[]{out.size(), MekanismShapeTemplates.isMekanismLoaded(), MekanismShapeTemplates.isMekanismGeneratorsLoaded()});
            cache = local = out.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(out);
            // ** MonitorExit[var1_1] (shouldn't be in output)
            return local;
        }
    }

    public static MultiblockShapeData getShape(String id) {
        return id == null ? null : MekanismShapeTemplates.getAll().get(id);
    }

    public static List<String> getIds() {
        return List.copyOf(MekanismShapeTemplates.getAll().keySet());
    }

    public static void init() {
        try {
            PrefabCustomAddon.LOGGER.info("[MEK-MB] \u76ee\u5f55\u9884\u70ed: mek={}, generators={}", (Object)MekanismShapeTemplates.isMekanismLoaded(), (Object)MekanismShapeTemplates.isMekanismGeneratorsLoaded());
            MekanismShapeTemplates.getAll();
        }
        catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[MEK-MB] \u76ee\u5f55\u9884\u70ed\u5931\u8d25", t);
        }
    }

    private static void addIfPresent(Map<String, MultiblockShapeData> out, MultiblockShapeData data) {
        if (data != null && !data.localBlocks.isEmpty()) {
            out.put(data.id, data);
        }
    }

    private static BlockState resolve(String namespace, String path) {
        try {
            Block b = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.fromNamespaceAndPath((String)namespace, (String)path)).orElse(null);
            if (b == null || b == Blocks.AIR) {
                return null;
            }
            return b.defaultBlockState();
        }
        catch (Throwable t) {
            return null;
        }
    }

    private static MultiblockShapeData buildDynamicTank() {
        BlockState casing = MekanismShapeTemplates.resolve("mekanism", "dynamic_tank");
        if (casing == null) {
            return null;
        }
        BlockState glass = MekanismShapeTemplates.resolve("mekanism", "structural_glass");
        BlockState valve = MekanismShapeTemplates.resolve("mekanism", "dynamic_valve");
        Assembler a = new Assembler(3, 3, 3);
        a.casing = casing;
        a.wall = glass != null ? glass : casing;
        a.buildFrame();
        a.buildWalls();
        if (valve != null) {
            a.put(1, 1, 0, valve);
        }
        return MekanismShapeTemplates.wrap("mekanism:dynamic_tank", "block.mekanism.dynamic_tank", a);
    }

    private static MultiblockShapeData buildInductionMatrix() {
        BlockState casing = MekanismShapeTemplates.resolve("mekanism", "induction_casing");
        if (casing == null) {
            return null;
        }
        BlockState glass = MekanismShapeTemplates.resolve("mekanism", "structural_glass");
        BlockState cell = MekanismShapeTemplates.resolve("mekanism", "basic_induction_cell");
        BlockState provider = MekanismShapeTemplates.resolve("mekanism", "basic_induction_provider");
        BlockState port = MekanismShapeTemplates.resolve("mekanism", "induction_port");
        Assembler a = new Assembler(3, 4, 3);
        a.casing = casing;
        a.wall = glass != null ? glass : casing;
        a.buildFrame();
        a.buildWalls();
        if (cell != null) {
            a.put(1, 1, 1, cell);
        }
        if (provider != null) {
            a.put(1, 2, 1, provider);
        }
        if (port != null) {
            a.put(1, 1, 0, port);
        }
        return MekanismShapeTemplates.wrap("mekanism:induction_matrix", "block.mekanism.induction_casing", a);
    }

    private static MultiblockShapeData buildThermoelectricBoiler() {
        BlockState casing = MekanismShapeTemplates.resolve("mekanism", "boiler_casing");
        if (casing == null) {
            return null;
        }
        BlockState glass = MekanismShapeTemplates.resolve("mekanism", "structural_glass");
        BlockState element = MekanismShapeTemplates.resolve("mekanism", "superheating_element");
        BlockState disperser = MekanismShapeTemplates.resolve("mekanism", "pressure_disperser");
        BlockState valve = MekanismShapeTemplates.resolve("mekanism", "boiler_valve");
        Assembler a = new Assembler(3, 5, 3);
        a.casing = casing;
        a.wall = glass != null ? glass : casing;
        a.buildFrame();
        a.buildWalls();
        if (element != null) {
            a.buildInteriorLayer(1, element);
        }
        if (disperser != null) {
            a.buildInteriorLayer(3, disperser);
        }
        if (valve != null) {
            a.put(1, 2, 0, valve);
        }
        return MekanismShapeTemplates.wrap("mekanism:thermoelectric_boiler", "block.mekanism.boiler_casing", a);
    }

    private static MultiblockShapeData buildThermalEvaporationTower() {
        BlockState casing = MekanismShapeTemplates.resolve("mekanism", "thermal_evaporation_block");
        if (casing == null) {
            return null;
        }
        BlockState glass = MekanismShapeTemplates.resolve("mekanism", "structural_glass");
        BlockState controller = MekanismShapeTemplates.resolve("mekanism", "thermal_evaporation_controller");
        BlockState valve = MekanismShapeTemplates.resolve("mekanism", "thermal_evaporation_valve");
        Assembler a = new Assembler(4, 3, 4);
        a.casing = casing;
        a.wall = glass != null ? glass : casing;
        a.buildFrame();
        a.buildWalls();
        if (controller != null) {
            a.put(1, 1, 0, controller);
        }
        if (valve != null) {
            a.put(2, 1, 0, valve);
            a.put(1, 1, 3, valve);
        }
        return MekanismShapeTemplates.wrap("mekanism:thermal_evaporation_tower", "block.mekanism.thermal_evaporation_controller", a);
    }

    private static MultiblockShapeData buildSps() {
        BlockState casing = MekanismShapeTemplates.resolve("mekanism", "sps_casing");
        if (casing == null) {
            return null;
        }
        BlockState glass = MekanismShapeTemplates.resolve("mekanism", "structural_glass");
        BlockState port = MekanismShapeTemplates.resolve("mekanism", "sps_port");
        Assembler a = new Assembler(7, 7, 7);
        a.casing = casing;
        a.wall = glass != null ? glass : casing;
        a.buildPartialFrame(1);
        a.buildWalls();
        for (int x = -2; x < 2; ++x) {
            for (int y = -2; y < 2; ++y) {
                for (int z = -2; z < 2; ++z) {
                    if (x == -1 == (y == -1) == (z == -1) == (x == 0) == (y == 0) == (z == 0) || !(x != -1 && x != 0 || y != -1 && y != 0) && (z == -1 || z == 0)) continue;
                    a.put(x < 0 ? a.sizeX + x : x, y < 0 ? a.sizeY + y : y, z < 0 ? a.sizeZ + z : z, casing);
                }
            }
        }
        if (port != null) {
            a.put(3, 3, 0, port);
            a.put(3, 3, 6, port);
        }
        return MekanismShapeTemplates.wrap("mekanism:sps", "block.mekanism.sps_casing", a);
    }

    private static MultiblockShapeData buildFissionReactor() {
        BlockState casing = MekanismShapeTemplates.resolve("mekanismgenerators", "fission_reactor_casing");
        if (casing == null) {
            return null;
        }
        BlockState glass = MekanismShapeTemplates.resolve("mekanismgenerators", "reactor_glass");
        BlockState fuel = MekanismShapeTemplates.resolve("mekanismgenerators", "fission_fuel_assembly");
        BlockState rod = MekanismShapeTemplates.resolve("mekanismgenerators", "control_rod_assembly");
        BlockState port = MekanismShapeTemplates.resolve("mekanismgenerators", "fission_reactor_port");
        int sizeY = 4;
        Assembler a = new Assembler(3, sizeY, 3);
        a.casing = casing;
        a.wall = glass != null ? glass : casing;
        a.buildFrame();
        a.buildWalls();
        if (fuel != null) {
            a.buildColumn(1, 1, 1, sizeY - 3, fuel);
        }
        if (rod != null) {
            a.put(1, sizeY - 2, 1, rod);
        }
        if (port != null) {
            a.put(1, 1, 0, port);
        }
        return MekanismShapeTemplates.wrap("mekanismgenerators:fission_reactor", "block.mekanismgenerators.fission_reactor_casing", a);
    }

    private static MultiblockShapeData buildFusionReactor() {
        BlockState frame = MekanismShapeTemplates.resolve("mekanismgenerators", "fusion_reactor_frame");
        if (frame == null) {
            return null;
        }
        BlockState controller = MekanismShapeTemplates.resolve("mekanismgenerators", "fusion_reactor_controller");
        BlockState port = MekanismShapeTemplates.resolve("mekanismgenerators", "fusion_reactor_port");
        BlockState laser = MekanismShapeTemplates.resolve("mekanismgenerators", "laser_focus_matrix");
        Assembler a = new Assembler(5, 5, 5);
        a.casing = frame;
        a.wall = frame;
        a.buildPartialFrame(1);
        a.buildWalls();
        if (controller != null) {
            a.put(2, 4, 2, controller);
        }
        if (laser != null) {
            a.put(2, 0, 2, laser);
        }
        if (port != null) {
            a.put(0, 2, 2, port);
        }
        return MekanismShapeTemplates.wrap("mekanismgenerators:fusion_reactor", "block.mekanismgenerators.fusion_reactor_controller", a);
    }

    private static MultiblockShapeData buildIndustrialTurbine() {
        BlockState casing = MekanismShapeTemplates.resolve("mekanismgenerators", "turbine_casing");
        if (casing == null) {
            return null;
        }
        BlockState glass = MekanismShapeTemplates.resolve("mekanism", "structural_glass");
        BlockState vent = MekanismShapeTemplates.resolve("mekanismgenerators", "turbine_vent");
        BlockState rotor = MekanismShapeTemplates.resolve("mekanismgenerators", "turbine_rotor");
        BlockState disperser = MekanismShapeTemplates.resolve("mekanism", "pressure_disperser");
        BlockState complex = MekanismShapeTemplates.resolve("mekanismgenerators", "rotational_complex");
        BlockState condenser = MekanismShapeTemplates.resolve("mekanismgenerators", "saturating_condenser");
        BlockState coil = MekanismShapeTemplates.resolve("mekanismgenerators", "electromagnetic_coil");
        BlockState valve = MekanismShapeTemplates.resolve("mekanismgenerators", "turbine_valve");
        int sizeY = 7;
        Assembler a = new Assembler(5, sizeY, 5);
        a.casing = casing;
        a.wall = glass != null ? glass : casing;
        a.ventState = vent;
        a.ventFromY = sizeY - 3;
        a.roof = vent;
        a.buildFrame();
        a.buildWalls();
        int c = 2;
        if (rotor != null) {
            a.buildColumn(c, 1, c, sizeY - 4, rotor);
        }
        if (disperser != null) {
            a.buildInteriorLayer(sizeY - 3, disperser);
        }
        if (complex != null) {
            a.put(c, sizeY - 3, c, complex);
        }
        if (condenser != null) {
            a.buildInteriorLayer(sizeY - 2, condenser);
        }
        if (coil != null) {
            a.buildPlane(1, 1, 4, 4, sizeY - 2, coil);
        }
        if (valve != null) {
            a.put(c, 1, 0, valve);
        }
        return MekanismShapeTemplates.wrap("mekanismgenerators:industrial_turbine", "block.mekanismgenerators.turbine_casing", a);
    }

    private static MultiblockShapeData wrap(String id, String langKey, Assembler a) {
        if (a.blocks.isEmpty()) {
            return null;
        }
        return new MultiblockShapeData(id, langKey, a.blocks, new BlockPos(0, 0, 0), new BlockPos(a.sizeX - 1, a.sizeY - 1, a.sizeZ - 1), "mekanism");
    }

    private static final class Assembler {
        final int sizeX;
        final int sizeY;
        final int sizeZ;
        final Map<BlockPos, BlockState> blocks = new LinkedHashMap<BlockPos, BlockState>();
        BlockState casing;
        BlockState wall;
        BlockState floor;
        BlockState roof;
        BlockState ventState;
        int ventFromY = -1;

        Assembler(int sizeX, int sizeY, int sizeZ) {
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
        }

        void put(int x, int y, int z, BlockState state) {
            if (state == null || state.isAir()) {
                return;
            }
            if (x < 0 || y < 0 || z < 0 || x >= this.sizeX || y >= this.sizeY || z >= this.sizeZ) {
                return;
            }
            this.blocks.put(new BlockPos(x, y, z), state);
        }

        BlockState wallAt(int y) {
            if (this.ventFromY >= 0 && this.ventState != null && y >= this.ventFromY) {
                return this.ventState;
            }
            return this.wall != null ? this.wall : this.casing;
        }

        BlockState floorAt() {
            return this.floor != null ? this.floor : this.casing;
        }

        BlockState roofAt(int y) {
            return this.roof != null ? this.roof : this.wallAt(y);
        }

        void buildFrame() {
            this.buildPartialFrame(-1);
        }

        void buildPartialFrame(int cutoff) {
            for (int x = 0; x < this.sizeX; ++x) {
                if (x <= cutoff || x >= this.sizeX - 1 - cutoff) continue;
                this.put(x, 0, 0, this.casing);
                this.put(x, this.sizeY - 1, 0, this.casing);
                this.put(x, 0, this.sizeZ - 1, this.casing);
                this.put(x, this.sizeY - 1, this.sizeZ - 1, this.casing);
            }
            for (int y = 0; y < this.sizeY; ++y) {
                if (y <= cutoff || y >= this.sizeY - 1 - cutoff) continue;
                this.put(0, y, 0, this.casing);
                this.put(this.sizeX - 1, y, 0, this.casing);
                this.put(0, y, this.sizeZ - 1, this.casing);
                this.put(this.sizeX - 1, y, this.sizeZ - 1, this.casing);
            }
            for (int z = 0; z < this.sizeZ; ++z) {
                if (z <= cutoff || z >= this.sizeZ - 1 - cutoff) continue;
                this.put(0, 0, z, this.casing);
                this.put(this.sizeX - 1, 0, z, this.casing);
                this.put(0, this.sizeY - 1, z, this.casing);
                this.put(this.sizeX - 1, this.sizeY - 1, z, this.casing);
            }
        }

        void buildWalls() {
            int z;
            for (int x = 1; x < this.sizeX - 1; ++x) {
                for (z = 1; z < this.sizeZ - 1; ++z) {
                    this.put(x, 0, z, this.floorAt());
                    this.put(x, this.sizeY - 1, z, this.roofAt(this.sizeY - 1));
                }
            }
            for (int y = 1; y < this.sizeY - 1; ++y) {
                for (int x = 1; x < this.sizeZ - 1; ++x) {
                    this.put(x, y, 0, this.wallAt(y));
                    this.put(x, y, this.sizeZ - 1, this.wallAt(y));
                }
                for (z = 1; z < this.sizeZ - 1; ++z) {
                    this.put(0, y, z, this.wallAt(y));
                    this.put(this.sizeZ - 1, y, z, this.wallAt(y));
                }
            }
        }

        void buildInteriorLayer(int yLevel, BlockState state) {
            for (int x = 1; x < this.sizeX - 1; ++x) {
                for (int z = 1; z < this.sizeZ - 1; ++z) {
                    this.put(x, yLevel, z, state);
                }
            }
        }

        void buildInteriorLayers(int yMin, int yMax, BlockState state) {
            for (int y = yMin; y <= yMax; ++y) {
                this.buildInteriorLayer(y, state);
            }
        }

        void buildPlane(int x1, int z1, int x2, int z2, int yLevel, BlockState state) {
            for (int x = x1; x < x2 - 1; ++x) {
                for (int z = z1; z < z2 - 1; ++z) {
                    this.put(x, yLevel, z, state);
                }
            }
        }

        void buildColumn(int cx, int cy, int cz, int height, BlockState state) {
            for (int dy = 0; dy < height; ++dy) {
                this.put(cx, cy + dy, cz, state);
            }
        }
    }
}

