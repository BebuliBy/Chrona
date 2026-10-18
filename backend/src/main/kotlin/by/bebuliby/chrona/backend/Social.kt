package by.bebuliby.chrona.backend

import kotlinx.serialization.json.*
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class ViewRange(val from: LocalDate, val to: LocalDate, val zone: ZoneId) {
    val start: Instant get() = from.atStartOfDay(zone).toInstant()
    val end: Instant get() = to.atStartOfDay(zone).toInstant()
    fun wire() = obj("fromInclusive" to str(from.toString()), "toExclusive" to str(to.toString()), "zoneId" to str(zone.id))
    companion object {
        fun parse(from: String, to: String, zone: String): ViewRange {
            val range = try { ViewRange(LocalDate.parse(from), LocalDate.parse(to), by.bebuliby.chrona.backend.zone(zone)) } catch (_: Exception) { fail(400, "VALIDATION") }
            requireWire(range.to > range.from && range.to.toEpochDay() - range.from.toEpochDay() <= 366)
            return range
        }
    }
}
data class VisibleFacts(val habits: List<StoredEntity>, val entries: List<StoredEntity>,
                        val intervals: List<JsonObject>, val gaps: List<JsonObject>,
                        val goals: List<StoredEntity> = emptyList(), val days: List<LocalDate> = emptyList(),
                        val fullDays: Set<LocalDate> = emptySet(), val evidenceDays: Set<String> = emptySet(),
                        val multiDeviceDays: Set<String> = emptySet(), val ambiguousDays: Set<String> = emptySet())

class Social(private val identity: Identity, private val activities: Activities) {
    private val db get() = identity.db
    fun friends(c: Connection, owner: String): JsonObject {
        val ids = c.query("SELECT account_a,account_b FROM friendships WHERE account_a=? OR account_b=? ORDER BY account_a,account_b", owner, owner) {
            if (it.getString(1) == owner) it.getString(2) else it.getString(1)
        }
        return obj("items" to arr(ids.map { JsonObject(identity.publicUser(c, it) + ("relation" to str("accepted"))) }), "nextCursor" to JsonNull)
    }
    fun friends(p: Principal) = db.tx { c -> identity.checkSession(c, p); friends(c, p.accountId) }
    fun requests(c: Connection, owner: String) = obj("items" to arr(c.query("SELECT * FROM friend_requests WHERE sender_id=? OR target_id=? ORDER BY id", owner, owner) {
        obj("requestId" to str(it.getString("id")), "senderAccountId" to str(it.getString("sender_id")),
            "targetAccountId" to str(it.getString("target_id")), "state" to str(it.getString("state")))
    }))
    fun requests(p: Principal) = db.tx { c -> identity.checkSession(c, p); requests(c, p.accountId) }
    fun accepted(c: Connection, owner: String, friend: String): Boolean {
        val (a,b) = listOf(uuid(owner), uuid(friend)).sorted()
        return c.query("SELECT account_a FROM friendships WHERE account_a=? AND account_b=?", a,b) { true }.isNotEmpty()
    }
    fun request(c: Connection, owner: String, targetRaw: String): JsonObject {
        val target = uuid(targetRaw); requireWire(owner != target)
        requireWire(c.query("SELECT id FROM accounts WHERE id=? AND discoverable=TRUE", target) { true }.isNotEmpty(), "NOT_FOUND", 404)
        requireWire(!accepted(c, owner, target), "REVISION_CONFLICT", 409)
        requireWire(c.query("SELECT id FROM friend_requests WHERE state='pending' AND ((sender_id=? AND target_id=?) OR (sender_id=? AND target_id=?))", owner, target, target, owner) { true }.isEmpty(), "REVISION_CONFLICT", 409)
        val request = id()
        c.exec("INSERT INTO friend_requests VALUES (?,?,?,'pending')", request, owner, target)
        return obj("requestId" to str(request), "state" to str("pending"))
    }
    fun respond(c: Connection, owner: String, requestRaw: String, accept: Boolean): JsonObject {
        val request = uuid(requestRaw)
        val q = c.query("SELECT sender_id,target_id,state FROM friend_requests WHERE id=?", request) {
            Triple(it.getString(1), it.getString(2), it.getString(3))
        }.firstOrNull() ?: fail(404, "NOT_FOUND")
        requireWire(q.second == owner, "FORBIDDEN", 403)
        requireWire(q.third == "pending", "REVISION_CONFLICT", 409)
        val state = if (accept) "accepted" else "rejected"
        c.exec("UPDATE friend_requests SET state=? WHERE id=?", state, request)
        if (accept) {
            val (a,b) = listOf(owner, q.first).sorted()
            c.exec("INSERT INTO friendships VALUES (?,?)", a,b)
        }
        return obj("requestId" to str(request), "state" to str(state))
    }
    fun mutate(p: Principal, key: String, path: String, body: JsonObject, action: (Connection) -> JsonObject): JsonObject = db.tx { c ->
        identity.checkSession(c, p)
        activities.idempotent(c, p.accountId, key, obj("path" to str(path), "body" to body)) { action(c) }
    }
    fun deleteFriend(c: Connection, owner: String, friendRaw: String): JsonObject {
        val friend = uuid(friendRaw)
        val (a,b) = listOf(owner,friend).sorted()
        c.exec("DELETE FROM friendships WHERE account_a=? AND account_b=?", a,b)
        c.exec("DELETE FROM grants WHERE (owner_id=? AND friend_id=?) OR (owner_id=? AND friend_id=?)", owner,friend,friend,owner)
        c.exec("UPDATE friend_requests SET state='removed' WHERE state='accepted' AND ((sender_id=? AND target_id=?) OR (sender_id=? AND target_id=?))", owner,friend,friend,owner)
        return obj("removed" to bool(true))
    }
    fun putGrant(c: Connection, owner: String, friendRaw: String, body: JsonObject): JsonObject {
        val friend = uuid(friendRaw); body.only("habitIds", "historyScope", "expectedRevision")
        requireWire(accepted(c, owner, friend), "FORBIDDEN", 403)
        val ids = body.arrayAt("habitIds").map { requireWire(it is JsonPrimitive && it.isString); uuid(it.jsonPrimitive.content) }
        requireWire(ids.distinct().size == ids.size && ids.size <= 100)
        ids.forEach { requireWire(activities.record(c, owner, "habit", it) != null, "NOT_FOUND", 404) }
        val scope = body.objectAt("historyScope")
        when (scope.text("kind")) {
            "unresolved" -> { scope.only("kind"); requireWire(ids.isEmpty(), "POLICY_UNRESOLVED", 409) }
            "time_window" -> {
                scope.only("kind", "fromInclusive", "toExclusive")
                val start = timestamp(scope.text("fromInclusive")); val end = scope.optionalText("toExclusive")?.let(::timestamp)
                requireWire(end == null || end > start)
            }
            else -> fail(400, "UNSUPPORTED_VERSION")
        }
        val old = c.query("SELECT revision FROM grants WHERE owner_id=? AND friend_id=?", owner, friend) { it.getString(1) }.firstOrNull()
        requireWire(body.optionalText("expectedRevision") == old, "REVISION_CONFLICT", 409)
        val revision = c.revision()
        val stored = obj("habitIds" to arr(ids.map(::str)), "historyScope" to scope, "revision" to str(revision))
        c.exec("DELETE FROM grants WHERE owner_id=? AND friend_id=?", owner, friend)
        c.exec("INSERT INTO grants VALUES (?,?,?,?)", owner,friend,revision,canonical(stored))
        return stored
    }
    fun grant(c: Connection, owner: String, friend: String): JsonObject = c.query("SELECT payload FROM grants WHERE owner_id=? AND friend_id=?", owner,uuid(friend)) {
        parseObject(it.getString(1))
    }.firstOrNull() ?: fail(409, "POLICY_UNRESOLVED")
    fun getGrant(p: Principal, friend: String) = db.tx { c -> identity.checkSession(c, p); grant(c, p.accountId, uuid(friend)) }

    fun search(p: Principal, q: String, cursor: String?): JsonObject = db.tx { c ->
        identity.checkSession(c, p); requireWire(q.length in 2..80)
        requireWire(cursor == null, "NOT_IMPLEMENTED", 501)
        c.rate("search:${p.accountId}", identity.now(), 20)
        // Explicitly opted-in accounts only. No implicit Telegram directory/phone search.
        val users = c.query("SELECT id,display_name FROM accounts WHERE discoverable=TRUE AND id<>? ORDER BY id", p.accountId) {
            it.getString(1) to it.getString(2)
        }.filter { it.second.contains(q, ignoreCase = true) }
        requireWire(users.size <= 100, "NOT_IMPLEMENTED", 501) // never silently truncate
        obj("items" to arr(users.map { identity.publicUser(c, it.first) }), "nextCursor" to JsonNull)
    }

    // ACL engine shared by owner/friend projections and bot delivery. No cached authorization.
    // Returns only allowed facts; never exposes device/boot/segments/service metadata to friends.
    fun visibleFacts(c: Connection, viewer: String, owner: String, range: ViewRange): VisibleFacts {
        val isOwner = viewer == owner
        var start = range.start; var end = range.end
        val allowed: Set<String>? = if (isOwner) null else {
            requireWire(accepted(c, owner, viewer), "NOT_FOUND", 404)
            val grant = grant(c, owner, viewer)
            val scope = grant.objectAt("historyScope")
            requireWire(scope.text("kind") == "time_window", "POLICY_UNRESOLVED", 409)
            start = maxOf(start, timestamp(scope.text("fromInclusive")))
            scope.optionalText("toExclusive")?.let { end = minOf(end, timestamp(it)) }
            grant.arrayAt("habitIds").map { it.jsonPrimitive.content }.toSet()
        }
        val habits = activities.records(c, owner, "habit").filter { allowed == null || it.id in allowed }
        val habitIds = habits.map { it.id }.toSet()
        if (start >= end) return VisibleFacts(emptyList(),emptyList(),emptyList(),emptyList())
        val days = generateSequence(range.from) { it.plusDays(1) }.takeWhile { it < range.to }.filter {
            it.atStartOfDay(range.zone).toInstant() < end && it.plusDays(1).atStartOfDay(range.zone).toInstant() > start
        }.toList()
        val fullDays = days.filter {
            it.atStartOfDay(range.zone).toInstant() >= start && it.plusDays(1).atStartOfDay(range.zone).toInstant() <= end
        }.toSet()
        fun within(v: JsonObject): Boolean {
            val a = timestamp(v.text("startUtc")); val b = v.optionalText("endUtc")?.let(::timestamp)
            if (b == null) {
                // An unclosed gap reports a known authorized start, never an invented end/duration.
                requireWire(a >= start, "POLICY_UNRESOLVED", 409)
                return a < end
            }
            if (maxOf(a,b) <= start || minOf(a,b) >= end) return false
            // Clipping monotonic durations across a wall-clock boundary requires OPEN-TIME.
            // No prorating, original outside timestamps or invented partial sums.
            requireWire(minOf(a,b) >= start && maxOf(a,b) <= end, "POLICY_UNRESOLVED", 409)
            return true
        }
        val rawIntervals = activities.records(c, owner, "activity_interval").filter { it.payload.text("habitId") in habitIds && within(it.payload) }
        val intervals = rawIntervals.map { r ->
            JsonObject(r.payload.filterKeys { it in setOf("id", "habitId", "startUtc", "endUtc", "durationMillis", "quality") })
        }.sortedWith(compareBy({ timestamp(it.text("startUtc")) }, { it.text("id") }))
        val gaps = activities.records(c, owner, "observation_gap").filter { it.payload.text("habitId") in habitIds && within(it.payload) }
            .map { obj("id" to str(it.id),"habitId" to it.payload["habitId"],"startUtc" to it.payload["startUtc"],
                "endUtc" to (it.payload["endUtc"] ?: JsonNull),"reason" to it.payload["reason"]) }
            .sortedWith(compareBy({ timestamp(it.text("startUtc")) }, { it.text("id") }))
        fun exactDay(r: StoredEntity, field: String): Boolean {
            if (r.payload.text("habitId") !in habitIds) return false
            val d = r.payload.objectAt(field)
            return d.text("zoneId") == range.zone.id && LocalDate.parse(d.text("date")) in fullDays
        }
        val entries = activities.records(c, owner, "manual_entry").filter { exactDay(it,"day") }
        val goals = activities.records(c, owner, "goal_revision").filter { exactDay(it,"requestedFrom") }
        // Segment payloads and device IDs never enter the public projection. Keep only
        // per-authorized-cell evidence/ambiguity flags, after the same habit/time ACL.
        val segments = activities.records(c, owner, "observation_segment").filter {
            if (it.payload.text("habitId") !in habitIds) false else {
                val a = timestamp(it.payload.text("startUtc")); val b = timestamp(it.payload.text("endUtc"))
                minOf(a,b) >= start && maxOf(a,b) <= end
            }
        }
        val evidence = mutableSetOf<String>(); val multiDevice = mutableSetOf<String>(); val ambiguous = mutableSetOf<String>()
        for (date in days) for (habit in habits.filter { it.payload.text("collectionMode") == "automatic_shorts" }) {
            val a = date.atStartOfDay(range.zone).toInstant(); val b = date.plusDays(1).atStartOfDay(range.zone).toInstant()
            fun overlaps(r: StoredEntity): Boolean {
                val x = timestamp(r.payload.text("startUtc")); val y = timestamp(r.payload.text("endUtc"))
                return minOf(x,y) < b && maxOf(x,y) >= a
            }
            val observations = (rawIntervals + segments).filter { it.payload.text("habitId") == habit.id && overlaps(it) }
            val key = "${habit.id}|$date"
            if (observations.map { it.payload.text("deviceId") }.distinct().size > 1) multiDevice += key
            if (segments.any { it.payload.text("habitId") == habit.id && overlaps(it) && it.payload.text("quality") == "observed" }) evidence += key
            if (observations.any { it.payload.text("quality") != "observed" || timestamp(it.payload.text("startUtc")) < a || timestamp(it.payload.text("endUtc")) >= b }) ambiguous += key
        }
        return VisibleFacts(habits,entries,intervals,gaps,goals,days,fullDays,evidence,multiDevice,ambiguous)
    }
    fun activityView(c: Connection, viewer: String, ownerRaw: String, range: ViewRange): JsonObject {
        val owner = uuid(ownerRaw)
        val facts = visibleFacts(c, viewer, owner, range)
        return ActivityViews.snapshot(identity.publicUser(c,owner),range,facts,viewer == owner,
            LocalDate.now(identity.clock.withZone(range.zone)))
    }
    fun activityView(p: Principal, owner: String, range: ViewRange) = db.tx { c -> identity.checkSession(c, p); activityView(c, p.accountId, owner, range) }
}
