package by.bebuliby.chrona.backend

import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.Callable
import kotlin.test.*

class IdentityTest {
    @Test fun `start is not confirmation and public request cannot exchange without verifier`() {
        val f = Fixture(); val (q,v) = f.challenge(); val request = q.text("requestId")
        f.db.tx { f.identity.botStart(it,request,101) }
        assertEquals("pending",f.identity.status(request,obj("codeVerifier" to str(v))).text("status"))
        assertFault("LOGIN_NOT_CONFIRMED") { f.identity.exchange(request,obj("codeVerifier" to str(v))) }
        assertFault("UNAUTHENTICATED") { f.identity.status(request,obj("codeVerifier" to str(secret()))) }
        assertFault("UNAUTHENTICATED") { f.identity.exchange(request,obj("codeVerifier" to str(secret()))) }
    }
    @Test fun `bound stable sender prevents session swapping and no account leaks in status`() {
        val f = Fixture(); val (a,av) = f.challenge(); val (b,bv) = f.challenge()
        f.db.tx { f.identity.botStart(it,a.text("requestId"),101) }
        assertFault("FORBIDDEN") { f.db.tx { f.identity.botStart(it,a.text("requestId"),202) } }
        assertFault("FORBIDDEN") { f.db.tx { f.identity.botConfirm(it,a.text("requestId"),202,"attacker",true) } }
        f.db.tx { f.identity.botConfirm(it,a.text("requestId"),101,"FAKE",true) }
        assertFault("UNAUTHENTICATED") { f.identity.exchange(a.text("requestId"),obj("codeVerifier" to str(bv))) }
        assertFault("LOGIN_NOT_CONFIRMED") { f.identity.exchange(b.text("requestId"),obj("codeVerifier" to str(bv))) }
        assertEquals(setOf("status","expiresAt"),f.identity.status(a.text("requestId"),obj("codeVerifier" to str(av))).keys)
    }
    @Test fun `exchange consumes atomically under concurrent replay`() {
        val f = Fixture(); val (q,v) = f.challenge(); val request = q.text("requestId")
        f.db.tx { f.identity.botStart(it,request,101); f.identity.botConfirm(it,request,101,"FAKE",true) }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll((1..4).map { Callable {
                try { f.identity.exchange(request,obj("codeVerifier" to str(v))); "OK" } catch (e: Fault) { e.code }
            } }).map { it.get() }
            assertEquals(1,results.count { it == "OK" }); assertEquals(3,results.count { it == "CONSUMED" })
            assertEquals(1,f.db.tx { it.query("SELECT id FROM device_sessions") { true }.size })
        } finally { pool.shutdownNow() }
    }
    @Test fun `expiry cancel and denial never create session`() {
        val f = Fixture(); val (q,v) = f.challenge(); f.clock.advance(300_000)
        assertEquals("expired",f.identity.status(q.text("requestId"),obj("codeVerifier" to str(v))).text("status"))
        assertFault("EXPIRED") { f.identity.exchange(q.text("requestId"),obj("codeVerifier" to str(v))) }
        val (cancel,cv) = f.challenge()
        f.identity.cancel(cancel.text("requestId"),obj("codeVerifier" to str(cv)))
        assertFault("LOGIN_NOT_CONFIRMED") { f.identity.exchange(cancel.text("requestId"),obj("codeVerifier" to str(cv))) }
        assertFault("CONSUMED") { f.db.tx { f.identity.botStart(it,cancel.text("requestId"),101) } }
        val (deny,dv) = f.challenge()
        f.db.tx { f.identity.botStart(it,deny.text("requestId"),101); f.identity.botConfirm(it,deny.text("requestId"),101,"FAKE",false) }
        assertEquals("denied",f.identity.status(deny.text("requestId"),obj("codeVerifier" to str(dv))).text("status"))
        assertEquals(0,f.db.tx { it.query("SELECT id FROM device_sessions") { true }.size })
    }
    @Test fun `refresh rotation rejects old access and reuse revokes new token family durably`() {
        val f = Fixture(); val login = f.login()
        val rotated = f.identity.refresh(obj("refreshToken" to str(login.refresh)))
        assertFault("UNAUTHENTICATED") { f.identity.authenticate(login.access) }
        assertEquals(login.principal.accountId,f.identity.authenticate(rotated.text("accessToken")).accountId)
        assertFault("UNAUTHENTICATED") { f.identity.refresh(obj("refreshToken" to str(login.refresh))) }
        assertFault("UNAUTHENTICATED") { f.identity.authenticate(rotated.text("accessToken")) }
        assertFault("UNAUTHENTICATED") { f.identity.refresh(obj("refreshToken" to str(rotated.text("refreshToken")))) }
    }
    @Test fun `stable Telegram id not username and current session revocation isolated`() {
        val f = Fixture(); val a = f.login(101); val b = f.login(101); val other = f.login(202)
        assertEquals(a.principal.accountId,b.principal.accountId); assertNotEquals(a.principal.accountId,other.principal.accountId)
        f.identity.revoke(a.principal)
        assertFault("UNAUTHENTICATED") { f.identity.authenticate(a.access) }
        assertFault("UNAUTHENTICATED") { f.identity.refresh(obj("refreshToken" to str(a.refresh))) }
        assertEquals(b.principal,f.identity.authenticate(b.access))
        assertFalse(f.identity.me(b.principal).toString().contains("telegram_id"))
        val raw = f.db.tx { it.query("SELECT access_hash,refresh_hash FROM device_sessions") { r -> r.getString(1) + r.getString(2) }.joinToString() }
        assertFalse(raw.contains(b.access)); assertFalse(raw.contains(b.refresh))
    }
    @Test fun `challenge denies callback URLs unknown properties and non S256`() {
        val f = Fixture(); val body = obj("deviceId" to str(id()),"deviceLabel" to str("FAKE"),"codeChallenge" to str(hash(secret())),"codeChallengeMethod" to str("S256"))
        assertFault("VALIDATION") { f.identity.createChallenge(kotlinx.serialization.json.JsonObject(body + ("callbackUrl" to str("https://evil.invalid"))),"fake") }
        assertFault("VALIDATION") { f.identity.createChallenge(kotlinx.serialization.json.JsonObject(body + ("codeChallengeMethod" to str("plain"))),"fake") }
    }
    @Test fun `login security TTL and rate limits are bounded`() {
        val f = Fixture(); val device = id()
        repeat(5) { f.challenge(device) }
        assertFault("RATE_LIMITED") { f.challenge(device) }
        f.clock.advance(60_000); f.challenge(device)
        val login = f.login(); f.clock.advance(900_000)
        assertFault("UNAUTHENTICATED") { f.identity.authenticate(login.access) }
    }
}
