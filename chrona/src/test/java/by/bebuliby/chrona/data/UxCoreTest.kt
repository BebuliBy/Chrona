package by.bebuliby.chrona.data

import by.bebuliby.chrona.data.client.*
import by.bebuliby.chrona.domain.*
import by.bebuliby.chrona.presentation.LocalReadModels
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class UxCoreTest {
    companion object {
        private const val BASE = "https://bootstrap.chrona.example.org:8443/chrona"
        private const val OLD = "https://old.chrona.example.org"
        private const val ACCOUNT = "ec61cdb1-d543-422c-b39f-8360db762587"
        private const val PROFILE = "ced889d8-e17d-4f1f-9254-4877a6345a1c"
        private const val DEVICE = "a5691321-7be3-4e36-bb2f-325feec739a9"
    }
    private class MemoryVault(initial: PrivateConnection = PrivateConnection()) : SessionVault {
        var record = ConnectionRecordCodec.encode(initial)
        var writes = 0
        override suspend fun load() = ConnectionRecordCodec.decode(record)
        override suspend fun save(value: PrivateConnection) { record = ConnectionRecordCodec.encode(value); writes++ }
    }
    private fun noNetwork() = HttpTransport { throw AssertionError("Bootstrap/ordinary configuration must not call HTTP") }
    private fun identity() = flowOf(LocalSettingsSnapshot(PROFILE, DEVICE, AppPreferences(ThemeMode.SYSTEM, "ocean", "standard"), null, false))

    @Test fun buildAndRuntimeUseIdenticalStrictBaseUrlPolicy() {
        val module = listOf(File("chrona"), File(".")).first { File(it, "src/main/java/by/bebuliby/chrona/domain/ServerBaseUrl.kt").isFile }
        fun policy(file: File) = file.readText().substringAfter("// BEGIN CHRONA_BASE_URL_POLICY")
            .substringBefore("// END CHRONA_BASE_URL_POLICY").lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val runtime = policy(File(module, "src/main/java/by/bebuliby/chrona/domain/ServerBaseUrl.kt"))
        val build = policy(File(module, "build.gradle.kts"))
        assertTrue(runtime.isNotEmpty()); assertEquals(runtime, build)
    }
    @Test fun missingBootstrapStaysUnconfiguredWithoutWritesNetworkOrFakeDefault() = runTest {
        val vault = MemoryVault(); val auth = SessionClient(noNetwork(), vault) { _, _ -> }
        auth.initialize()
        assertEquals(BootstrapOutcome.NOT_SUPPLIED, auth.bootstrap("") { throw AssertionError("No supplied config") })
        assertEquals("", auth.state.value.origin); assertFalse(auth.hasSession()); assertEquals(0, vault.writes)
    }
    @Test fun validBootstrapPersistsOnlyTheFreshTargetAndIsIdempotent() = runTest {
        val vault = MemoryVault(); val auth = SessionClient(noNetwork(), vault) { _, _ -> }
        auth.initialize()
        assertEquals(BootstrapOutcome.APPLIED, auth.bootstrap("$BASE/") { true })
        assertEquals(BASE, vault.load().origin); assertNull(vault.load().session); assertNull(vault.load().pending)
        assertEquals(BootstrapOutcome.PRESERVED, auth.bootstrap(OLD) { throw AssertionError("Must not inspect/replace an existing target") })
        assertEquals(BASE, auth.state.value.origin); assertEquals(1, vault.writes)
    }
    @Test fun bootstrapNeverReplacesExistingTargetSessionOrPending() = runTest {
        val session = ClientSession(ACCOUNT, UUID.randomUUID().toString(), "a".repeat(43), "r".repeat(43), Instant.parse("2099-01-01T00:00:00Z"))
        val pending = PendingLogin(UUID.randomUUID().toString(), Pkce.verifier(), Instant.parse("2099-01-01T00:00:00Z"))
        for (original in listOf(PrivateConnection(OLD), PrivateConnection(OLD, session), PrivateConnection(OLD, pending = pending))) {
            val vault = MemoryVault(original); val before = vault.record
            val auth = SessionClient(noNetwork(), vault) { _, _ -> }; auth.initialize()
            assertEquals(BootstrapOutcome.PRESERVED, auth.bootstrap(BASE) { throw AssertionError("Preserve before DB callback") })
            assertEquals(before, vault.record); assertEquals(0, vault.writes)
        }
    }
    @Test fun existingBoundProfileWithEmptyVaultCannotBeBootstrappedToAnotherServer() = runTest {
        val vault = MemoryVault(); val auth = SessionClient(noNetwork(), vault) { _, _ -> }; auth.initialize()
        val dao = Proxy.newProxyInstance(ChronaDao::class.java.classLoader, arrayOf(ChronaDao::class.java)) { _, m, _ -> when (m.name) {
            "syncMeta" -> SyncMetaRow(origin = OLD, accountId = ACCOUNT)
            else -> throw AssertionError("Existing binding must not be touched: ${m.name}")
        } } as ChronaDao
        val tx = object : ClientTransactions { override suspend fun <T> inTransaction(block: suspend () -> T) = block() }
        val store = ClientSyncStore(dao, tx)
        assertEquals(BootstrapOutcome.BLOCKED_EXISTING_BINDING, auth.bootstrap(BASE, store::canBootstrapUnconfiguredProfile))
        assertEquals("", auth.state.value.origin); assertEquals(0, vault.writes)
        val client = AndroidClient(auth, store, identity(), backgroundScope)
        client.initialize(BASE); runCurrent()
        assertEquals("BOOTSTRAP_EXISTING_BINDING", client.state.value.notice!!.code)
        assertEquals(0, vault.writes)
        try { SyncWire.binding(OLD, ACCOUNT, BASE, ACCOUNT); fail() }
        catch (e: ClientFailure) { assertEquals("ACCOUNT_BINDING_CONFLICT", e.code) }
    }
    @Test fun invalidBootstrapCannotPersistOrLeakThroughTransport() = runTest {
        for (raw in listOf("http://invalid", "$BASE?token=NOT_REAL", "$BASE/%2e%2e", "$BASE//", "https://user:EXAMPLE@bootstrap.chrona.example.org")) {
            val vault = MemoryVault(); val auth = SessionClient(noNetwork(), vault) { _, _ -> }; auth.initialize()
            try { auth.bootstrap(raw) { true }; fail() } catch (e: ClientFailure) { assertEquals("INVALID_ORIGIN", e.code) }
            assertEquals(0, vault.writes); assertEquals("", auth.state.value.origin)
        }
    }
    @Test fun ordinaryEndpointEditIsBlockedInClientAsWellAsVmPolicy() = runTest {
        val dao = Proxy.newProxyInstance(ChronaDao::class.java.classLoader, arrayOf(ChronaDao::class.java)) { _, m, _ ->
            throw AssertionError("No DB access for user endpoint edit: ${m.name}")
        } as ChronaDao
        val tx = object : ClientTransactions { override suspend fun <T> inTransaction(block: suspend () -> T) = block() }
        val vault = MemoryVault(PrivateConnection(OLD)); val auth = SessionClient(noNetwork(), vault) { _, _ -> }; auth.initialize()
        val client = AndroidClient(auth, ClientSyncStore(dao, tx), identity(), backgroundScope)
        try { client.configure(BASE); fail() } catch (e: CoreFailure) { assertEquals(ManagedServerConfiguration.CODE, e.code) }
        assertEquals(OLD, vault.load().origin); assertEquals(0, vault.writes)
    }
    @Test fun freshClientBootstrapDoesNotLoginBindOrAuthorizeRawSync() = runTest {
        val dao = Proxy.newProxyInstance(ChronaDao::class.java.classLoader, arrayOf(ChronaDao::class.java)) { _, m, _ -> when (m.name) {
            "syncMeta", "uploadScope" -> null
            "wireRequests" -> emptyList<WireRow>()
            else -> throw AssertionError("Unexpected bootstrap mutation ${m.name}")
        } } as ChronaDao
        val tx = object : ClientTransactions { override suspend fun <T> inTransaction(block: suspend () -> T) = block() }
        val vault = MemoryVault(); val auth = SessionClient(noNetwork(), vault) { _, _ -> throw AssertionError("Bootstrap must not bind account") }
        val client = AndroidClient(auth, ClientSyncStore(dao, tx), identity(), backgroundScope)
        client.initialize(BASE); runCurrent(); advanceTimeBy(6_000); runCurrent()
        assertEquals(BASE, client.state.value.baseUrl); assertNull(client.state.value.profile)
        assertEquals(SyncStatus.LOCAL_ONLY, client.state.value.syncStatus); assertNull(vault.load().session)
    }

    private class RepositoryFixture {
        val habits = linkedMapOf<String, HabitRow>()
        val goals = mutableListOf<GoalRow>()
        val outbox = mutableListOf<OutboxRow>()
        val intervals = linkedMapOf<String, IntervalRow>()
        val gaps = linkedMapOf<String, GapRow>()
        var health: HealthRow? = null
        private val mutex = Mutex()
        val transactions = object : RepositoryTransactions {
            override suspend fun <T> inTransaction(block: suspend () -> T): T = mutex.withLock { block() }
        }
        val dao = Proxy.newProxyInstance(ChronaDao::class.java.classLoader, arrayOf(ChronaDao::class.java)) { _, m, a -> when (m.name) {
            "habit" -> habits[a!![0] as String]
            "saveHabit" -> { (a!![0] as HabitRow).let { habits[it.value.id] = it }; Unit }
            "habits" -> habits.values.toList()
            "insertGoal" -> { goals += a!![0] as GoalRow; Unit }
            "goals" -> goals.toList()
            "entries", "segments", "wireRequests" -> emptyList<Any>()
            "intervals" -> intervals.values.toList()
            "interval" -> intervals[a!![0] as String]
            "insertInterval" -> { (a!![0] as IntervalRow).let { intervals[it.value.id] = it }; Unit }
            "gaps" -> gaps.values.toList()
            "saveGap" -> { (a!![0] as GapRow).let { gaps[it.value.id] = it }; Unit }
            "checkpoint" -> null
            "health" -> health
            "saveHealth" -> { health = a!![0] as HealthRow; Unit }
            "enqueue" -> { outbox += a!![0] as OutboxRow; Unit }
            "pendingCount" -> outbox.size
            else -> throw AssertionError("Unexpected repository call ${m.name}")
        } } as ChronaDao
        fun repository() = ChronaRepository(dao, transactions, flowOf(Unit))
    }
    @Test fun freshShortsGoalCanBeSetInRawMsReloadedAndChangedToZeroWithoutManualActual() = runTest {
        val f = RepositoryFixture(); val repo = f.repository(); repo.initialize()
        val day = DayKey(LocalDate.now(), "UTC")
        val initial = repo.snapshot(); val habit = initial.habits.single()
        assertEquals(ChronaRepository.SHORTS_ID, habit.id); assertEquals(GoalDirection.AT_MOST, habit.direction)
        assertEquals("duration_ms", habit.unit.code); assertTrue(initial.goals.isEmpty())
        val cell = LocalReadModels.cell(habit, day, initial)
        assertTrue(cell.canEditGoal); assertFalse(cell.canEditActual); assertTrue(cell.actual is ActualValue.Unknown)
        val minutesAsRawMs = "15.5".toBigDecimal() * "60000".toBigDecimal()
        repo.setGoal(habit.id, minutesAsRawMs, day)
        val reloaded = f.repository().snapshot()
        val saved = LocalReadModels.cell(habit, day, reloaded)
        assertEquals(0, saved.goal!!.amount.compareTo("930000".toBigDecimal()))
        assertEquals(day, saved.goal.requestedFrom); assertTrue(saved.canEditGoal); assertFalse(saved.canEditActual)
        assertTrue(saved.actual is ActualValue.Unknown); assertTrue(saved.score is Computed.Unavailable)
        repo.setGoal(habit.id, "0".toBigDecimal(), day)
        val latest = f.repository().snapshot()
        assertEquals(2, latest.goals.size); assertNotEquals(latest.goals[0].id, latest.goals[1].id)
        assertEquals(0, LocalReadModels.cell(habit, day, latest).goal!!.amount.signum())
        assertNull(LocalReadModels.cell(habit, day.copy(date = day.date.plusDays(1)), latest).goal)
        assertTrue(latest.intervals.isEmpty()); assertTrue(latest.entries.isEmpty())
        val before = f.outbox.size
        try { repo.setManual(habit.id, day, "1".toBigDecimal()); fail() }
        catch (e: CoreFailure) { assertEquals("SOURCE_NOT_WRITABLE", e.code) }
        assertEquals(before, f.outbox.size)
        assertEquals(listOf("habit", "goal_revision", "goal_revision"), f.outbox.map { it.entityType })
    }
    @Test fun partialShortsActualDoesNotBlockGoalAndGoalWritePreservesIntervals() = runTest {
        val f = RepositoryFixture(); val repo = f.repository(); repo.initialize()
        val day = DayKey(LocalDate.now(), "UTC"); val start = day.date.atTime(10, 0).toInstant(java.time.ZoneOffset.UTC)
        val interval = ActivityInterval(UUID.randomUUID().toString(), ChronaRepository.SHORTS_ID, DEVICE, UUID.randomUUID().toString(),
            start, start.plusSeconds(5), 1000, 6000, 5000, "test", IntervalQuality.OBSERVED, IntervalEndReason.NOT_SHORTS)
        repo.record(CollectorBatch(intervals = listOf(interval), checkpoint = null,
            health = TrackerHealth(TrackerStatus.OBSERVING, ScreenObservation.NOT_SHORTS, start.plusSeconds(5))))
        val before = repo.snapshot(); val habit = before.habits.single()
        assertTrue(LocalReadModels.cell(habit, day, before).actual is ActualValue.Partial)
        assertTrue(LocalReadModels.cell(habit, day, before).canEditGoal)
        repo.setGoal(habit.id, "60000".toBigDecimal(), day)
        val after = f.repository().snapshot()
        assertEquals(before.intervals, after.intervals); assertEquals(before.entries, after.entries)
        val cell = LocalReadModels.cell(habit, day, after)
        assertTrue(cell.canEditGoal); assertFalse(cell.canEditActual); assertTrue(cell.actual is ActualValue.Partial)
        assertTrue(cell.emotion is Computed.Unavailable)
    }
}
