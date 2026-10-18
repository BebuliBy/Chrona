package by.bebuliby.chrona.presentation

import by.bebuliby.chrona.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class LocalReadModelsTest {
    private val day = DayKey(LocalDate.of(2026, 10, 18), "UTC")
    private val habit = Habit("h", "Читать", ValueUnit("pages", "страниц"), GoalDirection.AT_LEAST,
        CollectionMode.MANUAL, Instant.EPOCH)
    private val goal = GoalRevision("g", "h", "10".toBigDecimal(), day, Instant.EPOCH)

    @Test fun explicitDayValueAndGoalProduceRealScoreAndEmotionWithoutRecurrence() {
        val entry = ManualEntry("e", "h", day, "15.125".toBigDecimal(), Instant.EPOCH)
        val facts = LocalFacts(listOf(habit), listOf(goal), listOf(entry))
        val cell = LocalReadModels.cell(habit, day, facts)
        assertEquals(Computed.Ready(Emotion.BIG_SMILE), cell.emotion)
        assertEquals(0, (cell.score as Computed.Ready).value.compareTo("1".toBigDecimal()))
        val tomorrow = LocalReadModels.cell(habit, day.copy(date = day.date.plusDays(1)), facts)
        assertNull(tomorrow.goal)
        assertTrue(tomorrow.actual is ActualValue.Unknown)
        assertEquals(PlanStatus.UNRESOLVED, tomorrow.planStatus)
    }

    @Test fun noFixtureSeedOrMissingManualValueAsZero() {
        val cell = LocalReadModels.cell(habit, day, LocalFacts(listOf(habit), listOf(goal)))
        assertTrue(cell.actual is ActualValue.Unknown)
        assertTrue(cell.score is Computed.Unavailable)
    }

    @Test fun sameDayGoalRevisionIsAppendOnlyAndNotInheritedByOtherDaysOrZones() {
        val second = goal.copy(id = "g2", amount = "20".toBigDecimal())
        val facts = LocalFacts(listOf(habit), listOf(goal, second))
        assertEquals(second, LocalReadModels.cell(habit, day, facts).goal)
        assertEquals("10".toBigDecimal(), facts.goals.first().amount)
        assertNull(LocalReadModels.cell(habit, day.copy(zoneId = "Europe/Minsk"), facts).goal)
    }

    @Test fun automaticEmptyHistoryNeverKnownZeroEvenAtZeroLimit() {
        val automatic = habit.copy(collectionMode = CollectionMode.AUTOMATIC_SHORTS, direction = GoalDirection.AT_MOST)
        val facts = LocalFacts(listOf(automatic), listOf(goal.copy(amount = "0".toBigDecimal())))
        val cell = LocalReadModels.cell(automatic, day, facts)
        assertTrue(cell.actual is ActualValue.Unknown)
        assertFalse(cell.canEditActual)
        assertTrue(cell.score is Computed.Unavailable)
    }

    @Test fun archivedHabitIsReadOnlyWithoutDeletingHistory() {
        val archived = habit.copy(archivedAt = Instant.now())
        val entry = ManualEntry("e", "h", day, "25".toBigDecimal(), Instant.EPOCH)
        val cell = LocalReadModels.cell(archived, day, LocalFacts(listOf(archived), listOf(goal), listOf(entry)))
        assertTrue(cell.actual is ActualValue.Known)
        assertFalse(cell.canEditActual)
        assertFalse(cell.canEditGoal)
    }

    @Test fun restoredMultipleDevicesAreNotSilentlySummed() {
        val automatic = habit.copy(collectionMode = CollectionMode.AUTOMATIC_SHORTS, direction = GoalDirection.AT_MOST)
        val start = Instant.parse("2026-10-18T10:00:00Z")
        fun interval(id: String, device: String) = ActivityInterval(id, "h", device, "boot", start, start.plusSeconds(10),
            0, 10000, 10000, "test", IntervalQuality.OBSERVED, IntervalEndReason.NOT_SHORTS)
        val facts = LocalFacts(listOf(automatic), intervals = listOf(interval("a", "device-a"), interval("b", "device-b")))
        assertEquals(ActualValue.Unknown(UnavailableReason.MULTI_DEVICE_UNRESOLVED), LocalReadModels.cell(automatic, day, facts, "device-a").actual)
    }
}
