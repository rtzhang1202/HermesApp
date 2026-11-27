package com.ai.assistance.operit.core.tools.defaultTool.standard

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.UserMemoryPromptInjector
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.ToolExecutor
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.model.UserMemoryFormat
import com.ai.assistance.operit.data.model.UserMemoryItem
import com.ai.assistance.operit.data.model.UserMemoryKind
import com.ai.assistance.operit.data.model.UserMemorySource
import com.ai.assistance.operit.data.model.UserMemoryStatus
import com.ai.assistance.operit.data.model.UserMemoryType
import com.ai.assistance.operit.data.repository.UserMemoryStore
import com.ai.assistance.operit.ui.features.usermemory.UserMemoryConfirmActivity
import com.ai.assistance.operit.util.AppLogger

/**
 * 用户记忆数据库工具：memory_save / memory_search。
 *
 * 与旧 query_memory 的区别（工具描述里也写明）：query_memory 查的是知识库/笔记库，
 * memory_* 查的是"关于用户本人的事实"（证件、地址、偏好、账号、物品位置、关系、经历）。
 *
 * memory_save 只创建 pending 条目并弹确认卡，用户点「保存」才真正入库（不静默写入）。
 */
class UserMemoryToolExecutor(private val context: Context) : ToolExecutor {

    companion object {
        private const val TAG = "UserMemoryTool"
    }

    private val store: UserMemoryStore
        get() = UserMemoryStore.getInstance(context)

    override fun invoke(tool: AITool): ToolResult {
        return when (tool.name) {
            "memory_save" -> save(tool)
            "memory_search" -> search(tool)
            else ->
                    ToolResult(
                            toolName = tool.name,
                            success = false,
                            result = StringResultData(""),
                            error = "Unsupported tool '${tool.name}'. Expected memory_save or memory_search."
                    )
        }
    }

    private fun param(tool: AITool, name: String): String? =
            tool.parameters.find { it.name == name }?.value?.trim()?.takeIf { it.isNotEmpty() }

    private fun save(tool: AITool): ToolResult {
        val title = param(tool, "title")
        val value = param(tool, "value")
        if (title.isNullOrBlank()) {
            return failure(tool, "memory_save 需要 title（这条记忆的标题，如「车停位置」）")
        }
        if (value.isNullOrBlank()) {
            return failure(tool, "memory_save 需要 value（要记住的具体内容）")
        }
        val type = UserMemoryType.normalize(param(tool, "type"))
        val kind = UserMemoryKind.normalize(param(tool, "kind"))
        val sensitivity = (param(tool, "sensitivity")?.toIntOrNull() ?: 0).coerceIn(0, 2)
        val subject = param(tool, "subject")
        val sourceApp = param(tool, "source_app")
        val sourceUri = param(tool, "source_uri")
        val source = UserMemorySource.normalize(param(tool, "source") ?: UserMemorySource.MANUAL)

        return try {
            val result =
                    store.insert(
                            title = title,
                            value = value,
                            type = type,
                            kind = kind,
                            subject = subject,
                            source = source,
                            sourceApp = sourceApp,
                            sourceUri = sourceUri,
                            confidence = 1.0,
                            sensitivity = sensitivity,
                            userVerified = false,
                            status = UserMemoryStatus.PENDING,
                            actor = "agent"
                    )
            val item = store.get(result.id)
            // 确认卡：pending 条目弹卡片让用户点保存（后台启动失败不影响 pending 落库）
            val cardShown = UserMemoryConfirmActivity.show(context, result.id)

            val sb = StringBuilder()
            if (result.deduped) {
                sb.append("已存在于用户记忆（id=${result.id}），已刷新时间和置信度，无需重复保存。")
            } else {
                sb.append("已创建待确认的记忆（id=${result.id}，状态=待确认）。")
                sb.append("\n确认卡").append(if (cardShown) "已弹出" else "未能自动弹出，可在「用户记忆 → 待确认」里处理")
                sb.append("，用户点「保存到记忆」后才会正式入库。")
            }
            item?.let {
                sb.append("\n条目：")
                sb.append(UserMemoryPromptInjector.describeForTool(it, showValue = it.sensitivity < 2))
            }
            if (result.conflictWithId != null) {
                val old = store.get(result.conflictWithId)
                sb.append("\n⚠ 冲突：已存在同类型同主题的另一条记忆（id=${result.conflictWithId}")
                old?.let { sb.append("，值=「${it.maskedValue}」") }
                sb.append("）。两条都已保留并标记 conflict，请在「用户记忆」里让用户选择哪条正确，不要自作主张覆盖。")
            }
            sb.append("\n请用一句话告诉用户「要记住这条吗」并等待确认，不要声称已经保存成功。")
            ToolResult(toolName = tool.name, success = true, result = StringResultData(sb.toString()))
        } catch (e: Exception) {
            AppLogger.e(TAG, "memory_save failed", e)
            failure(tool, "memory_save 写入失败: ${e.message}")
        }
    }

    private fun search(tool: AITool): ToolResult {
        val query = param(tool, "query") ?: ""
        val typeFilter = param(tool, "type")
        val limit = (param(tool, "limit")?.toIntOrNull() ?: 5).coerceIn(1, 20)
        return try {
            val items =
                    if (query.isBlank() || query == "*") {
                        store.list(
                                type = typeFilter,
                                status = UserMemoryStatus.ACTIVE,
                                limit = limit
                        )
                    } else {
                        store.list(
                                query = query,
                                type = typeFilter,
                                status = UserMemoryStatus.ACTIVE,
                                limit = 200
                        )
                    }
            val filtered = items.take(limit)
            if (filtered.isEmpty()) {
                // 关键词没命中时退回"最近的记忆"，比一句"没有"更有用（并明确标注不是精确匹配）
                val recent =
                        store.list(status = UserMemoryStatus.ACTIVE, limit = limit)
                if (recent.isNotEmpty() && query.isNotBlank() && query != "*") {
                    val sb = StringBuilder()
                    sb.append("用户记忆库里没有精确匹配「$query」的条目，下面是最近的 ${recent.size} 条记忆（仅供参考）：\n")
                    for (item in recent) {
                        sb.append("- ").append(UserMemoryPromptInjector.describeForTool(item, showValue = item.sensitivity < 2))
                        sb.append('\n')
                    }
                    return ToolResult(toolName = tool.name, success = true, result = StringResultData(sb.toString().trimEnd()))
                }
                return ToolResult(
                        toolName = tool.name,
                        success = true,
                        result =
                                StringResultData(
                                        "用户记忆库里没有匹配「$query」的条目。（注意：memory_search 查的是关于用户本人的记忆；知识库/笔记内容请用 query_memory）"
                                )
                )
            }
            val sb = StringBuilder()
            sb.append("用户记忆命中 ${filtered.size} 条（memory_search：关于用户本人的事实，不是知识库）：\n")
            for (item in filtered) {
                sb.append("- ").append(UserMemoryPromptInjector.describeForTool(item, showValue = item.sensitivity < 2))
                if (item.sensitivity == 2) sb.append("（机密值已打码，完整值只在用户记忆详情页可见）")
                sb.append('\n')
            }
            ToolResult(toolName = tool.name, success = true, result = StringResultData(sb.toString().trimEnd()))
        } catch (e: Exception) {
            AppLogger.e(TAG, "memory_search failed", e)
            failure(tool, "memory_search 查询失败: ${e.message}")
        }
    }

    private fun failure(tool: AITool, error: String): ToolResult =
            ToolResult(toolName = tool.name, success = false, result = StringResultData(""), error = error)
}
