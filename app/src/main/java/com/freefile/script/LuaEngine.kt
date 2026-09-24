package com.freefile.script

import com.freefile.vfs.EngineSelector
import com.freefile.vfs.joinPath
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.jse.JsePlatform
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.Executors

/**
 * Lua 脚本引擎：luaj（纯 Java Lua 5.2）。
 * 绑定 freefile API，可直接操作任意 VFS 路径（本地/Root/网络）。
 *
 * 关键修复（2026-09-06 死机问题）：
 *  - VFS 接口为 suspend，Lua 回调为同步，原实现用 `withContext(Dispatchers.IO) + runBlocking`
 *    桥接，导致 runBlocking 嵌套在 IO 协程内 → IO 线程池被阻塞 → 死锁。
 *  - 现改为「专用单线程执行器 + runBlocking」：Lua 整段在专用线程 runBlocking 中执行，
 *    VFS 回调内的 runBlocking 同样阻塞该专用线程，而 VFS 内部 suspend 走 Dispatchers.IO
 *    （独立线程池），不再产生死锁。
 *
 * 示例：
 *   local files = freefile.list("/storage/emulated/0")
 *   for i, f in ipairs(files) do freefile.log(f.name) end
 */
object LuaEngine {

    /** 专用执行线程：Lua 同步回调需要阻塞调用 suspend VFS，必须与协程 IO 池隔离。 */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lua-engine").apply { isDaemon = true }
    }

    /** 执行 Lua 代码。onOutput 接收 print/freefile.log 输出行。 */
    suspend fun run(code: String, onOutput: (String) -> Unit) {
        val done = CompletableDeferred<Unit>()
        executor.execute {
            try {
                // 整段 Lua 在 runBlocking 中运行，VFS 回调内的 runBlocking 不再嵌套在
                // Dispatchers.IO 协程内，避免死锁。
                kotlinx.coroutines.runBlocking {
                    val globals: Globals = JsePlatform.standardGlobals()
                    bindFreeFile(globals, onOutput)

                    // print 重定向到输出面板
                    globals.set("print", object : OneArgFunction() {
                        override fun call(arg: LuaValue): LuaValue {
                            onOutput(arg.tojstring())
                            return LuaValue.NIL
                        }
                    })

                    try {
                        globals.load(code, "freefile_script").call()
                        onOutput("[Exec Finish]")
                    } catch (e: Exception) {
                        onOutput("[Lua Err] ${e.message}")
                    }
                }
            } catch (t: Throwable) {
                onOutput("[Lua Fatal Err] ${t.message ?: t::class.simpleName}")
            } finally {
                done.complete(Unit)
            }
        }
        done.await()
    }

    private fun bindFreeFile(globals: Globals, onOutput: (String) -> Unit) {
        val ff = LuaTable()

        // luaj 回调非 suspend：VFS suspend 方法用 runBlocking 桥接。
        // 由于 run() 整体在专用线程的 runBlocking 中执行，这里的 runBlocking
        // 阻塞的是同一专用线程，VFS 内部走 Dispatchers.IO（独立池），不会死锁。
        fun vfs() = EngineSelector

        // freefile.list(path) → {{name=,isdir=,size=}, ...}
        ff.set("list", object : OneArgFunction() {
            override fun call(path: LuaValue): LuaValue {
                val p = path.checkjstring()
                val entries = kotlinx.coroutines.runBlocking {
                    vfs().select(p).list(p)
                }
                val table = LuaTable()
                entries.forEachIndexed { i, e ->
                    val t = LuaTable()
                    t.set("name", LuaValue.valueOf(e.name))
                    t.set("path", LuaValue.valueOf(e.path))
                    t.set("isdir", LuaValue.valueOf(e.isDirectory))
                    t.set("size", LuaValue.valueOf(e.size.toDouble()))
                    table.set(i + 1, t)
                }
                return table
            }
        })

        // freefile.read(path) → string
        ff.set("read", object : OneArgFunction() {
            override fun call(path: LuaValue): LuaValue {
                val p = path.checkjstring()
                return vfs().select(p).openInputStream(p).use {
                    it.readBytes().toString(Charsets.UTF_8)
                }.let { LuaValue.valueOf(it) }
            }
        })

        // freefile.write(path, content)
        ff.set("write", object : TwoArgFunction() {
            override fun call(path: LuaValue, content: LuaValue): LuaValue {
                val p = path.checkjstring()
                vfs().select(p).openOutputStream(p).use {
                    it.write(content.checkjstring().toByteArray(Charsets.UTF_8))
                }
                return LuaValue.TRUE
            }
        })

        // freefile.copy(src, dst)
        ff.set("copy", object : TwoArgFunction() {
            override fun call(src: LuaValue, dst: LuaValue): LuaValue {
                kotlinx.coroutines.runBlocking {
                    copyDeep(src.checkjstring(), dst.checkjstring())
                }
                return LuaValue.TRUE
            }
        })

        // freefile.delete(path, recursive)
        ff.set("delete", object : TwoArgFunction() {
            override fun call(path: LuaValue, recursive: LuaValue): LuaValue {
                val p = path.checkjstring()
                kotlinx.coroutines.runBlocking {
                    vfs().select(p).delete(p, recursive.optboolean(false))
                }
                return LuaValue.TRUE
            }
        })

        // freefile.mkdir(path)
        ff.set("mkdir", object : OneArgFunction() {
            override fun call(path: LuaValue): LuaValue {
                val p = path.checkjstring()
                return LuaValue.valueOf(
                    kotlinx.coroutines.runBlocking { vfs().select(p).mkdirs(p) }
                )
            }
        })

        // freefile.rename(old, new)
        ff.set("rename", object : TwoArgFunction() {
            override fun call(oldPath: LuaValue, newPath: LuaValue): LuaValue {
                val p = oldPath.checkjstring()
                return LuaValue.valueOf(
                    kotlinx.coroutines.runBlocking { vfs().select(p).rename(p, newPath.checkjstring()) }
                )
            }
        })

        // freefile.exists(path)
        ff.set("exists", object : OneArgFunction() {
            override fun call(path: LuaValue): LuaValue {
                val p = path.checkjstring()
                return LuaValue.valueOf(
                    kotlinx.coroutines.runBlocking { vfs().select(p).exists(p) }
                )
            }
        })

        // freefile.log(msg)
        ff.set("log", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                onOutput(arg.tojstring())
                return LuaValue.NIL
            }
        })

        globals.set("freefile", ff)
    }

    private suspend fun copyDeep(src: String, dst: String) {
        val engine = EngineSelector.select(src)
        val dstEngine = EngineSelector.select(dst)
        val entry = engine.stat(src) ?: throw IllegalStateException("Source Not Found: $src")
        if (entry.isDirectory) {
            dstEngine.mkdirs(dst)
            for (child in engine.list(src)) {
                copyDeep(child.path, joinPath(dst, child.name))
            }
        } else {
            engine.openInputStream(src).use { input ->
                dstEngine.openOutputStream(dst).use { output -> input.copyTo(output) }
            }
        }
    }
}
