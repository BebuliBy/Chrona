package by.bebuliby.chrona.backend

import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate

/** A pure projection of ALREADY authorized facts. No SQL, grants or fallback policies here.
 * Shorts configuration is effective from requestedFrom in the SAME zone until a
 * newer effective day. Manual goals remain exact-day only. Same-day revisions use
 * server append order; no client-clock/UUID tie-break or general recurrence policy.
 */
object ActivityViews {
    private val mc = MathContext.DECIMAL128
    private fun ready(value: BigDecimal) = obj("state" to str("ready"), "value" to str(value.toPlainString()))
    private fun day(date: LocalDate, range: ViewRange) = obj("date" to str(date.toString()), "zoneId" to str(range.zone.id))
    private fun cellKey(habit: String, date: LocalDate) = "$habit|$date"
    private data class Cell(val habit: StoredEntity, val date: LocalDate, val goal: StoredEntity?, val actual: JsonObject,
                            val amount: BigDecimal?, val score: JsonObject, val wire: JsonObject)

    fun snapshot(owner: JsonObject, range: ViewRange, facts: VisibleFacts, isOwner: Boolean, today: LocalDate): JsonObject {
        val habits = facts.habits.sortedBy { it.id }
        val cells = facts.days.flatMap { date -> habits.map { habit -> cell(habit,date,range,facts,isOwner,today) } }
        return obj("owner" to owner, "range" to range.wire(), "habits" to arr(habits.map(::habitDto)),
            "cells" to arr(cells.map { it.wire }), "summary" to summary(habits,cells,facts.days,range),
            "intervals" to arr(facts.intervals), "gaps" to arr(facts.gaps))
    }

    private fun habitDto(h: StoredEntity) = obj("id" to str(h.id), "title" to h.payload["title"], "unit" to h.payload["unit"],
        "direction" to h.payload["direction"], "collectionMode" to h.payload["collectionMode"], "createdAt" to h.payload["createdAt"],
        "archivedAt" to (h.payload["archivedAt"] ?: JsonNull), "revision" to str(h.revision))

    private fun goalDto(g: StoredEntity) = JsonObject(g.payload + ("revision" to str(g.revision)))

    internal fun applicableGoal(habit: StoredEntity, date: LocalDate, zoneId: String, goals: List<StoredEntity>): StoredEntity? {
        val shorts = habit.payload.text("collectionMode") == "automatic_shorts"
        return goals.filter { goal ->
            if (goal.payload.text("habitId") != habit.id) false else {
                val requested = goal.payload.objectAt("requestedFrom")
                val effectiveDate = LocalDate.parse(requested.text("date"))
                requested.text("zoneId") == zoneId && (if (shorts) effectiveDate <= date else effectiveDate == date)
            }
        }.maxWithOrNull(compareBy<StoredEntity>(
            { LocalDate.parse(it.payload.objectAt("requestedFrom").text("date")) },
            { it.revision.toLong() }, // existing deterministic server append order within one effective day
        ))
    }

    private fun cell(h: StoredEntity, date: LocalDate, range: ViewRange, facts: VisibleFacts, isOwner: Boolean, today: LocalDate): Cell {
        val key = day(date,range)
        val fullDay = date in facts.fullDays
        // Inherited configuration is not permission to expose a partially authorized day.
        val goal = if (fullDay) applicableGoal(h,date,range.zone.id,facts.goals) else null
        val manual = h.payload.text("collectionMode") == "manual"
        val actual = if (manual) {
            val entry = facts.entries.singleOrNull { it.payload.text("habitId") == h.id && it.payload["day"] == key }
            when {
                !fullDay -> obj("state" to str("unknown"), "reason" to str("sharing_policy_unresolved"))
                entry == null -> obj("state" to str("unknown"), "reason" to str("unknown_actual"))
                else -> obj("state" to str("known"), "value" to entry.payload["value"], "source" to str("manual"))
            }
        } else observed(h.id,date,range,facts)
        val amount = if (actual.text("state") == "known") decimal(actual["value"]) else null
        val missing = when (actual.text("state")) {
            "unknown" -> actual.text("reason")
            "partial" -> "partial_coverage"
            else -> if (goal == null) "goal_unresolved" else null
        }
        val g = goal?.let { decimal(it.payload["amount"]) }
        val atMost = h.payload.text("direction") == "at_most"
        val score = if (missing != null) unavailable(missing) else Scoring.q(amount,g!!,atMost)
        val usage = when {
            missing != null -> unavailable(missing)
            g!!.signum() == 0 -> unavailable("zero_limit_ratio")
            else -> ready(amount!!.multiply(BigDecimal(100)).divide(g,mc))
        }
        val editable = isOwner && fullDay && h.payload.optionalText("archivedAt") == null
        val wire = obj("habitId" to str(h.id), "day" to key, "planStatus" to str(if (goal == null) "unresolved" else "planned"),
            "goal" to (goal?.let(::goalDto) ?: JsonNull), "actual" to actual, "usagePercent" to usage, "score" to score,
            "emotion" to if (missing != null) unavailable(missing) else Scoring.emotion(amount,g!!,atMost),
            "streak" to unavailable("streak_rules_unresolved"),
            // Unlike a local editor, the server currently accepts actual writes only for today's explicit DayKey.
            "canEditActual" to bool(editable && manual && date == today), "canEditGoal" to bool(editable))
        return Cell(h,date,goal,actual,amount,score,wire)
    }

    private fun observed(habit: String, date: LocalDate, range: ViewRange, facts: VisibleFacts): JsonObject {
        val key = cellKey(habit,date)
        if (key in facts.multiDeviceDays) return obj("state" to str("unknown"), "reason" to str("multi_device_unresolved"))
        val start = date.atStartOfDay(range.zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(range.zone).toInstant()
        val intervals = facts.intervals.filter { v ->
            v.text("habitId") == habit && v.text("quality") == "observed" &&
                timestamp(v.text("startUtc")) >= start && timestamp(v.text("endUtc")) < end &&
                timestamp(v.text("endUtc")) >= timestamp(v.text("startUtc"))
        }
        if (intervals.isNotEmpty() || key in facts.evidenceDays) {
            val value = intervals.fold(BigDecimal.ZERO) { total,v -> total + BigDecimal(v.longAt("durationMillis")) }
            return obj("state" to str("partial"), "observedValue" to str(value.toPlainString()), "source" to str("collector"))
        }
        return obj("state" to str("unknown"), "reason" to str(if (key in facts.ambiguousDays) "day_policy_unresolved" else "unknown_actual"))
    }

    private fun summary(habits: List<StoredEntity>, cells: List<Cell>, dates: List<LocalDate>, range: ViewRange): JsonObject {
        val planned = cells.filter { it.goal != null }
        val reason = when {
            cells.any { it.goal == null } -> "schedule_unresolved"
            planned.isEmpty() -> "not_planned"
            planned.any { it.score.text("state") != "ready" } -> "partial_coverage"
            else -> null
        }
        val score = if (reason != null) unavailable(reason) else Scoring.meanPercent(planned.map { it.score.text("value").toBigDecimal() })
        fun curve(habit: StoredEntity?): JsonObject {
            var sum = BigDecimal.ZERO
            var blocked: String? = null
            val points = dates.map { date ->
                for (cell in cells.filter { it.date == date && (habit == null || it.habit.id == habit.id) }) {
                    when {
                        cell.goal == null -> if (blocked == null) blocked = "schedule_unresolved"
                        cell.amount == null -> if (blocked == null) blocked = "partial_coverage"
                        else -> {
                            val delta = Scoring.deviation(cell.amount,decimal(cell.goal.payload["amount"]),cell.habit.payload.text("direction") == "at_most",habit == null)
                            if (delta.text("state") == "ready") sum += delta.text("value").toBigDecimal()
                            else if (blocked == null) blocked = delta.text("reason")
                        }
                    }
                }
                obj("day" to day(date,range), "cumulative" to (blocked?.let(::unavailable) ?: ready(sum)))
            }
            return obj("habitId" to (habit?.let { str(it.id) } ?: JsonNull),
                "unit" to (habit?.payload?.get("unit") ?: obj("code" to str("plan_shares"), "label" to str("доли плана"))), "points" to arr(points))
        }
        return obj("plannedCells" to num(planned.size), "knownCells" to num(planned.count { it.amount != null }),
            "scorePercent" to score, "curves" to arr(if (habits.isEmpty()) emptyList() else listOf(curve(null)) + habits.map { curve(it) }))
    }
}
