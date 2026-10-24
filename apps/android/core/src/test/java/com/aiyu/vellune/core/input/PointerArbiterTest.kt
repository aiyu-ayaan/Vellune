package com.aiyu.vellune.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One test per arbiter rule in docs/05, built from tiny synthetic sequences. */
class PointerArbiterTest {

    private fun pen(action: InputAction, x: Float, t: Long, id: Int = 0, tool: ToolKind = ToolKind.Stylus) =
        InputEvent(action, PointerSample(id, tool, x, 0f, t))

    private fun finger(action: InputAction, id: Int, x: Float, t: Long, y: Float = 0f, major: Float = 20f, canceled: Boolean = false) =
        InputEvent(action, PointerSample(id, ToolKind.Finger, x, y, t, touchMajor = major), canceled)

    private fun arbiter(mode: InputMode = InputMode.PenOnly, lockout: Boolean = true) =
        PointerArbiter(ArbiterConfig(mode = mode, lockoutEnabled = lockout))

    @Test
    fun rule1_osCancelDropsCommittedFingerStroke() {
        val a = arbiter(InputMode.FingerOnly)
        val out = a.replay(
            listOf(
                finger(InputAction.Down, 1, 0f, 0),
                finger(InputAction.Move, 1, 50f, 10),
                finger(InputAction.Up, 1, 60f, 20, canceled = true),
            )
        )
        assertTrue(out.first() is Gesture.StrokeStart)
        assertEquals(Gesture.StrokeCancel(1), out.last())
    }

    @Test
    fun rule2_penDownCancelsFingerStrokeAndOwnsGesture() {
        val a = arbiter(InputMode.PenAndFinger)
        val out = a.replay(
            listOf(
                finger(InputAction.Down, 1, 0f, 0),
                finger(InputAction.Move, 1, 40f, 10),
                pen(InputAction.Down, 100f, 20),
                finger(InputAction.Move, 1, 80f, 30),
                pen(InputAction.Up, 120f, 40),
            )
        )
        assertEquals(
            listOf(Gesture.StrokeStart::class, Gesture.StrokeMove::class, Gesture.StrokeCancel::class, Gesture.StrokeStart::class, Gesture.StrokeEnd::class),
            out.map { it::class }
        )
        assertEquals(ToolKind.Stylus, (out[3] as Gesture.StrokeStart).tool)
    }

    @Test
    fun rule3_lockoutBlocksFingerRightAfterPen_thenExpires() {
        val events = listOf(
            pen(InputAction.Down, 0f, 0),
            pen(InputAction.Up, 10f, 100),
            finger(InputAction.Down, 1, 0f, 200),
            finger(InputAction.Move, 1, 30f, 220),
            finger(InputAction.Up, 1, 30f, 240),
            finger(InputAction.Down, 2, 0f, 600),
            finger(InputAction.Move, 2, 30f, 620),
        )
        val out = arbiter().replay(events)
        val pans = out.filterIsInstance<Gesture.Transform>()
        assertEquals("only pointer 2 pans", 1, pans.size)

        val noLockout = arbiter(lockout = false).replay(events).filterIsInstance<Gesture.Transform>()
        assertEquals(2, noLockout.size)
    }

    @Test
    fun rule4_largeContactIsPalm() {
        val out = arbiter(InputMode.FingerOnly).replay(
            listOf(
                finger(InputAction.Down, 1, 0f, 0, major = 200f),
                finger(InputAction.Move, 1, 100f, 50, major = 200f),
                finger(InputAction.Up, 1, 100f, 60, major = 200f),
            )
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun rule4_contactGrowingMidStrokeCancelsIt() {
        val out = arbiter(InputMode.FingerOnly).replay(
            listOf(
                finger(InputAction.Down, 1, 0f, 0),
                finger(InputAction.Move, 1, 50f, 50),
                finger(InputAction.Move, 1, 55f, 60, major = 200f),
            )
        )
        assertEquals(Gesture.StrokeCancel(1), out.last())
    }

    @Test
    fun rule5_twoFingersZoomAroundCentroid() {
        val out = arbiter().replay(
            listOf(
                finger(InputAction.Down, 1, 100f, 0),
                finger(InputAction.Down, 2, 200f, 0),
                finger(InputAction.Move, 2, 300f, 16),
            )
        )
        val t = out.filterIsInstance<Gesture.Transform>().single()
        // Span (mean distance to centroid) grows from 50 to 100.
        assertEquals(2f, t.scale, 1e-4f)
        assertEquals(200f, t.focusX, 1e-4f)
    }

    @Test
    fun rule6_ambiguousFingerIsBufferedThenCommittedOnSlop() {
        val a = arbiter(InputMode.PenAndFinger)
        assertTrue(a.onEvent(finger(InputAction.Down, 1, 0f, 0)).isEmpty())
        assertTrue("inside slop and delay", a.onEvent(finger(InputAction.Move, 1, 3f, 5)).isEmpty())
        val commit = a.onEvent(finger(InputAction.Move, 1, 40f, 10))
        assertEquals(listOf(Gesture.StrokeStart::class, Gesture.StrokeMove::class, Gesture.StrokeMove::class), commit.map { it::class })
    }

    @Test
    fun rule6_secondPointerBeforeCommitDropsBufferedStroke() {
        val out = arbiter(InputMode.PenAndFinger).replay(
            listOf(
                finger(InputAction.Down, 1, 0f, 0),
                finger(InputAction.Move, 1, 2f, 5),
                finger(InputAction.Down, 2, 300f, 8),
                finger(InputAction.Move, 2, 320f, 20),
            )
        )
        assertTrue(out.none { it is Gesture.StrokeStart })
        assertTrue(out.any { it is Gesture.Transform })
    }

    @Test
    fun rule7_ownerCannotBeStolenBySecondPen() {
        val out = arbiter().replay(
            listOf(
                pen(InputAction.Down, 0f, 0, id = 0),
                pen(InputAction.Down, 50f, 5, id = 3),
                pen(InputAction.Move, 60f, 10, id = 3),
                pen(InputAction.Move, 10f, 10, id = 0),
                pen(InputAction.Up, 20f, 20, id = 0),
            )
        )
        assertEquals(listOf(Gesture.StrokeStart::class, Gesture.StrokeMove::class, Gesture.StrokeEnd::class), out.map { it::class })
    }

    @Test
    fun eraserEndStartsEraserStroke() {
        val out = arbiter().replay(listOf(pen(InputAction.Down, 0f, 0, tool = ToolKind.Eraser)))
        assertEquals(ToolKind.Eraser, (out.single() as Gesture.StrokeStart).tool)
    }

    @Test
    fun classifierMapsBarrelButtonToEraser() {
        assertEquals(ToolKind.Eraser, InputClassifier.classify(InputClassifier.TOOL_TYPE_STYLUS, InputClassifier.BUTTON_STYLUS_PRIMARY))
        assertEquals(ToolKind.Stylus, InputClassifier.classify(InputClassifier.TOOL_TYPE_STYLUS, 0))
        assertEquals(ToolKind.Eraser, InputClassifier.classify(InputClassifier.TOOL_TYPE_ERASER, 0))
        assertEquals(ToolKind.Finger, InputClassifier.classify(InputClassifier.TOOL_TYPE_FINGER, 0))
        assertEquals(ToolKind.Unknown, InputClassifier.classify(0, 0))
    }

    @Test
    fun traceCodecRoundTrips() {
        val e = finger(InputAction.Up, 4, 1.5f, 99, canceled = true)
        assertEquals(listOf(e), TraceCodec.decode("# header\n" + TraceCodec.encodeLine(e)))
    }
}
