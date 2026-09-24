package com.freefile.script

import com.freefile.shell.SuProvider
import com.freefile.shell.waitForCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Python 脚本引擎（Sprint 5）。
 * 本机未内嵌 CPython（Chaquopy 需要构建期 Python 3.8+ 环境），
 * 改为调用设备上可用的 python3 解释器：
 * - Root 环境：su -c python3 …
 * - 普通 shell：直接执行 python3（部分 ROM / Termux 前缀可用）
 * 执行前先探测解释器；不可用时返回明确提示。
 */
object PythonEngine {

    data class Interpreter(val command: String, val viaRoot: Boolean, val version: String)

    /** 探测可用解释器：优先普通 shell，其次 Root。 */
    suspend fun detect(): Interpreter? = withContext(Dispatchers.IO) {
        // 1) 普通 shell
        probeLocal()?.let { return@withContext it }
        // 2) Root shell
        if (SuProvider.isReady()) {
            val out = runCatching {
                SuProvider.session.exec("command -v python3 && python3 --version", noThrow = true)
            }.getOrNull()
            if (!out.isNullOrBlank() && out.contains("Python 3")) {
                return@withContext Interpreter("python3", viaRoot = true, version = out.trim())
            }
        }
        null
    }

    private fun probeLocal(): Interpreter? = try {
        val p = Runtime.getRuntime().exec(arrayOf("python3", "--version"))
        val ok = p.waitForCompat(3000)
        if (!ok) { p.destroy(); null }
        else {
            val text = p.inputStream.bufferedReader().readText() +
                p.errorStream.bufferedReader().readText()
            if (text.contains("Python 3")) Interpreter("python3", viaRoot = false, version = text.trim())
            else null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * 执行 Python 脚本文件，流式输出到 onOutput。
     * @return 退出码（探测失败时返回 -1 并输出提示）
     */
    suspend fun runScript(
        scriptFile: File,
        onOutput: (String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val interpreter = detect()
        if (interpreter == null) {
            onOutput("[未找到 python3 解释器]")
            onOutput("请安装后重试：Root 用户可通过 Magisk 模块安装，或使用 Termux 环境。")
            return@withContext -1
        }
        onOutput("[解释器] ${interpreter.version} (${if (interpreter.viaRoot) "Root" else "本地"})")

        val process = if (interpreter.viaRoot) {
            Runtime.getRuntime().exec(
                arrayOf("su", "-c", "python3 ${shellQuote(scriptFile.absolutePath)}")
            )
        } else {
            Runtime.getRuntime().exec(arrayOf("python3", scriptFile.absolutePath))
        }

        // 流式读取 stdout/stderr
        val outThread = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { onOutput(it) }
            } catch (_: Exception) {}
        }
        val errThread = Thread {
            try {
                process.errorStream.bufferedReader().forEachLine { onOutput("[stderr] $it") }
            } catch (_: Exception) {}
        }
        outThread.isDaemon = true
        errThread.isDaemon = true
        outThread.start()
        errThread.start()

        val exit = try {
            process.waitFor()
            outThread.join(3000)
            errThread.join(3000)
            process.exitValue()
        } catch (e: Exception) {
            process.destroy()
            -1
        }
        exit
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
