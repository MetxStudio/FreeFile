package com.freefile.tools.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.freefile.tools.ai.model.ThinkText

/**
 * 轻量 Markdown 渲染器（无第三方依赖，兼容 Compose 1.6）。
 *
 * 支持：标题、有序/无序列表、引用、分隔线、围栏代码块、段落，
 * 以及行内 **粗体**、*斜体*、`代码`、~~删除线~~、[链接](url)。
 * 流式输出时未闭合的标记会按普通文本原样显示，不会吞字。
 */
@Composable
fun MarkdownText(
    markdown: String,
    color: Color,
    modifier: Modifier = Modifier,
    baseStyle: TextStyle = MaterialTheme.typography.bodyMedium
) {
    // 已保存的消息可能仍带 <think> 残留，渲染前再剥一次，避免标签直接暴露。
    val body = remember(markdown) {
        val (_, visible) = ThinkText.split(markdown)
        visible
    }
    val blocks = remember(body) { parseMarkdown(body) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
    val linkColor = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.outline
    val bodyStyle = baseStyle.copy(color = color)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> Text(
                    text = inlineStyled(body = block.text, color = color, codeBg = codeBg, linkColor = linkColor),
                    style = bodyStyle
                )

                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> bodyStyle.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        2 -> bodyStyle.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        else -> bodyStyle.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = inlineStyled(body = block.text, color = color, codeBg = codeBg, linkColor = linkColor),
                        style = style
                    )
                }

                is MdBlock.Bullet -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    block.items.forEach { item ->
                        Row {
                            Text(
                                "•",
                                style = bodyStyle.copy(color = muted),
                                modifier = Modifier
                                    .width(16.dp)
                                    .padding(start = (item.indent * 12).dp)
                            )
                            Text(
                                text = inlineStyled(item.text, color, codeBg, linkColor),
                                style = bodyStyle,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                is MdBlock.Ordered -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    block.items.forEachIndexed { idx, item ->
                        Row {
                            Text(
                                "${idx + 1}.",
                                style = bodyStyle.copy(color = muted),
                                modifier = Modifier
                                    .width(24.dp)
                                    .padding(start = (item.indent * 12).dp)
                            )
                            Text(
                                text = inlineStyled(item.text, color, codeBg, linkColor),
                                style = bodyStyle,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(muted.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = inlineStyled(block.text, color, codeBg, linkColor),
                        style = bodyStyle.copy(color = muted, fontStyle = FontStyle.Italic)
                    )
                }

                is MdBlock.Code -> Box(
                    Modifier
                        .fillMaxWidth()
                        .background(codeBg, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = block.code,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = color
                        ),
                        softWrap = false,
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    )
                }

                MdBlock.Rule -> HorizontalDivider(
                    color = muted.copy(alpha = 0.35f),
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

// ------------------------------------------------------------- 块级解析

private sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val items: List<ListItem>) : MdBlock
    data class Ordered(val items: List<ListItem>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    object Rule : MdBlock
}

private data class ListItem(val indent: Int, val text: String)

private val headingRegex = Regex("^\\s{0,3}(#{1,6})\\s+(.*)$")
private val bulletRegex = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val orderedRegex = Regex("^(\\s*)\\d{1,3}[.)]\\s+(.*)$")
private val ruleRegex = Regex("^\\s{0,3}([-*_])(?:\\s*\\1){2,}\\s*$")

private fun parseMarkdown(markdown: String): List<MdBlock> {
    val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val blocks = ArrayList<MdBlock>()
    val paragraph = ArrayList<String>()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks.add(MdBlock.Paragraph(paragraph.joinToString("\n").trim()))
            paragraph.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> {
                flushParagraph()
                i++
            }

            trimmed.startsWith("```") -> {
                flushParagraph()
                val lang = trimmed.removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                if (i < lines.size) i++ // 跳过闭合围栏
                blocks.add(MdBlock.Code(lang, code.toString()))
            }

            ruleRegex.matches(trimmed) -> {
                flushParagraph()
                blocks.add(MdBlock.Rule)
                i++
            }

            headingRegex.matches(line) -> {
                flushParagraph()
                val m = headingRegex.matchEntire(line)
                val level = m?.groupValues?.get(1)?.length ?: 1
                val text = m?.groupValues?.get(2)?.trim().orEmpty()
                blocks.add(MdBlock.Heading(level, text.trimEnd('#').trim()))
                i++
            }

            trimmed.startsWith(">") -> {
                flushParagraph()
                val quote = ArrayList<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quote.add(lines[i].trim().removePrefix(">").trim())
                    i++
                }
                blocks.add(MdBlock.Quote(quote.joinToString("\n")))
            }

            bulletRegex.matches(line) -> {
                flushParagraph()
                val items = ArrayList<ListItem>()
                while (i < lines.size) {
                    val m = bulletRegex.matchEntire(lines[i]) ?: break
                    val indent = (m.groupValues[1].length / 2).coerceAtMost(3)
                    items.add(ListItem(indent, m.groupValues[2].trim()))
                    i++
                }
                blocks.add(MdBlock.Bullet(items))
            }

            orderedRegex.matches(line) -> {
                flushParagraph()
                val items = ArrayList<ListItem>()
                while (i < lines.size) {
                    val m = orderedRegex.matchEntire(lines[i]) ?: break
                    val indent = (m.groupValues[1].length / 2).coerceAtMost(3)
                    items.add(ListItem(indent, m.groupValues[2].trim()))
                    i++
                }
                blocks.add(MdBlock.Ordered(items))
            }

            else -> {
                paragraph.add(trimmed)
                i++
            }
        }
    }
    flushParagraph()
    return blocks
}

// ------------------------------------------------------------- 行内样式

private fun inlineStyled(
    body: String,
    color: Color,
    codeBg: Color,
    linkColor: Color
): AnnotatedString = buildAnnotatedString {
    val plain = StringBuilder()
    fun flushPlain() {
        if (plain.isNotEmpty()) {
            append(plain.toString())
            plain.clear()
        }
    }

    var i = 0
    while (i < body.length) {
        val c = body[i]
        when {
            c == '`' -> {
                val end = body.indexOf('`', i + 1)
                if (end > i + 1) {
                    flushPlain()
                    val start = length
                    append(body.substring(i + 1, end))
                    addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg), start, length)
                    i = end + 1
                } else {
                    plain.append(c); i++
                }
            }

            body.startsWith("**", i) || body.startsWith("__", i) -> {
                val mark = body.substring(i, i + 2)
                val end = body.indexOf(mark, i + 2)
                if (end > i + 2) {
                    flushPlain()
                    val start = length
                    append(body.substring(i + 2, end))
                    addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
                    i = end + 2
                } else {
                    plain.append(c); i++
                }
            }

            body.startsWith("~~", i) -> {
                val end = body.indexOf("~~", i + 2)
                if (end > i + 2) {
                    flushPlain()
                    val start = length
                    append(body.substring(i + 2, end))
                    addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, length)
                    i = end + 2
                } else {
                    plain.append(c); i++
                }
            }

            c == '[' -> {
                val close = body.indexOf(']', i + 1)
                if (close > i + 1 && close + 1 < body.length && body[close + 1] == '(') {
                    val urlEnd = body.indexOf(')', close + 2)
                    if (urlEnd > close) {
                        flushPlain()
                        val start = length
                        append(body.substring(i + 1, close))
                        addStyle(
                            SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline
                            ),
                            start,
                            length
                        )
                        i = urlEnd + 1
                    } else {
                        plain.append(c); i++
                    }
                } else {
                    plain.append(c); i++
                }
            }

            c == '*' || c == '_' -> {
                val end = body.indexOf(c, i + 1)
                if (end > i + 1) {
                    flushPlain()
                    val start = length
                    append(body.substring(i + 1, end))
                    addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length)
                    i = end + 1
                } else {
                    plain.append(c); i++
                }
            }

            else -> {
                plain.append(c)
                i++
            }
        }
    }
    flushPlain()
}
