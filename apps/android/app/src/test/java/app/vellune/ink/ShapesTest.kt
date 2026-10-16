package app.vellune.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapesTest {
    private fun xy(p: DoubleArray, i: Int) = p[i * 3] to p[i * 3 + 1]

    @Test fun rectangleIsClosedAndEven() {
        val p = shapePoints(ShapeKind.Rectangle, 0f, 0f, 120f, 60f)
        assertEquals(0, p.size % 3)
        assertEquals(xy(p, 0), xy(p, p.size / 3 - 1))
        // Corners are on the path and every pressure is the neutral 0.5.
        val pts = (0 until p.size / 3).map { xy(p, it) }
        assertTrue((120.0 to 60.0) in pts)
        assertTrue((0 until p.size / 3).all { p[it * 3 + 2] == 0.5 })
    }

    @Test fun ellipseStaysInsideItsBox() {
        val p = shapePoints(ShapeKind.Ellipse, 10f, 20f, 110f, 80f)
        for (i in 0 until p.size / 3) {
            val (x, y) = xy(p, i)
            assertTrue(x in 9.99..110.01 && y in 19.99..80.01)
        }
    }

    @Test fun arrowEndsOnABarbNearTheTip() {
        val p = shapePoints(ShapeKind.Arrow, 0f, 0f, 100f, 0f)
        val (ex, _) = xy(p, p.size / 3 - 1)
        assertTrue("barb points back along the shaft", ex < 100.0 && ex > 70.0)
        assertTrue((0 until p.size / 3).any { xy(p, it) == (100.0 to 0.0) })
    }
}
