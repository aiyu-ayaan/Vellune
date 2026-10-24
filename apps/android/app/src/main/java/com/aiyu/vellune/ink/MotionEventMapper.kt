package com.aiyu.vellune.ink

import android.os.Build
import android.view.MotionEvent
import com.aiyu.vellune.core.input.InputAction
import com.aiyu.vellune.core.input.InputClassifier
import com.aiyu.vellune.core.input.InputEvent
import com.aiyu.vellune.core.input.PointerSample

/**
 * Flattens a batched [MotionEvent] into per-pointer [InputEvent]s, historical samples first,
 * so fast strokes keep every point (docs/05 "InputClassifier").
 */
object MotionEventMapper {

    fun map(ev: MotionEvent, out: MutableList<InputEvent>) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                out += InputEvent(InputAction.Down, sample(ev, ev.actionIndex, -1))
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until ev.historySize) {
                    for (p in 0 until ev.pointerCount) out += InputEvent(InputAction.Move, sample(ev, p, h))
                }
                for (p in 0 until ev.pointerCount) out += InputEvent(InputAction.Move, sample(ev, p, -1))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                out += InputEvent(InputAction.Up, sample(ev, ev.actionIndex, -1), canceled = isCanceled(ev))
            MotionEvent.ACTION_CANCEL ->
                for (p in 0 until ev.pointerCount) out += InputEvent(InputAction.Cancel, sample(ev, p, -1))
            MotionEvent.ACTION_HOVER_ENTER -> out += InputEvent(InputAction.HoverEnter, sample(ev, 0, -1))
            MotionEvent.ACTION_HOVER_MOVE -> out += InputEvent(InputAction.HoverMove, sample(ev, 0, -1))
            MotionEvent.ACTION_HOVER_EXIT -> out += InputEvent(InputAction.HoverExit, sample(ev, 0, -1))
        }
    }

    /** `FLAG_CANCELED` on a pointer-up (API 33+): the system decided this touch was accidental. */
    private fun isCanceled(ev: MotionEvent): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ev.flags and MotionEvent.FLAG_CANCELED != 0

    /** [h] = historical index, or -1 for the current sample. */
    private fun sample(ev: MotionEvent, p: Int, h: Int): PointerSample {
        val cur = h < 0
        return PointerSample(
            id = ev.getPointerId(p),
            tool = InputClassifier.classify(ev.getToolType(p), ev.buttonState),
            x = if (cur) ev.getX(p) else ev.getHistoricalX(p, h),
            y = if (cur) ev.getY(p) else ev.getHistoricalY(p, h),
            tMs = if (cur) ev.eventTime else ev.getHistoricalEventTime(h),
            pressure = if (cur) ev.getPressure(p) else ev.getHistoricalPressure(p, h),
            tilt = if (cur) ev.getAxisValue(MotionEvent.AXIS_TILT, p) else ev.getHistoricalAxisValue(MotionEvent.AXIS_TILT, p, h),
            orientation = if (cur) ev.getOrientation(p) else ev.getHistoricalOrientation(p, h),
            touchMajor = if (cur) ev.getTouchMajor(p) else ev.getHistoricalTouchMajor(p, h),
        )
    }
}
