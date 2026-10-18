package by.bebuliby.chrona.ui

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import by.bebuliby.chrona.domain.Computed
import by.bebuliby.chrona.domain.Emotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal fun Emotion.label(): String = when (this) {
    Emotion.TEARS -> "Слёзы"; Emotion.VERY_SAD -> "Сильная грусть"; Emotion.SAD -> "Небольшая грусть"
    Emotion.NEUTRAL -> "Ровный рот"; Emotion.SMILE -> "Улыбка"; Emotion.BIG_SMILE -> "Большая улыбка"
    Emotion.STARS -> "Звёзды в глазах"
}

@Composable
internal fun rememberReducedMotion(): Boolean {
    if (LocalInspectionMode.current) return true
    val resolver = LocalContext.current.contentResolver
    var reduced by remember { mutableStateOf(!ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = !ValueAnimator.areAnimatorsEnabled() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

/** Seven semantic faces. No emoji font dependency, infinite transition, tick or business formula. */
@Composable
internal fun EmotionFace(emotion: Computed<Emotion>?, reveal: Float = 1f, modifier: Modifier = Modifier) {
    val mood = (emotion as? Computed.Ready)?.value
    val label = mood?.label() ?: "Эмоция недоступна"
    val ink = MaterialTheme.colorScheme.onSurface
    val fill = MaterialTheme.colorScheme.secondaryContainer
    val accent = MaterialTheme.colorScheme.primary
    val scale = 1f + sin(reveal * PI).toFloat() * .12f
    Canvas(modifier.size(64.dp).graphicsLayer { scaleX = scale; scaleY = scale; rotationZ = sin(reveal * PI * 2).toFloat() * 5f }
        .semantics { contentDescription = label }) {
        val d = size.minDimension
        val c = Offset(size.width / 2, size.height / 2)
        val r = d * .43f
        val stroke = d * .033f
        drawCircle(fill, r, c)
        drawCircle(ink.copy(alpha = .15f), r, c, style = Stroke(stroke / 2))
        if (mood == null) {
            drawCircle(ink.copy(alpha = .55f), d * .027f, c - Offset(d * .1f, 0f))
            drawCircle(ink.copy(alpha = .55f), d * .027f, c)
            drawCircle(ink.copy(alpha = .55f), d * .027f, c + Offset(d * .1f, 0f))
            return@Canvas
        }
        val left = c + Offset(-d * .15f, -d * .10f)
        val right = c + Offset(d * .15f, -d * .10f)
        if (mood == Emotion.STARS) {
            listOf(left, right).forEach { eye ->
                val path = Path()
                repeat(10) { n ->
                    val angle = -PI / 2 + n * PI / 5
                    val radius = if (n % 2 == 0) d * .105f else d * .046f
                    val x = eye.x + cos(angle).toFloat() * radius
                    val y = eye.y + sin(angle).toFloat() * radius
                    if (n == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close(); drawPath(path, accent)
            }
        } else {
            listOf(left, right).forEach { eye -> drawOval(ink, eye - Offset(d * .028f, d * .04f), Size(d * .056f, d * .08f)) }
        }
        val mouth = Path()
        val y = c.y + d * .19f
        val bend = when (mood) {
            Emotion.TEARS -> -.22f; Emotion.VERY_SAD -> -.18f; Emotion.SAD -> -.10f
            Emotion.NEUTRAL -> 0f; Emotion.SMILE -> .12f; Emotion.BIG_SMILE, Emotion.STARS -> .23f
        }
        mouth.moveTo(c.x - d * .17f, y)
        mouth.quadraticTo(c.x, y + d * bend, c.x + d * .17f, y)
        drawPath(mouth, ink, style = Stroke(stroke))
        if (mood == Emotion.VERY_SAD || mood == Emotion.TEARS) {
            drawLine(ink, left + Offset(-d * .065f, -d * .09f), left + Offset(d * .04f, -d * .13f), stroke)
            drawLine(ink, right + Offset(-d * .04f, -d * .13f), right + Offset(d * .065f, -d * .09f), stroke)
        }
        if (mood == Emotion.TEARS) listOf(left, right).forEach { eye ->
            val tear = Path().apply {
                moveTo(eye.x, eye.y + d * .08f)
                quadraticTo(eye.x - d * .10f, eye.y + d * .23f, eye.x, eye.y + d * .24f)
                quadraticTo(eye.x + d * .10f, eye.y + d * .23f, eye.x, eye.y + d * .08f)
                close()
            }
            drawPath(tear, accent.copy(alpha = .7f))
        }
    }
}
