package com.ai.assistance.operit.data.model

import org.json.JSONObject
import java.util.UUID

/**
 * 用户记忆数据库条目（独立于知识库记忆 memory_* 那套）。
 *
 * 定位：关于用户的、可编辑、可追溯的断言库 —— 证件 / 地址 / 偏好 / 账号 / 关系 / 经历。
 * 表结构对齐《语音助手-屏幕识别与记忆调研》建议二：memory_item + memory_audit。
 */
data class UserMemoryItem(
        val id: String,
        val kind: String, // fact | episode | snippet
        val type: String, // id_card | bank_card | address | preference | schedule | account | object_location | relation | event | other
        val title: String,
        val valueJson: String,
        val normalized: String,
        val subject: String,
        val source: String, // manual | screen | chat | import | system
        val sourceApp: String?,
        val sourceUri: String?,
        val confidence: Double,
        val sensitivity: Int, // 0 normal | 1 sensitive | 2 secret
        val userVerified: Boolean,
        val status: String, // pending | active | conflict
        val conflictWith: String?,
        val createdAt: Long,
        val updatedAt: Long,
        val lastUsedAt: Long?,
        val expiresAt: Long?
) {
    val plainValue: String
        get() = UserMemoryFormat.plainValueOf(valueJson)

    val maskedValue: String
        get() = UserMemoryFormat.mask(plainValue, sensitivity)

    val isPending: Boolean
        get() = status == UserMemoryStatus.PENDING

    val hasConflict: Boolean
        get() = status == UserMemoryStatus.CONFLICT
}

object UserMemoryKind {
    const val FACT = "fact"
    const val EPISODE = "episode"
    const val SNIPPET = "snippet"

    fun normalize(raw: String?): String =
            when (raw?.trim()?.lowercase()) {
                EPISODE -> EPISODE
                SNIPPET -> SNIPPET
                else -> FACT
            }
}

object UserMemoryType {
    const val ID_CARD = "id_card"
    const val BANK_CARD = "bank_card"
    const val ADDRESS = "address"
    const val PREFERENCE = "preference"
    const val SCHEDULE = "schedule"
    const val ACCOUNT = "account"
    const val OBJECT_LOCATION = "object_location"
    const val RELATION = "relation"
    const val EVENT = "event"
    const val OTHER = "other"

    /** 记忆主页分类 tab 的顺序（全部 / 待确认 另加在最前） */
    val ALL: List<String> =
            listOf(
                    ID_CARD,
                    ADDRESS,
                    PREFERENCE,
                    SCHEDULE,
                    ACCOUNT,
                    OBJECT_LOCATION,
                    RELATION,
                    EVENT,
                    OTHER
            )

    fun normalize(raw: String?): String {
        val v = raw?.trim()?.lowercase() ?: return OTHER
        return if (v in ALL || v == BANK_CARD) v else OTHER
    }
}

object UserMemorySource {
    const val MANUAL = "manual"
    const val SCREEN = "screen"
    const val CHAT = "chat"
    const val IMPORT = "import"
    const val SYSTEM = "system"

    fun normalize(raw: String?): String =
            when (raw?.trim()?.lowercase()) {
                SCREEN -> SCREEN
                CHAT -> CHAT
                IMPORT -> IMPORT
                SYSTEM -> SYSTEM
                else -> MANUAL
            }
}

object UserMemoryStatus {
    const val PENDING = "pending"
    const val ACTIVE = "active"
    const val CONFLICT = "conflict"
}

/** 格式化 / 规范化 / 脱敏 工具（纯函数，便于单元验证） */
object UserMemoryFormat {

    private val CJK = Regex("[\\u4e00-\\u9fff]")
    private val CJK_RUN = Regex("[\\u4e00-\\u9fff]+")
    private val ASCII_WORD = Regex("[a-z0-9][a-z0-9._-]{1,}")
    private val LONG_DIGITS = Regex("\\d{5,}")

    /** 中文疑问/客套等无检索价值的二元组，避免任何一句话都把全部记忆召回 */
    private val STOP_BIGRAMS =
            setOf(
                    "我的", "你的", "他的", "她的", "我们", "你们", "什么", "怎么", "哪个", "哪些",
                    "哪里", "哪了", "了吗", "一下", "这个", "那个", "可以", "帮我", "请问", "你好",
                    "是不", "不是", "现在", "今天", "明天", "知道", "告诉", "记得", "好像", "然后",
                    "就是", "还是", "已经", "没有", "有没"
            )

    fun newId(): String = UUID.randomUUID().toString()

    /** 搜索用规范化：小写、去空白/标点，保留汉字与字母数字 */
    fun normalize(text: String): String =
            text.lowercase().replace(Regex("[\\s\\p{Punct}，。、；：？！“”‘’（）【】《》…—～·]"), "")

    /** 把模型/用户给的 value 统一成 JSON 对象字符串 */
    fun toValueJson(raw: String): String {
        val text = raw.trim()
        if (text.startsWith("{") && text.endsWith("}")) {
            try {
                JSONObject(text)
                return text
            } catch (_: Exception) {
                // 不是合法 JSON 就按纯文本存
            }
        }
        return JSONObject().put("text", text).toString()
    }

    fun plainValueOf(valueJson: String): String {
        if (valueJson.isBlank()) return ""
        return try {
            val obj = JSONObject(valueJson)
            when {
                obj.has("text") -> obj.optString("text")
                obj.has("value") -> obj.optString("value")
                obj.length() == 1 -> obj.keys().asSequence().first().let { obj.optString(it) }
                else -> {
                    val parts = mutableListOf<String>()
                    obj.keys().forEach { key -> parts.add("$key：${obj.optString(key)}") }
                    parts.joinToString("；")
                }
            }
        } catch (_: Exception) {
            valueJson
        }
    }

    /** 列表/气泡里展示的脱敏值：sensitivity=0 原样；1 长数字保后 4 位；2 只留首尾 */
    fun mask(value: String, sensitivity: Int): String {
        if (sensitivity <= 0) return value
        if (sensitivity == 1) {
            val masked = value.replace(LONG_DIGITS) { m ->
                "*".repeat(m.value.length - 4) + m.value.takeLast(4)
            }
            return masked
        }
        // secret：保留首尾各 2 个字符，中间全部打码
        if (value.length <= 4) return "*".repeat(value.length)
        val head = value.take(2)
        val tail = value.takeLast(2)
        return head + "*".repeat((value.length - 4).coerceAtMost(12)) + tail
    }

    /** 主体键：冲突检测用（同 type + 同 subject 视为同一条事实的不同版本） */
    fun subjectKey(type: String, subject: String?, title: String): String {
        val base = (subject?.takeIf { it.isNotBlank() } ?: title).trim()
        val norm = normalize(base)
        return if (norm.isBlank()) type else "$type:$norm"
    }

    /** 检索 token：英文按词，中文按二元组；去掉疑问/客套二元组 */
    fun queryTokens(raw: String): List<String> {
        val s = raw.lowercase()
        val tokens = LinkedHashSet<String>()
        ASCII_WORD.findAll(s).forEach { m ->
            val w = m.value
            if (w.length >= 2 && w !in setOf("the", "and", "for", "what", "where", "is", "my")) {
                tokens.add(w)
            }
        }
        CJK_RUN.findAll(s).forEach { m ->
            val run = m.value
            if (run.length < 2) return@forEach
            if (run.length == 2) {
                if (run !in STOP_BIGRAMS) tokens.add(run)
            } else {
                // 长度 >= 3 一律拆二元组：这样「车停哪」也能命中「车停在b2层…」
                for (i in 0..(run.length - 2)) {
                    val bg = run.substring(i, i + 2)
                    if (bg !in STOP_BIGRAMS) tokens.add(bg)
                }
            }
        }
        return tokens.toList()
    }

    /** 命中的 token 数（用于打分） */
    fun matchScore(tokens: List<String>, title: String, normalized: String): Double {
        if (tokens.isEmpty()) return 0.0
        val loweredTitle = title.lowercase()
        var score = 0.0
        for (t in tokens) {
            if (normalized.contains(t)) {
                score += if (CJK.containsMatchIn(t)) 1.6 else 1.2
            } else if (loweredTitle.contains(t)) {
                score += 1.0
            }
        }
        return score
    }

    /** 按 type/kind 给默认过期时间（毫秒时间戳，null=不过期） */
    fun defaultExpiry(type: String, kind: String, now: Long): Long? {
        val day = 24L * 3600L * 1000L
        return when {
            kind == UserMemoryKind.SNIPPET -> now + 30 * day
            type == UserMemoryType.PREFERENCE -> now + 548 * day // ≈18 个月
            kind == UserMemoryKind.EPISODE || type == UserMemoryType.EVENT -> now + 365 * day
            else -> null
        }
    }
}
