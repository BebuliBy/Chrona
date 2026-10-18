package by.bebuliby.chrona.backend

import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import java.util.UUID

val wireJson = Json { isLenient = false; ignoreUnknownKeys = false }
fun obj(vararg fields: Pair<String, JsonElement?>): JsonObject = JsonObject(fields.filter { it.second != null }.associate { it.first to it.second!! })
fun str(value: String) = JsonPrimitive(value)
fun num(value: Number) = JsonPrimitive(value)
fun bool(value: Boolean) = JsonPrimitive(value)
fun arr(values: Iterable<JsonElement>) = JsonArray(values.toList())
fun parseObject(text: String): JsonObject = try { wireJson.parseToJsonElement(text).jsonObject } catch (_: Exception) { fail(400, "VALIDATION") }
class Fault(val status: Int, val code: String) : RuntimeException(code)
fun fail(status: Int, code: String): Nothing = throw Fault(status, code)
fun requireWire(value: Boolean, code: String = "VALIDATION", status: Int = 400) { if (!value) fail(status, code) }
fun JsonObject.only(vararg keys: String) { requireWire(this.keys.all { it in keys }) }
fun JsonObject.text(key: String): String {
    val p = this[key] as? JsonPrimitive ?: fail(400, "VALIDATION")
    requireWire(p.isString)
    return p.content
}
fun JsonObject.optionalText(key: String): String? = if (this[key] == null || this[key] == JsonNull) null else text(key)
fun JsonObject.objectAt(key: String) = this[key] as? JsonObject ?: fail(400, "VALIDATION")
fun JsonObject.arrayAt(key: String) = this[key] as? JsonArray ?: fail(400, "VALIDATION")
fun JsonObject.longAt(key: String): Long {
    val p = this[key] as? JsonPrimitive ?: fail(400, "VALIDATION")
    requireWire(!p.isString)
    return p.longOrNull ?: fail(400, "VALIDATION")
}
fun JsonObject.booleanAt(key: String): Boolean {
    val p = this[key] as? JsonPrimitive ?: fail(400, "VALIDATION")
    requireWire(!p.isString)
    return p.booleanOrNull ?: fail(400, "VALIDATION")
}
fun uuid(value: String): String {
    requireWire(value.matches(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")))
    return UUID.fromString(value).toString()
}
fun id() = UUID.randomUUID().toString()
fun timestamp(value: String): Instant {
    requireWire(value.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,3})?Z")))
    return try { Instant.parse(value) } catch (_: Exception) { fail(400, "VALIDATION") }
}
fun day(value: JsonObject) {
    value.only("date", "zoneId")
    try { LocalDate.parse(value.text("date")); zone(value.text("zoneId")) } catch (_: Exception) { fail(400, "VALIDATION") }
}
fun zone(value: String): ZoneId {
    requireWire(value in ZoneId.getAvailableZoneIds())
    return ZoneId.of(value)
}
fun decimal(value: JsonElement?): BigDecimal {
    val p = value as? JsonPrimitive ?: fail(400, "VALIDATION")
    // Transport resource limit, NOT a goal/actual product cap. No exponent/locale/float.
    requireWire(p.isString && p.content.length <= 512 && p.content.matches(Regex("(0|[1-9][0-9]*)(\\.[0-9]+)?")))
    return p.content.toBigDecimal()
}
fun canonical(e: JsonElement): String = when (e) {
    is JsonObject -> e.keys.sorted().joinToString(",", "{", "}") { str(it).toString() + ":" + canonical(e.getValue(it)) }
    is JsonArray -> e.joinToString(",", "[", "]") { canonical(it) }
    else -> e.toString()
}
fun hash(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
fun constantEquals(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
private val random = SecureRandom()
fun secret(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { random.nextBytes(it) })
fun unavailable(reason: String) = obj("state" to str("unavailable"), "reason" to str(reason))
