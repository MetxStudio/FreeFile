package com.freefile.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.freefile.ui.tools.ToolTrayScreen
import com.freefile.ui.theme.FreeFileTheme

/**
 * 工具托盘 Activity：独立任务栈运行。
 *
 * Intent extra:
 *  - [EXTRA_PENDING_FILE_PATH] : String?，需要处理的文件路径（可选）
 */
class ToolTrayActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pendingFilePath = intent.getStringExtra(EXTRA_PENDING_FILE_PATH)
        setContent {
            FreeFileTheme {
                ToolTrayScreen(
                    pendingFilePath = pendingFilePath,
                    onBack = { finish() },
                    onMessage = { msg ->
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    companion object {
        const val EXTRA_PENDING_FILE_PATH = "pending_file_path"
    }
}
