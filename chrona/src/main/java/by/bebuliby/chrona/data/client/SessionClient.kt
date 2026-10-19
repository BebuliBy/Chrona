package by.bebuliby.chrona.data.client

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class RemoteProfile(val accountId: String, val displayName: String, val telegramLinked: Boolean, val discoverable: Boolean = false) {
    override fun toString() = "RemoteProfile(redacted)"
}
data class AuthView(val origin: String = "", val loginPending: Boolean = false, val profile: RemoteProfile? = null,
                    val requiresLogin: Boolean = true, val loginRevision: Long = 0, val generation: Long = 0)
internal enum class BootstrapOutcome { NOT_SUPPLIED, APPLIED, PRESERVED, BLOCKED_EXISTING_BINDING }

/** All token use/rotation/config changes serialized. Never sends a credential across origins. */
class SessionClient(private val transport: HttpTransport, private val vault: SessionVault,
                    private val bind: suspend (String, String) -> Unit) {
    private val mutex = Mutex()
    private var connection = PrivateConnection()
    private val mutable = MutableStateFlow(AuthView())
    val state = mutable.asStateFlow()

    suspend fun initialize() = mutex.withLock {
        connection = ConnectionRecordCodec.normalize(vault.load())
        val retained = connection.session?.let { s ->
            bind(connection.origin, s.accountId)
            connection.cachedProfile ?: RemoteProfile(s.accountId, "Профиль", true)
        }
        // Last confirmed local identity is not a claim of a fresh /me. Network loss must not look like logout.
        mutable.value = AuthView(connection.origin, connection.pending != null, retained,
            connection.session == null || connection.session?.rotating == true)
        if (connection.session?.rotating == true) throw ClientFailure("SESSION_REFRESH_UNCERTAIN")
        // Lost exchange cannot be replayed, but must never discard an existing independent session.
        if (connection.pending?.exchanging == true) {
            persist(PrivateConnection(connection.origin, connection.session))
            throw ClientFailure("LOGIN_RESPONSE_LOST")
        }
    }
    private suspend fun persist(value: PrivateConnection, refreshInFlight: Boolean = false) {
        val same = value.origin == connection.origin && value.session?.accountId == connection.session?.accountId
        val cached = if (value.session == null) null else value.cachedProfile ?: if (same)
            mutable.value.profile ?: connection.cachedProfile ?: RemoteProfile(value.session.accountId, "Профиль", true)
            else RemoteProfile(value.session.accountId, "Профиль", true)
        val record = PrivateConnection(value.origin, value.session, value.pending, cached)
        val changedSession = record.origin != connection.origin || record.session?.sessionId != connection.session?.sessionId ||
            record.session?.accountId != connection.session?.accountId
        vault.save(record); connection = record
        mutable.value = mutable.value.copy(origin = record.origin, loginPending = record.pending != null,
            profile = cached, requiresLogin = record.session == null || (record.session?.rotating == true && !refreshInFlight),
            generation = mutable.value.generation + if (changedSession) 1 else 0)
    }
    suspend fun configure(raw: String): Boolean = mutex.withLock {
        val origin = ServerOrigin.parse(raw)
        if (origin == connection.origin) return@withLock false
        // Deliberately no old credentials sent even to revoke at the newly selected server.
        persist(PrivateConnection(origin)); true
    }
    /** First-configuration only. Never calls configure(), which intentionally clears an old session. */
    internal suspend fun bootstrap(raw: String, canBootstrap: suspend () -> Boolean): BootstrapOutcome = mutex.withLock {
        if (connection.origin.isNotBlank() || connection.session != null || connection.pending != null)
            return@withLock BootstrapOutcome.PRESERVED
        if (raw.isBlank()) return@withLock BootstrapOutcome.NOT_SUPPLIED
        val target = ServerOrigin.parse(raw) // same runtime guard as UI/transport/vault, no fake fallback
        if (!canBootstrap()) return@withLock BootstrapOutcome.BLOCKED_EXISTING_BINDING
        persist(PrivateConnection(target))
        BootstrapOutcome.APPLIED
    }
    suspend fun begin(deviceId: String): String = mutex.withLock {
        if (connection.origin.isBlank()) throw ClientFailure("SERVER_NOT_CONFIGURED")
        connection.pending?.let {
            if (it.expiresAt > Instant.now()) send(connection.origin, "POST", "/v1/auth/telegram/challenges/${it.requestId}/cancel", verifier(it))
            persist(PrivateConnection(connection.origin, connection.session))
        }
        val secret = Pkce.verifier()
        val reply = send(connection.origin, "POST", "/v1/auth/telegram/challenges", JSONObject()
            .put("deviceId", WireCodec.uuid(deviceId)).put("deviceLabel", "Chrona Android")
            .put("codeChallenge", Pkce.challenge(secret)).put("codeChallengeMethod", "S256").toString())
        val request = WireCodec.uuid(WireCodec.text(reply, "requestId"))
        val expires = WireCodec.stamp(reply, "expiresAt")
        val url = ServerOrigin.telegram(WireCodec.text(reply, "telegramStartUrl"), request)
        if (expires <= Instant.now()) throw ClientFailure("EXPIRED")
        persist(PrivateConnection(connection.origin, connection.session, PendingLogin(request, secret, expires)))
        url
    }
    suspend fun refreshLogin() {
        val revisionBefore = mutable.value.loginRevision
        mutex.withLock {
            val pending = connection.pending ?: return@withLock
            if (pending.exchanging || pending.expiresAt <= Instant.now()) {
                persist(PrivateConnection(connection.origin, connection.session)); throw ClientFailure("EXPIRED")
            }
            val reply = send(connection.origin, "POST", "/v1/auth/telegram/challenges/${pending.requestId}/status", verifier(pending))
            when (WireCodec.text(reply, "status")) {
                "pending" -> return@withLock
                "confirmed" -> {
                    persist(PrivateConnection(connection.origin, connection.session,
                        PendingLogin(pending.requestId, pending.verifier, pending.expiresAt, true)))
                    try {
                        val token = send(connection.origin, "POST", "/v1/auth/telegram/challenges/${pending.requestId}/exchange", verifier(pending))
                        val session = session(token)
                        try { bind(connection.origin, session.accountId) }
                        catch (error: Exception) {
                            runCatching { send(connection.origin, "DELETE", "/v1/sessions/current", null, session.access, UUID.randomUUID().toString()) }
                            throw error
                        }
                        persist(PrivateConnection(connection.origin, session))
                        mutable.value = mutable.value.copy(loginRevision = mutable.value.loginRevision + 1)
                        // Keep last confirmed local identity if /me is temporarily unreachable.
                    } catch (error: Exception) {
                        persist(PrivateConnection(connection.origin, connection.session)); throw error
                    }
                }
                "cancelled", "denied", "expired", "consumed" -> {
                    val status = WireCodec.text(reply, "status").uppercase(Locale.ROOT)
                    persist(PrivateConnection(connection.origin, connection.session)); throw ClientFailure(status)
                }
                else -> throw ClientFailure("INVALID_RESPONSE")
            }
        }
        // A completed exchange is already a valid session. Profile hydration failure is not failed login.
        // No pending request / ordinary Activity resume must not repeat /me and social GETs.
        if (mutable.value.loginRevision != revisionBefore) {
            try { me() }
            catch (failure: ClientFailure) {
                if (failure.code != "OFFLINE" && failure.httpStatus !in 500..599 && failure.httpStatus != 429) throw failure
            }
        }
    }
    suspend fun hasSession(): Boolean = mutex.withLock { connection.session != null }
    suspend fun refreshIsUncertain(): Boolean = mutex.withLock { connection.session?.rotating == true }
    suspend fun me(): RemoteProfile = mutex.withLock {
        try {
            // Request + cache persistence are one session operation. A concurrent rotation can no
            // longer insert a normal rotating marker between the GET and this profile save.
            val p = WireCodec.profile(authorizedLocked("GET", "/v1/me"))
            if (p.accountId != connection.session?.accountId) throw ClientFailure("ACCOUNT_BINDING_CONFLICT")
            bind(connection.origin, p.accountId)
            persist(PrivateConnection(connection.origin, connection.session, connection.pending, p))
            p
        } catch (failure: ClientFailure) {
            failure.sessionGeneration = mutable.value.generation
            throw failure
        }
    }
    suspend fun ownSharingRevision(friendId: String, origin: String, account: String): String? {
        val path = "/v1/friends/${WireCodec.uuid(friendId)}/sharing"
        return try { WireCodec.text(authorized("GET",path,expectedOrigin=origin,expectedAccount=account),"revision").also {
            if (it.isBlank()) throw ClientFailure("INVALID_RESPONSE")
        } } catch (error: ClientFailure) {
            if (error.httpStatus == 409 && error.code == "POLICY_UNRESOLVED") null else throw error
        }
    }
    suspend fun logout() {
        // Persist local logout BEFORE network IO. Crash/offline cannot resurrect the old session on restart.
        val prior = mutex.withLock { val old = connection; persist(PrivateConnection(old.origin)); old }
        var failure: Exception? = null
        try {
            prior.pending?.let { send(prior.origin, "POST", "/v1/auth/telegram/challenges/${it.requestId}/cancel", verifier(it)) }
        } catch (error: Exception) { failure = error }
        try {
            prior.session?.let { send(prior.origin, "DELETE", "/v1/sessions/current", null, it.access, UUID.randomUUID().toString()) }
        } catch (error: Exception) { failure = error }
        if (failure != null) throw ClientFailure("REMOTE_REVOKE_UNCONFIRMED")
    }
    suspend fun authorized(method: String, path: String, body: String? = null, key: String? = null,
                           expectedOrigin: String? = null, expectedAccount: String? = null): JSONObject = mutex.withLock {
        try { authorizedLocked(method, path, body, key, expectedOrigin, expectedAccount) }
        catch (failure: ClientFailure) { failure.sessionGeneration = mutable.value.generation; throw failure }
    }
    private suspend fun authorizedLocked(method: String, path: String, body: String? = null, key: String? = null,
                           expectedOrigin: String? = null, expectedAccount: String? = null): JSONObject {
        var s = connection.session ?: throw ClientFailure("LOGIN_REQUIRED")
        if ((expectedOrigin != null && expectedOrigin != connection.origin) ||
            (expectedAccount != null && expectedAccount != s.accountId)) throw ClientFailure("ACCOUNT_BINDING_CONFLICT")
        if (s.rotating) throw ClientFailure("SESSION_REFRESH_UNCERTAIN")
        var refreshed = false
        if (s.expiresAt <= Instant.now().plusSeconds(5)) { s = rotate(s); refreshed = true }
        return try { send(connection.origin, method, path, body, s.access, key) }
        catch (error: ClientFailure) {
            if (error.httpStatus != 401 || error.code != "UNAUTHENTICATED") throw error
            if (refreshed) { persist(PrivateConnection(connection.origin)); throw ClientFailure("LOGIN_REQUIRED", 401) }
            val replacement = rotate(s)
            try { send(connection.origin, method, path, body, replacement.access, key) }
            catch (again: ClientFailure) {
                if (again.httpStatus == 401 && again.code == "UNAUTHENTICATED") persist(PrivateConnection(connection.origin))
                throw again
            }
        }
    }
    private suspend fun rotate(old: ClientSession): ClientSession = withContext(NonCancellable) {
        if (old.rotating) throw ClientFailure("SESSION_REFRESH_UNCERTAIN")
        persist(PrivateConnection(connection.origin, ClientSession(old.accountId, old.sessionId, old.access,
            old.refresh, old.expiresAt, true), connection.pending), refreshInFlight = true)
        try {
            val result = send(connection.origin, "POST", "/v1/auth/refresh", JSONObject().put("refreshToken", old.refresh).toString())
            val fresh = session(result)
            if (fresh.accountId != old.accountId || fresh.sessionId != old.sessionId) throw ClientFailure("ACCOUNT_BINDING_CONFLICT")
            persist(PrivateConnection(connection.origin, fresh, connection.pending)); return@withContext fresh
        } catch (cancelled: CancellationException) {
            mutable.value = mutable.value.copy(requiresLogin = true)
            throw cancelled // durable rotating marker retained
        }
        catch (failure: ClientFailure) {
            if (failure.httpStatus == 401 && failure.code == "UNAUTHENTICATED") {
                persist(PrivateConnection(connection.origin)); throw ClientFailure("LOGIN_REQUIRED", 401)
            }
            if (failure.code == "OFFLINE" && failure.requestMayHaveBeenSent == false) {
                // Definitely no refresh body sent (DNS/connect/TLS failed): the old refresh is not spent.
                persist(PrivateConnection(connection.origin, old, connection.pending))
                throw failure
            }
            // MAY have rotated: preserve encrypted state, prohibit reuse. Recovery needs a server contract.
            mutable.value = mutable.value.copy(requiresLogin = true)
            throw ClientFailure("SESSION_REFRESH_UNCERTAIN")
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(requiresLogin = true)
            throw ClientFailure("SESSION_REFRESH_UNCERTAIN")
        }
    }
    private fun session(j: JSONObject): ClientSession {
        val account = j.getJSONObject("account")
        val access = WireCodec.text(j, "accessToken"); val refresh = WireCodec.text(j, "refreshToken")
        if (!access.matches(Regex("[A-Za-z0-9_-]{43}")) || !refresh.matches(Regex("[A-Za-z0-9_-]{43}"))) throw ClientFailure("INVALID_RESPONSE")
        return ClientSession(WireCodec.uuid(WireCodec.text(account, "accountId")), WireCodec.uuid(WireCodec.text(j, "sessionId")),
            access, refresh, WireCodec.stamp(j, "accessExpiresAt"))
    }
    private fun verifier(p: PendingLogin) = JSONObject().put("codeVerifier", p.verifier).toString()
    private suspend fun send(origin: String, method: String, path: String, body: String? = null,
                             bearer: String? = null, key: String? = null): JSONObject {
        if (origin.isEmpty()) throw ClientFailure("SERVER_NOT_CONFIGURED")
        val response = try { transport.execute(HttpRequest(origin, method, path, body, bearer, key)) }
        catch (error: ClientFailure) { throw error }
        catch (_: IOException) { throw ClientFailure("OFFLINE") }
        val json = try { JSONObject(response.body) } catch (_: Exception) { throw ClientFailure("INVALID_RESPONSE", response.status) }
        if (response.status !in 200..299) {
            val raw = json.optJSONObject("error")?.optString("code").orEmpty()
            throw ClientFailure(raw.takeIf { it.matches(Regex("[A-Z_]{1,80}")) } ?: "HTTP_ERROR", response.status)
        }
        return json
    }
}
