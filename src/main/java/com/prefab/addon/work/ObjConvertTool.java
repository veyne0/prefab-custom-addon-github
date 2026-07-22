package com.prefab.addon.work;

import com.prefab.addon.extension.ObjToSchematicConverter;
import com.prefab.addon.extension.SpongeSchematicParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 独立测试工具: 命令行把一个 OBJ 转换为 .schem.
 *
 * <p>使用: java ... com.prefab.addon.work.ObjConvertTool &lt;input.obj&gt; [output.schem]
 */
public class ObjConvertTool {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法: ObjConvertTool <input.obj> [output.schem]");
            System.exit(1);
        }
        Path input = Paths.get(args[0]);
        if (!Files.exists(input)) {
            System.err.println("找不到文件: " + input);
            System.exit(2);
        }
        String name = input.getFileName().toString();
        if (name.toLowerCase().endsWith(".obj")) name = name.substring(0, name.length() - 4);
        Path output = args.length > 1
                ? Paths.get(args[1])
                : input.resolveSibling(name + ".schem");

        ObjToSchematicConverter.Options opt = new ObjToSchematicConverter.Options();
        opt.voxelsPerMeter = 8; // 1 voxel = 12.5cm
        opt.fillInterior = true;
        opt.sampleStep = 1;

        System.out.println("开始转换: " + input);
        System.out.println("  缩放: " + opt.voxelsPerMeter + " voxels/m");
        System.out.println("  实心: " + opt.fillInterior);

        ObjToSchematicConverter.Result r = ObjToSchematicConverter.convertToSchematic(input, opt);
        Files.write(output, r.schematicBytes);

        System.out.println("\n=== 转换完成 ===");
        System.out.println("输出文件: " + output);
        System.out.println("尺寸: " + r.width + " x " + r.height + " x " + r.length);
        System.out.println("填充方块: " + r.blockCount);
        System.out.println("耗时: " + r.elapsedMs + " ms");
        if (!r.warnings.isEmpty()) {
            System.out.println("\n警告 (" + r.warnings.size() + "):");
            for (String w : r.warnings) System.out.println("  - " + w);
        }

        // 验证: 用 SpongeSchematicParser 再读一遍
        System.out.println("\n=== 回读验证 ===");
        var nbt = SpongeSchematicParser.readRoot(output);
        System.out.println("NBT 根标签: " + nbt.getString("Materials"));
        System.out.println("  Width=" + nbt.getShort("Width")
                + " Height=" + nbt.getShort("Height")
                + " Length=" + nbt.getShort("Length"));
    }
}
