package dev.navidroid.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.navidroid.R

/** Farben des Redesigns: ein ruhiger, fast schwarzer Grund mit einem einzigen Akzent. */
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

@OptIn(ExperimentalTextApi::class)
private fun manrope(weight: Int) = Font(
    R.font.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Manrope = FontFamily(manrope(400), manrope(500), manrope(600), manrope(700), manrope(800))

private val scheme = darkColorScheme(
    primary = Navi.Accent,
    onPrimary = Navi.OnAccent,
    primaryContainer = Navi.Surface2,
    onPrimaryContainer = Navi.Text,
    secondary = Navi.Accent,
    onSecondary = Navi.OnAccent,
    secondaryContainer = Navi.Surface2,
    onSecondaryContainer = Navi.Text,
    background = Navi.Bg,
    onBackground = Navi.Text,
    surface = Navi.Bg,
    onSurface = Navi.Text,
    surfaceVariant = Navi.Surface2,
    onSurfaceVariant = Navi.Muted,
    surfaceContainerLowest = Navi.Bg,
    surfaceContainerLow = Navi.Surface,
    surfaceContainer = Navi.Surface,
    surfaceContainerHigh = Navi.Surface2,
    surfaceContainerHighest = Navi.Surface2,
    outline = Navi.Outline,
    outlineVariant = Navi.Divider,
    error = Color(0xFFFF8A80),
)

private fun TextStyle.m(weight: FontWeight, size: Int? = null, tracking: Double? = null) = copy(
    fontFamily = Manrope,
    fontWeight = weight,
    fontSize = size?.sp ?: fontSize,
    letterSpacing = tracking?.em ?: letterSpacing,
)

/**
 * Für Überschriften: ausgewogene Zeilen und Silbentrennung (Sprache des Geräts) statt harter Brüche
 * mitten im Wort – lange deutsche Komposita wie „Indie-Rock-Montagnachmittag“ passen sonst nicht.
 */
private fun TextStyle.heading() = copy(lineBreak = LineBreak.Heading, hyphens = Hyphens.Auto)

private val base = Typography()
private val typography = Typography(
    displayLarge = base.displayLarge.m(FontWeight.ExtraBold, tracking = -0.02).heading(),
    displayMedium = base.displayMedium.m(FontWeight.ExtraBold, tracking = -0.02).heading(),
    displaySmall = base.displaySmall.m(FontWeight.ExtraBold, tracking = -0.02).heading(),
    headlineLarge = base.headlineLarge.m(FontWeight.ExtraBold, 30, -0.02).heading(),
    headlineMedium = base.headlineMedium.m(FontWeight.ExtraBold, 28, -0.02).heading(),
    headlineSmall = base.headlineSmall.m(FontWeight.ExtraBold, 26, -0.02).heading(),
    titleLarge = base.titleLarge.m(FontWeight.Bold, 19, -0.01).heading(),
    titleMedium = base.titleMedium.m(FontWeight.Bold, 16).heading(),
    titleSmall = base.titleSmall.m(FontWeight.SemiBold, 14).heading(),
    bodyLarge = base.bodyLarge.m(FontWeight.Medium, 15),
    bodyMedium = base.bodyMedium.m(FontWeight.Medium, 14),
    bodySmall = base.bodySmall.m(FontWeight.Medium, 12),
    labelLarge = base.labelLarge.m(FontWeight.Bold, 14),
    labelMedium = base.labelMedium.m(FontWeight.Bold, 12),
    labelSmall = base.labelSmall.m(FontWeight.Bold, 11, 0.08),
)

@Composable
fun NaviTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
