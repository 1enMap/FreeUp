package app.freeup.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * FreeUp's look: calm ink surfaces, an indigo accent for actions and a mint "space freed" colour
 * that always means savings. Amber is reserved for caveats. Fixed rather than wallpaper-based so
 * those meanings never change.
 */

@Immutable
data class Accents(
    val saving: Color,
    val onSaving: Color,
    val savingContainer: Color,
    val caution: Color,
    val cautionContainer: Color,
    val onCautionContainer: Color,
    val heroStart: Color,
    val heroEnd: Color,
)

private val DarkAccents = Accents(
    saving = Color(0xFF4DE3A8),
    onSaving = Color(0xFF00382A),
    savingContainer = Color(0xFF0F3D30),
    caution = Color(0xFFFFC15E),
    cautionContainer = Color(0xFF3D2E12),
    onCautionContainer = Color(0xFFFFE2B3),
    heroStart = Color(0xFF26306B),
    heroEnd = Color(0xFF0F4A3E),
)

private val LightAccents = Accents(
    saving = Color(0xFF00875A),
    onSaving = Color.White,
    savingContainer = Color(0xFFD2F5E6),
    caution = Color(0xFF9A6200),
    cautionContainer = Color(0xFFFFEBC7),
    onCautionContainer = Color(0xFF3D2700),
    heroStart = Color(0xFFDDE2FF),
    heroEnd = Color(0xFFCFF3E4),
)

val LocalAccents = staticCompositionLocalOf { DarkAccents }

private val Dark = darkColorScheme(
    primary = Color(0xFF9EAEFF),
    onPrimary = Color(0xFF14206B),
    primaryContainer = Color(0xFF2C3A8C),
    onPrimaryContainer = Color(0xFFDEE2FF),
    secondary = Color(0xFFB9C3E0),
    onSecondary = Color(0xFF232C44),
    secondaryContainer = Color(0xFF2A3350),
    onSecondaryContainer = Color(0xFFDCE2F9),
    tertiary = Color(0xFF4DE3A8),
    onTertiary = Color(0xFF00382A),
    error = Color(0xFFFF8A80),
    errorContainer = Color(0xFF5C1D18),
    onErrorContainer = Color(0xFFFFDAD5),
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFE4E7F0),
    surface = Color(0xFF0B0E14),
    onSurface = Color(0xFFE4E7F0),
    surfaceVariant = Color(0xFF1D2330),
    onSurfaceVariant = Color(0xFF9AA3B8),
    surfaceContainerLowest = Color(0xFF07090D),
    surfaceContainerLow = Color(0xFF11151D),
    surfaceContainer = Color(0xFF151A24),
    surfaceContainerHigh = Color(0xFF1B212D),
    surfaceContainerHighest = Color(0xFF222937),
    outline = Color(0xFF3A4356),
    outlineVariant = Color(0xFF262D3B),
)

private val Light = lightColorScheme(
    primary = Color(0xFF3A4FCF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDEE2FF),
    onPrimaryContainer = Color(0xFF0E1A66),
    secondary = Color(0xFF545D78),
    secondaryContainer = Color(0xFFDCE2F9),
    onSecondaryContainer = Color(0xFF111A31),
    tertiary = Color(0xFF00875A),
    onTertiary = Color.White,
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF161A23),
    surface = Color(0xFFF6F7FB),
    onSurface = Color(0xFF161A23),
    surfaceVariant = Color(0xFFE3E6EF),
    onSurfaceVariant = Color(0xFF5A6275),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F2F7),
    surfaceContainer = Color(0xFFEAEDF4),
    surfaceContainerHigh = Color(0xFFE4E7EF),
    surfaceContainerHighest = Color(0xFFDEE1EA),
    outline = Color(0xFFC3C8D6),
    outlineVariant = Color(0xFFDDE1EA),
)

private val BaseType = Typography()
private val Type = BaseType.copy(
    displaySmall = BaseType.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineLarge = BaseType.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = BaseType.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = BaseType.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = BaseType.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseType.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = BaseType.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = BaseType.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
fun FreeUpTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalAccents provides if (dark) DarkAccents else LightAccents) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, content = content)
    }
}
