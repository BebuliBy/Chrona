package by.bebuliby.chrona.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import by.bebuliby.chrona.domain.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))
internal val shortDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM", Locale.forLanguageTag("ru"))

/** Presentation validation only. The write boundary must validate again. No cap by goal or arbitrary 9999. */
internal fun parseDayNumber(text: String): BigDecimal? {
    val normalized = text.trim().replace(',', '.')
    if (!Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)").matches(normalized)) return null
    return normalized.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }
}

internal fun parseDate(text: String): LocalDate? = runCatching { LocalDate.parse(text.trim()) }.getOrNull()
internal fun friendDisplayName(friend: FriendSummary?): String {
    if (friend == null) return "Пользователь"
    return friend.displayName.trim().takeIf { it.isNotBlank() && it != friend.accountId && it != friend.requestId } ?: "Пользователь"
}
internal fun BigDecimal.number(): String = stripTrailingZeros().toPlainString().replace('.', ',')
internal fun percent(value: BigDecimal): String = "${value.setScale(1, RoundingMode.HALF_UP).number()}%"
/** Display only: truncate subsecond remainder, but never turn a nonzero duration into 0 seconds. */
internal fun durationLabel(milliseconds: BigDecimal): String {
    val magnitude = milliseconds.abs()
    val sign = if (milliseconds.signum() < 0) "−" else ""
    if (magnitude.signum() == 0) return "0 с"
    if (magnitude < BigDecimal(1000)) return "${sign}<1 с"
    val seconds = magnitude.divideToIntegralValue(BigDecimal(1000)).toBigInteger()
    val hours = seconds / BigInteger.valueOf(3600)
    val minutes = seconds % BigInteger.valueOf(3600) / BigInteger.valueOf(60)
    val rest = seconds % BigInteger.valueOf(60)
    return sign + listOfNotNull(
        hours.takeIf { it.signum() > 0 }?.let { "$it ч" },
        minutes.takeIf { it.signum() > 0 }?.let { "$it мин" },
        rest.takeIf { it.signum() > 0 }?.let { "$it с" },
    ).joinToString(" ")
}
internal fun presentedValue(value: BigDecimal, unit: ValueUnit): String =
    if (unit.code == "duration_ms") durationLabel(value) else value.number()
internal fun presentedUnit(unit: ValueUnit): String = when (unit.code) {
    "duration_ms" -> ""
    "plan_shares" -> "доли плана"
    else -> unit.label
}
internal fun measurementLabel(value: BigDecimal, unit: ValueUnit): String =
    listOf(presentedValue(value, unit), presentedUnit(unit)).filter { it.isNotBlank() }.joinToString(" ")
internal fun axisUnitLabel(unit: ValueUnit): String = if (unit.code == "duration_ms") "время" else presentedUnit(unit)
internal fun actualLabel(actual: ActualValue, unit: ValueUnit): String = when (actual) {
    is ActualValue.Known -> presentedValue(actual.value, unit)
    is ActualValue.Partial -> if (actual.source == FactSource.COLLECTOR && unit.code == "duration_ms")
        presentedValue(actual.observedValue, unit) else "≥ ${presentedValue(actual.observedValue, unit)}"
    is ActualValue.Unknown -> "Нет данных"
}
internal fun actualGoalLabel(cell: DayCell?, unit: ValueUnit): String =
    "${cell?.let { actualLabel(it.actual, unit) } ?: "Нет данных"} / ${cell?.goal?.let { presentedValue(it.amount, unit) } ?: "—"}" +
        presentedUnit(unit).takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()

/** Minute editor is independent of the human-readable h/min/s display. Preserve untouched raw facts. */
internal fun numberEditorText(value: BigDecimal?, unit: ValueUnit): String = value?.let {
    if (unit.code == "duration_ms") it.divide(BigDecimal(60_000), java.math.MathContext.DECIMAL128).stripTrailingZeros().toPlainString()
    else it.toPlainString()
}.orEmpty()
internal fun numberEditorValue(text: String, initial: BigDecimal?, unit: ValueUnit): BigDecimal? {
    val parsed = parseDayNumber(text) ?: return null
    return if (initial != null && text == numberEditorText(initial, unit)) initial
    else if (unit.code == "duration_ms") parsed.multiply(BigDecimal(60_000)) else parsed
}

internal fun intervalTimeLabel(start: Instant, end: Instant, zoneId: String, day: LocalDate? = null): String {
    val zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault())
    val a = start.atZone(zone); val b = end.atZone(zone)
    val clock = DateTimeFormatter.ofPattern("HH:mm:ss")
    val date = DateTimeFormatter.ofPattern(if (a.year != b.year || day != null && a.year != day.year) "dd.MM.yyyy" else "dd.MM")
    val first = if (a.toLocalDate() == day || day == null && a.toLocalDate() == b.toLocalDate()) clock.format(a) else "${date.format(a)} ${clock.format(a)}"
    val last = if (b.toLocalDate() == a.toLocalDate()) clock.format(b) else "${date.format(b)} ${clock.format(b)}"
    return "$first → $last"
}

internal data class CalendarDateMarker(val isToday: Boolean, val isSelected: Boolean)
internal fun calendarDateMarker(date: LocalDate, today: LocalDate?, selected: LocalDate?): CalendarDateMarker =
    CalendarDateMarker(date == today, date == selected)

/** This interpolates an already-derived score. It never derives score from facts. */
internal fun periodScoreColor(score: BigDecimal): Color {
    val bounded = score.coerceIn(BigDecimal.ZERO, BigDecimal(100))
    return if (bounded <= BigDecimal(50)) lerp(ChronaDesign.scoreLow, ChronaDesign.scoreMid, bounded.divide(BigDecimal(50)).toFloat())
    else lerp(ChronaDesign.scoreMid, ChronaDesign.scoreHigh, bounded.subtract(BigDecimal(50)).divide(BigDecimal(50)).toFloat())
}

/** Choose the higher-contrast ink. Burgundy cells must not inherit unreadable dark text. */
internal fun scoreContentColor(background: Color): Color {
    val luminance = background.luminance()
    return if ((luminance + .05f) / .05f >= 1.05f / (luminance + .05f)) Color.Black else Color.White
}

internal fun scoreLabel(score: Computed<BigDecimal>): String = when (score) {
    is Computed.Ready -> when {
        score.value.compareTo(BigDecimal.ONE) == 0 -> "✓"
        score.value.signum() == 0 -> "×"
        else -> {
            val p = score.value.multiply(BigDecimal(100))
            when {
                p < BigDecimal("0.1") -> "<0,1%"
                p > BigDecimal("99.9") -> ">99,9%"
                else -> percent(p)
            }
        }
    }
    is Computed.Unavailable -> if (score.reason == UnavailableReason.NOT_PLANNED) "·" else "—"
}

/** An unavailable point splits a path. Never bridge across missing coverage. */
internal fun contiguousCurveRuns(points: List<CurvePoint>): List<List<CurvePoint>> {
    val result = mutableListOf<List<CurvePoint>>()
    var run = mutableListOf<CurvePoint>()
    points.forEach { point ->
        if (point.cumulative is Computed.Ready) run.add(point)
        else if (run.isNotEmpty()) { result.add(run); run = mutableListOf() }
    }
    if (run.isNotEmpty()) result.add(run)
    return result
}
