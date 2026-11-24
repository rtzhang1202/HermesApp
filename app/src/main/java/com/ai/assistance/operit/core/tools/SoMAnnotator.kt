package com.ai.assistance.operit.core.tools

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.util.concurrent.atomic.AtomicInteger

/**
 * SoM (Set-of-Mark) 标注截图：把 UI 层次结构中的可交互/有文本元素按序号画框，
 * 供多模态模型做视觉定位（visual grounding）——模型读取回执中的 [n] 序号与 bounds，
 * 再以坐标调用 tap/clickElement。
 *
 * 防 stale：每次生成带自增 generation id 与唯一文件名；屏幕在标注后若变化，
 * 动作执行后的 [observe] 回执（ScreenObserver）会显式暴露 changed=yes。
 *
 * 数据源：UIHierarchyManager（无障碍 provider 的 UI 树，uiautomator XML 格式），
 * 坐标与 provider 截图同处物理像素空间，无需缩放。
 */
object SoMAnnotator {

    private val NODE_REGEX = Regex("<node\\s+([^>]+?)\\s*/?>")
    private val ATTR_REGEX = Regex("(\\w[\\w-]*)=\"([^\"]*)\"")

    private val MARKABLE_FLAGS = setOf("clickable", "editable", "scrollable", "checkable", "long-clickable")

    private val generation = AtomicInteger(0)

    data class Mark(
            val label: String,
            val cls: String,
            val left: Int,
            val top: Int,
            val right: Int,
            val bottom: Int,
            val flags: List<String>,
            val hasRealLabel: Boolean = false
    ) {
        fun indexLine(index: Int): String {
            val flagText = if (flags.isEmpty()) "" else " | " + flags.joinToString(",")
            return "[$index] \"$label\" bounds=[$left,$top][$right,$bottom]$flagText"
        }
    }

    /** 下一个 generation id（防 stale：模型引用的永远是"这一张"的序号体系）。 */
    fun nextGen(): Int = generation.incrementAndGet()

    /**
     * 从 uiautomator XML 解析可标注元素：
     * 有可见文本/content-desc，或带交互 flag；过滤自窗（harness 自己的悬浮 UI）。
     * 结果按 (top, left) 阅读顺序排序并截断到 [maxMarks]。
     */
    fun parseMarks(xml: String, maxMarks: Int = 40, selfPackage: String? = null): List<Mark> {
        if (xml.isEmpty()) return emptyList()
        val marks = mutableListOf<Mark>()
        val seenBounds = HashSet<String>()
        for (m in NODE_REGEX.findAll(xml)) {
            val attrs = HashMap<String, String>()
            for (a in ATTR_REGEX.findAll(m.groupValues[1])) {
                attrs[a.groupValues[1]] = a.groupValues[2]
            }
            val bounds = attrs["bounds"] ?: continue
            val rect = parseBounds(bounds) ?: continue
            if (rect.right - rect.left < 8 || rect.bottom - rect.top < 8) continue
            val pkg = attrs["package"]
            if (selfPackage != null && pkg == selfPackage) continue

            val text = (attrs["text"] ?: "").trim()
            val desc = (attrs["contentDesc"] ?: "").trim()
            val flags = MARKABLE_FLAGS.filter { attrs[it] == "true" }
            if (text.isEmpty() && desc.isEmpty() && flags.isEmpty()) continue
            if (!seenBounds.add(bounds)) continue

            val hasRealLabel = text.isNotEmpty() || desc.isNotEmpty()
            val label = when {
                text.isNotEmpty() -> text
                desc.isNotEmpty() -> desc
                else -> (attrs["class"] ?: "node").substringAfterLast('.')
            }
            marks.add(
                    Mark(
                            label = label.take(60),
                            cls = (attrs["class"] ?: "").substringAfterLast('.'),
                            left = rect.left, top = rect.top, right = rect.right, bottom = rect.bottom,
                            flags = flags,
                            hasRealLabel = hasRealLabel
                    )
            )
        }
        // 去嵌套：无真实文本的容器（仅类名标记）若完整包住一个有文本的标记，则丢弃容器，
        // 避免同一行出双框/序号互相遮挡（行容器 vs 行内文字）。
        val filtered = marks.filter { m ->
            if (m.hasRealLabel) return@filter true
            !marks.any { o ->
                o !== m && o.hasRealLabel &&
                    o.left >= m.left - 4 && o.right <= m.right + 4 &&
                    o.top >= m.top - 4 && o.bottom <= m.bottom + 4
            }
        }
        return filtered.sortedWith(compareBy({ it.top }, { it.left })).take(maxMarks.coerceIn(1, 200))
    }

    private fun parseBounds(bounds: String): android.graphics.Rect? {
        // "[left,top][right,bottom]"
        val parts = bounds.replace("[", "").replace("]", ",").split(",")
        if (parts.size < 4) return null
        return try {
            android.graphics.Rect(parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), parts[3].toInt())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 在截图上画序号框：鲜艳红框 + 左上角红底白字圆形序号（字号/线宽随分辨率缩放，
     * 保证大图上依然清晰）。返回新的 ARGB 位图，入参不修改。
     */
    fun annotate(source: Bitmap, marks: List<Mark>): Bitmap {
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true) ?: return source
        if (marks.isEmpty()) return bitmap
        val canvas = Canvas(bitmap)

        val unit = maxOf(bitmap.width, bitmap.height).toFloat()
        val strokeW = (unit / 360f).coerceAtLeast(3f)
        val badgeR = unit / 48f
        val fontSize = unit / 34f

        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeW
            color = Color.parseColor("#FFD32F2F")
        }
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#FFD32F2F")
        }
        val badgeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeW / 2f
            color = Color.WHITE
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textSize = fontSize
            textAlign = Paint.Align.CENTER
        }

        marks.forEachIndexed { i, mark ->
            val rect = RectF(mark.left.toFloat(), mark.top.toFloat(), mark.right.toFloat(), mark.bottom.toFloat())
            canvas.drawRoundRect(rect, strokeW * 2, strokeW * 2, boxPaint)

            // 序号圆标放在框【上方外侧】（贴住上边缘），避免压住框内文字；
            // 贴近屏幕顶部时回退到框内左上角。
            val cx = rect.left + badgeR + strokeW
            val cy = if (rect.top >= badgeR * 2 + strokeW * 2) {
                rect.top - badgeR - strokeW
            } else {
                rect.top + badgeR + strokeW
            }
            canvas.drawCircle(cx, cy, badgeR, badgePaint)
            canvas.drawCircle(cx, cy, badgeR, badgeRingPaint)

            val number = (i + 1).toString()
            val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(number, cx, textY, textPaint)
        }
        return bitmap
    }
}
