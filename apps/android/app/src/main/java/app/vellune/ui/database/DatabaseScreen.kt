package app.vellune.ui.database

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallReceived
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.TableRows
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vellune.core.LinkIndex
import app.vellune.core.Page
import app.vellune.core.Workspace
import app.vellune.ui.AppState
import app.vellune.ui.Dest
import app.vellune.ui.shell.EmptyState
import app.vellune.ui.shell.VTopBar
import app.vellune.ui.shell.excerpt

/** Databases: every page (or one tag's pages) as a table or a board by tag, like the web. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DatabaseScreen(ws: Workspace, pages: Map<String, Page>, index: LinkIndex, tag: String?, nav: AppState, onMenu: (() -> Unit)?) {
    var board by rememberSaveable { mutableStateOf(false) }
    val live = remember(pages) { pages.values.filter { it.deletedAt == null && !it.isFolder } }
    // Tags come from the link index: frontmatter and inline #tags alike.
    val tagged = index.tags
    val shown = remember(live, tag, tagged) {
        (if (tag == null) live else live.filter { it.id in tagged[tag].orEmpty() }).sortedByDescending { it.updatedAt }
    }
    val tags = remember(tagged) { tagged.keys.sorted() }
    val tagsOf = remember(tagged) { tagged.entries.flatMap { (t, ids) -> ids.map { it to t } }.groupBy({ it.first }, { it.second }) }
    // Board lanes: tags, or the values of a text/checkbox property (null = tags).
    var groupBy by rememberSaveable { mutableStateOf<String?>(null) }
    val groupable = remember(shown) {
        shown.flatMap { p -> p.props.filterValues { it is String || it is Boolean }.keys }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }
    }
    val prop = groupBy?.takeIf { it in groupable }
    val isCheck = prop != null && shown.any { it.props[prop] is Boolean }
    val lanes: List<Lane> = remember(tags, shown, prop, isCheck) {
        when {
            prop == null -> listOf(Lane(NONE, "Untagged")) + tags.map { Lane(it, "#$it") }
            isCheck -> listOf(Lane("true", "Yes"), Lane("false", "No"))
            else -> listOf(Lane(NONE, "No value")) + shown.mapNotNull { (it.props[prop] as? String)?.takeIf(String::isNotEmpty) }.distinct().sorted().map { Lane(it, it) }
        }
    }
    fun inLane(p: Page, lane: String): Boolean = when {
        prop == null -> tagsOf[p.id].orEmpty().let { if (lane == NONE) it.isEmpty() else lane in it }
        isCheck -> ((p.props[prop] as? Boolean) == true).toString() == lane
        else -> (p.props[prop] as? String).let { if (lane == NONE) it.isNullOrEmpty() else it == lane }
    }
    val moveTo: ((Page, String) -> Unit)? = prop?.let { k ->
        { p: Page, lane: String ->
            ws.updatePage(p.id) { it.copy(props = if (isCheck) it.props + (k to (lane == "true")) else if (lane == NONE) it.props - k else it.props + (k to lane)) }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            VTopBar(
                icon = if (tag != null) Icons.Outlined.Tag else Icons.Outlined.Storage,
                title = if (tag != null) tag else "All pages",
                subtitle = "${shown.size} ${if (shown.size == 1) "page" else "pages"} · ${if (board) "board" else "table"}",
                onMenu = onMenu,
            ) { ViewSwitch(board) { board = it } }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.open(ws.createPage("New page") { if (tag != null) it.copy(tags = listOf(tag)) else it }.id) },
                icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("New page") },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())) {
            // Collections: one chip per tag, like switching channels.
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(tag == null, { nav.navigate(Dest.Database()) }, { Text("All") }, shape = CircleShape) }
                items(tags, key = { it }) { t ->
                    FilterChip(tag == t, { nav.navigate(Dest.Database(t)) }, { Text("#$t") }, shape = CircleShape)
                }
            }
            AnimatedContent(board, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "view", modifier = Modifier.weight(1f)) { b ->
                when {
                    shown.isEmpty() -> EmptyState(Icons.Outlined.Storage, "Nothing here yet", "Pages tagged #${tag ?: "anything"} gather in this view.")
                    b -> Column {
                        if (groupable.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                            item { FilterChip(prop == null, { groupBy = null }, { Text("By tag") }) }
                            items(groupable, key = { "g-$it" }) { k -> FilterChip(prop == k, { groupBy = k }, { Text("By $k") }) }
                        }
                        Board(lanes, shown, ::inLane, moveTo, nav)
                    }
                    else -> Table(shown, index, tagsOf, nav)
                }
            }
        }
    }
}

@Composable
private fun ViewSwitch(board: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.padding(end = 8.dp).clip(CircleShape).background(colors.surfaceContainerHigh).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(false to Icons.Outlined.TableRows, true to Icons.Outlined.ViewColumn).forEach { (mode, icon) ->
            val on = mode == board
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(if (on) colors.primaryContainer else Color.Transparent)
                    .selectable(on, role = Role.Tab) { onChange(mode) }
                    .semantics { contentDescription = if (mode) "Board" else "Table" },
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, Modifier.size(18.dp), tint = if (on) colors.onPrimaryContainer else colors.onSurfaceVariant) }
        }
    }
}

private fun ago(t: Long) =
    DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

/** Phones get two-line rows; wider windows a real table with columns. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Table(shown: List<Page>, index: LinkIndex, tagsOf: Map<String, List<String>>, nav: AppState) = BoxWithConstraints(Modifier.fillMaxSize()) {
    val colors = MaterialTheme.colorScheme
    val wide = maxWidth >= 600.dp
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 104.dp)) {
        if (wide) stickyHeader {
            Row(
                Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Header("Name", Modifier.weight(1f))
                Header("Properties", Modifier.width(200.dp))
                Header("Tags", Modifier.width(180.dp))
                Header("Backlinks", Modifier.width(88.dp))
                Header("Updated", Modifier.width(96.dp))
            }
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
        }
        items(shown, key = { it.id }) { p ->
            val links = index.backlinks[p.id]?.size ?: 0
            val ptags = tagsOf[p.id].orEmpty()
            Row(
                Modifier
                    .animateItem()
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { nav.open(p.id) }
                    .padding(horizontal = if (wide) 16.dp else 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PageGlyph(p)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(p.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!wide) {
                        val meta = listOfNotNull(
                            ptags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" },
                            propsLine(p).takeIf { it.isNotEmpty() },
                            if (links > 0) "$links backlinks" else null,
                            ago(p.updatedAt),
                        ).joinToString(" · ")
                        Text(meta, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (wide) {
                    Text(propsLine(p), Modifier.width(200.dp).padding(end = 8.dp), style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(ptags.joinToString(" ") { "#$it" }, Modifier.width(180.dp), style = MaterialTheme.typography.labelLarge,
                        color = colors.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.width(88.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Outlined.CallReceived, null, Modifier.size(14.dp), tint = colors.outline)
                        Text("$links", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
                    }
                    Text(ago(p.updatedAt), Modifier.width(96.dp), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Header(text: String, modifier: Modifier) = Text(
    text.uppercase(), modifier, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun PageGlyph(p: Page) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(36.dp).clip(MaterialTheme.shapes.small).background(colors.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        if (p.icon != null) Text(p.icon, style = MaterialTheme.typography.titleMedium)
        else Icon(if (p.isEdgeless) Icons.Outlined.Dashboard else Icons.Outlined.Description, null, Modifier.size(18.dp), tint = colors.primary)
    }
}

private const val NONE = "\u0000none"

private data class Lane(val key: String, val label: String)

private fun propsLine(p: Page) = p.props.entries.joinToString(" · ") { (k, v) ->
    "$k: " + when (v) { is Boolean -> if (v) "✓" else "✗"; is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString(); else -> v.toString() }
}

/** Kanban lanes (tags or a property's values) with a count badge; long-press a card to move it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Board(lanes: List<Lane>, shown: List<Page>, inLane: (Page, String) -> Boolean, moveTo: ((Page, String) -> Unit)?, nav: AppState) {
    val colors = MaterialTheme.colorScheme
    LazyRow(
        Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
    ) {
        items(lanes, key = { it.key }) { lane ->
            val col = lane.key
            val inCol = shown.filter { inLane(it, col) }
            Surface(color = colors.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.width(280.dp).fillMaxHeight()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) {
                        Text(lane.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.clip(CircleShape).background(colors.secondaryContainer).padding(horizontal = 8.dp, vertical = 2.dp)) {
                            Text("${inCol.size}", style = MaterialTheme.typography.labelMedium, color = colors.onSecondaryContainer)
                        }
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 88.dp)) {
                        items(inCol, key = { col + it.id }) { p ->
                            var menu by remember { mutableStateOf(false) }
                            Card(
                                shape = MaterialTheme.shapes.medium,
                                colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
                                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                                    .combinedClickable(onClick = { nav.open(p.id) }, onLongClick = { if (moveTo != null) menu = true }),
                            ) {
                                DropdownMenu(menu, { menu = false }) {
                                    lanes.filter { it.key != col }.forEach { l ->
                                        DropdownMenuItem({ Text("Move to ${l.label}") }, { menu = false; moveTo?.invoke(p, l.key) })
                                    }
                                }
                                Column(Modifier.padding(14.dp)) {
                                    Text(listOfNotNull(p.icon, p.displayTitle).joinToString(" "), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    val ex = p.excerpt(120)
                                    if (ex.isNotEmpty()) Text(ex, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                        maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                                    if (p.props.isNotEmpty()) Text(propsLine(p), style = MaterialTheme.typography.labelSmall, color = colors.tertiary,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                    Text(ago(p.updatedAt), style = MaterialTheme.typography.labelSmall, color = colors.outline, modifier = Modifier.padding(top = 8.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
