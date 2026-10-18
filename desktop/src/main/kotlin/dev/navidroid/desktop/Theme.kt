package dev.navidroid.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Gleiche Farben wie die App. */
object Navi {
    val Bg = Color(0xFF0B0C0F)
    val Surface = Color(0xFF16181D)
    val Surface2 = Color(0xFF1F2229)
    val Outline = Color(0xFF2A2E36)
    val Divider = Color(0xFF1A1C22)
    val Text = Color(0xFFF2F3F5)
    val Muted = Color(0xFF9AA0AB)
    val Faint = Color(0xFF8A909B)
    val Accent = Color(0xFF7FD1FF)
    val OnAccent = Color(0xFF06131B)
}

private val Manrope = FontFamily(
    listOf(400, 500, 600, 700, 800).map { w -> Font("font/manrope.ttf", FontWeight(w)) },
)

private val scheme = darkColorScheme(
    primary = Navi.Accent, onPrimary = Navi.OnAccent,
    secondary = Navi.Accent, onSecondary = Navi.OnAccent,
    primaryContainer = Navi.Surface2, onPrimaryContainer = Navi.Text,
    background = Navi.Bg, onBackground = Navi.Text,
    surface = Navi.Bg, onSurface = Navi.Text,
    surfaceVariant = Navi.Surface2, onSurfaceVariant = Navi.Muted,
    surfaceContainer = Navi.Surface, surfaceContainerHigh = Navi.Surface2, surfaceContainerHighest = Navi.Surface2,
    outline = Navi.Outline, outlineVariant = Navi.Divider,
    error = Color(0xFFFF8A80),
)

private fun TextStyle.m(weight: FontWeight, size: Int? = null, tracking: Double? = null) = copy(
    fontFamily = Manrope, fontWeight = weight,
    fontSize = size?.sp ?: fontSize, letterSpacing = tracking?.em ?: letterSpacing,
)

private val base = Typography()
private val typography = Typography(
    displaySmall = base.displaySmall.m(FontWeight.ExtraBold, 40, -0.02),
    headlineLarge = base.headlineLarge.m(FontWeight.ExtraBold, 32, -0.02),
    headlineMedium = base.headlineMedium.m(FontWeight.ExtraBold, 26, -0.02),
    headlineSmall = base.headlineSmall.m(FontWeight.ExtraBold, 22, -0.02),
    titleLarge = base.titleLarge.m(FontWeight.Bold, 18, -0.01),
    titleMedium = base.titleMedium.m(FontWeight.Bold, 15),
    titleSmall = base.titleSmall.m(FontWeight.SemiBold, 14),
    bodyLarge = base.bodyLarge.m(FontWeight.Medium, 14),
    bodyMedium = base.bodyMedium.m(FontWeight.Medium, 13),
    bodySmall = base.bodySmall.m(FontWeight.Medium, 12),
    labelLarge = base.labelLarge.m(FontWeight.Bold, 13),
    labelMedium = base.labelMedium.m(FontWeight.Bold, 12),
    labelSmall = base.labelSmall.m(FontWeight.Bold, 11, 0.08),
)

/** [accent] folgt dem Cover des laufenden Titels. */
@Composable
fun NaviTheme(accent: Color = Navi.Accent, content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = scheme.copy(primary = accent, secondary = accent), typography = typography, content = content)
