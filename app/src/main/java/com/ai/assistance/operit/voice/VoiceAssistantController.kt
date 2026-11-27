package com.ai.assistance.operit.voice

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.api.chat.llmprovider.MediaLinkParser
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
import com.ai.assistance.operit.voice.asr.AsrEngine
import com.ai.assistance.operit.voice.asr.AsrEngineKind
import com.ai.assistance.operit.voice.asr.AsrErrorKind
import com.ai.assistance.operit.voice.asr.AsrException
import com.ai.assistance.operit.voice.asr.AsrListener
import com.ai.assistance.operit.voice.asr.CloudMimoAsr
import com.ai.assistance.operit.voice.asr.LocalSherpaAsr
import com.ai.assistance.operit.voice.asr.MimoAsrConfig
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
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

/**
 * 语音助手状态机：唤起入场 → 聆听 → 识别（云端转写/离线出字） → 思考 → 回答流式 → 追问
 *
 * [RECOGNIZING] 是新增的一态：点按停止聆听后进入，
 * 云端 = 「云端识别中…」（上传 WAV → MiMo 转写，最长 180s）；
 * 本地 = 「离线识别中…」（等待 sherpa 最终结果）。
 * 转写落地 → [THINKING]；任何失败 → 文字兜底 + 顶部原因。
 */
enum class VoicePhase { AWAKE, LISTENING, RECOGNIZING, TRANSCRIBING, THINKING, ANSWERING, HANDED_OFF, FOLLOWUP, ERROR }

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

    private var lastHandledVoiceText: String? = null

    // ========== 可插拔 ASR 引擎（云端 MiMo 优先 / 本地 sherpa 离线降级） ==========
    private var cloudEngine: CloudMimoAsr? = null
    private var localEngine: LocalSherpaAsr? = null
    private var asrEngine: AsrEngine? = null

    /** 云端网络类失败后 30s 内直接走本地（避免每轮都等连接超时） */
    private var cloudBackoffUntil = 0L

    /** 识别态 watchdog：引擎不回调时不许静默卡死 */
    private var watchdogJob: Job? = null

    /** 本轮识别是否已结算（watchdog 与引擎回调二选一） */
    private var recognitionSettled: CompletableDeferred<Unit>? = null

    private val asrListener = object : AsrListener {
        override fun onVolume(level: Float) {
            _volume.value = level
        }

        override fun onFinal(text: String) {
            settleRecognition()
            scope.launch { onVoiceFinal(text) }
        }

        override fun onError(error: AsrException) {
            settleRecognition()
            AppLogger.w(TAG, "asr error kind=${error.kind} msg=${error.message}")
            scope.launch { onAsrError(error) }
        }
    }

    /** 结算本轮识别（取消 watchdog，幂等） */
    private fun settleRecognition() {
        watchdogJob?.cancel()
        watchdogJob = null
        val settled = recognitionSettled
        recognitionSettled = null
        settled?.complete(Unit)
    }

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

    /** Activity 销毁：释放麦克风与在途识别，但保留 session 与子代理回填能力 */
    fun onUiClosed() {
        watchdogJob?.cancel()
        watchdogJob = null
        val settled = recognitionSettled
        recognitionSettled = null
        settled?.cancel()
        val eng = asrEngine
        if (eng != null) {
            scope.launch { runCatching { eng.cancel() } }
        }
        _volume.value = 0f
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

    // ========== 语音输入（可插拔 ASR：云端 MiMo 优先，网络失败降级本地 sherpa） ==========

    fun startVoiceInput() {
        if (!_micPermissionGranted.value) {
            _phase.value = VoicePhase.FOLLOWUP
            _statusDetail.value = appContext.getString(R.string.voice_permission_needed)
            _inputMode.value = InputMode.TEXT
            return
        }
        scope.launch {
            when (_phase.value) {
                VoicePhase.LISTENING, VoicePhase.RECOGNIZING,
                VoicePhase.THINKING, VoicePhase.ANSWERING, VoicePhase.HANDED_OFF ->
                    return@launch // 防止二次唤起/识别中重复启动
                else -> {}
            }
            _statusDetail.value = null
            // 与悬浮窗语音链路一致：先让唤醒词服务让出麦克风
            try {
                AIForegroundService.ensureMicrophoneForeground(appContext, forceStart = true)
            } catch (t: Throwable) {
                AppLogger.w(TAG, "ensureMicrophoneForeground failed: ${t.message}")
            }
            delay(180)

            // 云端主路径；网络类失败退避期内直接走本地离线
            val engine = try {
                resolveEngine()
            } catch (e: AsrException) {
                AppLogger.w(TAG, "engine init failed kind=${e.kind}: ${e.message}")
                fallbackToText(reasonFor(e))
                return@launch
            } catch (t: Throwable) {
                AppLogger.e(TAG, "engine init failed", t)
                fallbackToText(
                    appContext.getString(R.string.voice_asr_unavailable) +
                        " (${t.message ?: "unknown"})"
                )
                return@launch
            }

            val language = if (Locale.getDefault().language == "zh") "zh-CN" else "en-US"
            try {
                engine.startListening(language, asrListener)
            } catch (e: AsrException) {
                AppLogger.w(TAG, "startListening failed kind=${e.kind}: ${e.message}")
                fallbackToText(reasonFor(e))
                return@launch
            } catch (t: Throwable) {
                AppLogger.e(TAG, "startListening failed", t)
                fallbackToText(appContext.getString(R.string.voice_asr_unavailable))
                return@launch
            }

            _inputMode.value = InputMode.VOICE
            _phase.value = VoicePhase.LISTENING
            _statusDetail.value = null
            AppLogger.d(TAG, "listening started engine=${engine.engineName} lang=$language")
        }
    }

    /** 引擎选择：云端 MiMo 优先；仅网络类失败后的退避期内走本地离线 */
    private suspend fun resolveEngine(): AsrEngine {
        if (System.currentTimeMillis() >= cloudBackoffUntil) {
            val cloud = cloudEngine
                ?: CloudMimoAsr { resolveCloudConfig() }.also { cloudEngine = it }
            cloud.initialize() // 配置类失败抛 CONFIG：不回退本地，直接报错给用户
            asrEngine = cloud
            return cloud
        }
        AppLogger.i(TAG, "cloud in backoff, use local engine")
        return localEngineOrThrow()
    }

    private suspend fun localEngineOrThrow(): AsrEngine {
        val local = localEngine ?: LocalSherpaAsr(appContext).also { localEngine = it }
        local.initialize() // 模型缺失抛 UNAVAILABLE → 文字兜底
        asrEngine = local
        return local
    }

    /** 云端 ASR 配置：复用 LLM 网关配置（同款密钥/基址），读不到 → CONFIG 错 */
    private suspend fun resolveCloudConfig(): MimoAsrConfig? {
        val delegate = try {
            core.getApiConfigDelegate()
        } catch (t: Throwable) {
            AppLogger.w(TAG, "ApiConfigDelegate unavailable: ${t.message}")
            null
        } ?: return null
        try {
            withTimeoutOrNull(8_000) { delegate.isInitialized.first { it } }
        } catch (_: Throwable) {
        }
        val key = delegate.apiKey.value
        val cfg = MimoAsrConfig.fromLlmConfig(key, delegate.apiEndpoint.value)
        AppLogger.i(
            TAG,
            "cloud asr config ready=${cfg != null} " +
                "key=${MimoAsrConfig.mask(key)} base=${cfg?.baseUrl ?: "-"}"
        )
        return cfg
    }

    /** 静默停止：取消录音/在途请求/看门狗，不产出结果 */
    private fun stopEngineQuietly() {
        watchdogJob?.cancel()
        watchdogJob = null
        val settled = recognitionSettled
        recognitionSettled = null
        settled?.cancel()
        val eng = asrEngine ?: return
        scope.launch { runCatching { eng.cancel() } }
    }

    fun stopVoiceInput() {
        _volume.value = 0f
        stopEngineQuietly()
    }

    /** 点按麦克风停止聆听 → 进入识别态（新增态：云端转写 / 离线出字） */
    private fun beginRecognition() {
        val eng = asrEngine
        if (eng == null) {
            fallbackToText(appContext.getString(R.string.voice_asr_unavailable))
            return
        }
        _phase.value = VoicePhase.RECOGNIZING
        _statusDetail.value = appContext.getString(
            if (eng.kind == AsrEngineKind.CLOUD) R.string.voice_status_recognizing_cloud
            else R.string.voice_status_recognizing_local
        )
        _volume.value = 0f

        val settled = CompletableDeferred<Unit>()
        recognitionSettled = settled
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            // 云端 180s 超时外再留 20s 余量；本地等 final 最多 6s
            val limitMs = if (eng.kind == AsrEngineKind.CLOUD) 200_000L else 6_000L
            val done = withTimeoutOrNull(limitMs) { settled.await() }
            if (done == null) {
                AppLogger.w(TAG, "recognition watchdog timeout engine=${eng.engineName}")
                eng.cancel() // 抑制迟到回调，避免双发
                handleAsrError(
                    AsrException(
                        AsrErrorKind.TIMEOUT,
                        appContext.getString(R.string.voice_asr_timeout)
                    )
                )
            }
        }
        AppLogger.d(TAG, "recognizing start engine=${eng.engineName}")
        scope.launch {
            try {
                eng.stopListening()
            } catch (e: AsrException) {
                handleAsrError(e)
            } catch (t: Throwable) {
                handleAsrError(
                    AsrException(AsrErrorKind.UNKNOWN, t.message ?: "stop failed", t)
                )
            }
        }
    }

    private fun handleAsrError(error: AsrException) {
        settleRecognition()
        AppLogger.w(TAG, "asr error kind=${error.kind} msg=${error.message}")
        scope.launch { onAsrError(error) }
    }

    /** 麦克风按钮开关：聆听中点按 → 停止并识别；识别中忽略点击 */
    fun toggleListening() {
        when (_phase.value) {
            VoicePhase.THINKING, VoicePhase.ANSWERING, VoicePhase.HANDED_OFF -> return
            VoicePhase.RECOGNIZING -> return
            VoicePhase.LISTENING -> beginRecognition()
            else -> startVoiceInput()
        }
    }

    fun setTextMode(enabled: Boolean) {
        if (enabled) {
            stopVoiceInput()
            _inputMode.value = InputMode.TEXT
            if (_phase.value == VoicePhase.LISTENING ||
                _phase.value == VoicePhase.RECOGNIZING
            ) {
                _phase.value = VoicePhase.FOLLOWUP
            }
        } else {
            _inputMode.value = InputMode.VOICE
            startVoiceInput()
        }
    }

    private fun fallbackToText(reason: String) {
        stopVoiceInput()
        _inputMode.value = InputMode.TEXT
        if (_phase.value == VoicePhase.AWAKE ||
            _phase.value == VoicePhase.LISTENING ||
            _phase.value == VoicePhase.RECOGNIZING
        ) {
            _phase.value = VoicePhase.FOLLOWUP
        }
        _statusDetail.value = reason
        AppLogger.w(TAG, "fallback to text input: $reason")
    }

    /** 转写落地 → 进入思考（复用同一条 submit 后续链路：VoiceRouter → session） */
    private suspend fun onVoiceFinal(text: String) {
        val t = text.trim()
        if (t.isBlank()) {
            onAsrError(AsrException(AsrErrorKind.EMPTY_AUDIO, "空转写结果"))
            return
        }
        if (t == lastHandledVoiceText) return
        lastHandledVoiceText = t
        AppLogger.d(TAG, "asr final: $t")
        stopVoiceInput()
        _phase.value = VoicePhase.RECOGNIZING // 转写落地
        _statusDetail.value = null
        submit(t)
    }

    /**
     * ASR 失败统一处理：
     * - [AsrErrorKind.NETWORK]（仅云端）→ 降级本地离线识别；本地也不可用 → 文字兜底
     * - 其余（4xx 业务/空音频/超时/配置/麦克风）→ 直接文字兜底 + 原因，不静默转本地
     */
    private suspend fun onAsrError(error: AsrException) {
        if (error.kind != AsrErrorKind.NETWORK) {
            fallbackToText(reasonFor(error))
            return
        }
        if (asrEngine?.kind != AsrEngineKind.CLOUD) {
            fallbackToText(reasonFor(error))
            return
        }
        cloudBackoffUntil = System.currentTimeMillis() + 30_000
        val language = if (Locale.getDefault().language == "zh") "zh-CN" else "en-US"
        AppLogger.i(TAG, "cloud network failure, falling back to local asr")
        val local = try {
            localEngineOrThrow()
        } catch (e: AsrException) {
            AppLogger.w(TAG, "local asr unavailable: ${e.message}")
            fallbackToText(
                appContext.getString(R.string.voice_asr_network_failed) + " / " +
                    appContext.getString(R.string.voice_asr_local_unavailable)
            )
            return
        }
        try {
            local.startListening(language, asrListener)
            _inputMode.value = InputMode.VOICE
            _phase.value = VoicePhase.LISTENING
            _statusDetail.value = appContext.getString(R.string.voice_asr_network_fallback)
            AppLogger.i(TAG, "fell back to local asr after cloud network failure")
        } catch (e: AsrException) {
            AppLogger.w(TAG, "local asr start failed: ${e.message}")
            fallbackToText(
                appContext.getString(R.string.voice_asr_network_failed) + " / " +
                    appContext.getString(R.string.voice_asr_local_unavailable)
            )
        }
    }

    /** 错误 → 用户可读原因（顶部大字展示，含服务端细节片段） */
    private fun reasonFor(e: AsrException): String {
        val base = when (e.kind) {
            AsrErrorKind.CONFIG -> appContext.getString(R.string.voice_asr_config_missing)
            AsrErrorKind.EMPTY_AUDIO -> appContext.getString(R.string.voice_asr_empty_audio)
            AsrErrorKind.TIMEOUT -> appContext.getString(R.string.voice_asr_timeout)
            AsrErrorKind.NO_MIC -> appContext.getString(R.string.voice_asr_no_mic)
            AsrErrorKind.NETWORK -> appContext.getString(R.string.voice_asr_network_failed)
            AsrErrorKind.SERVER -> appContext.getString(R.string.voice_asr_server_error)
            AsrErrorKind.UNAVAILABLE ->
                if (asrEngine?.kind == AsrEngineKind.LOCAL) {
                    appContext.getString(R.string.voice_asr_local_unavailable)
                } else {
                    appContext.getString(R.string.voice_asr_unavailable)
                }
            AsrErrorKind.UNKNOWN -> appContext.getString(R.string.voice_asr_unavailable)
        }
        val detail = e.message?.trim()?.take(120).orEmpty()
        return if (detail.isEmpty()) base else "$base（$detail）"
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
        val turnStartedAt = System.currentTimeMillis()

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
                val body = stripThinkMarkers(answer)
                updateBubble(aiKey, body)
                // 工具回执（如 som_screenshot 的 <link type=image id=...>）被外部通道消毒器剥离，
                // 语音气泡只留正文会丢图——从本轮落库的原始内容里捞出图片链接补挂到气泡上。
                attachTurnImages(aiKey, turnStartedAt)
                val afterImages = _bubbles.value.firstOrNull { it.key == aiKey }?.text.orEmpty()
                if (afterImages.isBlank()) {
                    // 空回答兜底：正文为空（含 think 剥离后为空）→ 友好重试提示 + 原始内容摘要日志
                    AppLogger.w(
                        TAG,
                        "empty answer rawLen=${answer.length} " +
                            "raw=${answer.take(200).replace(Regex("\\s+"), " ")}"
                    )
                    updateBubble(aiKey, appContext.getString(R.string.voice_answer_empty))
                }
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

    // ========== 气泡图片（工具回执里的 <link type=image>） ==========

    /**
     * 外部通道消毒器会整块剥掉 <tool_result>，其中的图片链接因此丢失（语音气泡只剩文字）。
     * 这里从本轮落库的原始消息内容里提取图片 id，补成无引号 link 标签挂到气泡末尾，
     * 由 VoiceMessageContent 渲染成图片（ImagePoolManager 取图 + 主聊天图片管线）。
     */
    private suspend fun attachTurnImages(bubbleKey: Long, turnStartedAt: Long) {
        val ids = loadTurnImageIds(turnStartedAt)
        if (ids.isEmpty()) return
        val current = _bubbles.value.firstOrNull { it.key == bubbleKey }?.text.orEmpty()
        val merged = appendImageLinks(current, ids)
        if (merged != current) {
            updateBubble(bubbleKey, merged)
            AppLogger.d(TAG, "attached turn images: ${ids.joinToString(",")}")
        }
    }

    /** 轮询本轮落库的 ai 消息（核心链路在回合结束后才写库） */
    private suspend fun loadTurnImageIds(turnStartedAt: Long): List<String> {
        repeat(8) { attempt ->
            val msgs = runCatching { history.loadChatMessages(SESSION_CHAT_ID) }
                .getOrNull()
                .orEmpty()
            val ids = msgs.asSequence()
                .filter { it.sender == "ai" && it.timestamp >= turnStartedAt - 1_000L }
                .flatMap {
                    MediaLinkParser.extractImageLinkIds(decodeEscapedMarkup(it.content)).asSequence()
                }
                .distinct()
                .toList()
            if (ids.isNotEmpty()) return ids
            delay(if (attempt == 0) 300L else 250L)
        }
        return emptyList()
    }

    /**
     * 工具回执在消息内容里是 JSON 字符串（`{"result":"...\u003clink type\u003dimage id\u003d..."}`），
     * XML 的 `<`/`>`/`=` 被转成 \u003c 一类转义，直接跑 MediaLinkParser 抓不到。
     * 这里只还原标记相关转义（不影响正文语义），供图片 id 提取使用。
     */
    private fun decodeEscapedMarkup(raw: String): String {
        if (!raw.contains("\\u00")) return raw
        return raw
            .replace("\\u003c", "<").replace("\\u003C", "<")
            .replace("\\u003e", ">").replace("\\u003E", ">")
            .replace("\\u003d", "=").replace("\\u003D", "=")
            .replace("\\u0022", "\"").replace("\\u0027", "'")
    }

    /** 去重追加图片链接标签，文本原样保留 */
    private fun appendImageLinks(display: String, ids: List<String>): String {
        if (ids.isEmpty()) return display
        val existing = MediaLinkParser.extractImageLinkIds(display).toMutableSet()
        val missing = ids.filter { existing.add(it) }
        if (missing.isEmpty()) return display
        val suffix = missing.joinToString("\n") { "<link type=image id=$it></link>" }
        return if (display.isBlank()) suffix else display.trimEnd() + "\n\n" + suffix
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
                    // 未闭合的 open 标签：只删 open 本身，不截断尾部——
                    // 否则 think 后紧跟的工具调用/正文会被整段吞掉（缺陷3 畸形残留根因之一）
                    s = s.substring(0, i) + s.substring(i + open.length)
                    continue
                }
                s = s.substring(0, i) + s.substring(j + close.length)
            }
        }
        for (t in pairedTags) {
            s = s.replace("<" + t + ">", "").replace("</" + t + ">", "")
        }
        return s.trim()
    }

    /** 轮次结束后的统一收尾：裁剪窗口；单发模式——不再自动拉起拾音，停在结果态 */
    private fun afterTurn() {
        scope.launch {
            delay(400)
            trimWindow()
            // 单发模式：回答完成后停在 FOLLOWUP 结果态，
            // 由界面「再说一次 / 关闭」按钮决定是否重新拾音（缺陷1：不再连续对话）
            AppLogger.d(TAG, "afterTurn: stay at result state (single-shot mode)")
        }
    }

    /** 结果态「再说一次」：重新进入聆听 */
    fun speakAgain() {
        if (_phase.value == VoicePhase.FOLLOWUP || _phase.value == VoicePhase.ERROR) {
            startVoiceInput()
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
                    // 消毒器剥掉 <tool_result> 后只剩正文：从原始内容补回图片链接，重开界面也能看到图
                    appendImageLinks(
                        stripThinkMarkers(sanitized),
                        MediaLinkParser.extractImageLinkIds(decodeEscapedMarkup(m.content))
                    )
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
