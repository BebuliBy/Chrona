package by.bebuliby.chrona.backend

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class SocialActivationTest {
    @Test fun `own me flag defaults private explicit on off search and public DTO stay separate`() = testApplication {
        val f = Fixture(); val a = f.login(); val b = f.login(202)
        application { chrona(f.backend) }
        assertFalse(parseObject(client.get("/v1/me") { bearerAuth(a.access) }.bodyAsText()).booleanAt("discoverable"))
        assertTrue(f.social.search(b.principal,"FAKE",null).arrayAt("items").isEmpty())
        val on = client.put("/v1/me/discoverability") {
            bearerAuth(a.access); header("Idempotency-Key",id()); contentType(ContentType.Application.Json); setBody("{\"enabled\":true}")
        }
        assertEquals(HttpStatusCode.OK,on.status); assertTrue(parseObject(on.bodyAsText()).booleanAt("discoverable"))
        val visible = f.social.search(b.principal,"FAKE",null).arrayAt("items").single().jsonObject
        assertEquals(a.principal.accountId,visible.text("accountId"))
        assertEquals(setOf("accountId","displayName"),visible.keys)
        assertFalse(f.identity.me(b.principal).booleanAt("discoverable"))
        assertEquals(0,f.db.tx { it.query("SELECT owner_id FROM grants") { true }.size })
        f.identity.configureDiscoverability(a.principal,id(),obj("enabled" to bool(false)))
        assertTrue(f.social.search(b.principal,"FAKE",null).arrayAt("items").isEmpty())
        assertFalse(f.social.activityView(a.principal,a.principal.accountId,f.range()).objectAt("owner").containsKey("discoverable"))
    }

    @Test fun `setter requires session key strict boolean and cannot mutate body owner`() = testApplication {
        val f = Fixture(); val a = f.login(); val b = f.login(202); application { chrona(f.backend) }
        assertEquals(HttpStatusCode.Unauthorized,client.put("/v1/me/discoverability") {
            contentType(ContentType.Application.Json); header("Idempotency-Key",id()); setBody("{\"enabled\":true}")
        }.status)
        assertEquals(HttpStatusCode.BadRequest,client.put("/v1/me/discoverability") {
            bearerAuth(a.access); contentType(ContentType.Application.Json); setBody("{\"enabled\":true}")
        }.status)
        for (body in listOf("{}","{\"enabled\":\"true\"}","{\"enabled\":null}","{\"enabled\":true,\"ownerId\":\"${b.principal.accountId}\"}")) {
            assertEquals(HttpStatusCode.BadRequest,client.put("/v1/me/discoverability") {
                bearerAuth(a.access); contentType(ContentType.Application.Json); header("Idempotency-Key",id()); setBody(body)
            }.status)
        }
        assertFalse(f.identity.me(a.principal).booleanAt("discoverable")); assertFalse(f.identity.me(b.principal).booleanAt("discoverable"))
        f.identity.revoke(a.principal)
        assertEquals(HttpStatusCode.Unauthorized,client.put("/v1/me/discoverability") {
            bearerAuth(a.access); contentType(ContentType.Application.Json); header("Idempotency-Key",id()); setBody("{\"enabled\":true}")
        }.status)
    }

    @Test fun `discoverability is transactional account scoped idempotency without grants fanout`() {
        val f = Fixture(); val a = f.login().principal; val b = f.login(202).principal; val key = id()
        val on = obj("enabled" to bool(true)); val first = f.identity.configureDiscoverability(a,key,on)
        assertEquals(first,f.identity.configureDiscoverability(a,key,on))
        assertFault("IDEMPOTENCY_CONFLICT") { f.identity.configureDiscoverability(a,key,obj("enabled" to bool(false))) }
        f.identity.configureDiscoverability(b,key,obj("enabled" to bool(false)))
        assertTrue(f.identity.me(a).booleanAt("discoverable")); assertFalse(f.identity.me(b).booleanAt("discoverable"))
        assertEquals(2,f.db.tx { it.query("SELECT key_id FROM request_dedup") { true }.size })
        assertEquals(0,f.db.tx { it.query("SELECT account_a FROM friendships") { true }.size })
    }

    @Test fun `confirmed current username is searchable attribute not stable identity or auto consent`() {
        val f = Fixture(); val a = f.login().principal; val b = f.login(202).principal
        f.identity.configureDiscoverability(a,id(),obj("enabled" to bool(true)))
        val (challenge,verifier) = f.challenge(); val request = challenge.text("requestId")
        f.db.tx { f.identity.botStart(it,request,101) }
        assertFalse(f.identity.me(a).text("displayName").contains("@current_nick"))
        f.db.tx { f.identity.botConfirm(it,request,101,"FAKE\nname",true,"Current_Nick") }
        val response = f.identity.exchange(request,obj("codeVerifier" to str(verifier)))
        assertEquals(a.accountId,response.objectAt("account").text("accountId"))
        assertTrue(f.identity.me(a).booleanAt("discoverable"))
        assertTrue(f.identity.me(a).text("displayName").contains("@current_nick")); assertFalse(f.identity.me(a).text("displayName").contains('\n'))
        assertEquals(a.accountId,f.social.search(b,"@CURRENT_NICK",null).arrayAt("items").single().jsonObject.text("accountId"))
        assertFalse(f.identity.confirmedName("FAKE","https://evil.invalid").contains("evil"))
    }

    @Test fun `bot discover uses same owner usecase durable dedup and invalid or unlinked command does not opt in`() {
        val f = Fixture(); val a = f.login().principal; val b = f.login(202).principal
        val tg = FakeTelegram(); val worker = BotWorker(f.identity,f.social,tg) { 0 }; worker.acquire()
        tg.updates = arr(listOf(message(1,101,"/discover on"),message(2,303,"/discover on"),message(3,202,"/discover all")))
        worker.pollOnce(); worker.processInbox()
        assertTrue(f.identity.me(a).booleanAt("discoverable")); assertFalse(f.identity.me(b).booleanAt("discoverable"))
        tg.updates = arr(listOf(message(4,101,"/discover off"))); worker.pollOnce(); worker.processInbox()
        assertFalse(f.identity.me(a).booleanAt("discoverable"))
        tg.updates = arr(listOf(message(1,101,"/discover on"))); worker.pollOnce(); worker.processInbox()
        assertFalse(f.identity.me(a).booleanAt("discoverable"))
        assertEquals("noop",normalizeUpdate(message(9,101,"/discover on","group")).text("command"))
    }

    @Test fun `turning discoverability off never removes existing explicit friendship or grants`() {
        val f = Fixture(); val a = f.login().principal; val b = f.login(202).principal
        f.identity.configureDiscoverability(b,id(),obj("enabled" to bool(true)))
        val request = f.db.tx { f.social.request(it,a.accountId,b.accountId) }
        f.db.tx { f.social.respond(it,b.accountId,request.text("requestId"),true) }
        f.grant(a,b,listOf(f.auto(a).id))
        f.identity.configureDiscoverability(b,id(),obj("enabled" to bool(false)))
        assertEquals(1,f.social.friends(a).arrayAt("items").size)
        assertEquals(1,f.social.activityView(b,a.accountId,f.range()).arrayAt("habits").size)
        repeat(20) { f.social.search(a,"FAKE",null) }
        assertFault("RATE_LIMITED") { f.social.search(a,"FAKE",null) }
    }
}
