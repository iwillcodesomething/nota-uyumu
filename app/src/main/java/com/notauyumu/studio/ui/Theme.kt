package com.notauyumu.studio.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.notauyumu.core.Grade

object Palette {
    val Ink = Color(0xFF12141A)
    val Surface = Color(0xFF1B1E26)
    val SurfaceHigh = Color(0xFF242833)
    val Line = Color(0xFF323745)
    val Text = Color(0xFFF1EEE7)
    val Muted = Color(0xFF9CA1AE)
    val Amber = Color(0xFFF2B544)
    val Hit = Color(0xFF4FC3A1)
    val Near = Color(0xFFF2B544)
    val Miss = Color(0xFFEF6F6C)
    val Target = Color(0xFF8C93A6)

    fun grade(g: Grade): Color = when (g) {
        Grade.HIT -> Hit
        Grade.NEAR -> Near
        Grade.MISS -> Miss
        Grade.SILENT -> Muted
    }

    /** 0..1 doğruluğu kırmızı → sarı → yeşil renge çevirir. */
    fun accuracy(a: Double): Color = when {
        a >= 0.8 -> Hit
        a >= 0.55 -> Near
        else -> Miss
    }
}

private val scheme = darkColorScheme(
    primary = Palette.Amber,
    onPrimary = Color(0xFF221A06),
    secondary = Palette.Hit,
    background = Palette.Ink,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.Muted,
    outline = Palette.Line,
    error = Palette.Miss,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerHighest = Palette.SurfaceHigh,
)

private val serif = FontFamily.Serif
private val type = Typography(
    displaySmall = TextStyle(fontFamily = serif, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp),
    headlineSmall = TextStyle(fontFamily = serif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp),
)

@Composable
fun NotaUyumuTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
