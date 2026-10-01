package com.prefab.addon.terminal;

import com.prefab.addon.terminal.network.TerminalPayloads;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * 现代终端 (Modern Terminal) 注册助手 — 自 modernterminal-1.21.1 移植进本模组.
 *
 * 原独立模组的 @Mod 主类, 合并后不再带 @Mod 注解, 改由主模组
 * {@link com.prefab.addon.PrefabCustomAddon} 构造器调用 {@link #register(IEventBus)} 完成注册.
 *
 * 资源命名空间仍为 modern_terminal (lang/模型/贴图/创造栏 key 均保持不变).
 */
public final class TerminalRegistry {
    public static final String MOD_ID = "modern_terminal";
    /**
     * 宿主模组 id: 事件订阅 (@EventBusSubscriber) 必须挂在 mods.toml 里实际声明的
     * prefab_custom_addon 上 — NeoForge 按 modid 找 ModContainer, 挂到未声明的
     * modern_terminal 上会导致客户端事件 (FMLClientSetup/ScreenEvent 等) 全部静默失效.
     * 物品/资源命名空间仍用 MOD_ID (modern_terminal), 两者不要混用.
     */
    public static final String HOST_MOD_ID = "prefab_custom_addon";
    public static final Logger LOGGER = LoggerFactory.getLogger("ModernTerminal");

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);

    /**
     * 终端物品: 右键打开终端 GUI (LDLib2 Menu UI).
     * 本类 (common) 绝不 import 客户端类 — {@code TerminalItem} 实现
     * {@code HeldItemUIMenuType.HeldItemUI}, 右键时服务端 openUI 打开
     * ModularUIContainerMenu, 客户端由 LDLib2 注册的 screen factory 弹屏;
     * UI 树由 {@code ui.TerminalUI} 双侧构建, 否则 dedicated server 直接崩.
     */
    public static final DeferredItem<Item> TERMINAL = ITEMS.register("terminal",
            () -> new com.prefab.addon.terminal.items.TerminalItem(new Item.Properties().stacksTo(1)));

    public static final Supplier<CreativeModeTab> TAB = CREATIVE_TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.modern_terminal"))
                    .icon(() -> new ItemStack(TERMINAL.get()))
                    .displayItems((params, output) -> output.accept(TERMINAL.get()))
                    .build());

    /** 由主模组构造器调用 (mod 总线). */
    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        CREATIVE_TABS.register(modBus);
        modBus.addListener(TerminalPayloads::register);
        LOGGER.info("[MODERN-TERMINAL] 现代终端已并入 prefab_custom_addon");
    }

    private TerminalRegistry() {
    }
}
