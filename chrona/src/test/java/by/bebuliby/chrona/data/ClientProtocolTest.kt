package by.bebuliby.chrona.data

import by.bebuliby.chrona.data.client.*
import by.bebuliby.chrona.domain.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID

class ClientProtocolTest {
    companion object {
        private const val ORIGIN = "https://chrona.example.org"
        private const val ACCOUNT = "2dca2539-2b3b-4f68-8a30-feb342c97cfe"
        private const val OTHER = "8c9a89c3-7932-49cb-aee7-ddaf38b8c6a4"
        private const val SESSION = "b7cb049c-debc-40af-8bf6-c518f8dd3311"
        private const val DEVICE = "72b208ba-3965-4e26-a1c1-d94ee083ecef"
        private const val REQUEST = "ca8ae9b8-278a-478e-81ca-be6ce331ea65"
    }
    private class MemoryVault(var value: PrivateConnection = PrivateConnection()) : SessionVault {
        override suspend fun load() = value
        override suspend fun save(value: PrivateConnection) { this.value = value }
    }
    private class Fake(private val respond: suspend (HttpRequest) -> HttpReply) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        override suspend fun execute(request: HttpRequest): HttpReply { requests += request; return respond(request) }
    }
    private fun ok(j: JSONObject) = HttpReply(200, j.toString())
    private fun user() = JSONObject().put("accountId", ACCOUNT).put("displayName", "Confirmed name").put("telegramLinked", true)
    private fun tokens(access: String = "a".repeat(43), refresh: String = "r".repeat(43)) = JSONObject()
        .put("account", user()).put("sessionId", SESSION).put("accessToken", access).put("refreshToken", refresh)
        .put("accessExpiresAt", "2099-01-01T00:00:00Z")
    private fun session() = ClientSession(ACCOUNT, SESSION, "a".repeat(43), "r".repeat(43), Instant.parse("2099-01-01T00:00:00Z"))
    private fun failure(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (error: ClientFailure) { assertEquals(code, error.code) }
    }

    @Test fun ownDiscoverabilityParserIsPrivateDefaultOffAndStrictBoolean() {
        assertFalse(ConnectionUiState().discoverable)
        assertFalse(WireCodec.profile(user()).discoverable)
        assertTrue(WireCodec.profile(user().put("discoverable",true)).discoverable)
        assertFalse(WireCodec.profile(user().put("discoverable",false)).discoverable)
        failure("INVALID_RESPONSE") { WireCodec.profile(user().put("discoverable","true")) }
        failure("INVALID_RESPONSE") { WireCodec.profile(user().put("discoverable",JSONObject.NULL)) }
    }

    @Test fun explicitDiscoveryMutationUsesCurrentAuthFrozenBooleanBodyAndKeyThenAuthoritativeMe() = runTest {
        var enabled = false
        val transport = Fake { request -> when (request.path) {
            "/v1/me" -> ok(user().put("discoverable",enabled))
            "/v1/me/discoverability" -> {
                assertEquals("PUT",request.method); assertEquals("a".repeat(43),request.bearer); assertEquals(REQUEST,request.idempotencyKey)
                val body = JSONObject(request.body!!); assertEquals(setOf("enabled"),body.keys().asSequence().toSet())
                assertTrue(body.get("enabled") is Boolean); enabled = body.getBoolean("enabled")
                ok(user().put("discoverable",enabled))
            }
            else -> throw AssertionError("Unexpected route")
        } }
        val auth = SessionClient(transport,MemoryVault(PrivateConnection(ORIGIN,session()))) { _, _ -> }
        auth.initialize(); assertFalse(auth.me().discoverable)
        val frozen = WireCodec.canonical(SocialWire.discoverability(true))
        auth.authorized("PUT","/v1/me/discoverability",frozen,REQUEST,ORIGIN,ACCOUNT)
        assertFalse(auth.state.value.profile!!.discoverable) // dispatch/response alone is not the /me read model
        assertTrue(auth.me().discoverable)
        assertEquals("{\"enabled\":true}",frozen)
    }

    @Test fun sharingFetchesOnlyOwnGrantRevisionAndConcurrentConflictIsNotForced() = runTest {
        var missing = false
        val transport = Fake { request ->
            assertEquals("/v1/friends/$OTHER/sharing",request.path)
            if (request.method == "GET") {
                if (missing) HttpReply(409,"{\"error\":{\"code\":\"POLICY_UNRESOLVED\"}}") else ok(JSONObject().put("revision","41"))
            } else {
                assertEquals("41",JSONObject(request.body!!).getString("expectedRevision"))
                HttpReply(409,"{\"error\":{\"code\":\"REVISION_CONFLICT\"}}")
            }
        }
        val auth = SessionClient(transport,MemoryVault(PrivateConnection(ORIGIN,session()))) { _, _ -> }; auth.initialize()
        val revision = auth.ownSharingRevision(OTHER,ORIGIN,ACCOUNT); assertEquals("41",revision)
        val draft = SharingGrantDraft(OTHER,emptySet(),SharingHistoryScope.TimeWindow(Instant.EPOCH,Instant.EPOCH.plusSeconds(1)),null)
        try { auth.authorized("PUT","/v1/friends/$OTHER/sharing",WireCodec.canonical(SocialWire.sharing(draft,null,revision)),REQUEST,ORIGIN,ACCOUNT); fail() }
        catch (error: ClientFailure) { assertEquals("REVISION_CONFLICT",error.code) }
        assertEquals(1,transport.requests.count { it.method == "PUT" })
        missing = true; assertNull(auth.ownSharingRevision(OTHER,ORIGIN,ACCOUNT))
    }

    @Test fun sharingWireRequiresFiniteExplicitWindowAndPreservesEmptyRevocationAndShortsMapping() {
        val window = SharingHistoryScope.TimeWindow(Instant.EPOCH,Instant.EPOCH.plusSeconds(100))
        val empty = SharingGrantDraft(OTHER,emptySet(),window,null)
        val revoke = SocialWire.sharing(empty,null,"7")
        assertEquals(0,revoke.getJSONArray("habitIds").length()); assertEquals("7",revoke.getString("expectedRevision"))
        assertEquals("time_window",revoke.getJSONObject("historyScope").getString("kind"))
        assertFalse(revoke.has("ownerId")); assertFalse(revoke.getJSONObject("historyScope").isNull("toExclusive"))
        val grant = SocialWire.sharing(empty.copy(habitIds=setOf(ChronaRepository.SHORTS_ID,SESSION)),OTHER,null)
        assertEquals(setOf(OTHER,SESSION),(0 until grant.getJSONArray("habitIds").length()).map { grant.getJSONArray("habitIds").getString(it) }.toSet())
        failure("VALIDATION") { SocialWire.sharing(empty.copy(historyScope=SharingHistoryScope.Unresolved),null,null) }
        failure("VALIDATION") { SocialWire.sharing(empty.copy(historyScope=SharingHistoryScope.TimeWindow(Instant.EPOCH,null)),null,null) }
        failure("VALIDATION") { SocialWire.sharing(empty.copy(historyScope=SharingHistoryScope.TimeWindow(Instant.EPOCH,Instant.EPOCH)),null,null) }
    }

    @Test fun originIsExplicitHttpsBaseWithoutCredentialsQueryOrFragment() {
        assertEquals(ORIGIN, ServerOrigin.parse("HTTPS://CHRONA.EXAMPLE.ORG:443/"))
        assertEquals("$ORIGIN/chrona", ServerOrigin.parse("$ORIGIN/chrona/"))
        assertEquals("", ServerOrigin.parse(""))
        for (bad in listOf("http://example.test", "https://u:p@example.test", "$ORIGIN/a/../path",
            "https://example.test?x=secret", "https://example.test#x", "https://example.test:0", "https://example.test\\@bad.test"))
            failure("INVALID_ORIGIN") { ServerOrigin.parse(bad) }
    }
    @Test fun pkceHas256BitsAndMatchesRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val a = Pkce.verifier(); val b = Pkce.verifier()
        assertEquals(43, a.length); assertNotEquals(a, b)
        assertTrue(a.matches(Regex("[A-Za-z0-9_-]{43}")))
    }
    @Test fun telegramUrlCannotContainVerifierOrArbitraryOrigin() {
        assertEquals("https://t.me/chrona_bot?start=$REQUEST", ServerOrigin.telegram("https://t.me/chrona_bot?start=$REQUEST", REQUEST))
        failure("UNSAFE_LOGIN_URL") { ServerOrigin.telegram("https://evil.test/bot?start=$REQUEST", REQUEST) }
        failure("UNSAFE_LOGIN_URL") { ServerOrigin.telegram("https://t.me/chrona_bot?start=$REQUEST&codeVerifier=secret", REQUEST) }
    }

    @Test fun pendingLoginRestoresAndOnlyExchangeThenMeEstablishesProfile() = runTest {
        val vault = MemoryVault()
        var confirmed = false
        var bindings = 0
        val transport = Fake { r -> when {
            r.path == "/v1/auth/telegram/challenges" -> ok(JSONObject().put("requestId", REQUEST)
                .put("expiresAt", "2099-01-01T00:00:00Z").put("telegramStartUrl", "https://t.me/chrona_bot?start=$REQUEST"))
            r.path.endsWith("/status") -> ok(JSONObject().put("status", if (confirmed) "confirmed" else "pending"))
            r.path.endsWith("/exchange") -> ok(tokens())
            r.path == "/v1/me" -> ok(user())
            else -> throw AssertionError("Unexpected route")
        } }
        val first = SessionClient(transport, vault) { _, _ -> bindings++ }
        first.initialize(); first.configure(ORIGIN)
        val url = first.begin(DEVICE)
        assertFalse(url.contains(vault.value.pending!!.verifier))
        val create = JSONObject(transport.requests.first().body!!)
        assertFalse(create.has("codeVerifier"))
        assertEquals(Pkce.challenge(vault.value.pending!!.verifier), create.getString("codeChallenge"))
        assertNull(first.state.value.profile)
        val restored = SessionClient(transport, vault) { _, account -> assertEquals(ACCOUNT, account); bindings++ }
        restored.initialize(); restored.refreshLogin()
        assertTrue(restored.state.value.loginPending); assertNull(restored.state.value.profile)
        assertEquals(0, transport.requests.count { it.path.endsWith("exchange") })
        confirmed = true; restored.refreshLogin()
        assertFalse(restored.state.value.loginPending)
        assertEquals("Confirmed name", restored.state.value.profile!!.displayName)
        assertNotNull(vault.value.session); assertNull(vault.value.pending)
        assertEquals(1, transport.requests.count { it.path.endsWith("exchange") })
        assertTrue(bindings >= 1)
    }
    @Test fun telegramDisabledIsNotSuccessfulLogin() = runTest {
        val vault = MemoryVault(PrivateConnection(ORIGIN))
        val client = SessionClient(Fake { HttpReply(503, "{\"error\":{\"code\":\"TELEGRAM_NOT_CONFIGURED\"}}") }, vault) { _, _ -> }
        client.initialize()
        try { client.begin(DEVICE); fail() } catch (e: ClientFailure) { assertEquals("TELEGRAM_NOT_CONFIGURED", e.code) }
        assertNull(vault.value.pending); assertNull(vault.value.session); assertNull(client.state.value.profile)
    }
    @Test fun interruptedExchangeMarkerIsNotReplayedOnRestore() = runTest {
        val vault = MemoryVault(PrivateConnection(ORIGIN, pending = PendingLogin(REQUEST, "v".repeat(43), Instant.MAX, true)))
        val transport = Fake { throw AssertionError("One-use exchange cannot be replayed") }
        val client = SessionClient(transport, vault) { _, _ -> }
        try { client.initialize(); fail() } catch (e: ClientFailure) { assertEquals("LOGIN_RESPONSE_LOST", e.code) }
        assertNull(vault.value.pending); assertNull(vault.value.session); assertTrue(transport.requests.isEmpty())
    }
    @Test fun cancelledChallengeDoesNotExchangeOrCreateProfile() = runTest {
        val vault = MemoryVault(PrivateConnection(ORIGIN, pending = PendingLogin(REQUEST, "v".repeat(43), Instant.MAX)))
        val transport = Fake { ok(JSONObject().put("status", "cancelled")) }
        val client = SessionClient(transport, vault) { _, _ -> }; client.initialize()
        try { client.refreshLogin(); fail() } catch (e: ClientFailure) { assertEquals("CANCELLED", e.code) }
        assertNull(client.state.value.profile); assertNull(vault.value.pending)
        assertEquals(1, transport.requests.size); assertTrue(transport.requests.single().path.endsWith("status"))
    }
    @Test fun exchangingAnotherAccountCannotRebindTheLocalProfile() = runTest {
        val vault = MemoryVault(PrivateConnection(ORIGIN, pending = PendingLogin(REQUEST, "v".repeat(43), Instant.MAX)))
        val transport = Fake { r -> when {
            r.path.endsWith("status") -> ok(JSONObject().put("status", "confirmed"))
            r.path.endsWith("exchange") -> ok(tokens().put("account", user().put("accountId", OTHER)))
            r.path == "/v1/sessions/current" -> ok(JSONObject().put("revoked", true))
            else -> throw AssertionError("Cannot read/import another account")
        } }
        val client = SessionClient(transport, vault) { origin, account -> SyncWire.binding(ORIGIN, ACCOUNT, origin, account) }
        client.initialize()
        try { client.refreshLogin(); fail() } catch (e: ClientFailure) { assertEquals("ACCOUNT_BINDING_CONFLICT", e.code) }
        assertNull(vault.value.session); assertNull(vault.value.pending); assertNull(client.state.value.profile)
        assertEquals(1, transport.requests.count { it.path == "/v1/sessions/current" })
    }
    @Test fun changingOriginClearsOldSessionAndPendingWithoutSendingCredentials() = runTest {
        val vault = MemoryVault(PrivateConnection(ORIGIN, session(), PendingLogin(REQUEST, "v".repeat(43), Instant.MAX)))
        val transport = Fake { throw AssertionError("Origin change must not call network") }
        val client = SessionClient(transport, vault) { _, _ -> }
        client.initialize(); client.configure("https://different.example.org")
        assertNull(vault.value.session); assertNull(vault.value.pending)
        assertFalse(client.state.value.loginPending)
        try { client.authorized("GET", "/v1/me"); fail() } catch (e: ClientFailure) { assertEquals("LOGIN_REQUIRED", e.code) }
    }
    @Test fun refreshRotationAndConcurrent401OnlyRefreshOnce() = runTest {
        val vault = MemoryVault(PrivateConnection(ORIGIN, session()))
        val transport = Fake { r -> when {
            r.path == "/v1/auth/refresh" -> ok(tokens("b".repeat(43), "s".repeat(43)))
            r.bearer == "a".repeat(43) -> HttpReply(401, "{\"error\":{\"code\":\"UNAUTHENTICATED\"}}")
            else -> ok(user())
        } }
        val client = SessionClient(transport, vault) { _, _ -> }
        client.initialize()
        (1..8).map { async { client.authorized("GET", "/v1/me") } }.awaitAll()
        assertEquals(1, transport.requests.count { it.path == "/v1/auth/refresh" })
        assertEquals("s".repeat(43), vault.value.session!!.refresh)
        assertFalse(vault.value.session!!.rotating)
    }
    @Test fun refreshDoesNotRecomputeFrozenMutationBodyOrIdempotencyKey() = runTest {
        val vault = MemoryVault(PrivateConnection(ORIGIN, session()))
        val body = "{\"frozen\":\"12.7500\"}"
        val transport = Fake { r -> when {
            r.path == "/v1/auth/refresh" -> ok(tokens("b".repeat(43)))
            r.bearer == "a".repeat(43) -> HttpReply(401, "{\"error\":{\"code\":\"UNAUTHENTICATED\"}}")
            else -> ok(JSONObject())
        } }
        val client = SessionClient(transport, vault) { _, _ -> }; client.initialize()
        client.authorized("POST", "/v1/sync/push", body, REQUEST, ORIGIN, ACCOUNT)
        val mutations = transport.requests.filter { it.path == "/v1/sync/push" }
        assertEquals(2, mutations.size)
        assertTrue(mutations.all { it.body == body && it.idempotencyKey == REQUEST })
    }
    @Test fun lostDeliveryReplyCanReplayExactlyThePersistedRequest() = runTest {
        val vault = MemoryVault(PrivateConnection(ORIGIN, session()))
        var sends = 0
        val transport = Fake { if (++sends == 1) throw IOException() else ok(JSONObject().put("results", JSONArray())) }
        val client = SessionClient(transport, vault) { _, _ -> }; client.initialize()
        val frozen = "{\"deviceId\":\"$DEVICE\",\"operations\":[]}"
        try { client.authorized("POST", "/v1/sync/push", frozen, REQUEST, ORIGIN, ACCOUNT); fail() }
        catch (e: ClientFailure) { assertEquals("OFFLINE", e.code) }
        client.authorized("POST", "/v1/sync/push", frozen, REQUEST, ORIGIN, ACCOUNT)
        assertTrue(transport.requests.all { it.body == frozen && it.idempotencyKey == REQUEST })
    }
    @Test fun interruptedRefreshIsNeverRetriedWithSpentRefreshSecret() = runTest {
        val vault = MemoryVault(PrivateConnection(ORIGIN, session()))
        val transport = Fake { r -> if (r.path.endsWith("refresh")) throw IOException() else
            HttpReply(401, "{\"error\":{\"code\":\"UNAUTHENTICATED\"}}") }
        val client = SessionClient(transport, vault) { _, _ -> }; client.initialize()
        try { client.authorized("GET", "/v1/me"); fail() } catch (_: ClientFailure) { }
        assertNull(vault.value.session)
        val rotated = ClientSession(ACCOUNT, SESSION, "a".repeat(43), "r".repeat(43), Instant.MAX, true)
        vault.value = PrivateConnection(ORIGIN, rotated)
        try { SessionClient(transport, vault) { _, _ -> }.initialize(); fail() }
        catch (e: ClientFailure) { assertEquals("LOGIN_RESPONSE_LOST", e.code) }
        assertNull(vault.value.session)
    }
    @Test fun logoutClearsLocalTokensEvenIfRemoteRevocationIsOffline() = runTest {
        val vault = MemoryVault(PrivateConnection(ORIGIN, session()))
        val client = SessionClient(Fake { throw IOException() }, vault) { _, _ -> }; client.initialize()
        try { client.logout(); fail() } catch (e: ClientFailure) { assertEquals("REMOTE_REVOKE_UNCONFIRMED", e.code) }
        assertNull(vault.value.session)
        assertEquals(ORIGIN, vault.value.origin)
    }
    @Test fun bindingBlocksDifferentAccountOrDifferentOriginNotSilentlyMerging() {
        SyncWire.binding(null, null, ORIGIN, ACCOUNT)
        SyncWire.binding(ORIGIN, ACCOUNT, ORIGIN, ACCOUNT)
        failure("ACCOUNT_BINDING_CONFLICT") { SyncWire.binding(ORIGIN, ACCOUNT, ORIGIN, OTHER) }
        failure("ACCOUNT_BINDING_CONFLICT") { SyncWire.binding(ORIGIN, ACCOUNT, "https://different.example.test", ACCOUNT) }
    }
    @Test fun randomServerShortsMapsToStableLocalIdentityAndBootIdIsStableUuid() {
        assertEquals(ChronaRepository.SHORTS_ID, SyncWire.localHabit(OTHER, OTHER))
        assertEquals(OTHER, SyncWire.wireHabit(ChronaRepository.SHORTS_ID, OTHER))
        val boot = SyncWire.bootId(DEVICE, "boot-123")
        assertEquals(boot, WireCodec.uuid(boot)); assertEquals(boot, SyncWire.bootId(DEVICE, "boot-123"))
        assertNotEquals(boot, SyncWire.bootId(ACCOUNT, "boot-123"))
        failure("SHORTS_MAPPING_REQUIRED") { SyncWire.wireHabit(ChronaRepository.SHORTS_ID, null) }
    }
    @Test fun syncFreezeTranslatesForeignKeyAndFreezesRevisionAndRawDecimal() {
        val payload = JSONObject().put("id", REQUEST).put("habitId", ChronaRepository.SHORTS_ID).put("amount", "12.7500")
        val row = OutboxRow(SESSION, "goal_revision", REQUEST, baseRevision = null, payload = payload.toString(), createdAt = Instant.EPOCH)
        val frozen = SyncWire.freeze(row, DEVICE, OTHER, "123")
        val op = JSONObject(frozen).getJSONArray("operations").getJSONObject(0)
        assertEquals("123", op.getString("baseRevision"))
        assertEquals(OTHER, op.getJSONObject("payload").getString("habitId"))
        assertEquals("12.7500", op.getJSONObject("payload").getString("amount"))
        payload.put("amount", "99") // mutable editor input does not change a persisted frozen String
        assertEquals("12.7500", JSONObject(frozen).getJSONArray("operations").getJSONObject(0).getJSONObject("payload").getString("amount"))
        assertEquals(frozen, SyncWire.freeze(row, DEVICE, OTHER, "123"))
    }
    @Test fun neverPushSystemProvisionAsManualAndRejectManualShortsSourceInjection() {
        fun row(type: String, id: String, payload: JSONObject) = OutboxRow(REQUEST, type, id, baseRevision = null,
            payload = payload.toString(), createdAt = Instant.EPOCH)
        failure("SOURCE_NOT_WRITABLE") { SyncWire.freeze(row("habit", ChronaRepository.SHORTS_ID,
            JSONObject().put("id", ChronaRepository.SHORTS_ID)), DEVICE, OTHER, null) }
        failure("SOURCE_NOT_WRITABLE") { SyncWire.freeze(row("manual_entry", SESSION,
            JSONObject().put("id", SESSION).put("habitId", ChronaRepository.SHORTS_ID)), DEVICE, OTHER, null) }
    }
    @Test fun importedUnknownAndPartialAreNotKnownZero() {
        assertTrue(WireCodec.actual(JSONObject("{\"state\":\"unknown\",\"reason\":\"unknown_actual\"}")) is ActualValue.Unknown)
        assertTrue(WireCodec.actual(JSONObject("{\"state\":\"partial\",\"observedValue\":\"0\",\"source\":\"collector\"}")) is ActualValue.Partial)
        assertEquals(Computed.Unavailable(UnavailableReason.PARTIAL_COVERAGE),
            WireCodec.computed<Decimal>(JSONObject("{\"state\":\"unavailable\",\"reason\":\"partial_coverage\"}")) { WireCodec.decimal(it) })
        val automatic = Habit(ChronaRepository.SHORTS_ID, "Shorts", ValueUnit("duration_ms", "ms"),
            GoalDirection.AT_MOST, CollectionMode.AUTOMATIC_SHORTS, Instant.EPOCH)
        try { WireCodec.manual(JSONObject(), automatic); fail() }
        catch (e: ClientFailure) { assertEquals("SOURCE_NOT_WRITABLE", e.code) }
    }

    @Test fun authorizedViewParsesBackendShapeAndKeepsFriendReadOnlyAndUnavailable() {
        val unit = JSONObject().put("code", "pages").put("label", "страниц")
        val habit = JSONObject().put("id", OTHER).put("title", "Читать").put("unit", unit)
            .put("direction", "at_least").put("collectionMode", "manual").put("createdAt", "2026-10-18T00:00:00Z")
        val day = JSONObject().put("date", "2026-10-18").put("zoneId", "UTC")
        val unavailable = JSONObject().put("state", "unavailable").put("reason", "goal_unresolved")
        val cell = JSONObject().put("habitId", OTHER).put("day", day).put("planStatus", "unresolved").put("goal", JSONObject.NULL)
            .put("actual", JSONObject().put("state", "known").put("value", "12.75").put("source", "manual"))
            .put("usagePercent", unavailable).put("score", unavailable).put("emotion", unavailable)
            .put("streak", JSONObject().put("state", "unavailable").put("reason", "streak_rules_unresolved"))
            .put("canEditActual", true).put("canEditGoal", true)
        val range = JSONObject().put("fromInclusive", "2026-10-18").put("toExclusive", "2026-10-19").put("zoneId", "UTC")
        val snapshot = JSONObject().put("owner", user()).put("range", range).put("habits", JSONArray().put(habit))
            .put("cells", JSONArray().put(cell)).put("intervals", JSONArray()).put("gaps", JSONArray())
            .put("summary", JSONObject().put("plannedCells", 0).put("knownCells", 0).put("scorePercent", unavailable).put("curves", JSONArray()))
        val parsed = WireCodec.snapshot(snapshot)
        val parsedCell = parsed.history.cells.single()
        assertFalse(parsedCell.canEditActual); assertFalse(parsedCell.canEditGoal)
        assertTrue(parsedCell.actual is ActualValue.Known)
        assertEquals(Computed.Unavailable(UnavailableReason.GOAL_UNRESOLVED), parsedCell.score)
    }

    @Test fun acknowledgedOpenGapRevisionIsUsedForClosureAndFrozenReplayRetainsRawGap() = runTest {
        val outbox = linkedMapOf<String, OutboxRow>()
        val wires = linkedMapOf<String, WireRow>()
        val remotes = mutableMapOf<String, RemoteRow>()
        val applied = mutableSetOf<String>()
        val rawGaps = mutableMapOf<String, GapRow>()
        var transactionActive = false
        val transaction = object : ClientTransactions {
            override suspend fun <T> inTransaction(block: suspend () -> T): T {
                check(!transactionActive)
                transactionActive = true
                try { return block() } finally { transactionActive = false }
            }
        }
        var meta = SyncMetaRow(origin = ORIGIN, accountId = ACCOUNT, serverShortsId = OTHER)
        var approval: UploadScopeRow? = null
        val members = mutableMapOf<String, ApprovedUploadRow>()
        // Runs the actual ClientSyncStore algorithm. Only DAO IO/Room transaction runtime are fakes.
        val dao = Proxy.newProxyInstance(ChronaDao::class.java.classLoader, arrayOf(ChronaDao::class.java)) { _, method, args ->
            when (method.name) {
                "syncMeta" -> meta
                "saveSyncMeta" -> { assertTrue(transactionActive); meta = args!![0] as SyncMetaRow; Unit }
                "uploadScope" -> approval
                "saveUploadScope" -> { approval = args!![0] as UploadScopeRow; Unit }
                "deleteUploadScope" -> { approval = null; Unit }
                "approvals" -> members.values.filter { it.scopeId == args!![0] }
                "insertApprovals" -> { @Suppress("UNCHECKED_CAST") val rows = args!![0] as List<ApprovedUploadRow>
                    rows.forEach { members[it.operationId] = it }; Unit }
                "deleteApproval" -> { members.remove(args!![0] as String); Unit }
                "deleteApprovals" -> { members.entries.removeAll { it.value.scopeId == args!![0] }; Unit }
                "outbox" -> outbox.values.toList()
                "wireRequests" -> wires.values.toList()
                "remote" -> remotes[args!![0] as String]
                "insertWire" -> { assertTrue(transactionActive); (args!![0] as WireRow).let { wires[it.requestId] = it }; Unit }
                "saveRemote" -> { assertTrue(transactionActive); (args!![0] as RemoteRow).let { remotes[it.key] = it }; Unit }
                "markApplied" -> { assertTrue(transactionActive); applied += (args!![0] as AppliedRow).revision; Unit }
                "deleteOutbox" -> { assertTrue(transactionActive); outbox.remove(args!![0] as String); Unit }
                "deleteWire" -> { assertTrue(transactionActive); wires.remove(args!![0] as String); Unit }
                "saveGap" -> { (args!![0] as GapRow).let { rawGaps[it.value.id] = it }; Unit }
                "gap" -> rawGaps[args!![0] as String]
                "enqueue" -> { (args!![0] as OutboxRow).let { outbox[it.operationId] = it }; Unit }
                "toString" -> "GapRegressionDao"
                else -> throw AssertionError("Unexpected DAO call: ${method.name}")
            }
        } as ChronaDao
        val store = ClientSyncStore(dao, transaction)
        val start = Instant.parse("2026-10-18T10:00:00Z")
        val open = ObservationGap(REQUEST, ChronaRepository.SHORTS_ID, start, null, GapReason.UNKNOWN_SCREEN)
        fun payload(gap: ObservationGap) = JSONObject().put("id", gap.id).put("habitId", gap.habitId)
            .put("startUtc", gap.startUtc.toString()).put("endUtc", gap.endUtc?.toString() ?: JSONObject.NULL)
            .put("reason", "unknown_screen")
        suspend fun queue(gap: ObservationGap): String {
            dao.saveGap(GapRow(gap))
            val operation = UUID.randomUUID().toString()
            dao.enqueue(OutboxRow(operation, "observation_gap", gap.id, baseRevision = null,
                payload = payload(gap).toString(), createdAt = start))
            return operation
        }
        var serverRevision: String? = null
        var serverPayload: JSONObject? = null
        val serverDedup = mutableMapOf<String, Pair<String, String>>()
        val transport = Fake { request ->
            val body = request.body!!
            val operation = JSONObject(body).getJSONArray("operations").getJSONObject(0)
            val operationId = operation.getString("operationId")
            assertEquals(operationId, request.idempotencyKey)
            val prior = serverDedup[operationId]
            val revision: String
            val status: String
            if (prior != null) {
                assertEquals("Replay must not mutate frozen request", prior.first, body)
                revision = prior.second; status = "duplicate"
            } else {
                assertEquals("Wrong gap baseRevision would cause REVISION_CONFLICT", serverRevision,
                    WireCodec.optional(operation, "baseRevision"))
                val next = operation.getJSONObject("payload")
                assertEquals(OTHER, next.getString("habitId"))
                serverPayload?.let { previous ->
                    assertTrue(previous.isNull("endUtc")); assertFalse(next.isNull("endUtc"))
                    val oldFields = JSONObject(previous.toString()).apply { remove("endUtc") }
                    val newFields = JSONObject(next.toString()).apply { remove("endUtc") }
                    assertEquals(WireCodec.canonical(oldFields), WireCodec.canonical(newFields))
                }
                revision = if (serverRevision == null) "100" else "101"
                serverRevision = revision; serverPayload = next
                serverDedup[operationId] = body to revision
                status = "accepted"
            }
            ok(JSONObject().put("results", JSONArray().put(JSONObject().put("operationId", operationId)
                .put("status", status).put("revision", revision))))
        }
        suspend fun send(wire: WireRow) = JSONObject(transport.execute(
            HttpRequest(wire.origin, wire.method, wire.path, wire.body, idempotencyKey = wire.requestId)).body)

        val openOperation = queue(open)
        val firstConsent = store.approveSnapshot(ORIGIN, ACCOUNT, "local-profile")
        val openWire = store.nextRequest(DEVICE, firstConsent)!!
        assertTrue(JSONObject(openWire.body).getJSONArray("operations").getJSONObject(0).isNull("baseRevision"))
        store.acknowledge(openWire, send(openWire))
        assertEquals("100", remotes["observation_gap:$REQUEST"]!!.revision)
        assertTrue(outbox.isEmpty()); assertTrue(wires.isEmpty())
        assertEquals(open, dao.gap(REQUEST)!!.value)
        assertTrue(store.success(firstConsent))

        val closed = open.copy(endUtc = start.plusSeconds(30))
        val closeOperation = queue(closed)
        assertNotEquals(openOperation, closeOperation)
        val secondConsent = store.approveSnapshot(ORIGIN, ACCOUNT, "local-profile")
        val closedWire = store.nextRequest(DEVICE, secondConsent)!!
        assertEquals("100", JSONObject(closedWire.body).getJSONArray("operations").getJSONObject(0).getString("baseRevision"))
        send(closedWire) // server committed revision101, but response/local ACK is deliberately lost
        assertTrue(outbox.containsKey(closeOperation))
        val key = "observation_gap:$REQUEST"
        remotes[key] = RemoteRow(key, "101", WireCodec.canonical(serverPayload!!))
        val restartedStore = ClientSyncStore(dao, transaction)
        val replay = restartedStore.nextRequest(DEVICE, secondConsent)!!
        assertEquals(closedWire.requestId, replay.requestId)
        assertEquals(closedWire.body, replay.body) // still base100, never recomputed as101
        val duplicate = send(replay)
        assertEquals("duplicate", duplicate.getJSONArray("results").getJSONObject(0).getString("status"))
        restartedStore.acknowledge(replay, duplicate)
        assertEquals(closed, dao.gap(REQUEST)!!.value)
        assertEquals("101", remotes[key]!!.revision)
        assertTrue(applied.containsAll(listOf("100", "101")))
        assertTrue(outbox.isEmpty()); assertTrue(wires.isEmpty())
    }
}
