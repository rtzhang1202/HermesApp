package com.ai.assistance.operit.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ai.assistance.operit.util.AppLogger

/**
 * 外部按键 App（KeyPilot）广播唤起入口：
 *   am broadcast -a com.ai.assistance.operit.action.OPEN_VOICE_ASSISTANT
 *
 * 支持透传 extras：text / auto_send。
 * 收到广播后以 NEW_TASK 启动 [VoiceAssistantActivity]。
 */
class VoiceWakeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "VoiceAssistant"
    }

    override fun onReceive(context: Context, intent: Intent) {
        AppLogger.d(TAG, "VoiceWakeReceiver action=${intent.action} from=${intent.`package`}")
        if (intent.action != VoiceAssistantActivity.ACTION_OPEN_VOICE_ASSISTANT) return
        try {
            val launch = Intent(context, VoiceAssistantActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP)
                intent.getStringExtra(VoiceAssistantActivity.EXTRA_TEXT)?.let {
                    putExtra(VoiceAssistantActivity.EXTRA_TEXT, it)
                }
                if (intent.hasExtra(VoiceAssistantActivity.EXTRA_AUTO_SEND)) {
                    putExtra(
                        VoiceAssistantActivity.EXTRA_AUTO_SEND,
                        intent.getBooleanExtra(VoiceAssistantActivity.EXTRA_AUTO_SEND, false)
                    )
                }
            }
            context.startActivity(launch)
        } catch (t: Throwable) {
            AppLogger.e(TAG, "VoiceWakeReceiver failed to launch voice assistant", t)
        }
    }
}
