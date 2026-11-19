package com.swpp.stylemate.ui.wardrobe

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Consistent selector for basic attributes and recommendation labels; codes stay untouched. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GarmentChoiceField(label: String, choices: List<Pair<String, String>>, selected: List<String>,
    multiple: Int, enabled: Boolean, nullable: Boolean = true, onChange: (List<String>) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val description = choices.filter { it.first in selected }.joinToString(", ") { it.second }.ifEmpty { "선택 안 함" }
    OutlinedButton(onClick = { expanded = true }, enabled = enabled, shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface), contentPadding = PaddingValues(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text("$label: $description", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(20.dp))
    }
    if (expanded && enabled) ModalBottomSheet(onDismissRequest = { expanded = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { expanded = false }) { Text("완료") }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                val rows = if (nullable) listOf("" to "선택 안 함") + choices else choices
                rows.forEach { (code, title) ->
                    val checked = if (code.isEmpty()) selected.isEmpty() else code in selected
                    val available = code.isEmpty() || multiple == 0 || checked || selected.size < multiple
                    val choose = {
                        val next = if (code.isEmpty()) emptyList() else if (multiple == 0) listOf(code)
                            else if (checked) selected - code else selected + code
                        onChange(next)
                        if (multiple == 0) expanded = false
                    }
                    val selection = if (multiple > 0) Modifier.toggleable(checked, enabled = available,
                        role = Role.Checkbox, onValueChange = { choose() })
                        else Modifier.selectable(checked, enabled = available, role = Role.RadioButton, onClick = choose)
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).then(selection).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                            color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (multiple > 0) Checkbox(checked, null, enabled = available)
                        else RadioButton(checked, null, enabled = available)
                    }
                }
            }
        }
    }
}
