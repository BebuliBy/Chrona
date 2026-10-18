package by.bebuliby.chrona.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import by.bebuliby.chrona.domain.*
import by.bebuliby.chrona.preview.DemoReducer
import by.bebuliby.chrona.preview.DemoState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal

/** Prepared, not run in the UI worktree. No screenshots, wake/unlock, intents or MainActivity dependency. */
class ChronaUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun navigationOrderAndProfileSettingsAreSeparate() {
        val state = mutableStateOf(DemoState.sample())
        compose.setContent { ChronaApp(state.value) { state.value = DemoReducer.reduce(state.value, it) } }
        compose.onNodeWithTag("demo_banner").assertIsDisplayed()
        MainTab.entries.forEach { compose.onNodeWithTag("tab_${it.name}").assertIsDisplayed() }
        compose.onNodeWithTag("tab_SETTINGS").assertDoesNotExist()
        compose.onNodeWithTag("profile_menu").performClick()
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithTag("theme_SYSTEM").performScrollTo().assertIsDisplayed()
    }

    @Test fun localModeNeverShowsDemoBannerOrFakeDisconnectedFriends() {
        val sample = DemoState.sample()
        // Malformed disconnected payload containing a fake list must not become a visible friendship.
        val fake = FriendSummary("fake", "Не должен показываться", null, FriendRelation.ACCEPTED)
        compose.setContent { ChronaApp(sample.copy(mode = DataMode.LOCAL, tab = MainTab.FRIENDS,
            friends = FriendsUiState(FeatureStatus.NOT_CONNECTED, friends = listOf(fake))), {}) }
        compose.onNodeWithTag("demo_banner").assertDoesNotExist()
        compose.onNodeWithTag("find_friends").assertIsDisplayed()
        compose.onNodeWithTag("friends_not_connected").assertIsDisplayed()
        compose.onNodeWithText(fake.displayName).assertDoesNotExist()
    }

    @Test fun numericManualValueCanExceedGoal() {
        val sample = DemoState.sample()
        val state = DemoReducer.reduce(sample, ChronaAction.OpenHabit("reading", sample.today))
        val actions = mutableListOf<ChronaAction>()
        compose.setContent { ChronaApp(state, actions::add) }
        compose.onNodeWithTag("edit_actual").performScrollTo().performClick()
        compose.onNodeWithTag("number_input").performTextReplacement("42,75")
        compose.onNodeWithTag("save_number").performClick()
        compose.runOnIdle {
            assertEquals(BigDecimal("42.75"), actions.filterIsInstance<ChronaAction.SaveManualValue>().single().value)
        }
    }

    @Test fun forgedAutomaticEditPermissionStillHasNoManualEditor() {
        val sample = DemoState.sample()
        val opened = DemoReducer.reduce(sample, ChronaAction.OpenHabit("shorts", sample.today))
        val state = opened.copy(details = opened.details!!.copy(cell = opened.details.cell.copy(canEditActual = true)))
        compose.setContent { ChronaApp(state, {}) }
        compose.onNodeWithTag("edit_actual").assertDoesNotExist()
        compose.onNodeWithTag("number_input").assertDoesNotExist()
    }

    @Test fun yearIsHonestAndStatisticsStillAvailable() {
        val sample = DemoState.sample()
        compose.setContent { ChronaApp(sample.copy(tab = MainTab.HISTORY, history = sample.history.copy(period = HistoryPeriod.YEAR)), {}) }
        compose.onNodeWithTag("open_statistics").assertIsDisplayed()
        compose.onNodeWithTag("year_layout_unselected").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("history_table").assertDoesNotExist()
    }

    @Test fun monthHasOneSharedHeaderAndBodyScrollContainer() {
        val sample = DemoState.sample()
        val range = periodRange(HistoryPeriod.MONTH, sample.today.date, sample.today.zoneId)
        compose.setContent { ChronaApp(sample.copy(tab = MainTab.HISTORY, history = sample.history.copy(period = HistoryPeriod.MONTH, range = range)), {}) }
        compose.onNodeWithTag("history_table").performScrollTo()
        compose.onNodeWithTag("sticky_habits").assertExists()
        compose.onAllNodesWithTag("history_scroll").assertCountEquals(1)
        compose.onNodeWithTag("history_scroll").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("sticky_habits").assertExists()
    }

    @Test fun confirmedEventIsAcknowledgedOnceNotOnEveryRecomposition() {
        val sample = DemoState.sample()
        val state = mutableStateOf(sample.copy(valueCommit = ValueCommit("event-1", "reading", sample.today)))
        val actions = mutableListOf<ChronaAction>()
        compose.setContent { ChronaApp(state.value, actions::add) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, actions.filterIsInstance<ChronaAction.AcknowledgeValueAnimation>().size) }
        compose.runOnIdle { state.value = state.value.copy(tracker = state.value.tracker.copy(status = TrackerStatus.OBSERVING)) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, actions.filterIsInstance<ChronaAction.AcknowledgeValueAnimation>().size) }
    }

    @Test fun authorizedFriendDetailNeverExposesEditorsEvenWithBadFlags() {
        val sample = DemoState.sample()
        val owner = FriendSummary("friend", "Друг · тестовый fixture", null, FriendRelation.ACCEPTED)
        val view = FriendActivityUiState(owner, sample.habits, sample.history, emptyList(), emptyList())
        compose.setContent { ChronaApp(sample.copy(tab = MainTab.FRIENDS, friends = FriendsUiState(FeatureStatus.READY,
            friends = listOf(owner), selected = view)), {}) }
        val day = sample.history.range.fromInclusive
        compose.onNodeWithTag("history_table").performScrollTo()
        compose.onNodeWithTag("cell_reading_$day").performClick()
        compose.onNodeWithTag("edit_actual").assertDoesNotExist()
        compose.onNodeWithTag("edit_goal").assertDoesNotExist()
        compose.onNodeWithTag("archive_habit").assertDoesNotExist()
    }

    @Test fun serverEditorIsAbsentAndOpeningSettingsDoesNotChangeConnection() {
        val actions = mutableListOf<ChronaAction>()
        compose.setContent { ChronaApp(DemoState.sample().copy(settingsOpen = true), actions::add) }
        compose.onNodeWithTag("server_base_url").assertDoesNotExist()
        compose.onNodeWithTag("save_server").assertDoesNotExist()
        compose.onNodeWithTag("confirm_server_change").assertDoesNotExist()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test fun existingAccountAndDestinationAreNotExposedOrReset() {
        val actions = mutableListOf<ChronaAction>()
        val sample = DemoState.sample()
        val state = sample.copy(settingsOpen = true, connection = ConnectionUiState("https://hidden.chrona.example.org:8443", false),
            profile = sample.profile.copy(accountId = "fixture-account", telegramLinked = true))
        compose.setContent { ChronaApp(state, actions::add) }
        compose.onNodeWithTag("logout").performScrollTo().assertIsEnabled()
        compose.onAllNodesWithText("hidden.chrona.example.org", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("8443", substring = true).assertCountEquals(0)
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test fun pendingLoginHasManualRefreshAndNoDuplicateLoginOrAutoSync() {
        val actions = mutableListOf<ChronaAction>()
        val state = DemoState.sample().copy(settingsOpen = true, connection = ConnectionUiState("https://api.chrona.example.org", true))
        compose.setContent { ChronaApp(state, actions::add) }
        compose.onNodeWithTag("telegram_login").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("refresh_login").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(ChronaAction.RefreshLoginStatus), actions) }
    }

    @Test fun manualSyncNeedsCheckboxAndConfirmationAndCancellationSendsNothing() {
        val actions = mutableListOf<ChronaAction>()
        val sample = DemoState.sample()
        val state = sample.copy(settingsOpen = true, connection = ConnectionUiState("https://api.chrona.example.org", false),
            profile = sample.profile.copy(accountId = "fixture-account", telegramLinked = true))
        compose.setContent { ChronaApp(state, actions::add) }
        compose.onNodeWithTag("request_sync").performScrollTo().performClick()
        compose.onNodeWithTag("confirm_sync").assertIsNotEnabled()
        compose.onAllNodesWithText("api.chrona.example.org", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("sync_consent_checkbox").performClick()
        compose.onNodeWithTag("cancel_sync").performClick()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        compose.onNodeWithTag("request_sync").performScrollTo().performClick()
        compose.onNodeWithTag("confirm_sync").assertIsNotEnabled()
        compose.onNodeWithTag("sync_consent_checkbox").performClick()
        compose.onNodeWithTag("confirm_sync").performClick()
        compose.runOnIdle { assertEquals(listOf(ChronaAction.RequestSync), actions) }
    }

    @Test fun consentIsInvalidatedWhenDestinationChanges() {
        val actions = mutableListOf<ChronaAction>()
        val sample = DemoState.sample()
        val state = mutableStateOf(sample.copy(settingsOpen = true, connection = ConnectionUiState("https://old.chrona.example.org", false),
            profile = sample.profile.copy(accountId = "fixture-account", telegramLinked = true)))
        compose.setContent { ChronaApp(state.value, actions::add) }
        compose.onNodeWithTag("request_sync").performScrollTo().performClick()
        compose.onNodeWithTag("sync_consent_checkbox").performClick()
        compose.runOnIdle { state.value = state.value.copy(connection = ConnectionUiState("https://new.chrona.example.org", false)) }
        compose.onNodeWithTag("confirm_sync").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test fun shortsLimitRemainsEditableInMinutesButActualHasNoEditor() {
        val sample = DemoState.sample()
        val opened = DemoReducer.reduce(sample, ChronaAction.OpenHabit("shorts", sample.today))
        val actions = mutableListOf<ChronaAction>()
        compose.setContent { ChronaApp(opened, actions::add) }
        compose.onNodeWithTag("edit_actual").assertDoesNotExist()
        compose.onNodeWithTag("edit_goal").performScrollTo().performClick()
        compose.onNodeWithText("Минуты").assertExists()
        compose.onNodeWithTag("number_input").performTextReplacement("1,5")
        compose.onNodeWithTag("save_number").performClick()
        compose.runOnIdle {
            assertEquals(BigDecimal("90000.0"), actions.filterIsInstance<ChronaAction.SaveGoal>().single().amount)
            assertTrue(actions.none { it is ChronaAction.SaveManualValue })
        }
    }

    @Test fun routineSuccessAndHealthyTrackerDoNotDisplayDiagnosticsOrAcknowledgement() {
        val sample = DemoState.sample()
        compose.setContent { ChronaApp(sample.copy(mode = DataMode.LOCAL,
            tracker = TrackerHealth(TrackerStatus.OBSERVING, ScreenObservation.NOT_SHORTS, java.time.Instant.parse("2026-10-18T09:00:00Z")),
            notice = UiNotice("fixture-notice", "SYNC_SNAPSHOT_COMPLETE", "Подтверждённый снимок отправлен. Новые записи ждут следующего ручного согласия.")), {}) }
        compose.onNodeWithTag("ui_notice").assertDoesNotExist()
        for (text in listOf("Понятно", "Служба наблюдения активна", "Последнее состояние", "Последнее наблюдение", "2026-10-18T09:00:00Z"))
            compose.onAllNodesWithText(text, substring = true).assertCountEquals(0)
    }

    @Test fun actionableErrorsStayVisibleWithoutRawDebugText() {
        val actions = mutableListOf<ChronaAction>()
        compose.setContent { ChronaApp(DemoState.sample().copy(notice = UiNotice("permission", "PERMISSION_REQUIRED", "raw debug details")), actions::add) }
        compose.onNodeWithTag("ui_notice").assertTextContains("Разрешите отслеживание Shorts")
        compose.onNodeWithText("raw debug details").assertDoesNotExist()
        compose.onNodeWithContentDescription("Закрыть сообщение").performClick()
        compose.runOnIdle { assertEquals(listOf(ChronaAction.DismissNotice("permission")), actions) }
    }

    @Test fun historyTodayMarkerAndSelectedDateAreIndependent() {
        val sample = DemoState.sample()
        val past = sample.today.copy(date = sample.today.date.minusDays(1))
        compose.setContent { ChronaApp(sample.copy(tab = MainTab.HISTORY, selectedDay = past), {}) }
        compose.onNodeWithTag("today_marker_${sample.today.date}").assertExists().assertIsNotSelected()
        compose.onNodeWithTag("calendar_day_${past.date}").assertIsSelected()
        for (text in listOf("Видны 14 дней", "✓ — выполнение", "известных плановых ячеек"))
            compose.onAllNodesWithText(text, substring = true).assertCountEquals(0)
    }

    @Test fun monthTodayMarkerDoesNotDependOnCompletionColor() {
        val sample = DemoState.sample()
        val month = sample.history.copy(period = HistoryPeriod.MONTH,
            range = periodRange(HistoryPeriod.MONTH, sample.today.date, sample.today.zoneId), cells = emptyList())
        compose.setContent { ChronaApp(sample.copy(tab = MainTab.HISTORY, history = month), {}) }
        compose.onNodeWithTag("today_marker_${sample.today.date}").assertExists().assertIsSelected()
    }

    @Test fun settingsTitleUsesOneLineWithALongProfileInNarrowWidth() {
        val sample = DemoState.sample()
        compose.setContent { Box(Modifier.width(320.dp)) {
            ChronaApp(sample.copy(settingsOpen = true, profile = sample.profile.copy(displayName = "Очень длинное имя профиля для проверки ширины")), {})
        } }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("screen_title").assertTextEquals("Настройки")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        compose.runOnIdle { assertEquals(1, layouts.single().lineCount); assertFalse(layouts.single().isLineEllipsized(0)) }
    }

    @Test fun todayDateMarkerRemainsVisibleWhenAnotherDayIsSelected() {
        val sample = DemoState.sample()
        val actions = mutableListOf<ChronaAction>()
        compose.setContent { ChronaApp(sample.copy(selectedDay = sample.today.copy(date = sample.today.date.minusDays(1))), actions::add) }
        compose.onNodeWithTag("today_marker_${sample.today.date}").assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals(listOf(ChronaAction.SelectDay(sample.today)), actions) }
    }

    @Test fun intervalClockAndDurationAreReadableWithoutCoverageOrGapDiagnostics() {
        val sample = DemoState.sample()
        val start = java.time.Instant.parse("2026-10-18T06:00:00.001Z")
        val end = java.time.Instant.parse("2026-10-18T06:01:01.999Z")
        val interval = IntervalView("fixture-interval", "shorts", start, end, 61_998, IntervalQuality.OBSERVED)
        val gap = ObservationGap("fixture-gap", "shorts", end, null, GapReason.UNKNOWN_SCREEN)
        val state = sample.copy(details = HabitDetailsUiState("shorts", sample.todayCells.first(), listOf(interval), listOf(gap)))
        compose.setContent { ChronaApp(state, {}) }
        compose.onNodeWithText("09:00:00 → 09:01:01").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1 мин 1 с").assertIsDisplayed()
        for (text in listOf("Полнота данных", "Разрывы наблюдения", "Неполное наблюдение", ".999", "2026-10-18T"))
            compose.onAllNodesWithText(text, substring = true).assertCountEquals(0)
        compose.runOnIdle { assertEquals(listOf(gap), state.details!!.gaps) }
    }
}
