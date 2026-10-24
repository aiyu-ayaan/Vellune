package com.aiyu.vellune.ui.canvas

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.BorderColor
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.FilterCenterFocus
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.strokes.Stroke
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aiyu.vellune.R
import com.aiyu.vellune.container
import com.aiyu.vellune.core.input.ArbiterConfig
import com.aiyu.vellune.data.BrushKind
import com.aiyu.vellune.data.StoredStroke
import com.aiyu.vellune.data.Viewport
import com.aiyu.vellune.ui.components.VelluneIcons
import com.aiyu.vellune.ui.home.RenameDialog
import com.aiyu.vellune.ui.theme.Dimens

/** Swatches; null is the theme ink (dark on light paper, light on dark paper). */
private val Swatches: List<Int?> = listOf(
    null,
    0xFF0D9488.toInt(), // teal
    0xFF2563EB.toInt(), // blue
    0xFF7C3AED.toInt(), // violet
    0xFFDB2777.toInt(), // pink
    0xFFDC2626.toInt(), // red
    0xFFEA580C.toInt(), // orange
    0xFFF59E0B.toInt(), // amber
    0xFF16A34A.toInt(), // green
)

private data class ToolEntry(val tool: Tool, val icon: ImageVector, val labelRes: Int)

private val ToolEntries = listOf(
    ToolEntry(Tool.Pen, Icons.Rounded.Draw, R.string.tool_pen),
    ToolEntry(Tool.Marker, Icons.Rounded.Brush, R.string.tool_marker),
    ToolEntry(Tool.Highlighter, Icons.Rounded.BorderColor, R.string.tool_highlighter),
    ToolEntry(Tool.Eraser, VelluneIcons.Eraser, R.string.tool_eraser),
    ToolEntry(Tool.Hand, Icons.Rounded.PanTool, R.string.tool_hand),
)

@Composable
fun CanvasScreen(canvasId: String, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val app = context.container
    val viewModel = viewModel(key = canvasId) { CanvasViewModel(app.canvases, canvasId) }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val strokes by viewModel.strokes.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val xdpi = context.resources.displayMetrics.xdpi
    val config = remember(settings, xdpi) {
        ArbiterConfig(
            mode = settings.inputMode,
            lockoutEnabled = settings.lockoutEnabled,
            lockoutMs = settings.lockoutMs.toLong(),
            palmMajorPx = settings.palmSizeMm * xdpi / 25.4f,
            slopPx = with(density) { 8.dp.toPx() },
        )
    }

    // One trace file per canvas session while recording is on.
    DisposableEffect(settings.recordTraces) {
        if (settings.recordTraces) app.traces.start("mode=${settings.inputMode} canvas=$canvasId")
        onDispose { app.traces.stop() }
    }

    val scheme = MaterialTheme.colorScheme
    var brushPanel by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(scheme.surfaceContainerLowest)) {
        AndroidView(
            factory = { ctx ->
                InkCanvasLayout(ctx, object : InkCanvasCallbacks {
                    override fun onStrokeFinished(stored: StoredStroke, stroke: Stroke) = viewModel.addStroke(stored, stroke)
                    override fun onEraseBegin() = viewModel.beginErase()
                    override fun onEraseAt(x: Float, y: Float, radius: Float) = viewModel.eraseAt(x, y, radius)
                    override fun onEraseEnd() = viewModel.endErase()
                    override fun onViewportChanged(viewport: Viewport) = viewModel.onViewportChanged(viewport)
                    override fun onStats(stats: InkStats) = viewModel.onStats(stats)
                }).also { it.contentDescription = ctx.getString(R.string.cd_canvas) }
            },
            update = { view ->
                view.tool = ui.tool
                view.themeInk = scheme.onSurface.toArgb()
                view.predictionEnabled = settings.prediction
                view.statsEnabled = settings.showHud
                view.recorder = if (settings.recordTraces) app.traces else null
                view.configure(config)
                view.setPaper(scheme.surfaceContainerLowest.toArgb(), scheme.outlineVariant.copy(alpha = 0.7f).toArgb())
                view.setStrokes(strokes)
                view.applyViewport(viewModel.viewport, ui.viewportEpoch)
            },
            modifier = Modifier.fillMaxSize(),
        )

        CanvasTopBar(
            ui = ui,
            hudOn = settings.showHud,
            onBack = onBack,
            onRename = { renaming = true },
            onUndo = viewModel::undo,
            onRedo = viewModel::redo,
            onResetView = viewModel::resetViewport,
            onToggleHud = { app.settings.update { it.copy(showHud = !it.showHud) } },
            onOpenSettings = onOpenSettings,
            onClear = { confirmClear = true },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        if (settings.showHud) {
            InputHud(ui.stats, strokes.size, settings.recordTraces, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 72.dp))
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = Dimens.lg),
        ) {
            AnimatedVisibility(
                visible = brushPanel && ui.tool.tool.brush != null,
                enter = fadeIn() + scaleIn(initialScale = 0.9f),
                exit = fadeOut() + scaleOut(targetScale = 0.9f),
            ) {
                ui.tool.tool.brush?.let { kind ->
                    BrushPanel(
                        kind = kind,
                        style = ui.tool.styles.getValue(kind),
                        themeInk = scheme.onSurface,
                        onStyle = { viewModel.setStyle(kind, it) },
                        modifier = Modifier.padding(bottom = Dimens.md),
                    )
                }
            }
            ToolBar(
                tool = ui.tool,
                themeInk = scheme.onSurface,
                panelOpen = brushPanel,
                onTool = { t ->
                    // Tapping the active brush again opens its style panel.
                    if (t == ui.tool.tool && t.brush != null) brushPanel = !brushPanel else viewModel.setTool(t)
                    if (t.brush == null) brushPanel = false
                },
                onTogglePanel = { brushPanel = !brushPanel },
            )
        }
    }

    if (renaming) {
        RenameDialog(initial = ui.title, onDismiss = { renaming = false }, onConfirm = { viewModel.rename(it); renaming = false })
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = null) },
            title = { Text(stringResource(R.string.clear_canvas_title)) },
            text = { Text(stringResource(R.string.clear_canvas_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.clear(); confirmClear = false }) {
                    Text(stringResource(R.string.action_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun CanvasTopBar(
    ui: CanvasUi,
    hudOn: Boolean,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onResetView: () -> Unit,
    onToggleHud: () -> Unit,
    onOpenSettings: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Dimens.md, vertical = Dimens.sm),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 2.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = Dimens.lg)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back)) }
                Text(
                    ui.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 200.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClickLabel = stringResource(R.string.action_rename), onClick = onRename)
                        .padding(vertical = Dimens.xs),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 2.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onUndo, enabled = ui.canUndo) { Icon(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.action_undo)) }
                IconButton(onClick = onRedo, enabled = ui.canRedo) { Icon(Icons.AutoMirrored.Rounded.Redo, stringResource(R.string.action_redo)) }
                TextButton(onClick = onResetView) {
                    Text(stringResource(R.string.zoom_percent, ui.zoomPercent), style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
                }
                Box {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.cd_more_options)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = MaterialTheme.shapes.large) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_reset_view)) },
                            leadingIcon = { Icon(Icons.Rounded.FilterCenterFocus, null) },
                            onClick = { menu = false; onResetView() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_input_hud)) },
                            leadingIcon = { Icon(Icons.Rounded.Speed, null) },
                            trailingIcon = { Checkbox(checked = hudOn, onCheckedChange = null) },
                            onClick = { menu = false; onToggleHud() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_input_settings)) },
                            leadingIcon = { Icon(Icons.Rounded.Tune, null) },
                            onClick = { menu = false; onOpenSettings() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_clear_canvas), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onClear() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolBar(tool: ToolState, themeInk: Color, panelOpen: Boolean, onTool: (Tool) -> Unit, onTogglePanel: () -> Unit) {
    HorizontalFloatingToolbar(expanded = true, colors = FloatingToolbarDefaults.standardFloatingToolbarColors()) {
        ToolEntries.forEach { entry ->
            val label = stringResource(entry.labelRes)
            FilledIconToggleButton(
                checked = tool.tool == entry.tool,
                onCheckedChange = { onTool(entry.tool) },
                shapes = IconButtonDefaults.toggleableShapes(),
                colors = IconButtonDefaults.filledIconToggleButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    checkedContainerColor = MaterialTheme.colorScheme.primary,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.semantics { contentDescription = label },
            ) {
                Icon(entry.icon, contentDescription = null)
            }
        }
        VerticalDivider(Modifier.padding(horizontal = Dimens.xs, vertical = Dimens.md).size(width = 1.dp, height = 24.dp))
        val style = tool.activeStyle
        val swatch = style.color?.let(::Color) ?: themeInk
        val panelLabel = stringResource(R.string.cd_brush_style)
        IconButton(
            onClick = onTogglePanel,
            enabled = tool.tool.brush != null,
            modifier = Modifier.semantics { contentDescription = panelLabel; selected = panelOpen },
        ) {
            Box(
                Modifier
                    .size(26.dp)
                    .border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(swatch)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrushPanel(kind: BrushKind, style: BrushStyle, themeInk: Color, onStyle: (BrushStyle) -> Unit, modifier: Modifier = Modifier) {
    val maxSize = if (kind == BrushKind.Highlighter) 64f else 40f
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 3.dp,
        modifier = modifier.widthIn(max = 360.dp),
    ) {
        Column(Modifier.padding(Dimens.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(kind.labelRes), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.brush_size_value, style.size.toInt()),
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(Dimens.md))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.sm), verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                Swatches.forEach { argb ->
                    val color = argb?.let(::Color) ?: themeInk
                    val selected = style.color == argb
                    val label = if (argb == null) stringResource(R.string.color_ink) else stringResource(R.string.color_swatch, String.format("#%06X", argb and 0xFFFFFF))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(color)
                            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                            .clickable(role = Role.RadioButton, onClickLabel = label) { onStyle(style.copy(color = argb)) }
                            .semantics { contentDescription = label; this.selected = selected },
                    ) {
                        if (selected) Icon(
                            Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp),
                            tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                        )
                    }
                }
            }
            Spacer(Modifier.size(Dimens.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FiberManualRecord, null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = style.size,
                    onValueChange = { onStyle(style.copy(size = it)) },
                    valueRange = 1f..maxSize,
                    modifier = Modifier.weight(1f).padding(horizontal = Dimens.sm),
                )
                Icon(Icons.Rounded.FiberManualRecord, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun InputHud(stats: InkStats, strokeCount: Int, recording: Boolean, modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.88f), modifier = modifier) {
        val mono = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.md), modifier = Modifier.padding(horizontal = Dimens.lg, vertical = Dimens.sm)) {
            val c = MaterialTheme.colorScheme.inverseOnSurface
            Text(stats.state, style = mono, color = c)
            Text(stats.tool, style = mono, color = c)
            Text(stringResource(R.string.hud_pressure, stats.pressure), style = mono, color = c)
            Text(stringResource(R.string.hud_rate, stats.samplesPerSec), style = mono, color = c)
            Text(stringResource(R.string.hud_strokes, strokeCount), style = mono, color = c)
            if (recording) Text(stringResource(R.string.hud_rec), style = mono, color = MaterialTheme.colorScheme.inversePrimary)
        }
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

val BrushKind.labelRes: Int
    get() = when (this) {
        BrushKind.Pen -> R.string.tool_pen
        BrushKind.Marker -> R.string.tool_marker
        BrushKind.Highlighter -> R.string.tool_highlighter
    }
