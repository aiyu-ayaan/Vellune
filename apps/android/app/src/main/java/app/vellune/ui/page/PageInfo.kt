package app.vellune.ui.page

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMade
import androidx.compose.material.icons.automirrored.outlined.CallReceived
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vellune.core.LinkIndex
import app.vellune.core.Page
import app.vellune.core.pageReferences
import app.vellune.ui.AppState
import app.vellune.ui.Dest

/**
 * The right-hand panel, Discord's member list for a page: its outline, tags, who links here and
 * where it links to.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PageInfoPanel(page: Page, index: LinkIndex, pages: Map<String, Page>, nav: AppState, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val headings = remember(page.blocks) { page.blocks.filter { it.kind == "heading" && it.text.isNotBlank() } }
    val backlinks = index.backlinks[page.id].orEmpty()
    val (linked, missing) = index.outgoing[page.id] ?: (emptySet<String>() to emptySet())
    val tags = remember(page) { pageReferences(page).tags }
    val words = remember(page.blocks) { page.blocks.sumOf { b -> b.text.split(Regex("\\s+")).count { it.isNotBlank() } } }
    fun go(id: String) { nav.open(id); onDone() }

    LazyColumn(
        modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End)),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(page.displayTitle, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "$words words · edited ${DateUtils.getRelativeTimeSpanString(page.updatedAt)}",
                    style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                )
            }
        }
        if (tags.isNotEmpty()) section("Tags") {
            item {
                FlowRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { t -> AssistChip({ nav.navigate(Dest.Database(t)); onDone() }, { Text("#$t") }) }
                }
            }
        }
        if (headings.isNotEmpty()) section("Outline") {
            items(headings, key = { "h-" + it.id }) { h ->
                Surface(
                    onClick = { nav.jumpTo = h.id; onDone() }, shape = MaterialTheme.shapes.small, color = colors.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().padding(start = (((h.level ?: 1) - 1) * 12).dp),
                ) {
                    Text(h.text, style = if ((h.level ?: 1) <= 1) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                }
            }
        }
        section("Linked here · ${backlinks.size}") {
            if (backlinks.isEmpty()) item { Empty("No pages link here yet.") }
            items(backlinks, key = { "b-" + it.sourceId + it.context.hashCode() }) { l ->
                pages[l.sourceId]?.let { src -> LinkRow(Icons.AutoMirrored.Outlined.CallReceived, src, l.context) { go(src.id) } }
            }
        }
        section("Links to · ${linked.size + missing.size}") {
            if (linked.isEmpty() && missing.isEmpty()) item { Empty("Type [[ in the page to link another.") }
            items(linked.toList(), key = { "o-$it" }) { id ->
                pages[id]?.let { p -> LinkRow(Icons.AutoMirrored.Outlined.CallMade, p, null) { go(p.id) } }
            }
            items(missing.toList(), key = { "m-$it" }) { name ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.LinkOff, null, Modifier.size(18.dp), tint = colors.outline)
                    Text(name, style = MaterialTheme.typography.bodyMedium, color = colors.outline, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
        section("Details") {
            item {
                val ctx = LocalContext.current
                Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Detail("Created", DateUtils.formatDateTime(ctx, page.createdAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME))
                    Detail("Updated", DateUtils.formatDateTime(ctx, page.updatedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME))
                    if (page.aliases.isNotEmpty()) Detail("Aliases", page.aliases.joinToString(", "))
                }
            }
        }
    }
}

private fun LazyListScope.section(label: String, content: LazyListScope.() -> Unit) {
    item(key = "s-$label") {
        Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 8.dp))
    }
    content()
}

@Composable
private fun LinkRow(icon: ImageVector, p: Page, context: String?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = colors.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(if (p.isEdgeless) Icons.Outlined.Dashboard else Icons.Outlined.Description, null, Modifier.size(18.dp), tint = colors.primary)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(p.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!context.isNullOrBlank()) Text(context, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(icon, null, Modifier.size(16.dp), tint = colors.outline)
        }
    }
}

@Composable
private fun Empty(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 8.dp))

@Composable
private fun Detail(label: String, value: String) = Row {
    Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp).width(80.dp))
    Text(value, style = MaterialTheme.typography.bodyMedium)
}
