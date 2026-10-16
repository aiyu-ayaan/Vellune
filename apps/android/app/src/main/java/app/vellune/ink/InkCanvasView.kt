package app.vellune.ink

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockTextureBitmapStore
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableParallelogram
import androidx.ink.geometry.ImmutableSegment
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.Intersection.intersects
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.createClosedShape
import androidx.input.motionprediction.MotionEventPredictor
import app.vellune.core.Stroke
import app.vellune.core.newId
import kotlin.math.hypot
import androidx.ink.strokes.Stroke as InkStroke

/** What a pen-down does on the canvas. */
sealed interface InkMode {
    /** Move canvas items; empty-space drags pan. */
    data object Select : InkMode
    data class Draw(val tool: PenTool) : InkMode
    data object Eraser : InkMode
    data object Lasso : InkMode
    /** Tap to place a sticky note. */
    data object Sticky : InkMode
}

/** A shared stroke plus its renderable ink twin and world bounds. */
class InkEntry(val data: Stroke, val ink: InkStroke) {
    val bounds = RectF().also { r -> ink.shape.computeBoundingBox()?.let { r.set(it.xMin, it.yMin, it.xMax, it.yMax) } }
}

/**
 * Native inking surface over a page's edgeless canvas. Committed strokes are drawn here through
 * androidx.ink; live strokes on the child [InProgressStrokesView] (front-buffered, motion-predicted),
 * so the pen path never touches Compose, storage or the network. The view is transparent: Compose
 * draws the paper and canvas items underneath, following [Listener.onTransform].
 *
 * Input policy: a stylus always draws; its eraser end or a held side button erases. Once a stylus
 * has been seen (or with finger drawing off) fingers only pan and pinch-zoom, which is the palm
 * rejection. Before that, one finger draws and a second finger turns it into a pan/zoom.
 */
@SuppressLint("ViewConstructor")
class InkCanvasView(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onEdit(added: List<Stroke>, removedIds: List<String>)
        fun onHistory(canUndo: Boolean, canRedo: Boolean)
        fun onSelection(count: Int)
        fun onInking(active: Boolean)
        /** World -> screen: screen = world * scale + (tx, ty), in pixels. */
        fun onTransform(scale: Float, tx: Float, ty: Float)
        fun onStylusDetected()
        fun onItemDrag(id: String, dx: Float, dy: Float, done: Boolean)
        fun onItemTap(id: String)
        fun onPlaceSticky(x: Float, y: Float)
        fun onTapEmpty()
    }

    var listener: Listener? = null
    var mode: InkMode = InkMode.Draw(PenTool.Pen)
        set(value) {
            if (field == value) return
            field = value
            if (value != InkMode.Lasso) clearSelection()
            invalidate()
        }
    var color: Int = 0xFF1C1B1F.toInt()
    val sizes = PenTool.entries.associateWith { Brushes.defaultSize(it) }.toMutableMap()
    var fingerDraws = true

    /** World rects of canvas items, measured by Compose; topmost last. */
    var items: List<Pair<String, RectF>> = emptyList()

    private val density = resources.displayMetrics.density
    private val textures = StockTextureBitmapStore(resources).apply { preloadStockBrushesTextures(Brushes.pencil) }
    private val renderer = CanvasStrokeRenderer.create(textures)
    private val live = InProgressStrokesView(context).apply { textureBitmapStore = textures }
    private val predictor = MotionEventPredictor.newInstance(this)

    private val entries = LinkedHashMap<String, InkEntry>()
    private var dark = false

    // World units are CSS px on the web and dp here, so a page looks the same size on both at 100%.
    private val view = Matrix().apply { setScale(density, density); postTranslate(24 * density, 24 * density) }
    private val inverse = Matrix().also { view.invert(it) }
    private val scratch = FloatArray(9)
    private val visible = RectF()

    private class Edit(val added: List<Stroke>, val removed: List<Stroke>)
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()

    private enum class Action { None, Ink, Erase, Lasso, Move, Item }
    private var action = Action.None
    private var penPointer = -1
    private var liveId: InProgressStrokeId? = null
    private val liveTool = HashMap<InProgressStrokeId, Pair<PenTool, Int>>()
    private val erased = ArrayList<Stroke>()
    private val lassoPath = Path()
    private val lassoPoints = ArrayList<Float>()
    private val last = FloatArray(2)
    private val downAt = FloatArray(2)
    private val downScreen = FloatArray(2)
    private var dragItem: String? = null
    private var dragged = false

    private val gesturePointers = ArrayList<Int>()
    private var prevSpan = 0f
    private var prevCx = 0f
    private var prevCy = 0f
    private var panned = false

    private val selection = LinkedHashSet<String>()
    private val selBounds = RectF()
    private var dragDx = 0f
    private var dragDy = 0f

    private var cursorX = Float.NaN
    private var cursorY = Float.NaN
    private var cursorErases = false

    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val accentFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density }

    init {
        setWillNotDraw(false)
        isFocusable = true
        contentDescription = "Canvas"
        addView(live, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        live.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, InkStroke>) = commitLive(strokes)
        })
        live.eagerInit()
    }

    fun setColors(accent: Int, halo: Int, isDark: Boolean) {
        accentPaint.color = accent
        accentFill.color = (accent and 0x00FFFFFF) or (0x1F shl 24)
        ringPaint.color = accent
        ringPaint.setShadowLayer(2f * density, 0f, 0f, halo)
        if (isDark != dark) {
            dark = isDark
            val rebuilt = entries.values.map { InkEntry(it.data, Brushes.build(it.data, dark)) }
            entries.clear()
            rebuilt.forEach { entries[it.data.id] = it }
        }
        invalidate()
    }

    fun isDark() = dark

    /** Built strokes by id, so a remote edit only rebuilds what changed. */
    fun entry(id: String) = entries[id]

    /**
     * Brings the page's strokes in line with the shared document (initial load or a remote edit).
     * The caller builds new entries off the main thread with [Brushes.build].
     */
    fun setEntries(list: List<InkEntry>) {
        entries.clear()
        for (e in list) entries[e.data.id] = e
        if (selection.isNotEmpty()) {
            selection.retainAll(entries.keys)
            updateSelectionBounds()
        }
        invalidate()
    }

    fun undo() = undoStack.removeLastOrNull()?.let { e ->
        apply(added = e.removed, removed = e.added)
        redoStack.addLast(e)
        publishHistory()
    }

    fun redo() = redoStack.removeLastOrNull()?.let { e ->
        apply(added = e.added, removed = e.removed)
        undoStack.addLast(e)
        publishHistory()
    }

    fun deleteSelection() {
        val removed = selection.mapNotNull { entries[it]?.data }
        clearSelection()
        if (removed.isEmpty()) return
        apply(emptyList(), removed)
        record(Edit(emptyList(), removed))
    }

    fun duplicateSelection() {
        val copies = selection.mapNotNull { entries[it]?.data }.map { it.translated(24f, 24f, newId()) }
        if (copies.isEmpty()) return
        apply(copies, emptyList())
        record(Edit(copies, emptyList()))
        selection.clear()
        copies.forEach { selection += it.id }
        updateSelectionBounds()
    }

    /** Recolors the lassoed strokes. */
    fun recolorSelection(c: Int) {
        val old = selection.mapNotNull { entries[it]?.data }
        if (old.isEmpty()) return
        val next = old.map { it.copy(color = Brushes.hex(c)) }
        apply(next, old)
        record(Edit(next, old))
    }

    fun clearSelection() {
        if (selection.isEmpty()) return
        selection.clear()
        listener?.onSelection(0)
        invalidate()
    }

    /** Animates to show the world rect [target], or the page origin at 100%. */
    fun fitTo(target: RectF?) {
        val end = Matrix()
        if (target == null || target.isEmpty || width == 0) {
            end.setScale(density, density)
            end.postTranslate(24 * density, 24 * density)
        } else {
            val pad = 32 * density
            val s = minOf((width - 2 * pad) / target.width(), (height - 2 * pad) / target.height())
                .coerceIn(MIN_SCALE * density, density * 2)
            end.setScale(s, s)
            end.postTranslate((width - target.width() * s) / 2 - target.left * s, (height - target.height() * s) / 2 - target.top * s)
        }
        animateTo(end)
    }

    fun zoomBy(factor: Float) {
        val end = Matrix(view)
        val f = factor.coerceIn(MIN_SCALE * density / scale(), MAX_SCALE * density / scale())
        end.postScale(f, f, width / 2f, height / 2f)
        animateTo(end)
    }

    /** World bounds of all strokes. */
    fun inkBounds(): RectF? = entries.values.fold(null as RectF?) { acc, e -> (acc ?: RectF(e.bounds)).apply { union(e.bounds) } }

    private fun animateTo(end: Matrix) {
        val a = FloatArray(9).also(view::getValues)
        val b = FloatArray(9).also(end::getValues)
        val out = FloatArray(9)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f) // M3 emphasized
            addUpdateListener {
                val t = it.animatedValue as Float
                for (i in 0..8) out[i] = a[i] + (b[i] - a[i]) * t
                view.setValues(out)
                onViewChanged()
            }
            start()
        }
    }

    // ---- touch ----

    override fun onInterceptTouchEvent(ev: MotionEvent) = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        predictor.record(event)
        val index = event.actionIndex
        val id = event.getPointerId(index)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> pointerDown(event, index, id)
            MotionEvent.ACTION_MOVE -> move(event)
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val palm = Build.VERSION.SDK_INT >= 33 && event.flags and MotionEvent.FLAG_CANCELED != 0
                if (id == penPointer) if (palm) cancelPen() else penUp(event, index)
                if (event.actionMasked == MotionEvent.ACTION_UP) endAll() else removeGesturePointer(id)
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelPen()
                gesturePointers.clear()
                panned = false
            }
        }
        return true
    }

    private fun isPen(type: Int) = type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER

    private fun itemAt(x: Float, y: Float): String? = items.lastOrNull { it.second.contains(x, y) }?.first

    private fun pointerDown(event: MotionEvent, index: Int, id: Int) {
        val type = event.getToolType(index)
        val first = event.actionMasked == MotionEvent.ACTION_DOWN
        if (first) {
            panned = false
            downScreen[0] = event.getX(index); downScreen[1] = event.getY(index)
        }
        if (isPen(type)) {
            if (fingerDraws) listener?.onStylusDetected()
            fingerDraws = false
            if (penPointer != -1) cancelPen() // a palm or finger got there first
            gesturePointers.remove(id)
            if (startPen(event, index, id)) return
        } else if (penPointer == -1 && first && gesturePointers.isEmpty()) {
            val mouse = type == MotionEvent.TOOL_TYPE_MOUSE
            if ((mouse || fingerDraws || mode != InkMode.Select && mode !is InkMode.Draw && mode != InkMode.Eraser) && startPen(event, index, id)) return
            if (mode == InkMode.Select && startPen(event, index, id)) return
        } else if (penPointer != -1 && !isPen(event.getToolType(event.findPointerIndex(penPointer).coerceAtLeast(0)))) {
            // A second finger while a finger is acting: that was the start of a pinch.
            val firstId = penPointer
            cancelPen()
            gesturePointers += firstId
        }
        gesturePointers += id
        resetGestureAnchor(event)
    }

    /** Starts a pen action; false when this down should pan instead (empty space in select/sticky mode). */
    private fun startPen(event: MotionEvent, index: Int, id: Int): Boolean {
        val x = event.getX(index)
        val y = event.getY(index)
        toWorld(x, y, last)
        val erasing = event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER ||
            event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0
        val next = when {
            erasing || mode == InkMode.Eraser -> Action.Erase
            mode == InkMode.Lasso && selection.isNotEmpty() && selectionHit(last[0], last[1]) -> Action.Move
            mode == InkMode.Lasso -> Action.Lasso
            mode == InkMode.Select || mode == InkMode.Sticky -> if (itemAt(last[0], last[1]) != null) Action.Item else return false
            else -> Action.Ink
        }
        penPointer = id
        action = next
        downAt[0] = last[0]; downAt[1] = last[1]
        downScreen[0] = x; downScreen[1] = y
        dragged = false
        listener?.onInking(action == Action.Ink || action == Action.Erase || action == Action.Lasso)
        when (action) {
            Action.Ink -> {
                val tool = (mode as? InkMode.Draw)?.tool ?: PenTool.Pen
                val brush = Brushes.brush(tool, color, sizes.getValue(tool), dark)
                liveId = live.startStroke(event, id, brush, inverse).also { liveTool[it] = tool to color }
            }
            Action.Erase -> {
                erased.clear()
                cursorErases = true
                cursorX = x; cursorY = y
                eraseAlong(last[0], last[1], last[0], last[1])
            }
            Action.Lasso -> {
                clearSelection()
                lassoPoints.clear()
                lassoPath.reset()
                lassoPath.moveTo(last[0], last[1])
                lassoPoints += last[0]; lassoPoints += last[1]
            }
            Action.Move -> { dragDx = 0f; dragDy = 0f }
            Action.Item -> dragItem = itemAt(last[0], last[1])
            Action.None -> Unit
        }
        invalidate()
        return true
    }

    private val world = FloatArray(2)

    private inline fun forSamples(event: MotionEvent, index: Int, f: (Float, Float) -> Unit) {
        // Historical samples too, so a fast swipe still covers everything it crossed.
        for (h in 0 until event.historySize) f(event.getHistoricalX(index, h), event.getHistoricalY(index, h))
        f(event.getX(index), event.getY(index))
    }

    private fun move(event: MotionEvent) {
        if (penPointer != -1) {
            val index = event.findPointerIndex(penPointer)
            if (index >= 0) when (action) {
                Action.Ink -> liveId?.let { sid ->
                    val predicted = predictor.predict()
                    try {
                        live.addToStroke(event, penPointer, sid, predicted)
                    } finally {
                        predicted?.recycle()
                    }
                }
                Action.Erase -> {
                    forSamples(event, index) { x, y ->
                        toWorld(x, y, world)
                        eraseAlong(last[0], last[1], world[0], world[1])
                        last[0] = world[0]; last[1] = world[1]
                    }
                    cursorX = event.getX(index); cursorY = event.getY(index)
                    invalidate()
                }
                Action.Lasso -> {
                    forSamples(event, index) { x, y ->
                        toWorld(x, y, world)
                        lassoPath.lineTo(world[0], world[1])
                        lassoPoints += world[0]; lassoPoints += world[1]
                    }
                    invalidate()
                }
                Action.Move -> {
                    toWorld(event.getX(index), event.getY(index), world)
                    dragDx = world[0] - downAt[0]; dragDy = world[1] - downAt[1]
                    invalidate()
                }
                Action.Item -> {
                    if (!dragged && hypot(event.getX(index) - downScreen[0], event.getY(index) - downScreen[1]) > TOUCH_SLOP * density) dragged = true
                    if (dragged) {
                        toWorld(event.getX(index), event.getY(index), world)
                        dragItem?.let { listener?.onItemDrag(it, world[0] - downAt[0], world[1] - downAt[1], false) }
                    }
                }
                Action.None -> Unit
            }
        }
        if (gesturePointers.isNotEmpty()) panZoom(event)
    }

    private fun penUp(event: MotionEvent, index: Int) {
        when (action) {
            Action.Ink -> liveId?.let { live.finishStroke(event, penPointer, it) }
            Action.Erase -> if (erased.isNotEmpty()) {
                val removed = erased.toList()
                listener?.onEdit(emptyList(), removed.map { it.id })
                record(Edit(emptyList(), removed))
            }
            Action.Lasso -> finishLasso()
            Action.Move -> finishMove()
            Action.Item -> dragItem?.let { item ->
                if (dragged) {
                    toWorld(event.getX(index), event.getY(index), world)
                    listener?.onItemDrag(item, world[0] - downAt[0], world[1] - downAt[1], true)
                } else listener?.onItemTap(item)
            }
            Action.None -> Unit
        }
        penDone()
    }

    private fun cancelPen() {
        if (penPointer == -1) return
        liveId?.let { live.cancelStroke(it, null); liveTool.remove(it) }
        if (action == Action.Erase && erased.isNotEmpty()) {
            // Restore what the cancelled swipe already removed.
            erased.forEach { entries[it.id] = InkEntry(it, Brushes.build(it, dark)) }
        }
        if (action == Action.Move) { dragDx = 0f; dragDy = 0f }
        if (action == Action.Item && dragged) dragItem?.let { listener?.onItemDrag(it, 0f, 0f, false) }
        penDone()
    }

    private fun penDone() {
        penPointer = -1
        liveId = null
        action = Action.None
        dragItem = null
        erased.clear()
        lassoPath.reset()
        cursorX = Float.NaN
        cursorErases = false
        listener?.onInking(false)
        invalidate()
    }

    private fun endAll() {
        // A single tap on empty canvas that never moved: place a sticky, or dismiss.
        if (penPointer == -1 && gesturePointers.size == 1 && !panned) {
            toWorld(downScreen[0], downScreen[1], world)
            if (mode == InkMode.Sticky) listener?.onPlaceSticky(world[0], world[1]) else listener?.onTapEmpty()
        }
        gesturePointers.clear()
        panned = false
        if (penPointer != -1) cancelPen()
    }

    private fun removeGesturePointer(id: Int) {
        gesturePointers.remove(id)
        prevSpan = 0f
    }

    private fun resetGestureAnchor(event: MotionEvent) {
        centroid(event)
        prevSpan = span(event)
    }

    private fun panZoom(event: MotionEvent) {
        val px = prevCx
        val py = prevCy
        if (!centroid(event)) return
        if (!panned && (gesturePointers.size > 1 || hypot(prevCx - downScreen[0], prevCy - downScreen[1]) > TOUCH_SLOP * density)) panned = true
        if (!panned) return
        view.postTranslate(prevCx - px, prevCy - py)
        if (gesturePointers.size >= 2) {
            val span = span(event)
            if (prevSpan > 0f && span > 0f) {
                val current = scale()
                val factor = (span / prevSpan).coerceIn(MIN_SCALE * density / current, MAX_SCALE * density / current)
                view.postScale(factor, factor, prevCx, prevCy)
            }
            prevSpan = span
        }
        onViewChanged()
    }

    private fun centroid(event: MotionEvent): Boolean {
        var sx = 0f
        var sy = 0f
        var n = 0
        for (pid in gesturePointers) {
            val i = event.findPointerIndex(pid)
            if (i < 0) continue
            sx += event.getX(i); sy += event.getY(i); n++
        }
        if (n == 0) return false
        prevCx = sx / n; prevCy = sy / n
        return true
    }

    private fun span(event: MotionEvent): Float {
        if (gesturePointers.size < 2) return 0f
        val a = event.findPointerIndex(gesturePointers[0])
        val b = event.findPointerIndex(gesturePointers[1])
        if (a < 0 || b < 0) return 0f
        return hypot(event.getX(a) - event.getX(b), event.getY(a) - event.getY(b))
    }

    // Stylus hover shows where the tip will land, sized to the brush; mouse wheel pans, Ctrl+wheel zooms.
    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (!isPen(event.getToolType(0))) return super.onHoverEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                cursorX = event.x; cursorY = event.y
                cursorErases = mode == InkMode.Eraser || event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ||
                    event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0
            }
            MotionEvent.ACTION_HOVER_EXIT -> cursorX = Float.NaN
        }
        invalidate()
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_SCROLL) return super.onGenericMotionEvent(event)
        val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        if (event.metaState and KeyEvent.META_CTRL_ON != 0) {
            val factor = (if (v > 0) 1.1f else 1 / 1.1f).coerceIn(MIN_SCALE * density / scale(), MAX_SCALE * density / scale())
            view.postScale(factor, factor, event.x, event.y)
        } else {
            view.postTranslate(-h * 48 * density, v * 48 * density)
        }
        onViewChanged()
        return true
    }

    // ---- editing ----

    private fun commitLive(finished: Map<InProgressStrokeId, InkStroke>) {
        val added = ArrayList<Stroke>(finished.size)
        for ((sid, ink) in finished) {
            val (tool, logical) = liveTool.remove(sid) ?: continue
            if (ink.inputs.size == 0) continue
            val data = Stroke(points = Brushes.points(ink), color = Brushes.hex(logical), width = ink.brush.size.toDouble(), tool = tool.id)
            entries[data.id] = InkEntry(data, ink)
            added += data
        }
        // Same frame: this view takes the strokes over as the live layer drops them, so nothing flickers.
        invalidate()
        live.removeFinishedStrokes(finished.keys)
        if (added.isEmpty()) return
        listener?.onEdit(added, emptyList())
        record(Edit(added, emptyList()))
    }

    private val hitRect = RectF()

    private fun eraseAlong(x0: Float, y0: Float, x1: Float, y1: Float) {
        val radius = ERASER_RADIUS_DP * density / scale()
        val area = ImmutableParallelogram.fromSegmentAndPadding(ImmutableSegment(ImmutableVec(x0, y0), ImmutableVec(x1, y1)), radius)
        hitRect.set(minOf(x0, x1) - radius, minOf(y0, y1) - radius, maxOf(x0, x1) + radius, maxOf(y0, y1) + radius)
        val hit = entries.values.filter { e -> RectF.intersects(e.bounds, hitRect) && area.intersects(e.ink.shape, AffineTransform.IDENTITY) }
        if (hit.isEmpty()) return
        for (e in hit) {
            entries.remove(e.data.id)
            erased += e.data
        }
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun finishLasso() {
        if (lassoPoints.size < 6) return
        val batch = MutableStrokeInputBatch()
        var t = 0L
        for (i in lassoPoints.indices step 2) batch.add(InputToolType.STYLUS, lassoPoints[i], lassoPoints[i + 1], t++)
        val shape = runCatching { batch.createClosedShape() }.getOrNull() ?: return
        val bounds = RectF().also { r -> shape.computeBoundingBox()?.let { r.set(it.xMin, it.yMin, it.xMax, it.yMax) } }
        for (e in entries.values) {
            if (!RectF.intersects(bounds, e.bounds)) continue
            if (e.ink.shape.computeCoverageIsGreaterThan(shape, 0.4f)) selection += e.data.id
        }
        updateSelectionBounds()
        if (selection.isNotEmpty()) performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    private fun finishMove() {
        if (dragDx == 0f && dragDy == 0f) return
        val old = selection.mapNotNull { entries[it]?.data }
        val moved = old.map { it.translated(dragDx, dragDy, it.id) }
        dragDx = 0f; dragDy = 0f
        apply(moved, old)
        record(Edit(moved, old))
    }

    /** Applies an edit and reports it for saving. Replacing a stroke keeps its id. */
    private fun apply(added: List<Stroke>, removed: List<Stroke>) {
        removed.forEach { entries.remove(it.id) }
        added.forEach { entries[it.id] = InkEntry(it, Brushes.build(it, dark)) }
        val addedIds = added.mapTo(HashSet()) { it.id }
        listener?.onEdit(added, removed.map { it.id }.filterNot { it in addedIds })
        if (selection.isNotEmpty()) {
            selection.retainAll(entries.keys)
            updateSelectionBounds()
        }
        invalidate()
    }

    private fun record(edit: Edit) {
        undoStack.addLast(edit)
        if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()
        publishHistory()
    }

    private fun publishHistory() = listener?.onHistory(undoStack.isNotEmpty(), redoStack.isNotEmpty())

    private fun updateSelectionBounds() {
        selBounds.setEmpty()
        selection.forEach { id -> entries[id]?.let { selBounds.union(it.bounds) } }
        listener?.onSelection(selection.size)
        invalidate()
    }

    private fun selectionHit(x: Float, y: Float): Boolean {
        val pad = 16 * density / scale()
        return x in selBounds.left - pad..selBounds.right + pad && y in selBounds.top - pad..selBounds.bottom + pad
    }

    private fun Stroke.translated(dx: Float, dy: Float, newId: String): Stroke {
        val p = points.copyOf()
        for (i in p.indices step 3) {
            p[i] += dx.toDouble()
            if (i + 1 < p.size) p[i + 1] += dy.toDouble()
        }
        return copy(id = newId, points = p)
    }

    // ---- view transform ----

    private fun scale(): Float {
        view.getValues(scratch)
        return hypot(scratch[Matrix.MSCALE_X], scratch[Matrix.MSKEW_Y])
    }

    private fun toWorld(x: Float, y: Float, out: FloatArray) {
        out[0] = x; out[1] = y
        inverse.mapPoints(out)
    }

    private fun onViewChanged() {
        view.invert(inverse)
        view.getValues(scratch)
        listener?.onTransform(scratch[Matrix.MSCALE_X], scratch[Matrix.MTRANS_X], scratch[Matrix.MTRANS_Y])
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        onViewChanged()
    }

    // ---- drawing ----

    override fun onDraw(canvas: Canvas) {
        visible.set(0f, 0f, width.toFloat(), height.toFloat())
        inverse.mapRect(visible)
        canvas.save()
        canvas.concat(view)
        val moving = action == Action.Move && (dragDx != 0f || dragDy != 0f)
        for (e in entries.values) {
            // Off-screen strokes are skipped; large pages cost only what is visible.
            if (moving && e.data.id in selection) continue
            if (!RectF.intersects(visible, e.bounds)) continue
            renderer.draw(canvas, e.ink, view)
        }
        if (moving) {
            canvas.save()
            canvas.translate(dragDx, dragDy)
            val m = Matrix(view).apply { preTranslate(dragDx, dragDy) }
            selection.forEach { id -> entries[id]?.let { renderer.draw(canvas, it.ink, m) } }
            canvas.restore()
        }
        canvas.restore()
        drawSelectionChrome(canvas)
        drawCursor(canvas)
    }

    private val dash = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    private val screenPath = Path()
    private val screenRect = RectF()

    private fun drawSelectionChrome(canvas: Canvas) {
        if (action == Action.Lasso && !lassoPath.isEmpty) {
            lassoPath.transform(view, screenPath)
            accentPaint.strokeWidth = 2f * density
            accentPaint.pathEffect = dash
            canvas.drawPath(screenPath, accentFill)
            canvas.drawPath(screenPath, accentPaint)
        }
        if (selection.isNotEmpty() && !selBounds.isEmpty) {
            screenRect.set(selBounds)
            screenRect.offset(dragDx, dragDy)
            view.mapRect(screenRect)
            screenRect.inset(-10 * density, -10 * density)
            val r = 16 * density
            accentPaint.strokeWidth = 2f * density
            accentPaint.pathEffect = null
            canvas.drawRoundRect(screenRect, r, r, accentFill)
            canvas.drawRoundRect(screenRect, r, r, accentPaint)
        }
    }

    private fun drawCursor(canvas: Canvas) {
        if (cursorX.isNaN()) return
        val radius = if (cursorErases) ERASER_RADIUS_DP * density else {
            val tool = (mode as? InkMode.Draw)?.tool ?: return
            sizes.getValue(tool) / 2f * scale()
        }
        canvas.drawCircle(cursorX, cursorY, radius.coerceAtLeast(3 * density), ringPaint)
    }

    companion object {
        const val MIN_SCALE = 0.1f
        const val MAX_SCALE = 8f
        private const val ERASER_RADIUS_DP = 12f
        private const val MAX_HISTORY = 200
        private const val TOUCH_SLOP = 6f
    }
}
