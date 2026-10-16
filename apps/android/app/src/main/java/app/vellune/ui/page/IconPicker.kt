package app.vellune.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Same set as the web picker (packages/ui/src/components/PageIcon.tsx).
private val GROUPS = listOf(
    "Common" to "📄 📝 📌 📎 📚 📖 🗂️ 📁 🗒️ 📋 ✅ ☑️ 💡 🧠 🎯 🚀 ⭐ 🔥 ⚡ 🌙",
    "Work" to "💼 📊 📈 📉 🗓️ ⏰ 🧾 🧮 🔧 🛠️ ⚙️ 🧪 🔬 💻 🖥️ 📱 🔒 🔑 🧩 🏗️",
    "Life" to "🏠 🍳 ☕ 🍎 🏃 🧘 🎵 🎨 🎬 📷 ✈️ 🌍 🌱 🌸 🐱 🐶 ❤️ 🎁 🎉 😊",
    "Symbols" to "❓ ❗ ⚠️ ✨ 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🔺 🔷 ➕ ♻️ 🏷️ 🔖 📍 🧭",
)

/** Page header line above the title: the page's emoji, or an "Add icon" button. */
@Composable
fun PageIconHeader(icon: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    if (icon != null) {
        Surface(onClick = { open = true }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.semantics { contentDescription = "Change page icon" }) {
            Text(icon, fontSize = 52.sp, modifier = Modifier.padding(4.dp))
        }
    } else {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Outlined.EmojiEmotions, null, Modifier.size(18.dp))
            Text("Add icon", modifier = Modifier.padding(start = 6.dp))
        }
    }
    if (open) IconSheet(icon, onDismiss = { open = false }) { onPick(it); open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IconSheet(current: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Page icon", style = MaterialTheme.typography.titleMedium)
                if (current != null) TextButton(onClick = { onPick(null) }) { Text("Remove") }
            }
            LazyVerticalGrid(GridCells.Adaptive(48.dp), Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                GROUPS.forEach { (label, emojis) ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    }
                    items(emojis.split(' ')) { e ->
                        Surface(
                            onClick = { onPick(e) }, shape = MaterialTheme.shapes.small,
                            color = if (e == current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.padding(2.dp).size(44.dp).semantics { contentDescription = "Use $e as icon" },
                        ) { Box(contentAlignment = Alignment.Center) { Text(e, fontSize = 24.sp) } }
                    }
                }
            }
        }
    }
}
