package com.aiyu.vellune.ui.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.InputToolType
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.input.motionprediction.MotionEventPredictor
import com.aiyu.vellune.core.input.ArbiterConfig
import com.aiyu.vellune.core.input.Gesture
import com.aiyu.vellune.core.input.InputEvent
import com.aiyu.vellune.core.input.PointerArbiter
import com.aiyu.vellune.core.input.PointerSample
import com.aiyu.vellune.core.input.ToolKind
import com.aiyu.vellune.data.BrushKind
import com.aiyu.vellune.data.StoredStroke
import com.aiyu.vellune.data.TraceRecorder
import com.aiyu.vellune.data.Viewport
import com.aiyu.vellune.ink.Brushes
import com.aiyu.vellune.ink.InkStroke
import com.aiyu.vellune.ink.MotionEventMapper
import java.util.UUID
import kotlin.math.PI

/** What the canvas view reports back. Implemented by the screen on top of [CanvasViewModel]. */
interface InkCanvasCallbacks {
    fun onStrokeFinished(stored: StoredStroke, stroke: Stroke)
    fun onEraseBegin()
    fun onEraseAt(x: Float, y: Float, radius: Float)
    fun onEraseEnd()
    fun onViewportChanged(viewport: Viewport)
    fun onStats(stats: InkStats)
}

/**
 * Input pipeline of docs/05 on a real view: MotionEvent → [MotionEventMapper] → [PointerArbiter]
 * → tool. Wet ink goes through [InProgressStrokesView] (front-buffered on API 29+); committed
 * strokes are drawn by [DryInkView] underneath, in world coordinates under [matrix].
 */
@SuppressLint("ViewConstructor")
class InkCanvasLayout(context: Context, private val callbacks: InkCanvasCallbacks) : FrameLayout(context),
    InProgressStrokesFinishedListener {

    private val arbiter = PointerArbiter()
    private val dry = DryInkView(context)
    private val wet = InProgressStrokesView(context)
    private val predictor = MotionEventPredictor.newInstance(this)
    private val events = ArrayList<InputEvent>()

    /** World → view. */
    private val matrix = Matrix()
    private val inverse = Matrix()
    private var scale = 1f
    private var appliedEpoch = -1

    var tool = ToolState()
    var themeInk = 0xFF1C1B1F.toInt()
    var predictionEnabled = true
    var statsEnabled = false
    var recorder: TraceRecorder? = null

    private val live = HashMap<Int, LiveStroke>()
    private val finishing = HashMap<InProgressStrokeId, LiveStroke>()
    private var eraserPointer: Int? = null
    private var panPointer: Int? = null
    private var panLast = floatArrayOf(0f, 0f)
    private val point = FloatArray(2)

    private var statsAt = 0L
    private var samplesSinceStats = 0
    private var lastTool = "-"
    private var lastPressure = 0f

    init {
        addView(dry, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(wet, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        wet.addFinishedStrokesListener(this)
        isFocusable = true
    }

    fun setStrokes(strokes: List<InkStroke>) {
        if (dry.strokes === strokes) return
        dry.strokes = strokes
        dry.invalidate()
    }

    private var paper = 0 to 0

    fun setPaper(background: Int, dots: Int) {
        if (paper == background to dots) return
        paper = background to dots
        dry.setBackgroundColor(background)
        dry.dotPaint.color = dots
        dry.invalidate()
    }

    fun configure(config: ArbiterConfig) {
        if (arbiter.config != config) arbiter.config = config
    }

    /** Applies an external viewport (load or reset) once per [epoch]; gestures update it locally. */
    fun applyViewport(viewport: Viewport, epoch: Int) {
        if (epoch == appliedEpoch) return
        appliedEpoch = epoch
        scale = viewport.scale
        matrix.setScale(viewport.scale, viewport.scale)
        matrix.postTranslate(viewport.tx, viewport.ty)
        onMatrixChanged(report = false)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Deliver moves as they arrive instead of once per frame (lower ink latency).
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) requestUnbufferedDispatch(ev)
        predictor.record(ev)
        handle(ev)
        val predicted = if (predictionEnabled && ev.actionMasked == MotionEvent.ACTION_MOVE) predictor.predict() else null
        flushLive(predicted)
        predicted?.recycle()
        return true
    }

    override fun onHoverEvent(ev: MotionEvent): Boolean {
        handle(ev)
        return true
    }

    private fun handle(ev: MotionEvent) {
        events.clear()
        MotionEventMapper.map(ev, events)
        val rec = recorder
        for (e in events) {
            rec?.record(e)
            samplesSinceStats++
            lastTool = e.sample.tool.name
            lastPressure = e.sample.pressure
            for (g in arbiter.onEvent(e)) apply(g)
        }
        reportStats()
    }

    private fun apply(g: Gesture) {
        when (g) {
            is Gesture.StrokeStart -> start(g.sample, g.tool)
            is Gesture.StrokeMove -> move(g.sample)
            is Gesture.StrokeEnd -> end(g.sample)
            is Gesture.StrokeCancel -> cancel(g.pointerId)
            is Gesture.Transform -> transform(g.panX, g.panY, g.scale, g.focusX, g.focusY)
        }
    }

    private fun start(s: PointerSample, kind: ToolKind) {
        when {
            kind == ToolKind.Eraser || tool.tool == Tool.Eraser -> {
                eraserPointer = s.id
                callbacks.onEraseBegin()
                erase(s)
            }
            tool.tool == Tool.Hand -> {
                panPointer = s.id
                panLast = floatArrayOf(s.x, s.y)
            }
            else -> {
                val brushKind = tool.tool.brush ?: BrushKind.Pen
                val style = tool.styles.getValue(brushKind)
                val color = style.color ?: themeInk
                val stroke = LiveStroke(s.tMs, inkTool(kind), brushKind, color, style.size)
                val first = stroke.input(s, worldOf(s)) ?: return
                stroke.id = wet.startStroke(first, Brushes.create(brushKind, color, style.size), matrix)
                live[s.id] = stroke
            }
        }
    }

    private fun move(s: PointerSample) {
        when (s.id) {
            eraserPointer -> erase(s)
            panPointer -> {
                transform(s.x - panLast[0], s.y - panLast[1], 1f, s.x, s.y)
                panLast = floatArrayOf(s.x, s.y)
            }
            else -> live[s.id]?.let { st -> st.input(s, worldOf(s))?.let(st.pending::add) }
        }
    }

    private fun end(s: PointerSample) {
        when (s.id) {
            eraserPointer -> {
                erase(s)
                eraserPointer = null
                callbacks.onEraseEnd()
            }
            panPointer -> panPointer = null
            else -> {
                val st = live.remove(s.id) ?: return
                val id = st.id ?: return
                flush(st, null)
                st.input(s, worldOf(s))?.let { wet.finishStroke(it, id) } ?: wet.finishStroke(st.lastInput(), id)
                finishing[id] = st
            }
        }
    }

    private fun cancel(pointerId: Int) {
        when (pointerId) {
            eraserPointer -> {
                eraserPointer = null
                callbacks.onEraseEnd()
            }
            panPointer -> panPointer = null
            else -> live.remove(pointerId)?.id?.let(wet::cancelStroke)
        }
    }

    private fun erase(s: PointerSample) {
        val w = worldOf(s)
        callbacks.onEraseAt(w[0], w[1], ERASER_RADIUS_DP * resources.displayMetrics.density / scale)
    }

    private fun transform(panX: Float, panY: Float, s: Float, fx: Float, fy: Float) {
        val next = (scale * s).coerceIn(MIN_SCALE, MAX_SCALE)
        val applied = next / scale
        matrix.postTranslate(panX, panY)
        matrix.postScale(applied, applied, fx, fy)
        scale = next
        onMatrixChanged(report = true)
    }

    private fun onMatrixChanged(report: Boolean) {
        matrix.invert(inverse)
        dry.setMatrix(matrix, scale)
        if (report) {
            val v = FloatArray(9).also(matrix::getValues)
            callbacks.onViewportChanged(Viewport(tx = v[Matrix.MTRANS_X], ty = v[Matrix.MTRANS_Y], scale = scale))
        }
    }

    /** Sends the samples batched during one MotionEvent, plus the predicted tail. Prediction is drawn, never stored. */
    private fun flushLive(predicted: MotionEvent?) {
        for ((pointerId, st) in live) {
            val index = predicted?.findPointerIndex(pointerId) ?: -1
            val prediction = if (predicted != null && index >= 0) {
                point[0] = predicted.getX(index)
                point[1] = predicted.getY(index)
                inverse.mapPoints(point)
                st.predicted(point, predicted.eventTime)
            } else null
            flush(st, prediction)
        }
    }

    private fun flush(st: LiveStroke, prediction: MutableStrokeInputBatch?) {
        val id = st.id ?: return
        if (st.pending.isEmpty() && prediction == null) return
        if (prediction != null) wet.addToStroke(st.pending, id, prediction) else wet.addToStroke(st.pending, id)
        st.pending.clear()
    }

    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
        for ((id, stroke) in strokes) {
            val st = finishing.remove(id) ?: continue
            val stored = st.toStored()
            // Draw it dry this frame, before the VM round-trip, so the hand-off never flickers.
            dry.strokes = dry.strokes + InkStroke(stored, stroke)
            callbacks.onStrokeFinished(stored, stroke)
        }
        dry.invalidate()
        dry.postOnAnimation { wet.removeFinishedStrokes(strokes.keys) }
    }

    private fun worldOf(s: PointerSample): FloatArray {
        point[0] = s.x
        point[1] = s.y
        inverse.mapPoints(point)
        return point
    }

    private fun reportStats() {
        if (!statsEnabled) return
        val now = SystemClock.uptimeMillis()
        val elapsed = now - statsAt
        if (elapsed < STATS_INTERVAL_MS) return
        val rate = (samplesSinceStats * 1000L / elapsed.coerceAtLeast(1)).toInt()
        samplesSinceStats = 0
        statsAt = now
        callbacks.onStats(InkStats(arbiter.stateLabel, lastTool, lastPressure, rate))
    }

    private fun inkTool(kind: ToolKind): InputToolType = when (kind) {
        ToolKind.Stylus, ToolKind.Eraser -> InputToolType.STYLUS
        ToolKind.Finger -> InputToolType.TOUCH
        ToolKind.Mouse -> InputToolType.MOUSE
        ToolKind.Unknown -> InputToolType.UNKNOWN
    }

    /** One stroke in progress: inputs sent to the wet layer and the same points kept for storage. */
    private class LiveStroke(
        val startT: Long,
        val toolType: InputToolType,
        val brush: BrushKind,
        val color: Int,
        val size: Float,
    ) {
        var id: InProgressStrokeId? = null
        val pending = MutableStrokeInputBatch()
        private val xy = ArrayList<Float>()
        private val t = ArrayList<Long>()
        private val pressure = ArrayList<Float>()
        private val tilt = ArrayList<Float>()
        private val orientation = ArrayList<Float>()
        private val isStylus = toolType == InputToolType.STYLUS
        private var last: StrokeInput? = null

        /** Builds the next input and records it, or null when it would go back in time or repeat the last point. */
        fun input(s: PointerSample, world: FloatArray): StrokeInput? {
            val dt = s.tMs - startT
            val prev = last
            if (prev != null && (dt < prev.elapsedTimeMillis || (dt == prev.elapsedTimeMillis && world[0] == prev.x && world[1] == prev.y))) return null
            val p = if (isStylus) s.pressure.coerceIn(0f, 1f) else StrokeInput.NO_PRESSURE
            val tl = if (isStylus) s.tilt.coerceIn(0f, (PI / 2).toFloat()) else StrokeInput.NO_TILT
            // MotionEvent orientation is -π..π; ink wants 0..2π.
            val o = if (isStylus) ((s.orientation + 2 * PI) % (2 * PI)).toFloat() else StrokeInput.NO_ORIENTATION
            val input = StrokeInput.create(
                x = world[0], y = world[1], elapsedTimeMillis = dt, toolType = toolType,
                strokeUnitLengthCm = StrokeInput.NO_STROKE_UNIT_LENGTH, pressure = p, tiltRadians = tl, orientationRadians = o,
            )
            xy += world[0]; xy += world[1]; t += dt
            pressure += p; tilt += tl; orientation += o
            last = input
            return input
        }

        fun lastInput(): StrokeInput = checkNotNull(last)

        /** Predicted point at view-mapped [world]; reuses the last real pressure and tilt. */
        fun predicted(world: FloatArray, eventTime: Long): MutableStrokeInputBatch? {
            val prev = last ?: return null
            return MutableStrokeInputBatch().add(
                StrokeInput.create(
                    x = world[0], y = world[1],
                    elapsedTimeMillis = (eventTime - startT).coerceAtLeast(prev.elapsedTimeMillis),
                    toolType = toolType,
                    strokeUnitLengthCm = StrokeInput.NO_STROKE_UNIT_LENGTH,
                    pressure = if (isStylus) prev.pressure else StrokeInput.NO_PRESSURE,
                    tiltRadians = if (isStylus) prev.tiltRadians else StrokeInput.NO_TILT,
                    orientationRadians = if (isStylus) prev.orientationRadians else StrokeInput.NO_ORIENTATION,
                )
            )
        }

        fun toStored() = StoredStroke(
            id = UUID.randomUUID().toString(),
            brush = brush,
            color = color,
            size = size,
            toolType = toolType.value,
            xy = xy.toFloatArray(),
            t = t.toLongArray(),
            pressure = if (isStylus) pressure.toFloatArray() else null,
            tilt = if (isStylus) tilt.toFloatArray() else null,
            orientation = if (isStylus) orientation.toFloatArray() else null,
        )
    }

    private companion object {
        const val MIN_SCALE = 0.1f
        const val MAX_SCALE = 8f
        const val ERASER_RADIUS_DP = 12f
        const val STATS_INTERVAL_MS = 125L
    }
}

/** Committed strokes over a dot-grid paper, drawn with the canvas transform. */
@SuppressLint("ViewConstructor")
class DryInkView(context: Context) : View(context) {
    private val renderer = CanvasStrokeRenderer.create()
    private val matrix = Matrix()
    private val inverse = Matrix()
    private var scale = 1f
    private val visible = RectF()
    private var dots = FloatArray(0)
    var strokes: List<InkStroke> = emptyList()
    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.2f * resources.displayMetrics.density
    }

    fun setMatrix(m: Matrix, scale: Float) {
        matrix.set(m)
        matrix.invert(inverse)
        this.scale = scale
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        visible.set(0f, 0f, width.toFloat(), height.toFloat())
        inverse.mapRect(visible)
        drawDots(canvas)
        // The renderer expects the canvas to already hold the transform; its matrix argument only tunes quality.
        val save = canvas.save()
        canvas.concat(matrix)
        for (s in strokes) {
            val b = s.bounds
            // Cull in world space so 10k-stroke pages only draw what is on screen.
            if (b[2] < visible.left || b[0] > visible.right || b[3] < visible.top || b[1] > visible.bottom) continue
            renderer.draw(canvas, s.stroke, matrix)
        }
        canvas.restoreToCount(save)
    }

    private fun drawDots(canvas: Canvas) {
        var step = DOT_SPACING
        // Keep dots at least ~14dp apart on screen when zoomed out.
        while (step * scale < 14f * resources.displayMetrics.density) step *= 2
        val startX = kotlin.math.floor(visible.left / step) * step
        val startY = kotlin.math.floor(visible.top / step) * step
        val cols = ((visible.right - startX) / step).toInt() + 1
        val rows = ((visible.bottom - startY) / step).toInt() + 1
        val needed = cols * rows * 2
        if (needed > MAX_DOTS * 2) return
        if (dots.size < needed) dots = FloatArray(needed)
        var i = 0
        for (r in 0 until rows) for (c in 0 until cols) {
            dots[i++] = startX + c * step
            dots[i++] = startY + r * step
        }
        matrix.mapPoints(dots, 0, dots, 0, i / 2)
        canvas.drawPoints(dots, 0, i, dotPaint)
    }

    private companion object {
        const val DOT_SPACING = 32f
        const val MAX_DOTS = 20_000
    }
}
