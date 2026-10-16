package app.vellune.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vellune.core.Connection
import app.vellune.core.Page
import app.vellune.core.SessionState
import app.vellune.core.Status
import app.vellune.core.SyncState
import app.vellune.core.Workspace
import app.vellune.ui.Dest

/** What the sidebar asks the shell to do. */
class SidebarActions(
    val navigate: (Dest) -> Unit,
    val search: () -> Unit,
    val newDoc: (parentId: String?) -> Unit,
    val newCanvas: () -> Unit,
    val newFolder: (parentId: String?) -> Unit,
    val rename: (Page) -> Unit,
    val moveTo: (Page) -> Unit,
    val signOut: () -> Unit,
    val signIn: () -> Unit,
    val toggleDynamicColor: () -> Unit,
)

/**
 * Discord's layout for a knowledge base: a rail of places (home, journal, brain view, databases,
 * trash) beside a list of pages, grouped in collapsible categories like channels.
 */
@Composable
fun Sidebar(
    ws: Workspace,
    pages: Map<String, Page>,
    current: Dest,
    session: SessionState,
    sync: SyncState,
    tags: List<String>,
    expanded: Set<String>,
    onExpand: (String, Boolean) -> Unit,
    renamingId: String?,
    onRenamed: (Page, String?) -> Unit,
    dynamicColor: Boolean,
    a: SidebarActions,
    modifier: Modifier = Modifier,
) {
    val currentPage = (current as? Dest.Doc)?.let { pages[it.id] }
    val trashCount = remember(pages) { pages.values.count { it.deletedAt != null } }
    val insets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)
    Row(modifier.fillMaxHeight()) {
        Column(
            Modifier
                .width(72.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .windowInsetsPadding(insets)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RailItem("Home", current == Dest.Home, logo = true) { a.navigate(Dest.Home) }
            Box(Modifier.width(32.dp).height(2.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
            RailItem("Today's journal", currentPage?.journal != null, Icons.Outlined.CalendarToday) { a.navigate(Dest.Doc(ws.openToday().id)) }
            RailItem("Brain view", current == Dest.Graph, Icons.Outlined.Hub) { a.navigate(Dest.Graph) }
            RailItem("Databases", current is Dest.Database, Icons.Outlined.Storage) { a.navigate(Dest.Database()) }
            RailItem("Trash", current == Dest.Trash, Icons.Outlined.Delete, badge = trashCount) { a.navigate(Dest.Trash) }
            Spacer(Modifier.weight(1f))
            RailItem("New doc", false, Icons.Outlined.Add, accent = true) { a.newDoc(null) }
            AccountButton(session, dynamicColor, a)
        }
        Column(Modifier.weight(1f).fillMaxHeight().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))) {
            Header(session, sync)
            PageList(ws, pages, current, tags, expanded, onExpand, renamingId, onRenamed, a)
        }
    }
}

@Composable
private fun PageList(
    ws: Workspace,
    pages: Map<String, Page>,
    current: Dest,
    tags: List<String>,
    expanded: Set<String>,
    onExpand: (String, Boolean) -> Unit,
    renamingId: String?,
    onRenamed: (Page, String?) -> Unit,
    a: SidebarActions,
) {
    val favorites = remember(pages) { pages.values.filter { it.favorite && it.deletedAt == null && !it.isFolder }.sortedBy { it.displayTitle.lowercase() } }
    val activeId = (current as? Dest.Doc)?.id
    val rows = remember(pages, expanded) { flatten(ws, null, 0, expanded) }
    var collapsed by rememberSaveable { mutableStateOf(setOf<String>()) }
    fun toggle(s: String) { collapsed = if (s in collapsed) collapsed - s else collapsed + s }
    val list = rememberLazyListState()
    // Renaming happens in the tree: make sure its row is shown and on screen.
    LaunchedEffect(renamingId, rows) {
        val row = rows.indexOfFirst { it.page.id == renamingId }
        if (row < 0) return@LaunchedEffect
        collapsed = collapsed - "Pages"
        // Items before the tree: search, the favorites header (+ rows when open), the Pages header.
        val favs = if (favorites.isEmpty()) 0 else 1 + if ("Favorites" in collapsed) 0 else favorites.size
        val at = 1 + favs + 1 + row
        if (list.layoutInfo.visibleItemsInfo.none { it.index == at }) list.animateScrollToItem(at)
    }

    LazyColumn(Modifier.fillMaxWidth(), state = list, contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp)) {
        item(key = "search") {
            Surface(
                onClick = a.search, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp).height(44.dp),
            ) {
                Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Search or jump to…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
        if (favorites.isNotEmpty()) category("Favorites", "Favorites" in collapsed, { toggle("Favorites") }) {
            items(favorites, key = { "fav-" + it.id }) { p ->
                TreeRow(p, 0, p.id == activeId, false, false, {}, a, ws, Modifier.animateItem())
            }
        }
        category("Pages", "Pages" in collapsed, { toggle("Pages") }, action = {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }, Modifier.size(32.dp)) { Icon(Icons.Outlined.Add, "New page or folder", Modifier.size(18.dp)) }
                DropdownMenu(menu, { menu = false }, shape = MaterialTheme.shapes.large) {
                    DropdownMenuItem({ Text("New doc") }, { menu = false; a.newDoc(null) }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.NoteAdd, null) })
                    DropdownMenuItem({ Text("New canvas") }, { menu = false; a.newCanvas() }, leadingIcon = { Icon(Icons.Outlined.Dashboard, null) })
                    DropdownMenuItem({ Text("New folder") }, { menu = false; a.newFolder(null) }, leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) })
                }
            }
        }) {
            if (rows.isEmpty()) item(key = "pages-empty") {
                Text("No pages yet. Tap + to start one.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            items(rows, key = { "tree-" + it.page.id }) { r ->
                TreeRow(r.page, r.depth, r.page.id == activeId, r.hasKids, r.open, { onExpand(r.page.id, !r.open) }, a, ws, Modifier.animateItem(),
                    renaming = r.page.id == renamingId, onRenamed = { onRenamed(r.page, it) })
            }
        }
        if (tags.isNotEmpty()) category("Tags", "Tags" in collapsed, { toggle("Tags") }) {
            items(tags, key = { "tag-$it" }) { t ->
                ChannelRow(Icons.Outlined.Tag, t, current is Dest.Database && current.tag == t, Modifier.animateItem()) { a.navigate(Dest.Database(t)) }
            }
        }
    }
}

/** A Discord category: an all-caps header that folds its rows away. */
private fun LazyListScope.category(
    label: String,
    folded: Boolean,
    onToggle: () -> Unit,
    action: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    item(key = "section-$label") {
        val turn by animateFloatAsState(if (folded) -90f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "fold")
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .heightIn(min = 36.dp)
                .clip(MaterialTheme.shapes.small)
                .clickable(role = Role.Button, onClickLabel = if (folded) "Expand" else "Collapse", onClick = onToggle)
                .semantics { stateDescription = if (folded) "Collapsed" else "Expanded" }
                .padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(16.dp).rotate(turn), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 4.dp))
            action?.invoke()
        }
    }
    if (!folded) content()
}

private class TreeEntry(val page: Page, val depth: Int, val hasKids: Boolean, val open: Boolean)

/** The visible rows of the folder/doc tree: children of expanded folders only. */
private fun flatten(ws: Workspace, parent: String?, depth: Int, expanded: Set<String>): List<TreeEntry> =
    ws.children(parent).flatMap { p ->
        val hasKids = ws.children(p.id).isNotEmpty()
        val open = p.id in expanded
        listOf(TreeEntry(p, depth, hasKids, open)) + if (open && hasKids) flatten(ws, p.id, depth + 1, expanded) else emptyList()
    }

@Composable
private fun Header(session: SessionState, sync: SyncState) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 18.dp, bottom = 10.dp)) {
        Text(session.user?.name?.let { "$it’s space" } ?: "Vellune", style = MaterialTheme.typography.titleLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        SyncLabel(session, sync)
    }
}

/**
 * A place on the rail. Like Discord's server icons: a circle that settles into a rounded square
 * when selected, with a pill on the edge that grows from a dot.
 */
@Composable
private fun RailItem(
    label: String,
    selected: Boolean,
    icon: ImageVector? = null,
    logo: Boolean = false,
    accent: Boolean = false,
    badge: Int = 0,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<androidx.compose.ui.unit.Dp>()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(if (selected || pressed) 16.dp else 24.dp, spatial, label = "corner")
    val pill by animateDpAsState(if (selected) 36.dp else if (pressed) 18.dp else 0.dp, spatial, label = "pill")
    val bg by animateColorAsState(
        when {
            selected || logo -> colors.primary
            accent -> colors.tertiaryContainer
            else -> colors.surfaceContainerHigh
        },
        MaterialTheme.motionScheme.fastEffectsSpec(), label = "bg",
    )
    val fg = when {
        selected || logo -> colors.onPrimary
        accent -> colors.onTertiaryContainer
        else -> colors.onSurfaceVariant
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Right),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        Box(Modifier.width(72.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(4.dp)
                    .height(pill)
                    .background(colors.onSurface, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)),
            )
            BadgedBox(badge = { if (badge > 0) Badge { Text(if (badge > 99) "99+" else "$badge") } }) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(corner))
                        .background(bg)
                        .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), role = Role.Tab, onClick = onClick)
                        .semantics { contentDescription = label; this.selected = selected },
                    contentAlignment = Alignment.Center,
                ) {
                    if (logo) Text("V", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = fg)
                    else if (icon != null) Icon(icon, null, Modifier.size(22.dp), tint = fg)
                }
            }
        }
    }
}

@Composable
private fun AccountButton(session: SessionState, dynamicColor: Boolean, a: SidebarActions) {
    var menu by remember { mutableStateOf(false) }
    val user = session.user
    Box {
        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = "Account") { menu = true },
            contentAlignment = Alignment.Center,
        ) { Avatar(user?.name ?: "Local", 40) }
        DropdownMenu(menu, { menu = false }, shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(user?.name ?: "On this device", style = MaterialTheme.typography.titleSmall)
                Text(user?.email ?: "Sign in to sync across devices", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                DropdownMenuItem(
                    { Text("Wallpaper colors") }, { a.toggleDynamicColor() },
                    leadingIcon = { Icon(Icons.Outlined.Palette, null) },
                    trailingIcon = { Switch(dynamicColor, null) },
                )
            }
            if (session.status == Status.Local) {
                DropdownMenuItem({ Text("Sign in to sync") }, { menu = false; a.signIn() }, leadingIcon = { Icon(Icons.Outlined.Login, null) })
            } else {
                DropdownMenuItem({ Text("Sign out") }, { menu = false; a.signOut() }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, null) })
            }
        }
    }
}

@Composable
fun SyncLabel(session: SessionState, sync: SyncState) {
    val colors = MaterialTheme.colorScheme
    val (icon, text, tint) = when {
        session.status == Status.Local -> Triple(Icons.Outlined.CloudOff, "On this device", colors.onSurfaceVariant)
        sync.connection == Connection.Online && sync.pending > 0 -> Triple(Icons.Outlined.CloudSync, "Syncing ${sync.pending}", colors.primary)
        sync.connection == Connection.Online -> Triple(Icons.Outlined.CloudDone, "Synced", colors.primary)
        sync.connection == Connection.Denied -> Triple(Icons.Outlined.CloudOff, "Signed out on server", colors.error)
        sync.connection == Connection.Connecting -> Triple(Icons.Outlined.CloudSync, "Connecting…", colors.onSurfaceVariant)
        else -> Triple(Icons.Outlined.CloudOff, if (sync.pending > 0) "Offline · ${sync.pending} waiting" else "Offline", colors.onSurfaceVariant)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = tint)
        Text(text, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
fun Avatar(name: String, size: Int = 28) {
    val initials = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
    Box(Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
        Text(initials, style = if (size >= 36) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

/** A channel-like row: muted until selected, then raised on a tonal fill. */
@Composable
private fun ChannelRow(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else null,
            modifier = Modifier.padding(start = 10.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRow(
    p: Page,
    depth: Int,
    selected: Boolean,
    hasKids: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
    a: SidebarActions,
    ws: Workspace,
    modifier: Modifier,
    renaming: Boolean = false,
    onRenamed: (String?) -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    val turn by animateFloatAsState(if (open) 90f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "chev")
    val fg = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clip(MaterialTheme.shapes.small)
                .background(if (selected) colors.secondaryContainer else Color.Transparent)
                .combinedClickable(
                    onClick = { if (p.isFolder) onToggle() else a.navigate(Dest.Doc(p.id)) },
                    onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
                    onLongClickLabel = "Actions",
                )
                .semantics {
                    this.selected = selected
                    if (hasKids) stateDescription = if (open) "Expanded" else "Collapsed"
                }
                .padding(start = (4 + depth * 14).dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (hasKids) Icon(
                    Icons.Outlined.ChevronRight, if (open) "Collapse" else "Expand",
                    Modifier.size(18.dp).rotate(turn).clip(CircleShape).clickable(onClick = onToggle),
                    tint = colors.onSurfaceVariant,
                )
            }
            if (p.icon != null && !p.isFolder) Text(p.icon, fontSize = 16.sp, modifier = Modifier.size(18.dp))
            else Icon(
                when {
                    p.isFolder && open -> Icons.Outlined.FolderOpen
                    p.isFolder -> Icons.Outlined.Folder
                    p.isEdgeless -> Icons.Outlined.Dashboard
                    else -> Icons.Outlined.Description
                },
                null, Modifier.size(18.dp), tint = fg,
            )
            if (renaming) InlineName(p.title, onRenamed, Modifier.weight(1f).padding(start = 6.dp, end = 8.dp))
            else Text(
                p.displayTitle, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (selected) colors.onSecondaryContainer else colors.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else null,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
            if (!renaming) IconButton(onClick = { menu = true }, Modifier.size(32.dp).semantics { contentDescription = "More actions for ${p.displayTitle}" }) {
                Icon(Icons.Outlined.MoreHoriz, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
            }
        }
        DropdownMenu(menu, { menu = false }, shape = MaterialTheme.shapes.large) {
            fun item(label: String, icon: ImageVector, danger: Boolean = false, f: () -> Unit) = @Composable {
                DropdownMenuItem(
                    { Text(label, color = if (danger) colors.error else Color.Unspecified) },
                    { menu = false; f() },
                    leadingIcon = { Icon(icon, null, tint = if (danger) colors.error else colors.onSurfaceVariant) },
                )
            }
            if (p.isFolder) {
                item("New doc", Icons.AutoMirrored.Outlined.NoteAdd) { a.newDoc(p.id) }()
                item("New folder", Icons.Outlined.CreateNewFolder) { a.newFolder(p.id) }()
                item("Rename", Icons.Outlined.DriveFileRenameOutline) { a.rename(p) }()
                item("Move to…", Icons.AutoMirrored.Outlined.DriveFileMove) { a.moveTo(p) }()
                HorizontalDivider()
                item("Delete folder", Icons.Outlined.Delete, danger = true) { ws.trash(p.id) }()
            } else {
                item("Open", Icons.Outlined.Description) { a.navigate(Dest.Doc(p.id)) }()
                item("New doc inside", Icons.AutoMirrored.Outlined.NoteAdd) { a.newDoc(p.id) }()
                item("Rename", Icons.Outlined.DriveFileRenameOutline) { a.rename(p) }()
                item("Duplicate", Icons.Outlined.ContentCopy) { ws.duplicate(p.id)?.let { a.navigate(Dest.Doc(it.id)) } }()
                if (p.favorite) item("Remove from favorites", Icons.Outlined.StarOutline) { ws.setFavorite(p.id, false) }()
                else item("Add to favorites", Icons.Outlined.Star) { ws.setFavorite(p.id, true) }()
                item("Move to…", Icons.AutoMirrored.Outlined.DriveFileMove) { a.moveTo(p) }()
                HorizontalDivider()
                item("Move to trash", Icons.Outlined.Delete, danger = true) { ws.trash(p.id) }()
            }
        }
    }
}

/**
 * A name edited in place: text selected, keyboard up. Done, tapping elsewhere or closing the panel
 * saves; Escape cancels (null). Saves at most once.
 */
@Composable
private fun InlineName(title: String, onDone: (String?) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    val keyboard = LocalFocusManager.current
    var value by remember { mutableStateOf(TextFieldValue(title, TextRange(0, title.length))) }
    var focused by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    fun finish(t: String?) {
        if (finished) return
        finished = true
        onDone(t)
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Leaving composition (panel closed, row scrolled away) still keeps what was typed.
    val latest by rememberUpdatedState(value.text)
    DisposableEffect(Unit) { onDispose { finish(latest) } }
    BasicTextField(
        value = value,
        onValueChange = { value = it },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { finish(value.text); keyboard.clearFocus() }),
        modifier = modifier
            .focusRequester(focus)
            .onFocusChanged { if (it.isFocused) focused = true else if (focused) finish(value.text) }
            .onPreviewKeyEvent { e -> if (e.key == Key.Escape) { finish(null); keyboard.clearFocus(); true } else false }
            .semantics { contentDescription = "Name" },
        decorationBox = { inner ->
            Box(
                Modifier
                    .border(1.5.dp, colors.primary, MaterialTheme.shapes.extraSmall)
                    .background(colors.surfaceContainerHighest, MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) { inner() }
        },
    )
}
