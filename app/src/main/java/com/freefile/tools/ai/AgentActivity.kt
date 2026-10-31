package com.freefile.tools.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.freefile.tools.ai.ui.AgentScreen
import com.freefile.ui.theme.FreeFileTheme

/**
 * 文件管理 Agent 独立任务栈入口（侧拉栏 / 文件多选菜单 / 文件详情页跳入）。
 * exported=false，仅应用内路由；路由常量统一在 [AgentConstants]。
 */
class AgentActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val paths = intent.getStringArrayListExtra(AgentConstants.EXTRA_PATHS) ?: arrayListOf()
        val prompt = intent.getStringExtra(AgentConstants.EXTRA_PROMPT)

        setContent {
            FreeFileTheme {
                AgentScreen(
                    initialPaths = paths,
                    initialPrompt = prompt
                )
            }
        }
    }

    companion object {
        /** Manifest taskAffinity 使用的后缀。 */
        const val TASK_AFFINITY_SUFFIX = ".aiagent"
    }
}
