package com.aiyu.vellune.core.ink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class StrokeGeometryTest {

    @Test
    fun distanceToPolyline_usesNearestSegment() {
        val pts = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f)
        assertEquals(3f, StrokeGeometry.distanceToPolyline(pts, 5f, 3f), 1e-4f)
        assertEquals(2f, StrokeGeometry.distanceToPolyline(pts, 12f, 5f), 1e-4f)
        assertEquals(5f, StrokeGeometry.distanceToPolyline(pts, -3f, -4f), 1e-4f)
    }

    @Test
    fun singlePointIsADot() {
        assertEquals(5f, StrokeGeometry.distanceToPolyline(floatArrayOf(0f, 0f), 3f, 4f), 1e-4f)
    }

    @Test
    fun bounds() {
        assertArrayEquals(floatArrayOf(-1f, 2f, 4f, 9f), StrokeGeometry.bounds(floatArrayOf(4f, 2f, -1f, 9f)), 0f)
    }
}
