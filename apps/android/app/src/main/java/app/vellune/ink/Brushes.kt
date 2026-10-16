package app.vellune.ink

import android.graphics.Color
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke as InkStroke
import androidx.ink.strokes.StrokeInput
import app.vellune.core.Stroke

/** Pen tools; stored on the shared stroke as `tool` (web strokes have none and draw as a pen). */
enum class PenTool(val id: String) { Pen("pen"), Pencil("pencil"), Highlighter("highlighter") }

fun toolOf(s: Stroke) = PenTool.entries.firstOrNull { it.id == s.tool } ?: PenTool.Pen

object Brushes {
    private val pen = StockBrushes.pressurePen()
    private val highlighter = StockBrushes.highlighter()
    val pencil: BrushFamily = StockBrushes.pencilUnstable

    fun sizeRange(tool: PenTool) = when (tool) {
        PenTool.Pen -> 1f..24f
        PenTool.Pencil -> 1f..16f
        PenTool.Highlighter -> 8f..48f
    }

    fun defaultSize(tool: PenTool) = when (tool) {
        PenTool.Pen -> 3f
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
     * Ink keeps its stored color; on a dark page neutral dark ink is shown light, so a note written
     * in black stays readable. Highlighters are see-through so the writing beneath survives.
     */
    fun displayColor(color: Int, dark: Boolean, tool: PenTool): Int {
        val hsv = FloatArray(3).also { Color.colorToHSV(color, it) }
        var out = color
        if (dark && hsv[1] < 0.15f && hsv[2] < 0.35f) out = Color.argb(Color.alpha(color), 0xE6, 0xE1, 0xE9)
        if (tool == PenTool.Highlighter) out = (out and 0x00FFFFFF) or (0x66 shl 24)
        return out
    }

    fun parse(hex: String): Int = runCatching { Color.parseColor(hex) }.getOrDefault(0xFF6366F1.toInt())

    fun hex(color: Int) = String.format("#%06x", color and 0xFFFFFF)

    /** Renderable ink stroke (world coordinates) from a shared stroke: flat `[x, y, pressure, ...]`. */
    fun build(s: Stroke, dark: Boolean): InkStroke {
        val batch = MutableStrokeInputBatch()
        val p = s.points
        var t = 0L
        for (i in 0 until p.size / 3) {
            val pressure = p[i * 3 + 2].toFloat()
            batch.add(
                InputToolType.STYLUS, p[i * 3].toFloat(), p[i * 3 + 1].toFloat(), t,
                StrokeInput.NO_STROKE_UNIT_LENGTH,
                if (pressure > 0f) pressure.coerceAtMost(1f) else StrokeInput.NO_PRESSURE,
                StrokeInput.NO_TILT, StrokeInput.NO_ORIENTATION,
            )
            t += 8 // stored strokes carry no timing; even spacing keeps time-based brush effects stable
        }
        val tool = toolOf(s)
        return InkStroke(brush(tool, parse(s.color), s.width.toFloat(), dark), batch)
    }

    /** Flat `[x, y, pressure, ...]` of a finished ink stroke, the shape the web renders. */
    fun points(stroke: InkStroke): DoubleArray {
        val inputs = stroke.inputs
        val scratch = StrokeInput()
        val out = DoubleArray(inputs.size * 3)
        for (i in 0 until inputs.size) {
            val s = inputs.populate(i, scratch)
            out[i * 3] = s.x.toDouble()
            out[i * 3 + 1] = s.y.toDouble()
            out[i * 3 + 2] = if (s.hasPressure) s.pressure.toDouble() else 0.5
        }
        return out
    }
}
