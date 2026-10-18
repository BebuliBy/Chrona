package by.bebuliby.chrona.backend

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class HttpApiTest {
    @Test fun `unsafe temporal boundary fails closed without exposing outside timestamps or IDs`() = testApplication {
        val f = Fixture(); val a = f.login(); val b = f.login(202); f.accept(a.principal,b.principal)
        val raw = f.interval(a.principal); f.single(a.principal,f.op("activity_interval",raw))
        f.grant(a.principal,b.principal,listOf(f.auto(a.principal).id),from="2026-10-18T10:00:00.500Z")
        application { chrona(f.backend) }
        val response = client.get("/v1/owners/${a.principal.accountId}/activity-view?fromInclusive=2026-10-18&toExclusive=2026-10-19&zoneId=UTC") { bearerAuth(b.access) }
        assertEquals(HttpStatusCode.Conflict,response.status); assertEquals("POLICY_UNRESOLVED",parseObject(response.bodyAsText()).errorCode())
        for (privateField in listOf(raw.text("id"),raw.text("startUtc"),raw.text("endUtc"),a.principal.deviceId)) assertFalse(response.bodyAsText().contains(privateField))
    }
    @Test fun `HTTP nonempty friend snapshot is complete sanitized and partial calculations are fields`() = testApplication {
        val f = Fixture(); val owner = f.login(); val friend = f.login(202); f.accept(owner.principal,friend.principal)
        val auto = f.auto(owner.principal).id; val h = f.manual(owner.principal).first.text("id")
        f.push(owner.principal,f.op("goal_revision",f.goal(auto)),f.op("goal_revision",f.goal(h)),f.op("manual_entry",f.entry(h)),f.op("activity_interval",f.interval(owner.principal)))
        f.grant(owner.principal,friend.principal,listOf(h,auto))
        application { chrona(f.backend) }
        val path = "/v1/owners/${owner.principal.accountId}/activity-view?fromInclusive=2026-10-18&toExclusive=2026-10-19&zoneId=UTC"
        val response = client.get(path) { bearerAuth(friend.access) }
        assertEquals(HttpStatusCode.OK,response.status)
        val view = parseObject(response.bodyAsText())
        assertEquals(f.social.activityView(friend.principal,owner.principal.accountId,f.range()),view)
        assertEquals(2,view.arrayAt("cells").size)
        assertTrue(view.arrayAt("cells").all { !it.jsonObject.booleanAt("canEditActual") && !it.jsonObject.booleanAt("canEditGoal") })
        assertEquals("partial_coverage",view.objectAt("summary").objectAt("scorePercent").text("reason"))
        assertFalse(response.bodyAsText().contains(owner.principal.deviceId)); assertFalse(response.bodyAsText().contains(owner.access))
        f.db.tx { f.social.deleteFriend(it,owner.principal.accountId,friend.principal.accountId) }
        assertEquals(HttpStatusCode.NotFound,client.get(path) { bearerAuth(friend.access) }.status)
    }
    @Test fun `HTTP request body and malformed cursor limit fail explicitly`() = testApplication {
        val f = Fixture(); val login = f.login(); application { chrona(f.backend) }
        assertEquals(HttpStatusCode.BadRequest,client.get("/v1/sync/pull?limit=garbage") { bearerAuth(login.access) }.status)
        assertEquals(HttpStatusCode.Conflict,client.get("/v1/sync/pull?cursor=FAKE_UNKNOWN_CURSOR") { bearerAuth(login.access) }.status)
        val huge = client.post("/v1/sync/push") {
            bearerAuth(login.access); header("Idempotency-Key",id()); contentType(ContentType.Application.Json)
            setBody(" ".repeat(1_048_577))
        }
        assertEquals(HttpStatusCode.BadRequest,huge.status)
    }
    @Test fun `HTTP auth and owner scope reject body identity spoof and revoked tokens`() = testApplication {
        val f = Fixture(); val login = f.login(); val p = login.principal
        application { chrona(f.backend) }
        assertEquals(HttpStatusCode.Unauthorized,client.get("/v1/me").status)
        val me = client.get("/v1/me") { bearerAuth(login.access) }
        assertEquals(HttpStatusCode.OK,me.status); assertEquals(p.accountId,parseObject(me.bodyAsText()).text("accountId"))
        val response = client.post("/v1/sync/push") {
            bearerAuth(login.access); contentType(ContentType.Application.Json); header("Idempotency-Key",id())
            setBody(canonical(obj("deviceId" to str(p.deviceId),"operations" to arr(emptyList()),"ownerId" to str(id()))))
        }
        assertEquals(HttpStatusCode.BadRequest,response.status); assertEquals("VALIDATION",parseObject(response.bodyAsText()).errorCode())
        assertEquals(HttpStatusCode.OK,client.delete("/v1/sessions/current") { bearerAuth(login.access); header("Idempotency-Key",id()) }.status)
        assertEquals(HttpStatusCode.Unauthorized,client.get("/v1/sync/pull") { bearerAuth(login.access) }.status)
    }
    @Test fun `HTTP sync wire decimals idempotency and unsupported source preserve contract`() = testApplication {
        val f = Fixture(); val login = f.login(); val p = login.principal
        application { chrona(f.backend) }
        val batch = obj("deviceId" to str(p.deviceId),"operations" to arr(listOf(f.op("manual_entry",f.entry(f.auto(p).id)))))
        val response = client.post("/v1/sync/push") {
            bearerAuth(login.access); header("Idempotency-Key",id()); contentType(ContentType.Application.Json); setBody(canonical(batch))
        }
        assertEquals(HttpStatusCode.OK,response.status)
        val result = parseObject(response.bodyAsText()).arrayAt("results").single().jsonObject
        assertEquals("rejected",result.text("status")); assertEquals("SOURCE_NOT_WRITABLE",result.errorCode())
        val noKey = client.post("/v1/sync/push") { bearerAuth(login.access); contentType(ContentType.Application.Json); setBody(canonical(batch)) }
        assertEquals(HttpStatusCode.BadRequest,noKey.status)
        assertFalse(response.bodyAsText().contains(login.access))
    }
    @Test fun `HTTP challenge no redirect caller callback no fake exchange and error envelope`() = testApplication {
        val f = Fixture(); application { chrona(f.backend) }
        val verifier = secret()
        val body = obj("deviceId" to str(id()),"deviceLabel" to str("FAKE HTTP"),"codeChallenge" to str(hash(verifier)),"codeChallengeMethod" to str("S256"))
        val created = client.post("/v1/auth/telegram/challenges") { contentType(ContentType.Application.Json); setBody(canonical(body)) }
        assertEquals(HttpStatusCode.Created,created.status)
        val q = parseObject(created.bodyAsText())
        assertTrue(q.text("telegramStartUrl").startsWith("https://t.me/ChronaFakeTestBot?start="))
        assertFalse(q.toString().contains(verifier))
        val exchange = client.post("/v1/auth/telegram/challenges/${q.text("requestId")}/exchange") { contentType(ContentType.Application.Json); setBody(canonical(obj("codeVerifier" to str(verifier)))) }
        assertEquals(HttpStatusCode.Conflict,exchange.status)
        assertEquals(setOf("code","message","requestId"),parseObject(exchange.bodyAsText()).objectAt("error").keys)
        val injected = client.post("/v1/auth/telegram/challenges") { contentType(ContentType.Application.Json); setBody(canonical(JsonObject(body + ("returnUrl" to str("https://evil.invalid"))))) }
        assertEquals(HttpStatusCode.BadRequest,injected.status)
    }
    @Test fun `HTTP policy unresolved forbidden friend and no features fake 200`() = testApplication {
        val f = Fixture(); val a = f.login(); val b = f.login(202)
        application { chrona(f.backend) }
        val suffix = "?fromInclusive=2026-10-18&toExclusive=2026-10-19&zoneId=UTC"
        val hidden = client.get("/v1/owners/${a.principal.accountId}/activity-view$suffix") { bearerAuth(b.access) }
        assertEquals(HttpStatusCode.NotFound,hidden.status)
        val unresolved = client.get("/v1/owners/${a.principal.accountId}/activity-view$suffix") { bearerAuth(a.access) }
        assertEquals(HttpStatusCode.OK,unresolved.status)
        assertEquals("schedule_unresolved",parseObject(unresolved.bodyAsText()).objectAt("summary").objectAt("scorePercent").text("reason"))
        assertEquals(HttpStatusCode.Conflict,client.put("/v1/sharing/defaults") { bearerAuth(a.access); header("Idempotency-Key",id()); contentType(ContentType.Application.Json); setBody("{}") }.status)
        assertEquals(HttpStatusCode.NotImplemented,client.post("/v1/friend-invites") { bearerAuth(a.access); header("Idempotency-Key",id()) }.status)
        assertEquals(HttpStatusCode.OK,client.get("/health/ready").status)
    }
    @Test fun `bot mode exposes health but not HTTP product routes`() = testApplication {
        val f = Fixture(); application { chrona(f.backend,"bot",null) }
        assertEquals(HttpStatusCode.NotFound,client.get("/v1/me").status)
        assertEquals(HttpStatusCode.OK,client.get("/health/live").status)
        assertEquals(HttpStatusCode.ServiceUnavailable,client.get("/health/ready").status)
    }
}
