package com.ai.assistance.operit.ui.floating

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.ColorScheme

/**
 * 为悬浮窗提供的独立主题
 * 使用静态颜色，避免对Activity上下文的依赖
 */
@Composable
fun FloatingWindowTheme(
    colorScheme: ColorScheme? = null,
    typography: Typography? = null,
    content: @Composable () -> Unit
) {
    // 使用静态颜色，匹配动态主题的默认值
    val finalColorScheme = colorScheme ?: darkColorScheme(
        // Hermes 官方设计令牌
        primary = Color(0xFF8B5CF6),                // 强调紫罗兰
        onPrimary = Color.White,
        primaryContainer = Color(0xFF2E1A5E),       // 深紫容器
        onPrimaryContainer = Color(0xFFEADDFF),

        secondary = Color(0xFFA78BFA),              // 辅助亮紫
        onSecondary = Color(0xFF0F0E13),
        secondaryContainer = Color(0xFF3A2F63),
        onSecondaryContainer = Color(0xFFF4F2F8),

        tertiary = Color(0xFFA78BFA),
        onTertiary = Color(0xFF0F0E13),
        tertiaryContainer = Color(0xFF3A2F63),
        onTertiaryContainer = Color(0xFFF4F2F8),

        error = Color(0xFFB3261E),
        onError = Color.White,
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),

        // 深底 + 玻璃卡片 rgba(255,255,255,0.06)
        background = Color(0xFF0F0E13),
        onBackground = Color(0xFFF4F2F8),
        surface = Color(0xFF0F0E13),
        onSurface = Color(0xFFF4F2F8),
        surfaceVariant = Color(alpha = 0.06f, red = 1f, green = 1f, blue = 1f),
        onSurfaceVariant = Color(0xFF9CA3AF),

        outline = Color(0xFF9CA3AF)
    )
    
    // 创建调整大小后的默认Typography，如果没有传入typography参数则使用此默认值
    val defaultSmallTypography = Typography(
        // 正文大字号
        bodyLarge = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.5.sp
        ),
        // 正文中字号 
        bodyMedium = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.25.sp
        ),
        // 正文小字号 
        bodySmall = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Normal,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            letterSpacing = 0.4.sp
        ),
        // 标签小字号
        labelSmall = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            letterSpacing = 0.5.sp
        ),
        // 标题小字号
        titleSmall = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.5.sp
        ),
        // 按钮文本样式
        labelMedium = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.5.sp
        ),
        // 按钮大文本样式
        labelLarge = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.5.sp
        )
    )

    // 优先使用传入的typography，如果没有则使用默认的小型typography
    val finalTypography = typography ?: defaultSmallTypography
    
    MaterialTheme(
        colorScheme = finalColorScheme,
        typography = finalTypography,
        content = content
    )
} 