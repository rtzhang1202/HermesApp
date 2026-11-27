package com.ai.assistance.operit.voice.lens

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import com.ai.assistance.operit.R

/** 识屏阶段：采集中 → 可选区 → 识别中/结果（结果面板覆盖）→ 拦截/错误 */
enum class LensPhase { CAPTURING, SELECT, ANALYZING, RESULT, BLOCKED, FATAL }

/** 识屏界面的可观察状态（Activity 持有，Compose 读取） */
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
        onContinueAsk: () -> Unit,
        onDismissResult: () -> Unit,
        onOpenAccessibilitySettings: () -> Unit
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

        // 2) 选择层（画轨迹 + 选区框 + 节点框）：采集期即可画，不等截图/识别
        if (phase == LensPhase.CAPTURING ||
                        phase == LensPhase.SELECT ||
                        phase == LensPhase.ANALYZING ||
                        phase == LensPhase.RESULT) {
            SelectionCanvas(
                    state = state,
                    onTap = onTap,
                    onDragEnd = onDragEnd,
                    onTrailUpdate = onTrailUpdate
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
                            Modifier.size(34.dp)
                                    .background(Color(0x66000000), CircleShape)
                                    .border(1.dp, LensGlass, CircleShape)
                                    .clickable { onClose() },
                    contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.lens_close), tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                    text =
                            when (phase) {
                                LensPhase.CAPTURING -> stringResource(R.string.lens_hint_reading)
                                LensPhase.SELECT -> stringResource(R.string.lens_hint_select)
                                LensPhase.ANALYZING -> stringResource(R.string.lens_analyzing)
                                LensPhase.RESULT -> stringResource(R.string.lens_result_title)
                                LensPhase.BLOCKED -> stringResource(R.string.lens_blocked_title)
                                LensPhase.FATAL -> stringResource(R.string.lens_title)
                            },
                    color = Color.White,
                    fontSize = 13.sp,
                    maxLines = 2,
                    modifier =
                            Modifier.background(Color(0x66000000), RoundedCornerShape(14.dp))
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }

        // 4) 底部按钮条（可选区/采集期即出现；结果面板时让位）
        if (phase == LensPhase.SELECT || phase == LensPhase.CAPTURING) {
            Row(
                    modifier =
                            Modifier.align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
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

        // 5) 结果面板（底部 45%，绝不跳 App）
        if (phase == LensPhase.ANALYZING || phase == LensPhase.RESULT) {
            ResultSheet(
                    state = state,
                    onCopy = onCopy,
                    onRemember = onRemember,
                    onTranslate = onTranslate,
                    onSearch = onSearch,
                    onContinueAsk = onContinueAsk,
                    onDismiss = onDismissResult,
                    modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // 6) 拦截 / 致命错误
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
                            text = stringResource(
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
                        if (phase == LensPhase.FATAL &&
                                        !ScreenSnapshotter.isConnected()
                        ) {
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

@Composable
private fun SelectionCanvas(
        state: ScreenLensState,
        onTap: (Float, Float) -> Unit,
        onDragEnd: (List<Offset>) -> Unit,
        onTrailUpdate: (List<Offset>) -> Unit
) {
    val bitmap = state.bitmap
    Canvas(
            modifier =
                    Modifier.fillMaxSize().pointerInput(state.phase, bitmap) {
                        if (state.phase != LensPhase.SELECT &&
                                        state.phase != LensPhase.CAPTURING
                        )
                                return@pointerInput
                        // 画布坐标 → 显示像素（截图坐标系）：节点 bounds / 裁剪都用显示像素
                        val scaleX =
                                if (bitmap != null && bitmap.width > 0)
                                        bitmap.width.toFloat() / size.width.coerceAtLeast(1)
                                else 1f
                        val scaleY =
                                if (bitmap != null && bitmap.height > 0)
                                        bitmap.height.toFloat() / size.height.coerceAtLeast(1)
                                else 1f
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
    ) {
        val sx = if (bitmap != null && bitmap.width > 0) size.width / bitmap.width else 1f
        val sy = if (bitmap != null && bitmap.height > 0) size.height / bitmap.height else 1f

        // 已选中的节点/选区框（全屏选区不铺满紫色蒙层，只留边框）
        state.selection?.let { sel ->
            val r = sel.bounds
            if (sel.kind != LensSelectionKind.FULL) {
                drawRect(
                        color = LensAccent.copy(alpha = 0.22f),
                        topLeft = Offset(r.left * sx, r.top * sy),
                        size = Size(r.width() * sx, r.height() * sy)
                )
            }
            drawRect(
                    color = LensAccentLight,
                    topLeft = Offset(r.left * sx, r.top * sy),
                    size = Size(r.width() * sx, r.height() * sy),
                    style = Stroke(width = 3f)
            )
            val corners =
                    listOf(
                            Offset(r.left * sx, r.top * sy),
                            Offset(r.right * sx, r.top * sy),
                            Offset(r.left * sx, r.bottom * sy),
                            Offset(r.right * sx, r.bottom * sy)
                    )
            corners.forEach { drawCircle(color = Color.White, radius = 7f, center = it) }
        }

        // 手绘轨迹
        val trail = state.trail
        if (trail.size >= 2) {
            val path = Path().apply {
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

@Composable
private fun ResultSheet(
        state: ScreenLensState,
        onCopy: () -> Unit,
        onRemember: () -> Unit,
        onTranslate: () -> Unit,
        onSearch: () -> Unit,
        onContinueAsk: () -> Unit,
        onDismiss: () -> Unit,
        modifier: Modifier = Modifier
) {
    val result = state.result
    val text = state.localText.ifBlank { result?.selectedText.orEmpty() }
    Box(
            modifier =
                    modifier.fillMaxWidth()
                            .fillMaxHeight(0.46f)
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
                        Modifier.fillMaxSize()
                                .navigationBarsPadding()
                                .verticalScroll(rememberScrollState())
                                .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 22.dp)
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
                        text = result?.scene?.takeIf { it.isNotBlank() }
                                        ?: stringResource(R.string.lens_result_title),
                        color = LensTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                    Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.lens_close),
                            tint = LensTextSecondary,
                            modifier = Modifier.size(16.dp)
                    )
                }
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
                            modifier = Modifier.clickable { onCopy() }.padding(4.dp),
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
                        maxLines = 6,
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
                            modifier =
                                    Modifier.fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
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
                                                    .padding(
                                                            horizontal = 10.dp,
                                                            vertical = 5.dp
                                                    )
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LensChip(stringResource(R.string.lens_extract)) { onCopy() }
                    LensChip(stringResource(R.string.lens_translate)) { onTranslate() }
                    LensChip(stringResource(R.string.lens_remember)) { onRemember() }
                    LensChip(stringResource(R.string.lens_search)) { onSearch() }
                }
                Spacer(Modifier.height(14.dp))
                Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LensPill(
                            label = stringResource(R.string.lens_continue_ask),
                            modifier = Modifier.weight(1f),
                            onClick = onContinueAsk
                    )
                    LensPill(
                            label = stringResource(R.string.lens_remember),
                            primary = true,
                            modifier = Modifier.weight(1f),
                            onClick = onRemember
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                        text =
                                if (state.modelName.isNotBlank()) {
                                    state.modelName + " · " + state.elapsedMs + "ms"
                                } else "",
                        color = Color(0x66FFFFFF),
                        fontSize = 10.sp
                )
            }
        }
    }
}

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
                    modifier.background(
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
                            .padding(horizontal = 12.dp, vertical = 7.dp)
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
