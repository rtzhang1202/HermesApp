package com.ai.assistance.operit.voice.asr

import android.content.Context
import com.ai.assistance.operit.api.speech.SpeechService
import com.ai.assistance.operit.api.speech.SpeechServiceFactory
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 本地 sherpa-ncnn 识别引擎（离线降级路径）。
 * 仅在云端 ASR 出现【网络类失败】（连接失败/超时/无网）时由控制器回退到本引擎；
 * 云端 4xx 业务错误不会走到这里。
 *
 * 模型资产缺失（设备未下载 Operit/models）→ [initialize] 抛 UNAVAILABLE，
 * 由控制器降级到文字输入提示，不卡死。
 */
class LocalSherpaAsr(private val context: Context) : AsrEngine {

    companion object {
        private const val TAG = "LocalSherpaAsr"
    }

    override val kind: AsrEngineKind = AsrEngineKind.LOCAL
    override val engineName: String = "local-sherpa"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var service: SpeechService? = null
    private var resultJob: Job? = null
    private var volumeJob: Job? = null

    override suspend fun initialize() {
        val svc = withContext(Dispatchers.IO) {
            try {
                SpeechServiceFactory.getInstance(context)
            } catch (t: Throwable) {
                AppLogger.w(TAG, "SpeechServiceFactory failed: ${t.message}")
                null
            }
        } ?: throw AsrException(AsrErrorKind.UNAVAILABLE, "本地语音服务不可用")
        service = svc

        val ok = try {
            withContext(Dispatchers.IO) { svc.initialize() }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "initialize failed: ${t.message}")
            false
        }
        if (!ok) {
            AppLogger.w(TAG, "initialize returned false (sherpa model assets missing?)")
            throw AsrException(
                AsrErrorKind.UNAVAILABLE,
                "本地 sherpa 模型不可用（未下载模型资产）"
            )
        }
        AppLogger.i(TAG, "local sherpa initialized")
    }

    override suspend fun startListening(languageCode: String, listener: AsrListener) {
        val svc = service ?: throw AsrException(AsrErrorKind.UNAVAILABLE, "本地识别未初始化")
        var started = false
        var attempt = 0
        while (!started && attempt < 10) {
            if (attempt > 0) delay(160)
            started = try {
                svc.startRecognition(
                    languageCode = languageCode,
                    continuousMode = true,
                    partialResults = true
                )
            } catch (t: Throwable) {
                AppLogger.w(TAG, "startRecognition attempt $attempt failed: ${t.message}")
                false
            }
            attempt++
        }
        if (!started) throw AsrException(AsrErrorKind.UNAVAILABLE, "本地识别启动失败")

        resultJob?.cancel()
        resultJob = scope.launch {
            svc.recognitionResultFlow.collect { r ->
                if (r.isFinal && r.text.isNotBlank()) {
                    AppLogger.d(TAG, "local final: ${r.text.take(80)}")
                    listener.onFinal(r.text.trim())
                }
            }
        }
        volumeJob?.cancel()
        volumeJob = scope.launch {
            svc.volumeLevelFlow.collect { listener.onVolume(it) }
        }
        AppLogger.i(TAG, "listening started lang=$languageCode")
    }

    override suspend fun stopListening() {
        val svc = service ?: return
        // 本地连续识别的 final 可能在聆听期间已回调；stop 后的短窗口由控制器 watchdog 等待
        try {
            svc.stopRecognition()
        } catch (t: Throwable) {
            AppLogger.w(TAG, "stopRecognition failed: ${t.message}")
        }
    }

    override fun cancel() {
        resultJob?.cancel()
        resultJob = null
        volumeJob?.cancel()
        volumeJob = null
        val svc = service ?: return
        scope.launch { runCatching { svc.cancelRecognition() } }
        AppLogger.d(TAG, "cancel")
    }

    override fun shutdown() {
        cancel()
        val svc = service
        service = null
        try {
            svc?.shutdown()
        } catch (_: Throwable) {
        }
    }
}
