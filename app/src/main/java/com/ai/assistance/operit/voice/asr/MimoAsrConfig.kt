package com.ai.assistance.operit.voice.asr

/**
 * 云端 ASR（MiMo）配置：复用 App 现有 LLM 网关配置（ApiConfigDelegate 的 apiKey/apiEndpoint）。
 *
 * 规则（契约：密钥前缀绑定网关）：
 * - 基址优先取 LLM 配置里 MiMo 网关的地址（剥掉 /chat/completions、/messages 等后缀）
 * - 配置基址不是 MiMo 网关时，按密钥前缀推导：tp- → Token Plan 中国，sk- → 原生 API
 * - 前缀也不是 MiMo 的（如 OpenRouter key）→ 返回 null（云端 ASR 不可用，由上层报 CONFIG 错）
 *
 * 代码中不存任何密钥：只读配置，日志只打 mask（前 6 后 4）。
 */
data class MimoAsrConfig(val apiKey: String, val baseUrl: String) {

    companion object {
        /** MiMo-V2.5-ASR 是语音识别模型本名（无 v2.6 ASR 替代），不要"升级" */
        const val ASR_MODEL = "mimo-v2.5-asr"

        const val TOKEN_PLAN_CN = "https://token-plan-cn.xiaomimimo.com/v1"
        const val OFFICIAL = "https://api.xiaomimimo.com/v1"

        private val SUFFIXES = listOf(
            "/chat/completions", "/messages", "/completions", "/responses"
        )

        /**
         * 从 LLM 网关配置推导云端 ASR 配置；读不到（无密钥/非 MiMo 网关）返回 null。
         */
        fun fromLlmConfig(apiKey: String, apiEndpoint: String?): MimoAsrConfig? {
            val key = apiKey?.trim().orEmpty()
            if (key.isEmpty()) return null
            val base = baseUrlFromEndpoint(apiEndpoint) ?: baseFromKeyPrefix(key) ?: return null
            return MimoAsrConfig(key, base)
        }

        /** 端点 → 基址（去掉 /chat/completions 等后缀，只接受 MiMo 网关域名） */
        private fun baseUrlFromEndpoint(endpoint: String?): String? {
            var e = endpoint?.trim()?.trimEnd('/').orEmpty()
            if (e.isEmpty()) return null
            for (s in SUFFIXES) {
                if (e.endsWith(s, ignoreCase = true)) {
                    e = e.removeSuffix(s)
                    break
                }
            }
            if (!e.contains("xiaomimimo.com", ignoreCase = true)) return null
            return if (e.endsWith("/v1")) e else "$e/v1"
        }

        /** 密钥前缀 → 网关（tp→Token Plan，sk→原生） */
        private fun baseFromKeyPrefix(key: String): String? = when {
            key.startsWith("tp-") -> TOKEN_PLAN_CN
            key.startsWith("sk-") -> OFFICIAL
            else -> null
        }

        /** 日志用掩码：前 6 后 4；短值一律打码 */
        fun mask(key: String): String =
            if (key.length > 12) key.take(6) + "****" + key.takeLast(4) else "****"
    }
}
