package com.ai.assistance.operit.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.ai.assistance.operit.R
import com.ai.assistance.operit.util.AppLogger

/**
 * 语音助手入口（Gemini / 超级小爱 风格全屏界面）。
 *
 * 供外部自定义按键 App（KeyPilot）唤起：
 *   am start -a com.ai.assistance.operit.action.OPEN_VOICE_ASSISTANT
 *   am start -a com.ai.assistance.operit.action.OPEN_VOICE_ASSISTANT \
 *       --es text "你好" --ez auto_send true
 *
 * 唤起后默认进入聆听态（云端 MiMo ASR 优先，网络类失败降级本地 sherpa 离线识别）；
 * 配置缺失/无麦克风/识别失败时自动降级为键盘文本兜底输入（顶部显示具体原因）。
 * 会话走专用短记忆 session（voice:session）。
 */
class VoiceAssistantActivity : ComponentActivity() {

    companion object {
        private const val TAG = "VoiceAssistant"
        const val ACTION_OPEN_VOICE_ASSISTANT =
            "com.ai.assistance.operit.action.OPEN_VOICE_ASSISTANT"
        const val EXTRA_TEXT = "text"
        const val EXTRA_AUTO_SEND = "auto_send"
    }

    private lateinit var controller: VoiceAssistantController

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        AppLogger.d(TAG, "RECORD_AUDIO granted=$granted")
        controller.setMicPermissionAndMaybeStart(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.d(TAG, "VoiceAssistantActivity onCreate action=${intent?.action}")
        controller = VoiceAssistantController.getInstance(this)
        controller.setMicPermission(
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
        handleVoiceIntent(intent)
        setContent {
            VoiceAssistantScreen(
                controller = controller,
                onClose = { finish() },
                onScreenLens = { openScreenLens() }
            )
        }
    }

    /**
     * 识别屏幕：语音界面是全屏的，它盖住目标应用时既截不到「底下那一屏」，无障碍也读不到它的窗口。
     * 唤起识屏悬浮窗（TYPE_ACCESSIBILITY_OVERLAY，不抢焦点）后立即 finish 自己，
     * 目标应用回到前台 —— 悬浮窗随后轮询到它的节点树并截屏。
     * tapT0 传下去用于「按下→可选态」的可信计时。
     */
    private fun openScreenLens() {
        val tapT0 = android.os.SystemClock.uptimeMillis()
        AppLogger.d(TAG, "screen lens requested")
        try {
            com.ai.assistance.operit.voice.lens.ScreenLensController
                .getInstance(applicationContext)
                .show(tapT0)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "open screen lens failed: ${t.message}")
            return
        }
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AppLogger.d(TAG, "VoiceAssistantActivity onNewIntent action=${intent.action}")
        handleVoiceIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // 不在此处自动重启聆听：onCreate/onNewIntent 的 handleVoiceIntent 已负责，
        // 避免与权限回调并发导致 startRecognition 竞态降级为文本模式。
    }

    override fun onDestroy() {
        // 只在真正销毁（非配置变更导致的重建会再走 onCreate）时释放麦克风
        if (isFinishing) {
            controller.onUiClosed()
        }
        super.onDestroy()
    }

    private fun handleVoiceIntent(intent: Intent?) {
        val text = intent?.getStringExtra(EXTRA_TEXT)
        val autoSend = intent?.getBooleanExtra(EXTRA_AUTO_SEND, false) == true
        if (intent != null && intent.hasExtra(EXTRA_TEXT)) {
            intent.removeExtra(EXTRA_TEXT)
            intent.removeExtra(EXTRA_AUTO_SEND)
        }
        requestMicPermissionThen { controller.onUiReady(text, autoSend) }
    }

    private fun requestMicPermissionThen(next: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            controller.setMicPermission(true)
            next()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            // 权限回调里会启动聆听；此处仍先推进会话初始化（历史恢复）
            next()
        }
    }
}
