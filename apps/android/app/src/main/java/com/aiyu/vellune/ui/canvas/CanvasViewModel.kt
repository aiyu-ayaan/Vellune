package com.aiyu.vellune.ui.canvas

import androidx.ink.strokes.Stroke
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiyu.vellune.core.ink.StrokeGeometry
import com.aiyu.vellune.data.BrushKind
import com.aiyu.vellune.data.CanvasDoc
import com.aiyu.vellune.data.CanvasRepository
import com.aiyu.vellune.data.StoredStroke
import com.aiyu.vellune.data.Viewport
import com.aiyu.vellune.ink.InkStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tool { Pen, Marker, Highlighter, Eraser, Hand }

val Tool.brush: BrushKind?
    get() = when (this) {
        Tool.Pen -> BrushKind.Pen
        Tool.Marker -> BrushKind.Marker
        Tool.Highlighter -> BrushKind.Highlighter
        Tool.Eraser, Tool.Hand -> null
    }

/** [color] null = the theme's ink colour, resolved when drawing so it suits light and dark paper. */
data class BrushStyle(val color: Int?, val size: Float)

data class ToolState(
    val tool: Tool = Tool.Pen,
    val styles: Map<BrushKind, BrushStyle> = mapOf(
        BrushKind.Pen to BrushStyle(null, 4f),
        BrushKind.Marker to BrushStyle(null, 10f),
        BrushKind.Highlighter to BrushStyle(0xFFF59E0B.toInt(), 24f),
    ),
) {
    /** Style of the active brush, or of the pen when erasing/panning. */
    val activeStyle: BrushStyle get() = styles.getValue(tool.brush ?: BrushKind.Pen)
}

/** Live input numbers for the debug HUD. */
data class InkStats(val state: String = "Idle", val tool: String = "-", val pressure: Float = 0f, val samplesPerSec: Int = 0)

data class CanvasUi(
    val loaded: Boolean = false,
    val title: String = "",
    val tool: ToolState = ToolState(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val zoomPercent: Int = 100,
    /** Bumped when the viewport is set from outside a gesture (load, reset); the view applies it once. */
    val viewportEpoch: Int = 0,
    val stats: InkStats = InkStats(),
)

/** Owns one canvas: committed strokes, per-stroke undo/redo, tool state and saving (docs/05 "Undo/redo"). */
class CanvasViewModel(private val repo: CanvasRepository, private val canvasId: String) : ViewModel() {

    private sealed interface Op {
        class Add(val stroke: InkStroke) : Op
        /** One eraser drag or a clear: removed strokes with the index each had when it was removed. */
        class Erase(val removed: List<Pair<Int, InkStroke>>) : Op
    }

    private val _strokes = MutableStateFlow<List<InkStroke>>(emptyList())
    val strokes: StateFlow<List<InkStroke>> = _strokes.asStateFlow()

    private val _ui = MutableStateFlow(CanvasUi())
    val ui: StateFlow<CanvasUi> = _ui.asStateFlow()

    var viewport = Viewport()
        private set

    private val undo = ArrayDeque<Op>()
    private val redo = ArrayDeque<Op>()
    private var erasing: MutableList<Pair<Int, InkStroke>>? = null
    private var doc: CanvasDoc? = null
    private var saveJob: Job? = null
    private var dirty = false

    init {
        viewModelScope.launch {
            val loaded = repo.get(canvasId) ?: return@launch
            // Building ink geometry is native work; keep it off the main thread.
            val ink = withContext(Dispatchers.Default) { loaded.strokes.map(InkStroke::from) }
            doc = loaded
            viewport = loaded.viewport
            _strokes.value = ink
            _ui.update { it.copy(loaded = true, title = loaded.title, zoomPercent = percent(viewport), viewportEpoch = it.viewportEpoch + 1) }
        }
    }

    fun addStroke(stored: StoredStroke, stroke: Stroke) {
        val ink = InkStroke(stored, stroke)
        _strokes.update { it + ink }
        push(Op.Add(ink))
    }

    fun beginErase() {
        erasing = mutableListOf()
    }

    /** Removes every stroke within [radius] world units of (x, y). */
    fun eraseAt(x: Float, y: Float, radius: Float) {
        val session = erasing ?: return
        val current = _strokes.value
        val hits = current.withIndex().filter { (_, s) ->
            val b = s.bounds
            x >= b[0] - radius && x <= b[2] + radius && y >= b[1] - radius && y <= b[3] + radius &&
                StrokeGeometry.distanceToPolyline(s.stored.xy, x, y) <= radius + s.stored.size / 2
        }
        if (hits.isEmpty()) return
        val list = current.toMutableList()
        // Each earlier removal shifts later indices down by one; record the index at removal time.
        for ((offset, hit) in hits.withIndex()) {
            val index = hit.index - offset
            session += index to hit.value
            list.removeAt(index)
        }
        _strokes.value = list
    }

    fun endErase() {
        val session = erasing ?: return
        erasing = null
        if (session.isNotEmpty()) push(Op.Erase(session))
    }

    fun clear() {
        val all = _strokes.value
        if (all.isEmpty()) return
        _strokes.value = emptyList()
        // Removing from the front each time keeps index 0 for every entry.
        push(Op.Erase(all.map { 0 to it }))
    }

    fun undo() {
        val op = undo.removeLastOrNull() ?: return
        when (op) {
            is Op.Add -> _strokes.update { it - op.stroke }
            is Op.Erase -> _strokes.update { list ->
                list.toMutableList().apply { op.removed.asReversed().forEach { (i, s) -> add(i.coerceAtMost(size), s) } }
            }
        }
        redo.addLast(op)
        changed()
    }

    fun redo() {
        val op = redo.removeLastOrNull() ?: return
        when (op) {
            is Op.Add -> _strokes.update { it + op.stroke }
            is Op.Erase -> _strokes.update { list ->
                list.toMutableList().apply { op.removed.forEach { (i, _) -> if (i < size) removeAt(i) } }
            }
        }
        undo.addLast(op)
        changed()
    }

    fun setTool(tool: Tool) = _ui.update { it.copy(tool = it.tool.copy(tool = tool)) }

    fun setStyle(kind: BrushKind, style: BrushStyle) =
        _ui.update { it.copy(tool = it.tool.copy(styles = it.tool.styles + (kind to style))) }

    fun rename(title: String) {
        val t = title.trim().ifEmpty { return }
        _ui.update { it.copy(title = t) }
        dirty = true
        scheduleSave()
    }

    /** Called from the view during gestures; the view already shows it, so nothing flows back. */
    fun onViewportChanged(v: Viewport) {
        viewport = v
        val p = percent(v)
        if (p != _ui.value.zoomPercent) _ui.update { it.copy(zoomPercent = p) }
        dirty = true
        scheduleSave()
    }

    fun resetViewport() {
        viewport = Viewport()
        _ui.update { it.copy(zoomPercent = 100, viewportEpoch = it.viewportEpoch + 1) }
        dirty = true
        scheduleSave()
    }

    fun onStats(stats: InkStats) = _ui.update { it.copy(stats = stats) }

    private fun push(op: Op) {
        undo.addLast(op)
        if (undo.size > MAX_UNDO) undo.removeFirst()
        redo.clear()
        changed()
    }

    private fun changed() {
        _ui.update { it.copy(canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty()) }
        dirty = true
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            saveNow()
        }
    }

    private fun saveNow() {
        val base = doc ?: return
        if (!dirty) return
        dirty = false
        val updated = base.copy(
            title = _ui.value.title,
            strokes = _strokes.value.map { it.stored },
            viewport = viewport,
            updatedAt = System.currentTimeMillis(),
        )
        doc = updated
        repo.save(updated)
    }

    override fun onCleared() {
        // The repository writes on the app scope, so this survives the screen.
        saveNow()
    }

    private fun percent(v: Viewport) = Math.round(v.scale * 100)

    private companion object {
        const val SAVE_DEBOUNCE_MS = 600L
        const val MAX_UNDO = 200
    }
}
