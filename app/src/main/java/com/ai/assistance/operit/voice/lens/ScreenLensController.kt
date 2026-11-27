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
import com.ai.assistance.operit.voice.VoiceAssistantActivity
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
 * 识屏层（Screen Lens）宿主：无障碍悬浮窗（TYPE_ACCESSIBILITY_OVERLAY）+ Compose 界面。
 *
 * 为什么不是 Activity：Activity 会把目标应用顶到后台，AccessibilityService.getWindows()
 * 随后就看不到目标应用的窗口（实测只剩 [系统窗口, 识屏窗口]），节点树为空。
 * TYPE_ACCESSIBILITY_OVERLAY 不抢焦点，目标应用仍是活动窗口 —— 节点树与截图都能拿到，
 * 也满足「按下即暗化可圈选、不等识别」的交互原则。
 *
 * 时序：按下 → 语音界面 finish + 本悬浮窗立刻出现（暗化/可画）→ 后台轮询目标窗口快照
 * → 无障碍截屏 → 选区 → 本地文字立即展示 → 多模态模型 → 结果面板（占屏 46%）。
 */
class ScreenLensController private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "ScreenLens"
        private const val WINDOW_POLL_MS = 30L
        private const val WINDOW_POLL_TIMEOUT_MS = 1500L

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
    private var lastCrop: Bitmap? = null
    private var firstDrawLogged = false

    /** 可选态（悬浮窗已出现、手势可接管）之前用户就点/画了：先记账，等快照到手再执行 */
    private var pendingTap: Pair<Int, Int>? = null
    private var pendingDrag: List<androidx.compose.ui.geometry.Offset>? = null
    private var pendingFull = false

    val isShowing: Boolean
        get() = host != null

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
            overlay.attach {
                ScreenLensScreen(
                        state = newState,
                        onTap = ::onTapAt,
                        onDragEnd = ::onDragSelected,
                        onTrailUpdate = { trail -> newState.trail = trail },
                        onFullScreen = ::selectFullScreen,
                        onResetSelection = ::resetSelection,
                        onClose = { hide() },
                        onCopy = ::copySelected,
                        onRemember = ::rememberSelected,
                        onTranslate = { reAnalyze(appContext.getString(R.string.lens_question_translate)) },
                        onSearch = ::searchSelected,
                        onContinueAsk = ::continueInVoice,
                        onDismissResult = ::dismissResult,
                        onOpenAccessibilitySettings = {
                            runCatching {
                                appContext.startActivity(
                                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                            hide()
                        }
                )
            }
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
        captureJob?.cancel()
        analyzeJob?.cancel()
        captureJob = null
        analyzeJob = null
        pendingTap = null
        pendingDrag = null
        pendingFull = false
        host?.detach()
        host = null
        state = null
        lastCrop = null
        AppLogger.d(TAG, "hidden")
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
                                    "bitmap=${bitmap?.width}x${bitmap?.height} " +
                                    "nodes=${snapshot.nodes.size} pkg=${snapshot.packageName}"
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
            val snap = withContext(Dispatchers.IO) {
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

    private fun onTapAt(x: Float, y: Float) {
        val st = state ?: return
        if (st.phase != LensPhase.SELECT && st.phase != LensPhase.CAPTURING) return
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
                        "bounds=${node?.bounds}"
        )
        st.trail = emptyList()
        analyzeSelection(LensSelection(LensSelectionKind.NODE, clamp(bounds), node))
    }

    private fun onDragSelected(points: List<androidx.compose.ui.geometry.Offset>) {
        val st = state ?: return
        if (st.phase != LensPhase.SELECT && st.phase != LensPhase.CAPTURING) return
        if (st.snapshot == null && st.bitmap == null) {
            pendingDrag = points
            AppLogger.d(TAG, "drag deferred: snapshot not ready points=${points.size}")
            return
        }
        val rect = clamp(normalizeSelectionRect(points, 1f, 1f))
        AppLogger.d(TAG, "lasso bounds=$rect points=${points.size}")
        analyzeSelection(LensSelection(LensSelectionKind.PATH, rect))
    }

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
        analyzeSelection(LensSelection(LensSelectionKind.FULL, rect))
    }

    private fun resetSelection() {
        val st = state ?: return
        st.phase = LensPhase.SELECT
        st.selection = null
        st.result = null
        st.trail = emptyList()
        st.analyzing = false
        st.localText = ""
        st.analyzeError = null
    }

    private fun dismissResult() {
        val st = state ?: return
        st.phase = LensPhase.SELECT
        st.result = null
        st.selection = null
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

    // ===================== 识别 =====================

    private fun analyzeSelection(selection: LensSelection, question: String? = null) {
        val st = state ?: return
        analyzeJob?.cancel()
        st.selection = selection
        st.phase = LensPhase.ANALYZING
        st.analyzing = true
        st.result = null
        st.analyzeError = null
        st.lastQuestion = question
        val snapshot = st.snapshot
        val localText = snapshot?.textIn(selection.bounds).orEmpty()
        st.localText = localText
        val crop = st.bitmap?.let { cropForModel(it, selection.bounds) }
        lastCrop = crop
        AppLogger.d(
                TAG,
                "analyze start bounds=${selection.bounds} localTextLen=${localText.length} " +
                        "crop=${crop?.width}x${crop?.height} question=${question ?: "-"}"
        )
        analyzeJob =
                scope.launch {
                    val outcome =
                            ScreenLensAnalyzer.analyze(
                                    context = appContext,
                                    crop = crop,
                                    snapshot = snapshot,
                                    selection = selection.bounds,
                                    question = question
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
        val sel = state?.selection ?: return
        analyzeSelection(sel, question)
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
        val title = appContext.getString(R.string.lens_memory_title_prefix) + (scene.ifBlank { text.take(18) })
        val thumbPath = saveThumbnail(lastCrop)
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
            // 悬浮窗在确认卡之上会挡住它：先收起识屏层，再拉起记忆确认卡
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
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.bing.com/search?q=" + Uri.encode(text)))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        AppLogger.d(TAG, "search dispatched len=${text.length} direct=$ok")
        hide()
    }

    private fun continueInVoice() {
        val text = currentText().trim()
        val raw =
                appContext.getString(R.string.lens_ask_prefix) +
                        (if (text.length > 500) text.take(500) + "…" else text) +
                        appContext.getString(R.string.lens_ask_suffix)
        // 预填框只有 3 行：把换行压成空格，保证「请帮我：」这段尾巴也能看见
        val context = raw.replace('\n', ' ').replace(Regex(" {2,}"), " ").trim()
        val intent =
                Intent(appContext, VoiceAssistantActivity::class.java).apply {
                    action = VoiceAssistantActivity.ACTION_OPEN_VOICE_ASSISTANT
                    putExtra(VoiceAssistantActivity.EXTRA_TEXT, context)
                    putExtra(VoiceAssistantActivity.EXTRA_AUTO_SEND, false)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
        hide()
        runCatching { appContext.startActivity(intent) }
        AppLogger.d(TAG, "continue in voice, prefillLen=${context.length}")
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
