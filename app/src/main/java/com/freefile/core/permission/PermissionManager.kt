package com.freefile.core.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.freefile.shell.SuProvider
import com.freefile.vfs.AccessMode
import com.freefile.vfs.EngineSelector
import rikka.shizuku.Shizuku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** 应用权限模式（对外暴露的抽象，与 AccessMode 引擎策略解耦）。 */
enum class PermissionMode { AUTO, ROOT, SHIZUKU, LOCAL_ONLY }

/**
 * 权限管理器：
 * - MANAGE_EXTERNAL_STORAGE（所有文件访问）申请与状态
 * - Root 可用性检测（su）
 * - Shizuku 连接/授权检测
 * - 自动降级：Root > Shizuku > 本地（SAF 兜底）
 */
class PermissionManager(private val context: Context) {

    data class Snapshot(
        val storageGranted: Boolean,
        val rootAvailable: Boolean,
        val shizukuAvailable: Boolean,
        val mode: PermissionMode,
        val effectiveEngine: String // local/root/shizuku/none
    )

    private val _snapshot = MutableStateFlow(
        Snapshot(false, false, false, PermissionMode.AUTO, "none")
    )
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    /** 是否已授予「所有文件访问」。 */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()

    /** 传统 READ_EXTERNAL_STORAGE 状态（低版本设备）。 */
    fun hasLegacyStorage(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

    /** 刷新整体快照（检测 Root/Shizuku 有一定开销，放在 IO）。 */
    suspend fun refresh(): Snapshot = withContext(Dispatchers.IO) {
        val storage = hasAllFilesAccess() || hasLegacyStorage()
        val root = try { SuProvider.isReady() } catch (e: Exception) { false }
        val shizuku = try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) { false }
        val mode = _snapshot.value.mode
        val engine = when {
            mode == PermissionMode.ROOT && root -> "root"
            mode == PermissionMode.SHIZUKU && shizuku -> "shizuku"
            mode == PermissionMode.LOCAL_ONLY -> if (storage) "local" else "none"
            root -> "root"
            shizuku -> "shizuku"
            storage -> "local"
            else -> "none"
        }
        val s = Snapshot(storage, root, shizuku, mode, engine)
        _snapshot.value = s
        // 同步引擎选择策略
        EngineSelector.setMode(
            when (mode) {
                PermissionMode.AUTO -> AccessMode.AUTO
                PermissionMode.ROOT -> AccessMode.ROOT_FIRST
                PermissionMode.SHIZUKU -> AccessMode.SHIZUKU_FIRST
                PermissionMode.LOCAL_ONLY -> AccessMode.LOCAL_ONLY
            }
        )
        s
    }

    fun setMode(mode: PermissionMode) {
        _snapshot.value = _snapshot.value.copy(mode = mode)
        EngineSelector.setMode(
            when (mode) {
                PermissionMode.AUTO -> AccessMode.AUTO
                PermissionMode.ROOT -> AccessMode.ROOT_FIRST
                PermissionMode.SHIZUKU -> AccessMode.SHIZUKU_FIRST
                PermissionMode.LOCAL_ONLY -> AccessMode.LOCAL_ONLY
            }
        )
    }

    companion object {
        /** 构建「所有文件访问」设置页跳转 Intent。 */
        fun allFilesAccessIntent(context: Context): android.content.Intent {
            val uri = android.net.Uri.fromParts("package", context.packageName, null)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    uri
                ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
            } else {
                android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    uri
                ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
            }
        }
    }
}
