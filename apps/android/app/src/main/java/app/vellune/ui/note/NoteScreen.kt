package app.vellune.ui.note

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import app.vellune.AppViewModel
import app.vellune.data.InkStroke
import app.vellune.data.Paper
import app.vellune.data.PenTool
import app.vellune.ink.Brushes
import app.vellune.ink.InkCanvasView
import app.vellune.ink.InkMode
import kotlinx.coroutines.delay

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NoteScreen(
    vm: AppViewModel,
    id: String,
    sharedKey: String,
    sharedScope: SharedTransitionScope,
    visibility: AnimatedVisibilityScope,
    boundsSpec: FiniteAnimationSpec<Rect>,
    onBack: () -> Unit,
) = with(sharedScope) {
    val note = vm.note(id) ?: return@with
    val context = LocalContext.current
    val prefs = vm.prefs
    val dark = isSystemInDarkTheme()
    val colors = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current

    var title by remember { mutableStateOf(note.title) }
    var body by remember { mutableStateOf(note.body) }
    var canvasMode by remember { mutableStateOf(note.canvas) }
    var paper by remember { mutableStateOf(note.paper) }

    // Pen state survives across notes and launches.
    var mode by remember {
        mutableStateOf(
            when (val m = prefs.getString("mode", "Pen")) {
                "Eraser" -> InkMode.Eraser
                "Lasso" -> InkMode.Lasso
                else -> InkMode.Draw(runCatching { PenTool.valueOf(m!!) }.getOrDefault(PenTool.Pen))
            },
        )
    }
    var lastDrawTool by remember { mutableStateOf((mode as? InkMode.Draw)?.tool ?: PenTool.Pen) }
    var color by remember { mutableIntStateOf(prefs.getInt("color", InkColors[0])) }
    var sizeTick by remember { mutableIntStateOf(0) }
    var fingerDraws by remember { mutableStateOf(prefs.getBoolean("finger_draws", true)) }
    var paletteOpen by remember { mutableStateOf(false) }
    var inking by remember { mutableStateOf(false) }
    var selection by remember { mutableIntStateOf(0) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var zoomShown by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val canvas = remember {
        InkCanvasView(context).apply {
            PenTool.entries.forEach { t -> sizes[t] = prefs.getFloat("size_$t", Brushes.defaultSize(t)) }
            this.fingerDraws = prefs.getBoolean("finger_draws", true)
            listener = object : InkCanvasView.Listener {
                override fun onEdit(added: List<InkStroke>, removedIds: List<String>) = vm.applyInk(id, added, removedIds)
                override fun onHistory(undo: Boolean, redo: Boolean) { canUndo = undo; canRedo = redo }
                override fun onSelection(count: Int) { selection = count }
                override fun onInking(active: Boolean) { inking = active; if (active) paletteOpen = false }
                override fun onZoom(scale: Float) { zoom = scale; zoomShown = true }
                override fun onStylusDetected() {
                    fingerDraws = false
                    prefs.edit { putBoolean("finger_draws", false) }
                }
            }
        }
    }

    LaunchedEffect(id) { canvas.setEntries(vm.loadInk(id, dark)) }
    LaunchedEffect(mode) {
        canvas.mode = mode
        prefs.edit { putString("mode", (mode as? InkMode.Draw)?.tool?.name ?: mode.toString()) }
        (mode as? InkMode.Draw)?.let { lastDrawTool = it.tool }
    }
    LaunchedEffect(color) { canvas.color = color; prefs.edit { putInt("color", color) } }
    LaunchedEffect(fingerDraws) { canvas.fingerDraws = fingerDraws }
    LaunchedEffect(paper) { canvas.setPaper(paper) }
    LaunchedEffect(title, body, dark) {
        canvas.setDoc(title, body, colors.onSurface.toArgb(), colors.onSurfaceVariant.toArgb())
    }
    LaunchedEffect(colors, dark) {
        canvas.setColors(
            paperColor = colors.surfaceContainerLowest.toArgb(),
            patternColor = colors.outlineVariant.toArgb(),
            accent = colors.primary.toArgb(),
            onAccent = colors.surface.toArgb(),
            isDark = dark,
        )
    }
    LaunchedEffect(zoom, zoomShown) { if (zoomShown) { delay(1400); zoomShown = false } }

    fun setMode(m: InkMode) {
        if (m == mode && m is InkMode.Draw) paletteOpen = !paletteOpen else paletteOpen = false
        mode = m
    }

    fun setCanvas(on: Boolean) {
        if (on == canvasMode) return
        haptic.performHapticFeedback(HapticFeedbackType.ToggleOn)
        canvasMode = on
        vm.update(id) { it.copy(canvas = on) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .sharedBounds(
                rememberSharedContentState(sharedKey), visibility,
                boundsTransform = { _, _ -> boundsSpec },
                clipInOverlayDuringTransition = OverlayClip(MaterialTheme.shapes.large),
            )
            .background(colors.surfaceContainerLowest),
    ) {
        // The canvas stays alive under the document, so switching modes keeps the view, zoom and undo history.
        AndroidView({ canvas }, Modifier.fillMaxSize())

        AnimatedVisibility(
            visible = !canvasMode,
            enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                slideInVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { it / 12 },
            exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
                slideOutVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { it / 12 },
        ) {
            DocPane(
                title = title, body = body, updatedAt = note.updatedAt,
                onTitle = { title = it; vm.updateText(id, it, body) },
                onBody = { body = it; vm.updateText(id, title, it) },
            )
        }

        TopBar(
            canvasMode = canvasMode,
            title = title,
            onTitle = { title = it; vm.updateText(id, it, body) },
            onBack = onBack,
            onMode = ::setCanvas,
            menu = menu,
            onMenu = { menu = it },
            menuContent = {
                if (canvasMode) {
                    Text("Paper", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    Paper.entries.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.name) },
                            trailingIcon = { if (p == paper) Icon(Icons.Outlined.Check, "Selected") },
                            onClick = { paper = p; vm.update(id) { it.copy(paper = p) } },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Draw with finger") },
                        leadingIcon = { Icon(Icons.Outlined.TouchApp, null) },
                        trailingIcon = { Switch(checked = fingerDraws, onCheckedChange = null) },
                        onClick = {
                            fingerDraws = !fingerDraws
                            prefs.edit { putBoolean("finger_draws", fingerDraws) }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Recenter") },
                        leadingIcon = { Icon(Icons.Outlined.CenterFocusStrong, null) },
                        onClick = { menu = false; canvas.recenter() },
                    )
                    HorizontalDivider()
                }
                DropdownMenuItem(
                    text = { Text("Delete note") },
                    leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                    onClick = { menu = false; confirmDelete = true },
                )
            },
        )

        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                icon = { Icon(Icons.Outlined.Delete, null) },
                title = { Text("Delete this note?") },
                text = { Text("Its text and ink are removed from this device.") },
                confirmButton = {
                    TextButton(onClick = { confirmDelete = false; vm.deleteNow(id); onBack() }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            )
        }

        if (canvasMode) BoxWithConstraints(Modifier.align(Alignment.BottomCenter)) {
            val compact = maxWidth < 560.dp
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp, start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (compact) HistoryBar(
                    canUndo, canRedo, { canvas.undo() }, { canvas.redo() },
                    Modifier.align(Alignment.End).padding(bottom = 12.dp),
                )
                AnimatedVisibility(
                    visible = zoomShown,
                    enter = fadeIn() + scaleIn(MaterialTheme.motionScheme.fastSpatialSpec(), 0.8f),
                    exit = fadeOut() + scaleOut(targetScale = 0.8f),
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = colors.inverseSurface,
                        modifier = Modifier.padding(bottom = 12.dp).clickable { canvas.recenter() },
                    ) {
                        Text(
                            "${(zoom * 100).toInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.inverseOnSurface,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
                val tool = (mode as? InkMode.Draw)?.tool ?: lastDrawTool
                AnimatedVisibility(
                    visible = paletteOpen,
                    enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()) +
                        scaleIn(MaterialTheme.motionScheme.defaultSpatialSpec(), 0.85f,
                            androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)),
                    exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
                        scaleOut(MaterialTheme.motionScheme.fastSpatialSpec(), 0.9f,
                            androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)),
                ) {
                    val size = remember(sizeTick, tool) { canvas.sizes.getValue(tool) }
                    PalettePanel(
                        tool = tool,
                        color = color,
                        size = size,
                        dark = dark,
                        onColor = { color = it },
                        onSize = {
                            canvas.sizes[tool] = it
                            sizeTick++
                            prefs.edit { putFloat("size_$tool", it) }
                        },
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                PenToolbar(
                    mode = mode,
                    color = color,
                    inking = inking,
                    selection = selection,
                    canUndo = canUndo,
                    canRedo = canRedo,
                    paletteOpen = paletteOpen,
                    showHistory = !compact,
                    onMode = ::setMode,
                    onPalette = { paletteOpen = !paletteOpen },
                    onUndo = { canvas.undo() },
                    onRedo = { canvas.redo() },
                    onDuplicate = { canvas.duplicateSelection() },
                    onDeleteSelection = { canvas.deleteSelection() },
                    onClearSelection = { canvas.clearSelection() },
                )
            }
        }
    }
}


@Composable
private fun TopBar(
    canvasMode: Boolean,
    title: String,
    onTitle: (String) -> Unit,
    onBack: () -> Unit,
    onMode: (Boolean) -> Unit,
    menu: Boolean,
    onMenu: (Boolean) -> Unit,
    menuContent: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalIconButton(onClick = onBack, shapes = androidx.compose.material3.IconButtonDefaults.shapes()) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
        }
        Box(Modifier.weight(1f)) {
            androidx.compose.animation.AnimatedVisibility(canvasMode, enter = fadeIn(), exit = fadeOut()) {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    BasicTextField(
                        value = title,
                        onValueChange = onTitle,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        decorationBox = { inner ->
                            if (title.isEmpty()) Text("Untitled canvas", style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            inner()
                        },
                    )
                }
            }
        }
        // Connected toggle: which face of the note is showing. Remembered per note.
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            ToggleButton(
                checked = !canvasMode,
                onCheckedChange = { onMode(false) },
                shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                modifier = Modifier.semantics { stateDescription = if (!canvasMode) "Showing" else "" },
            ) { Icon(Icons.AutoMirrored.Outlined.Notes, "Document") }
            ToggleButton(
                checked = canvasMode,
                onCheckedChange = { onMode(true) },
                shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                modifier = Modifier.semantics { stateDescription = if (canvasMode) "Showing" else "" },
            ) { Icon(Icons.Outlined.Draw, "Canvas") }
        }
        Box {
            FilledTonalIconButton(onClick = { onMenu(true) }) { Icon(Icons.Outlined.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { onMenu(false) }, shape = MaterialTheme.shapes.large) {
                menuContent()
            }
        }
    }
}

@Composable
private fun DocPane(
    title: String,
    body: String,
    updatedAt: Long,
    onTitle: (String) -> Unit,
    onBody: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.surface)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 24.dp, end = 24.dp, top = 80.dp, bottom = 160.dp),
        ) {
            BasicTextField(
                value = title,
                onValueChange = onTitle,
                textStyle = MaterialTheme.typography.displaySmall.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (title.isEmpty()) Text("Untitled", style = MaterialTheme.typography.displaySmall, color = colors.outline)
                    inner()
                },
            )
            Spacer(Modifier.height(8.dp))
            val words = remember(body) { body.split(Regex("\\s+")).count { it.isNotBlank() } }
            Text(
                "Edited ${DateUtils.getRelativeTimeSpanString(updatedAt)} · $words words",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.padding(vertical = 20.dp), color = colors.outlineVariant)
            BasicTextField(
                value = body,
                onValueChange = onBody,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface, lineHeight = MaterialTheme.typography.bodyLarge.fontSize * 1.6),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (body.isEmpty()) Text("Start writing…", style = MaterialTheme.typography.bodyLarge, color = colors.outline)
                    inner()
                },
            )
        }
    }
}
