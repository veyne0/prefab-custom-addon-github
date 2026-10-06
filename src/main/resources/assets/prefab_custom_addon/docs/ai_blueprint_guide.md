# Prefab Custom Addon — AI 蓝图创建指南

> **这份文档是写给 AI Agent 的操作手册。** 玩家把本文档直接发给 AI（或放进 AI 的项目上下文），
> AI 按照本文的规则和模板，就能替玩家生成一个可用的自定义蓝图，无需玩家手写任何代码。
>
> 适用版本：Minecraft 1.21.1 · NeoForge · Prefab Custom Addon 2.4.x · KubeJS（1.21.1 / 1907.20+ API）

---

## 0. 系统总览（AI 必须先理解的结构）

一个"自定义蓝图" = **1 个建筑文件 + 3 个 KubeJS 文件**：

| 文件 | 位置 | 作用 | 生效方式 |
|---|---|---|---|
| 建筑文件 | `.minecraft/prefab-extension/<id>.nbt`（也支持 `.schem` / `.litematic` / `.schematic`） | 蓝图指向的实际建筑 | 重启游戏或重进存档后自动扫描 |
| 启动脚本 | `.minecraft/kubejs/startup_scripts/<ns>_<itemId>_blueprint.js` | 注册蓝图物品 + 写入绑定 NBT + 加创造栏 | **仅启动时执行，必须重启游戏**（`/reload` 无效） |
| 配方脚本 | `.minecraft/kubejs/server_scripts/<ns>_<itemId>_blueprint_recipes.js` | 蓝图物品的合成配方 | `/reload` 即可 |
| 物品贴图 | `.minecraft/kubejs/assets/<ns>/textures/item/<itemId>.png` | 物品图标 | 重启游戏 |

**绑定原理**（AI 生成脚本时不要改动这些约定值）：
- 物品打了 tag `prefab_custom_addon:player_blueprint` 后，mod 会在客户端拦截它的右键事件；
- 物品的 `CUSTOM_DATA` NBT 里存 `packName` + `constructionId` + `locked` 三个字段；
- 右键时 mod 按 `packName + constructionId` 找到建筑，打开 3D 预览/建造界面。

---

## 1. 前置条件

- 已安装：Prefab（主 mod）、Prefab Custom Addon、KubeJS；
- `.minecraft` 指含 `mods/`、`config/`、`kubejs/` 的游戏目录（多实例启动器注意选对实例）；
- 玩家需要提供：一个建筑文件（nbt/schem/litematic/schematic），或告诉他已存在建筑的 id。

---

## 2. 第一步：放置建筑文件

把建筑文件复制到 `.minecraft/prefab-extension/`，文件名（去掉扩展名）就是 `constructionId`。

```
.minecraft/prefab-extension/
├── my_house.nbt        ← 建筑本体（必需）→ constructionId = "my_house"
├── my_house.txt        ← 元信息（可选）
└── my_house.png        ← 预览图（可选）
```

**规则：**
- `constructionId` = 建筑文件名去掉扩展名，脚本里的 NBT 必须与它**逐字符一致**；
- 支持格式：`.nbt`（结构方块）、`.schem`（WorldEdit）、`.litematic`（Litematica）、`.schematic`（旧版）；
- 文件名允许中文，但**建议 AI 统一用小写英文+数字+下划线**，避免路径问题；
- 同名建筑在 `prefab-extension/` 优先于 `prefab-download/`。

**可选元信息文件 `<id>.txt`**（UTF-8 编码，`key: value` 每行一条）：

```
建筑名: 我的小屋
作者: Steve
描述: 一个 9x9 的橡木小屋
分类: 住宅
依赖: create, supplementaries
```

（key 支持中英文别名：`name/建筑名`、`author/作者`、`description/描述/说明`、`category/分类`、`dependencies/依赖`，依赖项用逗号/分号/空格分隔。）

---

## 3. 第二步：确定命名（严格校验，AI 不要自由发挥）

| 名称 | 规则 | 示例 |
|---|---|---|
| 命名空间（最终） | 只能 `[a-z0-9_]`，**必须以 `player_` 开头**，禁止 `prefab` / `minecraft` | `player_mypack` |
| 物品 ID（只需要问玩家显示名，显示名问玩家是否直接就用建筑文件的文件名或玩家自己取，物品ID根据玩家给的显示名决定） `itemId` | 只能 `[a-z0-9_]` | `my_house_blue` |
| `constructionId` | 建筑文件名去扩展名（见第 2 步） | `my_house` |
| `packName` | 建筑文件位置标签（见下表） | `extension` |

**`packName` 取值：**

| 建筑文件位置 | packName |
|---|---|
| `.minecraft/prefab-extension/` | `extension` |
| `.minecraft/prefab-download/` | `download` |
| zip 拓展包内 | 拓展包的 packageName |

最终物品注册 ID = `<命名空间>:<itemId>`，例如 `player_mypack:my_house_blue`。
**注意：** 玩家在游戏内生成器填命名空间时会自动补 `player_` 前缀；AI 直接写脚本时请**自己就写全** `player_xxx`，不要写成 `mypack`。

---

## 4. 第三步：生成启动脚本

写入 `.minecraft/kubejs/startup_scripts/<ns>_<itemId>_blueprint.js`。
**模板照抄，只替换尖括号内容，不要增删事件、不要换 API 名：**

```js
// kubejs/startup_scripts/player_mypack_my_house_blue_blueprint.js
// 生效方式: 重启游戏 (因为startup_scripts 只在启动时跑, /reload 不会重跑)

StartupEvents.registry('item', event => {
    event.create('player_mypack:my_house_blue')
        .displayName('我的小屋蓝图')
        .texture('player_mypack:item/my_house_blue')
        .maxStackSize(1)
        .tag('prefab_custom_addon:player_blueprint');
})

// 初始化绑定 NBT: packName / constructionId / locked
ItemEvents.modification(event => {
    event.modify('player_mypack:my_house_blue', item => {
        item.setCustomData(`{
            packName: "extension",
            constructionId: "my_house",
            locked: 1b
        }`);
    });
})

// 加到 Prefab 创造模式物品栏
StartupEvents.modifyCreativeTab('prefab:prefab_items', event => {
    event.add('player_mypack:my_house_blue');
})
```

**模板要点（逐条都是硬约束）：**

1. `setCustomData` 用**反引号模板字符串**包 SNBT 文本；`locked` 是 byte，必须带 `b` 后缀（`1b` / `0b`）；
2. `packName` / `constructionId` 是 SNBT 字符串，值里若有 `"` 需写成 `\"`；
3. `displayName` 建议纯文本，**不要包含单引号 `'`**（会破坏 JS 单引号字符串），包含双引号时写 `\"`；
4. 三处物品 ID（`event.create` / `event.modify` / `event.add`）和 `.texture()` 的命名空间必须完全一致；
5. `.tag('prefab_custom_addon:player_blueprint')` 是右键拦截的开关，**绝对不能漏**；
6. `locked` 语义：`1b` = 蓝图与建筑锁死（界面里"更换建筑"按钮禁用，不能再绑定其他建筑）；`0b` = 未锁定。AI 生成时默认填 `1b`，玩家明确想换建筑用 `0b`。

---

## 5. 第四步：生成配方脚本（可选但推荐）

写入 `.minecraft/kubejs/server_scripts/<ns>_<itemId>_blueprint_recipes.js`。
**必须走 `ServerEvents.recipes`，禁止写成 `kubejs/data/<ns>/recipes/*.json`**（那个目录是 datagen 输出，游戏不加载，JEI 搜不到）。

```js
// kubejs/server_scripts/player_mypack_my_house_blue_blueprint_recipes.js
// 生效方式: /reload 即可

ServerEvents.recipes(event => {
    event.shaped(
        'player_mypack:my_house_blue',
        [
            'AAA',
            'ABA',
            'AAA'
        ],
        {
            A: 'minecraft:paper',
            B: 'minecraft:diamond'
        }
    )
})
```

- 3×3 图案，空格子写 `' '`（空格）；材料种类 1~9 种，key 依次 A/B/C...；
- 先询问玩家该蓝图需要什么配方，玩家说不要配方就整个跳过（蓝图仍可用创造模式拿取）；
- 材料用带命名空间的完整 id（如 `minecraft:paper`、`create:cogwheel`）。

---

## 6. 第五步：物品贴图

写入 `.minecraft/kubejs/assets/<ns>/textures/item/<itemId>.png`（注意 `<ns>` 是含 `player_` 前缀的最终命名空间）。

- **正方形 PNG**；推荐尺寸 16×16（经典像素风）或 32×32 / 64×64（高细节）；上限 256×256；
- 若 AI 具备画图/写脚本能力，可程序化生成一张简单像素图标（如带边框的卷轴/房子剪影） 注意：先询问玩家是否要用自己准备的贴图；
- 非方形或非 2 的幂的图会被游戏内生成器缩放，AI 直接给正确尺寸最稳；
- `.texture('<ns>:item/<itemId>')` 对应路径 `kubejs/assets/<ns>/textures/item/<itemId>.png`，两边必须一致。

---

## 7. 第六步：生效与验证

1. **重启游戏**（启动脚本和贴图只认重启；配方改动可以只 `/reload`）；
2. 创造模式物品栏 → Prefab 的物品 tab（`prefab:prefab_items`）应出现蓝图物品；JEI 也能搜到；
3. JEI 搜物品名，确认配方出现；
4. 生存模式拿到物品后右键（空气或方块均可）→ 应弹出 3D 预览/建造界面；
5. 手持物品输入 `/kubejs hand`，确认输出里有 `CUSTOM_DATA` 且含 `packName` / `constructionId` / `locked`。

**右键后的行为（AI 可据此向玩家解释）：**
- 已绑定 → 打开该建筑的 3D 预览界面：预览、摆放位置、生存模式提交材料建造；
- 未绑定（NBT 丢失/没写）→ 打开建筑浏览器让玩家手动选建筑绑定；
- 右键后聊天栏提示"找不到建筑" → 见下方错误表。

---

## 8. 高频错误对照表（AI 排查时优先对照）

| 症状 | 原因 | 修复 |
|---|---|---|
| 启动报 `Unknown event 'ItemEvents.modifyDefaultComponents'`（或 `CreativeTabEvents.modify`） | 用了旧版 KubeJS API 名 | 只用本指南的三个事件：`StartupEvents.registry('item')`、`ItemEvents.modification`、`StartupEvents.modifyCreativeTab` |
| JEI 搜不到配方 | 配方写成了 `kubejs/data/<ns>/recipes/*.json` | 必须写 `kubejs/server_scripts/*.js`，走 `ServerEvents.recipes` |
| 右键物品无任何反应 | 漏了 `.tag('prefab_custom_addon:player_blueprint')` | 启动脚本补上 tag |
| 右键提示"找不到建筑" | `constructionId` 与建筑文件名（去扩展名）不一致，或文件被删/移走 | 改 NBT 或重命名文件，两者逐字符一致 |
| 物品贴图是紫黑棋盘 | 贴图路径与 `<ns>` / `<itemId>` 不一致，或 PNG 未放对目录 | 核对 `assets/<ns>/textures/item/<itemId>.png` |
| 服务器上物品在背包里消失 | 服务端没有同样的启动脚本 | 服务端 `kubejs/` 放同一份 startup 脚本（见第 9 节） |
| 修改了启动脚本但没变化 | startup_scripts 只在启动时跑 | 重启游戏，`/reload` 不够 |

---

## 9. 专用服务器注意（简版）

- 物品注册发生在两端：**服务端也必须有同一份 startup 脚本**，否则客户端注册的物品服务端不认识；
- 建筑文件、配方脚本建议两端保持一致；命名与 `constructionId` 不要两端各写一套；
- 单人游戏 / 客户端整合包无需关心本节。

---

## 10. AI 执行清单（收尾自检）

生成完毕后，AI 逐条确认：

- [ ] 建筑文件已在 `.minecraft/prefab-extension/`，且 `constructionId` = 文件名去扩展名
- [ ] 启动脚本放在 `kubejs/startup_scripts/`，三处物品 ID 与命名空间一致
- [ ] 命名空间以 `player_` 开头，全小写 `[a-z0-9_]`，不是 `prefab`/`minecraft`
- [ ] `setCustomData` 内含 `packName` / `constructionId` / `locked: ?b` 三个字段
- [ ] `.tag('prefab_custom_addon:player_blueprint')` 存在
- [ ] 配方脚本（如有）在 `kubejs/server_scripts/`，走 `ServerEvents.recipes`
- [ ] 贴图在 `kubejs/assets/<ns>/textures/item/<itemId>.png`，正方形 PNG
- [ ] 已提醒玩家：**重启游戏**后才生效；配方改动可 `/reload`
