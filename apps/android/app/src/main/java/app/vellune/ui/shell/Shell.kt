package app.vellune.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import app.vellune.ui.page.PageInfoPanel
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vellune.Vellune
import app.vellune.core.Page
import app.vellune.core.buildIndex
import app.vellune.ui.AppState
import app.vellune.ui.Dest
import app.vellune.ui.database.DatabaseScreen
import app.vellune.ui.graph.GraphScreen
import app.vellune.ui.page.PageScreen
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * The workspace frame. Phones and small tablets get Discord's overlapping panels: the page slides
 * right over the sidebar or left over the page info. Wide windows dock both beside the page.
 */
@Composable
fun Shell(app: Vellune, nav: AppState, dynamicColor: Boolean, onDynamicColor: (Boolean) -> Unit) {
    val ws = app.workspace
    val pages by ws.pages.collectAsStateWithLifecycle()
    val session by app.session.state.collectAsStateWithLifecycle()
    val sync by app.sync.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val index = remember(pages) { buildIndex(pages.values) }
    val tags = remember(index) { index.tags.keys.sorted() }
    var expanded by remember {
        mutableStateOf(app.db.kv("expanded")?.let { j -> JSONArray(j).let { a -> List(a.length()) { a.getString(it) }.toSet() } } ?: emptySet())
    }
    var renaming by remember { mutableStateOf<String?>(null) }
    var moving by remember { mutableStateOf<Page?>(null) }
    var infoDocked by remember { mutableStateOf(app.db.kv("info_panel") == "1") }
    val panels = remember { PanelsState() }
    val page = (nav.current as? Dest.Doc)?.let { pages[it.id] }

    LaunchedEffect(pages) { nav.prune(pages) }
    // A panel coming in puts the keyboard away, as Discord does.
    LaunchedEffect(panels.target) { if (panels.target != Panel.Closed) focus.clearFocus() }
    BackHandler(nav.stack.size > 1 && panels.target == Panel.Closed) { nav.back() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        val closePanels = { if (!wide) scope.launch { panels.close() } }
        val actions = SidebarActions(
            navigate = { d -> nav.navigate(d); closePanels() },
            search = { nav.searchOpen = true; closePanels() },
            newDoc = { parent ->
                nav.newDoc(parent)
                if (parent != null) expanded = expanded + parent
                closePanels()
            },
            newCanvas = { nav.newDoc(edgeless = true); closePanels() },
            newFolder = { parent ->
                val f = ws.createFolder(parentId = parent)
                if (parent != null) expanded = expanded + parent
                renaming = f.id
            },
            rename = { p ->
                // Open every folder above it so its row is on screen to edit in place.
                var up = p.parentId
                while (up != null) { expanded = expanded + up; up = pages[up]?.parentId }
                renaming = p.id
            },
            moveTo = { moving = it },
            signOut = {
                scope.launch {
                    app.sync.reset()
                    app.session.logout()
                    ws.reset()
                    ws.load()
                }
            },
            signIn = { app.session.showLogin() },
            toggleDynamicColor = { onDynamicColor(!dynamicColor) },
        )
        val sidebar = @Composable {
            Sidebar(
                ws, pages, nav.current, session, sync, tags, expanded,
                onExpand = { id, open ->
                    expanded = if (open) expanded + id else expanded - id
                    app.db.setKv("expanded", JSONArray(expanded.toList()).toString())
                },
                renamingId = renaming,
                onRenamed = { p, title ->
                    if (title != null && title.isNotBlank() && title.trim() != p.title) ws.updatePage(p.id) { it.copy(title = title.trim()) }
                    renaming = null
                },
                dynamicColor = dynamicColor, a = actions,
            )
        }
        val info = page?.takeUnless { it.isFolder }?.let { p ->
            @Composable { PageInfoPanel(p, index, pages, nav, onDone = { closePanels() }) }
        }
        val onInfo = {
            if (wide) {
                infoDocked = !infoDocked
                app.db.setKv("info_panel", if (infoDocked) "1" else null)
            } else scope.launch { panels.open(Panel.End) }
            Unit
        }

        if (page?.isEdgeless == true) {
            // Canvas: full screen, nothing beside it.
            Content(app, nav, pages, index, sync, onMenu = null, onInfo = onInfo)
        } else if (wide) {
            val docked = infoDocked && info != null
            val card = RoundedCornerShape(
                topStart = 28.dp, bottomStart = 28.dp,
                topEnd = if (docked) 28.dp else 0.dp, bottomEnd = if (docked) 28.dp else 0.dp,
            )
            Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                Box(Modifier.width(344.dp).fillMaxHeight()) { sidebar() }
                Box(Modifier.weight(1f).fillMaxHeight().clip(card).background(MaterialTheme.colorScheme.surface)) {
                    Content(app, nav, pages, index, sync, onMenu = null, onInfo = onInfo)
                }
                AnimatedVisibility(
                    docked,
                    enter = expandHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(),
                    exit = shrinkHorizontally(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(),
                ) {
                    Box(Modifier.width(340.dp).fillMaxHeight()) { info?.invoke() }
                }
            }
        } else {
            OverlappingPanels(
                state = panels,
                start = sidebar,
                end = info,
                // A canvas belongs to the pen; its menu button still opens the sidebar.
                gesturesEnabled = page?.isEdgeless != true,
            ) {
                Content(app, nav, pages, index, sync, onMenu = { scope.launch { panels.open(Panel.Start) } }, onInfo = onInfo)
            }
        }
    }

    if (nav.searchOpen) SearchDialog(pages, onDismiss = { nav.searchOpen = false }, onOpen = { nav.open(it); nav.searchOpen = false }, onCreate = { title ->
        nav.searchOpen = false
        nav.open(ws.createPage(title).id)
    })
    moving?.let { p -> MoveDialog(ws, p, onDismiss = { moving = null }) { parent -> ws.move(p.id, parent); moving = null } }
}

@Composable
private fun Content(
    app: Vellune,
    nav: AppState,
    pages: Map<String, Page>,
    index: app.vellune.core.LinkIndex,
    sync: app.vellune.core.SyncState,
    onMenu: (() -> Unit)?,
    onInfo: () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = nav.current,
        transitionSpec = {
            (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.96f)) togetherWith
                fadeOut(motion.fastEffectsSpec())
        },
        contentKey = { if (it is Dest.Doc) "doc" + it.id else it.toString() },
        label = "content",
    ) { d ->
        when (d) {
            Dest.Home -> HomeScreen(app.workspace, pages, nav, onMenu)
            is Dest.Doc -> pages[d.id]?.let { PageScreen(app, nav, it, index, sync, onMenu, onInfo) } ?: HomeScreen(app.workspace, pages, nav, onMenu)
            Dest.Graph -> GraphScreen(pages, index, nav, onMenu)
            is Dest.Database -> DatabaseScreen(app.workspace, pages, index, d.tag, nav, onMenu)
            Dest.Trash -> TrashScreen(app.workspace, pages, onMenu)
        }
    }
}
