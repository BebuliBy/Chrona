package by.bebuliby.chrona.backend

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlinx.serialization.json.*

/** Pure agreed arithmetic for explicitly resolved cells only, NOT a schedule/coverage policy.
 * DECIMAL128 (34 significant digits, HALF_EVEN) for derived ratios/means/curves;
 * q uses 34 digits DOWN and exact comparisons (same as core), so a tiny deficit
 * cannot round to success. Exact cross-products for emotion boundaries.
 * Raw facts are never rounded or capped by these functions.
 */
object Scoring {
    private val mc = MathContext.DECIMAL128
    private val scoreContext = MathContext(34,RoundingMode.DOWN)
    private val zero = BigDecimal.ZERO
    private val one = BigDecimal.ONE
    private fun ready(value: BigDecimal) = obj("state" to str("ready"), "value" to str(value.toPlainString()))
    fun q(a: BigDecimal?, g: BigDecimal, atMost: Boolean): JsonObject {
        require(g.signum() >= 0 && (a == null || a.signum() >= 0) && (atMost || g.signum() > 0))
        if (a == null) return unavailable("unknown_actual")
        if (g.signum() == 0) return ready(if (a.signum() == 0) one else zero)
        val score = if (!atMost) {
            if (a >= g) one else a.divide(g,scoreContext)
        } else when {
            a <= g -> one
            a >= g.multiply(BigDecimal(2)) -> zero
            else -> (g.multiply(BigDecimal(2)) - a).divide(g,scoreContext)
        }
        return ready(score)
    }
    fun emotion(a: BigDecimal?, g: BigDecimal, atMost: Boolean): JsonObject {
        if (a == null) return unavailable("unknown_actual")
        if (g.signum() == 0) return unavailable("zero_limit_emotion_unresolved")
        val p = a.multiply(BigDecimal(100))
        fun below(n: Int) = p < g.multiply(BigDecimal(n))
        fun atMost(n: Int) = p <= g.multiply(BigDecimal(n))
        val emotion = if (!atMost) when {
            below(25) -> "tears"; below(50) -> "very_sad"; below(75) -> "sad"
            below(100) -> "neutral"; below(150) -> "smile"; below(200) -> "big_smile"; else -> "stars"
        } else when {
            a.signum() == 0 -> "stars"; atMost(50) -> "big_smile"; atMost(100) -> "smile"
            atMost(150) -> "neutral"; atMost(200) -> "sad"; atMost(400) -> "very_sad"; else -> "tears"
        }
        return obj("state" to str("ready"), "value" to str(emotion))
    }
    fun deviation(a: BigDecimal?, g: BigDecimal, atMost: Boolean, normalized: Boolean): JsonObject {
        if (a == null) return unavailable("unknown_actual")
        if (normalized && g.signum() == 0) return unavailable("zero_limit_curve_unresolved")
        val delta = if (atMost) g - a else a - g
        return ready(if (normalized) delta.divide(g,mc) else delta)
    }
    fun meanPercent(scores: List<BigDecimal>?): JsonObject {
        if (scores == null) return unavailable("partial_coverage")
        if (scores.isEmpty()) return unavailable("not_planned")
        require(scores.all { it in zero..one })
        return ready(scores.fold(zero,BigDecimal::add).multiply(BigDecimal(100)).divide(BigDecimal(scores.size),mc))
    }
}
