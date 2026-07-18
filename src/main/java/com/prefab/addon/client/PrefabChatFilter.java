package com.prefab.addon.client;

import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;

/**
 * 过滤 prefab 模组自己发送的预览模式提示消息:
 * <ul>
 *   <li>"右击任何方块即可移除预览。"  (prefab.gui.preview.notice)</li>
 *   <li>"黄色轮廓是您单击的块。"  (prefab.gui.structure.block.clicked)</li>
 * </ul>
 *
 * 触发场景: prefab 原版建筑 (例如 starter house) 在预览模式下, prefab 自带代码会在聊天栏
 * 反复发这两条提示. 我们的附属让用户也能用方向键/ALT 控制, 但这些消息太烦.
 *
 * <p>实现: 监听 {@link ClientChatReceivedEvent#SYSTEM} 类型的事件, 如果消息内容匹配
 * 上述两条之一, 就 setCanceled(true) 拦截 (不出现在聊天栏).
 */
@EventBusSubscriber(modid = PrefabCustomAddon.MOD_ID, value = Dist.CLIENT)
public class PrefabChatFilter {

    private static final String NOTICE = "\u53f3\u51fb\u4efb\u4f55\u65b9\u5757\u5373\u53ef\u79fb\u9664\u9884\u89c8\u3002"; // "右击任何方块即可移除预览。"
    private static final String CLICKED = "\u9ec4\u8272\u8f6e\u5ed3\u662f\u60a8\u5355\u51fb\u7684\u5757\u3002"; // "黄色轮廓是您单击的块。"

    @SubscribeEvent
    public static void onClientChat(ClientChatReceivedEvent event) {
        // 只过滤系统消息 (GAME_INFO / SYSTEM), 不影响玩家聊天
        if (event.isCanceled()) return;

        Component msg = event.getMessage();
        if (msg == null) return;

        String text = msg.getString();
        if (text == null) return;

        if (text.equals(NOTICE) || text.equals(CLICKED) || text.contains(NOTICE) || text.contains(CLICKED)) {
            PrefabCustomAddon.LOGGER.info("[CHAT-FILTER] 已过滤 prefab 预览提示: {}", text);
            event.setCanceled(true);
        }
    }
}
