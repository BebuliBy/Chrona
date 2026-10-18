package by.bebuliby.chrona.ui

import java.net.URI
import java.util.Locale

/** Input syntax guard only, NOT DNS/TLS/server trust verification. Core validates again before use. */
internal fun normalizedPublicServerBaseUrl(input: String): String? {
    val text = input.trim()
    if (text.isEmpty() || text.length > 2048 || text.any { it.code <= 32 || it.code >= 127 } || '%' in text || '\\' in text) return null
    val uri = runCatching { URI(text) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", true) || uri.isOpaque || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    if (uri.port != -1 && uri.port !in 1..65535) return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    val labels = host.split('.')
    if (host.length > 253 || labels.size < 2 || !Regex("[a-z]{2,63}").matches(labels.last()) ||
        labels.any { !Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?").matches(it) } ||
        labels.last() in setOf("localhost", "local", "internal", "lan", "home", "test", "invalid", "example", "onion", "arpa")) return null
    // Neither the bot API nor bot file URLs are a Chrona server base URL.
    if (host == "api.telegram.org" || host.endsWith(".api.telegram.org")) return null
    val path = uri.rawPath.orEmpty().trimEnd('/')
    if (path.isNotEmpty()) {
        if (!Regex("/[A-Za-z0-9._~/-]+").matches(path)) return null
        val segments = path.drop(1).split('/')
        val credentialRoutes = setOf("token", "access_token", "refresh_token", "authorization", "session", "sessions", "login")
        if (segments.any { it.isEmpty() || it == "." || it == ".." || it.lowercase(Locale.ROOT) in credentialRoutes ||
                Regex("bot[0-9].*", RegexOption.IGNORE_CASE).matches(it) }) return null
    }
    return URI("https", null, host, if (uri.port == 443) -1 else uri.port, path, null, null).toASCIIString()
}

internal fun serverOrigin(baseUrl: String): String? {
    val normalized = normalizedPublicServerBaseUrl(baseUrl) ?: return null
    val uri = URI(normalized)
    return "https://${uri.host}${if (uri.port == -1) "" else ":${uri.port}"}"
}
