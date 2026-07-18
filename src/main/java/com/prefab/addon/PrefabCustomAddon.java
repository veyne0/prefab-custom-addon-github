package com.prefab.addon;

import com.mojang.serialization.MapCodec;
import com.prefab.addon.config.AddonConfig;
import com.prefab.addon.config.CustomBlueprintRecipeCondition;
import com.prefab.addon.extension.ExtensionPackManager;
import com.prefab.addon.items.CustomBlueprintItem;
import com.prefab.addon.network.NetworkHandler;
import com.prefab.addon.network.ServerPackSyncServer;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(PrefabCustomAddon.MOD_ID)
public class PrefabCustomAddon {
    public static final String MOD_ID = "prefab_custom_addon";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    // 自定义创造栏（让玩家和 JEI 都能找到我们的自定义蓝图）
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);
    // 自定义数据加载条件 (配方 JSON 里 "type": "prefab_custom_addon:custom_blueprint_recipe")
    //   - 注册到 NeoForgeRegistries.CONDITION_SERIALIZERS, NeoForge 解析 recipe JSON 时会读
    //   - condition.test() 返回 false → 整个 recipe 跳过, 玩家无法合成
    public static final DeferredRegister<MapCodec<? extends ICondition>> CONDITION_SERIALIZERS =
        DeferredRegister.create(NeoForgeRegistries.CONDITION_SERIALIZERS, MOD_ID);

    public static final DeferredItem<Item> CUSTOM_BLUEPRINT = ITEMS.register("custom_blueprint",
            () -> new CustomBlueprintItem(new Item.Properties().stacksTo(1)));

    /**
     * 注册 "prefab_custom_addon:custom_blueprint_recipe" 条件, 配方 JSON 里写
     * {@code "type": "prefab_custom_addon:custom_blueprint_recipe"} 即可让 NeoForge
     * 在加载该配方前调用 {@link CustomBlueprintRecipeCondition#test}, 决定是否加载.
     */
    public static final net.neoforged.neoforge.registries.DeferredHolder<
        MapCodec<? extends ICondition>, MapCodec<? extends ICondition>> CUSTOM_BLUEPRINT_RECIPE_CONDITION =
        CONDITION_SERIALIZERS.register("custom_blueprint_recipe",
            () -> CustomBlueprintRecipeCondition.CODEC);

    public static final net.neoforged.neoforge.registries.DeferredHolder<CreativeModeTab, CreativeModeTab> CUSTOM_TAB =
        CREATIVE_TABS.register("prefab_custom_addon_tab", () -> CreativeModeTab.builder()
            .title(net.minecraft.network.chat.Component.translatable("itemGroup." + MOD_ID))
            .icon(() -> new ItemStack(CUSTOM_BLUEPRINT.get()))
            .displayItems((params, output) -> {
                output.accept(CUSTOM_BLUEPRINT.get());
            })
            .build());

    public PrefabCustomAddon(IEventBus modEventBus, ModContainer modContainer) {
        ITEMS.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
        CONDITION_SERIALIZERS.register(modEventBus);

        // 注册配置文件 (common config: prefab_custom_addon-common.toml)
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, AddonConfig.COMMON_SPEC);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::clientSetup);
        modEventBus.addListener(this::addCreativeTabContents);
        modEventBus.addListener(NetworkHandler::register);

        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        // StructurePreviewHandler 用 @EventBusSubscriber 自动注册（避免 addListener 时机问题）
    }

    /**
     * 客户端每帧 tick, 用于闪烁调试日志输出 (0.5s 节流)
     */
    public void onClientTick(final ClientTickEvent.Pre event) {
        com.prefab.addon.client.gui.GuiExtensionPackCreator.onFrameTick();
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Prefab Custom Addon common setup");
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        LOGGER.info("Prefab Custom Addon client setup");
        ExtensionPackManager.getInstance().initializeClient();
    }

    /**
     * 关键修复：JEI 在 NeoForge 1.21.1 下默认过滤掉**不在任何创造栏**的物品。
     * 自定义蓝图原本没有 creative tab → JEI 搜索框找不到它（但配方能通过 R 键访问）。
     * 解决办法：把它放进一个内建的创造栏（这里选 INGREDIENTS）。
     * 同时我们也注册了上面的 CUSTOM_TAB，让用户能在自定义栏里直接找到。
     */
    private void addCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        // 放进 INGREDIENTS（材料）创造栏，确保 JEI 索引到这个物品
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(CUSTOM_BLUEPRINT.get());
        }
    }

    public void onServerStarting(final ServerStartingEvent event) {
        LOGGER.info("Initializing extension pack manager");
        ExtensionPackManager.getInstance().initialize(event.getServer());
    }

    /**
     * 玩家进服后, 服务端把拓展包清单推给该玩家, 触发客户端自动拉取。
     * 同时把当前全服建造速度也推一份, 让客户端 SettingsGui 滑条值与服务端同步。
     *
     * 额外: 给该玩家发一条欢迎消息到聊天栏, 告诉玩家按 O 可以打开本模组设置界面.
     *   - 服务端发 sendSystemMessage: 玩家客户端能直接看到, 不需要网络包.
     *   - 一次只发 1 次 (该 listener 在登录事件触发一次).
     *   - 注意: 在整合服里玩家既走这里(服务端路径), 又走 onPlayerLoggedInClient(客户端路径).
     *     为避免重复发, 这里只在服务端路径里发 (entity 是 ServerPlayer 实例 = 整合服/专用服的服务端逻辑).
     */
    public void onPlayerLoggedIn(final PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            ServerPackSyncServer.getInstance().onPlayerJoin(sp);
            // 同步当前全服建造速度给该玩家 (客户端拿到后写本地 PlayerPreferences.buildBatchPercent)
            int serverBuildSpeed = com.prefab.addon.config.PlayerPreferences.get().getBuildBatchPercent();
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                sp, new com.prefab.addon.network.SyncBuildSpeedPayload(serverBuildSpeed));
            PrefabCustomAddon.LOGGER.info("[BUILD-SPEED] Sent initial build speed {}% to player {}",
                serverBuildSpeed, sp.getName().getString());

            // === 欢迎消息 (服务端路径, 整合服/专用服的玩家) ===
            sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§a感谢使用 §e\"预制建筑附属\"§a, 按 §fO §a键可以打开本模组的设置界面"));
        }
    }

    public void onPlayerLoggedOut(final PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            ServerPackSyncServer.getInstance().onPlayerLeave(sp);
        }
    }

    /**
     * 注册 /prefabaddon reload 指令：重新扫描服务端 prefab-extension/ 目录,
     * 服主中途加 zip 后不再需要重启服, 客户端也会自动收到新清单。
     */
    public void onRegisterCommands(final RegisterCommandsEvent event) {
        com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> d = event.getDispatcher();
        d.register(
            net.minecraft.commands.Commands.literal("prefabaddon")
                .then(net.minecraft.commands.Commands.literal("reload")
                    .requires(src -> src.hasPermission(2))  // OP 权限
                    .executes(ctx -> {
                        int n = com.prefab.addon.extension.ExtensionPackManager.getInstance().reload();
                        ctx.getSource().sendSuccess(
                            () -> net.minecraft.network.chat.Component.literal(
                                "§a[PrefabAddon] 重新扫描完成, 当前 " + n + " 个拓展包"), true);
                        // 重扫后, 给所有在线玩家重发 manifest (他们会自动拿到新包)
                        for (net.minecraft.server.level.ServerPlayer sp :
                                ctx.getSource().getServer().getPlayerList().getPlayers()) {
                            ServerPackSyncServer.getInstance().onPlayerJoin(sp);
                        }
                        return n;
                    })
                )
        );
    }
}
