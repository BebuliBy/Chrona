package by.bebuliby.chrona.backend

import kotlinx.serialization.json.*
import java.sql.Connection
import java.time.LocalDate
import java.time.ZoneId

data class StoredEntity(val type: String, val id: String, val revision: String, val payload: JsonObject)

class Activities(private val identity: Identity) {
    private val db get() = identity.db
    companion object {
        fun provisionShorts(c: Connection, owner: String, now: String) {
            val habit = obj("id" to str(id()), "title" to str("YouTube Shorts"),
                "unit" to obj("code" to str("duration_ms"), "label" to str("ms")),
                "direction" to str("at_most"), "collectionMode" to str("automatic_shorts"),
                "createdAt" to str(now), "archivedAt" to JsonNull)
            save(c, owner, "habit", habit.text("id"), habit)
        }
        fun save(c: Connection, owner: String, type: String, entity: String, payload: JsonObject): String {
            val rev = c.revision()
            c.exec("DELETE FROM entities WHERE owner_id=? AND entity_type=? AND id=?", owner, type, entity)
            c.exec("INSERT INTO entities VALUES (?,?,?,?,?)", owner, type, entity, rev, canonical(payload))
            c.exec("INSERT INTO change_log VALUES (?,?,?,?,?,?)", rev.toLong(), owner, type, entity, rev, canonical(payload))
            return rev
        }
    }
    fun records(c: Connection, owner: String, type: String? = null): List<StoredEntity> {
        val sql = "SELECT entity_type,id,revision,payload FROM entities WHERE owner_id=?" + if (type == null) "" else " AND entity_type=?"
        val args = if (type == null) arrayOf<Any?>(owner) else arrayOf<Any?>(owner, type)
        return c.query(sql, *args) { StoredEntity(it.getString(1), it.getString(2), it.getString(3), parseObject(it.getString(4))) }
    }
    fun record(c: Connection, owner: String, type: String, entity: String): StoredEntity? = c.query(
        "SELECT revision,payload FROM entities WHERE owner_id=? AND entity_type=? AND id=?", owner, type, entity) {
        StoredEntity(type, entity, it.getString(1), parseObject(it.getString(2)))
    }.firstOrNull()

    fun idempotent(c: Connection, owner: String, key: String, request: JsonObject, block: () -> JsonObject): JsonObject {
        val keyId = uuid(key)
        val fingerprint = hash(canonical(request))
        val prior = c.query("SELECT fingerprint,result FROM request_dedup WHERE owner_id=? AND key_id=?", owner, keyId) { it.getString(1) to it.getString(2) }.firstOrNull()
        if (prior != null) {
            requireWire(prior.first == fingerprint, "IDEMPOTENCY_CONFLICT", 409)
            return parseObject(prior.second)
        }
        val result = block()
        c.exec("INSERT INTO request_dedup VALUES (?,?,?,?)", owner, keyId, fingerprint, canonical(result))
        return result
    }
    fun push(p: Principal, key: String, body: JsonObject): JsonObject = db.tx { c ->
        identity.checkSession(c, p)
        body.only("deviceId", "operations")
        requireWire(uuid(body.text("deviceId")) == p.deviceId, "FORBIDDEN", 403)
        val ops = body.arrayAt("operations")
        requireWire(ops.size <= 100)
        idempotent(c, p.accountId, key, obj("path" to str("sync/push"), "body" to body)) {
            obj("results" to arr(ops.map { element ->
                val op = element as? JsonObject ?: fail(400, "VALIDATION")
                op.only("operationId", "entityType", "entityId", "kind", "baseRevision", "payload")
                val operation = uuid(op.text("operationId"))
                val fingerprint = hash(canonical(op))
                val prior = c.query("SELECT fingerprint,result FROM operation_dedup WHERE owner_id=? AND operation_id=?", p.accountId, operation) { it.getString(1) to it.getString(2) }.firstOrNull()
                if (prior != null) {
                    if (prior.first != fingerprint) operationError(operation, "IDEMPOTENCY_CONFLICT")
                    else {
                        val result = parseObject(prior.second)
                        if (result.text("status") == "accepted") JsonObject(result + ("status" to str("duplicate"))) else result
                    }
                } else {
                    val savepoint = c.setSavepoint()
                    val result = try {
                        val (rev, duplicate) = mutate(c, p, op)
                        obj("operationId" to str(operation), "status" to str(if (duplicate) "duplicate" else "accepted"), "revision" to str(rev))
                    } catch (f: Fault) {
                        c.rollback(savepoint)
                        operationError(operation, f.code)
                    } finally { c.releaseSavepoint(savepoint) }
                    c.exec("INSERT INTO operation_dedup VALUES (?,?,?,?)", p.accountId, operation, fingerprint, canonical(result))
                    result
                }
            }))
        }
    }
    private fun operationError(operation: String, code: String) = obj("operationId" to str(operation),
        "status" to str(if (code in setOf("REVISION_CONFLICT", "IDEMPOTENCY_CONFLICT")) "conflict" else "rejected"),
        "error" to obj("code" to str(code)))

    private fun mutate(c: Connection, p: Principal, op: JsonObject): Pair<String, Boolean> {
        val type = op.text("entityType")
        requireWire(type in setOf("habit", "goal_revision", "manual_entry", "activity_interval", "observation_gap", "observation_segment"), "SOURCE_NOT_WRITABLE", 403)
        requireWire(op.text("kind") == "upsert", "POLICY_UNRESOLVED", 409)
        val entity = uuid(op.text("entityId")); val payload = op.objectAt("payload")
        requireWire(uuid(payload.text("id")) == entity)
        if (payload.containsKey("source") || payload.containsKey("ownerId") || payload.containsKey("accountId")) fail(403, "SOURCE_NOT_WRITABLE")
        val old = record(c, p.accountId, type, entity)
        val immutable = type in setOf("activity_interval", "goal_revision", "observation_segment")
        val clean = JsonObject((payload - "revision").mapValues { (key,value) ->
            if (key in setOf("id","habitId","deviceId","bootSessionId")) str(uuid(payload.text(key))) else value
        })
        // Immutable dedup is structural, even if a restarted client has a fresh operation ID.
        if (old != null && immutable) {
            requireWire(canonical(old.payload) == canonical(clean), "REVISION_CONFLICT", 409)
            return old.revision to true
        }
        val expected = op.optionalText("baseRevision")
        requireWire(expected == old?.revision, "REVISION_CONFLICT", 409)
        payload.optionalText("revision")?.let { requireWire(it == expected, "REVISION_CONFLICT", 409) }
        when (type) {
            "habit" -> validateHabit(clean, old)
            else -> {
                val habitId = uuid(clean.text("habitId"))
                val habit = record(c, p.accountId, "habit", habitId)?.payload ?: fail(404, "NOT_FOUND")
                when (type) {
                    "goal_revision" -> {
                        clean.only("id", "habitId", "amount", "requestedFrom", "recordedAt")
                        val amount = decimal(clean["amount"])
                        requireWire(habit.text("direction") == "at_most" || amount.signum() > 0)
                        day(clean.objectAt("requestedFrom")); timestamp(clean.text("recordedAt"))
                    }
                    "manual_entry" -> {
                        requireWire(habit.text("collectionMode") == "manual", "SOURCE_NOT_WRITABLE", 403)
                        clean.only("id", "habitId", "day", "value", "recordedAt")
                        decimal(clean["value"]); timestamp(clean.text("recordedAt")); val d = clean.objectAt("day"); day(d)
                        // Explicit current-day writes only; retroactive edit policy is NOT chosen.
                        requireWire(LocalDate.parse(d.text("date")) == LocalDate.now(identity.clock.withZone(ZoneId.of(d.text("zoneId")))), "POLICY_UNRESOLVED", 409)
                        requireWire(old == null || (old.payload["day"] == clean["day"] && old.payload["habitId"] == clean["habitId"]), "REVISION_CONFLICT", 409)
                        val other = records(c, p.accountId, type).any { it.id != entity && it.payload["habitId"] == clean["habitId"] && it.payload["day"] == clean["day"] }
                        requireWire(!other, "REVISION_CONFLICT", 409)
                    }
                    "activity_interval", "observation_segment" -> {
                        requireWire(habit.text("collectionMode") == "automatic_shorts", "SOURCE_NOT_WRITABLE", 403)
                        validateCollector(clean, type, p)
                    }
                    "observation_gap" -> {
                        requireWire(habit.text("collectionMode") == "automatic_shorts", "SOURCE_NOT_WRITABLE", 403)
                        clean.only("id", "habitId", "startUtc", "endUtc", "reason")
                        val start = timestamp(clean.text("startUtc")); val end = clean.optionalText("endUtc")?.let(::timestamp)
                        requireWire(end == null || end >= start)
                        enum(clean.text("reason"), "unknown_screen", "permission_lost", "user_disabled", "service_unavailable", "process_death", "reboot", "clock_changed", "unsupported_window")
                        if (old != null) requireWire(old.payload.optionalText("endUtc") == null && end != null &&
                            JsonObject(old.payload - "endUtc") == JsonObject(clean - "endUtc"), "REVISION_CONFLICT", 409)
                    }
                }
            }
        }
        return save(c, p.accountId, type, entity, clean) to false
    }
    private fun enum(value: String, vararg allowed: String) = requireWire(value in allowed, "UNSUPPORTED_VERSION")
    private fun validateHabit(v: JsonObject, old: StoredEntity?) {
        v.only("id", "title", "unit", "direction", "collectionMode", "createdAt", "archivedAt")
        requireWire(v.text("title").isNotBlank() && v.text("title").length <= 120)
        val unit = v.objectAt("unit"); unit.only("code", "label")
        requireWire(unit.text("code").matches(Regex("[a-z][a-z0-9_]{0,39}")) && unit.text("label").length <= 40)
        enum(v.text("direction"), "at_least", "at_most")
        enum(v.text("collectionMode"), "manual", "automatic_shorts")
        if (old == null) requireWire(v.text("collectionMode") == "manual", "SOURCE_NOT_WRITABLE", 403)
        else {
            requireWire(v["collectionMode"] == old.payload["collectionMode"] && v["unit"] == old.payload["unit"] && v["direction"] == old.payload["direction"], "SOURCE_NOT_WRITABLE", 403)
            // Only archive is contracted, not rename/unit/history rewrites.
            requireWire(JsonObject(v - "archivedAt") == JsonObject(old.payload - "archivedAt"), "POLICY_UNRESOLVED", 409)
            if (old.payload.optionalText("archivedAt") != null)
                requireWire(v["archivedAt"] == old.payload["archivedAt"], "POLICY_UNRESOLVED", 409)
        }
        val created = timestamp(v.text("createdAt"))
        v.optionalText("archivedAt")?.let { requireWire(timestamp(it) >= created) }
    }
    private fun validateCollector(v: JsonObject, type: String, p: Principal) {
        val common = arrayOf("id", "habitId", "deviceId", "bootSessionId", "startUtc", "endUtc", "startElapsedMillis", "endElapsedMillis", "quality", "collectorVersion")
        if (type == "activity_interval") v.only(*common, "durationMillis", "endReason") else v.only(*common, "observation")
        requireWire(uuid(v.text("deviceId")) == p.deviceId, "FORBIDDEN", 403); uuid(v.text("bootSessionId"))
        val start = v.longAt("startElapsedMillis"); val end = v.longAt("endElapsedMillis")
        requireWire(start >= 0 && end >= start)
        enum(v.text("quality"), "observed", "clock_discontinuity")
        val startUtc = timestamp(v.text("startUtc")); val endUtc = timestamp(v.text("endUtc"))
        requireWire(v.text("quality") == "clock_discontinuity" || endUtc >= startUtc)
        requireWire(v.text("collectorVersion").isNotBlank() && v.text("collectorVersion").length <= 80)
        if (type == "activity_interval") {
            requireWire(v.longAt("durationMillis") == end - start)
            enum(v.text("endReason"), "not_shorts", "unknown", "screen_locked", "user_disabled", "permission_lost", "service_stopped", "process_recovery", "clock_changed")
        } else enum(v.text("observation"), "shorts", "not_shorts", "unknown")
    }
    fun pull(p: Principal, cursor: String?, limit: Int): JsonObject = db.tx { c ->
        identity.checkSession(c, p); requireWire(limit in 1..500)
        val after = if (cursor == null) 0L else {
            // Durable opaque cursor is a random SQL capability bound to this owner.
            val stored = c.query("SELECT sequence_no FROM sync_cursors WHERE token_hash=? AND owner_id=?", hash(cursor), p.accountId) { it.getLong(1) }.firstOrNull()
            stored ?: fail(409, "CURSOR_RESNAPSHOT_REQUIRED")
        }
        val changes = c.query("SELECT * FROM change_log WHERE owner_id=? AND sequence_no>? ORDER BY sequence_no LIMIT ?", p.accountId, after, limit + 1) {
            val type = it.getString("entity_type"); val rev = it.getString("revision")
            val raw = parseObject(it.getString("payload"))
            val payload = if (type in setOf("habit","goal_revision","manual_entry")) JsonObject(raw + ("revision" to str(rev))) else raw
            it.getLong("sequence_no") to obj("entityType" to str(type), "entityId" to str(it.getString("entity_id")),
                "kind" to str("upsert"), "revision" to str(rev), "payload" to payload)
        }
        val page = changes.take(limit); val next = secret()
        c.exec("INSERT INTO sync_cursors VALUES (?,?,?)", hash(next), p.accountId, page.lastOrNull()?.first ?: after)
        obj("changes" to arr(page.map { it.second }), "nextCursor" to str(next), "hasMore" to bool(changes.size > limit))
    }
}
