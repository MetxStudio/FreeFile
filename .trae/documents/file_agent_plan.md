# 文件管理 Agent（com.freefile.tools.ai）设计与实施计划

> 定位：**文件管理优先、通用能力可扩展**的工具调用型 Agent。
> 架构范式（已与用户确认）：**桌面端 Agent 模式**——用户自配 LLM API Key（OpenAI 兼容协议），LLM 负责理解、对话与工具调用编排；FreeFile 的全部文件能力以 **MCP 风格本地工具** 暴露，宿主对写操作强制预览/确认/撤销，文件事实只能来自工具结果。

---

## 1. 需求理解与范围边界

### 1.1 核心循环
理解意图 → 必要时澄清 → 生成计划 → 只读工具直接执行 → **写工具由宿主拦截、生成预览卡片、用户确认后才执行** → 逐项汇总结果 → 提供撤销入口。

### 1.2 范围
- **做**：侧拉栏入口；Agent 聊天主界面；MCP 风格工具协议与工具实现（MVP 子集）；权限分级、路径安全、批量阈值；操作日志、撤销记录；LLM 接入（用户自配 Key）与通用聊天/创作/问答/摘要；创作落盘走写操作流程。
- **MVP 不做（协议保留，二期实现）**：标签体系、收藏（现有书签可二期映射）、重复文件扫描、存储分析、加密/解密（`FileCrypto` 已就绪，二期接线）、分享到外部、远程文件系统操作、规则引擎 `create_rule`、OCR/相似图片、长期记忆、主动打扰式推荐。
- **绝不做**：不臆造文件/路径/大小/时间/结果；不自动执行破坏性操作；不绕过确认；未授权不上传文件内容。

### 1.3 优先级与中断规则
1. 文件指令优先；混合请求先文件后通用。
2. **存在待确认写操作时，确认卡片置顶锁定**：新消息不取消、不覆盖待确认项；模型在用户决策前拿到的是 `awaiting_confirmation` 工具结果，被要求等待。
3. 用户长时间闲聊可简短提示，不强制拉回。
4. 创作结果要保存/分享/压缩/移动，必须转为 `write_text_file` 等写工具，经预览确认。

### 1.4 隐私边界
- 默认**只发送完成任务所必需的文件元数据**（路径、名称、大小、时间、工具返回摘要）到用户自配的端点。
- 文件正文读取（`read_text_file`，用于摘要/代码解释）受独立开关控制，**默认关闭**；关闭时工具直接返回错误，要求模型先请求用户在设置中开启。
- 不内置任何官方 API Key；不经过任何第三方中转；端点、模型、Key 均由用户填写，存于本机。
- 敏感目录（应用私有目录 `/data/data/*`、`.safe`/保险箱、加密容器、Agent 自身目录）默认不搜索、不扫描、不可作为工具目标。

---

## 2. 模块结构、路由与侧拉栏入口

### 2.1 工程决策
项目当前为**单模块** application（`settings.gradle.kts` 无子模块），且既有功能工具均位于 `com.freefile.tools.*`（pdfviewer/web/richeditor/unzip 等）。遵循硬约束"最小改动、不改工程配置"，**不新建 Gradle 模块**，在 app 模块内以包隔离：

```
com.freefile.tools.ai
├── AgentActivity.kt                  # 独立任务栈入口 Activity
├── AgentConstants.kt                 # TAG / Action / Extra / 深链前缀，统一命名空间
├── data/
│   ├── AgentDatabase.kt              # 独立 Room 库 agent.db（v=1，不动 freefile.db v3）
│   ├── entity/  (Conversation, Message, ToolCall, OpLog)
│   ├── dao/     (ConversationDao, MessageDao, ToolCallDao, OpLogDao)
│   ├── AgentSettings.kt              # 独立 DataStore：freefile_agent_settings
│   └── AgentRepository.kt            # 会话/日志/撤销记录读写
├── model/
│   ├── ChatMessage.kt                # OpenAI 兼容消息模型（org.json 序列化）
│   ├── ToolSchema.kt                 # 工具名/描述/JSON Schema/权限级别/可撤销标记
│   ├── ToolResult.kt                 # 统一返回 + 错误码枚举
│   ├── AgentMode.kt                  # AUTO / FILE / GENERAL
│   └── AgentState.kt                 # 状态机状态
├── tool/
│   ├── AgentTool.kt                  # 工具接口：schema + 权限 + suspend execute
│   ├── ToolRegistry.kt               # 注册/查找/权限汇总
│   ├── PathGuard.kt                  # 规范化、越权校验、敏感目录、路径穿越拦截
│   ├── ReadonlyTools.kt              # search_files/list_dir/get_file_info/list_trash
│   ├── WriteTools.kt                 # mkdir/rename/move/copy/delete_to_trash/restore
│   ├── ArchiveTools.kt               # compress/extract（接 TransferManager）
│   ├── ContentTools.kt               # read_text_file（受开关）/write_text_file
│   └── PluginBridgeTool.kt           # run_plugin_action（外部 App Intent 桥接）
├── security/
│   ├── ConfirmationGate.kt           # 写工具拦截：tool_call → 待确认预览 → 批准/取消
│   └── UndoManager.kt                # 逆操作生成与执行；逐项失败记录
├── llm/
│   ├── LlmClient.kt                  # 接口：流式 chat completion
│   ├── OpenAiCompatibleClient.kt     # OkHttp + okhttp-sse；preset providers
│   ├── AgentOrchestrator.kt          # 工具调用循环、迭代上限、取消、模式系统提示词
│   └── Prompts.kt                    # 中/英文系统提示词、工具协议说明、当前日期注入
└── ui/
    ├── AgentScreen.kt                # 主界面（消息流 + 输入栏 + 模式切换）
    ├── AgentViewModel.kt
    ├── components/
    │   ├── MessageBubble.kt          # 用户/助手气泡、文件事实 chips
    │   ├── ToolCallCard.kt           # 工具调用过程卡（可折叠）
    │   ├── ConfirmPlanCard.kt        # 写操作预览/勾选/批量汇总/危险二次确认
    │   ├── AgentProgressCard.kt      # 执行进度
    │   ├── ResultUndoCard.kt         # 逐项结果 + 撤销按钮
    │   └── OnboardingSheet.kt        # 首次能力/权限/隐私说明
    ├── AgentSettingsScreen.kt        # 端点/Key/模型/内容开关/批量阈值/入口可见性
    └── OpLogScreen.kt                # 操作日志查看
```

测试代码：`app/src/test/java/com/freefile/tools/ai/...`（PathGuard、参数校验、撤销逆操作、工具注册、结果截断）。

### 2.2 路由与常量（AgentConstants）
- 日志 TAG：`FreeFileAI`
- Intent Action：`com.freefile.tools.ai.action.CHAT`
- Extras（均以包名为前缀）：
  - `com.freefile.tools.ai.extra.PATHS`（ArrayList<String>，预选文件/目录）
  - `com.freefile.tools.ai.extra.PROMPT`（String，预填指令）
- AndroidManifest：`AgentActivity`，`exported="false"`，`taskAffinity="${applicationId}.aiagent"`，`configChanges` 与其他 Activity 一致，`label="@string/ai_agent_title"`。
- 不使用 navigation-compose 新图：沿用项目"独立 Activity + Compose"既有模式（同 ToolTrayActivity/SettingsActivity）。

### 2.3 侧拉栏接入（必须位于"工具托盘"正下方）
- [MainScreen.kt](file:///d:/1AndroidProject_D/FreeFile2/app/src/main/java/com/freefile/ui/MainScreen.kt) 改动点（仅 3 处，最小侵入）：
  1. `launchToolTray()` 旁新增 `launchAgent(paths, prompt)`，`FLAG_ACTIVITY_NEW_TASK` 启动 `AgentActivity`。
  2. `DrawerContent(...)` 参数新增 `onOpenAgent: () -> Unit`；在"工具"分区中**紧接工具托盘 item（约 L1399 之后）**插入 `DrawerEntryRow(Icons.Rounded.SmartToy, R.string.ai_agent_title, R.string.ai_agent_sub, onClick=onOpenAgent)`；可见性受 `AgentSettings.entryVisible`（默认 true）控制，不可见时不渲染。
  3. 多选 `showActionSheet` 内新增一项"用 Agent 处理"（`Icons.Rounded.SmartToy`），携带 `activeVm.selectedEntries()` 路径启动 Agent（交互入口 §6.3）。
- 图标使用 material-icons-extended（项目已依赖 1.4.0）中的 `SmartToy`；样式完全复用 `DrawerEntryRow`。
- 文案全部进 `values/strings.xml` 与 `values-en/strings.xml`。

### 2.4 刷新闭环
写操作完成后调用现有 `FileChangeBus.notify(dirPath)`，MainScreen 已订阅该总线自动刷新窗格，无需改造主界面。

---

## 3. Agent 状态机

`AgentViewModel` 以单一 `StateFlow<AgentUiState>` 驱动，旋转屏由 ViewModel + Room 持久化自然恢复。

```
                 ┌──────────────────────────────┐
                 │  Bootstrapping（读取设置/会话）│
                 └──────────────┬───────────────┘
                                │
                  Key 未配置 ────┼────────────► NeedsSetup（引导去设置，可看介绍）
                                │
                     ┌──────────▼──────────┐
        ┌───────────►│        Idle         │◄──────────────┐
        │            └──┬───────────────┬──┘               │
        │               │ 用户发送       │ 选中文档且        │
        │       通用消息 │               │ 内容开关关        │
        │               ▼               ▼                  │
        │        ┌─────────────┐  ┌──────────────────┐    │
        │        │ Streaming   │  │ NeedsPermission  │    │
        │        │（助手回复中）│  │（请求开内容开关） │    │
        │        └──┬──────────┘  └──────────────────┘    │
        │           │ 模型发起 tool_call                    │
        │           ▼                                      │
        │   ┌─────────────────┐    只读工具   ┌───────────┐│
        │   │ ToolCallPlanning│─────────────►│ Executing ││
        │   │（宿主解析/校验） │              │（进度回调）││
        │   └──┬──────────┬───┘              └──┬────────┘│
        │      │写工具     │信息不足             │         │
        │      │          ▼                     ▼         │
        │      │   ┌────────────────┐   ┌──────────────┐  │
        │      │   │ Clarifying     │   │ Reporting    │──┘
        │      │   │（模型追问用户） │   │（结果+撤销） │
        │      │   └────────────────┘   └──────────────┘
        │      ▼
        │  ┌────────────────────────────┐
        │  │ AwaitingConfirmation ★锁定 │ 用户取消 → 工具结果回填"已取消"→ Idle
        └──┤ 预览卡片置顶；可改选/前N个  │ 用户确认 → 带批准令牌重放 → Executing
           └────────────────────────────┘
```

规则：
- 任意阶段可取消（取消 LLM Job；TransferManager 任务调 `cancel(taskId)`）。
- `AwaitingConfirmation` 期间输入栏仍可输入，但发出的消息排队等待决策后处理，确认卡不被移除；UI 提示"有操作待确认"。
- 工具调用循环设硬上限（默认 8 轮），超出则要求模型总结并停下。
- 异常态：网络/鉴权/限流/JSON 解析失败均转 `Reporting` 展示明确错误，不静默重试写操作。

### 3.1 写工具拦截协议（兼容标准 OpenAI tool_calls）
1. 模型返回 `tool_call(rename, args)`，宿主**不执行**：`PathGuard` + 参数校验后构造预览。
2. 宿主立即回灌一条 tool message（同一 `tool_call_id`）：
   `{"status":"awaiting_confirmation","preview":{...摘要...},"note":"已向用户展示预览，等待确认，请勿重复调用"}`
   同时 UI 进入 `AwaitingConfirmation`，模型自然输出计划说明。
3. 用户**确认**：宿主以批准的参数实际执行 → 追加 `{role:"tool", tool_call_id, content:真实结果}` → 继续循环。
4. 用户**取消/改选**：以修改后参数生成新预览（走 2）；取消则回填 `{"status":"cancelled_by_user"}`。
5. 模型无法自行触碰执行通道；批准令牌一次性、参数哈希绑定，防替换。

---

## 4. 工具 Schema 清单

统一约定：
- 传输形态：OpenAI Chat Completions `tools` 数组（type=function，parameters 为 JSON Schema），由 `ToolSchema` 用 `org.json` 构建，**不引入序列化新依赖**。
- `ToolResult`：`{ ok: Boolean, code: ErrorCode?, data: Any?, truncated: Boolean }`；列表类结果**上限 100 条并返回 total/hasMore**，防止上下文爆涨。
- 错误码：`INVALID_PATH / OUT_OF_SCOPE / NOT_FOUND / PERMISSION_DENIED / CONFLICT / ALREADY_EXISTS / NO_SPACE / PASSWORD_REQUIRED / WRONG_PASSWORD / IO_ERROR / UNSUPPORTED / CANCELLED / NOT_INSTALLED / CONTENT_NOT_AUTHORIZED / NEEDS_CONFIRMATION / INVALID_ARGS`。

| # | 工具 | 关键参数 | 权限级别 | 写 | 可撤销 | MVP | 备注/复用点 |
|---|------|---------|---------|----|-------|-----|------------|
| 1 | `search_files` | query, ext[], is_dir, size_op, size_bytes, time_from, time_to, scope(root), content, depth, use_index | READ | 否 | — | ✅ | 映射 `SearchOptions`，调 `SearchEngine.searchLive/searchIndexed`；scope 可传 `trash`（查 Room 回收站） |
| 2 | `list_dir` | path | READ | 否 | — | ✅ | `EngineSelector.select(path).list()` |
| 3 | `get_file_info` | path, hash?(md5/sha1/sha256) | READ | 否 | — | ✅ | `stat` + `FileOperations.hash`；EXIF 等二期 |
| 4 | `list_trash` | name_kw, time_from, time_to | READ | 否 | — | ✅ | 读 `Repository.trash` 流首帧过滤 |
| 5 | `open_with` | path, target(system/plugin_id) | EXTERNAL_READ | 否 | — | ✅ | 复用 `FileOpener.openWithMime`；外部启动明确告知"离开本应用" |
| 6 | `mkdir` | path | WRITE | 是 | ✅（移入回收站） | ✅ | `engine.mkdirs` |
| 7 | `rename` | path, new_name | WRITE | 是 | ✅（改回） | ✅ | 同名校验、不可改名到其他目录 |
| 8 | `move` | paths[], destination, conflict(skip/overwrite/rename) | WRITE | 是 | ✅（移回原父目录） | ✅ | `FileOperations.detectConflicts` + `TransferManager.enqueueMove` |
| 9 | `copy` | paths[], destination, conflict | WRITE | 是 | ✅（副本入回收站） | ✅ | `enqueueCopy` |
| 10 | `delete_to_trash` | paths[] | DESTRUCTIVE_REVERSIBLE | 是 | ✅（恢复） | ✅ | `Repository.moveToTrash`；无永久删除工具 |
| 11 | `restore_from_trash` | trash_ids[] 或 paths[] | WRITE | 是 | ✅（重新入站） | ✅ | `Repository.restore` |
| 12 | `compress` | paths[], format(zip/7z/tar/tar.gz/tar.bz2/tar.xz), destination, level, split_mb(仅zip) | WRITE | 是 | ✅（产物入回收站） | ✅ | `CompressionEngine.Options` + `enqueueCompress`；按硬约束**不带密码** |
| 13 | `extract` | archive, destination | WRITE | 是 | ✅（新增项入回收站） | ✅ | `enqueueExtract`；记录实际展开清单供撤销 |
| 14 | `write_text_file` | path, content, format(txt/md/html/pdf) | WRITE | 是 | ✅ | ✅ | 创作落盘；PDF 用系统自带 `android.graphics.pdf.PdfDocument` 渲染，零新依赖；冲突走预览 |
| 15 | `read_text_file` | path, max_chars | READ_SENSITIVE | 否 | — | ✅ | 受"允许发送文件内容"开关；≤1MB 文本，超长截断 |
| 16 | `run_plugin_action` | plugin_id/package, action, paths[] | EXTERNAL | 否* | — | ✅ | Intent 桥接（§7.4），逐项报告 已发起/未安装/失败 |
| 17 | `undo_last_action` | — | WRITE(元) | 是 | — | ✅ | 调 UndoManager 最近一条可撤销记录 |
| 18 | `encrypt` | paths, destination | SENSITIVE | 是 | 条件 | 🔜二期 | `FileCrypto`，二次确认；MVP 注册但返回 UNSUPPORTED |
| 19 | `decrypt` | path, destination | SENSITIVE | 是 | 条件 | 🔜二期 | 同上，密码错误不产出明文 |
| 20 | `tag` | paths, tags[] | WRITE | 是 | ✅ | 🔜二期 | 标签表二期 |
| 21 | `share` | paths, options | EXTERNAL | 否 | — | 🔜二期 | `FileOpener.shareFiles`，二次确认 |
| 22 | `scan_duplicates` | scope, by(name+size/hash) | READ(重) | 否 | — | 🔜二期 | 报告只读；删除仍走 delete_to_trash |
| 23 | `analyze_storage` | scope | READ(重) | 否 | — | 🔜二期 | 大文件/空目录/分类占比 |
| 24 | `create_rule` | rule_definition | PRIVILEGED | — | — | 🔜二期 | 规则引擎二期 |

> 二期工具同样在 `ToolRegistry` 注册元数据但执行返回 `UNSUPPORTED`，模型可据此向用户说明，协议不留缺口。

---

## 5. 权限与确认矩阵

| 场景 | 级别 | 执行方式 | 撤销策略 |
|------|------|---------|---------|
| 搜索/列目录/信息/回收站列表/分析报告 | READ | 直接执行，过程可见 | — |
| 读取文本送模型 | READ_SENSITIVE | 开关关 → 拒绝并让模型请求授权；开关开 → 直接 | — |
| 新建/重命名/移动/复制/压缩/解压/写文件 | WRITE | **必出预览卡**：文件清单（复选框）、目标路径、数量、冲突策略、是否可撤销；确认后执行 | 自动记录逆操作，结果卡提供"撤销" |
| 删除 | DESTRUCTIVE | 只能入回收站；**永久删除不向模型开放** | 恢复（restore） |
| 批量 > 10（阈值可配 1–100） | WRITE/DESTRUCTIVE | 强制汇总确认，提供 全选/反选/只处理前 N 个 | 按实际成功项撤销 |
| 覆盖同名 | WRITE+冲突 | 预览中默认"重命名保留两者"，选覆盖需在卡片上二次勾选 | 被覆盖项无法找回时明确红字警告（现有引擎无版本历史，§7.5） |
| 加密/解密（二期） | SENSITIVE | 二次确认 + 密码 UI（不经模型传密码，密码仅用户本地输入） | 条件撤销 |
| 分享到外部/拉起第三方 App/打开文件 | EXTERNAL | 明确提示"内容将离开本应用"；分享类二期二次确认 | — |
| Root/Shizuku 路径 | PRIVILEGED | 预览卡显示"将使用 Root/Shizuku"并二次确认；遵循 `EngineSelector` 当前模式 | — |
| 终端命令（二期 create_rule/terminal） | PRIVILEGED | 逐条二次确认，禁止静默执行；遵守 `waitForCompat` 等既有约束 | — |
| 路径越权/目录穿越（`..`、符号链接逃逸、敏感目录） | 拦截 | PathGuard 直接返回 INVALID_PATH/OUT_OF_SCOPE，不进入预览 | — |

**PathGuard 规则**：`canonicalPath` 归一；拒绝相对路径与 `..` 穿越；允许根：`/storage/emulated/0`、`/sdcard`、外接存储（`/storage/XXXX-XXXX`）、`/`（只读浏览；写 `/` 下系统目录要求特权且二次确认）；黑名单：`/data/data`、应用 files/noBackup、`.safe`、Agent 数据库目录；`remote://` 与 `#` 压缩包路径 MVP 仅允许只读工具，写操作返回 UNSUPPORTED（二期）。

---

## 6. UI 入口与结果页设计

### 6.1 侧拉栏主入口
"工具"分区内、工具托盘正下方：图标 `SmartToy`，标题"文件管理 Agent"，副标题"用自然语言管理文件"。点击进入 AgentActivity；首次进入展示 `OnboardingSheet`（能力清单 + 权限/隐私说明 + "去配置 API Key"）。

### 6.2 Agent 主界面
- **TopAppBar**：标题 + 会话名；动作：模式（自动/文件/通用，`FilterChip` 三选一并常驻可见）、新建会话、日志、设置。
- **消息流**：
  - 用户气泡；助手 Markdown 轻量渲染（加粗/列表/代码块，自绘最小实现，不引库）。
  - **文件事实以 chip/卡片呈现**：路径、大小、修改时间、命中原因（"名称匹配 *.pdf · 18MB · 修改于 9 月"）；点击 chip → 启动 `OpenWithActivity` 或跳转主界面定位（通过 `FileChangeBus` 无关；定位用发广播/Intent 唤起 MainActivity 二期，MVP chip 点击走 OpenWith）。
  - `ToolCallCard`：工具名 + 参数摘要 + 状态（执行中/成功/失败），可折叠看原始 JSON。
  - `ConfirmPlanCard`（写操作）：操作类型图标标题；目标路径（可编辑）；条目可勾选列表（全选/反选/只处理前 N）；冲突策略下拉；危险项红色警示；底部"确认执行 / 取消"。
  - `AgentProgressCard`：复用 TransferManager 任务流的进度语义（当前文件、计数、百分比），可取消。
  - `ResultUndoCard`：成功/失败/跳过逐项计数与清单；失败原因逐项展示；"撤销本次操作"与"定位到目录"。
- **输入栏**：文本 + 发送；流式中变"停止"；附件按钮显示"已引用 N 个文件"（来自入口 extras）；未配 Key 时输入栏置顶提示条。
- **模式行为差异（通过系统提示词实现，对用户可见）**：自动=默认；文件=通用问题简答并引导；通用=允许完整创作，写操作规则不变。

### 6.3 其他入口（MVP 轻量版）
- 多选 ActionSheet 新增"用 Agent 处理"→ 携带路径进入，输入栏预填"已选中 N 个文件：…"。
- `OpenWithActivity` 二期加入"问 Agent"；MVP 不改动其现有逻辑。
- 全局命令栏/文件详情页入口二期。

### 6.4 设置页（AgentSettingsScreen）
- 服务商预设（OpenAI / DeepSeek / Moonshot Kimi / 通义千问兼容模式 / 自定义 baseUrl）、API Key（密码框）、模型名、"测试连接"。
- 开关：入口可见（默认开）、允许发送文件内容（默认关）、显示思考/工具详情（默认开）。
- 批量确认阈值（默认 10）。
- 首屏说明与隐私政策再看入口；清除会话与日志。

### 6.5 日志页（OpLogScreen）
时间、用户指令、工具名、参数摘要、结果（成功/失败数）、撤销方式/状态；仅本机存储，可导出/清空。

---

## 7. 与现有能力的集成点

| 现有能力 | 文件 | Agent 集成方式 |
|---------|------|---------------|
| 搜索引擎 | [SearchEngine.kt](file:///d:/1AndroidProject_D/FreeFile2/app/src/main/java/com/freefile/search/SearchEngine.kt) | 把模型参数映射为 `SearchOptions`（深度/正则/大小 SizeOp/时间窗/contentPattern/useIndex）；索引未就绪自动实时回退（既有逻辑） |
| VFS 引擎 | [EngineSelector.kt](file:///d:/1AndroidProject_D/FreeFile2/app/src/main/java/com/freefile/vfs/EngineSelector.kt)、FileEngine、LocalEngine、ShellEngine | list/stat/mkdirs/rename/delete/流读写；Root>Shizuku>Local 策略天然继承 |
| 文件操作 | [FileOperations.kt](file:///d:/1AndroidProject_D/FreeFile2/app/src/main/java/com/freefile/operations/FileOperations.kt) | detectConflicts/copy/move/hash/readText/writeText |
| 任务队列 | [TransferManager.kt](file:///d:/1AndroidProject_D/FreeFile2/app/src/main/java/com/freefile/operations/TransferManager.kt) | enqueueCopy/Move/Compress/Extract + tasks 进度流 + cancel；完成回调走应用级 mainScope |
| 回收站 | [Repository.kt](file:///d:/1AndroidProject_D/FreeFile2/app/src/main/java/com/freefile/data/repo/Repository.kt) | moveToTrash/restore/trash Flow；list_trash 直接读 DAO 流首帧 |
| 压缩 | CompressionEngine | Options(format/level/splitMb)；压缩与密码解耦（无密码 UI/参数） |
| 加解密 | [FileCrypto.kt](file:///d:/1AndroidProject_D/FreeFile2/app/src/main/java/com/freefile/security/FileCrypto.kt) | 二期 encrypt/decrypt 工具，密码走本地原生输入框 |
| 打开/分享 | FileOpener、OpenWithActivity | open_with 工具；"打开文件必须经 OpenWithActivity"约束：单文件打开复用该 Activity |
| 文件变更 | FileChangeBus | 所有写成功后 notify 父目录，主界面自动刷新 |
| 工具托盘 | ToolTrayScreen、assets/apk/index.json、ExternalToolsStore | run_plugin_action 按 packageName 解析，Intent 附 FileProvider Uri + MIME（见 7.4） |
| 插件骨架 | [PluginManager.kt](file:///d:/1AndroidProject_D/FreeFile2/app/src/main/java/com/freefile/plugin/PluginManager.kt) | 二期内部插件注册位；MVP 不改 |
| 设置 | [AppSettings.kt](file:///d:/1AndroidProject_D/FreeFile2/app/src/main/java/com/freefile/data/settings/AppSettings.kt) | **不改动**；Agent 设置独立 DataStore |
| 数据库 | [FreeFileDatabase.kt](file:///d:/1AndroidProject_D/FreeFile2/app/src/main/java/com/freefile/data/db/FreeFileDatabase.kt) | **不改动**（避免 v3→v4 destructive 清库）；新建 agent.db |
| 收藏 | Bookmark DAO | 二期 tag/favorite 工具映射 |

**插件桥接细节（7.4）**：`run_plugin_action` 先查 `index.json` 登记的工具，再用 `packageManager.getLaunchIntentForPackage` / `queryIntentActivities(ACTION_VIEW/SEND_MULTIPLE)` 解析；通过现有 `${applicationId}.fileprovider` 授权 File Uri（复用 `FileOpener.prepareSharableFile` 思路，压缩包内文件先解缓存）。Agent 不等待第三方结果，逐项回报"已发起"；未安装回报 NOT_INSTALLED 并建议去工具托盘下载。"图像工具箱"未内置，模型可推荐用户安装支持图片编辑的 App 并由用户指定包名/选择匹配 Activity。

**版本历史（7.5）如实说明**：项目当前**不存在版本历史机制**，覆盖操作不可恢复。MVP 用 Agent 操作日志 + 撤销记录承担审计职责；预览默认采用"重命名保留两者"规避覆盖，覆盖必须显式勾选并红字警示；独立版本历史建议二期单独立项。

---

## 8. LLM 接入设计

- 协议：OpenAI Chat Completions（`/v1/chat/completions`），`tools` + `tool_calls` + `tool` 角色消息；`stream=true`。
- 新增依赖（仅一个，同版本族）：`com.squareup.okhttp3:okhttp-sse:4.12.0`（理由：标准 SSE 事件解析，避免手写流解析的边界 bug；与既有 okhttp 4.12.0 同版本）。
- 兼容兜底：若服务商流式下不回 tool_calls 分片，自动降级 `stream=false` 重试（设置中可关流式）。
- 系统提示词要点：当前日期（用于"上个月"解析）、常见目录绝对路径表、只能依据工具结果陈述文件事实、写操作必然要用户确认（模型侧行为规范，真正强制在宿主）、模式策略、回答语言跟随用户。
- Key 存储：DataStore 本机明文（与项目现有 NetworkLocation 密码存储同级）；文档注明风险，二期可换 EncryptedFile。
- 超时/限流：30s 连接、首 token 60s；401/429/5xx 明确中文提示。
- 成本防护：工具结果截断、单轮上下文条数上限（会话超长按摘要折叠，MVP 先截断+提示新建会话）、迭代 8 轮上限。

---

## 9. 实施步骤（依赖排序）

1. **M0 骨架**：建包；AgentActivity + manifest 注册；AgentConstants；strings（中/英）；MainScreen 三处接入（抽屉入口项、launchAgent、ActionSheet 项）；OnboardingSheet；无 Key 引导页。
2. **M1 存储层**：AgentDatabase（4 实体/DAO）、AgentSettings、AgentRepository。
3. **M2 协议层**：ToolSchema/ToolResult/ErrorCode、AgentTool 接口、ToolRegistry、PathGuard（带单测）、ConfirmationGate 批准令牌。
4. **M3 只读工具**：search_files、list_dir、get_file_info、list_trash、read_text_file（开关）。
5. **M4 写工具 + 撤销**：UndoManager（逆操作+op log）、mkdir/rename/move/copy/delete_to_trash/restore，接 TransferManager/Repository/FileChangeBus；批量阈值与冲突策略。
6. **M5 压缩与内容**：compress/extract（含 extract 落地清单）；write_text_file（txt/md/html，PDF 用 PdfDocument）。
7. **M6 插件桥接**：run_plugin_action + 未安装引导。
8. **M7 LLM 编排**：LlmClient/OpenAiCompatibleClient(SSE)、预设服务商、设置页与测试连接、Prompts、AgentOrchestrator 工具循环、拦截协议、取消与迭代上限、三模式。
9. **M8 UI 收口**：消息气泡、ToolCallCard、ConfirmPlanCard、ProgressCard、ResultUndoCard、日志页、会话持久化与旋转恢复。
10. **M9 测试与验收**：单元测试 + 12 条验收用例手工回归（§10）。

## 10. 验证与验收用例映射

**自动化（JUnit，不依赖网络/Key）**
- PathGuard：`..` 穿越、符号路径、`/data/data`、外部存储白名单、remote/archive 只读。
- 工具参数校验与错误码；列表截断 100 条；注册表权限/可撤销元数据完整。
- UndoManager：rename/move/copy/compress/extract/write/trash/restore 的逆操作生成与部分失败记账。
- 确认协议：批准令牌一次性、参数哈希绑定；写工具未经确认不产生 IO（用临时目录断言）。

**手工/真机回归（对应用户 12 条）**
1. 工程：代码均在 com.freefile.tools.ai；抽屉工具托盘下方有入口可进入；未配 Key 显示引导。
2. "找出下载目录里上个月大于10MB的PDF"→ 真实列表（chip 显示路径/大小/时间），断网/换问法不出现虚构项。
3. "按类型归档到子文件夹"→ 先计划后执行，结果卡可一键撤销复原。
4. "删除重复文件"→ MVP 无扫描工具时模型如实说明能力边界；若用户已给出文件列表，删除进回收站且可恢复（二期 scan_duplicates 出报告）。
5. "选中图片压缩成 zip 并加密"→ 压缩正常执行；加密诉求按现状说明压缩包不带密码、可生成后用加密功能（二期工具直接承接）。
6. "恢复回收站里昨天的报告"→ list_trash 时间过滤 → 确认 → restore，主目录刷新可见。
7. "用图像工具箱压缩这几张图"→ 未安装时 NOT_INSTALLED 引导；安装匹配 App 后逐项"已发起"回报。
8. "帮我写周报"→ 通用模式正常创作；说"保存到下载目录"→ 弹 write_text_file 预览确认。
9. "解释这段代码"→ 授权开关后读文本解释，并主动询问是否重命名/归档/打开。
10. "讲个笑话"→ 简短回应，不长聊。
11. "把周报保存成 PDF 放到工作目录"→ 预览路径/文件名/格式，确认后生成 PDF，可撤销（入回收站）。
12. 越权路径/批量覆盖/永久删除/root：越权被拒；覆盖默认保留两者且勾选才覆盖；无永久删除工具；特权路径二次确认。

另回归：现有工具托盘/终端/压缩解压/多选操作不受影响；主界面随 FileChangeBus 正常刷新；旋转屏进度与待确认状态不丢。

## 11. 依赖与注意事项
- 仅新增 `okhttp-sse:4.12.0`；不改 compileSdk/targetSdk/包名/NDK；不加 DocumentFile 用法。
- 所有新文案进 strings.xml（中/英）；Material3 实验 API 加 `@OptIn`；点击传参用具名参数；进程相关 catch Throwable；不在组合 scope 起长任务（用 ViewModel/应用 mainScope）。
- Compose BOM 2024.02 下 `SmartToy` 图标在 material-icons-extended 1.4.0 已存在。
- LLM 相关网络错误不得崩溃；API Key 不入日志、不进 op log 明文。

## 12. 风险与应对
- **服务商 tool_calls 兼容性差异**：预设表内置已验证厂商；自定义端点提供"流式/非流式"降级与连接测试。
- **模型幻觉**：宿主是唯一事实源；路径必须 stat 验证；写操作结构性拦截，模型无法绕过。
- **大结果撑爆上下文/费用**：100 条截断、读文本 1MB 上限、迭代轮次上限、提示新建会话。
- **撤销不完整**（跨卷移动中失败、第三方 App 改动）：逐项如实报告，撤销只覆盖可证明项；覆盖类操作预先警示。
- **Key 泄露风险**：仅存本机、设置页掩码、日志脱敏；二期升级 EncryptedFile。
- **配置变更**：ViewModel + Room 恢复会话；传输任务在全局 TransferManager，不随 Activity 销毁。
