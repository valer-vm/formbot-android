package com.formbot.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Тема: как в системе, всегда светлая или всегда тёмная. */
enum class ThemeMode(val id: String, val title: String) {
    SYSTEM("system", "Системная"), LIGHT("light", "Светлая"), DARK("dark", "Тёмная");

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/** Основной цвет — электрик-фиолетовый #7C4DFF; системные цвета (dynamic color) не используются. */
val Violet = Color(0xFF7C4DFF)

private val Light = lightColorScheme(
    primary = Violet, onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DDFF), onPrimaryContainer = Color(0xFF23005C),
    secondary = Color(0xFF645A7A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEADEFF), onSecondaryContainer = Color(0xFF1F1733),
    tertiary = Color(0xFFB03C8C), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD8EE), onTertiaryContainer = Color(0xFF3B0030),
    background = Color(0xFFF6F1FF), onBackground = Color(0xFF1C1726),
    surface = Color(0xFFFBF8FF), onSurface = Color(0xFF1C1726),
    surfaceVariant = Color(0xFFE7DEF5), onSurfaceVariant = Color(0xFF4A4458),
    surfaceContainer = Color(0xFFF0EAFB), surfaceContainerHigh = Color(0xFFEAE3F6),
    outline = Color(0xFF7B7389), outlineVariant = Color(0xFFCBC3D9),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB9A0FF), onPrimary = Color(0xFF2A0076),
    primaryContainer = Color(0xFF5B2EE0), onPrimaryContainer = Color(0xFFEDE3FF),
    secondary = Color(0xFFCEC1E8), onSecondary = Color(0xFF342B4A),
    secondaryContainer = Color(0xFF4B4162), onSecondaryContainer = Color(0xFFEADEFF),
    tertiary = Color(0xFFFFADE0), onTertiary = Color(0xFF5E0B4C),
    tertiaryContainer = Color(0xFF8A2470), onTertiaryContainer = Color(0xFFFFD8EE),
    background = Color(0xFF0E0A18), onBackground = Color(0xFFE8E0F3),
    surface = Color(0xFF130E1F), onSurface = Color(0xFFE8E0F3),
    surfaceVariant = Color(0xFF4A4458), onSurfaceVariant = Color(0xFFCBC3D9),
    surfaceContainer = Color(0xFF1D1729), surfaceContainerHigh = Color(0xFF282133),
    outline = Color(0xFF958DA3), outlineVariant = Color(0xFF4A4458),
)

/** Тёмная ли сейчас тема — для стекла, которое в тёмной теме дымчатое, а в светлой молочное. */
val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun FormBotTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Typography(), content = content)
    }
}
