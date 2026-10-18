package by.bebuliby.chrona.presentation

import by.bebuliby.chrona.domain.*
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

/** Exact-day requests resolve only that day. No recurrence, goal inheritance or streak policy. */
object LocalReadModels {
    fun cell(habit: Habit, day: DayKey, facts: LocalFacts, localDeviceId: String? = null): DayCell {
        val goal = facts.goals.filter { it.habitId == habit.id && it.requestedFrom == day }
            .lastOrNull() // immutable rows arrive in local insertion order; no random UUID tie-break
        val entry = facts.entries.find { it.habitId == habit.id && it.day == day }
        val actual = if (habit.collectionMode == CollectionMode.MANUAL) {
            entry?.let { ActualValue.Known(it.value, FactSource.MANUAL) }
                ?: ActualValue.Unknown(UnavailableReason.UNKNOWN_ACTUAL)
        } else observedPartial(habit.id, day, facts, localDeviceId)
        return Scoring.cell(habit, day, goal, actual)
    }

    fun cells(range: DayRange, facts: LocalFacts, localDeviceId: String? = null): List<DayCell> {
        require(range.toExclusive >= range.fromInclusive)
        val days = java.time.temporal.ChronoUnit.DAYS.between(range.fromInclusive, range.toExclusive)
        require(days <= 366) { "History range exceeds one year" }
        return (0 until days).flatMap { offset ->
            val day = DayKey(range.fromInclusive.plusDays(offset), range.zoneId)
            facts.habits.map { cell(it, day, facts, localDeviceId) }
        }
    }

    fun intervals(habitId: String, day: DayKey, facts: LocalFacts): List<IntervalView> {
        val (start, end) = bounds(day)
        return facts.intervals.filter { it.habitId == habitId && overlaps(it.startUtc, it.endUtc, start, end) }
            .map { IntervalView(it.id, it.habitId, it.startUtc, it.endUtc, it.durationMillis, it.quality) }
        // Only closed immutable intervals here; partial open amount is labelled in ActualValue.
    }

    fun gaps(habitId: String, day: DayKey, facts: LocalFacts): List<ObservationGap> {
        val (start, end) = bounds(day)
        return facts.gaps.filter { it.habitId == habitId && overlaps(it.startUtc, it.endUtc ?: Instant.MAX, start, end) }
    }

    private fun observedPartial(habitId: String, day: DayKey, facts: LocalFacts, localDeviceId: String?): ActualValue {
        val (start, end) = bounds(day)
        val devices = (facts.intervals.filter { it.habitId == habitId && overlaps(it.startUtc, it.endUtc, start, end) }.map { it.deviceId } +
            facts.segments.filter { it.habitId == habitId && overlaps(it.startUtc, it.endUtc, start, end) }.map { it.deviceId }).toMutableSet()
        val open = facts.checkpoint?.let { it.observation == ScreenObservation.SHORTS &&
            it.startWall < end.toEpochMilli() && it.lastWall >= start.toEpochMilli() } == true
        if (open && localDeviceId != null) devices += localDeviceId
        if (devices.size > 1 || (open && localDeviceId == null && devices.isNotEmpty()))
            return ActualValue.Unknown(UnavailableReason.MULTI_DEVICE_UNRESOLVED)
        val intervals = facts.intervals.filter {
            it.habitId == habitId && it.quality == IntervalQuality.OBSERVED &&
                it.startUtc >= start && it.endUtc < end && it.endUtc >= it.startUtc
        }
        var value = intervals.fold(BigDecimal.ZERO) { total, interval -> total + BigDecimal(interval.durationMillis) }
        val cp = facts.checkpoint
        var hasOpen = false
        if (cp?.observation == ScreenObservation.SHORTS && cp.startWall >= start.toEpochMilli() &&
            cp.lastWall < end.toEpochMilli() && cp.lastWall >= cp.startWall) {
            value += BigDecimal((cp.lastElapsed - cp.startElapsed).coerceAtLeast(0))
            hasOpen = true
        }
        val evidence = facts.segments.any { it.habitId == habitId && overlaps(it.startUtc, it.endUtc, start, end) }
        return if (intervals.isNotEmpty() || hasOpen || evidence) ActualValue.Partial(value, FactSource.COLLECTOR)
        else ActualValue.Unknown(UnavailableReason.UNKNOWN_ACTUAL)
    }

    private fun bounds(day: DayKey): Pair<Instant, Instant> {
        val zone = ZoneId.of(day.zoneId)
        return day.date.atStartOfDay(zone).toInstant() to day.date.plusDays(1).atStartOfDay(zone).toInstant()
    }
    private fun overlaps(a: Instant, b: Instant, start: Instant, end: Instant): Boolean =
        minOf(a, b) < end && maxOf(a, b) >= start
}
