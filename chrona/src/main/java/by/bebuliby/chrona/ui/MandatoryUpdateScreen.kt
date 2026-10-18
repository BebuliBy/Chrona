package by.bebuliby.chrona.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import by.bebuliby.chrona.domain.ChronaAction
import by.bebuliby.chrona.domain.UpdateUiState

/** UI-only gate. It does not stop collectors, sync, services or the host lifecycle. */
@Composable
internal fun MandatoryUpdateScreen(update: UpdateUiState, dispatch: (ChronaAction) -> Unit) {
    BackHandler { /* No back route into the old UI. OS Home/minimize remains available. */ }
    Surface(Modifier.fillMaxSize().testTag("mandatory_update"), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(Modifier.align(Alignment.Center).widthIn(max = 520.dp).fillMaxWidth().padding(24.dp)
                .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ChronaClockArt(Modifier.size(112.dp))
                Text("Доступно обновление", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                if (update.versionName.isNotBlank()) Text("Версия ${update.versionName}", style = MaterialTheme.typography.titleMedium)
                if (update.checking) {
                    CircularProgressIndicator()
                    Text("Проверяем обновление…")
                }
                if (update.downloading) {
                    val progress = update.progressPercent?.coerceIn(0, 100)
                    if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else {
                        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("Загрузка: $progress%")
                    }
                }
                update.message?.takeIf { it.isNotBlank() && !update.checking && !update.downloading }?.let {
                    // Mandatory-update errors stay here with retry, not in the transient notice banner.
                    Text(if (update.installerPermissionRequired) "Разрешите установку обновления." else "Не удалось обновить приложение. Повторите попытку.",
                        color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("update_message"))
                }
                Button(enabled = !update.checking && !update.downloading, onClick = { dispatch(ChronaAction.InstallUpdate) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("install_update")) {
                    Text(when {
                        update.installerPermissionRequired -> "Разрешить установку"
                        !update.message.isNullOrBlank() -> "Повторить обновление"
                        else -> "Обновить"
                    }, maxLines = 2, textAlign = TextAlign.Center)
                }
                if (!update.message.isNullOrBlank()) TextButton(enabled = !update.checking && !update.downloading,
                    onClick = { dispatch(ChronaAction.CheckForUpdates) }) { Text("Проверить снова") }
            }
        }
    }
}
