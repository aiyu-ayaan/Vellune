package app.vellune.ui.page

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Web
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vellune.Vellune
import app.vellune.core.LinkIndex
import app.vellune.core.Page
import app.vellune.core.SyncState
import app.vellune.ui.AppState
import app.vellune.ui.Dest
import app.vellune.ui.canvas.EdgelessScreen
import app.vellune.ui.shell.Avatar
import app.vellune.ui.shell.MoveDialog
import app.vellune.ui.shell.VTopBar

/** A page: the web's TopBar (path, doc/edgeless switch, favorite, presence, menu) over the doc or its canvas. */
@Composable
fun PageScreen(app: Vellune, nav: AppState, page: Page, index: LinkIndex, sync: SyncState, onMenu: (() -> Unit)?, onInfo: () -> Unit) {
    val ws = app.workspace
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    val parent = page.parentId?.let { ws.pages.value[it] }
    val others = sync.presence[page.id].orEmpty().filter { it.id != app.session.state.value.user?.id }
    // The page remembers its face on every device (`mode` in the shared doc).
    fun setEdgeless(on: Boolean) {
        if (on == page.isEdgeless) return
        haptic.performHapticFeedback(HapticFeedbackType.ToggleOn)
        ws.updatePage(page.id) { it.copy(mode = if (on) "edgeless" else null) }
    }

    val pageMenu = @Composable {
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
            DropdownMenu(menu, { menu = false }, shape = MaterialTheme.shapes.large) {
                DropdownMenuItem(
                    { Text(if (page.favorite) "Remove from favorites" else "Add to favorites") },
                    { menu = false; ws.setFavorite(page.id, !page.favorite) },
                    leadingIcon = { Icon(if (page.favorite) Icons.Outlined.Star else Icons.Outlined.StarOutline, null) },
                )
                if (page.isEdgeless) DropdownMenuItem({ Text("Embed a page") }, { menu = false; nav.embedRequest++ },
                    leadingIcon = { Icon(Icons.Outlined.Web, null) })
                DropdownMenuItem({ Text("Duplicate") }, { menu = false; ws.duplicate(page.id)?.let { nav.open(it.id) } },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) })
                DropdownMenuItem({ Text("Move to…") }, { menu = false; moving = true },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) })
                HorizontalDivider()
                DropdownMenuItem({ Text("Move to trash", color = MaterialTheme.colorScheme.error) }, { menu = false; ws.trash(page.id) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) })
            }
        }
    }

    if (page.isEdgeless) {
        // The canvas owns the whole screen: only floating controls, no shell chrome.
        EdgelessScreen(
            app, nav, page,
            onEditDoc = { setEdgeless(false) },
            onBack = { if (!nav.back()) nav.navigate(Dest.Home) },
            actions = {
                ModeSwitch(true, ::setEdgeless)
                pageMenu()
            },
        )
    } else {
        Scaffold(
            topBar = {
                VTopBar(
                    icon = if (page.journal != null) Icons.Outlined.CalendarToday else Icons.Outlined.Description,
                    title = listOfNotNull(page.icon, page.displayTitle).joinToString(" "),
                    subtitle = parent?.displayTitle ?: "Doc",
                    onMenu = onMenu,
                    actions = {
                        Row(horizontalArrangement = Arrangement.spacedBy((-8).dp), modifier = Modifier.padding(end = 4.dp)) { others.take(3).forEach { Avatar(it.name, 26) } }
                        ModeSwitch(false, ::setEdgeless)
                        IconButton(onClick = onInfo) { Icon(Icons.Outlined.Info, "Page info") }
                        pageMenu()
                    },
                )
            },
        ) { pad -> DocEditor(app, nav, page, index, Modifier.padding(top = pad.calculateTopPadding())) }
    }
    if (moving) MoveDialog(ws, page, onDismiss = { moving = false }) { p -> ws.move(page.id, p); moving = false }
}

/** Doc or canvas: one capsule, the chosen face lifted on a tonal circle. */
@Composable
fun ModeSwitch(edgeless: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.padding(horizontal = 4.dp).clip(CircleShape).background(colors.surfaceContainerHigh).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(false to Icons.Outlined.Description, true to Icons.Outlined.Dashboard).forEach { (mode, icon) ->
            val on = mode == edgeless
            val bg by animateColorAsState(if (on) colors.primaryContainer else Color.Transparent, MaterialTheme.motionScheme.fastEffectsSpec(), label = "mode")
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(bg)
                    .selectable(on, role = Role.Tab) { onChange(mode) }
                    .semantics { contentDescription = if (mode) "Canvas mode" else "Doc mode" },
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, Modifier.size(18.dp), tint = if (on) colors.onPrimaryContainer else colors.onSurfaceVariant) }
        }
    }
}
