package com.freefile.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.freefile.ui.terminal.TerminalScreen
import com.freefile.ui.theme.FreeFileTheme

/**
 * 终端 Activity：独立任务栈运行，避免与主界面共享返回栈。
 *
 * Intent extra:
 *  - [EXTRA_INITIAL_PATHS] : ArrayList<String>，需要在终端中打开的路径列表（可选）
 */
class TerminalActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialPaths = intent.getStringArrayListExtra(EXTRA_INITIAL_PATHS) ?: emptyList()
        setContent {
            FreeFileTheme {
                TerminalScreen(
                    initialPaths = initialPaths,
                    onBack = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_INITIAL_PATHS = "initial_paths"
    }
}
