package com.freefile.tools.dotnine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 点九（NinePatch）引导线模型。
 *
 * 一张 .9.png 是在内容图外围加一圈 1px 边框：
 *  - 上边（row=0）：水平可拉伸区域
 *  - 左边（col=0）：垂直可拉伸区域
 *  - 下边（row=h+1）：水平内容（内边距）区域
 *  - 右边（col=w+1）：垂直内容（内边距）区域
 * 引导像素为纯黑不透明（#FF000000），其余边框像素透明。
 */
data class NinePatchGuide(
    val contentWidth: Int,
    val contentHeight: Int,
    val top: BooleanArray,
    val left: BooleanArray,
    val bottom: BooleanArray,
    val right: BooleanArray
) {
    fun copyOf(): NinePatchGuide = NinePatchGuide(
        contentWidth = contentWidth,
        contentHeight = contentHeight,
        top = top.copyOf(),
        left = left.copyOf(),
        bottom = bottom.copyOf(),
        right = right.copyOf()
    )

    /** 用另一个同尺寸快照原地覆盖（保持数组引用，供撤销/重做使用）。 */
    fun restoreFrom(other: NinePatchGuide) {
        System.arraycopy(other.top, 0, top, 0, top.size)
        System.arraycopy(other.left, 0, left, 0, left.size)
        System.arraycopy(other.bottom, 0, bottom, 0, bottom.size)
        System.arraycopy(other.right, 0, right, 0, right.size)
    }

    fun clear() {
        top.fill(false)
        left.fill(false)
        bottom.fill(false)
        right.fill(false)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NinePatchGuide) return false
        return contentWidth == other.contentWidth &&
            contentHeight == other.contentHeight &&
            top.contentEquals(other.top) &&
            left.contentEquals(other.left) &&
            bottom.contentEquals(other.bottom) &&
            right.contentEquals(other.right)
    }

    override fun hashCode(): Int {
        var result = contentWidth
        result = 31 * result + contentHeight
        result = 31 * result + top.contentHashCode()
        result = 31 * result + left.contentHashCode()
        result = 31 * result + bottom.contentHashCode()
        result = 31 * result + right.contentHashCode()
        return result
    }
}

/** 判断文件名是否为点九文件。 */
fun isNinePatchFile(name: String): Boolean =
    name.endsWith(".9.png", ignoreCase = true)

/** 从本地文件加载点九：返回（内容位图，引导线）。 */
fun loadNinePatch(file: File): Pair<Bitmap, NinePatchGuide> =
    file.inputStream().use { loadNinePatch(it, file.name) }

/** 从输入流加载点九（content:// 等场景）。 */
fun loadNinePatch(input: InputStream, displayName: String): Pair<Bitmap, NinePatchGuide> {
    val full = BitmapFactory.decodeStream(input)
        ?: throw IllegalArgumentException("无法解码图片：$displayName")
    return if (isNinePatchFile(displayName) && full.width > 2 && full.height > 2) {
        // 必须在回收全图前提取引导线
        val guide = extractGuide(full)
        val content = Bitmap.createBitmap(full, 1, 1, full.width - 2, full.height - 2)
        if (content !== full) full.recycle()
        content to guide
    } else {
        full to emptyGuide(full.width, full.height)
    }
}

/** 从带边框的位图中提取引导线（黑像素视为已标记）。 */
private fun extractGuide(bmp: Bitmap): NinePatchGuide {
    val w = bmp.width - 2
    val h = bmp.height - 2
    val top = BooleanArray(w) { isBlackPixel(bmp.getPixel(it + 1, 0)) }
    val left = BooleanArray(h) { isBlackPixel(bmp.getPixel(0, it + 1)) }
    val bottom = BooleanArray(w) { isBlackPixel(bmp.getPixel(it + 1, bmp.height - 1)) }
    val right = BooleanArray(h) { isBlackPixel(bmp.getPixel(bmp.width - 1, it + 1)) }
    return NinePatchGuide(w, h, top, left, bottom, right)
}

private fun emptyGuide(w: Int, h: Int) = NinePatchGuide(
    contentWidth = w,
    contentHeight = h,
    top = BooleanArray(w),
    left = BooleanArray(h),
    bottom = BooleanArray(w),
    right = BooleanArray(h)
)

private fun isBlackPixel(color: Int): Boolean {
    val alpha = (color ushr 24) and 0xFF
    val r = (color shr 16) and 0xFF
    val g = (color shr 8) and 0xFF
    val b = color and 0xFF
    return alpha >= 128 && r < 128 && g < 128 && b < 128
}

/** 合成带 1px 边框的标准点九位图。 */
fun renderNinePatch(content: Bitmap, guide: NinePatchGuide): Bitmap {
    val w = content.width
    val h = content.height
    val out = Bitmap.createBitmap(w + 2, h + 2, Bitmap.Config.ARGB_8888)
    out.eraseColor(AndroidColor.TRANSPARENT)
    val canvas = AndroidCanvas(out)
    canvas.drawBitmap(content, 1f, 1f, null)
    val paint = Paint().apply {
        color = AndroidColor.BLACK
        isAntiAlias = false
    }
    for (x in 0 until w) {
        if (guide.top[x]) canvas.drawPoint((x + 1).toFloat(), 0f, paint)
        if (guide.bottom[x]) canvas.drawPoint((x + 1).toFloat(), (h + 1).toFloat(), paint)
    }
    for (y in 0 until h) {
        if (guide.left[y]) canvas.drawPoint(0f, (y + 1).toFloat(), paint)
        if (guide.right[y]) canvas.drawPoint((w + 1).toFloat(), (y + 1).toFloat(), paint)
    }
    return out
}

/** 保存为 .9.png 文件。 */
fun saveNinePatch(file: File, content: Bitmap, guide: NinePatchGuide) {
    file.outputStream().use { writeNinePatch(it, content, guide) }
}

/** 写入 .9.png 到任意输出流（SAF 导出场景）。 */
fun writeNinePatch(out: OutputStream, content: Bitmap, guide: NinePatchGuide) {
    val bmp = renderNinePatch(content, guide)
    try {
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
    } finally {
        bmp.recycle()
    }
    out.flush()
}

/** 单轴的九宫格切分：[0, start) 固定前段、[start, end) 拉伸段、[end, length) 固定后段。 */
private data class AxisSlice(
    val length: Int,
    val stretchStart: Int,
    val stretchEnd: Int,
    val hasStretch: Boolean
) {
    /** 给定目标尺寸，返回三段各自的像素长度（无拉伸段时后两段为 0，保持原尺寸）。 */
    fun targetSegments(target: Int): IntArray {
        if (!hasStretch) return intArrayOf(length, 0, 0)
        val fixed = stretchStart + (length - stretchEnd)
        val stretchDst = (target - fixed).coerceAtLeast(0)
        return intArrayOf(stretchStart, stretchDst, length - stretchEnd)
    }
}

private fun sliceAxis(length: Int, marks: BooleanArray): AxisSlice {
    var start = -1
    var end = -1
    for (i in 0 until length) {
        if (marks[i]) {
            if (start == -1) start = i
            end = i + 1
        } else if (start != -1) break // 只取第一个连续段
    }
    return if (start == -1) AxisSlice(length, 0, 0, false)
    else AxisSlice(length, start, end, true)
}

/**
 * 按点九规则把内容图绘制到目标尺寸（单拉伸段，与系统 NinePatch 单段效果一致）。
 * 同时根据下/右引导线绘制紫色内容区（内边距）指示框。
 */
fun drawNinePatchPreview(
    canvas: AndroidCanvas,
    content: Bitmap,
    guide: NinePatchGuide,
    targetW: Int,
    targetH: Int,
    drawContentPadding: Boolean = true
) {
    val col = sliceAxis(content.width, guide.top)
    val row = sliceAxis(content.height, guide.left)
    val cw = col.targetSegments(targetW)
    val ch = row.targetSegments(targetH)

    val srcX = intArrayOf(0, col.stretchStart, col.stretchEnd)
    val srcXEnd = intArrayOf(col.stretchStart, col.stretchEnd, content.width)
    val srcY = intArrayOf(0, row.stretchStart, row.stretchEnd)
    val srcYEnd = intArrayOf(row.stretchStart, row.stretchEnd, content.height)

    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    val src = Rect()
    val dst = RectF()
    var dy = 0f
    for (r in 0..2) {
        if (ch[r] <= 0) { dy += ch[r]; continue }
        var dx = 0f
        for (cIdx in 0..2) {
            if (cw[cIdx] > 0 && srcXEnd[cIdx] > srcX[cIdx] && srcYEnd[r] > srcY[r]) {
                src.set(srcX[cIdx], srcY[r], srcXEnd[cIdx], srcYEnd[r])
                dst.set(dx, dy, dx + cw[cIdx], dy + ch[r])
                canvas.drawBitmap(content, src, dst, paint)
            }
            dx += cw[cIdx]
        }
        dy += ch[r]
    }

    if (drawContentPadding) {
        val padCol = sliceAxis(content.width, guide.bottom)
        val padRow = sliceAxis(content.height, guide.right)
        if (padCol.hasStretch && padRow.hasStretch) {
            val pcw = padCol.targetSegments(targetW)
            val pch = padRow.targetSegments(targetH)
            val left = pcw[0].toFloat()
            val top = pch[0].toFloat()
            val right = targetW - pcw[2].toFloat()
            val bottom = targetH - pch[2].toFloat()
            val padPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.parseColor("#AA7B1FA2")
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
            canvas.drawRect(left, top, right, bottom, padPaint)
        }
    }
}
