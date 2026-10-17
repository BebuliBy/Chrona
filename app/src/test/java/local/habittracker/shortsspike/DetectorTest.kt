package local.habittracker.shortsspike

import org.junit.Assert.*
import org.junit.Test

class DetectorTest {
    @Test fun bottomNavigationIsNotEvidence() {
        assertEquals(ScreenState.UNKNOWN, ShortsClassifier.classify(true, true, setOf("shorts_tab", "reel_shelf")).state)
    }
    @Test fun playerMarkerIsCandidate() {
        assertEquals(ScreenState.SHORTS, ShortsClassifier.classify(true, true, setOf("reel_recycler")).state)
    }
    @Test fun leavingYoutubeOverridesCachedIds() {
        assertEquals(ScreenState.NOT_SHORTS, ShortsClassifier.classify(false, true, setOf("reel_recycler")).state)
    }
    @Test fun missingWindowIsUnknown() {
        assertEquals(ScreenState.UNKNOWN, ShortsClassifier.classify(true, false, setOf("reel_recycler")).state)
    }
    @Test fun noMarkerDoesNotClaimOrdinaryVideo() {
        assertEquals(ScreenState.UNKNOWN, ShortsClassifier.classify(true, true, emptySet()).state)
    }
    @Test fun repeatedSamplesDoNotRestartInterval() {
        val tracker = IntervalTracker()
        tracker.accept(ScreenState.SHORTS, 10000, 100, "entry")
        assertNull(tracker.accept(ScreenState.SHORTS, 20000, 500, "sample"))
        val end = tracker.accept(ScreenState.NOT_SHORTS, 21000, 1100, "background")!!
        assertEquals(10000L, end.startUtcMs)
        assertEquals(1000L, end.durationMs)
    }
    @Test fun wallClockChangeDoesNotChangeDuration() {
        val tracker = IntervalTracker()
        tracker.accept(ScreenState.SHORTS, 10000, 100, "entry")
        assertEquals(500L, tracker.accept(ScreenState.UNKNOWN, 5000, 600, "gap")!!.durationMs)
    }
    @Test fun unknownClosesOnce() {
        val tracker = IntervalTracker()
        tracker.accept(ScreenState.SHORTS, 10000, 100, "entry")
        assertNotNull(tracker.accept(ScreenState.UNKNOWN, 11000, 1100, "permission_gap"))
        assertNull(tracker.accept(ScreenState.NOT_SHORTS, 12000, 2100, "background"))
    }
}
