package com.aiyu.vellune.core.ink

import kotlin.math.hypot

/** Geometry on flat `[x0, y0, x1, y1, …]` point arrays in world units. */
object StrokeGeometry {

    /** Shortest distance from (px, py) to the polyline; a single point counts as a dot. */
    fun distanceToPolyline(points: FloatArray, px: Float, py: Float): Float {
        require(points.size >= 2 && points.size % 2 == 0) { "points must hold x,y pairs" }
        if (points.size == 2) return hypot(px - points[0], py - points[1])
        var best = Float.MAX_VALUE
        var i = 0
        while (i + 3 < points.size) {
            val d = distanceToSegment(px, py, points[i], points[i + 1], points[i + 2], points[i + 3])
            if (d < best) best = d
            i += 2
        }
        return best
    }

    fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** Axis-aligned bounds as `[left, top, right, bottom]`. */
    fun bounds(points: FloatArray): FloatArray {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        var i = 0
        while (i + 1 < points.size) {
            l = minOf(l, points[i]); r = maxOf(r, points[i])
            t = minOf(t, points[i + 1]); b = maxOf(b, points[i + 1])
            i += 2
        }
        return floatArrayOf(l, t, r, b)
    }
}
