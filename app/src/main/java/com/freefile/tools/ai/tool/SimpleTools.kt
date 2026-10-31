package com.freefile.tools.ai.tool

import android.content.Intent
import android.net.Uri
import com.freefile.tools.ai.model.ErrorCode
import com.freefile.tools.ai.model.ToolPermission
import com.freefile.tools.ai.model.ToolResult
import com.freefile.tools.ai.tool.ToolArgs.str
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 轻量日常任务工具：算术计算、当前时间、打开链接。
 * 面向「不太复杂」的即时需求，全部无文件写操作。
 */

// --------------------------------------------------------------- 计算器

/** 安全四则运算求值（+ - * / % 括号 ^），纯手写解析，不执行任意代码。 */
object Calculator {

    private class Parser(private val s: String) {
        private var pos = 0

        fun parse(): Double {
            val v = parseExpr()
            skip()
            if (pos < s.length) throw IllegalArgumentException("位置 $pos 存在无法解析的字符")
            return v
        }

        private fun skip() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun peek(): Char? {
            skip()
            return if (pos < s.length) s[pos] else null
        }

        private fun parseExpr(): Double {
            var v = parseTerm()
            while (true) {
                when (peek()) {
                    '+' -> { pos++; v += parseTerm() }
                    '-' -> { pos++; v -= parseTerm() }
                    else -> return v
                }
            }
        }

        private fun parseTerm(): Double {
            var v = parseUnary()
            while (true) {
                when (peek()) {
                    '*' -> { pos++; v *= parseUnary() }
                    '/' -> {
                        pos++
                        val d = parseUnary()
                        if (d == 0.0) throw IllegalArgumentException("除数为 0")
                        v /= d
                    }
                    '%' -> {
                        pos++
                        val d = parseUnary()
                        if (d == 0.0) throw IllegalArgumentException("模数为 0")
                        v %= d
                    }
                    else -> return v
                }
            }
        }

        private fun parseUnary(): Double {
            skip()
            if (pos < s.length && s[pos] == '-') {
                pos++
                return -parseUnary()
            }
            if (pos < s.length && s[pos] == '+') {
                pos++
                return parseUnary()
            }
            return parsePower()
        }

        private fun parsePower(): Double {
            val base = parseAtom()
            skip()
            if (pos < s.length && s[pos] == '^') {
                pos++
                return Math.pow(base, parseUnary())
            }
            return base
        }

        private fun parseAtom(): Double {
            skip()
            if (pos >= s.length) throw IllegalArgumentException("表达式意外结束")
            val c = s[pos]
            if (c == '(') {
                pos++
                val v = parseExpr()
                skip()
                if (pos >= s.length || s[pos] != ')') throw IllegalArgumentException("缺少右括号")
                pos++
                return v
            }
            // 数字（支持 1.5、.5）
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            if (pos == start) throw IllegalArgumentException("位置 $pos 存在无法解析的字符 '${s[pos]}'")
            return s.substring(start, pos).toDouble()
        }
    }

    fun eval(expr: String): Double {
        val cleaned = expr.replace('（', '(').replace('）', ')')
            .replace('×', '*').replace('÷', '/')
            .replace(",", "")
        return Parser(cleaned).parse()
    }
}

class CalculatorTool : AgentTool {
    override val name = "calculator"
    override val description =
        "计算数学表达式（支持 + - * / % ^ 括号，中文括号与 × ÷ 自动转换）。需要精确算术时必须使用本工具，不要心算。"
    override val permission = ToolPermission.READ
    override val write = false
    override val undoable = false

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().put("expression", JSONObject().put("type", "string")))
        .put("required", org.json.JSONArray(listOf("expression")))

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult {
        val expr = args.str("expression")
        if (expr.isEmpty()) {
            return ToolResult.error(ErrorCode.INVALID_ARGS, "缺少 expression")
        }
        return try {
            val v = Calculator.eval(expr)
            val out = if (v == Math.floor(v) && !v.isInfinite() &&
                Math.abs(v) < 9.0e15
            ) v.toLong().toString() else v.toString()
            val data = JSONObject()
            data.put("expression", expr as Any)
            data.put("result", out as Any)
            ToolResult.ok(data, "$expr = $out")
        } catch (t: Throwable) {
            ToolResult.error(ErrorCode.INVALID_ARGS, "无法计算：${t.message}")
        }
    }
}

// --------------------------------------------------------------- 当前时间

class GetCurrentTimeTool : AgentTool {
    override val name = "get_current_time"
    override val description = "获取设备当前日期、时间、星期与时间戳（毫秒）。"
    override val permission = ToolPermission.READ
    override val write = false
    override val undoable = false

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object").put("properties", JSONObject())

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult {
        val now = System.currentTimeMillis()
        val d = Date(now)
        val data = JSONObject()
        data.put("timestamp_ms", now)
        data.put("datetime", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(d) as Any)
        data.put("weekday", SimpleDateFormat("EEEE", Locale.getDefault()).format(d) as Any)
        data.put("timezone", java.util.TimeZone.getDefault().id as Any)
        return ToolResult.ok(
            data,
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE", Locale.getDefault()).format(d)
        )
    }
}

// --------------------------------------------------------------- 打开链接

class OpenUrlTool : AgentTool {
    override val name = "open_url"
    override val description =
        "用系统浏览器打开网页链接（http/https）。这是外发动作：调用后确认卡会展示给用户，等待确认即可，不要声称已打开。"
    override val permission = ToolPermission.EXTERNAL_READ
    override val write = false
    override val undoable = false

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().put("url", JSONObject().put("type", "string")))
        .put("required", org.json.JSONArray(listOf("url")))

    override suspend fun dispatch(args: JSONObject, ctx: ToolContext): ToolResult {
        val raw = args.str("url")
        val uri = Uri.parse(raw)
        if (uri.scheme !in listOf("http", "https")) {
            return ToolResult.error(ErrorCode.INVALID_ARGS, "仅支持 http/https 链接")
        }
        // 外发动作：走确认闸门（简单预览）
        val token = ctx.gate.newToken()
        val preview = com.freefile.tools.ai.model.WritePreview(
            token = token,
            toolName = name,
            title = "打开网页链接",
            items = listOf(
                com.freefile.tools.ai.model.PreviewItem(
                    path = raw, name = uri.host ?: raw, size = 0, isDirectory = false
                )
            ),
            destination = raw,
            destinationEditable = true,
            destinationLabel = "链接地址",
            conflicts = emptyList(),
            privileged = false,
            undoable = false
        )
        val approval = ctx.gate.request(preview) ?: return ToolResult.cancelledByUser()
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(approval.destination))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.appContext.startActivity(intent)
            ToolResult.ok(message = "已在浏览器打开：${approval.destination}")
        } catch (t: Throwable) {
            ToolResult.error(ErrorCode.IO_ERROR, "无法打开链接：${t.message}")
        }
    }
}
