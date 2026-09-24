package com.freefile.ui

import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.CenterFocusWeak
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FirstPage
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LastPage
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SendToMobile
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.freefile.FreeFileApp
import com.freefile.R
import com.freefile.core.permission.PermissionManager
import com.freefile.core.permission.PermissionMode
import com.freefile.data.db.Bookmark
import com.freefile.data.db.History
import com.freefile.data.db.RecentFile
import com.freefile.data.db.TrashItem
import com.freefile.data.settings.AppSettings
import com.freefile.util.FileOpener
import com.freefile.util.Format
import com.freefile.operations.Conflict
import com.freefile.operations.ConflictResolution
import com.freefile.operations.FileOperations
import com.freefile.operations.OpKind
import com.freefile.operations.TransferManager
import com.freefile.search.IndexState
import com.freefile.search.SearchEngine
import com.freefile.ui.core.DualPaneLayout
import com.freefile.ui.core.Breadcrumb
import com.freefile.ui.core.FilePane
import com.freefile.ui.dialogs.BatchRenameDialog
import com.freefile.ui.dialogs.ConfirmDeleteDialog
import com.freefile.ui.dialogs.ConfirmExtractDialog
import com.freefile.ui.dialogs.ConflictDialog
import com.freefile.ui.dialogs.JumpToPathDialog
import com.freefile.ui.dialogs.NewNodeDialog
import com.freefile.ui.dialogs.PropertiesDialog
import com.freefile.ui.dialogs.SearchSettingsDialog
import com.freefile.ui.dialogs.TaskProgressCard
import com.freefile.ui.dialogs.TextInputDialog
import com.freefile.ui.files.FileClipboard
import com.freefile.ui.files.FilterMode
import com.freefile.ui.files.FileTypes
import com.freefile.ui.files.FileViewModel
import com.freefile.ui.files.ViewMode
import com.freefile.ui.network.AddNetworkDialog
import com.freefile.ui.tabs.Pane
import com.freefile.ui.tabs.TabManager
import com.freefile.ui.tabs.TabDrawerContent
import com.freefile.ui.theme.FreeFileTheme
import com.freefile.vfs.ArchiveEngine
import com.freefile.vfs.EngineSelector
import com.freefile.vfs.VfsEntry
import com.freefile.vfs.remote.RemoteEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 挂起的待处理动作（驱动各类对话框）。 */
private sealed interface PendingAction {
    data class Delete(val entries: List<VfsEntry>, val vm: FileViewModel) : PendingAction
    data class Properties(val entry: VfsEntry) : PendingAction
    data class Rename(val entry: VfsEntry, val vm: FileViewModel) : PendingAction
    data class BatchRename(val entries: List<VfsEntry>, val vm: FileViewModel) : PendingAction
    data class NewNode(val isDir: Boolean?, val vm: FileViewModel) : PendingAction
    data class Extract(val archivePath: String) : PendingAction
    data class Conflicts(
        val kind: OpKind,
        val entries: List<VfsEntry>,
        val targetDir: String,
        val conflicts: List<Conflict>
    ) : PendingAction
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val repo = FreeFileApp.instance.repository
    val permissionManager = FreeFileApp.instance.permissionManager
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // 左抽屉：功能导航；右抽屉：标签页管理
    val leftDrawerState = rememberDrawerState(DrawerValue.Closed)
    val rightDrawerState = rememberDrawerState(DrawerValue.Closed)

    val leftVm: FileViewModel = viewModel(key = "left") { FileViewModel(0, repo) }
    val rightVm: FileViewModel = viewModel(key = "right") { FileViewModel(1, repo) }
    val tabManager = remember { TabManager() }

    var activePane by remember { mutableStateOf(Pane.LEFT) }
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    var dividerPosition by remember { mutableStateOf(0.5f) }
    // 右窗格临时隐藏/显示（单窗口模式）
    var showRightPane by remember { mutableStateOf(true) }
    // 设置/脚本/快传已改为独立 Activity（见 SettingsActivity / ScriptActivity / TransferActivity）
    var showAddNetwork by remember { mutableStateOf(false) }
    // 底栏展开区：搜索 / 过滤
    var showSearch by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    // 搜索设置 Dialog
    var showSearchDialog by remember { mutableStateOf(false) }
    // 跳转对话框
    var showJump by remember { mutableStateOf(false) }
    // 多选操作面板（需求 4：操作按钮触发，含分享/系统打开/内置模块/文件操作）
    var showActionSheet by remember { mutableStateOf(false) }
    // 打开方式 Activity 结果回传：仅「解压此压缩包」回传路径，触发 PendingAction.Extract
    val openWithLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val extractPath = result.data
                ?.getStringExtra(com.freefile.ui.OpenWithActivity.EXTRA_EXTRACT_PATH)
            if (extractPath != null) pending = PendingAction.Extract(extractPath)
        }
    }

    // ==================== 独立 Activity 启动（新任务栈） ====================
    fun launchTerminal(paths: List<String> = emptyList()) {
        val intent = android.content.Intent(context, com.freefile.ui.TerminalActivity::class.java)
            .putStringArrayListExtra(
                com.freefile.ui.TerminalActivity.EXTRA_INITIAL_PATHS,
                ArrayList(paths)
            )
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun launchTextEditor(filePath: String) {
        val intent = android.content.Intent(context, com.freefile.ui.TextEditorActivity::class.java)
            .putExtra(com.freefile.ui.TextEditorActivity.EXTRA_FILE_PATH, filePath)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun launchToolTray(pendingFilePath: String? = null) {
        val intent = android.content.Intent(context, com.freefile.ui.ToolTrayActivity::class.java)
            .putExtra(com.freefile.ui.ToolTrayActivity.EXTRA_PENDING_FILE_PATH, pendingFilePath)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun launchSettings() {
        val intent = android.content.Intent(context, com.freefile.ui.SettingsActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun launchScript() {
        val intent = android.content.Intent(context, com.freefile.ui.ScriptActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun launchTransfer() {
        val intent = android.content.Intent(context, com.freefile.ui.TransferActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // 打开方式 Activity：单击文件 / 多选面板「内置模块打开」统一入口
    fun launchOpenWith(entry: VfsEntry) {
        val intent = android.content.Intent(context, com.freefile.ui.OpenWithActivity::class.java)
            .putExtra(com.freefile.ui.OpenWithActivity.EXTRA_FILE_PATH, entry.path)
        openWithLauncher.launch(intent)
    }

    val permSnapshot by permissionManager.snapshot.collectAsState()
    val storageSettingLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { scope.launch { permissionManager.refresh() } }

    // 权限快照刷新
    LaunchedEffect(Unit) { permissionManager.refresh() }

    // VM 消息 → Snackbar
    LaunchedEffect(Unit) {
        listOf(leftVm, rightVm).forEach { vm ->
            launch {
                vm.messages.collectLatest { msg -> snackbar.showSnackbar(msg) }
            }
        }
    }

    // 标签 ↔ VM 同步（切换标签时打开对应路径，不产生浏览历史）
    LaunchedEffect(Unit) {
        listOf(Pane.LEFT to leftVm, Pane.RIGHT to rightVm).forEach { (pane, vm) ->
            launch {
                vm.ui.collectLatest { st ->
                    val tab = tabManager.currentTab(pane)
                    if (tab != null && tab.path != st.path) {
                        tabManager.updateCurrentPath(pane, st.path)
                    }
                }
            }
            launch {
                snapshotFlowOf(pane, tabManager).collectLatest { tabPath ->
                    if (tabPath != null && tabPath.isNotBlank() && tabPath != vm.ui.value.path) {
                        vm.openExternal(tabPath)
                    }
                }
            }
        }
    }

    val activeVm = if (activePane == Pane.LEFT) leftVm else rightVm
    val otherVm = if (activePane == Pane.LEFT) rightVm else leftVm
    val activeUi by activeVm.ui.collectAsState()
    val copyLabel = stringResource(R.string.copy)
    val moveLabel = stringResource(R.string.sel_move)

    // 返回键：优先关闭抽屉/子页面，不直接结束 Activity
    BackHandler(
        enabled = leftDrawerState.isOpen || rightDrawerState.isOpen || showAddNetwork
    ) {
        scope.launch {
            if (rightDrawerState.isOpen) rightDrawerState.close()
            if (leftDrawerState.isOpen) leftDrawerState.close()
            showAddNetwork = false
        }
    }

    fun toast(msg: String) = scope.launch { snackbar.showSnackbar(msg) }

    fun doTransfer(
        kind: OpKind,
        entries: List<VfsEntry>,
        targetDir: String,
        conflicts: List<Conflict>,
        resolution: ConflictResolution
    ) {
        when (kind) {
            OpKind.COPY -> TransferManager.enqueueCopy(entries, targetDir, conflicts, resolution) { ok, msg ->
                scope.launch { if (!ok) toast(msg) }
            }
            OpKind.MOVE -> TransferManager.enqueueMove(entries, targetDir, conflicts, resolution) { ok, msg ->
                scope.launch { if (!ok) toast(msg) }
            }
            else -> {}
        }
        activeVm.clearSelection()
    }

    /** 发起跨窗格复制/移动（预检冲突）。 */
    fun startTransfer(kind: OpKind) {
        val entries = activeVm.selectedEntries()
        if (entries.isEmpty()) return
        val targetDir = otherVm.ui.value.path
        if (targetDir.isBlank()) {
            toast(context.getString(R.string.msg_other_pane_empty))
            return
        }
        scope.launch {
            try {
                val conflicts = withContext(Dispatchers.IO) {
                    FileOperations.detectConflicts(entries, targetDir)
                }
                if (conflicts.isEmpty()) {
                    doTransfer(kind, entries, targetDir, emptyList(), ConflictResolution.OverwriteAll)
                } else {
                    pending = PendingAction.Conflicts(kind, entries, targetDir, conflicts)
                }
            } catch (e: Exception) {
                toast(context.getString(R.string.msg_precheck_failed, e.message ?: ""))
            }
        }
    }

    // ==================== 抽屉（左：功能导航 / 右：标签页） ====================

    ModalNavigationDrawer(
        drawerState = leftDrawerState,
        drawerContent = {
            DrawerContent(
                permSnapshot = permSnapshot,
                onSetMode = { permissionManager.setMode(it) },
                onNavigate = { path ->
                    activeVm.open(path)
                    scope.launch { leftDrawerState.close() }
                },
                onOpenInOther = { path ->
                    otherVm.open(path)
                    scope.launch { leftDrawerState.close() }
                },
                onLocateFile = { filePath ->
                    // 需求 6：最近文件点击 → 跳转并定位到所在目录（高亮该文件）
                    activeVm.locateTo(filePath)
                    scope.launch { leftDrawerState.close() }
                },
                onMessage = { toast(it) },
                onTrashChanged = {
                    leftVm.refresh()
                    rightVm.refresh()
                },
                onOpenSettings = {
                    scope.launch { leftDrawerState.close() }
                    launchSettings()
                },
                onOpenTerminal = {
                    scope.launch { leftDrawerState.close() }
                    launchTerminal()
                },
                onOpenScript = {
                    scope.launch { leftDrawerState.close() }
                    launchScript()
                },
                onOpenShare = {
                    scope.launch { leftDrawerState.close() }
                    launchTransfer()
                },
                onOpenToolTray = {
                    scope.launch { leftDrawerState.close() }
                    launchToolTray()
                },
                onAddNetwork = {
                    scope.launch { leftDrawerState.close() }
                    showAddNetwork = true
                },
                onClose = { scope.launch { leftDrawerState.close() } }
            )
        }
    ) {
        ModalNavigationDrawer(
            drawerState = rightDrawerState,
            drawerContent = {
                TabDrawerContent(
                    tabManager = tabManager,
                    activePane = activePane,
                    onSelectTab = { pane, index ->
                        tabManager.selectTab(pane, index)
                        // 立即同步路径并激活对应窗格（人性化的焦点跟随）
                        val vm = if (pane == Pane.LEFT) leftVm else rightVm
                        tabManager.currentTab(pane)?.path?.takeIf { it.isNotBlank() }?.let {
                            vm.openExternal(it)
                        }
                        activePane = pane
                        scope.launch { rightDrawerState.close() }
                    },
                    onNewTab = { pane ->
                        tabManager.newTab(pane)
                        activePane = pane
                        scope.launch { rightDrawerState.close() }
                    },
                    onClose = { scope.launch { rightDrawerState.close() } }
                )
            }
        ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        // 需求 1：保留 "FreeFile" 文本视图 + 活动窗格状态副行
                        Column {
                            Text(
                                stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                stringResource(
                                    R.string.pane_item_count,
                                    if (activePane == Pane.LEFT)
                                        stringResource(R.string.left_pane)
                                    else stringResource(R.string.right_pane),
                                    activeUi.entries.size
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { leftDrawerState.open() } }) {
                            Icon(Icons.Rounded.Menu, contentDescription = stringResource(R.string.func_menu))
                        }
                    },
                    actions = {
                        // 临时隐藏/显示右侧窗格（单窗口模式切换）
                        IconButton(onClick = { showRightPane = !showRightPane }) {
                            Icon(
                                if (showRightPane) Icons.Rounded.LastPage else Icons.Rounded.FirstPage,
                                contentDescription = if (showRightPane)
                                    stringResource(R.string.hide_right_pane)
                                else stringResource(R.string.show_right_pane)
                            )
                        }
                        // 整体交换左右窗格（含全部标签页）
                        IconButton(onClick = {
                            tabManager.swapPanes()
                            tabManager.currentTab(Pane.LEFT)?.path?.let { leftVm.openExternal(it) }
                            tabManager.currentTab(Pane.RIGHT)?.path?.let { rightVm.openExternal(it) }
                            activePane = if (activePane == Pane.LEFT) Pane.RIGHT else Pane.LEFT
                        }) {
                            Icon(Icons.Rounded.SwapHoriz, contentDescription = stringResource(R.string.swap_panes))
                        }
                        // 标签页抽屉
                        IconButton(onClick = { scope.launch { rightDrawerState.open() } }) {
                            Icon(Icons.Rounded.Tab, contentDescription = stringResource(R.string.tabs))
                        }
                    }
                )
            },
            bottomBar = {
                // 设置/脚本/快传已迁移到独立 Activity，主页底部工具栏始终显示
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column {
                        // 任务进度卡片
                        val tasks by TransferManager.tasks.collectAsState()
                        tasks.forEach { task ->
                            TaskProgressCard(
                                task = task,
                                onCancel = { TransferManager.cancel(it) },
                                onDismiss = { TransferManager.dismiss(it) }
                            )
                        }

                        // 搜索展开区（需求 5：深度/正则/完全匹配/索引）
                        if (showSearch) {
                            SearchPanel(vm = activeVm, onClose = { showSearch = false })
                        }
                        // 过滤展开区（需求 6：类型多选）
                        if (showFilter) {
                            FilterBar(vm = activeVm, onClose = { showFilter = false })
                        }

                        // 多选操作行（需求 4：五按钮 - 全选/反选/区间选择/取消/操作）
                        if (activeUi.inMultiSelect) {
                            SelectionBar(
                                count = activeUi.selection.size,
                                single = activeUi.selection.size == 1,
                                hasAnchor = activeVm.hasSelectionAnchor(),
                                onCopy = {
                                    val entries = activeVm.selectedEntries()
                                    if (entries.isNotEmpty()) {
                                        activeVm.copyToClipboard(entries)
                                        toast(context.getString(R.string.msg_copied_to_clip, entries.size))
                                    }
                                },
                                onCut = {
                                    val entries = activeVm.selectedEntries()
                                    if (entries.isNotEmpty()) {
                                        activeVm.cutToClipboard(entries)
                                        toast(context.getString(R.string.msg_cut_to_clip, entries.size))
                                    }
                                },
                                onDelete = {
                                    val entries = activeVm.selectedEntries()
                                    if (entries.isNotEmpty()) pending = PendingAction.Delete(entries, activeVm)
                                },
                                onRename = {
                                    val entries = activeVm.selectedEntries()
                                    if (entries.size == 1) pending = PendingAction.Rename(entries.first(), activeVm)
                                },
                                onBatch = {
                                    val entries = activeVm.selectedEntries()
                                    if (entries.isNotEmpty()) pending = PendingAction.BatchRename(entries, activeVm)
                                },
                                onProperties = {
                                    val entries = activeVm.selectedEntries()
                                    if (entries.size == 1) pending = PendingAction.Properties(entries.first())
                                },
                                onSelectAll = { activeVm.selectAll() },
                                onInvert = { activeVm.invertSelection() },
                                onRangeSelect = { activeVm.enableRangeAnchor() },
                                onClear = { activeVm.clearSelection() },
                                onAction = { showActionSheet = true }
                            )
                        } else {
                            // 统一主操作栏（需求 1：一套，控制权随活动窗格）
                            val clip by FileClipboard.clip.collectAsState()
                            MainActionBar(
                                vm = activeVm,
                                canPaste = clip != null,
                                searchActive = showSearch,
                                filterActive = showFilter,
                                onToggleSearch = {
                                    // 点击搜索打开搜索设置 Dialog
                                    showSearchDialog = true
                                },
                                onToggleFilter = {
                                    showFilter = !showFilter
                                    if (showFilter) showSearch = false
                                },
                                onJump = { showJump = true },
                                onNew = { pending = PendingAction.NewNode(null, activeVm) },
                                onCopy = {
                                    val entries = activeVm.selectedEntries()
                                    if (entries.isNotEmpty()) {
                                        activeVm.copyToClipboard(entries)
                                        toast(context.getString(R.string.msg_copied_to_clip, entries.size))
                                    } else toast(context.getString(R.string.range_select_hint))
                                },
                                onCut = {
                                    val entries = activeVm.selectedEntries()
                                    if (entries.isNotEmpty()) {
                                        activeVm.cutToClipboard(entries)
                                        toast(context.getString(R.string.msg_cut_to_clip, entries.size))
                                    } else toast(context.getString(R.string.range_select_hint))
                                },
                                onPaste = {
                                    if (!activeVm.pasteFromClipboard(onStarted = { })) {
                                        toast(context.getString(R.string.msg_clip_empty))
                                    }
                                },
                                onDelete = {
                                    val entries = activeVm.selectedEntries()
                                    if (entries.isNotEmpty()) {
                                        pending = PendingAction.Delete(entries, activeVm)
                                    } else toast(context.getString(R.string.range_select_hint))
                                },
                                onRename = {
                                    val entries = activeVm.selectedEntries()
                                    if (entries.size == 1) {
                                        pending = PendingAction.Rename(entries.first(), activeVm)
                                    } else toast(context.getString(R.string.range_select_hint))
                                },
                                onRefresh = { activeVm.refresh() },
                                onBookmark = { activeVm.toggleBookmark() },
                                // Item 5：复制当前活动窗格目录路径到剪贴板
                                onCopyPath = {
                                    val path = activeUi.path
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("path", path))
                                    toast(context.getString(R.string.path_copied, path))
                                },
                                onExtract = {
                                    if (ArchiveEngine.isArchivePath(activeUi.path)) {
                                        pending = PendingAction.Extract(activeUi.path)
                                    }
                                },
                                onToast = { toast(it) }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding)) {
                // 存储权限横幅
                if (!permSnapshot.storageGranted) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.storage_permission_banner),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = {
                                storageSettingLauncher.launch(
                                    PermissionManager.allFilesAccessIntent(context)
                                )
                            }) { Text(stringResource(R.string.grant_access)) }
                        }
                    }
                }

                if (showRightPane) {
                        DualPaneLayout(
                            dividerPosition = dividerPosition,
                            onDividerPositionChange = { dividerPosition = it },
                            leftPane = {
                                FilePane(
                                    vm = leftVm,
                                    isActive = activePane == Pane.LEFT,
                                    onActivate = { activePane = Pane.LEFT },
                                    onOpenFile = { entry ->
                                        // 需求：点击文件弹出「打开方式」Activity（分享/系统打开/内置 6 选项）
                                        launchOpenWith(entry)
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            },
                            rightPane = {
                                FilePane(
                                    vm = rightVm,
                                    isActive = activePane == Pane.RIGHT,
                                    onActivate = { activePane = Pane.RIGHT },
                                    onOpenFile = { entry -> launchOpenWith(entry) },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        )
                    } else {
                        // 单窗口模式：只显示左窗格
                        FilePane(
                            vm = leftVm,
                            isActive = true,
                            onActivate = { activePane = Pane.LEFT },
                            onOpenFile = { entry -> launchOpenWith(entry) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // 跳转对话框（需求 2：目录跳转）
            if (showJump) {
                JumpToPathDialog(
                    initialPath = activeUi.path,
                    onConfirm = {
                        activeVm.open(it)
                        showJump = false
                    },
                    onDismiss = { showJump = false }
                )
            }

            // 搜索设置 Dialog（需求：搜索设置放入 Dialog，无限递归/正则/通配符）
            if (showSearchDialog) {
                val indexState by SearchEngine.indexState.collectAsState()
                SearchSettingsDialog(
                    initialQuery = activeUi.searchQuery,
                    initialOptions = activeUi.searchOptions,
                    indexEnabled = activeUi.indexEnabled,
                    indexState = indexState,
                    onBuildIndex = {
                        scope.launch { SearchEngine.buildIndex(FreeFileApp.instance) }
                    },
                    onConfirm = { query, opts ->
                        activeVm.setSearchOptions(opts)
                        activeVm.search(query)
                        showSearchDialog = false
                        showSearch = true
                    },
                    onDismiss = { showSearchDialog = false }
                )
            }

            // Sprint 5：添加网络位置对话框
            if (showAddNetwork) {
                AddNetworkDialog(
                    onDismiss = { showAddNetwork = false },
                    onSave = { loc ->
                        showAddNetwork = false
                        scope.launch {
                            FreeFileApp.instance.repository.addNetworkLocation(loc)
                            toast(context.getString(R.string.msg_network_added))
                        }
                    }
                )
            }
        }
    }

    // ==================== 对话框 ====================

    pending?.let { action ->
        when (action) {
            is PendingAction.Delete -> ConfirmDeleteDialog(
                entries = action.entries,
                onTrash = {
                    action.vm.deleteToTrash(action.entries)
                    pending = null
                },
                onDelete = {
                    action.vm.deletePermanently(action.entries)
                    pending = null
                },
                onDismiss = { pending = null }
            )
            is PendingAction.Properties -> PropertiesDialog(
                entry = action.entry,
                onDismiss = { pending = null }
            )
            is PendingAction.Rename -> TextInputDialog(
                title = stringResource(R.string.dlg_rename_title),
                label = stringResource(R.string.dlg_rename_label),
                initial = action.entry.name,
                onConfirm = { name ->
                    action.vm.rename(action.entry, name)
                    action.vm.clearSelection()
                    pending = null
                },
                onDismiss = { pending = null }
            )
            is PendingAction.NewNode -> {
                val isDir = action.isDir
                if (isDir == null) {
                    // 未指定类型 → 弹出新建选择对话框（文件/文件夹）
                    NewNodeDialog(
                        onConfirm = { dir, name ->
                            if (dir) action.vm.createFolder(name) else action.vm.createFile(name)
                            pending = null
                        },
                        onDismiss = { pending = null }
                    )
                } else {
                    TextInputDialog(
                        title = if (isDir) stringResource(R.string.dlg_new_folder_title)
                        else stringResource(R.string.dlg_new_file_title),
                        label = if (isDir) stringResource(R.string.dlg_new_folder_label)
                        else stringResource(R.string.dlg_new_file_label),
                        onConfirm = { name ->
                            if (isDir) action.vm.createFolder(name) else action.vm.createFile(name)
                            pending = null
                        },
                        onDismiss = { pending = null }
                    )
                }
            }
            is PendingAction.BatchRename -> BatchRenameDialog(
                entries = action.entries,
                onApply = { pairs ->
                    scope.launch {
                        var ok = 0
                        for ((entry, newName) in pairs) {
                            try {
                                val engine = EngineSelector.select(entry.path)
                                val newPath = entry.path.substringBeforeLast('/') + "/" + newName
                                if (!engine.exists(newPath)) {
                                    engine.rename(entry.path, newPath)
                                    ok++
                                }
                            } catch (_: Exception) {
                            }
                        }
                        toast(context.getString(R.string.msg_batch_renamed, ok))
                        action.vm.refresh()
                        action.vm.clearSelection()
                    }
                    pending = null
                },
                onDismiss = { pending = null }
            )
            is PendingAction.Conflicts -> ConflictDialog(
                kindLabel = if (action.kind == OpKind.COPY) copyLabel else moveLabel,
                conflicts = action.conflicts,
                onResolve = { resolution ->
                    doTransfer(action.kind, action.entries, action.targetDir, action.conflicts, resolution)
                    pending = null
                },
                onDismiss = { pending = null }
            )
            is PendingAction.Extract -> ConfirmExtractDialog(
                archiveName = action.archivePath.substringAfterLast('/'),
                defaultTargetDir = defaultExtractDir(action.archivePath),
                onConfirm = { targetDir ->
                    TransferManager.enqueueExtract(action.archivePath, targetDir) { ok, msg ->
                        scope.launch {
                            if (ok) {
                                toast(context.getString(R.string.msg_extract_done))
                                // 解压目标位于本地文件系统 → 刷新双窗格（若在其中）
                                leftVm.refresh()
                                rightVm.refresh()
                            } else toast(msg)
                        }
                    }
                    pending = null
                },
                onDismiss = { pending = null }
            )
        }
    }

    // 多选操作面板（需求 4：操作按钮触发；任务 7：复用主页底部文件操作逻辑）
    if (showActionSheet) {
        val selected = activeVm.selectedEntries()
        val singleSel = selected.size == 1
        AlertDialog(
            onDismissRequest = { showActionSheet = false },
            title = {
                Text(
                    stringResource(R.string.action_sheet_title, selected.size),
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column {
                    // —— 打开方式三选项（需求 4 之"操作"）——
                    OpenSheetItem(
                        icon = Icons.Rounded.Share,
                        title = stringResource(R.string.open_with_share),
                        onClick = {
                            showActionSheet = false
                            scope.launch {
                                selected.forEach { FileOpener.shareFile(context, it, ::toast) }
                                activeVm.recordRecentFile(selected.firstOrNull()?.path ?: "")
                            }
                        }
                    )
                    OpenSheetItem(
                        icon = Icons.Rounded.Apps,
                        title = stringResource(R.string.open_with_mime),
                        onClick = {
                            showActionSheet = false
                            scope.launch {
                                selected.forEach { FileOpener.openWithMime(context, it, ::toast) }
                            }
                        }
                    )
                    // 内置模块打开：统一进入「打开方式」Activity（分享/系统打开/文本/工具托盘/终端/解压）
                    OpenSheetItem(
                        icon = Icons.Rounded.OpenInNew,
                        title = stringResource(R.string.open_with_builtin),
                        onClick = {
                            showActionSheet = false
                            val target = selected.firstOrNull() ?: return@OpenSheetItem
                            scope.launch { activeVm.recordRecentFile(target.path) }
                            launchOpenWith(target)
                        },
                        enabled = singleSel
                    )
                    // —— 任务 6 / 5 入口：终端 / 工具托盘 ——
                    OpenSheetItem(
                        icon = Icons.Rounded.Terminal,
                        title = stringResource(R.string.terminal_open_with_path),
                        onClick = {
                            showActionSheet = false
                            launchTerminal(selected.map { it.path })
                        },
                        enabled = singleSel
                    )
                    OpenSheetItem(
                        icon = Icons.Rounded.Apps,
                        title = stringResource(R.string.tool_tray_open_with_file),
                        onClick = {
                            showActionSheet = false
                            launchToolTray(selected.firstOrNull()?.path)
                        },
                        enabled = singleSel
                    )
                    Divider(modifier = Modifier.padding(vertical = 6.dp))
                    // —— 文件操作（任务 7：复用主页底部按钮逻辑）——
                    OpenSheetItem(
                        icon = Icons.Rounded.ContentCopy,
                        title = stringResource(R.string.copy),
                        onClick = {
                            showActionSheet = false
                            activeVm.copyToClipboard(selected)
                            toast(context.getString(R.string.msg_copied_to_clip, selected.size))
                        }
                    )
                    OpenSheetItem(
                        icon = Icons.Rounded.ContentCut,
                        title = stringResource(R.string.cut),
                        onClick = {
                            showActionSheet = false
                            activeVm.cutToClipboard(selected)
                            toast(context.getString(R.string.msg_cut_to_clip, selected.size))
                        }
                    )
                    OpenSheetItem(
                        icon = Icons.Rounded.Delete,
                        title = stringResource(R.string.delete),
                        onClick = {
                            showActionSheet = false
                            pending = PendingAction.Delete(selected, activeVm)
                        }
                    )
                    OpenSheetItem(
                        icon = Icons.Rounded.DriveFileRenameOutline,
                        title = stringResource(R.string.rename),
                        onClick = {
                            showActionSheet = false
                            if (singleSel) pending = PendingAction.Rename(selected.first(), activeVm)
                            else toast(context.getString(R.string.range_select_hint))
                        },
                        enabled = singleSel
                    )
                    OpenSheetItem(
                        icon = Icons.Rounded.TextFields,
                        title = stringResource(R.string.sel_batch),
                        onClick = {
                            showActionSheet = false
                            pending = PendingAction.BatchRename(selected, activeVm)
                        },
                        enabled = selected.isNotEmpty()
                    )
                    OpenSheetItem(
                        icon = Icons.Rounded.Info,
                        title = stringResource(R.string.sel_props),
                        onClick = {
                            showActionSheet = false
                            if (singleSel) pending = PendingAction.Properties(selected.first())
                            else toast(context.getString(R.string.range_select_hint))
                        },
                        enabled = singleSel
                    )
                    // Item 5：复制选中文件/文件夹路径
                    OpenSheetItem(
                        icon = Icons.Rounded.ContentPaste,
                        title = stringResource(R.string.copy_path),
                        onClick = {
                            showActionSheet = false
                            val paths = selected.joinToString("\n") { it.path }
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("paths", paths))
                            toast(
                                if (selected.size == 1) context.getString(R.string.path_copied, selected.first().path)
                                else context.getString(R.string.paths_copied, selected.size)
                            )
                        },
                        enabled = selected.isNotEmpty()
                    )
                    // 任务 7：解压（复用 PendingAction.Extract 共享逻辑）
                    // 仅当选中单个压缩包时启用；点击后由 ConfirmExtractDialog 处理。
                    OpenSheetItem(
                        icon = Icons.Rounded.Archive,
                        title = stringResource(R.string.open_with_extract),
                        onClick = {
                            showActionSheet = false
                            val target = selected.firstOrNull() ?: return@OpenSheetItem
                            pending = PendingAction.Extract(target.path)
                        },
                        enabled = singleSel && ArchiveEngine.isArchiveFile(selected.first().name)
                    )
                    // TODO 任务 7：压缩（to zip）尚未实现，需新增 ArchiveEngine.compress(entries, targetPath)
                    // 当前先以 disabled 占位条目展示，避免用户找不到入口；后续补齐。
                    OpenSheetItem(
                        icon = Icons.Rounded.FolderZip,
                        title = stringResource(R.string.action_compress),
                        onClick = {
                            // 占位：待 ArchiveEngine 压缩能力落地后启用
                            showActionSheet = false
                            toast(context.getString(R.string.action_compress_todo))
                        },
                        enabled = false
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showActionSheet = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/** 打开方式菜单项。 */
@Composable
private fun OpenSheetItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, enabled = enabled)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 简化：把 TabManager 的当前路径转成 Flow（快照轮询）。 */
private fun snapshotFlowOf(pane: Pane, tabManager: TabManager) =
    kotlinx.coroutines.flow.flow {
        var last: String? = null
        while (true) {
            val p = tabManager.currentTab(pane)?.path
            if (p != last) {
                last = p
                emit(p)
            }
            kotlinx.coroutines.delay(200)
        }
    }

/** 计算压缩包的默认解压目标：同级目录下去掉扩展名的文件夹。 */
private fun defaultExtractDir(archivePath: String): String {
    val name = archivePath.substringAfterLast('/')
    val lower = name.lowercase()
    val base = when {
        lower.endsWith(".tar.gz") -> name.dropLast(7)
        lower.endsWith(".tar.bz2") -> name.dropLast(8)
        lower.endsWith(".tar.xz") -> name.dropLast(7)
        else -> name.substringBeforeLast('.')
    }.ifEmpty { name }
    return com.freefile.vfs.joinPath(
        archivePath.substringBeforeLast('/').ifEmpty { "/" }, base
    )
}


// ==================== 侧边栏 ====================

/**
 * 侧边栏：访问模式切换 / 存储位置 / 书签 / 历史 / 最近 / 回收站。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrawerContent(
    permSnapshot: PermissionManager.Snapshot,
    onSetMode: (PermissionMode) -> Unit,
    onNavigate: (String) -> Unit,
    onOpenInOther: (String) -> Unit,
    onLocateFile: (String) -> Unit,
    onMessage: (String) -> Unit,
    onTrashChanged: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onOpenScript: () -> Unit = {},
    onOpenShare: () -> Unit = {},
    onOpenToolTray: () -> Unit = {},
    onAddNetwork: () -> Unit = {},
    onClose: () -> Unit = {}
) {
    val repo = FreeFileApp.instance.repository
    val scope = rememberCoroutineScope()
    val drawerContext = LocalContext.current
    val bookmarks by repo.bookmarks.collectAsState(initial = emptyList())
    val history by repo.history.collectAsState(initial = emptyList())
    val recents by repo.recents.collectAsState(initial = emptyList())
    val trash by repo.trash.collectAsState(initial = emptyList())
    val networkLocations by repo.networkLocations.collectAsState(initial = emptyList())

    var trashExpanded by remember { mutableStateOf(false) }
    var showClearTrash by remember { mutableStateOf(false) }

    // 存储位置入口（在组合期构建，stringResource 需 Composable 上下文）
    val storageEntries = listOf(
        Triple(Icons.Rounded.Smartphone, stringResource(R.string.storage_internal), "/storage/emulated/0"),
        Triple(Icons.Rounded.Folder, stringResource(R.string.storage_download), "/storage/emulated/0/Download"),
        Triple(Icons.Rounded.Folder, stringResource(R.string.storage_camera), "/storage/emulated/0/DCIM"),
        Triple(Icons.Rounded.Folder, stringResource(R.string.storage_documents), "/storage/emulated/0/Documents"),
        Triple(Icons.Rounded.Storage, stringResource(R.string.storage_root_dir), "/")
    )

    ModalDrawerSheet {
        // 顶部标题栏：FreeFile + 关闭按钮（用户反馈：有时滑动关闭抽屉有 bug，显式按钮更可靠）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "FreeFile",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.close)
                )
            }
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {

            // ---------- 设置入口（需求 3） ----------
            item {
                DrawerEntryRow(
                    icon = Icons.Rounded.Settings,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.settings_title),
                    subtitle = stringResource(R.string.settings_applied_immediately),
                    onClick = onOpenSettings
                )
            }

            // ---------- 访问模式 ----------
            item { DrawerSectionTitle(stringResource(R.string.drawer_access_mode)) }
            item {
                Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ModeChip(stringResource(R.string.mode_auto), permSnapshot.mode == PermissionMode.AUTO) { onSetMode(PermissionMode.AUTO) }
                        ModeChip(stringResource(R.string.mode_root), permSnapshot.mode == PermissionMode.ROOT) { onSetMode(PermissionMode.ROOT) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ModeChip(stringResource(R.string.mode_shizuku), permSnapshot.mode == PermissionMode.SHIZUKU) { onSetMode(PermissionMode.SHIZUKU) }
                        ModeChip(stringResource(R.string.mode_local), permSnapshot.mode == PermissionMode.LOCAL_ONLY) { onSetMode(PermissionMode.LOCAL_ONLY) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.settings_effective_engine, permSnapshot.effectiveEngine) +
                            " · Root" + stringResource(
                                if (permSnapshot.rootAvailable) R.string.available else R.string.unavailable
                            ) +
                            " · Shizuku" + stringResource(
                                if (permSnapshot.shizukuAvailable) R.string.available else R.string.unavailable
                            ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---------- 存储位置 ----------
            item { DrawerSectionTitle(stringResource(R.string.drawer_storage)) }
            items(storageEntries) { (icon, label, path) ->
                DrawerNavRow(icon, label, path, onNavigate, onOpenInOther)
            }

            // ---------- 工具（Sprint 5：终端/脚本/快传） ----------
            item { DrawerSectionTitle(stringResource(R.string.drawer_tools)) }
            item {
                DrawerEntryRow(
                    icon = Icons.Rounded.Terminal,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.tool_terminal),
                    subtitle = stringResource(R.string.tool_terminal_sub),
                    onClick = onOpenTerminal
                )
            }
            item {
                DrawerEntryRow(
                    icon = Icons.Rounded.Code,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.tool_script),
                    subtitle = stringResource(R.string.tool_script_sub),
                    onClick = onOpenScript
                )
            }
            item {
                DrawerEntryRow(
                    icon = Icons.Rounded.SendToMobile,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.tool_share),
                    subtitle = stringResource(R.string.tool_share_sub),
                    onClick = onOpenShare
                )
            }
            item {
                DrawerEntryRow(
                    icon = Icons.Rounded.Apps,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.tool_tray),
                    subtitle = stringResource(R.string.tool_tray_sub),
                    onClick = onOpenToolTray
                )
            }

            // ---------- 网络位置（Sprint 5：WebDAV/FTP/SMB/S3） ----------
            item { DrawerSectionTitle(stringResource(R.string.drawer_network)) }
            items(networkLocations, key = { "net${it.id}" }) { loc ->
                DrawerEntryRow(
                    icon = Icons.Rounded.Cloud,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = loc.name,
                    subtitle = "${loc.type.lowercase()}://${loc.host}:${loc.port}" +
                        if (loc.basePath.isNotBlank()) loc.basePath else "",
                    onClick = { onNavigate(RemoteEngine.SCHEME + loc.id + "/") },
                    onOpenInOther = { onOpenInOther(RemoteEngine.SCHEME + loc.id + "/") },
                    onDelete = {
                        scope.launch {
                            repo.removeNetworkLocation(loc.id)
                            onMessage(drawerContext.getString(R.string.msg_network_deleted))
                        }
                    }
                )
            }
            item {
                DrawerEntryRow(
                    icon = Icons.Rounded.Add,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    title = stringResource(R.string.net_add),
                    subtitle = "WebDAV · FTP · SMB · S3/OSS",
                    onClick = onAddNetwork
                )
            }

            // ---------- 书签 ----------
            if (bookmarks.isNotEmpty()) {
                item { DrawerSectionTitle(stringResource(R.string.drawer_bookmarks)) }
                items(bookmarks, key = { "bm${it.id}" }) { bm ->
                    DrawerEntryRow(
                        icon = Icons.Rounded.Bookmark,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = bm.name,
                        subtitle = bm.path,
                        onClick = { onNavigate(bm.path) },
                        onOpenInOther = { onOpenInOther(bm.path) },
                        onDelete = {
                            scope.launch {
                                repo.removeBookmark(bm.path)
                                onMessage(drawerContext.getString(R.string.msg_bookmark_removed))
                            }
                        }
                    )
                }
            }

            // ---------- 历史 ----------
            if (history.isNotEmpty()) {
                item { DrawerSectionTitle(stringResource(R.string.drawer_history)) }
                items(history.take(8), key = { "h${it.id}" }) { h ->
                    DrawerEntryRow(
                        icon = Icons.Rounded.History,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        title = h.path.trimEnd('/').substringAfterLast('/').ifEmpty { h.path },
                        subtitle = Format.time(h.timestamp),
                        onClick = { onNavigate(h.path) },
                        onOpenInOther = { onOpenInOther(h.path) }
                    )
                }
            }

            // ---------- 最近文件 ----------
            if (recents.isNotEmpty()) {
                item { DrawerSectionTitle(stringResource(R.string.drawer_recent_files)) }
                items(recents.take(8), key = { "r${it.id}" }) { r ->
                    DrawerEntryRow(
                        icon = Icons.Rounded.Schedule,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        title = r.path.substringAfterLast('/'),
                        subtitle = Format.time(r.accessedAt),
                        // 需求 6：点击最近文件 → 直接跳转并定位到所在目录（高亮该文件）
                        onClick = { onLocateFile(r.path) },
                        onOpenInOther = { onOpenInOther(r.path.substringBeforeLast('/')) }
                    )
                }
            }

            // ---------- 回收站 ----------
            item { DrawerSectionTitle(stringResource(R.string.drawer_trash)) }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { trashExpanded = !trashExpanded }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.trash_count, trash.size),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (trash.isNotEmpty()) {
                        TextButton(onClick = { showClearTrash = true }) {
                            Text(stringResource(R.string.trash_clear_all), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Icon(
                        if (trashExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (trashExpanded) stringResource(R.string.collapse)
                        else stringResource(R.string.expand),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            if (trashExpanded) {
                items(trash, key = { "t${it.id}" }) { item ->
                    TrashRow(
                        item = item,
                        onRestore = {
                            scope.launch {
                                val ok = repo.restore(item)
                                onMessage(
                                    if (ok) drawerContext.getString(R.string.msg_restored, item.fileName)
                                    else drawerContext.getString(R.string.msg_restore_failed)
                                )
                                if (ok) onTrashChanged()
                            }
                        },
                        onPurge = {
                            scope.launch {
                                val ok = repo.purge(item)
                                onMessage(
                                    if (ok) drawerContext.getString(R.string.msg_purged, item.fileName)
                                    else drawerContext.getString(R.string.msg_purge_failed)
                                )
                                if (ok) onTrashChanged()
                            }
                        }
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // 清空回收站确认
    if (showClearTrash) {
        AlertDialog(
            onDismissRequest = { showClearTrash = false },
            title = { Text(stringResource(R.string.trash_empty_title)) },
            text = { Text(stringResource(R.string.trash_empty_msg, trash.size)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearTrash = false
                    scope.launch {
                        val ok = repo.clearTrash()
                        onMessage(
                            if (ok) drawerContext.getString(R.string.msg_trash_cleared)
                            else drawerContext.getString(R.string.msg_trash_clear_failed)
                        )
                        if (ok) onTrashChanged()
                    }
                }) { Text(stringResource(R.string.trash_clear_all), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearTrash = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun DrawerSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
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

/** 存储位置行：图标 + 名称，支持在另一窗格打开。 */
@Composable
private fun DrawerNavRow(
    icon: ImageVector,
    label: String,
    path: String,
    onNavigate: (String) -> Unit,
    onOpenInOther: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigate(path) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        IconButton(
            onClick = { onOpenInOther(path) },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                Icons.Rounded.CompareArrows,
                contentDescription = stringResource(R.string.open_in_other_pane),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 通用条目行：书签/历史/最近共用。 */
@Composable
private fun DrawerEntryRow(
    icon: ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onOpenInOther: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        onOpenInOther?.let {
            IconButton(onClick = it, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Rounded.CompareArrows,
                    contentDescription = stringResource(R.string.open_in_other_pane),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        onDelete?.let {
            IconButton(onClick = it, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.delete),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 回收站条目行：恢复 / 永久删除。 */
@Composable
private fun TrashRow(
    item: TrashItem,
    onRestore: () -> Unit,
    onPurge: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (item.isDirectory) Icons.Rounded.Folder else Icons.Rounded.DeleteOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.fileName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                stringResource(
                    R.string.trash_row_subtitle,
                    item.originalPath,
                    Format.size(item.fileSize),
                    Format.time(item.deletedAt)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onRestore, modifier = Modifier.size(30.dp)) {
            Icon(
                Icons.Rounded.RestoreFromTrash,
                contentDescription = stringResource(R.string.trash_restore),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        IconButton(onClick = onPurge, modifier = Modifier.size(30.dp)) {
            Icon(
                Icons.Rounded.DeleteOutline,
                contentDescription = stringResource(R.string.trash_delete_forever),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

// ==================== 多选操作栏 ====================

/**
 * 多选操作栏（需求 4）：五按钮 - 全选 / 反选 / 区间选择 / 取消 / 操作。
 * - 全选：选中当前列表所有可见项（不含已隐藏的）。
 * - 反选：切换所有项的选中状态。
 * - 区间选择：以当前选中项为锚点，下次点击列表项时区间选中。
 * - 取消：清空选中并退出选择模式。
 * - 操作：弹出底部面板（分享 / 系统打开 / 内置模块 / 文件操作）。
 */
@Composable
private fun SelectionBar(
    count: Int,
    single: Boolean,
    hasAnchor: Boolean,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onBatch: () -> Unit,
    onProperties: () -> Unit,
    onSelectAll: () -> Unit,
    onInvert: () -> Unit,
    onRangeSelect: () -> Unit,
    onClear: () -> Unit,
    onAction: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 标题行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.selected_count, count),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (hasAnchor) {
                    Text(
                        stringResource(R.string.range_select_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
            }
            // 五按钮行（横向滚动以适配窄屏）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SelectionAction(
                    Icons.Rounded.SelectAll,
                    stringResource(R.string.select_all),
                    onSelectAll
                )
                SelectionAction(
                    Icons.Rounded.CompareArrows,
                    stringResource(R.string.invert_selection),
                    onInvert
                )
                SelectionAction(
                    Icons.Rounded.CenterFocusWeak,
                    stringResource(R.string.range_select),
                    onRangeSelect,
                    tint = if (hasAnchor) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                SelectionAction(
                    Icons.Rounded.Close,
                    stringResource(R.string.cancel),
                    onClear
                )
                SelectionAction(
                    Icons.Rounded.MoreVert,
                    stringResource(R.string.action),
                    onAction,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun SelectionAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
) {
    Column(
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(22.dp),
            tint = tint
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = tint
        )
    }
}

// ==================== 统一主操作栏（需求 1：一套，随活动窗格） ====================

/** 统一主操作栏：导航行 + 操作行（横向滚动），控制权自动作用于活动窗格。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainActionBar(
    vm: FileViewModel,
    canPaste: Boolean,
    searchActive: Boolean,
    filterActive: Boolean,
    onToggleSearch: () -> Unit,
    onToggleFilter: () -> Unit,
    onJump: () -> Unit,
    onNew: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onRefresh: () -> Unit,
    onBookmark: () -> Unit,
    onCopyPath: () -> Unit,
    onExtract: () -> Unit,
    onToast: (String) -> Unit
) {
    val ui by vm.ui.collectAsState()
    val scope = rememberCoroutineScope()
    var showSortMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    // 六点菜单的过滤子菜单（MoreVert → 过滤 → 模式子菜单）
    var showFilterMenu by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            // 行1：导航（后退/前进/面包屑/上级/跳转）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { vm.back() }, enabled = ui.canBack) {
                    Icon(
                        Icons.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.close),
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = { vm.forward() }, enabled = ui.canForward) {
                    Icon(
                        Icons.Rounded.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Breadcrumb(
                    path = ui.path,
                    modifier = Modifier.weight(1f),
                    onNavigate = { vm.open(it) }
                )
                IconButton(onClick = { vm.openParent() }) {
                    Icon(
                        Icons.Rounded.ArrowUpward,
                        contentDescription = stringResource(R.string.go_up),
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onJump) {
                    Icon(
                        Icons.Rounded.CenterFocusWeak,
                        contentDescription = stringResource(R.string.jump_to_path),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Divider(modifier = Modifier.padding(horizontal = 12.dp))

            // 行2：操作（横向滚动，居中排布）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 新建：点击后弹出对话框选择文件/文件夹并输入名称
                ActionIcon(Icons.Rounded.CreateNewFolder, stringResource(R.string.new_node), onNew)
                // Item 4：书签当前文件夹（一键切换）
                ActionIcon(
                    if (ui.isBookmarked) Icons.Rounded.Bookmark else Icons.Outlined.BookmarkBorder,
                    stringResource(R.string.add_bookmark),
                    onBookmark,
                    tint = if (ui.isBookmarked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                // Item 5：复制当前目录路径
                ActionIcon(Icons.Rounded.ContentPaste, stringResource(R.string.copy_path), onCopyPath)
                // 问题 4：底部操作栏六点菜单 (MoreVert) 暂时隐藏
                //   根因：原本子菜单 DropdownMenu 的 expanded 直接复用 showMoreMenu，
                //   导致打开瞬间自动展开"显示方式"子菜单（视觉"第一项自动展开"），
                //   且 anchor 在横向滚动的 Row 内会触发位置跳跃（定位乱跑）。
                //   等后续改用独立 SubMenu 状态（而非嵌套 DropdownMenu 共享 expanded）
                //   再恢复显示。快捷入口（⛶/↕/⛶过滤/⊞视图）保留正常使用。
                // TODO 问题 3：重写 MoreVert 使用独立 showViewModeMenu / showSortMenu 状态。
                // 过滤子菜单（SortMenu 在下方 Box 中声明）
                DropdownMenu(
                    expanded = showFilterMenu,
                    onDismissRequest = { showFilterMenu = false }
                ) {
                    FilterMode.values().forEach { mode ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (ui.filterMode == mode) {
                                        Icon(
                                            Icons.Rounded.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(stringResource(mode.labelRes))
                                }
                            },
                            onClick = {
                                showFilterMenu = false
                                vm.setFilterMode(mode)
                            }
                        )
                    }
                }
                ActionIcon(Icons.Rounded.ContentCopy, stringResource(R.string.copy), onCopy)
                ActionIcon(Icons.Rounded.ContentCut, stringResource(R.string.cut), onCut)
                ActionIcon(
                    Icons.Rounded.ContentPaste, stringResource(R.string.paste), onPaste,
                    tint = if (canPaste) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                ActionIcon(Icons.Rounded.Delete, stringResource(R.string.delete), onDelete)
                ActionIcon(
                    Icons.Rounded.DriveFileRenameOutline, stringResource(R.string.rename), onRename
                )
                // 搜索（点击展开搜索设置 Dialog）
                ActionIcon(
                    Icons.Rounded.Search, stringResource(R.string.search), onToggleSearch,
                    tint = if (searchActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 过滤（快捷按钮，独立入口；MoreVert 内也有过滤子菜单）
                ActionIcon(
                    Icons.Rounded.FilterList, stringResource(R.string.filter), onToggleFilter,
                    tint = if (filterActive || ui.typeFilters.isNotEmpty()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 排序（快捷按钮，独立入口；MoreVert 内也有排序入口）
                Box {
                    ActionIcon(Icons.Rounded.Sort, stringResource(R.string.sort), onClick = { showSortMenu = true })
                    SortMenu(
                        expanded = showSortMenu,
                        ui = ui,
                        onDismiss = { showSortMenu = false },
                        onSort = { vm.setSortBy(it) }
                    )
                }
                // 显示方式快捷切换（MoreVert 内也有完整列表）
                ActionIcon(
                    Icons.Rounded.GridView,
                    stringResource(R.string.view_mode),
                    onClick = { vm.toggleViewMode() }
                )
                ActionIcon(Icons.Rounded.Refresh, stringResource(R.string.refresh), onRefresh)
            }
        }
    }
}

/** 底栏操作图标（含文字标签，便于发现功能）。 */
@Composable
private fun ActionIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1
        )
    }
}

/** 排序菜单：点击已选中的项切换升/降序（由 VM.setSortBy 处理）。 */
@Composable
private fun SortMenu(
    expanded: Boolean,
    ui: FileViewModel.UiState,
    onDismiss: () -> Unit,
    onSort: (com.freefile.vfs.SortBy) -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        listOf(
            com.freefile.vfs.SortBy.NAME to R.string.sort_by_name,
            com.freefile.vfs.SortBy.SIZE to R.string.sort_by_size,
            com.freefile.vfs.SortBy.TYPE to R.string.sort_by_type,
            com.freefile.vfs.SortBy.MODIFIED to R.string.sort_by_time
        ).forEach { (key, labelRes) ->
            DropdownMenuItem(
                text = {
                    val arrow = when {
                        ui.sortBy != key -> ""
                        ui.sortAsc -> " ↑"
                        else -> " ↓"
                    }
                    Text(stringResource(labelRes) + arrow)
                },
                onClick = {
                    onDismiss()
                    onSort(key)
                }
            )
        }
    }
}

// ==================== 搜索面板（需求 5） ====================

/** 搜索展开面板：关键词 + 深度/正则/完全匹配 + 索引状态。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchPanel(vm: FileViewModel, onClose: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val scope = rememberCoroutineScope()
    val indexState by SearchEngine.indexState.collectAsState()

    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = ui.searchQuery,
                    onValueChange = { vm.search(it) },
                    placeholder = {
                        Text(stringResource(R.string.search_placeholder), style = MaterialTheme.typography.bodySmall)
                    },
                    trailingIcon = {
                        Row {
                            if (ui.searchQuery.isNotBlank()) {
                                IconButton(onClick = { vm.search("") }) {
                                    Icon(Icons.Rounded.Close, stringResource(R.string.search_clear), Modifier.size(18.dp))
                                }
                            }
                            IconButton(onClick = onClose) {
                                Icon(Icons.Rounded.Close, stringResource(R.string.close), Modifier.size(18.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(6.dp))
            // 选项行已迁移到 SearchSettingsDialog（点击搜索按钮触发）
            // 索引状态行
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when {
                        ui.indexEnabled && indexState is IndexState.Ready ->
                            stringResource(R.string.search_index_hint)
                        ui.indexEnabled && indexState is IndexState.Building ->
                            stringResource(R.string.search_index_building)
                        else -> stringResource(R.string.search_live_hint)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (ui.indexEnabled && indexState !is IndexState.Building) {
                    TextButton(onClick = {
                        scope.launch { SearchEngine.buildIndex(FreeFileApp.instance) }
                    }) {
                        Text(
                            stringResource(R.string.search_index_build),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

// ==================== 过滤栏（需求 6） ====================

/** 过滤展开栏：基础模式 + 类型多选 chips。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(vm: FileViewModel, onClose: () -> Unit) {
    val ui by vm.ui.collectAsState()

    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.filter),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (ui.typeFilters.isNotEmpty() || ui.filterMode != FilterMode.ALL) {
                    TextButton(onClick = { vm.setFilterMode(FilterMode.ALL); vm.clearTypeFilters() }) {
                        Text(stringResource(R.string.clear_selection), style = MaterialTheme.typography.labelMedium)
                    }
                }
                IconButton(onClick = onClose, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.close), Modifier.size(18.dp))
                }
            }
            // 基础模式
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterMode.values().forEach { mode ->
                    FilterChip(
                        selected = ui.filterMode == mode,
                        onClick = { vm.setFilterMode(mode) },
                        label = { Text(stringResource(mode.labelRes)) }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            // 类型多选
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TypeChip(FileTypes.IMAGE, R.string.filter_images, ui.typeFilters) { vm.toggleTypeFilter(FileTypes.IMAGE) }
                TypeChip(FileTypes.VIDEO, R.string.filter_videos, ui.typeFilters) { vm.toggleTypeFilter(FileTypes.VIDEO) }
                TypeChip(FileTypes.AUDIO, R.string.filter_audio, ui.typeFilters) { vm.toggleTypeFilter(FileTypes.AUDIO) }
                TypeChip(FileTypes.ARCHIVE, R.string.filter_archives, ui.typeFilters) { vm.toggleTypeFilter(FileTypes.ARCHIVE) }
                TypeChip(FileTypes.DOC, R.string.filter_docs, ui.typeFilters) { vm.toggleTypeFilter(FileTypes.DOC) }
                TypeChip(FileTypes.CODE, R.string.filter_code, ui.typeFilters) { vm.toggleTypeFilter(FileTypes.CODE) }
                TypeChip(FileTypes.APK, R.string.filter_apk, ui.typeFilters) { vm.toggleTypeFilter(FileTypes.APK) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeChip(
    type: String,
    labelRes: Int,
    selected: Set<String>,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected.contains(type),
        onClick = onClick,
        label = { Text(stringResource(labelRes)) }
    )
}
