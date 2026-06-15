# Prefab Custom Addon

Prefab 自带的建筑就那么几个，玩久了腻。

这个附属加了一套拓展包系统：游戏里按 Z 能下别人做好的包，按 X 能自己做。

下载器：浏览社区拓展包列表，选中直接装到 prefab-extension，进了世界就能用。

制作器：本地工作区模式，填表 + 选 NBT + 选封面，几次点击产出一个标准格式的拓展包（含 zip）。所有创作产物都在游戏根目录的 prefab-work/ 下，NBT 用游戏自带的结构方块导出就行。

前置：Prefab 1.0.7+、LDLib2 2.2+。运行环境：NeoForge 21.1.233、MC 1.21.1、Java 21+。

## 键位

| 按键 | 作用 |
|---|---|
| Z | 打开拓展包下载器 |
| X | 打开拓展包制作器 |

## 目录结构

工作区目录和发布的拓展包目录结构完全一致：

```
prefab-work/
├── <pack_id>/
│   ├── information/
│   │   ├── <pack_id>.txt
│   │   └── cover.png
│   └── construction/
│       ├── <building_id>.nbt
│       ├── <building_id>.png
│       └── <building_id>.txt
└── <pack_id>.zip          ← 自动打包
```

`prefab-work/<id>/` 的任何改动都会自动重新打包到 `prefab-work/<id>.zip`。把 zip 解压放到 `prefab-extension/<id>/` 即可使用。

## 构建

需要 Java 21+。

1. 下载 Prefab 主模组的 jar（[CurseForge](https://www.curseforge.com/minecraft/mc-mods/prefab) / [Modrinth](https://modrinth.com/mod/prefab)），放到 `libs/prefab-neoforge-1.0.8.jar`
2. 下载 LDLib2 的 jar（[Modrinth](https://modrinth.com/mod/ldlib2)），放到 `libs/ldlib2-2.2.18.jar`
3. 跑 `gradlew build`，产物在 `build/libs/`

构建时如果 `compileOnly` 找不到 Prefab 类，参考上面的两个文件路径。

## 依赖

| 模组 | 版本 | 强制 | 用途 |
|---|---|---|---|
| Prefab | 1.0.7+ | 是 | 核心，提供 GuiStructure、BuildBlock 等类 |
| LDLib2 | 2.2+ | 是 | UI 工具库 |

NeoForge 21.1.233、MC 1.21.1。

## 许可证

MIT。详见 [LICENSE](LICENSE)。

## 相关链接

- [Prefab 主模组](https://www.curseforge.com/minecraft/mc-mods/prefab)
- [NeoForge](https://neoforged.net/)
- [LDLib2](https://modrinth.com/mod/ldlib2)
