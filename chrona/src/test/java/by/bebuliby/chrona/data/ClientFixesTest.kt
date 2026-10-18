package by.bebuliby.chrona.data

import by.bebuliby.chrona.data.client.*
import by.bebuliby.chrona.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ClientFixesTest {
    companion object {
        private const val BASE = "https://api.chrona.example.org"
        private const val ACCOUNT = "84e5aac6-dcb5-4b52-931c-8a13d6e740eb"
        private const val DEVICE = "e0ff5f4b-8f6a-4b44-8eb0-b14fc8b72cb7"
        private const val PROFILE = "e9f68e39-e160-44e8-a918-7f323863ed26"
        private const val SERVER_SHORTS = "d06d2c2b-1a3b-4b0d-a798-653d7370e201"
        private const val MANUAL = "e5eaaf78-752c-4229-95ba-630373fb0ef5"
        private val start = Instant.parse("2026-10-18T10:00:00Z")
    }
    private class RecordVault(initial: PrivateConnection = PrivateConnection()) : SessionVault {
        var record = ConnectionRecordCodec.encode(initial)
        override suspend fun load() = ConnectionRecordCodec.decode(record)
        override suspend fun save(value: PrivateConnection) { record = ConnectionRecordCodec.encode(value) }
    }
    private fun session() = ClientSession(ACCOUNT, UUID.randomUUID().toString(), "a".repeat(43), "r".repeat(43), Instant.parse("2099-01-01T00:00:00Z"))
    private suspend fun expect(code: String, block: suspend () -> Unit) {
        try { block(); fail("Expected $code") } catch (e: ClientFailure) { assertEquals(code, e.code) }
    }
    private fun ack(wire: WireRow, revision: String = "100") = JSONObject().put("results", JSONArray().put(JSONObject()
        .put("operationId", wire.outboxId).put("status", "accepted").put("revision", revision)))

    @Test fun bootstrapSessionTransportShareRootPortAndBasePathContract() = runTest {
        for (input in listOf("$BASE/", "$BASE:8443/", "$BASE:8443/chrona/", "$BASE/chrona/nested/")) {
            val target = ServerBaseUrl.normalize(input)!!
            val vault = RecordVault()
            val calls = mutableListOf<HttpRequest>()
            val id = UUID.randomUUID().toString()
            val client = SessionClient(HttpTransport { r ->
                calls += r
                assertEquals(target + "/v1/auth/telegram/challenges", r.resolvedUrl().toASCIIString())
                HttpReply(201, JSONObject().put("requestId", id).put("expiresAt", "2099-01-01T00:00:00Z")
                    .put("telegramStartUrl", "https://t.me/chrona_bot?start=$id").toString())
            }, vault) { _, _ -> }
            client.initialize(); client.bootstrap(target) { true }; client.begin(DEVICE)
            assertEquals(target, vault.load().origin)
            assertEquals(1, calls.size)
            assertFalse(calls.single().body!!.contains("codeVerifier"))
            val restored = SessionClient(HttpTransport { throw AssertionError("No automatic network") }, vault) { _, _ -> }
            restored.initialize(); assertEquals(target, restored.state.value.origin)
            assertTrue(restored.state.value.loginPending)
        }
    }
    @Test fun vaultCodecNormalizesTargetAndRoundTripsBoundSessionAndPending() = runTest {
        for (target in listOf("HTTPS://API.CHRONA.EXAMPLE.ORG:443/chrona/", "$BASE:8443/")) {
            val pending = PendingLogin(UUID.randomUUID().toString(), Pkce.verifier(), Instant.parse("2099-01-01T00:00:00Z"))
            val value = PrivateConnection(target, session(), pending)
            val restored = ConnectionRecordCodec.decode(ConnectionRecordCodec.encode(value))
            assertEquals(ServerOrigin.parse(target), restored.origin)
            assertEquals(value.session!!.access, restored.session!!.access)
            assertEquals(pending.verifier, restored.pending!!.verifier)
        }
        expect("SECURE_STORAGE") { ConnectionRecordCodec.normalize(PrivateConnection("", session())) }
    }
    @Test fun prefixChangeClearsCredentialsButEquivalentNormalizationDoesNot() = runTest {
        val vault = RecordVault(PrivateConnection("$BASE/chrona", session(),
            PendingLogin(UUID.randomUUID().toString(), Pkce.verifier(), Instant.parse("2099-01-01T00:00:00Z"))))
        val auth = SessionClient(HttpTransport { throw AssertionError("Configure is not network IO") }, vault) { _, _ -> }
        auth.initialize()
        assertFalse(auth.configure("HTTPS://API.CHRONA.EXAMPLE.ORG:443/chrona/"))
        assertNotNull(vault.load().session)
        assertTrue(auth.configure("$BASE/other"))
        assertNull(vault.load().session); assertNull(vault.load().pending)
        expect("LOGIN_REQUIRED") { auth.authorized("GET", "/v1/me") }
    }
    @Test fun unsafeBasesAndRoutesAreRejectedBySharedPolicyAndTransportBuilder() = runTest {
        val bad = listOf("$BASE//", "$BASE/chrona//", "$BASE/a//b", "$BASE/.", "$BASE/a/../b", "$BASE/%2e%2e",
            "$BASE/%252e%252e", "$BASE/a%2fb", "$BASE/a\\b", "$BASE?token=EXAMPLE", "$BASE#x", "https://u:p@api.chrona.example.org",
            "http://api.chrona.example.org", "https://api.telegram.org", "$BASE/token/EXAMPLE")
        for (url in bad) {
            assertNull(url, ServerBaseUrl.normalize(url))
            expect("INVALID_ORIGIN") { ServerOrigin.parse(url) }
        }
        for (route in listOf("//evil.example.org/v1/me", "/v1/../me", "/v1/%2e%2e/me", "/v1//me", "/v1/me#x"))
            expect("INVALID_ORIGIN") { ServerOrigin.requestUrl("$BASE/chrona", route) }
        assertEquals("$BASE/chrona/v1/friends?q=a%2Fb", ServerOrigin.requestUrl("$BASE/chrona/", "/v1/friends?q=a%2Fb").toASCIIString())
    }
    @Test fun oldBindingToAnotherPrefixOrAccountCannotRebind() = runTest {
        expect("ACCOUNT_BINDING_CONFLICT") { SyncWire.binding("$BASE/old", ACCOUNT, "$BASE/chrona", ACCOUNT) }
        expect("ACCOUNT_BINDING_CONFLICT") { SyncWire.binding(BASE, ACCOUNT, BASE, UUID.randomUUID().toString()) }
    }

    private class Fixture {
        var meta = SyncMetaRow(origin = BASE, accountId = ACCOUNT, serverShortsId = SERVER_SHORTS)
        var scope: UploadScopeRow? = null
        val members = linkedMapOf<String, ApprovedUploadRow>()
        val outbox = linkedMapOf<String, OutboxRow>()
        val wires = linkedMapOf<String, WireRow>()
        val remotes = mutableMapOf<String, RemoteRow>("habit:$MANUAL" to RemoteRow("habit:$MANUAL", "1", "{}"))
        val gaps = mutableMapOf<String, GapRow>()
        val entries = mutableMapOf<String, ManualRow>()
        private val lock = Mutex()
        val transactions = object : ClientTransactions {
            override suspend fun <T> inTransaction(block: suspend () -> T): T = lock.withLock { block() }
        }
        val dao = Proxy.newProxyInstance(ChronaDao::class.java.classLoader, arrayOf(ChronaDao::class.java)) { _, m, a ->
            when (m.name) {
                "syncMeta" -> meta
                "saveSyncMeta" -> { meta = a!![0] as SyncMetaRow; Unit }
                "uploadScope" -> scope
                "saveUploadScope" -> { scope = a!![0] as UploadScopeRow; Unit }
                "deleteUploadScope" -> { scope = null; Unit }
                "approvals" -> members.values.filter { it.scopeId == a!![0] }
                "insertApprovals" -> { @Suppress("UNCHECKED_CAST") val rows = a!![0] as List<ApprovedUploadRow>
                    rows.forEach { members[it.operationId] = it }; Unit }
                "deleteApproval" -> { members.remove(a!![0] as String); Unit }
                "deleteApprovals" -> { members.entries.removeAll { it.value.scopeId == a!![0] }; Unit }
                "outbox" -> outbox.values.toList()
                "pendingCount" -> outbox.size
                "deleteOutbox" -> { outbox.remove(a!![0] as String); Unit }
                "wireRequests" -> wires.values.toList()
                "insertWire", "saveWire" -> { (a!![0] as WireRow).let { wires[it.requestId] = it }; Unit }
                "deleteWire" -> { wires.remove(a!![0] as String); Unit }
                "remote" -> remotes[a!![0] as String]
                "saveRemote" -> { (a!![0] as RemoteRow).let { remotes[it.key] = it }; Unit }
                "markApplied" -> Unit
                "entryById" -> entries[a!![0] as String]
                "saveEntry" -> { (a!![0] as ManualRow).let { entries[it.value.id] = it }; Unit }
                "saveGap" -> { (a!![0] as GapRow).let { gaps[it.value.id] = it }; Unit }
                "toString" -> "BoundedConsentFixture"
                else -> throw AssertionError("Unexpected DAO: ${m.name}")
            }
        } as ChronaDao
        val store = ClientSyncStore(dao, transactions)
        fun addGap(): String {
            val id = UUID.randomUUID().toString(); val operation = UUID.randomUUID().toString()
            val gap = ObservationGap(id, ChronaRepository.SHORTS_ID, start, null, GapReason.UNKNOWN_SCREEN)
            gaps[id] = GapRow(gap)
            outbox[operation] = OutboxRow(operation, "observation_gap", id, baseRevision = null, createdAt = start,
                payload = JSONObject().put("id", id).put("habitId", gap.habitId).put("startUtc", start.toString())
                    .put("endUtc", JSONObject.NULL).put("reason", "unknown_screen").toString())
            return operation
        }
        fun addManual(habitId: String = MANUAL): String {
            val id = UUID.randomUUID().toString(); val operation = UUID.randomUUID().toString()
            val day = DayKey(LocalDate.of(2026, 10, 18), "UTC")
            entries[id] = ManualRow(ManualEntry(id, habitId, day, "12.75".toBigDecimal(), start))
            outbox[operation] = OutboxRow(operation, "manual_entry", id, baseRevision = null, createdAt = start,
                payload = JSONObject().put("id", id).put("habitId", habitId).put("value", "12.75")
                    .put("day", JSONObject().put("date", day.date.toString()).put("zoneId", day.zoneId)).put("recordedAt", start.toString()).toString())
            return operation
        }
    }
    @Test fun confirmationSnapshotExcludesFutureCollectorGapAndManualEntryAndClearsConsent() = runTest {
        val f = Fixture(); val first = f.addGap()
        val approved = f.store.approveSnapshot(BASE, ACCOUNT, PROFILE)
        val futureGap = f.addGap(); val futureManual = f.addManual()
        assertEquals(setOf(first), f.members.keys)
        val request = f.store.nextRequest(DEVICE, approved)!!
        assertEquals(first, request.outboxId)
        f.store.acknowledge(request, ack(request))
        assertNull(f.store.nextRequest(DEVICE, approved))
        assertTrue(f.store.success(approved))
        assertFalse(f.meta.uploadEnabled); assertNull(f.scope)
        assertEquals(setOf(futureGap, futureManual), f.outbox.keys)
        assertEquals(2, f.gaps.size); assertEquals(1, f.entries.size)
        assertNull(f.store.scopeForRetry(PROFILE))
        val next = f.store.approveSnapshot(BASE, ACCOUNT, PROFILE)
        assertNotEquals(approved.scopeId, next.scopeId)
        assertEquals(setOf(futureGap, futureManual), f.members.keys)
    }
    @Test fun repeatedConfirmAndResumeNeverBroadenOrRewriteFrozenBody() = runTest {
        val f = Fixture(); val first = f.addGap()
        val scope = f.store.approveSnapshot(BASE, ACCOUNT, PROFILE)
        val frozen = f.store.nextRequest(DEVICE, scope)!!
        val future = f.addGap()
        assertEquals(scope, f.store.approveSnapshot(BASE, ACCOUNT, PROFILE))
        f.store.pauseConsent()
        expect("CONSENT_REQUIRED") { f.store.requireApproved(frozen, scope) }
        val resumed = f.store.approveSnapshot(BASE, ACCOUNT, PROFILE)
        assertEquals(scope.scopeId, resumed.scopeId)
        assertEquals(setOf(first), f.members.keys)
        assertEquals(frozen, f.store.nextRequest(DEVICE, resumed))
        assertTrue(f.outbox.containsKey(future))
    }
    @Test fun restartKeepsOnlyOldApprovedPendingAndAckDoesNotEraseRaw() = runTest {
        val f = Fixture(); f.addGap(); val second = f.addManual()
        val scope = f.store.approveSnapshot(BASE, ACCOUNT, PROFILE)
        val first = f.store.nextRequest(DEVICE, scope)!!; f.store.acknowledge(first, ack(first))
        val frozen = f.store.nextRequest(DEVICE, scope)!!
        val later = f.addGap()
        val restarted = ClientSyncStore(f.dao, f.transactions)
        assertEquals(scope, restarted.scopeForRetry(PROFILE))
        assertEquals(listOf(frozen), restarted.approvedFrozen(scope))
        assertEquals(second, frozen.outboxId)
        assertEquals(frozen.body, restarted.nextRequest(DEVICE, scope)!!.body)
        restarted.acknowledge(frozen, ack(frozen, "101"))
        assertTrue(restarted.success(scope)); assertEquals(setOf(later), f.outbox.keys)
        assertEquals("12.75".toBigDecimal(), f.entries.values.single().value.value)
    }
    @Test fun changedScopeTargetAccountOrLocalProfileFailClosedWithoutDeletingData() = runTest {
        val f = Fixture(); val operation = f.addGap(); val scope = f.store.approveSnapshot(BASE, ACCOUNT, PROFILE)
        val wire = f.store.nextRequest(DEVICE, scope)!!
        expect("ACCOUNT_BINDING_CONFLICT") { f.store.approveSnapshot("$BASE/other", ACCOUNT, PROFILE) }
        expect("ACCOUNT_BINDING_CONFLICT") { f.store.approveSnapshot(BASE, UUID.randomUUID().toString(), PROFILE) }
        expect("CONSENT_SCOPE_CONFLICT") { f.store.approveSnapshot(BASE, ACCOUNT, "other-profile") }
        expect("CONSENT_SCOPE_CONFLICT") { f.store.nextRequest(DEVICE, scope.copy(scopeId = UUID.randomUUID().toString())) }
        expect("CONSENT_SCOPE_CONFLICT") { f.store.requireApproved(wire.copy(body = "{}"), scope) }
        assertEquals(setOf(operation), f.outbox.keys); assertEquals(wire, f.wires[wire.requestId])
    }
    @Test fun dependencyAddedAfterBarrierCannotBeSilentlyIncluded() = runTest {
        val f = Fixture(); val missing = UUID.randomUUID().toString(); val child = f.addManual(missing)
        val scope = f.store.approveSnapshot(BASE, ACCOUNT, PROFILE)
        val parent = UUID.randomUUID().toString()
        f.outbox[parent] = OutboxRow(parent, "habit", missing, baseRevision = null, payload = "{}", createdAt = start)
        expect("CONSENT_DEPENDENCY_PENDING") { f.store.nextRequest(DEVICE, scope) }
        assertEquals(setOf(child), f.members.keys); assertTrue(f.wires.isEmpty())
        assertEquals(setOf(child, parent), f.outbox.keys)
    }
    @Test fun discoveryAndFriendGrantDoNotAuthorizeRawUpload() = runTest {
        val f = Fixture(); val raw = f.addGap()
        val discovery = f.store.enqueue("PUT", "/v1/me/discoverability", JSONObject().put("enabled", true))
        val grant = f.store.enqueue("PUT", "/v1/friends/$ACCOUNT/sharing", JSONObject().put("habitIds", JSONArray()))
        f.store.acknowledge(discovery, JSONObject()); f.store.acknowledge(grant, JSONObject())
        assertFalse(f.meta.uploadEnabled); assertNull(f.scope); assertTrue(f.members.isEmpty())
        assertEquals(setOf(raw), f.outbox.keys)
    }
    @Test fun actualRequestSyncDoubleTapUsesOneBarrierAndBackgroundCannotSendFutureRows() = runTest {
        val f = Fixture(); val first = f.addGap()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val pushes = mutableListOf<String>(); var pulls = 0
        val auth = SessionClient(HttpTransport { r -> when {
            r.path == "/v1/me" -> HttpReply(200, JSONObject().put("accountId", ACCOUNT).put("displayName", "Owner").put("telegramLinked", true).toString())
            r.path.startsWith("/v1/sync/pull") -> {
                if (++pulls == 1) { entered.complete(Unit); release.await() }
                HttpReply(200, JSONObject().put("changes", JSONArray()).put("nextCursor", "cursor-$pulls").put("hasMore", false).toString())
            }
            r.path == "/v1/sync/push" -> {
                val op = JSONObject(r.body!!).getJSONArray("operations").getJSONObject(0)
                pushes += op.getString("operationId")
                HttpReply(200, JSONObject().put("results", JSONArray().put(JSONObject().put("operationId", op.getString("operationId"))
                    .put("status", "accepted").put("revision", "100"))).toString())
            }
            else -> throw AssertionError("Unexpected network route")
        } }, RecordVault(PrivateConnection(BASE, session())), f.store::bind)
        val identity = LocalSettingsSnapshot(PROFILE, DEVICE, AppPreferences(ThemeMode.SYSTEM, "ocean", "standard"), null, false)
        val client = AndroidClient(auth, f.store, flowOf(identity), backgroundScope)
        client.initialize()
        val running = async { client.requestSync() }
        entered.await()
        val futureGap = f.addGap(); val futureManual = f.addManual()
        expect("SYNC_IN_PROGRESS") { client.requestSync() }
        release.complete(Unit); running.await()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf(first), pushes)
        assertFalse(f.meta.uploadEnabled); assertNull(f.scope)
        assertEquals(setOf(futureGap, futureManual), f.outbox.keys)
        assertNull(client.state.value.notice) // routine success is state/time/count, not a banner/modal
        client.notify(ClientFailure("ACCOUNT_BINDING_CONFLICT"))
        assertEquals("ACCOUNT_BINDING_CONFLICT", client.state.value.notice!!.code)
        client.notify(ClientFailure("CONSENT_SCOPE_CONFLICT"))
        assertEquals("CONSENT_SCOPE_CONFLICT", client.state.value.notice!!.code)
    }
    @Test fun cancelledRequestAndProcessRestartRetryOnlyThePreviouslyApprovedSnapshot() = runTest {
        val f = Fixture(); val first = f.addGap()
        val waiting = CompletableDeferred<Unit>(); val never = CompletableDeferred<Unit>()
        var blockFirstPull = true
        val sent = mutableListOf<String>()
        val transport = HttpTransport { r -> when {
            r.path == "/v1/me" -> HttpReply(200, JSONObject().put("accountId", ACCOUNT).put("displayName", "Owner").put("telegramLinked", true).toString())
            r.path.startsWith("/v1/sync/pull") -> {
                if (blockFirstPull) { waiting.complete(Unit); never.await() }
                HttpReply(200, JSONObject().put("changes", JSONArray()).put("nextCursor", "cursor").put("hasMore", false).toString())
            }
            r.path == "/v1/sync/push" -> {
                val op = JSONObject(r.body!!).getJSONArray("operations").getJSONObject(0).getString("operationId")
                sent += op
                HttpReply(200, JSONObject().put("results", JSONArray().put(JSONObject().put("operationId", op)
                    .put("status", "accepted").put("revision", "100"))).toString())
            }
            else -> throw AssertionError("Unexpected route")
        } }
        val vault = RecordVault(PrivateConnection(BASE, session()))
        val identity = flowOf(LocalSettingsSnapshot(PROFILE, DEVICE, AppPreferences(ThemeMode.SYSTEM, "ocean", "standard"), null, false))
        val oldScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val old = AndroidClient(SessionClient(transport, vault, f.store::bind), f.store, identity, oldScope)
        old.initialize()
        val request = launch { old.requestSync() }
        waiting.await()
        val savedScope = f.scope!!
        request.cancelAndJoin(); oldScope.cancel()
        assertTrue(f.meta.uploadEnabled); assertEquals(setOf(first), f.members.keys)
        val future = f.addManual()
        blockFirstPull = false
        val restartedStore = ClientSyncStore(f.dao, f.transactions)
        assertEquals(savedScope, restartedStore.scopeForRetry(PROFILE))
        val restarted = AndroidClient(SessionClient(transport, vault, restartedStore::bind), restartedStore, identity, backgroundScope)
        restarted.initialize()
        advanceTimeBy(5_001); runCurrent()
        assertEquals(listOf(first), sent)
        assertEquals(setOf(future), f.outbox.keys)
        assertFalse(f.meta.uploadEnabled); assertNull(f.scope)
    }
}
