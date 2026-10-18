package by.bebuliby.chrona.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import by.bebuliby.chrona.domain.*
import java.math.BigDecimal
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
internal fun HabitTile(
    habit: Habit, cell: DayCell?, commit: ValueCommit? = null, reveal: Float = 1f,
    onClick: () -> Unit,
) {
    val youtube = habit.collectionMode == CollectionMode.AUTOMATIC_SHORTS
    val ink = if (youtube) ChronaDesign.youtubeRed else MaterialTheme.colorScheme.onSurface
    val body = if (youtube) ChronaDesign.youtubeWhite else MaterialTheme.colorScheme.surface
    val animate = commit?.let { it.habitId == habit.id && it.day == cell?.day } == true
    Card(
        onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("habit_${habit.id}"),
        colors = CardDefaults.cardColors(containerColor = body, contentColor = ink),
        border = BorderStroke(if (youtube || LocalChronaStyle.current.outlined) 1.dp else 0.dp,
            if (youtube) ChronaDesign.youtubeRed.copy(alpha = .25f) else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(LocalChronaStyle.current.spacing), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (youtube) Surface(color = ChronaDesign.youtubeRed, contentColor = Color.White, shape = MaterialTheme.shapes.small) {
                    Text("▶", Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(habit.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(if (youtube) "YouTube · автоматически" else if (habit.direction == GoalDirection.AT_LEAST) "Достигать минимума" else "Не превышать лимит",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    // One Text style for both actual and goal. Raw overgoal is never truncated.
                    Text("${cell?.let { actualLabel(it.actual, habit.unit) } ?: "—"} / ${cell?.goal?.let { presentedValue(it.amount, habit.unit) } ?: "—"}",
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("actual_goal_${habit.id}"))
                    Text(presentedUnit(habit.unit), style = MaterialTheme.typography.labelLarge)
                }
                EmotionFace(cell?.emotion, if (animate) reveal else 1f)
            }
            Text(cell?.let { coverageLabel(it.actual) } ?: "Нет значения за этот день", style = MaterialTheme.typography.bodySmall)
            cell?.let {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Выполнение: ${scoreLabel(it.score)}", style = MaterialTheme.typography.labelMedium)
                    Text(when (val streak = it.streak) {
                        is Computed.Ready -> "Серия: ${streak.value} дн."
                        is Computed.Unavailable -> "Серия: —"
                    }, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
internal fun DaySelector(day: DayKey, onSelect: (DayKey) -> Unit) {
    var text by rememberSaveable(day.date.toString(), day.zoneId) { mutableStateOf(day.date.toString()) }
    val date = parseDate(text)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { text = it }, label = { Text("Дата · ГГГГ-ММ-ДД") }, singleLine = true,
                isError = date == null, modifier = Modifier.weight(1f).testTag("day_input"))
            TextButton(onClick = { date?.let { onSelect(DayKey(it, day.zoneId)) } }, enabled = date != null,
                modifier = Modifier.testTag("select_day")) { Text("Открыть") }
        }
        Text("Часовой пояс: ${day.zoneId}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun HabitDetails(
    habit: Habit, details: HabitDetailsUiState, busy: Boolean, dispatch: (ChronaAction) -> Unit,
    commit: ValueCommit?, reveal: Float,
) {
    var editActual by rememberSaveable(habit.id, details.cell.day.date.toString()) { mutableStateOf(false) }
    var editGoal by rememberSaveable(habit.id, details.cell.day.date.toString()) { mutableStateOf(false) }
    var archive by remember { mutableStateOf(false) }
    val cell = details.cell
    // Collection-mode guard is mandatory even if a malformed core state says canEditActual=true.
    val manualAllowed = habit.collectionMode == CollectionMode.MANUAL && cell.canEditActual
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(LocalChronaStyle.current.spacing)) {
        item { DaySelector(cell.day) { dispatch(ChronaAction.OpenHabit(habit.id, it)) } }
        item { HabitTile(habit, cell, commit, reveal, onClick = {}) }
        if (manualAllowed) item {
            Button(onClick = { editActual = true }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("edit_actual")) { Text("Записать значение за день") }
        }
        if (habit.collectionMode == CollectionMode.AUTOMATIC_SHORTS) item {
            InfoCard("Факт — только от системы", "Ручной ввод Shorts недоступен. Учитывается открытый экран Shorts, без маленького плавающего окна и предупреждения о лимите. Неизмеренное время не дополняется.")
        }
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("План и оценка", style = MaterialTheme.typography.titleMedium)
                    Text(when (cell.planStatus) {
                        PlanStatus.PLANNED -> "Запланировано на этот день"
                        PlanStatus.NOT_PLANNED -> "Этот день не запланирован"
                        PlanStatus.UNRESOLVED -> "Планирование дня ещё не определено"
                    })
                    Text(when (val usage = cell.usagePercent) {
                        is Computed.Ready -> "Факт относительно плана: ${percent(usage.value)}"
                        is Computed.Unavailable -> usage.reason.label()
                    }, style = MaterialTheme.typography.bodySmall)
                    if (cell.score is Computed.Unavailable) Text(cell.score.reason.label(), style = MaterialTheme.typography.bodySmall)
                    if (cell.emotion is Computed.Unavailable) Text(cell.emotion.reason.label(), style = MaterialTheme.typography.bodySmall)
                    if (cell.streak is Computed.Unavailable) Text(cell.streak.reason.label(), style = MaterialTheme.typography.bodySmall)
                    if (cell.canEditGoal) OutlinedButton(onClick = { editGoal = true }, enabled = !busy,
                        modifier = Modifier.testTag("edit_goal")) { Text("Изменить план / лимит") }
                }
            }
        }
        item { InfoCard("Полнота данных", coverageLabel(cell.actual)) }
        item { IntervalTimeline(details.intervals, details.gaps, cell.day.zoneId) }
        if (habit.collectionMode == CollectionMode.MANUAL) item {
            OutlinedButton(onClick = { archive = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("archive_habit")) { Text("Архивировать привычку") }
        }
    }
    if (editActual && manualAllowed) NumberDialog(
        title = "Значение за ${dateFormat.format(cell.day.date)}", unit = habit.unit,
        initial = (cell.actual as? ActualValue.Known)?.value,
        description = "Абсолютное значение за день. Можно 0 и больше цели; запись не обрезается.",
        allowZero = true, busy = busy, onDismiss = { editActual = false },
    ) { value -> dispatch(ChronaAction.SaveManualValue(habit.id, cell.day, value)); editActual = false }
    if (editGoal && cell.canEditGoal) NumberDialog(
        title = "${if (habit.direction == GoalDirection.AT_MOST) "Лимит" else "Минимум"} · ${shortDateFormat.format(cell.day.date)}",
        unit = habit.unit, initial = cell.goal?.amount,
        description = "Запрос плана от выбранного дня. Применение к истории подтверждает ядро; измеренный факт не меняется.",
        allowZero = habit.direction == GoalDirection.AT_MOST, busy = busy, onDismiss = { editGoal = false },
    ) { value -> dispatch(ChronaAction.SaveGoal(habit.id, value, cell.day)); editGoal = false }
    if (archive) AlertDialog(onDismissRequest = { archive = false }, title = { Text("В архив?") },
        text = { Text("Привычка перестанет быть активной. Это не удаление сохранённой истории.") },
        confirmButton = { TextButton(enabled = !busy, onClick = { dispatch(ChronaAction.ArchiveHabit(habit.id)); archive = false }) { Text("Архивировать") } },
        dismissButton = { TextButton(onClick = { archive = false }) { Text("Отмена") } })
}

@Composable
private fun NumberDialog(
    title: String, unit: ValueUnit, initial: BigDecimal?, description: String,
    allowZero: Boolean, busy: Boolean, onDismiss: () -> Unit, onSave: (BigDecimal) -> Unit,
) {
    val initialText = remember(initial, unit) { initial?.let {
        if (unit.code == "duration_ms") it.divide(BigDecimal(60_000), java.math.MathContext.DECIMAL128).stripTrailingZeros().toPlainString() else it.toPlainString()
    } ?: "" }
    var input by rememberSaveable { mutableStateOf(initialText) }
    val number = parseDayNumber(input)?.takeIf { allowZero || it.signum() > 0 }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(description, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(input, { input = it }, singleLine = true, label = { Text(presentedUnit(unit)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.testTag("number_input"),
                isError = input.isNotEmpty() && number == null, supportingText = { Text(if (allowZero) "Неотрицательное число, запятая или точка" else "Число больше нуля") })
        }
    }, confirmButton = { TextButton(enabled = number != null && !busy, modifier = Modifier.testTag("save_number"), onClick = {
        number?.let {
            // A recurring minute representation must not change an untouched millisecond goal.
            onSave(if (initial != null && input == initialText) initial else if (unit.code == "duration_ms") it.multiply(BigDecimal(60_000)) else it)
        }
    }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable
internal fun CreateHabitDialog(day: DayKey, busy: Boolean, dispatch: (ChronaAction) -> Unit, onDismiss: () -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("раз") }
    var goal by rememberSaveable { mutableStateOf("1") }
    var atMost by rememberSaveable { mutableStateOf(false) }
    val amount = parseDayNumber(goal)?.takeIf { atMost || it.signum() > 0 }
    val valid = title.isNotBlank() && unit.isNotBlank() && amount != null
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Новая ручная привычка") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.testTag("habit_title"))
            OutlinedTextField(unit, { unit = it }, label = { Text("Единицы, например страницы") }, singleLine = true, modifier = Modifier.testTag("habit_unit"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!atMost, { atMost = false }, label = { Text("Минимум") })
                FilterChip(atMost, { atMost = true }, label = { Text("Лимит") })
            }
            OutlinedTextField(goal, { goal = it }, label = { Text(if (atMost) "Не больше" else "Не меньше") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = amount == null, modifier = Modifier.testTag("habit_target"))
            Text("От ${dateFormat.format(day.date)} · ${day.zoneId}. Правила расписания и серий определяет ядро, не эта форма.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(enabled = valid && !busy, modifier = Modifier.testTag("save_habit"), onClick = {
        amount?.let {
            dispatch(ChronaAction.CreateManualHabit(title.trim(), ValueUnit("custom", unit.trim()),
                if (atMost) GoalDirection.AT_MOST else GoalDirection.AT_LEAST, it, day))
            onDismiss()
        }
    }) { Text("Создать") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable
internal fun IntervalTimeline(intervals: List<IntervalView>, gaps: List<ObservationGap>, zoneId: String) {
    val zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneOffset.UTC)
    val time = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss.SSS").withZone(zone)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Интервалы · ${zone.id}", style = MaterialTheme.typography.titleMedium)
        Text("Только чтение. Точность записи времени не гарантирует точность распознавания.", style = MaterialTheme.typography.bodySmall)
        intervals.sortedBy { it.startUtc }.forEach { interval ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${time.format(interval.startUtc)} → ${time.format(interval.endUtc)}", style = MaterialTheme.typography.labelLarge)
                    Text("${BigDecimal(interval.durationMillis).divide(BigDecimal(1000)).number()} с · ${if (interval.quality == IntervalQuality.OBSERVED) "наблюдаемый" else "скачок часов"}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (intervals.isEmpty()) Text("Нет переданных интервалов. Это не доказательство нулевого использования.", style = MaterialTheme.typography.bodySmall)
        Text("Разрывы наблюдения", style = MaterialTheme.typography.titleSmall)
        gaps.sortedBy { it.startUtc }.forEach { gap ->
            InfoCard(gap.reason.label(), "${time.format(gap.startUtc)} → ${gap.endUtc?.let(time::format) ?: "ещё открыт"}")
        }
        if (gaps.isEmpty()) Text("Нет зарегистрированных разрывов. Полнота дня определяется отдельно.", style = MaterialTheme.typography.bodySmall)
    }
}

private fun GapReason.label(): String = when (this) {
    GapReason.UNKNOWN_SCREEN -> "Экран не распознан"; GapReason.PERMISSION_LOST -> "Нет разрешения"
    GapReason.USER_DISABLED -> "Выключено пользователем"; GapReason.SERVICE_UNAVAILABLE -> "Служба недоступна"
    GapReason.PROCESS_DEATH -> "Процесс остановлен"; GapReason.REBOOT -> "Перезагрузка устройства"
    GapReason.CLOCK_CHANGED -> "Изменились часы"; GapReason.UNSUPPORTED_WINDOW -> "Неподдерживаемое окно"
}
