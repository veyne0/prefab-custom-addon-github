package com.prefab.addon.client;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.items.CustomBlueprintItem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.ListIterator;

/**
 * KubeJS 联动蓝图悬浮信息增强.
 *
 * <p>KubeJS 在 startup_scripts 里 {@code add('kubejs:my_blueprint')} 把任意物品加进
 * {@code prefab_custom_addon:player_blueprint} tag, 这些蓝图走本 mod 的 ALT 建造路径.
 * 但 KubeJS 注册的物品自己只显示 {@code player_pack:player_blueprint} 这种 namespace
 * 信息, 玩家看不出来 "哦原来是这个 mod 在管".</p>
 *
 * <p>本类通过 {@link ItemTooltipEvent} 做两件事:
 * <ol>
 *   <li>把 vanilla + KubeJS 加在 tooltip 末尾的 "namespace 行"
 *       (蓝字 + 斜体, 例 {@code player_pack:}) 替换成 {@code prefab_custom_addon},
 *       跟原版 prefab 蓝图显示的 {@code prefab:} 同一位置同一样式, 让玩家一眼看出
 *       "本 mod 负责建造逻辑".</li>
 *   <li>不追加其他文字 — 用户要的就是改 namespace 那行, 不需要额外的 "由 ... 提供".</li>
 * </ol>
 *
 * <p>只对 <b>非 mod 原生</b> {@link CustomBlueprintItem} 且带 player_blueprint tag
 * 的物品生效. mod 原生 CustomBlueprintItem 自带 namespace (就是 prefab_custom_addon),
 * 不动它.</p>
 *
 * <p>放在 client 端是因为 ItemTooltipEvent 主要用于客户端渲染, dedicated server 永远
 * 不加载本类, 不会触发 "Attempted to load class net/minecraft/client/..." 错误.</p>
 */
@EventBusSubscriber(modid = PrefabCustomAddon.MOD_ID, value = Dist.CLIENT)
public class KubeJSPlayerBlueprintTooltip {

    private static final TagKey<Item> PLAYER_BLUEPRINT_TAG = TagKey.create(
        Registries.ITEM,
        ResourceLocation.fromNamespaceAndPath(PrefabCustomAddon.MOD_ID, "player_blueprint"));

    /**
     * 玩家悬浮物品时, 把 tooltip 里以原 namespace + ":" 结尾的那行替换成本 mod 的 namespace.
     *
     * <p>vanilla 1.21.1 在 tooltip 末尾会插入一行 "namespace:" (蓝字 + 斜体),
     * 例如 {@code player_pack:} 这种, 用来指示物品归属 mod. KubeJS 注册的物品
     * namespace 写的是 player_pack, 看起来跟我们无关. 我们遍历 tooltip 找到这一行
     * (用 "endsWith : + italic style" 判定), 替换成 {@code prefab_custom_addon:}
     * 同一位置同一样式, 让玩家知道这个蓝图是 Prefab Custom Addon 在管.</p>
     */
    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        // 排除 mod 原生 CustomBlueprintItem: 它的 namespace 已经是 prefab_custom_addon, 不动它
        if (stack.getItem() instanceof CustomBlueprintItem) return;
        // 只处理带 player_blueprint tag 的物品 (= KubeJS 联动蓝图)
        if (!stack.is(PLAYER_BLUEPRINT_TAG)) return;

        // 从后往前找: vanilla 的 namespace 行一般在最末尾, 也可能在 "registry name" 之后.
        // 判定条件: Component 是 plain literal, 去掉末尾 ":" 之后等于 stack 的 namespace.
        String stackNs = stack.getItem().builtInRegistryHolder().key().location().getNamespace();

        // 备份 vanilla namespace 行的 style, 我们的替换行用同一 style 保持视觉一致
        Style vanillaNsStyle = null;

        ListIterator<Component> it = event.getToolTip().listIterator();
        while (it.hasNext()) {
            Component line = it.next();
            // 只看 plain literal (Component.literal 出来的)
            String text = line.getString();
            if (text.endsWith(":")) {
                String trimmed = text.substring(0, text.length() - 1);
                if (trimmed.equals(stackNs)) {
                    // 这是 vanilla namespace 行, 备份 style
                    vanillaNsStyle = line.getStyle();
                    it.remove();
                    break;  // 只删一个
                }
            }
        }

        if (vanillaNsStyle == null) {
            // 没找到, 可能是 KubeJS 用了不同渲染方式, 退回用 vanilla 蓝字斜体 style
            vanillaNsStyle = Style.EMPTY
                .withColor(ChatFormatting.BLUE)
                .withItalic(true);
        }

        // 在 tooltip 末尾追加我们自己的 namespace 行
        // 用 vanilla 同一 style (蓝字 + 斜体), 但 namespace 字符串改成本 mod 的
        event.getToolTip().add(
            Component.literal(PrefabCustomAddon.MOD_ID + ":")
                .withStyle(vanillaNsStyle)
        );
    }
}
