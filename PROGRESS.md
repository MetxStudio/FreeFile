# FreeFile2 开发进度

> 最后更新：2026-10-31  
> 当前版本：v1.0  
> 当前阶段：核心功能完成，准备开源  
> 说明：本文档记录内部开发进度。点九编辑器、VirtualBox、DOCX 查看器属于内部预留能力，暂不对外公布。

## 当前状态

| 项目 | 状态 |
| --- | --- |
| 代码规模 | 129 个 Kotlin 文件，约 32,474 行（仅统计 `app/src/main/java`） |
| 多语言 | 中文 889 条、英文 888 条字符串资源 |
| 单元测试 | 6 个测试文件（Agent 路径校验、参数与结果、确认门、逆操作） |
| 编译 | `:app:compileDebugKotlin` BUILD SUCCESSFUL（2026-10-31 验证） |
| 打包 | `:app:assembleDebug` BUILD SUCCESSFUL（2026-10-31 验证，产物 `app-debug.apk`） |
| 静态检查 | 项目未配置 ktlint / detekt |
| 真机回归 | 待执行 |

已交付的用户功能包括：双窗格文件管理、Root / Shizuku / 本地引擎、搜索与索引、压缩解压、WebDAV / FTP / SMB / S3、局域网快传、终端、脚本、文本与富文本编辑、媒体查看器、PDF 查看器、网页查看器、文件加密、工具托盘与 APK 启动器、AI 文件管理 Agent、Gallery 文档中心与中英文切换。

内部预留、暂不对外公布的能力：点九编辑器、VirtualBox 虚拟容器、DOCX 查看器。

## 任务总览

| # | 任务 | 状态 | 完成时间 | 说明 |
| --- | --- | --- | --- | --- |
| 1 | 修复终端启动闪退 | 完成 | 2026-09 | 先初始化 `mRenderer` 再 `attachSession`，增加就绪状态 |
| 2 | 修复文件快传三问题 | 完成 | 2026-09 | 过滤自连、私网明文 HTTP、服务关闭移到 IO 线程 |
| 3 | 恢复显示方式 / 排序 / 过滤菜单 | 完成 | 2026-09 | 整合到 MoreVert 菜单，DataStore 持久化 |
| 4 | 文件选择五按钮 | 完成 | 2026-09 | 全选 / 反选 / 区间 / 取消 / 操作面板 |
| 5 | APK 工具托盘与启动参数 | 完成 | 2026-09 | 接收 Intent、解析 APK、自定义启动 Intent |
| 6 | 终端接收 Intent 并自动 cd / ls | 完成 | 2026-09 | 多路径解析与 shell 转义 |
| 7 | 复用文件操作逻辑到操作面板 | 完成 | 2026-09 | `PendingAction` 共享删除 / 重命名 / 属性 / 解压等 |
| 8 | 编译验证与静态检查 | 完成 | 2026-09 | assembleDebug 成功；ktlint / detekt 未配置 |
| 9 | ZIP 压缩打包 | 完成 | 2026-10 | 多选文件 / 目录压缩，后台进度与取消 |
| 10 | ZIP 压缩配置与结果定位 | 完成 | 2026-10 | 密码、压缩级别、覆盖确认、完成后定位产物 |
| 11 | 多文件压缩 / 批量解压与错误修复 | 完成 | 2026-10 | TransferManager 统一调度，独立进度 |
| 12 | 压缩格式 / 分卷 / 密码完善与回调修复 | 完成 | 2026-10 | 格式下拉、ZIP 分卷、解压密码、应用级 mainScope 回调 |
| 13 | 媒体查看器 MVP | 完成 | 2026-10-04 | 图片 / 视频 / 音频统一 Pager 浏览 |
| 14 | 媒体查看器增强 | 完成 | 2026-10-11 | 画中画、倍速、循环、音轨 / 字幕、EXIF、壁纸、旋转 |
| 15 | 文件加密 / 解密 | 完成 | 2026-10-05 | AES-256-GCM `.cry`，接入打开方式 |
| 16 | PDF 查看器 | 完成 | 2026-10-10 | 缩放、页码跳转、分享、详情 |
| 17 | 网页查看器与内置文档桥 | 完成 | 2026-10-25 | 多标签、下载、浏览器设置、eruda；DOCX 页面与读取桥就绪 |
| 18 | 文件管理 Agent | 完成 | 2026-10-30 | LLM 工具循环、30+ 工具、写操作确认与撤销、MCP |
| 19 | Gallery 文档中心 | 完成 | 2026-10-25 | 用户协议、隐私政策、帮助、关于，中英文 |
| 20 | 开源文档整理 | 完成 | 2026-10-31 | 更新 README / README_EN / PROGRESS |

## 详细记录

### 一、基础与文件管理（任务 1–8）

- **终端闪退**：Termux v0.118 的 `TerminalView` 构造函数不初始化 `mRenderer`，直接 `attachSession()` 会在 `updateSize()` 访问空对象。修复方式是在 `AndroidView` factory 中先 `setTextSize()` 创建渲染器，并用 `sessionReady` 状态延迟渲染。
- **文件快传**：`ShareDiscovery` 增加本机设备名与地址集合以过滤自连；Manifest 增加 `usesCleartextTraffic` 与私网 `network_security_config`；`server.stop()` / `jmdns.close()` 移到 `Dispatchers.IO`，避免关闭卡死。
- **显示菜单**：`ViewMode` 扩展为 LIST / GRID / LARGE_ICON，通过 DataStore 持久化；按名称 / 大小 / 类型 / 时间排序，支持升降序与分类过滤。
- **多选操作**：`SelectionBar` 提供全选、反选、区间选择、取消与操作面板；`selectionAnchor` 记录区间起点；`ActionSheet` 集成文件操作与内置工具。
- **APK 工具托盘**：`ToolTrayScreen` 支持默认工具列表与 APK 启动器双模式；`PackageManager.getPackageArchiveInfo` 解析未安装 APK；支持 Action、Category、Data、Extras 与窗口 Flags。
- **终端 Intent**：`TerminalScreen(initialPaths)` 在会话就绪后自动 `cd` 并 `ls -la`，使用 `shellQuote()` 转义路径，`pathsHandled` 防止重复执行。
- **文件操作复用**：`FileViewModel` 的复制 / 剪切 / 选择方法与 `PendingAction` 密封接口作为共享逻辑，供 SelectionBar 与 ActionSheet 调用。
- **编译验证**：`compileDebugKotlin` 与 `assembleDebug` 均通过；项目未配置 ktlint / detekt。

### 二、压缩与解压（任务 9–12）

- **ZIP 压缩**：复用 `CompressionEngine` 的 ZIP4J 实现，通过 `TransferManager` 后台执行，显示文件 / 字节进度，支持取消与失败提示。
- **压缩配置**：支持密码（ZIP AES-256）、压缩级别（STORE / FAST / NORMAL / MAXIMUM）、压缩格式（ZIP / 7z / TAR / TAR.GZ / TAR.BZ2 / TAR.XZ）与 ZIP 分卷。
- **结果处理**：目标已存在时先确认覆盖；完成后刷新窗格并自动定位生成的压缩包。
- **批量任务**：多文件统一压缩；多压缩包输入一次目标目录后分别创建后台解压任务。
- **密码与校验**：压缩后重新打开归档校验 `isEncrypted` 与首个条目可读；解压密码透传给 zip4j / commons-compress 7z / JunRAR。
- **回调修复**：后台完成回调改用 `FreeFileApp.mainScope`，修复 `StandaloneCoroutine was cancelled` 导致刷新与提示失效。
- **限制**：密码与分卷仅 ZIP 支持；7z 压缩暂不支持加密；TAR 系列无加密能力。

### 三、媒体查看器（任务 13–14）

- **入口**：`MediaViewerActivity` 接收文件路径列表与初始索引，独立任务栈；`MediaItem` 按扩展名识别图片 / 视频 / 音频。
- **图片**：Coil 加载（GIF / WebP），双指缩放、双击放大 / 还原、拖拽平移、旋转、设为壁纸、加载失败重试。
- **视频**：ExoPlayer + 自定义控件，0.5x / 0.75x / 1x / 1.25x / 1.5x / 2x 倍速，单集循环，音轨 / 字幕切换，画中画（含播放 / 暂停 RemoteAction）。
- **音频**：自定义播控、封面占位、进度条、上下首联动 Pager、艺术家与专辑信息。
- **详情**：分辨率、时长、EXIF 拍摄时间、设备、位置、艺术家、专辑。
- **操作回传**：分享、删除、详情；删除路径经 `MediaViewerActivity → OpenWithActivity → MainScreen` 回传并刷新窗格。
- **限制**：未实现后台 MediaSession / 通知栏控制；暂不支持压缩包内 / 远程媒体直接预览。

### 四、文件加密（任务 15）

- **算法**：AES-256-GCM（128 位认证标签），PBKDF2-HMAC-SHA256 派生密钥，200,000 次迭代，16 字节随机盐，12 字节随机 nonce。
- **格式**：`.cry` 文件包含 Magic `CRY1`、版本、盐、nonce、原文件名、原大小与密文；对外只暴露单个文件的读取接口。
- **交互**：加密 / 解密接入 Open With，支持密码与确认密码、强度提示、加密后删除源文件、输出存在检查。
- **错误处理**：密码错误或文件损坏时不产出明文；源文件删除失败会明确提示。
- **风险**：密码遗失无法恢复；当前未接入 Agent 的 `encrypt` / `decrypt` 工具（仍为占位）。

### 五、查看器（任务 16–17）

- **PDF**：`AndroidPdfViewer` 渲染，垂直滚动、双击缩放、放大 / 缩小、页码跳转、当前页 / 总页数、分享、详情；支持外部 `content://` / `file://` 打开。
- **网页查看器**：多标签、侧拉栏管理、地址栏搜索、前进 / 后退 / 刷新、下载中心（HTTP 与 blob / data URL 共用进度）、完整浏览器设置（JS、DOM 存储、数据库、缩放、宽视口、文件访问、缓存、字体、UA、代理、混合内容）、证书错误确认。
- **eruda 控制台**：通过注入 script 标签并由 `shouldInterceptRequest` 返回本地 `assets/eruda.min.js`，避免大段内联脚本在部分 WebView 被截断。
- **内置文档桥**：`LocalWebServer` + `DocxBridge` 以 256KB 分块读取 DOCX，交给内置 mammoth 页面解析；该入口当前隐藏，暂不对外公布。

### 六、文件管理 Agent（任务 18）

- **架构**：`com.freefile.tools.ai` 包内独立实现，不新增 Gradle 模块；独立 Room 数据库 `freefile_agent.db` 与 DataStore `freefile_agent_settings`，不改动主设置与主数据库。
- **LLM**：OpenAI 兼容 Chat Completions，OkHttp SSE 流式；预设 OpenAI、DeepSeek、Moonshot Kimi、通义千问兼容模式，自定义 Base URL / 模型 / Key；支持测试连接、关闭流式降级、401 / 429 / 5xx 明确提示。
- **工具**：30+ 个工具，覆盖只读（搜索、列目录、文件信息、回收站、读文本、打开方式、插件列表）、写操作（新建、重命名、移动、复制、删除到回收站、恢复）、压缩 / 解压 / 写文本、插件桥接、计算器、时间、打开 URL、存储根、目录树、目录大小、存储分析、重复文件扫描、权限检查、批量读文件、内容搜索、追加 / 替换、标签、批量重命名、分享、媒体库查询、删除空目录。
- **写操作安全**：`ConfirmationGate` 拦截写工具，生成预览卡片；支持勾选、全选 / 反选 / 只处理前 N 个、冲突策略、批量阈值、特权路径提示、覆盖红字警示；批准令牌一次性且绑定参数哈希。
- **撤销与日志**：`UndoManager` 生成逆操作并记录逐项失败；`AgentRepository` 保存会话、工具调用与操作日志；结果卡片提供「撤销本次操作」与「定位到目录」。
- **路径安全**：`PathGuard` 规范化路径、拦截 `..` 穿越、限制敏感目录；远程与压缩包路径以只读为主；写操作必须经确认。
- **MCP**：`McpClient` 支持 Streamable HTTP / JSON-RPC 2.0，`initialize → tools/list → tools/call`，兼容 JSON 与 SSE 响应；设置页可增删、启停、测试 MCP 服务器。
- **隐私**：默认只发送元数据；`read_text_file` 等正文读取受「允许发送文件内容」开关控制（默认关闭）；Key 仅存本机，不进日志。
- **UI**：侧拉栏入口、Agent 聊天页、工具调用卡、确认卡、进度卡、结果 / 撤销卡、思考折叠、Markdown 轻量渲染、操作日志页、首次引导页。
- **单元测试**：`PathGuardLexicalTest`、`ToolArgsResultsTest`、`ConfirmationGateTest`、`InverseOpsTest`。

### 七、Gallery 文档中心（任务 19）

- 侧拉栏「Gallery」入口，集中展示用户协议、隐私政策、帮助与关于。
- 文档以 `app/src/main/assets/docs-ch` 与 `docs-en` 内置 HTML 提供，支持中英文切换，离线可读。
- 首次启动展示用户协议确认；设置页保留「关于」入口。

### 八、开源整理（任务 20）

- 重写 [README.md](README.md) 与 [README_EN.md](README_EN.md)：补充 Agent、查看器、加密、隐私与安全、贡献指南、第三方许可与已知限制。
- 重写本文件，统一任务编号与状态，补充 2026-10 之后的模块进展。
- 明确内部预留能力：点九编辑器、VirtualBox、DOCX 查看器暂不对外公布。
- 移除 README 中已不准确的「前台服务保障传输」「VirtualBox 可用」等描述，改为与当前实现一致的说明。

## 内部预留（暂不对外公布）

| 模块 | 当前状态 | 后续计划 |
| --- | --- | --- |
| 点九编辑器 `tools/dotnine` | 代码已实现：引导线绘制 / 擦除、缩放、撤销 / 重做、拉伸预览、导出 `.9.png` | 清理入口与 Manifest 暴露面后再对外；当前 Activity 仍为 `exported=true` 且带 PNG VIEW 过滤器，正式发布前需改为内部入口或移除过滤器 |
| VirtualBox `tools/virtualbox` | 仅有骨架：`VirtualBoxApp`、`PluginManagerHelper` 与占位 Activity；`BlackBoxCore` 初始化与 Provider 注册均被注释 | 需补充 Bcore 与 black-reflection AAR、确认授权与兼容性后再启用 |
| DOCX 查看器 `tools/web` + `assets/web-plugin/docx-viewer` | 页面、mammoth 离线库、`DocxBridge` 分块读取均已就绪；Open With 入口已注释 | 完成多机型验证、下载 / 大文件与异常处理后再开放 |

## 编译与测试状态

| 检查项 | 状态 |
| --- | --- |
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL（2026-10-31） |
| `:app:assembleDebug` | BUILD SUCCESSFUL（2026-10-31，46 个任务，13 个执行） |
| `:app:testDebugUnitTest` | BUILD SUCCESSFUL（2026-10-31） |
| `ktlintCheck` / `detekt` | 未配置插件，跳过 |
| 真机 / 模拟器回归 | 待执行（首次安装、冷启动、热启动、中英文切换、横竖屏、低内存、大目录） |

## 开源前检查清单

### P0（发布前必须处理）

- [ ] 从 Git 索引移除 `.vs/` 目录并加入 `.gitignore`；`.idea/` 目前部分文件被跟踪，建议整体忽略
- [ ] 确认 `app/src/main/assets/apk/acode.apk`、`apktoolm.apk` 的再分发许可；若不允许，改为首次使用下载或移除示例 APK
- [ ] 确认 `app/libs/Bcore-release.aar` 的授权；当前公开构建并不依赖它，可在开源仓库中移除
- [ ] 为 `release` 构建补充签名配置，或明确说明发布流程
- [ ] 在 Manifest 中收口内部预留 Activity（尤其是点九编辑器的 `exported=true` 与 VIEW 过滤器）
- [ ] 检查仓库与提交历史中是否存在个人路径、密钥、签名文件或敏感日志

### P1（开源后优先迭代）

- [ ] 建立 GitHub Actions：编译、单元测试、Lint
- [ ] 增加 Agent 工具与文件操作的单元测试，补齐压缩 / 解压 / 加密测试
- [ ] Agent 支持远程位置与压缩包内的写操作
- [ ] 媒体播放增加后台 MediaSession 与通知栏控制
- [ ] 长任务引入前台服务与断点续传
- [ ] 7z 加密压缩（依赖上游库能力）
- [ ] 完成 DOCX 查看器、点九编辑器、VirtualBox 的公开条件评估

### P2（体验完善）

- [ ] README 增加截图与功能演示 GIF
- [ ] 补充贡献指南、Issue 模板与 PR 模板
- [ ] 梳理第三方依赖许可证清单（含 Termux GPL 系列）
- [ ] 大目录、超大文件、低内存场景专项优化

## 验收测试

- 首次安装与冷启动：授予所有文件访问后能正常浏览 `/storage/emulated/0`
- Root / Shizuku 模式：切换引擎后系统目录可读，未授权时给出引导
- 文件操作：复制 / 移动 / 删除 / 重命名 / 批量重命名 / 回收站恢复
- 压缩：ZIP 普通、ZIP 密码、ZIP 分卷、7z / TAR 系列；解压加密 ZIP 与多压缩包批量解压
- 网络：WebDAV / FTP / SMB / S3 位置新增、浏览、上传 / 下载
- 快传：两台设备发现、发送、接收、关闭不卡死
- 终端：普通 / Root 会话、Intent 路径自动 `cd`、多标签
- 查看器：图片缩放 / 旋转 / 壁纸；视频倍速 / 字幕 / 画中画；音频上下首；PDF 跳页；网页多标签与下载
- 加密：加密后解密还原、错误密码不产出明文、删除源文件选项
- Agent：只读查询不虚构、写操作预览确认、撤销、回收站恢复、MCP 工具调用、未配置 Key 引导
- 多语言：中英文即时切换；Gallery 文档语言切换
- 异常：断网、低存储、超大文件、后台切换、旋转屏状态恢复

## 版本记录

| 版本 | 日期 | 主要内容 |
| --- | --- | --- |
| v1.0 | 2026-10 | 双窗格文件管理、权限引擎、搜索、压缩、网络存储、终端、脚本、编辑器、媒体 / PDF / 网页查看、文件加密、工具托盘、AI Agent、Gallery 与中英文 |
