package com.prefab.addon.client.gui;

import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.integration.BlueprintSpecStore;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * "制作蓝图" tab 的代码生成器: 把玩家填的表单 -> KubeJS 启动脚本 + 配方 JSON + 贴图资源.
 *
 * <p>输出三件事:</p>
 * <ol>
 *   <li>{@code <minecraft>/kubejs/startup_scripts/<ns>_<id>_blueprint.js}
 *       — 注册物品, 加 tag, 加到 prefab 创造模式物品栏 (默认 NBT 不在这里烤)</li>
 *   <li>{@code <minecraft>/kubejs/data/<ns>/recipes/<id>.json}
 *       — 9 宫格合成配方</li>
 *   <li>{@code <minecraft>/kubejs/assets/<ns>/textures/item/<id>.png}
 *       — 自动 resize 到 16x16 的贴图</li>
 * </ol>
 *
 * <p>玩家重启游戏 (或跑 /reload 但 startup_scripts 只在启动时跑) 后, KubeJS 跑 .js,
 * 物品就出现在 prefab 的"结构物品"创造模式 tab 里.</p>
 *
 * <p>mod 端的 {@code CustomBlueprintClientHandler} 认
 * {@code prefab_custom_addon:player_blueprint} tag, 所以右键行为跟原生 CustomBlueprintItem
 * 完全一致 (NBT 字段 packName / constructionId / locked).</p>
 */
public final class CustomBlueprintCodeGenerator {

    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9_]+$");

    private CustomBlueprintCodeGenerator() {}

    /**
     * 玩家表单 -> 输出三件套.
     *
     * @param spec 玩家填的表单 (见内嵌 {@link Spec})
     * @return 结果: 写出去的 3 个文件路径, 失败时抛异常
     */
    public static Result generate(Spec spec) throws IOException {
        validate(spec);

        Path mcDir = minecraftDir();
        Path kubejsDir = mcDir.resolve("kubejs");
        Path startupScripts = kubejsDir.resolve("startup_scripts");
        Path assetsTextures = kubejsDir.resolve("assets").resolve(spec.namespace).resolve("textures").resolve("item");

        Files.createDirectories(startupScripts);
        Files.createDirectories(assetsTextures);

        // 1) 贴图: 复制 + resize 到 16x16
        Path textureOut = assetsTextures.resolve(spec.itemId + ".png");
        copyAndResizeTo16(spec.sourceTexture, textureOut);

        // 2) KubeJS 启动脚本
        String js = buildKubeJSScript(spec);
        Path jsOut = startupScripts.resolve(spec.namespace + "_" + spec.itemId + "_blueprint.js");
        Files.writeString(jsOut, js);

        // 3) 配方 (写到 KubeJS server_scripts/, 走 ServerEvents.recipes 注册)
        // 关键: 之前错写到 kubejs/data/<ns>/recipes/<id>.json, 那个目录是 KubeJS datagen 输出
        // 不是配方源, Minecraft 配方系统根本不会加载 — JEI 搜不到配方
        // 正确路径: kubejs/server_scripts/<ns>_<id>_blueprint_recipes.js
        Path serverScripts = kubejsDir.resolve("server_scripts");
        Files.createDirectories(serverScripts);
        String recipeJs = buildRecipeJs(spec);
        Path recipeJsOut = serverScripts.resolve(spec.namespace + "_" + spec.itemId + "_blueprint_recipes.js");
        Files.writeString(recipeJsOut, recipeJs);

        // 4) 默认绑定规格: 脚本不再用 setCustomData 烤 NBT (1.20.1 KubeJS 2001.x 没这个 API,
        //    1.21.1 移植过来的写法会让启动直接 TypeError). 改写成规格文件, 读绑定处
        //    (服务端 AsyncBuildManager / 客户端 ClientHandler + tooltip) 按
        //    "物品栈 NBT 优先 → 规格文件兜底" 解析. 写失败只 warn 不阻断.
        BlueprintSpecStore.writeSpec(new BlueprintSpecStore.SpecEntry(
            spec.namespace, spec.itemId, spec.displayName,
            spec.packName, spec.constructionId, spec.locked));

        // 5) 旧脚本兼容: kubejs/startup_scripts/ 里残留的旧脚本 (带 setCustomData) 会让
        //    1.20.1 KubeJS 启动报 TypeError, 顺手重写成不含 modification 块的版本.
        migrateLegacyScripts(startupScripts);

        PrefabCustomAddon.LOGGER.info(
            "[MAKE-BLUEPRINT] generated for {}:{}/{} (building={}/{} locked={} recipeItems={})",
            spec.namespace, spec.itemId, spec.displayName,
            spec.packName, spec.constructionId, spec.locked, spec.recipeItems.size());
        PrefabCustomAddon.LOGGER.info("[MAKE-BLUEPRINT]   js         = {}", jsOut);
        PrefabCustomAddon.LOGGER.info("[MAKE-BLUEPRINT]   recipe js  = {}", recipeJsOut);
        PrefabCustomAddon.LOGGER.info("[MAKE-BLUEPRINT]   tex        = {}", textureOut);

        return new Result(jsOut, recipeJsOut, textureOut);
    }

    /**
     * 字段校验 + 前缀规范化:
     * <ul>
     *   <li>id 全部小写字母数字下划线, 配方至少 1 个非空材料</li>
     *   <li>命名空间: 用户在 GUI 填的是"子命名空间", 实际写入用 {@code player_<子命名空间>}
     *       (防撞 prefab / minecraft / 别的 mod). 子命名空间不能含 {@code player_} 字面量,
     *       已含则原样使用, 避免 {@code player_player_xxx} 这种嵌套.</li>
     * </ul>
     */
    private static void validate(Spec spec) {
        if (!ID_PATTERN.matcher(spec.namespace).matches()) {
            throw new IllegalArgumentException(
                "命名空间必须是 [a-z0-9_], 当前: " + spec.namespace);
        }
        if (!ID_PATTERN.matcher(spec.itemId).matches()) {
            throw new IllegalArgumentException(
                "物品 ID 必须是 [a-z0-9_], 当前: " + spec.itemId);
        }
        if (spec.displayName == null || spec.displayName.isBlank()) {
            throw new IllegalArgumentException("显示名不能为空");
        }
        if (spec.constructionId == null || spec.constructionId.isBlank()) {
            throw new IllegalArgumentException("未选择建筑");
        }
        if (spec.sourceTexture == null || !Files.exists(spec.sourceTexture)) {
            throw new IllegalArgumentException("未选择贴图文件");
        }
        if (spec.recipeItems == null || spec.recipeItems.isEmpty()) {
            throw new IllegalArgumentException("配方至少需要 1 个材料");
        }
        // 防 KubeJS 撞 prefab 自带物品
        if (spec.namespace.equals("prefab") || spec.namespace.equals("minecraft")) {
            throw new IllegalArgumentException(
                "命名空间不能是 'prefab' / 'minecraft', 会撞原版. 用 'my_pack' / 'mypack' 之类");
        }
        // 4A: 强制 player_ 前缀. 实际命名空间 = "player_" + 子命名空间 (若子命名空间已含 player_ 则不再加).
        if (!spec.namespace.startsWith("player_")) {
            spec.namespace = "player_" + spec.namespace;
        }
    }

    /**
     * 复制玩家贴图到目标位置.
     * <p>之前缩到 16x16 + nearest neighbor, 但玩家上传的如果是 64x64 / 256x256 高清图, 强制 16x16
     * 后再被 Minecraft 物品槽放大到 32x32 物理像素, 看着像"画质很差" (像素方块糊成一片).</p>
     *
     * <p>新方案: <b>不缩放, 原图复制</b>. Minecraft 1.21+ 物品贴图支持任意尺寸, 玩家选
     * 16x16 像素图 → 16x16 渲染 (标准 Minecraft 物品风格, 锐利); 选 32x32 像素图 → 32x32
     * (高细节); 选 256x256 真实图 → Minecraft 会 mipmap 模糊 (但不会比 16x16 更差).</p>
     *
     * <p>如果原图不是 2 的幂 (不是 16/32/64/128/256/512), 用 nearest neighbor 缩到最接近的 2 的幂.
     * 这样保持锐利, 不会糊.</p>
     */
    private static void copyAndResizeTo16(Path source, Path dest) throws IOException {
        BufferedImage src = ImageIO.read(source.toFile());
        if (src == null) {
            throw new IOException("无法读取图片: " + source);
        }
        int w = src.getWidth();
        int h = src.getHeight();
        // 标准 2 的幂: 16, 32, 64, 128, 256, 512
        // Minecraft 物品贴图 16x16 最经典, 32x32 是高细节上限
        int target = pickBestSize(w, h);
        if (w == target && h == target) {
            // 已经是 2 的幂 + 正方形 + 16+ → 原样写入, 不做任何处理 (保真)
            ImageIO.write(src, "png", dest.toFile());
            PrefabCustomAddon.LOGGER.info("[MAKE-BLUEPRINT] 贴图原样保存 ({}x{}): {}", w, h, dest);
            return;
        }
        // 不是 2 的幂 → nearest neighbor 缩到 target×target (保持锐利)
        BufferedImage dst = new BufferedImage(target, target, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_OFF);
            g.drawImage(src, 0, 0, target, target, null);
        } finally {
            g.dispose();
        }
        ImageIO.write(dst, "png", dest.toFile());
        PrefabCustomAddon.LOGGER.info("[MAKE-BLUEPRINT] 贴图缩放 {}x{} -> {}x{} (nearest): {}",
            w, h, target, target, dest);
    }

    /**
     * 给定原图尺寸, 选最合适的 2 的幂.
     * <ul>
     *   <li>≤ 16: 选 16 (标准 Minecraft 物品贴图, 不放大避免插值)</li>
     *   <li>17..32: 选 32 (高细节, 允许轻微拉伸)</li>
     *   <li>33..64: 选 64 (高清)</li>
     *   <li>更大: 选最接近的 2 的幂, 上限 256 (避免 512+ 触发 mipmap 模糊)</li>
     * </ul>
     */
    private static int pickBestSize(int w, int h) {
        int max = Math.max(w, h);
        if (max <= 16) return 16;
        if (max <= 32) return 32;
        if (max <= 64) return 64;
        if (max <= 128) return 128;
        if (max <= 256) return 256;
        return 256;  // 上限 256, 避免更大触发 mipmap 模糊
    }

    /**
     * 生成 KubeJS 启动脚本 (KubeJS 1.20.1 / 2001.x 兼容写法).
     * <ul>
     *   <li>{@code StartupEvents.registry('item')} — 注册物品 (贴图 + tag)</li>
     *   <li>{@code StartupEvents.modifyCreativeTab} — 加到 prefab 创造模式物品栏</li>
     * </ul>
     *
     * <p>注意: <b>不要</b>在脚本里用 {@code ItemEvents.modification} + {@code item.setCustomData}
     * 烤默认 NBT — 那是 KubeJS 1.21.1 (2100.x) 的 API, 1.20.1 (2001.x) 没有,
     * 启动会抛 {@code TypeError: Cannot find function setCustomData}.
     * 默认绑定数据 (packName / constructionId / locked) 改由模组从
     * {@code prefab-extension/.blueprint-specs/<itemId>.json} 规格文件解析
     * (见 {@link BlueprintSpecStore#resolveBinding}).</p>
     */
    static String buildKubeJSScript(Spec spec) {
        String safeName = spec.displayName.replace("\"", "\\\"");
        String nsItem = spec.namespace + ":" + spec.itemId;

        return ""
            + "// ============================================================\n"
            + "// Auto-generated by Prefab Custom Addon — 制作蓝图 tab\n"
            + "// Blueprint: " + safeName + "\n"
            + "// Item:     " + nsItem + "\n"
            + "// Building: " + spec.packName + "/" + spec.constructionId + "\n"
            + "// Locked:   " + spec.locked + "\n"
            + "// ============================================================\n"
            + "// 生效方式: 重启游戏 (startup_scripts 只在启动时跑, /reload 不会重跑)\n"
            + "// 默认绑定 (packName/constructionId/locked) 由模组从\n"
            + "// prefab-extension/.blueprint-specs/" + spec.itemId + ".json 读取, 本脚本不写 NBT.\n"
            + "\n"
            + "StartupEvents.registry('item', event => {\n"
            + "    event.create('" + nsItem + "')\n"
            + "        .displayName('" + safeName + "')\n"
            + "        .texture('" + spec.namespace + ":item/" + spec.itemId + "')\n"
            + "        .maxStackSize(1)\n"
            + "        .tag('prefab_custom_addon:player_blueprint');\n"
            + "})\n"
            + "\n"
            + "// 加到 prefab 创造模式物品栏 (Prefab 自带的 'Prefab' tab)\n"
            + "StartupEvents.modifyCreativeTab('prefab:prefab_items', event => {\n"
            + "    event.add('" + nsItem + "');\n"
            + "})\n";
    }

    // ============ 旧脚本兼容 (1.21.1 移植残留的 setCustomData 会让 1.20.1 KubeJS 启动报错) ============

    /** 旧脚本里 {@code event.create('ns:id')} 的匹配 (迁移时抠 namespace/itemId 用). */
    private static final Pattern CREATE_ITEM_PATTERN =
        Pattern.compile("event\\.create\\('([a-z0-9_]+):([a-z0-9_]+)'\\)");

    /**
     * 旧脚本 {@code ItemEvents.modification} 块 (含前面 "初始化 NBT" 注释行).
     * <p>生成器产出的块内部没有行首 {@code }}, 所以非贪婪匹配到第一个行首 {@code })} 就是块尾.</p>
     */
    private static final Pattern LEGACY_MODIFICATION_BLOCK = Pattern.compile(
        "(?ms)^(?:(?:// 初始化 NBT|// \\(KubeJS 的 setCustomData)[^\\n]*\\n)*"
            + "ItemEvents\\.modification\\s*\\(.*?^\\}\\)\\s*\\n?");

    /**
     * 扫描 startup_scripts/ 里其它 {@code *.js}, 把带 {@code setCustomData} 的本模组旧脚本
     * 重写为不含 modification 块的版本 (保留注册 / 创造栏部分).
     *
     * <p>识别: 文件名 {@code *_blueprint.js} 或内容带本模组生成头. 重写策略:
     * <ol>
     *   <li>从脚本内容抠 {@code event.create('ns:id')} 的 itemId,
     *       {@code .blueprint-specs/} 里有对应规格 → 按规格整个重新生成;</li>
     *   <li>找不到规格 → 保底正则删除 {@code ItemEvents.modification} 块.</li>
     * </ol>
     * 单个文件失败只 warn, 不影响其他文件 / 生成流程.</p>
     */
    static void migrateLegacyScripts(Path startupScripts) {
        if (!Files.isDirectory(startupScripts)) return;
        List<Path> scripts;
        try (Stream<Path> stream = Files.list(startupScripts)) {
            scripts = stream
                .filter(p -> p.getFileName().toString().endsWith(".js"))
                .collect(Collectors.toList());
        } catch (IOException e) {
            PrefabCustomAddon.LOGGER.warn("[MAKE-BLUEPRINT] 旧脚本扫描失败: {}", e.toString());
            return;
        }
        for (Path script : scripts) {
            try {
                String name = script.getFileName().toString();
                String content = Files.readString(script);
                if (!content.contains("setCustomData")) continue;
                boolean ours = name.endsWith("_blueprint.js")
                    || content.contains("Auto-generated by Prefab Custom Addon");
                if (!ours) continue;

                String rewritten = null;
                Matcher m = CREATE_ITEM_PATTERN.matcher(content);
                if (m.find()) {
                    BlueprintSpecStore.SpecEntry entry = BlueprintSpecStore.readSpec(m.group(2));
                    if (entry != null && !entry.packName().isEmpty()
                        && !entry.constructionId().isEmpty()) {
                        Spec legacy = new Spec();
                        legacy.namespace = m.group(1);
                        legacy.itemId = m.group(2);
                        legacy.displayName = entry.displayName() != null
                            ? entry.displayName() : m.group(2);
                        legacy.packName = entry.packName();
                        legacy.constructionId = entry.constructionId();
                        legacy.locked = entry.locked();
                        rewritten = buildKubeJSScript(legacy);
                    }
                }
                if (rewritten == null) {
                    // 保底: 正则删掉 modification 块 (含注释行), 保留注册 / 创造栏部分
                    rewritten = LEGACY_MODIFICATION_BLOCK.matcher(content).replaceAll("");
                }
                if (!rewritten.equals(content)) {
                    Files.writeString(script, rewritten);
                    PrefabCustomAddon.LOGGER.warn(
                        "[MAKE-BLUEPRINT] 迁移旧启动脚本 (移除 1.21 setCustomData): {}", script);
                }
            } catch (Exception e) {
                PrefabCustomAddon.LOGGER.warn("[MAKE-BLUEPRINT] 旧脚本迁移失败 ({}): {}",
                    script, e.toString());
            }
        }
    }

    /**
     * 把 9 宫格配方编码成 KubeJS server script.
     * <p>之前是写成 JSON 写到 {@code kubejs/data/<ns>/recipes/}, 但 KubeJS 不会从 {@code kubejs/data/}
     * 加载配方 (那是 datagen 输出目录), 玩家在 JEI 搜不到这个配方.
     * 正确做法: 写到 {@code kubejs/server_scripts/}, 走 {@code ServerEvents.recipes} 注册,
     * KubeJS 在加载 server scripts 时会调用, 配方才进 Minecraft 配方系统.</p>
     */
    static String buildRecipeJs(Spec spec) {
        // 收集去重的 key -> item
        List<String> distinctItems = new ArrayList<>();
        // pattern[9] 是 'A'/'B'/'C'/...
        String[] pattern = new String[9];
        for (int i = 0; i < 9; i++) {
            String item = null;
            if (i < spec.recipeItems.size()) {
                String s = spec.recipeItems.get(i);
                if (s != null && !s.isBlank()) item = s.trim();
            }
            if (item == null) {
                pattern[i] = " ";
            } else {
                int keyIdx = distinctItems.indexOf(item);
                if (keyIdx < 0) {
                    distinctItems.add(item);
                    keyIdx = distinctItems.size() - 1;
                }
                pattern[i] = String.valueOf((char) ('A' + keyIdx));
            }
        }

        String nsItem = spec.namespace + ":" + spec.itemId;
        StringBuilder sb = new StringBuilder();
        sb.append("// ============================================================\n");
        sb.append("// Auto-generated by Prefab Custom Addon — 制作蓝图 tab\n");
        sb.append("// Recipe for: ").append(nsItem).append("\n");
        sb.append("// ============================================================\n");
        sb.append("// 生效方式: /reload 即可 (server_scripts 会在 reload 时跑)\n");
        sb.append("\n");
        sb.append("ServerEvents.recipes(event => {\n");

        // 9 宫格全空 → 不写配方, 只留注释
        if (distinctItems.isEmpty()) {
            sb.append("    // 玩家没填 9 宫格配方, 跳过\n");
        } else {
            // event.shaped(result, pattern_array, key_map)
            sb.append("    event.shaped(\n");
            sb.append("        '").append(nsItem).append("',\n");
            // pattern: 3 行, 每行 3 字符
            sb.append("        [\n");
            for (int row = 0; row < 3; row++) {
                String line = "" + pattern[row*3] + pattern[row*3+1] + pattern[row*3+2];
                sb.append("            '").append(line).append("'");
                if (row < 2) sb.append(",");
                sb.append("\n");
            }
            sb.append("        ],\n");
            // key map
            sb.append("        {\n");
            for (int i = 0; i < distinctItems.size(); i++) {
                String key = String.valueOf((char) ('A' + i));
                String item = distinctItems.get(i);
                sb.append("            ").append(key).append(": '").append(item).append("'");
                if (i < distinctItems.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("        }\n");
            sb.append("    )\n");
        }

        sb.append("})\n");
        return sb.toString();
    }

    private static Path minecraftDir() {
        // 客户端: Minecraft.getInstance().gameDirectory.toPath()
        // 服务端: 单独处理 (这个 generator 应该在 client-only 流程里调)
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null) {
            throw new IllegalStateException("CustomBlueprintCodeGenerator 必须在 client 端调用");
        }
        return mc.gameDirectory.toPath();
    }

    /** 玩家表单的所有字段. */
    public static final class Spec {
        public String namespace = "player_pack";
        public String itemId;
        public String displayName;
        public String packName;            // "local" / zip pack name
        public String constructionId;
        public boolean locked = true;
        public Path sourceTexture;         // 玩家电脑上的 .png
        public List<String> recipeItems = new ArrayList<>(); // size <= 9, "minecraft:paper" 之类
    }

    /** 输出三件套路径. */
    public static final class Result {
        public final Path jsFile;
        public final Path jsonFile;
        public final Path textureFile;
        public Result(Path jsFile, Path jsonFile, Path textureFile) {
            this.jsFile = jsFile;
            this.jsonFile = jsonFile;
            this.textureFile = textureFile;
        }
    }
}
