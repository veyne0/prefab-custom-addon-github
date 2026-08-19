package com.prefab.addon.integration.jade;

import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * Jade 插件入口: 注册自定义建筑信息 provider.
 *
 * <p>用法: 玩家看向已放出的云端建筑方块时, Jade tooltip 底部会显示
 *  "建筑: XXX" / "放置者: XXX" (通过 {@link BuildingJadeProvider}).</p>
 *
 * <p><b>重要</b>: 这个类标了 {@link OnlyIn} (Dist.CLIENT), 这样在 dedicated server 上
 *  NeoForge 根本不会加载这个类, 也就不会因为 {@code snownee.jade.api.IWailaPlugin}
 *  接口不在 classpath 而爆 NoClassDefFoundError. 客户端侧正常加载 + 注册.</p>
 *
 * <p>Jade 1.21.x API: IWailaPlugin 拆成 register (common) + registerClient (client),
 *  注册参数从 IRegistrar 改名为 IWailaClientRegistration / IWailaCommonRegistration.</p>
 */
@OnlyIn(Dist.CLIENT)
@WailaPlugin(PrefabCustomAddon.MOD_ID)
public class BuildingJadePlugin implements IWailaPlugin {

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        // 注册: 所有 Block 都触发 (BuildingDatabase.find 内部判断是否在建筑内)
        // 优先级用默认 (TooltipPosition.BODY, 1000)
        registration.registerBlockComponent(BuildingJadeProvider.INSTANCE, Block.class);
    }

    /** 唯一 ID, Jade 内部用, 别瞎改. */
    public static final ResourceLocation UID =
        ResourceLocation.fromNamespaceAndPath(PrefabCustomAddon.MOD_ID, "building_info");
}
