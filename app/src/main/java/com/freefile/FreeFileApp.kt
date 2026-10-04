package com.freefile

import android.util.Log
import com.freefile.core.permission.PermissionManager
import com.freefile.data.repo.Repository
import com.freefile.data.settings.AppSettings
import com.freefile.search.SearchEngine
import com.freefile.tools.virtualbox.VirtualBoxApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口：承载全局单例（Repository、PermissionManager、PluginManager）。
 *
 * 继承 [VirtualBoxApp]：使 NewBlackbox（BlackBoxCore）在 attachBaseContext / onCreate
 * 阶段完成虚拟环境初始化；FreeFile 自身的初始化在父类 onCreate 之后执行，互不干扰。
 *
 * 关键初始化在 onCreate 同步完成，避免子模块首次访问时未就绪导致冷启动崩溃。
 */
class FreeFileApp : VirtualBoxApp() {

    lateinit var repository: Repository
        private set
    lateinit var permissionManager: PermissionManager
        private set

    /** 应用级主线程作用域（生命周期与进程一致），供后台任务完成回调安全地更新 UI，
     *  避免依赖 Compose 组合 scope 在异步回调时已被取消而抛出 StandaloneCoroutine cancelled。 */
    val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: start")
        instance = this
        // 必须在任何使用 AppSettings.state 的组件（如 FileViewModel）初始化前完成
        AppSettings.init(this)
        Log.d(TAG, "onCreate: AppSettings ready")
        repository = Repository(this)
        Log.d(TAG, "onCreate: Repository ready")
        permissionManager = PermissionManager(this)
        Log.d(TAG, "onCreate: PermissionManager ready, init done")
        // 恢复搜索索引就绪状态（Room 中已有数据则标记为 Ready，避免重启后显示未就绪）
        CoroutineScope(Dispatchers.IO).launch { SearchEngine.restoreIndexState(this@FreeFileApp) }
    }

    companion object {
        @Volatile
        lateinit var instance: FreeFileApp
            private set

        private const val TAG = "FreeFileApp"
    }
}
