package com.flammedemon.certcheck.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Custom colors
val GreenOk = Color(0xFF2E7D32)
val GreenOkLight = Color(0xFFE8F5E9)
val OrangeWarning = Color(0xFFE65100)
val OrangeWarningLight = Color(0xFFFFF3E0)
val RedCritical = Color(0xFFC62828)
val RedCriticalLight = Color(0xFFFFEBEE)
val BlueInfo = Color(0xFF1565C0)
val BlueInfoLight = Color(0xFFE3F2FD)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF90CAF9),
    secondary = Color(0xFFA5D6A7),
    tertiary = Color(0xFFCE93D8),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1565C0),
    secondary = Color(0xFF2E7D32),
    tertiary = Color(0xFF7B1FA2),
    background = Color(0xFFF5F5F5),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun CertCheckTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // Dynamic colors on Android 12+
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
