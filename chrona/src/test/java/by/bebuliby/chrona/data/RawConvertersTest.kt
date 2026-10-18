package by.bebuliby.chrona.data

import by.bebuliby.chrona.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class RawConvertersTest {
    private val converter = RawConverters()

    @Test fun decimalTextRoundTripRetainsPrecisionAndOvergoal() {
        val value = "12345678901234567890.12345678901234567890".toBigDecimal()
        assertEquals(value, converter.decimal(converter.decimal(value)))
        assertEquals("100000000000000000000", converter.decimal("1E20".toBigDecimal()))
    }

    @Test fun explicitDayZoneAndUnitLabelsRoundTripWithoutLocaleChanges() {
        val day = DayKey(LocalDate.of(2026, 10, 18), "Europe/Minsk")
        assertEquals(day, converter.day(converter.day(day)))
        val unit = ValueUnit("custom", "страниц | разделов / день")
        assertEquals(unit, converter.unit(converter.unit(unit)))
    }

    @Test fun utcInstantAndNullRoundTrip() {
        val instant = Instant.parse("2026-10-18T01:02:03.456Z")
        assertEquals(instant, converter.instant(converter.instant(instant)))
        assertNull(converter.decimal(null as String?))
        assertNull(converter.instant(null as Instant?))
    }
}
