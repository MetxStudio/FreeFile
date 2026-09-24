package com.freefile.share

import fi.iki.elonen.NanoHTTPD
import java.io.File

/**
 * 局域网快传服务端（Sprint 5）：NanoHTTPD。
 * - GET /            → 状态页（JSON）
 * - GET /files       → 接收目录文件列表（JSON）
 * - GET /download?n= → 下载接收目录中的文件
 * - POST /upload     → multipart 上传（保存到 /sdcard/FreeFileShare）
 */
class ShareServer(
    private val inboxDir: File,
    port: Int = DEFAULT_PORT
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        return try {
            when (session.method) {
                Method.POST -> when (uri) {
                    "/upload" -> handleUpload(session)
                    else -> json(404, "{\"ok\":false,\"error\":\"unknown endpoint\"}")
                }
                Method.GET -> when (uri) {
                    "/" -> json(
                        200,
                        "{\"app\":\"FreeFile\",\"share\":true,\"files\":${listInbox().size}}"
                    )
                    "/files" -> json(200, filesJson())
                    "/download" -> handleDownload(session)
                    else -> json(404, "{\"ok\":false,\"error\":\"unknown endpoint\"}")
                }
                else -> json(405, "{\"ok\":false,\"error\":\"method not allowed\"}")
            }
        } catch (e: Exception) {
            json(500, "{\"ok\":false,\"error\":\"${e.message?.replace("\"", "'") ?: "error"}\"}")
        }
    }

    /** multipart/form-data 上传（文件字段名 file）。 */
    private fun handleUpload(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        // parseBody 会把上传文件存到临时目录，键 → 临时文件路径
        session.parseBody(files)
        val tmpPath = files["file"]
            ?: session.parameters["file"]?.firstOrNull()
        if (tmpPath == null) return json(400, "{\"ok\":false,\"error\":\"no file\"}")

        val tmpFile = File(tmpPath)
        if (!tmpFile.exists()) return json(400, "{\"ok\":false,\"error\":\"upload missing\"}")

        // NanoHTTPD multipart 解析：files["file"] = 临时文件路径；
        // parameters["file"] = 原始文件名（Content-Disposition filename）
        val fileName = session.parameters["file"]?.firstOrNull()
            ?.takeIf { it.isNotBlank() && it != tmpPath }
            ?: "upload_${System.currentTimeMillis()}"

        inboxDir.mkdirs()
        var dest = File(inboxDir, sanitize(fileName))
        var i = 1
        while (dest.exists()) {
            val base = fileName.substringBeforeLast('.')
            val ext = fileName.substringAfterLast('.', "")
            dest = File(inboxDir, "$base($i)." + if (ext.isEmpty()) "bin" else ext)
            i++
        }
        tmpFile.renameTo(dest) ?: tmpFile.copyTo(dest, overwrite = true)
        tmpFile.delete()

        return json(
            200,
            "{\"ok\":true,\"name\":\"${dest.name.replace("\"", "'")}\",\"size\":${dest.length()}}"
        )
    }

    private fun handleDownload(session: IHTTPSession): Response {
        val name = session.parameters["n"]?.firstOrNull() ?: return json(400, "{\"ok\":false}")
        val f = File(inboxDir, sanitize(name))
        if (!f.exists() || !f.isFile) return json(404, "{\"ok\":false,\"error\":\"not found\"}")
        return newChunkedResponse(
            Response.Status.OK,
            "application/octet-stream",
            f.inputStream()
        ).apply {
            addHeader("Content-Disposition", "attachment; filename=\"${f.name}\"")
        }
    }

    private fun listInbox(): List<File> =
        inboxDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

    private fun filesJson(): String {
        val arr = listInbox().joinToString(",") { f ->
            "{\"name\":\"${f.name.replace("\"", "'")}\",\"size\":${f.length()}}"
        }
        return "{\"ok\":true,\"files\":[$arr]}"
    }

    private fun sanitize(name: String): String =
        name.replace("/", "_").replace("\\", "_").replace("..", "_").ifBlank { "upload.bin" }

    private fun json(code: Int, body: String): Response =
        newFixedLengthResponse(
            if (code == 200) Response.Status.OK
            else Response.Status.lookup(code) ?: Response.Status.INTERNAL_ERROR,
            "application/json; charset=utf-8",
            body
        )

    companion object {
        const val DEFAULT_PORT = 8765
    }
}
