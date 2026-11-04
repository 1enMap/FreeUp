package app.freeup.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF1B6EF3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF001A43),
    tertiary = Color(0xFF0B8A5F),
    tertiaryContainer = Color(0xFFCFF3E2),
    onTertiaryContainer = Color(0xFF00281A),
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFF7F8FA),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFAFC6FF),
    onPrimary = Color(0xFF002D6E),
    primaryContainer = Color(0xFF00429A),
    onPrimaryContainer = Color(0xFFDCE6FF),
    tertiary = Color(0xFF7FDBB2),
    tertiaryContainer = Color(0xFF00513A),
    onTertiaryContainer = Color(0xFFCFF3E2),
    background = Color(0xFF0E1116),
    surface = Color(0xFF0E1116),
)

@Composable
fun FreeUpTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
