package by.bebuliby.chrona.backend

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class FakeTelegram : TelegramTransport {
    val calls = mutableListOf<Pair<String,JsonObject>>()
    var updates: JsonArray = arr(emptyList())
    var pollFailure: TelegramFailure? = null
    var sendFailure: TelegramFailure? = null
    override fun call(method: String, body: JsonObject): JsonElement {
        calls += method to body
        if (method == "getUpdates") { pollFailure?.let { throw it }; return updates.also { updates = arr(emptyList()) } }
        sendFailure?.let { throw it }
        return obj("message_id" to num(100))
    }
}
fun message(update: Long, sender: Long, text: String, type: String = "private") = obj("update_id" to num(update),
    "message" to obj("from" to obj("id" to num(sender),"is_bot" to bool(false),"first_name" to str("FAKE"),"username" to str("not_identity")),
        "chat" to obj("id" to num(sender),"type" to str(type)),"text" to str(text)))
fun callback(update: Long, sender: Long, request: String, accept: Boolean = true) = obj("update_id" to num(update),
    "callback_query" to obj("id" to str("fake-callback-$update"),"from" to obj("id" to num(sender),"is_bot" to bool(false),"first_name" to str("FAKE")),
        "message" to obj("chat" to obj("id" to num(sender),"type" to str("private"))),"data" to str("login:${if (accept) "yes" else "no"}:$request")))

class BotWorkerTest {
    @Test fun `bot single part activity reply is the same nonempty projection as HTTP`() {
        val f = Fixture(); val a = f.login().principal; val b = f.login(202).principal; f.accept(a,b)
        f.single(a,f.op("activity_interval",f.interval(a))); f.grant(a,b,listOf(f.auto(a).id))
        val expected = f.social.activityView(b,a.accountId,f.range())
        val tg = FakeTelegram(); val worker = BotWorker(f.identity,f.social,tg) { 0 }; worker.acquire()
        tg.updates = arr(listOf(message(1,202,"/view ${a.accountId} 2026-10-18 2026-10-19 UTC")))
        worker.pollOnce(); worker.processInbox(); assertTrue(worker.deliverOne())
        val text = tg.calls.last().second.text("text")
        assertEquals(expected,parseObject(text)); assertFalse(text.contains(a.deviceId))
        assertTrue(parseObject(text).arrayAt("cells").all { !it.jsonObject.booleanAt("canEditActual") })
    }

    @Test fun `long activity replies have ordered durable fragments with no stored data snapshots`() {
        val f = Fixture(); val a = f.login().principal; val b = f.login(202).principal; f.accept(a,b)
        val h = f.manual(a).first.text("id"); f.grant(a,b,listOf(h))
        val range = ViewRange.parse("2026-10-18","2026-10-23","UTC")
        val expected = canonical(f.social.activityView(b,a.accountId,range))
        assertTrue(expected.length > 3500)
        val tg = FakeTelegram(); val one = BotWorker(f.identity,f.social,tg) { 0 }; one.acquire()
        tg.updates = arr(listOf(message(1,202,"/view ${a.accountId} 2026-10-18 2026-10-23 UTC")))
        one.pollOnce(); one.processInbox()
        tg.sendFailure = TelegramFailure("NETWORK"); assertTrue(one.deliverOne())
        // No second fragment is queued before the first fragment's external send + SQL ack.
        assertEquals(1,f.db.tx { it.query("SELECT id FROM bot_outbox WHERE kind='view'") { true }.size })
        f.clock.advance(1000); tg.sendFailure = null; one.deliverOne(); one.release()
        val restarted = BotWorker(f.identity,f.social,tg) { 0 }; assertTrue(restarted.acquire())
        repeat(20) { restarted.deliverOne() }
        val sent = tg.calls.filter { it.first == "sendMessage" }.drop(1).map { it.second.text("text") }
        assertTrue(sent.size > 1); assertTrue(sent.all { it.length <= 3800 && it.startsWith("Chrona activity-view [") })
        assertEquals(expected,sent.joinToString("") { it.substringAfter('\n') })
        val payloads = f.db.tx { it.query("SELECT payload FROM bot_outbox") { r -> r.getString(1) }.joinToString() }
        assertFalse(payloads.contains("cells")); assertFalse(payloads.contains("FAKE reading")); assertTrue(payloads.contains("snapshotHash"))
    }

    @Test fun `revocation before next fragment prevents remaining activity data delivery`() {
        val f = Fixture(); val a = f.login().principal; val b = f.login(202).principal; f.accept(a,b)
        val h = f.manual(a).first.text("id"); f.grant(a,b,listOf(h))
        val tg = FakeTelegram(); val worker = BotWorker(f.identity,f.social,tg) { 0 }; worker.acquire()
        tg.updates = arr(listOf(message(1,202,"/view ${a.accountId} 2026-10-18 2026-10-23 UTC")))
        worker.pollOnce(); worker.processInbox(); worker.deliverOne()
        assertTrue(tg.calls.last().second.text("text").startsWith("Chrona activity-view [1/"))
        f.db.tx { f.social.deleteFriend(it,a.accountId,b.accountId) }
        val priorSends = tg.calls.count { it.first == "sendMessage" }
        worker.deliverOne()
        assertEquals(priorSends,tg.calls.count { it.first == "sendMessage" })
        worker.deliverOne()
        assertTrue(tg.calls.last().second.text("text").startsWith("Представление недоступно:"))
        assertFalse(tg.calls.last().second.text("text").contains(h))
        assertEquals(1,f.db.tx { it.query("SELECT id FROM bot_outbox WHERE kind='view' AND discarded=TRUE") { true }.size })
    }

    @Test fun `changed activity snapshot stops multipart reply rather than mixing revisions`() {
        val f = Fixture(); val a = f.login().principal; val b = f.login(202).principal; f.accept(a,b)
        val h = f.manual(a).first.text("id"); f.grant(a,b,listOf(h))
        val tg = FakeTelegram(); val worker = BotWorker(f.identity,f.social,tg) { 0 }; worker.acquire()
        tg.updates = arr(listOf(message(1,202,"/view ${a.accountId} 2026-10-18 2026-10-23 UTC")))
        worker.pollOnce(); worker.processInbox(); worker.deliverOne()
        f.single(a,f.op("manual_entry",f.entry(h,value=str("987654321"))))
        val priorSends = tg.calls.count { it.first == "sendMessage" }; worker.deliverOne()
        assertEquals(priorSends,tg.calls.count { it.first == "sendMessage" })
        worker.deliverOne()
        assertTrue(tg.calls.last().second.text("text").contains("REVISION_CONFLICT"))
        assertFalse(tg.calls.last().second.text("text").contains("987654321"))
    }
    @Test fun `outbox retry rerenders current friend list and failed delivery is not ready`() {
        val f = Fixture(); val a = f.login(101).principal; val b = f.login(202).principal; f.accept(a,b)
        val tg = FakeTelegram(); val worker = BotWorker(f.identity,f.social,tg) { 0 }; worker.acquire()
        tg.updates = arr(listOf(message(1,101,"/friends")))
        worker.pollOnce(); worker.processInbox(); assertTrue(worker.ready())
        tg.sendFailure = TelegramFailure("NETWORK"); worker.deliverOne()
        assertTrue(tg.calls.last().second.toString().contains(b.accountId)); assertFalse(worker.ready())
        f.db.tx { f.social.deleteFriend(it,a.accountId,b.accountId) }
        f.clock.advance(1000); tg.sendFailure = null; assertTrue(worker.deliverOne())
        assertFalse(tg.calls.last().second.toString().contains(b.accountId)); assertTrue(worker.ready())
        worker.release(); assertFalse(worker.ready())
    }
    @Test fun `fake Telegram protocol confirms explicitly persists inbox offset and deduplicates replay`() {
        val f = Fixture(); val tg = FakeTelegram(); val worker = BotWorker(f.identity,f.social,tg) { 0 }
        val (q,v) = f.challenge(); val request = q.text("requestId")
        assertTrue(worker.acquire()); assertFalse(worker.ready())
        tg.updates = arr(listOf(message(10,101,"/start $request")))
        assertEquals(0L,worker.pollOnce()); assertEquals(1,worker.processInbox())
        assertEquals("pending",f.identity.status(request,obj("codeVerifier" to str(v))).text("status"))
        assertTrue(worker.deliverOne())
        val prompt = tg.calls.last { it.first == "sendMessage" }.second
        assertTrue(prompt.objectAt("reply_markup").arrayAt("inline_keyboard").toString().contains("login:yes:$request"))
        assertFalse(prompt.toString().contains(v))
        tg.updates = arr(listOf(callback(11,101,request),callback(11,101,request)))
        worker.pollOnce(); assertEquals(1,worker.processInbox()); worker.deliverOne()
        assertEquals("confirmed",f.identity.status(request,obj("codeVerifier" to str(v))).text("status"))
        tg.updates = arr(listOf(callback(11,101,request))); worker.pollOnce(); assertEquals(0,worker.processInbox())
        val response = f.identity.exchange(request,obj("codeVerifier" to str(v)))
        assertEquals(1,f.db.tx { it.query("SELECT id FROM accounts") { true }.size })
        assertFalse(tg.calls.toString().contains(response.text("accessToken")))
        val polls = tg.calls.filter { it.first == "getUpdates" }.map { it.second }
        assertTrue(polls.all { it.longAt("timeout") == 5L }); assertEquals(12L,polls.last().longAt("offset"))
        assertTrue(worker.ready())
    }
    @Test fun `wrong callback sender cannot swap binding and unknown messages are not stored`() {
        val f = Fixture(); val tg = FakeTelegram(); val worker = BotWorker(f.identity,f.social,tg) { 0 }; worker.acquire()
        val (q,v) = f.challenge(); val request = q.text("requestId")
        tg.updates = arr(listOf(message(1,101,"/start $request"),callback(2,202,request),message(3,101,"PRIVATE_ARBITRARY_CONTENT"),message(4,101,"/start $request","group")))
        worker.pollOnce(); worker.processInbox()
        assertEquals("pending",f.identity.status(request,obj("codeVerifier" to str(v))).text("status"))
        val inbox = f.db.tx { it.query("SELECT payload FROM bot_inbox") { r -> r.getString(1) }.joinToString() }
        assertFalse(inbox.contains("PRIVATE_ARBITRARY_CONTENT")); assertFalse(inbox.contains("not_identity")); assertFalse(inbox.contains("group"))
        assertEquals(0,f.db.tx { it.query("SELECT id FROM accounts") { true }.size })
    }
    @Test fun `SQL lease permits one poller and expired holder cannot commit or poll`() {
        val f = Fixture(); val a = FakeTelegram(); val b = FakeTelegram()
        val one = BotWorker(f.identity,f.social,a) { 0 }; val two = BotWorker(f.identity,f.social,b) { 0 }
        assertTrue(one.acquire()); assertFalse(two.acquire())
        assertFault("LEASE_LOST") { two.pollOnce() }; assertTrue(b.calls.isEmpty())
        f.clock.advance(45_000); assertTrue(two.acquire())
        assertFault("LEASE_LOST") { one.pollOnce() }; assertTrue(a.calls.isEmpty())
        two.pollOnce(); assertEquals(1,b.calls.size)
    }
    @Test fun `network backoff jitter budget 429 retry_after and readiness are bounded`() {
        val f = Fixture(); val tg = FakeTelegram(); val worker = BotWorker(f.identity,f.social,tg) { 7 }; worker.acquire()
        tg.pollFailure = TelegramFailure("NETWORK")
        assertEquals(507,worker.pollOnce()); assertEquals(1007,worker.pollOnce())
        tg.pollFailure = TelegramFailure("RATE_LIMITED",9)
        assertEquals(9007,worker.pollOnce()); assertFalse(worker.ready())
        tg.pollFailure = null; worker.pollOnce(); assertTrue(worker.ready())
        f.clock.advance(45_000); assertFalse(worker.ready())
    }
    @Test fun `SQL restart preserves outbox retry offset dedup and inbox actions`(@TempDir dir: Path) {
        val url = "jdbc:h2:file:${dir.resolve("bot-state")};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
        val f = Fixture(url); val tg = FakeTelegram(); val one = BotWorker(f.identity,f.social,tg) { 0 }; one.acquire()
        val (q,v) = f.challenge(); val request = q.text("requestId")
        tg.updates = arr(listOf(message(8,101,"/start $request")))
        one.pollOnce(); one.processInbox()
        tg.sendFailure = TelegramFailure("RATE_LIMITED",3); assertTrue(one.deliverOne())
        assertFalse(one.deliverOne()); one.release()
        val restartedDb = Database(url); restartedDb.migrate()
        val identity = Identity(restartedDb,f.clock,"ChronaFakeTestBot"); val b = Backend(identity); val next = FakeTelegram()
        val two = BotWorker(identity,b.social,next) { 0 }; assertTrue(two.acquire())
        assertEquals(0,two.processInbox()); assertFalse(two.deliverOne())
        f.clock.advance(3000); assertTrue(two.deliverOne()); assertFalse(two.deliverOne())
        next.updates = arr(listOf(callback(9,101,request)))
        two.pollOnce(); assertEquals(9,next.calls.last().second.longAt("offset")); two.processInbox()
        assertEquals("confirmed",identity.status(request,obj("codeVerifier" to str(v))).text("status"))
        assertEquals(1,restartedDb.tx { it.query("SELECT id FROM bot_outbox WHERE delivered=TRUE") { true }.size })
    }
    @Test fun `bot friend operations use same use cases as API and queued views recheck revocation`() {
        val f = Fixture(); val a = f.login(101).principal; val b = f.login(202).principal
        f.db.tx { it.exec("UPDATE accounts SET discoverable=TRUE WHERE id=?",b.accountId) }
        val tg = FakeTelegram(); val worker = BotWorker(f.identity,f.social,tg) { 0 }; worker.acquire()
        tg.updates = arr(listOf(message(1,101,"/request ${b.accountId}")))
        worker.pollOnce(); worker.processInbox()
        val request = f.social.requests(b).arrayAt("items").single().jsonObject.text("requestId")
        tg.updates = arr(listOf(message(2,202,"/accept $request")))
        worker.pollOnce(); worker.processInbox()
        assertEquals(1,f.social.friends(a).arrayAt("items").size)
        f.grant(a,b,listOf(f.auto(a).id))
        tg.updates = arr(listOf(message(3,202,"/view ${a.accountId} 2026-10-18 2026-10-19 UTC")))
        worker.pollOnce(); worker.processInbox()
        f.db.tx { f.social.deleteFriend(it,a.accountId,b.accountId) }
        repeat(5) { worker.deliverOne() }
        assertEquals(1,f.db.tx { it.query("SELECT id FROM bot_outbox WHERE kind='view' AND discarded=TRUE") { true }.size })
        assertTrue(tg.calls.filter { it.first == "sendMessage" }.all { !it.second.toString().contains(f.auto(a).id) })
    }
}
