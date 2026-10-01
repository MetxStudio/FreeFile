# FreeFile

[中文](README.md) | [English](README_EN.md) | [许可证](LICENSE.md)

> 全能文件大师 —— 一款基于 Jetpack Compose 构建的 Android 双窗格文件管理器，集本地 / Root / Shizuku 文件访问、网络存储、终端、脚本引擎、压缩解压与逆向工具于一体。

## 简介

FreeFile 以 **双窗格 + 标签页** 为核心交互，面向需要高效率文件操作的进阶用户。通过统一的虚拟文件系统（VFS）抽象层，同一份文件操作逻辑可以无缝运行在本地分区存储、Root Shell、Shizuku、压缩包内部以及 SMB / FTP / WebDAV / S3 等远程位置之上。

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
- Shizuku 授权状态、所有文件访问（MANAGE_EXTERNAL_STORAGE）授权集中在设置页管理

### 搜索

- 可配置递归深度（仅当前目录 / 指定层数 / 无限递归）
- 通配符（`*` `?`）、正则表达式、完全匹配、区分大小写
- 按文件大小、修改时间、文件内容过滤
- **搜索索引缓存**：后台建索引实现秒搜，可在设置中随时开关或重建

### 压缩与解压

- 支持格式：`zip`、`jar`、`apk`、`7z`、`tar`、`tar.gz`、`tgz`、`tar.bz2`、`tar.xz`、`rar4`、`rar5`、`gz`、`bz2`、`xz`
- 加密 ZIP 解压支持
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

### 编辑器

- **文本编辑器**：搜索 / 替换 / 全部替换、正则、全词匹配、区分大小写、行号、跳转指定行、字号调节、多编码、大文件流畅模式（超过 1MB 截断加载）
- **富文本编辑器**：基于 richeditor-compose，支持 HTML / Markdown / 纯文本编辑与格式互转、超链接插入

### 逆向工具集

- **工具托盘**：内置 APK 工具（Acode、APKToolM），支持解压 → 安装 → 启动三步流程，可将当前文件直接传递给工具处理
- **APK 启动器**：解析未安装 APK 的包名 / 入口 Activity / 版本，可完整自定义启动 Intent：Action、Category、Data、Extras（String/Int/Bool/Long）、窗口 Flags
- **VirtualBox 免安装运行**：基于虚拟应用容器（Bcore）将 APK 导入虚拟环境运行，无需实际安装到系统

### 其他

- **统一打开方式**（[OpenWithActivity](app/src/main/java/com/freefile/ui/OpenWithActivity.kt)）：分享、系统应用打开、文本编辑器、工具托盘、终端、解压，结果回传主界面做上下文处理
- **多语言**：简体中文 / English，应用内切换、即时生效（Per-app language）
- 文件大小计算默认关闭以加快大目录浏览，可在设置中开启
- 前台服务保障传输 / 服务类任务稳定运行

## 技术栈

| 类别 | 技术选型 |
| --- | --- |
| 语言 / UI | Kotlin、Jetpack Compose、Material3 |
| 架构 | MVI + ViewModel、StateFlow、协程 |
| 数据持久化 | Room（标签 / 书签 / 历史 / 回收站）、DataStore（偏好设置） |
| 导航 | Navigation Compose |
| 高权限 | Shizuku API 13.1.5、Root Shell |
| 压缩 | zip4j、Apache Commons Compress、XZ、JunRAR |
| 网络 | OkHttp、commons-net（FTP）、SMBJ（SMB2/3）、NanoHTTPD、jmdns |
| 终端 | Termux terminal-emulator / terminal-view v0.118 |
| 脚本 | LuaJ 3.0.1 |
| 编辑器 | richeditor-compose |

## 系统要求

- **minSdk**：24（Android 7.0）
- **targetSdk**：33
- **compileSdk**：34
- Root / Shizuku 功能为可选增强，非必需

## 编译构建

使用 Android Studio 打开项目，或在命令行执行：

```bash
# Windows
gradlew.bat :app:assembleDebug

# macOS / Linux
./gradlew :app:assembleDebug
```

产物路径：`app/build/outputs/apk/debug/app-debug.apk`

环境要求：JDK 17、Gradle 8.0（Wrapper 已自带）。终端模拟器与虚拟容器均以预构建 AAR / Maven 依赖方式引入，**无需本地 NDK / CMake 环境**。

若您想在您自行构建的应用中使用"第三方应用下载"功能，请在DownloadToolActivity中将常量
TOOL_CATALOG_URL = "https://thisisexample.com/json/tools.json" 换为您自己的工具索引json，格式见/example/tools.json
其中https://thisisexample.com/example为实例链接，example为example.apk去掉.apk后缀后的文件
## 项目结构

```
app/src/main/java/com/freefile/
├── core/          # MVI 基类、权限管理
├── data/          # Room 数据库、仓库、DataStore 设置
├── operations/    # 文件操作、传输管理、解压缩、冲突策略
├── vfs/           # 虚拟文件系统：Local / Shell / Archive / Remote 引擎与选择器
│   └── remote/    # WebDAV / FTP / SMB / S3 客户端
├── search/        # 搜索引擎与索引
├── terminal/      # Termux 终端会话管理
├── shell/         # Root / Shizuku Shell 提供者
├── script/        # Lua / Python 脚本引擎
├── share/         # 局域网快传：HTTP 服务与 mDNS 发现
├── plugin/        # 插件管理
├── tools/         # 富文本编辑器、压缩引擎、VirtualBox 虚拟容器
└── ui/            # Compose 界面：双窗格、对话框、设置、终端、各独立 Activity
```

## 权限说明

| 权限 | 用途 |
| --- | --- |
| `MANAGE_EXTERNAL_STORAGE` | 核心文件浏览能力，需用户在系统设置中手动授予 |
| `QUERY_ALL_PACKAGES` / `REQUEST_INSTALL_PACKAGES` | 工具托盘解析、安装 APK |
| Shizuku 权限 | Root 不可用时的高权限文件访问方案 |
| 网络 / WLAN 相关权限 | 远程文件系统与局域网快传（含 mDNS 设备发现） |
| 前台服务权限 | 文件传输等长时任务保活 |

## 开发状态

当前版本 **v1.0**，核心功能均已落地并可编译运行，部分能力（如压缩打包、虚拟容器 Provider 接入）仍在迭代中。详细任务进展见 [PROGRESS.md](PROGRESS.md)。

## 文档与许可证

- 英文版：[README_EN.md](README_EN.md)
- 开发进度：[PROGRESS.md](PROGRESS.md)
- 许可证：[LICENSE.md](LICENSE.md)

## 免责声明

- 本应用提供 Root / Shizuku 高权限文件操作能力，请在充分了解风险的前提下使用；删除、移动系统文件可能导致设备故障
- 局域网快传默认仅允许私网明文传输，请勿在公共网络下开启
- 应用内逆向工具仅用于学习研究、个人文件处理及对自有应用的分析，请遵守当地法律法规及目标软件许可协议
