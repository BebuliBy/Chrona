package by.bebuliby.chrona.preview

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DemoReducerTest {
    private val today = LocalDate.of(2026, 10, 18)
    private val initial = DemoState.sample(today)

    @Test fun fixtureDurationIsConsistent() { assertEquals(1476L, initial.totalSeconds) }
    @Test fun invalidGoalIsRejected() { assertFalse(DemoReducer.validHabit("Читать", 0, "раз")) }
    @Test fun emptyTitleIsRejected() { assertFalse(DemoReducer.validHabit("  ", 1, "раз")) }
    @Test fun addingHabitTrimsInput() {
        val next = DemoReducer.reduce(initial, DemoAction.AddHabit("new", "  Вода  ", 2, " стакана "))
        assertEquals("Вода", next.habits.last().title)
        assertEquals("стакана", next.habits.last().unit)
    }
    @Test fun duplicateIdIsRejected() {
        assertEquals(initial, DemoReducer.reduce(initial, DemoAction.AddHabit("walk", "Дубль", 1, "раз")))
    }
    @Test fun progressCannotExceedGoal() {
        val next = DemoReducer.reduce(initial, DemoAction.ChangeProgress("reading", Int.MAX_VALUE))
        assertEquals(20, next.habits.first().progress)
    }
    @Test fun progressCannotBeNegative() {
        val next = DemoReducer.reduce(initial, DemoAction.ChangeProgress("reading", Int.MIN_VALUE))
        assertEquals(0, next.habits.first().progress)
    }
    @Test fun completionCanBeUndone() {
        val done = DemoReducer.reduce(initial, DemoAction.ToggleCompleted("reading"))
        assertTrue(done.habits.first().completed)
        assertEquals(0, DemoReducer.reduce(done, DemoAction.ToggleCompleted("reading")).habits.first().progress)
    }
    @Test fun clearIsOnlyInMemoryAndKeepsTheme() {
        val dark = initial.copy(theme = ThemeMode.DARK)
        val empty = DemoReducer.reduce(dark, DemoAction.ClearExamples)
        assertTrue(empty.habits.isEmpty()); assertEquals(0L, empty.totalSeconds)
        assertEquals(ThemeMode.DARK, empty.theme)
    }
    @Test fun futureDateIsRejected() {
        assertEquals(initial, DemoReducer.reduce(initial, DemoAction.SelectDate(today.plusDays(1))))
    }
    @Test fun resetKeepsVisualPreferences() {
        val empty = initial.copy(habits = emptyList(), theme = ThemeMode.DARK, palette = Palette.LILAC)
        val reset = DemoReducer.reduce(empty, DemoAction.ResetExamples)
        assertEquals(initial.habits, reset.habits); assertEquals(ThemeMode.DARK, reset.theme)
        assertEquals(Palette.LILAC, reset.palette)
    }
    @Test fun durationFormattingDoesNotInventPrecision() {
        assertEquals("24 мин 36 с", durationLabel(1476))
        assertEquals("0 с", durationLabel(-1))
    }
}
