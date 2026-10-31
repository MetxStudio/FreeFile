package com.freefile.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.freefile.data.externaltools.ExternalToolsStore
import com.freefile.ui.theme.FreeFileTheme
import com.freefile.ui.tools.ToolInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class DownloadProgress(
	val downloadedBytes: Long,
	val totalBytes: Long,
	val speedBytesPerSecond: Long
)

private fun formatBytes(bytes: Long): String {
	if (bytes < 0) return "0 B"
	if (bytes < 1024) return "$bytes B"
	val units = arrayOf("KB", "MB", "GB", "TB")
	var value = bytes.toDouble() / 1024.0
	var idx = 0
	while (value >= 1024 && idx < units.lastIndex) {
		value /= 1024.0
		idx++
	}
	val pattern = if (value < 10) "%.2f" else "%.1f"
	return String.format(java.util.Locale.US, "$pattern %s", value, units[idx])
}

private const val TOOL_CATALOG_URL = "https://your-domain.example/json/tools.json"

class DownloadToolActivity : AppCompatActivity() {
	private val viewModel by lazy {
		ViewModelProvider(this)[DownloadToolViewModel::class.java]
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContent {
			FreeFileTheme {
				DownloadToolScreen(
					viewModel = viewModel,
					onBack = ::finish,
					onInstall = ::install
				)
			}
		}
	}

	private fun install(item: DownloadTool, apk: File, showMessage: (String) -> Unit) {
		lifecycleScope.launch {
			val installApk = try {
				withContext(Dispatchers.IO) { renameForInstall(apk) }
			} catch (e: Exception) {
				showMessage("APK 重命名失败，已阻止安装：${e.message}")
				return@launch
			}
			try {
				withContext(Dispatchers.IO) {
					ExternalToolsStore(this@DownloadToolActivity).upsert(item.toToolInfo(installApk.name))
				}
			} catch (e: Exception) {
				showMessage("工具配置写入失败，已阻止安装：${e.message}")
				return@launch
			}
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
				!packageManager.canRequestPackageInstalls()
			) {
				showMessage("请允许本应用安装未知来源应用，然后再次点击安装")
				startActivity(Intent(
					Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
					Uri.parse("package:$packageName")
				))
				return@launch
			}
			try {
				val uri = FileProvider.getUriForFile(
					this@DownloadToolActivity,
					"$packageName.fileprovider",
					installApk
				)
				startActivity(Intent(Intent.ACTION_VIEW).apply {
					setDataAndType(uri, "application/vnd.android.package-archive")
					addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
				})
			} catch (e: Exception) {
				showMessage("启动安装失败：${e.message}")
			}
		}
	}

	private fun renameForInstall(downloaded: File): File {
		if (!downloaded.exists() || downloaded.length() == 0L) {
			throw IOException("下载文件不存在或为空")
		}
		if (downloaded.extension.equals("apk", ignoreCase = true)) return downloaded
		val apk = File(downloaded.parentFile, downloaded.name + ".apk")
		if (apk.exists() && !apk.delete()) throw IOException("无法替换旧 APK 文件")
		if (!downloaded.renameTo(apk)) throw IOException("无法将 ${downloaded.name} 重命名为 ${apk.name}")
		return apk
	}
}

data class DownloadTool(
	val id: String,
	val name: String,
	val description: String,
	val packageName: String,
	val downloadUrl: String,
	val iconUrl: String = "",
	val version: String = "",
	val size: String = "",
	val launchActivity: String = ""
) {
	fun toToolInfo(apkFile: String) = ToolInfo(
		id = id.ifBlank { packageName },
		name = name.ifBlank { packageName },
		apkFile = apkFile,
		packageName = packageName,
		launchActivity = launchActivity,
		version = version,
		description = description
	)
}

sealed interface DownloadUiState {
	data object Loading : DownloadUiState
	data class Content(val tools: List<DownloadTool>) : DownloadUiState
	data class Error(val message: String) : DownloadUiState
}

class DownloadToolViewModel : ViewModel() {
	private val client = OkHttpClient()
	private val _state = MutableStateFlow<DownloadUiState>(DownloadUiState.Loading)
	val state: StateFlow<DownloadUiState> = _state.asStateFlow()
	private val _downloads = MutableStateFlow<Map<String, File>>(emptyMap())
	val downloads: StateFlow<Map<String, File>> = _downloads.asStateFlow()
	private val _downloading = MutableStateFlow<String?>(null)
	val downloading: StateFlow<String?> = _downloading.asStateFlow()
	private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
	val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress.asStateFlow()

	init { load() }

	fun load() {
		viewModelScope.launch {
			_state.value = DownloadUiState.Loading
			try {
				val tools = withContext(Dispatchers.IO) { requestCatalog() }
				_state.value = DownloadUiState.Content(tools)
			} catch (e: Exception) {
				_state.value = DownloadUiState.Error(e.message ?: "请求失败")
			}
		}
	}

	fun download(context: android.content.Context, item: DownloadTool) {
		if (_downloading.value != null) return
		viewModelScope.launch {
			_downloading.value = item.id
			try {
				val file = withContext(Dispatchers.IO) {
					val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
						?: context.cacheDir
					directory.mkdirs()
					val target = File(directory, item.id.replace(Regex("[^A-Za-z0-9._-]"), "_"))
					val request = Request.Builder().url(item.downloadUrl).build()
					client.newCall(request).execute().use { response ->
						if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
						val body = response.body ?: throw IOException("响应为空")
						val totalBytes = body.contentLength()
						_downloadProgress.value = DownloadProgress(0L, totalBytes, 0L)
						body.byteStream().use { input ->
							target.outputStream().use { output ->
								val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
								var downloadedBytes = 0L
								var lastUpdateNanos = System.nanoTime()
								var lastUpdateBytes = 0L
								while (true) {
									val read = input.read(buffer)
									if (read == -1) break
									output.write(buffer, 0, read)
									downloadedBytes += read
									val now = System.nanoTime()
									if (now - lastUpdateNanos >= PROGRESS_UPDATE_INTERVAL_NANOS) {
										val elapsedSeconds = (now - lastUpdateNanos) / 1_000_000_000.0
										val speed = ((downloadedBytes - lastUpdateBytes) / elapsedSeconds).toLong()
										_downloadProgress.value = DownloadProgress(downloadedBytes, totalBytes, speed)
										lastUpdateNanos = now
										lastUpdateBytes = downloadedBytes
									}
								}
								_downloadProgress.value = DownloadProgress(
									downloadedBytes,
									totalBytes,
									if (downloadedBytes == 0L) 0L else {
										val elapsedSeconds = (System.nanoTime() - lastUpdateNanos) / 1_000_000_000.0
										if (elapsedSeconds > 0) ((downloadedBytes - lastUpdateBytes) / elapsedSeconds).toLong() else 0L
									}
								)
								output.flush()
							}
						}
					}
					if (!target.exists() || target.length() == 0L) throw IOException("APK 文件为空")
					target
				}
				_downloads.value = _downloads.value + (item.id to file)
			} catch (e: Exception) {
				_state.value = DownloadUiState.Error("下载失败：${e.message ?: "未知错误"}")
			} finally {
				_downloading.value = null
				_downloadProgress.value = null
			}
		}
	}

	private companion object {
		const val PROGRESS_UPDATE_INTERVAL_NANOS = 100_000_000L
	}

	private fun requestCatalog(): List<DownloadTool> {
		val request = Request.Builder().url(TOOL_CATALOG_URL).build()
		client.newCall(request).execute().use { response ->
			if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
			val root = JSONObject(response.body?.string() ?: throw IOException("响应为空"))
			val array = root.optJSONArray("tools") ?: root.optJSONArray("apps")
				?: throw IOException("缺少 tools/apps 数组")
			return (0 until array.length()).mapNotNull { i ->
				array.optJSONObject(i)?.let { item ->
					DownloadTool(
						id = item.optString("id").ifBlank { item.optString("packageName") },
						name = item.optString("name"),
						description = item.optString("description").ifBlank { item.optString("summary") },
						packageName = item.optString("packageName"),
						downloadUrl = item.optString("downloadUrl"),
						iconUrl = item.optString("iconUrl"),
						version = item.optString("version"),
						size = item.optString("size"),
						launchActivity = item.optString("launchActivity")
					).takeIf { it.name.isNotBlank() && it.packageName.isNotBlank() && it.downloadUrl.isNotBlank() }
				}
			}
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadToolScreen(
	viewModel: DownloadToolViewModel,
	onBack: () -> Unit,
	onInstall: (DownloadTool, File, (String) -> Unit) -> Unit
) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	val downloads by viewModel.downloads.collectAsStateWithLifecycle()
	val downloading by viewModel.downloading.collectAsStateWithLifecycle()
	val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
	val snackbar = remember { SnackbarHostState() }
	val scope = rememberCoroutineScope()
	val context = androidx.compose.ui.platform.LocalContext.current

	Scaffold(
		snackbarHost = { SnackbarHost(snackbar) },
		topBar = {
			TopAppBar(
				title = { Text("下载工具") },
				navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, null) } },
				actions = { IconButton(onClick = viewModel::load) { Icon(Icons.Rounded.Refresh, "重试") } }
			)
		}
	) { padding ->
		when (val current = state) {
			DownloadUiState.Loading -> CircularProgressIndicator(Modifier.padding(padding).padding(24.dp))
			is DownloadUiState.Error -> Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.Center) {
				Text(current.message, color = MaterialTheme.colorScheme.error)
				Button(onClick = viewModel::load, modifier = Modifier.padding(top = 12.dp)) { Text("重试") }
			}
			is DownloadUiState.Content -> if (current.tools.isEmpty()) {
				Text("暂无可下载工具", Modifier.padding(padding).padding(16.dp))
			} else {
				LazyColumn(Modifier.fillMaxSize().padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
					items(current.tools, key = { it.id }) { item ->
						Card(Modifier.fillMaxWidth()) {
							Column(Modifier.padding(14.dp)) {
								Text(item.name, style = MaterialTheme.typography.titleMedium)
								if (item.description.isNotBlank()) Text(item.description, modifier = Modifier.padding(top = 4.dp))
								Text("${item.packageName}${if (item.version.isNotBlank()) " · v${item.version}" else ""}", style = MaterialTheme.typography.labelSmall)
								if (item.id == downloading) {
									val progress = downloadProgress
									if (progress != null) {
										val hasTotal = progress.totalBytes > 0L
										val percent = if (hasTotal) {
											(progress.downloadedBytes * 100 / progress.totalBytes).coerceIn(0L, 100L)
										} else null
										LinearProgressIndicator(
											progress = { if (hasTotal) progress.downloadedBytes.toFloat() / progress.totalBytes else 0f },
											modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
										)
											val totalText = if (hasTotal) formatBytes(progress.totalBytes) else "未知大小"
											Text(
												"${percent?.let { "$it% · " } ?: "下载中 · "}${formatBytes(progress.downloadedBytes)} / " +
													"$totalText · ${formatBytes(progress.speedBytesPerSecond)}/s",
												style = MaterialTheme.typography.bodySmall,
												modifier = Modifier.padding(top = 4.dp)
											)
									}
								}
								Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
									val file = downloads[item.id]
									if (file != null) {
										Button(onClick = { onInstall(item, file) { message -> scope.launch { snackbar.showSnackbar(message) } } }) { Text("安装") }
									}
									Button(
										onClick = { viewModel.download(context, item) },
										enabled = downloading == null,
										modifier = Modifier.padding(start = 8.dp)
									) {
										Icon(Icons.Rounded.Download, null)
										Text(if (file == null) "下载" else "重新下载", Modifier.padding(start = 4.dp))
									}
								}
							}
						}
					}
				}
			}

			else -> {}
		}
	}
}
