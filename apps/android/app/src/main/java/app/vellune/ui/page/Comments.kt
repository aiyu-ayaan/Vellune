package app.vellune.ui.page

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vellune.core.Comment
import app.vellune.ui.shell.Avatar

/** One block's thread, as on the web: first comment opens it, the rest reply; resolve, delete own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentSheet(all: List<Comment>, blockId: String, meId: String, meName: String, readOnly: Boolean, onChange: (List<Comment>) -> Unit, onDismiss: () -> Unit) {
    val thread = all.filter { it.blockId == blockId }
    val root = thread.firstOrNull { it.parentId == null }
    var text by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (root?.resolved == true) "Comments · resolved" else "Comments", style = MaterialTheme.typography.titleMedium)
            thread.forEach { c ->
                Row(verticalAlignment = Alignment.Top) {
                    Avatar(c.authorName, 28)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.authorName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            Text(
                                DateUtils.getRelativeTimeSpanString(c.createdAt).toString(), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        Text(c.text, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!readOnly && c.id == root?.id) IconButton(onClick = {
                        onChange(all.map { if (it.id == c.id) it.copy(resolved = if (c.resolved == true) null else true) else it })
                    }) { Icon(if (c.resolved == true) Icons.Outlined.Replay else Icons.Outlined.Check, if (c.resolved == true) "Reopen" else "Resolve") }
                    if (!readOnly && c.authorId == meId) IconButton(onClick = {
                        // Deleting the first comment removes the whole thread.
                        onChange(all.filter { it.id != c.id && (c.parentId != null || it.parentId != c.id) })
                    }) { Icon(Icons.Outlined.Delete, "Delete comment") }
                }
                HorizontalDivider()
            }
            if (!readOnly) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text(if (root != null) "Reply…" else "Add a comment…") })
                Button(enabled = text.isNotBlank(), onClick = {
                    val c = Comment(blockId = blockId, parentId = root?.id, authorId = meId, authorName = meName, text = text.trim())
                    // Replying to a resolved thread reopens it.
                    onChange(all.map { if (it.id == root?.id) it.copy(resolved = null) else it } + c)
                    text = ""
                }) { Text(if (root != null) "Reply" else "Comment") }
            }
        }
    }
}
