package by.bebuliby.chrona.ui

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

/** Original yellow emoji-style Canvas art, not Telegram assets or a pixel-identical reproduction. */
@Composable
internal fun EmotionFace(emotion: Computed<Emotion>?, reveal: Float = 1f, modifier: Modifier = Modifier) {
    val supplied = (emotion as? Computed.Ready)?.value
    // Undefined goal/zero-limit emotion is a neutral drawing, never a made-up percentage or tier.
    val mood = supplied ?: Emotion.NEUTRAL
    val label = supplied?.label() ?: "Нейтральное лицо"
    val reduced = rememberReducedMotion()
    val appearance = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (reduced) appearance.snapTo(1f) else appearance.animateTo(1f, tween(520))
    }
    val intro = if (reduced) 1f else appearance.value
    val commit = if (reduced) 1f else reveal.coerceIn(0f, 1f)
    val scale = .94f + .06f * intro + sin(commit * PI).toFloat() * .045f
    val rotation = sin(intro * PI).toFloat() * 1.8f + sin(commit * PI * 2).toFloat() * 2.5f
    val blinkPhase = if (commit < 1f) commit else intro
    val blink = if (blinkPhase in .42f.. .58f) sin((blinkPhase - .42f) / .16f * PI).toFloat() else 0f
    val ink = Color(0xFF58320E)
    val goldenEdge = Color(0xFFE6A12A)
    val tearBlue = Color(0xFF47B7F4)
    Canvas(modifier.size(64.dp).graphicsLayer { scaleX = scale; scaleY = scale; rotationZ = rotation }
        .semantics { contentDescription = label }) {
        val d = size.minDimension
        val c = Offset(size.width / 2, size.height / 2)
        val r = d * .43f
        val stroke = d * .033f
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFFEB8A), Color(0xFFFFD044), Color(0xFFFFBD2F)),
            center = c + Offset(-d * .12f, -d * .18f), radius = d * .7f), r, c)
        drawCircle(goldenEdge, r, c, style = Stroke(stroke / 2))
        drawOval(Color.White.copy(alpha = .18f), c + Offset(-d * .24f, -d * .32f), Size(d * .30f, d * .12f))
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
                path.close()
                drawPath(path, Color(0xFFFFF3A0))
                drawPath(path, Color(0xFFBC7412), style = Stroke(stroke * .65f))
            }
        } else {
            listOf(left, right).forEach { eye ->
                val height = d * .092f * (1f - .85f * blink)
                drawOval(ink, eye - Offset(d * .031f, height / 2), Size(d * .062f, height))
                if (blink < .3f) drawCircle(Color.White.copy(alpha = .7f), d * .010f, eye + Offset(-d * .009f, -d * .018f))
            }
        }
        val mouth = Path()
        val y = c.y + d * .19f
        val bend = when (mood) {
            Emotion.TEARS -> -.22f; Emotion.VERY_SAD -> -.18f; Emotion.SAD -> -.10f
            Emotion.NEUTRAL -> 0f; Emotion.SMILE -> .12f; Emotion.BIG_SMILE, Emotion.STARS -> .23f
        }
        mouth.moveTo(c.x - d * .17f, y)
        mouth.quadraticTo(c.x, y + d * bend, c.x + d * .17f, y)
        if (mood == Emotion.BIG_SMILE || mood == Emotion.STARS) {
            mouth.quadraticTo(c.x, y + d * .025f, c.x - d * .17f, y)
            mouth.close()
            drawPath(mouth, ink)
            val teeth = Path().apply {
                moveTo(c.x - d * .12f, y + d * .015f)
                quadraticTo(c.x, y + d * .055f, c.x + d * .12f, y + d * .015f)
            }
            drawPath(teeth, Color(0xFFFFFCED), style = Stroke(stroke * 1.5f))
        } else drawPath(mouth, ink, style = Stroke(stroke))
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
            drawPath(tear, tearBlue)
            drawCircle(Color.White.copy(alpha = .65f), d * .017f, eye + Offset(-d * .014f, d * .18f))
        }
    }
}
