package com.freefile.ui.core

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 分割线位置预设（占左窗格宽度比例）。 */
object DividerPresets {
    const val LEFT_HEAVY = 0.7f   // 70:30
    const val EVEN = 0.5f        // 50:50
    const val RIGHT_HEAVY = 0.3f // 30:70
}

/**
 * 双窗口分屏布局：左右窗格独立操作，分割线可拖拽调整比例。
 *
 * @param dividerPosition 左窗格占比（0..1），外部控制以便持久化
 * @param onDividerPositionChange 拖拽后回调新比例
 * @param leftPane 左窗格内容
 * @param rightPane 右窗格内容
 */
@Composable
fun DualPaneLayout(
    modifier: Modifier = Modifier,
    dividerPosition: Float = DividerPresets.EVEN,
    onDividerPositionChange: (Float) -> Unit = {},
    leftPane: @Composable () -> Unit,
    rightPane: @Composable () -> Unit
) {
    var position by remember(dividerPosition) { mutableStateOf(dividerPosition) }
    val dividerTouchWidth = 16.dp
    val dividerVisualWidth = 1.dp
    // Composable 属性需在 Composable 作用域读取，不能放进 DrawScope
    val dividerColor = MaterialTheme.colorScheme.outlineVariant

    Row(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(position)) {
            leftPane()
        }

        // 可拖拽分割线（含触摸热区）
        Canvas(
            modifier = Modifier
                .fillMaxHeight()
                .width(dividerTouchWidth)
                .pointerInput(position) {
                    detectDragGestures(
                        onDragStart = { },
                        onDragEnd = { onDividerPositionChange(position) },
                        onDragCancel = { onDividerPositionChange(position) },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val widthPx = this.size.width.toFloat().coerceAtLeast(1f)
                            position = (position + dragAmount.x / widthPx)
                                .coerceIn(0.15f, 0.85f)
                        }
                    )
                }
        ) {
            val canvasWidth = this.size.width
            val canvasHeight = this.size.height
            val lineX = (canvasWidth - dividerVisualWidth.toPx()) / 2f
            drawLine(
                color = dividerColor,
                start = Offset(lineX, 0f),
                end = Offset(lineX, canvasHeight),
                strokeWidth = dividerVisualWidth.toPx()
            )
        }

        Box(modifier = Modifier.weight(1f - position)) {
            rightPane()
        }
    }
}

/** 将比例限制到预设之一（用于快捷按钮）。 */
fun snapDivider(current: Float): Float {
    val presets = listOf(DividerPresets.LEFT_HEAVY, DividerPresets.EVEN, DividerPresets.RIGHT_HEAVY)
    return presets.minBy { kotlin.math.abs(it - current) }
}

@Suppress("unused")
private fun Float.roundTo2Decimals(): Float = (this * 100f).roundToInt() / 100f
