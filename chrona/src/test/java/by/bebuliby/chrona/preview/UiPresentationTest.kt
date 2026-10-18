package by.bebuliby.chrona.preview

import by.bebuliby.chrona.domain.*
import by.bebuliby.chrona.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class UiPresentationTest {
    private val duration = ValueUnit("duration_ms", "мс")

    @Test fun durationZeroAndNonzeroSubsecondAreDistinct() {
        assertEquals("0 с", durationLabel(BigDecimal.ZERO))
        assertEquals("<1 с", durationLabel(BigDecimal.ONE))
        assertEquals("<1 с", durationLabel(BigDecimal("999.9")))
        assertEquals("1 с", durationLabel(BigDecimal("1000")))
        assertEquals("1 с", durationLabel(BigDecimal("1999.9")))
    }
    @Test fun durationSkipsZeroHoursMinutesAndSeconds() {
        assertEquals("1 мин", durationLabel(BigDecimal(60_000)))
        assertEquals("1 ч", durationLabel(BigDecimal(3_600_000)))
        assertEquals("1 ч 1 с", durationLabel(BigDecimal(3_601_000)))
        assertEquals("2 ч 3 мин 4 с", durationLabel(BigDecimal(7_384_000)))
    }
    @Test fun durationsAreNotLimitedToLongOrConvertedThroughFloat() {
        assertEquals("100000000000000000000 ч", durationLabel(BigDecimal("360000000000000000000000000")))
    }
    @Test fun signedDurationCurveKeepsItsSignAndUnit() {
        assertEquals("−1 мин 2 с", presentedValue(BigDecimal(-62_000), duration))
        assertEquals("−<1 с", presentedValue(BigDecimal(-1), duration))
        assertEquals("время", axisUnitLabel(duration))
    }
    @Test fun durationDisplayDoesNotConvertPagesMinutesOrPlanShares() {
        assertEquals("12,75 страниц", measurementLabel(BigDecimal("12.75"), ValueUnit("pages", "страниц")))
        assertEquals("90 мин", measurementLabel(BigDecimal(90), ValueUnit("minutes", "мин")))
        assertEquals("1,25 доли плана", measurementLabel(BigDecimal("1.25"), ValueUnit("plan_shares", "доли плана")))
        assertEquals("1 ч 30 мин", measurementLabel(BigDecimal(5_400_000), duration))
    }
    @Test fun tileDetailsAndStatisticsShareActualGoalFormatting() {
        val cell = DemoState.sample().todayCells.first().copy(actual = ActualValue.Known(BigDecimal(5_400_000), FactSource.COLLECTOR))
        assertEquals("1 ч 30 мин / 20 мин", actualGoalLabel(cell, duration))
        assertEquals("1 ч 30 мин", actualLabel(cell.actual, duration))
    }
    @Test fun partialAndUnknownFactsNeverBecomeKnownZero() {
        assertEquals("≥ <1 с", actualLabel(ActualValue.Partial(BigDecimal.ONE, FactSource.COLLECTOR), duration))
        assertEquals("Нет данных", actualLabel(ActualValue.Unknown(UnavailableReason.UNKNOWN_ACTUAL), duration))
        assertEquals("0 с", actualLabel(ActualValue.Known(BigDecimal.ZERO, FactSource.COLLECTOR), duration))
    }
    @Test fun localIntervalClockHasNoMillisecondsOrUtcSuffix() {
        assertEquals("09:12:00 → 09:18:02", intervalTimeLabel(Instant.parse("2026-10-18T06:12:00.123Z"),
            Instant.parse("2026-10-18T06:18:02.999Z"), "Europe/Minsk", LocalDate.of(2026, 10, 18)))
    }
    @Test fun datesAppearOnlyWhenIntervalCrossesOrIsOutsideSelectedDay() {
        assertEquals("23:59:59 → 19.10 00:00:01", intervalTimeLabel(Instant.parse("2026-10-18T20:59:59Z"),
            Instant.parse("2026-10-18T21:00:01Z"), "Europe/Minsk", LocalDate.of(2026, 10, 18)))
        assertEquals("17.10 23:59:59 → 18.10 00:00:01", intervalTimeLabel(Instant.parse("2026-10-17T20:59:59Z"),
            Instant.parse("2026-10-17T21:00:01Z"), "Europe/Minsk", LocalDate.of(2026, 10, 18)))
    }
    @Test fun intervalYearBoundaryIsNotAmbiguous() {
        assertEquals("31.12.2026 23:59:59 → 01.01.2027 00:00:01", intervalTimeLabel(Instant.parse("2026-12-31T23:59:59Z"),
            Instant.parse("2027-01-01T00:00:01Z"), "UTC"))
    }
    @Test fun minuteEditorConvertsInputNotTheDisplayString() {
        assertEquals(BigDecimal("90000.0"), numberEditorValue("1,5", null, duration))
        assertEquals(BigDecimal("5400000"), numberEditorValue("90", null, duration))
        assertEquals(BigDecimal.ZERO, numberEditorValue("0", null, duration))
        assertNull(numberEditorValue("1 ч 30 мин", null, duration))
        assertEquals(BigDecimal("90"), numberEditorValue("90", null, ValueUnit("pages", "страниц")))
    }
    @Test fun untouchedRecurringMinuteValuePreservesExactRawMilliseconds() {
        val raw = BigDecimal("1.00000000000000000001")
        assertEquals(raw, numberEditorValue(numberEditorText(raw, duration), raw, duration))
    }
    @Test fun todayAndSelectedMarkersAreIndependentOfEachOtherAndScores() {
        val today = LocalDate.of(2026, 10, 18)
        val selected = today.minusDays(1)
        assertEquals(CalendarDateMarker(true, false), calendarDateMarker(today, today, selected))
        assertEquals(CalendarDateMarker(false, true), calendarDateMarker(selected, today, selected))
        assertEquals(CalendarDateMarker(true, true), calendarDateMarker(today, today, today))
    }
    @Test fun routineSuccessDoesNotNeedAcknowledgementButErrorsStayVisible() {
        for (code in listOf("SYNC_SNAPSHOT_COMPLETE", "SHARING_SAVED", "SYNC_IN_PROGRESS"))
            assertNull(userNoticeMessage(UiNotice("fixture", code, "Technical success text")))
        for (code in listOf("STORAGE_ERROR", "CONSENT_REQUIRED", "REVISION_CONFLICT", "PERMISSION_REQUIRED", "NOT_READY", "BOOTSTRAP_EXISTING_BINDING", "FUTURE_ERROR"))
            assertFalse(userNoticeMessage(UiNotice("fixture", code, "Internal details" )).isNullOrBlank())
    }
}
