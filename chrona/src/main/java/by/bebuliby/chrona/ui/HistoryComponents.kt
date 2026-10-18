package by.bebuliby.chrona.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import by.bebuliby.chrona.domain.*
import java.math.BigDecimal
import java.math.MathContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

internal fun periodRange(period: HistoryPeriod, anchor: LocalDate, zone: String): DayRange {
    val start = when (period) {
        HistoryPeriod.WEEK -> anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        HistoryPeriod.MONTH -> anchor.withDayOfMonth(1)
        HistoryPeriod.YEAR -> anchor.withDayOfYear(1)
    }
    val end = when (period) {
        HistoryPeriod.WEEK -> start.plusWeeks(1)
        HistoryPeriod.MONTH -> start.plusMonths(1)
        HistoryPeriod.YEAR -> start.plusYears(1)
    }
    return DayRange(start, end, zone)
}

private fun HistoryPeriod.title(): String = when (this) {
    HistoryPeriod.WEEK -> "Неделя"; HistoryPeriod.MONTH -> "Месяц"; HistoryPeriod.YEAR -> "Год"
}

@Composable
internal fun HistoryPage(
    habits: List<Habit>, history: HistoryUiState, dispatch: (ChronaAction) -> Unit,
    readOnly: Boolean = false, onOpenCell: ((String, DayKey) -> Unit)? = null,
    onSelectHistory: ((HistoryPeriod, DayRange) -> Unit)? = null,
) {
    var stats by rememberSaveable { mutableStateOf(false) }
    val canNavigate = !readOnly || onSelectHistory != null
    val selectHistory: (HistoryPeriod, DayRange) -> Unit = { period, range ->
        onSelectHistory?.invoke(period, range) ?: dispatch(ChronaAction.SelectHistory(period, range))
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (readOnly) "Разрешённая история" else "История", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = { stats = true }, modifier = Modifier.testTag("open_statistics")) { Text("Статистика") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryPeriod.entries.forEach { period ->
                    FilterChip(history.period == period, enabled = canNavigate, onClick = {
                        selectHistory(period, periodRange(period, history.range.fromInclusive, history.range.zoneId))
                    }, label = { Text(period.title()) }, modifier = Modifier.testTag("period_${period.name}"))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = canNavigate, onClick = {
                    val previous = when (history.period) {
                        HistoryPeriod.WEEK -> history.range.fromInclusive.minusWeeks(1)
                        HistoryPeriod.MONTH -> history.range.fromInclusive.minusMonths(1)
                        HistoryPeriod.YEAR -> history.range.fromInclusive.minusYears(1)
                    }
                    selectHistory(history.period, periodRange(history.period, previous, history.range.zoneId))
                }) { Text("‹") }
                Text("${shortDateFormat.format(history.range.fromInclusive)} — ${dateFormat.format(history.range.toExclusive.minusDays(1))}",
                    Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
                TextButton(enabled = canNavigate, onClick = {
                    selectHistory(history.period, periodRange(history.period, history.range.toExclusive, history.range.zoneId))
                }) { Text("›") }
            }
        }
        item { PeriodScore(history.summary) }
        if (history.period == HistoryPeriod.YEAR) item {
            InfoCard("Годовой вид ещё не выбран", "Пользователь пока не определил layout года. Здесь намеренно нет новой heatmap. Кнопка «Статистика» показывает переданные ядром числа и кривые выбранного года.", Modifier.testTag("year_layout_unselected"))
        } else item {
            HistoryTable(habits, history) { id, day ->
                onOpenCell?.invoke(id, day) ?: if (!readOnly) dispatch(ChronaAction.OpenHabit(id, day)) else Unit
            }
        }
        item {
            Text("✓ — выполнение 100% · × — оценка 0% · частичная оценка — процент. «—» не означает провал, «·» — день без плана.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (history.period == HistoryPeriod.MONTH) Text("Видны 14 дней. Проведите по датам: заголовок и строки перемещаются вместе, названия закреплены.", style = MaterialTheme.typography.bodySmall)
        }
        if (habits.isEmpty()) item { InfoCard("Нет доступных привычек", "Таблица не заполняется вымышленными данными.") }
    }
    if (stats) StatisticsDialog(habits, history, onDismiss = { stats = false })
}

@Composable
internal fun PeriodScore(summary: PeriodSummary) {
    when (val score = summary.scorePercent) {
        is Computed.Ready -> Surface(color = periodScoreColor(score.value), contentColor = scoreContentColor(periodScoreColor(score.value)),
            shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("period_score")) {
            Column(Modifier.padding(16.dp)) {
                Text("${percent(score.value)} выполнения", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("За выбранный период · ${summary.knownCells}/${summary.plannedCells} известных плановых ячеек", style = MaterialTheme.typography.bodySmall)
            }
        }
        is Computed.Unavailable -> InfoCard("Итог периода недоступен", "${score.reason.label()}. Известно ${summary.knownCells} из ${summary.plannedCells} плановых ячеек. Неизвестное не заменено нулём.", Modifier.testTag("period_unavailable"))
    }
}

@Composable
private fun HistoryTable(habits: List<Habit>, history: HistoryUiState, onOpen: (String, DayKey) -> Unit) {
    val count = ChronoUnit.DAYS.between(history.range.fromInclusive, history.range.toExclusive).toInt()
    val dates = remember(history.range) { List(count.coerceIn(0, 366)) { history.range.fromInclusive.plusDays(it.toLong()) } }
    val cells = remember(history.cells) { history.cells.associateBy { it.habitId to it.day.date } }
    val monthly = history.period == HistoryPeriod.MONTH
    val scroll = rememberScrollState()
    val rowHeight = 76.dp
    val surface = MaterialTheme.colorScheme.surface
    BoxWithConstraints(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(surface).testTag("history_table")) {
        val nameWidth = maxWidth * .27f
        val viewport = maxWidth - nameWidth
        val width = viewport / if (monthly) 14 else 7
        Row {
            // This column is outside the horizontally scrolling viewport: truly sticky labels.
            Column(Modifier.width(nameWidth).background(MaterialTheme.colorScheme.surfaceVariant).testTag("sticky_habits")) {
                Box(Modifier.height(54.dp).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.CenterStart) {
                    Text("Привычка", style = MaterialTheme.typography.labelSmall)
                }
                habits.forEach { habit ->
                    Box(Modifier.height(rowHeight).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.CenterStart) {
                        Text(habit.title, style = MaterialTheme.typography.labelMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            // Exactly one ScrollState and one scroll container for both header AND body. No pager/snap.
            Column(Modifier.width(viewport).horizontalScroll(scroll).testTag("history_scroll")) {
                Row {
                    dates.forEach { date ->
                        Column(Modifier.width(width).height(54.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(date.dayOfMonth.toString(), fontSize = if (monthly) 10.sp else 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (monthly) date.monthValue.toString() else listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")[date.dayOfWeek.value - 1],
                                fontSize = if (monthly) 9.sp else 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                habits.forEach { habit ->
                    Row {
                        dates.forEach { date ->
                            val cell = cells[habit.id to date]
                            val score = (cell?.score as? Computed.Ready)?.value
                            val color = score?.let { periodScoreColor(it.multiply(BigDecimal(100))) } ?: MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)
                            val label = cell?.let { scoreLabel(it.score) } ?: "—"
                            Box(Modifier.width(width).height(rowHeight).padding(horizontal = 1.dp, vertical = 5.dp)
                                .clip(MaterialTheme.shapes.extraSmall).background(color)
                                .clickable { onOpen(habit.id, cell?.day ?: DayKey(date, history.range.zoneId)) }
                                .semantics { contentDescription = "${habit.title}, ${dateFormat.format(date)}: ${if (cell == null) "нет данных" else coverageLabel(cell.actual)}, выполнение $label" }
                                .testTag("cell_${habit.id}_$date"), contentAlignment = Alignment.Center) {
                                Text(label, color = if (score == null) MaterialTheme.colorScheme.onSurfaceVariant else scoreContentColor(color),
                                    fontSize = if (monthly) 9.sp else 12.sp, fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center, maxLines = 2)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatisticsDialog(habits: List<Habit>, history: HistoryUiState, onDismiss: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var chooseCurve by remember { mutableStateOf(false) }
    val curve = history.summary.curves.find { it.habitId == selected }
    Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.92f).padding(12.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Статистика · ${history.period.title().lowercase()}", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text("Готово") }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { PeriodScore(history.summary) }
                    item { Box {
                        OutlinedButton(onClick = { chooseCurve = true }) {
                            Text(selected?.let { id -> habits.find { it.id == id }?.title } ?: "Все привычки · доли плана")
                        }
                        DropdownMenu(chooseCurve, onDismissRequest = { chooseCurve = false }) {
                            DropdownMenuItem(text = { Text("Все привычки · доли плана") }, onClick = { selected = null; chooseCurve = false })
                            habits.forEach { habit -> DropdownMenuItem(text = { Text(habit.title) }, onClick = { selected = habit.id; chooseCurve = false }) }
                        }
                    } }
                    item { if (curve != null) CumulativeChart(curve) else InfoCard("Кривая недоступна", "Ядро не передало вычисленную кривую. UI не суммирует разнородные единицы и не подставляет нули.") }
                    item { Text("Числа по дням", style = MaterialTheme.typography.titleMedium) }
                    val visible = history.cells.filter { selected == null || it.habitId == selected }.sortedBy { it.day.date }
                    items(visible) { cell ->
                        val habit = habits.find { it.id == cell.habitId }
                        if (habit != null) Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${shortDateFormat.format(cell.day.date)} · ${habit.title}", style = MaterialTheme.typography.labelLarge)
                                Text("${actualLabel(cell.actual, habit.unit)} / ${cell.goal?.let { presentedValue(it.amount, habit.unit) } ?: "—"} ${presentedUnit(habit.unit)}")
                                Text("Выполнение: ${scoreLabel(cell.score)} · ${when (val usage = cell.usagePercent) {
                                    is Computed.Ready -> "факт/план ${percent(usage.value)}"
                                    is Computed.Unavailable -> usage.reason.label()
                                }}", style = MaterialTheme.typography.bodySmall)
                                Text(coverageLabel(cell.actual), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (visible.isEmpty()) item { Text("Нет переданных значений за этот период.") }
                }
            }
        }
    }
}

@Composable
internal fun CumulativeChart(curve: CumulativeCurve) {
    var showPointList by rememberSaveable(curve.habitId, curve.points.firstOrNull()?.day?.date?.toString()) { mutableStateOf(false) }
    val ink = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val values = curve.points.mapNotNull { (it.cumulative as? Computed.Ready)?.value }
    val maxAbs = values.maxOfOrNull { it.abs() }?.takeIf { it.signum() > 0 } ?: BigDecimal.ONE
    val missing = curve.points.mapNotNull { (it.cumulative as? Computed.Unavailable)?.reason }.distinct()
    val runs = contiguousCurveRuns(curve.points)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Накопленное отклонение · ${presentedUnit(curve.unit)}", style = MaterialTheme.typography.titleSmall)
        Text("0 — точно по плану. Выше — избыток полезного / экономия лимита. Разрывы не соединяются.", style = MaterialTheme.typography.bodySmall)
        if (values.isEmpty()) {
            InfoCard("Нет доступных точек", missing.joinToString("; ") { it.label() }.ifEmpty { "Нет данных" })
        } else {
            Row(Modifier.fillMaxWidth().height(210.dp)) {
                Column(Modifier.width(64.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    Text("+${presentedValue(maxAbs, curve.unit)}", style = MaterialTheme.typography.labelSmall)
                    Text("0 · план", style = MaterialTheme.typography.labelSmall)
                    Text("−${presentedValue(maxAbs, curve.unit)}", style = MaterialTheme.typography.labelSmall)
                }
                Canvas(Modifier.weight(1f).fillMaxHeight().padding(vertical = 8.dp).testTag("cumulative_chart")
                    .semantics { contentDescription = "Накопленная кривая, ${presentedUnit(curve.unit)}, ось плана ноль, известных точек ${values.size}, разрывов ${curve.points.size - values.size}" }) {
                    val zero = size.height / 2
                    drawLine(axis.copy(alpha = .6f), Offset(0f, zero), Offset(size.width, zero), 1.dp.toPx())
                    drawLine(axis.copy(alpha = .3f), Offset.Zero, Offset(0f, size.height), 1.dp.toPx())
                    val index = curve.points.withIndex().associate { it.value.day to it.index }
                    runs.forEach { run ->
                        val path = Path()
                        run.forEachIndexed { n, point ->
                            val raw = (point.cumulative as Computed.Ready).value
                            // Normalize with decimal arithmetic BEFORE Float: huge valid raw values do not overflow Canvas.
                            val normalized = raw.divide(maxAbs, MathContext.DECIMAL64).toFloat()
                            val x = (index[point.day] ?: 0).toFloat() / (curve.points.size - 1).coerceAtLeast(1) * size.width
                            val y = zero - normalized * (zero - 4.dp.toPx())
                            if (n == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            drawCircle(ink, 2.5.dp.toPx(), Offset(x, y))
                        }
                        drawPath(path, ink, style = Stroke(2.dp.toPx()))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(curve.points.firstOrNull()?.day?.date?.let(shortDateFormat::format) ?: "", style = MaterialTheme.typography.labelSmall)
                Text(curve.points.lastOrNull()?.day?.date?.let(shortDateFormat::format) ?: "", style = MaterialTheme.typography.labelSmall)
            }
            if (missing.isNotEmpty()) Text(missing.joinToString("; ") { it.label() }, style = MaterialTheme.typography.bodySmall)
            // An explicit text equivalent for accessibility; year does not eagerly render hundreds of rows.
            TextButton(onClick = { showPointList = !showPointList }) { Text(if (showPointList) "Скрыть значения точек" else "Значения точек кривой") }
            if (showPointList) curve.points.forEach { point ->
                Text("${shortDateFormat.format(point.day.date)}: ${when (val value = point.cumulative) {
                    is Computed.Ready -> "${presentedValue(value.value, curve.unit)} ${presentedUnit(curve.unit)}"
                    is Computed.Unavailable -> value.reason.label()
                }}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
