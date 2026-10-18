package by.bebuliby.chrona.ui

import by.bebuliby.chrona.domain.PhotoKind
import by.bebuliby.chrona.domain.ProfilePhoto
import java.net.URI
import java.util.Locale

/**
 * Host-supplied, exact HTTPS origins for sanitized, credential-free avatar endpoints.
 * Never build this allowlist from ProfilePhoto.location, user input or a server response.
 * Empty by default: adding the renderer does not authorize arbitrary HTTP destinations.
 */
class RemotePhotoPolicy(allowedOrigins: Set<String> = emptySet()) {
    internal val origins: Set<String> = allowedOrigins.mapNotNull { raw ->
        parseHttps(raw)?.takeIf { it.rawPath.isNullOrEmpty() || it.rawPath == "/" }?.let(::origin)
    }.toSet()
}

/** Not a data class: accidental toString/logging must not expose the image URL. */
internal class SafeRemotePhoto internal constructor(internal val uri: URI) {
    internal val key: String get() = uri.toASCIIString()
}

internal fun validatedRemotePhoto(photo: ProfilePhoto?, telegramLinked: Boolean, policy: RemotePhotoPolicy): SafeRemotePhoto? {
    if (!telegramLinked || photo?.kind != PhotoKind.TELEGRAM_URL) return null
    val parsed = parseHttps(photo.location) ?: return null
    if (origin(parsed) !in policy.origins) return null
    val path = parsed.rawPath.orEmpty()
    // Backend must provide a stable, sanitized path ID, not a signed URL/bot download URL.
    if (!path.startsWith('/') || path == "/" || !Regex("/[A-Za-z0-9._~/-]+").matches(path)) return null
    val segments = path.split('/').drop(1)
    if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
    val forbidden = setOf("auth", "authorization", "access_token", "token", "refresh_token", "session", "sessions", "login")
    if (segments.any { it.lowercase(Locale.ROOT) in forbidden || Regex("bot(?:[0-9].*|$)", RegexOption.IGNORE_CASE).matches(it) }) return null
    return SafeRemotePhoto(URI("https", null, parsed.host.lowercase(Locale.ROOT), -1, path, null, null))
}

private fun origin(uri: URI): String = "https://${uri.host.lowercase(Locale.ROOT)}"

private fun parseHttps(raw: String): URI? {
    if (raw.length !in 1..2048 || raw.any { it.code <= 32 || it.code >= 127 } || '\\' in raw || '%' in raw) return null
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.isOpaque || uri.rawUserInfo != null ||
        uri.rawQuery != null || uri.rawFragment != null || uri.port !in setOf(-1, 443)) return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    if (!publicDnsName(host) || host == "api.telegram.org" || host.endsWith(".api.telegram.org")) return null
    return uri
}

/** Lexical check only; a second check rejects non-public DNS answers before opening a connection. */
private fun publicDnsName(host: String): Boolean {
    if (host.length > 253 || host.endsWith('.')) return false
    val labels = host.split('.')
    if (labels.size < 2 || !Regex("[a-z]{2,63}").matches(labels.last())) return false
    if (labels.any { !Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?").matches(it) }) return false
    return labels.last() !in setOf("localhost", "local", "internal", "lan", "home", "test", "invalid", "example", "onion", "arpa")
}

/** Public-unicast only, including explicit CGNAT/documentation/benchmark exclusions. No DNS in tests. */
internal fun isPublicPhotoAddress(address: ByteArray): Boolean {
    val b = address.map { it.toInt() and 255 }
    if (b.size == 4) {
        if (b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224) return false
        if (b[0] == 100 && b[1] in 64..127) return false
        if (b[0] == 169 && b[1] == 254 || b[0] == 172 && b[1] in 16..31) return false
        if (b[0] == 192 && (b[1] == 168 || b[1] == 0 && b[2] in setOf(0, 2))) return false
        if (b[0] == 198 && (b[1] in 18..19 || b[1] == 51 && b[2] == 100)) return false
        if (b[0] == 203 && b[1] == 0 && b[2] == 113) return false
        return true
    }
    if (b.size == 16) {
        if (b[0] and 0xe0 != 0x20) return false // only global 2000::/3; excludes local/mapped/multicast
        if (b[0] == 0x20 && b[1] == 0x01 && (b[2] == 0x0d && b[3] == 0xb8 || b[2] == 0 && b[3] == 0)) return false
        if (b[0] == 0x20 && b[1] == 0x02) return false // 6to4 embeds an unchecked IPv4 address
        return true
    }
    return false
}
