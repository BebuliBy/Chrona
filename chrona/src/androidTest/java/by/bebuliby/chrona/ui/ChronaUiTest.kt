package by.bebuliby.chrona.ui

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.PowerManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import by.bebuliby.chrona.MainActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class ChronaUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun screenMustBeVoluntarilyUnlocked() {
        val context = compose.activity
        assertTrue("User must turn screen on", context.getSystemService(PowerManager::class.java).isInteractive)
        assertFalse("User must unlock phone; test must not bypass lock", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
    }

    @Test fun demoIsExplicitAndTabsWork() {
        compose.onNodeWithTag("demo_banner").assertIsDisplayed()
        compose.onNodeWithTag("tab_HABITS").performClick()
        compose.onNodeWithTag("add_habit").assertIsDisplayed()
        compose.onNodeWithTag("tab_HISTORY").performClick()
        compose.onNodeWithText("Неделя · пример").assertIsDisplayed()
        compose.onNodeWithTag("tab_SETTINGS").performClick()
        compose.onNodeWithTag("theme_LIGHT").assertIsDisplayed()
    }

    @Test fun manualHabitCanBeCreatedInDemo() {
        compose.onNodeWithTag("tab_HABITS").performClick()
        compose.onNodeWithTag("add_habit").performClick()
        compose.onNodeWithTag("habit_title").performTextInput("Проверка UI")
        compose.onNodeWithTag("habit_target").performTextReplacement("5")
        compose.onNodeWithTag("save_habit").performClick()
        // The new row may not be composed yet: scroll the lazy list to its matcher.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Проверка UI"))
        compose.onNodeWithText("Проверка UI").assertIsDisplayed()
    }

    @Test fun lightAndDarkOwnUiScreenshots() {
        compose.onNodeWithTag("tab_SETTINGS").performClick()
        compose.onNodeWithTag("theme_LIGHT").performClick()
        compose.onNodeWithTag("tab_TODAY").performClick()
        capture("chrona-today-light.png")
        compose.onNodeWithTag("tab_SETTINGS").performClick()
        compose.onNodeWithTag("theme_DARK").performClick()
        compose.onNodeWithTag("tab_TODAY").performClick()
        capture("chrona-today-dark.png")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        // Compose root only; no other app, notification shade or system screenshot.
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.cacheDir, name).outputStream().use { stream ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }
}
