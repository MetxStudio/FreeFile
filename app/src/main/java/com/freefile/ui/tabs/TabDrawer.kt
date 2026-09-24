package com.freefile.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.freefile.R

/**
 * 右侧标签抽屉：集中管理两窗格的全部标签页。
 * 双窗口始终各显示一个标签，其余标签收在这里，点击即切换到对应窗格。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabDrawerContent(
    tabManager: TabManager,
    activePane: Pane,
    onSelectTab: (Pane, Int) -> Unit,
    onNewTab: (Pane) -> Unit,
    onClose: () -> Unit = {}
) {
    val leftTabs = tabManager.leftTabs
    val rightTabs = tabManager.rightTabs

    ModalDrawerSheet {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 标题行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.Tab,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.tabs),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    stringResource(R.string.new_tab),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onNewTab(activePane) }
                        .padding(6.dp)
                )
                // 显式关闭按钮（用户反馈：滑动关闭抽屉有 bug）
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.close)
                    )
                }
            }

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                // 左窗格组
                item { PaneSectionLabel(stringResource(R.string.left_pane)) }
                itemsIndexed(leftTabs, key = { _, t -> "L${t.id}" }) { index, tab ->
                    TabRow(
                        tab = tab,
                        isCurrentOfPane = index == tabManager.leftCurrentIndex,
                        isActivePane = activePane == Pane.LEFT,
                        onClick = { onSelectTab(Pane.LEFT, index) },
                        onClose = { tabManager.closeTab(Pane.LEFT, index) }
                    )
                }
                // 右窗格组
                item { PaneSectionLabel(stringResource(R.string.right_pane)) }
                itemsIndexed(rightTabs, key = { _, t -> "R${t.id}" }) { index, tab ->
                    TabRow(
                        tab = tab,
                        isCurrentOfPane = index == tabManager.rightCurrentIndex,
                        isActivePane = activePane == Pane.RIGHT,
                        onClick = { onSelectTab(Pane.RIGHT, index) },
                        onClose = { tabManager.closeTab(Pane.RIGHT, index) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PaneSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun TabRow(
    tab: Tab,
    isCurrentOfPane: Boolean,
    isActivePane: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit
) {
    val container = when {
        isCurrentOfPane && isActivePane -> MaterialTheme.colorScheme.primaryContainer
        isCurrentOfPane -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .background(container, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                tab.title.ifEmpty { stringResource(R.string.new_tab_label) },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (tab.path.isNotEmpty()) {
                Text(
                    tab.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (isCurrentOfPane) {
            Text(
                if (isActivePane) stringResource(R.string.tab_active)
                else stringResource(R.string.tab_current),
                style = MaterialTheme.typography.labelSmall,
                color = if (isActivePane) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
        }
        IconButton(onClick = onClose, modifier = Modifier.size(26.dp)) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = stringResource(R.string.close_tab),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
