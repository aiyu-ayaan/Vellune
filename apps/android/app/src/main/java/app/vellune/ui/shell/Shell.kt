package app.vellune.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
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
 * The workspace frame: the web's sidebar + main area. Wide windows (tablets, desktop mode) keep the
 * sidebar docked like the web; narrower ones slide it in as a modal drawer.
 */
@Composable
fun Shell(app: Vellune, nav: AppState, dynamicColor: Boolean, onDynamicColor: (Boolean) -> Unit) {
    val ws = app.workspace
    val pages by ws.pages.collectAsStateWithLifecycle()
    val session by app.session.state.collectAsStateWithLifecycle()
    val sync by app.sync.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val index = remember(pages) { buildIndex(pages.values) }
    val tags = remember(index) { index.tags.keys.sorted() }
    var expanded by remember {
        mutableStateOf(app.db.kv("expanded")?.let { j -> JSONArray(j).let { a -> List(a.length()) { a.getString(it) }.toSet() } } ?: emptySet())
    }
    var renaming by remember { mutableStateOf<Page?>(null) }
    var moving by remember { mutableStateOf<Page?>(null) }

    LaunchedEffect(pages) { nav.prune(pages) }
    BackHandler(nav.stack.size > 1) { nav.back() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val actions = SidebarActions(
            navigate = { d -> nav.navigate(d); scope.launch { drawer.close() } },
            search = { nav.searchOpen = true; scope.launch { drawer.close() } },
            newDoc = { parent ->
                nav.newDoc(parent)
                if (parent != null) expanded = expanded + parent
                scope.launch { drawer.close() }
            },
            newFolder = { parent ->
                val f = ws.createFolder(parentId = parent)
                if (parent != null) expanded = expanded + parent
                renaming = f
            },
            rename = { renaming = it },
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
        val sidebar = @Composable { m: Modifier ->
            Sidebar(
                ws, pages, nav.current, session, sync, tags, expanded,
                onExpand = { id, open ->
                    expanded = if (open) expanded + id else expanded - id
                    app.db.setKv("expanded", JSONArray(expanded.toList()).toString())
                },
                dynamicColor = dynamicColor, a = actions, modifier = m,
            )
        }
        val content = @Composable {
            Content(app, nav, pages, index, sync, onMenu = if (wide) null else ({ scope.launch { drawer.open() } }))
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(296.dp).fillMaxHeight()) {
                    sidebar(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)))
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f)) { content() }
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawer,
                // Edge swipe would fight the pen on a canvas; the menu button opens it there.
                gesturesEnabled = drawer.isOpen || (nav.current as? Dest.Doc)?.let { pages[it.id]?.isEdgeless } != true,
                drawerContent = {
                    ModalDrawerSheet(drawerState = drawer, drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        sidebar(Modifier)
                    }
                },
            ) { content() }
        }
    }

    if (nav.searchOpen) SearchDialog(pages, onDismiss = { nav.searchOpen = false }, onOpen = { nav.open(it); nav.searchOpen = false }, onCreate = { title ->
        nav.searchOpen = false
        nav.open(ws.createPage(title).id)
    })
    renaming?.let { p -> RenameDialog(p, onDismiss = { renaming = null }) { t -> ws.updatePage(p.id) { it.copy(title = t) }; renaming = null } }
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
            is Dest.Doc -> pages[d.id]?.let { PageScreen(app, nav, it, index, sync, onMenu) } ?: HomeScreen(app.workspace, pages, nav, onMenu)
            Dest.Graph -> GraphScreen(pages, index, nav, onMenu)
            is Dest.Database -> DatabaseScreen(app.workspace, pages, index, d.tag, nav, onMenu)
            Dest.Trash -> TrashScreen(app.workspace, pages, onMenu)
        }
    }
}
