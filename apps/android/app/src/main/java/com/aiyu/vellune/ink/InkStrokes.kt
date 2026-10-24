package com.aiyu.vellune.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import com.aiyu.vellune.core.ink.StrokeGeometry
import com.aiyu.vellune.data.BrushKind
import com.aiyu.vellune.data.StoredStroke

/** Brush families are native objects; build each once. */
object Brushes {
    private val pen: BrushFamily by lazy { StockBrushes.pressurePen() }
    private val marker: BrushFamily by lazy { StockBrushes.marker() }
    private val highlighter: BrushFamily by lazy { StockBrushes.highlighter() }

    /** Highlighters always draw translucent, whatever swatch was picked. */
    private const val HIGHLIGHTER_ALPHA = 0x66000000

    fun create(kind: BrushKind, color: Int, size: Float): Brush = when (kind) {
        BrushKind.Pen -> Brush.createWithColorIntArgb(pen, color, size, EPSILON)
        BrushKind.Marker -> Brush.createWithColorIntArgb(marker, color, size, EPSILON)
        BrushKind.Highlighter -> Brush.createWithColorIntArgb(highlighter, (color and 0x00FFFFFF) or HIGHLIGHTER_ALPHA, size, EPSILON)
    }

    /** Smallest distinguishable distance in world units. */
    private const val EPSILON = 0.1f
}

/** A committed stroke: the stored data plus its ink geometry, ready to draw and hit-test. */
class InkStroke(val stored: StoredStroke, val stroke: Stroke) {
    /** `[left, top, right, bottom]` in world units, padded by half the brush size. */
    val bounds: FloatArray = StrokeGeometry.bounds(stored.xy).also { b ->
        val pad = stored.size / 2
        b[0] -= pad; b[1] -= pad; b[2] += pad; b[3] += pad
    }

    companion object {
        fun from(stored: StoredStroke): InkStroke = InkStroke(stored, stored.toStroke())
    }
}

fun StoredStroke.toStroke(): Stroke {
    val tool = InputToolType.fromInt(toolType)
    val batch = MutableStrokeInputBatch()
    for (i in t.indices) {
        batch.add(
            StrokeInput.create(
                x = xy[i * 2],
                y = xy[i * 2 + 1],
                elapsedTimeMillis = t[i],
                toolType = tool,
                strokeUnitLengthCm = StrokeInput.NO_STROKE_UNIT_LENGTH,
                pressure = pressure?.get(i) ?: StrokeInput.NO_PRESSURE,
                tiltRadians = tilt?.get(i) ?: StrokeInput.NO_TILT,
                orientationRadians = orientation?.get(i) ?: StrokeInput.NO_ORIENTATION,
            )
        )
    }
    return Stroke(Brushes.create(brush, color, size), batch)
}
