/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.prefab.PrefabBase
 *  com.prefab.network.ClientToServerTypes
 *  com.prefab.structures.base.Structure
 *  com.prefab.structures.config.StructureConfiguration
 *  com.prefab.structures.messages.StructureTagMessage
 *  com.prefab.structures.messages.StructureTagMessage$EnumStructureConfiguration
 *  net.minecraft.ChatFormatting
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.player.LocalPlayer
 *  net.minecraft.core.BlockPos
 *  net.minecraft.core.Direction
 *  net.minecraft.network.chat.Component
 *  net.minecraft.world.item.ItemStack
 *  net.neoforged.api.distmarker.Dist
 *  net.neoforged.bus.api.SubscribeEvent
 *  net.neoforged.fml.common.EventBusSubscriber
 *  net.neoforged.neoforge.client.event.ClientTickEvent$Post
 *  org.lwjgl.glfw.GLFW
 */
package com.prefab.addon.client;

import com.prefab.PrefabBase;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.client.CustomBlueprintClientHandler;
import com.prefab.addon.client.EditModeController;
import com.prefab.addon.client.MultiblockPreview;
import com.prefab.addon.client.gui.CustomStructureGui;
import com.prefab.addon.cloud.CloudBuilding;
import com.prefab.addon.cloud.CloudBuildingClientCache;
import com.prefab.addon.cloud.CloudBuildingSummonPayload;
import com.prefab.addon.cloud.CloudPreview;
import com.prefab.addon.config.PlayerPreferences;
import com.prefab.addon.extension.ConstructionInfo;
import com.prefab.addon.extension.ExtensionPackManager;
import com.prefab.addon.items.CustomBlueprintItem;
import com.prefab.addon.items.OutsourceBlueprintItem;
import com.prefab.addon.multiblock.MultiblockCatalog;
import com.prefab.addon.multiblock.MultiblockShapeData;
import com.prefab.addon.network.BindConstructionPayload;
import com.prefab.addon.network.BuildCustomStructurePayload;
import com.prefab.addon.network.BuildOutsourceStructurePayload;
import com.prefab.addon.network.MultiblockSummonPayload;
import com.prefab.addon.network.NetworkHandler;
import com.prefab.addon.structure.AsyncBuildManager;
import com.prefab.addon.structure.CustomStructureBuilder;
import com.prefab.addon.work.ChallengeSessionManager;
import com.prefab.addon.work.MaterialCalculator;
import com.prefab.network.ClientToServerTypes;
import com.prefab.structures.base.Structure;
import com.prefab.structures.config.StructureConfiguration;
import com.prefab.structures.messages.StructureTagMessage;
import com.prefab.structures.render.StructureRenderHandler;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid="prefab_custom_addon", value={Dist.CLIENT})
public class StructurePreviewKeyHandler {
    private static int lastAction = -1;
    private static final long MOVE_INTERVAL_MS = 150L;
    private static long lastMoveTimeMs = 0L;
    private static boolean lastRightDown = false;
    private static int addonPreviewNoticeStructureHash = 0;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        boolean altDown;
        long now;
        boolean canMove;
        boolean isPrefabOriginalPreview;
        boolean isAddonPreview;
        boolean anyPreviewActive;
        if (EditModeController.isEditing()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        // 取消键: KeyMapping (默认鼠标右键), 玩家可改键
        boolean rightDownEarly = PackBrowserKeyHandler.isKeyDown(PackBrowserKeyHandler.CANCEL_PREVIEW);
        boolean bl = anyPreviewActive = CustomStructureGui.getAddonPreviewStructure() != null || StructureRenderHandler.currentStructure != null;
        if (anyPreviewActive && rightDownEarly && !lastRightDown && !EditModeController.shouldBlockPreviewCancel()) {
            if (mc.screen != null) {
                mc.player.closeContainer();
                mc.setScreen(null);
            }
            if (MultiblockPreview.isActive()) {
                mc.player.sendSystemMessage((Component)Component.literal((String)"\u2717 \u5df2\u53d6\u6d88\u591a\u65b9\u5757\u9884\u89c8").withStyle(ChatFormatting.YELLOW));
            } else if (CloudPreview.isActive()) {
                mc.player.sendSystemMessage((Component)Component.literal((String)"\u2717 \u5df2\u53d6\u6d88\u4e91\u7aef\u5efa\u7b51\u9884\u89c8").withStyle(ChatFormatting.YELLOW));
            } else {
                mc.player.sendSystemMessage((Component)Component.literal((String)"\u2717 \u5df2\u53d6\u6d88\u9884\u89c8").withStyle(ChatFormatting.YELLOW));
            }
            MultiblockPreview.cancel();
            CloudPreview.cancel();
            CustomStructureGui.clearAddonPreviewFlag();
            StructureRenderHandler.setStructure(null, null);
            addonPreviewNoticeStructureHash = 0;
            lastRightDown = true;
            PrefabCustomAddon.LOGGER.info("[PREVIEW-CANCEL] \u53f3\u952e\u53d6\u6d88\u9884\u89c8 (\u65e9\u4e8e screen \u68c0\u67e5, \u5f53\u524d screen={})", (Object)(mc.screen == null ? "null" : mc.screen.getClass().getSimpleName()));
            return;
        }
        lastRightDown = rightDownEarly;
        if (mc.screen != null) {
            return;
        }
        Structure currentStructure = CustomStructureGui.getAddonPreviewStructure();
        StructureConfiguration cfg = CustomStructureGui.getAddonPreviewConfig();
        boolean bl2 = isAddonPreview = currentStructure != null && cfg != null;
        if (!isAddonPreview) {
            currentStructure = StructureRenderHandler.currentStructure;
            cfg = StructureRenderHandler.currentConfiguration;
        }
        if (cfg == null || cfg.pos == null || currentStructure == null) {
            CustomStructureGui.clearAddonPreviewFlag();
            addonPreviewNoticeStructureHash = 0;
            CloudPreview.cancel();
            MultiblockPreview.cancel();
            lastRightDown = false;
            return;
        }
        long window = mc.getWindow().getWindow();
        boolean bl3 = isPrefabOriginalPreview = !isAddonPreview;
        if (isPrefabOriginalPreview) {
            int hash = System.identityHashCode(currentStructure);
            if (hash != addonPreviewNoticeStructureHash) {
                addonPreviewNoticeStructureHash = hash;
                mc.player.sendSystemMessage((Component)Component.literal((String)("\u2139 \u8be5\u9884\u89c8\u6a21\u5f0f\u7531\u9644\u5c5e\u6a21\u7ec4\u63d0\u4f9b: " + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_FORWARD, "\u2191") + "/" + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_BACK, "\u2193") + "/" + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_LEFT, "\u2190") + "/" + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_RIGHT, "\u2192") + " \u79fb\u52a8, " + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_ROTATE, "CTRL") + " \u65cb\u8f6c, " + PackBrowserKeyHandler.buildKeyName() + " \u5efa\u9020, " + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.CANCEL_PREVIEW, "\u53f3\u952e") + " \u53d6\u6d88")).withStyle(ChatFormatting.AQUA));
            }
        } else {
            addonPreviewNoticeStructureHash = 0;
        }
        // 以下操作键全部读 KeyMapping (默认值 = 原硬轮询键, 行为不变), 玩家可在 选项→控制→按键绑定 改键
        boolean shiftDown = PackBrowserKeyHandler.isKeyDown(PackBrowserKeyHandler.PREVIEW_FAST_MOVE);
        boolean ctrlDown = PackBrowserKeyHandler.isKeyDown(PackBrowserKeyHandler.PREVIEW_ROTATE);
        int step = shiftDown ? 5 : 1;
        Direction playerFacing = mc.player.getNearestViewDirection();
        if (playerFacing == Direction.UP || playerFacing == Direction.DOWN) {
            playerFacing = Direction.NORTH;
        }
        Direction forward = playerFacing;
        Direction back = playerFacing.getOpposite();
        Direction left = playerFacing.getCounterClockWise();
        Direction right = playerFacing.getClockWise();
        int dx = 0;
        int dz = 0;
        int dy = 0;
        if (PackBrowserKeyHandler.isKeyDown(PackBrowserKeyHandler.PREVIEW_LEFT)) {
            dx += left.getStepX();
            dz += left.getStepZ();
        }
        if (PackBrowserKeyHandler.isKeyDown(PackBrowserKeyHandler.PREVIEW_RIGHT)) {
            dx += right.getStepX();
            dz += right.getStepZ();
        }
        if (PackBrowserKeyHandler.isKeyDown(PackBrowserKeyHandler.PREVIEW_FORWARD)) {
            dx += forward.getStepX();
            dz += forward.getStepZ();
        }
        if (PackBrowserKeyHandler.isKeyDown(PackBrowserKeyHandler.PREVIEW_BACK)) {
            dx += back.getStepX();
            dz += back.getStepZ();
        }
        if (PackBrowserKeyHandler.isKeyDown(PackBrowserKeyHandler.PREVIEW_RAISE)
                || GLFW.glfwGetKey((long)window, (int)61) == 1) {  // 主排 =/+ 键 (次要绑定, 不可改)
            ++dy;
        }
        if (PackBrowserKeyHandler.isKeyDown(PackBrowserKeyHandler.PREVIEW_LOWER)
                || GLFW.glfwGetKey((long)window, (int)333) == 1) {  // 小键盘 - (次要绑定, 不可改)
            --dy;
        }
        boolean bl4 = canMove = (now = System.currentTimeMillis()) - lastMoveTimeMs >= 150L;
        if (canMove && (dx != 0 || dz != 0 || dy != 0)) {
            BlockPos newPos;
            BlockPos oldPos = cfg.pos;
            cfg.pos = newPos = oldPos.offset(dx * step, dy * step, dz * step);
            if (isAddonPreview) {
                CustomStructureBuilder.offsetStructureBlocks(currentStructure, newPos, cfg.houseFacing);
            } else {
                StructureRenderHandler.setStructure(currentStructure, cfg);
                StructureRenderHandler.showedMessage = true;
            }
            lastMoveTimeMs = now;
            PrefabCustomAddon.LOGGER.info("[PREVIEW-MOVE] {} player={} dx={} dz={} dy={} step={}  {} -> {}", new Object[]{isAddonPreview ? "addon" : "prefab", playerFacing, dx, dz, dy, step, oldPos, newPos});
            return;
        }
        if (canMove && ctrlDown) {
            Direction newFacing = StructurePreviewKeyHandler.rotateCounterClockwise(cfg.houseFacing);
            Direction oldFacing = cfg.houseFacing;
            cfg.houseFacing = newFacing;
            if (isAddonPreview) {
                CustomStructureBuilder.offsetStructureBlocks(currentStructure, cfg.pos, cfg.houseFacing);
            } else {
                StructureRenderHandler.setStructure(currentStructure, cfg);
                StructureRenderHandler.showedMessage = true;
            }
            lastMoveTimeMs = now;
            PrefabCustomAddon.LOGGER.info("[PREVIEW-ROTATE] {} houseFacing {} -> {}", new Object[]{isAddonPreview ? "addon" : "prefab", oldFacing, newFacing});
        }
        // 建造键: 注册成 KeyMapping (默认左 ALT), 玩家可在 选项→控制→按键绑定 改键
        boolean bl5 = altDown = PackBrowserKeyHandler.BUILD_AT_PREVIEW != null && PackBrowserKeyHandler.BUILD_AT_PREVIEW.isDown();
        if (altDown && lastAction != 342 && lastAction != 346) {
            if (EditModeController.isEditing()) {
                EditModeController.saveAndExit();
                lastAction = 342;
                return;
            }
            if (MultiblockPreview.isActive()) {
                StructurePreviewKeyHandler.triggerMultiblockSummon(cfg);
            } else if (CloudPreview.isActive()) {
                StructurePreviewKeyHandler.triggerCloudSummon(cfg);
            } else if (CustomStructureGui.isCurrentOutsource()) {
                StructurePreviewKeyHandler.triggerOutsourceBuildAtPreview(cfg);
            } else if (!isAddonPreview) {
                StructurePreviewKeyHandler.triggerPrefabOriginalBuild(cfg);
            } else {
                StructurePreviewKeyHandler.triggerBuildAtPreview(cfg, currentStructure);
            }
        }
        if (!altDown) {
            lastAction = -1;
        }
    }

    private static Direction rotateCounterClockwise(Direction current) {
        return switch (current) {
            case Direction.SOUTH -> Direction.EAST;
            case Direction.EAST -> Direction.NORTH;
            case Direction.NORTH -> Direction.WEST;
            case Direction.WEST -> Direction.SOUTH;
            default -> current;
        };
    }

    private static void triggerBuildAtPreview(StructureConfiguration cfg, Structure structure) {
        boolean silent;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        lastAction = 342;
        String packName = CustomStructureGui.getPackNameForBuild();
        String constructionId = CustomStructureGui.getConstructionIdForBuild();
        if (packName.isEmpty() || constructionId.isEmpty()) {
            player.sendSystemMessage((Component)Component.literal((String)"\u26a0 \u627e\u4e0d\u5230\u5f53\u524d\u9884\u89c8\u7684\u5efa\u7b51\u4fe1\u606f! \u8bf7\u91cd\u65b0\u6253\u5f00\u84dd\u56fe\u53f3\u952e").withStyle(ChatFormatting.RED));
            PrefabCustomAddon.LOGGER.warn("[PREVIEW-BUILD] packName/constructionId \u4e3a\u7a7a, GUI \u5df2\u5173\u95ed");
            return;
        }
        // 材料消耗按玩家游戏模式判定: 生存/冒险消耗, 创造免费 (旧的"挑战模式"开关已移除)
        boolean consumeMaterials = !player.isCreative();
        // KubeJS 联动蓝图豁免 (与 GUI 流程一致): 玩家合成的蓝图建造不收材料.
        // 判定优先级跟下面 silent 的确定保持一致: 先看预览来源蓝图, 再退回背包第一个蓝图.
        if (consumeMaterials) {
            ItemStack kbSrc = CustomStructureGui.currentBlueprint;
            boolean kubeJSBlueprint = kbSrc != null && !kbSrc.isEmpty()
                    && AsyncBuildManager.isKubeJSPlayerBlueprint(kbSrc);
            if (!kubeJSBlueprint) {
                for (int i = 0; i < player.getInventory().getContainerSize(); ++i) {
                    ItemStack s = player.getInventory().getItem(i);
                    if (!s.isEmpty() && AsyncBuildManager.isKubeJSPlayerBlueprint(s)) {
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
        PrefabCustomAddon.LOGGER.info("[PREVIEW-BUILD] ALT build attempt: pack={}/{} consumeMaterials={}", new Object[]{packName, constructionId, consumeMaterials});
        if (consumeMaterials) {
            ConstructionInfo info = ExtensionPackManager.getInstance().findConstruction(packName, constructionId);
            if (info == null) {
                player.sendSystemMessage((Component)Component.literal((String)("\u26a0 \u627e\u4e0d\u5230\u5efa\u7b51: " + constructionId)).withStyle(ChatFormatting.RED));
                return;
            }
            try {
                MaterialCalculator.MaterialList matList = MaterialCalculator.calculate(info.getNbtData());
                boolean ready = ChallengeSessionManager.isReady(player.getUUID(), constructionId, matList.required);
                PrefabCustomAddon.LOGGER.info("[PREVIEW-BUILD] Material check: ready={} required={} submitted={}", new Object[]{ready, matList.required, ChallengeSessionManager.getSubmitted(player.getUUID(), constructionId)});
                if (!ready) {
                    int totalRequired = matList.required.values().stream().mapToInt(Integer::intValue).sum();
                    int totalSubmitted = ChallengeSessionManager.getSubmitted(player.getUUID(), constructionId).values().stream().mapToInt(Integer::intValue).sum();
                    player.sendSystemMessage((Component)Component.literal((String)("\u26a0 \u6311\u6218\u6a21\u5f0f: \u5df2\u4ea4 " + totalSubmitted + " / " + totalRequired + " \u4e2a\u6750\u6599, \u8fd8\u5dee " + (totalRequired - totalSubmitted) + " \u4e2a\u624d\u80fd\u5efa\u9020 (\u6309 H \u952e\u6253\u5f00\u63d0\u4ea4\u6750\u6599\u754c\u9762)")).withStyle(ChatFormatting.RED));
                    return;
                }
            }
            catch (Throwable t) {
                PrefabCustomAddon.LOGGER.error("[PREVIEW-BUILD] Material check failed for {}/{}", new Object[]{packName, constructionId, t});
                player.sendSystemMessage((Component)Component.literal((String)"\u26a0 \u6311\u6218\u6a21\u5f0f\u6750\u6599\u68c0\u67e5\u5931\u8d25, \u5df2\u963b\u6b62\u5efa\u9020").withStyle(ChatFormatting.RED));
                return;
            }
        }
        // 终端入口 (建筑终端 → 建筑选择) 不需要蓝图: 跳过蓝图扫描/检查/绑定.
        boolean fromTerminal = CustomStructureGui.isOpenedFromTerminal();
        ItemStack blueprint = ItemStack.EMPTY;
        if (fromTerminal) {
            silent = false;  // 终端直接建造, 没有 KubeJS 蓝图上下文
            PrefabCustomAddon.LOGGER.info("[PREVIEW-BUILD] terminal build (no blueprint required)");
        } else {
            for (int i = 0; i < player.getInventory().getContainerSize(); ++i) {
                ItemStack s = player.getInventory().getItem(i);
                if (!(s.getItem() instanceof CustomBlueprintItem) && !CustomBlueprintClientHandler.isHandledBlueprint(s)) continue;
                blueprint = s;
                break;
            }
            if (blueprint.isEmpty()) {
                player.sendSystemMessage((Component)Component.literal((String)"\u80cc\u5305\u91cc\u6ca1\u6709 \u81ea\u5b9a\u4e49\u84dd\u56fe \u7269\u54c1!").withStyle(ChatFormatting.RED));
                return;
            }
            ItemStack previewSource = CustomStructureGui.currentBlueprint;
            if (previewSource != null && !previewSource.isEmpty()) {
                silent = AsyncBuildManager.isKubeJSPlayerBlueprint(previewSource);
                PrefabCustomAddon.LOGGER.info("[PREVIEW-BUILD] using previewSource (currentBlueprint) for silent check: item={} silent={}", (Object)previewSource.getItem(), (Object)silent);
            } else {
                silent = AsyncBuildManager.isKubeJSPlayerBlueprint(blueprint);
                PrefabCustomAddon.LOGGER.info("[PREVIEW-BUILD] no currentBlueprint tracked, fallback to first-in-inventory: item={} silent={}", (Object)blueprint.getItem(), (Object)silent);
            }
            PrefabCustomAddon.LOGGER.info("[PREVIEW-BUILD] held blueprint (consumption check): item={} (KubeJS\u8054\u52a8\u6a21\u5f0f\u5224\u5b9a: silent={})", (Object)blueprint.getItem(), (Object)silent);
        }
        ChallengeSessionManager.reset(player.getUUID(), constructionId);
        if (!fromTerminal) {
            // 终端入口不绑定蓝图 (没有蓝图可绑, 也不该劫持玩家已有的蓝图)
            NetworkHandler.sendToServer(new BindConstructionPayload(packName, constructionId, false));
        }
        PrefabCustomAddon.LOGGER.info("[PREVIEW-BUILD] ALT pressed, sending BuildCustomStructurePayload for {}/{} at {} silent={}", new Object[]{packName, constructionId, cfg.pos, silent});
        NetworkHandler.sendToServer(new BuildCustomStructurePayload(cfg.pos, packName, constructionId, cfg.houseFacing, PlayerPreferences.get().getBuildAnimationMode(), silent));
        StructureRenderHandler.setStructure(null, null);
        CustomStructureGui.clearAddonPreviewFlag();
    }

    private static void triggerOutsourceBuildAtPreview(StructureConfiguration cfg) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        lastAction = 342;
        String buildingId = CustomStructureGui.getCurrentOutsourceBuildingId();
        int styleIndex = CustomStructureGui.getCurrentOutsourceStyleIndex();
        if (buildingId == null || buildingId.isEmpty()) {
            player.sendSystemMessage((Component)Component.literal((String)"\u26a0 \u5916\u5305\u5efa\u7b51\u4e0a\u4e0b\u6587\u4e22\u5931, \u8bf7\u91cd\u65b0\u6253\u5f00\u84dd\u56fe\u53f3\u952e").withStyle(ChatFormatting.RED));
            PrefabCustomAddon.LOGGER.warn("[OUTSOURCE-BUILD] ALT \u89e6\u53d1\u65f6 buildingId \u4e3a\u7a7a (setOutsourceContext \u6ca1\u88ab\u8c03?)");
            return;
        }
        ItemStack blueprint = ItemStack.EMPTY;
        for (int i = 0; i < player.getInventory().getContainerSize(); ++i) {
            String effId;
            ItemStack s = player.getInventory().getItem(i);
            if (s.isEmpty() || !(s.getItem() instanceof OutsourceBlueprintItem) || !buildingId.equals(effId = OutsourceBlueprintItem.getEffectiveBuildingId(s))) continue;
            blueprint = s;
            break;
        }
        if (blueprint.isEmpty()) {
            player.sendSystemMessage((Component)Component.literal((String)("\u26a0 \u80cc\u5305\u91cc\u6ca1\u6709\u5bf9\u5e94\u7684\u5916\u5305\u5efa\u7b51\u84dd\u56fe (buildingId=" + buildingId + ")")).withStyle(ChatFormatting.RED));
            return;
        }
        PrefabCustomAddon.LOGGER.info("[OUTSOURCE-BUILD] ALT pressed, sending BuildOutsourceStructurePayload for buildingId={} style={}/{} at {} facing {}", new Object[]{buildingId, styleIndex, blueprint, cfg.pos, cfg.houseFacing});
        NetworkHandler.sendToServer(new BuildOutsourceStructurePayload(buildingId, styleIndex, cfg.pos, cfg.houseFacing, PlayerPreferences.get().getBuildAnimationMode()));
        StructureRenderHandler.setStructure(null, null);
        CustomStructureGui.clearAddonPreviewFlag();
    }

    private static void triggerPrefabOriginalBuild(StructureConfiguration cfg) {
        if (cfg == null) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        lastAction = 342;
        StructureTagMessage.EnumStructureConfiguration enumConfig = StructureTagMessage.EnumStructureConfiguration.getByConfigurationInstance((StructureConfiguration)cfg);
        if (enumConfig == null) {
            player.sendSystemMessage((Component)Component.literal((String)("\u26a0 \u627e\u4e0d\u5230\u5bf9\u5e94\u7684 prefab EnumStructureConfiguration (\u7c7b\u578b=" + cfg.getClass().getSimpleName() + ")")).withStyle(ChatFormatting.RED));
            PrefabCustomAddon.LOGGER.warn("[PREVIEW-BUILD] getByConfigurationInstance returned null for class {}", (Object)cfg.getClass().getName());
            return;
        }
        StructureTagMessage msg = new StructureTagMessage(cfg.WriteToCompoundTag(), enumConfig);
        PrefabCustomAddon.LOGGER.info("[PREVIEW-BUILD] ALT (prefab original) sending STRUCTURE_BUILD for {} at {} facing {}", new Object[]{enumConfig, cfg.pos, cfg.houseFacing});
        PrefabBase.networkWrapper.sendToServer(ClientToServerTypes.STRUCTURE_BUILD, (Object)msg);
        StructureRenderHandler.setStructure(null, null);
    }

    private static void triggerPrefabRebuild() {
        StructureConfiguration cfg = StructureRenderHandler.currentConfiguration;
        Structure structure = StructureRenderHandler.currentStructure;
        if (cfg == null || structure == null) {
            return;
        }
        StructureRenderHandler.setStructure(structure, cfg);
        StructureRenderHandler.showedMessage = true;
    }

    private static void triggerCloudSummon(StructureConfiguration cfg) {
        if (cfg == null || cfg.pos == null) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        lastAction = 342;
        String buildingId = CloudPreview.getCurrentCloudBuildingId();
        if (buildingId == null || buildingId.isEmpty()) {
            player.sendSystemMessage((Component)Component.literal((String)"\u26a0 \u4e91\u7aef\u9884\u89c8\u72b6\u6001\u4e22\u5931, \u8bf7\u91cd\u5f00\u653e\u51fa\u6309\u94ae").withStyle(ChatFormatting.RED));
            PrefabCustomAddon.LOGGER.warn("[PREVIEW-BUILD] cloudSummon called but CURRENT_CLOUD_BUILDING_ID is null");
            CloudPreview.cancel();
            return;
        }
        CloudBuilding cb = CloudBuildingClientCache.getInstance().getById(buildingId);
        if (cb == null) {
            player.sendSystemMessage((Component)Component.literal((String)("\u26a0 \u4e91\u7aef\u5efa\u7b51\u5df2\u4e0d\u5b58\u5728: " + buildingId)).withStyle(ChatFormatting.RED));
            CloudPreview.cancel();
            return;
        }
        if (cb.placed) {
            player.sendSystemMessage((Component)Component.literal((String)("\u26a0 \u8be5\u4e91\u7aef\u5efa\u7b51\u5df2\u653e\u51fa @ " + (cb.placedAt == null ? "?" : cb.placedAt.toShortString()) + ", \u8bf7\u5148\u6536\u56de")).withStyle(ChatFormatting.RED));
            CloudPreview.cancel();
            return;
        }
        PrefabCustomAddon.LOGGER.info("[PREVIEW-BUILD] ALT (cloud) sending cloud_summon id={} pos={} facing={}", new Object[]{buildingId, cfg.pos, cfg.houseFacing});
        NetworkHandler.sendToServer(new CloudBuildingSummonPayload(buildingId, cfg.pos, cfg.houseFacing));
        CloudPreview.cancel();
        CustomStructureGui.clearAddonPreviewFlag();
        StructureRenderHandler.setStructure(null, null);
    }

    private static void triggerMultiblockSummon(StructureConfiguration cfg) {
        MultiblockShapeData shape;
        if (cfg == null || cfg.pos == null) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        lastAction = 342;
        String id = MultiblockPreview.getCurrentId();
        MultiblockShapeData multiblockShapeData = shape = id == null || id.isEmpty() ? null : MultiblockCatalog.getShape(id);
        if (shape == null) {
            player.sendSystemMessage((Component)Component.literal((String)"\u26a0 \u591a\u65b9\u5757\u9884\u89c8\u72b6\u6001\u4e22\u5931, \u8bf7\u91cd\u65b0\u6253\u5f00\u8be6\u7ec6\u754c\u9762").withStyle(ChatFormatting.RED));
            PrefabCustomAddon.LOGGER.warn("[PREVIEW-BUILD] multiblockSummon called but shape missing id={}", (Object)id);
            MultiblockPreview.cancel();
            return;
        }
        MaterialCalculator.MaterialList matList = shape.toMaterialList();
        UUID uuid = player.getUUID();
        // 创造模式免材料 (与自定义建筑同一规则); 生存/冒险需要提交材料
        if (!player.isCreative() && !ChallengeSessionManager.isReady(uuid, shape.sessionKey(), matList.required)) {
            Map<String, Integer> submitted = ChallengeSessionManager.getSubmitted(uuid, shape.sessionKey());
            int submittedCount = 0;
            int requiredCount = 0;
            for (Map.Entry<String, Integer> e : matList.required.entrySet()) {
                requiredCount += e.getValue().intValue();
                submittedCount += Math.min(e.getValue(), submitted.getOrDefault(e.getKey(), 0));
            }
            player.sendSystemMessage((Component)Component.literal((String)("\u26a0 \u6750\u6599\u672a\u63d0\u4ea4\u5b8c\u6bd5: " + submittedCount + " / " + requiredCount + " \u5df2\u4ea4, \u4ec5\u53ef\u9884\u89c8. \u56de\u5230\u591a\u65b9\u5757\u8be6\u7ec6\u754c\u9762\u70b9\u300c\u63d0\u4ea4\u6750\u6599\u300d")).withStyle(ChatFormatting.GOLD));
            return;
        }
        PrefabCustomAddon.LOGGER.info("[PREVIEW-BUILD] ALT (multiblock) sending multiblock_summon id={} pos={} facing={}", new Object[]{id, cfg.pos, cfg.houseFacing});
        NetworkHandler.sendToServer(new MultiblockSummonPayload(id, cfg.pos, cfg.houseFacing));
        ChallengeSessionManager.reset(uuid, shape.sessionKey());
        MultiblockPreview.cancel();
        CustomStructureGui.clearAddonPreviewFlag();
        StructureRenderHandler.setStructure(null, null);
    }
}

