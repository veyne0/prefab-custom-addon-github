package com.prefab.addon.structure;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.ConstructionInfo;
import com.prefab.addon.extension.ExtensionPackManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class CustomStructureBuilder {
    private static CustomStructureBuilder instance;
    private CompoundTag currentStructureNbt;
    private ConstructionInfo currentConstruction;

    private CustomStructureBuilder() {}

    public static CustomStructureBuilder getInstance() {
        if (instance == null) {
            instance = new CustomStructureBuilder();
        }
        return instance;
    }

    public void loadStructureFromNbt(ConstructionInfo construction) {
        this.currentConstruction = construction;

        try {
            if (construction.getNbtData() == null) {
                PrefabCustomAddon.LOGGER.error("No NBT data cached for: {}", construction.getName());
                currentStructureNbt = null;
                return;
            }
            currentStructureNbt = NbtIo.readCompressed(new ByteArrayInputStream(construction.getNbtData()), NbtAccounter.unlimitedHeap());

            PrefabCustomAddon.LOGGER.info("Loaded custom structure: {} (size={}, blocks={}, palette={})",
                    construction.getName(),
                    currentStructureNbt.get("size"),
                    currentStructureNbt.getList("blocks", 10).size(),
                    currentStructureNbt.getList("palette", 10).size());

        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("Failed to load structure from NBT", e);
            currentStructureNbt = null;
        }
    }

    public CompoundTag getCurrentStructureNbt() {
        return currentStructureNbt;
    }

    public ConstructionInfo getCurrentConstruction() {
        return currentConstruction;
    }

    /**
     * 解析NBT为方块列表。支持两种格式：
     * 1) Prefab 旧格式：每个 block tag 包含 x/y/z/block 字段
     * 2) Minecraft 标准 structure 格式：blocks 列表 + palette 列表
     */
    public List<BlockData> parseStructureBlocks() {
        List<BlockData> blocks = new ArrayList<>();

        if (currentStructureNbt == null) return blocks;

        ListTag blockList = currentStructureNbt.getList("blocks", 10);
        if (blockList.isEmpty()) return blocks;

        CompoundTag first = blockList.getCompound(0);
        // 判断格式：有 pos + state 字段 = 标准 structure 格式
        // 注意：标准格式中 pos 是 List<Int>(tagType=9)，state 是 Int(tagType=3)
        boolean hasPos = first.contains("pos");
        boolean hasState = first.contains("state");
        PrefabCustomAddon.LOGGER.info("  Format detection: hasPos={}, hasState={}, first keys={}",
                hasPos, hasState, first.getAllKeys());
        if (hasPos && hasState) {
            // 标准 Minecraft structure 格式
            return parseStandardStructure(blockList);
        } else {
            // Prefab 旧格式（兼容）
            PrefabCustomAddon.LOGGER.info("  Using LEGACY Prefab format (pos/state not found)");
            return parseLegacyPrefabFormat(blockList);
        }
    }

    /**
     * 解析 Minecraft 标准 structure NBT 格式（带 palette）
     */
    private List<BlockData> parseStandardStructure(ListTag blockList) {
        List<BlockData> blocks = new ArrayList<>();
        ListTag palette = currentStructureNbt.getList("palette", 10);

        // 预解析 palette 为 BlockState 列表
        BlockState[] paletteStates = new BlockState[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            CompoundTag entry = palette.getCompound(i);
            paletteStates[i] = readBlockState(entry);
            PrefabCustomAddon.LOGGER.info("  palette[{}] -> {} (air={})", i, paletteStates[i], paletteStates[i].isAir());
        }

        int skippedAir = 0, skippedPos = 0, skippedIndex = 0, added = 0;
        for (int i = 0; i < blockList.size(); i++) {
            CompoundTag blockTag = blockList.getCompound(i);
            // pos 在标准 structure 格式中是 List<Int>（不是 int[]）
            ListTag posList = blockTag.getList("pos", 3);
            int stateIndex = blockTag.getInt("state");

            if (posList.size() != 3) { skippedPos++; continue; }
            if (stateIndex < 0 || stateIndex >= paletteStates.length) { skippedIndex++; continue; }

            BlockState state = paletteStates[stateIndex];
            if (state == null || state.isAir()) { skippedAir++; continue; } // 跳过空气

            blocks.add(new BlockData(new BlockPos(posList.getInt(0), posList.getInt(1), posList.getInt(2)), state));
            added++;
        }
        PrefabCustomAddon.LOGGER.info("  parse result: added={}, skippedAir={}, skippedPos={}, skippedIndex={}",
                added, skippedAir, skippedPos, skippedIndex);

        return blocks;
    }

    /**
     * 解析 Prefab 旧格式（兼容）
     */
    private List<BlockData> parseLegacyPrefabFormat(ListTag blockList) {
        List<BlockData> blocks = new ArrayList<>();
        for (int i = 0; i < blockList.size(); i++) {
            CompoundTag blockTag = blockList.getCompound(i);
            int x = blockTag.getInt("x");
            int y = blockTag.getInt("y");
            int z = blockTag.getInt("z");
            String blockName = blockTag.getString("block");
            if (blockName == null || blockName.isEmpty()) continue;

            Block block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(blockName))
                    .orElse(Blocks.AIR);
            if (block == Blocks.AIR) continue;

            blocks.add(new BlockData(new BlockPos(x, y, z), block.defaultBlockState()));
        }
        return blocks;
    }

    /**
     * 解析单个 palette 条目为 BlockState。
     * palette 项形如: {Name: "minecraft:andesite", Properties: {waterlogged: "false", type: "top"}}
     * 实现逻辑：先按 Name 取 Block 的 defaultBlockState，再依次应用 Properties。
     */
    private BlockState readBlockState(CompoundTag tag) {
        try {
            if (!tag.contains("Name", 8)) {
                PrefabCustomAddon.LOGGER.warn("  readBlockState: no Name field in {}", tag);
                return Blocks.AIR.defaultBlockState();
            }
            String name = tag.getString("Name");
            Block block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(name))
                    .orElse(Blocks.AIR);
            BlockState state = block.defaultBlockState();

            if (tag.contains("Properties", 10) && block != Blocks.AIR) {
                CompoundTag props = tag.getCompound("Properties");
                StateDefinition<Block, BlockState> def = block.getStateDefinition();
                for (String key : props.getAllKeys()) {
                    Property<?> property = def.getProperty(key);
                    if (property != null) {
                        String value = props.getString(key);
                        Optional<?> optValue = property.getValue(value);
                        if (optValue.isPresent()) {
                            state = setValueHelper(state, property, optValue.get());
                        } else {
                            PrefabCustomAddon.LOGGER.warn("Unable to read property: {}={} for blockstate: {}",
                                    key, value, tag.toString());
                        }
                    }
                }
            }
            return state;
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.error("readBlockState exception for tag={}", tag, e);
            return Blocks.AIR.defaultBlockState();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState setValueHelper(BlockState state, Property<T> property, Object value) {
        return state.setValue(property, (T) value);
    }

    public boolean hasStructure() {
        return currentStructureNbt != null;
    }

    public void clearStructure() {
        currentStructureNbt = null;
        currentConstruction = null;
    }

    /**
     * 在指定位置放置自定义建筑（服务端调用）
     */
    public void placeStructure(ServerPlayer player, Level level, BlockPos origin, String packName, String constructionId) {
        PrefabCustomAddon.LOGGER.info("[PLACE-DEBUG] === placeStructure called: pack={} construction={} pos={} player={}",
            packName, constructionId, origin, player != null ? player.getName().getString() : "null");
        ConstructionInfo info = ExtensionPackManager.getInstance().findConstruction(packName, constructionId);
        if (info == null) {
            PrefabCustomAddon.LOGGER.error("Cannot find construction: {}/{}", packName, constructionId);
            return;
        }

        // 加载NBT
        loadStructureFromNbt(info);
        if (currentStructureNbt == null) {
            PrefabCustomAddon.LOGGER.error("Failed to load NBT for construction: {}", constructionId);
            return;
        }

        PrefabCustomAddon.LOGGER.info("Placing {} blocks for {} at {}", info.getName(), info.getName(), origin);

        List<BlockData> blocks = parseStructureBlocks();
        int placed = 0;
        for (BlockData data : blocks) {
            BlockPos target = origin.offset(data.pos);
            level.setBlock(target, data.state, 3);
            placed++;
        }

        PrefabCustomAddon.LOGGER.info("Placed {}/{} blocks for {} at {}",
                placed, blocks.size(), info.getName(), origin);
        if (player != null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "Placed " + placed + " blocks for " + info.getName()).withStyle(net.minecraft.ChatFormatting.GREEN));
            // 关键：消耗一个自定义蓝图
            // 使用 Prefab 的**官方**模式（BasicStructureConfiguration.java#buildStructure）：
            //   stack.getCount() == 1 → inventory.removeItem(stack)
            //   否则                → stack.setCount(stack.getCount() - 1)
            //   最后                → player.containerMenu.broadcastChanges()  ← 关键
            // 仅 setItem() 不会被推到客户端，必须 broadcastChanges() 才能让玩家看到物品消失
            net.minecraft.world.entity.player.Inventory inv = player.getInventory();
            int foundSlot = -1;
            net.minecraft.world.item.ItemStack foundStack = net.minecraft.world.item.ItemStack.EMPTY;
            // 调试：列出所有 Blueprint 槽位和它们的绑定状态
            int blueprintSlots = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                net.minecraft.world.item.ItemStack stack = inv.getItem(i);
                if (stack.getItem() instanceof com.prefab.addon.items.CustomBlueprintItem) {
                    blueprintSlots++;
                    String pn = com.prefab.addon.items.CustomBlueprintItem.getBoundPackName(stack);
                    String cid = com.prefab.addon.items.CustomBlueprintItem.getBoundConstructionId(stack);
                    boolean matches = com.prefab.addon.items.CustomBlueprintItem.isBoundTo(stack, info);
                    PrefabCustomAddon.LOGGER.info("[BLUEPRINT-DEBUG]   slot {}: blueprint bound pack={} construction={} matches={}",
                        i, pn, cid, matches);
                }
            }
            PrefabCustomAddon.LOGGER.info("[BLUEPRINT-DEBUG] Total custom blueprints in inventory: {} (expected: 1+ for {}/{})",
                blueprintSlots, packName, constructionId);

            for (int i = 0; i < inv.getContainerSize(); i++) {
                net.minecraft.world.item.ItemStack stack = inv.getItem(i);
                if (com.prefab.addon.items.CustomBlueprintItem.isBoundTo(stack, info)) {
                    foundSlot = i;
                    foundStack = stack;
                    break;
                }
            }
            if (!foundStack.isEmpty()) {
                PrefabCustomAddon.LOGGER.info("[BLUEPRINT-DEBUG] Found bound blueprint in slot {} (count={}), consuming",
                    foundSlot, foundStack.getCount());
                if (foundStack.getCount() == 1) {
                    // 整组移除（count=1 时直接清空槽位）
                    inv.setItem(foundSlot, net.minecraft.world.item.ItemStack.EMPTY);
                } else {
                    // 多组时只减 1
                    foundStack.setCount(foundStack.getCount() - 1);
                }
                // 关键：把变化广播给客户端，否则客户端看不到物品消失
                if (player.containerMenu != null) {
                    player.containerMenu.broadcastChanges();
                }
                // 防御：标记物品栏脏，强制下次 tick 重新发送
                inv.setChanged();
                PrefabCustomAddon.LOGGER.info("[BLUEPRINT-DEBUG] Consumed custom blueprint, slot {} now contains {}",
                    foundSlot, inv.getItem(foundSlot));
            } else {
                PrefabCustomAddon.LOGGER.warn("[BLUEPRINT-DEBUG] No bound custom blueprint found in inventory for {}/{} (searched {} slots)",
                    info.getPack() != null ? info.getPack().getName() : "?", info.getId(), inv.getContainerSize());
            }
        }
    }

    public static class BlockData {
        public final BlockPos pos;
        public final BlockState state;

        public BlockData(BlockPos pos, BlockState state) {
            this.pos = pos;
            this.state = state;
        }
    }

    /**
     * 把 ConstructionInfo 的 NBT 解析为 Prefab 的 Structure 实例。
     * 复用 Prefab 原生 Structure 类，让 GuiStructure 直接处理预览/建造。
     *
     * 关键：每个 BuildBlock 必须正确设置：
     *  - blockDomain, blockName（资源位置）
     *  - startingPosition（PositionOffset：存方块在结构内的偏移）
     *  - state（BlockState，必须非 null！rebuildPreviewMeshes 调 getBlockState() 渲染）
     *  - properties（可选，特殊方块需要）
     */
    public static com.prefab.structures.base.Structure parseToPrefabStructure(ConstructionInfo construction) {
        // 1. 加载 NBT
        CompoundTag nbt;
        try {
            if (construction.getNbtData() == null) {
                throw new IOException("No NBT data for: " + construction.getId());
            }
            nbt = NbtIo.readCompressed(new ByteArrayInputStream(construction.getNbtData()), NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("Failed to load NBT for {}", construction.getId(), e);
            return null;
        }

        if (nbt == null) {
            PrefabCustomAddon.LOGGER.error("NBT is null for {}", construction.getId());
            return null;
        }

        // 2. 解析 palette
        ListTag paletteList = nbt.getList("palette", 10);
        BlockState[] paletteStates = new BlockState[paletteList.size()];
        CustomStructureBuilder self = new CustomStructureBuilder();
        for (int i = 0; i < paletteList.size(); i++) {
            paletteStates[i] = self.readBlockState(paletteList.getCompound(i));
        }

        // 3. 创建 Prefab Structure 实例（直接 new，CreateInstance 从 jar 资源读 JSON，不适合我们）
        com.prefab.structures.base.Structure structure = new com.prefab.structures.base.Structure();
        structure.setName(construction.getId());

        // 4. 解析 blocks 为 BuildBlock 列表
        List<com.prefab.structures.base.BuildBlock> buildBlocks = new ArrayList<>();
        ListTag blockList = nbt.getList("blocks", 10);

        com.prefab.structures.config.StructureConfiguration config = new com.prefab.structures.config.StructureConfiguration();
        config.Initialize();

        // 客户端可获取 level（用于 SetBlockState 中的 world 操作）
        Level world = net.minecraft.client.Minecraft.getInstance().level;

        int skippedAir = 0, skippedPos = 0, skippedIndex = 0, errorCount = 0, added = 0;
        for (int i = 0; i < blockList.size(); i++) {
            CompoundTag blockTag = blockList.getCompound(i);
            if (!blockTag.contains("pos") || !blockTag.contains("state")) { skippedPos++; continue; }

            ListTag posList = blockTag.getList("pos", 3);
            if (posList.size() != 3) { skippedPos++; continue; }
            int stateIndex = blockTag.getInt("state");
            if (stateIndex < 0 || stateIndex >= paletteStates.length) { skippedIndex++; continue; }
            BlockState state = paletteStates[stateIndex];
            if (state == null || state.isAir()) { skippedAir++; continue; }

            int bx = posList.getInt(0);
            int by = posList.getInt(1);
            int bz = posList.getInt(2);

            // 4.1 创建一个新的 BuildBlock 并设置资源位置
            com.prefab.structures.base.BuildBlock bb = new com.prefab.structures.base.BuildBlock();
            bb.Initialize();
            // setBlockDomain/setBlockName (私有字段 via setter)
            bb.setBlockDomain(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace());
            bb.setBlockName(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());

            // 4.2 设置 startingPosition（PositionOffset）
            // 关键：用 Prefab 自带的 Structure.getStartingPositionFromOriginalAndCurrentPosition！
            // 这个方法内部根据 currentPos 相对 originalPos 的方向选择 eastOffset/westOffset/southOffset/northOffset，
            // 配合 getRelativePosition(originalPos, NORTH, houseFacing) 会**正确还原** (bx, by, bz)。
            // 之前我自己手动同时设 eastOffset=bx 和 westOffset=-bx 导致 X 被累加为 2*bx（"对称"效果）。
            // 我们这里只需要 relative offset，basePos (configuration.pos) 在 Rebuild 时**才**确定。
            // 用一个虚拟 originalPos=(0,0,0) 和 currentPos=(bx, by, bz) 计算 offset。
            net.minecraft.core.BlockPos currentPos = new net.minecraft.core.BlockPos(bx, by, bz);
            com.prefab.structures.base.PositionOffset offset =
                com.prefab.structures.base.Structure.getStartingPositionFromOriginalAndCurrentPosition(currentPos, net.minecraft.core.BlockPos.ZERO);
            bb.setStartingPosition(offset);

            // 4.3 显式设置 state（关键！rebuildPreviewMeshes 调 getBlockState() 拿这个）
            bb.setBlockState(state);

            // 4.4 blockStateData 设为空（不使用 Tag 格式）
            bb.setBlockStateData("");

            // 4.5 blockPos 字段（rebuildPreviewMeshes 内部 bakeBlockAndSubBlock 用 bb.blockPos 检查 world state，
            //     必须是**绝对世界位置** = basePos + offset）
            // 注意：basePos = configuration.pos，在 Rebuild 时才确定。
            // 我们设的 bb.blockPos 应当 = basePos + (bx, by, bz)。
            // 但 basePos 在 Rebuild 时会**重新计算**并设给 bb.blockPos（line 393 putfield blockPos）。
            // 实际上 Prefab 的 Rebuild 流程：
            //   1. 调用 getStartingPosition().getRelativePosition(basePos, NORTH, houseFacing) = world pos
            //   2. **直接用**这个 world pos 作为 chunk 坐标（line 129-163）
            //   3. bakeBlockAndSubBlock 内部用 bb.blockPos 检查 world state（line 9-21）
            // 所以 bb.blockPos 必须是**绝对世界位置** = basePos + offset
            // 由于 basePos 不可知，我们先设一个 placeholder（不会影响 chunk 计算，因为 chunk 用的是 getRelativePosition 的结果）
            bb.blockPos = currentPos;

            buildBlocks.add(bb);
            added++;
        }
        PrefabCustomAddon.LOGGER.info("parseToPrefabStructure: {} added={}, skippedAir={}, skippedPos={}, skippedIndex={}, errors={}",
                construction.getId(), added, skippedAir, skippedPos, skippedIndex, errorCount);

        structure.setBlocks((ArrayList) buildBlocks);

        // 5. 设置 clear space
        com.prefab.structures.base.BuildClear clearSpace = new com.prefab.structures.base.BuildClear();
        if (nbt.contains("size")) {
            net.minecraft.nbt.ListTag sizeList = nbt.getList("size", 3);
            if (sizeList.size() == 3) {
                com.prefab.structures.base.BuildShape shape = clearSpace.getShape();
                if (shape != null) {
                    shape.setWidth(sizeList.getInt(0));
                    shape.setHeight(sizeList.getInt(1));
                    shape.setLength(sizeList.getInt(2));
                    shape.setDirection(net.minecraft.core.Direction.NORTH);
                }
            }
        }
        structure.setClearSpace(clearSpace);

        return structure;
    }
}
