package com.freefile.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "freefile_settings")

/** 设置快照数据。 */
data class SettingsData(
    // 通用
    val showHidden: Boolean = false,
    val computeSizes: Boolean = false,   // 需求 8：文件大小按需计算，默认关
    // 视图模式（需求 3：列表/网格/大图标，跨窗格持久化）
    val viewMode: String = "LIST",
    // 索引缓存（需求 5）
    val indexEnabled: Boolean = false,
    val userAgreementAccepted: Boolean = false,
    // 搜索默认选项（需求 5）
    val searchDepth: Int = 0,            // 0 = 仅当前目录；-1 = 无限递归
    val searchRegex: Boolean = false,
    val searchWildcard: Boolean = false,
    val searchExact: Boolean = false
)

/**
 * 应用设置中心（DataStore 持久化，StateFlow 即时生效）。
 * 语言切换使用 AndroidX per-app language，不在此存储。
 */
object AppSettings {

    private val KEYS = mapOf(
        "showHidden" to booleanPreferencesKey("show_hidden"),
        "computeSizes" to booleanPreferencesKey("compute_sizes"),
        "viewMode" to stringPreferencesKey("view_mode"),
        "indexEnabled" to booleanPreferencesKey("index_enabled"),
        "userAgreementAccepted" to booleanPreferencesKey("user_agreement_accepted"),
        "searchDepth" to intPreferencesKey("search_depth"),
        "searchRegex" to booleanPreferencesKey("search_regex"),
        "searchWildcard" to booleanPreferencesKey("search_wildcard"),
        "searchExact" to booleanPreferencesKey("search_exact")
    )

    private lateinit var appContext: Context

    private val _state = MutableStateFlow(SettingsData())
    val state: StateFlow<SettingsData> = _state.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        // 同步预热一次当前值（首次组合立即拿到持久化值）
        runBlocking {
            val p = appContext.dataStore.data.first()
            _state.value = readSnapshot(p)
        }
        // 持续监听（任何写入即时推送到所有观察者）
        CoroutineScope(Dispatchers.Default).launch {
            appContext.dataStore.data.map { readSnapshot(it) }.collect { _state.value = it }
        }
    }

    private fun readSnapshot(p: Preferences): SettingsData = SettingsData(
        showHidden = p[KEYS["showHidden"] as Preferences.Key<Boolean>] ?: false,
        computeSizes = p[KEYS["computeSizes"] as Preferences.Key<Boolean>] ?: false,
        viewMode = p[KEYS["viewMode"] as Preferences.Key<String>] ?: "LIST",
        indexEnabled = p[KEYS["indexEnabled"] as Preferences.Key<Boolean>] ?: false,
        userAgreementAccepted = p[KEYS["userAgreementAccepted"] as Preferences.Key<Boolean>] ?: false,
        searchDepth = p[KEYS["searchDepth"] as Preferences.Key<Int>] ?: 0,
        searchRegex = p[KEYS["searchRegex"] as Preferences.Key<Boolean>] ?: false,
        searchWildcard = p[KEYS["searchWildcard"] as Preferences.Key<Boolean>] ?: false,
        searchExact = p[KEYS["searchExact"] as Preferences.Key<Boolean>] ?: false
    )

    suspend fun setShowHidden(v: Boolean) = put("showHidden", v)
    suspend fun setComputeSizes(v: Boolean) = put("computeSizes", v)
    suspend fun setViewMode(v: String) = put("viewMode", v)
    suspend fun setIndexEnabled(v: Boolean) = put("indexEnabled", v)
    suspend fun setUserAgreementAccepted(v: Boolean) = put("userAgreementAccepted", v)
    suspend fun setSearchDepth(v: Int) = put("searchDepth", v)
    suspend fun setSearchRegex(v: Boolean) = put("searchRegex", v)
    suspend fun setSearchWildcard(v: Boolean) = put("searchWildcard", v)
    suspend fun setSearchExact(v: Boolean) = put("searchExact", v)

    private suspend inline fun <reified T> put(key: String, value: T) {
        appContext.dataStore.edit { p ->
            @Suppress("UNCHECKED_CAST")
            p[KEYS[key] as Preferences.Key<T>] = value
        }
    }
}
