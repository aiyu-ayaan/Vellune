package app.vellune.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    val newFolder: (parentId: String?) -> Unit,
    val rename: (Page) -> Unit,
    val moveTo: (Page) -> Unit,
    val signOut: () -> Unit,
    val signIn: () -> Unit,
    val toggleDynamicColor: () -> Unit,
)

/** The web sidebar, item for item: search, journals, brain view, databases, favorites, organize, tags, trash. */
@OptIn(ExperimentalFoundationApi::class)
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
    dynamicColor: Boolean,
    a: SidebarActions,
    modifier: Modifier = Modifier,
) {
    val favorites = remember(pages) { pages.values.filter { it.favorite && it.deletedAt == null && !it.isFolder }.sortedBy { it.displayTitle.lowercase() } }
    val trashCount = remember(pages) { pages.values.count { it.deletedAt != null } }
    val activeId = (current as? Dest.Doc)?.id
    val rows = remember(pages, expanded) { flatten(ws, null, 0, expanded) }

    Column(modifier.fillMaxHeight()) {
        Header(session, sync, dynamicColor, a)
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NavItem(Icons.Outlined.Search, "Search", false, Modifier.weight(1f), onClick = a.search)
                    IconButton(onClick = { a.newDoc(null) }) { Icon(Icons.Outlined.Add, "New doc") }
                }
                NavItem(Icons.Outlined.CalendarToday, "Journals", false) { a.navigate(Dest.Doc(ws.openToday().id)) }
                NavItem(Icons.Outlined.Hub, "Brain View", current == Dest.Graph) { a.navigate(Dest.Graph) }
                NavItem(Icons.Outlined.Storage, "Databases", current is Dest.Database && current.tag == null) { a.navigate(Dest.Database()) }
            }
            if (favorites.isNotEmpty()) section("Favorites") {
                items(favorites, key = { "fav-" + it.id }) { p ->
                    TreeRow(p, 0, p.id == activeId, false, false, {}, a, ws, Modifier.animateItem())
                }
            }
            section("Organize", action = {
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }, Modifier.size(32.dp)) { Icon(Icons.Outlined.Add, "New doc or folder", Modifier.size(18.dp)) }
                    DropdownMenu(menu, { menu = false }, shape = MaterialTheme.shapes.large) {
                        DropdownMenuItem({ Text("New doc") }, { menu = false; a.newDoc(null) }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.NoteAdd, null) })
                        DropdownMenuItem({ Text("New folder") }, { menu = false; a.newFolder(null) }, leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) })
                    }
                }
            }) {
                items(rows, key = { "tree-" + it.page.id }) { r ->
                    TreeRow(r.page, r.depth, r.page.id == activeId, r.hasKids, r.open, { onExpand(r.page.id, !r.open) }, a, ws, Modifier.animateItem())
                }
            }
            if (tags.isNotEmpty()) section("Tags") {
                items(tags, key = { "tag-$it" }) { t ->
                    NavItem(Icons.Outlined.Tag, t, current is Dest.Database && current.tag == t) { a.navigate(Dest.Database(t)) }
                }
            }
            section("Others") {
                item { NavItem(Icons.Outlined.Delete, if (trashCount > 0) "Trash ($trashCount)" else "Trash", current == Dest.Trash) { a.navigate(Dest.Trash) } }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun LazyListScope.section(label: String, action: (@Composable () -> Unit)? = null, content: LazyListScope.() -> Unit) {
    item(key = "section-$label") {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 16.dp, bottom = 4.dp).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            action?.invoke()
        }
    }
    content()
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
private fun Header(session: SessionState, sync: SyncState, dynamicColor: Boolean, a: SidebarActions) {
    var menu by remember { mutableStateOf(false) }
    val user = session.user
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
            Text("V", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("Vellune", style = MaterialTheme.typography.titleMedium)
            SyncLabel(session, sync)
        }
        Box {
            IconButton(onClick = { menu = true }) { Avatar(user?.name ?: "Local") }
            DropdownMenu(menu, { menu = false }, shape = MaterialTheme.shapes.large) {
                if (user != null) {
                    Text(user.email, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    DropdownMenuItem(
                        { Text("Wallpaper colors") }, { a.toggleDynamicColor() },
                        leadingIcon = { Icon(Icons.Outlined.Palette, null) },
                        trailingIcon = { Switch(dynamicColor, null) },
                    )
                }
                HorizontalDivider()
                if (session.status == Status.Local) {
                    DropdownMenuItem({ Text("Sign in to sync") }, { menu = false; a.signIn() }, leadingIcon = { Icon(Icons.Outlined.Login, null) })
                } else {
                    DropdownMenuItem({ Text("Sign out") }, { menu = false; a.signOut() }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, null) })
                }
            }
        }
    }
}

@Composable
fun SyncLabel(session: SessionState, sync: SyncState) {
    val (icon, text) = when {
        session.status == Status.Local -> Icons.Outlined.CloudOff to "On this device"
        sync.connection == Connection.Online && sync.pending > 0 -> Icons.Outlined.CloudSync to "Syncing ${sync.pending}"
        sync.connection == Connection.Online -> Icons.Outlined.CloudDone to "Synced"
        sync.connection == Connection.Denied -> Icons.Outlined.CloudOff to "Signed out on server"
        sync.connection == Connection.Connecting -> Icons.Outlined.CloudSync to "Connecting…"
        else -> Icons.Outlined.CloudOff to if (sync.pending > 0) "Offline · ${sync.pending} waiting" else "Offline"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
fun Avatar(name: String, size: Int = 28) {
    val initials = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
    Box(Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
        Text(initials, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(bg)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 12.dp))
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
) {
    var menu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val turn by animateFloatAsState(if (open) 90f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "chev")
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(bg)
                .combinedClickable(
                    onClick = { if (p.isFolder) onToggle() else a.navigate(Dest.Doc(p.id)) },
                    onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
                    onLongClickLabel = "Actions",
                )
                .semantics { if (hasKids) stateDescription = if (open) "Expanded" else "Collapsed" }
                .padding(start = (8 + depth * 16).dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (hasKids) Icon(
                    Icons.Outlined.ChevronRight, if (open) "Collapse" else "Expand",
                    Modifier.size(18.dp).rotate(turn).clip(CircleShape).clickable(onClick = onToggle),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                when {
                    p.isFolder && open -> Icons.Outlined.FolderOpen
                    p.isFolder -> Icons.Outlined.Folder
                    p.isEdgeless -> Icons.Outlined.Dashboard
                    else -> Icons.Outlined.Description
                },
                null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                p.displayTitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.SemiBold else null,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
            IconButton(onClick = { menu = true }, Modifier.size(32.dp).semantics { contentDescription = "More actions for ${p.displayTitle}" }) {
                Icon(Icons.Outlined.MoreHoriz, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(menu, { menu = false }, shape = MaterialTheme.shapes.large) {
            fun item(label: String, icon: ImageVector, danger: Boolean = false, f: () -> Unit) = @Composable {
                DropdownMenuItem(
                    { Text(label, color = if (danger) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified) },
                    { menu = false; f() },
                    leadingIcon = { Icon(icon, null, tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
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
