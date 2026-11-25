---
name: "org-decompose"
description: "将任务标题拆解为 Org-mode 子任务。Invoke when 需要生成可执行清单；统一插入到 inbox.org。"
---

# Org Decompose

你是一个 Org-mode 和 git 专家。请将用户输入的任务标题拆解为详细的、可执行的
子任务清单，并写入仓库根目录的 `inbox.org`。

> 本技能是 App 内「AI 任务分解」向导的仓库侧对应物。App 只写 `inbox.org`，
> 不执行 git；本技能在仓库工作树中运行，负责 git 环节。设计依据见
> [../ai-task-decompose.md](../ai-task-decompose.md)，标签规则见
> [../design-tag-system.md](../design-tag-system.md)。

## 硬要求

1. 操作前先 commit 本地修改（如果有变更），然后 pull 最新代码。
2. 使用 Org-mode 语法：`*` 表示输入的任务标题，`**` 表示子任务，
   `***` 表示检查点。
3. 每个子任务以 `** TODO` 开头。
4. `GENERATE` 阶段输出纯文本，不要额外解释。
5. 只写 `inbox.org`，不对 `archive/` 下任何文件写入。

## 执行步骤

### 1. 澄清需求

针对用户输入的任务，提出 3–5 个关键问题（目标用户、成功标准、约束条件、
依赖与风险），逐个确认。用户未回答完之前不要进入下一步。

### 2. 方案对比

给出 2–3 种实现路径，说明各自的 trade-off（成本、风险、可逆性、工期），
让用户选择一条作为后续设计与拆解的依据。

### 3. 分段设计

分块输出设计，每块 200–300 字，块与块之间用 `<<<BLOCK>>>` 分隔。
每一块都要等用户确认后再展示下一块；必要时按确认意见修订本块。

### 4. 生成 Org 任务清单

基于最终设计：

- 拆解为若干个 `** TODO` 子任务，按依赖顺序（澄清 → 环境 → 设计 → 实施 → 收尾）排序
- 每个子任务下附带 2–3 个 `***` 检查点
- 为顶层与子任务添加合理的 `DEADLINE` 建议
- 标签按 [design-tag-system.md](../design-tag-system.md) 自动推断与注入：
  - 顶层标题继承到所有子任务：技术/开发 `:coding:` + `@computer`；
    会议/沟通 `:meeting:` + `@office`；规划/复盘 `:planning:` + `@computer`；
    健康/就医 `:health:`
  - 子任务级别补充：需求澄清/范围/风险/方案设计/拆解 → `:planning:`；
    环境/依赖/构建/实现/联调/修复/重构 → `:coding:` + `@computer`；
    文档/说明/发布说明/变更记录 → `:writing:` + `@computer`；
    评审/验收/Review → `:review:`
  - 用户标题末尾已带的标签（如 `:coding:` 或 `@computer`）保留并优先使用
  - 标题含紧急语义（紧急/阻塞/必须今天）时，顶层加 `[#A]` 或 `:p1:`（不强制）
  - **书写形式**：Org 尾部必须是单个连续冒号组，例如
    `* TODO 搭建CI流程 :planning:@computer:`。不要写成 `:planning: @computer`
    ——空格后的第二组不会被 Bloom 的 `OrgParser.TAGS_AT_END` 识别为标签。

### 5. 插入文件

将生成的 Org 任务清单插入到仓库根目录的 `inbox.org`（不存在则创建），
追加到文件末尾**一段空行之后**，末尾保留一个换行。

### 6. Git 操作

```bash
# 步骤 1 之前
git add -A && git commit -m "chore: 自动拆解任务前保存本地修改"   # 仅当有变更
git pull --rebase

# 步骤 5 之后
git add inbox.org
git commit -m "feat: 自动拆解任务: <任务名>" && git push
```

提交信息前缀统一为 `feat: 自动拆解任务: <任务名>`。

## 示例输出

```org
* TODO 搭建CI流程 :planning:@computer:
  DEADLINE: <2026-12-02 Wed>
** TODO 需求澄清与范围 :planning:@computer:
  DEADLINE: <2026-11-27 Fri>
*** 与干系人确认目标与成果边界
*** 记录依赖与风险
** TODO 环境与仓库准备 :coding:@computer:
*** 创建或更新仓库结构与权限
*** 校验访问令牌与远端可达性
** TODO 方案设计与任务拆解 :planning:@computer:
*** 列出关键步骤与里程碑
*** 明确产出与验收标准
** TODO 实施与联调 :coding:@computer:
*** 逐步执行并记录变更
*** 完成联调与功能验证
** TODO 收尾与复盘 :writing:@computer:
*** 整理文档与问题清单
*** 归档完成项并创建后续任务
```

## 使用说明与注意

- 所有文本文件使用 UTF-8（无 BOM），Windows 换行符（CRLF）。
- 只写 `inbox.org`；不要改写已有标题，只在文件末尾追加。
- 在 App 内使用时不需要执行 git 步骤，清单写入后由既有同步链路接管。
