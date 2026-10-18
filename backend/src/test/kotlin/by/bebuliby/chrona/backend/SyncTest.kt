package by.bebuliby.chrona.backend

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class SyncTest {
    @Test fun `migration checksum mismatch fails without destructive fallback`() {
        val f = Fixture(); val login = f.login()
        f.db.tx { it.exec("UPDATE schema_migrations SET checksum='FAKE_TAMPERED_CHECKSUM' WHERE version=1") }
        assertFailsWith<IllegalStateException> { f.db.migrate() }
        assertEquals(login.principal,f.identity.authenticate(login.access))
        assertEquals(1,f.db.tx { it.query("SELECT id FROM accounts") { true }.size })
    }
    @Test fun `wire IDs normalize consistently and fixed offset is not IANA DayKey`() {
        val f = Fixture(); val p = f.login().principal
        val h = f.habit(entity=id().uppercase())
        assertEquals("accepted",f.single(p,f.op("habit",h)).text("status"))
        val stored = f.db.tx { f.activities.record(it,p.accountId,"habit",h.text("id").lowercase())!! }
        assertEquals(h.text("id").lowercase(),stored.payload.text("id"))
        val entry = f.entry(h.text("id"))
        assertEquals("accepted",f.single(p,f.op("manual_entry",entry)).text("status"))
        val bad = JsonObject(f.entry(h.text("id")) + ("day" to obj("date" to str("2026-10-18"),"zoneId" to str("+03:00"))))
        assertEquals("VALIDATION",f.single(p,f.op("manual_entry",bad)).errorCode())
    }
    @Test fun `manual source and automatic provisioning cannot be spoofed`() {
        val f = Fixture(); val p = f.login().principal
        val auto = f.auto(p)
        assertEquals("SOURCE_NOT_WRITABLE",f.single(p,f.op("manual_entry",f.entry(auto.id))).errorCode())
        assertEquals("SOURCE_NOT_WRITABLE",f.single(p,f.op("manual_entry",JsonObject(f.entry(auto.id) + ("source" to str("collector"))))).errorCode())
        assertEquals("SOURCE_NOT_WRITABLE",f.single(p,f.op("habit",JsonObject(auto.payload + ("collectionMode" to str("manual"))),base=auto.revision)).errorCode())
        assertEquals("SOURCE_NOT_WRITABLE",f.single(p,f.op("habit",f.habit(mode="automatic_shorts"))).errorCode())
        assertEquals("SOURCE_NOT_WRITABLE",f.single(p,f.op("actual_total",f.entry(auto.id))).errorCode())
    }
    @Test fun `manual decimal absolute overgoal values revision and day uniqueness`() {
        val f = Fixture(); val p = f.login().principal; val h = f.manual(p).first
        val entry = f.entry(h.text("id"),value=str("100000000000000000000000000.123456789"))
        val first = f.single(p,f.op("manual_entry",entry)); assertEquals("accepted",first.text("status"))
        val changed = JsonObject(entry + ("value" to str("7.25")))
        assertEquals("REVISION_CONFLICT",f.single(p,f.op("manual_entry",changed)).errorCode())
        assertEquals("accepted",f.single(p,f.op("manual_entry",changed,base=first.text("revision"))).text("status"))
        assertEquals("REVISION_CONFLICT",f.single(p,f.op("manual_entry",f.entry(h.text("id")))).errorCode())
        val stored = f.db.tx { f.activities.record(it,p.accountId,"manual_entry",entry.text("id"))!! }
        assertEquals("7.25",stored.payload.text("value")) // absolute SET, never increment
    }
    @Test fun `numeric exponent negative comma and unknown write fields are rejected`() {
        val f = Fixture(); val p = f.login().principal; val h = f.manual(p).first.text("id")
        for (bad in listOf(num(2.5),str("-1"),str("1e3"),str("NaN"),str("2,5")))
            assertEquals("VALIDATION",f.single(p,f.op("manual_entry",f.entry(h,value=bad))).errorCode())
        assertEquals("VALIDATION",f.single(p,f.op("manual_entry",JsonObject(f.entry(h) + ("surprise" to bool(true))))).errorCode())
        assertEquals("SOURCE_NOT_WRITABLE",f.single(p,f.op("manual_entry",JsonObject(f.entry(h) + ("ownerId" to str(id()))))).errorCode())
        assertEquals("POLICY_UNRESOLVED",f.single(p,f.op("manual_entry",f.entry(h,date="2026-10-17"))).errorCode())
    }
    @Test fun `immutable interval dedup and conflicting retry never doubles duration`() {
        val f = Fixture(); val p = f.login().principal; val interval = f.interval(p); val op = f.op("activity_interval",interval)
        assertEquals("accepted",f.single(p,op).text("status"))
        assertEquals("duplicate",f.single(p,op).text("status"))
        assertEquals("duplicate",f.single(p,f.op("activity_interval",interval)).text("status"))
        assertEquals("REVISION_CONFLICT",f.single(p,f.op("activity_interval",JsonObject(interval + ("durationMillis" to num(2))))).errorCode())
        assertEquals(1000L,f.db.tx { f.activities.records(it,p.accountId,"activity_interval").sumOf { r -> r.payload.longAt("durationMillis") } })
    }
    @Test fun `bound devices owner scoped FK and monotonic durations are validated`() {
        val f = Fixture(); val p = f.login().principal; val other = f.login(202).principal
        assertFault("FORBIDDEN") { f.push(p,f.op("activity_interval",f.interval(p)),device=other.deviceId) }
        val foreign = JsonObject(f.interval(p) + ("deviceId" to str(other.deviceId)))
        assertEquals("FORBIDDEN",f.single(p,f.op("activity_interval",foreign)).errorCode())
        assertEquals("NOT_FOUND",f.single(p,f.op("activity_interval",f.interval(p,habit=f.auto(other).id))).errorCode())
        assertEquals("VALIDATION",f.single(p,f.op("activity_interval",JsonObject(f.interval(p) + ("durationMillis" to num(999))))).errorCode())
        assertEquals("VALIDATION",f.single(p,f.op("activity_interval",JsonObject(f.interval(p) + ("endElapsedMillis" to num(0))))).errorCode())
    }
    @Test fun `idempotency key canonical request repeat and changed body conflict`() {
        val f = Fixture(); val p = f.login().principal; val op = f.op("habit",f.habit()); val key = id()
        val first = f.push(p,op,key=key)
        assertEquals(first,f.push(p,JsonObject(op.toSortedMap(compareByDescending { it })),key=key))
        assertFault("IDEMPOTENCY_CONFLICT") { f.push(p,f.op("habit",f.habit()),key=key) }
        val altered = JsonObject(op + ("payload" to f.habit(entity=op.text("entityId"),title="FAKE changed")))
        assertEquals("IDEMPOTENCY_CONFLICT",f.single(p,altered).errorCode())
    }
    @Test fun `ordered batch rejects dependent orphan while later valid operation succeeds`() {
        val f = Fixture(); val p = f.login().principal; val bad = f.habit(mode="automatic_shorts"); val good = f.habit()
        val result = f.push(p,f.op("habit",bad),f.op("manual_entry",f.entry(bad.text("id"))),f.op("habit",good)).arrayAt("results")
        assertEquals(listOf("rejected","rejected","accepted"),result.map { it.jsonObject.text("status") })
        assertTrue(f.db.tx { f.activities.records(it,p.accountId,"manual_entry") }.isEmpty())
    }
    @Test fun `goals raw requests immutable zero at most and positive at least`() {
        val f = Fixture(); val p = f.login().principal; val manual = f.manual(p).first.text("id"); val auto = f.auto(p).id
        fun goal(h: String, amount: String) = obj("id" to str(id()),"habitId" to str(h),"amount" to str(amount),
            "requestedFrom" to obj("date" to str("2026-10-01"),"zoneId" to str("UTC")),"recordedAt" to str("2026-10-18T12:00:00Z"))
        assertEquals("VALIDATION",f.single(p,f.op("goal_revision",goal(manual,"0"))).errorCode())
        val zero = goal(auto,"0"); assertEquals("accepted",f.single(p,f.op("goal_revision",zero)).text("status"))
        assertEquals("duplicate",f.single(p,f.op("goal_revision",zero)).text("status"))
        assertEquals("REVISION_CONFLICT",f.single(p,f.op("goal_revision",JsonObject(zero + ("amount" to str("5"))))).errorCode())
        assertEquals("POLICY_UNRESOLVED",f.single(p,f.op("goal_revision",zero,kind="tombstone")).errorCode())
    }
    @Test fun `gap close is version checked and segments remain immutable evidence`() {
        val f = Fixture(); val p = f.login().principal; val auto = f.auto(p).id
        val gap = obj("id" to str(id()),"habitId" to str(auto),"startUtc" to str("2026-10-18T10:00:00Z"),"endUtc" to JsonNull,"reason" to str("permission_lost"))
        val initial = f.single(p,f.op("observation_gap",gap)); assertEquals("accepted",initial.text("status"))
        val closed = JsonObject(gap + ("endUtc" to str("2026-10-18T11:00:00Z")))
        assertEquals("REVISION_CONFLICT",f.single(p,f.op("observation_gap",closed)).errorCode())
        val next = f.single(p,f.op("observation_gap",closed,base=initial.text("revision")))
        assertEquals("accepted",next.text("status"))
        assertEquals("REVISION_CONFLICT",f.single(p,f.op("observation_gap",closed,base=next.text("revision"))).errorCode())
        val segment = JsonObject((f.interval(p) - setOf("durationMillis","endReason")) + ("observation" to str("not_shorts")))
        assertEquals("accepted",f.single(p,f.op("observation_segment",segment)).text("status"))
        assertEquals("duplicate",f.single(p,f.op("observation_segment",segment)).text("status"))
        val bad = JsonObject(segment + ("quality" to str("magic")))
        assertEquals("UNSUPPORTED_VERSION",f.single(p,f.op("observation_segment",JsonObject(bad + ("id" to str(id()))))).errorCode())
    }
    @Test fun `SQL file restart preserves operation acknowledgements cursor owner and schema`(@TempDir dir: Path) {
        val url = "jdbc:h2:file:${dir.resolve("chrona-test")};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
        val f = Fixture(url); val a = f.login(); val b = f.login(202); val op = f.op("activity_interval",f.interval(a.principal)); val ack = f.single(a.principal,op)
        val pull = f.activities.pull(a.principal,null,1); val cursor = pull.text("nextCursor")
        val restarted = Database(url); restarted.migrate()
        val core = Backend(Identity(restarted,f.clock,"ChronaFakeTestBot"))
        assertEquals("duplicate",core.activities.push(a.principal,id(),obj("deviceId" to str(a.principal.deviceId),"operations" to arr(listOf(op)))).arrayAt("results").single().jsonObject.text("status"))
        val more = core.activities.pull(a.principal,cursor,100)
        assertTrue(more.arrayAt("changes").isNotEmpty()); assertFalse(more["hasMore"]!!.jsonPrimitive.boolean)
        assertEquals(ack.text("revision"),more.arrayAt("changes").last().jsonObject.text("revision"))
        assertFault("CURSOR_RESNAPSHOT_REQUIRED") { core.activities.pull(b.principal,cursor,10) }
        val bChanges = core.activities.pull(b.principal,null,100)
        assertFalse(bChanges.toString().contains(f.auto(a.principal).id))
        assertEquals(1,restarted.tx { it.query("SELECT version FROM schema_migrations") { true }.size })
    }
}
