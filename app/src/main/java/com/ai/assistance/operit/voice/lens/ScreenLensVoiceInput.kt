package com.ai.assistance.operit.voice.lens

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.voice.asr.AsrEngine
import com.ai.assistance.operit.voice.asr.AsrEngineKind
import com.ai.assistance.operit.voice.asr.AsrErrorKind
import com.ai.assistance.operit.voice.asr.AsrException
import com.ai.assistance.operit.voice.asr.AsrListener
import com.ai.assistance.operit.voice.asr.CloudMimoAsr
import com.ai.assistance.operit.voice.asr.LocalSherpaAsr
import com.ai.assistance.operit.voice.asr.MimoAsrConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 识屏提问框的语音输入：**云优先 + 本地兜底**，语种锁 zh。
 *
 * 与语音助手（VoiceAssistantController）同一套引擎与策略：
 * - 主路径 [CloudMimoAsr]（`voice/asr`，复用 LLM 网关配置；请求体 asr_options.language='zh'）
 * - 云端出现【网络类】失败（DNS/连接/超时）→ 回退 [LocalSherpaAsr] 离线识别（请用户再说一次）
 * - 云端 4xx/配置缺失/空音频/超时 → 直接报原因，由调用方提示「可直接打字」
 *
 * 本类只产出文本，**不碰输入框**：是否插入/替换选段由 Controller 按光标决定，
 * 保证「边打字边点语音」不会把手输内容冲掉。
 */
class ScreenLensVoiceInput(
        private val appContext: Context,
        private val onEvent: (Event) -> Unit
) {

    sealed class Event {
        /** 开始聆听（engine = cloud-mimo / local-sherpa） */
        data class Listening(val engine: String) : Event()

        /** 麦克风音量 0..1 */
        data class Volume(val level: Float) : Event()

        /** 停止录音，等待转写 */
        data class Recognizing(val engine: String) : Event()

        /** 过程提示（如「已切本地离线识别，请再说一次」） */
        data class Info(val text: String) : Event()

        /** 转写落地（已 trim） */
        data class Final(val text: String) : Event()

        /** 失败原因（直接展示给用户，文字输入仍可用） */
        data class Failed(val reason: String) : Event()
    }

    private enum class State { IDLE, LISTENING, RECOGNIZING }

    private companion object {
        const val TAG = "ScreenLensAsr"
        const val CLOUD_WATCHDOG_MS = 200_000L
        const val LOCAL_WATCHDOG_MS = 8_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var state = State.IDLE
    private var cloud: CloudMimoAsr? = null
    private var local: LocalSherpaAsr? = null
    private var active: AsrEngine? = null
    private var watchdog: Job? = null

    private val listener =
            object : AsrListener {
                override fun onVolume(level: Float) {
                    scope.launch {
                        if (state == State.LISTENING) onEvent(Event.Volume(level))
                    }
                }

                override fun onFinal(text: String) {
                    scope.launch {
                        watchdog?.cancel()
                        watchdog = null
                        state = State.IDLE
                        AppLogger.i(TAG, "asr final len=${text.length} text=${text.take(80)}")
                        onEvent(Event.Final(text))
                    }
                }

                override fun onError(error: AsrException) {
                    scope.launch { handleError(error) }
                }
            }

    fun start() {
        if (state != State.IDLE) return
        state = State.LISTENING
        scope.launch {
            try {
                val engine = cloud ?: CloudMimoAsr { resolveCloudConfig() }.also { cloud = it }
                engine.initialize()
                active = engine
                engine.startListening("zh", listener)
                AppLogger.i(TAG, "listening start engine=${engine.engineName} lang=zh")
                onEvent(Event.Listening(engine.engineName))
            } catch (e: AsrException) {
                state = State.IDLE
                AppLogger.w(TAG, "start failed kind=${e.kind} msg=${e.message}")
                onEvent(Event.Failed(e.message ?: e.kind.name))
            } catch (t: Throwable) {
                state = State.IDLE
                AppLogger.w(TAG, "start failed: ${t.message}")
                onEvent(Event.Failed(t.message ?: "麦克风启动失败"))
            }
        }
    }

    fun stop() {
        val engine = active
        if (engine == null || state != State.LISTENING) {
            state = State.IDLE
            return
        }
        state = State.RECOGNIZING
        onEvent(Event.Recognizing(engine.engineName))
        AppLogger.i(TAG, "stop requested engine=${engine.engineName}")
        watchdog?.cancel()
        watchdog =
                scope.launch {
                    delay(if (engine.kind == AsrEngineKind.CLOUD) CLOUD_WATCHDOG_MS else LOCAL_WATCHDOG_MS)
                    if (state == State.RECOGNIZING) {
                        AppLogger.w(TAG, "watchdog timeout, cancel engine")
                        runCatching { engine.cancel() }
                        state = State.IDLE
                        onEvent(Event.Failed(appContext.getString(R.string.lens_asr_timeout)))
                    }
                }
        scope.launch {
            try {
                engine.stopListening()
            } catch (e: AsrException) {
                handleError(e)
            } catch (t: Throwable) {
                handleError(AsrException(AsrErrorKind.UNKNOWN, t.message ?: "stop failed", t))
            }
        }
    }

    fun cancel() {
        watchdog?.cancel()
        watchdog = null
        runCatching { active?.cancel() }
        state = State.IDLE
        AppLogger.d(TAG, "cancel")
    }

    fun shutdown() {
        cancel()
        runCatching { cloud?.shutdown() }
        runCatching { local?.shutdown() }
        cloud = null
        local = null
        active = null
    }

    /** 云端网络类失败 → 本地离线兜底（与语音助手同策略）；其余错误直接报原因 */
    private suspend fun handleError(error: AsrException) {
        watchdog?.cancel()
        watchdog = null
        AppLogger.w(TAG, "asr error kind=${error.kind} msg=${error.message}")
        if (error.kind == AsrErrorKind.NETWORK) {
            val localEngine = local ?: LocalSherpaAsr(appContext).also { local = it }
            try {
                localEngine.initialize()
                active = localEngine
                state = State.LISTENING
                onEvent(Event.Info(appContext.getString(R.string.lens_asr_fallback_local)))
                localEngine.startListening("zh", listener)
                AppLogger.i(TAG, "fallback to local engine, listening again")
                return
            } catch (t: Throwable) {
                AppLogger.w(TAG, "local fallback unavailable: ${t.message}")
            }
        }
        state = State.IDLE
        onEvent(Event.Failed(error.message ?: error.kind.name))
    }

    /** 复用 LLM 网关配置（与 VoiceAssistantController.resolveCloudConfig 同源） */
    private suspend fun resolveCloudConfig(): MimoAsrConfig? {
        val core =
                try {
                    ChatRuntimeHolder.getInstance(appContext).getCore(ChatRuntimeSlot.GATEWAY)
                } catch (t: Throwable) {
                    AppLogger.w(TAG, "core unavailable: ${t.message}")
                    return null
                }
        val delegate =
                try {
                    core.getApiConfigDelegate()
                } catch (t: Throwable) {
                    AppLogger.w(TAG, "ApiConfigDelegate unavailable: ${t.message}")
                    return null
                }
        try {
            withTimeoutOrNull(8_000) { delegate.isInitialized.first { it } }
        } catch (_: Throwable) {
        }
        val key = delegate.apiKey.value
        val cfg = MimoAsrConfig.fromLlmConfig(key, delegate.apiEndpoint.value)
        AppLogger.i(
                TAG,
                "cloud config ready=${cfg != null} key=${MimoAsrConfig.mask(key)} " +
                        "base=${cfg?.baseUrl ?: "-"}"
        )
        return cfg
    }
}
