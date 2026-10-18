package by.bebuliby.chrona.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import by.bebuliby.chrona.domain.*

/** Production entry point. All facts, permissions and derived business values come from core. */
@Composable
fun ChronaApp(state: ChronaUiState, dispatch: (ChronaAction) -> Unit) {
    val photoPolicy = LocalChronaRemotePhotoPolicy.current
    val photoCache = remember(photoPolicy, state.profile.localProfileId, state.profile.accountId, state.profile.telegramLinked) { RemotePhotoCache() }
    DisposableEffect(photoCache) { onDispose { photoCache.close() } }
    CompositionLocalProvider(LocalRemotePhotoCache provides photoCache) {
        ChronaTheme(state.preferences) { ChronaContent(state, dispatch) }
    }
}

/** Compatibility for the old detached-copy MainActivity ONLY; never seeds a production state. */
@Deprecated("DEMO ONLY. Production hosts must call ChronaApp(state, dispatch).")
@Composable
fun ChronaApp() = by.bebuliby.chrona.preview.ChronaDemoApp()

internal fun MainTab.title(): String = when (this) {
    MainTab.TODAY -> "Сегодня"; MainTab.HISTORY -> "История"
    MainTab.HABITS -> "Привычки"; MainTab.FRIENDS -> "Друзья"
}
private fun MainTab.icon(): ImageVector = when (this) {
    MainTab.TODAY -> Icons.Outlined.Home
    MainTab.HISTORY -> Icons.Outlined.DateRange
    MainTab.HABITS -> Icons.Outlined.CheckCircle
    MainTab.FRIENDS -> Icons.Outlined.Person
}

@Composable
private fun ChronaContent(state: ChronaUiState, dispatch: (ChronaAction) -> Unit) {
    var profileMenu by remember { mutableStateOf(false) }
    var addHabit by rememberSaveable { mutableStateOf(false) }
    var lastAnimatedId by rememberSaveable { mutableStateOf<String?>(null) }
    var activeCommit by remember { mutableStateOf<ValueCommit?>(null) }
    val reveal = remember { Animatable(1f) }
    val reducedMotion = rememberReducedMotion()
    val currentDispatch by rememberUpdatedState(dispatch)
    val event = state.valueCommit
    LaunchedEffect(event?.eventId) {
        if (event == null) return@LaunchedEffect
        if (lastAnimatedId == event.eventId) {
            currentDispatch(ChronaAction.AcknowledgeValueAnimation(event.eventId))
            return@LaunchedEffect
        }
        lastAnimatedId = event.eventId
        activeCommit = event
        try {
            if (!reducedMotion) {
                reveal.snapTo(0f)
                reveal.animateTo(1f, tween(850))
            } else reveal.snapTo(1f)
        } finally {
            activeCommit = null
            currentDispatch(ChronaAction.AcknowledgeValueAnimation(event.eventId))
        }
    }
    val progress = if (reducedMotion) 1f else reveal.value
    val detailHabit = state.details?.let { details -> state.habits.find { it.id == details.habitId } }
    BackHandler(state.settingsOpen || state.details != null || state.friends.selected != null || state.tab != MainTab.TODAY) {
        when {
            state.settingsOpen -> dispatch(ChronaAction.CloseSettings)
            state.details != null -> dispatch(ChronaAction.CloseDetails)
            state.friends.selected != null -> dispatch(ChronaAction.CloseFriend)
            else -> dispatch(ChronaAction.Navigate(MainTab.TODAY))
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.settingsOpen || state.details != null) IconButton(onClick = {
                    dispatch(if (state.settingsOpen) ChronaAction.CloseSettings else ChronaAction.CloseDetails)
                }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад") }
                Column(Modifier.weight(1f)) {
                    Text("Chrona", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    Text(
                        when { state.settingsOpen -> "Настройки"; state.details != null -> "За день"; else -> state.tab.title() },
                        style = if (state.settingsOpen) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("screen_title"),
                    )
                }
                Box {
                    Row(
                        Modifier.widthIn(max = if (state.settingsOpen || state.details != null) 96.dp else 160.dp)
                            .clickable { profileMenu = true }.padding(6.dp).testTag("profile_menu"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        ProfileAvatar(state.profile.displayName, state.profile.photo, state.profile.telegramLinked)
                        Text(state.profile.displayName, Modifier.weight(1f, fill = false), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                        Text("⌄", style = MaterialTheme.typography.titleMedium)
                    }
                    DropdownMenu(expanded = profileMenu, onDismissRequest = { profileMenu = false }) {
                        DropdownMenuItem(text = { Text("Настройки") }, onClick = {
                            profileMenu = false; dispatch(ChronaAction.OpenSettings)
                        }, leadingIcon = { Icon(Icons.Outlined.Settings, null) })
                        DropdownMenuItem(text = { Text("Выбрать фото") }, onClick = {
                            profileMenu = false; dispatch(ChronaAction.ChooseProfilePhoto)
                        }, leadingIcon = { Icon(Icons.Outlined.Person, null) })
                    }
                }
            }
        },
        bottomBar = {
            if (!state.settingsOpen && state.details == null) NavigationBar {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = state.tab == tab, onClick = { dispatch(ChronaAction.Navigate(tab)) },
                        icon = { Icon(tab.icon(), null) }, label = { Text(tab.title()) },
                        modifier = Modifier.testTag("tab_${tab.name}"),
                    )
                }
            }
        },
        floatingActionButton = {
            if (state.tab == MainTab.HABITS && state.details == null && !state.settingsOpen) {
                ExtendedFloatingActionButton(onClick = { addHabit = true }, modifier = Modifier.testTag("add_habit"),
                    icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Новая привычка") })
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.mode == DataMode.DEMO) Surface(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("demo_banner"),
                color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small,
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Демоданные", style = MaterialTheme.typography.labelLarge)
                }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("busy_indicator"))
            state.notice?.let { notice ->
                userNoticeMessage(notice)?.let { message ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f).testTag("ui_notice"), style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = { dispatch(ChronaAction.DismissNotice(notice.id)) }) { Icon(Icons.Outlined.Close, "Закрыть сообщение") }
                    }
                }
                }
            }
            Box(Modifier.weight(1f)) {
                when {
                    state.settingsOpen -> SettingsPage(state, dispatch)
                    state.details != null && detailHabit != null -> HabitDetails(
                        detailHabit, state.details, state.busy, dispatch, activeCommit, progress, today = state.today.date,
                    )
                    state.details != null -> InfoCard("Нет данных")
                    state.tab == MainTab.TODAY -> TodayPage(state, dispatch, activeCommit, progress)
                    state.tab == MainTab.HISTORY -> HistoryPage(state.habits, state.history, dispatch,
                        today = state.today.date, selectedDate = state.selectedDay.date)
                    state.tab == MainTab.HABITS -> HabitsPage(state, dispatch, activeCommit, progress)
                    else -> FriendsPage(state, dispatch)
                }
            }
        }
    }
    if (addHabit) CreateHabitDialog(state.selectedDay, state.busy, dispatch, onDismiss = { addHabit = false })
}

@Composable
private fun TodayPage(state: ChronaUiState, dispatch: (ChronaAction) -> Unit, commit: ValueCommit?, progress: Float) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(LocalChronaStyle.current.spacing)) {
        item { DaySelector(state.selectedDay, state.today.date) { dispatch(ChronaAction.SelectDay(it)) } }
        if (state.tracker.status in setOf(TrackerStatus.PERMISSION_REQUIRED, TrackerStatus.UNAVAILABLE, TrackerStatus.NOT_CONNECTED)) item { TrackingCard(state.tracker, dispatch) }
        if (state.habits.isEmpty()) item { InfoCard("Пока нет привычек", message = "Добавьте привычку во вкладке «Привычки».") }
        items(state.habits.filter { it.archivedAt == null }, key = { it.id }) { habit ->
            val cell = state.todayCells.find { it.habitId == habit.id && it.day == state.selectedDay }
            HabitTile(habit, cell, commit, progress) {
                dispatch(ChronaAction.OpenHabit(habit.id, state.selectedDay))
            }
        }
    }
}

@Composable
private fun HabitsPage(state: ChronaUiState, dispatch: (ChronaAction) -> Unit, commit: ValueCommit?, progress: Float) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(LocalChronaStyle.current.spacing)) {
        items(state.habits.filter { it.archivedAt == null }, key = { it.id }) { habit ->
            HabitTile(habit, state.todayCells.find { it.habitId == habit.id && it.day == state.selectedDay }, commit, progress) {
                dispatch(ChronaAction.OpenHabit(habit.id, state.selectedDay))
            }
        }
        if (state.habits.none { it.archivedAt == null }) item { InfoCard("Пока нет привычек") }
        item { Spacer(Modifier.height(90.dp)) }
    }
}

@Composable
internal fun InfoCard(title: String, modifier: Modifier = Modifier, message: String = "") {
    Card(modifier.fillMaxWidth(), border = if (LocalChronaStyle.current.outlined) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null) {
        Column(Modifier.padding(LocalChronaStyle.current.spacing), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
