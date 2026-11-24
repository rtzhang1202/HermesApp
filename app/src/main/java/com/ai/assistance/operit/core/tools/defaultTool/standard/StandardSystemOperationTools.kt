package com.ai.assistance.operit.core.tools.defaultTool.standard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.widget.Toast
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.app.NotificationCompat
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.core.tools.AppListData
import com.ai.assistance.operit.core.tools.AppOperationData
import com.ai.assistance.operit.core.tools.AppUsageTimeEntry
import com.ai.assistance.operit.core.tools.AppUsageTimeResultData
import com.ai.assistance.operit.core.tools.LocationData
import com.ai.assistance.operit.core.tools.NotificationData
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.SystemSettingData
import com.ai.assistance.operit.core.tools.system.AndroidShellExecutor
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.app.ActivityManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import java.util.Calendar
import androidx.core.content.FileProvider
import java.io.File
import com.ai.assistance.operit.services.notification.OperitNotificationStore
import com.ai.assistance.operit.R
import com.ai.assistance.operit.util.AndroidUserPathUtils
import com.ai.assistance.operit.util.OperitPaths

/** 提供系统级操作的工具类 包括系统设置修改、应用安装和卸载等 这些操作需要用户明确授权 */
open class StandardSystemOperationTools(private val context: Context) {

    companion object {
        private const val TAG = "SystemOperationTools"
        private val OPERIT_PACKAGE = com.ai.assistance.operit.BuildConfig.APPLICATION_ID

        private const val AI_REPLY_CHANNEL_ID = "AI_REPLY_CHANNEL"
        private const val AI_REPLY_CHANNEL_NAME = "Chat Completion Reminder"
    }

    private fun isOperitInternalPath(path: String): Boolean {
        val normalizedPath = path.trim()
        return normalizedPath.startsWith("/data/data/$OPERIT_PACKAGE") ||
            AndroidUserPathUtils.isCurrentUserPackageDataPath(normalizedPath, OPERIT_PACKAGE)
    }

    private fun stageApkForInstallIfNeeded(apkFile: File): File {
        val apkPath = apkFile.absolutePath
        if (!isOperitInternalPath(apkPath)) {
            return apkFile
        }

        val cleanOnExitDir = OperitPaths.cleanOnExitDir()
        if (!cleanOnExitDir.exists() && !cleanOnExitDir.mkdirs()) {
            throw IllegalStateException("Cannot create cleanOnExit dir: ${cleanOnExitDir.absolutePath}")
        }

        val stagedFileName = "install_${System.currentTimeMillis()}_${apkFile.name}"
        val stagedFile = File(cleanOnExitDir, stagedFileName)
        apkFile.copyTo(stagedFile, overwrite = true)
        AppLogger.d(TAG, "Staged internal apk for installer: $apkPath -> ${stagedFile.absolutePath}")
        return stagedFile
    }

    private fun hasUsageStatsAccess(): Boolean {
        val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOpsManager.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOpsManager.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }

        return mode == AppOpsManager.MODE_ALLOWED
    }

    open suspend fun toast(tool: AITool): ToolResult {
        val message = tool.parameters.find { it.name == "message" }?.value
        if (message.isNullOrBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Must provide message parameter"
            )
        }

        return try {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
            }
            ToolResult(toolName = tool.name, success = true, result = StringResultData("OK"))
        } catch (e: Exception) {
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Toast failed: ${e.message}"
            )
        }
    }

    open suspend fun sendNotification(tool: AITool): ToolResult {
        val title = tool.parameters.find { it.name == "title" }?.value?.takeIf { it.isNotBlank() } ?: "Notification"
        val message = tool.parameters.find { it.name == "message" }?.value
        if (message.isNullOrBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Must provide message parameter"
            )
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = NotificationChannel(
                    AI_REPLY_CHANNEL_ID,
                    AI_REPLY_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                )
                manager.createNotificationChannel(channel)
            }

            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pendingIntent = if (launchIntent != null) {
                PendingIntent.getActivity(
                    context,
                    0,
                    launchIntent,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }
                )
            } else {
                null
            }

            val builder =
                NotificationCompat.Builder(context, AI_REPLY_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(message.take(100))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setAutoCancel(true)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))

            if (pendingIntent != null) {
                builder.setContentIntent(pendingIntent)
            }

            val notification = builder.build()
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val id = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
            manager.notify(id, notification)

            ToolResult(toolName = tool.name, success = true, result = StringResultData("OK"))
        } catch (e: SecurityException) {
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Failed to send notification (missing permission): ${e.message}"
            )
        } catch (e: Exception) {
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Failed to send notification: ${e.message}"
            )
        }
    }

    /** 修改系统设置 支持修改各种系统设置，如音量、亮度等 */
    open suspend fun modifySystemSetting(tool: AITool): ToolResult {
        val setting = tool.parameters.find { it.name == "setting" }?.value ?: ""
        val value = tool.parameters.find { it.name == "value" }?.value ?: ""
        val namespace = tool.parameters.find { it.name == "namespace" }?.value ?: "system"

        if (setting.isBlank() || value.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Must provide setting and value parameters"
            )
        }

        val validNamespaces = listOf("system", "secure", "global")
        if (!validNamespaces.contains(namespace)) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Namespace must be one of: ${validNamespaces.joinToString(", ")}"
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
            // 自动打开系统设置页面引导用户授权
            try {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "No permission to modify system settings. Settings page opened for you, please grant WRITE_SETTINGS permission and retry."
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "打开设置页面失败", e)
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "No permission to modify system settings and cannot open settings page: ${e.message}"
                )
            }
        }

        return try {
            when (namespace) {
                "system" -> Settings.System.putString(context.contentResolver, setting, value)
                "secure" -> Settings.Secure.putString(context.contentResolver, setting, value)
                "global" -> Settings.Global.putString(context.contentResolver, setting, value)
            }

            // When modifying screen_brightness, automatically disable auto-brightness
            // first so the manual value takes visible effect.  Then notify the system
            // ContentObserver so the display subsystem re-reads the value immediately.
            if (namespace == "system" && setting == "screen_brightness") {
                try {
                    // Disable auto-brightness if it is currently on
                    val currentMode = Settings.System.getInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                    )
                    if (currentMode != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                        Settings.System.putInt(
                            context.contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                        )
                    }
                    // Notify observers to force an immediate display refresh
                    val brightnessUri = Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)
                    context.contentResolver.notifyChange(brightnessUri, null)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to force brightness refresh: ${e.message}")
                }
            }
            if (namespace == "system" && setting == "screen_brightness_mode") {
                try {
                    val modeUri = Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE)
                    context.contentResolver.notifyChange(modeUri, null)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to notify brightness mode change: ${e.message}")
                }
            }

            val resultData = SystemSettingData(namespace = namespace, setting = setting, value = value)
            ToolResult(toolName = tool.name, success = true, result = resultData, error = "")
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "修改系统设置时出错", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Security exception when modifying system settings: ${e.message}. This may require higher permissions."
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "修改系统设置时出错", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Error modifying system settings: ${e.message}"
            )
        }
    }

    /** 获取系统设置的当前值 */
    open suspend fun getSystemSetting(tool: AITool): ToolResult {
        val setting = tool.parameters.find { it.name == "setting" }?.value ?: ""
        val namespace = tool.parameters.find { it.name == "namespace" }?.value ?: "system"

        if (setting.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Must provide setting parameter"
            )
        }

        val validNamespaces = listOf("system", "secure", "global")
        if (!validNamespaces.contains(namespace)) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Namespace must be one of: ${validNamespaces.joinToString(", ")}"
            )
        }

        return try {
            val value = when (namespace) {
                "system" -> Settings.System.getString(context.contentResolver, setting)
                "secure" -> Settings.Secure.getString(context.contentResolver, setting)
                "global" -> Settings.Global.getString(context.contentResolver, setting)
                else -> null
            }

            if (value != null) {
                val resultData = SystemSettingData(namespace = namespace, setting = setting, value = value)
                ToolResult(toolName = tool.name, success = true, result = resultData, error = "")
            } else {
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Failed to get setting: setting '$setting' not found in namespace '$namespace'."
                )
            }
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "获取系统设置时出错", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Security exception when getting system settings: ${e.message}. This may require higher permissions."
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "获取系统设置时出错", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Error getting system settings: ${e.message}"
            )
        }
    }

    /** 安装应用程序 需要APK文件的路径 */
    open suspend fun installApp(tool: AITool): ToolResult {
        val apkPath = tool.parameters.find { it.name == "path" }?.value ?: ""

        if (apkPath.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = context.getString(R.string.sys_op_must_provide_apk_path)
            )
        }

        val file = File(apkPath)
        if (!file.exists()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "APK file does not exist: $apkPath"
            )
        }

        return try {
            val installFile = stageApkForInstallIfNeeded(file)
            val apkUri =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        installFile
                    )
                } else {
                    Uri.fromFile(installFile)
                }

            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)

            val resultData = AppOperationData(
                operationType = "install_request",
                packageName = apkPath,
                success = true,
                details = context.getString(R.string.sys_op_install_request_sent)
            )
            ToolResult(toolName = tool.name, success = true, result = resultData, error = "")
        } catch (e: Exception) {
            AppLogger.e(TAG, "请求安装应用时出错", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Error requesting app installation: ${e.message}"
            )
        }
    }

    /** 卸载应用程序 需要提供包名 */
    open suspend fun uninstallApp(tool: AITool): ToolResult {
        val packageName = tool.parameters.find { it.name == "package_name" }?.value ?: ""

        if (packageName.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Must provide package_name parameter"
            )
        }

        try {
            context.packageManager.getPackageInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "App not installed: $packageName"
            )
        }

        return try {
            val intent = Intent(Intent.ACTION_DELETE)
            intent.data = Uri.parse("package:$packageName")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            val resultData = AppOperationData(
                operationType = "uninstall_request",
                packageName = packageName,
                success = true,
                details = context.getString(R.string.sys_op_uninstall_request_sent)
            )
            ToolResult(toolName = tool.name, success = true, result = resultData, error = "")
        } catch (e: Exception) {
            AppLogger.e(TAG, "请求卸载应用时出错", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Error requesting app uninstallation: ${e.message}"
            )
        }
    }

    /** 获取已安装的应用列表 */
    suspend fun listInstalledApps(tool: AITool): ToolResult {
        val includeSystemApps =
                tool.parameters.find { it.name == "include_system_apps" }?.value?.toBoolean()
                        ?: false
        return try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val appDetails = mutableListOf<String>()

            apps.forEach { appInfo ->
                val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (includeSystemApps || !isSystemApp) {
                    val packageName = appInfo.packageName
                    val appName =
                            try {
                                appInfo.loadLabel(pm).toString()
                            } catch (e: Exception) {
                                AppLogger.w(
                                        TAG,
                                        "Failed to load application label for $packageName",
                                        e
                                )
                                packageName
                            }
                    appDetails.add("$appName ($packageName)")
                }
            }

            val sortedAppDetails = appDetails.sorted()
            val resultData = AppListData(
                includesSystemApps = includeSystemApps, 
                packages = sortedAppDetails
            )
            
            ToolResult(toolName = tool.name, success = true, result = resultData)
        } catch (e: Exception) {
            AppLogger.e(TAG, "获取已安装应用列表时出错", e)
            ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Failed to get app list: ${e.message}"
            )
        }
    }

    /** 启动应用程序 如果提供了activity参数，将启动指定的活动 否则使用默认启动器启动应用 */
    open suspend fun startApp(tool: AITool): ToolResult {
        val packageName = tool.parameters.find { it.name == "package_name" }?.value ?: ""
        val activityName = tool.parameters.find { it.name == "activity" }?.value ?: ""

        if (packageName.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Must provide package_name parameter"
            )
        }

        return try {
            val intent: Intent? = if (activityName.isBlank()) {
                context.packageManager.getLaunchIntentForPackage(packageName)
            } else {
                Intent(Intent.ACTION_MAIN).also {
                    it.addCategory(Intent.CATEGORY_LAUNCHER)
                    it.component = ComponentName(packageName, activityName)
                }
            }

            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                val details = if (activityName.isNotBlank()) "Activity: $activityName" else ""
                val resultData = AppOperationData(
                    operationType = "start",
                    packageName = packageName,
                    success = true,
                    details = details
                )
                ToolResult(toolName = tool.name, success = true, result = resultData, error = "")
            } else {
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Failed to start app: Cannot find app launch Intent. Please check package name or Activity name."
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "启动应用时出错", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Error starting app: ${e.message}"
            )
        }
    }

    /** 停止应用程序 */
    open suspend fun stopApp(tool: AITool): ToolResult {
        val packageName = tool.parameters.find { it.name == "package_name" }?.value ?: ""

        if (packageName.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Must provide package_name parameter"
            )
        }

        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.killBackgroundProcesses(packageName)
            val resultData = AppOperationData(
                operationType = "stop",
                packageName = packageName,
                success = true,
                details = context.getString(R.string.sys_op_stop_app_requested)
            )
            ToolResult(toolName = tool.name, success = true, result = resultData, error = "")
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "停止应用时出现安全异常", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Failed to stop app: ${e.message}. Requires KILL_BACKGROUND_PROCESSES permission."
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "停止应用时出错", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Error stopping app: ${e.message}"
            )
        }
    }

    /** 读取设备通知内容 获取当前设备上的通知信息 */
    open suspend fun getNotifications(tool: AITool): ToolResult {
        val limit = tool.parameters.find { it.name == "limit" }?.value?.toIntOrNull() ?: 10
        val includeOngoing =
            tool.parameters.find { it.name == "include_ongoing" }?.value?.toBoolean() ?: false

        val enabledListeners =
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?: ""
        val myPackageName = context.packageName

        val hasNotificationAccess = enabledListeners
            .split(":")
            .asSequence()
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any { it.packageName == myPackageName }

        if (!hasNotificationAccess) {
            try {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                AppLogger.e(TAG, "打开通知使用权设置页面失败", e)
            }

            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Cannot read notifications. This app needs to be authorized as a Notification Listener Service."
            )
        }

        return try {
            val notifications = OperitNotificationStore.snapshot(
                limit = limit,
                includeOngoing = includeOngoing
            )
            val resultData = NotificationData(
                notifications = notifications,
                timestamp = System.currentTimeMillis()
            )
            ToolResult(toolName = tool.name, success = true, result = resultData, error = "")
        } catch (e: Exception) {
            AppLogger.e(TAG, "获取通知时出错", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Error getting notifications: ${e.message}"
            )
        }
    }

    /** 获取应用使用时长（前台使用时间） */
    open suspend fun getAppUsageTime(tool: AITool): ToolResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "App usage time is only supported on Android 5.0 and above."
            )
        }

        val requestedPackageName =
            tool.parameters.find { it.name == "package_name" }?.value?.trim().orEmpty()
        val sinceHours = tool.parameters.find { it.name == "since_hours" }?.value?.toIntOrNull() ?: 24
        val limit = tool.parameters.find { it.name == "limit" }?.value?.toIntOrNull() ?: 10
        val includeSystemApps =
            tool.parameters.find { it.name == "include_system_apps" }?.value?.toBoolean() ?: false

        if (sinceHours <= 0) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "since_hours must be a positive integer."
            )
        }

        if (limit <= 0) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "limit must be a positive integer."
            )
        }

        if (!hasUsageStatsAccess()) {
            try {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                AppLogger.e(TAG, "打开使用情况访问设置页面失败", e)
            }

            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Cannot read app usage time. This app needs Usage Access permission. Settings page opened for you."
            )
        }

        return try {
            val endTime = System.currentTimeMillis()
            val startTime = endTime - sinceHours * 60L * 60L * 1000L
            val usageStatsManager =
                context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val rawStats =
                usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    startTime,
                    endTime
                ) ?: emptyList()

            val aggregatedStats =
                rawStats
                    .groupBy { it.packageName.orEmpty() }
                    .mapNotNull { (packageName, stats) ->
                        if (packageName.isBlank()) {
                            return@mapNotNull null
                        }

                        val totalForegroundTimeMs = stats.sumOf { it.totalTimeInForeground }
                        if (totalForegroundTimeMs <= 0L) {
                            return@mapNotNull null
                        }

                        val lastTimeUsed = stats.maxOfOrNull { it.lastTimeUsed } ?: 0L
                        val applicationInfo =
                            try {
                                context.packageManager.getApplicationInfo(packageName, 0)
                            } catch (_: PackageManager.NameNotFoundException) {
                                null
                            }
                        val isSystemApp =
                            applicationInfo?.let {
                                (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                            } ?: false

                        if (requestedPackageName.isBlank() && !includeSystemApps && isSystemApp) {
                            return@mapNotNull null
                        }

                        if (requestedPackageName.isNotBlank() && requestedPackageName != packageName) {
                            return@mapNotNull null
                        }

                        val appName =
                            if (applicationInfo != null) {
                                try {
                                    applicationInfo.loadLabel(context.packageManager).toString()
                                } catch (_: Exception) {
                                    packageName
                                }
                            } else {
                                packageName
                            }

                        AppUsageTimeEntry(
                            packageName = packageName,
                            appName = appName,
                            totalForegroundTimeMs = totalForegroundTimeMs,
                            lastTimeUsed = lastTimeUsed,
                            isSystemApp = isSystemApp
                        )
                    }
                    .sortedByDescending { it.totalForegroundTimeMs }

            val limitedEntries =
                if (requestedPackageName.isBlank()) aggregatedStats.take(limit) else aggregatedStats.take(1)

            val resultData =
                AppUsageTimeResultData(
                    startTime = startTime,
                    endTime = endTime,
                    sinceHours = sinceHours,
                    requestedPackageName = requestedPackageName.ifBlank { null },
                    includesSystemApps = includeSystemApps,
                    totalEntries = limitedEntries.size,
                    entries = limitedEntries
                )

            ToolResult(toolName = tool.name, success = true, result = resultData, error = "")
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "读取应用使用时长时出现权限异常", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Security exception when reading app usage time: ${e.message}"
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "读取应用使用时长时出错", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Error getting app usage time: ${e.message}"
            )
        }
    }

    // ============ 剪贴板（get / set） ============
    open suspend fun controlClipboard(tool: AITool): ToolResult {
        val action = tool.parameters.find { it.name == "action" }?.value?.trim()?.lowercase(Locale.ROOT) ?: "get"
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Clipboard service unavailable"
            )
        }
        return when (action) {
            "get" -> {
                val clip = clipboard.primaryClip
                if (clip == null || clip.itemCount == 0) {
                    ToolResult(
                        toolName = tool.name,
                        success = true,
                        result = StringResultData("clipboard is empty"),
                        error = ""
                    )
                } else {
                    val parts = (0 until clip.itemCount).mapNotNull { i ->
                        clip.getItemAt(i).coerceToText(context)?.toString()
                    }
                    ToolResult(
                        toolName = tool.name,
                        success = true,
                        result = StringResultData("clipboard (${clip.itemCount} item(s)): " + parts.joinToString(" | ").take(2000)),
                        error = ""
                    )
                }
            }

            "set" -> {
                val text = tool.parameters.find { it.name == "text" }?.value
                if (text.isNullOrEmpty()) {
                    ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = "action=set requires text"
                    )
                } else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("hermes", text))
                    ToolResult(
                        toolName = tool.name,
                        success = true,
                        result = StringResultData("clipboard set (${text.length} chars)"),
                        error = ""
                    )
                }
            }

            else -> ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Unknown action '$action'. Use get / set."
            )
        }
    }

    // ============ 录屏（root: screenrecord） ============
    open suspend fun controlScreenRecord(tool: AITool): ToolResult {
        val action = tool.parameters.find { it.name == "action" }?.value?.trim()?.lowercase(Locale.ROOT) ?: "start"
        return when (action) {
            "start" -> {
                val seconds = (tool.parameters.find { it.name == "seconds" }?.value?.toIntOrNull() ?: 10).coerceIn(5, 60)
                val path = "/sdcard/Download/hermes_rec_${System.currentTimeMillis() / 1000}.mp4"
                try {
                    val process = ProcessBuilder("su", "-c", "screenrecord --time-limit $seconds $path")
                        .redirectErrorStream(true)
                        .start()
                    // 同步等待录制完成（IO 线程执行，最长 60s）
                    process.waitFor(seconds + 5L, java.util.concurrent.TimeUnit.SECONDS)
                    val output = process.inputStream.bufferedReader().readText().take(500)
                    process.destroy()
                    val file = java.io.File(path)
                    if (file.exists() && file.length() > 0) {
                        ToolResult(
                            toolName = tool.name,
                            success = true,
                            result = StringResultData("recorded ${seconds}s -> $path (${file.length() / 1024} KB)"),
                            error = ""
                        )
                    } else {
                        ToolResult(
                            toolName = tool.name,
                            success = false,
                            result = StringResultData(""),
                            error = "screenrecord failed (root required?): $output"
                        )
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "录屏失败", e)
                    ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = "screen_record requires root (su screenrecord): ${e.message}"
                    )
                }
            }

            "stop" -> {
                try {
                    val process = ProcessBuilder("su", "-c", "pkill -INT screenrecord")
                        .redirectErrorStream(true)
                        .start()
                    process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                    process.destroy()
                    ToolResult(
                        toolName = tool.name,
                        success = true,
                        result = StringResultData("stop signal sent to screenrecord"),
                        error = ""
                    )
                } catch (e: Exception) {
                    ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = "stop failed: ${e.message}"
                    )
                }
            }

            else -> ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Unknown action '$action'. Use start / stop."
            )
        }
    }

    // ============ ContentProvider 查询（content query） ============
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    open suspend fun queryContentProvider(tool: AITool): ToolResult {
        val uri = tool.parameters.find { it.name == "uri" }?.value?.trim()
        if (uri.isNullOrEmpty() || !uri.startsWith("content://")) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "uri is required and must start with content://"
            )
        }
        val selection = tool.parameters.find { it.name == "selection" }?.value?.trim()?.takeIf { it.isNotEmpty() }
        val limit = (tool.parameters.find { it.name == "limit" }?.value?.toIntOrNull() ?: 30).coerceIn(1, 100)

        fun buildCmd(prefix: String): String =
            prefix + "content query --uri " + shellQuote(uri) +
                (selection?.let { " --where " + shellQuote(it) } ?: "")

        return try {
            // 优先 root（可查受限 provider），失败回退普通 shell
            var output: String
            var proc = ProcessBuilder("su", "-c", buildCmd(""))
                .redirectErrorStream(true)
                .start()
            proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            output = proc.inputStream.bufferedReader().readText()
            proc.destroy()
            if (output.isBlank() || output.contains("Permission Denial") || output.contains("Error")) {
                proc = ProcessBuilder("sh", "-c", buildCmd(""))
                    .redirectErrorStream(true)
                    .start()
                proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
                output = proc.inputStream.bufferedReader().readText()
                proc.destroy()
            }
            if (output.isBlank()) {
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "content query returned no output (provider may not exist or is not queryable)"
                )
            } else {
                val allLines = output.trim().lines()
                val shown = allLines.take(limit).joinToString("\n") { it.take(300) }
                val more = if (allLines.size > limit) "\n... (${allLines.size - limit} more rows truncated)" else ""
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData(shown + more),
                    error = ""
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "content query 失败", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "content query failed: ${e.message}"
            )
        }
    }

    // ============ 发短信（打开编辑器，Android 无静默发送 API） ============
    open suspend fun sendSms(tool: AITool): ToolResult {
        val to = tool.parameters.find { it.name == "to" }?.value?.trim()
        val message = tool.parameters.find { it.name == "message" }?.value
        if (to.isNullOrEmpty() || message.isNullOrEmpty()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "send_sms requires to and message"
            )
        }
        return try {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$to"))
                .putExtra("sms_body", message)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolResult(
                toolName = tool.name,
                success = true,
                result = StringResultData("SMS composer opened for $to (${message.length} chars). Android has no silent-send API - confirm send in the Messages app."),
                error = ""
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "打开短信编辑器失败", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "send_sms failed: ${e.message}"
            )
        }
    }

    // ============ 闹钟工具（set / show / dismiss） ============
    open suspend fun controlAlarm(tool: AITool): ToolResult {
        val action = tool.parameters.find { it.name == "action" }?.value?.trim()?.lowercase(Locale.ROOT) ?: "set"
        return try {
            when (action) {
                "set" -> {
                    val hour = tool.parameters.find { it.name == "hour" }?.value?.toIntOrNull()
                    val minute = tool.parameters.find { it.name == "minute" }?.value?.toIntOrNull() ?: 0
                    if (hour == null || hour !in 0..23 || minute !in 0..59) {
                        return ToolResult(
                            toolName = tool.name,
                            success = false,
                            result = StringResultData(""),
                            error = "action=set requires hour (0-23) and minute (0-59)"
                        )
                    }
                    val label = tool.parameters.find { it.name == "label" }?.value?.takeIf { it.isNotBlank() } ?: "Hermes"
                    val skipUi = tool.parameters.find { it.name == "skip_ui" }?.value != "false"
                    val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, hour)
                        .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
                    val dayMap = mapOf(
                        "sun" to Calendar.SUNDAY,
                        "mon" to Calendar.MONDAY,
                        "tue" to Calendar.TUESDAY,
                        "wed" to Calendar.WEDNESDAY,
                        "thu" to Calendar.THURSDAY,
                        "fri" to Calendar.FRIDAY,
                        "sat" to Calendar.SATURDAY
                    )
                    val dayList = arrayListOf<Int>()
                    tool.parameters.find { it.name == "days" }?.value
                        ?.split(",")
                        ?.forEach { token -> dayMap[token.trim().lowercase(Locale.ROOT)]?.let { dayList.add(it) } }
                    if (dayList.isNotEmpty()) {
                        intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, dayList)
                    }
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    val timeText = String.format(Locale.ROOT, "%02d:%02d", hour, minute)
                    val repeatText = if (dayList.isNotEmpty()) "repeat ${dayList.size} day(s)/week" else "one-shot"
                    ToolResult(
                        toolName = tool.name,
                        success = true,
                        result = StringResultData("Alarm set: $label at $timeText ($repeatText)"),
                        error = ""
                    )
                }

                "show" -> {
                    val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    ToolResult(
                        toolName = tool.name,
                        success = true,
                        result = StringResultData("Opened the system alarm list"),
                        error = ""
                    )
                }

                "dismiss" -> {
                    val label = tool.parameters.find { it.name == "label" }?.value?.takeIf { it.isNotBlank() }
                    val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM)
                    if (label != null) {
                        intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
                        intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    } else {
                        intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
                    }
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    ToolResult(
                        toolName = tool.name,
                        success = true,
                        result = StringResultData(if (label != null) "Dismissed alarm: $label" else "Dismissed the next alarm"),
                        error = ""
                    )
                }

                else -> ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Unknown action '$action'. Use set / show / dismiss."
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "闹钟操作失败", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Alarm operation failed: ${e.message}"
            )
        }
    }

    // ============ 日历工具（query / create / delete） ============
    private fun hasPermission(perm: String): Boolean =
        context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    // 已 root 设备：pm grant 直接拿运行时权限，免去设置页手工授权
    private fun tryGrantPermission(perm: String) {
        if (hasPermission(perm)) return
        try {
            val process = ProcessBuilder("su", "-c", "pm grant $OPERIT_PACKAGE $perm")
                .redirectErrorStream(true)
                .start()
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            process.destroy()
        } catch (e: Exception) {
            AppLogger.e(TAG, "pm grant 失败: $perm", e)
        }
    }

    open suspend fun controlCalendar(tool: AITool): ToolResult {
        val action = tool.parameters.find { it.name == "action" }?.value?.trim()?.lowercase(Locale.ROOT) ?: "query"
        return try {
            when (action) {
                "query" -> {
                    if (!hasPermission(android.Manifest.permission.READ_CALENDAR)) {
                        tryGrantPermission(android.Manifest.permission.READ_CALENDAR)
                    }
                    if (!hasPermission(android.Manifest.permission.READ_CALENDAR)) {
                        return ToolResult(
                            toolName = tool.name,
                            success = false,
                            result = StringResultData(""),
                            error = "READ_CALENDAR permission not granted. Grant it in Settings -> Apps -> Hermes -> Permissions."
                        )
                    }
                    val days = tool.parameters.find { it.name == "days_from_now" }?.value?.toIntOrNull() ?: 7
                    val begin = System.currentTimeMillis()
                    val end = begin + days.coerceIn(1, 365).toLong() * 86_400_000L
                    val projection = arrayOf(
                        CalendarContract.Instances.EVENT_ID,
                        CalendarContract.Instances.TITLE,
                        CalendarContract.Instances.BEGIN,
                        CalendarContract.Instances.END,
                        CalendarContract.Instances.EVENT_LOCATION,
                        CalendarContract.Instances.CALENDAR_DISPLAY_NAME
                    )
                    val lines = mutableListOf<String>()
                    val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    CalendarContract.Instances.query(context.contentResolver, projection, begin, end)?.use { cursor ->
                        val idIdx = cursor.getColumnIndex(CalendarContract.Instances.EVENT_ID)
                        val titleIdx = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                        val beginIdx = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                        val endIdx = cursor.getColumnIndex(CalendarContract.Instances.END)
                        val locIdx = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
                        val calIdx = cursor.getColumnIndex(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
                        while (cursor.moveToNext() && lines.size < 50) {
                            val id = cursor.getLong(idIdx)
                            val b = cursor.getLong(beginIdx)
                            val e = cursor.getLong(endIdx)
                            val title = cursor.getString(titleIdx) ?: "(untitled)"
                            val loc = cursor.getString(locIdx)?.takeIf { it.isNotBlank() }
                            val cal = cursor.getString(calIdx)?.takeIf { it.isNotBlank() }
                            val sb = StringBuilder()
                                .append("[id=")
                                .append(id)
                                .append("] ")
                                .append(fmt.format(java.util.Date(b)))
                                .append(" ~ ")
                                .append(fmt.format(java.util.Date(e)))
                                .append(" | ")
                                .append(title)
                            if (!loc.isNullOrEmpty()) sb.append(" @ ").append(loc)
                            if (!cal.isNullOrEmpty()) sb.append(" [").append(cal).append("]")
                            lines.add(sb.toString())
                        }
                    }
                    if (lines.isEmpty()) {
                        ToolResult(
                            toolName = tool.name,
                            success = true,
                            result = StringResultData("No events in the next $days day(s)."),
                            error = ""
                        )
                    } else {
                        ToolResult(
                            toolName = tool.name,
                            success = true,
                            result = StringResultData("Upcoming events (${lines.size}):\n" + lines.joinToString("\n")),
                            error = ""
                        )
                    }
                }

                "create" -> {
                    val title = tool.parameters.find { it.name == "title" }?.value?.takeIf { it.isNotBlank() }
                    if (title == null) {
                        return ToolResult(
                            toolName = tool.name,
                            success = false,
                            result = StringResultData(""),
                            error = "action=create requires title"
                        )
                    }
                    val start = tool.parameters.find { it.name == "start_time" }?.value?.toLongOrNull()
                        ?: (System.currentTimeMillis() + 3_600_000L)
                    val end = tool.parameters.find { it.name == "end_time" }?.value?.toLongOrNull()
                        ?: (start + 3_600_000L)
                    val description = tool.parameters.find { it.name == "description" }?.value?.takeIf { it.isNotBlank() }
                    val location = tool.parameters.find { it.name == "location" }?.value?.takeIf { it.isNotBlank() }

                    if (!hasPermission(android.Manifest.permission.WRITE_CALENDAR)) {
                        tryGrantPermission(android.Manifest.permission.WRITE_CALENDAR)
                    }
                    if (hasPermission(android.Manifest.permission.WRITE_CALENDAR)) {
                        // 直接写入主日历
                        val calendarId = resolveWritableCalendarId()
                        if (calendarId == null) {
                            return ToolResult(
                                toolName = tool.name,
                                success = false,
                                result = StringResultData(""),
                                error = "No writable calendar found on this device."
                            )
                        }
                        val values = ContentValues().apply {
                            put(CalendarContract.Events.CALENDAR_ID, calendarId)
                            put(CalendarContract.Events.TITLE, title)
                            put(CalendarContract.Events.DTSTART, start)
                            put(CalendarContract.Events.DTEND, end)
                            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                            if (description != null) put(CalendarContract.Events.DESCRIPTION, description)
                            if (location != null) put(CalendarContract.Events.EVENT_LOCATION, location)
                        }
                        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                        val eventId = uri?.lastPathSegment
                        if (eventId != null) {
                            ToolResult(
                                toolName = tool.name,
                                success = true,
                                result = StringResultData("Event created: '$title' (id=$eventId, ${fmtMillis(start)} ~ ${fmtMillis(end)})"),
                                error = ""
                            )
                        } else {
                            ToolResult(
                                toolName = tool.name,
                                success = false,
                                result = StringResultData(""),
                                error = "Calendar insert failed (contentResolver.insert returned null)."
                            )
                        }
                    } else {
                        // 无写权限时降级：唤起系统日历新建界面，由用户确认保存
                        val intent = Intent(Intent.ACTION_INSERT)
                            .setData(CalendarContract.Events.CONTENT_URI)
                            .putExtra(CalendarContract.Events.TITLE, title)
                            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                        if (description != null) intent.putExtra(CalendarContract.Events.DESCRIPTION, description)
                        if (location != null) intent.putExtra(CalendarContract.Events.EVENT_LOCATION, location)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        ToolResult(
                            toolName = tool.name,
                            success = true,
                            result = StringResultData("Opened calendar create UI for '$title' (WRITE_CALENDAR not granted, please confirm in the calendar app)"),
                            error = ""
                        )
                    }
                }

                "delete" -> {
                    val eventId = tool.parameters.find { it.name == "event_id" }?.value?.toLongOrNull()
                    if (eventId == null) {
                        return ToolResult(
                            toolName = tool.name,
                            success = false,
                            result = StringResultData(""),
                            error = "action=delete requires event_id (use action=query to list ids first... note: ids are not shown in query output yet, pass the id from your calendar app)"
                        )
                    }
                    if (!hasPermission(android.Manifest.permission.WRITE_CALENDAR)) {
                        tryGrantPermission(android.Manifest.permission.WRITE_CALENDAR)
                    }
                    if (!hasPermission(android.Manifest.permission.WRITE_CALENDAR)) {
                        return ToolResult(
                            toolName = tool.name,
                            success = false,
                            result = StringResultData(""),
                            error = "WRITE_CALENDAR permission not granted."
                        )
                    }
                    val uri = android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                    val rows = context.contentResolver.delete(uri, null, null)
                    ToolResult(
                        toolName = tool.name,
                        success = rows > 0,
                        result = StringResultData(if (rows > 0) "Deleted event id=$eventId" else "No event matched id=$eventId"),
                        error = if (rows > 0) "" else "Event not found or not deletable."
                    )
                }

                else -> ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Unknown action '$action'. Use query / create / delete."
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "日历操作失败", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Calendar operation failed: ${e.message}"
            )
        }
    }

    private fun resolveWritableCalendarId(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_OWNER}"
        val sortOrder = "${CalendarContract.Calendars.IS_PRIMARY} DESC"
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, null, sortOrder)?.use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return null
    }

    private fun fmtMillis(millis: Long): String =
        java.text.SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(java.util.Date(millis))

    /** 获取设备位置信息 通过系统API获取当前设备位置 */
    suspend fun getDeviceLocation(tool: AITool): ToolResult {
        val timeout = tool.parameters.find { it.name == "timeout" }?.value?.toIntOrNull() ?: 10
        val highAccuracy =
                tool.parameters.find { it.name == "high_accuracy" }?.value?.toBoolean() ?: false
        val includeAddress =
                tool.parameters.find { it.name == "include_address" }?.value?.toBoolean() ?: true

        return try {
            // 检查位置权限
            val hasFineLocationPermission =
                    context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED

            val hasCoarseLocationPermission =
                    context.checkSelfPermission(
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            // 如果没有任何位置权限，返回错误
            if (!hasFineLocationPermission && !hasCoarseLocationPermission) {
                return ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = context.getString(R.string.sys_op_location_permission_not_granted)
                )
            }

            // 根据精度要求和权限情况决定使用哪种精度
            val actualHighAccuracy = highAccuracy && hasFineLocationPermission

            // 使用Dispatchers.Main确保在主线程上执行位置操作
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            val locationResult =
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        kotlinx.coroutines.suspendCancellableCoroutine<Location?> { continuation ->
                            val locationManager =
                                    context.getSystemService(Context.LOCATION_SERVICE) as
                                            LocationManager

                            // 选择合适的位置提供者
                            val provider =
                                    when {
                                        actualHighAccuracy &&
                                                locationManager.isProviderEnabled(
                                                        LocationManager.GPS_PROVIDER
                                                ) -> LocationManager.GPS_PROVIDER
                                        locationManager.isProviderEnabled(
                                                LocationManager.NETWORK_PROVIDER
                                        ) -> LocationManager.NETWORK_PROVIDER
                                        locationManager.isProviderEnabled(
                                                LocationManager.PASSIVE_PROVIDER
                                        ) -> LocationManager.PASSIVE_PROVIDER
                                        else -> null
                                    }

                            if (provider == null) {
                                continuation.resume(null) { AppLogger.e(TAG, "位置请求取消", it) }
                                return@suspendCancellableCoroutine
                            }

                            // 尝试获取最后已知位置
                            val lastKnownLocation =
                                    try {
                                        if (actualHighAccuracy && hasFineLocationPermission) {
                                            locationManager.getLastKnownLocation(
                                                    LocationManager.GPS_PROVIDER
                                            )
                                                    ?: locationManager.getLastKnownLocation(
                                                            LocationManager.NETWORK_PROVIDER
                                                    )
                                        } else if (hasCoarseLocationPermission) {
                                            locationManager.getLastKnownLocation(
                                                    LocationManager.NETWORK_PROVIDER
                                            )
                                                    ?: locationManager.getLastKnownLocation(
                                                            LocationManager.PASSIVE_PROVIDER
                                                    )
                                        } else {
                                            null
                                        }
                                    } catch (e: SecurityException) {
                                        AppLogger.e(TAG, "获取最后已知位置失败", e)
                                        null
                                    }

                            // 如果有最后已知位置且足够新（10分钟内），直接返回
                            if (lastKnownLocation != null &&
                                            System.currentTimeMillis() - lastKnownLocation.time <
                                                    10 * 60 * 1000
                            ) {
                                continuation.resume(lastKnownLocation) { AppLogger.e(TAG, "位置请求取消", it) }
                                return@suspendCancellableCoroutine
                            }

                            // 否则请求位置更新
                            val locationListener =
                                    object : android.location.LocationListener {
                                        override fun onLocationChanged(location: Location) {
                                            locationManager.removeUpdates(this)
                                            continuation.resume(location) {
                                                AppLogger.e(TAG, "位置请求取消", it)
                                            }
                                        }

                                        override fun onProviderDisabled(provider: String) {
                                            // 如果提供者被禁用，尝试使用最后已知位置
                                            if (!continuation.isCompleted) {
                                                if (lastKnownLocation != null) {
                                                    continuation.resume(lastKnownLocation) {
                                                        AppLogger.e(TAG, "位置请求取消", it)
                                                    }
                                                } else {
                                                    continuation.resume(null) {
                                                        AppLogger.e(TAG, "位置请求取消", it)
                                                    }
                                                }
                                            }
                                        }

                                        override fun onProviderEnabled(provider: String) {
                                            // 不需要处理
                                        }

                                        @Deprecated("Deprecated in Java")
                                        override fun onStatusChanged(
                                                provider: String,
                                                status: Int,
                                                extras: android.os.Bundle
                                        ) {
                                            // 不需要处理
                                        }
                                    }

                            try {
                                // 设置位置请求参数
                                locationManager.requestLocationUpdates(
                                        provider,
                                        0, // 最小时间间隔
                                        0f, // 最小距离变化
                                        locationListener
                                )

                                // 设置超时
                                kotlinx.coroutines.GlobalScope.launch {
                                    delay(timeout * 1000L)
                                    // 在主线程上移除更新和恢复协程
                                    kotlinx.coroutines.withContext(
                                            kotlinx.coroutines.Dispatchers.Main
                                    ) {
                                        if (!continuation.isCompleted) {
                                            locationManager.removeUpdates(locationListener)
                                            // 如果超时，尝试使用最后已知位置
                                            continuation.resume(lastKnownLocation) {
                                                AppLogger.e(TAG, "位置请求取消", it)
                                            }
                                        }
                                    }
                                }

                                // 如果协程被取消，移除位置更新
                                continuation.invokeOnCancellation {
                                    try {
                                        // 确保在主线程上移除位置更新
                                        kotlinx.coroutines.runBlocking(
                                                kotlinx.coroutines.Dispatchers.Main
                                        ) { locationManager.removeUpdates(locationListener) }
                                    } catch (e: Exception) {
                                        AppLogger.e(TAG, "移除位置更新失败", e)
                                    }
                                }
                            } catch (e: SecurityException) {
                                continuation.resume(lastKnownLocation) { AppLogger.e(TAG, "位置请求取消", it) }
                                AppLogger.e(TAG, "请求位置更新失败", e)
                            }
                        }
                    }

            // 处理位置结果
            if (locationResult == null) {
                return ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = context.getString(R.string.sys_op_cannot_get_location)
                )
            }

            val resultData =
                    if (includeAddress) {
                        // 获取地址信息
                        val addressInfo =
                                getAddressFromLocation(
                                        locationResult.latitude,
                                        locationResult.longitude
                                )

                        LocationData(
                                latitude = locationResult.latitude,
                                longitude = locationResult.longitude,
                                accuracy = locationResult.accuracy,
                                provider = locationResult.provider ?: "unknown",
                                timestamp = locationResult.time,
                                rawData = locationResult.toString(),
                                city = addressInfo.city,
                                address = addressInfo.address,
                                country = addressInfo.country,
                                province = addressInfo.province
                        )
                    } else {
                        LocationData(
                                latitude = locationResult.latitude,
                                longitude = locationResult.longitude,
                                accuracy = locationResult.accuracy,
                                provider = locationResult.provider ?: "unknown",
                                timestamp = locationResult.time,
                                rawData = locationResult.toString()
                        )
                    }

            return ToolResult(toolName = tool.name, success = true, result = resultData, error = "")
        } catch (e: Exception) {
            AppLogger.e(TAG, "获取位置信息时出错", e)
            return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Error getting location information: ${e.message}"
            )
        }
    }

    /**
     * 从经纬度获取地址信息
     * @param latitude 纬度
     * @param longitude 经度
     * @return 包含地址信息的数据类
     */
    private fun getAddressFromLocation(latitude: Double, longitude: Double): AddressInfo {
        try {
            val geocoder = Geocoder(context, Locale.getDefault())

            // 尝试获取地址
            val addresses = geocoder.getFromLocation(latitude, longitude, 1)

            if (addresses != null && addresses.isNotEmpty()) {
                val address = addresses[0]

                return AddressInfo(
                        address = address.getAddressLine(0) ?: "",
                        city = address.locality ?: address.subAdminArea ?: "",
                        province = address.adminArea ?: "",
                        country = address.countryName ?: "",
                        postalCode = address.postalCode ?: ""
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "获取地址信息时出错", e)
        }

        // 如果无法获取地址信息，返回空对象
        return AddressInfo("", "", "", "", "")
    }

    /** 地址信息数据类 */
    data class AddressInfo(
            val address: String, // 完整地址
            val city: String, // 城市
            val province: String, // 省/州
            val country: String, // 国家
            val postalCode: String // 邮政编码
    )
}
