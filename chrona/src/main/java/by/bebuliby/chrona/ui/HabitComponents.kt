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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import by.bebuliby.chrona.domain.*
import java.math.BigDecimal
import java.time.LocalDate

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
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    // One Text style for both actual and goal. Raw overgoal is never truncated.
                    Text(actualGoalLabel(cell, habit.unit),
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("actual_goal_${habit.id}"))
                }
                EmotionFace(cell?.emotion, if (animate) reveal else 1f)
            }
            cell?.let {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (it.score is Computed.Ready) Text("Выполнение: ${scoreLabel(it.score)}", style = MaterialTheme.typography.labelMedium)
                    else if (!youtube && it.actual is ActualValue.Partial) Text("Недостаточно данных", style = MaterialTheme.typography.labelSmall)
                    if (it.streak is Computed.Ready) Text("Серия: ${it.streak.value} дн.", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
internal fun DaySelector(day: DayKey, today: LocalDate? = null, onSelect: (DayKey) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CalendarDateField("Дата", day.date, onSelect = { onSelect(DayKey(it, day.zoneId)) },
            modifier = Modifier.fillMaxWidth().testTag("day_input"))
        if (today != null) OutlinedButton(onClick = { onSelect(DayKey(today, day.zoneId)) },
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary), modifier = Modifier.testTag("today_marker_$today")
                .semantics { selected = day.date == today; contentDescription = "Сегодня, ${dateFormat.format(today)}" }) {
            Text("Сегодня · ${shortDateFormat.format(today)}")
        }
    }
}

@Composable
internal fun HabitDetails(
    habit: Habit, details: HabitDetailsUiState, busy: Boolean, dispatch: (ChronaAction) -> Unit,
    commit: ValueCommit?, reveal: Float, today: LocalDate? = null,
) {
    var editActual by rememberSaveable(habit.id, details.cell.day.date.toString()) { mutableStateOf(false) }
    var editGoal by rememberSaveable(habit.id, details.cell.day.date.toString()) { mutableStateOf(false) }
    var archive by remember { mutableStateOf(false) }
    val cell = details.cell
    // Collection-mode guard is mandatory even if a malformed core state says canEditActual=true.
    val manualAllowed = habit.collectionMode == CollectionMode.MANUAL && cell.canEditActual
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(LocalChronaStyle.current.spacing)) {
        item { DaySelector(cell.day, today) { dispatch(ChronaAction.OpenHabit(habit.id, it)) } }
        item { HabitTile(habit, cell, commit, reveal, onClick = {}) }
        if (manualAllowed) item {
            Button(onClick = { editActual = true }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("edit_actual")) { Text("Записать значение за день") }
        }
        if (cell.canEditGoal) item {
            OutlinedButton(onClick = { editGoal = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("edit_goal")) {
                Text(if (habit.direction == GoalDirection.AT_MOST) "Изменить лимит" else "Изменить цель")
            }
        }
        if (cell.usagePercent is Computed.Ready) item { Text("Факт / план: ${percent(cell.usagePercent.value)}", style = MaterialTheme.typography.bodyMedium) }
        if (habit.collectionMode == CollectionMode.AUTOMATIC_SHORTS || details.intervals.isNotEmpty()) item {
            IntervalTimeline(details.intervals, cell.day.zoneId, cell.day.date)
        }
        if (habit.collectionMode == CollectionMode.MANUAL) item {
            OutlinedButton(onClick = { archive = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("archive_habit")) { Text("Архивировать привычку") }
        }
    }
    if (editActual && manualAllowed) NumberDialog(
        title = "Значение за ${dateFormat.format(cell.day.date)}", unit = habit.unit,
        initial = (cell.actual as? ActualValue.Known)?.value,
        allowZero = true, busy = busy, onDismiss = { editActual = false },
    ) { value -> dispatch(ChronaAction.SaveManualValue(habit.id, cell.day, value)); editActual = false }
    if (editGoal && cell.canEditGoal) NumberDialog(
        title = if (habit.collectionMode == CollectionMode.AUTOMATIC_SHORTS) "Лимит с ${shortDateFormat.format(cell.day.date)}" else "${if (habit.direction == GoalDirection.AT_MOST) "Лимит" else "Минимум"} · ${shortDateFormat.format(cell.day.date)}",
        unit = habit.unit, initial = cell.goal?.amount,
        allowZero = habit.direction == GoalDirection.AT_MOST, busy = busy, onDismiss = { editGoal = false },
    ) { value -> dispatch(ChronaAction.SaveGoal(habit.id, value, cell.day)); editGoal = false }
    if (archive) AlertDialog(onDismissRequest = { archive = false }, title = { Text("В архив?") },
        text = { Text("История сохранится.") },
        confirmButton = { TextButton(enabled = !busy, onClick = { dispatch(ChronaAction.ArchiveHabit(habit.id)); archive = false }) { Text("Архивировать") } },
        dismissButton = { TextButton(onClick = { archive = false }) { Text("Отмена") } })
}

@Composable
private fun NumberDialog(
    title: String, unit: ValueUnit, initial: BigDecimal?,
    allowZero: Boolean, busy: Boolean, onDismiss: () -> Unit, onSave: (BigDecimal) -> Unit,
) {
    val initialText = remember(initial, unit) { numberEditorText(initial, unit) }
    var input by rememberSaveable { mutableStateOf(initialText) }
    val number = parseDayNumber(input)?.takeIf { allowZero || it.signum() > 0 }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(input, { input = it }, singleLine = true, label = { Text(if (unit.code == "duration_ms") "Минуты" else presentedUnit(unit)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.testTag("number_input"),
                isError = input.isNotEmpty() && number == null, supportingText = {
                    if (input.isNotEmpty() && number == null) Text(if (allowZero) "Введите число от 0" else "Введите число больше 0")
                })
        }
    }, confirmButton = { TextButton(enabled = number != null && !busy, modifier = Modifier.testTag("save_number"), onClick = {
        number?.let {
            // A recurring minute representation must not change an untouched millisecond goal.
            numberEditorValue(input, initial, unit)?.let(onSave)
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
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Новая привычка") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.testTag("habit_title"))
            OutlinedTextField(unit, { unit = it }, label = { Text("Единицы, например страницы") }, singleLine = true, modifier = Modifier.testTag("habit_unit"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!atMost, { atMost = false }, label = { Text("Минимум") })
                FilterChip(atMost, { atMost = true }, label = { Text("Лимит") })
            }
            OutlinedTextField(goal, { goal = it }, label = { Text(if (atMost) "Не больше" else "Не меньше") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = amount == null, modifier = Modifier.testTag("habit_target"))
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
internal fun IntervalTimeline(intervals: List<IntervalView>, zoneId: String, day: LocalDate? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Интервалы", style = MaterialTheme.typography.titleMedium)
        intervals.sortedBy { it.startUtc }.forEach { interval ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(intervalTimeLabel(interval.startUtc, interval.endUtc, zoneId, day), style = MaterialTheme.typography.labelLarge)
                    Text(durationLabel(BigDecimal(interval.durationMillis)), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (intervals.isEmpty()) Text("Нет интервалов", style = MaterialTheme.typography.bodySmall)
    }
}
