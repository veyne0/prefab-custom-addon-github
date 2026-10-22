package com.lowdragmc.lowdraglib2.utils;

/**
 * LDLib2 1.20.1 兼容补丁的工厂类:
 * Connector 等重映射环境下 net.minecraft.resources.ResourceLocation 的
 * mojmap 静态工厂 (fromNamespaceAndPath / parse / withDefaultNamespace) 可能不存在,
 * 这里统一改走 public 构造器 (任何环境下都稳定).
 * 方法签名与原静态工厂完全一致, 字节码层只改方法引用的 owner.
 */
public final class RLFactory {
    private RLFactory() {}

    public static net.minecraft.resources.ResourceLocation fromNamespaceAndPath(String namespace, String path) {
        return new net.minecraft.resources.ResourceLocation(namespace, path);
    }

    public static net.minecraft.resources.ResourceLocation parse(String location) {
        return new net.minecraft.resources.ResourceLocation(location);
    }

    public static net.minecraft.resources.ResourceLocation withDefaultNamespace(String path) {
        return new net.minecraft.resources.ResourceLocation("minecraft", path);
    }
}
