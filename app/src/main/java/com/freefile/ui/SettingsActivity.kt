package com.freefile.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.freefile.FreeFileApp
import com.freefile.core.permission.PermissionMode
import com.freefile.ui.settings.SettingsScreen
import com.freefile.ui.theme.FreeFileTheme
import kotlinx.coroutines.launch

/**
 * 设置 Activity：独立任务栈运行。
 * 权限管理复用应用全局的 PermissionManager（FreeFileApp.instance.permissionManager）。
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissionManager = FreeFileApp.instance.permissionManager
        setContent {
            val permSnapshot by permissionManager.snapshot.collectAsState()
            FreeFileTheme {
                SettingsScreen(
                    permSnapshot = permSnapshot,
                    onBack = { finish() },
                    onMessage = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() },
                    onSetMode = { mode: PermissionMode ->
                        permissionManager.setMode(mode)
                        lifecycleScope.launch { permissionManager.refresh() }
                    },
                    onRefresh = { lifecycleScope.launch { permissionManager.refresh() } }
                )
            }
        }
        // 进入设置页时刷新一次权限快照
        lifecycleScope.launch { permissionManager.refresh() }
    }
}
