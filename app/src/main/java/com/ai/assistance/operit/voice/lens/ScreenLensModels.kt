package com.ai.assistance.operit.voice.lens

import android.graphics.Bitmap
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
    fun nodesIn(rect: Rect): List<ScreenNode> = filterIn(nodes, rect)

    /**
     * 选区内命中的节点（也是 [nodesIn] 的实现）：节点中心落入选区，或与选区重叠 ≥40%。
     * 暴露成静态便于只拿到节点列表的调用方（如附件快照日志）复用同一套判定。
     */
    companion object {
        fun filterIn(nodes: List<ScreenNode>, rect: Rect): List<ScreenNode> =
                nodes.filter { n ->
                    if (n.bounds.isEmpty) return@filter false
                    val inter = Rect()
                    if (!inter.setIntersect(n.bounds, rect)) return@filter false
                    val interArea = inter.width().toLong() * inter.height()
                    val nodeArea = n.bounds.width().toLong() * n.bounds.height()
                    val centerInside = rect.contains(n.bounds.centerX(), n.bounds.centerY())
                    centerInside || (nodeArea > 0 && interArea * 100 / nodeArea >= 40)
                }
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

/**
 * 附件快照：**一次选框状态**（进入提问步 / 每次改框 / 提交）产出一份，
 * 文字、chip 缩略图、送模型的裁剪图全部从**同一个冻结帧 + 同一个 bounds** 派生。
 *
 * 为什么要做成对象而不是散落在 state 里各自重算：
 * 上一版的缺陷是「框已经移到『已连接的设备』，chip 还停在『网络和互联网』」——
 * 附件与识别各自读 state、各自提取，一旦某次刷新漏掉就永久脱节，用户看到的就是
 * 「回答与选区不符」。现在 chip 渲染与 analyzeSelection 都只认这一份快照，
 * 并且 [frameSeq]/[frame] 记录帧身份，日志里能证明两边同源同帧。
 */
data class LensAttachSnapshot(
        val bounds: Rect,
        /** 冻结帧序号（每次截屏 +1），同一帧派生的快照帧号相同 */
        val frameSeq: Int,
        /** 冻结帧本体（缩略图与模型裁剪都从它裁） */
        val frame: Bitmap?,
        /** 按 bounds 从节点树提取的文字；选区里没有文字时为空串（UI 显示「未识别到文字」） */
        val text: String,
        /** chip 缩略图（同帧同框裁切，最长边 256） */
        val thumb: Bitmap?,
        /** 产出原因：ask-open / rect-edit-live / rect-edit-end / submit */
        val reason: String
) {
    val textLen: Int
        get() = text.length

    val textEmpty: Boolean
        get() = text.isBlank()

    /** 帧身份串（进日志用）：帧号 + 位图对象身份，证明 chip 与模型图取自同一帧 */
    val frameId: String
        get() = "f$frameSeq@" + (frame?.let { Integer.toHexString(System.identityHashCode(it)) } ?: "none")
}

