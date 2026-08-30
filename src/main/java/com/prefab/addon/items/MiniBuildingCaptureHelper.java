package com.prefab.addon.items;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.blocks.MiniBuildingStorage;
import com.prefab.addon.blocks.PrefabBlockEntities;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * 迷你建筑捕获工具: 扫描一个 AABB 区域, 写到外部文件 + ItemStack 只存引用.
 *
 * <p><b>为什么改外部存储</b>: Minecraft 1.21.1 客户端的
 * {@link net.minecraft.nbt.NbtAccounter} 限制 2MB. 玩家捕获 48x96x59 (271k 块)
 * 这种大建筑, 完整 NBT 约 2MB, 服务端发 container_set_slot 同步 ItemStack 时
 * 客户端会断开连接. 把完整 NBT 写文件, ItemStack 只存引用 (< 1KB) 绕过这个限制.
 *
 * <p><b>新数据流</b>:
 * <ol>
 *   <li>扫描区域 → 生成 {@code data} (含 width/height/depth/blocks 等)</li>
 *   <li>分配 UUID, 把完整 NBT 写到 {@code <world>/data/prefab_custom_addon/mini_buildings/<uuid>.nbt}</li>
 *   <li>ItemStack 只存引用 NBT (UUID + 名字 + 尺寸 + 块数 + 作者), &lt; 1KB</li>
 *   <li>{@link BlockItem#setBlockEntityData} 写引用 NBT, 玩家放方块时 BE 收到引用 NBT</li>
 *   <li>BE 第一次 {@code rebuildCache} 时按 UUID 读文件, 得到完整数据</li>
 * </ol>
 *
 * <p><b>关键决策</b>:
 * <ul>
 *   <li>跳过基岩/屏障/命令方块 (玩家不该捕获这些)</li>
 *   <li>跳过空气 (节省空间)</li>
 *   <li>每方块用 NbtUtils.writeBlockState 序列化 (跨版本兼容)</li>
 *   <li>体积上限 500000 块 (服务端扫描限制, 不再受 2MB 限制)</li>
 *   <li>NBT 体积无客户端限制 (数据在文件里, ItemStack 只存引用)</li>
 * </ul>
 */
public class MiniBuildingCaptureHelper {

    /**
     * 体积上限 500000 块.
     * <p>为什么是 500k 不是 128³: 玩家真实场景经常需要 64×64×64 (262k) 或 50×100×50 (250k)
     * 这种"中等城市区块". 设到 500k 能覆盖 95% 真实建筑, 又不致于让 NBT 体积爆掉.
     * 128³ = 2,097,152 是理论上限, 但 1M+ 块的 NBT 写盘会卡 5-10 秒, 玩家受不了.</p>
     */
    public static final int MAX_BLOCKS = 500_000;

    /** NBT 体积软上限 (字节). 超过给清晰错误, 不让服务端 OOM. 500k 块 ≈ 15-20MB. */
    public static final int MAX_NBT_BYTES = 32 * 1024 * 1024;  // 32 MB

    public static ItemStack capture(Level level, AABB box, Player player) {
        if (level == null || box == null || player == null) return null;

        int x1 = (int) Math.floor(box.minX);
        int y1 = (int) Math.floor(box.minY);
        int z1 = (int) Math.floor(box.minZ);
        int x2 = (int) Math.floor(box.maxX);  // exclusive
        int y2 = (int) Math.floor(box.maxY);
        int z2 = (int) Math.floor(box.maxZ);

        int width = x2 - x1;
        int height = y2 - y1;
        int depth = z2 - z1;

        if (width <= 0 || height <= 0 || depth <= 0) {
            PrefabCustomAddon.LOGGER.warn("[MINI_BUILDING] 选区无效: {}x{}x{}", width, height, depth);
            return null;
        }

        long total = (long) width * height * depth;
        if (total > MAX_BLOCKS) {
            PrefabCustomAddon.LOGGER.warn("[MINI_BUILDING] 选区过大: {}x{}x{} = {} > {}",
                width, height, depth, total, MAX_BLOCKS);
            return null;
        }

        List<CompoundTag> blockTags = new ArrayList<>();
        int airSkipped = 0;
        int forbiddenSkipped = 0;

        for (int x = x1; x < x2; x++) {
            for (int y = y1; y < y2; y++) {
                for (int z = z1; z < z2; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);

                    if (state.isAir()) {
                        airSkipped++;
                        continue;
                    }

                    // 跳过危险方块
                    Block block = state.getBlock();
                    if (block == Blocks.BEDROCK || block == Blocks.BARRIER
                        || block == Blocks.COMMAND_BLOCK || block == Blocks.CHAIN_COMMAND_BLOCK
                        || block == Blocks.REPEATING_COMMAND_BLOCK
                        || block == Blocks.STRUCTURE_BLOCK
                        || block == Blocks.JIGSAW) {
                        forbiddenSkipped++;
                        continue;
                    }

                    // 序列化方块状态
                    CompoundTag stateTag = NbtUtils.writeBlockState(state);
                    CompoundTag blockEntry = new CompoundTag();
                    blockEntry.putInt("x", pos.getX() - x1);
                    blockEntry.putInt("y", pos.getY() - y1);
                    blockEntry.putInt("z", pos.getZ() - z1);
                    blockEntry.put("state", stateTag);
                    blockTags.add(blockEntry);
                }
            }
        }

        // 1) 准备建筑数据
        String buildingId = MiniBuildingStorage.newId();
        CompoundTag data = new CompoundTag();
        data.putString("id", buildingId);
        data.putString("author", player.getName().getString());
        data.putLong("created", System.currentTimeMillis());
        data.putInt("width", width);
        data.putInt("height", height);
        data.putInt("depth", depth);
        data.putInt("block_count", blockTags.size());

        ListTag blocksList = new ListTag();
        blocksList.addAll(blockTags);
        data.put("blocks", blocksList);

        // 2) NBT 体积软检查 (服务端扫描时, 防止 OOM / 卡顿)
        try {
            int estimatedBytes = data.toString().length() * 2;
            if (estimatedBytes > MAX_NBT_BYTES) {
                PrefabCustomAddon.LOGGER.warn(
                    "[MINI_BUILDING] NBT 过大: 区域 {}x{}x{}, {} 块, 估算 ~{} bytes > {} bytes",
                    width, height, depth, blockTags.size(), estimatedBytes, MAX_NBT_BYTES);
                if (player != null) {
                    player.sendSystemMessage(Component.literal(
                        "§c[迷你建筑转换器] §7捕获失败: 建筑数据过大 (~"
                            + (estimatedBytes / 1024) + " KB), 试着选更小的区域"
                    ).withStyle(ChatFormatting.RED));
                }
                return null;
            }
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.debug("[MINI_BUILDING] NBT size estimate failed: {}", e.getMessage());
        }

        // 3) 写完整 NBT 到世界目录文件 (无 2MB 限制)
        boolean saved = MiniBuildingStorage.save(level, buildingId, data);
        if (!saved) {
            if (player != null) {
                player.sendSystemMessage(Component.literal(
                    "§c[迷你建筑转换器] §7捕获失败: 写入外部存储失败, 请检查日志"
                ).withStyle(ChatFormatting.RED));
            }
            return null;
        }

        // 4) ItemStack 只存引用 (< 1KB, 避开 2MB 客户端限制)
        CompoundTag ref = new CompoundTag();
        ref.putString(MiniBuildingStorage.KEY_REF_ID, buildingId);
        ref.putString(MiniBuildingStorage.KEY_REF_NAME, "");  // 预留名字
        ref.putLong(MiniBuildingStorage.KEY_REF_CREATED, System.currentTimeMillis());
        ref.putString(MiniBuildingStorage.KEY_REF_AUTHOR, player.getName().getString());
        ref.putInt(MiniBuildingStorage.KEY_REF_WIDTH, width);
        ref.putInt(MiniBuildingStorage.KEY_REF_HEIGHT, height);
        ref.putInt(MiniBuildingStorage.KEY_REF_DEPTH, depth);
        ref.putInt(MiniBuildingStorage.KEY_REF_BLOCK_COUNT, blockTags.size());
        // [DEBUG] 详细日志: 看 ref 内容
        PrefabCustomAddon.LOGGER.info(
            "[MINI_BUILDING] [DEBUG] helper: ref NBT created, blockTags.size()={}, refBlockCount={}, refId={}",
            blockTags.size(), blockTags.size(), buildingId);

        // 5) 创建 ItemStack, 写引用 NBT 到 BLOCK_ENTITY_DATA
        //    BE NBT 结构 (顶层必须有 id 字段, 否则 ItemStack.save() 反序列化会爆 "Missing id for entity"):
        //      {
        //        "id": "prefab_custom_addon:mini_building",  ← 必须! vanilla 把它当 entity NBT 解析
        //        "MiniBuilding": ref
        //      }
        //    注: vanilla BlockItem.setBlockEntityData 内部会加 id 字段, 但 1.21.1
        //    某些场景下表现不稳, 这里直接构造, 行为最可控.
        CompoundTag beRef = new CompoundTag();
        // 1) 加 id 字段 (ResourceLocation 格式: "namespace:path")
        beRef.putString("id",
            net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE
                .getKey(PrefabBlockEntities.MINI_BUILDING_BE.get()).toString());
        // 2) 加 MiniBuilding ref
        beRef.put(MiniBuildingStorage.KEY_FULL_DATA, ref);

        ItemStack miniBlock = new ItemStack(PrefabCustomAddon.MINI_BUILDING_BLOCK_ITEM.get());
        miniBlock.set(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA,
            net.minecraft.world.item.component.CustomData.of(beRef));

        // 调试: 立刻读回确认写入成功
        var verify = miniBlock.get(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA);
        if (verify == null) {
            PrefabCustomAddon.LOGGER.error(
                "[MINI_BUILDING] [DEBUG] CRITICAL: BLOCK_ENTITY_DATA not set after set! tag={}",
                beRef);
        } else {
            PrefabCustomAddon.LOGGER.info(
                "[MINI_BUILDING] [DEBUG] helper wrote BE NBT (verify pass): {}",
                verify.copyTag());
        }

        PrefabCustomAddon.LOGGER.info(
            "[MINI_BUILDING] 捕获: {}x{}x{} 区域, {} 方块 (跳过 {} 空气, {} 危险), id={}",
            width, height, depth, blockTags.size(), airSkipped, forbiddenSkipped, buildingId);

        return miniBlock;
    }
}
