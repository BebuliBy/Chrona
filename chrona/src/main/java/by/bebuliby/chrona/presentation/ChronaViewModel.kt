package by.bebuliby.chrona.presentation

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import by.bebuliby.chrona.data.*
import by.bebuliby.chrona.domain.*
import by.bebuliby.chrona.tracking.TrackingPermission
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class ChronaViewModel(private val container: ChronaContainer, application: Application) : AndroidViewModel(application), ChronaUiBridge {
    private val mutable = MutableStateFlow(initial())
    override val state = mutable.asStateFlow()
    private val commands = Channel<ChronaAction>(Channel.UNLIMITED)
    private val effectChannel = Channel<ChronaEffect>(Channel.BUFFERED)
    override val effects: Flow<ChronaEffect> = effectChannel.receiveAsFlow()
    private var facts = LocalFacts()
    private var settings: LocalSettingsSnapshot? = null
    private var selectedHabit: Pair<String, DayKey>? = null
    private var initialized = false
    private var clientNoticeId: String? = null

    init {
        viewModelScope.launch {
            try {
                container.ready.await()
                initialized = true
                combine(container.repository.facts, container.settings.snapshots, container.client.state) { f, s, c -> Triple(f, s, c) }
                    .collect { (f, s, c) ->
                        facts = f; settings = s
                        c.notice?.let { notice -> if (clientNoticeId != notice.id) {
                            clientNoticeId = notice.id; mutable.update { it.copy(notice = notice) }
                        } }
                        render()
                    }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail("STORAGE_ERROR", "Не удалось открыть локальное хранилище. Данные не сбрасывались.") }
        }
        viewModelScope.launch {
            for (action in commands) {
                try { handle(action) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: CoreFailure) { fail(failure.code, failure.message) }
                catch (_: Exception) { fail("LOCAL_ERROR", "Не удалось подтвердить операцию. Проверьте ввод, данные и доступ к хранилищу.") }
                finally { mutable.update { it.copy(busy = false) } }
            }
        }
        viewModelScope.launch {
            while (isActive) { delay(15_000); render() } // freshness/UI clock only; no storage writes
        }
    }

    override fun dispatch(action: ChronaAction) { commands.trySend(action) }
    fun refreshHostStatus() {
        render()
        if (container.client.state.value.loginPending || container.client.state.value.profile != null)
            dispatch(ChronaAction.RefreshLoginStatus)
    }
    fun hostError(code: String, message: String) { fail(code, message) }

    private suspend fun handle(action: ChronaAction) {
        when (action) {
            is ChronaAction.Navigate -> {
                mutable.update { it.copy(tab = action.tab) }
                if (action.tab == MainTab.FRIENDS) clientAction { container.client.refreshFriends() }
            }
            is ChronaAction.ConfigureServer -> ManagedServerConfiguration.rejectUserEdit()
            is ChronaAction.ConfigureDiscoverability -> clientAction { container.client.configureDiscoverability(action.enabled) }
            is ChronaAction.SelectDay -> {
                validateDay(action.day)
                selectedHabit = selectedHabit?.let { it.first to action.day }
                mutable.update { it.copy(selectedDay = action.day) }
            }
            is ChronaAction.SelectHistory -> {
                validateDay(DayKey(action.range.fromInclusive, action.range.zoneId))
                val length = java.time.temporal.ChronoUnit.DAYS.between(action.range.fromInclusive, action.range.toExclusive)
                if (length !in 1..366) throw CoreFailure("VALIDATION", "Выберите период от одного дня до года.")
                mutable.update { it.copy(history = it.history.copy(period = action.period, range = action.range)) }
            }
            is ChronaAction.OpenHabit -> { validateDay(action.day); selectedHabit = action.habitId to action.day
                mutable.update { it.copy(selectedDay = action.day) } }
            ChronaAction.CloseDetails -> selectedHabit = null
            ChronaAction.OpenSettings -> mutable.update { it.copy(settingsOpen = true) }
            ChronaAction.CloseSettings -> mutable.update { it.copy(settingsOpen = false) }
            is ChronaAction.CreateManualHabit -> {
                saving(); container.repository.create(action); facts = container.repository.snapshot()
            }
            is ChronaAction.SaveManualValue -> {
                // Repository repeats this check against actual persisted collectionMode inside transaction.
                SourcePolicy.manual(facts.habits.find { it.id == action.habitId }
                    ?: throw CoreFailure("NOT_FOUND", "Привычка не найдена."))
                saving(); container.repository.setManual(action.habitId, action.day, action.value)
                facts = container.repository.snapshot()
                mutable.update { it.copy(valueCommit = ValueCommit(UUID.randomUUID().toString(), action.habitId, action.day)) }
            }
            is ChronaAction.SaveGoal -> { saving(); container.repository.setGoal(action.habitId, action.amount, action.requestedFrom)
                facts = container.repository.snapshot() }
            is ChronaAction.ArchiveHabit -> { saving(); container.repository.archive(action.habitId)
                facts = container.repository.snapshot(); selectedHabit = null }
            is ChronaAction.ChangePreferences -> { saving(); container.settings.preferences(action.preferences) }
            ChronaAction.ChooseProfilePhoto -> effectChannel.send(ChronaEffect.OpenPhotoPicker)
            is ChronaAction.ProfilePhotoPicked -> {
                val raw = action.localUri ?: return
                val uri = Uri.parse(raw)
                val resolver = getApplication<Application>().contentResolver
                if (uri.scheme != "content" || resolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission })
                    throw CoreFailure("PHOTO_PERMISSION", "Не удалось сохранить доступ к выбранному фото.")
                if (resolver.getType(uri)?.startsWith("image/") != true)
                    throw CoreFailure("VALIDATION", "Выберите изображение.")
                saving(); container.settings.photo(raw)
            }
            ChronaAction.OpenTrackingPermissionSettings -> effectChannel.send(ChronaEffect.OpenAccessibilitySettings)
            is ChronaAction.SetTrackingEnabled -> {
                saving(); container.settings.tracking(action.enabled)
                if (action.enabled && !TrackingPermission.granted(getApplication<Application>()))
                    fail("PERMISSION_REQUIRED", "Наблюдение включено в Chrona. Разрешите службу вручную в настройках Android.")
            }
            is ChronaAction.DismissNotice -> mutable.update { if (it.notice?.id == action.id) it.copy(notice = null) else it }
            is ChronaAction.AcknowledgeValueAnimation -> mutable.update {
                if (it.valueCommit?.eventId == action.eventId) it.copy(valueCommit = null) else it
            }
            ChronaAction.CloseFriend -> container.client.closeFriend()
            ChronaAction.BeginTelegramLogin -> clientAction {
                effectChannel.send(ChronaEffect.OpenTelegramLogin(container.client.beginLogin()))
            }
            ChronaAction.RefreshLoginStatus -> clientAction { container.client.refreshLogin() }
            ChronaAction.Logout -> clientAction { container.client.logout() }
            ChronaAction.RequestSync -> clientAction { container.client.requestSync() }
            is ChronaAction.SetAutomaticSync -> clientAction { container.client.setAutomaticSync(action.enabled) }
            is ChronaAction.SearchFriends -> clientAction { container.client.search(action.query) }
            is ChronaAction.RequestFriend -> clientAction { container.client.requestFriend(action.accountId) }
            is ChronaAction.RespondToFriendRequest -> clientAction { container.client.respond(action.requestId, action.accept) }
            is ChronaAction.OpenFriend -> clientAction { container.client.openFriend(action.accountId, action.range, mutable.value.history.period) }
            is ChronaAction.SaveSharing -> clientAction { container.client.sharing(action.grant) }
        }
        render()
    }

    private fun clientAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { container.clientReady.await(); block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { container.client.notify(error) }
        }
    }

    private suspend fun saving() {
        container.ready.await()
        if (!initialized) throw CoreFailure("NOT_READY", "Хранилище ещё открывается.")
        mutable.update { it.copy(busy = true) }
    }
    private fun validateDay(day: DayKey) {
        runCatching { ZoneId.of(day.zoneId) }.getOrElse { throw CoreFailure("VALIDATION", "Неизвестный часовой пояс.") }
    }
    private fun fail(code: String, message: String) {
        mutable.update { it.copy(notice = UiNotice(UUID.randomUUID().toString(), code, message)) }
    }

    private fun render() {
        val s = settings ?: return
        val old = mutable.value
        val client = container.client.state.value
        val remote = client.profile
        val cells = LocalReadModels.cells(old.history.range, facts, s.deviceId)
        val selected = selectedHabit?.let { (id, day) -> facts.habits.find { it.id == id }?.let { habit ->
            HabitDetailsUiState(id, LocalReadModels.cell(habit, day, facts, s.deviceId),
                LocalReadModels.intervals(id, day, facts), LocalReadModels.gaps(id, day, facts))
        } }
        val permission = TrackingPermission.granted(getApplication<Application>())
        val health = when {
            !s.trackingEnabled -> TrackerHealth(TrackerStatus.DISABLED, null, facts.health?.lastObservedAt, GapReason.USER_DISABLED)
            !permission -> TrackerHealth(TrackerStatus.PERMISSION_REQUIRED, null, facts.health?.lastObservedAt, GapReason.PERMISSION_LOST)
            facts.health?.lastObservedAt?.isAfter(Instant.now().minusSeconds(45)) != true ->
                TrackerHealth(TrackerStatus.UNAVAILABLE, null, facts.health?.lastObservedAt, GapReason.SERVICE_UNAVAILABLE)
            else -> facts.health!!
        }
        mutable.update { it.copy(
            mode = if (remote != null) DataMode.CONNECTED else DataMode.LOCAL,
            today = currentDay(), profile = ProfileView(s.profileId, remote?.accountId, remote?.displayName ?: "Локальный профиль",
                s.photoUri?.let { uri -> ProfilePhoto(PhotoKind.LOCAL_URI, uri) }, remote?.telegramLinked ?: false),
            preferences = s.preferences, habits = facts.habits,
            todayCells = facts.habits.filter { h -> h.archivedAt == null }.map { h -> LocalReadModels.cell(h, old.selectedDay, facts, s.deviceId) },
            history = old.history.copy(cells = cells, summary = Scoring.summary(facts.habits, cells)),
            details = selected, tracker = health, sync = SyncState(client.syncStatus, client.lastSuccessAt, facts.pending, client.automaticEnabled),
            friends = client.friends, connection = ConnectionUiState(client.baseUrl, client.loginPending,remote?.discoverable ?: false),
        ) }
    }

    companion object {
        private fun currentDay() = DayKey(LocalDate.now(), ZoneId.systemDefault().id)
        private fun initial(): ChronaUiState {
            val today = currentDay()
            val range = DayRange(today.date.minusDays(6), today.date.plusDays(1), today.zoneId)
            return ChronaUiState(DataMode.LOCAL, today, today, MainTab.TODAY,
                ProfileView("", null, "Локальный профиль", null, false),
                AppPreferences(ThemeMode.SYSTEM, "ocean", "standard"), emptyList(), emptyList(),
                HistoryUiState(HistoryPeriod.WEEK, range, emptyList(), Scoring.summary(emptyList(), emptyList())),
                null, FriendsUiState(FeatureStatus.NOT_CONNECTED),
                TrackerHealth(TrackerStatus.DISABLED, null, null, GapReason.USER_DISABLED),
                SyncState(SyncStatus.LOCAL_ONLY, null, 0))
        }
        // Compatibility adapter for existing hosts. Normalize before creating the factory:
        // neither the factory nor the ViewModel captures the caller's Activity/Context.
        fun factory(container: ChronaContainer, context: Context): ViewModelProvider.Factory {
            val application = context.applicationContext
            require(application is Application) { "An Application context is required" }
            return factory(container, application)
        }

        fun factory(container: ChronaContainer, application: Application) = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(ChronaViewModel::class.java))
                @Suppress("UNCHECKED_CAST") return ChronaViewModel(container, application) as T
            }
        }
    }
}
