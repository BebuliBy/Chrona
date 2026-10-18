package by.bebuliby.chrona.ui

import java.net.URI
import by.bebuliby.chrona.domain.ServerBaseUrl

/** Input syntax guard only, NOT DNS/TLS/server trust verification. Core validates again before use. */
internal fun normalizedPublicServerBaseUrl(input: String): String? = ServerBaseUrl.normalize(input)

internal fun serverOrigin(baseUrl: String): String? {
    val normalized = normalizedPublicServerBaseUrl(baseUrl) ?: return null
    val uri = URI(normalized)
    return "https://${uri.host}${if (uri.port == -1) "" else ":${uri.port}"}"
}
