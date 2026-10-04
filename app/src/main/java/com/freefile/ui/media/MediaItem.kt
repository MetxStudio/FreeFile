package com.freefile.ui.media

import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.File

/** 媒体类型。 */
enum class MediaType { IMAGE, VIDEO, AUDIO, UNKNOWN }

/**
 * 统一媒体条目模型。
 * 只携带展示/播放所需的最小信息；详情按需懒加载。
 */
data class MediaItem(
    val uri: Uri,
    val type: MediaType,
    val mimeType: String?,
    val title: String,
    val size: Long,
    val path: String? = null
) {
    companion object {
        private val IMAGE_EXT = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg"
        )
        private val VIDEO_EXT = setOf(
            "mp4", "mkv", "webm", "mov", "avi", "flv", "wmv", "m4v", "3gp", "ts"
        )
        private val AUDIO_EXT = setOf(
            "mp3", "flac", "aac", "ogg", "wav", "m4a", "wma", "opus", "amr", "mid"
        )

        /** 从本地文件路径构建 [MediaItem]。 */
        fun fromPath(path: String): MediaItem {
            val file = File(path)
            val ext = file.extension.lowercase()
            val type = typeOf(ext)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            return MediaItem(
                uri = Uri.fromFile(file),
                type = type,
                mimeType = mime,
                title = file.name,
                size = if (file.exists()) file.length() else 0L,
                path = path
            )
        }

        /** 根据扩展名推断媒体类型。 */
        fun typeOf(extension: String): MediaType {
            val ext = extension.lowercase()
            return when {
                ext in IMAGE_EXT -> MediaType.IMAGE
                ext in VIDEO_EXT -> MediaType.VIDEO
                ext in AUDIO_EXT -> MediaType.AUDIO
                else -> MediaType.UNKNOWN
            }
        }

        /** 是否为可识别的媒体文件。 */
        fun isMedia(extension: String): Boolean = typeOf(extension) != MediaType.UNKNOWN
    }
}
