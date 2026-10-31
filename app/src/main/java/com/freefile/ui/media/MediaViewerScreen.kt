package com.freefile.ui.media

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.request.ImageRequest
import com.freefile.R
import com.freefile.ui.media.audio.AudioPage
import com.freefile.ui.media.image.ImagePage
import com.freefile.ui.media.video.VideoPage
import kotlinx.coroutines.launch

/** 底部操作栏固定高度：视频控制条/音频内容据此上移避让，保证不重叠。 */
val MediaActionRowHeight = 84.dp

/**
 * 媒体查看器主屏幕（无 Scaffold，单一控制层）。
 *  - 顶部：标题栏悬浮层；底部：操作栏悬浮层
 *  - 两者与视频页控制条共用 [controlsVisible] 同步显隐，层级互不重叠
 *  - 图片额外提供设为壁纸；视频的画中画/倍速等控件在视频页控制条内
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaViewerScreen(
    items: List<MediaItem>,
    initialIndex: Int = 0,
    isInPip: Boolean = false,
    onBack: () -> Unit,
    onShare: (MediaItem) -> Unit,
    onDelete: (MediaItem) -> Boolean,
    onDetails: (MediaItem) -> Unit,
    onOpenWith: (MediaItem) -> Unit,
    onSetWallpaper: (MediaItem) -> Unit = {},
    onCurrentTypeChanged: (MediaType) -> Unit = {},
    onVideoSizeChanged: (Int, Int) -> Unit = { _, _ -> },
    onEnterPip: () -> Unit = {},
    pipBridge: PipPlaybackBridge? = null
) {
    // 可变列表：删除后实时更新
    var itemList by remember { mutableStateOf(items.toMutableList()) }
    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { itemList.size }
    )
    var currentIndex by remember { mutableIntStateOf(initialIndex) }
    val scope = rememberCoroutineScope()

    // 顶/底悬浮栏显隐（单击内容切换；视频页播放中自动隐藏）
    var controlsVisible by remember { mutableStateOf(true) }
    val context = LocalContext.current

    LaunchedEffect(pagerState.currentPage) {
        currentIndex = pagerState.currentPage
        // 切换媒体后控制栏恢复显示
        controlsVisible = true
    }

    val current = itemList.getOrNull(currentIndex)

    // 通知 Activity 当前媒体类型（用于 Home 键判断是否进入 PiP）
    LaunchedEffect(current?.type) {
        current?.let { onCurrentTypeChanged(it.type) }
    }

    // 相邻图片预加载（仅预解码相邻 ±1，降低切换白屏）
    LaunchedEffect(currentIndex, itemList) {
        listOf(currentIndex - 1, currentIndex + 1)
            .filter { it in itemList.indices && itemList[it].type == MediaType.IMAGE }
            .forEach { idx ->
                val request = ImageRequest.Builder(context)
                    .data(itemList[idx].uri)
                    .build()
                context.imageLoader.enqueue(request)
            }
    }

    // 系统返回键也走 onBack，确保删除结果回传
    BackHandler { onBack() }

    // PiP 中强制隐藏所有覆盖控件
    val chromeVisible = controlsVisible && !isInPip

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 媒体内容铺满全屏
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = itemList[page]
            when (item.type) {
                MediaType.IMAGE -> ImagePage(
                    item = item,
                    onTap = { controlsVisible = !controlsVisible }
                )
                MediaType.VIDEO -> VideoPage(
                    item = item,
                    isActive = page == currentIndex,
                    isInPip = isInPip,
                    controlsVisible = chromeVisible,
                    onToggleControls = { controlsVisible = !controlsVisible },
                    onVideoSizeChanged = onVideoSizeChanged,
                    onEnterPip = onEnterPip,
                    pipBridge = pipBridge
                )
                MediaType.AUDIO -> AudioPage(
                    item = item,
                    isActive = page == currentIndex,
                    onPrevious = {
                        if (page > 0) scope.launch { pagerState.animateScrollToPage(page - 1) }
                    },
                    onNext = {
                        if (page < itemList.size - 1) scope.launch { pagerState.animateScrollToPage(page + 1) }
                    },
                    onTap = { controlsVisible = !controlsVisible }
                )
                MediaType.UNKNOWN -> UnknownMedia(
                    item = item,
                    onTap = { controlsVisible = !controlsVisible }
                )
            }
        }

        // 顶部标题栏（悬浮，随 chrome 显隐）
        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.75f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Rounded.ArrowBack,
                        contentDescription = null,
                        tint = Color.White
                    )
                }
                Text(
                    text = current?.title ?: "",
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${currentIndex + 1}/${itemList.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }

        // 底部操作栏（悬浮，随 chrome 显隐）
        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.75f))
                    .navigationBarsPadding()
                    .height(MediaActionRowHeight)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ActionButton(icon = Icons.Rounded.Share, label = stringResource(R.string.media_share)) {
                    current?.let(onShare)
                }
                ActionButton(icon = Icons.Rounded.Info, label = stringResource(R.string.media_details)) {
                    current?.let(onDetails)
                }
                if (current?.type == MediaType.IMAGE) {
                    ActionButton(
                        icon = Icons.Rounded.Wallpaper,
                        label = stringResource(R.string.media_set_wallpaper)
                    ) {
                        current.let(onSetWallpaper)
                    }
                }
                ActionButton(icon = Icons.Rounded.OpenInNew, label = stringResource(R.string.media_open_with)) {
                    current?.let(onOpenWith)
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(68.dp)
            .padding(vertical = 4.dp)
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun UnknownMedia(
    item: MediaItem,
    onTap: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.media_unsupported, item.title),
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
