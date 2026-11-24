package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.repository.UIHierarchyManager
import com.ai.assistance.operit.util.AppLogger

/**
 * ScreenObserver - 动作后观察（post-action observation）统一回执。
 *
 * 移植自 mobilerun-mcp 的验证闭环机制：每个 UI 动作执行前后各采一次轻量快照，
 * 把「前台应用 / 活动 / 可交互元素数 / 界面树是否变化」追加到 ToolResult 文本末尾，
 * 让 agent 在单步结果里就能确认动作是否真的生效，避免错误累积。
 *
 * 快照数据源：UIHierarchyManager（无障碍服务提供 UI 层次结构），失败时返回 null 并
 * 在回执中显式标注 unavailable，绝不阻塞主动作。
 */
object ScreenObserver {

    private const val TAG = "ScreenObserver"

    data class Snapshot(
        val foreground: String?,
        val activity: String?,
        val elementCount: Int,
        val treeHash: Int
    )

    /** 采一次轻量快照；UI 层次结构不可用时返回 null（不抛异常）。 */
    suspend fun capture(context: Context): Snapshot? {
        return try {
            val xml = UIHierarchyManager.getUIHierarchy(context)
            if (xml.isEmpty()) return null
            val pkg = UIHierarchyManager.extractWindowInfo(xml).first
            val activity = runCatching { UIHierarchyManager.getCurrentActivityName(context) }.getOrNull()
            val count = Regex("<node").findAll(xml).count()
            Snapshot(pkg, activity, count, xml.hashCode())
        } catch (e: Exception) {
            AppLogger.e(TAG, "采样屏幕快照失败", e)
            null
        }
    }

    /**
     * 把前后快照差异追加为一行 [observe] 回执。
     * 结构化结果（如 UIActionResultData）转换为文本一并携带——这些类型只在工具执行端构造、
     * 下游（桥接/展示）统一走 toString，无按类型消费方，回执完整性优先。
     */
    fun appendObservation(result: ToolResult, pre: Snapshot?, post: Snapshot?): ToolResult {
        val line = buildLine(pre, post)
        val current = result.result
        val merged = when (current) {
            is StringResultData ->
                if (current.value.isBlank()) line else current.value + "\n" + line
            else ->
                current.toString() + "\n" + line
        }
        return result.copy(result = StringResultData(merged))
    }

    private fun buildLine(pre: Snapshot?, post: Snapshot?): String {
        if (pre == null && post == null) {
            return "[observe] screen state unavailable (UI hierarchy not accessible)"
        }
        val target = post ?: pre!!
        val fg = target.foreground ?: "?"
        val act = target.activity ?: "-"
        val els = when {
            pre != null && post != null && pre.elementCount != post.elementCount ->
                "${pre.elementCount}->${post.elementCount}"
            else -> "${target.elementCount}"
        }
        val fgChanged = pre != null && post != null && pre.foreground != post.foreground
        val actChanged = pre != null && post != null && pre.activity != post.activity
        val treeChanged = pre != null && post != null && pre.treeHash != post.treeHash
        val changed = when {
            fgChanged -> "yes(foreground ${pre!!.foreground} -> ${post!!.foreground})"
            actChanged -> "yes(activity)"
            treeChanged -> "yes(ui tree)"
            pre != null && post != null -> "no"
            else -> "unknown"
        }
        return "[observe] fg=$fg act=$act elements=$els changed=$changed"
    }
}
