package by.bebuliby.chrona.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import by.bebuliby.chrona.domain.*

/** New absolute set with explicit dates. Blank dates/unchecked consent NEVER dispatch. */
@Composable
internal fun SharingEditor(state: ChronaUiState, target: SharingConsentTarget, dispatch: (ChronaAction) -> Unit, dismiss: () -> Unit) {
    var selected by remember(target) { mutableStateOf(emptySet<String>()) }
    var from by remember(target) { mutableStateOf("") }
    var to by remember(target) { mutableStateOf("") }
    var zone by remember(target) { mutableStateOf(state.today.zoneId) }
    var consent by remember(target,selected,from,to,zone) { mutableStateOf(false) }
    val current = sharingConsentTarget(state,target.friendId) == target
    val command = confirmedSharingAction(state,target,selected,from,to,zone,consent)
    Dialog(onDismissRequest = dismiss) {
        Surface(shape = MaterialTheme.shapes.large,modifier = Modifier.fillMaxWidth().fillMaxHeight(.9f)) {
            Column(Modifier.padding(16.dp),verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Поделиться моими привычками",style = MaterialTheme.typography.titleLarge)
                LazyColumn(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Text("Выбранные привычки заменят прежний набор доступа. Пустой выбор отзовёт доступ. Друг увидит значения и точные интервалы за выбранный период.")
                        Text("Друг: ${friendDisplayName(state.friends.friends.find { it.accountId == target.friendId })}")
                    }
                    item { CalendarDateField("С даты включительно", parseDate(from), onSelect = { from = it.toString() },
                        modifier = Modifier.fillMaxWidth().testTag("sharing_from"), initialMonth = parseDate(from) ?: state.today.date, enabled = current) }
                    item { CalendarDateField("До даты (не включая)", parseDate(to), onSelect = { to = it.toString() },
                        modifier = Modifier.fillMaxWidth().testTag("sharing_to"), initialMonth = parseDate(to) ?: parseDate(from) ?: state.today.date, enabled = current) }
                    item { OutlinedTextField(zone,{ zone = it },singleLine=true,label={ Text("Часовой пояс") },modifier=Modifier.fillMaxWidth().testTag("sharing_zone")) }
                    items(state.habits,key={ it.id }) { habit ->
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(habit.id in selected,onCheckedChange={ checked -> selected = if (checked) selected + habit.id else selected - habit.id },enabled=current,
                                modifier=Modifier.testTag("share_habit_${habit.id}"))
                            Text(habit.title + if (habit.archivedAt != null) " · архив" else "",Modifier.weight(1f))
                        }
                    }
                    item {
                        if (!current) Text("Подключение изменилось. Откройте форму заново.",color=MaterialTheme.colorScheme.error)
                        if ((from.isNotBlank() || to.isNotBlank()) && explicitSharingWindow(from,to,zone) == null) Text("Проверьте даты и часовой пояс.",style=MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(consent,onCheckedChange={ consent = it },enabled=current,modifier=Modifier.testTag("sharing_consent"))
                            Text("Подтверждаю замену набора и указанный период доступа",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick=dismiss) { Text("Отмена") }
                    Button(enabled=command != null,onClick={
                        confirmedSharingAction(state,target,selected,from,to,zone,consent)?.let { dispatch(it); dismiss() }
                    },modifier=Modifier.testTag("save_sharing")) { Text(if (selected.isEmpty()) "Отозвать доступ" else "Заменить набор") }
                }
            }
        }
    }
}
