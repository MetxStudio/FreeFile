package com.freefile.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.freefile.ui.editor.TextEditorScreen
import com.freefile.ui.theme.FreeFileTheme

/**
 * 文本编辑器 Activity：独立任务栈运行。
 *
 * Intent extra:
 *  - [EXTRA_FILE_PATH] : String，要编辑的文件路径
 */
class TextEditorActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: return
        setContent {
            FreeFileTheme {
                TextEditorScreen(
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
