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
import androidx.ink.geometry.PartitionedMesh
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.createClosedShape
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import app.vellune.data.InkStroke
import app.vellune.data.Paper
import app.vellune.data.PenTool
import java.util.UUID
import kotlin.math.hypot

/** What a pen-down does. Erasing and selecting never create strokes. */
sealed interface InkMode {
    data class Draw(val tool: PenTool) : InkMode
    data object Eraser : InkMode
    data object Lasso : InkMode
}

/** A stroke as stored plus its renderable ink twin and page-space bounds. */
class InkEntry(val data: InkStroke, val stroke: Stroke) {
    val bounds = RectF().also { r ->
        stroke.shape.computeBoundingBox()?.let { r.set(it.xMin, it.yMin, it.xMax, it.yMax) }
    }
}

/**
 * Native inking surface: committed strokes are drawn here through the ink renderer, live strokes on the child
 * [InProgressStrokesView] (front-buffered, motion-predicted), so the pen path never touches Compose, storage or
 * the network.
 *
 * Input policy: a stylus always draws; its eraser end or a held side button erases. Once a stylus has been seen,
 * or when [fingerDraws] is off, fingers only pan and pinch-zoom, which is the palm rejection. Before that a single
 * finger draws and a second finger turns the gesture into pan/zoom.
 */
@SuppressLint("ViewConstructor")
class InkCanvasView(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onEdit(added: List<InkStroke>, removedIds: List<String>)
        fun onHistory(canUndo: Boolean, canRedo: Boolean)
        fun onSelection(count: Int)
        fun onInking(active: Boolean)
        fun onZoom(scale: Float)
        fun onStylusDetected()
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

    private val density = resources.displayMetrics.density
    private val textures = StockTextureBitmapStore(resources).apply { preloadStockBrushesTextures(Brushes.pencil) }
    private val renderer = CanvasStrokeRenderer.create(textures)
    private val live = InProgressStrokesView(context).apply { textureBitmapStore = textures }
    private val predictor = MotionEventPredictor.newInstance(this)

    private val entries = LinkedHashMap<String, InkEntry>()
    private var dark = false

    // Page (world) units are dp, so a note looks the same size on every screen at 100%.
    private val view = Matrix().apply { setScale(density, density) }
    private val inverse = Matrix()
    private val scratch = FloatArray(9)
    private val visible = RectF()

    private class Edit(val added: List<InkStroke>, val removed: List<InkStroke>)
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()

    // Active pen gesture.
    private enum class Action { None, Ink, Erase, Lasso, Move }
    private var action = Action.None
    private var penPointer = -1
    private var liveId: InProgressStrokeId? = null
    private val liveColor = HashMap<InProgressStrokeId, Pair<PenTool, Int>>()
    private val erased = ArrayList<InkStroke>()
    private val lassoPath = Path()
    private val lassoPoints = ArrayList<Float>()
    private val last = FloatArray(2)
    private val downAt = FloatArray(2)

    // Pan / zoom.
    private val gesturePointers = ArrayList<Int>()
    private var prevSpan = 0f
    private var prevCx = 0f
    private var prevCy = 0f

    // Selection.
    private val selection = LinkedHashSet<String>()
    private val selBounds = RectF()
    private var dragDx = 0f
    private var dragDy = 0f

    // Pointer feedback (hover ring / eraser ring), screen coords.
    private var cursorX = Float.NaN
    private var cursorY = Float.NaN
    private var cursorErases = false

    private var paper = Paper.Dots
    private val paperPaint = Paint()
    private val patternPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val accentFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density }

    init {
        setWillNotDraw(false)
        isFocusable = true
        contentDescription = "Drawing canvas"
        addView(live, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        live.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) = commitLive(strokes)
        })
        live.eagerInit()
    }

    fun setColors(paperColor: Int, patternColor: Int, accent: Int, onAccent: Int, isDark: Boolean) {
        paperPaint.color = paperColor
        patternPaint.color = patternColor
        accentPaint.color = accent
        accentFill.color = (accent and 0x00FFFFFF) or (0x1F shl 24)
        ringPaint.color = accent
        ringPaint.setShadowLayer(2f * density, 0f, 0f, onAccent)
        if (isDark != dark) {
            dark = isDark
            val rebuilt = entries.values.map { InkEntry(it.data, it.stroke.copy(brushFor(it.data))) }
            entries.clear()
            rebuilt.forEach { entries[it.data.id] = it }
        }
        invalidate()
    }

    fun setPaper(value: Paper) {
        paper = value
        invalidate()
    }

    fun isDark() = dark

    /** Replaces the page content (strokes built off the main thread with [Brushes.build]). */
    fun setEntries(list: List<InkEntry>) {
        entries.clear()
        list.forEach { entries[it.data.id] = it }
        undoStack.clear(); redoStack.clear()
        publishHistory()
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
        val offset = 24f
        val copies = selection.mapNotNull { entries[it]?.data }.map { it.translated(offset, offset, UUID.randomUUID().toString()) }
        if (copies.isEmpty()) return
        apply(copies, emptyList())
        record(Edit(copies, emptyList()))
        selection.clear()
        copies.forEach { selection += it.id }
        updateSelectionBounds()
    }

    fun clearSelection() {
        if (selection.isEmpty()) return
        selection.clear()
        listener?.onSelection(0)
        invalidate()
    }

    /** Animates back to the page origin at 100%. */
    fun recenter() {
        val start = Matrix(view)
        val end = Matrix().apply { setScale(density, density) }
        val a = FloatArray(9).also(start::getValues)
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
                else removeGesturePointer(id)
                if (event.actionMasked == MotionEvent.ACTION_UP) endAll()
            }
            MotionEvent.ACTION_CANCEL -> { cancelPen(); endAll() }
        }
        return true
    }

    private fun isPen(type: Int) = type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER
    private fun stylusSeen(): Boolean = !fingerDraws

    private fun pointerDown(event: MotionEvent, index: Int, id: Int) {
        val type = event.getToolType(index)
        if (isPen(type)) {
            if (fingerDraws) listener?.onStylusDetected()
            fingerDraws = false
            if (penPointer != -1) cancelPen() // a palm or finger got there first
            startPen(event, index, id)
            return
        }
        val fingerMayDraw = type == MotionEvent.TOOL_TYPE_MOUSE || (!stylusSeen() && gesturePointers.isEmpty())
        if (fingerMayDraw && penPointer == -1 && event.actionMasked == MotionEvent.ACTION_DOWN) {
            startPen(event, index, id)
            return
        }
        // A second finger while a finger is inking: that was the start of a pinch, not a stroke.
        if (penPointer != -1 && !isPen(event.getToolType(event.findPointerIndex(penPointer)))) {
            val first = penPointer
            cancelPen()
            gesturePointers += first
        }
        gesturePointers += id
        resetGestureAnchor(event)
    }

    private fun startPen(event: MotionEvent, index: Int, id: Int) {
        penPointer = id
        val x = event.getX(index)
        val y = event.getY(index)
        toWorld(x, y, last)
        downAt[0] = last[0]; downAt[1] = last[1]
        val erasing = event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER ||
            event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0
        action = when {
            erasing || mode == InkMode.Eraser -> Action.Erase
            mode == InkMode.Lasso && selection.isNotEmpty() && selectionHit(last[0], last[1]) -> Action.Move
            mode == InkMode.Lasso -> Action.Lasso
            else -> Action.Ink
        }
        listener?.onInking(true)
        when (action) {
            Action.Ink -> {
                val tool = (mode as InkMode.Draw).tool
                val brush = Brushes.brush(tool, color, sizes.getValue(tool), dark)
                liveId = live.startStroke(event, id, brush, inverse).also { liveColor[it] = tool to color }
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
            Action.None -> Unit
        }
        invalidate()
    }

    private val world = FloatArray(2)

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
                    // Historical samples too, so a fast swipe still erases everything it crossed.
                    for (h in 0..event.historySize) {
                        val x = if (h < event.historySize) event.getHistoricalX(index, h) else event.getX(index)
                        val y = if (h < event.historySize) event.getHistoricalY(index, h) else event.getY(index)
                        toWorld(x, y, world)
                        eraseAlong(last[0], last[1], world[0], world[1])
                        last[0] = world[0]; last[1] = world[1]
                    }
                    cursorX = event.getX(index); cursorY = event.getY(index)
                    invalidate()
                }
                Action.Lasso -> {
                    for (h in 0..event.historySize) {
                        val x = if (h < event.historySize) event.getHistoricalX(index, h) else event.getX(index)
                        val y = if (h < event.historySize) event.getHistoricalY(index, h) else event.getY(index)
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
            Action.None -> Unit
        }
        penDone()
    }

    private fun cancelPen() {
        if (penPointer == -1) return
        liveId?.let { live.cancelStroke(it, null); liveColor.remove(it) }
        if (action == Action.Erase && erased.isNotEmpty()) {
            // Restore what the cancelled swipe already removed.
            erased.forEach { entries[it.id] = InkEntry(it, Brushes.build(it, dark)) }
        }
        if (action == Action.Move) { dragDx = 0f; dragDy = 0f }
        penDone()
    }

    private fun penDone() {
        penPointer = -1
        liveId = null
        action = Action.None
        erased.clear()
        lassoPath.reset()
        cursorX = Float.NaN
        cursorErases = false
        listener?.onInking(false)
        invalidate()
    }

    private fun endAll() {
        gesturePointers.clear()
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
        if (event.metaState and android.view.KeyEvent.META_CTRL_ON != 0) {
            val factor = (if (v > 0) 1.1f else 1 / 1.1f).let { f ->
                f.coerceIn(MIN_SCALE * density / scale(), MAX_SCALE * density / scale())
            }
            view.postScale(factor, factor, event.x, event.y)
        } else {
            view.postTranslate(-h * 48 * density, v * 48 * density)
        }
        onViewChanged()
        return true
    }

    // ---- editing ----

    private fun commitLive(finished: Map<InProgressStrokeId, Stroke>) {
        val added = ArrayList<InkStroke>(finished.size)
        for ((sid, stroke) in finished) {
            val (tool, logicalColor) = liveColor.remove(sid) ?: continue
            if (stroke.inputs.size == 0) continue
            val data = InkStroke(
                UUID.randomUUID().toString(), tool, logicalColor, stroke.brush.size,
                stroke.inputs.getToolType().value, Brushes.points(stroke),
            )
            entries[data.id] = InkEntry(data, stroke)
            added += data
        }
        // Same frame: this view takes the strokes over as the live layer drops them, so nothing flickers.
        invalidate()
        live.removeFinishedStrokes(finished.keys)
        if (added.isEmpty()) return
        listener?.onEdit(added, emptyList())
        record(Edit(added, emptyList()))
    }

    private val segment = FloatArray(4)

    private fun eraseAlong(x0: Float, y0: Float, x1: Float, y1: Float) {
        val radius = ERASER_RADIUS_DP * density / scale()
        val area = ImmutableParallelogram.fromSegmentAndPadding(
            ImmutableSegment(ImmutableVec(x0, y0), ImmutableVec(x1, y1)), radius,
        )
        segment[0] = minOf(x0, x1) - radius; segment[1] = minOf(y0, y1) - radius
        segment[2] = maxOf(x0, x1) + radius; segment[3] = maxOf(y0, y1) + radius
        val hit = entries.values.filter { e ->
            RectF.intersects(e.bounds, RectF(segment[0], segment[1], segment[2], segment[3])) &&
                area.intersects(e.stroke.shape, AffineTransform.IDENTITY)
        }
        if (hit.isEmpty()) return
        for (e in hit) {
            entries.remove(e.data.id)
            erased += e.data
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun finishLasso() {
        if (lassoPoints.size < 6) return
        val batch = MutableStrokeInputBatch()
        var t = 0L
        for (i in lassoPoints.indices step 2) batch.add(InputToolType.STYLUS, lassoPoints[i], lassoPoints[i + 1], t++)
        val shape: PartitionedMesh = runCatching { batch.createClosedShape() }.getOrNull() ?: return
        val bounds = RectF().also { r -> shape.computeBoundingBox()?.let { r.set(it.xMin, it.yMin, it.xMax, it.yMax) } }
        for (e in entries.values) {
            if (!RectF.intersects(bounds, e.bounds)) continue
            if (e.stroke.shape.computeCoverageIsGreaterThan(shape, 0.4f)) selection += e.data.id
        }
        updateSelectionBounds()
        if (selection.isNotEmpty()) performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
    }

    private fun finishMove() {
        if (dragDx == 0f && dragDy == 0f) return
        val old = selection.mapNotNull { entries[it]?.data }
        val moved = old.map { it.translated(dragDx, dragDy, it.id) }
        dragDx = 0f; dragDy = 0f
        apply(moved, old)
        record(Edit(moved, old))
        updateSelectionBounds()
    }

    /** Applies an edit to the page and reports it for saving. Replacing a stroke = remove + add, same id. */
    private fun apply(added: List<InkStroke>, removed: List<InkStroke>) {
        removed.forEach { entries.remove(it.id) }
        added.forEach { entries[it.id] = InkEntry(it, Brushes.build(it, dark)) }
        listener?.onEdit(added, removed.map { it.id })
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

    private fun brushFor(d: InkStroke) = Brushes.brush(d.tool, d.color, d.size, dark)

    private fun InkStroke.translated(dx: Float, dy: Float, newId: String) =
        InkStroke(newId, tool, color, size, toolType, points.map { it.copy(x = it.x + dx, y = it.y + dy) })

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
        listener?.onZoom(scale() / density)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        view.invert(inverse)
    }

    // ---- drawing ----

    override fun onDraw(canvas: Canvas) {
        canvas.drawPaint(paperPaint)
        visible.set(0f, 0f, width.toFloat(), height.toFloat())
        inverse.mapRect(visible)
        drawPattern(canvas)

        canvas.save()
        canvas.concat(view)
        val moving = action == Action.Move && (dragDx != 0f || dragDy != 0f)
        for (e in entries.values) {
            // Off-screen strokes are skipped; large pages cost only what is visible.
            if (moving && e.data.id in selection) continue
            if (!RectF.intersects(visible, e.bounds)) continue
            renderer.draw(canvas, e.stroke, view)
        }
        if (moving) {
            canvas.save()
            canvas.translate(dragDx, dragDy)
            val m = Matrix(view).apply { preTranslate(dragDx, dragDy) }
            selection.forEach { id -> entries[id]?.let { renderer.draw(canvas, it.stroke, m) } }
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

    private fun drawPattern(canvas: Canvas) {
        if (paper == Paper.Blank) return
        val step = GRID_DP
        val s = scale()
        if (step * s < 10 * density) return // too dense to read at this zoom
        val x0 = kotlin.math.floor(visible.left / step) * step
        val y0 = kotlin.math.floor(visible.top / step) * step
        val pts = floatArrayOf(0f, 0f, 0f, 0f)
        patternPaint.strokeWidth = if (paper == Paper.Dots) 2.2f * density else 1f * density
        patternPaint.strokeCap = Paint.Cap.ROUND
        when (paper) {
            Paper.Dots -> {
                var y = y0
                while (y <= visible.bottom) {
                    var x = x0
                    while (x <= visible.right) {
                        pts[0] = x; pts[1] = y
                        view.mapPoints(pts, 0, pts, 0, 1)
                        canvas.drawPoint(pts[0], pts[1], patternPaint)
                        x += step
                    }
                    y += step
                }
            }
            Paper.Lines, Paper.Grid -> {
                var y = y0
                while (y <= visible.bottom) {
                    pts[0] = visible.left; pts[1] = y; pts[2] = visible.right; pts[3] = y
                    view.mapPoints(pts)
                    canvas.drawLine(pts[0], pts[1], pts[2], pts[3], patternPaint)
                    y += step
                }
                if (paper == Paper.Grid) {
                    var x = x0
                    while (x <= visible.right) {
                        pts[0] = x; pts[1] = visible.top; pts[2] = x; pts[3] = visible.bottom
                        view.mapPoints(pts)
                        canvas.drawLine(pts[0], pts[1], pts[2], pts[3], patternPaint)
                        x += step
                    }
                }
            }
            Paper.Blank -> Unit
        }
    }

    companion object {
        private const val MIN_SCALE = 0.25f
        private const val MAX_SCALE = 8f
        private const val ERASER_RADIUS_DP = 12f
        private const val GRID_DP = 32f
        private const val MAX_HISTORY = 200
    }
}
