package com.local.geckobrowser.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.local.geckobrowser.browser.ThemeMode

private val LightColors = lightColorScheme(
    primary = Green40, secondary = GreenGrey40, tertiary = LightGreen40,
    background = Neutral90, surface = Neutral90, error = ErrorRed
)
private val DarkColors = darkColorScheme(
    primary = Green80, secondary = GreenGrey80, tertiary = LightGreen80,
    background = Neutral10, surface = Neutral10, error = ErrorRed
)

@Composable
fun GeckoBrowserTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    highContrast: Boolean = false,
    content: @Composable () -> Unit
) {
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val scheme = when {
        // Android 12+ 动态取色；系统高对比度开启时切换到预设高对比配色。
        dynamicColor && !highContrast && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        highContrast -> if (dark) DarkColors.copy(primary = Green80, outline = Green80)
        else LightColors.copy(primary = Green40, outline = Green40)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
