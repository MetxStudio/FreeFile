# FreeFile

[中文](README.md) | [English](README_EN.md) | [MIT License](LICENSE.md)

<p align="center">
  <img src="icon.png" width="128" alt="FreeFile icon">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/platform-Android%207.0%2B-3DDC84" alt="Platform">
  <img src="https://img.shields.io/badge/minSdk-24-3DDC84" alt="minSdk 24">
  <img src="https://img.shields.io/badge/version-v1.0-blue" alt="Version v1.0">
  <img src="https://img.shields.io/badge/license-MIT-yellow" alt="MIT License">
</p>

> 全能文件大师：一款基于 Jetpack Compose 构建的 Android 双窗格文件管理器，集成本地 / Root / Shizuku 文件访问、网络存储、终端、脚本引擎、压缩解压、媒体与文档查看、文件加密以及 AI 文件管理 Agent。

## 简介

FreeFile 以 **双窗格 + 标签页** 为核心交互，面向需要高效率文件操作的进阶用户。通过统一的虚拟文件系统（VFS）抽象层，同一份文件操作逻辑可以无缝运行在本地分区存储、Root Shell、Shizuku、压缩包内部以及 SMB / FTP / WebDAV / S3 等远程位置之上。

项目使用 Kotlin 与 Jetpack Compose 编写，采用 MVI 架构。除文件管理主流程外，还提供终端、脚本、媒体查看、PDF 与网页查看、文件加密、局域网快传，以及可接入任意 OpenAI 兼容服务端的文件管理 Agent。

## 功能特性

### 文件管理

- **双窗格布局**：左右窗格独立浏览，支持一键交换、临时隐藏 / 显示右窗格，活动窗格由触摸交互决定
- **多标签页**：每个窗格可打开多个目录标签，会话持久化
- **完整文件操作**：新建（支持 `a/b/c` 多级目录）、复制、剪切、粘贴、重命名、批量重命名（序号 / 查找替换 / 前后缀 + 实时预览）
- **回收站**：删除先入回收站，可恢复或清空；也可永久删除
- **多选体系**：全选、反选、长按区间选择、操作面板
- **属性面板**：目录统计、MD5 / SHA-256 持久化校验值展示
- **导航辅助**：面包屑、路径跳转、书签、最近目录、最近文件，每个列表固定提供 `..` 返回上级
- **三种视图**：列表 / 网格 / 大图标；按名称 / 大小 / 类型 / 时间排序并切换升降序
- **细粒度过滤**：文件夹、图片、视频、音频、压缩包、文档、代码、APK 等分类 + 隐藏文件开关

### 权限与访问引擎

- **自动引擎选择**（[EngineSelector](app/src/main/java/com/freefile/vfs/EngineSelector.kt)）：按路径自动探测，优先级 **Root > Shizuku > 本地分区存储**，并记忆「路径前缀 → 引擎」映射
- 支持手动切换：自动 / Root 优先 / Shizuku 优先 / 仅本地
- Shizuku 授权状态与所有文件访问（`MANAGE_EXTERNAL_STORAGE`）授权集中在设置页管理
- Root / Shizuku 均为可选增强，不影响本地文件管理

### 搜索

- 可配置递归深度（仅当前目录 / 指定层数 / 无限递归）
- 通配符（`*` `?`）、正则表达式、完全匹配、区分大小写
- 按文件大小、修改时间、文件内容过滤
- **搜索索引缓存**：后台建索引实现秒搜，可在设置中随时开关或重建

### 压缩与解压

- 支持格式：`zip`、`jar`、`apk`、`7z`、`tar`、`tar.gz`、`tgz`、`tar.bz2`、`tar.xz`、`rar4`、`rar5`、`gz`、`bz2`、`xz`
- 加密 ZIP 解压支持；压缩支持 ZIP 密码、压缩级别与分卷（仅 ZIP）
- **压缩包内直接浏览**：通过 `xxx.zip#/internal/path` 路径语法映射压缩包内部目录，无需解压即可操作
- 复制 / 移动 / 解压任务提供冲突策略：覆盖 / 跳过 / 保留两者，支持批量应用

### 网络与远程文件系统

- **WebDAV / FTP / SMB2/3 / S3**（兼容阿里 OSS 等 S3 协议端点），统一 `remote://` 路径语法
- 网络位置可保存、编辑，支持 SSL、域、Bucket、自定义 Endpoint 等配置
- **局域网快传**：基于 NanoHTTPD + mDNS（jmdns）自动发现附近设备，免流量互传文件，支持接收记录

### 内置终端

- 基于 Termux terminal-emulator 的**真 PTY + VT100** 终端，无需 NDK 编译
- 多标签会话，支持普通 / Root 模式及自定义解释器路径（如 `/system/bin/bash`）
- 接收外部 Intent 传入路径，打开后自动 `cd` 到目标目录并列出选中文件
- 独立任务栈，可与文件界面自由切换

### 脚本引擎

- 内置 **Lua** 引擎（LuaJ，纯 Java 实现），并支持调用设备上的 **Python** 解释器（如 Termux 环境）
- 提供 `freefile.list / read / write / copy / delete / mkdir / rename / exists / log` 等文件 API 绑定
- 内置常用脚本：列目录、存储信息、查找大文件、批量重命名预览、目录大小统计、删除空目录等

### 编辑器与查看器

- **文本编辑器**：搜索 / 替换 / 全部替换、正则、全词匹配、区分大小写、行号、跳转指定行、字号调节、多编码、大文件流畅模式（超过 1MB 截断加载）
- **富文本编辑器**：基于 richeditor-compose，支持 HTML / Markdown / 纯文本编辑与格式互转、超链接插入
- **媒体查看器**：图片 / 视频 / 音频统一浏览
  - 图片：双指缩放、双击放大、拖拽平移、旋转、设为壁纸
  - 视频：ExoPlayer 播放、进度与全屏、0.5x–2x 倍速、单集循环、音轨 / 字幕切换、画中画（PiP）
  - 音频：自定义播放控件、上下首切换、艺术家 / 专辑信息
  - 详情：分辨率、时长、EXIF 拍摄时间、设备、位置、艺术家、专辑等
- **PDF 查看器**：垂直滚动、双击缩放、放大 / 缩小、页码跳转、分享、文件详情
- **网页查看器**：多标签浏览、侧拉栏管理标签、地址栏搜索、前进 / 后退 / 刷新、网页下载与实时进度、内置浏览器设置（JS、存储、缩放、字体、UA、代理、混合内容等）、证书错误确认、eruda 调试控制台

### 文件加密

- 独立于压缩包的 **AES-256-GCM** 文件加密（`.cry` 格式），PBKDF2-HMAC-SHA256 密钥派生（200,000 次迭代、随机盐）
- 加密 / 解密入口集成在「打开方式」，支持密码强度提示、确认密码、加密后删除源文件
- 密码错误或文件损坏时不会产出明文
- 注意：密码遗失后无法恢复文件

### 工具托盘与 APK 工具

- **工具托盘**：内置 APK 工具（Acode、APKToolM），支持解压 → 安装 → 启动三步流程，可将当前文件直接传递给工具处理
- **APK 启动器**：解析未安装 APK 的包名 / 入口 Activity / 版本，可完整自定义启动 Intent：Action、Category、Data、Extras（String/Int/Bool/Long）、窗口 Flags
- 支持从远程工具目录下载并更新工具；工具索引格式见 [example/tools.json](example/tools.json)

### 文件管理 Agent（AI）

- **自带服务端点**：支持 OpenAI 兼容协议，内置 OpenAI、DeepSeek、Moonshot Kimi、通义千问（DashScope 兼容模式）预设，也可自定义 Base URL / 模型
- **工具调用**：30+ 个本地文件工具，覆盖搜索、浏览、信息、回收站、新建、重命名、移动、复制、删除到回收站、恢复、压缩、解压、读写文本、存储分析、重复文件扫描、批量重命名、标签、分享、媒体库查询等
- **MCP 支持**：可配置外部 MCP 服务器（Streamable HTTP / JSON-RPC 2.0），把远端工具桥接给模型
- **写操作安全**：默认所有写操作先展示预览确认卡片，支持勾选、冲突策略、批量阈值与危险提示，可在设置中为指定工具开启「始终允许」；执行后可一键撤销
- **操作日志**：本机记录写操作与撤销状态，可查看、清空
- **隐私控制**：默认只发送文件元数据；读取文件正文需单独授权；不内置、不中转任何 API Key
- 三种模式（自动 / 文件 / 通用），支持流式回复、取消、工具轮次上限，侧拉栏可隐藏入口

### 文档中心与多语言

- **Gallery 文档中心**：内置用户协议、隐私政策、帮助文档与关于页面，支持中英文切换
- **多语言**：简体中文 / English，应用内切换、即时生效（Per-app language）

### 其他

- **统一打开方式**（[OpenWithActivity](app/src/main/java/com/freefile/ui/OpenWithActivity.kt)）：分享、系统应用打开、文本编辑器、工具托盘、终端、压缩 / 解压、加密 / 解密、媒体 / PDF / 网页查看，结果回传主界面做上下文处理
- 文件大小计算默认关闭以加快大目录浏览，可在设置中开启
- 传输 / 压缩 / 解压任务统一由应用级 `TransferManager` 协程调度，支持实时进度与取消

## 技术栈

| 类别 | 技术选型 |
| --- | --- |
| 语言 / UI | Kotlin 1.9.24、Jetpack Compose、Material3 |
| 架构 | MVI + ViewModel、StateFlow、协程 |
| 数据持久化 | Room（标签 / 书签 / 历史 / 回收站 / Agent 会话）、DataStore（偏好设置） |
| 导航 | Navigation Compose、独立 Activity 任务栈 |
| 高权限 | Shizuku API 13.1.5、Root Shell |
| 压缩 | zip4j、Apache Commons Compress、XZ、JunRAR |
| 网络 | OkHttp、commons-net（FTP）、SMBJ（SMB2/3）、NanoHTTPD、jmdns |
| 终端 | Termux terminal-emulator / terminal-view v0.118 |
| 脚本 | LuaJ 3.0.1 |
| 编辑器 | richeditor-compose |
| 媒体 | Coil（GIF / WebP / 视频首帧）、Media3 ExoPlayer、ExifInterface |
| 文档 / 网页 | AndroidPdfViewer、系统 WebView、内置 eruda 控制台 |
| AI Agent | OkHttp SSE、OpenAI 兼容 Chat Completions、MCP（JSON-RPC 2.0）、org.json |
| 加密 | `javax.crypto` AES-256-GCM / PBKDF2-HMAC-SHA256 |
| 测试 | JUnit4、AndroidX Test |

## 系统要求

| 项目 | 要求 |
| --- | --- |
| minSdk | 24（Android 7.0） |
| targetSdk | 33 |
| compileSdk | 34 |
| JDK | 17 |
| Gradle | 8.0（Wrapper 已自带） |
| Android Gradle Plugin | 8.1.2 |
| NDK / CMake | 不需要 |

Root 与 Shizuku 为可选增强，非必需。

## 编译构建

使用 Android Studio 打开项目，或在命令行执行：

```bash
# Windows
gradlew.bat :app:assembleDebug

# macOS / Linux
./gradlew :app:assembleDebug
```

产物路径：`app/build/outputs/apk/debug/app-debug.apk`

运行本地单元测试：

```bash
gradlew.bat :app:testDebugUnitTest
```

说明：

- 终端模拟器、PDF 查看器等以预构建 AAR / Maven 依赖方式引入，**无需本地 NDK / CMake 环境**
- 依赖 JitPack 仓库拉取 Termux 终端组件，首次构建需要可访问 `jitpack.io`
- `release` 构建当前未配置签名，发布前请自行在 `app/build.gradle.kts` 中添加 `signingConfig`
- 首次构建请确认 `local.properties` 中的 Android SDK 路径正确，或交由 Android Studio 自动生成

## 首次使用

1. 启动后按提示授予「所有文件访问」权限（Android 11+ 必需）
2. 如需访问系统目录，可选择安装并授权 Shizuku，或在已 Root 设备上使用 Root 模式
3. 在设置中切换语言、搜索深度、是否计算文件大小、是否启用索引缓存等
4. 如需 AI 功能，在 Agent 设置中填写服务端点、模型与 API Key，并先执行「测试连接」
5. 如需访问 NAS / 网盘，在侧拉栏「网络位置」中添加 WebDAV / FTP / SMB / S3 位置

### 自定义工具目录

如需使用「第三方工具下载」功能，请将 [DownloadToolActivity](app/src/main/java/com/freefile/ui/DownloadToolActivity.kt) 中的常量替换为你自己的工具索引地址：

```kotlin
private const val TOOL_CATALOG_URL = "https://your-domain.example/json/tools.json"
```

索引 JSON 格式见 [example/tools.json](example/tools.json)。其中 `downloadUrl` 指向 APK 下载地址；示例中的 `https://thisisexample.com/example` 仅为占位。

## 项目结构

```text
app/src/main/java/com/freefile/
├── FreeFileApp.kt / MainActivity.kt   # 应用入口
├── core/            # MVI 基类、权限管理
├── data/            # Room 数据库、仓库、DataStore 设置、外部工具索引
├── operations/      # 文件操作、传输管理、解压缩、冲突策略
├── vfs/             # 虚拟文件系统：Local / Shell / Archive / Remote 引擎与选择器
│   └── remote/      # WebDAV / FTP / SMB / S3 客户端
├── search/          # 搜索引擎与索引
├── terminal/        # Termux 终端会话管理
├── shell/           # Root / Shizuku Shell 提供者
├── script/          # Lua / Python 脚本引擎
├── share/           # 局域网快传：HTTP 服务与 mDNS 发现
├── plugin/          # 插件管理
├── security/        # 文件加解密（AES-256-GCM / .cry）
├── tools/           # Agent、媒体、PDF、网页、富文本、压缩引擎等
└── ui/              # Compose 界面：双窗格、对话框、设置、终端、各独立 Activity
```

## 权限说明

| 权限 | 用途 |
| --- | --- |
| `MANAGE_EXTERNAL_STORAGE` | 核心文件浏览能力，需用户在系统设置中手动授予 |
| `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` | Android 9 及以下的传统存储访问 |
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO` | Android 13+ 细分媒体权限，供媒体浏览与查询 |
| `QUERY_ALL_PACKAGES` / `REQUEST_INSTALL_PACKAGES` | 工具托盘解析、安装 APK |
| Shizuku 权限 | Root 不可用时的高权限文件访问方案 |
| `INTERNET` / `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` / `CHANGE_WIFI_MULTICAST_STATE` / `NEARBY_WIFI_DEVICES` | 远程文件系统与局域网快传（含 mDNS 设备发现） |
| `FOREGROUND_SERVICE` / `POST_NOTIFICATIONS` | 长任务保活与通知（清单预留，当前版本主要以前台协程执行） |

## 隐私与安全

- **无遥测**：应用不内置统计 SDK，不上传使用数据
- **AI 数据边界**：对话发送到你自行配置的端点。默认只发送文件名、路径、大小等元数据；读取并发送文件正文需在 Agent 设置中单独授权。API Key 仅保存在本机
- **API Key 存储**：当前使用 DataStore 本机存储（非系统加密容器），请勿在共享设备上保存敏感 Key；后续版本可升级为加密存储
- **局域网快传**：默认使用私网明文 HTTP，请仅在可信网络开启
- **Root / Shizuku**：高权限操作可能修改系统文件并导致设备异常，请谨慎使用
- **文件加密**：`.cry` 文件一旦遗忘密码将无法恢复
- **工具托盘示例 APK**：`assets/apk/` 中的 Acode、APKToolM 为示例工具，版权归各自作者所有；发布自定义版本时请替换或移除，并遵守其许可协议
- **第三方组件**：项目依赖的部分组件（如 Termux terminal-emulator）采用 GPL 系列许可证，分发时请遵循对应上游许可；`app/libs/` 下的第三方 AAR 请确认授权后再分发

## 已知限制与后续计划

- 压缩包加密与分卷仅 ZIP 支持；7z 压缩暂不支持加密，TAR 系列无加密能力
- Agent 的写操作当前主要面向本地文件系统；远程位置与压缩包内部路径以只读为主
- 同名冲突提供覆盖 / 跳过 / 保留两者策略；本应用暂无文件版本历史，覆盖后原文件不可恢复
- 媒体播放未提供后台 MediaSession / 通知栏控制；视频已支持画中画
- 长任务以前台协程为主，尚未实现前台服务与断点续传
- 单元测试覆盖有限，发布前仍需真机回归（首次安装、冷启动、中英文切换、横竖屏、低内存、大目录）
- `release` 构建尚未配置签名；开源仓库中也未配置 CI

后续计划：

- 完善远程位置与压缩包内的写操作
- 增加更多查看器与工具能力
- 补充自动化测试与持续集成
- 持续优化大目录、超大文件与低内存场景

## 参与贡献

欢迎提交 Issue 与 Pull Request。

- 提交问题前请先搜索现有 Issue，并附上设备型号、系统版本、复现步骤与相关日志
- 提交代码前请先执行 `gradlew.bat :app:assembleDebug` 与 `gradlew.bat :app:testDebugUnitTest`
- 新增用户可见文案请同步更新 `values/strings.xml` 与 `values-en/strings.xml`
- 请保持现有包边界与 MVI 结构，避免在 Compose 组合作用域启动长任务
- 请勿提交真实 API Key、签名文件、个人路径或含敏感信息的日志

## 第三方组件与许可

本项目原创代码采用 [MIT License](LICENSE.md)。第三方依赖、示例 APK 与 AAR 分别遵循其各自许可证，主要包括：

- Kotlin / AndroidX / Jetpack Compose / Material3
- Shizuku（Rikka）
- zip4j、Apache Commons Compress、XZ、JunRAR
- OkHttp、commons-net、SMBJ、NanoHTTPD、jmdns
- Termux terminal-emulator / terminal-view
- LuaJ、richeditor-compose
- Coil、Media3 ExoPlayer、AndroidPdfViewer
- eruda（网页调试控制台）

具体版本与许可证请以各依赖的上游仓库与发行说明为准。

## 免责声明

- 本应用提供 Root / Shizuku 高权限文件操作能力，请在充分了解风险的前提下使用；删除、移动系统文件可能导致设备故障
- 局域网快传默认仅允许私网明文传输，请勿在公共网络下开启
- AI Agent 的文件事实来自本机工具结果，但模型输出仍可能存在错误，请在执行写操作前核对预览
- 应用内工具仅用于学习研究、个人文件处理及对自有应用的分析，请遵守当地法律法规及目标软件许可协议
