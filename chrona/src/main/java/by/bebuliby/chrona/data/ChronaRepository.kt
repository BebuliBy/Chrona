package by.bebuliby.chrona.data

import androidx.room.withTransaction
import by.bebuliby.chrona.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.Locale

class ChronaRepository(private val db: ChronaDatabase) {
    private val dao = db.dao()
    companion object {
        // Stable provisioned UUID, not a demo habit or a fake measurement.
        const val SHORTS_ID = "9b2ef341-bf76-47db-a810-fdf58b95d887"
        fun id(): String = UUID.randomUUID().toString()
    }
    private fun now(): Instant = Instant.ofEpochMilli(System.currentTimeMillis())
    val facts: Flow<LocalFacts> = db.invalidationTracker.createFlow(
        "habits", "goals", "manual_entries", "intervals", "segments", "gaps",
        "tracking_checkpoint", "tracker_health", "outbox", "wire_requests", emitInitialState = true,
    ).map { snapshot() }

    suspend fun snapshot(): LocalFacts = db.withTransaction {
        LocalFacts(dao.habits().map { it.value }, dao.goals().map { it.value },
            dao.entries().map { it.value }, dao.intervals().map { it.value },
            dao.segments().map { it.value }, dao.gaps().map { it.value },
            dao.checkpoint()?.value, dao.health()?.value,
            dao.pendingCount() + dao.wireRequests().count { it.outboxId == null })
    }

    suspend fun initialize() = db.withTransaction {
        if (dao.habit(SHORTS_ID) == null) {
            val habit = Habit(SHORTS_ID, "YouTube Shorts", ValueUnit("duration_ms", "мс"),
                GoalDirection.AT_MOST, CollectionMode.AUTOMATIC_SHORTS, now())
            dao.saveHabit(HabitRow(habit)); queue("habit", habit.id, habitJson(habit))
        }
    }

    private suspend fun required(id: String): Habit = dao.habit(id)?.value
        ?: throw CoreFailure("NOT_FOUND", "Привычка не найдена.")

    private fun day(day: DayKey) {
        runCatching { ZoneId.of(day.zoneId) }.getOrElse { throw CoreFailure("VALIDATION", "Неизвестный часовой пояс.") }
    }

    suspend fun create(action: ChronaAction.CreateManualHabit): String = db.withTransaction {
        day(action.requestedFrom)
        if (action.title.isBlank() || action.title.length > 200 || action.unit.label.isBlank() ||
            action.unit.code.isBlank() || '\u0000' in action.unit.code || '\u0000' in action.unit.label)
            throw CoreFailure("VALIDATION", "Укажите название и единицу привычки.")
        val habit = Habit(id(), action.title.trim(), action.unit, action.direction, CollectionMode.MANUAL, now())
        SourcePolicy.goal(habit, action.initialGoal)
        dao.saveHabit(HabitRow(habit)); queue("habit", habit.id, habitJson(habit))
        insertGoal(habit, action.initialGoal, action.requestedFrom)
        habit.id
    }

    suspend fun setManual(habitId: String, day: DayKey, value: Decimal) = db.withTransaction {
        val habit = required(habitId)
        SourcePolicy.manual(habit); SourcePolicy.amount(value); day(day)
        val old = dao.entry(habitId, day)?.value
        val entry = ManualEntry(old?.id ?: id(), habitId, day, value, now(), old?.revision)
        dao.saveEntry(ManualRow(entry))
        queue("manual_entry", entry.id, JSONObject().put("id", entry.id).put("habitId", habitId)
            .put("day", dayJson(day)).put("value", value.toPlainString()).put("recordedAt", entry.recordedAt.toString())
            .put("revision", entry.revision ?: JSONObject.NULL), entry.revision)
    }

    suspend fun setGoal(habitId: String, value: Decimal, requestedFrom: DayKey) = db.withTransaction {
        val habit = required(habitId)
        SourcePolicy.goal(habit, value); day(requestedFrom)
        insertGoal(habit, value, requestedFrom)
    }

    private suspend fun insertGoal(habit: Habit, amount: Decimal, requestedFrom: DayKey) {
        val revision = GoalRevision(id(), habit.id, amount, requestedFrom, now())
        dao.insertGoal(GoalRow(revision))
        queue("goal_revision", revision.id, JSONObject().put("id", revision.id).put("habitId", habit.id)
            .put("amount", amount.toPlainString()).put("requestedFrom", dayJson(requestedFrom))
            .put("recordedAt", revision.recordedAt.toString()).put("revision", JSONObject.NULL))
    }

    suspend fun archive(habitId: String) = db.withTransaction {
        val habit = required(habitId)
        if (habit.collectionMode != CollectionMode.MANUAL)
            throw CoreFailure("SOURCE_NOT_WRITABLE", "Системную активность можно отключить в настройках наблюдения.")
        if (habit.archivedAt == null) {
            val archived = habit.copy(archivedAt = now())
            dao.saveHabit(HabitRow(archived)); queue("habit", archived.id, habitJson(archived), archived.revision)
        }
    }

    suspend fun checkpoint(): TrackingCheckpoint? = dao.checkpoint()?.value

    /** Only the collector uses this typed path. No scalar actual/source override exists. */
    suspend fun record(batch: CollectorBatch) = db.withTransaction {
        val habit = required(SHORTS_ID)
        check(habit.collectionMode == CollectionMode.AUTOMATIC_SHORTS)
        for (value in batch.intervals) {
            require(value.habitId == SHORTS_ID && value.durationMillis >= 0 &&
                value.endElapsedMillis - value.startElapsedMillis == value.durationMillis)
            val existing = dao.interval(value.id)?.value
            if (existing != null) { require(existing == value) { "Immutable interval conflict" }; continue }
            dao.insertInterval(IntervalRow(value))
            queue("activity_interval", value.id, JSONObject().put("id", value.id).put("habitId", value.habitId)
                .put("deviceId", value.deviceId).put("bootSessionId", value.bootSessionId)
                .put("startUtc", value.startUtc.toString()).put("endUtc", value.endUtc.toString())
                .put("startElapsedMillis", value.startElapsedMillis).put("endElapsedMillis", value.endElapsedMillis)
                .put("durationMillis", value.durationMillis).put("collectorVersion", value.collectorVersion)
                .put("quality", value.quality.name.lowercase(Locale.ROOT)).put("endReason", value.endReason.name.lowercase(Locale.ROOT)))
        }
        for (value in batch.segments) {
            require(value.habitId == SHORTS_ID && value.endElapsedMillis >= value.startElapsedMillis)
            val existing = dao.segment(value.id)?.value
            if (existing != null) { require(existing == value) { "Immutable segment conflict" }; continue }
            dao.insertSegment(SegmentRow(value))
            queue("observation_segment", value.id, JSONObject().put("id", value.id).put("habitId", value.habitId)
                .put("deviceId", value.deviceId).put("bootSessionId", value.bootSessionId)
                .put("startUtc", value.startUtc.toString()).put("endUtc", value.endUtc.toString())
                .put("startElapsedMillis", value.startElapsedMillis).put("endElapsedMillis", value.endElapsedMillis)
                .put("observation", value.observation.name.lowercase(Locale.ROOT)).put("quality", value.quality.name.lowercase(Locale.ROOT))
                .put("collectorVersion", value.collectorVersion))
        }
        for (gap in batch.gaps) {
            require(gap.habitId == SHORTS_ID)
            dao.saveGap(GapRow(gap))
            queue("observation_gap", gap.id, JSONObject().put("id", gap.id).put("habitId", gap.habitId)
                .put("startUtc", gap.startUtc.toString()).put("endUtc", gap.endUtc?.toString() ?: JSONObject.NULL)
                .put("reason", gap.reason.name.lowercase(Locale.ROOT)))
        }
        batch.checkpoint?.let { dao.saveCheckpoint(CheckpointRow(it)) }
        dao.saveHealth(HealthRow(value = batch.health))
    }

    private suspend fun queue(type: String, entityId: String, payload: JSONObject, revision: String? = null) {
        dao.enqueue(OutboxRow(id(), type, entityId, baseRevision = revision, payload = payload.toString(), createdAt = now()))
    }
    private fun dayJson(day: DayKey) = JSONObject().put("date", day.date.toString()).put("zoneId", day.zoneId)
    private fun habitJson(h: Habit) = JSONObject().put("id", h.id).put("title", h.title)
        .put("unit", JSONObject().put("code", h.unit.code).put("label", h.unit.label))
        .put("direction", h.direction.name.lowercase(Locale.ROOT)).put("collectionMode", h.collectionMode.name.lowercase(Locale.ROOT))
        .put("createdAt", h.createdAt.toString()).put("archivedAt", h.archivedAt?.toString() ?: JSONObject.NULL)
        .put("revision", h.revision ?: JSONObject.NULL)
}
