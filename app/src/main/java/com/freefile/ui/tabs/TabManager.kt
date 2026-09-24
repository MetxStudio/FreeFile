package com.freefile.ui.tabs

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.util.UUID

/** 窗格标识：左/右。 */
enum class Pane { LEFT, RIGHT }

/**
 * 单个标签页。
 * @param id 唯一标识（持久化、恢复会话用）
 * @param path 当前目录路径
 * @param sortBy 排序方式（name/size/type/modified）
 * @param sortAsc 升序
 * @param viewMode 视图模式（list/grid）
 */
data class Tab(
    val id: String = UUID.randomUUID().toString(),
    val path: String = "",
    val sortBy: String = "name",
    val sortAsc: Boolean = true,
    val viewMode: String = "list"
) {
    /** 由路径推导的标签标题（空路径返回空串，由 UI 层显示"新标签"文案）。 */
    val title: String
        get() {
            if (path.isEmpty()) return ""
            val name = path.trimEnd('/').substringAfterLast('/')
            return if (name.isEmpty()) path else name
        }
}

/**
 * 标签页管理器：每个窗格独立维护无限标签页。
 * 支持新建/关闭/选择/拖拽排序，状态用 Compose 快照保持以驱动 UI。
 */
class TabManager {

    private val _leftTabs = mutableStateListOf<Tab>()
    private val _rightTabs = mutableStateListOf<Tab>()

    val leftTabs: List<Tab> get() = _leftTabs
    val rightTabs: List<Tab> get() = _rightTabs

    var leftCurrentIndex by mutableStateOf(0)
        private set
    var rightCurrentIndex by mutableStateOf(0)
        private set

    val leftCurrentTab: Tab? get() = _leftTabs.getOrNull(leftCurrentIndex)
    val rightCurrentTab: Tab? get() = _rightTabs.getOrNull(rightCurrentIndex)

    init {
        // 每个窗格初始化一个空白标签，后续由 SAF/权限层填充真实路径
        if (_leftTabs.isEmpty()) _leftTabs.add(Tab())
        if (_rightTabs.isEmpty()) _rightTabs.add(Tab())
    }

    /** 当前窗格对应的标签列表。 */
    fun tabsOf(pane: Pane): List<Tab> = when (pane) {
        Pane.LEFT -> _leftTabs
        Pane.RIGHT -> _rightTabs
    }

    /** 当前选中标签。 */
    fun currentTab(pane: Pane): Tab? = when (pane) {
        Pane.LEFT -> leftCurrentTab
        Pane.RIGHT -> rightCurrentTab
    }

    /** 新建标签并切换过去。 */
    fun newTab(pane: Pane, path: String = "") {
        val tab = Tab(path = path)
        when (pane) {
            Pane.LEFT -> {
                _leftTabs.add(tab)
                leftCurrentIndex = _leftTabs.lastIndex
            }
            Pane.RIGHT -> {
                _rightTabs.add(tab)
                rightCurrentIndex = _rightTabs.lastIndex
            }
        }
    }

    /** 关闭指定标签；标签全关后保留一个空白标签。 */
    fun closeTab(pane: Pane, index: Int) {
        when (pane) {
            Pane.LEFT -> {
                if (index in _leftTabs.indices) {
                    _leftTabs.removeAt(index)
                    if (_leftTabs.isEmpty()) _leftTabs.add(Tab())
                    leftCurrentIndex = (leftCurrentIndex).coerceIn(0, _leftTabs.lastIndex)
                    if (index < leftCurrentIndex) leftCurrentIndex--
                }
            }
            Pane.RIGHT -> {
                if (index in _rightTabs.indices) {
                    _rightTabs.removeAt(index)
                    if (_rightTabs.isEmpty()) _rightTabs.add(Tab())
                    rightCurrentIndex = (rightCurrentIndex).coerceIn(0, _rightTabs.lastIndex)
                    if (index < rightCurrentIndex) rightCurrentIndex--
                }
            }
        }
    }

    /** 选中标签。 */
    fun selectTab(pane: Pane, index: Int) {
        when (pane) {
            Pane.LEFT -> if (index in _leftTabs.indices) leftCurrentIndex = index
            Pane.RIGHT -> if (index in _rightTabs.indices) rightCurrentIndex = index
        }
    }

    /** 更新当前标签的路径（进入目录时调用）。 */
    fun updateCurrentPath(pane: Pane, path: String) {
        val list = when (pane) {
            Pane.LEFT -> _leftTabs
            Pane.RIGHT -> _rightTabs
        }
        val idx = when (pane) {
            Pane.LEFT -> leftCurrentIndex
            Pane.RIGHT -> rightCurrentIndex
        }
        if (idx in list.indices) {
            list[idx] = list[idx].copy(path = path)
        }
    }

    /** 在窗格内拖拽排序：from -> to。 */
    fun moveTab(pane: Pane, from: Int, to: Int) {
        val list = when (pane) {
            Pane.LEFT -> _leftTabs
            Pane.RIGHT -> _rightTabs
        }
        if (from !in list.indices || to !in list.indices) return
        val item = list.removeAt(from)
        list.add(to, item)
        when (pane) {
            Pane.LEFT -> {
                leftCurrentIndex = when {
                    from == leftCurrentIndex -> to
                    from < leftCurrentIndex && to >= leftCurrentIndex -> leftCurrentIndex - 1
                    from > leftCurrentIndex && to <= leftCurrentIndex -> leftCurrentIndex + 1
                    else -> leftCurrentIndex
                }
            }
            Pane.RIGHT -> {
                rightCurrentIndex = when {
                    from == rightCurrentIndex -> to
                    from < rightCurrentIndex && to >= rightCurrentIndex -> rightCurrentIndex - 1
                    from > rightCurrentIndex && to <= rightCurrentIndex -> rightCurrentIndex + 1
                    else -> rightCurrentIndex
                }
            }
        }
    }

    /** 整体交换左右窗格的标签页（含当前选中索引）。 */
    fun swapPanes() {
        val lt = _leftTabs.toList()
        val li = leftCurrentIndex
        _leftTabs.clear()
        _leftTabs.addAll(_rightTabs.toList())
        leftCurrentIndex = rightCurrentIndex
        _rightTabs.clear()
        _rightTabs.addAll(lt)
        rightCurrentIndex = li
    }
}
