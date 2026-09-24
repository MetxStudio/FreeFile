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

## 编译状态

* ✅ `./gradlew :app:compileDebugKotlin` — BUILD SUCCESSFUL

* ✅ `./gradlew :app:assembleDebug` — BUILD SUCCESSFUL

* ⏭ `./gradlew ktlintCheck` — 项目未配置 ktlint 插件，跳过

* ⏭ `./gradlew detekt` — 项目未配置 detekt 插件，跳过

## 验收测试

* 待真机/模拟器回归测试（首次安装 / 冷启动 / 热启动 / 中英文切换 / 横竖屏 / 低内存）

