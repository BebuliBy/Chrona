package by.bebuliby.chrona.preview

import by.bebuliby.chrona.ui.normalizedPublicServerBaseUrl
import by.bebuliby.chrona.ui.serverOrigin
import org.junit.Assert.*
import org.junit.Test

/** Pure input syntax tests; never resolves DNS or claims that an example server exists. */
class ServerUrlInputTest {
    private val origin = "https://api.chrona.example.org"

    @Test fun blankOrIncompleteIsNotConfigured() {
        listOf("", " ", "https://", "api.chrona.example.org").forEach { assertNull(normalizedPublicServerBaseUrl(it)) }
    }
    @Test fun normalizesSchemeHostDefaultPortAndTrailingSlash() {
        assertEquals(origin, normalizedPublicServerBaseUrl(" HTTPS://API.CHRONA.EXAMPLE.ORG:443/ "))
    }
    @Test fun supportsOrdinaryBasePathAndExplicitHttpsPort() {
        assertEquals("$origin:8443/chrona/v1", normalizedPublicServerBaseUrl("$origin:8443/chrona/v1/"))
        assertEquals("$origin:8443", serverOrigin("$origin:8443/chrona/v1"))
    }
    @Test fun refusesNonHttpsUserInfoQueryFragmentAndInvalidPorts() {
        listOf("http://api.chrona.example.org", "file:///tmp/server", "content://server", "https://user:EXAMPLE@api.chrona.example.org",
            "$origin?token=NOT_REAL", "$origin#fragment", "$origin:0", "$origin:65536").forEach { assertNull(normalizedPublicServerBaseUrl(it)) }
    }
    @Test fun refusesObviousNonPublicAndAmbiguousHostNames() {
        listOf("https://localhost", "https://server.internal", "https://server.local", "https://127.0.0.1", "https://10.0.0.1",
            "https://[::1]", "https://2130706433", "https://api.chrona.example.org.", "https://*.example.org").forEach { assertNull(normalizedPublicServerBaseUrl(it)) }
    }
    @Test fun credentialRoutesTraversalEncodingAndBotApiAreNotServerBases() {
        listOf("$origin/token/example", "$origin/login", "$origin/file/bot000-EXAMPLE", "$origin/../v1", "$origin/a//b",
            "$origin/%2e%2e/v1", "$origin/a\\b", "$origin/фото", "https://api.telegram.org").forEach { assertNull(normalizedPublicServerBaseUrl(it)) }
    }
    @Test fun originIgnoresBasePathButNotPortOrHost() {
        assertEquals(serverOrigin("$origin/a"), serverOrigin("$origin:443/b"))
        assertNotEquals(serverOrigin(origin), serverOrigin("$origin:8443"))
        assertNotEquals(serverOrigin(origin), serverOrigin("https://other.chrona.example.org"))
    }
    @Test fun embeddedControlsAndOversizedInputAreRejected() {
        assertNull(normalizedPublicServerBaseUrl("$origin/a\nb"))
        assertNull(normalizedPublicServerBaseUrl("$origin/${"a".repeat(2048)}"))
    }
}
