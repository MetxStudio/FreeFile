package com.freefile.ui.share

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.freefile.R
import com.freefile.share.ShareClient
import com.freefile.share.ShareDiscovery
import com.freefile.share.ShareServer
import kotlinx.coroutines.launch
import java.io.File

/**
 * 局域网快传页（Sprint 5）：
 * - 打开服务（HTTP + mDNS 注册），同一 WLAN 的 FreeFile 设备自动出现
 * - 点击设备 → 系统选择器选文件 → 上传到对方
 * - 接收目录列表（/sdcard/FreeFileShare）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(onBack: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val inboxDir = remember {
        File("/storage/emulated/0/FreeFileShare").apply { mkdirs() }
    }

    var serverRunning by remember { mutableStateOf(false) }
    var server: ShareServer? = remember { null }
    val devices = remember { mutableStateListOf<Pair<String, Pair<String, Int>>>() } // name → (host, port)
    val received = remember { mutableStateListOf<File>() }

    fun refreshInbox() {
        received.clear()
        received.addAll(
            inboxDir.listFiles()?.filter { it.isFile }
                ?.sortedByDescending { it.lastModified() }.orEmpty()
        )
    }
    remember { refreshInbox(); true }

    DisposableEffect(Unit) {
        onDispose {
            // NanoHTTPD.stop() 与 JmDNS.close() 内部可能阻塞，移到后台线程避免主线程卡死
            Thread {
                try { server?.stop() } catch (_: Throwable) {}
                ShareDiscovery.unregister()
            }.apply { isDaemon = true; name = "ShareScreen-cleanup" }.start()
        }
    }

    // 系统文件选择器（可多选）
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            for (uri in uris) {
                // 先复制到缓存（ContentResolver 流 → 临时文件），再上传
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                val tmp = File(context.cacheDir, "share_upload_$name")
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    devices.forEach { (devName, hostPort) ->
                        val (ok, msg) = ShareClient.upload(hostPort.first, hostPort.second, tmp)
                        onMessage(
                            context.getString(
                                if (ok) R.string.share_sent else R.string.share_send_failed,
                                name, devName
                            ) + if (!ok) " ($msg)" else ""
                        )
                    }
                } catch (e: Exception) {
                    onMessage(e.message ?: "error")
                } finally {
                    tmp.delete()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.share_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = stringResource(R.string.close))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // 服务开关卡片
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.share_service), style = MaterialTheme.typography.titleSmall)
                        val ip = ShareClient.localIpAddress()
                        Text(
                            if (serverRunning && ip != null) {
                                stringResource(R.string.share_service_running, ip, ShareServer.DEFAULT_PORT)
                            } else {
                                stringResource(R.string.share_service_stopped)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = serverRunning, onCheckedChange = { enable ->
                        serverRunning = enable
                        if (enable) {
                            try {
                                val s = ShareServer(inboxDir)
                                s.start()
                                server = s
                                // 内部自带后台线程，非挂起即可调用
                                ShareDiscovery.registerAndDiscover(
                                    "FreeFile-" + (ipSuffix(ShareClient.localIpAddress()) ?: "dev"),
                                    ShareServer.DEFAULT_PORT
                                )
                                ShareDiscovery.onDeviceAdded = { name, host, port ->
                                    scope.launch {
                                        if (devices.none { it.first == name }) {
                                            devices.add(name to (host to port))
                                        }
                                    }
                                }
                                ShareDiscovery.onDeviceRemoved = { name ->
                                    scope.launch { devices.removeAll { it.first == name } }
                                }
                                onMessage(context.getString(R.string.share_service_started))
                            } catch (e: Exception) {
                                serverRunning = false
                                onMessage(e.message ?: "start failed")
                            }
                        } else {
                            // 关闭服务时同步释放，避免阻塞主线程
                            val s = server
                            server = null
                            Thread {
                                try { s?.stop() } catch (_: Throwable) {}
                                ShareDiscovery.unregister()
                            }.apply { isDaemon = true; name = "ShareServer-stop" }.start()
                            devices.clear()
                            onMessage(context.getString(R.string.share_service_stopped_msg))
                        }
                        refreshInbox()
                    })
                }
            }

            // 附近设备
            Text(
                stringResource(R.string.share_nearby),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            if (devices.isEmpty()) {
                Text(
                    stringResource(R.string.share_no_devices),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                ) {
                    items(devices, key = { it.first }) { (name, hostPort) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    filePicker.launch(arrayOf("*/*"))
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.PhoneAndroid, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${hostPort.first}:${hostPort.second}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(Icons.Rounded.Send, null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.share_inbox, received.size),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    filePicker.launch(arrayOf("*/*"))
                }) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.share_send_direct))
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(received, key = { it.absolutePath }) { f ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // 主动发送该文件到所有已发现设备
                                if (devices.isEmpty()) {
                                    onMessage(context.getString(R.string.share_no_devices))
                                } else {
                                    scope.launch {
                                        devices.forEach { (name, hp) ->
                                            val (ok, msg) = ShareClient.upload(hp.first, hp.second, f)
                                            onMessage(
                                                context.getString(
                                                    if (ok) R.string.share_sent
                                                    else R.string.share_send_failed,
                                                    f.name, name
                                                ) + if (!ok) " ($msg)" else ""
                                            )
                                        }
                                    }
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Computer, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(f.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${f.length()} B",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 取 IP 末段做设备名。 */
private fun ipSuffix(ip: String?): String? = ip?.substringAfterLast('.')
