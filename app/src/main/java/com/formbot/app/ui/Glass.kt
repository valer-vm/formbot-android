package com.formbot.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/** Слой, который «просвечивает» сквозь стекло. Задаётся один раз на экран в [GlassBackground]. */
val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

/**
 * Фон в стиле liquid glass: фиолетовый градиент и медленно плывущие размытые пятна.
 * Всё, что рисуется поверх с [glass], размывает именно этот слой.
 */
@Composable
fun GlassBackground(state: HazeState, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val dark = LocalDarkTheme.current
    val base = if (dark) listOf(Color(0xFF0E0A18), Color(0xFF1A1030), Color(0xFF0B0814))
    else listOf(Color(0xFFF3ECFF), Color(0xFFE6DBFF), Color(0xFFF8F4FF))
    val blobs = if (dark) listOf(Color(0xFF7C4DFF), Color(0xFFC2185B), Color(0xFF3D5AFE))
    else listOf(Color(0xFF9E7BFF), Color(0xFFFF8AD8), Color(0xFF82B1FF))
    val t = rememberInfiniteTransition(label = "blobs")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Reverse), label = "phase")

    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().hazeSource(state).background(Brush.verticalGradient(base))) {
            Canvas(Modifier.fillMaxSize().blur(90.dp)) {
                val w = size.width
                val h = size.height
                val a = if (dark) 0.55f else 0.6f
                drawCircle(blobs[0].copy(alpha = a), w * 0.55f, Offset(w * (0.15f + 0.2f * phase), h * 0.18f))
                drawCircle(blobs[1].copy(alpha = a * 0.8f), w * 0.45f, Offset(w * (0.95f - 0.25f * phase), h * (0.45f + 0.1f * phase)))
                drawCircle(blobs[2].copy(alpha = a * 0.7f), w * 0.5f, Offset(w * (0.3f + 0.15f * phase), h * (0.85f - 0.08f * phase)))
            }
        }
        content()
    }
}

/** Стекло: размытие фона, лёгкий молочный/дымчатый оттенок, тонкая светлая обводка-блик. */
@Composable
fun Modifier.glass(shape: Shape = RoundedCornerShape(24.dp), blur: Dp = 28.dp, strong: Boolean = false): Modifier {
    val dark = LocalDarkTheme.current
    val tint = when {
        dark && strong -> Color(0xFF1B1430).copy(alpha = 0.62f)
        dark -> Color(0xFF241A3D).copy(alpha = 0.42f)
        strong -> Color.White.copy(alpha = 0.66f)
        else -> Color.White.copy(alpha = 0.45f)
    }
    val edge = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = if (dark) 0.28f else 0.9f),
            Color.White.copy(alpha = if (dark) 0.04f else 0.25f),
            MaterialTheme.colorScheme.primary.copy(alpha = if (dark) 0.25f else 0.18f),
        )
    )
    return this
        .clip(shape)
        .hazeEffect(LocalHaze.current, HazeStyle(backgroundColor = MaterialTheme.colorScheme.background, tint = HazeTint(tint), blurRadius = blur, noiseFactor = 0.04f, fallbackTint = HazeTint(tint.copy(alpha = 0.85f))))
        // Блик сверху — как у толстого стекла.
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.07f else 0.22f), Color.Transparent)))
        .border(1.dp, edge, shape)
}

/** Карточка-стекло вместо Material Card. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.glass(shape).padding(padding), content = content)
}
