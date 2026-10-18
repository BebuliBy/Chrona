package by.bebuliby.chrona.backend

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Fake protocol only: no Telegram requests, real credentials or synthetic live messages. */
class BotStartHelpTest {
    private fun authSnapshot(f: Fixture) = f.db.tx { c ->
        c.query("SELECT id,state,telegram_id,account_id,chat_id FROM login_challenges ORDER BY id") {
            (1..5).map { column -> it.getString(column) }
        } to listOf("accounts","device_sessions","entities").map { table -> c.query("SELECT COUNT(*) FROM $table") { it.getLong(1) }.single() }
    }
    private fun worker(f: Fixture, tg: FakeTelegram) = BotWorker(f.identity,f.social,tg) { 0 }.also { assertTrue(it.acquire()) }
    private fun drain(worker: BotWorker) { repeat(20) { worker.deliverOne() } }
    private fun replies(tg: FakeTelegram) = tg.calls.filter { it.first == "sendMessage" }.map { it.second }
    private fun assertGeneric(reply: JsonObject, chat: Long) {
        assertEquals(chat,reply.longAt("chat_id"))
        assertEquals("Ссылка недействительна или устарела. Начните новый вход в Chrona.",reply.text("text"))
        assertFalse(reply.containsKey("reply_markup"))
    }

    @Test fun `bare start and help reply through outbox without identity or challenge creation`() {
        val f = Fixture(); val tg = FakeTelegram(); val w = worker(f,tg); val before = authSnapshot(f)
        assertFalse(w.deliverOne())
        tg.updates = arr(listOf(message(1,101,"/start"),message(2,101,"/help"),message(3,101,"/start@ChronaFakeTestBot")))
        w.pollOnce(); assertEquals(3,w.processInbox()); drain(w)
        assertEquals(before,authSnapshot(f)); assertEquals(3,replies(tg).size)
        for (reply in replies(tg)) {
            assertEquals(101L,reply.longAt("chat_id")); assertTrue(reply.text("text").contains("Chrona → Настройки → Войти через Telegram"))
            assertTrue(reply.text("text").contains("новую ссылку")); assertFalse(reply.text("text").contains("https://"))
        }
        val normalized = normalizeUpdate(message(4,101,"/help"))
        assertEquals(setOf("updateId","senderId","chatId","command","args"),normalized.keys)
        assertEquals("help",normalized.text("command")); assertTrue(normalized.arrayAt("args").isEmpty())
        assertEquals("noop",normalizeUpdate(message(5,101,"/help","group")).text("command"))
    }

    @Test fun `bare start never binds to even the latest expired unbound request`() {
        val f = Fixture(); val (q,v) = f.challenge(); f.clock.advance(8*60_000L)
        val tg = FakeTelegram(); val w = worker(f,tg); val before = authSnapshot(f)
        tg.updates = arr(listOf(message(1,101,"/start"))); w.pollOnce(); w.processInbox(); drain(w)
        assertEquals(before,authSnapshot(f)); assertEquals(1,replies(tg).size)
        assertEquals("expired",f.identity.status(q.text("requestId"),obj("codeVerifier" to str(v))).text("status"))
        assertNull(f.db.tx { it.query("SELECT telegram_id FROM login_challenges") { r -> r.getObject(1) }.single() })
    }

    @Test fun `fresh UUID start keeps separate confirm deny keyboard and never logs in automatically`() {
        val f = Fixture(); val (q,v) = f.challenge(); val request = q.text("requestId")
        val tg = FakeTelegram(); val w = worker(f,tg)
        tg.updates = arr(listOf(message(1,101,"/start $request"))); w.pollOnce(); w.processInbox(); drain(w)
        val prompt = replies(tg).single()
        assertTrue(prompt.objectAt("reply_markup").toString().contains("login:yes:$request"))
        assertTrue(prompt.objectAt("reply_markup").toString().contains("login:no:$request"))
        assertEquals("pending",f.identity.status(request,obj("codeVerifier" to str(v))).text("status"))
        assertEquals(listOf(0L,0L,0L),authSnapshot(f).second)
        assertFault("LOGIN_NOT_CONFIRMED") { f.identity.exchange(request,obj("codeVerifier" to str(v))) }
        assertFalse(prompt.toString().contains(v))
    }

    @Test fun `invalid missing UUID expired consumed and other bound start receive indistinguishable safe errors`() {
        val f = Fixture(); val expired = f.challenge().first.text("requestId"); f.clock.advance(300_000)
        f.login(404)
        val consumed = f.db.tx { it.query("SELECT id FROM login_challenges WHERE state='consumed'") { r -> r.getString(1) }.single() }
        val bound = f.challenge().first.text("requestId"); f.db.tx { f.identity.botStart(it,bound,101) }
        val tg = FakeTelegram(); val w = worker(f,tg); val before = authSnapshot(f)
        val starts = listOf("/start DO_NOT_STORE_PRIVATE_TEXT","/start ${id()}","/start $expired","/start $consumed","/start $bound","/start $bound extra")
        tg.updates = arr(starts.mapIndexed { index,text -> message(index+1L,202,text) }); w.pollOnce(); w.processInbox(); drain(w)
        assertEquals(before,authSnapshot(f)); assertEquals(starts.size,replies(tg).size)
        replies(tg).forEach { assertGeneric(it,202) }
        val stored = f.db.tx { it.query("SELECT payload FROM bot_inbox") { r -> r.getString(1) }.joinToString() }
        assertFalse(stored.contains("DO_NOT_STORE_PRIVATE_TEXT")); assertFalse(stored.contains("extra"))
    }

    @Test fun `expired wrong sender replay and malformed callbacks preserve auth and answer only sender chat`() {
        for (scenario in listOf("expired","wrong_sender","replay","malformed")) {
            val f = Fixture(); val (q,v) = f.challenge(); val request = q.text("requestId")
            f.db.tx { f.identity.botStart(it,request,101) }
            if (scenario == "expired") f.clock.advance(300_000)
            if (scenario == "replay") {
                f.db.tx { f.identity.botConfirm(it,request,101,"FAKE",true) }
                f.identity.exchange(request,obj("codeVerifier" to str(v)))
            }
            val sender = if (scenario == "wrong_sender") 202L else 101L
            val update = callback(1,sender,if (scenario == "malformed") "PRIVATE_INVALID_PAYLOAD" else request)
            val tg = FakeTelegram(); val w = worker(f,tg); val before = authSnapshot(f)
            tg.updates = arr(listOf(update)); w.pollOnce(); w.processInbox(); drain(w)
            assertEquals(before,authSnapshot(f),scenario); assertGeneric(replies(tg).single(),sender)
            assertEquals(1,tg.calls.count { it.first == "answerCallbackQuery" })
            assertFalse(replies(tg).toString().contains(request))
        }
    }

    @Test fun `prompt expiring before delivery yields generic response not silence or stale device disclosure`() {
        val f = Fixture(); val q = f.challenge().first; val tg = FakeTelegram(); val w = worker(f,tg)
        tg.updates = arr(listOf(message(1,101,"/start ${q.text("requestId")}"))); w.pollOnce(); w.processInbox()
        val before = authSnapshot(f); f.clock.advance(300_000); assertTrue(w.acquire())
        drain(w); assertEquals(before,authSnapshot(f)); assertGeneric(replies(tg).single(),101)
    }

    @Test fun `processed old noop is never replayed offset only advances and unknown text stays private`() {
        val f = Fixture()
        f.db.tx {
            it.exec("INSERT INTO bot_inbox VALUES (10,?,TRUE)",canonical(obj("updateId" to num(10),"command" to str("noop"))))
            it.exec("UPDATE bot_state SET next_offset=11 WHERE id=1")
        }
        val tg = FakeTelegram(); val w = worker(f,tg)
        tg.updates = arr(listOf(message(10,101,"/start"),message(11,101,"/help"),message(12,101,"PRIVATE_UNKNOWN_MESSAGE")))
        w.pollOnce(); assertEquals(2,w.processInbox()); drain(w); assertEquals(1,replies(tg).size)
        assertEquals(13L,f.db.tx { it.query("SELECT next_offset FROM bot_state") { r -> r.getLong(1) }.single() })
        val stored = f.db.tx { it.query("SELECT payload FROM bot_inbox") { r -> r.getString(1) }.joinToString() }
        assertFalse(stored.contains("PRIVATE_UNKNOWN_MESSAGE")); assertFalse(stored.contains("not_identity"))
        assertEquals("noop",f.db.tx { it.query("SELECT payload FROM bot_inbox WHERE update_id=10") { r -> parseObject(r.getString(1)) }.single() }.text("command"))
        w.release(); val restarted = worker(f,FakeTelegram()); assertEquals(0,restarted.processInbox()); assertFalse(restarted.deliverOne())
    }
}
