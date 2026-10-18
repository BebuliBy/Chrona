package by.bebuliby.chrona.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class MainTab { TODAY, HISTORY, HABITS, FRIENDS }
enum class HistoryPeriod { WEEK, MONTH, YEAR }
enum class DataMode { DEMO, LOCAL, CONNECTED }
enum class FeatureStatus { NOT_CONNECTED, LOADING, READY, ERROR }

data class HistoryUiState(
    val period: HistoryPeriod,
    val range: DayRange,
    val cells: List<DayCell>,
    val summary: PeriodSummary,
)

data class HabitDetailsUiState(
    val habitId: String,
    val cell: DayCell,
    val intervals: List<IntervalView>,
    val gaps: List<ObservationGap>,
)

/** All lists have already been authorized. UI must not receive hidden habits and filter locally. */
data class FriendActivityUiState(
    val owner: FriendSummary,
    val habits: List<Habit>,
    val history: HistoryUiState,
    val intervals: List<IntervalView>,
    val gaps: List<ObservationGap>,
)

data class FriendsUiState(
    val status: FeatureStatus,
    val friends: List<FriendSummary> = emptyList(),
    val searchResults: List<FriendSummary> = emptyList(),
    val selected: FriendActivityUiState? = null,
)

data class UiNotice(val id: String, val code: String, val message: String)
data class ValueCommit(val eventId: String, val habitId: String, val day: DayKey)
data class ConnectionUiState(val baseUrl: String = "", val loginPending: Boolean = false, val discoverable: Boolean = false)

/** Stable boundary for UI/preview and the core-owned ViewModel. No fixture seeding by default. */
data class ChronaUiState(
    val mode: DataMode,
    val today: DayKey,
    val selectedDay: DayKey,
    val tab: MainTab,
    val profile: ProfileView,
    val preferences: AppPreferences,
    val habits: List<Habit>,
    val todayCells: List<DayCell>,
    val history: HistoryUiState,
    val details: HabitDetailsUiState?,
    val friends: FriendsUiState,
    val tracker: TrackerHealth,
    val sync: SyncState,
    val settingsOpen: Boolean = false,
    val busy: Boolean = false,
    val notice: UiNotice? = null,
    val valueCommit: ValueCommit? = null,
    val connection: ConnectionUiState = ConnectionUiState(),
    val update: UpdateUiState = UpdateUiState(),
    val showAccountEntry: Boolean = false,
)

sealed interface ChronaAction {
    data object CheckForUpdates : ChronaAction
    data object InstallUpdate : ChronaAction
    data object OpenAccountEntry : ChronaAction
    data object ContinueWithoutAccount : ChronaAction
    data class ConfigureServer(val baseUrl: String) : ChronaAction
    data class ConfigureDiscoverability(val enabled: Boolean) : ChronaAction
    data class Navigate(val tab: MainTab) : ChronaAction
    data class SelectDay(val day: DayKey) : ChronaAction
    data class SelectHistory(val period: HistoryPeriod, val range: DayRange) : ChronaAction
    data class OpenHabit(val habitId: String, val day: DayKey) : ChronaAction
    data object CloseDetails : ChronaAction
    data object OpenSettings : ChronaAction
    data object CloseSettings : ChronaAction

    data class CreateManualHabit(
        val title: String,
        val unit: ValueUnit,
        val direction: GoalDirection,
        val initialGoal: Decimal,
        val requestedFrom: DayKey,
    ) : ChronaAction
    data class SaveManualValue(val habitId: String, val day: DayKey, val value: Decimal) : ChronaAction
    data class SaveGoal(val habitId: String, val amount: Decimal, val requestedFrom: DayKey) : ChronaAction
    data class ArchiveHabit(val habitId: String) : ChronaAction

    data class ChangePreferences(val preferences: AppPreferences) : ChronaAction
    data object ChooseProfilePhoto : ChronaAction
    data class ProfilePhotoPicked(val localUri: String?) : ChronaAction
    data object OpenTrackingPermissionSettings : ChronaAction
    data class SetTrackingEnabled(val enabled: Boolean) : ChronaAction

    data object BeginTelegramLogin : ChronaAction
    data object RefreshLoginStatus : ChronaAction
    data object Logout : ChronaAction
    data object RequestSync : ChronaAction
    data class SetAutomaticSync(val enabled: Boolean) : ChronaAction
    data class SearchFriends(val query: String) : ChronaAction
    data class RequestFriend(val accountId: String) : ChronaAction
    data class RespondToFriendRequest(val requestId: String, val accept: Boolean) : ChronaAction
    data class OpenFriend(val accountId: String, val range: DayRange, val period: HistoryPeriod? = null) : ChronaAction
    data object CloseFriend : ChronaAction
    data class SaveSharing(val grant: SharingGrantDraft) : ChronaAction

    data class DismissNotice(val id: String) : ChronaAction
    data class AcknowledgeValueAnimation(val eventId: String) : ChronaAction
}

/** Host-only one-off work, not replayed state. Permission changes still require the user. */
sealed interface ChronaEffect {
    data object OpenPhotoPicker : ChronaEffect
    data object OpenAccessibilitySettings : ChronaEffect
    data class OpenTelegramLogin(val telegramStartUrl: String) : ChronaEffect
    data object OpenUpdateInstallPermission : ChronaEffect
    data class OpenUpdateInstaller(val contentUri: String) : ChronaEffect
}

/** Implemented by core's presentation/ChronaViewModel, not by UI or preview models. */
interface ChronaUiBridge {
    val state: StateFlow<ChronaUiState>
    val effects: Flow<ChronaEffect>
    fun dispatch(action: ChronaAction)
}
