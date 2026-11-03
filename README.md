# Entry_Traversal

面向 **Minecraft 1.20.1 / Forge** 的聊天栏信息辅助模组。它将附魔与药水效果整理成可翻页、可点击的列表，方便在游戏中查询，并能一键把对应命令填入聊天框。

## 环境要求

| 项目 | 版本 |
| --- | --- |
| Minecraft | 1.20.1 |
| Forge | 47.4.16 及以上 |
| Java | 17 |
| 运行环境 | 客户端与服务端均需安装 |

## 功能概览

- 列出所有已注册附魔（包括其他模组注册的附魔），显示翻译名称、等级范围与适用物品。
- 列出所有已注册药水效果（包括其他模组注册的效果），显示翻译名称、等级范围与类别。
- 每条记录独占一行，自动分页，序号跨页连续计数。
- 点击记录行可把对应命令填入聊天框；悬停可查看 ID、等级、适用物品等详细信息。
- 支持按主手物品筛选“该物品可用的附魔”。
- 可选联动 **EnchantmentLevelBreak**：检测到该模组时，优先使用其 `/cenchant` 命令。

## 命令

| 命令 | 说明 |
| --- | --- |
| `/enchantlist [页码]` | 列出全部附魔 |
| `/enchantlist -a [页码]` | 仅列出主手物品可用的附魔 |
| `/enchantlist help` | 显示附魔列表命令用法 |
| `/effectlist [页码]` | 列出全部药水效果 |
| `/effectlist help` | 显示药水效果命令用法 |

示例：

```
/enchantlist          # 第 1 页，全部附魔
/enchantlist 2        # 第 2 页
/enchantlist -a       # 第 1 页，仅主手物品可用附魔
/enchantlist -a 2     # 第 2 页
/effectlist           # 第 1 页，全部药水效果
/effectlist 2         # 第 2 页
```

## 列表格式

附魔：

```
1. 锋利 等级 1-5 适用：木剑, 石剑, 铁剑, 金剑 等另 N 项
```

药水效果：

```
1. 速度 等级 1-2 增益
```

- 附魔名称、物品名称与效果名称均以可翻译组件发送，会按**客户端当前语言**显示。
- 附魔的等级范围来自该附魔的 `minLevel` / `maxLevel`。
- 附魔的适用物品通过遍历所有已注册物品并调用 `Enchantment#canEnchant` 得出，因此模组物品同样适用。
- 效果的最高等级取自所有已注册药水中该效果出现的最高等级（amplifier + 1）；没有对应药水的效果显示 `1-256`，即 `/effect` 命令的 amplifier 上限。

## 点击与悬停

- 点击附魔行：把附魔命令填入聊天框（只填入，不自动执行）。
  - 检测到 EnchantmentLevelBreak 且 `/cenchant` 可用时：`/cenchant <附魔ID> <最高等级>`
  - 否则：`/enchant @s <附魔ID> <最高等级>`
- 点击效果行：把 `/effect give @s <效果ID>` 填入聊天框。
- 悬停附魔行：显示 ID、支持等级、适用物品列表（最多 32 项）以及点击提示。
- 悬停效果行：显示 ID、可用等级、类别以及点击提示。

## 分页

- 每页 **15 条**。
- 页脚显示 `« 上一页  第 X/Y 页  下一页 »`，点击按钮直接翻页。
- 页码超出范围时自动夹取到有效页。
- 序号在所有页面之间连续（第 2 页从 16 开始，第 3 页从 31 开始）。
- 只有一页时不显示翻页栏。

## 与 EnchantmentLevelBreak 联动

- 模组 ID：`enchantmentlevelbreak`
- 命令：`/cenchant <附魔ID> [等级]`（作用于玩家主手物品）
- 本模组会同时检查“模组是否加载”和“命令是否真的注册在命令分发器中”，两者都满足时才使用 `/cenchant`，避免与其他同名 `/cenchant` 命令冲突。
- 如果未安装该模组，则自动回退到原版 `/enchant`。

## 供其他模组调用

`InfoCommands` 公开了以下静态方法，其他模组可以直接调用：

```java
public static int showEnchantments(CommandSourceStack source, boolean onlyApplicable, int requestedPage)
public static int showEffects(CommandSourceStack source, int requestedPage)
```

- `source` 决定聊天输出发送给谁（玩家、命令方块、控制台等）。
- 页码超出范围会自动夹取到有效页。
- 返回值为列表总条目数（不是当前页条数）。

两个命令本身也可以从命令方块、函数或 `/execute as ... run ...` 等外部来源执行。

## 构建

需要 JDK 17。

```bash
# 使用已安装的 Gradle 8.x
gradle build

# 或使用项目自带的 Gradle Wrapper（若已生成）
./gradlew build
```

构建产物：

```
build/libs/entry_traversal-1.0-SNAPSHOT.jar
```

将 jar 放入 Forge 1.20.1 客户端的 `mods/` 目录（服务端同样需要）即可。

## 项目结构

```
src/main/java/org/example_1/entry_traversal/
  Entry_Traversal.java     模组主类
  InfoCommands.java        /enchantlist 与 /effectlist 的实现
  Config.java              示例配置
src/main/resources/
  META-INF/mods.toml       模组元数据
  assets/entry_traversal/lang/
    en_us.json             英文文本
    zh_cn.json             中文文本
```

## 开发说明

项目基于 Forge MDK 模板创建，仍保留示例方块、示例物品与示例创造模式标签页（`entry_traversal:example_block`、`entry_traversal:example_item`、`entry_traversal:example_tab`）。如不需要，可自行删除相关注册代码。

## 许可证

All Rights Reserved。详见根目录 `LICENSE`。
