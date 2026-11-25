package com.ai.assistance.operit.voice

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.api.speech.SpeechService
import com.ai.assistance.operit.api.speech.SpeechServiceFactory
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.InputProcessingState
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.integrations.externalchat.ExternalChatResponseSanitizer
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.stream.SharedStream
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 语音助手状态机：唤起入场 → 聆听 → 转写 → 思考 → 回答流式 → 追问 */
enum class VoicePhase { AWAKE, LISTENING, TRANSCRIBING, THINKING, ANSWERING, HANDED_OFF, FOLLOWUP, ERROR }

enum class InputMode { VOICE, TEXT }

/** UI 气泡：role = "user" | "ai" */
data class VoiceBubble(val key: Long, val role: String, val text: String)

/**
 * 语音助手控制器（进程级单例）：
 * - 专用短记忆 session（固定 chatId `voice:session`，跨唤醒共用，滑动窗口保留最近 6 轮）
 * - 与主聊天数据隔离（独立 session 标记 `voice:` 前缀）
 * - 智能路由：简单问答直接短 session 流式回复；开发/搜索/复杂任务拉起 run_ui_subagent
 *   后台子代理异步执行，先回执「已交给后台处理」，结果回填同一 session
 *
 * 单例 + 独立 scope：子代理回填不随 Activity 销毁而丢失。
 */
class VoiceAssistantController private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "VoiceAssistant"
        const val SESSION_CHAT_ID = "voice:session"
        private const val WINDOW_ROUNDS = 6
        private const val STREAM_ACQUIRE_TIMEOUT_MS = 15_000L
        private const val RESPONSE_TIMEOUT_MS = 180_000L

        @Volatile private var INSTANCE: VoiceAssistantController? = null
        fun getInstance(context: Context): VoiceAssistantController =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: VoiceAssistantController(context.applicationContext).also { INSTANCE = it }
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val history: ChatHistoryManager get() = ChatHistoryManager.getInstance(appContext)
    private val core get() = ChatRuntimeHolder.getInstance(appContext).getCore(ChatRuntimeSlot.GATEWAY)

    private val _phase = MutableStateFlow(VoicePhase.AWAKE)
    val phase: StateFlow<VoicePhase> = _phase.asStateFlow()

    private val _bubbles = MutableStateFlow<List<VoiceBubble>>(emptyList())
    val bubbles: StateFlow<List<VoiceBubble>> = _bubbles.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _volume = MutableStateFlow(0f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    /** 状态副标题（错误/回执/降级提示），null 表示用 phase 默认文案 */
    private val _statusDetail = MutableStateFlow<String?>(null)
    val statusDetail: StateFlow<String?> = _statusDetail.asStateFlow()

    private val _inputMode = MutableStateFlow(InputMode.VOICE)
    val inputMode: StateFlow<InputMode> = _inputMode.asStateFlow()

    private val _micPermissionGranted = MutableStateFlow(false)
    val micPermissionGranted: StateFlow<Boolean> = _micPermissionGranted.asStateFlow()

    private val _pendingPrefill = MutableStateFlow<String?>(null)
    val pendingPrefill: StateFlow<String?> = _pendingPrefill.asStateFlow()

    private var speechService: SpeechService? = null
    private var resultJob: Job? = null
    private var volumeJob: Job? = null
    private var lastHandledVoiceText: String? = null

    // ========== 生命周期 ==========

    fun setMicPermission(granted: Boolean) {
        _micPermissionGranted.value = granted
    }

    /** Activity 就绪：确保 session、恢复历史、处理唤起 extras、默认进入聆听态 */
    fun onUiReady(text: String?, autoSend: Boolean) {
        scope.launch {
            ensureSession()
            if (_bubbles.value.isEmpty()) reloadFromHistory()
            when {
                autoSend && !text.isNullOrBlank() -> {
                    _phase.value = VoicePhase.FOLLOWUP
                    submit(text.trim())
                }
                !text.isNullOrBlank() -> {
                    _pendingPrefill.value = text
                    _phase.value = VoicePhase.FOLLOWUP
                }
                _micPermissionGranted.value -> startVoiceInput()
                else -> {
                    _phase.value = VoicePhase.FOLLOWUP
                    _statusDetail.value = appContext.getString(R.string.voice_permission_needed)
                    _inputMode.value = InputMode.TEXT
                }
            }
        }
    }

    /** Activity 销毁：释放麦克风，但保留 session 与子代理回填能力 */
    fun onUiClosed() {
        resultJob?.cancel()
        volumeJob?.cancel()
        _volume.value = 0f
        val svc = speechService
        if (svc != null) {
            scope.launch { runCatching { svc.cancelRecognition() } }
        }
    }

    fun consumePrefill() {
        _pendingPrefill.value = null
    }

    fun setMicPermissionAndMaybeStart(granted: Boolean) {
        _micPermissionGranted.value = granted
        if (granted) {
            startVoiceInput()
        } else {
            _phase.value = VoicePhase.FOLLOWUP
            _statusDetail.value = appContext.getString(R.string.voice_permission_needed)
            _inputMode.value = InputMode.TEXT
        }
    }

    // ========== 语音输入（复用 APP 现有本地 ASR 链路） ==========

    fun startVoiceInput() {
        if (!_micPermissionGranted.value) {
            _phase.value = VoicePhase.FOLLOWUP
            _statusDetail.value = appContext.getString(R.string.voice_permission_needed)
            _inputMode.value = InputMode.TEXT
            return
        }
        scope.launch {
            if (_phase.value == VoicePhase.LISTENING) return@launch // 防止二次唤起重复 startRecognition
            if (_phase.value == VoicePhase.THINKING ||
                _phase.value == VoicePhase.ANSWERING ||
                _phase.value == VoicePhase.HANDED_OFF
            ) {
                return@launch
            }
            _statusDetail.value = null
            // 与悬浮窗语音链路一致：先让唤醒词服务让出麦克风
            try {
                AIForegroundService.ensureMicrophoneForeground(appContext, forceStart = true)
            } catch (t: Throwable) {
                AppLogger.w(TAG, "ensureMicrophoneForeground failed: ${t.message}")
            }
            delay(180)

            val svc = try {
                SpeechServiceFactory.getInstance(appContext)
            } catch (t: Throwable) {
                AppLogger.e(TAG, "SpeechServiceFactory failed", t)
                null
            }
            if (svc == null) {
                fallbackToText(appContext.getString(R.string.voice_asr_unavailable))
                return@launch
            }
            speechService = svc

            val initialized = try {
                svc.initialize()
            } catch (t: Throwable) {
                AppLogger.w(TAG, "ASR initialize failed: ${t.message}")
                false
            }
            if (!initialized) {
                // 本地 sherpa 模型资产缺失（assets/models 未打包）时必然走到这里 → 文本兜底
                AppLogger.w(TAG, "ASR initialize returned false, falling back to text input")
                fallbackToText(appContext.getString(R.string.voice_asr_unavailable))
                return@launch
            }

            val language = if (Locale.getDefault().language == "zh") "zh-CN" else "en-US"
            var started = false
            var attempt = 0
            while (!started && attempt < 10) {
                if (attempt > 0) delay(160)
                started = try {
                    svc.startRecognition(
                        languageCode = language,
                        continuousMode = true,
                        partialResults = true
                    )
                } catch (t: Throwable) {
                    AppLogger.w(TAG, "startRecognition attempt $attempt failed: ${t.message}")
                    false
                }
                attempt++
            }
            if (!started) {
                fallbackToText(appContext.getString(R.string.voice_asr_unavailable))
                return@launch
            }

            _inputMode.value = InputMode.VOICE
            _phase.value = VoicePhase.LISTENING
            _statusDetail.value = null
            AppLogger.d(TAG, "ASR listening started (lang=$language)")

            // startRecognition 会把 result flow 重置为空串，旧的 final 结果不会误触发
            resultJob?.cancel()
            resultJob = scope.launch {
                svc.recognitionResultFlow.collect { r ->
                    if (_phase.value == VoicePhase.LISTENING &&
                        r.isFinal && r.text.isNotBlank() &&
                        r.text != lastHandledVoiceText
                    ) {
                        lastHandledVoiceText = r.text
                        onVoiceFinal(r.text.trim())
                    }
                }
            }
            volumeJob?.cancel()
            volumeJob = scope.launch {
                svc.volumeLevelFlow.collect { _volume.value = it }
            }
        }
    }

    fun stopVoiceInput() {
        resultJob?.cancel()
        resultJob = null
        volumeJob?.cancel()
        volumeJob = null
        _volume.value = 0f
        val svc = speechService ?: return
        scope.launch { runCatching { svc.stopRecognition() } }
    }

    /** 麦克风按钮开关 */
    fun toggleListening() {
        when {
            _phase.value == VoicePhase.THINKING ||
                _phase.value == VoicePhase.ANSWERING ||
                _phase.value == VoicePhase.HANDED_OFF -> return
            _phase.value == VoicePhase.LISTENING -> {
                stopVoiceInput()
                _phase.value = VoicePhase.FOLLOWUP
            }
            else -> startVoiceInput()
        }
    }

    fun setTextMode(enabled: Boolean) {
        if (enabled) {
            stopVoiceInput()
            _inputMode.value = InputMode.TEXT
            if (_phase.value == VoicePhase.LISTENING) _phase.value = VoicePhase.FOLLOWUP
        } else {
            _inputMode.value = InputMode.VOICE
            startVoiceInput()
        }
    }

    private fun fallbackToText(reason: String) {
        stopVoiceInput()
        _inputMode.value = InputMode.TEXT
        if (_phase.value == VoicePhase.AWAKE || _phase.value == VoicePhase.LISTENING) {
            _phase.value = VoicePhase.FOLLOWUP
        }
        _statusDetail.value = reason
    }

    private suspend fun onVoiceFinal(text: String) {
        if (text.isBlank()) return
        AppLogger.d(TAG, "voice final: $text")
        stopVoiceInput()
        _phase.value = VoicePhase.TRANSCRIBING
        submit(text)
    }

    // ========== 发送与智能路由 ==========

    fun submitFromText(text: String) {
        val t = text.trim()
        if (t.isBlank()) return
        if (_phase.value == VoicePhase.THINKING ||
            _phase.value == VoicePhase.ANSWERING ||
            _phase.value == VoicePhase.HANDED_OFF
        ) {
            AppLogger.d(TAG, "busy, ignore submit: $t")
            return
        }
        stopVoiceInput()
        _phase.value = VoicePhase.THINKING
        submit(t)
    }

    private fun submit(text: String) {
        scope.launch {
            _statusDetail.value = null
            trimWindow()
            val route = VoiceRouter.classify(text)
            AppLogger.d(
                TAG,
                "route=$route session=$SESSION_CHAT_ID text=${text.take(100)}"
            )
            when (route) {
                VoiceRouter.Route.SIMPLE -> sendDirect(text)
                VoiceRouter.Route.SUBAGENT -> dispatchSubagent(text)
            }
        }
    }

    /** 简单问答：走短记忆 session 直接流式回复（复用 ChatServiceCore 本体链路） */
    private suspend fun sendDirect(text: String) {
        _phase.value = VoicePhase.THINKING
        appendBubble("user", text)
        val aiKey = nextKey()
        _bubbles.value = _bubbles.value + VoiceBubble(aiKey, "ai", "")

        try {
            // 等 API 配置就绪（新装进程冷启动时 config 异步加载）
            withTimeoutOrNull(8_000) {
                core.getApiConfigDelegate().isInitialized.first { it }
            }
            // 等上一轮结束（同 session 串行）
            withTimeoutOrNull(15_000) {
                core.activeStreamingChatIds.first { SESSION_CHAT_ID !in it }
            }

            core.sendUserMessage(
                chatIdOverride = SESSION_CHAT_ID,
                messageTextOverride = text
            )

            val stream = acquireStream()
            if (stream == null) {
                dropBubble(aiKey)
                _phase.value = VoicePhase.ERROR
                _statusDetail.value =
                    _statusDetail.value ?: appContext.getString(R.string.voice_error_prefix) + "no stream"
                afterTurn()
                return
            }

            _phase.value = VoicePhase.ANSWERING
            val sb = StringBuilder()
            try {
                withTimeoutOrNull(RESPONSE_TIMEOUT_MS) {
                    ExternalChatResponseSanitizer.sanitizeStream(stream, false).collect { chunk ->
                        sb.append(chunk)
                        _streamingText.value = stripThinkMarkers(sb.toString())
                    }
                }
            } catch (t: Throwable) {
                AppLogger.e(TAG, "stream collect failed", t)
            }
            _streamingText.value = ""

            val finalState = core.inputProcessingStateByChatId.value[SESSION_CHAT_ID]
            val answer = sb.toString()
            if (finalState is InputProcessingState.Error) {
                updateBubble(aiKey, answer.ifBlank {
                    appContext.getString(R.string.voice_error_prefix) + finalState.message
                })
                _statusDetail.value =
                    appContext.getString(R.string.voice_error_prefix) + finalState.message
            } else {
                updateBubble(aiKey, stripThinkMarkers(answer).ifBlank {
                    appContext.getString(R.string.voice_error_prefix) + "empty answer"
                })
            }
            _phase.value = VoicePhase.FOLLOWUP
        } catch (t: Throwable) {
            AppLogger.e(TAG, "sendDirect failed", t)
            dropBubble(aiKey)
            _phase.value = VoicePhase.ERROR
            _statusDetail.value =
                appContext.getString(R.string.voice_error_prefix) + (t.message ?: "unknown")
        }
        afterTurn()
    }

    /** 剥离模型思考块（成对 think 类标签含内容），只留正文 */
    private fun stripThinkMarkers(raw: String): String {
        var s = raw
        val pairedTags = listOf("thinking", "thinking_mode", "reasoning", "thought", "think", "cot")
        for (t in pairedTags) {
            val open = "<" + t + ">"
            val close = "</" + t + ">"
            while (true) {
                val i = s.indexOf(open)
                if (i < 0) break
                val j = s.indexOf(close, i)
                if (j < 0) {
                    s = s.substring(0, i)
                    break
                }
                s = s.substring(0, i) + s.substring(j + close.length)
            }
        }
        for (t in pairedTags) {
            s = s.replace("<" + t + ">", "").replace("</" + t + ">", "")
        }
        return s.trim()
    }

    /** 轮次结束后的统一收尾：裁剪窗口 + 语音模式自动续听 */
    private fun afterTurn() {
        scope.launch {
            delay(400)
            trimWindow()
            if (_inputMode.value == InputMode.VOICE && _micPermissionGranted.value) {
                startVoiceInput()
            }
        }
    }

    /** 轮询等待本 session 的响应流（复用本体链路的获取模式） */
    private suspend fun acquireStream(): SharedStream<String>? {
        val deadline = System.currentTimeMillis() + STREAM_ACQUIRE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            core.getResponseStream(SESSION_CHAT_ID)?.let { return it }
            val st = core.inputProcessingStateByChatId.value[SESSION_CHAT_ID]
            if (st is InputProcessingState.Error) {
                _statusDetail.value =
                    appContext.getString(R.string.voice_error_prefix) + st.message
                return null
            }
            delay(50)
        }
        AppLogger.w(TAG, "acquireStream timeout")
        runCatching { core.cancelMessage(SESSION_CHAT_ID) }
        return null
    }

    /** 复杂任务：先回执，再后台拉起 run_ui_subagent，结果回填同一 session */
    private suspend fun dispatchSubagent(text: String) {
        _phase.value = VoicePhase.HANDED_OFF
        _statusDetail.value = appContext.getString(R.string.voice_handed_off_detail)
        appendBubble("user", text)
        val receipt = appContext.getString(R.string.voice_handed_off_detail)
        appendBubble("ai", receipt)
        runCatching {
            history.addMessage(SESSION_CHAT_ID, ChatMessage(sender = "user", content = text))
            history.addMessage(SESSION_CHAT_ID, ChatMessage(sender = "ai", content = receipt))
        }.onFailure { AppLogger.w(TAG, "persist receipt failed: ${it.message}") }

        // 单例 scope（非 UI scope）：Activity 关闭后子代理结果仍会回填
        scope.launch(Dispatchers.IO) {
            val resultText = try {
                val res = AIToolHandler.getInstance(appContext).executeTool(
                    AITool(
                        name = "run_ui_subagent",
                        parameters = listOf(
                            ToolParameter(name = "intent", value = text),
                            ToolParameter(name = "max_steps", value = "8")
                        )
                    )
                )
                if (res.success) {
                    (res.result as? StringResultData)?.value?.takeIf { it.isNotBlank() }
                        ?: "(empty result)"
                } else {
                    appContext.getString(R.string.voice_error_prefix) + (res.error ?: "unknown")
                }
            } catch (t: Throwable) {
                AppLogger.e(TAG, "run_ui_subagent failed", t)
                appContext.getString(R.string.voice_error_prefix) + (t.message ?: "unknown")
            }
            val finalText =
                appContext.getString(R.string.voice_subagent_result_prefix) + "\n" + resultText.take(4000)
            runCatching {
                history.addMessage(SESSION_CHAT_ID, ChatMessage(sender = "ai", content = finalText))
            }.onFailure { AppLogger.w(TAG, "persist subagent result failed: ${it.message}") }
            AppLogger.d(TAG, "subagent backfilled, len=${finalText.length}")
            withContext(Dispatchers.Main) {
                appendBubble("ai", finalText)
                trimWindow()
                _statusDetail.value = null
                _phase.value = VoicePhase.FOLLOWUP
            }
        }
    }

    // ========== 短记忆 session 管理 ==========

    private suspend fun ensureSession() {
        try {
            val exists = history.chatExists(SESSION_CHAT_ID)
            if (!exists) {
                history.ensureChatWithId(
                    SESSION_CHAT_ID,
                    appContext.getString(R.string.voice_session_title)
                )
            }
            AppLogger.d(TAG, "session ensured id=$SESSION_CHAT_ID existed=$exists")
        } catch (t: Throwable) {
            AppLogger.e(TAG, "ensureSession failed", t)
        }
    }

    /** 滑动窗口：仅保留最近 [WINDOW_ROUNDS] 轮（以用户消息为轮次边界） */
    private suspend fun trimWindow() {
        try {
            val msgs = history.loadChatMessages(SESSION_CHAT_ID)
            val userTurns = msgs.filter { it.sender == "user" }
            if (userTurns.size <= WINDOW_ROUNDS) return
            val cutoff = userTurns[userTurns.size - WINDOW_ROUNDS].timestamp
            val stale = msgs.filter { it.timestamp < cutoff }
            stale.forEach { history.deleteMessage(SESSION_CHAT_ID, it.timestamp) }
            AppLogger.d(
                TAG,
                "trimWindow: rounds=${userTurns.size} removed=${stale.size} kept=${msgs.size - stale.size}"
            )
        } catch (t: Throwable) {
            AppLogger.w(TAG, "trimWindow failed: ${t.message}")
        }
    }

    private suspend fun reloadFromHistory() {
        try {
            val msgs = history.loadChatMessages(SESSION_CHAT_ID)
            _bubbles.value = msgs.filter { it.content.isNotBlank() }.map { m ->
                val display = if (m.sender == "ai") {
                    val sanitized = runCatching { ExternalChatResponseSanitizer.sanitize(m.content, false) }
                        .getOrNull() ?: m.content
                    stripThinkMarkers(sanitized)
                } else {
                    m.content
                }
                VoiceBubble(key = m.timestamp, role = if (m.sender == "user") "user" else "ai", text = display)
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "reloadFromHistory failed: ${t.message}")
        }
    }

    // ========== UI 气泡辅助 ==========

    // 负数 key：与历史消息 timestamp（正数）不冲突，避免 LazyColumn 重复 key
    private var keyCounter = Long.MIN_VALUE + 1
    private fun nextKey(): Long = ++keyCounter

    private fun appendBubble(role: String, text: String) {
        _bubbles.value = _bubbles.value + VoiceBubble(nextKey(), role, text)
    }

    private fun updateBubble(key: Long, text: String) {
        _bubbles.value = _bubbles.value.map {
            if (it.key == key) it.copy(text = text) else it
        }
    }

    private fun dropBubble(key: Long) {
        _bubbles.value = _bubbles.value.filterNot { it.key == key }
    }
}
