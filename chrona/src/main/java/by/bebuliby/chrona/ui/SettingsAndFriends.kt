package by.bebuliby.chrona.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import by.bebuliby.chrona.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.ZoneOffset

private class AvatarImage(val key: String, val cache: RemotePhotoCache?, val bitmap: ImageBitmap?, val failed: Boolean)

/** User-picked local URI or an explicitly bound, policy-approved remote avatar. No gallery scan. */
@Composable
internal fun ProfileAvatar(name: String, photo: ProfilePhoto?, telegramLinked: Boolean, large: Boolean = false) {
    val resolver = LocalContext.current.contentResolver
    val preview = LocalInspectionMode.current
    val policy = LocalChronaRemotePhotoPolicy.current
    val cache = LocalRemotePhotoCache.current
    val uri = photo?.takeIf { it.kind == PhotoKind.LOCAL_URI }?.location
    val remote = remember(photo, telegramLinked, policy) { validatedRemotePhoto(photo, telegramLinked, policy) }
    val key = uri?.let { "local:$it" } ?: remote?.let { "remote:${it.key}" }
    val image by produceState<AvatarImage?>(null, key, resolver, cache, preview) {
        value = null
        if (preview || key == null) return@produceState
        if (uri != null) {
            val bitmap = withContext(Dispatchers.IO) { try {
                val selected = Uri.parse(uri)
                if (selected.scheme != "content") return@withContext null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(selected)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                var sample = 1
                while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                resolver.openInputStream(selected)?.use { BitmapFactory.decodeStream(it, null, options)?.asImageBitmap() }
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null } }
            value = AvatarImage(key, cache, bitmap, bitmap == null)
        } else if (remote != null) {
            when (val result = cache?.load(remote) ?: RemotePhotoResult.Failed) {
                is RemotePhotoResult.Ready -> value = AvatarImage(key, cache, result.image, false)
                RemotePhotoResult.Failed -> value = AvatarImage(key, cache, null, true)
            }
        }
    }
    // A changed URI/binding must never flash the previous account's bitmap for a frame.
    val currentImage = image?.takeIf { it.key == key && it.cache === cache }
    val bitmap = currentImage?.bitmap
    val blocked = !preview && photo?.kind == PhotoKind.TELEGRAM_URL && telegramLinked && remote == null
    val failed = blocked || currentImage?.failed == true
    val loading = !preview && key != null && currentImage == null
    Box(Modifier.size(if (large) 72.dp else 34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
        .semantics { contentDescription = when { failed -> "Фото профиля недоступно"; loading -> "Фото профиля загружается"; else -> "Фото профиля" } },
        contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Text(if (photo?.kind == PhotoKind.TELEGRAM_URL && telegramLinked) "TG" else name.trim().take(1).uppercase().ifEmpty { "C" },
                color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
        if (loading) CircularProgressIndicator(Modifier.fillMaxSize().padding(2.dp), strokeWidth = 2.dp)
        if (failed) Box(Modifier.align(Alignment.BottomEnd).size(14.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error), contentAlignment = Alignment.Center) {
            Text("!", fontSize = 10.sp, color = MaterialTheme.colorScheme.onError)
        }
    }
}

@Composable
internal fun TrackingCard(tracker: TrackerHealth, dispatch: (ChronaAction) -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when (tracker.status) {
                TrackerStatus.NOT_CONNECTED -> "Отслеживание не подключено"
                TrackerStatus.DISABLED -> "Отслеживание выключено"
                TrackerStatus.PERMISSION_REQUIRED -> "Нужно разрешение Android"
                TrackerStatus.OBSERVING -> "Служба наблюдения активна"
                TrackerStatus.UNAVAILABLE -> "Наблюдение недоступно"
            }, style = MaterialTheme.typography.titleSmall)
            Text(when (tracker.observation) {
                ScreenObservation.SHORTS -> "Последнее состояние: экран Shorts"
                ScreenObservation.NOT_SHORTS -> "Последнее состояние: не Shorts. Это не доказательство полного наблюдения дня."
                ScreenObservation.UNKNOWN -> "Последний экран не распознан. Разрыв не считается просмотром."
                null -> "Нет подтверждённого состояния экрана. Это не нулевое использование."
            }, style = MaterialTheme.typography.bodySmall)
            tracker.lastObservedAt?.let { Text("Последнее наблюдение: $it", style = MaterialTheme.typography.labelSmall) }
            tracker.reason?.let { Text("Причина: ${it.name.lowercase()}", style = MaterialTheme.typography.labelSmall) }
            if (tracker.status == TrackerStatus.PERMISSION_REQUIRED) OutlinedButton(onClick = { dispatch(ChronaAction.OpenTrackingPermissionSettings) }) { Text("Открыть разрешения") }
        }
    }
}

@Composable
internal fun SettingsPage(state: ChronaUiState, dispatch: (ChronaAction) -> Unit) {
    val preferences = state.preferences
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ProfileAvatar(state.profile.displayName, state.profile.photo, state.profile.telegramLinked, true)
                        Column(Modifier.weight(1f)) {
                            Text(state.profile.displayName, style = MaterialTheme.typography.titleLarge)
                            Text(if (state.profile.telegramLinked) "Telegram привязан" else "Локальный профиль · Telegram не привязан", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    OutlinedButton(onClick = { dispatch(ChronaAction.ChooseProfilePhoto) }, enabled = !state.busy) { Text("Выбрать своё фото") }
                    Text("Только явно выбранное фото. Локальный URI не отправляется друзьям или на сервер.", style = MaterialTheme.typography.bodySmall)
                    if (state.profile.photo?.kind == PhotoKind.TELEGRAM_URL && state.profile.telegramLinked) {
                        Text("Telegram-фото загружается только с разрешённого HTTPS photo-origin без токенов. При недоступности или запрете показан placeholder; можно выбрать своё локальное фото.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item { ConnectionSettings(state, dispatch) }
        if (state.profile.telegramLinked && !state.profile.accountId.isNullOrBlank()) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Находить мой профиль",style=MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("Разрешить находить меня по имени/нику",Modifier.weight(1f))
                        Switch(checked=state.connection.discoverable,enabled=state.mode == DataMode.CONNECTED && !state.busy && !state.connection.loginPending,
                            onCheckedChange={ dispatch(ChronaAction.ConfigureDiscoverability(it)) },modifier=Modifier.testTag("discoverability"))
                    }
                    Text("По умолчанию выключено. Это только поиск моего профиля в Chrona, не доступ к статистике и не поиск всех пользователей Telegram. Выключение поиска не удаляет существующих друзей/grants.",style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { ManualSyncSettings(state, dispatch) }
        item {
            Text("Оформление", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(preferences.themeMode == mode, onClick = {
                        dispatch(ChronaAction.ChangePreferences(preferences.copy(themeMode = mode)))
                    }, modifier = Modifier.testTag("theme_${mode.name}"), label = {
                        Text(when (mode) { ThemeMode.SYSTEM -> "Система"; ThemeMode.LIGHT -> "Светлая"; ThemeMode.DARK -> "Тёмная" })
                    })
                }
            }
            Text("Палитра", style = MaterialTheme.typography.titleMedium)
        }
        items(ChronaDesign.palettes.chunked(2)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { palette ->
                    FilterChip(preferences.paletteId == palette.id, onClick = {
                        dispatch(ChronaAction.ChangePreferences(preferences.copy(paletteId = palette.id)))
                    }, modifier = Modifier.weight(1f).testTag("palette_${palette.id}"),
                        leadingIcon = { Box(Modifier.size(16.dp).clip(CircleShape).background(palette.lightAccent)) }, label = { Text(palette.title) })
                }
            }
        }
        item {
            Text("Стиль", style = MaterialTheme.typography.titleMedium)
            Column {
                ChronaDesign.styles.forEach { style ->
                    FilterChip(preferences.styleId == style.id, onClick = {
                        dispatch(ChronaAction.ChangePreferences(preferences.copy(styleId = style.id)))
                    }, label = { Text(style.title) }, modifier = Modifier.testTag("style_${style.id}"))
                }
            }
            if (ChronaDesign.palettes.none { it.id == preferences.paletteId } || ChronaDesign.styles.none { it.id == preferences.styleId }) {
                Text("Неизвестный вариант показан с безопасным fallback. Сохранённый ID не перезаписан.", style = MaterialTheme.typography.bodySmall)
            }
            Text("Системное отключение анимаций учитывается автоматически.", style = MaterialTheme.typography.bodySmall)
        }
        item { TrackingCard(state.tracker, dispatch) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Наблюдение Shorts", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(state.tracker.status == TrackerStatus.OBSERVING, enabled = !state.busy,
                    onCheckedChange = { dispatch(ChronaAction.SetTrackingEnabled(it)) }, modifier = Modifier.testTag("tracking_enabled"))
            }
            Text("Разрешение выдаёте вы в Android. Приложение не включает службу вместо вас и не обходит остановку.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { dispatch(ChronaAction.OpenTrackingPermissionSettings) }) { Text("Настройки разрешения Android") }
        }
    }
}

@Composable
internal fun FriendsPage(state: ChronaUiState, dispatch: (ChronaAction) -> Unit) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var readCell by remember { mutableStateOf<DayCell?>(null) }
    var sharingFor by remember { mutableStateOf<SharingConsentTarget?>(null) }
    val friends = state.friends
    val authorized = friends.selected?.takeIf { friends.status != FeatureStatus.NOT_CONNECTED && it.owner.relation == FriendRelation.ACCEPTED }
    Column(Modifier.fillMaxSize()) {
        Button(onClick = { searching = !searching }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("find_friends")) { Text("Найти друзей") }
        if (searching) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, label = { Text("Имя или @ник в Chrona") }, singleLine = true,
                modifier = Modifier.weight(1f).testTag("friend_query"))
            TextButton(enabled = query.isNotBlank() && !state.busy, onClick = { dispatch(ChronaAction.SearchFriends(query.trim())) }) { Text("Найти") }
        }
        if (friends.status == FeatureStatus.NOT_CONNECTED) {
            InfoCard("Telegram и сервер не подключены", "Здесь нет вымышленных друзей. Поиск, запросы и разрешённая статистика появятся после настоящего подключения.", Modifier.padding(16.dp).testTag("friends_not_connected"))
        } else if (authorized != null) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(authorized.owner.displayName, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { readCell = null; dispatch(ChronaAction.CloseFriend) }) { Text("Свернуть") }
            }
            Text("Только разрешённые владельцем данные · только чтение", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
            Box(Modifier.weight(1f)) {
                HistoryPage(authorized.habits, authorized.history, dispatch, readOnly = true, onOpenCell = { id, day ->
                    readCell = authorized.history.cells.find { it.habitId == id && it.day == day }
                }, onSelectHistory = { _, range -> dispatch(ChronaAction.OpenFriend(authorized.owner.accountId, range)) })
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (friends.status == FeatureStatus.LOADING) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (friends.status == FeatureStatus.ERROR) item { InfoCard("Не удалось загрузить друзей", "Нет подтверждения новых данных. Проверьте подключение и повторите поиск.") }
                if (searching) item { Text("Находятся только пользователи Chrona, явно включившие поиск своего профиля в настройках. Это не каталог Telegram и не доступ к статистике.",style=MaterialTheme.typography.bodySmall) }
                if (friends.friends.isEmpty()) item { InfoCard("Список пока пуст", "Взаимная дружба и доступ к активностям требуют подтверждения владельца.") }
                items(friends.friends, key = { it.accountId }) { friend ->
                    FriendRow(friend, state.busy, state.profile.telegramLinked && state.mode == DataMode.CONNECTED, dispatch,
                        onShare = { sharingFor = sharingConsentTarget(state,friend.accountId) }) { dispatch(ChronaAction.OpenFriend(friend.accountId, state.history.range)) }
                }
                if (searching && friends.searchResults.isNotEmpty()) item { Text("Результаты поиска", style = MaterialTheme.typography.titleMedium) }
                if (searching) items(friends.searchResults, key = { "search_${it.accountId}" }) { friend ->
                    FriendRow(friend, state.busy, state.profile.telegramLinked && state.mode == DataMode.CONNECTED, dispatch) { dispatch(ChronaAction.OpenFriend(friend.accountId, state.history.range)) }
                }
            }
        }
    }
    val cell = readCell?.takeIf { chosen -> authorized?.history?.cells?.contains(chosen) == true }
    val habit = authorized?.habits?.find { it.id == cell?.habitId }
    if (authorized != null && cell != null && habit != null) FriendDayDialog(authorized, habit, cell) { readCell = null }
    sharingFor?.let { target -> SharingEditor(state,target,dispatch) { sharingFor = null } }
}

@Composable
private fun FriendRow(friend: FriendSummary, busy: Boolean, remotePhotosEnabled: Boolean, dispatch: (ChronaAction) -> Unit,
                      onShare: (() -> Unit)? = null, open: () -> Unit) {
    Card(onClick = { if (friend.relation == FriendRelation.ACCEPTED) open() }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // A remote friend's local content URI is never opened.
                ProfileAvatar(friend.displayName, friend.photo?.takeIf { it.kind == PhotoKind.TELEGRAM_URL }, remotePhotosEnabled)
                Text(friend.displayName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            }
            when (friend.relation) {
                FriendRelation.ACCEPTED -> {
                    Text("Показать разрешённую статистику →", style = MaterialTheme.typography.labelLarge)
                    if (onShare != null) OutlinedButton(enabled=!busy && remotePhotosEnabled,onClick=onShare,
                        modifier=Modifier.testTag("share_with_${friend.accountId}")) { Text("Поделиться моими привычками…") }
                }
                FriendRelation.OUTGOING -> Text("Запрос отправлен · доступ ещё не открыт")
                FriendRelation.INCOMING -> friend.requestId?.let { id -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !busy, onClick = { dispatch(ChronaAction.RespondToFriendRequest(id, true)) }) { Text("Принять") }
                    TextButton(enabled = !busy, onClick = { dispatch(ChronaAction.RespondToFriendRequest(id, false)) }) { Text("Отклонить") }
                } } ?: Text("Входящий запрос недоступен")
                null -> TextButton(enabled = !busy, onClick = { dispatch(ChronaAction.RequestFriend(friend.accountId)) }) { Text("Добавить в друзья") }
            }
        }
    }
}

@Composable
private fun FriendDayDialog(view: FriendActivityUiState, habit: Habit, cell: DayCell, onDismiss: () -> Unit) {
    val zone = runCatching { ZoneId.of(cell.day.zoneId) }.getOrDefault(ZoneOffset.UTC)
    val start = cell.day.date.atStartOfDay(zone).toInstant()
    val end = cell.day.date.plusDays(1).atStartOfDay(zone).toInstant()
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().fillMaxHeight(.9f)) {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { TextButton(onClick = onDismiss) { Text("Закрыть") } }
                item { Text("${view.owner.displayName} · ${dateFormat.format(cell.day.date)}", style = MaterialTheme.typography.titleMedium) }
                item { HabitTile(habit, cell, onClick = {}) }
                item { Text("Разрешённые данные · только чтение", style = MaterialTheme.typography.labelLarge) }
                item { IntervalTimeline(
                    view.intervals.filter { it.habitId == habit.id && it.startUtc < end && it.endUtc > start },
                    view.gaps.filter { it.habitId == habit.id && it.startUtc < end && (it.endUtc == null || it.endUtc > start) }, cell.day.zoneId,
                ) }
            }
        }
    }
}
