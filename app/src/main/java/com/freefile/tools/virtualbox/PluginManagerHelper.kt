package com.freefile.tools.virtualbox

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
// TODO: 将 Bcore-release.aar 与 black-reflection-release.aar 放入 app/libs/ 后取消注释
// import top.niunaijun.blackbox.BlackBoxCore
import java.io.File
import java.io.FileOutputStream

/**
 * 虚拟应用信息
 */
data class VirtualAppInfo(
    val label: String,
    val packageName: String,
    val apkPath: String?,
    val isInstalled: Boolean   // true = 设备上已安装，false = 仅 APK 文件
)

/**
 * 虚拟应用管理工具类
 *
 * 封装 NewBlackbox 的安装、启动、列表查询等 API。
 * AAR 就绪前所有 BlackBoxCore 调用被注释，方法返回 false/emptyList。
 */
object VirtualBoxHelper {

    private const val DEFAULT_USER_ID = 0

    /**
     * 从 SAF Uri 导入 APK 文件到内部存储
     * @return 复制后的 APK 文件路径，失败返回 null
     */
    fun importApkFromUri(context: Context, uri: Uri): File? {
        return try {
            val displayName = queryDisplayName(context, uri)
                ?: "virtual_app_${System.currentTimeMillis()}.apk"

            val destDir = File(context.filesDir, "virtual_apks")
            if (!destDir.exists()) destDir.mkdirs()

            val destFile = File(destDir, displayName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            destFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 通过 APK 文件路径安装虚拟应用（免安装运行的核心 API）
     * 对应 NewBlackbox: BlackBoxCore.get().installPackageAsUser(File, userId)
     */
    fun installFromApkFile(apkFile: File, userId: Int = DEFAULT_USER_ID): Boolean {
        return try {
            // TODO: AAR 就绪后取消注释
            // BlackBoxCore.get().installPackageAsUser(apkFile, userId)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 通过已安装应用的包名安装（设备上已存在的应用）
     */
    fun installFromPackageName(packageName: String, userId: Int = DEFAULT_USER_ID): Boolean {
        return try {
            // TODO: AAR 就绪后取消注释
            // BlackBoxCore.get().installPackageAsUser(packageName, userId)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 启动虚拟应用（免安装运行）
     * 对应 NewBlackbox: BlackBoxCore.get().launchApk(packageName, userId)
     */
    fun launchApp(packageName: String, userId: Int = DEFAULT_USER_ID): Boolean {
        return try {
            // TODO: AAR 就绪后取消注释
            // BlackBoxCore.get().launchApk(packageName, userId)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 获取 BlackBox 内已安装的虚拟应用列表
     */
    fun getInstalledVirtualApps(
        context: Context,
        userId: Int = DEFAULT_USER_ID
    ): List<VirtualAppInfo> {
        return try {
            // TODO: AAR 就绪后取消注释，删除 return@getInstalledVirtualApps emptyList()
            // val pm = context.packageManager
            // val packages = BlackBoxCore.get()
            //     .getInstalledPackages(PackageManager.GET_ACTIVITIES, userId)
            //
            // packages.mapNotNull { pkg ->
            //     val appInfo = pkg.applicationInfo ?: return@mapNotNull null
            //     VirtualAppInfo(
            //         label = runCatching { appInfo.loadLabel(pm).toString() }
            //             .getOrElse { pkg.packageName },
            //         packageName = pkg.packageName,
            //         apkPath = appInfo.sourceDir,
            //         isInstalled = true
            //     )
            // }
            emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * 从 APK 文件解析应用信息（不安装，仅用于展示）
     */
    fun parseApkInfo(context: Context, apkFile: File): VirtualAppInfo? {
        return try {
            val pm = context.packageManager
            val packageInfo = pm.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.GET_ACTIVITIES
            ) ?: return null

            val appInfo = packageInfo.applicationInfo ?: return null
            // 关键：通过档案解析的 ApplicationInfo 默认 sourceDir 为空，需手动设置
            appInfo.sourceDir = apkFile.absolutePath
            appInfo.publicSourceDir = apkFile.absolutePath

            VirtualAppInfo(
                label = pm.getApplicationLabel(appInfo).toString(),
                packageName = packageInfo.packageName,
                apkPath = apkFile.absolutePath,
                isInstalled = false
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 查询 SAF Uri 的显示名称
     */
    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }
    }
}
