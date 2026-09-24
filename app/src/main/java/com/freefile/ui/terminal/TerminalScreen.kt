package com.freefile.ui.terminal

import android.graphics.Typeface
import android.util.Log
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.freefile.R
import com.freefile.terminal.TerminalManager
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.tooling.preview.Preview


/**
 * 终端模拟器页（Sprint 5 + 任务 6 + 多标签扩展）。
 *
 * 重构要点（用户反馈 2026-09-05）：
 *  1. **多标签 + 独立环境**：每个标签独立持有 [TerminalSession]，独立 PTY / cwd / env。
 *     切换标签时把对应会话 attach 到同一个 [TerminalView]；新建标签默认普通 sh。
 *  2. **长按 Ctrl 锁定 + 修饰键状态机**：[ModState] 三态（OFF/ONCE/LOCK）。
 *     - 轻点 Ctrl = ONCE：下一个非修饰键发送后自动释放；
 *     - 长按 Ctrl = LOCK：粘滞，连续发送 Ctrl 组合键；
 *     - 再点 Ctrl = OFF。
 *     字母键 + Ctrl 发送 0x01-0x1A；Alt 暂仅做修饰标记（Termux 自身 Alt 组合较少使用）。
 *  3. **返回手势拦截**：[BackHandler] 始终启用并实现「再按一次返回退出」，
 *     防止误触屏幕边缘（系统预测式返回手势）直接退出终端。
 *  4. **输入法不灵敏修复**：
 *     - `TerminalViewClient.isTerminalViewSelected()` 始终返回 true（原代码返回 view.hasFocus()，
 *       焦点未到位时 IMM 拿到 null InputConnection → 不弹键盘）；
 *     - 新增 OnTouchListener：ACTION_UP 时 requestFocus + showSoftInput(SHOW_FORCED)；
 *     - 顶栏「键盘」按钮：显式 force-show IME；
 *     - `onSingleTapUp` 回调里也请求焦点 + 弹 IME。
 *
 * 任务 6：通过 [initialPaths] 接收外部路径（来自主页 ActionSheet「在终端中打开」），
 * 会话就绪后自动 `cd` 到目标目录并执行 `ls -la <文件名>`，仅作用于首个标签。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TerminalScreen(
    initialPaths: List<String> = emptyList(),
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val tabs = remember { mutableStateListOf<TerminalTab>() }
    var activeId by remember { mutableStateOf<String?>(null) }
    // 文本变化/会话状态变化时 +1，触发 AndroidView update
    var textTick by remember { mutableStateOf(0) }
    // 双击返回退出
    var lastBackTime by remember { mutableStateOf(0L) }
    // 功能键行（F1-F12）是否展开
    var showFnRow by remember { mutableStateOf(false) }
    // 修饰键状态机
    var ctrlState by remember { mutableStateOf(ModState.OFF) }
    var altState by remember { mutableStateOf(ModState.OFF) }
    // 任务 6：是否已对 initialPaths 执行过 cd/ls
    var pathsHandled by remember { mutableStateOf(false) }
    // 持有 TerminalView 引用，供顶栏「键盘」按钮 force-show IME
    var terminalViewRef by remember { mutableStateOf<TerminalView?>(null) }
    // 页面销毁标记：避免 DisposableEffect 清理时 onSessionFinished 回调再次操作 tabs
    var disposing by remember { mutableStateOf(false) }
    // 侧拉栏（标签列表）状态
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    // 重命名目标标签
    var renameTarget by remember { mutableStateOf<TerminalTab?>(null) }
    var renameInput by remember { mutableStateOf("") }
    // 新建标签类型选择对话框
    var showNewTabDialog by remember { mutableStateOf(false) }
    // 自定义解释器路径输入
    var customShellInput by remember { mutableStateOf("") }

    /**
     * 会话结束处理：标记标签为已结束；若为活动标签则自动关闭并切到下一个，
     * 所有标签都结束后返回主页。避免结束的会话卡在「正在初始化」界面。
     */
    fun handleTabFinished(id: String) {
        if (disposing) return
        val tab = tabs.firstOrNull { it.id == id } ?: return
        tab.finished = true
        Log.d(TAG, "tab finished: $id")
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx >= 0) {
            tabs.removeAt(idx)
            if (activeId == id) {
                activeId = tabs.firstOrNull()?.id
            }
        }
        if (tabs.isEmpty()) {
            onBack()
        } else {
            textTick++
        }
    }

    val imm = remember(context) {
        context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    }
    val toastPressAgain = stringResource(R.string.terminal_press_back_again)

    // 首次进入：创建一个普通 sh 标签
    LaunchedEffect(Unit) {
        if (tabs.isEmpty()) {
            try {
                val t = createTab(
                    context = context,
                    mode = TerminalManager.Mode.NORMAL,
                    customShell = null,
                    customArgs = emptyArray(),
                    onChanged = { textTick++ },
                    onFinished = { id -> handleTabFinished(id) }
                )
                tabs.add(t)
                activeId = t.id
                Log.d(TAG, "initial tab created: ${t.id}")
            } catch (e: Throwable) {
                Log.e(TAG, "initial tab failed", e)
                Toast.makeText(context, e.message ?: e.toString(), Toast.LENGTH_LONG).show()
            }
        }
    }

    // 任务 6：会话就绪后，对首个标签执行 cd + ls -la（仅一次）
    LaunchedEffect(tabs.size, initialPaths) {
        if (tabs.isEmpty() || initialPaths.isEmpty() || pathsHandled) return@LaunchedEffect
        val first = tabs.first()
        if (!first.session.isRunning) return@LaunchedEffect
        try {
            val (dir, files) = resolveInitialPaths(initialPaths)
            if (dir != null) {
                val cmd = buildString {
                    append("cd ").append(shellQuote(dir)).append(" && clear")
                    when {
                        files.size == 1 -> append(" && ls -la ").append(shellQuote(files[0]))
                        files.isEmpty() -> append(" && ls -la")
                        else -> {
                            append(" && ls -la")
                            files.forEach { append(' ').append(shellQuote(it)) }
                        }
                    }
                }
                Log.d(TAG, "sending initial command: $cmd")
                first.session.write("$cmd\n")
                Toast.makeText(
                    context,
                    context.getString(R.string.terminal_cd_hint, dir),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                val lsCmd = buildString {
                    append("ls -la")
                    initialPaths.forEach { append(' ').append(shellQuote(it)) }
                }
                Log.d(TAG, "sending ls command (no cd): $lsCmd")
                first.session.write("$lsCmd\n")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "initialPaths handling failed", e)
        } finally {
            pathsHandled = true
        }
    }

    // 离开页面：销毁所有会话
    DisposableEffect(Unit) {
        onDispose {
            disposing = true
            tabs.forEach { it.session.finishIfRunning() }
            tabs.clear()
            activeId = null
            terminalViewRef = null
        }
    }

    // 返回手势拦截：双击退出，防止误触边缘直接退出
    BackHandler(enabled = true) {
        val now = System.currentTimeMillis()
        if (now - lastBackTime < 2000L) {
            onBack()
        } else {
            lastBackTime = now
            Toast.makeText(context, toastPressAgain, Toast.LENGTH_SHORT).show()
        }
    }

    val activeTab = tabs.firstOrNull { it.id == activeId }

    fun tryExit() {
        val now = System.currentTimeMillis()
        if (now - lastBackTime < 2000L) {
            onBack()
        } else {
            lastBackTime = now
            Toast.makeText(context, toastPressAgain, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 新建标签，支持自定义解释器路径（[Mode.CUSTOM]）。
     * [customShell] / [customArgs] 仅在 [Mode.CUSTOM] 时生效。
     */
    fun addTab(
        mode: TerminalManager.Mode,
        customShell: String? = null,
        customArgs: Array<String> = emptyArray()
    ) {
        try {
            val t = createTab(
                context = context,
                mode = mode,
                customShell = customShell,
                customArgs = customArgs,
                onChanged = { textTick++ },
                onFinished = { id -> handleTabFinished(id) }
            )
            tabs.add(t)
            activeId = t.id
            textTick++
        } catch (e: Throwable) {
            Log.e(TAG, "addTab failed", e)
            Toast.makeText(context, e.message ?: e.toString(), Toast.LENGTH_LONG).show()
        }
    }

    fun closeTab(id: String) {
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        tabs[idx].session.finishIfRunning()
        tabs.removeAt(idx)
        if (activeId == id) {
            activeId = tabs.firstOrNull()?.id
        }
        if (tabs.isEmpty()) {
            // 没有标签了，自动返回主页
            onBack()
        } else {
            textTick++
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            Surface(color = Color(0xFF1A1A1A), modifier = Modifier.fillMaxHeight()) {
                Column(modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)) {
                    Text(
                        stringResource(R.string.terminal_tabs),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    tabs.forEach { tab ->
                        val isActive = tab.id == activeId
                        NavigationDrawerItem(
                            label = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        tab.title,
                                        color = if (isActive) Color.White else Color(0xFFB0B0B0)
                                    )
                                    Row {
                                        IconButton(onClick = {
                                            renameTarget = tab
                                            renameInput = tab.title
                                        }) {
                                            Icon(
                                                Icons.Rounded.Edit,
                                                contentDescription = stringResource(R.string.rename),
                                                tint = Color(0xFFB0B0B0)
                                            )
                                        }
                                        IconButton(onClick = { closeTab(tab.id) }) {
                                            Icon(
                                                Icons.Rounded.Delete,
                                                contentDescription = stringResource(R.string.terminal_close_tab),
                                                tint = Color(0xFFB0B0B0)
                                            )
                                        }
                                    }
                                }
                            },
                            selected = isActive,
                            onClick = {
                                if (activeId != tab.id) {
                                    activeId = tab.id
                                    textTick++
                                }
                                drawerScope.launch { drawerState.close() }
                            }
                        )
                    }
                    // 新建标签按钮
                    NavigationDrawerItem(
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Add, contentDescription = null, tint = Color(0xFF3B6EA5))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.terminal_new_tab), color = Color(0xFF3B6EA5))
                            }
                        },
                        selected = false,
                        onClick = {
                            showNewTabDialog = true
                            drawerScope.launch { drawerState.close() }
                        }
                    )
                }
            }
        }
    ) {
        Scaffold(
            containerColor = Color.Black,
            topBar = {
                Surface(color = Color.Black) {
                    TopAppBar(
                        colors = TopAppBarDefaults.smallTopAppBarColors(
                            containerColor = Color.Black
                        ),
                        title = {
                            Text(
                                text = stringResource(R.string.terminal_title) +
                                    (activeTab?.let { "  ·  ${it.title}" } ?: ""),
                                color = Color.White
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { drawerScope.launch { drawerState.open() } }) {
                                Icon(Icons.Rounded.Menu, contentDescription = stringResource(R.string.terminal_tabs), tint = Color.White)
                            }
                        },
                        actions = {
                            IconButton(onClick = { tryExit() }) {
                                Icon(Icons.Rounded.ArrowBack, contentDescription = null, tint = Color.White)
                            }
                            IconButton(onClick = { addTab(TerminalManager.Mode.NORMAL) }) {
                                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.terminal_new_tab), tint = Color.White)
                            }
                            // 更多：弹出解释器选择（普通 / Root / Termux bash / 自定义路径）
                            IconButton(onClick = { showNewTabDialog = true }) {
                                Icon(Icons.Rounded.MoreHoriz, contentDescription = stringResource(R.string.terminal_new_tab_more), tint = Color.White)
                            }
                            IconButton(onClick = {
                                val v = terminalViewRef
                                if (v != null) {
                                    v.requestFocus()
                                    imm?.showSoftInput(v, InputMethodManager.SHOW_FORCED)
                                } else {
                                    Toast.makeText(context, R.string.terminal_loading, Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Rounded.Keyboard, contentDescription = stringResource(R.string.terminal_keyboard), tint = Color.White)
                            }
                        }
                    )
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                // 按键栏放在顶部（终端视图上方），避免被终端窗口遮挡
                KeyBoardBar(
                    ctrlState = ctrlState,
                    altState = altState,
                    showFnRow = showFnRow,
                    onCtrlChange = { ctrlState = it },
                    onAltChange = { altState = it },
                    onToggleFnRow = { showFnRow = !showFnRow },
                    onSend = { data, isLetterKey ->
                        val tab = activeTab ?: return@KeyBoardBar
                        val s = tab.session
                        if (!s.isRunning) return@KeyBoardBar
                        val out = when {
                            ctrlState != ModState.OFF && isLetterKey && data.length == 1 -> {
                                val c = data[0]
                                if (c in 'a'..'z' || c in 'A'..'Z') {
                                    val base = if (c in 'a'..'z') 'a' else 'A'
                                    (c - base + 1).toInt().toChar().toString()
                                } else data
                            }
                            else -> data
                        }
                        s.write(out)
                        if (ctrlState == ModState.ONCE) ctrlState = ModState.OFF
                        if (altState == ModState.ONCE) altState = ModState.OFF
                    }
                )

                // 终端视图：仅当有活跃标签且会话运行时渲染
                if (activeTab != null && activeTab.session.isRunning) {
                    AndroidView(
                        factory = { ctx ->
                            val view = TerminalView(ctx, null).apply {
                                ensureTerminalViewClient(this)
                                setTextSize(DEFAULT_FONT_SIZE)
                                setTypeface(Typeface.MONOSPACE)
                                isFocusable = true
                                isFocusableInTouchMode = true
                                setOnTouchListener { v, ev ->
                                    if (ev.action == MotionEvent.ACTION_UP) {
                                        v.performClick()
                                        v.requestFocus()
                                        val imm2 = v.context
                                            .getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                                            as? InputMethodManager
                                        imm2?.showSoftInput(v, InputMethodManager.SHOW_FORCED)
                                    }
                                    false
                                }
                                onFocusChangeListener = android.view.View.OnFocusChangeListener { v, hasFocus ->
                                    if (hasFocus) {
                                        val imm2 = v.context
                                            .getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                                            as? InputMethodManager
                                        imm2?.showSoftInput(v, InputMethodManager.SHOW_FORCED)
                                    }
                                }
                                setOnClickListener { requestFocus() }
                            }
                            try {
                                view.attachSession(activeTab.session)
                                view.onScreenUpdated()
                                terminalViewRef = view
                                view.post {
                                    view.requestFocus()
                                    imm?.showSoftInput(view, InputMethodManager.SHOW_FORCED)
                                }
                                Log.d(TAG, "TerminalView factory done, attached session=${activeTab.id}")
                            } catch (e: Throwable) {
                                Log.e(TAG, "factory attachSession failed", e)
                            }
                            view
                        },
                        update = { view ->
                            textTick
                            ensureTerminalViewClient(view)
                            val s = activeTab.session
                            if (view.mEmulator == null || view.mTermSession !== s) {
                                view.attachSession(s)
                            }
                            view.onScreenUpdated()
                            terminalViewRef = view
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.terminal_loading),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }

    // 重命名对话框
    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.rename)) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renameTarget?.let { it.title = renameInput.ifBlank { it.title } }
                    textTick++
                    renameTarget = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // 新建标签：解释器类型选择对话框
    if (showNewTabDialog) {
        AlertDialog(
            onDismissRequest = { showNewTabDialog = false },
            title = { Text(stringResource(R.string.terminal_new_tab)) },
            text = {
                Column {
                    // 普通 sh
                    TextButton(
                        onClick = {
                            addTab(TerminalManager.Mode.NORMAL)
                            showNewTabDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.terminal_mode_normal) + "  (/system/bin/sh)")
                    }
                    // Root su
                    TextButton(
                        onClick = {
                            addTab(TerminalManager.Mode.ROOT)
                            showNewTabDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.terminal_mode_root) + "  (su)")
                    }
                    // Termux bash
                    TextButton(
                        onClick = {
                            addTab(TerminalManager.Mode.TERMUX)
                            showNewTabDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.terminal_mode_termux) + "  (Termux bash)")
                    }
                    // 自定义解释器路径
                    Spacer(Modifier.padding(vertical = 4.dp))
                    Text(
                        stringResource(R.string.terminal_custom_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = customShellInput,
                        onValueChange = { customShellInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.terminal_custom_placeholder)) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (customShellInput.isNotBlank()) {
                            addTab(
                                mode = TerminalManager.Mode.CUSTOM,
                                customShell = customShellInput.trim(),
                                customArgs = emptyArray()
                            )
                        }
                        customShellInput = ""
                        showNewTabDialog = false
                    },
                    enabled = customShellInput.isNotBlank()
                ) { Text(stringResource(R.string.terminal_custom_open)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    customShellInput = ""
                    showNewTabDialog = false
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

// ==================== 多标签数据 / 工厂 ====================

/**
 * 单个标签状态：独立 [TerminalSession] + 自身的 client 转发。
 */
private class TerminalTab(
    val id: String,
    val mode: TerminalManager.Mode,
    val session: TerminalSession,
    var title: String,
    val customShell: String? = null,
    var finished: Boolean = false
)

/**
 * 每个标签独立 client，把 Termux 回调转发为 lambda。
 * onFinished 携带 tab id，便于定位并清理已结束会话。
 */
private class TabSessionClient(
    private val tabId: String,
    private val onTextChanged: () -> Unit,
    private val onFinished: (String) -> Unit
) : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession?) = onTextChanged()
    override fun onTitleChanged(changedSession: TerminalSession?) {}
    override fun onSessionFinished(finishedSession: TerminalSession?) {
        onFinished(tabId)
    }
    override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) {}
    override fun onPasteTextFromClipboard(session: TerminalSession?) {}
    override fun onBell(session: TerminalSession?) {}
    override fun onColorsChanged(session: TerminalSession?) {}
    override fun onTerminalCursorStateChange(visible: Boolean) {}
    override fun getTerminalCursorStyle(): Int = 0
    override fun logError(tag: String?, msg: String?) {}
    override fun logWarn(tag: String?, msg: String?) {}
    override fun logInfo(tag: String?, msg: String?) {}
    override fun logDebug(tag: String?, msg: String?) {}
    override fun logVerbose(tag: String?, msg: String?) {}
    override fun logStackTraceWithMessage(tag: String?, msg: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}

/** 工厂：创建一个新标签（含独立会话）。 */
private fun createTab(
    context: android.content.Context,
    mode: TerminalManager.Mode,
    customShell: String?,
    customArgs: Array<String>,
    onChanged: () -> Unit,
    onFinished: (String) -> Unit
): TerminalTab {
    val id = UUID.randomUUID().toString()
    val client = TabSessionClient(id, onChanged, onFinished)
    val session = TerminalManager.createSession(
        context = context,
        mode = mode,
        client = client,
        customShell = customShell,
        customArgs = customArgs
    )
    val title = when (mode) {
        TerminalManager.Mode.NORMAL -> "sh"
        TerminalManager.Mode.ROOT -> "root"
        TerminalManager.Mode.TERMUX -> "bash"
        TerminalManager.Mode.CUSTOM -> customShell?.substringAfterLast('/') ?: "custom"
    }
    return TerminalTab(
        id = id,
        mode = mode,
        session = session,
        title = title,
        customShell = customShell
    )
}

/** 修饰键状态机。 */
private enum class ModState { OFF, ONCE, LOCK }

// ==================== 标签栏 ====================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TerminalTabBar(
    tabs: List<TerminalTab>,
    activeId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onAddNormal: () -> Unit,
    onAddRoot: () -> Unit
) {
    Surface(color = Color(0xFF1A1A1A)) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(tabs, key = { it.id }) { tab ->
                val isActive = tab.id == activeId
                val tabLabel = tab.title + if (tab.mode == TerminalManager.Mode.ROOT) "" else ""
                Surface(
                    color = if (isActive) Color(0xFF3B6EA5) else Color(0xFF2D2D2D),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.combinedClickable(
                        onClick = { onSelect(tab.id) },
                        onLongClick = { onClose(tab.id) }
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            tabLabel,
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium
                        )
                        // 关闭按钮（点击触发，避免长按才关闭）
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.terminal_close_tab),
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .combinedClickable(onClick = { onClose(tab.id) })
                        )
                    }
                }
            }
            // 尾部「+」按钮：显眼的新建标签入口（普通 sh）
            item(key = "add_normal") {
                Surface(
                    color = Color(0xFF1F3A5F),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.combinedClickable(
                        onClick = onAddNormal,
                        onLongClick = onAddRoot
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.terminal_new_tab),
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.terminal_new_tab_short),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

// ==================== 按键栏 ====================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeyBoardBar(
    ctrlState: ModState,
    altState: ModState,
    showFnRow: Boolean,
    onCtrlChange: (ModState) -> Unit,
    onAltChange: (ModState) -> Unit,
    onToggleFnRow: () -> Unit,
    onSend: (data: String, isLetterKey: Boolean) -> Unit
) {
    Surface(color = Color.Black) {
        Column {
            // 行 1：修饰键 + 方向键 + 翻页
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Ctrl：长按锁定，轻点单次
                val ctrlLabel = when (ctrlState) {
                    ModState.OFF -> "Ctrl"
                    ModState.ONCE -> "CTRL ✱"
                    ModState.LOCK -> "CTRL ✓"
                }
                ModifierKey(
                    label = ctrlLabel,
                    state = ctrlState,
                    onClick = { onCtrlChange(if (ctrlState == ModState.OFF) ModState.ONCE else ModState.OFF) },
                    onLongClick = { onCtrlChange(if (ctrlState == ModState.LOCK) ModState.OFF else ModState.LOCK) }
                )
                // Alt
                val altLabel = when (altState) {
                    ModState.OFF -> "Alt"
                    ModState.ONCE -> "ALT ✱"
                    ModState.LOCK -> "ALT ✓"
                }
                ModifierKey(
                    label = altLabel,
                    state = altState,
                    onClick = { onAltChange(if (altState == ModState.OFF) ModState.ONCE else ModState.OFF) },
                    onLongClick = { onAltChange(if (altState == ModState.LOCK) ModState.OFF else ModState.LOCK) }
                )
                TerminalKey("Esc") { onSend("\u001b", false) }
                TerminalKey("Tab") { onSend("\t", false) }
                TerminalKey("↑") { onSend("\u001b[A", false) }
                TerminalKey("↓") { onSend("\u001b[B", false) }
                TerminalKey("←") { onSend("\u001b[D", false) }
                TerminalKey("→") { onSend("\u001b[C", false) }
                TerminalKey("Home") { onSend("\u001b[H", false) }
                TerminalKey("End") { onSend("\u001b[F", false) }
                TerminalKey("PgUp") { onSend("\u001b[5~", false) }
                TerminalKey("PgDn") { onSend("\u001b[6~", false) }
                TerminalKey("Fn", highlight = showFnRow, onClick = onToggleFnRow)
            }
            // 行 2：常用 Ctrl 组合 + shell 符号
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TerminalKey("C-c") { onSend("\u0003", false) }   // SIGINT
                TerminalKey("C-d") { onSend("\u0004", false) }   // EOF
                TerminalKey("C-l") { onSend("\u000c", false) }   // 清屏
                TerminalKey("C-z") { onSend("\u001a", false) }   // 挂起
                TerminalKey("C-u") { onSend("\u0015", false) }   // 清行
                TerminalKey("C-w") { onSend("\u0017", false) }   // 删前一个单词
                TerminalKey("C-a") { onSend("\u0001", false) }   // 行首
                TerminalKey("C-e") { onSend("\u0005", false) }   // 行尾
                TerminalKey("C-r") { onSend("\u0012", false) }   // 历史搜索
                TerminalKey("/") { onSend("/", false) }
                TerminalKey("-") { onSend("-", false) }
                TerminalKey("|") { onSend("|", false) }
                TerminalKey("~") { onSend("~", false) }
                TerminalKey("..") { onSend("..", false) }
                TerminalKey(" ") { onSend(" ", false) }
            }
            // 行 3（可折叠）：F1-F12 + Ins/Del
            if (showFnRow) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // F1-F12 → ESC O P ~ F1=ESCOP, F2=ESCOQ, F3=ESCOR, F4=ESCOS, F5=ESC[15~...
                    // 通用 ANSI：F1-F4 用 ESC O P/Q/R/S，F5-F12 用 ESC [ 1X ~ 序列
                    val fSeqs = listOf(
                        "\u001bOP" to "F1",
                        "\u001bOQ" to "F2",
                        "\u001bOR" to "F3",
                        "\u001bOS" to "F4",
                        "\u001b[15~" to "F5",
                        "\u001b[17~" to "F6",
                        "\u001b[18~" to "F7",
                        "\u001b[19~" to "F8",
                        "\u001b[20~" to "F9",
                        "\u001b[21~" to "F10",
                        "\u001b[23~" to "F11",
                        "\u001b[24~" to "F12"
                    )
                    fSeqs.forEach { (seq, label) ->
                        TerminalKey(label) { onSend(seq, false) }
                    }
                    TerminalKey("Ins") { onSend("\u001b[2~", false) }
                    TerminalKey("Del") { onSend("\u001b[3~", false) }
                }
            }
        }
    }
}

/** 修饰键按钮（带长按锁定）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModifierKey(
    label: String,
    state: ModState,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        color = when (state) {
            ModState.OFF -> Color(0xFF2D2D2D)
            ModState.ONCE -> Color(0xFFB07A1A)   // 暖色：单次待发送
            ModState.LOCK -> Color(0xFF3B6EA5)   // 蓝色：锁定
        },
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            label,
            color = Color.White,
            modifier = Modifier
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/** 普通按键（支持长按重复发送，模仿 Termux ExtraKeysView 行为）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TerminalKey(
    label: String,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        color = if (highlight) Color(0xFF3B6EA5) else Color(0xFF2D2D2D),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            label,
            color = Color.White,
            modifier = Modifier
                .combinedClickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

// ==================== TerminalViewClient 反射注入 ====================

private const val TAG = "TerminalScreen"

/**
 * Termux 终端字体大小（px）。
 * 原默认 12 px 在现代高 DPI 手机下几乎看不清；调为 36 px。
 */
private const val DEFAULT_FONT_SIZE = 36

/**
 * 为 TerminalViewClient 的 Dynamic Proxy 提供「符合返回类型」的默认值，
 * 避免 `result has type boolean, got kotlin.Unit` 的 IllegalArgumentException。
 */
private fun defaultForReturnType(retType: Class<*>?): Any? = when {
    retType == null -> null
    retType.isPrimitive -> when (retType) {
        java.lang.Boolean.TYPE -> java.lang.Boolean.FALSE
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> 0.toChar()
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }
    else -> null
}

/**
 * 构造 TerminalViewClient 的空实现 Proxy。
 *
 * Item 2 / Item 4（IME 不灵敏）关键修复：
 *  - `isTerminalViewSelected()` 始终返回 **true**（原代码返回 view.hasFocus()，
 *    焦点未到位时 IMM 拿到 null InputConnection → 不弹键盘）；
 *  - `onSingleTapUp(e)` 在用户轻点时主动请求焦点 + 强制弹 IME；
 *  - 其余方法按返回类型给默认值。
 */
private fun createEmptyClientProxy(clientClass: Class<*>, view: TerminalView): Any {
    val imm = view.context
        .getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    return java.lang.reflect.Proxy.newProxyInstance(
        clientClass.classLoader,
        arrayOf(clientClass)
    ) { _: Any, method: java.lang.reflect.Method, _: Array<out Any>? ->
        when (method.name) {
            "isTerminalViewSelected" -> java.lang.Boolean.TRUE
            "onSingleTapUp" -> {
                view.requestFocus()
                imm?.showSoftInput(view, InputMethodManager.SHOW_FORCED)
                null
            }
            "shouldBackButtonBeVisible" -> java.lang.Boolean.TRUE
            "onScale" -> java.lang.Boolean.TRUE
            "shouldEnforceCharSize" -> java.lang.Boolean.FALSE
            "shouldUseCtrlSpaceWorkaround" -> java.lang.Boolean.FALSE
            else -> defaultForReturnType(method.returnType)
        }
    }
}

/**
 * 为 Termux 的 TerminalView 初始化 mTerminalViewClient，避免：
 *  - onSizeChanged → updateSize → `client.onEmulatorSet()` 空对象崩 NPE；
 *  - 输入法 onCreateInputConnection → `isTerminalViewSelected()` 返回 Unit 崩 IAE；
 *  - 输入法不弹出（isTerminalViewSelected 返回 false 导致 InputConnection 为 null）。
 *  - mRenderer 为 null → updateSize 中访问 mFontWidth 崩 NPE（必须确保 renderer 被创建）。
 *
 * Termux v0.118 的 mRenderer 在 setTerminalViewClient() 中创建；
 * 若反射设字段则 renderer 仍为 null，必须兜底创建。
 */
private fun ensureTerminalViewClient(view: TerminalView) {
    val clientClass = try {
        Class.forName("com.termux.view.TerminalViewClient")
    } catch (_: Throwable) {
        Log.w(TAG, "TerminalViewClient class not found; skip client init")
        return
    }
    val client = createEmptyClientProxy(clientClass, view)

    // 1) 优先调用公开 setter（会创建 mRenderer）
    var setterOk = false
    for (name in arrayOf("setTerminalViewClient", "setClient")) {
        try {
            val setter = TerminalView::class.java.getMethod(name, clientClass)
            setter.invoke(view, client)
            setterOk = true
            break
        } catch (_: Throwable) { /* try next name */ }
    }

    // 2) setter 不可用时反射设字段
    if (!setterOk) {
        try {
            val field = TerminalView::class.java.getDeclaredField("mTerminalViewClient")
            field.isAccessible = true
            field.set(view, client)
        } catch (_: Throwable) { /* ignore */ }
    }

    // 3) 兜底：确保 mRenderer 非空（updateSize 会访问 mRenderer.mFontWidth）
    ensureRendererCreated(view, clientClass)
}

/**
 * 检查并创建 mRenderer。Termux 的 renderer 在 setTerminalViewClient 中创建；
 * 若通过反射设字段导致 renderer 为 null，这里尝试构造 TerminalRenderer 并注入。
 */
private fun ensureRendererCreated(view: TerminalView, clientClass: Class<*>) {
    try {
        val rendererField = TerminalView::class.java.getDeclaredField("mRenderer")
        rendererField.isAccessible = true
        if (rendererField.get(view) != null) return  // 已创建

        // 尝试通过 setTextSize 触发创建（部分版本 setTextSize 内部会 new renderer）
        runCatching { view.setTextSize(DEFAULT_FONT_SIZE) }
        if (rendererField.get(view) != null) return

        // 反射构造 TerminalRenderer：构造签名 (int textSize, TextStyle[] colorScheme, int[] defaultColors)
        val rendererClass = Class.forName("com.termux.view.TerminalRenderer")
        val textStyleClass = Class.forName("com.termux.view.TextStyle")
        val colorScheme = java.lang.reflect.Array.newInstance(textStyleClass, 256) as Array<*>
        val defaultColors = IntArray(256)
        // Termux 的配色：用黑色背景、白色前景的默认值
        for (i in 0 until 256) defaultColors[i] = -0x1000000 or 0x00FFFFFF
        val ctor = rendererClass.getConstructor(
            Int::class.javaPrimitiveType,
            colorScheme.javaClass,
            defaultColors.javaClass
        )
        val renderer = ctor.newInstance(DEFAULT_FONT_SIZE, colorScheme, defaultColors)
        rendererField.set(view, renderer)
        Log.i(TAG, "mRenderer created via reflection fallback")
    } catch (t: Throwable) {
        Log.w(TAG, "ensureRendererCreated failed: ${t.message}")
    }
}

// ==================== 路径解析（任务 6） ====================

/**
 * 解析外部传入的路径列表，确定要 `cd` 的目标目录和要 `ls -la` 的文件名。
 */
private fun resolveInitialPaths(paths: List<String>): Pair<String?, List<String>> {
    if (paths.isEmpty()) return Pair(null, emptyList())
    val firstPath = paths.first().trimEnd('/')
    if (firstPath.isEmpty() || firstPath == "/") return Pair("/", emptyList())

    val firstFile = File(firstPath)
    val targetDir = when {
        firstFile.isDirectory -> firstPath
        firstFile.exists() -> firstFile.parent ?: firstPath
        else -> {
            val idx = firstPath.lastIndexOf('/')
            if (idx > 0) firstPath.substring(0, idx) else "/"
        }
    }
    val fileNames = paths.mapNotNull { p ->
        val f = File(p)
        val parent = f.parent
        if (f.isDirectory) null
        else if (parent != null && File(targetDir).absolutePath == File(parent).absolutePath) {
            f.name
        } else if (parent == null && targetDir == "/") {
            f.name
        } else null
    }
    return Pair(targetDir, fileNames)
}

/**
 * 对路径做单引号转义，避免空格 / 特殊字符破坏 shell 命令。
 */
private fun shellQuote(s: String): String {
    if (s.isEmpty()) return "''"
    if (s.all { it.isLetterOrDigit() || it in setOf('/', '.', '-', '_', '+', ':', '~', '=') }) {
        return s
    }
    return "'" + s.replace("'", "'\\''") + "'"
}
