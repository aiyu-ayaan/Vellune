package app.vellune.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Panel { Start, Closed, End }

/** Which panel the content card has slid away from, Discord style. */
@Stable
class PanelsState {
    internal val drag = AnchoredDraggableState(Panel.Closed)
    val target: Panel get() = drag.targetValue
    val offset: Float get() = drag.offset.takeUnless { it.isNaN() } ?: 0f

    suspend fun open(p: Panel) {
        if (drag.anchors.hasPositionFor(p)) drag.animateTo(p, spring(dampingRatio = 0.86f, stiffness = 520f))
    }

    suspend fun close() = open(Panel.Closed)
}

/**
 * Discord's overlapping panels: the sidebar and the info panel sit beneath the content card, which
 * follows the finger left or right and settles on a spring. Offsets live in the layout and draw
 * phases only, so dragging never recomposes the content.
 */
@Composable
fun OverlappingPanels(
    state: PanelsState,
    start: @Composable () -> Unit,
    end: (@Composable () -> Unit)?,
    gesturesEnabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val corner = with(density) { 28.dp.toPx() }
    BoxWithConstraints(modifier.fillMaxSize().background(colors.surfaceContainerLow)) {
        // A strip of the content stays visible (and tappable) beside an open panel.
        val startW = (maxWidth - 64.dp).coerceAtMost(400.dp)
        val endW = (maxWidth - 64.dp).coerceAtMost(380.dp)
        val startPx = with(density) { startW.toPx() }
        val endPx = with(density) { endW.toPx() }
        val hasEnd = end != null
        LaunchedEffect(startPx, endPx, hasEnd) {
            state.drag.updateAnchors(
                DraggableAnchors {
                    Panel.Start at startPx
                    Panel.Closed at 0f
                    if (hasEnd) Panel.End at -endPx
                },
                if (!hasEnd && state.drag.targetValue == Panel.End) Panel.Closed else state.drag.targetValue,
            )
        }
        val side by remember { derivedStateOf { if (state.offset < 0f) Panel.End else Panel.Start } }
        val open by remember { derivedStateOf { abs(state.offset) > 1f } }
        BackHandler(state.target != Panel.Closed) { scope.launch { state.close() } }

        val bg = colors.surfaceContainerLow
        // Only the panel on the side the content moved away from is drawn; the other stays composed for instant reveal.
        Box(
            Modifier.width(startW).fillMaxHeight().zIndex(if (side == Panel.Start) 1f else 0f)
                .graphicsLayer { alpha = if (side == Panel.Start) 1f else 0f }.background(bg),
        ) { start() }
        if (end != null) {
            Box(
                Modifier.align(Alignment.CenterEnd).width(endW).fillMaxHeight().zIndex(if (side == Panel.End) 1f else 0f)
                    .graphicsLayer { alpha = if (side == Panel.End) 1f else 0f }.background(bg),
            ) { end() }
        }

        val scrim = colors.scrim
        Box(
            Modifier
                .zIndex(2f)
                .fillMaxSize()
                .offset { IntOffset(state.offset.roundToInt(), 0) }
                .graphicsLayer {
                    val f = (abs(state.offset) / size.width.coerceAtLeast(1f) * 4f).coerceIn(0f, 1f)
                    shape = RoundedCornerShape(corner * f)
                    clip = f > 0f
                    shadowElevation = 12.dp.toPx() * f
                }
                .anchoredDraggable(
                    state.drag,
                    Orientation.Horizontal,
                    enabled = gesturesEnabled || open,
                    flingBehavior = AnchoredDraggableDefaults.flingBehavior(state.drag, positionalThreshold = { it * 0.35f }),
                )
                .drawWithContent {
                    drawContent()
                    val f = abs(state.offset) / size.width.coerceAtLeast(1f)
                    if (f > 0f) drawRect(scrim, alpha = (f * 0.45f).coerceAtMost(0.32f))
                },
        ) {
            content()
            // While a panel is open the peeking content only closes it.
            if (open) Box(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "Close panel"; onClick { scope.launch { state.close() }; true } }
                    .pointerInput(Unit) { detectTapGestures { scope.launch { state.close() } } },
            )
        }
    }
}
