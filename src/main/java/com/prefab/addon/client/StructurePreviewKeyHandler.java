package com.prefab.addon.client;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.structures.base.Structure;
import com.prefab.structures.config.StructureConfiguration;
import com.prefab.structures.render.StructureRenderHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;

/**
 * 自定义建筑 Preview 全局键盘控制。
 *
 * 玩家点 "预览" 后 GUI 会关闭（Prefab 默认行为），但玩家还需要在世界中用键盘
 * 微调预览位置。本类用 ClientTickEvent 全局监听键盘：
 *
 * - ←/→  : 相对**玩家朝向**左右平移（Shift = 5 格大步）
 * - ↑/↓  : 相对**玩家朝向**前后平移（Shift = 5 格大步）
 * - +/-  : 整体垂直上下 1 格（Shift = 5 格大步）
 * - CTRL : 整体逆时针旋转 90°（按一次 90°，按住时按节流间隔持续旋转）
 * - ALT  : 在当前 Preview 位置直接建造
 *
 * 节流：所有移动 / 旋转受 minIntervalMs 控制（默认 150ms），
 *       防止按 1 帧 1 格过快（实测 60fps 一次按下会飞 60 格），也避免
 *       setStructure→rebuildPreviewMeshes 在大建筑上卡顿。
 *
 * 渲染提示在 StructurePreviewHud（屏幕顶部居中显示）。
 */
@EventBusSubscriber(modid = PrefabCustomAddon.MOD_ID, value = Dist.CLIENT)
public class StructurePreviewKeyHandler {

    // 防止按住 ALT 时一帧内建造多次
    private static int lastAction = -1;

    // 节流：距离上次 move/rotate 至少经过这么久（毫秒）才允许下一次
    private static final long MOVE_INTERVAL_MS = 150L;
    private static long lastMoveTimeMs = 0L;

    // 防止按住右键时一帧内 cancel 多次
    private static boolean lastRightDown = false;

    // prefab 原版建筑预览时, 我们已经向聊天栏发过 "该预览模式由附属模组提供" 提示,
    // 防止每个 tick / 每次移动都重复发. 用 prefab.currentStructure 的 identityHashCode
    // 作 key — 同一个预览结构对象, 移动/旋转都不变; 玩家切换到另一个建筑预览时
    // reference 变, key 跟着变, 我们再发一次. 之前用 cfg.pos.toString() + identityHashCode
    // 失败: cfg.pos 每次方向键移动都变, key 跟着变 → 每次移动都触发发消息 → 刷屏.
    private static int addonPreviewNoticeStructureHash = 0;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // === 先于 "mc.screen != null return" 处理右键取消预览 ===
        // 否则玩家在预览模式下右键手持自定义蓝图 → 蓝图 GUI 打开 (mc.screen != null) →
        // 下一 tick 我们早退 → 右键取消逻辑永远跑不到 → 预览还卡在世界里.
        // 这里在 screen 检查之前先 polling 一次右键, 即使 GUI 已经开了也立即关掉 + 取消预览.
        long windowEarly = mc.getWindow().getWindow();
        boolean rightDownEarly = GLFW.glfwGetMouseButton(windowEarly, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        boolean anyPreviewActive =
            com.prefab.addon.client.gui.CustomStructureGui.getAddonPreviewStructure() != null
            || StructureRenderHandler.currentStructure != null;
        if (anyPreviewActive && rightDownEarly && !lastRightDown) {
            // 关闭可能因右键开启的 GUI (例如自定义蓝图右键 → CustomStructureGui)
            if (mc.screen != null) {
                mc.player.closeContainer();
                mc.setScreen(null);
            }
            // 取消所有预览状态
            if (com.prefab.addon.cloud.CloudPreview.isActive()) {
                mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "✗ 已取消云端建筑预览").withStyle(net.minecraft.ChatFormatting.YELLOW));
            } else {
                mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "✗ 已取消预览").withStyle(net.minecraft.ChatFormatting.YELLOW));
            }
            com.prefab.addon.cloud.CloudPreview.cancel();
            com.prefab.addon.client.gui.CustomStructureGui.clearAddonPreviewFlag();
            StructureRenderHandler.setStructure(null, null);
            addonPreviewNoticeStructureHash = 0;
            lastRightDown = true;
            PrefabCustomAddon.LOGGER.info("[PREVIEW-CANCEL] 右键取消预览 (早于 screen 检查, 当前 screen={})",
                mc.screen == null ? "null" : mc.screen.getClass().getSimpleName());
            return;
        }
        lastRightDown = rightDownEarly;

        // 当前没有打开任何 GUI（点完 Preview 后 GUI 已关闭）
        if (mc.screen != null) {
            return;
        }

        // === 两种预览都处理: 我们 own 的自定义预览 + prefab 原版预览 ===
        // 之前改成只读 ADDON 字段 → prefab 原版预览时 ADDON 是 null, KeyHandler return,
        // 玩家按方向键 / ALT 毫无反应 — 用户反馈"原版蓝图预览方向键/ALT 用不了".
        // 修复: 优先看 ADDON 字段 (我们的预览), 没有再回退到 prefab.currentStructure (原版预览).
        Structure currentStructure = com.prefab.addon.client.gui.CustomStructureGui.getAddonPreviewStructure();
        StructureConfiguration cfg = com.prefab.addon.client.gui.CustomStructureGui.getAddonPreviewConfig();
        boolean isAddonPreview = (currentStructure != null && cfg != null);

        if (!isAddonPreview) {
            // 回退到 prefab 原版预览 (玩家用 prefab 的 GuiStructure 预览原版建筑)
            currentStructure = StructureRenderHandler.currentStructure;
            cfg = StructureRenderHandler.currentConfiguration;
        }

        if (cfg == null || cfg.pos == null || currentStructure == null) {
            // 两种预览都结束 (玩家按了 ALT 建造 / 或 prefab 自己 setStructure(null, null))
            com.prefab.addon.client.gui.CustomStructureGui.clearAddonPreviewFlag();
            // 通知标记也清掉, 下次 prefab 原版预览时还能再发.
            addonPreviewNoticeStructureHash = 0;
            // 云端预览标志也清 (虽然 cancel() 自己也会清, 这里再保险一次)
            com.prefab.addon.cloud.CloudPreview.cancel();
            lastRightDown = false;
            return;
        }

        // 取 window handle — 必须在右键检测前, 因为右键检测要用 GLFW.glfwGetMouseButton
        long window = mc.getWindow().getWindow();

        // 右键取消预览已在方法最开头 (早于 screen 检查) 处理, 这里不再重复.
        // 之所以挪上去: 玩家在预览模式下右键手持自定义蓝图 → 蓝图 GUI 打开 → screen != null →
        // 这里就被 return 拦住, 取消逻辑跑不到. 改到上面后, 即使 GUI 开了也能 1 tick 内关掉.
        // lastRightDown 也由上面维护, 这里只复用同一个变量.

        // **关键**: isPrefabOriginalPreview 用 !isAddonPreview 单独判断.
        // 不要加 (packName.isEmpty()||constructionId.isEmpty()) 条件 — 跟 ALT 分支不一致
        // 会导致玩家先打开我们的 GUI 改过一个自定义建筑 (currentConstruction 缓存, packName 不空),
        // 然后再去开 prefab 的 GuiStructure 预览原版建筑, isPrefabOriginalPreview 误判 false
        // → 移动/旋转不调 triggerPrefabRebuild, prefab 的 previewChunks 不重建, 预览卡原位置.
        boolean isPrefabOriginalPreview = !isAddonPreview;

        // prefab 原版预览时, 第一次向聊天栏发提示 "由附属模组提供预览"
        if (isPrefabOriginalPreview) {
            int hash = System.identityHashCode(currentStructure);
            if (hash != addonPreviewNoticeStructureHash) {
                addonPreviewNoticeStructureHash = hash;
                mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "ℹ 该预览模式由附属模组提供, 可用方向键 / CTRL / ALT 操作")
                    .withStyle(net.minecraft.ChatFormatting.AQUA));
            }
        } else {
            // 自定义预览时不需要这条提示
            addonPreviewNoticeStructureHash = 0;
        }

        // window handle 已在上面右键检测前取了, 复用同一个变量
        boolean shiftDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        boolean ctrlDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
        int step = shiftDown ? 5 : 1;

        // === 方向键：相对玩家视角移动 ===
        // 玩家水平朝向（不含上下）
        Direction playerFacing = mc.player.getNearestViewDirection();
        if (playerFacing == Direction.UP || playerFacing == Direction.DOWN) {
            playerFacing = Direction.NORTH;
        }

        Direction forward = playerFacing;
        Direction back = playerFacing.getOpposite();
        Direction left = playerFacing.getCounterClockWise();
        Direction right = playerFacing.getClockWise();

        int dx = 0, dz = 0, dy = 0;
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT) == GLFW.GLFW_PRESS) {
            dx += left.getStepX();
            dz += left.getStepZ();
        }
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT) == GLFW.GLFW_PRESS) {
            dx += right.getStepX();
            dz += right.getStepZ();
        }
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_UP) == GLFW.GLFW_PRESS) {
            dx += forward.getStepX();
            dz += forward.getStepZ();
        }
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_DOWN) == GLFW.GLFW_PRESS) {
            dx += back.getStepX();
            dz += back.getStepZ();
        }
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_EQUAL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_KP_ADD) == GLFW.GLFW_PRESS) {
            dy += 1;
        }
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_MINUS) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_KP_SUBTRACT) == GLFW.GLFW_PRESS) {
            dy -= 1;
        }

        // 节流：如果上次 move/rotate 还没超过 MOVE_INTERVAL_MS，就不响应
        long now = System.currentTimeMillis();
        boolean canMove = (now - lastMoveTimeMs) >= MOVE_INTERVAL_MS;

        if (canMove && (dx != 0 || dz != 0 || dy != 0)) {
            BlockPos oldPos = cfg.pos;
            BlockPos newPos = oldPos.offset(dx * step, dy * step, dz * step);
            cfg.pos = newPos;
            if (isAddonPreview) {
                // === 我们 own 的预览: 同步更新每个 BuildBlock.blockPos, 否则我们的 renderer
                //   读 blockPos 算出来的位置跟 cfg.pos 不一致, 预览"半移动".
                //   必须传 cfg.houseFacing: 用户已经旋转过, 移动后还要保持旋转.
                com.prefab.addon.structure.CustomStructureBuilder.offsetStructureBlocks(
                    currentStructure, newPos, cfg.houseFacing);
                // 我们的 CustomStructurePreviewRenderer 检测到 cfg.pos 变化时, 自动重建.
            } else {
                // === prefab 原版建筑预览 ===
                // 不能调 CustomStructureBuilder.offsetStructureBlocks (那是给我们自定义建筑
                //   设计的, prefab 原版建筑调它会破坏 prefab 自己的数据结构).
                // 正确做法: 重新调 StructureRenderHandler.setStructure(structure, cfg) 触发
                //   prefab 重新 bake 一次 vertex buffer (prefab 渲染靠 bake 出来的 buffer,
                //   改 cfg.pos 不会自动重建).
                // 关键: setStructure 内部会把 showedMessage 重置为 false → prefab 下次渲染会
                //   重新发 "右键取消预览" 那 2 条聊天消息, 每移动一次刷一次屏.
                //   修复: 调完之后立即把 showedMessage 改回 true, 阻止 prefab 重复发.
                StructureRenderHandler.setStructure(currentStructure, cfg);
                StructureRenderHandler.showedMessage = true;
            }
            lastMoveTimeMs = now;
            PrefabCustomAddon.LOGGER.info("[PREVIEW-MOVE] {} player={} dx={} dz={} dy={} step={}  {} -> {}",
                isAddonPreview ? "addon" : "prefab", playerFacing, dx, dz, dy, step, oldPos, newPos);
            return;  // 一帧内 move 和 rotate 不能同时发生（避免冲突）
        }

        // === CTRL 键 → 旋转 90°（逆时针） ===
        if (canMove && ctrlDown) {
            Direction newFacing = rotateCounterClockwise(cfg.houseFacing);
            Direction oldFacing = cfg.houseFacing;
            cfg.houseFacing = newFacing;
            if (isAddonPreview) {
                // === 我们 own 的预览: houseFacing 变化要同步 BuildBlock.blockPos ===
                com.prefab.addon.structure.CustomStructureBuilder.offsetStructureBlocks(
                    currentStructure, cfg.pos, cfg.houseFacing);
            } else {
                // === prefab 原版建筑预览: 调 setStructure 重新 bake, 不要调 offsetStructureBlocks ===
                //    同样要把 showedMessage 改回 true 阻止 prefab 重发聊天消息
                StructureRenderHandler.setStructure(currentStructure, cfg);
                StructureRenderHandler.showedMessage = true;
            }
            lastMoveTimeMs = now;
            PrefabCustomAddon.LOGGER.info("[PREVIEW-ROTATE] {} houseFacing {} -> {}",
                isAddonPreview ? "addon" : "prefab", oldFacing, newFacing);
        }

        // === ALT 键 → 直接建造（节流：1 秒最多 1 次） ===
        // packName/constructionId 为空 = prefab 原版建筑预览, 我们不知道发什么 build packet,
        // 直接放弃 ALT 建造 (让用户用 prefab 自己的 build 按钮 / 或者回到 prefab GUI)
        boolean altDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
        if (altDown) {
            if (lastAction != GLFW.GLFW_KEY_LEFT_ALT && lastAction != GLFW.GLFW_KEY_RIGHT_ALT) {
                // 关键: 走"prefab 原版建造"还是"我们的自定义建造"用 isAddonPreview 判,
                // 不要用 packName.isEmpty() — 之前就是这里错: packName 来自 currentConstruction
                // (上次编辑的自定义建筑缓存), 即使玩家已经打开了 prefab 原版的预览,
                // currentConstruction 还残留, 误判走我们的路径 → BuildCustomStructurePayload
                // 被发到服务端 → 服务端 consumeBlueprint 把玩家背包里的**自定义蓝图**消耗掉了.
                if (com.prefab.addon.cloud.CloudPreview.isActive()) {
                    // === 云端建筑预览: 走 cloud_summon, 不消耗蓝图, 不走原版 build ===
                    triggerCloudSummon(cfg);
                } else if (!isAddonPreview) {
                    // === prefab 原版建筑预览 ===
                    // 复用 prefab 自己 GameClientEvents.KeyInput 用的同一条路径:
                    //   new StructureTagMessage(cfg.WriteToCompoundTag(),
                    //                            EnumStructureConfiguration.getByConfigurationInstance(cfg))
                    //   PrefabBase.networkWrapper.sendToServer(ClientToServerTypes.STRUCTURE_BUILD, msg);
                    // 这样 ALT 触发的是 prefab 原版的建造流程, 不会走我们的 BuildCustomStructurePayload,
                    // 也就不会消耗玩家背包里的自定义蓝图 (我们不插手 prefab 原版的消耗逻辑).
                    triggerPrefabOriginalBuild(cfg);
                } else {
                    triggerBuildAtPreview(cfg, currentStructure);
                }
            }
        }
        if (!altDown) {
            lastAction = -1;
        }
    }

    /**
     * 逆时针旋转 90°：SOUTH → EAST → NORTH → WEST → SOUTH
     * 也就是逆时针（看向俯视图）。
     */
    private static Direction rotateCounterClockwise(Direction current) {
        return switch (current) {
            case SOUTH -> Direction.EAST;
            case EAST -> Direction.NORTH;
            case NORTH -> Direction.WEST;
            case WEST -> Direction.SOUTH;
            default -> current;
        };
    }

    /**
     * ALT 按下时直接建造。
     * 挑战模式开启时, 必须先在 MaterialSubmissionGui 提交全部材料, 否则阻止建造.
     *
     * <p>关键: packName / constructionId 必须用当前打开的 GUI 提供的值
     * ({@link com.prefab.addon.client.gui.CustomStructureGui#getPackNameForBuild()}),
     * 不能用蓝图里存的 packName -- 旧版本蓝图绑定时用的是 getPackageName(),
     * 跟服务端 findConstruction 用的 getName() 不一致, 会导致服务端消耗蓝图失败
     * (刚开服 GUI 按钮能消耗是因为 GUI 用 currentConstruction.getPack().getName()).</p>
     */
    private static void triggerBuildAtPreview(StructureConfiguration cfg, Structure structure) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        lastAction = GLFW.GLFW_KEY_LEFT_ALT;

        // === 关键: 用 GUI 暴露的 packName/constructionId, 而不是蓝图存的旧 packName ===
        String packName = com.prefab.addon.client.gui.CustomStructureGui.getPackNameForBuild();
        String constructionId = com.prefab.addon.client.gui.CustomStructureGui.getConstructionIdForBuild();
        if (packName.isEmpty() || constructionId.isEmpty()) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "⚠ 找不到当前预览的建筑信息! 请重新打开蓝图右键")
                    .withStyle(net.minecraft.ChatFormatting.RED));
            PrefabCustomAddon.LOGGER.warn("[PREVIEW-BUILD] packName/constructionId 为空, GUI 已关闭");
            return;
        }

        // === 挑战模式检查 ===
        com.prefab.addon.config.PlayerPreferences prefs = com.prefab.addon.config.PlayerPreferences.get();
        PrefabCustomAddon.LOGGER.info("[PREVIEW-BUILD] ALT build attempt: pack={}/{} consumeMaterials={}",
            packName, constructionId, prefs.consumeMaterials);
        if (prefs.consumeMaterials) {
            // 用 GUI 提供的 packName/constructionId 找 info
            com.prefab.addon.extension.ConstructionInfo info =
                com.prefab.addon.extension.ExtensionPackManager.getInstance().findConstruction(packName, constructionId);
            if (info == null) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "⚠ 找不到建筑: " + constructionId).withStyle(net.minecraft.ChatFormatting.RED));
                return;
            }
            try {
                com.prefab.addon.work.MaterialCalculator.MaterialList matList =
                    com.prefab.addon.work.MaterialCalculator.calculate(info.getNbtData());
                boolean ready = com.prefab.addon.work.ChallengeSessionManager.isReady(
                    player.getUUID(), constructionId, matList.required);
                PrefabCustomAddon.LOGGER.info(
                    "[PREVIEW-BUILD] Material check: ready={} required={} submitted={}",
                    ready, matList.required,
                    com.prefab.addon.work.ChallengeSessionManager.getSubmitted(player.getUUID(), constructionId));
                if (!ready) {
                    int totalRequired = matList.required.values().stream().mapToInt(Integer::intValue).sum();
                    int totalSubmitted = com.prefab.addon.work.ChallengeSessionManager.getSubmitted(
                        player.getUUID(), constructionId).values().stream()
                        .mapToInt(Integer::intValue).sum();
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "⚠ 挑战模式: 已交 " + totalSubmitted + " / " + totalRequired
                            + " 个材料, 还差 " + (totalRequired - totalSubmitted)
                            + " 个才能建造 (按 H 键打开提交材料界面)")
                        .withStyle(net.minecraft.ChatFormatting.RED));
                    return;
                }
            } catch (Throwable t) {
                PrefabCustomAddon.LOGGER.error("[PREVIEW-BUILD] Material check failed for {}/{}",
                    packName, constructionId, t);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "⚠ 挑战模式材料检查失败, 已阻止建造")
                    .withStyle(net.minecraft.ChatFormatting.RED));
                return;
            }
        }

        // === 检查背包有蓝图 (没蓝图就直接走服务端消耗, 服务端会自己处理失败) ===
        // 既认 mod 原生 CustomBlueprintItem, 也认 KubeJS 注册的带 player_blueprint tag 的物品.
        net.minecraft.world.item.ItemStack blueprint = net.minecraft.world.item.ItemStack.EMPTY;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack s = player.getInventory().getItem(i);
            if (s.getItem() instanceof com.prefab.addon.items.CustomBlueprintItem
                || com.prefab.addon.client.CustomBlueprintClientHandler.isHandledBlueprint(s)) {
                blueprint = s;
                break;
            }
        }
        if (blueprint.isEmpty()) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "背包里没有 自定义蓝图 物品!").withStyle(net.minecraft.ChatFormatting.RED));
            return;
        }

        // 建造完后清空该建筑的提交进度 (挑战模式)
        com.prefab.addon.work.ChallengeSessionManager.reset(player.getUUID(), constructionId);

        PrefabCustomAddon.LOGGER.info("[PREVIEW-BUILD] ALT pressed, sending BuildCustomStructurePayload for {}/{} at {}",
            packName, constructionId, cfg.pos);

        // **关键**: 先发 BindConstructionPayload 让服务端把当前 Construction 绑到玩家背包里
        // 第一张未锁定的蓝图 (CustomStructureGui.performBuildClick 也加了同样逻辑).
        // 之前没绑过 → consumeBlueprint 在服务端查不到匹配的 blueprint → 不消耗.
        // packet 是有序的, 所以服务端会先处理 bind, 再处理 build, 蓝图会正确消耗.
        com.prefab.addon.network.NetworkHandler.sendToServer(
            new com.prefab.addon.network.BindConstructionPayload(
                packName, constructionId, false));

        com.prefab.addon.network.NetworkHandler.sendToServer(
            new com.prefab.addon.network.BuildCustomStructurePayload(
                cfg.pos, packName, constructionId, cfg.houseFacing,
                com.prefab.addon.config.PlayerPreferences.get().getBuildAnimationMode()));
        // 清预览: prefab 的 currentStructure (no-op, 之前已 null) + 我们 own 的 ADDON_PREVIEW_*
        StructureRenderHandler.setStructure(null, null);
        com.prefab.addon.client.gui.CustomStructureGui.clearAddonPreviewFlag();
    }

    /**
     * ALT 在 prefab 原版建筑预览中按下时, 复用 prefab 自己的 build 流程.
     * <p>本方法做的事情和 prefab 自带的 {@code GameClientEvents.KeyInput} 一模一样:</p>
     * <pre>
     *   new StructureTagMessage(cfg.WriteToCompoundTag(),
     *                            EnumStructureConfiguration.getByConfigurationInstance(cfg))
     *   PrefabBase.networkWrapper.sendToServer(ClientToServerTypes.STRUCTURE_BUILD, msg);
     * </pre>
     * 服务端 {@code ServerPayloadHandler.structureBuilderHandler} 会读这个 packet 然后
     * {@code configuration.BuildStructure(serverPlayer, level)} — 跟玩家点 prefab 自己的
     * "Build" 按钮是完全一致的代码路径.
     */
    private static void triggerPrefabOriginalBuild(StructureConfiguration cfg) {
        if (cfg == null) return;
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        lastAction = GLFW.GLFW_KEY_LEFT_ALT;

        com.prefab.structures.messages.StructureTagMessage.EnumStructureConfiguration enumConfig =
            com.prefab.structures.messages.StructureTagMessage.EnumStructureConfiguration
                .getByConfigurationInstance(cfg);
        if (enumConfig == null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "⚠ 找不到对应的 prefab EnumStructureConfiguration (类型="
                    + cfg.getClass().getSimpleName() + ")")
                .withStyle(net.minecraft.ChatFormatting.RED));
            PrefabCustomAddon.LOGGER.warn(
                "[PREVIEW-BUILD] getByConfigurationInstance returned null for class {}",
                cfg.getClass().getName());
            return;
        }

        com.prefab.structures.messages.StructureTagMessage msg =
            new com.prefab.structures.messages.StructureTagMessage(
                cfg.WriteToCompoundTag(), enumConfig);

        PrefabCustomAddon.LOGGER.info(
            "[PREVIEW-BUILD] ALT (prefab original) sending STRUCTURE_BUILD for {} at {} facing {}",
            enumConfig, cfg.pos, cfg.houseFacing);

        com.prefab.PrefabBase.networkWrapper.sendToServer(
            com.prefab.network.ClientToServerTypes.STRUCTURE_BUILD, msg);

        // 清预览
        StructureRenderHandler.setStructure(null, null);
    }

    /**
     * prefab 原版建筑预览时, 强制 prefab 重建 previewChunks 缓存.
     * <p>不调这个, 玩家移动/旋转后 prefab 的 renderer 还在用旧位置的 cached mesh,
     * 只看到黄色框在动, 实际结构不动.</p>
     *
     * <p>实现: {@code setStructure(currentStructure, currentConfiguration)}
     * 会把 {@code needsRebuild=true} 并清掉 {@code blockModelQuads} 缓存,
     * 下次 render 时 prefab 会重新跑 {@code rebuildPreviewMeshes}, 用新的 {@code cfg.pos}
     * 重新计算每个 block 的世界位置.</p>
     *
     * <p>副作用: setStructure 也会把 {@code showedMessage} 改回 {@code false},
     * 下次 render prefab 会再次发 "右击任何方块即可移除预览" / "黄色轮廓是您单击的块"
     * 两条聊天消息. 我们调完后立即把 {@code showedMessage} 改回 {@code true},
     * prefab 就不会重复发了 (PrefabChatFilter 也兜底拦截这两条).</p>
     */
    private static void triggerPrefabRebuild() {
        // 关键: prefab 原版预览时, 我们的 ADDON 字段是 null, prefab.currentStructure
        // 才是真的 structure. triggerPrefabRebuild 强制 prefab 重建 cache 必须用 prefab
        // 自己的字段. (我们自己的预览时, prefab.currentStructure = null 一直, 调它就是 no-op)
        StructureConfiguration cfg = StructureRenderHandler.currentConfiguration;
        Structure structure = StructureRenderHandler.currentStructure;
        if (cfg == null || structure == null) return;
        StructureRenderHandler.setStructure(structure, cfg);
        StructureRenderHandler.showedMessage = true;
    }

    /**
     * ALT 在云端建筑预览中按下时, 发送 {@link com.prefab.addon.cloud.CloudBuildingSummonPayload}
     * 把当前预览位置 (cfg.pos) + 朝向 (cfg.houseFacing) 发到服务端, 让服务端在玩家选的位置
     * 重建建筑 (不强制头顶 1 格).
     */
    private static void triggerCloudSummon(StructureConfiguration cfg) {
        if (cfg == null || cfg.pos == null) return;
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        lastAction = GLFW.GLFW_KEY_LEFT_ALT;

        String buildingId = com.prefab.addon.cloud.CloudPreview.getCurrentCloudBuildingId();
        if (buildingId == null || buildingId.isEmpty()) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "⚠ 云端预览状态丢失, 请重开放出按钮")
                .withStyle(net.minecraft.ChatFormatting.RED));
            PrefabCustomAddon.LOGGER.warn("[PREVIEW-BUILD] cloudSummon called but CURRENT_CLOUD_BUILDING_ID is null");
            com.prefab.addon.cloud.CloudPreview.cancel();
            return;
        }

        com.prefab.addon.cloud.CloudBuilding cb =
            com.prefab.addon.cloud.CloudBuildingClientCache.getInstance().getById(buildingId);
        if (cb == null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "⚠ 云端建筑已不存在: " + buildingId)
                .withStyle(net.minecraft.ChatFormatting.RED));
            com.prefab.addon.cloud.CloudPreview.cancel();
            return;
        }
        if (cb.placed) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "⚠ 该云端建筑已放出 @ " + (cb.placedAt == null ? "?" : cb.placedAt.toShortString())
                    + ", 请先收回")
                .withStyle(net.minecraft.ChatFormatting.RED));
            com.prefab.addon.cloud.CloudPreview.cancel();
            return;
        }

        PrefabCustomAddon.LOGGER.info(
            "[PREVIEW-BUILD] ALT (cloud) sending cloud_summon id={} pos={} facing={}",
            buildingId, cfg.pos, cfg.houseFacing);

        com.prefab.addon.network.NetworkHandler.sendToServer(
            new com.prefab.addon.cloud.CloudBuildingSummonPayload(
                buildingId, cfg.pos, cfg.houseFacing));

        // 清预览状态 (服务端处理完后会 sync 回来, 客户端 cache 也跟着更新)
        com.prefab.addon.cloud.CloudPreview.cancel();
        com.prefab.addon.client.gui.CustomStructureGui.clearAddonPreviewFlag();
        StructureRenderHandler.setStructure(null, null);
    }
}
