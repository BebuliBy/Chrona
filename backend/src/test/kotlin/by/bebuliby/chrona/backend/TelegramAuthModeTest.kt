package by.bebuliby.chrona.backend

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.test.*

class TelegramAuthModeTest {
    private fun env() = mapOf("SECRETS_PATH" to "/run/secrets/chrona",
        "DB_URL" to "jdbc:postgresql://db:5432/chrona")

    @Test fun `API and migrations configuration need no bot name or Telegram settings`() {
        for (settings in listOf(env(),env() + ("BOT_USERNAME" to " "),env() + ("TELEGRAM_AUTH_ENABLED" to "false"))) {
            val api = RuntimeConfig.from(settings,"api")
            assertNull(api.botUsername); assertFalse(api.telegramAuthEnabled)
            assertFalse(RuntimeConfig.from(settings,"migrate").telegramAuthEnabled)
        }
        // A migration process ignores even an unfinished enabled auth configuration.
        assertFalse(RuntimeConfig.from(env() + ("TELEGRAM_AUTH_ENABLED" to "true"),"migrate").telegramAuthEnabled)
    }

    @Test fun `enabled mode requires a valid configured name and false explicitly disables begin`() {
        val configured = env() + ("BOT_USERNAME" to "ChronaFakeTestBot")
        assertTrue(RuntimeConfig.from(configured,"api").telegramAuthEnabled)
        assertTrue(RuntimeConfig.from(configured + ("TELEGRAM_AUTH_ENABLED" to "true"),"api").telegramAuthEnabled)
        assertFalse(RuntimeConfig.from(configured + ("TELEGRAM_AUTH_ENABLED" to "false"),"api").telegramAuthEnabled)
        assertFailsWith<IllegalArgumentException> { RuntimeConfig.from(env() + ("TELEGRAM_AUTH_ENABLED" to "true"),"api") }
        assertFailsWith<IllegalArgumentException> { RuntimeConfig.from(configured + ("BOT_USERNAME" to "https://evil.invalid"),"api") }
        assertFailsWith<IllegalStateException> { RuntimeConfig.from(configured + ("TELEGRAM_AUTH_ENABLED" to "maybe"),"api") }
    }

    @Test fun `bot still requires name and token file and cannot silently use disabled API defaults`(@TempDir dir: Path) {
        val botEnv = env() + ("TELEGRAM_PROXY" to "socks5h://proxy:10808")
        assertFailsWith<IllegalArgumentException> { RuntimeConfig.from(botEnv,"bot") }
        assertFailsWith<IllegalArgumentException> { RuntimeConfig.from(botEnv + ("TELEGRAM_AUTH_ENABLED" to "false"),"bot") }
        val configured = RuntimeConfig.from(botEnv + mapOf("BOT_USERNAME" to "ChronaFakeTestBot","SECRETS_PATH" to dir.toString()),"bot")
        // This is a deliberately absent TEST file, never a real secret/token read.
        assertFailsWith<NoSuchFileException> { configured.readSecret("telegram-token") }
    }

    @Test fun `API boots with missing name health and existing session core work but begin is 503`() = testApplication {
        val f = Fixture(); val existing = f.login()
        val config = RuntimeConfig.from(env(),"api")
        val identity = Identity(f.db,f.clock,config.botUsername,config.telegramAuthEnabled)
        application { chrona(Backend(identity)) }
        assertEquals(HttpStatusCode.OK,client.get("/health/live").status)
        assertEquals(HttpStatusCode.OK,client.get("/health/ready").status)
        assertEquals(HttpStatusCode.OK,client.get("/v1/me") { bearerAuth(existing.access) }.status)
        assertEquals(HttpStatusCode.OK,client.get("/v1/sync/pull") { bearerAuth(existing.access) }.status)
        assertEquals(HttpStatusCode.Unauthorized,client.get("/v1/sync/pull").status)
        val before = f.db.tx { it.query("SELECT id FROM login_challenges") { true }.size }
        val response = client.post("/v1/auth/telegram/challenges") {
            contentType(ContentType.Application.Json)
            setBody(canonical(obj("deviceId" to str(id()),"deviceLabel" to str("FAKE unavailable auth"),
                "codeChallenge" to str(hash(secret())),"codeChallengeMethod" to str("S256"))))
        }
        assertEquals(HttpStatusCode.ServiceUnavailable,response.status)
        assertEquals("TELEGRAM_NOT_CONFIGURED",parseObject(response.bodyAsText()).errorCode())
        assertFalse(response.bodyAsText().contains("t.me")); assertFalse(response.bodyAsText().contains("telegramStartUrl"))
        assertEquals(before,f.db.tx { it.query("SELECT id FROM login_challenges") { true }.size })
    }

    @Test fun `explicit disabled mode with a name never fabricates a start URL or challenge`() {
        val f = Fixture()
        val disabled = Identity(f.db,f.clock,"ChronaFakeTestBot",false)
        assertFault("TELEGRAM_NOT_CONFIGURED") { disabled.createChallenge(obj(),"fake") }
        assertEquals(0,f.db.tx { it.query("SELECT id FROM login_challenges") { true }.size })
    }

    @Test fun `disabled begin preserves verifier expiry confirmation cancellation and one use security`() {
        val f = Fixture(); val (expired,ev) = f.challenge()
        f.clock.advance(300_000)
        val (confirmed,cv) = f.challenge(); val request = confirmed.text("requestId")
        f.db.tx { f.identity.botStart(it,request,101); f.identity.botConfirm(it,request,101,"FAKE",true) }
        val (pending,pv) = f.challenge(); val (cancelled,xv) = f.challenge()
        val disabled = Identity(f.db,f.clock)
        assertFault("UNAUTHENTICATED") { disabled.status(request,obj("codeVerifier" to str(secret()))) }
        assertFault("UNAUTHENTICATED") { disabled.exchange(request,obj("codeVerifier" to str(pv))) }
        assertEquals("confirmed",disabled.status(request,obj("codeVerifier" to str(cv))).text("status"))
        assertFault("LOGIN_NOT_CONFIRMED") { disabled.exchange(pending.text("requestId"),obj("codeVerifier" to str(pv))) }
        assertFault("EXPIRED") { disabled.exchange(expired.text("requestId"),obj("codeVerifier" to str(ev))) }
        disabled.cancel(cancelled.text("requestId"),obj("codeVerifier" to str(xv)))
        assertFault("LOGIN_NOT_CONFIRMED") { disabled.exchange(cancelled.text("requestId"),obj("codeVerifier" to str(xv))) }
        val response = disabled.exchange(request,obj("codeVerifier" to str(cv)))
        assertEquals(response.objectAt("account").text("accountId"),disabled.authenticate(response.text("accessToken")).accountId)
        assertFault("CONSUMED") { disabled.exchange(request,obj("codeVerifier" to str(cv))) }
    }
}
