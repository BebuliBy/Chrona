package by.bebuliby.chrona.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Material date-picker milliseconds represent a UTC calendar date, not the device's local midnight. */
internal fun datePickerMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
internal fun dateFromPickerMillis(value: Long): LocalDate = Instant.ofEpochMilli(value).atZone(ZoneOffset.UTC).toLocalDate()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarDateField(
    label: String, date: LocalDate?, onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier, initialMonth: LocalDate = date ?: LocalDate.now(), enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
            Text(date?.let(dateFormat::format) ?: "Выбрать дату", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Outlined.DateRange, "Открыть календарь")
        }
    }
    if (open) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = date?.let(::datePickerMillis),
            initialDisplayedMonthMillis = datePickerMillis(initialMonth),
            initialDisplayMode = DisplayMode.Picker,
        )
        DatePickerDialog(onDismissRequest = { open = false }, confirmButton = {
            TextButton(enabled = enabled && picker.selectedDateMillis != null, onClick = {
                picker.selectedDateMillis?.let { onSelect(dateFromPickerMillis(it)); open = false }
            }) { Text("Выбрать") }
        }, dismissButton = { TextButton(onClick = { open = false }) { Text("Отмена") } }) {
            DatePicker(state = picker, showModeToggle = false)
        }
    }
}
