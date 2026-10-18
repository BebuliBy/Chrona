package by.bebuliby.chrona.tracking

import by.bebuliby.chrona.domain.*
import org.junit.Assert.*
import org.junit.Test

class TrackingEngineTest {
    private var id = 0
    private fun engine() = TrackingEngine("shorts", "device") { "id-${++id}" }
    private fun frame(time: Long, state: ScreenObservation, boot: String = "boot1", wall: Long = time) =
        TrackingFrame(wall, time, boot, state)

    @Test fun onlyShortsCountsAndUnknownGapIsNotJoined() {
        val e = engine()
        e.recover(null, frame(0, ScreenObservation.SHORTS))
        val first = e.accept(frame(5000, ScreenObservation.UNKNOWN))!!
        assertEquals(5000L, first.intervals.single().durationMillis)
        assertEquals(ScreenObservation.UNKNOWN, first.checkpoint!!.observation)
        assertNotNull(first.gaps.single().id)
        val resume = e.accept(frame(9000, ScreenObservation.SHORTS))!!
        assertTrue(resume.intervals.isEmpty())
        assertEquals(9000L, resume.gaps.single().endUtc!!.toEpochMilli())
        val second = e.accept(frame(11000, ScreenObservation.NOT_SHORTS))!!
        assertEquals(2000L, second.intervals.single().durationMillis)
    }

    @Test fun crashRecoveryClosesAtLastCheckpointNotNow() {
        val e = engine()
        e.recover(null, frame(0, ScreenObservation.SHORTS))
        assertNull(e.accept(frame(10000, ScreenObservation.SHORTS)))
        val saved = e.accept(frame(20000, ScreenObservation.SHORTS))!!.checkpoint!!
        val recovered = engine().recover(saved, frame(100000, ScreenObservation.SHORTS))
        assertEquals(20000L, recovered.intervals.single().durationMillis)
        assertEquals(20000L, recovered.intervals.single().endUtc.toEpochMilli())
        assertEquals(GapReason.PROCESS_DEATH, recovered.gaps.single().reason)
        assertEquals(100000L, recovered.checkpoint!!.startElapsed)
    }

    @Test fun rebootCannotSubtractDifferentMonotonicClocks() {
        val e = engine()
        val saved = e.recover(null, frame(50000, ScreenObservation.SHORTS)).checkpoint!!
        val result = engine().recover(saved, frame(1000, ScreenObservation.NOT_SHORTS, "boot2", 100000))
        assertTrue(result.intervals.isEmpty())
        assertEquals(GapReason.REBOOT, result.gaps.single().reason)
    }

    @Test fun stalledServiceDoesNotCountTheMissingMinute() {
        val e = engine()
        e.recover(null, frame(0, ScreenObservation.SHORTS))
        e.accept(frame(10000, ScreenObservation.SHORTS))
        val batch = e.accept(frame(100000, ScreenObservation.SHORTS))!!
        assertEquals(10000L, batch.intervals.single().durationMillis)
        assertEquals(GapReason.SERVICE_UNAVAILABLE, batch.gaps.single().reason)
    }

    @Test fun clockJumpPreservesMonotonicFactsAndFlagsUncertainAttribution() {
        val e = engine()
        e.recover(null, frame(0, ScreenObservation.SHORTS))
        e.accept(frame(10000, ScreenObservation.SHORTS))
        val result = e.accept(frame(20000, ScreenObservation.NOT_SHORTS, wall = -100000))!!
        assertEquals(10000L, result.intervals.single().durationMillis)
        assertEquals(IntervalQuality.CLOCK_DISCONTINUITY, result.intervals.single().quality)
        assertEquals(GapReason.CLOCK_CHANGED, result.gaps.single().reason)
    }

    @Test fun sameStateEventsDoNotWriteRoomEverySecond() {
        val e = engine()
        e.recover(null, frame(0, ScreenObservation.SHORTS))
        for (time in 1L..14L) assertNull(e.accept(frame(time * 1000, ScreenObservation.SHORTS)))
        assertNotNull(e.accept(frame(15000, ScreenObservation.SHORTS)))
    }

    @Test fun disabledDoesNotCreatePeriodicObservationWrites() {
        val e = engine()
        val disabled = frame(0, ScreenObservation.UNKNOWN).copy(status = TrackerStatus.DISABLED, gapReason = GapReason.USER_DISABLED)
        e.recover(null, disabled)
        assertNull(e.accept(disabled.copy(wall = 60000, elapsed = 60000)))
        assertNull(e.accept(disabled.copy(wall = 120000, elapsed = 120000)))
        val resume = e.accept(frame(150000, ScreenObservation.SHORTS))!!
        assertTrue(resume.intervals.isEmpty())
        assertEquals(GapReason.USER_DISABLED, resume.gaps.single().reason)
    }

    @Test fun screenLockAndDisableCloseIntervals() {
        val e = engine()
        e.recover(null, frame(0, ScreenObservation.SHORTS))
        val lock = e.accept(frame(2000, ScreenObservation.NOT_SHORTS).copy(endReason = IntervalEndReason.SCREEN_LOCKED))!!
        assertEquals(IntervalEndReason.SCREEN_LOCKED, lock.intervals.single().endReason)
        e.accept(frame(3000, ScreenObservation.SHORTS))
        val disabled = e.accept(frame(4000, ScreenObservation.UNKNOWN).copy(status = TrackerStatus.DISABLED,
            gapReason = GapReason.USER_DISABLED, endReason = IntervalEndReason.USER_DISABLED))!!
        assertEquals(1000L, disabled.intervals.single().durationMillis)
        assertEquals(GapReason.USER_DISABLED, disabled.gaps.single().reason)
    }

    @Test fun classifierExcludesFloatingWarningUnknownAndNavigationOnly() {
        fun c(foreground: Boolean = true, full: Boolean = true, modal: Boolean = false,
              usable: Boolean = true, ids: Set<String> = setOf("reel_player_page_container"), small: Boolean = false) =
            ShortsClassifier.classify(foreground, full, modal, usable, ids, small)
        assertEquals(ScreenObservation.SHORTS, c())
        assertEquals(ScreenObservation.NOT_SHORTS, c(foreground = false))
        assertEquals(ScreenObservation.UNKNOWN, c(full = false))
        assertEquals(ScreenObservation.NOT_SHORTS, c(full = false, small = true))
        assertEquals(ScreenObservation.UNKNOWN, c(modal = true))
        assertEquals(ScreenObservation.UNKNOWN, c(usable = false))
        assertEquals(ScreenObservation.UNKNOWN, c(ids = setOf("reel_time_bar", "shorts_tab")))
        assertEquals(ScreenObservation.UNKNOWN, c(ids = emptySet()))
    }
}
