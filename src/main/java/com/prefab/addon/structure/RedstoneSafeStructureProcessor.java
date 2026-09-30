package com.prefab.addon.structure;

import com.mojang.serialization.Codec;
import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * 自定义 {@link StructureProcessor} - 解决 1.21.1 建造时红石/拉杆等组件被破坏变成掉落物的问题.
 *
 * <p>参考 Create mod 的 {@code com.simibubi.create.content.schematics.SchematicProcessor}
 * (Create-mc1.21.1-dev:24-44), 在 {@code placeInWorld} 放置前对每个 BlockEntity 的 NBT
 * 做处理, 去除会导致"看起来正常但实际状态错误"的字段:</p>
 *
 * <ul>
 *   <li><b>power</b>: 红石粉的信号强度 (应从周围邻居重新计算, 而不是从 NBT 恢复)</li>
 *   <li><b>signal</b>: 红石中继器的输出强度</li>
 *   <li><b>level</b>: 红石比较器的输出强度 / 漏斗液位等</li>
 *   <li><b>lit</b>: 红石火把/熔炉等被点燃状态 (虽然通常在 block state 里, 但有些 mod 放 NBT)</li>
 *   <li><b>enabled</b>: 漏斗/发射器/投掷器等使能状态</li>
 *   <li><b>Items</b>: 容器里的物品 (防复制, 同时避免放置时物品瞬间流失)</li>
 *   <li><b>Inventory</b>: 同上</li>
 *   <li><b>CustomName</b>: 容器名字 (避免误覆盖玩家原版容器名)</li>
 *   <li><b>command</b>: 命令方块的命令 (安全)</li>
 *   <li><b>Book</b>: 讲台的书 (避免放置后玩家操作不了)</li>
 *   <li><b>Record</b>: 唱片的唱片</li>
 * </ul>
 *
 * <p>关键 insight: 即使使用 placeInWorld (Minecraft 内置), 内部对每个方块会
 * 调用 block.onPlace, 红石/拉杆/红石粉等组件的 onPlace 会检查周围方块.
 * 异步分批放置时, 周围方块可能还没就绪, 这些组件会被判定为"失去支撑"
 * 立即 break 成掉落物. NBT 里残留的 power/signal/level 会让组件处于错误状态,
 * 加重此问题. 所以这里统一把状态字段剥掉, 让组件在放置后由 placeInWorld 完成后
 * 触发的邻居更新重算.</p>
 */
public class RedstoneSafeStructureProcessor extends StructureProcessor {
    public static final RedstoneSafeStructureProcessor INSTANCE = new RedstoneSafeStructureProcessor();
    public static final Codec<RedstoneSafeStructureProcessor> CODEC = Codec.unit(() -> INSTANCE);

    public static final java.util.Set<String> STRIPPED_FIELDS_SET = java.util.Set.of(
        // 红石状态字段 (重算)
        "power", "signal", "level", "lit", "enabled",
        // 容器字段 (安全 + 防复制)
        "Items", "Inventory", "LootTable", "LootTableSeed",
        // 危险 / 自定义字段
        "CustomName", "command", "Book", "Record",
        // 漏斗冷却 / 红石火把熄灭倒计时 (NBT 残留会让逻辑错乱)
        "TransferCooldown", "CooldownTime"
    );

    private static final java.util.Set<String> STRIPPED_FIELDS = STRIPPED_FIELDS_SET;

    private static int strippedCount = 0;

    private RedstoneSafeStructureProcessor() {}

    public static int getStrippedCount() {
        return strippedCount;
    }

    public static void resetStrippedCount() {
        strippedCount = 0;
    }

    @Nullable
    @Override
    public StructureTemplate.StructureBlockInfo process(
            LevelReader world, BlockPos pos, BlockPos anotherPos,
            StructureTemplate.StructureBlockInfo rawInfo,
            StructureTemplate.StructureBlockInfo info,
            StructurePlaceSettings settings, @Nullable StructureTemplate template) {

        if (info.nbt() == null) return info;
        if (!info.state().hasBlockEntity()) return info;

        CompoundTag nbt = info.nbt();
        boolean changed = false;
        int beforeSize = nbt.size();
        java.util.List<String> toRemove = new java.util.ArrayList<>();

        for (String key : nbt.getAllKeys()) {
            if (STRIPPED_FIELDS.contains(key)) {
                toRemove.add(key);
            }
        }
        if (!toRemove.isEmpty()) {
            for (String key : toRemove) {
                nbt.remove(key);
                changed = true;
            }
            strippedCount += toRemove.size();
            if (PrefabCustomAddon.LOGGER.isDebugEnabled()) {
                PrefabCustomAddon.LOGGER.debug("[REDSTONE-SAFE] Stripped {} fields from {}: {}",
                    toRemove.size(), info.state().getBlock().getDescriptionId(), toRemove);
            }
        }

        // 安全: 始终返回新 StructureBlockInfo, 让 placeInWorld 走我们的 NBT
        if (changed) {
            return new StructureTemplate.StructureBlockInfo(info.pos(), info.state(), nbt);
        }
        return info;
    }

    @Nullable
    @Override
    protected StructureProcessorType<?> getType() {
        // 注册一个 dummy processor type. 因为我们只用 singleton + 单线程,
        // 这里返回 null 即可 (StructureTemplate 处理 null 情况).
        return null;
    }
}
