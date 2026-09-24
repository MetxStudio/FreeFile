package com.freefile.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.freefile.ui.script.ScriptScreen
import com.freefile.ui.theme.FreeFileTheme

/**
 * 脚本工具 Activity：独立任务栈运行。
 */
class ScriptActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FreeFileTheme {
                ScriptScreen(onBack = { finish() })
            }
        }
    }
}
