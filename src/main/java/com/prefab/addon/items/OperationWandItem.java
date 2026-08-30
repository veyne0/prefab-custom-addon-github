package com.prefab.addon.items;

import net.minecraft.world.item.Item;

/**
 * 操作手杖 (Operation Wand) — WorldEdit //move + //copy 的轻量版.
 *
 * <p>无 GUI 类引用 (无 net.minecraft.client.*), 服务端可安全加载.
 * 手持行为 / 选区触发 / 模式切换全部走 {@link com.prefab.addon.client.OperationWandClientHandler}.</p>
 *
 * <p>模式 (per-player state, 存在 {@code OperationWandState}):
 * <ul>
 *   <li>MOVE  (默认) — 选区右键建造 = 移动原区域到目标位置, 原区域清空</li>
 *   <li>COPY         — 选区右键建造 = 在目标位置复制, 原区域保留. 仅创造模式可用</li>
 * </ul>
 */
public class OperationWandItem extends Item {

    public OperationWandItem(Properties properties) {
        super(properties);
    }
}
