package app.vellune.ui.database

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.TableRows
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vellune.core.LinkIndex
import app.vellune.core.Page
import app.vellune.core.Workspace
import app.vellune.ui.AppState
import app.vellune.ui.shell.VTopBar
import app.vellune.ui.shell.excerpt

/** Databases: every page (or one tag's pages) as a table or a kanban board by tag, like the web. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DatabaseScreen(ws: Workspace, pages: Map<String, Page>, index: LinkIndex, tag: String?, nav: AppState, onMenu: (() -> Unit)?) {
    var kanban by rememberSaveable { mutableStateOf(false) }
    val live = remember(pages) { pages.values.filter { it.deletedAt == null && !it.isFolder } }
    val shown = remember(live, tag) { (if (tag == null) live else live.filter { tag in it.tags }).sortedByDescending { it.updatedAt } }
    val columns = remember(live) { listOf("Untagged") + live.flatMap { it.tags }.distinct().sorted() }

    Scaffold(
        topBar = {
            VTopBar({
                Column {
                    Text(if (tag != null) "#$tag Collection" else "All Pages Database", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${shown.size} pages in this view", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }, onMenu) {
                Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween), modifier = Modifier.padding(end = 8.dp)) {
                    ToggleButton(!kanban, { kanban = false }, shapes = ButtonGroupDefaults.connectedLeadingButtonShapes()) { Icon(Icons.Outlined.TableRows, "Table") }
                    ToggleButton(kanban, { kanban = true }, shapes = ButtonGroupDefaults.connectedTrailingButtonShapes()) { Icon(Icons.Outlined.ViewColumn, "Kanban") }
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.open(ws.createPage("New Page") { if (tag != null) it.copy(tags = listOf(tag)) else it }.id) },
                icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("New Note") },
            )
        },
    ) { pad ->
        if (!kanban) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 96.dp)) {
                stickyHeader {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text("Name", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            Text("Tags", Modifier.width(120.dp), style = MaterialTheme.typography.labelLarge)
                            Text("Links", Modifier.width(48.dp), style = MaterialTheme.typography.labelLarge)
                            Text("Updated", Modifier.width(84.dp), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                items(shown, key = { it.id }) { p ->
                    Column(Modifier.animateItem()) {
                        Row(
                            Modifier.fillMaxWidth().clickable { nav.open(p.id) }.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(p.displayTitle, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(p.tags.joinToString(" ") { "#$it" }, Modifier.width(120.dp), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${index.backlinks[p.id]?.size ?: 0}", Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium)
                            Text(DateUtils.getRelativeTimeSpanString(p.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString(),
                                Modifier.width(84.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        } else {
            LazyRow(
                Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                items(columns, key = { it }) { col ->
                    val inCol = shown.filter { if (col == "Untagged") it.tags.isEmpty() else col in it.tags }
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.width(260.dp).fillMaxHeight()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(if (col == "Untagged") col else "#$col", style = MaterialTheme.typography.titleSmall)
                            Text("${inCol.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp)) {
                                items(inCol, key = { col + it.id }) { p ->
                                    Card(onClick = { nav.open(p.id) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                        Column(Modifier.padding(12.dp)) {
                                            Text(p.displayTitle, style = MaterialTheme.typography.titleSmall)
                                            val ex = p.excerpt(100)
                                            if (ex.isNotEmpty()) Text(ex, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
