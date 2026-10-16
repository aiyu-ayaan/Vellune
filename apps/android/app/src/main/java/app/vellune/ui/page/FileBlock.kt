package app.vellune.ui.page

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.vellune.Vellune
import app.vellune.core.Block
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** An attachment: decrypted into the private cache only when tapped, then handed to a viewer app. */
@Composable
fun FileBlock(app: Vellune, pageId: String, b: Block, onDelete: () -> Unit) {
    val src = b.src ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val mime = Images.mimeOf(src)
    val name = b.text.ifBlank { "attachment" }
    Surface(
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.padding(vertical = 4.dp).widthIn(max = 480.dp).fillMaxWidth().clickable(enabled = !busy) {
            busy = true
            scope.launch {
                val bytes = Images.bytes(app, pageId, src)
                busy = false
                if (bytes == null) {
                    Toast.makeText(ctx, "This file isn't available on this device yet.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val file = withContext(Dispatchers.IO) {
                    File(ctx.cacheDir, "attachments").apply { mkdirs() }.resolve(name.replace(Regex("[/\\\\]"), "_")).apply { writeBytes(bytes) }
                }
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
                val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { ctx.startActivity(Intent.createChooser(view, name)) }
                    .onFailure { Toast.makeText(ctx, "No app can open this file.", Toast.LENGTH_SHORT).show() }
            }
        },
    ) {
        Column {
            Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (mime == "application/pdf") Icons.Outlined.PictureAsPdf else Icons.Outlined.AttachFile, null)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(name.substringAfterLast('.', mime.substringAfter('/')).uppercase(), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Remove $name") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
