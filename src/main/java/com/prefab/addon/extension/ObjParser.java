package com.prefab.addon.extension;

import com.prefab.addon.PrefabCustomAddon;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 极简 OBJ + MTL 解析器.
 * 支持 v, vn, vt, f, g, o, usemtl, mtllib, map_Kd (贴图).
 * 不支持: 曲线/曲面, 自由形式, 多边形孔洞.
 *
 * <h3>颜色来源优先级 (从高到低)</h3>
 * <ol>
 *   <li>材质 Kd 颜色 (从 .mtl 解析, 非默认灰)</li>
 *   <li>顶点颜色 (v x y z r g b)</li>
 *   <li><b>贴图采样 (map_Kd PNG, 按 UV 中心插值)</b> — 关键: 混元 AI 生成的 OBJ 把颜色放在 PNG 贴图里,
 *       Kd 颜色通常是占位灰 0.5/0.5/0.5. 必须加载贴图按 UV 采样才能还原猫的橙/白/黑/绿眼等.</li>
 *   <li>灰色兜底 0.5/0.5/0.5</li>
 * </ol>
 */
public class ObjParser {

    public static class Result {
        public final ObjModel model;
        public final List<String> warnings;
        public Result(ObjModel m, List<String> w) { this.model = m; this.warnings = w; }
    }

    public static Result parse(Path objFile) throws IOException {
        ObjModel model = new ObjModel();
        List<String> warnings = new ArrayList<>();

        // 0) 扫描 OBJ 文件里所有 mtllib 指令, 收集 .mtl 路径 (混元 AI 生成的 OBJ 写的是
        //    `mtllib material.mtl` 而不是 `<obj名>.mtl`, 之前的 resolveMtl 找不到).
        //    多个 mtllib 用空格分隔, 这里用第一个能找到的.
        List<Path> mtlCandidates = new ArrayList<>();
        mtlCandidates.add(resolveMtl(objFile));  // 同名 .mtl (Blender 默认导出风格)
        try (BufferedReader scan = Files.newBufferedReader(objFile)) {
            String line;
            while ((line = scan.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.toLowerCase(Locale.ROOT).startsWith("mtllib ")) {
                    String[] parts = line.substring(7).trim().split("\\s+");
                    for (String p : parts) {
                        if (p.isEmpty()) continue;
                        // mtllib 路径相对 .obj 所在目录
                        Path resolved = objFile.getParent() != null
                                ? objFile.getParent().resolve(p) : null;
                        if (resolved != null) mtlCandidates.add(resolved);
                    }
                }
            }
        } catch (IOException e) {
            warnings.add("扫描 mtllib 失败: " + e.getMessage());
        }

        // 1) 加载第一个能找到的 .mtl, 同时按 map_Kd 加载贴图
        Path mtlFile = null;
        for (Path p : mtlCandidates) {
            if (p != null && Files.exists(p)) { mtlFile = p; break; }
        }
        if (mtlFile != null) {
            try (BufferedReader r = Files.newBufferedReader(mtlFile)) {
                parseMtl(r, mtlFile.getParent(), model, warnings);
            } catch (IOException e) {
                warnings.add("无法读取 MTL 文件: " + e.getMessage());
            }
        }

        // 2) 解析 OBJ
        String currentMaterial = null;
        String currentGroupName = "default";
        ObjModel.Group currentGroup = new ObjModel.Group(currentGroupName);
        model.groups.add(currentGroup);

        try (BufferedReader r = Files.newBufferedReader(objFile)) {
            String line;
            int lineNo = 0;
            while ((line = r.readLine()) != null) {
                lineNo++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] tok = line.split("\\s+");
                String head = tok[0].toLowerCase(Locale.ROOT);

                try {
                    switch (head) {
                        case "v": {
                            // OBJ v 格式:
                            //   标准: v x y z
                            //   带颜色: v x y z r g b   (r/g/b 范围 0~1, 0~255 都可能)
                            //   带法线: v x y z nx ny nz
                            //   同时有: v x y z r g b nx ny nz  (按这个顺序)
                            float x = Float.parseFloat(tok[1]);
                            float y = Float.parseFloat(tok[2]);
                            float z = Float.parseFloat(tok[3]);
                            float[] color = null;
                            if (tok.length >= 7) {
                                // 尝试解析为 RGB
                                try {
                                    float vR = Float.parseFloat(tok[4]);
                                    float vG = Float.parseFloat(tok[5]);
                                    float vB = Float.parseFloat(tok[6]);
                                    // 归一化到 0~1: 如果某个值 > 1, 说明是 0~255 整数色
                                    if (vR > 1f || vG > 1f || vB > 1f) {
                                        vR = vR / 255f; vG = vG / 255f; vB = vB / 255f;
                                    }
                                    // 验证是有效颜色 (值在 [0,2] 范围内)
                                    if (vR >= 0f && vR <= 2f && vG >= 0f && vG <= 2f && vB >= 0f && vB <= 2f) {
                                        color = new float[]{vR, vG, vB};
                                    }
                                } catch (NumberFormatException ignored) {
                                    // 后 3 个不是数字, 可能是法线
                                }
                            }
                            model.vertices.add(new float[]{x, y, z});
                            if (color != null) {
                                model.vertexColors.add(color);
                            } else {
                                model.vertexColors.add(null); // 保持索引对齐
                            }
                            break;
                        }
                        case "vn": {
                            float x = Float.parseFloat(tok[1]);
                            float y = Float.parseFloat(tok[2]);
                            float z = Float.parseFloat(tok[3]);
                            model.normals.add(new float[]{x, y, z});
                            break;
                        }
                        case "vt": {
                            float u = Float.parseFloat(tok[1]);
                            float v = tok.length > 2 ? Float.parseFloat(tok[2]) : 0f;
                            model.texCoords.add(new float[]{u, v});
                            break;
                        }
                        case "f": {
                            int n = tok.length - 1;
                            int[] vi = new int[n];
                            int[] ni = new int[n];
                            int[] ti = new int[n]; // 顶点 UV 索引
                            for (int i = 0; i < n; i++) {
                                String[] parts = tok[i + 1].split("/");
                                vi[i] = parseIndex(parts[0], model.vertices.size());
                                if (parts.length > 1 && !parts[1].isEmpty()) {
                                    ti[i] = parseIndex(parts[1], model.texCoords.size());
                                }
                                if (parts.length > 2 && !parts[2].isEmpty()) {
                                    ni[i] = parseIndex(parts[2], model.normals.size());
                                }
                            }
                            // 三角化 (fan triangulation)
                            for (int i = 1; i < n - 1; i++) {
                                int[] triV = { vi[0], vi[i], vi[i + 1] };
                                int[] triN = (ni[0] != 0)
                                        ? new int[]{ ni[0], ni[i], ni[i + 1] }
                                        : new int[]{ 0, 0, 0 };
                                int[] triT = { ti[0], ti[i], ti[i + 1] };
                                // 颜色优先级: 1) 材质颜色 (MTL Kd 非默认)  2) 顶点颜色平均
                                //            3) 贴图采样 (3 个顶点 UV 平均对应像素)  4) 灰色兜底
                                float[] color = null;
                                // 关键: 混元 AI 生成的 OBJ 写的是 "Kd 0.800 0.800 0.800" 占位灰,
                                // 真正的颜色在 map_Kd 贴图里. 所以优先级要变成
                                //   1) 贴图采样 (如果该材质有 map_Kd 贴图)  ← 混元的情况
                                //   2) 顶点颜色平均 (Blender "Vertex Colors" 导出)
                                //   3) MTL Kd 颜色 (非默认灰, 比如 Blender 手动指定)
                                //   4) 灰色兜底
                                // 之前 1=Kd 2=vertex 3=texture 的顺序导致混元全部走 Kd 0.8 → 渲染成白猫.
                                if (currentMaterial != null
                                        && model.materialTextures.containsKey(currentMaterial)) {
                                    // 优先走贴图采样
                                    BufferedImage tex = model.materialTextures.get(currentMaterial);
                                    color = sampleTextureByUV(tex, triT, model.texCoords);
                                }
                                if (color == null) {
                                    if (currentMaterial != null) {
                                        float[] mtl = model.materialColors.get(currentMaterial);
                                        if (mtl != null && !(mtl[0] == 0.5f && mtl[1] == 0.5f && mtl[2] == 0.5f)) {
                                            color = mtl;
                                        }
                                    }
                                }
                                if (color == null) {
                                    // 尝试用顶点颜色平均 (三角面 3 个顶点的颜色均值)
                                    float[] c0 = (vi[0] >= 0 && vi[0] < model.vertexColors.size()) ? model.vertexColors.get(vi[0]) : null;
                                    float[] c1 = (vi[i] >= 0 && vi[i] < model.vertexColors.size()) ? model.vertexColors.get(vi[i]) : null;
                                    float[] c2 = (vi[i+1] >= 0 && vi[i+1] < model.vertexColors.size()) ? model.vertexColors.get(vi[i+1]) : null;
                                    if (c0 != null || c1 != null || c2 != null) {
                                        float avgR = 0, avgG = 0, avgB = 0; int cnt = 0;
                                        if (c0 != null) { avgR += c0[0]; avgG += c0[1]; avgB += c0[2]; cnt++; }
                                        if (c1 != null) { avgR += c1[0]; avgG += c1[1]; avgB += c1[2]; cnt++; }
                                        if (c2 != null) { avgR += c2[0]; avgG += c2[1]; avgB += c2[2]; cnt++; }
                                        if (cnt > 0) color = new float[]{ avgR/cnt, avgG/cnt, avgB/cnt };
                                    }
                                }
                                if (color == null) {
                                    color = new float[]{0.5f, 0.5f, 0.5f};
                                }
                                currentGroup.faces.add(new ObjModel.Face(triV, triN, triT, currentMaterial, currentGroupName, color));
                            }
                            break;
                        }
                        case "g":
                        case "o": {
                            if (tok.length > 1) {
                                currentGroupName = tok[1];
                                currentGroup = new ObjModel.Group(currentGroupName);
                                model.groups.add(currentGroup);
                            }
                            break;
                        }
                        case "usemtl": {
                            if (tok.length > 1) currentMaterial = tok[1];
                            break;
                        }
                        case "s":
                        default:
                            // 静默忽略未实现的指令 (mtllib 已在外面处理)
                    }
                } catch (Exception e) {
                    if (warnings.size() < 50) {
                        warnings.add("第 " + lineNo + " 行解析失败: " + line);
                    }
                }
            }
        }

        // 3) 计算包围盒
        computeBounds(model);

        // 4) 颜色源统计 — 重要: 大量面用 fallback 灰 0.5/0.5/0.5 → 说明 OBJ 没颜色信息
        //    (没 .mtl 文件 / .mtl 没 Kd / 顶点也没 v x y z r g b / 也没贴图), 这种无法还原颜色.
        int totalFaces = 0;
        int coloredByMaterial = 0;
        int coloredByVertex = 0;
        int coloredByTexture = 0;  // 关键: 来自贴图采样 (map_Kd)
        int fallBackGray = 0;
        for (ObjModel.Group g : model.groups) {
            for (ObjModel.Face f : g.faces) {
                totalFaces++;
                if (f.color == null) continue;
                boolean isDefaultGray = (Math.abs(f.color[0] - 0.5f) < 0.001f
                        && Math.abs(f.color[1] - 0.5f) < 0.001f
                        && Math.abs(f.color[2] - 0.5f) < 0.001f);
                if (isDefaultGray) {
                    fallBackGray++;
                } else if (f.materialName != null && !f.materialName.isEmpty()
                           && model.materialColors.containsKey(f.materialName)) {
                    float[] mtl = model.materialColors.get(f.materialName);
                    if (mtl != null && !(mtl[0] == 0.5f && mtl[1] == 0.5f && mtl[2] == 0.5f)) {
                        coloredByMaterial++;
                    } else {
                        // Kd 是默认灰, 但 face.color 不是灰 → 来自贴图采样
                        coloredByTexture++;
                    }
                } else {
                    coloredByVertex++;
                }
            }
        }
        int nonNullVertexColors = 0;
        for (float[] vc : model.vertexColors) {
            if (vc != null) nonNullVertexColors++;
        }

        PrefabCustomAddon.LOGGER.info(
            "OBJ 解析完成: {} 顶点 ({} 有颜色), {} 法线, {} 面, {} 材质 ({} 有 Kd 颜色), {} group, {} 贴图",
                model.vertices.size(), nonNullVertexColors, model.normals.size(),
                totalFaces, model.materialColors.size(), countNonDefaultMaterials(model),
                model.groups.size(), model.materialTextures.size());
        PrefabCustomAddon.LOGGER.info(
            "OBJ 颜色来源统计: 材质色={}, 顶点色={}, 贴图采样={}, fallback灰={}, 总面={}, fallback占比={:.1f}%",
                coloredByMaterial, coloredByVertex, coloredByTexture, fallBackGray, totalFaces,
                totalFaces > 0 ? 100.0 * fallBackGray / totalFaces : 0.0);
        if (!model.materialTextures.isEmpty()) {
            for (var e : model.materialTexturePaths.entrySet()) {
                PrefabCustomAddon.LOGGER.info("  贴图: material='{}' -> {}", e.getKey(), e.getValue());
            }
        }
        if (totalFaces > 0 && (coloredByMaterial + coloredByVertex) == 0) {
            PrefabCustomAddon.LOGGER.warn(
                "OBJ 完全没有颜色信息 (没 .mtl 材质 Kd, 也没顶点颜色)!");
            PrefabCustomAddon.LOGGER.warn(
                "要还原颜色, 重新导出 OBJ 时: 1) Blender 勾选 'Vertex Colors' 或 2) 加 .mtl 文件 + Kd 颜色");
            // 即使没颜色, 仍然尝试从 group/material 名关键字推断 (比如猫身→橙, 眼→绿, 鼻→粉)
            // 列出所有 group 名, 方便玩家/调试查看 OBJ 实际结构
            if (!model.groups.isEmpty()) {
                StringBuilder sb = new StringBuilder("OBJ 包含的 group / object 清单 (前 20): ");
                int max = Math.min(20, model.groups.size());
                for (int i = 0; i < max; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append('"').append(model.groups.get(i).name).append('"');
                }
                if (model.groups.size() > 20) sb.append(" ... (+").append(model.groups.size() - 20).append(" more)");
                PrefabCustomAddon.LOGGER.info(sb.toString());
                PrefabCustomAddon.LOGGER.info(
                    "将从这些名字关键字推断颜色 (例: 'eye'→绿, 'cat/body/fur'→橙, 'nose'→粉, 'white'→白, 'black'→黑)");
            }
        }

        return new Result(model, warnings);
    }

    private static int countNonDefaultMaterials(ObjModel m) {
        int c = 0;
        for (float[] rgb : m.materialColors.values()) {
            if (rgb != null && !(rgb[0] == 0.5f && rgb[1] == 0.5f && rgb[2] == 0.5f)) c++;
        }
        return c;
    }

    private static int countFaces(ObjModel m) {
        int c = 0;
        for (ObjModel.Group g : m.groups) c += g.faces.size();
        return c;
    }

    private static int parseIndex(String s, int currentSize) {
        int idx = Integer.parseInt(s);
        if (idx < 0) return currentSize + idx; // 相对
        return idx - 1; // OBJ 1-based -> 0-based
    }

    private static Path resolveMtl(Path objFile) {
        // 优先同目录, 同名 .mtl
        Path sibling = objFile.resolveSibling(stripExtension(objFile.getFileName().toString()) + ".mtl");
        if (Files.exists(sibling)) return sibling;
        return null;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void parseMtl(BufferedReader r, Path mtlDir, ObjModel model, List<String> warnings) throws IOException {
        String current = null;
        String line;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] tok = line.split("\\s+");
            switch (tok[0].toLowerCase(Locale.ROOT)) {
                case "newmtl":
                    if (tok.length > 1) current = tok[1];
                    break;
                case "kd":
                case "kdrgb":
                    if (current != null && tok.length >= 4) {
                        try {
                            float rr = Float.parseFloat(tok[1]);
                            float gg = Float.parseFloat(tok[2]);
                            float bb = Float.parseFloat(tok[3]);
                            model.materialColors.put(current, new float[]{ rr, gg, bb });
                        } catch (NumberFormatException ignored) {}
                    }
                    break;
                case "map_kd":
                    // 关键: 加载贴图 — 路径相对 .mtl 所在目录. 混元 AI 生成的 OBJ 把颜色放在 PNG 贴图里,
                    // 不读这个就无法还原猫的橙/白/黑等.
                    if (current != null && tok.length >= 2 && mtlDir != null) {
                        // map_Kd 后面可能跟选项 (-s, -o 等), 提取最后一个非选项参数 (通常是文件路径)
                        String texPath = null;
                        for (int i = tok.length - 1; i >= 1; i--) {
                            String s = tok[i];
                            if (s.startsWith("-")) continue; // 跳过选项
                            texPath = s;
                            break;
                        }
                        if (texPath != null) {
                            try {
                                Path resolved = mtlDir.resolve(texPath);
                                if (Files.exists(resolved)) {
                                    BufferedImage img = ImageIO.read(resolved.toFile());
                                    if (img != null) {
                                        model.materialTextures.put(current, img);
                                        model.materialTexturePaths.put(current, resolved.toString());
                                        PrefabCustomAddon.LOGGER.info(
                                            "[OBJ] 加载贴图: material='{}' path='{}' size={}x{}",
                                            current, resolved, img.getWidth(), img.getHeight());
                                    }
                                } else {
                                    PrefabCustomAddon.LOGGER.warn(
                                        "[OBJ] 贴图文件不存在: material='{}' path='{}'", current, resolved);
                                }
                            } catch (IOException e) {
                                warnings.add("加载贴图失败 (" + texPath + "): " + e.getMessage());
                            }
                        }
                    }
                    break;
                default:
                    // 忽略 Ka, Ks, d, Tr 等
            }
        }
    }

    /**
     * 按三角面 3 个顶点的 UV 平均, 在贴图上采样一个 RGB 颜色.
     * <p>简化方案: 取 3 个顶点 UV 各采样一个像素, RGB 取平均 — 比中心点采样更稳定
     * (避免一个顶点恰好落在贴图边界外的边角).</p>
     * <p>注意: OBJ 的 V 坐标原点在左下, BufferedImage 的 Y 坐标原点在左上, 需要 v = 1 - v.</p>
     */
    private static float[] sampleTextureByUV(BufferedImage tex, int[] triT, List<float[]> texCoords) {
        int w = tex.getWidth();
        int h = tex.getHeight();
        if (w <= 0 || h <= 0) return null;
        float sumR = 0, sumG = 0, sumB = 0;
        int sampled = 0;
        for (int k = 0; k < 3; k++) {
            int tIdx = triT[k];
            if (tIdx < 0 || tIdx >= texCoords.size()) continue;
            float[] uv = texCoords.get(tIdx);
            if (uv == null || uv.length < 2) continue;
            // UV wrap: 某些 OBJ 文件 UV 超出 [0,1] (重复贴图)
            float u = uv[0] - (float)Math.floor(uv[0]);
            float v = uv[1] - (float)Math.floor(uv[1]);
            // V 翻转: OBJ V 原点在左下, 贴图 Y 原点在左上
            int px = Math.max(0, Math.min(w - 1, (int)(u * (w - 1))));
            int py = Math.max(0, Math.min(h - 1, (int)((1f - v) * (h - 1))));
            int rgb = tex.getRGB(px, py);
            // 跳过完全透明像素 (alpha < 30)
            int a = (rgb >>> 24) & 0xFF;
            if (a < 30) continue;
            sumR += ((rgb >> 16) & 0xFF) / 255f;
            sumG += ((rgb >>  8) & 0xFF) / 255f;
            sumB += ( rgb        & 0xFF) / 255f;
            sampled++;
        }
        if (sampled == 0) return null;
        return new float[]{ sumR / sampled, sumG / sampled, sumB / sampled };
    }

    private static void computeBounds(ObjModel m) {
        if (m.vertices.isEmpty()) return;
        float[] v0 = m.vertices.get(0);
        m.minX = m.maxX = v0[0];
        m.minY = m.maxY = v0[1];
        m.minZ = m.maxZ = v0[2];
        for (float[] v : m.vertices) {
            m.minX = Math.min(m.minX, v[0]); m.maxX = Math.max(m.maxX, v[0]);
            m.minY = Math.min(m.minY, v[1]); m.maxY = Math.max(m.maxY, v[1]);
            m.minZ = Math.min(m.minZ, v[2]); m.maxZ = Math.max(m.maxZ, v[2]);
        }
        m.boundsComputed = true;
    }
}
