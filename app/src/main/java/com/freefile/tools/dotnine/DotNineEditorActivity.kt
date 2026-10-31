package com.freefile.tools.dotnine

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import com.freefile.ui.theme.FreeFileTheme

/**
 * 点九（NinePatch / .9.png）编辑器入口。
 *
 * Intent extras：
 *  - [EXTRA_FILE_PATH] : String  → 本地图片路径（png / .9.png）
 *  - ACTION_VIEW(data)          → 其他应用"打开方式"传入的 file:///content://
 *
 * launchMode=singleTop：再次传入新文件时走 [onNewIntent] 更新界面。
 */
class DotNineEditorActivity : AppCompatActivity() {

    private val filePathState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            FreeFileTheme {
                DotNineEditorScreen(
                    filePath = filePathState.value,
                    onBack = { finish() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val path = intent.getStringExtra(EXTRA_FILE_PATH)
            ?: intent.data?.let { uri ->
                when (uri.scheme) {
                    "file" -> uri.path
                    "content" -> null // 由 screen 层处理 content uri
                    else -> uri.toString()
                }
            }
        filePathState.value = path
    }

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
    }
}
