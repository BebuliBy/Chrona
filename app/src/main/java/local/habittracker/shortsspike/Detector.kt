package local.habittracker.shortsspike

enum class ScreenState { SHORTS, NOT_SHORTS, UNKNOWN }

data class Verdict(val state: ScreenState, val reason: String)

/** Candidate IDs, NOT a validated YouTube contract. Bottom navigation is never evidence. */
object ShortsClassifier {
    private val playerIds = setOf(
        "reel_recycler", "reel_watch_fragment_root", "reel_watch_player",
        "reel_player_page_container", "reel_player_view", "reel_watch_fragment",
    )

    fun classify(
        youtubeForeground: Boolean,
        usableWindow: Boolean,
        visibleIds: Set<String>,
    ): Verdict {
        if (!youtubeForeground) return Verdict(ScreenState.NOT_SHORTS, "youtube_not_foreground")
        if (!usableWindow) return Verdict(ScreenState.UNKNOWN, "window_unavailable")
        val marker = visibleIds.sorted().firstOrNull { it in playerIds }
        if (marker != null) return Verdict(ScreenState.SHORTS, "candidate_player_id:$marker")
        // Missing a positive marker is not proof of ordinary YouTube. Calibrate on device.
        return Verdict(ScreenState.UNKNOWN, "no_validated_player_marker")
    }
}

data class Interval(val startUtcMs: Long, val endUtcMs: Long, val durationMs: Long, val endReason: String)

/** Durations use monotonic time; wall clock is only an annotation. */
class IntervalTracker {
    var state = ScreenState.UNKNOWN
        private set
    private var startWall = 0L
    private var startElapsed = 0L

    fun accept(next: ScreenState, wall: Long, elapsed: Long, reason: String): Interval? {
        if (next == state) return null
        val closed = if (state == ScreenState.SHORTS) {
            Interval(startWall, wall, (elapsed - startElapsed).coerceAtLeast(0), reason)
        } else null
        if (next == ScreenState.SHORTS) {
            startWall = wall
            startElapsed = elapsed
        }
        state = next
        return closed
    }
}
