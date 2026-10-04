# 媒体查看器（Media Viewer）开发计划

> 文件管理器内嵌的轻量媒体查看器：统一查看/播放图片、视频、音频，支持切换、分享、删除等基础操作，不替代系统图库与专业播放器。

## 1. 定位与边界

**定位**：文件管理器里的轻量媒体查看器，不替代系统图库、音乐播放器和视频播放器，但能快速查看/播放当前目录或筛选结果中的媒体，并完成常见文件操作。

**核心原则**：

* 打开快、切换顺、播放稳、大图不 OOM、音视频不卡顿。

* 与文件管理器解耦，只接收 `Uri` 列表和初始索引。

* 兼容本地、SAF、MediaStore、云盘等来源。

* MVP 先做查看/播放、切换、分享、删除、详情；编辑、投屏、转码后续迭代。

**不建议一开始做**：

* 完整图片编辑器、视频剪辑器、音乐库管理。

* 自己从零写播放器、缩放控件、媒体扫描。

* 同时支持所有冷门格式和投屏协议。

## 2. 媒体类型与统一模型

建议统一模型：

```kotlin
enum class MediaType { IMAGE, VIDEO, AUDIO, UNKNOWN }

data class MediaItem(
    val uri: Uri,
    val type: MediaType,
    val mimeType: String?,
    val title: String?,
    val size: Long?,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val artist: String?,
    val album: String?,
    val artworkUri: Uri?,
    val dateModified: Long?
)
```

类型识别：

* 优先 `ContentResolver.getType(uri)`。

* 回退扩展名：jpg/png/gif/webp/heic；mp4/mkv/webm/mov；mp3/flac/aac/ogg/wav/m4a。

* 无法识别时显示“用其他应用打开”。

## 3. 功能优先级

### P0 MVP

* 从文件管理器点击媒体进入查看器。

* 混合列表：同目录/同筛选结果中的图片、音频、视频。

* 按当前项类型切换 UI：图片页、视频页、音频页。

* 图片：左右滑动切换、双指缩放、双击放大/还原、拖拽平移。

* 视频：播放/暂停、进度、全屏、基础手势、分享、删除、详情。

* 音频：播放/暂停、上一首/下一首、进度、封面、分享、详情。

* 单击隐藏/显示控制栏。

* 加载进度、失败重试、文件不存在提示。

* 删除后回传结果并刷新文件管理器列表。

### P1 增强

* 音频后台播放、通知栏控制、耳机/蓝牙控制。

* 视频画中画、倍速、字幕、音轨切换、循环。

* 图片 EXIF、GIF/WebP 动图、设为壁纸。

* 播放列表、随机/循环、多选、复制/移动、重命名。

* 缩略图先显、高清后显、相邻媒体预加载。

* 深色/透明背景、折叠屏/刘海屏适配。

### P2 高级

* 投屏/DLNA、云盘、压缩包内媒体。

* 图片裁剪/旋转/滤镜、视频剪辑、音频均衡器。

* 睡眠定时、播放队列管理、媒体库收藏/标签。

* 视频帧预览、HDR、空间音频。

## 4. 技术选型

| 模块    | 建议                                                       |
| ----- | -------------------------------------------------------- |
| 语言    | Kotlin + Coroutines + Flow                               |
| UI    | ViewPager2 + Fragment，或 Compose HorizontalPager          |
| 图片加载  | Coil/Glide，支持 GIF/WebP/HEIC                              |
| 大图缩放  | SubsamplingScaleImageView 或 PhotoView                    |
| 音视频播放 | Media3 ExoPlayer                                         |
| 音频后台  | Media3 MediaSessionService + MediaController             |
| 视频缩略图 | Coil VideoFrameDecoder 或 MediaMetadataRetriever          |
| EXIF  | AndroidX ExifInterface                                   |
| 文件访问  | MediaStore、SAF、File API，统一转 Uri                          |
| 分享    | FileProvider                                             |
| 删除    | MediaStore.createDeleteRequest；SAF 用 DocumentFile.delete |
| 架构    | MVVM + Repository + UseCase                              |
| 依赖注入  | Hilt/Koin，按项目现状                                          |

**关键决策**：

* 查看器只接收 `List<MediaItem>` 或 `List<Uri>` + `initialIndex`。

* 独立 Activity 或 Navigation Destination，便于全屏、PiP、返回结果。

* 音频播放与视频播放分离：音频用 MediaSessionService，视频用 Activity/ViewModel 内 ExoPlayer。

* 进入视频时暂停音频；查看图片时音频可继续，可显示迷你控制条。

## 5. 推荐架构

```text
文件管理器
  -> MediaViewerLauncher.start(context, MediaViewerArgs)
    -> MediaViewerActivity
      -> MediaViewerViewModel
        -> MediaRepository（MediaStore/SAF/File）
        -> ImageLoader（Coil/Subsampling）
        -> VideoPlayerHolder（Media3 ExoPlayer）
        -> AudioController（MediaSessionService/MediaController）
        -> CacheManager（图片 + 音视频）
      -> ImagePage / VideoPage / AudioPage
      -> ViewerControls
      -> ActionController（分享/删除/重命名/详情/设为壁纸）
```

接口示例：

```kotlin
data class MediaViewerArgs(
    val items: List<MediaItem>,
    val initialIndex: Int,
    val source: SourceType, // FILE, SAF, MEDIASTORE, CLOUD
    val canDelete: Boolean,
    val canRename: Boolean,
    val sort: SortType
)

data class MediaViewerResult(
    val deletedUris: List<Uri>,
    val renamedUris: List<Uri>,
    val currentIndex: Int,
    val lastPlayedUri: Uri?,
    val lastPositionMs: Long?
)
```

## 6. UI 与交互规划

**统一框架**：

* 全屏沉浸，黑色/深灰背景。

* 顶部：返回、标题、当前索引/总数、更多菜单。

* 底部：根据类型动态变化。

* 单击切换控制栏；长按弹出菜单。

* 顶部/底部可自动隐藏。

**按类型交互**：

| 类型 | 主界面                           | 手势                            | 底部操作                              |
| -- | ----------------------------- | ----------------------------- | --------------------------------- |
| 图片 | 图片 + 缩放                       | 左右滑切换、双指缩放、双击缩放、拖拽            | 分享、删除、详情、设为壁纸、编辑                  |
| 视频 | SurfaceView/TextureView + 控制条 | 单击显示控制、双击播放/暂停、上下滑音量/亮度、水平滑进度 | 播放/暂停、进度、倍速、字幕、音轨、全屏、PiP、分享、删除    |
| 音频 | 封面 + 标题/艺术家 + 进度              | 左右滑切歌、上下滑音量可选                 | 播放/暂停、上一首/下一首、循环/随机、倍速、播放列表、分享、详情 |

**混合列表切换**：

* 图片页可左右滑动切换。

* 音频页可左右滑动切歌。

* 视频页水平手势用于 seek，切换媒体用上一首/下一首按钮或播放列表，避免手势冲突。

* 顶部始终显示“当前索引/总数”，可跨类型切换。

**加载状态**：

* 图片：先缩略图/模糊图，再高清图。

* 视频：先首帧/缩略图，再起播。

* 音频：先封面/元数据，再播放。

* 失败：占位图、重试、用其他应用打开。

**边界情况**：

* 0 字节、损坏文件、无权限、URI 失效。

* SD 卡拔出、文件被其他应用删除。

* 超大图、长图、透明 PNG、GIF、HEIC、SVG。

* 4K/HDR/旋转视频、字幕乱码、音轨切换。

* 音频焦点丢失、来电打断、耳机拔出。

* 横竖屏、分屏、折叠屏、手势导航。

## 7. 播放器与后台策略

**视频**：

* 使用 Media3 ExoPlayer。

* 支持 SurfaceView/TextureView、全屏、PiP、倍速、字幕、音轨。

* 进入后台或 PiP 时按需暂停/继续。

* 视频缓存可选 Media3 SimpleCache。

**音频**：

* 使用 Media3 MediaSessionService 持有 ExoPlayer。

* Activity 通过 MediaController 连接，通知栏、锁屏、耳机、蓝牙可控。

* 支持音频焦点、来电打断、耳机拔出暂停。

* Android 14+ 声明前台服务类型 `mediaPlayback`，申请 `FOREGROUND_SERVICE_MEDIA_PLAYBACK`。

* Android 13+ 需要 `POST_NOTIFICATIONS` 才能显示播放通知。

**冲突管理**：

* 进入视频播放时暂停音频。

* 音频播放时打开图片可继续，显示迷你控制条。

* 退出查看器时，音频可后台继续，视频应暂停/释放。

## 8. 权限与隐私

* 优先 SAF/MediaStore，避免 `MANAGE_EXTERNAL_STORAGE`。

* Android 13+：`READ_MEDIA_IMAGES`、`READ_MEDIA_VIDEO`、`READ_MEDIA_AUDIO`。

* Android 14+：支持部分照片/视频访问 `READ_MEDIA_VISUAL_USER_SELECTED`。

* 音频后台：`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PLAYBACK`、`POST_NOTIFICATIONS`。

* 分享用 FileProvider，不暴露真实路径。

* 删除走系统确认弹窗。

* EXIF 含定位时，分享前提示是否移除位置信息。

## 9. 性能与缓存

* 图片：解码前计算目标尺寸，`inSampleSize` 采样；大图用 `BitmapRegionDecoder` 或 SubsamplingScaleImageView 分块加载。

* 视频：硬解优先，复用播放器，预加载下一视频首帧/元数据。

* 音频：流式播放，缓存封面和元数据。

* 列表：缩略图异步加载，LruCache + 磁盘缓存。

* 预加载：图片预加载当前 ±1，音视频只预加载元数据和封面。

* `onTrimMemory` 清理缓存，主线程不解码、不 IO。

* 动图限制最大尺寸和帧率，防止 OOM。

## 10. 模块与代码结构

```text
mediaviewer/
  api/MediaViewerLauncher.kt
  api/MediaViewerArgs.kt
  api/MediaViewerResult.kt
  model/MediaItem.kt
  model/MediaType.kt
  model/MediaMeta.kt
  ui/MediaViewerActivity.kt
  ui/MediaViewerScreen.kt
  ui/image/ImagePage.kt
  ui/video/VideoPage.kt
  ui/audio/AudioPage.kt
  ui/common/ViewerControls.kt
  player/VideoPlayerHolder.kt
  player/AudioPlayerService.kt
  player/AudioController.kt
  repository/MediaRepository.kt
  repository/MediaStoreMediaRepository.kt
  repository/SafMediaRepository.kt
  loader/ImageLoader.kt
  loader/VideoThumbLoader.kt
  cache/MediaCacheManager.kt
  action/ShareAction.kt
  action/DeleteAction.kt
  action/RenameAction.kt
```

## 11. 迭代里程碑

**第 1 阶段：MVP，2-3 周**

* 统一入口、类型识别、混合列表。

* 图片查看、视频播放、音频播放。

* 基本控制、分享、删除、详情。

* 基础错误处理和权限适配。

**第 2 阶段：体验增强，2-3 周**

* 音频后台/通知/耳机控制。

* 视频 PiP、倍速、字幕、音轨。

* 图片 EXIF、GIF/WebP、设为壁纸。

* 缓存、预加载、重命名、复制/移动。

**第 3 阶段：高级能力**

* 投屏/DLNA、云盘、压缩包。

* 图片编辑、视频剪辑、音频均衡器。

* 播放队列、睡眠定时、收藏/标签。

## 12. 测试重点

* 单元测试：类型识别、排序、索引、缓存、EXIF、文件操作。

* UI 测试：图片手势、视频手势、音频控制、旋转、权限、删除确认。

* 播放测试：4K/HDR、字幕、音轨、后台音频、来电打断、耳机拔出。

* 性能测试：1000+ 媒体列表、50MP 大图、GIF、低内存设备。

* 兼容测试：Android 8-15、主流厂商、折叠屏、SAF/MediaStore。

## 13. 关键建议

1. **统一用 Uri + MediaItem**，不要依赖真实文件路径。
2. **图片用 Coil + Subsampling，音视频用 Media3 ExoPlayer**。
3. **音频后台用 MediaSessionService，视频前台用 Activity/ViewModel 内播放器**。
4. **混合列表按类型切换 UI，注意视频手势与切换手势冲突**。
5. **删除/重命名后必须回传结果**，让文件管理器刷新列表。
6. **先缩略图/首帧后高清/起播**，预加载只做相邻媒体元数据。
7. **MVP 不做编辑/投屏/转码**，避免工期失控。

