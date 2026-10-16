package app.vellune.ui.home

import android.graphics.Matrix
import android.graphics.RectF
import android.os.Build
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vellune.ui.theme.rememberReducedMotion
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.ink.brush.StockTextureBitmapStore
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import app.vellune.AppViewModel
import app.vellune.data.Note
import app.vellune.data.Paper
import kotlinx.coroutines.launch

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    vm: AppViewModel,
    sharedScope: SharedTransitionScope,
    visibility: AnimatedVisibilityScope,
    boundsSpec: FiniteAnimationSpec<Rect>,
    dynamicColor: Boolean,
    onDynamicColor: (Boolean) -> Unit,
    onOpen: (id: String, origin: String) -> Unit,
) = with(sharedScope) {
    val notes by vm.notes.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var fabOpen by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val renderer = remember { CanvasStrokeRenderer.create(StockTextureBitmapStore(context.resources)) }

    fun newNote(canvas: Boolean) {
        fabOpen = false
        scope.launch { onOpen(vm.create(Paper.Dots, canvas).id, "fab") }
    }

    fun delete(note: Note) = scope.launch {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val undo = vm.delete(note.id)
        val r = snackbar.showSnackbar("Note deleted", actionLabel = "Undo", withDismissAction = true)
        if (r == SnackbarResult.ActionPerformed) undo()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                LargeFlexibleTopAppBar(
                    title = { Text("Vellune") },
                    subtitle = { Text("Where thoughts take form") },
                    actions = {
                        IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                            Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, if (searching) "Close search" else "Search")
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = MaterialTheme.shapes.large) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    DropdownMenuItem(
                                        text = { Text("Wallpaper colors") },
                                        leadingIcon = { Icon(Icons.Outlined.Palette, null) },
                                        trailingIcon = { Switch(checked = dynamicColor, onCheckedChange = null) },
                                        onClick = { onDynamicColor(!dynamicColor) },
                                    )
                                }
                            }
                        }
                    },
                    scrollBehavior = scroll,
                )
                AnimatedVisibility(searching, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search notes") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        singleLine = true,
                        shape = CircleShape,
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButtonMenu(
                expanded = fabOpen,
                button = {
                    ToggleFloatingActionButton(
                        checked = fabOpen,
                        onCheckedChange = { fabOpen = it },
                        modifier = Modifier
                            .sharedBounds(rememberSharedContentState("fab"), visibility, boundsTransform = { _, _ -> boundsSpec })
                            .semantics { contentDescription = if (fabOpen) "Close menu" else "New note" },
                    ) {
                        val progress = checkedProgress
                        Icon(
                            if (progress > 0.5f) Icons.Outlined.Close else Icons.Outlined.Add,
                            contentDescription = null,
                            modifier = Modifier.animateIcon({ progress }),
                        )
                    }
                },
            ) {
                FloatingActionButtonMenuItem(
                    onClick = { newNote(canvas = true) },
                    icon = { Icon(Icons.Outlined.Draw, null) },
                    text = { Text("Canvas note") },
                )
                FloatingActionButtonMenuItem(
                    onClick = { newNote(canvas = false) },
                    icon = { Icon(Icons.AutoMirrored.Outlined.Notes, null) },
                    text = { Text("Text note") },
                )
            }
        },
    ) { padding ->
        val all = notes
        val shown = remember(all, query) {
            all.orEmpty().filter { query.isBlank() || it.title.contains(query, true) || it.body.contains(query, true) }
        }
        if (all != null && shown.isEmpty()) {
            EmptyState(searching = query.isNotBlank(), modifier = Modifier.padding(padding))
            return@Scaffold
        }
        val pinned = shown.filter { it.pinned }
        val rest = shown.filterNot { it.pinned }
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Adaptive(180.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalItemSpacing = 12.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (pinned.isNotEmpty()) {
                item(key = "h-pinned", span = StaggeredGridItemSpan.FullLine) { Section("Pinned", Modifier.animateItem()) }
                items(pinned, key = { it.id }) { note ->
                    NoteCard(note, vm, renderer, visibility, boundsSpec, onOpen, ::delete, Modifier.animateItem())
                }
                if (rest.isNotEmpty()) item(key = "h-rest", span = StaggeredGridItemSpan.FullLine) { Section("Notes", Modifier.animateItem()) }
            }
            items(rest, key = { it.id }) { note ->
                NoteCard(note, vm, renderer, visibility, boundsSpec, onOpen, ::delete, Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun Section(text: String, modifier: Modifier) = Text(
    text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
    modifier = modifier.padding(start = 4.dp, top = 8.dp),
)

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
private fun SharedTransitionScope.NoteCard(
    note: Note,
    vm: AppViewModel,
    renderer: CanvasStrokeRenderer,
    visibility: AnimatedVisibilityScope,
    boundsSpec: FiniteAnimationSpec<Rect>,
    onOpen: (String, String) -> Unit,
    onDelete: (Note) -> Unit,
    modifier: Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val dark = isSystemInDarkTheme()
    val key = "note-${note.id}"
    val ink by produceState<List<Stroke>>(emptyList(), note.id, note.updatedAt, dark) { value = vm.preview(note, dark) }
    Box(modifier) {
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier
                .fillMaxWidth()
                .sharedBounds(rememberSharedContentState(key), visibility, boundsTransform = { _, _ -> boundsSpec }),
        ) {
            Column(
                Modifier
                    .combinedClickable(
                        onClick = { onOpen(note.id, key) },
                        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
                        onLongClickLabel = "Note actions",
                    )
                    .padding(16.dp),
            ) {
                if (ink.isNotEmpty()) {
                    InkThumbnail(ink, renderer, Modifier.fillMaxWidth().height(if (note.canvas) 140.dp else 88.dp))
                    Spacer(Modifier.height(12.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (note.pinned) Icon(Icons.Outlined.PushPin, "Pinned", Modifier.size(16.dp).padding(end = 4.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        note.title.ifBlank { if (note.canvas) "Untitled canvas" else "Untitled" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (note.body.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        note.body, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (note.canvas) Icons.Outlined.Draw else Icons.AutoMirrored.Outlined.Notes, null,
                        Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        DateUtils.getRelativeTimeSpanString(note.updatedAt).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = MaterialTheme.shapes.large) {
            DropdownMenuItem(
                text = { Text(if (note.pinned) "Unpin" else "Pin") },
                leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                onClick = { menu = false; vm.update(note.id) { it.copy(pinned = !it.pinned) } },
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                onClick = { menu = false; onDelete(note) },
            )
        }
    }
}

/** Fits the page's ink into the card, never scaling past 100% so a single word stays word-sized. */
@Composable
private fun InkThumbnail(strokes: List<Stroke>, renderer: CanvasStrokeRenderer, modifier: Modifier) {
    val bounds = remember(strokes) {
        RectF().also { r ->
            strokes.forEach { s -> s.shape.computeBoundingBox()?.let { r.union(it.xMin, it.yMin, it.xMax, it.yMax) } }
        }
    }
    Canvas(modifier) {
        if (bounds.isEmpty) return@Canvas
        val scale = minOf(size.width / bounds.width(), size.height / bounds.height(), density)
        val m = Matrix().apply {
            setTranslate(-bounds.left, -bounds.top)
            postScale(scale, scale)
            postTranslate((size.width - bounds.width() * scale) / 2, 0f)
        }
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.save()
            nc.clipRect(0f, 0f, size.width, size.height)
            nc.concat(m)
            strokes.forEach { renderer.draw(nc, it, m) }
            nc.restore()
        }
    }
}

@Composable
private fun EmptyState(searching: Boolean, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The cookie turns slowly, the icon stays upright; still when animations are off.
        val still = rememberReducedMotion()
        val spin by rememberInfiniteTransition(label = "empty").animateFloat(
            0f, if (still) 0f else 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "spin",
        )
        Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.fillMaxSize().graphicsLayer { rotationZ = spin }
                    .background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie9Sided.toShape()),
            )
            Icon(
                if (searching) Icons.Outlined.Search else Icons.Outlined.Draw, null,
                Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            if (searching) "Nothing matches" else "A blank page awaits",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (searching) "Try another word." else "Tap + to start a canvas or a text note.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 360.dp),
        )
    }
}
