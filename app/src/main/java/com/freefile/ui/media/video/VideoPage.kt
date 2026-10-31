package com.freefile.ui.media.video

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.freefile.R
import com.freefile.ui.media.MediaActionRowHeight
import com.freefile.ui.media.MediaItem
import com.freefile.ui.media.PipPlaybackBridge
import kotlinx.coroutines.delay

/** 可选倍速档位。 */
private val SPEED_OPTIONS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * 视频页：Media3 ExoPlayer + PlayerView（禁用自带控制器，统一使用 Compose 控制条）。
 *
 * 控制层级唯一：
 *  - 单击视频区域 → 通知外层切换顶/底栏与本控制条（三层同步显隐，互不重叠）
 *  - 双击视频区域 → 播放/暂停
 *  - 底部控制条：播放/暂停、进度、倍速、单集循环、音轨、字幕、画中画
 *  - 控制条通过底部预留 [MediaActionRowHeight] 空间避让外层操作栏，绝不重叠
 */
@Composable
fun VideoPage(
    item: MediaItem,
    isActive: Boolean = true,
    isInPip: Boolean = false,
    controlsVisible: Boolean = true,
    onToggleControls: () -> Unit = {},
    onVideoSizeChanged: (width: Int, height: Int) -> Unit = { _, _ -> },
    onEnterPip: () -> Unit = {},
    pipBridge: PipPlaybackBridge? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 重新 prepare 的信号：点击重试时 +1
    var retryToken by remember(item.uri) { mutableIntStateOf(0) }

    val player = remember(item.uri, retryToken) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(ExoMediaItem.fromUri(item.uri))
            prepare()
            playWhenReady = false
        }
    }

    var playerError by remember(item.uri) { mutableStateOf<PlaybackException?>(null) }
    var hasAudioTrack by remember { mutableStateOf(false) }
    var hasTextTrack by remember { mutableStateOf(false) }
    var speed by remember(item.uri) { mutableFloatStateOf(1f) }
    var repeatOne by remember(item.uri) { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var sliderPos by remember { mutableFloatStateOf(0f) }

    DisposableEffect(item.uri, retryToken) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    onVideoSizeChanged(videoSize.width, videoSize.height)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                playerError = error
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                // 同步到画中画遥控桥，刷新小窗按钮图标
                pipBridge?.isPlaying = playing
                pipBridge?.onPlaybackStateChanged?.invoke()
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                hasAudioTrack = tracks.groups.any {
                    it.type == C.TRACK_TYPE_AUDIO && it.length > 0
                }
                hasTextTrack = tracks.groups.any {
                    it.type == C.TRACK_TYPE_TEXT && it.length > 0
                }
            }
        }
        player.addListener(listener)
        // 注册画中画遥控命令：RemoteAction 点击时切换播放/暂停
        pipBridge?.togglePlayback = {
            if (player.isPlaying) player.pause() else player.play()
        }
        onDispose {
            player.removeListener(listener)
            player.release()
            pipBridge?.togglePlayback = null
        }
    }

    // 切走页面时暂停；回到当前页自动播放（无错误时）
    LaunchedEffect(isActive) {
        if (isActive) {
            if (playerError == null) player.playWhenReady = true
        } else {
            player.pause()
        }
    }

    // 完全进入后台（ON_STOP）时暂停；画中画不会触发 ON_STOP，因此小窗内继续播放
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                player.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 周期刷新播放进度（拖动中暂停刷新，避免跳变）
    LaunchedEffect(item.uri) {
        while (true) {
            if (!isSeeking) {
                duration = player.duration.coerceAtLeast(0L)
                position = player.currentPosition.coerceAtLeast(0L)
            }
            delay(500)
        }
    }

    // 播放中 3 秒无操作自动隐藏控制条（与外层顶/底栏同步）
    val currentToggle by rememberUpdatedState(onToggleControls)
    LaunchedEffect(controlsVisible, isPlaying, isInPip) {
        if (controlsVisible && isPlaying && !isInPip) {
            delay(3000)
            currentToggle()
        }
    }

    // ---- 手势缩放状态（双指捏合缩放视频画面，可拖动平移，双击复位由双击播放冲突→三击不用，用缩放回弹） ----
    var videoScale by remember { mutableFloatStateOf(1f) }
    var videoOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    // 禁用自带控制器，避免与 Compose 控制层重叠
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = videoScale,
                    scaleY = videoScale,
                    translationX = videoOffset.x,
                    translationY = videoOffset.y
                ),
            update = { playerView ->
                playerView.player = player
            }
        )

        // 触摸层：双指缩放/平移（优先），单击切换控制栏，双击播放/暂停
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isInPip) {
                    if (!isInPip) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (videoScale * zoom).coerceIn(1f, 5f)
                            videoScale = newScale
                            if (newScale > 1f) {
                                videoOffset += pan
                            } else {
                                videoOffset = androidx.compose.ui.geometry.Offset.Zero
                            }
                        }
                    }
                }
                .pointerInput(isInPip) {
                    if (!isInPip) {
                        detectTapGestures(
                            onTap = { currentToggle() },
                            onDoubleTap = {
                                if (videoScale > 1f) {
                                    // 已放大：双击复位
                                    videoScale = 1f
                                    videoOffset = androidx.compose.ui.geometry.Offset.Zero
                                } else {
                                    if (player.isPlaying) player.pause() else player.play()
                                }
                            }
                        )
                    }
                }
        )

        // 画中画小窗：底部只读迷你进度条（RemoteAction 无法承载进度条）
        if (isInPip) {
            val fraction = if (duration > 0) {
                (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            } else 0f
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.25f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(Color.White)
                )
            }
        }

        // 播放失败：错误信息 + 重试
        if (playerError != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.media_video_play_failed),
                    color = Color.White
                )
                Spacer(Modifier.size(12.dp))
                TextButton(onClick = {
                    playerError = null
                    retryToken++
                }) {
                    Icon(
                        Icons.Rounded.Replay,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.media_retry), color = Color.White)
                }
            }
        }

        // 底部控制条（唯一控制层）：避让外层操作栏高度，绝不重叠
        AnimatedVisibility(
            visible = controlsVisible && !isInPip && isActive,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            VideoControlBar(
                isPlaying = isPlaying,
                position = position,
                duration = duration,
                isSeeking = isSeeking,
                sliderPos = sliderPos,
                speed = speed,
                repeatOne = repeatOne,
                hasAudioTrack = hasAudioTrack,
                hasTextTrack = hasTextTrack,
                onPlayPause = {
                    if (player.isPlaying) player.pause() else player.play()
                },
                onSeekChange = {
                    isSeeking = true
                    sliderPos = it
                },
                onSeekFinished = {
                    if (duration > 0) player.seekTo((sliderPos * duration).toLong())
                    isSeeking = false
                },
                onSpeedChange = {
                    speed = it
                    player.setPlaybackSpeed(it)
                },
                onToggleRepeat = {
                    repeatOne = !repeatOne
                    player.repeatMode =
                        if (repeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                },
                onPickAudio = {
                    TrackSelectionDialogBuilder(
                        context,
                        context.getString(R.string.media_track_audio),
                        player,
                        C.TRACK_TYPE_AUDIO
                    ).setShowDisableOption(false).build().show()
                },
                onPickSubtitle = {
                    TrackSelectionDialogBuilder(
                        context,
                        context.getString(R.string.media_track_subtitle),
                        player,
                        C.TRACK_TYPE_TEXT
                    ).setShowDisableOption(true).build().show()
                },
                onEnterPip = onEnterPip
            )
        }
    }
}

@Composable
private fun VideoControlBar(
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    isSeeking: Boolean,
    sliderPos: Float,
    speed: Float,
    repeatOne: Boolean,
    hasAudioTrack: Boolean,
    hasTextTrack: Boolean,
    onPlayPause: () -> Unit,
    onSeekChange: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onToggleRepeat: () -> Unit,
    onPickAudio: () -> Unit,
    onPickSubtitle: () -> Unit,
    onEnterPip: () -> Unit
) {
    var speedMenuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.65f))
            .navigationBarsPadding()
            .padding(bottom = MediaActionRowHeight)
    ) {
        // 第一行：播放/暂停 + 时间 + 进度条
        Row(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
            Text(
                text = formatTime(position),
                color = Color.White,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 2.dp)
            )
            Slider(
                value = when {
                    isSeeking -> sliderPos
                    duration > 0 -> position.toFloat() / duration.toFloat()
                    else -> 0f
                },
                onValueChange = onSeekChange,
                onValueChangeFinished = onSeekFinished,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
            )
            Text(
                text = formatTime(duration),
                color = Color.White,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(end = 8.dp)
            )
        }

        // 第二行：倍速 / 循环 / 音轨 / 字幕 / 画中画
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 倍速
            Box {
                IconButton(onClick = { speedMenuOpen = true }) {
                    Icon(
                        Icons.Rounded.Speed,
                        contentDescription = stringResource(R.string.media_speed),
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                DropdownMenu(
                    expanded = speedMenuOpen,
                    onDismissRequest = { speedMenuOpen = false }
                ) {
                    SPEED_OPTIONS.forEach { value ->
                        DropdownMenuItem(
                            text = { Text(formatSpeed(value)) },
                            onClick = {
                                onSpeedChange(value)
                                speedMenuOpen = false
                            }
                        )
                    }
                }
            }

            // 单集循环
            IconButton(onClick = onToggleRepeat) {
                Icon(
                    if (repeatOne) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    contentDescription = stringResource(R.string.media_repeat),
                    tint = if (repeatOne) Color(0xFF90CAF9) else Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            // 音轨
            IconButton(onClick = onPickAudio, enabled = hasAudioTrack) {
                Icon(
                    Icons.Rounded.MusicNote,
                    contentDescription = stringResource(R.string.media_track_audio),
                    tint = if (hasAudioTrack) Color.White else Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(24.dp)
                )
            }

            // 字幕
            IconButton(onClick = onPickSubtitle, enabled = hasTextTrack) {
                Icon(
                    Icons.Rounded.Subtitles,
                    contentDescription = stringResource(R.string.media_track_subtitle),
                    tint = if (hasTextTrack) Color.White else Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(Modifier.weight(1f))

            // 画中画
            IconButton(onClick = onEnterPip) {
                Icon(
                    Icons.Rounded.PictureInPicture,
                    contentDescription = stringResource(R.string.media_pip),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

private fun formatSpeed(speed: Float): String {
    // 1.0x 显示为 1x，其余保留必要小数
    return if (speed == 1f) "1x" else "${speed.toString().trimEnd('0').trimEnd('.')}x"
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
