package com.ai.assistance.operit.core.tools.system.shell

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel
import com.ai.assistance.operit.core.tools.system.ShellIdentity

/**
 * 基于无障碍服务的Shell命令执行器 实现ACCESSIBILITY权限级别的命令执行
 *
 * 无障碍服务本身无法执行shell命令：本执行器以「无障碍服务已启用」作为准入门槛，
 * 实际命令执行下沉到 StandardShellExecutor（应用进程内 shell），保证 ACCESSIBILITY
 * 档位下 execute_shell 能真实执行并回显 stdout/stderr/exitCode。
 */
class AccessibilityShellExecutor(private val context: Context) : ShellExecutor {
    companion object {
        private const val TAG = "AccessibilityShellExecutor"
        private var accessibilityService: AccessibilityService? = null

        /** 伴生无障碍服务提供者包名（UIAccessibilityService 所在包，随 assets/accessibility.apk 安装） */
        private const val PROVIDER_PACKAGE_NAME = "com.ai.assistance.operit.provider"

        /**
         * 设置全局无障碍服务引用
         * @param service 无障碍服务实例
         */
        fun setAccessibilityService(service: AccessibilityService?) {
            accessibilityService = service
        }
    }

    private val standardExecutor by lazy { StandardShellExecutor(context) }

    override fun getPermissionLevel(): AndroidPermissionLevel = AndroidPermissionLevel.ACCESSIBILITY

    override fun isAvailable(): Boolean {
        return isAccessibilityServiceEnabled()
    }

    override fun hasPermission(): ShellExecutor.PermissionStatus {
        return if (isAccessibilityServiceEnabled()) {
            ShellExecutor.PermissionStatus.granted()
        } else {
            ShellExecutor.PermissionStatus.denied("Accessibility service is not enabled")
        }
    }

    override fun initialize() {
        // 无障碍服务初始化由系统控制，此处无需额外操作
    }

    override fun requestPermission(onResult: (Boolean) -> Unit) {
        if (isAvailable()) {
            onResult(true)
            return
        }

        // 引导用户打开无障碍服务设置
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)

            // 由于无法知道用户是否启用了服务，返回false，让调用者自行处理后续检查
            onResult(false)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error opening accessibility settings", e)
            onResult(false)
        }
    }

    override suspend fun startProcess(command: String): ShellProcess {
        return standardExecutor.startProcess(command)
    }

    /** 检查无障碍服务是否已启用：解析系统设置中真实启用的服务组件，匹配本应用包或伴生提供者包 */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices =
                Settings.Secure.getString(
                        context.contentResolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false

        return enabledServices.split(':').any { entry ->
            val componentName = ComponentName.unflattenFromString(entry.trim())
            componentName != null &&
                    (componentName.packageName == context.packageName ||
                            componentName.packageName == PROVIDER_PACKAGE_NAME)
        }
    }

    override suspend fun executeCommand(
        command: String,
        identity: ShellIdentity
    ): ShellExecutor.CommandResult {
        val permStatus = hasPermission()
        if (!permStatus.granted) {
            return ShellExecutor.CommandResult(false, "", permStatus.reason, -1)
        }

        AppLogger.d(TAG, "Executing command via accessibility-tier shell: $command")

        // 无障碍服务不能直接执行shell命令：准入门槛（服务已启用）已在上面校验，
        // 实际执行与输出回显下沉到标准执行器（应用进程内 sh）
        return standardExecutor.executeCommand(command, identity)
    }
}
