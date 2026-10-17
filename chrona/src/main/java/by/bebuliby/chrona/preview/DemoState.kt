package by.bebuliby.chrona.preview

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

enum class Page(val title: String) {
    TODAY("Сегодня"), HABITS("Привычки"), HISTORY("История"), SETTINGS("Настройки")
}
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class Palette { OCEAN, LILAC }

/** Design fixtures, deliberately NOT the production habit/streak domain. */
data class DemoHabit(
    val id: String,
    val title: String,
    val target: Int,
    val unit: String,
    val progress: Int = 0,
    val exampleStreak: Int = 0,
) {
    init { require(target in 1..9999); require(progress in 0..target) }
    val completed get() = progress >= target
    val fraction get() = progress.toFloat() / target
}

data class DemoInterval(val start: LocalTime, val seconds: Long) {
    val end: LocalTime get() = start.plusSeconds(seconds)
}

data class DemoState(
    val today: LocalDate,
    val selectedDate: LocalDate = today,
    val page: Page = Page.TODAY,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val palette: Palette = Palette.OCEAN,
    val habits: List<DemoHabit> = emptyList(),
    val intervals: List<DemoInterval> = emptyList(),
) {
    val totalSeconds get() = intervals.sumOf { it.seconds }
    val completedCount get() = habits.count { it.completed }

    companion object {
        fun sample(today: LocalDate = LocalDate.now()) = DemoState(
            today = today,
            habits = listOf(
                DemoHabit("reading", "Читать", 20, "страниц", 12, 4),
                DemoHabit("walk", "Прогулка", 20, "минут", 20, 7),
                DemoHabit("english", "Английский", 15, "минут", 0, 2),
            ),
            intervals = listOf(
                DemoInterval(LocalTime.of(9, 12), 360),
                DemoInterval(LocalTime.of(12, 40), 420),
                DemoInterval(LocalTime.of(18, 3), 480),
                DemoInterval(LocalTime.of(21, 10), 216),
            ),
        )
    }
}

sealed interface DemoAction {
    data class Navigate(val page: Page) : DemoAction
    data class SelectDate(val date: LocalDate) : DemoAction
    data class AddHabit(val id: String, val title: String, val target: Int, val unit: String) : DemoAction
    data class ChangeProgress(val id: String, val delta: Int) : DemoAction
    data class ToggleCompleted(val id: String) : DemoAction
    data class DeleteHabit(val id: String) : DemoAction
    data class ChangeTheme(val mode: ThemeMode) : DemoAction
    data class ChangePalette(val palette: Palette) : DemoAction
    data object ClearExamples : DemoAction
    data object ResetExamples : DemoAction
}

object DemoReducer {
    fun validHabit(title: String, target: Int?, unit: String) =
        title.trim().length in 1..60 && target != null && target in 1..9999 && unit.trim().length in 1..20

    fun reduce(state: DemoState, action: DemoAction): DemoState = when (action) {
        is DemoAction.Navigate -> state.copy(page = action.page)
        is DemoAction.SelectDate -> if (action.date > state.today || action.date < state.today.minusDays(6)) state
            else state.copy(selectedDate = action.date)
        is DemoAction.AddHabit -> if (!validHabit(action.title, action.target, action.unit) || state.habits.any { it.id == action.id }) state
            else state.copy(habits = state.habits + DemoHabit(action.id, action.title.trim(), action.target, action.unit.trim()))
        is DemoAction.ChangeProgress -> state.copy(habits = state.habits.map {
            if (it.id == action.id) it.copy(progress = (it.progress.toLong() + action.delta).coerceIn(0, it.target.toLong()).toInt()) else it
        })
        is DemoAction.ToggleCompleted -> state.copy(habits = state.habits.map {
            if (it.id == action.id) it.copy(progress = if (it.completed) 0 else it.target) else it
        })
        is DemoAction.DeleteHabit -> state.copy(habits = state.habits.filterNot { it.id == action.id })
        is DemoAction.ChangeTheme -> state.copy(theme = action.mode)
        is DemoAction.ChangePalette -> state.copy(palette = action.palette)
        DemoAction.ClearExamples -> state.copy(habits = emptyList(), intervals = emptyList())
        DemoAction.ResetExamples -> DemoState.sample(state.today).copy(page = state.page, theme = state.theme, palette = state.palette)
    }
}

fun durationLabel(seconds: Long): String {
    val duration = Duration.ofSeconds(seconds.coerceAtLeast(0))
    val minutes = duration.toMinutes()
    val rest = duration.seconds % 60
    return if (minutes == 0L) "$rest с" else if (rest == 0L) "$minutes мин" else "$minutes мин $rest с"
}
