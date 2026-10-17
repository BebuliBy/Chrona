package by.bebuliby.chrona.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import by.bebuliby.chrona.preview.Palette
import by.bebuliby.chrona.preview.ThemeMode

/** Centralized draft design tokens; palette is awaiting the user's visual approval. */
@Composable
fun ChronaTheme(mode: ThemeMode, palette: Palette, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        view.context.activity()?.let { activity ->
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val primary = when {
        palette == Palette.OCEAN && dark -> Color(0xFFA8C7FF)
        palette == Palette.OCEAN -> Color(0xFF365F99)
        dark -> Color(0xFFD3BCFF)
        else -> Color(0xFF73549A)
    }
    val scheme = if (dark) darkColorScheme(
        primary = primary, onPrimary = Color(0xFF142846),
        primaryContainer = if (palette == Palette.OCEAN) Color(0xFF263E5F) else Color(0xFF493460),
        onPrimaryContainer = Color(0xFFE1EAFF),
        background = Color(0xFF10141C), onBackground = Color(0xFFE3E6ED),
        surface = Color(0xFF191E28), onSurface = Color(0xFFE3E6ED),
        surfaceVariant = Color(0xFF282F3D), onSurfaceVariant = Color(0xFFBFC7D7),
    ) else lightColorScheme(
        primary = primary, onPrimary = Color.White,
        primaryContainer = if (palette == Palette.OCEAN) Color(0xFFDBE9FF) else Color(0xFFEEDCFF),
        onPrimaryContainer = Color(0xFF182D4A),
        background = Color(0xFFF6F8FC), onBackground = Color(0xFF1A2230),
        surface = Color.White, onSurface = Color(0xFF1A2230),
        surfaceVariant = Color(0xFFE8EDF5), onSurfaceVariant = Color(0xFF536074),
    )
    MaterialTheme(
        colorScheme = scheme,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.activity() else null
    else -> null
}
