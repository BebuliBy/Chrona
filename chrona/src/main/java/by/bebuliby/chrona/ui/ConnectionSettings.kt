package by.bebuliby.chrona.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import by.bebuliby.chrona.domain.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The target is core-owned and intentionally not editable or displayed in the user UI. */
@Composable
internal fun ConnectionSettings(state: ChronaUiState, dispatch: (ChronaAction) -> Unit) {
    val configured = normalizedPublicServerBaseUrl(state.connection.baseUrl) != null
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Аккаунт", style = MaterialTheme.typography.titleMedium)
            if (state.connection.loginPending) {
                Text("Подтвердите вход в Telegram", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(enabled = configured && !state.busy,
                    onClick = { dispatch(ChronaAction.RefreshLoginStatus) }, modifier = Modifier.testTag("refresh_login")) { Text("Проверить вход") }
            }
            if (state.profile.telegramLinked) {
                OutlinedButton(enabled = !state.busy, onClick = { dispatch(ChronaAction.Logout) }, modifier = Modifier.testTag("logout")) { Text("Выйти из аккаунта") }
            } else {
                if (!configured) Text("Вход временно недоступен", style = MaterialTheme.typography.bodySmall)
                Button(enabled = configured && !state.busy && !state.connection.loginPending,
                    onClick = { dispatch(ChronaAction.BeginTelegramLogin) }, modifier = Modifier.testTag("telegram_login")) { Text("Войти через Telegram") }
            }
        }
    }
}

internal fun canRequestManualSync(state: ChronaUiState): Boolean = normalizedPublicServerBaseUrl(state.connection.baseUrl) != null &&
    state.profile.telegramLinked && !state.profile.accountId.isNullOrBlank() &&
    !state.connection.loginPending && !state.busy && state.sync.status != SyncStatus.SYNCING

@Composable
internal fun ManualSyncSettings(state: ChronaUiState, dispatch: (ChronaAction) -> Unit) {
    val canConfigure = normalizedPublicServerBaseUrl(state.connection.baseUrl) != null && state.profile.telegramLinked &&
        !state.profile.accountId.isNullOrBlank() && !state.connection.loginPending && !state.busy
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Синхронизация", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Автосинхронизация", Modifier.weight(1f))
                Switch(checked = state.sync.automaticEnabled, enabled = canConfigure,
                    onCheckedChange = { dispatch(ChronaAction.SetAutomaticSync(it)) }, modifier = Modifier.testTag("automatic_sync"))
            }
            when (state.sync.status) {
                SyncStatus.SYNCING -> Text("Синхронизация…", modifier = Modifier.testTag("sync_status"))
                SyncStatus.OFFLINE -> Text("Нет связи. Попробуйте позже.", modifier = Modifier.testTag("sync_status"))
                SyncStatus.ERROR -> Text("Не удалось синхронизировать. Повторите попытку.", modifier = Modifier.testTag("sync_status"))
                else -> Unit
            }
            state.sync.lastSuccessAt?.let { instant ->
                val zone = runCatching { ZoneId.of(state.today.zoneId) }.getOrDefault(ZoneId.systemDefault())
                Text("Обновлено: ${DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(zone).format(instant)}",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("last_sync"))
            }
            OutlinedButton(enabled = canRequestManualSync(state), onClick = { dispatch(ChronaAction.RequestSync) },
                modifier = Modifier.testTag("request_sync")) { Text("Синхронизировать сейчас") }
        }
    }
}
