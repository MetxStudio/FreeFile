package com.freefile.tools.ai.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * HTTP 日志：LLM / MCP 请求响应统一记录。
 * 内存环形缓冲（UI 即时查看）+ 文件追加（重启后仍可排查），不记录 Authorization 头。
 */
object HttpLog {

    data class Entry(
        val ts: Long,
        val tag: String,
        val method: String,
        val url: String,
        val status: String,
        val costMs: Long,
        val bodyPreview: String
    )

    private const val MAX_MEMORY = 300
    private const val MAX_BODY = 4000
    private const val FILE_NAME = "agent_http.log"
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    private val buffer = ArrayDeque<Entry>()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "freefile-ai-httplog") }
    @Volatile
    private var logDir: File? = null
    @Volatile
    var enabled: Boolean = true

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(dir: File) {
        logDir = dir
    }

    /** 请求发出。 */
    fun request(tag: String, method: String, url: String, body: String?) {
        if (!enabled) return
        append(Entry(System.currentTimeMillis(), tag, method, url, "REQ", 0, preview(body)))
    }

    /** 响应/失败落定。 */
    fun response(tag: String, method: String, url: String, status: String, costMs: Long, body: String?) {
        if (!enabled) return
        append(Entry(System.currentTimeMillis(), tag, method, url, status, costMs, preview(body)))
    }

    fun snapshot(): List<Entry> = synchronized(buffer) { buffer.toList() }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
        val f = file() ?: return
        io.execute { runCatching { f.delete() } }
    }

    /** 同步读文件日志（IO 线程调用）。 */
    suspend fun readFile(limit: Int = 500): List<String> = withContext(Dispatchers.IO) {
        val f = file() ?: return@withContext emptyList()
        runCatching {
            if (!f.exists()) emptyList()
            else f.readLines().takeLast(limit)
        }.getOrDefault(emptyList())
    }

    private fun file(): File? = logDir

    private fun preview(body: String?): String {
        val b = body.orEmpty()
        return if (b.length > MAX_BODY) b.substring(0, MAX_BODY) + "…(截断)" else b
    }

    private fun append(e: Entry) {
        synchronized(buffer) {
            buffer.addLast(e)
            while (buffer.size > MAX_MEMORY) buffer.removeFirst()
        }
        val dir = logDir ?: return
        io.execute {
            runCatching {
                if (dir.exists() && dir.length() > MAX_FILE_BYTES) dir.delete()
                dir.parentFile?.mkdirs()
                dir.appendText(format(e) + "\n")
            }
        }
    }

    private fun format(e: Entry): String =
        "${fmt.format(Date(e.ts))} [${e.tag}] ${e.method} ${e.url} -> ${e.status}" +
            (if (e.costMs > 0) " (${e.costMs}ms)" else "") +
            (if (e.bodyPreview.isNotEmpty()) "\n  ${e.bodyPreview.replace("\n", "\n  ")}" else "")
}
