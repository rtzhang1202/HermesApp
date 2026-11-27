package com.ai.assistance.operit.voice.lens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.UserMemoryKind
import com.ai.assistance.operit.data.model.UserMemorySource
import com.ai.assistance.operit.data.model.UserMemoryStatus
import com.ai.assistance.operit.data.model.UserMemoryType
import com.ai.assistance.operit.data.repository.UserMemoryStore
import com.ai.assistance.operit.services.accessibility.HermesAccessibilityService
import com.ai.assistance.operit.ui.features.usermemory.UserMemoryConfirmActivity
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 识屏层（Screen Lens）宿主。
 *
 * 表面分两段，原因是输入法：
 * 1) **采集 + 圈选**：无障碍悬浮窗（TYPE_ACCESSIBILITY_OVERLAY，不抢焦点）。只有这层能让目标应用
 *    保持前台，节点树与截图才拿得到；按下即暗化可圈选，不等识别（<150ms 进入可选态）。
 * 2) **提问 + 结果**：普通 Activity（LensAskActivity）。无障碍悬浮窗永远盖在应用窗口之上、
 *    且不抢焦点 —— 输入框拿不到输入法。圈选完成后把冻结帧/节点树/选区交给 Activity，
 *    Activity 用同一份 [ScreenLensState] 与同一套 Compose UI 渲染（像素一致，无闪白），
 *    悬浮窗在 Activity onResume 后再摘掉，避免过渡期看到真实应用。
 *
 * 交互（本轮改造）：圈选/全屏都不立即识别 —— 先落到「可编辑选框（整体拖动 + 四角/四边改尺寸，
 * 触控 44dp）+ 附件条（选区截图缩略图 + 文字摘要，可删）+ 提问框」，点『理解』才发起识别；
 * 输入框只放用户自己的话，ASR 结果按光标插入/替换选段，绝不整段清空。
 */
class ScreenLensController private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "ScreenLens"
        private const val WINDOW_POLL_MS = 30L
        private const val WINDOW_POLL_TIMEOUT_MS = 1500L
        private const val ASK_SURFACE_GUARD_MS = 4_000L

        @Volatile private var INSTANCE: ScreenLensController? = null

        fun getInstance(context: Context): ScreenLensController =
                INSTANCE
                        ?: synchronized(this) {
                            INSTANCE
                                    ?: ScreenLensController(context.applicationContext).also {
                                        INSTANCE = it
                                    }
                        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var state: ScreenLensState? = null
    private var host: OverlayHost? = null
    private var captureJob: Job? = null
    private var analyzeJob: Job? = null
    private var askOpenGuard: Job? = null
    private var lastCrop: Bitmap? = null
    private var firstDrawLogged = false

    /** 拖动中的缩略图重算去抖：文字每帧都重提（便宜），位图不必每帧都裁 */
    private var attachDebounce: Runnable? = null

    /** 提问层（Activity）是否已在运行；运行期间 beginAsk 不再重复拉 Activity */
    private var askSurfaceAlive = false
    private var askSurfaceClose: (() -> Unit)? = null
    private var askOpenAt = 0L

    private var voice: ScreenLensVoiceInput? = null

    /** 可选态（悬浮窗已出现、手势可接管）之前用户就点/画了：先记账，等快照到手再执行 */
    private var pendingTap: Pair<Int, Int>? = null
    private var pendingDrag: List<androidx.compose.ui.geometry.Offset>? = null
    private var pendingFull = false

    val isShowing: Boolean
        get() = host != null

    /** Activity 侧取当前会话状态（同一对象，不复制） */
    fun currentState(): ScreenLensState? = state

    // ===================== 显示 / 关闭 =====================

    fun show(tapT0: Long) {
        // 无障碍服务重连时系统会销毁它名下的悬浮窗，但单例里还留着 host 引用；
        // 不校验就会持续静默 no-op（症状：点识屏按钮只关语音界面、不弹识屏层）。
        val current = host
        if (current != null) {
            if (current.isAttached()) {
                AppLogger.d(TAG, "show ignored: overlay already attached")
                return
            }
            AppLogger.w(TAG, "stale overlay host detected (window gone), rebuilding")
            runCatching { current.detach() }
            host = null
        }
        val svc = ScreenSnapshotter.service()
        if (svc == null) {
            Toast.makeText(appContext, R.string.lens_no_accessibility, Toast.LENGTH_LONG).show()
            AppLogger.w(TAG, "show aborted: accessibility service not connected")
            return
        }
        val newState = ScreenLensState()
        state = newState
        firstDrawLogged = false
        pendingTap = null
        pendingDrag = null
        pendingFull = false
        val overlay = OverlayHost(svc)
        host = overlay
        try {
            overlay.attach { LensContent(newState, onMicTap = {}) }
            overlay.onFirstDraw = {
                if (!firstDrawLogged) {
                    firstDrawLogged = true
                    AppLogger.d(TAG, "firstFrame sinceTap=${elapsed(tapT0)}ms")
                }
            }
            AppLogger.d(
                    TAG,
                    "selectMode sinceTap=${elapsed(tapT0)}ms (gestures accepted, no analysis waited)"
            )
            startCapture(tapT0)
        } catch (t: Throwable) {
            AppLogger.e(TAG, "attach overlay failed: ${t.message}", t)
            hide()
        }
    }

    fun hide() {
        hideInternal(notifySurface = true)
    }

    private fun hideInternal(notifySurface: Boolean) {
        if (notifySurface) {
            val cb = askSurfaceClose
            askSurfaceClose = null
            askSurfaceAlive = false
            runCatching { cb?.invoke() }
        }
        captureJob?.cancel()
        analyzeJob?.cancel()
        askOpenGuard?.cancel()
        attachDebounce?.let { mainHandler.removeCallbacks(it) }
        attachDebounce = null
        captureJob = null
        analyzeJob = null
        askOpenGuard = null
        pendingTap = null
        pendingDrag = null
        pendingFull = false
        runCatching { voice?.cancel() }
        host?.detach()
        host = null
        state = null
        lastCrop = null
        AppLogger.d(TAG, "hidden")
    }

    // ===================== 提问层面（Activity）=====================

    /** Activity onCreate 时登记关闭回调，保证 hide() 能把 Activity 一起收掉 */
    fun attachAskSurface(onClose: () -> Unit) {
        askSurfaceClose = onClose
    }

    /** Activity onResume：像素已经由 Activity 接管，此时再摘悬浮窗（过渡期不露真实应用） */
    fun onAskSurfaceShown() {
        askSurfaceAlive = true
        askOpenGuard?.cancel()
        askOpenGuard = null
        if (host != null) {
            host?.detach()
            host = null
            AppLogger.d(TAG, "ask surface shown sinceAsk=${elapsed(askOpenAt)}ms overlayDetached=true")
        } else {
            AppLogger.d(TAG, "ask surface shown sinceAsk=${elapsed(askOpenAt)}ms")
        }
    }

    /** Activity onDestroy（用户返回/关闭）→ 整场会话收尾；不再回调 finish（避免递归） */
    fun onAskSurfaceDestroyed() {
        askSurfaceAlive = false
        askSurfaceClose = null
        if (state != null) hideInternal(notifySurface = false)
    }

    // ===================== 采集 =====================

    private fun startCapture(tapT0: Long) {
        captureJob?.cancel()
        captureJob =
                scope.launch {
                    val t0 = SystemClock.uptimeMillis()
                    // 语音界面 finish 后目标应用才回到前台；轮询到「非本包应用窗口」再冻结
                    val snapshot = pollTargetWindow(tapT0)
                    val st = state ?: return@launch
                    st.snapshot = snapshot
                    val blocked = SensitiveScreenGuard.blockReason(snapshot?.packageName)
                    if (blocked != null) {
                        st.blockReason = blocked
                        st.phase = LensPhase.BLOCKED
                        AppLogger.w(
                                TAG,
                                "blocked sensitive package=${snapshot?.packageName} reason=$blocked"
                        )
                        return@launch
                    }
                    if (snapshot == null) {
                        st.fatalMessage = appContext.getString(R.string.lens_no_accessibility)
                        st.phase = LensPhase.FATAL
                        AppLogger.w(TAG, "no target window snapshot")
                        return@launch
                    }
                    // 目标窗口刚出现时，系统还在跑「返回应用」的缩放/滑入过渡；
                    // 立即截屏会拍到黑边/裁切中间态（真机实测）。等过渡沉降再冻结。
                    delay(280)
                    // 截屏前把悬浮层藏起来：否则冻结帧会把提示条/按钮/暗化一起烤进截图，
                    // 之后再叠加实时 UI 就出现双重文字（实测「Result」与「Reading the screen…」重叠）。
                    host?.setHidden(true)
                    delay(60)
                    val shot =
                            try {
                                ScreenSnapshotter.takeScreenshot()
                            } finally {
                                host?.setHidden(false)
                            }
                    val bitmap = shot.getOrNull()
                    if (state !== st) {
                        bitmap?.recycle()
                        return@launch
                    }
                    st.bitmap = bitmap
                    if (bitmap != null) {
                        // 新冻结帧：帧号 +1，旧附件（文字/缩略图）一律作废，等选区落定后重算
                        st.frameSeq += 1
                        st.attach = null
                        // 冻结帧落地后再读一次节点树。轮询阶段那次读到的可能是「返回应用」转场
                        // 中的布局，bounds 与 280ms 后的截图错位 —— 实测就是附件文字 textLen=0
                        // （框在『网络和互联网』上却提取不到字）的主因。宁可多读一次 30~60ms。
                        val aligned =
                                withContext(Dispatchers.IO) {
                                    ScreenSnapshotter.captureTopAppWindow(appContext.packageName)
                                }
                        if (aligned != null &&
                                        aligned.nodes.isNotEmpty() &&
                                        aligned.packageName == snapshot.packageName
                        ) {
                            st.snapshot = aligned
                            AppLogger.d(
                                    TAG,
                                    "node tree re-read after frame: nodes=${aligned.nodes.size} " +
                                            "(was ${snapshot.nodes.size}) pkg=${aligned.packageName}"
                            )
                        }
                    }
                    st.captureMs = SystemClock.uptimeMillis() - t0
                    if (bitmap == null) {
                        st.analyzeError =
                                appContext.getString(
                                        R.string.lens_capture_failed,
                                        shot.exceptionOrNull()?.message ?: "unknown"
                                )
                        AppLogger.w(TAG, "capture failed: ${shot.exceptionOrNull()?.message}")
                    }
                    st.phase = if (st.phase == LensPhase.CAPTURING) LensPhase.SELECT else st.phase
                    AppLogger.d(
                            TAG,
                            "selectReady sinceTap=${elapsed(tapT0)}ms capture=${st.captureMs}ms " +
                                    "bitmap=${bitmap?.width}x${bitmap?.height} frameSeq=${st.frameSeq} " +
                                    "nodes=${st.snapshot?.nodes?.size ?: -1} pkg=${snapshot.packageName}"
                    )
                    applyPendingSelection()
                }
    }

    /** 快照就绪前用户已经点/画/选了全屏：此时补执行（早于识别、但绝不丢手势） */
    private fun applyPendingSelection() {
        val tap = pendingTap
        val drag = pendingDrag
        val full = pendingFull
        pendingTap = null
        pendingDrag = null
        pendingFull = false
        when {
            tap != null -> {
                AppLogger.d(TAG, "deferred tap applied ($tap)")
                onTapAt(tap.first.toFloat(), tap.second.toFloat())
            }
            drag != null -> {
                AppLogger.d(TAG, "deferred drag applied points=${drag.size}")
                onDragSelected(drag)
            }
            full -> {
                AppLogger.d(TAG, "deferred full-screen applied")
                selectFullScreen()
            }
        }
    }

    /** 轮询直到出现「不属于本包的应用窗口」且能读出节点（语音界面 finish 需要几十~几百毫秒） */
    private suspend fun pollTargetWindow(tapT0: Long): ScreenSnapshot? {
        val deadline = SystemClock.uptimeMillis() + WINDOW_POLL_TIMEOUT_MS
        var lastSelfLog = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            val snap =
                    withContext(Dispatchers.IO) {
                        ScreenSnapshotter.captureTopAppWindow(appContext.packageName)
                    }
            if (snap != null && snap.packageName.isNotBlank()) {
                AppLogger.d(
                        TAG,
                        "target window ready sinceTap=${elapsed(tapT0)}ms pkg=${snap.packageName} " +
                                "nodes=${snap.nodes.size} title=${snap.pageTitle}"
                )
                return snap
            }
            if (snap == null && SystemClock.uptimeMillis() - lastSelfLog > 500) {
                lastSelfLog = SystemClock.uptimeMillis()
                AppLogger.d(TAG, "still no target window sinceTap=${elapsed(tapT0)}ms")
            }
            delay(WINDOW_POLL_MS)
        }
        AppLogger.w(TAG, "target window poll timeout")
        return null
    }

    // ===================== 选区 =====================

    /**
     * 点按 = 快速选中节点：走同一条提问层（Activity），但**自动发起识别**，
     * 保留「点完直接出结果」的既有快路径（不需要额外交互）。
     *
     * 为什么不再把结果画在悬浮窗里：无障碍服务重连时系统会静默销毁悬浮窗（无回调、无日志），
     * 结果面板会变成点不动的死图；Activity 不会。
     */
    private fun onTapAt(x: Float, y: Float) {
        val st = state ?: return
        if (st.phase != LensPhase.SELECT &&
                        st.phase != LensPhase.CAPTURING &&
                        st.phase != LensPhase.ASK
        )
                return
        val snapshot = st.snapshot
        val px = x.toInt()
        val py = y.toInt()
        if (snapshot == null) {
            pendingTap = px to py
            st.trail = emptyList()
            AppLogger.d(TAG, "tap ($px,$py) deferred: snapshot not ready")
            return
        }
        val node = ScreenSnapshotter.hitTest(snapshot.nodes, px, py)
        val bounds = node?.let { Rect(it.bounds) } ?: Rect(px - 24, py - 24, px + 24, py + 24)
        AppLogger.d(
                TAG,
                "tap ($px,$py) hit=${node?.id ?: -1} label=${node?.label?.take(40) ?: "-"} " +
                        "bounds=${node?.bounds} fastPath=autoAnalyze"
        )
        st.trail = emptyList()
        beginAsk(LensSelection(LensSelectionKind.NODE, clamp(bounds), node), autoSubmit = true)
    }

    /** 圈选完成 → 不识别，进入「选框修正 + 提问」 */
    private fun onDragSelected(points: List<androidx.compose.ui.geometry.Offset>) {
        val st = state ?: return
        if (st.phase != LensPhase.SELECT &&
                        st.phase != LensPhase.CAPTURING &&
                        st.phase != LensPhase.ASK
        )
                return
        if (st.snapshot == null && st.bitmap == null) {
            pendingDrag = points
            AppLogger.d(TAG, "drag deferred: snapshot not ready points=${points.size}")
            return
        }
        val rect = clamp(normalizeSelectionRect(points, 1f, 1f))
        AppLogger.d(TAG, "lasso bounds=$rect points=${points.size}")
        beginAsk(LensSelection(LensSelectionKind.PATH, rect))
    }

    /** 全屏 → 同样先提问（空输入可直接『理解』） */
    private fun selectFullScreen() {
        val st = state ?: return
        val bmp = st.bitmap
        val snap = st.snapshot
        if (bmp == null && snap == null) {
            pendingFull = true
            AppLogger.d(TAG, "full screen deferred: snapshot not ready")
            return
        }
        val rect =
                if (bmp != null) Rect(0, 0, bmp.width, bmp.height)
                else snap?.let { Rect(0, 0, it.displayWidth, it.displayHeight) }
                        ?: Rect(0, 0, 1080, 2400)
        AppLogger.d(TAG, "full screen selected bounds=$rect")
        beginAsk(LensSelection(LensSelectionKind.FULL, rect))
    }

    /** 进入提问步：冻结帧上显示可编辑选框 + 附件 + 输入框，只有『理解』才识别。
     *  [autoSubmit]=true 用于点按快路径：面板拉起来后立刻识别（用户不需要再点一次）。 */
    private fun beginAsk(sel: LensSelection, autoSubmit: Boolean = false) {
        val st = state ?: return
        st.selection = sel
        st.phase = LensPhase.ASK
        st.trail = emptyList()
        st.result = null
        st.analyzeError = null
        st.asrDetail = null
        // 进入提问步：立刻按当前选框算一份附件（文字 + 缩略图），chip 与后续识别同源
        refreshAttachments("ask-open")
        AppLogger.d(
                TAG,
                "ask opened kind=${sel.kind} bounds=${sel.bounds} surfaceAlive=$askSurfaceAlive " +
                        "autoSubmit=$autoSubmit hint=${if (autoSubmit) "tap fast path" else "no analyze until submit"}"
        )
        if (!askSurfaceAlive) {
            askOpenAt = SystemClock.uptimeMillis()
            val intent =
                    Intent(appContext, LensAskActivity::class.java)
                            .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                            )
            val ok =
                    runCatching {
                                appContext.startActivity(intent)
                                true
                            }
                            .getOrDefault(false)
            AppLogger.d(TAG, "ask activity start ok=$ok")
            if (!ok && !autoSubmit) {
                revertAskToSelect()
                return
            }
            if (ok) {
                askOpenGuard?.cancel()
                askOpenGuard =
                        scope.launch {
                            delay(ASK_SURFACE_GUARD_MS)
                            if (!askSurfaceAlive) {
                                AppLogger.w(
                                        TAG,
                                        "ask surface missing after ${ASK_SURFACE_GUARD_MS}ms"
                                )
                                if (!autoSubmit) revertAskToSelect()
                            }
                        }
            }
        }
        if (autoSubmit) {
            analyzeSelection(
                    LensSelection(sel.kind, Rect(sel.bounds), sel.node),
                    question = null,
                    includeImage = st.attachImage,
                    includeText = st.attachText
            )
        }
    }

    private fun revertAskToSelect() {
        val st = state ?: return
        if (st.phase == LensPhase.ASK) st.phase = LensPhase.SELECT
        askOpenGuard?.cancel()
        askOpenGuard = null
        toast(R.string.lens_ask_open_failed)
    }

    private fun resetSelection() {
        val st = state ?: return
        attachDebounce?.let { mainHandler.removeCallbacks(it) }
        attachDebounce = null
        st.phase = LensPhase.SELECT
        st.selection = null
        st.attach = null
        st.result = null
        st.trail = emptyList()
        st.analyzing = false
        st.localText = ""
        st.analyzeError = null
        st.asrDetail = null
        runCatching { voice?.cancel() }
        st.asrState = LensAsrState.IDLE
        AppLogger.d(TAG, "selection reset (back to lasso)")
    }

    private fun dismissResult() {
        val st = state ?: return
        st.phase = LensPhase.SELECT
        st.result = null
        st.selection = null
        st.attach = null
        st.trail = emptyList()
        st.analyzing = false
    }

    private fun clamp(rect: Rect): Rect {
        val st = state
        val bmp = st?.bitmap
        val w = bmp?.width ?: st?.snapshot?.displayWidth ?: rect.right
        val h = bmp?.height ?: st?.snapshot?.displayHeight ?: rect.bottom
        val out =
                Rect(
                        rect.left.coerceIn(0, maxOf(0, w - 1)),
                        rect.top.coerceIn(0, maxOf(0, h - 1)),
                        rect.right.coerceIn(0, w),
                        rect.bottom.coerceIn(0, h)
                )
        if (out.width() < 16) out.right = (out.left + 16).coerceAtMost(w)
        if (out.height() < 16) out.bottom = (out.top + 16).coerceAtMost(h)
        return out
    }

    // ===================== 选框修正 =====================

    private fun onRectEditStart(grab: RectGrab, before: Rect) {
        attachDebounce?.let { mainHandler.removeCallbacks(it) }
        attachDebounce = null
        val att = state?.attach
        AppLogger.d(
                TAG,
                "rectEdit begin grab=$grab before=$before " +
                        "chipBounds=${att?.bounds} chipTextLen=${att?.textLen ?: -1} frame=${att?.frameId ?: "none"}"
        )
    }

    /**
     * 拖动/改尺寸中：选框先跟着走，附件**文字立刻按新 bounds 重提**（节点树过滤很便宜），
     * 缩略图去抖 120ms 再裁 —— 目的就是让 chip 永远不可能停在旧那一行。
     */
    private fun onRectEdited(rect: Rect) {
        val st = state ?: return
        val sel = st.selection ?: return
        val newRect = clamp(rect)
        if (newRect == sel.bounds) return
        st.selection = LensSelection(sel.kind, newRect, sel.node)
        // 文字即时刷新（quiet：拖动期间不刷日志），缩略图去抖
        refreshAttachments("rect-edit-live", withThumb = false, quiet = true)
        attachDebounce?.let { mainHandler.removeCallbacks(it) }
        val job =
                Runnable {
                    attachDebounce = null
                    val cur = state ?: return@Runnable
                    if (cur.phase == LensPhase.ASK) refreshAttachments("rect-edit-live")
                }
        attachDebounce = job
        mainHandler.postDelayed(job, 120L)
    }

    /**
     * 松开手 = 一次编辑结束：**整份附件重算**（文字 + 缩略图）并留成对日志，
     * 与上面的 `rectEdit begin/end` 一起可复核「框动到哪里、chip 就换到哪里」。
     */
    private fun onRectEditEnd(grab: RectGrab, before: Rect, after: Rect) {
        attachDebounce?.let { mainHandler.removeCallbacks(it) }
        attachDebounce = null
        val st = state ?: return
        val sel = st.selection ?: return
        val finalRect = clamp(after)
        st.selection = LensSelection(sel.kind, finalRect, sel.node)
        refreshAttachments("rect-edit-end")
        AppLogger.d(
                TAG,
                "rectEdit end grab=$grab before=$before after=$finalRect " +
                        "w=${finalRect.width()} h=${finalRect.height()}"
        )
    }

    /**
     * 附件快照重算：文字（节点树按 bounds 提取）+ 缩略图（冻结帧按同一 bounds 重裁），
     * 整份替换 [ScreenLensState.attach] —— chip 与送识别的裁剪/文字都只读它。
     *
     * textLen=0 时 text 是空串，UI 显示「未识别到文字」占位；**绝不复用上一次的文字**。
     * [withThumb]=false 只重提文字（拖动中逐帧调用），旧缩略图先留着，稍后去抖补裁。
     */
    private fun refreshAttachments(reason: String, withThumb: Boolean = true, quiet: Boolean = false) {
        val st = state ?: run {
            AppLogger.w(TAG, "attachments skipped reason=$reason: no state")
            return
        }
        val sel = st.selection ?: run {
            AppLogger.w(TAG, "attachments skipped reason=$reason: no selection")
            return
        }
        val bounds = Rect(sel.bounds)
        val prev = st.attach
        val frame = st.bitmap
        val text = st.snapshot?.textIn(bounds).orEmpty()
        val thumb =
                if (withThumb) frame?.let { thumbOf(it, bounds) }
                else if (prev != null && prev.frame === frame) prev.thumb else frame?.let { thumbOf(it, bounds) }
        val snap =
                LensAttachSnapshot(
                        bounds = bounds,
                        frameSeq = st.frameSeq,
                        frame = frame,
                        text = text,
                        thumb = thumb,
                        reason = reason
                )
        st.attach = snap
        if (quiet) return
        val snapNodes = st.snapshot?.nodes
        AppLogger.d(
                TAG,
                "attachments reason=$reason bounds=$bounds textLen=${snap.textLen} " +
                        "thumb=${thumb?.let { "${it.width}x${it.height}" } ?: "none"} " +
                        "frame=${snap.frameId} image=${st.attachImage} text=${st.attachText} " +
                        "nodes=${snapNodes?.size ?: -1} labeled=${snapNodes?.count { it.hasLabel } ?: -1} " +
                        "inBox=${snapNodes?.let { ScreenSnapshot.filterIn(it, bounds) }?.size ?: -1} " +
                        "preview=" +
                        text.replace('\n', ' ').take(28).ifBlank { "<empty→未识别到文字>" }
        )
    }

    /**
     * 取「当前选框」的附件快照（提交识别前调用）。
     * 快照缺失、或 bounds / 冻结帧已经变了，就先按同一帧重算一份 ——
     * 这样『理解』送出去的文字与裁剪图，必然就是附件条上展示的那一份。
     */
    private fun ensureAttachments(reason: String): LensAttachSnapshot? {
        val st = state ?: return null
        val sel = st.selection ?: return null
        val cur = st.attach
        if (cur != null &&
                        cur.bounds == sel.bounds &&
                        cur.frame === st.bitmap &&
                        cur.frameSeq == st.frameSeq
        ) {
            return cur
        }
        AppLogger.d(
                TAG,
                "attachments rebuild reason=$reason " +
                        "why=${if (cur == null) "missing" else if (cur.bounds != sel.bounds) "bounds ${cur.bounds}→${sel.bounds}" else "frame changed"}"
        )
        refreshAttachments(reason)
        return st.attach
    }

    private fun thumbOf(source: Bitmap, bounds: Rect, maxSide: Int = 256): Bitmap? {
        val rect = Rect(clamp(bounds))
        rect.intersect(0, 0, source.width, source.height)
        if (rect.isEmpty) return null
        return try {
            val raw = Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
            val side = maxOf(raw.width, raw.height)
            val out =
                    if (side <= maxSide) raw
                    else
                            Bitmap.createScaledBitmap(
                                    raw,
                                    (raw.width * maxSide / side).coerceAtLeast(1),
                                    (raw.height * maxSide / side).coerceAtLeast(1),
                                    true
                            )
            if (out !== raw && raw !== source) raw.recycle()
            out
        } catch (t: Throwable) {
            AppLogger.w(TAG, "thumb failed: ${t.message}")
            null
        }
    }

    // ===================== 提问 / 语音 =====================

    /** 『理解』/『继续问』：ASK 是首次提问，RESULT 是带上一轮回答的追问 */
    fun submitQuestion() {
        val st = state ?: return
        val sel = st.selection ?: return
        val question = st.askField.text.trim()
        val followUp = st.phase == LensPhase.RESULT
        val previousAnswer = if (followUp) st.result?.answer else null
        AppLogger.d(
                TAG,
                "submit followUp=$followUp bounds=${sel.bounds} " +
                        "question=${question.ifBlank { "<empty→default>" }} " +
                        "attachImage=${st.attachImage} attachText=${st.attachText}"
        )
        // 输入框只放用户自己的话：提交后清空，避免把选中内容或旧问题留在框里
        st.askField = TextFieldValue("")
        analyzeSelection(
                LensSelection(sel.kind, Rect(sel.bounds), sel.node),
                question.takeIf { it.isNotBlank() },
                st.attachImage,
                st.attachText,
                previousAnswer
        )
    }

    fun toggleMic() {
        val st = state ?: return
        when (st.asrState) {
            LensAsrState.IDLE -> {
                st.asrDetail = null
                voiceInput().start()
            }
            LensAsrState.LISTENING -> voiceInput().stop()
            LensAsrState.RECOGNIZING -> Unit
        }
    }

    fun onMicDenied() {
        val st = state ?: return
        st.asrState = LensAsrState.IDLE
        st.asrDetail = appContext.getString(R.string.lens_asr_mic_denied)
        AppLogger.w(TAG, "mic permission denied, text input only")
    }

    private fun voiceInput(): ScreenLensVoiceInput =
            voice ?: ScreenLensVoiceInput(appContext) { ev -> onVoiceEvent(ev) }.also { voice = it }

    private fun onVoiceEvent(ev: ScreenLensVoiceInput.Event) {
        val st = state ?: return
        when (ev) {
            is ScreenLensVoiceInput.Event.Listening -> {
                st.asrState = LensAsrState.LISTENING
                st.asrDetail = appContext.getString(R.string.lens_asr_listening)
                AppLogger.d(TAG, "asr listening engine=${ev.engine} lang=zh")
            }
            is ScreenLensVoiceInput.Event.Volume -> st.volume = ev.level
            is ScreenLensVoiceInput.Event.Recognizing -> {
                st.asrState = LensAsrState.RECOGNIZING
                st.volume = 0f
                st.asrDetail =
                        appContext.getString(
                                if (ev.engine.startsWith("cloud")) R.string.lens_asr_recognizing
                                else R.string.lens_asr_recognizing_local
                        )
                AppLogger.d(TAG, "asr recognizing engine=${ev.engine}")
            }
            is ScreenLensVoiceInput.Event.Info -> {
                st.asrDetail = ev.text
                AppLogger.d(TAG, "asr info: ${ev.text}")
            }
            is ScreenLensVoiceInput.Event.Final -> {
                st.asrState = LensAsrState.IDLE
                st.volume = 0f
                st.asrDetail = null
                appendAsrText(st, ev.text)
            }
            is ScreenLensVoiceInput.Event.Failed -> {
                st.asrState = LensAsrState.IDLE
                st.volume = 0f
                st.asrDetail = appContext.getString(R.string.lens_asr_failed, ev.reason)
                AppLogger.w(TAG, "asr failed: ${ev.reason}")
            }
        }
    }

    /**
     * ASR 回填：只负责「用户提问」这一段 —— 在光标处插入 / 替换当前选段，
     * 绝不整段清空已有手输内容；无有效光标时按追加处理。
     */
    private fun appendAsrText(st: ScreenLensState, asrText: String) {
        val clean = asrText.trim()
        if (clean.isBlank()) {
            st.asrDetail = appContext.getString(R.string.lens_asr_empty)
            AppLogger.w(TAG, "asr final empty, field untouched")
            return
        }
        val tv = st.askField
        val before = tv.text
        val sel = tv.selection
        val start: Int
        val end: Int
        val reason: String
        if (sel.collapsed && sel.start == 0 && before.isNotEmpty()) {
            // 从未落光标（常见：刚打完字就点麦克风）→ 追加到末尾，绝不清空
            start = before.length
            end = before.length
            reason = "append-no-cursor"
        } else {
            start = sel.min.coerceIn(0, before.length)
            end = sel.max.coerceIn(0, before.length)
            reason = if (start == end) "insert-cursor" else "replace-selection"
        }
        val needLeadingSpace =
                start > 0 &&
                        !before[start - 1].isWhitespace() &&
                        !clean.first().isWhitespace() &&
                        clean.first() !in "，。,.!?！？;；:：、）)】]"
        val needTrailingSpace =
                end < before.length &&
                        !before[end].isWhitespace() &&
                        !clean.last().isWhitespace()
        val insert =
                (if (needLeadingSpace) " " else "") +
                        clean +
                        (if (needTrailingSpace) " " else "")
        val newText = before.substring(0, start) + insert + before.substring(end)
        val cursor = start + insert.length
        st.askField = TextFieldValue(newText, androidx.compose.ui.text.TextRange(cursor))
        st.asrState = LensAsrState.IDLE
        st.volume = 0f
        st.asrDetail = null
        AppLogger.d(
                TAG,
                "asr fill mode=$reason beforeLen=${before.length} afterLen=${newText.length} " +
                        "cursor=$cursor keptUserText=${before.isNotEmpty()} text=${clean.take(60)}"
        )
    }

    // ===================== 识别 =====================

    private fun analyzeSelection(
            selection: LensSelection,
            question: String? = null,
            includeImage: Boolean = true,
            includeText: Boolean = true,
            previousAnswer: String? = null
    ) {
        val st = state ?: return
        analyzeJob?.cancel()
        attachDebounce?.let { mainHandler.removeCallbacks(it) }
        attachDebounce = null
        st.selection = LensSelection(selection.kind, Rect(selection.bounds), selection.node)
        st.phase = LensPhase.ANALYZING
        st.analyzing = true
        st.result = null
        st.analyzeError = null
        st.lastQuestion = question
        st.asrDetail = null
        // 同源同帧：附件条展示的那一份快照（文字 + 从同一冻结帧裁的图）就是送模型的唯一输入。
        // 若选框刚被改过而快照还没跟上，ensureAttachments 会先按同一帧重算 —— 于是
        // 「chip 写的是哪一行」=「模型看到的是哪一行」，不会再出现回答与选区不符。
        val attach = ensureAttachments("submit")
        val rect = attach?.bounds ?: Rect(selection.bounds)
        st.selection = LensSelection(selection.kind, Rect(rect), selection.node)
        val localText = if (includeText) attach?.text.orEmpty() else ""
        st.localText = localText
        val crop =
                if (includeImage) attach?.let { a -> a.frame?.let { cropForModel(it, a.bounds) } }
                else null
        lastCrop = crop
        AppLogger.d(
                TAG,
                "analyze start bounds=$rect localTextLen=${localText.length} " +
                        "attachReason=${attach?.reason ?: "none"} frame=${attach?.frameId ?: "none"} " +
                        "chipTextLen=${attach?.textLen ?: -1} " +
                        "thumb=${attach?.thumb?.let { "${it.width}x${it.height}" } ?: "none"} " +
                        "crop=${crop?.width}x${crop?.height} question=${question ?: "<default>"} " +
                        "attachImage=$includeImage attachText=$includeText " +
                        "followUp=${previousAnswer != null}"
        )
        analyzeJob =
                scope.launch {
                    val outcome =
                            ScreenLensAnalyzer.analyze(
                                    context = appContext,
                                    crop = crop,
                                    snapshot = st.snapshot,
                                    selection = rect,
                                    question = question,
                                    // 用附件快照里的文字（同一份），不让分析层自己再提取一遍
                                    localText = if (includeText) localText else null,
                                    includeText = includeText,
                                    previousAnswer = previousAnswer
                            )
                    if (state !== st) return@launch
                    st.result = outcome.result
                    st.visionUsed = outcome.usedVision
                    st.modelName = outcome.modelName
                    st.elapsedMs = outcome.elapsedMs
                    st.analyzing = false
                    st.phase = LensPhase.RESULT
                    if (outcome.result.answer.isBlank() &&
                                    outcome.result.selectedText.isBlank()) {
                        st.analyzeError = appContext.getString(R.string.lens_model_failed)
                    }
                    AppLogger.d(
                            TAG,
                            "analyze done elapsed=${outcome.elapsedMs}ms scene=${outcome.result.scene} " +
                                    "answer=${outcome.result.answer.take(80)}"
                    )
                }
    }

    private fun reAnalyze(question: String) {
        val st = state ?: return
        val sel = st.selection ?: return
        analyzeSelection(
                LensSelection(sel.kind, Rect(sel.bounds), sel.node),
                question,
                st.attachImage,
                st.attachText
        )
    }

    private fun cropForModel(source: Bitmap, bounds: Rect): Bitmap? {
        val rect = clamp(bounds)
        if (rect.isEmpty) return null
        val safe = Rect(rect).apply { intersect(0, 0, source.width, source.height) }
        if (safe.isEmpty) return null
        return try {
            val raw = Bitmap.createBitmap(source, safe.left, safe.top, safe.width(), safe.height())
            val maxSide = maxOf(raw.width, raw.height)
            if (maxSide <= 1280) raw
            else {
                val scale = 1280f / maxSide
                val scaled =
                        Bitmap.createScaledBitmap(
                                raw,
                                (raw.width * scale).toInt().coerceAtLeast(1),
                                (raw.height * scale).toInt().coerceAtLeast(1),
                                true
                        )
                // 整屏选区时 createBitmap(source, 0,0,w,h) 返回的就是 source 本身，
                // 不能回收（否则 Compose 正在绘制的冻结帧被 recycle → Canvas crash）
                if (scaled !== raw && raw !== source) raw.recycle()
                scaled
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "crop failed: ${t.message}")
            null
        }
    }

    // ===================== 结果面板动作 =====================

    private fun currentText(): String {
        val st = state
        return st?.result?.selectedText?.takeIf { it.isNotBlank() }
                ?: st?.localText?.takeIf { it.isNotBlank() }
                ?: st?.result?.answer.orEmpty()
    }

    private fun copySelected() {
        val text = currentText()
        if (text.isBlank()) {
            toast(R.string.lens_nothing_to_remember)
            return
        }
        val cm = appContext.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("screen_lens", text))
        toast(R.string.lens_copied)
        AppLogger.d(TAG, "copied len=${text.length}")
    }

    private fun rememberSelected() {
        val st = state ?: return
        val text = currentText().trim()
        if (text.isBlank()) {
            toast(R.string.lens_nothing_to_remember)
            return
        }
        val scene = st.result?.scene.orEmpty()
        val title =
                appContext.getString(R.string.lens_memory_title_prefix) +
                        (scene.ifBlank { text.take(18) })
        val thumbPath = saveThumbnail(lastCrop ?: st.attach?.thumb)
        val pkg = st.snapshot?.packageName
        val sensitivity = sensitivityOf(text)
        scope.launch {
            val result =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            UserMemoryStore.getInstance(appContext)
                                    .insert(
                                            title = title,
                                            value = text,
                                            type = UserMemoryType.OTHER,
                                            kind = UserMemoryKind.SNIPPET,
                                            subject = null,
                                            source = UserMemorySource.SCREEN,
                                            sourceApp = pkg,
                                            sourceUri = thumbPath,
                                            confidence = 0.9,
                                            sensitivity = sensitivity,
                                            userVerified = false,
                                            status = UserMemoryStatus.PENDING,
                                            actor = "agent"
                                    )
                        }
                    }
            val item = result.getOrNull()
            if (item == null) {
                val msg = result.exceptionOrNull()?.message ?: "unknown"
                AppLogger.w(TAG, "memory insert failed: $msg")
                toast(appContext.getString(R.string.lens_memory_failed, msg))
                return@launch
            }
            AppLogger.d(
                    TAG,
                    "memory pending id=${item.id} deduped=${item.deduped} " +
                            "conflict=${item.conflictWithId} thumb=$thumbPath"
            )
            toast(R.string.lens_saved_pending)
            // 确认卡要盖在最上面：先收起识屏层（含提问 Activity），再拉起记忆确认卡
            hide()
            val shown = UserMemoryConfirmActivity.show(appContext, item.id)
            AppLogger.d(TAG, "memory confirm card shown=$shown")
        }
    }

    private fun searchSelected() {
        val text = currentText().trim()
        if (text.isBlank()) return
        val intent =
                Intent(Intent.ACTION_WEB_SEARCH)
                        .putExtra(android.app.SearchManager.QUERY, text)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { appContext.startActivity(intent); true }.getOrDefault(false)
        if (!ok) {
            runCatching {
                appContext.startActivity(
                        Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://www.bing.com/search?q=" + Uri.encode(text))
                                )
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        AppLogger.d(TAG, "search dispatched len=${text.length} direct=$ok")
        hide()
    }

    // ===================== 杂项 =====================

    private fun toast(resId: Int) {
        Toast.makeText(appContext, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(text: String) {
        Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show()
    }

    /** 落盘仅用于记忆确认卡 source_uri（应用私有目录，7 天后清理）；不上传、不进相册。 */
    private fun saveThumbnail(crop: Bitmap?): String? {
        val src = crop ?: return null
        return try {
            val dir = File(appContext.filesDir, "screen_lens").apply { mkdirs() }
            val cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
            runCatching {
                dir.listFiles()?.forEach { f ->
                    if (f.isFile && f.lastModified() < cutoff) f.delete()
                }
            }
            val file = File(dir, "lens_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out -> src.compress(Bitmap.CompressFormat.PNG, 90, out) }
            file.absolutePath
        } catch (t: Throwable) {
            AppLogger.w(TAG, "saveThumbnail failed: ${t.message}")
            null
        }
    }

    private fun sensitivityOf(text: String): Int {
        val digits = text.replace(Regex("[^0-9]"), "")
        val sensitive =
                Regex("\\d{15,19}").containsMatchIn(text) ||
                        Regex("\\d{11}").containsMatchIn(text) ||
                        Regex("(密码|验证码|password|code)\\s*[:：]?\\s*\\w{4,}", RegexOption.IGNORE_CASE)
                                .containsMatchIn(text) ||
                        digits.length >= 15
        return if (sensitive) 1 else 0
    }

    private fun elapsed(t0: Long): Long = if (t0 <= 0L) -1L else SystemClock.uptimeMillis() - t0

    // ===================== 共享 Compose 内容 =====================

    /** 悬浮窗与提问 Activity 用同一套 UI（同一 state），像素无缝 */
    @Composable
    fun LensContent(
            state: ScreenLensState,
            onMicTap: () -> Unit,
            extraBottomInset: androidx.compose.ui.unit.Dp = 0.dp
    ) {
        ScreenLensScreen(
                state = state,
                onTap = ::onTapAt,
                onDragEnd = ::onDragSelected,
                onTrailUpdate = { trail -> state.trail = trail },
                onFullScreen = ::selectFullScreen,
                onResetSelection = ::resetSelection,
                onClose = { hide() },
                onCopy = ::copySelected,
                onRemember = ::rememberSelected,
                onTranslate = { reAnalyze(appContext.getString(R.string.lens_question_translate)) },
                onSearch = ::searchSelected,
                onDismissResult = ::dismissResult,
                onSubmitQuestion = ::submitQuestion,
                onRectEditStart = ::onRectEditStart,
                onRectEdited = ::onRectEdited,
                onRectEditEnd = ::onRectEditEnd,
                onMicTap = onMicTap,
                onRemoveAttachImage = {
                    state.attachImage = false
                    AppLogger.d(TAG, "attachment removed: image")
                },
                onRemoveAttachText = {
                    state.attachText = false
                    AppLogger.d(TAG, "attachment removed: text")
                },
                onOpenAccessibilitySettings = {
                    runCatching {
                        appContext.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    hide()
                },
                extraBottomInset = extraBottomInset
        )
    }

    // ===================== 悬浮窗宿主 =====================

    private class OverlayHost(private val service: HermesAccessibilityService) {
        private val wm: WindowManager? = service.getSystemService(WindowManager::class.java)
        private var view: ComposeView? = null
        private var owner: OverlayOwner? = null
        var onFirstDraw: (() -> Unit)? = null

        fun attach(content: @Composable () -> Unit) {
            val w = wm ?: throw IllegalStateException("WindowManager unavailable")
            val o = OverlayOwner()
            owner = o
            val composeView =
                    ComposeView(service).apply {
                        setViewCompositionStrategy(
                                ViewCompositionStrategy.DisposeOnDetachedFromWindow
                        )
                        setViewTreeLifecycleOwner(o)
                        setViewTreeViewModelStoreOwner(o)
                        setViewTreeSavedStateRegistryOwner(o)
                        setContent(content)
                        viewTreeObserver.addOnDrawListener(
                                object : ViewTreeObserver.OnDrawListener {
                                    override fun onDraw() {
                                        onFirstDraw?.invoke()
                                    }
                                }
                        )
                    }
            view = composeView
            val params =
                    WindowManager.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                                    PixelFormat.TRANSLUCENT
                            )
                            .apply {
                                gravity = Gravity.TOP or Gravity.START
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    layoutInDisplayCutoutMode =
                                            WindowManager.LayoutParams
                                                    .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                                }
                            }
            w.addView(composeView, params)
        }

        fun detach() {
            val v = view ?: return
            view = null
            try {
                wm?.removeViewImmediate(v)
            } catch (t: Throwable) {
                AppLogger.w(TAG, "removeView failed: ${t.message}")
            }
            owner?.destroy()
            owner = null
        }

        /** 视窗是否还挂在 WindowManager 上（服务重连会把它摘掉） */
        fun isAttached(): Boolean = view?.isAttachedToWindow == true

        /** 截屏瞬间隐藏悬浮层（不参与合成），避免把自己画进冻结帧 */
        fun setHidden(hidden: Boolean) {
            view?.visibility = if (hidden) android.view.View.INVISIBLE else android.view.View.VISIBLE
        }
    }

    /** ComposeView 在 WindowManager 里没有 Activity，需要自带三大 owner */
    private class OverlayOwner :
            LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val store = ViewModelStore()
        private val savedStateController = SavedStateRegistryController.create(this)

        init {
            savedStateController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        override val lifecycle: Lifecycle
            get() = lifecycleRegistry

        override val viewModelStore: ViewModelStore
            get() = store

        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateController.savedStateRegistry

        fun destroy() {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            store.clear()
        }
    }
}
