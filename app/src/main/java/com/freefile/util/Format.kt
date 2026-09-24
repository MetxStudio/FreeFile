package com.freefile.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 文件大小/时间等格式化工具。 */
object Format {

    fun size(bytes: Long): String {
        if (bytes < 0) return "-"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    fun time(millis: Long): String {
        if (millis <= 0) return "-"
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(millis))
    }

    fun speed(bytesPerSecond: Float): String = when {
        bytesPerSecond <= 0 -> ""
        bytesPerSecond < 1024 * 1024 -> String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024)
        else -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond / 1024 / 1024)
    }
}
