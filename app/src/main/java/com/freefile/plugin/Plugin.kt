package com.freefile.plugin

import android.content.Context
import androidx.compose.runtime.Composable

/**
 * 插件契约：逆向、网络、终端、AI 等重型功能以插件形式动态加载，保持核心轻量。
 *
 * 生命周期由 [PluginManager] 统一管理：
 * load() -> onCreate() -> onResume() -> onPause() -> onDestroy() -> unload()
 */
interface Plugin {

    /** 插件唯一标识（如 "reverse"、"network"、"ai"）。 */
    val id: String

    /** 展示名称。 */
    val name: String

    /** 版本号（语义化版本字符串）。 */
    val version: String

    /** 简短描述。 */
    val description: String

    /**
     * 是否已加载并可用。核心据此决定 UI 是否暴露该插件的入口。
     */
    val loaded: Boolean

    /**
     * 加载插件（分配独立 DexClassLoader/资源、注册组件）。
     * 幂等：重复调用应安全返回。
     */
    suspend fun load(context: Context)

    /** 卸载插件，释放资源与引用，防止类加载器内存泄漏。 */
    suspend fun unload(context: Context)

    // ---- 生命周期回调（仅当 loaded 为 true 时由 PluginManager 调用）----

    fun onCreate() {}
    fun onResume() {}
    fun onPause() {}
    fun onDestroy() {}

    /**
     * 插件提供的入口界面（可选）。宿主通过反射或直接调用渲染。
     * 宿主不实现该 Composable，由各插件自行提供具体实现。
     */
    @Composable
    fun Content() {
        // 默认空实现，插件按需覆盖
    }
}

/** 插件元数据，用于未加载时展示入口与说明。 */
data class PluginMeta(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val entryClass: String
)
