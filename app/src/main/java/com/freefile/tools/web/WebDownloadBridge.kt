package com.freefile.tools.web

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.widget.Toast
import com.freefile.R
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class WebDownloadStatus { RUNNING, SUCCESS, FAILED }

/** 浏览器下载任务（HTTP 与 blob/data 共用），用于页面内进度展示。 */
data class WebDownloadTask(
    val id: Long,
    val name: String,
    val bytes: Long = 0L,
    val total: Long = -1L,
    val status: WebDownloadStatus = WebDownloadStatus.RUNNING,
    val path: String = ""
) {
    /** null 表示总大小未知（不确定进度条）。 */
    val progress: Float?
        get() = if (total > 0L) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
}

/**
 * 全局下载任务中心：系统下载器与 JS 桥都向这里登记任务，
 * WebViewerScreen 订阅 [tasks] 显示实时进度。
 */
object WebDownloadCenter {
    private val seq = AtomicLong(0)
    private val _tasks = MutableStateFlow<List<WebDownloadTask>>(emptyList())
    val tasks: StateFlow<List<WebDownloadTask>> = _tasks.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun begin(name: String, total: Long = -1L): Long {
        val id = seq.incrementAndGet()
        _tasks.update { it + WebDownloadTask(id = id, name = name, total = total) }
        return id
    }

    fun progress(id: Long, bytes: Long, total: Long = -1L) {
        if (id == 0L) return
        _tasks.update { list ->
            list.map { t ->
                if (t.id != id) t
                else t.copy(bytes = bytes, total = if (total > 0L) total else t.total)
            }
        }
    }

    fun complete(id: Long, path: String) {
        if (id == 0L) return
        _tasks.update { list ->
            list.map { if (it.id == id) it.copy(status = WebDownloadStatus.SUCCESS, path = path) else it }
        }
        removeLater(id)
    }

    fun fail(id: Long) {
        if (id == 0L) return
        _tasks.update { list ->
            list.map { if (it.id == id) it.copy(status = WebDownloadStatus.FAILED) else it }
        }
        removeLater(id)
    }

    fun clear(id: Long) {
        _tasks.update { list -> list.filterNot { it.id == id } }
    }

    private fun removeLater(id: Long) {
        mainHandler.postDelayed({ clear(id) }, 5_000L)
    }

    /** 轮询系统下载器进度，直到成功/失败。 */
    fun trackDownloadManager(context: Context, downloadId: Long, taskId: Long) {
        val app = context.applicationContext
        scope.launch {
            while (isActive) {
                val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                if (dm == null) {
                    fail(taskId)
                    return@launch
                }
                var terminal = false
                try {
                    dm.query(DownloadManager.Query().setFilterById(downloadId))?.use { c ->
                        if (!c.moveToFirst()) {
                            fail(taskId)
                            terminal = true
                            return@use
                        }
                        val status = c.getInt(
                            c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                        )
                        val soFar = c.getLong(
                            c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        )
                        val total = c.getLong(
                            c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        )
                        progress(taskId, soFar, total)
                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                val uriCol = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                                complete(
                                    taskId,
                                    if (uriCol >= 0) c.getString(uriCol) ?: "" else ""
                                )
                                terminal = true
                            }
                            DownloadManager.STATUS_FAILED -> {
                                fail(taskId)
                                terminal = true
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // 查询失败时继续重试，避免瞬时异常直接判定失败
                }
                if (terminal) return@launch
                delay(500L)
            }
        }
    }
}

/**
 * 网页下载桥。
 *
 * WebView 的 DownloadListener 拿不到 blob:/data: 链接的真实内容（页面用
 * `URL.createObjectURL` + `<a download>` 生成文件时非常常见），因此注入
 * [WebDownloads.HOOK_JS] 截获这类点击，分块把内容经 JS 接口写入下载目录。
 *
 * JS 侧按块读取（默认 3MB），避免一次性 base64 大文件导致内存暴涨。
 */
class WebDownloadBridge(private val context: Context) {

    private val lock = Any()
    private var output: OutputStream? = null
    private var mediaUri: Uri? = null
    private var file: File? = null
    private var displayName: String = ""
    private var failed = false
    private var taskId = 0L
    private var written = 0L
    private var totalBytes = -1L

    @JavascriptInterface
    fun start(name: String, mime: String, size: Double): Boolean = synchronized(lock) {
        cleanup(delete = true)
        failed = false
        displayName = sanitize(name)
        written = 0L
        totalBytes = if (size > 0) size.toLong() else -1L
        taskId = WebDownloadCenter.begin(displayName, totalBytes)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE,
                        mime.ifBlank { "application/octet-stream" })
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS)
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = context.contentResolver
                    .insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("insert failed")
                mediaUri = uri
                output = context.contentResolver.openOutputStream(uri)
            } else {
                var dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (dir == null || !(dir.exists() || dir.mkdirs()) || !dir.canWrite()) {
                    dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                }
                if (dir == null) dir = context.filesDir
                val target = File(dir, uniqueName(dir, displayName))
                file = target
                output = FileOutputStream(target)
            }
            output != null
        } catch (_: Throwable) {
            cleanup(delete = true)
            false
        }
    }

    @JavascriptInterface
    fun append(base64: String): Boolean = synchronized(lock) {
        val out = output ?: return false
        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            out.write(bytes)
            written += bytes.size
            WebDownloadCenter.progress(taskId, written, totalBytes)
            true
        } catch (_: Throwable) {
            failed = true
            false
        }
    }

    @JavascriptInterface
    fun finish(): String = synchronized(lock) {
        val out = output
        output = null
        try {
            out?.flush()
            out?.close()
        } catch (_: Throwable) {
            failed = true
        }
        if (failed) {
            cleanup(delete = true)
            return@synchronized ""
        }

        val uri = mediaUri
        if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                context.contentResolver.update(
                    uri,
                    ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                    },
                    null, null
                )
            } catch (_: Throwable) {
            }
        }

        val path = when {
            uri != null -> "${Environment.DIRECTORY_DOWNLOADS}/$displayName"
            file != null -> file!!.absolutePath
            else -> displayName
        }
        mediaUri = null
        file = null
        val finishedTask = taskId
        taskId = 0L
        WebDownloadCenter.complete(finishedTask, path)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                context,
                context.getString(R.string.web_download_saved, path),
                Toast.LENGTH_LONG
            ).show()
        }
        path
    }

    @JavascriptInterface
    fun abort() {
        synchronized(lock) { cleanup(delete = true) }
    }

    private fun cleanup(delete: Boolean) {
        try {
            output?.close()
        } catch (_: Throwable) {
        }
        output = null

        val uri = mediaUri
        mediaUri = null
        if (delete && uri != null) {
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (_: Throwable) {
            }
        }

        val f = file
        file = null
        if (delete && f != null) {
            try {
                f.delete()
            } catch (_: Throwable) {
            }
        }
        val interrupted = taskId
        taskId = 0L
        if (delete && interrupted != 0L) WebDownloadCenter.fail(interrupted)
        failed = false
    }

    private fun sanitize(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').trim().ifBlank { "download" }

    private fun uniqueName(dir: File, name: String): String {
        var candidate = File(dir, name)
        if (!candidate.exists()) return name
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (candidate.exists()) {
            val n = if (ext.isNotEmpty()) "$base ($i).$ext" else "$base ($i)"
            candidate = File(dir, n)
            i++
        }
        return candidate.name
    }
}

/** 普通 http(s) 下载（交给系统下载器）与 blob 下载注入脚本。 */
object WebDownloads {

    /**
     * 注入到页面的下载钩子：拦截 `<a download href="blob:/data:">` 的点击，
     * 分块读取后交给 [WebDownloadBridge] 保存。
     */
    val HOOK_JS: String = """
        (function(){
          if(window.__ffDownloadHook)return;
          window.__ffDownloadHook=true;
          var CHUNK=3*1024*1024;
          function saveBlob(blob,name){
            if(!window.FreeFileDownload)return;
            if(!FreeFileDownload.start(name||'download',blob.type||'application/octet-stream',blob.size||0))return;
            var offset=0;
            function next(){
              if(offset>=blob.size){FreeFileDownload.finish();return;}
              var end=Math.min(offset+CHUNK,blob.size);
              var slice=blob.slice(offset,end);
              offset=end;
              var fr=new FileReader();
              fr.onload=function(){
                var s=String(fr.result||'');
                var i=s.indexOf(',');
                if(i>=0)s=s.slice(i+1);
                if(s&&!FreeFileDownload.append(s)){FreeFileDownload.abort();return;}
                next();
              };
              fr.onerror=function(){FreeFileDownload.abort();};
              fr.readAsDataURL(slice);
            }
            next();
          }
          window.__ffSaveBlobUrl=function(href,name){
            if(!window.FreeFileDownload)return;
            fetch(href).then(function(r){return r.blob();}).then(function(b){
              saveBlob(b,name||'download');
            }).catch(function(){});
          };
          document.addEventListener('click',function(e){
            if(!window.FreeFileDownload)return;
            var el=e.target;
            while(el&&el!==document){
              if(el.tagName==='A'&&el.hasAttribute&&el.hasAttribute('download'))break;
              el=el.parentNode;
            }
            if(!el||el===document||!el.tagName||el.tagName!=='A')return;
            var href=el.href||'';
            if(href.indexOf('blob:')!==0&&href.indexOf('data:')!==0)return;
            e.preventDefault();
            e.stopPropagation();
            var name=el.getAttribute('download')||'download';
            fetch(href).then(function(r){return r.blob();}).then(function(b){
              saveBlob(b,name);
            }).catch(function(){});
          },true);
        })();
    """.trimIndent()

    /** 用系统下载器下载 http(s)/content 链接；返回进度中心任务 id，失败返回 -1。 */
    fun enqueue(
        context: Context,
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String
    ): Long {
        if (!(url.startsWith("http://") || url.startsWith("https://") ||
                url.startsWith("content://"))
        ) return -1L

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return -1L
        val name = URLUtil.guessFileName(url, contentDisposition, mimeType)

        fun build(publicDir: Boolean): DownloadManager.Request =
            DownloadManager.Request(Uri.parse(url)).apply {
                if (mimeType.isNotBlank()) setMimeType(mimeType)
                if (userAgent.isNotBlank()) addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { addRequestHeader("Cookie", it) }
                setTitle(name)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                if (publicDir) {
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                } else {
                    setDestinationInExternalFilesDir(
                        context, Environment.DIRECTORY_DOWNLOADS, name
                    )
                }
            }

        val downloadId = try {
            manager.enqueue(build(true))
        } catch (_: Throwable) {
            try {
                manager.enqueue(build(false))
            } catch (_: Throwable) {
                return -1L
            }
        }
        val taskId = WebDownloadCenter.begin(name)
        WebDownloadCenter.trackDownloadManager(context, downloadId, taskId)
        return taskId
    }
}
