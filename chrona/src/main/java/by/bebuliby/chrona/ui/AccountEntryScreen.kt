package by.bebuliby.chrona.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import by.bebuliby.chrona.domain.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A dedicated entry route, not an overlay on the app/navigation or a second identity implementation. */
@Composable
internal fun AccountEntryScreen(state: ChronaUiState, dispatch: (ChronaAction) -> Unit, updateCheckError: Boolean = false) {
    BackHandler { /* Choose account/guest explicitly. OS Home remains available. */ }
    val configured = normalizedPublicServerBaseUrl(state.connection.baseUrl) != null
    val blue = Color(0xFF3F87F7)
    val hero = Color(0xFFA5E5FC)
    val dark = Color(0xFF0D0D0F)
    BoxWithConstraints(Modifier.fillMaxSize().background(dark).testTag("account_entry")) {
        val heroHeight = maxHeight * .36f
        val bodyHeight = maxHeight - heroHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(heroHeight)) {
                Canvas(Modifier.fillMaxSize()) {
                    val arc = Path().apply {
                        moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height * .86f)
                        quadraticTo(size.width / 2, size.height * 1.14f, 0f, size.height * .86f)
                        close()
                    }
                    drawPath(arc, hero)
                }
                Box(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = 20.dp), contentAlignment = Alignment.Center) {
                    ChronaClockArt(Modifier.size((heroHeight * .58f).coerceIn(88.dp, 200.dp)))
                }
            }
            Column(Modifier.fillMaxWidth().heightIn(min = bodyHeight).navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, top = (bodyHeight * .14f).coerceIn(20.dp, 80.dp), bottom = 28.dp),
                verticalArrangement = Arrangement.Top, horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.connection.loginPending) Text("Подтвердите вход в Telegram", color = Color.White,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        dispatch(if (state.connection.loginPending) ChronaAction.RefreshLoginStatus else ChronaAction.BeginTelegramLogin)
                    }, enabled = configured && !state.busy, shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = blue, contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("entry_telegram")) {
                        TelegramPlane(Modifier.size(25.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(if (state.connection.loginPending) "Проверить вход в Telegram" else "Войти с помощью Telegram",
                            Modifier.weight(1f), maxLines = 2, textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Button(onClick = { dispatch(ChronaAction.ContinueWithoutAccount) }, enabled = !state.busy, shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF232427), contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("entry_guest")) {
                        Text("Продолжить без учётной записи", maxLines = 2, textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    if (!configured) Text("Вход временно недоступен", color = Color(0xFFD8DDE3),
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = blue)
                }
            }
        }
        TransientNoticeBanner(state.notice, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp), updateCheckError)
    }
}

/** Original static clock glyph; no borrowed/downloaded launcher, Telegram or reference-image assets. */
@Composable
internal fun ChronaClockArt(modifier: Modifier = Modifier) {
    Canvas(modifier.semantics { contentDescription = "Часы Chrona" }) {
        val d = size.minDimension
        val center = Offset(size.width / 2, size.height / 2)
        val rim = Color(0xFF236C9D)
        val ink = Color(0xFF193E66)
        drawCircle(rim.copy(alpha = .14f), d * .44f, center + Offset(0f, d * .035f))
        drawCircle(rim, d * .44f, center)
        drawCircle(Color(0xFFF7FCFF), d * .365f, center)
        repeat(12) { tick ->
            val angle = tick * PI / 6 - PI / 2
            val a = Offset(cos(angle).toFloat(), sin(angle).toFloat())
            val radius = if (tick % 3 == 0) .285f else .305f
            drawLine(ink.copy(alpha = if (tick % 3 == 0) 1f else .45f), center + a * (d * radius),
                center + a * (d * .325f), d * .018f, cap = StrokeCap.Round)
        }
        drawLine(ink, center, center + Offset(-d * .18f, -d * .13f), d * .043f, cap = StrokeCap.Round)
        drawLine(ink, center, center + Offset(d * .22f, -d * .20f), d * .035f, cap = StrokeCap.Round)
        drawCircle(rim, d * .035f, center)
        drawCircle(Color.White.copy(alpha = .6f), d * .44f, center, style = Stroke(d * .009f))
    }
}

@Composable
private fun TelegramPlane(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val plane = Path().apply {
            moveTo(size.width * .06f, size.height * .43f)
            lineTo(size.width * .94f, size.height * .10f)
            lineTo(size.width * .76f, size.height * .91f)
            lineTo(size.width * .49f, size.height * .68f)
            lineTo(size.width * .34f, size.height * .83f)
            lineTo(size.width * .34f, size.height * .55f)
            close()
        }
        drawPath(plane, Color.White)
        val fold = Path().apply {
            moveTo(size.width * .34f, size.height * .55f)
            lineTo(size.width * .80f, size.height * .25f)
            lineTo(size.width * .49f, size.height * .68f)
            lineTo(size.width * .34f, size.height * .83f)
            close()
        }
        drawPath(fold, Color(0xFFB9DCFF))
    }
}
