package app.vellune.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.ScatterPlot
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vellune.core.LinkIndex
import app.vellune.core.NodeKind
import app.vellune.core.Page
import app.vellune.core.buildGraph
import app.vellune.ui.AppState
import app.vellune.ui.Dest
import app.vellune.ui.shell.VTopBar
import kotlin.math.sqrt
import kotlin.random.Random

/** Brain View: every page and the links between them, laid out by a small force simulation. */
@Composable
fun GraphScreen(pages: Map<String, Page>, index: LinkIndex, nav: AppState, onMenu: (() -> Unit)?) {
    var local by remember { mutableStateOf(false) }
    var showTags by remember { mutableStateOf(false) }
    var showUnresolved by remember { mutableStateOf(false) }
    var showOrphans by remember { mutableStateOf(true) }
    val focus = (nav.stack.dropLast(1).lastOrNull { it is Dest.Doc } as? Dest.Doc)?.id
    val graph = remember(pages, index, local, showTags, showUnresolved, showOrphans) {
        buildGraph(pages.values, index, showUnresolved, showTags, showOrphans, if (local) focus else null, 2)
    }
    val (nodes, edges) = graph
    val ids = remember(nodes) { nodes.map { it.id } }
    val idx = remember(ids) { ids.withIndex().associate { it.value to it.index } }
    val pos = remember(ids) {
        val r = Random(42)
        FloatArray(ids.size * 2) { (r.nextFloat() - 0.5f) * 600f }
    }
    var frame by remember { mutableIntStateOf(0) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme

    // Force layout: repulsion between all nodes, springs on edges, gentle pull to the center; cools down.
    LaunchedEffect(ids, edges) {
        val n = ids.size
        val vel = FloatArray(n * 2)
        val e = edges.mapNotNull { ed -> idx[ed.source]?.let { a -> idx[ed.target]?.let { b -> a to b } } }
        var temp = 1f
        while (temp > 0.02f) {
            withFrameNanos { }
            repeat(2) {
                for (i in 0 until n) for (j in i + 1 until n) {
                    val dx = pos[i * 2] - pos[j * 2]
                    val dy = pos[i * 2 + 1] - pos[j * 2 + 1]
                    val d2 = (dx * dx + dy * dy).coerceAtLeast(25f)
                    val f = 2400f / d2
                    val d = sqrt(d2)
                    vel[i * 2] += dx / d * f; vel[i * 2 + 1] += dy / d * f
                    vel[j * 2] -= dx / d * f; vel[j * 2 + 1] -= dy / d * f
                }
                for ((a, b) in e) {
                    val dx = pos[b * 2] - pos[a * 2]
                    val dy = pos[b * 2 + 1] - pos[a * 2 + 1]
                    val d = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                    val f = (d - 90f) * 0.02f
                    vel[a * 2] += dx / d * f; vel[a * 2 + 1] += dy / d * f
                    vel[b * 2] -= dx / d * f; vel[b * 2 + 1] -= dy / d * f
                }
                for (i in 0 until n) {
                    vel[i * 2] -= pos[i * 2] * 0.004f; vel[i * 2 + 1] -= pos[i * 2 + 1] * 0.004f
                    pos[i * 2] += (vel[i * 2] * temp).coerceIn(-30f, 30f)
                    pos[i * 2 + 1] += (vel[i * 2 + 1] * temp).coerceIn(-30f, 30f)
                    vel[i * 2] *= 0.6f; vel[i * 2 + 1] *= 0.6f
                }
            }
            temp *= 0.985f
            frame++
        }
    }

    val pageCount = nodes.count { it.kind == NodeKind.Page || it.kind == NodeKind.Journal }
    Scaffold(
        containerColor = colors.surfaceContainerLowest,
        topBar = { VTopBar(Icons.Outlined.Hub, "Brain view", onMenu, subtitle = "$pageCount pages · ${edges.size} links") },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.2f, 5f)
                            offset += pan
                        }
                    }
                    .pointerInput(ids) {
                        detectTapGestures { tap ->
                            val cx = size.width / 2f + offset.x
                            val cy = size.height / 2f + offset.y
                            val wx = (tap.x - cx) / scale
                            val wy = (tap.y - cy) / scale
                            val hit = ids.indices.minByOrNull { i -> (pos[i * 2] - wx).let { it * it } + (pos[i * 2 + 1] - wy).let { it * it } }
                            if (hit != null) {
                                val d = (pos[hit * 2] - wx).let { it * it } + (pos[hit * 2 + 1] - wy).let { it * it }
                                val id = ids[hit]
                                if (d < 30f * 30f / (scale * scale) + 400f) when {
                                    id.startsWith("tag:") -> nav.navigate(Dest.Database(id.removePrefix("tag:")))
                                    id.startsWith("unresolved:") -> Unit
                                    else -> nav.open(id)
                                }
                            }
                        }
                    },
            ) {
                frame // redraw as the layout settles
                val cx = size.width / 2f + offset.x
                val cy = size.height / 2f + offset.y
                // A soft moonlit glow behind the cluster.
                drawCircle(
                    Brush.radialGradient(listOf(colors.primary.copy(alpha = 0.10f), Color.Transparent), center = Offset(cx, cy), radius = 420f * scale + 200f),
                    radius = 420f * scale + 200f, center = Offset(cx, cy),
                )
                withTransform({ translate(cx, cy); scale(scale, scale, Offset.Zero) }) {
                    for (ed in edges) {
                        val a = idx[ed.source] ?: continue
                        val b = idx[ed.target] ?: continue
                        val lit = focus != null && (ed.source == focus || ed.target == focus)
                        drawLine(
                            if (lit) colors.primary.copy(alpha = 0.8f) else colors.outlineVariant.copy(alpha = 0.7f),
                            Offset(pos[a * 2], pos[a * 2 + 1]), Offset(pos[b * 2], pos[b * 2 + 1]),
                            strokeWidth = (if (lit) 2.5f else 1.5f) / scale,
                        )
                    }
                    nodes.forEachIndexed { i, node ->
                        val c = when (node.kind) {
                            NodeKind.Page -> if (node.id == focus) colors.tertiary else colors.primary
                            NodeKind.Journal -> colors.secondary
                            NodeKind.Unresolved -> colors.outline
                            NodeKind.Tag -> colors.tertiary
                        }
                        val r = 5f + sqrt(node.degree.toFloat()) * 3f
                        val p = Offset(pos[i * 2], pos[i * 2 + 1])
                        drawCircle(c.copy(alpha = if (node.id == focus) 0.35f else 0.18f), r + if (node.id == focus) 10f else 5f, p)
                        drawCircle(c, r, p)
                        if (scale > 0.5f || node.degree > 2) {
                            val layout = measurer.measure(node.label.take(28), TextStyle(fontSize = 11.sp, color = if (node.id == focus || node.degree > 2) colors.onSurface else colors.onSurfaceVariant))
                            drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y + r + 3f))
                        }
                    }
                }
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                item { Filter(local, if (focus == null) "Local (open a page first)" else "Local", Icons.Outlined.MyLocation, enabled = focus != null) { local = !local } }
                item { Filter(showTags, "Tags", Icons.Outlined.Tag) { showTags = !showTags } }
                item { Filter(showUnresolved, "Unresolved", Icons.Outlined.LinkOff) { showUnresolved = !showUnresolved } }
                item { Filter(showOrphans, "Orphans", Icons.Outlined.ScatterPlot) { showOrphans = !showOrphans } }
            }
            // Legend, bottom left; zoom, bottom right.
            Surface(shape = CircleShape, color = colors.surfaceContainerHigh, modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(16.dp)) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Legend(colors.primary, "Page")
                    Legend(colors.secondary, "Journal")
                    if (showTags) Legend(colors.tertiary, "Tag")
                    if (showUnresolved) Legend(colors.outline, "Missing")
                }
            }
            Surface(shape = CircleShape, color = colors.surfaceContainerHigh, modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { scale = (scale * 0.8f).coerceIn(0.2f, 5f) }) { Icon(Icons.Outlined.Remove, "Zoom out") }
                    Text("${(scale * 100).toInt()}%", style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(CircleShape).clickable(onClickLabel = "Reset view") { scale = 1f; offset = Offset.Zero }.padding(8.dp))
                    IconButton(onClick = { scale = (scale * 1.25f).coerceIn(0.2f, 5f) }) { Icon(Icons.Outlined.Add, "Zoom in") }
                }
            }
        }
    }
}

@Composable
private fun Filter(on: Boolean, label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) = FilterChip(
    on, onClick, { Text(label) }, enabled = enabled, shape = CircleShape,
    leadingIcon = { Icon(icon, null, Modifier.size(FilterChipDefaults.IconSize)) },
    colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
)

@Composable
private fun Legend(color: Color, label: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(8.dp).background(color, CircleShape))
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
}
