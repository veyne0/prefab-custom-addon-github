package com.prefab.addon.work;

import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 计算建造自定义建筑需要的材料清单 (按 block id 聚合)。
 *
 * 与 NbtStructureParser 不同: 这个工具按"每个方块 ID" (modid:block, 忽略 properties) 统计,
 * 比如 minecraft:stone 出现 64 次就只算 1 行 "minecraft:stone × 64", 而不是按 palette index 数。
 */
public class MaterialCalculator {

    public static class MaterialList {
        /** 排序去重的 block id -> 需要的数量 */
        public final Map<String, Integer> required = new TreeMap<>();
        /** 总方块数 (含空气, 即 blocks 列表 size) */
        public int totalBlocks = 0;
        /** 实际占空间的方块数 (排除空气) */
        public int nonAirBlocks = 0;
        /** 唯一方块类型数 */
        public int uniqueBlockTypes = 0;
        /** 被过滤掉的基础设施方块数 (水/熔岩/Create 施工方块等) */
        public int filteredInfrastructure = 0;
        /** 被过滤的方块类型 (用于 UI 提示) */
        public final Map<String, Integer> filteredTypes = new TreeMap<>();

        @Override
        public String toString() {
            return String.format("MaterialList{total=%d nonAir=%d unique=%d}",
                totalBlocks, nonAirBlocks, uniqueBlockTypes);
        }
    }

    /**
     * 从 NBT byte[] 计算材料清单。
     */
    public static MaterialList calculate(byte[] nbtData) {
        MaterialList list = new MaterialList();
        if (nbtData == null || nbtData.length == 0) {
            PrefabCustomAddon.LOGGER.warn("[MATERIALS] NBT 数据为空");
            return list;
        }
        try {
            CompoundTag root;
            try (var bais = new ByteArrayInputStream(nbtData)) {
                root = NbtIo.readCompressed(bais, NbtAccounter.create(64L * 1024 * 1024));
            } catch (IOException e) {
                try (var bais = new ByteArrayInputStream(nbtData)) {
                    root = NbtIo.read(new java.io.DataInputStream(bais));
                }
            }
            if (root == null) {
                PrefabCustomAddon.LOGGER.warn("[MATERIALS] 解析失败: NBT 不可读");
                return list;
            }

            // 1) 读取 palette (id -> block name)
            String[] paletteNames = new String[0];
            if (root.contains("palette", Tag.TAG_LIST)) {
                ListTag palette = root.getList("palette", Tag.TAG_COMPOUND);
                paletteNames = new String[palette.size()];
                for (int i = 0; i < palette.size(); i++) {
                    CompoundTag entry = palette.getCompound(i);
                    String name = null;
                    for (String k : new String[]{"Name", "name", "Block", "block", "id"}) {
                        if (entry.contains(k, Tag.TAG_STRING)) {
                            name = entry.getString(k);
                            break;
                        }
                    }
                    paletteNames[i] = name;
                }
            }

            // 2) 遍历 blocks, 按 state 索引查 palette, 按 block name 聚合
            if (root.contains("blocks", Tag.TAG_LIST)) {
                ListTag blocks = root.getList("blocks", Tag.TAG_COMPOUND);
                list.totalBlocks = blocks.size();
                for (int i = 0; i < blocks.size(); i++) {
                    CompoundTag b = blocks.getCompound(i);
                    String blockName = null;

                    // 标准 MC structure 格式: blocks[].state -> palette index
                    if (b.contains("state", Tag.TAG_INT) && paletteNames.length > 0) {
                        int idx = b.getInt("state");
                        if (idx >= 0 && idx < paletteNames.length) {
                            blockName = paletteNames[idx];
                        }
                    } else {
                        // 直接在 block 上有 name
                        for (String k : new String[]{"Name", "name", "Block", "block", "id"}) {
                            if (b.contains(k, Tag.TAG_STRING)) {
                                blockName = b.getString(k);
                                break;
                            }
                        }
                    }

                    if (blockName == null) continue;
                    // 跳过空气
                    if (blockName.equals("minecraft:air") || blockName.equals("air")
                        || blockName.endsWith(":air")) {
                        continue;
                    }
                    // 跳过"基础设施"方块 (水/熔岩/方块结构占位/Schematicannon 施工方块等)
                    // 这些方块玩家不能放进背包, 也不应该算在挑战模式材料里, 默认已满足
                    if (isInfrastructureBlock(blockName)) {
                        list.filteredInfrastructure++;
                        list.filteredTypes.merge(blockName, 1, Integer::sum);
                        continue;
                    }
                    list.required.merge(blockName, 1, Integer::sum);
                    list.nonAirBlocks++;
                }
            }
            list.uniqueBlockTypes = list.required.size();
            PrefabCustomAddon.LOGGER.info("[MATERIALS] {} total={} nonAir={} unique={} (filtered {} infrastructure)",
                list, list.totalBlocks, list.nonAirBlocks, list.uniqueBlockTypes, list.filteredInfrastructure);
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("[MATERIALS] 解析异常", e);
        }
        return list;
    }

    /**
     * 计算玩家背包里某种方块有多少个 (按 block id 模糊匹配 item).
     * 注意: block id 不一定等于 item id (例如 slab, fence).
     * 我们用 block 的 Item 形式 (Block.asItem()) 来匹配.
     */
    public static int countInInventory(Inventory inv, String blockId) {
        if (inv == null || blockId == null || blockId.isEmpty()) return 0;
        ResourceLocation rl = ResourceLocation.tryParse(blockId);
        if (rl == null) return 0;
        Item target = BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
        // block id 可能指向方块, 我们用 Block 的 item 形式
        if (target == null) {
            net.minecraft.world.level.block.Block b = BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
            if (b != null) target = b.asItem();
        }
        if (target == null) return 0;
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() == target) total += stack.getCount();
        }
        return total;
    }

    /**
     * 返回某 blockId 的 Item 形式 (用于 GUI 渲染图标).
     * 找不到时**永远**返回一个占位 ItemStack (GRAY_STAINED_GLASS_PANE), 配合 MaterialSubmissionGui 显示完整 ID.
     *
     * 常见 "无 Item form" 方块 (水/熔岩/火/草方块等) 走 fallback 映射:
     * - water       -> water_bucket
     * - lava        -> lava_bucket
     * - tall_grass  -> short_grass
     * - fire        -> 物品不存在, 用 BARRIER 占位
     */
    public static ItemStack getItemStack(String blockId) {
        if (blockId == null || blockId.isEmpty()) {
            return new ItemStack(net.minecraft.world.item.Items.GRAY_STAINED_GLASS_PANE);
        }
        ResourceLocation rl = ResourceLocation.tryParse(blockId);
        if (rl == null) {
            PrefabCustomAddon.LOGGER.warn("[MATERIAL-ICON] 无法解析 blockId: {}", blockId);
            return new ItemStack(net.minecraft.world.item.Items.GRAY_STAINED_GLASS_PANE);
        }
        // 1) 优先 Item 注册
        Item item = BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
        if (item != null) return new ItemStack(item);
        // 2) 走 Block 注册
        net.minecraft.world.level.block.Block b = BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
        if (b != null) {
            Item blockItem = b.asItem();
            if (blockItem != null && blockItem != net.minecraft.world.item.Items.AIR) {
                return new ItemStack(blockItem);
            }
            // 3) Block 有但 asItem() 是 AIR (例如水/熔岩/火) → 走 fallback 映射
            String path = rl.getPath();
            if (path.equals("water") || path.equals("bubble_column") || path.equals("water_cauldron")) {
                return new ItemStack(net.minecraft.world.item.Items.WATER_BUCKET);
            }
            if (path.equals("lava") || path.equals("lava_cauldron")) {
                return new ItemStack(net.minecraft.world.item.Items.LAVA_BUCKET);
            }
            if (path.equals("tall_grass") || path.equals("large_fern")) {
                return new ItemStack(net.minecraft.world.item.Items.SHORT_GRASS);
            }
            if (path.equals("fire") || path.equals("soul_fire") || path.equals("soul_fire")
                || path.equals("powder_snow")) {
                return new ItemStack(net.minecraft.world.item.Items.BARRIER);
            }
            if (path.equals("kelp") || path.equals("kelp_plant") || path.equals("seagrass") || path.equals("tall_seagrass")) {
                return new ItemStack(net.minecraft.world.item.Items.KELP);
            }
            if (path.equals("redstone_wire")) {
                return new ItemStack(net.minecraft.world.item.Items.REDSTONE);
            }
            if (path.equals("tripwire")) {
                return new ItemStack(net.minecraft.world.item.Items.STRING);
            }
            if (path.equals("vine") || path.equals("cave_vines") || path.equals("cave_vines_plant")) {
                return new ItemStack(net.minecraft.world.item.Items.VINE);
            }
            if (path.equals("nether_portal")) {
                return new ItemStack(net.minecraft.world.item.Items.FLINT_AND_STEEL);
            }
            // 4) 方块存在但 mod 没注册 Item form (例如 Create 的 multipart 结构方块)
            // → 尝试**用方块 ID 查 Item** (很多 mod 同时注册 Block + Item, 命名空间/路径可能不同)
            String itemNamespace = rl.getNamespace();
            String itemPath = path;
            // 尝试: 用 _block 后缀, 或无后缀, 或 _item 后缀
            for (String suffix : new String[]{"", "_block", "_item", "block"}) {
                ResourceLocation trialRl = ResourceLocation.fromNamespaceAndPath(
                    itemNamespace, itemPath + suffix);
                Item trialItem = BuiltInRegistries.ITEM.getOptional(trialRl).orElse(null);
                if (trialItem != null && trialItem != net.minecraft.world.item.Items.AIR) {
                    PrefabCustomAddon.LOGGER.info("[MATERIAL-ICON] Block 走 fallback 找到 Item: {} → {}",
                        blockId, trialRl);
                    return new ItemStack(trialItem);
                }
            }
            // 5) 实在找不到, 灰色玻璃占位
            PrefabCustomAddon.LOGGER.debug("[MATERIAL-ICON] Block 找不到对应 Item, 用占位: {} (path={})",
                blockId, path);
            return new ItemStack(net.minecraft.world.item.Items.GRAY_STAINED_GLASS_PANE);
        }
        // 6) Block 也没找到, 用 BARRIER 提示 (这种情况可能是 NBT 损坏/格式不对)
        PrefabCustomAddon.LOGGER.warn("[MATERIAL-ICON] Block 注册表都没有: {}", blockId);
        return new ItemStack(net.minecraft.world.item.Items.BARRIER);
    }

    /**
     * 判断某个 blockId 是否属于"基础设施方块" (挑战模式下默认已满足, 不计入材料需求).
     * 包含: 水/熔岩/火/红石线/藤蔓 等不可拾取方块, 以及 Create 的 Schematicannon 施工方块/结构占位.
     *
     * 这些方块:
     * - 玩家**无法**放进背包 (没 Item form 或不是物品)
     * - 玩家也无法 "提交" 它们 (挑战模式不适用)
     * - 在 NBT 里被存为结构的一部分, 但实际建造时由世界自然生成 (水)/由 mod 注入 (Create)
     */
    public static boolean isInfrastructureBlock(String blockId) {
        if (blockId == null || blockId.isEmpty()) return false;
        ResourceLocation rl = ResourceLocation.tryParse(blockId);
        if (rl == null) return false;
        String ns = rl.getNamespace();
        String path = rl.getPath();

        // === 1) 原版不可拾取方块 (按 path 匹配, 任何命名空间都算) ===
        // 水/水柱/水锅/熔岩/熔岩锅/海带/水草/红石线/绊线/火/灵魂火/末地传送门/藤蔓/洞穴藤蔓
        if (path.equals("water") || path.equals("lava") || path.equals("fire") || path.equals("soul_fire")
            || path.equals("bubble_column") || path.equals("water_cauldron") || path.equals("lava_cauldron")
            || path.equals("kelp") || path.equals("kelp_plant") || path.equals("seagrass")
            || path.equals("tall_seagrass") || path.equals("redstone_wire") || path.equals("tripwire")
            || path.equals("vine") || path.equals("cave_vines") || path.equals("cave_vines_plant")
            || path.equals("nether_portal") || path.equals("end_portal") || path.equals("end_gateway")
            || path.equals("powder_snow") || path.equals("air") || path.equals("cave_air")
            || path.equals("void_air") || path.equals("structure_void")
            || path.equals("tall_grass") || path.equals("large_fern")
            || path.equals("bubble_coral") || path.equals("brain_coral") || path.equals("fire_coral")
            || path.equals("horn_coral") || path.equals("tube_coral")
            || path.equals("dead_bubble_coral") || path.equals("dead_brain_coral")
            || path.equals("dead_fire_coral") || path.equals("dead_horn_coral") || path.equals("dead_tube_coral")
            || path.equals("bubble_coral_fan") || path.equals("brain_coral_fan") || path.equals("fire_coral_fan")
            || path.equals("horn_coral_fan") || path.equals("tube_coral_fan")
            || path.equals("dead_bubble_coral_fan") || path.equals("dead_brain_coral_fan")
            || path.equals("dead_fire_coral_fan") || path.equals("dead_horn_coral_fan")
            || path.equals("dead_tube_coral_fan")
            || path.equals("bubble_coral_wall_fan") || path.equals("brain_coral_wall_fan")
            || path.equals("fire_coral_wall_fan") || path.equals("horn_coral_wall_fan")
            || path.equals("tube_coral_wall_fan")
            || path.equals("dead_bubble_coral_wall_fan") || path.equals("dead_brain_coral_wall_fan")
            || path.equals("dead_fire_coral_wall_fan") || path.equals("dead_horn_coral_wall_fan")
            || path.equals("dead_tube_coral_wall_fan")) {
            return true;
        }

        // === 2) Create mod 的"施工方块" (Schematicannon 用, 不应该被玩家看到) ===
        // create:water_wheel_structure, create:large_water_wheel_structure, etc.
        // 任何 *_structure 后缀的方块, 默认按基础设施处理
        if (ns.equals("create") && (path.endsWith("_structure") || path.endsWith("_placeholder"))) {
            return true;
        }

        // === 3) Create 的"动力" 不可拾取方块 (动力源/连接器/传动轴) - 多部分方块 ===
        // 这些方块在 NBT 中通常以 multipart 形式存, Item registry 没对应, Block registry 有但 asItem() = AIR
        // 玩家无法提交, 默认已满足
        if (ns.equals("create")) {
            if (path.startsWith("axis_") || path.startsWith("shaft_") || path.startsWith("cogwheel_")
                || path.startsWith("large_cogwheel_") || path.startsWith("gearbox_")
                || path.startsWith("clutch_") || path.startsWith("gearshift_")
                || path.startsWith("encased_chain_drive_") || path.startsWith("chain_drive_")
                || path.startsWith("adjustable_chain_gearshift_")
                || path.startsWith("water_wheel_")  // create 的水车方块 (是 Block, 但通常用作动力)
                || path.startsWith("millstone_") || path.startsWith("mechanical_press_")
                || path.startsWith("mechanical_mixer_") || path.startsWith("mechanical_saw_")
                || path.startsWith("mechanical_drill_") || path.startsWith("mechanical_roller_")
                || path.startsWith("deployer_") || path.startsWith("portable_fluid_interface_")
                || path.startsWith("mechanical_piston_") || path.startsWith("piston_extension_pole_")
                || path.startsWith("gantry_shaft_") || path.startsWith("rotation_speed_controller_")
                || path.startsWith("mechanical_bearing_") || path.startsWith("clockwork_bearing_")
                || path.startsWith("rope_pulley_") || path.startsWith("elevator_pulley_")
                || path.startsWith("wind_bearing_") || path.startsWith("turn_table_")
                || path.startsWith("mechanical_arm_") || path.startsWith("arm_base_")
                || path.startsWith("arm_cog_") || path.startsWith("mechanical_spring_")
                || path.startsWith("flywheel_") || path.startsWith("crank_")
                || path.startsWith("valve_handle_") || path.startsWith("powered_latch_")
                || path.startsWith("powered_toggle_") || path.startsWith("analog_lever_")
                || path.startsWith("powered_rails_") || path.startsWith("track_station_")
                || path.startsWith("track_signal_") || path.startsWith("track_observer_")
                || path.startsWith("controller_rail_") || path.startsWith("bogey_")
                || path.startsWith("contraption_")) {
                return true;
            }
        }

        // === 4) 其他 mod 的 "structure" 通用规则 (Schematicannon 类) ===
        // 任何命名空间, 路径含 _structure/_schematic/_blueprint/structure_void 的方块
        if (path.contains("structure_void") || path.endsWith("_schematic_block")
            || path.contains("placeholder") || path.contains("construction_marker")) {
            return true;
        }

        return false;
    }
}
