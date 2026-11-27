package com.ai.assistance.operit.api.chat.enhance

import android.content.Context
import com.ai.assistance.operit.data.model.UserMemoryFormat
import com.ai.assistance.operit.data.model.UserMemoryItem
import com.ai.assistance.operit.data.model.UserMemoryStatus
import com.ai.assistance.operit.data.repository.UserMemoryStore
import com.ai.assistance.operit.util.AppLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 对话前把「用户记忆」召回块注入 system prompt。
 *
 * 独立于工具描述面（SystemToolPrompts 那一套不动），只在 ConversationService 组装 prompt 的
 * 最后一步拼一段 <user_memory>；任何异常都退化为"不注入"，绝不影响正常对话。
 *
 * 规则（对齐调研报告建议二）：
 *  - 只召回 status=active、未过期、sensitivity <= 1 的条目；secret(2) 永不注入；
 *  - top 5–8 条，整块 ≤ 1600 字符（约 500–800 token）；
 *  - 召回即记 last_used_at + 审计 read。
 */
object UserMemoryPromptInjector {

    private const val TAG = "UserMemoryInject"
    private const val MAX_ITEMS = 8
    private const val MAX_CHARS = 1600

    suspend fun buildBlock(context: Context, query: String, useEnglish: Boolean): String =
            withContext(Dispatchers.IO) {
                runCatching { buildBlockInternal(context, query, useEnglish) }
                        .getOrElse {
                            AppLogger.w(TAG, "buildBlock failed: ${it.message}")
                            ""
                        }
            }

    private fun buildBlockInternal(context: Context, query: String, useEnglish: Boolean): String {
        if (query.isBlank()) return ""
        val store = UserMemoryStore.getInstance(context)
        val items = store.recall(query, limit = MAX_ITEMS, maxSensitivity = 1)
        if (items.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("\n\n<user_memory>\n")
        var used = 0
        var count = 0
        for (item in items) {
            val line = formatLine(item, useEnglish)
            if (used + line.length > MAX_CHARS) break
            sb.append(line).append('\n')
            used += line.length
            count++
        }
        if (count == 0) return ""
        sb.append("</user_memory>")
        sb.append(
                if (useEnglish)
                        "\nThe block above is the user's own memory database (facts the user asked you to remember). Use it only when relevant, quote it as-is, and never invent extra details. If the user asks something that is answered by an entry above, answer from it directly — do not deny knowing it just because a tool search returned nothing. Secret-level entries are never injected."
                else
                        "\n以上是用户记忆数据库中与本次提问相关的条目（用户主动要求记住的事实）。仅在相关时引用，按原文表述，不要编造未列出的细节；如果用户问的内容上面已有答案，直接据此回答，不要因为工具检索没命中就否认知道。机密级(secret)条目不会注入。"
        )
        AppLogger.d(TAG, "inject items=$count chars=${sb.length} queryLen=${query.length}")
        return sb.toString()
    }

    private fun formatLine(item: UserMemoryItem, useEnglish: Boolean): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(item.updatedAt))
        val sourceLabel =
                when (item.source) {
                    "screen" -> if (useEnglish) "screen capture" else "屏幕识别"
                    "chat" -> if (useEnglish) "chat" else "对话"
                    "import" -> if (useEnglish) "import" else "导入"
                    "system" -> if (useEnglish) "system" else "系统"
                    else -> if (useEnglish) "manual" else "手动"
                }
        val verified = if (item.userVerified) (if (useEnglish) ", verified" else "，已确认") else ""
        val value = item.maskedValue.replace("\n", " ")
        val title = item.title.replace("\n", " ")
        return if (useEnglish) {
            "- [${item.kind}/${item.type}] $title: $value (source: $date $sourceLabel, confidence ${"%.1f".format(item.confidence)}$verified)"
        } else {
            "- [${item.kind}/${item.type}] $title：$value（来源：$date $sourceLabel，置信度 ${"%.1f".format(item.confidence)}$verified）"
        }
    }

    /** 给 UI / 工具复用的简短摘要（不含 prompt 指令） */
    fun describeForTool(item: UserMemoryItem, showValue: Boolean = true): String {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.updatedAt))
        val value = if (showValue) item.plainValue else item.maskedValue
        val flag =
                when (item.status) {
                    UserMemoryStatus.PENDING -> "待确认"
                    UserMemoryStatus.CONFLICT -> "冲突"
                    else -> if (item.userVerified) "已确认" else "未确认"
                }
        val sens =
                when (item.sensitivity) {
                    1 -> "，敏感"
                    2 -> "，机密"
                    else -> ""
                }
        return "id=${item.id} | [${item.kind}/${item.type}] ${item.title}：$value（来源：$date ${item.source}$sens，$flag）"
    }
}
