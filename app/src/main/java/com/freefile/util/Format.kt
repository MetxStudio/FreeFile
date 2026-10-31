package com.freefile.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 文件大小/时间等格式化工具。 */
object Format {

    fun size(bytes: Long): String {
        if (bytes < 0) return "-"
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var idx = 0
        while (value >= 1024 && idx < units.lastIndex) {
            value /= 1024.0
            idx++
        }
        val pattern = if (value < 10) "%.2f" else "%.1f"
        return String.format(Locale.US, "$pattern %s", value, units[idx])
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
