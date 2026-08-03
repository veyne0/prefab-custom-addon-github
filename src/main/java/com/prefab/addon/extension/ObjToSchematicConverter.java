package com.prefab.addon.extension;

import com.prefab.addon.PrefabCustomAddon;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * OBJ → vanilla structure NBT 转换器.
 *
 * <p>体素化策略 (简化版, 实心模式):
 * 1. 根据 OBJ 包围盒 + 缩放/分辨率参数, 建立 3D 整数栅格.
 * 2. 遍历每个三角形, 标记所有被三角形覆盖 (AABBox 命中) 的体素为"有材质".
 * 3. 用光线投射判定"内部"体素 (奇数次相交), 区分外壳与实体.
 * 4. 把每个体素颜色映射到最近的 Minecraft 方块, 写入 vanilla palette + blocks 列表.
 *
 * <p>输出 vanilla structure NBT (1.21.1+ 格式), 用 GZIP 压缩, 字段:
 *   - size: ListTag<3 IntTag> [w, h, l]
 *   - palette: ListTag<CompoundTag {Name, Properties?}>
 *   - blocks: ListTag<CompoundTag {pos: [x,y,z], state: {Name, Properties?}}>
 *
 * <p>直接输出 vanilla 格式 (而不是 Sponge v2 中转), 避免 NbtFormatConverter 在
 * varint BlockData 上踩坑. 你的 NbtStructureParser / SpongeSchematicParser
 * 已经支持 vanilla NBT.</p>
 */
public class ObjToSchematicConverter {

    /** 进度回调: fraction ∈ [0,1], message 是阶段描述 (用于 GUI 显示) */
    public interface IProgress {
        void update(float fraction, String message);
    }

    /** 转换选项 */
    public static class Options {
        /** 每米的体素数量 (默认 32 = 1 voxel ≈ 3.1cm). 4 太粗, 32 能保留猫这类模型的细节. */
        public int voxelsPerMeter = 32;
        /** true = 实心填充内部体素; false = 仅保留表面 (空心). 玩家做装饰/雕像用空心更省方块. */
        public boolean fillInterior = false;
        /** Y 是否向上 (OBJ 多数用 -Y 为上, 这里默认 +Y 向上, 即 Blender 风格). */
        public boolean yUp = true;
        /** 单个 OBJ 最大体素数量上限, 超过则停止并报错 (避免 OOM). */
        public int maxVoxels = 384 * 384 * 384;
        /** 三角形-体素扫描精度 (1 = 每个 voxel 都尝试填, 2 = 跳一个填一个, 速度 x8 但方块数 1/8).
         *  默认 1 让 X64 实际生成 6-10 万方块, 够精细. 之前默认 2 → 8 万变 1 万, 太稀疏. */
        public int sampleStep = 1;
        /** 表面强化: 对表面体素做 1 层膨胀, 防止非水密 OBJ (如猫) 出现孔洞. */
        public boolean strengthenSurface = true;
        /** 进度回调 (可选). null = 同步无进度; 非 null = 异步, 会在 worker 线程被调用.
         *  实现方需自行处理线程间通信 (如 volatile field + 主线程 tick 读). */
        public IProgress progressCallback = null;
    }

    public static class Result {
        public final byte[] schematicBytes;   // gzip NBT bytes
        public final int width, height, length;
        public final int blockCount;
        public final List<String> warnings;
        public final long elapsedMs;

        public Result(byte[] data, int w, int h, int l, int count, List<String> w_, long t) {
            this.schematicBytes = data; this.width = w; this.height = h; this.length = l;
            this.blockCount = count; this.warnings = w_; this.elapsedMs = t;
        }
    }

    /** 入口: OBJ 文件 → .schem 二进制 (gzip NBT). */
    public static Result convertToSchematic(Path objFile, Options opt) throws IOException {
        long t0 = System.currentTimeMillis();
        IProgress cb = opt.progressCallback;
        // 每次转换开始时清空调色板, 避免上次转换残留
        resetPalette();
        if (cb != null) cb.update(0.02f, "解析 OBJ 文件...");
        ObjParser.Result parsed = ObjParser.parse(objFile);
        ObjModel model = parsed.model;
        List<String> warnings = new ArrayList<>(parsed.warnings);
        if (cb != null) cb.update(0.10f, "OBJ 解析完成 (" + model.vertices.size() + " 顶点, "
                + countAllFaces(model) + " 面)");

        if (model.vertices.isEmpty()) {
            throw new IOException("OBJ 文件不包含任何顶点");
        }

        // 计算缩放 + 栅格尺寸
        float sizeX = model.maxX - model.minX;
        float sizeY = model.maxY - model.minY;
        float sizeZ = model.maxZ - model.minZ;
        float maxDim = Math.max(sizeX, Math.max(sizeY, sizeZ));
        if (maxDim <= 0.0001f) throw new IOException("模型尺寸为 0");

        float voxelsPerUnit = opt.voxelsPerMeter;
        int width  = Math.max(1, Math.round(sizeX * voxelsPerUnit));
        int height = Math.max(1, Math.round(sizeY * voxelsPerUnit));
        int length = Math.max(1, Math.round(sizeZ * voxelsPerUnit));

        // 防止过长一边
        long total = (long) width * height * length;
        if (total > opt.maxVoxels) {
            float scale = (float) Math.cbrt(opt.maxVoxels / (double) total);
            width  = Math.max(1, (int)(width  * scale));
            height = Math.max(1, (int)(height * scale));
            length = Math.max(1, (int)(length * scale));
            warnings.add("模型过大, 已自动缩放体素栅格到 " + width + "x" + height + "x" + length);
        }

        PrefabCustomAddon.LOGGER.info("体素栅格: {}x{}x{} ({} 体素)", width, height, length, (long)width*height*length);
        if (cb != null) cb.update(0.15f, String.format("体素栅格: %dx%dx%d", width, height, length));

        // 体素数据: -1 = 空, >=0 = 调色板索引
        int[] voxels = new int[width * height * length];
        java.util.Arrays.fill(voxels, -1);

        // 收集所有面 (跨 group)
        List<ObjModel.Face> allFaces = new ArrayList<>();
        for (ObjModel.Group g : model.groups) allFaces.addAll(g.faces);
        if (allFaces.isEmpty()) throw new IOException("OBJ 文件没有面");

        // 1) 标记被任何三角形 AABB 覆盖的体素 (表面候选)
        boolean[] surface = new boolean[voxels.length];

        float[] origin = new float[]{ model.minX, model.minY, model.minZ };
        float cellX = sizeX / width;
        float cellY = sizeY / height;
        float cellZ = sizeZ / length;
        if (cellX <= 0) cellX = 0.001f;
        if (cellY <= 0) cellY = 0.001f;
        if (cellZ <= 0) cellZ = 0.001f;

        for (int faceIdx = 0; faceIdx < allFaces.size(); faceIdx++) {
            ObjModel.Face face = allFaces.get(faceIdx);
            if (face.vertexIndices.length < 3) continue;
            // 进度回调: 0.15 -> 0.55, 按 face 比例
            if (cb != null && (faceIdx & 0x3FFF) == 0) {
                float frac = 0.15f + 0.40f * (float) faceIdx / allFaces.size();
                cb.update(frac, String.format("体素化中... %d / %d (%.0f%%)",
                    faceIdx, allFaces.size(), 100f * faceIdx / allFaces.size()));
            }
            float[] a = model.vertices.get(face.vertexIndices[0]);
            float[] b = model.vertices.get(face.vertexIndices[1]);
            float[] c = model.vertices.get(face.vertexIndices[2]);
            // 转体素坐标
            int ax = clamp((int)((a[0] - origin[0]) / cellX), 0, width  - 1);
            int ay = clamp((int)((a[1] - origin[1]) / cellY), 0, height - 1);
            int az = clamp((int)((a[2] - origin[2]) / cellZ), 0, length - 1);
            int bx = clamp((int)((b[0] - origin[0]) / cellX), 0, width  - 1);
            int by = clamp((int)((b[1] - origin[1]) / cellY), 0, height - 1);
            int bz = clamp((int)((b[2] - origin[2]) / cellZ), 0, length - 1);
            int cx = clamp((int)((c[0] - origin[0]) / cellX), 0, width  - 1);
            int cy = clamp((int)((c[1] - origin[1]) / cellY), 0, height - 1);
            int cz = clamp((int)((c[2] - origin[2]) / cellZ), 0, length - 1);

            int minX = Math.min(ax, Math.min(bx, cx));
            int maxX = Math.max(ax, Math.max(bx, cx));
            int minY = Math.min(ay, Math.min(by, cy));
            int maxY = Math.max(ay, Math.max(by, cy));
            int minZ = Math.min(az, Math.min(bz, cz));
            int maxZ = Math.max(az, Math.max(bz, cz));

            int colorIdx = registerColorForFace(face);

            for (int y = minY; y <= maxY; y += opt.sampleStep) {
                for (int z = minZ; z <= maxZ; z += opt.sampleStep) {
                    for (int x = minX; x <= maxX; x += opt.sampleStep) {
                        int idx = (y * length + z) * width + x;
                        if (!surface[idx]) {
                            surface[idx] = true;
                            voxels[idx] = colorIdx;
                        }
                    }
                }
            }
        }

        if (cb != null) cb.update(0.55f, "体素化完成");
        //       把表面体素向外膨胀 1 层, 把"几乎被包住"的体素也认作表面
        if (opt.strengthenSurface) {
            int dilated = 0;
            boolean[] surfaceDilation = new boolean[voxels.length];
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < length; z++) {
                    for (int x = 0; x < width; x++) {
                        int idx = (y * length + z) * width + x;
                        if (voxels[idx] >= 0) continue; // 已有表面, 跳过
                        // 6 邻居中只要有 >= 4 个是表面, 就把当前体素也升级为表面
                        int neighborSurface = 0;
                        if (x > 0        && surface[(y * length + z) * width + (x - 1)]) neighborSurface++;
                        if (x < width-1  && surface[(y * length + z) * width + (x + 1)]) neighborSurface++;
                        if (y > 0        && surface[((y - 1) * length + z) * width + x]) neighborSurface++;
                        if (y < height-1 && surface[((y + 1) * length + z) * width + x]) neighborSurface++;
                        if (z > 0        && surface[(y * length + (z - 1)) * width + x]) neighborSurface++;
                        if (z < length-1 && surface[(y * length + (z + 1)) * width + x]) neighborSurface++;
                        if (neighborSurface >= 4) {
                            surfaceDilation[idx] = true;
                            // 用邻居中最常见的颜色 (简化: 用 +X 邻居的颜色)
                            int sampleIdx = -1;
                            if (x < width-1)  sampleIdx = (y * length + z) * width + (x + 1);
                            else if (z < length-1) sampleIdx = (y * length + (z + 1)) * width + x;
                            else if (y < height-1) sampleIdx = ((y + 1) * length + z) * width + x;
                            if (sampleIdx >= 0 && voxels[sampleIdx] >= 0) {
                                voxels[idx] = voxels[sampleIdx];
                            } else {
                                voxels[idx] = registerColor(new float[]{0.5f, 0.5f, 0.5f});
                            }
                            dilated++;
                        }
                    }
                }
            }
            if (dilated > 0) {
                PrefabCustomAddon.LOGGER.info("表面强化: 膨胀 {} 个体素 (填洞)", dilated);
            }
        }

        // 2) 实心填充: 用 flood fill from outside 标记所有"外部空 voxel",
        //    剩余的"空 voxel"= 内部 voxel, 一律填实心.
        //    之前用光线投射 O(N * F) 对 1.5M 面 128K voxel 是 1920 亿次, 必卡死.
        //    改成 O(N) BFS: 从角落 (0,0,0) 开始 6 邻居搜索, 标外部.
        if (opt.fillInterior) {
            if (cb != null) cb.update(0.62f, "内部填充中 (flood fill)...");
            int filled = 0;
            // outside[idx] = true 表示该空 voxel 在模型外部 (从角落可达)
            boolean[] outside = new boolean[voxels.length];
            int[] queue = new int[voxels.length];
            int head = 0, tail = 0;

            // 起点: 4 个角落 + 边界外推 (确保是模型外的空 voxel)
            int[] seeds = new int[]{
                0,                                  // (0,0,0)
                (0 * length + (length - 1)) * width + 0,        // (0,0,W-1)
                ((height - 1) * length + 0) * width + 0,        // (0,H-1,0)
                ((height - 1) * length + (length - 1)) * width + (width - 1)
            };
            for (int s : seeds) {
                if (s < 0 || s >= voxels.length) continue;
                if (voxels[s] >= 0) continue;     // 表面不作为起点
                if (outside[s]) continue;
                outside[s] = true;
                queue[tail++] = s;
            }
            // 兜底: 如果 4 个角都是表面, 用 6 个面中心代替
            if (tail == 0) {
                int[] centers = new int[]{
                    (0 * length + 0) * width + 0,                        // (0,0,0)
                    ((height-1) * length + 0) * width + 0,                // 底面
                    (0 * length + 0) * width + (width-1),                 // 底面 X+
                    (0 * length + (length-1)) * width + 0,                // 底面 Z+
                    ((height-1) * length + 0) * width + (width-1),
                    ((height-1) * length + (length-1)) * width + 0
                };
                for (int s : centers) {
                    if (s < 0 || s >= voxels.length) continue;
                    if (voxels[s] >= 0) continue;
                    if (outside[s]) continue;
                    outside[s] = true;
                    queue[tail++] = s;
                }
            }

            // BFS 6 邻居: 标记所有从外部可达的空 voxel
            int grayInternal = registerColor(new float[]{0.5f, 0.5f, 0.5f});
            while (head < tail) {
                int cur = queue[head++];
                int cy = cur / (width * length);
                int cz = (cur / width) % length;
                int cx = cur % width;
                int[] nbrs = new int[]{
                    cx - 1, cy, cz,
                    cx + 1, cy, cz,
                    cx, cy - 1, cz,
                    cx, cy + 1, cz,
                    cx, cy, cz - 1,
                    cx, cy, cz + 1
                };
                for (int k = 0; k < 6; k++) {
                    int nx = nbrs[k * 3], ny = nbrs[k * 3 + 1], nz = nbrs[k * 3 + 2];
                    if (nx < 0 || nx >= width || ny < 0 || ny >= height || nz < 0 || nz >= length) continue;
                    int nidx = (ny * length + nz) * width + nx;
                    if (voxels[nidx] >= 0) continue;   // 跳过表面
                    if (outside[nidx]) continue;
                    outside[nidx] = true;
                    queue[tail++] = nidx;
                }
            }

            // 剩余空 voxel = 内部, 填实心
            for (int i = 0; i < voxels.length; i++) {
                if (voxels[i] < 0 && !outside[i]) {
                    voxels[i] = grayInternal;
                    filled++;
                }
            }
            PrefabCustomAddon.LOGGER.info("内部填充 (flood fill): {} 个体素, BFS 队列峰值={}",
                filled, tail);
            if (cb != null) cb.update(0.88f, "内部填充完成 (" + filled + " 体素)");
        }

        // 3) 构建 Sponge Schematic v2 NBT
        if (cb != null) cb.update(0.90f, "构建 NBT...");
        byte[] data = buildSchematicNbt(voxels, width, height, length, warnings);

        // 统计非空块
        int count = 0;
        for (int v : voxels) if (v >= 0) count++;

        if (cb != null) cb.update(1.0f, "完成 (" + count + " 块)");

        long elapsed = System.currentTimeMillis() - t0;
        return new Result(data, width, height, length, count, warnings, elapsed);
    }

    // ------------ 几何工具 ------------

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static int countAllFaces(ObjModel m) {
        int c = 0;
        for (ObjModel.Group g : m.groups) c += g.faces.size();
        return c;
    }

    /** Möller-Trumbore 射线-三角形相交测试. */
    private static boolean rayTriangleHit(float ox, float oy, float oz,
                                          float dx, float dy, float dz,
                                          float[] v0, float[] v1, float[] v2) {
        float EPS = 1e-6f;
        float[] edge1 = sub(v1, v0);
        float[] edge2 = sub(v2, v0);
        float[] h = cross(dx, dy, dz, edge2[0], edge2[1], edge2[2]);
        float a = dot(edge1[0], edge1[1], edge1[2], h);
        if (a > -EPS && a < EPS) return false;
        float f = 1.0f / a;
        float[] s = sub(new float[]{ox, oy, oz}, v0);
        float u = f * dot(s[0], s[1], s[2], h);
        if (u < 0.0f || u > 1.0f) return false;
        float[] q = cross(s[0], s[1], s[2], edge1[0], edge1[1], edge1[2]);
        float v = f * dot(dx, dy, dz, q);
        if (v < 0.0f || u + v > 1.0f) return false;
        float t = f * dot(edge2[0], edge2[1], edge2[2], q);
        return t > EPS; // 仅前方相交
    }

    private static float[] sub(float[] a, float[] b) { return new float[]{a[0]-b[0], a[1]-b[1], a[2]-b[2]}; }
    private static float dot(float ax, float ay, float az, float[] b) { return ax*b[0]+ay*b[1]+az*b[2]; }
    private static float[] cross(float ax, float ay, float az, float bx, float by, float bz) {
        return new float[]{ ay*bz - az*by, az*bx - ax*bz, ax*by - ay*bx };
    }

    // ------------ 调色板 ------------

    private static final List<float[]> COLORS = new ArrayList<>();
    private static final Map<String, Integer> COLOR_INDEX = new HashMap<>();

    private static synchronized int registerColor(float[] rgb) {
        // 量化为 5-bit/通道, 减少重复
        int key = ((int)(rgb[0] * 31) << 10) | ((int)(rgb[1] * 31) << 5) | (int)(rgb[2] * 31);
        String s = Integer.toString(key);
        Integer existing = COLOR_INDEX.get(s);
        if (existing != null) return existing;
        int idx = COLORS.size();
        COLORS.add(new float[]{ rgb[0], rgb[1], rgb[2] });
        COLOR_INDEX.put(s, idx);
        return idx;
    }

    /**
     * 用 face 的 groupName / materialName 关键字推断 fallback 颜色.
     * <p>很多 OBJ 文件 (尤其是 Sketchfab 下载的猫/人物模型) 既没顶点颜色也没 .mtl 文件,
     * 全部走 0.5/0.5/0.5 兜底 → 全是浅灰 → 用户反馈"颜色不对". 这里从名字关键词推断:
     * "Cat" / "Body" / "Fur" / "Tabby" → 橙色 (默认猫身颜色),
     * "Eye" / "Green" → 绿色 (猫眼睛), "Nose" / "Tongue" → 粉色,
     * "White" / "Belly" → 白色, "Black" / "Stripe" → 黑色 等等.
     * 不命中时返回 null → 继续用 0.5 兜底.</p>
     * <p>大小写不敏感, 模糊匹配 (比如 "Cat_Body" / "cat-body" / "CatBody" 都能匹配 "cat").</p>
     */
    private static float[] guessColorFromName(String groupName, String materialName) {
        // 合并两个名字, 转小写, 拆词
        String combined = ((groupName == null ? "" : groupName) + " " +
                           (materialName == null ? "" : materialName)).toLowerCase(java.util.Locale.ROOT);
        if (combined.isBlank()) return null;
        // 优先匹配更具体的关键词 (eye / nose 比 color 优先)
        if (matchesAny(combined, "eye", "eyes", "pupil")) return new float[]{ 0.20f, 0.65f, 0.30f }; // 绿色眼睛
        if (matchesAny(combined, "nose", "tongue", "mouth", "lip", "lips", "inner"))
            return new float[]{ 0.95f, 0.65f, 0.70f }; // 粉色
        if (matchesAny(combined, "claw", "paw", "foot", "hand", "palm"))
            return new float[]{ 0.30f, 0.20f, 0.15f }; // 深棕爪
        if (matchesAny(combined, "hair", "mane", "beard", "mustache"))
            return new float[]{ 0.45f, 0.30f, 0.20f }; // 棕发
        // 颜色关键词
        if (matchesAny(combined, "white", "belly", "chest", "puff", "underside"))
            return new float[]{ 0.95f, 0.95f, 0.92f };
        if (matchesAny(combined, "black", "dark", "shadow", "outline", "stripe", "stripes"))
            return new float[]{ 0.10f, 0.10f, 0.12f };
        if (matchesAny(combined, "blue")) return new float[]{ 0.30f, 0.50f, 0.85f };
        if (matchesAny(combined, "red")) return new float[]{ 0.75f, 0.20f, 0.20f };
        if (matchesAny(combined, "yellow")) return new float[]{ 0.95f, 0.85f, 0.20f };
        if (matchesAny(combined, "purple", "violet")) return new float[]{ 0.55f, 0.30f, 0.70f };
        if (matchesAny(combined, "pink")) return new float[]{ 0.95f, 0.65f, 0.75f };
        if (matchesAny(combined, "brown", "tan", "khaki", "wood")) return new float[]{ 0.55f, 0.35f, 0.20f };
        if (matchesAny(combined, "gray", "grey", "stone", "silver")) return new float[]{ 0.55f, 0.55f, 0.55f };
        // 模糊匹配: cat / body / fur / tabby / skin / main → 默认橙色 (猫身)
        if (matchesAny(combined, "cat", "kitten", "feline", "tabby", "calico", "tiger", "lion",
                              "body", "torso", "head", "skin", "main", "fur", "tail",
                              "default", "mesh", "group1", "sphere", "cube", "mat_0", "mat1"))
            return new float[]{ 0.85f, 0.55f, 0.25f }; // 橙黄 (猫身)
        return null;
    }

    private static boolean matchesAny(String s, String... keywords) {
        for (String k : keywords) {
            // 用 s.indexOf(k) > -1 简单包含匹配; 不需要 word boundary — 比如 "eyelash" 包含 "eye"
            // 仍然命中, 这是有意的 (eyelash 通常是深色, 但保险起见让它走 eye 分支 → 绿色)
            if (s.indexOf(k) >= 0) return true;
        }
        return false;
    }

    /**
     * 注册一个带 "name 标签" 的颜色. 用于体素化时, face 的颜色是默认 0.5 兜底时,
     * 用 groupName / materialName 关键字推断一个更合理的颜色.
     * 返回的 colorIdx 是 COLORS 列表里的索引, 后续 mapColorToBlock 用它算方块.
     */
    private static int registerColorForFace(ObjModel.Face face) {
        float[] color = face.color;
        boolean isDefaultGray = (color != null
                && Math.abs(color[0] - 0.5f) < 0.001f
                && Math.abs(color[1] - 0.5f) < 0.001f
                && Math.abs(color[2] - 0.5f) < 0.001f);
        if (isDefaultGray) {
            float[] guessed = guessColorFromName(face.groupName, face.materialName);
            if (guessed != null) {
                color = guessed;
            }
        }
        return registerColor(color);
    }

    private static void resetPalette() { COLORS.clear(); COLOR_INDEX.clear(); }

    /**
     * RGB → 最近方块 ID.
     *
     * <h3>调色板策略 (从饱和到不饱和, 4 层):</h3>
     * <ol>
     *   <li><b>染色混凝土 (stained_concrete)</b>: 颜色最饱和, 表面光滑. 鲜亮物体 (猫的橙黄) 优先用它.</li>
     *   <li><b>染色陶瓦 (stained_terracotta)</b>: 颜色稍暗, 适合中间色.</li>
     *   <li><b>染色羊毛 (wool)</b>: 颜色鲜艳, 适合大色块.</li>
     *   <li><b>染色玻璃 (stained_glass)</b>: 半透明, 适合高亮色.</li>
     * </ol>
     * 选择规则: v (明度) 决定选哪一层 — 高亮 → 玻璃 / 鲜亮 → 混凝土 / 中间 → 陶瓦 / 中暗 → 羊毛.
     *
     * <h3>为什么用 v 而不是固定的 wool:</h3>
     * 之前默认全用 wool, 浅黄色 (v=0.9) 和橙色 (v=0.6) 都映射到同一个 yellow_wool, 失真.
     * 现在 v 划分后, 浅黄 → 玻璃, 中黄 → 混凝土, 暗黄 → 羊毛, 层次更明显.
     */
    private static String mapColorToBlock(float r, float g, float b) {
        // 1) 转 HSV
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float diff = max - min;
        float v = max;                       // value/brightness
        float s = (max == 0) ? 0 : diff / max; // saturation
        float h;
        if (diff < 0.0001f) {
            h = 0;
        } else if (max == r) {
            h = ((g - b) / diff) % 6f;
        } else if (max == g) {
            h = (b - r) / diff + 2f;
        } else {
            h = (r - g) / diff + 4f;
        }
        h = h * 60f; // 转角度, [0, 360)
        if (h < 0) h += 360f;

        // 2) 灰度 (饱和度低) → 黑白灰系列
        //    灰度也要按 v 分层, 不要全用同一个 wool. 浅灰 → 白色, 中灰 → 浅灰, 暗灰 → 灰, 黑 → 黑.
        if (s < 0.20f) {
            if (v < 0.15f) return "minecraft:black_concrete";
            if (v < 0.32f) return "minecraft:gray_concrete";
            if (v < 0.55f) return "minecraft:light_gray_concrete";
            if (v < 0.78f) return "minecraft:white_concrete";
            return "minecraft:white_concrete";
        }

        // 3) 彩色 → 按色相分类, 同时按 v 选择调色板层
        //    色调名: dye color (white/orange/magenta/light_blue/yellow/lime/pink/gray/light_gray/cyan/purple/blue/brown/green/red/black)
        String colorName;
        if (h < 15f || h >= 345f) {
            colorName = "red";
        } else if (h < 40f) {
            colorName = "orange";  // 猫的颜色
        } else if (h < 65f) {
            colorName = "yellow";
        } else if (h < 90f) {
            colorName = "lime";
        } else if (h < 155f) {
            colorName = "green";
        } else if (h < 200f) {
            colorName = "cyan";
        } else if (h < 250f) {
            colorName = "light_blue";
        } else if (h < 290f) {
            colorName = "purple";
        } else if (h < 330f) {
            colorName = "magenta";
        } else {
            colorName = "pink";
        }

        // 4) 按 v (明度) 选方块类型
        //    v >= 0.85 → 玻璃 (高亮, 半透明, 适合发光/高光部分)
        //    v >= 0.65 → 染色混凝土 (颜色饱和, 适合大色块)
        //    v >= 0.45 → 染色陶瓦 (颜色稍暗, 适合中间色)
        //    v <  0.45 → 染色羊毛 (不透明, 适合深色)
        if (v >= 0.85f) {
            return "minecraft:" + colorName + "_stained_glass";
        } else if (v >= 0.65f) {
            return "minecraft:" + colorName + "_concrete";
        } else if (v >= 0.45f) {
            return "minecraft:" + colorName + "_terracotta";
        } else {
            return "minecraft:" + colorName + "_wool";
        }
    }

    // ------------ Vanilla NBT 构造 (1.21.1+) ------------

    private static byte[] buildSchematicNbt(int[] voxels, int w, int h, int l, List<String> warnings) {
        // 注意: 不要在这里 resetPalette() — COLORS 已在 convertToSchematic 开头清空,
        // 体素扫描时通过 registerColor(...) 填充, 这里直接读 COLORS 即可.
        // 1) 收集实际用到的颜色 → 调色板 (colorIdx -> blockId, 去重)
        Map<Integer, String> colorToBlock = new HashMap<>();
        int fallbackColorCount = 0;
        for (int v : voxels) {
            if (v < 0) continue;
            if (colorToBlock.containsKey(v)) continue;
            float[] rgb = COLORS.get(v);
            if (rgb == null || rgb.length < 3) {
                // 防御: 理论上 COLORS 索引必然与 registerColor 返回的 idx 对应,
                // 但万一空了就跳过, 避免后续流程崩溃
                PrefabCustomAddon.LOGGER.warn("[OBJ-CONVERT] COLORS 缺失索引 {}, 跳过", v);
                continue;
            }
            String blockId = mapColorToBlock(rgb[0], rgb[1], rgb[2]);
            colorToBlock.put(v, blockId);
            // 统计 fallback 颜色 (灰 0.5/0.5/0.5 → light_gray_concrete) 数量
            if (rgb[0] == 0.5f && rgb[1] == 0.5f && rgb[2] == 0.5f) {
                fallbackColorCount++;
            }
        }
        if (fallbackColorCount > 0 && fallbackColorCount == colorToBlock.size()) {
            PrefabCustomAddon.LOGGER.warn(
                "[OBJ-CONVERT] 调色板中 {} / {} 个颜色是 fallback 灰 0.5/0.5/0.5 → 说明 OBJ 文件没颜色信息",
                fallbackColorCount, colorToBlock.size());
            PrefabCustomAddon.LOGGER.warn(
                "[OBJ-CONVERT]   解决: 重新导出 OBJ 时勾选 Blender 的 'Vertex Colors' / 加 .mtl 文件 + Kd 颜色");
        } else {
            int textureColors = 0;  // 来自贴图采样的非 fallback 颜色数
            for (int v : voxels) {
                if (v < 0) continue;
                float[] rgb = COLORS.get(v);
                if (rgb == null) continue;
                if (!(rgb[0] == 0.5f && rgb[1] == 0.5f && rgb[2] == 0.5f)) {
                    textureColors++;
                }
            }
            if (textureColors > 0) {
                PrefabCustomAddon.LOGGER.info(
                    "[OBJ-CONVERT] 调色板中 {} 个非 fallback 颜色, OBJ 颜色信息已成功解析 (含贴图采样)",
                    colorToBlock.size());
            } else {
                PrefabCustomAddon.LOGGER.info(
                    "[OBJ-CONVERT] 调色板中 {} 个颜色 (全部 fallback 灰)", colorToBlock.size());
            }
        }

        // 2) 收集用到的方块 ID, 建立 vanilla palette
        // 顺序: minecraft:air 在索引 0, 其余方块按出现顺序
        List<String> paletteList = new ArrayList<>();
        paletteList.add("minecraft:air");
        Map<String, Integer> blockToPaletteIdx = new LinkedHashMap<>();
        blockToPaletteIdx.put("minecraft:air", 0);
        for (String blockId : colorToBlock.values()) {
            if (blockToPaletteIdx.containsKey(blockId)) continue;
            blockToPaletteIdx.put(blockId, paletteList.size());
            paletteList.add(blockId);
        }

        // 3) 构建 vanilla palette ListTag
        ListTag vanillaPalette = new ListTag();
        for (String blockId : paletteList) {
            CompoundTag entry = new CompoundTag();
            int propStart = blockId.indexOf('[');
            String name;
            CompoundTag properties = new CompoundTag();
            if (propStart >= 0 && blockId.endsWith("]")) {
                name = blockId.substring(0, propStart);
                String propStr = blockId.substring(propStart + 1, blockId.length() - 1);
                for (String prop : propStr.split(",")) {
                    String[] kv = prop.split("=", 2);
                    if (kv.length == 2) {
                        properties.putString(kv[0].trim(), kv[1].trim());
                    }
                }
            } else {
                name = blockId;
            }
            entry.putString("Name", name);
            if (!properties.isEmpty()) {
                entry.put("Properties", properties);
            }
            vanillaPalette.add(entry);
        }

        // 4) 构建 vanilla blocks ListTag
        // 顺序: index = y * Width * Length + z * Width + x (Y-major, Z-then-X)
        ListTag vanillaBlocks = new ListTag();
        int airPaletteIdx = blockToPaletteIdx.get("minecraft:air");
        int filled = 0;
        int skipped = 0;
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < l; z++) {
                for (int x = 0; x < w; x++) {
                    int srcIdx = (y * l + z) * w + x;
                    if (voxels[srcIdx] < 0) continue;
                    String blockId = colorToBlock.get(voxels[srcIdx]);
                    if (blockId == null) { skipped++; continue; }
                    Integer palIdx = blockToPaletteIdx.get(blockId);
                    if (palIdx == null) { skipped++; continue; }
                    if (palIdx == airPaletteIdx) { skipped++; continue; } // 不写空气

                    CompoundTag block = new CompoundTag();
                    ListTag posList = new ListTag();
                    posList.add(IntTag.valueOf(x));
                    posList.add(IntTag.valueOf(y));
                    posList.add(IntTag.valueOf(z));
                    block.put("pos", posList);

                    // 1.21.1+ 格式: state 是 CompoundTag {Name, Properties?}
                    CompoundTag stateTag = new CompoundTag();
                    int propStart = blockId.indexOf('[');
                    String name;
                    CompoundTag properties = new CompoundTag();
                    if (propStart >= 0 && blockId.endsWith("]")) {
                        name = blockId.substring(0, propStart);
                        String propStr = blockId.substring(propStart + 1, blockId.length() - 1);
                        for (String prop : propStr.split(",")) {
                            String[] kv = prop.split("=", 2);
                            if (kv.length == 2) {
                                properties.putString(kv[0].trim(), kv[1].trim());
                            }
                        }
                    } else {
                        name = blockId;
                    }
                    stateTag.putString("Name", name);
                    if (!properties.isEmpty()) {
                        stateTag.put("Properties", properties);
                    }
                    block.put("state", stateTag);

                    vanillaBlocks.add(block);
                    filled++;
                }
            }
        }

        // 5) 验证: 确保使用的所有方块都已在注册表
        for (String blockId : blockToPaletteIdx.keySet()) {
            if ("minecraft:air".equals(blockId)) continue;
            if (lookupBlockStateId(blockId) == 0) {
                warnings.add("未知方块: " + blockId + " (将回退为石头)");
            }
        }
        if (skipped > 0) {
            PrefabCustomAddon.LOGGER.warn("[OBJ-CONVERT] 跳过 {} 个空气/无色体素", skipped);
        }

        // 6) 组装 vanilla structure NBT
        CompoundTag root = new CompoundTag();
        // size: ListTag<3 IntTag> [w, h, l] (1.21.1+ 格式)
        ListTag sizeList = new ListTag();
        sizeList.add(IntTag.valueOf(w));
        sizeList.add(IntTag.valueOf(h));
        sizeList.add(IntTag.valueOf(l));
        root.put("size", sizeList);
        // 同时也写 sizeIntArray (部分解析器优先用这个)
        root.put("sizeIntArray", new IntArrayTag(new int[]{w, h, l}));
        root.put("palette", vanillaPalette);
        root.put("blocks", vanillaBlocks);
        root.putInt("DataVersion", 3953);
        // 标记来源 (给调试用)
        root.putString("_obj_source", "ObjToSchematicConverter");

        // 7) 写入 GZIP 压缩 NBT
        try {
            // NbtIo.write 需要 DataOutput, 所以先写未压缩到内存, 再用 GZIP 包一层
            ByteArrayOutputStream rawBaos = new ByteArrayOutputStream();
            try (DataOutputStream dout = new DataOutputStream(rawBaos)) {
                NbtIo.write(root, dout);
            }
            byte[] rawNbt = rawBaos.toByteArray();

            ByteArrayOutputStream gzBaos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(gzBaos)) {
                gz.write(rawNbt);
            }
            PrefabCustomAddon.LOGGER.info("Vanilla NBT 写入完成: {}x{}x{}, 填充 {} 块, {} 调色板",
                    w, h, l, filled, paletteList.size());
            return gzBaos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("无法序列化 Vanilla NBT", e);
        }
    }

    private static int lookupBlockStateId(String blockId) {
        try {
            ResourceLocation rl = ResourceLocation.parse(blockId);
            Block block = BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
            if (block == null || block == Blocks.AIR) return 0;
            return BuiltInRegistries.BLOCK.getId(block);
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------ 便捷方法 ------------

    /** 把 .schem bytes 写到一个 Path. */
    public static Path writeSchematicFile(Path outputDir, String name, byte[] data) throws IOException {
        Files.createDirectories(outputDir);
        Path out = outputDir.resolve(name + ".schem");
        Files.write(out, data);
        return out;
    }
}
