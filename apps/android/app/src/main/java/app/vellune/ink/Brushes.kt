package app.vellune.ink

import android.graphics.Color
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import app.vellune.data.InkStroke
import app.vellune.data.PenPoint
import app.vellune.data.PenTool

object Brushes {
    private val pen = StockBrushes.pressurePen()
    private val highlighter = StockBrushes.highlighter()
    val pencil: BrushFamily = StockBrushes.pencilUnstable

    // Size range per tool in page units, and its default.
    fun sizeRange(tool: PenTool) = when (tool) {
        PenTool.Pen -> 1f..24f
        PenTool.Pencil -> 1f..16f
        PenTool.Highlighter -> 8f..48f
    }

    fun defaultSize(tool: PenTool) = when (tool) {
        PenTool.Pen -> 4f
        PenTool.Pencil -> 3f
        PenTool.Highlighter -> 22f
    }

    fun family(tool: PenTool) = when (tool) {
        PenTool.Pen -> pen
        PenTool.Pencil -> pencil
        PenTool.Highlighter -> highlighter
    }

    fun brush(tool: PenTool, color: Int, size: Float, dark: Boolean): Brush =
        Brush.createWithColorIntArgb(family(tool), displayColor(color, dark, tool), size, 0.1f)

    /**
     * Ink keeps its stored color; on a dark page neutral dark ink is shown light (and light ink dark) so a note
     * written in black stays readable. Saturated colors are left alone.
     */
    fun displayColor(color: Int, dark: Boolean, tool: PenTool): Int {
        val hsv = FloatArray(3).also { Color.colorToHSV(color, it) }
        var out = color
        if (dark && hsv[1] < 0.15f && hsv[2] < 0.35f) out = Color.argb(Color.alpha(color), 0xE6, 0xE1, 0xE9)
        // Highlighters are see-through so the writing beneath survives.
        if (tool == PenTool.Highlighter) out = (out and 0x00FFFFFF) or (0x66 shl 24)
        return out
    }

    /** Rebuilds a renderable ink stroke (world coordinates) from stored samples. */
    fun build(data: InkStroke, dark: Boolean): Stroke {
        val batch = MutableStrokeInputBatch()
        val type = InputToolType.fromInt(data.toolType)
        for (p in data.points) {
            batch.add(
                type, p.x, p.y, p.timeMs, StrokeInput.NO_STROKE_UNIT_LENGTH,
                if (p.pressure.isNaN()) StrokeInput.NO_PRESSURE else p.pressure,
                if (p.tilt.isNaN()) StrokeInput.NO_TILT else p.tilt,
                if (p.orientation.isNaN()) StrokeInput.NO_ORIENTATION else p.orientation,
            )
        }
        return Stroke(brush(data.tool, data.color, data.size, dark), batch)
    }

    /** Raw samples of a finished ink stroke, with "no data" stored as NaN for other platforms. */
    fun points(stroke: Stroke): List<PenPoint> {
        val inputs = stroke.inputs
        val scratch = StrokeInput()
        return List(inputs.size) { i ->
            val s = inputs.populate(i, scratch)
            PenPoint(
                s.x, s.y, s.elapsedTimeMillis,
                if (s.hasPressure) s.pressure else Float.NaN,
                if (s.hasTilt) s.tiltRadians else Float.NaN,
                if (s.hasOrientation) s.orientationRadians else Float.NaN,
            )
        }
    }
}
