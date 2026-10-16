package app.vellune.ui.page

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Web
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
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
import app.vellune.ui.canvas.EdgelessScreen
import app.vellune.ui.shell.Avatar
import app.vellune.ui.shell.MoveDialog
import app.vellune.ui.shell.VTopBar

/** A page: the web's TopBar (path, doc/edgeless switch, favorite, presence, menu) over the doc or its canvas. */
@Composable
fun PageScreen(app: Vellune, nav: AppState, page: Page, index: LinkIndex, sync: SyncState, onMenu: (() -> Unit)?) {
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

    Scaffold(
        topBar = {
            VTopBar(
                title = {
                    Column {
                        if (parent != null) Text(parent.displayTitle, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(page.displayTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                onMenu = onMenu,
                actions = {
                    Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) { others.take(3).forEach { Avatar(it.name, 26) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                        ToggleButton(
                            checked = !page.isEdgeless, onCheckedChange = { setEdgeless(false) },
                            shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                            contentPadding = ToggleButtonDefaults.ContentPadding,
                        ) { Icon(Icons.Outlined.Description, "Page mode", Modifier.size(18.dp)) }
                        ToggleButton(
                            checked = page.isEdgeless, onCheckedChange = { setEdgeless(true) },
                            shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                            contentPadding = ToggleButtonDefaults.ContentPadding,
                        ) { Icon(Icons.Outlined.Dashboard, "Edgeless mode", Modifier.size(18.dp)) }
                    }
                    IconToggleButton(page.favorite, { ws.setFavorite(page.id, it) }) {
                        Icon(if (page.favorite) Icons.Outlined.Star else Icons.Outlined.StarOutline,
                            if (page.favorite) "Remove from favorites" else "Add to favorites",
                            tint = if (page.favorite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }, shape = MaterialTheme.shapes.large) {
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
                },
            )
        },
    ) { pad ->
        val motion = MaterialTheme.motionScheme
        AnimatedContent(
            page.isEdgeless,
            transitionSpec = { (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), 0.94f)) togetherWith fadeOut(motion.fastEffectsSpec()) },
            modifier = Modifier.padding(top = pad.calculateTopPadding()).fillMaxSize(),
            label = "face",
        ) { edgeless ->
            if (edgeless) EdgelessScreen(app, nav, page, onEditDoc = { setEdgeless(false) })
            else DocEditor(app, nav, page, index)
        }
    }
    if (moving) MoveDialog(ws, page, onDismiss = { moving = false }) { p -> ws.move(page.id, p); moving = false }
}
