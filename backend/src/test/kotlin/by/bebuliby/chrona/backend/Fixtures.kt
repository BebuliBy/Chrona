package by.bebuliby.chrona.backend

import kotlinx.serialization.json.*
import java.time.*
import kotlin.test.*

class TestClock(var millisValue: Long = Instant.parse("2026-10-18T12:00:00Z").toEpochMilli(), private val tz: ZoneId = ZoneOffset.UTC) : Clock() {
    override fun getZone() = tz
    override fun withZone(zone: ZoneId): Clock = object : Clock() {
        override fun getZone() = zone
        override fun withZone(z: ZoneId) = this@TestClock.withZone(z)
        override fun instant() = this@TestClock.instant()
    }
    override fun instant(): Instant = Instant.ofEpochMilli(millisValue)
    fun advance(ms: Long) { millisValue += ms }
}
data class TestLogin(val principal: Principal, val access: String, val refresh: String, val response: JsonObject)
class Fixture(url: String = "jdbc:h2:mem:${id()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE") {
    val db = Database(url)
    val clock = TestClock()
    val identity: Identity
    val backend: Backend
    val activities get() = backend.activities
    val social get() = backend.social
    init { db.migrate(); identity = Identity(db,clock,"ChronaFakeTestBot"); backend = Backend(identity) }
    fun challenge(device: String = id(), verifier: String = secret()): Pair<JsonObject,String> = identity.createChallenge(
        obj("deviceId" to str(device), "deviceLabel" to str("FAKE test device"), "codeChallenge" to str(hash(verifier)), "codeChallengeMethod" to str("S256")),device) to verifier
    fun login(sender: Long = 101, device: String = id()): TestLogin {
        val (q,v) = challenge(device)
        db.tx { c -> identity.botStart(c,q.text("requestId"),sender); identity.botConfirm(c,q.text("requestId"),sender,"FAKE user $sender",true) }
        val response = identity.exchange(q.text("requestId"),obj("codeVerifier" to str(v)))
        return TestLogin(identity.authenticate(response.text("accessToken")),response.text("accessToken"),response.text("refreshToken"),response)
    }
    fun auto(p: Principal) = db.tx { c -> activities.records(c,p.accountId,"habit").single { it.payload.text("collectionMode") == "automatic_shorts" } }
    fun habit(entity: String = id(), mode: String = "manual", title: String = "FAKE reading") = obj(
        "id" to str(entity), "title" to str(title), "unit" to obj("code" to str("pages"),"label" to str("pages")),
        "direction" to str("at_least"), "collectionMode" to str(mode), "createdAt" to str("2026-10-18T00:00:00Z"), "archivedAt" to JsonNull)
    fun op(type: String, payload: JsonObject, operation: String = id(), base: String? = null, kind: String = "upsert") = obj(
        "operationId" to str(operation), "entityType" to str(type), "entityId" to payload["id"], "kind" to str(kind),
        "baseRevision" to (base?.let(::str) ?: JsonNull), "payload" to payload)
    fun push(p: Principal, vararg operations: JsonObject, key: String = id(), device: String = p.deviceId) = activities.push(p,key,
        obj("deviceId" to str(device), "operations" to arr(operations.toList())))
    fun single(p: Principal, operation: JsonObject) = push(p,operation).arrayAt("results").single().jsonObject
    fun manual(p: Principal, habit: JsonObject = habit()): Pair<JsonObject,String> {
        val result = single(p,op("habit",habit)); assertEquals("accepted",result.text("status"))
        return habit to result.text("revision")
    }
    fun entry(habit: String, entity: String = id(), value: JsonElement = str("12.75"), date: String = "2026-10-18") = obj(
        "id" to str(entity), "habitId" to str(habit), "day" to obj("date" to str(date),"zoneId" to str("UTC")),
        "value" to value, "recordedAt" to str("2026-10-18T12:00:00Z"))
    fun goal(habit: String, amount: String = "10", date: String = "2026-10-18", zone: String = "UTC", entity: String = id()) = obj(
        "id" to str(entity), "habitId" to str(habit), "amount" to str(amount),
        "requestedFrom" to obj("date" to str(date),"zoneId" to str(zone)), "recordedAt" to str("2026-10-18T12:00:00Z"))
    fun interval(p: Principal, habit: String = auto(p).id, entity: String = id(), start: String = "2026-10-18T10:00:00Z", end: String = "2026-10-18T10:00:01Z") = obj(
        "id" to str(entity), "habitId" to str(habit), "deviceId" to str(p.deviceId), "bootSessionId" to str(id()),
        "startUtc" to str(start), "endUtc" to str(end), "startElapsedMillis" to num(1000), "endElapsedMillis" to num(2000),
        "durationMillis" to num(1000), "collectorVersion" to str("FAKE/1"), "quality" to str("observed"), "endReason" to str("not_shorts"))
    fun accept(a: Principal, b: Principal) {
        // Test fixture explicitly opts in; production has no automatic discoverability.
        db.tx { c -> c.exec("UPDATE accounts SET discoverable=TRUE WHERE id=?",b.accountId) }
        val q = db.tx { c -> social.request(c,a.accountId,b.accountId) }
        db.tx { c -> social.respond(c,b.accountId,q.text("requestId"),true) }
    }
    fun grant(a: Principal, b: Principal, habits: List<String>, from: String = "2026-10-18T00:00:00Z", to: String? = null, rev: String? = null) = db.tx { c ->
        social.putGrant(c,a.accountId,b.accountId,obj("habitIds" to arr(habits.map(::str)),
            "historyScope" to obj("kind" to str("time_window"),"fromInclusive" to str(from),"toExclusive" to (to?.let(::str) ?: JsonNull)),
            "expectedRevision" to (rev?.let(::str) ?: JsonNull)))
    }
    fun range() = ViewRange.parse("2026-10-18","2026-10-19","UTC")
}
fun assertFault(code: String, block: () -> Any?) = assertEquals(code,assertFailsWith<Fault>(block=block).code)
fun JsonObject.errorCode() = objectAt("error").text("code")
