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
 * 唤起后默认进入聆听态（本地 ASR）；模型资产缺失/无麦克风时自动降级为
 * 键盘文本兜底输入。会话走专用短记忆 session（voice:session）。
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
                onClose = { finish() }
            )
        }
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
