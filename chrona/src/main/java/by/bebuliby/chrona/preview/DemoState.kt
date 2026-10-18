package by.bebuliby.chrona.preview

import by.bebuliby.chrona.domain.*
import by.bebuliby.chrona.ui.normalizedPublicServerBaseUrl
import by.bebuliby.chrona.ui.serverOrigin
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Explicit fixtures. Production ChronaApp never calls this factory or this reducer. */
object DemoState {
    fun sample(today: LocalDate = LocalDate.of(2026, 10, 18)): ChronaUiState {
        val day = DayKey(today, "Europe/Minsk")
        val now = Instant.parse("2026-10-18T09:00:00Z")
        val habits = listOf(
            Habit("shorts", "YouTube Shorts", ValueUnit("duration_ms", "мс"), GoalDirection.AT_MOST, CollectionMode.AUTOMATIC_SHORTS, now),
            Habit("reading", "Читать", ValueUnit("pages", "страниц"), GoalDirection.AT_LEAST, CollectionMode.MANUAL, now),
            Habit("walk", "Прогулка", ValueUnit("minutes", "мин"), GoalDirection.AT_LEAST, CollectionMode.MANUAL, now),
            Habit("english", "Английский", ValueUnit("minutes", "мин"), GoalDirection.AT_LEAST, CollectionMode.MANUAL, now),
        )
        val cells = habits.map { habit ->
            val goal = if (habit.id == "shorts") BigDecimal(1_200_000) else BigDecimal(20)
            val actual = when (habit.id) {
                "shorts" -> ActualValue.Partial(BigDecimal(1_476_000), FactSource.COLLECTOR)
                "reading" -> ActualValue.Known(BigDecimal(12), FactSource.MANUAL)
                "walk" -> ActualValue.Known(BigDecimal(40), FactSource.MANUAL)
                else -> ActualValue.Unknown(UnavailableReason.UNKNOWN_ACTUAL)
            }
            fixtureCell(habit, day, goal, actual)
        }
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val range = DayRange(monday, monday.plusDays(7), day.zoneId)
        val historyCells = (0L..6L).flatMap { offset ->
            habits.map { habit ->
                val source = cells.first { it.habitId == habit.id }
                val actual = when (habit.id) {
                    "reading" -> ActualValue.Known(BigDecimal(40), FactSource.MANUAL)
                    "walk" -> ActualValue.Known(BigDecimal(listOf(0, 5, 10, 15, 20, 30, 40)[offset.toInt()]), FactSource.MANUAL)
                    else -> source.actual
                }
                fixtureCell(habit, DayKey(monday.plusDays(offset), day.zoneId), source.goal!!.amount, actual)
            }
        }
        val readingCurve = CumulativeCurve("reading", habits[1].unit, (0L..6L).map {
            CurvePoint(DayKey(monday.plusDays(it), day.zoneId), Computed.Ready(BigDecimal((it + 1) * 20)))
        })
        val summary = PeriodSummary(28, 14, Computed.Unavailable(UnavailableReason.PARTIAL_COVERAGE), listOf(readingCurve))
        return ChronaUiState(
            DataMode.DEMO, day, day, MainTab.TODAY,
            ProfileView("demo-profile", null, "Демо-профиль", null, false),
            AppPreferences(ThemeMode.SYSTEM, "ocean", "standard"), habits, cells,
            HistoryUiState(HistoryPeriod.WEEK, range, historyCells, summary), null,
            FriendsUiState(FeatureStatus.NOT_CONNECTED), TrackerHealth(TrackerStatus.NOT_CONNECTED, null, null),
            SyncState(SyncStatus.LOCAL_ONLY, null, 0),
        )
    }
}

private fun unknown(reason: UnavailableReason): Computed.Unavailable = Computed.Unavailable(reason)

/** Demo-only calculator so input previews are interactive, not a production scoring implementation. */
private fun fixtureCell(habit: Habit, day: DayKey, goal: BigDecimal, actual: ActualValue): DayCell {
    val a = (actual as? ActualValue.Known)?.value
    val reason = if (actual is ActualValue.Partial) UnavailableReason.PARTIAL_COVERAGE else UnavailableReason.UNKNOWN_ACTUAL
    val ratio = if (a != null && goal.signum() > 0) a.divide(goal, MathContext.DECIMAL128) else null
    val score: Computed<BigDecimal> = when {
        a == null -> unknown(reason)
        goal.signum() == 0 -> Computed.Ready(if (a.signum() == 0) BigDecimal.ONE else BigDecimal.ZERO)
        habit.direction == GoalDirection.AT_LEAST -> Computed.Ready(ratio!!.coerceIn(BigDecimal.ZERO, BigDecimal.ONE))
        a <= goal -> Computed.Ready(BigDecimal.ONE)
        else -> Computed.Ready(BigDecimal(2).subtract(ratio).coerceIn(BigDecimal.ZERO, BigDecimal.ONE))
    }
    val mood: Computed<Emotion> = when {
        a == null -> unknown(reason)
        goal.signum() == 0 -> unknown(UnavailableReason.ZERO_LIMIT_EMOTION_UNRESOLVED)
        habit.direction == GoalDirection.AT_LEAST -> Computed.Ready(when {
            a.multiply(BigDecimal(4)) < goal -> Emotion.TEARS
            a.multiply(BigDecimal(2)) < goal -> Emotion.VERY_SAD
            a.multiply(BigDecimal(4)) < goal.multiply(BigDecimal(3)) -> Emotion.SAD
            a < goal -> Emotion.NEUTRAL
            a.multiply(BigDecimal(2)) < goal.multiply(BigDecimal(3)) -> Emotion.SMILE
            a < goal.multiply(BigDecimal(2)) -> Emotion.BIG_SMILE
            else -> Emotion.STARS
        })
        else -> Computed.Ready(when {
            a.signum() == 0 -> Emotion.STARS
            a.multiply(BigDecimal(2)) <= goal -> Emotion.BIG_SMILE
            a <= goal -> Emotion.SMILE
            a.multiply(BigDecimal(2)) <= goal.multiply(BigDecimal(3)) -> Emotion.NEUTRAL
            a <= goal.multiply(BigDecimal(2)) -> Emotion.SAD
            a <= goal.multiply(BigDecimal(4)) -> Emotion.VERY_SAD
            else -> Emotion.TEARS
        })
    }
    return DayCell(habit.id, day, PlanStatus.PLANNED,
        GoalRevision("demo-goal-${habit.id}-${day.date}", habit.id, goal, day, habit.createdAt), actual,
        ratio?.let { Computed.Ready(it.multiply(BigDecimal(100))) } ?: unknown(if (a == null) reason else UnavailableReason.ZERO_LIMIT_RATIO),
        score, mood, unknown(UnavailableReason.STREAK_RULES_UNRESOLVED), habit.collectionMode == CollectionMode.MANUAL, true)
}

/** Handles only DEMO states. Never used to turn LOCAL into seeded data or simulate auth/friends. */
object DemoReducer {
    fun reduce(state: ChronaUiState, action: ChronaAction): ChronaUiState {
        if (state.mode != DataMode.DEMO) return state
        return when (action) {
            is ChronaAction.Navigate -> state.copy(tab = action.tab, details = null, settingsOpen = false)
            is ChronaAction.ChangePreferences -> state.copy(preferences = action.preferences)
            is ChronaAction.ConfigureDiscoverability -> state.copy(connection = state.connection.copy(discoverable = action.enabled),
                notice = UiNotice(UUID.randomUUID().toString(),"DEMO_ONLY","Демо: обнаруживаемость изменена только в памяти. Согласие на сервер не отправлено."))
            ChronaAction.OpenSettings -> state.copy(settingsOpen = true)
            ChronaAction.CloseSettings -> state.copy(settingsOpen = false)
            ChronaAction.CloseDetails -> state.copy(details = null)
            is ChronaAction.ConfigureServer -> {
                val url = normalizedPublicServerBaseUrl(action.baseUrl)
                    ?: return state.copy(notice = UiNotice(UUID.randomUUID().toString(), "VALIDATION", "Демо: нужен публичный HTTPS адрес без паролей и токенов."))
                val changedOrigin = serverOrigin(state.connection.baseUrl) != serverOrigin(url)
                state.copy(connection = state.connection.copy(baseUrl = url, loginPending = if (changedOrigin) false else state.connection.loginPending,
                    discoverable = if (changedOrigin) false else state.connection.discoverable),
                    profile = if (!changedOrigin) state.profile else state.profile.copy(accountId = null, telegramLinked = false,
                        photo = state.profile.photo?.takeIf { it.kind == PhotoKind.LOCAL_URI }),
                    friends = if (changedOrigin) FriendsUiState(FeatureStatus.NOT_CONNECTED) else state.friends,
                    sync = if (changedOrigin) state.sync.copy(status = SyncStatus.LOCAL_ONLY, lastSuccessAt = null) else state.sync,
                    notice = UiNotice(UUID.randomUUID().toString(), "DEMO_ONLY", "Демо: адрес изменён только в памяти. HTTP, вход и синхронизация не выполнялись."))
            }
            is ChronaAction.SelectDay -> state.copy(selectedDay = action.day)
            is ChronaAction.SelectHistory -> state.copy(history = state.history.copy(period = action.period, range = action.range,
                cells = state.history.cells.filter { it.day.date >= action.range.fromInclusive && it.day.date < action.range.toExclusive },
                summary = PeriodSummary(0, 0, unknown(UnavailableReason.NOT_IMPLEMENTED), emptyList())))
            is ChronaAction.OpenHabit -> {
                val habit = state.habits.find { it.id == action.habitId } ?: return state
                val cell = (state.todayCells + state.history.cells).find { it.habitId == habit.id && it.day == action.day }
                    ?: fixtureCell(habit, action.day, state.todayCells.find { it.habitId == habit.id }?.goal?.amount ?: BigDecimal.ONE,
                        ActualValue.Unknown(UnavailableReason.UNKNOWN_ACTUAL))
                state.copy(details = HabitDetailsUiState(habit.id, cell, emptyList(), emptyList()))
            }
            is ChronaAction.SaveManualValue -> {
                val habit = state.habits.find { it.id == action.habitId } ?: return state
                if (habit.collectionMode != CollectionMode.MANUAL || action.value.signum() < 0) return state
                val old = state.details?.cell?.takeIf { it.habitId == habit.id && it.day == action.day }
                    ?: state.todayCells.find { it.habitId == habit.id && it.day == action.day } ?: return state
                if (!old.canEditActual) return state
                replaceCell(state, fixtureCell(habit, action.day, old.goal?.amount ?: BigDecimal.ONE, ActualValue.Known(action.value, FactSource.MANUAL)))
                    .copy(valueCommit = ValueCommit(UUID.randomUUID().toString(), habit.id, action.day))
            }
            is ChronaAction.SaveGoal -> {
                val habit = state.habits.find { it.id == action.habitId } ?: return state
                val cell = state.details?.cell?.takeIf { it.habitId == habit.id && it.day == action.requestedFrom } ?: return state
                if (!cell.canEditGoal || action.amount.signum() < 0 || (habit.direction == GoalDirection.AT_LEAST && action.amount.signum() == 0)) return state
                replaceCell(state, fixtureCell(habit, cell.day, action.amount, cell.actual))
            }
            is ChronaAction.CreateManualHabit -> {
                if (action.title.isBlank() || action.unit.label.isBlank() || action.initialGoal.signum() < 0 ||
                    (action.direction == GoalDirection.AT_LEAST && action.initialGoal.signum() == 0)) return state
                val habit = Habit(UUID.randomUUID().toString(), action.title.trim(), action.unit, action.direction, CollectionMode.MANUAL, Instant.now())
                state.copy(habits = state.habits + habit, todayCells = state.todayCells + fixtureCell(habit, action.requestedFrom, action.initialGoal,
                    ActualValue.Unknown(UnavailableReason.UNKNOWN_ACTUAL)))
            }
            is ChronaAction.ArchiveHabit -> state.copy(habits = state.habits.map { if (it.id == action.habitId && it.collectionMode == CollectionMode.MANUAL) it.copy(archivedAt = Instant.now()) else it }, details = null)
            is ChronaAction.ProfilePhotoPicked -> state.copy(profile = state.profile.copy(photo = action.localUri?.let { ProfilePhoto(PhotoKind.LOCAL_URI, it) } ?: state.profile.photo))
            is ChronaAction.AcknowledgeValueAnimation -> if (state.valueCommit?.eventId == action.eventId) state.copy(valueCommit = null) else state
            is ChronaAction.DismissNotice -> if (state.notice?.id == action.id) state.copy(notice = null) else state
            ChronaAction.CloseFriend -> state.copy(friends = state.friends.copy(selected = null))
            else -> state.copy(notice = UiNotice(UUID.randomUUID().toString(), "NOT_CONNECTED", "Демо: Android host, Telegram и сервер не подключены. Настоящая операция не выполнена."))
        }
    }

    private fun replaceCell(state: ChronaUiState, cell: DayCell): ChronaUiState = state.copy(
        todayCells = if (state.todayCells.any { it.habitId == cell.habitId && it.day == cell.day }) state.todayCells.map {
            if (it.habitId == cell.habitId && it.day == cell.day) cell else it
        } else state.todayCells + cell,
        history = state.history.copy(cells = state.history.cells.map { if (it.habitId == cell.habitId && it.day == cell.day) cell else it },
            summary = state.history.summary.copy(scorePercent = unknown(UnavailableReason.NOT_IMPLEMENTED), curves = emptyList())),
        details = state.details?.let { if (it.habitId == cell.habitId && it.cell.day == cell.day) it.copy(cell = cell) else it },
    )
}
