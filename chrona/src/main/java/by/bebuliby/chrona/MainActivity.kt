package by.bebuliby.chrona

import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import by.bebuliby.chrona.data.ChronaApplication
import by.bebuliby.chrona.data.client.ServerOrigin
import by.bebuliby.chrona.data.client.WireCodec
import by.bebuliby.chrona.domain.ChronaAction
import by.bebuliby.chrona.domain.ChronaEffect
import by.bebuliby.chrona.presentation.ChronaViewModel
import by.bebuliby.chrona.ui.ChronaApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var model: ChronaViewModel
    private val updatePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (::model.isInitialized) model.updatePermissionReturned()
    }
    private val photoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                model.dispatch(ChronaAction.ProfilePhotoPicked(uri.toString()))
            } catch (_: SecurityException) {
                model.hostError("PHOTO_PERMISSION", "Выбранное приложение не предоставило постоянный доступ к фото.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ChronaApplication).container
        model = ViewModelProvider(this, ChronaViewModel.factory(container, this))[ChronaViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.effects.collect { effect ->
                    try {
                        when (effect) {
                            ChronaEffect.OpenPhotoPicker -> photoPicker.launch(arrayOf("image/*"))
                            ChronaEffect.OpenAccessibilitySettings -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            ChronaEffect.OpenUpdateInstallPermission -> updatePermission.launch(Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName")))
                            is ChronaEffect.OpenUpdateInstaller -> {
                                val uri = android.net.Uri.parse(effect.contentUri)
                                require(uri.scheme == "content" && uri.authority == "$packageName.updates")
                                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            }
                            is ChronaEffect.OpenTelegramLogin -> {
                                val uri = android.net.Uri.parse(effect.telegramStartUrl)
                                val request = WireCodec.uuid(uri.getQueryParameter("start").orEmpty())
                                val safe = ServerOrigin.telegram(effect.telegramStartUrl, request)
                                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(safe)))
                            }
                        }
                    } catch (_: android.content.ActivityNotFoundException) {
                        model.hostError("HOST_UNAVAILABLE", "Не удалось открыть системный экран.")
                    } catch (_: java.io.IOException) {
                        model.hostError("UNSAFE_LOGIN_URL", "Сервер вернул недопустимую ссылку входа.")
                    } catch (_: SecurityException) {
                        model.hostError("INSTALL_PERMISSION", "Android не разрешил открыть установщик. Данные не изменены.")
                    } catch (_: IllegalArgumentException) {
                        model.hostError("UNSAFE_INSTALL_URI", "Ссылка обновления не прошла проверку.")
                    }
                }
            }
        }
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            ChronaApp(state, model::dispatch)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::model.isInitialized) model.refreshHostStatus()
    }
    override fun onStart() {
        super.onStart()
        if (::model.isInitialized) model.hostStarted()
    }
    override fun onStop() {
        if (::model.isInitialized) model.hostStopped()
        super.onStop()
    }
}
