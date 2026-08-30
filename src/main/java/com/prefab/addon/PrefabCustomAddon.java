package com.prefab.addon;

import com.mojang.serialization.MapCodec;
import com.prefab.addon.cloud.CloudBuildingManager;
import com.prefab.addon.config.AddonConfig;
import com.prefab.addon.config.CustomBlueprintRecipeCondition;
import com.prefab.addon.extension.ExtensionPackManager;
import com.prefab.addon.items.CustomBlueprintItem;
import com.prefab.addon.items.ItemCustomBulldozer;
import com.prefab.addon.items.MiniBuildingConverterItem;
import com.prefab.addon.items.OperationWandItem;
import com.prefab.addon.items.OutsourceBlueprintItem;
import com.prefab.addon.network.NetworkHandler;
import com.prefab.addon.network.ServerPackSyncServer;
import com.prefab.addon.outsource.OutsourceBuildingLoader;
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
    // 4 耐久推土机 (跟原版 prefab 一致), 区域大小存 NBT
    public static final DeferredItem<Item> CUSTOM_BULLDOZER = ITEMS.register("custom_bulldozer",
            () -> {
                ItemCustomBulldozer item = new ItemCustomBulldozer(new Item.Properties().durability(4));
                // [DEBUG] 日志点 1: 物品注册时打印 ID
                LOGGER.info("[CUSTOM_BULLDOZER-DEBUG] 注册 ID = {}", MOD_ID + ":custom_bulldozer");
                return item;
            });
    // 迷你建筑转换器: 2 耐久, 把建筑变成迷你方块
    public static final DeferredItem<Item> MINI_BUILDING_CONVERTER = ITEMS.register("mini_building_converter",
            () -> new MiniBuildingConverterItem(new Item.Properties().durability(2)));
    // 迷你建筑方块 (用 MiniBuildingBlockItem 让手持/掉落物也显示微缩建筑, 而不是方块贴图)
    public static final DeferredItem<Item> MINI_BUILDING_BLOCK_ITEM = ITEMS.register("mini_building",
            () -> new com.prefab.addon.items.MiniBuildingBlockItem(
                com.prefab.addon.blocks.PrefabBlockEntities.MINI_BUILDING_BLOCK.get(),
                new Item.Properties().stacksTo(1)));

    // 操作手杖 (Operation Wand) — 移动/复制模式, 无耐久
    public static final DeferredItem<Item> OPERATION_WAND = ITEMS.register("operation_wand",
            () -> {
                OperationWandItem item = new OperationWandItem(new Item.Properties().stacksTo(1));
                LOGGER.info("[OPERATION_WAND-DEBUG] 注册 ID = {}", MOD_ID + ":operation_wand");
                return item;
            });

    // 外包建筑蓝图 —— 8 个 Item 实例,每个对应 投影/ 下的一个子文件夹。
    //  玩家把 投影.zip 放进 prefab-outsource/ 后,扫描器用 "投影_<子文件夹名>" 作为 buildingId,
    //  这里每个 Item 硬编码自己的 buildingId,所以创造栏里的 8 个蓝图只能打开对应建筑。
    //  贴图与配方用户后续补:目前用 1 张占位贴图(全部 8 个 model.json 都指向它)。
    public static final DeferredItem<Item> OUTSOURCE_BLUEPRINT_MEDIEVAL = ITEMS.register(
        "outsource_blueprint_medieval_house",
        () -> new OutsourceBlueprintItem("投影_中世纪小屋", new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> OUTSOURCE_BLUEPRINT_TAVERN = ITEMS.register(
        "outsource_blueprint_adventurer_tavern",
        () -> new OutsourceBlueprintItem("投影_冒险者酒馆", new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> OUTSOURCE_BLUEPRINT_SHOP = ITEMS.register(
        "outsource_blueprint_shop",
        () -> new OutsourceBlueprintItem("投影_商店", new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> OUTSOURCE_BLUEPRINT_SMALL = ITEMS.register(
        "outsource_blueprint_small_building",
        () -> new OutsourceBlueprintItem("投影_小建筑", new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> OUTSOURCE_BLUEPRINT_LIGHT_TREE = ITEMS.register(
        "outsource_blueprint_light_tree",
        () -> new OutsourceBlueprintItem("投影_棱光树", new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> OUTSOURCE_BLUEPRINT_FORGE = ITEMS.register(
        "outsource_blueprint_forge_workshop",
        () -> new OutsourceBlueprintItem("投影_锻造工坊", new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> OUTSOURCE_BLUEPRINT_LINGSHUI = ITEMS.register(
        "outsource_blueprint_lingshui_inn",
        () -> new OutsourceBlueprintItem("投影_陵水旅馆", new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> OUTSOURCE_BLUEPRINT_WINDMILL = ITEMS.register(
        "outsource_blueprint_windmill_building",
        () -> new OutsourceBlueprintItem("投影_风车建筑", new Item.Properties().stacksTo(1)));

    /** 8 个外包建筑蓝图集中列表(创造栏填充 + 其他模块复用) */
    public static final java.util.List<DeferredItem<Item>> OUTSOURCE_BLUEPRINTS = java.util.List.of(
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
                output.accept(CUSTOM_BULLDOZER.get());
                output.accept(MINI_BUILDING_CONVERTER.get());
                // MINI_BUILDING_BLOCK_ITEM 不在创造栏显示, 它只能通过转换器生成
                // 8 个外包建筑蓝图(硬编码,按子文件夹顺序)
                for (DeferredItem<Item> b : OUTSOURCE_BLUEPRINTS) {
                    output.accept(b.get());
                }
            })
            .build());

    public PrefabCustomAddon(IEventBus modEventBus, ModContainer modContainer) {
        ITEMS.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
        CONDITION_SERIALIZERS.register(modEventBus);
        com.prefab.addon.blocks.PrefabBlockEntities.BLOCKS.register(modEventBus);
        com.prefab.addon.blocks.PrefabBlockEntities.BLOCK_ENTITIES.register(modEventBus);

        // 注册配置文件 (common config: prefab_custom_addon-common.toml)
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, AddonConfig.COMMON_SPEC);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::clientSetup);
        modEventBus.addListener(this::addCreativeTabContents);
        modEventBus.addListener(NetworkHandler::register);
        // 1.21.1: RegisterClientExtensionsEvent 是注入 IClientItemExtensions 的新入口
        // (Item.initializeClient 已 deprecated, 不再被调用)
        modEventBus.addListener(com.prefab.addon.client.MiniBuildingClientExtensions::onRegisterClientExtensions);

        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
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
        // GuiExtensionPackCreator 已重写, 不再需要 onFrameTick
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Prefab Custom Addon common setup");

        // [DEBUG-RECIPE] 把 data/recipe 目录里所有 JSON 文件枚举出来, 确认 9 个配方都在 jar 里
        try {
            java.net.URL recipeDirUrl = getClass().getClassLoader().getResource(
                "data/prefab_custom_addon/recipe/");
            if (recipeDirUrl == null) {
                LOGGER.info("[DEBUG-RECIPE] classpath 上找不到 data/prefab_custom_addon/recipe/ 目录");
            } else {
                java.nio.file.Path recipeDir = java.nio.file.Paths.get(recipeDirUrl.toURI());
                try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(recipeDir)) {
                    stream.filter(p -> p.toString().endsWith(".json"))
                        .sorted()
                        .forEach(p -> LOGGER.info("[DEBUG-RECIPE] 发现 recipe JSON: {}", p.getFileName()));
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[DEBUG-RECIPE] 枚举 recipe 目录失败: {}", t.getMessage());
        }

        // [BUILTIN] 把 mod 内置的 投影.zip 写到 <gameDir>/prefab-outsource/,
        //  让 OutsourceBuildingLoader 扫到, 8 个硬编码 ID 才能匹配上。
        //  在 commonSetup 阶段做 (而不是 onServerStarting):
        //  - commonSetup 在专用服/整合服/客户端三边都会触发一次, 统一处理
        //  - 写盘用 enqueueWork 推到主线程, 避免 mod loading 阶段的并发 IO
        event.enqueueWork(() -> {
            try {
                Path gameDir = net.neoforged.fml.loading.FMLLoader.getGamePath();
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

        // 注册迷你建筑方块 BER (1.21.1 BlockEntityRenderers.register)
        event.enqueueWork(() -> {
            net.minecraft.client.renderer.blockentity.BlockEntityRenderers.register(
                com.prefab.addon.blocks.PrefabBlockEntities.MINI_BUILDING_BE.get(),
                com.prefab.addon.client.MiniBuildingBlockRenderer::new);
        });

        // [DEBUG] 日志点 2: 客户端启动时验证 model 引用 + 实际加载的贴图
        event.enqueueWork(() -> {
            try {
                // 读自己的 model JSON
                var modelLoc = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
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
                var texLoc = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
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
                var prefabTex = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
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
        // 放进 INGREDIENTS(材料)创造栏,确保 JEI 索引到这些物品
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(CUSTOM_BLUEPRINT.get());
            event.accept(CUSTOM_BULLDOZER.get());
            event.accept(MINI_BUILDING_CONVERTER.get());
            event.accept(OPERATION_WAND.get());
            // 8 个外包建筑蓝图(每个 Item 硬编码了 buildingId,直接 accept 即可)
            for (DeferredItem<Item> b : OUTSOURCE_BLUEPRINTS) {
                event.accept(b.get());
            }
        }
    }

    public void onServerStarting(final ServerStartingEvent event) {
        LOGGER.info("Initializing extension pack manager");
        ExtensionPackManager.getInstance().initialize(event.getServer());
        // 云端建筑: 注入 server 实例, 后续 saveToDisk 拿 worldDir 拼路径
        CloudBuildingManager.getInstance().setServer(event.getServer());
        // 外包建筑: 扫描 <serverDir>/prefab-outsource/ 下的 .zip
        OutsourceBuildingLoader.getInstance().scan(event.getServer().getServerDirectory());

        // [DEBUG-RECIPE] 在 server starting 时枚举所有包含 outsource_blueprint / custom_blueprint 的配方
        //  看看到底哪些进了 RecipeManager, 哪些没进.
        //  1.21.1 RecipeManager 没有 getAllRecipes()！正确方法:
        //    - getRecipeIds() : Stream<ResourceLocation>
        //    - getRecipes() / getOrderedRecipes() : Collection<RecipeHolder<?>>
        //    - byKey(ResourceLocation) : Optional<RecipeHolder<?>>
        try {
            var recipeManager = event.getServer().getRecipeManager();
            LOGGER.info("[DEBUG-RECIPE] RecipeManager.hadErrorsLoading() = {}", recipeManager.hadErrorsLoading());
            // 用 getRecipeIds() 拿到所有 id, 过滤外包/自定义蓝图
            int all = 0, hit = 0;
            StringBuilder hits = new StringBuilder();
            StringBuilder missing = new StringBuilder();
            java.util.Set<String> expected = new java.util.HashSet<>(java.util.List.of(
                "prefab_custom_addon:custom_blueprint",
                "prefab_custom_addon:outsource_blueprint_medieval_house",
                "prefab_custom_addon:outsource_blueprint_adventurer_tavern",
                "prefab_custom_addon:outsource_blueprint_shop",
                "prefab_custom_addon:outsource_blueprint_small_building",
                "prefab_custom_addon:outsource_blueprint_light_tree",
                "prefab_custom_addon:outsource_blueprint_forge_workshop",
                "prefab_custom_addon:outsource_blueprint_lingshui_inn",
                "prefab_custom_addon:outsource_blueprint_windmill_building"
            ));
            java.util.Set<String> found = new java.util.HashSet<>();
            try (var idStream = recipeManager.getRecipeIds()) {
                var iter = idStream.iterator();
                while (iter.hasNext()) {
                    var id = iter.next();
                    all++;
                    String idStr = id.toString();
                    if (idStr.contains("outsource_blueprint") || idStr.contains("custom_blueprint")) {
                        hit++;
                        hits.append(idStr).append(", ");
                        found.add(idStr);
                    }
                }
            }
            // 列出缺失的
            for (String exp : expected) {
                if (!found.contains(exp)) missing.append(exp).append(", ");
            }
            LOGGER.info("[DEBUG-RECIPE] RecipeManager 总数: {} | 命中(outsource/custom): {} | 列表: {}",
                all, hit, hits);
            if (missing.length() > 0) {
                LOGGER.warn("[DEBUG-RECIPE] 缺失配方: {}", missing);
            } else {
                LOGGER.info("[DEBUG-RECIPE] ✓ 所有 9 个预期配方都已加载");
            }
        } catch (Throwable t) {
            LOGGER.warn("[DEBUG-RECIPE] 枚举 RecipeManager 失败: {}", t.getMessage(), t);
        }
    }

    /** 关服兜底: 全量落盘, 防止断电丢未保存的云端建筑. */
    public void onServerStopping(final net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        try {
            CloudBuildingManager.getInstance().onServerStopping();
        } catch (Throwable t) {
            LOGGER.warn("[CLOUD] onServerStopping failed: {}", t.getMessage());
        }
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
            // [MINI_BUILDING] 迁移老版本 ItemStack: 1.9 之前 NBT 内嵌在 ItemStack,
            // 大于 2MB 时客户端 NbtAccounter 崩溃. 改成文件 + 引用格式.
            com.prefab.addon.blocks.MiniBuildingStorage.migrateOldItemStacks(sp);
            // [MINI_BUILDING] 修复老版本物品缺失的 "id" 字段, 防止 1.21.1 ItemStack.save()
            // 报 "Missing id for entity" 崩溃 (BLOCK_ENTITY_DATA 顶层必须有 id).
            com.prefab.addon.blocks.MiniBuildingStorage.fixMissingIdField(sp);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                sp, new com.prefab.addon.network.SyncBuildSpeedPayload(serverBuildSpeed));
            PrefabCustomAddon.LOGGER.info("[BUILD-SPEED] Sent initial build speed {}% to player {}",
                serverBuildSpeed, sp.getName().getString());

            // 云端建筑: 加载该玩家的存档 + 全量推一份到客户端
            //   - 联机: 服务端 onPlayerJoin 走 setServer + 读盘 + 推 sync
            //   - 单机: getInstance() 是同一份, 客户端 cache 在收到 sync 后填充
            CloudBuildingManager.getInstance().onPlayerJoin(sp);

            // === 欢迎消息 (服务端路径, 整合服/专用服的玩家) ===
            sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(tr("welcome.thanks")));
        }
    }

    public void onPlayerLoggedOut(final PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            ServerPackSyncServer.getInstance().onPlayerLeave(sp);
        }
    }

    /**
     * 注册 /prefabaddon 指令：
     *   reload               — 重新扫描服务端 prefab-extension/ 目录
     *   recipes on|off|status — 切换 enableCustomBlueprintRecipe 配置 (开/关/查状态)
     *                          改完会自动写回 toml, 但需要 /reload 才生效 (NeoForge 配方在启动时加载).
     */
    public void onRegisterCommands(final RegisterCommandsEvent event) {
        com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> d = event.getDispatcher();
        d.register(
            net.minecraft.commands.Commands.literal("prefabaddon")
                .then(net.minecraft.commands.Commands.literal("reload")
                    .requires(src -> src.hasPermission(2))  // OP 权限
                    .executes(ctx -> {
                        int n = com.prefab.addon.extension.ExtensionPackManager.getInstance().reload();
                        // 外包建筑: 重新扫描 prefab-outsource/
                        int ob = OutsourceBuildingLoader.getInstance().scan(ctx.getSource().getServer().getServerDirectory());
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
                .then(net.minecraft.commands.Commands.literal("recipes")
                    .requires(src -> src.hasPermission(2))
                    .then(net.minecraft.commands.Commands.literal("on")
                        .executes(ctx -> {
                            AddonConfig.ENABLE_CUSTOM_BLUEPRINT_RECIPE.set(true);
                            AddonConfig.COMMON_SPEC.save();
                            com.prefab.addon.config.CustomBlueprintRecipeCondition.resetLogFlag();
                            ctx.getSource().sendSuccess(
                                () -> net.minecraft.network.chat.Component.literal(
                                    "§a[prefabaddon] enableCustomBlueprintRecipe = true (已写回 toml)"),
                                true);
                            ctx.getSource().sendSuccess(
                                () -> net.minecraft.network.chat.Component.literal(
                                    "§e请执行 /reload 让 custom_blueprint 配方生效"),
                                true);
                            return 1;
                        }))
                    .then(net.minecraft.commands.Commands.literal("off")
                        .executes(ctx -> {
                            AddonConfig.ENABLE_CUSTOM_BLUEPRINT_RECIPE.set(false);
                            AddonConfig.COMMON_SPEC.save();
                            com.prefab.addon.config.CustomBlueprintRecipeCondition.resetLogFlag();
                            ctx.getSource().sendSuccess(
                                () -> net.minecraft.network.chat.Component.literal(
                                    "§c[prefabaddon] enableCustomBlueprintRecipe = false (已写回 toml)"),
                                true);
                            ctx.getSource().sendSuccess(
                                () -> net.minecraft.network.chat.Component.literal(
                                    "§e请执行 /reload 让 custom_blueprint 配方被禁用 "
                                    + "(8 个外包建筑蓝图 outsource_blueprint_* 不受此开关影响, 会保持生效)"),
                                true);
                            return 1;
                        }))
                    .then(net.minecraft.commands.Commands.literal("status")
                        .executes(ctx -> {
                            boolean cur = AddonConfig.isCustomBlueprintRecipeEnabled();
                            ctx.getSource().sendSuccess(
                                () -> net.minecraft.network.chat.Component.literal(
                                    "§7[prefabaddon] enableCustomBlueprintRecipe = " + cur
                                    + (cur ? " §a(custom_blueprint 配方应已加载, 8 个外包蓝图始终生效)"
                                           : " §c(custom_blueprint 配方被禁用, 8 个外包蓝图仍生效)")),
                                true);
                            return 1;
                        }))
                )
        );
    }
}
