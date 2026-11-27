package com.ai.assistance.operit.voice

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.delay

// ===== 设计令牌（参考 Gemini / 超级小爱 风格规范） =====
private val VoiceBg = Color(0xFF0F0E13)
private val VoiceGlass = Color(0x0FFFFFFF) // rgba(255,255,255,0.06)
private val VoiceAccent = Color(0xFF8B5CF6)
private val VoiceAccentLight = Color(0xFFA78BFA)
private val VoiceTextPrimary = Color(0xFFF4F1FA)
private val VoiceTextSecondary = Color(0xB3FFFFFF)
private val WaveColors = listOf(
    Color(0xFF4F8EF7), // 蓝
    Color(0xFF8B5CF6), // 紫
    Color(0xFFEC4899), // 粉
    Color(0xFFF59E0B)  // 橙
)

@Composable
fun VoiceAssistantScreen(
    controller: VoiceAssistantController,
    onClose: () -> Unit,
    onScreenLens: () -> Unit = {}
) {
    val phase by controller.phase.collectAsState()
    val bubbles by controller.bubbles.collectAsState()
    val streamingText by controller.streamingText.collectAsState()
    val volume by controller.volume.collectAsState()
    val statusDetail by controller.statusDetail.collectAsState()
    val inputMode by controller.inputMode.collectAsState()

    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    val prefill by controller.pendingPrefill.collectAsState()
    LaunchedEffect(prefill) {
        prefill?.let {
            inputText = it
            controller.consumePrefill()
        }
    }

    // 入场动画：底部弹层升起（Gemini 式）
    val enterProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enterProgress.animateTo(1f, tween(420)) }

    val listState = rememberLazyListState()
    val lastBubbleText = bubbles.lastOrNull()?.text
    LaunchedEffect(bubbles.size, lastBubbleText, streamingText) {
        if (bubbles.isNotEmpty() || streamingText.isNotEmpty()) {
            val last = if (streamingText.isNotEmpty()) bubbles.size else bubbles.size - 1
            if (last >= 0) {
                delay(60)
                listState.animateScrollToItem(last)
                // 图片是异步加载的：气泡挂上图片后还会再变高一次，补一次滚动保证整条气泡可见
                delay(700)
                listState.animateScrollToItem(last)
            }
        }
    }

    val statusText = statusDetail ?: when (phase) {
        VoicePhase.AWAKE -> stringResource(R.string.voice_status_awake)
        VoicePhase.LISTENING -> stringResource(R.string.voice_status_listening)
        VoicePhase.RECOGNIZING -> stringResource(R.string.voice_status_recognizing)
        VoicePhase.TRANSCRIBING -> stringResource(R.string.voice_status_transcribing)
        VoicePhase.THINKING -> stringResource(R.string.voice_status_thinking)
        VoicePhase.ANSWERING -> stringResource(R.string.voice_status_answering)
        VoicePhase.HANDED_OFF -> stringResource(R.string.voice_status_handed_off)
        VoicePhase.FOLLOWUP -> stringResource(R.string.voice_status_followup)
        VoicePhase.ERROR -> stringResource(R.string.voice_status_error)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VoiceBg)
            .graphicsLayer {
                alpha = enterProgress.value
                translationY = (1f - enterProgress.value) * 160f
            }
    ) {
        // 顶部紫色光晕
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x558B5CF6), Color(0x000F0E13)),
                    center = Offset(size.width / 2f, size.height * 0.18f),
                    radius = size.width * 0.9f
                ),
                center = Offset(size.width / 2f, size.height * 0.18f),
                radius = size.width * 0.9f
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            // ===== 顶栏：品牌图 + 标题 + 关闭 =====
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_girl),
                    contentDescription = stringResource(R.string.voice_title),
                    modifier = Modifier.size(34.dp).border(1.dp, VoiceGlass, CircleShape),
                    tint = Color.Unspecified
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.voice_title),
                    color = VoiceTextSecondary,
                    fontSize = 14.sp,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.voice_close),
                        tint = VoiceTextSecondary
                    )
                }
            }

            // ===== 状态区：大字状态 + 发光波形 =====
            Spacer(Modifier.height(18.dp))
            Text(
                text = statusText,
                color = VoiceTextPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = when (phase) {
                    VoicePhase.LISTENING -> stringResource(R.string.voice_hint_listening)
                    VoicePhase.RECOGNIZING ->
                        stringResource(R.string.voice_hint_recognizing)
                    VoicePhase.THINKING, VoicePhase.ANSWERING ->
                        stringResource(R.string.voice_hint_thinking)
                    VoicePhase.HANDED_OFF -> stringResource(R.string.voice_hint_handed_off)
                    else -> stringResource(R.string.voice_hint_idle)
                },
                color = VoiceTextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(20.dp))
            VoiceWaveform(
                modifier = Modifier.fillMaxWidth().height(72.dp),
                active = phase == VoicePhase.LISTENING,
                volume = volume
            )
            Spacer(Modifier.height(12.dp))

            // ===== 对话区 =====
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(bubbles, key = { it.key }) { b ->
                    VoiceBubbleRow(b)
                }
                if (streamingText.isNotEmpty()) {
                    item(key = "streaming") {
                        VoiceBubbleRow(VoiceBubble(-1L, "ai", streamingText))
                    }
                }
            }

            // ===== 结果态操作：单发模式下回答完成后给「再说一次 / 关闭」（缺陷1） =====
            if (phase == VoicePhase.FOLLOWUP && (bubbles.isNotEmpty() || streamingText.isNotEmpty())) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ResultActionButton(
                        label = stringResource(R.string.voice_action_speak_again),
                        primary = true,
                        modifier = Modifier.weight(1f)
                    ) { controller.speakAgain() }
                    ResultActionButton(
                        label = stringResource(R.string.voice_action_close),
                        primary = false,
                        modifier = Modifier.weight(1f)
                    ) { onClose() }
                }
            }

            // ===== 建议胶囊 =====
            if (bubbles.isEmpty() && streamingText.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                val sug1 = stringResource(R.string.voice_suggestion_1)
                val sug2 = stringResource(R.string.voice_suggestion_2)
                val sug3 = stringResource(R.string.voice_suggestion_3)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SuggestionChip(sug1) { controller.submitFromText(sug1) }
                    SuggestionChip(sug2) { controller.submitFromText(sug2) }
                    SuggestionChip(sug3) { controller.submitFromText(sug3) }
                }
            }

            // ===== 底部输入条：麦克风 / 文本兜底 / 发送 =====
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(VoiceGlass, RoundedCornerShape(26.dp))
                    .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(26.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 键盘切换（文本兜底入口）
                IconButton(
                    onClick = {
                        controller.setTextMode(inputMode == InputMode.VOICE)
                    }
                ) {
                    Icon(
                        if (inputMode == InputMode.VOICE) Icons.Filled.Keyboard else Icons.Filled.Mic,
                        contentDescription = stringResource(
                            if (inputMode == InputMode.VOICE) R.string.voice_keyboard else R.string.voice_mic
                        ),
                        tint = VoiceAccentLight
                    )
                }

                if (inputMode == InputMode.TEXT) {
                    BasicTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = VoiceTextPrimary,
                            fontSize = 15.sp
                        ),
                        maxLines = 3,
                        decorationBox = { inner ->
                            Box {
                                if (inputText.isEmpty()) {
                                    Text(
                                        stringResource(R.string.voice_input_hint),
                                        color = VoiceTextSecondary,
                                        fontSize = 15.sp
                                    )
                                }
                                inner()
                            }
                        }
                    )
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                controller.submitFromText(inputText)
                                inputText = ""
                            }
                        },
                        enabled = inputText.isNotBlank()
                    ) {
                        Icon(
                            Icons.Filled.Send,
                            contentDescription = stringResource(R.string.voice_send),
                            tint = if (inputText.isNotBlank()) VoiceAccent else VoiceTextSecondary
                        )
                    }
                } else {
                    // 聆听态：中间提示，点按麦克风开始/停止
                    Text(
                        text = stringResource(R.string.voice_tap_to_type),
                        color = VoiceTextSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { controller.setTextMode(true) }
                            .padding(horizontal = 6.dp, vertical = 12.dp)
                    )
                    // 识别屏幕：在麦克风钮旁，按下即进入全屏圈选态（不等任何识别）
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .background(VoiceGlass, CircleShape)
                            .border(1.dp, Color(0x338B5CF6), CircleShape)
                            .clickable { onScreenLens() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.CenterFocusStrong,
                            contentDescription = stringResource(R.string.lens_title),
                            tint = VoiceAccentLight,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .background(
                                if (phase == VoicePhase.LISTENING) VoiceAccent else VoiceGlass,
                                CircleShape
                            )
                            .clickable { controller.toggleListening() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = stringResource(R.string.voice_mic),
                            tint = if (phase == VoicePhase.LISTENING) Color.White else VoiceAccentLight,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun VoiceBubbleRow(bubble: VoiceBubble) {
    val isUser = bubble.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(
                    if (isUser) VoiceGlass else Color(0x148B5CF6),
                    RoundedCornerShape(if (isUser) 18.dp else 18.dp)
                )
                .border(
                    1.dp,
                    if (isUser) Color(0x1FFFFFFF) else Color(0x338B5CF6),
                    RoundedCornerShape(18.dp)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // 富内容气泡：文本照常排版，图片（link 标签 / 本地路径 / markdown）真实渲染
            VoiceMessageContent(
                text = bubble.text,
                textColor = VoiceTextPrimary,
                fontSize = if (isUser) 15.sp else 17.sp,
                lineHeight = if (isUser) 21.sp else 25.sp
            )
        }
    }
}

@Composable
private fun SuggestionChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(VoiceGlass, RoundedCornerShape(16.dp))
            .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text = label, color = VoiceTextSecondary, fontSize = 13.sp, maxLines = 1)
    }
}

/** 结果态操作按钮：「再说一次」（主色）/「关闭」（玻璃） */
@Composable
private fun ResultActionButton(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .background(
                if (primary) VoiceAccent else VoiceGlass,
                RoundedCornerShape(22.dp)
            )
            .border(
                1.dp,
                if (primary) VoiceAccentLight else Color(0x1FFFFFFF),
                RoundedCornerShape(22.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (primary) Color.White else VoiceTextSecondary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

/**
 * 发光波形条（兼取小爱彩条与 Gemini 光晕）：
 * - active：条高随麦克风音量跳动
 * - 静默：呼吸式低幅起伏
 */
@Composable
private fun VoiceWaveform(
    modifier: Modifier = Modifier,
    active: Boolean,
    volume: Float
) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "wavePhase"
    )
    val glow by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "glow"
    )

    Canvas(modifier = modifier) {
        val barCount = 32
        val totalWidth = size.width
        val slot = totalWidth / barCount
        val barWidth = slot * 0.45f
        val centerY = size.height / 2f
        val maxHalf = size.height / 2f - 2f

        for (i in 0 until barCount) {
            val idle = (sin(phase + i * 0.55f) * 0.5f + 0.5f) // 0..1
            val jitter = if (active) {
                (idle * 0.45f + volume * 0.55f).coerceIn(0f, 1f)
            } else {
                idle * 0.22f * glow
            }
            // 中间高两边低的整体包络
            val normalized = abs(i - barCount / 2f) / (barCount / 2f)
            val envelope = (1f - normalized * 0.75f).coerceIn(0.25f, 1f)
            val half = (jitter * envelope * maxHalf).coerceAtLeast(3f)
            val x = i * slot + slot / 2f
            drawLine(
                color = WaveColors[i % WaveColors.size].copy(alpha = if (active) 0.95f else 0.6f),
                start = Offset(x, centerY - half),
                end = Offset(x, centerY + half),
                strokeWidth = barWidth,
                cap = StrokeCap.Round
            )
        }
    }
}
