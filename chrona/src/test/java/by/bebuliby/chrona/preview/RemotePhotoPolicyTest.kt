package by.bebuliby.chrona.preview

import by.bebuliby.chrona.domain.PhotoKind
import by.bebuliby.chrona.domain.ProfilePhoto
import by.bebuliby.chrona.ui.RemotePhotoPolicy
import by.bebuliby.chrona.ui.isPublicPhotoAddress
import by.bebuliby.chrona.ui.validatedRemotePhoto
import org.junit.Assert.*
import org.junit.Test

/** Pure syntax/address checks: no URLConnection, DNS, Android context or actual remote image request. */
class RemotePhotoPolicyTest {
    private val origin = "https://photos.chrona.example.org"
    private val path = "/v1/profile-photos/avatar-123.webp"
    private val policy = RemotePhotoPolicy(setOf(origin))
    private fun allowed(url: String, linked: Boolean = true, kind: PhotoKind = PhotoKind.TELEGRAM_URL) =
        validatedRemotePhoto(ProfilePhoto(kind, url), linked, policy)

    @Test fun approvedHttpsAvatarIsAcceptedAndCanonicalized() {
        assertEquals("$origin$path", allowed("https://PHOTOS.CHRONA.EXAMPLE.ORG:443$path")?.key)
    }
    @Test fun bindingAndRemoteKindAreMandatory() {
        assertNull(allowed("$origin$path", linked = false))
        assertNull(allowed("$origin$path", kind = PhotoKind.LOCAL_URI))
        assertNull(validatedRemotePhoto(null, true, policy))
    }
    @Test fun emptyPolicyFailsClosedEvenForWellFormedHttps() {
        assertNull(validatedRemotePhoto(ProfilePhoto(PhotoKind.TELEGRAM_URL, "$origin$path"), true, RemotePhotoPolicy()))
    }
    @Test fun originMustMatchExactlyNotBySubstringOrSuffix() {
        listOf("https://other.example.org$path", "https://photos.chrona.example.org.evil.org$path",
            "https://sub.photos.chrona.example.org$path").forEach { assertNull(allowed(it)) }
    }
    @Test fun onlyHttps443WithoutUserInfoQueryOrFragment() {
        listOf("http://photos.chrona.example.org$path", "file:///tmp/avatar.png", "content://gallery/avatar", "data:image/png,bytes",
            "$origin:444$path", "https://user:example@photos.chrona.example.org$path", "$origin$path?token=EXAMPLE",
            "$origin$path?size=64", "$origin$path#fragment").forEach { assertNull(allowed(it)) }
    }
    @Test fun telegramBotFileUrlsAndRecognizableCredentialRoutesAreRejected() {
        val botPolicy = RemotePhotoPolicy(setOf("https://api.telegram.org", origin))
        assertNull(validatedRemotePhoto(ProfilePhoto(PhotoKind.TELEGRAM_URL,
            "https://api.telegram.org/file/bot000000:NOT_A_REAL_TOKEN/photos/avatar.jpg"), true, botPolicy))
        listOf("/file/bot000000-EXAMPLE/avatar.jpg", "/token/example/avatar.png", "/auth/avatar.png", "/session/avatar.jpg")
            .forEach { assertNull(allowed("$origin$it")) }
    }
    @Test fun traversalEncodingControlsAndAmbiguousPathsAreRejected() {
        listOf("/../avatar.png", "/v1/./avatar.png", "/v1//avatar.png", "/v1/%2e%2e/avatar.png", "/file/%62ot000/avatar.png",
            "/v1/avatar.png\n", "/v1\\avatar.png", "/v1/фото.png", "/").forEach { assertNull(allowed("$origin$it")) }
    }
    @Test fun unsafeOriginsCannotBeEnabledByPolicy() {
        listOf("https://localhost", "https://localhost.local", "https://127.0.0.1", "https://127.1", "https://2130706433",
            "https://[::1]", "https://192.168.0.1", "https://server.internal", "https://photos.chrona.example.org.",
            "http://photos.chrona.example.org", "https://*.example.org", "$origin/with/path", "$origin?token=EXAMPLE").forEach { bad ->
            assertTrue(RemotePhotoPolicy(setOf(bad)).origins.isEmpty())
        }
    }
    @Test fun policyCopiesCallerSetRatherThanTrustingFutureMutations() {
        val origins = mutableSetOf(origin)
        val snapshot = RemotePhotoPolicy(origins)
        origins.clear()
        assertNotNull(validatedRemotePhoto(ProfilePhoto(PhotoKind.TELEGRAM_URL, "$origin$path"), true, snapshot))
    }
    @Test fun addressGuardRejectsPrivateLoopbackLinkLocalReservedAndMulticast() {
        fun bytes(a: Int, b: Int, c: Int, d: Int) = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())
        listOf(bytes(0, 0, 0, 0), bytes(10, 1, 2, 3), bytes(127, 0, 0, 1), bytes(172, 16, 0, 1), bytes(192, 168, 1, 1),
            bytes(169, 254, 1, 1), bytes(100, 64, 0, 1), bytes(192, 0, 2, 1), bytes(198, 18, 0, 1), bytes(198, 51, 100, 1),
            bytes(203, 0, 113, 1), bytes(224, 0, 0, 1), bytes(255, 255, 255, 255)).forEach { assertFalse(isPublicPhotoAddress(it)) }
        assertFalse(isPublicPhotoAddress(ByteArray(16)))
        assertFalse(isPublicPhotoAddress(ByteArray(16).apply { this[15] = 1 }))
        assertFalse(isPublicPhotoAddress(ByteArray(16).apply { this[0] = 0xfc.toByte() }))
        assertFalse(isPublicPhotoAddress(byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte()) + ByteArray(12)))
        assertTrue(isPublicPhotoAddress(bytes(8, 8, 8, 8)))
        assertTrue(isPublicPhotoAddress(byteArrayOf(0x26, 0x06, 0x47, 0x00) + ByteArray(12)))
    }
}
