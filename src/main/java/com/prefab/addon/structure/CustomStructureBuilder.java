package com.prefab.addon.structure;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.extension.ConstructionInfo;
import com.prefab.addon.extension.ExtensionPackManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
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
            CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(construction.getNbtData()), NbtAccounter.unlimitedHeap());

            PrefabCustomAddon.LOGGER.info("Loaded custom structure: {} (root keys={}, size={}, blocks={}, palette={})",
                    construction.getName(),
                    root.getAllKeys(),
                    root.get("size"),
                    root.getList("blocks", 10).size(),
                    root.getList("palette", 10).size());

            // 兼容: 某些工具的 NBT 顶层不在根, 而是包在嵌套 key 里:
            //   - vanilla .nbt 文件: 整结构在 "structure" compound 里
            //   - litematic .litematic: 整结构在 "Schematic" compound 里
            //   - 一些打包脚本: 包在 "data" 里
            // 这里递归查找第一个含 "blocks" + "palette" 的 CompoundTag
            if (!root.contains("blocks", 10) || !root.contains("palette", 10)) {
                PrefabCustomAddon.LOGGER.info("  Root has no blocks+palette, 尝试解包嵌套结构 (keys={})", root.getAllKeys());
                CompoundTag unwrapped = findNestedStructure(root, 0);
                if (unwrapped != null && unwrapped != root) {
                    PrefabCustomAddon.LOGGER.info("  解包成功, 找到嵌套的 blocks+palette");
                    root = unwrapped;
                } else {
                    PrefabCustomAddon.LOGGER.warn("  未找到嵌套 blocks+palette, 继续用原始根");
                }
            }
            currentStructureNbt = root;

        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("Failed to load structure from NBT", e);
            currentStructureNbt = null;
        }
    }

    /**
     * 递归查找第一个同时含 "blocks" 和 "palette" 的 CompoundTag.
     * 限制递归深度 5, 防止极端病态 NBT.
     */
    private CompoundTag findNestedStructure(CompoundTag tag, int depth) {
        if (depth > 5) return null;
        if (tag.contains("blocks", 10) && tag.contains("palette", 10)) return tag;
        for (String key : tag.getAllKeys()) {
            Tag child = tag.get(key);
            if (child instanceof CompoundTag childC) {
                CompoundTag found = findNestedStructure(childC, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    public CompoundTag getCurrentStructureNbt() {
        return currentStructureNbt;
    }

    public ConstructionInfo getCurrentConstruction() {
        return currentConstruction;
    }

    /**
     * 静态方法: 从一个 NBT (CompoundTag) 直接解析方块列表. 不依赖实例状态.
     * 用于 GuiCreateBuildingInfo 等需要在打开 GUI 时根据已加载的 NBT 直接生成 3D 预览的场景.
     *
     * @param nbt 标准 vanilla structure NBT (含 blocks + palette)
     * @return List of BlockData
     */
    public static List<BlockData> parseStructureBlocksFromNbt(CompoundTag nbt) {
        if (nbt == null) {
            PrefabCustomAddon.LOGGER.warn("[PARSE-STATIC] nbt == null");
            return new ArrayList<>();
        }
        ListTag blockList = nbt.getList("blocks", 10);
        if (blockList.isEmpty()) {
            PrefabCustomAddon.LOGGER.warn("[PARSE-STATIC] blocks 列表为空");
            return new ArrayList<>();
        }
        // 格式探测: 前 5 个 block 任意一个有 pos+state 就认为是 standard
        boolean hasPos = false;
        boolean hasState = false;
        int probeCount = Math.min(5, blockList.size());
        for (int i = 0; i < probeCount; i++) {
            CompoundTag b = blockList.getCompound(i);
            if (b.contains("pos")) hasPos = true;
            if (b.contains("state")) hasState = true;
            if (hasPos && hasState) break;
        }
        if (hasPos && hasState) {
            return parseStandardStructureStatic(nbt, blockList);
        } else {
            return parseLegacyPrefabFormatStatic(blockList);
        }
    }

    private static List<BlockData> parseStandardStructureStatic(CompoundTag nbt, ListTag blockList) {
        List<BlockData> blocks = new ArrayList<>();
        ListTag palette = nbt.getList("palette", 10);
        if (palette.isEmpty()) {
            PrefabCustomAddon.LOGGER.warn("[PARSE-STATIC] standard 格式但 palette 为空");
            return blocks;
        }
        BlockState[] paletteStates = new BlockState[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            paletteStates[i] = readBlockState(palette.getCompound(i));
        }
        for (int i = 0; i < blockList.size(); i++) {
            CompoundTag blockTag = blockList.getCompound(i);
            int bx, by, bz;
            Tag posTag = blockTag.get("pos");
            if (posTag instanceof ListTag posList && posList.size() == 3) {
                bx = posList.getInt(0);
                by = posList.getInt(1);
                bz = posList.getInt(2);
            } else if (posTag instanceof IntArrayTag posIArr && posIArr.size() == 3) {
                int[] posArr = posIArr.getAsIntArray();
                bx = posArr[0];
                by = posArr[1];
                bz = posArr[2];
            } else if (posTag instanceof CompoundTag posC) {
                bx = posC.getInt("x");
                by = posC.getInt("y");
                bz = posC.getInt("z");
            } else {
                continue;
            }
            // 兼容 1.21.1+ vanilla 格式: state 可能是 CompoundTag {Name, Properties?}, 也可能是 int palette index
            BlockState state = readBlockStateFromBlockTag(blockTag, paletteStates);
            if (state == null || state.isAir()) continue;
            blocks.add(new BlockData(new BlockPos(bx, by, bz), state));
        }
        return blocks;
    }

    private static List<BlockData> parseLegacyPrefabFormatStatic(ListTag blockList) {
        // 老 Prefab 格式不常用, 暂返回空. (3D 预览只支持 vanilla standard 即可)
        PrefabCustomAddon.LOGGER.info("[PARSE-STATIC] Legacy Prefab format 不支持 3D 预览");
        return new ArrayList<>();
    }

    /**
     * 解析NBT为方块列表。支持两种格式：
     * 1) Prefab 旧格式：每个 block tag 包含 x/y/z/block 字段
     * 2) Minecraft 标准 structure 格式：blocks 列表 + palette 列表
     */
    public List<BlockData> parseStructureBlocks() {
        List<BlockData> blocks = new ArrayList<>();

        if (currentStructureNbt == null) {
            PrefabCustomAddon.LOGGER.warn("[PARSE] currentStructureNbt == null");
            return blocks;
        }

        ListTag blockList = currentStructureNbt.getList("blocks", 10);
        if (blockList.isEmpty()) {
            PrefabCustomAddon.LOGGER.warn("[PARSE] blocks 列表为空 (nbt size={})", currentStructureNbt.size());
            return blocks;
        }

        PrefabCustomAddon.LOGGER.info("[PARSE] 检测格式: blocks={}个, palette={}个, nbt keys={}",
            blockList.size(),
            currentStructureNbt.getList("palette", 10).size(),
            currentStructureNbt.getAllKeys());

        // 格式探测: 不只检查第一个 block (有些建筑第一个 block 是 nbt structure 的
        // 特殊标记, 没有 pos/state 字段, 会误判成 Legacy 格式 → 全部按 "block" 字段
        // 找不到 → 0 块), 而是前 5 个 block 任意一个有 pos+state 就认为是 standard 格式
        boolean hasPos = false;
        boolean hasState = false;
        int probeCount = Math.min(5, blockList.size());
        for (int i = 0; i < probeCount; i++) {
            CompoundTag b = blockList.getCompound(i);
            if (b.contains("pos")) hasPos = true;
            if (b.contains("state")) hasState = true;
            if (hasPos && hasState) break;
        }
        CompoundTag first = blockList.getCompound(0);
        PrefabCustomAddon.LOGGER.info("[PARSE] 格式探测: hasPos={}, hasState={}, first keys={} (前 {} 个 block 统计)",
                hasPos, hasState, first.getAllKeys(), probeCount);
        if (hasPos && hasState) {
            // 标准 Minecraft structure 格式
            return parseStandardStructure(blockList);
        } else {
            // Prefab 旧格式（兼容）
            PrefabCustomAddon.LOGGER.info("[PARSE] Using LEGACY Prefab format (pos/state not found)");
            return parseLegacyPrefabFormat(blockList);
        }
    }

    /**
     * 解析 Minecraft 标准 structure NBT 格式（带 palette）
     *
     * <p>关键: pos 字段在 NBT 里有多种写法, 必须同时支持:</p>
     * <ul>
     *   <li>{@code TAG_LIST} (tag 9) — 1.21+ 标准, LitematicaParser 默认输出</li>
     *   <li>{@code TAG_INT_ARRAY} (tag 11) — 旧 mod 输出, 1.20- 兼容</li>
     *   <li>{@code TAG_COMPOUND} (tag 10) — 旧版用 {x,y,z} Compound 形式</li>
     * </ul>
     *
     * <p>只支持 ListTag 的旧版会把 IntArray/Compound 写法的 litematic (.litematic 文件走某些
     * 序列化路径会回退到 IntArray, 比如 NbtAccounter 触发 OOM 时的兜底路径) 整批跳过
     * → 玩家看到 "Placed 0 blocks".</p>
     */
    private List<BlockData> parseStandardStructure(ListTag blockList) {
        List<BlockData> blocks = new ArrayList<>();
        ListTag palette = currentStructureNbt.getList("palette", 10);

        if (palette.isEmpty()) {
            PrefabCustomAddon.LOGGER.warn("[PARSE] standard 格式但 palette 为空! blocks={}, palette=0", blockList.size());
            return blocks;
        }

        // 预解析 palette 为 BlockState 列表
        BlockState[] paletteStates = new BlockState[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            CompoundTag entry = palette.getCompound(i);
            paletteStates[i] = readBlockState(entry);
        }

        int skippedAir = 0, skippedPos = 0, skippedIndex = 0, skippedPosType = 0, added = 0;
        for (int i = 0; i < blockList.size(); i++) {
            CompoundTag blockTag = blockList.getCompound(i);

            int bx, by, bz;
            if (blockTag.contains("pos", Tag.TAG_LIST)) {
                ListTag posList = blockTag.getList("pos", 3);
                if (posList.size() != 3) { skippedPos++; continue; }
                bx = posList.getInt(0);
                by = posList.getInt(1);
                bz = posList.getInt(2);
            } else if (blockTag.contains("pos", Tag.TAG_INT_ARRAY)) {
                int[] posArr = blockTag.getIntArray("pos");
                if (posArr.length < 3) { skippedPos++; continue; }
                bx = posArr[0];
                by = posArr[1];
                bz = posArr[2];
            } else if (blockTag.contains("pos", Tag.TAG_COMPOUND)) {
                CompoundTag p = blockTag.getCompound("pos");
                bx = p.getInt("x");
                by = p.getInt("y");
                bz = p.getInt("z");
            } else if (blockTag.contains("pos")) {
                // pos 字段存在但类型不在已知 3 种中 - 诊断
                byte posTypeId = blockTag.getTagType("pos");
                skippedPosType++;
                if (i < 3) {
                    PrefabCustomAddon.LOGGER.warn("[PARSE] block#{} pos 字段类型未知: tagId={}, keys={}, full={}",
                        i, posTypeId, blockTag.getAllKeys(), blockTag);
                }
                continue;
            } else if (blockTag.contains("x") && blockTag.contains("y") && blockTag.contains("z")) {
                // 有些工具把 pos 写为独立 x/y/z 字段而不是 pos 列表
                bx = blockTag.getInt("x");
                by = blockTag.getInt("y");
                bz = blockTag.getInt("z");
            } else {
                skippedPos++; continue;
            }

            // 兼容 1.21.1+ vanilla 格式: state 可能是 CompoundTag {Name, Properties?}, 也可能是 int palette index
            BlockState state = readBlockStateFromBlockTag(blockTag, paletteStates);
            if (state == null) { skippedIndex++; continue; }
            if (state.isAir()) { skippedAir++; continue; } // 跳过空气

            blocks.add(new BlockData(new BlockPos(bx, by, bz), state));
            added++;
        }
        PrefabCustomAddon.LOGGER.info("[PARSE] standard 解析结果: added={}, skippedAir={}, skippedPos={}, skippedPosType={}, skippedIndex={}, palette={}, totalBlocks={}",
                added, skippedAir, skippedPos, skippedPosType, skippedIndex, palette.size(), blockList.size());

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
    private static BlockState readBlockState(CompoundTag tag) {
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

    /**
     * 从一个 block tag 解析出 BlockState.
     * 兼容两种 vanilla structure NBT 格式:
     * <ul>
     *   <li>1.21+ 标准: {@code state: CompoundTag {Name, Properties?}} — 直接调 readBlockState</li>
     *   <li>Litematica 风格: {@code state: int} — 当成 palette 索引查 paletteStates 数组</li>
     * </ul>
     * 解析失败 (state 字段缺失 / 类型未知 / 索引越界) 返回 null.
     */
    private static BlockState readBlockStateFromBlockTag(CompoundTag blockTag, BlockState[] paletteStates) {
        // 先看 state 字段是什么类型
        if (blockTag.contains("state", Tag.TAG_COMPOUND)) {
            // 1.21+ 标准格式: state 是 CompoundTag
            return readBlockState(blockTag.getCompound("state"));
        }
        if (blockTag.contains("state", Tag.TAG_INT)) {
            // Litematica 风格: state 是 int palette index
            int stateIndex = blockTag.getInt("state");
            if (stateIndex < 0 || stateIndex >= paletteStates.length) return null;
            return paletteStates[stateIndex];
        }
        // 兜底: 有些工具写 state 为 0 (无 palette 索引含义), 我们把 palette[0] 当作 fallback
        // 但仅当 state 字段确实存在 (只是类型不是 INT 也不是 COMPOUND, 比如是 BYTE)
        if (blockTag.contains("state")) {
            try {
                int stateIndex = blockTag.getInt("state");
                if (stateIndex >= 0 && stateIndex < paletteStates.length) return paletteStates[stateIndex];
            } catch (Throwable ignored) {}
        }
        return null;
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
     * 在指定位置放置自定义建筑（服务端调用） - **异步**版本 (无旋转, 默认 SOUTH).
     * 详见下方带 {@link net.minecraft.core.Direction} 参数的重载.
     */
    public void placeStructure(ServerPlayer player, Level level, BlockPos origin, String packName, String constructionId) {
        placeStructure(player, (net.minecraft.server.level.ServerLevel) level, origin, packName, constructionId, net.minecraft.core.Direction.SOUTH);
    }

    /**
     * 在指定位置放置自定义建筑（服务端调用） - **异步**版本.
     *
     * <p>流程: 加载 NBT → 修正 vanilla 格式 (size/pos) → 应用 RedstoneSafe 剥字段
     * → 解析为 {@link BlockData} 列表 → 启动 {@link AsyncBuildManager} 任务.</p>
     *
     * <p>后续 server tick 里分批放置, 每批 buildBatchPercent% 个方块.
     * 完成后异步任务自动: 触发红石重算 + 消耗蓝图 + 发成功消息.</p>
     *
     * <p>相比旧版一次性 setBlock 全部方块, 异步版每 tick 耗时 ~几十 ms (40k 块结构),
     * 避免单 tick 卡 1+ 秒. 玩家可正常游戏, 聊天栏实时显示进度.</p>
     *
     * <p>houseFacing: 预览时的旋转方向, 旋转在 AsyncBuildManager.processTick 里
     * **逐方块**进行 (绕 (0,0,0), 公式 (x,z)→(z,-x), 跟客户端 offsetStructureBlocks 一致).</p>
     *
     * @return true=异步任务已启动 (不代表放完), false=失败 (找不到建筑 / 加载失败 / 无效 NBT)
     */
    public boolean placeStructure(ServerPlayer player, net.minecraft.server.level.ServerLevel level, BlockPos origin,
                               String packName, String constructionId,
                               net.minecraft.core.Direction houseFacing) {
        PrefabCustomAddon.LOGGER.info("[PLACE-ASYNC] === placeStructure called: pack={} construction={} pos={} player={} houseFacing={}",
            packName, constructionId, origin, player != null ? player.getName().getString() : "null", houseFacing);
        ConstructionInfo info = ExtensionPackManager.getInstance().findConstruction(packName, constructionId);
        if (info == null) {
            PrefabCustomAddon.LOGGER.error("[PLACE-ASYNC] 找不到建筑: {}/{}", packName, constructionId);
            if (player != null) {
                // 多行提示: 告诉玩家 (1) 真正的"权威"是服务端 prefab-extension/ 而不是本地
                // (2) 怎么把包放到服务端 (3) 放完后怎么同步到客户端: 按 O → 同步拓展包
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    com.prefab.addon.PrefabCustomAddon.tr("err.not_found", packName, constructionId)
                    + " §7(包名 " + packName + ")").withStyle(net.minecraft.ChatFormatting.RED));
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    com.prefab.addon.PrefabCustomAddon.tr("err.hint.pack_on_server"))
                    .withStyle(net.minecraft.ChatFormatting.YELLOW));
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    com.prefab.addon.PrefabCustomAddon.tr("err.hint.sync_server"))
                    .withStyle(net.minecraft.ChatFormatting.YELLOW));
            }
            return false;
        }

        // 1. 加载 NBT
        loadStructureFromNbt(info);
        if (currentStructureNbt == null) {
            PrefabCustomAddon.LOGGER.error("[PLACE-ASYNC] 加载 NBT 失败: {}", constructionId);
            if (player != null) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§c✗ 加载建筑失败: " + constructionId).withStyle(net.minecraft.ChatFormatting.RED));
            }
            return false;
        }

        // 2. 修正 NBT 格式 (vanilla 兼容: size→ListTag[Int,Int,Int], pos→IntArrayTag)
        ensureNbtVanillaFormat(currentStructureNbt);

        // 3. 应用 RedstoneSafe: 剥除 power/signal/Items/CustomName/command 等危险字段
        //    (红石/拉杆/漏斗等组件在分批放置时, 周围方块可能尚未就绪, 剥字段避免变成掉落物)
        applyRedstoneSafeToNbt(currentStructureNbt);

        // 4. 解析 NBT 为 BlockData 列表 (跳过空气/无效方块)
        List<BlockData> blockDataList = parseStructureBlocks();
        if (blockDataList.isEmpty()) {
            PrefabCustomAddon.LOGGER.error("[PLACE-ASYNC] 解析后无有效方块");
            if (player != null) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    com.prefab.addon.PrefabCustomAddon.tr("err.parse_no_blocks")).withStyle(net.minecraft.ChatFormatting.RED));
            }
            return false;
        }

        // 5. 启动异步任务
        //    - 后续 server tick 里分批放置, 玩家不卡
        //    - 旋转在 AsyncBuildManager.processTick 里**逐方块**进行 (绕 (0,0,0), 跟客户端 offsetStructureBlocks 一致)
        //    - 完成后自动消耗蓝图 + 红石重算
        PrefabCustomAddon.LOGGER.info("[PLACE-ASYNC] 启动异步任务: {} blocks, batchPercent={}%, houseFacing={}",
            blockDataList.size(), com.prefab.addon.config.PlayerPreferences.get().getBuildBatchPercent(), houseFacing);
        AsyncBuildManager.startTask(player, level, origin, packName, constructionId, blockDataList, houseFacing);

        if (player != null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§e⏳ 开始建造 " + constructionId + ": " + blockDataList.size() + " 块 (异步分批, "
                + com.prefab.addon.config.PlayerPreferences.get().getBuildBatchPercent() + "%, 进度在聊天栏)")
                .withStyle(net.minecraft.ChatFormatting.YELLOW));
        }
        return true;
    }

    /**
     * 把 NBT 转成 vanilla 兼容的格式.
     * <p>主要是 size 和 pos 字段: vanilla StructureTemplate (1.21.1) 期待:
     * <ul>
     *   <li>size: ListTag 含 3 个 IntTag (不是 IntArray, 不是 CompoundTag {x,y,z})</li>
     *   <li>每个 block 的 pos: IntArrayTag (3 个 int) (不是 ListTag)</li>
     * </ul>
     * 我们的 LitematicaParser / 缓存 NBT 可能是 ListTag 格式, 这里统一转成 vanilla 期待的格式.
     */
    private void ensureNbtVanillaFormat(CompoundTag nbt) {
        if (nbt == null) return;

        // 1. size 字段
        if (nbt.contains("size")) {
            net.minecraft.nbt.Tag sizeTag = nbt.get("size");
            int sx = 0, sy = 0, sz = 0;
            boolean needConvert = true;
            if (sizeTag instanceof net.minecraft.nbt.ListTag sizeList
                && sizeList.size() == 3
                && sizeList.getElementType() == net.minecraft.nbt.Tag.TAG_INT) {
                // 已经是正确的 ListTag[3 IntTags]
                needConvert = false;
            } else if (sizeTag instanceof net.minecraft.nbt.IntArrayTag intArr && intArr.size() == 3) {
                int[] arr = intArr.getAsIntArray();
                sx = arr[0]; sy = arr[1]; sz = arr[2];
            } else if (sizeTag instanceof net.minecraft.nbt.CompoundTag ct) {
                sx = ct.getInt("x");
                sy = ct.getInt("y");
                sz = ct.getInt("z");
            } else if (sizeTag instanceof net.minecraft.nbt.ListTag sizeList2 && sizeList2.size() == 3) {
                sx = sizeList2.getInt(0);
                sy = sizeList2.getInt(1);
                sz = sizeList2.getInt(2);
            }
            if (needConvert) {
                net.minecraft.nbt.ListTag sizeListFinal = new net.minecraft.nbt.ListTag();
                sizeListFinal.add(net.minecraft.nbt.IntTag.valueOf(sx));
                sizeListFinal.add(net.minecraft.nbt.IntTag.valueOf(sy));
                sizeListFinal.add(net.minecraft.nbt.IntTag.valueOf(sz));
                nbt.put("size", sizeListFinal);
                PrefabCustomAddon.LOGGER.info("[PLACE-ASYNC] NBT size → ListTag[{},{},{}]", sx, sy, sz);
            }
        }

        // 2. blocks.pos 字段
        if (nbt.contains("blocks", net.minecraft.nbt.Tag.TAG_LIST)) {
            net.minecraft.nbt.ListTag blocksList = nbt.getList("blocks", 10);
            int converted = 0;
            for (int i = 0; i < blocksList.size(); i++) {
                net.minecraft.nbt.CompoundTag blockTag = (net.minecraft.nbt.CompoundTag) blocksList.get(i);
                if (blockTag.contains("pos", net.minecraft.nbt.Tag.TAG_LIST)) {
                    net.minecraft.nbt.ListTag posList = blockTag.getList("pos", 3);
                    if (posList.size() == 3) {
                        int x = posList.getInt(0);
                        int y = posList.getInt(1);
                        int z = posList.getInt(2);
                        blockTag.put("pos", new net.minecraft.nbt.IntArrayTag(new int[]{x, y, z}));
                        converted++;
                    }
                }
            }
            if (converted > 0) {
                PrefabCustomAddon.LOGGER.info("[PLACE-ASYNC] NBT blocks.pos: 转换 {} 个 ListTag→IntArrayTag", converted);
            }
        }
    }

    /**
     * 对 NBT 里的每个 block 的 nbt 字段, 应用 RedstoneSafe 剥除危险字段.
     * 关键: 分批放置时, 红石/拉杆/红石粉/漏斗等组件的 onPlace 会检查周围方块
     * (如果周围还是 air, 会被判定为"失去支撑"而 pop off 变掉落物).
     * 提前剥除 power/signal/Items/CustomName/command 等字段, 避免这种"伪状态".
     *
     * <p>注意: 这里不能直接复用 vanilla StructurePlaceSettings 的 RedstoneSafeStructureProcessor,
     * 因为我们不走 vanilla placeInWorld, 是手动 setBlock.</p>
     */
    private static void applyRedstoneSafeToNbt(CompoundTag nbt) {
        if (nbt == null || !nbt.contains("blocks", net.minecraft.nbt.Tag.TAG_LIST)) return;
        net.minecraft.nbt.ListTag blocksList = nbt.getList("blocks", 10);
        int stripped = 0;
        for (int i = 0; i < blocksList.size(); i++) {
            net.minecraft.nbt.CompoundTag bt = blocksList.getCompound(i);
            if (!bt.contains("nbt")) continue;
            net.minecraft.nbt.CompoundTag beNbt = bt.getCompound("nbt");
            for (String key : beNbt.getAllKeys().toArray(new String[0])) {
                if (RedstoneSafeStructureProcessor.STRIPPED_FIELDS_SET.contains(key)) {
                    beNbt.remove(key);
                    stripped++;
                }
            }
        }
        PrefabCustomAddon.LOGGER.info("[PLACE-ASYNC] RedstoneSafe: 剥除 {} 个危险字段", stripped);
    }

    /**
     * houseFacing -> 90° 旋转步数 (绕 Y 轴, CCW 俯视, 跟客户端 offsetStructureBlocks 一致).
     * SOUTH=0, EAST=1, NORTH=2, WEST=3.
     */
    private static int facingToRotationSteps(net.minecraft.core.Direction facing) {
        if (facing == null) return 0;
        return switch (facing) {
            case SOUTH -> 0;
            case EAST  -> 1;
            case NORTH -> 2;
            case WEST  -> 3;
            default    -> 0;
        };
    }

    public static class BlockData {
        public final BlockPos pos;
        public final BlockState state;

        public BlockData(BlockPos pos, BlockState state) {
            this.pos = pos;
            this.state = state;
        }
    }

    // === 全局表: 记录每个 Structure 里的 BuildBlock 局部位置 ===
    // 供 CustomStructureGui 在预览前调 offsetStructureBlocks() 把 bb.blockPos
    // 从 localPos 转换为 worldPos (= basePos + localPos), 让 Prefab 的
    // bakeBlockAndSubBlock 查 world state 时检查的是真实世界位置 (而不是原点附近).
    private static final java.util.Map<com.prefab.structures.base.Structure, java.util.Map<com.prefab.structures.base.BuildBlock, BlockPos>> STRUCTURE_LOCAL_POS =
            new java.util.WeakHashMap<>();

    /**
     * 外部 (如 {@code CloudPreview.buildStructureFromCloud}) 把 structure 对应的
     * localPos map 注入. 不调这个, {@link #offsetStructureBlocks} 会 warn 后 return,
     * KeyHandler 移动/旋转预览时 blockPos 不更新 → 预览卡原位置.
     */
    public static void putLocalPosMap(com.prefab.structures.base.Structure structure,
                                       java.util.Map<com.prefab.structures.base.BuildBlock, BlockPos> localMap) {
        if (structure == null || localMap == null) return;
        STRUCTURE_LOCAL_POS.put(structure, localMap);
    }

    /**
     * 遍历 structure.getBlocks() 里的每个 BuildBlock, 把它的 blockPos
     * 重新设置为 basePos + localPos (worldPos).
     * 必须在 StructureRenderHandler.setStructure() **之前**调用.
     *
     * <p>支持 houseFacing 旋转 (新参数重载): 玩家按 CTRL 时 cfg.houseFacing 变化,
     * 这里会同时把 localPos 围绕 bbox 中心旋转 90° × steps 步, 然后再 basePos + rotatedLocal.
     * 不传 houseFacing 等价于 SOUTH (不旋转), 向后兼容.</p>
     */
    public static void offsetStructureBlocks(com.prefab.structures.base.Structure structure, BlockPos basePos) {
        offsetStructureBlocks(structure, basePos, net.minecraft.core.Direction.SOUTH);
    }

    /**
     * 带旋转的 offset. houseFacing 决定旋转步数 (SOUTH=0, EAST=1, NORTH=2, WEST=3).
     * 旋转轴: 垂直 (Y) 轴, 朝向按 MC 坐标: (x, y, z) → (z, y, -x) 一次 (90° CCW, 俯视),
     *        和 Prefab 的 PositionOffset.getRelativePosition 保持一致 (NORTH→WEST, EAST→NORTH ...).
     * 旋转中心: **localPos 的 (0, 0, 0) 原点**, 让 local (0,0,0) 永远在 basePos,
     *          这样结构原地旋转不会从 cfg.pos 漂走. (旧版用 bbox 中心, 大型 litematic 旋转后
     *          整个结构会从 cfg.pos 偏移 100+ 格, 看起来"上下分开".)
     */
    public static void offsetStructureBlocks(com.prefab.structures.base.Structure structure, BlockPos basePos, net.minecraft.core.Direction houseFacing) {
        if (structure == null) return;
        java.util.Map<com.prefab.structures.base.BuildBlock, BlockPos> localMap = STRUCTURE_LOCAL_POS.get(structure);
        if (localMap == null) {
            PrefabCustomAddon.LOGGER.warn("[OFFSET] No local pos map for structure '{}', skipping offset", structure.getName());
            return;
        }
        int steps = facingToRotationSteps(houseFacing);

        // 计算 localPos 的实际范围 (仅用于日志/诊断, 不作为旋转中心)
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        boolean hasBlocks = false;
        for (BlockPos lp : localMap.values()) {
            if (lp == null) continue;
            if (lp.getX() < minX) minX = lp.getX();
            if (lp.getY() < minY) minY = lp.getY();
            if (lp.getZ() < minZ) minZ = lp.getZ();
            if (lp.getX() > maxX) maxX = lp.getX();
            if (lp.getY() > maxY) maxY = lp.getY();
            if (lp.getZ() > maxZ) maxZ = lp.getZ();
            hasBlocks = true;
        }
        // 关键: 旋转中心 = (0, 0, 0) (local 原点), 不是 bbox 中心.
        // 这样 local (0,0,0) 永远在 basePos, 旋转不会让 cfg.pos 漂走.
        final int centerX = 0;
        final int centerY = 0;
        final int centerZ = 0;

        int updated = 0;
        for (var bb : structure.getBlocks()) {
            BlockPos local = localMap.get(bb);
            if (local == null) continue;
            // 1) 平移到旋转中心 (这里 center 都是 0, 所以这步相当于直接用 local)
            int rx = local.getX() - centerX;
            int ry = local.getY() - centerY;
            int rz = local.getZ() - centerZ;
            // 2) 90° CCW 绕 Y 轴, 应用 steps 次. 公式: (x, z) -> (z, -x)
            //    这跟 Prefab 的 PositionOffset.getRelativePosition 行为一致:
            //      houseFacing=EAST: 北→西, 东→北, 南→东, 西→南
            //    验证: (0, 0, -1) 北 → (0, 0, -0) ??? 不对. 让我重算:
            //      (0, -1) [北] 用 (x,z)→(z,-x) 变 (z=-1, -x=0) = (-1, 0) ✓ 西
            //      (1,  0) [东] 用 (x,z)→(z,-x) 变 (0, -1) ✓ 北
            //      (0,  1) [南] 用 (x,z)→(z,-x) 变 (1, 0) ✓ 东
            //      (-1, 0) [西] 用 (x,z)→(z,-x) 变 (0, 1) ✓ 南
            for (int s = 0; s < steps; s++) {
                int newRx =  rz;
                int newRz = -rx;
                rx = newRx;
                rz = newRz;
            }
            // 3) 平移回 (center 都是 0, 这步相当于直接用 rotated), 再加 basePos
            //    关键: state 也要跟着转, 否则预览跟实际放置不匹配 (预览里楼梯朝向 A,
            //    实际放下去后楼梯朝向 B). 跟 AsyncBuildManager.setBlock 用同一个 BlockStateRotator
            //    保证两边公式一致.
            if (steps != 0 && bb.getBlockState() != null) {
                bb.setBlockState(BlockStateRotator.rotateY(bb.getBlockState(), steps));
            }
            bb.blockPos = new BlockPos(
                basePos.getX() + centerX + rx,
                basePos.getY() + centerY + ry,
                basePos.getZ() + centerZ + rz);
            updated++;
        }
        if (steps != 0) {
            PrefabCustomAddon.LOGGER.info("[OFFSET] Updated {} blocks: basePos={}, rotation={}({} steps), pivot=({},{},{}), local range X[{}..{}] Y[{}..{}] Z[{}..{}]",
                    updated, basePos, houseFacing, steps, centerX, centerY, centerZ,
                    hasBlocks ? minX : 0, hasBlocks ? maxX : 0,
                    hasBlocks ? minY : 0, hasBlocks ? maxY : 0,
                    hasBlocks ? minZ : 0, hasBlocks ? maxZ : 0);
        } else {
            PrefabCustomAddon.LOGGER.info("[OFFSET] Updated {} blocks: basePos={} (no rotation)", updated, basePos);
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
        for (int i = 0; i < paletteList.size(); i++) {
            paletteStates[i] = readBlockState(paletteList.getCompound(i));
        }

        // 3. 创建 Prefab Structure 实例（直接 new，CreateInstance 从 jar 资源读 JSON，不适合我们）
        com.prefab.structures.base.Structure structure = new com.prefab.structures.base.Structure();
        structure.setName(construction.getId());

        // 4. 解析 blocks 为 BuildBlock 列表
        List<com.prefab.structures.base.BuildBlock> buildBlocks = new ArrayList<>();
        java.util.Map<com.prefab.structures.base.BuildBlock, BlockPos> localPosMap = new java.util.HashMap<>();
        ListTag blockList = nbt.getList("blocks", 10);

        com.prefab.structures.config.StructureConfiguration config = new com.prefab.structures.config.StructureConfiguration();
        config.Initialize();

        int skippedAir = 0, skippedPos = 0, skippedIndex = 0, errorCount = 0, added = 0;
        // 调试: 记录前 3 个 block 的 NBT (一次性日志, 方便诊断 pos 格式问题)
        int debugLoggedBlocks = 0;
        // 调试: 记录 palette 前 3 项
        StringBuilder paletteSample = new StringBuilder();
        for (int pi = 0; pi < Math.min(3, paletteList.size()); pi++) {
            CompoundTag p = paletteList.getCompound(pi);
            paletteSample.append("[").append(pi).append("] ").append(p.toString()).append("; ");
        }
        PrefabCustomAddon.LOGGER.info("[parseToPrefabStructure] 开始解析: blocks={}, palette={}, 前 3 个 palette: {}",
                blockList.size(), paletteList.size(), paletteSample);
        for (int i = 0; i < blockList.size(); i++) {
            CompoundTag blockTag = blockList.getCompound(i);
            if (!blockTag.contains("pos") || !blockTag.contains("state")) { skippedPos++; continue; }

            // **关键**: 同时支持 ListTag[pos, Int, Int, Int] (1.21+ 标准) 和 IntArray[pos, 3 ints] (旧 mod 输出, 1.20- 兼容)
            int bx, by, bz;
            if (blockTag.contains("pos", Tag.TAG_LIST)) {
                ListTag posList = blockTag.getList("pos", 3);
                if (posList.size() != 3) { skippedPos++; continue; }
                bx = posList.getInt(0);
                by = posList.getInt(1);
                bz = posList.getInt(2);
            } else if (blockTag.contains("pos", Tag.TAG_INT_ARRAY)) {
                int[] posArr = blockTag.getIntArray("pos");
                if (posArr.length < 3) { skippedPos++; continue; }
                bx = posArr[0];
                by = posArr[1];
                bz = posArr[2];
            } else if (blockTag.contains("pos", Tag.TAG_COMPOUND)) {
                // 容错: 旧版用 Compound{x,y,z} 形式
                CompoundTag p = blockTag.getCompound("pos");
                bx = p.getInt("x");
                by = p.getInt("y");
                bz = p.getInt("z");
            } else {
                if (debugLoggedBlocks < 1) {
                    PrefabCustomAddon.LOGGER.warn("[parseToPrefabStructure] 第 1 个 block 的 pos 字段不是 ListTag/IntArray/Compound, 实际 type={}, 完整 blockTag={}",
                            blockTag.contains("pos") ? blockTag.get("pos").getId() : "missing", blockTag);
                }
                skippedPos++; continue;
            }
            // **关键**: 同时支持 1.21+ 标准 (state 是 CompoundTag{Name, Properties?})
            //       和 Litematica 风格 (state 是 int palette index) — OBJ 转换产物是前一种
            BlockState state = readBlockStateFromBlockTag(blockTag, paletteStates);
            if (state == null) { skippedIndex++; continue; }
            if (state.isAir()) { skippedAir++; continue; }

            // 调试: 前 3 个 block 完整日志
            if (debugLoggedBlocks < 3) {
                debugLoggedBlocks++;
                PrefabCustomAddon.LOGGER.info("[parseToPrefabStructure] block #{} OK: pos=({},{},{}), state={}",
                        i, bx, by, bz, state);
            }

            // 4.1 创建一个新的 BuildBlock 并设置资源位置
            com.prefab.structures.base.BuildBlock bb = new com.prefab.structures.base.BuildBlock();
            bb.Initialize();
            // setBlockDomain/setBlockName (私有字段 via setter)
            bb.setBlockDomain(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace());
            bb.setBlockName(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());

            // 4.1.1 关键: 填充 properties, 否则 BuildBlock.getProperty("facing" / "east" / "axis" 等) 返回 null
            // prefab 的 GetBlockRenderShape() 等渲染代码会查 "facing", "east", "axis" 等 key,
            // 见 BuildBlock.java:235, 265-268, 305-308, 344, 373-374
            // 没有 BuildProperty → NullPointerException
            java.util.ArrayList<com.prefab.structures.base.BuildProperty> propList = new java.util.ArrayList<>();
            for (var propEntry : state.getValues().entrySet()) {
                var prop = propEntry.getKey();
                var val = propEntry.getValue();
                com.prefab.structures.base.BuildProperty bp = new com.prefab.structures.base.BuildProperty();
                bp.setName(prop.getName());
                bp.setValue(val.toString());  // 枚举类型 toString() 返回 "north" 等
                propList.add(bp);
            }
            bb.setProperties(propList);

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
            // 我们设 bb.blockPos = localPos (0..sizeXYZ), 同时把 localPos 记到 STRUCTURE_LOCAL_POS,
            // 让 CustomStructureGui 在 setStructure 之前调 offsetStructureBlocks() 转成 worldPos.
            bb.blockPos = currentPos;
            localPosMap.put(bb, currentPos);

            buildBlocks.add(bb);
            added++;
        }
        PrefabCustomAddon.LOGGER.info("parseToPrefabStructure: {} added={}, skippedAir={}, skippedPos={}, skippedIndex={}, errors={}",
                construction.getId(), added, skippedAir, skippedPos, skippedIndex, errorCount);

        structure.setBlocks((ArrayList) buildBlocks);
        // 关键: 把 localPos 记到全局表, 供预览时 offsetStructureBlocks() 用
        STRUCTURE_LOCAL_POS.put(structure, localPosMap);

        // 5. 设置 clear space
        com.prefab.structures.base.BuildClear clearSpace = new com.prefab.structures.base.BuildClear();
        if (nbt.contains("size")) {
            int w = 0, h = 0, l = 0;
            if (nbt.contains("size", Tag.TAG_LIST)) {
                net.minecraft.nbt.ListTag sizeList = nbt.getList("size", 3);
                if (sizeList.size() == 3) {
                    w = sizeList.getInt(0);
                    h = sizeList.getInt(1);
                    l = sizeList.getInt(2);
                }
            } else if (nbt.contains("size", Tag.TAG_INT_ARRAY)) {
                int[] sizeArr = nbt.getIntArray("size");
                if (sizeArr.length >= 3) {
                    w = sizeArr[0];
                    h = sizeArr[1];
                    l = sizeArr[2];
                }
            } else if (nbt.contains("size", Tag.TAG_COMPOUND)) {
                net.minecraft.nbt.CompoundTag sc = nbt.getCompound("size");
                w = sc.getInt("x");
                h = sc.getInt("y");
                l = sc.getInt("z");
            }
            if (w > 0 && h > 0 && l > 0) {
                com.prefab.structures.base.BuildShape shape = clearSpace.getShape();
                if (shape != null) {
                    shape.setWidth(w);
                    shape.setHeight(h);
                    shape.setLength(l);
                    shape.setDirection(net.minecraft.core.Direction.NORTH);
                    PrefabCustomAddon.LOGGER.info("[parseToPrefabStructure] clearSpace set: {}x{}x{}", w, h, l);
                }
            }
        }
        structure.setClearSpace(clearSpace);

        return structure;
    }
}
