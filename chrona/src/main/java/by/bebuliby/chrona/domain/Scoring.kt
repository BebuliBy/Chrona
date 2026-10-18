package by.bebuliby.chrona.domain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** DECIMAL128 for derived division only. Emotion boundaries use exact multiplication. */
object Scoring {
    private val mc = MathContext.DECIMAL128
    private val scoreContext = MathContext(34, RoundingMode.DOWN)
    private val zero = BigDecimal.ZERO
    private val one = BigDecimal.ONE
    private val hundred = BigDecimal("100")

    fun score(actual: Decimal, goal: Decimal, direction: GoalDirection): Decimal {
        require(actual.signum() >= 0 && goal.signum() >= 0)
        require(direction == GoalDirection.AT_MOST || goal.signum() > 0)
        if (goal.signum() == 0) return if (actual.signum() == 0) one else zero
        return when (direction) {
            GoalDirection.AT_LEAST -> if (actual >= goal) one else actual.divide(goal, scoreContext)
            GoalDirection.AT_MOST -> when {
                actual <= goal -> one
                actual >= goal.multiply(BigDecimal("2")) -> zero
                // Divide the positive residual, avoiding cancellation/rounding a tiny violation to success.
                else -> (goal.multiply(BigDecimal("2")) - actual).divide(goal, scoreContext)
            }
        }
    }

    fun emotion(actual: Decimal, goal: Decimal, direction: GoalDirection): Computed<Emotion> {
        require(actual.signum() >= 0 && goal.signum() >= 0)
        if (goal.signum() == 0) return Computed.Unavailable(UnavailableReason.ZERO_LIMIT_EMOTION_UNRESOLVED)
        val a = actual.multiply(hundred)
        fun below(p: String) = a < goal.multiply(BigDecimal(p))
        fun atMost(p: String) = a <= goal.multiply(BigDecimal(p))
        return Computed.Ready(when (direction) {
            GoalDirection.AT_LEAST -> when {
                below("25") -> Emotion.TEARS
                below("50") -> Emotion.VERY_SAD
                below("75") -> Emotion.SAD
                below("100") -> Emotion.NEUTRAL
                below("150") -> Emotion.SMILE
                below("200") -> Emotion.BIG_SMILE
                else -> Emotion.STARS
            }
            GoalDirection.AT_MOST -> when {
                actual.signum() == 0 -> Emotion.STARS
                atMost("50") -> Emotion.BIG_SMILE
                atMost("100") -> Emotion.SMILE
                atMost("150") -> Emotion.NEUTRAL
                atMost("200") -> Emotion.SAD
                atMost("400") -> Emotion.VERY_SAD
                else -> Emotion.TEARS
            }
        })
    }

    fun cell(habit: Habit, day: DayKey, goal: GoalRevision?, actual: ActualValue): DayCell {
        val missing = when {
            actual is ActualValue.Unknown -> actual.reason
            actual is ActualValue.Partial -> UnavailableReason.PARTIAL_COVERAGE
            goal == null -> UnavailableReason.GOAL_UNRESOLVED
            else -> null
        }
        val a = (actual as? ActualValue.Known)?.value
        val usage = if (missing != null) Computed.Unavailable(missing)
            else if (goal!!.amount.signum() == 0) Computed.Unavailable(UnavailableReason.ZERO_LIMIT_RATIO)
            else Computed.Ready(a!!.multiply(hundred).divide(goal.amount, mc))
        return DayCell(
            habit.id, day, if (goal == null) PlanStatus.UNRESOLVED else PlanStatus.PLANNED,
            goal, actual, usage,
            if (missing != null) Computed.Unavailable(missing) else Computed.Ready(score(a!!, goal!!.amount, habit.direction)),
            if (missing != null) Computed.Unavailable(missing) else emotion(a!!, goal!!.amount, habit.direction),
            Computed.Unavailable(UnavailableReason.STREAK_RULES_UNRESOLVED),
            habit.collectionMode == CollectionMode.MANUAL && habit.archivedAt == null,
            habit.archivedAt == null,
        )
    }

    fun deviation(actual: Decimal, goal: Decimal, direction: GoalDirection, normalized: Boolean): Computed<Decimal> {
        val difference = if (direction == GoalDirection.AT_LEAST) actual - goal else goal - actual
        return if (normalized && goal.signum() == 0)
            Computed.Unavailable(UnavailableReason.ZERO_LIMIT_CURVE_UNRESOLVED)
        else Computed.Ready(if (normalized) difference.divide(goal, mc) else difference)
    }

    fun summary(habits: List<Habit>, cells: List<DayCell>): PeriodSummary {
        val planned = cells.filter { it.planStatus == PlanStatus.PLANNED }
        val unresolved = cells.any { it.planStatus == PlanStatus.UNRESOLVED }
        val unavailable = when {
            unresolved -> UnavailableReason.SCHEDULE_UNRESOLVED
            planned.isEmpty() -> UnavailableReason.NOT_PLANNED
            planned.any { it.score !is Computed.Ready } -> UnavailableReason.PARTIAL_COVERAGE
            else -> null
        }
        val total = planned.mapNotNull { (it.score as? Computed.Ready)?.value }.fold(zero, Decimal::add)
        val aggregate: Computed<Decimal> = if (unavailable != null) Computed.Unavailable(unavailable)
            else Computed.Ready(total.multiply(hundred).divide(BigDecimal(planned.size), mc))
        val dates = cells.map { it.day }.distinct().sortedBy { it.date }
        fun curve(habit: Habit?): CumulativeCurve {
            var sum = zero
            var blocked: UnavailableReason? = null
            val points = dates.map { day ->
                val batch = cells.filter { it.day == day && (habit == null || it.habitId == habit.id) }
                for (cell in batch) {
                    if (cell.planStatus == PlanStatus.NOT_PLANNED) continue
                    val a = cell.actual as? ActualValue.Known
                    val g = cell.goal
                    val h = habits.find { it.id == cell.habitId }
                    if (cell.planStatus == PlanStatus.UNRESOLVED) blocked = blocked ?: UnavailableReason.SCHEDULE_UNRESOLVED
                    else if (a == null || g == null || h == null) blocked = blocked ?: UnavailableReason.PARTIAL_COVERAGE
                    else when (val d = deviation(a.value, g.amount, h.direction, habit == null)) {
                        is Computed.Ready -> sum += d.value
                        is Computed.Unavailable -> blocked = blocked ?: d.reason
                    }
                }
                CurvePoint(day, blocked?.let { Computed.Unavailable(it) } ?: Computed.Ready(sum))
            }
            return CumulativeCurve(habit?.id, habit?.unit ?: ValueUnit("plan_shares", "доли плана"), points)
        }
        return PeriodSummary(planned.size, planned.count { it.actual is ActualValue.Known }, aggregate,
            listOf(curve(null)) + habits.map { curve(it) })
    }
}

class CoreFailure(val code: String, override val message: String) : IllegalArgumentException(message)

/** Used by both the ViewModel boundary and the actual repository write transaction. */
object SourcePolicy {
    fun manual(habit: Habit) {
        if (habit.collectionMode != CollectionMode.MANUAL)
            throw CoreFailure("SOURCE_NOT_WRITABLE", "Факт Shorts изменяет только система наблюдения.")
        editable(habit)
    }
    fun editable(habit: Habit) {
        if (habit.archivedAt != null) throw CoreFailure("ARCHIVED", "Привычка в архиве.")
    }
    fun amount(value: Decimal) {
        if (value.signum() < 0) throw CoreFailure("VALIDATION", "Значение не может быть отрицательным.")
        // Resource bound, not a product maximum or a goal cap.
        if (value.precision() > 1000 || value.scale() !in -1000..1000)
            throw CoreFailure("VALIDATION", "Слишком длинное число.")
    }
    fun goal(habit: Habit, value: Decimal) {
        editable(habit); amount(value)
        if (habit.direction == GoalDirection.AT_LEAST && value.signum() == 0)
            throw CoreFailure("VALIDATION", "Для полезной привычки цель должна быть больше нуля.")
    }
}
