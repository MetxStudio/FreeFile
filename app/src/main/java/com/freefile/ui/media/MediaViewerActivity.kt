package com.freefile.ui.media

import android.app.PictureInPictureParams
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.freefile.R
import com.freefile.operations.FileChangeBus
import com.freefile.ui.theme.FreeFileTheme
import java.io.File

/**
 * 画中画遥控桥：VideoPage 注册播放命令与播放状态，Activity 的 RemoteAction 触发。
 */
class PipPlaybackBridge {
    @Volatile var isPlaying: Boolean = false
    /** 由视频页注册；画中画 RemoteAction 调用。 */
    var togglePlayback: (() -> Unit)? = null
    /** 播放状态变化通知，用于刷新小窗按钮图标。 */
    var onPlaybackStateChanged: (() -> Unit)? = null
}

/**
 * 媒体查看器入口：接收文件路径列表与初始索引，统一展示图片/视频/音频。
 *
 * Intent extras:
 *  - [EXTRA_FILE_PATHS]  : ArrayList<String>，文件路径列表
 *  - [EXTRA_INITIAL_INDEX]: Int，初始展示索引，默认 0
 *
 * 结果回传：
 *  - resultCode = RESULT_OK
 *  - [EXTRA_DELETED_PATHS]: ArrayList<String>，被删除的文件路径，供主页刷新列表
 */
class MediaViewerActivity : AppCompatActivity() {

    /** 是否处于画中画模式（Compose 可观察）。 */
    private var isInPip by mutableStateOf(false)
    /** 当前视频的画面尺寸，用于构造 PiP 宽高比。 */
    private var videoWidth by mutableIntStateOf(0)
    private var videoHeight by mutableIntStateOf(0)
    /** 当前展示项是否为视频，决定 Home 键是否进入 PiP。 */
    private var currentIsVideo by mutableStateOf(false)

    /** 画中画播放控制桥。 */
    private val pipBridge = PipPlaybackBridge()

    /** 画中画 RemoteAction 的广播接收器：切换播放/暂停。 */
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getIntExtra(EXTRA_PIP_CONTROL, -1) == CONTROL_TOGGLE_PLAY) {
                pipBridge.togglePlayback?.invoke()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val paths = intent.getStringArrayListExtra(EXTRA_FILE_PATHS) ?: emptyList()
        val initialIndex = intent.getIntExtra(EXTRA_INITIAL_INDEX, 0).coerceIn(0, paths.size - 1)

        if (paths.isEmpty()) {
            finish()
            return
        }

        // 画中画：播放状态变化时刷新小窗按钮图标；注册遥控广播
        pipBridge.onPlaybackStateChanged = { if (isInPip) updatePipParams() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(pipReceiver, IntentFilter(ACTION_PIP_CONTROL))
        }

        val items = paths.map { MediaItem.fromPath(it) }
        val deletedPaths = java.util.ArrayList<String>()

        setContent {
            FreeFileTheme {
                MediaViewerScreen(
                    items = items,
                    initialIndex = initialIndex,
                    isInPip = isInPip,
                    onBack = { finishWithResult(deletedPaths) },
                    onShare = { item -> shareItem(item) },
                    onDelete = { item -> deleteItem(item, deletedPaths) },
                    onDetails = { item -> showDetails(item) },
                    onOpenWith = { item -> openWith(item) },
                    onSetWallpaper = { item -> setWallpaper(item) },
                    onCurrentTypeChanged = { type -> currentIsVideo = type == MediaType.VIDEO },
                    onVideoSizeChanged = { w, h ->
                        videoWidth = w
                        videoHeight = h
                    },
                    onEnterPip = { enterPip() },
                    pipBridge = pipBridge
                )
            }
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                unregisterReceiver(pipReceiver)
            } catch (_: Exception) {
            }
        }
        pipBridge.togglePlayback = null
        pipBridge.onPlaybackStateChanged = null
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 用户按 Home 键离开时，正在播放视频则进入画中画继续播放
        if (currentIsVideo && pipBridge.isPlaying && !isInPip) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPip = isInPictureInPictureMode
        // 进入小窗后挂载播放/暂停遥控按钮
        if (isInPictureInPictureMode) updatePipParams()
    }

    /** 设备是否支持画中画（Android 8+ 且声明特性）。 */
    private fun pipSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun enterPip() {
        if (!pipSupported()) {
            toast(getString(R.string.media_pip_unsupported))
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val params = PictureInPictureParams.Builder().apply {
                if (videoWidth > 0 && videoHeight > 0) {
                    setAspectRatio(Rational(videoWidth, videoHeight))
                }
                setActions(buildPipActions())
            }.build()
            enterPictureInPictureMode(params)
        } catch (e: Exception) {
            toast(getString(R.string.media_pip_unsupported))
        }
    }

    /** 更新画中画参数（宽高比 + 遥控按钮），用于进入小窗或播放状态变化时刷新。 */
    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val params = PictureInPictureParams.Builder().apply {
                if (videoWidth > 0 && videoHeight > 0) {
                    setAspectRatio(Rational(videoWidth, videoHeight))
                }
                setActions(buildPipActions())
            }.build()
            setPictureInPictureParams(params)
        } catch (_: Exception) {
        }
    }

    /** 画中画小窗的遥控按钮：播放/暂停。 */
    private fun buildPipActions(): List<RemoteAction> {
        val intent = Intent(ACTION_PIP_CONTROL)
            .setPackage(packageName)
            .putExtra(EXTRA_PIP_CONTROL, CONTROL_TOGGLE_PLAY)
        val pendingIntent = PendingIntent.getBroadcast(
            this, REQUEST_PIP_TOGGLE, intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        val playing = pipBridge.isPlaying
        val iconRes = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val title = getString(if (playing) R.string.media_pip_pause else R.string.media_pip_play)
        return listOf(
            RemoteAction(Icon.createWithResource(this, iconRes), title, title, pendingIntent)
        )
    }

    private fun finishWithResult(deletedPaths: ArrayList<String>) {
        if (deletedPaths.isNotEmpty()) {
            setResult(
                RESULT_OK,
                Intent().putStringArrayListExtra(EXTRA_DELETED_PATHS, deletedPaths)
            )
        }
        finish()
    }

    private fun shareItem(item: MediaItem) {
        val file = item.path?.let { File(it) }
        if (file == null || !file.exists()) {
            toast(getString(R.string.media_open_failed))
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType ?: "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, item.title))
        } catch (e: Exception) {
            toast(getString(R.string.msg_no_app))
        }
    }

    private fun deleteItem(item: MediaItem, deletedPaths: ArrayList<String>): Boolean {
        val file = item.path?.let { File(it) }
        if (file == null || !file.exists()) {
            toast(getString(R.string.media_open_failed))
            return false
        }
        val ok = file.delete()
        if (ok) {
            item.path?.let { deletedPaths.add(it) }
            // 通知文件变更总线，让正在展示该目录的窗格刷新
            FileChangeBus.notify(file.parent ?: "")
            toast(getString(R.string.media_delete_done))
        } else {
            toast(getString(R.string.media_delete_failed))
        }
        return ok
    }

    /** 用系统其他应用打开当前媒体文件。 */
    private fun openWith(item: MediaItem) {
        val file = item.path?.let { File(it) }
        if (file == null || !file.exists()) {
            toast(getString(R.string.media_open_failed))
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            type = item.mimeType ?: "*/*"
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.media_open_with)))
        } catch (e: Exception) {
            toast(getString(R.string.msg_no_app))
        }
    }

    /** 将图片设置为系统静态壁纸。解码与设置在后台线程执行。 */
    private fun setWallpaper(item: MediaItem) {
        val path = item.path
        if (path == null || !File(path).exists()) {
            toast(getString(R.string.media_open_failed))
            return
        }
        Thread {
            try {
                val bitmap = BitmapFactory.decodeFile(path)
                if (bitmap == null) {
                    runOnUiThread { toast(getString(R.string.media_wallpaper_failed)) }
                    return@Thread
                }
                val wm = android.app.WallpaperManager.getInstance(this)
                wm.setBitmap(bitmap)
                bitmap.recycle()
                runOnUiThread { toast(getString(R.string.media_wallpaper_done)) }
            } catch (t: Throwable) {
                runOnUiThread { toast(getString(R.string.media_wallpaper_failed)) }
            }
        }.start()
    }

    private fun showDetails(item: MediaItem) {
        // 元数据（EXIF / MediaMetadataRetriever）在后台线程读取
        Thread {
            val msg = buildDetailMessage(item)
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle(R.string.media_detail_title)
                    .setMessage(msg)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }.start()
    }

    private fun buildDetailMessage(item: MediaItem): String {
        val sizeText = android.text.format.Formatter.formatShortFileSize(this, item.size)
        return buildString {
            append(item.title).append('\n')
            append(getString(R.string.media_detail_type)).append(' ').append(item.type.name).append('\n')
            append(getString(R.string.media_detail_mime)).append(' ').append(item.mimeType ?: "-").append('\n')
            append(getString(R.string.media_detail_size)).append(' ').append(sizeText).append('\n')
            item.path?.let { append(getString(R.string.media_detail_path)).append(' ').append(it).append('\n') }

            when (item.type) {
                MediaType.IMAGE -> appendImageExif(item)
                MediaType.VIDEO, MediaType.AUDIO -> appendMediaMetadata(item)
                else -> Unit
            }
        }.trimEnd('\n')
    }

    private fun StringBuilder.appendImageExif(item: MediaItem) {
        val path = item.path ?: return
        try {
            // 先用 BitmapFactory 取真实像素尺寸（EXIF 宽高字段部分设备不可靠）
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                append(getString(R.string.media_detail_resolution))
                    .append(" ${opts.outWidth} × ${opts.outHeight}\n")
            }
            ExifInterface(path).let { exif ->
                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.takeIf { it.isNotBlank() }?.let {
                    append(getString(R.string.media_detail_date_taken)).append(' ').append(it).append('\n')
                }
                val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
                val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
                val camera = listOf(make, model).filter { it.isNotBlank() }.joinToString(" ")
                if (camera.isNotBlank()) {
                    append(getString(R.string.media_detail_camera)).append(' ').append(camera).append('\n')
                }
                val lat = exif.latLong
                if (lat != null) {
                    append(getString(R.string.media_detail_location))
                        .append(" %.5f, %.5f\n".format(lat[0], lat[1]))
                }
            }
        } catch (_: Throwable) {
            // EXIF 读取失败不影响基础详情
        }
    }

    private fun StringBuilder.appendMediaMetadata(item: MediaItem) {
        val path = item.path ?: return
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull()
            if (durationMs != null && durationMs > 0) {
                append(getString(R.string.media_detail_duration))
                    .append(' ').append(formatDuration(durationMs)).append('\n')
            }
            if (item.type == MediaType.VIDEO) {
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                if (!w.isNullOrBlank() && !h.isNullOrBlank()) {
                    append(getString(R.string.media_detail_resolution)).append(" $w × $h")
                    if (!rotation.isNullOrBlank() && rotation != "0") append(" ($rotation°)")
                    append('\n')
                }
            } else {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.takeIf { it.isNotBlank() }?.let {
                        append(getString(R.string.media_detail_artist)).append(' ').append(it).append('\n')
                    }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?.takeIf { it.isNotBlank() }?.let {
                        append(getString(R.string.media_detail_album)).append(' ').append(it).append('\n')
                    }
            }
        } catch (_: Throwable) {
            // 元数据读取失败不影响基础详情
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_FILE_PATHS = "media_file_paths"
        const val EXTRA_INITIAL_INDEX = "media_initial_index"
        const val EXTRA_DELETED_PATHS = "media_deleted_paths"

        private const val ACTION_PIP_CONTROL = "com.freefile.media.pip.CONTROL"
        private const val EXTRA_PIP_CONTROL = "pip_control"
        private const val CONTROL_TOGGLE_PLAY = 1
        private const val REQUEST_PIP_TOGGLE = 1001
    }
}
