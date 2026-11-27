package com.ai.assistance.operit.voice.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import com.ai.assistance.operit.util.AppLogger
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 云端 MiMo ASR 引擎（默认主路径）：
 * 录音（16kHz 单声道 PCM16）→ WAV → base64 → POST {base}/v1/chat/completions
 *
 * 契约（mimo-asr-api.md，PC 侧双网关实测通过）：
 * - model=mimo-v2.5-asr；messages[0].content[0] = {type:input_audio, input_audio:{data:<裸base64>, format:"wav"}}
 * - data 必须是裸 base64（data-URI 形态会被误报 Invalid API Key）；format 必填且声明真实容器
 * - extra_body.asr_options.language=auto（顶层 extra_body 字段，非 OpenAI 标准字段）
 * - 超时 180s（实测有 58s 响应）；raw ≤10MB
 * - 429 = 端点活着：同 URL 退避重试（1.5s/3s），绝不换网关（密钥前缀绑定网关，换址只会 401）
 * - HTTP 4xx/5xx → [AsrErrorKind.SERVER]（不回退本地，直接报错给用户）
 * - 连接失败/超时 → [AsrErrorKind.NETWORK]（上层允许回退本地离线识别）
 */
class CloudMimoAsr(
    private val configProvider: suspend () -> MimoAsrConfig?
) : AsrEngine {

    companion object {
        private const val TAG = "CloudMimoAsr"
        const val SAMPLE_RATE = 16000

        /** 录音硬上限：到点交付 TIMEOUT 错误态（不静默挂死） */
        private const val MAX_RECORD_MS = 60_000L

        /** 低于 0.25s 的录音视为未录到音频 */
        private const val MIN_AUDIO_BYTES = SAMPLE_RATE * 2 / 4

        /** ASR 请求超时 180s（实测响应最长 58s，60s 会误杀） */
        private const val TIMEOUT_SEC = 180L

        // ---- 能量 VAD 门控（缺陷2：静音不送 ASR，防 LLM-ASR 底噪幻觉） ----
        /**
         * 门槛不再固定 500：正常音量/离麦稍远时一句话的 RMS 常在 200~400，固定 500 会整段误杀；
         * 且旧逻辑只缓存「过门槛」的块 → 送上去的音频断断续续，转写大面积错字。
         *
         * 二轮调敏（真机实测 rmsPeak=134 被 180 拦成「无语音」）：
         * - 下限 180 → 80：正常音量/稍远/轻声不再误杀，仍高于静音房底噪（实测 20~40）两倍以上；
         * - 噪声底系数 2.5 → 1.5：环境噪声抬高时门槛跟得上，安静时贴着下限走；
         * - 噪声底改用「30s 窗口 p80 分位 + 600ms 校准期」，修掉旧逻辑「底噪高于下限时门槛永不更新」的锁死；
         * - 门控再加「峰值 ≥ 出现时门槛 ×1.5」：环境噪峰值只有 1.2 倍，真人声 3 倍以上。
         */
        private const val MIN_SPEECH_THRESHOLD = 80.0
        private const val MAX_SPEECH_THRESHOLD = 600.0
        private const val NOISE_FACTOR = 1.5
        /** 噪声底滚动窗口：30s（300 块 × 100ms） */
        private const val NOISE_HISTORY_CHUNKS = 300
        /** 噪声底取窗口内 p80 分位（环境噪声上沿）：取中位数时安静的实测房噪峰值仍会越过门槛误触发 */
        private const val NOISE_PERCENTILE = 0.8
        /** 起始校准期：前 600ms 只采样不判语音（避免一进门就把环境噪声判成语音） */
        private const val WARMUP_CHUNKS = 6
        /** 语音起点前预卷块数（每块 100ms）：避免「说什么」的起音被切 */
        private const val PREROLL_CHUNKS = 4
        /** 语音尾音拖尾块数：短暂停顿也收进去，避免句中换气被剪断（300ms→500ms） */
        private const val HANGOVER_CHUNKS = 5
        /** 最短有效语音时长：低于该值视为未听到语音，不送云端 */
        private const val MIN_SPEECH_MS = 300
        /**
         * 峰值相对门槛的最小倍数（判「真人声」的核心判据）：
         * 环境噪的峰值只比门槛高 10~30%（实测静音轮 1.17~1.34），真实语音通常 2 倍以上
         * （手机外放 69% 2.7~5.3×，PC 外放 80% 3.3~5.3×）。
         * 不用「最短连续段」：弱耦合时语音块本身断续（实测连续段仅 300ms），会把真语音误杀。
         */
        private const val MIN_PEAK_MARGIN = 1.5

        // ---- 上传前电平归一（软件 AGC）：远处/轻声捕获到的语音常只有 -40dBFS 级，云端识别率骤降 ----
        /** 目标 RMS（16bit，约 -27dBFS）：语音清晰可辨又不易削顶 */
        private const val TARGET_UPLOAD_RMS = 1500.0
        /** 峰值上限，避免放大后削波失真 */
        private const val PEAK_CEILING = 30000.0
        /** 最大放大倍数：再低就是纯底噪，放大只会把噪声一起抬起来 */
        private const val MAX_UPLOAD_GAIN = 16.0
    }

    override val kind: AsrEngineKind = AsrEngineKind.CLOUD
    override val engineName: String = "cloud-mimo"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listener: AsrListener? = null
    private var config: MimoAsrConfig? = null

    // ---- 录音状态（recordLoop 线程 + 调用线程共享） ----
    @Volatile private var stopRequested = false
    @Volatile private var recording = false
    @Volatile private var recordJob: Job? = null
    @Volatile private var audioRecord: AudioRecord? = null
    /** 本次录音挂上的系统降噪 effect（NoiseSuppressor）；null = 设备不支持或启用失败 */
    @Volatile private var noiseSuppressor: NoiseSuppressor? = null
    @Volatile private var noiseSuppressorActive = false
    private val pcmBuffer = ByteArrayOutputStream()
    @Volatile private var startAtMs = 0L

    // ---- VAD 门控统计（recordLoop 写，stopListening 在 join 后读） ----
    @Volatile private var vadRmsPeak = 0.0
    @Volatile private var vadSpeechMs = 0L
    @Volatile private var vadThreshold = 0.0
    @Volatile private var vadNoiseFloor = 0.0
    @Volatile private var vadLongestRunMs = 0L
    /** RMS 峰值出现那一刻的门槛（判「峰值显著越阈」用；不能用录音末尾门槛比，底噪会漂移） */
    @Volatile private var vadPeakThreshold = 0.0
    /** 录音期间 NS 是否真正生效（releaseRecorder 会把 noiseSuppressorActive 复位，统计要留存） */
    @Volatile private var vadNsEnabled = false

    // ---- 识别状态 ----
    @Volatile private var recognitionJob: Job? = null
    @Volatile private var currentCall: Call? = null

    /** 每轮只交付一次结果（final/error 二选一），防止超时与迟到回调双发 */
    private val settled = AtomicBoolean(false)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    /** 解析云端配置（复用 LLM 网关配置）；读不到 → CONFIG 错误（不回退本地，直接报错） */
    override suspend fun initialize() {
        val cfg = configProvider()
            ?: throw AsrException(
                AsrErrorKind.CONFIG,
                "云端识别配置不可用（未读到 MiMo API 密钥/基址）"
            )
        config = cfg
        AppLogger.i(
            TAG,
            "config ready baseUrl=${cfg.baseUrl} key=${MimoAsrConfig.mask(cfg.apiKey)}"
        )
    }

    @SuppressLint("MissingPermission")
    override suspend fun startListening(languageCode: String, listener: AsrListener) {
        if (recording) return
        config ?: throw AsrException(AsrErrorKind.CONFIG, "云端识别未初始化")
        this.listener = listener
        settled.set(false)
        stopRequested = false

        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = try {
            AudioRecord.getMinBufferSize(SAMPLE_RATE, channel, encoding)
        } catch (t: Throwable) {
            throw AsrException(AsrErrorKind.NO_MIC, "查询录音参数失败: ${t.message}", t)
        }
        if (minBuf <= 0) {
            throw AsrException(AsrErrorKind.NO_MIC, "设备不支持 16kHz 录音 (code=$minBuf)")
        }
        val bufSize = maxOf(minBuf, SAMPLE_RATE * 2 / 5 * 4) // ≥400ms
        // ASR 专用音源：VOICE_RECOGNITION 不做 AEC/AGC。
        // VOICE_COMMUNICATION 会开回声消除（把外放/近场语音一起削掉）并做自动增益（压字），
        // 实测同一句话转写大面积错字；拿不到就退回 MIC（纯麦克风，无预处理）。
        val rec =
                createRecorder(MediaRecorder.AudioSource.VOICE_RECOGNITION, channel, encoding, bufSize)
                        ?: createRecorder(MediaRecorder.AudioSource.MIC, channel, encoding, bufSize)
                        ?: throw AsrException(AsrErrorKind.NO_MIC, "创建录音器失败（无可用音源）")
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw AsrException(AsrErrorKind.NO_MIC, "录音器初始化失败（无麦克风？）")
        }
        // 采集端降噪：NoiseSuppressor 必须挂在本次 AudioRecord 的 audioSessionId 上，
        // 且在 startRecording() 之前 enable（之后挂会 ERROR_INVALID_OPERATION）。
        // 启用失败只降级（继续用原始音频），但原因必须落日志，不能静默。
        attachNoiseSuppressor(rec)
        try {
            rec.startRecording()
        } catch (t: Throwable) {
            releaseNoiseSuppressor()
            rec.release()
            throw AsrException(AsrErrorKind.NO_MIC, "开始录音失败: ${t.message}", t)
        }

        audioRecord = rec
        synchronized(pcmBuffer) { pcmBuffer.reset() }
        startAtMs = System.currentTimeMillis()
        vadRmsPeak = 0.0
        vadSpeechMs = 0L
        recording = true
        AppLogger.i(TAG, "record start buf=$bufSize lang=$languageCode")
        recordJob = scope.launch { recordLoop(rec, listener) }
    }

    /** 建 AudioRecord：失败/未初始化返回 null，让调用方降级到下一个音源 */
    private fun createRecorder(
            source: Int,
            channel: Int,
            encoding: Int,
            bufSize: Int
    ): AudioRecord? {
        val rec =
                try {
                    AudioRecord(source, SAMPLE_RATE, channel, encoding, bufSize)
                } catch (t: Throwable) {
                    AppLogger.w(TAG, "createRecorder source=$source failed: ${t.message}")
                    return null
                }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            AppLogger.w(TAG, "createRecorder source=$source not initialized, fallback")
            runCatching { rec.release() }
            return null
        }
        AppLogger.i(TAG, "recorder source=$source buf=$bufSize rate=$SAMPLE_RATE")
        return rec
    }

    /**
     * 显式启用系统降噪（android.media.audiofx.NoiseSuppressor），挂在本次录音的 audioSessionId 上。
     * NS 只做稳态底噪抑制（空调/风扇/电流声），语音谱峰保留，不会把说话声当噪降掉；
     * 失败一律降级为原始音频并打日志说明原因（设备无该 effect / 会话无效 / 被占用）。
     */
    private fun attachNoiseSuppressor(rec: AudioRecord): Boolean {
        val sessionId = try {
            rec.audioSessionId
        } catch (t: Throwable) {
            AppLogger.w(TAG, "NoiseSuppressor skip: audioSessionId threw ${t.message}")
            -1
        }
        if (sessionId <= 0) {
            AppLogger.w(TAG, "NoiseSuppressor skip: invalid audioSessionId=$sessionId")
            return false
        }
        return try {
            val ns = NoiseSuppressor.create(sessionId)
            if (ns == null) {
                AppLogger.w(
                    TAG,
                    "NoiseSuppressor unavailable: create() returned null session=$sessionId " +
                        "(device/effect not supported) → 继续用原始音频"
                )
                false
            } else {
                val status = ns.setEnabled(true)
                val actual = ns.enabled
                if (status == AudioEffect.SUCCESS && actual) {
                    noiseSuppressor = ns
                    noiseSuppressorActive = true
                    AppLogger.i(
                        TAG,
                        "NoiseSuppressor enabled session=$sessionId status=SUCCESS enabled=$actual"
                    )
                    true
                } else {
                    AppLogger.w(
                        TAG,
                        "NoiseSuppressor enable failed session=$sessionId status=$status " +
                            "enabled=$actual → 继续用原始音频"
                    )
                    runCatching { ns.release() }
                    false
                }
            }
        } catch (t: Throwable) {
            AppLogger.w(
                TAG,
                "NoiseSuppressor create/enable threw ${t.javaClass.simpleName}: ${t.message} " +
                    "→ 继续用原始音频"
            )
            false
        }
    }

    /** 释放降噪 effect（录音停止后、AudioRecord release 前调用；释放顺序错了会让 HAL 报错） */
    private fun releaseNoiseSuppressor() {
        val ns = noiseSuppressor ?: return
        noiseSuppressor = null
        noiseSuppressorActive = false
        runCatching { ns.enabled = false }
        runCatching { ns.release() }
        AppLogger.i(TAG, "NoiseSuppressor released")
    }

    /** 录音循环：100ms 块读取 → 能量 VAD 门控（仅语音段入缓冲）+ 音量回调；读失败/超上限交付错误态 */
    private fun recordLoop(rec: AudioRecord, lst: AsrListener) {
        val chunk = ByteArray(SAMPLE_RATE * 2 / 10)
        var readFailures = 0
        // ---- VAD 统计（仅本线程写，stopListening 在 join 后读，无需加锁） ----
        var rmsPeak = 0.0          // 全程 RMS 峰值（调参日志用）
        var speechMs = 0L          // 判定为语音的累计时长
        var speechChunks = 0
        var peakThreshold = 0.0    // 峰值出现时的门槛
        var runMs = 0L             // 当前连续语音段（只累加真正过门槛的块）
        var longestRunMs = 0L      // 全程最长连续语音段
        // ---- 自适应门控状态 ----
        var noiseFloor = 0.0                              // 噪声底（滚动 p80）
        var threshold = MIN_SPEECH_THRESHOLD              // 当前生效门槛
        var hangoverLeft = 0                              // 尾音拖尾剩余块
        var warmupLeft = WARMUP_CHUNKS                    // 起始校准剩余块
        val warmupSamples = ArrayList<Double>(WARMUP_CHUNKS)
        val rmsHistory = ArrayDeque<Double>(NOISE_HISTORY_CHUNKS + 1)
        val preRoll = ArrayDeque<ByteArray>()             // 语音起点前的预卷
        try {
            while (recording && !stopRequested) {
                val n = try {
                    rec.read(chunk, 0, chunk.size)
                } catch (t: Throwable) {
                    AppLogger.w(TAG, "read threw: ${t.message}")
                    -1
                }
                if (n <= 0) {
                    if (++readFailures >= 20) { // 连续约 2s 读不到数据
                        deliverError(
                            AsrException(AsrErrorKind.NO_MIC, "麦克风读取失败 (code=$n)"),
                            lst
                        )
                        break
                    }
                    try {
                        Thread.sleep(20)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
                readFailures = 0
                val rms = rmsOf(chunk, n)
                if (rms > rmsPeak) {
                    rmsPeak = rms
                    peakThreshold = threshold
                }
                // ---- 自适应门槛：底噪 ×1.5，夹在 [80,600] ----
                // 底噪 = 近 30s 块 RMS 的 p20 分位（语音只占少数块，p20 稳定贴住环境噪声；
                // 旧实现「只在低于门槛时更新」在底噪高于下限时会锁死在 80，实测静音房被 80~157 的底噪连续触发）。
                rmsHistory.addLast(rms)
                if (rmsHistory.size > NOISE_HISTORY_CHUNKS) rmsHistory.removeFirst()
                val warmingUp = warmupLeft > 0
                if (warmingUp) {
                    warmupSamples.add(rms)
                    warmupLeft--
                    if (warmupLeft == 0) {
                        val sortedWarmup = warmupSamples.sorted()
                        noiseFloor = sortedWarmup[(sortedWarmup.size / 2).coerceIn(0, sortedWarmup.size - 1)]
                        threshold =
                            (noiseFloor * NOISE_FACTOR)
                                .coerceIn(MIN_SPEECH_THRESHOLD, MAX_SPEECH_THRESHOLD)
                        AppLogger.i(
                            TAG,
                            "vad warmup done floor=$noiseFloor threshold=$threshold " +
                                "samples=${sortedWarmup.map { it.toLong() }}"
                        )
                    }
                } else {
                    val sorted = rmsHistory.sorted()
                    // 起步阶段窗口太短，p80 会等于最近最响的块（可能是语音）→ 先按中位数，攒满 5s 再切 p80
                    val pct = if (sorted.size >= 50) NOISE_PERCENTILE else 0.5
                    val p = sorted[((sorted.size - 1) * pct).toInt()]
                    noiseFloor = noiseFloor * 0.7 + p * 0.3
                    threshold =
                        (noiseFloor * NOISE_FACTOR)
                            .coerceIn(MIN_SPEECH_THRESHOLD, MAX_SPEECH_THRESHOLD)
                }
                val chunkMs = n * 1000L / 2 / SAMPLE_RATE
                val voiced = !warmingUp && rms >= threshold
                if (voiced) {
                    // 首块语音：把预卷补进来，别把起音吃掉
                    if (preRoll.isNotEmpty()) {
                        synchronized(pcmBuffer) {
                            for (p in preRoll) pcmBuffer.write(p, 0, p.size)
                        }
                        preRoll.clear()
                    }
                    synchronized(pcmBuffer) { pcmBuffer.write(chunk, 0, n) }
                    speechMs += chunkMs
                    speechChunks++
                    runMs += chunkMs
                    if (runMs > longestRunMs) longestRunMs = runMs
                    hangoverLeft = HANGOVER_CHUNKS
                } else if (hangoverLeft > 0) {
                    // 语音尾音：短暂静音照样收入，避免词尾被切
                    synchronized(pcmBuffer) { pcmBuffer.write(chunk, 0, n) }
                    hangoverLeft--
                    // 拖尾不算「连续语音段」（否则环境噪瞬态 + 拖尾会被拼成假长段）
                    runMs = 0
                } else {
                    runMs = 0
                    preRoll.addLast(chunk.copyOf(n))
                    if (preRoll.size > PREROLL_CHUNKS) preRoll.removeFirst()
                }
                lst.onVolume(volumeOf(chunk, n))
                if (System.currentTimeMillis() - startAtMs >= MAX_RECORD_MS) {
                    AppLogger.w(TAG, "record hit ${MAX_RECORD_MS}ms cap, delivering TIMEOUT")
                    deliverError(
                        AsrException(
                            AsrErrorKind.TIMEOUT,
                            "录音超时（${MAX_RECORD_MS / 1000}s 上限）"
                        ),
                        lst
                    )
                    break
                }
            }
        } finally {
            vadRmsPeak = rmsPeak
            vadSpeechMs = speechMs
            vadThreshold = threshold
            vadNoiseFloor = noiseFloor
            vadLongestRunMs = longestRunMs
            vadNsEnabled = noiseSuppressorActive
            vadPeakThreshold = peakThreshold
            AppLogger.i(
                TAG,
                "vad stats rmsPeak=$rmsPeak threshold=$threshold floor=$noiseFloor " +
                    "speechMs=$speechMs speechChunks=$speechChunks longestRunMs=$longestRunMs " +
                    "peakThreshold=$peakThreshold ns=$noiseSuppressorActive " +
                    "durMs=${System.currentTimeMillis() - startAtMs}"
            )
            recording = false
            releaseRecorder(rec)
            AppLogger.d(TAG, "record loop end")
        }
    }

    /** 停止录音 → 上传转写（结果经 listener 回调；本函数在识别真正结束后才返回的语义由控制器 watchdog 承担） */
    override suspend fun stopListening() {
        val lst = listener
        stopRequested = true
        recordJob?.join()
        if (settled.get()) return // 已因超时/读失败交付过错误
        if (lst == null) {
            deliverError(AsrException(AsrErrorKind.UNKNOWN, "无识别监听器"), null)
            return
        }
        val pcmRaw = synchronized(pcmBuffer) { pcmBuffer.toByteArray() }
        val durMs = System.currentTimeMillis() - startAtMs
        AppLogger.i(TAG, "record stop bytes=${pcmRaw.size} durMs=$durMs")
        // ---- VAD 门控判定：有效语音不足 / 峰值只是噪声级 → 未听到语音，不送云端 ----
        // 峰值必须比「它出现那一刻」的门槛高 1.5 倍以上：环境噪峰值≈门槛（实测 1.2×），
        // 弱耦合语音峰值仍是门槛的 3 倍以上（PC 外放 80% 实测 700/214）。
        val peakMargin = if (vadPeakThreshold > 0) vadRmsPeak / vadPeakThreshold else 0.0
        if (vadSpeechMs < MIN_SPEECH_MS || peakMargin < MIN_PEAK_MARGIN) {
            AppLogger.i(
                TAG,
                "vad reject: speechMs=$vadSpeechMs (<${MIN_SPEECH_MS}) " +
                    "peakMargin=${"%.2f".format(peakMargin)} (<$MIN_PEAK_MARGIN) " +
                    "longestRunMs=$vadLongestRunMs rmsPeak=$vadRmsPeak " +
                    "peakThreshold=$vadPeakThreshold threshold=$vadThreshold " +
                    "floor=$vadNoiseFloor ns=$vadNsEnabled → not sending to ASR"
            )
            deliverError(
                AsrException(
                    AsrErrorKind.EMPTY_AUDIO,
                    "未听到语音（有效语音 ${vadSpeechMs}ms，连续段 ${vadLongestRunMs}ms，" +
                        "RMS 峰值 ${vadRmsPeak.toLong()}，门槛 ${vadThreshold.toLong()}）"
                ),
                lst
            )
            return
        }
        if (pcmRaw.size < MIN_AUDIO_BYTES) {
            deliverError(
                AsrException(AsrErrorKind.EMPTY_AUDIO, "未录到有效音频 (${pcmRaw.size}B)"),
                lst
            )
            return
        }
        // 上传前电平归一：远处/轻声的语音常只有 rms 100~200，云端 ASR 在这种电平上错字率很高
        val pcm = normalizeLevel(pcmRaw)
        val cfg = config ?: run {
            deliverError(AsrException(AsrErrorKind.CONFIG, "云端识别配置缺失"), lst)
            return
        }
        recognitionJob = scope.launch { recognize(cfg, pcm, lst) }
    }

    /** 上传并转写（同步 HTTP，跑在 IO scope；180s 超时） */
    private fun recognize(cfg: MimoAsrConfig, pcm: ByteArray, lst: AsrListener) {
        val t0 = System.currentTimeMillis()
        val url = cfg.baseUrl.trimEnd('/') + "/chat/completions"
        try {
            val wav = encodeWav(pcm, SAMPLE_RATE)
            // raw 限制 10MB（16k 单声道 60s 仅约 1.9MB，这里只做保险）
            if (wav.size > 10 * 1024 * 1024) {
                deliverError(
                    AsrException(AsrErrorKind.SERVER, "音频过大 (${wav.size}B > 10MB)"), lst
                )
                return
            }
            val b64 = Base64.encodeToString(wav, Base64.NO_WRAP)
            var withPrompt = true
            var body = buildRequestBody(b64, withPrompt)
            AppLogger.i(
                TAG,
                "recognize send url=$url wavBytes=${wav.size} b64Len=${b64.length} " +
                    "prompt=$withPrompt key=${MimoAsrConfig.mask(cfg.apiKey)}"
            )

            var attempt = 0
            while (attempt < 3) {
                val (code, text) = try {
                    postOnce(url, cfg.apiKey, body)
                } catch (e: IOException) {
                    // 连接失败/超时 → 网络类：同址退避重试，仍失败按 NETWORK 上抛（上层回退本地）
                    val msg = e.message ?: e.javaClass.simpleName
                    AppLogger.w(
                        TAG, "recognize io error attempt=$attempt url=$url msg=$msg"
                    )
                    if (attempt < 2) {
                        attempt++
                        try {
                            Thread.sleep(1000L shl (attempt - 1))
                        } catch (_: InterruptedException) {
                        }
                        continue
                    }
                    deliverError(
                        AsrException(AsrErrorKind.NETWORK, "云端识别网络失败: $msg", e), lst
                    )
                    return
                }
                AppLogger.i(
                    TAG,
                    "recognize resp url=$url status=$code ms=${System.currentTimeMillis() - t0}"
                )
                if (code == 429 && attempt < 2) {
                    // 429 = 端点活着：同 URL 退避重试，绝不换网关（密钥前缀绑定网关）
                    val waitMs = 1500L shl attempt
                    AppLogger.w(
                        TAG, "429 rate-limited (endpoint alive), backoff ${waitMs}ms same url"
                    )
                    attempt++
                    try {
                        Thread.sleep(waitMs)
                    } catch (_: InterruptedException) {
                    }
                    continue
                }
                if (code !in 200..299) {
                    // 4xx 若是文本提示消息不被接受 → 去掉 prompt 只留 VAD 音频重试一次
                    if (code in 400..499 && code != 429 && withPrompt) {
                        withPrompt = false
                        body = buildRequestBody(b64, withPrompt)
                        AppLogger.w(
                            TAG,
                            "HTTP $code with prompt message, retrying once without prompt"
                        )
                        continue
                    }
                    // 业务错误（鉴权/配额/格式/限流）→ 不回退本地，直接报错给用户
                    deliverError(
                        AsrException(AsrErrorKind.SERVER, "HTTP $code ${text.take(160)}"), lst
                    )
                    return
                }
                val content = try {
                    JSONObject(text)
                        .optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("message")?.optString("content", "")
                        ?.trim().orEmpty()
                } catch (t: Throwable) {
                    deliverError(
                        AsrException(AsrErrorKind.UNKNOWN, "响应解析失败: ${t.message}", t), lst
                    )
                    return
                }
                if (content.isEmpty()) {
                    deliverError(
                        AsrException(AsrErrorKind.EMPTY_AUDIO, "云端未识别到语音"), lst
                    )
                    return
                }
                AppLogger.i(
                    TAG,
                    "recognize done ms=${System.currentTimeMillis() - t0} len=${content.length}"
                )
                deliverFinal(content, lst)
                return
            }
            // 429 连续退避耗尽
            deliverError(AsrException(AsrErrorKind.SERVER, "云端识别失败：连续限流(429)"), lst)
        } catch (t: Throwable) {
            AppLogger.e(TAG, "recognize failed", t)
            deliverError(
                AsrException(AsrErrorKind.UNKNOWN, t.message ?: "识别失败", t), lst
            )
        } finally {
            currentCall = null
        }
    }

    /** 单次 POST（同步）：返回 (HTTP 状态码, 响应体)；连接类失败抛 IOException */
    private fun postOnce(url: String, apiKey: String, body: String): Pair<Int, String> {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val call = http.newCall(request)
        currentCall = call
        return call.execute().use { resp -> resp.code to resp.body?.string().orEmpty() }
    }

    /** 契约请求体：裸 base64 + format=wav + 顶层 extra_body.asr_options；
     *  [withPrompt] 时在 input_audio 前插入文本指令（LLM 型 ASR 提示，4xx 时由调用方去掉重试） */
    private fun buildRequestBody(b64: String, withPrompt: Boolean): String {
        val content = org.json.JSONArray()
        if (withPrompt) {
            content.put(
                JSONObject()
                    .put("type", "text")
                    .put(
                        "text",
                        "只输出中文或英文的转写结果，没有说话时输出空字符串"
                    )
            )
        }
        content.put(
            JSONObject()
                .put("type", "input_audio")
                .put(
                    "input_audio",
                    JSONObject().put("data", b64).put("format", "wav")
                )
        )
        return JSONObject()
            .put("model", MimoAsrConfig.ASR_MODEL)
            .put(
                "messages",
                org.json.JSONArray().put(
                    JSONObject().put("role", "user").put("content", content)
                )
            )
            .put(
                "extra_body",
                JSONObject().put("asr_options", JSONObject().put("language", "zh"))
            )
            .put("stream", false)
            .toString()
    }

    /**
     * 上传前电平归一（软件 AGC）：只在整体偏轻时放大，峰值钳在 [PEAK_CEILING] 以内避免削波。
     * 与 NoiseSuppressor 配合：NS 压底噪，这里补语音电平，两者都不改变语音谱形状。
     */
    private fun normalizeLevel(pcm: ByteArray): ByteArray {
        if (pcm.size < 4) return pcm
        var sum = 0.0
        var peak = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val s = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            sum += s.toDouble() * s
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
            i += 2
        }
        val frames = pcm.size / 2
        if (frames == 0) return pcm
        val rms = Math.sqrt(sum / frames)
        if (rms < 1.0 || peak <= 0) return pcm
        val gainByRms = TARGET_UPLOAD_RMS / rms
        val gainByPeak = PEAK_CEILING / peak
        val gain = minOf(gainByRms, gainByPeak).coerceIn(1.0, MAX_UPLOAD_GAIN)
        if (gain < 1.05) {
            AppLogger.i(TAG, "level ok rms=${rms.toLong()} peak=$peak gain=1.0 (no boost)")
            return pcm
        }
        val out = ByteArray(pcm.size)
        i = 0
        while (i + 1 < pcm.size) {
            val s = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            val v = (s * gain).toInt().coerceIn(-32768, 32767)
            out[i] = (v and 0xFF).toByte()
            out[i + 1] = ((v shr 8) and 0xFF).toByte()
            i += 2
        }
        AppLogger.i(
            TAG,
            "level boost rms=${rms.toLong()}→${(rms * gain).toLong()} peak=$peak→" +
                "${minOf(32767, (peak * gain).toInt())} gain=${"%.2f".format(gain)}"
        )
        return out
    }

    /** PCM16（小端）→ 标准 44 字节头 WAV */
    private fun encodeWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(sampleRate); h.putInt(sampleRate * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(pcm.size)
        out.write(h.array()); out.write(pcm)
        return out.toByteArray()
    }

    /** 块 RMS 能量（16bit PCM，用于 VAD 门控判定） */
    private fun rmsOf(chunk: ByteArray, n: Int): Double {
        var sum = 0.0
        var i = 0
        while (i + 1 < n) {
            val s = ((chunk[i].toInt() and 0xFF) or (chunk[i + 1].toInt() shl 8)).toShort()
            sum += s.toDouble() * s.toDouble()
            i += 2
        }
        val frames = n / 2
        return if (frames == 0) 0.0 else Math.sqrt(sum / frames)
    }

    /** 音量 0..1（RMS 归一，语音常用幅度约 2000-8000） */
    private fun volumeOf(chunk: ByteArray, n: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < n) {
            val s = ((chunk[i].toInt() and 0xFF) or (chunk[i + 1].toInt() shl 8)).toShort()
            sum += s.toDouble() * s.toDouble()
            i += 2
        }
        val frames = n / 2
        if (frames == 0) return 0f
        val rms = Math.sqrt(sum / frames)
        return (rms / 3000.0).coerceIn(0.0, 1.0).toFloat()
    }

    private fun deliverFinal(text: String, lst: AsrListener?) {
        if (!settled.compareAndSet(false, true)) return
        lst?.onFinal(text)
    }

    private fun deliverError(e: AsrException, lst: AsrListener?) {
        if (!settled.compareAndSet(false, true)) {
            AppLogger.d(TAG, "error dropped (already settled): kind=${e.kind}")
            return
        }
        AppLogger.w(TAG, "error kind=${e.kind} msg=${e.message}")
        lst?.onError(e)
    }

    private fun releaseRecorder(rec: AudioRecord) {
        try {
            if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
        } catch (t: Throwable) {
            AppLogger.w(TAG, "recorder stop failed: ${t.message}")
        }
        // 先摘 effect 再 release recorder：反序会让部分机型的 audio HAL 打印错误并可能残留 effect
        releaseNoiseSuppressor()
        try {
            rec.release()
        } catch (_: Throwable) {
        }
        if (audioRecord === rec) audioRecord = null
    }

    /** 静默取消：停录音、断在途请求、抑制迟到回调 */
    override fun cancel() {
        stopRequested = true
        settled.set(true)
        recordJob?.cancel()
        recognitionJob?.cancel()
        currentCall?.cancel()
        synchronized(pcmBuffer) { pcmBuffer.reset() }
        AppLogger.d(TAG, "cancel")
    }

    override fun shutdown() {
        cancel()
        listener = null
        config = null
    }
}
