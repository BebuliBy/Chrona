package by.bebuliby.chrona.backend

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.*

class ScoringTest {
    @Test fun `score near exact boundaries never rounds an incomplete cell to one or tiny score to zero`() {
        val almost = BigDecimal("0.99999999999999999999999999999999999999999999999999")
        val justOver = BigDecimal("1.00000000000000000000000000000000000000000000000001")
        assertTrue(Scoring.q(almost,BigDecimal.ONE,false).text("value").toBigDecimal() < BigDecimal.ONE)
        assertTrue(Scoring.q(justOver,BigDecimal.ONE,true).text("value").toBigDecimal() < BigDecimal.ONE)
        assertTrue(Scoring.q(BigDecimal("0.00000000000000000000000000000000000000000000000001"),BigDecimal.ONE,false).text("value").toBigDecimal() > BigDecimal.ZERO)
    }
    @Test fun `all emotion boundaries use exact comparisons not rounded percents`() {
        val minimum = listOf("0" to "tears","24.99999999999999999999999999999" to "tears","25" to "very_sad","49.999" to "very_sad",
            "50" to "sad","74.999" to "sad","75" to "neutral","99.999" to "neutral","100" to "smile","149.999" to "smile",
            "150" to "big_smile","199.999" to "big_smile","200" to "stars","9999" to "stars")
        val maximum = listOf("0" to "stars","0.0001" to "big_smile","50" to "big_smile","50.0001" to "smile","100" to "smile",
            "100.0001" to "neutral","150" to "neutral","150.0001" to "sad","200" to "sad","200.0001" to "very_sad","400" to "very_sad","400.0001" to "tears")
        for ((a,e) in minimum) assertEquals(e,Scoring.emotion(a.toBigDecimal(),BigDecimal(100),false).text("value"),a)
        for ((a,e) in maximum) assertEquals(e,Scoring.emotion(a.toBigDecimal(),BigDecimal(100),true).text("value"),a)
    }
    @Test fun `mean cap no compensation and plus twenty seven times equals 140`() {
        val over = Scoring.q(BigDecimal(200),BigDecimal(100),false).text("value").toBigDecimal()
        val failed = Scoring.q(BigDecimal.ZERO,BigDecimal(100),false).text("value").toBigDecimal()
        assertEquals(0,Scoring.meanPercent(listOf(over,failed)).text("value").toBigDecimal().compareTo(BigDecimal(50)))
        val delta = Scoring.deviation(BigDecimal(120),BigDecimal(100),false,false).text("value").toBigDecimal()
        assertEquals(BigDecimal(140),delta.multiply(BigDecimal(7)))
        assertEquals("0.2",Scoring.deviation(BigDecimal(120),BigDecimal(100),false,true).text("value"))
        assertEquals("0.5",Scoring.q(BigDecimal(150),BigDecimal(100),true).text("value"))
    }
    @Test fun `zero limit known zero not unknown and undefined branches do not divide by zero`() {
        assertEquals("1",Scoring.q(BigDecimal.ZERO,BigDecimal.ZERO,true).text("value"))
        assertEquals("0",Scoring.q(BigDecimal("0.00001"),BigDecimal.ZERO,true).text("value"))
        assertEquals("unknown_actual",Scoring.q(null,BigDecimal.ZERO,true).text("reason"))
        assertEquals("zero_limit_curve_unresolved",Scoring.deviation(BigDecimal.ZERO,BigDecimal.ZERO,true,true).text("reason"))
        assertEquals("zero_limit_emotion_unresolved",Scoring.emotion(BigDecimal.ZERO,BigDecimal.ZERO,true).text("reason"))
        assertEquals("not_planned",Scoring.meanPercent(emptyList()).text("reason"))
        assertEquals("partial_coverage",Scoring.meanPercent(null).text("reason"))
    }
    @Test fun `DECIMAL128 fractional division is explicit and raw inputs unchanged`() {
        val raw = BigDecimal("1.000000000000000000000000000000000000001")
        val q = Scoring.q(raw,BigDecimal(3),false).text("value")
        assertEquals(34,q.count { it.isDigit() } - 1) // leading zero is not a significant digit
        assertEquals(BigDecimal("1.000000000000000000000000000000000000001"),raw)
    }
}
