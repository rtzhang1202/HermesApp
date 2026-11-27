package com.ai.assistance.operit.voice.lens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.ai.assistance.operit.util.AppLogger

/**
 * 识屏「提问步」表面：冻结帧 + 可编辑选框 + 附件条 + 提问框（语音/打字）+ 结果面板。
 *
 * 为什么必须是 Activity：无障碍悬浮窗（TYPE_ACCESSIBILITY_OVERLAY）不抢焦点、且永远盖在应用窗口之上，
 * 里面的输入框既拿不到输入法、也压不住 IME。采集/圈选仍由悬浮窗完成（只有它能让目标应用保持前台），
 * 圈选完成后把同一份 [ScreenLensState]（冻结帧/节点树/选区）交给本 Activity ——
 * Compose 内容一致，悬浮窗在 onResume 后才摘除，视觉上无闪断。
 *
 * 空输入可直接点『理解』= 用默认提示词理解所选内容。
 */
class LensAskActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ScreenLensAsk"
    }

    private lateinit var controller: ScreenLensController
    private var lensState: ScreenLensState? = null

    /** 键盘高度（px）：由 WindowInsets 监听直接读，绕开 Compose imePadding 在该 ROM 失效的问题 */
    private val imeBottomPx = mutableIntStateOf(0)

    private val micPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                AppLogger.d(TAG, "RECORD_AUDIO granted=$granted")
                if (granted) controller.toggleMic() else controller.onMicDenied()
            }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = ScreenLensController.getInstance(this)
        val st = controller.currentState()
        if (st == null) {
            AppLogger.w(TAG, "no lens state, finishing")
            finish()
            return
        }
        lensState = st
        // 冻结帧是整屏截图（含状态栏/导航栏），必须全屏铺满才能 1:1 对齐选区
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AppLogger.d(
                TAG,
                "onCreate phase=${st.phase} bounds=${st.selection?.bounds} " +
                        "bitmap=${st.bitmap?.width}x${st.bitmap?.height}"
        )
        observeImeInsets()
        controller.attachAskSurface {
            runCatching {
                if (!isFinishing && !isDestroyed) finish()
            }
        }
        setContent {
            val insetDp =
                    with(LocalDensity.current) {
                        // 键盘高度即底部遮挡高度：减去系统导航栏（面板自身已 navigationBarsPadding）
                        val nav =
                                ViewCompat.getRootWindowInsets(window.decorView)
                                        ?.getInsets(WindowInsetsCompat.Type.navigationBars())
                                        ?.bottom ?: 0
                        (imeBottomPx.intValue - nav).coerceAtLeast(0).toDp()
                    }
            controller.LensContent(st, onMicTap = ::onMicTap, extraBottomInset = insetDp)
        }
    }

    override fun onResume() {
        super.onResume()
        // onResume 之后系统已完成窗口切换，此时摘悬浮窗不会露出底层应用
        controller.onAskSurfaceShown()
    }

    override fun onDestroy() {
        if (isFinishing) {
            AppLogger.d(TAG, "onDestroy (finishing) → close lens session")
            controller.onAskSurfaceDestroyed()
        }
        super.onDestroy()
    }

    /** IME 高度必须自己量：该 ROM 上 Compose imePadding 拿到的 inset 为 0，提问框会被键盘盖住 */
    private fun observeImeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            if (ime != imeBottomPx.intValue) {
                imeBottomPx.intValue = ime
                AppLogger.d(TAG, "ime inset bottom=$ime")
            }
            // 原样返回：让 Compose 继续拿到 navigationBars 等 inset（返回 CONSUMED 会全吞掉）
            insets
        }
        ViewCompat.requestApplyInsets(window.decorView)
    }

    private fun onMicTap() {
        val granted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
        if (!granted) {
            AppLogger.d(TAG, "request RECORD_AUDIO")
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            controller.toggleMic()
        }
    }
}
