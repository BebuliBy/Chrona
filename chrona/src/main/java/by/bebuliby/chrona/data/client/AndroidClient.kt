package by.bebuliby.chrona.data.client

import by.bebuliby.chrona.data.LocalSettings
import by.bebuliby.chrona.data.LocalSettingsSnapshot
import by.bebuliby.chrona.data.AutomaticSyncPreferences
import by.bebuliby.chrona.data.WireRow
import by.bebuliby.chrona.data.UploadScopeRow
import by.bebuliby.chrona.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

data class ClientView(val baseUrl: String = "", val loginPending: Boolean = false,
    val profile: RemoteProfile? = null, val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY,
    val lastSuccessAt: Instant? = null, val friends: FriendsUiState = FriendsUiState(FeatureStatus.NOT_CONNECTED),
    val notice: UiNotice? = null, val automaticEnabled: Boolean = true)

class AndroidClient internal constructor(val auth: SessionClient, private val store: ClientSyncStore,
                    private val identities: Flow<LocalSettingsSnapshot>, private val scope: CoroutineScope,
                    private val automaticPreferences: AutomaticSyncPreferences = MemoryAutomaticSyncPreferences(),
                    private val scheduleBackground: () -> Unit = {}, private val cancelBackground: () -> Unit = {}) {
    constructor(auth: SessionClient, store: ClientSyncStore, settings: LocalSettings, scope: CoroutineScope,
                scheduleBackground: () -> Unit = {}, cancelBackground: () -> Unit = {}) :
        this(auth, store, settings.snapshots, scope, settings, scheduleBackground, cancelBackground)
    private val mutable = MutableStateFlow(ClientView())
    val state = mutable.asStateFlow()
    private val syncMutex = Mutex()
    @Volatile private var backoff = 10_000L
    @Volatile private var retryNotBeforeNanos = 0L
    @Volatile private var initialized = false
    private var automaticSignal: Job? = null
    private var lastAutomaticError: String? = null

    suspend fun initialize(bootstrapBaseUrl: String = "") {
        scope.launch { auth.state.collect { a -> mutable.update { it.copy(baseUrl = a.origin,
            loginPending = a.loginPending, profile = a.profile,
            friends = if (a.profile == null) FriendsUiState(FeatureStatus.NOT_CONNECTED) else it.friends) }
            if (a.profile != null) {
                try { updateMeta(); signalAutomaticSync() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { automaticFailure(error) }
            }
        } }
        try {
            auth.initialize()
            if (auth.bootstrap(bootstrapBaseUrl, store::canBootstrapUnconfiguredProfile) == BootstrapOutcome.BLOCKED_EXISTING_BINDING)
                throw ClientFailure("BOOTSTRAP_EXISTING_BINDING")
            if (auth.hasSession()) auth.me()
            else if (auth.state.value.loginPending) auth.refreshLogin()
            updateMeta()
        } catch (error: Exception) { automaticFailure(error) }
        initialized = true
        signalAutomaticSync()
        scope.launch {
            while (isActive) {
                delay(backoff)
                try {
                    if (auth.hasSession()) {
                        retrySocial()
                        runAutomaticCycle()
                        refreshSelectedFriend()
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { automaticFailure(error) }
            }
        }
    }
    suspend fun configure(url: String) {
        ManagedServerConfiguration.rejectUserEdit()
    }
    suspend fun beginLogin(): String = auth.begin(identities.first { it.deviceId.isNotEmpty() }.deviceId)
    suspend fun refreshLogin() {
        auth.refreshLogin()
        signalAutomaticSync()
        if (auth.state.value.profile != null) refreshFriends()
        updateMeta()
    }
    suspend fun logout() {
        cancelBackground()
        store.pauseConsent()
        try { auth.logout() } finally { mutable.update { ClientView(baseUrl = auth.state.value.origin) } }
    }
    suspend fun requestSync() {
        if (!auth.hasSession()) throw ClientFailure("LOGIN_REQUIRED")
        val meta = store.meta() ?: throw ClientFailure("LOGIN_REQUIRED")
        if (!automaticPreferences.automaticEnabled(meta.origin, meta.accountId)) throw ClientFailure("AUTOMATIC_SYNC_DISABLED")
        runAutomaticCycle() // compatibility "now", never a manual-consent popup or a permission bypass
    }
    suspend fun setAutomaticSync(enabled: Boolean) {
        val meta = store.meta() ?: throw ClientFailure("LOGIN_REQUIRED")
        val view = auth.state.value
        if (view.origin != meta.origin || (view.profile != null && view.profile.accountId != meta.accountId))
            throw ClientFailure("ACCOUNT_BINDING_CONFLICT")
        automaticPreferences.setAutomaticEnabled(meta.origin, meta.accountId, enabled)
        mutable.update { it.copy(automaticEnabled = enabled) }
        if (!enabled) {
            automaticSignal?.cancel(); cancelBackground(); store.pauseConsent()
            mutable.update { it.copy(syncStatus = SyncStatus.LOCAL_ONLY) }
        } else signalAutomaticSync()
    }
    /** Throttled producer/login signal: frequent checkpoint/events do not create a 100ms sender loop. */
    @Synchronized fun signalAutomaticSync() {
        if (!initialized || automaticSignal?.isActive == true) return
        automaticSignal = scope.launch {
            delay(5_000)
            try {
                if (!auth.hasSession()) return@launch
                val meta = store.meta() ?: return@launch
                if (!automaticPreferences.automaticEnabled(meta.origin, meta.accountId)) return@launch
                scheduleBackground()
                runAutomaticCycle()
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { automaticFailure(error) }
        }
    }
    /** Returns true when a constrained Worker should retry a remaining bounded slice. */
    suspend fun runAutomaticCycle(): Boolean {
        val retryAt = retryNotBeforeNanos
        if (retryAt != 0L && System.nanoTime() - retryAt < 0L) return true
        if (!syncMutex.tryLock()) return true
        try {
            if (!auth.hasSession()) return false
            if (auth.refreshIsUncertain()) throw ClientFailure("SESSION_REFRESH_UNCERTAIN")
            var meta = store.meta()
            if (meta == null) { auth.me(); meta = store.meta() }
            val bound = meta ?: throw ClientFailure("LOGIN_REQUIRED")
            if (bound.origin != auth.state.value.origin) throw ClientFailure("ACCOUNT_BINDING_CONFLICT")
            if (!automaticPreferences.automaticEnabled(bound.origin, bound.accountId)) {
                store.pauseConsent(); updateMeta(); return false
            }
            val p = auth.state.value.profile ?: auth.me()
            SyncWire.binding(bound.origin, bound.accountId, auth.state.value.origin, p.accountId)
            if (bound.errorCode in setOf("REVISION_CONFLICT", "PULL_LOCAL_CONFLICT", "IDEMPOTENCY_CONFLICT",
                    "SOURCE_NOT_WRITABLE", "POLICY_UNRESOLVED", "CONSENT_SCOPE_CONFLICT")) {
                updateMeta(); return false // unresolved data is retained, not LWW/rebased/dropped
            }
            val local = identities.first { it.profileId.isNotBlank() }
            // Snapshot is now an INTERNAL retry/ordering strategy, not one-shot user consent.
            val approved = store.approveSnapshot(bound.origin, bound.accountId, local.profileId, extendAutomatic = true)
            val completed = syncApproved(approved)
            lastAutomaticError = null
            backoff = 10_000; retryNotBeforeNanos = 0L
            return !completed || store.hasPendingUploads()
        } finally { syncMutex.unlock() }
    }
    suspend fun runBackgroundCycle(): Boolean {
        if (!auth.hasSession()) return false
        retrySocial()
        return runAutomaticCycle()
    }
    private fun automaticFailure(error: Exception) {
        val code = (error as? ClientFailure)?.code ?: "CLIENT_ERROR"
        backoff = (backoff * 2).coerceIn(10_000, 120_000)
        retryNotBeforeNanos = System.nanoTime() + backoff * 1_000_000
        mutable.update { it.copy(syncStatus = if (code == "OFFLINE") SyncStatus.OFFLINE else SyncStatus.ERROR) }
        if (lastAutomaticError != code) { lastAutomaticError = code; notify(error) }
    }
    /** Default network only: no physical-network selection, VPN exclusion or custom DNS. */
    fun defaultNetworkChanged(available: Boolean) {
        if (!available) {
            mutable.update { it.copy(syncStatus = SyncStatus.OFFLINE) }
        } else {
            retryNotBeforeNanos = 0L; backoff = 10_000
            signalAutomaticSync()
        }
    }
    fun backgroundFailure(error: Exception) { automaticFailure(error) }
    private suspend fun ensureAutomaticAllowed(origin: String, accountId: String) {
        if (!auth.hasSession()) throw ClientFailure("LOGIN_REQUIRED")
        if (auth.state.value.origin != origin) throw ClientFailure("ACCOUNT_BINDING_CONFLICT")
        if (!automaticPreferences.automaticEnabled(origin, accountId)) throw ClientFailure("AUTOMATIC_SYNC_DISABLED")
    }
    private suspend fun syncApproved(approved: UploadScopeRow): Boolean {
        val profile = auth.state.value.profile ?: auth.me()
        val meta = store.meta() ?: throw ClientFailure("LOGIN_REQUIRED")
        SyncWire.binding(approved.origin, approved.accountId, auth.state.value.origin, profile.accountId)
        val local = identities.first { it.profileId.isNotBlank() }
        if (approved.localProfileId != local.profileId || meta.origin != approved.origin || meta.accountId != approved.accountId)
            throw ClientFailure("CONSENT_SCOPE_CONFLICT")
        mutable.update { it.copy(syncStatus = SyncStatus.SYNCING) }
        try {
            var sent = 0
            val deadline = System.nanoTime() + 90_000_000_000L
            // A prior send may have committed remotely but its response/ack was lost.
            // Replay frozen requests BEFORE pull, so our own echo cannot be mistaken for a local collision.
            for (wire in store.approvedFrozen(approved)) {
                wire.errorCode?.let { throw ClientFailure(it, 409) }
                execute(wire, approved)
                sent++
            }
            pull()
            val device = identities.first { it.deviceId.isNotBlank() }.deviceId
            while (true) {
                if (sent >= 100 || System.nanoTime() >= deadline) {
                    updateMeta(); return false // durable unfinished snapshot resumes next scheduled cycle
                }
                val wire = store.nextRequest(device, approved) ?: break
                execute(wire, approved)
                sent++
            }
            pull()
            if (!store.success(approved)) throw ClientFailure("CONSENT_SCOPE_CONFLICT")
            updateMeta()
            // Success is represented by sync state/time/pending count, not an intrusive routine notice.
            return true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val code = (error as? ClientFailure)?.code ?: "CLIENT_ERROR"
            // Transient delivery failure retains a retryable frozen request, hard conflicts remain explicit.
            if (!retryable(error) && code != "AUTOMATIC_SYNC_DISABLED") store.error(code)
            mutable.update { it.copy(syncStatus = if (code == "OFFLINE") SyncStatus.OFFLINE else SyncStatus.ERROR) }
            throw error
        }
    }
    private suspend fun pull() {
        var resnapshot = false
        var pages = 0
        while (true) {
            if (++pages > 10_000) throw ClientFailure("PULL_LIMIT")
            val m = store.meta() ?: throw ClientFailure("LOGIN_REQUIRED")
            val path = "/v1/sync/pull?limit=200" + (m.cursor?.let { "&cursor=${ServerOrigin.query(it)}" } ?: "")
            val reply = try { auth.authorized("GET", path, expectedOrigin = m.origin, expectedAccount = m.accountId) }
            catch (error: ClientFailure) {
                if (error.code == "CURSOR_RESNAPSHOT_REQUIRED" && !resnapshot) {
                    resnapshot = true; store.resetCursor(); continue // records/pending operations never erased
                }
                throw error
            }
            store.applyPage(reply)
            if (!reply.getBoolean("hasMore")) break
        }
    }
    private suspend fun execute(wire: WireRow, approved: UploadScopeRow? = null) {
        if (wire.outboxId != null) {
            ensureAutomaticAllowed(wire.origin, wire.accountId)
            store.requireApproved(wire, approved ?: throw ClientFailure("CONSENT_REQUIRED"))
        }
        try {
            val result = auth.authorized(wire.method, wire.path, wire.body, wire.requestId, wire.origin, wire.accountId)
            store.acknowledge(wire, result)
            if (wire.outboxId != null) delay(100) // bounded backlog pacing, not a 100-HTTP/s flush
            if (wire.path == "/v1/me/discoverability") auth.me() // authoritative state, including after a durable retry
        } catch (error: ClientFailure) {
            if (error.httpStatus in setOf(400, 403, 404, 409, 410, 422)) store.failed(wire, error.code)
            throw error
        }
    }
    private suspend fun retrySocial() = syncMutex.withLock {
        // Explicit social mutations are distinct from the account-bound raw automatic-sync toggle.
        for (wire in store.socialRequests()) {
            if (wire.errorCode == null) execute(wire)
        }
    }
    private fun retryable(error: Exception): Boolean = error is ClientFailure &&
        (error.code == "OFFLINE" || error.httpStatus == 429 || error.httpStatus in 500..599)

    suspend fun refreshFriends() {
        mutable.update { it.copy(friends = it.friends.copy(status = FeatureStatus.LOADING)) }
        try {
            val profile = auth.state.value.profile ?: auth.me()
            val response = auth.authorized("GET", "/v1/friends")
            if (!response.isNull("nextCursor")) throw ClientFailure("UNSUPPORTED_PAGINATION")
            val accepted = WireCodec.objects(response.getJSONArray("items")).map { WireCodec.user(it, FriendRelation.ACCEPTED) }
            val requests = WireCodec.objects(auth.authorized("GET", "/v1/friend-requests").getJSONArray("items"))
                .filter { WireCodec.text(it, "state") == "pending" }.map { r ->
                    val incoming = WireCodec.text(r, "targetAccountId") == profile.accountId
                    val other = WireCodec.uuid(WireCodec.text(r, if (incoming) "senderAccountId" else "targetAccountId"))
                    FriendSummary(other, other, null, if (incoming) FriendRelation.INCOMING else FriendRelation.OUTGOING,
                        WireCodec.uuid(WireCodec.text(r, "requestId")))
                }
            mutable.update { it.copy(friends = FriendsUiState(FeatureStatus.READY, accepted + requests,
                it.friends.searchResults, null)) }
        } catch (error: Exception) {
            mutable.update { it.copy(friends = FriendsUiState(FeatureStatus.ERROR)) }
            throw error
        }
    }
    suspend fun search(query: String) {
        mutable.update { it.copy(friends = it.friends.copy(searchResults = emptyList())) }
        val response = auth.authorized("GET", "/v1/users/search?q=${ServerOrigin.query(query)}")
        if (!response.isNull("nextCursor")) throw ClientFailure("UNSUPPORTED_PAGINATION")
        val users = WireCodec.objects(response.getJSONArray("items")).map { WireCodec.user(it) }
        mutable.update { it.copy(friends = it.friends.copy(status = FeatureStatus.READY, searchResults = users)) }
    }
    suspend fun requestFriend(accountId: String) {
        social("POST", "/v1/friend-requests", JSONObject().put("targetAccountId", WireCodec.uuid(accountId)))
        refreshFriends()
    }
    suspend fun respond(requestId: String, accept: Boolean) {
        social("POST", "/v1/friend-requests/${WireCodec.uuid(requestId)}/respond", JSONObject().put("accept", accept))
        refreshFriends()
    }
    suspend fun openFriend(accountId: String, range: DayRange, period: HistoryPeriod = HistoryPeriod.WEEK) {
        mutable.update { it.copy(friends = it.friends.copy(selected = null, status = FeatureStatus.LOADING)) }
        try {
            val query = "fromInclusive=${range.fromInclusive}&toExclusive=${range.toExclusive}&zoneId=${ServerOrigin.query(range.zoneId)}"
            val response = auth.authorized("GET", "/v1/owners/${WireCodec.uuid(accountId)}/activity-view?$query")
            val snapshot = WireCodec.snapshot(response)
            if (snapshot.owner.accountId != accountId || snapshot.history.range != range) throw ClientFailure("INVALID_RESPONSE")
            mutable.update { it.copy(friends = it.friends.copy(status = FeatureStatus.READY,
                selected = snapshot.copy(history = snapshot.history.copy(period = period)))) }
        } catch (error: Exception) {
            mutable.update { it.copy(friends = it.friends.copy(status = FeatureStatus.ERROR, selected = null)) }
            throw error
        }
    }
    fun closeFriend() { mutable.update { it.copy(friends = it.friends.copy(selected = null)) } }
    /** Refresh only the already-open authorized owner/range; never expands a grant or merges friend raw data. */
    private suspend fun refreshSelectedFriend() {
        if (!mutable.value.automaticEnabled) return
        val selected = mutable.value.friends.selected ?: return
        val viewer = auth.state.value.profile ?: return
        val origin = auth.state.value.origin
        val range = selected.history.range
        val query = "fromInclusive=${range.fromInclusive}&toExclusive=${range.toExclusive}&zoneId=${ServerOrigin.query(range.zoneId)}"
        try {
            val reply = auth.authorized("GET", "/v1/owners/${WireCodec.uuid(selected.owner.accountId)}/activity-view?$query",
                expectedOrigin = origin, expectedAccount = viewer.accountId)
            val fresh = WireCodec.snapshot(reply)
            if (fresh.owner.accountId != selected.owner.accountId || fresh.history.range != range) throw ClientFailure("INVALID_RESPONSE")
            mutable.update { current ->
                if (current.friends.selected !== selected) current else current.copy(friends = current.friends.copy(
                    status = FeatureStatus.READY, selected = fresh.copy(history = fresh.history.copy(period = selected.history.period))))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ClientFailure) {
            mutable.update { current ->
                if (current.friends.selected !== selected) current else current.copy(friends = current.friends.copy(
                    status = FeatureStatus.ERROR, selected = if (failure.httpStatus in setOf(401, 403, 404)) null else selected))
            }
            // Offline keeps only already-delivered data and a visible connection error; no fresh-access claim.
            throw failure
        }
    }
    suspend fun configureDiscoverability(enabled: Boolean) = syncMutex.withLock {
        if (store.socialRequests().any { it.path == "/v1/me/discoverability" }) throw ClientFailure("SOCIAL_PENDING")
        social("PUT","/v1/me/discoverability",SocialWire.discoverability(enabled))
    }
    suspend fun sharing(grant: SharingGrantDraft) = syncMutex.withLock {
        val submitted = grant.copy(habitIds = grant.habitIds.toSet())
        // Reject unresolved/open-ended input before IO. This Android editor requires both dates.
        SocialWire.validateSharing(submitted)
        auth.me()
        val path = "/v1/friends/${WireCodec.uuid(submitted.friendAccountId)}/sharing"
        if (store.socialRequests().any { it.path == path }) throw ClientFailure("SOCIAL_PENDING")
        // Reading owner data to discover the server Shorts identity is not local-history upload consent.
        if (submitted.habitIds.contains(by.bebuliby.chrona.data.ChronaRepository.SHORTS_ID) && store.meta()?.serverShortsId == null) pull()
        val m = store.meta() ?: throw ClientFailure("LOGIN_REQUIRED")
        val revision = submitted.expectedRevision ?: auth.ownSharingRevision(submitted.friendAccountId,m.origin,m.accountId)
        social("PUT",path,SocialWire.sharing(submitted,m.serverShortsId,revision))
        mutable.update { it.copy(notice = UiNotice(UUID.randomUUID().toString(),"SHARING_SAVED","Новый явный набор доступа подтверждён сервером. Пустой набор отзывает доступ.")) }
    }
    private suspend fun social(method: String, path: String, body: JSONObject) {
        auth.me()
        try { execute(store.enqueue(method, path, body)) }
        catch (error: Exception) { updateMeta(); throw error }
    }
    private suspend fun updateMeta() {
        var m = store.meta()
        val enabled = if (m != null && m.origin == auth.state.value.origin)
            automaticPreferences.automaticEnabled(m.origin, m.accountId) else true
        if (auth.state.value.profile != null && m?.errorCode in setOf("LOGIN_REQUIRED", "UNAUTHENTICATED", "OFFLINE", "SERVER_NOT_CONFIGURED",
                "CONSENT_REQUIRED", "CONSENT_RECONFIRM_REQUIRED")) {
            store.error(null); m = store.meta()
        }
        mutable.update { it.copy(automaticEnabled = enabled, syncStatus = when {
            !enabled -> SyncStatus.LOCAL_ONLY
            m?.errorCode != null -> SyncStatus.ERROR
            auth.state.value.profile != null && (m?.uploadEnabled == true || m?.lastSuccessAt != null) -> SyncStatus.IDLE
            else -> SyncStatus.LOCAL_ONLY
        }, lastSuccessAt = m?.lastSuccessAt) }
    }
    fun notify(error: Exception) {
        val code = (error as? ClientFailure)?.code ?: "CLIENT_ERROR"
        val message = when (code) {
            "SERVER_NOT_CONFIGURED" -> "В этой сборке не задан сервер. Локальные данные доступны; нужна сборка с настройкой сервера."
            "INVALID_ORIGIN" -> "Настройка сервера этой сборки недопустима. Локальные данные сохранены."
            "BOOTSTRAP_EXISTING_BINDING" -> "Существующая привязка профиля сохранена. Для смены сервера нужен отдельно согласованный перенос, не новая настройка сборки."
            "SERVER_CONFIGURATION_MANAGED" -> ManagedServerConfiguration.MESSAGE
            "TELEGRAM_NOT_CONFIGURED" -> "На сервере ещё не настроен Telegram-бот. Вход не выполнен."
            "OFFLINE" -> "Сервер недоступен. Локальные данные и очередь сохранены."
            "ACCOUNT_BINDING_CONFLICT" -> "История привязана к другому адресу (включая префикс) или аккаунту. Вернитесь к прежнему серверу либо согласуйте отдельный перенос; автоматической перепривязки/очистки нет."
            "LOGIN_REQUIRED", "LOGIN_RESPONSE_LOST" -> "Нужен новый вход. Одноразовый ответ не удалось безопасно восстановить."
            "SESSION_REFRESH_UNCERTAIN" -> "Не удалось подтвердить обновление входа после обрыва связи. Данные сохранены. В этом случае нужно повторить вход через Telegram."
            "REMOTE_REVOKE_UNCONFIRMED" -> "Локальная сессия удалена. Отзыв на сервере не подтверждён."
            "REVISION_CONFLICT", "PULL_LOCAL_CONFLICT", "IDEMPOTENCY_CONFLICT" -> "Конфликт данных. Локальные записи и операция сохранены; автоматической перезаписи нет."
            "POLICY_UNRESOLVED" -> "Для этой операции сервер требует ещё не выбранное правило. Данные не удалены."
            "SOCIAL_PENDING" -> "Предыдущая операция для этой настройки ещё не подтверждена или конфликтует. Новый набор не отправлен; автоматической перезаписи нет."
            "CONSENT_REQUIRED", "CONSENT_RECONFIRM_REQUIRED", "CONSENT_SCOPE_CONFLICT" -> "Привязка или сохранённая очередь изменилась. Передача остановлена без удаления записей."
            "CONSENT_DEPENDENCY_PENDING" -> "Зависимая запись ещё не готова к отправке. Данные сохранены."
            "SYNC_IN_PROGRESS" -> "Синхронизация уже выполняется."
            "AUTOMATIC_SYNC_DISABLED" -> "Автосинхронизация выключена."
            else -> "Операция не подтверждена ($code)."
        }
        mutable.update { it.copy(notice = UiNotice(UUID.randomUUID().toString(), code, message)) }
    }
}

/** Internal JVM injection default only; the application constructor always uses persistent LocalSettings. */
private class MemoryAutomaticSyncPreferences : AutomaticSyncPreferences {
    private val values = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Boolean>()
    override suspend fun automaticEnabled(origin: String, accountId: String) = values[origin to accountId] ?: true
    override suspend fun setAutomaticEnabled(origin: String, accountId: String, enabled: Boolean) { values[origin to accountId] = enabled }
}
