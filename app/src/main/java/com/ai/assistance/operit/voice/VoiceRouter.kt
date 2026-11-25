package com.ai.assistance.operit.voice

/**
 * 语音请求难度路由（语音助手专用）。
 *
 * - [Route.SIMPLE]：简单问答/寒暄/常识 → 走短记忆 session 直接回复。
 * - [Route.SUBAGENT]：开发、搜索、复杂多步任务 → 拉起后台子代理（run_ui_subagent）
 *   异步执行，语音界面先回执「已交给后台处理」，结果回填到同一 session。
 *
 * 纯关键词启发式，决策结果打到 logcat（tag=VoiceAssistant）以便验收取证。
 */
object VoiceRouter {

    enum class Route { SIMPLE, SUBAGENT }

    private val devKeywords = listOf(
        // 中文
        "开发", "写代码", "写个", "写一个", "代码", "程序", "脚本", "编程",
        "构建", "编译", "部署", "调试", "重构", "工程", "实现一个", "创建一个",
        "改一下", "修一下", "报错", "接口", "自动化脚本",
        // 英文
        "build", "compile", "deploy", "refactor", "debug", "code", "program",
        "script", "python", "kotlin", "java ", "bug", "website", "web app"
    )

    private val searchKeywords = listOf(
        // 中文
        "搜索", "搜一下", "查资料", "查一下资料", "调研", "网上找", "帮我找",
        "下载资料", "最新消息", "新闻",
        // 英文
        "search", "research", "look up", "google", "browse the web",
        "find online", "download"
    )

    fun classify(text: String): Route {
        val t = text.lowercase()
        val hit = devKeywords.any { t.contains(it) } || searchKeywords.any { t.contains(it) }
        val route = if (hit) Route.SUBAGENT else Route.SIMPLE
        return route
    }
}
