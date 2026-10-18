package by.bebuliby.chrona.ui

import android.app.KeyguardManager
import android.os.PowerManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import by.bebuliby.chrona.MainActivity
import by.bebuliby.chrona.data.ChronaRepository
import by.bebuliby.chrona.data.client.HttpRequest
import by.bebuliby.chrona.data.client.HttpsTransport
import by.bebuliby.chrona.data.client.ServerOrigin
import by.bebuliby.chrona.domain.ServerBaseUrl
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.net.InetAddress
import java.net.URI

/**
 * Separately authorized native connectivity probe, skipped before Activity launch without an argument.
 * No longer a configuration test: the user UI intentionally has no server editor. Never reads vault,
 * changes target/account, clicks login/sync, wakes/unlocks, takes screenshots or logs a URL/tree.
 */
@RunWith(AndroidJUnit4::class)
class PublicConnectionSmokeTest {
    private var stage = "ARGUMENT"
    private lateinit var target: String

    @get:Rule(order = 0)
    val privacy = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val argument = InstrumentationRegistry.getArguments().getString("publicApiBaseUrl")
            Assume.assumeTrue("PUBLIC_CONNECTION_SMOKE_ARGUMENT_REQUIRED", !argument.isNullOrBlank())
            try {
                stage = "VALIDATE_ARGUMENT"
                target = ServerBaseUrl.normalize(argument!!) ?: throw AssertionError()
                check(ServerOrigin.parse(target) == target)
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                check(context.packageName == "by.bebuliby.chrona.debug")
                stage = "AWAKE_UNLOCKED"
                check(context.getSystemService(PowerManager::class.java).isInteractive)
                check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
                base.evaluate()
            } catch (failure: Throwable) {
                val kind = failure.javaClass.simpleName.replace(Regex("[^A-Za-z0-9_]"), "_").take(80)
                throw AssertionError("PUBLIC_CONNECTION_SMOKE_FAILED_STAGE_${stage}_$kind")
            }
        }
    } }

    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun anonymous401AndNoUserServerEditor() {
        stage = "DNS_GLOBAL"
        val addresses = InetAddress.getAllByName(URI(target).host)
        check(addresses.isNotEmpty() && addresses.all(::isGlobalAddress))
        stage = "ANONYMOUS_GET_ME"
        // Exactly one anonymous request to the explicit test argument, NOT a claim about persisted target.
        val status = runBlocking { HttpsTransport().execute(HttpRequest(target, "GET", "/v1/me")).status }
        check(status == 401)
        stage = "NATIVE_ROOM_READY"
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("habit_${ChronaRepository.SHORTS_ID}")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        stage = "READ_ONLY_SETTINGS"
        compose.onNodeWithTag("profile_menu").performClick()
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithTag("server_base_url").assertDoesNotExist()
        compose.onNodeWithTag("save_server").assertDoesNotExist()
        compose.onNodeWithTag("confirm_server_change").assertDoesNotExist()
        compose.onAllNodesWithText(URI(target).host, substring = true).assertCountEquals(0)
        // Do not inspect profile/session values, persisted target, editable text or click account actions.
    }

    /** Preserve the native probe's conservative public-unicast guard, independently of renderer policy. */
    private fun isGlobalAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        if (b.size == 4) {
            val a = b[0]; val c = b[1]; val d = b[2]
            return !(a == 0 || a == 10 || a == 127 || a >= 224 || (a == 100 && c in 64..127) ||
                (a == 169 && c == 254) || (a == 172 && c in 16..31) ||
                (a == 192 && (c == 168 || (c == 0 && d in setOf(0, 2)) || (c == 88 && d == 99))) ||
                (a == 198 && (c in 18..19 || (c == 51 && d == 100))) || (a == 203 && c == 0 && d == 113))
        }
        if (b.size != 16 || (b[0] and 0xe0) != 0x20) return false
        if (b[0] == 0x20 && b[1] == 0x02) return false
        if (b[0] == 0x20 && b[1] == 0x01 && (b[2] < 2 || (b[2] == 0x0d && b[3] == 0xb8))) return false
        return true
    }
}
