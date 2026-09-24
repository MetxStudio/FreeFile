package com.freefile.shell

import android.os.Handler
import android.os.Looper
import com.freefile.vfs.VfsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Shell 提供者抽象：Root(su) 与 Shizuku 共用同一套会话协议。
 * 每个提供者维护一条持久 shell 进程，通过 EOF 标记协议同步收发命令。
 */
interface ShellProvider {
    val id: String
    /** 服务/权限是否就绪（轻量检查）。 */
    fun isReady(): Boolean
    /** 启动持久 shell 进程。 */
    fun spawnProcess(): Process

    /** 单例锁下获取会话。 */
    val session: ShellSession
}

/** Root 提供者：通过 su 启动持久 shell。 */
object SuProvider : ShellProvider {
    override val id = "root"
    private var cachedAvailable: Boolean? = null

    override fun isReady(): Boolean {
        cachedAvailable?.let { return it }
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id -u"))
            // 先等进程退出（兼容低版本 API），再读输出，避免 su 授权弹窗时 readText 无限阻塞
            val finished = p.waitForCompat(3000)
            if (!finished) {
                p.destroy()
                cachedAvailable = false
                return false
            }
            val out = p.inputStream.bufferedReader().readText()
            val ok = out.trim().endsWith("0")
            cachedAvailable = ok
            ok
        } catch (e: Throwable) {
            // 捕 Throwable：NoSuchMethodError 等 Error 也不应崩溃
            cachedAvailable = false
            false
        }
    }

    override fun spawnProcess(): Process =
        Runtime.getRuntime().exec(arrayOf("su"))

    override val session: ShellSession by lazy { ShellSession(this) }

    /** 用户可在设置中重新检测 Root。 */
    fun recheck() { cachedAvailable = null }
}

/** Shizuku 提供者：通过 Shizuku.newProcess 启动 shell。 */
object ShizukuProvider : ShellProvider {
    override val id = "shizuku"

    override fun isReady(): Boolean = try {
        rikka.shizuku.Shizuku.pingBinder() && rikka.shizuku.Shizuku.checkSelfPermission() == 0
    } catch (e: Exception) {
        false
    }

    override fun spawnProcess(): Process {
        if (!rikka.shizuku.Shizuku.pingBinder()) throw VfsException("Shizuku 未连接")
        if (rikka.shizuku.Shizuku.getVersion() < 10) throw VfsException("Shizuku 版本过低（需要 API 10+）")
        // newProcess 在 13.1.5 中已不再是公开 API，反射调用（Shizuku-API#276）
        return try {
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val method = clazz.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            method.isAccessible = true
            method.invoke(null, arrayOf("sh"), null, null) as Process
        } catch (e: Exception) {
            throw VfsException("Shizuku 无法启动 shell", e)
        }
    }

    override val session: ShellSession by lazy { ShellSession(this) }
}

/**
 * 持久 shell 会话：写入命令后等待唯一 EOF 标记行，读取期间的所有输出为命令结果。
 * 非线程安全 —— 外部用互斥保证同一时刻一条命令（synchronized(session)）。
 */
class ShellSession(private val provider: ShellProvider) {

    private var process: Process? = null
    private var stdoutReader: BufferedReader? = null
    private var stderrDrain: Thread? = null

    private fun ensureStarted() {
        if (process?.isAlive == true) return
        process?.destroy()
        val p = provider.spawnProcess()
        process = p
        stdoutReader = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))
        // 后台持续消化 stderr，避免缓冲区阻塞
        stderrDrain?.interrupt()
        stderrDrain = Thread {
            try {
                p.errorStream.bufferedReader().forEachLine { }
            } catch (_: Exception) {}
        }.also { it.isDaemon = true; it.start() }
    }

    /**
     * 同步执行一条 shell 命令。
     * @return stdout 全文；exitCode 非 0 时抛 [VfsException]（noThrow=false）。
     */
    fun exec(command: String, noThrow: Boolean = false): String {
        ensureStarted()
        val p = process ?: throw VfsException("shell 未启动")
        val marker = "__FF_EOF_${UUID.randomUUID()}__"
        val stdin = p.outputStream
        stdin.write("$command\necho $marker \$?\n".toByteArray(Charsets.UTF_8))
        stdin.flush()

        val sb = StringBuilder()
        val reader = stdoutReader ?: throw VfsException("shell 流未就绪")
        while (true) {
            val line = reader.readLine() ?: throw VfsException("shell 流意外结束")
            if (line.startsWith(marker)) {
                val code = line.substringAfter(marker).trim().toIntOrNull() ?: -1
                if (code != 0 && !noThrow) throw VfsException("命令失败($code): $command")
                return sb.toString()
            }
            sb.appendLine(line)
        }
    }

    /** 带超时的可用性探测。 */
    fun probe(): Boolean = try {
        exec("true", noThrow = false) == ""
    } catch (e: Exception) {
        false
    }

    fun close() {
        try { process?.outputStream?.write("exit\n".toByteArray()) } catch (_: Throwable) {}
        try { process?.outputStream?.flush() } catch (_: Throwable) {}
        try { process?.waitForCompat(1000) } catch (_: Throwable) {}
        process?.destroy()
        process = null
        stderrDrain?.interrupt()
        stderrDrain = null
    }
}

/** shell 命令互斥执行：同一会话同一时刻只允许一条命令。 */
suspend fun <T> ShellProvider.withSession(block: ShellSession.() -> T): T =
    withContext(Dispatchers.IO) {
        synchronized(session) { block(session) }
    }

/** shell 工具：单引号转义路径。 */
fun shQuote(path: String): String = "'" + path.replace("'", "'\\''") + "'"

/**
 * 兼容所有 Android 版本的带超时 waitFor。
 * Process.waitFor(timeout, unit) 为 API 26+ 才有的方法，低版本设备会抛 NoSuchMethodError
 * （且它是 Error，catch Exception 拦不住）。这里用轮询 exitValue 实现。
 * @return true=进程已退出；false=超时仍在运行（不销毁，由调用方决定）。
 */
fun Process.waitForCompat(timeoutMs: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        try {
            exitValue()
            return true
        } catch (_: IllegalThreadStateException) {
            // 进程仍在运行，继续等待
        }
        if (System.currentTimeMillis() >= deadline) return false
        try {
            Thread.sleep(50)
        } catch (_: InterruptedException) {
            return false
        }
    }
}

/** 全局会话表（供引擎按 provider 获取）。 */
object Shells {
    private val map = ConcurrentHashMap<String, ShellProvider>()
    fun get(id: String): ShellProvider? = when (id) {
        "root" -> SuProvider
        "shizuku" -> ShizukuProvider
        else -> null
    }
}
