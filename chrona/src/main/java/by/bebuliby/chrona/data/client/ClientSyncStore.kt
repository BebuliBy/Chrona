package by.bebuliby.chrona.data.client

import androidx.room.withTransaction
import by.bebuliby.chrona.data.*
import by.bebuliby.chrona.domain.*
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Test seam only: production always delegates to the same Room transaction boundary. */
internal interface ClientTransactions {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

/** Single bound local profile; no automatic cross-account/origin merge or destructive resnapshot. */
class ClientSyncStore internal constructor(private val dao: ChronaDao, private val transactions: ClientTransactions) {
    constructor(db: ChronaDatabase) : this(db.dao(), object : ClientTransactions {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = db.withTransaction { block() }
    })
    suspend fun meta() = dao.syncMeta()
    suspend fun frozenRequests() = dao.wireRequests()
    suspend fun socialRequests() = dao.wireRequests().filter { it.outboxId == null }
    suspend fun bind(origin: String, account: String) = transactions.inTransaction {
        val old = dao.syncMeta()
        SyncWire.binding(old?.origin, old?.accountId, origin, account)
        if (old == null) dao.saveSyncMeta(SyncMetaRow(origin = origin, accountId = account))
    }
    suspend fun consent(value: Boolean) = transactions.inTransaction {
        dao.syncMeta()?.let { dao.saveSyncMeta(it.copy(uploadEnabled = value)) }
    }
    suspend fun error(code: String?) = transactions.inTransaction {
        dao.syncMeta()?.let { dao.saveSyncMeta(it.copy(errorCode = code)) }
    }
    suspend fun resetCursor() = transactions.inTransaction {
        dao.syncMeta()?.let { dao.saveSyncMeta(it.copy(cursor = null)) }
    }
    suspend fun success(): Boolean = transactions.inTransaction {
        val m = dao.syncMeta() ?: throw ClientFailure("LOGIN_REQUIRED")
        if (dao.pendingCount() != 0 || dao.wireRequests().isNotEmpty()) return@inTransaction false
        dao.saveSyncMeta(m.copy(lastSuccessAt = Instant.ofEpochMilli(System.currentTimeMillis()), errorCode = null))
        true
    }
    suspend fun nextRequest(deviceId: String): WireRow? = transactions.inTransaction {
        val meta = dao.syncMeta() ?: throw ClientFailure("LOGIN_REQUIRED")
        dao.wireRequests().firstOrNull()?.let {
            if (it.errorCode != null) throw ClientFailure(it.errorCode)
            return@inTransaction it
        }
        val rows = dao.outbox()
        for (row in rows.filter { it.entityType == "habit" && it.entityId == ChronaRepository.SHORTS_ID }) {
            // A migration from v1 may have provision queued; server provision is NEVER client-created.
            if (meta.serverShortsId == null) throw ClientFailure("SHORTS_MAPPING_REQUIRED")
            dao.deleteOutbox(row.operationId)
        }
        val row = rows.firstOrNull { it.entityType != "habit" || it.entityId != ChronaRepository.SHORTS_ID }
            ?: return@inTransaction null
        val revision = dao.remote("${row.entityType}:${row.entityId}")?.revision
        val wire = WireRow(row.operationId, meta.origin, meta.accountId, "POST", "/v1/sync/push",
            SyncWire.freeze(row, deviceId, meta.serverShortsId, revision), row.operationId)
        dao.insertWire(wire)
        wire
    }
    suspend fun enqueue(method: String, path: String, body: JSONObject): WireRow = transactions.inTransaction {
        val m = dao.syncMeta() ?: throw ClientFailure("LOGIN_REQUIRED")
        val wire = WireRow(UUID.randomUUID().toString(), m.origin, m.accountId, method, path, WireCodec.canonical(body), null)
        dao.insertWire(wire); wire
    }
    suspend fun failed(wire: WireRow, code: String) = transactions.inTransaction {
        dao.saveWire(wire.copy(errorCode = code)); dao.syncMeta()?.let { dao.saveSyncMeta(it.copy(errorCode = code)) }
    }
    suspend fun acknowledge(wire: WireRow, response: JSONObject) = transactions.inTransaction {
        if (wire.outboxId == null) { dao.deleteWire(wire.requestId); return@inTransaction }
        val results = response.getJSONArray("results")
        if (results.length() != 1) throw ClientFailure("INVALID_RESPONSE")
        val result = results.getJSONObject(0)
        if (WireCodec.text(result, "operationId") != wire.outboxId) throw ClientFailure("INVALID_RESPONSE")
        val status = WireCodec.text(result, "status")
        if (status !in setOf("accepted", "duplicate")) throw ClientFailure(
            result.optJSONObject("error")?.let { WireCodec.text(it, "code") } ?: "SYNC_REJECTED", 409)
        val revision = WireCodec.text(result, "revision")
        val op = JSONObject(wire.body).getJSONArray("operations").getJSONObject(0)
        val type = WireCodec.text(op, "entityType"); val id = WireCodec.text(op, "entityId")
        dao.saveRemote(RemoteRow("$type:$id", revision, WireCodec.canonical(WireCodec.clean(op.getJSONObject("payload")))))
        dao.markApplied(AppliedRow(revision))
        updateRevision(type, id, revision)
        dao.deleteOutbox(wire.outboxId); dao.deleteWire(wire.requestId)
    }
    private suspend fun updateRevision(type: String, id: String, revision: String) {
        when (type) {
            "habit" -> dao.habit(id)?.let { dao.saveHabit(HabitRow(it.value.copy(revision = revision))) }
            "manual_entry" -> dao.entryById(id)?.let { dao.saveEntry(ManualRow(it.value.copy(revision = revision))) }
            "goal_revision" -> dao.goalRevision(id, revision)
        }
    }

    suspend fun applyPage(response: JSONObject) = transactions.inTransaction {
        var meta = dao.syncMeta() ?: throw ClientFailure("LOGIN_REQUIRED")
        for (change in WireCodec.objects(response.getJSONArray("changes"))) {
            val revision = WireCodec.text(change, "revision")
            if (dao.applied(revision) != 0) continue
            if (WireCodec.text(change, "kind") != "upsert") throw ClientFailure("UNSUPPORTED_VERSION")
            val type = WireCodec.text(change, "entityType")
            val serverId = WireCodec.uuid(WireCodec.text(change, "entityId"))
            val payload = change.getJSONObject("payload")
            if (WireCodec.uuid(WireCodec.text(payload, "id")) != serverId || payload.has("source") ||
                payload.has("ownerId") || payload.has("accountId")) throw ClientFailure("INVALID_RESPONSE")
            val clean = WireCodec.clean(payload)
            val serverAutomatic = type == "habit" && WireCodec.habit(payload).collectionMode == CollectionMode.AUTOMATIC_SHORTS
            if (serverAutomatic) {
                if (meta.serverShortsId != null && meta.serverShortsId != serverId) throw ClientFailure("SHORTS_MAPPING_CONFLICT")
                meta = meta.copy(serverShortsId = serverId)
                // No push of the old local provision; only server-owned system identity is authoritative.
                dao.outbox().filter { it.entityType == "habit" && it.entityId == ChronaRepository.SHORTS_ID }
                    .forEach { dao.deleteOutbox(it.operationId) }
            }
            val localId = if (type == "habit") SyncWire.localHabit(serverId, meta.serverShortsId) else serverId
            val key = "$type:$localId"
            val pending = dao.outbox().any { it.entityType == type && it.entityId == localId }
            val remote = dao.remote(key)
            if (pending && !serverAutomatic) {
                // Never replace a local edit with a remote one. An exact known remote version is only an echo.
                if (remote?.payload != WireCodec.canonical(clean)) throw ClientFailure("PULL_LOCAL_CONFLICT", 409)
            } else {
                val localPayload = JSONObject(WireCodec.canonical(payload)).put("id", localId).put("revision", revision)
                val habitId = if (localPayload.has("habitId")) SyncWire.localHabit(
                    WireCodec.uuid(WireCodec.text(localPayload, "habitId")), meta.serverShortsId) else null
                when (type) {
                    "habit" -> {
                        val value = WireCodec.habit(localPayload, localId)
                        val existing = dao.habit(localId)?.value
                        if (existing != null && remote == null && !serverAutomatic) throw ClientFailure("PULL_LOCAL_CONFLICT", 409)
                        if (existing != null && existing.collectionMode != value.collectionMode) throw ClientFailure("SOURCE_NOT_WRITABLE")
                        dao.saveHabit(HabitRow(value))
                    }
                    "goal_revision" -> {
                        val value = WireCodec.goal(localPayload, habitId!!)
                        val pendingSameDay = dao.outbox().filter { it.entityType == "goal_revision" && it.entityId != value.id }.any {
                            val p = JSONObject(it.payload)
                            WireCodec.text(p, "habitId") == habitId && WireCodec.day(p.getJSONObject("requestedFrom")) == value.requestedFrom
                        }
                        if (pendingSameDay) throw ClientFailure("PULL_LOCAL_CONFLICT", 409)
                        val habit = dao.habit(habitId)?.value ?: throw ClientFailure("INVALID_RESPONSE")
                        SourcePolicy.goal(habit.copy(archivedAt = null), value.amount)
                        val old = dao.goal(value.id)?.value
                        if (old == null) dao.insertGoal(GoalRow(value))
                        else if (old.copy(revision = revision) != value) throw ClientFailure("PULL_LOCAL_CONFLICT", 409)
                        else dao.goalRevision(value.id, revision)
                    }
                    "manual_entry" -> {
                        val habit = dao.habit(habitId!!)?.value ?: throw ClientFailure("INVALID_RESPONSE")
                        val value = WireCodec.manual(localPayload, habit)
                        val sameDay = dao.entry(habitId, value.day)?.value
                        if (sameDay != null && sameDay.id != value.id) throw ClientFailure("PULL_LOCAL_CONFLICT", 409)
                        val old = dao.entryById(value.id)?.value
                        if (old != null && remote == null && old.copy(revision = revision) != value) throw ClientFailure("PULL_LOCAL_CONFLICT", 409)
                        dao.saveEntry(ManualRow(value))
                    }
                    "activity_interval" -> {
                        requireAutomatic(habitId!!)
                        val value = WireCodec.interval(localPayload, habitId)
                        val old = dao.interval(value.id)?.value
                        if (old == null) dao.insertInterval(IntervalRow(value))
                        else if (old != value) throw ClientFailure("PULL_LOCAL_CONFLICT", 409)
                    }
                    "observation_segment" -> {
                        requireAutomatic(habitId!!)
                        val value = WireCodec.segment(localPayload, habitId)
                        val old = dao.segment(value.id)?.value
                        if (old == null) dao.insertSegment(SegmentRow(value))
                        else if (old != value) throw ClientFailure("PULL_LOCAL_CONFLICT", 409)
                    }
                    "observation_gap" -> {
                        requireAutomatic(habitId!!)
                        val value = WireCodec.gap(localPayload, habitId)
                        val old = dao.gap(value.id)?.value
                        if (old != null && remote == null && old != value) throw ClientFailure("PULL_LOCAL_CONFLICT", 409)
                        dao.saveGap(GapRow(value))
                    }
                    else -> throw ClientFailure("UNSUPPORTED_VERSION")
                }
            }
            dao.saveRemote(RemoteRow(key, revision, WireCodec.canonical(clean)))
            dao.markApplied(AppliedRow(revision))
        }
        dao.saveSyncMeta(meta.copy(cursor = WireCodec.text(response, "nextCursor")))
    }
    private suspend fun requireAutomatic(id: String) {
        if (dao.habit(id)?.value?.collectionMode != CollectionMode.AUTOMATIC_SHORTS) throw ClientFailure("SOURCE_NOT_WRITABLE")
    }
}
