package by.bebuliby.chrona.preview

import by.bebuliby.chrona.domain.*
import by.bebuliby.chrona.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Preview/formatting checks only, not proof of core storage/scoring correctness. */
class DemoReducerTest {
    private val initial = DemoState.sample()
    private fun detail(id: String) = DemoReducer.reduce(initial, ChronaAction.OpenHabit(id, initial.today))

    private fun connected() = initial.copy(mode=DataMode.CONNECTED,
        profile=initial.profile.copy(accountId="owner-account",telegramLinked=true),
        connection=ConnectionUiState("https://api.chrona.example.org"),
        friends=FriendsUiState(FeatureStatus.READY,listOf(FriendSummary("accepted-friend","Друг",null,FriendRelation.ACCEPTED))))

    @Test fun discoverabilityActionIsExplicitDemoMemoryOnlyAndNeverChangesLocalAuthority() {
        assertFalse(initial.connection.discoverable)
        val next = DemoReducer.reduce(initial,ChronaAction.ConfigureDiscoverability(true))
        assertTrue(next.connection.discoverable); assertEquals("DEMO_ONLY",next.notice?.code)
        assertEquals(initial.profile,next.profile); assertEquals(initial.friends,next.friends); assertEquals(initial.sync,next.sync)
        val local = initial.copy(mode=DataMode.LOCAL)
        assertEquals(local,DemoReducer.reduce(local,ChronaAction.ConfigureDiscoverability(true)))
    }

    @Test fun sharingRequiresBothExplicitIsoDatesAndIanaZone() {
        for ((from,to,zone) in listOf(Triple("","2026-10-19","UTC"),Triple("2026-10-18","","UTC"),
            Triple("2026-02-30","2026-10-19","UTC"),Triple("2026-10-19","2026-10-18","UTC"),
            Triple("2026-10-18","2026-10-18","UTC"),Triple("2026-10-18","2026-10-19","+03:00")))
            assertNull(explicitSharingWindow(from,to,zone))
    }

    @Test fun sharingWindowIsEndExclusiveAndUsesZoneRatherThanFixed24HourDay() {
        val window = explicitSharingWindow("2026-03-29","2026-03-30","Europe/Berlin")!!
        assertEquals(23*3600L,java.time.Duration.between(window.fromInclusive,window.toExclusive).seconds)
        assertEquals(java.time.Instant.parse("2026-03-28T23:00:00Z"),window.fromInclusive)
    }

    @Test fun sharingCannotAutomaticallySaveWithoutFreshConsentEvenWithValidDates() {
        val state = connected(); val target = sharingConsentTarget(state,"accepted-friend")!!
        assertNull(confirmedSharingAction(state,target,setOf("reading"),"2026-10-18","2026-10-19","UTC",false))
        val submitted = confirmedSharingAction(state,target,setOf("reading"),"2026-10-18","2026-10-19","UTC",true)!!
        assertEquals(setOf("reading"),submitted.grant.habitIds); assertNull(submitted.grant.expectedRevision)
    }

    @Test fun sharingIsOnlyForAcceptedFriendAndBoundUnchangedAccountOrigin() {
        val state = connected(); val target = sharingConsentTarget(state,"accepted-friend")!!
        assertNull(sharingConsentTarget(state.copy(mode=DataMode.LOCAL),"accepted-friend"))
        assertNull(sharingConsentTarget(state.copy(friends=state.friends.copy(friends=listOf(state.friends.friends.single().copy(relation=FriendRelation.INCOMING)))),"accepted-friend"))
        for (changed in listOf(state.copy(connection=state.connection.copy(baseUrl="https://other.chrona.example.org")),
            state.copy(profile=state.profile.copy(accountId="other-account")),state.copy(connection=state.connection.copy(loginPending=true))))
            assertNull(confirmedSharingAction(changed,target,setOf("reading"),"2026-10-18","2026-10-19","UTC",true))
    }

    @Test fun sharingEmptySetRevokesAndNewHabitsAreNeverAutomaticallyIncluded() {
        val state = connected(); val target = sharingConsentTarget(state,"accepted-friend")!!
        assertNull(confirmedSharingAction(state,target,setOf("not-my-habit"),"2026-10-18","2026-10-19","UTC",true))
        val revoke = confirmedSharingAction(state,target,emptySet(),"2026-10-18","2026-10-19","UTC",true)!!
        assertTrue(revoke.grant.habitIds.isEmpty())
        val expanded = state.copy(habits=state.habits + state.habits.first().copy(id="new-habit"))
        val keep = confirmedSharingAction(expanded,target,setOf("reading"),"2026-10-18","2026-10-19","UTC",true)!!
        assertEquals(setOf("reading"),keep.grant.habitIds)
    }

    @Test fun noFixtureSeedingOutsideDemo() {
        val local = initial.copy(mode = DataMode.LOCAL, habits = emptyList(), todayCells = emptyList())
        assertEquals(local, DemoReducer.reduce(local, ChronaAction.Navigate(MainTab.HABITS)))
    }
    @Test fun overgoalIsNotCapped() {
        val result = DemoReducer.reduce(detail("reading"), ChronaAction.SaveManualValue("reading", initial.today, BigDecimal("12000.75")))
        assertEquals(BigDecimal("12000.75"), (result.details!!.cell.actual as ActualValue.Known).value)
        assertNotNull(result.valueCommit)
    }
    @Test fun manualZeroIsARealValue() {
        val result = DemoReducer.reduce(detail("reading"), ChronaAction.SaveManualValue("reading", initial.today, BigDecimal.ZERO))
        assertEquals(BigDecimal.ZERO, (result.details!!.cell.actual as ActualValue.Known).value)
    }
    @Test fun negativeValueIsRejected() {
        val state = detail("reading")
        assertEquals(state, DemoReducer.reduce(state, ChronaAction.SaveManualValue("reading", initial.today, BigDecimal("-1"))))
    }
    @Test fun automaticFactIsRejectedEvenIfUiFlagIsForged() {
        val state = detail("shorts").let { it.copy(details = it.details!!.copy(cell = it.details.cell.copy(canEditActual = true))) }
        assertEquals(state, DemoReducer.reduce(state, ChronaAction.SaveManualValue("shorts", initial.today, BigDecimal.ZERO)))
    }
    @Test fun onlyMatchingCommitAcknowledged() {
        val state = initial.copy(valueCommit = ValueCommit("event-1", "reading", initial.today))
        assertEquals(state, DemoReducer.reduce(state, ChronaAction.AcknowledgeValueAnimation("other")))
        assertNull(DemoReducer.reduce(state, ChronaAction.AcknowledgeValueAnimation("event-1")).valueCommit)
    }
    @Test fun archiveDoesNotEraseHistory() {
        val result = DemoReducer.reduce(initial, ChronaAction.ArchiveHabit("reading"))
        assertNotNull(result.habits.first { it.id == "reading" }.archivedAt)
        assertEquals(initial.history.cells, result.history.cells)
    }
    @Test fun loginDoesNotCreateFakeBindingOrFriends() {
        val result = DemoReducer.reduce(initial, ChronaAction.BeginTelegramLogin)
        assertFalse(result.profile.telegramLinked)
        assertEquals(FeatureStatus.NOT_CONNECTED, result.friends.status)
        assertTrue(result.friends.friends.isEmpty())
        assertNotNull(result.notice)
    }
    @Test fun creationDoesNotInventKnownZeroOrStreak() {
        val result = DemoReducer.reduce(initial, ChronaAction.CreateManualHabit("Новая", ValueUnit("count", "раз"),
            GoalDirection.AT_LEAST, BigDecimal.ONE, initial.today))
        val cell = result.todayCells.last()
        assertTrue(cell.actual is ActualValue.Unknown)
        assertTrue(cell.streak is Computed.Unavailable)
    }
    @Test fun knownZeroAndZeroLimitAreNotDivisionByZero() {
        val created = DemoReducer.reduce(initial, ChronaAction.CreateManualHabit("Ограничение", ValueUnit("count", "раз"),
            GoalDirection.AT_MOST, BigDecimal.ZERO, initial.today))
        val id = created.habits.last().id
        val opened = DemoReducer.reduce(created, ChronaAction.OpenHabit(id, initial.today))
        val result = DemoReducer.reduce(opened, ChronaAction.SaveManualValue(id, initial.today, BigDecimal.ZERO))
        assertEquals(0, (result.details!!.cell.score as Computed.Ready).value.compareTo(BigDecimal.ONE))
        assertTrue(result.details.cell.usagePercent is Computed.Unavailable)
        assertTrue(result.details.cell.emotion is Computed.Unavailable)
    }
    @Test fun localCommaIsParsedWithoutFloatOrGoalCap() {
        assertEquals(BigDecimal("12345678901234567890.125"), parseDayNumber(" 12345678901234567890,125 "))
        assertEquals(BigDecimal.ZERO, parseDayNumber("0"))
        assertEquals(BigDecimal("0.25"), parseDayNumber(".25"))
    }
    @Test fun malformedNumbersAreNotCommands() {
        listOf("", "-1", "1e8", "NaN", "Infinity", "1,2,3", "1 000", "+1").forEach { assertNull(it, parseDayNumber(it)) }
    }
    @Test fun paletteAndStyleIdsRemainStableAndUnique() {
        assertEquals(12, ChronaDesign.palettes.size)
        assertEquals(12, ChronaDesign.palettes.map { it.id }.distinct().size)
        assertEquals(setOf("standard", "soft", "editorial"), ChronaDesign.styles.map { it.id }.toSet())
        assertEquals("ocean", ChronaDesign.palette("unknown").id)
        assertEquals("lilac", ChronaDesign.palette("lilac").id)
        assertEquals("standard", ChronaDesign.style("unknown").id)
    }
    @Test fun fractionalScoreIsNotRoundedBeforeColor() {
        assertEquals(ChronaDesign.scoreLow, periodScoreColor(BigDecimal.ZERO))
        assertEquals(ChronaDesign.scoreMid, periodScoreColor(BigDecimal(50)))
        assertEquals(ChronaDesign.scoreHigh, periodScoreColor(BigDecimal(100)))
        assertNotEquals(periodScoreColor(BigDecimal("50.1")), periodScoreColor(BigDecimal("50.4")))
    }
    @Test fun curveHasNoBridgeAcrossUnavailable() {
        val day = initial.today
        val first = CurvePoint(day, Computed.Ready(BigDecimal(20)))
        val gap = CurvePoint(day.copy(date = day.date.plusDays(1)), Computed.Unavailable(UnavailableReason.PARTIAL_COVERAGE))
        val last = CurvePoint(day.copy(date = day.date.plusDays(2)), Computed.Ready(BigDecimal(60)))
        assertEquals(listOf(listOf(first), listOf(last)), contiguousCurveRuns(listOf(first, gap, last)))
    }
    @Test fun weekMonthAndYearNavigationPreserveExplicitZone() {
        val anchor = LocalDate.of(2026, 10, 18)
        assertEquals(DayRange(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 19), "Europe/Minsk"), periodRange(HistoryPeriod.WEEK, anchor, "Europe/Minsk"))
        assertEquals(LocalDate.of(2026, 11, 1), periodRange(HistoryPeriod.MONTH, anchor, "Europe/Minsk").toExclusive)
        assertEquals(LocalDate.of(2027, 1, 1), periodRange(HistoryPeriod.YEAR, anchor, "Europe/Minsk").toExclusive)
    }
    @Test fun fixtureCumulativeReadingEndsAt140Not170() {
        val last = initial.history.summary.curves.first { it.habitId == "reading" }.points.last().cumulative as Computed.Ready
        assertEquals(BigDecimal(140), last.value)
    }
    @Test fun partialCellLabelNeverRoundsIntoFullOrZero() {
        assertEquals("<0,1%", scoreLabel(Computed.Ready(BigDecimal("0.0000001"))))
        assertEquals(">99,9%", scoreLabel(Computed.Ready(BigDecimal("0.9999999"))))
        assertEquals("✓", scoreLabel(Computed.Ready(BigDecimal.ONE)))
        assertEquals("×", scoreLabel(Computed.Ready(BigDecimal.ZERO)))
    }
    @Test fun scoreInkMeetsNormalTextContrastAcrossGradient() {
        (0..1000).forEach { step ->
            val background = periodScoreColor(BigDecimal(step).movePointLeft(1))
            val foreground = scoreContentColor(background)
            val high = maxOf(background.luminance(), foreground.luminance())
            val low = minOf(background.luminance(), foreground.luminance())
            assertTrue("Score $step", (high + .05f) / (low + .05f) >= 4.5f)
        }
    }
    @Test fun paletteAccentTextContrastIsReadableInBothModes() {
        ChronaDesign.palettes.forEach { palette ->
            val lightContrast = 1.05f / (palette.lightAccent.luminance() + .05f)
            val darkContrast = (palette.darkAccent.luminance() + .05f) / (Color(0xFF182331).luminance() + .05f)
            assertTrue(palette.id, lightContrast >= 4.5f && darkContrast >= 4.5f)
        }
    }
    @Test fun durationPresentationDoesNotRoundSmallKnownValueToZero() {
        assertNotEquals("0", presentedValue(BigDecimal.ONE, ValueUnit("duration_ms", "мс")))
    }
    @Test fun serverConfigurationIsExplicitDemoOnlyWithoutFakeLoginOrSync() {
        val next = DemoReducer.reduce(initial, ChronaAction.ConfigureServer("https://API.CHRONA.EXAMPLE.ORG/"))
        assertEquals("https://api.chrona.example.org", next.connection.baseUrl)
        assertEquals(DataMode.DEMO, next.mode)
        assertFalse(next.connection.loginPending)
        assertFalse(next.profile.telegramLinked)
        assertEquals(initial.todayCells, next.todayCells)
        assertEquals(initial.sync, next.sync)
        assertEquals("DEMO_ONLY", next.notice?.code)
        val login = DemoReducer.reduce(next, ChronaAction.BeginTelegramLogin)
        assertFalse(login.profile.telegramLinked)
        assertEquals(next.sync, login.sync)
    }
    @Test fun demoOriginChangeClearsBindingButPreservesLocalDataAndOutboxCount() {
        val localPhoto = ProfilePhoto(PhotoKind.LOCAL_URI, "content://test-fixture/avatar")
        val prior = initial.copy(connection = ConnectionUiState("https://old.chrona.example.org", true),
            profile = initial.profile.copy(accountId = "fixture-account", telegramLinked = true, photo = localPhoto),
            sync = SyncState(SyncStatus.ERROR, java.time.Instant.parse("2026-10-18T09:00:00Z"), 9))
        val next = DemoReducer.reduce(prior, ChronaAction.ConfigureServer("https://new.chrona.example.org"))
        assertNull(next.profile.accountId)
        assertFalse(next.profile.telegramLinked)
        assertFalse(next.connection.loginPending)
        assertEquals(localPhoto, next.profile.photo)
        assertEquals(prior.todayCells, next.todayCells)
        assertEquals(prior.history, next.history)
        assertEquals(9, next.sync.pendingOperations)
        assertNull(next.sync.lastSuccessAt)
        assertEquals(FeatureStatus.NOT_CONNECTED, next.friends.status)
    }
    @Test fun demoRejectsInvalidServerWithoutMutatingConnectionOrAccount() {
        val next = DemoReducer.reduce(initial, ChronaAction.ConfigureServer("http://localhost"))
        assertEquals(initial.connection, next.connection)
        assertEquals(initial.profile, next.profile)
        assertEquals(initial.sync, next.sync)
        assertEquals("VALIDATION", next.notice?.code)
    }
    @Test fun manualSyncGateRequiresSavedServerAccountAndNoPendingLoginOrBusySync() {
        val ready = initial.copy(connection = ConnectionUiState("https://api.chrona.example.org", false),
            profile = initial.profile.copy(telegramLinked = true, accountId = "fixture-account"))
        assertTrue(canRequestManualSync(ready))
        assertTrue(canRequestManualSync(ready.copy(sync = ready.sync.copy(status = SyncStatus.OFFLINE))))
        assertFalse(canRequestManualSync(initial))
        assertFalse(canRequestManualSync(ready.copy(connection = ready.connection.copy(loginPending = true))))
        assertFalse(canRequestManualSync(ready.copy(busy = true)))
        assertFalse(canRequestManualSync(ready.copy(profile = ready.profile.copy(accountId = null))))
        assertFalse(canRequestManualSync(ready.copy(sync = ready.sync.copy(status = SyncStatus.SYNCING))))
    }
}
