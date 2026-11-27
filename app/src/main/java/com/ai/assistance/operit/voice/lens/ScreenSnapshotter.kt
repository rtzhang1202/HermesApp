package com.ai.assistance.operit.voice.lens

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.ai.assistance.operit.services.accessibility.HermesAccessibilityService
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 识屏采集层：无障碍节点树 + 系统截屏（AccessibilityService.takeScreenshot）。
 *
 * 设计要点（调研建议一）：
 * - 节点树优先于视觉分割：点按命中走 getBoundsInScreen，覆盖原生 UI 的 90%；
 * - 截图只在内存里处理，不落媒体库；落盘缩略图仅用于记忆确认卡（source_uri）；
 * - 取的是「识屏界面下方那个应用窗口」而不是 rootInActiveWindow —— 识屏 Activity 自己在最上层，
 *   rootInActiveWindow 会返回我们自己。遍历 windows（flagRetrieveInteractiveWindows）跳过本包的活动窗口即可。
 */
object ScreenSnapshotter {

    private const val TAG = "ScreenLensCapture"
    private const val MAX_NODES = 260
    private const val MAX_DEPTH = 40

    fun service(): HermesAccessibilityService? = HermesAccessibilityService.instance

    fun isConnected(): Boolean = service() != null

    fun canTakeScreenshot(svc: AccessibilityService): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            val caps = svc.serviceInfo?.capabilities ?: 0
            (caps and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0
        } catch (t: Throwable) {
            AppLogger.w(TAG, "读取 capabilities 失败: ${t.message}")
            false
        }
    }

    /**
     * 取识屏层下方应用的窗口快照。
     * 优先：windows 列表里第一个「非本包活动窗口」的应用窗口；
     * 兜底：rootInActiveWindow（当它不属于本包时，例如无障碍服务未启用 flagRetrieveInteractiveWindows）。
     */
    fun captureTopAppWindow(selfPackage: String): ScreenSnapshot? {
        val svc = service() ?: run {
            AppLogger.w(TAG, "无障碍服务未连接，无法读取节点树")
            return null
        }
        val roots = mutableListOf<Pair<AccessibilityWindowInfo?, AccessibilityNodeInfo>>()
        try {
            val wins = svc.windows.orEmpty()
            for (w in wins) {
                val root = windowRoot(w) ?: run {
                    AppLogger.d(TAG, "window type=${w.type} active=${w.isActive} root=null")
                    continue
                }
                val pkg = root.packageName?.toString().orEmpty()
                AppLogger.d(
                        TAG,
                        "window type=${w.type} active=${w.isActive} pkg=$pkg " +
                                "kids=${runCatching { root.childCount }.getOrDefault(-1)}"
                )
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                if (pkg == selfPackage && w.isActive) continue
                roots.add(w to root)
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "遍历窗口失败: ${t.message}")
        }
        if (roots.isEmpty()) {
            val active = try {
                svc.rootInActiveWindow
            } catch (t: Throwable) {
                null
            }
            if (active != null && active.packageName?.toString() != selfPackage) {
                roots.add(null to active)
            }
        }
        // 优先别的应用窗口；只有全是本包窗口（例如原页面就是 Hermes 自己）时才用本包非活动窗口
        val picked =
                roots.firstOrNull { (_, r) -> r.packageName?.toString() != selfPackage }
                        ?: roots.firstOrNull()
                        ?: run {
                            AppLogger.w(
                                    TAG,
                                    "未找到可用的应用窗口（windows=${runCatching { svc.windows?.size }.getOrNull()}）"
                            )
                            return null
                        }
        val root = picked.second
        val pkg = root.packageName?.toString().orEmpty()
        val (dw, dh) = displaySize(svc)
        // 敏感页面：包名一旦命中，连节点树都不读（不 flatten、不截屏），只回包名让上层拦截。
        val blocked = SensitiveScreenGuard.blockReason(pkg)
        if (blocked != null) {
            AppLogger.w(TAG, "sensitive package blocked before tree read pkg=$pkg reason=$blocked")
            return ScreenSnapshot(
                    packageName = pkg,
                    pageTitle = "",
                    displayWidth = dw,
                    displayHeight = dh,
                    nodes = emptyList()
            )
        }
        val nodes = flatten(root)
        val title = guessTitle(nodes, dh)
        AppLogger.d(
                TAG,
                "snapshot pkg=$pkg title=$title nodes=${nodes.size} display=${dw}x$dh " +
                        "windowActive=${picked.first?.isActive}"
        )
        return ScreenSnapshot(
                packageName = pkg,
                pageTitle = title,
                displayWidth = dw,
                displayHeight = dh,
                nodes = nodes
        )
    }

    private fun windowRoot(window: AccessibilityWindowInfo): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return try {
            window.root
        } catch (t: Throwable) {
            null
        }
    }

    private fun displaySize(svc: AccessibilityService): Pair<Int, Int> {
        return try {
            val wm = svc.getSystemService(WindowManager::class.java)
            val b = wm!!.currentWindowMetrics.bounds
            b.width() to b.height()
        } catch (t: Throwable) {
            val dm = svc.resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    /** BFS 扁平化：文本/描述/可点击/可编辑节点，剔除不可见与过小节点 */
    fun flatten(root: AccessibilityNodeInfo): List<ScreenNode> {
        val out = mutableListOf<ScreenNode>()
        val seen = HashSet<String>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && out.size < MAX_NODES * 2 && visited < 4000) {
            val (node, depth) = queue.removeFirst()
            visited++
            if (depth > MAX_DEPTH) continue
            val rect = Rect()
            try {
                node.getBoundsInScreen(rect)
            } catch (t: Throwable) {
                continue
            }
            val visible = try {
                node.isVisibleToUser
            } catch (t: Throwable) {
                true
            }
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val clickable =
                    try {
                        node.isClickable || node.isLongClickable
                    } catch (t: Throwable) {
                        false
                    }
            val editable = try {
                node.isEditable
            } catch (t: Throwable) {
                false
            }
            if (visible &&
                            !rect.isEmpty &&
                            rect.width() >= 6 &&
                            rect.height() >= 6 &&
                            (text.isNotEmpty() || desc.isNotEmpty() || clickable || editable)
            ) {
                val key = "${rect.toShortString()}|${text.hashCode()}|${desc.hashCode()}"
                if (seen.add(key)) {
                    out.add(
                            ScreenNode(
                                    id = 0,
                                    text = text.take(120),
                                    desc = desc.take(120),
                                    bounds = Rect(rect),
                                    clickable = clickable,
                                    editable = editable,
                                    className = (node.className?.toString().orEmpty()).substringAfterLast('.')
                            )
                    )
                }
            }
            val childCount = try {
                node.childCount
            } catch (t: Throwable) {
                0
            }
            for (i in 0 until childCount) {
                val child = try {
                    node.getChild(i)
                } catch (t: Throwable) {
                    null
                } ?: continue
                queue.add(child to depth + 1)
            }
        }
        return out
                .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
                .take(MAX_NODES)
                .mapIndexed { index, n -> n.copy(id = index + 1) }
    }

    /** 页面标题启发式：屏幕上部 28% 区域内第一个像标题的文字（2~40 字） */
    private fun guessTitle(nodes: List<ScreenNode>, displayHeight: Int): String {
        val limit = (displayHeight * 0.28f).toInt()
        return nodes.firstOrNull { n ->
            n.label.length in 2..40 && n.bounds.top < limit && !n.editable
        }?.label.orEmpty()
    }

    /** 点按命中：包含该点的最小「有文字」节点 → 最小可点击节点 → 最近节点 */
    fun hitTest(nodes: List<ScreenNode>, x: Int, y: Int): ScreenNode? {
        val containing = nodes.filter { it.bounds.contains(x, y) }
        containing.filter { it.hasLabel }.minByOrNull { it.area }?.let { return it }
        containing.filter { it.clickable }.minByOrNull { it.area }?.let { return it }
        containing.minByOrNull { it.area }?.let { return it }
        // 文本节点 bounds 可能比手指落点小（行内文字），退化为「最近的有文字节点」，容差 72px
        return nodes
                .filter { it.hasLabel }
                .minByOrNull { n ->
                    val dx = (n.bounds.centerX() - x).toLong()
                    val dy = (n.bounds.centerY() - y).toLong()
                    dx * dx + dy * dy
                }
                ?.takeIf { n ->
                    val dx = n.bounds.centerX() - x
                    val dy = n.bounds.centerY() - y
                    dx * dx + dy * dy <= 72 * 72
                }
    }

    /**
     * 系统截屏（整屏，含识屏层自身的暗化蒙版 —— 蒙版是均匀 alpha，不影响文字可读性）。
     * 返回 software ARGB_8888 位图（可裁剪/绘制），失败返回 null + 原因。
     */
    suspend fun takeScreenshot(timeoutMs: Long = 2500L): Result<Bitmap> {
        val svc = service() ?: return Result.failure(IllegalStateException("无障碍服务未连接"))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Result.failure(IllegalStateException("系统版本低于 Android 11，不支持无障碍截屏"))
        }
        if (!canTakeScreenshot(svc)) {
            return Result.failure(
                    IllegalStateException("无障碍服务未声明 canTakeScreenshot（需重新启用服务）")
            )
        }
        return suspendCancellableCoroutine { cont ->
            var resumed = false
            val timeout = android.os.Handler(android.os.Looper.getMainLooper())
            val timeoutTask = Runnable {
                if (!resumed) {
                    resumed = true
                    cont.resume(Result.failure(IllegalStateException("截屏超时")))
                }
            }
            timeout.postDelayed(timeoutTask, timeoutMs)
            try {
                svc.takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        androidx.core.content.ContextCompat.getMainExecutor(svc),
                        object : AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                                if (resumed) return
                                resumed = true
                                timeout.removeCallbacks(timeoutTask)
                                val bitmap = try {
                                    val hw = Bitmap.wrapHardwareBuffer(
                                            screenshot.hardwareBuffer,
                                            screenshot.colorSpace
                                    )
                                    hw?.copy(Bitmap.Config.ARGB_8888, false)
                                } catch (t: Throwable) {
                                    AppLogger.w(TAG, "wrapHardwareBuffer 失败: ${t.message}")
                                    null
                                } finally {
                                    try {
                                        screenshot.hardwareBuffer.close()
                                    } catch (_: Throwable) {}
                                }
                                if (bitmap != null) {
                                    AppLogger.d(
                                            TAG,
                                            "takeScreenshot ok ${bitmap.width}x${bitmap.height}"
                                    )
                                    cont.resume(Result.success(bitmap))
                                } else {
                                    cont.resume(
                                            Result.failure(IllegalStateException("截屏数据为空"))
                                    )
                                }
                            }

                            override fun onFailure(errorCode: Int) {
                                if (resumed) return
                                resumed = true
                                timeout.removeCallbacks(timeoutTask)
                                AppLogger.w(TAG, "takeScreenshot failed code=$errorCode")
                                cont.resume(Result.failure(IllegalStateException(reasonFor(errorCode))))
                            }
                        }
                )
            } catch (t: Throwable) {
                if (!resumed) {
                    resumed = true
                    timeout.removeCallbacks(timeoutTask)
                    AppLogger.w(TAG, "takeScreenshot 抛异常: ${t.message}")
                    cont.resume(Result.failure(t))
                }
            }
        }
    }

    private fun reasonFor(code: Int): String =
            when (code) {
                AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "截屏内部错误"
                AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
                        "无无障碍截屏权限"
                AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                        "截屏过于频繁"
                AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "无效显示"
                AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
                        "该页面禁止截屏（FLAG_SECURE）"
                else -> "截屏失败（code=$code）"
            }
}
