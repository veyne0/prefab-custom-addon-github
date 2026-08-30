package com.prefab.addon.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.items.MiniBuildingConverterItem;
import com.prefab.addon.work.RegionSelector;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.lwjgl.glfw.GLFW;

/**
 * 监听玩家在世界中左键/右键方块, 用来在"游戏中选区"模式下选角点.
 *
 * 任何时候只要有玩家在选区模式中:
 *   - 客户端左键方块 → 设置 pos1
 *   - 客户端右键方块 → 设置 pos2
 *   - 玩家退游戏 → 清理选区状态
 *
 * 渲染阶段画 AABB 框, 让玩家看到当前区域.
 */
@EventBusSubscriber(modid = PrefabCustomAddon.MOD_ID, value = Dist.CLIENT)
public class RegionSelectorEventHandler {

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        // 服务端事件 - 我们在客户端监听, 但事件会发到两边. 客户端处理:
        // 注意: 1.21.1 客户端 LeftClickBlock 不一定触发, 主要拦截在 mixin (MinecraftStartAttackMixin)
        Player player = event.getEntity();
        if (player.level().isClientSide && RegionSelector.isActive(player)) {
            // 临时解锁 (按住 SHIFT) 时不拦截, 让玩家正常挖方块
            if (RegionSelector.isTempUnlocked(player)) {
                return;
            }
            // 在客户端取消该事件, 防止破坏方块
            event.setCanceled(true);
            BlockPos pos = event.getPos();
            RegionSelector.onLeftClick(player, pos);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        // 1.21.1 中 RightClickBlock 在 server 触发, client 端不触发
        // 但因为 client 端用 hitResult 选点 (支持空气), 我们在 onClientTick 处理选点逻辑
        // 这里只阻止放方块/用物品 (通过 setCanceled)
        Player player = event.getEntity();
        if (player.level().isClientSide) {
            // 客户端: 仅选点 (支持方块), 空气由 onClientTick 处理
            if (RegionSelector.isActive(player)) {
                // 临时解锁 (按住 SHIFT) 时不拦截, 让玩家正常用物品/开方块
                if (RegionSelector.isTempUnlocked(player)) {
                    return;
                }
                event.setCanceled(true);
                BlockPos pos = event.getPos();
                if (pos != null) {
                    RegionSelector.onRightClick(player, pos);
                }
            }
        } else {
            // 服务端: 阻止放方块/用物品
            if (player == Minecraft.getInstance().player && RegionSelector.isActive(player)
                && !RegionSelector.isTempUnlocked(player)) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onRightClickEmpty(PlayerInteractEvent.RightClickEmpty event) {
        // 鼠标右键到空气 - 选点
        // 1.21.1 RightClickEmpty 不可取消, 我们只能监听 (不阻止 vanilla useItem)
        // 实际选点由 onMouseButtonPre 处理, 这里不做任何事
    }

    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // 玩家退游戏时清理
        if (Minecraft.getInstance().player != null) {
            RegionSelector.cancel(Minecraft.getInstance().player);
        }
    }

    /**
     * 鼠标按键事件: 在 RegionSelector 激活时, 把左键/右键重定向为"选角点",
     * 防止走 vanilla startAttack/startUseItem (即便 mixin 拦了 startAttack, 仍保险).
     *
     * 1.21.1 NeoForge InputEvent.MouseButton.Pre 在 mouseClicked/mouseReleased 前发,
     * setCanceled 阻止 vanilla 处理.
     */
    @SubscribeEvent
    public static void onMouseButtonPre(net.neoforged.neoforge.client.event.InputEvent.MouseButton.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.screen != null) return;  // 开了 GUI 时不处理
        if (!RegionSelector.isActive(mc.player)) return;

        // 临时解锁 (按住 SHIFT) 时不重定向鼠标, 让玩家正常挖方块/用物品
        if (RegionSelector.isTempUnlocked(mc.player)) {
            return;
        }

        int button = event.getButton();
        boolean pressed = event.getAction() == 1;  // GLFW_PRESS = 1, GLFW_RELEASE = 0
        if (!pressed) return;  // 只处理按下, 不处理松开 (松开不需要)

        // GLFW_MOUSE_BUTTON_LEFT = 0, GLFW_MOUSE_BUTTON_RIGHT = 1
        if (button == org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            event.setCanceled(true);
            // 用 hitResult 选点 (支持空气)
            BlockPos pos = resolveHitPos(mc);
            if (pos != null) {
                RegionSelector.onLeftClick(mc.player, pos);
            }
        } else if (button == org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            event.setCanceled(true);
            BlockPos pos = resolveHitPos(mc);
            if (pos != null) {
                RegionSelector.onRightClick(mc.player, pos);
            }
        }
    }

    /**
     * 从 hitResult 解析 BlockPos: BLOCK 类型用 blockPos, MISS/ENTITY 用 location 转换.
     */
    private static BlockPos resolveHitPos(Minecraft mc) {
        if (mc.hitResult == null) return null;
        if (mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            return ((net.minecraft.world.phys.BlockHitResult) mc.hitResult).getBlockPos();
        }
        if (mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
            return BlockPos.containing(mc.hitResult.getLocation());
        }
        // ENTITY 命中: 用 entity 位置
        if (mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.ENTITY) {
            net.minecraft.world.phys.EntityHitResult er = (net.minecraft.world.phys.EntityHitResult) mc.hitResult;
            return er.getEntity().blockPosition();
        }
        return null;
    }

    /**
     * 监听输入事件: ALT 键确认导出 NBT, CTRL 键取消.
     * 1.21.1 的键盘事件: 监听 {@link net.neoforged.neoforge.client.event.InputEvent.Key} 不可用, 改用
     * {@link com.mojang.blaze3d.platform.InputConstants} + 客户端 tick.
     */
    @net.neoforged.bus.api.SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.NORMAL)
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.screen != null) return;  // 开了 GUI 时不处理, 避免冲突

        // ---- 全局热重载快捷键: CTRL+R 手动重新扫描拓展包 ----
        // 每帧检查, 加节流 (避免按住 R 时每帧 reload)
        long window = mc.getWindow().getWindow();
        boolean ctrlDown = com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL)
                        || com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
        boolean rDown = com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_R);
        if (ctrlDown && rDown) {
            // 用 System.nanoTime() 代替 long tick 计数器, 更精确
            long nowNs = System.nanoTime();
            if (nowNs - lastManualReloadNs > 1_500_000_000L) {  // 1.5s 节流
                lastManualReloadNs = nowNs;
                com.prefab.addon.PrefabCustomAddon.LOGGER.info("[HOT-RELOAD] 玩家按 CTRL+R, 手动重新扫描拓展包");
                com.prefab.addon.extension.ExtensionPackManager.getInstance().reloadClient();
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                            com.prefab.addon.PrefabCustomAddon.tr("sel.pack_rescanned")), true);
                }
            }
        }

        // ---- 迷你建筑转换器: 自动进入选区模式 ----
        // 玩家手持转换器时, 自动启动 MINI_BUILDING_CAPTURE 模式 → 显示黄/绿/红框
        // 玩家放下转换器时, 自动取消迷你模式 (BLUEPRINT_EXPORT 模式不受影响, 由 GUI 控制)
        ItemStack mainHand = mc.player.getMainHandItem();
        ItemStack offHand = mc.player.getOffhandItem();
        boolean holdingConverter = mainHand.getItem() instanceof MiniBuildingConverterItem
                                || offHand.getItem() instanceof MiniBuildingConverterItem;
        RegionSelector.SelectionState curSt = RegionSelector.getState(mc.player);
        if (holdingConverter) {
            if (curSt == null || curSt.mode != RegionSelector.Mode.MINI_BUILDING_CAPTURE) {
                // 还没进入迷你模式, 或当前是 BLUEPRINT 模式 (不应该在持有转换器时)
                RegionSelector.startMiniBuilding(mc.player);
            }
        } else {
            if (curSt != null && curSt.mode == RegionSelector.Mode.MINI_BUILDING_CAPTURE) {
                // 放下转换器 → 退出迷你模式
                RegionSelector.cancel(mc.player);
            }
        }

        if (!RegionSelector.isActive(mc.player)) return;

        // 临时解锁 (按住 SHIFT) 时, 不重定向鼠标, 让玩家正常挖方块/用物品
        // 用于解决"想挖开区域来定对角点但挖不动"的问题 (1.2.0 反馈)
        boolean shiftDown = com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT)
                         || com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
        RegionSelector.SelectionState selSt = RegionSelector.getState(mc.player);
        if (selSt != null && shiftDown != selSt.tempUnlocked) {
            selSt.tempUnlocked = shiftDown;
            if (shiftDown) {
                mc.player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                        "§6[选择模式] §e临时解锁, 可正常挖方块/用物品 §7(松开 SHIFT 恢复选点)"),
                    true);
            } else {
                mc.player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal("§a[选择模式] §7恢复选点模式"),
                    true);
            }
        }
        if (shiftDown) {
            // 临时解锁中: 跳过阻止破坏的逻辑, 让 vanilla 正常处理
            return;
        }

        // 阻止创造模式左键破坏方块: PlayerInteractEvent.LeftClickBlock 在客户端不触发,
        // 需要手动调 gameMode.stopDestroyBlock() 取消进行中的破坏.
        if (mc.gameMode != null && mc.gameMode.isDestroying()) {
            mc.gameMode.stopDestroyBlock();
        }

        // 先处理 CTRL+R 热重载 (优先级高于 CTRL 取消, 防止误触)
        if (ctrlDown && rDown) {
            long nowNs = System.nanoTime();
            if (nowNs - lastManualReloadNs > 1_500_000_000L) {  // 1.5s 节流
                lastManualReloadNs = nowNs;
                com.prefab.addon.PrefabCustomAddon.LOGGER.info("[HOT-RELOAD] 玩家按 CTRL+R, 手动重新扫描拓展包");
                com.prefab.addon.extension.ExtensionPackManager.getInstance().reloadClient();
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                            com.prefab.addon.PrefabCustomAddon.tr("sel.pack_rescanned")), true);
                }
            }
            return;
        }

        // CTRL 取消 (用 CTRL 不用 ESC, 避免和原版暂停菜单冲突)
        if (ctrlDown) {
            RegionSelector.cancel(mc.player);
            return;
        }
        // ALT 确认 (左 ALT 或右 ALT 都可以)
        if (com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT)
            || com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT)) {
            RegionSelector.confirm(mc.player);
        }
    }

    /** 上次手动热重载时间 (纳秒), 防止按住 CTRL+R 重复触发 */
    private static long lastManualReloadNs = 0L;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // 阶段: AFTER_ENTITIES 阶段 MultiBufferSource 还没被 endBatch, line buffer 还能提交.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!RegionSelector.isActive(mc.player)) return;

        RegionSelector.SelectionState st = RegionSelector.getState(mc.player);
        if (st == null) return;

        // 用 Create Outliner 类似的方案: Tesselator.begin(Mode, VertexFormat) 拿 BufferBuilder
        // -> build() 拿 MeshData -> BufferUploader.drawWithShader 提交.
        com.mojang.blaze3d.vertex.Tesselator tess = com.mojang.blaze3d.vertex.Tesselator.getInstance();
        com.mojang.blaze3d.vertex.BufferBuilder bb = tess.begin(
                com.mojang.blaze3d.vertex.VertexFormat.Mode.DEBUG_LINES,
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);

        // PoseStack 在 RenderLevelStageEvent 阶段已经乘过 view matrix, 所以需要先 pushPose + translate -camPos
        // 然后 addVertex 用世界坐标就会被变换到 view-space
        PoseStack pose = event.getPoseStack();
        org.joml.Matrix4f poseMat;
        pose.pushPose();
        try {
            net.minecraft.world.phys.Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
            pose.translate(-cam.x, -cam.y, -cam.z);
            poseMat = pose.last().pose();

            try {
                // 整体 AABB (黄) - pos1 + pos2 都选好时
                if (st.bothSelected()) {
                    BlockPos mn = st.getMin();
                    BlockPos mx = st.getMax();
                    drawBoxWireframe(bb, poseMat,
                        (float) mn.getX(),  (float) mn.getY(),  (float) mn.getZ(),
                        (float) (mx.getX() + 1.0), (float) (mx.getY() + 1.0), (float) (mx.getZ() + 1.0),
                        1.0f, 0.9f, 0.2f, 1.0f);
                }
                // pos1 小框 (绿)
                if (st.pos1 != null) {
                    drawBoxWireframe(bb, poseMat,
                        (float) st.pos1.getX(), (float) st.pos1.getY(), (float) st.pos1.getZ(),
                        (float) (st.pos1.getX() + 1.0), (float) (st.pos1.getY() + 1.0), (float) (st.pos1.getZ() + 1.0),
                        0.2f, 1.0f, 0.2f, 1.0f);
                }
                // pos2 小框 (红)
                if (st.pos2 != null) {
                    drawBoxWireframe(bb, poseMat,
                        (float) st.pos2.getX(), (float) st.pos2.getY(), (float) st.pos2.getZ(),
                        (float) (st.pos2.getX() + 1.0), (float) (st.pos2.getY() + 1.0), (float) (st.pos2.getZ() + 1.0),
                        1.0f, 0.3f, 0.2f, 1.0f);
                }
                // 指示器小框 (白) - 鼠标所指方块/位置, 方便选空气方块
                if (mc.hitResult != null) {
                    BlockPos crossPos = ((net.minecraft.world.phys.HitResult) mc.hitResult).getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                        ? ((net.minecraft.world.phys.BlockHitResult) mc.hitResult).getBlockPos()
                        : BlockPos.containing(((net.minecraft.world.phys.HitResult) mc.hitResult).getLocation());
                    drawBoxWireframe(bb, poseMat,
                        (float) crossPos.getX(), (float) crossPos.getY(), (float) crossPos.getZ(),
                        (float) (crossPos.getX() + 1.0), (float) (crossPos.getY() + 1.0), (float) (crossPos.getZ() + 1.0),
                        1.0f, 1.0f, 1.0f, 0.6f);
                }
            } catch (Throwable t) {
                PrefabCustomAddon.LOGGER.warn("[REGION-SELECT] Render error: {}", t.getMessage());
            }
        } finally {
            pose.popPose();
        }

        // 提交 (build 返回 MeshData, 可空)
        com.mojang.blaze3d.vertex.MeshData meshData = bb.build();
        if (meshData == null) {
            PrefabCustomAddon.LOGGER.debug("[REGION-SELECT] bb.build() returned null (no vertices?)");
            return;
        }

        try {
            // 用 vanilla POSITION_COLOR shader 渲染
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
            com.mojang.blaze3d.systems.RenderSystem.disableCull();
            com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();   // 线总在最前, 方便看选区
            com.mojang.blaze3d.systems.RenderSystem.depthMask(false);
            com.mojang.blaze3d.systems.RenderSystem.setShader(
                net.minecraft.client.renderer.GameRenderer::getPositionColorShader);
            com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(meshData);
            PrefabCustomAddon.LOGGER.debug("[REGION-SELECT] Drew AABB box");
        } finally {
            com.mojang.blaze3d.systems.RenderSystem.enableCull();
            com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
            com.mojang.blaze3d.systems.RenderSystem.depthMask(true);
            meshData.close();
        }
    }

    /**
     * 画 AABB 的 12 条边线. 使用 PoseStack.last().pose() 变换矩阵, 直接用 BufferBuilder.addVertex.
     */
    private static void drawBoxWireframe(com.mojang.blaze3d.vertex.BufferBuilder bb, org.joml.Matrix4f m,
                                         float x0, float y0, float z0,
                                         float x1, float y1, float z1,
                                         float r, float g, float b, float a) {
        // 底面 4 条
        line(bb, m, x0, y0, z0, x1, y0, z0, r, g, b, a);
        line(bb, m, x1, y0, z0, x1, y0, z1, r, g, b, a);
        line(bb, m, x1, y0, z1, x0, y0, z1, r, g, b, a);
        line(bb, m, x0, y0, z1, x0, y0, z0, r, g, b, a);
        // 顶面 4 条
        line(bb, m, x0, y1, z0, x1, y1, z0, r, g, b, a);
        line(bb, m, x1, y1, z0, x1, y1, z1, r, g, b, a);
        line(bb, m, x1, y1, z1, x0, y1, z1, r, g, b, a);
        line(bb, m, x0, y1, z1, x0, y1, z0, r, g, b, a);
        // 4 条柱
        line(bb, m, x0, y0, z0, x0, y1, z0, r, g, b, a);
        line(bb, m, x1, y0, z0, x1, y1, z0, r, g, b, a);
        line(bb, m, x1, y0, z1, x1, y1, z1, r, g, b, a);
        line(bb, m, x0, y0, z1, x0, y1, z1, r, g, b, a);
    }

    private static void line(com.mojang.blaze3d.vertex.BufferBuilder bb, org.joml.Matrix4f m,
                              float x0, float y0, float z0,
                              float x1, float y1, float z1,
                              float r, float g, float b, float a) {
        // DEBUG_LINES mode + POSITION_COLOR format: 只设 color, 不用 normal
        bb.addVertex(m, x0, y0, z0).setColor(r, g, b, a);
        bb.addVertex(m, x1, y1, z1).setColor(r, g, b, a);
    }
}



