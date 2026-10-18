package by.bebuliby.chrona.backend

import kotlinx.serialization.json.*
import java.sql.Connection
import kotlin.math.min
import kotlin.random.Random

data class BotJob(val id: String, val chat: Long, val kind: String, val payload: JsonObject, val attempts: Int)
private data class RenderedDelivery(val method: String, val body: JsonObject,
                                    val nextViewId: String? = null, val nextViewPayload: JsonObject? = null)
internal const val BOT_LOGIN_LINK_UNAVAILABLE = "Ссылка недействительна или устарела. Начните новый вход в Chrona."
internal const val BOT_LOGIN_HELP = "Для входа откройте Chrona → Настройки → Войти через Telegram. Используйте новую ссылку из приложения, подтвердите запрос кнопкой в боте и вернитесь в Chrona. Обычный запуск бота не выполняет вход."

class BotWorker(private val identity: Identity, private val social: Social, private val transport: TelegramTransport,
                private val jitter: (Long) -> Long = { Random.nextLong(it / 4 + 1) }) {
    private val db get() = identity.db
    val holder = id()
    private val leaseMs = 45_000L
    private var pollFailures = 0
    @Volatile var lastProgress = identity.now(); private set
    @Volatile var lastProgressNanos = System.nanoTime(); private set
    @Volatile var lastSuccess = 0L; private set

    fun acquire(): Boolean = db.tx { c ->
        val lease = c.query("SELECT lease_holder,lease_until_ms FROM bot_state WHERE id=1") { it.getString(1) to it.getLong(2) }.single()
        if (lease.first != holder && lease.second > identity.now()) false else {
            c.exec("UPDATE bot_state SET lease_holder=?,lease_until_ms=? WHERE id=1", holder,identity.now() + leaseMs)
            true
        }
    }
    private fun lease(c: Connection) {
        val state = c.query("SELECT lease_holder,lease_until_ms FROM bot_state WHERE id=1") { it.getString(1) to it.getLong(2) }.single()
        requireWire(state.first == holder && state.second > identity.now(), "LEASE_LOST", 503)
        c.exec("UPDATE bot_state SET lease_until_ms=?,progress_ms=? WHERE id=1", identity.now() + leaseMs, identity.now())
        lastProgress = identity.now()
        lastProgressNanos = System.nanoTime()
    }
    fun release() = db.tx { c -> c.exec("UPDATE bot_state SET lease_holder=NULL,lease_until_ms=0 WHERE id=1 AND lease_holder=?", holder) }
    fun ready(): Boolean = try {
        val now = identity.now()
        now >= lastProgress && now - lastProgress < 45_000 && lastSuccess > 0 && now >= lastSuccess && now - lastSuccess < 45_000 && db.tx { c ->
            c.query("SELECT lease_holder,lease_until_ms FROM bot_state WHERE id=1") { it.getString(1) == holder && it.getLong(2) > now }.single() &&
                c.query("SELECT id FROM bot_outbox WHERE delivered=FALSE AND discarded=FALSE AND attempts>0 LIMIT 1") { true }.isEmpty()
        }
    } catch (_: Exception) { false }
    fun progress() = db.tx { c -> lease(c) }
    fun stalled(nowNanos: Long = System.nanoTime()) = nowNanos - lastProgressNanos > 60_000_000_000L

    // Returns bounded sleep on network loss/429; caller remains watchdog-observable while sleeping.
    fun pollOnce(): Long {
        val offset = db.tx { c -> lease(c); c.query("SELECT next_offset FROM bot_state WHERE id=1") { it.getLong(1) }.single() }
        return try {
            val updates = transport.call("getUpdates", obj("offset" to num(offset), "timeout" to num(5), "limit" to num(50),
                "allowed_updates" to arr(listOf(str("message"),str("callback_query"))))) as? JsonArray ?: throw TelegramFailure("PROTOCOL")
            requireWire(updates.size <= 50)
            val normalized = updates.map { normalizeUpdate(it.jsonObject) }.sortedBy { it.longAt("updateId") }
            db.tx { c ->
                lease(c)
                var next = offset
                for (update in normalized) {
                    val updateId = update.longAt("updateId")
                    if (updateId < offset) continue
                    if (c.query("SELECT update_id FROM bot_inbox WHERE update_id=?", updateId) { true }.isEmpty())
                        c.exec("INSERT INTO bot_inbox VALUES (?,?,FALSE)", updateId,canonical(update))
                    requireWire(updateId < Long.MAX_VALUE)
                    next = maxOf(next, updateId + 1)
                }
                c.exec("UPDATE bot_state SET next_offset=?,success_ms=? WHERE id=1", next,identity.now())
            }
            pollFailures = 0; lastSuccess = identity.now(); 0L
        } catch (f: TelegramFailure) {
            progress()
            pollFailures = min(pollFailures + 1, 16)
            val base = f.retryAfterSeconds?.times(1000) ?: min(30_000L, 500L shl min(pollFailures - 1, 6))
            base + jitter(base)
        }
    }
    private fun enqueue(c: Connection, key: String, chat: Long, kind: String, payload: JsonObject) {
        if (c.query("SELECT id FROM bot_outbox WHERE id=?", key) { true }.isEmpty())
            c.exec("INSERT INTO bot_outbox (id,chat_id,kind,payload,next_attempt_ms) VALUES (?,?,?,?,?)", key,chat,kind,canonical(payload),identity.now())
    }
    fun processInbox(): Int = db.tx { c ->
        lease(c)
        val updates = c.query("SELECT update_id,payload FROM bot_inbox WHERE processed=FALSE ORDER BY update_id LIMIT 50") {
            it.getLong(1) to parseObject(it.getString(2))
        }
        for ((updateId,u) in updates) {
            val command = u.text("command")
            if (command != "noop") {
                val sender = u.longAt("senderId"); val chat = u.longAt("chatId")
                val args = u.arrayAt("args").map { it.jsonPrimitive.content }
                val sp = c.setSavepoint()
                try {
                    when (command) {
                        "help" -> enqueue(c,"$updateId:help",chat,"text",obj("text" to str(BOT_LOGIN_HELP)))
                        "invalid_login" -> enqueue(c,"$updateId:error",chat,"text",obj("text" to str(BOT_LOGIN_LINK_UNAVAILABLE)))
                        "start" -> { identity.botStart(c,args[0],sender); enqueue(c,"$updateId:prompt",chat,"login_prompt",obj("requestId" to str(args[0]))) }
                        "confirm", "deny" -> {
                            identity.botConfirm(c,args[0],sender,u.text("displayName"),command == "confirm",u.optionalText("username"))
                            enqueue(c,"$updateId:result",chat,"text",obj("text" to str(if (command == "confirm") "Вход подтверждён. Вернитесь в Chrona; токены в Telegram не отправляются." else "Вход отклонён.")))
                        }
                        else -> {
                            val account = identity.accountForTelegram(c,sender)
                            when (command) {
                                "discover" -> {
                                    identity.setDiscoverability(c,account,args[0] == "on")
                                    enqueue(c,"$updateId:result",chat,"text",obj("text" to str(if (args[0] == "on")
                                        "Вы явно разрешили находить ваш профиль по имени/нику. Доступ к статистике этим не открыт."
                                        else "Поиск вашего профиля выключен. Уже существующие дружба и явные grants не изменены.")))
                                }
                                "request" -> { social.request(c,account,args[0]); enqueue(c,"$updateId:result",chat,"text",obj("text" to str("Запрос отправлен."))) }
                                "accept", "reject" -> { social.respond(c,account,args[0],command == "accept"); enqueue(c,"$updateId:result",chat,"text",obj("text" to str("Ответ сохранён."))) }
                                "friends", "requests", "view" -> enqueue(c,"$updateId:view",chat,command,obj("viewer" to str(account), "args" to arr(args.map(::str))))
                            }
                        }
                    }
                } catch (f: Fault) {
                    c.rollback(sp)
                    val reply = if (command in setOf("start","confirm","deny")) BOT_LOGIN_LINK_UNAVAILABLE else "Операция недоступна: ${f.code}"
                    enqueue(c,"$updateId:error",chat,"text",obj("text" to str(reply)))
                } finally { c.releaseSavepoint(sp) }
                u.optionalText("callbackId")?.let { enqueue(c,"$updateId:callback",chat,"callback",obj("callbackId" to str(it))) }
            }
            c.exec("UPDATE bot_inbox SET processed=TRUE WHERE update_id=?", updateId)
        }
        updates.size
    }
    private fun render(c: Connection, job: BotJob): RenderedDelivery {
        val body = job.payload
        if (job.kind == "callback") return RenderedDelivery("answerCallbackQuery",obj("callback_query_id" to str(body.text("callbackId"))))
        val text: String
        var keyboard: JsonObject? = null
        var nextViewPayload: JsonObject? = null
        var nextViewId: String? = null
        when (job.kind) {
            "text" -> text = body.text("text")
            "login_prompt" -> {
                val request = body.text("requestId")
                val q = c.query("SELECT device_label,state,expires_ms,telegram_id FROM login_challenges WHERE id=?", request) {
                    listOf(it.getString(1),it.getString(2),it.getLong(3).toString(),it.getLong(4).toString())
                }.firstOrNull() ?: fail(404,"NOT_FOUND")
                requireWire(q[1] == "pending" && q[2].toLong() > identity.now() && q[3].toLong() == job.chat, "EXPIRED", 410)
                text = "Запрос входа Chrona, устройство: ${q[0]}. Подтвердите только если вы начали этот вход."
                keyboard = obj("inline_keyboard" to arr(listOf(arr(listOf(
                    obj("text" to str("Подтвердить"), "callback_data" to str("login:yes:$request")),
                    obj("text" to str("Отказать"), "callback_data" to str("login:no:$request")))))))
            }
            "friends", "requests", "view" -> {
                val viewer = identity.accountForTelegram(c,job.chat)
                requireWire(viewer == body.text("viewer"), "FORBIDDEN", 403)
                val value = when (job.kind) {
                    "friends" -> social.friends(c,viewer)
                    "requests" -> social.requests(c,viewer)
                    else -> {
                        val args = body.arrayAt("args").map { it.jsonPrimitive.content }
                        // Authorization is reevaluated immediately before EACH attempted delivery.
                        social.activityView(c,viewer,args[0],ViewRange.parse(args[1],args[2],args[3]))
                    }
                }
                val encoded = canonical(value)
                if (job.kind == "view") {
                    val part = body["partIndex"]?.jsonPrimitive?.intOrNull ?: 0
                    val fingerprint = hash(encoded)
                    // Each subsequent fragment is re-projected with CURRENT ACL. If the view
                    // changed, stop instead of mixing snapshots or sending stale private data.
                    if (part > 0) requireWire(body.text("snapshotHash") == fingerprint,"REVISION_CONFLICT",409)
                    val chunks = telegramChunks(encoded)
                    requireWire(part in chunks.indices)
                    text = if (chunks.size == 1) chunks.single() else "Chrona activity-view [${part+1}/${chunks.size}] JSON fragment\n${chunks[part]}"
                    if (part + 1 < chunks.size) {
                        val root = body.optionalText("rootJobId") ?: job.id
                        nextViewId = "$root:part:${part+1}"
                        nextViewPayload = obj("viewer" to str(viewer),"args" to body["args"],"partIndex" to num(part+1),
                            "snapshotHash" to str(fingerprint),"rootJobId" to str(root))
                    }
                } else text = encoded
            }
            else -> fail(400,"VALIDATION")
        }
        requireWire(text.length <= 3800, "NOT_IMPLEMENTED", 501) // no silent truncation or hidden counts
        return RenderedDelivery("sendMessage",obj("chat_id" to num(job.chat), "text" to str(text), "reply_markup" to keyboard),nextViewId,nextViewPayload)
    }
    fun deliverOne(): Boolean {
        val jobAndRequest = db.tx { c ->
            lease(c)
            val job = c.query("SELECT * FROM bot_outbox WHERE delivered=FALSE AND discarded=FALSE AND next_attempt_ms<=? ORDER BY next_attempt_ms,id LIMIT 1", identity.now()) {
                BotJob(it.getString("id"),it.getLong("chat_id"),it.getString("kind"),parseObject(it.getString("payload")),it.getInt("attempts"))
            }.firstOrNull() ?: return@tx null
            try { job to render(c,job) } catch (f: Fault) {
                // A revoked/expired/unsafe view is discarded, NEVER delivered from a stale snapshot.
                c.exec("UPDATE bot_outbox SET discarded=TRUE WHERE id=?",job.id)
                if (job.kind in setOf("friends","requests","view"))
                    enqueue(c,"${job.id}:error",job.chat,"text",obj("text" to str("Представление недоступно: ${f.code}")))
                if (job.kind == "login_prompt")
                    enqueue(c,"${job.id}:error",job.chat,"text",obj("text" to str(BOT_LOGIN_LINK_UNAVAILABLE)))
                job to null
            }
        } ?: return false
        val (job,request) = jobAndRequest
        if (request == null) return true
        try {
            transport.call(request.method,request.body)
            db.tx { c ->
                lease(c)
                c.exec("UPDATE bot_outbox SET delivered=TRUE,attempts=attempts+1 WHERE id=?",job.id)
                // Only enqueue the next fragment with this fragment's durable ack: ordered
                // retry survives restart, without persisting an authorized data snapshot.
                request.nextViewPayload?.let { enqueue(c,request.nextViewId!!,job.chat,"view",it) }
            }
        } catch (f: TelegramFailure) {
            val base = f.retryAfterSeconds?.times(1000) ?: min(3_600_000L,1000L shl min(job.attempts,11))
            db.tx { c -> lease(c); c.exec("UPDATE bot_outbox SET attempts=attempts+1,next_attempt_ms=? WHERE id=?",identity.now() + base + jitter(base),job.id) }
        }
        return true
    }
}

private fun telegramChunks(text: String): List<String> = buildList {
    var offset = 0
    while (offset < text.length) {
        var end = minOf(offset+3500,text.length)
        if (end < text.length && text[end-1].isHighSurrogate()) end--
        add(text.substring(offset,end)); offset = end
    }
}
