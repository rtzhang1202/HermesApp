package com.ai.assistance.operit.voice.lens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.ai.assistance.operit.api.chat.enhance.MultiServiceManager
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.ImagePoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * 识屏识别层：本地先拼文字上下文（节点树 + 选区文字），再带裁剪图问多模态模型。
 *
 * 复用 app 现有 LLM 网关（MultiServiceManager → FunctionType.IMAGE_RECOGNITION 的 AIService），
 * 多模态输入链路与主聊天一致：图片注册到 ImagePoolManager，消息体里放 <link type=image id=…>，
 * 由 provider（OpenAI/Claude/Gemini）解析成图片 part。
 *
 * 不写聊天历史（单次调用，无工具面），输出按调研约定的 JSON 结构解析。
 */
object ScreenLensAnalyzer {

    private const val TAG = "ScreenLensAnalyze"
    private const val TIMEOUT_MS = 90_000L
    private const val MAX_NODE_LINES = 60

    data class Outcome(
            val result: LensResult,
            val usedVision: Boolean,
            val modelName: String,
            val elapsedMs: Long
    )

    suspend fun analyze(
            context: Context,
            crop: Bitmap?,
            snapshot: ScreenSnapshot?,
            selection: Rect,
            question: String?,
            localText: String? = null,
            includeText: Boolean = true,
            previousAnswer: String? = null
    ): Outcome = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val msm = MultiServiceManager(context.applicationContext)
        val config = runCatching {
            msm.getModelConfigForFunction(FunctionType.IMAGE_RECOGNITION)
        }.getOrNull()
        val visionEnabled = config?.enableDirectImageProcessing ?: false
        val modelName = config?.modelName.orEmpty()

        // includeText=false（用户删掉文字附件）→ 不把节点文字/节点清单塞进提示词，只按问题+图片作答
        val screenText =
                if (includeText) (localText ?: snapshot?.textIn(selection).orEmpty()) else ""
        val prompt =
                buildPrompt(
                        snapshot = if (includeText) snapshot else null,
                        selection = selection,
                        localText = screenText,
                        question = question,
                        visionEnabled = visionEnabled,
                        previousAnswer = previousAnswer
                )

        var imageId: String? = null
        var raw = ""
        try {
            val service = msm.getServiceForFunction(FunctionType.IMAGE_RECOGNITION)
            val params = msm.getModelParametersForFunction(FunctionType.IMAGE_RECOGNITION)
            var userContent = prompt
            if (visionEnabled && crop != null) {
                imageId = ImagePoolManager.addImageFromBitmap(crop, "image/png")
                if (imageId != "error") {
                    userContent = "<link type=image id=$imageId></link>\n$prompt"
                } else {
                    imageId = null
                }
            }
            val turns =
                    listOf(
                            PromptTurn(
                                    kind = PromptTurnKind.SYSTEM,
                                    content =
                                            "你是 Hermes 的屏幕理解助手。只输出一个 JSON 对象，" +
                                                    "不要 markdown 代码块，不要额外解释。"
                            ),
                            PromptTurn(kind = PromptTurnKind.USER, content = userContent)
                    )
            AppLogger.d(
                    TAG,
                    "send model=$modelName vision=${visionEnabled && crop != null} promptLen=${prompt.length} " +
                            "crop=${crop?.let { "${it.width}x${it.height}" } ?: "none"} " +
                            "nodes=${snapshot?.nodes?.size ?: 0}"
            )
            val sb = StringBuilder()
            val completed = withTimeoutOrNull(TIMEOUT_MS) {
                service.sendMessage(
                                context = context,
                                chatHistory = turns,
                                modelParameters = params,
                                stream = true
                        )
                        .collect { chunk -> sb.append(chunk) }
                true
            }
            if (completed == null) {
                runCatching { service.cancelStreaming() }
                AppLogger.w(TAG, "timeout after ${TIMEOUT_MS}ms")
            }
            raw = sb.toString()
        } catch (t: Throwable) {
            AppLogger.e(TAG, "analyze failed: ${t.message}", t)
            raw = ""
        } finally {
            imageId?.let { id -> runCatching { ImagePoolManager.removeImage(id) } }
        }

        val elapsed = System.currentTimeMillis() - started
        val parsed = parse(raw, screenText)
        AppLogger.d(
                TAG,
                "result elapsed=${elapsed}ms rawLen=${raw.length} scene=${parsed.scene} " +
                        "entities=${parsed.entities.size} answerLen=${parsed.answer.length}"
        )
        Outcome(result = parsed, usedVision = visionEnabled, modelName = modelName, elapsedMs = elapsed)
    }

    private fun buildPrompt(
            snapshot: ScreenSnapshot?,
            selection: Rect,
            localText: String,
            question: String?,
            visionEnabled: Boolean,
            previousAnswer: String?
    ): String {
        val sb = StringBuilder()
        sb.append("用户在手机上选中了一块屏幕区域（附件）。")
        if (visionEnabled) sb.append("附图为该区域的截图。")
        sb.append(
                "请理解这块内容并回答。只输出 JSON，字段：" +
                        "{\"scene\":\"当前页面是什么（中文，30字内）\"," +
                        "\"selected\":{\"type\":\"text|image|element\",\"text\":\"选中区域内的原文\"}," +
                        "\"entities\":[{\"type\":\"id_card|bank_card|phone|address|date|amount|account|url|other\",\"value\":\"...\"}]," +
                        "\"answer\":\"对选中内容的回答（中文，2-4 句，直接回答问题）\"," +
                        "\"suggested_actions\":[{\"id\":\"extract|translate|remember|search\",\"label\":\"中文动作名\"}]}"
        )
        sb.append("\n\n已知屏幕信息：")
        if (snapshot == null) {
            sb.append("\n- 无障碍节点树不可用，请仅依据图片与用户问题作答。")
        } else {
            sb.append("\n- 应用包名：").append(snapshot.packageName)
            if (snapshot.pageTitle.isNotBlank()) {
                sb.append("\n- 页面标题（启发式）：").append(snapshot.pageTitle)
            }
            sb.append("\n- 屏幕尺寸：").append(snapshot.displayWidth).append('x').append(snapshot.displayHeight)
            val lines = nodeLines(snapshot.nodes, selection)
            if (lines.isNotEmpty()) {
                sb.append("\n- 选区附近的节点（编号|类型|可点击|边界|文本）：\n")
                lines.forEach { sb.append(it).append('\n') }
            }
        }
        if (localText.isNotBlank()) {
            sb.append("\n选区本地文字（按阅读顺序，可能不全）：\n").append(localText.take(1600)).append('\n')
        }
        if (!previousAnswer.isNullOrBlank()) {
            sb.append("\n上一轮已给出的回答（用户正在追问，请结合它，不要重复整段）：\n")
                    .append(previousAnswer.take(800))
                    .append('\n')
        }
        sb.append("\n用户问题：")
                .append(question?.takeIf { it.isNotBlank() } ?: "这块内容是什么？请提取要点并说明。")
        return sb.toString()
    }

    private fun nodeLines(nodes: List<ScreenNode>, selection: Rect): List<String> {
        if (nodes.isEmpty()) return emptyList()
        val near = Rect(selection)
        near.inset(-selection.width() / 2 - 40, -selection.height() / 2 - 40)
        return nodes
                .filter { n ->
                    val inter = Rect()
                    inter.setIntersect(n.bounds, near) && !inter.isEmpty
                }
                .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
                .take(MAX_NODE_LINES)
                .map { n ->
                    val label = n.label.replace("\n", " ").take(80)
                    "[${n.id}] ${n.className.ifBlank { "node" }} " +
                            "clk=${n.clickable} [${n.bounds.left},${n.bounds.top}]" +
                            "[${n.bounds.right},${n.bounds.bottom}] \"$label\""
                }
    }

    /** 宽容解析：剥 think/代码围栏 → 取第一个平衡 JSON 对象 → 字段缺省兜底 */
    fun parse(raw: String, localText: String): LensResult {
        val cleaned = stripNoise(raw)
        val json = extractJsonObject(cleaned)
        if (json != null) {
            try {
                val obj = JSONObject(json)
                val selected = obj.optJSONObject("selected")
                val selectedText =
                        selected?.optString("text")?.trim()?.takeIf { it.isNotBlank() }
                                ?: localText
                val entities = mutableListOf<LensEntity>()
                obj.optJSONArray("entities")?.let { arr ->
                    for (i in 0 until minOf(arr.length(), 12)) {
                        val e = arr.optJSONObject(i) ?: continue
                        val v = e.optString("value").trim()
                        if (v.isNotBlank()) {
                            entities.add(LensEntity(e.optString("type", "other"), v))
                        }
                    }
                }
                val actions = mutableListOf<LensAction>()
                obj.optJSONArray("suggested_actions")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val a = arr.optJSONObject(i) ?: continue
                        val id = a.optString("id").trim()
                        val label = a.optString("label").trim().ifBlank { id }
                        if (id.isNotBlank()) actions.add(LensAction(id, label))
                    }
                }
                val merged = actions.toMutableList()
                LensResult.DEFAULT_ACTIONS.forEach { def ->
                    if (merged.none { it.id == def.id }) merged.add(def)
                }
                return LensResult(
                        scene = obj.optString("scene").trim(),
                        selectedText = selectedText,
                        entities = entities,
                        answer = obj.optString("answer").trim(),
                        suggested = merged.distinctBy { it.id },
                        raw = raw
                )
            } catch (t: Throwable) {
                AppLogger.w(TAG, "JSON 解析失败: ${t.message}")
            }
        }
        // 模型没按 JSON 回：正文直接当答案（选中文字用本地提取兜底）
        val fallbackAnswer = cleaned.trim()
        return LensResult(
                scene = "",
                selectedText = localText,
                entities = emptyList(),
                answer = fallbackAnswer,
                suggested = LensResult.DEFAULT_ACTIONS,
                raw = raw
        )
    }

    private fun stripNoise(raw: String): String {
        var s = raw
        s = s.replace(Regex("(?s)<\\s*(think|thinking|reasoning)\\s*>.*?<\\s*/\\s*(think|thinking|reasoning)\\s*>"), "")
        s = s.replace(Regex("(?s)```(?:json)?\\s*"), "").replace("```", "")
        return s.trim()
    }

    /** 取第一个括号配平的 JSON 对象（容忍前后废话） */
    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
