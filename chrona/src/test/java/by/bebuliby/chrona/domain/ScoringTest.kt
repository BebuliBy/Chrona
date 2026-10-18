package by.bebuliby.chrona.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ScoringTest {
    private fun d(value: String) = value.toBigDecimal()
    private val day = DayKey(LocalDate.of(2026, 10, 18), "UTC")
    private val habit = Habit("read", "Читать", ValueUnit("pages", "страниц"), GoalDirection.AT_LEAST,
        CollectionMode.MANUAL, Instant.EPOCH)
    private fun goal(amount: String, key: DayKey = day) = GoalRevision("goal-${key.date}", habit.id, d(amount), key, Instant.EPOCH)
    private fun assertDecimal(expected: String, actual: Decimal) = assertEquals(0, d(expected).compareTo(actual))

    @Test fun allSevenBeneficialBoundariesExactAndAdjacent() {
        val cases = mapOf("0" to Emotion.TEARS, "0.0001" to Emotion.TEARS,
            "24.9999" to Emotion.TEARS, "25" to Emotion.VERY_SAD, "49.9999" to Emotion.VERY_SAD,
            "50" to Emotion.SAD, "74.9999" to Emotion.SAD, "75" to Emotion.NEUTRAL,
            "99.9999" to Emotion.NEUTRAL, "100" to Emotion.SMILE, "149.9999" to Emotion.SMILE,
            "150" to Emotion.BIG_SMILE, "199.9999" to Emotion.BIG_SMILE, "200" to Emotion.STARS,
            "100000.125" to Emotion.STARS)
        for ((actual, expected) in cases) assertEquals(actual, Computed.Ready(expected),
            Scoring.emotion(d(actual), d("100"), GoalDirection.AT_LEAST))
        assertEquals(7, cases.values.toSet().size)
    }

    @Test fun allSevenHarmfulBoundariesExactAndAdjacent() {
        val cases = mapOf("0" to Emotion.STARS, "0.0001" to Emotion.BIG_SMILE,
            "50" to Emotion.BIG_SMILE, "50.0001" to Emotion.SMILE,
            "100" to Emotion.SMILE, "100.0001" to Emotion.NEUTRAL,
            "150" to Emotion.NEUTRAL, "150.0001" to Emotion.SAD,
            "200" to Emotion.SAD, "200.0001" to Emotion.VERY_SAD,
            "400" to Emotion.VERY_SAD, "400.0001" to Emotion.TEARS)
        for ((actual, expected) in cases) assertEquals(actual, Computed.Ready(expected),
            Scoring.emotion(d(actual), d("100"), GoalDirection.AT_MOST))
        assertEquals(7, cases.values.toSet().size)
    }

    @Test fun fractionalGoalDoesNotRoundEmotionAtBoundary() {
        assertEquals(Computed.Ready(Emotion.NEUTRAL), Scoring.emotion(d("0.99999999999999999999999999999999999"),
            d("1"), GoalDirection.AT_LEAST))
        assertEquals(Computed.Ready(Emotion.VERY_SAD), Scoring.emotion(d("0.075"), d("0.3"), GoalDirection.AT_LEAST))
    }

    @Test fun scoresHarmfulAndBeneficialWithNoRawCap() {
        assertDecimal("1", Scoring.score(d("100000.25"), d("20"), GoalDirection.AT_LEAST))
        assertDecimal("1", Scoring.score(d("50"), d("100"), GoalDirection.AT_MOST))
        assertDecimal("0.5", Scoring.score(d("150"), d("100"), GoalDirection.AT_MOST))
        assertDecimal("0", Scoring.score(d("200"), d("100"), GoalDirection.AT_MOST))
        val cell = Scoring.cell(habit, day, goal("20"), ActualValue.Known(d("100000.25"), FactSource.MANUAL))
        assertDecimal("100000.25", (cell.actual as ActualValue.Known).value)
    }

    @Test fun roundingCannotTurnTinyDeficitOrViolationIntoFullSuccess() {
        val epsilon = d("0.0000000000000000000000000000000000000000000001")
        assertTrue(Scoring.score(d("1") - epsilon, d("1"), GoalDirection.AT_LEAST) < d("1"))
        assertTrue(Scoring.score(d("1") + epsilon, d("1"), GoalDirection.AT_MOST) < d("1"))
        assertTrue(Scoring.score(d("2") - epsilon, d("1"), GoalDirection.AT_MOST) > d("0"))
    }

    @Test fun zeroLimitKnownZeroIsSuccessNotUnknownOrDivisionByZero() {
        val harmful = habit.copy(direction = GoalDirection.AT_MOST)
        val known = Scoring.cell(harmful, day, goal("0"), ActualValue.Known(d("0"), FactSource.MANUAL))
        assertDecimal("1", (known.score as Computed.Ready).value)
        assertEquals(Computed.Unavailable(UnavailableReason.ZERO_LIMIT_RATIO), known.usagePercent)
        assertEquals(Computed.Unavailable(UnavailableReason.ZERO_LIMIT_EMOTION_UNRESOLVED), known.emotion)
        assertDecimal("0", Scoring.score(d("0.001"), d("0"), GoalDirection.AT_MOST))
        val unknown = Scoring.cell(harmful, day, goal("0"), ActualValue.Unknown(UnavailableReason.UNKNOWN_ACTUAL))
        assertTrue(unknown.score is Computed.Unavailable)
        val partial = Scoring.cell(harmful, day, goal("0"), ActualValue.Partial(d("0"), FactSource.COLLECTOR))
        assertTrue(partial.score is Computed.Unavailable)
    }

    @Test fun meanCapDoesNotLetOvergoalCompensateFailure() {
        val other = habit.copy(id = "walk")
        val cells = listOf(
            Scoring.cell(habit, day, goal("10"), ActualValue.Known(d("100"), FactSource.MANUAL)),
            Scoring.cell(other, day, goal("10").copy(habitId = other.id), ActualValue.Known(d("0"), FactSource.MANUAL)))
        assertDecimal("50", (Scoring.summary(listOf(habit, other), cells).scorePercent as Computed.Ready).value)
    }

    @Test fun sevenDailyTwentyExtraIs140Not170() {
        val cells = (0L..6L).map { offset ->
            val key = day.copy(date = day.date.plusDays(offset))
            Scoring.cell(habit, key, goal("20", key), ActualValue.Known(d("40"), FactSource.MANUAL))
        }
        val summary = Scoring.summary(listOf(habit), cells)
        assertDecimal("140", (summary.curves.single { it.habitId == habit.id }.points.last().cumulative as Computed.Ready).value)
        assertDecimal("7", (summary.curves.single { it.habitId == null }.points.last().cumulative as Computed.Ready).value)
    }

    @Test fun harmfulDeviationIsReversedAndCurveIsNotCapped() {
        assertDecimal("-2", (Scoring.deviation(d("30"), d("10"), GoalDirection.AT_MOST, true) as Computed.Ready).value)
        assertDecimal("20", (Scoring.deviation(d("30"), d("10"), GoalDirection.AT_LEAST, false) as Computed.Ready).value)
        assertTrue(Scoring.deviation(d("0"), d("0"), GoalDirection.AT_MOST, true) is Computed.Unavailable)
    }

    @Test fun partialCoverageAndMissingPlanFailClosedWithoutHidingKnownCellScore() {
        val known = Scoring.cell(habit, day, goal("10"), ActualValue.Known(d("5"), FactSource.MANUAL))
        assertDecimal("0.5", (known.score as Computed.Ready).value)
        val missing = Scoring.cell(habit, day.copy(date = day.date.plusDays(1)), null,
            ActualValue.Unknown(UnavailableReason.UNKNOWN_ACTUAL))
        assertTrue(Scoring.summary(listOf(habit), listOf(known, missing)).scorePercent is Computed.Unavailable)
        assertTrue(Scoring.summary(emptyList(), emptyList()).scorePercent is Computed.Unavailable)
        assertEquals(Computed.Unavailable(UnavailableReason.STREAK_RULES_UNRESOLVED), known.streak)
    }

    @Test fun sourceGuardRejectsAutomaticEvenIfCallerInventsManualAction() {
        val automatic = habit.copy(collectionMode = CollectionMode.AUTOMATIC_SHORTS)
        try { SourcePolicy.manual(automatic); fail("Must reject automatic actual") }
        catch (error: CoreFailure) { assertEquals("SOURCE_NOT_WRITABLE", error.code) }
        SourcePolicy.manual(habit)
        SourcePolicy.amount(d("123456789.123456789"))
        SourcePolicy.goal(automatic.copy(direction = GoalDirection.AT_MOST), d("0"))
    }

    @Test fun negativeActualZeroBeneficialGoalAndArchivedWriteRejected() {
        for (block in listOf<() -> Unit>({ SourcePolicy.amount(d("-0.1")) },
            { SourcePolicy.goal(habit, d("0")) }, { SourcePolicy.manual(habit.copy(archivedAt = Instant.EPOCH)) })) {
            try { block(); fail("Validation expected") } catch (_: CoreFailure) { }
        }
    }
}
