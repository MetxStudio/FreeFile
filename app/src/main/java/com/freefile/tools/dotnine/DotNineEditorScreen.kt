package com.freefile.tools.dotnine

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.freefile.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 画笔模式：绘制（涂黑）或擦除（恢复透明）。 */
private enum class PaintMode { DRAW, ERASE }

private enum class Edge { TOP, BOTTOM, LEFT, RIGHT }

/**
 * 引导线编辑控制器：撤销/重做快照栈 + 版本号（驱动画布重绘）。
 * guide 实例保持不变，撤销时原地覆盖数组，避免手势闭包持有旧引用。
 */
private class GuideEditor(val guide: NinePatchGuide) {
    private val undoStack = ArrayDeque<NinePatchGuide>()
    private val redoStack = ArrayDeque<NinePatchGuide>()

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var version by mutableIntStateOf(0)
        private set

    /** 一次连续编辑开始前压入快照（点击 / 拖拽起点各一次）。 */
    fun beginChange() {
        undoStack.addLast(guide.copyOf())
        if (undoStack.size > MAX_STACK) undoStack.removeFirst()
        redoStack.clear()
        refresh()
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(guide.copyOf())
        guide.restoreFrom(prev)
        refresh()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(guide.copyOf())
        guide.restoreFrom(next)
        refresh()
    }

    private fun refresh() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
        version++
    }

    companion object {
        private const val MAX_STACK = 60
    }
}

/** 点九编辑器主屏幕。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DotNineEditorScreen(
    filePath: String?,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // 当前编辑的工作文件（本地路径；SAF 打开时为 cache 副本）
    var workingFile by remember { mutableStateOf<File?>(null) }
    var displayName by remember { mutableStateOf("") }
    var openedFromSaf by remember { mutableStateOf(false) }

    var contentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var guide by remember { mutableStateOf<NinePatchGuide?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var paintMode by remember { mutableStateOf(PaintMode.DRAW) }
    var showPreview by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }

    // 视图尺寸与缩放/平移
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    // 是否处于"自动适应"状态：用户一旦手动缩放/平移即关闭，切图/点适应按钮重新打开。
    // 自动模式下显示矩形在组合阶段直接按 fit 计算，避免先显示错误初始位置再回弹。
    var autoFit by remember { mutableStateOf(true) }

    val borderDp = 24.dp
    val borderPx = with(density) { borderDp.toPx() }

    val editor = remember(guide) { guide?.let { GuideEditor(it) } }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    /** 载入工作文件并重建状态。 */
    fun applyLoaded(file: File, name: String, fromSaf: Boolean) {
        scope.launch {
            try {
                val (bmp, g) = withContext(Dispatchers.IO) { loadNinePatch(file) }
                contentBitmap?.takeIf { !it.isRecycled }?.recycle()
                contentBitmap = bmp
                guide = g
                workingFile = file
                displayName = name
                openedFromSaf = fromSaf
                dirty = false
                error = null
                paintMode = PaintMode.DRAW
                pan = Offset.Zero
                autoFit = true
            } catch (e: Exception) {
                error = e.message ?: context.getString(R.string.dotnine_load_failed)
            }
        }
    }

    // 初始载入外部传入的本地路径
    LaunchedEffect(filePath) {
        val path = filePath ?: run {
            error = context.getString(R.string.dotnine_no_file)
            return@LaunchedEffect
        }
        val file = File(path)
        if (!file.exists()) {
            error = context.getString(R.string.dotnine_file_not_found)
            return@LaunchedEffect
        }
        applyLoaded(file, file.name, fromSaf = false)
    }

    val bmp = contentBitmap
    val g = guide

    // 适应屏幕缩放
    fun fitScale(): Float {
        val b = contentBitmap ?: return 1f
        if (viewSize.width == 0 || viewSize.height == 0) return 1f
        val availW = viewSize.width - 2 * borderPx
        val availH = viewSize.height - 2 * borderPx
        return minOf(availW / b.width, availH / b.height).coerceAtLeast(0.01f)
    }

    // 当前生效的缩放/平移：自动适应模式下直接派生，首帧即为正确位置（无回弹/竞态）
    val effectiveScale = if (autoFit) fitScale() else scale
    val effectivePan = if (autoFit) Offset.Zero else pan

    // 内容图在画布上的位置矩形
    val contentRect = if (bmp != null) {
        val w = bmp.width * effectiveScale
        val h = bmp.height * effectiveScale
        val cx = (viewSize.width - w) / 2f + effectivePan.x
        val cy = (viewSize.height - h) / 2f + effectivePan.y
        androidx.compose.ui.geometry.Rect(cx, cy, cx + w, cy + h)
    } else null

    // SAF：打开其他图片
    val openDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val name = queryDisplayName(context, uri) ?: "image.png"
                val dir = File(context.cacheDir, "dotnine").apply { mkdirs() }
                val work = File(dir, name)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        work.outputStream().use { input.copyTo(it) }
                    } ?: throw IllegalStateException("openInputStream 返回空")
                }
                applyLoaded(work, name, fromSaf = true)
            } catch (e: Exception) {
                toast(e.message ?: context.getString(R.string.dotnine_open_failed))
            }
        }
    }

    // SAF：导出 .9.png
    val createDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val cb = contentBitmap
        val gd = guide
        if (cb == null || gd == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        writeNinePatch(os, cb, gd)
                    } ?: throw IllegalStateException("openOutputStream 返回空")
                }
                dirty = false
                toast(context.getString(R.string.dotnine_export_done))
            } catch (e: Exception) {
                toast(e.message ?: context.getString(R.string.dotnine_export_failed))
            }
        }
    }

    /** 保存：本地文件直接写；SAF 打开的走 CreateDocument 导出。 */
    fun persistBitmap() {
        val cb = contentBitmap ?: return
        val gd = guide ?: return
        val work = workingFile ?: return
        if (openedFromSaf) {
            val defaultName = if (isNinePatchFile(work.name)) work.name
            else work.nameWithoutExtension + ".9.png"
            createDocLauncher.launch(defaultName)
            return
        }
        val outFile = if (isNinePatchFile(work.name)) work
        else File(work.parentFile, work.nameWithoutExtension + ".9.png")
        scope.launch {
            try {
                withContext(Dispatchers.IO) { saveNinePatch(outFile, cb, gd) }
                if (outFile != work) {
                    workingFile = outFile
                    displayName = outFile.name
                }
                dirty = false
                toast(context.getString(R.string.dotnine_saved, outFile.name))
            } catch (e: Exception) {
                toast(e.message ?: context.getString(R.string.dotnine_save_failed))
            }
        }
    }

    fun requestExit() {
        if (dirty) showExitConfirm = true else onBack()
    }

    // 系统返回键：未保存时弹确认
    BackHandler(enabled = dirty) { showExitConfirm = true }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.smallTopAppBarColors(
                    containerColor = Color(0xFF1565C0),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                title = {
                    Text(
                        text = displayName.ifEmpty { stringResource(R.string.dotnine_title) },
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { requestExit() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    val hasImage = bmp != null
                    IconButton(onClick = { editor?.undo(); dirty = true },
                        enabled = hasImage && editor?.canUndo == true) {
                        Icon(Icons.Rounded.Undo,
                            contentDescription = stringResource(R.string.dotnine_undo))
                    }
                    IconButton(onClick = { editor?.redo(); dirty = true },
                        enabled = hasImage && editor?.canRedo == true) {
                        Icon(Icons.Rounded.Redo,
                            contentDescription = stringResource(R.string.dotnine_redo))
                    }
                    IconButton(onClick = { showPreview = true }, enabled = hasImage) {
                        Icon(Icons.Rounded.Visibility,
                            contentDescription = stringResource(R.string.dotnine_preview))
                    }
                    IconButton(onClick = { openDocLauncher.launch(arrayOf("image/png")) }) {
                        Icon(Icons.Rounded.FolderOpen,
                            contentDescription = stringResource(R.string.dotnine_open))
                    }
                    IconButton(onClick = { persistBitmap() }, enabled = hasImage) {
                        Icon(Icons.Rounded.Check,
                            contentDescription = stringResource(R.string.dotnine_save))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onSizeChanged { viewSize = it }
            ) {
                if (bmp != null && g != null && editor != null && contentRect != null) {
                    NinePatchCanvas(
                        contentBitmap = bmp,
                        editor = editor,
                        contentRect = contentRect,
                        borderPx = borderPx,
                        paintMode = paintMode,
                        onGuideEdited = { dirty = true },
                        onPan = { delta ->
                            // 首次手动平移：把自动适应的派生值固化到 scale/pan
                            if (autoFit) {
                                scale = effectiveScale
                                autoFit = false
                            }
                            pan = Offset(pan.x + delta.x, pan.y + delta.y)
                        }
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = error ?: stringResource(R.string.dotnine_loading),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            BottomControls(
                paintMode = paintMode,
                onPaintModeChange = { paintMode = it },
                onClear = {
                    val ed = editor ?: return@BottomControls
                    ed.beginChange()
                    ed.guide.clear()
                    dirty = true
                },
                onFit = {
                    autoFit = true
                    pan = Offset.Zero
                },
                onActualSize = {
                    autoFit = false
                    scale = 1f
                    pan = Offset.Zero
                },
                onZoomIn = {
                    if (autoFit) {
                        scale = effectiveScale
                        autoFit = false
                    }
                    scale = (scale * 1.5f).coerceAtMost(64f)
                },
                onZoomOut = {
                    if (autoFit) {
                        scale = effectiveScale
                        autoFit = false
                    }
                    scale = (scale / 1.5f).coerceAtLeast(fitScale())
                },
                zoomPercent = (effectiveScale * 100).toInt()
            )
        }
    }

    // 拉伸预览
    if (showPreview && bmp != null && g != null && editor != null) {
        StretchPreviewDialog(
            contentBitmap = bmp,
            guide = g,
            version = editor.version,
            onDismiss = { showPreview = false }
        )
    }

    // 未保存退出确认
    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text(stringResource(R.string.dotnine_unsaved_title)) },
            text = { Text(stringResource(R.string.dotnine_unsaved_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    onBack()
                }) { Text(stringResource(R.string.dotnine_exit)) }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/** 点九绘制画布：纹理棋盘背景 + 连续段引导线，pointerInput 常驻不重启。 */
@Composable
private fun NinePatchCanvas(
    contentBitmap: Bitmap,
    editor: GuideEditor,
    contentRect: androidx.compose.ui.geometry.Rect,
    borderPx: Float,
    paintMode: PaintMode,
    onGuideEdited: () -> Unit,
    onPan: (delta: Offset) -> Unit
) {
    val guide = editor.guide
    val contentW = contentBitmap.width
    val contentH = contentBitmap.height

    // 动态值通过 state 传入常驻手势检测器，避免绘制时重启手势
    val rectState = remember { mutableStateOf(contentRect) }.also { it.value = contentRect }
    val modeState = remember { mutableStateOf(paintMode) }.also { it.value = paintMode }

    val checker = remember { createCheckerBitmap() }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(contentBitmap) {
                detectTapGestures { pos ->
                    val hit = hitTest(pos, rectState.value, borderPx, contentW, contentH)
                    if (hit != null) {
                        editor.beginChange()
                        applyGuide(guide, hit, modeState.value)
                        onGuideEdited()
                    }
                }
            }
            .pointerInput(contentBitmap) {
                var panning = false
                var lockedEdge: Edge? = null
                var lastIndex = -1

                detectDragGestures(
                    onDragStart = { pos ->
                        val hit = hitTest(pos, rectState.value, borderPx, contentW, contentH)
                        if (hit == null) {
                            panning = true
                        } else {
                            panning = false
                            lockedEdge = hit.first
                            lastIndex = hit.second
                            editor.beginChange()
                            applyGuide(guide, hit, modeState.value)
                            onGuideEdited()
                        }
                    },
                    onDragEnd = {
                        panning = false
                        lockedEdge = null
                        lastIndex = -1
                    },
                    onDragCancel = {
                        panning = false
                        lockedEdge = null
                        lastIndex = -1
                    },
                    onDrag = { change, dragAmount ->
                        if (panning) {
                            onPan(dragAmount)
                        } else {
                            val edge = lockedEdge
                            val hit = hitTest(change.position, rectState.value, borderPx,
                                contentW, contentH)
                            if (edge != null && hit != null && hit.first == edge) {
                                // 填充经过的所有像素，快速拖动也不断线
                                val value = modeState.value == PaintMode.DRAW
                                val from = minOf(lastIndex, hit.second)
                                val to = maxOf(lastIndex, hit.second)
                                val arr = when (edge) {
                                    Edge.TOP -> guide.top
                                    Edge.BOTTOM -> guide.bottom
                                    Edge.LEFT -> guide.left
                                    Edge.RIGHT -> guide.right
                                }
                                for (i in from..to) arr[i] = value
                                lastIndex = hit.second
                                onGuideEdited()
                            }
                        }
                        change.consume()
                    }
                )
            }
    ) {
        // 订阅版本号，引导线被原地修改后触发重绘
        @Suppress("UNUSED_EXPRESSION") editor.version

        val rect = contentRect

        // 棋盘格：BitmapShader 平铺，仅一次 drawRect
        drawIntoCanvas { canvas ->
            val shader = BitmapShader(checker, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            val paint = Paint().apply { this.shader = shader }
            canvas.nativeCanvas.drawRect(
                RectF(rect.left, rect.top, rect.right, rect.bottom), paint
            )
        }

        // 内容图
        withTransform({
            translate(rect.left, rect.top)
            scale(scaleX = rect.width / contentW, scaleY = rect.height / contentH)
        }) {
            drawImage(contentBitmap.asImageBitmap())
        }

        // 四周边框底色
        val borderColor = Color(0x33000000)
        drawRect(borderColor, topLeft = Offset(rect.left, rect.top - borderPx),
            size = Size(rect.width, borderPx))
        drawRect(borderColor, topLeft = Offset(rect.left, rect.bottom),
            size = Size(rect.width, borderPx))
        drawRect(borderColor, topLeft = Offset(rect.left - borderPx, rect.top),
            size = Size(borderPx, rect.height))
        drawRect(borderColor, topLeft = Offset(rect.right, rect.top),
            size = Size(borderPx, rect.height))

        // 引导线：合并连续段，整条边至多几个 drawRect
        val black = Color.Black
        val cellW = rect.width / contentW
        val cellH = rect.height / contentH
        drawHorizontalSegments(guide.top, rect.left, rect.top - borderPx, cellW, borderPx, black)
        drawHorizontalSegments(guide.bottom, rect.left, rect.bottom, cellW, borderPx, black)
        drawVerticalSegments(guide.left, rect.left - borderPx, rect.top, borderPx, cellH, black)
        drawVerticalSegments(guide.right, rect.right, rect.top, borderPx, cellH, black)

        // 内容区分界
        drawRect(
            color = Color(0x66000000),
            topLeft = Offset(rect.left, rect.top),
            size = Size(rect.width, rect.height),
            style = Stroke(width = 1f)
        )
    }
}

/** 横向引导线（上/下边）：把连续 true 合并为一个矩形。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHorizontalSegments(
    marks: BooleanArray,
    startX: Float,
    y: Float,
    cellW: Float,
    height: Float,
    color: Color
) {
    var i = 0
    while (i < marks.size) {
        if (marks[i]) {
            var j = i + 1
            while (j < marks.size && marks[j]) j++
            drawRect(
                color = color,
                topLeft = Offset(startX + i * cellW, y),
                size = Size((j - i) * cellW, height)
            )
            i = j
        } else i++
    }
}

/** 纵向引导线（左/右边）。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawVerticalSegments(
    marks: BooleanArray,
    x: Float,
    startY: Float,
    width: Float,
    cellH: Float,
    color: Color
) {
    var i = 0
    while (i < marks.size) {
        if (marks[i]) {
            var j = i + 1
            while (j < marks.size && marks[j]) j++
            drawRect(
                color = color,
                topLeft = Offset(x, startY + i * cellH),
                size = Size(width, (j - i) * cellH)
            )
            i = j
        } else i++
    }
}

/** 生成 16x16 棋盘纹理（4 个 8px 色块）。 */
private fun createCheckerBitmap(): Bitmap {
    val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
    val light = 0xFFF2F2F2.toInt()
    val dark = 0xFFD3D3D3.toInt()
    for (y in 0 until 16) {
        for (x in 0 until 16) {
            val odd = (x / 8 + y / 8) % 2 == 0
            bmp.setPixel(x, y, if (odd) light else dark)
        }
    }
    return bmp
}

/** 命中测试：返回被点击的边框与像素索引。 */
private fun hitTest(
    pos: Offset,
    rect: androidx.compose.ui.geometry.Rect,
    borderPx: Float,
    contentW: Int,
    contentH: Int
): Pair<Edge, Int>? {
    val cellW = rect.width / contentW
    val cellH = rect.height / contentH
    val x = pos.x
    val y = pos.y

    if (y in (rect.top - borderPx)..rect.top && x in rect.left..rect.right) {
        val idx = ((x - rect.left) / cellW).toInt().coerceIn(0, contentW - 1)
        return Edge.TOP to idx
    }
    if (y in rect.bottom..(rect.bottom + borderPx) && x in rect.left..rect.right) {
        val idx = ((x - rect.left) / cellW).toInt().coerceIn(0, contentW - 1)
        return Edge.BOTTOM to idx
    }
    if (x in (rect.left - borderPx)..rect.left && y in rect.top..rect.bottom) {
        val idx = ((y - rect.top) / cellH).toInt().coerceIn(0, contentH - 1)
        return Edge.LEFT to idx
    }
    if (x in rect.right..(rect.right + borderPx) && y in rect.top..rect.bottom) {
        val idx = ((y - rect.top) / cellH).toInt().coerceIn(0, contentH - 1)
        return Edge.RIGHT to idx
    }
    return null
}

private fun applyGuide(guide: NinePatchGuide, hit: Pair<Edge, Int>, mode: PaintMode) {
    val value = mode == PaintMode.DRAW
    when (hit.first) {
        Edge.TOP -> guide.top[hit.second] = value
        Edge.BOTTOM -> guide.bottom[hit.second] = value
        Edge.LEFT -> guide.left[hit.second] = value
        Edge.RIGHT -> guide.right[hit.second] = value
    }
}

/** 底部控制栏（横向可滚动，避免小屏按钮被挤压变形）。 */
@Composable
private fun BottomControls(
    paintMode: PaintMode,
    onPaintModeChange: (PaintMode) -> Unit,
    onClear: () -> Unit,
    onFit: () -> Unit,
    onActualSize: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    zoomPercent: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .horizontalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        FilterChip(
            selected = paintMode == PaintMode.DRAW,
            onClick = { onPaintModeChange(PaintMode.DRAW) },
            label = { Text(stringResource(R.string.dotnine_draw)) },
            leadingIcon = {
                Icon(Icons.Rounded.Brush, contentDescription = null,
                    modifier = Modifier.size(18.dp))
            }
        )
        FilterChip(
            selected = paintMode == PaintMode.ERASE,
            onClick = { onPaintModeChange(PaintMode.ERASE) },
            label = { Text(stringResource(R.string.dotnine_erase)) },
            leadingIcon = {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null,
                    modifier = Modifier.size(18.dp))
            }
        )
        IconButton(onClick = onClear) {
            Icon(Icons.Rounded.ClearAll,
                contentDescription = stringResource(R.string.dotnine_clear))
        }
        IconButton(onClick = onFit) {
            Icon(Icons.Rounded.CropFree,
                contentDescription = stringResource(R.string.dotnine_fit))
        }
        TextButton(
            onClick = onActualSize,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
        ) {
            Text("1:1")
        }
        Spacer(Modifier.width(12.dp))
        IconButton(onClick = onZoomOut) {
            Icon(Icons.Rounded.ZoomOut,
                contentDescription = stringResource(R.string.dotnine_zoom_out))
        }
        Text(
            "$zoomPercent%",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(56.dp),
            maxLines = 1
        )
        IconButton(onClick = onZoomIn) {
            Icon(Icons.Rounded.ZoomIn,
                contentDescription = stringResource(R.string.dotnine_zoom_in))
        }
    }
}

/** 拉伸预览对话框：滑块调整目标宽高，实时按点九规则渲染，紫框为内容区。 */
@Composable
private fun StretchPreviewDialog(
    contentBitmap: Bitmap,
    guide: NinePatchGuide,
    version: Int,
    onDismiss: () -> Unit
) {
    val maxW = (contentBitmap.width * 4).coerceAtLeast(32)
    val maxH = (contentBitmap.height * 4).coerceAtLeast(32)
    var targetW by remember(contentBitmap) {
        mutableFloatStateOf((contentBitmap.width * 2).toFloat())
    }
    var targetH by remember(contentBitmap) {
        mutableFloatStateOf((contentBitmap.height * 2).toFloat())
    }
    val checker = remember { createCheckerBitmap() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dotnine_preview_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        @Suppress("UNUSED_EXPRESSION") version
                        // 背景棋盘铺满预览区
                        drawIntoCanvas { canvas ->
                            val shader = BitmapShader(checker,
                                Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                            val paint = Paint().apply { this.shader = shader }
                            canvas.nativeCanvas.drawRect(
                                RectF(0f, 0f, size.width, size.height), paint
                            )
                        }
                        val tw = targetW.toInt().coerceAtLeast(1)
                        val th = targetH.toInt().coerceAtLeast(1)
                        val ds = minOf(size.width / tw, size.height / th) * 0.9f
                        val dx = (size.width - tw * ds) / 2f
                        val dy = (size.height - th * ds) / 2f
                        drawIntoCanvas { canvas ->
                            val c: AndroidCanvas = canvas.nativeCanvas
                            c.save()
                            c.translate(dx, dy)
                            c.scale(ds, ds)
                            drawNinePatchPreview(c, contentBitmap, guide, tw, th)
                            c.restore()
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.dotnine_preview_width, targetW.toInt()),
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = targetW,
                    onValueChange = { targetW = it },
                    valueRange = 16f..maxW.toFloat()
                )
                Text(
                    stringResource(R.string.dotnine_preview_height, targetH.toInt()),
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = targetH,
                    onValueChange = { targetH = it },
                    valueRange = 16f..maxH.toFloat()
                )
                Text(
                    stringResource(R.string.dotnine_preview_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dotnine_preview_close))
            }
        }
    )
}

/** 查询 content:// 的显示文件名。 */
private fun queryDisplayName(context: android.content.Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
        null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
} catch (_: Exception) {
    null
}
