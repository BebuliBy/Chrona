package by.bebuliby.chrona.domain

/** Local-only raw state, deliberately separate from the stable UI/wire contract. */
data class TrackingCheckpoint(
    val id: String = "main",
    val segmentId: String,
    val intervalId: String?,
    val gapId: String?,
    val bootId: String,
    val startWall: Long,
    val startElapsed: Long,
    val lastWall: Long,
    val lastElapsed: Long,
    val observation: ScreenObservation,
    val gapReason: GapReason?,
    val status: TrackerStatus,
)

data class CollectorBatch(
    val intervals: List<ActivityInterval> = emptyList(),
    val segments: List<ObservationSegment> = emptyList(),
    val gaps: List<ObservationGap> = emptyList(),
    val checkpoint: TrackingCheckpoint?,
    val health: TrackerHealth,
)

data class LocalFacts(
    val habits: List<Habit> = emptyList(),
    val goals: List<GoalRevision> = emptyList(),
    val entries: List<ManualEntry> = emptyList(),
    val intervals: List<ActivityInterval> = emptyList(),
    val segments: List<ObservationSegment> = emptyList(),
    val gaps: List<ObservationGap> = emptyList(),
    val checkpoint: TrackingCheckpoint? = null,
    val health: TrackerHealth? = null,
    val pending: Int = 0,
)
