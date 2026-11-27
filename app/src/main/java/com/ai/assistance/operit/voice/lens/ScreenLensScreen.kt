package com.ai.assistance.operit.voice.lens

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.R
import kotlin.math.abs

/**
 * 识屏阶段：
 * CAPTURING 采集中 → SELECT 可圈选 → ASK 选框可修正 + 提问（此步不识别）→ ANALYZING/RESULT → 拦截/错误。
 *
 * 本轮交互改造的核心：圈选/全屏都不再立即识别，先落到「可编辑选框 + 附件 + 提问框」，
 * 用户点『理解』才发起识别；选框可一直改到满意。
 */
enum class LensPhase { CAPTURING, SELECT, ASK, ANALYZING, RESULT, BLOCKED, FATAL }

/** 提问框语音态（麦克风钮三态） */
enum class LensAsrState { IDLE, LISTENING, RECOGNIZING }

/** 选框抓手：整体移动 / 四角 / 四边 */
enum class RectGrab {
    NONE,
    MOVE,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    LEFT,
    TOP,
    RIGHT,
    BOTTOM
}

/** 识屏界面的可观察状态（Controller / Activity 持有，Compose 读取） */
class ScreenLensState {
    var phase by mutableStateOf(LensPhase.CAPTURING)
    var bitmap by mutableStateOf<Bitmap?>(null)
    var snapshot by mutableStateOf<ScreenSnapshot?>(null)
    var selection by mutableStateOf<LensSelection?>(null)
    var trail by mutableStateOf<List<Offset>>(emptyList())
    var result by mutableStateOf<LensResult?>(null)
    var localText by mutableStateOf("")
    var analyzing by mutableStateOf(false)
    var analyzeError by mutableStateOf<String?>(null)
    var blockReason by mutableStateOf<String?>(null)
    var fatalMessage by mutableStateOf<String?>(null)
    var visionUsed by mutableStateOf(true)
    var modelName by mutableStateOf("")
    var elapsedMs by mutableStateOf(0L)
    var captureMs by mutableStateOf(0L)
    var lastQuestion by mutableStateOf<String?>(null)

    // ---- 提问步（ASK / 结果面板继续问）----
    /** 输入框只放用户自己的话：ASR 回填按光标插入/替换选段，绝不整段清空 */
    var askField by mutableStateOf(TextFieldValue(""))

    /** 附件：选中区域的文字摘要（以 chip 形式贴在输入框上方） */
    var selectionText by mutableStateOf("")

    /** 附件：选中区域缩略图 */
    var thumb by mutableStateOf<Bitmap?>(null)

    /** 附件开关（× 删除后不再随本次识别上传） */
    var attachImage by mutableStateOf(true)
    var attachText by mutableStateOf(true)
    var asrState by mutableStateOf(LensAsrState.IDLE)
    var asrDetail by mutableStateOf<String?>(null)
    var volume by mutableStateOf(0f)
}

private val LensAccent = Color(0xFF8B5CF6)
private val LensAccentLight = Color(0xFFA78BFA)
private val LensPanelBg = Color(0xFF14121A)
private val LensTextPrimary = Color(0xFFF4F2F8)
private val LensTextSecondary = Color(0xFF9CA3AF)
private val LensGlass = Color(0x1FFFFFFF)

@Composable
fun ScreenLensScreen(
        state: ScreenLensState,
        onTap: (Float, Float) -> Unit,
        onDragEnd: (List<Offset>) -> Unit,
        onTrailUpdate: (List<Offset>) -> Unit,
        onFullScreen: () -> Unit,
        onResetSelection: () -> Unit,
        onClose: () -> Unit,
        onCopy: () -> Unit,
        onRemember: () -> Unit,
        onTranslate: () -> Unit,
        onSearch: () -> Unit,
        onDismissResult: () -> Unit,
        onSubmitQuestion: () -> Unit,
        onRectEditStart: (RectGrab, Rect) -> Unit,
        onRectEdited: (Rect) -> Unit,
        onRectEditEnd: (RectGrab, Rect, Rect) -> Unit,
        onMicTap: () -> Unit,
        onRemoveAttachImage: () -> Unit,
        onRemoveAttachText: () -> Unit,
        onOpenAccessibilitySettings: () -> Unit,
        /** 键盘（IME）高度：由 Activity 用 WindowInsets 监听直接量出来传进来。
         *  不用 Modifier.imePadding()：translucent/部分 ROM 拿不到 ime inset（Nothing OS 实测 0）。 */
        extraBottomInset: androidx.compose.ui.unit.Dp = 0.dp
) {
    val phase = state.phase
    // 采集期只铺 10% 暗化（会一起进截图，幅度可忽略）；拿到冻结帧后铺不透明底 + 12% 蒙版；
    // 拦截/失败态没有截图，保留 80% 暗化让底下的应用仍可辨认（而不是一块全黑）。
    val rootBg =
            when {
                phase == LensPhase.CAPTURING -> Color(0x1A000000)
                state.bitmap == null -> Color(0xCC000000)
                else -> Color.Black
            }
    Box(modifier = Modifier.fillMaxSize().background(rootBg)) {
        // 1) 冻结帧（截图）。截屏包含识屏层 10% 暗化蒙版，整体略暗但文字可读。
        state.bitmap?.let { bmp ->
            Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
            )
            Box(modifier = Modifier.fillMaxSize().background(Color(0x1F000000)))
        }

        // 2) 选择/修正层（轨迹 + 选区框 + 可拖动把手）：采集期即可画，不等截图/识别
        if (phase == LensPhase.CAPTURING ||
                        phase == LensPhase.SELECT ||
                        phase == LensPhase.ASK ||
                        phase == LensPhase.ANALYZING ||
                        phase == LensPhase.RESULT) {
            SelectionCanvas(
                    state = state,
                    onTap = onTap,
                    onDragEnd = onDragEnd,
                    onTrailUpdate = onTrailUpdate,
                    onRectEditStart = onRectEditStart,
                    onRectEdited = onRectEdited,
                    onRectEditEnd = onRectEditEnd
            )
        }

        // 3) 顶部提示条（悬浮窗拿不到 Activity 的 inset 分发：读系统 status_bar_height 资源固定留白）
        val statusBarHeightDp =
                with(androidx.compose.ui.platform.LocalDensity.current) {
                    val res = androidx.compose.ui.platform.LocalContext.current.resources
                    val id = res.getIdentifier("status_bar_height", "dimen", "android")
                    val px = if (id > 0) res.getDimensionPixelSize(id) else (24 * density).toInt()
                    (px / density).dp
                }
        Row(
                modifier =
                        Modifier.fillMaxWidth()
                                .padding(
                                        start = 12.dp,
                                        end = 12.dp,
                                        top = statusBarHeightDp + 8.dp,
                                        bottom = 8.dp
                                ),
                verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                    modifier =
                            Modifier.size(40.dp)
                                    .background(Color(0x66000000), CircleShape)
                                    .border(1.dp, LensGlass, CircleShape)
                                    .clickable { onClose() },
                    contentAlignment = Alignment.Center
            ) {
                Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.lens_close),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                    text =
                            when (phase) {
                                LensPhase.CAPTURING -> stringResource(R.string.lens_hint_reading)
                                LensPhase.SELECT -> stringResource(R.string.lens_hint_select)
                                LensPhase.ASK -> stringResource(R.string.lens_hint_ask)
                                LensPhase.ANALYZING -> stringResource(R.string.lens_analyzing)
                                LensPhase.RESULT -> stringResource(R.string.lens_result_title)
                                LensPhase.BLOCKED -> stringResource(R.string.lens_blocked_title)
                                LensPhase.FATAL -> stringResource(R.string.lens_title)
                            },
                    color = Color.White,
                    fontSize = 13.sp,
                    maxLines = 2,
                    modifier =
                            Modifier.weight(1f, fill = false)
                                    .background(Color(0x66000000), RoundedCornerShape(14.dp))
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }

        // 4) 底部区域：可选态按钮条 / 提问步（选框修正 + 提问） / 结果面板（都随 IME 上浮）
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            when (phase) {
                LensPhase.SELECT, LensPhase.CAPTURING -> {
                    Row(
                            modifier =
                                    Modifier.navigationBarsPadding()
                                            .padding(horizontal = 16.dp, vertical = 20.dp)
                                            .fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LensPill(
                                icon = Icons.Filled.Fullscreen,
                                label = stringResource(R.string.lens_action_full),
                                modifier = Modifier.weight(1f),
                                onClick = onFullScreen
                        )
                        if (state.selection != null) {
                            LensPill(
                                    icon = Icons.Filled.Refresh,
                                    label = stringResource(R.string.lens_action_reselect),
                                    modifier = Modifier.weight(1f),
                                    onClick = onResetSelection
                            )
                        }
                    }
                }
                LensPhase.ASK -> {
                    AskPanel(
                            state = state,
                            submitLabel = stringResource(R.string.lens_ask_submit),
                            placeholder = stringResource(R.string.lens_ask_placeholder),
                            onReselect = onResetSelection,
                            onSubmit = onSubmitQuestion,
                            onMicTap = onMicTap,
                            onRemoveAttachImage = onRemoveAttachImage,
                            onRemoveAttachText = onRemoveAttachText,
                            modifier = Modifier.fillMaxWidth().padding(bottom = extraBottomInset)
                    )
                }
                LensPhase.ANALYZING, LensPhase.RESULT -> {
                    ResultSheet(
                            state = state,
                            onCopy = onCopy,
                            onRemember = onRemember,
                            onTranslate = onTranslate,
                            onSearch = onSearch,
                            onDismiss = onDismissResult,
                            onSubmitQuestion = onSubmitQuestion,
                            onMicTap = onMicTap,
                            onRemoveAttachImage = onRemoveAttachImage,
                            onRemoveAttachText = onRemoveAttachText,
                            modifier = Modifier.fillMaxWidth().padding(bottom = extraBottomInset)
                    )
                }
                else -> Unit
            }
        }

        // 5) 拦截 / 致命错误
        if (phase == LensPhase.BLOCKED || phase == LensPhase.FATAL) {
            Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xE6000000)),
                    contentAlignment = Alignment.Center
            ) {
                Column(
                        modifier =
                                Modifier.padding(28.dp)
                                        .background(LensPanelBg, RoundedCornerShape(20.dp))
                                        .border(1.dp, LensGlass, RoundedCornerShape(20.dp))
                                        .padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                            text =
                                    stringResource(
                                            if (phase == LensPhase.BLOCKED) R.string.lens_blocked_title
                                            else R.string.lens_title
                                    ),
                            color = LensTextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                            text =
                                    if (phase == LensPhase.BLOCKED) {
                                        stringResource(
                                                R.string.lens_blocked_body,
                                                state.blockReason.orEmpty()
                                        )
                                    } else {
                                        state.fatalMessage
                                                ?: stringResource(R.string.lens_no_accessibility)
                                    },
                            color = LensTextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (phase == LensPhase.FATAL && !ScreenSnapshotter.isConnected()) {
                            LensPill(
                                    label = stringResource(R.string.lens_open_settings),
                                    onClick = onOpenAccessibilitySettings
                            )
                        }
                        LensPill(
                                label = stringResource(R.string.lens_blocked_close),
                                primary = true,
                                onClick = onClose
                        )
                    }
                }
            }
        }
    }
}

// ===================== 选择层：圈选 + 选框修正 =====================

@Composable
private fun SelectionCanvas(
        state: ScreenLensState,
        onTap: (Float, Float) -> Unit,
        onDragEnd: (List<Offset>) -> Unit,
        onTrailUpdate: (List<Offset>) -> Unit,
        onRectEditStart: (RectGrab, Rect) -> Unit,
        onRectEdited: (Rect) -> Unit,
        onRectEditEnd: (RectGrab, Rect, Rect) -> Unit
) {
    val bitmap = state.bitmap
    Canvas(
            modifier =
                    Modifier.fillMaxSize().pointerInput(state.phase, bitmap) {
                        val drawMode =
                                state.phase == LensPhase.SELECT ||
                                        state.phase == LensPhase.CAPTURING
                        val rectEdit = state.phase == LensPhase.ASK
                        if (!drawMode && !rectEdit) return@pointerInput
                        // 画布坐标 → 显示像素（截图坐标系）：节点 bounds / 裁剪都用显示像素
                        val scaleX =
                                if (bitmap != null && bitmap.width > 0)
                                        bitmap.width.toFloat() / size.width.coerceAtLeast(1)
                                else 1f
                        val scaleY =
                                if (bitmap != null && bitmap.height > 0)
                                        bitmap.height.toFloat() / size.height.coerceAtLeast(1)
                                else 1f
                        // 触控目标 ≥44dp（四角/四边把手）
                        val handleTouch = 44.dp.toPx()
                        val minSizePx = 48.dp.toPx() * scaleX
                        val fullW = bitmap?.width?.toFloat() ?: (size.width * scaleX)
                        val fullH = bitmap?.height?.toFloat() ?: (size.height * scaleY)

                        if (rectEdit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val cur = state.selection?.bounds
                                if (cur == null ||
                                                cur.width() <= 0 ||
                                                state.selection?.kind == LensSelectionKind.FULL
                                )
                                        return@awaitEachGesture
                                val grab = grabOf(cur, down.position, scaleX, scaleY, handleTouch)
                                if (grab == RectGrab.NONE) return@awaitEachGesture
                                onRectEditStart(grab, Rect(cur))
                                var latest = Rect(cur)
                                var last = down.position
                                var change =
                                        awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                while (change != null) {
                                    val pos = change.position
                                    if (pos != last) {
                                        latest =
                                                applyGrab(
                                                        cur,
                                                        grab,
                                                        (pos.x - down.position.x) * scaleX,
                                                        (pos.y - down.position.y) * scaleY,
                                                        minSizePx,
                                                        fullW,
                                                        fullH
                                                )
                                        last = pos
                                        onRectEdited(latest)
                                        change.consume()
                                    }
                                    if (!change.pressed) break
                                    change =
                                            awaitPointerEvent()
                                                    .changes
                                                    .firstOrNull { it.id == down.id }
                                }
                                onRectEditEnd(grab, Rect(cur), latest)
                            }
                        } else {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val start = down.position
                                var moved = false
                                val points = mutableListOf(start)
                                var lastAdded = start
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change =
                                            event.changes.firstOrNull { it.id == down.id } ?: break
                                    val pos = change.position
                                    if (!moved &&
                                                    (pos - start).getDistance() >
                                                            viewConfiguration.touchSlop
                                    ) {
                                        moved = true
                                    }
                                    if (moved) {
                                        if ((pos - lastAdded).getDistance() > 3f) {
                                            points.add(pos)
                                            lastAdded = pos
                                            onTrailUpdate(points.toList())
                                        }
                                        change.consume()
                                    }
                                    if (!change.pressed) break
                                }
                                if (moved && points.size >= 2) {
                                    onDragEnd(points.map { Offset(it.x * scaleX, it.y * scaleY) })
                                } else {
                                    onTap(start.x * scaleX, start.y * scaleY)
                                }
                            }
                        }
                    }
    ) {
        val sx = if (bitmap != null && bitmap.width > 0) size.width / bitmap.width else 1f
        val sy = if (bitmap != null && bitmap.height > 0) size.height / bitmap.height else 1f
        val editable = state.phase == LensPhase.ASK

        // 已选中的节点/选区框（全屏选区不铺满紫色蒙层，只留边框）
        state.selection?.let { sel ->
            val r = sel.bounds
            val isFull = sel.kind == LensSelectionKind.FULL
            if (!isFull) {
                // 修正期把框外压暗，聚焦选区
                if (editable) {
                    val l = r.left * sx
                    val t = r.top * sy
                    val rr = r.right * sx
                    val b = r.bottom * sy
                    val shade = Color(0x73000000)
                    drawRect(shade, Offset.Zero, Size(size.width, t.coerceAtLeast(0f)))
                    drawRect(
                            shade,
                            Offset(0f, b),
                            Size(size.width, (size.height - b).coerceAtLeast(0f))
                    )
                    drawRect(
                            shade,
                            Offset(0f, t),
                            Size(l.coerceAtLeast(0f), (b - t).coerceAtLeast(0f))
                    )
                    drawRect(
                            shade,
                            Offset(rr, t),
                            Size((size.width - rr).coerceAtLeast(0f), (b - t).coerceAtLeast(0f))
                    )
                }
                drawRect(
                        color = LensAccent.copy(alpha = 0.18f),
                        topLeft = Offset(r.left * sx, r.top * sy),
                        size = Size(r.width() * sx, r.height() * sy)
                )
            }
            drawRect(
                    color = LensAccentLight,
                    topLeft = Offset(r.left * sx, r.top * sy),
                    size = Size(r.width() * sx, r.height() * sy),
                    style = Stroke(width = if (editable) 4f else 3f)
            )
            if (editable && !isFull) {
                drawEditHandles(r, sx, sy)
            } else if (!isFull) {
                listOf(
                                Offset(r.left * sx, r.top * sy),
                                Offset(r.right * sx, r.top * sy),
                                Offset(r.left * sx, r.bottom * sy),
                                Offset(r.right * sx, r.bottom * sy)
                        )
                        .forEach { drawCircle(color = Color.White, radius = 7f, center = it) }
            }
        }

        // 手绘轨迹
        val trail = state.trail
        if (trail.size >= 2) {
            val path =
                    Path().apply {
                        moveTo(trail[0].x, trail[0].y)
                        for (i in 1 until trail.size) lineTo(trail[i].x, trail[i].y)
                    }
            drawPath(
                    path = path,
                    color = LensAccentLight.copy(alpha = 0.9f),
                    style = Stroke(width = 10f, cap = StrokeCap.Round)
            )
        }
    }
}

/** 四角圆点 + 四边中点把手（视觉 18dp / 触控 44dp） */
private fun DrawScope.drawEditHandles(r: Rect, sx: Float, sy: Float) {
    val l = r.left * sx
    val t = r.top * sy
    val rr = r.right * sx
    val b = r.bottom * sy
    val radius = 9.dp.toPx()
    val corners = listOf(Offset(l, t), Offset(rr, t), Offset(l, b), Offset(rr, b))
    corners.forEach { c ->
        drawCircle(color = Color.White, radius = radius, center = c)
        drawCircle(color = LensAccent, radius = radius, center = c, style = Stroke(width = 3f))
    }
    val barLen = 26.dp.toPx()
    val barThick = 5.dp.toPx()
    val mids =
            listOf(
                    Offset((l + rr) / 2f, t) to Size(barLen, barThick),
                    Offset((l + rr) / 2f, b) to Size(barLen, barThick),
                    Offset(l, (t + b) / 2f) to Size(barThick, barLen),
                    Offset(rr, (t + b) / 2f) to Size(barThick, barLen)
            )
    mids.forEach { (c, s) ->
        val topLeft = Offset(c.x - s.width / 2f, c.y - s.height / 2f)
        val cr = CornerRadius(barThick / 2f)
        drawRoundRect(color = Color.White, topLeft = topLeft, size = s, cornerRadius = cr)
        drawRoundRect(
                color = LensAccent,
                topLeft = topLeft,
                size = s,
                cornerRadius = cr,
                style = Stroke(width = 2f)
        )
    }
}

/** 落点命中哪个抓手：角 → 内部（整体移动）→ 边 */
private fun grabOf(r: Rect, p: Offset, sx: Float, sy: Float, touchPx: Float): RectGrab {
    if (r.width() <= 0 || r.height() <= 0) return RectGrab.NONE
    val l = r.left * sx
    val t = r.top * sy
    val rr = r.right * sx
    val b = r.bottom * sy
    val h = touchPx / 2f
    if (p.x < l - h || p.x > rr + h || p.y < t - h || p.y > b + h) return RectGrab.NONE
    val nearL = abs(p.x - l) <= h
    val nearR = abs(p.x - rr) <= h
    val nearT = abs(p.y - t) <= h
    val nearB = abs(p.y - b) <= h
    if (nearL && nearT) return RectGrab.TOP_LEFT
    if (nearR && nearT) return RectGrab.TOP_RIGHT
    if (nearL && nearB) return RectGrab.BOTTOM_LEFT
    if (nearR && nearB) return RectGrab.BOTTOM_RIGHT
    // 内部留出足够移动区域（小框也能整体拖动）
    val innerInset = minOf(h, abs(rr - l) * 0.25f, abs(b - t) * 0.25f)
    if (p.x > l + innerInset &&
                    p.x < rr - innerInset &&
                    p.y > t + innerInset &&
                    p.y < b - innerInset
    ) {
        return RectGrab.MOVE
    }
    if (nearL && p.y in t..b) return RectGrab.LEFT
    if (nearR && p.y in t..b) return RectGrab.RIGHT
    if (nearT && p.x in l..rr) return RectGrab.TOP
    if (nearB && p.x in l..rr) return RectGrab.BOTTOM
    return RectGrab.NONE
}

/** 按抓手把矩形改到位（minSize / 屏幕边界都夹住） */
private fun applyGrab(
        before: Rect,
        grab: RectGrab,
        dx: Float,
        dy: Float,
        minSize: Float,
        maxW: Float,
        maxH: Float
): Rect {
    var l = before.left.toFloat()
    var t = before.top.toFloat()
    var r = before.right.toFloat()
    var b = before.bottom.toFloat()
    when (grab) {
        RectGrab.MOVE -> {
            val w = r - l
            val h = b - t
            l = (l + dx).coerceIn(0f, (maxW - w).coerceAtLeast(0f))
            t = (t + dy).coerceIn(0f, (maxH - h).coerceAtLeast(0f))
            r = l + w
            b = t + h
        }
        RectGrab.LEFT -> l = (l + dx).coerceIn(0f, r - minSize)
        RectGrab.RIGHT -> r = (r + dx).coerceIn(l + minSize, maxW)
        RectGrab.TOP -> t = (t + dy).coerceIn(0f, b - minSize)
        RectGrab.BOTTOM -> b = (b + dy).coerceIn(t + minSize, maxH)
        RectGrab.TOP_LEFT -> {
            l = (l + dx).coerceIn(0f, r - minSize)
            t = (t + dy).coerceIn(0f, b - minSize)
        }
        RectGrab.TOP_RIGHT -> {
            r = (r + dx).coerceIn(l + minSize, maxW)
            t = (t + dy).coerceIn(0f, b - minSize)
        }
        RectGrab.BOTTOM_LEFT -> {
            l = (l + dx).coerceIn(0f, r - minSize)
            b = (b + dy).coerceIn(t + minSize, maxH)
        }
        RectGrab.BOTTOM_RIGHT -> {
            r = (r + dx).coerceIn(l + minSize, maxW)
            b = (b + dy).coerceIn(t + minSize, maxH)
        }
        RectGrab.NONE -> Unit
    }
    val mx = maxW.toInt().coerceAtLeast(1)
    val my = maxH.toInt().coerceAtLeast(1)
    return Rect(
            Math.round(l).coerceIn(0, mx),
            Math.round(t).coerceIn(0, my),
            Math.round(r).coerceIn(0, mx),
            Math.round(b).coerceIn(0, my)
    )
}

// ===================== 提问步（附件条 + 输入框 + 麦克风 + 理解） =====================

@Composable
private fun AskPanel(
        state: ScreenLensState,
        submitLabel: String,
        placeholder: String,
        onReselect: () -> Unit,
        onSubmit: () -> Unit,
        onMicTap: () -> Unit,
        onRemoveAttachImage: () -> Unit,
        onRemoveAttachText: () -> Unit,
        modifier: Modifier = Modifier
) {
    Column(
            modifier =
                    modifier.background(
                                    LensPanelBg,
                                    RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
                            )
                            .border(
                                    1.dp,
                                    LensGlass,
                                    RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
                            )
                            .navigationBarsPadding()
                            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                    text = stringResource(R.string.lens_ask_hint_rect),
                    color = LensTextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
            )
            Text(
                    text = stringResource(R.string.lens_action_reselect),
                    color = LensAccentLight,
                    fontSize = 11.sp,
                    modifier =
                            Modifier.clickable { onReselect() }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        AttachmentBar(
                state = state,
                onRemoveImage = onRemoveAttachImage,
                onRemoveText = onRemoveAttachText
        )
        Spacer(Modifier.height(8.dp))
        AskComposer(
                state = state,
                placeholder = placeholder,
                submitLabel = submitLabel,
                onSubmit = onSubmit,
                onMicTap = onMicTap
        )
        AsrStatus(state)
    }
}

/** 附件条：缩略图 chip + 文字摘要 chip，都可点 × 删除（删除即不再随本次识别上传） */
@Composable
private fun AttachmentBar(
        state: ScreenLensState,
        onRemoveImage: () -> Unit,
        onRemoveText: () -> Unit
) {
    val summary = state.selectionText.replace('\n', ' ').replace(Regex(" {2,}"), " ").trim()
    val summaryText = if (summary.length > 16) summary.take(16) + "…" else summary
    Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
    ) {
        if (state.attachImage) {
            Row(
                    modifier =
                            Modifier.background(LensGlass, RoundedCornerShape(10.dp))
                                    .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(10.dp))
                                    .padding(start = 4.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
            ) {
                val thumb = state.thumb
                if (thumb != null) {
                    Image(
                            bitmap = thumb.asImageBitmap(),
                            contentDescription = stringResource(R.string.lens_attach_image),
                            contentScale = ContentScale.Crop,
                            modifier =
                                    Modifier.size(28.dp)
                                            .clip(RoundedCornerShape(7.dp))
                                            .border(
                                                    1.dp,
                                                    Color(0x33FFFFFF),
                                                    RoundedCornerShape(7.dp)
                                            )
                    )
                } else {
                    Box(
                            modifier =
                                    Modifier.size(28.dp)
                                            .background(
                                                    LensAccent.copy(alpha = 0.25f),
                                                    RoundedCornerShape(7.dp)
                                            ),
                            contentAlignment = Alignment.Center
                    ) {
                        Icon(
                                Icons.Filled.Image,
                                contentDescription = null,
                                tint = LensAccentLight,
                                modifier = Modifier.size(15.dp)
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                Text(
                        text = stringResource(R.string.lens_attach_image),
                        color = LensTextPrimary,
                        fontSize = 11.sp,
                        maxLines = 1
                )
                Box(
                        modifier = Modifier.size(30.dp).clickable { onRemoveImage() },
                        contentAlignment = Alignment.Center
                ) {
                    Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.lens_attach_remove),
                            tint = LensTextSecondary,
                            modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
        if (state.attachText) {
            Row(
                    modifier =
                            Modifier.background(LensGlass, RoundedCornerShape(10.dp))
                                    .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(10.dp))
                                    .padding(start = 8.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                        Icons.Filled.Subject,
                        contentDescription = null,
                        tint = LensAccentLight,
                        modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                        text = summaryText.ifBlank { stringResource(R.string.lens_attach_text) },
                        color = LensTextPrimary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 150.dp)
                )
                Box(
                        modifier = Modifier.size(30.dp).clickable { onRemoveText() },
                        contentAlignment = Alignment.Center
                ) {
                    Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.lens_attach_remove),
                            tint = LensTextSecondary,
                            modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
        if (!state.attachImage && !state.attachText) {
            Text(
                    text = stringResource(R.string.lens_attach_none),
                    color = LensTextSecondary,
                    fontSize = 11.sp
            )
        }
    }
}

/** 输入框 + 麦克风 + 提交（输入框永远只放用户自己的话） */
@Composable
private fun AskComposer(
        state: ScreenLensState,
        placeholder: String,
        submitLabel: String,
        onSubmit: () -> Unit,
        onMicTap: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                    value = state.askField,
                    onValueChange = { state.askField = it },
                    textStyle = TextStyle(color = LensTextPrimary, fontSize = 14.sp),
                    cursorBrush = SolidColor(LensAccentLight),
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                    modifier =
                            Modifier.fillMaxWidth()
                                    .heightIn(min = 46.dp, max = 108.dp)
                                    .background(Color(0x14FFFFFF), RoundedCornerShape(12.dp))
                                    .border(1.dp, LensGlass, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 13.dp),
                    decorationBox = { inner ->
                        Box(modifier = Modifier.fillMaxWidth()) {
                            if (state.askField.text.isEmpty()) {
                                Text(
                                        text = placeholder,
                                        color = LensTextSecondary,
                                        fontSize = 13.sp
                                )
                            }
                            inner()
                        }
                    }
            )
        }
        Spacer(Modifier.width(8.dp))
        MicButton(state = state, onMicTap = onMicTap)
        Spacer(Modifier.width(8.dp))
        LensPill(label = submitLabel, primary = true, onClick = onSubmit)
    }
}

@Composable
private fun MicButton(state: ScreenLensState, onMicTap: () -> Unit) {
    val listening = state.asrState == LensAsrState.LISTENING
    val recognizing = state.asrState == LensAsrState.RECOGNIZING
    Box(
            modifier =
                    Modifier.size(48.dp)
                            .background(
                                    when {
                                        listening -> LensAccent
                                        recognizing -> LensAccent.copy(alpha = 0.35f)
                                        else -> Color(0x1FFFFFFF)
                                    },
                                    CircleShape
                            )
                            .border(
                                    1.dp,
                                    if (listening) LensAccentLight else LensGlass,
                                    CircleShape
                            )
                            .clickable(enabled = !recognizing) { onMicTap() },
            contentAlignment = Alignment.Center
    ) {
        if (recognizing) {
            CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
            )
        } else {
            val iconSize = (20f + (if (listening) state.volume * 6f else 0f)).dp
            Icon(
                    Icons.Filled.Mic,
                    contentDescription = stringResource(R.string.lens_ask_voice),
                    tint = Color.White,
                    modifier = Modifier.size(iconSize)
            )
        }
    }
}

@Composable
private fun AsrStatus(state: ScreenLensState) {
    val detail = state.asrDetail
    if (detail.isNullOrBlank()) return
    Spacer(Modifier.height(6.dp))
    Text(
            text = detail,
            color = if (state.asrState == LensAsrState.IDLE) Color(0xFFFCA5A5) else LensAccentLight,
            fontSize = 11.sp,
            lineHeight = 15.sp
    )
}

// ===================== 结果面板（含继续问输入框） =====================

@Composable
private fun ResultSheet(
        state: ScreenLensState,
        onCopy: () -> Unit,
        onRemember: () -> Unit,
        onTranslate: () -> Unit,
        onSearch: () -> Unit,
        onDismiss: () -> Unit,
        onSubmitQuestion: () -> Unit,
        onMicTap: () -> Unit,
        onRemoveAttachImage: () -> Unit,
        onRemoveAttachText: () -> Unit,
        modifier: Modifier = Modifier
) {
    val result = state.result
    val text = state.localText.ifBlank { result?.selectedText.orEmpty() }
    Box(
            modifier =
                    modifier.heightIn(max = 520.dp)
                            .background(
                                    LensPanelBg,
                                    RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
                            )
                            .border(
                                    1.dp,
                                    LensGlass,
                                    RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
                            )
    ) {
        Column(
                modifier =
                        Modifier.fillMaxWidth()
                                .navigationBarsPadding()
                                .verticalScroll(rememberScrollState())
                                .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                        modifier =
                                Modifier.width(38.dp)
                                        .height(4.dp)
                                        .background(Color(0x33FFFFFF), RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(10.dp))
                Text(
                        text =
                                result?.scene?.takeIf { it.isNotBlank() }
                                        ?: stringResource(R.string.lens_result_title),
                        color = LensTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.lens_close),
                            tint = LensTextSecondary,
                            modifier = Modifier.size(16.dp)
                    )
                }
            }

            val asked = state.lastQuestion
            if (!asked.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                        text = stringResource(R.string.lens_asked_prefix) + asked,
                        color = LensAccentLight,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                )
            }

            if (text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                            text = stringResource(R.string.lens_extracted_label),
                            color = LensTextSecondary,
                            fontSize = 11.sp
                    )
                    Spacer(Modifier.weight(1f))
                    Row(
                            modifier = Modifier.clickable { onCopy() }.padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = stringResource(R.string.lens_copy),
                                tint = LensAccentLight,
                                modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                                text = stringResource(R.string.lens_copy),
                                color = LensAccentLight,
                                fontSize = 11.sp
                        )
                    }
                }
                Text(
                        text = text,
                        color = LensTextPrimary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(10.dp))
            if (state.analyzing) {
                Text(
                        text = stringResource(R.string.lens_analyzing),
                        color = LensAccentLight,
                        fontSize = 13.sp
                )
                if (!state.visionUsed) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                            text = stringResource(R.string.lens_vision_off),
                            color = LensTextSecondary,
                            fontSize = 11.sp
                    )
                }
            } else {
                val answer = result?.answer.orEmpty()
                Text(
                        text = answer.ifBlank { stringResource(R.string.lens_model_failed) },
                        color = LensTextPrimary,
                        fontSize = 15.sp,
                        lineHeight = 23.sp
                )
                if (state.analyzeError != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                            text = state.analyzeError.orEmpty(),
                            color = Color(0xFFF87171),
                            fontSize = 11.sp
                    )
                }
                val entities = result?.entities.orEmpty()
                if (entities.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        entities.forEach { e ->
                            Text(
                                    text = e.value,
                                    color = LensTextPrimary,
                                    fontSize = 12.sp,
                                    modifier =
                                            Modifier.background(
                                                            LensAccent.copy(alpha = 0.18f),
                                                            RoundedCornerShape(10.dp)
                                                    )
                                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LensChip(stringResource(R.string.lens_extract)) { onCopy() }
                    LensChip(stringResource(R.string.lens_translate)) { onTranslate() }
                    LensChip(stringResource(R.string.lens_remember)) { onRemember() }
                    LensChip(stringResource(R.string.lens_search)) { onSearch() }
                }
                if (state.modelName.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                            text = state.modelName + " · " + state.elapsedMs + "ms",
                            color = Color(0x66FFFFFF),
                            fontSize = 10.sp
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0x1AFFFFFF)))
            Spacer(Modifier.height(10.dp))
            // 继续问：选中内容以附件形态贴在输入框上方，输入框只放用户自己的话
            AttachmentBar(
                    state = state,
                    onRemoveImage = onRemoveAttachImage,
                    onRemoveText = onRemoveAttachText
            )
            Spacer(Modifier.height(8.dp))
            AskComposer(
                    state = state,
                    placeholder = stringResource(R.string.lens_followup_placeholder),
                    submitLabel = stringResource(R.string.lens_continue_ask),
                    onSubmit = onSubmitQuestion,
                    onMicTap = onMicTap
            )
            AsrStatus(state)
        }
    }
}

// ===================== 通用小件 =====================

@Composable
private fun LensPill(
        label: String,
        modifier: Modifier = Modifier,
        icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
        primary: Boolean = false,
        onClick: () -> Unit
) {
    Row(
            modifier =
                    modifier.defaultMinSize(minHeight = 44.dp)
                            .background(
                                    if (primary) LensAccent else Color(0x99000000),
                                    RoundedCornerShape(22.dp)
                            )
                            .border(
                                    1.dp,
                                    if (primary) LensAccentLight else LensGlass,
                                    RoundedCornerShape(22.dp)
                            )
                            .clickable { onClick() }
                            .padding(vertical = 11.dp, horizontal = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
                text = label,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
        )
    }
}

@Composable
private fun LensChip(label: String, onClick: () -> Unit) {
    Text(
            text = label,
            color = LensTextPrimary,
            fontSize = 12.sp,
            modifier =
                    Modifier.background(LensGlass, RoundedCornerShape(14.dp))
                            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(14.dp))
                            .clickable { onClick() }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

/** 选区外接矩形（显示坐标 → 显示像素），并保证最小尺寸，避免误触出零面积选区 */
fun normalizeSelectionRect(points: List<Offset>, scaleX: Float, scaleY: Float): Rect {
    if (points.isEmpty()) return Rect()
    var l = Float.MAX_VALUE
    var t = Float.MAX_VALUE
    var r = -Float.MAX_VALUE
    var b = -Float.MAX_VALUE
    points.forEach { p ->
        l = minOf(l, p.x)
        t = minOf(t, p.y)
        r = maxOf(r, p.x)
        b = maxOf(b, p.y)
    }
    val rect =
            Rect(
                    Math.round(l * scaleX),
                    Math.round(t * scaleY),
                    Math.round(r * scaleX),
                    Math.round(b * scaleY)
            )
    if (rect.width() < 24) rect.right = rect.left + 24
    if (rect.height() < 24) rect.bottom = rect.top + 24
    return rect
}
