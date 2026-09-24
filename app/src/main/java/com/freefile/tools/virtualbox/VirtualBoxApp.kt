package com.freefile.tools.virtualbox

import android.app.Application
import android.content.Context
// TODO: 将 Bcore-release.aar 与 black-reflection-release.aar 放入 app/libs/ 后取消注释
// import top.niunaijun.blackbox.BlackBoxCore
// import top.niunaijun.blackbox.client.ClientConfiguration

/**
 * VirtualBox Application：NewBlackbox 集成的关键入口。
 *
 * 必须在 [attachBaseContext] 中完成 BlackBox 初始化，在 [onCreate] 中创建虚拟环境。
 *
 * 注意：本项目主 Application 为 [com.freefile.FreeFileApp]，已通过继承本类
 * 确保 BlackBoxCore 与 FreeFile 自身的初始化都能执行（顺序：Application →
 * BlackBoxCore.doCreate → FreeFile 业务初始化）。
 *
 * 需要 app/libs/ 下存在 Bcore-release.aar 与 black-reflection-release.aar。
 */
open class VirtualBoxApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        try {
            // Step 1: 在 attachBaseContext 中初始化 BlackBox
            // TODO: AAR 就绪后取消注释
            // BlackBoxCore.get().doAttachBaseContext(base, object : ClientConfiguration() {
            //     override fun getHostPackageName(): String {
            //         return base.packageName
            //     }
            // })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Step 2: 在 onCreate 中创建虚拟环境
        try {
            // TODO: AAR 就绪后取消注释
            // BlackBoxCore.get().doCreate()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
