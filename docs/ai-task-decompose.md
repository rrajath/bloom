# AI 加持第一期：任务分解

> 分支：`feat/ai-task-decompose`（基线 `webdav-sync`）
> 范围：App 内四步任务分解向导 + 仓库侧 `org-decompose` 技能文档
> 标签规范：[design-tag-system.md](design-tag-system.md)

## 1. 目标

用户输入一个任务标题（例如「搭建CI流程」），App 通过可配置的 OpenAI 兼容
大模型端点，按「澄清 → 对比 → 分段设计 → 生成清单」四步，产出可直接执行的
Org-mode 子任务树，预览确认后追加到 `inbox.org`，并触发重新索引与既有同步链路。

这是「AI 加持」的第一期，但代码结构按可扩展设计：`ai/` 包只依赖一个
`AiClient` 接口，后续摘要、改写、周报、智能分类等能力可以复用同一套配置、
密钥存储与会话编排，不需要再碰设置页和网络层。

## 2. 明确的边界

| 项目 | 决策 |
| --- | --- |
| 模型 | 用户自填 OpenAI 兼容 `base URL` + `model` + `API Key`；不绑定任何厂商，不内置 Key |
| 密钥 | AES-256/GCM + Android Keystore，存 `filesDir/ai/secret.bin`；不进 DataStore、不进备份、不进设置导出 |
| 网络 | 复用 `libs.okhttp` (4.12.0) + `libs.kotlinx.serialization.json` (1.7.3)；HTTPS（仅回环地址允许 HTTP，便于本地模型） |
| 交互 | 完整四步向导，每步都要用户确认；最后一步是可编辑的 Org 预览 |
| Git | **App 内不做**。Bloom 的笔记目录是 SAF tree / WebDAV 镜像，不是 git 工作树，仓库里也没有 JGit 依赖；`commit / pull / push` 由仓库侧技能 `docs/skills/org-decompose.md` 承担（见第 6 节） |
| 写入目标 | 仅 `inbox.org`；`archive/` 不写；文件不存在则创建 |

## 3. 分层

```
com.rrajath.grove.ai                         纯 Kotlin（无 android.* 导入，可直接 JVM 单测）
├── AiConfig.kt            AiConfig / AiEndpoint.normalize / AiConfigProblem
├── AiClient.kt           AiMessage / AiClient / AiException
├── OpenAiCompatibleClient.kt   OkHttp + kotlinx.serialization 实现
├── AiSecretStore.kt       密钥读写接口（实现放在 Android 层同包）
├── AiKeystoreSecretStore.kt   AndroidKeyStore AES/GCM 实现（本目录唯一的 Android 文件）
├── DecomposeModels.kt     Stage / Qa / Approach / DecomposeTask / DecomposePlan
├── DecomposePrompts.kt   四步 system/user prompt 构造
├── AiTagPolicy.kt        标签 / 优先级 / DEADLINE 推断与注入
├── OrgTaskParser.kt      模型输出的 Org 文本 → DecomposePlan
├── OrgTaskRenderer.kt    DecomposePlan → Org 文本 + InboxAppender
└── DecomposeEngine.kt    四步编排（每次调用 = 一次 LLM 请求）

ui/screens/settings/      AiSettingsViewModel / SettingsAiScreen
ui/screens/ai/            DecomposeViewModel / DecomposeScreen
settings/                 GroveSettings 增加 aiBaseUrl / aiModel 两个字段
```

`AiClient` 是唯一的外部依赖边界：单测用内存 fake 驱动整个四步引擎，不需要网络。

## 4. 四步状态机

| 阶段 | 输入 | LLM 输出 | App 解析 | 用户动作 |
| --- | --- | --- | --- | --- |
| `TITLE` | 任务标题 | — | — | 输入标题，点「开始分解」 |
| `CLARIFY` | 标题 | 3–5 个关键问题，每行一条 | 去编号/去项目符号，取 3–5 行 | 逐个回答后确认 |
| `COMPARE` | 标题 + 问答 | 2–3 条实现路径，每条含 trade-off | 按编号切块，丢弃空块 | 单选一条路径 |
| `DESIGN` | 上述 + 选中路径 | 若干设计块，每块 200–300 字，用 `<<<BLOCK>>>` 分隔 | 按分隔符切块 | **逐块确认**，最后一块确认后进入生成 |
| `GENERATE` | 上述 + 全部设计块 | **纯 Org 文本，无解释** | `OrgTaskParser` → `AiTagPolicy` 注入 → `OrgTaskRenderer` | 可编辑预览，点「插入 inbox.org」 |
| `DONE` | — | — | — | 可再分解一个 |

任何一步失败都保留当前阶段与已填内容，只显示错误状态，允许「重试本步」；
不会丢失用户已经确认过的澄清答案与方案选择。

## 5. 写入链路

沿用 capture 的既有路径，不引入新的文件 I/O：

```
InboxAppender.append(existingText, orgSubtree)   // 末尾补一个空行后追加
  → Vault.save("inbox.org", newText)             // 文件不存在则先 createNotebook
  → SyncManager.requestReindex("inbox.org", newText, "ai task breakdown")
  → （既有）WebDAV afterLocalWrite 去抖上传 / Room 增量索引 / widget 刷新
```

`InboxAppender` 只做纯文本拼接，不解析 Org，未触碰的行保持原样。
`LineEndingFileStore` 负责按目标文件既有约定还原 CRLF；新建文件默认 LF，
UTF-8 无 BOM。

## 6. Git 操作由仓库侧技能承担

示例技能要求「操作前 commit + pull」「插入后 commit + push」。在 App 里做这件事
需要引入 JGit、把 SAF `DocumentFile` 树映射成真实路径、处理凭据与合并冲突，
对一个离线优先的 Org 笔记应用是明显的职责越界。因此拆成两半：

- **App**：只负责生成清单并写入 `inbox.org`，同步交给既有 SyncManager / WebDAV。
- **仓库技能** `docs/skills/org-decompose.md`：在仓库工作树里执行
  `git add -A && git commit ...`（有变更时）、`git pull --rebase`、写入
  `inbox.org`、`git commit -m "feat: 自动拆解任务: <任务名>" && git push`。

提交信息统一前缀 `feat: 自动拆解任务: <任务名>`（示例第 6 步的
`feat: 分解任务 [描述]` 视为旧写法，不采用）。

## 7. 与示例的偏差（有意为之）

1. **标签写法**：示例的 `:planning: @computer` 不是合法的 Org 尾部标签组，
   Bloom 解析不到。统一改为 `:planning:@computer:`，语义不变。详见
   [design-tag-system.md](design-tag-system.md)。
2. **Tag 注入位置**：模型负责任务语义与拆解，标签/优先级/DEADLINE 由
   `AiTagPolicy` 用确定性规则补齐与校正，避免模型输出格式漂移导致清单不可用。
3. **换行**：App 侧跟随目标文件既有 CRLF/LF，仓库侧技能按 Windows CRLF。
4. **纯文本输出**：只有 `GENERATE` 阶段强制纯 Org 文本；前三步需要结构化展示，
   用轻量分隔符解析而不是 JSON（对本地小模型更稳）。

## 8. 验证

- `app/src/test/java/com/rrajath/grove/ai/` 下的 JVM 单测覆盖端点归一化、
  标签推断、Org 解析/渲染、追加器、四步引擎、HTTP 错误映射。
- `:app:testDebugUnitTest --tests "com.rrajath.grove.ai.*"` +
  `NoHardcodedUiStringsTest`（UI 文本全部走 `stringResource`）。
- `:app:assembleDebug` 确认 Compose / Manifest / R.string 接线。
- 所有新增 Kotlin 文件中出现的英文说明性字面量，均用文件头
  `// i18n:` 标记为「非 UI 文本」（模型 prompt、Org 语法、内部错误原因）。

### 实测结果（2026-11-25）

- `:app:testDebugUnitTest`（AI 包 25 例 + i18n ratchet / 应用名 ratchet / 翻译检查 / 设置序列化）：**69 tests, 0 failures, 0 errors, 0 skipped**。
- `:app:assembleDebug`：**BUILD SUCCESSFUL**；产物 `bloom-ai-decompose-debug-v1.apk`（36,150,581 bytes，SHA-256 `dfd69f1169670c8b4f6b167ce50ca970cddaa5a81aae80abdde60daccd7a551f`）。
- 新增文件统一 UTF-8 无 BOM + CRLF；转换后再次跑 `compileDebugKotlin` 与三个 ratchet 测试，仍 BUILD SUCCESSFUL。
- 本机 `gradle/gradle-daemon-jvm.properties` 的 JDK25 配置为构建临时改动，验证后已还原，未进入提交。
