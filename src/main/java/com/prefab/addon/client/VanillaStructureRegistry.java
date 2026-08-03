package com.prefab.addon.client;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 原版 MC-Prefab 建筑注册表.
 * <p>原版 mod 资源在 <code>assets/prefab/structures/&lt;name&gt;.gz</code> (1.21.1+ 改为 gz 格式),
 * 缩略图在 <code>assets/prefab/textures/gui/&lt;name&gt;.png</code>.</p>
 *
 * <p>本注册表只做"展示用"索引, 不复制原版 NBT 数据. 资源从 ResourceManager 实时读.</p>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>命名不一定跟原版 mod 完全一致, 因为 1.21.1 资源列表跟旧版 (.zip 格式) 不一定对应.</li>
 *   <li>这里以 Shared/resources/assets/prefab/structures 下实际存在的 .gz 文件为准.</li>
 *   <li>每个 Entry 的 variants 列表就是该建筑的所有"风格", 对应 GuiBasicStructure 的 chosenOption.</li>
 * </ul>
 *
 * <h3>为什么不用 EnumBasicStructureName?</h3>
 * 因为 prefab mod 没有作为 classpath 依赖, EnumBasicStructureName 是 prefab mod 的类, 我们访问不到.
 * 维护一份精简的注册表, 既稳定又不依赖 prefab mod 是否安装.
 */
public final class VanillaStructureRegistry {

    private VanillaStructureRegistry() {}

    /** 一个变种 = 一个原版 .gz 资源 + 一个 GUI 缩略图 (2D, 原版直接提供). */
    public static final class Variant {
        public final String displayName;          // 中文显示名
        public final ResourceLocation nbt;       // prefab:structures/<name>.gz
        public final ResourceLocation thumb;     // prefab:textures/gui/<name>.png (可能缺失, UI 用占位符)

        public Variant(String displayName, String nbtPath, String thumbPath) {
            this.displayName = displayName;
            // 1.21.1 ResourceLocation 必须用 (namespace, path) 双参数, 不能用单 String
            this.nbt = parse(nbtPath);
            this.thumb = (thumbPath == null || thumbPath.isEmpty()) ? null : parse(thumbPath);
        }

        /** 解析 "prefab:structures/x.gz" 为 ResourceLocation. 失败返回 null. */
        private static ResourceLocation parse(String s) {
            try {
                // 1.21.1 ResourceLocation(String, String) 是 private, 必须用 fromNamespaceAndPath 或 parse(String)
                return ResourceLocation.parse(s);
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** 一个建筑 = 名字 + 变种列表. */
    public static final class Entry {
        public final String displayName;          // 建筑名
        public final List<Variant> variants;      // 变种 (风格)

        public Entry(String displayName, List<Variant> variants) {
            this.displayName = displayName;
            this.variants = variants;
        }
    }

    // ========================================================================
    // 注册表. 命名以 Shared/resources/assets/prefab/structures 实际存在的 .gz 为准.
    // 变种分组参考 ModernBuildingsOptions / VillagerHouseOptions / FarmOptions 等.
    // ========================================================================

    public static final List<Entry> ALL = List.of(
        // === 基础房屋 (House) ===
        new Entry("基础房屋", List.of(
            new Variant("基础", "prefab:structures/house_basic.gz", "prefab:textures/gui/house_basic.png"),
            new Variant("小屋", "prefab:structures/house_cabin.gz", "prefab:textures/gui/house_cabin.png"),
            new Variant("树屋", "prefab:structures/house_tree.gz", "prefab:textures/gui/house_tree.png"),
            new Variant("沙漠屋", "prefab:structures/house_desert.gz", "prefab:textures/gui/house_desert.png"),
            new Variant("沙漠屋2", "prefab:structures/house_desert_2.gz", "prefab:textures/gui/house_desert_2.png"),
            new Variant("雪屋", "prefab:structures/house_snow.gz", "prefab:textures/gui/house_snowy.png"),
            new Variant("蘑菇屋", "prefab:structures/house_mushroom.gz", "prefab:textures/gui/house_mushroom.png"),
            new Variant("农庄", "prefab:structures/house_ranch.gz", "prefab:textures/gui/house_ranch.png"),
            new Variant("霍比特", "prefab:structures/house_hobbit.gz", "prefab:textures/gui/house_hobbit.png"),
            new Variant("现代", "prefab:structures/house_modern.gz", "prefab:textures/gui/house_modern.png"),
            new Variant("阁楼", "prefab:structures/house_loft.gz", "prefab:textures/gui/house_loft.png"),
            new Variant("塔楼", "prefab:structures/house_tower.gz", "prefab:textures/gui/house_tower.png"),
            new Variant("水底", "prefab:structures/house_sub_aqua.gz", "prefab:textures/gui/house_subaquatic.png"),
            new Variant("露营", "prefab:structures/house_campsite.gz", "prefab:textures/gui/house_campsite.png"),
            new Variant("俄式", "prefab:structures/house_izba.gz", "prefab:textures/gui/house_izba.png")
        )),

        // === 高级房屋 (HouseAdvanced) ===
        new Entry("高级房屋", List.of(
            new Variant("庄园", "prefab:structures/house_advanced_estate.gz", "prefab:textures/gui/house_advanced_estate.png"),
            new Variant("府邸", "prefab:structures/house_advanced_manor.gz", "prefab:textures/gui/house_advanced_manor.png"),
            new Variant("工坊", "prefab:structures/house_advanced_workshop.gz", "prefab:textures/gui/house_advanced_workshop.png")
        )),

        // === 改良房屋 (HouseImproved) ===
        new Entry("改良房屋", List.of(
            new Variant("金合欢", "prefab:structures/house_improved_acacia.gz", "prefab:textures/gui/house_improved_acacia.png"),
            new Variant("金合欢2", "prefab:structures/house_improved_acacia_2.gz", "prefab:textures/gui/house_improved_acacia_2.png"),
            new Variant("村舍", "prefab:structures/house_improved_cottage.gz", "prefab:textures/gui/house_improved_cottage.png"),
            new Variant("绯红", "prefab:structures/house_improved_crimson.gz", "prefab:textures/gui/house_improved_crimson.png"),
            new Variant("土质", "prefab:structures/house_improved_earthen.gz", "prefab:textures/gui/house_improved_earthen.png"),
            new Variant("霍比特", "prefab:structures/house_improved_hobbit.gz", "prefab:textures/gui/house_improved_hobbit.png"),
            new Variant("丛林", "prefab:structures/house_improved_jungle.gz", "prefab:textures/gui/house_improved_jungle.png"),
            new Variant("现代", "prefab:structures/house_improved_modern.gz", "prefab:textures/gui/house_improved_modern.png"),
            new Variant("山地", "prefab:structures/house_improved_mountain.gz", "prefab:textures/gui/house_improved_mountain.png"),
            new Variant("下界", "prefab:structures/house_improved_nether.gz", "prefab:textures/gui/house_improved_nether.png"),
            new Variant("云杉", "prefab:structures/house_improved_spruce.gz", "prefab:textures/gui/house_improved_spruce.png"),
            new Variant("塔楼", "prefab:structures/house_improved_tower.gz", "prefab:textures/gui/house_improved_tower.png")
        )),

        // === 水下基地 (AquaBase) ===
        new Entry("水下基地", List.of(
            new Variant("基础", "prefab:structures/aqua_base.gz", "prefab:textures/gui/aqua_base.png"),
            new Variant("改良", "prefab:structures/aqua_base_improved.gz", "prefab:textures/gui/aqua_base_improved.png")
        )),

        // === 现代建筑 (ModernBuildings) ===
        new Entry("现代建筑", List.of(
            new Variant("公寓", "prefab:structures/modern_apartment.gz", "prefab:textures/gui/modern_apartment.png"),
            new Variant("银行", "prefab:structures/modern_bank.gz", "prefab:textures/gui/modern_bank.png"),
            new Variant("影院", "prefab:structures/modern_cinema.gz", "prefab:textures/gui/modern_cinema.png"),
            new Variant("工地", "prefab:structures/modern_construction_site.gz", "prefab:textures/gui/modern_construction_site.png"),
            new Variant("村舍", "prefab:structures/modern_cottage.gz", "prefab:textures/gui/modern_cottage.png"),
            new Variant("加油站", "prefab:structures/modern_gas_station.gz", "prefab:textures/gui/modern_gas_station.png"),
            new Variant("嘻哈水果摊", "prefab:structures/modern_hipster_fruit_stand.gz", "prefab:textures/gui/modern_hipster_fruit_stand.png"),
            new Variant("现代屋", "prefab:structures/modern_house.gz", "prefab:textures/gui/modern_house.png"),
            new Variant("果汁店", "prefab:structures/modern_juice_shop.gz", "prefab:textures/gui/modern_juice_shop.png"),
            new Variant("图书馆", "prefab:structures/modern_library.gz", "prefab:textures/gui/modern_library.png"),
            new Variant("商场", "prefab:structures/modern_mall_store.gz", "prefab:textures/gui/modern_mall_store.png"),
            new Variant("小酒店", "prefab:structures/modern_mini_hotel.gz", "prefab:textures/gui/modern_mini_hotel.png"),
            new Variant("火车站", "prefab:structures/modern_railway_station.gz", "prefab:textures/gui/modern_railway_station.png"),
            new Variant("餐厅", "prefab:structures/modern_restaurant.gz", "prefab:textures/gui/modern_restaurant.png"),
            new Variant("树屋", "prefab:structures/modern_tree_house.gz", "prefab:textures/gui/modern_tree_house.png"),
            new Variant("围墙别墅", "prefab:structures/modern_walled_villa.gz", "prefab:textures/gui/modern_walled_villa.png"),
            new Variant("水上乐园", "prefab:structures/modern_water_park.gz", "prefab:textures/gui/modern_water_park.png")
        )),

        // === 农庄系列 ===
        new Entry("自动化农场", List.of(
            new Variant("通用", "prefab:structures/automated_farm.gz", "prefab:textures/gui/automated_farm.png"),
            new Variant("竹子", "prefab:structures/automated_bamboo_farm.gz", "prefab:textures/gui/automated_bamboo_farm.png"),
            new Variant("西瓜", "prefab:structures/automated_melon_farm.gz", "prefab:textures/gui/automated_melon_farm.png"),
            new Variant("仙人掌", "prefab:structures/cactus_farm.gz", "prefab:textures/gui/cactus_farm.png"),
            new Variant("蘑菇", "prefab:structures/mushroom_farm.gz", "prefab:textures/gui/mushroom_farm.png"),
            new Variant("甘蔗", "prefab:structures/sugar_cane_farm.gz", "prefab:textures/gui/sugar_cane_farm.png"),
            new Variant("浆果", "prefab:structures/berry_farm.gz", "prefab:textures/gui/berry_farm.png"),
            new Variant("树", "prefab:structures/tree_farm.gz", "prefab:textures/gui/tree_farm.png"),
            new Variant("鱼塘", "prefab:structures/fish_pond.gz", "prefab:textures/gui/fish_pond.png"),
            new Variant("蜂箱", "prefab:structures/bee_farm.gz", "prefab:textures/gui/bee_farm.png"),
            new Variant("牛肉", "prefab:structures/beef_farm.gz", "prefab:textures/gui/beef_farm.png"),
            new Variant("高架", "prefab:structures/elevated_farm.gz", "prefab:textures/gui/elevated_farm.png"),
            new Variant("多层", "prefab:structures/multi_level_farm.gz", "prefab:textures/gui/multi_level_farm.png"),
            new Variant("产粮", "prefab:structures/produce_farm.gz", "prefab:textures/gui/produce_farm.png")
        )),

        new Entry("谷仓", List.of(
            new Variant("改良", "prefab:structures/barn_improved.gz", "prefab:textures/gui/barn_improved.png"),
            new Variant("高级", "prefab:structures/barn_advanced.gz", "prefab:textures/gui/barn_advanced.png")
        )),

        new Entry("温室", List.of(
            new Variant("改良", "prefab:structures/green_house_improved.gz", "prefab:textures/gui/green_house_improved.png"),
            new Variant("高级", "prefab:structures/green_house_advanced.gz", "prefab:textures/gui/green_house_advanced.png")
        )),

        new Entry("鸡舍", List.of(
            new Variant("基础", "prefab:structures/chicken_coop.gz", "prefab:textures/gui/chicken_coop.png"),
            new Variant("改良", "prefab:structures/chicken_coop_improved.gz", "prefab:textures/gui/chicken_coop_improved.png")
        )),

        new Entry("马厩", List.of(
            new Variant("基础", "prefab:structures/horse_stable.gz", "prefab:textures/gui/horse_stable.png"),
            new Variant("高级", "prefab:structures/horse_stable_advanced.gz", "prefab:textures/gui/horse_stable_advanced.png")
        )),

        new Entry("兔舍", List.of(
            new Variant("基础", "prefab:structures/rabbit_hutch.gz", "prefab:textures/gui/rabbit_hutch.png")
        )),

        // === 防御 / 特殊建筑 ===
        new Entry("防御工事", List.of(
            new Variant("防御地堡", "prefab:structures/defense_bunker.gz", "prefab:textures/gui/defense_bunker.png"),
            new Variant("瞭望塔", "prefab:structures/watch_tower.gz", "prefab:textures/gui/watch_tower.png"),
            new Variant("瞭望塔2", "prefab:structures/watch_tower_2.gz", "prefab:textures/gui/watch_tower_2.png"),
            new Variant("瞭望塔暗", "prefab:structures/watch_tower_dark.gz", "prefab:textures/gui/watch_tower_dark.png"),
            new Variant("监狱", "prefab:structures/jail.gz", "prefab:textures/gui/jail.png")
        )),

        new Entry("传送门", List.of(
            new Variant("末地", "prefab:structures/ender_gateway.gz", "prefab:textures/gui/ender_gateway.png"),
            new Variant("下界", "prefab:structures/nether_gate.gz", "prefab:textures/gui/nether_gate.png"),
            new Variant("下界树", "prefab:structures/nether_gate_tree.gz", "prefab:textures/gui/nether_gate_tree.png")
        )),

        // === 村庄 / 城镇建筑 ===
        new Entry("村庄建筑", List.of(
            new Variant("有角", "prefab:structures/village_house_angled.gz", "prefab:textures/gui/village_house_angled.png"),
            new Variant("铁匠", "prefab:structures/village_house_blacksmith.gz", "prefab:textures/gui/village_house_blacksmith.png"),
            new Variant("带栅栏", "prefab:structures/village_house_fenced.gz", "prefab:textures/gui/village_house_fenced.png"),
            new Variant("平顶", "prefab:structures/village_house_flat.gz", "prefab:textures/gui/village_house_flat.png"),
            new Variant("长形", "prefab:structures/village_house_long.gz", "prefab:textures/gui/village_house_long.png"),
            new Variant("草原", "prefab:structures/grassy_plain.gz", "prefab:textures/gui/grassy_plain.png")
        )),

        // === 大型建筑 ===
        new Entry("酒馆", List.of(
            new Variant("西部", "prefab:structures/saloon.gz", "prefab:textures/gui/saloon.png")
        )),

        new Entry("滑雪小屋", List.of(
            new Variant("山间", "prefab:structures/ski_lodge.gz", "prefab:textures/gui/ski_lodge.png")
        )),

        new Entry("城镇大厅", List.of(
            new Variant("基础", "prefab:structures/town_hall.gz", "prefab:textures/gui/town_hall.png")
        )),

        new Entry("迎宾中心", List.of(
            new Variant("基础", "prefab:structures/welcome_center.gz", "prefab:textures/gui/welcome_center.png")
        )),

        new Entry("风车磨坊", List.of(
            new Variant("传统", "prefab:structures/wind_mill.gz", "prefab:textures/gui/wind_mill.png")
        )),

        new Entry("魔法神庙", List.of(
            new Variant("基础", "prefab:structures/magic_temple.gz", "prefab:textures/gui/magic_temple.png")
        )),

        new Entry("机械塔", List.of(
            new Variant("基础", "prefab:structures/machinery_tower.gz", "prefab:textures/gui/machinery_tower.png")
        )),

        new Entry("矿井入口", List.of(
            new Variant("基础", "prefab:structures/mineshaft_entrance.gz", "prefab:textures/gui/mineshaft_entrance.png")
        )),

        new Entry("仓库", List.of(
            new Variant("基础", "prefab:structures/warehouse.gz", "prefab:textures/gui/warehouse.png"),
            new Variant("改良", "prefab:structures/warehouse_improved.gz", "prefab:textures/gui/warehouse_improved.png")
        )),

        // === 怪物 / 战斗 ===
        new Entry("怪物碾碎机", List.of(
            new Variant("基础", "prefab:structures/monster_masher.gz", "prefab:textures/gui/monster_masher.png")
        )),

        // === 桥梁 ===
        new Entry("即时桥", List.of(
            new Variant("基础", "prefab:structures/instant_bridge.gz", "prefab:textures/gui/instant_bridge.png")
        ))
    );

    /**
     * 检查 prefab mod 是否真的安装了 (尝试读取任意一个资源).
     * <p>如果没装, 整个 tab 走空状态提示, 不崩溃.</p>
     */
    public static boolean isPrefabModAvailable() {
        try {
            return net.minecraft.client.Minecraft.getInstance()
                .getResourceManager()
                .getResource(ResourceLocation.fromNamespaceAndPath("prefab", "structures/house_basic.gz"))
                .isPresent();
        } catch (Exception e) {
            return false;
        }
    }
}
