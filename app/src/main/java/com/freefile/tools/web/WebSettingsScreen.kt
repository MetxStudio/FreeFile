package com.freefile.tools.web

import android.webkit.WebSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.freefile.R

private val Primary = Color(0xFF1A73E8)

/**
 * 浏览器设置页：几十项 WebView API 级开关 + 代理 + 用户代理 + 一键复位。
 * 全屏覆盖在 WebViewerScreen 之上，返回时调用 onBack（应用设置）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSettingsScreen(
    store: WebSettingsStore,
    onBack: () -> Unit
) {
    // 用本地状态触发重组刷新（store 直接读写 prefs）
    var refresh by remember { mutableIntStateOf(0) }
    fun bump() { refresh++ }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                title = { Text(stringResource(R.string.web_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    TextButton(onClick = { store.resetAll(); bump() }) {
                        Icon(Icons.Rounded.RestartAlt, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.web_reset), color = Color.White)
                    }
                }
            )
        }
    ) { innerPadding ->
        @Suppress("UNUSED_EXPRESSION") refresh
        LazyColumn(
            Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)
        ) {
            // ===== 功能开关 =====
            item { SectionHeader(stringResource(R.string.web_group_features)) }
            item { SwitchItem(stringResource(R.string.web_js), store.javaScriptEnabled) { store.javaScriptEnabled = it; bump() } }
            item { SwitchItem(stringResource(R.string.web_dom_storage), store.domStorageEnabled) { store.domStorageEnabled = it; bump() } }
            item { SwitchItem(stringResource(R.string.web_database), store.databaseEnabled) { store.databaseEnabled = it; bump() } }
            item { SwitchItem(stringResource(R.string.web_js_open_windows), store.javaScriptCanOpenWindowsAutomatically) { store.javaScriptCanOpenWindowsAutomatically = it; bump() } }
            item { SwitchItem(stringResource(R.string.web_multi_windows), store.supportMultipleWindows) { store.supportMultipleWindows = it; bump() } }
            item { SwitchItem(stringResource(R.string.web_media_gesture), store.mediaPlaybackRequiresUserGesture) { store.mediaPlaybackRequiresUserGesture = it; bump() } }
            item { SwitchItem(stringResource(R.string.web_eruda), store.erudaEnabled) { store.erudaEnabled = it; bump() } }

            // ===== 缩放 / 视口 =====
            item { SectionHeader(stringResource(R.string.web_group_zoom)) }
            item { SwitchItem(stringResource(R.string.web_support_zoom), store.supportZoom) { store.supportZoom = it; bump() } }
            item { SwitchItem(stringResource(R.string.web_zoom_controls), store.builtInZoomControls) { store.builtInZoomControls = it; bump() } }
            item { SwitchItem(stringResource(R.string.web_zoom_buttons), store.displayZoomControls) { store.displayZoomControls = it; bump() } }
            item { SwitchItem(stringResource(R.string.web_wide_viewport), store.useWideViewPort) { store.useWideViewPort = it; bump() } }
            item { SwitchItem(stringResource(R.string.web_overview_mode), store.loadWithOverviewMode) { store.loadWithOverviewMode = it; bump() } }

            // ===== 访问 / 安全 =====
            item { SectionHeader(stringResource(R.string.web_group_access)) }
            item { SwitchItem(stringResource(R.string.web_file_access), store.allowFileAccess) { store.allowFileAccess = it; bump() } }
            item { SwitchItem(stringResource(R.string.web_content_access), store.allowContentAccess) { store.allowContentAccess = it; bump() } }
            item { SwitchItem(stringResource(R.string.web_file_url_access), store.allowFileAccessFromFileURLs) { store.allowFileAccessFromFileURLs = it; bump() } }
            item { SwitchItem(stringResource(R.string.web_universal_access), store.allowUniversalAccessFromFileURLs) { store.allowUniversalAccessFromFileURLs = it; bump() } }
            item { SwitchItem(stringResource(R.string.web_safe_browsing), store.safeBrowsingEnabled) { store.safeBrowsingEnabled = it; bump() } }
            item { SwitchItem(stringResource(R.string.web_save_form), store.saveFormData) { store.saveFormData = it; bump() } }

            // ===== 加载 / 缓存 =====
            item { SectionHeader(stringResource(R.string.web_group_loading)) }
            item { SwitchItem(stringResource(R.string.web_load_images), store.loadsImagesAutomatically) { store.loadsImagesAutomatically = it; bump() } }
            item { SwitchItem(stringResource(R.string.web_block_network_image), store.blockNetworkImage) { store.blockNetworkImage = it; bump() } }
            item { SwitchItem(stringResource(R.string.web_block_network_loads), store.blockNetworkLoads) { store.blockNetworkLoads = it; bump() } }
            item {
                ChoiceItem(
                    title = stringResource(R.string.web_cache_mode),
                    options = listOf(
                        stringResource(R.string.web_cache_default),
                        stringResource(R.string.web_cache_else_network),
                        stringResource(R.string.web_cache_no_cache),
                        stringResource(R.string.web_cache_cache_only)
                    ),
                    selected = when (store.cacheMode) {
                        WebSettings.LOAD_CACHE_ELSE_NETWORK -> 1
                        WebSettings.LOAD_NO_CACHE -> 2
                        WebSettings.LOAD_CACHE_ONLY -> 3
                        else -> 0
                    }
                ) { i ->
                    store.cacheMode = when (i) {
                        1 -> WebSettings.LOAD_CACHE_ELSE_NETWORK
                        2 -> WebSettings.LOAD_NO_CACHE
                        3 -> WebSettings.LOAD_CACHE_ONLY
                        else -> WebSettings.LOAD_DEFAULT
                    }; bump()
                }
            }
            item {
                ChoiceItem(
                    title = stringResource(R.string.web_mixed_content),
                    options = listOf(
                        stringResource(R.string.web_mixed_never),
                        stringResource(R.string.web_mixed_always),
                        stringResource(R.string.web_mixed_compat)
                    ),
                    selected = when (store.mixedContentMode) {
                        WebSettings.MIXED_CONTENT_NEVER_ALLOW -> 0
                        WebSettings.MIXED_CONTENT_ALWAYS_ALLOW -> 1
                        else -> 2
                    }
                ) { i ->
                    store.mixedContentMode = when (i) {
                        0 -> WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        1 -> WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        else -> WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    }; bump()
                }
            }

            // ===== 字体 =====
            item { SectionHeader(stringResource(R.string.web_group_font)) }
            item { IntItem(stringResource(R.string.web_text_zoom), store.textZoom, 30..300) { store.textZoom = it; bump() } }
            item { IntItem(stringResource(R.string.web_default_font), store.defaultFontSize, 1..72) { store.defaultFontSize = it; bump() } }
            item { IntItem(stringResource(R.string.web_min_font), store.minimumFontSize, 1..72) { store.minimumFontSize = it; bump() } }
            item { TextItem(stringResource(R.string.web_font_family), store.standardFontFamily) { store.standardFontFamily = it; bump() } }
            item { TextItem(stringResource(R.string.web_encoding), store.defaultTextEncodingName) { store.defaultTextEncodingName = it; bump() } }

            // ===== 用户代理 =====
            item { SectionHeader(stringResource(R.string.web_group_ua)) }
            item { TextItem(stringResource(R.string.web_user_agent), store.userAgent, placeholder = stringResource(R.string.web_ua_hint)) { store.userAgent = it; bump() } }

            // ===== 代理 =====
            item { SectionHeader(stringResource(R.string.web_group_proxy)) }
            item { TextItem(stringResource(R.string.web_proxy_host), store.proxyHost, placeholder = "127.0.0.1") { store.proxyHost = it; bump() } }
            item { IntItem(stringResource(R.string.web_proxy_port), store.proxyPort, 1..65535) { store.proxyPort = it; bump() } }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = Primary,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp)
        )
        HorizontalDivider()
    }
}

@Composable
private fun SwitchItem(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun IntItem(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = text,
            onValueChange = { s ->
                text = s.filter(Char::isDigit)
                text.toIntOrNull()?.let { if (it in range) onChange(it) }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(90.dp)
        )
    }
}

@Composable
private fun TextItem(label: String, value: String, placeholder: String = "", onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; onChange(it) },
            singleLine = true,
            placeholder = { Text(placeholder) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceItem(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, opt ->
                SegmentedButton(
                    selected = selected == i,
                    onClick = { onSelect(i) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size)
                ) { Text(opt, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}
