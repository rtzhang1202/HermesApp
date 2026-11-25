package com.ai.assistance.operit.services.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.ai.assistance.operit.core.tools.system.shell.AccessibilityShellExecutor
import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * HermesApp 自有无障碍服务。
 *
 * 背景（P6 官方 runner 对照实验定位的缺陷）：伴生提供者 accessibility.apk 的 performClick 用的是
 * 零长度 Path 手势（moveTo==lineTo、50ms），在本设备上 dispatchGesture 回执 success 但目标视图
 * 收不到有效点击（同坐标裸 input tap 立即生效）。本服务提供两条不依赖该实现的点击链：
 *  1) 节点 ACTION_CLICK —— 由无障碍框架直接回调目标 View，不经过输入注入；
 *  2) 规范手势回退 —— 两点 Path（非零长度）+ 100ms。
 * 同时为 AccessibilityShellExecutor 提供真实服务名（占位服务名 .accessibility.YourAccessibilityService 缺陷）。
 */
class HermesAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "HermesA11yService"
        private const val ROOT_FETCH_TIMEOUT_MS = 700L

        @Volatile
        var instance: HermesAccessibilityService? = null
            private set

        /** AccessibilityWindowInfo.getRoot() 需 API34+，低版本一律返回 null */
        private fun windowRoot(window: AccessibilityWindowInfo): AccessibilityNodeInfo? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    window.root
                } catch (e: Exception) {
                    null
                }
            } else null
        }

        private fun describeWindows(windows: List<AccessibilityWindowInfo>): String {
            return windows.joinToString("; ") { w ->
                val r = windowRoot(w)
                "type=${w.type},active=${w.isActive},focused=${w.isFocused},rootPkg=${r?.packageName}"
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AccessibilityShellExecutor.setAccessibilityService(this)
        val si = try { serviceInfo } catch (e: Exception) { null }
        AppLogger.d(
            TAG,
            "自有无障碍服务已连接 eventTypes=${si?.eventTypes} caps=${si?.capabilities} flags=${si?.flags} feedback=${si?.feedbackType} packageNames=${si?.packageNames?.toList()}"
        )
        // 连接后延迟探测窗口内容可见性（轮询期外），用于对比 tap 时刻的窗口状态
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                val root = rootInActiveWindow
                val wins = windows
                AppLogger.d(
                    TAG,
                    "probe: root=${root != null} rootPkg=${root?.packageName}, windows=${wins.size} [${describeWindows(wins)}]"
                )
            } catch (e: Exception) {
                AppLogger.w(TAG, "probe 失败: ${e.message}")
            }
        }, 5000L)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        AccessibilityShellExecutor.setAccessibilityService(null)
        return super.onUnbind(intent)
    }

    private var eventLogCount = 0

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // 仅作为点击执行通道，不监听事件；前几条事件用于诊断窗口内容可见性
        if (event == null) return
        if (eventLogCount < 6) {
            eventLogCount++
            try {
                val wins = windows
                val activeRoot = wins.firstOrNull { it.isActive }?.let { windowRoot(it) != null } ?: false
                AppLogger.d(
                    TAG,
                    "event type=${event.eventType} pkg=${event.packageName} windows=${wins.size} activeRoot=$activeRoot root=${rootInActiveWindow != null}"
                )
            } catch (e: Exception) {
                AppLogger.d(TAG, "event type=${event.eventType} pkg=${event.packageName} (windows 查询失败: ${e.message})")
            }
        }
    }

    override fun onInterrupt() {}

    /**
     * 在屏幕坐标 (x, y) 处执行点击。
     * 优先对包含该坐标「最深的可点击/可勾选节点」执行 ACTION_CLICK（框架级回调，不经输入注入）。
     * @return true 表示目标节点已处理该点击
     */
    fun performClickAt(x: Int, y: Int): Boolean {
        // 候选根节点：活动窗口优先，其次窗口列表（需 flagRetrieveInteractiveWindows）。
        // 实测轮询期间活动窗口可能是自有悬浮窗（坐标不在其内），此时必须改用其它窗口的根节点。
        val roots = mutableListOf<AccessibilityNodeInfo>()
        fetchActiveRoot()?.let { roots.add(it) }
        try {
            val wins = windows
            val ordered = wins.filter { it.isActive } +
                wins.filter { !it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION } +
                wins.filter { !it.isActive && it.type != AccessibilityWindowInfo.TYPE_APPLICATION }
            for (w in ordered) {
                windowRoot(w)?.let { roots.add(it) }
            }
            if (roots.size > 1) {
                AppLogger.d(TAG, "候选根节点=${roots.size} windows=${wins.size} [${describeWindows(wins)}]")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "遍历窗口列表失败: ${e.message}")
        }

        for (root in roots) {
            val bounds = Rect()
            try {
                root.getBoundsInScreen(bounds)
            } catch (e: Exception) {
                continue
            }
            if (!bounds.contains(x, y)) {
                AppLogger.d(TAG, "跳过根节点 pkg=${root.packageName} bounds=$bounds（不含目标点）")
                continue
            }
            if (performClickOnRoot(root, x, y)) return true
        }

        AppLogger.d(TAG, "所有候选根节点均未完成点击（roots=${roots.size}, target=($x,$y)）")
        return false
    }

    /**
     * 取活动窗口根节点：先当前线程，失败则切主线程重试一次
     * （实测 tap 工具调用线程上 rootInActiveWindow 可能为 null，而主线程事件/探测路径可取到）。
     */
    private fun fetchActiveRoot(): AccessibilityNodeInfo? {
        try {
            rootInActiveWindow?.let { return it }
        } catch (e: Exception) {
            AppLogger.w(TAG, "获取活动窗口失败: ${e.message}")
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return null

        var result: AccessibilityNodeInfo? = null
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                result = rootInActiveWindow
            } catch (e: Exception) {
                AppLogger.w(TAG, "主线程获取活动窗口失败: ${e.message}")
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(ROOT_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            AppLogger.w(TAG, "等待主线程取根节点超时: ${e.message}")
        }
        AppLogger.d(TAG, "主线程重试取根节点：root=${result != null}")
        return result
    }

    private fun performClickOnRoot(root: AccessibilityNodeInfo, x: Int, y: Int): Boolean {
        dfsVisited = 0
        dfsPrunedAt = null
        dfsDeepest = null
        dfsDeepestDepth = -1
        dfsChildFails = 0
        val target = findActionableNode(root, x, y, 0)
        if (target == null) {
            AppLogger.d(
                TAG,
                "($x, $y) 处未找到可点击节点（root pkg=${root.packageName} rootChildren=${root.childCount} " +
                    "visited=$dfsVisited childFails=$dfsChildFails prunedAt=$dfsPrunedAt deepest=$dfsDeepest）"
            )
            return false
        }
        return performAction(target, x, y)
    }

    private var dfsVisited = 0
    private var dfsPrunedAt: String? = null
    private var dfsDeepest: String? = null
    private var dfsDeepestDepth = -1
    private var dfsChildFails = 0

    private fun performAction(node: AccessibilityNodeInfo, x: Int, y: Int): Boolean {
        return try {
            val handled = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            AppLogger.d(TAG, "($x, $y) 节点 ACTION_CLICK handled=$handled class=${node.className}")
            handled
        } catch (e: Exception) {
            AppLogger.w(TAG, "ACTION_CLICK 失败: ${e.message}")
            false
        }
    }

    /**
     * 规范点击手势回退：两点 Path + 100ms。
     * （伴生实现为零长度 Path + 50ms，疑似因此在目标视图上无效，故此处不复用其形状。）
     */
    fun dispatchTapGesture(x: Int, y: Int): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x.toFloat(), y.toFloat() + 1f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        return try {
            val queued = dispatchGesture(gesture, null, null)
            AppLogger.d(TAG, "dispatchTapGesture($x, $y) queued=$queued")
            queued
        } catch (e: Exception) {
            AppLogger.w(TAG, "dispatchGesture 失败: ${e.message}")
            false
        }
    }

    /** 深度优先返回包含 (x, y) 的最深可点击/可勾选节点；无则返回 null（由调用方走手势回退）。
     *  注意：不按祖先边界剪枝 —— Compose 树中存在「祖先边界不含目标点但后代含」的结构
     *  （实测 Settings Wi-Fi 行在伴生/自有服务树中均需全量遍历才能命中）。 */
    private fun findActionableNode(
        node: AccessibilityNodeInfo,
        x: Int,
        y: Int,
        depth: Int
    ): AccessibilityNodeInfo? {
        dfsVisited++
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val contains = bounds.isEmpty || bounds.contains(x, y)

        if (contains && depth >= dfsDeepestDepth) {
            dfsDeepestDepth = depth
            dfsDeepest = "d=$depth cls=${node.className} bounds=$bounds chk=${node.isCheckable} clk=${node.isClickable} lc=${node.isLongClickable} kids=${node.childCount}"
        }

        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            }
            if (child == null) {
                dfsChildFails++
                continue
            }
            if (depth >= 8) {
                val cb = Rect()
                child.getBoundsInScreen(cb)
                AppLogger.d(TAG, "dfs child d=${depth + 1} idx=$i cls=${child.className} bounds=$cb contains=${cb.contains(x, y)} kids=${child.childCount}")
            }
            findActionableNode(child, x, y, depth + 1)?.let { return it }
        }

        if (contains && (node.isCheckable || node.isClickable || node.isLongClickable)) return node
        return null
    }
}
