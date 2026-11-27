package com.ai.assistance.operit.voice.asr

/**
 * 可插拔 ASR 适配层（适配器模式）：
 * - [CloudMimoAsr]：默认主路径，录音 → MiMo 云端转写 → 文本
 * - [LocalSherpaAsr]：离线降级，仅在云端出现【网络类失败】时回退
 *
 * 云端 4xx 业务错误（鉴权/配额/格式/限流）不回退本地，直接上报给用户；
 * 本地缺模型时降级链末端是文字输入提示（由控制器兜底）。
 */

enum class AsrEngineKind { CLOUD, LOCAL }

/**
 * 错误分类决定降级策略：
 * - [NETWORK] 连接失败/超时/无网 → 允许回退本地离线识别
 * - [SERVER] 4xx/5xx 业务错误（含 401/403/400/429）→ 不回退，直接报错
 * - [EMPTY_AUDIO] 空音频/未识别到语音 → 不回退，报错 + 文字兜底
 * - [TIMEOUT] 录音超时/识别超时 → 不回退，报错 + 文字兜底
 * - [NO_MIC] 麦克风不可用 → 报错 + 文字兜底
 * - [CONFIG] 云端配置（密钥/基址）读不到 → 报错 + 文字兜底
 * - [UNAVAILABLE] 引擎本身不可用（本地缺模型等）→ 报错 + 文字兜底
 */
enum class AsrErrorKind { NETWORK, SERVER, EMPTY_AUDIO, TIMEOUT, NO_MIC, CONFIG, UNAVAILABLE, UNKNOWN }

/** ASR 异常：kind 决定上层降级/提示策略 */
class AsrException(
    val kind: AsrErrorKind,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/** 引擎回调（任意线程，控制器内部自行切回 scope） */
interface AsrListener {
    /** 麦克风音量 0..1（驱动波形动画） */
    fun onVolume(level: Float) {}

    /** 识别出最终文本（本地：流式 final；云端：转写落地） */
    fun onFinal(text: String)

    /** 识别失败（kind 见 [AsrErrorKind]） */
    fun onError(error: AsrException)
}

/** ASR 引擎统一接口 */
interface AsrEngine {
    val kind: AsrEngineKind

    /** 日志/界面用引擎名 */
    val engineName: String

    /**
     * 初始化引擎（云端：解析网关配置；本地：加载 sherpa 模型）。
     * @throws AsrException 初始化失败的具体原因
     */
    suspend fun initialize()

    /**
     * 开始聆听（云端：开始录音；本地：开始连续识别）。
     * @throws AsrException 启动失败
     */
    suspend fun startListening(languageCode: String, listener: AsrListener)

    /**
     * 停止聆听并产出结果：
     * 云端 = 停止录音 → 上传转写（结果经 [AsrListener.onFinal]/[AsrListener.onError] 回调，最长 180s）；
     * 本地 = 停止识别（final 结果可能已在聆听期间回调）。
     */
    suspend fun stopListening()

    /** 静默取消：停录音/停识别/断开在途请求，不产出任何结果 */
    fun cancel()

    /** 释放资源 */
    fun shutdown()
}
