package com.prefab.addon.cloud;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.structure.CustomStructureBuilder;
import com.prefab.structures.base.BuildBlock;
import com.prefab.structures.base.BuildClear;
import com.prefab.structures.base.BuildProperty;
import com.prefab.structures.base.BuildShape;
import com.prefab.structures.base.PositionOffset;
import com.prefab.structures.base.Structure;
import com.prefab.structures.config.StructureConfiguration;
import com.prefab.structures.render.StructureRenderHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 云端建筑的世界内预览: 把 {@link CloudBuilding} 转成 prefab 的 {@link Structure},
 * 复用 {@code CustomStructureGui} 的 {@code ADDON_PREVIEW_STRUCTURE} / {@code ADDON_PREVIEW_CONFIG}
 * 通道渲染 (跟"右键自定义蓝图 → 预览"是同一条路径).
 *
 * <p>区别在于: {@link #CURRENT_CLOUD_BUILDING_ID} 被设上, 让 {@code StructurePreviewKeyHandler}
 * 在 ALT 时发 {@link CloudBuildingSummonPayload} 而不是 {@code BuildCustomStructurePayload}.
 * 右键取消也走 {@link #cancel()}.</p>
 *
 * <p>云端建筑里方块已经是"放置后"的状态 (lx/ly/lz 是相对 placedAt 的坐标, state 是 setBlock 用的
 * BlockState, 已经按当时 facing 旋转过), 所以预览时不需要再旋转 state 一次.
 * KeyHandler 旋转预览时, {@link CustomStructureBuilder#offsetStructureBlocks} 会再次旋转 state 跟 blockPos,
 * 保证 ALT 放出后跟玩家在世界里看到的一致.</p>
 */
public final class CloudPreview {

    private CloudPreview() {}

    /** 当前正在预览的云端建筑 ID. null = 没有云端预览 (是普通自定义建筑预览). */
    private static volatile String CURRENT_CLOUD_BUILDING_ID = null;

    public static String getCurrentCloudBuildingId() {
        return CURRENT_CLOUD_BUILDING_ID;
    }

    public static boolean isActive() {
        return CURRENT_CLOUD_BUILDING_ID != null;
    }

    /**
     * 开启云端建筑的世界预览. 关闭当前 GUI, 玩家在世界里用方向键/CTRL/ALT 操作.
     * 右键会调用 {@link #cancel()} 清掉预览状态.
     *
     * @param buildingId CloudBuilding.id
     * @return true 成功, false 失败 (建筑不存在/数据损坏).
     */
    public static boolean start(String buildingId) {
        if (buildingId == null || buildingId.isEmpty()) return false;
        CloudBuilding b = CloudBuildingClientCache.getInstance().getById(buildingId);
        if (b == null) {
            PrefabCustomAddon.LOGGER.warn("[CLOUD-PREVIEW] 找不到云端建筑 id={}", buildingId);
            return false;
        }
        if (b.placed) {
            PrefabCustomAddon.LOGGER.warn("[CLOUD-PREVIEW] 建筑已放出, 不能预览 id={}", buildingId);
            return false;
        }

        Structure structure = buildStructureFromCloud(b);
        if (structure == null || structure.getBlocks() == null || structure.getBlocks().isEmpty()) {
            PrefabCustomAddon.LOGGER.warn("[CLOUD-PREVIEW] 转换失败, blocks 为空 id={}", buildingId);
            return false;
        }

        // 初始位置: 玩家头顶 1 格, 朝向 = 建筑原朝向 (跟玩家在世界里看到的样子一致)
        Minecraft mc = Minecraft.getInstance();
        BlockPos origin = (mc.player != null)
            ? mc.player.blockPosition().offset(0, 1, 0)
            : BlockPos.ZERO;
        // 关键: 快照存的是原始 (未旋转) pos + state, 所以预览初始朝向必须用 b.facing,
        //   这样 offsetStructureBlocks 第一次旋转时 = 建筑建出来的姿态, 跟玩家记忆中一致.
        //   玩家可以按 CTRL 在预览里旋转, 那是另外的事.
        Direction houseFacing = b.facing != null ? b.facing : Direction.SOUTH;

        StructureConfiguration cfg = new StructureConfiguration();
        cfg.Initialize();
        cfg.pos = origin;
        cfg.houseFacing = houseFacing;

        // 把每个 BuildBlock.blockPos 从 localPos 转换为 worldPos (用我们自己的 offset,
        // 跟 CustomStructureGui.handlePreviewButtonClick 一致)
        CustomStructureBuilder.offsetStructureBlocks(structure, cfg.pos, cfg.houseFacing);

        // === 跟 CustomStructureGui.handlePreviewButtonClick 完全一致的 setup 流程 ===
        // 1) 存到我们 own 的静态字段
        com.prefab.addon.client.gui.CustomStructureGui.setAddonPreviewStructure(structure);
        com.prefab.addon.client.gui.CustomStructureGui.setAddonPreviewConfig(cfg);
        com.prefab.addon.client.gui.CustomStructureGui.markAddonPreviewActive();

        // 2) 调 prefab 的 setStructure 重建 chunk cache, 立即 setStructure(null, null) 清掉
        //    (CustomStructurePreviewRenderer 读的是我们 own 的字段, 不依赖 prefab 的)
        StructureRenderHandler.setStructure(structure, cfg);
        StructureRenderHandler.setStructure(null, null);

        // 3) 标记这是云端预览, KeyHandler ALT 时改走 cloud_summon packet
        CURRENT_CLOUD_BUILDING_ID = buildingId;

        PrefabCustomAddon.LOGGER.info("[CLOUD-PREVIEW] 开启云端预览 id={} name={} pos={} facing={} blocks={}",
            buildingId, b.name, origin, houseFacing, structure.getBlocks().size());

        // 4) 关闭 GUI
        mc.setScreen(null);
        return true;
    }

    /** 取消云端预览, 清掉所有相关状态. KeyHandler 右键时会调这个. */
    public static void cancel() {
        if (CURRENT_CLOUD_BUILDING_ID == null
            && com.prefab.addon.client.gui.CustomStructureGui.getAddonPreviewStructure() == null) {
            return;
        }
        PrefabCustomAddon.LOGGER.info("[CLOUD-PREVIEW] 取消预览 id={}", CURRENT_CLOUD_BUILDING_ID);
        CURRENT_CLOUD_BUILDING_ID = null;
        com.prefab.addon.client.gui.CustomStructureGui.clearAddonPreviewFlag();
        StructureRenderHandler.setStructure(null, null);
    }

    // ============================================================
    // CloudBuilding → Prefab Structure 转换
    // ============================================================

    /**
     * 把 CloudBuilding 的方块快照转成 prefab 的 Structure 实例, 跟
     * {@code CustomStructureBuilder.parseToPrefabStructure} 的产物结构一致 (同样的
     * BuildProperty / PositionOffset / blockState / blockPos 字段填充), 这样
     * {@link CustomStructurePreviewRenderer} 不用改任何东西就能直接渲染.
     */
    public static Structure buildStructureFromCloud(CloudBuilding b) {
        Structure structure = new Structure();
        structure.setName("cloud_" + (b.name == null ? b.id : b.name));

        ArrayList<BuildBlock> buildBlocks = new ArrayList<>(b.blocks.size());
        // 关键: STRUCTURE_LOCAL_POS 是 CustomStructureBuilder 内部用的 map, KeyHandler 旋转/移动时
        // 读它把每个 bb.blockPos 重新算到 worldPos. 我们这里也填一份, 跟 parseToPrefabStructure
        // 行为一致.
        IdentityHashMap<BuildBlock, BlockPos> localPosMap = new IdentityHashMap<>();
        int added = 0, skippedAir = 0;
        for (CloudBuilding.BlockSnapshot bs : b.blocks) {
            BlockState state = bs.getState();
            if (state == null || state.isAir()) { skippedAir++; continue; }

            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if (id == null) continue;

            BuildBlock bb = new BuildBlock();
            bb.Initialize();
            bb.setBlockDomain(id.getNamespace());
            bb.setBlockName(id.getPath());

            // properties: prefab 的渲染 / 放置代码会查 "facing"/"axis"/"east" 等 key,
            // 不填会导致 NPE. 跟 parseToPrefabStructure 行为一致.
            ArrayList<BuildProperty> propList = new ArrayList<>();
            for (Map.Entry<Property<?>, Comparable<?>> e : state.getValues().entrySet()) {
                BuildProperty bp = new BuildProperty();
                bp.setName(e.getKey().getName());
                bp.setValue(e.getValue().toString());
                propList.add(bp);
            }
            bb.setProperties(propList);

            // 起始位置: 用 prefab 自带的 getStartingPositionFromOriginalAndCurrentPosition,
            // 跟 parseToPrefabStructure 一致 (relative offset, basePos 由 cfg 决定)
            BlockPos local = new BlockPos(bs.lx, bs.ly, bs.lz);
            PositionOffset offset = Structure.getStartingPositionFromOriginalAndCurrentPosition(
                local, BlockPos.ZERO);
            bb.setStartingPosition(offset);

            bb.setBlockState(state);
            bb.setBlockStateData("");

            // blockPos: 初始 = local (后面 offsetStructureBlocks 改成 worldPos)
            bb.blockPos = local;
            localPosMap.put(bb, local);

            buildBlocks.add(bb);
            added++;
        }
        structure.setBlocks(buildBlocks);
        // 必须 put 到 STRUCTURE_LOCAL_POS, 否则 offsetStructureBlocks 会 warn 后直接 return,
        // KeyHandler 移动/旋转时 blockPos 不会更新, 预览永远停在初始 localPos.
        CustomStructureBuilder.putLocalPosMap(structure, localPosMap);

        // clearSpace: 用 b.sizeX/Y/Z (收回/放出时存好的包围盒), 没存就用 blocks 算一个
        BuildClear clearSpace = new BuildClear();
        BuildShape shape = clearSpace.getShape();
        if (shape != null) {
            int w, h, l;
            if (b.sizeX > 0 && b.sizeY > 0 && b.sizeZ > 0) {
                w = b.sizeX; h = b.sizeY; l = b.sizeZ;
            } else {
                int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                for (CloudBuilding.BlockSnapshot bs : b.blocks) {
                    if (bs.lx < minX) minX = bs.lx;
                    if (bs.ly < minY) minY = bs.ly;
                    if (bs.lz < minZ) minZ = bs.lz;
                    if (bs.lx > maxX) maxX = bs.lx;
                    if (bs.ly > maxY) maxY = bs.ly;
                    if (bs.lz > maxZ) maxZ = bs.lz;
                }
                w = maxX - minX + 1;
                h = maxY - minY + 1;
                l = maxZ - minZ + 1;
            }
            shape.setWidth(w);
            shape.setHeight(h);
            shape.setLength(l);
            shape.setDirection(Direction.NORTH);
        }
        structure.setClearSpace(clearSpace);

        PrefabCustomAddon.LOGGER.info("[CLOUD-PREVIEW] 转换 CloudBuilding '{}' → Structure: blocks added={} skippedAir={}",
            b.name, added, skippedAir);
        return structure;
    }
}
