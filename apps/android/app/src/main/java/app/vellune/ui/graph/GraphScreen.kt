package app.vellune.ui.graph

import androidx.compose.foundation.Canvas
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

    Scaffold(topBar = { VTopBar({ Text("Brain View") }, onMenu) }) { pad ->
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
                withTransform({ translate(cx, cy); scale(scale, scale, Offset.Zero) }) {
                    for (ed in edges) {
                        val a = idx[ed.source] ?: continue
                        val b = idx[ed.target] ?: continue
                        drawLine(colors.outlineVariant, Offset(pos[a * 2], pos[a * 2 + 1]), Offset(pos[b * 2], pos[b * 2 + 1]), strokeWidth = 1.5f / scale)
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
                        drawCircle(c, r, p)
                        if (scale > 0.5f || node.degree > 2) {
                            val layout = measurer.measure(node.label.take(28), TextStyle(fontSize = 11.sp, color = colors.onSurfaceVariant))
                            drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y + r + 3f))
                        }
                    }
                }
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                item { FilterChip(local, { local = !local }, { Text(if (focus == null) "Local (open a page first)" else "Local") }, enabled = focus != null) }
                item { FilterChip(showTags, { showTags = !showTags }, { Text("Tags") }) }
                item { FilterChip(showUnresolved, { showUnresolved = !showUnresolved }, { Text("Unresolved") }) }
                item { FilterChip(showOrphans, { showOrphans = !showOrphans }, { Text("Orphans") }) }
            }
        }
    }
}
