package app.vellune.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vellune.core.Page

/** Live, read-only card for `![[Page]]`, as on the web: the page's first blocks, nesting kept. */
@Composable
fun EmbedCard(name: String, target: Page?, onOpen: () -> Unit, onEdit: () -> Unit) {
    OutlinedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(target?.label ?: name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Edit embed", Modifier.size(18.dp)) }
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.padding(end = 10.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
            if (target == null) Text("No page named “$name” yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            else target.blocks.take(12).filter { it.text.isNotBlank() && it.kind !in setOf("image", "file", "table") }.forEach { b ->
                Text(
                    (if (b.kind == "bullet") "• " else if (b.kind == "todo") (if (b.checked == true) "☑ " else "☐ ") else "") + b.text,
                    style = if (b.kind == "heading") MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3,
                    modifier = Modifier.padding(start = 16.dp * b.depth, top = 2.dp),
                )
            }
        }
    }
}
