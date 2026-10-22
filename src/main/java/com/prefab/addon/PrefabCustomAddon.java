package com.prefab.addon;

import com.prefab.addon.config.AddonConfig;
import com.prefab.addon.config.CustomBlueprintRecipeCondition;
import com.prefab.addon.extension.ExtensionPackManager;
import com.prefab.addon.items.CustomBlueprintItem;
import com.prefab.addon.items.ItemCustomBulldozer;
import com.prefab.addon.items.OutsourceBlueprintItem;
import com.prefab.addon.network.NetworkHandler;
import com.prefab.addon.network.ServerPackSyncServer;
import com.prefab.addon.outsource.OutsourceBuildingLoader;
import com.prefab.addon.terminal.TerminalRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.crafting.CraftingHelper;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

@Mod(PrefabCustomAddon.MOD_ID)
public class PrefabCustomAddon {
    public static final String MOD_ID = "prefab_custom_addon";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * i18n helper: 把 lang key + 可变参数 → 渲染好的 String (含 § 颜色码).
     * lang 文件里直接放 §7xxx 这种带颜色码的字符串即可.
     * key 缺失时降级为 key 本身 (不崩, 也好排查缺失).
     */
    public static String tr(String key, Object... args) {
        try {
            return net.minecraft.network.chat.Component.translatable(key, args).getString();
        } catch (Throwable t) {
            return key;
        }
    }

    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);
    // 自定义创造栏（让玩家和 JEI 都能找到我们的自定义蓝图）
    // Forge 1.20.1: 创造栏注册到原版 Registries.CREATIVE_MODE_TABS
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
        DeferredRegister.create(net.minecraft.core.registries.Registries.CREATIVE_MODE_TAB, MOD_ID);
    // 自定义数据加载条件 (配方 JSON 里 "conditions": [{"type": "prefab_custom_addon:custom_blueprint_recipe"}])
    //   - Forge 1.20.1 用 CraftingHelper.register(IConditionSerializer) 注册
    //   - condition.test() 返回 false → 整个 recipe 跳过, 玩家无法合成

    public static final RegistryObject<Item> CUSTOM_BLUEPRINT = ITEMS.register("custom_blueprint",
            () -> new CustomBlueprintItem(new Item.Properties().stacksTo(1)));

    // 缺失方块 ("?" 方块) 的物品形态: 创造栏可拿, 方便服主手动替换/演示 (从 1.21.1 移植)
    public static final RegistryObject<Item> MISSING_BLOCK_ITEM = ITEMS.register("missing_block",
            () -> new BlockItem(com.prefab.addon.blocks.PrefabBlocks.MISSING_BLOCK.get(),
                    new Item.Properties()));

    // 4 耐久推土机 (跟原版 prefab 一致), 区域大小存 NBT
    public static final RegistryObject<Item> CUSTOM_BULLDOZER = ITEMS.register("custom_bulldozer",
            () -> {
                ItemCustomBulldozer item = new ItemCustomBulldozer(new Item.Properties().durability(4));
                // [DEBUG] 日志点 1: 物品注册时打印 ID
                LOGGER.info("[CUSTOM_BULLDOZER-DEBUG] 注册 ID = {}", MOD_ID + ":custom_bulldozer");
                return item;
            });

    // 外包建筑蓝图 (从 1.21.1 移植) —— 8 个 Item 实例,每个对应 投影/ 下的一个子文件夹。
    //  玩家把 投影.zip 放进 prefab-outsource/ 后,扫描器用 "投影_<子文件夹名>" 作为 buildingId,
    //  这里每个 Item 硬编码自己的 buildingId,所以创造栏里的 8 个蓝图只能打开对应建筑。
    //  客户端右键打开 GUI 的逻辑在 OutsourceBuildingClientHandler (Dist.CLIENT 拦截右键),
    //  本类 (common) 不引用 client 类, 避免 dedicated server 加载崩溃.
    public static final RegistryObject<Item> OUTSOURCE_BLUEPRINT_MEDIEVAL = ITEMS.register(
        "outsource_blueprint_medieval_house",
        () -> new OutsourceBlueprintItem("投影_中世纪小屋", new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> OUTSOURCE_BLUEPRINT_TAVERN = ITEMS.register(
        "outsource_blueprint_adventurer_tavern",
        () -> new OutsourceBlueprintItem("投影_冒险者酒馆", new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> OUTSOURCE_BLUEPRINT_SHOP = ITEMS.register(
        "outsource_blueprint_shop",
        () -> new OutsourceBlueprintItem("投影_商店", new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> OUTSOURCE_BLUEPRINT_SMALL = ITEMS.register(
        "outsource_blueprint_small_building",
        () -> new OutsourceBlueprintItem("投影_小建筑", new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> OUTSOURCE_BLUEPRINT_LIGHT_TREE = ITEMS.register(
        "outsource_blueprint_light_tree",
        () -> new OutsourceBlueprintItem("投影_棱光树", new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> OUTSOURCE_BLUEPRINT_FORGE = ITEMS.register(
        "outsource_blueprint_forge_workshop",
        () -> new OutsourceBlueprintItem("投影_锻造工坊", new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> OUTSOURCE_BLUEPRINT_LINGSHUI = ITEMS.register(
        "outsource_blueprint_lingshui_inn",
        () -> new OutsourceBlueprintItem("投影_陵水旅馆", new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> OUTSOURCE_BLUEPRINT_WINDMILL = ITEMS.register(
        "outsource_blueprint_windmill_building",
        () -> new OutsourceBlueprintItem("投影_风车建筑", new Item.Properties().stacksTo(1)));

    /** 8 个外包建筑蓝图集中列表(创造栏填充 + 其他模块复用) */
    public static final java.util.List<RegistryObject<Item>> OUTSOURCE_BLUEPRINTS = java.util.List.of(
        OUTSOURCE_BLUEPRINT_MEDIEVAL,
        OUTSOURCE_BLUEPRINT_TAVERN,
        OUTSOURCE_BLUEPRINT_SHOP,
        OUTSOURCE_BLUEPRINT_SMALL,
        OUTSOURCE_BLUEPRINT_LIGHT_TREE,
        OUTSOURCE_BLUEPRINT_FORGE,
        OUTSOURCE_BLUEPRINT_LINGSHUI,
        OUTSOURCE_BLUEPRINT_WINDMILL
    );


    /**
     * 注册 "prefab_custom_addon:custom_blueprint_recipe" 条件, 配方 JSON 里写
     * {@code "conditions": [{"type": "prefab_custom_addon:custom_blueprint_recipe"}]} 即可让 Forge
     * 在加载该配方前调用 {@link CustomBlueprintRecipeCondition#test}, 决定是否加载.
     * Forge 1.20.1: 直接 CraftingHelper.register, 不走注册表.
     */
    static {
        CraftingHelper.register(CustomBlueprintRecipeCondition.SERIALIZER);
    }

    public static final RegistryObject<CreativeModeTab> CUSTOM_TAB =
        CREATIVE_TABS.register("prefab_custom_addon_tab", () -> CreativeModeTab.builder()
            .title(net.minecraft.network.chat.Component.translatable("itemGroup." + MOD_ID))
            .icon(() -> new ItemStack(CUSTOM_BLUEPRINT.get()))
            .displayItems((params, output) -> {
                output.accept(CUSTOM_BLUEPRINT.get());
                output.accept(CUSTOM_BULLDOZER.get());
                output.accept(TerminalRegistry.TERMINAL.get());
                output.accept(MISSING_BLOCK_ITEM.get());
                // 外包建筑蓝图 (从 1.21.1 移植): 8 个硬编码 buildingId 的蓝图物品
                for (RegistryObject<Item> b : OUTSOURCE_BLUEPRINTS) {
                    output.accept(b.get());
                }
            })
            .build());

    public PrefabCustomAddon() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
        // 缺失方块 ("?" 方块, 从 1.21.1 移植)
        com.prefab.addon.blocks.PrefabBlocks.BLOCKS.register(modEventBus);

        // 注册配置文件 (common config: prefab_custom_addon-common.toml)
        ModLoadingContext.get().registerConfig(net.minecraftforge.fml.config.ModConfig.Type.COMMON, AddonConfig.COMMON_SPEC);

        // SimpleChannel 不依赖事件, 直接注册全部网络包
        NetworkHandler.register();

        // 现代终端 (LDLib2 UI): 注册物品/创造栏/网络通道
        TerminalRegistry.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::clientSetup);
        modEventBus.addListener(this::addCreativeTabContents);

        MinecraftForge.EVENT_BUS.addListener(this::onServerStarting);
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerLoggedOut);
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
        // StructurePreviewHandler 用 @EventBusSubscriber 自动注册（避免 addListener 时机问题）
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Prefab Custom Addon common setup");

        // [BUILTIN] 外包建筑 (从 1.21.1 移植): 把 mod 内置的 投影.zip 写到 <gameDir>/prefab-outsource/,
        //  让 OutsourceBuildingLoader 扫到, 8 个硬编码 ID 才能匹配上。
        //  在 commonSetup 阶段做 (而不是 onServerStarting):
        //  - commonSetup 在专用服/整合服/客户端三边都会触发一次, 统一处理
        //  - 写盘用 enqueueWork 推到主线程, 避免 mod loading 阶段的并发 IO
        event.enqueueWork(() -> {
            try {
                Path gameDir = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get();
                boolean ok = com.prefab.addon.outsource.BuiltinPackExtractor.extract(gameDir);
                if (ok) {
                    // 内置包覆盖了旧版, 立刻重新扫描, 让 GUI 第一次打开就能看到 8 个建筑
                    int n = OutsourceBuildingLoader.getInstance().scan(gameDir);
                    LOGGER.info("[BUILTIN] 内置包解包完成, 重新扫描发现 {} 个建筑", n);
                } else {
                    // 内置资源缺失 (例如用户 build 时漏放了 投影.zip),
                    // 仍然扫一下外部目录 —— 玩家可能自己手动放了一份
                    OutsourceBuildingLoader.getInstance().scan(gameDir);
                }
            } catch (Throwable t) {
                LOGGER.error("[BUILTIN] commonSetup 内置包解包异常: {}", t.getMessage(), t);
            }
        });
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        LOGGER.info("Prefab Custom Addon client setup");
        ExtensionPackManager.getInstance().initializeClient();

        // [DEBUG] 日志点 2: 客户端启动时验证 model 引用 + 实际加载的贴图
        event.enqueueWork(() -> {
            try {
                // 读自己的 model JSON
                var modelLoc = new net.minecraft.resources.ResourceLocation(
                    MOD_ID, "models/item/custom_bulldozer.json");
                var opt = net.minecraft.client.Minecraft.getInstance()
                    .getResourceManager().getResource(modelLoc);
                if (opt.isPresent()) {
                    String json = new String(opt.get().open().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                    LOGGER.info("[CUSTOM_BULLDOZER-DEBUG] model JSON 路径 = {}", modelLoc);
                    LOGGER.info("[CUSTOM_BULLDOZER-DEBUG] model JSON 内容 = {}", json);
                } else {
                    LOGGER.error("[CUSTOM_BULLDOZER-DEBUG] ❌ 找不到 model JSON: {}", modelLoc);
                }

                // 读自己的贴图
                var texLoc = new net.minecraft.resources.ResourceLocation(
                    MOD_ID, "textures/item/custom_bulldozer.png");
                var optTex = net.minecraft.client.Minecraft.getInstance()
                    .getResourceManager().getResource(texLoc);
                if (optTex.isPresent()) {
                    long size = optTex.get().open().readAllBytes().length;
                    LOGGER.info("[CUSTOM_BULLDOZER-DEBUG] 贴图 = {} ({} 字节)", texLoc, size);
                } else {
                    LOGGER.error("[CUSTOM_BULLDOZER-DEBUG] ❌ 找不到贴图: {}", texLoc);
                }

                // 检查 Prefab 原版贴图是否被引用
                var prefabTex = new net.minecraft.resources.ResourceLocation(
                    "prefab", "textures/item/item_bulldozer.png");
                var optPrefab = net.minecraft.client.Minecraft.getInstance()
                    .getResourceManager().getResource(prefabTex);
                if (optPrefab.isPresent()) {
                    long size = optPrefab.get().open().readAllBytes().length;
                    LOGGER.warn("[CUSTOM_BULLDOZER-DEBUG] ⚠️ Prefab 原版贴图也存在: {} ({} 字节) — 这正常, 但我们代码不引用它",
                        prefabTex, size);
                }
            } catch (Throwable t) {
                LOGGER.error("[CUSTOM_BULLDOZER-DEBUG] 验证失败", t);
            }
        });
    }

    /**
     * 关键修复：JEI 在 NeoForge 1.21.1 下默认过滤掉**不在任何创造栏**的物品。
     * 自定义蓝图原本没有 creative tab → JEI 搜索框找不到它（但配方能通过 R 键访问）。
     * 解决办法：把它放进一个内建的创造栏（这里选 INGREDIENTS）。
     * 同时我们也注册了上面的 CUSTOM_TAB，让用户能在自定义栏里直接找到。
     */
    private void addCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        // 放进 INGREDIENTS（材料）创造栏，确保 JEI 索引到这些物品
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(CUSTOM_BLUEPRINT.get());
            event.accept(CUSTOM_BULLDOZER.get());
            event.accept(TerminalRegistry.TERMINAL.get());
        }
    }

    public void onServerStarting(final ServerStartingEvent event) {
        LOGGER.info("Initializing extension pack manager");
        ExtensionPackManager.getInstance().initialize(event.getServer());
        // 加载玩家上传权限表 (config/prefab_custom_addon/upload_perms.json)
        com.prefab.addon.network.BuildingManageServer.getInstance().loadPerms();
        // 外包建筑 (从 1.21.1 移植): 扫描 <serverDir>/prefab-outsource/ 下的 .zip
        // 1.20.1 适配: MinecraftServer.getServerDirectory() 返回 File, 转 Path
        OutsourceBuildingLoader.getInstance().scan(event.getServer().getServerDirectory().toPath());
    }

    /**
     * 玩家进服后, 服务端把拓展包清单推给该玩家, 触发客户端自动拉取。
     * 同时把当前全服建造速度也推一份, 让客户端 SettingsGui 滑条值与服务端同步。
     */
    public void onPlayerLoggedIn(final PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            ServerPackSyncServer.getInstance().onPlayerJoin(sp);
            // 同步当前全服建造速度给该玩家 (客户端拿到后写本地 PlayerPreferences.buildBatchPercent)
            int serverBuildSpeed = com.prefab.addon.config.PlayerPreferences.get().getBuildBatchPercent();
            com.prefab.addon.network.NetworkHandler.sendToPlayer(
                sp, new com.prefab.addon.network.SyncBuildSpeedPayload(serverBuildSpeed));
            PrefabCustomAddon.LOGGER.info("[BUILD-SPEED] Sent initial build speed {}% to player {}",
                serverBuildSpeed, sp.getName().getString());
        }
    }

    public void onPlayerLoggedOut(final PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            ServerPackSyncServer.getInstance().onPlayerLeave(sp);
            // 清掉该玩家的建筑上传会话 (防退出后残留半截会话)
            com.prefab.addon.network.BuildingUploadServer.getInstance().onPlayerLeave(sp);
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
                        // 外包建筑 (从 1.21.1 移植): 重新扫描 prefab-outsource/
                        // 1.20.1 适配: getServerDirectory() 返回 File, 转 Path
                        int ob = OutsourceBuildingLoader.getInstance().scan(ctx.getSource().getServer().getServerDirectory().toPath());
                        ctx.getSource().sendSuccess(
                            () -> net.minecraft.network.chat.Component.literal(
                                tr("sel.pack_rescanned") + " (§a外包建筑: " + ob + " 座)"), true);
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
