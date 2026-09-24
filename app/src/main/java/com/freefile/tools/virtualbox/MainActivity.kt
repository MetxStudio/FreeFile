package com.freefile.tools.virtualbox

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.freefile.R
import com.freefile.ui.theme.FreeFileTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * VirtualBox 主 Activity：免安装 APK 运行工具入口。
 *
 * Intent extras:
 *  - [EXTRA_APK_PATH] : String?，从 OpenWithActivity 传入的 APK 文件路径；非空时自动导入并安装。
 *
 * 使用 AppCompatActivity 以支持 per-app language；UI 走 Compose + FreeFileTheme。
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH)
        setContent {
            FreeFileTheme {
                VirtualBoxScreen(
                    initialApkPath = apkPath,
                    onBack = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_APK_PATH = "apk_path"
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun VirtualBoxScreen(
        initialApkPath: String? = null,
        onBack: () -> Unit
    ) {
        val context = LocalContext.current
        val scope = lifecycleScope

        var virtualApps by remember { mutableStateOf<List<VirtualAppInfo>>(emptyList()) }
        var statusMessage by remember { mutableStateOf("") }
        var isLoading by remember { mutableStateOf(false) }

        val readyText = stringResource(R.string.vbox_status_ready)
        val loadedFmt = stringResource(R.string.vbox_status_loaded)
        val importingText = stringResource(R.string.vbox_status_importing)
        val importFailRead = stringResource(R.string.vbox_import_fail_read)
        val importFailParse = stringResource(R.string.vbox_import_fail_parse)
        val installOkFmt = stringResource(R.string.vbox_install_ok)
        val installFailText = stringResource(R.string.vbox_install_fail)
        val launchFailFmt = stringResource(R.string.vbox_launch_fail)

        // 刷新已安装的虚拟应用列表
        fun refreshList() {
            scope.launch {
                val list = withContext(Dispatchers.IO) {
                    VirtualBoxHelper.getInstalledVirtualApps(context)
                }
                virtualApps = list
                statusMessage = loadedFmt.format(list.size)
            }
        }

        // 从本地路径导入 APK（由 OpenWithActivity 传入）
        fun importFromPath(path: String) {
            isLoading = true
            statusMessage = importingText
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    val apkFile = File(path)
                    if (!apkFile.exists()) return@withContext importFailRead
                    val appInfo = VirtualBoxHelper.parseApkInfo(context, apkFile)
                        ?: return@withContext importFailParse
                    val installed = VirtualBoxHelper.installFromApkFile(apkFile)
                    if (installed) installOkFmt.format(appInfo.label, appInfo.packageName)
                    else installFailText
                }
                statusMessage = result
                isLoading = false
                refreshList()
            }
        }

        // 首次加载：有传入路径则自动导入，否则仅刷新列表
        LaunchedEffect(initialApkPath) {
            if (initialApkPath != null) {
                importFromPath(initialApkPath)
            } else {
                statusMessage = readyText
                refreshList()
            }
        }

        // SAF 文件选择器
        val pickApkLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            uri ?: return@rememberLauncherForActivityResult
            isLoading = true
            statusMessage = importingText

            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    // Step 1: 复制 APK 到内部存储
                    val apkFile = VirtualBoxHelper.importApkFromUri(context, uri)
                        ?: return@withContext importFailRead

                    // Step 2: 解析 APK 信息
                    val appInfo = VirtualBoxHelper.parseApkInfo(context, apkFile)
                        ?: return@withContext importFailParse

                    // Step 3: 调用 NewBlackbox 安装（免安装运行）
                    val installed = VirtualBoxHelper.installFromApkFile(apkFile)
                    if (installed) {
                        installOkFmt.format(appInfo.label, appInfo.packageName)
                    } else {
                        installFailText
                    }
                }

                statusMessage = result
                isLoading = false
                refreshList()
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.smallTopAppBarColors(
                        containerColor = Color.Black
                    ),
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.vbox_title),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Text(
                                text = stringResource(R.string.vbox_subtitle),
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 12.sp
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { refreshList() }) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.refresh),
                                tint = Color.White
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // ===== 状态提示 =====
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = statusMessage,
                            modifier = Modifier.padding(12.dp),
                            fontSize = 13.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // ===== 导入按钮 =====
                    Button(
                        onClick = {
                            pickApkLauncher.launch(
                                arrayOf("application/vnd.android.package-archive")
                            )
                        },
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = stringResource(
                                if (isLoading) R.string.vbox_processing
                                else R.string.vbox_import_btn
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = stringResource(R.string.vbox_list_title, virtualApps.size),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // ===== 应用列表 =====
                    if (virtualApps.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.vbox_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(virtualApps, key = { it.packageName }) { app ->
                                VirtualAppCard(
                                    app = app,
                                    onLaunch = {
                                        val success = VirtualBoxHelper.launchApp(app.packageName)
                                        if (!success) {
                                            Toast.makeText(
                                                context,
                                                launchFailFmt.format(app.label),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun VirtualAppCard(
        app: VirtualAppInfo,
        onLaunch: () -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Android,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.label,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp
                    )
                    Text(
                        text = app.packageName,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(
                            if (app.isInstalled) R.string.vbox_source_installed
                            else R.string.vbox_source_apk
                        ),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onLaunch) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.vbox_launch),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
