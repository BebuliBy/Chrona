package by.bebuliby.chrona.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import by.bebuliby.chrona.domain.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.math.MathContext
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
internal fun BigDecimal.number(): String = stripTrailingZeros().toPlainString().replace('.', ',')
internal fun percent(value: BigDecimal): String = "${value.setScale(1, RoundingMode.HALF_UP).number()}%"
internal fun presentedValue(value: BigDecimal, unit: ValueUnit): String =
    if (unit.code == "duration_ms") value.divide(BigDecimal(60_000), MathContext(6, RoundingMode.HALF_UP)).number() else value.number()
internal fun presentedUnit(unit: ValueUnit): String = if (unit.code == "duration_ms") "мин" else if (unit.code == "plan_shares") "доли плана" else unit.label
internal fun actualLabel(actual: ActualValue, unit: ValueUnit): String = when (actual) {
    is ActualValue.Known -> presentedValue(actual.value, unit)
    is ActualValue.Partial -> "≥ ${presentedValue(actual.observedValue, unit)}"
    is ActualValue.Unknown -> "—"
}

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

internal fun UnavailableReason.label(): String = when (this) {
    UnavailableReason.UNKNOWN_ACTUAL -> "Нет достоверного значения"
    UnavailableReason.PARTIAL_COVERAGE -> "Неполное наблюдение"
    UnavailableReason.NOT_PLANNED -> "Не запланировано"
    UnavailableReason.SCHEDULE_UNRESOLVED -> "Расписание ещё не определено"
    UnavailableReason.GOAL_UNRESOLVED -> "План для этого дня не определён"
    UnavailableReason.DAY_POLICY_UNRESOLVED -> "Правило границ дня ещё не выбрано"
    UnavailableReason.MULTI_DEVICE_UNRESOLVED -> "Сумма нескольких устройств не определена"
    UnavailableReason.ZERO_LIMIT_RATIO -> "При нулевом лимите отношение не вычисляется"
    UnavailableReason.ZERO_LIMIT_EMOTION_UNRESOLVED -> "Эмоция нулевого лимита ещё не выбрана"
    UnavailableReason.ZERO_LIMIT_CURVE_UNRESOLVED -> "Кривая нулевого лимита ещё не определена"
    UnavailableReason.STREAK_RULES_UNRESOLVED -> "Правила серий ещё не определены"
    UnavailableReason.SHARING_POLICY_UNRESOLVED -> "Область доступа ещё не разрешена"
    UnavailableReason.NOT_CONNECTED -> "Сервер не подключён"
    UnavailableReason.NOT_IMPLEMENTED -> "Функция ещё не подключена"
}

internal fun coverageLabel(actual: ActualValue): String = when (actual) {
    is ActualValue.Known -> "Известное значение · ${if (actual.source == FactSource.MANUAL) "ручная запись" else "наблюдение системы"}"
    is ActualValue.Partial -> "Измерена только часть дня. Это не окончательный итог."
    is ActualValue.Unknown -> actual.reason.label()
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
