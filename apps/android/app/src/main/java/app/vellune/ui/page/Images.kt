package app.vellune.ui.page

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.vellune.Vellune
import app.vellune.core.Block
import app.vellune.core.Crypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Image blocks, same storage rules as the web (packages/ui/src/cloud/blobs.ts). */
object Images {
    private const val PREFIX = "vblob:"
    private const val INLINE_MAX = 1_500_000
    private const val UPLOAD_MAX = 6_000_000
    // shortcut: in-memory cache only, refetched each launch; add a disk cache for offline viewing.
    private val cache = ConcurrentHashMap<String, Bitmap>()

    /**
     * Cloud pages: the image is sealed with the page key and uploaded, referenced as `vblob:<id>:<mime>`.
     * Pages not in the cloud keep it inline as a data URL (up to 1.5 MB). Null if it can't be stored.
     */
    suspend fun importImage(app: Vellune, pageId: String, uri: Uri): String? = withContext(Dispatchers.IO) {
        val cr = app.contentResolver
        val mime = cr.getType(uri) ?: "image/jpeg"
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
        val key = app.sync.pageKey(pageId)
        if (key != null) {
            runCatching { "$PREFIX${app.api.uploadBlob(pageId, Crypto.seal(key, bytes))}:$mime" }.getOrNull()?.let { return@withContext it }
        }
        if (bytes.size > INLINE_MAX) null else "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    suspend fun load(app: Vellune, pageId: String, src: String): Bitmap? = cache[src] ?: withContext(Dispatchers.IO) {
        val bytes = bytes(app, pageId, src) ?: return@withContext null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { cache[src] = it }
    }

    /** Decrypted bytes of a `vblob:` ref or a data URL; null when unavailable on this device. */
    suspend fun bytes(app: Vellune, pageId: String, src: String): ByteArray? = withContext(Dispatchers.IO) {
        when {
            src.startsWith("data:") -> Base64.decode(src.substringAfter(","), Base64.DEFAULT)
            src.startsWith(PREFIX) -> {
                val id = src.removePrefix(PREFIX).substringBefore(':')
                val key = app.sync.pageKey(pageId) ?: return@withContext null
                runCatching { Crypto.open(key, app.api.blob(pageId, id)) }.getOrNull()
            }
            else -> null
        }
    }

    fun mimeOf(src: String): String =
        if (src.startsWith(PREFIX)) src.removePrefix(PREFIX).substringAfter(':') else src.substringAfter("data:").substringBefore(';')

    /** Any file, stored like an image (sealed upload, else inline up to 1.5 MB); null if it can't be. */
    suspend fun importFile(app: Vellune, pageId: String, uri: Uri): Pair<String, String>? = withContext(Dispatchers.IO) {
        val cr = app.contentResolver
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val name = cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "attachment"
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
        if (bytes.size > UPLOAD_MAX) return@withContext null
        val key = app.sync.pageKey(pageId)
        if (key != null) {
            runCatching { "$PREFIX${app.api.uploadBlob(pageId, Crypto.seal(key, bytes))}:$mime" }.getOrNull()?.let { return@withContext name to it }
        }
        if (bytes.size > INLINE_MAX) null else name to "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}

@Composable
fun BlobImage(app: Vellune, pageId: String, b: Block, onDelete: () -> Unit) {
    val src = b.src.orEmpty()
    val state by produceState<Result<Bitmap?>?>(null, src) { value = runCatching { Images.load(app, pageId, src) } }
    Box(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLow).heightIn(min = 120.dp),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = state?.getOrNull()
        when {
            state == null -> LoadingIndicator()
            bmp != null -> Image(bmp.asImageBitmap(), b.text.ifBlank { "Image" }, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            else -> Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.BrokenImage, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.outline)
                Text("Image unavailable offline", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 56.dp))
            }
        }
        FilledTonalIconButton(onClick = onDelete, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) { Icon(Icons.Outlined.Delete, "Delete image") }
    }
}
