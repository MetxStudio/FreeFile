package com.freefile.ui.settings

import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Handyman
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.freefile.R
import com.freefile.core.permission.PermissionManager
import com.freefile.core.permission.PermissionMode
import com.freefile.data.settings.AppSettings
import com.freefile.search.IndexState
import com.freefile.search.SearchEngine
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * 统一设置页（需求 3）：Shizuku 权限、用户偏好、多语言等集中配置。
 * 所有修改写入 AppSettings（DataStore），即时生效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    permSnapshot: PermissionManager.Snapshot,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
    onSetMode: (PermissionMode) -> Unit = {},
    onRefresh: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by AppSettings.state.collectAsState()
    val indexState by SearchEngine.indexState.collectAsState()

    var showLangDialog by remember { mutableStateOf(false) }
    var showDepthDialog by remember { mutableStateOf(false) }

    // Shizuku 权限请求结果监听（需求 3：设置页内请求 Shizuku 授权）
    val shizukuListener = remember {
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                onMessage(
                    context.getString(
                        if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED)
                            R.string.settings_shizuku_granted
                        else R.string.settings_shizuku_not_granted
                    )
                )
                onRefresh()
            }
        }
    }
    DisposableEffect(Unit) {
        Shizuku.addRequestPermissionResultListener(shizukuListener)
        onDispose { Shizuku.removeRequestPermissionResultListener(shizukuListener) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.close)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                stringResource(R.string.settings_applied_immediately),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            // ==================== 通用 ====================
            SectionHeader(Icons.Rounded.Tune, stringResource(R.string.settings_general))
            SwitchRow(
                icon = { Icon(Icons.Rounded.Visibility, null, Modifier.size(22.dp)) },
                title = stringResource(R.string.settings_show_hidden),
                subtitle = stringResource(R.string.settings_show_hidden_desc),
                checked = settings.showHidden
            ) { scope.launch { AppSettings.setShowHidden(it) } }
            SwitchRow(
                icon = { Icon(Icons.Rounded.Storage, null, Modifier.size(22.dp)) },
                title = stringResource(R.string.settings_compute_sizes),
                subtitle = stringResource(R.string.settings_compute_sizes_desc),
                checked = settings.computeSizes
            ) { scope.launch { AppSettings.setComputeSizes(it) } }

            SectionDivider()

            // ==================== 索引缓存 ====================
            SectionHeader(Icons.Rounded.ManageSearch, stringResource(R.string.settings_index))
            SwitchRow(
                icon = { Icon(Icons.Rounded.Handyman, null, Modifier.size(22.dp)) },
                title = stringResource(R.string.settings_index),
                subtitle = stringResource(R.string.settings_index_desc),
                checked = settings.indexEnabled
            ) { enabled ->
                scope.launch {
                    AppSettings.setIndexEnabled(enabled)
                    if (enabled) {
                        onMessage(context.getString(R.string.msg_index_started))
                        SearchEngine.buildIndex(context)
                    } else {
                        SearchEngine.clearIndex(context)
                    }
                }
            }
            val indexText = when (val st = indexState) {
                is IndexState.Building ->
                    context.getString(R.string.search_index_building) + " (${st.scanned})"
                is IndexState.Ready ->
                    context.getString(R.string.search_index_done, st.total)
                else -> ""
            }
            if (indexText.isNotEmpty()) {
                Text(
                    indexText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = {
                    scope.launch {
                        onMessage(context.getString(R.string.msg_index_started))
                        SearchEngine.buildIndex(context)
                    }
                }) { Text(stringResource(R.string.search_index_build)) }
            }

            SectionDivider()

            // ==================== 搜索 ====================
            SectionHeader(Icons.Rounded.Search, stringResource(R.string.settings_search))
            ClickRow(
                icon = { Icon(Icons.Rounded.ManageSearch, null, Modifier.size(22.dp)) },
                title = stringResource(R.string.settings_search_depth),
                subtitle = if (settings.searchDepth <= 0)
                    stringResource(R.string.search_depth_only_current)
                else stringResource(R.string.search_depth_n, settings.searchDepth),
                onClick = { showDepthDialog = true }
            )
            SwitchRow(
                icon = { Icon(Icons.Rounded.Code, null, Modifier.size(22.dp)) },
                title = stringResource(R.string.settings_search_regex),
                subtitle = null,
                checked = settings.searchRegex
            ) { scope.launch { AppSettings.setSearchRegex(it) } }
            SwitchRow(
                icon = { Icon(Icons.Rounded.TextFields, null, Modifier.size(22.dp)) },
                title = stringResource(R.string.settings_search_exact),
                subtitle = null,
                checked = settings.searchExact
            ) { scope.launch { AppSettings.setSearchExact(it) } }

            SectionDivider()

            // ==================== 权限 ====================
            SectionHeader(Icons.Rounded.Shield, stringResource(R.string.settings_permission))

            // 所有文件访问
            ClickRow(
                icon = { Icon(Icons.Rounded.Storage, null, Modifier.size(22.dp)) },
                title = stringResource(R.string.settings_all_files_access),
                subtitle = stringResource(
                    if (permSnapshot.storageGranted) R.string.settings_granted
                    else R.string.settings_not_granted
                ),
                onClick = {
                    runCatching {
                        context.startActivity(PermissionManager.allFilesAccessIntent(context))
                    }
                }
            )

            // 访问模式
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(
                    stringResource(R.string.drawer_access_mode),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModeChip(stringResource(R.string.mode_auto), permSnapshot.mode == PermissionMode.AUTO) {
                        onSetMode(PermissionMode.AUTO)
                    }
                    ModeChip(stringResource(R.string.mode_root), permSnapshot.mode == PermissionMode.ROOT) {
                        onSetMode(PermissionMode.ROOT)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModeChip(stringResource(R.string.mode_shizuku), permSnapshot.mode == PermissionMode.SHIZUKU) {
                        onSetMode(PermissionMode.SHIZUKU)
                    }
                    ModeChip(stringResource(R.string.mode_local), permSnapshot.mode == PermissionMode.LOCAL_ONLY) {
                        onSetMode(PermissionMode.LOCAL_ONLY)
                    }
                }
                Text(
                    stringResource(R.string.settings_effective_engine, permSnapshot.effectiveEngine),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            // Shizuku 授权
            ClickRow(
                icon = { Icon(Icons.Rounded.Shield, null, Modifier.size(22.dp)) },
                title = stringResource(R.string.settings_shizuku),
                subtitle = stringResource(
                    if (permSnapshot.shizukuAvailable) R.string.settings_shizuku_granted
                    else R.string.settings_shizuku_not_granted
                ) + "\n" + stringResource(R.string.settings_shizuku_hint),
                onClick = {
                    try {
                        if (Shizuku.pingBinder()) {
                            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
                        } else {
                            onMessage(context.getString(R.string.settings_shizuku_hint))
                        }
                    } catch (e: Exception) {
                        onMessage(context.getString(R.string.settings_shizuku_hint))
                    }
                }
            )

            SectionDivider()

            // ==================== 语言 ====================
            SectionHeader(Icons.Rounded.Language, stringResource(R.string.settings_language))
            ClickRow(
                icon = { Icon(Icons.Rounded.Language, null, Modifier.size(22.dp)) },
                title = stringResource(R.string.settings_language),
                subtitle = currentLanguageLabel(),
                onClick = { showLangDialog = true }
            )

            SectionDivider()

            // ==================== 关于 ====================
            SectionHeader(Icons.Rounded.Info, stringResource(R.string.settings_about))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Settings, null, Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.settings_about_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // ---------- 语言选择对话框（即时生效） ----------
    if (showLangDialog) {
        val tags = listOf(
            "" to stringResource(R.string.settings_lang_system),
            "zh-CN" to stringResource(R.string.settings_lang_zh),
            "en" to stringResource(R.string.settings_lang_en)
        )
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        AlertDialog(
            onDismissRequest = { showLangDialog = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column {
                    tags.forEach { (tag, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    applyLanguage(tag)
                                    showLangDialog = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = tag == current ||
                                    (tag.isEmpty() && current.isBlank()),
                                onClick = {
                                    applyLanguage(tag)
                                    showLangDialog = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLangDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    // ---------- 搜索深度选择对话框（即时生效） ----------
    if (showDepthDialog) {
        val depths = listOf(0, 1, 2, 3, 5, 10)
        AlertDialog(
            onDismissRequest = { showDepthDialog = false },
            title = { Text(stringResource(R.string.settings_search_depth)) },
            text = {
                Column {
                    depths.forEach { d ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        AppSettings.setSearchDepth(d)
                                    }
                                    showDepthDialog = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.searchDepth == d,
                                onClick = {
                                    scope.launch { AppSettings.setSearchDepth(d) }
                                    showDepthDialog = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (d <= 0) stringResource(R.string.search_depth_only_current)
                                else stringResource(R.string.search_depth_n, d)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDepthDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }
}

// ==================== 辅助 ====================

private const val SHIZUKU_REQUEST_CODE = 1201

/** 应用 per-app language（API 33+ 原生；低版本由 AppCompatDelegate 处理，即时生效）。 */
private fun applyLanguage(tag: String) {
    try {
        AppCompatDelegate.setApplicationLocales(
            if (tag.isBlank()) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag)
        )
    } catch (_: Exception) {
    }
}

@Composable
private fun currentLanguageLabel(): String {
    val locales = AppCompatDelegate.getApplicationLocales()
    return when {
        locales.isEmpty -> stringResource(R.string.settings_lang_system)
        else -> locales.toLanguageTags()
    }
}

@Composable
private fun SectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun SectionDivider() {
    Divider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

@Composable
private fun SwitchRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ClickRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) }
    )
}
