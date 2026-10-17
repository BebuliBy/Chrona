package by.bebuliby.chrona.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import by.bebuliby.chrona.preview.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private val dateFormat = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
fun ChronaApp(model: DemoViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    ChronaTheme(state.theme, state.palette) { ChronaScreen(state, model::dispatch) }
}

@Composable
fun ChronaScreen(state: DemoState, dispatch: (DemoAction) -> Unit) {
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    val selectedHabit = state.habits.find { "habit:${it.id}" == detail }
    val detailsTitle = if (detail == "shorts") "YouTube Shorts" else selectedHabit?.title
    BackHandler(detail != null || state.page != Page.TODAY) {
        if (detail != null) detail = null else dispatch(DemoAction.Navigate(Page.TODAY))
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (detail != null) IconButton(onClick = { detail = null }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад")
                    } else Box(
                        Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text("C", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Chrona", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(detailsTitle ?: state.page.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    }
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text("Локально", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        },
        bottomBar = {
            if (detail == null) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Page.entries.forEach { page ->
                    NavigationBarItem(
                        modifier = Modifier.testTag("tab_${page.name}"),
                        selected = state.page == page,
                        onClick = { dispatch(DemoAction.Navigate(page)) },
                        icon = { Icon(page.icon(), contentDescription = null) },
                        label = { Text(page.title, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (state.page == Page.HABITS && detail == null) ExtendedFloatingActionButton(
                onClick = { showAdd = true }, modifier = Modifier.testTag("add_habit"),
                icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Новая привычка") },
            )
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag("demo_banner"),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text("Дизайн-превью · демоданные", style = MaterialTheme.typography.labelLarge)
                    Text("Не ваша статистика. Изменения только в памяти.", style = MaterialTheme.typography.bodySmall)
                }
            }
            when {
                detail == "shorts" -> ShortsDetails(state)
                selectedHabit != null -> HabitDetails(selectedHabit, dispatch, onDelete = {
                    dispatch(DemoAction.DeleteHabit(selectedHabit.id)); detail = null
                })
                else -> when (state.page) {
                    Page.TODAY -> TodayPage(state, dispatch, onDetails = { detail = it }, onAdd = { showAdd = true })
                    Page.HABITS -> HabitsPage(state, dispatch, onDetails = { detail = "habit:$it" })
                    Page.HISTORY -> HistoryPage(state, dispatch)
                    Page.SETTINGS -> SettingsPage(state, dispatch)
                }
            }
        }
    }
    if (showAdd) AddHabitDialog(
        onDismiss = { showAdd = false },
        onSave = { title, target, unit ->
            dispatch(DemoAction.AddHabit(UUID.randomUUID().toString(), title, target, unit))
            showAdd = false
        },
    )
}

private fun Page.icon(): ImageVector = when (this) {
    Page.TODAY -> Icons.Outlined.Home
    Page.HABITS -> Icons.Outlined.CheckCircle
    Page.HISTORY -> Icons.Outlined.DateRange
    Page.SETTINGS -> Icons.Outlined.Settings
}

@Composable
private fun TodayPage(state: DemoState, dispatch: (DemoAction) -> Unit, onDetails: (String) -> Unit, onAdd: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text(dateFormat.format(state.today), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Ваш день — в одном месте", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        item { TrackingNotice() }
        item { ShortsCard(state, onClick = { onDetails("shorts") }) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallStat("${state.completedCount}/${state.habits.size}", "привычек выполнено", Modifier.weight(1f))
                SmallStat(state.intervals.size.toString(), "интервалов · пример", Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Привычки на сегодня", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onAdd) { Text("Добавить") }
            }
        }
        if (state.habits.isEmpty()) item { EmptyCard("Пока нет привычек", "Добавьте пробную привычку и посмотрите, удобно ли ей управлять.") }
        items(state.habits, key = { it.id }) { habit ->
            HabitCard(habit, dispatch, onDetails = { onDetails("habit:${habit.id}") })
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun TrackingNotice() {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column {
                Text("Отслеживание ещё не подключено", style = MaterialTheme.typography.titleSmall)
                Text("Эта сборка нужна для выбора интерфейса. Она не наблюдает YouTube.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ShortsCard(state: DemoState, onClick: () -> Unit) {
    Card(onClick = onClick, shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("YouTube Shorts", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text("АВТО · ПРИМЕР", style = MaterialTheme.typography.labelSmall)
            }
            Text(durationLabel(state.totalSeconds), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Демонстрационное время за день, не ваши данные", style = MaterialTheme.typography.bodySmall)
            Text("Посмотреть интервалы →", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SmallStat(value: String, label: String, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HabitCard(habit: DemoHabit, dispatch: (DemoAction) -> Unit, onDetails: () -> Unit) {
    Card(
        onClick = onDetails, modifier = Modifier.fillMaxWidth().testTag("habit_${habit.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(habit.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Пример цели: ${habit.target} ${habit.unit} в день", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Checkbox(
                    checked = habit.completed,
                    onCheckedChange = { dispatch(DemoAction.ToggleCompleted(habit.id)) },
                    modifier = Modifier.semantics { contentDescription = "Отметить цель: ${habit.title}" },
                )
            }
            LinearProgressIndicator(progress = { habit.fraction }, modifier = Modifier.fillMaxWidth().height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${habit.progress}/${habit.target} ${habit.unit}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                IconButton(
                    onClick = { dispatch(DemoAction.ChangeProgress(habit.id, -1)) }, enabled = habit.progress > 0,
                ) { Text("−", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { contentDescription = "Уменьшить: ${habit.title}" }) }
                IconButton(
                    onClick = { dispatch(DemoAction.ChangeProgress(habit.id, 1)) }, enabled = !habit.completed,
                ) { Icon(Icons.Outlined.Add, "Добавить отметку: ${habit.title}") }
            }
            Text("Серия: ${habit.exampleStreak} дн. · условный пример", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun HabitsPage(state: DemoState, dispatch: (DemoAction) -> Unit, onDetails: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Небольшие шаги, заметный прогресс", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Попробуйте отметки, счётчик и создание. Это демо, история ещё не сохраняется.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.habits.isEmpty()) item { EmptyCard("Начните с одной привычки", "Кнопка «Новая привычка» откроет пробную форму.") }
        items(state.habits, key = { it.id }) { HabitCard(it, dispatch, onDetails = { onDetails(it.id) }) }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun HistoryPage(state: DemoState, dispatch: (DemoAction) -> Unit) {
    var filter by rememberSaveable { mutableStateOf("Всё") }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { dispatch(DemoAction.SelectDate(state.selectedDate.minusDays(1))) }, enabled = state.selectedDate > state.today.minusDays(6)) {
                    Text("‹", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { contentDescription = "Предыдущий день" })
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(dateFormat.format(state.selectedDate), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("Условный пример выбранного дня", style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { dispatch(DemoAction.SelectDate(state.selectedDate.plusDays(1))) }, enabled = state.selectedDate < state.today) {
                    Text("›", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { contentDescription = "Следующий день" })
                }
            }
        }
        item { WeekExample(hasData = state.intervals.isNotEmpty()) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Всё", "Shorts", "Привычки").forEach { value ->
                    FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(value) })
                }
            }
        }
        if (state.intervals.isEmpty() && state.habits.isEmpty()) item { EmptyCard("История пока пуста", "В реальной версии здесь будут только сохранённые события.") }
        if (filter != "Привычки") items(state.intervals) { IntervalRow(it) }
        if (filter != "Shorts") items(state.habits.filter { it.completed }, key = { "done-${it.id}" }) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text(it.title, style = MaterialTheme.typography.titleMedium)
                    Text("Цель отмечена · пример", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item { Text("Точные интервалы, а не названия роликов. Неполнота наблюдения в реальной версии будет отмечена отдельно.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun WeekExample(hasData: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text("Неделя · пример", style = MaterialTheme.typography.titleMedium)
            Text("Shorts, минут в день", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().height(130.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                val values = if (hasData) listOf(18, 32, 14, 26, 9, 41, 24) else List(7) { 0 }
                values.forEachIndexed { index, value ->
                    Column(Modifier.weight(1f).semantics { contentDescription = "Демо-день ${index + 1}: $value минут" }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(value.toString(), style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(5.dp))
                        Box(Modifier.width(22.dp).height(maxOf(4, value * 2).dp).background(
                            if (index == 6) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(7.dp),
                        ))
                        Spacer(Modifier.height(6.dp))
                        Text(listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")[index], style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun IntervalRow(interval: DemoInterval) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Shorts · пример", style = MaterialTheme.typography.titleSmall)
                Text("${timeFormat.format(interval.start)} — ${timeFormat.format(interval.end)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(durationLabel(interval.seconds), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ShortsDetails(state: DemoState) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ShortsCard(state, onClick = {}) }
        item { TrackingNotice() }
        item { InfoCard("Что входит в учёт", "Открытый экран Shorts. Маленькое плавающее окно и время предупреждения о лимите исключены по вашему решению.") }
        item { Text("Интервалы · демонстрационный день", style = MaterialTheme.typography.titleMedium) }
        items(state.intervals) { IntervalRow(it) }
        if (state.intervals.isEmpty()) item { EmptyCard("Нет интервалов", "Демонстрационные данные очищены.") }
    }
}

@Composable
private fun HabitDetails(habit: DemoHabit, dispatch: (DemoAction) -> Unit, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { HabitCard(habit, dispatch, onDetails = {}) }
        item { InfoCard("Серия дней — пример", "Показанные ${habit.exampleStreak} дн. условны. Правила периодичности, пропусков и прошлых отметок ещё согласуем; настоящий расчёт серии пока не подключён.") }
        item { OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Удалить из демо") } }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, title = { Text("Удалить пробную привычку?") },
        text = { Text("Это затронет только демоданные этой сессии, не настоящую историю.") },
        confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } },
    )
}

@Composable
private fun SettingsPage(state: DemoState, dispatch: (DemoAction) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { InfoCard("Сейчас — выбор интерфейса", "Отметки и настройки живут только в памяти. При новом запуске процесса демо сбрасывается. Реальные БД, отслеживание и обновления с сохранением данных подключаются следующим этапом.") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Оформление", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = state.theme == mode, onClick = { dispatch(DemoAction.ChangeTheme(mode)) },
                                modifier = Modifier.testTag("theme_${mode.name}"),
                                label = { Text(when (mode) { ThemeMode.SYSTEM -> "Система"; ThemeMode.LIGHT -> "Светлая"; ThemeMode.DARK -> "Тёмная" }) },
                            )
                        }
                    }
                    Text("Две палитры для сравнения", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Palette.entries.forEach { palette ->
                            FilterChip(selected = state.palette == palette, onClick = { dispatch(DemoAction.ChangePalette(palette)) },
                                label = { Text(if (palette == Palette.OCEAN) "Океан" else "Лаванда") })
                        }
                    }
                }
            }
        }
        item { TrackingNotice() }
        item { InfoCard("Напоминания и резервная копия", "Эти функции здесь не имитируются. Сначала согласуем поведение и защиту данных; сервер и Telegram в этой сборке не используются.") }
        item {
            OutlinedButton(onClick = { dispatch(DemoAction.ClearExamples) }, modifier = Modifier.fillMaxWidth().testTag("clear_demo")) { Text("Показать пустое состояние") }
            TextButton(onClick = { dispatch(DemoAction.ResetExamples) }, modifier = Modifier.fillMaxWidth()) { Text("Восстановить демопримеры") }
        }
        item { Text("Chrona · UI-чекпоинт 0.2\nДизайн и бизнес-правила ещё ждут вашего подтверждения.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun InfoCard(title: String, message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyCard(title: String, message: String) = InfoCard(title, message)

@Composable
private fun AddHabitDialog(onDismiss: () -> Unit, onSave: (String, Int, String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("1") }
    var unit by rememberSaveable { mutableStateOf("раз") }
    val valid = DemoReducer.validHabit(title, target.toIntOrNull(), unit)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая привычка · демо") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Пробная форма. Сохранится только в памяти этой сессии.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Название") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("habit_title"), isError = title.length > 60)
                OutlinedTextField(value = target, onValueChange = { target = it }, label = { Text("Пример цели на день") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("habit_target"), isError = target.toIntOrNull()?.let { it !in 1..9999 } ?: true)
                OutlinedTextField(value = unit, onValueChange = { unit = it }, label = { Text("Единица: раз, минут, страниц") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("habit_unit"))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(title, target.toInt(), unit) }, enabled = valid, modifier = Modifier.testTag("save_habit")) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Preview(name = "Chrona · light", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun TodayLightPreview() {
    ChronaTheme(ThemeMode.LIGHT, Palette.OCEAN) { ChronaScreen(DemoState.sample(LocalDate.of(2026, 10, 18)), {}) }
}

@Preview(name = "Chrona · dark", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun TodayDarkPreview() {
    ChronaTheme(ThemeMode.DARK, Palette.LILAC) { ChronaScreen(DemoState.sample(LocalDate.of(2026, 10, 18)).copy(theme = ThemeMode.DARK), {}) }
}
