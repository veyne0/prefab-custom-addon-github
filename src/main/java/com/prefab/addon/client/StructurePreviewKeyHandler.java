package com.prefab.addon.client;

import com.prefab.addon.PrefabCustomAddon;
import com.wuest.prefab.structures.base.Structure;
import com.wuest.prefab.structures.config.StructureConfiguration;
import com.wuest.prefab.structures.render.StructureRenderHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;
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
@Mod.EventBusSubscriber(modid = PrefabCustomAddon.MOD_ID, value = Dist.CLIENT)
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
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        // === 编辑原理图模式: 按键全部由 EditModeController 自己的 tick 处理, 这里完全跳过 ===
        // (否则编辑模式的右键=放置会被下面的 "右键取消预览" 逻辑吞掉, ESC=退出会被干扰)
        if (EditModeController.isEditing()) return;
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
        if (anyPreviewActive && rightDownEarly && !lastRightDown && !EditModeController.shouldBlockPreviewCancel()) {
            // 关闭可能因右键开启的 GUI (例如自定义蓝图右键 → CustomStructureGui)
            if (mc.screen != null) {
                mc.player.closeContainer();
                mc.setScreen(null);
            }
            // 取消所有预览状态
            mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "✗ 已取消预览").withStyle(net.minecraft.ChatFormatting.YELLOW));
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
        Direction playerFacing = Direction.orderedByNearest(mc.player)[0]; // 1.20.1: 无 getNearestViewDirection
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
                // 关键: prefab 1.20.1 渲染 (StructureRenderHandler.newRenderPlayerLook) 里
                //   BuildBlock.blockPos/centerOfBlock 是懒缓存 — 首帧按当时 cfg.pos 算出
                //   绝对坐标存进字段, 之后每帧直接用缓存, 不再读 cfg.pos; 而 1.20.1 的
                //   setStructure 只赋静态字段 (无 needsRebuild/重建逻辑), 所以只改 cfg.pos
                //   或重调 setStructure 方块纹丝不动 (黄框 renderClickedBlock 每帧读
                //   cfg.pos 所以会动). 必须清缓存让 prefab 下帧按 newPos 重算
                //   (移动不改朝向, blockState 保留不清).
                resetPrefabBlockLazyCache(currentStructure, false);
                // setStructure 会把 showedMessage 重置为 false → prefab 下次渲染会
                //   重新发 "右键取消预览" 那 2 条聊天消息, 每移动一次刷一次屏.
                //   调完之后立即改回 true, 阻止 prefab 重复发.
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
                // === prefab 原版建筑预览: 清 BuildBlock 懒缓存 (原理同移动分支) ===
                //    旋转额外把 blockState 也清掉: 楼梯等朝向方块的 state 带 facing 属性,
                //    prefab 渲染发现 state==null 会用 BuildBlock.SetBlockState 按
                //    新 houseFacing 重算. showedMessage 改回 true 阻止 prefab 重发聊天消息.
                resetPrefabBlockLazyCache(currentStructure, true);
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
                // 编辑原理图模式: ALT = 保存并退出编辑 (而不是触发建造)
                if (EditModeController.isEditing()) {
                    EditModeController.saveAndExit();
                    lastAction = GLFW.GLFW_KEY_LEFT_ALT;
                    return;
                }
                // 关键: 走"prefab 原版建造"还是"我们的自定义建造"用 isAddonPreview 判,
                // 不要用 packName.isEmpty() — 之前就是这里错: packName 来自 currentConstruction
                // (上次编辑的自定义建筑缓存), 即使玩家已经打开了 prefab 原版的预览,
                // currentConstruction 还残留, 误判走我们的路径 → BuildCustomStructurePayload
                // 被发到服务端 → 服务端 consumeBlueprint 把玩家背包里的**自定义蓝图**消耗掉了.
                if (!isAddonPreview) {
                    // === prefab 原版建筑预览 ===
                    // 复用 prefab 自己 GameClientEvents.KeyInput 用的同一条路径:
                    //   new StructureTagMessage(cfg.WriteToCompoundTag(),
                    //                            EnumStructureConfiguration.getByConfigurationInstance(cfg))
                    //   PrefabBase.networkWrapper.sendToServer(ClientToServerTypes.STRUCTURE_BUILD, msg);
                    // 这样 ALT 触发的是 prefab 原版的建造流程, 不会走我们的 BuildCustomStructurePayload,
                    // 也就不会消耗玩家背包里的自定义蓝图 (我们不插手 prefab 原版的消耗逻辑).
                    triggerPrefabOriginalBuild(cfg);
                } else if (com.prefab.addon.client.gui.CustomStructureGui.isCurrentOutsource()) {
                    // === 外包建筑预览 (从 1.21.1 移植) ===
                    // GuiOutsourceBuildingDetail.onPreview 设置了 outsource 上下文 → 走专用 build 路径
                    triggerOutsourceBuildAtPreview(cfg);
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

        // === 材料检查 (按玩家游戏模式判定: 生存/冒险消耗, 创造免费; 旧的"挑战模式"开关已移除) ===
        boolean consumeMaterials = !player.isCreative();
        // KubeJS 联动蓝图豁免 (与 GUI 流程一致): 玩家合成的蓝图建造不收材料.
        // 判定优先级: 先看预览来源蓝图, 再退回背包扫描.
        if (consumeMaterials) {
            ItemStack kbSrc = com.prefab.addon.client.gui.CustomStructureGui.currentBlueprint;
            boolean kubeJSBlueprint = kbSrc != null && !kbSrc.isEmpty()
                    && com.prefab.addon.structure.AsyncBuildManager.isKubeJSPlayerBlueprint(kbSrc);
            if (!kubeJSBlueprint) {
                for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                    ItemStack s = player.getInventory().getItem(i);
                    if (!s.isEmpty() && com.prefab.addon.structure.AsyncBuildManager.isKubeJSPlayerBlueprint(s)) {
                        kubeJSBlueprint = true;
                        break;
                    }
                }
            }
            if (kubeJSBlueprint) {
                consumeMaterials = false;
                PrefabCustomAddon.LOGGER.info("[PREVIEW-BUILD] KubeJS player blueprint detected, materials exempted");
            }
        }
        PrefabCustomAddon.LOGGER.info("[PREVIEW-BUILD] ALT build attempt: pack={}/{} consumeMaterials={}",
            packName, constructionId, consumeMaterials);
        if (consumeMaterials) {
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

        // 建造不再要求持有/绑定自定义蓝图: 任何玩家从建筑列表选中建筑即可进入建造流程
        // (生存交材料建一次, 创造直接建). KubeJS 联动蓝图物品 (player_blueprint) 功能保留,
        // 但建造流程不再检查/绑定/劫持玩家已有的蓝图.

        // 建造完后清空该建筑的提交进度 (挑战模式)
        com.prefab.addon.work.ChallengeSessionManager.reset(player.getUUID(), constructionId);

        PrefabCustomAddon.LOGGER.info("[PREVIEW-BUILD] ALT pressed, sending BuildCustomStructurePayload for {}/{} at {}",
            packName, constructionId, cfg.pos);

        com.prefab.addon.network.NetworkHandler.sendToServer(
            new com.prefab.addon.network.BuildCustomStructurePayload(
                cfg.pos, packName, constructionId, cfg.houseFacing,
                com.prefab.addon.config.PlayerPreferences.get().getBuildAnimationMode()));
        // 清预览: prefab 的 currentStructure (no-op, 之前已 null) + 我们 own 的 ADDON_PREVIEW_*
        StructureRenderHandler.setStructure(null, null);
        com.prefab.addon.client.gui.CustomStructureGui.clearAddonPreviewFlag();
    }

    /**
     * ALT 在外包建筑预览中按下时, 走外包建筑专用的 build 路径 (从 1.21.1 移植).
     *
     * <p>与 {@link #triggerBuildAtPreview} 的区别:</p>
     * <ul>
     *   <li>packName/constructionId 不来自 currentConstruction, 而是 CustomStructureGui
     *       里 setOutsourceContext 存的 buildingId + styleIndex (GuiOutsourceBuildingDetail 设置).</li>
     *   <li>发的是 {@link com.prefab.addon.network.BuildOutsourceStructurePayload},
     *       服务端走 OutsourceBuildManager.placeStructure, 任务完成后消耗对应的
     *       OutsourceBlueprintItem (按 buildingId 精确匹配).</li>
     * </ul>
     */
    private static void triggerOutsourceBuildAtPreview(StructureConfiguration cfg) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        lastAction = GLFW.GLFW_KEY_LEFT_ALT;

        String buildingId = com.prefab.addon.client.gui.CustomStructureGui.getCurrentOutsourceBuildingId();
        int styleIndex = com.prefab.addon.client.gui.CustomStructureGui.getCurrentOutsourceStyleIndex();
        if (buildingId == null || buildingId.isEmpty()) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "⚠ 外包建筑上下文丢失, 请重新打开蓝图右键").withStyle(net.minecraft.ChatFormatting.RED));
            PrefabCustomAddon.LOGGER.warn("[OUTSOURCE-BUILD] ALT 触发时 buildingId 为空 (setOutsourceContext 没被调?)");
            return;
        }
        // 校验背包里确实有对应的 OutsourceBlueprintItem (防止 GUI 直开但蓝图不在)
        ItemStack blueprint = ItemStack.EMPTY;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s.isEmpty() || !(s.getItem() instanceof com.prefab.addon.items.OutsourceBlueprintItem)) continue;
            if (buildingId.equals(com.prefab.addon.items.OutsourceBlueprintItem.getEffectiveBuildingId(s))) {
                blueprint = s;
                break;
            }
        }
        if (blueprint.isEmpty()) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "⚠ 背包里没有对应的外包建筑蓝图 (buildingId=" + buildingId + ")")
                .withStyle(net.minecraft.ChatFormatting.RED));
            return;
        }
        PrefabCustomAddon.LOGGER.info(
            "[OUTSOURCE-BUILD] ALT pressed, sending BuildOutsourceStructurePayload for buildingId={} style={}/{} at {} facing {}",
            buildingId, styleIndex, blueprint.getCount(), cfg.pos, cfg.houseFacing);
        com.prefab.addon.network.NetworkHandler.sendToServer(
            new com.prefab.addon.network.BuildOutsourceStructurePayload(
                buildingId, styleIndex, cfg.pos, cfg.houseFacing,
                com.prefab.addon.config.PlayerPreferences.get().getBuildAnimationMode()));
        // 清预览: prefab 的 currentStructure (no-op, 之前已 null) + 我们 own 的 ADDON_PREVIEW_*
        // (clearAddonPreviewFlag 同时会清掉 outsource 上下文)
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

        com.wuest.prefab.structures.messages.StructureTagMessage.EnumStructureConfiguration enumConfig =
            com.wuest.prefab.structures.messages.StructureTagMessage.EnumStructureConfiguration
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

        com.wuest.prefab.structures.messages.StructureTagMessage msg =
            new com.wuest.prefab.structures.messages.StructureTagMessage(
                cfg.WriteToCompoundTag(), enumConfig);

        PrefabCustomAddon.LOGGER.info(
            "[PREVIEW-BUILD] ALT (prefab original) sending STRUCTURE_BUILD for {} at {} facing {}",
            enumConfig, cfg.pos, cfg.houseFacing);

        // 1.20.1 Prefab: 直接往 Prefab.network (SimpleChannel) 发 StructureTagMessage
        com.wuest.prefab.Prefab.network.sendToServer(msg);

        // 清预览
        StructureRenderHandler.setStructure(null, null);
    }

    /**
     * prefab 原版建筑预览时, 强制 prefab 下一帧按当前 cfg 重新渲染.
     * <p>不调这个, 玩家移动/旋转后只看到黄色框在动, 实际结构不动 —
     * prefab 1.20.1 渲染用的 {@code BuildBlock.blockPos/centerOfBlock} 是懒缓存,
     * 首帧按当时 cfg.pos 算好存字段后每帧直接复用, 不再读 cfg.pos.</p>
     *
     * <p>实现: 先 {@link #resetPrefabBlockLazyCache} 清懒缓存, 再
     * {@code setStructure(currentStructure, currentConfiguration)} 重新登记.
     * 注意 1.20.1 的 setStructure 只赋静态字段, 没有 needsRebuild/blockModelQuads
     * 那套重建逻辑 (那是 1.21.1 prefab 的行为), 清缓存才是关键.</p>
     *
     * <p>副作用: setStructure 会把 {@code showedMessage} 改回 {@code false},
     * 下次 render prefab 会再次发 "右击任何方块即可移除预览" / "黄色轮廓是您单击的块"
     * 两条聊天消息. 调完后立即改回 {@code true}, prefab 就不会重复发了.</p>
     */
    private static void triggerPrefabRebuild() {
        // 关键: prefab 原版预览时, 我们的 ADDON 字段是 null, prefab.currentStructure
        // 才是真的 structure. (我们自己的预览时, prefab.currentStructure = null 一直, 调它就是 no-op)
        StructureConfiguration cfg = StructureRenderHandler.currentConfiguration;
        Structure structure = StructureRenderHandler.currentStructure;
        if (cfg == null || structure == null) return;
        resetPrefabBlockLazyCache(structure, false);
        StructureRenderHandler.setStructure(structure, cfg);
        StructureRenderHandler.showedMessage = true;
    }

    /**
     * 清空 prefab Structure 里每个 BuildBlock 的懒缓存字段.
     *
     * <p>prefab 1.20.1 的 {@code StructureRenderHandler.newRenderPlayerLook} 每帧渲染时:</p>
     * <ul>
     *   <li>{@code block.blockPos == null} 时才按 {@code cfg.pos} + {@code cfg.houseFacing}
     *       经 {@code PositionOffset.getRelativePosition} 重算绝对坐标, 算完存进字段;</li>
     *   <li>{@code block.centerOfBlock == null} 时才按 blockPos 重算 (raytrace 用);</li>
     *   <li>{@code block.getBlockState() == null} 时才用 {@code BuildBlock.SetBlockState}
     *       按 {@code cfg.pos}/{@code cfg.houseFacing} 重算方块朝向属性.</li>
     * </ul>
     * 置 null 后 prefab 渲染会走这些重算路径, 预览位置/朝向立即跟上新的 cfg.
     *
     * @param clearState true 时连 blockState 一起清 (旋转用: 让朝向方块按新 houseFacing 重算);
     *                   false 只清位置缓存 (移动用: 相对位置不变, state 无需重算).
     */
    private static void resetPrefabBlockLazyCache(Structure structure, boolean clearState) {
        if (structure == null || structure.getBlocks() == null) return;
        for (com.wuest.prefab.structures.base.BuildBlock block : structure.getBlocks()) {
            clearPrefabBlockCache(block, clearState);
            // subBlock (如楼梯的下半块等复合方块) 也是 BuildBlock, 同样有懒缓存.
            if (block.getSubBlock() != null) {
                clearPrefabBlockCache(block.getSubBlock(), clearState);
            }
        }
    }

    private static void clearPrefabBlockCache(com.wuest.prefab.structures.base.BuildBlock block, boolean clearState) {
        block.blockPos = null;
        block.centerOfBlock = null;
        if (clearState) {
            block.setBlockState(null);
        }
    }
}
