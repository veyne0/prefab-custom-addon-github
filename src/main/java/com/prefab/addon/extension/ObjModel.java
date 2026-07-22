package com.prefab.addon.extension;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 简易 OBJ 模型数据 - 顶点 / 面 / 材质分组.
 */
public class ObjModel {
    public static class Face {
        public final int[] vertexIndices; // OBJ 顶点索引 (1-based, 转为 0-based)
        public final int[] normalIndices; // 法线索引
        public final int[] texCoordIndices; // UV 索引 (用于从贴图采样颜色)
        public final String materialName;  // usemtl 指定的材质
        public final String groupName;     // 所属的 group / object 名 (g / o)
        public final float[] color;        // 当前面颜色 (RGB 0-1), 解析时填充

        public Face(int[] vertexIndices, int[] normalIndices, int[] texCoordIndices,
                    String materialName, String groupName, float[] color) {
            this.vertexIndices = vertexIndices;
            this.normalIndices = normalIndices;
            this.texCoordIndices = texCoordIndices;
            this.materialName = materialName;
            this.groupName = groupName != null ? groupName : "";
            this.color = color != null ? color : new float[]{0.5f, 0.5f, 0.5f};
        }
    }

    public static class Group {
        public final String name;
        public final List<Face> faces = new ArrayList<>();
        public Group(String name) { this.name = name; }
    }

    public final List<float[]> vertices = new ArrayList<>();   // x,y,z
    public final List<float[]> normals  = new ArrayList<>();   // nx,ny,nz
    public final List<float[]> texCoords = new ArrayList<>();  // u,v
    public final List<float[]> vertexColors = new ArrayList<>(); // 与 vertices 索引对齐, 可能为 null
    public final List<Group> groups = new ArrayList<>();

    // 材质 -> RGB 颜色 (解析 mtllib 后填充, 来自 Kd 或 map_Kd 贴图采样)
    public final Map<String, float[]> materialColors = new HashMap<>();

    // 材质 -> 加载过的贴图 (map_Kd 路径解析后), 用于 face 颜色采样.
    // 关键: 混元 AI 生成的 OBJ 把颜色放在 PNG 贴图里, Kd 是灰色 0.5/0.5/0.5 占位.
    // 必须加载贴图, 用 face 的 UV 中心点对应像素颜色, 才能还原猫的橙黄/白/黑等.
    public final Map<String, BufferedImage> materialTextures = new HashMap<>();
    // 贴图路径 (供 ObjToSchematicConverter 报日志用)
    public final Map<String, String> materialTexturePaths = new HashMap();

    public float minX, minY, minZ, maxX, maxY, maxZ;
    public boolean boundsComputed = false;
}
