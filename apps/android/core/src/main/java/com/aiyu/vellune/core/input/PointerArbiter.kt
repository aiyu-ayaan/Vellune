package com.aiyu.vellune.core.input

import kotlin.math.hypot

enum class InputMode { PenOnly, PenAndFinger, FingerOnly }

/** Tunables from docs/05 "PointerArbiter". Distances are view pixels; the app derives them from density. */
data class ArbiterConfig(
    val mode: InputMode = InputMode.PenOnly,
    val lockoutEnabled: Boolean = true,
    val lockoutMs: Long = 400,
    val palmMajorPx: Float = 72f,
    val commitDelayMs: Long = 40,
    val slopPx: Float = 12f,
)

/** What the canvas should do. Coordinates are view pixels, exactly as received. */
sealed interface Gesture {
    data class StrokeStart(val sample: PointerSample, val tool: ToolKind) : Gesture
    data class StrokeMove(val sample: PointerSample) : Gesture
    data class StrokeEnd(val sample: PointerSample) : Gesture

    /** Drop the uncommitted stroke of [pointerId]: it was a palm, a thumb or a pinch start. */
    data class StrokeCancel(val pointerId: Int) : Gesture

    /** Incremental view transform: translate by pan, then scale around the focus point. */
    data class Transform(val panX: Float, val panY: Float, val scale: Float, val focusX: Float, val focusY: Float) : Gesture
}

/**
 * Decides which pointer owns the gesture: pen vs finger vs palm (docs/05 rules 1–7).
 * Pure state machine; feed it [InputEvent]s in order and apply the returned [Gesture]s.
 */
class PointerArbiter(var config: ArbiterConfig = ArbiterConfig()) {

    private sealed interface Owner {
        data object None : Owner
        data object Navigate : Owner
        class Pending(val pointerId: Int, val buffer: MutableList<PointerSample>) : Owner
        data class Stroke(val pointerId: Int, val tool: ToolKind) : Owner
    }

    private var owner: Owner = Owner.None
    private val fingers = LinkedHashMap<Int, PointerSample>()
    private val ignored = HashSet<Int>()
    private var penDown = false
    private var penHover = false
    private var penLeftAt = Long.MIN_VALUE / 2

    /** Short state name for the debug HUD. */
    val stateLabel: String
        get() = when (val o = owner) {
            Owner.None -> if (penHover) "Pen hover" else "Idle"
            Owner.Navigate -> if (fingers.size > 1) "Pinch" else "Pan"
            is Owner.Pending -> "Finger pending"
            is Owner.Stroke -> if (o.tool.isPen) "Pen drawing" else "Finger drawing"
        }

    fun reset() {
        owner = Owner.None
        fingers.clear()
        ignored.clear()
        penDown = false
        penHover = false
        penLeftAt = Long.MIN_VALUE / 2
    }

    fun onEvent(event: InputEvent): List<Gesture> {
        val s = event.sample
        return when (event.action) {
            InputAction.HoverEnter, InputAction.HoverMove -> {
                if (s.tool.isPen) penHover = true
                emptyList()
            }
            InputAction.HoverExit -> {
                if (s.tool.isPen) {
                    penHover = false
                    penLeftAt = s.tMs
                }
                emptyList()
            }
            InputAction.Down -> if (s.drawsLikePen) penDown(s) else fingerDown(s)
            InputAction.Move -> if (s.drawsLikePen) penMove(s) else fingerMove(s)
            // Rule 1: an OS-canceled pointer-up is a palm; never commit it.
            InputAction.Up -> if (event.canceled) cancel(s) else if (s.drawsLikePen) penUp(s) else fingerUp(s)
            InputAction.Cancel -> cancel(s)
        }
    }

    private fun penDown(s: PointerSample): List<Gesture> {
        val out = ArrayList<Gesture>(1)
        // Rule 2: the pen takes over; whatever the fingers were doing is dropped.
        when (val o = owner) {
            is Owner.Stroke -> if (o.tool.isPen) return emptyList() else out += Gesture.StrokeCancel(o.pointerId)
            else -> Unit
        }
        ignored += fingers.keys
        fingers.clear()
        if (s.tool.isPen) penDown = true
        owner = Owner.Stroke(s.id, s.tool)
        out += Gesture.StrokeStart(s, s.tool)
        return out
    }

    private fun penMove(s: PointerSample): List<Gesture> {
        val o = owner
        return if (o is Owner.Stroke && o.pointerId == s.id) listOf(Gesture.StrokeMove(s)) else emptyList()
    }

    private fun penUp(s: PointerSample): List<Gesture> {
        if (s.tool.isPen) {
            penDown = false
            penLeftAt = s.tMs
        }
        val o = owner
        if (o !is Owner.Stroke || o.pointerId != s.id) return emptyList()
        owner = Owner.None
        return listOf(Gesture.StrokeEnd(s))
    }

    private fun fingerDown(s: PointerSample): List<Gesture> {
        // Rule 4: contact too large to be a fingertip.
        if (s.touchMajor > config.palmMajorPx) return ignore(s.id)
        if (config.mode != InputMode.FingerOnly) {
            // Rule 2 (thumb cancellation) and rule 3 (wrist lands right after the pen lifts).
            if (penDown || penHover) return ignore(s.id)
            if (config.lockoutEnabled && s.tMs - penLeftAt < config.lockoutMs) return ignore(s.id)
        }
        if ((owner as? Owner.Stroke)?.tool?.isPen == true) return ignore(s.id)

        fingers[s.id] = s
        if (fingers.size == 1) {
            // Rule 5: one finger pans in pen-only mode, otherwise it may draw (rule 6 buffers it first).
            owner = if (config.mode == InputMode.PenOnly) Owner.Navigate else Owner.Pending(s.id, mutableListOf(s))
            return emptyList()
        }
        // Second finger: always navigation.
        val out = when (val o = owner) {
            is Owner.Stroke -> listOf(Gesture.StrokeCancel(o.pointerId))
            else -> emptyList()
        }
        owner = Owner.Navigate
        return out
    }

    private fun fingerMove(s: PointerSample): List<Gesture> {
        if (s.id in ignored || s.id !in fingers) return emptyList()
        if (s.touchMajor > config.palmMajorPx) return becomePalm(s.id)

        val before = Centroid.of(fingers.values)
        fingers[s.id] = s
        return when (val o = owner) {
            is Owner.Pending -> {
                if (o.pointerId != s.id) return emptyList()
                o.buffer += s
                val first = o.buffer.first()
                val moved = hypot(s.x - first.x, s.y - first.y) > config.slopPx
                if (moved || s.tMs - first.tMs >= config.commitDelayMs) commit(o) else emptyList()
            }
            is Owner.Stroke -> if (o.pointerId == s.id) listOf(Gesture.StrokeMove(s)) else emptyList()
            Owner.Navigate -> {
                val after = Centroid.of(fingers.values)
                val scale = if (fingers.size > 1 && before.span > 0f) after.span / before.span else 1f
                listOf(Gesture.Transform(after.x - before.x, after.y - before.y, scale, after.x, after.y))
            }
            Owner.None -> emptyList()
        }
    }

    private fun fingerUp(s: PointerSample): List<Gesture> {
        if (ignored.remove(s.id) || fingers.remove(s.id) == null) return emptyList()
        return when (val o = owner) {
            is Owner.Pending -> if (o.pointerId == s.id) {
                // A quick tap in finger-draw mode leaves a dot.
                commit(o).also { owner = Owner.None } + Gesture.StrokeEnd(s)
            } else emptyList()
            is Owner.Stroke -> if (o.pointerId == s.id) {
                owner = Owner.None
                listOf(Gesture.StrokeEnd(s))
            } else emptyList()
            Owner.Navigate -> {
                if (fingers.isEmpty()) owner = Owner.None
                emptyList()
            }
            Owner.None -> emptyList()
        }
    }

    private fun cancel(s: PointerSample): List<Gesture> {
        if (s.tool.isPen) {
            penDown = false
            penLeftAt = s.tMs
        }
        ignored.remove(s.id)
        fingers.remove(s.id)
        val out = when (val o = owner) {
            is Owner.Stroke -> if (o.pointerId == s.id) {
                owner = Owner.None
                listOf(Gesture.StrokeCancel(s.id))
            } else emptyList()
            is Owner.Pending -> {
                if (o.pointerId == s.id) owner = Owner.None
                emptyList()
            }
            Owner.Navigate -> {
                if (fingers.isEmpty()) owner = Owner.None
                emptyList()
            }
            Owner.None -> emptyList()
        }
        return out
    }

    /** A finger whose contact grew past the palm threshold mid-gesture. */
    private fun becomePalm(id: Int): List<Gesture> {
        fingers.remove(id)
        ignored += id
        val o = owner
        return when {
            o is Owner.Stroke && o.pointerId == id -> {
                owner = Owner.None
                listOf(Gesture.StrokeCancel(id))
            }
            o is Owner.Pending && o.pointerId == id -> {
                owner = Owner.None
                emptyList()
            }
            o is Owner.Navigate && fingers.isEmpty() -> {
                owner = Owner.None
                emptyList()
            }
            else -> emptyList()
        }
    }

    private fun commit(pending: Owner.Pending): List<Gesture> {
        owner = Owner.Stroke(pending.pointerId, ToolKind.Finger)
        val first = pending.buffer.first()
        return buildList {
            add(Gesture.StrokeStart(first, ToolKind.Finger))
            pending.buffer.drop(1).forEach { add(Gesture.StrokeMove(it)) }
        }
    }

    private fun ignore(id: Int): List<Gesture> {
        ignored += id
        return emptyList()
    }

    private val PointerSample.drawsLikePen: Boolean get() = tool.isPen || tool == ToolKind.Mouse

    private data class Centroid(val x: Float, val y: Float, val span: Float) {
        companion object {
            fun of(points: Collection<PointerSample>): Centroid {
                if (points.isEmpty()) return Centroid(0f, 0f, 0f)
                val cx = points.sumOf { it.x.toDouble() }.toFloat() / points.size
                val cy = points.sumOf { it.y.toDouble() }.toFloat() / points.size
                val span = points.sumOf { hypot(it.x - cx, it.y - cy).toDouble() }.toFloat() / points.size
                return Centroid(cx, cy, span)
            }
        }
    }
}

/** Runs a whole trace through a fresh arbiter. */
fun PointerArbiter.replay(events: List<InputEvent>): List<Gesture> {
    reset()
    return events.flatMap(::onEvent)
}
