package com.prefab.addon;

import com.prefab.addon.config.AddonConfig;
import com.prefab.addon.extension.ExtensionPackManager;
import com.prefab.addon.items.CustomBlueprintItem;
import com.prefab.addon.network.NetworkHandler;
import net.minecraft.core.registries.Registries;
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
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
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

    public static final DeferredItem<Item> CUSTOM_BLUEPRINT = ITEMS.register("custom_blueprint",
            () -> new CustomBlueprintItem(new Item.Properties().stacksTo(1)));

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

        // 注册配置文件 (common config: prefab_custom_addon-common.toml)
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, AddonConfig.COMMON_SPEC);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::clientSetup);
        modEventBus.addListener(this::addCreativeTabContents);
        modEventBus.addListener(NetworkHandler::register);

        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        // StructurePreviewHandler 用 @EventBusSubscriber 自动注册（避免 addListener 时机问题）
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
}
