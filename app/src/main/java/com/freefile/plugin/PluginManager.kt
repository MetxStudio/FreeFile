package com.freefile.plugin

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 插件管理器：负责插件注册、加载、卸载与生命周期驱动。
 *
 * 当前为内置实现骨架，插件以依赖注入方式注册；
 * Sprint 6 将替换为基于 DexClassLoader 的动态加载方案
 * （独立 ClassLoader + AssetManager.addAssetPath 资源隔离）。
 */
class PluginManager(private val context: Context) {

    private val mutex = Mutex()

    private val _plugins: MutableStateFlow<List<Plugin>> = MutableStateFlow(emptyList())

    /** 所有已注册插件。 */
    val plugins: StateFlow<List<Plugin>> = _plugins.asStateFlow()

    private val _loadedPlugins: MutableStateFlow<List<Plugin>> = MutableStateFlow(emptyList())

    /** 已加载（可用）的插件，UI 据此显示入口。 */
    val loadedPlugins: StateFlow<List<Plugin>> = _loadedPlugins.asStateFlow()

    suspend fun register(plugin: Plugin) = mutex.withLock {
        if (_plugins.value.none { it.id == plugin.id }) {
            _plugins.update { it + plugin }
            refreshLoaded()
        }
    }

    suspend fun unregister(id: String) = mutex.withLock {
        val target = _plugins.value.firstOrNull { it.id == id } ?: return@withLock
        if (target.loaded) target.unload(context)
        _plugins.update { it.filterNot { p -> p.id == id } }
        refreshLoaded()
    }

    suspend fun load(id: String) = mutex.withLock {
        val target = _plugins.value.firstOrNull { it.id == id } ?: return@withLock
        if (!target.loaded) {
            target.load(context)
            target.onCreate()
            refreshLoaded()
        }
    }

    suspend fun unload(id: String) = mutex.withLock {
        val target = _plugins.value.firstOrNull { it.id == id } ?: return@withLock
        if (target.loaded) {
            target.onDestroy()
            target.unload(context)
            refreshLoaded()
        }
    }

    suspend fun onResume() = mutex.withLock {
        _plugins.value.filter { it.loaded }.forEach { it.onResume() }
    }

    suspend fun onPause() = mutex.withLock {
        _plugins.value.filter { it.loaded }.forEach { it.onPause() }
    }

    fun get(id: String): Plugin? = _plugins.value.firstOrNull { it.id == id }

    private fun refreshLoaded() {
        _loadedPlugins.value = _plugins.value.filter { it.loaded }
    }
}
