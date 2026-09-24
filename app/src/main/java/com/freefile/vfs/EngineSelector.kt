package com.freefile.vfs

import com.freefile.shell.SuProvider
import com.freefile.shell.ShizukuProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 权限/引擎模式：用户可手动指定，默认自动。
 * 自动策略：路径用 File 可直达 → Local；不可直达 → Root > Shizuku > 抛错引导授权。
 */
enum class AccessMode { AUTO, ROOT_FIRST, SHIZUKU_FIRST, LOCAL_ONLY }

/**
 * 引擎选择器：统一入口。按路径与当前可用引擎自动选择最优引擎，
 * 并记忆「路径前缀 → 引擎」映射，避免重复探测。
 */
object EngineSelector {

    private val rootEngine by lazy { ShellEngine(SuProvider) }
    private val shizukuEngine by lazy { ShellEngine(ShizukuProvider) }

    private val engineCache = HashMap<String, FileEngine>()

    private val _mode = MutableStateFlow(AccessMode.AUTO)
    val mode: StateFlow<AccessMode> = _mode.asStateFlow()

    fun setMode(m: AccessMode) {
        _mode.value = m
        synchronized(engineCache) { engineCache.clear() }
    }

    /** 按优先级返回当前可用的特权引擎（Root 优先）。 */
    fun availablePrivilegedEngines(): List<FileEngine> {
        val list = mutableListOf<FileEngine>()
        if (rootEngine.isAvailable()) list.add(rootEngine)
        if (shizukuEngine.isAvailable()) list.add(shizukuEngine)
        return list
    }

    fun rootAvailable(): Boolean = rootEngine.isAvailable()
    fun shizukuAvailable(): Boolean = shizukuEngine.isAvailable()

    /** 为路径选择引擎。 */
    fun select(path: String): FileEngine {
        // 网络位置（Sprint 5）→ RemoteEngine 专门处理
        if (com.freefile.vfs.remote.RemoteEngine.isRemotePath(path)) {
            return com.freefile.vfs.remote.RemoteEngine
        }
        // 压缩包虚拟路径（含 '#'）→ ArchiveEngine 专门处理
        if (ArchiveEngine.isArchivePath(path)) return ArchiveEngine
        synchronized(engineCache) {
            engineCache[path]?.let { return it }
        }
        val engine = when (_mode.value) {
            AccessMode.LOCAL_ONLY -> LocalEngine
            AccessMode.ROOT_FIRST -> pickPrivileged(rootFirst = true) ?: LocalEngine
            AccessMode.SHIZUKU_FIRST -> pickPrivileged(rootFirst = false) ?: LocalEngine
            AccessMode.AUTO -> {
                if (LocalEngine.canAccess(path)) LocalEngine
                else pickPrivileged(rootFirst = true) ?: LocalEngine
            }
        }
        // 缓存按目录前缀（第一级）记忆，减少探测
        val cacheKey = path.substringBefore('/', path).ifEmpty { "/" }
        synchronized(engineCache) { engineCache[cacheKey] = engine }
        return engine
    }

    private fun pickPrivileged(rootFirst: Boolean): FileEngine? {
        val engines = if (rootFirst) {
            listOf(rootEngine, shizukuEngine)
        } else {
            listOf(shizukuEngine, rootEngine)
        }
        return engines.firstOrNull { it.isAvailable() }
    }
}
