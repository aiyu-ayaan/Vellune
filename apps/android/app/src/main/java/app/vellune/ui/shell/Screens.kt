package app.vellune.ui.shell

import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vellune.core.Page
import app.vellune.core.Workspace
import app.vellune.ui.AppState

/** Standard top bar; shows the drawer button when the sidebar isn't docked. */
@Composable
fun VTopBar(title: @Composable () -> Unit, onMenu: (() -> Unit)?, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}) =
    TopAppBar(
        title = title,
        navigationIcon = { if (onMenu != null) IconButton(onClick = onMenu) { Icon(Icons.Outlined.Menu, "Open sidebar") } },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )

/** A page's text in one line, for previews and search results. */
fun Page.excerpt(max: Int = 220): String =
    blocks.filter { it.kind != "divider" && it.kind != "image" }.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ").trim().take(max)

fun parseColor(hex: String, fallback: Color): Color = runCatching {
    Color(android.graphics.Color.parseColor(if (hex.length == 4) "#" + hex.drop(1).map { "$it$it" }.joinToString("") else hex))
}.getOrDefault(fallback)

/** Strokes of a canvas, fitted into the box: a quick vector thumbnail (no ink engine needed). */
@Composable
fun StrokeThumbnail(page: Page, modifier: Modifier) {
    val strokes = page.canvas.strokes
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (s in strokes) for (i in s.points.indices step 3) {
            val x = s.points[i].toFloat(); val y = s.points.getOrElse(i + 1) { 0.0 }.toFloat()
            minX = minOf(minX, x); minY = minOf(minY, y); maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
        }
        if (minX > maxX) return@Canvas
        val sc = minOf(size.width / (maxX - minX + 1), size.height / (maxY - minY + 1), density)
        val ox = (size.width - (maxX - minX) * sc) / 2
        for (s in strokes) {
            if (s.points.size < 6) continue
            val path = Path()
            for (i in s.points.indices step 3) {
                val x = (s.points[i].toFloat() - minX) * sc + ox
                val y = (s.points.getOrElse(i + 1) { 0.0 }.toFloat() - minY) * sc
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val c = parseColor(s.color, ink).let { if (s.tool == "highlighter") it.copy(alpha = 0.4f) else it }
            drawPath(path, c, style = Stroke(width = (s.width * sc).toFloat().coerceAtLeast(1f), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
fun HomeScreen(ws: Workspace, pages: Map<String, Page>, nav: AppState, onMenu: (() -> Unit)?) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var fab by rememberSaveable { mutableStateOf(false) }
    val recent = remember(pages) { pages.values.filter { it.deletedAt == null && !it.isFolder }.sortedByDescending { it.updatedAt } }
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Vellune") },
                subtitle = { Text("Where thoughts take form") },
                navigationIcon = { if (onMenu != null) IconButton(onClick = onMenu) { Icon(Icons.Outlined.Menu, "Open sidebar") } },
                actions = { IconButton(onClick = { nav.searchOpen = true }) { Icon(Icons.Outlined.Search, "Search") } },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            FloatingActionButtonMenu(
                expanded = fab,
                button = {
                    ToggleFloatingActionButton(checked = fab, onCheckedChange = { fab = it }, modifier = Modifier.semantics { contentDescription = "New" }) {
                        val p = checkedProgress
                        Icon(if (p > 0.5f) Icons.Outlined.Close else Icons.Outlined.Add, null, Modifier.animateIcon({ p }))
                    }
                },
            ) {
                FloatingActionButtonMenuItem({ fab = false; nav.newDoc(edgeless = true) }, { Text("Canvas") }, { Icon(Icons.Outlined.Dashboard, null) })
                FloatingActionButtonMenuItem({ fab = false; nav.newDoc() }, { Text("Doc") }, { Icon(Icons.AutoMirrored.Outlined.NoteAdd, null) })
                FloatingActionButtonMenuItem({ fab = false; nav.open(ws.openToday().id) }, { Text("Today's journal") }, { Icon(Icons.Outlined.CalendarToday, null) })
            }
        },
    ) { pad ->
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Adaptive(180.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalItemSpacing = 12.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = StaggeredGridItemSpan.FullLine) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    item { AssistChip({ nav.newDoc() }, { Text("New doc") }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.NoteAdd, null, Modifier.size(AssistChipDefaults.IconSize)) }) }
                    item { AssistChip({ nav.newDoc(edgeless = true) }, { Text("New canvas") }, leadingIcon = { Icon(Icons.Outlined.Dashboard, null, Modifier.size(AssistChipDefaults.IconSize)) }) }
                    item { AssistChip({ nav.open(ws.openToday().id) }, { Text("Journal") }, leadingIcon = { Icon(Icons.Outlined.CalendarToday, null, Modifier.size(AssistChipDefaults.IconSize)) }) }
                }
            }
            if (recent.isEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine) { EmptyHome() }
            } else {
                item(span = StaggeredGridItemSpan.FullLine) {
                    Text("Recent", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                }
                items(recent, key = { it.id }) { p -> PageCard(p, Modifier.animateItem()) { nav.open(p.id) } }
            }
        }
    }
}

@Composable
private fun PageCard(p: Page, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick, modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (p.canvas.strokes.isNotEmpty()) {
                StrokeThumbnail(p, Modifier.fillMaxWidth().height(if (p.isEdgeless) 132.dp else 80.dp))
                Spacer(Modifier.height(12.dp))
            }
            Text(p.displayTitle, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val ex = p.excerpt()
            if (ex.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(ex, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (p.isEdgeless) Icons.Outlined.Dashboard else Icons.Outlined.Description, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
                Text(DateUtils.getRelativeTimeSpanString(p.updatedAt).toString(), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun EmptyHome() = Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.size(112.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Description, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
    Spacer(Modifier.height(20.dp))
    Text("A blank page awaits", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(6.dp))
    Text("Start a doc, a canvas, or today's journal.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun TrashScreen(ws: Workspace, pages: Map<String, Page>, onMenu: (() -> Unit)?) {
    val trashed = remember(pages) { pages.values.filter { it.deletedAt != null }.sortedByDescending { it.deletedAt } }
    var confirm by remember { mutableStateOf<Page?>(null) }
    Scaffold(topBar = { VTopBar({ Text("Trash") }, onMenu) }) { pad ->
        if (trashed.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("Trash is empty.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 32.dp)) {
            items(trashed, key = { it.id }) { p ->
                ListItem(
                    headlineContent = { Text(p.displayTitle) },
                    supportingContent = { Text("Deleted ${DateUtils.getRelativeTimeSpanString(p.deletedAt ?: 0)}") },
                    leadingContent = { Icon(if (p.isFolder) Icons.Outlined.Folder else Icons.Outlined.Description, null) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { ws.restore(p.id) }) { Icon(Icons.AutoMirrored.Outlined.Undo, "Restore") }
                            IconButton(onClick = { confirm = p }) { Icon(Icons.Outlined.DeleteForever, "Delete forever", tint = MaterialTheme.colorScheme.error) }
                        }
                    },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
    confirm?.let { p ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(Icons.Outlined.DeleteForever, null) },
            title = { Text("Delete forever?") },
            text = { Text("“${p.displayTitle}” is removed from every device and the server. This can't be undone.") },
            confirmButton = { TextButton({ ws.purge(p.id); confirm = null }) { Text("Delete forever") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun SearchDialog(pages: Map<String, Page>, onDismiss: () -> Unit, onOpen: (String) -> Unit, onCreate: (String) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val hits = remember(pages, q) {
        val live = pages.values.filter { it.deletedAt == null && !it.isFolder }
        if (q.isBlank()) live.sortedByDescending { it.updatedAt }.take(12)
        else live.mapNotNull { p ->
            val t = p.title.contains(q, true)
            val body = p.blocks.firstOrNull { it.text.contains(q, true) }
            when {
                t -> p to 2
                p.aliases.any { it.contains(q, true) } || p.tags.any { it.contains(q, true) } -> p to 1
                body != null -> p to 0
                else -> null
            }
        }.sortedWith(compareByDescending<Pair<Page, Int>> { it.second }.thenByDescending { it.first.updatedAt }).map { it.first }.take(40)
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp,
            modifier = Modifier.widthIn(max = 640.dp).heightIn(max = 560.dp)) {
            Column {
                TextField(
                    value = q, onValueChange = { q = it }, singleLine = true,
                    placeholder = { Text("Search or create a page") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant, unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                LazyColumn {
                    if (q.isNotBlank() && hits.none { it.title.equals(q.trim(), true) }) item {
                        ListItem(
                            headlineContent = { Text("Create “${q.trim()}”") },
                            leadingContent = { Icon(Icons.Outlined.Add, null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { onCreate(q.trim()) },
                        )
                    }
                    items(hits, key = { it.id }) { p ->
                        ListItem(
                            headlineContent = { Text(p.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = p.excerpt(120).takeIf { it.isNotEmpty() }?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                            leadingContent = { Icon(if (p.isEdgeless) Icons.Outlined.Dashboard else Icons.Outlined.Description, null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { onOpen(p.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RenameDialog(p: Page, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var t by rememberSaveable { mutableStateOf(p.title) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (p.isFolder) "Rename folder" else "Rename") },
        text = { OutlinedTextField(t, { t = it }, singleLine = true, shape = MaterialTheme.shapes.large, modifier = Modifier.focusRequester(focus)) },
        confirmButton = { TextButton({ onDone(t.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun MoveDialog(ws: Workspace, p: Page, onDismiss: () -> Unit, onMove: (String?) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    val blocked = remember(p) { ws.descendants(p.id).toSet() + p.id }
    val folders = remember(q) {
        ws.pages.value.values.filter { it.deletedAt == null && it.id !in blocked && (it.isFolder || ws.children(it.id).isNotEmpty() || true) }
            .filter { q.isBlank() || it.displayTitle.contains(q, true) }
            .sortedWith(compareByDescending<Page> { it.isFolder }.thenBy { it.displayTitle.lowercase() })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move “${p.displayTitle}” to") },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text("Search folders and docs") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item {
                        ListItem({ Text("Workspace root") }, leadingContent = { Icon(Icons.Outlined.Folder, null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent), modifier = Modifier.clickable { onMove(null) })
                    }
                    items(folders, key = { it.id }) { f ->
                        ListItem({ Text(f.displayTitle) }, leadingContent = { Icon(if (f.isFolder) Icons.Outlined.Folder else Icons.Outlined.Description, null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent), modifier = Modifier.clickable { onMove(f.id) })
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { OutlinedButton(onDismiss) { Text("Cancel") } },
    )
}
