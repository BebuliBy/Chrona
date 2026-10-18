package by.bebuliby.chrona.domain

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Contract v1. Raw decimals never pass through Float/Double or a goal cap. */
typealias Decimal = BigDecimal

data class DayKey(val date: LocalDate, val zoneId: String)
data class DayRange(val fromInclusive: LocalDate, val toExclusive: LocalDate, val zoneId: String)
data class ValueUnit(val code: String, val label: String)

enum class GoalDirection { AT_LEAST, AT_MOST }
enum class CollectionMode { MANUAL, AUTOMATIC_SHORTS }
enum class FactSource { MANUAL, COLLECTOR }

data class Habit(
    val id: String,
    val title: String,
    val unit: ValueUnit,
    val direction: GoalDirection,
    val collectionMode: CollectionMode,
    val createdAt: Instant,
    val archivedAt: Instant? = null,
    val revision: String? = null,
)

/** Immutable revision; requested applicability does not silently rewrite historical cells. */
data class GoalRevision(
    val id: String,
    val habitId: String,
    val amount: Decimal,
    val requestedFrom: DayKey,
    val recordedAt: Instant,
    val revision: String? = null,
)

/** Absolute value for one explicit day, not an increment or an automatic measurement override. */
data class ManualEntry(
    val id: String,
    val habitId: String,
    val day: DayKey,
    val value: Decimal,
    val recordedAt: Instant,
    val revision: String? = null,
)

enum class IntervalQuality { OBSERVED, CLOCK_DISCONTINUITY }
enum class IntervalEndReason {
    NOT_SHORTS, UNKNOWN, SCREEN_LOCKED, USER_DISABLED, PERMISSION_LOST,
    SERVICE_STOPPED, PROCESS_RECOVERY, CLOCK_CHANGED,
}

/** Closed raw collector record; elapsed values belong to the same boot session. */
data class ActivityInterval(
    val id: String,
    val habitId: String,
    val deviceId: String,
    val bootSessionId: String,
    val startUtc: Instant,
    val endUtc: Instant,
    val startElapsedMillis: Long,
    val endElapsedMillis: Long,
    val durationMillis: Long,
    val collectorVersion: String,
    val quality: IntervalQuality,
    val endReason: IntervalEndReason,
)

enum class GapReason {
    UNKNOWN_SCREEN, PERMISSION_LOST, USER_DISABLED, SERVICE_UNAVAILABLE,
    PROCESS_DEATH, REBOOT, CLOCK_CHANGED, UNSUPPORTED_WINDOW,
}

data class ObservationGap(
    val id: String,
    val habitId: String,
    val startUtc: Instant,
    val endUtc: Instant?,
    val reason: GapReason,
)

enum class TrackerStatus { NOT_CONNECTED, DISABLED, PERMISSION_REQUIRED, OBSERVING, UNAVAILABLE }
enum class ScreenObservation { SHORTS, NOT_SHORTS, UNKNOWN }

/** Raw coverage evidence, not an assertion that a whole calendar day was observed. */
data class ObservationSegment(
    val id: String,
    val habitId: String,
    val deviceId: String,
    val bootSessionId: String,
    val startUtc: Instant,
    val endUtc: Instant,
    val startElapsedMillis: Long,
    val endElapsedMillis: Long,
    val observation: ScreenObservation,
    val quality: IntervalQuality,
    val collectorVersion: String,
)

data class TrackerHealth(
    val status: TrackerStatus,
    val observation: ScreenObservation?,
    val lastObservedAt: Instant?,
    val reason: GapReason? = null,
)

enum class UnavailableReason {
    UNKNOWN_ACTUAL, PARTIAL_COVERAGE, NOT_PLANNED, SCHEDULE_UNRESOLVED, GOAL_UNRESOLVED,
    DAY_POLICY_UNRESOLVED, MULTI_DEVICE_UNRESOLVED, ZERO_LIMIT_RATIO,
    ZERO_LIMIT_EMOTION_UNRESOLVED, ZERO_LIMIT_CURVE_UNRESOLVED,
    STREAK_RULES_UNRESOLVED, SHARING_POLICY_UNRESOLVED, NOT_CONNECTED, NOT_IMPLEMENTED,
}

sealed interface Computed<out T> {
    data class Ready<T>(val value: T) : Computed<T>
    data class Unavailable(val reason: UnavailableReason) : Computed<Nothing>
}

sealed interface ActualValue {
    data class Known(val value: Decimal, val source: FactSource) : ActualValue
    data class Partial(val observedValue: Decimal, val source: FactSource) : ActualValue
    data class Unknown(val reason: UnavailableReason) : ActualValue
}

enum class PlanStatus { PLANNED, NOT_PLANNED, UNRESOLVED }
enum class Emotion { TEARS, VERY_SAD, SAD, NEUTRAL, SMILE, BIG_SMILE, STARS }

/** Derived read model, never a source table for facts. score is q in 0..1, not percent. */
data class DayCell(
    val habitId: String,
    val day: DayKey,
    val planStatus: PlanStatus,
    val goal: GoalRevision?,
    val actual: ActualValue,
    val usagePercent: Computed<Decimal>,
    val score: Computed<Decimal>,
    val emotion: Computed<Emotion>,
    val streak: Computed<Int>,
    val canEditActual: Boolean,
    val canEditGoal: Boolean,
)

/** Sanitized interval read model: no device identifiers, boot metadata or permission settings. */
data class IntervalView(
    val id: String,
    val habitId: String,
    val startUtc: Instant,
    val endUtc: Instant,
    val durationMillis: Long,
    val quality: IntervalQuality,
)

data class CurvePoint(val day: DayKey, val cumulative: Computed<Decimal>)
data class CumulativeCurve(
    val habitId: String?, // null = general curve, unit.code = "plan_shares"
    val unit: ValueUnit,
    val points: List<CurvePoint>,
)

data class PeriodSummary(
    val plannedCells: Int,
    val knownCells: Int,
    val scorePercent: Computed<Decimal>, // S in 0..100; retain fractional precision for color
    val curves: List<CumulativeCurve>,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }
data class AppPreferences(val themeMode: ThemeMode, val paletteId: String, val styleId: String)
enum class PhotoKind { LOCAL_URI, TELEGRAM_URL }
data class ProfilePhoto(val kind: PhotoKind, val location: String)
data class ProfileView(
    val localProfileId: String,
    val accountId: String?,
    val displayName: String,
    val photo: ProfilePhoto?,
    val telegramLinked: Boolean,
)

enum class FriendRelation { INCOMING, OUTGOING, ACCEPTED }
data class FriendSummary(
    val accountId: String,
    val displayName: String,
    val photo: ProfilePhoto?,
    val relation: FriendRelation?, // null = discoverable search result, not a friend yet
    val requestId: String? = null,
)

sealed interface SharingHistoryScope {
    data object Unresolved : SharingHistoryScope
    data class TimeWindow(val fromInclusive: Instant, val toExclusive: Instant?) : SharingHistoryScope
}

data class SharingGrantDraft(
    val friendAccountId: String,
    val habitIds: Set<String>,
    val historyScope: SharingHistoryScope,
    val expectedRevision: String?,
)

enum class SyncStatus { LOCAL_ONLY, IDLE, SYNCING, OFFLINE, ERROR }
data class SyncState(val status: SyncStatus, val lastSuccessAt: Instant?, val pendingOperations: Int,
    val automaticEnabled: Boolean = true)
