package app.vellune.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate

private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")

/** Typed page properties (same rules as the web): value type decides the editor. */
@Composable
fun PropsEditor(props: Map<String, Any>, onChange: (Map<String, Any>) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        props.forEach { (k, v) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(k, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.width(110.dp))
                when (v) {
                    is Boolean -> Checkbox(v, { onChange(props + (k to it)) }, Modifier.size(32.dp))
                    is Double -> PlainField(if (v % 1.0 == 0.0) v.toLong().toString() else v.toString(), KeyboardType.Decimal, Modifier.weight(1f)) {
                        it.toDoubleOrNull()?.let { d -> onChange(props + (k to d)) }
                    }
                    else -> PlainField(v.toString(), if (DATE.matches(v.toString())) KeyboardType.Number else KeyboardType.Text, Modifier.weight(1f)) {
                        onChange(props + (k to it))
                    }
                }
                IconButton(onClick = { onChange(props - k) }, Modifier.size(32.dp)) { Icon(Icons.Outlined.Close, "Remove $k", Modifier.size(16.dp)) }
            }
        }
        if (adding) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlainField(name, KeyboardType.Text, Modifier.widthIn(min = 100.dp).padding(end = 8.dp), placeholder = "Name") { name = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf<Pair<String, Any>>("Text" to "", "Number" to 0.0, "Checkbox" to false, "Date" to LocalDate.now().toString()).forEach { (label, init) ->
                    FilterChip(false, {
                        val n = name.trim()
                        if (n.isNotEmpty() && n !in props) onChange(props + (n to init))
                        name = ""; adding = false
                    }, { Text(label) }, enabled = name.isNotBlank())
                }
            }
        } else {
            TextButton(onClick = { adding = true }) { Text("+ Add property") }
        }
    }
}

@Composable
private fun PlainField(value: String, type: KeyboardType, modifier: Modifier, placeholder: String = "Empty", onValue: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    BasicTextField(
        text, { text = it; onValue(it) }, singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = type),
        modifier = modifier.padding(vertical = 8.dp),
        decorationBox = { inner -> if (text.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline); inner() },
    )
}
