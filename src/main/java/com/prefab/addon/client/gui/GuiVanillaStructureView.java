package com.prefab.addon.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollDisplay;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Scene;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.lowdragmc.lowdraglib2.utils.virtuallevel.TrackedDummyWorld;
import com.mojang.blaze3d.platform.InputConstants;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.client.VanillaStructureRegistry;
import com.prefab.addon.structure.CustomStructureBuilder.BlockData;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;

/**
 * 原版建筑详情界面 - 用 LDLib2 写, 纯展示不可建造.
 *
 * <pre>
 *   ┌────────────────────────────────────────────────────────────┐
 *   │ ← 返回                                                    │  ← 顶部 (仅一个按钮)
 *   ├──────────┬─────────────────────────────────────────────────┤
 *   │ 风格列表  │                                                  │
 *   │ • 基础   │             3D 预览 (LDLlib2 Scene)             │  ← 中部
 *   │ • 沙漠   │             - 拖动旋转 / 滚轮缩放                │
 *   │ • ...    │                                                  │
 *   │          │                                                  │
 *   └──────────┴─────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>资源格式</h3>
 * 原版 mod 资源在 <code>assets/prefab/structures/&lt;name&gt;.gz</code>,
 * 实际是 GZIP 压缩的 JSON, 不是 NBT. JSON 结构 (来自 prefab mod 源码):
 * <pre>
 * {
 *   "clearSpace": { "shape": ..., "startingPosition": {north/south/east/west/heightOffset} },
 *   "blocks": [
 *     {
 *       "blockDomain": "minecraft",
 *       "blockName": "stone",
 *       "startingPosition": {north/south/east/west/heightOffset},
 *       "properties": [{"name": "type", "value": "north"}, ...],
 *       "hasFacing": false,
 *       "blockStateData": "" // NBT-style 状态字符串 (备用)
 *     }
 *   ]
 * }
 * </pre>
 *
 * <h3>3D 渲染流程</h3>
 * <ol>
 *   <li>玩家点击变种按钮 → 异步从 prefab:structures/&lt;name&gt;.gz 读 bytes</li>
 *   <li>GZIPInputStream 解压 → UTF-8 字符串</li>
 *   <li>Gson 解析 → JsonObject</li>
 *   <li>遍历 blocks, 计算 BlockPos, 用 properties 还原 BlockState</li>
 *   <li>回到主线程, 一次性 setBlock 到 TrackedDummyWorld, scene.setRenderedCore + needCompileCache</li>
 * </ol>
 */
public final class GuiVanillaStructureView {

    private GuiVanillaStructureView() {}

    /**
     * 打开详情界面. 保存上一个 Screen, 用于返回按钮恢复 (而不是直接关掉回到游戏).
     */
    public static void open(VanillaStructureRegistry.Entry entry) {
        Screen previous = Minecraft.getInstance().screen;
        Minecraft.getInstance().setScreen(
            new ModularUIScreen(buildUI(entry, previous),
                Component.literal(com.prefab.addon.PrefabCustomAddon.tr("gui.vanilla.title", entry.displayName))));
    }

    private static ModularUI buildUI(VanillaStructureRegistry.Entry entry, Screen previousScreen) {
        // 当前选中的变种下标
        final int[] selectedIdx = {0};

        // ---------- 根元素 ----------
        UIElement root = new UIElement();
        root.layout(l -> l
            .widthPercent(100).heightPercent(100)
            .flexDirection(FlexDirection.COLUMN)
            .paddingAll(2).gapAll(2)
        );

        // ---------- 顶部栏: 仅 ← 返回 按钮 (左上角) ----------
        UIElement topBar = new UIElement();
        topBar.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .widthPercent(100).height(22)
            .gapAll(4).paddingAll(2)
            .alignItems(AlignItems.CENTER)
        );
        topBar.style(s -> s.background(Sprites.RECT_DARK));

        Button btnBack = new Button();
        btnBack.setText("← 返回");
        // 用 setOnClick (客户端点击), 不是 setOnServerClick (服务器事件, 单人模式可能不触发)
        btnBack.setOnClick(e -> {
            // 恢复上一个 Screen (例如 GuiExtensionPackBrowser); 如果没有就关掉
            if (previousScreen != null) {
                Minecraft.getInstance().setScreen(previousScreen);
            } else {
                Minecraft.getInstance().setScreen(null);
            }
        });
        btnBack.layout(l -> l.height(18));
        topBar.addChild(btnBack);

        root.addChild(topBar);

        // ---------- 主体: 左 130 按钮列表 + 右 flexGrow Scene ----------
        UIElement body = new UIElement();
        body.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .widthPercent(100).flexGrow(1).flexShrink(1)
            .gapAll(4).minHeight(0).minWidth(0)
        );

        // 左侧: 变种按钮列表 (ScrollerView, 强制垂直滚动条总是可见)
        UIElement listContainer = new UIElement();
        listContainer.layout(l -> l.width(130).heightPercent(100).minHeight(0).minWidth(0));
        listContainer.style(s -> s.background(Sprites.RECT_DARK));

        // 按钮列表内容
        // 关键: listContent 必须有 widthPercent(100), height 由内容自然撑开 (不写 height)
        // 这样 ScrollerView 才能算出"内容比视口高" → 出现滚动条
        UIElement listContent = new UIElement();
        listContent.layout(l -> l
            .flexDirection(FlexDirection.COLUMN)
            .widthPercent(100)
            .gapAll(2).paddingAll(2)
        );

        // 3D 场景 widget
        // 关键: Scene 必须有明确的 width/height, 否则 getContentWidth()=0 → 3D 不渲染
        Scene scene = new Scene();
        scene.layout(l -> l.widthPercent(100).heightPercent(100));
        scene.useOrtho(true);
        scene.setDraggable(true);
        scene.setScalable(true);
        scene.setIntractable(true);
        scene.setRenderFacing(false);
        scene.setRenderSelect(false);
        scene.useCacheBuffer(true);
        scene.syncCompile(true);
        scene.setTickWorld(false);

        // 提示文字 (3D 没加载完时显示在场景左上角) - 用 absolute 浮在 Scene 上层
        TextElement sceneHint = new TextElement();
        sceneHint.setText("加载中...");
        sceneHint.textStyle(t -> t.textColor(0xAAFFAA));
        sceneHint.layout(l -> l.width(160).height(12).positionType(TaffyPosition.ABSOLUTE).top(4).left(4));

        // 场景容器: Scene + 浮在上层的 hint
        UIElement sceneContainer = new UIElement();
        sceneContainer.layout(l -> l.flexGrow(1).flexShrink(1).heightPercent(100).minHeight(0).minWidth(0));
        sceneContainer.style(s -> s.background(Sprites.RECT_DARK));
        sceneContainer.setOverflowVisible(false);
        sceneContainer.addChild(scene);
        sceneContainer.addChild(sceneHint);

        // 为每个变种创建按钮
        for (int i = 0; i < entry.variants.size(); i++) {
            final int idx = i;
            VanillaStructureRegistry.Variant v = entry.variants.get(i);

            Button btn = new Button();
            btn.setText(v.displayName);
            // flexShrink(0) 防止按钮被压扁
            btn.layout(l -> l.widthPercent(100).height(18).flexShrink(0));
            // 用 setOnClick 而不是 setOnServerClick (后者是 server-side 事件, 单人可能不触发)
            final int captured = i;
            final VanillaStructureRegistry.Variant capturedV = v;
            btn.setOnClick(e -> {
                if (captured == selectedIdx[0]) return;
                selectedIdx[0] = captured;
                sceneHint.setText("加载: " + capturedV.displayName);
                loadAndRenderVariant(scene, sceneHint, capturedV);
            });
            listContent.addChild(btn);
        }

        // 滚动容器: 强制垂直滚动条总是显示 (解决左侧列表没滚动条的问题)
        // 关键: 用 addScrollViewChild (而不是 addChild) 把 listContent 放进 viewContainer
        // 否则 listContent 会成为 ScrollerView 的 sibling, 不会被滚动
        ScrollerView scroller = new ScrollerView();
        scroller.layout(l -> l.widthPercent(100).heightPercent(100).minHeight(0).minWidth(0));
        scroller.scrollerStyle(s -> s
            .mode(ScrollerMode.VERTICAL)
            .verticalScrollDisplay(ScrollDisplay.ALWAYS)
            .horizontalScrollDisplay(ScrollDisplay.NEVER)
        );
        scroller.addScrollViewChild(listContent);
        listContainer.addChild(scroller);

        body.addChild(listContainer);
        body.addChild(sceneContainer);
        root.addChild(body);

        // 注册全局 ESC 键 → 返回上一个 Screen
        root.addEventListener(UIEvents.KEY_DOWN, e -> {
            if (e.keyCode == InputConstants.KEY_ESCAPE) {
                if (previousScreen != null) {
                    Minecraft.getInstance().setScreen(previousScreen);
                } else {
                    Minecraft.getInstance().setScreen(null);
                }
            }
        });

        // 加载初始变种
        if (!entry.variants.isEmpty()) {
            loadAndRenderVariant(scene, sceneHint, entry.variants.get(0));
        }

        return ModularUI.of(UI.of(root,
            StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)),
            Minecraft.getInstance().player);
    }

    /**
     * 异步加载 → GZIP 解压 → JSON 解析 → 渲染 3D.
     * 状态写入 sceneHint.
     */
    private static void loadAndRenderVariant(Scene scene, TextElement sceneHint,
                                              VanillaStructureRegistry.Variant v) {
        if (v.nbt == null) {
            sceneHint.setText(com.prefab.addon.PrefabCustomAddon.tr("gui.vanilla.invalid"));
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                // 1. 主线程不能 getResource? ResourceManager 线程安全, 但保险起见先在主线程开 InputStream 拿 bytes
                // 简化: 直接 runAsync 内读 (Minecraft.getResourceManager 应该是只读线程安全的)
                byte[] gzBytes;
                try (InputStream is = Minecraft.getInstance()
                        .getResourceManager().getResource(v.nbt)
                        .orElseThrow(() -> new java.io.IOException("资源不存在: " + v.nbt))
                        .open()) {
                    gzBytes = readAllBytes(is);
                }

                // 2. GZIP 解压 → JSON 字符串
                String json;
                try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(gzBytes))) {
                    json = new String(readAllBytes(gz), StandardCharsets.UTF_8);
                }

                // 3. Gson 解析 JSON
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                JsonArray blocksArr = root.getAsJsonArray("blocks");
                if (blocksArr == null) {
                    setHint(sceneHint, "JSON 无 blocks 数组");
                    return;
                }

                // 4. 解析 blocks → List<BlockData>
                List<BlockData> blocks = new ArrayList<>();
                for (JsonElement el : blocksArr) {
                    BlockData bd = parseBlock(el.getAsJsonObject());
                    if (bd != null && bd.state != null && !bd.state.isAir()) {
                        blocks.add(bd);
                    }
                }
                if (blocks.isEmpty()) {
                    setHint(sceneHint, "无有效方块 (JSON 解析结果为空)");
                    return;
                }

                // 5. 回到主线程: 渲染
                final List<BlockData> finalBlocks = blocks;
                Minecraft.getInstance().execute(() -> {
                    initSceneForBlocks(scene, sceneHint, finalBlocks, v.displayName);
                });

            } catch (Exception e) {
                PrefabCustomAddon.LOGGER.warn("[VANILLA-VIEW] 加载失败 {}: {}", v.nbt, e.toString());
                e.printStackTrace();
                setHint(sceneHint, com.prefab.addon.PrefabCustomAddon.tr("gui.vanilla.load_fail", e.getClass().getSimpleName(), e.getMessage()));
            }
        });
    }

    /**
     * 解析单个 block JSON 对象 → BlockData.
     *
     * <p>位置算法: 来自 prefab 的 {@code PositionOffset.getRelativePosition(BlockPos.ZERO, Direction.SOUTH, Direction.NORTH)}:
     * <pre>
     *   x = eastOffset - westOffset
     *   y = heightOffset
     *   z = southOffset - northOffset
     * </pre>
     *
     * <p>block 状态: 优先用 {@code properties} 列表, 否则用默认 state.
     */
    private static BlockData parseBlock(JsonObject obj) {
        try {
            // 1. 位置
            JsonObject posObj = obj.has("startingPosition") ? obj.getAsJsonObject("startingPosition") : new JsonObject();
            int north = getInt(posObj, "northOffset", 0);
            int south = getInt(posObj, "southOffset", 0);
            int east  = getInt(posObj, "eastOffset", 0);
            int west  = getInt(posObj, "westOffset", 0);
            int h     = getInt(posObj, "heightOffset", 0);
            int x = east - west;
            int y = h;
            int z = south - north;
            BlockPos pos = new BlockPos(x, y, z);

            // 2. block id
            String domain = obj.has("blockDomain") ? obj.get("blockDomain").getAsString() : "minecraft";
            String name   = obj.has("blockName")   ? obj.get("blockName").getAsString()   : null;
            if (name == null || name.isEmpty()) return null;
            if (name.contains(":")) {
                // name 已经是 "namespace:path" 形式
            } else {
                name = domain + ":" + name;
            }
            ResourceLocation blockId = ResourceLocation.parse(name);
            Block block = BuiltInRegistries.BLOCK.get(blockId);
            if (block == null || block == Blocks.AIR) return null;
            BlockState state = block.defaultBlockState();

            // 3. 应用 properties
            if (obj.has("properties") && obj.get("properties").isJsonArray()) {
                JsonArray props = obj.getAsJsonArray("properties");
                state = applyProperties(state, block, props);
            }
            return new BlockData(pos, state);
        } catch (Exception e) {
            return null;
        }
    }

    /** 把 properties [{name, value}] 应用到 BlockState. */
    private static BlockState applyProperties(BlockState state, Block block, JsonArray props) {
        try {
            StateDefinition<Block, BlockState> def = block.getStateDefinition();
            for (JsonElement el : props) {
                JsonObject p = el.getAsJsonObject();
                String pname = p.get("name").getAsString();
                String pval  = p.get("value").getAsString();
                Property<?> prop = def.getProperty(pname);
                if (prop == null) continue;
                Optional<?> opt = prop.getValue(pval);
                if (opt.isEmpty()) continue;
                state = invokeSetValue(state, prop, opt.get());
            }
        } catch (Exception ignored) {}
        return state;
    }

    /** 调用 BlockState.setValue(property, value) - 用反射避免泛型问题. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <S, T extends Comparable<T>> BlockState invokeSetValue(BlockState state, Property<T> prop, Object value) {
        return state.setValue(prop, (T) value);
    }

    private static int getInt(JsonObject o, String key, int def) {
        return (o != null && o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsInt() : def;
    }

    private static byte[] readAllBytes(InputStream is) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** 在主线程初始化 Scene. */
    private static void initSceneForBlocks(Scene scene, TextElement sceneHint,
                                           List<BlockData> blocks, String variantName) {
        try {
            // 1) 先创建 TrackedDummyWorld, 把方块放进去
            //    ⚠ 顺序: 必须先 setBlock 再 createScene,
            //    因为 Scene.createScene 内部会调 dummyWorld.setBlockFilter(core::contains),
            //    此时 core 还是空的, 所有方块都会被过滤掉, 导致 3D 完全不显示.
            TrackedDummyWorld world = new TrackedDummyWorld();
            for (BlockData bd : blocks) {
                world.setBlockAndUpdate(bd.pos, bd.state);
            }

            // 2) 把所有 block pos 收集成 List 给 Scene.setRenderedCore
            List<BlockPos> positions = new ArrayList<>(blocks.size());
            for (BlockData bd : blocks) positions.add(bd.pos);

            // 3) 创建 Scene (内部会 setup blockFilter = core::contains, 然后把 core addRenderedBlocks)
            scene.createScene(world);

            // 4) 标记要渲染的核心方块 (autoCamera=true 会根据 bounds 自动算 zoom)
            scene.setRenderedCore(positions, null, true);

            // 5) 重新编译 cache
            scene.needCompileCache();

            setHint(sceneHint, "✓ " + variantName + " (" + blocks.size() + " 块)");
        } catch (Throwable t) {
            PrefabCustomAddon.LOGGER.error("[VANILLA-VIEW] initSceneForBlocks 失败", t);
            setHint(sceneHint, "渲染失败: " + t.getMessage());
        }
    }

    private static void setHint(TextElement el, String text) {
        if (el != null) el.setText(text);
    }
}
