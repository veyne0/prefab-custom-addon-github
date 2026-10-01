package com.prefab.addon.client;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.structures.config.StructureConfiguration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 编辑原理图 (Edit Schematic) 模式控制器 — Litematica 风格.
 *
 * <p><b>架构</b>：完全脱离 prefab 的 BuildBlock / PositionOffset 体系 (那套链路太脆弱,
 * 字段缺失就静默不显示, 没有报错). 用两个独立 Map 管理数据:</p>
 * <ul>
 *   <li>{@code worldBlocks}: world pos → BlockState — 渲染 + raycast 唯一数据源</li>
 *   <li>{@code localBlocks}: local pos → BlockState — NBT 读写唯一数据源</li>
 * </ul>
 *
 * <p>两个 map 始终同步, place/delete/undo 时一起改. enterEditMode 时从 Structure + NBT
 * 一次提取, saveAndExit 时从 localBlocks 序列化 NBT 写回文件.</p>
 *
 * <p>渲染走 {@link CustomStructurePreviewRenderer} 的 edit-mode 分支, 直接遍历
 * {@link #getWorldBlocks()} 用 BakedModel 画方块, 跳过 BuildBlock.SetBlockState 链路.</p>
 *
 * <p>快捷键 (均为 KeyMapping, 可在按键绑定改键): 左键删除 | 右键放置主手方块 | CTRL 撤销 | ALT 保存 | ESC 退出.</p>
 */
@EventBusSubscriber(modid = PrefabCustomAddon.MOD_ID, value = Dist.CLIENT)
public class EditModeController {

    private static final double MAX_RAYCAST_DIST = 6.0;
    private static final int MAX_UNDO = 200;

    // === 核心数据 ===
    private static boolean editing = false;
    private static StructureConfiguration config = null;
    private static byte[] nbtBytes = null;
    private static Path localNbtPath = null;

    /** world pos → BlockState. 渲染 + raycast 唯一数据源. */
    private static final Map<BlockPos, BlockState> worldBlocks = new LinkedHashMap<>();
    /** local pos → BlockState. NBT 读写唯一数据源. */
    private static final Map<BlockPos, BlockState> localBlocks = new LinkedHashMap<>();

    // === 交互状态 ===
    /** 玩家瞄的现有方块 (delete target). null = 没瞄现有方块. */
    private static BlockPos hoveredBlock = null;
    /** 玩家瞄的空气格 (place target). null = 没瞄空气. */
    private static BlockPos hoveredAir = null;

    // === Undo ===
    private static final Deque<ActionRecord> undoStack = new ArrayDeque<>();

    /** 保存成功后的回调 (编辑器设置: 把 ConstructionInfo 缓存的 nbtData 置 null, 否则建造仍用旧字节 → "改了没生效"). */
    private static Runnable onSavedCallback = null;

    /** 编辑器进入编辑前设置回调; saveAndExit 成功写文件后执行一次. */
    public static void setOnSavedCallback(Runnable r) { onSavedCallback = r; }

    // === 键位上一帧状态 (边沿检测用) ===
    private static boolean lastLeftDown = false;
    private static boolean lastRightDown = false;
    private static boolean lastCtrlDown = false;
    private static boolean lastAltDown = false;
    private static boolean lastEscDown = false;

    private EditModeController() {}

    public static boolean isEditing() { return editing; }
    public static BlockPos getHoveredPos() { return hoveredBlock != null ? hoveredBlock : hoveredAir; }
    public static BlockPos getHoveredBlockForRender() { return hoveredBlock; }
    public static BlockPos getHoveredAirForRender() { return hoveredAir; }
    public static StructureConfiguration getEditedConfig() { return config; }
    public static boolean shouldBlockPreviewCancel() { return editing; }

    /** 给 CustomStructurePreviewRenderer 用的 world blocks 视图 (immutable). */
    public static Map<BlockPos, BlockState> getWorldBlocks() {
        return Collections.unmodifiableMap(worldBlocks);
    }

    /** 单条 undo 记录. placedPos 是 world pos (渲染坐标系), placedState 是该位置的 state. */
    private record ActionRecord(boolean wasDelete, BlockPos placedPos, BlockState placedState) {}

    /**
     * 进入编辑模式.
     *
     * @param worldBlocksInitial 建筑在 world 坐标系的方块 map (从 Structure 提取)
     * @param localBlocksInitial 建筑在 local 坐标系的方块 map (从 NBT 提取)
     * @param editedConfig       预览配置 (提供 houseFacing / pos)
     * @param nbt                原始 NBT byte[] (玩家改的就是这个, ALT 时写回)
     * @param nbtPath            原 NBT 文件路径
     */
    public static void enterEditMode(Map<BlockPos, BlockState> worldBlocksInitial,
                                     Map<BlockPos, BlockState> localBlocksInitial,
                                     StructureConfiguration editedConfig,
                                     byte[] nbt, Path nbtPath) {
        if (editing) {
            PrefabCustomAddon.LOGGER.warn("[EDIT-MODE] re-entering, force reset");
            exitEditMode();
        }
        if (worldBlocksInitial == null || localBlocksInitial == null || editedConfig == null || nbt == null) {
            PrefabCustomAddon.LOGGER.error("[EDIT-MODE] enterEditMode: required arg is null (world={} local={} cfg={} nbt={})",
                worldBlocksInitial, localBlocksInitial, editedConfig, nbt);
            return;
        }
        worldBlocks.clear();
        worldBlocks.putAll(worldBlocksInitial);
        localBlocks.clear();
        localBlocks.putAll(localBlocksInitial);
        config = editedConfig;
        nbtBytes = nbt;
        localNbtPath = nbtPath;
        hoveredBlock = null;
        hoveredAir = null;
        undoStack.clear();
        lastLeftDown = lastRightDown = lastCtrlDown = lastAltDown = lastEscDown = false;
        PrefabCustomAddon.LOGGER.info("[EDIT-MODE] entered: worldBlocks={} localBlocks={} cfg.pos={} facing={}",
            worldBlocks.size(), localBlocks.size(), config.pos, config.houseFacing);
        editing = true;
    }

    /** 退出编辑模式, 不保存. */
    public static void exitEditMode() {
        if (!editing) return;
        PrefabCustomAddon.LOGGER.info("[EDIT-MODE] exited (discarded, worldBlocks={} undoStack={})",
            worldBlocks.size(), undoStack.size());
        editing = false;
        config = null;
        nbtBytes = null;
        localNbtPath = null;
        onSavedCallback = null;
        worldBlocks.clear();
        localBlocks.clear();
        hoveredBlock = null;
        hoveredAir = null;
        undoStack.clear();
        lastLeftDown = lastRightDown = lastCtrlDown = lastAltDown = lastEscDown = false;
        try { com.prefab.addon.client.gui.CustomStructureGui.clearAddonPreviewFlag(); } catch (Exception ignored) {}
    }

    /** ALT 保存: 从 localBlocks 序列化 NBT 写回文件, 退出. */
    public static void saveAndExit() {
        if (!editing) return;
        if (localNbtPath == null) {
            PrefabCustomAddon.LOGGER.error("[EDIT-MODE] save failed: no path");
            sendPlayerMessage("§c保存失败: 没有本地路径");
            exitEditMode();
            return;
        }
        try {
            CompoundTag newTag = buildNbtFromLocalBlocks();
            // 保留原压缩格式 (GZIP vs uncompressed)
            boolean wasCompressed = nbtBytes != null && nbtBytes.length >= 2
                && (nbtBytes[0] & 0xFF) == 0x1F && (nbtBytes[1] & 0xFF) == 0x8B;
            byte[] written;
            if (wasCompressed) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                NbtIo.writeCompressed(newTag, baos);
                written = baos.toByteArray();
            } else {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                NbtIo.write(newTag, new DataOutputStream(baos));
                written = baos.toByteArray();
            }
            Files.write(localNbtPath, written);
            PrefabCustomAddon.LOGGER.info("[EDIT-MODE] SAVED: path={} bytes={} blocks={}",
                localNbtPath, written.length, worldBlocks.size());
            sendPlayerMessage("§a已保存: §f" + localNbtPath.getFileName() + " §7(" + worldBlocks.size() + " 块)");
            // NBT 变了依赖可能也变了 (编辑时加了新模组方块): 同步重算 .txt 的依赖行
            updateTxtDependencies();
            // 通知编辑器清掉 ConstructionInfo.nbtData 缓存 — 否则 GUI/建造路径
            // 仍读内存里的旧 NBT 字节, 出现 "依赖更新了但方块没变化".
            if (onSavedCallback != null) {
                try {
                    onSavedCallback.run();
                } catch (Exception e) {
                    PrefabCustomAddon.LOGGER.warn("[EDIT-MODE] onSavedCallback failed", e);
                }
                onSavedCallback = null;
            }
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.error("[EDIT-MODE] save failed", e);
            sendPlayerMessage("§c保存失败: " + e.getClass().getSimpleName());
        }
        exitEditMode();
    }

    /**
     * 保存后同步 .txt 的依赖行: 从编辑后的方块集合提取非 minecraft 的 modid 命名空间,
     * 与 .txt 原有依赖合并 (保留用户手动加的项, 如 prefab), 写回同目录同名 .txt.
     * .txt 不存在且检测到模组方块时新建只含依赖行的 .txt (创建流程保证正常都有).
     */
    private static void updateTxtDependencies() {
        if (localNbtPath == null) return;
        try {
            // 1) 从编辑后的方块集合提取 modid 命名空间
            Set<String> modIds = new TreeSet<>();
            for (BlockState state : localBlocks.values()) {
                if (state == null) continue;
                var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
                if (key == null) continue;
                String ns = key.getNamespace();
                if (!ns.isEmpty() && !"minecraft".equals(ns)) modIds.add(ns);
            }
            // 2) 同名 .txt (文件名去扩展名 + .txt)
            String name = localNbtPath.getFileName().toString();
            int dot = name.lastIndexOf('.');
            Path txtPath = localNbtPath.resolveSibling(
                dot < 0 ? name + ".txt" : name.substring(0, dot) + ".txt");
            if (!Files.exists(txtPath)) {
                if (!modIds.isEmpty()) {
                    Files.writeString(txtPath, "依赖: " + String.join(", ", modIds) + "\n",
                        StandardCharsets.UTF_8);
                    sendPlayerMessage("§7依赖已更新: §f" + String.join(", ", modIds));
                }
                return;
            }
            // 3) 读原 txt, 找依赖行并合并 (兼容 依赖/依赖模组/dependencies/deps)
            String content = Files.readString(txtPath, StandardCharsets.UTF_8);
            if (!content.isEmpty() && content.charAt(0) == '\uFEFF') content = content.substring(1);
            List<String> lines = new ArrayList<>(List.of(content.split("\\r?\\n", -1)));
            LinkedHashSet<String> merged = new LinkedHashSet<>();
            int depLineIdx = -1;
            for (int i = 0; i < lines.size(); i++) {
                String[] kv = lines.get(i).split("[:：]", 2);
                if (kv.length != 2) continue;
                String k = kv[0].trim();
                if (k.equalsIgnoreCase("依赖") || k.equalsIgnoreCase("依赖模组")
                    || k.equalsIgnoreCase("dependencies") || k.equalsIgnoreCase("deps")) {
                    depLineIdx = i;
                    for (String d : kv[1].split("[,，;；\\s]+")) {
                        if (!d.isEmpty()) merged.add(d.trim());
                    }
                    break;
                }
            }
            if (!merged.addAll(modIds)) {
                return; // 依赖没变化, 不重写文件
            }
            String joined = String.join(", ", merged);
            if (depLineIdx >= 0) {
                lines.set(depLineIdx, "依赖: " + joined);
            } else {
                lines.add("依赖: " + joined);
            }
            Files.writeString(txtPath, String.join("\n", lines), StandardCharsets.UTF_8);
            PrefabCustomAddon.LOGGER.info("[EDIT-MODE] txt deps updated: {} -> {}", txtPath.getFileName(), joined);
            sendPlayerMessage("§7依赖已更新: §f" + joined);
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.warn("[EDIT-MODE] failed to update txt deps", e);
        }
    }

    /**
     * 从 localBlocks 构造完整的 NBT (vanilla structure 格式):
     * <pre>
     * { dataVersion: ..., size: [sx,sy,sz], palette: [...], blocks: [{pos:[x,y,z], state:int}, ...] }
     * </pre>
     * 注意: 如果原 NBT 用了其它格式 (litematic 风格), 这种重写会破坏. 简单方案: 直接复用
     * 原 NBT tag, 只替换 `blocks` 字段, 不动其它 (palette, size, entities 等). 这样兼容性好.
     */
    private CompoundTag buildNbtFromLocalBlocksLocal() {
        // 委托给静态方法, 留 this 引用方便测试 (实际只用静态, 但保留 instance 入口好调用)
        return buildNbtFromLocalBlocks();
    }

    private static CompoundTag buildNbtFromLocalBlocks() {
        if (nbtBytes == null) {
            // 兜底: 没有原 NBT, 构造一个 minimal structure
            CompoundTag tag = new CompoundTag();
            ListTag list = new ListTag();
            for (Map.Entry<BlockPos, BlockState> e : localBlocks.entrySet()) {
                list.add(makeNbtEntry(e.getKey(), e.getValue()));
            }
            tag.put("blocks", list);
            return tag;
        }
        // 解析原 NBT (兼容 GZIP)
        CompoundTag tag;
        try {
            tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(nbtBytes)), NbtAccounter.unlimitedHeap());
        } catch (IOException e1) {
            try {
                tag = NbtIo.readCompressed(new ByteArrayInputStream(nbtBytes), NbtAccounter.unlimitedHeap());
            } catch (IOException e2) {
                PrefabCustomAddon.LOGGER.error("[EDIT-MODE] failed to parse original NBT for rebuild", e2);
                tag = new CompoundTag();
            }
        }
        // 关键: 原 NBT 是 litematic/sponge 等第三方格式时, 直接往里塞 "blocks" 是无效的 —
        // 读取端按内容检测格式, 仍会走第三方转换分支 (读 Regions/Palette), 我们写的 blocks
        // 被整个忽略 → "保存后方块没变化". 先把原 NBT 转成 vanilla 格式再替换 blocks.
        if (!tag.contains("blocks", net.minecraft.nbt.Tag.TAG_LIST)) {
            try {
                CompoundTag converted = com.prefab.addon.work.NbtFormatConverter.toVanilla(tag);
                if (converted != null && converted.contains("blocks", net.minecraft.nbt.Tag.TAG_LIST)) {
                    tag = converted;
                }
            } catch (Throwable t) {
                PrefabCustomAddon.LOGGER.warn("[EDIT-MODE] toVanilla conversion failed, saving as-is", t);
            }
        }
        // 重建 blocks list (其它字段保持原样, 比如 size/palette/entities — 这是关键!)
        ListTag list = new ListTag();
        for (Map.Entry<BlockPos, BlockState> e : localBlocks.entrySet()) {
            list.add(makeNbtEntry(e.getKey(), e.getValue()));
        }
        tag.put("blocks", list);
        return tag;
    }

    /** 构造一个 vanilla structure NBT entry: {pos:[x,y,z], state:int} 或 {pos:[x,y,z], state:{Name:"minecraft:stone"}}. */
    private static CompoundTag makeNbtEntry(BlockPos localPos, BlockState state) {
        CompoundTag entry = new CompoundTag();
        entry.putIntArray("pos", new int[]{localPos.getX(), localPos.getY(), localPos.getZ()});
        // 跟原 NBT 风格一致: 如果原 NBT 用 state int (palette index), 我们不能直接
        // 用 palette index (没维护 palette), 改用 {Name:"...", Properties:{...}} 格式.
        // vanilla structure loader 同时支持这两种 (优先 state int, fallback state object).
        CompoundTag stateTag = new CompoundTag();
        stateTag.putString("Name", net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .getKey(state.getBlock()).toString());
        CompoundTag props = new CompoundTag();
        for (var e : state.getValues().entrySet()) {
            props.putString(e.getKey().getName(), e.getValue().toString());
        }
        if (!props.isEmpty()) stateTag.put("Properties", props);
        entry.put("state", stateTag);
        return entry;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!editing) return;
        try {
            onClientTickImpl(event);
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[EDIT-MODE] onClientTick threw, force exit", t);
            forceExit();
        }
    }

    private static void onClientTickImpl(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || config == null) {
            forceExit();
            return;
        }

        // === 1. 读 KeyMapping (默认值 = 原硬轮询键: 左键/右键/CTRL/ALT/ESC), 玩家可在按键绑定改键 ===
        boolean leftDown  = PackBrowserKeyHandler.isKeyDown(PackBrowserKeyHandler.EDIT_DELETE);
        boolean rightDown = PackBrowserKeyHandler.isKeyDown(PackBrowserKeyHandler.EDIT_PLACE);
        boolean ctrlDown  = PackBrowserKeyHandler.isKeyDown(PackBrowserKeyHandler.EDIT_UNDO);
        boolean altDown   = PackBrowserKeyHandler.isKeyDown(PackBrowserKeyHandler.EDIT_SAVE);
        boolean escDown   = PackBrowserKeyHandler.isKeyDown(PackBrowserKeyHandler.EDIT_EXIT);

        if (leftDown && !lastLeftDown) applyDelete();
        if (rightDown && !lastRightDown) applyPlace();
        if (ctrlDown && !lastCtrlDown) undoLast();
        if (altDown && !lastAltDown) {
            PrefabCustomAddon.LOGGER.info("[EDIT-MODE] ALT pressed, saving");
            saveAndExit();
        }
        if (escDown && !lastEscDown) {
            PrefabCustomAddon.LOGGER.info("[EDIT-MODE] ESC pressed, exiting (discard)");
            sendPlayerMessage("§e已退出编辑, 修改未保存");
            exitEditMode();
        }
        lastLeftDown = leftDown;
        lastRightDown = rightDown;
        lastCtrlDown = ctrlDown;
        lastAltDown = altDown;
        lastEscDown = escDown;

        // === 2. raycast 找 hovered (同时算 hoveredBlock + hoveredAir) ===
        hoveredBlock = null;
        hoveredAir = null;
        try {
            if (player.distanceToSqr(Vec3.atCenterOf(config.pos)) <= 32 * 32) {
                updateHovered(player);
            }
        } catch (Exception e) {
            PrefabCustomAddon.LOGGER.error("[EDIT-MODE] raycast exception", e);
        }
    }

    private static void forceExit() {
        editing = false;
        config = null;
        nbtBytes = null;
        localNbtPath = null;
        onSavedCallback = null;
        worldBlocks.clear();
        localBlocks.clear();
        hoveredBlock = null;
        hoveredAir = null;
        undoStack.clear();
        try { com.prefab.addon.client.gui.CustomStructureGui.clearAddonPreviewFlag(); } catch (Exception ignored) {}
    }

    private static void updateHovered(LocalPlayer player) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F);
        Vec3 end = eye.add(look.x * MAX_RAYCAST_DIST, look.y * MAX_RAYCAST_DIST, look.z * MAX_RAYCAST_DIST);

        // === 关键: 建筑方块不在 mc.level 里 (是内存 map) ——
        // 不能用 mc.level.clip raycast 找建筑方块 (永远命中脚下的世界方块).
        // 必须自己遍历 worldBlocks 算 AABB.clip (跟 litematica schEditUtils 一样).
        // ===

        // 1. 遍历 worldBlocks 找最近方块 (AABB.clip), 同时记录命中点用于算命中面
        BlockPos bestBlock = null;
        Vec3 bestHit = null;
        double bestBlockDist = Double.MAX_VALUE;
        for (BlockPos p : worldBlocks.keySet()) {
            AABB aabb = new AABB(p);
            var hit = aabb.clip(eye, end);
            if (hit.isPresent()) {
                double d = eye.distanceToSqr(hit.get());
                if (d < bestBlockDist) {
                    bestBlockDist = d;
                    bestBlock = p;
                    bestHit = hit.get();
                }
            }
        }

        if (bestBlock != null) {
            // 找到了建筑内方块 → delete target
            hoveredBlock = bestBlock;
            // === 算 air target (放置格) ——
            // 命中面必须是 "射线打进方块的那个面" (法线朝玩家), 从 AABB.clip 的命中点反推,
            // 而不是视线方向. 之前用 Direction.getNearest(look) 拿到的是 "看向的方向",
            // airTarget = bestBlock.relative(look方向) 会算到方块背面: 要么被别的方块占用
            // (放不下去 → "无法对着空气放方块"), 要么触发 6 方向兜底扫描 (顺序固定但跟玩家
            // 瞄的面无关 → "放置方向随机"). 现在直接用真实命中面, intuitive 且稳定.
            Direction face = faceFromHit(bestBlock, bestHit, look);
            BlockPos airTarget = bestBlock.relative(face);
            // 命中面相邻格是空的 → 可放置; 已被占用 → 无处可放 (让玩家换个面瞄).
            hoveredAir = worldBlocks.containsKey(airTarget) ? null : airTarget;
            return;
        }

        // 2. 没命中建筑内方块 → fallback 到 mc.level.clip 找世界方块
        BlockHitResult levelHit;
        try {
            levelHit = mc.level.clip(new net.minecraft.world.level.ClipContext(
                eye, end, net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        } catch (Throwable t) {
            hoveredBlock = null;
            hoveredAir = null;
            return;
        }

        if (levelHit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            hoveredBlock = null;
            hoveredAir = null;
            return;
        }
        BlockPos hitBlock = levelHit.getBlockPos();
        Direction hitFace = levelHit.getDirection();
        BlockPos airTarget = hitBlock.relative(hitFace);
        hoveredBlock = null;
        if (isInsideBuildingBbox(airTarget)
            && !worldBlocks.containsKey(airTarget)
            && mc.level.getBlockState(airTarget).isAir()) {
            hoveredAir = airTarget;
        } else {
            hoveredAir = null;
        }
    }

    /**
     * 从 AABB.clip 命中点反推命中的是哪个面 (返回该面法线朝外的 Direction).
     *
     * <p>命中点一定落在方块 AABB 的某个面上 (该轴坐标 = min 或 max 边界, 距离 ~0),
     * 取 6 个边界距离里最小的那个面即为命中面. look 仅作 hit==null 时的兜底
     * (返回朝向玩家的面 = 视线反方向).</p>
     */
    private static Direction faceFromHit(BlockPos p, Vec3 hit, Vec3 look) {
        if (hit == null) return Direction.getNearest(-look.x, -look.y, -look.z);
        double dxMin = Math.abs(hit.x - p.getX());        // WEST  (x = min)
        double dxMax = Math.abs(hit.x - (p.getX() + 1));  // EAST  (x = max)
        double dyMin = Math.abs(hit.y - p.getY());        // DOWN  (y = min)
        double dyMax = Math.abs(hit.y - (p.getY() + 1));  // UP    (y = max)
        double dzMin = Math.abs(hit.z - p.getZ());        // NORTH (z = min)
        double dzMax = Math.abs(hit.z - (p.getZ() + 1));  // SOUTH (z = max)
        double min = Math.min(Math.min(dxMin, dxMax),
                     Math.min(Math.min(dyMin, dyMax), Math.min(dzMin, dzMax)));
        double eps = 1.0E-4;
        if (dxMin <= min + eps) return Direction.WEST;
        if (dxMax <= min + eps) return Direction.EAST;
        if (dyMin <= min + eps) return Direction.DOWN;
        if (dyMax <= min + eps) return Direction.UP;
        if (dzMin <= min + eps) return Direction.NORTH;
        return Direction.SOUTH;
    }

    private static boolean isInsideBuildingBbox(BlockPos p) {
        if (worldBlocks.isEmpty()) return false;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos k : worldBlocks.keySet()) {
            if (k.getX() < minX) minX = k.getX();
            if (k.getY() < minY) minY = k.getY();
            if (k.getZ() < minZ) minZ = k.getZ();
            if (k.getX() > maxX) maxX = k.getX();
            if (k.getY() > maxY) maxY = k.getY();
            if (k.getZ() > maxZ) maxZ = k.getZ();
        }
        return p.getX() >= minX - 1 && p.getX() <= maxX + 1
            && p.getY() >= minY - 1 && p.getY() <= maxY + 1
            && p.getZ() >= minZ - 1 && p.getZ() <= maxZ + 1;
    }

    private static void applyDelete() {
        if (hoveredBlock == null) return;
        BlockPos pos = hoveredBlock;
        BlockState state = worldBlocks.get(pos);
        if (state == null) return;
        BlockPos localPos = worldToLocal(pos);
        worldBlocks.remove(pos);
        localBlocks.remove(localPos);
        pushUndo(new ActionRecord(true, pos, state));
        PrefabCustomAddon.LOGGER.info("[EDIT-MODE] DELETE: pos={} state={}", pos, state);
        sendPlayerMessage("§c✗ 删除 §7" + pos.toShortString());
        hoveredBlock = null;
        hoveredAir = null;
    }

    private static void applyPlace() {
        if (hoveredAir == null) {
            sendPlayerMessage("§e请瞄准建筑内 (或附近) 一个空格 (橙色高亮)");
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        ItemStack mainHand = player.getMainHandItem();
        if (mainHand.isEmpty()) {
            sendPlayerMessage("§e主手没拿方块");
            return;
        }
        if (!(mainHand.getItem() instanceof BlockItem blockItem)) {
            sendPlayerMessage("§e主手不是方块");
            return;
        }
        BlockState state = blockItem.getBlock().defaultBlockState();
        BlockPos worldPos = hoveredAir;
        if (worldBlocks.containsKey(worldPos)) {
            // race: 玩家连续按右键, 上次还没清完 → 视为 no-op
            PrefabCustomAddon.LOGGER.warn("[EDIT-MODE] PLACE ignored: worldPos already occupied {}", worldPos);
            return;
        }
        BlockPos localPos = worldToLocal(worldPos);
        BlockState oldState = null;  // 是新增, 不是替换
        int sizeBefore = worldBlocks.size();
        worldBlocks.put(worldPos, state);
        localBlocks.put(localPos, state);
        int sizeAfter = worldBlocks.size();
        pushUndo(new ActionRecord(false, worldPos, state));
        PrefabCustomAddon.LOGGER.info("[EDIT-MODE] PLACE: worldPos={} localPos={} state={} size {}->{} dup={}",
            worldPos, localPos, state, sizeBefore, sizeAfter, sizeAfter == sizeBefore);
        sendPlayerMessage("§a✓ 放置 §7" + state.getBlock().getName().getString() + " @ " + localPos.toShortString());
        hoveredBlock = null;
        hoveredAir = null;
    }

    private static void undoLast() {
        ActionRecord rec = undoStack.pollLast();
        if (rec == null) {
            sendPlayerMessage("§7没有可撤销的操作");
            return;
        }
        if (rec.wasDelete()) {
            // 之前是 delete → 放回
            BlockState state = rec.placedState();
            BlockPos worldPos = rec.placedPos();
            BlockPos localPos = worldToLocal(worldPos);
            worldBlocks.put(worldPos, state);
            localBlocks.put(localPos, state);
            sendPlayerMessage("§a↶ 撤销删除: " + worldPos.toShortString());
        } else {
            // 之前是 place → 删除 (如果原来是替换, 还原成 oldState)
            BlockPos worldPos = rec.placedPos();
            BlockPos localPos = worldToLocal(worldPos);
            BlockState oldState = rec.placedState();
            if (worldBlocks.containsKey(worldPos)) {
                worldBlocks.remove(worldPos);
            }
            if (localBlocks.containsKey(localPos)) {
                localBlocks.remove(localPos);
            }
            sendPlayerMessage("§a↶ 撤销放置: " + worldPos.toShortString());
        }
        PrefabCustomAddon.LOGGER.info("[EDIT-MODE] UNDO: wasDelete={} pos={} remaining={}",
            rec.wasDelete(), rec.placedPos(), undoStack.size());
        hoveredBlock = null;
        hoveredAir = null;
    }

    private static void pushUndo(ActionRecord rec) {
        if (undoStack.size() >= MAX_UNDO) undoStack.pollFirst();
        undoStack.offerLast(rec);
    }

    /**
     * world pos → local pos. 跟 CustomStructureBuilder.offsetStructureBlocks 互逆.
     * 已知 CustomStructureBuilder forward 用 (x,z)→(z,-x), 逆用 (x,z)→(-z,x).
     */
    private static BlockPos worldToLocal(BlockPos worldPos) {
        if (config == null || config.pos == null) return worldPos;
        int steps = facingToSteps(config.houseFacing);
        int rx = worldPos.getX() - config.pos.getX();
        int ry = worldPos.getY() - config.pos.getY();
        int rz = worldPos.getZ() - config.pos.getZ();
        for (int s = 0; s < steps; s++) {
            int newRx = -rz;
            int newRz =  rx;
            rx = newRx;
            rz = newRz;
        }
        return new BlockPos(rx, ry, rz);
    }

    private static int facingToSteps(Direction facing) {
        if (facing == null) return 0;
        return switch (facing) {
            case SOUTH -> 0;
            case EAST  -> 1;
            case NORTH -> 2;
            case WEST  -> 3;
            default    -> 0;
        };
    }

    /**
     * 从 worldBlocks 反推 local pos. 因为 local map 数量与 world map 数量一致,
     * 而 local map 的 key (local pos) 经过 worldToLocal 应该 = world map 的 key.
     * 如果 world map 多了一个 key (玩家刚 place 的, local map 应该也有), 直接用 worldToLocal.
     * 反之 (玩家刚 delete, world map 没这个 key) → 不可能调用本方法.
     */
    private static BlockPos findLocalForWorld(BlockPos worldPos) {
        if (config == null) return null;
        return worldToLocal(worldPos);
    }

    private static void sendPlayerMessage(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(msg), true);
        }
    }
}
