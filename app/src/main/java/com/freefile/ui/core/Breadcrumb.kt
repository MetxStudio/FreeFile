package com.freefile.ui.core

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.freefile.R

/**
 * 面包屑导航：逐级显示路径，点击跳转；下拉切换同级兄弟目录（后续实现）。
 *
 * @param path 当前目录路径（用 '/' 分隔）
 * @param onNavigate 点击某一级时回调该级完整路径
 */
@Composable
fun Breadcrumb(
    path: String,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit = {}
) {
    if (path.isEmpty()) {
        Text(
            text = stringResource(R.string.unauthorized_dir),
            modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    val segments = path.trimStart('/').split('/').filter { it.isNotEmpty() }
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 根目录入口
        Icon(
            imageVector = Icons.Rounded.Home,
            contentDescription = stringResource(R.string.root_dir),
            modifier = Modifier
                .padding(end = 4.dp)
                .clickable { onNavigate("/") },
            tint = MaterialTheme.colorScheme.primary
        )
        var accumulated = ""
        segments.forEachIndexed { index, name ->
            accumulated += "/$name"
            val target = accumulated
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                modifier = Modifier.padding(horizontal = 2.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .clickable { onNavigate(target) }
                    .padding(vertical = 2.dp),
                color = if (index == segments.lastIndex)
                    MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
