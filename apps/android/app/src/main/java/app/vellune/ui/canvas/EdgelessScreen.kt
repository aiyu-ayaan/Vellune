package app.vellune.ui.canvas

import android.graphics.RectF
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.activity.compose.BackHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.ui.unit.IntOffset
import app.vellune.ink.ShapeKind
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.isSystemInDarkTheme
import app.vellune.Vellune
import app.vellune.core.Canvas as PageCanvas
import app.vellune.core.CanvasItem
import app.vellune.core.Page
import app.vellune.core.Stroke
import app.vellune.ink.Brushes
import app.vellune.ink.InkCanvasView
import app.vellune.ink.InkEntry
import app.vellune.ink.InkMode
import app.vellune.ink.PenTool
import app.vellune.ui.AppState
import app.vellune.ui.shell.excerpt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Sticky colors, the same names and tones as the web (`.canvas-sticky.amber` …). */
private val STICKY = linkedMapOf(
    "amber" to (Color(0xFFFFF3C4) to Color(0xFF4A3B00)),
    "blue" to (Color(0xFFD8E8FF) to Color(0xFF0B2A55)),
    "green" to (Color(0xFFD7F5DD) to Color(0xFF0E3B1A)),
    "rose" to (Color(0xFFFFDDE4) to Color(0xFF4F1222)),
)

private const val NOTE_ID = "note"

/**
 * A page's edgeless whiteboard (the web's EdgelessCanvas): the page document as a card, sticky
 * notes, embedded pages and native ink, on an infinite pannable, zoomable sheet.
 */
@Composable
fun EdgelessScreen(
    app: Vellune,
    nav: AppState,
    page: Page,
    onEditDoc: () -> Unit,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    val ws = app.workspace
    val db = app.db
    val colors = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val density = LocalDensity.current.density

    var mode by remember {
        mutableStateOf(
            when (val m = db.kv("pen_mode")) {
                "select" -> InkMode.Select
                "eraser" -> InkMode.Eraser
                "lasso" -> InkMode.Lasso
                "sticky" -> InkMode.Sticky
                else -> if (m?.startsWith("shape:") == true) InkMode.Shape(ShapeKind.entries.firstOrNull { "shape:" + it.id == m } ?: ShapeKind.Rectangle)
                else InkMode.Draw(PenTool.entries.firstOrNull { it.id == m } ?: PenTool.Pen)
            },
        )
    }
    // The pen and shape buttons remember which member of their family was used last.
    var lastPen by remember { mutableStateOf(PenTool.entries.firstOrNull { it.id == db.kv("last_pen") } ?: PenTool.Pen) }
    var lastShape by remember { mutableStateOf(ShapeKind.entries.firstOrNull { it.id == db.kv("last_shape") } ?: ShapeKind.Rectangle) }
    var color by remember { mutableIntStateOf(db.kv("pen_color")?.toIntOrNull() ?: InkColors[0]) }
    var sizeTick by remember { mutableIntStateOf(0) }
    var fingerDraws by remember { mutableStateOf(db.kv("finger_draws") != "0") }
    var paletteOpen by remember { mutableStateOf(false) }
    var inking by remember { mutableStateOf(false) }
    var selection by remember { mutableIntStateOf(0) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(density) }
    var tx by remember { mutableFloatStateOf(24 * density) }
    // Start below the floating back and title pills.
    var ty by remember { mutableFloatStateOf(88 * density) }
    val drag = remember { mutableStateMapOf<String, Offset>() }
    var editing by remember { mutableStateOf<CanvasItem?>(null) }
    var embedPicker by remember { mutableStateOf(false) }

    fun updateCanvas(f: (PageCanvas) -> PageCanvas) = ws.updatePage(page.id) { it.copy(canvas = f(it.canvas)) }

    // Without a placed note card the page's document still shows on its canvas (as on the web).
    val items = remember(page.canvas.items, page.title, page.blocks) {
        val placed = page.canvas.items
        if (placed.any { it.type == "note" } || (page.title.isBlank() && page.blocks.all { it.text.isBlank() })) placed
        else listOf(CanvasItem(id = NOTE_ID, type = "note", x = 0.0, y = 0.0, w = 460.0, h = 0.0)) + placed
    }
    val itemRects = remember { HashMap<String, RectF>() }
    val current by rememberUpdatedState(items)
    val editDoc by rememberUpdatedState(onEditDoc)

    val view = remember {
        InkCanvasView(context).apply {
            PenTool.entries.forEach { t -> db.kv("pen_size_${t.id}")?.toFloatOrNull()?.let { sizes[t] = it } }
            this.fingerDraws = db.kv("finger_draws") != "0"
            listener = object : InkCanvasView.Listener {
                override fun onEdit(added: List<Stroke>, removedIds: List<String>) = updateCanvas { c ->
                    val replaced = added.associateBy { it.id }
                    val gone = removedIds.toSet()
                    val kept = c.strokes.filterNot { it.id in gone }.map { replaced[it.id] ?: it }
                    c.copy(strokes = kept + added.filter { a -> kept.none { it.id == a.id } })
                }
                override fun onHistory(undo: Boolean, redo: Boolean) { canUndo = undo; canRedo = redo }
                override fun onSelection(count: Int) { selection = count }
                override fun onInking(active: Boolean) { inking = active; if (active) paletteOpen = false }
                override fun onTransform(s: Float, x: Float, y: Float) { scale = s; tx = x; ty = y }
                override fun onStylusDetected() {
                    fingerDraws = false
                    db.setKv("finger_draws", "0")
                }
                override fun onItemDrag(id: String, dx: Float, dy: Float, done: Boolean) {
                    if (!done) {
                        drag[id] = Offset(dx, dy)
                        return
                    }
                    drag.remove(id)
                    val item = current.firstOrNull { it.id == id } ?: return
                    val moved = item.copy(x = item.x + dx, y = item.y + dy)
                    updateCanvas { c ->
                        if (id == NOTE_ID && c.items.none { it.id == NOTE_ID }) c.copy(items = listOf(moved.copy(h = 300.0)) + c.items)
                        else c.copy(items = c.items.map { if (it.id == id) moved else it })
                    }
                }
                override fun onItemTap(id: String) {
                    val item = current.firstOrNull { it.id == id } ?: return
                    when (item.type) {
                        "note" -> editDoc()
                        "embed" -> item.pageId?.let { nav.open(it) }
                        else -> editing = item
                    }
                }
                override fun onPlaceSticky(x: Float, y: Float) {
                    val s = CanvasItem(type = "sticky", x = x - 100.0, y = y - 80.0, w = 200.0, h = 160.0, text = "", color = "amber")
                    updateCanvas { it.copy(items = it.items + s) }
                    editing = s
                }
                override fun onTapEmpty() { paletteOpen = false }
            }
        }
    }
    LaunchedEffect(mode) {
        view.mode = mode
        db.setKv("pen_mode", when (val m = mode) {
            InkMode.Select -> "select"; InkMode.Eraser -> "eraser"; InkMode.Lasso -> "lasso"; InkMode.Sticky -> "sticky"
            is InkMode.Draw -> m.tool.id
            is InkMode.Shape -> "shape:" + m.kind.id
        })
        (mode as? InkMode.Draw)?.let { lastPen = it.tool; db.setKv("last_pen", it.tool.id) }
        (mode as? InkMode.Shape)?.let { lastShape = it.kind; db.setKv("last_shape", it.kind.id) }
    }
    LaunchedEffect(color) { view.color = color; db.setKv("pen_color", color.toString()) }
    LaunchedEffect(fingerDraws) { view.fingerDraws = fingerDraws }
    LaunchedEffect(colors, dark) { view.setColors(colors.primary.toArgb(), colors.surface.toArgb(), dark) }
    // Shared strokes -> view; strokes it already holds are reused, so local edits cost nothing here.
    LaunchedEffect(page.canvas.strokes, dark) {
        val strokes = page.canvas.strokes
        val built = withContext(Dispatchers.Default) {
            strokes.map { s -> view.entry(s.id)?.takeIf { it.data == s && view.isDark() == dark } ?: InkEntry(s, Brushes.build(s, dark)) }
        }
        view.setEntries(built)
    }

    // Drawing gets the whole display; a swipe from an edge brings the system bars back for a moment.
    val window = (LocalActivity.current)?.window
    val hostView = LocalView.current
    DisposableEffect(window) {
        val bars = window?.let { WindowCompat.getInsetsController(it, hostView) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    Box(modifier.fillMaxSize().clipToBounds().background(colors.surfaceContainerLowest)) {
        // Paper: dot grid in world space.
        val dot = colors.outlineVariant
        Canvas(Modifier.fillMaxSize()) {
            val step = 24f * scale
            if (step < 10f) return@Canvas
            var y = ((ty % step) + step) % step
            while (y < size.height) {
                var x = ((tx % step) + step) % step
                while (x < size.width) {
                    drawCircle(dot, radius = 1.1f * density, center = Offset(x, y))
                    x += step
                }
                y += step
            }
        }
        // Items, laid out in world space under the ink.
        items.forEach { item ->
            val d = drag[item.id] ?: Offset.Zero
            Box(
                Modifier
                    .graphicsLayer {
                        translationX = tx + (item.x.toFloat() + d.x) * scale
                        translationY = ty + (item.y.toFloat() + d.y) * scale
                        scaleX = scale / density
                        scaleY = scale / density
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                    .onSizeChanged { sz ->
                        // Unscaled layout size in px -> world units (dp).
                        itemRects[item.id] = RectF(item.x.toFloat(), item.y.toFloat(), item.x.toFloat() + sz.width / density, item.y.toFloat() + sz.height / density)
                        view.items = items.mapNotNull { i -> itemRects[i.id]?.let { i.id to it } }
                    },
            ) {
                when (item.type) {
                    "note" -> NoteCard(page, item)
                    "embed" -> EmbedCard(ws.pages.value[item.pageId], item)
                    "text" -> TextLabel(item)
                    else -> StickyCard(item)
                }
            }
        }
        LaunchedEffect(items) { view.items = items.mapNotNull { i -> itemRects[i.id]?.let { i.id to it } } }

        AndroidView({ view }, Modifier.fillMaxSize())

        BackHandler(onBack = onBack)

        // Where things are: an arrow on the edge for each item (and the ink) that is off screen.
        val markers = remember(items, page.canvas.strokes) {
            val ink = view.inkBounds()
            items.map { i -> Triple<String, () -> RectF?, Color>(i.id, { itemRects[i.id] }, when (i.type) {
                "note" -> colors.primary
                "embed" -> colors.tertiary
                "text" -> colors.onSurface
                else -> (STICKY[i.color] ?: STICKY.getValue("amber")).first
            }) } + listOfNotNull(ink?.let { r -> Triple<String, () -> RectF?, Color>("ink", { r }, colors.onSurface) })
        }
        markers.forEach { (key, rect, tint) ->
            EdgeMarker(
                rect = rect, tint = tint,
                transform = { Triple(scale, tx, ty) },
                insets = { view.chromeInsets },
                onTap = { rect()?.let { view.centerOn(it) } },
                label = if (key == "ink") "Go to drawing" else "Go to ${items.firstOrNull { it.id == key }?.type ?: "item"}",
            )
        }

        // Floating chrome: back and title on the left, zoom and page actions on the right. Fades while inking.
        val chromeAlpha by animateFloatAsState(if (inking) 0.3f else 1f, MaterialTheme.motionScheme.fastEffectsSpec(), label = "chrome")
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(12.dp)
                .graphicsLayer { alpha = chromeAlpha },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = colors.surfaceContainerHigh, shadowElevation = 3.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                    Text(page.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 200.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            Surface(shape = CircleShape, color = colors.surfaceContainerHigh, shadowElevation = 3.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
                    Text(
                        "${(scale / density * 100).toInt()}%", style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(onClickLabel = "Fit to content") {
                                val all = view.inkBounds() ?: RectF()
                                itemRects.values.forEach { all.union(it) }
                                view.fitTo(all.takeUnless { it.isEmpty })
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                    actions()
                }
            }
        }

        BoxWithConstraints(Modifier.align(Alignment.BottomCenter)) {
            val compact = maxWidth < 600.dp
            // Fitting keeps content clear of the floating pills above and the toolbar below.
            val navBottom = WindowInsets.navigationBars.getBottom(LocalDensity.current)
            val topInset = WindowInsets.safeDrawing.getTop(LocalDensity.current)
            LaunchedEffect(compact, navBottom, topInset) {
                view.chromeInsets = (topInset + 72 * density) to (navBottom + (if (compact) 148 else 88) * density)
            }
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp, start = 12.dp, end = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (compact) HistoryBar(canUndo, canRedo, { view.undo() }, { view.redo() }, Modifier.align(Alignment.End).padding(bottom = 12.dp))
                val tool = (mode as? InkMode.Draw)?.tool ?: PenTool.Pen
                AnimatedVisibility(
                    visible = paletteOpen,
                    enter = fadeIn() + scaleIn(MaterialTheme.motionScheme.defaultSpatialSpec(), 0.85f, TransformOrigin(0.5f, 1f)),
                    exit = fadeOut() + scaleOut(targetScale = 0.9f, transformOrigin = TransformOrigin(0.5f, 1f)),
                ) {
                    val size = remember(sizeTick, tool) { view.sizes.getValue(tool) }
                    PalettePanel(
                        tool = tool, color = color, size = size, dark = dark,
                        onColor = { c -> if (selection > 0) view.recolorSelection(c) else color = c },
                        onSize = {
                            view.sizes[tool] = it
                            sizeTick++
                            db.setKv("pen_size_${tool.id}", it.toString())
                        },
                        modifier = Modifier.padding(bottom = 12.dp),
                        kinds = when (val m = mode) {
                            is InkMode.Draw -> ({ KindRow("Pen", PenTool.entries, m.tool, ::penIcon, ::penLabel) { mode = InkMode.Draw(it) } })
                            is InkMode.Shape -> ({ KindRow("Shape", ShapeKind.entries, m.kind, ::shapeIcon, ::shapeLabel) { mode = InkMode.Shape(it) } })
                            else -> null
                        },
                    )
                }
                PenToolbar(
                    mode = mode, color = color, inking = inking, selection = selection,
                    canUndo = canUndo, canRedo = canRedo, paletteOpen = paletteOpen, showHistory = !compact, compact = compact,
                    lastPen = lastPen, lastShape = lastShape,
                    onMode = { m ->
                        // Tapping the active pen or shape button opens its options.
                        val family = (m is InkMode.Draw && mode is InkMode.Draw) || (m is InkMode.Shape && mode is InkMode.Shape)
                        paletteOpen = if (family) !paletteOpen else false
                        if (!family) mode = m
                    },
                    onPalette = { paletteOpen = !paletteOpen },
                    onUndo = { view.undo() }, onRedo = { view.redo() },
                    onDuplicate = { view.duplicateSelection() },
                    onDeleteSelection = { view.deleteSelection() },
                    onClearSelection = { view.clearSelection() },
                )
            }
        }
    }

    editing?.let { item ->
        StickyEditor(
            item,
            onDismiss = { editing = null },
            onSave = { t, c -> updateCanvas { cv -> cv.copy(items = cv.items.map { if (it.id == item.id) it.copy(text = t, color = c) else it }) }; editing = null },
            onDelete = { updateCanvas { cv -> cv.copy(items = cv.items.filterNot { it.id == item.id }) }; editing = null },
        )
    }
    if (embedPicker) EmbedPicker(ws.pages.value.values.filter { it.deletedAt == null && !it.isFolder && it.id != page.id }, onDismiss = { embedPicker = false }) { target ->
        val cx = ((view.width / 2f) - tx) / scale
        val cy = ((view.height / 2f) - ty) / scale
        updateCanvas { it.copy(items = it.items + CanvasItem(type = "embed", x = cx - 130.0, y = cy - 60.0, w = 260.0, h = 120.0, pageId = target.id)) }
        embedPicker = false
    }

    // Exposed to the page's top bar through nav: the "Embed page" action.
    var seenEmbed by remember { mutableIntStateOf(nav.embedRequest) }
    LaunchedEffect(nav.embedRequest) {
        if (nav.embedRequest != seenEmbed) {
            seenEmbed = nav.embedRequest
            embedPicker = true
        }
    }
}

@Composable
private fun NoteCard(page: Page, item: CanvasItem) {
    Surface(
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp, shadowElevation = 6.dp,
        modifier = Modifier.requiredWidth(item.w.toFloat().coerceAtLeast(200f).dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(page.displayTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            page.blocks.forEach { b ->
                when (b.kind) {
                    "divider" -> HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    "image" -> Unit
                    else -> Text(
                        when (b.kind) {
                            "bullet" -> "•  ${b.text}"
                            "todo" -> (if (b.checked == true) "☑  " else "☐  ") + b.text
                            "numbered" -> "–  ${b.text}"
                            else -> b.text
                        },
                        style = when (b.kind) {
                            "heading" -> MaterialTheme.typography.titleMedium
                            "code" -> MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            else -> MaterialTheme.typography.bodyMedium
                        },
                        color = if (b.kind == "quote") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
        }
    }
}

/** A free text label placed on the web canvas. */
@Composable
private fun TextLabel(item: CanvasItem) {
    Text(
        item.text.orEmpty().ifEmpty { " " }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.requiredWidth(item.w.toFloat().dp).padding(4.dp),
    )
}

@Composable
private fun StickyCard(item: CanvasItem) {
    val (bg, ink) = STICKY[item.color] ?: STICKY.getValue("amber")
    Surface(
        shape = MaterialTheme.shapes.medium, color = bg, shadowElevation = 4.dp,
        modifier = Modifier.requiredWidth(item.w.toFloat().dp).heightIn(min = item.h.toFloat().dp),
    ) {
        Text(item.text.orEmpty().ifEmpty { " " }, color = ink, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun EmbedCard(target: Page?, item: CanvasItem) {
    Surface(
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer, shadowElevation = 3.dp,
        modifier = Modifier.requiredWidth(item.w.toFloat().coerceAtLeast(160f).dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(target?.displayTitle ?: "Embedded Note", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(target?.excerpt(140) ?: "This page isn't available.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StickyEditor(item: CanvasItem, onDismiss: () -> Unit, onSave: (String, String) -> Unit, onDelete: () -> Unit) {
    var text by remember { mutableStateOf(item.text.orEmpty()) }
    var c by remember { mutableStateOf(item.color ?: "amber") }
    AlertDialog(
        onDismissRequest = { onSave(text, c) },
        title = { Text("Sticky note") },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, minLines = 4, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    STICKY.forEach { (name, pair) ->
                        Box(
                            Modifier.size(36.dp).background(pair.first, CircleShape)
                                .then(if (name == c) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), CircleShape) else Modifier)
                                .clickable { c = name },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton({ onSave(text, c) }) { Text("Done") } },
        dismissButton = { TextButton(onDelete) { Icon(Icons.Outlined.Delete, null); Text(" Delete") } },
    )
}

@Composable
private fun EmbedPicker(pages: List<Page>, onDismiss: () -> Unit, onPick: (Page) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Embed a page") },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(pages.sortedByDescending { it.updatedAt }, key = { it.id }) { p ->
                    ListItem({ Text(p.displayTitle) }, modifier = Modifier.clickable { onPick(p) })
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/**
 * A pill on the screen edge pointing at a world rect that is off screen; tap to pan there.
 * Position and rotation are computed in layout and draw, so panning never recomposes it.
 */
@Composable
private fun BoxScope.EdgeMarker(
    rect: () -> RectF?,
    tint: Color,
    transform: () -> Triple<Float, Float, Float>,
    insets: () -> Pair<Float, Float>,
    onTap: () -> Unit,
    label: String,
) {
    val sizePx = with(LocalDensity.current) { 40.dp.toPx() }
    val margin = with(LocalDensity.current) { 12.dp.toPx() }
    var angle by remember { mutableFloatStateOf(0f) }
    var bounds by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    Box(Modifier.matchParentSize().onSizeChanged { bounds = it })
    Surface(
        onClick = onTap, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 3.dp,
        modifier = Modifier
            .size(40.dp)
            .offset {
                val r = rect() ?: return@offset IntOffset(-100_000, 0)
                val (s, x, y) = transform()
                val (top, bottom) = insets()
                val w = bounds.width.toFloat(); val h = bounds.height.toFloat()
                val l = r.left * s + x; val t = r.top * s + y; val rr = r.right * s + x; val b = r.bottom * s + y
                // On screen (even partly): no marker.
                if (rr > 0 && l < w && b > top && t < h - bottom) return@offset IntOffset(-100_000, 0)
                val vx = w / 2; val vy = top + (h - top - bottom) / 2
                val dx = (l + rr) / 2 - vx; val dy = (t + b) / 2 - vy
                angle = Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat()
                val hw = w / 2 - margin - sizePx / 2; val hh = (h - top - bottom) / 2 - margin - sizePx / 2
                val k = minOf(if (dx != 0f) hw / kotlin.math.abs(dx) else Float.MAX_VALUE, if (dy != 0f) hh / kotlin.math.abs(dy) else Float.MAX_VALUE)
                IntOffset((vx + dx * k - sizePx / 2).toInt(), (vy + dy * k - sizePx / 2).toInt())
            }
            .semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Kind color in the middle, an arrow riding the rim toward the item.
            Box(Modifier.size(10.dp).background(tint, CircleShape))
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward, null,
                Modifier.size(40.dp).graphicsLayer { rotationZ = angle }.padding(start = 26.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
