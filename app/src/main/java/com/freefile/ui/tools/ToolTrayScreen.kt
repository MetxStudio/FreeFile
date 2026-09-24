package com.freefile.ui.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GetApp
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.freefile.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 工具托盘页面（需求 5 + 任务 5 扩展）。
 *
 * 两种模式：
 *  - 默认模式（[pendingFilePath] == null）：读取 assets/apk/index.json 展示内置工具列表，
 *    按顺序执行三步：解压 → 安装 → 启动；启动 Intent 可在弹窗中覆盖。
 *  - 文件路由模式（[pendingFilePath] != null）：**仍显示工具列表**，但每次启动工具时，
 *    会把 [pendingFilePath] 对应的 File Uri 与 MIME 附加到工具启动 Intent，
 *    相当于"用工具托盘里的某个工具打开该文件"。
 *
 *    用户纠正：此路径不是「要安装的 APK」，而是「要继续作为 Intent 传给工具」的任意文件。
 *    APK 启动器逻辑移至独立入口（仅当用户明确从工具列表点"APK 分析器"等工具时被触发）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolTrayScreen(
    pendingFilePath: String? = null,
    onBack: () -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var tools by remember { mutableStateOf<List<ToolInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var parseError by remember { mutableStateOf<String?>(null) }
    var workingId by remember { mutableStateOf<String?>(null) }
    var stateVersion by remember { mutableStateOf(0) }
    var intentEditTarget by remember { mutableStateOf<ToolInfo?>(null) }

    // 工具列表模式（两种入口都走同一个列表）
    LaunchedEffect(Unit) {
        loading = true
        parseError = null
        try {
            val parsed = withContext(Dispatchers.IO) { parseIndex(context) }
            tools = parsed
        } catch (e: Exception) {
            parseError = e.message ?: e.toString()
        } finally {
            loading = false
        }
    }

    fun toast(msg: String) = scope.launch { snackbar.showSnackbar(msg) }

    // 把待路由文件的 MIME/Uri 附加到工具启动 Intent（文件路由模式）。
    fun attachPendingFileToIntent(intent: android.content.Intent) {
        val path = pendingFilePath ?: return
        val file = File(path)
        if (!file.exists()) return
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(
                file.extension.lowercase()
            ) ?: "*/*"
        try {
            val uri: android.net.Uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            intent.setDataAndType(uri, mime)
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Throwable) {
            // 无 FileProvider 时退回到 Uri.fromFile（目标 APK 若未使用 fileprovider 也可能报错）
            @Suppress("DEPRECATION")
            intent.setDataAndType(android.net.Uri.fromFile(file), mime)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (pendingFilePath != null)
                            stringResource(R.string.tool_tray_title) +
                                " · " + pendingFilePath.substringAfterLast('/')
                        else stringResource(R.string.tool_tray_title)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.close)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            loading = true
                            try {
                                tools = withContext(Dispatchers.IO) { parseIndex(context) }
                                parseError = null
                                toast(context.getString(R.string.tool_tray_refreshed))
                            } catch (e: Exception) {
                                parseError = e.message ?: e.toString()
                            } finally {
                                loading = false
                            }
                        }
                    }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
                // ==================== 工具列表模式 ====================
                when {
                    loading -> {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(32.dp)
                                .align(Alignment.Center)
                        )
                        Text(
                            stringResource(R.string.tool_tray_loading),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(top = 56.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    parseError != null -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                stringResource(R.string.tool_tray_parse_error, parseError!!),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    tools.isEmpty() -> {
                        Text(
                            stringResource(R.string.tool_tray_empty),
                            modifier = Modifier
                                .align(Alignment.Center),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 12.dp,
                                vertical = 8.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(tools, key = { it.id }) { tool ->
                                ToolCard(
                                    tool = tool,
                                    working = workingId == tool.id,
                                    stateVersion = stateVersion,
                                    onExtract = {
                                        scope.launch {
                                            workingId = tool.id
                                            try {
                                                val msg = withContext(Dispatchers.IO) {
                                                    extractTool(context, tool)
                                                }
                                                toast(msg)
                                            } catch (e: Exception) {
                                                toast(context.getString(
                                                    R.string.tool_tray_extract_failed,
                                                    e.message ?: ""
                                                ))
                                            } finally {
                                                workingId = null
                                                stateVersion++
                                            }
                                        }
                                    },
                                    onInstall = {
                                        scope.launch {
                                            workingId = tool.id
                                            try {
                                                val msg = withContext(Dispatchers.IO) {
                                                    installTool(context, tool)
                                                }
                                                toast(msg)
                                            } catch (e: Exception) {
                                                toast(context.getString(
                                                    R.string.tool_tray_install_failed,
                                                    e.message ?: ""
                                                ))
                                            } finally {
                                                workingId = null
                                                stateVersion++
                                            }
                                        }
                                    },
                                    onLaunch = {
                                        scope.launch {
                                            try {
                                                val msg = withContext(Dispatchers.IO) {
                                                    launchTool(context, tool) { intent ->
                                                        attachPendingFileToIntent(intent)
                                                    }
                                                }
                                                if (msg.isNotEmpty()) toast(msg)
                                            } catch (e: Exception) {
                                                toast(context.getString(
                                                    R.string.tool_tray_launch_failed,
                                                    e.message ?: ""
                                                ))
                                            }
                                        }
                                    },
                                    onEditIntent = { intentEditTarget = tool }
                                )
                            }
                        }
                    }
                }
            }
        }

    // Intent 自定义配置弹窗
    intentEditTarget?.let { target ->
        IntentEditDialog(
            tool = target,
            onConfirm = { pkg, cls, action, category, data, extra, flags ->
                tools = tools.map {
                    if (it.id == target.id) it.copy(
                        packageName = pkg.ifBlank { it.packageName },
                        launchActivity = cls,
                        intentAction = action,
                        intentCategory = category,
                        intentData = data,
                        intentExtra = extra,
                        intentFlags = flags
                    ) else it
                }
                intentEditTarget = null
                toast(context.getString(R.string.tool_tray_intent_saved))
            },
            onDismiss = { intentEditTarget = null }
        )
    }
}

// ==================== APK 启动器面板（任务 5） ====================

/**
 * 解析后的 APK 元信息（仅内存，不持久化）。
 *
 * @property label 应用名（如「MT 管理器」）；取不到时回退到包名。
 * @property packageName 包名；解析失败时为空。
 * @property launchActivity 入口 Activity 的完整类名；可能为空（无 launcher 的 APK）。
 * @property versionName 版本名。
 * @property installed 是否已安装（同包名存在于系统中）。
 */
private data class ApkInfo(
    val label: String,
    val packageName: String,
    val launchActivity: String,
    val versionName: String,
    val installed: Boolean
)

/**
 * APK 启动器主体：展示 APK 元信息卡片 + 启动参数编辑区 + 启动按钮。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ApkLauncherPanel(
    info: ApkInfo,
    apkPath: String,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current

    // 启动参数状态（默认值：ACTION_MAIN / CATEGORY_LAUNCHER / 自带入口）
    var useCustomAction by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf(Intent.ACTION_MAIN) }
    var category by remember { mutableStateOf(Intent.CATEGORY_LAUNCHER) }
    var dataUri by remember { mutableStateOf("") }
    var extrasText by remember { mutableStateOf("") }

    var useCustomClass by remember { mutableStateOf(false) }
    var customClass by remember { mutableStateOf(info.launchActivity) }

    // 窗口 flags
    var flagNewTask by remember { mutableStateOf(true) }
    var flagClearTop by remember { mutableStateOf(false) }
    var flagSingleTop by remember { mutableStateOf(false) }
    var flagNoHistory by remember { mutableStateOf(false) }
    var flagClearTask by remember { mutableStateOf(false) }
    var flagBroughtToFront by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // —— APK 元信息卡片 ——
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Apps,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            info.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (info.versionName.isNotBlank()) {
                            Text(
                                "v${info.versionName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                InfoRow(stringResource(R.string.apk_launcher_pkg), info.packageName)
                if (info.launchActivity.isNotBlank()) {
                    InfoRow(stringResource(R.string.apk_launcher_class), info.launchActivity)
                }
                InfoRow(
                    stringResource(R.string.apk_launcher_path),
                    apkPath,
                    maxLines = 2
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusChip(
                        text = if (info.installed) stringResource(R.string.apk_launcher_installed)
                        else stringResource(R.string.apk_launcher_not_installed),
                        ok = info.installed
                    )
                }
            }
        }

        // —— 启动参数编辑区 ——
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    stringResource(R.string.apk_launcher_params),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(10.dp))

                // Action
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = useCustomAction,
                        onCheckedChange = { useCustomAction = it }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.apk_launcher_use_action),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (useCustomAction) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = action,
                        onValueChange = { action = it },
                        label = { Text(stringResource(R.string.apk_launcher_action)) },
                        placeholder = { Text(stringResource(R.string.apk_launcher_action_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(8.dp))
                // Category
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text(stringResource(R.string.apk_launcher_category)) },
                    placeholder = { Text(stringResource(R.string.apk_launcher_category_hint)) },
                    singleLine = true,
                    enabled = useCustomAction,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                // Data Uri
                OutlinedTextField(
                    value = dataUri,
                    onValueChange = { dataUri = it },
                    label = { Text(stringResource(R.string.apk_launcher_data)) },
                    placeholder = { Text(stringResource(R.string.apk_launcher_data_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                // Extras
                OutlinedTextField(
                    value = extrasText,
                    onValueChange = { extrasText = it },
                    label = { Text(stringResource(R.string.apk_launcher_extras)) },
                    placeholder = { Text(stringResource(R.string.apk_launcher_extras_hint)) },
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                // 自定义入口 Activity
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = useCustomClass,
                        onCheckedChange = { useCustomClass = it }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.apk_launcher_use_custom_class),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (useCustomClass) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = customClass,
                        onValueChange = { customClass = it },
                        label = { Text(stringResource(R.string.apk_launcher_class)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.apk_launcher_flags),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                FlagRow(R.string.apk_launcher_flag_new_task, flagNewTask) { flagNewTask = it }
                FlagRow(R.string.apk_launcher_flag_clear_top, flagClearTop) { flagClearTop = it }
                FlagRow(R.string.apk_launcher_flag_single_top, flagSingleTop) { flagSingleTop = it }
                FlagRow(R.string.apk_launcher_flag_no_history, flagNoHistory) { flagNoHistory = it }
                FlagRow(R.string.apk_launcher_flag_clear_task, flagClearTask) { flagClearTask = it }
                FlagRow(R.string.apk_launcher_flag_brought_to_front, flagBroughtToFront) { flagBroughtToFront = it }
            }
        }

        // —— 启动按钮组 ——
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    val cfg = LaunchConfig(
                        useCustomAction = useCustomAction,
                        action = action.trim(),
                        category = category.trim(),
                        dataUri = dataUri.trim(),
                        extrasText = extrasText,
                        useCustomClass = useCustomClass,
                        customClass = customClass.trim(),
                        flagNewTask = flagNewTask,
                        flagClearTop = flagClearTop,
                        flagSingleTop = flagSingleTop,
                        flagNoHistory = flagNoHistory,
                        flagClearTask = flagClearTask,
                        flagBroughtToFront = flagBroughtToFront
                    )
                    val msg = launchApk(context, info, cfg)
                    if (msg.isNotEmpty()) onMessage(msg)
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.OpenInNew, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.apk_launcher_btn_launch))
            }
            OutlinedButton(
                onClick = {
                    val msg = installApk(context, apkPath, info.label)
                    onMessage(msg)
                }
            ) {
                Icon(Icons.Rounded.GetApp, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.apk_launcher_btn_install))
            }
        }
        OutlinedButton(
            onClick = {
                val cfg = LaunchConfig(
                    useCustomAction = useCustomAction,
                    action = action.trim(),
                    category = category.trim(),
                    dataUri = dataUri.trim(),
                    extrasText = extrasText,
                    useCustomClass = useCustomClass,
                    customClass = customClass.trim(),
                    flagNewTask = flagNewTask,
                    flagClearTop = flagClearTop,
                    flagSingleTop = flagSingleTop,
                    flagNoHistory = flagNoHistory,
                    flagClearTask = flagClearTask,
                    flagBroughtToFront = flagBroughtToFront
                )
                val msg = launchWithChooser(context, info, cfg)
                if (msg.isNotEmpty()) onMessage(msg)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Rounded.Apps, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.apk_launcher_btn_chooser))
        }
    }
}

/** APK 元信息行（label : value）。 */
@Composable
private fun InfoRow(label: String, value: String, maxLines: Int = 1) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$label：",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 窗口方式 Flag 行。 */
@Composable
private fun FlagRow(labelRes: Int, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 启动参数配置（任务 5）。
 * 将 UI 上的可变状态打包成不可变配置对象，便于传递给 [launchApk] / [launchWithChooser]。
 */
private data class LaunchConfig(
    val useCustomAction: Boolean,
    val action: String,
    val category: String,
    val dataUri: String,
    val extrasText: String,
    val useCustomClass: Boolean,
    val customClass: String,
    val flagNewTask: Boolean,
    val flagClearTop: Boolean,
    val flagSingleTop: Boolean,
    val flagNoHistory: Boolean,
    val flagClearTask: Boolean,
    val flagBroughtToFront: Boolean
)

/**
 * 用 [PackageManager.getPackageArchiveInfo] 解析 APK 文件元信息。
 *
 * 注意：必须在 IO 线程调用（PackageManager 调用 + 文件读取）。
 * 设置 [ApplicationInfo.sourceDir] 与 [ApplicationInfo.publicSourceDir] 才能正确取到 label。
 *
 * @return 解析失败返回 null；成功返回 [ApkInfo]（label/launchActivity 可能为空字符串）。
 */
private fun parseApkFile(context: Context, apkPath: String): ApkInfo? {
    val pm = context.packageManager
    @Suppress("DEPRECATION")
    val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_INTENT_FILTERS
    val pkgInfo = pm.getPackageArchiveInfo(apkPath, flags) ?: return null
    val appInfo = pkgInfo.applicationInfo ?: return null
    // 关键：必须设置 sourceDir 才能调用 getApplicationLabel
    appInfo.sourceDir = apkPath
    appInfo.publicSourceDir = apkPath

    val pkg = appInfo.packageName ?: ""
    val label = try {
        pm.getApplicationLabel(appInfo).toString()
    } catch (_: Throwable) {
        pkg
    }
    val version = pkgInfo.versionName ?: ""

    // 已安装判断
    val installed = try {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(pkg, 0)
        true
    } catch (_: Throwable) {
        false
    }

    // 解析 launcher activity
    // 注：ActivityInfo 在当前 compileSdk 不暴露 actionNames/categoryNames 字段，
    // 因此无法精确匹配 ACTION_MAIN+LAUNCHER；这里取第一个 exported Activity 作为入口，
    // 用户可在 UI 中通过「自定义入口 Activity」开关覆盖该选择。
    var launchActivity = ""
    val activities = pkgInfo.activities
    if (activities != null && activities.isNotEmpty()) {
        // 1. 优先取 exported=true 的 Activity（一般是入口）
        for (a in activities) {
            if (a.exported) {
                launchActivity = a.name
                break
            }
        }
        // 2. 兜底：取第一个 Activity
        if (launchActivity.isBlank()) {
            launchActivity = activities[0].name
        }
    }

    if (pkg.isBlank()) return null
    return ApkInfo(
        label = label.ifBlank { pkg },
        packageName = pkg,
        launchActivity = launchActivity,
        versionName = version,
        installed = installed
    )
}

/**
 * 构建启动 Intent 的公共方法（任务 5）。
 *
 * 策略：
 *  - 若 [LaunchConfig.useCustomClass] 且 [LaunchConfig.customClass] 非空，用 setClassName；
 *    否则若 APK 已安装，用 [PackageManager.getLaunchIntentForPackage]；
 *    否则取 APK 自带 [ApkInfo.launchActivity]。
 *  - Action / Category / Data / Extras 按 [LaunchConfig] 覆盖。
 *  - 失败时返回错误消息字符串；成功返回空字符串。
 */
private fun buildLaunchIntent(
    context: Context,
    info: ApkInfo,
    cfg: LaunchConfig
): Pair<Intent?, String> {
    if (info.packageName.isBlank()) {
        return Pair(null, context.getString(R.string.apk_launcher_no_pkg))
    }

    // 1. 起点 Intent
    var intent: Intent? = null
    if (cfg.useCustomClass && cfg.customClass.isNotBlank()) {
        intent = Intent().apply {
            setClassName(info.packageName, cfg.customClass)
        }
    } else if (info.installed) {
        intent = context.packageManager.getLaunchIntentForPackage(info.packageName)
    }
    if (intent == null && info.launchActivity.isNotBlank()) {
        intent = Intent().apply {
            setClassName(info.packageName, info.launchActivity)
        }
    }
    if (intent == null) {
        return Pair(null, context.getString(R.string.tool_tray_launch_no_intent, info.label))
    }

    // 2. Action
    if (cfg.useCustomAction && cfg.action.isNotBlank()) {
        intent.action = cfg.action
    }
    // 3. Category
    cfg.category
        .split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .forEach { cat -> intent.addCategory(cat) }
    // 4. Data Uri
    if (cfg.dataUri.isNotBlank()) {
        try {
            intent.data = Uri.parse(cfg.dataUri)
        } catch (_: Throwable) {
            // 解析失败时忽略，避免崩溃
        }
    }
    // 5. Extras
    parseExtras(cfg.extrasText).forEach { (k, v) ->
        when (v) {
            is ExtraValue.IntVal -> intent.putExtra(k, v.value)
            is ExtraValue.LongVal -> intent.putExtra(k, v.value)
            is ExtraValue.BoolVal -> intent.putExtra(k, v.value)
            is ExtraValue.StringVal -> intent.putExtra(k, v.value)
        }
    }
    // 6. Flags
    var flags = 0
    if (cfg.flagNewTask) flags = flags or Intent.FLAG_ACTIVITY_NEW_TASK
    if (cfg.flagClearTop) flags = flags or Intent.FLAG_ACTIVITY_CLEAR_TOP
    if (cfg.flagSingleTop) flags = flags or Intent.FLAG_ACTIVITY_SINGLE_TOP
    if (cfg.flagNoHistory) flags = flags or Intent.FLAG_ACTIVITY_NO_HISTORY
    if (cfg.flagClearTask) flags = flags or Intent.FLAG_ACTIVITY_CLEAR_TASK
    if (cfg.flagBroughtToFront) flags = flags or Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
    if (flags != 0) intent.addFlags(flags)

    return Pair(intent, "")
}

/** 启动 APK（任务 5 主路径）。成功返回空字符串，失败返回错误消息。 */
private fun launchApk(
    context: Context,
    info: ApkInfo,
    cfg: LaunchConfig
): String {
    val (intent, err) = buildLaunchIntent(context, info, cfg)
    if (intent == null) return err
    return try {
        context.startActivity(intent)
        context.getString(R.string.apk_launcher_launch_ok, info.label)
    } catch (e: Exception) {
        // 回退：ACTION_VIEW + package，再不行就 App Chooser
        val fallback = Intent(Intent.ACTION_VIEW).apply {
            setPackage(info.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(fallback)
            context.getString(R.string.apk_launcher_launch_ok, info.label)
        } catch (_: Exception) {
            context.getString(R.string.apk_launcher_launch_failed, e.message ?: "")
        }
    }
}

/** 用 App Chooser 启动（任务 5 备选路径）。 */
private fun launchWithChooser(
    context: Context,
    info: ApkInfo,
    cfg: LaunchConfig
): String {
    val (intent, err) = buildLaunchIntent(context, info, cfg)
    if (intent == null) return err
    return try {
        val chooser = Intent.createChooser(intent, info.label).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        ""
    } catch (e: Exception) {
        context.getString(R.string.apk_launcher_launch_failed, e.message ?: "")
    }
}

/**
 * 安装 APK（任务 5 辅助路径）：用 FileProvider 暴露 APK 并请求系统安装。
 * 需 AndroidManifest 声明 REQUEST_INSTALL_PACKAGES 权限。
 */
private fun installApk(context: Context, apkPath: String, label: String): String {
    val file = File(apkPath)
    if (!file.exists() || file.length() == 0L) {
        return context.getString(R.string.tool_tray_install_no_apk)
    }
    return try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", file
        )
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        context.getString(R.string.apk_launcher_install_ok, label)
    } catch (e: Exception) {
        context.getString(R.string.apk_launcher_install_failed, e.message ?: "")
    }
}

/** Extras 解析结果（区分类型，避免 putExtra 时类型推断错乱）。 */
private sealed class ExtraValue {
    data class StringVal(val value: String) : ExtraValue()
    data class IntVal(val value: Int) : ExtraValue()
    data class LongVal(val value: Long) : ExtraValue()
    data class BoolVal(val value: Boolean) : ExtraValue()
}

/**
 * 解析多行 Extras 文本为键值对。
 * 格式：每行 `key=value`；可显式标注类型 `key:i=value`、`key:l=value`、`key:b=value`，
 * 否则按「true/false→Bool」「纯数字→Int」「其他→String」推断。
 */
private fun parseExtras(text: String): List<Pair<String, ExtraValue>> {
    if (text.isBlank()) return emptyList()
    val out = ArrayList<Pair<String, ExtraValue>>()
    text.split("\n").forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
        val eq = trimmed.indexOf('=')
        if (eq <= 0) return@forEach
        val keyPart = trimmed.substring(0, eq).trim()
        val valuePart = trimmed.substring(eq + 1).trim()
        if (keyPart.isEmpty()) return@forEach

        // 类型后缀解析：key:i / key:l / key:b
        var typeTag = ""
        var key = keyPart
        val colon = keyPart.lastIndexOf(':')
        if (colon in 1 until keyPart.length - 1) {
            typeTag = keyPart.substring(colon + 1).lowercase()
            key = keyPart.substring(0, colon).trim()
        }

        val value: ExtraValue = when (typeTag) {
            "i", "int" -> valuePart.toIntOrNull()?.let { ExtraValue.IntVal(it) }
            "l", "long" -> valuePart.toLongOrNull()?.let { ExtraValue.LongVal(it) }
            "b", "bool", "boolean" ->
                ExtraValue.BoolVal(valuePart.equals("true", ignoreCase = true))
            else -> when {
                valuePart.equals("true", ignoreCase = true) ||
                    valuePart.equals("false", ignoreCase = true) ->
                    ExtraValue.BoolVal(valuePart.toBoolean())
                valuePart.toLongOrNull() != null -> ExtraValue.LongVal(valuePart.toLong())
                else -> ExtraValue.StringVal(valuePart)
            }
        } ?: ExtraValue.StringVal(valuePart)

        out += key to value
    }
    return out
}

// ==================== 工具卡片（默认模式） ====================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ToolCard(
    tool: ToolInfo,
    working: Boolean,
    stateVersion: Int,
    onExtract: () -> Unit,
    onInstall: () -> Unit,
    onLaunch: () -> Unit,
    onEditIntent: () -> Unit
) {
    val context = LocalContext.current
    val extracted = remember(tool.id, stateVersion) { extractedFile(context, tool).exists() }
    val installed = remember(tool.id, tool.packageName, stateVersion) {
        isInstalled(context, tool.packageName)
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Apps,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        tool.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (tool.version.isNotBlank()) {
                        Text(
                            "v${tool.version}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (working) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            if (tool.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusChip(
                    text = stringResource(R.string.tool_tray_status_extracted),
                    ok = extracted
                )
                StatusChip(
                    text = stringResource(R.string.tool_tray_status_installed),
                    ok = installed
                )
            }
            Spacer(Modifier.height(10.dp))
            // Item 1 改进：按钮分两行，避免水平方向挤在一行
            // 第 1 行：解压 + 安装
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onExtract,
                    enabled = !working,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Download, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.tool_tray_btn_extract))
                }
                OutlinedButton(
                    onClick = onInstall,
                    enabled = !working && extracted,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.GetApp, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.tool_tray_btn_install))
                }
            }
            Spacer(Modifier.height(6.dp))
            // 第 2 行：启动 + 设置启动参数
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onLaunch,
                    enabled = !working && installed,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.OpenInNew, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.tool_tray_btn_launch))
                }
                OutlinedButton(
                    onClick = onEditIntent,
                    enabled = !working,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Settings, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.tool_tray_edit_intent))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusChip(text: String, ok: Boolean) {
    AssistChip(
        onClick = {},
        enabled = true,
        label = { Text(text) },
        leadingIcon = {
            if (ok) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (ok) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
/**
 * Intent 启动参数编辑对话框（Item 1 增强）。
 *
 * 支持：包名 / Activity 类名 / Action / Category / Data / Extras (key=value 多行) / Flags。
 * 所有字段可空，空表示不覆盖（走系统 Launcher 默认 Intent）。
 */
private fun IntentEditDialog(
    tool: ToolInfo,
    onConfirm: (
        packageName: String,
        className: String,
        action: String,
        category: String,
        data: String,
        extra: String,
        flags: String
    ) -> Unit,
    onDismiss: () -> Unit
) {
    var pkg by remember { mutableStateOf(tool.packageName) }
    var cls by remember { mutableStateOf(tool.launchActivity) }
    var action by remember { mutableStateOf(tool.intentAction) }
    var category by remember { mutableStateOf(tool.intentCategory) }
    var data by remember { mutableStateOf(tool.intentData) }
    var extra by remember { mutableStateOf(tool.intentExtra) }
    var flags by remember { mutableStateOf(tool.intentFlags) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tool_tray_intent_title, tool.name)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(R.string.tool_tray_intent_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = pkg, onValueChange = { pkg = it },
                    label = { Text(stringResource(R.string.tool_tray_intent_pkg)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = cls, onValueChange = { cls = it },
                    label = { Text(stringResource(R.string.tool_tray_intent_cls)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = action, onValueChange = { action = it },
                    label = { Text("Action（如 android.intent.action.VIEW，留空用默认）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = category, onValueChange = { category = it },
                    label = { Text("Category（如 android.intent.category.DEFAULT）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = data, onValueChange = { data = it },
                    label = { Text("Data Uri") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = flags, onValueChange = { flags = it },
                    label = { Text("Flags（用 | 分隔，如 FLAG_ACTIVITY_NEW_TASK）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = extra, onValueChange = { extra = it },
                    label = { Text("Extras（每行一对 key=value）") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                )
                Text(
                    stringResource(R.string.tool_tray_intent_cls_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(pkg.trim(), cls.trim(), action.trim(), category.trim(), data.trim(), extra, flags)
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

// ==================== 数据模型与工具方法 ====================

/**
 * 工具条目（来自 index.json）。
 *
 * [intentAction]/[intentCategory]/[intentData]/[intentExtra]/[intentFlags]
 *  为「启动参数」面板中的可编辑字段，默认空（表示用系统 Launcher Intent）。
 *  用户可在每个工具的「设置启动参数」中覆盖，由 [launchTool] 在构建 Intent 时应用。
 */
data class ToolInfo(
    val id: String,
    val name: String,
    val apkFile: String,
    val packageName: String,
    val launchActivity: String,
    val version: String,
    val description: String,
    val intentAction: String = "",
    val intentCategory: String = "",
    val intentData: String = "",
    val intentExtra: String = "",   // key=value, multiple lines
    val intentFlags: String = ""    // e.g. "FLAG_ACTIVITY_NEW_TASK|FLAG_GRANT_READ_URI_PERMISSION"
)

/** 解析 assets/apk/index.json 为工具列表。 */
private fun parseIndex(context: Context): List<ToolInfo> {
    val json = context.assets.open("apk/index.json").use { it.readBytes().toString(Charsets.UTF_8) }
    val root = JSONObject(json)
    val arr = root.optJSONArray("tools") ?: return emptyList()
    val list = ArrayList<ToolInfo>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        list += ToolInfo(
            id = o.optString("id").ifBlank { o.optString("apkFile") },
            name = o.optString("name").ifBlank { o.optString("apkFile") },
            apkFile = o.optString("apkFile"),
            packageName = o.optString("packageName"),
            launchActivity = o.optString("launchActivity"),
            version = o.optString("version"),
            description = o.optString("description"),
            intentAction = o.optString("intentAction"),
            intentCategory = o.optString("intentCategory"),
            intentData = o.optString("intentData"),
            intentExtra = o.optString("intentExtra"),
            intentFlags = o.optString("intentFlags")
        )
    }
    return list
}

/** 解压后 APK 落地的缓存文件（不解压时不创建）。 */
private fun extractedFile(context: Context, tool: ToolInfo): File =
    File(File(context.filesDir, "tool_tray").apply { mkdirs() }, tool.apkFile)

/**
 * 第一步：解压工具 —— 从 assets 复制 APK 到内部缓存目录。
 * 注意：assets 中的 APK 不会被自动解压，这里按需复制。
 */
private fun extractTool(context: Context, tool: ToolInfo): String {
    val out = extractedFile(context, tool)
    if (out.exists() && out.length() > 0) {
        return context.getString(R.string.tool_tray_extract_exists, out.absolutePath)
    }
    try {
        context.assets.open("apk/${tool.apkFile}").use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
    } catch (e: Exception) {
        throw java.io.IOException(
            context.getString(R.string.tool_tray_apk_missing, "apk/${tool.apkFile}")
        )
    }
    return context.getString(R.string.tool_tray_extract_ok, out.absolutePath)
}

/** 判断目标包是否已安装。 */
private fun isInstalled(context: Context, packageName: String): Boolean {
    if (packageName.isBlank()) return false
    return try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: Throwable) {
        false
    }
}

/**
 * 第二步：安装工具 —— 复用「以 Intent 启动」的统一逻辑：通过 FileProvider 暴露
 * 已解压的 APK，调用系统 ACTION_INSTALL_PACKAGE 进行安装（系统会弹确认窗）。
 * 需 AndroidManifest 声明 REQUEST_INSTALL_PACKAGES 权限。
 */
private fun installTool(context: Context, tool: ToolInfo): String {
    val apk = extractedFile(context, tool)
    if (!apk.exists() || apk.length() == 0L) {
        throw java.io.IOException(context.getString(R.string.tool_tray_install_no_apk))
    }
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context, context.packageName + ".fileprovider", apk
    )
    val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        context.getString(R.string.tool_tray_install_started, tool.name)
    } catch (e: Exception) {
        throw java.io.IOException(e.message ?: "INSTALL_FAILED")
    }
}

/**
 * 第三步：启动工具 —— 按优先级构建 Intent：
 *  1. 用户在 IntentEditDialog 中设置的 launchActivity
 *  2. index.json 配置的 launchActivity
 *  3. 回退到 PackageManager.getLaunchIntentForPackage（复用「以 Intent 启动应用」的逻辑）
 *
 * 返回非空字符串表示需要 toast 提示（如启动失败原因），空字符串表示已成功启动。
 */
/**
 * 把工具自身的 intent config（action/category/data/extras/flags）应用到 Intent。
 * 空字段表示不覆盖。
 */
private fun applyToolIntentConfig(intent: Intent, tool: ToolInfo) {
    if (tool.intentAction.isNotBlank()) intent.action = tool.intentAction
    if (tool.intentCategory.isNotBlank()) intent.addCategory(tool.intentCategory)
    if (tool.intentData.isNotBlank()) {
        intent.data = android.net.Uri.parse(tool.intentData)
    }
    tool.intentExtra.lineSequence().forEach { line ->
        val kv = line.trim()
        if (kv.isBlank() || !kv.contains('=')) return@forEach
        val (k, v) = kv.split('=', limit = 2)
        intent.putExtra(k.trim(), v)
    }
    if (tool.intentFlags.isNotBlank()) {
        tool.intentFlags.split('|').forEach { flagName ->
            val f = flagName.trim()
            if (f.isBlank()) return@forEach
            try {
                val field = Intent::class.java.getField(f)
                val value = field.getInt(null)
                intent.addFlags(value)
            } catch (_: Throwable) { /* ignore unknown flag names */ }
        }
    }
}

private fun launchTool(
    context: Context,
    tool: ToolInfo,
    intentCustomizer: (Intent) -> Unit = {}
): String {
    val pkg = tool.packageName
    if (pkg.isBlank()) {
        return context.getString(R.string.tool_tray_launch_no_pkg)
    }
    val intent: Intent? = when {
        tool.launchActivity.isNotBlank() -> {
            Intent().apply {
                setClassName(pkg, tool.launchActivity)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        else -> context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    if (intent == null) {
        return context.getString(R.string.tool_tray_launch_no_intent, pkg)
    }
    // 1) 工具自身配置（用户在「设置启动参数」里编辑的）
    applyToolIntentConfig(intent, tool)
    // 2) 调用方定制（如文件路由模式：附加 Uri + MIME）
    intentCustomizer(intent)
    return try {
        context.startActivity(intent)
        ""
    } catch (e: Exception) {
        // 回退：尝试系统 App Chooser（ACTION_VIEW + package）
        val chooser = Intent(Intent.ACTION_VIEW).apply {
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            applyToolIntentConfig(this, tool)
            intentCustomizer(this)
        }
        try {
            context.startActivity(Intent.createChooser(chooser, tool.name).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            ""
        } catch (_: Exception) {
            context.getString(R.string.tool_tray_launch_failed, e.message ?: "")
        }
    }
}
