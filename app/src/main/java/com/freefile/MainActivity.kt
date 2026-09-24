package com.freefile

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import com.freefile.ui.MainScreen
import com.freefile.ui.theme.FreeFileTheme

/**
 * 主 Activity：使用 AppCompatActivity 以支持 per-app language（设置页切换语言即时生效）。
 *
 * 启动流程 Log.d 追踪：onCreate → setContent → MainScreen 首帧，便于排查冷/热启动问题。
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: start, savedInstanceState=${savedInstanceState != null}")
        setContent {
            FreeFileTheme {
                MainScreen()
            }
        }
        Log.d(TAG, "onCreate: setContent returned")
    }

    override fun onRestart() {
        super.onRestart()
        Log.d(TAG, "onRestart")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
