package by.bebuliby.chrona.preview

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import by.bebuliby.chrona.domain.*
import by.bebuliby.chrona.ui.ChronaApp
import by.bebuliby.chrona.ui.ChronaTheme
import by.bebuliby.chrona.ui.EmotionFace
import by.bebuliby.chrona.ui.label
import java.math.BigDecimal
import java.time.Instant

@Composable
fun ChronaDemoApp(model: DemoViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    ChronaApp(state, model::dispatch)
}

@Preview(name = "Today · light", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun TodayLight() = ChronaApp(DemoState.sample().copy(preferences = AppPreferences(ThemeMode.LIGHT, "ocean", "standard")), {})

@Preview(name = "Today · dark · soft", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun TodayDark() = ChronaApp(DemoState.sample().copy(preferences = AppPreferences(ThemeMode.DARK, "lilac", "soft")), {})

@Preview(name = "History · week", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun HistoryWeek() = ChronaApp(DemoState.sample().copy(tab = MainTab.HISTORY), {})

@Preview(name = "Complete manual week · score and seven levels", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun CompleteManualWeek() {
    val sample = DemoState.sample()
    val manual = sample.habits.filter { it.id in setOf("reading", "walk") }
    val days = sample.history.summary.curves.first().points.map { it.day }
    val general = CumulativeCurve(null, ValueUnit("plan_shares", "доли плана"),
        days.zip(listOf("0", "0.25", "0.75", "1.5", "2.5", "4", "6")).map { (day, value) -> CurvePoint(day, Computed.Ready(BigDecimal(value))) })
    val walk = CumulativeCurve("walk", manual.last().unit,
        days.zip(listOf(-20, -35, -45, -50, -50, -40, -20)).map { (day, value) -> CurvePoint(day, Computed.Ready(BigDecimal(value))) })
    ChronaApp(sample.copy(tab = MainTab.HISTORY, habits = manual, history = sample.history.copy(
        cells = sample.history.cells.filter { it.habitId in manual.map { habit -> habit.id } },
        summary = PeriodSummary(14, 14, Computed.Ready(BigDecimal("82.142857142857142857")),
            listOf(general, walk) + sample.history.summary.curves))), {})
}

@Preview(name = "Shorts · partial coverage · intervals", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun ShortsIntervals() {
    val sample = DemoState.sample()
    val interval = IntervalView("demo-interval", "shorts", Instant.parse("2026-10-18T06:12:00Z"),
        Instant.parse("2026-10-18T06:18:00Z"), 360_000, IntervalQuality.OBSERVED)
    val cell = sample.todayCells.first().copy(actual = ActualValue.Partial(BigDecimal(360_000), FactSource.COLLECTOR))
    val gap = ObservationGap("demo-gap", "shorts", interval.endUtc, null, GapReason.UNKNOWN_SCREEN)
    ChronaApp(sample.copy(details = HabitDetailsUiState("shorts", cell, listOf(interval), listOf(gap))), {})
}

@Preview(name = "History · month · 14 day viewport", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun HistoryMonth() {
    val sample = DemoState.sample()
    ChronaApp(sample.copy(tab = MainTab.HISTORY, history = sample.history.copy(period = HistoryPeriod.MONTH,
        range = sample.history.range.copy(fromInclusive = sample.today.date.withDayOfMonth(1), toExclusive = sample.today.date.withDayOfMonth(1).plusMonths(1)))), {})
}

@Preview(name = "Local · empty · no fake friends", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun LocalEmpty() = ChronaApp(DemoState.sample().copy(mode = DataMode.LOCAL, habits = emptyList(), todayCells = emptyList(), tab = MainTab.FRIENDS), {})

@Preview(name = "Seven emotions", showBackground = true, widthDp = 390)
@Composable
private fun EmotionGallery() {
    ChronaTheme(AppPreferences(ThemeMode.LIGHT, "amber", "standard")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Emotion.entries.forEach { emotion ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EmotionFace(Computed.Ready(emotion))
                    Text(emotion.label(), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
