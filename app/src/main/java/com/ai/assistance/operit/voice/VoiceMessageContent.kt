package com.ai.assistance.operit.voice

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.ui.common.markdown.MarkdownImageRenderer
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.ImagePoolManager
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 语音气泡富内容渲染：文本 + 图片混排。
 *
 * 语音界面原先只用一个 Text 渲染整段回答，模型附图时只能看到
 * `<link type=image id=...>` 标签/文件路径。这里把气泡文本拆成
 * 文本段与图片段，图片复用主聊天同一套管线：
 * - `<link type=image id=UUID>`（SoM/相册工具回执）→ ImagePoolManager 取图 → 缓存文件 → MarkdownImageRenderer
 * - 本地文件路径（/sdcard/...、file://...）与 markdown `![](path)` → 同一个 MarkdownImageRenderer
 * MarkdownImageRenderer 自带 coil 加载、失败占位与点击全屏查看。
 */
internal sealed class VoiceContentPart {
    data class Text(val text: String) : VoiceContentPart()

    data class Image(
        val kind: Kind,
        val ref: String,
        val alt: String
    ) : VoiceContentPart() {
        enum class Kind { POOL, URL }
    }
}

internal object VoiceContentParser {

    private val markdownImageRegex = Regex(
        """!\[([^\]]*)\]\(\s*<?([^)\s>]+)>?\s*(?:"[^"]*")?\s*\)"""
    )

    private val linkOpenTagRegex = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val linkCloseTagRegex = Regex("""</link\s*>""", RegexOption.IGNORE_CASE)
    private val attrTypeRegex = Regex("""\btype\s*=\s*\\?["']?([a-zA-Z]+)""")
    private val attrIdRegex = Regex("""\bid\s*=\s*\\?["']?([^"'\s>]+)""")

    /** 裸本地图片路径：/sdcard、/storage、/data（含 file:// 前缀），扩展名白名单 */
    private val bareImagePathRegex = Regex(
        """(?:file://)?/(?:sdcard|storage|data|mnt)/[^\s<>"'()\[\]，。；：、）】]+?\.(?:png|jpg|jpeg|webp|gif|bmp|heic)""",
        RegexOption.IGNORE_CASE
    )

    private data class Hit(val start: Int, val end: Int, val part: VoiceContentPart.Image)

    fun parse(raw: String): List<VoiceContentPart> {
        if (raw.isBlank()) return emptyList()
        // 工具回执可能是 JSON 字符串，标记被转成 \u003c 一类转义：先还原，避免转义串直接上屏
        val text = raw.replace("\r\n", "\n")
            .replace("\\u003c", "<").replace("\\u003C", "<")
            .replace("\\u003e", ">").replace("\\u003E", ">")
            .replace("\\u003d", "=").replace("\\u003D", "=")
            .replace("\\u0022", "\"").replace("\\u0027", "'")
        val hits = ArrayList<Hit>()

        // 1) markdown 图片语法
        markdownImageRegex.findAll(text).forEach { m ->
            val alt = m.groupValues[1].trim().take(80)
            val url = m.groupValues[2].trim()
            if (url.isNotEmpty()) {
                hits.add(
                    Hit(m.range.first, m.range.last + 1, VoiceContentPart.Image(VoiceContentPart.Image.Kind.URL, url, alt))
                )
            }
        }

        // 2) <link type=image id=...> 标签（含无引号/转义引号形态；闭合标签可选）
        linkOpenTagRegex.findAll(text).forEach { m ->
            val tag = m.value
            val type = attrTypeRegex.find(tag)?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT)
            val id = attrIdRegex.find(tag)?.groupValues?.getOrNull(1)
                ?.trim()
                ?.trim('\\', '"', '\'')
                ?.trim()
            if (type == "image" && !id.isNullOrBlank() && id != "error") {
                var end = m.range.last + 1
                var alt = ""
                val closeIdx = text.indexOf("</link>", end, ignoreCase = true)
                val closeOk = closeIdx >= 0 &&
                    closeIdx - end <= 200 &&
                    !text.substring(end, closeIdx).contains('<')
                if (closeOk) {
                    alt = text.substring(end, closeIdx).trim().take(80)
                    end = closeIdx + "</link>".length
                }
                hits.add(
                    Hit(m.range.first, end, VoiceContentPart.Image(VoiceContentPart.Image.Kind.POOL, id!!, alt))
                )
            }
        }

        // 3) 裸本地文件路径
        bareImagePathRegex.findAll(text).forEach { m ->
            hits.add(
                Hit(
                    m.range.first,
                    m.range.last + 1,
                    VoiceContentPart.Image(VoiceContentPart.Image.Kind.URL, m.value, "")
                )
            )
        }

        if (hits.isEmpty()) return listOf(VoiceContentPart.Text(cleanText(text)))

        // 按位置排序并丢弃重叠命中（markdown 已覆盖的 URL 不再重复当裸路径）
        val sorted = hits.sortedBy { it.start }
        val kept = ArrayList<Hit>(sorted.size)
        val seenRefs = HashSet<String>()
        var lastEnd = -1
        for (hit in sorted) {
            if (hit.start < lastEnd) continue
            // 重叠命中已由 markdown 覆盖；重复图（同 kind+ref）只渲染一次但标签照样吞掉，
            // 否则模型自带标签 + 控制器补挂标签会让原文残留一段 <link ...> 上屏。
            lastEnd = hit.end
            val refKey = hit.part.kind.name + ":" + hit.part.ref
            if (!seenRefs.add(refKey)) continue
            kept.add(hit)
        }

        val parts = ArrayList<VoiceContentPart>(kept.size * 2 + 1)
        var cursor = 0
        for (hit in kept) {
            if (hit.start > cursor) {
                val piece = cleanText(text.substring(cursor, hit.start))
                if (piece.isNotBlank()) parts.add(VoiceContentPart.Text(piece))
            }
            parts.add(hit.part)
            cursor = hit.end
        }
        if (cursor < text.length) {
            val tail = cleanText(text.substring(cursor))
            if (tail.isNotBlank()) parts.add(VoiceContentPart.Text(tail))
        }
        if (parts.isEmpty()) parts.add(VoiceContentPart.Text(cleanText(text)))
        return parts
    }

    /** 清掉残留的 link 标签，避免标签碎片/闭合符直接上屏 */
    private fun cleanText(segment: String): String {
        return segment
            .replace(linkCloseTagRegex, "")
            .replace(linkOpenTagRegex, "")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    fun toImageUrl(ref: String): String {
        val r = ref.trim()
        return when {
            r.startsWith("file://", ignoreCase = true) -> r
            r.startsWith("content://", ignoreCase = true) -> r
            r.startsWith("http://", ignoreCase = true) -> r
            r.startsWith("https://", ignoreCase = true) -> r
            r.startsWith("/") -> "file://$r"
            else -> r
        }
    }
}

/** 气泡内容：文本段照常排版，图片段走主聊天图片渲染管线 */
@Composable
internal fun VoiceMessageContent(
    text: String,
    textColor: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    modifier: Modifier = Modifier
) {
    val parts = remember(text) { VoiceContentParser.parse(text) }
    if (parts.size == 1 && parts[0] is VoiceContentPart.Text) {
        Text(
            text = (parts[0] as VoiceContentPart.Text).text,
            color = textColor,
            fontSize = fontSize,
            lineHeight = lineHeight,
            modifier = modifier
        )
        return
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        parts.forEach { part ->
            when (part) {
                is VoiceContentPart.Text -> Text(
                    text = part.text,
                    color = textColor,
                    fontSize = fontSize,
                    lineHeight = lineHeight
                )

                is VoiceContentPart.Image -> VoiceBubbleImage(part)
            }
        }
    }
}

@Composable
private fun VoiceBubbleImage(part: VoiceContentPart.Image) {
    when (part.kind) {
        VoiceContentPart.Image.Kind.POOL -> VoicePoolImage(part.ref, part.alt)
        VoiceContentPart.Image.Kind.URL -> {
            val url = remember(part.ref) { VoiceContentParser.toImageUrl(part.ref) }
            val fallbackAlt = stringResource(R.string.image_block)
            val alt = remember(part.alt, fallbackAlt) {
                part.alt.ifBlank { fallbackAlt }.replace(Regex("""[\[\]()]"""), " ").trim()
            }
            AppLogger.d(TAG, "render url image: $url")
            MarkdownImageRenderer(
                imageMarkdown = "![$alt]($url)",
                maxImageHeight = VOICE_IMAGE_MAX_HEIGHT_DP
            )
        }
    }
}

private sealed interface PoolImageState {
    data object Loading : PoolImageState
    data class Ready(val url: String) : PoolImageState
    data object Missing : PoolImageState
}

@Composable
private fun VoicePoolImage(id: String, alt: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var state by remember(id) { mutableStateOf<PoolImageState>(PoolImageState.Loading) }
    LaunchedEffect(id) {
        state = withContext(Dispatchers.IO) {
            resolvePoolImage(context, id)?.let { PoolImageState.Ready(it) } ?: PoolImageState.Missing
        }
    }
    when (val s = state) {
        is PoolImageState.Ready -> {
            val fallbackAlt = stringResource(R.string.image_block)
            val safeAlt = remember(alt, fallbackAlt) {
                alt.ifBlank { fallbackAlt }.replace(Regex("""[\[\]()]"""), " ").trim()
            }
            MarkdownImageRenderer(
                imageMarkdown = "![$safeAlt](${s.url})",
                maxImageHeight = VOICE_IMAGE_MAX_HEIGHT_DP
            )
        }

        PoolImageState.Missing -> ImagePlaceholder(stringResource(R.string.image_load_failed))
        PoolImageState.Loading -> ImagePlaceholder(null)
    }
}

/**
 * 把 ImagePoolManager 里的图片落到语音专用缓存文件（按 id 幂等），
 * 返回 file:// URL 供 MarkdownImageRenderer/coil 复用（主聊天同一套全屏查看器）。
 * 缓存独立于 ImagePoolManager 的启动清空逻辑，历史气泡重开界面仍能显示。
 */
private fun resolvePoolImage(context: Context, id: String): String? {
    val dir = File(context.cacheDir, "voice_images")
    val safeId = id.replace(Regex("""[^A-Za-z0-9_-]"""), "_")
    val file = File(dir, "$safeId.img")
    if (file.exists() && file.length() > 0L) {
        return Uri.fromFile(file).toString()
    }
    val data = ImagePoolManager.getImage(id)
    if (data == null) {
        AppLogger.w(TAG, "pool image missing: $id")
        return null
    }
    return try {
        val bytes = Base64.decode(data.base64, Base64.DEFAULT)
        if (!dir.exists()) dir.mkdirs()
        val tmp = File(dir, "$safeId.tmp")
        tmp.writeBytes(bytes)
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        if (file.length() <= 0L) {
            AppLogger.w(TAG, "pool image decode empty: $id")
            null
        } else {
            AppLogger.d(TAG, "pool image ready: id=$id file=${file.absolutePath} bytes=${file.length()}")
            Uri.fromFile(file).toString()
        }
    } catch (t: Throwable) {
        AppLogger.e(TAG, "pool image decode failed: $id", t)
        null
    }
}

@Composable
private fun ImagePlaceholder(message: String?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x1FFFFFFF)),
        contentAlignment = Alignment.Center
    ) {
        if (message == null) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = Color(0xFFA78BFA)
            )
        } else {
            Text(text = message, color = Color(0xB3FFFFFF), fontSize = 12.sp)
        }
    }
}

private const val TAG = "VoiceMessageContent"
private const val VOICE_IMAGE_MAX_HEIGHT_DP = 360
