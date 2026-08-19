package com.prefab.addon.integration.jade;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.integration.BuildingDatabase;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * Jade tooltip 提供者: 当玩家看向属于"已放出云端建筑"的方块时, 在 body 区域
 * 显示一行 "建筑: XXX" (绿色) + 可选 "放置者: XXX" (灰色).
 *
 * <p>实现要点:</p>
 * <ul>
 *   <li>enum 单例 (Jade 推荐的 INSTANCE 模式, 跟 WAILA 时代一样)</li>
 *   <li>UID 必须跟 {@link BuildingJadePlugin#UID} 一致, Jade 用它做配置和缓存</li>
 *   <li>appendTooltip 走 {@link BuildingDatabase#find} 反查, 命中才加行 — 没命中就静默返回</li>
 *   <li>查不到 = 玩家看的不是建筑, 这是绝大多数情况, 所以必须 fast path 越快越好</li>
 * </ul>
 *
 * <p>Jade 15.5.2+ API: BlockAccessor (无 I 前缀) / appendTooltip / IPluginConfig 已搬到 config 子包</p>
 */
@OnlyIn(Dist.CLIENT)
public enum BuildingJadeProvider implements IBlockComponentProvider {
    INSTANCE;

    @Override
    public ResourceLocation getUid() {
        return BuildingJadePlugin.UID;
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        try {
            BuildingDatabase.Record rec = BuildingDatabase.find(
                accessor.getLevel(), accessor.getPosition());
            if (rec == null) return;

            // 主行: 建筑名 (绿色) — 用 lang 键做 i18n, 失败时降级到原文
            Component nameLine = Component.literal(
                PrefabCustomAddon.tr("jade.building.name", rec.name))
                .withStyle(ChatFormatting.GREEN);
            tooltip.add(nameLine);

            // 副行: 尺寸 (灰色)
            String sizeStr = rec.sizeX + "×" + rec.sizeY + "×" + rec.sizeZ;
            tooltip.add(
                Component.literal(
                    PrefabCustomAddon.tr("jade.building.size", sizeStr))
                .withStyle(ChatFormatting.GRAY));

            // 可选: 放置者 (谁把这个建筑放到世界里的)
            if (rec.ownerName != null && !rec.ownerName.isEmpty()) {
                tooltip.add(
                    Component.literal(
                        PrefabCustomAddon.tr("jade.building.placer", rec.ownerName))
                    .withStyle(ChatFormatting.GRAY));
            }
        } catch (Throwable t) {
            // 任何意外 (Jade 版本不匹配/反射错位) 都不应崩游戏, 静默吞掉
            PrefabCustomAddon.LOGGER.debug("[JADE] appendTooltip failed: {}", t.getMessage());
        }
    }
}
