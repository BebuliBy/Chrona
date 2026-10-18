package by.bebuliby.chrona.data.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

class ClientFailure(rawCode: String, val httpStatus: Int = 0) : IOException("Chrona client request failed") {
    val code = rawCode.takeIf { it in codes } ?: "SERVER_ERROR"
    companion object {
        private val codes = setOf("INVALID_ORIGIN", "UNSAFE_LOGIN_URL", "INVALID_RESPONSE", "UNSUPPORTED_VERSION",
            "SOURCE_NOT_WRITABLE", "SHORTS_MAPPING_REQUIRED", "SHORTS_MAPPING_CONFLICT", "ACCOUNT_BINDING_CONFLICT",
            "SECURE_STORAGE", "LOGIN_RESPONSE_LOST", "SERVER_NOT_CONFIGURED", "EXPIRED", "CONSUMED", "DENIED",
            "CANCELLED", "LOGIN_REQUIRED", "REMOTE_REVOKE_UNCONFIRMED", "OFFLINE", "HTTP_ERROR", "SERVER_ERROR",
            "REDIRECT_REJECTED", "REQUEST_TOO_LARGE", "RESPONSE_TOO_LARGE", "SYNC_REJECTED", "PULL_LOCAL_CONFLICT",
            "PULL_LIMIT", "UNSUPPORTED_PAGINATION", "REVISION_CONFLICT", "IDEMPOTENCY_CONFLICT", "POLICY_UNRESOLVED",
            "CURSOR_RESNAPSHOT_REQUIRED", "TELEGRAM_NOT_CONFIGURED", "NOT_CONFIGURED", "NOT_IMPLEMENTED", "NOT_READY",
            "UNAUTHENTICATED", "FORBIDDEN", "NOT_FOUND", "VALIDATION", "RATE_LIMITED", "LOGIN_NOT_CONFIRMED")
    }
}

object ServerOrigin {
    fun parse(input: String): String {
        val raw = input.trim()
        if (raw.isEmpty()) return ""
        val uri = try { URI(raw) } catch (_: Exception) { throw ClientFailure("INVALID_ORIGIN") }
        if (raw.length > 2048 || !uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null || uri.rawPath !in listOf("", "/") ||
            uri.port !in -1..65535 || uri.port == 0 || raw.any { it.isISOControl() } || '\\' in raw)
            throw ClientFailure("INVALID_ORIGIN")
        val host = uri.host.lowercase()
        return "https://$host" + if (uri.port in listOf(-1, 443)) "" else ":${uri.port}"
    }
    fun telegram(input: String, requestId: String): String {
        val u = try { URI(input) } catch (_: Exception) { throw ClientFailure("UNSAFE_LOGIN_URL") }
        if (u.scheme != "https" || u.host != "t.me" || u.port != -1 || u.rawUserInfo != null ||
            u.rawFragment != null || !u.path.matches(Regex("/[A-Za-z0-9_]{5,32}")) ||
            u.rawQuery != "start=$requestId") throw ClientFailure("UNSAFE_LOGIN_URL")
        return input
    }
    fun query(value: String): String = URLEncoder.encode(value, "UTF-8")
}

object Pkce {
    fun verifier(random: SecureRandom = SecureRandom()): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also(random::nextBytes))
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}

class HttpRequest(val origin: String, val method: String, val path: String,
                  val body: String? = null, val bearer: String? = null, val idempotencyKey: String? = null) {
    override fun toString() = "HttpRequest(redacted)"
}
class HttpReply(val status: Int, val body: String) { override fun toString() = "HttpReply($status,redacted)" }
fun interface HttpTransport { suspend fun execute(request: HttpRequest): HttpReply }

class HttpsTransport : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpReply = withContext(Dispatchers.IO) {
        val origin = ServerOrigin.parse(request.origin)
        if (origin.isEmpty() || !request.path.startsWith("/v1/") || request.path.startsWith("//") ||
            request.path.any { it.isISOControl() }) throw ClientFailure("INVALID_ORIGIN")
        val connection = URI(origin + request.path).toURL().openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 15_000; connection.readTimeout = 20_000
            connection.requestMethod = request.method
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            request.bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            request.idempotencyKey?.let { connection.setRequestProperty("Idempotency-Key", it) }
            request.body?.let {
                val bytes = it.toByteArray(Charsets.UTF_8)
                if (bytes.size > 1_048_576) throw ClientFailure("REQUEST_TOO_LARGE")
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { stream -> stream.write(bytes) }
            }
            val status = connection.responseCode
            if (status in 300..399) throw ClientFailure("REDIRECT_REJECTED", status)
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use {
                val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val count = it.read(buffer)
                    if (count == -1) break
                    if (output.size() + count > 2_097_152) throw ClientFailure("RESPONSE_TOO_LARGE")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            if (bytes.size > 2_097_152) throw ClientFailure("RESPONSE_TOO_LARGE")
            HttpReply(status, bytes.toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
}
