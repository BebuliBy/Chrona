package by.bebuliby.chrona.backend

import kotlinx.serialization.json.*
import java.sql.Connection
import java.time.Clock
import java.time.Instant

data class Principal(val accountId: String, val sessionId: String, val deviceId: String)
data class LoginChallenge(val id: String, val deviceId: String, val label: String, val challenge: String,
                          val expires: Long, val state: String, val telegramId: Long?, val accountId: String?)

class Identity(val db: Database, val clock: Clock, val botUsername: String? = null,
               val telegramAuthEnabled: Boolean = !botUsername.isNullOrBlank()) {
    init { require(!telegramAuthEnabled || (botUsername != null && botUsername.matches(Regex("[A-Za-z0-9_]{5,32}")))) }
    val challengeTtlMs = 5 * 60_000L
    val accessTtlMs = 15 * 60_000L
    val refreshTtlMs = 30 * 24 * 60 * 60_000L
    fun now() = clock.millis()

    fun createChallenge(input: JsonObject, remoteBucket: String): JsonObject {
        requireWire(telegramAuthEnabled, "TELEGRAM_NOT_CONFIGURED", 503)
        val configuredName = requireNotNull(botUsername)
        return db.tx { c ->
        input.only("deviceId", "deviceLabel", "codeChallenge", "codeChallengeMethod")
        val device = uuid(input.text("deviceId"))
        val label = input.text("deviceLabel")
        requireWire(label.isNotBlank() && label.length <= 80 && label.none { it.isISOControl() })
        val challenge = input.text("codeChallenge")
        requireWire(challenge.matches(Regex("[A-Za-z0-9_-]{43}")) && input.text("codeChallengeMethod") == "S256")
        c.rate("login-ip:${hash(remoteBucket)}", now(), 20)
        c.rate("login-device:$device", now(), 5)
        val request = id()
        val expires = now() + challengeTtlMs
        c.exec("INSERT INTO login_challenges (id,device_id,device_label,challenge,expires_ms,state) VALUES (?,?,?,?,?,'pending')", request, device, label, challenge, expires)
        obj("requestId" to str(request), "expiresAt" to str(Instant.ofEpochMilli(expires).toString()),
            "telegramStartUrl" to str("https://t.me/$configuredName?start=$request"))
        }
    }

    private fun challenge(c: Connection, request: String): LoginChallenge = c.query("SELECT * FROM login_challenges WHERE id=?", uuid(request)) { r ->
        LoginChallenge(r.getString("id"), r.getString("device_id"), r.getString("device_label"), r.getString("challenge"),
            r.getLong("expires_ms"), r.getString("state"), (r.getObject("telegram_id") as? Number)?.toLong(), r.getString("account_id"))
    }.firstOrNull() ?: fail(404, "NOT_FOUND")

    private fun state(q: LoginChallenge): String = if (q.state in setOf("pending", "confirmed") && now() >= q.expires) "expired" else q.state
    private fun verify(q: LoginChallenge, input: JsonObject) {
        input.only("codeVerifier")
        val verifier = input.text("codeVerifier")
        requireWire(verifier.matches(Regex("[A-Za-z0-9._~-]{43,128}")))
        requireWire(constantEquals(hash(verifier), q.challenge), "UNAUTHENTICATED", 401)
    }
    fun status(request: String, input: JsonObject): JsonObject = db.tx { c ->
        val q = challenge(c, request); verify(q, input)
        obj("status" to str(state(q)), "expiresAt" to str(Instant.ofEpochMilli(q.expires).toString()))
    }
    fun cancel(request: String, input: JsonObject): JsonObject = db.tx { c ->
        val q = challenge(c, request); verify(q, input)
        requireWire(state(q) != "consumed", "CONSUMED", 410)
        requireWire(state(q) != "expired", "EXPIRED", 410)
        c.exec("UPDATE login_challenges SET state='cancelled' WHERE id=?", q.id)
        obj("status" to str("cancelled"))
    }

    // Called ONLY by the trusted polling adapter after private-chat sender checks.
    // /start binds this request to one stable Telegram sender, but does NOT confirm login.
    fun botStart(c: Connection, request: String, sender: Long): LoginChallenge {
        val q = challenge(c, request)
        requireWire(state(q) == "pending", if (state(q) == "expired") "EXPIRED" else "CONSUMED", 410)
        requireWire(q.telegramId == null || q.telegramId == sender, "FORBIDDEN", 403)
        c.exec("UPDATE login_challenges SET telegram_id=?,chat_id=? WHERE id=?", sender, sender, q.id)
        return q
    }
    fun botConfirm(c: Connection, request: String, sender: Long, displayName: String, accept: Boolean, username: String? = null) {
        val q = challenge(c, request)
        requireWire(q.telegramId == sender, "FORBIDDEN", 403)
        requireWire(state(q) == "pending", if (state(q) == "expired") "EXPIRED" else "CONSUMED", 410)
        if (!accept) { c.exec("UPDATE login_challenges SET state='denied' WHERE id=?", q.id); return }
        val account = c.query("SELECT id FROM accounts WHERE telegram_id=?", sender) { it.getString(1) }.firstOrNull() ?: id().also {
            c.exec("INSERT INTO accounts (id,telegram_id,display_name,discoverable) VALUES (?,?,?,FALSE)", it, sender, confirmedName(displayName,username))
            Activities.provisionShorts(c, it, Instant.ofEpochMilli(now()).toString())
        }
        // Refresh this optional public attribute ONLY after explicit confirmed login.
        // A changed/absent username never changes identity or discoverability consent.
        c.exec("UPDATE accounts SET display_name=? WHERE id=?",confirmedName(displayName,username),account)
        // Username never used as identity. New accounts are NOT discoverable by default.
        c.exec("UPDATE login_challenges SET state='confirmed',account_id=? WHERE id=?", account, q.id)
    }
    fun safeName(value: String) = value.filterNot { it.isISOControl() }.take(120).ifBlank { "Chrona user" }
    fun confirmedName(name: String, username: String?): String {
        val nick = username?.takeIf { it.matches(Regex("[A-Za-z0-9_]{5,32}")) }?.lowercase(java.util.Locale.ROOT)
        return if (nick == null) safeName(name) else "${safeName(name).take(80)} (@$nick)"
    }

    fun exchange(request: String, input: JsonObject): JsonObject = db.tx { c ->
        val q = challenge(c, request); verify(q, input)
        val s = state(q)
        if (s == "expired") fail(410, "EXPIRED")
        if (s == "consumed") fail(410, "CONSUMED")
        requireWire(s == "confirmed" && q.accountId != null, "LOGIN_NOT_CONFIRMED", 409)
        val session = id(); val access = secret(); val refresh = secret()
        c.exec("INSERT INTO device_sessions (id,account_id,device_id,revoked,access_hash,access_expires_ms,refresh_hash,refresh_expires_ms) VALUES (?,?,?,FALSE,?,?,?,?)",
            session, q.accountId, q.deviceId, hash(access), now() + accessTtlMs, hash(refresh), now() + refreshTtlMs)
        c.exec("UPDATE login_challenges SET state='consumed' WHERE id=?", q.id)
        tokenResponse(c, q.accountId!!, session, access, refresh)
    }
    private fun tokenResponse(c: Connection, account: String, session: String, access: String, refresh: String) = obj(
        "account" to publicUser(c, account), "sessionId" to str(session), "accessToken" to str(access),
        "accessExpiresAt" to str(Instant.ofEpochMilli(now() + accessTtlMs).toString()), "refreshToken" to str(refresh))

    fun authenticate(token: String): Principal = db.tx { c ->
        requireWire(token.matches(Regex("[A-Za-z0-9_-]{43}")), "UNAUTHENTICATED", 401)
        c.query("SELECT * FROM device_sessions WHERE access_hash=? AND revoked=FALSE AND access_expires_ms>?", hash(token), now()) {
            Principal(it.getString("account_id"), it.getString("id"), it.getString("device_id"))
        }.firstOrNull() ?: fail(401, "UNAUTHENTICATED")
    }
    fun checkSession(c: Connection, p: Principal) {
        requireWire(c.query("SELECT id FROM device_sessions WHERE id=? AND account_id=? AND device_id=? AND revoked=FALSE AND access_expires_ms>?",
            p.sessionId, p.accountId, p.deviceId, now()) { true }.isNotEmpty(), "UNAUTHENTICATED", 401)
    }
    fun refresh(input: JsonObject): JsonObject {
        input.only("refreshToken")
        val token = input.text("refreshToken")
        requireWire(token.matches(Regex("[A-Za-z0-9_-]{43}")), "UNAUTHENTICATED", 401)
        // Reuse detection must COMMIT the family revocation, not roll it back with the error.
        val result = db.tx { c ->
            val digest = hash(token)
            val spentSession = c.query("SELECT session_id FROM spent_refresh WHERE token_hash=?", digest) { it.getString(1) }.firstOrNull()
            if (spentSession != null) {
                c.exec("UPDATE device_sessions SET revoked=TRUE WHERE id=?", spentSession)
                null
            } else {
                val session = c.query("SELECT id,account_id FROM device_sessions WHERE refresh_hash=? AND revoked=FALSE AND refresh_expires_ms>?", digest, now()) {
                    it.getString(1) to it.getString(2)
                }.firstOrNull()
                if (session == null) null else {
                    val access = secret(); val refresh = secret()
                    c.exec("INSERT INTO spent_refresh VALUES (?,?)", digest, session.first)
                    c.exec("UPDATE device_sessions SET access_hash=?,access_expires_ms=?,refresh_hash=?,refresh_expires_ms=? WHERE id=?",
                        hash(access), now() + accessTtlMs, hash(refresh), now() + refreshTtlMs, session.first)
                    tokenResponse(c, session.second, session.first, access, refresh)
                }
            }
        }
        return result ?: fail(401, "UNAUTHENTICATED")
    }
    fun revoke(p: Principal, key: String? = null) = db.tx { c ->
        checkSession(c, p)
        val action = { c.exec("UPDATE device_sessions SET revoked=TRUE WHERE id=?", p.sessionId); obj("revoked" to bool(true)) }
        if (key == null) action() else Activities(this).idempotent(c,p.accountId,key,obj("path" to str("sessions/current"),"sessionId" to str(p.sessionId)),action)
    }
    fun me(p: Principal) = db.tx { c ->
        checkSession(c, p)
        ownProfile(c,p.accountId)
    }
    private fun ownProfile(c: Connection, owner: String): JsonObject {
        val discoverable = c.query("SELECT discoverable FROM accounts WHERE id=?",uuid(owner)) { it.getBoolean(1) }.firstOrNull() ?: fail(404,"NOT_FOUND")
        return JsonObject(publicUser(c,owner) + mapOf("telegramLinked" to bool(true),"discoverable" to bool(discoverable)))
    }
    fun setDiscoverability(c: Connection, owner: String, enabled: Boolean): JsonObject {
        requireWire(c.exec("UPDATE accounts SET discoverable=? WHERE id=?",enabled,uuid(owner)) == 1,"NOT_FOUND",404)
        return ownProfile(c,owner)
    }
    fun configureDiscoverability(p: Principal, key: String, body: JsonObject): JsonObject = db.tx { c ->
        checkSession(c,p); body.only("enabled"); val enabled = body.booleanAt("enabled")
        Activities(this).idempotent(c,p.accountId,key,obj("path" to str("me/discoverability"),"body" to body)) {
            setDiscoverability(c,p.accountId,enabled)
        }
    }
    fun publicUser(c: Connection, account: String): JsonObject = c.query("SELECT id,display_name FROM accounts WHERE id=?", uuid(account)) {
        obj("accountId" to str(it.getString(1)), "displayName" to str(it.getString(2)))
    }.firstOrNull() ?: fail(404, "NOT_FOUND")
    fun accountForTelegram(c: Connection, sender: Long) = c.query("SELECT id FROM accounts WHERE telegram_id=?", sender) { it.getString(1) }.firstOrNull() ?: fail(401, "UNAUTHENTICATED")
}
