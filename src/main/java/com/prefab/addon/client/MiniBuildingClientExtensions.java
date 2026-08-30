package com.prefab.addon.client;

import com.prefab.addon.PrefabCustomAddon;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/**
 * 1.21.1: 用 {@link RegisterClientExtensionsEvent} 给 MiniBuildingBlockItem 注入
 * IClientItemExtensions, 返回单例的 {@link MiniBuildingItemRenderer}.
 *
 * <p>1.21.1 中 Item.initializeClient 已被 deprecated, 实际不调用. 替代做法:
 * <ol>
 *   <li>监听 mod bus 上的 {@code RegisterClientExtensionsEvent}</li>
 *   <li>{@code event.registerItem(IClientItemExtensions, ItemLike...)}</li>
 *   <li>Minecraft 在渲染 ItemStack 时调 {@code IClientItemExtensions.getCustomRenderer()}</li>
 * </ol>
 *
 * <p>注意: 这只解决"世界掉落物 + GUI/手持"的 BEWLR 接管. model json 的 parent 仍
 * 应该是 {@code builtin/entity}, 否则 GUI 还是会回退到默认 block model.</p>
 */
public final class MiniBuildingClientExtensions {

    private MiniBuildingClientExtensions() {}

    public static void onRegisterClientExtensions(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions() {
            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return MiniBuildingItemRenderer.INSTANCE;
            }
        }, PrefabCustomAddon.MINI_BUILDING_BLOCK_ITEM.get());
        PrefabCustomAddon.LOGGER.info("[MINI_BUILDING] Registered IClientItemExtensions for MiniBuildingBlockItem");
    }
}
