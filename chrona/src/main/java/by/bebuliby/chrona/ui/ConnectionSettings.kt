package by.bebuliby.chrona.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import by.bebuliby.chrona.domain.*

/** UI-only draft. No VM, HTTP, token input, automatic login refresh or sync side effects. */
@Composable
internal fun ConnectionSettings(state: ChronaUiState, dispatch: (ChronaAction) -> Unit) {
    val connection = state.connection
    // Deliberately NOT rememberSaveable: an invalid pasted credential URL must not enter saved state.
    var draft by remember(connection.baseUrl) { mutableStateOf(normalizedPublicServerBaseUrl(connection.baseUrl).orEmpty()) }
    var confirmUrl by remember { mutableStateOf<String?>(null) }
    val normalized = normalizedPublicServerBaseUrl(draft)
    val saved = normalizedPublicServerBaseUrl(connection.baseUrl)
    val dirty = normalized != saved || draft.isNotBlank() && normalized == null
    val configured = saved != null
    val canLogin = configured && !dirty && !state.busy && !connection.loginPending
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Подключение к серверу", style = MaterialTheme.typography.titleLarge)
            Text(when {
                !configured -> "Сервер не настроен · данные остаются на устройстве"
                connection.loginPending -> "Ожидается подтверждение входа в Telegram"
                state.sync.status == SyncStatus.OFFLINE -> "Нет связи с сервером · локальные данные сохранены"
                state.sync.status == SyncStatus.ERROR -> "Ошибка подключения / синхронизации · см. сообщение приложения"
                state.profile.telegramLinked -> "Вход подтверждён · сервер настроен"
                else -> "Сервер настроен · вход ещё не выполнен"
            }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("connection_status"))
            OutlinedTextField(draft, { draft = it }, singleLine = true, enabled = !state.busy,
                label = { Text("Публичный HTTPS base URL") }, placeholder = { Text("https://<публичный-домен>") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                isError = draft.isNotBlank() && normalized == null, modifier = Modifier.fillMaxWidth().testTag("server_base_url"),
                supportingText = { Text("Без пароля, токена, userinfo, query или fragment. Адрес проверяется повторно ядром.") })
            Text("Смена адреса (домен / порт / base path) сбрасывает вход и незавершённый запрос входа. Локальные записи не удаляются и не отправляются при сохранении адреса.",
                style = MaterialTheme.typography.bodySmall)
            if (dirty) Text("Адрес ещё не сохранён. Сначала сохраните его или верните прежний, затем начинайте вход.", style = MaterialTheme.typography.bodySmall)
            Button(enabled = normalized != null && dirty && !state.busy, modifier = Modifier.testTag("save_server"), onClick = {
                normalized?.let { next ->
                    if (saved != next &&
                        (connection.baseUrl.isNotBlank() || state.profile.telegramLinked || connection.loginPending)) confirmUrl = next
                    else dispatch(ChronaAction.ConfigureServer(next))
                }
            }) { Text("Сохранить адрес") }
            if (connection.loginPending) {
                Text("Подтвердите запрос в Telegram-боте, затем вернитесь сюда. До подтверждённого ответа это не успешный вход.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = configured && !dirty && !state.busy,
                    onClick = { dispatch(ChronaAction.RefreshLoginStatus) }, modifier = Modifier.testTag("refresh_login")) { Text("Проверить статус входа") }
            }
            if (state.profile.telegramLinked) {
                OutlinedButton(enabled = !state.busy, onClick = { dispatch(ChronaAction.Logout) }, modifier = Modifier.testTag("logout")) { Text("Выйти из аккаунта") }
            } else {
                Button(enabled = canLogin, onClick = { dispatch(ChronaAction.BeginTelegramLogin) }, modifier = Modifier.testTag("telegram_login")) { Text("Войти через Telegram") }
            }
            Text("Пароль и токены в приложении вводить не нужно. Вход — через одноразовое подтверждение в боте. Сам вход не разрешает отправку локальных записей: синхронизация запускается отдельно вручную.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    confirmUrl?.let { target ->
        val stillCurrent = normalized == target && !state.busy
        AlertDialog(onDismissRequest = { confirmUrl = null }, title = { Text("Сменить сервер и сбросить вход?") },
            text = { Text("Новый сервер: $target\nТекущий вход и ожидающий запрос будут сброшены. Токены не переносятся на другой сервер или префикс. Локальные записи останутся на устройстве; эта кнопка не запускает синхронизацию.") },
            confirmButton = { TextButton(enabled = stillCurrent, modifier = Modifier.testTag("confirm_server_change"), onClick = {
                if (stillCurrent) { dispatch(ChronaAction.ConfigureServer(target)); confirmUrl = null }
            }) { Text("Сменить сервер") } },
            dismissButton = { TextButton(onClick = { confirmUrl = null }) { Text("Отмена") } })
    }
}

private data class SyncConsentTarget(val baseUrl: String, val localProfileId: String, val accountId: String?)
private fun ChronaUiState.syncTarget(): SyncConsentTarget? = normalizedPublicServerBaseUrl(connection.baseUrl)?.let {
    SyncConsentTarget(it, profile.localProfileId, profile.accountId)
}
internal fun canRequestManualSync(state: ChronaUiState): Boolean = normalizedPublicServerBaseUrl(state.connection.baseUrl) != null &&
    state.profile.telegramLinked && !state.profile.accountId.isNullOrBlank() &&
    !state.connection.loginPending && !state.busy && state.sync.status != SyncStatus.SYNCING

@Composable
internal fun ManualSyncSettings(state: ChronaUiState, dispatch: (ChronaAction) -> Unit) {
    var consentFor by remember { mutableStateOf<SyncConsentTarget?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Синхронизация · вручную", style = MaterialTheme.typography.titleLarge)
            Text(state.sync.status.label(), modifier = Modifier.testTag("sync_status"))
            Text("Последняя успешная: ${state.sync.lastSuccessAt ?: "ещё не было"}", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("last_sync"))
            Text("Ожидающих операций: ${state.sync.pendingOperations}", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("pending_operations"))
            Text("Вход сам по себе не отправляет локальные записи. Каждый ручной запуск требует отдельного согласия на отправку сохранённому серверу.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = canRequestManualSync(state), onClick = { consentFor = state.syncTarget() },
                modifier = Modifier.testTag("request_sync")) { Text("Синхронизировать…") }
            if (!state.profile.telegramLinked || normalizedPublicServerBaseUrl(state.connection.baseUrl) == null) {
                Text("Сначала настройте сервер и подтвердите вход через Telegram.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    consentFor?.let { target ->
        var checked by remember(target) { mutableStateOf(false) }
        val stillValid = target == state.syncTarget() && canRequestManualSync(state)
        AlertDialog(onDismissRequest = { consentFor = null }, title = { Text("Отправить локальные данные?") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Сервер: ${target.baseUrl}")
                Text("Будут отправлены локальные привычки, планы, ручные записи и измеренные интервалы / разрывы наблюдения. Локальное фото не загружается. Согласие — только на этот ручной запуск.")
                if (!stillValid) Text("Подключение или аккаунт изменились. Закройте диалог и начните заново.", color = MaterialTheme.colorScheme.error)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked, onCheckedChange = { checked = it }, enabled = stillValid, modifier = Modifier.testTag("sync_consent_checkbox"))
                    Text("Разрешаю отправить локальные записи на указанный сервер", style = MaterialTheme.typography.bodySmall)
                }
            }
        }, confirmButton = { TextButton(enabled = checked && stillValid, modifier = Modifier.testTag("confirm_sync"), onClick = {
            if (checked && stillValid) { consentFor = null; dispatch(ChronaAction.RequestSync) }
        }) { Text("Отправить и синхронизировать") } },
            dismissButton = { TextButton(modifier = Modifier.testTag("cancel_sync"), onClick = { consentFor = null }) { Text("Отмена") } })
    }
}

private fun SyncStatus.label(): String = when (this) {
    SyncStatus.LOCAL_ONLY -> "Только локальные данные · отправка не подтверждена"
    SyncStatus.IDLE -> "Нет активной передачи"
    SyncStatus.SYNCING -> "Идёт синхронизация"
    SyncStatus.OFFLINE -> "Нет связи · локальные записи сохранены"
    SyncStatus.ERROR -> "Ошибка синхронизации · успешная отправка не подтверждена"
}
