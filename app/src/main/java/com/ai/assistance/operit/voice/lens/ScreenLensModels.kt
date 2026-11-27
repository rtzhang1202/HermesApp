package com.ai.assistance.operit.voice.lens

import android.graphics.Rect

/**
 * 识屏（Screen Lens）数据模型。
 *
 * 采集层输出与《语音助手-屏幕识别与记忆调研》建议一「采集与模型管线」对齐：
 * 节点树扁平化为 [{id, text, desc, bounds, clickable, className}]，附带包名/页面标题。
 */
data class ScreenNode(
        val id: Int,
        val text: String,
        val desc: String,
        val bounds: Rect,
        val clickable: Boolean,
        val editable: Boolean,
        val className: String
) {
    /** 可读标签：文本优先，其次 contentDescription */
    val label: String
        get() = text.ifBlank { desc }.trim()

    val hasLabel: Boolean
        get() = label.isNotEmpty()

    val area: Int
        get() = (bounds.width() * bounds.height()).coerceAtLeast(0)
}

/** 一次屏幕快照（来自无障碍窗口，不含识屏界面自身） */
data class ScreenSnapshot(
        val packageName: String,
        val pageTitle: String,
        val displayWidth: Int,
        val displayHeight: Int,
        val nodes: List<ScreenNode>
) {
    fun nodesIn(rect: Rect): List<ScreenNode> =
            nodes.filter { n ->
                if (n.bounds.isEmpty) return@filter false
                val inter = Rect()
                if (!inter.setIntersect(n.bounds, rect)) return@filter false
                val interArea = inter.width().toLong() * inter.height()
                val nodeArea = n.bounds.width().toLong() * n.bounds.height()
                val centerInside = rect.contains(n.bounds.centerX(), n.bounds.centerY())
                centerInside || (nodeArea > 0 && interArea * 100 / nodeArea >= 40)
            }

    /** 选区内的文字，按阅读顺序（上→下，左→右）拼接，去重。 */
    fun textIn(rect: Rect, maxChars: Int = 1600): String {
        val seen = LinkedHashSet<String>()
        val sb = StringBuilder()
        for (n in nodesIn(rect).sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))) {
            val label = n.label
            if (label.isBlank()) continue
            val key = label.lowercase()
            if (!seen.add(key)) continue
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(label)
            if (sb.length >= maxChars) break
        }
        return sb.toString().take(maxChars)
    }
}

/** 模型输出的实体（证件号/金额/时间…） */
data class LensEntity(val type: String, val value: String)

/** 结果面板动作 chip */
data class LensAction(val id: String, val label: String)

/** 一次识屏的结构化结果（模型 JSON 解析 + 本地兜底） */
data class LensResult(
        val scene: String,
        val selectedText: String,
        val entities: List<LensEntity>,
        val answer: String,
        val suggested: List<LensAction>,
        val raw: String = ""
) {
    companion object {
        /** 与调研 JSON 对齐的默认动作集（模型没给时兜底） */
        val DEFAULT_ACTIONS =
                listOf(
                        LensAction("extract", "提取文字"),
                        LensAction("translate", "翻译"),
                        LensAction("remember", "记住这个"),
                        LensAction("search", "搜一下")
                )
    }
}

/** 选区类型：点按命中节点 / 手绘圈选 / 全屏 */
enum class LensSelectionKind { NODE, PATH, FULL }

data class LensSelection(
        val kind: LensSelectionKind,
        val bounds: Rect,
        val node: ScreenNode? = null
)
