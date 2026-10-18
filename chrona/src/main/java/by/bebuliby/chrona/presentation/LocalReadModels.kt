package by.bebuliby.chrona.presentation

import by.bebuliby.chrona.domain.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId

/** Shorts limits are effective-from in the same explicit zone; manual goals remain exact-day. */
object LocalReadModels {
    fun cell(habit: Habit, day: DayKey, facts: LocalFacts, localDeviceId: String? = null): DayCell {
        val applicable = facts.goals.filter {
            it.habitId == habit.id && it.requestedFrom.zoneId == day.zoneId &&
                if (habit.collectionMode == CollectionMode.AUTOMATIC_SHORTS) it.requestedFrom.date <= day.date
                else it.requestedFrom.date == day.date
        }
        val latestEffectiveDate = applicable.maxOfOrNull { it.requestedFrom.date }
        // Most recent effective date wins, then the established local insertion order for that date.
        // Never rank revisions by random UUID, wall clock, or overwrite/copy a historical entity.
        val goal = applicable.lastOrNull { it.requestedFrom.date == latestEffectiveDate }
        val entry = facts.entries.find { it.habitId == habit.id && it.day == day }
        val actual = if (habit.collectionMode == CollectionMode.MANUAL) {
            entry?.let { ActualValue.Known(it.value, FactSource.MANUAL) }
                ?: ActualValue.Unknown(UnavailableReason.UNKNOWN_ACTUAL)
        } else observedAmount(habit.id, day, facts, localDeviceId)
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

    /** Known means the recorded counter value, NOT proof that all hours were observed. */
    private fun observedAmount(habitId: String, day: DayKey, facts: LocalFacts, localDeviceId: String?): ActualValue {
        if (day.date > java.time.LocalDate.now(ZoneId.of(day.zoneId))) return ActualValue.Unknown(UnavailableReason.UNKNOWN_ACTUAL)
        val (start, end) = bounds(day)
        val devices = (facts.intervals.filter { it.habitId == habitId && overlaps(it.startUtc, it.endUtc, start, end) }.map { it.deviceId } +
            facts.segments.filter { it.habitId == habitId && overlaps(it.startUtc, it.endUtc, start, end) }.map { it.deviceId }).toMutableSet()
        val open = facts.checkpoint?.let { it.observation == ScreenObservation.SHORTS &&
            it.startWall < end.toEpochMilli() && it.lastWall >= start.toEpochMilli() } == true
        if (open && localDeviceId != null) devices += localDeviceId
        if (devices.size > 1 || (open && localDeviceId == null && devices.isNotEmpty()))
            return ActualValue.Unknown(UnavailableReason.MULTI_DEVICE_UNRESOLVED)
        var value = BigDecimal.ZERO
        var ambiguous = false
        fun add(a: Instant, b: Instant, duration: Long, quality: IntervalQuality) {
            if (!overlaps(a, b, start, end)) return
            if (a >= start && a < end && b >= start && b <= end) {
                value += BigDecimal(duration.coerceAtLeast(0)); return
            }
            if (quality != IntervalQuality.OBSERVED || b <= a) { ambiguous = true; return }
            // Partition an existing measured millisecond budget by UTC overlap in this explicit zone.
            // Cumulative floor differences preserve the total across midnight; never grow the budget.
            val span = BigDecimal(java.time.Duration.between(a, b).toMillis())
            if (span.signum() <= 0) { ambiguous = true; return }
            fun cumulative(boundary: Instant): BigDecimal {
                val offset = java.time.Duration.between(a, boundary.coerceIn(a, b)).toMillis()
                return BigDecimal(duration.coerceAtLeast(0)).multiply(BigDecimal(offset)).divide(span, 0, RoundingMode.FLOOR)
            }
            value += cumulative(end) - cumulative(start)
        }
        facts.intervals.filter { it.habitId == habitId }.forEach {
            add(it.startUtc, it.endUtc, it.durationMillis, it.quality)
        }
        val cp = facts.checkpoint
        if (cp?.observation == ScreenObservation.SHORTS) {
            add(Instant.ofEpochMilli(cp.startWall), Instant.ofEpochMilli(cp.lastWall),
                (cp.lastElapsed - cp.startElapsed).coerceAtLeast(0), IntervalQuality.OBSERVED)
        }
        if (ambiguous) return ActualValue.Unknown(UnavailableReason.DAY_POLICY_UNRESOLVED)
        // Empty automatic counter is 0 recorded milliseconds. Missing manual entries remain Unknown.
        return ActualValue.Known(value, FactSource.COLLECTOR)
    }

    private fun bounds(day: DayKey): Pair<Instant, Instant> {
        val zone = ZoneId.of(day.zoneId)
        return day.date.atStartOfDay(zone).toInstant() to day.date.plusDays(1).atStartOfDay(zone).toInstant()
    }
    private fun overlaps(a: Instant, b: Instant, start: Instant, end: Instant): Boolean =
        minOf(a, b) < end && maxOf(a, b) >= start
}
