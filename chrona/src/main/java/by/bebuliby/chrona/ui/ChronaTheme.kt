package by.bebuliby.chrona.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import by.bebuliby.chrona.domain.AppPreferences
import by.bebuliby.chrona.domain.ThemeMode

internal data class ChronaPalette(val id: String, val title: String, val lightAccent: Color, val darkAccent: Color)
internal data class ChronaStyle(val id: String, val title: String, val radius: Dp, val spacing: Dp, val outlined: Boolean)

/** Stable persisted IDs. Unknown IDs fall back visually, without dispatching a preference write. */
internal object ChronaDesign {
    val palettes = listOf(
        ChronaPalette("ocean", "Океан", Color(0xFF365F99), Color(0xFFA8C7FF)),
        ChronaPalette("lilac", "Лаванда", Color(0xFF73549A), Color(0xFFD3BCFF)),
        ChronaPalette("forest", "Лес", Color(0xFF34694D), Color(0xFF9AD5AF)),
        ChronaPalette("sage", "Шалфей", Color(0xFF526444), Color(0xFFBBCEA7)),
        ChronaPalette("teal", "Прилив", Color(0xFF20696B), Color(0xFF97D5D6)),
        ChronaPalette("sky", "Небо", Color(0xFF32667C), Color(0xFFA2CFE4)),
        ChronaPalette("rose", "Роза", Color(0xFF944F66), Color(0xFFF1B6CB)),
        ChronaPalette("clay", "Терракота", Color(0xFF92533B), Color(0xFFF2BAA2)),
        ChronaPalette("amber", "Янтарь", Color(0xFF795C20), Color(0xFFE8CA8A)),
        ChronaPalette("plum", "Слива", Color(0xFF80517D), Color(0xFFE1B5DC)),
        ChronaPalette("slate", "Сланец", Color(0xFF526070), Color(0xFFBAC8DB)),
        ChronaPalette("coffee", "Кофе", Color(0xFF765A45), Color(0xFFDFC2A9)),
    )
    val styles = listOf(
        ChronaStyle("standard", "Спокойный", 22.dp, 16.dp, false),
        ChronaStyle("soft", "Мягкий", 30.dp, 20.dp, false),
        ChronaStyle("editorial", "Чёткий", 12.dp, 14.dp, true),
    )
    fun palette(id: String) = palettes.firstOrNull { it.id == id } ?: palettes.first()
    fun style(id: String) = styles.firstOrNull { it.id == id } ?: styles.first()
    val youtubeRed = Color(0xFFA91E28)
    val youtubeWhite = Color(0xFFFFFAFA)
    val scoreLow = Color(0xFF865061)
    val scoreMid = Color(0xFFD3BE75)
    val scoreHigh = Color(0xFF6EA58A)
}

internal val LocalChronaStyle = staticCompositionLocalOf { ChronaDesign.styles.first() }

@Composable
fun ChronaTheme(preferences: AppPreferences, content: @Composable () -> Unit) {
    val dark = when (preferences.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val palette = ChronaDesign.palette(preferences.paletteId)
    val style = ChronaDesign.style(preferences.styleId)
    val accent = if (dark) palette.darkAccent else palette.lightAccent
    val base = if (dark) Color(0xFF11151B) else Color(0xFFF8FAFD)
    val surface = lerp(base, accent, if (dark) .055f else .025f)
    val text = if (dark) Color(0xFFE8EBF0) else Color(0xFF1B2530)
    val variant = lerp(base, accent, if (dark) .15f else .11f)
    val scheme = if (dark) darkColorScheme(
        primary = accent, onPrimary = Color(0xFF182331),
        primaryContainer = lerp(base, palette.lightAccent, .42f), onPrimaryContainer = text,
        secondary = accent, secondaryContainer = variant, onSecondaryContainer = text,
        background = base, onBackground = text, surface = surface, onSurface = text,
        surfaceVariant = variant, onSurfaceVariant = Color(0xFFC0C8D3),
        outline = Color(0xFF7D8897), outlineVariant = Color(0xFF3E4855),
    ) else lightColorScheme(
        primary = accent, onPrimary = Color.White,
        primaryContainer = lerp(Color.White, accent, .14f), onPrimaryContainer = text,
        secondary = accent, secondaryContainer = variant, onSecondaryContainer = text,
        background = base, onBackground = text, surface = surface, onSurface = text,
        surfaceVariant = variant, onSurfaceVariant = Color(0xFF4C5B6C),
        outline = Color(0xFF6D7B89), outlineVariant = Color(0xFFCFD7E1),
    )
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        view.context.activity()?.let {
            WindowCompat.getInsetsController(it.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalChronaStyle provides style) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(style.radius / 2),
                medium = RoundedCornerShape(style.radius), large = RoundedCornerShape(style.radius),
                extraLarge = RoundedCornerShape(style.radius + 6.dp),
            ), content = content,
        )
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.activity() else null
    else -> null
}
