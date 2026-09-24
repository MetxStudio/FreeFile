package com.freefile.terminal

import android.content.Context
import com.freefile.shell.SuProvider
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

/**
 * 终端会话工厂（支持多解释器：系统 sh / Root su / Termux bash / 自定义路径）。
 *
 * 模式说明：
 *  - [Mode.NORMAL] : `/system/bin/sh -l`，Android 自带的 mksh/toybox sh。
 *  - [Mode.ROOT]   : `su`，需 Root 可用，以 root 身份启动 shell。
 *  - [Mode.TERMUX] : Termux 官方 bash（`/data/data/com.termux/files/usr/bin/bash`），
 *                    带完整 Termux 环境（PREFIX / PATH / HOME 等），需 Root 才能跨应用
 *                    访问 Termux 私有数据目录；bash 以 `--login` 启动，会自动 source
 *                    Termux 的 /etc/profile、~/.bashrc，从而获得完整的包管理器与命令。
 *  - [Mode.CUSTOM] : 用户自定义解释器路径（如 `/system/bin/bash`、`/data/local/tmp/zsh`），
 *                    可附带自定义参数与环境变量。
 *
 * v0.118.0 API：
 *   TerminalSession(shellPath, cwd, args, env, transcriptRows, client)
 */
object TerminalManager {

    /** 终端模式。 */
    enum class Mode { NORMAL, ROOT, TERMUX, CUSTOM }

    // ==================== Termux 安装路径常量 ====================

    /** Termux 的 PREFIX（usr 目录）。 */
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"

    /** Termux bash 可执行文件路径。 */
    private const val TERMUX_BASH = "$TERMUX_PREFIX/bin/bash"

    /** Termux 用户 HOME 目录。 */
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"

    /** Termux 临时目录。 */
    private const val TERMUX_TMP = "$TERMUX_PREFIX/tmp"

    /**
     * 创建一个独立终端会话。
     *
     * @param mode        解释器模式
     * @param customShell [Mode.CUSTOM] 时必填，解释器可执行文件绝对路径
     * @param customArgs  [Mode.CUSTOM] 时可选，传递给解释器的参数
     * @param customEnv   [Mode.CUSTOM] 时可选，自定义环境变量数组（"KEY=VALUE"）
     */
    fun createSession(
        context: Context,
        mode: Mode,
        client: TerminalSessionClient,
        rows: Int = 24,
        cols: Int = 80,
        customShell: String? = null,
        customArgs: Array<String> = emptyArray(),
        customEnv: Array<String>? = null
    ): TerminalSession {
        val shell: String
        val args: Array<String>
        val env: Array<String>
        val cwd: String

        when (mode) {
            Mode.NORMAL -> {
                shell = "/system/bin/sh"
                args = arrayOf("-l")
                env = buildSystemEnv(context)
                cwd = context.filesDir.absolutePath
            }

            Mode.ROOT -> {
                if (!SuProvider.isReady()) throw IllegalStateException("Root 不可用")
                shell = "su"                       // execvp 按 PATH 查找
                args = arrayOf()
                env = buildSystemEnv(context)
                cwd = context.filesDir.absolutePath
            }

            Mode.TERMUX -> {
                // Termux bash 位于 Termux 私有数据目录，跨应用访问需要 Root。
                if (!SuProvider.isReady()) {
                    throw IllegalStateException("使用 Termux bash 需要 Root 权限")
                }
                if (!File(TERMUX_BASH).exists()) {
                    throw IllegalStateException("未检测到 Termux 安装（$TERMUX_BASH 不存在）")
                }
                // 通过 su 启动 termux bash，并在命令行中导出完整 Termux 环境。
                // 使用 `exec bash --login` 让 bash 读取 /etc/profile 与 ~/.bashrc，
                // 从而获得 apt、pkg、以及所有已安装命令。
                shell = "su"
                args = arrayOf(
                    "-c",
                    "export PREFIX=$TERMUX_PREFIX " +
                        "HOME=$TERMUX_HOME " +
                        "PATH=$TERMUX_PREFIX/bin:$TERMUX_PREFIX/bin/applets:/system/bin:/system/xbin " +
                        "LANG=en_US.UTF-8 " +
                        "TERM=xterm-256color " +
                        "TMPDIR=$TERMUX_TMP; " +
                        "exec $TERMUX_BASH --login"
                )
                env = buildTermuxEnv(context)
                cwd = TERMUX_HOME
            }

            Mode.CUSTOM -> {
                val path = customShell?.trim()
                if (path.isNullOrEmpty()) {
                    throw IllegalArgumentException("自定义解释器路径不能为空")
                }
                val f = File(path)
                if (!f.exists()) {
                    throw IllegalStateException("解释器不存在：$path")
                }
                shell = path
                args = customArgs
                env = customEnv ?: buildSystemEnv(context)
                // cwd 优先取解释器所在目录的父级，否则用 filesDir
                cwd = f.parentFile?.absolutePath ?: context.filesDir.absolutePath
            }
        }

        // v0.118.0 签名: (shellPath, cwd, args, env, transcriptRows, client)
        val s = TerminalSession(shell, cwd, args, env, 2000, client)
        s.updateSize(cols, rows)
        return s
    }

    /**
     * 检测 Termux 是否已安装（bash 可执行文件是否存在）。
     * 注意：无 Root 时本检测可能因权限不足返回 false（即使 Termux 已安装）。
     */
    fun isTermuxInstalled(): Boolean {
        return runCatching { File(TERMUX_BASH).exists() }.getOrDefault(false)
    }

    // ==================== 环境变量构建 ====================

    /** 系统 sh / Root / 自定义模式的默认环境。 */
    private fun buildSystemEnv(context: Context): Array<String> {
        val filesDir = context.filesDir.absolutePath
        val cacheDir = context.cacheDir.absolutePath
        return arrayOf(
            "PATH=/system/bin:/system/xbin:/vendor/bin:/data/local/tmp",
            "HOME=$filesDir",
            "TMPDIR=$cacheDir",
            "TERM=xterm-256color",
            "LANG=en_US.UTF-8",
            "PUBLIC=/storage/emulated/0"
        )
    }

    /**
     * Termux 模式的完整环境变量。
     * 这些变量会通过 su 进程传递；同时命令行也会再 export 一次作为兜底。
     */
    private fun buildTermuxEnv(context: Context): Array<String> {
        return arrayOf(
            "PREFIX=$TERMUX_PREFIX",
            "HOME=$TERMUX_HOME",
            "PATH=$TERMUX_PREFIX/bin:$TERMUX_PREFIX/bin/applets:/system/bin:/system/xbin:/vendor/bin",
            "TMPDIR=$TERMUX_TMP",
            "TERM=xterm-256color",
            "LANG=en_US.UTF-8",
            "EXTERNAL_STORAGE=/sdcard",
            "ANDROID_ROOT=/system",
            "ANDROID_DATA=/data",
            "PWD=$TERMUX_HOME"
        )
    }
}
