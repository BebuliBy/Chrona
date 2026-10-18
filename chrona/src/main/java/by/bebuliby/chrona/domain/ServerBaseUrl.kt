package by.bebuliby.chrona.domain

import java.net.URI
import java.util.Locale

/** Shared UI/core syntax policy. No DNS/TLS checks and no embedded deployment address. */
object ServerBaseUrl {
    fun normalize(input: String): String? {
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
        if (host == "api.telegram.org" || host.endsWith(".api.telegram.org")) return null
        val rawPath = uri.rawPath.orEmpty()
        if ("//" in rawPath) return null
        val path = rawPath.removeSuffix("/")
        if (path.isNotEmpty()) {
            if (!Regex("/[A-Za-z0-9._~/-]+").matches(path)) return null
            val credentials = setOf("token", "access_token", "refresh_token", "authorization", "session", "sessions", "login")
            if (path.drop(1).split('/').any { it.isEmpty() || it == "." || it == ".." ||
                    it.lowercase(Locale.ROOT) in credentials || Regex("bot[0-9].*", RegexOption.IGNORE_CASE).matches(it) }) return null
        }
        return URI("https", null, host, if (uri.port == 443) -1 else uri.port, path, null, null).toASCIIString()
    }
}
