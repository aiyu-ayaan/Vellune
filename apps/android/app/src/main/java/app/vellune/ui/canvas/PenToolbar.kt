package app.vellune.ui.canvas

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.HighlightAlt
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vellune.ink.PenTool
import app.vellune.ink.Brushes
import app.vellune.ink.InkMode

/** Ink colors: neutral, then the color wheel. Black reads as light ink on a dark page. */
val InkColors = listOf(
    0xFF1C1B1F, 0xFF6B6870, 0xFF5B53A8, 0xFF1E6FD9, 0xFF00897B,
    0xFF2E9D4A, 0xFFF2B705, 0xFFF26B1D, 0xFFE0323C, 0xFFD6338A,
).map { it.toInt() }

private val Eraser: ImageVector by lazy {
    ImageVector.Builder("Eraser", 24.dp, 24.dp, 960f, 960f).apply {
        // Material Symbols "ink_eraser" (viewBox 0 -960 960 960), shifted into 0..960.
        addGroup(translationY = 960f)
        path(fill = androidx.compose.ui.graphics.SolidColor(Color.Black)) {
            moveTo(690f, -240f); horizontalLineToRelative(190f); verticalLineToRelative(80f); horizontalLineTo(610f); lineToRelative(80f, -80f); close()
            moveTo(190f, -160f); lineToRelative(-85f, -85f)
            quadToRelative(-23f, -23f, -23.5f, -57f); reflectiveQuadToRelative(22.5f, -58f)
            lineToRelative(440f, -456f); quadToRelative(23f, -24f, 56.5f, -24f); reflectiveQuadToRelative(56.5f, 23f)
            lineToRelative(199f, 199f); quadToRelative(23f, 23f, 23f, 57f); reflectiveQuadToRelative(-23f, 57f)
            lineTo(520f, -160f); horizontalLineTo(190f); close()
            moveTo(486f, -240f); lineToRelative(314f, -322f); lineToRelative(-198f, -198f); lineToRelative(-442f, 456f)
            lineToRelative(64f, 64f); horizontalLineToRelative(262f); close()
        }
        clearGroup()
    }.build()
}

private data class ToolItem(val mode: InkMode, val label: String, val icon: ImageVector)

private val tools = listOf(
    ToolItem(InkMode.Select, "Select", Icons.Outlined.NearMe),
    ToolItem(InkMode.Draw(PenTool.Pen), "Pen", Icons.Outlined.Draw),
    ToolItem(InkMode.Draw(PenTool.Pencil), "Pencil", Icons.Outlined.Create),
    ToolItem(InkMode.Draw(PenTool.Highlighter), "Highlighter", Icons.Outlined.BorderColor),
    ToolItem(InkMode.Eraser, "Eraser", Eraser),
    ToolItem(InkMode.Lasso, "Lasso select", Icons.Outlined.HighlightAlt),
    ToolItem(InkMode.Sticky, "Sticky note", Icons.AutoMirrored.Outlined.StickyNote2),
)

/**
 * Floating pen toolbar. Swaps to selection actions while strokes are lassoed; fades back while the pen is down so
 * it never competes with the ink.
 */
@Composable
fun PenToolbar(
    mode: InkMode,
    color: Int,
    inking: Boolean,
    selection: Int,
    canUndo: Boolean,
    canRedo: Boolean,
    paletteOpen: Boolean,
    showHistory: Boolean,
    compact: Boolean,
    onMode: (InkMode) -> Unit,
    onPalette: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDuplicate: () -> Unit,
    onDeleteSelection: () -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val fade by animateFloatAsState(if (inking) 0.35f else 1f, MaterialTheme.motionScheme.fastEffectsSpec(), label = "fade")
    val motion = MaterialTheme.motionScheme
    HorizontalFloatingToolbar(expanded = true, modifier = modifier.graphicsLayer { alpha = fade }) {
        AnimatedContent(
            targetState = selection > 0,
            transitionSpec = {
                (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), 0.85f)) togetherWith
                    fadeOut(motion.fastEffectsSpec())
            },
            label = "toolbar",
        ) { selecting ->
            if (selecting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$selection selected",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    ColorButton(Brushes.displayColor(color, isSystemInDarkTheme(), PenTool.Pen) or (0xFF shl 24), paletteOpen, enabled = true, onClick = onPalette)
                    IconButton(onClick = onDuplicate) { Icon(Icons.Outlined.ContentCopy, "Duplicate") }
                    IconButton(onClick = onDeleteSelection) { Icon(Icons.Outlined.Delete, "Delete selection") }
                    IconButton(onClick = onClearSelection) { Icon(Icons.Outlined.Close, "Clear selection") }
                }
            } else {
                // Phones get slightly smaller tool targets so the whole set and the color fit without scrolling.
                val button = if (compact) 44.dp else 48.dp
                Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tools.forEach { t ->
                        val checked = mode == t.mode
                        Tooltip(t.label) {
                            IconToggleButton(
                                checked = checked,
                                onCheckedChange = {
                                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    onMode(t.mode)
                                },
                                shapes = IconButtonDefaults.toggleableShapes(),
                                colors = IconButtonDefaults.iconToggleButtonColors(
                                    checkedContainerColor = MaterialTheme.colorScheme.primary,
                                    checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                                modifier = Modifier.size(button),
                            ) { Icon(t.icon, t.label) }
                        }
                    }
                }
                    VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
                    val tool = (mode as? InkMode.Draw)?.tool ?: PenTool.Pen
                    ColorButton(Brushes.displayColor(color, isSystemInDarkTheme(), tool) or (0xFF shl 24), paletteOpen, enabled = mode is InkMode.Draw, size = button, onClick = onPalette)
                    if (showHistory) HistoryButtons(canUndo, canRedo, onUndo, onRedo)
                }
            }
        }
    }
}

@Composable
fun HistoryButtons(canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit) {
    IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.AutoMirrored.Outlined.Undo, "Undo") }
    IconButton(onClick = onRedo, enabled = canRedo) { Icon(Icons.AutoMirrored.Outlined.Redo, "Redo") }
}

/** Undo/redo on its own small floating bar, for windows too narrow to hold them in the pen toolbar. */
@Composable
fun HistoryBar(canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit, modifier: Modifier = Modifier) =
    HorizontalFloatingToolbar(expanded = true, modifier = modifier) { HistoryButtons(canUndo, canRedo, onUndo, onRedo) }

@Composable
private fun Tooltip(label: String, content: @Composable () -> Unit) = TooltipBox(
    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
    tooltip = { PlainTooltip { Text(label) } },
    state = rememberTooltipState(),
    content = content,
)

@Composable
private fun ColorButton(color: Int, open: Boolean, enabled: Boolean, size: androidx.compose.ui.unit.Dp = 48.dp, onClick: () -> Unit) {
    val ring by animateDpAsState(if (open) 3.dp else 2.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "ring")
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button, onClickLabel = "Pen color and size")
            .semantics { contentDescription = "Pen color and size" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(26.dp)
                .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
                .border(ring, MaterialTheme.colorScheme.outline, CircleShape)
                .padding(ring + 1.dp)
                .background(Color(color), CircleShape),
        )
    }
}

/** Color swatches (the chosen one morphs into a cookie) and a size slider with a live stroke preview. */
@Composable
fun PalettePanel(
    tool: PenTool,
    color: Int,
    size: Float,
    dark: Boolean,
    onColor: (Int) -> Unit,
    onSize: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    Surface(
        modifier = modifier.widthIn(max = 400.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Color", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            InkColors.chunked(5).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { c ->
                        val isSelected = c == color
                        val scale by animateFloatAsState(if (isSelected) 1.15f else 1f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "s")
                        val shape = if (isSelected) MaterialShapes.Cookie6Sided.toShape() else CircleShape
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable(role = Role.RadioButton) {
                                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    onColor(c)
                                }
                                .semantics { selected = isSelected; contentDescription = colorName(c) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .graphicsLayer { scaleX = scale; scaleY = scale }
                                    .background(Color(Brushes.displayColor(c, dark, PenTool.Pen)), shape)
                                    // The ink swatch gets an outline: it can match the panel.
                                    .then(if (c == InkColors[0]) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape) else Modifier),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Size", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text("${size.toInt()}", style = MaterialTheme.typography.labelLarge)
            }
            val range = Brushes.sizeRange(tool)
            Slider(
                value = size,
                onValueChange = onSize,
                valueRange = range,
                modifier = Modifier.semantics { contentDescription = "Stroke size" },
            )
            // Live preview: a soft S-curve at the chosen width and color.
            val previewColor = Color(Brushes.displayColor(color, dark, tool))
            Canvas(Modifier.fillMaxWidth().height(40.dp)) {
                val p = Path().apply {
                    moveTo(12f, this@Canvas.size.height * 0.65f)
                    cubicTo(
                        this@Canvas.size.width * 0.3f, -this@Canvas.size.height * 0.2f,
                        this@Canvas.size.width * 0.6f, this@Canvas.size.height * 1.2f,
                        this@Canvas.size.width - 12f, this@Canvas.size.height * 0.35f,
                    )
                }
                drawPath(p, previewColor, style = Stroke(width = size * density, cap = StrokeCap.Round))
            }
        }
    }
}

private fun colorName(c: Int) = when (InkColors.indexOf(c)) {
    0 -> "Ink"; 1 -> "Graphite"; 2 -> "Indigo"; 3 -> "Blue"; 4 -> "Teal"
    5 -> "Green"; 6 -> "Yellow"; 7 -> "Orange"; 8 -> "Red"; else -> "Pink"
}
