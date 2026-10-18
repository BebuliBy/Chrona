package by.bebuliby.chrona.data.client

import by.bebuliby.chrona.data.ChronaRepository
import by.bebuliby.chrona.data.OutboxRow
import by.bebuliby.chrona.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

object WireCodec {
    fun only(j: JSONObject, vararg keys: String) {
        if (j.keys().asSequence().any { it !in keys }) throw ClientFailure("INVALID_RESPONSE")
    }
    fun uuid(value: String): String {
        if (!value.matches(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))) throw ClientFailure("INVALID_RESPONSE")
        return UUID.fromString(value).toString()
    }
    fun text(j: JSONObject, key: String): String = (j.get(key) as? String) ?: throw ClientFailure("INVALID_RESPONSE")
    fun optional(j: JSONObject, key: String): String? = if (!j.has(key) || j.isNull(key)) null else text(j, key)
    fun boolean(j: JSONObject, key: String): Boolean = (j.get(key) as? Boolean) ?: throw ClientFailure("INVALID_RESPONSE")
    fun profile(j: JSONObject) = RemoteProfile(uuid(text(j,"accountId")),text(j,"displayName"),boolean(j,"telegramLinked"),
        if (j.has("discoverable")) boolean(j,"discoverable") else false)
    fun number(j: JSONObject, key: String): Long {
        val n = j.get(key)
        if (n !is Int && n !is Long) throw ClientFailure("INVALID_RESPONSE")
        return (n as Number).toLong()
    }
    inline fun <reified T : Enum<T>> enum(value: String): T =
        enumValues<T>().firstOrNull { it.name.lowercase(Locale.ROOT) == value } ?: throw ClientFailure("UNSUPPORTED_VERSION")
    fun decimal(value: Any, signed: Boolean = false): Decimal {
        val raw = value as? String ?: throw ClientFailure("INVALID_RESPONSE")
        if (raw.length > 2048 || !raw.matches(Regex(if (signed) "-?(0|[1-9][0-9]*)(\\.[0-9]+)?" else "(0|[1-9][0-9]*)(\\.[0-9]+)?")))
            throw ClientFailure("INVALID_RESPONSE")
        return raw.toBigDecimal()
    }
    fun day(j: JSONObject): DayKey {
        only(j, "date", "zoneId")
        return DayKey(LocalDate.parse(text(j, "date")), ZoneId.of(text(j, "zoneId")).id)
    }
    fun range(j: JSONObject) = DayRange(LocalDate.parse(text(j, "fromInclusive")), LocalDate.parse(text(j, "toExclusive")), ZoneId.of(text(j, "zoneId")).id)
    fun stamp(j: JSONObject, key: String): Instant = Instant.parse(text(j, key))
    fun clean(j: JSONObject): JSONObject = JSONObject(canonical(j)).apply { remove("revision") }
    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Boolean, is Number -> value.toString()
        else -> throw ClientFailure("INVALID_RESPONSE")
    }
    fun habit(j: JSONObject, localId: String = uuid(text(j, "id"))): Habit {
        if (j.has("source") || j.has("ownerId") || j.has("accountId")) throw ClientFailure("SOURCE_NOT_WRITABLE")
        only(j, "id", "title", "unit", "direction", "collectionMode", "createdAt", "archivedAt", "revision")
        val unit = j.getJSONObject("unit")
        val h = Habit(localId, text(j, "title"), ValueUnit(text(unit, "code"), text(unit, "label")),
            enum(text(j, "direction")), enum(text(j, "collectionMode")), stamp(j, "createdAt"),
            optional(j, "archivedAt")?.let(Instant::parse), optional(j, "revision"))
        if (h.collectionMode == CollectionMode.AUTOMATIC_SHORTS &&
            (h.direction != GoalDirection.AT_MOST || h.unit.code != "duration_ms")) throw ClientFailure("SOURCE_NOT_WRITABLE")
        return h
    }
    fun goal(j: JSONObject, habitId: String = uuid(text(j, "habitId"))): GoalRevision {
        only(j, "id", "habitId", "amount", "requestedFrom", "recordedAt", "revision")
        return GoalRevision(uuid(text(j, "id")), habitId, decimal(j.get("amount")), day(j.getJSONObject("requestedFrom")), stamp(j, "recordedAt"), optional(j, "revision"))
    }
    fun manual(j: JSONObject, habit: Habit): ManualEntry {
        if (j.has("source") || habit.collectionMode != CollectionMode.MANUAL) throw ClientFailure("SOURCE_NOT_WRITABLE")
        only(j, "id", "habitId", "day", "value", "recordedAt", "revision")
        SourcePolicy.manual(habit.copy(archivedAt = null)) // archival does not erase already-synced history
        return ManualEntry(uuid(text(j, "id")), habit.id, day(j.getJSONObject("day")), decimal(j.get("value")),
            stamp(j, "recordedAt"), optional(j, "revision"))
    }
    fun interval(j: JSONObject, habitId: String): ActivityInterval {
        only(j, "id", "habitId", "deviceId", "bootSessionId", "startUtc", "endUtc", "startElapsedMillis",
            "endElapsedMillis", "durationMillis", "collectorVersion", "quality", "endReason", "revision")
        val value = ActivityInterval(uuid(text(j, "id")), habitId, uuid(text(j, "deviceId")), uuid(text(j, "bootSessionId")),
            stamp(j, "startUtc"), stamp(j, "endUtc"), number(j, "startElapsedMillis"), number(j, "endElapsedMillis"),
            number(j, "durationMillis"), text(j, "collectorVersion"), enum(text(j, "quality")), enum(text(j, "endReason")))
        if (value.startElapsedMillis < 0 || value.endElapsedMillis < value.startElapsedMillis ||
            value.durationMillis != value.endElapsedMillis - value.startElapsedMillis) throw ClientFailure("INVALID_RESPONSE")
        return value
    }
    fun segment(j: JSONObject, habitId: String): ObservationSegment {
        only(j, "id", "habitId", "deviceId", "bootSessionId", "startUtc", "endUtc", "startElapsedMillis",
            "endElapsedMillis", "collectorVersion", "quality", "observation", "revision")
        val v = ObservationSegment(uuid(text(j, "id")), habitId, uuid(text(j, "deviceId")), uuid(text(j, "bootSessionId")),
            stamp(j, "startUtc"), stamp(j, "endUtc"), number(j, "startElapsedMillis"), number(j, "endElapsedMillis"),
            enum(text(j, "observation")), enum(text(j, "quality")), text(j, "collectorVersion"))
        if (v.startElapsedMillis < 0 || v.endElapsedMillis < v.startElapsedMillis) throw ClientFailure("INVALID_RESPONSE")
        return v
    }
    fun gap(j: JSONObject, habitId: String = uuid(text(j, "habitId"))): ObservationGap {
        only(j, "id", "habitId", "startUtc", "endUtc", "reason", "revision")
        return ObservationGap(uuid(text(j, "id")), habitId, stamp(j, "startUtc"), optional(j, "endUtc")?.let(Instant::parse), enum(text(j, "reason")))
    }

    fun <T> computed(j: JSONObject, read: (Any) -> T): Computed<T> = when (text(j, "state")) {
        "ready" -> Computed.Ready(read(j.get("value")))
        "unavailable" -> Computed.Unavailable(enum(text(j, "reason")))
        else -> throw ClientFailure("UNSUPPORTED_VERSION")
    }
    fun actual(j: JSONObject): ActualValue = when (text(j, "state")) {
        "known" -> ActualValue.Known(decimal(j.get("value")), enum(text(j, "source")))
        "partial" -> ActualValue.Partial(decimal(j.get("observedValue")), enum(text(j, "source")))
        "unknown" -> ActualValue.Unknown(enum(text(j, "reason")))
        else -> throw ClientFailure("UNSUPPORTED_VERSION")
    }
    fun user(j: JSONObject, relation: FriendRelation? = null, requestId: String? = null) =
        FriendSummary(uuid(text(j, "accountId")), text(j, "displayName"), null, relation, requestId)
    fun snapshot(j: JSONObject): FriendActivityUiState {
        val range = range(j.getJSONObject("range"))
        val days = java.time.temporal.ChronoUnit.DAYS.between(range.fromInclusive, range.toExclusive)
        if (days !in 1..366) throw ClientFailure("INVALID_RESPONSE")
        val habits = objects(j.getJSONArray("habits")).map { habit(it) }
        if (habits.map { it.id }.distinct().size != habits.size) throw ClientFailure("INVALID_RESPONSE")
        val cells = objects(j.getJSONArray("cells")).map { c ->
            val habitId = uuid(text(c, "habitId"))
            if (habits.none { it.id == habitId }) throw ClientFailure("INVALID_RESPONSE")
            DayCell(habitId, day(c.getJSONObject("day")), enum(text(c, "planStatus")), c.optJSONObject("goal")?.let { goal(it) },
                actual(c.getJSONObject("actual")), computed(c.getJSONObject("usagePercent")) { decimal(it, true) },
                computed(c.getJSONObject("score")) { decimal(it) },
                computed(c.getJSONObject("emotion")) { enum<Emotion>(it as String) },
                computed(c.getJSONObject("streak")) { (it as Number).toInt() }, false, false)
        }
        val summary = j.getJSONObject("summary")
        val curves = objects(summary.getJSONArray("curves")).map { c ->
            val unit = c.getJSONObject("unit")
            CumulativeCurve(optional(c, "habitId"), ValueUnit(text(unit, "code"), text(unit, "label")),
                objects(c.getJSONArray("points")).map { CurvePoint(day(it.getJSONObject("day")),
                    computed(it.getJSONObject("cumulative")) { n -> decimal(n, true) }) })
        }
        val stats = PeriodSummary(number(summary, "plannedCells").toInt(), number(summary, "knownCells").toInt(),
            computed(summary.getJSONObject("scorePercent")) { decimal(it) }, curves)
        val intervals = objects(j.getJSONArray("intervals")).map { IntervalView(uuid(text(it, "id")), uuid(text(it, "habitId")),
            stamp(it, "startUtc"), stamp(it, "endUtc"), number(it, "durationMillis"), enum(text(it, "quality"))) }
        val gaps = objects(j.getJSONArray("gaps")).map { gap(it) }
        fun validDay(day: DayKey) = day.zoneId == range.zoneId && day.date >= range.fromInclusive && day.date < range.toExclusive
        if (cells.any { cell -> !validDay(cell.day) || (cell.goal?.let { goal ->
                goal.habitId != cell.habitId ||
                    if (habits.first { it.id == cell.habitId }.collectionMode == CollectionMode.AUTOMATIC_SHORTS)
                        goal.requestedFrom.zoneId != cell.day.zoneId || goal.requestedFrom.date > cell.day.date
                    else goal.requestedFrom != cell.day
            } == true) } ||
            cells.map { it.habitId to it.day }.distinct().size != cells.size ||
            intervals.any { it.habitId !in habits.map(Habit::id) || it.durationMillis < 0 } ||
            gaps.any { it.habitId !in habits.map(Habit::id) } ||
            stats.knownCells !in 0..stats.plannedCells || stats.plannedCells > habits.size * days ||
            curves.any { c -> (c.habitId != null && habits.none { it.id == c.habitId }) || c.points.any { !validDay(it.day) } })
            throw ClientFailure("INVALID_RESPONSE")
        return FriendActivityUiState(user(j.getJSONObject("owner"), FriendRelation.ACCEPTED), habits,
            HistoryUiState(HistoryPeriod.WEEK, range, cells, stats), intervals, gaps)
    }
    fun objects(array: JSONArray): List<JSONObject> = (0 until array.length()).map { array.getJSONObject(it) }
}

/** Explicit social commands only. Bodies contain neither owner IDs nor private credentials. */
object SocialWire {
    fun discoverability(enabled: Boolean) = JSONObject().put("enabled",enabled)
    fun validateSharing(grant: SharingGrantDraft): SharingHistoryScope.TimeWindow {
        WireCodec.uuid(grant.friendAccountId)
        val window = grant.historyScope as? SharingHistoryScope.TimeWindow ?: throw ClientFailure("VALIDATION")
        val end = window.toExclusive ?: throw ClientFailure("VALIDATION")
        if (end <= window.fromInclusive) throw ClientFailure("VALIDATION")
        return window
    }
    fun sharing(grant: SharingGrantDraft, serverShortsId: String?, revision: String?): JSONObject {
        val window = validateSharing(grant)
        return JSONObject().put("habitIds",JSONArray(grant.habitIds.sorted().map { WireCodec.uuid(SyncWire.wireHabit(it,serverShortsId)) }))
            .put("historyScope",JSONObject().put("kind","time_window").put("fromInclusive",window.fromInclusive.toString()).put("toExclusive",window.toExclusive!!.toString()))
            .put("expectedRevision",revision ?: JSONObject.NULL)
    }
}

object SyncWire {
    fun localHabit(serverId: String, serverShortsId: String?): String =
        if (serverId == serverShortsId) ChronaRepository.SHORTS_ID else serverId
    fun wireHabit(localId: String, serverShortsId: String?): String = if (localId == ChronaRepository.SHORTS_ID)
        serverShortsId ?: throw ClientFailure("SHORTS_MAPPING_REQUIRED") else localId
    fun bootId(deviceId: String, localBoot: String): String = runCatching { WireCodec.uuid(localBoot) }.getOrElse {
        UUID.nameUUIDFromBytes("chrona.boot:$deviceId:$localBoot".toByteArray(Charsets.UTF_8)).toString()
    }
    /** Called exactly once before first network attempt; persisted body must be reused byte-for-byte. */
    fun freeze(row: OutboxRow, deviceId: String, serverShortsId: String?, revision: String?): String {
        val p = WireCodec.clean(JSONObject(row.payload))
        if (p.has("source") || p.has("ownerId") || p.has("accountId")) throw ClientFailure("SOURCE_NOT_WRITABLE")
        if (row.entityType == "habit" && row.entityId == ChronaRepository.SHORTS_ID) throw ClientFailure("SOURCE_NOT_WRITABLE")
        if (row.entityType == "manual_entry" && WireCodec.text(p, "habitId") == ChronaRepository.SHORTS_ID)
            throw ClientFailure("SOURCE_NOT_WRITABLE")
        if (p.has("habitId")) p.put("habitId", wireHabit(WireCodec.text(p, "habitId"), serverShortsId))
        if (p.has("bootSessionId")) p.put("bootSessionId", bootId(WireCodec.text(p, "deviceId"), WireCodec.text(p, "bootSessionId")))
        val op = JSONObject().put("operationId", row.operationId).put("entityType", row.entityType)
            .put("entityId", row.entityId).put("kind", row.kind).put("baseRevision", revision ?: JSONObject.NULL).put("payload", p)
        return WireCodec.canonical(JSONObject().put("deviceId", deviceId).put("operations", JSONArray().put(op)))
    }
    fun binding(oldOrigin: String?, oldAccount: String?, origin: String, account: String) {
        if (oldAccount != null && (oldAccount != account || oldOrigin != origin)) throw ClientFailure("ACCOUNT_BINDING_CONFLICT")
    }
}
