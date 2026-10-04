# FreeFile2 任务进度跟踪

> 按用户任务清单顺序实现，每项完成后更新本文件。

## 任务列表

| # | 任务                         | 状态   | 说明                                                                                                         |
| - | -------------------------- | ---- | ---------------------------------------------------------------------------------------------------------- |
| 1 | 修复终端启动闪退（bug.txt）          | ✅ 完成 | setTextSize 初始化 mRenderer，启动日志已加，LaunchedEffect 延迟渲染                                                       |
| 2 | 修复文件快传三问题                  | ✅ 完成 | 自连过滤（localAddressSet）；明文 HTTP（usesCleartextTraffic + network\_security\_config）；关闭卡死（server.stop 移到 IO 线程） |
| 3 | 恢复六点菜单（显示方式/排序/过滤）         | ✅ 完成 | 整合到 MoreVert DropdownMenu；ViewMode 扩展 LARGE\_ICON；DataStore 持久化                                            |
| 4 | 文件选择扩充五按钮（全选/反选/区间/取消/操作）  | ✅ 完成 | SelectionBar + SelectionAction；区间锚点 selectionAnchor；ActionSheet AlertDialog                                |
| 5 | 完善APK工具托盘（接收Intent + 启动参数） | ✅ 完成 | apkPath 参数 + ApkLauncherPanel；Action/Category/Data/Extras/Flags 全配置；PackageManager 启动入口                    |
| 6 | 终端模块接收Intent并自动cd/ls       | ✅ 完成 | initialPaths 参数；会话就绪后自动 cd + ls -la；shellQuote 转义                                                          |
| 7 | 复用文件操作逻辑到操作面板              | ✅ 完成 | ActionSheet 已集成复制/剪切/删除/重命名/批量/属性/解压；压缩占位 TODO                                                             |
| 8 | 通用：编译验证 + Lint             | ✅ 完成 | assembleDebug BUILD SUCCESSFUL；ktlint/detekt 项目未配置                                                         |

## 详细说明

### 任务 1：终端启动闪退（已完成）

* **根因**：Termux v0.118.0 的 `TerminalView` 构造函数不初始化 `mRenderer`，直接 `attachSession()` 会触发 `updateSize()` 访问 null `mRenderer` 导致 NPE。

* **修复**：在 `TerminalScreen` 的 `AndroidView` factory 中先 `setTextSize(DEFAULT_FONT_SIZE)` 创建 `TerminalRenderer`，再 `attachSession`；并加 `sessionReady` 状态在就绪前不渲染 `AndroidView`。

* **日志**：`FreeFileApp.onCreate` / `MainActivity.onCreate` 加 `Log.d` 启动流程追踪。

### 任务 2：文件快传三问题（已完成）

* **自连**：`ShareDiscovery` 增加 `localDeviceName` 与 `localAddressSet`，过滤 mDNS 发现结果中的自身 IP/设备名。

* **明文 HTTP**：`AndroidManifest.xml` 加 `android:usesCleartextTraffic="true"` 与 `network_security_config.xml`，允许 localhost / 10.0.0.0 / 172.16.0.0 / 192.168.0.0 私网明文。

* **关闭卡死**：`TransferScreen` 与 `ShareDiscovery` 把 `server.stop()` / `jmdns.close()` 移到 `Dispatchers.IO` 协程，避免阻塞主线程。

### 任务 3：恢复六点菜单（已完成）

* **位置**：`FileListContent` 工具栏 `MoreVert` IconButton + `DropdownMenu`。

* **显示方式**：`ViewMode` 枚举扩展为 LIST/GRID/LARGE\_ICON；`AppSettings.viewMode` 通过 DataStore 持久化。

* **排序**：按名称/大小/修改时间/类型，升序/降序切换。

* **过滤**：显示所有/仅文件夹/仅文件/按扩展名。

### 任务 4：文件选择五按钮（已完成）

* **布局**：`SelectionBar` Composable，底部横向 `SpaceEvenly` 排列五个 `SelectionAction`。

* **全选**：`selectAll()` 选中所有可见项。

* **反选**：`invertSelection()` 切换所有项选中状态。

* **区间选择**：`enableRangeAnchor()` 记录起点；`selectRangeTo(path)` 选中起点到当前点击项之间所有项。

* **取消**：`clearSelection()` 退出选择模式。

* **操作**：弹出 `ActionSheet` AlertDialog，列出分享 / App Chooser / 内置模块（文本编辑器 / APK 工具 / 终端） + 文件操作（复制 / 剪切 / 删除 / 重命名 / 压缩）。

* **OpenSheetItem** 已加 `enabled` 参数，单选/多选场景下动态启用/禁用。

### 任务 5：APK 工具托盘（已完成）

* **入口**：`MainScreen` ActionSheet 中「用 APK 工具打开」→ 设置 `apkPendingPath` → 启动 `ToolTrayScreen(apkPath = ...)`。

* **双模式**：

  * 默认模式（apkPath == null）：原工具列表，解压 → 安装 → 启动 三步流程。

  * 启动器模式（apkPath != null）：解析 APK + 启动参数面板。

* **APK 解析**：`parseApkFile()` 用 `PackageManager.getPackageArchiveInfo(GET_ACTIVITIES | GET_INTENT_FILTERS)` 在 IO 线程解析，得到包名 / 入口 Activity / 版本 / 应用名 / 是否已安装；`sourceDir` 必须设置才能取 label。

* **入口 Activity 选择**：当前 compileSdk 33 不暴露 `actionNames`/`categoryNames`，回退策略：优先 exported Activity，否则取首个 Activity；用户可在 UI 中通过「自定义入口 Activity」开关覆盖。

* **启动参数 UI**：

  * Action（Switch 启用，默认 ACTION\_MAIN）

  * Category（多个用逗号分隔，默认 CATEGORY\_LAUNCHER）

  * Data (Uri)（可留空）

  * Extras（多行 `key=value`，支持 String/Int/Bool/Long，类型后缀 `key:i`/`key:l`/`key:b`）

  * 窗口 Flags 复选框：NEW\_TASK / CLEAR\_TOP / SINGLE\_TOP / NO\_HISTORY / CLEAR\_TASK / BROUGHT\_TO\_FRONT

* **启动逻辑**：`buildLaunchIntent()` 按优先级构建 Intent：自定义类名 → 已安装包的 `getLaunchIntentForPackage` → APK 自带入口；失败回退 ACTION\_VIEW+package，再回退 App Chooser。

* **辅助按钮**：「安装此 APK」用 FileProvider 暴露 APK + ACTION\_INSTALL\_PACKAGE；「用 App Chooser」用 `Intent.createChooser`。

* **编译**：`./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL。

### 任务 10：ZIP 压缩配置与结果定位（已完成）

* **密码**：支持可选密码，使用 ZIP4J AES-256 加密；留空则创建普通 ZIP。

* **压缩级别**：支持 STORE、FAST、NORMAL、MAXIMUM 四档选择。

* **覆盖确认**：目标路径已存在时，先弹出确认对话框，确认后才覆盖原文件。

* **自动定位**：压缩完成后刷新当前窗格，并自动跳转/选中生成的 ZIP 文件。

* **真机测试提示**：大文件、超大目录、磁盘空间不足、后台切换和密码 ZIP 解压仍需在 Android 真机上回归验证。

### 任务 11：压缩/解压多文件与错误修复（已完成）

* **入口位置**：文件选择后的「操作」对话框；「属性」下方提供解压和压缩操作。

* **多文件压缩**：选中多个本地文件/目录后统一创建一个 ZIP。

* **批量解压**：选中多个压缩包后输入一次目标目录，分别创建后台解压任务。

* **线程与进度**：压缩和解压均通过 `TransferManager` 的 IO 线程执行，每个任务独立显示实时进度并支持取消。

* **错误修复**：清理 `MainScreen.kt` 中重复、错位的压缩分支，并通过 Kotlin 编译验证。

* **编译**：`./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL。

### 任务 12：压缩配置增强、格式/分卷/密码完善与回调修复（已完成）

* **压缩完成回调修复**：后台任务完成回调改用 `FreeFileApp.mainScope`（应用级主线程 SupervisorJob），不再依赖 Compose 组合 `scope`，修复压缩完成后偶发 `StandaloneCoroutine was cancelled` 导致的刷新/提示失效。`toast` 与复制/移动/压缩/解压完成回调均已切换。

* **压缩配置 UI 重构**：`CompressDialog` 将压缩级别由 `FilterChip` 行改为 `ExposedDropdownMenu` 下拉框；新增压缩格式下拉框（ZIP / 7z / TAR / TAR.GZ / TAR.BZ2 / TAR.XZ），切换格式自动同步目标扩展名；新增分卷大小（MB）输入框，0 表示不分卷。

* **密码修复与验证**：密码字段仅在 ZIP 格式下启用；压缩完成后重新以密码打开归档并校验 `isEncrypted` 与可读首个条目，确保密码真正生效而非静默失败。

* **ZIP 分卷**：移除 `splitMb == 0` 限制，使用 zip4j `isSplitArchive` + `splitLength` 实现 ZIP 分卷；非 ZIP 格式下分卷输入禁用并校验拒绝。

* **解压密码支持**：`ArchiveExtractor.extract` 新增 `password` 参数，zip4j / commons-compress 7z / junrar 均透传密码；加密 ZIP 未提供密码时给出明确提示。`TransferManager.enqueueExtract` 与 `ConfirmExtractDialog` 同步增加密码输入。

* **多语言**：新增 `dlg_compress_format_label` / `dlg_compress_password_zip_only` / `dlg_compress_split_label` / `dlg_compress_split_hint` / `dlg_extract_password_label`，中英文同步更新；`dlg_compress_target_label` 改为通用「压缩包文件路径」。

* **限制**：密码与分卷仅 ZIP 支持；7z 压缩暂不支持加密（commons-compress SevenZOutputFile 未暴露加密写入）；tar 系列无加密能力。

### 任务 13：媒体查看器 MVP（第一阶段，已完成）

依据 `plan/1/plan-1.md` 第 1 阶段落地图片 / 视频 / 音频统一查看器。

* **依赖**：新增 `coil-compose:2.5.0`（图片，含 GIF/WebP）、`coil-video:2.5.0`（视频首帧）、`media3-exoplayer:1.2.1` + `media3-ui:1.2.1`（音视频播放）。

* **入口与模型**：`ui/media/MediaItem.kt` 按扩展名识别 IMAGE/VIDEO/AUDIO；`MediaViewerActivity` 接收文件路径列表 + 初始索引，独立任务栈。

* **主屏幕**：`MediaViewerScreen` 使用 `HorizontalPager` 混合滑动切换；顶栏显示标题与「当前/总数」；底栏提供分享 / 删除 / 详情。

* **图片页**：`SubcomposeAsyncImage` 加载，支持双指缩放（1x–5x）、双击放大/还原、拖拽平移，带加载圈与失败占位。

* **视频页**：`ExoPlayer` + `PlayerView`，使用系统控件的播放/暂停/进度/全屏。

* **音频页**：`ExoPlayer` 自定义控件，封面占位、标题、进度条、播放/暂停、上下首（联动 Pager 切换）。

* **操作与回传**：分享走 FileProvider；删除用 `File.delete`；详情弹 AlertDialog。删除路径经 `MediaViewerActivity → OpenWithActivity → MainScreen` 三级回传，触发左右窗格刷新。

* **OpenWithActivity 集成**：新增「媒体查看器」条目，仅媒体文件启用；`isMedia` 由 `MediaItem.isMedia` 判断。

* **多语言**：新增 `media_*` 与 `open_with_media_viewer` 共 13 条中英文资源。

* **编译**：`./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL。

* **限制（留待 P1/P2）**：音频未做后台 MediaSession / 通知栏；视频未做 PiP / 倍速 / 字幕；图片未做 EXIF / 设为壁纸；大图未用 SubsamplingScaleImageView 分块；暂不支持压缩包内/远程媒体直接预览（需经 FileOpener 缓存）。

### 任务 6：终端接收 Intent（已完成）

* **入口**：`MainScreen` ActionSheet 中「在终端中打开」→ 设置 `terminalPendingPaths` → 启动 `TerminalScreen(initialPaths = ...)`。

* **参数**：`TerminalScreen(initialPaths: List<String> = emptyList(), onBack)`。

* **自动 cd/ls**：会话就绪后 `LaunchedEffect(sessionReady, initialPaths)` 触发：

  * `resolveInitialPaths(paths)` 解析目标目录与文件名列表（取首个路径父目录；目录路径则直接作为目标）。

  * 单文件：`cd "DIR" && clear && ls -la "FILENAME"`。

  * 多文件：`cd "DIR" && clear && ls -la "f1" "f2" ...`。

  * 仅目录：`cd "DIR" && clear && ls -la`。

  * 路径无父目录（如 `/`）：直接 `ls -la <paths>`。

* **shell 转义**：`shellQuote()` 用单引号包裹含空格/特殊字符的路径，简单标识符不引用。

* **去重**：`pathsHandled` 状态防止每次重组重复执行命令。

* **提示**：Toast 显示 `terminal_cd_hint`（已切换到：xxx）。

* **空 Intent**：`initialPaths` 为空时正常启动到默认目录（filesDir）。

### 任务 7：文件操作复用（已完成）

* **共享逻辑**：`FileViewModel` 方法（`copyToClipboard` / `cutToClipboard` / `selectAll` / `invertSelection`）+ `PendingAction` sealed interface（Delete / Rename / BatchRename / Properties / Extract）作为共享 UseCase，SelectionBar 与 ActionSheet 共同调用。

* **ActionSheet 集成**：

  * 复制（ContentCopy）→ `activeVm.copyToClipboard(selected)`

  * 剪切（ContentCut）→ `activeVm.cutToClipboard(selected)`

  * 删除（Delete）→ `PendingAction.Delete(selected, activeVm)`

  * 重命名（DriveFileRenameOutline）→ `PendingAction.Rename`，`enabled = singleSel`

  * 批量改名（TextFields）→ `PendingAction.BatchRename`，`enabled = selected.isNotEmpty()`

  * 属性（Info）→ `PendingAction.Properties`，`enabled = singleSel`

  * 解压（Archive）→ `PendingAction.Extract`，`enabled = singleSel && ArchiveEngine.isArchiveFile(...)`

  * 压缩（FolderZip）→ 占位条目 `enabled = false` + TODO（待 ArchiveEngine 压缩能力落地）

* **OpenSheetItem**：已加 `enabled` 参数，禁用时图标/文字变灰、不可点击。

* **动态启用/禁用**：rename/properties 仅单选可用；extract 仅单选且为压缩包时可用。

### 任务 9：ZIP 压缩打包（已完成）

* **入口**：多选操作面板中的「压缩」，支持单个或多个本地文件/目录。

* **目标路径**：弹出对话框输入 ZIP 完整路径，默认使用当前目录和选中项名称。

* **后台执行**：通过 `TransferManager` 调度，显示文件/字节进度，支持取消和失败提示。

* **完成处理**：任务完成后刷新当前窗格并清除选择。

* **核心实现**：复用 `CompressionEngine` 的 ZIP4J 实现，保留压缩级别和密码能力供后续配置 UI 使用。

* **编译**：`./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL。

## 编译状态

* ✅ `./gradlew :app:compileDebugKotlin` — BUILD SUCCESSFUL

* ✅ `./gradlew :app:assembleDebug` — BUILD SUCCESSFUL

* ⏭ `./gradlew ktlintCheck` — 项目未配置 ktlint 插件，跳过

* ⏭ `./gradlew detekt` — 项目未配置 detekt 插件，跳过

## 验收测试

* 待真机/模拟器回归测试（首次安装 / 冷启动 / 热启动 / 中英文切换 / 横竖屏 / 低内存）

