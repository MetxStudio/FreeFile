package com.freefile.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freefile.FreeFileApp
import com.freefile.R
import com.freefile.data.repo.Repository
import com.freefile.data.settings.AppSettings
import com.freefile.operations.FileChangeBus
import com.freefile.operations.OpKind
import com.freefile.operations.TransferManager
import com.freefile.search.IndexState
import com.freefile.search.SearchEngine
import com.freefile.search.SearchOptions
import com.freefile.vfs.EngineSelector
import com.freefile.vfs.SortBy
import com.freefile.vfs.VfsEntry
import com.freefile.vfs.sortedForDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 视图模式：列表 / 网格 / 大图标。 */
enum class ViewMode(val labelRes: Int) {
    LIST(R.string.view_mode_list),
    GRID(R.string.view_mode_grid),
    LARGE_ICON(R.string.view_mode_large_icon)
}

/** 基础过滤模式。 */
enum class FilterMode(val labelRes: Int) {
    ALL(R.string.filter_all),
    FOLDERS_ONLY(R.string.filter_folders),
    FILES_ONLY(R.string.filter_files)
}

/** 文件类型分类（与列表图标分类一致）。 */
object FileTypes {
    const val IMAGE = "image"
    const val VIDEO = "video"
    const val AUDIO = "audio"
    const val ARCHIVE = "archive"
    const val DOC = "doc"
    const val CODE = "code"
    const val APK = "apk"
    const val OTHER = "other"

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg")
    private val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "flv", "wmv")
    private val AUDIO_EXT = setOf("mp3", "flac", "wav", "ogg", "m4a", "aac")
    private val ARCHIVE_EXT = setOf("zip", "rar", "7z", "tar", "gz", "xz", "bz2", "apk")
    private val DOC_EXT = setOf("txt", "md", "log", "pdf", "doc", "docx")
    private val CODE_EXT = setOf("java", "kt", "smali", "xml", "json", "py", "lua", "c", "cpp", "h")

    fun categoryOf(entry: VfsEntry): String = when {
        entry.isDirectory -> OTHER
        entry.extension == "apk" -> APK
        entry.extension in IMAGE_EXT -> IMAGE
        entry.extension in VIDEO_EXT -> VIDEO
        entry.extension in AUDIO_EXT -> AUDIO
        entry.extension in ARCHIVE_EXT -> ARCHIVE
        entry.extension in DOC_EXT -> DOC
        entry.extension in CODE_EXT -> CODE
        else -> OTHER
    }
}

/**
 * 全局文件剪贴板：复制/剪切暂存，粘贴到目标目录。
 */
object FileClipboard {
    data class Clip(val kind: OpKind, val entries: List<VfsEntry>)

    private val _clip = MutableStateFlow<Clip?>(null)
    val clip: StateFlow<Clip?> = _clip.asStateFlow()

    fun copy(entries: List<VfsEntry>) { _clip.value = Clip(OpKind.COPY, entries) }
    fun cut(entries: List<VfsEntry>) { _clip.value = Clip(OpKind.MOVE, entries) }
    fun clear() { _clip.value = null }
    fun takeCut(): Clip? = _clip.value?.also { if (it.kind == OpKind.MOVE) _clip.value = null }
}

/**
 * 文件窗格 ViewModel（每窗格一例）。
 * 集成：后台 IO 加载 / 排序过滤后台重算 / 深度搜索（正则/完全匹配/索引缓存）/
 * 类型过滤 / 多选范围选择 / 前进后退 / 剪贴板粘贴 / 设置即时生效。
 */
class FileViewModel(
    val paneId: Int,
    private val repo: Repository
) : ViewModel() {

    data class UiState(
        val path: String = "",
        /** 已过滤 + 排序后的显示列表（后台预计算，避免主线程卡顿）。 */
        val entries: List<VfsEntry> = emptyList(),
        /** 当前目录原始条目数。 */
        val totalCount: Int = 0,
        val loading: Boolean = false,
        val error: String? = null,
        val sortBy: SortBy = SortBy.NAME,
        val sortAsc: Boolean = true,
        val viewMode: ViewMode = ViewMode.LIST,
        val selection: Set<String> = emptySet(),
        val searchQuery: String = "",
        /** 搜索结果模式（深度/索引搜索时为 true）。 */
        val searchMode: Boolean = false,
        val searchOptions: SearchOptions = SearchOptions(),
        val showHidden: Boolean = false,
        val showSizes: Boolean = false,
        val filterMode: FilterMode = FilterMode.ALL,
        /** 类型过滤集合（空 = 不过滤）。 */
        val typeFilters: Set<String> = emptySet(),
        val indexEnabled: Boolean = false,
        val engineId: String = "local",
        val isBookmarked: Boolean = false,
        val canBack: Boolean = false,
        val canForward: Boolean = false,
        /** 定位目标：加载完成后自动选中并滚动到此条目（用于最近文件跳转定位）。 */
        val highlightPath: String? = null
    ) {
        val inMultiSelect: Boolean get() = selection.isNotEmpty()
    }

    private val ctx = FreeFileApp.instance

    private val _ui = MutableStateFlow(
        UiState(
            showHidden = AppSettings.state.value.showHidden,
            showSizes = AppSettings.state.value.computeSizes,
            indexEnabled = AppSettings.state.value.indexEnabled,
            viewMode = runCatching {
                AppSettings.state.value.viewMode.let { ViewMode.valueOf(it) }
            }.getOrDefault(ViewMode.LIST),
            searchOptions = SearchOptions(
                searchDepth = AppSettings.state.value.searchDepth,
                regex = AppSettings.state.value.searchRegex,
                caseSensitive = AppSettings.state.value.searchExact
            )
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** 原始目录数据（未过滤未排序）。 */
    private var allEntries: List<VfsEntry> = emptyList()

    private var loadJob: Job? = null
    private var computeJob: Job? = null
    private var searchJob: Job? = null

    private val backStack = ArrayDeque<String>()
    private val forwardStack = ArrayDeque<String>()

    private var selectionAnchor: String? = null

    init {
        // 设置变化即时生效（需求 3）
        viewModelScope.launch {
            AppSettings.state.collect { s ->
                val st = _ui.value
                var changed = false
                var next = st
                if (st.showHidden != s.showHidden) {
                    next = next.copy(showHidden = s.showHidden); changed = true
                }
                if (st.showSizes != s.computeSizes) {
                    next = next.copy(showSizes = s.computeSizes)
                    // 大小计算开关切换后重载当前目录（需求 8：按需计算）
                    changed = true
                }
                if (st.indexEnabled != s.indexEnabled) {
                    next = next.copy(indexEnabled = s.indexEnabled); changed = true
                }
                // 视图模式持久化（需求 3）：设置页或其他窗格切换时即时同步
                val persistedMode = runCatching { ViewMode.valueOf(s.viewMode) }.getOrDefault(ViewMode.LIST)
                if (next.viewMode != persistedMode) {
                    next = next.copy(viewMode = persistedMode)
                }
                if (next.searchOptions.searchDepth != s.searchDepth ||
                    next.searchOptions.regex != s.searchRegex ||
                    next.searchOptions.caseSensitive != s.searchExact
                ) {
                    next = next.copy(
                        searchOptions = next.searchOptions.copy(
                            searchDepth = s.searchDepth,
                            regex = s.searchRegex,
                            caseSensitive = s.searchExact
                        )
                    )
                }
                if (changed) {
                    _ui.value = next
                    if (st.showSizes != s.computeSizes && !next.searchMode) refresh()
                    else if (!next.searchMode) applyView()
                }
            }
        }
        load("/storage/emulated/0")
    }

    private fun msg(resId: Int, vararg args: Any): String =
        ctx.getString(resId, *args)

    // ---------- 浏览 / 导航 ----------

    fun open(path: String) {
        val current = _ui.value.path
        if (current.isNotBlank() && current != path) {
            backStack.addLast(current)
            forwardStack.clear()
        }
        load(path)
    }

    fun openExternal(path: String) {
        if (path.isBlank() || path == _ui.value.path) return
        load(path)
    }

    fun back() {
        val target = backStack.removeLastOrNull() ?: return
        _ui.value.path.takeIf { it.isNotBlank() }?.let { forwardStack.addLast(it) }
        load(target)
    }

    fun forward() {
        val target = forwardStack.removeLastOrNull() ?: return
        _ui.value.path.takeIf { it.isNotBlank() }?.let { backStack.addLast(it) }
        load(target)
    }

    private fun load(path: String) {
        loadJob?.cancel()
        computeJob?.cancel()
        searchJob?.cancel()
        _ui.update {
            it.copy(
                loading = true, error = null, selection = emptySet(),
                path = path, searchQuery = "", searchMode = false,
                canBack = backStack.isNotEmpty(),
                canForward = forwardStack.isNotEmpty()
            )
        }
        selectionAnchor = null
        loadJob = viewModelScope.launch {
            try {
                val engine = EngineSelector.select(path)
                val list = withContext(Dispatchers.IO) { engine.list(path) }
                allEntries = list
                applyView(resetLoading = true)
                // 加载完成后若存在定位目标，则选中该条目（单选高亮）
                val target = _ui.value.highlightPath
                if (target != null) {
                    _ui.update {
                        it.copy(highlightPath = null, selection = setOf(target))
                    }
                    selectionAnchor = target
                } else {
                    _ui.update { it.copy(highlightPath = null) }
                }
                // 查询书签状态（若用户已切换目录则丢弃本次结果，避免覆盖新目录状态）
                val bookmarked = repo.hasBookmark(path)
                _ui.update {
                    if (it.path != path) it
                    else it.copy(
                        loading = false,
                        engineId = engine.id,
                        isBookmarked = bookmarked
                    )
                }
                repo.recordHistory(path)
            } catch (e: Exception) {
                allEntries = emptyList()
                // 即使目录加载失败（如无法访问），仍刷新书签状态，保证按钮显示正确
                val bookmarked = repo.hasBookmark(path)
                _ui.update {
                    if (it.path != path) it
                    else it.copy(
                        loading = false,
                        error = e.message ?: "N/A",
                        entries = emptyList(),
                        totalCount = 0,
                        highlightPath = null,
                        isBookmarked = bookmarked
                    )
                }
            }
        }
    }

    /**
     * 跳转并定位：打开 [filePath] 所在目录，加载完成后高亮选中该文件。
     * 用于"最近文件"列表点击 → 直接跳转定位到所在目录。
     */
    fun locateTo(filePath: String) {
        val parent = filePath.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }
        open(parent)
        _ui.update { it.copy(highlightPath = filePath) }
    }

    /** 后台重算显示列表（过滤 + 排序）。 */
    private fun applyView(resetLoading: Boolean = false) {
        val snapshot = _ui.value
        val source = allEntries
        computeJob?.cancel()
        computeJob = viewModelScope.launch(Dispatchers.IO) {
            val filtered = source
                .asSequence()
                .filter { snapshot.showHidden || !it.isHidden }
                .filter {
                    when (snapshot.filterMode) {
                        FilterMode.ALL -> true
                        FilterMode.FOLDERS_ONLY -> it.isDirectory
                        FilterMode.FILES_ONLY -> !it.isDirectory
                    }
                }
                .filter {
                    snapshot.typeFilters.isEmpty() ||
                        snapshot.typeFilters.contains(FileTypes.categoryOf(it))
                }
                .filter {
                    snapshot.searchQuery.isBlank() ||
                        it.name.contains(snapshot.searchQuery, ignoreCase = true)
                }
                .toList()
            val sorted = filtered.sortedForDisplay(snapshot.sortBy, snapshot.sortAsc)
            withContext(Dispatchers.Main) {
                _ui.update {
                    it.copy(
                        entries = sorted,
                        totalCount = source.size,
                        loading = if (resetLoading) false else it.loading
                    )
                }
            }
        }
    }

    fun refresh() {
        val p = _ui.value.path
        if (p.isNotBlank()) load(p)
    }

    fun openParent() {
        val p = _ui.value.path
        if (p.startsWith("remote://")) {
            // remote://<id>/sub → 逐级回退；位置根无上级
            val rest = p.removePrefix("remote://").trimEnd('/')
            if (!rest.contains('/')) return
            open("remote://" + rest.substringBeforeLast('/') + "/")
        } else {
            val parent = p.trimEnd('/').substringBeforeLast('/')
            open(parent.ifEmpty { "/" })
        }
    }

    // ---------- 排序 / 视图 ----------

    fun setSortBy(sortBy: SortBy) {
        _ui.update {
            if (it.sortBy == sortBy) it.copy(sortAsc = !it.sortAsc)
            else it.copy(sortBy = sortBy, sortAsc = true)
        }
        if (_ui.value.searchMode) {
            _ui.update { st ->
                st.copy(entries = st.entries.sortedForDisplay(st.sortBy, st.sortAsc))
            }
        } else applyView()
    }

    fun toggleViewMode() {
        // 三态循环：LIST → GRID → LARGE_ICON → LIST
        val next = when (_ui.value.viewMode) {
            ViewMode.LIST -> ViewMode.GRID
            ViewMode.GRID -> ViewMode.LARGE_ICON
            ViewMode.LARGE_ICON -> ViewMode.LIST
        }
        setViewMode(next)
    }

    /** 直接设置视图模式并持久化到 DataStore（需求 3）。 */
    fun setViewMode(mode: ViewMode) {
        if (_ui.value.viewMode == mode) return
        _ui.update { it.copy(viewMode = mode) }
        viewModelScope.launch { AppSettings.setViewMode(mode.name) }
    }

    // ---------- 搜索（需求 5：深度 / 正则 / 完全匹配 / 索引缓存） ----------

    fun setSearchOptions(opts: SearchOptions) {
        _ui.update { it.copy(searchOptions = opts) }
    }

    fun search(query: String) {
        val opts = _ui.value.searchOptions
        if (query.isBlank()) {
            // 清空搜索 → 回到目录视图
            searchJob?.cancel()
            if (_ui.value.searchMode) {
                _ui.update { it.copy(searchQuery = "", searchMode = false) }
                applyView(resetLoading = false)
            } else {
                _ui.update { it.copy(searchQuery = "") }
                applyView()
            }
            return
        }
        if (opts.searchDepth == 0 && !opts.regex && !opts.advanced) {
            // 浅搜索：本地即时过滤（原逻辑，已在分线程 applyView 中执行）
            searchJob?.cancel()
            _ui.update { it.copy(searchQuery = query, searchMode = false) }
            applyView()
        } else {
            // 深度/正则搜索：走 SearchEngine（索引或递归）
            searchJob?.cancel()
            _ui.update { it.copy(searchQuery = query, searchMode = true, loading = true) }
            searchJob = viewModelScope.launch {
                try {
                    // 是否走索引：由本次搜索对话框的复选框决定；索引未就绪或内容搜索时回退实时搜索
                    val wantIndex = opts.useIndex &&
                        SearchEngine.indexState.value is IndexState.Ready &&
                        !(opts.advanced && opts.contentPattern.isNotBlank())
                    val results: List<VfsEntry> = if (wantIndex) {
                        SearchEngine.searchIndexed(ctx, query, opts)
                    } else {
                        SearchEngine.searchLive(_ui.value.path, query, opts)
                    }
                    val sorted = results.sortedForDisplay(_ui.value.sortBy, _ui.value.sortAsc)
                    _ui.update {
                        it.copy(entries = sorted, totalCount = results.size, loading = false)
                    }
                } catch (e: Exception) {
                    _ui.update { it.copy(loading = false, error = e.message ?: "N/A") }
                }
            }
        }
    }

    // ---------- 过滤（需求 6：基础模式 + 类型多选） ----------

    fun setFilterMode(mode: FilterMode) {
        _ui.update { it.copy(filterMode = mode) }
        applyView()
    }

    fun toggleTypeFilter(type: String) {
        _ui.update {
            val set = it.typeFilters.toMutableSet()
            if (set.contains(type)) set.remove(type) else set.add(type)
            it.copy(typeFilters = set)
        }
        applyView()
    }

    fun clearTypeFilters() {
        _ui.update { it.copy(typeFilters = emptySet()) }
        applyView()
    }

    // ---------- 多选 ----------

    fun toggleSelect(path: String) {
        selectionAnchor = path
        _ui.update { st ->
            val sel = st.selection.toMutableSet()
            if (sel.contains(path)) sel.remove(path) else sel.add(path)
            st.copy(selection = sel)
        }
    }

    /** 范围选择：从锚点到目标之间（按当前显示顺序）全部选中。 */
    fun selectRangeTo(targetPath: String) {
        val anchor = selectionAnchor ?: return toggleSelect(targetPath)
        val order = _ui.value.entries.map { it.path }
        val a = order.indexOf(anchor)
        val b = order.indexOf(targetPath)
        if (a < 0 || b < 0) return toggleSelect(targetPath)
        val range = if (a <= b) order.subList(a, b + 1) else order.subList(b, a + 1)
        _ui.update { st -> st.copy(selection = st.selection + range) }
    }

    fun selectAll() {
        selectionAnchor = _ui.value.entries.firstOrNull()?.path
        _ui.update { st ->
            st.copy(selection = st.entries.map { it.path }.toSet())
        }
    }

    fun invertSelection() {
        _ui.update { st ->
            val all = st.entries.map { it.path }.toSet()
            st.copy(selection = all - st.selection)
        }
    }

    fun clearSelection() {
        selectionAnchor = null
        _ui.update { it.copy(selection = emptySet()) }
    }

    /** 是否已设置区间选择锚点（用于"区间选择"按钮 UI 反馈，需求 4）。 */
    fun hasSelectionAnchor(): Boolean = selectionAnchor != null

    /**
     * 启用区间选择锚点（需求 4）。
     * 取当前选中项的第一个作为锚点；若无选中则取列表首项。
     * 用户随后点击列表项时会调用 [selectRangeTo] 完成区间选择。
     */
    fun enableRangeAnchor() {
        val current = _ui.value
        selectionAnchor = current.selection.firstOrNull()
            ?: current.entries.firstOrNull()?.path
        if (selectionAnchor == null) {
            // 空列表，提示
            _messages.tryEmit(ctx.getString(R.string.msg_range_no_anchor))
        }
    }

    fun selectedEntries(): List<VfsEntry> {
        val st = _ui.value
        return st.entries.filter { st.selection.contains(it.path) }
    }

    // ---------- 剪贴板（需求 2：复制 / 剪切 / 粘贴） ----------

    fun copyToClipboard(entries: List<VfsEntry>) {
        FileClipboard.copy(entries)
    }

    fun cutToClipboard(entries: List<VfsEntry>) {
        FileClipboard.cut(entries)
    }

    /** 粘贴剪贴板内容到当前目录。返回是否发起了任务。 */
    fun pasteFromClipboard(onStarted: (String) -> Unit): Boolean {
        val clip = FileClipboard.clip.value ?: return false
        if (clip.entries.isEmpty()) return false
        val targetDir = _ui.value.path
        val kindLabel = ctx.getString(
            if (clip.kind == OpKind.COPY) R.string.copy else R.string.cut
        )
        onStarted(msg(R.string.msg_paste_started, kindLabel, clip.entries.size))
        if (clip.kind == OpKind.COPY) {
            TransferManager.enqueueCopy(clip.entries, targetDir) { _, _ ->
                viewModelScope.launch { refresh() }
            }
        } else {
            val moving = clip.entries
            FileClipboard.clear()  // 剪切粘贴一次性
            TransferManager.enqueueMove(moving, targetDir) { _, _ ->
                viewModelScope.launch { refresh() }
            }
        }
        return true
    }

    // ---------- 增删改 ----------

    fun createFolder(name: String) {
        viewModelScope.launch {
            try {
                val engine = EngineSelector.select(_ui.value.path)
                val target = com.freefile.vfs.joinPath(_ui.value.path, name)
                engine.mkdirs(target)
                _messages.emit(msg(R.string.msg_folder_created, name))
                refresh()
            } catch (e: Exception) {
                _messages.emit(msg(R.string.msg_create_failed, e.message ?: ""))
            }
        }
    }

    fun createFile(name: String) {
        viewModelScope.launch {
            try {
                val engine = EngineSelector.select(_ui.value.path)
                val target = com.freefile.vfs.joinPath(_ui.value.path, name)
                engine.openOutputStream(target).use { it.write(ByteArray(0)) }
                _messages.emit(msg(R.string.msg_file_created, name))
                refresh()
            } catch (e: Exception) {
                _messages.emit(msg(R.string.msg_create_failed, e.message ?: ""))
            }
        }
    }

    fun rename(entry: VfsEntry, newName: String) {
        viewModelScope.launch {
            try {
                val engine = EngineSelector.select(entry.path)
                val newPath = com.freefile.vfs.joinPath(
                    entry.path.substringBeforeLast('/'), newName
                )
                if (engine.exists(newPath)) {
                    _messages.emit(msg(R.string.msg_target_exists))
                    return@launch
                }
                engine.rename(entry.path, newPath)
                _messages.emit(msg(R.string.msg_renamed))
                refresh()
            } catch (e: Exception) {
                _messages.emit(msg(R.string.msg_rename_failed, e.message ?: ""))
            }
        }
    }

    fun deleteToTrash(entries: List<VfsEntry>) {
        viewModelScope.launch {
            val ok = repo.moveToTrash(entries)
            _messages.emit(
                if (ok) msg(R.string.msg_moved_to_trash, entries.size)
                else msg(R.string.msg_trash_partial_fail)
            )
            refresh()
            FileChangeBus.notify(_ui.value.path)
        }
    }

    fun deletePermanently(entries: List<VfsEntry>) {
        viewModelScope.launch {
            try {
                TransferManager.enqueueDelete(entries) { ok, _ ->
                    viewModelScope.launch {
                        _messages.emit(
                            if (ok) msg(R.string.msg_deleted_permanent, entries.size)
                            else msg(R.string.msg_delete_failed, "")
                        )
                        refresh()
                        FileChangeBus.notify(_ui.value.path)
                    }
                }
            } catch (e: Exception) {
                _messages.emit(msg(R.string.msg_delete_failed, e.message ?: ""))
            }
        }
    }

    // ---------- 书签 / 最近 ----------

    fun toggleBookmark() {
        viewModelScope.launch {
            val path = _ui.value.path
            if (_ui.value.isBookmarked) {
                repo.removeBookmark(path)
                _ui.update { it.copy(isBookmarked = false) }
                _messages.emit(msg(R.string.msg_bookmark_removed))
            } else {
                repo.addBookmark(path.substringAfterLast('/').ifEmpty { path }, path)
                _ui.update { it.copy(isBookmarked = true) }
                _messages.emit(msg(R.string.msg_bookmark_added))
            }
        }
    }

    fun recordRecentFile(path: String) {
        viewModelScope.launch { repo.recordRecent(path) }
    }
}
