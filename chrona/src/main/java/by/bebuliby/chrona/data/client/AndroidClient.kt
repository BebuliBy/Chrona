package by.bebuliby.chrona.data.client

import by.bebuliby.chrona.data.LocalSettings
import by.bebuliby.chrona.data.LocalSettingsSnapshot
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
    val notice: UiNotice? = null)

class AndroidClient internal constructor(val auth: SessionClient, private val store: ClientSyncStore,
                    private val identities: Flow<LocalSettingsSnapshot>, private val scope: CoroutineScope) {
    constructor(auth: SessionClient, store: ClientSyncStore, settings: LocalSettings, scope: CoroutineScope) :
        this(auth, store, settings.snapshots, scope)
    private val mutable = MutableStateFlow(ClientView())
    val state = mutable.asStateFlow()
    private val syncMutex = Mutex()
    private var backoff = 5_000L

    suspend fun initialize() {
        scope.launch { auth.state.collect { a -> mutable.update { it.copy(baseUrl = a.origin,
            loginPending = a.loginPending, profile = a.profile,
            friends = if (a.profile == null) FriendsUiState(FeatureStatus.NOT_CONNECTED) else it.friends) } } }
        try {
            auth.initialize()
            if (auth.hasSession()) auth.me()
            else if (auth.state.value.loginPending) auth.refreshLogin()
            updateMeta()
            if (store.meta()?.errorCode == "CONSENT_RECONFIRM_REQUIRED") notify(ClientFailure("CONSENT_RECONFIRM_REQUIRED"))
        } catch (error: Exception) { notify(error) }
        scope.launch {
            while (isActive) {
                delay(backoff)
                try {
                    if (auth.hasSession()) {
                        retrySocial()
                        val m = store.meta()
                        if (m?.uploadEnabled == true && m.errorCode == null) retryApproved()
                    }
                    backoff = 30_000
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { notify(error); backoff = (backoff * 2).coerceAtMost(120_000) }
            }
        }
    }
    suspend fun configure(url: String) {
        if (auth.configure(url)) {
            store.pauseConsent()
            mutable.update { ClientView(baseUrl = auth.state.value.origin) }
        }
    }
    suspend fun beginLogin(): String = auth.begin(identities.first { it.deviceId.isNotEmpty() }.deviceId)
    suspend fun refreshLogin() {
        auth.refreshLogin()
        if (auth.state.value.profile != null) refreshFriends()
        updateMeta()
    }
    suspend fun logout() {
        store.pauseConsent()
        try { auth.logout() } finally { mutable.update { ClientView(baseUrl = auth.state.value.origin) } }
    }
    suspend fun requestSync() {
        if (!syncMutex.tryLock()) throw ClientFailure("SYNC_IN_PROGRESS")
        try {
            val target = auth.state.value
            val p = target.profile ?: throw ClientFailure("LOGIN_REQUIRED")
            val profile = identities.first { it.profileId.isNotBlank() }
            // Capture BEFORE HTTP, under the same mutex that handles double taps/retry cycles.
            val approved = store.approveSnapshot(target.origin, p.accountId, profile.profileId)
            syncApproved(approved)
        } finally { syncMutex.unlock() }
    }
    private suspend fun retryApproved() = syncMutex.withLock {
        val profile = identities.first { it.profileId.isNotBlank() }
        val approved = store.scopeForRetry(profile.profileId) ?: return@withLock
        syncApproved(approved)
    }
    private suspend fun syncApproved(approved: UploadScopeRow) {
        val profile = auth.state.value.profile ?: auth.me()
        val meta = store.meta() ?: throw ClientFailure("LOGIN_REQUIRED")
        SyncWire.binding(approved.origin, approved.accountId, auth.state.value.origin, profile.accountId)
        val local = identities.first { it.profileId.isNotBlank() }
        if (approved.localProfileId != local.profileId || meta.origin != approved.origin || meta.accountId != approved.accountId)
            throw ClientFailure("CONSENT_SCOPE_CONFLICT")
        mutable.update { it.copy(syncStatus = SyncStatus.SYNCING) }
        try {
            // A prior send may have committed remotely but its response/ack was lost.
            // Replay frozen requests BEFORE pull, so our own echo cannot be mistaken for a local collision.
            for (wire in store.approvedFrozen(approved)) {
                wire.errorCode?.let { throw ClientFailure(it, 409) }
                execute(wire, approved)
            }
            pull()
            val device = identities.first { it.deviceId.isNotBlank() }.deviceId
            while (true) {
                val wire = store.nextRequest(device, approved) ?: break
                execute(wire, approved)
            }
            pull()
            if (!store.success(approved)) throw ClientFailure("CONSENT_SCOPE_CONFLICT")
            updateMeta()
            mutable.update { it.copy(notice = UiNotice(UUID.randomUUID().toString(), "SYNC_SNAPSHOT_COMPLETE",
                "Подтверждённый снимок отправлен. Новые записи ждут следующего ручного согласия.")) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val code = (error as? ClientFailure)?.code ?: "CLIENT_ERROR"
            // Transient delivery failure retains a retryable frozen request, hard conflicts remain explicit.
            if (!retryable(error)) store.error(code)
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
        if (wire.outboxId != null) store.requireApproved(wire, approved ?: throw ClientFailure("CONSENT_REQUIRED"))
        try {
            val result = auth.authorized(wire.method, wire.path, wire.body, wire.requestId, wire.origin, wire.accountId)
            store.acknowledge(wire, result)
            if (wire.path == "/v1/me/discoverability") auth.me() // authoritative state, including after a durable retry
        } catch (error: ClientFailure) {
            if (error.httpStatus in setOf(400, 403, 404, 409, 410, 422)) store.failed(wire, error.code)
            throw error
        }
    }
    private suspend fun retrySocial() = syncMutex.withLock {
        // Does not upload raw data before RequestSync; each social request was itself explicitly submitted.
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
        if (auth.state.value.profile != null && m?.errorCode in setOf("LOGIN_REQUIRED", "UNAUTHENTICATED", "OFFLINE", "SERVER_NOT_CONFIGURED")) {
            store.error(null); m = store.meta()
        }
        mutable.update { it.copy(syncStatus = when {
            m?.errorCode != null -> SyncStatus.ERROR
            auth.state.value.profile != null && (m?.uploadEnabled == true || m?.lastSuccessAt != null) -> SyncStatus.IDLE
            else -> SyncStatus.LOCAL_ONLY
        }, lastSuccessAt = m?.lastSuccessAt) }
    }
    fun notify(error: Exception) {
        val code = (error as? ClientFailure)?.code ?: "CLIENT_ERROR"
        val message = when (code) {
            "SERVER_NOT_CONFIGURED" -> "Укажите HTTPS-адрес своего сервера в настройках."
            "INVALID_ORIGIN" -> "Нужен HTTPS base URL с допустимым необязательным префиксом, без логина, query, fragment и обходов пути."
            "TELEGRAM_NOT_CONFIGURED" -> "На сервере ещё не настроен Telegram-бот. Вход не выполнен."
            "OFFLINE" -> "Сервер недоступен. Локальные данные и очередь сохранены."
            "ACCOUNT_BINDING_CONFLICT" -> "История привязана к другому адресу (включая префикс) или аккаунту. Вернитесь к прежнему серверу либо согласуйте отдельный перенос; автоматической перепривязки/очистки нет."
            "LOGIN_REQUIRED", "LOGIN_RESPONSE_LOST" -> "Нужен новый вход. Одноразовый ответ не удалось безопасно восстановить."
            "REMOTE_REVOKE_UNCONFIRMED" -> "Локальная сессия удалена. Отзыв на сервере не подтверждён."
            "REVISION_CONFLICT", "PULL_LOCAL_CONFLICT", "IDEMPOTENCY_CONFLICT" -> "Конфликт данных. Локальные записи и операция сохранены; автоматической перезаписи нет."
            "POLICY_UNRESOLVED" -> "Для этой операции сервер требует ещё не выбранное правило. Данные не удалены."
            "SOCIAL_PENDING" -> "Предыдущая операция для этой настройки ещё не подтверждена или конфликтует. Новый набор не отправлен; автоматической перезаписи нет."
            "CONSENT_REQUIRED", "CONSENT_RECONFIRM_REQUIRED" -> "Нужно подтвердить новый ручной снимок синхронизации. Старые записи и запросы сохранены."
            "CONSENT_SCOPE_CONFLICT" -> "Адрес, профиль или снимок согласия изменился. Передача остановлена без удаления записей."
            "CONSENT_DEPENDENCY_PENDING" -> "Зависимость записи не входит в подтверждённый снимок и ещё не известна серверу. Дополнительные данные автоматически не отправлены."
            "SYNC_IN_PROGRESS" -> "Подтверждённый запуск уже выполняется; его снимок не расширен повторным нажатием."
            else -> "Операция не подтверждена ($code)."
        }
        mutable.update { it.copy(notice = UiNotice(UUID.randomUUID().toString(), code, message)) }
    }
}
