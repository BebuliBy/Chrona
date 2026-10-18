package by.bebuliby.chrona.tracking

import by.bebuliby.chrona.domain.*
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

data class TrackingFrame(
    val wall: Long,
    val elapsed: Long,
    val bootId: String,
    val observation: ScreenObservation,
    val status: TrackerStatus = TrackerStatus.OBSERVING,
    val gapReason: GapReason? = null,
    val endReason: IntervalEndReason = IntervalEndReason.UNKNOWN,
)

/** Pure actor-owned state machine. No extension beyond a confirmed sample on recovery/stall. */
class TrackingEngine(
    private val habitId: String,
    private val deviceId: String,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    companion object {
        const val VERSION = "chrona-structural-1"
        const val CHECKPOINT_MILLIS = 5_000L
        const val SYNC_SLICE_MILLIS = 5_000L
        const val MAX_CONTINUITY_MILLIS = 30_000L
    }
    private var current: TrackingCheckpoint? = null
    private var lastPersisted = Long.MIN_VALUE

    fun recover(saved: TrackingCheckpoint?, frame: TrackingFrame): CollectorBatch {
        val intervals = mutableListOf<ActivityInterval>()
        val segments = mutableListOf<ObservationSegment>()
        val gaps = mutableListOf<ObservationGap>()
        if (saved != null) {
            close(saved, saved.lastWall, saved.lastElapsed, IntervalEndReason.PROCESS_RECOVERY,
                IntervalQuality.OBSERVED, intervals, segments, gaps)
            gaps += ObservationGap(newId(), habitId, Instant.ofEpochMilli(saved.lastWall),
                Instant.ofEpochMilli(frame.wall), when {
                    saved.bootId != frame.bootId -> GapReason.REBOOT
                    saved.status == TrackerStatus.DISABLED -> GapReason.USER_DISABLED
                    saved.status != TrackerStatus.OBSERVING -> GapReason.SERVICE_UNAVAILABLE
                    else -> GapReason.PROCESS_DEATH
                })
        }
        current = open(frame)
        current!!.gapId?.let { gaps += gap(current!!, null) }
        lastPersisted = frame.elapsed
        return batch(frame, intervals, segments, gaps)
    }

    /** Null means no durable write needed, not an observation of zero usage. */
    fun accept(frame: TrackingFrame): CollectorBatch? {
        val previous = current ?: return recover(null, frame)
        if (previous.status == TrackerStatus.DISABLED && frame.status == TrackerStatus.DISABLED &&
            previous.bootId == frame.bootId) {
            current = previous.copy(lastWall = frame.wall, lastElapsed = frame.elapsed)
            return null // disabled is an intentional gap, not periodic observation or a DB heartbeat
        }
        val delta = frame.elapsed - previous.lastElapsed
        val clockChanged = previous.bootId == frame.bootId && abs((frame.wall - previous.lastWall) - delta) > 5_000
        val discontinuity = previous.bootId != frame.bootId || delta < 0 ||
            (delta > MAX_CONTINUITY_MILLIS && previous.status != TrackerStatus.DISABLED) || clockChanged
        val changed = frame.observation != previous.observation || frame.status != previous.status ||
            frame.gapReason != previous.gapReason
        val intervals = mutableListOf<ActivityInterval>()
        val segments = mutableListOf<ObservationSegment>()
        val gaps = mutableListOf<ObservationGap>()
        if (discontinuity || changed) {
            close(previous, if (discontinuity) previous.lastWall else frame.wall,
                if (discontinuity) previous.lastElapsed else frame.elapsed,
                if (!discontinuity) frame.endReason else when {
                    previous.bootId != frame.bootId -> IntervalEndReason.PROCESS_RECOVERY
                    clockChanged -> IntervalEndReason.CLOCK_CHANGED
                    else -> IntervalEndReason.SERVICE_STOPPED
                },
                if (clockChanged) IntervalQuality.CLOCK_DISCONTINUITY else IntervalQuality.OBSERVED,
                intervals, segments, gaps)
            if (discontinuity) gaps += ObservationGap(newId(), habitId, Instant.ofEpochMilli(previous.lastWall),
                Instant.ofEpochMilli(frame.wall), when {
                    previous.bootId != frame.bootId -> GapReason.REBOOT
                    clockChanged -> GapReason.CLOCK_CHANGED
                    else -> GapReason.SERVICE_UNAVAILABLE
                })
            current = open(frame)
            current!!.gapId?.let { gaps += gap(current!!, null) }
        } else {
            if (previous.observation == ScreenObservation.SHORTS && frame.elapsed - previous.startElapsed >= SYNC_SLICE_MILLIS) {
                // Publish a CLOSED immutable measured slice while viewing continues. No gap, no guessed now-time.
                close(previous, frame.wall, frame.elapsed, IntervalEndReason.UNKNOWN, IntervalQuality.OBSERVED,
                    intervals, segments, gaps)
                current = open(frame)
            } else {
                current = previous.copy(lastWall = frame.wall, lastElapsed = frame.elapsed)
                if (frame.elapsed - lastPersisted < CHECKPOINT_MILLIS) return null
            }
        }
        lastPersisted = frame.elapsed
        return batch(frame, intervals, segments, gaps)
    }

    private fun open(f: TrackingFrame) = TrackingCheckpoint(
        segmentId = newId(), intervalId = if (f.observation == ScreenObservation.SHORTS) newId() else null,
        gapId = if (f.observation == ScreenObservation.UNKNOWN) newId() else null,
        bootId = f.bootId, startWall = f.wall, startElapsed = f.elapsed,
        lastWall = f.wall, lastElapsed = f.elapsed, observation = f.observation,
        gapReason = if (f.observation == ScreenObservation.UNKNOWN) f.gapReason ?: GapReason.UNKNOWN_SCREEN else null,
        status = f.status,
    )

    private fun gap(cp: TrackingCheckpoint, end: Long?) = ObservationGap(cp.gapId!!, habitId,
        Instant.ofEpochMilli(cp.startWall), end?.let(Instant::ofEpochMilli), cp.gapReason ?: GapReason.UNKNOWN_SCREEN)

    private fun close(cp: TrackingCheckpoint, wall: Long, elapsed: Long, reason: IntervalEndReason,
                      quality: IntervalQuality, intervals: MutableList<ActivityInterval>,
                      segments: MutableList<ObservationSegment>, gaps: MutableList<ObservationGap>) {
        val end = elapsed.coerceAtLeast(cp.startElapsed)
        if (end > cp.startElapsed) {
            segments += ObservationSegment(cp.segmentId, habitId, deviceId, cp.bootId,
                Instant.ofEpochMilli(cp.startWall), Instant.ofEpochMilli(wall), cp.startElapsed, end,
                cp.observation, quality, VERSION)
            cp.intervalId?.let { intervals += ActivityInterval(it, habitId, deviceId, cp.bootId,
                Instant.ofEpochMilli(cp.startWall), Instant.ofEpochMilli(wall), cp.startElapsed, end,
                end - cp.startElapsed, VERSION, quality, reason) }
        }
        cp.gapId?.let { gaps += gap(cp, wall) }
    }

    private fun batch(f: TrackingFrame, intervals: List<ActivityInterval>, segments: List<ObservationSegment>,
                      gaps: List<ObservationGap>) = CollectorBatch(intervals, segments, gaps, current,
        TrackerHealth(f.status, f.observation, Instant.ofEpochMilli(f.wall), f.gapReason))
}

/** Only two positive IDs have real-device evidence; never use the bottom Shorts tab/time bar. */
object ShortsClassifier {
    val positiveIds = setOf("reel_player_page_container", "reel_recycler")
    fun classify(youtubeForeground: Boolean, fullWindow: Boolean, modal: Boolean,
                 usable: Boolean, visibleMarkers: Set<String>, smallFloating: Boolean = false): ScreenObservation = when {
        !youtubeForeground -> ScreenObservation.NOT_SHORTS
        smallFloating -> ScreenObservation.NOT_SHORTS
        !fullWindow -> ScreenObservation.UNKNOWN // do not invent an exclusion policy for large split/pop-up modes
        modal || !usable -> ScreenObservation.UNKNOWN
        visibleMarkers.any { it in positiveIds } -> ScreenObservation.SHORTS
        else -> ScreenObservation.UNKNOWN
    }
}
