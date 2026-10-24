package com.aiyu.vellune.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays recorded traces from `src/test/resources/traces`. Every device bug gets a new trace
 * here (CLAUDE.md hard rule 4). The first line of each file says the mode and the expectation.
 */
class TraceReplayTest {

    private fun load(name: String): List<InputEvent> {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("traces/$name.jsonl")) { "missing trace $name" }
        return TraceCodec.decode(stream.bufferedReader().readText())
    }

    private fun run(name: String, mode: InputMode = InputMode.PenOnly): Outcome =
        Outcome(PointerArbiter(ArbiterConfig(mode = mode)).replay(load(name)))

    private class Outcome(val gestures: List<Gesture>) {
        val starts = gestures.filterIsInstance<Gesture.StrokeStart>()
        val ends = gestures.filterIsInstance<Gesture.StrokeEnd>()
        val cancels = gestures.filterIsInstance<Gesture.StrokeCancel>()
        val transforms = gestures.filterIsInstance<Gesture.Transform>()

        fun assertBalanced() = assertEquals("every stroke ends or cancels", starts.size, ends.size + cancels.size)
    }

    @Test
    fun palmBeforePen_leavesOnlyThePenStroke() {
        val o = run("palm_before_pen")
        assertEquals(1, o.starts.size)
        assertEquals(ToolKind.Stylus, o.starts.single().tool)
        assertEquals(1, o.ends.size)
        assertTrue("palm must not pan", o.transforms.isEmpty())
        o.assertBalanced()
    }

    @Test
    fun thumbDuringStroke_isDropped() {
        val o = run("thumb_during_stroke")
        assertEquals(1, o.starts.size)
        assertTrue(o.cancels.isEmpty())
        assertTrue(o.transforms.isEmpty())
        o.assertBalanced()
    }

    @Test
    fun penLiftThenWrist_isLockedOut() {
        val o = run("pen_lift_then_wrist")
        assertEquals(1, o.starts.size)
        assertTrue(o.transforms.isEmpty())
        o.assertBalanced()
    }

    @Test
    fun fastScribble_keepsEverySample() {
        val events = load("fast_scribble")
        val o = Outcome(PointerArbiter().replay(events))
        assertEquals(1, o.starts.size)
        assertEquals(events.count { it.action == InputAction.Move }, o.gestures.count { it is Gesture.StrokeMove })
        o.assertBalanced()
    }

    @Test
    fun hoverInOut_fingerIgnoredWhileHovering_thenPans() {
        val events = load("hover_in_out")
        val o = run("hover_in_out")
        assertTrue(o.starts.isEmpty())
        assertTrue("finger after lockout pans", o.transforms.isNotEmpty())
        // Total pan equals pointer 2's travel up to its last move; pointer 1 (during hover) adds nothing.
        val p2 = events.filter { it.sample.id == 2 && it.action != InputAction.Up }
        assertEquals(p2.last().sample.x - p2.first().sample.x, o.transforms.sumOf { it.panX.toDouble() }.toFloat(), 0.01f)
    }

    @Test
    fun noPressureDevice_fingerDrawsAndOsCancelRemovesPalm() {
        val o = run("no_pressure_device", InputMode.FingerOnly)
        assertEquals(2, o.starts.size)
        assertTrue(o.starts.all { it.tool == ToolKind.Finger })
        assertEquals(1, o.ends.size)
        assertEquals(listOf(Gesture.StrokeCancel(1)), o.cancels)
    }

    @Test
    fun allTraces_neverInkFromFingersInPenOnlyMode() {
        listOf("palm_before_pen", "thumb_during_stroke", "pen_lift_then_wrist", "fast_scribble", "hover_in_out").forEach { name ->
            val o = run(name)
            assertTrue("$name: finger ink in pen-only mode", o.starts.none { it.tool == ToolKind.Finger })
            o.assertBalanced()
        }
    }
}
