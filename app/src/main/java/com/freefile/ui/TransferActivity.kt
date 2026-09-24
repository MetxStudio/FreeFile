package com.freefile.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.freefile.ui.share.TransferScreen
import com.freefile.ui.theme.FreeFileTheme

/**
 * 快传 Activity：独立任务栈运行。
 */
class TransferActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FreeFileTheme {
                TransferScreen(
                    onBack = { finish() },
                    onMessage = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
                )
            }
        }
    }
}
