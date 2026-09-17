package com.nuomisp.englishbook.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF6957AD), onPrimary = Color.White,
    primaryContainer = Color(0xFFEBE5FF), onPrimaryContainer = Color(0xFF382A66),
    secondary = Color(0xFF526479), secondaryContainer = Color(0xFFE5ECF6),
    tertiary = Color(0xFF46745C), tertiaryContainer = Color(0xFFE1F2E7),
    background = Color(0xFFFAF9FE), surface = Color(0xFFFAF9FE),
    surfaceContainer = Color(0xFFF0EFF6), surfaceContainerLow = Color(0xFFF4F3F9),
    surfaceContainerHigh = Color(0xFFEAE8F2), onSurface = Color(0xFF252532),
    onSurfaceVariant = Color(0xFF686775), outlineVariant = Color(0xFFE0DEE9))
private val Dark = darkColorScheme(
    primary = Color(0xFFC5B5FF), primaryContainer = Color(0xFF40345F),
    onPrimaryContainer = Color(0xFFECE2FF), secondaryContainer = Color(0xFF2F3C50),
    tertiary = Color(0xFF9ED5B4), tertiaryContainer = Color(0xFF284939),
    background = Color(0xFF15151C), surface = Color(0xFF15151C),
    surfaceContainer = Color(0xFF23232E), surfaceContainerLow = Color(0xFF1C1C26),
    surfaceContainerHigh = Color(0xFF2B2B37), onSurface = Color(0xFFE9E6F1),
    onSurfaceVariant = Color(0xFFB5B0C2), outlineVariant = Color(0xFF393541))

@Composable fun EnglishTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 34.sp, lineHeight = 44.sp, fontWeight = FontWeight.Medium),
            headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium),
            titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 27.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 23.sp),
            labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)),
        content = content)
}

/** Original vector companion, drawn by the native Compose canvas. */
@Composable fun CompanionAvatar(size: Dp = 44.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)) {
        Canvas(Modifier.size(size)) {
            val w = this.size.width
            val cat = Path().apply {
                moveTo(w * .25f, w * .43f); lineTo(w * .21f, w * .2f); lineTo(w * .42f, w * .32f)
                quadraticTo(w * .5f, w * .28f, w * .6f, w * .32f)
                lineTo(w * .8f, w * .2f); lineTo(w * .77f, w * .44f)
                cubicTo(w * .91f, w * .81f, w * .62f, w * .85f, w * .5f, w * .86f)
                cubicTo(w * .18f, w * .83f, w * .15f, w * .62f, w * .25f, w * .43f); close()
            }
            drawPath(cat, Color(0xFF7760B4))
            drawLine(Color.White, Offset(w*.34f,w*.52f), Offset(w*.42f,w*.55f), w*.033f, StrokeCap.Round)
            drawLine(Color.White, Offset(w*.6f,w*.55f), Offset(w*.68f,w*.52f), w*.033f, StrokeCap.Round)
            drawArc(Color.White, 20f, 140f, false, Offset(w*.43f,w*.6f), Size(w*.16f,w*.1f), style=Stroke(w*.023f,cap=StrokeCap.Round))
        }
    }
}
