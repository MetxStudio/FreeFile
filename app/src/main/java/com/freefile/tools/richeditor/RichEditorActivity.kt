package com.freefile.tools.richeditor

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.freefile.ui.theme.FreeFileTheme

/**
 * 富文本编辑器 Activity：基于 richeditor-compose 实现，支持 HTML / Markdown 导入导出。
 *
 * Intent extras:
 *  - [EXTRA_FILE_PATH] : String，要编辑的文件路径（必传）
 *
 * 行为：
 *  - .html / .htm 文件以 HTML 方式加载与保存（保留富文本格式）；
 *  - 其余文件以纯文本加载，保存时输出纯文本（无 HTML 标签）。
 */
class RichEditorActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: run {
            finish()
            return
        }
        setContent {
            FreeFileTheme {
                RichEditorScreen(
                    filePath = filePath,
                    onBack = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_FILE_PATH = "file_path"
    }
}
