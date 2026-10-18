package by.bebuliby.chrona.backend

import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class TelegramFailure(val category: String, val retryAfterSeconds: Long? = null) : RuntimeException(category)
fun interface TelegramTransport { fun call(method: String, body: JsonObject): JsonElement }

/** Explicit SOCKS proxy: OkHttp's SOCKS route uses an unresolved target address.
 * Java Socket SOCKS5 sends the hostname to the proxy (ATYP=3). No ProxySelector,
 * direct fallback, interceptor or HTTP logging. The local DNS guard is defense in depth.
 */
class SocksTelegramTransport private constructor(
    private val token: String,
    private val endpoint: String,
    proxyHost: String,
    proxyPort: Int,
) : TelegramTransport {
    private val client = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(proxyHost, proxyPort)))
        .dns(object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                if (hostname == URI(endpoint).host) throw UnknownHostException("REMOTE_DNS_REQUIRED")
                return Dns.SYSTEM.lookup(hostname)
            }
        })
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS) // long-poll=5 plus TLS/proxy/network margin
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .build()

    override fun call(method: String, body: JsonObject): JsonElement {
        require(method in setOf("getUpdates", "sendMessage", "answerCallbackQuery"))
        try {
            val request = Request.Builder().url("$endpoint/bot$token/$method")
                .post(canonical(body).toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                val raw = response.body?.byteStream()?.readNBytes(2_097_153) ?: throw TelegramFailure("PROTOCOL")
                if (raw.size > 2_097_152) throw TelegramFailure("PROTOCOL")
                val result = parseObject(raw.toString(Charsets.UTF_8))
                if (response.code == 429 || (result["error_code"] as? JsonPrimitive)?.intOrNull == 429) {
                    val retry = (result["parameters"] as? JsonObject)?.get("retry_after")?.jsonPrimitive?.longOrNull
                    throw TelegramFailure("RATE_LIMITED", (retry ?: 5).coerceIn(1, 3600))
                }
                if (!response.isSuccessful || (result["ok"] as? JsonPrimitive)?.booleanOrNull != true) throw TelegramFailure("REMOTE_ERROR")
                return result["result"] ?: throw TelegramFailure("PROTOCOL")
            }
        } catch (f: TelegramFailure) { throw f }
        catch (_: Exception) { throw TelegramFailure("NETWORK_OR_PROTOCOL") } // NEVER include URL, token, body or cause
    }
    companion object {
        fun production(token: String, proxy: String): SocksTelegramTransport {
            require(token.matches(Regex("[0-9]{5,20}:[A-Za-z0-9_-]{20,100}"))) { "INVALID_TOKEN_FILE" }
            val uri = URI(proxy)
            require(uri.scheme == "socks5h" && uri.host != null && uri.port in 1..65535 && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.isNullOrEmpty()) { "SOCKS5H_REQUIRED" }
            return SocksTelegramTransport(token, "https://api.telegram.org", uri.host, uri.port)
        }
        internal fun fakeProxyTest(proxyPort: Int): SocksTelegramTransport = SocksTelegramTransport(
            "00000:FAKE_TEST_TOKEN_NOT_A_REAL_SECRET", "http://telegram.invalid", "127.0.0.1", proxyPort)
    }
}

/** Store ONLY allowlisted commands and identity fields needed for these use cases.
 * Arbitrary Telegram message content/whole updates are neither stored nor logged.
 */
fun normalizeUpdate(update: JsonObject): JsonObject {
    val updateId = update.longAt("update_id")
    requireWire(updateId >= 0)
    val callback = update["callback_query"] as? JsonObject
    val message = (callback?.get("message") ?: update["message"]) as? JsonObject
    val from = (callback?.get("from") ?: message?.get("from")) as? JsonObject
    val chat = message?.get("chat") as? JsonObject
    val base = obj("updateId" to num(updateId), "command" to str("noop"))
    if (from == null || chat == null || chat.optionalText("type") != "private") return base
    val sender = from.longAt("id"); val chatId = chat.longAt("id")
    if (sender <= 0 || sender != chatId || (from["is_bot"] as? JsonPrimitive)?.booleanOrNull != false) return base
    val name = from.optionalText("first_name")?.filterNot { it.isISOControl() }?.take(120) ?: "Chrona user"
    var args: List<String>
    val callbackId: String?
    if (callback != null) {
        val data = callback.optionalText("data") ?: return base
        val parts = data.split(':')
        if (parts.size != 3 || parts[0] != "login" || parts[1] !in setOf("yes", "no")) return base
        args = listOf(if (parts[1] == "yes") "confirm" else "deny", parts[2])
        callbackId = callback.optionalText("id")?.take(200)
    } else {
        val text = message.optionalText("text") ?: return base
        if (text.length > 400) return base
        args = text.trim().split(Regex("\\s+"))
        val command = args.first().substringBefore('@')
        val allowed = mapOf("/start" to 2, "/friends" to 1, "/requests" to 1, "/request" to 2, "/accept" to 2, "/reject" to 2, "/view" to 5, "/discover" to 2)
        if (allowed[command] != args.size) return base
        args = listOf(command.removePrefix("/")) + args.drop(1)
        callbackId = null
    }
    // Reject malformed command arguments before persisting them; never retain arbitrary text.
    try {
        when (args[0]) {
            "start", "confirm", "deny", "request", "accept", "reject" -> uuid(args[1])
            "view" -> { uuid(args[1]); ViewRange.parse(args[2],args[3],args[4]) }
            "discover" -> requireWire(args[1] in setOf("on","off"))
        }
    } catch (_: Fault) { return base }
    val nick = if (args[0] == "confirm") from.optionalText("username")?.takeIf { it.matches(Regex("[A-Za-z0-9_]{5,32}")) } else null
    return obj("updateId" to num(updateId), "senderId" to num(sender), "chatId" to num(chatId), "displayName" to str(name), "username" to nick?.let(::str),
        "command" to str(args[0]), "args" to arr(args.drop(1).map(::str)), "callbackId" to callbackId?.let(::str))
}
