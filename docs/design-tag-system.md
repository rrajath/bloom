# 标签系统设计（AI 任务分解共用）

本文件是任务分解功能（App 内 `com.rrajath.grove.ai.AiTagPolicy` 与仓库技能
`docs/skills/org-decompose.md`）唯一的标签规则来源。两处实现必须与本文件保持一致。

## 1. 书写形式

Org-mode 的标题标签是**尾部一个连续的冒号组**：`:planning:@computer:`。
Bloom 的解析器 `OrgParser.TAGS_AT_END` 只识别

```
^\s+(:[\p{L}\p{M}\p{N}_@#%-]+(?::[\p{L}\p{M}\p{N}_@#%-]+)*:)\s*$
```

因此 `@computer` 这类上下文标签也要写进同一个组里，不能像示例草稿那样写成
`:planning: @computer`（空格后的第二个组会被当成标题文字的一部分，标签丢失）。

示例草稿 → 实际写入：

```
* TODO 搭建CI流程 :planning: @computer      ← 示例写法（伪 Org）
* TODO 搭建CI流程 :planning:@computer:     ← 实际写法（Bloom 可解析）
```

语义完全保留：标签集合 = `planning`、`@computer`。同一标题内标签去重、保持
「类别标签在前、上下文标签在后」的顺序。

## 2. 标签词表

| 标签 | 含义 | 典型触发 |
| --- | --- | --- |
| `:coding:` | 编码 / 构建 / 联调 | C++、Qt、CMake、编译、UI、窗口、无边框、QWindowKit、framelesshelper、实现、修复、重构、依赖 |
| `:meeting:` | 会议 / 沟通 | 会议、周会、评审准备、沟通、对齐 |
| `:planning:` | 规划 / 澄清 / 拆解 | 计划、规划、周报、月报、OKR、复盘、需求澄清、范围、风险、方案设计、拆解 |
| `:health:` | 健康 / 就医 | 健康、医院、体检、预约 |
| `:writing:` | 文档 / 记录 | 文档、说明、发布说明、变更记录 |
| `:review:` | 评审 / 验收 | 评审、验收、Review |
| `@computer` | 需要在电脑前完成 | 编码、构建、文档、规划类任务 |
| `@office` | 需要在办公室 / 当面完成 | 会议、沟通类任务 |
| `:p1:` / `[#A]` | 紧急 | 标题含「紧急 / 阻塞 / 必须今天」等语义 |

## 3. 推断规则

### 顶层标题

1. 用户标题末尾已带标签（`:coding:` 或 `@computer`）→ **保留并优先使用**，
   不再覆盖用户显式表达；仅补齐推断出的、不重复的标签。
2. 按标题语义命中优先级顺序 `coding > meeting > planning > health`：
   - 技术/开发 → `:coding:` + `@computer`
   - 会议/沟通 → `:meeting:` + `@office`
   - 规划/复盘 → `:planning:` + `@computer`
   - 健康/就医 → `:health:`
3. 未命中任何类别时补 `:planning:` + `@computer`（任务清单默认是可执行规划）。
4. 紧急语义：在顶层标题加 `[#A]`（优先级 cookie），或加 `:p1:`，二选一，不强制。
   App 实现使用 `[#A]`（Bloom 的 `OrgHeadline.priority` 能直接索引）。

### 子任务（继承顶层标签后补充）

| 子任务语义 | 追加标签 |
| --- | --- |
| 需求澄清 / 范围 / 风险 / 方案设计 / 拆解 | `:planning:` |
| 环境 / 依赖 / 构建 / 实现 / 联调 / 修复 / 重构 | `:coding:` + `@computer` |
| 文档 / 说明 / 发布说明 / 变更记录 | `:writing:` + `@computer` |
| 评审 / 验收 / Review | `:review:` |

继承规则：顶层标签自动出现在每个子任务上；若子任务类别标签与顶层冲突
（例如顶层 `:meeting:`、子任务 `:coding:`），两者都保留，不删除继承项。
检查点（`***`）不继承、不加标签，保持纯文本。

## 4. 优先级与 DEADLINE

- 子任务按「依赖顺序」排序：澄清 → 环境 → 设计/拆解 → 实施 → 收尾/复盘。
- 顶层 DEADLINE 建议 = 今天 + 7 天；无紧急语义时按 7 天，紧急时按 2 天。
- 子任务 DEADLINE 在顶层截止日之前按序均匀铺开（最后一个不晚于顶层）。
- 模型若已输出 `DEADLINE:`，以模型值为准，App 只补缺失项，不覆盖。
- 时间戳统一写成 `DEADLINE: <YYYY-MM-DD Ddd>`（英文三日缩写，与
  `OrgTimestamp.format()` 一致），缩进两个空格，位于标题行下一行。
- 检查点不带 DEADLINE。

## 5. 写入边界

- 只写仓库根目录的 `inbox.org`；`archive/` 下任何文件都**不写**。
- 顶层用 `*`，子任务用 `**`，检查点用 `***`；每个子任务必须以 `** TODO` 开头。
- 追加位置：文件末尾，先补一个空行再写入整棵子树。
- 文本文件 UTF-8 无 BOM；换行跟随目标文件既有约定（Bloom 的
  `LineEndingFileStore` 会自动保留 CRLF），仓库侧技能按 Windows CRLF 处理。
