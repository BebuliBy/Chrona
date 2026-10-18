package by.bebuliby.chrona.ui

import android.app.KeyguardManager
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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

/** One native first-configuration probe. No fixtures, login, sync, screenshots or secret inspection. */
@RunWith(AndroidJUnit4::class)
class PublicConnectionSmokeTest {
    private var stage = "ARGUMENT"
    private lateinit var target: String
    private lateinit var requestedInput: String

    // Outside the activity rule: absent argument skips without launching MainActivity. Also sanitizes
    // rule setup/teardown errors, not just assertions, so Compose trees cannot enter the test result.
    @get:Rule(order = 0)
    val privacy = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val argument = InstrumentationRegistry.getArguments().getString("publicApiBaseUrl")
            Assume.assumeTrue("PUBLIC_CONNECTION_SMOKE_ARGUMENT_REQUIRED", !argument.isNullOrBlank())
            try {
                enter("VALIDATE_ARGUMENT")
                target = ServerBaseUrl.normalize(argument!!) ?: throw AssertionError()
                requestedInput = argument.trim()
                check(ServerOrigin.parse(target) == target)
                enter("TARGET_DEBUG_PACKAGE")
                check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "by.bebuliby.chrona.debug")
                base.evaluate()
            } catch (failure: Throwable) {
                val kind = failure.javaClass.simpleName.replace(Regex("[^A-Za-z0-9_]"), "_").take(80).ifEmpty { "Throwable" }
                // Intentionally no cause/original message/suppressed exception/semantics/URL/body.
                throw AssertionError("PUBLIC_CONNECTION_SMOKE_FAILED_STAGE_${stage}_$kind")
            }
        }
    } }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun public401AndFirstNativeServerConfiguration() {
        enter("AWAKE_UNLOCKED")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.getSystemService(PowerManager::class.java).isInteractive)
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)

        withOwnDisplayKeptAwake { runPublicProbe() }
    }

    private fun runPublicProbe() {
        enter("DNS_GLOBAL")
        val resolved = InetAddress.getAllByName(URI(target).host)
        val global = resolved.isNotEmpty() && resolved.all(::isGlobalAddress)
        Log.i(TAG, "DNS_GLOBAL_$global")
        if (!global) { enter("TAILNET_ROUTE_NOT_PUBLIC_TEST"); throw AssertionError() }

        enter("ANONYMOUS_GET_ME")
        // Exactly one direct probe request. No bearer, body, idempotency key or auth/session client.
        val status = runBlocking { HttpsTransport().execute(HttpRequest(target, "GET", "/v1/me")).status }
        Log.i(TAG, "HTTP_STATUS_$status")
        check(status == 401)

        enter("NATIVE_ROOM_READY")
        // This constant system card appears only after the real repository has provisioned/read Room.
        // No personal habit IDs, profile values, database or VM state are inspected.
        compose.waitUntil(20_000) { exists("habit_${ChronaRepository.SHORTS_ID}") }
        enter("OPEN_OWN_SETTINGS")
        compose.onNodeWithTag("profile_menu").performClick()
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithTag("server_base_url").performScrollTo().assertIsDisplayed()
        compose.waitForIdle()

        enter("REVIEW_EXISTING_LOGIN")
        // A first-setup smoke must not log out an existing account or cancel an owner's pending login.
        if (exists("logout") || exists("refresh_login")) {
            enter("EXISTING_LOGIN_REVIEW_REQUIRED"); throw AssertionError()
        }
        val previous = ServerBaseUrl.normalize(ownEditableValue())
        if (previous == target) {
            // Do not pretend an idempotent, disabled Save button has exercised a new Keystore save.
            enter("CONFIG_ALREADY_SAVED_NO_NEW_WRITE_PROBE"); throw AssertionError()
        }

        enter("ENTER_OWN_SERVER_BASE_URL")
        compose.onNodeWithTag("server_base_url").performTextReplacement(requestedInput)
        compose.onNodeWithTag("save_server").performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        if (exists("confirm_server_change")) {
            enter("CONFIRM_UNLINKED_TARGET_CHANGE")
            if (previous == target || exists("logout") || exists("refresh_login")) {
                enter("EXISTING_LOGIN_REVIEW_REQUIRED"); throw AssertionError()
            }
            compose.onNodeWithTag("confirm_server_change").assertIsEnabled().performClick()
        }

        enter("WAIT_NATIVE_CONFIGURE_SAVE")
        compose.waitUntil(20_000) {
            ownConfiguredStatus() && ServerBaseUrl.normalize(ownEditableValue()) == target &&
                !enabled("save_server") && enabled("telegram_login") && !exists("logout")
        }
        compose.onNodeWithTag("connection_status").performScrollTo().assertTextContains("Сервер настроен")
        compose.onNodeWithTag("save_server").assertIsNotEnabled()
        compose.onNodeWithTag("telegram_login").performScrollTo().assertExists().assertIsEnabled()
        // Never click telegram_login, refresh_login, request_sync, system dialogs or external UI.
        enter("COMPLETE_401_AND_NATIVE_CONFIGURED")
    }

    /** Keeps only this already-unlocked foreground test window on, never wakes/unlocks the phone. */
    private fun withOwnDisplayKeptAwake(block: () -> Unit) {
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        var wasSet = false
        compose.activityRule.scenario.onActivity { activity ->
            wasSet = activity.window.attributes.flags and flag != 0
            if (!wasSet) activity.window.addFlags(flag)
        }
        try {
            block()
        } finally {
            if (!wasSet) runCatching {
                compose.activityRule.scenario.onActivity { it.window.clearFlags(flag) }
            }
        }
    }

    private fun exists(tag: String): Boolean = compose.onAllNodesWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    private fun enabled(tag: String): Boolean = compose.onAllNodesWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNodes(atLeastOneRootRequired = false).any { !it.config.contains(SemanticsProperties.Disabled) }

    private fun ownEditableValue(): String = compose.onAllNodesWithTag("server_base_url", useUnmergedTree = true)
        .fetchSemanticsNodes(atLeastOneRootRequired = false).singleOrNull()
        ?.config?.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun ownConfiguredStatus(): Boolean = compose.onAllNodesWithTag("connection_status", useUnmergedTree = true)
        .fetchSemanticsNodes(atLeastOneRootRequired = false).singleOrNull()
        ?.config?.getOrNull(SemanticsProperties.Text)?.any { it.text.contains("Сервер настроен") } == true

    private fun enter(code: String) { stage = code; Log.i(TAG, "PUBLIC_CONNECTION_STAGE_$code") }

    /** Conservative public-unicast sanity check; not a VPN-state detector or routing-table audit. */
    private fun isGlobalAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        if (b.size == 4) {
            val a = b[0]; val c = b[1]; val d = b[2]
            return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
                (a == 100 && c in 64..127) || (a == 169 && c == 254) || (a == 172 && c in 16..31) ||
                (a == 192 && (c == 168 || (c == 0 && d in setOf(0, 2)) || (c == 88 && d == 99))) ||
                (a == 198 && (c in 18..19 || (c == 51 && d == 100))) || (a == 203 && c == 0 && d == 113))
        }
        if (b.size != 16 || (b[0] and 0xe0) != 0x20) return false // excludes ULA/link-local/mapped/special ranges
        if (b[0] == 0x20 && b[1] == 0x02) return false // deprecated 6to4
        if (b[0] == 0x20 && b[1] == 0x01 && (b[2] < 2 || (b[2] == 0x0d && b[3] == 0xb8))) return false
        return true
    }

    companion object { private const val TAG = "ChronaPublicSmoke" }
}
