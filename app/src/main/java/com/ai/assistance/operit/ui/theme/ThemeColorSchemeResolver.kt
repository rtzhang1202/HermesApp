package com.ai.assistance.operit.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.ai.assistance.operit.data.preferences.ThemePreferenceSnapshot
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.data.preferences.UserPreferencesManager.Companion.ON_COLOR_MODE_AUTO
import com.ai.assistance.operit.data.preferences.UserPreferencesManager.Companion.ON_COLOR_MODE_DARK
import com.ai.assistance.operit.data.preferences.UserPreferencesManager.Companion.ON_COLOR_MODE_LIGHT

private val ResolvedDarkColorScheme =
    darkColorScheme(
        primary = Purple80,
        onPrimary = Color.White,
        secondary = PurpleGrey80,
        onSecondary = Color(0xFF0F0E13),
        tertiary = Pink80,
        onTertiary = Color(0xFF0F0E13),
        background = Color(0xFF0F0E13),
        onBackground = Color(0xFFF4F2F8),
        surface = Color(0xFF0F0E13),
        onSurface = Color(0xFFF4F2F8),
        surfaceVariant = Color(alpha = 0.06f, red = 1f, green = 1f, blue = 1f),
        onSurfaceVariant = Color(0xFF9CA3AF),
        surfaceContainerLowest = Color(0xFF141318),
        surfaceContainerLow = Color(0xFF19181C),
        surfaceContainer = Color(0xFF1D1C21),
        surfaceContainerHigh = Color(0xFF222126),
        surfaceContainerHighest = Color(0xFF27262B),
    )

private val ResolvedLightColorScheme =
    lightColorScheme(
        primary = Purple40,
        onPrimary = Color.White,
        secondary = PurpleGrey40,
        onSecondary = Color(0xFF0F0E13),
        tertiary = Pink40,
        onTertiary = Color.White,
        background = Color(0xFFF4F2F8),
        onBackground = Color(0xFF0F0E13),
        surface = Color(0xFFF4F2F8),
        onSurface = Color(0xFF0F0E13),
    )

fun resolveThemeColorScheme(
    context: Context,
    snapshot: ThemePreferenceSnapshot
): ColorScheme {
    val systemDarkTheme =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    val darkTheme =
        if (snapshot.useSystemTheme) {
            systemDarkTheme
        } else {
            snapshot.themeMode == UserPreferencesManager.THEME_MODE_DARK
        }

    // Hermes 品牌色固定：禁用壁纸动态取色，确保官方设计令牌生效
    var colorScheme =
        if (darkTheme) {
            ResolvedDarkColorScheme
        } else {
            ResolvedLightColorScheme
        }

    if (snapshot.useCustomColors) {
        snapshot.customPrimaryColor?.let { primaryArgb ->
            val primary = Color(primaryArgb)
            val secondary = snapshot.customSecondaryColor?.let(::Color) ?: colorScheme.secondary
            colorScheme =
                if (darkTheme) {
                    generateResolvedDarkColorScheme(primary, secondary, snapshot.onColorMode)
                } else {
                    generateResolvedLightColorScheme(primary, secondary, snapshot.onColorMode)
                }
        }
    }

    return colorScheme
}

private fun generateResolvedLightColorScheme(
    primaryColor: Color,
    secondaryColor: Color,
    onColorMode: String
): ColorScheme {
    val onPrimary =
        when (onColorMode) {
            ON_COLOR_MODE_LIGHT -> Color.White
            ON_COLOR_MODE_DARK -> Color.Black
            else -> getResolvedContrastingTextColor(primaryColor)
        }
    val onSecondary =
        when (onColorMode) {
            ON_COLOR_MODE_LIGHT -> Color.White
            ON_COLOR_MODE_DARK -> Color.Black
            else -> getResolvedContrastingTextColor(secondaryColor)
        }

    val primaryContainer = lightenResolvedColor(primaryColor, 0.7f)
    val onPrimaryContainer = getResolvedContrastingTextColor(primaryContainer)
    val secondaryContainer = lightenResolvedColor(secondaryColor, 0.7f)
    val onSecondaryContainer = getResolvedContrastingTextColor(secondaryContainer)

    return ResolvedLightColorScheme.copy(
        primary = primaryColor,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondaryColor,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        onSurface = Color.Black,
        onSurfaceVariant = Color.Black.copy(alpha = 0.7f),
        onBackground = Color.Black
    )
}

private fun generateResolvedDarkColorScheme(
    primaryColor: Color,
    secondaryColor: Color,
    onColorMode: String
): ColorScheme {
    val adjustedPrimaryColor = lightenResolvedColor(primaryColor, 0.2f)
    val adjustedSecondaryColor = lightenResolvedColor(secondaryColor, 0.2f)

    val onPrimary =
        when (onColorMode) {
            ON_COLOR_MODE_LIGHT -> Color.White
            ON_COLOR_MODE_DARK -> Color.Black
            else -> getResolvedContrastingTextColor(adjustedPrimaryColor)
        }
    val onSecondary =
        when (onColorMode) {
            ON_COLOR_MODE_LIGHT -> Color.White
            ON_COLOR_MODE_DARK -> Color.Black
            else -> getResolvedContrastingTextColor(adjustedSecondaryColor)
        }

    val primaryContainer = darkenResolvedColor(primaryColor, 0.3f)
    val onPrimaryContainer = getResolvedContrastingTextColor(primaryContainer, forceLight = true)
    val secondaryContainer = darkenResolvedColor(secondaryColor, 0.3f)
    val onSecondaryContainer =
        getResolvedContrastingTextColor(secondaryContainer, forceLight = true)

    return ResolvedDarkColorScheme.copy(
        primary = adjustedPrimaryColor,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = adjustedSecondaryColor,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        onSurface = Color.White,
        onSurfaceVariant = Color.White.copy(alpha = 0.7f),
        onBackground = Color.White
    )
}

private fun getResolvedContrastingTextColor(
    backgroundColor: Color,
    forceDark: Boolean = false,
    forceLight: Boolean = false
): Color {
    if (forceDark) return Color.Black
    if (forceLight) return Color.White

    val luminance =
        0.299 * backgroundColor.red +
            0.587 * backgroundColor.green +
            0.114 * backgroundColor.blue

    return if (luminance > 0.5) Color.Black else Color.White
}

private fun lightenResolvedColor(color: Color, factor: Float): Color {
    val r = color.red + (1f - color.red) * factor
    val g = color.green + (1f - color.green) * factor
    val b = color.blue + (1f - color.blue) * factor
    return Color(r, g, b, color.alpha)
}

private fun darkenResolvedColor(color: Color, factor: Float): Color {
    val r = color.red * (1f - factor)
    val g = color.green * (1f - factor)
    val b = color.blue * (1f - factor)
    return Color(r, g, b, color.alpha)
}
